package com.smartglasses.app.ai

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

sealed interface ModelStatus {
    /** No usable model file. [message] explains a failed import/download, if any. */
    data class Missing(val message: String? = null) : ModelStatus
    data class Importing(val progress: Float, val source: String) : ModelStatus
    data class Verifying(val progress: Float) : ModelStatus
    data class Ready(
        val version: String,
        val sizeBytes: Long,
        val sha256: String,
        val path: String,
        /** True for the developer drop-in file, used where it is instead of copied. */
        val inPlace: Boolean,
    ) : ModelStatus
    /** A model file exists but is the wrong size or fails its SHA-256 check. */
    data class Corrupt(val reason: String, val path: String) : ModelStatus
}

/** Remembers which files already passed SHA-256, so a 2.6 GB file isn't re-hashed on every launch. */
interface VerifiedCache {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
}

/** Something the user picked in the system file picker (or any stream with a name). */
class ImportSource(val displayName: String, val sizeBytes: Long?, val open: () -> InputStream)

/**
 * Finds, imports, verifies and deletes the on-device model. The model is never in
 * the APK. Where it can come from, in the order [refresh] checks:
 *  1. developer drop-in: `<external files>/models/<fileName>` (e.g. `adb push`), used in place;
 *  2. app storage: `<files>/models/<fileName>`, filled by [import] or [adoptDownload].
 *
 * Copies go to a temp file and are renamed only after size + SHA-256 match, so a
 * half-copied file is never mistaken for a good one.
 */
class ModelStore(
    val spec: ModelSpec,
    private val internalDir: File,
    private val dropInDir: File?,
    private val cache: VerifiedCache,
    private val freeSpace: (File) -> Long = { it.usableSpace },
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _status = MutableStateFlow<ModelStatus>(ModelStatus.Missing())
    val status: StateFlow<ModelStatus> = _status.asStateFlow()
    private val lock = Mutex()

    val internalFile: File get() = File(internalDir, spec.fileName)
    val dropInFile: File? get() = dropInDir?.let { File(it, spec.fileName) }
    private val importTemp: File get() = File(internalDir, spec.fileName + ".importing")
    /** Where the in-app downloader writes; kept across attempts so downloads can resume. */
    val downloadTemp: File get() = File(internalDir, spec.fileName + ".download")

    /** Bytes needed in app storage to import the model. */
    val requiredFreeBytes: Long get() = spec.sizeBytes + SPACE_MARGIN

    /** Re-checks what is on disk. Hashes a file only if it hasn't been verified before. */
    suspend fun refresh(): ModelStatus = lock.withLock { withContext(io) { resolve() } }

    private suspend fun resolve(): ModelStatus {
        importTemp.delete()  // left over from an import that was interrupted
        val candidates = listOfNotNull(dropInFile?.let { it to true }, internalFile to false)
        for ((file, inPlace) in candidates) {
            if (!file.exists()) continue
            if (file.length() != spec.sizeBytes) {
                return set(ModelStatus.Corrupt(
                    "The model file is ${gb(file.length())}, expected ${gb(spec.sizeBytes)}. It may be incomplete.", file.path))
            }
            val key = cacheKey(file)
            val known = cache.get(key)
            val sha = if (known != null) known else {
                set(ModelStatus.Verifying(0f))
                val h = file.inputStream().use { sha256(it, file.length()) { p -> _status.value = ModelStatus.Verifying(p) } }
                if (h == spec.sha256) cache.put(key, h)
                h
            }
            if (sha != spec.sha256) {
                return set(ModelStatus.Corrupt("The model file failed its integrity check (SHA-256 mismatch).", file.path))
            }
            return set(ModelStatus.Ready(spec.version, file.length(), sha, file.path, inPlace))
        }
        return set(ModelStatus.Missing())
    }

    /**
     * Copies [source] into app storage, hashing while copying, then verifies.
     * On any failure the store goes back to Missing with the reason.
     */
    suspend fun import(source: ImportSource): ModelStatus = lock.withLock {
        withContext(io) {
            try {
                source.sizeBytes?.let { size ->
                    if (size != spec.sizeBytes) {
                        return@withContext set(ModelStatus.Missing(
                            "\"${source.displayName}\" is ${gb(size)}, but ${spec.name} is ${gb(spec.sizeBytes)}. Is this the right file?"))
                    }
                }
                internalDir.mkdirs()
                val free = freeSpace(internalDir) + (if (internalFile.exists()) internalFile.length() else 0)
                if (free < requiredFreeBytes) {
                    return@withContext set(ModelStatus.Missing(
                        "Not enough free storage: need ${gb(requiredFreeBytes)}, have ${gb(free)}."))
                }
                set(ModelStatus.Importing(0f, source.displayName))
                val digest = MessageDigest.getInstance("SHA-256")
                var copied = 0L
                importTemp.outputStream().use { out ->
                    source.open().use { input ->
                        copyLoop(input, spec.sizeBytes) { buf, n ->
                            out.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            copied += n
                            _status.value = ModelStatus.Importing((copied.toFloat() / spec.sizeBytes).coerceAtMost(1f), source.displayName)
                        }
                    }
                }
                val sha = digest.digest().toHex()
                when {
                    copied != spec.sizeBytes -> fail("The copy stopped early (${gb(copied)} of ${gb(spec.sizeBytes)}).")
                    sha != spec.sha256 -> fail("That file is not the expected model (SHA-256 mismatch).")
                    else -> {
                        internalFile.delete()
                        check(importTemp.renameTo(internalFile)) { "Could not move the model into place" }
                        cache.put(cacheKey(internalFile), sha)
                        set(ModelStatus.Ready(spec.version, spec.sizeBytes, sha, internalFile.path, inPlace = false))
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                importTemp.delete()
                _status.value = ModelStatus.Missing("Import cancelled.")
                throw e
            } catch (e: Exception) {
                fail("Import failed: ${e.message ?: e::class.simpleName}")
            }
        }
    }

    /** Verifies a finished download in [downloadTemp] and moves it into place. */
    suspend fun adoptDownload(): ModelStatus = lock.withLock {
        withContext(io) {
            val tmp = downloadTemp
            if (!tmp.exists() || tmp.length() != spec.sizeBytes) {
                return@withContext set(ModelStatus.Missing("The download is incomplete; try again to resume it."))
            }
            set(ModelStatus.Verifying(0f))
            val sha = tmp.inputStream().use { sha256(it, tmp.length()) { p -> _status.value = ModelStatus.Verifying(p) } }
            if (sha != spec.sha256) {
                tmp.delete()  // can't resume a corrupt file
                return@withContext set(ModelStatus.Missing("The download was corrupted (SHA-256 mismatch). Please download again."))
            }
            internalFile.delete()
            check(tmp.renameTo(internalFile)) { "Could not move the model into place" }
            cache.put(cacheKey(internalFile), sha)
            set(ModelStatus.Ready(spec.version, spec.sizeBytes, sha, internalFile.path, inPlace = false))
        }
    }

    /** Deletes every copy of the model (app storage, drop-in, temp files). */
    suspend fun delete(): ModelStatus = lock.withLock {
        withContext(io) {
            listOfNotNull(internalFile, dropInFile, importTemp, downloadTemp).forEach { f ->
                cache.remove(cacheKey(f))
                f.delete()
            }
            set(ModelStatus.Missing())
        }
    }

    /** Path to load, or null if the model isn't verified. */
    fun readyPath(): String? = (_status.value as? ModelStatus.Ready)?.path

    /** Bytes the model currently takes in app-owned storage (internal copy + temps + drop-in). */
    fun storageUsedBytes(): Long =
        listOfNotNull(internalFile, dropInFile, importTemp, downloadTemp).filter { it.exists() }.sumOf { it.length() }

    private fun fail(message: String): ModelStatus {
        importTemp.delete()
        return set(ModelStatus.Missing(message))
    }

    private fun set(s: ModelStatus): ModelStatus { _status.value = s; return s }

    private fun cacheKey(f: File) = "verified:${f.path}:${f.length()}:${f.lastModified()}"

    companion object {
        const val SPACE_MARGIN = 200L * 1024 * 1024
        private const val BUF = 4 * 1024 * 1024

        fun gb(bytes: Long) = "%.2f GB".format(java.util.Locale.US, bytes / 1e9)

        internal suspend fun copyLoop(input: InputStream, total: Long, sink: (ByteArray, Int) -> Unit) {
            val buf = ByteArray(BUF)
            var sinceCheck = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                sink(buf, n)
                sinceCheck += n
                if (sinceCheck >= 32L * 1024 * 1024) { sinceCheck = 0; coroutineContext.ensureActive() }
            }
        }

        suspend fun sha256(input: InputStream, total: Long, onProgress: (Float) -> Unit = {}): String {
            val digest = MessageDigest.getInstance("SHA-256")
            var done = 0L
            var lastPct = -1
            copyLoop(input, total) { buf, n ->
                digest.update(buf, 0, n)
                done += n
                val pct = if (total > 0) (done * 100 / total).toInt() else 0
                if (pct != lastPct) { lastPct = pct; onProgress(pct / 100f) }
            }
            return digest.digest().toHex()
        }

        private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    }
}

package com.smartglasses.app.ai

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Download progress for the UI (withDownload flavor only). */
data class DownloadProgress(val downloaded: Long, val total: Long, val error: String? = null, val running: Boolean = true)

/**
 * App-scoped model operations started from the UI (import, download, delete), so
 * they keep running if the screen rotates or the user leaves the app.
 */
class ModelActions(private val context: Context, private val scope: CoroutineScope, val store: ModelStore) {

    private var job: Job? = null
    private val _download = MutableStateFlow<DownloadProgress?>(null)
    val download: StateFlow<DownloadProgress?> = _download.asStateFlow()

    val busy: Boolean get() = job?.isActive == true

    /** Copies the file the user picked with ACTION_OPEN_DOCUMENT. No storage permission needed. */
    fun importFrom(uri: Uri) {
        if (busy) return
        job = scope.launch {
            val (name, size) = queryNameAndSize(uri)
            store.import(ImportSource(name, size) {
                context.contentResolver.openInputStream(uri) ?: error("Couldn't open \"$name\"")
            })
        }
    }

    fun startDownload(wifiOnly: Boolean) {
        if (busy || !ModelDownloader.AVAILABLE) return
        job = scope.launch {
            _download.value = DownloadProgress(store.downloadTemp.length(), store.spec.sizeBytes)
            try {
                ModelDownloader.download(context, store, wifiOnly) { d, t -> _download.value = DownloadProgress(d, t) }
                _download.value = null
            } catch (e: kotlinx.coroutines.CancellationException) {
                _download.value = DownloadProgress(store.downloadTemp.length(), store.spec.sizeBytes, "Paused", running = false)
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "download failed", e)
                _download.value = DownloadProgress(store.downloadTemp.length(), store.spec.sizeBytes,
                    e.message ?: "Download failed", running = false)
            }
        }
    }

    fun cancel() { job?.cancel() }

    fun delete() {
        job?.cancel()
        job = scope.launch { store.delete(); _download.value = null }
    }

    /** Re-checks storage (e.g. after an `adb push` to the drop-in folder). */
    fun rescan() {
        if (busy) return
        job = scope.launch { store.refresh() }
    }

    private fun queryNameAndSize(uri: Uri): Pair<String, Long?> {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val name = c.getString(0) ?: "selected file"
                val size = if (c.isNull(1)) null else c.getLong(1)
                return name to size
            }
        }
        return "selected file" to null
    }

    companion object {
        private const val TAG = "ModelActions"

        fun loadSpec(context: Context): ModelSpec =
            ModelSpec.parseManifest(context.assets.open("model-manifest.json").bufferedReader().use { it.readText() }).first()
    }
}

/** SharedPreferences-backed [VerifiedCache]. */
class PrefsVerifiedCache(private val prefs: SharedPreferences) : VerifiedCache {
    override fun get(key: String) = prefs.getString(key, null)
    override fun put(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    override fun remove(key: String) {
        // Keys include size + mtime; drop every entry for that path.
        val path = key.removePrefix("verified:").substringBeforeLast(':').substringBeforeLast(':')
        val e = prefs.edit()
        prefs.all.keys.filter { it.startsWith("verified:$path:") }.forEach(e::remove)
        e.apply()
    }
}

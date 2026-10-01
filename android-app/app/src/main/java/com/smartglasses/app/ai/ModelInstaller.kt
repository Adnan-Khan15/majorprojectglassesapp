package com.smartglasses.app.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * The model ships inside the APK as uncompressed 256 MB asset parts (the Android
 * build can't package one asset over 2 GB). LiteRT-LM needs a real file path, so
 * on first launch the parts are joined into one file in internal storage, once.
 * The copy is written to a temp file and renamed, so an interrupted copy is
 * never mistaken for a complete one.
 */
class ModelInstaller(private val context: Context) {

    val modelFile = File(context.filesDir, "models/$MODEL_NAME")

    private val parts: List<String> by lazy {
        context.assets.list(ASSET_DIR).orEmpty()
            .filter { it.startsWith("$MODEL_NAME.part") }
            .sorted()
            .map { "$ASSET_DIR/$it" }
            .also { check(it.isNotEmpty()) { "AI model is missing from this build" } }
    }

    private val assetSize: Long by lazy {
        parts.sumOf { part -> context.assets.openFd(part).use { it.length } }
    }

    fun isInstalled(): Boolean = modelFile.exists() && modelFile.length() == assetSize

    /** Copies the model if needed, reporting progress in 0..1. Returns the model path. */
    suspend fun install(onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val total = assetSize
        if (modelFile.exists() && modelFile.length() == total) return@withContext modelFile

        modelFile.parentFile?.mkdirs()
        val free = modelFile.parentFile!!.usableSpace
        if (free < total + SPACE_MARGIN) {
            throw IllegalStateException(
                "Not enough free storage to unpack the AI model: need %.1f GB, have %.1f GB"
                    .format((total + SPACE_MARGIN) / 1e9, free / 1e9)
            )
        }
        val tmp = File(modelFile.path + ".part")
        tmp.outputStream().use { output ->
            val buf = ByteArray(8 * 1024 * 1024)
            var copied = 0L
            var lastReported = -1
            for (part in parts) {
                context.assets.open(part).use { input ->
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        copied += n
                        val pct = (copied * 100 / total).toInt()
                        if (pct != lastReported) { lastReported = pct; onProgress(copied.toFloat() / total) }
                    }
                }
            }
        }
        check(tmp.length() == total) { "Model copy incomplete (${tmp.length()} of $total bytes)" }
        check(tmp.renameTo(modelFile)) { "Could not finalise model file" }
        modelFile
    }

    companion object {
        const val MODEL_NAME = "gemma-4-E2B-it.litertlm"
        private const val ASSET_DIR = "models"
        private const val SPACE_MARGIN = 300L * 1024 * 1024
    }
}

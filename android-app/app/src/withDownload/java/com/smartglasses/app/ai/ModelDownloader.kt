package com.smartglasses.app.ai

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * `withDownload` flavor: resumable HTTP download of the model from the manifest URL
 * (Hugging Face; no account needed). Uses HTTP Range to continue a partial file,
 * then hands it to [ModelStore.adoptDownload] for the SHA-256 check.
 *
 * Google Drive links are deliberately NOT supported here: large Drive files answer
 * with an HTML "can't scan for viruses" page instead of the file. For Drive, download
 * in the browser and use Import from file.
 */
object ModelDownloader {
    const val AVAILABLE = true

    suspend fun download(
        context: Context,
        store: ModelStore,
        wifiOnly: Boolean,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): ModelStatus = withContext(Dispatchers.IO) {
        val url = store.spec.url ?: error("The model manifest has no download URL")
        if (wifiOnly && !onUnmeteredNetwork(context)) {
            error("Not on Wi-Fi. The model is ${ModelStore.gb(store.spec.sizeBytes)}; connect to Wi-Fi or allow mobile data in Settings.")
        }
        val tmp = store.downloadTemp
        tmp.parentFile?.mkdirs()
        val total = store.spec.sizeBytes
        val free = tmp.parentFile!!.usableSpace
        if (free < total - tmp.length() + ModelStore.SPACE_MARGIN) {
            error("Not enough free storage: need ${ModelStore.gb(total - tmp.length() + ModelStore.SPACE_MARGIN)}, have ${ModelStore.gb(free)}.")
        }
        if (tmp.length() > total) tmp.delete()

        var attempts = 0
        while (tmp.length() < total) {
            coroutineContext.ensureActive()
            val have = tmp.length()
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "SmartGlassesApp")
                if (have > 0) setRequestProperty("Range", "bytes=$have-")
            }
            try {
                val code = conn.responseCode
                val append = when {
                    code == HttpURLConnection.HTTP_PARTIAL -> true
                    code == HttpURLConnection.HTTP_OK -> false  // server ignored Range: start over
                    else -> error("Download failed: HTTP $code")
                }
                var done = if (append) have else 0L
                conn.inputStream.use { input ->
                    FileOutputStream(tmp, append).use { out ->
                        val buf = ByteArray(1 shl 20)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            onProgress(done, total)
                            coroutineContext.ensureActive()
                        }
                    }
                }
                attempts = 0
            } catch (e: java.io.IOException) {
                // Network blip: retry from where we are, a few times, then give up (resumable later).
                if (++attempts > 5) throw IllegalStateException("Download interrupted: ${e.message}. Tap Download again to resume.", e)
                kotlinx.coroutines.delay(2_000L * attempts)
            } finally {
                conn.disconnect()
            }
        }
        store.adoptDownload()
    }

    private fun onUnmeteredNetwork(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
}

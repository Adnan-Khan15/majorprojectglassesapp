package com.smartglasses.app.ai

import android.content.Context

/**
 * `offline` flavor: no in-app download. This build has no network permission at all
 * (see src/offline/AndroidManifest.xml); the model is imported from a file instead.
 */
object ModelDownloader {
    const val AVAILABLE = false

    @Suppress("UNUSED_PARAMETER")
    suspend fun download(context: Context, store: ModelStore, wifiOnly: Boolean, onProgress: (downloaded: Long, total: Long) -> Unit): ModelStatus =
        error("This build has no network access. Import the model from a file instead.")
}

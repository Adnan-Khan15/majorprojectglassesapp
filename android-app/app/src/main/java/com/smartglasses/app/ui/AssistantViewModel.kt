package com.smartglasses.app.ui

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.smartglasses.app.glassesApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Thin UI adapter; the pipeline and its state live in the process-scoped AssistantSession. */
class AssistantViewModel(app: Application) : AndroidViewModel(app) {
    private val describer = app.glassesApp.describer
    private val session = app.glassesApp.session

    val modelState = describer.state
    val run = session.run

    fun describePhoto(uri: Uri) {
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }.onFailure { Log.w("AssistantViewModel", "read failed", it) }.getOrNull()
            }
            if (bytes != null) session.describeGalleryPhoto(bytes) else session.galleryPhotoUnreadable()
        }
    }

    fun retry() = session.retry()
    fun retryModel() = describer.retry()
}

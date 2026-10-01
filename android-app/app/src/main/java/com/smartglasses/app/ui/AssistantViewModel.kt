package com.smartglasses.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.smartglasses.app.ai.Description
import com.smartglasses.app.ai.SceneDescriber
import com.smartglasses.app.glassesApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface TestRun {
    data object Idle : TestRun
    data class Running(val preview: Bitmap) : TestRun
    data class Done(val preview: Bitmap, val result: Description) : TestRun
    data class Error(val preview: Bitmap?, val message: String) : TestRun
}

/** Survives rotation, so an in-flight description isn't lost or restarted. */
class AssistantViewModel(app: Application) : AndroidViewModel(app) {
    private val describer = app.glassesApp.describer
    val modelState = describer.state

    private val _test = MutableStateFlow<TestRun>(TestRun.Idle)
    val test: StateFlow<TestRun> = _test.asStateFlow()

    private var lastJpeg: ByteArray? = null
    private var job: Job? = null

    fun describePhoto(uri: Uri) {
        job?.cancel()
        job = viewModelScope.launch {
            val jpeg = withContext(Dispatchers.IO) {
                getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }
            if (jpeg == null) { _test.value = TestRun.Error(null, "Couldn't open that photo"); return@launch }
            lastJpeg = jpeg
            run(jpeg)
        }
    }

    fun retry() { lastJpeg?.let { jpeg -> job?.cancel(); job = viewModelScope.launch { run(jpeg) } } }

    fun retryModel() = describer.retry()

    private suspend fun run(jpeg: ByteArray) {
        val preview = withContext(Dispatchers.Default) { SceneDescriber.decodeDownscaled(jpeg, 512) }
        _test.value = TestRun.Running(preview)
        _test.value = try {
            TestRun.Done(preview, describer.describe(jpeg, distanceMm = null))
        } catch (e: TimeoutCancellationException) {
            TestRun.Error(preview, "The model took too long (over 30 s). Try again.")
        } catch (e: Exception) {
            TestRun.Error(preview, e.message ?: "Something went wrong")
        }
    }
}

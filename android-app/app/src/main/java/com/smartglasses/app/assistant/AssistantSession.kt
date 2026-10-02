package com.smartglasses.app.assistant

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.smartglasses.app.ai.Description
import com.smartglasses.app.ai.SceneDescriber
import com.smartglasses.app.ble.CaptureEvent
import com.smartglasses.app.ble.GlassesLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Source { GLASSES, GALLERY }

/** Per-stage times for one run, in ms. Stages that don't apply are null. */
data class RunTiming(
    val transferMs: Long?,      // BLE image transfer (glasses only)
    val ocrMs: Long,
    val modelMs: Long,
    val speechStartMs: Long?,   // until audio actually started; null if speech failed
    val totalMs: Long,          // photo header arriving (or photo picked) -> audio starting
)

sealed interface AssistantRun {
    data object Idle : AssistantRun
    data class Receiving(val receivedBytes: Int, val totalBytes: Int, val distanceMm: Int?) : AssistantRun
    data class Describing(val source: Source, val preview: Bitmap, val distanceMm: Int?) : AssistantRun
    data class Done(
        val source: Source,
        val preview: Bitmap,
        val distanceMm: Int?,
        val result: Description,
        val timing: RunTiming,
        val announcer: String,
        val speechError: String?,
        val sentToGlasses: Boolean?,  // null = not applicable (gallery photo)
    ) : AssistantRun
    data class Error(val source: Source, val preview: Bitmap?, val message: String, val canRetry: Boolean) : AssistantRun
}

/**
 * The one pipeline every photo goes through, whether it came from the glasses
 * or the gallery: SceneDescriber -> ResultAnnouncer (+ RESULT_TEXT for glasses).
 * Process-scoped, so a capture in flight survives rotation and backgrounding.
 */
class AssistantSession(
    private val scope: CoroutineScope,
    private val link: GlassesLink,
    private val describer: SceneDescriber,
    private val announcer: ResultAnnouncer,
) {
    private val _run = MutableStateFlow<AssistantRun>(AssistantRun.Idle)
    val run: StateFlow<AssistantRun> = _run.asStateFlow()

    private var job: Job? = null
    private var lastInput: Input? = null

    private data class Input(val jpeg: ByteArray, val source: Source, val distanceMm: Int?, val transferMs: Long?)

    fun start() {
        scope.launch { link.captures.collect(::onCapture) }
    }

    private fun onCapture(e: CaptureEvent) {
        when (e) {
            is CaptureEvent.Started -> {
                job?.cancel()  // a new button press supersedes whatever was running
                _run.value = AssistantRun.Receiving(0, e.totalBytes, e.distanceMm)
            }
            is CaptureEvent.Progress -> (_run.value as? AssistantRun.Receiving)?.let {
                _run.value = it.copy(receivedBytes = e.receivedBytes, totalBytes = e.totalBytes)
            }
            is CaptureEvent.Completed -> process(Input(e.jpeg, Source.GLASSES, e.distanceMm, e.transferMs))
            is CaptureEvent.Failed -> _run.value = AssistantRun.Error(
                Source.GLASSES, null,
                "The photo from the glasses didn't arrive complete (${e.reason}). Press the button again.",
                canRetry = false,
            )
        }
    }

    /** "Try with a photo": same pipeline, no distance sensor behind it. */
    fun describeGalleryPhoto(jpeg: ByteArray) = process(Input(jpeg, Source.GALLERY, distanceMm = null, transferMs = null))

    fun galleryPhotoUnreadable() {
        _run.value = AssistantRun.Error(Source.GALLERY, null, "Couldn't open that photo.", canRetry = false)
    }

    fun retry() { lastInput?.let(::process) }

    private fun process(input: Input) {
        lastInput = input
        job?.cancel()
        val processStart = SystemClock.elapsedRealtime()
        job = scope.launch {
            val preview = withContext(Dispatchers.Default) { SceneDescriber.decodeDownscaled(input.jpeg, PREVIEW_PX) }
            if (!describer.canDescribe) {
                _run.value = AssistantRun.Error(input.source, preview,
                    "The AI model is still loading. Try again when the card says it's ready.", canRetry = true)
                return@launch
            }
            _run.value = AssistantRun.Describing(input.source, preview, input.distanceMm)

            val result = try {
                describer.describe(input.jpeg, input.distanceMm)
            } catch (e: TimeoutCancellationException) {
                _run.value = AssistantRun.Error(input.source, preview, "The model took too long (over 30 s).", canRetry = true)
                return@launch
            } catch (e: Exception) {
                Log.e(TAG, "describe failed", e)
                _run.value = AssistantRun.Error(input.source, preview, e.message ?: "Something went wrong", canRetry = true)
                return@launch
            }

            // Speak and (for the glasses) send RESULT_TEXT at the same time.
            val sent = if (input.source == Source.GLASSES) async { link.sendResultText(result.sentence) } else null
            val speechStart = SystemClock.elapsedRealtime()
            var speechError: String? = null
            val speechMs = try {
                announcer.announce(result.sentence)
            } catch (e: Exception) {
                speechError = e.message ?: "speech failed"
                null
            }
            val timing = RunTiming(
                transferMs = input.transferMs,
                ocrMs = result.ocrMs,
                modelMs = result.modelMs,
                speechStartMs = speechMs,
                // Wall clock, so nothing between stages goes uncounted.
                totalMs = (input.transferMs ?: 0) +
                    (speechStart + (speechMs ?: 0) - processStart),
            )
            Log.d(TAG, "${input.source} run: transfer=${timing.transferMs} ocr=${timing.ocrMs} model=${timing.modelMs} " +
                "describeTotal=${result.totalMs} speechStart=${timing.speechStartMs} total=${timing.totalMs} :: ${result.sentence}")
            _run.value = AssistantRun.Done(
                input.source, preview, input.distanceMm, result, timing,
                announcer.name, speechError, sent?.await(),
            )
        }
    }

    companion object {
        private const val TAG = "AssistantSession"
        private const val PREVIEW_PX = 512
    }
}

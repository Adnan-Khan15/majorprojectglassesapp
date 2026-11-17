package com.smartglasses.app.assistant

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.smartglasses.app.ai.Description
import com.smartglasses.app.ai.SceneDescriber
import com.smartglasses.app.ble.CaptureEvent
import com.smartglasses.app.ble.GlassesLink
import com.smartglasses.app.ble.TofReading
import com.smartglasses.app.metrics.InteractionTimeline
import com.smartglasses.app.metrics.LatencyLog
import com.smartglasses.app.metrics.Stage
import kotlinx.coroutines.CancellationException
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
import java.util.Locale
import java.util.UUID

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
    data class Receiving(val receivedBytes: Int, val totalBytes: Int) : AssistantRun
    data class Describing(val source: Source, val preview: Bitmap) : AssistantRun
    data class Done(
        val source: Source,
        val preview: Bitmap,
        val sentence: String,
        val tof: TofReading?,     // null = no distance sensor behind this photo (gallery)
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
 * or the gallery: SceneDescriber -> sentence (+ distance) -> ResultAnnouncer.
 * Process-scoped, so a capture in flight survives rotation and backgrounding.
 */
class AssistantSession(
    private val scope: CoroutineScope,
    private val link: GlassesLink,
    private val describer: SceneDescriber,
    private val announcer: ResultAnnouncer,
    private val latencyLog: LatencyLog,
) {
    private val _run = MutableStateFlow<AssistantRun>(AssistantRun.Idle)
    val run: StateFlow<AssistantRun> = _run.asStateFlow()

    private var job: Job? = null
    private var lastInput: Input? = null
    /** Timeline of the glasses capture currently arriving (header seen, image not complete yet). */
    private var arriving: InteractionTimeline? = null

    private class Input(
        val jpeg: ByteArray,
        val source: Source,
        val imageId: Int?,         // v2: the ToF reading follows as an event for this id
        val v1Tof: TofReading?,    // v1: the distance came in the header
        val transferMs: Long?,
    )

    private fun newTimeline(source: Source) = InteractionTimeline(
        id = UUID.randomUUID().toString().take(8),
        mode = "tap",
        source = if (source == Source.GLASSES) "glasses" else "gallery",
        clock = SystemClock::elapsedRealtime,
    )

    private fun finish(t: InteractionTimeline, outcome: String, error: String? = null) {
        t.outcome = outcome
        t.put("error", error)
        latencyLog.append(t)
    }

    fun start() {
        scope.launch { link.captures.collect(::onCapture) }
    }

    private fun onCapture(e: CaptureEvent) {
        when (e) {
            is CaptureEvent.Started -> {
                job?.cancel()  // a new button press supersedes whatever was running
                arriving = newTimeline(Source.GLASSES).apply {
                    mark(Stage.IMAGE_HEADER)
                    put("image_bytes", e.totalBytes)
                }
                _run.value = AssistantRun.Receiving(0, e.totalBytes)
            }
            is CaptureEvent.Progress -> (_run.value as? AssistantRun.Receiving)?.let {
                _run.value = it.copy(receivedBytes = e.receivedBytes, totalBytes = e.totalBytes)
            }
            is CaptureEvent.Completed -> {
                val t = arriving ?: newTimeline(Source.GLASSES)
                arriving = null
                t.mark(Stage.IMAGE_RECEIVED)
                t.put("transfer_kbps", "%.1f".format(Locale.US, e.jpeg.size / 1.024 / e.transferMs.coerceAtLeast(1)))
                process(Input(e.jpeg, Source.GLASSES, e.imageId, e.v1Tof, e.transferMs), t)
            }
            is CaptureEvent.Failed -> {
                arriving?.let { finish(it, "transfer_failed", e.reason) }
                arriving = null
                _run.value = AssistantRun.Error(
                    Source.GLASSES, null,
                    "The photo from the glasses didn't arrive complete (${e.reason}). Press the button again.",
                    canRetry = false,
                )
            }
        }
    }

    /** "Try with a photo": same pipeline, no distance sensor behind it. */
    fun describeGalleryPhoto(jpeg: ByteArray) = process(Input(jpeg, Source.GALLERY, null, null, null))

    fun galleryPhotoUnreadable() {
        _run.value = AssistantRun.Error(Source.GALLERY, null, "Couldn't open that photo.", canRetry = false)
    }

    fun retry() { lastInput?.let { process(it) } }

    private fun process(input: Input, timeline: InteractionTimeline? = null) {
        lastInput = input
        job?.cancel()
        val processStart = SystemClock.elapsedRealtime()
        val t = timeline ?: newTimeline(input.source).apply { mark(Stage.IMAGE_RECEIVED, processStart) }
        job = scope.launch {
            val preview = withContext(Dispatchers.Default) { SceneDescriber.decodeDownscaled(input.jpeg, PREVIEW_PX) }
            if (!describer.canDescribe) {
                _run.value = AssistantRun.Error(input.source, preview,
                    "The AI model is still loading. Try again when the card says it's ready.", canRetry = true)
                finish(t, "model_not_ready")
                return@launch
            }
            _run.value = AssistantRun.Describing(input.source, preview)

            // v2: the ToF reading arrives as an event after the image; wait for it in
            // parallel with the model, so it costs no extra time.
            val tofJob = when {
                input.v1Tof != null -> null
                input.imageId != null -> async { link.awaitTof(input.imageId, TOF_WAIT_MS).also { t.mark(Stage.TOF_RECEIVED) } }
                else -> null
            }

            val result = try {
                describer.describe(input.jpeg, t)
            } catch (e: TimeoutCancellationException) {
                _run.value = AssistantRun.Error(input.source, preview, "The model took too long (over 30 s).", canRetry = true)
                finish(t, "model_timeout")
                return@launch
            } catch (e: CancellationException) {
                finish(t, "superseded")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "describe failed", e)
                _run.value = AssistantRun.Error(input.source, preview, e.message ?: "Something went wrong", canRetry = true)
                finish(t, "model_error", e.message)
                return@launch
            }

            val tof: TofReading? = input.v1Tof ?: tofJob?.await()
            tof?.let {
                t.put("distance_mm", it.medianMm)
                t.put("tof_flags", "0x%02x".format(it.flags))
                t.put("tof_samples", "${it.validCount}/${it.sampleCount}")
                t.put("tof_spread_mm", it.spreadMm)
            }
            val sentence = SceneDescriber.compose(result.objectPhrase, tof?.takeIf { it.isValid }?.medianMm)
            t.put("answer", sentence)

            // Speak and (for the glasses) send RESULT_TEXT at the same time.
            val sent = if (input.source == Source.GLASSES) async { link.sendResultText(sentence) } else null
            val speechStart = SystemClock.elapsedRealtime()
            var speechError: String? = null
            val speechMs = try {
                announcer.announce(sentence)
            } catch (e: Exception) {
                speechError = e.message ?: "speech failed"
                null
            }
            speechMs?.let { t.mark(Stage.PLAY_STARTED, speechStart + it) }
            val timing = RunTiming(
                transferMs = input.transferMs,
                ocrMs = result.ocrMs,
                modelMs = result.modelMs,
                speechStartMs = speechMs,
                // Wall clock, so nothing between stages goes uncounted.
                totalMs = (input.transferMs ?: 0) + (speechStart + (speechMs ?: 0) - processStart),
            )
            Log.d(TAG, "${input.source} run: transfer=${timing.transferMs} ocr=${timing.ocrMs} model=${timing.modelMs} " +
                "describeTotal=${result.totalMs} speechStart=${timing.speechStartMs} total=${timing.totalMs} :: $sentence")
            val sentOk = sent?.await()
            finish(t, if (speechError == null) "ok" else "speech_failed", speechError)
            _run.value = AssistantRun.Done(
                input.source, preview, sentence, tof, result, timing,
                announcer.name, speechError, sentOk,
            )
        }
    }

    companion object {
        private const val TAG = "AssistantSession"
        private const val PREVIEW_PX = 512
        private const val TOF_WAIT_MS = 4_000L
    }
}

package com.smartglasses.app.assistant

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.smartglasses.app.ai.AnswerComposer
import com.smartglasses.app.ai.ComposeSettings
import com.smartglasses.app.ai.ModelState
import com.smartglasses.app.ai.SceneDescriber
import com.smartglasses.app.ble.CaptureEvent
import com.smartglasses.app.ble.Frames
import com.smartglasses.app.ble.GlassesEvent
import com.smartglasses.app.ble.GlassesLink
import com.smartglasses.app.ble.LinkState
import com.smartglasses.app.ble.TofReading
import com.smartglasses.app.metrics.InteractionTimeline
import com.smartglasses.app.metrics.LatencyLog
import com.smartglasses.app.metrics.Stage
import com.smartglasses.app.settings.AppSettings
import com.smartglasses.app.speech.SpeechOutput
import com.smartglasses.app.voice.AndroidOnDeviceStt
import com.smartglasses.app.voice.AudioCheck
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID

enum class Source { GLASSES, GALLERY }
enum class Mode { TAP, QUESTION }

/** Per-stage times for one run, in ms. Stages that don't apply are null. */
data class RunTiming(
    val transferMs: Long?,      // BLE image transfer (glasses only)
    val ocrMs: Long,
    val modelMs: Long,
    val speechStartMs: Long?,   // until audio actually started; null if speech failed
    val totalMs: Long,          // photo arriving (or picked) -> audio starting
)

sealed interface AssistantRun {
    data object Idle : AssistantRun
    /** Recording a question with the phone's mic. */
    data class Listening(val level: Float) : AssistantRun
    data class Receiving(val receivedBytes: Int, val totalBytes: Int) : AssistantRun
    data class Describing(val source: Source, val preview: Bitmap, val question: Boolean) : AssistantRun
    data class Done(
        val source: Source,
        val mode: Mode,
        val preview: Bitmap,
        val question: String?,    // what was heard (question mode)
        val sentence: String,     // what was spoken
        val tof: TofReading?,     // null = no distance sensor behind this photo (gallery)
        val engine: String,
        val ocrText: String,
        val timing: RunTiming,
        val spokenOn: String,
        val speechError: String?,
    ) : AssistantRun
    data class Error(val source: Source?, val preview: Bitmap?, val message: String, val canRetry: Boolean) : AssistantRun
}

/** Everything about one finished interaction, for History (M7). */
data class InteractionRecord(
    val timeline: InteractionTimeline,
    val mode: Mode,
    val source: Source,
    val questionVia: String?,  // "glasses mic" | "phone mic" | null
    val heard: String?,
    val sentence: String?,
    val modelRaw: String?,
    val tof: TofReading?,
    val engine: String?,
    val stt: String?,
    val ocrText: String?,
    val jpeg: ByteArray?,
    val error: String?,
)

/**
 * The one pipeline every photo goes through: from the glasses (tap or hold-to-talk
 * question), from the gallery, or with a question asked on the phone's mic.
 * photo (+ question) -> SceneDescriber -> AnswerComposer (+ distance) -> SpeechOutput.
 * Process-scoped, so a run in flight survives rotation and backgrounding.
 * Every failure is also SPOKEN, because the wearer may not see the screen.
 */
class AssistantSession(
    private val scope: CoroutineScope,
    private val link: GlassesLink,
    private val describer: SceneDescriber,
    private val speech: SpeechOutput,
    private val latencyLog: LatencyLog,
    private val settings: AppSettings,
    private val androidStt: AndroidOnDeviceStt,
) {
    private val _run = MutableStateFlow<AssistantRun>(AssistantRun.Idle)
    val run: StateFlow<AssistantRun> = _run.asStateFlow()

    private val _completed = MutableSharedFlow<InteractionRecord>(extraBufferCapacity = 16)
    val completed: SharedFlow<InteractionRecord> = _completed.asSharedFlow()

    private var job: Job? = null
    private var lastInput: Input? = null
    private var arriving: InteractionTimeline? = null
    /** Most recent photo from any source, for phone-mic questions without glasses. */
    @Volatile private var lastPhoto: ByteArray? = null

    /** Hold-to-talk audio from the glasses (M6), waiting for its photo. */
    private var glassesQuestion: CompletableDeferred<ShortArray?>? = null
    /** Phone-mic question waiting for the photo it asked the glasses for. */
    private var phoneQuestion: Pair<ShortArray, Long>? = null

    private class Question(val audio: ShortArray?, val text: String?, val via: String)

    private class Input(
        val jpeg: ByteArray,
        val source: Source,
        val imageId: Int?,
        val v1Tof: TofReading?,
        val transferMs: Long?,
        val question: Question?,
    )

    private fun newTimeline(source: Source, mode: String) = InteractionTimeline(
        id = UUID.randomUUID().toString().take(8),
        mode = mode,
        source = if (source == Source.GLASSES) "glasses" else "gallery",
        clock = SystemClock::elapsedRealtime,
    )

    private fun composeSettings() = ComposeSettings(
        units = settings.units.value,
        region = settings.distanceRegion.value,
        tofOffsetMm = settings.tofOffsetMm.value,
        tofScale = settings.tofScale.value,
        length = settings.answerLength.value,
    )

    /** Board-side moments (button, recording, capture) seen before the photo header creates the timeline. */
    private val boardMarks = java.util.concurrent.ConcurrentHashMap<Stage, Long>()

    fun start() {
        scope.launch { link.captures.collect(::onCapture) }
        scope.launch {
            link.events.collect { e ->
                val now = SystemClock.elapsedRealtime()
                when (e) {
                    is GlassesEvent.ButtonDown -> {
                        boardMarks.clear(); boardMarks[Stage.BUTTON_DOWN] = now
                        // Barge-in: the glasses already stopped their speaker; stop any phone speech and the old run.
                        job?.cancel()
                        speech.stopPhone()
                    }
                    is GlassesEvent.RecordStart -> boardMarks.putIfAbsent(Stage.RECORD_START, now)
                    is GlassesEvent.ButtonUp -> boardMarks.putIfAbsent(Stage.BUTTON_UP, now)
                    is GlassesEvent.PhotoCaptured -> boardMarks.putIfAbsent(Stage.PHOTO_CAPTURED, now)
                    else -> Unit
                }
            }
        }
    }

    // ---------------- inputs ----------------

    private fun onCapture(e: CaptureEvent) {
        when (e) {
            is CaptureEvent.Started -> {
                if (e.resend) return  // same interaction, photo coming again
                job?.cancel()  // a new photo supersedes whatever was running
                arriving = newTimeline(Source.GLASSES, if (e.question) "question" else "tap").apply {
                    boardMarks.forEach { (stage, at) -> mark(stage, at) }
                    boardMarks.clear()
                    mark(Stage.IMAGE_HEADER)
                    put("image_bytes", e.totalBytes)
                }
                _run.value = AssistantRun.Receiving(0, e.totalBytes)
            }
            is CaptureEvent.Progress -> (_run.value as? AssistantRun.Receiving)?.let {
                _run.value = it.copy(receivedBytes = e.receivedBytes, totalBytes = e.totalBytes)
            }
            is CaptureEvent.Completed -> {
                val t = arriving ?: newTimeline(Source.GLASSES, if (e.question) "question" else "tap")
                arriving = null
                t.mark(Stage.IMAGE_RECEIVED)
                t.put("transfer_kbps", "%.1f".format(Locale.US, e.jpeg.size / 1.024 / e.transferMs.coerceAtLeast(1)))
                lastPhoto = e.jpeg
                onGlassesPhoto(e, t)
            }
            is CaptureEvent.Failed -> {
                arriving?.let { finish(it, Mode.TAP, Source.GLASSES, error = e.reason, outcome = "transfer_failed") }
                arriving = null
                fail(Source.GLASSES, null, "The photo from the glasses didn't arrive complete (${e.reason}).",
                    spoken = "I couldn't get a photo. Please try again.", canRetry = false)
            }
        }
    }

    private fun onGlassesPhoto(e: CaptureEvent.Completed, t: InteractionTimeline) {
        val pendingPhone = phoneQuestion?.takeIf { SystemClock.elapsedRealtime() - it.second < PHONE_QUESTION_TTL_MS }
        when {
            e.question -> scope.launch {
                // Hold-to-talk: the audio ended (RECORD_STOP) before the photo was taken.
                val audio = withTimeoutOrNull(QUESTION_AUDIO_WAIT_MS) { glassesQuestion?.await() }
                glassesQuestion = null
                process(Input(e.jpeg, Source.GLASSES, e.imageId, e.v1Tof, e.transferMs, Question(audio, null, "glasses mic")), t)
            }
            pendingPhone != null -> {
                phoneQuestion = null
                t.put("question", "(phone mic)")
                process(Input(e.jpeg, Source.GLASSES, e.imageId, e.v1Tof, e.transferMs, Question(pendingPhone.first, null, "phone mic")), t)
            }
            else -> process(Input(e.jpeg, Source.GLASSES, e.imageId, e.v1Tof, e.transferMs, null), t)
        }
    }

    /** "Try with a photo": same pipeline, no distance sensor behind it. */
    fun describeGalleryPhoto(jpeg: ByteArray) {
        lastPhoto = jpeg
        process(Input(jpeg, Source.GALLERY, null, null, null, null))
    }

    fun galleryPhotoUnreadable() {
        _run.value = AssistantRun.Error(Source.GALLERY, null, "Couldn't open that photo.", canRetry = false)
    }

    /** Home → "Describe what's ahead": asks the glasses for a photo, same as a tap. */
    fun describeNow() {
        scope.launch {
            if (!link.isV2 || link.state.value !is LinkState.Connected) {
                fail(null, null, "The glasses aren't connected.", spoken = "The glasses aren't connected.", canRetry = false)
                return@launch
            }
            link.sendCommand(Frames.cmdU8(Frames.CMD_CAPTURE, Frames.MODE_PHONE))
        }
    }

    /** Called by the glasses-question collector (M6) when a hold-to-talk recording starts. */
    fun glassesQuestionStarted() {
        job?.cancel()
        glassesQuestion = CompletableDeferred()
    }

    /** The hold-to-talk recording finished (null = it failed). */
    fun glassesQuestionAudio(pcm: ShortArray?) {
        pcm?.let { arriving?.mark(Stage.AUDIO_RECEIVED) ?: boardMarks.putIfAbsent(Stage.AUDIO_RECEIVED, SystemClock.elapsedRealtime()) }
        (glassesQuestion ?: CompletableDeferred<ShortArray?>().also { glassesQuestion = it }).complete(pcm)
    }

    fun setListening(level: Float) { _run.value = AssistantRun.Listening(level) }

    /**
     * "Ask with phone mic": the question was recorded on the phone. If the glasses
     * are connected they take a fresh photo (like releasing the button); otherwise the
     * latest photo (e.g. from the gallery) is used.
     */
    fun askWithPhoneMic(pcm: ShortArray) {
        scope.launch {
            if (!AudioCheck.isSpeechLikely(pcm)) {
                fail(null, null, "No speech was recorded.", spoken = "I didn't catch that, please try again.", canRetry = false)
                return@launch
            }
            val glassesUp = link.isV2 && link.state.value is LinkState.Connected
            if (glassesUp) {
                phoneQuestion = pcm to SystemClock.elapsedRealtime()
                if (!link.sendCommand(Frames.cmdU8(Frames.CMD_CAPTURE, Frames.MODE_PHONE))) {
                    phoneQuestion = null
                    useLastPhoto(pcm)
                }
            } else {
                useLastPhoto(pcm)
            }
        }
    }

    private suspend fun useLastPhoto(pcm: ShortArray) {
        val photo = lastPhoto
        if (photo == null) {
            fail(null, null, "There's no photo to ask about: connect the glasses, or pick a photo first.",
                spoken = "I couldn't get a photo. Connect the glasses, or pick a photo first.", canRetry = false)
            return
        }
        process(Input(photo, Source.GALLERY, null, null, null, Question(pcm, null, "phone mic")))
    }

    /** Typed question (Diagnostics / accessibility fallback). */
    fun askText(text: String) {
        val photo = lastPhoto ?: return
        process(Input(photo, Source.GALLERY, null, null, null, Question(null, text, "typed")))
    }

    fun retry() { lastInput?.let { process(it) } }

    fun stopSpeech() { scope.launch { speech.stop() } }

    fun replay() { scope.launch { speech.replay() } }

    // ---------------- the pipeline ----------------

    private fun fail(source: Source?, preview: Bitmap?, message: String, spoken: String, canRetry: Boolean) {
        _run.value = AssistantRun.Error(source, preview, message, canRetry)
        scope.launch { speech.say(spoken) }
    }

    private fun stillLoadingMessage(): String {
        val elapsed = (SystemClock.elapsedRealtime() - describer.loadingSinceMs) / 1000
        val left = ((TYPICAL_LOAD_S - elapsed).coerceAtLeast(5) / 5 * 5)
        return "I'm still starting up, about $left seconds."
    }

    private fun finish(
        t: InteractionTimeline, mode: Mode, source: Source, outcome: String,
        error: String? = null, sentence: String? = null, heard: String? = null, via: String? = null,
        raw: String? = null, tof: TofReading? = null, engine: String? = null, stt: String? = null,
        ocr: String? = null, jpeg: ByteArray? = null,
    ) {
        t.outcome = outcome
        t.put("error", error)
        t.put("question", heard)
        t.put("answer", sentence)
        latencyLog.append(t)
        _completed.tryEmit(InteractionRecord(t, mode, source, via, heard, sentence, raw, tof, engine, stt, ocr, jpeg, error))
    }

    private fun process(input: Input, timeline: InteractionTimeline? = null) {
        lastInput = input
        job?.cancel()
        val processStart = SystemClock.elapsedRealtime()
        val mode = if (input.question != null) Mode.QUESTION else Mode.TAP
        val t = timeline ?: newTimeline(input.source, if (input.question?.via == "phone mic") "phone_question"
            else if (mode == Mode.QUESTION) "question" else "tap").apply { mark(Stage.IMAGE_RECEIVED, processStart) }
        input.question?.audio?.let { t.mark(Stage.AUDIO_RECEIVED, processStart) }
        job = scope.launch {
            val preview = withContext(Dispatchers.Default) { SceneDescriber.decodeDownscaled(input.jpeg, PREVIEW_PX) }
            val q = input.question
            if (describer.state.value is ModelState.Loading || (q != null && !describer.isGemmaReady)) {
                val msg = if (describer.state.value is ModelState.Loading) stillLoadingMessage()
                    else "I can't answer questions until the AI model is set up."
                fail(input.source, preview, msg, spoken = msg, canRetry = true)
                finish(t, mode, input.source, "model_not_ready", error = msg)
                return@launch
            }
            if (q?.audio != null && !AudioCheck.isSpeechLikely(q.audio)) {
                fail(input.source, preview, "No speech in the recording.", spoken = "I didn't catch that, please try again.", canRetry = false)
                finish(t, mode, input.source, "no_speech", via = q.via)
                return@launch
            }
            _run.value = AssistantRun.Describing(input.source, preview, q != null)

            // v2: the distance arrives as an event after the image: wait in parallel with the model.
            val tofJob = when {
                input.v1Tof != null -> null
                input.imageId != null -> async { link.awaitTof(input.imageId, TOF_WAIT_MS).also { t.mark(Stage.TOF_RECEIVED) } }
                else -> null
            }

            val cs = composeSettings()
            var heard: String? = null
            var raw: String? = null
            var stt: String? = null
            val sentence: String
            val engine: String
            val ocrMs: Long
            val modelMs: Long
            val ocrText: String
            try {
                if (q == null) {
                    val d = describer.describe(input.jpeg, t)
                    val tof = input.v1Tof ?: tofJob?.await()
                    sentence = AnswerComposer.composeDescribe(d.objectPhrase, if (input.source == Source.GLASSES) tof ?: TofReading.MISSING else null, cs)
                    engine = d.engine; ocrMs = d.ocrMs; modelMs = d.modelMs; ocrText = d.ocrText
                } else {
                    val ocrStart = SystemClock.elapsedRealtime()
                    val r = describer.ask(input.jpeg, q.audio, q.text, settings.sttEngine.value, androidStt, cs.length, t)
                    heard = r.transcript; raw = r.raw; stt = r.stt
                    engine = r.engine; modelMs = r.modelMs; ocrText = r.ocrText
                    ocrMs = (SystemClock.elapsedRealtime() - ocrStart - r.modelMs).coerceAtLeast(0)
                    val tof = input.v1Tof ?: tofJob?.await()
                    sentence = when {
                        r.answer == null && r.transcript.isBlank() && r.attempts == 0 -> {
                            fail(input.source, preview, "Nothing was recognised in the question.", spoken = "I didn't catch that, please try again.", canRetry = true)
                            finish(t, mode, input.source, "no_transcript", via = q.via, stt = stt)
                            return@launch
                        }
                        r.answer == null -> {
                            fail(input.source, preview, "The model's answer couldn't be understood (twice).", spoken = "Sorry, I couldn't work that out. Please try again.", canRetry = true)
                            finish(t, mode, input.source, "unparseable", via = q.via, raw = raw, heard = heard, stt = stt)
                            return@launch
                        }
                        else -> AnswerComposer.composeAnswer(r.answer, if (input.source == Source.GLASSES) tof ?: TofReading.MISSING else null, cs)
                    }
                }
            } catch (e: TimeoutCancellationException) {
                fail(input.source, preview, "The model took too long (over 30 s).", spoken = "Sorry, that took too long. Please try again.", canRetry = true)
                finish(t, mode, input.source, "model_timeout")
                return@launch
            } catch (e: CancellationException) {
                finish(t, mode, input.source, "superseded")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "pipeline failed", e)
                fail(input.source, preview, e.message ?: "Something went wrong", spoken = "Sorry, something went wrong. Please try again.", canRetry = true)
                finish(t, mode, input.source, "error", error = e.message)
                return@launch
            }

            val tof = input.v1Tof ?: tofJob?.takeIf { it.isCompleted }?.await()
            tof?.let {
                t.put("distance_mm", it.medianMm)
                t.put("tof_flags", "0x%02x".format(it.flags))
                t.put("tof_samples", "${it.validCount}/${it.sampleCount}")
                t.put("tof_spread_mm", it.spreadMm)
            }
            t.put("engine", engine)

            // The board logs RESULT_TEXT on its serial console (handy when testing without the app screen).
            if (input.source == Source.GLASSES) scope.launch { link.sendResultText(sentence) }
            val speechStart = SystemClock.elapsedRealtime()
            val spoken = speech.say(sentence, t)
            val timing = RunTiming(
                transferMs = input.transferMs, ocrMs = ocrMs, modelMs = modelMs,
                speechStartMs = spoken.startedAfterMs,
                totalMs = (input.transferMs ?: 0) + (speechStart + (spoken.startedAfterMs ?: 0) - processStart),
            )
            Log.d(TAG, "${input.source} $mode: transfer=${timing.transferMs} ocr=$ocrMs model=$modelMs speech=${spoken.startedAfterMs} " +
                "total=${timing.totalMs} heard=\"$heard\" :: $sentence")
            finish(t, mode, input.source, if (spoken.startedAfterMs != null) "ok" else "speech_failed",
                error = spoken.error, sentence = sentence, heard = heard, via = q?.via, raw = raw, tof = tof,
                engine = engine, stt = stt, ocr = ocrText, jpeg = input.jpeg)
            _run.value = AssistantRun.Done(input.source, mode, preview, heard, sentence, tof, engine, ocrText, timing,
                spoken.where, spoken.error)
        }
    }

    companion object {
        private const val TAG = "AssistantSession"
        private const val PREVIEW_PX = 512
        private const val TOF_WAIT_MS = 4_000L
        private const val QUESTION_AUDIO_WAIT_MS = 1_500L
        private const val PHONE_QUESTION_TTL_MS = 10_000L
        private const val TYPICAL_LOAD_S = 30L  // Gemma GPU load measured at 24.6-34 s
    }
}

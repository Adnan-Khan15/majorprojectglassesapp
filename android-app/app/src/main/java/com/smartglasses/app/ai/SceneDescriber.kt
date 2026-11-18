package com.smartglasses.app.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import com.smartglasses.app.audio.Wav
import com.smartglasses.app.metrics.InteractionTimeline
import com.smartglasses.app.settings.AnswerLength
import com.smartglasses.app.settings.SttEngine
import com.smartglasses.app.voice.AndroidOnDeviceStt
import com.smartglasses.app.metrics.Stage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

sealed interface ModelState {
    /** No verified model file yet (see ModelStore). ML Kit labels are used meanwhile. */
    data class NoModel(val reason: String) : ModelState
    data object Loading : ModelState
    data class Ready(val engine: String, val loadMs: Long) : ModelState
    /** A model file is present but Gemma couldn't start on this phone; ML Kit labels are used instead. */
    data class Fallback(val reason: String) : ModelState
}

/** What the model saw. The spoken sentence (with distance) is composed by the caller. */
data class Description(
    val objectPhrase: String,
    val ocrText: String,
    val engine: String,
    val ocrMs: Long,
    val modelMs: Long,
    val totalMs: Long,
)

/**
 * Turns a photo (+ optional distance) into the sentence the glasses speak.
 * Lives for the whole process so the model is loaded once, not per Activity.
 * Follows [store]: loads Gemma when a verified model appears, unloads it when deleted.
 */
class SceneDescriber(context: Context, private val scope: CoroutineScope, private val store: ModelStore) {

    private val gemma = GemmaVision(context.cacheDir)
    private val mlkit = MlKitVision()

    private val _state = MutableStateFlow<ModelState>(ModelState.NoModel("Checking for the AI model…"))
    val state: StateFlow<ModelState> = _state.asStateFlow()

    private var watchJob: Job? = null
    private var loadedPath: String? = null

    /** Idempotent: checks storage for the model and starts following its status. */
    fun prepare() {
        if (watchJob?.isActive == true) return
        watchJob = scope.launch { store.status.collectLatest(::onStoreStatus) }
        scope.launch { store.refresh() }
    }

    private suspend fun onStoreStatus(s: ModelStatus) {
        when (s) {
            is ModelStatus.Ready -> if (loadedPath != s.path || _state.value !is ModelState.Ready) load(s.path)
            is ModelStatus.Missing -> { unload(); _state.value = ModelState.NoModel(s.message ?: "No AI model on this phone yet.") }
            is ModelStatus.Corrupt -> { unload(); _state.value = ModelState.NoModel(s.reason) }
            is ModelStatus.Importing, is ModelStatus.Verifying ->
                if (_state.value !is ModelState.Ready) _state.value = ModelState.NoModel("Preparing the AI model…")
        }
    }

    /** When the current Gemma load started (for "I'm still starting up, about N seconds"). */
    @Volatile var loadingSinceMs: Long = 0L
        private set

    private suspend fun load(path: String) {
        unload()
        loadingSinceMs = SystemClock.elapsedRealtime()
        _state.value = ModelState.Loading
        try {
            val t0 = SystemClock.elapsedRealtime()
            withTimeout(LOAD_TIMEOUT_MS) { gemma.load(java.io.File(path)) }
            val ms = SystemClock.elapsedRealtime() - t0
            loadedPath = path
            Log.d(TAG, "Gemma loaded on ${gemma.backendName} in $ms ms")
            _state.value = ModelState.Ready(gemma.backendName, ms)
            warmUp()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.e(TAG, "Gemma load failed", t)
            _state.value = ModelState.Fallback(t.message ?: t::class.simpleName ?: "unknown error")
        }
    }

    /**
     * The first answer after loading was 2.5x slower than the second in the first
     * hardware test (8.2 s vs 3.3 s), which looks like one-time GPU/kernel warm-up.
     * Run one tiny throwaway inference now, so the user's first question doesn't pay it.
     * Real requests queue behind it on Gemma's single thread.
     */
    private fun warmUp() {
        scope.launch {
            try {
                val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.GRAY) }
                val jpeg = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
                val t0 = SystemClock.elapsedRealtime()
                gemma.describe(jpeg, null)
                Log.d(TAG, "Gemma warm-up inference took ${SystemClock.elapsedRealtime() - t0} ms")
            } catch (e: Exception) {
                Log.w(TAG, "warm-up failed (not fatal)", e)
            }
        }
    }

    private suspend fun unload() {
        if (loadedPath != null) { gemma.close(); loadedPath = null }
    }

    /** Tries loading Gemma again after a Fallback. */
    fun retry() {
        scope.launch { store.readyPath()?.let { load(it) } ?: store.refresh() }
    }

    val isGemmaReady: Boolean get() = _state.value is ModelState.Ready
    val gemmaHearsAudio: Boolean get() = isGemmaReady && gemma.audioSupported

    data class QuestionResult(
        val answer: AiAnswer?,      // null: the model's reply couldn't be parsed, even after a retry
        val transcript: String,     // what was heard ("" = nothing recognised)
        val ocrText: String,
        val engine: String,
        val stt: String,            // which recogniser produced the transcript
        val modelMs: Long,
        val attempts: Int,
        val raw: String,
    )

    /**
     * Question mode. The question is audio (16 kHz PCM) or text. OCR always runs first
     * (~0.2 s): it is the hint for "read this / what does it say" questions, and with
     * Gemma hearing the audio directly we only learn the question from its reply.
     */
    suspend fun ask(
        jpeg: ByteArray,
        audio16k: ShortArray?,
        questionText: String?,
        sttPref: SttEngine,
        androidStt: AndroidOnDeviceStt,
        length: AnswerLength,
        timeline: InteractionTimeline? = null,
    ): QuestionResult = withContext(Dispatchers.Default) {
        check(isGemmaReady) { "The AI model isn't ready" }
        val small = decodeDownscaled(jpeg, MAX_EDGE_PX)
        val smallJpeg = ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        val ocrText = runCatching { mlkit.readText(decodeDownscaled(jpeg, OCR_MAX_EDGE_PX)) }.getOrDefault("")
        timeline?.mark(Stage.OCR_DONE)

        var text = questionText
        var sttName = if (questionText != null) "typed" else ""
        var wav: ByteArray? = null
        if (text == null && audio16k != null) {
            val useGemma = sttPref == SttEngine.GEMMA_AUDIO && gemma.audioSupported
            if (useGemma || !androidStt.available) {
                check(gemma.audioSupported) { "No speech recogniser available on this phone" }
                wav = Wav.write(audio16k, 16000)
                sttName = "Gemma audio"
            } else {
                text = androidStt.transcribe(audio16k)
                sttName = androidStt.name
                timeline?.mark(Stage.TRANSCRIPT_READY)
                if (text.isBlank()) {
                    return@withContext QuestionResult(null, "", ocrText, "-", sttName, 0, 0, "")
                }
            }
        }
        val modelStart = SystemClock.elapsedRealtime()
        var raw = ""
        var parsed: AiAnswer? = null
        var attempts = 0
        while (parsed == null && attempts < 2) {  // the JSON contract: retry once on a parse failure
            attempts++
            raw = withTimeout(INFERENCE_TIMEOUT_MS) {
                gemma.ask(smallJpeg, wav, text, ocrText, length) { timeline?.mark(Stage.FIRST_MODEL_OUTPUT) }
            }
            parsed = AiAnswer.parse(raw)
            if (parsed == null) Log.w(TAG, "unparseable answer (attempt $attempts): $raw")
        }
        timeline?.mark(Stage.ANSWER_COMPLETE)
        if (wav != null) timeline?.mark(Stage.TRANSCRIPT_READY)  // Gemma heard it as part of answering
        val transcript = text ?: parsed?.heard.orEmpty()
        QuestionResult(parsed, transcript, ocrText, "Gemma 4 E2B (${gemma.backendName})", sttName,
            SystemClock.elapsedRealtime() - modelStart, attempts, raw)
    }

    /** STT spike: Gemma as a plain transcriber. */
    suspend fun transcribeWithGemma(audio16k: ShortArray): String = gemma.transcribe(Wav.write(audio16k, 16000))

    /** False only while Gemma is loading; otherwise Gemma or the ML Kit fallback can answer. */
    val canDescribe: Boolean
        get() = _state.value !is ModelState.Loading

    suspend fun describe(
        jpeg: ByteArray,
        timeline: InteractionTimeline? = null,
    ): Description = withContext(Dispatchers.Default) {
        check(canDescribe) { "Model is not ready yet" }
        val start = SystemClock.elapsedRealtime()

        val small = decodeDownscaled(jpeg, MAX_EDGE_PX)
        val smallJpeg = ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()

        val ocrStart = SystemClock.elapsedRealtime()
        // OCR on the full-resolution image reads small print far better than at 512 px.
        val ocrText = runCatching { mlkit.readText(decodeDownscaled(jpeg, OCR_MAX_EDGE_PX)) }.getOrDefault("")
        val ocrMs = SystemClock.elapsedRealtime() - ocrStart
        timeline?.mark(Stage.OCR_DONE)

        val modelStart = SystemClock.elapsedRealtime()
        val engine: String
        val phrase: String = if (_state.value is ModelState.Ready) {
            engine = "Gemma 4 E2B (${gemma.backendName})"
            cleanPhrase(withTimeout(INFERENCE_TIMEOUT_MS) {
                gemma.describe(smallJpeg, ocrText) { timeline?.mark(Stage.FIRST_MODEL_OUTPUT) }
            })
        } else {
            engine = "ML Kit labels (fallback)"
            fallbackPhrase(mlkit.topLabel(small), ocrText)
        }
        val modelMs = SystemClock.elapsedRealtime() - modelStart
        timeline?.mark(Stage.FIRST_MODEL_OUTPUT)  // ML Kit fallback has no streaming: first output = done
        timeline?.mark(Stage.ANSWER_COMPLETE)
        timeline?.put("engine", engine)

        Description(phrase, ocrText, engine, ocrMs, modelMs, SystemClock.elapsedRealtime() - start)
            .also { Log.d(TAG, "Described in ${it.totalMs} ms (ocr ${it.ocrMs}, model ${it.modelMs}): ${it.objectPhrase}") }
    }

    companion object {
        private const val TAG = "SceneDescriber"
        private const val MAX_EDGE_PX = 512
        private const val OCR_MAX_EDGE_PX = 1600
        private const val LOAD_TIMEOUT_MS = 120_000L
        private const val INFERENCE_TIMEOUT_MS = 30_000L

        /** Strips quotes, trailing punctuation and chatty prefixes from the model reply. */
        fun cleanPhrase(raw: String): String {
            var s = raw.trim().lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: ""
            s = s.removePrefix("Answer:").trim()
            s = s.trim('"', '\'', '“', '”', '*', ' ').trimEnd('.', '!', ',')
            if (s.isEmpty()) return "something I can't make out"
            return s.replaceFirstChar { it.lowercase() }
        }

        fun fallbackPhrase(label: String?, ocr: String): String {
            val what = label?.lowercase()?.let { if (it.first() in "aeiou") "an $it" else "a $it" } ?: "an object"
            val text = ocr.split(' ').filter { it.length > 1 }.take(3).joinToString(" ")
            return if (text.isNotEmpty()) "$what with $text written on it" else what
        }

        fun decodeDownscaled(jpeg: ByteArray, maxEdge: Int): Bitmap {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
            val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: error("Could not decode image")
            val scale = maxEdge.toFloat() / maxOf(decoded.width, decoded.height)
            if (scale >= 1f) return decoded
            return Bitmap.createScaledBitmap(decoded, (decoded.width * scale).roundToInt(), (decoded.height * scale).roundToInt(), true)
        }
    }
}

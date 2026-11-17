package com.smartglasses.app.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import com.smartglasses.app.metrics.InteractionTimeline
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

    private suspend fun load(path: String) {
        unload()
        _state.value = ModelState.Loading
        try {
            val t0 = SystemClock.elapsedRealtime()
            withTimeout(LOAD_TIMEOUT_MS) { gemma.load(java.io.File(path)) }
            val ms = SystemClock.elapsedRealtime() - t0
            loadedPath = path
            Log.d(TAG, "Gemma loaded on ${gemma.backendName} in $ms ms")
            _state.value = ModelState.Ready(gemma.backendName, ms)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.e(TAG, "Gemma load failed", t)
            _state.value = ModelState.Fallback(t.message ?: t::class.simpleName ?: "unknown error")
        }
    }

    private suspend fun unload() {
        if (loadedPath != null) { gemma.close(); loadedPath = null }
    }

    /** Tries loading Gemma again after a Fallback. */
    fun retry() {
        scope.launch { store.readyPath()?.let { load(it) } ?: store.refresh() }
    }

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

        fun compose(phrase: String, distanceMm: Int?): String {
            if (distanceMm == null || distanceMm <= 0) return phrase
            return "$phrase, ${formatDistance(distanceMm)}"
        }

        fun formatDistance(mm: Int): String = when {
            mm < 1000 -> "${(mm / 10.0).roundToInt()} centimetres"
            else -> {
                val m = (mm / 100.0).roundToInt() / 10.0
                if (m == m.toInt().toDouble()) "${m.toInt()} metre${if (m.toInt() == 1) "" else "s"}" else "$m metres"
            }
        }

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

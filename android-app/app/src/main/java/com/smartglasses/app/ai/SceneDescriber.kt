package com.smartglasses.app.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

sealed interface ModelState {
    data class Unpacking(val progress: Float) : ModelState
    data object Loading : ModelState
    data class Ready(val engine: String, val loadMs: Long) : ModelState
    /** Gemma unavailable; ML Kit labels are used instead so captures still work. */
    data class Fallback(val reason: String) : ModelState
    data class Failed(val reason: String) : ModelState
}

data class Description(
    val sentence: String,
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
 */
class SceneDescriber(context: Context, private val scope: CoroutineScope) {

    private val installer = ModelInstaller(context)
    private val gemma = GemmaVision(context.cacheDir)
    private val mlkit = MlKitVision()

    private val _state = MutableStateFlow<ModelState>(ModelState.Loading)
    val state: StateFlow<ModelState> = _state.asStateFlow()

    private var prepareJob: Job? = null

    /** Idempotent: unpacks (first launch only) and loads the model. */
    fun prepare() {
        if (prepareJob?.isActive == true) return
        val s = _state.value
        if (s is ModelState.Ready || s is ModelState.Fallback) return
        prepareJob = scope.launch {
            try {
                if (!installer.isInstalled()) {
                    _state.value = ModelState.Unpacking(0f)
                    installer.install { _state.value = ModelState.Unpacking(it) }
                }
                _state.value = ModelState.Loading
                val t0 = SystemClock.elapsedRealtime()
                withTimeout(LOAD_TIMEOUT_MS) { gemma.load(installer.modelFile) }
                val ms = SystemClock.elapsedRealtime() - t0
                Log.d(TAG, "Gemma loaded on ${gemma.backendName} in $ms ms")
                _state.value = ModelState.Ready(gemma.backendName, ms)
            } catch (t: Throwable) {
                Log.e(TAG, "Model prepare failed", t)
                val reason = t.message ?: t::class.simpleName ?: "unknown error"
                // Storage problems are worth surfacing as a hard failure; a model that won't
                // start on this phone still leaves ML Kit available.
                _state.value = if (t is IllegalStateException && reason.startsWith("Not enough"))
                    ModelState.Failed(reason) else ModelState.Fallback(reason)
            }
        }
    }

    fun retry() {
        _state.value = ModelState.Loading
        prepare()
    }

    val canDescribe: Boolean
        get() = _state.value.let { it is ModelState.Ready || it is ModelState.Fallback }

    suspend fun describe(jpeg: ByteArray, distanceMm: Int?): Description = withContext(Dispatchers.Default) {
        check(canDescribe) { "Model is not ready yet" }
        val start = SystemClock.elapsedRealtime()

        val small = decodeDownscaled(jpeg, MAX_EDGE_PX)
        val smallJpeg = ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()

        val ocrStart = SystemClock.elapsedRealtime()
        // OCR on the full-resolution image reads small print far better than at 512 px.
        val ocrText = runCatching { mlkit.readText(decodeDownscaled(jpeg, OCR_MAX_EDGE_PX)) }.getOrDefault("")
        val ocrMs = SystemClock.elapsedRealtime() - ocrStart

        val modelStart = SystemClock.elapsedRealtime()
        val engine: String
        val phrase: String = if (_state.value is ModelState.Ready) {
            engine = "Gemma 4 E2B (${gemma.backendName})"
            cleanPhrase(withTimeout(INFERENCE_TIMEOUT_MS) { gemma.describe(smallJpeg, ocrText) })
        } else {
            engine = "ML Kit labels (fallback)"
            fallbackPhrase(mlkit.topLabel(small), ocrText)
        }
        val modelMs = SystemClock.elapsedRealtime() - modelStart

        val sentence = compose(phrase, distanceMm)
        Description(sentence, phrase, ocrText, engine, ocrMs, modelMs, SystemClock.elapsedRealtime() - start)
            .also { Log.d(TAG, "Described in ${it.totalMs} ms (ocr ${it.ocrMs}, model ${it.modelMs}): ${it.sentence}") }
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

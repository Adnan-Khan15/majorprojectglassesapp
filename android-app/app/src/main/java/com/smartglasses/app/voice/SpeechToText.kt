package com.smartglasses.app.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Speech recognition behind one interface, so the STT spike (docs/STT_SPIKE.md)
 * can compare engines on the same recordings and the app can switch by setting.
 */
interface SpeechToText {
    val name: String
    /** Transcribes 16 kHz mono PCM; returns "" if nothing was recognised. */
    suspend fun transcribe(pcm16k: ShortArray): String
}

/** Is there plausibly speech in this recording? Decides "I didn't catch that". */
object AudioCheck {
    const val MIN_SPEECH_MS = 300
    const val SILENCE_DBFS = -50f

    fun dbfs(x: ShortArray, n: Int = x.size): Float {
        if (n <= 0) return -96f
        var sum = 0.0
        for (i in 0 until n) sum += x[i].toDouble() * x[i]
        val rms = sqrt(sum / n)
        return if (rms < 1) -96f else (20 * log10(rms / 32768.0)).toFloat()
    }

    /** Loudest 100 ms window, so a short question in a long silent recording still counts. */
    fun peakWindowDbfs(x: ShortArray, rate: Int = 16000): Float {
        val w = rate / 10
        if (x.size < w) return dbfs(x)
        var best = -96f
        var i = 0
        while (i + w <= x.size) { best = maxOf(best, dbfs(x.copyOfRange(i, i + w))); i += w / 2 }
        return best
    }

    fun isSpeechLikely(x: ShortArray, rate: Int = 16000): Boolean =
        x.size * 1000L / rate >= MIN_SPEECH_MS && peakWindowDbfs(x, rate) > SILENCE_DBFS
}

/**
 * Spike B: Android's on-device recogniser, fed our own recording through
 * EXTRA_AUDIO_SOURCE (Android 13+). Offline only (EXTRA_PREFER_OFFLINE + the
 * on-device recogniser), so nothing leaves the phone.
 */
class AndroidOnDeviceStt(private val context: Context) : SpeechToText {
    override val name = "Android on-device"

    val available: Boolean
        get() = Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    override suspend fun transcribe(pcm16k: ShortArray): String {
        check(available) { "On-device speech recognition isn't available on this phone" }
        val pipe = ParcelFileDescriptor.createPipe()
        val result = CompletableDeferred<String>()
        return coroutineScope {
            val recognizer = withContext(Dispatchers.Main) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context).apply {
                    setRecognitionListener(object : RecognitionListener {
                        override fun onResults(b: Bundle?) {
                            result.complete(b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty())
                        }
                        override fun onError(error: Int) {
                            // NO_MATCH / SPEECH_TIMEOUT mean "heard nothing", not a failure.
                            if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) result.complete("")
                            else result.completeExceptionally(IllegalStateException("speech recogniser error $error"))
                        }
                        override fun onReadyForSpeech(p: Bundle?) {}
                        override fun onBeginningOfSpeech() {}
                        override fun onRmsChanged(v: Float) {}
                        override fun onBufferReceived(b: ByteArray?) {}
                        override fun onEndOfSpeech() {}
                        override fun onPartialResults(b: Bundle?) {}
                        override fun onEvent(t: Int, b: Bundle?) {}
                    })
                    startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT)
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16000)
                    })
                }
            }
            launch(Dispatchers.IO) {
                FileOutputStream(pipe[1].fileDescriptor).use { out ->
                    val bb = ByteBuffer.allocate(pcm16k.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                    pcm16k.forEach { bb.putShort(it) }
                    out.write(bb.array())
                }
                pipe[1].close()  // end of audio
            }
            try {
                withTimeout(20_000) { result.await() }
            } finally {
                withContext(Dispatchers.Main) { recognizer.destroy() }
                runCatching { pipe[0].close() }
            }
        }
    }
}

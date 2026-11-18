package com.smartglasses.app.speech

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import com.smartglasses.app.audio.Wav
import com.smartglasses.app.settings.AppSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Android's offline TextToSpeech, used two ways: rendered to a WAV for the glasses'
 * speaker (synthesizeToFile), or spoken on the phone. Prefers an English (India)
 * voice that needs no network, then any offline English voice.
 */
class TtsEngine(context: Context, private val settings: AppSettings) {
    private val appContext = context.applicationContext
    private val ready = CompletableDeferred<Boolean>()
    private val started = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val done = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    @Volatile var voiceDescription: String = "default"
        private set

    private val tts: TextToSpeech = TextToSpeech(appContext) { status ->
        if (status == TextToSpeech.SUCCESS) chooseVoice()
        ready.complete(status == TextToSpeech.SUCCESS)
    }.apply {
        setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String) { started.remove(id)?.complete(Unit) }
            override fun onDone(id: String) { done.remove(id)?.complete(Unit) }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String) = fail(id, "speech engine error")
            override fun onError(id: String, errorCode: Int) = fail(id, "speech engine error $errorCode")
        })
    }

    private fun fail(id: String, msg: String) {
        started.remove(id)?.completeExceptionally(IllegalStateException(msg))
        done.remove(id)?.completeExceptionally(IllegalStateException(msg))
    }

    private fun chooseVoice() {
        val voices: Set<Voice> = runCatching { tts.voices }.getOrNull().orEmpty()
        fun offline(v: Voice) = !v.isNetworkConnectionRequired &&
            TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in v.features.orEmpty()
        val pick = voices.filter(::offline).let { off ->
            off.firstOrNull { it.locale.language == "en" && it.locale.country == "IN" }
                ?: off.firstOrNull { it.locale.language == "en" && it.locale.country == Locale.getDefault().country }
                ?: off.firstOrNull { it.locale.language == "en" }
        }
        if (pick != null) {
            tts.voice = pick
            voiceDescription = "${pick.locale.toLanguageTag()} (${pick.name}, offline)"
        } else {
            tts.language = Locale.getDefault()
            voiceDescription = "${Locale.getDefault().toLanguageTag()} (engine default; no offline English voice found)"
        }
        Log.d(TAG, "TTS voice: $voiceDescription")
    }

    private suspend fun awaitReady() {
        val ok = withTimeout(INIT_TIMEOUT_MS) { ready.await() }
        check(ok) { "Text-to-speech is not available on this phone" }
        tts.setSpeechRate(settings.speechRate.value)
    }

    /** Speaks on the phone; returns ms until audio actually started. */
    suspend fun speakOnPhone(text: String): Long {
        val t0 = SystemClock.elapsedRealtime()
        awaitReady()
        val id = UUID.randomUUID().toString()
        val onStart = CompletableDeferred<Unit>().also { started[id] = it }
        val rc = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        if (rc != TextToSpeech.SUCCESS) { started.remove(id); error("Text-to-speech refused the sentence (code $rc)") }
        withTimeout(START_TIMEOUT_MS) { onStart.await() }
        return SystemClock.elapsedRealtime() - t0
    }

    /** Renders [text] to PCM (engine's own sample rate) without playing it. */
    suspend fun synthesize(text: String): Wav.Pcm {
        awaitReady()
        val id = UUID.randomUUID().toString()
        val file = File(appContext.cacheDir, "tts-$id.wav")
        val finished = CompletableDeferred<Unit>().also { done[id] = it }
        val rc = tts.synthesizeToFile(text, Bundle(), file, id)
        if (rc != TextToSpeech.SUCCESS) { done.remove(id); error("Text-to-speech couldn't render the sentence (code $rc)") }
        try {
            withTimeout(SYNTH_TIMEOUT_MS) { finished.await() }
            return Wav.parse(file.readBytes())
        } finally {
            file.delete()
        }
    }

    fun stopPhone() { runCatching { tts.stop() } }

    companion object {
        private const val TAG = "TtsEngine"
        private const val INIT_TIMEOUT_MS = 5_000L
        private const val START_TIMEOUT_MS = 5_000L
        private const val SYNTH_TIMEOUT_MS = 10_000L
    }
}

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
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Android's offline TextToSpeech, used two ways: rendered to a WAV for the glasses'
 * speaker (synthesizeToFile), or spoken on the phone. Prefers an English (India)
 * voice that needs no network, then any offline English voice.
 *
 * The app keeps one engine for its whole life, but the TTS service runs in another
 * process and its connection can be lost (the engine is killed or updated); after that,
 * every call returns ERROR (-1). So a refused or failed call shuts the engine down,
 * binds a fresh one and tries once more. The voice is only kept if the engine accepts it.
 */
class TtsEngine(context: Context, private val settings: AppSettings) {
    private val appContext = context.applicationContext
    private val started = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val done = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    @Volatile var voiceDescription: String = "default"
        private set

    /** A failure that a fresh engine may fix. */
    private class TtsFailure(message: String) : Exception(message)

    private class Instance(val tts: TextToSpeech, val status: CompletableDeferred<Int>) {
        @Volatile var configured = false
    }

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(id: String) { started.remove(id)?.complete(Unit) }
        override fun onDone(id: String) { done.remove(id)?.complete(Unit) }
        @Deprecated("Deprecated in Java")
        override fun onError(id: String) = fail(id, "speech engine error")
        override fun onError(id: String, errorCode: Int) = fail(id, "speech engine error $errorCode")
    }

    @Volatile private var instance: Instance = newInstance()

    private fun newInstance(): Instance {
        val status = CompletableDeferred<Int>()
        val tts = TextToSpeech(appContext) { status.complete(it) }
        tts.setOnUtteranceProgressListener(listener)
        return Instance(tts, status)
    }

    /** Replaces [dead] with a new engine (once, even if several callers notice). */
    private fun restart(dead: Instance) = synchronized(this) {
        if (instance === dead) {
            runCatching { dead.tts.shutdown() }
            instance = newInstance()
        }
    }

    private fun fail(id: String, msg: String) {
        started.remove(id)?.completeExceptionally(TtsFailure(msg))
        done.remove(id)?.completeExceptionally(TtsFailure(msg))
    }

    private fun engineName(i: Instance) = runCatching { i.tts.defaultEngine }.getOrNull() ?: "unknown engine"

    private suspend fun ready(i: Instance): TextToSpeech {
        val status = withTimeoutOrNull(INIT_TIMEOUT_MS) { i.status.await() }
        if (status != TextToSpeech.SUCCESS) {
            throw TtsFailure(if (status == null) "text-to-speech didn't start" else "text-to-speech failed to start (code $status)")
        }
        if (!i.configured) { chooseVoice(i.tts); i.configured = true }
        i.tts.setSpeechRate(settings.speechRate.value)
        return i.tts
    }

    /** Runs [block] on the current engine; on a [TtsFailure], once more on a fresh engine. */
    private suspend fun <T> withEngine(block: suspend (TextToSpeech) -> T): T {
        val first = instance
        try {
            return block(ready(first))
        } catch (e: TtsFailure) {
            Log.w(TAG, "TTS failed (${e.message}, ${engineName(first)}); restarting the engine and retrying", e)
            restart(first)
        }
        val second = instance
        try {
            return block(ready(second))
        } catch (e: TtsFailure) {
            throw IllegalStateException("${e.message} (${engineName(second)}, after a restart)", e)
        }
    }

    private fun chooseVoice(tts: TextToSpeech) {
        val voices: Set<Voice> = runCatching { tts.voices }.getOrNull().orEmpty()
        fun offline(v: Voice) = !v.isNetworkConnectionRequired &&
            TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in v.features.orEmpty()
        val pick = voices.filter(::offline).let { off ->
            off.firstOrNull { it.locale.language == "en" && it.locale.country == "IN" }
                ?: off.firstOrNull { it.locale.language == "en" && it.locale.country == Locale.getDefault().country }
                ?: off.firstOrNull { it.locale.language == "en" }
        }
        voiceDescription = if (pick != null && tts.setVoice(pick) == TextToSpeech.SUCCESS) {
            "${pick.locale.toLanguageTag()} (${pick.name}, offline)"
        } else {
            // No usable offline English voice, or the engine refused it: let the engine choose.
            val lang = tts.setLanguage(Locale.getDefault()).takeIf { it >= TextToSpeech.LANG_AVAILABLE }?.let { Locale.getDefault() }
                ?: Locale.ENGLISH.also { tts.setLanguage(it) }
            "${lang.toLanguageTag()} (engine default${if (pick != null) "; ${pick.name} was refused" else "; no offline English voice found"})"
        }
        Log.d(TAG, "TTS voice: $voiceDescription")
    }

    /** Speaks on the phone; returns ms until audio actually started. */
    suspend fun speakOnPhone(text: String): Long {
        val t0 = SystemClock.elapsedRealtime()
        withEngine { tts ->
            val id = UUID.randomUUID().toString()
            val onStart = CompletableDeferred<Unit>().also { started[id] = it }
            val rc = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
            if (rc != TextToSpeech.SUCCESS) { started.remove(id); throw TtsFailure("Text-to-speech refused the sentence (code $rc)") }
            withTimeoutOrNull(START_TIMEOUT_MS) { onStart.await() }
                ?: run { started.remove(id); throw TtsFailure("phone speech didn't start") }
        }
        return SystemClock.elapsedRealtime() - t0
    }

    /** Renders [text] to PCM (engine's own sample rate) without playing it. */
    suspend fun synthesize(text: String): Wav.Pcm = withEngine { tts ->
        val id = UUID.randomUUID().toString()
        val file = File(appContext.cacheDir, "tts-$id.wav")
        val finished = CompletableDeferred<Unit>().also { done[id] = it }
        try {
            val rc = tts.synthesizeToFile(text, Bundle(), file, id)
            if (rc != TextToSpeech.SUCCESS) { done.remove(id); throw TtsFailure("Text-to-speech couldn't render the sentence (code $rc)") }
            withTimeoutOrNull(SYNTH_TIMEOUT_MS) { finished.await() }
                ?: run { done.remove(id); throw TtsFailure("rendering the sentence timed out") }
            Wav.parse(file.readBytes())
        } finally {
            file.delete()
        }
    }

    fun stopPhone() { runCatching { instance.tts.stop() } }

    companion object {
        private const val TAG = "TtsEngine"
        private const val INIT_TIMEOUT_MS = 5_000L
        private const val START_TIMEOUT_MS = 5_000L
        private const val SYNTH_TIMEOUT_MS = 10_000L
    }
}

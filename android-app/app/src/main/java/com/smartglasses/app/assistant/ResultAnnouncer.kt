package com.smartglasses.app.assistant

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * THE swap point for how a finished description reaches the wearer.
 * Exactly one implementation is bound, in GlassesApp.
 */
interface ResultAnnouncer {
    /** Human-readable name shown in the UI timing line. */
    val name: String

    /**
     * Starts announcing [sentence] and returns once audio has actually started,
     * with the time that took in ms. Throws if it could not start.
     */
    suspend fun announce(sentence: String): Long
}

/**
 * Speaks the sentence through the PHONE's speaker with Android TextToSpeech.
 *
 * TODO(Phase 7): replace with a BoardSpeakerAnnouncer that writes the sentence to
 * RESULT_TEXT and lets SAM speak it on the glasses through the MAX98357A, once the
 * amp + speaker are physically wired. Swap the binding in GlassesApp.announcer;
 * nothing else should need to change. (AssistantSession already writes
 * RESULT_TEXT on every glasses capture; at that point that write becomes the
 * announcement, so drop the separate write there.)
 */
class PhoneTtsAnnouncer(context: Context) : ResultAnnouncer {
    override val name = "phone speaker"

    private val ready = CompletableDeferred<Boolean>()
    private val started = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready.complete(status == TextToSpeech.SUCCESS)
    }.apply {
        setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String) { started.remove(id)?.complete(Unit) }
            override fun onDone(id: String) = Unit
            @Deprecated("Deprecated in Java")
            override fun onError(id: String) {
                started.remove(id)?.completeExceptionally(IllegalStateException("speech engine error"))
            }
            override fun onError(id: String, errorCode: Int) {
                started.remove(id)?.completeExceptionally(IllegalStateException("speech engine error $errorCode"))
            }
        })
    }

    override suspend fun announce(sentence: String): Long {
        val t0 = SystemClock.elapsedRealtime()
        val ok = withTimeout(INIT_TIMEOUT_MS) { ready.await() }
        check(ok) { "Text-to-speech is not available on this phone" }
        if (tts.voice == null) tts.language = Locale.getDefault()
        val id = UUID.randomUUID().toString()
        val onStart = CompletableDeferred<Unit>().also { started[id] = it }
        val rc = tts.speak(sentence, TextToSpeech.QUEUE_FLUSH, null, id)
        if (rc != TextToSpeech.SUCCESS) {
            started.remove(id)
            error("Text-to-speech refused the sentence (code $rc)")
        }
        withTimeout(START_TIMEOUT_MS) { onStart.await() }
        return (SystemClock.elapsedRealtime() - t0).also { Log.d("PhoneTtsAnnouncer", "speech started after $it ms") }
    }

    companion object {
        private const val INIT_TIMEOUT_MS = 5_000L
        private const val START_TIMEOUT_MS = 5_000L
    }
}

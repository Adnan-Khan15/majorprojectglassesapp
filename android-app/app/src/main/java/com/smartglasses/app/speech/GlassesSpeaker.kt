package com.smartglasses.app.speech

import android.os.SystemClock
import android.util.Log
import com.smartglasses.app.audio.CreditWindow
import com.smartglasses.app.audio.SpeechFramer
import com.smartglasses.app.ble.Frames
import com.smartglasses.app.ble.GlassesEvent
import com.smartglasses.app.ble.GlassesLink
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Streams 16 kHz speech to the glasses' speaker: IMA ADPCM frames on AUDIO_DOWN,
 * never more than the glasses' credits allow (their 2 s buffer), so nothing is
 * dropped for lack of space. Returns once the glasses report PLAY_STARTED.
 */
class GlassesSpeaker(private val link: GlassesLink) {

    /** Returns the phone time (elapsedRealtime) when the glasses reported audio started. */
    suspend fun play(pcm16k: ShortArray): Long = coroutineScope {
        val caps = link.caps.value ?: error("glasses not connected")
        check(caps.hasSpeechStream) { "these glasses' firmware can't play speech (update it)" }
        val frames = SpeechFramer.frames(pcm16k)
        val window = CreditWindow(caps.ringFrames.takeIf { it > 0 } ?: 16)
        val allowedFlow = MutableStateFlow(window.allowed)
        val playStarted = CompletableDeferred<Long>()
        val listener = launch(start = CoroutineStart.UNDISPATCHED) {
            link.events.collect { e ->
                when {
                    e is GlassesEvent.Credits && e.stream == Frames.STREAM_SPEECH -> { window.grant(e.allowedSeq); allowedFlow.value = window.allowed }
                    e is GlassesEvent.PlayStarted && e.stream == Frames.STREAM_SPEECH -> playStarted.complete(SystemClock.elapsedRealtime())
                    e is GlassesEvent.PlayDone && e.stream == Frames.STREAM_SPEECH && !playStarted.isCompleted ->
                        playStarted.completeExceptionally(IllegalStateException("glasses stopped before playing"))
                }
            }
        }
        try {
            val t0 = SystemClock.elapsedRealtime()
            for ((i, f) in frames.withIndex()) {
                if (!window.canSend(i)) {
                    withTimeoutOrNull(CREDIT_TIMEOUT_MS) { allowedFlow.first { window.canSend(i) } }
                        ?: error("the glasses stopped accepting audio (no credits for 5 s)")
                }
                check(link.writeAudioFrame(f)) { "audio write failed (glasses disconnected?)" }
            }
            Log.d(TAG, "streamed ${frames.size} frames (${pcm16k.size / 16} ms of speech) in ${SystemClock.elapsedRealtime() - t0} ms")
            withTimeout(PLAY_START_TIMEOUT_MS) { playStarted.await() }
        } finally {
            listener.cancel()
        }
    }

    companion object {
        private const val TAG = "GlassesSpeaker"
        private const val CREDIT_TIMEOUT_MS = 5_000L
        private const val PLAY_START_TIMEOUT_MS = 3_000L
    }
}

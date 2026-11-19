package com.smartglasses.app.speech

import android.os.SystemClock
import android.util.Log
import com.smartglasses.app.audio.Resampler
import com.smartglasses.app.audio.SpeechLoudness
import com.smartglasses.app.ble.Frames
import com.smartglasses.app.ble.GlassesLink
import com.smartglasses.app.ble.LinkState
import com.smartglasses.app.metrics.InteractionTimeline
import com.smartglasses.app.metrics.Stage
import com.smartglasses.app.settings.AppSettings
import com.smartglasses.app.settings.SpeakOn
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** Where a sentence was actually spoken, and when audio started. */
data class SpeechResult(
    val where: String,            // "glasses" | "phone" | "glasses + phone"
    val startedAfterMs: Long?,    // from say() to audio start; null if it failed
    val error: String?,           // set if speech failed or fell back
)

/**
 * The one place a sentence reaches the wearer. Follows Settings → "Speak on"
 * (glasses / phone / both); falls back to the phone if the glasses aren't connected,
 * can't play speech, or the stream fails.
 */
class SpeechOutput(
    private val tts: TtsEngine,
    private val glasses: GlassesSpeaker,
    private val link: GlassesLink,
    private val settings: AppSettings,
) {
    @Volatile var lastText: String? = null
        private set

    val voiceDescription get() = tts.voiceDescription

    private fun glassesCanSpeak() =
        link.state.value is LinkState.Connected && link.caps.value?.hasSpeechStream == true

    suspend fun say(text: String, timeline: InteractionTimeline? = null): SpeechResult {
        lastText = text
        val t0 = SystemClock.elapsedRealtime()
        val want = settings.speakOn.value
        if (want == SpeakOn.PHONE || !glassesCanSpeak()) {
            return phone(text, t0, timeline, if (want != SpeakOn.PHONE) "glasses not available; spoke on the phone" else null)
        }
        return try {
            coroutineScope {
                val phoneJob = if (want == SpeakOn.BOTH) async { runCatching { tts.speakOnPhone(text) } } else null
                val pcm = tts.synthesize(text)
                val resampled = Resampler.resample(pcm.samples, pcm.sampleRate, 16000)
                // Small speaker: make speech loud and clear (see SpeechLoudness). The glasses'
                // volume ceiling still applies on top.
                val pcm16 = if (settings.speechBoost.value) SpeechLoudness.process(resampled) else resampled
                timeline?.mark(Stage.TTS_READY)
                val startedAt = glasses.play(pcm16)
                timeline?.mark(Stage.PLAY_STARTED, startedAt)
                phoneJob?.await()
                SpeechResult(if (phoneJob != null) "glasses + phone" else "glasses", startedAt - t0, null)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "glasses speech failed, falling back to the phone", e)
            phone(text, t0, timeline, "glasses speech failed (${e.message}); spoke on the phone")
        }
    }

    private suspend fun phone(text: String, t0: Long, timeline: InteractionTimeline?, note: String?): SpeechResult = try {
        val ms = tts.speakOnPhone(text)
        timeline?.mark(Stage.TTS_READY, t0 + ms)
        timeline?.mark(Stage.PLAY_STARTED, t0 + ms)
        SpeechResult("phone", SystemClock.elapsedRealtime() - t0, note)
    } catch (e: Exception) {
        SpeechResult("nowhere", null, "speech failed: ${e.message}")
    }

    suspend fun replay(): SpeechResult? = lastText?.let { say(it) }

    fun stopPhone() = tts.stopPhone()

    /** Stops speech everywhere (barge-in / Stop button). */
    suspend fun stop() {
        tts.stopPhone()
        if (link.state.value is LinkState.Connected) link.sendCommand(Frames.cmd(Frames.CMD_STOP_PLAYBACK))
    }

    companion object { private const val TAG = "SpeechOutput" }
}

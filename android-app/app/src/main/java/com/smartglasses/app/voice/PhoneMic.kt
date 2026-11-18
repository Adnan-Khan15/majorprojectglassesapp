package com.smartglasses.app.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * The phone's microphone, 16 kHz mono 16-bit: the same format the glasses' mic
 * streams. Used for "Ask with phone mic" (M5) and the STT spike recordings.
 */
class PhoneMic {
    @Volatile private var stopRequested = false

    /** Records until [stop] or [maxMs]; needs RECORD_AUDIO (the caller checks). */
    @SuppressLint("MissingPermission")
    suspend fun record(maxMs: Long = 10_000, onLevel: (Float) -> Unit = {}): ShortArray = withContext(Dispatchers.IO) {
        stopRequested = false
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(min, RATE / 5 * 2))
        check(rec.state == AudioRecord.STATE_INITIALIZED) { "The phone's microphone is unavailable" }
        val maxSamples = (RATE * maxMs / 1000).toInt()
        val out = ShortArray(maxSamples)
        var n = 0
        val buf = ShortArray(RATE / 20)  // 50 ms
        try {
            rec.startRecording()
            while (!stopRequested && n < maxSamples && coroutineContext.isActive) {
                val r = rec.read(buf, 0, minOf(buf.size, maxSamples - n))
                if (r <= 0) break
                System.arraycopy(buf, 0, out, n, r)
                n += r
                onLevel(AudioCheck.dbfs(buf, r))
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
        out.copyOf(n)
    }

    fun stop() { stopRequested = true }

    companion object { const val RATE = 16_000 }
}

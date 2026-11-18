package com.smartglasses.app.audio

import com.smartglasses.app.ble.Frames

/**
 * Splits 16 kHz mono PCM into AUDIO_DOWN frames: 400 samples (25 ms) of IMA ADPCM
 * each, with the encoder state at the start of every frame, so a lost frame never
 * corrupts the next. First frame START, last frame END, sequence from 0.
 */
object SpeechFramer {
    const val FRAME_SAMPLES = 400

    fun frames(pcm: ShortArray, stream: Int = Frames.STREAM_SPEECH): List<ByteArray> {
        val enc = ImaAdpcm()
        // Pad to whole frames (and at least one) with silence.
        val total = maxOf(FRAME_SAMPLES, (pcm.size + FRAME_SAMPLES - 1) / FRAME_SAMPLES * FRAME_SAMPLES)
        val padded = if (pcm.size == total) pcm else pcm.copyOf(total)
        val count = total / FRAME_SAMPLES
        return List(count) { i ->
            val h = Frames.AudioHeader(
                stream = stream,
                flags = (if (i == 0) Frames.AUDIO_FLAG_START else 0) or (if (i == count - 1) Frames.AUDIO_FLAG_END else 0),
                seq = i and 0xFFFF,
                predictor = enc.predictor,
                stepIndex = enc.stepIndex,
                codec = Frames.CODEC_ADPCM,
            )
            Frames.packAudio(h, enc.encode(padded, i * FRAME_SAMPLES, FRAME_SAMPLES))
        }
    }
}

/** The phone may send frame `seq` only while seq < allowed (16-bit wrap-safe). Thread-safe. */
class CreditWindow(initialAllowed: Int) {
    @Volatile var allowed: Int = initialAllowed and 0xFFFF
        private set

    /** Credits only ever move forward (a late, older CREDITS event is ignored). */
    @Synchronized
    fun grant(newAllowed: Int) {
        if (Frames.seqBefore(allowed, newAllowed and 0xFFFF)) allowed = newAllowed and 0xFFFF
    }

    fun canSend(seq: Int): Boolean = Frames.seqBefore(seq and 0xFFFF, allowed)
}

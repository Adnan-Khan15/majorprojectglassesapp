package com.smartglasses.app.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 16-bit PCM WAV read/write (what Android TTS writes and what LiteRT-LM's audio input reads). */
object Wav {
    class Pcm(val samples: ShortArray, val sampleRate: Int)

    /** Parses a PCM16 WAV; stereo is mixed down to mono. Throws on anything else. */
    fun parse(bytes: ByteArray): Pcm {
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE") { "not a WAV file" }
        var pos = 12
        var channels = 0
        var rate = 0
        var bits = 0
        var format = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = bb.getInt(pos + 4)
            val body = pos + 8
            when (id) {
                "fmt " -> {
                    format = bb.getShort(body).toInt() and 0xFFFF
                    channels = bb.getShort(body + 2).toInt()
                    rate = bb.getInt(body + 4)
                    bits = bb.getShort(body + 14).toInt()
                }
                "data" -> {
                    require(format == 1 || format == 0xFFFE) { "WAV is not PCM (format $format)" }
                    require(bits == 16) { "WAV is $bits-bit, expected 16" }
                    require(channels in 1..2) { "WAV has $channels channels" }
                    // Some engines write size 0 or 0xFFFFFFFF while streaming: use what's there.
                    val len = if (size <= 0 || body + size > bytes.size) bytes.size - body else size
                    val frames = len / (2 * channels)
                    val out = ShortArray(frames)
                    for (i in 0 until frames) {
                        out[i] = if (channels == 1) bb.getShort(body + 2 * i)
                        else ((bb.getShort(body + 4 * i).toInt() + bb.getShort(body + 4 * i + 2)) / 2).toShort()
                    }
                    return Pcm(out, rate)
                }
            }
            pos = body + size + (size and 1)
        }
        error("WAV has no data chunk")
    }

    fun write(samples: ShortArray, sampleRate: Int): ByteArray {
        val data = samples.size * 2
        return ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + data); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(sampleRate); putInt(sampleRate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(data)
            samples.forEach { putShort(it) }
        }.array()
    }
}

package com.smartglasses.app.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Converts speech to 16 kHz for the glasses. Downsampling first low-passes with a
 * windowed-sinc FIR at 0.45 x the output rate (no aliasing of sibilants), then
 * linearly interpolates. Plenty for speech; tested for length and passband/stopband.
 */
object Resampler {
    fun resample(input: ShortArray, fromRate: Int, toRate: Int): ShortArray {
        if (fromRate == toRate || input.isEmpty()) return input.copyOf()
        val src = if (toRate < fromRate) lowPass(input, 0.45 * toRate / fromRate) else input
        val outLen = (input.size.toLong() * toRate / fromRate).toInt()
        val ratio = fromRate.toDouble() / toRate
        return ShortArray(outLen) { i ->
            val x = i * ratio
            val i0 = x.toInt().coerceAtMost(src.size - 1)
            val i1 = (i0 + 1).coerceAtMost(src.size - 1)
            val f = x - i0
            (src[i0] * (1 - f) + src[i1] * f).roundToInt().coerceIn(-32768, 32767).toShort()
        }
    }

    /** Windowed-sinc low-pass; [cutoff] as a fraction of the input sample rate (0..0.5). */
    private fun lowPass(x: ShortArray, cutoff: Double, taps: Int = 31): ShortArray {
        val half = taps / 2
        val h = DoubleArray(taps) { n ->
            val m = n - half
            val sinc = if (m == 0) 2 * cutoff else sin(2 * PI * cutoff * m) / (PI * m)
            sinc * (0.54 - 0.46 * cos(2 * PI * n / (taps - 1)))  // Hamming window
        }
        val sum = h.sum()
        for (i in h.indices) h[i] /= sum
        return ShortArray(x.size) { i ->
            var acc = 0.0
            for (k in 0 until taps) {
                val j = (i + k - half).coerceIn(0, x.size - 1)
                acc += x[j] * h[k]
            }
            acc.roundToInt().coerceIn(-32768, 32767).toShort()
        }
    }
}

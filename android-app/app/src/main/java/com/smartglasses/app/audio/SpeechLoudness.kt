package com.smartglasses.app.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Makes TTS speech as loud as possible on the glasses' small 1 W speaker without
 * raising the firmware's volume ceiling.
 *
 * Why speech sounded quiet while test tones were fine (hardware test, 2026-11-19): a tone is
 * a full-scale sine, but rendered speech has a high peak-to-average ratio (its RMS
 * sits far below its peaks), so at the same ceiling it carries much less energy.
 * The chain below is standard broadcast/hearing-aid practice:
 *  1. high-pass ~300 Hz: removes bass a small cavity speaker can't reproduce,
 *     freeing headroom for the frequencies it can (speech intelligibility is 1-4 kHz);
 *  2. pre-normalise, so the compressor works the same whatever level the TTS engine
 *     renders at; then compress (fast attack, slower release): quiet syllables come
 *     up towards loud ones;
 *  3. normalise so nearly all samples reach [TARGET_PEAK] of full scale;
 *  4. soft limiter for the few remaining peaks (no hard clipping, no wrap-around).
 * The glasses then apply their own volume and ceiling to this signal as before.
 */
object SpeechLoudness {
    private const val HPF_HZ = 300.0
    private const val THRESHOLD = 0.1         // compressor threshold (-20 dBFS, after pre-normalising)
    private const val RATIO = 3.0
    private const val ATTACK_MS = 5.0
    private const val RELEASE_MS = 80.0
    private const val TARGET_PEAK = 0.85      // ~ -1.4 dBFS for the 99.5th-percentile sample
    private const val MAX_GAIN = 16.0         // +24 dB at most (don't blow up near-silence)
    private const val KNEE = 0.8              // soft limiter starts here

    fun process(input: ShortArray, rate: Int = 16000): ShortArray {
        if (input.isEmpty()) return input
        val x = DoubleArray(input.size) { input[it] / 32768.0 }
        highPass(x, rate)
        normalise(x)
        compress(x, rate)
        normalise(x)
        return ShortArray(x.size) { (softLimit(x[it]) * 32767).toInt().coerceIn(-32767, 32767).toShort() }
    }

    /** 2nd-order Butterworth high-pass (RBJ biquad), in place. */
    private fun highPass(x: DoubleArray, rate: Int) {
        val w0 = 2 * PI * HPF_HZ / rate
        val alpha = sin(w0) / (2 * sqrt(0.5))
        val c = cos(w0)
        val a0 = 1 + alpha
        val b0 = (1 + c) / 2 / a0
        val b1 = -(1 + c) / a0
        val b2 = b0
        val a1 = -2 * c / a0
        val a2 = (1 - alpha) / a0
        var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
        for (i in x.indices) {
            val y = b0 * x[i] + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x[i]; y2 = y1; y1 = y
            x[i] = y
        }
    }

    /** Feed-forward peak compressor, in place. */
    private fun compress(x: DoubleArray, rate: Int) {
        val att = exp(-1.0 / (rate * ATTACK_MS / 1000))
        val rel = exp(-1.0 / (rate * RELEASE_MS / 1000))
        var env = 0.0
        for (i in x.indices) {
            val a = abs(x[i])
            env = if (a > env) att * env + (1 - att) * a else rel * env + (1 - rel) * a
            if (env > THRESHOLD) x[i] *= (THRESHOLD / env).pow(1 - 1 / RATIO)
        }
    }

    /** Scales so the 99.5th-percentile |sample| reaches TARGET_PEAK (gain capped). */
    private fun normalise(x: DoubleArray) {
        val mags = x.map { abs(it) }.filter { it > 1e-4 }.sorted()
        if (mags.isEmpty()) return
        val ref = mags[((mags.size - 1) * 0.995).toInt()]
        val gain = (TARGET_PEAK / ref).coerceAtMost(MAX_GAIN)
        for (i in x.indices) x[i] *= gain
    }

    /** Linear below KNEE, then a tanh curve that approaches (never exceeds) full scale. */
    fun softLimit(v: Double): Double {
        val a = abs(v)
        if (a <= KNEE) return v
        val over = (a - KNEE) / (1 - KNEE)
        val out = KNEE + (1 - KNEE) * tanh(over)
        return if (v < 0) -out else out
    }

    fun rmsDbfs(x: ShortArray): Double {
        if (x.isEmpty()) return -96.0
        val r = sqrt(x.sumOf { it.toDouble() * it } / x.size) / 32768.0
        return if (r <= 0) -96.0 else 20 * kotlin.math.log10(r)
    }
}

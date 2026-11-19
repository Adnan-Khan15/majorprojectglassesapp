package com.smartglasses.app

import com.smartglasses.app.audio.SpeechLoudness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class SpeechLoudnessTest {
    private val rate = 16000

    /** Speech-like test signal: 250 Hz + 1.2 kHz + 2.5 kHz, in 4 "syllables" of different loudness, peak ~0.3 FS. */
    private fun fakeSpeech(): ShortArray {
        val syllables = listOf(1.0, 0.35, 0.7, 0.2)  // loud, quiet, medium, very quiet
        val per = rate / 4
        return ShortArray(per * syllables.size) { i ->
            val s = syllables[i / per]
            val pos = (i % per).toDouble() / per
            val env = sin(PI * pos)  // each syllable swells and fades
            val v = 0.5 * sin(2 * PI * 250 * i / rate) + 0.35 * sin(2 * PI * 1200 * i / rate) + 0.15 * sin(2 * PI * 2500 * i / rate)
            (0.3 * s * env * v * 32767).toInt().toShort()
        }
    }

    private fun bandRms(x: ShortArray, from: Int, to: Int) = SpeechLoudness.rmsDbfs(x.copyOfRange(from, to))

    @Test
    fun speechGetsMuchLouderWithoutClipping() {
        val input = fakeSpeech()
        val out = SpeechLoudness.process(input)
        val gainDb = SpeechLoudness.rmsDbfs(out) - SpeechLoudness.rmsDbfs(input)
        assertTrue("RMS gain only $gainDb dB", gainDb >= 10)
        assertTrue("samples must stay inside full scale", out.all { abs(it.toInt()) <= 32767 })
        assertEquals(input.size, out.size)
    }

    @Test
    fun quietSyllablesComeUpTowardsLoudOnes() {
        val input = fakeSpeech()
        val out = SpeechLoudness.process(input)
        val q = rate / 4
        val spreadIn = bandRms(input, 0, q) - bandRms(input, 3 * q, 4 * q)    // loud vs very quiet syllable
        val spreadOut = bandRms(out, 0, q) - bandRms(out, 3 * q, 4 * q)
        assertTrue("compression should narrow the loud/quiet gap ($spreadIn -> $spreadOut dB)", spreadOut < spreadIn - 4)
    }

    @Test
    fun bassIsCutRelativeToSpeechBand() {
        val n = rate
        val mixed = ShortArray(n) { i -> (6000 * sin(2 * PI * 80 * i / rate) + 6000 * sin(2 * PI * 2000 * i / rate)).toInt().toShort() }
        val out = SpeechLoudness.process(mixed)
        // Goertzel-free check: correlate with each frequency
        fun level(x: ShortArray, hz: Int): Double {
            var re = 0.0; var im = 0.0
            for (i in 2000 until x.size) { re += x[i] * kotlin.math.cos(2 * PI * hz * i / rate); im += x[i] * sin(2 * PI * hz * i / rate) }
            return kotlin.math.sqrt(re * re + im * im)
        }
        val ratioIn = level(mixed, 2000) / level(mixed, 80)
        val ratioOut = level(out, 2000) / level(out, 80)
        assertTrue("80 Hz should drop by > 12 dB relative to 2 kHz", ratioOut > ratioIn * 4)
    }

    @Test
    fun silenceStaysSilentAndLimiterIsBounded() {
        assertTrue(SpeechLoudness.process(ShortArray(1600)).all { it.toInt() == 0 })
        assertTrue(SpeechLoudness.softLimit(5.0) <= 1.0)
        assertTrue(SpeechLoudness.softLimit(0.9) in 0.8..1.0)
        assertEquals(0.5, SpeechLoudness.softLimit(0.5), 1e-9)
        assertEquals(-SpeechLoudness.softLimit(0.95), SpeechLoudness.softLimit(-0.95), 1e-12)
    }
}

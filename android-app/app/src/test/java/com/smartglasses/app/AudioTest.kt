package com.smartglasses.app

import com.google.gson.JsonParser
import com.smartglasses.app.audio.CreditWindow
import com.smartglasses.app.audio.ImaAdpcm
import com.smartglasses.app.audio.Resampler
import com.smartglasses.app.audio.SpeechFramer
import com.smartglasses.app.audio.Wav
import com.smartglasses.app.ble.Frames
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class AudioTest {

    /** The SAME vectors the firmware's native test checks (scripts/gen_adpcm_vectors.py). */
    @Test
    fun adpcmMatchesGoldenVectorsSharedWithFirmware() {
        val json = javaClass.classLoader!!.getResource("adpcm_golden.json")!!.readText()
        val root = JsonParser.parseString(json).asJsonObject
        val enc = ImaAdpcm()
        for (b in root.getAsJsonArray("blocks")) {
            val o = b.asJsonObject
            assertEquals(o["start_predictor"].asInt, enc.predictor)
            assertEquals(o["start_index"].asInt, enc.stepIndex)
            val pcm = o.getAsJsonArray("pcm").map { it.asInt.toShort() }.toShortArray()
            val adpcm = o.getAsJsonArray("adpcm").map { it.asInt.toByte() }.toByteArray()
            val decoded = o.getAsJsonArray("decoded").map { it.asInt.toShort() }.toShortArray()
            assertArrayEquals(adpcm, enc.encode(pcm))
            val dec = ImaAdpcm(o["start_predictor"].asInt, o["start_index"].asInt)
            assertArrayEquals(decoded, dec.decode(adpcm))
        }
    }

    @Test
    fun wavRoundTripAndStereoMixdown() {
        val s = ShortArray(100) { (it * 300 - 15000).toShort() }
        val back = Wav.parse(Wav.write(s, 22050))
        assertEquals(22050, back.sampleRate)
        assertArrayEquals(s, back.samples)

        // hand-made stereo WAV: L=1000, R=3000 -> mono 2000
        val mono = Wav.write(ShortArray(4) { if (it % 2 == 0) 1000 else 3000 }, 8000)
        mono[22] = 2  // channels = 2
        val st = Wav.parse(mono)
        assertEquals(2, st.samples.size)
        assertTrue(st.samples.all { it.toInt() == 2000 })
    }

    private fun tone(hz: Double, rate: Int, n: Int, amp: Double = 10000.0) =
        ShortArray(n) { (amp * sin(2 * PI * hz * it / rate)).toInt().toShort() }

    private fun rms(x: ShortArray, skip: Int = 50) = sqrt(x.drop(skip).dropLast(skip).map { it.toDouble() * it }.average())

    @Test
    fun resamplerKeepsSpeechBandAndRemovesAliases() {
        val passed = Resampler.resample(tone(1000.0, 24000, 24000), 24000, 16000)
        assertEquals(16000, passed.size)
        assertEquals(10000 / sqrt(2.0), rms(passed), 400.0)         // 1 kHz passes at ~full level
        val blocked = Resampler.resample(tone(10000.0, 24000, 24000), 24000, 16000)
        assertTrue("10 kHz must be filtered before downsampling, rms=${rms(blocked)}", rms(blocked) < 1500)
        assertEquals(32000, Resampler.resample(ShortArray(16000), 8000, 16000).size)
    }

    @Test
    fun framerSplitsIntoAdpcmFramesThatDecodeIndependently() {
        val pcm = tone(440.0, 16000, 1000)
        val frames = SpeechFramer.frames(pcm)
        assertEquals(3, frames.size)  // 1000 samples -> 3 frames of 400 (padded)
        val parsed = frames.map { Frames.parseAudio(it)!! }
        assertTrue(parsed.first().first.isStart && !parsed.first().first.isEnd)
        assertTrue(parsed.last().first.isEnd)
        assertEquals(listOf(0, 1, 2), parsed.map { it.first.seq })
        assertTrue(parsed.all { it.second.size == 200 && it.first.codec == Frames.CODEC_ADPCM })
        // Frame 2 decodes on its own from the state in its header (what the glasses do after a loss).
        val (h, data) = parsed[1]
        val out = ImaAdpcm(h.predictor, h.stepIndex).decode(data)
        val err = (0 until 400).maxOf { abs(out[it] - pcm[400 + it]) }
        assertTrue("ADPCM error too large: $err", err < 1500)
    }

    @Test
    fun creditWindowWrapsAndNeverMovesBackwards() {
        val w = CreditWindow(80)
        assertTrue(w.canSend(79))
        assertFalse(w.canSend(80))
        w.grant(88)
        assertTrue(w.canSend(87))
        w.grant(84)  // stale
        assertEquals(88, w.allowed)
        val wrap = CreditWindow(65530)
        wrap.grant(10)  // wrapped past 65535
        assertTrue(wrap.canSend(65535))
        assertTrue(wrap.canSend(5))
        assertFalse(wrap.canSend(10))
    }
}

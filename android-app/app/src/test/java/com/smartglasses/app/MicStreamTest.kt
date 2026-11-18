package com.smartglasses.app

import com.smartglasses.app.audio.SpeechFramer
import com.smartglasses.app.ble.Frames
import com.smartglasses.app.voice.MicStreamAssembler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

/** The glasses' mic frames use the same layout as speech frames, so SpeechFramer can play the board's role. */
class MicStreamTest {
    private val voice = ShortArray(4000) { (8000 * sin(it * 0.05)).toInt().toShort() }  // 10 frames
    private fun frames() = SpeechFramer.frames(voice, Frames.STREAM_QUESTION).map { Frames.parseAudio(it)!! }

    @Test
    fun reassemblesInOrderFrames() {
        val a = MicStreamAssembler()
        val fs = frames()
        fs.dropLast(1).forEach { (h, d) -> assertFalse(a.add(h, d)) }
        assertTrue(a.add(fs.last().first, fs.last().second))
        assertEquals(4000, a.pcm().size)
        assertEquals(0, a.lostFrames)
    }

    @Test
    fun lostFramesBecomeSilenceOfTheSameLength() {
        val a = MicStreamAssembler()
        frames().filterIndexed { i, _ -> i != 3 && i != 4 }.forEach { (h, d) -> a.add(h, d) }
        val pcm = a.pcm()
        assertEquals(4000, pcm.size)          // timing preserved
        assertEquals(2, a.lostFrames)
        assertTrue((1200 until 2000).all { pcm[it].toInt() == 0 })
        assertTrue((2000 until 2400).any { pcm[it].toInt() != 0 })  // frame 5 decodes on its own
    }

    @Test
    fun headerOnlyEndFrameAndRestart() {
        val a = MicStreamAssembler()
        val fs = frames()
        fs.take(3).forEach { (h, d) -> a.add(h, d) }
        val endOnly = Frames.AudioHeader(Frames.STREAM_QUESTION, Frames.AUDIO_FLAG_END, 3, 0, 0, Frames.CODEC_ADPCM)
        assertTrue(a.add(endOnly, ByteArray(0)))   // firmware's header-only END frame
        assertEquals(1200, a.pcm().size)
        a.add(fs[0].first, fs[0].second)           // START of a new recording resets
        assertEquals(400, a.pcm().size)
        assertFalse(a.ended)
    }
}

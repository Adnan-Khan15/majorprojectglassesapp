package com.smartglasses.app

import com.smartglasses.app.ble.Frames
import com.smartglasses.app.ble.GlassesEvent
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Same byte strings as esp32-firmware/test/test_core/test_core.cpp. */
class FramesTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun imageHeaderV2MatchesFirmwareBytes() {
        val wire = bytes(0x02, 0x03, 0x34, 0x12, 0x30, 0x75, 0x00, 0x00, 0x26, 0x39, 0xF4, 0xCB, 0x80, 0x02, 0xE0, 0x01)
        val h = Frames.parseImageHeader(wire)!!
        assertEquals(2, h.version)
        assertEquals(0x1234, h.imageId)
        assertEquals(30000L, h.size)
        assertEquals(0xCBF43926L, h.crc32)
        assertEquals(640, h.width)
        assertEquals(480, h.height)
        assertEquals(Frames.IMG_FLAG_QUESTION or Frames.IMG_FLAG_RETRIED, h.flags)
        assertArrayEquals(wire, Frames.packImageHeaderV2(h))
    }

    @Test
    fun imageHeaderV1StillParses() {
        val h = Frames.parseImageHeader(bytes(0x30, 0x75, 0, 0, 0x6A, 0x01))!!
        assertEquals(1, h.version)
        assertEquals(30000L, h.size)
        assertEquals(362, h.v1DistanceMm)
        assertNull(Frames.parseImageHeader(ByteArray(7)))
    }

    @Test
    fun audioHeaderMatchesFirmwareBytes() {
        val h = Frames.AudioHeader(Frames.STREAM_SPEECH, Frames.AUDIO_FLAG_START, 0xFFFE, -1234, 37, Frames.CODEC_ADPCM)
        val wire = Frames.packAudio(h, ByteArray(0))
        assertArrayEquals(bytes(0x02, 0x01, 0xFE, 0xFF, 0x2E, 0xFB, 0x25, 0x01), wire)
        val (back, data) = Frames.parseAudio(wire + bytes(9, 8))!!
        assertEquals(h, back)
        assertArrayEquals(bytes(9, 8), data)
        assertNull(Frames.parseAudio(ByteArray(7)))
    }

    @Test
    fun capsMatchesFirmwareBytes() {
        val wire = bytes(2, 0, 4, 0, 0x0D, 0, 0, 0, 0x80, 0x3E, 0x90, 0x01, 1, 1, 9, 60, 50, 0xF4, 0x01, 80, 0, 16, 0, 5, 0)
        val c = Frames.parseCaps(wire)!!
        assertEquals(2, c.protocol)
        assertEquals("0.4.0", c.firmware)
        assertTrue(c.hasCamera && c.hasSpeaker && c.hasMic)
        assertFalse(c.hasTof)
        assertEquals(16000, c.audioRate)
        assertEquals(400, c.samplesPerFrame)
        assertTrue(c.brownoutReset)
        assertEquals(500, c.holdMs)
        assertEquals(80, c.ringFrames)
        assertEquals(16, c.prebufferFrames)
        assertEquals(5, c.tofSamples)
    }

    @Test
    fun tofEventParses() {
        // type 7, ms 1000, image 5, 364 mm, flags VALID|OUT_OF_RANGE, 4 valid of 7, spread 15
        val wire = bytes(0x07, 0xE8, 0x03, 0, 0, 0x05, 0x00, 0x6C, 0x01, 0x03, 4, 7, 0x0F, 0x00)
        val e = Frames.parseEvent(wire) as GlassesEvent.Tof
        assertEquals(1000L, e.boardMs)
        assertEquals(5, e.imageId)
        assertEquals(364, e.reading.medianMm)
        assertTrue(e.reading.isValid)
        assertEquals(4, e.reading.validCount)
        assertEquals(7, e.reading.sampleCount)
        assertEquals(15, e.reading.spreadMm)
    }

    @Test
    fun truncatedOrUnknownEventsAreSafe() {
        assertNull(Frames.parseEvent(bytes(0x07, 0, 0, 0, 0, 0x05)))       // truncated TOF
        assertTrue(Frames.parseEvent(bytes(0x7F, 0, 0, 0, 0)) is GlassesEvent.Unknown)
        assertNull(Frames.parseEvent(bytes(0x01)))
    }

    @Test
    fun seqWrapAround() {
        assertTrue(Frames.seqBefore(10, 20))
        assertFalse(Frames.seqBefore(20, 20))
        assertTrue(Frames.seqBefore(65530, 5))
        assertFalse(Frames.seqBefore(5, 65530))
    }

    @Test
    fun commandsAreLittleEndian() {
        assertArrayEquals(bytes(0x04, 0xE8, 0x03, 0xF4, 0x01), Frames.cmdTone(1000, 500))
        assertArrayEquals(bytes(0x0B, 0x01, 0xF4, 0x01), Frames.cmdSetParam(Frames.PARAM_HOLD_MS, 500))
        assertArrayEquals(bytes(0x09, 0xA0, 0x86, 0x01, 0x00), Frames.cmdU32(Frames.CMD_THROUGHPUT_UP, 100_000))
    }
}

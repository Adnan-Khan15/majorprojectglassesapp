package com.smartglasses.app

import com.smartglasses.app.ai.SceneDescriber
import com.smartglasses.app.ble.CaptureEvent
import com.smartglasses.app.ble.Frames
import com.smartglasses.app.ble.ImageAssembler
import com.smartglasses.app.ble.TofReading
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/** Replays exactly what esp32-firmware sends for one button press (protocol v1 and v2). */
class CapturePipelineTest {

    private var clock = 0L
    private val assembler = ImageAssembler { clock }
    // ~30 KB, the size of a VGA capture, framed by the JPEG start/end markers the assembler checks.
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) +
        java.util.Random(42).let { r -> ByteArray(30_000).also(r::nextBytes) } +
        byteArrayOf(0xFF.toByte(), 0xD9.toByte())

    private fun v1Header(size: Int, distanceMm: Int): ByteArray =
        ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN).putInt(size).putShort(distanceMm.toShort()).array()

    private fun v2Header(data: ByteArray, id: Int, flags: Int = 0, crc: Long = CRC32().apply { update(data) }.value) =
        Frames.packImageHeaderV2(Frames.ImageHeader(2, id, data.size.toLong(), crc, 640, 480, flags, null))

    // Same chunking as the firmware: min(MTU - 3, 244) bytes.
    private fun chunks(data: ByteArray, mtu: Int = 515) = data.toList().chunked(minOf(mtu - 3, 244)).map { it.toByteArray() }

    @Test
    fun v2ReassemblesWithIdAndVerifiedCrc() {
        val started = assembler.onHeader(v2Header(jpeg, id = 7, flags = Frames.IMG_FLAG_RETRIED)).single() as CaptureEvent.Started
        assertEquals(7, started.imageId)
        assertNull(started.v1Tof)  // distance follows as an event in v2
        val events = chunks(jpeg).flatMap { clock += 15; assembler.onChunk(it) }
        val done = events.last() as CaptureEvent.Completed
        assertArrayEquals(jpeg, done.jpeg)
        assertEquals(7, done.imageId)
        assertEquals(clock, done.transferMs)
        assertTrue(done.retriedSmallFrame)
        assertTrue(events.dropLast(1).all { it is CaptureEvent.Progress })
    }

    @Test
    fun v2CrcMismatchIsFlaggedForResend() {
        assembler.onHeader(v2Header(jpeg, id = 9, crc = 0x12345678))
        val last = chunks(jpeg).flatMap { assembler.onChunk(it) }.last() as CaptureEvent.Failed
        assertTrue(last.crcMismatch)
        assertEquals(9, last.imageId)
    }

    @Test
    fun v1StillWorksWithDistanceInHeader() {
        val started = assembler.onHeader(v1Header(jpeg.size, 362)).single() as CaptureEvent.Started
        assertEquals(TofReading.fromV1(362), started.v1Tof)
        val done = chunks(jpeg).flatMap { assembler.onChunk(it) }.last() as CaptureEvent.Completed
        assertEquals(362, done.v1Tof!!.medianMm)
        assertTrue(done.v1Tof!!.isValid)
        assertNull(done.imageId)
    }

    @Test
    fun v1ZeroDistanceMeansNoReading() {
        assembler.onHeader(v1Header(jpeg.size, 0))
        val done = chunks(jpeg).flatMap { assembler.onChunk(it) }.last() as CaptureEvent.Completed
        assertTrue(!done.v1Tof!!.isValid)
    }

    @Test
    fun v1LargeDistanceSurvivesUnsignedDecoding() {
        // 40000 mm doesn't fit a signed short; must decode as 40000, not negative.
        val s = assembler.onHeader(v1Header(jpeg.size, 40000)).single() as CaptureEvent.Started
        assertEquals(40000, s.v1Tof!!.medianMm)
    }

    @Test
    fun corruptedJpegIsRejected() {
        val bad = jpeg.copyOf().also { it[it.size - 1] = 0 }  // missing JPEG end marker
        assembler.onHeader(v2Header(bad, 1))  // CRC matches the bad bytes, so the marker check must catch it
        val last = chunks(bad).flatMap { assembler.onChunk(it) }.last() as CaptureEvent.Failed
        assertTrue(!last.crcMismatch)
    }

    @Test
    fun overflowAndStallAndInterruptedTransferFail() {
        assembler.onHeader(v1Header(10, 300))
        assertTrue(assembler.onChunk(ByteArray(11)).single() is CaptureEvent.Failed)

        assembler.onHeader(v2Header(jpeg, 3))
        assembler.onChunk(jpeg.copyOfRange(0, 244))
        val aborted = assembler.abort("glasses disconnected").single() as CaptureEvent.Failed
        assertEquals(3, aborted.imageId)
        assertTrue(assembler.onChunk(ByteArray(244)).isEmpty())  // stray chunk after abort is ignored

        assembler.onHeader(v2Header(jpeg, 4))
        assembler.onChunk(jpeg.copyOfRange(0, 244))
        val restarted = assembler.onHeader(v2Header(jpeg, 5))
        assertTrue(restarted[0] is CaptureEvent.Failed && restarted[1] is CaptureEvent.Started)
        assertTrue(assembler.onHeader(ByteArray(9)).all { it is CaptureEvent.Failed })  // unfinished photo 5 + unknown header length
    }

    @Test
    fun sentenceIncludesTheRealDistance() {
        assertEquals("a blue pen with Pentel written on it, 36 centimetres",
            SceneDescriber.compose("a blue pen with Pentel written on it", 362))
        assertEquals("a white mug", SceneDescriber.compose("a white mug", null))
        assertEquals("two blue Pentel Superball pens on a grid",
            SceneDescriber.cleanPhrase("Two blue Pentel Superball pens on a grid."))
    }
}

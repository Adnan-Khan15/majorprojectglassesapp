package com.smartglasses.app

import com.smartglasses.app.ai.SceneDescriber
import com.smartglasses.app.ble.CaptureEvent
import com.smartglasses.app.ble.ImageAssembler
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Replays exactly what esp32-firmware/src/main.cpp sends for one button press. */
class CapturePipelineTest {

    private var clock = 0L
    private val assembler = ImageAssembler { clock }
    private val jpeg = javaClass.classLoader!!.getResourceAsStream("pens.jpg")!!.readBytes()

    private fun header(size: Int, distanceMm: Int): ByteArray =
        ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN).putInt(size).putShort(distanceMm.toShort()).array()

    private fun chunks(data: ByteArray, mtu: Int = 515) = data.toList().chunked(mtu - 3).map { it.toByteArray() }

    @Test
    fun reassemblesFirmwareStreamIntoTheSameJpegWithDistance() {
        val started = assembler.onHeader(header(jpeg.size, 362))
        assertEquals(listOf(CaptureEvent.Started(jpeg.size, 362)), started)

        val events = chunks(jpeg).flatMap { clock += 15; assembler.onChunk(it) }
        val done = events.last() as CaptureEvent.Completed
        assertArrayEquals(jpeg, done.jpeg)
        assertEquals(362, done.distanceMm)
        assertEquals(clock, done.transferMs)
        assertTrue(events.dropLast(1).all { it is CaptureEvent.Progress })
    }

    @Test
    fun zeroDistanceMeansNoReading() {
        assembler.onHeader(header(jpeg.size, 0))
        val done = chunks(jpeg).flatMap { assembler.onChunk(it) }.last() as CaptureEvent.Completed
        assertEquals(null, done.distanceMm)
    }

    @Test
    fun largeDistanceSurvivesUnsignedDecoding() {
        // 40000 mm doesn't fit a signed short; must decode as 40000, not negative.
        assertEquals(listOf(CaptureEvent.Started(jpeg.size, 40000)), assembler.onHeader(header(jpeg.size, 40000)))
    }

    @Test
    fun corruptedImageIsRejected() {
        val bad = jpeg.copyOf().also { it[it.size - 1] = 0 }  // missing JPEG end marker
        assembler.onHeader(header(bad.size, 300))
        val last = chunks(bad).flatMap { assembler.onChunk(it) }.last()
        assertTrue(last is CaptureEvent.Failed)
    }

    @Test
    fun overflowAndStallAndInterruptedTransferFail() {
        assembler.onHeader(header(10, 300))
        assertTrue(assembler.onChunk(ByteArray(11)).single() is CaptureEvent.Failed)

        assembler.onHeader(header(jpeg.size, 300))
        assembler.onChunk(jpeg.copyOfRange(0, 512))
        assertTrue(assembler.abort("glasses disconnected").single() is CaptureEvent.Failed)
        assertTrue(assembler.onChunk(ByteArray(512)).isEmpty())  // stray chunk after abort is ignored

        assembler.onHeader(header(jpeg.size, 300))
        assembler.onChunk(jpeg.copyOfRange(0, 512))
        val restarted = assembler.onHeader(header(jpeg.size, 300))
        assertTrue(restarted[0] is CaptureEvent.Failed && restarted[1] is CaptureEvent.Started)
    }

    @Test
    fun sentenceIncludesTheRealDistance() {
        assertEquals("a blue pen with Pentel written on it, 36 centimetres",
            SceneDescriber.compose("a blue pen with Pentel written on it", 362))
        assertEquals("a red Coca-Cola can, 1.5 metres", SceneDescriber.compose("a red Coca-Cola can", 1460))
        assertEquals("a white mug, 2 metres", SceneDescriber.compose("a white mug", 2000))
        assertEquals("a white mug", SceneDescriber.compose("a white mug", null))
        assertEquals("two blue Pentel Superball pens on a grid",
            SceneDescriber.cleanPhrase("Two blue Pentel Superball pens on a grid."))
    }
}

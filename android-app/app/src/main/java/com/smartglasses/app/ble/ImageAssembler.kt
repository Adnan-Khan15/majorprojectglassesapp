package com.smartglasses.app.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Progress of one capture arriving from the glasses. */
sealed interface CaptureEvent {
    data class Started(val totalBytes: Int, val distanceMm: Int?) : CaptureEvent
    data class Progress(val receivedBytes: Int, val totalBytes: Int) : CaptureEvent
    data class Completed(val jpeg: ByteArray, val distanceMm: Int?, val transferMs: Long) : CaptureEvent
    data class Failed(val reason: String) : CaptureEvent
}

/**
 * Reassembles one capture from the CONTROL header + ordered IMAGE_DATA chunks
 * (see GlassesProtocol). Synchronized: chunks arrive on Nordic's
 * callback thread while the stall watchdog may abort from another. Time is injected for testing.
 */
class ImageAssembler(private val now: () -> Long) {

    private var buffer: ByteArray? = null
    private var received = 0
    private var distanceMm: Int? = null
    private var startedAt = 0L

    val inProgress: Boolean @Synchronized get() = buffer != null

    @Synchronized
    fun onHeader(bytes: ByteArray): List<CaptureEvent> {
        val events = mutableListOf<CaptureEvent>()
        if (buffer != null) {
            events += CaptureEvent.Failed("new capture started before the previous photo finished (${received} of ${buffer!!.size} bytes)")
        }
        reset()
        if (bytes.size != GlassesProtocol.CONTROL_HEADER_LEN) {
            return events + CaptureEvent.Failed("unexpected CONTROL header length ${bytes.size}")
        }
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val size = bb.int.toLong() and 0xFFFF_FFFFL
        val mm = bb.short.toInt() and 0xFFFF
        if (size <= 0 || size > GlassesProtocol.MAX_IMAGE_BYTES) {
            return events + CaptureEvent.Failed("implausible image size $size bytes")
        }
        buffer = ByteArray(size.toInt())
        distanceMm = mm.takeIf { it > 0 }  // 0 = no valid ToF reading
        startedAt = now()
        return events + CaptureEvent.Started(size.toInt(), distanceMm)
    }

    @Synchronized
    fun onChunk(bytes: ByteArray): List<CaptureEvent> {
        val buf = buffer ?: return emptyList()  // stray chunk with no header: ignore
        if (received + bytes.size > buf.size) {
            val reason = "received more data than announced (${received + bytes.size} > ${buf.size} bytes)"
            reset()
            return listOf(CaptureEvent.Failed(reason))
        }
        System.arraycopy(bytes, 0, buf, received, bytes.size)
        received += bytes.size
        if (received < buf.size) return listOf(CaptureEvent.Progress(received, buf.size))

        val transferMs = now() - startedAt
        val mm = distanceMm
        reset()
        if (!looksLikeJpeg(buf)) return listOf(CaptureEvent.Failed("received ${buf.size} bytes but they are not a valid JPEG"))
        return listOf(CaptureEvent.Completed(buf, mm, transferMs))
    }

    /** Abandons an in-progress transfer (disconnect, stall). */
    @Synchronized
    fun abort(reason: String): List<CaptureEvent> {
        if (buffer == null) return emptyList()
        val detail = "$reason after ${received} of ${buffer!!.size} bytes"
        reset()
        return listOf(CaptureEvent.Failed(detail))
    }

    private fun reset() {
        buffer = null
        received = 0
        distanceMm = null
    }

    private fun looksLikeJpeg(b: ByteArray) =
        b.size > 4 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() &&
            b[b.size - 2] == 0xFF.toByte() && b[b.size - 1] == 0xD9.toByte()
}

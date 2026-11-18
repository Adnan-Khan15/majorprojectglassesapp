package com.smartglasses.app.ble

import java.util.zip.CRC32

/** Progress of one capture arriving from the glasses. */
sealed interface CaptureEvent {
    /** [v1Tof] is set only by v1 firmware, which put the distance in the header. */
    data class Started(val totalBytes: Int, val imageId: Int?, val question: Boolean, val v1Tof: TofReading?, val resend: Boolean = false) : CaptureEvent
    data class Progress(val receivedBytes: Int, val totalBytes: Int) : CaptureEvent
    data class Completed(
        val jpeg: ByteArray,
        val imageId: Int?,
        val transferMs: Long,
        val question: Boolean,
        val v1Tof: TofReading?,
        val retriedSmallFrame: Boolean,
    ) : CaptureEvent
    /** [resendable]: the glasses still have this photo and a resend may fix it (bad checksum or a lost fast-mode chunk). */
    data class Failed(val reason: String, val imageId: Int? = null, val resendable: Boolean = false) : CaptureEvent
}

/**
 * Reassembles one capture from the CONTROL header (v1 or v2, see [Frames]) plus the
 * ordered IMAGE_DATA chunks, and verifies it (JPEG markers; CRC32 for v2).
 * Synchronized: chunks arrive on Nordic's callback thread while the stall watchdog
 * may abort from another. Time is injected for testing.
 */
class ImageAssembler(private val now: () -> Long) {

    private var header: Frames.ImageHeader? = null
    private var buffer: ByteArray? = null
    private var received = 0
    private var startedAt = 0L
    private var nextSeq = 0

    val inProgress: Boolean @Synchronized get() = buffer != null

    @Synchronized
    fun onHeader(bytes: ByteArray): List<CaptureEvent> {
        val events = mutableListOf<CaptureEvent>()
        buffer?.let { events += CaptureEvent.Failed("new capture started before the previous photo finished ($received of ${it.size} bytes)", header?.imageId) }
        reset()
        val h = Frames.parseImageHeader(bytes)
            ?: return events + CaptureEvent.Failed("unexpected CONTROL header (${bytes.size} bytes)")
        if (h.size <= 0 || h.size > GlassesProtocol.MAX_IMAGE_BYTES) {
            return events + CaptureEvent.Failed("implausible image size ${h.size} bytes", h.imageId)
        }
        header = h
        buffer = ByteArray(h.size.toInt())
        startedAt = now()
        val v1Tof = if (h.version == 1) TofReading.fromV1(h.v1DistanceMm) else null
        return events + CaptureEvent.Started(h.size.toInt(), h.imageId, h.flags and Frames.IMG_FLAG_QUESTION != 0, v1Tof,
            resend = h.flags and Frames.IMG_FLAG_RESEND != 0)
    }

    /** True if the current photo's chunks come as AUDIO_UP stream-5 notifications. */
    val expectsStreamChunks: Boolean @Synchronized get() = header?.let { it.flags and Frames.IMG_FLAG_NOTIFY != 0 } == true

    /**
     * Fast mode: one AUDIO_UP frame of stream 5. Notifications aren't acknowledged, so
     * a sequence gap means a lost chunk: the photo fails as resendable.
     */
    @Synchronized
    fun onStreamChunk(seq: Int, data: ByteArray): List<CaptureEvent> {
        if (!expectsStreamChunks) return emptyList()
        if (seq != nextSeq) {
            val id = header?.imageId
            val reason = "lost photo chunk(s): expected #$nextSeq, got #$seq"
            reset()
            return listOf(CaptureEvent.Failed(reason, id, resendable = true))
        }
        nextSeq = (nextSeq + 1) and 0xFFFF
        return onChunk(data)
    }

    @Synchronized
    fun onChunk(bytes: ByteArray): List<CaptureEvent> {
        val buf = buffer ?: return emptyList()  // stray chunk with no header: ignore
        val h = header!!
        if (received + bytes.size > buf.size) {
            val reason = "received more data than announced (${received + bytes.size} > ${buf.size} bytes)"
            reset()
            return listOf(CaptureEvent.Failed(reason, h.imageId))
        }
        System.arraycopy(bytes, 0, buf, received, bytes.size)
        received += bytes.size
        if (received < buf.size) return listOf(CaptureEvent.Progress(received, buf.size))

        val transferMs = now() - startedAt
        reset()
        h.crc32?.let { want ->
            val got = CRC32().apply { update(buf) }.value
            if (got != want) {
                return listOf(CaptureEvent.Failed("checksum mismatch (got %08x, expected %08x)".format(got, want), h.imageId, resendable = true))
            }
        }
        if (!looksLikeJpeg(buf)) return listOf(CaptureEvent.Failed("received ${buf.size} bytes but they are not a valid JPEG", h.imageId))
        return listOf(CaptureEvent.Completed(
            buf, h.imageId, transferMs,
            question = h.flags and Frames.IMG_FLAG_QUESTION != 0,
            v1Tof = if (h.version == 1) TofReading.fromV1(h.v1DistanceMm) else null,
            retriedSmallFrame = h.flags and Frames.IMG_FLAG_RETRIED != 0,
        ))
    }

    /** Abandons an in-progress transfer (disconnect, stall, glasses aborted). */
    @Synchronized
    fun abort(reason: String): List<CaptureEvent> {
        val buf = buffer ?: return emptyList()
        val detail = "$reason after $received of ${buf.size} bytes"
        val id = header?.imageId
        reset()
        return listOf(CaptureEvent.Failed(detail, id))
    }

    private fun reset() {
        nextSeq = 0
        header = null
        buffer = null
        received = 0
    }

    private fun looksLikeJpeg(b: ByteArray) =
        b.size > 4 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() &&
            b[b.size - 2] == 0xFF.toByte() && b[b.size - 1] == 0xD9.toByte()
}

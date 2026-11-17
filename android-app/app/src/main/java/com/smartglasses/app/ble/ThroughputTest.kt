package com.smartglasses.app.ble

import android.os.SystemClock
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** Counts frames and gaps in a 16-bit sequence-numbered stream. */
class SeqTracker {
    var frames = 0; private set
    var missing = 0; private set
    var bytes = 0L; private set
    private var next: Int? = null

    fun add(seq: Int, payloadBytes: Int) {
        next?.let { expected -> if (seq != expected) missing += (seq - expected) and 0xFFFF }
        next = (seq + 1) and 0xFFFF
        frames++
        bytes += payloadBytes
    }

    val lossPercent: Double get() = if (frames + missing == 0) 0.0 else 100.0 * missing / (frames + missing)
}

data class ThroughputReport(
    val direction: String,
    val bytes: Long,
    val frames: Int,
    val missing: Int,
    val ms: Long,
    val boardRetries: Int,
) {
    val kbPerS: Double get() = if (ms > 0) bytes / 1.024 / ms else 0.0
    val lossPercent: Double get() = if (frames + missing == 0) 0.0 else 100.0 * missing / (frames + missing)
    override fun toString() = "%s: %.1f KB/s, %d bytes in %d frames, %d missing (%.1f%%), %d ms%s".format(
        direction, kbPerS, bytes, frames, missing, lossPercent, ms,
        if (boardRetries > 0) ", board retried $boardRetries times (out of buffers)" else "")
}

/**
 * Phase 3 M3 spike: measures what the BLE link actually carries, to decide between
 * raw PCM (32 KB/s) and IMA ADPCM (~8 KB/s + headers) and whether audio can stream
 * live. Uplink = notifications (AUDIO_UP), downlink = write-without-response (AUDIO_DOWN).
 */
class ThroughputTest(private val link: GlassesLink) {

    /** Glasses -> phone. Timing is measured on the phone, from first to last frame. */
    suspend fun uplink(bytes: Int): ThroughputReport = coroutineScope {
        val tracker = SeqTracker()
        var first = 0L
        var last = 0L
        val collector = async {
            withTimeoutOrNull(60_000) {
                link.audioUp.takeWhile { frame ->
                    val (h, data) = Frames.parseAudio(frame) ?: return@takeWhile true
                    if (h.stream != Frames.STREAM_THROUGHPUT) return@takeWhile true
                    val now = SystemClock.elapsedRealtime()
                    if (tracker.frames == 0) first = now
                    last = now
                    tracker.add(h.seq, data.size)
                    !h.isEnd
                }.collect {}
            }
        }
        val result = async {
            withTimeout(70_000) {
                link.events.filterIsInstance<GlassesEvent.ThroughputResult>().filter { !it.downlink }.first()
            }
        }
        check(link.sendCommand(Frames.cmdU32(Frames.CMD_THROUGHPUT_UP, bytes.toLong()))) { "Glasses not connected or firmware v1" }
        val board = result.await()
        withTimeoutOrNull(2_000) { collector.await() }
        collector.cancel()
        ThroughputReport("uplink (notify)", tracker.bytes, tracker.frames, tracker.missing, last - first, board.retries)
    }

    /** Phone -> glasses. The board measures and reports what arrived. */
    suspend fun downlink(bytes: Int, frameBytes: Int): ThroughputReport = coroutineScope {
        val result = async {
            withTimeout(70_000) {
                link.events.filterIsInstance<GlassesEvent.ThroughputResult>().filter { it.downlink }.first()
            }
        }
        check(link.sendCommand(Frames.cmdU32(Frames.CMD_THROUGHPUT_DOWN, bytes.toLong()))) { "Glasses not connected or firmware v1" }
        val dataLen = (frameBytes - Frames.AUDIO_HDR_LEN).coerceAtLeast(1)
        var sent = 0
        var seq = 0
        while (sent < bytes) {
            val n = minOf(dataLen, bytes - sent)
            val flags = (if (seq == 0) Frames.AUDIO_FLAG_START else 0) or (if (sent + n >= bytes) Frames.AUDIO_FLAG_END else 0)
            val frame = Frames.packAudio(
                Frames.AudioHeader(Frames.STREAM_THROUGHPUT, flags, seq and 0xFFFF, 0, 0, Frames.CODEC_RAW),
                ByteArray(n) { ((sent + it) and 0xFF).toByte() },
            )
            if (!link.writeAudioFrame(frame)) error("Write failed after $sent bytes (link dropped?)")
            sent += n
            seq++
        }
        val r = result.await()
        ThroughputReport("downlink (write without response)", r.bytes, r.frames, r.missing, r.durationMs, 0)
    }
}

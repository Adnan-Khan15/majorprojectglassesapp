package com.smartglasses.app.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Protocol v2 frame layouts, byte-for-byte the same as
 * esp32-firmware/lib/glasses_core/src/glasses_frames.h. All fields little-endian.
 * FramesTest.kt asserts the same byte strings as the firmware's native tests.
 */
object Frames {
    const val IMG_HDR_V1_LEN = 6
    const val IMG_HDR_V2_LEN = 16
    const val IMG_FLAG_QUESTION = 0x01
    const val IMG_FLAG_RETRIED = 0x02
    const val IMG_FLAG_RESEND = 0x04

    const val AUDIO_HDR_LEN = 8
    const val AUDIO_FLAG_START = 0x01
    const val AUDIO_FLAG_END = 0x02
    const val CODEC_RAW = 0
    const val CODEC_ADPCM = 1
    const val STREAM_QUESTION = 1
    const val STREAM_SPEECH = 2
    const val STREAM_THROUGHPUT = 3
    const val STREAM_MIC_TEST = 4

    const val TOF_LIVE_IMAGE_ID = 0xFFFF
    const val MODE_TAP = 0
    const val MODE_QUESTION = 1
    const val MODE_PHONE = 2

    // TOF flags (tof_filter.h)
    const val TOF_FLAG_VALID = 1
    const val TOF_FLAG_OUT_OF_RANGE = 2
    const val TOF_FLAG_TIMEOUT = 4
    const val TOF_FLAG_NO_SENSOR = 8

    // Commands
    const val CMD_CAPTURE = 0x01
    const val CMD_STOP_PLAYBACK = 0x02
    const val CMD_SET_VOLUME = 0x03
    const val CMD_PLAY_TONE = 0x04
    const val CMD_GET_CAPS = 0x05
    const val CMD_PING = 0x06
    const val CMD_RESEND_IMAGE = 0x07
    const val CMD_ABORT_IMAGE = 0x08
    const val CMD_THROUGHPUT_UP = 0x09
    const val CMD_THROUGHPUT_DOWN = 0x0A
    const val CMD_SET_PARAM = 0x0B
    const val CMD_MIC_MONITOR = 0x0C
    const val CMD_TOF_READ = 0x0D
    const val CMD_EARCON = 0x0E
    const val CMD_MIC_TEST = 0x0F

    const val PARAM_HOLD_MS = 1
    const val PARAM_TOF_SAMPLES = 2
    const val PARAM_MIC_GAIN_X10 = 3
    const val PARAM_CEILING = 4
    const val PARAM_DATA_LEN = 5

    const val CAPS_LEN = 25

    private fun le(size: Int): ByteBuffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
    private fun le(b: ByteArray): ByteBuffer = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)

    // ---------------- image header ----------------

    data class ImageHeader(
        val version: Int,
        val imageId: Int?,         // null for v1
        val size: Long,
        val crc32: Long?,          // null for v1
        val width: Int?, val height: Int?,
        val flags: Int,
        val v1DistanceMm: Int?,    // v1 only: distance was in the header (null = no reading)
    )

    /** Parses a v1 (6 B) or v2 (16 B) CONTROL header; null if the length is neither. */
    fun parseImageHeader(b: ByteArray): ImageHeader? = when (b.size) {
        IMG_HDR_V1_LEN -> le(b).let { bb ->
            val size = bb.int.toLong() and 0xFFFF_FFFFL
            val mm = bb.short.toInt() and 0xFFFF
            ImageHeader(1, null, size, null, null, null, 0, mm.takeIf { it > 0 })
        }
        IMG_HDR_V2_LEN -> le(b).let { bb ->
            val ver = bb.get().toInt() and 0xFF
            val flags = bb.get().toInt() and 0xFF
            val id = bb.short.toInt() and 0xFFFF
            val size = bb.int.toLong() and 0xFFFF_FFFFL
            val crc = bb.int.toLong() and 0xFFFF_FFFFL
            val w = bb.short.toInt() and 0xFFFF
            val h = bb.short.toInt() and 0xFFFF
            if (ver != 2) null else ImageHeader(2, id, size, crc, w, h, flags, null)
        }
        else -> null
    }

    fun packImageHeaderV2(h: ImageHeader): ByteArray = le(IMG_HDR_V2_LEN).apply {
        put(2); put(h.flags.toByte()); putShort(h.imageId!!.toShort()); putInt(h.size.toInt())
        putInt(h.crc32!!.toInt()); putShort(h.width!!.toShort()); putShort(h.height!!.toShort())
    }.array()

    // ---------------- audio frames ----------------

    data class AudioHeader(
        val stream: Int, val flags: Int, val seq: Int,
        val predictor: Int, val stepIndex: Int, val codec: Int,
    ) {
        val isStart get() = flags and AUDIO_FLAG_START != 0
        val isEnd get() = flags and AUDIO_FLAG_END != 0
    }

    fun packAudio(h: AudioHeader, data: ByteArray): ByteArray = le(AUDIO_HDR_LEN + data.size).apply {
        put(h.stream.toByte()); put(h.flags.toByte()); putShort(h.seq.toShort())
        putShort(h.predictor.toShort()); put(h.stepIndex.toByte()); put(h.codec.toByte()); put(data)
    }.array()

    fun parseAudio(b: ByteArray): Pair<AudioHeader, ByteArray>? {
        if (b.size < AUDIO_HDR_LEN) return null
        val bb = le(b)
        val h = AudioHeader(
            stream = bb.get().toInt() and 0xFF,
            flags = bb.get().toInt() and 0xFF,
            seq = bb.short.toInt() and 0xFFFF,
            predictor = bb.short.toInt(),
            stepIndex = (bb.get().toInt() and 0xFF).coerceAtMost(88),
            codec = bb.get().toInt() and 0xFF,
        )
        return h to b.copyOfRange(AUDIO_HDR_LEN, b.size)
    }

    /** True if [seq] comes before [allowed] in 16-bit wrap-around order. */
    fun seqBefore(seq: Int, allowed: Int): Boolean = ((seq - allowed) and 0xFFFF).toShort() < 0

    // ---------------- events ----------------

    data class Caps(
        val protocol: Int, val firmware: String, val features: Int,
        val audioRate: Int, val samplesPerFrame: Int, val codec: Int, val tofType: Int,
        val resetReason: Int, val volume: Int, val ceiling: Int, val holdMs: Int,
        val ringFrames: Int, val prebufferFrames: Int, val tofSamples: Int,
    ) {
        val hasCamera get() = features and 0x01 != 0
        val hasTof get() = features and 0x02 != 0
        val hasSpeaker get() = features and 0x04 != 0
        val hasMic get() = features and 0x08 != 0
        val brownoutReset get() = resetReason == 9
    }

    fun parseCaps(p: ByteArray): Caps? {
        if (p.size < CAPS_LEN) return null
        val bb = le(p)
        val proto = bb.get().toInt() and 0xFF
        val fw = "${bb.get().toInt() and 0xFF}.${bb.get().toInt() and 0xFF}.${bb.get().toInt() and 0xFF}"
        return Caps(
            protocol = proto, firmware = fw, features = bb.int,
            audioRate = bb.short.toInt() and 0xFFFF, samplesPerFrame = bb.short.toInt() and 0xFFFF,
            codec = bb.get().toInt() and 0xFF, tofType = bb.get().toInt() and 0xFF,
            resetReason = bb.get().toInt() and 0xFF, volume = bb.get().toInt() and 0xFF,
            ceiling = bb.get().toInt() and 0xFF, holdMs = bb.short.toInt() and 0xFFFF,
            ringFrames = bb.short.toInt() and 0xFFFF, prebufferFrames = bb.short.toInt() and 0xFFFF,
            tofSamples = bb.get().toInt() and 0xFF,
        )
    }

    /** Parses an EVENT notification into a [GlassesEvent]; Unknown for types this app doesn't know. */
    fun parseEvent(b: ByteArray): GlassesEvent? {
        if (b.size < 5) return null
        val bb = le(b)
        val type = bb.get().toInt() and 0xFF
        val ms = bb.int.toLong() and 0xFFFF_FFFFL
        fun u8() = bb.get().toInt() and 0xFF
        fun u16() = bb.short.toInt() and 0xFFFF
        fun u32() = bb.int.toLong() and 0xFFFF_FFFFL
        fun has(n: Int) = bb.remaining() >= n
        return try {
            when (type) {
                0x01 -> GlassesEvent.ButtonDown(ms)
                0x02 -> GlassesEvent.ButtonUp(ms, u16())
                0x03 -> GlassesEvent.HoldDetected(ms)
                0x04 -> GlassesEvent.RecordStart(ms, u8())
                0x05 -> GlassesEvent.RecordStop(ms, u8(), u16(), u8())
                0x06 -> GlassesEvent.PhotoCaptured(ms, u16(), u32(), u16(), u16(), u8())
                0x07 -> GlassesEvent.Tof(ms, u16(), TofReading(u16(), u8(), u8(), u8(), u16()))
                0x08 -> GlassesEvent.PlayStarted(ms, u8())
                0x09 -> GlassesEvent.PlayDone(ms, u8(), u8(), u16())
                0x0A -> GlassesEvent.Underrun(ms, u8(), u16())
                0x0B -> GlassesEvent.Credits(ms, u8(), u16())
                0x0C -> GlassesEvent.Error(ms, u8(), ByteArray(bb.remaining()).also { bb.get(it) }.toString(Charsets.US_ASCII))
                0x0D -> parseCaps(ByteArray(bb.remaining()).also { bb.get(it) })?.let { GlassesEvent.CapsEvent(ms, it) }
                0x0E -> GlassesEvent.Pong(ms, u32())
                0x0F -> GlassesEvent.ThroughputResult(ms, u8() == 1, u32(), u16(), u16(), u32(), if (has(2)) u16() else 0)
                0x10 -> GlassesEvent.MicLevel(ms, bb.short / 100f, bb.short / 100f)
                0x11 -> GlassesEvent.ImageAborted(ms, u16(), u8())
                else -> GlassesEvent.Unknown(ms, type)
            }
        } catch (e: java.nio.BufferUnderflowException) {
            null  // truncated event: ignore rather than crash
        }
    }

    // ---------------- commands ----------------

    fun cmd(op: Int, vararg payload: Byte) = byteArrayOf(op.toByte(), *payload)
    fun cmdU8(op: Int, v: Int) = byteArrayOf(op.toByte(), v.toByte())
    fun cmdU16(op: Int, v: Int) = le(3).put(op.toByte()).putShort(v.toShort()).array()
    fun cmdU32(op: Int, v: Long) = le(5).put(op.toByte()).putInt(v.toInt()).array()
    fun cmdTone(hz: Int, ms: Int) = le(5).put(CMD_PLAY_TONE.toByte()).putShort(hz.toShort()).putShort(ms.toShort()).array()
    fun cmdSetParam(param: Int, value: Int) = le(4).put(CMD_SET_PARAM.toByte()).put(param.toByte()).putShort(value.toShort()).array()
}

/** Capture-time distance reading as sent by the glasses (median of N samples). */
data class TofReading(
    val medianMm: Int,
    val flags: Int,
    val validCount: Int,
    val sampleCount: Int,
    val spreadMm: Int,
) {
    val isValid get() = flags and Frames.TOF_FLAG_VALID != 0 && medianMm > 0
    val noSensor get() = flags and Frames.TOF_FLAG_NO_SENSOR != 0

    companion object {
        /** v1 headers carried only a distance (0 = no reading). */
        fun fromV1(mm: Int?) = if (mm != null && mm > 0) TofReading(mm, Frames.TOF_FLAG_VALID, 1, 1, 0)
            else TofReading(0, Frames.TOF_FLAG_OUT_OF_RANGE, 0, 1, 0)
        val MISSING = TofReading(0, Frames.TOF_FLAG_TIMEOUT, 0, 0, 0)
    }
}

/** Things the glasses report on EVENT. [boardMs] is the board's millis() when it happened. */
sealed interface GlassesEvent {
    val boardMs: Long
    data class ButtonDown(override val boardMs: Long) : GlassesEvent
    data class ButtonUp(override val boardMs: Long, val heldMs: Int) : GlassesEvent
    data class HoldDetected(override val boardMs: Long) : GlassesEvent
    data class RecordStart(override val boardMs: Long, val stream: Int) : GlassesEvent
    data class RecordStop(override val boardMs: Long, val stream: Int, val frames: Int, val reason: Int) : GlassesEvent
    data class PhotoCaptured(override val boardMs: Long, val imageId: Int, val size: Long, val pressToCaptureMs: Int,
                             val rejectedFirstLen: Int, val mode: Int) : GlassesEvent
    data class Tof(override val boardMs: Long, val imageId: Int, val reading: TofReading) : GlassesEvent
    data class PlayStarted(override val boardMs: Long, val stream: Int) : GlassesEvent
    data class PlayDone(override val boardMs: Long, val stream: Int, val reason: Int, val underruns: Int) : GlassesEvent
    data class Underrun(override val boardMs: Long, val stream: Int, val count: Int) : GlassesEvent
    data class Credits(override val boardMs: Long, val stream: Int, val allowedSeq: Int) : GlassesEvent
    data class Error(override val boardMs: Long, val code: Int, val text: String) : GlassesEvent
    data class CapsEvent(override val boardMs: Long, val caps: Frames.Caps) : GlassesEvent
    data class Pong(override val boardMs: Long, val token: Long) : GlassesEvent
    data class ThroughputResult(override val boardMs: Long, val downlink: Boolean, val bytes: Long, val frames: Int,
                                val missing: Int, val durationMs: Long, val retries: Int) : GlassesEvent
    data class MicLevel(override val boardMs: Long, val rmsDbfs: Float, val peakDbfs: Float) : GlassesEvent
    data class ImageAborted(override val boardMs: Long, val imageId: Int, val reason: Int) : GlassesEvent
    data class Unknown(override val boardMs: Long, val type: Int) : GlassesEvent
}

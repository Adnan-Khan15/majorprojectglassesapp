package com.smartglasses.app.voice

import android.util.Log
import com.smartglasses.app.audio.ImaAdpcm
import com.smartglasses.app.ble.Frames
import com.smartglasses.app.ble.GlassesEvent
import com.smartglasses.app.ble.GlassesLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Rebuilds one mic recording from AUDIO_UP frames. Each frame carries its own ADPCM
 * start state, so a lost frame costs only its 25 ms, which is filled with silence to
 * keep the timing of the speech intact.
 */
class MicStreamAssembler(private val maxSamples: Int = 16000 * 12) {
    private val out = ArrayList<Short>(16000 * 4)
    private var nextSeq: Int? = null
    var frames = 0; private set
    var lostFrames = 0; private set
    var ended = false; private set

    fun reset() { out.clear(); nextSeq = null; frames = 0; lostFrames = 0; ended = false }

    /** Adds one frame (header already parsed). Returns true once the END frame arrived. */
    fun add(h: Frames.AudioHeader, data: ByteArray): Boolean {
        if (h.isStart) reset()
        val expected = nextSeq
        if (expected != null && h.seq != expected) {
            val missing = (h.seq - expected) and 0xFFFF
            if (missing in 1..400) {  // a gap; anything larger is a stale/out-of-order frame
                lostFrames += missing
                repeat(missing * FRAME) { if (out.size < maxSamples) out.add(0) }
            }
        }
        nextSeq = (h.seq + 1) and 0xFFFF
        if (data.isNotEmpty()) {
            frames++
            ImaAdpcm(h.predictor, h.stepIndex).decode(data).forEach { if (out.size < maxSamples) out.add(it) }
        }
        if (h.isEnd) ended = true
        return ended
    }

    fun pcm(): ShortArray = out.toShortArray()

    companion object { const val FRAME = 400 }
}

/**
 * Listens for the glasses' mic streams: hold-to-talk questions (stream 1) go to the
 * [onQuestion] callbacks; Diagnostics mic tests (stream 4) appear in [micTest].
 */
class GlassesMicReceiver(
    private val link: GlassesLink,
    private val scope: CoroutineScope,
    private val onQuestionStarted: () -> Unit,
    private val onQuestion: (ShortArray?) -> Unit,
) {
    private val question = MicStreamAssembler()
    private val test = MicStreamAssembler()
    private var questionOpen = false
    private var finishJob: Job? = null

    private val _micTest = MutableStateFlow<ShortArray?>(null)
    /** Latest Diagnostics mic-test recording (16 kHz), for playback on the phone. */
    val micTest: StateFlow<ShortArray?> = _micTest.asStateFlow()

    fun start() {
        scope.launch {
            link.audioUp.collect { bytes ->
                val (h, data) = Frames.parseAudio(bytes) ?: return@collect
                when (h.stream) {
                    Frames.STREAM_QUESTION -> if (question.add(h, data)) finishQuestion()
                    Frames.STREAM_MIC_TEST -> if (test.add(h, data)) _micTest.value = test.pcm()
                }
            }
        }
        scope.launch {
            link.events.collect { e ->
                when {
                    e is GlassesEvent.RecordStart && e.stream == Frames.STREAM_QUESTION -> {
                        question.reset()
                        questionOpen = true
                        onQuestionStarted()
                    }
                    e is GlassesEvent.RecordStart && e.stream == Frames.STREAM_MIC_TEST -> test.reset()
                    // If the END frame was lost, RECORD_STOP still closes the recording (after a short grace period).
                    e is GlassesEvent.RecordStop && e.stream == Frames.STREAM_QUESTION -> {
                        finishJob?.cancel()
                        finishJob = scope.launch { delay(300); finishQuestion() }
                    }
                }
            }
        }
    }

    @Synchronized
    private fun finishQuestion() {
        if (!questionOpen) return
        questionOpen = false
        finishJob?.cancel()
        val pcm = question.pcm()
        Log.d("GlassesMic", "question audio: ${pcm.size / 16} ms, ${question.frames} frames, ${question.lostFrames} lost")
        onQuestion(if (pcm.isEmpty()) null else pcm)
    }
}

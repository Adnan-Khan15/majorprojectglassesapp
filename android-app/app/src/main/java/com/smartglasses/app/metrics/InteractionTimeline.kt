package com.smartglasses.app.metrics

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Stages of one interaction, in the order they normally happen. Each is recorded as
 * the PHONE's clock when the phone learned about it. Board-side stages (button,
 * recording, capture, playback) therefore include one BLE hop of latency; see
 * docs/HARDWARE_TESTS.md.
 */
enum class Stage(val column: String) {
    BUTTON_DOWN("button_down"),
    RECORD_START("record_start"),
    BUTTON_UP("button_up"),
    PHOTO_CAPTURED("photo_captured"),
    IMAGE_HEADER("image_header"),
    IMAGE_RECEIVED("image_received"),
    AUDIO_RECEIVED("audio_received"),
    TOF_RECEIVED("tof_received"),
    TRANSCRIPT_READY("transcript_ready"),
    OCR_DONE("ocr_done"),
    FIRST_MODEL_OUTPUT("first_model_output"),
    ANSWER_COMPLETE("answer_complete"),
    TTS_READY("tts_ready"),
    PLAY_STARTED("play_started"),
}

/**
 * Timestamps for one interaction (button press or gallery photo -> audio playing).
 * Times are monotonic ms from [clock]; the CSV stores them as offsets from the
 * first recorded stage, so rows from different runs line up.
 */
class InteractionTimeline(
    val id: String,
    val mode: String,      // "tap" | "question" | "phone_question"
    val source: String,    // "glasses" | "gallery" | "phone_mic"
    private val clock: () -> Long,
    val wallStartMs: Long = System.currentTimeMillis(),
) {
    private val marks = LinkedHashMap<Stage, Long>()
    private val extras = LinkedHashMap<String, String>()
    var outcome: String = "incomplete"

    /** Records [stage] now. The first mark of a stage wins; repeats are ignored. */
    @Synchronized
    fun mark(stage: Stage, at: Long = clock()) {
        marks.putIfAbsent(stage, at)
    }

    @Synchronized
    fun put(key: String, value: Any?) {
        if (value != null) extras[key] = value.toString()
    }

    @Synchronized
    fun at(stage: Stage): Long? = marks[stage]

    /** ms between two recorded stages, or null if either is missing. */
    @Synchronized
    fun between(from: Stage, to: Stage): Long? {
        val a = marks[from] ?: return null
        val b = marks[to] ?: return null
        return b - a
    }

    @Synchronized
    fun toCsvRow(): String {
        val t0 = marks.values.minOrNull() ?: 0L
        val cells = mutableListOf(id, isoUtc(wallStartMs), mode, source, outcome)
        Stage.entries.forEach { s -> cells += marks[s]?.let { (it - t0).toString() } ?: "" }
        EXTRA_COLUMNS.forEach { cells += extras[it] ?: "" }
        return cells.joinToString(",") { csvEscape(it) }
    }

    companion object {
        /** Free-form per-interaction facts, in a fixed column order. */
        val EXTRA_COLUMNS = listOf(
            "image_bytes", "transfer_kbps", "distance_mm", "tof_flags", "tof_samples", "tof_spread_mm",
            "engine", "question", "answer", "error",
        )

        val CSV_HEADER: String =
            (listOf("id", "started_at_utc", "mode", "source", "outcome") +
                Stage.entries.map { it.column + "_ms" } + EXTRA_COLUMNS).joinToString(",")

        fun csvEscape(s: String): String =
            if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

        private fun isoUtc(ms: Long): String =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(Date(ms))
    }
}

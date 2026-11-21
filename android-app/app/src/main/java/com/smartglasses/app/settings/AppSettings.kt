package com.smartglasses.app.settings

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SpeakOn { GLASSES, PHONE, BOTH }
enum class Units { METRIC, IMPERIAL }
enum class AnswerLength { BRIEF, DETAILED }
/** Which of the model's 3x3 grid cells count as "in the ToF cone" (see docs/TOF_TEST_PROTOCOL.md). */
enum class DistanceRegion { CENTER_ONLY, CENTER_COLUMN }
enum class SttEngine { GEMMA_AUDIO, ANDROID_ON_DEVICE }
/** Clockwise rotation that makes the glasses' photos upright (depends on how the camera is mounted). */
enum class CameraRotation(val degrees: Int) { NONE(0), CW_90(90), CW_180(180), CW_270(270) }

/** One persisted setting, exposed as a StateFlow for Compose. */
class Pref<T>(
    private val prefs: SharedPreferences,
    private val key: String,
    private val default: T,
    private val read: SharedPreferences.(String, T) -> T,
    private val write: SharedPreferences.Editor.(String, T) -> Unit,
) {
    private val flow = MutableStateFlow(prefs.read(key, default))
    val state: StateFlow<T> = flow.asStateFlow()
    val value: T get() = flow.value
    fun set(v: T) {
        flow.value = v
        prefs.edit().apply { write(key, v) }.apply()
    }
}

/** Every user-adjustable setting in one place (Settings screen, M7). SharedPreferences-backed. */
class AppSettings(private val prefs: SharedPreferences) {
    private fun int(key: String, d: Int) = Pref(prefs, key, d, { k, v -> getInt(k, v) }, { k, v -> putInt(k, v) })
    private fun bool(key: String, d: Boolean) = Pref(prefs, key, d, { k, v -> getBoolean(k, v) }, { k, v -> putBoolean(k, v) })
    private fun float(key: String, d: Float) = Pref(prefs, key, d, { k, v -> getFloat(k, v) }, { k, v -> putFloat(k, v) })
    private inline fun <reified E : Enum<E>> enum(key: String, d: E) = Pref(prefs, key, d,
        { k, v -> runCatching { enumValueOf<E>(getString(k, v.name)!!) }.getOrDefault(v) }, { k, v -> putString(k, v.name) })

    val setupDone = bool("setup_done", false)
    val speakOn = enum("speak_on", SpeakOn.GLASSES)
    /** % of the glasses' loudness ceiling. */
    val glassesVolume = int("glasses_volume", 100)
    /** % of full scale; above 50 the UI warns (1 W / 8 ohm speaker). */
    val glassesCeiling = int("glasses_ceiling", 50)
    val speechRate = float("speech_rate", 1.0f)
    /** Compress + normalise speech for the glasses' small speaker (much louder at the same ceiling). */
    val speechBoost = bool("speech_boost", true)
    val units = enum("units", Units.METRIC)
    val answerLength = enum("answer_length", AnswerLength.BRIEF)
    val holdMs = int("hold_ms", 500)
    val distanceRegion = enum("distance_region", DistanceRegion.CENTER_ONLY)
    /** ToF calibration: corrected = raw * scale + offset (from the Diagnostics walk-through). */
    val tofOffsetMm = int("tof_offset_mm", 0)
    val tofScale = float("tof_scale", 1.0f)
    /** Photos as sequence-numbered notifications (fast) instead of ACKed indications. */
    val fastPhotos = bool("fast_photos", true)
    /** Speak state changes (connected, model ready, ...) for a blind wearer. */
    val announceStates = bool("announce_states", true)
    val downloadWifiOnly = bool("download_wifi_only", true)
    val sttEngine = enum("stt_engine", SttEngine.GEMMA_AUDIO)
    /** The camera is mounted on its side on the glasses: photos arrive rotated 90° anticlockwise. */
    val cameraRotation = enum("camera_rotation", CameraRotation.CW_90)
}

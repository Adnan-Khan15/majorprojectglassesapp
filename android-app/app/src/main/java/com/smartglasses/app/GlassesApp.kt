package com.smartglasses.app

import android.app.Application
import com.smartglasses.app.ai.ModelActions
import com.smartglasses.app.ai.ModelStore
import com.smartglasses.app.ai.PrefsVerifiedCache
import com.smartglasses.app.ai.SceneDescriber
import com.smartglasses.app.assistant.AssistantSession
import com.smartglasses.app.assistant.StateAnnouncer
import com.smartglasses.app.history.HistoryStore
import kotlinx.coroutines.launch
import com.smartglasses.app.ble.Frames
import com.smartglasses.app.ble.GlassesLink
import com.smartglasses.app.settings.AppSettings
import com.smartglasses.app.speech.GlassesSpeaker
import com.smartglasses.app.speech.SpeechOutput
import com.smartglasses.app.speech.TtsEngine
import com.smartglasses.app.voice.AndroidOnDeviceStt
import com.smartglasses.app.voice.GlassesMicReceiver
import com.smartglasses.app.metrics.LatencyLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class GlassesApp : Application() {
    /** Process-wide scope: survives Activity recreation. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings by lazy { AppSettings(getSharedPreferences("settings", MODE_PRIVATE)) }
    val link by lazy { GlassesLink(this, appScope) }
    val modelStore by lazy {
        ModelStore(
            spec = ModelActions.loadSpec(this),
            internalDir = java.io.File(filesDir, "models"),
            // Developer drop-in: adb push the file here and it is used in place, no copy.
            dropInDir = getExternalFilesDir("models"),
            cache = PrefsVerifiedCache(getSharedPreferences("model_store", MODE_PRIVATE)),
        )
    }
    val modelActions by lazy { ModelActions(this, appScope, modelStore) }
    val describer by lazy { SceneDescriber(this, appScope, modelStore) }

    val tts by lazy { TtsEngine(this, settings) }
    /** Where every spoken sentence goes: glasses speaker, phone, or both (Settings). */
    val speech by lazy { SpeechOutput(tts, GlassesSpeaker(link), link, settings) }

    val latencyLog by lazy { LatencyLog(filesDir) }
    val androidStt by lazy { AndroidOnDeviceStt(this) }
    val session by lazy { AssistantSession(appScope, link, describer, speech, latencyLog, settings, androidStt) }
    val history by lazy { HistoryStore(java.io.File(filesDir, "history"), appScope) }
    private val announcer by lazy { StateAnnouncer(appScope, link, describer, speech, settings) }
    /** Hold-to-talk audio and Diagnostics mic tests from the glasses' microphone. */
    val glassesMic by lazy { GlassesMicReceiver(link, appScope, session::glassesQuestionStarted, session::glassesQuestionAudio) }

    override fun onCreate() {
        super.onCreate()
        // Find + load the model immediately so it's ready by the first capture.
        describer.prepare()
        // Once per connection, push the app's settings to the glasses.
        link.onFirstCaps = { caps ->
            if (caps.hasImageNotify) {
                link.sendCommand(Frames.cmdSetParam(Frames.PARAM_IMAGE_MODE, if (settings.fastPhotos.value) 1 else 0))
            }
            link.sendCommand(Frames.cmdU8(Frames.CMD_SET_VOLUME, settings.glassesVolume.value))
            link.sendCommand(Frames.cmdSetParam(Frames.PARAM_HOLD_MS, settings.holdMs.value))
            if (caps.ceiling != settings.glassesCeiling.value) {
                link.sendCommand(Frames.cmdSetParam(Frames.PARAM_CEILING, settings.glassesCeiling.value))
            }
        }
        tts  // start the TTS engine now, not on the first capture
        session.start()
        glassesMic.start()
        history.load()
        appScope.launch { session.completed.collect { history.add(it) } }
        announcer.start()
    }
}

val android.content.Context.glassesApp get() = applicationContext as GlassesApp

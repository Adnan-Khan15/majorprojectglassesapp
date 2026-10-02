package com.smartglasses.app

import android.app.Application
import com.smartglasses.app.ai.SceneDescriber
import com.smartglasses.app.assistant.AssistantSession
import com.smartglasses.app.assistant.PhoneTtsAnnouncer
import com.smartglasses.app.assistant.ResultAnnouncer
import com.smartglasses.app.ble.GlassesLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class GlassesApp : Application() {
    /** Process-wide scope: survives Activity recreation. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val link by lazy { GlassesLink(this, appScope) }
    val describer by lazy { SceneDescriber(this, appScope) }

    // TODO(Phase 7): bind BoardSpeakerAnnouncer here (SAM on the glasses) once the
    // MAX98357A + speaker are wired. This is the only line that should change.
    private val announcer: ResultAnnouncer by lazy { PhoneTtsAnnouncer(this) }

    val session by lazy { AssistantSession(appScope, link, describer, announcer) }

    override fun onCreate() {
        super.onCreate()
        // Start unpacking/loading the model immediately so it's ready by the first capture.
        describer.prepare()
        announcer  // warm up the TTS engine now, not on the first capture
        session.start()
    }
}

val android.content.Context.glassesApp get() = applicationContext as GlassesApp

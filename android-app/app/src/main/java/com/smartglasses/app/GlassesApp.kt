package com.smartglasses.app

import android.app.Application
import com.smartglasses.app.ai.SceneDescriber
import com.smartglasses.app.ble.GlassesLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class GlassesApp : Application() {
    /** Process-wide scope: survives Activity recreation. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val link by lazy { GlassesLink(this, appScope) }
    val describer by lazy { SceneDescriber(this, appScope) }

    override fun onCreate() {
        super.onCreate()
        // Start unpacking/loading the model immediately so it's ready by the first capture.
        describer.prepare()
    }
}

val android.content.Context.glassesApp get() = applicationContext as GlassesApp

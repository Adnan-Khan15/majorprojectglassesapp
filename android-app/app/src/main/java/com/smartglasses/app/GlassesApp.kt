package com.smartglasses.app

import android.app.Application
import com.smartglasses.app.ble.GlassesLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class GlassesApp : Application() {
    /** Process-wide scope: survives Activity recreation. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val link by lazy { GlassesLink(this, appScope) }
}

val android.content.Context.glassesApp get() = applicationContext as GlassesApp

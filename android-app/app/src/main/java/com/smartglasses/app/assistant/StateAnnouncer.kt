package com.smartglasses.app.assistant

import com.smartglasses.app.ai.ModelState
import com.smartglasses.app.ai.SceneDescriber
import com.smartglasses.app.ble.GlassesLink
import com.smartglasses.app.ble.LinkState
import com.smartglasses.app.settings.AppSettings
import com.smartglasses.app.speech.SpeechOutput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Speaks state changes for a blind wearer (Settings → Announce status): glasses
 * connected / disconnected, AI ready. Uses the same speech route as answers, so
 * with the glasses disconnected it falls back to the phone.
 */
class StateAnnouncer(
    private val scope: CoroutineScope,
    private val link: GlassesLink,
    private val describer: SceneDescriber,
    private val speech: SpeechOutput,
    private val settings: AppSettings,
) {
    fun start() {
        scope.launch {
            var wasConnected = false
            link.state.map { it is LinkState.Connected }.distinctUntilChanged().collect { connected ->
                if (connected != wasConnected && settings.announceStates.value) {
                    speech.say(if (connected) "Glasses connected." else "Glasses disconnected.")
                }
                wasConnected = connected
            }
        }
        scope.launch {
            var announced = false
            describer.state.collect { s ->
                if (s is ModelState.Ready && !announced) {
                    announced = true
                    if (settings.announceStates.value) speech.say("AI ready.")
                }
                if (s !is ModelState.Ready) announced = false
            }
        }
    }
}

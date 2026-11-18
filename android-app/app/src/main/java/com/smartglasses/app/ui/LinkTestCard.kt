package com.smartglasses.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Slider
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartglasses.app.ble.Frames
import com.smartglasses.app.ble.ThroughputTest
import com.smartglasses.app.glassesApp
import com.smartglasses.app.ble.GlassesEvent
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Tester tools for the BLE link (M3). Moves into the Diagnostics screen in M7. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LinkTestCard() {
    val app = LocalContext.current.glassesApp
    val link = app.link
    val settings = app.settings
    val volume by settings.glassesVolume.state.collectAsStateWithLifecycle()
    val ceiling by settings.glassesCeiling.state.collectAsStateWithLifecycle()
    val fast by settings.fastPhotos.state.collectAsStateWithLifecycle()
    val caps by link.caps.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val results = remember { mutableStateListOf<String>() }
    var running by remember { mutableStateOf(false) }
    var use2m by remember { mutableStateOf(false) }

    fun run(label: String, block: suspend () -> String) {
        if (running) return
        running = true
        scope.launch {
            results.add(0, try { block() } catch (e: Exception) { "$label failed: ${e.message}" })
            running = false
        }
    }

    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Link tests", style = MaterialTheme.typography.titleMedium)
        Text(caps?.let { "Firmware ${it.firmware}, protocol v${it.protocol} · speaker ${it.hasSpeaker} · mic ${it.hasMic} · ToF ${it.hasTof}" +
            if (it.brownoutReset) " · last reset was a BROWNOUT" else "" } ?: "No v2 glasses connected",
            style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = use2m, onCheckedChange = { use2m = it; scope.launch { link.request2mPhy(it) } })
            Text("Request 2M PHY")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = fast, onCheckedChange = {
                settings.fastPhotos.set(it)
                scope.launch { link.sendCommand(Frames.cmdSetParam(Frames.PARAM_IMAGE_MODE, if (it) 1 else 0)) }
            })
            Text("Fast photo transfer (notifications)")
        }

        Text("Glasses speaker: volume $volume % of a $ceiling % ceiling = ${volume * ceiling / 100} % of full power",
            style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = volume.toFloat(), valueRange = 0f..100f, steps = 9,
            onValueChange = { settings.glassesVolume.set(it.toInt()) },
            onValueChangeFinished = { scope.launch { link.sendCommand(Frames.cmdU8(Frames.CMD_SET_VOLUME, settings.glassesVolume.value)) } },
            modifier = Modifier.semantics { contentDescription = "Glasses volume $volume percent" },
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(30, 50, 70, 100).forEach { c ->
                FilterChip(selected = ceiling == c, onClick = {
                    settings.glassesCeiling.set(c)
                    scope.launch { link.sendCommand(Frames.cmdSetParam(Frames.PARAM_CEILING, c)) }
                }, label = { Text("Ceiling $c %") })
            }
        }
        if (ceiling > 50) Text("Above 50 %: listen for distortion or rattling, and check the glasses don't restart (a brownout shows on the next connect). Go back to 50 % if they do.",
            color = StatusColors.bad, style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(500, 1000, 2000, 3000).forEach { hz ->
                OutlinedButton(enabled = !running, onClick = { scope.launch { link.sendCommand(Frames.cmdTone(hz, 1000)) } }) { Text("Tone $hz Hz") }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !running, onClick = { run("Uplink") { ThroughputTest(link).uplink(100_000).toString() } }) { Text("Uplink 100 KB") }
            OutlinedButton(enabled = !running, onClick = { run("Downlink") { ThroughputTest(link).downlink(100_000, 244).toString() } }) { Text("Downlink 100 KB") }
            OutlinedButton(enabled = !running, onClick = {
                run("Ping") {
                    val t0 = android.os.SystemClock.elapsedRealtime()
                    val pong = coroutineScope {
                        val wait = async { withTimeout(3000) { link.events.first { it is GlassesEvent.Pong } } }
                        link.sendCommand(Frames.cmdU32(Frames.CMD_PING, 42))
                        wait.await()
                    }
                    "Ping round trip ${android.os.SystemClock.elapsedRealtime() - t0} ms ($pong)"
                }
            }) { Text("Ping") }
            OutlinedButton(enabled = !running, onClick = { scope.launch { link.sendCommand(Frames.cmdU8(Frames.CMD_TOF_READ, 5)) } }) { Text("Read ToF") }
            OutlinedButton(enabled = !running, onClick = {
                run("Speech") {
                    val r = app.speech.say("This is a test of the glasses speaker. A blue pen with Pentel written on it, about thirty five centimetres.")
                    "Test sentence: spoken on ${r.where}" + (r.startedAfterMs?.let { ", audio started after $it ms" } ?: "") +
                        (r.error?.let { " · $it" } ?: "") + " · voice ${app.speech.voiceDescription}"
                }
            }) { Text("Speak test sentence") }
            OutlinedButton(enabled = !running, onClick = { scope.launch { link.sendCommand(Frames.cmdU8(Frames.CMD_CAPTURE, Frames.MODE_PHONE)) } }) { Text("Capture") }
        }
        results.take(6).forEach { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
    }
}


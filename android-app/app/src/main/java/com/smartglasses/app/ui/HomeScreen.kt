package com.smartglasses.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.smartglasses.app.ai.AnswerComposer
import com.smartglasses.app.ai.ComposeSettings
import com.smartglasses.app.ai.ModelState
import com.smartglasses.app.assistant.AssistantRun
import com.smartglasses.app.assistant.Mode
import com.smartglasses.app.assistant.RunTiming
import com.smartglasses.app.assistant.Source
import com.smartglasses.app.ble.LinkState
import com.smartglasses.app.ble.TofReading
import com.smartglasses.app.glassesApp
import com.smartglasses.app.settings.SpeakOn

private enum class Detail { GLASSES, MODEL, SPEECH }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(onOpenSettings: () -> Unit, vm: AssistantViewModel = viewModel()) {
    val app = LocalContext.current.glassesApp
    val link by app.link.state.collectAsStateWithLifecycle()
    val caps by app.link.caps.collectAsStateWithLifecycle()
    val model by app.describer.state.collectAsStateWithLifecycle()
    val speakOn by app.settings.speakOn.state.collectAsStateWithLifecycle()
    val run by app.session.run.collectAsStateWithLifecycle()
    var detail by remember { mutableStateOf<Detail?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(vm::describePhoto) }

    val connected = link is LinkState.Connected
    val glassesValue = when (val l = link) {
        is LinkState.Connected -> "Connected"
        LinkState.BluetoothOff -> "Bluetooth is off"
        LinkState.Scanning -> "Looking…"
        is LinkState.Connecting -> "Connecting…"
        is LinkState.Reconnecting -> "Retrying in ${l.inSeconds} s"
        LinkState.Idle -> "Starting…"
    }
    val modelValue = when (val m = model) {
        is ModelState.Ready -> "Ready (${m.engine})"
        ModelState.Loading -> "Loading…"
        is ModelState.NoModel -> "Not installed"
        is ModelState.Fallback -> "Basic mode"
    }
    val speechOnGlasses = speakOn != SpeakOn.PHONE && connected && caps?.hasSpeechStream == true
    val speechValue = when {
        speakOn == SpeakOn.PHONE -> "Phone speaker"
        speechOnGlasses && speakOn == SpeakOn.BOTH -> "Glasses + phone"
        speechOnGlasses -> "Glasses speaker"
        else -> "Phone (glasses offline)"
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Glasses", style = MaterialTheme.typography.headlineMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusChip(Icons.Outlined.Bluetooth, "Glasses", glassesValue,
                if (connected) Health.OK else if (link is LinkState.Reconnecting || link == LinkState.BluetoothOff) Health.BAD else Health.BUSY
            ) { detail = Detail.GLASSES }
            StatusChip(Icons.Outlined.AutoAwesome, "AI model", modelValue,
                when (model) { is ModelState.Ready -> Health.OK; ModelState.Loading -> Health.BUSY; else -> Health.BAD }
            ) { detail = Detail.MODEL }
            StatusChip(Icons.Outlined.VolumeUp, "Speech", speechValue,
                if (speechOnGlasses || speakOn == SpeakOn.PHONE) Health.OK else Health.BUSY
            ) { detail = Detail.SPEECH }
        }

        BigAction("Describe what's ahead", Icons.Outlined.CameraAlt, enabled = connected) { app.session.describeNow() }
        PhoneMicButton(enabled = model is ModelState.Ready)
        OutlinedButton(
            onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        ) {
            Icon(Icons.Outlined.PhotoLibrary, null); Spacer(Modifier.width(8.dp)); Text("Try with a photo from the gallery")
        }

        ResultCard(run, onRetry = app.session::retry, onReplay = app.session::replay, onStop = app.session::stopSpeech)

        if (!connected) Text(
            "Without the glasses you can still test everything with gallery photos and the phone's microphone.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    detail?.let { d ->
        AlertDialog(
            onDismissRequest = { detail = null },
            confirmButton = { TextButton(onClick = { detail = null }) { Text("Close") } },
            dismissButton = if (d != Detail.GLASSES) ({ TextButton(onClick = { detail = null; onOpenSettings() }) { Text("Settings") } }) else null,
            title = { Text(when (d) { Detail.GLASSES -> "Glasses"; Detail.MODEL -> "AI model"; Detail.SPEECH -> "Speech" }) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    when (d) {
                        Detail.GLASSES -> {
                            Text(glassesValue)
                            caps?.let { c ->
                                Text("Firmware ${c.firmware}, protocol v${c.protocol}")
                                Text("Camera ${yes(c.hasCamera)} · distance sensor ${yes(c.hasTof)} · speaker ${yes(c.hasSpeaker)} · mic ${yes(c.hasMic)}")
                                Text("Volume ${c.volume} % of a ${c.ceiling} % ceiling · hold ${c.holdMs} ms")
                                if (c.brownoutReset) Text("The glasses last restarted because of a power dip (brownout). Lower the volume ceiling or add the 100 µF capacitor.", color = StatusColors.bad)
                            }
                            if (link is LinkState.Reconnecting) Text("Make sure the glasses are on and nothing else (e.g. nRF Connect) is connected to them.")
                        }
                        Detail.MODEL -> {
                            Text(when (val m = model) {
                                is ModelState.Ready -> "Gemma 4 E2B is loaded on the ${m.engine} (took %.1f s). Spoken questions: ${if (app.describer.gemmaHearsAudio) "Gemma hears them directly" else "Android's recogniser"}.".format(m.loadMs / 1000.0)
                                ModelState.Loading -> "Loading Gemma into memory. This takes about 30 seconds after each app start."
                                is ModelState.NoModel -> m.reason + " Import it in Settings → AI model."
                                is ModelState.Fallback -> "Gemma couldn't start on this phone (${m.reason}). Photos are described with the basic ML Kit labeller; questions need Gemma."
                            })
                        }
                        Detail.SPEECH -> {
                            Text("Speak on: ${speakOn.name.lowercase()} (Settings). Now: $speechValue.")
                            Text("Voice: ${app.speech.voiceDescription}")
                        }
                    }
                }
            },
        )
    }
}

private fun yes(b: Boolean) = if (b) "OK" else "missing"

@Composable
private fun ResultCard(run: AssistantRun, onRetry: () -> Unit, onReplay: () -> Unit, onStop: () -> Unit) {
    val app = LocalContext.current.glassesApp
    Section("Latest result", Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        when (run) {
            AssistantRun.Idle -> Text("Tap the glasses button to hear what's ahead. Hold it to ask a question.",
                style = MaterialTheme.typography.bodyLarge)
            is AssistantRun.Listening -> Column {
                Text("Listening… release to ask", style = MaterialTheme.typography.titleMedium, color = StatusColors.busy)
                LinearProgressIndicator(progress = { ((run.level + 60f) / 60f).coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
            }
            is AssistantRun.Receiving -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Receiving photo… %.1f / %.1f KB".format(run.receivedBytes / 1024.0, run.totalBytes / 1024.0))
                LinearProgressIndicator(progress = { if (run.totalBytes > 0) run.receivedBytes.toFloat() / run.totalBytes else 0f }, Modifier.fillMaxWidth())
            }
            is AssistantRun.Describing -> PhotoRow(run.preview) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(if (run.question) "Thinking about your question…" else "Looking…", style = MaterialTheme.typography.titleMedium)
                }
            }
            is AssistantRun.Done -> PhotoRow(run.preview) {
                run.question?.takeIf { it.isNotBlank() }?.let { Text("You asked: “$it”", style = MaterialTheme.typography.bodyLarge) }
                Text(run.sentence, style = MaterialTheme.typography.titleLarge)
                DistanceBadge(run.source, run.tof)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onReplay, modifier = Modifier.heightIn(min = 48.dp)) { Icon(Icons.Outlined.Replay, null); Spacer(Modifier.width(6.dp)); Text("Replay") }
                    OutlinedButton(onClick = onStop, modifier = Modifier.heightIn(min = 48.dp)) { Icon(Icons.Outlined.Stop, null); Spacer(Modifier.width(6.dp)); Text("Stop") }
                }
                Text(timingLine(run.timing) + " · spoken on ${run.spokenOn}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                run.speechError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = StatusColors.bad) }
            }
            is AssistantRun.Error -> PhotoRow(run.preview) {
                Text(run.message, style = MaterialTheme.typography.titleMedium, color = StatusColors.bad)
                if (run.canRetry) OutlinedButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text("Try again") }
            }
        }
    }
}

@Composable
private fun DistanceBadge(source: Source, tof: TofReading?) {
    val app = LocalContext.current.glassesApp
    val text = when {
        source == Source.GALLERY -> "No distance (gallery photo)"
        tof == null || !tof.isValid -> "Distance: no reliable reading"
        else -> {
            val s = ComposeSettings(units = app.settings.units.value, tofOffsetMm = app.settings.tofOffsetMm.value, tofScale = app.settings.tofScale.value)
            AnswerComposer.calibrated(tof, s)?.let { "Distance ${AnswerComposer.formatDistance(it, s.units)} · ${tof.validCount}/${tof.sampleCount} samples, spread ${tof.spreadMm} mm" }
                ?: "Distance: beyond the sensor's reliable range"
        }
    }
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun PhotoRow(preview: android.graphics.Bitmap?, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.Top) {
        if (preview != null) {
            Image(preview.asImageBitmap(), "Photo the answer is about", Modifier.size(96.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop)
            Spacer(Modifier.width(12.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    }
}

fun timingLine(t: RunTiming) = buildList {
    t.transferMs?.let { add("photo %.1f s".format(it / 1000.0)) }
    add("model %.1f s".format(t.modelMs / 1000.0))
    t.speechStartMs?.let { add("speech %.1f s".format(it / 1000.0)) }
}.joinToString(" · ") + " = %.1f s".format(t.totalMs / 1000.0)

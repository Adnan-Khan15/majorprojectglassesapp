package com.smartglasses.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import com.smartglasses.app.ai.TofCalibration
import com.smartglasses.app.audio.PcmPlayer
import com.smartglasses.app.ble.Frames
import com.smartglasses.app.ble.GlassesEvent
import com.smartglasses.app.ble.GlassesLink
import com.smartglasses.app.ble.ThroughputTest
import com.smartglasses.app.ble.TofReading
import com.smartglasses.app.glassesApp
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** One live ToF reading (image id 0xFFFF) with [samples] samples. */
private suspend fun readTof(link: GlassesLink, samples: Int): TofReading? = coroutineScope {
    val wait = async {
        withTimeoutOrNull(samples * 300L + 2000) {
            link.events.filterIsInstance<GlassesEvent.Tof>().first { it.imageId == Frames.TOF_LIVE_IMAGE_ID }.reading
        }
    }
    if (!link.sendCommand(Frames.cmdU8(Frames.CMD_TOF_READ, samples))) { wait.cancel(); return@coroutineScope null }
    wait.await()
}

@Composable
fun DiagnosticsScreen() {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Diagnostics", style = MaterialTheme.typography.headlineMedium)
        Text("Tester tools. Each matches a test in docs/HARDWARE_TESTS.md.", style = MaterialTheme.typography.bodyMedium)
        GlassesTools()
        TofTools()
        MicTools()
        LinkTools()
        LatencyTools()
        Section("Speech-recognition spike") { SttSpikeSection() }
        BleLog()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GlassesTools() {
    val app = LocalContext.current.glassesApp
    val caps by app.link.caps.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf("") }
    Section("Glasses and speaker") {
        Text(caps?.let { "Firmware ${it.firmware}, protocol v${it.protocol}, reset reason ${it.resetReason}" + if (it.brownoutReset) " (BROWNOUT)" else "" }
            ?: "No v2 glasses connected", style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(500, 1000, 2000, 3000).forEach { hz ->
                OutlinedButton(onClick = { scope.launch { app.link.sendCommand(Frames.cmdTone(hz, 1000)) } }) { Text("Tone $hz Hz") }
            }
            OutlinedButton(onClick = {
                scope.launch {
                    val r = app.speech.say("This is a test of the glasses speaker. A blue pen with Pentel written on it, about thirty five centimetres.")
                    result = "Spoken on ${r.where}" + (r.startedAfterMs?.let { ", audio after $it ms" } ?: "") + (r.error?.let { " · $it" } ?: "")
                }
            }) { Text("Speak test sentence") }
            OutlinedButton(onClick = {
                scope.launch {
                    val t0 = android.os.SystemClock.elapsedRealtime()
                    val ok = coroutineScope {
                        val w = async { withTimeoutOrNull(3000) { app.link.events.first { it is GlassesEvent.Pong } } }
                        app.link.sendCommand(Frames.cmdU32(Frames.CMD_PING, 42)); w.await()
                    }
                    result = if (ok != null) "Ping round trip ${android.os.SystemClock.elapsedRealtime() - t0} ms" else "Ping: no answer"
                }
            }) { Text("Ping") }
        }
        if (result.isNotEmpty()) Text(result, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun TofTools() {
    val app = LocalContext.current.glassesApp
    val scope = rememberCoroutineScope()
    var live by remember { mutableStateOf(false) }
    var last by remember { mutableStateOf<TofReading?>(null) }
    val points = remember { mutableStateListOf<TofCalibration.Point>() }
    var calMsg by remember { mutableStateOf("") }
    val targets = listOf(200, 500, 1000)

    LaunchedEffect(live) {
        while (live && isActive) {
            last = readTof(app.link, 3)
            delay(300)
        }
    }
    Section("Distance sensor (ToF)") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Live reading", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Switch(checked = live, onCheckedChange = { live = it })
        }
        Text(last?.let {
            if (it.isValid) "${it.medianMm} mm · ${it.validCount}/${it.sampleCount} valid · spread ${it.spreadMm} mm"
            else "No valid reading (${tofFlagsText(it.flags)})"
        } ?: "-", style = MaterialTheme.typography.titleMedium)

        Text("Calibration walk-through", style = MaterialTheme.typography.titleSmall)
        Text("Fix the glasses facing a white card. For each distance, measure from the front of the sensor with a tape measure, then tap Measure.",
            style = MaterialTheme.typography.bodySmall)
        targets.forEach { mm ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val p = points.firstOrNull { it.trueMm == mm }
                Text("${mm / 10} cm: " + (p?.let { "measured ${it.measuredMm} mm" } ?: "-"), Modifier.weight(1f))
                OutlinedButton(onClick = {
                    scope.launch {
                        live = false
                        val r = readTof(app.link, 7)
                        if (r == null || !r.isValid) calMsg = "No valid reading at ${mm / 10} cm; check the card fills the view"
                        else { points.removeAll { it.trueMm == mm }; points.add(TofCalibration.Point(mm, r.medianMm)); calMsg = "" }
                    }
                }) { Text("Measure") }
            }
        }
        val fit = TofCalibration.fit(points.toList())
        fit?.let { Text("Fit: true = %.3f × measured %+d mm (worst error after fit %d mm)".format(it.scale, it.offsetMm, it.maxResidualMm)) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = fit != null, onClick = {
                fit?.let { app.settings.tofScale.set(it.scale); app.settings.tofOffsetMm.set(it.offsetMm); calMsg = "Saved." }
            }) { Text("Save calibration") }
            OutlinedButton(onClick = {
                points.clear(); app.settings.tofScale.set(1f); app.settings.tofOffsetMm.set(0); calMsg = "Calibration reset."
            }) { Text("Reset") }
        }
        if (calMsg.isNotEmpty()) Text(calMsg, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun MicTools() {
    val app = LocalContext.current.glassesApp
    val scope = rememberCoroutineScope()
    var meter by remember { mutableStateOf(false) }
    var level by remember { mutableStateOf<GlassesEvent.MicLevel?>(null) }
    var msg by remember { mutableStateOf("") }
    val testPcm by app.glassesMic.micTest.collectAsStateWithLifecycle()

    LaunchedEffect(meter) {
        if (meter) {
            app.link.sendCommand(Frames.cmdU8(Frames.CMD_MIC_MONITOR, 1))
            app.link.events.filterIsInstance<GlassesEvent.MicLevel>().collect { level = it }
        }
    }
    DisposableEffect(Unit) { onDispose { if (meter) scope.launch { app.link.sendCommand(Frames.cmdU8(Frames.CMD_MIC_MONITOR, 0)) } } }

    Section("Glasses microphone") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Level meter", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Switch(checked = meter, onCheckedChange = {
                meter = it
                if (!it) scope.launch { app.link.sendCommand(Frames.cmdU8(Frames.CMD_MIC_MONITOR, 0)) }
            })
        }
        level?.let { l ->
            Text("rms %.1f dBFS · peak %.1f dBFS".format(l.rmsDbfs, l.peakDbfs))
            LinearProgressIndicator(progress = { ((l.rmsDbfs + 60f) / 60f).coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
            if (l.peakDbfs > -1f) Text("Clipping: lower the mic gain (serial: set micgain)", color = StatusColors.bad)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                scope.launch {
                    meter = false
                    msg = "Recording 3 s on the glasses… speak now"
                    app.link.sendCommand(Frames.cmdU8(Frames.CMD_MIC_TEST, 3))
                    delay(4500)
                    msg = app.glassesMic.micTest.value?.let { "Got ${it.size / 16} ms. Tap Play." } ?: "No audio arrived"
                }
            }) { Text("Mic test: record 3 s") }
            OutlinedButton(enabled = testPcm != null, onClick = { scope.launch { testPcm?.let { PcmPlayer.play(it) } } }) { Text("Play on phone") }
        }
        if (msg.isNotEmpty()) Text(msg, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun LinkTools() {
    val app = LocalContext.current.glassesApp
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
    Section("Bluetooth throughput") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = use2m, onCheckedChange = { use2m = it; scope.launch { app.link.request2mPhy(it) } })
            Text("Request 2M PHY")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !running, onClick = { run("Uplink") { ThroughputTest(app.link).uplink(100_000).toString() } }) { Text("Uplink 100 KB") }
            OutlinedButton(enabled = !running, onClick = { run("Downlink") { ThroughputTest(app.link).downlink(100_000, 244).toString() } }) { Text("Downlink 100 KB") }
        }
        results.take(8).forEach { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun LatencyTools() {
    val app = LocalContext.current.glassesApp
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf(app.latencyLog.rowCount()) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let {
            scope.launch {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    context.contentResolver.openOutputStream(it)?.use { out -> app.latencyLog.file.inputStream().use { inp -> inp.copyTo(out) } }
                }
            }
        }
    }
    Section("Latency log") {
        Text("$rows interactions logged. Analyse with scripts/latency_report.py (p50/p90 per stage, needs ≥ 10 runs).")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = rows > 0, onClick = { export.launch("latency.csv") }) { Text("Export CSV") }
            OutlinedButton(enabled = rows > 0, onClick = { app.latencyLog.clear(); rows = 0 }) { Text("Clear") }
            OutlinedButton(onClick = { rows = app.latencyLog.rowCount() }) { Text("Refresh") }
        }
    }
}

@Composable
private fun BleLog() {
    val app = LocalContext.current.glassesApp
    val log by app.link.log.collectAsStateWithLifecycle()
    Section("Bluetooth log") {
        log.take(60).forEach { line ->
            Text("${line.time}  ${line.text}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
    }
}

package com.smartglasses.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartglasses.app.ai.ModelDownloader
import com.smartglasses.app.ble.Frames
import com.smartglasses.app.glassesApp
import com.smartglasses.app.settings.AnswerLength
import com.smartglasses.app.settings.DistanceRegion
import com.smartglasses.app.settings.Pref
import com.smartglasses.app.settings.SpeakOn
import com.smartglasses.app.settings.SttEngine
import com.smartglasses.app.settings.Units
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <E : Enum<E>> Choice(title: String, pref: Pref<E>, options: List<Pair<E, String>>, onChange: (E) -> Unit = {}) {
    val v by pref.state.collectAsStateWithLifecycle()
    Column {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (e, label) ->
                FilterChip(selected = v == e, onClick = { pref.set(e); onChange(e) }, label = { Text(label) },
                    modifier = Modifier.heightIn(min = 48.dp))
            }
        }
    }
}

@Composable
private fun Toggle(title: String, explain: String, pref: Pref<Boolean>, onChange: (Boolean) -> Unit = {}) {
    val v by pref.state.collectAsStateWithLifecycle()
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(explain, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = v, onCheckedChange = { pref.set(it); onChange(it) }, modifier = Modifier.semantics { contentDescription = title })
    }
}

@Composable
private fun IntSlider(title: String, pref: Pref<Int>, range: IntRange, step: Int, unit: String, onDone: (Int) -> Unit = {}) {
    val v by pref.state.collectAsStateWithLifecycle()
    Column {
        Text("$title: $v$unit", style = MaterialTheme.typography.bodyLarge)
        Slider(
            value = v.toFloat(), valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first) / step - 1,
            onValueChange = { pref.set((it / step).toInt() * step) },
            onValueChangeFinished = { onDone(pref.value) },
            modifier = Modifier.semantics { contentDescription = "$title $v$unit" },
        )
    }
}

@Composable
fun SettingsScreen() {
    val app = LocalContext.current.glassesApp
    val s = app.settings
    val link = app.link
    val scope = rememberCoroutineScope()
    val rate by s.speechRate.state.collectAsStateWithLifecycle()
    val ceiling by s.glassesCeiling.state.collectAsStateWithLifecycle()
    fun send(bytes: ByteArray) = scope.launch { link.sendCommand(bytes) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)

        Section("Speech") {
            Choice("Speak on", s.speakOn, listOf(SpeakOn.GLASSES to "Glasses", SpeakOn.PHONE to "Phone", SpeakOn.BOTH to "Both"))
            IntSlider("Glasses volume", s.glassesVolume, 0..100, 10, " %") { send(Frames.cmdU8(Frames.CMD_SET_VOLUME, it)) }
            Text("Loudness ceiling (protects the 1 W speaker)", style = MaterialTheme.typography.bodyLarge)
            FlowRowCeiling(ceiling) { c -> s.glassesCeiling.set(c); send(Frames.cmdSetParam(Frames.PARAM_CEILING, c)) }
            if (ceiling > 50) Text("Above 50 %: listen for distortion, and check the glasses don't restart (brownout).", color = StatusColors.bad,
                style = MaterialTheme.typography.bodySmall)
            Column {
                Text("Speech rate: %.1fx".format(rate), style = MaterialTheme.typography.bodyLarge)
                Slider(value = rate, valueRange = 0.6f..1.6f, steps = 9, onValueChange = { s.speechRate.set((it * 10).toInt() / 10f) },
                    modifier = Modifier.semantics { contentDescription = "Speech rate %.1f".format(rate) })
            }
            Toggle("Boost speech loudness", "Makes speech on the glasses' small speaker much louder and clearer (recommended)", s.speechBoost)
            Toggle("Announce status", "Say \"glasses connected\", \"AI ready\" and similar out loud", s.announceStates)
        }

        Section("Answers") {
            Choice("Answer length", s.answerLength, listOf(AnswerLength.BRIEF to "Brief", AnswerLength.DETAILED to "Detailed"))
            Choice("Units", s.units, listOf(Units.METRIC to "Centimetres / metres", Units.IMPERIAL to "Inches / feet"))
            Choice("Speech recogniser", s.sttEngine, listOf(SttEngine.GEMMA_AUDIO to "Gemma (hears audio)", SttEngine.ANDROID_ON_DEVICE to "Android on-device"))
        }

        Section("Button and distance") {
            IntSlider("Hold time for a question", s.holdMs, 300..1500, 100, " ms") { send(Frames.cmdSetParam(Frames.PARAM_HOLD_MS, it)) }
            Choice("Distance is given for things", s.distanceRegion,
                listOf(DistanceRegion.CENTER_ONLY to "In the centre", DistanceRegion.CENTER_COLUMN to "In the middle column"))
            val off by s.tofOffsetMm.state.collectAsStateWithLifecycle()
            val sc by s.tofScale.state.collectAsStateWithLifecycle()
            Text("Distance calibration: scale %.3f, offset %d mm (set it in Diagnostics → ToF calibration)".format(sc, off),
                style = MaterialTheme.typography.bodySmall)
        }

        Section("Glasses link") {
            Toggle("Fast photo transfer", "Send photos as notifications (~12x faster in testing). Turn off to compare.", s.fastPhotos) {
                send(Frames.cmdSetParam(Frames.PARAM_IMAGE_MODE, if (it) 1 else 0))
            }
        }

        Section("AI model") {
            ModelSetupCard(wifiOnly = s.downloadWifiOnly.value)
            if (ModelDownloader.AVAILABLE) Toggle("Download on Wi-Fi only", "The model is 2.59 GB", s.downloadWifiOnly)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowCeiling(current: Int, onPick: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(30, 50, 70, 100).forEach { c ->
            FilterChip(selected = current == c, onClick = { onPick(c) }, label = { Text("$c %") }, modifier = Modifier.heightIn(min = 48.dp))
        }
    }
}

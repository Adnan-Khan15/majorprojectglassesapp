package com.smartglasses.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.smartglasses.app.glassesApp
import com.smartglasses.app.voice.PhoneMic
import com.smartglasses.app.voice.SttSpike
import kotlinx.coroutines.launch

/** STT spike tools (docs/STT_SPIKE.md): record clips, run both recognisers over them. */
@Composable
fun SttSpikeSection() {
    val context = LocalContext.current
    val app = context.glassesApp
    val spike = remember { SttSpike(context, app.describer, app.androidStt) }
    val scope = rememberCoroutineScope()
    val mic = remember { PhoneMic() }
    var clips by remember { mutableIntStateOf(spike.clips().size) }
    var status by remember { mutableStateOf("") }
    var recording by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Speech-recognition spike: $clips clips in ${spike.dir.path}", style = MaterialTheme.typography.bodyMedium)
        Box(
            Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .background(if (recording) StatusColors.busy.copy(alpha = 0.3f) else MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(12.dp))
                .semantics { role = Role.Button; contentDescription = "Hold to record a spike clip" }
                .pointerInput(granted) {
                    detectTapGestures(onPress = {
                        if (!granted) { ask.launch(Manifest.permission.RECORD_AUDIO); return@detectTapGestures }
                        recording = true
                        scope.launch {
                            val pcm = runCatching { mic.record(10_000) }.getOrNull()
                            recording = false
                            if (pcm != null && pcm.size > 3200) {
                                val f = spike.save(pcm)
                                clips = spike.clips().size
                                status = "Saved ${f.name} (${pcm.size / 16} ms)"
                            }
                        }
                        tryAwaitRelease()
                        mic.stop()
                    })
                }
                .padding(12.dp),
            contentAlignment = Alignment.Center,
        ) { Text(if (recording) "Recording… release to save" else "Hold to record clip") }
        OutlinedButton(enabled = !running && clips > 0, onClick = {
            running = true
            scope.launch {
                status = try {
                    val f = spike.run { d, t, line -> status = "$d/$t  $line" }
                    "Done: ${f.path}. Score with scripts/wer.py (docs/STT_SPIKE.md)."
                } catch (e: Exception) { "Spike failed: ${e.message}" }
                running = false
            }
        }) { Text("Run spike on all clips") }
        if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
    }
}

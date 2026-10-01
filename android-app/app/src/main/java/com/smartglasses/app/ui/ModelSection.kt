package com.smartglasses.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.smartglasses.app.ai.ModelState

@Composable
fun ModelSection(vm: AssistantViewModel = viewModel()) {
    val model by vm.modelState.collectAsStateWithLifecycle()
    val test by vm.test.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(vm::describePhoto)
    }
    val ready = model is ModelState.Ready || model is ModelState.Fallback
    val busy = test is TestRun.Running

    Column(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text("On-device AI", style = MaterialTheme.typography.titleMedium)
        }
        when (val m = model) {
            is ModelState.Unpacking -> {
                Text("First launch: unpacking the AI model… ${(m.progress * 100).toInt()}%")
                LinearProgressIndicator(progress = { m.progress }, Modifier.fillMaxWidth())
                Text("This happens once and takes about a minute.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ModelState.Loading -> {
                Text("Loading Gemma 4 into memory…")
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            is ModelState.Ready -> Text(
                "Gemma 4 E2B ready on ${m.engine} (loaded in %.1f s)".format(m.loadMs / 1000.0),
                color = StatusColors.ok,
            )
            is ModelState.Fallback -> {
                Text("Gemma couldn't start on this phone, so the basic ML Kit labeller is being used instead.",
                    color = StatusColors.busy)
                Text(m.reason, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = vm::retryModel) { Text("Try loading Gemma again") }
            }
            is ModelState.Failed -> {
                Text(m.reason, color = StatusColors.bad)
                Button(onClick = vm::retryModel) { Text("Retry") }
            }
        }

        Button(
            onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            enabled = ready && !busy,
        ) {
            Icon(Icons.Outlined.PhotoLibrary, null)
            Spacer(Modifier.width(8.dp))
            Text(if (ready) "Try with a photo" else "Waiting for model…")
        }

        when (val t = test) {
            TestRun.Idle -> Unit
            is TestRun.Running -> ResultRow(t.preview) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Looking at the photo…")
                }
            }
            is TestRun.Done -> ResultRow(t.preview) {
                Text("“${t.result.sentence}”", style = MaterialTheme.typography.titleMedium)
                Text("Total %.1f s · OCR %.1f s · model %.1f s".format(
                    t.result.totalMs / 1000.0, t.result.ocrMs / 1000.0, t.result.modelMs / 1000.0),
                    style = MaterialTheme.typography.bodySmall)
                Text(t.result.engine, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (t.result.ocrText.isNotBlank()) Text("Text read: ${t.result.ocrText.take(80)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            is TestRun.Error -> ResultRow(t.preview) {
                Text(t.message, color = StatusColors.bad)
                OutlinedButton(onClick = vm::retry) { Text("Retry") }
            }
        }
    }
}

@Composable
private fun ResultRow(preview: android.graphics.Bitmap?, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.Top) {
        if (preview != null) {
            Image(preview.asImageBitmap(), null, Modifier.size(84.dp).clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop)
            Spacer(Modifier.width(12.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
    }
}

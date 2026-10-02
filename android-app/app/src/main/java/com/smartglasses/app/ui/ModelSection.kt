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
import com.smartglasses.app.ai.SceneDescriber
import com.smartglasses.app.assistant.AssistantRun
import com.smartglasses.app.assistant.RunTiming
import com.smartglasses.app.assistant.Source

@Composable
fun ModelSection(vm: AssistantViewModel = viewModel()) {
    val model by vm.modelState.collectAsStateWithLifecycle()
    val run by vm.run.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(vm::describePhoto)
    }
    val ready = model is ModelState.Ready || model is ModelState.Fallback
    val busy = run is AssistantRun.Describing || run is AssistantRun.Receiving

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

        RunView(run, onRetry = vm::retry)
    }
}

@Composable
private fun RunView(run: AssistantRun, onRetry: () -> Unit) {
    when (run) {
        AssistantRun.Idle -> Text(
            "Press the button on the glasses, or try a photo from your gallery.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        is AssistantRun.Receiving -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Receiving photo from glasses… %.1f / %.1f KB".format(run.receivedBytes / 1024.0, run.totalBytes / 1024.0))
            LinearProgressIndicator(
                progress = { if (run.totalBytes > 0) run.receivedBytes.toFloat() / run.totalBytes else 0f },
                Modifier.fillMaxWidth(),
            )
        }
        is AssistantRun.Describing -> ResultRow(run.preview) {
            SourceLabel(run.source, run.distanceMm)
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Looking at the photo…")
            }
        }
        is AssistantRun.Done -> ResultRow(run.preview) {
            SourceLabel(run.source, run.distanceMm)
            Text("\u201c${run.result.sentence}\u201d", style = MaterialTheme.typography.titleMedium)
            Text(timingLine(run.timing), style = MaterialTheme.typography.bodySmall)
            Text(
                "${run.result.engine} · spoken on ${run.announcer}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            run.speechError?.let { Text("Couldn't speak it: $it", color = StatusColors.bad, style = MaterialTheme.typography.bodySmall) }
            when (run.sentToGlasses) {
                true -> Text("Sent to glasses ✓", color = StatusColors.ok, style = MaterialTheme.typography.bodySmall)
                false -> Text("Couldn't send the sentence to the glasses", color = StatusColors.bad, style = MaterialTheme.typography.bodySmall)
                null -> Unit
            }
            if (run.result.ocrText.isNotBlank()) Text("Text read: ${run.result.ocrText.take(80)}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        is AssistantRun.Error -> ResultRow(run.preview) {
            SourceLabel(run.source, null)
            Text(run.message, color = StatusColors.bad)
            if (run.canRetry) OutlinedButton(onClick = onRetry) { Text("Retry") }
        }
    }
}

@Composable
private fun SourceLabel(source: Source, distanceMm: Int?) {
    val text = when (source) {
        Source.GLASSES -> "From glasses · " + (distanceMm?.let { SceneDescriber.formatDistance(it) } ?: "no distance reading")
        Source.GALLERY -> "From gallery (no distance sensor)"
    }
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
}

private fun timingLine(t: RunTiming) = buildList {
    t.transferMs?.let { add("Transfer %.1f s".format(it / 1000.0)) }
    add("OCR %.1f s".format(t.ocrMs / 1000.0))
    add("model %.1f s".format(t.modelMs / 1000.0))
    t.speechStartMs?.let { add("speech starts %.1f s".format(it / 1000.0)) }
}.joinToString(" · ") + "  =  %.1f s total".format(t.totalMs / 1000.0)

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

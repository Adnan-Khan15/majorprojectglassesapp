package com.smartglasses.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartglasses.app.ai.ModelDownloader
import com.smartglasses.app.ai.ModelStatus
import com.smartglasses.app.ai.ModelStore
import com.smartglasses.app.glassesApp

/**
 * Where the AI model comes from: import from a file (primary), developer drop-in
 * (adb push), or download (withDownload flavor). Shows verification status.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModelSetupCard(wifiOnly: Boolean = true) {
    val app = LocalContext.current.glassesApp
    val actions = app.modelActions
    val store = app.modelStore
    val status by store.status.collectAsStateWithLifecycle()
    val download by actions.download.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(actions::importFrom)
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("${store.spec.name} · ${ModelStore.gb(store.spec.sizeBytes)} · ${store.spec.license ?: ""}",
            style = MaterialTheme.typography.bodySmall)
        when (val s = status) {
            is ModelStatus.Missing -> {
                Text("No AI model on this phone yet.", style = MaterialTheme.typography.titleSmall)
                s.message?.let { Text(it, color = StatusColors.bad) }
                Text(
                    "Download ${store.spec.fileName} to this phone (for example from the Google Drive link " +
                        "you were given), then tap Import and pick it. It needs about " +
                        "${ModelStore.gb(store.requiredFreeBytes)} free.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            is ModelStatus.Importing -> {
                Text("Copying \"${s.source}\"… ${(s.progress * 100).toInt()}%")
                LinearProgressIndicator(progress = { s.progress }, Modifier.fillMaxWidth()
                    .semantics { contentDescription = "Import progress ${(s.progress * 100).toInt()} percent" })
            }
            is ModelStatus.Verifying -> {
                Text("Checking the model file… ${(s.progress * 100).toInt()}%")
                LinearProgressIndicator(progress = { s.progress }, Modifier.fillMaxWidth())
            }
            is ModelStatus.Ready -> {
                Text("Model installed and verified ✓", color = StatusColors.ok, style = MaterialTheme.typography.titleSmall)
                Text(
                    "SHA-256 ${s.sha256.take(12)}… · ${ModelStore.gb(s.sizeBytes)}" +
                        if (s.inPlace) " · developer drop-in (used in place)" else "",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            is ModelStatus.Corrupt -> {
                Text("The model file is damaged", color = StatusColors.bad, style = MaterialTheme.typography.titleSmall)
                Text(s.reason, style = MaterialTheme.typography.bodySmall)
            }
        }
        download?.let { d ->
            val p = if (d.total > 0) d.downloaded.toFloat() / d.total else 0f
            Text("Download: ${ModelStore.gb(d.downloaded)} of ${ModelStore.gb(d.total)}" + (d.error?.let { " · $it" } ?: ""))
            LinearProgressIndicator(progress = { p }, Modifier.fillMaxWidth())
        }

        val working = status is ModelStatus.Importing || status is ModelStatus.Verifying || download?.running == true
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (working) {
                OutlinedButton(onClick = actions::cancel) { Text("Cancel") }
            } else {
                Button(onClick = { picker.launch(arrayOf("*/*")) }) {
                    Text(if (status is ModelStatus.Ready) "Re-import" else "Import from file")
                }
                if (ModelDownloader.AVAILABLE && status !is ModelStatus.Ready) {
                    OutlinedButton(onClick = { actions.startDownload(wifiOnly) }) {
                        Text(if ((download?.downloaded ?: 0) > 0) "Resume download" else "Download")
                    }
                }
                OutlinedButton(onClick = actions::rescan) { Text("Rescan") }
                if (status is ModelStatus.Ready || status is ModelStatus.Corrupt) {
                    OutlinedButton(onClick = { confirmDelete = true }) { Text("Delete") }
                }
            }
        }
        Text("Storage used by the model: ${ModelStore.gb(store.storageUsedBytes())}",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete the AI model?") },
            text = { Text("This frees ${ModelStore.gb(store.storageUsedBytes())}. You'll need to import it again to use Gemma.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; actions.delete() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } },
        )
    }
}

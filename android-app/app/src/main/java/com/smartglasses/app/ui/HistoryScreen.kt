package com.smartglasses.app.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartglasses.app.glassesApp
import com.smartglasses.app.history.HistoryItem
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen() {
    val app = LocalContext.current.glassesApp
    val context = LocalContext.current
    val items by app.history.items.collectAsStateWithLifecycle()
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val exportJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { scope.launch { context.contentResolver.openOutputStream(it)?.let { o -> app.history.exportJson(o) } } }
    }
    val exportCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let { scope.launch { context.contentResolver.openOutputStream(it)?.let { o -> app.history.exportCsv(o) } } }
    }

    val open = items.firstOrNull { it.id == openId }
    if (open != null) {
        BackHandler { openId = null }
        HistoryDetail(open, onBack = { openId = null }, onDelete = { app.history.delete(open.id); openId = null })
        return
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Spacer(Modifier.size(12.dp))
            Text("History", style = MaterialTheme.typography.headlineMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                OutlinedButton(onClick = { exportJson.launch("glasses-history-$stamp.json") }, enabled = items.isNotEmpty()) { Text("Export JSON") }
                OutlinedButton(onClick = { exportCsv.launch("glasses-history-$stamp.csv") }, enabled = items.isNotEmpty()) { Text("Export CSV") }
                TextButton(onClick = { confirmClear = true }, enabled = items.isNotEmpty()) { Text("Clear") }
            }
            if (items.isEmpty()) Text("Nothing yet. Every description and answer appears here.", style = MaterialTheme.typography.bodyLarge)
        }
        items(items, key = { it.id }) { h -> HistoryRow(h) { openId = h.id } }
        item { Spacer(Modifier.size(24.dp)) }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Delete all history?") },
        text = { Text("Deletes ${items.size} items, including their photos. The latency log is kept (Diagnostics).") },
        confirmButton = { TextButton(onClick = { app.history.clear(); confirmClear = false }) { Text("Delete all") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Keep") } },
    )
}

private val TIME = SimpleDateFormat("d MMM, HH:mm:ss", Locale.getDefault())

@Composable
private fun Thumb(h: HistoryItem, size: Int) {
    val bmp = remember(h.thumbnail) { h.thumbnail?.let { BitmapFactory.decodeFile(it.path) } }
    if (bmp != null) Image(bmp.asImageBitmap(), "Photo", Modifier.size(size.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
}

@Composable
private fun HistoryRow(h: HistoryItem, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "${TIME.format(Date(h.startedAtMs))}. ${h.heard?.let { "You asked $it. " } ?: ""}${h.answer ?: h.error ?: h.outcome}" }
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumb(h, 64)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(TIME.format(Date(h.startedAtMs)) + " · " + h.mode.replace('_', ' '), style = MaterialTheme.typography.labelMedium)
            h.heard?.takeIf { it.isNotBlank() }?.let { Text("“$it”", style = MaterialTheme.typography.bodyMedium, maxLines = 1) }
            Text(h.answer ?: (h.error ?: h.outcome), style = MaterialTheme.typography.bodyLarge, maxLines = 2,
                color = if (h.outcome == "ok") MaterialTheme.colorScheme.onSurface else StatusColors.bad)
        }
    }
    HorizontalDivider()
}

@Composable
private fun HistoryDetail(h: HistoryItem, onBack: () -> Unit, onDelete: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBack) { Text("← Back") }
        Text(TIME.format(Date(h.startedAtMs)), style = MaterialTheme.typography.headlineSmall)
        Thumb(h, 240)
        Section("Conversation") {
            h.heard?.let { Text("Heard (${h.stt ?: "-"}, via ${h.questionVia ?: "-"}): “$it”") }
            Text("Answer: ${h.answer ?: "-"}", style = MaterialTheme.typography.titleMedium)
            Text("Outcome: ${h.outcome}" + (h.error?.let { " · $it" } ?: ""))
            Text("Engine: ${h.engine ?: "-"}")
            h.ocrText?.takeIf { it.isNotBlank() }?.let { Text("Text read (OCR): $it") }
        }
        Section("Distance reading") {
            if (h.distanceMm == null) Text("No distance sensor behind this photo.")
            else {
                Text("Median ${h.distanceMm} mm · ${h.tofValid}/${h.tofSamples} valid samples · spread ${h.tofSpreadMm} mm")
                Text("Flags 0x%02x (%s)".format(h.tofFlags ?: 0, tofFlagsText(h.tofFlags ?: 0)))
            }
        }
        Section("Latency (ms from the first event)") {
            if (h.stages.isEmpty()) Text("-")
            h.stages.entries.sortedBy { it.value }.forEach { (k, v) ->
                Text("%-20s %6d".format(k.removeSuffix("_ms"), v), fontFamily = FontFamily.Monospace)
            }
        }
        h.modelRaw?.takeIf { it.isNotBlank() }?.let {
            Section("Raw model reply") { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
        }
        OutlinedButton(onClick = onDelete) { Text("Delete this item") }
    }
}

fun tofFlagsText(f: Int) = buildList {
    if (f and 1 != 0) add("valid")
    if (f and 2 != 0) add("out of range")
    if (f and 4 != 0) add("timeout")
    if (f and 8 != 0) add("no sensor")
}.ifEmpty { listOf("none") }.joinToString(", ")

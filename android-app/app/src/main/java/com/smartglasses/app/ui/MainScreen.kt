package com.smartglasses.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartglasses.app.ble.LinkState
import com.smartglasses.app.ble.ReceivedMessage
import com.smartglasses.app.glassesApp

@Composable
fun MainScreen() {
    val link = LocalContext.current.glassesApp.link
    val state by link.state.collectAsStateWithLifecycle()
    val log by link.log.collectAsStateWithLifecycle()
    val last by link.lastReceived.collectAsStateWithLifecycle()

    Surface(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.safeDrawingPadding().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item { Spacer(Modifier.size(16.dp)); Text("Glasses", style = MaterialTheme.typography.headlineMedium) }
            item { Spacer(Modifier.size(8.dp)); ConnectionBanner(state) }
            item { Spacer(Modifier.size(8.dp)); ModelSection() }
            item { Spacer(Modifier.size(8.dp)); LastReceivedCard(last) }
            item { Spacer(Modifier.size(12.dp)); Text("Link log", style = MaterialTheme.typography.titleMedium) }
            items(log) { line ->
                Row {
                    Text(line.time, fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    Text(line.text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
            item { Spacer(Modifier.size(24.dp)) }
        }
    }
}

@Composable
fun ConnectionBanner(state: LinkState) {
    val (color, label) = when (state) {
        LinkState.Idle -> StatusColors.busy to "Starting…"
        LinkState.BluetoothOff -> StatusColors.bad to "Bluetooth is off — turn it on to connect"
        LinkState.Scanning -> StatusColors.busy to "Looking for glasses…"
        is LinkState.Connecting -> StatusColors.busy to "Connecting to ${state.name}…"
        is LinkState.Connected -> StatusColors.ok to "Connected to ${state.name}"
        is LinkState.Reconnecting -> StatusColors.bad to "${state.reason} — retrying in ${state.inSeconds}s"
    }
    Row(
        Modifier.fillMaxWidth()
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Dot(color)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun Dot(color: Color) {
    Surface(Modifier.size(10.dp), shape = CircleShape, color = color) {}
}

@Composable
private fun LastReceivedCard(msg: ReceivedMessage?) {
    Column(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Last message from glasses", style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (msg == null) {
            Text("Nothing received yet — press the button on the glasses",
                style = MaterialTheme.typography.bodyLarge)
        } else {
            Text("\"${msg.text}\"", style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace)
            Text("Received at ${msg.time}", style = MaterialTheme.typography.bodyMedium)
            when {
                msg.echoError != null -> Text("Echo back failed: ${msg.echoError}", color = StatusColors.bad)
                msg.echoMs != null -> Text("Echoed back in ${msg.echoMs} ms", color = StatusColors.ok)
                else -> Text("Echoing back…", color = StatusColors.busy)
            }
        }
    }
}

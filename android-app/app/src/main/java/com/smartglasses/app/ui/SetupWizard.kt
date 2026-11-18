package com.smartglasses.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartglasses.app.ai.ModelStatus
import com.smartglasses.app.ble.LinkState
import com.smartglasses.app.glassesApp

object Permissions {
    val BLUETOOTH = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    fun granted(c: Context, p: String) = ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED
    fun hasBluetooth(c: Context) = BLUETOOTH.all { granted(c, it) }
    fun hasNotifications(c: Context) = Build.VERSION.SDK_INT < 33 || granted(c, Manifest.permission.POST_NOTIFICATIONS)
    fun hasMic(c: Context) = granted(c, Manifest.permission.RECORD_AUDIO)
}

/**
 * First-run checklist with live status. Every step says what to do next in plain
 * words. Only Bluetooth is required to continue; the rest can be done later.
 */
@Composable
fun SetupWizard(onDone: () -> Unit) {
    val context = LocalContext.current
    val app = context.glassesApp
    var tick by remember { mutableIntStateOf(0) }  // re-read permissions after each request
    val refresh = { tick++ }
    val bt = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }
    val single = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }
    val model by app.modelStore.status.collectAsStateWithLifecycle()
    val link by app.link.state.collectAsStateWithLifecycle()
    @Suppress("UNUSED_EXPRESSION") tick

    val hasBt = Permissions.hasBluetooth(context)
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Set up your glasses", style = MaterialTheme.typography.headlineMedium)
            Text("Four steps. Only the first is needed to start; you can finish the others later in Settings.",
                style = MaterialTheme.typography.bodyLarge)

            Step(1, "Bluetooth access", hasBt,
                "The app talks to the glasses over Bluetooth. Android calls this \"Nearby devices\". Your location is never used.") {
                Button(onClick = { bt.launch(Permissions.BLUETOOTH) }) { Text("Allow Bluetooth access") }
                OutlinedButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                }) { Text("Open app settings (if you tapped \"Don't allow\")") }
            }
            Step(2, "Notifications (recommended)", Permissions.hasNotifications(context),
                "A small notification keeps the connection alive while the phone is locked.") {
                if (Build.VERSION.SDK_INT >= 33) Button(onClick = { single.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Allow notifications") }
            }
            Step(3, "Microphone (optional)", Permissions.hasMic(context),
                "Only for asking questions with the phone's microphone. The glasses have their own microphone.") {
                Button(onClick = { single.launch(Manifest.permission.RECORD_AUDIO) }) { Text("Allow microphone") }
            }
            Step(4, "AI model", model is ModelStatus.Ready,
                "Download gemma-4-E2B-it.litertlm (2.59 GB) to this phone, then import it here. Until then, photos are described with a basic labeller.") {
                ModelSetupCard()
            }
            Step(5, "Glasses", link is LinkState.Connected,
                when (link) {
                    is LinkState.Connected -> "Connected."
                    else -> "Switch the glasses on. They connect by themselves once Bluetooth access is allowed: there's no pairing step."
                }) {}

            Button(onClick = onDone, enabled = hasBt, modifier = Modifier.fillMaxWidth()) {
                Text(if (hasBt) "Continue" else "Allow Bluetooth access to continue")
            }
        }
    }
}

@Composable
private fun Step(n: Int, title: String, done: Boolean, explain: String, actions: @Composable () -> Unit) {
    Section("") {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) {
            contentDescription = "Step $n, $title: ${if (done) "done" else "to do"}"
        }) {
            Icon(if (done) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked, null,
                tint = if (done) StatusColors.ok else MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(10.dp))
            Text("$n. $title" + if (done) "  ✓ done" else "", style = MaterialTheme.typography.titleMedium)
        }
        Text(explain, style = MaterialTheme.typography.bodyMedium)
        if (!done) actions()
    }
}

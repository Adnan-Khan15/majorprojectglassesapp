package com.smartglasses.app.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

private val REQUIRED = buildList {
    add(Manifest.permission.BLUETOOTH_SCAN)
    add(Manifest.permission.BLUETOOTH_CONNECT)
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

// Notifications are nice-to-have; the app works without them.
private val ESSENTIAL = setOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)

/**
 * Explains why Bluetooth access is needed before asking, and gives a clear
 * path to Settings if the user previously chose "Don't allow".
 */
@Composable
fun PermissionGate(onGranted: () -> Unit, content: @Composable () -> Unit) {
    val context = LocalContext.current
    fun hasAll() = ESSENTIAL.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
    var granted by remember { mutableStateOf(hasAll()) }
    var deniedOnce by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted = hasAll(); if (!granted) deniedOnce = true }

    if (granted) {
        LaunchedEffect(Unit) { onGranted() }
        content()
        return
    }

    val activity = context as Activity
    val permanentlyDenied = deniedOnce && ESSENTIAL.none { activity.shouldShowRequestPermissionRationale(it) }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Outlined.Bluetooth, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
            Text("Connect to your glasses", style = MaterialTheme.typography.headlineSmall)
            Text(
                "This app talks to your glasses over Bluetooth to receive photos and send back " +
                    "what it sees. It needs permission to find and connect to nearby Bluetooth " +
                    "devices. Your location is not used.",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge,
            )
            if (permanentlyDenied) {
                Text(
                    "Bluetooth access was turned off for this app. Turn on \"Nearby devices\" in Settings to continue.",
                    textAlign = TextAlign.Center,
                    color = StatusColors.bad,
                )
                Button(onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                    )
                }) { Text("Open Settings") }
                Button(onClick = { granted = hasAll() }) { Text("I've turned it on") }
            } else {
                if (deniedOnce) Text("Without this the app can't reach your glasses.", color = StatusColors.bad)
                Button(onClick = { launcher.launch(REQUIRED) }) { Text("Allow Bluetooth access") }
            }
        }
    }
}

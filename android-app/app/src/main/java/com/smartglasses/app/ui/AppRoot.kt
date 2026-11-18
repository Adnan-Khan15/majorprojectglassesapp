package com.smartglasses.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartglasses.app.ble.LinkState
import com.smartglasses.app.glassesApp
import com.smartglasses.app.service.GlassesService

private enum class Tab(val label: String) { HOME("Home"), HISTORY("History"), SETTINGS("Settings"), DIAGNOSTICS("Diagnostics") }

/** Setup wizard on first run (or while Bluetooth access is missing), then four tabs. */
@Composable
fun AppRoot() {
    val context = LocalContext.current
    val app = context.glassesApp
    val setupDone by app.settings.setupDone.state.collectAsStateWithLifecycle()
    var btGranted by rememberSaveable { mutableStateOf(Permissions.hasBluetooth(context)) }

    if (!setupDone || !btGranted) {
        SetupWizard(onDone = {
            btGranted = Permissions.hasBluetooth(context)
            if (btGranted) app.settings.setupDone.set(true)
        })
        return
    }
    LaunchedEffect(Unit) { GlassesService.start(context) }

    // Haptic tick on every connection change (helpful without looking at the screen).
    val haptics = LocalHapticFeedback.current
    val link by app.link.state.collectAsStateWithLifecycle()
    val connected = link is LinkState.Connected
    LaunchedEffect(connected) { haptics.performHapticFeedback(HapticFeedbackType.LongPress) }

    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    BackHandler(enabled = tab != Tab.HOME) { tab = Tab.HOME }
    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t, onClick = { tab = t }, label = { Text(t.label) },
                        icon = {
                            Icon(when (t) {
                                Tab.HOME -> Icons.Outlined.Home
                                Tab.HISTORY -> Icons.Outlined.History
                                Tab.SETTINGS -> Icons.Outlined.Settings
                                Tab.DIAGNOSTICS -> Icons.Outlined.BugReport
                            }, null)
                        },
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when (tab) {
                Tab.HOME -> HomeScreen(onOpenSettings = { tab = Tab.SETTINGS })
                Tab.HISTORY -> HistoryScreen()
                Tab.SETTINGS -> SettingsScreen()
                Tab.DIAGNOSTICS -> DiagnosticsScreen()
            }
        }
    }
}

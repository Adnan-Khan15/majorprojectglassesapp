package com.smartglasses.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.smartglasses.app.service.GlassesService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GlassesTheme {
                PermissionGate(onGranted = { GlassesService.start(this) }) {
                    MainScreen()
                }
            }
        }
    }
}

package com.smartglasses.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Blue = Color(0xFF1F6FEB)

@Composable
fun GlassesTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = Color(0xFF79A8FF), secondary = Color(0xFF8BD5A8))
    } else {
        lightColorScheme(primary = Blue, secondary = Color(0xFF1E8E5A))
    }
    MaterialTheme(colorScheme = colors, content = content)
}

object StatusColors {
    val ok = Color(0xFF1E8E5A)
    val busy = Color(0xFFD99A00)
    val bad = Color(0xFFD1453B)
}

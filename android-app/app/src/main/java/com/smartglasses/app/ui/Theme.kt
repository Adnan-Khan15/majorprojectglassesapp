package com.smartglasses.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp

private val Blue = Color(0xFF1F5FD6)

/** Material 3, light and dark, with type a step larger than default for low-vision helpers. */
@Composable
fun GlassesTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = Color(0xFF8AB4FF), secondary = Color(0xFF8BD5A8))
    } else {
        lightColorScheme(primary = Blue, secondary = Color(0xFF1E7A4F))
    }
    val base = Typography()
    fun TextStyle.up(by: Int) = copy(fontSize = (fontSize.value + by).sp, lineHeight = (lineHeight.value + by + 2).sp)
    val type = base.copy(
        bodySmall = base.bodySmall.up(1), bodyMedium = base.bodyMedium.up(2), bodyLarge = base.bodyLarge.up(2),
        titleMedium = base.titleMedium.up(2), titleLarge = base.titleLarge.up(2), labelMedium = base.labelMedium.up(1),
        labelLarge = base.labelLarge.up(1),
    )
    MaterialTheme(colorScheme = colors, typography = type, content = content)
}

/** Status colours. Always paired with words or icons: meaning is never carried by colour alone. */
object StatusColors {
    val ok = Color(0xFF1E8E5A)
    val busy = Color(0xFFB57F00)
    val bad = Color(0xFFC7372E)
}

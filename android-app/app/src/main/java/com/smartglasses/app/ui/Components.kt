package com.smartglasses.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Card-like section with a title. */
@Composable
fun Section(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (title.isNotBlank()) Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        content()
    }
}

enum class Health { OK, BUSY, BAD }

fun Health.color() = when (this) { Health.OK -> StatusColors.ok; Health.BUSY -> StatusColors.busy; Health.BAD -> StatusColors.bad }

/**
 * Status chip: icon + word + dot. Meaning is in the WORDS (and the icon), never only
 * in the colour; TalkBack reads changes politely (live region).
 */
@Composable
fun StatusChip(icon: ImageVector, label: String, value: String, health: Health, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .heightIn(min = 56.dp)
            .background(health.color().copy(alpha = 0.14f), RoundedCornerShape(14.dp))
            .clickable(onClickLabel = "Details for $label", role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = "$label: $value"
                liveRegion = LiveRegionMode.Polite
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = health.color())
        Spacer(Modifier.width(8.dp))
        Column {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun Dot(color: Color) {
    Surface(Modifier.size(10.dp), shape = CircleShape, color = color) {}
}

/** Big primary action: at least 72 dp tall, full width. */
@Composable
fun BigAction(text: String, icon: ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    androidx.compose.material3.Button(
        onClick = onClick, enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
        shape = RoundedCornerShape(16.dp),
    ) {
        Icon(icon, null)
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

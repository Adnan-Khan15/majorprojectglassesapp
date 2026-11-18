package com.smartglasses.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.smartglasses.app.glassesApp
import com.smartglasses.app.voice.PhoneMic
import kotlinx.coroutines.launch

/**
 * "Ask with phone mic": hold to record (max 10 s), release to ask. The glasses take
 * the photo when you release (or the latest photo is used if they're not connected).
 * Also works by double-tap with TalkBack: first activation starts, second stops.
 */
@Composable
fun PhoneMicButton(enabled: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val session = context.glassesApp.session
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val mic = remember { PhoneMic() }
    var recording by remember { mutableStateOf(false) }
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }

    fun startRec() {
        if (recording) return
        recording = true
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        scope.launch {
            val pcm = runCatching { mic.record(10_000) { session.setListening(it) } }.getOrNull()
            recording = false
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            if (pcm != null) session.askWithPhoneMic(pcm)
        }
    }

    val label = when {
        !granted -> "Allow microphone to ask questions"
        recording -> "Listening… release to ask"
        else -> "Hold to ask with phone mic"
    }
    Box(
        modifier.fillMaxWidth().heightIn(min = 64.dp)
            .background(
                if (recording) StatusColors.busy.copy(alpha = 0.3f) else MaterialTheme.colorScheme.primaryContainer,
                RoundedCornerShape(16.dp),
            )
            .semantics { role = Role.Button; contentDescription = label }
            .pointerInput(enabled, granted) {
                detectTapGestures(
                    onPress = {
                        if (!enabled) return@detectTapGestures
                        if (!granted) { ask.launch(Manifest.permission.RECORD_AUDIO); return@detectTapGestures }
                        startRec()
                        tryAwaitRelease()
                        mic.stop()
                    },
                )
            }
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(if (enabled) label else "Asking questions needs the AI model", style = MaterialTheme.typography.titleMedium)
    }
}

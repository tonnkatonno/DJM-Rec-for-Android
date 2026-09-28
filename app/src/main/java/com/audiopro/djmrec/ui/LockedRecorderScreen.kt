package com.audiopro.djmrec.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.audiopro.djmrec.audio.RecordingFormat
import com.audiopro.djmrec.audio.RecordingHealth
import com.audiopro.djmrec.audio.RecordingHealthLevel
import com.audiopro.djmrec.audio.RecordingState
import com.audiopro.djmrec.ui.components.StereoVuMeter
import com.audiopro.djmrec.ui.theme.AccentAmber
import com.audiopro.djmrec.ui.theme.AccentCyan
import com.audiopro.djmrec.ui.theme.AccentLime
import com.audiopro.djmrec.ui.theme.AccentOrange
import com.audiopro.djmrec.ui.theme.BackgroundDark
import com.audiopro.djmrec.ui.theme.BorderHairline
import com.audiopro.djmrec.ui.theme.NfDisplayFontFamily
import com.audiopro.djmrec.ui.theme.NfMonoFontFamily
import com.audiopro.djmrec.ui.theme.SurfaceDark
import com.audiopro.djmrec.ui.theme.TextDim
import com.audiopro.djmrec.ui.theme.TextPrimary
import com.audiopro.djmrec.ui.theme.TextSecondary
import java.util.Locale

/**
 * What the phone shows when woken *while locked* during a take (MainActivity only turns on
 * show-when-locked while Recording/Paused). One job: prove at a glance that the set is still
 * being captured -- ticking timer, pulsing REC, the file size climbing, live meters -- and shout
 * if it isn't. Deliberately no Stop/Pause/Library/Settings here: nothing reachable without
 * unlocking can end or damage a take. MARK is allowed (harmless, and handy mid-mix).
 */
@Composable
fun LockedRecorderScreen(viewModel: MainViewModel, onUnlock: () -> Unit) {
    val state by viewModel.recordingState.collectAsState()
    val elapsed by viewModel.elapsedMillis.collectAsState()
    val levels by viewModel.levels.collectAsState()
    val health by viewModel.recordingHealth.collectAsState()
    val format by viewModel.selectedFormat.collectAsState()
    val awaitingAudio by viewModel.awaitingAudio.collectAsState()
    val marks by viewModel.trackMarkCount.collectAsState()

    val isPaused = state is RecordingState.Paused
    val problem = health.level != RecordingHealthLevel.GOOD && health.level != RecordingHealthLevel.READY
    val statusColor = when {
        problem -> AccentAmber
        isPaused -> AccentCyan
        else -> AccentLime
    }

    val pulse by rememberInfiniteTransition(label = "rec").animateFloat(
        initialValue = 1f, targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "recPulse"
    )

    Box(
        modifier = Modifier.fillMaxSize().background(BackgroundDark).systemBarsPadding().padding(24.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("DJM REC", style = labelStyle, color = TextDim)
                Spacer(Modifier.height(28.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(14.dp)
                            .alpha(if (isPaused) 1f else pulse)
                            .background(if (isPaused) AccentCyan else AccentOrange, CircleShape)
                    )
                    Spacer(Modifier.size(10.dp))
                    Text(
                        if (isPaused) "PAUSED" else "REC",
                        style = labelStyle.copy(fontSize = 16.sp),
                        color = if (isPaused) AccentCyan else AccentOrange
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    formatClock(elapsed),
                    style = TextStyle(
                        fontFamily = NfDisplayFontFamily, fontWeight = FontWeight.Bold,
                        fontSize = 64.sp, letterSpacing = 2.sp
                    ),
                    color = TextPrimary
                )
                Spacer(Modifier.height(20.dp))
                StatusPanel(health = health, format = format, isPaused = isPaused, awaitingAudio = awaitingAudio, color = statusColor)
            }

            Column(Modifier.fillMaxWidth()) {
                StereoVuMeter(levels = levels, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = viewModel::markTrackChange,
                        enabled = !isPaused,
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(3.dp),
                        border = BorderStroke(1.dp, AccentCyan)
                    ) {
                        Text(if (marks > 0) "MARK  ·  $marks" else "MARK TRACK", style = labelStyle, color = AccentCyan)
                    }
                    OutlinedButton(
                        onClick = onUnlock,
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(3.dp),
                        border = BorderStroke(1.dp, BorderHairline)
                    ) {
                        Text("UNLOCK", style = labelStyle, color = TextSecondary)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusPanel(
    health: RecordingHealth,
    format: RecordingFormat,
    isPaused: Boolean,
    awaitingAudio: Boolean,
    color: Color
) {
    val headline = when {
        health.level == RecordingHealthLevel.ERROR -> "⚠ RECORDING PROBLEM"
        health.level == RecordingHealthLevel.USB_UNSTABLE -> "⚠ USB UNSTABLE"
        health.level == RecordingHealthLevel.SILENCE -> "⚠ NO INPUT SIGNAL"
        health.level == RecordingHealthLevel.LOW_STORAGE -> "⚠ LOW STORAGE"
        health.level == RecordingHealthLevel.LOW_BATTERY -> "⚠ LOW BATTERY"
        health.level == RecordingHealthLevel.OVERHEATING -> "⚠ PHONE OVERHEATING"
        isPaused -> "NOTHING BEING WRITTEN"
        awaitingAudio -> "ARMED · WAITING FOR AUDIO"
        else -> "✓ WRITING TO DISK"
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = SurfaceDark,
        shape = RoundedCornerShape(3.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.6f))
    ) {
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(headline, style = labelStyle.copy(fontSize = 15.sp), color = color)
            Spacer(Modifier.height(10.dp))
            Text(
                format.name,
                style = TextStyle(fontFamily = NfDisplayFontFamily, fontWeight = FontWeight.Bold, fontSize = 26.sp),
                color = TextPrimary
            )
            if (health.level != RecordingHealthLevel.GOOD && health.message.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(health.message, style = labelStyle, color = TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (health.freeBytes > 0 && health.freeBytes != Long.MAX_VALUE) {
                Spacer(Modifier.height(6.dp))
                val remaining = health.remainingSeconds.takeIf { it in 1 until Long.MAX_VALUE }
                    ?.let { "  ·  ~${formatClock(it * 1000)} LEFT" }.orEmpty()
                Text("${formatSizeLong(health.freeBytes)} FREE$remaining", style = labelStyle, color = TextSecondary)
            }
        }
    }
}

private val labelStyle = TextStyle(
    fontFamily = NfMonoFontFamily, fontSize = 12.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Medium
)

private fun formatClock(ms: Long): String {
    val s = ms / 1000
    return String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
}

private fun formatSizeLong(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024) String.format(Locale.US, "%.2f GB", mb / 1024)
    else String.format(Locale.US, "%.1f MB", mb)
}

package com.ottomusic.sketch.ui

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ottomusic.sketch.core.RecordingCore
import com.ottomusic.sketch.recording.RecordingPhase
import com.ottomusic.sketch.recording.RecordingServiceState

@Composable
internal fun RecordScreen(
    recording: RecordingServiceState,
    shortcutRequested: Boolean,
    microphoneGranted: Boolean,
    microphoneDenied: Boolean,
    notificationsGranted: Boolean,
    startError: String?,
    layering: Boolean,
    calibrationAvailable: Boolean,
    onBack: () -> Unit,
    onCalibration: () -> Unit,
    onRecord: () -> Unit,
    onRequestMic: () -> Unit,
    onOpenSettings: () -> Unit,
    onPauseResume: () -> Unit,
    onStop: () -> Unit,
    onOpenRecording: () -> Unit
) {
    val active = recording.phase == RecordingPhase.RECORDING || recording.phase == RecordingPhase.PAUSED
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            SynthTopBar("Record", onBack, backLabel = "Library")
            SynthPanel {
                val label = when (recording.phase) {
                    RecordingPhase.PAUSED -> "Paused"
                    RecordingPhase.RECORDING -> if (layering) "Recording over backing" else "Recording"
                    RecordingPhase.STARTING -> "Starting microphone…"
                    RecordingPhase.COMPLETE -> "Recording saved"
                    RecordingPhase.FAILED -> "Recording stopped"
                    else -> if (layering) "Layer: backing plays in headphones" else "Ready"
                }
                Text(label, color = if (recording.phase == RecordingPhase.RECORDING) Synth.pink else Synth.muted, fontWeight = FontWeight.SemiBold)
                Text(
                    RecordingCore.formatClock(RecordingCore.elapsedMillis(recording.frames, recording.sampleRate)),
                    color = if (active) Synth.pink else Synth.cyan, fontSize = 60.sp, fontFamily = FontFamily.Monospace
                )
                LevelMeter(recording.level.peak, recording.level.isClipping, Modifier.fillMaxWidth().height(54.dp))
                val cue = when {
                    recording.inputSilenced && active -> "Android has silenced microphone input. Check microphone access and routing."
                    recording.silenceWarning && active -> "Sustained silence detected. Check microphone access and routing."
                    recording.level.isClipping && active -> "Clipping — move the microphone farther away."
                    recording.level.isLow && active -> "Low signal — check microphone distance."
                    else -> null
                }
                cue?.let { Text(it, color = Synth.muted) }
                Text("${recording.inputDevice} · ${recording.sampleRate} Hz", color = Synth.muted, style = MaterialTheme.typography.bodySmall)
                if (layering) Text("Wired headphones are recommended. Bluetooth timing is unverified.", color = Synth.muted, style = MaterialTheme.typography.bodySmall)
                if (recording.silent && recording.projectId != null) Text("Silent recording saved to your library.", color = MaterialTheme.colorScheme.tertiary)
                recording.interruptionNotice?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                recording.alignmentNotice?.let { Text(it, color = Synth.muted) }
            }
            if (!active && (layering || calibrationAvailable)) {
                BigButton("Route timing calibration", onCalibration, Modifier.fillMaxWidth())
            }
            when {
                active -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    RoundButton(
                        if (recording.phase == RecordingPhase.PAUSED) SynthIcon.Rec else SynthIcon.Pause, if (recording.phase == RecordingPhase.PAUSED) "Resume" else "Pause",
                        Synth.surfaceHi, onPauseResume, size = 80.dp, contentColor = Synth.text
                    )
                    RoundButton(SynthIcon.Stop, "Stop and save", Synth.pink, onStop, size = 112.dp, contentColor = Color.White)
                }
                recording.projectId != null -> {
                    BigButton("Open recording", onOpenRecording, Modifier.fillMaxWidth(), primary = true)
                    BigButton("Record another", onRecord, Modifier.fillMaxWidth(), icon = SynthIcon.Rec)
                }
                microphoneGranted -> RoundButton(SynthIcon.Rec, "Start recording", Synth.pink, onRecord, size = 120.dp, contentColor = Color.White)
                else -> {
                    Text(
                        if (microphoneDenied) "Microphone access is off. The library remains available; allow access to record." else "Audio Sketch needs microphone access to record.",
                        textAlign = TextAlign.Center, color = Synth.night
                    )
                    if (shortcutRequested) Text("On first use, Record now waits for microphone and notification permissions before recording.", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, color = Synth.night)
                    if (microphoneDenied) {
                        BigButton("Open app settings", onOpenSettings, Modifier.fillMaxWidth())
                        BigButton("Try permission again", onRequestMic, Modifier.fillMaxWidth())
                    } else BigButton("Allow microphone", onRecord, Modifier.fillMaxWidth(), primary = true)
                }
            }
            if (recording.error != null || startError != null) {
                Text(recording.error ?: startError.orEmpty(), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                if (recording.projectId != null) Text("A recoverable take is in your library.", color = Synth.night)
            }
            if (Build.VERSION.SDK_INT >= 33 && !notificationsGranted) {
                Text(
                    "Notifications are off. Android shows an active recording notice in Task Manager, but not in the notification drawer. Use the app controls to pause or stop.",
                    style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, color = Synth.night
                )
            }
        }
    }
}

@Composable
private fun LevelMeter(peak: Float, clipping: Boolean, modifier: Modifier = Modifier) {
    Canvas(
        modifier.clip(RoundedCornerShape(12.dp)).background(Synth.night.copy(alpha = 0.8f))
            .semantics {
                contentDescription = "Microphone level ${(peak * 100).toInt()} percent${if (clipping) ", clipping" else ""}"
                progressBarRangeInfo = ProgressBarRangeInfo(peak.coerceIn(0f, 1f), 0f..1f)
            }
    ) {
        val count = 30
        val gap = 3.dp.toPx()
        val cellWidth = (size.width - gap * (count - 1)) / count
        val active = (peak.coerceIn(0f, 1f) * count).toInt().coerceAtLeast(if (peak > 0f) 1 else 0)
        repeat(count) { index ->
            val left = index * (cellWidth + gap)
            val base = when { clipping -> Synth.pink; index > count * 0.85f -> Synth.pink; index > count * 0.6f -> Synth.yellow; else -> Synth.cyan }
            drawRoundRect(if (index < active) base else base.copy(alpha = 0.12f), androidx.compose.ui.geometry.Offset(left, 6.dp.toPx()), androidx.compose.ui.geometry.Size(cellWidth, size.height - 12.dp.toPx()), cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
        }
    }
}
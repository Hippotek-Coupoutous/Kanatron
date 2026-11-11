package com.kanatron.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kanatron.app.core.Eq
import com.kanatron.app.core.OverdubMix

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MixSheet(
    subject: String,
    levelSteps: Int,
    eq: Eq,
    mixed: Boolean,
    bypass: Boolean,
    playing: Boolean,
    previewReady: Boolean,
    onChange: (Int, Eq) -> Unit,
    onBypass: () -> Unit,
    onPreview: () -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
    status: String?
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Synth.surface, contentColor = Synth.text) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("EQ · $subject", style = MaterialTheme.typography.titleLarge, maxLines = 1)
            if (mixed) Text("Mixed settings. Change a control or Reset to set the whole selection.", color = Synth.muted, style = MaterialTheme.typography.bodySmall)
            MixRow("Level", if (levelSteps == 0) "0 dB" else "−${levelSteps * OverdubMix.DB_PER_STEP} dB",
                minus = (levelSteps + 1).takeIf { it <= OverdubMix.MAX_BALANCE_STEPS }?.let { { onChange(it, eq) } },
                plus = (levelSteps - 1).takeIf { it >= 0 }?.let { { onChange(it, eq) } })
            for ((band, steps, set) in listOf(
                Triple("Low", eq.lowSteps) { s: Int -> eq.copy(lowSteps = s) },
                Triple("Mid", eq.midSteps) { s: Int -> eq.copy(midSteps = s) },
                Triple("High", eq.highSteps) { s: Int -> eq.copy(highSteps = s) }
            )) {
                MixRow(band, signedDb(steps * Eq.DB_PER_STEP),
                    minus = (steps - 1).takeIf { it >= -Eq.MAX_STEPS }?.let { { onChange(levelSteps, set(it)) } },
                    plus = (steps + 1).takeIf { it <= Eq.MAX_STEPS }?.let { { onChange(levelSteps, set(it)) } })
            }
            status?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Key(if (playing) "Pause" else "Preview", onPreview, Modifier.weight(1f), enabled = previewReady, face = Synth.cyan, height = KeyHeight.Small)
                Key("Bypass", onBypass, Modifier.weight(1f), selected = bypass, height = KeyHeight.Small)
                Key("Reset", { onChange(0, Eq.FLAT) }, Modifier.weight(1f), enabled = levelSteps != 0 || !eq.flat || mixed, description = "Reset level and EQ", height = KeyHeight.Small)
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Key("Cancel", onDismiss, Modifier.weight(1f), height = KeyHeight.Small)
                Key("Apply", onApply, Modifier.weight(1f), face = Synth.yellow, enabled = !mixed, height = KeyHeight.Small)
            }
        }
    }
}

@Composable
private fun MixRow(name: String, value: String, minus: (() -> Unit)?, plus: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(name.uppercase(), style = MaterialTheme.typography.labelLarge, color = Synth.muted, modifier = Modifier.width(64.dp))
        Stepper(value, { minus?.invoke() }, { plus?.invoke() }, "$name down", "$name up", Modifier.weight(1f), minusEnabled = minus != null, plusEnabled = plus != null)
    }
}

private fun signedDb(db: Int) = when {
    db > 0 -> "+$db dB"
    db < 0 -> "−${-db} dB"
    else -> "0 dB"
}

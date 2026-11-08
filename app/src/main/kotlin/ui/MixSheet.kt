package com.kanatron.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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

/** Level and three-band EQ for whatever Edit is working on; changes apply live and render on Save. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MixSheet(subject: String, levelSteps: Int, eq: Eq, onChange: (levelSteps: Int, eq: Eq) -> Unit, onDismiss: () -> Unit) {
    // Every row matters at once, so the sheet opens fully.
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Synth.surface, contentColor = Synth.text) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("MIX · $subject", style = MaterialTheme.typography.titleLarge, maxLines = 1)
            // Level only lowers: it is the way out when a boost or an overlap would clip.
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
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Key("Flat", { onChange(0, Eq.FLAT) }, Modifier.weight(1f), enabled = levelSteps != 0 || !eq.flat, description = "Reset level and EQ")
                Key("Done", onDismiss, Modifier.weight(1f), face = Synth.cyan)
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

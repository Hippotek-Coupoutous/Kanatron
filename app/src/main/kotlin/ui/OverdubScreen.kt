package com.kanatron.app.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kanatron.app.core.OverdubMix
import com.kanatron.app.data.ProjectRepository
import com.kanatron.app.playback.WavPlayer
import kotlin.math.abs

/** A finished take over its backing: set the balance, nudge the take into time, hear it, then mix it. */
@Composable
internal fun OverdubScreen(
    mix: OverdubMix,
    repository: ProjectRepository,
    playbackAllowed: Boolean,
    saving: Boolean,
    error: String?,
    notice: String?,
    onNudge: (millis: Int) -> Unit,
    onBalance: (steps: Int) -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    // A save renders the mix itself, so the preview stops competing with it.
    val preview = producePlaybackRender(repository, PREVIEW_DEBOUNCE_MILLIS, mix, saving) { if (saving) null else mix.arrangement() }
    var player by remember(preview) { mutableStateOf<WavPlayer?>(null) }
    DisposableEffect(preview) { onDispose { player?.release() } }
    val playing = player?.state?.collectAsState()?.value?.isPlaying == true
    if (!playbackAllowed && playing) player?.pause()
    val rate = mix.base.sampleRate
    val balance: @Composable (Modifier) -> Unit = { modifier ->
        SynthPanel(modifier) {
            val steps = mix.balanceSteps
            Text(
                when { steps > 0 -> "Balance · backing lowered"; steps < 0 -> "Balance · take lowered"; else -> "Balance" },
                color = Synth.muted, style = MaterialTheme.typography.labelMedium
            )
            Stepper(
                if (steps == 0) "Even" else "−${abs(steps) * OverdubMix.DB_PER_STEP} dB", { onBalance(-1) }, { onBalance(1) }, "Take quieter", "Take louder",
                minusEnabled = !saving && steps > -OverdubMix.MAX_BALANCE_STEPS, plusEnabled = !saving && steps < OverdubMix.MAX_BALANCE_STEPS
            )
        }
    }
    val nudge: @Composable (Modifier) -> Unit = { modifier ->
        SynthPanel(modifier) {
            Text("Nudge take", color = Synth.muted, style = MaterialTheme.typography.labelMedium)
            Text(
                "${if (mix.nudgeMillis > 0) "+" else ""}${mix.nudgeMillis} ms · starts at ${if (mix.offsetFrames < 0) "−" else ""}${clockMillis(abs(mix.offsetFrames), rate)}",
                color = Synth.cyan, style = MaterialTheme.typography.titleMedium
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (millis in NUDGES) {
                    val label = "${if (millis > 0) "+" else "−"}${abs(millis)}"
                    Key(
                        "$label ms", { onNudge(millis) }, Modifier.weight(1f), height = KeyHeight.Small,
                        description = "Take ${abs(millis)} ms ${if (millis > 0) "later" else "earlier"}",
                        enabled = !saving && mix.nudged(millis) != null
                    )
                }
            }
        }
    }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SynthTopBar("Overdub", { player?.stop(); onClose() }, backLabel = "Sketch")
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Take over ${mix.base.name}", color = Synth.night, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    balance(Modifier.weight(1f))
                    nudge(Modifier.weight(1f))
                }
            } else {
                balance(Modifier)
                nudge(Modifier)
            }
        }
        // How the take was placed matters whatever the preview is doing.
        notice?.let { Text(it, color = Synth.night, style = MaterialTheme.typography.bodySmall) }
        val status = when {
            saving -> "Saving…"
            error != null -> error
            preview?.exceptionOrNull() != null -> preview.exceptionOrNull()?.message
            preview == null -> "Preparing the mix…"
            else -> "Save mixes the take into a new recording and removes the separate take. ${mix.base.name} stays as it is."
        }
        Text(status.orEmpty(), color = if (error != null) MaterialTheme.colorScheme.error else Synth.night, style = MaterialTheme.typography.bodySmall)
        if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val file = preview?.getOrNull()
            Key(
                if (playing) "Pause" else "Preview",
                {
                    val wav = file ?: return@Key
                    val current = player ?: WavPlayer(context, wav).also { player = it }
                    // A second of backing before the take is enough to judge its timing.
                    if (playing) current.pause() else { current.seek((mix.takePositionFrames - rate).coerceAtLeast(0L)); current.play() }
                },
                Modifier.weight(1f), icon = if (playing) SynthIcon.Pause else SynthIcon.Play, face = Synth.cyan, enabled = file != null && !saving && playbackAllowed,
                description = if (playing) "Pause preview" else "Preview from just before the take"
            )
            Key("Save as new", { player?.stop(); onSave() }, Modifier.weight(2f), icon = SynthIcon.Save, face = Synth.yellow, enabled = !saving)
        }
    }
}

private val NUDGES = listOf(-10, -1, 1, 10)

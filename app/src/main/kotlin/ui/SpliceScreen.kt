package com.kanatron.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kanatron.app.core.Cuts
import com.kanatron.app.core.Peak
import com.kanatron.app.core.PeakExtractor
import com.kanatron.app.core.TrimEdge
import com.kanatron.app.core.TrimRange
import com.kanatron.app.core.firstClip
import com.kanatron.app.data.ProjectRepository
import com.kanatron.app.data.SplicePart
import com.kanatron.app.playback.WavPlayer
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** Two recordings' ranges joined back to back into a new recording; the originals stay untouched. */
@Composable
internal fun SpliceScreen(
    parts: List<SplicePart>,
    repository: ProjectRepository,
    playbackAllowed: Boolean,
    saving: Boolean,
    error: String?,
    onRange: (index: Int, TrimRange) -> Unit,
    onChooseSecond: () -> Unit,
    onSwap: () -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val complete = parts.size == 2
    val joined = remember(parts) { if (complete) Cuts.join(parts.first().project, parts.map { it.project.firstClip.source to it.range }) else null }
    val preview by produceState<Result<File>?>(null, joined) {
        value = null
        if (joined == null) return@produceState
        delay(250)
        val cancelled = AtomicBoolean(false)
        val render = async(Dispatchers.IO) { runCatching { repository.playbackFile(joined, cancelled) } }
        try {
            value = render.await()
        } finally {
            cancelled.set(true)
        }
    }
    var player by remember(preview) { mutableStateOf<WavPlayer?>(null) }
    DisposableEffect(preview) { onDispose { player?.release() } }
    val playing = player?.state?.collectAsState()?.value?.isPlaying == true
    if (!playbackAllowed && playing) player?.pause()

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SynthTopBar("Splice", { player?.stop(); onClose() }, backLabel = "Edit")
        parts.forEachIndexed { index, part ->
            Lane(if (index == 0) "First" else "Then", part, repository) { onRange(index, it) }
        }
        if (!complete) {
            BigButton("Choose a recording to join", { player?.stop(); onChooseSecond() }, Modifier.fillMaxWidth(), icon = SynthIcon.Import, enabled = !saving)
            Box(Modifier.weight(1f))
        }
        val notice = when {
            saving -> "Saving…"
            error != null -> error
            preview?.exceptionOrNull() != null -> preview?.exceptionOrNull()?.message
            complete && parts[0].project.sampleRate != parts[1].project.sampleRate ->
                "The second part is converted to ${parts[0].project.sampleRate} Hz."
            else -> "Saving makes a new recording. The originals stay as they are."
        }
        Text(notice.orEmpty(), color = if (error != null) MaterialTheme.colorScheme.error else Synth.night, style = MaterialTheme.typography.bodySmall)
        if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val file = preview?.getOrNull()
            Tile(
                if (playing) SynthIcon.Pause else SynthIcon.Play, if (playing) "Pause" else "Preview",
                {
                    val wav = file ?: return@Tile
                    val current = player ?: WavPlayer(context, wav).also { player = it }
                    if (playing) current.pause() else { current.seek(0L); current.play() }
                },
                Modifier.weight(1f), enabled = file != null && !saving && playbackAllowed, minHeight = 76.dp,
                description = if (playing) "Pause preview" else "Preview the splice"
            )
            Tile(SynthIcon.Splice, "Swap", { player?.stop(); onSwap() }, Modifier.weight(1f), enabled = complete && !saving, minHeight = 76.dp, description = "Swap the order")
        }
        BigButton("Save as new recording", { player?.stop(); onSave() }, Modifier.fillMaxWidth(), primary = true, enabled = complete && !saving)
    }
}

@Composable
private fun ColumnScope.Lane(label: String, part: SplicePart, repository: ProjectRepository, onRange: (TrimRange) -> Unit) {
    val total = part.project.firstClip.source.totalFrames
    val file = remember(part.project.id) { repository.audioFile(part.project) }
    val overview by produceState(emptyList<Peak>(), file) {
        value = withContext(Dispatchers.IO) { runCatching { PeakExtractor.extractCached(file, 2_048, { !isActive }) }.getOrNull() }.orEmpty()
    }
    var dragging by remember { mutableStateOf<TrimEdge?>(null) }
    val rate = part.project.firstClip.source.sampleRate
    Text("$label · ${part.project.name}", color = Synth.night, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    SynthPanel(Modifier.fillMaxWidth().weight(1f)) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            Waveform(
                file, overview, total, rate, 0L, total, part.range, null, dragging,
                onEdgeDrag = { edge, frame ->
                    dragging = edge
                    when (edge) {
                        TrimEdge.Start -> onRange(TrimRange(frame.coerceIn(0L, part.range.endFrame - 1L), part.range.endFrame))
                        TrimEdge.End -> onRange(TrimRange(part.range.startFrame, frame.coerceIn(part.range.startFrame + 1L, total)))
                        null -> Unit
                    }
                },
                onPan = {}, onZoom = { _, _ -> }, onTap = {}
            )
        }
        Text("${clockMillis(part.range.startFrame, rate)} – ${clockMillis(part.range.endFrame, rate)}", color = Synth.muted, style = MaterialTheme.typography.bodySmall)
    }
}

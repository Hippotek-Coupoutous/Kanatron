package com.kanatron.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kanatron.app.core.Cuts
import com.kanatron.app.core.Peak
import com.kanatron.app.core.PeakExtractor
import com.kanatron.app.core.TrimEdge
import com.kanatron.app.core.TrimRange
import com.kanatron.app.core.firstClip
import com.kanatron.app.data.MAX_SPLICE_PARTS
import com.kanatron.app.data.ProjectRepository
import com.kanatron.app.data.SplicePart
import com.kanatron.app.playback.WavPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** Ranges of several recordings joined back to back into a new recording; the originals stay untouched. */
@Composable
internal fun SpliceScreen(
    parts: List<SplicePart>,
    repository: ProjectRepository,
    playbackAllowed: Boolean,
    saving: Boolean,
    error: String?,
    onRange: (index: Int, TrimRange) -> Unit,
    onChooseNext: () -> Unit,
    onMoveEarlier: (index: Int) -> Unit,
    onRemove: (index: Int) -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val complete = parts.size >= 2
    // A save renders the splice itself, so the preview stops competing with it.
    val preview = producePlaybackRender(repository, PREVIEW_DEBOUNCE_MILLIS, parts, saving) {
        if (complete && !saving) Cuts.join(parts.first().project, parts.map { it.project.firstClip.source to it.range }) else null
    }
    var player by remember(preview) { mutableStateOf<WavPlayer?>(null) }
    DisposableEffect(preview) { onDispose { player?.release() } }
    val playing = player?.state?.collectAsState()?.value?.isPlaying == true
    if (!playbackAllowed && playing) player?.pause()
    // The screen is new after the picker, so the part just added starts out selected.
    var selection by rememberSaveable { mutableIntStateOf(parts.lastIndex) }
    val selected = selection.coerceIn(0, parts.lastIndex)
    fun move(from: Int, to: Int) {
        player?.stop()
        onMoveEarlier(maxOf(from, to))
        selection = to
    }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SynthTopBar("Splice", { player?.stop(); onClose() }, backLabel = "Edit")
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            parts.forEachIndexed { index, part ->
                Lane(index, part, repository, index == selected, onSelect = { selection = index }, onRange = { onRange(index, it) })
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val idle = !saving
            PartKey("Add", "Add a recording", idle && parts.size < MAX_SPLICE_PARTS, Modifier.weight(1f)) { player?.stop(); onChooseNext() }
            PartKey("Earlier", "Play part ${selected + 1} earlier", idle && selected > 0, Modifier.weight(1f)) { move(selected, selected - 1) }
            PartKey("Later", "Play part ${selected + 1} later", idle && selected < parts.lastIndex, Modifier.weight(1f)) { move(selected, selected + 1) }
            PartKey("Remove", "Remove part ${selected + 1}", idle && parts.size > 1, Modifier.weight(1f)) { player?.stop(); onRemove(selected) }
        }
        val notice = when {
            saving -> "Saving…"
            error != null -> error
            preview?.exceptionOrNull() != null -> preview.exceptionOrNull()?.message
            parts.any { it.project.sampleRate != parts[0].project.sampleRate } ->
                "Every part plays at ${parts[0].project.sampleRate} Hz, the first part's rate."
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
            BigButton("Save as new", { player?.stop(); onSave() }, Modifier.weight(2f).heightIn(min = 76.dp), primary = true, enabled = complete && !saving)
        }
    }
}

@Composable
private fun Lane(index: Int, part: SplicePart, repository: ProjectRepository, selected: Boolean, onSelect: () -> Unit, onRange: (TrimRange) -> Unit) {
    val total = part.project.firstClip.source.totalFrames
    val file = remember(part.project.id) { repository.audioFile(part.project) }
    val overview by produceState(emptyList<Peak>(), file) {
        value = withContext(Dispatchers.IO) { runCatching { PeakExtractor.extractCached(file, 2_048, { !isActive }) }.getOrNull() }.orEmpty()
    }
    var dragging by remember { mutableStateOf<TrimEdge?>(null) }
    val rate = part.project.firstClip.source.sampleRate
    val shape = RoundedCornerShape(22.dp)
    val view = remember { BringIntoViewRequester() }
    // The shared keys act on the selected part, so it must be on screen.
    LaunchedEffect(selected) { if (selected) view.bringIntoView() }
    SynthPanel(
        Modifier.bringIntoViewRequester(view).border(2.dp, if (selected) Synth.yellow else Color.Transparent, shape).clip(shape)
            .clickable(role = Role.Tab, onClick = onSelect)
            .semantics { this.selected = selected }
    ) {
        Text("${index + 1} · ${part.project.name}", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        // A fixed height keeps every lane draggable however many parts there are; the list scrolls instead.
        Box(Modifier.fillMaxWidth().height(112.dp)) {
            Waveform(
                file, overview, total, rate, 0L, total, part.range, null, dragging,
                onEdgeDrag = { edge, frame ->
                    onSelect()
                    dragging = edge
                    when (edge) {
                        TrimEdge.Start -> onRange(TrimRange(frame.coerceIn(0L, part.range.endFrame - 1L), part.range.endFrame))
                        TrimEdge.End -> onRange(TrimRange(part.range.startFrame, frame.coerceIn(part.range.startFrame + 1L, total)))
                        null -> Unit
                    }
                },
                onPan = {}, onZoom = { _, _ -> }, onTap = { onSelect() }
            )
        }
        Text("${clockMillis(part.range.startFrame, rate)} – ${clockMillis(part.range.endFrame, rate)}", color = Synth.muted, style = MaterialTheme.typography.bodySmall)
    }
}

/** Acts on the selected part. */
@Composable
private fun PartKey(text: String, description: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.heightIn(min = 56.dp).clip(RoundedCornerShape(14.dp)).background(Synth.surface).border(1.dp, Synth.line, RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) { Text(text, color = if (enabled) Synth.text else Synth.text.copy(alpha = 0.35f), fontWeight = FontWeight.SemiBold) }
}

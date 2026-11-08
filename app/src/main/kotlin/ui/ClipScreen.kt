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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kanatron.app.core.MultitrackRenderer
import com.kanatron.app.core.Peak
import com.kanatron.app.core.PeakExtractor
import com.kanatron.app.core.TrimEdge
import com.kanatron.app.core.TrimRange
import com.kanatron.app.core.firstClip
import com.kanatron.app.data.ClipPart
import com.kanatron.app.data.MAX_CLIP_PARTS
import com.kanatron.app.data.ProjectRepository
import com.kanatron.app.data.clipArrangement
import com.kanatron.app.playback.WavPlayer
import kotlin.math.roundToLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Parts of several recordings on one timeline, made into a new recording; the originals stay untouched.
 * Drag a part's body to slide it, its handles to trim it; where parts overlap they play together.
 */
@Composable
internal fun ClipScreen(
    parts: List<ClipPart>,
    repository: ProjectRepository,
    playbackAllowed: Boolean,
    saving: Boolean,
    error: String?,
    onPart: (index: Int, ClipPart) -> Unit,
    onAdd: () -> Unit,
    onRemove: (index: Int) -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val rate = parts.first().project.sampleRate
    val arrangement = remember(parts) { clipArrangement(parts) }
    val timelineEnd = arrangement.totalFrames
    // A save renders the clip itself, so the preview stops competing with it.
    val preview = producePlaybackRender(repository, PREVIEW_DEBOUNCE_MILLIS, parts, saving) { if (parts.size >= 2 && !saving) arrangement else null }
    var player by remember(preview) { mutableStateOf<WavPlayer?>(null) }
    DisposableEffect(preview) { onDispose { player?.release() } }
    val playing = player?.state?.collectAsState()?.value?.isPlaying == true
    val overview by produceState(emptyList<Peak>(), preview) {
        value = preview?.getOrNull()?.let { withContext(Dispatchers.IO) { runCatching { PeakExtractor.extract(it, 512, { !isActive }) }.getOrNull() } }.orEmpty()
    }
    if (!playbackAllowed && playing) player?.pause()
    // The screen is new after the picker, so the part just added starts out selected.
    var selection by rememberSaveable { mutableIntStateOf(parts.lastIndex) }
    val selected = selection.coerceIn(0, parts.lastIndex)
    var mixOpen by rememberSaveable { mutableStateOf(false) }
    // Every lane shares one view of the timeline, with room to slide a part past the end.
    var viewStart by rememberSaveable(parts.size) { mutableStateOf(0L) }
    var viewEnd by rememberSaveable(parts.size) { mutableStateOf(maxOf(timelineEnd * 5 / 4, rate.toLong())) }
    fun zoom(factor: Double, anchor: Long) {
        val span = ((viewEnd - viewStart) * factor).roundToLong().coerceIn(rate / 100L, maxOf(timelineEnd * 2, rate.toLong()))
        // An anchor off screen is brought to the middle; one on screen stays under the same spot.
        viewStart = if (anchor !in viewStart..viewEnd) (anchor - span / 2).coerceAtLeast(0L)
        else (anchor - (anchor - viewStart) * span / (viewEnd - viewStart).coerceAtLeast(1L)).coerceAtLeast(0L)
        viewEnd = viewStart + span
    }
    fun change(index: Int, part: ClipPart) {
        player?.stop()
        selection = index
        onPart(index, part)
    }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SynthTopBar("Clip", { player?.stop(); onClose() }, backLabel = "Edit") {
            // Zooming holds the selected part's start in place, so the part being worked on stays in view.
            Key("−", { zoom(2.0, parts[selected].positionFrames) }, Modifier.width(48.dp), description = "Zoom out", height = KeyHeight.Small)
            Key("+", { zoom(0.5, parts[selected].positionFrames) }, Modifier.width(48.dp), description = "Zoom in", height = KeyHeight.Small)
        }
        // The whole timeline with the view outlined: a tap moves the view, since dragging a lane moves its part.
        val span = arrangement.tracks.single().clips[selected].let { it.positionFrames until it.positionFrames + MultitrackRenderer.clipFrameCount(it, rate) }
        Overview(overview, timelineEnd, viewStart, viewEnd, TrimRange(span.first, span.last + 1), Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Synth.surface)) { center ->
            val width = viewEnd - viewStart
            viewStart = (center - width / 2).coerceAtLeast(0L)
            viewEnd = viewStart + width
        }
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            parts.forEachIndexed { index, part ->
                Lane(
                    index, part, rate, viewStart, viewEnd, repository, index == selected,
                    onSelect = { selection = index },
                    onChange = { change(index, it) },
                    onZoom = ::zoom
                )
            }
        }
        NudgeKeys(rate, "part ${selected + 1}") { frames ->
            val part = parts[selected]
            change(selected, part.copy(positionFrames = (part.positionFrames + frames).coerceAtLeast(0L)))
        }
        val notice = when {
            saving -> "Saving…"
            error != null -> error
            preview?.exceptionOrNull() != null -> preview.exceptionOrNull()?.message
            parts.size < 2 -> "Add another recording to clip it with this one."
            parts.any { it.project.sampleRate != rate } -> "Every part plays at $rate Hz, the first part's rate."
            else -> "Saving makes a new recording. The originals stay as they are."
        }
        Text(notice.orEmpty(), color = if (error != null) MaterialTheme.colorScheme.error else Synth.night, style = MaterialTheme.typography.bodySmall)
        if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val file = preview?.getOrNull()
            val idle = !saving
            Key(
                if (playing) "Pause" else "Play",
                {
                    val wav = file ?: return@Key
                    val current = player ?: WavPlayer(context, wav).also { player = it }
                    if (playing) current.pause() else { current.seek(0L); current.play() }
                },
                Modifier.weight(1f), icon = if (playing) SynthIcon.Pause else SynthIcon.Play, face = Synth.cyan, height = KeyHeight.Transport,
                enabled = file != null && idle && playbackAllowed, description = if (playing) "Pause preview" else "Play the clip"
            )
            Key("Add", { player?.stop(); onAdd() }, Modifier.weight(1f), icon = SynthIcon.New, height = KeyHeight.Transport, enabled = idle && parts.size < MAX_CLIP_PARTS, description = "Add a recording")
            Key("Remove", { player?.stop(); onRemove(selected) }, Modifier.weight(1f), icon = SynthIcon.Delete, height = KeyHeight.Transport, enabled = idle && parts.size > 1, description = "Remove part ${selected + 1}")
            Key(
                "Mix", { mixOpen = true }, Modifier.weight(1f), height = KeyHeight.Transport, enabled = idle,
                sublabel = mixSummary(parts[selected].levelSteps, parts[selected].eq), description = "Level and EQ of part ${selected + 1}, ${mixSummary(parts[selected].levelSteps, parts[selected].eq)}"
            )
        }
        Key("Save as new", { player?.stop(); onSave() }, Modifier.fillMaxWidth(), icon = SynthIcon.Save, face = Synth.yellow, enabled = parts.size >= 2 && !saving)
    }

    if (mixOpen) {
        val part = parts[selected]
        MixSheet("${selected + 1} · ${part.project.name}", part.levelSteps, part.eq, onChange = { level, eq -> change(selected, part.copy(levelSteps = level, eq = eq)) }, onDismiss = { mixOpen = false })
    }
}

/**
 * One part over the shared timeline view. The waveform shows the part's whole source placed where it plays,
 * so the lane maps the timeline view into source frames and maps drags back.
 */
@Composable
private fun Lane(
    index: Int,
    part: ClipPart,
    rate: Int,
    viewStart: Long,
    viewEnd: Long,
    repository: ProjectRepository,
    selected: Boolean,
    onSelect: () -> Unit,
    onChange: (ClipPart) -> Unit,
    onZoom: (factor: Double, anchor: Long) -> Unit
) {
    val source = part.project.firstClip.source
    val file = remember(part.project.id) { repository.audioFile(part.project) }
    val overview by produceState(emptyList<Peak>(), file) {
        value = withContext(Dispatchers.IO) { runCatching { PeakExtractor.extractCached(file, 2_048, { !isActive }) }.getOrNull() }.orEmpty()
    }
    var dragging by remember { mutableStateOf<TrimEdge?>(null) }
    // Source frames per timeline frame: parts at another rate play converted to the first part's.
    val ratio = source.sampleRate.toDouble() / rate
    fun toSource(timeline: Long) = part.range.startFrame + ((timeline - part.positionFrames) * ratio).roundToLong()
    fun toTimeline(sourceFrame: Long) = part.positionFrames + ((sourceFrame - part.range.startFrame) / ratio).roundToLong()
    val shape = RoundedCornerShape(22.dp)
    val view = remember { BringIntoViewRequester() }
    // The shared keys act on the selected part, so it must be on screen.
    LaunchedEffect(selected) { if (selected) view.bringIntoView() }
    SynthPanel(
        Modifier.bringIntoViewRequester(view).border(2.dp, if (selected) Synth.yellow else Color.Transparent, shape).clip(shape)
            .clickable(role = Role.Tab, onClick = onSelect)
            .semantics { this.selected = selected }
    ) {
        Text(
            "${index + 1} · ${part.project.name} · ${mixSummary(part.levelSteps, part.eq)}",
            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        Box(Modifier.fillMaxWidth().height(96.dp)) {
            Waveform(
                file, overview, source.totalFrames, source.sampleRate, toSource(viewStart), toSource(viewEnd), part.range, null, dragging,
                onEdgeDrag = { edge, frame ->
                    onSelect()
                    dragging = edge
                    when (edge) {
                        // A trimmed start keeps the rest of the part where it plays, and never before zero.
                        TrimEdge.Start -> {
                            val start = frame.coerceIn(part.range.startFrame - (part.positionFrames * ratio).roundToLong(), part.range.endFrame - 1L).coerceAtLeast(0L)
                            onChange(part.copy(range = TrimRange(start, part.range.endFrame), positionFrames = toTimeline(start).coerceAtLeast(0L)))
                        }
                        TrimEdge.End -> onChange(part.copy(range = TrimRange(part.range.startFrame, frame.coerceIn(part.range.startFrame + 1L, source.totalFrames))))
                        null -> Unit
                    }
                },
                // Dragging the body slides the part along the timeline.
                onPan = { frames ->
                    onSelect()
                    onChange(part.copy(positionFrames = (part.positionFrames - (frames / ratio).roundToLong()).coerceAtLeast(0L)))
                },
                onZoom = { factor, anchor -> onZoom(factor, toTimeline(anchor)) },
                onTap = { onSelect() }
            )
        }
        Text(
            "at ${clockMillis(part.positionFrames, rate)} · ${clockMillis(part.range.startFrame, source.sampleRate)} – ${clockMillis(part.range.endFrame, source.sampleRate)}",
            color = Synth.muted, style = MaterialTheme.typography.bodySmall
        )
    }
}

package com.kanatron.app.ui

import android.content.res.Configuration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kanatron.app.core.Cuts
import com.kanatron.app.core.Peak
import com.kanatron.app.core.PeakExtractor
import com.kanatron.app.core.Project
import com.kanatron.app.core.RecordingCore
import com.kanatron.app.core.TrimEdge
import com.kanatron.app.core.TrimRange
import com.kanatron.app.data.ProjectRepository
import com.kanatron.app.playback.WavPlayer
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToLong

/** One recording's pending cuts: select a range on the waveform, then keep it or delete it. */
@Composable
internal fun EditScreen(
    project: Project,
    pieces: List<TrimRange>,
    canUndo: Boolean,
    repository: ProjectRepository,
    playbackAllowed: Boolean,
    saving: Boolean,
    error: String?,
    onCut: (List<TrimRange>) -> Unit,
    onUndo: () -> Unit,
    onSave: (asNew: Boolean) -> Unit,
    onImport: () -> Unit,
    onSplice: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val rate = project.sampleRate
    val length = Cuts.length(pieces)
    // The edited timeline plays from a cached render; an uncut recording plays its own WAV.
    val timeline by produceState<Result<File>?>(null, project, pieces) {
        value = null
        val cancelled = AtomicBoolean(false)
        val render = async(Dispatchers.IO) { runCatching { repository.playbackFile(Cuts.arrangement(project, pieces), cancelled) } }
        try {
            value = render.await()
        } finally {
            cancelled.set(true)
        }
    }
    val file = timeline?.getOrNull()
    val overview by produceState(emptyList<Peak>(), file) {
        value = emptyList()
        value = file?.let { withContext(Dispatchers.IO) { runCatching { PeakExtractor.extract(it, OVERVIEW_BUCKETS, { !isActive }) }.getOrNull() } }.orEmpty()
    }

    var selectionStart by rememberSaveable(project.id, length) { mutableStateOf(0L) }
    var selectionEnd by rememberSaveable(project.id, length) { mutableStateOf(length) }
    var activeEdge by rememberSaveable(project.id) { mutableStateOf(TrimEdge.Start) }
    var viewStart by rememberSaveable(project.id, length) { mutableStateOf(0L) }
    var viewEnd by rememberSaveable(project.id, length) { mutableStateOf(length) }
    var dragging by remember { mutableStateOf<TrimEdge?>(null) }
    var askSave by rememberSaveable { mutableStateOf(false) }
    val selection = TrimRange(selectionStart, selectionEnd)

    var player by remember(file) { mutableStateOf<WavPlayer?>(null) }
    DisposableEffect(file) { onDispose { player?.release() } }
    val playerState = player?.state?.collectAsState()?.value
    val playing = playerState?.isPlaying == true
    var playhead by remember(file) { mutableStateOf<Long?>(null) }
    LaunchedEffect(playerState?.positionFrames, playing) { if (playing) playhead = playerState?.positionFrames }
    if (!playbackAllowed && playing) player?.pause()

    fun setEdge(edge: TrimEdge, frame: Long) {
        if (edge == TrimEdge.Start) selectionStart = frame.coerceIn(0L, selectionEnd - 1L)
        else selectionEnd = frame.coerceIn(selectionStart + 1L, length)
    }
    fun zoom(factor: Double, anchor: Long) {
        val span = ((viewEnd - viewStart) * factor).roundToLong().coerceIn(MIN_VIEW_FRAMES.coerceAtMost(length), length)
        val start = (anchor - (anchor - viewStart) * span / (viewEnd - viewStart).coerceAtLeast(1L)).coerceIn(0L, length - span)
        viewStart = start
        viewEnd = start + span
    }
    fun preview() {
        val wav = file ?: return
        val current = player ?: WavPlayer(context, wav).also { player = it }
        if (playing) return current.pause()
        val from = playhead?.takeIf { it in selectionStart until selectionEnd } ?: selectionStart
        current.seek(from)
        current.play(selectionEnd)
    }
    fun cut(next: List<TrimRange>) {
        player?.stop()
        onCut(next)
    }

    val wholeSelected = selectionStart == 0L && selectionEnd == length
    val busyText = when {
        saving -> "Saving…"
        timeline == null -> "Preparing audio…"
        else -> null
    }
    val waveform: @Composable ColumnScope.() -> Unit = {
        SynthTopBar("Edit", onBack, backLabel = "Library") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // A splice joins saved audio, so pending cuts are saved first.
                IconSquare(SynthIcon.Splice, if (canUndo) "Save your cuts before splicing" else "Splice with another recording", { player?.stop(); onSplice() }, enabled = !saving && !canUndo)
                IconSquare(SynthIcon.Import, "Import a recording to edit", { player?.stop(); onImport() }, enabled = !saving)
            }
        }
        Text(
            buildString {
                append(project.name)
                if (project.draft) append(" · unsaved take")
            },
            color = Synth.night, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        SynthPanel(Modifier.fillMaxWidth().weight(1f)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Overview(overview, length, viewStart, viewEnd, selection, Modifier.weight(1f)) { center ->
                    val span = viewEnd - viewStart
                    viewStart = (center - span / 2).coerceIn(0L, length - span)
                    viewEnd = viewStart + span
                }
                val anchor = if (activeEdge == TrimEdge.Start) selectionStart else selectionEnd
                SmallKey("−", "Zoom out") { zoom(4.0, anchor) }
                SmallKey("+", "Zoom in") { zoom(0.25, anchor) }
            }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                Waveform(
                    file, overview, length, rate, viewStart, viewEnd, selection, playhead, dragging,
                    onEdgeDrag = { edge, frame ->
                        dragging = edge
                        if (edge != null) {
                            activeEdge = edge
                            setEdge(edge, frame)
                        }
                    },
                    onPan = { frames ->
                        val span = viewEnd - viewStart
                        viewStart = (viewStart + frames).coerceIn(0L, length - span)
                        viewEnd = viewStart + span
                    },
                    onZoom = ::zoom,
                    onTap = { frame ->
                        playhead = frame
                        if (playing) player?.seek(frame)
                    }
                )
            }
            (busyText ?: error ?: timeline?.exceptionOrNull()?.message ?: playerState?.error)?.let {
                Text(it, color = if (busyText != null) Synth.muted else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
    val controls: @Composable ColumnScope.() -> Unit = {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (edge in TrimEdge.entries) {
                val frame = if (edge == TrimEdge.Start) selectionStart else selectionEnd
                EdgeChip(edge, clockMillis(frame, rate), edge == activeEdge, Modifier.weight(1f)) { activeEdge = edge }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((label, unit, frames) in nudges(rate)) {
                NudgeKey(label, unit, Modifier.weight(1f)) {
                    val current = if (activeEdge == TrimEdge.Start) selectionStart else selectionEnd
                    setEdge(activeEdge, current + frames)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val idle = !saving && file != null
            Tile(
                if (playing) SynthIcon.Pause else SynthIcon.Play, if (playing) "Pause" else "Preview", ::preview, Modifier.weight(1f),
                enabled = idle && playbackAllowed, description = if (playing) "Pause preview" else "Preview selection", minHeight = ACTION_HEIGHT
            )
            Tile(SynthIcon.Trim, "Keep", { cut(Cuts.keep(pieces, selection)) }, Modifier.weight(1f), enabled = idle && !wholeSelected, description = "Keep only the selection", minHeight = ACTION_HEIGHT)
            Tile(SynthIcon.Delete, "Delete", { cut(Cuts.delete(pieces, selection)) }, Modifier.weight(1f), enabled = idle && !wholeSelected, description = "Delete the selection", minHeight = ACTION_HEIGHT)
            Tile(SynthIcon.Undo, "Undo", { player?.stop(); onUndo() }, Modifier.weight(1f), enabled = !saving && canUndo, description = "Undo last cut", minHeight = ACTION_HEIGHT)
        }
        BigButton(
            if (project.draft) "Save to Library" else "Save",
            { if (project.draft) onSave(false) else askSave = true },
            Modifier.fillMaxWidth(), primary = true, enabled = !saving && (project.draft || canUndo)
        )
    }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp), content = waveform)
                Column(Modifier.width(360.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Bottom), content = controls)
            }
        } else {
            Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), content = waveform)
            controls()
        }
    }

    if (askSave) AlertDialog(
        onDismissRequest = { askSave = false },
        title = { Text("Save edits") },
        text = { Text("Save replaces this recording's audio. Save as new keeps it and adds a new recording named with today's date and time.") },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton({ askSave = false; player?.stop(); onSave(false) }) { Text("Save") }
                TextButton({ askSave = false; player?.stop(); onSave(true) }) { Text("Save as new") }
                TextButton({ askSave = false }) { Text("Cancel") }
            }
        }
    )
}

/** The whole timeline with the visible window outlined; a tap moves the window there. */
@Composable
private fun Overview(peaks: List<Peak>, length: Long, viewStart: Long, viewEnd: Long, selection: TrimRange, modifier: Modifier, onCenter: (Long) -> Unit) {
    Canvas(
        modifier.height(40.dp)
            .pointerInput(length) { detectTapGestures { onCenter((it.x / size.width * length).roundToLong()) } }
            .semantics { contentDescription = "Whole recording overview" }
    ) {
        fun x(frame: Long) = frame.toFloat() / length * size.width
        drawRect(Synth.cyan.copy(alpha = 0.18f), Offset(x(selection.startFrame), 0f), Size(x(selection.endFrame) - x(selection.startFrame), size.height))
        drawPeaks(peaks, 0L, length, 0L, length, Synth.muted)
        drawRect(Synth.yellow, Offset(x(viewStart), 0f), Size((x(viewEnd) - x(viewStart)).coerceAtLeast(2f), size.height), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
    }
}

/**
 * Drag a handle to move it (a loupe shows its exact samples), drag elsewhere to scroll,
 * pinch to zoom, tap to place the playhead.
 */
@Composable
internal fun Waveform(
    file: File?,
    overview: List<Peak>,
    length: Long,
    rate: Int,
    viewStart: Long,
    viewEnd: Long,
    selection: TrimRange,
    playhead: Long?,
    dragging: TrimEdge?,
    onEdgeDrag: (TrimEdge?, Long) -> Unit,
    onPan: (Long) -> Unit,
    onZoom: (Double, Long) -> Unit,
    onTap: (Long) -> Unit
) {
    var widthPx by remember { mutableStateOf(1) }
    // Close-up peaks are read straight from the file once the view settles; the overview fills in meanwhile.
    val detail by produceState<Pair<LongRange, List<Peak>>?>(null, file, viewStart, viewEnd, widthPx) {
        if (file == null || viewEnd - viewStart >= length) return@produceState
        delay(120)
        value = withContext(Dispatchers.IO) {
            runCatching { viewStart..viewEnd to PeakExtractor.extract(file, widthPx, { !isActive }, viewStart, viewEnd) }.getOrNull()
        }
    }
    val loupeCenter = dragging?.let { if (it == TrimEdge.Start) selection.startFrame else selection.endFrame }
    val loupe by produceState<List<Peak>?>(null, file, loupeCenter) {
        if (file == null || loupeCenter == null) { value = null; return@produceState }
        val half = rate / LOUPE_DIVISOR
        val start = (loupeCenter - half).coerceAtLeast(0L)
        val end = (loupeCenter + half).coerceAtMost(length)
        value = withContext(Dispatchers.IO) { runCatching { PeakExtractor.extract(file, (end - start).toInt().coerceIn(1, 480), { false }, start, end) }.getOrNull() }
    }
    val current by rememberUpdatedState(Triple(viewStart, viewEnd, selection))
    val edgeDrag by rememberUpdatedState(onEdgeDrag)
    val pan by rememberUpdatedState(onPan)
    val zoom by rememberUpdatedState(onZoom)
    val tap by rememberUpdatedState(onTap)
    Box(Modifier.fillMaxSize()) {
        Canvas(
            Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(Synth.night)
                .pointerInput(length) {
                    // A touch on a handle becomes a drag only once it moves; a still touch places the playhead.
                    val slop = 28.dp.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val (start, end, range) = current
                        fun frameAt(x: Float) = (start + x / size.width * (end - start)).roundToLong().coerceIn(0L, length)
                        fun xOf(frame: Long) = (frame - start).toFloat() / (end - start) * size.width
                        val nearStart = abs(down.position.x - xOf(range.startFrame)) <= slop
                        val nearEnd = abs(down.position.x - xOf(range.endFrame)) <= slop
                        var edge: TrimEdge? = null
                        var travelled = 0f
                        do {
                            val event = awaitPointerEvent()
                            val change = event.changes.first()
                            val dx = change.position.x - change.previousPosition.x
                            travelled += abs(dx)
                            if (edge == null && (nearStart || nearEnd) && travelled > viewConfiguration.touchSlop) {
                                // Overlapping handles split by direction: right drags the end, left the start.
                                edge = if (nearStart && nearEnd) (if (dx > 0) TrimEdge.End else TrimEdge.Start)
                                else if (nearStart) TrimEdge.Start else TrimEdge.End
                            }
                            if (edge != null) {
                                edgeDrag(edge, frameAt(change.position.x))
                            } else if (!nearStart && !nearEnd) {
                                val (viewFrom, viewTo) = current
                                val framesPerPx = (viewTo - viewFrom).toDouble() / size.width
                                val factor = event.calculateZoom()
                                val shift = event.calculatePan().x
                                if (factor != 1f) zoom(1.0 / factor, (viewFrom + event.calculateCentroid().x * framesPerPx).roundToLong())
                                else if (shift != 0f && travelled > viewConfiguration.touchSlop) pan((-shift * framesPerPx).roundToLong())
                            }
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        } while (event.changes.any { it.pressed })
                        if (edge != null) edgeDrag(null, 0L)
                        else if (travelled <= viewConfiguration.touchSlop) tap(frameAt(down.position.x))
                    }
                }
                .semantics {
                    contentDescription = "Waveform"
                    stateDescription = "Selection ${clockMillis(selection.startFrame, rate)} to ${clockMillis(selection.endFrame, rate)}"
                }
        ) {
            widthPx = size.width.toInt().coerceAtLeast(1)
            fun x(frame: Long) = (frame - viewStart).toFloat() / (viewEnd - viewStart) * size.width
            drawRect(Synth.cyan.copy(alpha = 0.12f), Offset(x(selection.startFrame), 0f), Size(x(selection.endFrame) - x(selection.startFrame), size.height))
            val close = detail?.takeIf { it.first == viewStart..viewEnd }
            if (close != null) drawPeaks(close.second, viewStart, viewEnd, viewStart, viewEnd, Synth.cyan)
            else drawPeaks(overview, 0L, length, viewStart, viewEnd, Synth.cyan)
            for (edge in TrimEdge.entries) {
                val at = x(if (edge == TrimEdge.Start) selection.startFrame else selection.endFrame)
                val color = if (edge == dragging) Synth.yellow else Synth.pink
                drawLine(color, Offset(at, 0f), Offset(at, size.height), 2.dp.toPx())
                drawCircle(color, 8.dp.toPx(), Offset(at, size.height - 10.dp.toPx()))
            }
            playhead?.let { drawLine(Synth.text, Offset(x(it), 0f), Offset(x(it), size.height), 1.dp.toPx()) }
        }
        loupe?.let { peaks ->
            Canvas(
                Modifier.align(Alignment.TopCenter).padding(top = 8.dp).size(200.dp, 72.dp)
                    .clip(RoundedCornerShape(10.dp)).background(Synth.surfaceHi).border(1.dp, Synth.yellow, RoundedCornerShape(10.dp))
            ) {
                drawPeaks(peaks, 0L, peaks.size.toLong(), 0L, peaks.size.toLong(), Synth.cyan)
                drawLine(Synth.yellow, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), 1.dp.toPx())
            }
        }
    }
}

/** Peaks spread evenly over frames [from, to), drawn into the view [viewStart, viewEnd). */
private fun DrawScope.drawPeaks(peaks: List<Peak>, from: Long, to: Long, viewStart: Long, viewEnd: Long, color: Color) {
    if (peaks.isEmpty() || to <= from || viewEnd <= viewStart) {
        drawLine(color.copy(alpha = 0.4f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1f)
        return
    }
    val middle = size.height / 2
    val bucketFrames = (to - from).toDouble() / peaks.size
    val first = ((viewStart - from) / bucketFrames).toInt().coerceIn(0, peaks.size - 1)
    val last = ((viewEnd - from) / bucketFrames).toInt().coerceIn(0, peaks.size - 1)
    val pxPerFrame = size.width / (viewEnd - viewStart).toFloat()
    for (index in first..last) {
        val left = ((from + index * bucketFrames - viewStart) * pxPerFrame).toFloat()
        val width = (bucketFrames * pxPerFrame).toFloat().coerceAtLeast(1f)
        val top = middle - peaks[index].max / 32768f * middle
        val bottom = middle - peaks[index].min / 32768f * middle
        drawRect(color, Offset(left, top), Size(width, (bottom - top).coerceAtLeast(1f)))
    }
}

@Composable
private fun EdgeChip(edge: TrimEdge, time: String, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.heightIn(min = 56.dp).clip(RoundedCornerShape(14.dp))
            .background(if (active) Synth.violet else Synth.surface)
            .border(1.dp, if (active) Synth.violet else Synth.line, RoundedCornerShape(14.dp))
            .clickable(role = Role.Tab, onClick = onClick)
            .semantics { selected = active; contentDescription = "${edge.name} $time" }
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        val tint = if (active) Synth.night else Synth.muted
        Text(edge.name, color = tint, style = MaterialTheme.typography.labelMedium)
        Text(time, color = if (active) Synth.night else Synth.text, fontFamily = FontFamily.Monospace, fontSize = 16.sp)
    }
}

@Composable
private fun NudgeKey(label: String, unit: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.heightIn(min = 52.dp).clip(RoundedCornerShape(12.dp)).background(Synth.surface).border(1.dp, Synth.line, RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = "Move edge $label $unit" },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
    ) {
        Text(label, color = Synth.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, textAlign = TextAlign.Center)
        Text(unit, color = Synth.muted, fontSize = 11.sp)
    }
}

@Composable
private fun SmallKey(label: String, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(Synth.surfaceHi.copy(alpha = 0.85f))
            .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) { Text(label, color = Synth.text, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
}

private fun nudges(rate: Int) = listOf(
    Triple("−100", "ms", -rate / 10L), Triple("−10", "ms", -rate / 100L), Triple("−1", "frame", -1L),
    Triple("+1", "frame", 1L), Triple("+10", "ms", rate / 100L), Triple("+100", "ms", rate / 10L)
)

internal fun clockMillis(frames: Long, rate: Int) = RecordingCore.formatClock(RecordingCore.elapsedMillis(frames, rate), withMillis = true, hours = false)

private const val OVERVIEW_BUCKETS = 2_048
private val ACTION_HEIGHT = 76.dp
/** The tightest zoom still shows a few dozen samples per handle drag. */
private const val MIN_VIEW_FRAMES = 64L
/** The loupe spans ±1/200 s (5 ms) around the dragged handle. */
private const val LOUPE_DIVISOR = 200

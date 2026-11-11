package com.kanatron.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kanatron.app.core.AudioEdits
import com.kanatron.app.core.AudioSection
import com.kanatron.app.core.MusicalGrid
import com.kanatron.app.core.firstClip
import com.kanatron.app.core.Eq
import com.kanatron.app.core.Peak
import com.kanatron.app.core.PeakExtractor
import com.kanatron.app.core.Project
import com.kanatron.app.core.RecordingCore
import com.kanatron.app.core.TrimRange
import com.kanatron.app.data.EditSession
import com.kanatron.app.data.ProjectRepository
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToLong

@Composable
internal fun EditScreen(
    project: Project,
    session: EditSession,
    repository: ProjectRepository,
    playbackAllowed: Boolean,
    saving: Boolean,
    error: String?,
    onEdit: (List<AudioSection>) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onExtract: (List<AudioSection>) -> Unit,
    onDeleteTrack: () -> Unit,
    onSave: (Boolean) -> Unit,
    onClip: () -> Unit
) {
    val context = LocalContext.current
    val rate = project.sampleRate
    val length = AudioEdits.length(session.sections)
    val selection = rememberWaveformSelection(project.id + project.firstClip.source.wav, length)
    val range = selection.range(length)
    var cursor by rememberSaveable(project.id) { mutableStateOf(0L) }
    var previewSections by remember(project.id) { mutableStateOf<List<AudioSection>?>(null) }
    var resumePreview by remember { mutableStateOf(false) }
    var selectionPlayback by remember { mutableStateOf(false) }
    var localError by remember(project.id) { mutableStateOf<String?>(null) }
    var actionsOpen by remember { mutableStateOf(false) }
    var askSave by rememberSaveable { mutableStateOf(false) }
    var askDelete by rememberSaveable { mutableStateOf(false) }
    var loopOpen by rememberSaveable { mutableStateOf(false) }
    var mixOpen by remember { mutableStateOf(false) }
    var mixLevel by rememberSaveable { mutableStateOf(0) }
    var mixEq by remember { mutableStateOf(Eq.FLAT) }
    var mixedSettings by remember { mutableStateOf(false) }
    var bypass by rememberSaveable { mutableStateOf(false) }
    val renderedSections = previewSections ?: session.sections
    val timeline = producePlaybackRender(repository, PREVIEW_DEBOUNCE_MILLIS, project, renderedSections) {
        AudioEdits.arrangement(project, renderedSections)
    }
    val file = timeline?.getOrNull()
    val overview by produceState(emptyList<Peak>(), file) {
        value = file?.let { withContext(Dispatchers.IO) { runCatching { PeakExtractor.extract(it, OVERVIEW_BUCKETS, { !isActive }) }.getOrNull() } }.orEmpty()
    }
    val loaded = rememberWavPlayer(context, file)
    val player = loaded?.getOrNull()
    val playerState = player?.state?.collectAsState()?.value
    val playing = playerState?.isPlaying == true
    LaunchedEffect(playerState?.positionFrames, playing) {
        if (playing) cursor = playerState.positionFrames
        else if (playerState != null && playerState.positionFrames == playerState.totalFrames) cursor = length
    }
    LaunchedEffect(player, playbackAllowed, saving) {
        if (player != null && resumePreview && playbackAllowed && !saving) {
            player.seek(cursor.coerceIn(0, length - 1))
            player.play(if (selectionPlayback) range.endFrame else length)
            resumePreview = false
        }
        if (!playbackAllowed || saving) { player?.pause(); resumePreview = false }
    }
    fun play(selectedOnly: Boolean) {
        if (playing) { player.pause(); resumePreview = false; return }
        val current = player ?: return
        selectionPlayback = selectedOnly
        cursor = if (selectedOnly) range.startFrame else cursor.takeIf { it < length } ?: 0L
        current.seek(cursor)
        current.play(if (selectedOnly) range.endFrame else length)
    }
    fun stageMix() {
        val next = AudioEdits.mix(session.sections, range, mixLevel, if (bypass) Eq.FLAT else mixEq)
        if (next != renderedSections) {
            resumePreview = resumePreview || playing
            player?.pause()
        }
        previewSections = next
    }
    fun applySections(next: List<AudioSection>) {
        player?.pause()
        resumePreview = false
        localError = null
        onEdit(next)
    }
    fun attempt(action: () -> Unit) {
        runCatching(action).onFailure { localError = it.message ?: "Could not apply this edit." }
    }
    val ready = selection.confirmed && !saving && previewSections == null
    val transport: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Key(if (playing) "Pause" else "Play", { play(false) }, Modifier.weight(1f), icon = if (playing) SynthIcon.Pause else SynthIcon.Play,
                face = Synth.cyan, enabled = player != null && playbackAllowed && !saving, height = KeyHeight.Small)
            Key("Preview selection", { play(true) }, Modifier.weight(1f), enabled = player != null && playbackAllowed && selection.confirmed && !saving, height = KeyHeight.Small)
        }
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp, vertical = 12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SynthTopBar("Edit") {
            Key("Clip", { player?.stop(); onClip() }, height = KeyHeight.Small, icon = SynthIcon.Clip, enabled = !saving && !session.pending,
                description = if (session.pending) "Save edits before combining recordings" else "Combine recordings")
        }
        Text(project.name + if (project.draft) " · unsaved take" else "", color = Synth.night, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        WaveformSelectionEditor(project.name, file, overview, length, rate, selection, cursor,
            onSeek = { cursor = it; player?.seek(it) }, enabled = !saving && !mixOpen, grid = project.grid, transport = transport)
        transport()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Key("EQ / Level", {
                attempt {
                    val settings = AudioEdits.slice(session.sections, range).map { it.levelSteps to it.eq }.distinct()
                    mixedSettings = settings.size > 1
                    mixLevel = settings.singleOrNull()?.first ?: 0
                    mixEq = settings.singleOrNull()?.second ?: Eq.FLAT
                    bypass = false
                    mixOpen = true
                }
            }, Modifier.weight(1f), enabled = ready, height = KeyHeight.Small)
            Key("Loop", { loopOpen = true }, Modifier.weight(1f), enabled = ready, height = KeyHeight.Small)
            Box(Modifier.weight(1f)) {
                Key("Actions", { actionsOpen = true }, Modifier.fillMaxWidth(), enabled = ready, height = KeyHeight.Small)
                DropdownMenu(actionsOpen, { actionsOpen = false }) {
                    DropdownMenuItem(text = { Text("New track from selection") }, onClick = {
                        actionsOpen = false
                        attempt { player?.pause(); onExtract(AudioEdits.slice(session.sections, range)) }
                    })
                    DropdownMenuItem(text = { Text("Delete selection") }, onClick = {
                        actionsOpen = false
                        if (range.frameCount == length) askDelete = true
                        else attempt {
                            applySections(AudioEdits.delete(session.sections, range))
                            val nextLength = length - range.frameCount
                            val join = range.startFrame.coerceAtMost(nextLength - 1)
                            cursor = (join - rate / 2).coerceAtLeast(0)
                            selection.select(TrimRange(cursor, minOf(nextLength, join + rate / 2 + 1)))
                            selection.confirmed = true
                            selection.fit(TrimRange((join - rate * 2).coerceAtLeast(0), minOf(nextLength, join + rate * 2 + 1)))
                        }
                    })
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Key("Undo", { player?.pause(); onUndo() }, Modifier.weight(1f), enabled = !saving && session.undo.isNotEmpty(), height = KeyHeight.Small)
            Key("Redo", { player?.pause(); onRedo() }, Modifier.weight(1f), enabled = !saving && session.redo.isNotEmpty(), height = KeyHeight.Small)
            Key(if (project.draft) "Save take" else "Save", { if (project.draft) onSave(false) else askSave = true }, Modifier.weight(1f),
                icon = SynthIcon.Save, face = Synth.yellow, enabled = !saving && (project.draft || session.pending), height = KeyHeight.Small)
        }
        val status = when {
            saving -> "Saving…"
            localError != null -> localError
            error != null -> error
            timeline?.exceptionOrNull() != null -> timeline.exceptionOrNull()?.message
            loaded?.exceptionOrNull() != null -> "This preview could not be opened."
            playerState?.error != null -> playerState.error
            timeline == null -> "Preparing audio…"
            overview.any { it.max >= Short.MAX_VALUE || it.min <= Short.MIN_VALUE } -> "Output reaches its limit. Lower the selected section's level in EQ."
            session.pending -> "Edits ready to save."
            else -> null
        }
        status?.let { Text(it, color = Synth.night, style = MaterialTheme.typography.bodySmall) }
        if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
    if (mixOpen) MixSheet(
        "${clockMillis(range.startFrame, rate)} – ${clockMillis(range.endFrame, rate)}", mixLevel, mixEq,
        mixed = mixedSettings, bypass = bypass, playing = playing, previewReady = player != null && playbackAllowed,
        onChange = { level, eq -> attempt { mixLevel = level; mixEq = eq; mixedSettings = false; stageMix() } },
        onBypass = { attempt { bypass = !bypass; stageMix() } },
        onPreview = { play(true) },
        onApply = {
            attempt {
                val next = AudioEdits.mix(session.sections, range, mixLevel, mixEq)
                applySections(next)
                previewSections = null
                mixOpen = false
            }
        },
        onDismiss = { player?.pause(); resumePreview = false; previewSections = null; mixOpen = false },
        status = localError ?: if (overview.any { it.max >= Short.MAX_VALUE || it.min <= Short.MIN_VALUE }) "Output reaches its limit. Lower Level." else null
    )
    if (loopOpen) LoopDialog(range.frameCount, length, rate, session.sections, range,
        onApply = { times -> attempt {
            applySections(AudioEdits.repeat(session.sections, range, times))
            selection.select(TrimRange(range.startFrame, range.startFrame + range.frameCount * times))
            selection.confirmed = true
            selection.selecting = false
            selection.fit(selection.range(length + range.frameCount * (times - 1)))
            cursor = range.startFrame
            loopOpen = false
        } }, onDismiss = { loopOpen = false })
    if (askDelete) AlertDialog(onDismissRequest = { askDelete = false }, title = { Text("Delete recording?") },
        text = { Text("The whole track is selected. Delete ${project.name} from the Library?") }, confirmButton = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Key("Cancel", { askDelete = false }, Modifier.weight(1f), height = KeyHeight.Small)
                Key("Delete", { player?.stop(); askDelete = false; onDeleteTrack() }, Modifier.weight(1f), face = Synth.pink, height = KeyHeight.Small)
            }
        })
    if (askSave) AlertDialog(onDismissRequest = { askSave = false }, title = { Text("Save edits") },
        text = { Text("Save as new adds a recording. Replace original writes these edits into this recording's audio.") }, confirmButton = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Key("Save as new", { askSave = false; player?.stop(); onSave(true) }, Modifier.weight(1f), face = Synth.yellow, height = KeyHeight.Small)
                    Key("Replace original", { askSave = false; player?.stop(); onSave(false) }, Modifier.weight(1f), height = KeyHeight.Small)
                }
                Key("Cancel", { askSave = false }, Modifier.fillMaxWidth(), height = KeyHeight.Small)
            }
        })
}

@Composable
private fun LoopDialog(sectionFrames: Long, totalFrames: Long, rate: Int, sections: List<AudioSection>, range: TrimRange, onApply: (Int) -> Unit, onDismiss: () -> Unit) {
    var count by rememberSaveable { mutableStateOf("2") }
    val times = count.toIntOrNull()
    val result = times?.let { runCatching { AudioEdits.repeat(sections, range, it) } }
    val valid = result?.isSuccess == true
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Loop selection") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(count, { count = it }, label = { Text("Total plays, including the original") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = !valid)
            Text("The rest of the recording follows the final repetition.")
            if (valid) Text("New duration · ${clockMillis(totalFrames + sectionFrames * (times - 1), rate)}")
            else Text(result?.exceptionOrNull()?.message ?: "Choose between 1 and 64 plays.", color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Key("Cancel", onDismiss, Modifier.weight(1f), height = KeyHeight.Small)
            Key("Apply", { onApply(times!!) }, Modifier.weight(1f), face = Synth.cyan, enabled = valid, height = KeyHeight.Small)
        }
    })
}

/** The whole timeline with the visible window outlined; a tap moves the window there. */
@Composable
internal fun Overview(peaks: List<Peak>, length: Long, viewStart: Long, viewEnd: Long, selection: TrimRange, modifier: Modifier, height: androidx.compose.ui.unit.Dp = 40.dp, onLongPress: (() -> Unit)? = null, onCenter: (Long) -> Unit) {
    val center by rememberUpdatedState(onCenter)
    val expand by rememberUpdatedState(onLongPress)
    Canvas(
        modifier.height(height)
            .pointerInput(length, onLongPress != null) {
                detectTapGestures(onLongPress = if (onLongPress == null) null else { _ -> expand?.invoke() },
                    onTap = { center((it.x / size.width * length).roundToLong()) })
            }
            .semantics { contentDescription = "Whole recording overview"; stateDescription = "Viewing frames $viewStart to $viewEnd" }
    ) {
        fun x(frame: Long) = frame.toFloat() / length * size.width
        drawRect(Synth.cyan.copy(alpha = 0.18f), Offset(x(selection.startFrame), 0f), Size(x(selection.endFrame) - x(selection.startFrame), size.height))
        drawPeaks(peaks, 0L, length, 0L, length, Synth.muted)
        drawRect(Synth.yellow, Offset(x(viewStart), 0f), Size((x(viewEnd) - x(viewStart)).coerceAtLeast(2f), size.height), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
    }
}

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
    onZoom: (Double, Long) -> Unit,
    onTap: (Long) -> Unit,
    selecting: Boolean = false,
    onSelection: (TrimRange) -> Unit = {},
    onLongPress: (() -> Unit)? = null,
    grid: MusicalGrid = MusicalGrid(),
    gridOffset: Long = 0L,
    handlesEnabled: Boolean = true
) {
    var widthPx by remember { mutableStateOf(1) }
    // Close-up peaks are read straight from the file once the view settles; the overview fills in meanwhile.
    val detail by produceState<Pair<LongRange, List<Peak>>?>(null, file, viewStart, viewEnd, widthPx) {
        if (file == null || viewEnd - viewStart >= length) return@produceState
        delay(120)
        // A Clip lane's view can reach past its source; only the audio inside it is read, at the view's density.
        val from = viewStart.coerceIn(0L, length)
        val to = viewEnd.coerceIn(from, length)
        val buckets = (widthPx * (to - from) / (viewEnd - viewStart)).toInt().coerceAtLeast(1)
        value = withContext(Dispatchers.IO) {
            runCatching { viewStart..viewEnd to PeakExtractor.extract(file, buckets, { !isActive }, from, to) }.getOrNull()
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
    val zoom by rememberUpdatedState(onZoom)
    val tap by rememberUpdatedState(onTap)
    val selectRange by rememberUpdatedState(onSelection)
    val longPress by rememberUpdatedState(onLongPress)
    Box(Modifier.fillMaxSize()) {
        Canvas(
            Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(Synth.night)
                .pointerInput(length, selecting, handlesEnabled) {
                    val slop = 28.dp.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val (start, end, range) = current
                        fun frameAt(x: Float) = (start + x / size.width * (end - start)).roundToLong().coerceIn(0L, length)
                        fun xOf(frame: Long) = (frame - start).toFloat() / (end - start) * size.width
                        val zone = minOf(slop, abs(xOf(range.endFrame) - xOf(range.startFrame)) / 3f)
                        val nearStart = handlesEnabled && abs(down.position.x - xOf(range.startFrame)) <= zone
                        val nearEnd = handlesEnabled && abs(down.position.x - xOf(range.endFrame)) <= zone
                        val anchor = frameAt(down.position.x).coerceAtMost(length - 1)
                        var edge: TrimEdge? = null
                        var moved = false
                        var transformed = false
                        var held = false
                        var finished = false
                        while (!finished) {
                            val remaining = down.uptimeMillis + viewConfiguration.longPressTimeoutMillis - android.os.SystemClock.uptimeMillis()
                            val event = if (!moved && !transformed && longPress != null) {
                                withTimeoutOrNull(remaining.coerceAtLeast(1L)) { awaitPointerEvent() }
                            } else awaitPointerEvent()
                            if (event == null) {
                                held = true
                                longPress?.invoke()
                                waitForUpOrCancellation()
                                break
                            }
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.isConsumed) { transformed = true; break }
                            val dx = change.position.x - down.position.x
                            val dy = change.position.y - down.position.y
                            moved = moved || kotlin.math.hypot(dx, dy) > viewConfiguration.touchSlop
                            var handled = false
                            if (event.changes.count { it.pressed } > 1) {
                                transformed = true
                                handled = true
                                val (viewFrom, viewTo) = current
                                val framesPerPx = (viewTo - viewFrom).toDouble() / size.width
                                val factor = event.calculateZoom()
                                if (factor != 1f) zoom(1.0 / factor, (viewFrom + event.calculateCentroid().x * framesPerPx).roundToLong())
                            } else if (moved && !transformed && abs(dx) >= abs(dy)) {
                                val frame = frameAt(change.position.x)
                                if (selecting) {
                                    handled = true
                                    selectRange(TrimRange(minOf(anchor, frame).coerceAtMost(length - 1), maxOf(anchor + 1, frame).coerceAtMost(length)))
                                } else if (nearStart || nearEnd) {
                                    handled = true
                                    if (edge == null) edge = if (nearStart && nearEnd) (if (dx > 0) TrimEdge.End else TrimEdge.Start) else if (nearStart) TrimEdge.Start else TrimEdge.End
                                    edgeDrag(edge, frame)
                                }
                            }
                            if (handled) event.changes.forEach { if (it.positionChanged()) it.consume() }
                            finished = event.changes.none { it.pressed }
                        }
                        if (edge != null) edgeDrag(null, 0L)
                        if (!moved && !transformed && !held) tap(frameAt(down.position.x))
                    }
                }
                .semantics {
                    contentDescription = "Waveform"
                    stateDescription = "Selection ${clockMillis(selection.startFrame, rate)} to ${clockMillis(selection.endFrame, rate)}"
                    if (onLongPress != null) onLongClick("Open full-screen waveform") { onLongPress(); true }
                }
        ) {
            widthPx = size.width.toInt().coerceAtLeast(1)
            fun x(frame: Long) = (frame - viewStart).toFloat() / (viewEnd - viewStart) * size.width
            drawMusicalGrid(grid, rate, viewStart + gridOffset, viewEnd + gridOffset)
            drawRect(Synth.cyan.copy(alpha = 0.12f), Offset(x(selection.startFrame), 0f), Size(x(selection.endFrame) - x(selection.startFrame), size.height))
            val close = detail?.takeIf { it.first == viewStart..viewEnd }
            if (close != null) drawPeaks(close.second, viewStart.coerceIn(0L, length), viewEnd.coerceIn(0L, length), viewStart, viewEnd, Synth.cyan)
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
internal fun DrawScope.drawPeaks(peaks: List<Peak>, from: Long, to: Long, viewStart: Long, viewEnd: Long, color: Color) {
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

internal fun clockMillis(frames: Long, rate: Int) = RecordingCore.formatClock(RecordingCore.elapsedMillis(frames, rate), withMillis = true, hours = false)

private const val OVERVIEW_BUCKETS = 2_048
/** Long enough for a burst of taps on a preview control to settle into one render. */
internal const val PREVIEW_DEBOUNCE_MILLIS = 250L

/**
 * Renders the [arrangement] to a playable file; a new key cancels the render in flight. Taps that change the
 * arrangement in quick succession settle within [debounceMillis] before a render starts. Null while rendering or
 * while there is nothing to render.
 */
@Composable
internal fun producePlaybackRender(
    repository: ProjectRepository,
    debounceMillis: Long,
    vararg keys: Any?,
    arrangement: () -> Project?
): Result<File>? {
    val render by produceState<Result<File>?>(null, *keys) {
        value = null
        val built = runCatching(arrangement)
        val project = built.getOrElse { value = Result.failure(it); return@produceState } ?: return@produceState
        delay(debounceMillis)
        val cancelled = AtomicBoolean(false)
        val job = async(Dispatchers.IO) { runCatching { repository.playbackFile(project, cancelled) } }
        try {
            value = job.await()
        } finally {
            cancelled.set(true)
        }
    }
    return render
}

/** The tightest zoom still shows a few dozen samples per handle drag. */
private const val MIN_VIEW_FRAMES = 64L
/** The loupe spans ±1/200 s (5 ms) around the dragged handle. */
private const val LOUPE_DIVISOR = 200

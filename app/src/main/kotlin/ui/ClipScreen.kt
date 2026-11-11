package com.kanatron.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kanatron.app.core.MultitrackRenderer
import com.kanatron.app.core.MusicalGrid
import com.kanatron.app.core.Peak
import com.kanatron.app.core.PeakExtractor
import com.kanatron.app.core.ProjectClip
import com.kanatron.app.core.TrimRange
import com.kanatron.app.core.firstClip
import com.kanatron.app.data.ClipPart
import com.kanatron.app.data.MAX_CLIP_PARTS
import com.kanatron.app.data.ProjectRepository
import com.kanatron.app.data.clipArrangement
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

@Composable
internal fun ClipScreen(
    parts: List<ClipPart>,
    sampleRate: Int,
    grid: MusicalGrid,
    canUndo: Boolean,
    canRedo: Boolean,
    repository: ProjectRepository,
    playbackAllowed: Boolean,
    saving: Boolean,
    error: String?,
    onParts: (List<ClipPart>) -> Unit,
    onGrid: (MusicalGrid) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onAdd: () -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit
) {
    if (parts.isEmpty()) return
    val context = LocalContext.current
    val rate = sampleRate
    val arrangement = remember(parts, rate, grid) { clipArrangement(parts, rate, grid) }
    val length = arrangement.totalFrames
    val preview = producePlaybackRender(repository, PREVIEW_DEBOUNCE_MILLIS, parts, saving) { if (saving) null else arrangement }
    val loaded = rememberWavPlayer(context, preview?.getOrNull())
    val player = loaded?.getOrNull()
    val playerState = player?.state?.collectAsState()?.value
    val playing = playerState?.isPlaying == true
    var cursor by rememberSaveable { mutableStateOf(0L) }
    LaunchedEffect(playerState?.positionFrames, playing) {
        if (playing) cursor = playerState.positionFrames
        else if (playerState != null && playerState.positionFrames == playerState.totalFrames) cursor = length
    }
    LaunchedEffect(playbackAllowed, saving) { if (!playbackAllowed || saving) player?.pause() }
    val overview by produceState(emptyList<Peak>(), preview) {
        value = preview?.getOrNull()?.let { withContext(Dispatchers.IO) { runCatching { PeakExtractor.extract(it, 1_024, { !isActive }) }.getOrNull() } }.orEmpty()
    }
    var selectedId by rememberSaveable { mutableStateOf(parts.last().id) }
    val selected = parts.firstOrNull { it.id == selectedId } ?: parts.last()
    var viewStart by rememberSaveable { mutableStateOf(0L) }
    var viewEnd by rememberSaveable { mutableStateOf(maxOf(length * 5 / 4, rate.toLong())) }
    var dragParts by remember { mutableStateOf<List<ClipPart>?>(null) }
    var menu by remember { mutableStateOf(false) }
    var gridOpen by rememberSaveable { mutableStateOf(false) }
    var nudgeOpen by rememberSaveable { mutableStateOf(false) }
    var editorId by rememberSaveable { mutableStateOf<String?>(null) }
    var sectionRange by remember { mutableStateOf<Pair<String, TrimRange>?>(null) }
    var localError by remember { mutableStateOf<String?>(null) }
    val displayParts = dragParts ?: parts
    fun duration(part: ClipPart) = MultitrackRenderer.clipFrameCount(ProjectClip(part.id, part.project.firstClip.source, 0, part.range), rate)
    fun zoom(factor: Double, anchor: Long) {
        val oldSpan = viewEnd - viewStart
        val span = (oldSpan * factor).roundToLong().coerceIn(64L, maxOf(length * 4, rate.toLong()))
        viewStart = (anchor - (anchor - viewStart) * span / oldSpan).coerceAtLeast(0)
        viewEnd = viewStart + span
    }
    fun commit(next: List<ClipPart>) {
        if (next.size > MAX_CLIP_PARTS) { localError = "Up to $MAX_CLIP_PARTS clips fit in one arrangement."; return }
        player?.pause()
        localError = null
        dragParts = null
        onParts(next)
    }
    fun replace(part: ClipPart) = commit(parts.map { if (it.id == part.id) part else it })
    fun split() {
        val sourceRate = selected.project.firstClip.source.sampleRate
        val selectedRange = sectionRange?.takeIf { it.first == selected.id }?.second
        val sourceCursor = selected.range.startFrame + ((cursor - selected.positionFrames) * sourceRate.toDouble() / rate).roundToLong()
        val cuts = (selectedRange?.let { listOf(it.startFrame, it.endFrame) } ?: listOf(sourceCursor))
            .filter { it > selected.range.startFrame && it < selected.range.endFrame }.distinct().sorted()
        if (cuts.isEmpty()) { localError = "Place the cursor inside a clip, or confirm a smaller section."; return }
        val edges = listOf(selected.range.startFrame) + cuts + selected.range.endFrame
        val split = edges.zipWithNext().mapIndexed { index, (start, end) ->
            selected.copy(id = if (index == 0) selected.id else UUID.randomUUID().toString(), range = TrimRange(start, end),
                positionFrames = selected.positionFrames + ((start - selected.range.startFrame) * rate.toDouble() / sourceRate).roundToLong())
        }
        commit(parts.flatMap { if (it.id == selected.id) split else listOf(it) })
        sectionRange = null
    }
    val transport: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Key(if (playing) "Pause" else "Play", {
                if (playing) player.pause() else { player?.seek(cursor.takeIf { it < length } ?: 0L); player?.play() }
            }, Modifier.weight(1f), face = Synth.cyan, enabled = player != null && playbackAllowed && !saving, height = KeyHeight.Small)
            Key("From start", { cursor = 0; player?.seek(0); if (!playing && playbackAllowed) player?.play() }, Modifier.weight(1f), enabled = player != null && playbackAllowed && !saving, height = KeyHeight.Small)
        }
    }
    val timelineView: @Composable ColumnScope.() -> Unit = {
        val selectedSpan = TrimRange(selected.positionFrames, selected.positionFrames + duration(selected))
        SynthPanel(contentPadding = 8.dp) {
            Overview(overview, length, viewStart, viewEnd, selectedSpan, Modifier.fillMaxWidth(), height = 24.dp, onLongPress = { editorId = selected.id }) { center ->
                val span = viewEnd - viewStart
                viewStart = (center - span / 2).coerceAtLeast(0)
                viewEnd = viewStart + span
            }
            Text("${clockMillis(viewStart, rate)} – ${clockMillis(viewEnd, rate)}" + if (grid.enabled) " · ${grid.barMillis / 1_000.0}s/bar" else "", color = Synth.muted, style = MaterialTheme.typography.bodySmall)
        }
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            displayParts.groupBy { it.laneId }.values.forEachIndexed { index, lane ->
                key(lane.first().laneId) {
                    ClipLane(index + 1, lane, selected.id, rate, viewStart, viewEnd, cursor, grid, parts, sectionRange, repository, enabled = !saving,
                        onSelect = { id, frame -> selectedId = id; cursor = grid.snapped(frame, rate); player?.seek(cursor) },
                        onDrag = { part -> dragParts = parts.map { if (it.id == part.id) part else it } },
                        onCommit = { dragParts?.let(::commit) },
                        onCancel = { dragParts = null },
                        onExpand = { id -> selectedId = id; editorId = id },
                        onZoom = ::zoom)
                }
            }
        }
    }
    val clipControls: @Composable () -> Unit = {
        Text("${selected.project.name} · at ${clockMillis(selected.positionFrames, rate)} · ${clockMillis(duration(selected), rate)} long",
            color = Synth.night, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Key("Select section", { editorId = selected.id }, Modifier.weight(1f), enabled = !saving, height = KeyHeight.Small)
            Key("Split", ::split, Modifier.weight(1f), enabled = !saving, height = KeyHeight.Small)
            Box(Modifier.weight(1f)) {
                Key("More", { menu = true }, Modifier.fillMaxWidth(), enabled = !saving, height = KeyHeight.Small)
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Trim to confirmed section") }, enabled = sectionRange?.first == selected.id, onClick = {
                        menu = false
                        val range = sectionRange?.second ?: return@DropdownMenuItem
                        val position = selected.positionFrames + ((range.startFrame - selected.range.startFrame) * rate.toDouble() / selected.project.sampleRate).roundToLong()
                        replace(selected.copy(range = range, positionFrames = position.coerceAtLeast(0)))
                        sectionRange = null
                    })
                    DropdownMenuItem(text = { Text("Duplicate clip") }, enabled = parts.size < MAX_CLIP_PARTS, onClick = {
                        menu = false
                        val copy = selected.copy(id = UUID.randomUUID().toString(), positionFrames = selected.positionFrames + duration(selected))
                        commit(parts + copy)
                        selectedId = copy.id
                    })
                    DropdownMenuItem(text = { Text("Remove clip") }, enabled = parts.size > 1, onClick = { menu = false; commit(parts.filterNot { it.id == selected.id }); sectionRange = null })
                    DropdownMenuItem(text = { Text("Fit all tracks") }, onClick = { menu = false; viewStart = 0; viewEnd = maxOf(length * 11 / 10, rate.toLong()) })
                    DropdownMenuItem(text = { Text("Nudge position…") }, onClick = { menu = false; nudgeOpen = true })
                }
            }
        }
        transport()
        val notice = localError ?: error ?: preview?.exceptionOrNull()?.message ?: loaded?.exceptionOrNull()?.let { "This preview could not be opened." } ?: playerState?.error
            ?: if (saving) "Saving…" else if (preview == null) "Preparing audio…"
            else if (overview.any { it.max >= Short.MAX_VALUE || it.min <= Short.MIN_VALUE }) "Output reaches its limit. Save as new lowers the whole clip to fit."
            else sectionRange?.takeIf { it.first == selected.id }?.let { "Section confirmed. Split it into a clip, or trim using More." }
        notice?.let { Text(it, color = Synth.night, style = MaterialTheme.typography.bodySmall) }
        if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Key("Undo", { player?.pause(); onUndo() }, Modifier.weight(1f), enabled = canUndo && !saving, height = KeyHeight.Small)
            Key("Redo", { player?.pause(); onRedo() }, Modifier.weight(1f), enabled = canRedo && !saving, height = KeyHeight.Small)
            Key("Save as new", { player?.stop(); onSave() }, Modifier.weight(1f), face = Synth.yellow, enabled = !saving, height = KeyHeight.Small)
        }
    }
    val landscape = LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Key("Edit", { player?.stop(); onClose() }, Modifier.width(72.dp), height = KeyHeight.Small)
            Text("CLIP", style = MaterialTheme.typography.headlineMedium, color = Synth.night, modifier = Modifier.weight(1f))
            Key("Add", { player?.pause(); onAdd() }, Modifier.width(72.dp), enabled = !saving && parts.size < MAX_CLIP_PARTS, height = KeyHeight.Small, description = "Add track")
            Key("Grid", { gridOpen = true }, Modifier.width(72.dp), selected = grid.enabled, enabled = !saving, height = KeyHeight.Small)
        }
        if (landscape) Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp), content = timelineView)
            Column(Modifier.width(300.dp).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                clipControls()
            }
        } else {
            timelineView()
            clipControls()
        }
    }
    if (gridOpen) GridDialog(grid, onGrid, { gridOpen = false })
    if (nudgeOpen) NudgeDialog(rate, listOf("Position"), position = { selected.positionFrames }, onMove = { _, delta -> replace(selected.copy(positionFrames = (selected.positionFrames + delta).coerceAtLeast(0))) }, onDismiss = { nudgeOpen = false })
    editorId?.let { id ->
        val part = parts.firstOrNull { it.id == id }
        if (part != null) key(id) {
            val source = part.project.firstClip.source
            val file = remember(part.project.id) { repository.audioFile(part.project) }
            val peaks by produceState(emptyList<Peak>(), file) {
                value = withContext(Dispatchers.IO) { runCatching { PeakExtractor.extractCached(file, 2_048, { !isActive }) }.getOrDefault(emptyList()) }
            }
            val selection = rememberWaveformSelection(part.id, source.totalFrames, sectionRange?.takeIf { it.first == part.id }?.second ?: part.range)
            WaveformSelectionEditor(part.project.name, file, peaks, source.totalFrames, source.sampleRate, selection,
                cursor = part.range.startFrame + ((cursor - part.positionFrames) * source.sampleRate.toDouble() / rate).roundToLong(),
                onSeek = { frame -> cursor = (part.positionFrames + ((frame - part.range.startFrame) * rate.toDouble() / source.sampleRate).roundToLong()).coerceAtLeast(0); player?.seek(cursor) },
                enabled = !saving, grid = grid, gridOffset = (part.positionFrames * source.sampleRate.toDouble() / rate).roundToLong() - part.range.startFrame, startExpanded = true,
                onClose = {
                    if (selection.confirmed) sectionRange = part.id to selection.range(source.totalFrames)
                    editorId = null
                },
                onPositionNudge = { frames -> replace(part.copy(positionFrames = (part.positionFrames + (frames * rate.toDouble() / source.sampleRate).roundToLong()).coerceAtLeast(0))) },
                transport = transport)
        }
    }
}

@Composable
private fun ClipLane(
    number: Int,
    parts: List<ClipPart>,
    selectedId: String,
    rate: Int,
    viewStart: Long,
    viewEnd: Long,
    cursor: Long,
    grid: MusicalGrid,
    allParts: List<ClipPart>,
    confirmedSection: Pair<String, TrimRange>?,
    repository: ProjectRepository,
    enabled: Boolean,
    onSelect: (String, Long) -> Unit,
    onDrag: (ClipPart) -> Unit,
    onCommit: () -> Unit,
    onCancel: () -> Unit,
    onExpand: (String) -> Unit,
    onZoom: (Double, Long) -> Unit
) {
    val project = parts.first().project
    val source = project.firstClip.source
    val file = remember(project.id) { repository.audioFile(project) }
    val peaks by produceState(emptyList<Peak>(), file) {
        value = withContext(Dispatchers.IO) { runCatching { PeakExtractor.extractCached(file, 2_048, { !isActive }) }.getOrDefault(emptyList()) }
    }
    fun frames(part: ClipPart) = MultitrackRenderer.clipFrameCount(ProjectClip(part.id, source, 0, part.range), rate)
    val latestParts by rememberUpdatedState(parts)
    val select by rememberUpdatedState(onSelect)
    val drag by rememberUpdatedState(onDrag)
    val commit by rememberUpdatedState(onCommit)
    val cancel by rememberUpdatedState(onCancel)
    val expand by rememberUpdatedState(onExpand)
    val zoom by rememberUpdatedState(onZoom)
    val currentView by rememberUpdatedState(viewStart to viewEnd)
    val boundaries by rememberUpdatedState(allParts.flatMap { listOf(it.positionFrames, it.positionFrames + MultitrackRenderer.clipFrameCount(ProjectClip(it.id, it.project.firstClip.source, 0, it.range), rate)) })
    val view = remember { BringIntoViewRequester() }
    val active = parts.any { it.id == selectedId }
    LaunchedEffect(active) { if (active) view.bringIntoView() }
    SynthPanel(Modifier.bringIntoViewRequester(view), contentPadding = 10.dp) {
        Text("$number · ${project.name}", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
        Canvas(Modifier.fillMaxWidth().height(72.dp).clip(RoundedCornerShape(12.dp)).background(Synth.night)
            .semantics {
                contentDescription = "Track $number, ${project.name}, ${parts.size} clips"
                onLongClick("Open full-screen waveform") { expand(selectedId.takeIf { id -> parts.any { it.id == id } } ?: parts.first().id); true }
            }
            .pointerInput(enabled, source, rate, grid) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val (from, to) = currentView
                    val perPixel = (to - from).toDouble() / size.width
                    val frame = (from + down.position.x * perPixel).roundToLong().coerceAtLeast(0)
                    val hit = latestParts.lastOrNull { frame in it.positionFrames..(it.positionFrames + frames(it)) }
                    if (hit == null) {
                        select(selectedId, frame)
                        waitForUpOrCancellation()
                        return@awaitEachGesture
                    }
                    val left = (hit.positionFrames - from) / perPixel
                    val right = (hit.positionFrames + frames(hit) - from) / perPixel
                    val zone = minOf(24.dp.toPx().toDouble(), (right - left) / 3)
                    val startHandle = abs(down.position.x - left) < zone
                    val endHandle = abs(down.position.x - right) < zone
                    select(hit.id, frame)
                    var moved = false
                    var transformed = false
                    var held = false
                    var done = false
                    fun snap(value: Long): Long {
                        if (!grid.snap) return value.coerceAtLeast(0)
                        val beat = grid.snapped(value, rate)
                        val nearest = boundaries.minByOrNull { abs(it - value) }
                        return if (nearest != null && abs(nearest - value) <= perPixel * 8.dp.toPx() && (!grid.enabled || abs(nearest - value) < abs(beat - value))) nearest else beat
                    }
                    while (!done) {
                        val remaining = down.uptimeMillis + viewConfiguration.longPressTimeoutMillis - android.os.SystemClock.uptimeMillis()
                        val event = if (!moved && !transformed) withTimeoutOrNull(remaining.coerceAtLeast(1)) { awaitPointerEvent() } else awaitPointerEvent()
                        if (event == null) {
                            held = true
                            expand(hit.id)
                            waitForUpOrCancellation()
                            break
                        }
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.isConsumed) { transformed = true; break }
                        val dx = change.position.x - down.position.x
                        moved = moved || abs(dx) > viewConfiguration.touchSlop
                        if (event.changes.count { it.pressed } > 1) {
                            transformed = true
                            val factor = event.calculateZoom()
                            if (factor != 1f) zoom(1.0 / factor, (from + event.calculateCentroid().x * perPixel).roundToLong())
                        } else if (moved && !transformed) {
                            val target = snap(hit.positionFrames + (dx * perPixel).roundToLong())
                            val sourceDelta = ((target - hit.positionFrames) * source.sampleRate.toDouble() / rate).roundToLong()
                            val next = when {
                                startHandle -> {
                                    val start = (hit.range.startFrame + sourceDelta).coerceIn(maxOf(0L, hit.range.startFrame - (hit.positionFrames * source.sampleRate.toDouble() / rate).roundToLong()), hit.range.endFrame - 1)
                                    hit.copy(range = TrimRange(start, hit.range.endFrame), positionFrames = (hit.positionFrames + ((start - hit.range.startFrame) * rate.toDouble() / source.sampleRate).roundToLong()).coerceAtLeast(0))
                                }
                                endHandle -> {
                                    val endPosition = snap(hit.positionFrames + frames(hit) + (dx * perPixel).roundToLong())
                                    val end = (hit.range.startFrame + ((endPosition - hit.positionFrames) * source.sampleRate.toDouble() / rate).roundToLong()).coerceIn(hit.range.startFrame + 1, source.totalFrames)
                                    hit.copy(range = TrimRange(hit.range.startFrame, end))
                                }
                                else -> hit.copy(positionFrames = target)
                            }
                            drag(next)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                        done = event.changes.none { it.pressed }
                    }
                    if (moved && !transformed && !held) commit() else cancel()
                }
            }
        ) {
            drawMusicalGrid(grid, rate, viewStart, viewEnd)
            val width = (viewEnd - viewStart).toDouble()
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Synth.text.toArgb(); textSize = 11.dp.toPx() }
            for ((index, part) in parts.withIndex()) {
                val x = ((part.positionFrames - viewStart) / width * size.width).toFloat()
                val end = ((part.positionFrames + frames(part) - viewStart) / width * size.width).toFloat()
                if (end < 0 || x > size.width) continue
                val selected = part.id == selectedId
                clipRect(maxOf(0f, x), 20.dp.toPx(), minOf(size.width, end), size.height) {
                    drawRect(if (selected) Synth.violet.copy(alpha = 0.2f) else Synth.cyan.copy(alpha = 0.08f), Offset(x, 20.dp.toPx()), Size((end - x).coerceAtLeast(1f), size.height - 20.dp.toPx()))
                    if (peaks.isNotEmpty()) {
                        val bucketFrames = source.totalFrames.toDouble() / peaks.size
                        val first = (part.range.startFrame / bucketFrames).toInt().coerceIn(0, peaks.lastIndex)
                        val last = (part.range.endFrame / bucketFrames).toInt().coerceIn(first, peaks.lastIndex)
                        val middle = size.height * 0.62f
                        val amplitude = size.height * 0.30f
                        for (bucket in first..last) {
                            val position = part.positionFrames + (bucket * bucketFrames - part.range.startFrame) * rate / source.sampleRate
                            val left = ((position - viewStart) / width * size.width).toFloat()
                            val bucketWidth = (bucketFrames * rate / source.sampleRate / width * size.width).toFloat().coerceAtLeast(1f)
                            val peak = peaks[bucket]
                            drawRect(Synth.cyan, Offset(left, middle - peak.max / 32768f * amplitude), Size(bucketWidth, ((peak.max - peak.min) / 32768f * amplitude).coerceAtLeast(1f)))
                        }
                    }
                    drawContext.canvas.nativeCanvas.drawText("${index + 1}", maxOf(0f, x) + 6.dp.toPx(), 36.dp.toPx(), paint)
                    confirmedSection?.takeIf { it.first == part.id }?.second?.let { range ->
                        val start = part.positionFrames + (range.startFrame - part.range.startFrame) * rate.toDouble() / source.sampleRate
                        val finish = part.positionFrames + (range.endFrame - part.range.startFrame) * rate.toDouble() / source.sampleRate
                        val left = ((start - viewStart) / width * size.width).toFloat()
                        val right = ((finish - viewStart) / width * size.width).toFloat()
                        drawRect(Synth.cyan.copy(alpha = 0.18f), Offset(left, 20.dp.toPx()), Size(right - left, size.height - 20.dp.toPx()))
                        drawLine(Synth.cyan, Offset(left, 20.dp.toPx()), Offset(left, size.height), 2.dp.toPx())
                        drawLine(Synth.cyan, Offset(right, 20.dp.toPx()), Offset(right, size.height), 2.dp.toPx())
                    }
                }
                drawRect(if (selected) Synth.yellow else Synth.line, Offset(x, 20.dp.toPx()), Size((end - x).coerceAtLeast(1f), size.height - 20.dp.toPx()), style = Stroke(if (selected) 2.dp.toPx() else 1.dp.toPx()))
                if (selected) {
                    drawCircle(Synth.pink, 7.dp.toPx(), Offset(x, size.height - 10.dp.toPx()))
                    drawCircle(Synth.pink, 7.dp.toPx(), Offset(end, size.height - 10.dp.toPx()))
                }
            }
            val head = ((cursor - viewStart) / width * size.width).toFloat()
            drawLine(Synth.text, Offset(head, 0f), Offset(head, size.height), 1.dp.toPx())
        }
    }
}

package com.ottomusic.sketch.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ottomusic.sketch.core.ExportFilename
import com.ottomusic.sketch.core.MultitrackRenderer
import com.ottomusic.sketch.core.Project
import com.ottomusic.sketch.core.ProjectArrangementActions
import com.ottomusic.sketch.core.ProjectClip
import com.ottomusic.sketch.core.ProjectTrack
import com.ottomusic.sketch.core.RecordingCore
import com.ottomusic.sketch.core.TrimRange
import com.ottomusic.sketch.core.WavFormat
import com.ottomusic.sketch.core.firstClip
import com.ottomusic.sketch.data.ProjectRepository
import com.ottomusic.sketch.data.WavExporter
import com.ottomusic.sketch.playback.WavPlayer
import java.util.UUID
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class DragPreview(val clipId: String, val positionFrames: Long)

/** Creates or edits a persisted arrangement. Source selection is metadata-only; audio is opened lazily. */
@Composable
fun ArrangeScreen(
    projects: List<Project>,
    repository: ProjectRepository,
    initialProjectId: String?,
    onBack: () -> Unit,
    onSaved: (Project) -> Unit,
    onProjectChanged: (Project) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ordered = remember(projects) { projects.sortedByDescending(Project::createdAtEpochMillis) }
    var selectedIds by rememberSaveable(initialProjectId) { mutableStateOf(listOfNotNull(initialProjectId)) }
    var editingProjectId by rememberSaveable(initialProjectId) { mutableStateOf(initialProjectId ?: "") }
    var project by remember(initialProjectId) { mutableStateOf<Project?>(null) }
    var missingSources by remember { mutableStateOf(emptyList<String>()) }
    var loading by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var player by remember { mutableStateOf<WavPlayer?>(null) }

    LaunchedEffect(editingProjectId) {
        if (editingProjectId.isNotBlank()) {
            loading = true
            error = null
            try {
                val loaded = withContext(Dispatchers.IO) { repository.get(editingProjectId) }
                if (loaded != null) {
                    project = loaded
                    missingSources = withContext(Dispatchers.IO) { repository.missingSources(loaded) }
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                error = failure.message ?: "Could not open this arrangement."
            } finally {
                loading = false
            }
        } else {
            project = null
        }
    }

    fun stopPreview() {
        val current = player
        player = null
        current?.stop()
        current?.release()
    }

    fun persist(next: Project) {
        stopPreview()
        error = null
        busy = true
        scope.launch {
            try {
                val saved = withContext(Dispatchers.IO) { repository.saveEditState(next) }
                project = saved
                onProjectChanged(saved)
                missingSources = withContext(Dispatchers.IO) { repository.missingSources(saved) }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                error = failure.message ?: "Could not save this arrangement change."
            } finally {
                busy = false
            }
        }
    }

    fun createArrangement() {
        val sources = selectedIds.mapNotNull { id -> ordered.firstOrNull { it.id == id } }
        if (sources.size !in 2..Project.MAX_TRACKS) return
        loading = true
        error = null
        scope.launch {
            try {
                val created = withContext(Dispatchers.IO) {
                    repository.saveArrangement(repository.createArrangement("arr-${UUID.randomUUID()}", "${sources.first().name} arrangement", sources))
                }
                editingProjectId = created.id
                project = created
                onProjectChanged(created)
                missingSources = withContext(Dispatchers.IO) { repository.missingSources(created) }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                error = failure.message ?: "Could not create the arrangement."
            } finally {
                loading = false
            }
        }
    }

    fun preview() {
        val current = project ?: return
        if (missingSources.isNotEmpty()) return
        stopPreview()
        busy = true
        error = null
        scope.launch {
            try {
                val wav = withContext(Dispatchers.IO) { repository.playbackFile(current) }
                player = WavPlayer(context, wav).also { it.play() }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                error = failure.message ?: "Could not prepare the arrangement preview."
            } finally {
                busy = false
            }
        }
    }

    fun shareArrangement() {
        val current = project ?: return
        if (missingSources.isNotEmpty()) return
        stopPreview()
        busy = true
        error = null
        scope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    val selection = TrimRange(0L, current.totalFrames)
                    val name = ExportFilename.wav("${current.name}-mix", current.name)
                    val estimatedBytes = WavFormat.outputBytes(selection.frameCount, current.channels)
                    repository.exportFile(current.id, name, estimatedBytes) { target ->
                        repository.withTemporaryWav(current) { source ->
                            WavExporter.export(source, target, selection)
                        }
                    }
                }
                shareExport(context, file, "Share arrangement")
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                error = exportFailureMessage(failure, "Could not prepare this arrangement for sharing.")
            } finally {
                busy = false
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { player?.release() }
    }

    val currentPlayer = player
    val isPlaying = currentPlayer?.state?.collectAsState()?.value?.isPlaying == true
    val current = project
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Leaving mid-save would refresh the library before the write lands.
        BackHandler(enabled = busy) {}
        SynthTopBar(
            title = if (current == null) "Arrange" else "Arrangement",
            onBack = { if (!busy) { stopPreview(); if (current != null) onSaved(current) else onBack() } },
            action = if (current != null) ({
                androidx.compose.material3.TextButton(enabled = !busy, onClick = { stopPreview(); onSaved(current) }) { Text("Done") }
            }) else null
        )

        if (loading) {
            androidx.compose.material3.CircularProgressIndicator()
            Text("Opening arrangement…")
        } else if (current == null) {
            Text("Choose 2 to ${Project.MAX_TRACKS} recordings. Each starts at frame 0; drag a lane or enter an exact frame to place it.")
            SynthPanel {
                ordered.forEach { source ->
                    val selected = source.id in selectedIds
                    val available = runCatching { repository.missingSources(source).isEmpty() }.getOrDefault(false)
                    FilterChip(
                        selected = selected,
                        enabled = available && (selected || selectedIds.size < Project.MAX_TRACKS),
                        onClick = {
                            val ids = selectedIds.toMutableList()
                            if (selected) ids.remove(source.id) else ids.add(source.id)
                            selectedIds = ids.toList()
                        },
                        label = {
                            Text(
                                if (available) source.name else "${source.name} · missing WAV",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    )
                }
            }
            BigButton(
                text = if (loading) "Creating…" else "Create arrangement",
                onClick = ::createArrangement,
                primary = true,
                enabled = !loading && selectedIds.size in 2..Project.MAX_TRACKS
            )
        } else {
            Text("${current.sampleRate} Hz · ${current.channels} channel${if (current.channels == 1) "" else "s"} · ${RecordingCore.formatClock(RecordingCore.elapsedMillis(current.totalFrames, current.sampleRate), withMillis = true, hours = false)}")
            if (missingSources.isNotEmpty()) {
                Text("Missing or changed source WAV: ${missingSources.joinToString()}. The project is saved; preview and sharing are paused until it is restored.", color = MaterialTheme.colorScheme.error)
            }
            SynthPanel {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BigButton(
                        text = if (isPlaying) "Stop preview" else "Preview",
                        onClick = { if (isPlaying) stopPreview() else preview() },
                        enabled = !busy && missingSources.isEmpty(),
                        modifier = Modifier.weight(1f)
                    )
                    BigButton(
                        text = if (busy) "Working…" else "Undo",
                        onClick = { ProjectArrangementActions.undo(current)?.let(::persist) },
                        enabled = !busy && current.undoHistory.isNotEmpty(),
                        modifier = Modifier.weight(1f)
                    )
                }
                if (current.tracks.isEmpty()) Text("This arrangement has no tracks.")
            }

            current.tracks.forEach { track ->
                TrackLane(
                    project = current,
                    track = track,
                    busy = busy,
                    onChange = ::persist
                )
            }

            if (current.tracks.size < Project.MAX_TRACKS) {
                val existingSources = current.tracks.flatMap(ProjectTrack::clips).map { it.source.wav }.toSet()
                val availableSources = ordered.filter { source ->
                    source.firstClip.source.wav !in existingSources &&
                        runCatching { repository.audioFile(source).isFile }.getOrDefault(false)
                }
                if (availableSources.isNotEmpty()) {
                    SynthPanel {
                        Text("Add a source track", style = MaterialTheme.typography.titleMedium)
                        availableSources.forEach { source ->
                            FilterChip(
                                selected = false,
                                onClick = {
                                    val trackId = "track-${UUID.randomUUID()}"
                                    val sourceClip = source.firstClip
                                    val clip = ProjectClip(
                                        id = "clip-${UUID.randomUUID()}",
                                        source = sourceClip.source,
                                        positionFrames = 0L,
                                        trim = sourceClip.trim
                                    )
                                    runCatching { ProjectArrangementActions.addTrack(current, ProjectTrack(trackId, source.name, listOf(clip))) }
                                        .onSuccess(::persist)
                                        .onFailure { error = it.message ?: "Could not add this source track." }
                                },
                                enabled = !busy,
                                label = { Text("Add ${source.name}", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            )
                        }
                    }
                }
            }

            SynthPanel {
                BigButton(
                    text = if (busy) "Preparing…" else "Share arrangement",
                    onClick = ::shareArrangement,
                    primary = true,
                    enabled = !busy && missingSources.isEmpty()
                )
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun TrackLane(project: Project, track: ProjectTrack, busy: Boolean, onChange: (Project) -> Unit) {
    var gainDraft by remember(track.id, track.gainPermille) { mutableStateOf(track.gainPermille / 1_000f) }
    var dragPreview by remember(track.id) { mutableStateOf<DragPreview?>(null) }
    val onChangeState = androidx.compose.runtime.rememberUpdatedState(onChange)
    val laneSpan = project.totalFrames.coerceAtLeast(1L)

    SynthPanel {
        Text(track.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Canvas(
            Modifier.fillMaxWidth().height(80.dp)
                .semantics { contentDescription = "${track.name} timeline lane; drag a clip to move it" }
                .pointerInput(project.id, track.id, laneSpan, busy) {
                    var selectedClip: ProjectClip? = null
                    var dragOriginX = 0f
                    var dragOriginFrame = 0L
                    if (!busy) detectDragGestures(
                        onDragStart = { point ->
                            val scale = size.width.toDouble() / laneSpan
                            selectedClip = track.clips.lastOrNull { clip ->
                                val startX = clip.positionFrames * scale
                                val clipFrames = MultitrackRenderer.clipFrameCount(clip, project.sampleRate)
                                val endX = (clip.positionFrames + clipFrames).coerceAtLeast(clip.positionFrames + 1L) * scale
                                point.x >= startX && point.x <= endX
                            }
                            selectedClip?.let { clip ->
                                dragOriginX = point.x
                                dragOriginFrame = clip.positionFrames
                                dragPreview = DragPreview(clip.id, clip.positionFrames)
                            }
                        },
                        onDrag = { change, _ ->
                            val clip = selectedClip ?: return@detectDragGestures
                            val framesPerPixel = laneSpan.toDouble() / size.width.toDouble()
                            val delta = ((change.position.x - dragOriginX) * framesPerPixel).roundToLong()
                            val endBound = Long.MAX_VALUE - MultitrackRenderer.clipFrameCount(clip, project.sampleRate)
                            val desired = runCatching { Math.addExact(dragOriginFrame, delta) }
                                .getOrDefault(if (delta >= 0L) Long.MAX_VALUE else 0L)
                            dragPreview = DragPreview(clip.id, desired.coerceIn(0L, endBound))
                            change.consume()
                        },
                        onDragEnd = {
                            val moving = dragPreview
                            val clip = selectedClip
                            if (moving != null && clip != null && moving.positionFrames != clip.positionFrames) {
                                val next = ProjectArrangementActions.setPosition(project, track.id, clip.id, moving.positionFrames)
                                onChangeState.value(next)
                            }
                            dragPreview = null
                            selectedClip = null
                        },
                        onDragCancel = { dragPreview = null; selectedClip = null }
                    )
                }
        ) {
            drawRect(Synth.surfaceHi)
            val scale = size.width / laneSpan.toFloat()
            val center = size.height / 2f
            drawLine(Synth.line, Offset(0f, center), Offset(size.width, center), strokeWidth = 1.dp.toPx())
            track.clips.forEachIndexed { index, clip ->
                val position = dragPreview?.takeIf { it.clipId == clip.id }?.positionFrames ?: clip.positionFrames
                val frames = MultitrackRenderer.clipFrameCount(clip, project.sampleRate)
                val minWidth = minOf(5.dp.toPx(), size.width)
                val left = (position * scale).coerceIn(0f, (size.width - minWidth).coerceAtLeast(0f))
                val right = ((position + frames).toFloat() * scale).coerceIn(left + minWidth, size.width)
                drawRoundRect(
                    color = if (track.muted || clip.muted) Synth.line else laneColor(index),
                    topLeft = Offset(left, 12.dp.toPx()),
                    size = Size((right - left).coerceAtLeast(5.dp.toPx()), size.height - 24.dp.toPx()),
                    cornerRadius = CornerRadius(9.dp.toPx())
                )
            }
        }
        track.clips.forEach { clip ->
            ClipControls(project, track, clip, busy, onChange)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Gain ${(gainDraft * 100).roundToInt()}%", modifier = Modifier.weight(1f))
            Switch(
                checked = track.muted,
                onCheckedChange = { muted -> onChange(ProjectArrangementActions.setTrackMuted(project, track.id, muted)) },
                enabled = !busy,
                modifier = Modifier.semantics { contentDescription = "Mute ${track.name}" }
            )
            Text("Mute")
        }
        Slider(
            value = gainDraft,
            onValueChange = { gainDraft = it },
            onValueChangeFinished = {
                if (!busy) onChange(ProjectArrangementActions.setTrackGain(project, track.id, (gainDraft * 1_000).roundToInt()))
            },
            valueRange = 0f..2f,
            enabled = !busy,
            modifier = Modifier.semantics { contentDescription = "${track.name} gain, 0 to 200 percent" }
        )
        FilterChip(
            modifier = Modifier.semantics { contentDescription = "Solo ${track.name}" },
            selected = track.solo,
            onClick = { onChange(ProjectArrangementActions.setTrackSolo(project, track.id, !track.solo)) },
            enabled = !busy,
            label = { Text(if (track.solo) "Solo on" else "Solo") }
        )
    }
}

@Composable
private fun ClipControls(project: Project, track: ProjectTrack, clip: ProjectClip, busy: Boolean, onChange: (Project) -> Unit) {
    var positionText by remember(project.id, clip.id, clip.positionFrames) { mutableStateOf(clip.positionFrames.toString()) }
    var trimStartText by remember(project.id, clip.id, clip.trim.startFrame, clip.trim.endFrame) { mutableStateOf(clip.trim.startFrame.toString()) }
    var trimEndText by remember(project.id, clip.id, clip.trim.startFrame, clip.trim.endFrame) { mutableStateOf(clip.trim.endFrame.toString()) }
    var rateDraft by remember(clip.id, clip.rateCorrectionPpm) { mutableStateOf(clip.rateCorrectionPpm.toFloat()) }
    val maxPosition = Long.MAX_VALUE - MultitrackRenderer.clipFrameCount(clip, project.sampleRate)
    val correctedPosition = positionText.toLongOrNull()?.takeIf { it in 0L..maxPosition }
    val trimStart = trimStartText.toLongOrNull()
    val trimEnd = trimEndText.toLongOrNull()
    val validSourceTrim = trimStart != null && trimEnd != null && trimStart >= 0L &&
        trimStart < trimEnd && trimEnd <= clip.source.totalFrames
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(clip.source.wav.substringAfterLast('/'), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("Clip source trim · ${clip.source.sampleRate} Hz frames")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = trimStartText,
                onValueChange = { value -> if (value.all(Char::isDigit) && value.length <= 19) trimStartText = value },
                label = { Text("Source start") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                enabled = !busy,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = trimEndText,
                onValueChange = { value -> if (value.all(Char::isDigit) && value.length <= 19) trimEndText = value },
                label = { Text("Source end") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                enabled = !busy,
                modifier = Modifier.weight(1f)
            )
        }
        BigButton(
            text = "Apply source trim",
            onClick = {
                if (validSourceTrim) {
                    onChange(ProjectArrangementActions.setClipTrim(project, track.id, clip.id, TrimRange(trimStart!!, trimEnd!!)))
                }
            },
            enabled = !busy && validSourceTrim,
            modifier = Modifier.semantics { contentDescription = "Apply source frame trim for ${clip.source.wav.substringAfterLast('/')}" }
        )
        if (!validSourceTrim) Text("Use a start before the end; both frames must be within this WAV.", color = MaterialTheme.colorScheme.error)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = positionText,
                onValueChange = { value -> if (value.all(Char::isDigit) && value.length <= 19) positionText = value },
                label = { Text("Start frame") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                enabled = !busy,
                modifier = Modifier.weight(1f)
            )
            BigButton(
                text = "Apply",
                onClick = { correctedPosition?.let { onChange(ProjectArrangementActions.setPosition(project, track.id, clip.id, it)) } },
                enabled = !busy && correctedPosition != null
            )
        }
        Stepper(
            value = "±1 frame",
            onMinus = { if (!busy) onChange(ProjectArrangementActions.setPosition(project, track.id, clip.id, (clip.positionFrames - 1L).coerceAtLeast(0L))) },
            onPlus = { if (!busy && clip.positionFrames < maxPosition) onChange(ProjectArrangementActions.setPosition(project, track.id, clip.id, clip.positionFrames + 1L)) },
            minusLabel = "Move ${track.name} clip one frame earlier",
            plusLabel = "Move ${track.name} clip one frame later"
        )
        if (correctedPosition == null) Text("Enter a nonnegative frame number.", color = MaterialTheme.colorScheme.error)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Rate ${rateDraft.roundToInt()} ppm", modifier = Modifier.weight(1f))
            Switch(
                checked = clip.muted,
                onCheckedChange = { muted -> onChange(ProjectArrangementActions.updateTrack(project, track.id) { current ->
                    current.copy(clips = current.clips.map { if (it.id == clip.id) it.copy(muted = muted) else it })
                }) },
                enabled = !busy,
                modifier = Modifier.semantics { contentDescription = "Mute clip ${clip.source.wav.substringAfterLast('/')}" }
            )
            Text("Clip mute")
        }
        Slider(
            value = rateDraft,
            onValueChange = { rateDraft = it },
            onValueChangeFinished = {
                if (!busy) onChange(ProjectArrangementActions.setRateCorrection(project, track.id, clip.id, rateDraft.roundToInt()))
            },
            valueRange = -ProjectClip.MAX_RATE_CORRECTION_PPM.toFloat()..ProjectClip.MAX_RATE_CORRECTION_PPM.toFloat(),
            enabled = !busy,
            modifier = Modifier.semantics { contentDescription = "Clip rate correction, minus 2000 to plus 2000 parts per million" }
        )
    }
}

private fun laneColor(index: Int): Color = when (index % 4) {
    0 -> Synth.pink
    1 -> Synth.cyan
    2 -> Synth.violet
    else -> Synth.yellow
}

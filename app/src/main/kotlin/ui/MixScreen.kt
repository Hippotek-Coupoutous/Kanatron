package com.kanatron.app.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import com.kanatron.app.core.ExportFilename
import com.kanatron.app.core.MultitrackRenderer
import com.kanatron.app.core.Project
import com.kanatron.app.core.ProjectArrangementActions
import com.kanatron.app.core.ProjectClip
import com.kanatron.app.core.ProjectTrack
import com.kanatron.app.core.RecordingCore
import com.kanatron.app.core.TrimRange
import com.kanatron.app.core.WavFormat
import com.kanatron.app.core.firstClip
import com.kanatron.app.data.ProjectRepository
import com.kanatron.app.data.WavExporter
import com.kanatron.app.playback.WavPlayer
import java.util.UUID
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class DragPreview(val clipId: String, val positionFrames: Long)

/** The Mix destination: one project's tracks, clip controls, preview, share and track management. */
@Composable
fun MixScreen(
    project: Project?,
    projects: List<Project>,
    repository: ProjectRepository,
    audioAvailable: Boolean,
    onOpenLibrary: () -> Unit,
    onDone: () -> Unit,
    onTrimClip: (trackId: String, clipId: String) -> Unit,
    onProjectChanged: (Project) -> Unit,
    onBusyChange: (Boolean) -> Unit
) {
    if (project == null) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Mix", color = Synth.night, style = MaterialTheme.typography.headlineMedium)
            SynthPanel { Text("Pick a recording in the Library", color = Synth.muted) }
            BigButton("Open Library", onOpenLibrary, Modifier.fillMaxWidth(), primary = true)
        }
        return
    }
    if (!audioAvailable) {
        MissingAudioScreen(project.name, onOpenLibrary)
        return
    }
    MixEditor(project, projects, repository, onDone, onTrimClip, onProjectChanged, onBusyChange)
}

@Composable
private fun MixEditor(
    initial: Project,
    projects: List<Project>,
    repository: ProjectRepository,
    onDone: () -> Unit,
    onTrimClip: (trackId: String, clipId: String) -> Unit,
    onProjectChanged: (Project) -> Unit,
    onBusyChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var project by remember(initial.id) { mutableStateOf(initial) }
    var missingSources by remember(initial.id) { mutableStateOf(emptyList<String>()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var player by remember { mutableStateOf<WavPlayer?>(null) }
    var removingTrack by remember { mutableStateOf<ProjectTrack?>(null) }
    var edited by remember { mutableStateOf(false) }

    // The bottom navigation must not take the user away from a save, render or share.
    val latestOnBusyChange = rememberUpdatedState(onBusyChange)
    LaunchedEffect(busy) { latestOnBusyChange.value(busy) }

    // Re-read on entry so a trim made in the sub-route, or a stale selection snapshot, is not shown.
    LaunchedEffect(initial.id) {
        val fresh = try {
            withContext(Dispatchers.IO) { repository.get(initial.id) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
        if (fresh == null || edited) return@LaunchedEffect
        project = fresh
        missingSources = withContext(Dispatchers.IO) { repository.missingSources(fresh) }
    }

    fun stopPreview() {
        val current = player
        player = null
        current?.stop()
        current?.release()
    }

    fun persist(next: Project) {
        if (next == project) return
        edited = true
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
                error = failure.message ?: "Could not save this mix change."
            } finally {
                busy = false
            }
        }
    }

    fun preview() {
        val current = project
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
                error = failure.message ?: "Could not prepare the mix preview."
            } finally {
                busy = false
            }
        }
    }

    fun shareMix() {
        val current = project
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
                shareExport(context, file, "Share mix")
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                error = exportFailureMessage(failure, "Could not prepare this mix for sharing.")
            } finally {
                busy = false
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { player?.release(); latestOnBusyChange.value(false) }
    }

    val current = project
    val currentPlayer = player
    val isPlaying = currentPlayer?.state?.collectAsState()?.value?.isPlaying == true

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Leaving mid-save would refresh the library before the write lands.
        BackHandler(enabled = busy) {}
        SynthTopBar(
            title = "Mix",
            onBack = { if (!busy) { stopPreview(); onDone() } },
            action = {
                TextButton(enabled = !busy, onClick = { stopPreview(); onDone() }) { Text("Done") }
            }
        )

        Text(current.name, style = MaterialTheme.typography.headlineSmall)
        Text(
            "${current.sampleRate} Hz · ${current.channels} channel${if (current.channels == 1) "" else "s"} · " +
                RecordingCore.formatClock(RecordingCore.elapsedMillis(current.totalFrames, current.sampleRate), withMillis = true, hours = false),
            color = Synth.muted,
            style = MaterialTheme.typography.bodyMedium
        )
        if (current.silent) Text("Silent recording · all captured samples are zero-valued", color = MaterialTheme.colorScheme.tertiary)
        if (missingSources.isNotEmpty()) {
            Text(
                "Missing or changed source WAV: ${missingSources.joinToString()}. The project is saved; preview and sharing are paused until it is restored.",
                color = MaterialTheme.colorScheme.error
            )
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
        }

        val canRemove = { track: ProjectTrack ->
            val remaining = current.tracks.filterNot { it.id == track.id }.flatMap(ProjectTrack::clips)
            current.tracks.size > 1 &&
                remaining.any { it.source.channels == current.channels } &&
                remaining.any { it.source.sampleRate == current.sampleRate }
        }
        current.tracks.forEach { track ->
            TrackLane(
                project = current,
                track = track,
                projects = projects,
                busy = busy,
                canRemove = canRemove(track),
                onTrimClip = onTrimClip,
                onRequestRemove = { removingTrack = track },
                onChange = ::persist
            )
        }

        if (current.tracks.size < Project.MAX_TRACKS) {
            val existingSources = current.tracks.flatMap(ProjectTrack::clips).map { it.source.wav }.toSet()
            val candidates = projects.filter { source ->
                source.firstClip.source.wav !in existingSources &&
                    source.firstClip.source.channels <= current.channels
            }
            // Only offer sources that still match their saved format; reading a WAV is not cheap enough for composition.
            val availableSources by produceState(emptyList<Project>(), candidates) {
                value = withContext(Dispatchers.IO) { candidates.filter { repository.missingSources(it).isEmpty() } }
            }
            if (availableSources.isNotEmpty()) {
                SynthPanel {
                    Text("Add recording", style = MaterialTheme.typography.titleMedium)
                    availableSources.forEach { source ->
                        FilterChip(
                            selected = false,
                            onClick = {
                                val sourceClip = source.firstClip
                                val track = ProjectTrack(
                                    id = "track-${UUID.randomUUID()}",
                                    name = source.name,
                                    clips = listOf(
                                        ProjectClip(
                                            id = "clip-${UUID.randomUUID()}",
                                            source = sourceClip.source,
                                            positionFrames = 0L,
                                            trim = sourceClip.trim
                                        )
                                    )
                                )
                                runCatching { ProjectArrangementActions.addTrack(current, track) }
                                    .onSuccess(::persist)
                                    .onFailure { error = it.message ?: "Could not add this recording." }
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
                text = if (busy) "Preparing…" else "Share mix",
                onClick = ::shareMix,
                primary = true,
                enabled = !busy && missingSources.isEmpty()
            )
        }

        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }

    removingTrack?.let { track ->
        AlertDialog(
            onDismissRequest = { if (!busy) removingTrack = null },
            title = { Text("Remove track?") },
            text = { Text("“${track.name}” and its clips leave this mix. The recording stays in the Library.") },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        removingTrack = null
                        runCatching { ProjectArrangementActions.removeTrack(current, track.id) }
                            .onSuccess(::persist)
                            .onFailure { error = it.message ?: "Could not remove this track." }
                    }
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { removingTrack = null }) { Text("Keep") }
            }
        )
    }
}

@Composable
private fun TrackLane(
    project: Project,
    track: ProjectTrack,
    projects: List<Project>,
    busy: Boolean,
    canRemove: Boolean,
    onTrimClip: (trackId: String, clipId: String) -> Unit,
    onRequestRemove: () -> Unit,
    onChange: (Project) -> Unit
) {
    // Raw take file names are opaque, so clips are labelled by the Library project owning the WAV.
    val ownerNames = remember(projects, project.id) {
        buildMap {
            projects.filter { it.id != project.id }.forEach { owner ->
                owner.tracks.forEach { candidate -> candidate.clips.forEach { putIfAbsent(it.source.wav, owner.name) } }
            }
        }
    }
    var gainDraft by remember(track.id, track.gainPermille) { mutableStateOf(track.gainPermille / 1_000f) }
    var dragPreview by remember(track.id) { mutableStateOf<DragPreview?>(null) }
    val onChangeState = androidx.compose.runtime.rememberUpdatedState(onChange)
    val laneSpan = project.totalFrames.coerceAtLeast(1L)

    SynthPanel {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                track.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            IconSquare(
                icon = SynthIcon.Delete,
                label = "Remove ${track.name} track",
                onClick = onRequestRemove,
                enabled = !busy && canRemove
            )
        }
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
            ClipControls(project, track, clip, ownerNames[clip.source.wav] ?: track.name.ifBlank { "Clip" }, busy, onTrimClip, onChange)
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
private fun ClipControls(
    project: Project,
    track: ProjectTrack,
    clip: ProjectClip,
    label: String,
    busy: Boolean,
    onTrimClip: (trackId: String, clipId: String) -> Unit,
    onChange: (Project) -> Unit
) {
    var positionText by remember(project.id, clip.id, clip.positionFrames) { mutableStateOf(clip.positionFrames.toString()) }
    var rateDraft by remember(clip.id, clip.rateCorrectionPpm) { mutableStateOf(clip.rateCorrectionPpm.toFloat()) }
    val maxPosition = Long.MAX_VALUE - MultitrackRenderer.clipFrameCount(clip, project.sampleRate)
    val correctedPosition = positionText.toLongOrNull()?.takeIf { it in 0L..maxPosition }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("Source trim · ${clip.trim.startFrame}–${clip.trim.endFrame} · ${clip.source.sampleRate} Hz")
        BigButton(
            text = "Trim",
            onClick = { onTrimClip(track.id, clip.id) },
            modifier = Modifier.semantics {
                contentDescription = "Trim $label"
            },
            enabled = !busy,
            icon = SynthIcon.Trim
        )
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
                modifier = Modifier.semantics { contentDescription = "Mute clip $label" }
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

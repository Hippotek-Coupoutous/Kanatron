package com.ottomusic.sketch.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ottomusic.sketch.core.ExportFilename
import com.ottomusic.sketch.core.Peak
import com.ottomusic.sketch.core.PeakExtractor
import com.ottomusic.sketch.core.Project
import com.ottomusic.sketch.core.ProjectArrangementActions
import com.ottomusic.sketch.core.ProjectClip
import com.ottomusic.sketch.core.ProjectTrack
import com.ottomusic.sketch.core.RecordingCore
import com.ottomusic.sketch.core.TrimActions
import com.ottomusic.sketch.core.TrimEdge
import com.ottomusic.sketch.core.TrimRange
import com.ottomusic.sketch.core.WavFormat
import com.ottomusic.sketch.core.firstClip
import com.ottomusic.sketch.core.parseExactTimeMillis
import com.ottomusic.sketch.data.ProjectRepository
import com.ottomusic.sketch.data.WavExporter
import com.ottomusic.sketch.playback.WavPlayer
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun TrimScreen(
    projectId: String,
    trackId: String?,
    clipId: String?,
    playbackAllowed: Boolean,
    onBack: () -> Unit,
    onProjectChanged: (Project) -> Unit = {}
) {
    val context = LocalContext.current
    var loadState by remember(projectId, trackId, clipId, context) { mutableStateOf<TrimLoadState>(TrimLoadState.Loading) }
    LaunchedEffect(projectId, trackId, clipId, context) {
        loadState = TrimLoadState.Loading
        loadState = withContext(Dispatchers.IO) {
            try {
                val repository = ProjectRepository(context)
                val project = repository.get(projectId) ?: return@withContext TrimLoadState.MissingProject
                val target = project.trimTarget(trackId, clipId) ?: return@withContext TrimLoadState.MissingClip
                val wav = repository.audioFile(target.clip.source)
                if (!wav.isFile) TrimLoadState.MissingAudio else TrimLoadState.Ready(repository, project, target.track.id, target.clip.id, wav)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                TrimLoadState.Failed(failure.message ?: "Could not open this recording.")
            }
        }
    }

    when (val state = loadState) {
        TrimLoadState.Loading -> Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text("Opening recording…")
            androidx.compose.material3.CircularProgressIndicator()
        }
        TrimLoadState.MissingProject -> Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text("This recording could not be found.")
            TextButton(onClick = onBack) { Text("Back") }
        }
        TrimLoadState.MissingClip -> Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text("This clip could not be found.")
            TextButton(onClick = onBack) { Text("Back") }
        }
        TrimLoadState.MissingAudio -> Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text("The WAV for this recording is missing. It remains in your library.")
            TextButton(onClick = onBack) { Text("Back") }
        }
        is TrimLoadState.Failed -> Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text(state.message)
            TextButton(onClick = onBack) { Text("Back") }
        }
        is TrimLoadState.Ready -> TrimEditor(
            context, state.repository, state.project, state.trackId, state.clipId, state.wav,
            playbackAllowed, onBack, onProjectChanged
        )
    }
}

private data class TrimTarget(val track: ProjectTrack, val clip: ProjectClip)

/** Resolves an explicit clip, or the first clip for the original single-recording route. */
private fun Project.trimTarget(trackId: String?, clipId: String?): TrimTarget? {
    if (trackId == null && clipId == null) return TrimTarget(tracks.first(), firstClip)
    val track = tracks.firstOrNull { it.id == trackId } ?: return null
    val clip = track.clips.firstOrNull { it.id == clipId } ?: return null
    return TrimTarget(track, clip)
}

private sealed interface TrimLoadState {
    object Loading : TrimLoadState
    object MissingProject : TrimLoadState
    object MissingClip : TrimLoadState
    object MissingAudio : TrimLoadState
    data class Failed(val message: String) : TrimLoadState
    data class Ready(
        val repository: ProjectRepository,
        val project: Project,
        val trackId: String,
        val clipId: String,
        val wav: File
    ) : TrimLoadState
}

@Composable
private fun TrimEditor(
    context: Context,
    repository: ProjectRepository,
    initial: Project,
    trackId: String,
    clipId: String,
    wav: File,
    playbackAllowed: Boolean,
    onBack: () -> Unit,
    onProjectChanged: (Project) -> Unit
) {
    var player by remember(initial.id) { mutableStateOf<WavPlayer?>(null) }
    var playerLoadFailure by remember(initial.id) { mutableStateOf<String?>(null) }
    val latestPlayer = rememberUpdatedState(player)
    DisposableEffect(Unit) { onDispose { latestPlayer.value?.release() } }
    LaunchedEffect(initial.id, wav) {
        player = null
        playerLoadFailure = null
        val result = AtomicReference<WavPlayer?>(null)
        try {
            val created = withContext(Dispatchers.IO) { WavPlayer(context, wav).also(result::set) }
            result.set(null)
            player = created
        } catch (cancelled: CancellationException) {
            result.getAndSet(null)?.release()
            throw cancelled
        } catch (failure: Throwable) {
            playerLoadFailure = failure.message ?: "Could not open playback for this recording."
        }
    }
    val loadedPlayer = player
    if (loadedPlayer == null) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text(playerLoadFailure ?: "Preparing playback…")
            if (playerLoadFailure == null) androidx.compose.material3.CircularProgressIndicator()
            TextButton(onClick = onBack) { Text("Back") }
        }
        return
    }
    TrimEditorLoaded(context, repository, initial, trackId, clipId, wav, loadedPlayer, playbackAllowed, onBack, onProjectChanged)
}

@Composable
private fun TrimEditorLoaded(
    context: Context,
    repository: ProjectRepository,
    initial: Project,
    trackId: String,
    clipId: String,
    wav: File,
    player: WavPlayer,
    playbackAllowed: Boolean,
    onBack: () -> Unit,
    onProjectChanged: (Project) -> Unit
) {
    val playerState by player.state.collectAsState()
    val scope = rememberCoroutineScope()
    val latestOnProjectChanged = rememberUpdatedState(onProjectChanged)
    val view = LocalView.current
    var project by remember(initial.id) { mutableStateOf(initial) }
    var persistedProject by remember(initial.id) { mutableStateOf(initial) }
    val targetTrack = project.tracks.firstOrNull { it.id == trackId }
    val targetClip = targetTrack?.clips?.firstOrNull { it.id == clipId }
    // A clip can vanish under an edit made elsewhere (e.g. Undo in Mix); never silently edit another one.
    if (targetTrack == null || targetClip == null) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text("This clip no longer exists.")
            TextButton(onClick = onBack) { Text("Back") }
        }
        return
    }
    val total = targetClip.source.totalFrames
    val sampleRate = targetClip.source.sampleRate
    val trim = targetClip.trim
    val undoCount = project.undoHistory.size
    var activeEdge by remember(initial.id) { mutableStateOf(TrimEdge.Start) }
    var coarseStepSelected by remember(initial.id) { mutableStateOf(false) }
    var holdingZoom by remember(initial.id) { mutableStateOf(false) }
    var showMore by remember(initial.id) { mutableStateOf(false) }
    var editingEdge by remember(initial.id) { mutableStateOf<TrimEdge?>(null) }
    var exactTime by remember(initial.id) { mutableStateOf("") }
    var shareInProgress by remember { mutableStateOf(false) }
    var shareFailure by remember { mutableStateOf<String?>(null) }
    var editFailure by remember { mutableStateOf<String?>(null) }
    var edgeAudition by remember { mutableStateOf(false) }
    val peaks by produceState(initialValue = emptyList<Peak>(), key1 = wav.absolutePath) {
        val peakJob = currentCoroutineContext()[Job]
        value = withContext(Dispatchers.IO) {
            try { PeakExtractor.extractCached(wav, 480) { peakJob?.isActive == false } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Throwable) { emptyList() }
        }
    }
    LaunchedEffect(playerState.isPlaying) { if (!playerState.isPlaying) edgeAudition = false }

    fun persistEditState() {
        if (project == persistedProject) return
        try {
            persistedProject = repository.saveEditState(project)
            latestOnProjectChanged.value(persistedProject)
            editFailure = null
        } catch (failure: Throwable) {
            project = persistedProject
            editFailure = failure.message ?: "Could not save this trim change."
        }
    }

    fun edit(persist: Boolean = true, command: TrimActions.() -> TrimRange?) {
        val clip = targetClip
        val actions = TrimActions(clip.trim, clip.source.totalFrames, clip.source.sampleRate)
        val next = actions.command() ?: return
        if (next == clip.trim) return
        val edited = ProjectArrangementActions.setClipTrim(project, targetTrack.id, clip.id, next)
        // One gesture is one undo step: later unsaved drag updates keep the snapshot taken at its start.
        val dragInProgress = !persist && project.undoHistory.size > persistedProject.undoHistory.size
        project = if (dragInProgress) edited.copy(undoHistory = project.undoHistory) else edited
        editFailure = null
        if (persist) persistEditState()
    }

    fun editBoundary(edge: TrimEdge, frame: Long, persist: Boolean = true) {
        activeEdge = edge
        edit(persist) { if (edge == TrimEdge.Start) setRange(frame, range.endFrame) else setRange(range.startFrame, frame) }
    }

    fun setFromPlayhead() {
        edit {
            if (activeEdge == TrimEdge.Start) setStartAtFrame(playerState.positionFrames)
            else setEndAtFrame(playerState.positionFrames)
        }
    }

    fun audition(edge: TrimEdge = activeEdge) {
        if (!playbackAllowed) return
        if (playerState.isPlaying && edgeAudition) {
            player.pause()
            edgeAudition = false
            return
        }
        val frame = if (edge == TrimEdge.Start) trim.startFrame else trim.endFrame
        val halfSecond = sampleRate / 2L
        val start = (frame - halfSecond).coerceAtLeast(0L)
        val end = (frame + halfSecond).coerceAtMost(total).coerceAtLeast(start + 1L)
        player.pause()
        player.seek(start)
        player.play(end)
        edgeAudition = true
    }

    fun undo() {
        project = ProjectArrangementActions.undo(project) ?: return
        persistEditState()
    }

    fun announce(edge: TrimEdge) {
        val frame = if (edge == TrimEdge.Start) trim.startFrame else trim.endFrame
        view.announceForAccessibility("${edge.name} boundary ${formatFrame(frame, sampleRate)}")
    }

    fun shareSelection() {
        shareFailure = null
        shareInProgress = true
        val selection = trim
        scope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    val name = ExportFilename.wav("${project.name}-trim", project.name)
                    val estimatedBytes = WavFormat.outputBytes(selection.frameCount, targetClip.source.channels)
                    repository.exportFile(project.id, name, estimatedBytes) { target ->
                        WavExporter.export(wav, target, selection)
                    }
                }
                shareExport(context, file, "Share selection")
            } catch (failure: Throwable) {
                shareFailure = exportFailureMessage(failure, "Could not prepare this selection for sharing.")
            } finally {
                shareInProgress = false
            }
        }
    }

    val latestTrim = rememberUpdatedState(trim)
    val latestChangeBoundary = rememberUpdatedState<(TrimEdge, Long) -> Unit> { edge, frame -> editBoundary(edge, frame, false) }
    val latestAnnounce = rememberUpdatedState<(TrimEdge) -> Unit>(::announce)
    val latestPersistEdits = rememberUpdatedState<() -> Unit>(::persistEditState)
    val latestActiveEdge = rememberUpdatedState(activeEdge)
    val latestHoldingZoom = rememberUpdatedState<(Boolean) -> Unit> { holdingZoom = it }
    val latestDetailStart = rememberUpdatedState(
        run {
            val span = (total / if (holdingZoom) 32L else 8L).coerceAtLeast(1L).coerceAtMost(total)
            val edgeFrame = if (activeEdge == TrimEdge.Start) trim.startFrame else trim.endFrame
            (edgeFrame - span / 2L).coerceIn(0L, (total - span).coerceAtLeast(0L))
        }
    )
    val latestDetailEnd = rememberUpdatedState(
        run {
            val span = (total / if (holdingZoom) 32L else 8L).coerceAtLeast(1L).coerceAtMost(total)
            val edgeFrame = if (activeEdge == TrimEdge.Start) trim.startFrame else trim.endFrame
            (edgeFrame - span / 2L).coerceIn(0L, (total - span).coerceAtLeast(0L)) + span
        }
    )
    val waveSurface = MaterialTheme.colorScheme.surfaceVariant
    val waveAccent = MaterialTheme.colorScheme.primary
    val waveDim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.48f)
    val overlay = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)

    // Sharing reads the selection from the same files an exit would tear down.
    BackHandler(enabled = shareInProgress) {}
    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(enabled = !shareInProgress, onClick = { player.stop(); onBack() }) { Text("Back") }
                Text("Trim", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.weight(1f))
                TextButton(enabled = undoCount > 0, onClick = ::undo) { Text("Undo") }
            }
            Text(project.name, style = MaterialTheme.typography.headlineSmall)
            editFailure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            playerState.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            Text("Overview · whole recording", style = MaterialTheme.typography.labelLarge)
            Canvas(
                Modifier.fillMaxWidth().height(76.dp).clip(MaterialTheme.shapes.medium)
                    .semantics { contentDescription = "Trim overview waveform. Drag a boundary to change the selection." }
                    .pointerInput(project.id) {
                        var dragged: TrimEdge? = null
                        detectDragGestures(
                            onDragStart = { position ->
                                val current = latestTrim.value
                                val startX = current.startFrame.toFloat() / total * size.width
                                val endX = current.endFrame.toFloat() / total * size.width
                                val startDistance = kotlin.math.abs(position.x - startX)
                                val endDistance = kotlin.math.abs(position.x - endX)
                                dragged = when {
                                    minOf(startDistance, endDistance) > 24.dp.toPx() -> null
                                    startDistance <= endDistance -> TrimEdge.Start
                                    else -> TrimEdge.End
                                }
                            },
                            onDrag = { change, _ ->
                                dragged?.let { edge ->
                                    val frame = (change.position.x / size.width * total).toLong().coerceIn(0L, total)
                                    latestChangeBoundary.value(edge, frame)
                                    change.consume()
                                }
                            },
                            onDragEnd = {
                                dragged?.let { latestAnnounce.value(it); latestPersistEdits.value() }
                                dragged = null
                            },
                            onDragCancel = { if (dragged != null) latestPersistEdits.value(); dragged = null }
                        )
                    }
            ) {
                drawTrimWaveform(peaks, total, 0L, total, latestTrim.value, waveSurface, waveAccent, waveDim, overlay)
            }

            Text("Detail · ${activeEdge.name.lowercase()} boundary", style = MaterialTheme.typography.labelLarge)
            Canvas(
                Modifier.fillMaxWidth().height(76.dp).clip(MaterialTheme.shapes.medium)
                    .semantics { contentDescription = "Trim detail waveform for the ${activeEdge.name.lowercase()} boundary." }
                    .pointerInput(project.id) {
                        var dragging = false
                        detectDragGesturesAfterLongPress(
                            onDragStart = { dragging = true; latestHoldingZoom.value(true) },
                            onDrag = { change, _ ->
                                if (dragging) {
                                    val frame = latestDetailStart.value +
                                        (change.position.x / size.width * (latestDetailEnd.value - latestDetailStart.value)).toLong()
                                    latestChangeBoundary.value(latestActiveEdge.value, frame.coerceIn(0L, total))
                                    change.consume()
                                }
                            },
                            onDragEnd = {
                                if (dragging) {
                                    latestAnnounce.value(latestActiveEdge.value)
                                    latestPersistEdits.value()
                                }
                                dragging = false
                                latestHoldingZoom.value(false)
                            },
                            onDragCancel = { if (dragging) latestPersistEdits.value(); dragging = false; latestHoldingZoom.value(false) }
                        )
                    }
            ) {
                drawTrimWaveform(
                    peaks, total, latestDetailStart.value, latestDetailEnd.value, latestTrim.value,
                    waveSurface, waveAccent, waveDim, overlay
                )
            }
            Text("Long-press and drag the detail waveform to zoom while moving the selected boundary.", style = MaterialTheme.typography.bodySmall)

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BoundaryReadout(TrimEdge.Start, trim.startFrame, total, sampleRate, Modifier.weight(1f),
                    onClick = { activeEdge = TrimEdge.Start; editingEdge = TrimEdge.Start; exactTime = formatFrame(trim.startFrame, sampleRate) },
                    onMove = { edge, frame -> editBoundary(edge, frame) },
                    onSetFromPlayhead = { edge -> activeEdge = edge; edit { setStartAtFrame(playerState.positionFrames) } },
                    onAudition = ::audition
                )
                BoundaryReadout(TrimEdge.End, trim.endFrame, total, sampleRate, Modifier.weight(1f),
                    onClick = { activeEdge = TrimEdge.End; editingEdge = TrimEdge.End; exactTime = formatFrame(trim.endFrame, sampleRate) },
                    onMove = { edge, frame -> editBoundary(edge, frame) },
                    onSetFromPlayhead = { edge -> activeEdge = edge; edit { setEndAtFrame(playerState.positionFrames) } },
                    onAudition = ::audition
                )
            }
        }

        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (activeEdge == TrimEdge.Start) Button(onClick = {}, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("Start") }
                else OutlinedButton(onClick = { activeEdge = TrimEdge.Start }, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("Start") }
                if (activeEdge == TrimEdge.End) Button(onClick = {}, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("End") }
                else OutlinedButton(onClick = { activeEdge = TrimEdge.End }, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("End") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = { edit { nudge(activeEdge, -stepFrames(sampleRate, coarseStepSelected)) } },
                    modifier = Modifier.weight(0.65f).heightIn(min = 52.dp)
                ) { Text("−") }
                OutlinedButton(
                    onClick = { coarseStepSelected = !coarseStepSelected },
                    modifier = Modifier.weight(1.6f).heightIn(min = 52.dp)
                ) { Text(if (coarseStepSelected) "Coarse · 100 ms" else "Fine · 10 ms") }
                OutlinedButton(
                    onClick = { edit { nudge(activeEdge, stepFrames(sampleRate, coarseStepSelected)) } },
                    modifier = Modifier.weight(0.65f).heightIn(min = 52.dp)
                ) { Text("+") }
                Button(enabled = playbackAllowed, onClick = { audition() }, modifier = Modifier.weight(1.3f).heightIn(min = 52.dp)) {
                    Text(if (edgeAudition && playerState.isPlaying) "Stop edge" else "Hear edge")
                }
            }
            TextButton(onClick = { showMore = !showMore }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(if (showMore) "Less" else "More")
            }
            if (showMore) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = ::setFromPlayhead, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("Set from playhead") }
                    OutlinedButton(
                        enabled = playbackAllowed,
                        onClick = {
                            if (playerState.isPlaying) player.pause() else {
                                if (playerState.positionFrames !in trim.startFrame until trim.endFrame) player.seek(trim.startFrame)
                                player.play(trim.endFrame)
                            }
                        }, modifier = Modifier.weight(1f).heightIn(min = 52.dp)
                    ) { Text(if (playerState.isPlaying && !edgeAudition) "Pause selection" else "Play selection") }
                }
            }
        }

        Surface(tonalElevation = 3.dp) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text("${formatFrame(trim.frameCount, sampleRate)} selected", style = MaterialTheme.typography.bodyMedium)
                    shareFailure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
                Button(
                    enabled = !shareInProgress,
                    onClick = ::shareSelection,
                    modifier = Modifier.heightIn(min = 56.dp)
                ) { Text("Share WAV") }
            }
        }
    }

    editingEdge?.let { edge ->
        AlertDialog(
            onDismissRequest = { editingEdge = null },
            title = { Text("Set ${edge.name.lowercase()} time") },
            text = {
                OutlinedTextField(
                    value = exactTime,
                    onValueChange = { exactTime = it },
                    label = { Text("HH:MM:SS.mmm") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        parseExactTimeMillis(exactTime)?.let { millis ->
                            edit { setRangeMs(millis, millis, edge) }
                            editingEdge = null
                        }
                    })
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    parseExactTimeMillis(exactTime)?.let { millis ->
                        edit { setRangeMs(millis, millis, edge) }
                        editingEdge = null
                    }
                }) { Text("Set time") }
            },
            dismissButton = { TextButton(onClick = { editingEdge = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun BoundaryReadout(
    edge: TrimEdge,
    frame: Long,
    total: Long,
    sampleRate: Int,
    modifier: Modifier,
    onClick: () -> Unit,
    onMove: (TrimEdge, Long) -> Unit,
    onSetFromPlayhead: (TrimEdge) -> Unit,
    onAudition: (TrimEdge) -> Unit
) {
    val frameValue = frame.toFloat().coerceIn(0f, total.toFloat())
    val fine = fineStep(sampleRate)
    val coarse = coarseStep(sampleRate)
    TextButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 56.dp).semantics(mergeDescendants = true) {
            contentDescription = "${edge.name} trim boundary"
            stateDescription = formatFrame(frame, sampleRate)
            progressBarRangeInfo = ProgressBarRangeInfo(frameValue, 0f..total.toFloat())
            customActions = listOf(
                CustomAccessibilityAction("Move earlier by 10 ms") { onMove(edge, frame - fine); true },
                CustomAccessibilityAction("Move later by 10 ms") { onMove(edge, frame + fine); true },
                CustomAccessibilityAction("Move earlier by 100 ms") { onMove(edge, frame - coarse); true },
                CustomAccessibilityAction("Move later by 100 ms") { onMove(edge, frame + coarse); true },
                CustomAccessibilityAction("Set from playhead") { onSetFromPlayhead(edge); true },
                CustomAccessibilityAction("Audition edge") { onAudition(edge); true }
            )
        }
    ) {
        Column(horizontalAlignment = Alignment.Start) {
            Text(edge.name, style = MaterialTheme.typography.labelMedium)
            Text(formatFrame(frame, sampleRate), fontFamily = FontFamily.Monospace)
        }
    }
}

private fun DrawScope.drawTrimWaveform(
    peaks: List<Peak>,
    total: Long,
    viewStart: Long,
    viewEnd: Long,
    trim: TrimRange,
    background: Color,
    accent: Color,
    dimmed: Color,
    overlay: Color
) {
    val start = viewStart.coerceIn(0L, total)
    val end = viewEnd.coerceIn(start + 1L, total)
    val span = (end - start).toFloat()
    val xStart = ((trim.startFrame - start).toFloat() / span * size.width).coerceIn(0f, size.width)
    val xEnd = ((trim.endFrame - start).toFloat() / span * size.width).coerceIn(0f, size.width)
    drawRect(background)
    if (xStart > 0f) drawRect(overlay, size = Size(xStart, size.height))
    if (xEnd < size.width) drawRect(overlay, topLeft = Offset(xEnd, 0f), size = Size(size.width - xEnd, size.height))
    if (peaks.isEmpty()) {
        drawLine(accent.copy(alpha = 0.45f), Offset(0f, size.height / 2f), Offset(size.width, size.height / 2f), strokeWidth = 2.dp.toPx())
    } else {
        val middle = size.height / 2f
        peaks.forEachIndexed { index, peak ->
            val frame = index.toLong() * total / peaks.size
            if (frame in start..end) {
                val x = (frame - start).toFloat() / span * size.width
                val top = middle - peak.max / 32768f * (middle - 7.dp.toPx())
                val bottom = middle - peak.min / 32768f * (middle - 7.dp.toPx())
                val selected = frame in trim.startFrame until trim.endFrame
                drawLine(
                    if (selected) accent else dimmed,
                    Offset(x, top), Offset(x, bottom),
                    strokeWidth = maxOf(2.dp.toPx(), size.width / peaks.size * 0.55f),
                    cap = StrokeCap.Round
                )
            }
        }
    }
    listOf(xStart, xEnd).forEach { x ->
        drawLine(accent, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
        drawCircle(accent, radius = 7.dp.toPx(), center = Offset(x, size.height - 9.dp.toPx()))
    }
}

private fun fineStep(sampleRate: Int): Long = (sampleRate / 100).coerceAtLeast(1).toLong()
private fun coarseStep(sampleRate: Int): Long = (sampleRate / 10).coerceAtLeast(1).toLong()
private fun stepFrames(sampleRate: Int, coarse: Boolean): Long = if (coarse) coarseStep(sampleRate) else fineStep(sampleRate)

private fun formatFrame(frame: Long, sampleRate: Int): String =
    RecordingCore.formatClock(RecordingCore.elapsedMillis(frame, sampleRate), withMillis = true)
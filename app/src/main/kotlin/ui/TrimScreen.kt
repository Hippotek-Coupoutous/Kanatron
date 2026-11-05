package com.ottomusic.sketch.ui

import android.content.Context
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
import com.ottomusic.sketch.core.AacM4aExporter
import com.ottomusic.sketch.core.ExportFilename
import com.ottomusic.sketch.core.Peak
import com.ottomusic.sketch.core.PeakExtractor
import com.ottomusic.sketch.core.PlatformAacEncoder
import com.ottomusic.sketch.core.Project
import com.ottomusic.sketch.core.RecordingCore
import com.ottomusic.sketch.core.TrimActions
import com.ottomusic.sketch.core.TrimEdge
import com.ottomusic.sketch.core.TrimRange
import com.ottomusic.sketch.core.WavExporter
import com.ottomusic.sketch.core.parseExactTimeMillis
import com.ottomusic.sketch.data.ProjectRepository
import com.ottomusic.sketch.playback.WavPlayer
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class ExportFormat(val label: String) {
    Wav("WAV"), M4a("M4A (AAC)")
}

@Composable
fun TrimScreen(
    projectId: String,
    playbackAllowed: Boolean,
    onBack: () -> Unit,
    onProjectChanged: (Project) -> Unit = {}
) {
    val context = LocalContext.current
    var loadState by remember(projectId, context) { mutableStateOf<TrimLoadState>(TrimLoadState.Loading) }
    LaunchedEffect(projectId, context) {
        loadState = TrimLoadState.Loading
        loadState = withContext(Dispatchers.IO) {
            try {
                val repository = ProjectRepository(context)
                val project = repository.get(projectId) ?: return@withContext TrimLoadState.MissingProject
                val wav = repository.playbackFile(project)
                if (!wav.isFile) TrimLoadState.MissingAudio else TrimLoadState.Ready(repository, project, wav)
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
        TrimLoadState.MissingAudio -> Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text("The WAV for this recording is missing. It remains in your library.")
            TextButton(onClick = onBack) { Text("Back") }
        }
        is TrimLoadState.Failed -> Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text(state.message)
            TextButton(onClick = onBack) { Text("Back") }
        }
        is TrimLoadState.Ready -> TrimEditor(context, state.repository, state.project, state.wav, playbackAllowed, onBack, onProjectChanged)
    }
}

private sealed interface TrimLoadState {
    object Loading : TrimLoadState
    object MissingProject : TrimLoadState
    object MissingAudio : TrimLoadState
    data class Failed(val message: String) : TrimLoadState
    data class Ready(val repository: ProjectRepository, val project: Project, val wav: File) : TrimLoadState
}

@Composable
private fun TrimEditor(
    context: Context,
    repository: ProjectRepository,
    initial: Project,
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
    TrimEditorLoaded(context, repository, initial, wav, loadedPlayer, playbackAllowed, onBack, onProjectChanged)
}

@Composable
private fun TrimEditorLoaded(
    context: Context,
    repository: ProjectRepository,
    initial: Project,
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
    val total = initial.totalFrames
    val sampleRate = initial.sampleRate
    var project by remember(initial.id) { mutableStateOf(initial) }
    var persistedProject by remember(initial.id) { mutableStateOf(initial) }
    val trim = project.trim
    val undoCount = project.undoHistory.size
    var activeEdge by remember(initial.id) { mutableStateOf(TrimEdge.Start) }
    var coarseStepSelected by remember(initial.id) { mutableStateOf(false) }
    var holdingZoom by remember(initial.id) { mutableStateOf(false) }
    var showMore by remember(initial.id) { mutableStateOf(false) }
    var editingEdge by remember(initial.id) { mutableStateOf<TrimEdge?>(null) }
    var exactTime by remember(initial.id) { mutableStateOf("") }
    var exportName by remember(initial.id) { mutableStateOf("${initial.name}-trim.wav") }
    var exportFormat by remember(initial.id) { mutableStateOf(ExportFormat.Wav) }
    var exportInProgress by remember { mutableStateOf(false) }
    var exportProgress by remember { mutableStateOf(0f) }
    var exportFailure by remember { mutableStateOf<String?>(null) }
    var editFailure by remember { mutableStateOf<String?>(null) }
    var exportedFile by remember { mutableStateOf<File?>(null) }
    var activeExport by remember { mutableStateOf<AtomicBoolean?>(null) }
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
            exportedFile = null
            editFailure = failure.message ?: "Could not save this trim change."
        }
    }

    fun edit(persist: Boolean = true, command: TrimActions.() -> TrimRange?) {
        val actions = TrimActions(project.trim, total, sampleRate, initialHistory = project.undoHistory)
        val next = actions.command() ?: return
        if (next == project.trim) return
        project = project.copy(trim = next, undoHistory = actions.historySnapshot())
        exportedFile = null
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

    fun undo() { edit { this.undo() } }

    fun announce(edge: TrimEdge) {
        val frame = if (edge == TrimEdge.Start) trim.startFrame else trim.endFrame
        view.announceForAccessibility("${edge.name} boundary ${formatFrame(frame, sampleRate)}")
    }

    fun beginExport() {
        exportFailure = null
        exportedFile = null
        val selectedFormat = exportFormat
        val targetName = when (selectedFormat) {
            ExportFormat.Wav -> ExportFilename.wav(exportName, project.name)
            ExportFormat.M4a -> ExportFilename.m4a(exportName, project.name)
        }
        val cancelled = AtomicBoolean(false)
        activeExport = cancelled
        exportInProgress = true
        exportProgress = 0f
        val selectedRange = trim
        scope.launch {
            try {
                var reportedPercent = -1
                val onProgress: (Long, Long) -> Unit = { copied, totalBytes ->
                    val percent = if (totalBytes == 0L) 100 else (copied.toDouble() * 100.0 / totalBytes).toInt()
                    if (percent != reportedPercent) {
                        reportedPercent = percent
                        scope.launch { exportProgress = percent / 100f }
                    }
                }
                exportedFile = withContext(Dispatchers.IO) {
                    val estimatedBytes = when (selectedFormat) {
                        ExportFormat.Wav -> Math.addExact(
                            Math.multiplyExact(Math.multiplyExact(selectedRange.frameCount, project.channels.toLong()), 2L),
                            RecordingCore.WAV_HEADER_BYTES
                        )
                        ExportFormat.M4a -> AacM4aExporter.estimateOutputBytes(
                            selectedRange.frameCount, project.sampleRate, project.channels
                        )
                    }
                    repository.exportFile(project.id, targetName, estimatedBytes) { target ->
                        when (selectedFormat) {
                            ExportFormat.Wav -> WavExporter.export(wav, target, selectedRange, cancelled, onProgress = onProgress)
                            ExportFormat.M4a -> AacM4aExporter.export(
                                wav, target, selectedRange, PlatformAacEncoder(), cancelled, onProgress = onProgress
                            )
                        }
                    }
                }
            } catch (failure: Throwable) {
                exportFailure = exportFailureMessage(failure)
            } finally {
                exportInProgress = false
                activeExport = null
            }
        }
    }

    fun beginShare(file: File) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { repository.markExportShared(file) }
                shareExport(context, file)
            } catch (failure: Throwable) {
                exportFailure = failure.message ?: "Could not prepare this export for sharing."
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

    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { player.stop(); onBack() }) { Text("Back") }
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

            Text("Export filename", style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(
                value = exportName,
                onValueChange = { exportName = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
            )
            if (exportFailure != null) Text(exportFailure!!, color = MaterialTheme.colorScheme.error)
            if (exportInProgress) {
                androidx.compose.material3.LinearProgressIndicator(progress = { exportProgress }, modifier = Modifier.fillMaxWidth())
                Text("Exporting ${ (exportProgress * 100).toInt() }%", style = MaterialTheme.typography.bodySmall)
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
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Format", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                    ExportFormat.entries.forEach { format ->
                        if (exportFormat == format) {
                            Button(enabled = false, onClick = {}, modifier = Modifier.heightIn(min = 48.dp)) { Text(format.label) }
                        } else {
                            OutlinedButton(
                                enabled = !exportInProgress,
                                onClick = {
                                    exportFormat = format
                                    exportName = when (format) {
                                        ExportFormat.Wav -> ExportFilename.wav(exportName, project.name)
                                        ExportFormat.M4a -> ExportFilename.m4a(exportName, project.name)
                                    }
                                    exportedFile = null
                                    exportFailure = null
                                },
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) { Text(format.label) }
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("${formatFrame(trim.frameCount, sampleRate)} selected", style = MaterialTheme.typography.bodyMedium)
                        Text("WAV · ${readableSize(wavOutputBytes(trim.frameCount, project.channels))}", style = MaterialTheme.typography.bodySmall)
                        Text(
                            "M4A (AAC) · ${readableSize(AacM4aExporter.estimateOutputBytes(trim.frameCount, sampleRate, project.channels))}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (exportInProgress) {
                        Button(onClick = { activeExport?.set(true) }, modifier = Modifier.heightIn(min = 56.dp)) { Text("Cancel") }
                    } else if (exportedFile != null) {
                        Button(onClick = { beginShare(exportedFile!!) }, modifier = Modifier.heightIn(min = 56.dp)) { Text("Share ${exportFormat.label}") }
                    } else {
                        Button(onClick = ::beginExport, modifier = Modifier.heightIn(min = 56.dp)) { Text("Export ${exportFormat.label}") }
                    }
                }
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
private fun wavOutputBytes(frames: Long, channels: Int): Long = 44L + frames * channels * 2L
private fun readableSize(bytes: Long): String = if (bytes < 1024L * 1024L) "${(bytes + 1023L) / 1024L} KB" else "%.1f MB".format(bytes / (1024.0 * 1024.0))

private fun formatFrame(frame: Long, sampleRate: Int): String {
    val milliseconds = RecordingCore.elapsedMillis(frame, sampleRate)
    val hours = milliseconds / 3_600_000L
    val minutes = milliseconds / 60_000L % 60L
    val seconds = milliseconds / 1_000L % 60L
    val millis = milliseconds % 1_000L
    return if (hours > 0L) "%d:%02d:%02d.%03d".format(hours, minutes, seconds, millis)
    else "%02d:%02d.%03d".format(milliseconds / 60_000L, seconds, millis)
}

private fun exportFailureMessage(failure: Throwable): String =
    com.ottomusic.sketch.ui.exportFailureMessage(failure, "Could not export this selection.")
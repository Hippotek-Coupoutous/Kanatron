package com.ottomusic.sketch.ui

import android.content.Context
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ottomusic.sketch.core.Peak
import com.ottomusic.sketch.core.PeakExtractor
import com.ottomusic.sketch.core.Project
import com.ottomusic.sketch.core.RecordingCore
import com.ottomusic.sketch.playback.ClickEngine
import com.ottomusic.sketch.playback.MonitorClick
import com.ottomusic.sketch.playback.WavPlayer
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

@Composable
internal fun SketchScreen(
    current: Project?,
    currentWav: File?,
    playbackAllowed: Boolean,
    click: ClickEngine,
    layerOn: Boolean,
    onLayerToggle: () -> Unit,
    onRecord: (MonitorClick?) -> Unit,
    onShare: (Project) -> Unit,
    onImport: () -> Unit
) {
    val context = LocalContext.current
    val wav = currentWav
    val player = rememberWavPlayer(context, wav)
    val playerState = player?.state?.collectAsState()?.value
    val clickState by click.state.collectAsState()
    var clickPanel by rememberSaveable { mutableStateOf(false) }
    var countInBars by rememberSaveable { mutableStateOf(0) }
    val tapTimes = remember { mutableListOf<Long>() }
    val peaks by produceState(initialValue = emptyList<Peak>(), key1 = wav?.absolutePath) {
        value = wav?.let { file -> withContext(Dispatchers.IO) { runCatching { PeakExtractor.extractCached(file, 120) { false } }.getOrDefault(emptyList()) } }.orEmpty()
    }
    val playing = playerState?.isPlaying == true
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Audio Sketch", color = Synth.night, fontSize = 28.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp
            )
            SynthPanel {
                Text(current?.name ?: "No take yet", color = Synth.muted, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                val frames = when {
                    current == null -> 0L
                    playerState != null && (playing || playerState.positionFrames > current.trim.startFrame) -> playerState.positionFrames - current.trim.startFrame
                    else -> current.trim.frameCount
                }
                Text(RecordingCore.formatClock(if (current == null) 0L else RecordingCore.elapsedMillis(frames.coerceAtLeast(0L), current.sampleRate)), color = Synth.cyan, fontSize = 56.sp, fontFamily = FontFamily.Monospace)
                MiniWave(peaks, current, playerState?.positionFrames ?: 0L, Modifier.fillMaxWidth().height(96.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                RoundButton(
                    if (playing) SynthIcon.Pause else SynthIcon.Play, if (playing) "Pause" else "Play", Synth.cyan,
                    onClick = {
                        val p = player ?: return@RoundButton
                        if (playing) p.pause() else {
                            if (playerState!!.positionFrames !in current!!.trim.startFrame until current.trim.endFrame) p.seek(current.trim.startFrame)
                            p.play(current.trim.endFrame)
                        }
                    },
                    size = 84.dp, enabled = player != null && playbackAllowed
                )
                RoundButton(SynthIcon.Rec, "Record", Synth.pink, onClick = {
                    player?.stop()
                    val monitor = if (clickState.running) MonitorClick(clickState.bpm, clickState.beatsPerBar, countInBars) else null
                    click.stop()
                    onRecord(monitor)
                }, size = 112.dp, contentColor = Color.White)
                RoundButton(SynthIcon.Layer, if (layerOn) "Layer on" else "Layer", if (layerOn) Synth.violet else Synth.surfaceHi, onClick = onLayerToggle, size = 72.dp, contentColor = if (layerOn) Synth.night else Synth.text)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Tile(SynthIcon.Import, "Import", onImport, Modifier.weight(1f), description = "Import audio")
                Tile(
                    SynthIcon.Click, "${clickState.bpm}", { if (clickState.running) click.stop() else click.start() }, Modifier.weight(1f),
                    selected = clickState.running, description = "Click ${clickState.bpm} BPM", enabled = playbackAllowed
                )
                Tile(SynthIcon.Share, "Share", { current?.let(onShare) }, Modifier.weight(1f), enabled = current != null)
            }
            Text(
                if (clickPanel) "Hide click settings" else "Click settings", color = Synth.night,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { clickPanel = !clickPanel }.padding(vertical = 12.dp),
                textAlign = TextAlign.Center
            )
            if (clickPanel) SynthPanel {
                Stepper("${clickState.bpm}", { click.setBpm(clickState.bpm - 5) }, { click.setBpm(clickState.bpm + 5) }, "5 slower", "5 faster")
                BigButton("Tap", {
                    val now = System.currentTimeMillis()
                    if (tapTimes.isNotEmpty() && now - tapTimes.last() > 2000) tapTimes.clear()
                    tapTimes += now
                    while (tapTimes.size > 4) tapTimes.removeAt(0)
                    if (tapTimes.size >= 2) click.setBpm(Math.round(60_000.0 * (tapTimes.size - 1) / (tapTimes.last() - tapTimes.first())).toInt())
                }, Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (n in listOf(2, 3, 4, 6)) BigButton("$n", { click.setBeatsPerBar(n) }, Modifier.weight(1f), primary = clickState.beatsPerBar == n)
                }
                Text("Layer count-in", color = Synth.muted)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0, 1, 2).forEach { bars ->
                        BigButton(if (bars == 0) "Off" else "$bars bar${if (bars == 1) "" else "s"}", { countInBars = bars }, Modifier.weight(1f), primary = countInBars == bars)
                    }
                }
            }
        }
    }
}

@Composable
internal fun LibraryScreen(
    projects: List<Project>,
    onOpen: (Project) -> Unit,
    onRename: (Project, String) -> Unit,
    onDelete: (Project) -> Unit
) {
    var renameTarget by remember { mutableStateOf<Project?>(null) }
    var deleteTarget by remember { mutableStateOf<Project?>(null) }
    var renameName by remember { mutableStateOf("") }
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Library", color = Synth.night, fontSize = 28.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp
            )
            if (projects.isEmpty()) {
                Text("Your recordings live here", color = Synth.night, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp))
            } else projects.sortedByDescending { it.createdAtEpochMillis }.forEach { project ->
                LibraryRow(
                    project,
                    onOpen = { onOpen(project) },
                    onRename = { renameName = project.name; renameTarget = project },
                    onDelete = { deleteTarget = project }
                )
            }
        }
    }
    val rename = renameTarget
    if (rename != null) {
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename recording") },
            text = { OutlinedTextField(renameName, { renameName = it }, label = { Text("Name") }, singleLine = true) },
            confirmButton = { TextButton(onClick = { if (renameName.isNotBlank()) onRename(rename, renameName); renameTarget = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel") } }
        )
    }
    val delete = deleteTarget
    if (delete != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete recording?") },
            text = { Text("${delete.name} and its original WAV will be removed.") },
            confirmButton = { TextButton(onClick = { deleteTarget = null; onDelete(delete) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun LibraryRow(project: Project, onOpen: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Synth.surface)
            .border(1.dp, Synth.line, RoundedCornerShape(16.dp)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            Modifier.weight(1f).heightIn(min = 68.dp).clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 10.dp)
                .clearAndSetSemantics { contentDescription = "${project.name}, ${duration(project)}" },
            verticalArrangement = Arrangement.Center
        ) {
            Text(project.name, color = Synth.text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(if (project.silent) "${duration(project)} · silent" else duration(project), color = Synth.muted, fontFamily = FontFamily.Monospace)
        }
        Box {
            Box(
                Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).clickable(role = Role.Button, onClick = { menuOpen = true })
                    .semantics { contentDescription = "More options for ${project.name}" },
                contentAlignment = Alignment.Center
            ) { Text("⋮", color = Synth.text, fontSize = 26.sp) }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text("Rename") }, onClick = { menuOpen = false; onRename() })
                DropdownMenuItem(text = { Text("Delete") }, onClick = { menuOpen = false; onDelete() })
            }
        }
    }
}

@Composable
private fun MiniWave(peaks: List<Peak>, project: Project?, position: Long, modifier: Modifier) {
    Canvas(modifier.clip(RoundedCornerShape(12.dp)).background(Synth.night.copy(alpha = 0.7f)).semantics { contentDescription = "Waveform" }) {
        val center = size.height / 2f
        if (peaks.isEmpty() || project == null) {
            drawLine(Synth.muted.copy(alpha = 0.45f), androidx.compose.ui.geometry.Offset(0f, center), androidx.compose.ui.geometry.Offset(size.width, center), strokeWidth = 2.dp.toPx())
            return@Canvas
        }
        val step = size.width / peaks.size
        val total = project.totalFrames.toFloat()
        peaks.forEachIndexed { index, peak ->
            val frame = (index + 0.5f) / peaks.size * total
            val inside = frame >= project.trim.startFrame && frame < project.trim.endFrame
            val x = index * step + step / 2
            drawLine(
                if (inside) Synth.cyan else Synth.violet.copy(alpha = 0.35f),
                androidx.compose.ui.geometry.Offset(x, center - peak.max / 32768f * center), androidx.compose.ui.geometry.Offset(x, center - peak.min / 32768f * center),
                strokeWidth = maxOf(2.dp.toPx(), step * 0.55f), cap = StrokeCap.Round
            )
        }
        if (position > project.trim.startFrame) {
            val x = size.width * position / total
            drawLine(Synth.pink, androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Offset(x, size.height), strokeWidth = 2.dp.toPx())
        }
    }
}

@Composable
private fun rememberWavPlayer(context: Context, wav: File?, takesFocus: Boolean = true): WavPlayer? {
    val path = wav?.absolutePath
    var player by remember(path, takesFocus) { mutableStateOf<WavPlayer?>(null) }
    val holder = remember(path, takesFocus) { AtomicReference<WavPlayer?>(null) }
    LaunchedEffect(path, takesFocus) {
        val file = wav ?: return@LaunchedEffect
        val ownerJob = currentCoroutineContext()[Job]
        try {
            val loaded = withContext(Dispatchers.IO) {
                WavPlayer(context, file, takesFocus).also(holder::set)
            }
            if (ownerJob?.isActive == true) player = loaded else holder.getAndSet(null)?.release()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            player = null
        } finally {
            if (ownerJob?.isActive == false) holder.getAndSet(null)?.release()
        }
    }
    androidx.compose.runtime.DisposableEffect(holder) {
        onDispose { holder.getAndSet(null)?.release() }
    }
    return player
}

@Composable
internal fun MissingAudioScreen(projectName: String, onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Audio file missing", style = MaterialTheme.typography.titleLarge)
        Text("The WAV for $projectName could not be found. The project entry is still in your library.", modifier = Modifier.padding(top = 8.dp), textAlign = TextAlign.Center)
        Button(onClick = onBack, modifier = Modifier.padding(top = 16.dp)) { Text("Back to library") }
    }
}

private fun duration(project: Project): String = RecordingCore.formatClock(RecordingCore.elapsedMillis(project.totalFrames, project.sampleRate))
package com.kanatron.app.ui

import android.content.Context
import android.os.Build
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
import androidx.compose.material3.Slider
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
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kanatron.app.core.Peak
import com.kanatron.app.core.PeakExtractor
import com.kanatron.app.core.Project
import com.kanatron.app.core.RecordingCore
import com.kanatron.app.playback.ClickEngine
import com.kanatron.app.playback.MonitorClick
import com.kanatron.app.playback.WavPlayer
import com.kanatron.app.recording.RecordingPhase
import com.kanatron.app.recording.RecordingServiceState
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

@Composable
internal fun SketchScreen(
    recording: RecordingServiceState,
    shortcutRequested: Boolean,
    microphoneGranted: Boolean,
    microphoneDenied: Boolean,
    notificationsGranted: Boolean,
    startError: String?,
    current: Project?,
    currentWav: File?,
    draft: Project?,
    click: ClickEngine,
    onRecord: (MonitorClick?) -> Unit,
    onOverdub: (backing: Project, MonitorClick?) -> Unit,
    onSaveDraft: (Project) -> Unit,
    onSaveAndOverdub: (backing: Project, MonitorClick?) -> Unit,
    onDiscardDraft: (Project) -> Unit,
    onDiscardAndRecord: (Project, MonitorClick?) -> Unit,
    onPauseResume: () -> Unit,
    onPreview: () -> Unit,
    onSave: () -> Unit,
    onShare: (Project) -> Unit,
    onImport: () -> Unit,
    onCalibration: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenInEdit: () -> Unit
) {
    val context = LocalContext.current
    val wav = currentWav
    val player = rememberWavPlayer(context, wav)
    val playerState = player?.state?.collectAsState()?.value
    val clickState by click.state.collectAsState()
    var clickPanel by rememberSaveable { mutableStateOf(false) }
    var countInBars by rememberSaveable { mutableStateOf(0) }
    var draftPrompt by rememberSaveable(draft?.id) { mutableStateOf<DraftPrompt?>(null) }
    var overdubbing by rememberSaveable { mutableStateOf(false) }
    var seekPreview by remember(current?.id) { mutableStateOf<Long?>(null) }
    val tapTimes = remember { mutableListOf<Long>() }
    val peaks by produceState(initialValue = emptyList<Peak>(), key1 = wav?.absolutePath) {
        value = wav?.let { file -> withContext(Dispatchers.IO) { runCatching { PeakExtractor.extractCached(file, 120) { false } }.getOrDefault(emptyList()) } }.orEmpty()
    }
    val playing = playerState?.isPlaying == true
    val playhead = seekPreview ?: (playerState?.positionFrames ?: 0L)
    val phase = recording.phase
    // Terminal and notice fields describe the last take; they only describe `current` when that take is `current`.
    val lastTakeShown = recording.projectId != null && recording.projectId == current?.id
    val recordingNow = phase == RecordingPhase.STARTING || phase == RecordingPhase.RECORDING
    val paused = phase == RecordingPhase.PAUSED
    val active = recordingNow || paused
    val saved = phase == RecordingPhase.COMPLETE && lastTakeShown
    val canSave = phase == RecordingPhase.RECORDING || paused
    val previewing = paused && recording.previewPlaying
    val status = when {
        recordingNow -> "Recording"
        paused -> "Paused"
        saved -> "Saved"
        else -> "Ready"
    }
    fun monitorClick(): MonitorClick? =
        if (clickState.running) MonitorClick(clickState.bpm, clickState.beatsPerBar, countInBars) else null
    /** Every take starts silent: playback and the free-running click stop before the microphone opens. */
    fun startTake(backing: Project?, start: (MonitorClick?) -> Unit) {
        player?.stop()
        val monitor = monitorClick()
        click.stop()
        overdubbing = backing != null
        start(monitor)
    }
    val timerFrames = when {
        previewing -> recording.previewFrame
        active -> recording.frames
        current == null -> 0L
        seekPreview != null || playing || playhead > 0L -> playhead
        else -> current.totalFrames
    }
    val timerRate = if (active) recording.sampleRate else current?.sampleRate ?: recording.sampleRate
    val cue = when {
        !active -> null
        recording.inputSilenced -> "Android has silenced microphone input. Check microphone access and routing."
        recording.silenceWarning -> "Sustained silence detected. Check microphone access and routing."
        recording.level.isClipping -> "Clipping — move the microphone farther away."
        recording.level.isLow -> "Low signal — check microphone distance."
        else -> null
    }
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Kanatron", color = Synth.night, fontSize = 28.sp, fontWeight = FontWeight.Black,
                    letterSpacing = 2.sp, modifier = Modifier.weight(1f).padding(end = 8.dp)
                )
                StatusWord(status, recordingNow, paused, saved)
            }
            val recovered = draft
            if (recovered != null && !active) {
                SynthPanel {
                    Text(
                        "Recovered unsaved take · ${draftClock(recovered)}",
                        color = Synth.yellow,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextAction(
                            "Save",
                            {
                                player?.stop()
                                onSaveDraft(recovered)
                            },
                            Modifier.weight(1f),
                            description = "Save recovered take",
                            color = Synth.cyan
                        )
                        TextAction(
                            if (playing) "Pause" else "Play",
                            {
                                val p = player ?: return@TextAction
                                if (playing) {
                                    p.pause()
                                } else {
                                    val position = playerState?.positionFrames ?: 0L
                                    if (position !in 0L until recovered.totalFrames) p.seek(0L)
                                    p.play(recovered.totalFrames)
                                }
                            },
                            Modifier.weight(1f),
                            enabled = player != null,
                            description = if (playing) "Pause recovered take" else "Play recovered take",
                            color = Synth.cyan
                        )
                        TextAction(
                            "Discard",
                            { draftPrompt = DraftPrompt.DISCARD },
                            Modifier.weight(1f),
                            description = "Discard recovered take",
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
            SynthPanel {
                Text(current?.name ?: "No take yet", color = Synth.muted, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Text(
                    RecordingCore.formatClock(RecordingCore.elapsedMillis(timerFrames.coerceAtLeast(0L), timerRate)),
                    color = if (active) Synth.pink else Synth.cyan, fontSize = 56.sp, fontFamily = FontFamily.Monospace
                )
                if (active) {
                    LevelMeter(recording.level.peak, recording.level.isClipping, Modifier.fillMaxWidth().height(54.dp))
                } else {
                    MiniWave(peaks, current, playhead, Modifier.fillMaxWidth().height(96.dp))
                    if (current != null) {
                        val position = playhead.coerceIn(0L, current.totalFrames)
                        val total = current.totalFrames
                        Slider(
                            value = position.toFloat() / total.toFloat(),
                            onValueChange = { seekPreview = (it * total.toFloat()).roundToLong() },
                            onValueChangeFinished = {
                                seekPreview?.let { player?.seek(it) }
                                seekPreview = null
                            },
                            enabled = player != null,
                            modifier = Modifier.fillMaxWidth().semantics {
                                contentDescription = "Playback position"
                                stateDescription = "${clock(position, current.sampleRate)} of ${clock(total, current.sampleRate)}"
                            }
                        )
                    }
                }
                if (active) Notice("${recording.inputDevice} · ${recording.sampleRate} Hz")
                cue?.let { Notice(it) }
                if (overdubbing && active) Notice("Wired headphones are recommended. Bluetooth timing is unverified.")
                if (recording.silent && lastTakeShown) Notice("Silent recording saved to your library.", Synth.yellow)
                if (active || lastTakeShown) recording.interruptionNotice?.let { Notice(it, MaterialTheme.colorScheme.error) }
                if (active || lastTakeShown) recording.alignmentNotice?.let { Notice(it) }
                // startError is not tied to a take and has no other surface; never hide it.
                val takeError = recording.error.takeIf { active || lastTakeShown }
                (takeError ?: startError)?.let { Notice(it, MaterialTheme.colorScheme.error) }
                playerState?.error?.let { Notice(it, MaterialTheme.colorScheme.error) }
                clickState.error?.let { Notice(it, MaterialTheme.colorScheme.error) }
                if (phase == RecordingPhase.FAILED && lastTakeShown) {
                    Notice("A recoverable take is in your library.")
                }
                if (!microphoneGranted && !active) {
                    Notice(if (microphoneDenied) "Microphone access is off. The library remains available; allow access to record." else "Kanatron needs microphone access to record.")
                    if (microphoneDenied) TextAction("Open app settings", onOpenSettings, description = "Open app settings for microphone access", color = Synth.cyan)
                    if (shortcutRequested) Notice("On first use, Record now waits for microphone and notification permissions before recording.")
                }
                if (Build.VERSION.SDK_INT >= 33 && !notificationsGranted) {
                    Notice("Notifications are off. Android shows an active recording notice in Task Manager, but not in the notification drawer. Use the app controls to pause or stop.")
                }
                if (saved) {
                    TextAction("Open in Edit", onOpenInEdit, modifier = Modifier.fillMaxWidth(), description = "Open saved take in Edit", color = Synth.cyan)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                RoundButton(
                    if (previewing || (playing && !active)) SynthIcon.Pause else SynthIcon.Play,
                    when {
                        previewing -> "Stop"
                        paused -> "Play take so far"
                        playing && !active -> "Pause"
                        else -> "Play"
                    },
                    Synth.cyan,
                    onClick = {
                        if (paused) {
                            onPreview()
                            return@RoundButton
                        }
                        val p = player ?: return@RoundButton
                        if (playing) p.pause() else {
                            if (playerState!!.positionFrames !in 0L until current!!.totalFrames) p.seek(0L)
                            p.play(current!!.totalFrames)
                        }
                    },
                    size = 56.dp, enabled = paused || (player != null && current != null && !active),
                    description = when {
                        previewing -> "Stop"
                        paused -> "Play take so far"
                        playing -> "Pause playback"
                        else -> "Play recording"
                    },
                    state = when {
                        previewing -> "Playing take"
                        paused -> "Paused"
                        playing -> "Playing"
                        else -> status
                    }
                )
                RoundButton(
                    if (recordingNow) SynthIcon.Pause else SynthIcon.Rec, if (recordingNow) "Pause" else if (paused) "Resume" else "Record",
                    if (recordingNow) Synth.surfaceHi else Synth.pink,
                    onClick = {
                        when {
                            active -> onPauseResume()
                            draft != null -> draftPrompt = DraftPrompt.RECORD
                            else -> startTake(null, onRecord)
                        }
                    },
                    // While a take is starting there is no recorder to pause yet.
                    size = 72.dp, enabled = phase != RecordingPhase.STARTING, contentColor = if (recordingNow) Synth.text else Color.White,
                    description = when {
                        recordingNow -> "Pause recording"
                        paused -> "Resume recording"
                        else -> "Record"
                    },
                    state = status
                )
                RoundButton(
                    SynthIcon.Stop, "Save to Library", Synth.pink, onSave,
                    size = 56.dp, enabled = canSave, contentColor = Color.White,
                    description = "Save to Library", state = status
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Tile(
                    SynthIcon.Overdub, "Overdub",
                    {
                        val backing = current ?: return@Tile
                        if (backing.draft) draftPrompt = DraftPrompt.OVERDUB else startTake(backing) { onOverdub(backing, it) }
                    },
                    Modifier.weight(1f),
                    description = "Overdub over this recording",
                    enabled = current != null && current.tracks.size == 1 && !active
                )
                Tile(
                    SynthIcon.Click, "${clickState.bpm}", { if (clickState.running) click.stop() else click.start() }, Modifier.weight(1f),
                    selected = clickState.running, description = "Click ${clickState.bpm} BPM", enabled = !active
                )
                Tile(SynthIcon.Import, "Import", onImport, Modifier.weight(1f), description = "Import audio", enabled = !active)
                Tile(SynthIcon.Share, "Share", { current?.let(onShare) }, Modifier.weight(1f), enabled = current != null && !active && draft == null, description = "Share current take")
            }
            Row(Modifier.fillMaxWidth()) {
                TextAction(if (clickPanel) "Hide click settings" else "Click settings", { clickPanel = !clickPanel }, Modifier.weight(1f), enabled = !active)
                TextAction("Advanced", onCalibration, Modifier.weight(1f), enabled = !active, description = "Advanced timing calibration")
            }
            if (clickPanel && !active) SynthPanel {
                Stepper("${clickState.bpm}", { click.setBpm(clickState.bpm - 5) }, { click.setBpm(clickState.bpm + 5) }, "5 slower", "5 faster")
                BigButton("Tap", {
                    val now = System.currentTimeMillis()
                    if (tapTimes.isNotEmpty() && now - tapTimes.last() > 2000) tapTimes.clear()
                    tapTimes += now
                    while (tapTimes.size > 4) tapTimes.removeAt(0)
                    if (tapTimes.size >= 2) click.setBpm(Math.round(60_000.0 * (tapTimes.size - 1) / (tapTimes.last() - tapTimes.first())).toInt())
                }, Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (n in listOf(2, 3, 4, 6)) BigButton("$n", { click.setBeatsPerBar(n) }, Modifier.weight(1f).semantics { selected = clickState.beatsPerBar == n }, primary = clickState.beatsPerBar == n)
                }
                Text("Count-in", color = Synth.muted)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0, 1, 2).forEach { bars ->
                        BigButton(if (bars == 0) "Off" else "$bars bar${if (bars == 1) "" else "s"}", { countInBars = bars }, Modifier.weight(1f).semantics { selected = countInBars == bars }, primary = countInBars == bars)
                    }
                }
            }
        }
        val prompt = draftPrompt
        val promptedDraft = draft
        if (prompt == DraftPrompt.OVERDUB && promptedDraft != null) {
            AlertDialog(
                onDismissRequest = { draftPrompt = null },
                title = { Text("Save original to library first?") },
                text = { Text("This take is unsaved. Overdubbing saves it to your library, then records over it into a new recording.") },
                confirmButton = {
                    TextButton({
                        draftPrompt = null
                        startTake(promptedDraft) { onSaveAndOverdub(promptedDraft, it) }
                    }) { Text("Save and overdub") }
                },
                dismissButton = { TextButton({ draftPrompt = null }) { Text("Cancel") } }
            )
        } else if (prompt != null && promptedDraft != null) {
            val recordingAfter = prompt == DraftPrompt.RECORD
            AlertDialog(
                onDismissRequest = { draftPrompt = null },
                title = { Text("Discard unsaved take?") },
                text = { Text("The recovered take (${draftClock(promptedDraft)}) will be deleted.") },
                confirmButton = {
                    TextButton(
                        onClick = { draftPrompt = null },
                        modifier = Modifier.semantics { contentDescription = "Keep unsaved take" }
                    ) { Text("Keep") }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            draftPrompt = null
                            if (recordingAfter) {
                                startTake(null) { onDiscardAndRecord(promptedDraft, it) }
                            } else {
                                player?.stop()
                                onDiscardDraft(promptedDraft)
                            }
                        },
                        modifier = Modifier.semantics {
                            contentDescription = if (recordingAfter) "Discard unsaved take and record" else "Discard unsaved take"
                        }
                    ) { Text(if (recordingAfter) "Discard and record" else "Discard") }
                }
            )
        }
    }
}

private enum class DraftPrompt { DISCARD, RECORD, OVERDUB }

@Composable
private fun StatusWord(status: String, recording: Boolean, paused: Boolean, saved: Boolean) {
    val tint = when {
        recording -> Synth.pink
        paused -> Synth.yellow
        saved -> Synth.cyan
        else -> Synth.text
    }
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(Synth.night.copy(alpha = 0.88f)).padding(horizontal = 12.dp, vertical = 5.dp)
    ) { Text(status, color = tint, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun Notice(text: String, color: Color = Synth.muted) {
    Text(text, color = color, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun TextAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    description: String = text,
    color: Color = Synth.night
) {
    Text(
        text,
        color = if (enabled) color else color.copy(alpha = 0.5f),
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.heightIn(min = 48.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(vertical = 12.dp),
        textAlign = TextAlign.Center
    )
}

@Composable
internal fun LibraryScreen(
    projects: List<Project>,
    onOpen: (Project) -> Unit,
    onRename: ((Project, String) -> Unit)?,
    onDelete: ((Project) -> Unit)?,
    selectedId: String? = null,
    onShare: ((Project) -> Unit)? = null
) {
    var renameId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<Project?>(null) }
    var renameName by rememberSaveable { mutableStateOf("") }
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Library", color = Synth.night, fontSize = 28.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp,
                    modifier = Modifier.weight(1f)
                )
                if (onShare != null) {
                    val chosen = projects.firstOrNull { it.id == selectedId }
                    IconSquare(SynthIcon.Share, chosen?.let { "Share ${it.name}" } ?: "Choose a recording to share", { chosen?.let(onShare) }, enabled = chosen != null)
                }
            }
            if (projects.isEmpty()) {
                Text("Your recordings live here", color = Synth.night, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp))
            } else projects.sortedByDescending { it.createdAtEpochMillis }.forEach { project ->
                LibraryRow(
                    project,
                    selected = project.id == selectedId,
                    onOpen = { onOpen(project) },
                    onRename = onRename?.let { { renameName = project.name; renameId = project.id } },
                    onDelete = onDelete?.let { { deleteTarget = project } }
                )
            }
        }
    }
    val rename = projects.firstOrNull { it.id == renameId }
    if (rename != null) {
        AlertDialog(
            onDismissRequest = { renameId = null },
            title = { Text("Rename recording") },
            text = { OutlinedTextField(renameName, { renameName = it }, label = { Text("Name") }, singleLine = true) },
            confirmButton = { TextButton(onClick = { if (renameName.isNotBlank()) onRename?.invoke(rename, renameName); renameId = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renameId = null }) { Text("Cancel") } }
        )
    }
    val delete = deleteTarget
    if (delete != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete recording?") },
            text = { Text("${delete.name} will be removed. Audio still used by another recording is kept.") },
            confirmButton = { TextButton(onClick = { deleteTarget = null; onDelete?.invoke(delete) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun LibraryRow(project: Project, selected: Boolean, onOpen: () -> Unit, onRename: (() -> Unit)?, onDelete: (() -> Unit)?) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (selected) Synth.surfaceHi else Synth.surface)
            .border(if (selected) 2.dp else 1.dp, if (selected) Synth.cyan else Synth.line, RoundedCornerShape(16.dp)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            Modifier.weight(1f).heightIn(min = 68.dp).clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 10.dp)
                .clearAndSetSemantics {
                    contentDescription = "${project.name}, ${duration(project)}"
                    this.selected = selected
                },
            verticalArrangement = Arrangement.Center
        ) {
            Text(project.name, color = Synth.text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(if (project.silent) "${duration(project)} · silent" else duration(project), color = Synth.muted, fontFamily = FontFamily.Monospace)
        }
        if (onRename != null && onDelete != null) Box {
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
            val x = index * step + step / 2
            drawLine(
                Synth.cyan,
                androidx.compose.ui.geometry.Offset(x, center - peak.max / 32768f * center), androidx.compose.ui.geometry.Offset(x, center - peak.min / 32768f * center),
                strokeWidth = maxOf(2.dp.toPx(), step * 0.55f), cap = StrokeCap.Round
            )
        }
        if (position > 0L) {
            val x = size.width * position / total
            drawLine(Synth.pink, androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Offset(x, size.height), strokeWidth = 2.dp.toPx())
        }
    }
}

@Composable
private fun LevelMeter(peak: Float, clipping: Boolean, modifier: Modifier = Modifier) {
    Canvas(
        modifier.clip(RoundedCornerShape(12.dp)).background(Synth.night.copy(alpha = 0.8f))
            .semantics {
                contentDescription = "Microphone level ${(peak * 100).toInt()} percent${if (clipping) ", clipping" else ""}"
                progressBarRangeInfo = ProgressBarRangeInfo(peak.coerceIn(0f, 1f), 0f..1f)
            }
    ) {
        val count = 30
        val gap = 3.dp.toPx()
        val cellWidth = (size.width - gap * (count - 1)) / count
        val active = (peak.coerceIn(0f, 1f) * count).toInt().coerceAtLeast(if (peak > 0f) 1 else 0)
        repeat(count) { index ->
            val left = index * (cellWidth + gap)
            val base = when { clipping -> Synth.pink; index > count * 0.85f -> Synth.pink; index > count * 0.6f -> Synth.yellow; else -> Synth.cyan }
            drawRoundRect(if (index < active) base else base.copy(alpha = 0.12f), androidx.compose.ui.geometry.Offset(left, 6.dp.toPx()), androidx.compose.ui.geometry.Size(cellWidth, size.height - 12.dp.toPx()), cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
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

private fun duration(project: Project): String = clock(project.totalFrames, project.sampleRate)

private fun draftClock(project: Project): String = clock(project.totalFrames, project.sampleRate, hours = false)

private fun clock(frames: Long, sampleRate: Int, hours: Boolean = true): String =
    RecordingCore.formatClock(RecordingCore.elapsedMillis(frames, sampleRate), hours = hours)
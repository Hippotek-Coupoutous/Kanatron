package com.kanatron.app.ui

import android.content.Context
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kanatron.app.R
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
    onNew: () -> Unit,
    onShare: (Project) -> Unit,
    onCalibration: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    val wav = currentWav
    val loaded = rememberWavPlayer(context, wav)
    val player = loaded?.getOrNull()
    val playerState = player?.state?.collectAsState()?.value
    val clickState by click.state.collectAsState()
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
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
    val error = MaterialTheme.colorScheme.error
    // One line under the clock, most urgent first; the rest wait their turn.
    val notice: Pair<String, Color>? = listOfNotNull(
        (recording.error.takeIf { active || lastTakeShown } ?: startError)?.let { it to error },
        recording.interruptionNotice?.takeIf { active || lastTakeShown }?.let { it to error },
        (loaded?.exceptionOrNull()?.let { "This recording could not be opened." } ?: playerState?.error)?.let { it to error },
        clickState.error?.let { it to error },
        when {
            !active -> null
            recording.inputSilenced -> "Android has silenced microphone input. Check microphone access and routing."
            recording.silenceWarning -> "Sustained silence detected. Check microphone access and routing."
            recording.level.isClipping -> "Clipping — move the microphone farther away."
            recording.level.isLow -> "Low signal — check microphone distance."
            else -> null
        }?.let { it to Synth.yellow },
        "A recoverable take is in your library.".takeIf { phase == RecordingPhase.FAILED && lastTakeShown }?.let { it to Synth.muted },
        recording.alignmentNotice?.takeIf { active || lastTakeShown }?.let { it to Synth.muted },
        "Silent recording saved to your library.".takeIf { recording.silent && lastTakeShown }?.let { it to Synth.yellow },
        when {
            microphoneGranted || active -> null
            microphoneDenied -> "Microphone access is off. Allow it in app settings to record."
            shortcutRequested -> "Record now waits for microphone and notification permissions on first use."
            else -> "Kanatron needs microphone access to record."
        }?.let { it to Synth.muted },
        "Wired headphones are recommended. Bluetooth timing is unverified.".takeIf { overdubbing && active }?.let { it to Synth.muted },
        "Notifications are off, so pause and save from here.".takeIf { Build.VERSION.SDK_INT >= 33 && !notificationsGranted }?.let { it to Synth.muted },
        (if (active) "${recording.inputDevice} · ${recording.sampleRate} Hz" else null)?.let { it to Synth.muted }
    ).firstOrNull()
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SynthTopBar("Sketch", action = { StatusWord(status, recordingNow, paused, saved) })
            val recovered = draft
            if (recovered != null && !active) {
                SynthPanel {
                    Text("Recovered unsaved take · ${draftClock(recovered)}", color = Synth.yellow, fontWeight = FontWeight.SemiBold)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Key("Save", { player?.stop(); onSaveDraft(recovered) }, Modifier.weight(1f), description = "Save recovered take", height = KeyHeight.Small)
                        Key(
                            if (playing) "Pause" else "Play",
                            {
                                val p = player ?: return@Key
                                if (playing) p.pause() else {
                                    val position = playerState?.positionFrames ?: 0L
                                    if (position !in 0L until recovered.totalFrames) p.seek(0L)
                                    p.play(recovered.totalFrames)
                                }
                            },
                            Modifier.weight(1f), enabled = player != null, height = KeyHeight.Small,
                            description = if (playing) "Pause recovered take" else "Play recovered take"
                        )
                        Key("Discard", { draftPrompt = DraftPrompt.DISCARD }, Modifier.weight(1f), description = "Discard recovered take", height = KeyHeight.Small)
                    }
                }
            }
            SynthPanel {
                Text(current?.name ?: "New take", color = Synth.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Readout(
                    RecordingCore.formatClock(RecordingCore.elapsedMillis(timerFrames.coerceAtLeast(0L), timerRate)),
                    if (active) Synth.pink else Synth.cyan, 64.sp
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
                notice?.let { (text, color) -> Text(text, color = color, style = MaterialTheme.typography.bodySmall) }
                if (microphoneDenied && !microphoneGranted && !active) {
                    Key("App settings", onOpenSettings, Modifier.fillMaxWidth(), description = "Open app settings for microphone access", height = KeyHeight.Small)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Key(
                    when {
                        previewing -> "Stop"
                        playing && !active -> "Pause"
                        else -> "Play"
                    },
                    {
                        if (paused) {
                            onPreview()
                            return@Key
                        }
                        val p = player ?: return@Key
                        if (playing) p.pause() else {
                            if (playerState!!.positionFrames !in 0L until current!!.totalFrames) p.seek(0L)
                            p.play(current!!.totalFrames)
                        }
                    },
                    Modifier.weight(1f),
                    icon = if (previewing || (playing && !active)) SynthIcon.Pause else SynthIcon.Play,
                    face = Synth.cyan, height = KeyHeight.Transport,
                    enabled = paused || (player != null && current != null && !active),
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
                Key(
                    when {
                        recordingNow -> "Pause"
                        paused -> "Resume"
                        else -> "Rec"
                    },
                    {
                        when {
                            active -> onPauseResume()
                            draft != null -> draftPrompt = DraftPrompt.RECORD
                            else -> startTake(null, onRecord)
                        }
                    },
                    Modifier.weight(1f),
                    icon = if (recordingNow) SynthIcon.Pause else SynthIcon.Rec,
                    face = Synth.pink, height = KeyHeight.Transport,
                    // While a take is starting there is no recorder to pause yet.
                    enabled = phase != RecordingPhase.STARTING,
                    selected = recordingNow,
                    description = when {
                        recordingNow -> "Pause recording"
                        paused -> "Resume recording"
                        else -> "Record"
                    },
                    state = status
                )
                Key(
                    "New",
                    {
                        player?.stop()
                        if (draft != null) draftPrompt = DraftPrompt.NEW else onNew()
                    },
                    Modifier.weight(1f), icon = SynthIcon.New, height = KeyHeight.Transport,
                    enabled = !active && current != null, description = "Start a new take"
                )
                Key(
                    "Save", onSave, Modifier.weight(1f), icon = SynthIcon.Save, face = Synth.yellow, height = KeyHeight.Transport,
                    enabled = canSave, description = "Save to Library", state = status
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Key(
                    "Overdub",
                    {
                        val backing = current ?: return@Key
                        if (backing.draft) draftPrompt = DraftPrompt.OVERDUB else startTake(backing) { onOverdub(backing, it) }
                    },
                    Modifier.weight(1f), icon = SynthIcon.Overdub, height = KeyHeight.Transport,
                    description = "Overdub over this recording",
                    enabled = current != null && current.tracks.size == 1 && !active
                )
                Key(
                    "${clickState.bpm}", { if (clickState.running) click.stop() else click.start() }, Modifier.weight(1f),
                    icon = SynthIcon.Click, height = KeyHeight.Transport,
                    selected = clickState.running, description = "Click ${clickState.bpm} BPM", enabled = !active
                )
                Key(
                    "Share", { current?.let(onShare) }, Modifier.weight(1f), icon = SynthIcon.Share, height = KeyHeight.Transport,
                    enabled = current != null && !active && draft == null, description = "Share current take"
                )
                Key(
                    "More", { settingsOpen = !settingsOpen }, Modifier.weight(1f), icon = SynthIcon.More, height = KeyHeight.Transport,
                    selected = settingsOpen && !active, enabled = !active, description = "Click and timing settings"
                )
            }
            if (settingsOpen && !active) SynthPanel {
                Text("Click", style = MaterialTheme.typography.titleMedium)
                Stepper("${clickState.bpm}", { click.setBpm(clickState.bpm - 5) }, { click.setBpm(clickState.bpm + 5) }, "5 slower", "5 faster")
                Key("Tap tempo", {
                    val now = System.currentTimeMillis()
                    if (tapTimes.isNotEmpty() && now - tapTimes.last() > 2000) tapTimes.clear()
                    tapTimes += now
                    while (tapTimes.size > 4) tapTimes.removeAt(0)
                    if (tapTimes.size >= 2) click.setBpm(Math.round(60_000.0 * (tapTimes.size - 1) / (tapTimes.last() - tapTimes.first())).toInt())
                }, Modifier.fillMaxWidth())
                Text("Beats per bar", color = Synth.muted)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (n in listOf(2, 3, 4, 6)) Key("$n", { click.setBeatsPerBar(n) }, Modifier.weight(1f), selected = clickState.beatsPerBar == n, description = "$n beats per bar", height = KeyHeight.Small)
                }
                Text("Count-in", color = Synth.muted)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0, 1, 2).forEach { bars ->
                        val text = if (bars == 0) "Off" else "$bars bar${if (bars == 1) "" else "s"}"
                        Key(text, { countInBars = bars }, Modifier.weight(1f), selected = countInBars == bars, description = "Count-in $text", height = KeyHeight.Small)
                    }
                }
                Key("Timing calibration", onCalibration, Modifier.fillMaxWidth(), description = "Overdub timing calibration", height = KeyHeight.Small)
                val uriHandler = LocalUriHandler.current
                // A phone without a browser throws; the link is optional, so the tap does nothing.
                Key("Open-source licences", { runCatching { uriHandler.openUri("https://rootedrobot.com/kanatron/licences") } }, Modifier.fillMaxWidth(), height = KeyHeight.Small)
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
            val then = when (prompt) {
                DraftPrompt.RECORD -> "Discard and record"
                DraftPrompt.NEW -> "Discard and start new"
                else -> "Discard"
            }
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
                            when (prompt) {
                                DraftPrompt.RECORD -> startTake(null) { onDiscardAndRecord(promptedDraft, it) }
                                else -> {
                                    player?.stop()
                                    onDiscardDraft(promptedDraft)
                                    if (prompt == DraftPrompt.NEW) onNew()
                                }
                            }
                        },
                        modifier = Modifier.semantics { contentDescription = "$then unsaved take" }
                    ) { Text(then) }
                }
            )
        }
    }
}

private enum class DraftPrompt { DISCARD, RECORD, NEW, OVERDUB }

@Composable
private fun StatusWord(status: String, recording: Boolean, paused: Boolean, saved: Boolean) {
    val tint = when {
        recording -> Synth.pink
        paused -> Synth.yellow
        saved -> Synth.cyan
        else -> Synth.text
    }
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(Synth.night.copy(alpha = 0.88f)).padding(horizontal = 14.dp, vertical = 6.dp)
    ) { Text(status.uppercase(), color = tint, style = MaterialTheme.typography.labelMedium) }
}

/**
 * Recordings newest first. In the Library a tap highlights the recording every screen works on and opens its keys;
 * a picker passes no [selectedKeys] and takes the tap as its choice.
 */
@Composable
internal fun LibraryScreen(
    projects: List<Project>,
    onTap: (Project) -> Unit,
    selectedId: String? = null,
    header: (@Composable () -> Unit)? = null,
    selectedKeys: (@Composable (Project) -> Unit)? = null
) {
    LazyColumn(
        Modifier.fillMaxSize().safeDrawingPadding(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        header?.let { item { it() } }
        if (projects.isEmpty()) item {
            Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Image(painterResource(R.drawable.kanatron_logo), null, Modifier.height(180.dp))
                Text("Your recordings live here", color = Synth.night, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            }
        } else items(projects, key = Project::id) { project ->
            val selected = project.id == selectedId
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (selected) Synth.surfaceHi else Synth.surface)
                    .border(if (selected) 2.dp else 1.dp, if (selected) Synth.cyan else Synth.line, RoundedCornerShape(16.dp))
            ) {
                Column(
                    Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(role = Role.Button) { onTap(project) }.padding(horizontal = 16.dp, vertical = 10.dp)
                        .clearAndSetSemantics {
                            contentDescription = "${project.name}, ${duration(project)}"
                            this.selected = selected
                        },
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(project.name, color = if (selected) Synth.cyan else Synth.text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (project.silent) "${duration(project)} · silent" else duration(project), color = Synth.muted, fontFamily = FontFamily.Monospace)
                }
                if (selected && selectedKeys != null) Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) { selectedKeys(project) }
            }
        }
    }
}

/** Shown while startup recovery and the first library read run; their passes report no fraction, so the bar sweeps. */
@Composable
internal fun LoadingScreen() {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Image(painterResource(R.drawable.kanatron_splash), null, Modifier.fillMaxWidth(0.6f).weight(1f, fill = false))
        Text("KANATRON", style = MaterialTheme.typography.displayLarge, color = Synth.night, modifier = Modifier.padding(top = 24.dp, bottom = 16.dp))
        LinearProgressIndicator(
            Modifier.fillMaxWidth(0.6f).height(8.dp).clip(RoundedCornerShape(4.dp)),
            color = Synth.cyan,
            trackColor = Synth.night,
            gapSize = 0.dp
        )
        Text("Loading recordings…", color = Synth.night, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 12.dp))
    }
}

/** The Library's own header: the in-app logo, the wordmark and Import. */
@Composable
internal fun LibraryHeader(onImport: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Image(painterResource(R.drawable.kanatron_logo), null, Modifier.height(64.dp))
        Text("KANATRON", style = MaterialTheme.typography.headlineMedium, color = Synth.night, maxLines = 1, modifier = Modifier.weight(1f))
        Key("Import", onImport, icon = SynthIcon.Import, height = KeyHeight.Small, description = "Import audio")
    }
}

/** Play, Edit, Share and the rest for the highlighted recording. */
@Composable
internal fun LibraryKeys(
    project: Project,
    loaded: Result<WavPlayer>?,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit
) {
    val player = loaded?.getOrNull()
    val playerState = player?.state?.collectAsState()?.value
    val playing = playerState?.isPlaying == true
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by rememberSaveable(project.id) { mutableStateOf<String?>(null) }
    var deleting by remember(project.id) { mutableStateOf(false) }
    (loaded?.exceptionOrNull()?.let { "This recording could not be opened." } ?: playerState?.error)?.let {
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Key(
            if (playing) "Pause" else "Play",
            {
                val p = player ?: return@Key
                if (playing) p.pause() else {
                    if (p.state.value.positionFrames !in 0L until project.totalFrames) p.seek(0L)
                    p.play(project.totalFrames)
                }
            },
            Modifier.weight(1f), icon = if (playing) SynthIcon.Pause else SynthIcon.Play, face = Synth.cyan, height = KeyHeight.Small,
            enabled = player != null, description = if (playing) "Pause ${project.name}" else "Play ${project.name}"
        )
        Key("Edit", { player?.stop(); onEdit() }, Modifier.weight(1f), icon = SynthIcon.Trim, height = KeyHeight.Small, description = "Edit ${project.name}")
        Key("Share", { player?.stop(); onShare() }, Modifier.weight(1f), icon = SynthIcon.Share, height = KeyHeight.Small, description = "Share ${project.name}")
        Box {
            Key(null, { player?.stop(); menuOpen = true }, Modifier.width(52.dp), icon = SynthIcon.More, height = KeyHeight.Small, description = "More options for ${project.name}")
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text("Rename") }, onClick = { menuOpen = false; renaming = project.name })
                DropdownMenuItem(text = { Text("Delete") }, onClick = { menuOpen = false; deleting = true })
            }
        }
    }
    renaming?.let { name ->
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename recording") },
            text = { OutlinedTextField(name, { renaming = it }, label = { Text("Name") }, singleLine = true) },
            confirmButton = { TextButton(onClick = { if (name.isNotBlank()) onRename(name); renaming = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } }
        )
    }
    if (deleting) AlertDialog(
        onDismissRequest = { deleting = false },
        title = { Text("Delete recording?") },
        text = { Text("${project.name} will be removed. Audio still used by another recording is kept.") },
        confirmButton = { TextButton(onClick = { deleting = false; player?.stop(); onDelete() }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } }
    )
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

/** Opens [wav] off the main thread; null while loading or without a file, a failure when the WAV cannot be played. */
@Composable
internal fun rememberWavPlayer(context: Context, wav: File?, takesFocus: Boolean = true): Result<WavPlayer>? {
    val path = wav?.absolutePath
    var player by remember(path, takesFocus) { mutableStateOf<Result<WavPlayer>?>(null) }
    val holder = remember(path, takesFocus) { AtomicReference<WavPlayer?>(null) }
    LaunchedEffect(path, takesFocus) {
        val file = wav ?: return@LaunchedEffect
        val ownerJob = currentCoroutineContext()[Job]
        try {
            val loaded = withContext(Dispatchers.IO) {
                WavPlayer(context, file, takesFocus).also(holder::set)
            }
            if (ownerJob?.isActive == true) player = Result.success(loaded) else holder.getAndSet(null)?.release()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            player = Result.failure(failure)
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
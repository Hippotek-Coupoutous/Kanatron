package com.kanatron.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import com.kanatron.app.core.ExportFilename
import com.kanatron.app.core.Project
import com.kanatron.app.core.firstClip
import com.kanatron.app.core.ResumeStartGate
import com.kanatron.app.core.TrimRange
import com.kanatron.app.data.AppViewModel
import com.kanatron.app.data.ImportStage
import com.kanatron.app.data.MicrophoneAction
import com.kanatron.app.data.OverdubCalibrationStore
import com.kanatron.app.data.RecordingActivePath
import com.kanatron.app.data.Route
import com.kanatron.app.data.WavExporter
import com.kanatron.app.platform.MICROPHONE_PERMISSION_ERROR
import com.kanatron.app.playback.ClickEngine
import com.kanatron.app.playback.MonitorClick
import com.kanatron.app.recording.RecordingPhase
import com.kanatron.app.recording.RecordingService
import com.kanatron.app.ui.KanatronTheme
import com.kanatron.app.ui.LibraryScreen
import com.kanatron.app.ui.EditScreen
import com.kanatron.app.ui.MissingAudioScreen
import com.kanatron.app.ui.SynthTopBar
import com.kanatron.app.ui.OverdubCalibrationScreen
import com.kanatron.app.ui.SketchScreen
import com.kanatron.app.ui.SpliceScreen
import com.kanatron.app.ui.Synth
import com.kanatron.app.ui.SynthGlyph
import com.kanatron.app.ui.SynthIcon
import com.kanatron.app.ui.TunerScreen
import com.kanatron.app.ui.shareExport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val click = ClickEngine()
    private val recordingStartGate = ResumeStartGate()
    private var shortcutRequestPending = false
    private var shortcutRecordAction: (() -> Unit)? = null
    private lateinit var appViewModel: AppViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appViewModel = ViewModelProvider(this)[AppViewModel::class.java]
        consumeRecordNowIntent(intent)
        appViewModel.initialize()
        enableEdgeToEdge()
        updatePermissionState()

        fun continueMicrophoneAction(action: MicrophoneAction) {
            if (action == MicrophoneAction.TUNER) {
                appViewModel.clearPendingRecord()
                appViewModel.navigate(Route.TUNER)
            } else startRecordingService()
        }
        lateinit var requestMicrophone: (MicrophoneAction) -> Unit
        val microphoneLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            appViewModel.onMicrophonePermissionResult(granted)?.let(::continueMicrophoneAction)
        }
        requestMicrophone = { action ->
            appViewModel.awaitMicrophonePermission(action)
            if (microphoneAllowed()) {
                appViewModel.markMicrophoneGranted()
                continueMicrophoneAction(action)
            } else microphoneLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
        val notificationLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            updatePermissionState()
            requestMicrophone(MicrophoneAction.RECORD)
        }
        val openAudioDocument = registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri != null) {
                appViewModel.setImportNotice(null)
                runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                appViewModel.startImport(uri)
            } else {
                appViewModel.setImportNotice("Import cancelled. No recording was added.")
            }
        }
        val requestRecordPermissions = {
            appViewModel.awaitMicrophonePermission(MicrophoneAction.RECORD)
            appViewModel.setStartError(null)
            if (!notificationsAllowed()) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else requestMicrophone(MicrophoneAction.RECORD)
        }
        val askToRecord: (String?, MonitorClick?) -> Unit = { overdubProjectId, click ->
            appViewModel.setPendingRecord(overdubProjectId, click)
            appViewModel.awaitMicrophonePermission(MicrophoneAction.RECORD)
            appViewModel.setStartError(null)
            if (microphoneAllowed()) {
                appViewModel.markMicrophoneGranted()
                requestRecordPermissions()
            } else appViewModel.setMicrophoneRationale(true)
        }
        val askToTune = {
            appViewModel.clearPendingRecord()
            appViewModel.awaitMicrophonePermission(MicrophoneAction.TUNER)
            if (microphoneAllowed()) {
                appViewModel.markMicrophoneGranted()
                appViewModel.awaitMicrophonePermission(null)
            } else appViewModel.setMicrophoneRationale(true)
        }
        shortcutRecordAction = {
            // Sketch offers the recovered take first; another recording must not start over it.
            if (appViewModel.state.value.drafts.isEmpty()) askToRecord(null, null) else appViewModel.clearShortcutRequest()
        }

        setContent {
            KanatronTheme {
                KanatronApp(
                    hostLifecycle = lifecycle,
                    appViewModel = appViewModel,
                    click = click,
                    onContinueMicrophoneRationale = {
                        appViewModel.setMicrophoneRationale(false)
                        when (appViewModel.state.value.pendingMicrophoneAction ?: MicrophoneAction.RECORD) {
                            MicrophoneAction.RECORD -> requestRecordPermissions()
                            MicrophoneAction.TUNER -> requestMicrophone(MicrophoneAction.TUNER)
                        }
                    },
                    onDismissMicrophoneRationale = { appViewModel.dismissMicrophoneRationale() },
                    onRecordRequest = askToRecord,
                    onTunerMicRequest = askToTune,
                    onOpenSettings = { openAppSettings() },
                    onImportRequest = {
                        appViewModel.setImportNotice(null)
                        openAudioDocument.launch(arrayOf("audio/*"))
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionState()
        if (recordingStartGate.onResume(appViewModel.state.value.microphoneGranted)) startRecordingServiceNow()
    }

    override fun onPause() {
        super.onPause()
        // A metronome must not keep ticking behind a backgrounded app.
        click.stop()
    }

    override fun onDestroy() {
        click.release()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeRecordNowIntent(intent)
    }

    private fun consumeRecordNowIntent(intent: Intent) {
        if (intent.action == ACTION_RECORD_NOW) {
            appViewModel.startShortcutRecord()
            shortcutRequestPending = true
            // Consume the action so an in-process redelivery cannot start a second take.
            intent.action = Intent.ACTION_MAIN
            window.decorView.post { dispatchShortcutRecordRequest() }
        }
    }

    private fun dispatchShortcutRecordRequest() {
        val action = shortcutRecordAction ?: return
        if (!shortcutRequestPending) return
        shortcutRequestPending = false
        action()
    }

    private fun microphoneAllowed() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun notificationsAllowed() =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun updatePermissionState() {
        appViewModel.updatePermissions(microphoneAllowed(), notificationsAllowed())
    }

    private fun startRecordingService() {
        if (!recordingStartGate.request(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))) return
        startRecordingServiceNow()
    }

    private fun startRecordingServiceNow() {
        // Every take starts here; the metronome must be silent before the microphone opens.
        click.stop()
        appViewModel.clearShortcutRequest()
        val pending = appViewModel.state.value
        runCatching {
            val request = Intent(this, RecordingService::class.java).setAction(RecordingService.ACTION_START)
            pending.pendingOverdubProjectId?.let { request.putExtra(RecordingService.EXTRA_OVERDUB_PROJECT_ID, it) }
            pending.pendingClick?.let {
                request.putExtra(RecordingService.EXTRA_OVERDUB_CLICK_BPM, it.bpm)
                request.putExtra(RecordingService.EXTRA_OVERDUB_CLICK_BEATS, it.beatsPerBar)
                request.putExtra(RecordingService.EXTRA_OVERDUB_COUNT_IN_BARS, it.countInBars)
            }
            ContextCompat.startForegroundService(this, request)
            appViewModel.clearPendingRecord()
        }.onFailure {
            appViewModel.setStartError(
                if (it is SecurityException) MICROPHONE_PERMISSION_ERROR
                else it.message ?: "Could not start recording service"
            )
        }
    }

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }

    companion object {
        const val ACTION_RECORD_NOW = "com.kanatron.app.action.RECORD_NOW"
    }
}

private fun playbackAllowed(recordingPhase: RecordingPhase): Boolean =
    recordingPhase !in setOf(RecordingPhase.STARTING, RecordingPhase.RECORDING, RecordingPhase.PAUSED)

@Composable
private fun KanatronApp(
    hostLifecycle: Lifecycle,
    appViewModel: AppViewModel,
    click: ClickEngine,
    onContinueMicrophoneRationale: () -> Unit,
    onDismissMicrophoneRationale: () -> Unit,
    onRecordRequest: (String?, MonitorClick?) -> Unit,
    onTunerMicRequest: () -> Unit,
    onOpenSettings: () -> Unit,
    onImportRequest: () -> Unit
) {
    val context = LocalContext.current
    val calibrationStore = remember(context) { OverdubCalibrationStore(context) }
    val repository = appViewModel.repository
    val appState by appViewModel.state.collectAsState()
    val recording by RecordingService.state.collectAsState()
    val projects = appState.projects
    val unreadableProjectCount = appState.unreadableProjectCount
    val route = appState.route
    val selectedId = appState.selectedId
    val homeId = appState.homeId
    val selected = appState.selectedProject
    var dismissedTakeError by remember { mutableStateOf<String?>(null) }
    var shareFailure by remember { mutableStateOf<String?>(null) }
    var shareFailureProject by remember { mutableStateOf<Project?>(null) }
    var discardAndThen by remember { mutableStateOf<(() -> Unit)?>(null) }
    /** Unsaved cuts or a chosen splice are only dropped after the user agrees. */
    fun leavingEdits(then: () -> Unit) {
        when {
            appState.editSaving -> Unit
            route == Route.SPLICE && appState.splice.size == 2 -> discardAndThen = { appViewModel.closeSplice(); then() }
            route == Route.SPLICE -> { appViewModel.closeSplice(); then() }
            appState.edit != null -> discardAndThen = { appViewModel.discardEdits(); then() }
            else -> then()
        }
    }
    val scope = rememberCoroutineScope()

    fun share(project: Project) {
        shareFailure = null
        shareFailureProject = project
        scope.launch {
            try {
                val shareFile = withContext(Dispatchers.IO) {
                    val source = repository.playbackFile(project)
                    val file = repository.exportFile(
                        project.id,
                        ExportFilename.wav(project.name, project.name),
                        source.length()
                    ) { target ->
                        WavExporter.export(source, target, TrimRange(0L, project.totalFrames))
                    }
                    file
                }
                shareExport(context, shareFile, "Share recording")
                shareFailureProject = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                shareFailure = failure.message ?: "Could not prepare this recording for sharing."
            }
        }
    }

    LaunchedEffect(recording.phase) {
        if (recording.phase != RecordingPhase.FAILED) dismissedTakeError = null
    }

    LaunchedEffect(recording.projectId, recording.phase) {
        val finishedProjectId = recording.projectId
        if (finishedProjectId != null && (recording.phase == RecordingPhase.COMPLETE || recording.phase == RecordingPhase.FAILED)) {
            // Select the finished take; an overdub is a new recording the library has not read yet.
            appViewModel.refreshLibrary(finishedProjectId)
            appViewModel.setHome(finishedProjectId)
        }
    }
    LaunchedEffect(appState.importedProjectId) {
        appState.importedProjectId?.let { id ->
            appViewModel.selectProject(id)
            appViewModel.setHome(id)
            appViewModel.navigate(Route.EDIT)
            appViewModel.consumeImportedProject(id)
        }
    }

    // A running take keeps its controls (and the microphone) on Sketch.
    val takeRunning = recording.phase in setOf(RecordingPhase.STARTING, RecordingPhase.RECORDING, RecordingPhase.PAUSED)

    // The click belongs to idle Sketch only; leaving it silences the metronome.
    LaunchedEffect(route) { if (route != Route.SKETCH) click.stop() }

    BackHandler(enabled = route != Route.LIBRARY) {
        when (route) {
            Route.SKETCH -> if (!takeRunning) appViewModel.leave(Route.LIBRARY)
            Route.TUNER -> appViewModel.leave(Route.LIBRARY)
            Route.EDIT -> leavingEdits { appViewModel.leave(Route.LIBRARY) }
            Route.EDIT_IMPORT -> appViewModel.navigate(Route.EDIT)
            Route.SPLICE -> leavingEdits {}
            Route.SPLICE_PICK -> appViewModel.navigate(Route.SPLICE)
            Route.OVERDUB_CALIBRATION -> appViewModel.leave(Route.SKETCH)
            Route.LIBRARY -> Unit
        }
    }

    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier.weight(1f).then(
                // The bottom bar covers only a bottom navigation bar; in landscape it sits at the side.
                if (route.isTopLevel) Modifier.consumeWindowInsets(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)) else Modifier
            )
        ) {
            when (route) {
                Route.LIBRARY -> LibraryScreen(
                    projects = projects,
                    // A tap chooses the recording that Sketch, Edit and Share work on.
                    onOpen = {
                        appViewModel.selectProject(it.id)
                        appViewModel.setHome(it.id)
                    },
                    onRename = { project, name -> appViewModel.renameProject(project.id, name) },
                    onDelete = { appViewModel.deleteProject(it.id) },
                    selectedId = selectedId,
                    onShare = ::share
                )
                Route.SKETCH -> {
                    val visibleCurrent = projects.firstOrNull { it.id == (homeId ?: selectedId) } ?: projects.maxByOrNull { it.createdAtEpochMillis }
                    val draft = appState.drafts.firstOrNull()
                    // An unsaved take is what Sketch shows and plays; it is saved before it can be overdubbed.
                    val current = draft ?: visibleCurrent
                    LaunchedEffect(current?.id) { appViewModel.loadPlaybackFile(current?.id) }
                    SketchScreen(
                        recording = recording,
                        shortcutRequested = appState.shortcutRequested,
                        microphoneGranted = appState.microphoneGranted,
                        microphoneDenied = appState.microphoneDenied,
                        notificationsGranted = appState.notificationGranted,
                        startError = appState.startError,
                        current = current,
                        currentWav = current?.let { appState.audioFiles[it.id] },
                        draft = draft,
                        click = click,
                        onRecord = { clickSettings -> onRecordRequest(null, clickSettings) },
                        onOverdub = { backing, clickSettings -> onRecordRequest(backing.id, clickSettings) },
                        onSaveDraft = { appViewModel.saveRecoveredDraft(it.id) },
                        onSaveAndOverdub = { backing, clickSettings ->
                            appViewModel.saveRecoveredDraft(backing.id) { onRecordRequest(backing.id, clickSettings) }
                        },
                        onDiscardDraft = { appViewModel.discardRecoveredDraft(it.id) },
                        onDiscardAndRecord = { discarded, clickSettings ->
                            appViewModel.discardRecoveredDraft(discarded.id) { onRecordRequest(null, clickSettings) }
                        },
                        onPauseResume = {
                            val action = if (recording.phase == RecordingPhase.PAUSED) RecordingService.ACTION_RESUME else RecordingService.ACTION_PAUSE
                            context.startService(Intent(context, RecordingService::class.java).setAction(action))
                        },
                        onPreview = { context.startService(Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_PREVIEW)) },
                        onSave = { context.startService(Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_STOP)) },
                        onShare = ::share,
                        onImport = onImportRequest,
                        onCalibration = { appViewModel.navigate(Route.OVERDUB_CALIBRATION) },
                        onOpenSettings = onOpenSettings,
                        onOpenInEdit = {
                            val projectId = recording.projectId ?: visibleCurrent?.id
                            if (projectId != null) {
                                appViewModel.selectProject(projectId)
                                appViewModel.setHome(projectId)
                                appViewModel.navigate(Route.EDIT)
                            }
                        }
                    )
                }
                Route.EDIT -> {
                    // Edit works on the chosen recording, else the newest unsaved take, else the newest recording.
                    val known = selectedId?.takeIf { id -> projects.any { it.id == id } || appState.drafts.any { it.id == id } }
                    val subjectId = known ?: appState.drafts.firstOrNull()?.id ?: projects.firstOrNull()?.id
                    LaunchedEffect(subjectId) { if (subjectId != null && subjectId != selectedId) appViewModel.selectProject(subjectId) }
                    val project = selected?.takeIf { it.id == subjectId }
                    val sourcesMissing by produceState<Boolean?>(null, project) {
                        value = project?.let { withContext(Dispatchers.IO) { repository.missingSources(it).isNotEmpty() } }
                    }
                    // Edit rebuilds the recording from its one source, so anything else it would silently drop.
                    val editable = project?.tracks?.singleOrNull()?.let { track ->
                        val clip = track.clips.singleOrNull()
                        clip != null && clip.positionFrames == 0L && clip.rateCorrectionPpm == 0 && clip.source.sampleRate == project.sampleRate
                    } == true
                    when {
                        subjectId == null -> EditMessage("Record or import a recording to edit it here.") { appViewModel.leave(Route.LIBRARY) }
                        project == null -> EditMessage(appState.selectionError ?: "Loading recording…") { appViewModel.leave(Route.LIBRARY) }
                        sourcesMissing == true -> MissingAudioScreen(project.name) { appViewModel.leave(Route.LIBRARY) }
                        !editable -> EditMessage("${project.name} was placed or speed-corrected by an older version and cannot be edited here.") { appViewModel.leave(Route.LIBRARY) }
                        else -> {
                            val session = appState.edit?.takeIf { it.projectId == project.id }
                            EditScreen(
                                project = project,
                                pieces = session?.pieces ?: listOf(project.firstClip.trim),
                                canUndo = session != null,
                                repository = repository,
                                playbackAllowed = playbackAllowed(recording.phase),
                                saving = appState.editSaving,
                                error = appState.editError ?: appState.libraryError,
                                onCut = { appViewModel.cut(project, it) },
                                onUndo = appViewModel::undoCut,
                                onSave = { asNew -> appViewModel.saveEdits(project, asNew) },
                                onImport = { appViewModel.navigate(Route.EDIT_IMPORT) },
                                onSplice = { appViewModel.startSplice(project) },
                                onBack = { leavingEdits { appViewModel.leave(Route.LIBRARY) } }
                            )
                        }
                    }
                }
                Route.SPLICE -> SpliceScreen(
                    parts = appState.splice,
                    repository = repository,
                    playbackAllowed = playbackAllowed(recording.phase),
                    saving = appState.editSaving,
                    error = appState.editError,
                    onRange = appViewModel::setSpliceRange,
                    onChooseSecond = { appViewModel.navigate(Route.SPLICE_PICK) },
                    onSwap = appViewModel::swapSplice,
                    onSave = appViewModel::saveSplice,
                    onClose = { leavingEdits {} }
                )
                Route.SPLICE_PICK -> Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                    Box(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp)) {
                        SynthTopBar("Join", { appViewModel.navigate(Route.SPLICE) }, backLabel = "Splice")
                    }
                    val first = appState.splice.firstOrNull()?.project?.id
                    LibraryScreen(
                        projects = projects.filter { it.id != first && it.tracks.size == 1 && it.tracks[0].clips.size == 1 },
                        onOpen = appViewModel::addToSplice,
                        onRename = null,
                        onDelete = null
                    )
                }
                Route.EDIT_IMPORT -> Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                    Box(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp)) {
                        SynthTopBar("Import", { appViewModel.navigate(Route.EDIT) }, backLabel = "Edit")
                    }
                    LibraryScreen(
                        projects = projects,
                        onOpen = { picked ->
                            val open = {
                                appViewModel.discardEdits()
                                appViewModel.selectProject(picked.id)
                                appViewModel.navigate(Route.EDIT)
                            }
                            if (picked.id == selectedId) appViewModel.navigate(Route.EDIT) else leavingEdits(open)
                        },
                        onRename = { project, name -> appViewModel.renameProject(project.id, name) },
                        onDelete = { appViewModel.deleteProject(it.id) }
                    )
                }
                Route.TUNER -> TunerScreen(
                    microphoneGranted = appState.microphoneGranted,
                    microphoneDenied = appState.microphoneDenied,
                    lifecycle = hostLifecycle,
                    microphoneBlockedReason = when {
                        recording.phase in setOf(RecordingPhase.STARTING, RecordingPhase.RECORDING, RecordingPhase.PAUSED) || RecordingActivePath.path != null ->
                            "Stop the active recording before tuning."
                        else -> null
                    },
                    onRequestMicrophone = onTunerMicRequest,
                    onOpenSettings = onOpenSettings,
                    onBack = { appViewModel.leave(Route.LIBRARY) }
                )
                Route.OVERDUB_CALIBRATION -> OverdubCalibrationScreen(
                    route = recording.overdubRoute ?: calibrationStore.lastRoute(),
                    onBack = { appViewModel.leave(Route.SKETCH) }
                )
            }
        }
        if (route.isTopLevel) TopLevelBar(route, itemsEnabled = !appState.editSaving && !takeRunning) { destination ->
            val go = { if (destination == Route.LIBRARY) appViewModel.leave(Route.LIBRARY) else appViewModel.navigate(destination) }
            if (route == Route.EDIT) leavingEdits(go) else go()
        }
    }
    val takeErrorKey = "${recording.projectId ?: "no-project"}:${recording.error}"
    val hasTakeError = recording.phase == RecordingPhase.FAILED && recording.error != null &&
        dismissedTakeError != takeErrorKey
    val activeSelectionError = appState.selectionError?.takeIf { appState.selectionErrorForId == selectedId }
    when {
        appState.importProgress != null -> {
            val progress = appState.importProgress!!
            AlertDialog(
                onDismissRequest = { appViewModel.cancelImport() },
                title = {
                    Text(when (progress.stage) {
                        ImportStage.COPYING -> "Staging audio"
                        ImportStage.DECODING -> "Decoding audio"
                        else -> "Saving audio"
                    })
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (progress.totalBytes == null) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        else LinearProgressIndicator(
                            progress = { (progress.completedBytes.toFloat() / progress.totalBytes.coerceAtLeast(1)).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(if (progress.totalBytes == null) "Working…" else "${(progress.completedBytes.toFloat() / progress.totalBytes.coerceAtLeast(1) * 100).toInt()}%")
                    }
                },
                confirmButton = { TextButton(onClick = { appViewModel.cancelImport() }) { Text("Cancel import") } }
            )
        }
        appState.microphoneRationale -> {
            AlertDialog(
                onDismissRequest = onDismissMicrophoneRationale,
                title = { Text(if (appState.pendingMicrophoneAction == MicrophoneAction.TUNER) "Before tuning" else "Before recording") },
                text = {
                    Text(
                        if (appState.pendingMicrophoneAction == MicrophoneAction.TUNER) {
                            "Kanatron uses microphone access to detect pitch while the tuner is open. It stops listening when you leave."
                        } else {
                            "Kanatron uses microphone access to record a take or record over a backing track. You can import, play, and manage recordings without it."
                        }
                    )
                },
                confirmButton = { TextButton(onClick = onContinueMicrophoneRationale) { Text("Continue") } },
                dismissButton = { TextButton(onClick = onDismissMicrophoneRationale) { Text("Not now") } }
            )
        }
        appState.importFailure != null -> {
            val failure = appState.importFailure!!
            AlertDialog(
                onDismissRequest = { appViewModel.dismissImportFailure() },
                title = { Text("Import") },
                text = { Text(failure) },
                confirmButton = { TextButton(onClick = { appViewModel.dismissImportFailure() }) { Text("OK") } }
            )
        }
        appState.importNotice != null -> {
            val notice = appState.importNotice!!
            AlertDialog(
                onDismissRequest = { appViewModel.setImportNotice(null) },
                title = { Text("Import") },
                text = { Text(notice) },
                confirmButton = { TextButton(onClick = { appViewModel.setImportNotice(null) }) { Text("OK") } }
            )
        }
        unreadableProjectCount > 0 -> {
            AlertDialog(
                onDismissRequest = { appViewModel.dismissUnreadableCount() },
                title = { Text("Recording data could not be read") },
                text = { Text("$unreadableProjectCount project file(s) are damaged or unsupported. Their WAV files were left in storage.") },
                confirmButton = { TextButton(onClick = { appViewModel.dismissUnreadableCount() }) { Text("OK") } }
            )
        }
        appState.libraryError != null -> {
            val error = appState.libraryError!!
            AlertDialog(
                onDismissRequest = { appViewModel.consumeLibraryError() },
                title = { Text("Library") },
                text = { Text(error) },
                confirmButton = {
                    TextButton(onClick = {
                        appViewModel.consumeLibraryError()
                        appViewModel.refreshLibrary()
                    }) { Text("Retry") }
                },
                dismissButton = { TextButton(onClick = { appViewModel.consumeLibraryError() }) { Text("Close") } }
            )
        }
        activeSelectionError != null -> {
            val error = activeSelectionError
            AlertDialog(
                onDismissRequest = { appViewModel.selectProject(null) },
                title = { Text("Recording") },
                text = { Text(error) },
                confirmButton = {
                    TextButton(onClick = { selectedId?.let { appViewModel.selectProject(it, force = true) } }) { Text("Retry") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        appViewModel.selectProject(null)
                        appViewModel.leave(Route.LIBRARY)
                    }) { Text("Back to library") }
                }
            )
        }
        discardAndThen != null -> {
            AlertDialog(
                onDismissRequest = { discardAndThen = null },
                title = { Text("Discard changes?") },
                text = { Text(if (route == Route.SPLICE) "The splice is not saved yet." else "Your unsaved cuts will be lost.") },
                confirmButton = {
                    TextButton(onClick = {
                        val then = discardAndThen
                        discardAndThen = null
                        then?.invoke()
                    }) { Text("Discard") }
                },
                dismissButton = { TextButton(onClick = { discardAndThen = null }) { Text("Keep editing") } }
            )
        }
        shareFailure != null -> {
            val error = shareFailure!!
            AlertDialog(
                onDismissRequest = { shareFailure = null; shareFailureProject = null },
                title = { Text("Share") },
                text = { Text(error) },
                confirmButton = {
                    TextButton(onClick = { shareFailureProject?.let(::share) }) { Text("Retry") }
                },
                dismissButton = { TextButton(onClick = { shareFailure = null; shareFailureProject = null }) { Text("Close") } }
            )
        }
        hasTakeError -> {
            AlertDialog(
                onDismissRequest = { dismissedTakeError = takeErrorKey },
                title = { Text(if (recording.projectId != null) "Recording saved with a problem" else "Recording stopped") },
                text = {
                    Text(if (recording.projectId != null) "${recording.error} The library entry was retained, but the take did not finalize cleanly."
                    else recording.error.orEmpty())
                },
                confirmButton = { TextButton(onClick = { dismissedTakeError = takeErrorKey }) { Text("OK") } }
            )
        }
    }
}

private data class TopDestination(val route: Route, val label: String, val icon: SynthIcon)

private val TOP_DESTINATIONS = listOf(
    TopDestination(Route.LIBRARY, "Library", SynthIcon.Library),
    TopDestination(Route.SKETCH, "Sketch", SynthIcon.Sketch),
    TopDestination(Route.EDIT, "Edit", SynthIcon.Trim),
    TopDestination(Route.TUNER, "Tuner", SynthIcon.Tuner)
)

/** What Edit shows when it has nothing it can edit. */
@Composable
private fun EditMessage(text: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SynthTopBar("Edit", onBack, backLabel = "Library")
        Text(text, color = Synth.night)
    }
}

@Composable
private fun TopLevelBar(current: Route, itemsEnabled: Boolean, onNavigate: (Route) -> Unit) {
    NavigationBar(containerColor = Synth.night, contentColor = Synth.text) {
        TOP_DESTINATIONS.forEach { destination ->
            val selected = destination.route == current
            NavigationBarItem(
                selected = selected,
                enabled = itemsEnabled,
                onClick = { if (!selected) onNavigate(destination.route) },
                icon = { SynthGlyph(destination.icon, if (selected) Synth.cyan else Synth.muted) },
                label = { Text(destination.label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Synth.cyan,
                    selectedTextColor = Synth.cyan,
                    indicatorColor = Synth.surfaceHi,
                    unselectedIconColor = Synth.muted,
                    unselectedTextColor = Synth.muted
                )
            )
        }
    }
}

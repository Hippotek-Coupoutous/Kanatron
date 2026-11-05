package com.ottomusic.sketch

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
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.ottomusic.sketch.core.ExportFilename
import com.ottomusic.sketch.core.Project
import com.ottomusic.sketch.core.ResumeStartGate
import com.ottomusic.sketch.core.TrimRange
import com.ottomusic.sketch.data.AppViewModel
import com.ottomusic.sketch.data.ImportStage
import com.ottomusic.sketch.data.MicrophoneAction
import com.ottomusic.sketch.data.OverdubCalibrationStore
import com.ottomusic.sketch.data.RecordingActivePath
import com.ottomusic.sketch.data.Route
import com.ottomusic.sketch.data.WavExporter
import com.ottomusic.sketch.platform.MICROPHONE_PERMISSION_ERROR
import com.ottomusic.sketch.playback.ClickEngine
import com.ottomusic.sketch.playback.MonitorClick
import com.ottomusic.sketch.recording.RecordingPhase
import com.ottomusic.sketch.recording.RecordingService
import com.ottomusic.sketch.ui.ArrangeScreen
import com.ottomusic.sketch.ui.AudioSketchTheme
import com.ottomusic.sketch.ui.LibraryScreen
import com.ottomusic.sketch.ui.MixEntryScreen
import com.ottomusic.sketch.ui.MixScreen
import com.ottomusic.sketch.ui.OverdubCalibrationScreen
import com.ottomusic.sketch.ui.RecordScreen
import com.ottomusic.sketch.ui.SketchScreen
import com.ottomusic.sketch.ui.Synth
import com.ottomusic.sketch.ui.SynthGlyph
import com.ottomusic.sketch.ui.SynthIcon
import com.ottomusic.sketch.ui.TrimScreen
import com.ottomusic.sketch.ui.TunerScreen
import com.ottomusic.sketch.ui.shareExport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val recordingStartGate = ResumeStartGate()
    private var shortcutRequestPending = false
    private var shortcutRecordAction: (() -> Unit)? = null
    private lateinit var appViewModel: AppViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appViewModel = ViewModelProvider(this)[AppViewModel::class.java]
        consumeRecordNowIntent(intent)
        appViewModel.initialize()
        savedInstanceState?.getString(STATE_PENDING_IMPORT_URI)?.let { savedUri ->
            appViewModel.startImport(Uri.parse(savedUri), restored = true)
        }
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
        shortcutRecordAction = { askToRecord(null, null) }

        setContent {
            AudioSketchTheme {
                AudioSketchApp(
                    hostLifecycle = lifecycle,
                    appViewModel = appViewModel,
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
        window.decorView.post { dispatchShortcutRecordRequest() }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionState()
        if (recordingStartGate.onResume(appViewModel.state.value.microphoneGranted)) startRecordingServiceNow()
        if (shortcutRequestPending) window.decorView.post { dispatchShortcutRecordRequest() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeRecordNowIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        appViewModel.state.value.importUri?.let { outState.putString(STATE_PENDING_IMPORT_URI, it.toString()) }
        super.onSaveInstanceState(outState)
    }

    private fun consumeRecordNowIntent(intent: Intent) {
        if (intent.action == ACTION_RECORD_NOW || intent.getBooleanExtra(EXTRA_RECORD_NOW, false)) {
            appViewModel.startShortcutRecord()
            shortcutRequestPending = true
            intent.removeExtra(EXTRA_RECORD_NOW)
            if (intent.action == ACTION_RECORD_NOW) intent.action = Intent.ACTION_MAIN
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
        const val EXTRA_RECORD_NOW = "com.ottomusic.sketch.extra.RECORD_NOW"
        const val ACTION_RECORD_NOW = "com.ottomusic.sketch.action.RECORD_NOW"
        private const val STATE_PENDING_IMPORT_URI = "pendingImportUri"
    }
}

private fun playbackAllowed(recordingPhase: RecordingPhase): Boolean =
    recordingPhase !in setOf(RecordingPhase.STARTING, RecordingPhase.RECORDING, RecordingPhase.PAUSED)

@Composable
private fun AudioSketchApp(
    hostLifecycle: Lifecycle,
    appViewModel: AppViewModel,
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
    val silentRecoveryFiles = appState.silentRecoveryFiles
    var silentRecoveryFailure by remember { mutableStateOf<String?>(null) }
    var shareFailure by remember { mutableStateOf<String?>(null) }
    var shareFailureProject by remember { mutableStateOf<Project?>(null) }
    val scope = rememberCoroutineScope()
    val click = remember { ClickEngine() }
    androidx.compose.runtime.DisposableEffect(click) { onDispose { click.release() } }

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
        if (recording.phase == RecordingPhase.COMPLETE || recording.phase == RecordingPhase.FAILED) appViewModel.setLayerBacking(null, null)
    }

    LaunchedEffect(appState.startupComplete, recording.phase) {
        if (appState.startupComplete && (recording.phase == RecordingPhase.RECORDING || recording.phase == RecordingPhase.PAUSED || recording.phase == RecordingPhase.STARTING)) {
            appViewModel.navigate(Route.RECORD)
        }
    }
    // A take keeps running after leaving the recorder, so Sketch leads back to its controls.
    LaunchedEffect(route, recording.phase) {
        if (route == Route.SKETCH && recording.phase in setOf(RecordingPhase.STARTING, RecordingPhase.RECORDING, RecordingPhase.PAUSED)) {
            appViewModel.navigate(Route.RECORD)
        }
    }
    LaunchedEffect(recording.projectId, recording.phase, route) {
        val finishedProjectId = recording.projectId
        if (finishedProjectId != null && (recording.phase == RecordingPhase.COMPLETE || recording.phase == RecordingPhase.FAILED)) {
            appViewModel.refreshLibrary(finishedProjectId.takeIf { route == Route.RECORD })
            if (route == Route.RECORD) appViewModel.setHome(finishedProjectId)
        }
    }
    LaunchedEffect(appState.importedProjectId) {
        appState.importedProjectId?.let { id ->
            appViewModel.selectProject(id)
            appViewModel.setHome(id)
            appViewModel.navigate(Route.MIX)
            appViewModel.consumeImportedProject(id)
        }
    }

    BackHandler(enabled = route != Route.LIBRARY) {
        when (route) {
            Route.SKETCH, Route.MIX, Route.TUNER -> appViewModel.leave(Route.LIBRARY)
            Route.RECORD -> appViewModel.leave(Route.SKETCH)
            Route.TRIM -> appViewModel.leave(Route.MIX, selected?.id ?: selectedId)
            Route.ARRANGE -> appViewModel.leave(Route.MIX, appState.arrangeProjectId ?: homeId ?: selectedId)
            Route.MIX_TAKES -> appViewModel.leave(Route.MIX)
            Route.OVERDUB_CALIBRATION -> appViewModel.leave(Route.RECORD)
            Route.LIBRARY -> Unit
        }
    }

    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier.weight(1f).then(
                if (route.isTopLevel) Modifier.consumeWindowInsets(WindowInsets.navigationBars) else Modifier
            )
        ) {
            when (route) {
                Route.LIBRARY -> LibraryScreen(
                    projects = projects,
                    onOpen = {
                        appViewModel.selectProject(it.id)
                        appViewModel.setHome(it.id)
                        appViewModel.navigate(Route.MIX)
                    },
                    onRename = { project, name -> appViewModel.renameProject(project.id, name) },
                    onDelete = {
                        appViewModel.deleteProject(it.id)
                        if (it.id == selectedId) appViewModel.selectProject(null)
                        if (it.id == homeId) appViewModel.setHome(null)
                    }
                )
                Route.SKETCH -> {
                    val current = projects.firstOrNull { it.id == (homeId ?: selectedId) } ?: projects.maxByOrNull { it.createdAtEpochMillis }
                    LaunchedEffect(current?.id) { appViewModel.loadPlaybackFile(current?.id) }
                    SketchScreen(
                        current = current,
                        currentWav = current?.let { appState.audioFiles[it.id] },
                        playbackAllowed = playbackAllowed(recording.phase),
                        click = click,
                        layerOn = appState.layerOn,
                        onLayerToggle = { appViewModel.setLayerEnabled(!appState.layerOn) },
                        onRecord = { clickSettings ->
                            appViewModel.setLayerBacking(if (appState.layerOn) current?.id else null, clickSettings)
                            appViewModel.navigate(Route.RECORD)
                        },
                        onShare = ::share,
                        onImport = onImportRequest
                    )
                }
                Route.MIX -> {
                    val project = selected?.takeIf { it.id == selectedId }
                    if (selectedId != null && project == null) Text("Loading recording…")
                    else MixEntryScreen(
                        project = project,
                        audioAvailable = project != null && appState.audioFiles[project.id] != null,
                        takeCount = projects.size,
                        onOpenLibrary = { appViewModel.navigate(Route.LIBRARY) },
                        onTrim = { appViewModel.navigate(Route.TRIM) },
                        onArrange = {
                            appViewModel.setArrangeProject(null)
                            appViewModel.navigate(Route.ARRANGE)
                        },
                        onMixTakes = { appViewModel.navigate(Route.MIX_TAKES) }
                    )
                }
                Route.TUNER -> TunerScreen(
                    microphoneGranted = appState.microphoneGranted,
                    microphoneDenied = appState.microphoneDenied,
                    lifecycle = hostLifecycle,
                    microphoneBlockedReason = when {
                        recording.phase in setOf(RecordingPhase.STARTING, RecordingPhase.RECORDING, RecordingPhase.PAUSED) || RecordingActivePath.path != null ->
                            "Stop the active recording before tuning."
                        appState.layerOn -> "Turn Layer off before tuning."
                        else -> null
                    },
                    onRequestMicrophone = onTunerMicRequest,
                    onOpenSettings = onOpenSettings,
                    onBack = { appViewModel.leave(Route.LIBRARY) }
                )
                Route.MIX_TAKES -> MixScreen(
                    projects = projects, repository = repository,
                    onBack = { appViewModel.leave(Route.MIX) },
                    onSaved = {
                        appViewModel.invalidatePlayback(it.id)
                        appViewModel.leave(Route.LIBRARY, it.id)
                    }
                )
                Route.ARRANGE -> ArrangeScreen(
                    projects = projects,
                    repository = repository,
                    initialProjectId = homeId ?: selectedId,
                    onBack = { appViewModel.leave(Route.MIX) },
                    onSaved = { appViewModel.leave(Route.MIX, it.id) },
                    onProjectChanged = {
                        appViewModel.invalidatePlayback(it.id)
                        appViewModel.setArrangeProject(it.id)
                        if (route == Route.LIBRARY) appViewModel.refreshLibrary(it.id)
                    }
                )
                Route.OVERDUB_CALIBRATION -> OverdubCalibrationScreen(
                    route = recording.overdubRoute ?: calibrationStore.lastRoute(),
                    onBack = { appViewModel.leave(Route.RECORD) }
                )
                Route.RECORD -> {
                    val clickSettings = appState.layerClickBpm.takeIf { it > 0 }?.let {
                        runCatching { MonitorClick(it, appState.layerClickBeats, appState.layerCountInBars) }.getOrNull()
                    }
                    RecordScreen(
                        recording = recording,
                        shortcutRequested = appState.shortcutRequested,
                        microphoneGranted = appState.microphoneGranted,
                        microphoneDenied = appState.microphoneDenied,
                        notificationsGranted = appState.notificationGranted,
                        startError = appState.startError,
                        layering = appState.layerBackingId != null,
                        calibrationAvailable = recording.overdubRoute != null || calibrationStore.lastRoute() != null,
                        onBack = { appViewModel.leave(Route.SKETCH) },
                        onCalibration = { appViewModel.navigate(Route.OVERDUB_CALIBRATION) },
                        onRecord = { onRecordRequest(appState.layerBackingId, clickSettings) },
                        onRequestMic = { onRecordRequest(appState.layerBackingId, clickSettings) },
                        onOpenSettings = onOpenSettings,
                        onPauseResume = {
                            val action = if (recording.phase == RecordingPhase.PAUSED) RecordingService.ACTION_RESUME else RecordingService.ACTION_PAUSE
                            context.startService(Intent(context, RecordingService::class.java).setAction(action))
                        },
                        onStop = { context.startService(Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_STOP)) },
                        onOpenRecording = {
                            val projectId = recording.projectId
                            if (projectId != null) {
                                appViewModel.selectProject(projectId)
                                appViewModel.setHome(projectId)
                                appViewModel.navigate(Route.MIX)
                            }
                        }
                    )
                }
                Route.TRIM -> if (selected != null && selected.id == selectedId) {
                    val project = selected
                    // Trim keeps its own Material layout; it sits on a dark panel so its pink accents stay readable on orange.
                    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(10.dp).clip(RoundedCornerShape(22.dp)).background(Synth.surface)) {
                        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides Synth.text) {
                            TrimScreen(
                                projectId = project.id,
                                playbackAllowed = playbackAllowed(recording.phase),
                                onBack = { appViewModel.leave(Route.MIX, project.id) },
                                onProjectChanged = { appViewModel.refreshLibrary(it.id) }
                            )
                        }
                    }
                } else if (selectedId == null) {
                    Text("Loading recording…")
                } else Text("Loading recording…")
            }
        }
        if (route.isTopLevel) TopLevelBar(route) { appViewModel.navigate(it) }
    }
    val takeErrorKey = "${recording.projectId ?: "no-project"}:${recording.error}"
    val hasTakeError = recording.phase == RecordingPhase.FAILED && recording.error != null &&
        (recording.projectId != null || route != Route.RECORD) && dismissedTakeError != takeErrorKey
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
                        if (appState.importWasRestored) Text("Import continued while this screen was recreated.")
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
                            "Audio Sketch uses microphone access to detect pitch while the tuner is open. It stops listening when you leave."
                        } else {
                            "Audio Sketch uses microphone access to record a take or record over a backing track. You can import, play, and manage recordings without it."
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
        silentRecoveryFiles.isNotEmpty() -> {
            val file = silentRecoveryFiles.first()
            AlertDialog(
                onDismissRequest = {},
                title = { Text("Silent recording found") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${file.name} contains only zero-valued audio samples. Save it to the library anyway?")
                        silentRecoveryFailure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { repository.recovery.recoverSilentTake(file) }
                                appViewModel.refreshLibrary()
                                appViewModel.removeSilentRecovery(file)
                                silentRecoveryFailure = null
                            } catch (failure: Throwable) {
                                silentRecoveryFailure = failure.message ?: "Could not recover silent recording"
                            }
                        }
                    }) { Text("Save anyway") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        scope.launch {
                            val deleted = withContext(Dispatchers.IO) { file.delete() || !file.exists() }
                            if (deleted) {
                                appViewModel.removeSilentRecovery(file)
                                silentRecoveryFailure = null
                            } else silentRecoveryFailure = "Could not discard this silent recording"
                        }
                    }) { Text("Discard") }
                }
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
    TopDestination(Route.MIX, "Mix", SynthIcon.Mix),
    TopDestination(Route.TUNER, "Tuner", SynthIcon.Tuner)
)

@Composable
private fun TopLevelBar(current: Route, onNavigate: (Route) -> Unit) {
    NavigationBar(containerColor = Synth.night, contentColor = Synth.text) {
        TOP_DESTINATIONS.forEach { destination ->
            val selected = destination.route == current
            NavigationBarItem(
                selected = selected,
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

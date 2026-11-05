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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.ottomusic.sketch.core.SUPPORTED_BPM
import com.ottomusic.sketch.core.TrimRange
import com.ottomusic.sketch.core.WavExporter
import com.ottomusic.sketch.data.AppViewModel
import com.ottomusic.sketch.data.ImportStage
import com.ottomusic.sketch.data.OverdubCalibrationStore
import com.ottomusic.sketch.platform.MICROPHONE_PERMISSION_ERROR
import com.ottomusic.sketch.playback.ClickEngine
import com.ottomusic.sketch.playback.MonitorClick
import com.ottomusic.sketch.recording.RecordingPhase
import com.ottomusic.sketch.recording.RecordingService
import com.ottomusic.sketch.recording.WavRecorder
import com.ottomusic.sketch.ui.ArrangeScreen
import com.ottomusic.sketch.ui.AudioSketchTheme
import com.ottomusic.sketch.ui.DetailScreen
import com.ottomusic.sketch.ui.HomeScreen
import com.ottomusic.sketch.ui.MissingAudioScreen
import com.ottomusic.sketch.ui.MixScreen
import com.ottomusic.sketch.ui.OverdubCalibrationScreen
import com.ottomusic.sketch.ui.RecordScreen
import com.ottomusic.sketch.ui.Synth
import com.ottomusic.sketch.ui.TrimScreen
import com.ottomusic.sketch.ui.TunerScreen
import com.ottomusic.sketch.ui.shareExport
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val microphoneGranted = mutableStateOf(false)
    private val microphoneDenied = mutableStateOf(false)
    private val notificationGranted = mutableStateOf(true)
    private val startError = mutableStateOf<String?>(null)
    private val importNotice = mutableStateOf<String?>(null)
    private val microphoneRationale = mutableStateOf(false)
    private val shortcutHint = mutableStateOf(false)
    private val routeState = mutableStateOf(Route.LIBRARY)
    private lateinit var appViewModel: AppViewModel
    private lateinit var selectedProjectState: androidx.compose.runtime.MutableState<String?>
    private val recordingStartGate = ResumeStartGate()
    private var shortcutRequestPending = false
    private var shortcutRecordAction: (() -> Unit)? = null
    private var pendingMicrophoneAction: MicrophoneAction? = null
    private var pendingOverdubProjectId: String? = null
    private var pendingClick: MonitorClick? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appViewModel = ViewModelProvider(this)[AppViewModel::class.java]
        selectedProjectState = appViewModel.selectedProjectState
        routeState.value = savedInstanceState?.getString(STATE_ROUTE)?.let { runCatching { Route.valueOf(it) }.getOrNull() } ?: Route.LIBRARY
        selectedProjectState.value = savedInstanceState?.getString(STATE_SELECTED_PROJECT)
        pendingMicrophoneAction = savedInstanceState?.getString(STATE_PENDING_MIC_ACTION)
            ?.let { runCatching { MicrophoneAction.valueOf(it) }.getOrNull() }
        pendingOverdubProjectId = savedInstanceState?.getString(STATE_PENDING_OVERDUB_PROJECT)
        val clickBpm = savedInstanceState?.getInt(STATE_PENDING_CLICK_BPM, 0) ?: 0
        if (clickBpm in SUPPORTED_BPM) pendingClick = runCatching {
            MonitorClick(
                clickBpm,
                savedInstanceState?.getInt(STATE_PENDING_CLICK_BEATS, 4) ?: 4,
                savedInstanceState?.getInt(STATE_PENDING_COUNT_IN_BARS, 0) ?: 0
            )
        }.getOrNull()
        consumeRecordNowIntent(intent)
        appViewModel.initialize()
        savedInstanceState?.getString(STATE_PENDING_IMPORT_URI)?.let { savedUri ->
            appViewModel.startImport(Uri.parse(savedUri), restored = true)
        }
        enableEdgeToEdge()
        updatePermissionState()

        fun continueMicrophoneAction(action: MicrophoneAction) {
            pendingMicrophoneAction = null
            if (action == MicrophoneAction.TUNER) {
                pendingOverdubProjectId = null
                routeState.value = Route.TUNER
            }
            else startRecordingService()
        }
        lateinit var requestMicrophone: (MicrophoneAction) -> Unit
        val microphoneLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            microphoneGranted.value = granted
            microphoneDenied.value = !granted
            val action = pendingMicrophoneAction
            pendingMicrophoneAction = null
            if (granted) {
                shortcutHint.value = false
                action?.let(::continueMicrophoneAction)
            }
        }
        requestMicrophone = { action ->
            pendingMicrophoneAction = action
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                microphoneGranted.value = true
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
                importNotice.value = null
                runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                appViewModel.startImport(uri)
            } else {
                importNotice.value = "Import cancelled. No recording was added."
            }
        }
        val requestRecordPermissions = {
            pendingMicrophoneAction = MicrophoneAction.RECORD
            startError.value = null
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else requestMicrophone(MicrophoneAction.RECORD)
        }
        val askToRecord: (String?, MonitorClick?) -> Unit = { overdubProjectId, click ->
            pendingOverdubProjectId = overdubProjectId
            pendingClick = click
            pendingMicrophoneAction = MicrophoneAction.RECORD
            startError.value = null
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                microphoneGranted.value = true
                requestRecordPermissions()
            } else microphoneRationale.value = true
        }
        val askToTune = {
            pendingOverdubProjectId = null
            pendingClick = null
            pendingMicrophoneAction = MicrophoneAction.TUNER
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                microphoneGranted.value = true
                microphoneDenied.value = false
                pendingMicrophoneAction = null
            } else microphoneRationale.value = true
        }
        shortcutRecordAction = { askToRecord(null, null) }

        setContent {
            AudioSketchTheme {
                AudioSketchApp(
                    hostLifecycle = lifecycle,
                    routeState = routeState,
                    selectedProjectState = selectedProjectState,
                    appViewModel = appViewModel,
                    microphoneGranted = microphoneGranted.value,
                    microphoneDenied = microphoneDenied.value,
                    notificationGranted = notificationGranted.value,
                    startError = startError.value,
                    importNotice = importNotice.value,
                    shortcutRequested = shortcutHint.value,
                    microphoneRationale = microphoneRationale.value,
                    pendingMicrophoneAction = pendingMicrophoneAction,
                    onContinueMicrophoneRationale = {
                        microphoneRationale.value = false
                        when (pendingMicrophoneAction ?: MicrophoneAction.RECORD) {
                            MicrophoneAction.RECORD -> requestRecordPermissions()
                            MicrophoneAction.TUNER -> requestMicrophone(MicrophoneAction.TUNER)
                        }
                    },
                    onDismissMicrophoneRationale = {
                        microphoneRationale.value = false
                        pendingMicrophoneAction = null
                        pendingOverdubProjectId = null
                        pendingClick = null
                        shortcutHint.value = false
                    },
                    onRecordRequest = askToRecord,
                    onTunerMicRequest = askToTune,
                    onOpenSettings = { openAppSettings() },
                    onImportRequest = { importNotice.value = null; openAudioDocument.launch(arrayOf("audio/*")) },
                    onImportNoticeHandled = { importNotice.value = null }
                )
            }
        }
        window.decorView.post { dispatchShortcutRecordRequest() }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionState()
        if (recordingStartGate.onResume(microphoneGranted.value)) startRecordingServiceNow()
        if (shortcutRequestPending) window.decorView.post { dispatchShortcutRecordRequest() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeRecordNowIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        appViewModel.state.value.importUri?.let { outState.putString(STATE_PENDING_IMPORT_URI, it.toString()) }
        outState.putString(STATE_ROUTE, routeState.value.name)
        selectedProjectState.value?.let { outState.putString(STATE_SELECTED_PROJECT, it) }
        pendingMicrophoneAction?.let { outState.putString(STATE_PENDING_MIC_ACTION, it.name) }
        pendingOverdubProjectId?.let { outState.putString(STATE_PENDING_OVERDUB_PROJECT, it) }
        pendingClick?.let {
            outState.putInt(STATE_PENDING_CLICK_BPM, it.bpm)
            outState.putInt(STATE_PENDING_CLICK_BEATS, it.beatsPerBar)
            outState.putInt(STATE_PENDING_COUNT_IN_BARS, it.countInBars)
        }
        super.onSaveInstanceState(outState)
    }

    private fun consumeRecordNowIntent(intent: Intent) {
        if (intent.action == ACTION_RECORD_NOW || intent.getBooleanExtra(EXTRA_RECORD_NOW, false)) {
            routeState.value = Route.RECORD
            shortcutHint.value = true
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

    private fun updatePermissionState() {
        microphoneGranted.value = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (microphoneGranted.value) microphoneDenied.value = false
        notificationGranted.value = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    private fun startRecordingService() {
        if (!recordingStartGate.request(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))) return
        startRecordingServiceNow()
    }

    private fun startRecordingServiceNow() {
        shortcutHint.value = false
        runCatching {
            val request = Intent(this, RecordingService::class.java).setAction(RecordingService.ACTION_START)
            pendingOverdubProjectId?.let { request.putExtra(RecordingService.EXTRA_OVERDUB_PROJECT_ID, it) }
            pendingClick?.let {
                request.putExtra(RecordingService.EXTRA_OVERDUB_CLICK_BPM, it.bpm)
                request.putExtra(RecordingService.EXTRA_OVERDUB_CLICK_BEATS, it.beatsPerBar)
                request.putExtra(RecordingService.EXTRA_OVERDUB_COUNT_IN_BARS, it.countInBars)
            }
            ContextCompat.startForegroundService(this, request)
            pendingOverdubProjectId = null
            pendingClick = null
        }.onFailure {
            startError.value = if (it is SecurityException) MICROPHONE_PERMISSION_ERROR
            else it.message ?: "Could not start recording service"
        }
    }

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }

    companion object {
        const val EXTRA_RECORD_NOW = "com.ottomusic.sketch.extra.RECORD_NOW"
        const val ACTION_RECORD_NOW = "com.ottomusic.sketch.action.RECORD_NOW"
        private const val STATE_PENDING_IMPORT_URI = "pendingImportUri"
        private const val STATE_ROUTE = "route"
        private const val STATE_SELECTED_PROJECT = "selectedProjectId"
        private const val STATE_PENDING_MIC_ACTION = "pendingMicrophoneAction"
        private const val STATE_PENDING_OVERDUB_PROJECT = "pendingOverdubProjectId"
        private const val STATE_PENDING_CLICK_BPM = "pendingClickBpm"
        private const val STATE_PENDING_CLICK_BEATS = "pendingClickBeats"
        private const val STATE_PENDING_COUNT_IN_BARS = "pendingCountInBars"
    }
}

private enum class Route { LIBRARY, RECORD, DETAIL, TRIM, MIX, TUNER, ARRANGE, OVERDUB_CALIBRATION }
private enum class MicrophoneAction { RECORD, TUNER }

private fun playbackAllowed(recordingPhase: RecordingPhase): Boolean =
    recordingPhase !in setOf(RecordingPhase.STARTING, RecordingPhase.RECORDING, RecordingPhase.PAUSED)

@Composable
private fun AudioSketchApp(
    hostLifecycle: Lifecycle,
    routeState: androidx.compose.runtime.MutableState<Route>,
    selectedProjectState: androidx.compose.runtime.MutableState<String?>,
    appViewModel: AppViewModel,
    microphoneGranted: Boolean,
    microphoneDenied: Boolean,
    notificationGranted: Boolean,
    startError: String?,
    importNotice: String?,
    shortcutRequested: Boolean,
    microphoneRationale: Boolean,
    pendingMicrophoneAction: MicrophoneAction?,
    onContinueMicrophoneRationale: () -> Unit,
    onDismissMicrophoneRationale: () -> Unit,
    onRecordRequest: (String?, MonitorClick?) -> Unit,
    onTunerMicRequest: () -> Unit,
    onOpenSettings: () -> Unit,
    onImportRequest: () -> Unit,
    onImportNoticeHandled: () -> Unit
) {
    val context = LocalContext.current
    val calibrationStore = remember(context) { OverdubCalibrationStore(context) }
    val repository = appViewModel.repository
    val appState by appViewModel.state.collectAsState()
    val recording by RecordingService.state.collectAsState()
    val projects = appState.projects
    val libraryLoaded = appState.libraryLoaded
    val unreadableProjectCount = appState.unreadableProjectCount
    var route by routeState
    var selectedId by selectedProjectState
    val selected = appState.selectedProject
    var dismissedTakeError by remember { mutableStateOf<String?>(null) }
    val silentRecoveryFiles = appState.silentRecoveryFiles
    var silentRecoveryFailure by remember { mutableStateOf<String?>(null) }
    var shareFailure by remember { mutableStateOf<String?>(null) }
    var shareFailureProject by remember { mutableStateOf<Project?>(null) }
    val scope = rememberCoroutineScope()
    val click = remember { ClickEngine() }
    androidx.compose.runtime.DisposableEffect(click) { onDispose { click.release() } }
    var layerOn by rememberSaveable { mutableStateOf(false) }
    var layerBackingId by rememberSaveable { mutableStateOf<String?>(null) }
    var layerClickBpm by rememberSaveable { mutableStateOf(0) }
    var layerClickBeats by rememberSaveable { mutableStateOf(4) }
    var layerCountInBars by rememberSaveable { mutableStateOf(0) }
    var homeId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(selectedId) { appViewModel.selectProject(selectedId) }

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
                        source.length(),
                        forSharing = true
                    ) { target ->
                        WavExporter.export(source, target, TrimRange(0L, project.totalFrames), AtomicBoolean(false))
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
        if (recording.phase == RecordingPhase.COMPLETE || recording.phase == RecordingPhase.FAILED) {
            layerBackingId = null
            layerClickBpm = 0
            layerClickBeats = 4
            layerCountInBars = 0
        }
    }

    LaunchedEffect(appState.startupComplete, recording.phase) {
        if (appState.startupComplete && (recording.phase == RecordingPhase.RECORDING || recording.phase == RecordingPhase.PAUSED || recording.phase == RecordingPhase.STARTING)) route = Route.RECORD
    }
    LaunchedEffect(recording.projectId, recording.phase, route) {
        if (recording.projectId != null && (recording.phase == RecordingPhase.COMPLETE || recording.phase == RecordingPhase.FAILED)) {
            appViewModel.refreshLibrary(recording.projectId.takeIf { route == Route.RECORD })
            if (route == Route.RECORD) { selectedId = recording.projectId; homeId = recording.projectId }
        }
    }
    LaunchedEffect(appState.importedProjectId) {
        appState.importedProjectId?.let { id ->
            selectedId = id
            route = Route.DETAIL
            appViewModel.consumeImportedProject(id)
        }
    }
    BackHandler(enabled = route != Route.LIBRARY) {
        route = when (route) {
            Route.TRIM -> {
                selected?.let { selectedId = it.id }
                Route.DETAIL
            }
            Route.DETAIL, Route.RECORD, Route.MIX -> Route.LIBRARY
            Route.TUNER, Route.ARRANGE -> Route.LIBRARY
            Route.OVERDUB_CALIBRATION -> Route.RECORD
            Route.LIBRARY -> Route.LIBRARY
        }
    }

    when (route) {
        Route.LIBRARY -> {
            val current = projects.firstOrNull { it.id == (homeId ?: selectedId) } ?: projects.maxByOrNull { it.createdAtEpochMillis }
            LaunchedEffect(current?.id) { appViewModel.loadPlaybackFile(current?.id) }
            HomeScreen(
                projects = projects.sortedByDescending { it.createdAtEpochMillis },
                current = current,
                currentWav = current?.let { appState.audioFiles[it.id] },
                playbackAllowed = playbackAllowed(recording.phase),
                click = click,
                layerOn = layerOn,
                onLayerToggle = { layerOn = !layerOn },
                onRecord = { clickSettings ->
                    layerBackingId = if (layerOn) current?.id else null
                    layerClickBpm = clickSettings?.bpm ?: 0
                    layerClickBeats = clickSettings?.beatsPerBar ?: 4
                    layerCountInBars = clickSettings?.countInBars ?: 0
                    route = Route.RECORD
                },
                onOpen = { selectedId = it.id; homeId = it.id; route = Route.DETAIL },
                onSelect = { homeId = it.id },
                onShare = ::share,
                onTrim = { selectedId = it.id; route = Route.TRIM },
                onMix = { route = Route.MIX },
                onTuner = { route = Route.TUNER },
                onArrange = { route = Route.ARRANGE },
                onImport = onImportRequest
            )
        }
        Route.MIX -> {
            MixScreen(
                projects = projects, repository = repository,
                onBack = { route = Route.LIBRARY },
                onSaved = { appViewModel.refreshLibrary(it.id); homeId = it.id; route = Route.LIBRARY }
            )
        }
        Route.TUNER -> TunerScreen(
            microphoneGranted = microphoneGranted,
            microphoneDenied = microphoneDenied,
            lifecycle = hostLifecycle,
            microphoneBlockedReason = when {
                recording.phase in setOf(RecordingPhase.STARTING, RecordingPhase.RECORDING, RecordingPhase.PAUSED) || WavRecorder.activePath != null ->
                    "Stop the active recording before tuning."
                layerOn -> "Turn Layer off before tuning."
                else -> null
            },
            onRequestMicrophone = onTunerMicRequest,
            onOpenSettings = onOpenSettings,
            onBack = { route = Route.LIBRARY }
        )
        Route.ARRANGE -> ArrangeScreen(
            projects = projects,
            repository = repository,
            initialProjectId = homeId ?: selectedId,
            onBack = { route = Route.LIBRARY },
            onSaved = { appViewModel.refreshLibrary(it.id); selectedId = it.id; homeId = it.id; route = Route.LIBRARY }
        )
        Route.OVERDUB_CALIBRATION -> OverdubCalibrationScreen(
            route = recording.overdubRoute ?: calibrationStore.lastRoute(),
            onBack = { route = Route.RECORD }
        )
        Route.RECORD -> {
            val clickSettings = layerClickBpm.takeIf { it > 0 }?.let {
                runCatching { MonitorClick(it, layerClickBeats, layerCountInBars) }.getOrNull()
            }
            RecordScreen(
                recording = recording,
                shortcutRequested = shortcutRequested,
                microphoneGranted = microphoneGranted,
                microphoneDenied = microphoneDenied,
                notificationsGranted = notificationGranted,
                startError = startError,
                layering = layerBackingId != null,
                calibrationAvailable = recording.overdubRoute != null || calibrationStore.lastRoute() != null,
                onBack = { route = Route.LIBRARY },
                onCalibration = { route = Route.OVERDUB_CALIBRATION },
                onRecord = { onRecordRequest(layerBackingId, clickSettings) },
                onRequestMic = { onRecordRequest(layerBackingId, clickSettings) },
                onOpenSettings = onOpenSettings,
                onPauseResume = {
                    val action = if (recording.phase == RecordingPhase.PAUSED) RecordingService.ACTION_RESUME else RecordingService.ACTION_PAUSE
                    context.startService(Intent(context, RecordingService::class.java).setAction(action))
                },
                onStop = { context.startService(Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_STOP)) },
                onOpenRecording = {
                    selectedId = recording.projectId
                    if (selectedId != null) route = Route.DETAIL
                }
            )
        }
        Route.DETAIL -> if (selected != null && selected.id == selectedId) {
            val project = selected
            val wav = appState.audioFiles[project.id]
            if (wav == null) MissingAudioScreen(project.name) { route = Route.LIBRARY }
            else DetailScreen(
                project = project,
                wav = wav,
                playbackAllowed = playbackAllowed(recording.phase),
                onBack = { appViewModel.refreshLibrary(); route = Route.LIBRARY },
                onTrim = { route = Route.TRIM },
                onShare = { share(project) },
                onRename = { name -> appViewModel.renameProject(project.id, name) },
                onDelete = { appViewModel.deleteProject(project.id); selectedId = null; homeId = null; route = Route.LIBRARY }
            )
        } else if (selectedId == null) {
            if (libraryLoaded) route = Route.LIBRARY else Text("Loading recording…")
        } else Text("Loading recording…")
        Route.TRIM -> if (selected != null && selected.id == selectedId) {
            val project = selected
            // Trim keeps its own Material layout; it sits on a dark panel so its pink accents stay readable on orange.
            Box(Modifier.fillMaxSize().safeDrawingPadding().padding(10.dp).clip(RoundedCornerShape(22.dp)).background(Synth.surface)) {
                androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides Synth.text) {
                    TrimScreen(
                        projectId = project.id,
                        playbackAllowed = playbackAllowed(recording.phase),
                        onBack = {
                            appViewModel.selectProject(project.id, force = true)
                            route = Route.DETAIL
                        },
                        onProjectChanged = { appViewModel.refreshLibrary(it.id) }
                    )
                }
            }
        } else if (selectedId == null) {
            if (libraryLoaded) route = Route.LIBRARY else Text("Loading recording…")
        } else Text("Loading recording…")
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
        microphoneRationale -> {
            AlertDialog(
                onDismissRequest = onDismissMicrophoneRationale,
                title = { Text(if (pendingMicrophoneAction == MicrophoneAction.TUNER) "Before tuning" else "Before recording") },
                text = {
                    Text(
                        if (pendingMicrophoneAction == MicrophoneAction.TUNER) {
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
        importNotice != null -> {
            val notice = importNotice
            AlertDialog(
                onDismissRequest = onImportNoticeHandled,
                title = { Text("Import") },
                text = { Text(notice) },
                confirmButton = { TextButton(onClick = onImportNoticeHandled) { Text("OK") } }
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
                                withContext(Dispatchers.IO) { repository.recoverSilentTake(file) }
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
                dismissButton = { TextButton(onClick = { selectedId = null; route = Route.LIBRARY }) { Text("Back to library") } }
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

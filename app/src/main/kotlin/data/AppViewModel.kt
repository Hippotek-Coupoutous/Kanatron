package com.ottomusic.sketch.data

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.ottomusic.sketch.core.Project
import com.ottomusic.sketch.core.SUPPORTED_BPM
import com.ottomusic.sketch.playback.MonitorClick
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class Route {
    LIBRARY, SKETCH, MIX, TUNER,
    RECORD, TRIM, ARRANGE, MIX_TAKES, OVERDUB_CALIBRATION;

    /** Top-level destinations show the bottom bar; sub-routes hide it and have their own back. */
    val isTopLevel: Boolean get() = this == LIBRARY || this == SKETCH || this == MIX || this == TUNER
}

/** What the Activity is about to do once the microphone permission is settled. */
internal enum class MicrophoneAction { RECORD, TUNER }

internal data class AppUiState(
    val route: Route = Route.LIBRARY,
    val selectedId: String? = null,
    val homeId: String? = null,
    val arrangeProjectId: String? = null,
    val layerOn: Boolean = false,
    val layerBackingId: String? = null,
    val layerClickBpm: Int = 0,
    val layerClickBeats: Int = 4,
    val layerCountInBars: Int = 0,
    val microphoneGranted: Boolean = false,
    val microphoneDenied: Boolean = false,
    val notificationGranted: Boolean = true,
    val microphoneRationale: Boolean = false,
    val pendingMicrophoneAction: MicrophoneAction? = null,
    val pendingOverdubProjectId: String? = null,
    val pendingClick: MonitorClick? = null,
    val shortcutRequested: Boolean = false,
    val startError: String? = null,
    val importNotice: String? = null,
    val projects: List<Project> = emptyList(),
    val audioFiles: Map<String, File> = emptyMap(),
    val unreadableProjectCount: Int = 0,
    val libraryError: String? = null,
    val startupComplete: Boolean = false,
    val silentRecoveryFiles: List<File> = emptyList(),
    val selectedProject: Project? = null,
    val selectionError: String? = null,
    val selectionErrorForId: String? = null,
    val importUri: Uri? = null,
    val importProgress: ImportProgress? = null,
    val importFailure: String? = null,
    val importWasRestored: Boolean = false,
    val importedProjectId: String? = null
)

/**
 * The one owner of app state: screens render it, Activity callbacks write through intents.
 * Retains library reads, the route and a single import across Activity and process recreation.
 */
internal class AppViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle
) : AndroidViewModel(application) {
    val repository = ProjectRepository(application)
    private val _state = MutableStateFlow(restoredState())
    val state: StateFlow<AppUiState> = _state.asStateFlow()

    private var initialization: Job? = null
    private var libraryGeneration = 0L
    private var selectionGeneration = 0L
    private var lastRequestedSelection: String? = null
    private val playbackJobs = mutableMapOf<String, Job>()
    private var importJob: Job? = null
    private var importCancellation: ImportCancellation? = null
    private var lastFinishedImportUri: Uri? = null

    init {
        // A restored route can already point at a project; load it like the first selection would.
        _state.value.selectedId?.let { selectProject(it) }
    }

    fun navigate(route: Route) = updateState { it.copy(route = route) }

    fun setHome(id: String?) = updateState { it.copy(homeId = id) }

    fun setArrangeProject(id: String?) = updateState { it.copy(arrangeProjectId = id) }

    fun setLayerEnabled(on: Boolean) = updateState { it.copy(layerOn = on) }

    /** Layer backing and click settings captured when a take starts. */
    fun setLayerBacking(backingId: String?, click: MonitorClick?) = updateState {
        it.copy(
            layerBackingId = backingId,
            layerClickBpm = click?.bpm ?: 0,
            layerClickBeats = click?.beatsPerBar ?: 4,
            layerCountInBars = click?.countInBars ?: 0
        )
    }

    fun updatePermissions(granted: Boolean, notificationsGranted: Boolean) = updateState {
        it.copy(
            microphoneGranted = granted,
            microphoneDenied = if (granted) false else it.microphoneDenied,
            notificationGranted = notificationsGranted
        )
    }

    fun markMicrophoneGranted() = updateState { it.copy(microphoneGranted = true, microphoneDenied = false) }

    fun awaitMicrophonePermission(action: MicrophoneAction?) = updateState { it.copy(pendingMicrophoneAction = action) }

    /** Applies a runtime prompt result and returns the action that was waiting on it. */
    fun onMicrophonePermissionResult(granted: Boolean): MicrophoneAction? {
        val pending = _state.value.pendingMicrophoneAction
        updateState {
            it.copy(
                microphoneGranted = granted,
                microphoneDenied = !granted,
                pendingMicrophoneAction = null,
                shortcutRequested = if (granted) false else it.shortcutRequested
            )
        }
        return pending?.takeIf { granted }
    }

    fun setPendingRecord(projectId: String?, click: MonitorClick?) =
        updateState { it.copy(pendingOverdubProjectId = projectId, pendingClick = click) }

    fun clearPendingRecord() = setPendingRecord(null, null)

    fun setMicrophoneRationale(visible: Boolean) = updateState { it.copy(microphoneRationale = visible) }

    fun dismissMicrophoneRationale() = updateState {
        it.copy(
            microphoneRationale = false,
            pendingMicrophoneAction = null,
            pendingOverdubProjectId = null,
            pendingClick = null,
            shortcutRequested = false
        )
    }

    fun setStartError(message: String?) = updateState { it.copy(startError = message) }

    fun setImportNotice(notice: String?) = updateState { it.copy(importNotice = notice) }

    fun clearShortcutRequest() = updateState { it.copy(shortcutRequested = false) }

    /** A RECORD_NOW intent: show Sketch and let the recorder ask for the microphone. */
    fun startShortcutRecord() = updateState { it.copy(route = Route.SKETCH, shortcutRequested = true) }

    /** The single exit for editing screens so system Back refreshes exactly like on-screen Back. */
    fun leave(to: Route, changedProjectId: String? = null) {
        if (changedProjectId != null) {
            // refreshLibrary selects the saved project, which is the id the screens should show.
            refreshLibrary(changedProjectId)
            updateState { it.copy(homeId = changedProjectId, route = to) }
        } else {
            if (to == Route.LIBRARY) refreshLibrary()
            updateState { it.copy(route = to) }
        }
    }

    fun initialize() {
        if (initialization != null) return
        initialization = viewModelScope.launch {
            refreshLibrary(startup = true).join()
        }
    }

    fun refreshLibrary(selectId: String? = null) = refreshLibrary(startup = false, selectId = selectId)

    private fun refreshLibrary(startup: Boolean, selectId: String? = null): Job {
        if (selectId != null) selectProject(selectId, force = true)
        val generation = ++libraryGeneration
        _state.update { it.copy(libraryError = null) }
        return viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    if (startup) {
                        val activePath = RecordingActivePath.path
                        repository.sweepStartupOrphans(ImportActivePaths.activeDecodePaths(), ImportActivePaths.activeStagingPaths())
                        repository.recovery.recoverTakes(activePath)
                        repository.recovery.recoverOrphanImports()
                    }
                    val library = repository.readLibrary()
                    val audioFiles = library.projects.mapNotNull { project ->
                        runCatching { repository.audioFile(project).takeIf(File::isFile)?.let { project.id to it } }.getOrNull()
                    }.toMap()
                    val silentFiles = if (startup) repository.recovery.silentRecoveryCandidates(RecordingActivePath.path) else emptyList()
                    LibrarySnapshot(library, audioFiles, silentFiles)
                }
                if (generation == libraryGeneration) {
                    val existingAudioFiles = _state.value.audioFiles
                    val existingLive = withContext(Dispatchers.IO) { existingAudioFiles.filterValues(File::isFile) }
                    if (generation == libraryGeneration) {
                        _state.update {
                            it.copy(
                                projects = result.library.projects,
                                // Fresh results win; entries added during the IO check are kept unchecked.
                                audioFiles = it.audioFiles.filter { (id, file) ->
                                    result.library.projects.any { project -> project.id == id } &&
                                        (existingAudioFiles[id] != file || existingLive[id] == file)
                                } + result.audioFiles,
                                unreadableProjectCount = result.library.unreadableCount,
                                libraryError = null
                            )
                        }
                    }
                }
                if (startup) _state.update { it.copy(silentRecoveryFiles = result.silentFiles, startupComplete = true) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (generation == libraryGeneration) {
                    _state.update { it.copy(libraryError = failure.message ?: "Could not read the library.") }
                }
            }
            if (startup) {
                _state.update { it.copy(startupComplete = true) }
                if (generation != libraryGeneration) refreshLibrary(startup = false).join()
            }
        }
    }

    fun selectProject(id: String?, force: Boolean = false) {
        val sameSelection = id != null && _state.value.selectedProject?.id == id
        if (!sameSelection) updateState { it.copy(selectedId = id) }
        if (!force && lastRequestedSelection == id) return
        lastRequestedSelection = id
        val generation = ++selectionGeneration
        _state.update {
            it.copy(
                selectedProject = if (sameSelection) it.selectedProject else null,
                selectionError = null,
                selectionErrorForId = null
            )
        }
        if (id == null) return
        viewModelScope.launch {
            try {
                val (project, wav) = withContext(Dispatchers.IO) {
                    val loaded = repository.get(id) ?: return@withContext null to null
                    loaded to repository.playbackFile(loaded).takeIf(File::isFile)
                }
                if (generation == selectionGeneration && _state.value.selectedId == id) {
                    _state.update {
                        it.copy(
                            selectedProject = project,
                            audioFiles = if (wav == null) it.audioFiles - id else it.audioFiles + (id to wav),
                            selectionError = if (project == null) "Recording could not be found." else null,
                            selectionErrorForId = if (project == null) id else null
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (generation == selectionGeneration && _state.value.selectedId == id) {
                    _state.update {
                        it.copy(
                            selectionError = failure.message ?: "Could not open this recording.",
                            selectionErrorForId = id
                        )
                    }
                }
            }
        }
    }

    /** Drops a cached playback file so the next load resolves the latest render. */
    fun invalidatePlayback(projectId: String) {
        // An in-flight load may hold the pre-save render; cancelling it keeps that stale file out.
        playbackJobs.remove(projectId)?.cancel()
        _state.update { it.copy(audioFiles = it.audioFiles - projectId) }
    }

    /** Resolves Home playback on demand; library enumeration never renders arrangements. */
    fun loadPlaybackFile(id: String?) {
        if (id == null || _state.value.audioFiles[id]?.isFile == true || playbackJobs[id]?.isActive == true) return
        lateinit var job: Job
        job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val wav = withContext(Dispatchers.IO) {
                    val project = repository.get(id) ?: return@withContext null
                    repository.playbackFile(project).takeIf(File::isFile)
                }
                if (wav != null && _state.value.projects.any { it.id == id }) {
                    _state.update { it.copy(audioFiles = it.audioFiles + (id to wav), libraryError = null) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                _state.update { it.copy(libraryError = failure.message ?: "Could not prepare playback.") }
            } finally {
                if (playbackJobs[id] === job) playbackJobs.remove(id)
            }
        }
        playbackJobs[id] = job
        job.start()
    }

    fun startImport(uri: Uri, restored: Boolean = false) {
        if (importJob?.isActive == true) {
            if (uri == _state.value.importUri) _state.update { it.copy(importWasRestored = true) }
            return
        }
        if (restored && _state.value.importUri == null && lastFinishedImportUri == uri) return
        lastFinishedImportUri = null
        importCancellation = ImportCancellation()
        val cancellation = importCancellation!!
        _state.update {
            it.copy(
                importUri = uri,
                importProgress = ImportProgress(ImportStage.COPYING, 0L, null),
                importFailure = null,
                importWasRestored = restored,
                importedProjectId = null
            )
        }
        lateinit var job: Job
        job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            initialization?.join()
            val id = UUID.randomUUID().toString()
            val decoded = repository.importOutput(id)
            try {
                val imported = withContext(Dispatchers.IO) {
                    AudioImporter(getApplication()).import(uri, decoded, cancellation) { progress ->
                        _state.update { it.copy(importProgress = progress) }
                    }
                }
                cancellation.check()
                val project = withContext(Dispatchers.IO) {
                    cancellation.check()
                    repository.saveImported(id, decoded, imported.sampleRate, imported.channels, imported.totalFrames, imported.sourceName)
                }
                refreshLibrary(selectId = project.id).join()
                _state.update { it.copy(importProgress = null, importUri = null, importedProjectId = project.id) }
            } catch (_: ImportCancelledException) {
                _state.update { it.copy(importProgress = null, importFailure = "Import cancelled. No recording was added.", importUri = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                _state.update { it.copy(importProgress = null, importFailure = failure.message ?: "Could not import this audio file.", importUri = null) }
            } finally {
                decoded.delete()
                ImportActivePaths.finishOutput(decoded)
                importCancellation = null
                lastFinishedImportUri = uri
                importJob = null
            }
        }
        importJob = job
        cancellation.cancelWithJob { job.isActive }
        job.start()
    }

    fun cancelImport() { importCancellation?.cancel() }

    fun consumeImportedProject(id: String) {
        _state.update { if (it.importedProjectId == id) it.copy(importedProjectId = null) else it }
    }

    fun dismissImportFailure() { _state.update { it.copy(importFailure = null) } }

    fun renameProject(id: String, name: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { repository.rename(id, name) }
                refreshLibrary(selectId = id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                _state.update { it.copy(libraryError = failure.message ?: "Could not rename this recording.") }
            }
        }
    }

    fun deleteProject(id: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { repository.delete(id) }
                selectProject(null, force = true)
                refreshLibrary()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                _state.update { it.copy(libraryError = failure.message ?: "Could not delete this recording.") }
            }
        }
    }

    fun consumeLibraryError() { _state.update { it.copy(libraryError = null) } }

    fun dismissUnreadableCount() { _state.update { it.copy(unreadableProjectCount = 0) } }

    fun removeSilentRecovery(file: File) {
        _state.update { it.copy(silentRecoveryFiles = it.silentRecoveryFiles.filterNot { candidate -> candidate == file }) }
    }

    /** Every write goes through here so the process-death copy never drifts from the visible state. */
    private fun updateState(transform: (AppUiState) -> AppUiState) {
        _state.update(transform)
        persist(_state.value)
    }

    private fun persist(state: AppUiState) {
        savedStateHandle[KEY_ROUTE] = state.route.name
        savedStateHandle[KEY_SELECTED] = state.selectedId
        savedStateHandle[KEY_HOME] = state.homeId
        savedStateHandle[KEY_ARRANGE] = state.arrangeProjectId
        savedStateHandle[KEY_LAYER_ON] = state.layerOn
        savedStateHandle[KEY_LAYER_BACKING] = state.layerBackingId
        savedStateHandle[KEY_LAYER_CLICK_BPM] = state.layerClickBpm
        savedStateHandle[KEY_LAYER_CLICK_BEATS] = state.layerClickBeats
        savedStateHandle[KEY_LAYER_COUNT_IN] = state.layerCountInBars
        savedStateHandle[KEY_PENDING_MIC] = state.pendingMicrophoneAction?.name
        savedStateHandle[KEY_PENDING_OVERDUB] = state.pendingOverdubProjectId
        savedStateHandle[KEY_CLICK_BPM] = state.pendingClick?.bpm
        savedStateHandle[KEY_CLICK_BEATS] = state.pendingClick?.beatsPerBar
        savedStateHandle[KEY_CLICK_COUNT_IN] = state.pendingClick?.countInBars
    }

    private fun restoredState(): AppUiState = AppUiState(
        route = savedStateHandle.get<String>(KEY_ROUTE)?.let { stored -> runCatching { Route.valueOf(stored) }.getOrNull() } ?: Route.LIBRARY,
        selectedId = savedStateHandle.get<String>(KEY_SELECTED),
        homeId = savedStateHandle.get<String>(KEY_HOME),
        arrangeProjectId = savedStateHandle.get<String>(KEY_ARRANGE),
        layerOn = savedStateHandle.get<Boolean>(KEY_LAYER_ON) ?: false,
        layerBackingId = savedStateHandle.get<String>(KEY_LAYER_BACKING),
        layerClickBpm = savedStateHandle.get<Int>(KEY_LAYER_CLICK_BPM) ?: 0,
        layerClickBeats = savedStateHandle.get<Int>(KEY_LAYER_CLICK_BEATS) ?: 4,
        layerCountInBars = savedStateHandle.get<Int>(KEY_LAYER_COUNT_IN) ?: 0,
        pendingMicrophoneAction = savedStateHandle.get<String>(KEY_PENDING_MIC)
            ?.let { stored -> runCatching { MicrophoneAction.valueOf(stored) }.getOrNull() },
        pendingOverdubProjectId = savedStateHandle.get<String>(KEY_PENDING_OVERDUB),
        pendingClick = restoredClick()
    )

    private fun restoredClick(): MonitorClick? {
        val bpm = savedStateHandle.get<Int>(KEY_CLICK_BPM) ?: return null
        if (bpm !in SUPPORTED_BPM) return null
        return runCatching {
            MonitorClick(
                bpm,
                savedStateHandle.get<Int>(KEY_CLICK_BEATS) ?: 4,
                savedStateHandle.get<Int>(KEY_CLICK_COUNT_IN) ?: 0
            )
        }.getOrNull()
    }

    private data class LibrarySnapshot(
        val library: ProjectRepository.LibraryContents,
        val audioFiles: Map<String, File>,
        val silentFiles: List<File>
    )

    private companion object {
        const val KEY_ROUTE = "route"
        const val KEY_SELECTED = "selectedProjectId"
        const val KEY_HOME = "homeProjectId"
        const val KEY_ARRANGE = "arrangeProjectId"
        const val KEY_LAYER_ON = "layerOn"
        const val KEY_LAYER_BACKING = "layerBackingId"
        const val KEY_LAYER_CLICK_BPM = "layerClickBpm"
        const val KEY_LAYER_CLICK_BEATS = "layerClickBeats"
        const val KEY_LAYER_COUNT_IN = "layerCountInBars"
        const val KEY_PENDING_MIC = "pendingMicrophoneAction"
        const val KEY_PENDING_OVERDUB = "pendingOverdubProjectId"
        const val KEY_CLICK_BPM = "pendingClickBpm"
        const val KEY_CLICK_BEATS = "pendingClickBeats"
        const val KEY_CLICK_COUNT_IN = "pendingCountInBars"
    }
}

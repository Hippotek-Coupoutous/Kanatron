package com.kanatron.app.data

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.kanatron.app.core.Cuts
import com.kanatron.app.core.Eq
import com.kanatron.app.core.MultitrackRenderer
import com.kanatron.app.core.OverdubMix
import com.kanatron.app.core.ProjectClip
import com.kanatron.app.core.ProjectTrack
import com.kanatron.app.core.Project
import com.kanatron.app.core.TrimRange
import com.kanatron.app.core.firstClip
import com.kanatron.app.core.SUPPORTED_BPM
import com.kanatron.app.playback.MonitorClick
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
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
    LIBRARY, SKETCH, EDIT, TUNER, CLIP, CLIP_PICK, OVERDUB_CALIBRATION, OVERDUB_REVIEW;

    /** Top-level destinations show the bottom bar; sub-routes hide it and have their own back. */
    val isTopLevel: Boolean get() = this == LIBRARY || this == SKETCH || this == EDIT || this == TUNER
}

/**
 * One recording's kept [range] on Clip's timeline: it starts playing at [positionFrames], counted at the first
 * part's rate, lowered by [levelSteps] and shaped by [eq].
 */
internal data class ClipPart(
    val project: Project,
    val range: TrimRange,
    val positionFrames: Long,
    val levelSteps: Int = 0,
    val eq: Eq = Eq.FLAT
)

/** Every part on one track at the first part's rate; where parts overlap they sum. */
internal fun clipArrangement(parts: List<ClipPart>): Project {
    val base = parts.first().project
    val clips = parts.mapIndexed { index, part ->
        ProjectClip("clip-$index", part.project.firstClip.source, part.positionFrames, part.range).mixed(part.levelSteps, part.eq)
    }
    return base.copy(channels = clips.maxOf { it.source.channels }, tracks = listOf(ProjectTrack("track-main", base.name, clips)))
}

/** Enough parts to comp a phrase from several takes while every lane stays tall enough to drag. */
internal const val MAX_CLIP_PARTS = 4

/** Unsaved cuts, level and EQ on the Edit subject; they survive rotation but not process death. */
internal data class EditSession(
    val projectId: String,
    val pieces: List<TrimRange>,
    val undo: List<List<TrimRange>>,
    val levelSteps: Int = 0,
    val eq: Eq = Eq.FLAT
) {
    val pending: Boolean get() = undo.isNotEmpty() || levelSteps != 0 || !eq.flat

    /** The recording as it would save: its pieces back to back, each lowered and shaped alike. */
    fun arrangement(project: Project): Project = Cuts.arrangement(project, pieces).let { cut ->
        cut.copy(tracks = cut.tracks.map { track -> track.copy(clips = track.clips.map { it.mixed(levelSteps, eq) }) })
    }
}

/** What the Activity is about to do once the microphone permission is settled. */
internal enum class MicrophoneAction { RECORD, TUNER }

internal data class AppUiState(
    val route: Route = Route.LIBRARY,
    val selectedId: String? = null,
    val edit: EditSession? = null,
    /** The clip being built from Edit; rotation-safe only. */
    val clip: List<ClipPart> = emptyList(),
    /** A finished take over its backing, not mixed yet; rotation-safe only, the take itself is already saved. */
    val overdub: OverdubMix? = null,
    val editSaving: Boolean = false,
    val editError: String? = null,
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
    /** Recovered unsaved takes, newest first; hidden from the Library until saved. */
    val drafts: List<Project> = emptyList(),
    val audioFiles: Map<String, File> = emptyMap(),
    val unreadableProjectCount: Int = 0,
    val libraryError: String? = null,
    val selectedProject: Project? = null,
    val selectionError: String? = null,
    val selectionErrorForId: String? = null,
    val importProgress: ImportProgress? = null,
    val importFailure: String? = null,
    val importedProjectId: String? = null
)

/**
 * The one owner of app state: screens render it, Activity callbacks write through intents.
 * Retains library reads, the route and a single import across Activity recreation.
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
    private var selectionRenderCancel = AtomicBoolean(false)
    private var lastRequestedSelection: String? = null
    private val playbackJobs = mutableMapOf<String, Job>()
    private var importJob: Job? = null
    private var importCancellation: ImportCancellation? = null
    private var draftActionInFlight = false
    init {
        // A restored route can already point at a project; load it like the first selection would.
        _state.value.selectedId?.let { selectProject(it) }
    }

    fun navigate(route: Route) = updateState { it.copy(route = route) }

    private fun AppUiState.session(project: Project): EditSession =
        edit?.takeIf { it.projectId == project.id } ?: EditSession(project.id, listOf(project.firstClip.trim), emptyList())

    /** A session with nothing pending is no session, so leaving Edit asks nothing. */
    private fun AppUiState.withSession(session: EditSession) = copy(edit = session.takeIf { it.pending })

    fun cut(project: Project, pieces: List<TrimRange>) = editing { state ->
        val session = state.session(project)
        state.withSession(session.copy(pieces = pieces, undo = session.undo + listOf(session.pieces)))
    }

    fun undoCut() = editing { state ->
        val session = state.edit?.takeIf { it.undo.isNotEmpty() } ?: return@editing state
        state.withSession(session.copy(pieces = session.undo.last(), undo = session.undo.dropLast(1)))
    }

    fun mix(project: Project, levelSteps: Int, eq: Eq) = editing { state ->
        state.withSession(state.session(project).copy(levelSteps = levelSteps, eq = eq))
    }

    fun discardEdits() = updateState { it.copy(edit = null, editError = null) }

    fun startClip(project: Project) = updateState { it.copy(clip = listOf(ClipPart(project, project.firstClip.trim, 0L)), route = Route.CLIP) }

    /** A new part starts where the timeline ends, overlapping the last fade so the two crossfade. */
    fun addToClip(project: Project) = editing { state ->
        val parts = state.clip
        if (parts.size >= MAX_CLIP_PARTS) return@editing state.copy(route = Route.CLIP)
        val end = clipArrangement(parts).totalFrames
        val at = (end - MultitrackRenderer.fadeFrames(parts.first().project.sampleRate)).coerceAtLeast(0L)
        state.copy(clip = parts + ClipPart(project, project.firstClip.trim, at), route = Route.CLIP)
    }

    fun setClipPart(index: Int, part: ClipPart) = editing { state ->
        state.copy(clip = state.clip.mapIndexed { i, old -> if (i == index) part else old })
    }

    fun removeClipPart(index: Int) = editing { state ->
        val parts = state.clip
        if (parts.size <= 1 || index !in parts.indices) return@editing state
        val left = parts.filterIndexed { i, _ -> i != index }
        // Positions count frames at the first part's rate, so a new first part at another rate rescales them.
        val from = parts.first().project.sampleRate
        val to = left.first().project.sampleRate
        state.copy(clip = if (from == to) left else left.map { it.copy(positionFrames = it.positionFrames * to / from) })
    }

    fun closeClip() = updateState { it.copy(clip = emptyList(), route = Route.EDIT) }

    /** Saves the clip as a new recording and makes it Edit's subject. */
    fun saveClip() {
        val parts = _state.value.clip
        if (parts.size < 2 || _state.value.editSaving) return
        updateState { it.copy(editSaving = true, editError = null) }
        viewModelScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) {
                    repository.saveRendered(clipArrangement(parts)) ?: error("The clip would clip. Lower a part's level in Mix.")
                }
                refreshLibrary(selectId = saved.id).join()
                updateState { it.copy(selectedProject = saved, clip = emptyList(), editSaving = false, route = Route.EDIT) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                updateState { it.copy(editSaving = false, editError = failure.message ?: "Could not save the clip.") }
            }
        }
    }

    fun startOverdubReview(mix: OverdubMix) = updateState { it.copy(overdub = mix, route = Route.OVERDUB_REVIEW) }

    fun nudgeOverdub(millis: Int) = editing { state -> state.overdub?.nudged(millis)?.let { state.copy(overdub = it) } ?: state }

    fun balanceOverdub(steps: Int) = editing { state -> state.overdub?.let { state.copy(overdub = it.balanced(steps)) } ?: state }

    /** The take stays in the Library on its own. */
    fun discardOverdub() = updateState { it.copy(route = Route.SKETCH) }

    /** Mixes the take into a new recording; the take's own entry goes, the backing stays as it is. */
    fun saveOverdub() {
        val mix = _state.value.overdub
        if (mix == null || _state.value.editSaving) return
        updateState { it.copy(editSaving = true, editError = null) }
        viewModelScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) {
                    repository.saveRendered(mix.arrangement()) ?: error("The mix would clip. Lower the backing or the take.")
                }
                updateState { it.copy(selectedId = saved.id, selectedProject = saved) }
                // The take goes only once nothing points at it; if it stays, it is just another Library entry.
                withContext(Dispatchers.IO) { runCatching { repository.delete(mix.takeProjectId) } }
                // Sketch shows the newest recording, so it waits for a Library without the take.
                refreshLibrary(selectId = saved.id).join()
                updateState { it.copy(editSaving = false, route = Route.SKETCH) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                updateState { it.copy(editSaving = false, editError = failure.message ?: "Could not save the mix.") }
            }
        }
    }

    /** Saves Edit's changes over the recording's audio, or as a new recording; an untouched unsaved take is simply kept. */
    fun saveEdits(project: Project, asNew: Boolean) {
        val session = _state.value.edit?.takeIf { it.projectId == project.id }
        if (session == null) {
            if (project.draft) saveRecoveredDraft(project.id)
            return
        }
        if (_state.value.editSaving) return
        updateState { it.copy(editSaving = true, editError = null) }
        viewModelScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) {
                    val arrangement = session.arrangement(project)
                    if (asNew) repository.saveRendered(arrangement) ?: error("The edit would clip. Lower the level in Mix.")
                    else repository.replaceAudio(project, arrangement)
                }
                invalidatePlayback(project.id)
                refreshLibrary(selectId = saved.id).join()
                updateState { it.copy(selectedProject = saved, edit = null, editSaving = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                updateState { it.copy(editSaving = false, editError = failure.message ?: "Could not save the edits.") }
            }
        }
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

    /**
     * A RECORD_NOW intent shows Sketch for a deliberate new take; an open overdub review closes, its take stays in the Library.
     * Refused while a save runs, since the save ends on its own screen. Returns whether the take should start.
     */
    fun startShortcutRecord(): Boolean {
        if (_state.value.editSaving) return false
        updateState { it.copy(route = Route.SKETCH, shortcutRequested = true) }
        return true
    }

    /** The single exit for editing screens so system Back refreshes exactly like on-screen Back. */
    fun leave(to: Route) {
        if (to == Route.LIBRARY) refreshLibrary()
        updateState { it.copy(route = to) }
    }

    fun initialize() {
        if (initialization != null) return
        initialization = viewModelScope.launch {
            refreshLibrary(startup = true).join()
        }
    }

    fun refreshLibrary(selectId: String? = null) = refreshLibrary(startup = false, selectId = selectId)

    private fun refreshLibrary(startup: Boolean, selectId: String? = null): Job {
        if (selectId != null) {
            // The selected project may have changed its playable file; force a fresh resolve.
            invalidatePlayback(selectId)
            selectProject(selectId, force = true)
        }
        val generation = ++libraryGeneration
        _state.update { it.copy(libraryError = null) }
        return viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    if (startup) {
                        repository.sweepStartupOrphans(ImportActivePaths.activeDecodePaths(), ImportActivePaths.activeStagingPaths())
                        repository.recovery.recoverTakes()
                        repository.recovery.recoverOrphanImports()
                    }
                    val library = repository.readLibrary()
                    val audioFiles = (library.projects + library.drafts).mapNotNull { project ->
                        runCatching { repository.directPlaybackFile(project)?.let { project.id to it } }.getOrNull()
                    }.toMap()
                    LibrarySnapshot(library, audioFiles)
                }
                if (generation == libraryGeneration) {
                    val existingAudioFiles = _state.value.audioFiles
                    val existingLive = withContext(Dispatchers.IO) { existingAudioFiles.filterValues(File::isFile) }
                    if (generation == libraryGeneration) {
                        _state.update {
                            it.copy(
                                projects = result.library.projects,
                                drafts = result.library.drafts,
                                // Fresh results win; entries added during the IO check are kept unchecked.
                                audioFiles = it.audioFiles.filter { (id, file) ->
                                    (result.library.projects.any { project -> project.id == id } ||
                                        result.library.drafts.any { project -> project.id == id }) &&
                                        (existingAudioFiles[id] != file || existingLive[id] == file)
                                } + result.audioFiles,
                                unreadableProjectCount = result.library.unreadableCount,
                                libraryError = null
                            )
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (generation == libraryGeneration) {
                    _state.update { it.copy(libraryError = failure.message ?: "Could not read the library.") }
                }
            }
            if (startup && generation != libraryGeneration) refreshLibrary(startup = false).join()
        }
    }

    fun selectProject(id: String?, force: Boolean = false) {
        val sameSelection = id != null && _state.value.selectedProject?.id == id
        if (!sameSelection) updateState { it.copy(selectedId = id) }
        if (!force && lastRequestedSelection == id) return
        lastRequestedSelection = id
        val generation = ++selectionGeneration
        val renderCancel = AtomicBoolean(false)
        selectionRenderCancel.set(true)
        selectionRenderCancel = renderCancel
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
                    loaded to repository.playbackFile(loaded, renderCancel).takeIf(File::isFile)
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

    /** Resolves Home playback on demand; library enumeration never renders previews. */
    fun loadPlaybackFile(id: String?) {
        if (id == null || _state.value.audioFiles[id]?.isFile == true || playbackJobs[id]?.isActive == true) return
        lateinit var job: Job
        job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val wav = withContext(Dispatchers.IO) {
                    val project = repository.get(id) ?: return@withContext null
                    repository.playbackFile(project).takeIf(File::isFile)
                }
                val known = _state.value.projects.any { it.id == id } || _state.value.drafts.any { it.id == id }
                if (wav != null && known) {
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

    fun startImport(uri: Uri) {
        if (importJob?.isActive == true) return
        importCancellation = ImportCancellation()
        val cancellation = importCancellation!!
        _state.update {
            it.copy(
                importProgress = ImportProgress(ImportStage.COPYING, 0L, null),
                importFailure = null,
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
                _state.update { it.copy(importProgress = null, importedProjectId = project.id) }
            } catch (_: ImportCancelledException) {
                _state.update { it.copy(importProgress = null, importFailure = "Import cancelled. No recording was added.") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                _state.update { it.copy(importProgress = null, importFailure = failure.message ?: "Could not import this audio file.") }
            } finally {
                decoded.delete()
                ImportActivePaths.finishOutput(decoded)
                importCancellation = null
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
                refreshLibrary(selectId = id.takeIf { it == _state.value.selectedId })
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
                if (_state.value.selectedId == id) {
                    invalidatePlayback(id)
                    selectProject(null, force = true)
                }
                if (_state.value.pendingOverdubProjectId == id) clearPendingRecord()
                if (_state.value.edit?.projectId == id) discardEdits()
                refreshLibrary()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                _state.update { it.copy(libraryError = failure.message ?: "Could not delete this recording.") }
            }
        }
    }

    /** Promotes a recovered take into the Library and makes it the current take; [onSaved] runs only if Sketch is still showing. */
    fun saveRecoveredDraft(id: String, onSaved: () -> Unit = {}) {
        if (draftActionInFlight) return
        draftActionInFlight = true
        viewModelScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) {
                    val draft = repository.get(id) ?: error("The recovered take could not be found.")
                    require(draft.draft) { "This take is already in the library." }
                    repository.saveEditState(draft.copy(draft = false))
                }
                _state.update { it.copy(drafts = it.drafts.filterNot { draft -> draft.id == id }) }
                refreshLibrary(selectId = saved.id).join()
                if (_state.value.route == Route.SKETCH) onSaved()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                _state.update { it.copy(libraryError = failure.message ?: "Could not save this take.") }
            } finally {
                draftActionInFlight = false
            }
        }
    }

    /** Deletes a recovered take; its WAV survives while another project references it. [onDeleted] runs only if Sketch is still showing. */
    fun discardRecoveredDraft(id: String, onDeleted: () -> Unit = {}) {
        // A stale banner must never delete a take that has since been saved into the Library.
        if (draftActionInFlight || _state.value.drafts.none { it.id == id }) return
        draftActionInFlight = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { repository.delete(id) }
                _state.update { it.copy(drafts = it.drafts.filterNot { draft -> draft.id == id }) }
                invalidatePlayback(id)
                if (_state.value.selectedId == id) selectProject(null, force = true)
                refreshLibrary().join()
                if (_state.value.route == Route.SKETCH) onDeleted()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                _state.update { it.copy(libraryError = failure.message ?: "Could not discard this take.") }
            } finally {
                draftActionInFlight = false
            }
        }
    }

    fun consumeLibraryError() { _state.update { it.copy(libraryError = null) } }

    fun dismissUnreadableCount() { _state.update { it.copy(unreadableProjectCount = 0) } }

    /** Every write goes through here so the process-death copy never drifts from the visible state. */
    private fun updateState(transform: (AppUiState) -> AppUiState) {
        // An edit error belongs to the screen that showed it, and an unsaved mix to its review.
        _state.update { state ->
            transform(state).let { next ->
                if (next.route == state.route) next
                else next.copy(editError = null, overdub = next.overdub.takeIf { next.route == Route.OVERDUB_REVIEW })
            }
        }
        persist(_state.value)
    }

    /** Edits wait out a save, whose snapshot is already taken; a real change clears the last save error. */
    private fun editing(transform: (AppUiState) -> AppUiState) = updateState { state ->
        if (state.editSaving) state else transform(state).let { if (it == state) state else it.copy(editError = null) }
    }

    private fun persist(state: AppUiState) {
        savedStateHandle[KEY_ROUTE] = state.route.name
        savedStateHandle[KEY_SELECTED] = state.selectedId
        savedStateHandle[KEY_PENDING_MIC] = state.pendingMicrophoneAction?.name
        savedStateHandle[KEY_PENDING_OVERDUB] = state.pendingOverdubProjectId
        savedStateHandle[KEY_CLICK_BPM] = state.pendingClick?.bpm
        savedStateHandle[KEY_CLICK_BEATS] = state.pendingClick?.beatsPerBar
        savedStateHandle[KEY_CLICK_COUNT_IN] = state.pendingClick?.countInBars
    }

    private fun restoredState(): AppUiState = AppUiState(
        route = when (val stored = savedStateHandle.get<String>(KEY_ROUTE)?.let { runCatching { Route.valueOf(it) }.getOrNull() }) {
            null -> Route.LIBRARY
            Route.CLIP, Route.CLIP_PICK -> Route.EDIT
            Route.OVERDUB_REVIEW -> Route.SKETCH
            else -> stored
        },
        selectedId = savedStateHandle.get<String>(KEY_SELECTED),
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
        val audioFiles: Map<String, File>
    )

    private companion object {
        const val KEY_ROUTE = "route"
        const val KEY_SELECTED = "selectedProjectId"
        const val KEY_PENDING_MIC = "pendingMicrophoneAction"
        const val KEY_PENDING_OVERDUB = "pendingOverdubProjectId"
        const val KEY_CLICK_BPM = "pendingClickBpm"
        const val KEY_CLICK_BEATS = "pendingClickBeats"
        const val KEY_CLICK_COUNT_IN = "pendingCountInBars"
    }
}

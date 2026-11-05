package com.ottomusic.sketch.data

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ottomusic.sketch.core.Project
import com.ottomusic.sketch.recording.WavRecorder
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

internal data class AppUiState(
    val projects: List<Project> = emptyList(),
    val audioFiles: Map<String, File> = emptyMap(),
    val libraryLoaded: Boolean = false,
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

/** Retains library reads and a single import across Activity recreation. */
internal class AppViewModel(application: Application) : AndroidViewModel(application) {
    val repository = ProjectRepository(application)
    private val _state = MutableStateFlow(AppUiState())
    val state: StateFlow<AppUiState> = _state.asStateFlow()

    val selectedProjectState = androidx.compose.runtime.mutableStateOf<String?>(null)

    private var initialization: Job? = null
    private var libraryGeneration = 0L
    private var selectionGeneration = 0L
    private var lastRequestedSelection: String? = null
    private val playbackJobs = mutableMapOf<String, Job>()
    private var importJob: Job? = null
    private var importCancellation: ImportCancellation? = null
    private var lastFinishedImportUri: Uri? = null

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
                        val activePath = WavRecorder.activePath
                        repository.sweepStartupOrphans(ImportActivePaths.activeDecodePaths(), ImportActivePaths.activeStagingPaths())
                        repository.recoverTakes(activePath)
                        repository.recoverOrphanImports()
                    }
                    val library = repository.readLibrary()
                    val audioFiles = library.projects.mapNotNull { project ->
                        runCatching { repository.audioFile(project).takeIf(File::isFile)?.let { project.id to it } }.getOrNull()
                    }.toMap()
                    val silentFiles = if (startup) repository.silentRecoveryCandidates(WavRecorder.activePath) else emptyList()
                    LibrarySnapshot(library, audioFiles, silentFiles)
                }
                if (generation == libraryGeneration) {
                    _state.update {
                        it.copy(
                            projects = result.library.projects,
                            audioFiles = result.audioFiles + it.audioFiles.filter { (id, file) ->
                                result.library.projects.any { project -> project.id == id } && file.isFile
                            },
                            libraryLoaded = true,
                            unreadableProjectCount = result.library.unreadableCount,
                            libraryError = null
                        )
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
        if (!sameSelection) selectedProjectState.value = id
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
                if (generation == selectionGeneration && selectedProjectState.value == id) {
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
                if (generation == selectionGeneration && selectedProjectState.value == id) {
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

    private data class LibrarySnapshot(
        val library: ProjectRepository.LibraryContents,
        val audioFiles: Map<String, File>,
        val silentFiles: List<File>
    )
}
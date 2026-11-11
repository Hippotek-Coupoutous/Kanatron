package com.kanatron.app.data

import android.content.Context
import com.kanatron.app.core.ExportPreflight
import com.kanatron.app.core.MultitrackRenderer
import com.kanatron.app.core.MusicalGrid
import com.kanatron.app.core.PROJECT_ID
import com.kanatron.app.core.PeakExtractor
import com.kanatron.app.core.Project
import com.kanatron.app.core.ProjectAudioSource
import com.kanatron.app.core.ProjectClip
import com.kanatron.app.core.ProjectTrack
import com.kanatron.app.core.SUPPORTED_SAMPLE_RATES
import com.kanatron.app.core.TrimRange
import com.kanatron.app.core.WavFormat
import com.kanatron.app.core.firstClip
import com.kanatron.app.platform.PlatformPorts
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class ProjectRepository(context: Context) {

    private val rootDirectory = context.filesDir
    private val projects = File(rootDirectory, "projects").apply { mkdirs() }
    private val recordings = File(rootDirectory, "recordings").apply { mkdirs() }
    private val arrangementWork = File(rootDirectory, "arrangement-work")
    private val previewCache = PreviewCache(File(rootDirectory, "arrangement-previews"))
    private val exportRetention = ExportRetention(rootDirectory)

    /** Repairs takes and rebuilds records at startup; each pass scans project metadata once. */
    internal val recovery = TakeRecovery(projects, recordings, ::projectFile, ::get) { id, file, rate, channels, frames, name, silent, draft ->
        createProject(id, file, rate, channels, frames, name, silent, draft)
    }

    data class LibraryContents(
        val projects: List<Project>,
        val unreadableCount: Int,
        val drafts: List<Project>
    )

    fun readLibrary(): LibraryContents {
        var unreadable = 0
        val loaded = projects.listFiles { file -> file.extension == "json" }.orEmpty().mapNotNull { file ->
            try { ProjectStore(file).loadSupported() } catch (_: Exception) { unreadable++; null }
        }.sortedByDescending(Project::createdAtEpochMillis)
        return LibraryContents(
            projects = loaded.filterNot(Project::draft),
            unreadableCount = unreadable,
            drafts = loaded.filter(Project::draft)
        )
    }

    /** Returns null only when no project file exists; a present file that cannot be read is damage, not absence. */
    fun get(id: String): Project? {
        val file = projectFile(id)
        if (!file.isFile) return null
        return try {
            ProjectStore(file).load()
        } catch (failure: Exception) {
            throw IOException("Recording data is damaged.", failure)
        }
    }

    /** The raw WAV of a project's first clip. */
    fun audioFile(project: Project): File = sourceFile(project.firstClip.source)

    private fun sourceFile(source: ProjectAudioSource): File = recovery.recordingFile(source.wav)

    fun missingSources(project: Project): List<String> = project.tracks.flatMap(ProjectTrack::clips)
        .map(ProjectClip::source).distinct()
        .filterNot(::isValidSource)
        .map(ProjectAudioSource::wav).distinct()

    /** The first clip is the whole project only while every audio-relevant field is at its default. */
    private fun directSource(project: Project): ProjectAudioSource? {
        val track = project.tracks.singleOrNull() ?: return null
        val clip = track.clips.singleOrNull() ?: return null
        val untouched = clip.positionFrames == 0L && clip.rateCorrectionPpm == 0 && clip.gainPermille == ProjectClip.UNITY_GAIN && clip.eq.flat &&
            clip.source.sampleRate == project.sampleRate &&
            clip.trim.startFrame == 0L && clip.trim.endFrame == clip.source.totalFrames
        return clip.source.takeIf { untouched }
    }

    /** Returns the raw source for a one-clip project, otherwise its bounded private preview. */
    fun playbackFile(project: Project, cancelled: AtomicBoolean = AtomicBoolean(false)): File {
        directSource(project)?.let { return sourceFile(it) }
        return previewCache.render(project, ::sourceFile, cancelled) {
            if (missingSources(project).isNotEmpty()) {
                throw IOException("A source WAV is missing or no longer matches its saved format.")
            }
        }
    }

    /** Raw playback without rendering, for library enumeration that must stay cheap. */
    fun directPlaybackFile(project: Project): File? =
        directSource(project)?.let(::sourceFile)?.takeIf(File::isFile)

    /** Holds the private rendered WAV, and whether it clipped, only while the bounded callback consumes it. */
    fun <T> withTemporaryWav(
        project: Project,
        onPeak: (Double) -> Unit = {},
        consume: (wav: File, clipped: Boolean) -> T
    ): T = synchronized(ARRANGEMENT_WORK_LOCK) {
        if (!arrangementWork.isDirectory && !arrangementWork.mkdirs()) {
            throw IOException("Could not create the temporary mix folder.")
        }
        cleanupArrangementWorkTemps()
        val expectedBytes = WavFormat.outputBytes(project.totalFrames, project.channels)
        if (expectedBytes > WavFormat.MAX_WAV_BYTES) throw IOException("This WAV mix exceeds the supported file size.")
        if (!ExportPreflight.hasEnoughOutputSpace(PlatformPorts.availableBytes(arrangementWork), expectedBytes)) {
            throw IOException("Not enough free storage to prepare the project for export.")
        }
        val temp = File.createTempFile(MIX_SOURCE_PREFIX, MIX_SOURCE_SUFFIX, arrangementWork)
        try {
            consume(temp, MultitrackRenderer.renderToWav(project, ::sourceFile, temp, onPeak = onPeak))
        } finally {
            temp.delete()
        }
    }

    /** Renders [arrangement] as a new Library recording. Returns null, committing nothing, when the sum would clip. */
    fun saveRendered(arrangement: Project, onPeak: (Double) -> Unit = {}): Project? = withTemporaryWav(arrangement, onPeak) { wav, clipped ->
        if (clipped) null else saveTake(UUID.randomUUID().toString(), wav, arrangement.sampleRate, arrangement.channels, arrangement.totalFrames, grid = arrangement.grid)
    }

    /**
     * Replaces [project]'s audio with [arrangement] rendered; the recording keeps its id and name and joins the Library.
     * The new WAV lands before the metadata that points at it, so a crash leaves at most a WAV startup recovery adopts.
     */
    fun replaceAudio(project: Project, arrangement: Project): Project = withTemporaryWav(arrangement) { wav, clipped ->
        if (clipped) throw IOException("The edit would clip. Lower the selected section's level in EQ.")
        synchronized(PROJECT_LOCK) {
            val file = File(recordings, "take-${UUID.randomUUID()}.wav")
            Files.move(wav.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
            val frames = arrangement.totalFrames
            val source = ProjectAudioSource("recordings/${file.name}", arrangement.sampleRate, arrangement.channels, frames)
            val replaced = project.copy(
                sampleRate = arrangement.sampleRate,
                channels = arrangement.channels,
                draft = false,
                tracks = listOf(ProjectTrack("track-main", project.name, listOf(ProjectClip("clip-main", source, 0L, TrimRange(0L, frames)))))
            )
            ProjectStore(projectFile(project.id)).save(replaced)
            previewCache.delete(project.id)
            // The replace has landed; an old WAV that resists deletion stays behind rather than failing the save.
            runCatching { recovery.deleteUnreferenced(project.sourceWavs(), recovery.sourceReferences()) }
            replaced
        }
    }

    fun saveEditState(project: Project): Project {
        synchronized(PROJECT_LOCK) {
            ProjectStore(projectFile(project.id)).save(project)
            return project
        }
    }

    private fun isValidSource(source: ProjectAudioSource): Boolean = runCatching {
        val file = sourceFile(source)
        if (!file.isFile) return false
        val format = file.inputStream().use(WavFormat::read)
        format.bitsPerSample == 16 && format.sampleRate == source.sampleRate &&
            format.channels == source.channels && format.frameCount == source.totalFrames &&
            Math.addExact(format.dataOffset, format.dataSize) <= file.length()
    }.getOrDefault(false)

    private fun isMixSourceTemp(name: String): Boolean =
        name.startsWith(MIX_SOURCE_PREFIX) && name.endsWith(MIX_SOURCE_SUFFIX)

    /** Caller holds ARRANGEMENT_WORK_LOCK to protect any in-flight render/export lease. */
    private fun cleanupArrangementWorkTemps() {
        arrangementWork.listFiles { file ->
            file.isFile && (isMixSourceTemp(file.name) || MultitrackRenderer.isRenderTemp(file.name))
        }.orEmpty().forEach { it.delete() }
    }

    fun importOutput(id: String): File {
        require(id.matches(PROJECT_ID)) { "Invalid import id" }
        return File(recordings, "import-$id.decoding.wav")
    }

    fun saveImported(id: String, decodedWav: File, sampleRate: Int, channels: Int, frames: Long, sourceName: String): Project {
        require(id.matches(PROJECT_ID)) { "Invalid import id" }
        require(frames > 0L)
        val finalFile = File(recordings, "import-$id.wav")
        if (decodedWav.absoluteFile != finalFile.absoluteFile) {
            Files.move(decodedWav.toPath(), finalFile.toPath(), StandardCopyOption.ATOMIC_MOVE)
        }
        val name = sourceName.substringAfterLast('/').substringBeforeLast('.').trim().ifEmpty { "Imported audio" }
        // Keep the committed WAV if metadata persistence fails; startup recovery can rebuild its project.
        return createProject(id, finalFile, sampleRate, channels, frames, name)
    }

    fun exportFile(
        projectId: String,
        preferredName: String,
        estimatedBytes: Long,
        writer: (File) -> Unit
    ): File = exportRetention.write(projectId, preferredName, estimatedBytes, writer)

    fun sweepStartupOrphans(activeDecodePaths: Set<String>, activeStagingPaths: Set<String>) {
        StartupOrphanSweep.sweep(
            recordings,
            File(rootDirectory, "import-staging"),
            File(rootDirectory, "exports"),
            activeDecodePaths,
            activeStagingPaths
        )
        PeakExtractor.sweepOrphans(recordings)
        exportRetention.cleanup()
        previewCache.cleanup()
        synchronized(ARRANGEMENT_WORK_LOCK) { cleanupArrangementWorkTemps() }
    }

    fun rename(id: String, name: String): Project {
        synchronized(PROJECT_LOCK) {
            val clean = name.trim()
            require(clean.isNotEmpty())
            val project = get(id) ?: error("Recording not found")
            return project.copy(name = clean).also { ProjectStore(projectFile(id)).save(it) }
        }
    }

    fun delete(id: String) {
        synchronized(PROJECT_LOCK) {
            val project = get(id)
            if (project != null) {
                // Metadata goes last: a crash must not leave WAVs that startup recovery rebuilds as projects.
                previewCache.delete(id)
                recovery.deleteUnreferenced(project.sourceWavs(), recovery.sourceReferences(excludeProjectId = id))
                val metadata = projectFile(id)
                if (!metadata.delete() && metadata.exists()) throw IOException("Could not delete the project metadata.")
                File(metadata.path + ".bak").delete()
            }
        }
    }

    /** Startup: drops archived projects and the audio only they used, before recovery adopts any WAV. */
    fun reclaimArchived() = synchronized(PROJECT_LOCK) { recovery.reclaimArchived() }

    private fun Project.sourceWavs(): List<String> = tracks.flatMap(ProjectTrack::clips).map { it.source.wav }.distinct()

    fun saveTake(
        takeId: String,
        wav: File,
        sampleRate: Int,
        channels: Int,
        frames: Long,
        silent: Boolean = false,
        grid: MusicalGrid = MusicalGrid()
    ): Project? {
        if (frames <= 0L) return null
        require(takeId.matches(PROJECT_ID)) { "Invalid recording id" }
        synchronized(PROJECT_LOCK) {
            val metadata = projectFile(takeId)
            if (metadata.exists() || File(metadata.path + ".bak").exists() || File(metadata.path + ".new").exists()) {
                throw IOException("A recording with this id already has project metadata.")
            }
            val finalFile = File(recordings, "take-$takeId.wav")
            val partialFile = File(recordings, "take-$takeId.partial.wav")
            if (finalFile.exists()) throw IOException("A recording WAV with this id already exists.")

            validateCompletedTake(wav, sampleRate, channels, frames)

            if (!wav.renameTo(finalFile)) {
                throw IOException("Could not finalize recording; the partial file was kept for startup recovery.")
            }
            return try {
                createProject(takeId, finalFile, sampleRate, channels, frames, takeName(finalFile.lastModified()), silent, grid = grid)
            } catch (failure: Throwable) {
                if (!finalFile.renameTo(partialFile)) {
                    throw IOException("Project metadata could not be saved and the WAV could not be moved back to a partial recording.", failure)
                }
                throw failure
            }
        }
    }

    private fun validateCompletedTake(wav: File, sampleRate: Int, channels: Int, frames: Long) {
        val message = "Recording WAV header does not match captured audio. The partial was kept for startup recovery."
        if (!wav.isFile || sampleRate !in SUPPORTED_SAMPLE_RATES || channels !in 1..2 || frames <= 0L) {
            throw IOException(message)
        }
        val format = try {
            wav.inputStream().use(WavFormat::read)
        } catch (failure: Exception) {
            throw IOException(message, failure)
        }
        val expectedDataSize = try {
            Math.multiplyExact(frames, channels.toLong() * 2L)
        } catch (failure: ArithmeticException) {
            throw IOException(message, failure)
        }
        val dataEnd = try {
            Math.addExact(format.dataOffset, format.dataSize)
        } catch (failure: ArithmeticException) {
            throw IOException(message, failure)
        }
        if (format.bitsPerSample != 16 || format.sampleRate != sampleRate || format.channels != channels ||
            format.frameCount != frames || format.dataSize != expectedDataSize || dataEnd > wav.length()
        ) {
            throw IOException(message)
        }
    }

    private fun createProject(
        id: String,
        file: File,
        sampleRate: Int,
        channels: Int,
        frames: Long,
        name: String,
        silent: Boolean = false,
        draft: Boolean = false,
        grid: MusicalGrid = MusicalGrid()
    ): Project {
        val project = Project(
            id = id,
            name = name,
            sampleRate = sampleRate,
            channels = channels,
            createdAtEpochMillis = System.currentTimeMillis(),
            silent = silent,
            draft = draft,
            grid = grid,
            tracks = listOf(
                ProjectTrack(
                    id = "track-main",
                    name = name,
                    clips = listOf(
                        ProjectClip(
                            id = "clip-main",
                            source = ProjectAudioSource("recordings/${file.name}", sampleRate, channels, frames),
                            positionFrames = 0L,
                            trim = TrimRange(0L, frames)
                        )
                    )
                )
            )
        )
        synchronized(PROJECT_LOCK) { ProjectStore(projectFile(id)).save(project) }
        return project
    }

    private fun projectFile(id: String): File {
        require(id.matches(PROJECT_ID)) { "Invalid recording id" }
        return File(projects, "$id.json")
    }

    internal companion object {
        private const val MIX_SOURCE_PREFIX = "mix-source-"
        private const val MIX_SOURCE_SUFFIX = ".wav"
        private val PROJECT_LOCK = Any()
        private val ARRANGEMENT_WORK_LOCK = Any()
    }
}

/** A take's display name, stamped with when its audio was written; recovered takes pass the WAV's timestamp. */
internal fun takeName(writtenAtMillis: Long): String {
    val at = writtenAtMillis.takeIf { it > 0L } ?: System.currentTimeMillis()
    return "Recording " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(at))
}

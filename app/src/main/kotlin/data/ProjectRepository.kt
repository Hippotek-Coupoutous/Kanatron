package com.ottomusic.sketch.data

import android.content.Context
import com.ottomusic.sketch.core.ExportPreflight
import com.ottomusic.sketch.core.MultitrackRenderer
import com.ottomusic.sketch.core.PROJECT_ID
import com.ottomusic.sketch.core.PROJECT_ID_PATTERN
import com.ottomusic.sketch.core.Project
import com.ottomusic.sketch.core.ProjectArrangementActions
import com.ottomusic.sketch.core.ProjectArrangementSnapshot
import com.ottomusic.sketch.core.ProjectAudioSource
import com.ottomusic.sketch.core.ProjectClip
import com.ottomusic.sketch.core.ProjectTrack
import com.ottomusic.sketch.core.SUPPORTED_SAMPLE_RATES
import com.ottomusic.sketch.core.TrimRange
import com.ottomusic.sketch.core.WavFormat
import com.ottomusic.sketch.platform.PlatformPorts
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
    internal val recovery = TakeRecovery(projects, recordings, ::projectFile, ::get, ::createProject)

    data class LibraryContents(val projects: List<Project>, val unreadableCount: Int)

    fun readLibrary(): LibraryContents {
        var unreadable = 0
        val loaded = projects.listFiles { file -> file.extension == "json" }.orEmpty().mapNotNull { file ->
            try { ProjectStore(file).load() } catch (_: Exception) { unreadable++; null }
        }.sortedByDescending(Project::createdAtEpochMillis)
        return LibraryContents(loaded, unreadable)
    }

    fun get(id: String): Project? = projectFile(id).takeIf(File::isFile)?.let { runCatching { ProjectStore(it).load() }.getOrNull() }

    fun audioFile(project: Project): File {
        require(!project.isArrangement) { "Arranged projects use their rendered playback file." }
        val name = File(project.sourceWav).name
        require(name.matches(RECORDING_PATH)) { "Invalid recording path" }
        return File(recordings, name)
    }

    private fun sourceFile(source: ProjectAudioSource): File {
        val name = File(source.wav).name
        require(source.wav == "recordings/$name" && RECORDING_PATH.matches(name)) { "Invalid arrangement source path." }
        return File(recordings, name)
    }

    fun missingSources(project: Project): List<String> = project.tracks.flatMap(ProjectTrack::clips)
        .map(ProjectClip::source).distinct()
        .filterNot(::isValidSource)
        .map(ProjectAudioSource::wav).distinct()

    /** Lazily returns the immutable source or materializes an arrangement's bounded private preview. */
    fun playbackFile(project: Project): File {
        if (!project.isArrangement) return audioFile(project)
        return previewCache.render(project, ::sourceFile) {
            if (missingSources(project).isNotEmpty()) {
                throw IOException("A source WAV is missing or no longer matches its saved format.")
            }
        }
    }

    /** Holds the private rendered WAV only while the bounded export callback consumes it. */
    fun <T> withTemporaryWav(
        project: Project,
        cancelled: AtomicBoolean = AtomicBoolean(false),
        consume: (File) -> T
    ): T = synchronized(ARRANGEMENT_WORK_LOCK) {
        require(project.isArrangement)
        if (!arrangementWork.isDirectory && !arrangementWork.mkdirs()) {
            throw IOException("Could not create the temporary mix folder.")
        }
        cleanupArrangementWorkTemps()
        val expectedBytes = Math.addExact(
            Math.multiplyExact(Math.multiplyExact(project.totalFrames, project.channels.toLong()), 2L),
            WavFormat.HEADER_BYTES.toLong()
        )
        if (expectedBytes > WavFormat.MAX_WAV_BYTES) throw IOException("This WAV mix exceeds the supported file size.")
        if (!ExportPreflight.hasEnoughOutputSpace(PlatformPorts.availableBytes(arrangementWork), expectedBytes)) {
            throw IOException("Not enough free storage to prepare the arrangement for export.")
        }
        val temp = File.createTempFile("mix-source-", ".wav", arrangementWork)
        try {
            MultitrackRenderer.renderToWav(project, ::sourceFile, temp, cancelled)
            consume(temp)
        } finally {
            temp.delete()
        }
    }

    fun renderMix(
        a: Project, b: Project, mix: Project, destination: File,
        cancelled: AtomicBoolean
    ): com.ottomusic.sketch.core.MultitrackRenderResult {
        val sources = mapOf(a.id to playbackFile(a), b.id to playbackFile(b))
        val bytes = Math.addExact(Math.multiplyExact(mix.totalFrames, mix.channels * 2L), WavFormat.HEADER_BYTES.toLong())
        val parent = destination.absoluteFile.parentFile ?: throw IOException("No mix output folder.")
        if (!ExportPreflight.hasEnoughOutputSpace(PlatformPorts.availableBytes(parent), bytes)) {
            throw IOException("Not enough free storage to save this mix.")
        }
        return MultitrackRenderer.renderToWav(mix, { sources.getValue(it.wav) }, destination, cancelled)
    }

    fun createArrangement(id: String, name: String, sources: List<Project>): Project {
        require(id.matches(PROJECT_ID)) { "Invalid arrangement id" }
        val cleanName = name.trim()
        require(cleanName.isNotEmpty()) { "An arrangement needs a name." }
        require(sources.size in 2..Project.MAX_TRACKS) { "Choose two to ${Project.MAX_TRACKS} recordings." }
        require(sources.none { it.isArrangement }) { "Choose source recordings, not another arrangement." }
        val sampleRate = sources.first().sampleRate
        val tracks = sources.mapIndexed { index, source ->
            val audio = ProjectAudioSource(source.sourceWav, source.sampleRate, source.channels, source.totalFrames)
            ProjectTrack(
                id = "track-${index + 1}",
                name = source.name,
                clips = listOf(
                    ProjectClip(
                        id = "clip-${index + 1}",
                        source = audio,
                        positionFrames = 0L,
                        trim = source.trim
                    )
                )
            )
        }
        val channels = sources.maxOf { it.channels }
        val frames = MultitrackRenderer.frameCount(tracks, sampleRate)
        return Project(
            id = id,
            name = cleanName,
            sourceWav = "",
            sampleRate = sampleRate,
            channels = channels,
            totalFrames = frames,
            trim = TrimRange(0L, frames),
            createdAtEpochMillis = System.currentTimeMillis(),
            tracks = tracks,
            isArrangement = true
        )
    }

    fun saveArrangement(project: Project): Project {
        require(project.isArrangement)
        synchronized(PROJECT_LOCK) {
            require(!projectFile(project.id).exists()) { "An arrangement with this id already exists." }
            ProjectStore(projectFile(project.id)).save(project.copy(migrationPending = false))
        }
        return project.copy(migrationPending = false)
    }

    /** Persists a completed mic-only take as a new, frame-positioned track. */
    fun appendRecordedTake(baseProjectId: String, takeProjectId: String, positionFrames: Long): Project {
        require(positionFrames >= 0L)
        require(baseProjectId != takeProjectId)
        synchronized(PROJECT_LOCK) {
            val base = get(baseProjectId) ?: error("The arrangement source could not be found.")
            val take = get(takeProjectId) ?: error("The recorded take could not be found.")
            require(!take.isArrangement) { "A recorded take must be a single-source project." }
            require(audioFile(take).isFile) { "The recorded take's WAV is missing." }
            val updated = if (base.isArrangement) {
                val clipId = "clip-${UUID.randomUUID()}"
                val trackId = "track-${UUID.randomUUID()}"
                val clip = ProjectClip(
                    id = clipId,
                    source = ProjectAudioSource(take.sourceWav, take.sampleRate, take.channels, take.totalFrames),
                    positionFrames = positionFrames,
                    trim = take.trim
                )
                ProjectArrangementActions.addTrack(base, ProjectTrack(trackId, take.name, listOf(clip)))
            } else {
                val arrangement = createArrangement(
                    id = "arr-${UUID.randomUUID()}",
                    name = "${base.name} + ${take.name}",
                    sources = listOf(base, take)
                )
                ProjectArrangementActions.setPosition(arrangement, "track-2", "clip-2", positionFrames)
            }
            return if (base.isArrangement) saveEditState(updated) else saveArrangement(updated)
        }
    }

    fun saveEditState(project: Project): Project {
        synchronized(PROJECT_LOCK) {
            ProjectStore(projectFile(project.id)).save(project.copy(migrationPending = false))
            return project.copy(migrationPending = false)
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

    /** Caller holds ARRANGEMENT_WORK_LOCK to protect any in-flight render/export lease. */
    private fun cleanupArrangementWorkTemps() {
        arrangementWork.listFiles { file ->
            file.isFile && (
                file.name.matches(MIX_SOURCE_TEMP) ||
                    file.name.matches(MIX_RENDER_TEMP)
                )
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
        forSharing: Boolean = false,
        writer: (File) -> Unit
    ): File = exportRetention.write(projectId, preferredName, estimatedBytes, forSharing, writer)

    fun markExportShared(file: File) = exportRetention.shared(file)

    fun sweepStartupOrphans(activeDecodePaths: Set<String>, activeStagingPaths: Set<String>) {
        StartupOrphanSweep.sweep(
            recordings,
            File(rootDirectory, "import-staging"),
            File(rootDirectory, "exports"),
            activeDecodePaths,
            activeStagingPaths
        )
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
        // Wait for any in-flight export and persist its share pin before removing its source.
        exportRetention.projectDeleted(id)
        synchronized(PROJECT_LOCK) {
            val project = get(id)
            if (project != null) {
                val ownedSourceFile = if (project.isArrangement) null else runCatching { audioFile(project) }.getOrNull()
                val sourcePath = if (project.isArrangement) null else project.sourceWav
                val sourceReferenced = sourcePath?.let {
                    recovery.sourceReferences(excludingProjectId = id).isReferenced(it)
                }
                val arrangementSources = if (project.isArrangement) {
                    (project.tracks + project.arrangementUndoHistory.flatMap(ProjectArrangementSnapshot::tracks))
                        .flatMap(ProjectTrack::clips).map { it.source }.distinct()
                } else emptyList()
                val metadata = projectFile(id)
                if (!metadata.delete() && metadata.exists()) throw IOException("Could not delete the project metadata.")
                File(metadata.path + ".bak").delete()
                if (ownedSourceFile != null && sourceReferenced == false) ownedSourceFile.delete()
                if (project.isArrangement) {
                    previewCache.delete(id)
                    arrangementSources.forEach { source ->
                        val remaining = recovery.sourceReferences()
                        if (!remaining.isReferenced(source.wav)) runCatching { sourceFile(source).delete() }
                    }
                }
            }
        }
    }

    fun saveTake(
        takeId: String,
        wav: File,
        sampleRate: Int,
        channels: Int,
        frames: Long,
        recovered: Boolean = false,
        silent: Boolean = false
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
            if (wav.absoluteFile != finalFile.absoluteFile && finalFile.exists()) {
                throw IOException("A recording WAV with this id already exists.")
            }

            try {
                validateCompletedTake(wav, sampleRate, channels, frames)
            } catch (failure: IOException) {
                if (wav.absoluteFile == finalFile.absoluteFile && finalFile.isFile && !partialFile.exists()) {
                    if (finalFile.renameTo(partialFile)) {
                        throw IOException("${failure.message} The file was kept as a partial recording for startup recovery.", failure)
                    }
                    throw IOException("${failure.message} The file could not be moved to a recoverable partial path.", failure)
                }
                throw failure
            }

            if (wav.absoluteFile != finalFile.absoluteFile && !wav.renameTo(finalFile)) {
                throw IOException("Could not finalize recording; the partial file was kept for startup recovery.")
            }
            return try {
                createProject(takeId, finalFile, sampleRate, channels, frames, if (recovered) "Recovered take" else defaultName(), silent)
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
        silent: Boolean = false
    ): Project {
        val project = Project(
            id = id,
            name = name,
            sourceWav = "recordings/${file.name}",
            sampleRate = sampleRate,
            channels = channels,
            totalFrames = frames,
            trim = TrimRange(0, frames),
            createdAtEpochMillis = System.currentTimeMillis(),
            silent = silent
        )
        synchronized(PROJECT_LOCK) { ProjectStore(projectFile(id)).save(project) }
        return project
    }

    private fun projectFile(id: String): File {
        require(id.matches(PROJECT_ID)) { "Invalid recording id" }
        return File(projects, "$id.json")
    }

    private fun defaultName(): String = "Recording " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())

    internal companion object {
        private val RECORDING_PATH = Regex("(?:take-$PROJECT_ID_PATTERN(?:\\.partial)?|import-$PROJECT_ID_PATTERN)\\.wav")
        private val MIX_RENDER_TEMP = Regex("mix-render-[A-Za-z0-9]+\\.tmp")
        private val MIX_SOURCE_TEMP = Regex("mix-source-[A-Za-z0-9]+\\.wav")
        private val PROJECT_LOCK = Any()
        private val ARRANGEMENT_WORK_LOCK = Any()
    }
}

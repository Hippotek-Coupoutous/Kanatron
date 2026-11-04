package com.ottomusic.sketch.data

import android.content.Context
import com.ottomusic.sketch.core.ExportPreflight
import com.ottomusic.sketch.core.MultitrackRenderer
import com.ottomusic.sketch.core.Project
import com.ottomusic.sketch.core.ProjectArrangementActions
import com.ottomusic.sketch.core.ProjectArrangementSnapshot
import com.ottomusic.sketch.core.ProjectAudioSource
import com.ottomusic.sketch.core.ProjectClip
import com.ottomusic.sketch.core.ProjectTrack
import com.ottomusic.sketch.core.RecordingCore
import com.ottomusic.sketch.core.TrimRange
import com.ottomusic.sketch.core.WavFormat
import com.ottomusic.sketch.platform.PlatformPorts
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class ProjectRepository(context: Context) {

    private val rootDirectory = context.filesDir
    private val projects = File(rootDirectory, "projects").apply { mkdirs() }
    private val recordings = File(rootDirectory, "recordings").apply { mkdirs() }
    private val arrangementPreviews = File(rootDirectory, "arrangement-previews")
    private val arrangementWork = File(rootDirectory, "arrangement-work")
    private val exportRetention = ExportRetention(rootDirectory)

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
        require(name.matches(Regex("(?:take-[A-Za-z0-9-]{1,80}(?:\\.partial)?|import-[A-Za-z0-9-]{1,80})\\.wav"))) { "Invalid recording path" }
        return File(recordings, name)
    }

    private fun sourceFile(source: ProjectAudioSource): File {
        val name = File(source.wav).name
        require(source.wav == "recordings/$name" && name.matches(
            Regex("(?:take-[A-Za-z0-9-]{1,80}(?:\\.partial)?|import-[A-Za-z0-9-]{1,80})\\.wav")
        )) { "Invalid arrangement source path." }
        return File(recordings, name)
    }

    fun missingSources(project: Project): List<String> = project.tracks.flatMap(ProjectTrack::clips)
        .map(ProjectClip::source).distinct()
        .filterNot(::isValidSource)
        .map(ProjectAudioSource::wav).distinct()

    /** Lazily returns the immutable source or materializes an arrangement's bounded private preview. */
    fun playbackFile(project: Project): File {
        if (!project.isArrangement) return audioFile(project)
        synchronized(PREVIEW_LOCK) {
            if (missingSources(project).isNotEmpty()) {
                throw IOException("A source WAV is missing or no longer matches its saved format.")
            }
            if (!arrangementPreviews.isDirectory && !arrangementPreviews.mkdirs()) {
                throw IOException("Could not create the arrangement preview folder.")
            }
            cleanupPreviewRenderTemps()
            val expectedBytes = Math.addExact(
                Math.multiplyExact(Math.multiplyExact(project.totalFrames, project.channels.toLong()), 2L),
                44L
            )
            if (expectedBytes > MAX_PREVIEW_BYTES) {
                throw IOException("This arrangement is too large for the preview cache. Shorten its clips and try again.")
            }
            val fingerprint = sha256("${project.sampleRate}|${project.channels}|${project.tracks}")
            val target = File(arrangementPreviews, "${project.id}.$fingerprint.wav")
            if (target.isFile && isValidPreview(target, project)) {
                target.setLastModified(System.currentTimeMillis())
                cleanupPreviewCache(target)
                return target
            }
            if (!ExportPreflight.hasEnoughOutputSpace(PlatformPorts.availableBytes(arrangementPreviews), expectedBytes)) {
                throw IOException("Not enough free storage to prepare the arrangement preview.")
            }
            if (target.exists()) target.delete()
            MultitrackRenderer.renderToWav(project, ::sourceFile, target)
            cleanupPreviewCache(target)
            return target
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
            44L
        )
        if (expectedBytes > 0xFFFF_FFFFL - 36L) throw IOException("This WAV mix exceeds the supported file size.")
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
        val bytes = Math.addExact(Math.multiplyExact(mix.totalFrames, mix.channels * 2L), 44L)
        val parent = destination.absoluteFile.parentFile ?: throw IOException("No mix output folder.")
        if (!ExportPreflight.hasEnoughOutputSpace(PlatformPorts.availableBytes(parent), bytes)) {
            throw IOException("Not enough free storage to save this mix.")
        }
        return MultitrackRenderer.renderToWav(mix, { sources.getValue(it.wav) }, destination, cancelled)
    }

    fun createArrangement(id: String, name: String, sources: List<Project>): Project {
        require(id.matches(Regex("[A-Za-z0-9-]{1,80}"))) { "Invalid arrangement id" }
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

    private fun isValidPreview(file: File, project: Project): Boolean = runCatching {
        val format = file.inputStream().use(WavFormat::read)
        format.bitsPerSample == 16 && format.sampleRate == project.sampleRate &&
            format.channels == project.channels && format.frameCount == project.totalFrames &&
            format.dataOffset + format.dataSize <= file.length()
    }.getOrDefault(false)

    private fun isValidSource(source: ProjectAudioSource): Boolean = runCatching {
        val file = sourceFile(source)
        if (!file.isFile) return false
        val format = file.inputStream().use(WavFormat::read)
        format.bitsPerSample == 16 && format.sampleRate == source.sampleRate &&
            format.channels == source.channels && format.frameCount == source.totalFrames &&
            Math.addExact(format.dataOffset, format.dataSize) <= file.length()
    }.getOrDefault(false)

    private fun cleanupPreviewCache(keep: File?) {
        cleanupPreviewRenderTemps()
        val previews = arrangementPreviews.listFiles { file ->
            file.isFile && file.name.matches(Regex("[A-Za-z0-9-]{1,80}\\.[a-f0-9]{64}\\.wav"))
        }.orEmpty()
        var total = previews.fold(0L) { sum, file ->
            runCatching { Math.addExact(sum, file.length()) }.getOrDefault(Long.MAX_VALUE)
        }
        var count = previews.size
        for (file in previews.filterNot { it == keep }.sortedBy(File::lastModified)) {
            if (count <= MAX_PREVIEW_COUNT && total <= MAX_PREVIEW_BYTES) break
            val size = file.length()
            if (file.delete()) {
                count--
                total = (total - size).coerceAtLeast(0L)
            }
        }
    }

    /** Caller holds PREVIEW_LOCK so no live renderer's scratch file can be removed. */
    private fun cleanupPreviewRenderTemps() {
        arrangementPreviews.listFiles { file ->
            file.isFile && file.name.matches(Regex("mix-render-[A-Za-z0-9]+\\.tmp"))
        }.orEmpty().forEach { it.delete() }
    }

    /** Caller holds ARRANGEMENT_WORK_LOCK to protect any in-flight render/export lease. */
    private fun cleanupArrangementWorkTemps() {
        arrangementWork.listFiles { file ->
            file.isFile && (
                file.name.matches(Regex("mix-source-[A-Za-z0-9]+\\.wav")) ||
                    file.name.matches(Regex("mix-render-[A-Za-z0-9]+\\.tmp"))
                )
        }.orEmpty().forEach { it.delete() }
    }

    private fun deletePreviewFiles(projectId: String) {
        arrangementPreviews.listFiles { file -> file.name.startsWith("$projectId.") && file.name.endsWith(".wav") }
            .orEmpty().forEach { it.delete() }
    }

    private data class ReferenceScan(val referenced: Boolean, val fullyReadable: Boolean)

    /** Any unreadable project metadata makes source deletion and orphan reconstruction unsafe. */
    private fun scanSourceReferences(sourceWav: String, excludingProjectId: String? = null): ReferenceScan {
        val bases = linkedMapOf<String, File>()
        projects.listFiles().orEmpty().forEach { file ->
            val baseName = when {
                file.name.endsWith(".json") -> file.name
                file.name.endsWith(".json.bak") -> file.name.removeSuffix(".bak")
                file.name.endsWith(".json.new") -> file.name.removeSuffix(".new")
                else -> null
            } ?: return@forEach
            val base = File(projects, baseName)
            bases[baseName] = base
        }
        var readable = true
        var referenced = false
        for ((baseName, file) in bases) {
            val projectId = baseName.removeSuffix(".json")
            if (projectId == excludingProjectId) continue
            try {
                val project = ProjectStore(file).load()
                val liveTracks = project.tracks + project.arrangementUndoHistory.flatMap(ProjectArrangementSnapshot::tracks)
                if (liveTracks.any { track -> track.clips.any { it.source.wav == sourceWav } }) referenced = true
            } catch (_: Exception) {
                readable = false
            }
        }
        return ReferenceScan(referenced, readable)
    }

    fun importOutput(id: String): File {
        require(id.matches(Regex("[A-Za-z0-9-]{1,80}"))) { "Invalid import id" }
        return File(recordings, "import-$id.decoding.wav")
    }

    fun saveImported(id: String, decodedWav: File, sampleRate: Int, channels: Int, frames: Long, sourceName: String): Project {
        require(id.matches(Regex("[A-Za-z0-9-]{1,80}"))) { "Invalid import id" }
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
        synchronized(PREVIEW_LOCK) { cleanupPreviewCache(keep = null) }
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
                val refs = sourcePath?.let { scanSourceReferences(it, excludingProjectId = id) }
                val arrangementSources = if (project.isArrangement) {
                    (project.tracks + project.arrangementUndoHistory.flatMap(ProjectArrangementSnapshot::tracks))
                        .flatMap(ProjectTrack::clips).map { it.source }.distinct()
                } else emptyList()
                val metadata = projectFile(id)
                if (!metadata.delete() && metadata.exists()) throw IOException("Could not delete the project metadata.")
                File(metadata.path + ".bak").delete()
                if (ownedSourceFile != null && refs?.let { it.fullyReadable && !it.referenced } == true) ownedSourceFile.delete()
                if (project.isArrangement) {
                    synchronized(PREVIEW_LOCK) { deletePreviewFiles(id) }
                    arrangementSources.forEach { source ->
                        val remaining = scanSourceReferences(source.wav)
                        if (remaining.fullyReadable && !remaining.referenced) runCatching { sourceFile(source).delete() }
                    }
                }
            }
        }
    }

    /** Repairs crash-left partial WAVs before exposing the library. */
    fun recoverPartials(activePath: String? = null): List<Project> {
        return recordings.listFiles { file -> file.name.matches(Regex("take-[A-Za-z0-9-]{1,80}\\.partial\\.wav")) }.orEmpty().mapNotNull { partial ->
            runCatching { recoverPartial(partial, activePath) }.getOrNull()
        }
    }

    /** Rebuilds missing project records from complete take WAVs left by a process death during save. */
    fun recoverOrphanTakes(): List<Project> {
        return recordings.listFiles { file -> file.name.matches(Regex("take-[A-Za-z0-9-]{1,80}\\.wav")) }
            .orEmpty().mapNotNull { wav -> runCatching { recoverOrphan(wav) }.getOrNull() }
    }

    /** Rebuilds project records from imported WAVs committed before a process death. */
    fun recoverOrphanImports(): List<Project> = recordings.listFiles { file ->
        file.name.matches(Regex("import-[A-Za-z0-9-]{1,80}\\.wav"))
    }.orEmpty().mapNotNull { wav ->
        val id = wav.name.removePrefix("import-").removeSuffix(".wav")
        if (projectFile(id).isFile) return@mapNotNull null
        val refs = scanSourceReferences("recordings/${wav.name}")
        if (!refs.fullyReadable || refs.referenced) return@mapNotNull null
        runCatching {
            val format = wav.inputStream().use(WavFormat::read)
            require(format.channels in 1..2 && format.sampleRate in 8_000..192_000 && format.frameCount > 0L)
            createProject(id, wav, format.sampleRate, format.channels, format.frameCount, "Recovered import")
        }.getOrNull()
    }

    fun recoverTakes(activePath: String? = null): List<Project> =
        recoverPartials(activePath) + recoverOrphanTakes()

    fun silentRecoveryCandidates(activePath: String? = null): List<File> =
        recordings.listFiles {
            it.name.matches(Regex("take-[A-Za-z0-9-]{1,80}\\.partial\\.wav")) || it.name.matches(Regex("take-[A-Za-z0-9-]{1,80}\\.wav"))
        }
            .orEmpty().filter { file ->
                file.absolutePath != activePath && runCatching {
                    val refs = scanSourceReferences("recordings/${file.name}")
                    refs.fullyReadable && !refs.referenced && isSilentRecoveryCandidate(file)
                }.getOrDefault(false)
            }

    fun recoverSilentTake(file: File): Project {
        require(file.absoluteFile.parentFile == recordings.absoluteFile) { "Invalid recovered take path" }
        return when {
            file.name.matches(Regex("take-[A-Za-z0-9-]{1,80}\\.partial\\.wav")) -> recoverPartial(file, null)
            file.name.matches(Regex("take-[A-Za-z0-9-]{1,80}\\.wav")) -> recoverOrphan(file)
            else -> null
        } ?: error("Silent recording could not be recovered")
    }

    private fun recoverPartial(partial: File, activePath: String?): Project? {
        if (partial.absolutePath == activePath) return null
        if (!partial.name.matches(Regex("take-[A-Za-z0-9-]{1,80}\\.partial\\.wav"))) return null
        val refs = scanSourceReferences("recordings/${partial.name}")
        if (!refs.fullyReadable || refs.referenced) return null
        val id = partial.name.removePrefix("take-").removeSuffix(".partial.wav")
        if (!id.matches(Regex("[A-Za-z0-9-]{1,80}")) || get(id) != null) return null
        val dataBytes = RecordingCore.repairableDataBytes(partial.length(), channels = 1)
        if (dataBytes < 2L) return null
        val allZero = RecordingCore.allSamplesZero(partial, RecordingCore.WAV_HEADER_BYTES, dataBytes)
        val sampleRate = readRateFromPlaceholderOrDefault(partial)
        RandomAccessFile(partial, "rw").use { file ->
            file.setLength(RecordingCore.WAV_HEADER_BYTES + dataBytes)
            file.seek(0)
            file.write(RecordingCore.header(sampleRate = sampleRate, channels = 1, dataBytes = dataBytes))
            file.fd.sync()
        }
        val finalFile = File(recordings, "take-$id.wav")
        if (!partial.renameTo(finalFile)) error("Could not recover partial recording")
        return try {
            createProject(id, finalFile, sampleRate, 1, dataBytes / 2, "Recovered take", silent = allZero)
        } catch (failure: Throwable) {
            finalFile.renameTo(partial)
            throw failure
        }
    }

    private fun recoverOrphan(wav: File): Project? {
        val refs = scanSourceReferences("recordings/${wav.name}")
        if (!refs.fullyReadable || refs.referenced) return null
        val id = wav.name.removePrefix("take-").removeSuffix(".wav")
        if (!id.matches(Regex("[A-Za-z0-9-]{1,80}")) || get(id) != null) return null
        val format = wav.inputStream().use(WavFormat::read)
        require(format.channels == 1 && format.frameCount > 0L) { "Invalid recovered take format" }
        require(format.dataOffset + format.dataSize <= wav.length()) { "Truncated recovered take" }
        val allZero = RecordingCore.allSamplesZero(wav, format.dataOffset, format.dataSize)
        return createProject(id, wav, format.sampleRate, format.channels, format.frameCount, "Recovered take", silent = allZero)
    }

    private fun isSilentRecoveryCandidate(file: File): Boolean {
        val partial = file.name.matches(Regex("take-[A-Za-z0-9-]{1,80}\\.partial\\.wav"))
        val orphan = file.name.matches(Regex("take-[A-Za-z0-9-]{1,80}\\.wav"))
        if (!partial && !orphan) return false
        val id = if (partial) file.name.removePrefix("take-").removeSuffix(".partial.wav")
        else file.name.removePrefix("take-").removeSuffix(".wav")
        if (!id.matches(Regex("[A-Za-z0-9-]{1,80}")) || get(id) != null) return false
        return if (partial) {
            val bytes = RecordingCore.repairableDataBytes(file.length(), channels = 1)
            bytes >= 2L && RecordingCore.allSamplesZero(file, RecordingCore.WAV_HEADER_BYTES, bytes)
        } else {
            val format = file.inputStream().use(WavFormat::read)
            format.channels == 1 && format.frameCount > 0L &&
                RecordingCore.allSamplesZero(file, format.dataOffset, format.dataSize)
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
        require(takeId.matches(Regex("[A-Za-z0-9-]{1,80}"))) { "Invalid recording id" }
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
        if (!wav.isFile || sampleRate !in 8_000..192_000 || channels !in 1..2 || frames <= 0L) {
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

    private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun projectFile(id: String): File {
        require(id.matches(Regex("[A-Za-z0-9-]{1,80}"))) { "Invalid recording id" }
        return File(projects, "$id.json")
    }

    private fun defaultName(): String = "Recording " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())

    private fun readRateFromPlaceholderOrDefault(file: File): Int =
        runCatching {
            RandomAccessFile(file, "r").use { input ->
                val header = ByteArray(28)
                input.readFully(header)
                require(String(header, 0, 4, Charsets.US_ASCII) == "RIFF" && String(header, 8, 4, Charsets.US_ASCII) == "WAVE")
                require(String(header, 12, 4, Charsets.US_ASCII) == "fmt ")
                val sampleRate = (header[24].toLong() and 0xFF) or
                    ((header[25].toLong() and 0xFF) shl 8) or
                    ((header[26].toLong() and 0xFF) shl 16) or
                    ((header[27].toLong() and 0xFF) shl 24)
                sampleRate.toInt().takeIf { sampleRate in 8_000L..192_000L } ?: 48_000
            }
        }.getOrDefault(48_000)

    internal companion object {
        private val PROJECT_LOCK = Any()
        private val PREVIEW_LOCK = Any()
        private val ARRANGEMENT_WORK_LOCK = Any()
        private const val MAX_PREVIEW_BYTES = 512L * 1024 * 1024
        private const val MAX_PREVIEW_COUNT = 8
    }
}
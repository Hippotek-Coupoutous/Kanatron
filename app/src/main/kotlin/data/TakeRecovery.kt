package com.kanatron.app.data

import com.kanatron.app.core.PROJECT_ID
import com.kanatron.app.core.PROJECT_ID_PATTERN
import com.kanatron.app.core.PeakExtractor
import com.kanatron.app.core.Project
import com.kanatron.app.core.ProjectTrack
import com.kanatron.app.core.RecordingCore
import com.kanatron.app.core.SUPPORTED_SAMPLE_RATES
import com.kanatron.app.core.WavFormat
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Repairs partial takes and rebuilds project records left behind by a process death.
 * Takes the storage roots and the repository's project read/write entry points so its cost stays visible here.
 */
internal class TakeRecovery(
    private val projects: File,
    private val recordings: File,
    private val projectFile: (String) -> File,
    private val loadProject: (String) -> Project?,
    private val createProject: (String, File, Int, Int, Long, String, Boolean, Boolean) -> Project
) {
    /** Source paths referenced by project metadata, plus whether every JSON could be read at all. */
    class SourceReferences internal constructor(private val sources: Set<String>, val fullyReadable: Boolean) {
        /** Unreadable metadata makes every source look referenced, so nothing may be deleted or rebuilt. */
        fun isReferenced(sourcePath: String): Boolean = !fullyReadable || sourcePath in sources
    }

    /** Scans all project metadata once; callers reuse the result for every candidate in one pass. */
    fun sourceReferences(excludeProjectId: String? = null): SourceReferences {
        val bases = linkedMapOf<String, File>()
        projects.listFiles().orEmpty().forEach { file ->
            val baseName = when {
                file.name.endsWith(".json") -> file.name
                file.name.endsWith(".json.bak") -> file.name.removeSuffix(".bak")
                file.name.endsWith(".json.new") -> file.name.removeSuffix(".new")
                else -> null
            } ?: return@forEach
            bases[baseName] = File(projects, baseName)
        }
        val sources = mutableSetOf<String>()
        var fullyReadable = true
        for (file in bases.values) {
            if (excludeProjectId != null && file.name == "$excludeProjectId.json") continue
            try {
                val project = ProjectStore(file).loadSupported()
                // An unsupported project that could not be archived is still on disk with references nobody reads.
                if (project == null && file.isFile) fullyReadable = false
                project?.tracks?.flatMap(ProjectTrack::clips)?.mapTo(sources) { it.source.wav }
            } catch (_: Exception) {
                fullyReadable = false
            }
        }
        // Until startup reclaims an archive, its audio stays referenced so recovery does not adopt it.
        projects.listFiles { file -> file.name.endsWith(ARCHIVED_JSON) }.orEmpty().forEach { archived ->
            if (excludeProjectId != null && archived.name == excludeProjectId + ARCHIVED_JSON) return@forEach
            try {
                sources += Project.sourcePaths(archived.readText())
            } catch (_: Exception) {
                fullyReadable = false
            }
        }
        return SourceReferences(sources, fullyReadable)
    }

    /** The private WAV a project source path names; anything outside the recordings folder is rejected. */
    fun recordingFile(wav: String): File {
        val name = File(wav).name
        require(wav == "recordings/$name" && RECORDING_PATH.matches(name)) { "Invalid recording source path." }
        return File(recordings, name)
    }

    /** Caller holds the project lock. Throws when an unreferenced WAV survives, so its metadata is kept and the failure shows. */
    fun deleteUnreferenced(wavs: Collection<String>, references: SourceReferences) {
        wavs.filterNot(references::isReferenced).forEach { wav ->
            val file = runCatching { recordingFile(wav) }.getOrNull() ?: return@forEach
            if (file.delete()) PeakExtractor.deleteCaches(file)
            else if (file.exists()) throw IOException("Could not delete the recording audio.")
        }
    }

    /**
     * Removes archived projects with the audio no other project uses. Caller holds the project lock. Audio goes first,
     * so a crash or a WAV that resists deletion keeps the archive for the next launch instead of leaving WAVs to adopt.
     */
    fun reclaimArchived() {
        // A full scan archives any unsupported project still under its .json name.
        sourceReferences()
        projects.listFiles { file -> file.name.endsWith(ARCHIVED_JSON) }.orEmpty()
            // A failed read keeps the archive for the next launch; only malformed JSON counts as unreadable.
            .mapNotNull { archive -> runCatching(archive::readText).getOrNull()?.let { archive to runCatching { Project.sourcePaths(it) }.getOrNull() } }
            // An unreadable archive makes every scan incomplete, so it goes before those that need a complete one.
            .sortedBy { (_, wavs) -> wavs != null }
            .forEach { (archive, wavs) ->
                runCatching {
                    if (wavs != null) {
                        val references = sourceReferences(excludeProjectId = archive.name.removeSuffix(ARCHIVED_JSON))
                        if (!references.fullyReadable) return@runCatching
                        deleteUnreferenced(wavs, references)
                    }
                    // An unreadable archive names no audio to reclaim; recovery adopts its WAVs as recordings instead.
                    archive.delete()
                }
            }
    }

    fun recoverTakes(): List<Project> {
        val references = sourceReferences()
        return recoverPartials(references) + recoverOrphanTakes(references)
    }

    /** Rebuilds project records from imported WAVs committed before a process death. */
    fun recoverOrphanImports(): List<Project> {
        val references = sourceReferences()
        return recordings.listFiles { file -> file.name.matches(IMPORT_PATH) }.orEmpty().mapNotNull { wav ->
            val id = wav.name.removePrefix("import-").removeSuffix(".wav")
            if (projectFile(id).isFile) return@mapNotNull null
            if (references.isReferenced("recordings/${wav.name}")) return@mapNotNull null
            runCatching {
                val format = wav.inputStream().use(WavFormat::read)
                require(format.channels in 1..2 && format.sampleRate in SUPPORTED_SAMPLE_RATES && format.frameCount > 0L)
                createProject(id, wav, format.sampleRate, format.channels, format.frameCount, "Recovered import", false, false)
            }.getOrNull()
        }
    }

    /** Repairs crash-left partial WAVs before exposing the library. */
    private fun recoverPartials(references: SourceReferences): List<Project> {
        return recordings.listFiles { file -> file.name.matches(TAKE_PARTIAL_PATH) }.orEmpty().mapNotNull { partial ->
            runCatching { recoverPartial(partial, references) }.getOrNull()
        }
    }

    /** Rebuilds missing project records from complete take WAVs left by a process death during save. */
    private fun recoverOrphanTakes(references: SourceReferences): List<Project> {
        return recordings.listFiles { file -> file.name.matches(TAKE_PATH) }
            .orEmpty().mapNotNull { wav -> runCatching { recoverOrphan(wav, references) }.getOrNull() }
    }

    private fun recoverPartial(partial: File, references: SourceReferences): Project? {
        if (!partial.name.matches(TAKE_PARTIAL_PATH)) return null
        if (references.isReferenced("recordings/${partial.name}")) return null
        val id = partial.name.removePrefix("take-").removeSuffix(".partial.wav")
        if (!id.matches(PROJECT_ID) || loadProject(id) != null) return null
        val (sampleRate, channels) = readCanonicalHeaderOrDefault(partial)
        val dataBytes = RecordingCore.repairableDataBytes(partial.length(), channels)
        if (dataBytes < 2L) return null
        val allZero = RecordingCore.allSamplesZero(partial, WavFormat.HEADER_BYTES.toLong(), dataBytes)
        val finalFile = File(recordings, "take-$id.wav")
        // Recheck under the registration lock: the recorder may own the file, or its save may have renamed it.
        val repaired = RecordingActivePath.exclusive {
            if (!partial.isFile || partial.absolutePath == RecordingActivePath.path || loadProject(id) != null) {
                return@exclusive false
            }
            RandomAccessFile(partial, "rw").use { file ->
                file.setLength(WavFormat.HEADER_BYTES + dataBytes)
                file.seek(0)
                file.write(RecordingCore.header(sampleRate = sampleRate, channels = channels, dataBytes = dataBytes))
                file.fd.sync()
            }
            if (!partial.renameTo(finalFile)) error("Could not recover partial recording")
            true
        }
        if (!repaired) return null
        return try {
            createProject(id, finalFile, sampleRate, channels, dataBytes / (channels * 2), takeName(finalFile.lastModified()), allZero, true)
        } catch (failure: Throwable) {
            finalFile.renameTo(partial)
            throw failure
        }
    }

    private fun recoverOrphan(wav: File, references: SourceReferences): Project? {
        if (references.isReferenced("recordings/${wav.name}")) return null
        val id = wav.name.removePrefix("take-").removeSuffix(".wav")
        if (!id.matches(PROJECT_ID) || loadProject(id) != null) return null
        // saveTake renames the in-flight partial to this name before its metadata lands.
        if (File(recordings, "take-$id.partial.wav").absolutePath == RecordingActivePath.path) return null
        val format = wav.inputStream().use(WavFormat::read)
        // Recorded takes are mono; overdub mixes and edits may be stereo.
        require(format.channels in 1..2 && format.frameCount > 0L) { "Invalid recovered take format" }
        require(format.dataOffset + format.dataSize <= wav.length()) { "Truncated recovered take" }
        val allZero = RecordingCore.allSamplesZero(wav, format.dataOffset, format.dataSize)
        // The scan above is slow; do not overwrite metadata that a completed save wrote meanwhile.
        if (loadProject(id) != null) return null
        return createProject(id, wav, format.sampleRate, format.channels, format.frameCount, takeName(wav.lastModified()), allZero, true)
    }

    /**
     * Rate and channels from the canonical 44-byte header every take is written with: the recorder's placeholder, or a
     * complete stereo render that saveTake moved back after its metadata failed. Unreadable headers mean a mono 48 kHz take.
     */
    private fun readCanonicalHeaderOrDefault(file: File): Pair<Int, Int> =
        runCatching {
            RandomAccessFile(file, "r").use { input ->
                val header = ByteArray(28)
                input.readFully(header)
                require(String(header, 0, 4, Charsets.US_ASCII) == "RIFF" && String(header, 8, 4, Charsets.US_ASCII) == "WAVE")
                require(String(header, 12, 4, Charsets.US_ASCII) == "fmt ")
                val channels = (header[22].toInt() and 0xFF) or ((header[23].toInt() and 0xFF) shl 8)
                val sampleRate = (header[24].toLong() and 0xFF) or
                    ((header[25].toLong() and 0xFF) shl 8) or
                    ((header[26].toLong() and 0xFF) shl 16) or
                    ((header[27].toLong() and 0xFF) shl 24)
                require(channels in 1..2 && sampleRate in SUPPORTED_SAMPLE_RATES.first.toLong()..SUPPORTED_SAMPLE_RATES.last.toLong())
                sampleRate.toInt() to channels
            }
        }.getOrDefault(48_000 to 1)

    private companion object {
        val TAKE_PARTIAL_PATH = Regex("take-$PROJECT_ID_PATTERN\\.partial\\.wav")
        val TAKE_PATH = Regex("take-$PROJECT_ID_PATTERN\\.wav")
        val IMPORT_PATH = Regex("import-$PROJECT_ID_PATTERN\\.wav")
        val RECORDING_PATH = Regex("(?:take-$PROJECT_ID_PATTERN(?:\\.partial)?|import-$PROJECT_ID_PATTERN)\\.wav")
        const val ARCHIVED_JSON = ".json" + ProjectStore.ARCHIVE_SUFFIX
    }
}

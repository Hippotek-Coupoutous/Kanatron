package com.kanatron.app.data

import com.kanatron.app.core.PROJECT_ID
import com.kanatron.app.core.PROJECT_ID_PATTERN
import com.kanatron.app.core.Project
import com.kanatron.app.core.ProjectArrangementSnapshot
import com.kanatron.app.core.ProjectTrack
import com.kanatron.app.core.RecordingCore
import com.kanatron.app.core.SUPPORTED_SAMPLE_RATES
import com.kanatron.app.core.WavFormat
import java.io.File
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
    class SourceReferences internal constructor(private val sources: Set<String>, private val fullyReadable: Boolean) {
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
                val project = ProjectStore(file).load()
                val liveTracks = project.tracks + project.undoHistory.flatMap(ProjectArrangementSnapshot::tracks)
                liveTracks.flatMap(ProjectTrack::clips).mapTo(sources) { it.source.wav }
            } catch (_: Exception) {
                fullyReadable = false
            }
        }
        return SourceReferences(sources, fullyReadable)
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
        val dataBytes = RecordingCore.repairableDataBytes(partial.length(), channels = 1)
        if (dataBytes < 2L) return null
        val allZero = RecordingCore.allSamplesZero(partial, WavFormat.HEADER_BYTES.toLong(), dataBytes)
        val sampleRate = readRateFromPlaceholderOrDefault(partial)
        val finalFile = File(recordings, "take-$id.wav")
        // Recheck under the registration lock: the recorder may own the file, or its save may have renamed it.
        val repaired = RecordingActivePath.exclusive {
            if (!partial.isFile || partial.absolutePath == RecordingActivePath.path || loadProject(id) != null) {
                return@exclusive false
            }
            RandomAccessFile(partial, "rw").use { file ->
                file.setLength(WavFormat.HEADER_BYTES + dataBytes)
                file.seek(0)
                file.write(RecordingCore.header(sampleRate = sampleRate, channels = 1, dataBytes = dataBytes))
                file.fd.sync()
            }
            if (!partial.renameTo(finalFile)) error("Could not recover partial recording")
            true
        }
        if (!repaired) return null
        return try {
            createProject(id, finalFile, sampleRate, 1, dataBytes / 2, takeName(finalFile.lastModified()), allZero, true)
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
        require(format.channels == 1 && format.frameCount > 0L) { "Invalid recovered take format" }
        require(format.dataOffset + format.dataSize <= wav.length()) { "Truncated recovered take" }
        val allZero = RecordingCore.allSamplesZero(wav, format.dataOffset, format.dataSize)
        // The scan above is slow; do not overwrite metadata that a completed save wrote meanwhile.
        if (loadProject(id) != null) return null
        return createProject(id, wav, format.sampleRate, format.channels, format.frameCount, takeName(wav.lastModified()), allZero, true)
    }

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
                sampleRate.toInt().takeIf { sampleRate in SUPPORTED_SAMPLE_RATES.first.toLong()..SUPPORTED_SAMPLE_RATES.last.toLong() } ?: 48_000
            }
        }.getOrDefault(48_000)

    private companion object {
        val TAKE_PARTIAL_PATH = Regex("take-$PROJECT_ID_PATTERN\\.partial\\.wav")
        val TAKE_PATH = Regex("take-$PROJECT_ID_PATTERN\\.wav")
        val IMPORT_PATH = Regex("import-$PROJECT_ID_PATTERN\\.wav")
    }
}

package com.kanatron.app.data

import com.kanatron.app.core.ExportPreflight
import com.kanatron.app.core.MultitrackRenderer
import com.kanatron.app.core.PROJECT_ID_PATTERN
import com.kanatron.app.core.PeakExtractor
import com.kanatron.app.core.Project
import com.kanatron.app.core.ProjectAudioSource
import com.kanatron.app.core.WavFormat
import com.kanatron.app.core.sha256Hex
import com.kanatron.app.platform.PlatformPorts
import java.io.File
import java.io.IOException

/** Bounded on-disk cache of rendered project previews. */
internal class PreviewCache(private val directory: File) {

    /** Renders or reuses the private preview; [check] runs under the cache lock before any preview file is touched. */
    fun render(project: Project, sourceFile: (ProjectAudioSource) -> File, check: () -> Unit): File {
        synchronized(PREVIEW_LOCK) {
            check()
            if (!directory.isDirectory && !directory.mkdirs()) {
                throw IOException("Could not create the project preview folder.")
            }
            cleanupRenderTemps()
            val expectedBytes = WavFormat.outputBytes(project.totalFrames, project.channels)
            if (expectedBytes > MAX_PREVIEW_BYTES) {
                throw IOException("This project is too large for the preview cache. Shorten its clips and try again.")
            }
            val fingerprint = sha256Hex(renderKey(project))
            val target = File(directory, "${project.id}.$fingerprint.wav")
            if (target.isFile && isValid(target, project)) {
                target.setLastModified(System.currentTimeMillis())
                evict(keep = target)
                return target
            }
            if (!ExportPreflight.hasEnoughOutputSpace(PlatformPorts.availableBytes(directory), expectedBytes)) {
                throw IOException("Not enough free storage to prepare the project preview.")
            }
            if (target.exists()) target.delete()
            MultitrackRenderer.renderToWav(project, sourceFile, target)
            evict(keep = target)
            return target
        }
    }

    /** Evicts old renders down to the cache limits. */
    fun cleanup() = synchronized(PREVIEW_LOCK) {
        evict(keep = null)
        PeakExtractor.sweepOrphans(directory)
    }

    /** Removes every cached render of [projectId] once its metadata is gone. */
    fun delete(projectId: String) = synchronized(PREVIEW_LOCK) {
        directory.listFiles { file -> file.name.startsWith("$projectId.") && file.name.endsWith(".wav") }
            .orEmpty().forEach { file ->
                file.delete()
                PeakExtractor.deleteCaches(file)
            }
    }

    /** Only the fields MultitrackRenderer reads: names and ids must not invalidate a cached render. */
    private fun renderKey(project: Project): String = buildString {
        append(project.sampleRate).append('|').append(project.channels).append('|').append(project.totalFrames)
        project.tracks.forEach { track ->
            append("|t").append(track.gainPermille).append(',').append(track.muted).append(',').append(track.solo)
            track.clips.forEach { clip ->
                append("|c").append(clip.source.wav).append(',')
                    .append(clip.source.sampleRate).append(',').append(clip.source.channels).append(',')
                    .append(clip.source.totalFrames).append(',').append(clip.positionFrames).append(',')
                    .append(clip.trim.startFrame).append('-').append(clip.trim.endFrame).append(',')
                    .append(clip.gainPermille).append(',').append(clip.muted).append(',').append(clip.rateCorrectionPpm)
            }
        }
    }

    private fun isValid(file: File, project: Project): Boolean = runCatching {
        val format = file.inputStream().use(WavFormat::read)
        format.bitsPerSample == 16 && format.sampleRate == project.sampleRate &&
            format.channels == project.channels && format.frameCount == project.totalFrames &&
            format.dataOffset + format.dataSize <= file.length()
    }.getOrDefault(false)

    private fun evict(keep: File?) {
        cleanupRenderTemps()
        val previews = directory.listFiles { file ->
            file.isFile && file.name.matches(PREVIEW_PATH)
        }.orEmpty()
        var total = previews.fold(0L) { sum, file ->
            runCatching { Math.addExact(sum, file.length()) }.getOrDefault(Long.MAX_VALUE)
        }
        var count = previews.size
        for (file in previews.filterNot { it == keep }.sortedBy(File::lastModified)) {
            if (count <= MAX_PREVIEW_COUNT && total <= MAX_PREVIEW_BYTES) break
            val size = file.length()
            if (file.delete()) {
                PeakExtractor.deleteCaches(file)
                count--
                total = (total - size).coerceAtLeast(0L)
            }
        }
    }

    /** Caller holds PREVIEW_LOCK so no live renderer's scratch file can be removed. */
    private fun cleanupRenderTemps() {
        directory.listFiles { file -> file.isFile && MultitrackRenderer.isRenderTemp(file.name) }
            .orEmpty().forEach { it.delete() }
    }

    private companion object {
        val PREVIEW_PATH = Regex("$PROJECT_ID_PATTERN\\.[a-f0-9]{64}\\.wav")
        val PREVIEW_LOCK = Any()
        const val MAX_PREVIEW_BYTES = 512L * 1024 * 1024
        const val MAX_PREVIEW_COUNT = 8
    }
}

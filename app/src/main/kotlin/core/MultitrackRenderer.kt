package com.ottomusic.sketch.core

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

data class MultitrackRenderResult(
    val sampleRate: Int,
    val channels: Int,
    val frames: Long,
    val clippedSamples: Long
)

class MultitrackRenderCancelledException : IOException("Mix render cancelled")

/** Sequential PCM16 rendering; working sample memory is limited by one output block and one source block. */
object MultitrackRenderer {
    const val BLOCK_FRAMES = 1_024
    private const val MAX_WAV_DATA_BYTES = 0xFFFF_FFFFL - 36L

    fun twoTakeMix(a: Project, b: Project, offsetFrames: Long, gainA: Int, gainB: Int): Project {
        require(a.id != b.id) { "Choose two different recordings." }
        require(offsetFrames != Long.MIN_VALUE)
        val tracks = listOf(a, b).mapIndexed { index, source ->
            ProjectTrack(
                id = "track-$index",
                name = source.name,
                gainPermille = if (index == 0) gainA else gainB,
                clips = listOf(ProjectClip(
                    id = "clip-$index",
                    source = ProjectAudioSource(source.id, source.sampleRate, source.channels, source.totalFrames),
                    positionFrames = if (index == 0) maxOf(0L, -offsetFrames) else maxOf(0L, offsetFrames),
                    trim = source.trim
                ))
            )
        }
        val frames = frameCount(tracks, a.sampleRate)
        return Project(
            id = "mix", name = "Mix", sourceWav = "", sampleRate = a.sampleRate,
            channels = maxOf(a.channels, b.channels), totalFrames = frames,
            trim = TrimRange(0L, frames), createdAtEpochMillis = 0L,
            tracks = tracks, isArrangement = true
        )
    }

    fun frameCount(tracks: List<ProjectTrack>, sampleRate: Int): Long {
        require(sampleRate in 8_000..192_000)
        return tracks.flatMap(ProjectTrack::clips).maxOfOrNull { clip ->
            Math.addExact(clip.positionFrames, clipFrameCount(clip, sampleRate))
        } ?: 0L
    }

    fun clipFrameCount(clip: ProjectClip, sampleRate: Int): Long =
        resampledFrames(clip.trim.frameCount, clip.source.sampleRate, sampleRate, clip.rateCorrectionPpm)

    fun renderToWav(
        project: Project,
        resolveSource: (ProjectAudioSource) -> File,
        destination: File,
        cancelled: AtomicBoolean = AtomicBoolean(false),
        onProgress: (completedFrames: Long, totalFrames: Long) -> Unit = { _, _ -> }
    ): MultitrackRenderResult {
        require(project.isArrangement) { "Only arranged projects can be rendered as a multitrack mix." }
        val frames = frameCount(project.tracks, project.sampleRate)
        require(frames == project.totalFrames) { "Arrangement length does not match its clips." }
        val channels = project.tracks.flatMap(ProjectTrack::clips).maxOfOrNull { it.source.channels } ?: 1
        require(channels == project.channels) { "Arrangement channel count does not match its sources." }
        val dataBytes = Math.multiplyExact(Math.multiplyExact(frames, channels.toLong()), 2L)
        if (dataBytes > MAX_WAV_DATA_BYTES) throw IOException("This WAV mix exceeds the supported file size.")

        val target = destination.absoluteFile
        val parent = target.parentFile ?: throw IOException("No parent directory for the mix output.")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Could not create the mix output folder.")
        val inputs = project.tracks.flatMap(ProjectTrack::clips).map { clip -> Triple(clip, clip.source, resolveSource(clip.source)) }
        val targetCanonical = target.canonicalFile
        require(inputs.none { (_, _, file) -> file.canonicalFile == targetCanonical }) { "Mix output must not overwrite an original source." }
        val readers = mutableListOf<ClipReader>()
        try {
            for ((clip, source, file) in inputs) readers += ClipReader(clip.id, source, file, project.sampleRate)
            val byClipId = readers.associateBy { it.clipId }
            val soloExists = project.tracks.any { it.solo && !it.muted }
            val active = project.tracks.flatMap { track ->
                if (track.muted || (soloExists && !track.solo)) emptyList()
                else track.clips.filterNot(ProjectClip::muted).map { track to it }
            }

            val temp = File.createTempFile("mix-render-", ".tmp", parent)
            var committed = false
            var clipped = 0L
            try {
                FileOutputStream(temp).use { output ->
                    WavFormat.writeHeader(output, channels, project.sampleRate, dataBytes)
                    val pcm = ByteArray(BLOCK_FRAMES * channels * 2)
                    var start = 0L
                    while (start < frames) {
                        checkCancelled(cancelled)
                        val count = minOf(BLOCK_FRAMES.toLong(), frames - start).toInt()
                        val sums = DoubleArray(count * channels)
                        for ((track, clip) in active) {
                            byClipId.getValue(clip.id).addBlock(clip, track.gainPermille, start, count, channels, sums)
                        }
                        var byteOffset = 0
                        for (sum in sums) {
                            val rounded = Math.round(sum)
                            if (rounded !in Short.MIN_VALUE.toLong()..Short.MAX_VALUE.toLong()) clipped++
                            val sample = rounded.coerceIn(Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong()).toInt()
                            pcm[byteOffset++] = sample.toByte()
                            pcm[byteOffset++] = (sample ushr 8).toByte()
                        }
                        output.write(pcm, 0, byteOffset)
                        start += count
                        onProgress(start, frames)
                    }
                    checkCancelled(cancelled)
                    output.fd.sync()
                }
                checkCancelled(cancelled)
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                committed = true
                return MultitrackRenderResult(project.sampleRate, channels, frames, clipped)
            } finally {
                if (!committed) Files.deleteIfExists(temp.toPath())
            }
        } finally {
            readers.forEach { runCatching { it.close() } }
        }
    }

    private fun resampledFrames(frames: Long, sourceRate: Int, targetRate: Int, rateCorrectionPpm: Int): Long {
        val ratio = rateRatio(sourceRate, targetRate, rateCorrectionPpm)
        return BigDecimal.valueOf(frames).multiply(BigDecimal.valueOf(ratio.denominator))
            .divide(BigDecimal.valueOf(ratio.numerator), 0, RoundingMode.HALF_UP)
            .longValueExact().coerceAtLeast(1L)
    }

    private data class RateRatio(val numerator: Long, val denominator: Long)

    private fun rateRatio(sourceRate: Int, targetRate: Int, rateCorrectionPpm: Int): RateRatio {
        require(sourceRate in 8_000..192_000 && targetRate in 8_000..192_000)
        require(rateCorrectionPpm in -ProjectClip.MAX_RATE_CORRECTION_PPM..ProjectClip.MAX_RATE_CORRECTION_PPM)
        val numerator = sourceRate.toLong() * (1_000_000L + rateCorrectionPpm)
        val denominator = targetRate.toLong() * 1_000_000L
        val divisor = gcd(numerator, denominator)
        return RateRatio(numerator / divisor, denominator / divisor)
    }

    private fun gcd(left: Long, right: Long): Long {
        var a = left
        var b = right
        while (b != 0L) {
            val next = a % b
            a = b
            b = next
        }
        return a
    }

    private fun checkCancelled(cancelled: AtomicBoolean) {
        if (cancelled.get()) throw MultitrackRenderCancelledException()
    }

    private class ClipReader(val clipId: String, source: ProjectAudioSource, file: File, private val targetRate: Int) : AutoCloseable {
        private val format = file.inputStream().use(WavFormat::read)

        init {
            require(format.bitsPerSample == 16 && format.channels == source.channels && format.sampleRate == source.sampleRate && format.frameCount == source.totalFrames) {
                "A source WAV no longer matches its saved format."
            }
        }

        private val random = RandomAccessFile(file, "r")

        fun addBlock(
            clip: ProjectClip,
            trackGainPermille: Int,
            blockStart: Long,
            blockCount: Int,
            outputChannels: Int,
            sums: DoubleArray
        ) {
            val ratio = rateRatio(clip.source.sampleRate, targetRate, clip.rateCorrectionPpm)
            val clipFrames = resampledFrames(clip.trim.frameCount, clip.source.sampleRate, targetRate, clip.rateCorrectionPpm)
            val clipEnd = Math.addExact(clip.positionFrames, clipFrames)
            val start = max(blockStart, clip.positionFrames)
            val end = minOf(blockStart + blockCount, clipEnd)
            if (start >= end) return

            val localFirst = start - clip.positionFrames
            val localLast = end - 1L - clip.positionFrames
            val firstPosition = mappingAt(localFirst, ratio)
            val lastPosition = advance(firstPosition, (end - start - 1L).toInt(), ratio)
            val firstFrame = clip.trim.startFrame + firstPosition.offset
            val lastFrame = minOf(clip.trim.endFrame - 1L, clip.trim.startFrame + lastPosition.offset + 1L)
            val sourceFrames = Math.addExact(lastFrame - firstFrame, 1L)
            val byteCount = Math.toIntExact(Math.multiplyExact(sourceFrames, format.blockAlign.toLong()))
            val bytes = ByteArray(byteCount)
            random.seek(format.dataOffset + firstFrame * format.blockAlign)
            random.readFully(bytes)

            val combinedGain = clip.gainPermille.toDouble() * trackGainPermille.toDouble() / 1_000_000.0
            var position = firstPosition
            for (outputFrame in start until end) {
                val lowerFrame = clip.trim.startFrame + position.offset
                val upperFrame = minOf(clip.trim.endFrame - 1L, lowerFrame + 1L)
                val fraction = position.remainder.toDouble() / ratio.denominator
                val outputIndex = ((outputFrame - blockStart).toInt()) * outputChannels
                for (channel in 0 until outputChannels) {
                    val sourceChannel = if (clip.source.channels == 1) 0 else channel
                    val lower = sample(bytes, firstFrame, lowerFrame, sourceChannel)
                    val upper = sample(bytes, firstFrame, upperFrame, sourceChannel)
                    val interpolated = lower + (upper - lower) * fraction
                    sums[outputIndex + channel] += interpolated * combinedGain
                }
                position = advance(position, 1, ratio)
            }
            nextLocalOutputFrame = localFirst + (end - start)
            nextPosition = position
        }

        private var nextLocalOutputFrame = 0L
        private var nextPosition = Position(0L, 0L)

        private data class Position(val offset: Long, val remainder: Long)

        private fun mappingAt(localOutputFrame: Long, ratio: RateRatio): Position {
            require(localOutputFrame >= nextLocalOutputFrame) { "Clip frames must be rendered in order." }
            nextPosition = advance(nextPosition, Math.toIntExact(localOutputFrame - nextLocalOutputFrame), ratio)
            nextLocalOutputFrame = localOutputFrame
            return nextPosition
        }

        private fun advance(start: Position, count: Int, ratio: RateRatio): Position {
            var offset = start.offset
            var remainder = start.remainder
            repeat(count) {
                val accumulated = remainder + ratio.numerator
                offset = Math.addExact(offset, accumulated / ratio.denominator)
                remainder = accumulated % ratio.denominator
            }
            return Position(offset, remainder)
        }

        private fun sample(bytes: ByteArray, startFrame: Long, frame: Long, channel: Int): Int {
            val offset = Math.toIntExact((frame - startFrame) * format.blockAlign + channel * 2L)
            val raw = (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
            return if (raw and 0x8000 != 0) raw - 0x1_0000 else raw
        }

        override fun close() = random.close()
    }
}
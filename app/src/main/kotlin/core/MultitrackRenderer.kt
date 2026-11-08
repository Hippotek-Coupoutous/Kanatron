package com.kanatron.app.core

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

/** Sequential PCM16 rendering; working sample memory is limited by one output block and one source block. */
object MultitrackRenderer {
    const val BLOCK_FRAMES = 1_024
    private const val TEMP_PREFIX = "mix-render-"
    private const val TEMP_SUFFIX = ".tmp"

    /** True for scratch files this renderer may leave behind; sweepers delete only these. */
    fun isRenderTemp(name: String): Boolean = name.startsWith(TEMP_PREFIX) && name.endsWith(TEMP_SUFFIX)

    fun frameCount(tracks: List<ProjectTrack>, sampleRate: Int): Long {
        require(sampleRate in SUPPORTED_SAMPLE_RATES)
        return tracks.flatMap(ProjectTrack::clips).maxOfOrNull { clip ->
            Math.addExact(clip.positionFrames, clipFrameCount(clip, sampleRate))
        } ?: 0L
    }

    fun clipFrameCount(clip: ProjectClip, sampleRate: Int): Long =
        resampledFrames(clip.trim.frameCount, clip.source.sampleRate, sampleRate, clip.rateCorrectionPpm)

    /** Returns true when the sum exceeded PCM16 and some samples were clamped. */
    fun renderToWav(
        project: Project,
        resolveSource: (ProjectAudioSource) -> File,
        destination: File,
        cancelled: AtomicBoolean = AtomicBoolean(false)
    ): Boolean {
        val frames = project.totalFrames
        val channels = project.tracks.flatMap(ProjectTrack::clips).maxOfOrNull { it.source.channels } ?: 1
        require(channels == project.channels) { "Project channel count does not match its sources." }
        val dataBytes = Math.multiplyExact(Math.multiplyExact(frames, channels.toLong()), 2L)
        if (dataBytes > WavFormat.MAX_WAV_BYTES) throw IOException("This WAV mix exceeds the supported file size.")

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

            val temp = File.createTempFile(TEMP_PREFIX, TEMP_SUFFIX, parent)
            var committed = false
            var clipped = false
            try {
                FileOutputStream(temp).use { output ->
                    WavFormat.writeHeader(output, channels, project.sampleRate, dataBytes)
                    val pcm = ByteArray(BLOCK_FRAMES * channels * 2)
                    var start = 0L
                    while (start < frames) {
                        if (cancelled.get()) throw IOException("Render cancelled.")
                        val count = minOf(BLOCK_FRAMES.toLong(), frames - start).toInt()
                        val sums = DoubleArray(count * channels)
                        for ((track, clip) in active) {
                            byClipId.getValue(clip.id).addBlock(clip, track.gainPermille, start, count, channels, sums)
                        }
                        var byteOffset = 0
                        for (sum in sums) {
                            val rounded = Math.round(sum)
                            val sample = rounded.coerceIn(Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong()).toInt()
                            if (sample.toLong() != rounded) clipped = true
                            putPcm16(pcm, byteOffset, sample)
                            byteOffset += 2
                        }
                        output.write(pcm, 0, byteOffset)
                        start += count
                    }
                    output.fd.sync()
                }
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                committed = true
                return clipped
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
        require(sourceRate in SUPPORTED_SAMPLE_RATES && targetRate in SUPPORTED_SAMPLE_RATES)
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
            return pcm16At(bytes, offset)
        }

        override fun close() = random.close()
    }
}
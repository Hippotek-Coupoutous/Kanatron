package com.ottomusic.sketch.data

import com.ottomusic.sketch.core.AacPrimingProbe
import com.ottomusic.sketch.core.ExportPreflight
import com.ottomusic.sketch.core.TrimRange
import com.ottomusic.sketch.platform.PlatformPorts
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean

class UnsupportedAacExportException : IOException(
    "AAC-LC export isn't available on this device for this audio format."
)

/** Streams selected PCM through the platform encoder. */
interface AacEncoder {
    fun isAvailable(sampleRate: Int, channels: Int, outputDirectory: File): Boolean

    fun create(
        output: File,
        sampleRate: Int,
        channels: Int,
        bitRate: Int,
        cancelled: AtomicBoolean
    ): AacEncodingSession
}

interface AacEncodingSession : AutoCloseable {
    fun queuePcm(bytes: ByteArray, length: Int, presentationTimeUs: Long)
    fun finish()
}

object AacM4aExporter {
    private const val CHUNK_BYTES = 8 * 1024
    private const val CONTAINER_AND_PROBE_BYTES = 64 * 1024L

    fun export(
        source: File,
        destination: File,
        trim: TrimRange,
        encoder: AacEncoder,
        cancelled: AtomicBoolean = AtomicBoolean(false),
        onProgress: (copiedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): File {
        val selection = WavExporter.selection(source, destination, trim)
        val format = selection.format
        WavExporter.checkCancelled(cancelled)
        if (!encoder.isAvailable(format.sampleRate, format.channels, selection.parent)) {
            throw UnsupportedAacExportException()
        }
        WavExporter.checkCancelled(cancelled)
        if (!ExportPreflight.hasEnoughOutputSpace(
                PlatformPorts.availableBytes(selection.parent),
                estimateOutputBytes(trim.frameCount, format.sampleRate, format.channels)
            )) {
            throw IOException("Not enough free space to save this export.")
        }

        val temp = ExportActivePaths.createTracked {
            Files.createTempFile(selection.parent.toPath(), ".m4a-export-", ".tmp").toFile()
        }
        var committed = false
        try {
            WavExporter.checkCancelled(cancelled)
            val blockAlign = format.blockAlign
            RandomAccessFile(selection.source, "r").use { input ->
                input.seek(selection.dataOffset)
                encoder.create(
                    temp, format.sampleRate, format.channels, bitRate(format.channels), cancelled
                ).use { session ->
                    val buffer = ByteArray(CHUNK_BYTES)
                    var copied = 0L
                    while (copied < selection.payloadSize) {
                        WavExporter.checkCancelled(cancelled)
                        val wanted = minOf(buffer.size.toLong(), selection.payloadSize - copied).toInt()
                        input.readFully(buffer, 0, wanted)
                        val framesBeforeChunk = copied / blockAlign
                        session.queuePcm(buffer, wanted, framesBeforeChunk * 1_000_000L / format.sampleRate)
                        copied += wanted
                        onProgress(copied, selection.payloadSize)
                    }
                    WavExporter.checkCancelled(cancelled)
                    session.finish()
                }
            }

            WavExporter.checkCancelled(cancelled)
            FileOutputStream(temp, true).use { it.fd.sync() }
            WavExporter.checkCancelled(cancelled)
            WavExporter.moveAtomically(temp.toPath(), selection.destination.toPath())
            committed = true
            return selection.destination
        } finally {
            try { if (!committed) Files.deleteIfExists(temp.toPath()) }
            finally { ExportActivePaths.finish(temp) }
        }
    }

    fun estimateOutputBytes(frameCount: Long, sampleRate: Int, channels: Int): Long {
        require(frameCount >= 0 && sampleRate > 0)
        val paddedFrames = Math.addExact(frameCount, AacPrimingProbe.MAX_DELAY_FRAMES.toLong())
        val bytes = BigDecimal.valueOf(paddedFrames)
            .multiply(BigDecimal.valueOf(bitRate(channels).toLong()))
            .divide(BigDecimal.valueOf(sampleRate.toLong() * 8L), 0, RoundingMode.CEILING)
            .longValueExact()
        // Budget both movie tables (the original becomes a free box), plus the bounded
        // codec probe. Per-packet sample/offset/timing tables grow with duration.
        val packets = Math.addExact(paddedFrames, 1023L) / 1024L
        return Math.addExact(bytes, Math.addExact(CONTAINER_AND_PROBE_BYTES, Math.multiplyExact(packets, 64L)))
    }

    private fun bitRate(channels: Int): Int = when (channels) {
        1 -> 96_000
        2 -> 128_000
        else -> throw UnsupportedAacExportException()
    }

}

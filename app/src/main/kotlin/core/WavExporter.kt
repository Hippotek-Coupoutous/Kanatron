package com.ottomusic.sketch.core

import com.ottomusic.sketch.data.ExportActivePaths
import com.ottomusic.sketch.platform.PlatformPorts
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean

class ExportCancelledException : IOException("Audio export cancelled")

internal data class WavExportSlice(
    val source: File,
    val destination: File,
    val parent: File,
    val format: WavFormat,
    val payloadSize: Long,
    val dataOffset: Long
)

object WavExporter {
    fun export(
        source: File,
        destination: File,
        trim: TrimRange,
        cancelled: AtomicBoolean = AtomicBoolean(false),
        onProgress: (copiedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): File {
        val slice = selection(source, destination, trim)
        val sourceFile = slice.source
        val destinationFile = slice.destination
        val parent = slice.parent
        val format = slice.format
        val payloadSize = slice.payloadSize
        if (!ExportPreflight.hasEnoughSpace(PlatformPorts.availableBytes(parent), payloadSize)) {
            throw IOException("Not enough free space to save this export.")
        }

        val tempFile = ExportActivePaths.createTracked {
            Files.createTempFile(parent.toPath(), ".wav-export-", ".tmp").toFile()
        }
        var committed = false
        try {
            RandomAccessFile(sourceFile, "r").use { input ->
                input.seek(slice.dataOffset)
                FileOutputStream(tempFile).use { output ->
                    checkCancelled(cancelled)
                    WavFormat.writeHeader(output, format.channels, format.sampleRate, payloadSize)
                    val buffer = ByteArray(8192)
                    var copied = 0L
                    while (copied < payloadSize) {
                        checkCancelled(cancelled)
                        val wanted = minOf(buffer.size.toLong(), payloadSize - copied).toInt()
                        input.readFully(buffer, 0, wanted)
                        output.write(buffer, 0, wanted)
                        copied += wanted
                        onProgress(copied, payloadSize)
                    }
                    checkCancelled(cancelled)
                    output.fd.sync()
                }
            }

            checkCancelled(cancelled)
            moveAtomically(tempFile.toPath(), destinationFile.toPath())
            committed = true
            return destinationFile
        } finally {
            try { if (!committed) Files.deleteIfExists(tempFile.toPath()) }
            finally { ExportActivePaths.finish(tempFile) }
        }
    }

    internal fun selection(source: File, destination: File, trim: TrimRange): WavExportSlice {
        val sourceFile = source.canonicalFile
        val destinationFile = destination.absoluteFile
        require(sourceFile != destinationFile.canonicalFile) { "Export destination must differ from the source" }
        val parent = destinationFile.parentFile ?: throw IOException("Destination has no parent directory")
        if (!parent.isDirectory) throw IOException("Destination directory does not exist")

        val format = sourceFile.inputStream().use(WavFormat::read)
        trim.requireValidFor(format.frameCount)
        val payloadSize = Math.multiplyExact(trim.frameCount, format.blockAlign.toLong())
        val dataOffset = Math.addExact(format.dataOffset, Math.multiplyExact(trim.startFrame, format.blockAlign.toLong()))
        return WavExportSlice(sourceFile, destinationFile, parent, format, payloadSize, dataOffset)
    }

    internal fun checkCancelled(cancelled: AtomicBoolean) {
        if (cancelled.get()) throw ExportCancelledException()
    }

    internal fun moveAtomically(from: Path, to: Path) {
        Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

}
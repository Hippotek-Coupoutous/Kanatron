package com.ottomusic.sketch.data

import com.ottomusic.sketch.core.SUPPORTED_SAMPLE_RATES
import com.ottomusic.sketch.core.UnsupportedWavEncodingException
import com.ottomusic.sketch.core.WavFormat
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile

/** Imports strict RIFF/WAVE PCM16 directly, removing container chunks without changing samples. */
internal object WavFileImporter {
    fun importIfWav(
        source: File,
        output: File,
        sourceName: String,
        cancellation: ImportCancellation,
        progress: (writtenBytes: Long, expectedBytes: Long?) -> Unit,
        checkFreeSpace: (Long) -> Unit = {}
    ): ImportedAudio? {
        if (!source.inputStream().use { input ->
                val signature = ByteArray(12)
                var read = 0
                while (read < signature.size) {
                    val count = input.read(signature, read, signature.size - read)
                    if (count < 0) break
                    read += count
                }
                read >= 4 && String(signature, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                    (read < signature.size || String(signature, 8, 4, Charsets.US_ASCII) == "WAVE")
            }) return null

        val format = try {
            FileInputStream(source).use(WavFormat::read)
        } catch (unsupported: UnsupportedWavEncodingException) {
            throw AudioImportException(
                "This WAV format is unsupported. Use mono or stereo PCM16 WAV; 8-bit, 24-bit, and floating-point WAV must be converted first.",
                unsupported
            )
        } catch (malformed: IOException) {
            throw AudioImportException("Invalid WAV file: ${malformed.message ?: "malformed RIFF/WAVE data"}.", malformed)
        }
        if (format.channels !in 1..2 || format.sampleRate !in SUPPORTED_SAMPLE_RATES) {
            throw AudioImportException("This WAV format is unsupported. Use mono or stereo PCM16 WAV at 8–192 kHz.")
        }
        if (format.dataSize == 0L) throw AudioImportException("This WAV contains no audio samples.")
        val outputBytes = try {
            Math.addExact(WavFormat.HEADER_BYTES.toLong(), format.dataSize)
        } catch (failure: ArithmeticException) {
            throw AudioImportException("This WAV is too large to import.", failure)
        }
        checkFreeSpace(outputBytes)

        var complete = false
        try {
            FileOutputStream(output).use { target ->
                WavFormat.writeHeader(target, format.channels, format.sampleRate, format.dataSize)
                RandomAccessFile(source, "r").use { input ->
                    input.seek(format.dataOffset)
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    while (copied < format.dataSize) {
                        cancellation.check()
                        val count = minOf(buffer.size.toLong(), format.dataSize - copied).toInt()
                        input.readFully(buffer, 0, count)
                        target.write(buffer, 0, count)
                        copied += count
                        progress(copied, format.dataSize)
                    }
                }
                target.fd.sync()
            }
            complete = true
            return ImportedAudio(format.sampleRate, format.channels, format.frameCount, sourceName)
        } catch (cancelled: ImportCancelledException) {
            throw cancelled
        } catch (failure: Throwable) {
            if (failure is AudioImportException) throw failure
            throw AudioImportException("Could not copy PCM samples from this WAV file.", failure)
        } finally {
            if (!complete) output.delete()
        }
    }
}
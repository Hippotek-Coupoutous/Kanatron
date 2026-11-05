package com.kanatron.app.core

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.RandomAccessFile

data class RecordingLevel(val peak: Float, val rms: Float) {
    val isClipping: Boolean get() = peak >= 0.98f
    val isLow: Boolean get() = peak < 0.015f
}

/** Pure recording arithmetic shared by the recorder. */
object RecordingCore {
    fun header(sampleRate: Int, channels: Int, dataBytes: Long): ByteArray =
        ByteArrayOutputStream(WavFormat.HEADER_BYTES).also { WavFormat.writeHeader(it, channels, sampleRate, dataBytes) }.toByteArray()

    fun repairableDataBytes(fileLength: Long, channels: Int = 1): Long {
        require(channels > 0)
        val align = channels * 2L
        return ((fileLength - WavFormat.HEADER_BYTES).coerceAtLeast(0L) / align) * align
    }

    fun elapsedMillis(frames: Long, sampleRate: Int): Long {
        require(frames >= 0 && sampleRate > 0)
        return (frames / sampleRate) * 1000L + (frames % sampleRate) * 1000L / sampleRate
    }

    /**
     * Clock text for [milliseconds]: mm:ss, or h:mm:ss past an hour, plus .mmm when [withMillis].
     * Without [hours] the minute field counts total minutes instead, e.g. m:ss.mmm.
     */
    fun formatClock(milliseconds: Long, withMillis: Boolean = false, hours: Boolean = true): String {
        val seconds = milliseconds / 1_000L
        val tail = if (withMillis) "%02d.%03d".format(seconds % 60L, milliseconds % 1_000L) else "%02d".format(seconds % 60L)
        if (!hours) return "%d:%s".format(milliseconds / 60_000L, tail)
        return if (seconds >= 3_600L) "%d:%02d:%s".format(seconds / 3_600L, seconds / 60L % 60L, tail)
        else "%02d:%s".format(seconds / 60L, tail)
    }

    fun hasFreeSpace(freeBytes: Long, reserveBytes: Long): Boolean {
        require(freeBytes >= 0 && reserveBytes >= 0)
        return freeBytes >= reserveBytes
    }

    fun allSamplesZero(pcm16le: ByteArray, byteCount: Int = pcm16le.size): Boolean {
        require(byteCount in 0..pcm16le.size && byteCount % 2 == 0)
        return (0 until byteCount).all { pcm16le[it] == 0.toByte() }
    }

    fun allSamplesZero(file: File, dataOffset: Long, dataSize: Long): Boolean {
        require(dataOffset >= 0L && dataSize >= 0L)
        RandomAccessFile(file, "r").use { input ->
            input.seek(dataOffset)
            var remaining = dataSize
            val buffer = ByteArray(8192)
            while (remaining > 0L) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read < 0) throw EOFException("Truncated PCM data")
                if ((0 until read).any { buffer[it] != 0.toByte() }) return false
                remaining -= read
            }
            return true
        }
    }

    fun isSustainedSilence(silentFrames: Long, sampleRate: Int): Boolean {
        require(silentFrames >= 0L && sampleRate > 0)
        return silentFrames >= sampleRate * 5L
    }

    fun level(pcm16le: ByteArray, byteCount: Int = pcm16le.size): RecordingLevel {
        require(byteCount in 0..pcm16le.size && byteCount % 2 == 0)
        if (byteCount == 0) return RecordingLevel(0f, 0f)
        var peak = 0
        var squares = 0.0
        val samples = byteCount / 2
        for (i in 0 until samples) {
            val offset = i * 2
            val sample = pcm16At(pcm16le, offset)
            val magnitude = kotlin.math.abs(sample)
            peak = maxOf(peak, magnitude)
            squares += sample.toDouble() * sample.toDouble()
        }
        return RecordingLevel(peak / 32768f, kotlin.math.sqrt(squares / samples).toFloat() / 32768f)
    }
}
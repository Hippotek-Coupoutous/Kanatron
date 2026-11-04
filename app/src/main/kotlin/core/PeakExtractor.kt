package com.ottomusic.sketch.core

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.CancellationException

data class Peak(val min: Short, val max: Short)

object PeakExtractor {
    internal fun extract(wav: File, bucketCount: Int, isCancelled: () -> Boolean): List<Peak> {
        require(bucketCount > 0)
        val format = wav.inputStream().use(WavFormat::read)
        val align = format.blockAlign
        val frameCount = format.frameCount
        return RandomAccessFile(wav, "r").use { input ->
            val buffer = ByteArray((8192 / align * align).coerceAtLeast(align))
            List(bucketCount) { bucket ->
                checkCancelled(isCancelled)
                val first = bucket.toLong() * frameCount / bucketCount
                val end = (bucket + 1L) * frameCount / bucketCount
                if (first == end) return@List Peak(0, 0)
                input.seek(format.dataOffset + first * align)
                var remaining = (end - first) * align
                var minimum = Short.MAX_VALUE
                var maximum = Short.MIN_VALUE
                while (remaining > 0) {
                    checkCancelled(isCancelled)
                    val wanted = minOf(buffer.size.toLong(), remaining).toInt()
                    input.readFully(buffer, 0, wanted)
                    for (offset in 0 until wanted step 2) {
                        val sample = ((buffer[offset + 1].toInt() shl 8) or (buffer[offset].toInt() and 0xff)).toShort()
                        if (sample < minimum) minimum = sample
                        if (sample > maximum) maximum = sample
                    }
                    remaining -= wanted
                }
                Peak(minimum, maximum)
            }
        }
    }

    fun extractCached(wav: File, bucketCount: Int, isCancelled: () -> Boolean = { false }): List<Peak> {
        require(bucketCount > 0)
        checkCancelled(isCancelled)
        val cache = File(wav.parentFile, ".${wav.name}.$bucketCount.peaks")
        readCache(cache, wav, bucketCount)?.let { return it }
        val peaks = extract(wav, bucketCount, isCancelled)
        checkCancelled(isCancelled)
        runCatching { writeCache(cache, wav, bucketCount, peaks) }
        return peaks
    }

    private fun readCache(cache: File, wav: File, bucketCount: Int): List<Peak>? = runCatching {
        DataInputStream(BufferedInputStream(FileInputStream(cache))).use { input ->
            if (input.readInt() != CACHE_MAGIC || input.readLong() != wav.length() ||
                input.readLong() != wav.lastModified() || input.readInt() != bucketCount
            ) return null
            val result = List(bucketCount) { Peak(input.readShort(), input.readShort()) }
            if (input.read() != -1) return null
            result
        }
    }.getOrNull()

    private fun writeCache(cache: File, wav: File, bucketCount: Int, peaks: List<Peak>) {
        val temp = File.createTempFile(".peaks-", ".tmp", cache.absoluteFile.parentFile)
        try {
            FileOutputStream(temp).use { file ->
                DataOutputStream(BufferedOutputStream(file)).use { output ->
                    output.writeInt(CACHE_MAGIC)
                    output.writeLong(wav.length())
                    output.writeLong(wav.lastModified())
                    output.writeInt(bucketCount)
                    peaks.forEach { output.writeShort(it.min.toInt()); output.writeShort(it.max.toInt()) }
                    output.flush()
                    file.fd.sync()
                }
            }
            Files.move(temp.toPath(), cache.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temp.delete()
        }
    }

    private fun checkCancelled(isCancelled: () -> Boolean) {
        if (isCancelled()) throw CancellationException("Waveform extraction cancelled")
    }

    private const val CACHE_MAGIC = 0x41575031
}
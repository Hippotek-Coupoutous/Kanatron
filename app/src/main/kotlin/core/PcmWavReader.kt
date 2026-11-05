package com.ottomusic.sketch.core

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.RandomAccessFile

object PcmWavReader {

    class Pcm(val sampleRate: Int, val channels: Int, val samples: ShortArray) {
        init {
            require(sampleRate > 0) { "Invalid sample rate" }
            require(channels > 0) { "Invalid channel count" }
            require(samples.size % channels == 0) { "Samples must contain complete frames" }
        }
    }

    fun readPcm16(file: File): Pcm {
        val format = FileInputStream(file).use { fileInput -> WavFormat.read(BufferedInputStream(fileInput)) }
        if (format.dataSize > Int.MAX_VALUE.toLong()) throw IOException("WAV data is too large to load")
        val samples = ShortArray((format.dataSize / 2L).toInt())
        RandomAccessFile(file, "r").use { input ->
            input.seek(format.dataOffset)
            val bytes = ByteArray(8192)
            var sampleIndex = 0
            while (sampleIndex < samples.size) {
                val wanted = minOf(bytes.size, (samples.size - sampleIndex) * 2)
                input.readFully(bytes, 0, wanted)
                for (offset in 0 until wanted step 2) {
                    samples[sampleIndex++] = pcm16At(bytes, offset).toShort()
                }
            }
        }
        return Pcm(format.sampleRate, format.channels, samples)
    }
}

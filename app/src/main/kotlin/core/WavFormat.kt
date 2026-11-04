package com.ottomusic.sketch.core

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class UnsupportedWavEncodingException(message: String) : IOException(message)

data class WavFormat(
    val channels: Int,
    val sampleRate: Int,
    val bitsPerSample: Int,
    val dataOffset: Long,
    val dataSize: Long
) {
    val blockAlign: Int get() = channels * (bitsPerSample / 8)
    val byteRate: Long get() = sampleRate.toLong() * blockAlign
    val frameCount: Long get() = dataSize / blockAlign

    companion object {
        const val HEADER_BYTES = 44
        private const val UINT32_MAX = 0xFFFF_FFFFL

        fun read(input: InputStream): WavFormat {
            val stream = CountingInputStream(input)
            val riff = ByteArray(12)
            readFully(stream, riff)
            if (ascii(riff, 0, 4) != "RIFF" || ascii(riff, 8, 4) != "WAVE") {
                throw IOException("Not a RIFF/WAVE file")
            }

            val riffEnd = 8L + uint32(riff, 4)
            if (riffEnd < 12L) throw IOException("Invalid RIFF size")

            var channels: Int? = null
            var sampleRate: Int? = null
            var bitsPerSample: Int? = null
            var blockAlign: Int? = null
            var byteRate: Long? = null
            var dataOffset: Long? = null
            var dataSize: Long? = null

            while (stream.count < riffEnd) {
                if (riffEnd - stream.count < 8L) throw EOFException("Truncated WAV chunk header")
                val chunkHeader = ByteArray(8)
                readFully(stream, chunkHeader)
                val chunkId = ascii(chunkHeader, 0, 4)
                val chunkSize = uint32(chunkHeader, 4)
                if (chunkSize > riffEnd - stream.count) throw EOFException("Truncated WAV chunk")

                when (chunkId) {
                    "fmt " -> {
                        if (channels != null) throw IOException("Duplicate WAV fmt chunk")
                        if (chunkSize < 16L) throw IOException("Invalid WAV fmt chunk")
                        val format = ByteArray(16)
                        readFully(stream, format)
                        val formatTag = uint16(format, 0)
                        val parsedChannels = uint16(format, 2)
                        val parsedSampleRate = uint32(format, 4)
                        val parsedByteRate = uint32(format, 8)
                        val parsedBlockAlign = uint16(format, 12)
                        val parsedBits = uint16(format, 14)
                        if (formatTag == 0xFFFE) {
                            if (chunkSize < 40L) throw IOException("Invalid WAVE_FORMAT_EXTENSIBLE fmt chunk")
                            val extension = ByteArray(24)
                            readFully(stream, extension)
                            val extensionSize = uint16(extension, 0)
                            if (extensionSize < 22 || extensionSize.toLong() > chunkSize - 18L) throw IOException("Invalid WAVE_FORMAT_EXTENSIBLE fmt chunk")
                            if (uint16(extension, 2) != 16 || !isPcmSubformat(extension, 8)) {
                                throw UnsupportedWavEncodingException("Only WAVE_FORMAT_EXTENSIBLE PCM16 WAV is supported")
                            }
                            skipFully(stream, chunkSize - 40L)
                        } else {
                            if (formatTag != 1) throw UnsupportedWavEncodingException(if (formatTag == 3) "Floating-point WAV is unsupported" else "Compressed WAV is unsupported")
                            skipFully(stream, chunkSize - 16L)
                        }
                        if (parsedChannels == 0 || parsedSampleRate == 0L || parsedSampleRate > Int.MAX_VALUE) throw IOException("Invalid WAV rate or channel count")
                        if (parsedBits != 16) throw UnsupportedWavEncodingException("${parsedBits}-bit WAV is unsupported; only PCM16 WAV is supported")
                        channels = parsedChannels
                        sampleRate = parsedSampleRate.toInt()
                        bitsPerSample = parsedBits
                        blockAlign = parsedBlockAlign
                        byteRate = parsedByteRate
                    }
                    "data" -> {
                        if (dataOffset != null) throw IOException("Duplicate WAV data chunk")
                        dataOffset = stream.count
                        dataSize = chunkSize
                        skipFully(stream, chunkSize)
                    }
                    else -> skipFully(stream, chunkSize)
                }

                if ((chunkSize and 1L) != 0L) {
                    if (stream.count >= riffEnd) throw EOFException("Missing WAV chunk padding")
                    skipFully(stream, 1L)
                }
            }

            if (stream.read() != -1) throw IOException("Trailing data after RIFF/WAVE")

            val actualChannels = channels ?: throw IOException("Missing WAV fmt chunk")
            val actualRate = sampleRate ?: throw IOException("Missing WAV fmt chunk")
            val actualBits = bitsPerSample ?: throw IOException("Missing WAV fmt chunk")
            val actualAlign = blockAlign ?: throw IOException("Missing WAV fmt chunk")
            val actualByteRate = byteRate ?: throw IOException("Missing WAV fmt chunk")
            val actualDataOffset = dataOffset ?: throw IOException("Missing WAV data chunk")
            val actualDataSize = dataSize ?: throw IOException("Missing WAV data chunk")
            val expectedAlign = actualChannels * 2
            if (actualAlign != expectedAlign || actualByteRate != actualRate.toLong() * expectedAlign) {
                throw IOException("Inconsistent WAV PCM format fields")
            }
            if (actualDataSize % expectedAlign != 0L) throw IOException("WAV data is not frame-aligned")
            return WavFormat(actualChannels, actualRate, actualBits, actualDataOffset, actualDataSize)
        }

        fun writeHeader(output: OutputStream, channels: Int, sampleRate: Int, dataSize: Long) {
            require(channels in 1..32767) { "Invalid channel count" }
            require(sampleRate > 0) { "Invalid sample rate" }
            val align = channels * 2
            val rate = sampleRate.toLong() * align
            require(rate <= UINT32_MAX) { "Byte rate exceeds WAV limit" }
            require(dataSize >= 0 && dataSize <= UINT32_MAX - 36L) { "Data exceeds RIFF/WAV size limit" }
            require(dataSize % align == 0L) { "PCM data must contain complete frames" }

            output.write("RIFF".toByteArray(Charsets.US_ASCII))
            writeUInt32(output, 36L + dataSize)
            output.write("WAVEfmt ".toByteArray(Charsets.US_ASCII))
            writeUInt32(output, 16L)
            writeUInt16(output, 1)
            writeUInt16(output, channels)
            writeUInt32(output, sampleRate.toLong())
            writeUInt32(output, rate)
            writeUInt16(output, align)
            writeUInt16(output, 16)
            output.write("data".toByteArray(Charsets.US_ASCII))
            writeUInt32(output, dataSize)
        }

        private fun readFully(input: InputStream, bytes: ByteArray) {
            var offset = 0
            while (offset < bytes.size) {
                val read = input.read(bytes, offset, bytes.size - offset)
                if (read < 0) throw EOFException("Unexpected end of WAV file")
                offset += read
            }
        }

        private fun skipFully(input: InputStream, length: Long) {
            var remaining = length
            val buffer = ByteArray(8192)
            while (remaining > 0) {
                val skipped = input.skip(remaining)
                if (skipped > 0) {
                    remaining -= skipped
                } else {
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (read < 0) throw EOFException("Unexpected end of WAV chunk")
                    remaining -= read
                }
            }
        }

        private fun ascii(bytes: ByteArray, offset: Int, length: Int): String =
            String(bytes, offset, length, Charsets.US_ASCII)

        private fun uint16(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

        private fun uint32(bytes: ByteArray, offset: Int): Long =
            (bytes[offset].toLong() and 0xFF) or
                ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
                ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
                ((bytes[offset + 3].toLong() and 0xFF) shl 24)

        private fun isPcmSubformat(bytes: ByteArray, offset: Int): Boolean =
            bytes.copyOfRange(offset, offset + 16).contentEquals(
                byteArrayOf(1, 0, 0, 0, 0, 0, 16, 0, 0x80.toByte(), 0, 0, 0xAA.toByte(), 0, 0x38, 0x9B.toByte(), 0x71)
            )

        private fun writeUInt16(output: OutputStream, value: Int) {
            output.write(value and 0xFF)
            output.write((value ushr 8) and 0xFF)
        }

        private fun writeUInt32(output: OutputStream, value: Long) {
            repeat(4) { shift -> output.write(((value ushr (shift * 8)) and 0xFF).toInt()) }
        }
    }

    private class CountingInputStream(input: InputStream) : java.io.FilterInputStream(input) {
        var count: Long = 0
            private set

        override fun read(): Int = super.read().also { if (it >= 0) count++ }

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
            super.read(bytes, offset, length).also { if (it > 0) count += it }

        override fun skip(amount: Long): Long = super.skip(amount).also { count += it }
    }
}
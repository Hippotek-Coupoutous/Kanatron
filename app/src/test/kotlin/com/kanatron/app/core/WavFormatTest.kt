package com.kanatron.app.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream

class WavFormatTest {

    @Test
    fun `canonical header describes its payload and round-trips`() {
        val out = ByteArrayOutputStream()
        WavFormat.writeHeader(out, channels = 2, sampleRate = 48_000, dataSize = 192_000)
        assertEquals(44, out.size())

        out.write(ByteArray(192_000))
        val bytes = out.toByteArray()

        assertArrayEquals("RIFF".toByteArray(Charsets.US_ASCII), bytes.copyOfRange(0, 4))
        // RIFF size counts everything after its own size field: 36 fixed bytes + payload.
        assertEquals(192_036L, uint32(bytes, 4))
        assertArrayEquals("WAVE".toByteArray(Charsets.US_ASCII), bytes.copyOfRange(8, 12))
        assertArrayEquals("fmt ".toByteArray(Charsets.US_ASCII), bytes.copyOfRange(12, 16))
        assertEquals(16L, uint32(bytes, 16))
        assertArrayEquals("data".toByteArray(Charsets.US_ASCII), bytes.copyOfRange(36, 40))

        val format = WavFormat.read(bytes.inputStream())
        assertEquals(2, format.channels)
        assertEquals(48_000, format.sampleRate)
        assertEquals(16, format.bitsPerSample)
        assertEquals(44L, format.dataOffset)
        assertEquals(192_000L, format.dataSize)
        assertEquals(48_000L, format.frameCount)
        assertEquals(4, format.blockAlign)
        assertEquals(192_000L, format.byteRate)
    }

    @Test
    fun `output size includes the canonical header`() {
        // One second at 48 kHz stereo 16-bit: 48 000 frames x 2 channels x 2 bytes + 44-byte header.
        assertEquals(192_044L, WavFormat.outputBytes(frames = 48_000, channels = 2))
    }

    @Test
    fun `header rejects a dangling half-frame`() {
        // 191 999 % 4 == 3, so the payload is not a whole number of stereo frames.
        assertThrows(IllegalArgumentException::class.java) {
            WavFormat.writeHeader(ByteArrayOutputStream(), channels = 2, sampleRate = 48_000, dataSize = 191_999)
        }
    }

    private fun uint32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)
}

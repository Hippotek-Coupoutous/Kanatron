package com.kanatron.app.data

import com.kanatron.app.core.WavFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WavFileImporterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `fast path copies the PCM payload and declines non-RIFF`() {
        val root = tempFolder.root
        val source = File(root, "take.wav")
        source.writeBytes(paddedSource())
        val output = File(root, "imported.wav")

        val imported = WavFileImporter.importIfWav(source, output, "My take.wav", ImportCancellation(), { _, _ -> })

        assertEquals(ImportedAudio(48_000, 1, 4, "My take.wav"), imported)
        // 44-byte canonical header + the 8-byte data chunk payload.
        assertEquals(52L, output.length())
        val format = output.inputStream().use(WavFormat::read)
        assertEquals(44L, format.dataOffset)
        assertEquals(8L, format.dataSize)
        assertEquals(4L, format.frameCount)
        assertArrayEquals(source.readBytes().copyOfRange(56, 64), output.readBytes().copyOfRange(44, 52))
    }

    @Test
    fun `non-RIFF input is declined without creating an output`() {
        val root = tempFolder.root
        val source = File(root, "song.mp3")
        source.writeBytes("ID3".toByteArray(Charsets.US_ASCII) + ByteArray(9))
        val output = File(root, "declined.wav")

        assertNull(WavFileImporter.importIfWav(source, output, "song.mp3", ImportCancellation(), { _, _ -> }))
        assertFalse(output.exists())
    }

    private fun paddedSource(): ByteArray {
        val bytes = ByteArray(64)
        bytes.ascii(0, "RIFF")
        bytes.u32(4, 56)
        bytes.ascii(8, "WAVE")
        bytes.ascii(12, "fmt ")
        bytes.u32(16, 16)
        bytes.u16(20, 1)
        bytes.u16(22, 1)
        bytes.u32(24, 48_000)
        bytes.u32(28, 96_000)
        bytes.u16(32, 2)
        bytes.u16(34, 16)
        // LIST chunk: 8-byte header, 3 data bytes, 1 pad byte.
        bytes.ascii(36, "LIST")
        bytes.u32(40, 3)
        bytes.ascii(44, "abc")
        // The data chunk starts at 48, so its payload starts at 56.
        bytes.ascii(48, "data")
        bytes.u32(52, 8)
        listOf(1, -2, 3, -4).forEachIndexed { index, sample ->
            bytes[56 + index * 2] = sample.toByte()
            bytes[57 + index * 2] = (sample shr 8).toByte()
        }
        return bytes
    }

    private fun ByteArray.ascii(offset: Int, value: String) {
        value.toByteArray(Charsets.US_ASCII).copyInto(this, offset)
    }

    private fun ByteArray.u16(offset: Int, value: Int) {
        this[offset] = value.toByte()
        this[offset + 1] = (value shr 8).toByte()
    }

    private fun ByteArray.u32(offset: Int, value: Long) {
        repeat(4) { index -> this[offset + index] = (value ushr (index * 8)).toByte() }
    }
}

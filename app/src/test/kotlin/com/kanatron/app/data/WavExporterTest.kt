package com.kanatron.app.data

import com.kanatron.app.core.TrimRange
import com.kanatron.app.core.WavFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WavExporterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `selection slices the trimmed frames and refuses the source`() {
        val root = tempFolder.root
        val source = File(root, "source.wav")
        source.outputStream().use { output ->
            WavFormat.writeHeader(output, channels = 2, sampleRate = 48_000, dataSize = 4_000)
            output.write(ByteArray(4_000))
        }

        val destination = File(root, "export.wav")
        val slice = WavExporter.selection(source, destination, TrimRange(100, 700))

        assertEquals(1_000L, slice.format.frameCount)
        // 600 trimmed frames x 4-byte stereo block align.
        assertEquals(2_400L, slice.payloadSize)
        // 44-byte header + 100 skipped frames x 4 bytes.
        assertEquals(444L, slice.dataOffset)

        assertThrows(IllegalArgumentException::class.java) {
            WavExporter.selection(source, source, TrimRange(100, 700))
        }
    }
}

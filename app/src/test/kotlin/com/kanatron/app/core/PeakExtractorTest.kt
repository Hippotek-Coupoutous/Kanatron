package com.kanatron.app.core

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PeakExtractorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun aFrameRangeReadsOnlyItsOwnFramesAndClampsAtTheEnd() {
        val wav = File(tempFolder.root, "ramp.wav")
        val payload = ByteArray(20)
        repeat(10) { putPcm16(payload, it * 2, it * 100) }
        wav.outputStream().use { output ->
            WavFormat.writeHeader(output, 1, 8_000, payload.size.toLong())
            output.write(payload)
        }

        assertEquals(listOf(Peak(200, 300), Peak(400, 500)), PeakExtractor.extract(wav, 2, { false }, 2L, 6L))
        assertEquals(listOf(Peak(800, 900)), PeakExtractor.extract(wav, 1, { false }, 8L, 50L))
    }
}

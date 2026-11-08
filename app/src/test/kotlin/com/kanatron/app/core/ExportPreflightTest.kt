package com.kanatron.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportPreflightTest {

    @Test
    fun addsTheStorageReserveOnTopOfTheExportSize() {
        val reserve = ExportPreflight.STORAGE_RESERVE_BYTES
        // 10 MiB.
        assertEquals(10_485_760L, reserve)
        // 48_000 frames x 2 channels x 2 bytes + 44-byte header.
        assertEquals(192_044L, WavFormat.outputBytes(48_000, 2))

        val outputs = listOf(WavFormat.outputBytes(48_000, 2), 20L * 1024 * 1024)
        for (output in outputs) {
            assertEquals(output + reserve, ExportPreflight.requiredOutputBytes(output))
            assertFalse(ExportPreflight.hasEnoughOutputSpace(output + reserve - 1, output))
            assertTrue(ExportPreflight.hasEnoughOutputSpace(output + reserve, output))
            // A max-style implementation would accept this, leaving nothing for the next save.
            assertFalse(ExportPreflight.hasEnoughOutputSpace(output, output))
        }

        assertEquals(10_677_804L, ExportPreflight.requiredOutputBytes(192_044))
        assertEquals(31_457_280L, ExportPreflight.requiredOutputBytes(20L * 1024 * 1024))
    }
}

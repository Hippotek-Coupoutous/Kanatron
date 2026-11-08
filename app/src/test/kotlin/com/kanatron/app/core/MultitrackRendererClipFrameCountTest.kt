package com.kanatron.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

class MultitrackRendererClipFrameCountTest {

    private data class Row(
        val totalFrames: Long,
        val trimStart: Long,
        val trimEnd: Long,
        val sourceRate: Int,
        val projectRate: Int,
        val ppm: Int,
        val expected: Long
    )

    @Test
    fun `clip length rounds half up and honours trim and rate correction`() {
        val rows = listOf(
            Row(1_000, 0, 1_000, 8_000, 48_000, 0, 6_000),
            Row(1, 0, 1, 8_000, 48_000, 0, 6),
            // 3 x 8000 / 48000 = 0.5 -> HALF_UP 1.
            Row(3, 0, 3, 48_000, 8_000, 0, 1),
            // 15 x 8000 / 48000 = 2.5 -> HALF_UP 3 (HALF_EVEN would give 2).
            Row(15, 0, 15, 48_000, 8_000, 0, 3),
            // 1 x 8000 / 48000 = 0.167 -> 0, floored at one frame.
            Row(1, 0, 1, 48_000, 8_000, 0, 1),
            Row(441, 0, 441, 44_100, 48_000, 0, 480),
            // 100 x 48000 / 44100 = 108.84 -> 109.
            Row(100, 0, 100, 44_100, 48_000, 0, 109),
            // 1000 x 1000000 / 1001000 = 999.001 -> 999.
            Row(1_000, 0, 1_000, 48_000, 48_000, 1_000, 999),
            // 1000 x 1000000 / 999000 = 1001.001 -> 1001.
            Row(1_000, 0, 1_000, 48_000, 48_000, -1_000, 1001),
            // 1000 x 1000000 / 1002000 = 998.004 -> 998.
            Row(1_000, 0, 1_000, 48_000, 48_000, 2_000, 998),
            // Trim covers 500 of 1000 frames.
            Row(1_000, 100, 600, 48_000, 48_000, 0, 500)
        )

        rows.forEach { row ->
            val clip = ProjectClip(
                "c",
                ProjectAudioSource("t.wav", row.sourceRate, 1, row.totalFrames),
                0L,
                TrimRange(row.trimStart, row.trimEnd),
                rateCorrectionPpm = row.ppm
            )
            assertEquals(row.toString(), row.expected, MultitrackRenderer.clipFrameCount(clip, row.projectRate))
        }
    }
}

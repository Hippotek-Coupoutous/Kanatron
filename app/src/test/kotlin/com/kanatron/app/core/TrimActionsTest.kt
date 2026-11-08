package com.kanatron.app.core

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrimActionsTest {

    @Test
    fun setRangeMsConvertsExactMillisecondsToFrames() {
        // 1000 ms × 48 frames/ms.
        assertEquals(
            TrimRange(48_000, 480_000),
            TrimActions(TrimRange(0, 480_000), 480_000, 48_000)
                .setRangeMs(BigDecimal("1000"), BigDecimal("0"), TrimEdge.Start)
        )
        // 250.5 × 48 = 12 024.
        assertEquals(
            TrimRange(0, 12_024),
            TrimActions(TrimRange(0, 480_000), 480_000, 48_000)
                .setRangeMs(BigDecimal("0"), BigDecimal("250.5"), TrimEdge.End)
        )
        // 750.25 × 48 = 36 012.
        assertEquals(
            TrimRange(36_012, 480_000),
            TrimActions(TrimRange(0, 480_000), 480_000, 48_000)
                .setRangeMs(BigDecimal("750.25"), BigDecimal("0"), TrimEdge.Start)
        )
        // Start moves to 3000 × 48 = 144 000, end stays.
        assertEquals(
            TrimRange(144_000, 200_000),
            TrimActions(TrimRange(10_000, 200_000), 480_000, 48_000)
                .setRangeMs(BigDecimal("3000"), BigDecimal("0"), TrimEdge.Start)
        )
        // 0.0625 × 8 = 0.5 frames, HALF_UP → 1.
        assertEquals(
            TrimRange(1, 80_000),
            TrimActions(TrimRange(0, 80_000), 80_000, 8_000)
                .setRangeMs(BigDecimal("0.0625"), BigDecimal("0"), TrimEdge.Start)
        )
        // 1E+30 ms × 48 frames/ms = 4.8E+31, overflowing longValueExact unless the guard clamps to totalFrames.
        assertEquals(
            TrimRange(0, 480_000),
            TrimActions(TrimRange(0, 480_000), 480_000, 48_000)
                .setRangeMs(BigDecimal("0"), BigDecimal("1E+30"), TrimEdge.End)
        )
    }

    @Test
    fun boundaryCommandsStayInsideAOneFrameSelection() {
        // 480 frames = 10 ms at 48 kHz.
        assertRange(10_480, 200_000, TrimActions(TrimRange(10_000, 200_000), 480_000, 48_000).nudge(TrimEdge.Start, 480))
        assertRange(10_000, 199_520, TrimActions(TrimRange(10_000, 200_000), 480_000, 48_000).nudge(TrimEdge.End, -480))
        // The start clamps to end - 1.
        assertRange(479_999, 480_000, TrimActions(TrimRange(0, 480_000), 480_000, 48_000).setStartAtFrame(480_000))
        // The end clamps to start + 1.
        assertRange(0, 1, TrimActions(TrimRange(0, 480_000), 480_000, 48_000).setEndAtFrame(0))
        assertRange(479_999, 480_000, TrimActions(TrimRange(0, 480_000), 480_000, 48_000).setRange(600_000, 700_000))
        // Crossed edges collapse to one frame.
        assertRange(1_000, 1_001, TrimActions(TrimRange(0, 480_000), 480_000, 48_000).setRange(1_000, 500))
        assertRange(0, 480_000, TrimActions(TrimRange(0, 480_000), 480_000, 48_000).setEndAtFrame(Long.MAX_VALUE))
    }

    private fun assertRange(start: Long, end: Long, actual: TrimRange, totalFrames: Long = 480_000) {
        assertEquals(TrimRange(start, end), actual)
        assertTrue(actual.startFrame >= 0L)
        assertTrue(actual.startFrame < actual.endFrame)
        assertTrue(actual.endFrame <= totalFrames)
    }
}

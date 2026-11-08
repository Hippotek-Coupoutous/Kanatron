package com.kanatron.app.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ClickSchedulingTest {
    @Test
    fun beatFramesUseAbsoluteBeatIndex() {
        assertEquals(24_000L, frameAtBeat(1, 48_000, 120_000))
        assertEquals(96_000L, frameAtBeat(4, 48_000, 120_000))
        assertEquals(192_000L, frameAtBeat(8, 48_000, 120_000))
        // 2,880,000 frames per minute at 130 BPM; one beat is 22,153.846 → 22,154
        assertEquals(22_154L, frameAtBeat(1, 48_000, 130_000))
        // 65 beats is exactly 30 s
        assertEquals(1_440_000L, frameAtBeat(65, 48_000, 130_000))
        // 130 beats is exactly 60 s
        assertEquals(2_880_000L, frameAtBeat(130, 48_000, 130_000))
    }

    @Test
    fun blockSplitsDoNotMoveOrDuplicateClicks() {
        val click = shortArrayOf(1000, -500, 250)
        val single = ShortArray(192_000)
        mixClicks(single, 0, 48_000, 120_000) { click }
        assertEquals(1000, single[24_000].toInt())
        assertEquals(-500, single[24_001].toInt())
        assertEquals(250, single[24_002].toInt())
        assertEquals(0, single[23_999].toInt())
        assertEquals(0, single[24_003].toInt())
        assertEquals(0, single[191_999].toInt())
        assertEquals(1000, single[72_000].toInt())
        assertEquals(-500, single[72_001].toInt())
        assertEquals(250, single[72_002].toInt())

        val chunked = ShortArray(192_000)
        var start = 0
        for (size in intArrayOf(1, 23_999, 2, 48_000, 1, 7, 119_990)) {
            val block = ShortArray(size)
            mixClicks(block, start.toLong(), 48_000, 120_000) { click }
            block.copyInto(chunked, start)
            start += size
        }
        assertArrayEquals(single, chunked)
    }

    @Test
    fun blockSplitsDoNotMoveOrDuplicateClicksAt130Bpm() {
        val click = shortArrayOf(1000, -500, 250)
        val single = ShortArray(100_000)
        mixClicks(single, 0, 48_000, 130_000) { click }

        val chunked = ShortArray(100_000)
        var start = 0
        for (size in intArrayOf(22_156, 44_308, 33_536)) {
            val block = ShortArray(size)
            mixClicks(block, start.toLong(), 48_000, 130_000) { click }
            block.copyInto(chunked, start)
            start += size
        }
        assertArrayEquals(single, chunked)
    }

    @Test
    fun mixClampsAtPcm16Limits() {
        val loud = ShortArray(1) { 32_000.toShort() }
        mixClicks(loud, 0, 48_000, 120_000) { shortArrayOf(2_000.toShort()) }
        assertEquals(32_767, loud[0].toInt())

        val quiet = ShortArray(1) { (-32_000).toShort() }
        mixClicks(quiet, 0, 48_000, 120_000) { shortArrayOf((-2_000).toShort()) }
        assertEquals(-32_768, quiet[0].toInt())
    }
}

package com.kanatron.app.recording

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class FrameAlignerTest {
    @Test
    fun emitsEveryCompleteFrameAcrossArbitraryReadBoundaries() {
        val stream = ByteArray(40) { it.toByte() }
        val chunks = intArrayOf(3, 0, 1, 7, 2, 9, 18)
        val aligner = FrameAligner(frameBytes = 4)
        val scratch = ByteArray(64)
        val result = ByteArray(40)
        var written = 0
        var read = 0

        for (size in chunks) {
            val input = stream.copyOfRange(read, read + size)
            val produced = aligner.align(input, size, scratch)
            scratch.copyInto(result, written, 0, produced)
            written += produced
            read += size
        }

        assertEquals(40, written)
        assertArrayEquals(stream, result)
    }

    @Test
    fun clearDropsCarriedPartialFrame() {
        val aligner = FrameAligner(frameBytes = 4)
        val scratch = ByteArray(64)

        assertEquals(0, aligner.align(byteArrayOf(7, 8), 2, scratch))
        aligner.clear()
        val produced = aligner.align(byteArrayOf(1, 2, 3, 4), 4, scratch)

        assertEquals(4, produced)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), scratch.copyOf(produced))
    }
}

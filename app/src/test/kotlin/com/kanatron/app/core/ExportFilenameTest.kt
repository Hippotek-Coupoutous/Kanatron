package com.kanatron.app.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportFilenameTest {

    @Test
    fun producesSafeSingleSegmentWavNames() {
        val cases = listOf(
            Triple("My Song", "Fallback", "My Song.wav"),
            // An existing .wav suffix is replaced, never doubled.
            Triple("My Song.WAV", "Fallback", "My Song.wav"),
            // '/', ':', '*' and '?' are each replaced by one underscore.
            Triple("a/b:c*?", "x", "a_b_c__.wav"),
            // A newline is replaced like any other unsupported character.
            Triple("Live\nTake", "x", "Live_Take.wav"),
            Triple("   ", "Fallback", "Fallback.wav"),
            // A stem of only dots trims to empty and an empty fallback lands on "Recording".
            Triple("...", "", "Recording.wav")
        )
        for ((input, fallback, expected) in cases) {
            val result = ExportFilename.wav(input, fallback)
            assertEquals("input=$input", expected, result)
            assertTrue(result.endsWith(".wav"))
            assertEquals("input=$input", result, File(result).name)
        }

        val long = ExportFilename.wav("x".repeat(300), "Fallback")
        assertEquals("x".repeat(300) + ".wav", long)
        assertTrue(long.endsWith(".wav"))
        assertEquals(long, File(long).name)
    }
}

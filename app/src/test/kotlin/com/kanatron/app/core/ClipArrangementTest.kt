package com.kanatron.app.core

import com.kanatron.app.data.ClipPart
import com.kanatron.app.data.clipArrangement
import org.junit.Assert.assertEquals
import org.junit.Test

class ClipArrangementTest {
    private fun recording(id: String, rate: Int): Project {
        val source = ProjectAudioSource("recordings/$id.wav", rate, 1, rate * 10L)
        return Project(id, id, rate, 1, 0,
            tracks = listOf(ProjectTrack("track-$id", id, listOf(ProjectClip("clip-$id", source, 0, TrimRange(0, source.totalFrames))))))
    }

    @Test
    fun `removing original lane preserves timing even when remaining sources use another rate`() {
        val first = recording("a", 48_000)
        val second = recording("b", 44_100)
        val parts = listOf(
            ClipPart(first, TrimRange(0, 48_000), 0),
            ClipPart(second, TrimRange(0, 44_100), 96_000),
            ClipPart(second, TrimRange(44_100, 88_200), 192_000)
        )
        val grid = MusicalGrid(true, 3_000, 6, true)
        val remaining = clipArrangement(parts.drop(1), 48_000, grid)
        assertEquals(48_000, remaining.sampleRate)
        assertEquals(listOf(96_000L, 192_000L), remaining.tracks.single().clips.map { it.positionFrames })
        assertEquals(240_000L, remaining.totalFrames)
        assertEquals(grid, remaining.grid)
    }
}

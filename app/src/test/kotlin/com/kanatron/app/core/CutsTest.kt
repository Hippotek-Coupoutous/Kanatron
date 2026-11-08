package com.kanatron.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

class CutsTest {

    @Test
    fun cutsKeepAndDeleteWorkOnTheTimelineAcrossPieces() {
        val whole = listOf(TrimRange(0, 100))
        val gap = Cuts.delete(whole, TrimRange(20, 30))
        assertEquals(listOf(TrimRange(0, 20), TrimRange(30, 100)), gap)
        assertEquals(90L, Cuts.length(gap))

        // Timeline 15..25 spans the join: source 15..20 then 30..35.
        assertEquals(listOf(TrimRange(15, 20), TrimRange(30, 35)), Cuts.keep(gap, TrimRange(15, 25)))
        assertEquals(listOf(TrimRange(0, 15), TrimRange(35, 100)), Cuts.delete(gap, TrimRange(15, 25)))

        assertEquals(listOf(TrimRange(0, 10)), Cuts.keep(whole, TrimRange(0, 10)))
        assertEquals(listOf(TrimRange(5, 20), TrimRange(30, 100)), Cuts.delete(gap, TrimRange(0, 5)))
    }

    @Test
    fun arrangementHoldsManyCutsAndKeepsTheTimelineLength() {
        val source = ProjectAudioSource("a.wav", 8_000, 1, 10_000)
        val recording = Project("p", "A", 8_000, 1, 0L, tracks = listOf(
            ProjectTrack("track-main", "A", listOf(ProjectClip("clip-main", source, 0L, TrimRange(0, 10_000))))
        ))
        var pieces = listOf(TrimRange(0, 10_000))
        repeat(20) { pieces = Cuts.delete(pieces, TrimRange(it * 100L + 50, it * 100L + 60)) }

        assertEquals(21, pieces.size)
        assertEquals(Cuts.length(pieces), Cuts.arrangement(recording, pieces).totalFrames)
    }
}

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

    @Test
    fun aJoinOfSeveralPartsPlaysThemBackToBack() {
        val a = ProjectAudioSource("a.wav", 8_000, 1, 1_000)
        val b = ProjectAudioSource("b.wav", 16_000, 2, 2_000)
        val base = Project("p", "A", 8_000, 1, 0L, tracks = listOf(ProjectTrack("track-main", "A", listOf(ProjectClip("clip-main", a, 0L, TrimRange(0, 1_000))))))

        // The same take may appear twice; b's 16 kHz frames count half at 8 kHz.
        val joined = Cuts.join(base, listOf(a to TrimRange(0, 300), b to TrimRange(0, 400), a to TrimRange(500, 600)))

        val clips = joined.tracks.single().clips
        assertEquals(listOf("a.wav", "b.wav", "a.wav"), clips.map { it.source.wav })
        assertEquals(listOf(0L, 300L, 500L), clips.map { it.positionFrames })
        // Each part but the last runs on 5 ms (40 frames at 8 kHz, 80 at b's 16 kHz) under the next one.
        assertEquals(listOf(TrimRange(0, 340), TrimRange(0, 480), TrimRange(500, 600)), clips.map { it.trim })
        assertEquals(600L, joined.totalFrames)
        assertEquals(2, joined.channels)
    }
}

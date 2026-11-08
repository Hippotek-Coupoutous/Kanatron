package com.kanatron.app.core

import com.kanatron.app.playback.MonitorClick
import org.junit.Assert.assertEquals
import org.junit.Test

class TakePlacementTest {
    @Test
    fun countInShiftsTheTakeBeforeProjectZero() {
        val countIn = MonitorClick(bpm = 120, beatsPerBar = 4, countInBars = 1).countInFrames(48_000)
        // 120 BPM is 2 beats/s; one 4-beat bar is 96,000 frames at 48 kHz
        assertEquals(96_000L, countIn)

        val timeline = SharedAudioTimeline(projectSampleRate = 48_000, countInFrames = countIn)
        timeline.observeBacking(AudioClockPoint(0, 10_000_000_000), "route-a")
        timeline.observeBacking(AudioClockPoint(480_000, 20_000_000_000), "route-a")
        timeline.observeCapture(AudioClockPoint(0, 10_000_000_001), "route-a")

        val alignment = timeline.alignmentForFirstCapturedFrame()!!
        // take frame 0 sits at backing frame 0, one count-in bar before project zero
        assertEquals(-96_000L, alignment.positionFrames)
        assertEquals(OverdubPlacement(0L, 96_000L), OverdubTiming.placement(alignment.positionFrames, null))
    }

    @Test
    fun placementAppliesCalibrationAndReportsLeadIn() {
        val minus1500 = OverdubCalibration("route-a", 44_100, 48_000, 48_000, offsetFrames = -1_500)
        val plus250 = OverdubCalibration("route-a", 44_100, 48_000, 48_000, offsetFrames = 250)

        assertEquals(OverdubPlacement(0L, 1_000L), OverdubTiming.placement(-1_000L, null))
        assertEquals(OverdubPlacement(1_000L, 0L), OverdubTiming.placement(1_000L, null))
        assertEquals(OverdubPlacement(0L, 500L), OverdubTiming.placement(1_000L, minus1500))
        assertEquals(OverdubPlacement(150L, 0L), OverdubTiming.placement(-100L, plus250))
    }

    @Test
    fun leadInTrimScalesByDeclaredRatesAndDrift() {
        // 88,120 × 44,100 / 48,000 = 80,960.25
        assertEquals(80_960L, OverdubTiming.sourceTrimForLeadIn(88_120L, 44_100, 48_000, 0))
        // 80,960.25 × 1.001 = 81,041.21
        assertEquals(81_041L, OverdubTiming.sourceTrimForLeadIn(88_120L, 44_100, 48_000, 1_000))
        // 80,960.25 × 0.999 = 80,879.29
        assertEquals(80_879L, OverdubTiming.sourceTrimForLeadIn(88_120L, 44_100, 48_000, -1_000))
    }
}

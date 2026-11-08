package com.kanatron.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioTimelineTest {
    private val route = "route-a"

    @Test
    fun mapsTakeFrameZeroOntoBackingClockAndMeasuresDrift() {
        val timeline = SharedAudioTimeline(projectSampleRate = 48_000)
        timeline.observeBacking(AudioClockPoint(0, 10_000_000_000), route)
        timeline.observeBacking(AudioClockPoint(479_952, 20_000_000_000), route)
        timeline.observeCapture(AudioClockPoint(0, 20_000_000_001), route)
        timeline.observeCapture(AudioClockPoint(480_024, 30_000_000_001), route)

        val alignment = timeline.alignmentForFirstCapturedFrame()!!
        // take frame 0 is stamped 1 ns after backing frame 479,952
        assertEquals(479_952L, alignment.positionFrames)
        // capture +50 ppm minus backing -100 ppm
        assertEquals(150, alignment.rateCorrectionPpm)
        assertEquals(route, alignment.routeKey)
    }

    @Test
    fun routeChangeLatchesInvalidation() {
        val timeline = SharedAudioTimeline(projectSampleRate = 48_000)
        timeline.observeBacking(AudioClockPoint(0, 10_000_000_000), route)
        timeline.observeBacking(AudioClockPoint(480_000, 20_000_000_000), route)
        timeline.observeCapture(AudioClockPoint(0, 20_000_000_001), route)
        assertEquals(480_000L, timeline.alignmentForFirstCapturedFrame()!!.positionFrames)

        assertFalse(timeline.observeCapture(AudioClockPoint(24_000, 20_500_000_001), "route-b"))
        assertTrue(timeline.routeInvalidated())
        assertNull(timeline.alignmentForFirstCapturedFrame())
        assertFalse(timeline.observeCapture(AudioClockPoint(24_001, 20_500_000_002), route))
        assertNull(timeline.alignmentForFirstCapturedFrame())
    }

    @Test
    fun honoursDeclaredCaptureRate() {
        val timeline = SharedAudioTimeline(
            projectSampleRate = 48_000,
            captureSampleRate = 44_100,
            backingSampleRate = 48_000
        )
        timeline.observeBacking(AudioClockPoint(0, 10_000_000_000), route)
        timeline.observeBacking(AudioClockPoint(480_000, 20_000_000_000), route)
        timeline.observeCapture(AudioClockPoint(0, 20_000_000_001), route)
        timeline.observeCapture(AudioClockPoint(441_000, 30_000_000_001), route)

        val alignment = timeline.alignmentForFirstCapturedFrame()!!
        // take frame 0 is stamped 1 ns after backing frame 480,000
        assertEquals(480_000L, alignment.positionFrames)
        // 441,000 capture frames in 10 s is exactly 44.1 kHz
        assertEquals(0, alignment.rateCorrectionPpm)
    }
}

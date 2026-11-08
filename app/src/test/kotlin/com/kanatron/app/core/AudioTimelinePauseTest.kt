package com.kanatron.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioTimelinePauseTest {
    private val route = "route-a"

    @Test
    fun pauseAfterAlignmentKeepsTakeStart() {
        val timeline = SharedAudioTimeline(projectSampleRate = 48_000)
        timeline.observeBacking(AudioClockPoint(0, 10_000_000_000), route)
        timeline.observeBacking(AudioClockPoint(480_000, 20_000_000_000), route)
        timeline.observeCapture(AudioClockPoint(0, 20_000_000_001), route)
        timeline.observeCapture(AudioClockPoint(480_000, 30_000_000_001), route)
        // capture frame 0 is stamped 1 ns after backing frame 480,000, so the take starts at 480,000
        assertEquals(480_000L, timeline.alignmentForFirstCapturedFrame()!!.positionFrames)

        timeline.pause()
        timeline.resume()
        assertEquals(480_000L, timeline.alignmentForFirstCapturedFrame()!!.positionFrames)

        timeline.observeBacking(AudioClockPoint(528_000, 21_000_000_000), route)
        timeline.observeBacking(AudioClockPoint(576_000, 22_000_000_000), route)
        timeline.observeCapture(AudioClockPoint(528_000, 31_000_000_001), route)
        timeline.observeCapture(AudioClockPoint(576_000, 32_000_000_001), route)
        assertEquals(480_000L, timeline.alignmentForFirstCapturedFrame()!!.positionFrames)
    }
}

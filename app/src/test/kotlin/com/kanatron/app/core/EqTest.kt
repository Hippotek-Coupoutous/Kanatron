package com.kanatron.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class EqTest {

    /** Steady-state gain of a sine through [eq], in dB; the first half second settles the filter. */
    private fun gainDb(eq: Eq, hz: Double, rate: Int = 48_000, channels: Int = 2): Double {
        val signal = DoubleArray(rate * channels) { sin(2 * PI * hz * (it / channels) / rate) }
        val half = signal.size / 2
        val filter = eq.filter(rate, channels)!!
        // Two blocks, so the measured half also proves history carries across a block boundary.
        filter.process(signal.copyOfRange(0, half))
        val settled = signal.copyOfRange(half, signal.size)
        val shaped = settled.copyOf().also(filter::process)
        fun rms(samples: DoubleArray) = sqrt(samples.sumOf { it * it } / samples.size)
        return 20 * log10(rms(shaped) / rms(settled))
    }

    @Test
    fun eachBandMovesItsOwnRangeByItsStepsAndLeavesTheOthers() {
        assertNull(Eq.FLAT.filter(48_000, 2))
        assertEquals(12.0, gainDb(Eq(lowSteps = 4), 30.0), 0.5)
        assertEquals(0.0, gainDb(Eq(lowSteps = 4), 3_000.0), 0.5)
        assertEquals(6.0, gainDb(Eq(midSteps = 2), Eq.MID_HZ), 0.3)
        assertEquals(0.0, gainDb(Eq(midSteps = 2), 50.0), 0.5)
        assertEquals(-12.0, gainDb(Eq(highSteps = -4), 18_000.0), 0.7)
        assertEquals(0.0, gainDb(Eq(highSteps = -4), 200.0), 0.5)
    }

    @Test
    fun aHighShelfAboveNyquistStaysStable() {
        // At 8 kHz the 8 kHz shelf would sit on Nyquist; it moves below it and still boosts the top.
        val gain = gainDb(Eq(highSteps = 4), 3_500.0, rate = 8_000, channels = 1)
        assertTrue(gain.isFinite() && gain > 3.0 && gain < 13.0)
    }
}

package com.kanatron.app.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Three-band tone a clip gets when it renders: a low shelf, a mid bell and a high shelf, in [DB_PER_STEP] steps. */
data class Eq(val lowSteps: Int = 0, val midSteps: Int = 0, val highSteps: Int = 0) {
    init {
        require(listOf(lowSteps, midSteps, highSteps).all { it in -MAX_STEPS..MAX_STEPS }) { "EQ bands stay within ±${MAX_STEPS * DB_PER_STEP} dB." }
    }

    val flat: Boolean get() = this == FLAT

    /** A fresh filter with its own history; null when there is nothing to shape. */
    fun filter(sampleRate: Int, channels: Int): EqFilter? {
        if (flat) return null
        // A band above the recording's top frequency would be unstable, so it sits just below Nyquist instead.
        fun hz(target: Double) = minOf(target, sampleRate * 0.45)
        val stages = listOfNotNull(
            Biquad.lowShelf(hz(LOW_HZ), lowSteps * DB_PER_STEP.toDouble(), sampleRate, channels).takeIf { lowSteps != 0 },
            Biquad.bell(hz(MID_HZ), midSteps * DB_PER_STEP.toDouble(), sampleRate, channels).takeIf { midSteps != 0 },
            Biquad.highShelf(hz(HIGH_HZ), highSteps * DB_PER_STEP.toDouble(), sampleRate, channels).takeIf { highSteps != 0 }
        )
        return EqFilter(stages)
    }

    companion object {
        const val DB_PER_STEP = 3
        const val MAX_STEPS = 4
        const val LOW_HZ = 120.0
        const val MID_HZ = 1_000.0
        const val HIGH_HZ = 8_000.0
        val FLAT = Eq()
    }
}

/** Runs interleaved samples through every band in turn; history carries over between blocks. */
class EqFilter internal constructor(private val stages: List<Biquad>) {
    fun process(interleaved: DoubleArray) = stages.forEach { it.process(interleaved) }
}

/** One RBJ-cookbook biquad in direct form I, with history per channel. */
internal class Biquad private constructor(
    private val b0: Double, private val b1: Double, private val b2: Double,
    private val a1: Double, private val a2: Double,
    private val channels: Int
) {
    private val x1 = DoubleArray(channels)
    private val x2 = DoubleArray(channels)
    private val y1 = DoubleArray(channels)
    private val y2 = DoubleArray(channels)

    fun process(interleaved: DoubleArray) {
        for (i in interleaved.indices) {
            val c = i % channels
            val x = interleaved[i]
            val y = b0 * x + b1 * x1[c] + b2 * x2[c] - a1 * y1[c] - a2 * y2[c]
            x2[c] = x1[c]; x1[c] = x
            y2[c] = y1[c]; y1[c] = y
            interleaved[i] = y
        }
    }

    companion object {
        private const val BELL_Q = 0.9

        private class Terms(hz: Double, db: Double, rate: Int) {
            val a = 10.0.pow(db / 40.0)
            val w = 2.0 * PI * hz / rate
            val cos = cos(w)
            val sin = sin(w)
        }

        private fun normalized(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double, channels: Int) =
            Biquad(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0, channels)

        fun bell(hz: Double, db: Double, rate: Int, channels: Int): Biquad = with(Terms(hz, db, rate)) {
            val alpha = sin / (2.0 * BELL_Q)
            normalized(1 + alpha * a, -2 * cos, 1 - alpha * a, 1 + alpha / a, -2 * cos, 1 - alpha / a, channels)
        }

        /** Shelf slope 1: the steepest shelf without an overshoot bump. */
        fun lowShelf(hz: Double, db: Double, rate: Int, channels: Int): Biquad = with(Terms(hz, db, rate)) {
            val k = 2 * sqrt(a) * sin / sqrt(2.0)
            normalized(
                a * ((a + 1) - (a - 1) * cos + k), 2 * a * ((a - 1) - (a + 1) * cos), a * ((a + 1) - (a - 1) * cos - k),
                (a + 1) + (a - 1) * cos + k, -2 * ((a - 1) + (a + 1) * cos), (a + 1) + (a - 1) * cos - k, channels
            )
        }

        fun highShelf(hz: Double, db: Double, rate: Int, channels: Int): Biquad = with(Terms(hz, db, rate)) {
            val k = 2 * sqrt(a) * sin / sqrt(2.0)
            normalized(
                a * ((a + 1) + (a - 1) * cos + k), -2 * a * ((a - 1) + (a + 1) * cos), a * ((a + 1) + (a - 1) * cos - k),
                (a + 1) - (a - 1) * cos + k, 2 * ((a - 1) - (a + 1) * cos), (a + 1) - (a - 1) * cos - k, channels
            )
        }
    }
}

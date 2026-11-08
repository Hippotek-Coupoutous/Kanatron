package com.kanatron.app.core

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Pure YIN pitch detection for normalized mono PCM samples. */
object TunerPitch {
    const val MIN_FREQUENCY_HZ = 65.4
    const val MAX_FREQUENCY_HZ = 1046.5
    const val DEFAULT_A4_HZ = 440.0

    /** Below this a frame is mic hiss or dither, whatever the room's noise floor. */
    const val MIN_GATE_RMS = 0.004
    /** The fixed gate this replaced: a noisy room never makes the tuner stricter than it was. */
    private const val MAX_GATE_RMS = 0.008
    /** Under 10x the noise floor, low E over white noise reads more than 3 cents off. */
    private const val NOISE_GATE_RATIO = 10.0
    /** A noise floor that opens the gate fully until the room proves louder. */
    const val QUIET_ROOM_FLOOR = MIN_GATE_RMS / NOISE_GATE_RATIO
    private const val CMND_THRESHOLD = 0.15
    private const val EDGE_TOLERANCE_CENTS = 2.0
    private const val LN_2 = 0.6931471805599453
    private val noteNames = arrayOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")

    fun detect(
        samples: FloatArray,
        sampleRate: Int,
        a4Hz: Double = DEFAULT_A4_HZ,
        gateRms: Double = MIN_GATE_RMS
    ): TunerPitchReading? {
        if (samples.size < 1024 || sampleRate <= 0 || !a4Hz.isFinite() || a4Hz <= 0.0) return null

        if (rms(samples) < gateRms) return null
        val mean = samples.average()
        val centered = DoubleArray(samples.size) { samples[it] - mean }

        val minLag = maxOf(2, (sampleRate / MAX_FREQUENCY_HZ).toInt())
        val maxLag = minOf(samples.size / 2, ceil(sampleRate / MIN_FREQUENCY_HZ).toInt() + 1)
        if (maxLag <= minLag) return null

        val differenceCount = samples.size - maxLag
        val differences = DoubleArray(maxLag + 1)
        for (lag in 1..maxLag) {
            var sum = 0.0
            for (i in 0 until differenceCount) {
                val delta = centered[i] - centered[i + lag]
                sum += delta * delta
            }
            differences[lag] = sum
        }

        val normalized = DoubleArray(maxLag + 1)
        var runningDifference = 0.0
        for (lag in 1..maxLag) {
            runningDifference += differences[lag]
            normalized[lag] = if (runningDifference > 0.0) differences[lag] * lag / runningDifference else 1.0
        }

        var candidate = -1
        var lag = minLag
        while (lag <= maxLag) {
            if (normalized[lag] < CMND_THRESHOLD) {
                while (lag < maxLag && normalized[lag + 1] < normalized[lag]) lag++
                candidate = lag
                break
            }
            lag++
        }
        if (candidate < 0 || candidate >= maxLag) return null

        val left = differences[candidate - 1]
        val center = differences[candidate]
        val right = differences[candidate + 1]
        val curvature = left - 2.0 * center + right
        val correction = if (abs(curvature) > 1e-12) 0.5 * (left - right) / curvature else 0.0
        val period = candidate + correction
        val rawFrequency = sampleRate / period
        val edgeTolerance = Math.pow(2.0, EDGE_TOLERANCE_CENTS / 1200.0)
        if (!rawFrequency.isFinite() || rawFrequency < MIN_FREQUENCY_HZ / edgeTolerance || rawFrequency > MAX_FREQUENCY_HZ * edgeTolerance) return null
        val frequency = rawFrequency.coerceIn(MIN_FREQUENCY_HZ, MAX_FREQUENCY_HZ)

        val midi = (69.0 + 12.0 * ln(frequency / a4Hz) / LN_2).roundToInt()
        val target = a4Hz * Math.pow(2.0, (midi - 69) / 12.0)
        val cents = 1200.0 * ln(frequency / target) / LN_2
        return TunerPitchReading(
            note = "${noteNames[Math.floorMod(midi, 12)]}${midi / 12 - 1}",
            frequencyHz = frequency,
            cents = cents
        )
    }

    fun gateRms(noiseFloor: Double): Double = (NOISE_GATE_RATIO * noiseFloor).coerceIn(MIN_GATE_RMS, MAX_GATE_RMS)

    /** Mean-centred, so a DC offset never reads as sound. */
    fun rms(samples: FloatArray): Double {
        if (samples.isEmpty()) return 0.0
        val mean = samples.average()
        return sqrt(samples.sumOf { (it - mean) * (it - mean) } / samples.size)
    }
}

data class TunerPitchReading(
    val note: String,
    val frequencyHz: Double,
    val cents: Double
)
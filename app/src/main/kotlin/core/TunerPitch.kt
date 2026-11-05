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

    private const val MIN_RMS = 0.008
    private const val CMND_THRESHOLD = 0.15
    private const val MIN_CLARITY = 0.80
    private const val EDGE_TOLERANCE_CENTS = 2.0
    private const val LN_2 = 0.6931471805599453
    private val noteNames = arrayOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")

    fun detect(samples: FloatArray, sampleRate: Int, a4Hz: Double = DEFAULT_A4_HZ): TunerPitchReading? {
        if (samples.size < 1024 || sampleRate <= 0 || !a4Hz.isFinite() || a4Hz <= 0.0) return null

        var mean = 0.0
        for (sample in samples) mean += sample.toDouble()
        mean /= samples.size
        var energy = 0.0
        val centered = DoubleArray(samples.size) { i ->
            val value = samples[i].toDouble() - mean
            energy += value * value
            value
        }
        if (sqrt(energy / samples.size) < MIN_RMS) return null

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
        if (candidate < 0 || 1 >= candidate || candidate >= maxLag) return null

        val clarity = 1.0 - normalized[candidate]
        if (clarity < MIN_CLARITY) return null

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
}

data class TunerPitchReading(
    val note: String,
    val frequencyHz: Double,
    val cents: Double
)
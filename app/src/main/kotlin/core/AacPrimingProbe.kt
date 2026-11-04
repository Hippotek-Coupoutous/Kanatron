package com.ottomusic.sketch.core

import java.io.IOException
import kotlin.math.sqrt

/** A small synthetic, band-limited marker measures the selected codec rather than guessing its delay. */
internal object AacPrimingProbe {
    const val PREFIX_FRAMES = 1_024
    const val MARKER_FRAMES = 2_048
    const val MAX_DELAY_FRAMES = 4_096
    const val TOTAL_FRAMES = PREFIX_FRAMES + MARKER_FRAMES + MAX_DELAY_FRAMES

    fun marker(): ShortArray {
        val result = ShortArray(TOTAL_FRAMES)
        var random = 19
        var filtered = 0
        for (index in 0 until MARKER_FRAMES) {
            random = random * 1_664_525 + 1_013_904_223
            val value = ((random ushr 16) and 0x7fff) - 16_384
            filtered = (3 * filtered + value) / 4
            result[PREFIX_FRAMES + index] = filtered.toShort()
        }
        return result
    }

    fun pcm(marker: ShortArray, channels: Int): ByteArray {
        require(channels in 1..2)
        return ByteArray(marker.size * channels * 2) { index ->
            val sample = marker[index / (channels * 2)].toInt()
            if (index % 2 == 0) sample.toByte() else (sample shr 8).toByte()
        }
    }

    fun delayFrames(reference: ShortArray, decoded: ShortArray, channels: Int): Int {
        require(channels in 1..2 && reference.size >= PREFIX_FRAMES + MARKER_FRAMES)
        val lastLag = minOf(MAX_DELAY_FRAMES, decoded.size / channels - PREFIX_FRAMES - MARKER_FRAMES)
        if (lastLag < 0) throw IOException("AAC codec produced too little probe audio.")
        var referenceEnergy = 0.0
        for (index in 0 until MARKER_FRAMES) {
            val value = reference[PREFIX_FRAMES + index].toDouble()
            referenceEnergy += value * value
        }
        var bestLag = -1
        var bestCorrelation = Double.NEGATIVE_INFINITY
        for (lag in 0..lastLag) {
            var dot = 0.0
            var energy = 0.0
            for (index in 0 until MARKER_FRAMES) {
                val expected = reference[PREFIX_FRAMES + index].toDouble()
                val actual = decoded[(PREFIX_FRAMES + lag + index) * channels].toDouble()
                dot += expected * actual
                energy += actual * actual
            }
            val correlation = if (energy == 0.0) 0.0 else dot / sqrt(referenceEnergy * energy)
            if (correlation > bestCorrelation) { bestCorrelation = correlation; bestLag = lag }
        }
        if (bestLag < 0 || bestCorrelation < 0.75) throw IOException("Could not measure this AAC codec's priming delay.")
        return bestLag
    }
}
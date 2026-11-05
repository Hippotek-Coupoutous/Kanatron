package com.ottomusic.sketch.playback

import com.ottomusic.sketch.core.SUPPORTED_BPM
import com.ottomusic.sketch.core.frameAtBeat
import com.ottomusic.sketch.core.mixClicks
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** Accent and normal click voices shared by the engine and the in-service mixer. */
internal const val ACCENT_CLICK_HZ = 1_500.0
internal const val ACCENT_CLICK_AMPLITUDE = 0.6
internal const val NORMAL_CLICK_HZ = 1_000.0
internal const val NORMAL_CLICK_AMPLITUDE = 0.4

data class MonitorClick(val bpm: Int, val beatsPerBar: Int, val countInBars: Int = 0) {
    init {
        require(bpm in SUPPORTED_BPM)
        require(beatsPerBar in 1..12)
        require(countInBars in 0..2)
    }

    fun countInFrames(sampleRate: Int): Long =
        if (countInBars == 0) 0L else frameAtBeat(countInBars.toLong() * beatsPerBar, sampleRate, bpm * 1_000)
}

/** Adds the existing synthesized click to PCM16 output blocks without opening another stream. */
internal class ClickPcmMixer(
    private val sampleRate: Int,
    private val channels: Int,
    private val click: MonitorClick
) {
    private val accent = synthesizeClick(sampleRate, ACCENT_CLICK_HZ, ACCENT_CLICK_AMPLITUDE)
    private val normal = synthesizeClick(sampleRate, NORMAL_CLICK_HZ, NORMAL_CLICK_AMPLITUDE)
    private var mono = ShortArray(0)

    fun renderInto(bytes: ByteArray, offset: Int, frames: Int, outputStartFrame: Long) {
        require(channels in 1..2 && offset >= 0 && frames >= 0 && offset + frames * channels * 2 <= bytes.size)
        if (mono.size != frames) mono = ShortArray(frames)
        val clicks = mono
        clicks.fill(0)
        mixClicks(clicks, outputStartFrame, sampleRate, click.bpm * 1_000) { beat ->
            if (click.beatsPerBar > 1 && beat % click.beatsPerBar == 0L) accent else normal
        }
        for (frame in 0 until frames) {
            val clickSample = clicks[frame].toInt()
            for (channel in 0 until channels) {
                val index = offset + (frame * channels + channel) * 2
                val sample = (bytes[index].toInt() and 0xff) or (bytes[index + 1].toInt() shl 8)
                val mixed = (sample.toShort().toInt() + clickSample).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                bytes[index] = mixed.toByte()
                bytes[index + 1] = (mixed shr 8).toByte()
            }
        }
    }
}

internal fun synthesizeClick(sampleRate: Int, hz: Double, amplitude: Double): ShortArray {
    val frames = sampleRate * 30 / 1_000
    val fadeFrom = frames - frames / 10
    return ShortArray(frames) { index ->
        val time = index.toDouble() / sampleRate
        val fade = if (index < fadeFrom) 1.0 else (frames - index).toDouble() / (frames - fadeFrom)
        (sin(2 * PI * hz * time) * exp(-time * 110.0) * fade * amplitude * Short.MAX_VALUE).toInt().toShort()
    }
}
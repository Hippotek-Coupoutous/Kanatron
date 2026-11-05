package com.kanatron.app.core

/** Metronome tempo accepted by the UI, the service intents and the scheduler. */
val SUPPORTED_BPM = 30..300

/** Frame of [beat] at a fixed tempo, from the absolute beat index so rounding never accumulates. 120 BPM = 120_000 milli-BPM. */
fun frameAtBeat(beat: Long, sampleRate: Int, milliBpm: Int): Long {
    require(beat >= 0 && sampleRate > 0 && milliBpm > 0)
    val numerator = Math.multiplyExact(Math.multiplyExact(beat, sampleRate.toLong()), 60_000L)
    return Math.addExact(numerator, milliBpm / 2L) / milliBpm
}

/**
 * Mixes clicks into one PCM16 mono output block that starts at absolute frame [blockStart].
 * A click that began in an earlier block continues here, so any block size gives identical output.
 * Each click must be shorter than one beat.
 */
fun mixClicks(out: ShortArray, blockStart: Long, sampleRate: Int, milliBpm: Int, clickFor: (beat: Long) -> ShortArray) {
    require(blockStart >= 0)
    val blockEnd = blockStart + out.size
    val framesPerMinute = Math.multiplyExact(sampleRate.toLong(), 60_000L)
    // Latest beat at or before blockStart: its exact position is <= blockStart, and half-up rounding of it is too.
    var beat = Math.multiplyExact(blockStart, milliBpm.toLong()) / framesPerMinute
    while (true) {
        val start = frameAtBeat(beat, sampleRate, milliBpm)
        if (start >= blockEnd) return
        val click = clickFor(beat)
        require(start + click.size <= frameAtBeat(beat + 1, sampleRate, milliBpm)) { "Click is longer than one beat" }
        for (frame in maxOf(start, blockStart) until minOf(start + click.size, blockEnd)) {
            val i = (frame - blockStart).toInt()
            out[i] = (out[i] + click[(frame - start).toInt()]).coerceIn(-32_768, 32_767).toShort()
        }
        beat++
    }
}
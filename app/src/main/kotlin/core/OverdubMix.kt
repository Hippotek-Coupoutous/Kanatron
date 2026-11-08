package com.kanatron.app.core

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * A finished take over its backing before the two are mixed: balance and nudge stay adjustable until Save.
 * [measuredOffsetFrames] is where take frame 0 lands on the backing, in its frames; negative starts before it.
 */
data class OverdubMix(
    val base: Project,
    val takeProjectId: String,
    val take: ProjectAudioSource,
    val measuredOffsetFrames: Long,
    val rateCorrectionPpm: Int,
    val nudgeMillis: Int = 0,
    /** Positive lowers the backing, negative lowers the take, [DB_PER_STEP] each. */
    val balanceSteps: Int = 0
) {
    init {
        require(nudgeMillis in -MAX_NUDGE_MILLIS..MAX_NUDGE_MILLIS)
        require(balanceSteps in -MAX_BALANCE_STEPS..MAX_BALANCE_STEPS)
        require(rateCorrectionPpm in -ProjectClip.MAX_RATE_CORRECTION_PPM..ProjectClip.MAX_RATE_CORRECTION_PPM)
        require(takeTrimFrames < take.totalFrames) { "The take would start before frame zero." }
    }

    /** Where take frame 0 lands on the backing after the nudge; negative starts before it. */
    val offsetFrames: Long
        get() = Math.addExact(measuredOffsetFrames, OverdubTiming.framesFromMilliseconds(nudgeMillis.toDouble(), base.sampleRate))

    /** Where the take's first kept frame plays on the backing. */
    val takePositionFrames: Long get() = offsetFrames.coerceAtLeast(0L)

    private val takeTrimFrames: Long
        get() = if (offsetFrames >= 0L) 0L else OverdubTiming.sourceTrimForLeadIn(-offsetFrames, take.sampleRate, base.sampleRate, rateCorrectionPpm)

    /** Null once the take would start before the backing or the nudge leaves its range. */
    fun nudged(millis: Int): OverdubMix? = runCatching { copy(nudgeMillis = nudgeMillis + millis) }.getOrNull()

    fun balanced(steps: Int): OverdubMix = copy(balanceSteps = (balanceSteps + steps).coerceIn(-MAX_BALANCE_STEPS, MAX_BALANCE_STEPS))

    /** The backing as it played during the take, plus the take, as one arrangement; balance only ever lowers a side. */
    fun arrangement(): Project {
        val backingGain = gainPermille(balanceSteps.coerceAtLeast(0))
        val takeClip = ProjectClip(
            "clip-overdub", take, takePositionFrames, TrimRange(takeTrimFrames, take.totalFrames),
            rateCorrectionPpm, gainPermille(-balanceSteps.coerceAtMost(0))
        )
        val track = base.tracks.single()
        return base.copy(
            tracks = listOf(track.copy(clips = track.clips.map { it.copy(gainPermille = backingGain) } + takeClip)),
            channels = maxOf(base.channels, take.channels)
        )
    }

    companion object {
        const val MAX_NUDGE_MILLIS = 500
        const val MAX_BALANCE_STEPS = 8
        const val DB_PER_STEP = 3

        fun gainPermille(stepsDown: Int): Int = (1_000 * 10.0.pow(-DB_PER_STEP * stepsDown / 20.0)).roundToInt()
    }
}

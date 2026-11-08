package com.kanatron.app.core

/** A hardware frame position paired with Android's monotonic audio timestamp. */
data class AudioClockPoint(val framePosition: Long, val monotonicNanos: Long) {
    init {
        require(framePosition >= 0L)
        require(monotonicNanos > 0L)
    }

    fun atFrame(targetFrame: Long, sampleRate: Int): AudioClockPoint? {
        require(targetFrame >= 0L && sampleRate > 0)
        if (framePosition < targetFrame) return null
        val time = monotonicNanos - (framePosition - targetFrame) * 1_000_000_000L / sampleRate
        return if (time > 0L) AudioClockPoint(targetFrame, time) else null
    }
}

/** Maps audio frames to the shared monotonic clock; timestamp absence is represented by no map. */
class AudioClockMap(private val nominalSampleRate: Int) {
    private var first: AudioClockPoint? = null
    private var last: AudioClockPoint? = null
    private var discontinuity = false

    init { require(nominalSampleRate > 0) }

    fun observe(point: AudioClockPoint): Boolean {
        val previous = last
        if (previous != null) {
            val frameDelta = point.framePosition - previous.framePosition
            val timeDelta = point.monotonicNanos - previous.monotonicNanos
            if (timeDelta <= 0L || frameDelta < 0L) return false
            if (frameDelta == 0L) {
                if (timeDelta >= MIN_DISCONTINUITY_WINDOW_NANOS) discontinuity = true
                return false
            }
            if (timeDelta >= MIN_DISCONTINUITY_WINDOW_NANOS) {
                val measuredPpm = ((frameDelta.toDouble() * 1_000_000_000.0 / timeDelta) / nominalSampleRate - 1.0) * 1_000_000.0
                if (measuredPpm.isFinite() && kotlin.math.abs(measuredPpm) > MAX_CONTINUOUS_DRIFT_PPM) discontinuity = true
            }
        }
        if (first == null) first = point
        last = point
        return true
    }

    fun clear() {
        first = null
        last = null
        discontinuity = false
    }

    fun monotonicNanosAt(framePosition: Long): Long? {
        val end = last ?: return null
        val start = first ?: return null
        val frameDelta = end.framePosition - start.framePosition
        val timeDelta = end.monotonicNanos - start.monotonicNanos
        val nanosPerFrame = if (frameDelta > 0L && timeDelta > 0L) {
            timeDelta.toDouble() / frameDelta
        } else 1_000_000_000.0 / nominalSampleRate
        val result = end.monotonicNanos + (framePosition - end.framePosition) * nanosPerFrame
        if (!result.isFinite() || result !in Long.MIN_VALUE.toDouble()..Long.MAX_VALUE.toDouble()) return null
        return result.toLong()
    }

    fun frameAt(monotonicNanos: Long): Long? {
        val end = last ?: return null
        val start = first ?: return null
        val frameDelta = end.framePosition - start.framePosition
        val timeDelta = end.monotonicNanos - start.monotonicNanos
        val framesPerNano = if (frameDelta > 0L && timeDelta > 0L) {
            frameDelta.toDouble() / timeDelta
        } else nominalSampleRate / 1_000_000_000.0
        val result = end.framePosition + (monotonicNanos - end.monotonicNanos) * framesPerNano
        if (!result.isFinite() || result !in Long.MIN_VALUE.toDouble()..Long.MAX_VALUE.toDouble()) return null
        return result.toLong()
    }

    /** Measured clock rate relative to the declared WAV rate; null until two timestamps exist. */
    fun rateCorrectionPpm(): Int? {
        val start = first ?: return null
        val end = last ?: return null
        val frames = end.framePosition - start.framePosition
        val nanos = end.monotonicNanos - start.monotonicNanos
        if (discontinuity || frames <= 0L || nanos < MIN_DRIFT_WINDOW_NANOS) return null
        val measuredRate = frames.toDouble() * 1_000_000_000.0 / nanos
        val ppm = ((measuredRate / nominalSampleRate) - 1.0) * 1_000_000.0
        return ppm.takeIf { it.isFinite() && it in -100_000.0..100_000.0 }?.toInt()
    }

    fun hasDiscontinuity(): Boolean = discontinuity

    private companion object {
        const val MIN_DRIFT_WINDOW_NANOS = 5_000_000_000L
        const val MIN_DISCONTINUITY_WINDOW_NANOS = 250_000_000L
        const val MAX_CONTINUOUS_DRIFT_PPM = 2_000.0
    }
}

data class OverdubAlignment(
    val positionFrames: Long,
    /** Positive means the captured source clock ran faster than the backing clock. */
    val rateCorrectionPpm: Int?,
    val routeKey: String
)

/**
 * Converts capture timestamps to backing-timeline frames. Clock-rate correction remains null
 * until both devices provide enough monotonic timestamp samples.
 */
class SharedAudioTimeline(
    private val projectSampleRate: Int,
    private val countInFrames: Long = 0L,
    captureSampleRate: Int = projectSampleRate,
    backingSampleRate: Int = projectSampleRate
) {
    private val captureClock = AudioClockMap(captureSampleRate)
    private val backingClock = AudioClockMap(backingSampleRate)
    private var captureStartFrame: Long? = null
    private var routeKey: String? = null
    private var paused = false
    private var invalidated = false
    private var alignmentLost = false
    private var firstAlignment: OverdubAlignment? = null

    init {
        require(projectSampleRate > 0)
        require(countInFrames >= 0L)
    }

    @Synchronized
    fun observeCapture(point: AudioClockPoint, route: String): Boolean = observe(point, route, captureClock, true)

    @Synchronized
    fun observeBacking(point: AudioClockPoint, route: String): Boolean = observe(point, route, backingClock, false)

    private fun observe(point: AudioClockPoint, route: String, clock: AudioClockMap, capture: Boolean): Boolean {
        if (paused || invalidated || route.isBlank()) return false
        val knownRoute = routeKey
        if (knownRoute != null && knownRoute != route) {
            invalidateRoute()
            return false
        }
        routeKey = route
        if (!clock.observe(point)) return false
        if (capture && captureStartFrame == null) captureStartFrame = point.framePosition
        if (firstAlignment == null) firstAlignment = calculateAlignment()
        return true
    }

    @Synchronized
    fun pause() {
        if (firstAlignment == null) {
            firstAlignment = calculateAlignment()
            alignmentLost = firstAlignment == null
        }
        paused = true
        captureClock.clear()
        backingClock.clear()
    }

    @Synchronized
    fun resume() { paused = false }

    @Synchronized
    fun invalidateRoute() {
        invalidated = true
        routeKey = null
        captureClock.clear()
        backingClock.clear()
        captureStartFrame = null
    }

    @Synchronized
    fun alignmentForFirstCapturedFrame(): OverdubAlignment? {
        if (invalidated) return null
        if (alignmentLost) return null
        firstAlignment?.let { saved ->
            if (clockDiscontinuous()) return saved.copy(rateCorrectionPpm = null)
            val correction = currentDriftCorrection()
            return if (correction == null) saved else saved.copy(rateCorrectionPpm = correction)
        }
        return calculateAlignment()?.also { firstAlignment = it }
    }

    private fun calculateAlignment(): OverdubAlignment? {
        val captureStart = captureStartFrame ?: return null
        val captureTime = captureClock.monotonicNanosAt(captureStart) ?: return null
        val backingFrameAtCapture = backingClock.frameAt(captureTime) ?: return null
        val position = backingFrameAtCapture - countInFrames
        val inputRate = captureClock.rateCorrectionPpm()
        val backingRate = backingClock.rateCorrectionPpm()
        val correction = if (inputRate != null && backingRate != null) inputRate - backingRate else null
        return OverdubAlignment(position, correction, routeKey ?: return null)
    }

    private fun currentDriftCorrection(): Int? {
        val captureRate = captureClock.rateCorrectionPpm() ?: return null
        val backingRate = backingClock.rateCorrectionPpm() ?: return null
        return captureRate - backingRate
    }

    @Synchronized
    fun routeInvalidated(): Boolean = invalidated

    @Synchronized
    fun clockDiscontinuous(): Boolean = captureClock.hasDiscontinuity() || backingClock.hasDiscontinuity()
}
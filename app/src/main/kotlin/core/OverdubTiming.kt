package com.kanatron.app.core

import java.math.BigDecimal
import java.math.RoundingMode

data class OverdubRoute(
    val routeKey: String,
    val displayName: String,
    val captureSampleRate: Int,
    val backingSampleRate: Int,
    val projectSampleRate: Int
) {
    init {
        require(routeKey.isNotBlank() && displayName.isNotBlank())
        require(captureSampleRate in SUPPORTED_SAMPLE_RATES)
        require(backingSampleRate in SUPPORTED_SAMPLE_RATES)
        require(projectSampleRate in SUPPORTED_SAMPLE_RATES)
    }
}

/** Per-route timing offset in project frames; positive moves captured takes later. */
data class OverdubCalibration(
    val routeKey: String,
    val captureSampleRate: Int,
    val backingSampleRate: Int,
    val projectSampleRate: Int,
    val offsetFrames: Long
) {
    init {
        require(routeKey.isNotBlank())
        require(captureSampleRate in SUPPORTED_SAMPLE_RATES)
        require(backingSampleRate in SUPPORTED_SAMPLE_RATES)
        require(projectSampleRate in SUPPORTED_SAMPLE_RATES)
    }

    fun matches(route: OverdubRoute): Boolean =
        routeKey == route.routeKey && captureSampleRate == route.captureSampleRate &&
            backingSampleRate == route.backingSampleRate && projectSampleRate == route.projectSampleRate
}

data class OverdubPlacement(val positionFrames: Long, val leadInFrames: Long) {
    /** Where take frame 0 lands on the backing; negative starts before it. */
    val offsetFrames: Long get() = positionFrames - leadInFrames
}

object OverdubTiming {
    /** Positive offset moves the captured clip later. */
    fun placement(timestampPositionFrames: Long, calibration: OverdubCalibration?): OverdubPlacement {
        val corrected = if (calibration == null) timestampPositionFrames else {
            Math.addExact(timestampPositionFrames, calibration.offsetFrames)
        }
        return if (corrected < 0L) OverdubPlacement(0L, Math.negateExact(corrected))
        else OverdubPlacement(corrected, 0L)
    }

    fun driftCorrectionPpm(clockDiscontinuous: Boolean, timestampPpm: Int?): Int? =
        if (clockDiscontinuous) null else timestampPpm

    fun framesFromMilliseconds(milliseconds: Double, sampleRate: Int): Long {
        require(milliseconds.isFinite() && sampleRate in SUPPORTED_SAMPLE_RATES)
        return BigDecimal.valueOf(milliseconds).multiply(BigDecimal.valueOf(sampleRate.toLong()))
            .divide(BigDecimal.valueOf(1_000L), 0, RoundingMode.HALF_UP).longValueExact()
    }

    /** Source frames to trim when the measured start falls before project frame zero. */
    fun sourceTrimForLeadIn(leadInFrames: Long, sourceRate: Int, projectRate: Int, rateCorrectionPpm: Int = 0): Long {
        require(leadInFrames >= 0L)
        require(sourceRate in SUPPORTED_SAMPLE_RATES && projectRate in SUPPORTED_SAMPLE_RATES)
        require(rateCorrectionPpm in -ProjectClip.MAX_RATE_CORRECTION_PPM..ProjectClip.MAX_RATE_CORRECTION_PPM)
        val numerator = BigDecimal.valueOf(leadInFrames)
            .multiply(BigDecimal.valueOf(sourceRate.toLong()))
            .multiply(BigDecimal.valueOf(1_000_000L + rateCorrectionPpm))
        val denominator = BigDecimal.valueOf(projectRate.toLong()).multiply(BigDecimal.valueOf(1_000_000L))
        return numerator.divide(denominator, 0, RoundingMode.HALF_UP).longValueExact()
    }
}
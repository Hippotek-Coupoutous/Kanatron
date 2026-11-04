package com.ottomusic.sketch.core

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
        require(captureSampleRate in 8_000..192_000)
        require(backingSampleRate in 8_000..192_000)
        require(projectSampleRate in 8_000..192_000)
    }
}

/** Owner-entered result from the wired loopback marker protocol. Lag is capture minus backing. */
data class OverdubCalibration(
    val routeKey: String,
    val captureSampleRate: Int,
    val backingSampleRate: Int,
    val projectSampleRate: Int,
    val measuredLagFrames: Long,
    val uncertaintyFrames: Long,
    val manualNudgeFrames: Long,
    val measuredDriftCorrectionPpm: Int?,
    val measuredAtEpochMillis: Long
) {
    init {
        require(routeKey.isNotBlank())
        require(captureSampleRate in 8_000..192_000)
        require(backingSampleRate in 8_000..192_000)
        require(projectSampleRate in 8_000..192_000)
        require(uncertaintyFrames >= 0L)
        require(measuredDriftCorrectionPpm == null || measuredDriftCorrectionPpm in -2_000..2_000)
    }

    fun matches(route: OverdubRoute): Boolean =
        routeKey == route.routeKey && captureSampleRate == route.captureSampleRate &&
            backingSampleRate == route.backingSampleRate && projectSampleRate == route.projectSampleRate
}

data class OverdubPlacement(val positionFrames: Long, val leadInFrames: Long)

object OverdubTiming {
    /** Positive measured lag moves the captured clip earlier; positive manual nudge moves it later. */
    fun placement(timestampPositionFrames: Long, calibration: OverdubCalibration?): OverdubPlacement {
        val corrected = if (calibration == null) timestampPositionFrames else {
            Math.addExact(Math.subtractExact(timestampPositionFrames, calibration.measuredLagFrames), calibration.manualNudgeFrames)
        }
        return if (corrected < 0L) OverdubPlacement(0L, Math.negateExact(corrected))
        else OverdubPlacement(corrected, 0L)
    }

    fun driftCorrectionPpm(clockDiscontinuous: Boolean, calibratedPpm: Int?, timestampPpm: Int?): Int? =
        if (clockDiscontinuous) null else calibratedPpm ?: timestampPpm

    fun exceedsStartUncertaintyCaution(uncertaintyFrames: Long, sampleRate: Int): Boolean {
        require(uncertaintyFrames >= 0L && sampleRate in 8_000..192_000)
        return uncertaintyFrames * 1_000.0 / sampleRate > 5.0
    }

    fun framesFromMilliseconds(milliseconds: Double, sampleRate: Int): Long {
        require(milliseconds.isFinite() && sampleRate in 8_000..192_000)
        return BigDecimal.valueOf(milliseconds).multiply(BigDecimal.valueOf(sampleRate.toLong()))
            .divide(BigDecimal.valueOf(1_000L), 0, RoundingMode.HALF_UP).longValueExact()
    }

    /** Source frames to trim when the measured start falls before project frame zero. */
    fun sourceTrimForLeadIn(leadInFrames: Long, sourceRate: Int, projectRate: Int, rateCorrectionPpm: Int = 0): Long {
        require(leadInFrames >= 0L)
        require(sourceRate in 8_000..192_000 && projectRate in 8_000..192_000)
        require(rateCorrectionPpm in -2_000..2_000)
        val numerator = BigDecimal.valueOf(leadInFrames)
            .multiply(BigDecimal.valueOf(sourceRate.toLong()))
            .multiply(BigDecimal.valueOf(1_000_000L + rateCorrectionPpm))
        val denominator = BigDecimal.valueOf(projectRate.toLong()).multiply(BigDecimal.valueOf(1_000_000L))
        return numerator.divide(denominator, 0, RoundingMode.HALF_UP).longValueExact()
    }
}
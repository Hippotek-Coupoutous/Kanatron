package com.ottomusic.sketch.data

import android.content.Context
import com.ottomusic.sketch.core.OverdubCalibration
import com.ottomusic.sketch.core.OverdubRoute
import java.security.MessageDigest

/** Device-local owner measurements, kept separate for each exact route and rate combination. */
internal class OverdubCalibrationStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("overdub-calibration", Context.MODE_PRIVATE)

    fun rememberRoute(route: OverdubRoute) {
        preferences.edit()
            .putString("last.route", route.routeKey)
            .putString("last.label", route.displayName)
            .putInt("last.captureRate", route.captureSampleRate)
            .putInt("last.backingRate", route.backingSampleRate)
            .putInt("last.projectRate", route.projectSampleRate)
            .apply()
    }

    fun lastRoute(): OverdubRoute? {
        val key = preferences.getString("last.route", null) ?: return null
        val label = preferences.getString("last.label", null) ?: return null
        return runCatching {
            OverdubRoute(
                key, label,
                preferences.getInt("last.captureRate", 0),
                preferences.getInt("last.backingRate", 0),
                preferences.getInt("last.projectRate", 0)
            )
        }.getOrNull()
    }

    fun calibration(route: OverdubRoute): OverdubCalibration? {
        val prefix = "cal.${routeId(route)}"
        if (!preferences.getBoolean("$prefix.saved", false)) return null
        return runCatching {
            OverdubCalibration(
                routeKey = preferences.getString("$prefix.route", null) ?: error("Missing route"),
                captureSampleRate = preferences.getInt("$prefix.captureRate", 0),
                backingSampleRate = preferences.getInt("$prefix.backingRate", 0),
                projectSampleRate = preferences.getInt("$prefix.projectRate", 0),
                measuredLagFrames = preferences.getLong("$prefix.lagFrames", 0L),
                uncertaintyFrames = preferences.getLong("$prefix.uncertaintyFrames", 0L),
                manualNudgeFrames = preferences.getLong("$prefix.nudgeFrames", 0L),
                measuredDriftCorrectionPpm = if (preferences.getBoolean("$prefix.hasDrift", false)) {
                    preferences.getInt("$prefix.driftPpm", 0)
                } else null,
                measuredAtEpochMillis = preferences.getLong("$prefix.measuredAt", 0L)
            ).takeIf { it.matches(route) }
        }.getOrNull()
    }

    fun save(route: OverdubRoute, calibration: OverdubCalibration): Boolean {
        require(calibration.matches(route))
        val prefix = "cal.${routeId(route)}"
        return preferences.edit()
            .putBoolean("$prefix.saved", true)
            .putString("$prefix.route", route.routeKey)
            .putInt("$prefix.captureRate", route.captureSampleRate)
            .putInt("$prefix.backingRate", route.backingSampleRate)
            .putInt("$prefix.projectRate", route.projectSampleRate)
            .putLong("$prefix.lagFrames", calibration.measuredLagFrames)
            .putLong("$prefix.uncertaintyFrames", calibration.uncertaintyFrames)
            .putLong("$prefix.nudgeFrames", calibration.manualNudgeFrames)
            .putBoolean("$prefix.hasDrift", calibration.measuredDriftCorrectionPpm != null)
            .putInt("$prefix.driftPpm", calibration.measuredDriftCorrectionPpm ?: 0)
            .putLong("$prefix.measuredAt", calibration.measuredAtEpochMillis)
            .commit()
    }

    fun clear(route: OverdubRoute): Boolean {
        val prefix = "cal.${routeId(route)}"
        return preferences.edit()
            .remove("$prefix.saved")
            .remove("$prefix.route")
            .remove("$prefix.captureRate")
            .remove("$prefix.backingRate")
            .remove("$prefix.projectRate")
            .remove("$prefix.lagFrames")
            .remove("$prefix.uncertaintyFrames")
            .remove("$prefix.nudgeFrames")
            .remove("$prefix.hasDrift")
            .remove("$prefix.driftPpm")
            .remove("$prefix.measuredAt")
            .commit()
    }

    private fun routeId(route: OverdubRoute): String = MessageDigest.getInstance("SHA-256")
        .digest("${route.routeKey}|${route.captureSampleRate}|${route.backingSampleRate}|${route.projectSampleRate}".toByteArray())
        .take(12).joinToString("") { "%02x".format(it) }
}
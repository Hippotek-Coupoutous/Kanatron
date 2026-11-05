package com.ottomusic.sketch.data

import android.content.Context
import com.ottomusic.sketch.core.OverdubCalibration
import com.ottomusic.sketch.core.OverdubRoute
import com.ottomusic.sketch.core.sha256Hex

/** Device-local timing offsets, kept separate for each exact route and rate combination. */
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
                offsetFrames = preferences.getLong("$prefix.offsetFrames", 0L)
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
            .putLong("$prefix.offsetFrames", calibration.offsetFrames)
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
            .remove("$prefix.offsetFrames")
            .commit()
    }

    private fun routeId(route: OverdubRoute): String =
        sha256Hex("${route.routeKey}|${route.captureSampleRate}|${route.backingSampleRate}|${route.projectSampleRate}").take(24)
}
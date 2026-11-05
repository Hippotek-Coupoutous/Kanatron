package com.kanatron.app.recording

import android.content.Context
import com.kanatron.app.core.AudioClockPoint
import com.kanatron.app.core.OverdubRoute
import com.kanatron.app.core.Project
import com.kanatron.app.core.SharedAudioTimeline
import com.kanatron.app.data.OverdubCalibrationStore

/**
 * Tracks the capture and backing routes and clocks of one take and feeds the shared timeline.
 * Route and clock fields are guarded by [clockLock]; route changes are published through [onRoute]
 * so the tracker never touches service state.
 */
internal class OverdubClockTracker(
    context: Context,
    private val baseProject: () -> Project?,
    private val onRoute: (OverdubRoute?) -> Unit
) {
    /** One side of the overdub route; fields are guarded by [clockLock]. */
    private class Side(defaultRouteName: String) {
        @Volatile var routeKey: String? = null
        var routeName: String = defaultRouteName
        var firstClock: Triple<AudioClockPoint, String, String>? = null
    }

    private val captureSide = Side("System microphone")
    private val backingSide = Side("System output")
    private val calibration by lazy { OverdubCalibrationStore(context) }
    private val clockLock = Any()
    private var captureSampleRate = 48_000
    private var commonRouteKey: String? = null
    private var timelineObserving = false
    @Volatile private var observedRoute: OverdubRoute? = null

    @Volatile
    var timeline: SharedAudioTimeline? = null
        private set

    val route: OverdubRoute?
        get() = observedRoute

    /** Main thread: the recorder is open and the base project is known, so clocks can be primed. */
    fun attach(timeline: SharedAudioTimeline, captureSampleRate: Int, captureRouteName: String) {
        this.timeline = timeline
        this.captureSampleRate = captureSampleRate
        captureSide.routeName = captureRouteName
        synchronized(clockLock) { primeTimelineClocks() }
    }

    /** Main thread: drop the previous take's routes and clocks before a new capture. */
    fun reset() {
        timeline = null
        captureSide.routeKey = null
        backingSide.routeKey = null
        captureSide.routeName = "System microphone"
        backingSide.routeName = "System output"
        observedRoute = null
        captureSampleRate = 48_000
        synchronized(clockLock) {
            captureSide.firstClock = null
            backingSide.firstClock = null
            commonRouteKey = null
            timelineObserving = false
        }
    }

    fun onCaptureRoute(route: String, name: String) = observeRoute(captureSide, route, name)

    fun onBackingRoute(route: String, name: String) = observeRoute(backingSide, route, name)

    fun onCaptureClock(point: AudioClockPoint, route: String, name: String) =
        observeClock(captureSide, point, route, name, capture = true)

    fun onBackingClock(point: AudioClockPoint, route: String, name: String) =
        observeClock(backingSide, point, route, name, capture = false)

    /** Everything the disk part of a finished take needs; read under the clock lock on the main thread. */
    fun snapshot(base: Project?, lostFocus: Boolean): TakeSnapshot = synchronized(clockLock) {
        val active = timeline
        TakeSnapshot(
            base = base,
            alignment = active?.alignmentForFirstCapturedFrame(),
            routeInvalidated = active?.routeInvalidated() == true,
            clockDiscontinuous = active?.clockDiscontinuous() == true,
            route = observedRoute,
            lostFocus = lostFocus
        )
    }

    /** Shared capture/backing route handler; a first unknown- route stays provisional. */
    private fun observeRoute(side: Side, route: String, name: String) {
        synchronized(clockLock) {
            val previous = side.routeKey
            if (previous != null && previous != route &&
                (!previous.startsWith("unknown-") || route.startsWith("unknown-") || side.firstClock != null || timelineObserving)) {
                timeline?.invalidateRoute()
                side.firstClock = null
            }
            side.routeKey = route
            side.routeName = name
            rememberCurrentRoute()
        }
    }

    private fun observeClock(side: Side, point: AudioClockPoint, route: String, name: String, capture: Boolean) {
        synchronized(clockLock) {
            observeRoute(side, route, name)
            if (side.firstClock == null) side.firstClock = Triple(point, route, name)
            if (!timelineObserving) {
                primeTimelineClocks()
                return
            }
            val active = timeline ?: return
            val routeKey = commonRouteKey ?: return
            if (capture) active.observeCapture(point, routeKey) else active.observeBacking(point, routeKey)
        }
    }

    private fun rememberCurrentRoute() {
        val base = baseProject() ?: return
        val capture = captureSide.routeKey ?: return
        val backing = backingSide.routeKey ?: return
        if (capture.startsWith("unknown-") || backing.startsWith("unknown-") ||
            capture == "System microphone" || backing == "System output") {
            if (observedRoute != null) {
                observedRoute = null
                onRoute(null)
            }
            return
        }
        val route = OverdubRoute(
            "$capture|$backing", "${captureSide.routeName} → ${backingSide.routeName}",
            captureSampleRate, base.sampleRate, base.sampleRate
        )
        if (route == observedRoute) return
        observedRoute = route
        calibration.rememberRoute(route)
        onRoute(route)
    }

    private fun primeTimelineClocks() {
        if (timelineObserving) return
        val (capturePoint, captureRoute) = captureSide.firstClock ?: return
        val (backingPoint, outputRoute) = backingSide.firstClock ?: return
        val active = timeline ?: return
        if (captureRoute != captureSide.routeKey || outputRoute != backingSide.routeKey) {
            active.invalidateRoute()
            return
        }
        commonRouteKey = "$captureRoute|$outputRoute"
        active.observeBacking(backingPoint, commonRouteKey!!)
        active.observeCapture(capturePoint, commonRouteKey!!)
        timelineObserving = true
    }
}

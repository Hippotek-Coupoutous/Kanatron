package com.ottomusic.sketch.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.ottomusic.sketch.MainActivity
import com.ottomusic.sketch.R
import com.ottomusic.sketch.core.AudioClockPoint
import com.ottomusic.sketch.core.OverdubRoute
import com.ottomusic.sketch.core.OverdubTiming
import com.ottomusic.sketch.core.ProjectArrangementActions
import com.ottomusic.sketch.core.RecordingLevel
import com.ottomusic.sketch.core.SharedAudioTimeline
import com.ottomusic.sketch.core.TrimRange
import com.ottomusic.sketch.data.OverdubCalibrationStore
import com.ottomusic.sketch.data.ProjectRepository
import com.ottomusic.sketch.platform.MICROPHONE_PERMISSION_ERROR
import com.ottomusic.sketch.platform.PcmInput
import com.ottomusic.sketch.platform.PlatformPorts
import com.ottomusic.sketch.platform.RecordingFocus
import com.ottomusic.sketch.playback.ClickOnlyPlayer
import com.ottomusic.sketch.playback.MonitorClick
import com.ottomusic.sketch.playback.WavPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class RecordingServiceState(
    val phase: String = "idle",
    val frames: Long = 0L,
    val sampleRate: Int = 48_000,
    val level: RecordingLevel = RecordingLevel(0f, 0f),
    val error: String? = null,
    val projectId: String? = null,
    val inputDevice: String = "System microphone",
    val silenceWarning: Boolean = false,
    val inputSilenced: Boolean = false,
    val silent: Boolean = false,
    val interruptionNotice: String? = null,
    val alignmentNotice: String? = null,
    val overdubRoute: OverdubRoute? = null
)

class RecordingService : Service() {
    companion object {
        const val ACTION_START = "com.ottomusic.sketch.action.START_RECORDING"
        const val ACTION_PAUSE = "com.ottomusic.sketch.action.PAUSE_RECORDING"
        const val ACTION_RESUME = "com.ottomusic.sketch.action.RESUME_RECORDING"
        const val ACTION_STOP = "com.ottomusic.sketch.action.STOP_RECORDING"
        const val EXTRA_OVERDUB_PROJECT_ID = "com.ottomusic.sketch.extra.OVERDUB_PROJECT_ID"
        const val EXTRA_OVERDUB_CLICK_BPM = "com.ottomusic.sketch.extra.OVERDUB_CLICK_BPM"
        const val EXTRA_OVERDUB_CLICK_BEATS = "com.ottomusic.sketch.extra.OVERDUB_CLICK_BEATS"
        const val EXTRA_OVERDUB_COUNT_IN_BARS = "com.ottomusic.sketch.extra.OVERDUB_COUNT_IN_BARS"
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 42
        private val mutableState = MutableStateFlow(RecordingServiceState())
        val state = mutableState.asStateFlow()
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var audioManager: AudioManager
    private lateinit var recordingFocus: RecordingFocus
    private var recorder: WavRecorder? = null
    private var latestStartId = 0
    private var recordingCallback: android.media.AudioManager.AudioRecordingCallback? = null
    private var backingPlayer: WavPlayer? = null
    private var clickOnlyPlayer: ClickOnlyPlayer? = null
    private var monitorClick: MonitorClick? = null
    private var overdubBaseProject: com.ottomusic.sketch.core.Project? = null
    private var overdubBackingRate: Int? = null
    private var captureSampleRate = 48_000
    private var captureRouteName = "System microphone"
    private var backingRouteName = "System output"
    @Volatile private var observedOverdubRoute: OverdubRoute? = null
    @Volatile private var overdubTimeline: SharedAudioTimeline? = null
    @Volatile private var captureRouteKey: String? = null
    @Volatile private var backingRouteKey: String? = null
    private val clockLock = Any()
    private var firstCaptureClock: Triple<AudioClockPoint, String, String>? = null
    private var firstBackingClock: Triple<AudioClockPoint, String, String>? = null
    private var commonRouteKey: String? = null
    private var timelineObserving = false
    @Volatile private var transportStopped = false
    private var finalizing = false
    @Volatile private var lostFocusPermanently = false
    @Volatile private var outputFailureNotice: String? = null

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AudioManager::class.java)
        recordingFocus = PlatformPorts.recordingFocus(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        val action = intent?.action
        when (action) {
            ACTION_START -> startCapture(intent)
            ACTION_PAUSE -> pauseTransport()
            ACTION_RESUME -> resumeTransport()
            ACTION_STOP -> stopTransport()
        }
        if (recorder == null && action != ACTION_START) stopSelfResult(startId)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        unregisterRecordingCallback()
        releaseBackingPlayer()
        releaseClickOnlyPlayer()
        recorder?.requestStop()
        abandonFocus()
        super.onDestroy()
    }

    private fun startCapture(intent: Intent?) {
        if (recorder != null) return
        finalizing = false
        transportStopped = false
        lostFocusPermanently = false
        outputFailureNotice = null
        mutableState.value = RecordingServiceState("starting")
        try {
            loadOverdubBase(intent)
            promoteToForeground("Preparing microphone…")
            if (!requestFocus()) {
                finishWithoutTake("Microphone could not get audio focus")
                return
            }
            lateinit var createdRecorder: WavRecorder
            createdRecorder = WavRecorder(
                context = this,
                onState = { value -> main.post {
                    // onFinished owns terminal service state, after the take and project are persisted.
                    if (value.phase == "complete" || value.phase == "failed") return@post
                    // A recorder update posted after the take was finalized must not wipe its projectId.
                    if (mutableState.value.phase == "complete" || mutableState.value.phase == "failed") return@post
                    val phaseChanged = mutableState.value.phase != value.phase
                    mutableState.value = RecordingServiceState(
                        value.phase, value.frames, value.sampleRate, value.level, value.error,
                        inputDevice = value.inputDevice,
                        silenceWarning = value.silenceWarning,
                        inputSilenced = value.inputSilenced,
                        alignmentNotice = mutableState.value.alignmentNotice,
                        overdubRoute = observedOverdubRoute
                    )
                    if (phaseChanged) updateNotification()
                } },
                onFinished = { take -> main.post { completeTake(createdRecorder, take) } },
                onClock = ::observeCaptureClock,
                onRoute = ::observeCaptureRoute
            )
            recorder = createdRecorder
            createdRecorder.start { input ->
                registerRecordingCallback(input)
                overdubBaseProject?.let { project ->
                    overdubTimeline = SharedAudioTimeline(
                        projectSampleRate = project.sampleRate,
                        projectStartFrame = project.trim.startFrame,
                        captureSampleRate = input.sampleRate,
                        backingSampleRate = overdubBackingRate ?: project.sampleRate,
                        countInFrames = monitorClick?.countInFrames(project.sampleRate) ?: 0L
                    )
                    captureSampleRate = input.sampleRate
                    captureRouteName = input.routeName
                    synchronized(clockLock) { primeTimelineClocks() }
                }
            }
            startBackingPlayback()
            if (lostFocusPermanently) createdRecorder.requestStop()
            mutableState.value = RecordingServiceState("recording", sampleRate = recorder?.state?.value?.sampleRate ?: 48_000, inputDevice = recorder?.state?.value?.inputDevice ?: "System microphone")
            updateNotification()
        } catch (failure: Throwable) {
            unregisterRecordingCallback()
            recorder = null
            finishWithoutTake(if (failure is SecurityException) MICROPHONE_PERMISSION_ERROR else failure.message ?: "Could not start microphone")
        }
    }

    private fun requestFocus(): Boolean {
        // Audio focus is requested before AudioRecord starts so focus loss cannot silently corrupt a take.
        return recordingFocus.request {
            lostFocusPermanently = true
            recorder?.requestStop()
        }
    }

    private fun loadOverdubBase(intent: Intent?) {
        releaseBackingPlayer()
        releaseClickOnlyPlayer()
        overdubTimeline = null
        captureRouteKey = null
        backingRouteKey = null
        captureRouteName = "System microphone"
        backingRouteName = "System output"
        observedOverdubRoute = null
        captureSampleRate = 48_000
        synchronized(clockLock) {
            firstCaptureClock = null
            firstBackingClock = null
            commonRouteKey = null
            timelineObserving = false
        }
        val id = intent?.getStringExtra(EXTRA_OVERDUB_PROJECT_ID)?.takeIf(String::isNotBlank)
        val clickBpm = intent?.getIntExtra(EXTRA_OVERDUB_CLICK_BPM, 0) ?: 0
        monitorClick = if (clickBpm in 30..300) runCatching {
            MonitorClick(
                clickBpm,
                intent?.getIntExtra(EXTRA_OVERDUB_CLICK_BEATS, 4) ?: 4,
                intent?.getIntExtra(EXTRA_OVERDUB_COUNT_IN_BARS, 0) ?: 0
            )
        }.getOrNull() else null
        if (id == null) {
            overdubBaseProject = null
            overdubBackingRate = null
            return
        }
        overdubBaseProject = ProjectRepository(this).get(id)
            ?: error("The backing recording could not be found.")
        // Arrangement previews are rendered at their project rate; single-source files use that same rate.
        overdubBackingRate = overdubBaseProject?.sampleRate
    }

    private fun startBackingPlayback() {
        val project = overdubBaseProject
        if (project == null) {
            val click = monitorClick ?: return
            try {
                clickOnlyPlayer = ClickOnlyPlayer(
                    this,
                    recorder?.state?.value?.sampleRate ?: 48_000,
                    click,
                    ::observeBackingClock,
                    ::observeBackingRoute
                ).also(ClickOnlyPlayer::start)
                clickOnlyPlayer?.let { player -> main.postDelayed({ reportClickOnlyStatus(player) }, 1_000L) }
            } catch (_: Throwable) {
                main.post { if (!transportStopped) mutableState.value = mutableState.value.copy(alignmentNotice = "Click output is unavailable. Recording continues.") }
            }
            return
        }
        Thread({
            try {
                val file = ProjectRepository(this).playbackFile(project)
                if (transportStopped || recorder == null) return@Thread
                val player = WavPlayer(
                    this, file, takesFocus = false, onClock = ::observeBackingClock,
                    monitorClick = monitorClick, onRoute = ::observeBackingRoute
                )
                if (transportStopped || recorder == null) {
                    player.release()
                    return@Thread
                }
                backingPlayer = player
                player.seek(project.trim.startFrame)
                player.play(project.trim.endFrame)
                main.postDelayed({ reportBackingStatus(player) }, 1_000L)
            } catch (_: Throwable) {
                main.post {
                    if (!transportStopped) mutableState.value = mutableState.value.copy(
                        alignmentNotice = "Backing playback is unavailable. Recording continues without it."
                    )
                }
            }
        }, "overdub-backing-loader").apply { start() }
    }

    private fun reportBackingStatus(player: WavPlayer) {
        if (transportStopped || backingPlayer !== player) return
        val playerState = player.state.value
        val notice = when {
            playerState.error != null -> "Backing playback failed; recording continued without it."
            overdubTimeline?.routeInvalidated() == true -> "Audio route changed. Timing is uncalibrated; captured audio is still being saved."
            overdubTimeline?.clockDiscontinuous() == true -> "Audio clock discontinuity detected. Drift correction is disabled for this take."
            else -> null
        }
        if (playerState.error != null) rememberOutputFailure(notice!!)
        else if (notice != null) mutableState.value = mutableState.value.copy(alignmentNotice = notice)
        if (playerState.error == null) main.postDelayed({ reportBackingStatus(player) }, 1_000L)
    }

    private fun reportClickOnlyStatus(player: ClickOnlyPlayer) {
        if (transportStopped || clickOnlyPlayer !== player) return
        val failure = player.error
        if (failure != null) rememberOutputFailure("Click output stopped; recording continues.")
        else main.postDelayed({ reportClickOnlyStatus(player) }, 1_000L)
    }

    private fun rememberOutputFailure(message: String) {
        outputFailureNotice = message
        mutableState.value = mutableState.value.copy(
            alignmentNotice = mergeNotices(mutableState.value.alignmentNotice, message)
        )
    }

    private fun mergeNotices(vararg notices: String?): String? = notices.asSequence()
        .filterNotNull()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .joinToString(" ")
        .takeIf(String::isNotEmpty)

    private fun observeCaptureRoute(route: String, name: String) {
        synchronized(clockLock) {
            val previous = captureRouteKey
            if (previous != null && previous != route &&
                (!previous.startsWith("unknown-") || route.startsWith("unknown-") || firstCaptureClock != null || timelineObserving)) {
                overdubTimeline?.invalidateRoute()
                firstCaptureClock = null
            }
            captureRouteKey = route
            captureRouteName = name
            rememberCurrentRoute()
        }
    }

    private fun observeBackingRoute(route: String, name: String) {
        synchronized(clockLock) {
            val previous = backingRouteKey
            if (previous != null && previous != route &&
                (!previous.startsWith("unknown-") || route.startsWith("unknown-") || firstBackingClock != null || timelineObserving)) {
                overdubTimeline?.invalidateRoute()
                firstBackingClock = null
            }
            backingRouteKey = route
            backingRouteName = name
            rememberCurrentRoute()
        }
    }

    private fun observeCaptureClock(point: AudioClockPoint, route: String, name: String) {
        synchronized(clockLock) {
            observeCaptureRoute(route, name)
            if (firstCaptureClock == null) firstCaptureClock = Triple(point, route, name)
            if (timelineObserving) overdubTimeline?.observeCapture(point, commonRouteKey ?: return)
            else primeTimelineClocks()
        }
    }

    private fun observeBackingClock(point: AudioClockPoint, route: String, name: String) {
        synchronized(clockLock) {
            observeBackingRoute(route, name)
            if (firstBackingClock == null) firstBackingClock = Triple(point, route, name)
            if (timelineObserving) overdubTimeline?.observeBacking(point, commonRouteKey ?: return)
            else primeTimelineClocks()
        }
    }

    private fun rememberCurrentRoute() {
        val base = overdubBaseProject ?: return
        val capture = captureRouteKey ?: return
        val backing = backingRouteKey ?: return
        if (capture.startsWith("unknown-") || backing.startsWith("unknown-") ||
            capture == "System microphone" || backing == "System output") {
            if (observedOverdubRoute != null) {
                observedOverdubRoute = null
                main.post { mutableState.value = mutableState.value.copy(overdubRoute = null) }
            }
            return
        }
        val route = OverdubRoute(
            "$capture|$backing", "$captureRouteName → $backingRouteName",
            captureSampleRate, overdubBackingRate ?: base.sampleRate, base.sampleRate
        )
        if (route == observedOverdubRoute) return
        observedOverdubRoute = route
        OverdubCalibrationStore(this).rememberRoute(route)
        main.post { mutableState.value = mutableState.value.copy(overdubRoute = route) }
    }

    private fun primeTimelineClocks() {
        if (timelineObserving) return
        val (capturePoint, captureRoute) = firstCaptureClock ?: return
        val (backingPoint, outputRoute) = firstBackingClock ?: return
        val timeline = overdubTimeline ?: return
        if (captureRoute != captureRouteKey || outputRoute != backingRouteKey) {
            timeline.invalidateRoute()
            return
        }
        commonRouteKey = "$captureRoute|$outputRoute"
        timeline.observeBacking(backingPoint, commonRouteKey!!)
        timeline.observeCapture(capturePoint, commonRouteKey!!)
        timelineObserving = true
    }

    private fun pauseTransport() {
        overdubTimeline?.pause()
        recorder?.pause()
        backingPlayer?.pause()
        clickOnlyPlayer?.pause()
    }

    private fun resumeTransport() {
        overdubTimeline?.resume()
        recorder?.resume()
        overdubBaseProject?.let { backingPlayer?.play(it.trim.endFrame) }
        clickOnlyPlayer?.resume()
    }

    private fun stopTransport() {
        transportStopped = true
        snapshotOutputFailure()
        releaseBackingPlayer()
        releaseClickOnlyPlayer()
        recorder?.requestStop()
    }

    private fun snapshotOutputFailure() {
        clickOnlyPlayer?.error?.let { rememberOutputFailure("Click output stopped; recording continued.") }
        backingPlayer?.state?.value?.error?.let { rememberOutputFailure("Backing playback failed; recording continued without it.") }
    }

    private fun releaseBackingPlayer() {
        backingPlayer?.release()
        backingPlayer = null
    }

    private fun releaseClickOnlyPlayer() {
        clickOnlyPlayer?.release()
        clickOnlyPlayer = null
    }

    private fun registerRecordingCallback(input: PcmInput) {
        if (Build.VERSION.SDK_INT < 29) return
        val audioRecord = input.platformAudioRecord ?: return
        val sessionId = audioRecord.audioSessionId
        val callback = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                val silenced = configs.firstOrNull { it.clientAudioSessionId == sessionId }?.isClientSilenced == true
                recorder?.setInputSilenced(silenced)
            }
        }
        recordingCallback = callback
        audioManager.registerAudioRecordingCallback(callback, main)
    }

    private fun unregisterRecordingCallback() {
        recordingCallback?.let { runCatching { audioManager.unregisterAudioRecordingCallback(it) } }
        recordingCallback = null
    }

    private fun completeTake(sourceRecorder: WavRecorder, take: RecordingTake) {
        if (recorder !== sourceRecorder || finalizing) return
        finalizing = true
        unregisterRecordingCallback()
        transportStopped = true
        snapshotOutputFailure()
        releaseBackingPlayer()
        releaseClickOnlyPlayer()
        if (take.frames <= 0L) {
            if (hasRecoverablePcm(take)) {
                finishTake(sourceRecorder, take, null, take.error ?: "WAV header finalization failed; a recoverable partial was retained.")
            } else {
                take.file.delete()
                finishTake(sourceRecorder, take, null, take.error ?: "No audio samples were captured. Nothing was saved.")
            }
            return
        }
        persistTake(sourceRecorder, take)
    }

    private fun persistTake(sourceRecorder: WavRecorder, take: RecordingTake) {
        var project: com.ottomusic.sketch.core.Project? = null
        var alignmentNotice: String? = null
        var error = take.error
        try {
            val repository = ProjectRepository(this)
            val savedTake = repository.saveTake(
                take.id, take.file, take.sampleRate, take.channels, take.frames,
                recovered = take.error != null, silent = take.allSamplesZero
            )
            project = savedTake
            val base = overdubBaseProject
            if (savedTake != null && base != null) {
                val alignment = overdubTimeline?.alignmentForFirstCapturedFrame()
                val route = observedOverdubRoute?.takeIf {
                    alignment != null && !overdubTimeline!!.routeInvalidated() && it.routeKey == alignment.routeKey
                }
                val calibration = route?.let { OverdubCalibrationStore(this).calibration(it) }
                val clockDiscontinuous = overdubTimeline?.clockDiscontinuous() == true
                val calibratedPlacement = OverdubTiming.placement(
                    alignment?.positionFrames ?: base.trim.startFrame,
                    calibration.takeIf { alignment != null }
                )
                val correction = OverdubTiming.driftCorrectionPpm(
                    clockDiscontinuous,
                    alignment?.rateCorrectionPpm
                )
                val safeCorrection = correction?.takeIf { it in -2_000..2_000 } ?: 0
                val leadInTrim = if (alignment == null || calibratedPlacement.leadInFrames == 0L) 0L else {
                    OverdubTiming.sourceTrimForLeadIn(
                        calibratedPlacement.leadInFrames, savedTake.sampleRate, base.sampleRate, safeCorrection
                    )
                }
                if (leadInTrim >= savedTake.totalFrames) {
                    project = savedTake
                    alignmentNotice = "The take would start before frame zero. Its original WAV was kept in the library without shifting its audio."
                } else {
                    val arrangement = repository.appendRecordedTake(base.id, savedTake.id, calibratedPlacement.positionFrames)
                    var edited = arrangement
                    val track = arrangement.tracks.firstOrNull { candidate ->
                        candidate.clips.any { it.source.wav == savedTake.sourceWav }
                    }
                    val clip = track?.clips?.firstOrNull { it.source.wav == savedTake.sourceWav }
                    if (track != null && clip != null) {
                        if (correction != null && correction in -2_000..2_000 && correction != 0) {
                            edited = ProjectArrangementActions.setRateCorrection(edited, track.id, clip.id, correction)
                        }
                        if (leadInTrim > 0L) {
                            val safeStart = leadInTrim.coerceAtMost(savedTake.totalFrames - 1L)
                            edited = ProjectArrangementActions.setClipTrim(
                                edited, track.id, clip.id, TrimRange(safeStart, savedTake.totalFrames)
                            )
                        }
                    }
                    project = if (edited == arrangement) arrangement else repository.saveEditState(edited)
                    alignmentNotice = when {
                        overdubTimeline?.routeInvalidated() == true ->
                            "The audio route changed. The take was saved at the backing start."
                        alignment == null ->
                            "Timestamps were unavailable. The take was saved at the backing start."
                        else -> null
                    }
                }
            }
            if (project == null) error = error ?: "Could not save recording"
        } catch (failure: Throwable) {
            error = error ?: failure.message ?: "Could not save recording"
            alignmentNotice = if (project != null) {
                "The take was saved in your library, but could not be added to the backing project."
            } else if (failure.message.orEmpty().contains("header does not match", ignoreCase = true)) {
                "The WAV header did not match the captured audio. Its retained partial was kept for recovery at startup."
            } else {
                "The take was not saved in your library. Its retained partial will be checked for recovery at startup."
            }
        }
        val interruptionNotice = if (lostFocusPermanently && project != null) {
            "Audio focus was lost. Your recording was saved."
        } else null
        finishTake(sourceRecorder, take, project, error, interruptionNotice, alignmentNotice)
    }

    private fun finishTake(
        completedRecorder: WavRecorder,
        take: RecordingTake,
        project: com.ottomusic.sketch.core.Project?,
        error: String?,
        interruptionNotice: String? = null,
        alignmentNotice: String? = null
    ) {
        if (recorder === completedRecorder) recorder = null
        val terminalNotice = mergeNotices(alignmentNotice, outputFailureNotice, mutableState.value.alignmentNotice)
        mutableState.value = RecordingServiceState(
            phase = if (project != null && error == null) "complete" else "failed",
            frames = take.frames,
            sampleRate = take.sampleRate,
            error = error,
            projectId = project?.id,
            silent = take.allSamplesZero,
            interruptionNotice = interruptionNotice,
            alignmentNotice = terminalNotice,
            overdubRoute = observedOverdubRoute
        )
        updateNotification()
        abandonFocus()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelfResult(latestStartId)
    }

    private fun finishWithoutTake(message: String) {
        recorder = null
        mutableState.value = RecordingServiceState("failed", error = message)
        stopForeground(STOP_FOREGROUND_REMOVE)
        abandonFocus()
        stopSelfResult(latestStartId)
    }

    private fun hasRecoverablePcm(take: RecordingTake): Boolean =
        runCatching { take.file.isFile && take.file.length() >= 44L + take.channels * 2L }.getOrDefault(false)

    private fun promoteToForeground(text: String) {
        val notification = notification(text)
        if (Build.VERSION.SDK_INT >= 30) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(NOTIFICATION_ID, notification)
    }

    private fun updateNotification() {
        if (mutableState.value.phase == "idle" || mutableState.value.phase == "complete" || mutableState.value.phase == "failed") return
        val message = when (mutableState.value.phase) {
            "paused" -> "Recording paused"
            else -> "Recording in progress"
        }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(message))
    }

    private fun notification(text: String): Notification {
        val paused = mutableState.value.phase == "paused"
        val launch = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val pauseAction = if (paused) ACTION_RESUME else ACTION_PAUSE
        val pauseLabel = if (paused) "Resume" else "Pause"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic_notification)
            .setContentTitle("Audio Sketch")
            .setContentText(text)
            .setContentIntent(launch)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, pauseLabel, serviceActionPendingIntent(pauseAction, 2))
            .addAction(0, "Stop", serviceActionPendingIntent(ACTION_STOP, 3))
            .build()
    }

    private fun serviceActionPendingIntent(action: String, requestCode: Int): PendingIntent = PendingIntent.getService(
        this, requestCode, Intent(this, RecordingService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Recording", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shows active recording controls"
        })
    }

    private fun abandonFocus() = recordingFocus.abandon()
}
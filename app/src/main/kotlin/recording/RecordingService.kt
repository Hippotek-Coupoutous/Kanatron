package com.kanatron.app.recording

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
import com.kanatron.app.MainActivity
import com.kanatron.app.R
import com.kanatron.app.core.OverdubMix
import com.kanatron.app.core.OverdubRoute
import com.kanatron.app.core.Project
import com.kanatron.app.core.RecordingLevel
import com.kanatron.app.core.SharedAudioTimeline
import com.kanatron.app.core.SUPPORTED_BPM
import com.kanatron.app.core.WavFormat
import com.kanatron.app.data.ProjectRepository
import com.kanatron.app.data.RecordingActivePath
import com.kanatron.app.platform.MICROPHONE_PERMISSION_ERROR
import com.kanatron.app.platform.PcmInput
import com.kanatron.app.platform.PlatformPorts
import com.kanatron.app.platform.RecordingFocus
import com.kanatron.app.playback.ClickOnlyPlayer
import com.kanatron.app.playback.MonitorClick
import com.kanatron.app.playback.WavPlayer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate

enum class RecordingPhase { IDLE, STARTING, RECORDING, PAUSED, COMPLETE, FAILED }

/** A saved take the screen has not shown yet; an overdub also waits for review over its backing. */
data class TakeCompletion(val projectId: String, val overdub: OverdubMix?)

data class RecordingServiceState(
    val phase: RecordingPhase = RecordingPhase.IDLE,
    val frames: Long = 0L,
    val sampleRate: Int = 48_000,
    val level: RecordingLevel = RecordingLevel(0f),
    val error: String? = null,
    val projectId: String? = null,
    val inputDevice: String = "System microphone",
    val silenceWarning: Boolean = false,
    val inputSilenced: Boolean = false,
    val silent: Boolean = false,
    val interruptionNotice: String? = null,
    val alignmentNotice: String? = null,
    val overdubRoute: OverdubRoute? = null,
    val previewPlaying: Boolean = false,
    val previewFrame: Long = 0L,
    val completion: TakeCompletion? = null
)

class RecordingService : Service() {
    companion object {
        const val ACTION_START = "com.kanatron.app.action.START_RECORDING"
        const val ACTION_PAUSE = "com.kanatron.app.action.PAUSE_RECORDING"
        const val ACTION_RESUME = "com.kanatron.app.action.RESUME_RECORDING"
        const val ACTION_STOP = "com.kanatron.app.action.STOP_RECORDING"
        /** Toggles listening to the paused take so far. Ignored outside [RecordingPhase.PAUSED]. */
        const val ACTION_PREVIEW = "com.kanatron.app.action.PREVIEW_TAKE"
        const val EXTRA_OVERDUB_PROJECT_ID = "com.kanatron.app.extra.OVERDUB_PROJECT_ID"
        const val EXTRA_OVERDUB_CLICK_BPM = "com.kanatron.app.extra.OVERDUB_CLICK_BPM"
        const val EXTRA_OVERDUB_CLICK_BEATS = "com.kanatron.app.extra.OVERDUB_CLICK_BEATS"
        const val EXTRA_OVERDUB_COUNT_IN_BARS = "com.kanatron.app.extra.OVERDUB_COUNT_IN_BARS"
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 42
        private const val PREVIEW_PUBLISH_MILLIS = 100L
        private val mutableState = MutableStateFlow(RecordingServiceState())
        val state = mutableState.asStateFlow()

        /** Hands a finished take over once, so a recreated screen does not reselect it or reopen its review. */
        fun takeCompletion(): TakeCompletion? = mutableState.getAndUpdate { it.copy(completion = null) }.completion
    }

    private val main = Handler(Looper.getMainLooper())
    private val background: ExecutorService = Executors.newSingleThreadExecutor()
    private var startToken = 0
    private var loadingBase = false
    private var destroyed = false
    private lateinit var audioManager: AudioManager
    private lateinit var recordingFocus: RecordingFocus
    private lateinit var clockTracker: OverdubClockTracker
    private var recorder: WavRecorder? = null
    private var latestStartId = 0
    private var recordingCallback: AudioManager.AudioRecordingCallback? = null
    @Volatile private var backingPlayer: WavPlayer? = null
    private var previewPlayer: WavPlayer? = null
    private var clickOnlyPlayer: ClickOnlyPlayer? = null
    private var monitorClick: MonitorClick? = null
    private var overdubBaseProject: Project? = null
    @Volatile private var transportStopped = false
    private var finalizing = false
    @Volatile private var lostFocusPermanently = false
    @Volatile private var outputFailureNotice: String? = null

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AudioManager::class.java)
        recordingFocus = PlatformPorts.recordingFocus(this)
        clockTracker = OverdubClockTracker(this, { overdubBaseProject }, ::publishOverdubRoute)
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
            ACTION_PREVIEW -> if (previewPlayer != null) stopPreview() else startPreview()
        }
        if (recorder == null && !loadingBase && action != ACTION_START) stopSelfResult(startId)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        destroyed = true
        transportStopped = true
        startToken++
        stopPreview()
        unregisterRecordingCallback()
        releaseBackingPlayer()
        releaseClickOnlyPlayer()
        recorder?.requestStop()
        abandonFocus()
        // shutdown lets a take already being saved finish; dropping it would lose the recording.
        background.shutdown()
        super.onDestroy()
    }

    private fun startCapture(intent: Intent?) {
        if (recorder != null) return
        finalizing = false
        transportStopped = false
        lostFocusPermanently = false
        outputFailureNotice = null
        mutableState.value = RecordingServiceState(RecordingPhase.STARTING)
        // startForegroundService allows only seconds before promotion, so it must not wait for the base project load.
        try {
            promoteToForeground("Preparing microphone…")
        } catch (failure: Throwable) {
            finishWithoutTake(if (failure is SecurityException) MICROPHONE_PERMISSION_ERROR else failure.message ?: "Could not start microphone")
            return
        }
        val token = ++startToken
        val id = prepareOverdubBase(intent)
        if (id == null) {
            beginCapture()
            return
        }
        // The base project comes from disk, so the capture waits for it: the backing player must start after it is known.
        loadingBase = true
        background.execute {
            val loaded = runCatching { ProjectRepository(this).get(id) }
            main.post {
                if (token != startToken) return@post
                loadingBase = false
                val project = loaded.getOrNull()
                when {
                    loaded.isFailure -> finishWithoutTake(loaded.exceptionOrNull()?.message ?: "Could not start microphone")
                    project == null -> finishWithoutTake("The backing recording could not be found.")
                    else -> {
                        overdubBaseProject = project
                        beginCapture()
                    }
                }
            }
        }
    }

    private fun beginCapture() {
        try {
            if (!requestFocus()) {
                finishWithoutTake("Microphone could not get audio focus")
                return
            }
            lateinit var createdRecorder: WavRecorder
            createdRecorder = WavRecorder(
                context = this,
                onState = { value -> main.post {
                    // onFinished owns terminal service state, after the take and project are persisted.
                    if (value.phase == RecordingPhase.COMPLETE || value.phase == RecordingPhase.FAILED) return@post
                    // A recorder update posted after the take was finalized must not wipe its projectId.
                    if (mutableState.value.phase == RecordingPhase.COMPLETE || mutableState.value.phase == RecordingPhase.FAILED) return@post
                    val phaseChanged = mutableState.value.phase != value.phase
                    mutableState.value = RecordingServiceState(
                        value.phase, value.frames, value.sampleRate, value.level, value.error,
                        inputDevice = value.inputDevice,
                        silenceWarning = value.silenceWarning,
                        inputSilenced = value.inputSilenced,
                        alignmentNotice = mutableState.value.alignmentNotice,
                        overdubRoute = clockTracker.route,
                        previewPlaying = mutableState.value.previewPlaying,
                        previewFrame = mutableState.value.previewFrame
                    )
                    if (phaseChanged) updateNotification()
                } },
                onFinished = { take -> main.post { completeTake(createdRecorder, take) } },
                onClock = clockTracker::onCaptureClock,
                onRoute = clockTracker::onCaptureRoute
            )
            recorder = createdRecorder
            createdRecorder.start { input ->
                registerRecordingCallback(input)
                overdubBaseProject?.let { project ->
                    // The playback file starts at project frame zero; clip trims are already applied.
                    clockTracker.attach(
                        SharedAudioTimeline(
                            projectSampleRate = project.sampleRate,
                            captureSampleRate = input.sampleRate,
                            backingSampleRate = project.sampleRate,
                            countInFrames = monitorClick?.countInFrames(project.sampleRate) ?: 0L
                        ),
                        input.sampleRate,
                        input.routeName
                    )
                }
            }
            startBackingPlayback()
            if (lostFocusPermanently) createdRecorder.requestStop()
            mutableState.value = RecordingServiceState(RecordingPhase.RECORDING, sampleRate = recorder?.state?.value?.sampleRate ?: 48_000, inputDevice = recorder?.state?.value?.inputDevice ?: "System microphone")
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
            // Focus callbacks arrive on the main looper, so the preview is released before finalisation starts.
            stopPreview()
            recorder?.requestStop()
        }
    }

    private fun prepareOverdubBase(intent: Intent?): String? {
        releaseBackingPlayer()
        releaseClickOnlyPlayer()
        clockTracker.reset()
        val id = intent?.getStringExtra(EXTRA_OVERDUB_PROJECT_ID)?.takeIf(String::isNotBlank)
        val clickBpm = intent?.getIntExtra(EXTRA_OVERDUB_CLICK_BPM, 0) ?: 0
        monitorClick = if (clickBpm in SUPPORTED_BPM) runCatching {
            MonitorClick(
                clickBpm,
                intent?.getIntExtra(EXTRA_OVERDUB_CLICK_BEATS, 4) ?: 4,
                intent?.getIntExtra(EXTRA_OVERDUB_COUNT_IN_BARS, 0) ?: 0
            )
        }.getOrNull() else null
        if (id == null) {
            overdubBaseProject = null
        }
        return id
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
                    clockTracker::onBackingClock,
                    clockTracker::onBackingRoute
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
                    this, file, takesFocus = false, onClock = clockTracker::onBackingClock,
                    monitorClick = monitorClick, onRoute = clockTracker::onBackingRoute
                )
                if (transportStopped || recorder == null) {
                    player.release()
                    return@Thread
                }
                backingPlayer = player
                if (transportStopped) {
                    player.release()
                    if (backingPlayer === player) backingPlayer = null
                    return@Thread
                }
                player.seek(0L)
                player.play(project.totalFrames)
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
            clockTracker.timeline?.routeInvalidated() == true -> "Audio route changed. Timing is uncalibrated; captured audio is still being saved."
            clockTracker.timeline?.clockDiscontinuous() == true -> "Audio clock discontinuity detected. Drift correction is disabled for this take."
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

    /** Route changes arrive on audio threads; state updates belong on the main looper. */
    private fun publishOverdubRoute(route: OverdubRoute?) {
        main.post { mutableState.value = mutableState.value.copy(overdubRoute = route) }
    }

    private fun pauseTransport() {
        clockTracker.timeline?.pause()
        recorder?.pause()
        backingPlayer?.pause()
        clickOnlyPlayer?.pause()
    }

    private fun resumeTransport() {
        stopPreview()
        clockTracker.timeline?.resume()
        recorder?.resume()
        overdubBaseProject?.let { backingPlayer?.play(it.totalFrames) }
        clickOnlyPlayer?.resume()
    }

    private fun stopTransport() {
        startToken++
        loadingBase = false
        transportStopped = true
        stopPreview()
        snapshotOutputFailure()
        releaseBackingPlayer()
        releaseClickOnlyPlayer()
        recorder?.requestStop()
        // Nothing else publishes a terminal phase before the take starts, so the cancel is reported here.
        if (recorder == null && mutableState.value.phase == RecordingPhase.STARTING) mutableState.value = RecordingServiceState()
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

    /** Toggle target for ACTION_PREVIEW; only a paused take has a stable PCM range to hear. */
    private fun startPreview() {
        val source = recorder ?: return
        if (mutableState.value.phase != RecordingPhase.PAUSED || finalizing || transportStopped) return
        if (source.state.value.phase != RecordingPhase.PAUSED) return
        val captured = source.captured()
        if (captured.frames <= 0L) return
        stopPreview()
        val player = try {
            // The service already holds recording focus, so the preview rides on it and requests none of its own.
            WavPlayer.forCapturedRange(this, captured.file, captured.sampleRate, captured.channels, captured.frames)
        } catch (_: Throwable) {
            mutableState.value = mutableState.value.copy(
                alignmentNotice = mergeNotices(mutableState.value.alignmentNotice, "Take preview is unavailable.")
            )
            return
        }
        previewPlayer = player
        player.play(captured.frames)
        mutableState.value = mutableState.value.copy(previewPlaying = true, previewFrame = 0L)
        main.postDelayed(previewTick, PREVIEW_PUBLISH_MILLIS)
    }

    /** Must run before the recorder resumes or finalises: the player holds the partial WAV open. */
    private fun stopPreview() {
        main.removeCallbacks(previewTick)
        previewPlayer?.release()
        previewPlayer = null
        if (mutableState.value.previewPlaying || mutableState.value.previewFrame != 0L) {
            mutableState.value = mutableState.value.copy(previewPlaying = false, previewFrame = 0L)
        }
    }

    private val previewTick = object : Runnable {
        override fun run() {
            val player = previewPlayer ?: return
            val snapshot = player.state.value
            if (!snapshot.isPlaying) {
                snapshot.error?.let { failure ->
                    mutableState.value = mutableState.value.copy(
                        alignmentNotice = mergeNotices(mutableState.value.alignmentNotice, "Take preview failed: $failure")
                    )
                }
                stopPreview()
                return
            }
            mutableState.value = mutableState.value.copy(previewPlaying = true, previewFrame = snapshot.positionFrames)
            main.postDelayed(this, PREVIEW_PUBLISH_MILLIS)
        }
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
        stopPreview()
        unregisterRecordingCallback()
        transportStopped = true
        snapshotOutputFailure()
        releaseBackingPlayer()
        releaseClickOnlyPlayer()
        val snapshot = clockTracker.snapshot(overdubBaseProject, lostFocusPermanently)
        val save = Runnable {
            if (take.frames <= 0L) {
                val recoverable = hasRecoverablePcm(take)
                if (!recoverable) take.file.delete()
                val notice = if (recoverable) {
                    take.error ?: "WAV header finalization failed; a recoverable partial was retained."
                } else {
                    take.error ?: "No audio samples were captured. Nothing was saved."
                }
                main.post { finishTake(sourceRecorder, take, null, notice) }
                return@Runnable
            }
            val saved = TakePersister(this).persist(take, snapshot)
            main.post {
                finishTake(sourceRecorder, take, saved.project, saved.error, saved.interruptionNotice, saved.alignmentNotice, saved.overdub)
            }
        }
        // onDestroy also runs here, so this check cannot race; a destroyed service has no saving thread left.
        if (background.isShutdown) save.run() else background.execute(save)
    }

    private fun finishTake(
        completedRecorder: WavRecorder,
        take: RecordingTake,
        project: Project?,
        error: String?,
        interruptionNotice: String? = null,
        alignmentNotice: String? = null,
        overdub: OverdubMix? = null
    ) {
        // The take is saved or abandoned now, so startup recovery may adopt its file.
        if (RecordingActivePath.path == take.file.absolutePath) RecordingActivePath.path = null
        if (recorder === completedRecorder) recorder = null
        val terminalNotice = mergeNotices(alignmentNotice, outputFailureNotice, mutableState.value.alignmentNotice)
        mutableState.value = RecordingServiceState(
            phase = if (project != null && error == null) RecordingPhase.COMPLETE else RecordingPhase.FAILED,
            frames = take.frames,
            sampleRate = take.sampleRate,
            error = error,
            projectId = project?.id,
            silent = take.allSamplesZero,
            interruptionNotice = interruptionNotice,
            alignmentNotice = terminalNotice,
            overdubRoute = clockTracker.route,
            completion = project?.let { TakeCompletion(it.id, overdub) }
        )
        // A save can outlive the service; the result is still published, but a destroyed service has nothing to stop.
        if (destroyed) return
        updateNotification()
        abandonFocus()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelfResult(latestStartId)
    }

    private fun finishWithoutTake(message: String) {
        recorder = null
        mutableState.value = RecordingServiceState(RecordingPhase.FAILED, error = message)
        stopForeground(STOP_FOREGROUND_REMOVE)
        abandonFocus()
        stopSelfResult(latestStartId)
    }

    private fun hasRecoverablePcm(take: RecordingTake): Boolean =
        runCatching { take.file.isFile && take.file.length() >= WavFormat.HEADER_BYTES + take.channels * 2L }.getOrDefault(false)

    private fun promoteToForeground(text: String) {
        val notification = notification(text)
        if (Build.VERSION.SDK_INT >= 30) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(NOTIFICATION_ID, notification)
    }

    private fun updateNotification() {
        val phase = mutableState.value.phase
        if (phase == RecordingPhase.IDLE || phase == RecordingPhase.COMPLETE || phase == RecordingPhase.FAILED) return
        val message = when (phase) {
            RecordingPhase.PAUSED -> "Recording paused"
            else -> "Recording in progress"
        }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(message))
    }

    private fun notification(text: String): Notification {
        val paused = mutableState.value.phase == RecordingPhase.PAUSED
        val launch = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val pauseAction = if (paused) ACTION_RESUME else ACTION_PAUSE
        val pauseLabel = if (paused) "Resume" else "Pause"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic_notification)
            .setContentTitle("Kanatron")
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
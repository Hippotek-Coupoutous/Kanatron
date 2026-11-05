package com.ottomusic.sketch.playback

import android.content.Context
import android.content.pm.ApplicationInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.ottomusic.sketch.core.AudioClockPoint
import com.ottomusic.sketch.core.WavFormat
import com.ottomusic.sketch.platform.PcmOutput
import com.ottomusic.sketch.platform.PlatformPorts
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PlayerState(val positionFrames: Long = 0L, val totalFrames: Long = 0L, val isPlaying: Boolean = false, val error: String? = null)

private const val TAG = "KanatronAudio"

private data class SeekRequest(val frame: Long, val revision: Long)

/**
 * Streams PCM directly so seek positions are expressed in exact source frames.
 * [takesFocus] = false plays alongside an active recording (a layer backing track) without stealing its audio focus.
 */
class WavPlayer(
    context: Context,
    private val wav: File,
    private val takesFocus: Boolean = true,
    private val onClock: (AudioClockPoint, String, String) -> Unit = { _, _, _ -> },
    private val monitorClick: MonitorClick? = null,
    private val onRoute: (String, String) -> Unit = { _, _ -> }
) {
    private val appContext = context.applicationContext
    private val debugAudioLog = (appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    private val format = wav.inputStream().use(WavFormat::read)
    private val align = format.blockAlign
    private val blockBytes = maxOf(align, 8192 / align * align)
    private val totalFrames = format.frameCount
    private val clickMixer = monitorClick?.let { ClickPcmMixer(format.sampleRate, format.channels, it) }
    private val countInFrames = monitorClick?.countInFrames(format.sampleRate) ?: 0L
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val mutableState = MutableStateFlow(PlayerState(totalFrames = totalFrames))
    val state: StateFlow<PlayerState> = mutableState.asStateFlow()
    @Volatile private var playing = false
    private val requestedSeek = AtomicReference(SeekRequest(0L, 0L))
    @Volatile private var stopFrame = totalFrames
    @Volatile private var closed = false
    @Volatile private var track: PcmOutput? = null
    // True while the AudioTrack is started and its buffered audio is still valid (a flush clears it).
    @Volatile private var outputStarted = false
    @Volatile private var focusRequest: AudioFocusRequest? = null
    private val worker = RestartableWorker("wav-player", ::playLoop)

    init {
        require(format.bitsPerSample == 16 && format.channels in 1..2) { "Only mono/stereo PCM16 WAV is supported" }
    }

    @Synchronized
    fun play(stopAtFrame: Long = totalFrames) {
        if (closed) return
        val focusGranted = !takesFocus || try {
            requestFocus()
        } catch (failure: Throwable) {
            mutableState.value = mutableState.value.copy(isPlaying = false, error = failure.message ?: "Could not get audio focus for playback")
            return
        }
        if (!focusGranted) {
            mutableState.value = mutableState.value.copy(isPlaying = false, error = "Could not get audio focus for playback")
            return
        }
        stopFrame = stopAtFrame.coerceIn(requestedSeek.get().frame, totalFrames)
        playing = true
        mutableState.value = mutableState.value.copy(isPlaying = true, error = null)
        // Resume of a started track reuses its buffered block; that also releases a write blocked by pause().
        if (outputStarted && runCatching { track?.play() }.isFailure) {
            playing = false
            abandonFocus()
            mutableState.value = mutableState.value.copy(isPlaying = false, error = "Could not resume playback")
            return
        }
        worker.start()
    }

    @Synchronized
    fun pause() {
        playing = false
        runCatching { track?.pause() }
        abandonFocus()
        mutableState.value = mutableState.value.copy(isPlaying = false)
    }

    @Synchronized
    fun seek(frame: Long) {
        val target = frame.coerceIn(0L, totalFrames)
        val request = requestedSeek.updateAndGet { SeekRequest(target, it.revision + 1L) }
        if (!playing) mutableState.value = mutableState.value.copy(positionFrames = request.frame)
        // A blocking AudioTrack write can keep the worker from observing a seek.
        runCatching { track?.pause(); track?.flush() }
    }

    @Synchronized
    fun stop() {
        pause()
        requestedSeek.updateAndGet { SeekRequest(0L, it.revision + 1L) }
        stopFrame = totalFrames
        runCatching { track?.flush() }
        mutableState.value = PlayerState(totalFrames = totalFrames)
    }

    fun release() {
        if (closed) return
        closed = true
        pause()
        worker.join(500L)
        runCatching { track?.release() }
        track = null
        abandonFocus()
    }

    private fun playLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        try {
            val output = createTrack() ?: throw IllegalStateException("Could not create audio output")
            track = output
            outputStarted = false
            if (debugAudioLog) {
                Log.d(TAG, "track rate=${format.sampleRate} bufferFrames=${output.bufferSizeInFrames} bufferCapacityFrames=${output.bufferCapacityInFrames}")
            }
            try {
            RandomAccessFile(wav, "r").use { input ->
            val buffer = ByteArray(blockBytes)
            val publishEveryFrames = format.sampleRate / 10L
            var lastPublishFrame = -1L
            var publishDue = true
            var lastUnderruns = output.underruns
            var lastWriteMs = SystemClock.elapsedRealtime()
            val routeCheckFrames = format.sampleRate / 4L
            var routeFrames = routeCheckFrames
            var routeKey = ""
            var routeName = ""
            var appliedFrame = -1L
            var appliedRevision = -1L
            var headBase = output.playedFrames
            var submittedFrames = 0L
            var outputFramesSubmitted = 0L
            var pendingOffset = 0
            var pendingCount = 0
            var pendingIsBacking = true
            while (!closed) {
                val request = requestedSeek.get()
                val target = request.frame
                if (request.revision != appliedRevision) {
                    var resetApplied = false
                    synchronized(this) {
                        if (requestedSeek.get().revision == request.revision) {
                            output.pause()
                            output.flush()
                            input.seek(format.dataOffset + target * align)
                            appliedFrame = target
                            appliedRevision = request.revision
                            submittedFrames = 0L
                            outputFramesSubmitted = 0L
                            pendingOffset = 0
                            pendingCount = 0
                            pendingIsBacking = true
                            headBase = output.playedFrames
                            outputStarted = false
                            publishDue = true
                            resetApplied = true
                        }
                    }
                    if (!resetApplied) continue
                }
                if (!playing) {
                    try { Thread.sleep(20L) } catch (_: InterruptedException) { break }
                    continue
                }
                if (appliedFrame + submittedFrames >= stopFrame) {
                    var waitForPlayback = false
                    synchronized(this) {
                        if (playing && requestedSeek.get().revision == appliedRevision &&
                            appliedFrame + submittedFrames >= stopFrame) {
                            val played = (output.playedFrames - headBase) and 0xffff_ffffL
                            val backingPlayed = (played - countInFrames).coerceAtLeast(0L)
                            val frame = (appliedFrame + backingPlayed).coerceAtMost(stopFrame)
                            if (publishDue || frame < lastPublishFrame || frame - lastPublishFrame >= publishEveryFrames) {
                                publishDue = false
                                lastPublishFrame = frame
                                mutableState.value = PlayerState(frame, totalFrames, frame < stopFrame)
                            }
                            if (backingPlayed >= submittedFrames && played >= outputFramesSubmitted) {
                                output.pause()
                                requestedSeek.updateAndGet {
                                    if (it.revision == appliedRevision) it.copy(frame = stopFrame) else it
                                }
                                abandonFocus()
                                playing = false
                                mutableState.value = PlayerState(stopFrame, totalFrames, false)
                            } else {
                                waitForPlayback = true
                            }
                        }
                    }
                    if (waitForPlayback) Thread.sleep(15L)
                    continue
                }
                val left = stopFrame - (appliedFrame + submittedFrames)
                if (pendingOffset >= pendingCount) {
                    val countInLeft = countInFrames - outputFramesSubmitted
                    if (countInLeft > 0L) {
                        val frames = minOf(buffer.size / align.toLong(), countInLeft).toInt()
                        val wanted = frames * align
                        buffer.fill(0, 0, wanted)
                        clickMixer?.renderInto(buffer, 0, frames, outputFramesSubmitted)
                        pendingIsBacking = false
                        pendingCount = wanted
                    } else {
                        val wanted = minOf(buffer.size.toLong(), left * align).toInt()
                        input.readFully(buffer, 0, wanted)
                        clickMixer?.renderInto(buffer, 0, wanted / align, outputFramesSubmitted)
                        pendingIsBacking = true
                        pendingCount = wanted
                    }
                    pendingOffset = 0
                    if (pendingCount <= 0) {
                        output.pause()
                        abandonFocus()
                        playing = false
                        mutableState.value = mutableState.value.copy(isPlaying = false)
                        continue
                    }
                }
                while (pendingOffset < pendingCount && playing && !closed && requestedSeek.get().revision == appliedRevision) {
                    val written = output.write(buffer, pendingOffset, pendingCount - pendingOffset)
                    if (written < 0) {
                        throw IllegalStateException("Audio output failed ($written)")
                    }
                    if (written == 0) continue
                    val framesBeforeWrite = pendingOffset / align
                    pendingOffset += written
                    val writtenFrames = pendingOffset / align - framesBeforeWrite
                    outputFramesSubmitted += writtenFrames
                    routeFrames += writtenFrames
                    if (pendingIsBacking) submittedFrames += writtenFrames
                }
                // An unstarted track consumes nothing, so play() only once a full block sits in its buffer.
                if (!outputStarted && pendingOffset >= pendingCount && playing && !closed &&
                    requestedSeek.get().revision == appliedRevision) {
                    output.play()
                    outputStarted = true
                }
                if (debugAudioLog) {
                    val nowMs = SystemClock.elapsedRealtime()
                    val count = output.underruns
                    if (count > lastUnderruns) {
                        lastUnderruns = count
                        Log.w(TAG, "underruns=$count headFrames=${output.playedFrames} sinceWriteMs=${nowMs - lastWriteMs}")
                    }
                    lastWriteMs = nowMs
                }
                synchronized(this) {
                    if (playing && requestedSeek.get().revision == appliedRevision) {
                        val played = (output.playedFrames - headBase) and 0xffff_ffffL
                        val frame = (appliedFrame + (played - countInFrames).coerceAtLeast(0L)).coerceAtMost(stopFrame)
                        if (publishDue || frame < lastPublishFrame || frame - lastPublishFrame >= publishEveryFrames) {
                            publishDue = false
                            lastPublishFrame = frame
                            mutableState.value = PlayerState(frame, totalFrames, true)
                        }
                    }
                }
                if (routeFrames >= routeCheckFrames) {
                    routeFrames = 0L
                    routeKey = output.routeKey
                    routeName = output.routeName
                    runCatching { onRoute(routeKey, routeName) }
                }
                output.timestamp()?.let { point -> runCatching { onClock(point, routeKey, routeName) } }
            }
            }
            } finally {
                runCatching { output.pause(); output.flush(); output.release() }
                if (track === output) track = null
            }
        } catch (failure: Throwable) {
            playing = false
            mutableState.value = mutableState.value.copy(
                isPlaying = false,
                error = failure.message ?: "Playback failed"
            )
        } finally {
            if (playing) {
                playing = false
                mutableState.value = mutableState.value.copy(isPlaying = false)
            }
            abandonFocus()
        }
    }

    private fun createTrack(): PcmOutput? {
        // 4 write blocks of buffered headroom (48 kHz: ~340 ms mono, ~170 ms stereo).
        return PlatformPorts.pcmOutputFactory(appContext)
            .open(format.sampleRate, format.channels, blockBytes * 4)
    }

    @Synchronized
    private fun requestFocus(): Boolean {
        if (focusRequest != null) return true
        val listener = AudioManager.OnAudioFocusChangeListener { change ->
            if (change <= AudioManager.AUDIOFOCUS_LOSS) pause()
        }
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setOnAudioFocusChangeListener(listener)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            .build()
        focusRequest = request
        return try {
            val granted = audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            if (!granted) focusRequest = null
            granted
        } catch (failure: Throwable) {
            focusRequest = null
            throw failure
        }
    }

    @Synchronized
    private fun abandonFocus() {
        val request = focusRequest ?: return
        focusRequest = null
        runCatching { audioManager.abandonAudioFocusRequest(request) }
    }
}
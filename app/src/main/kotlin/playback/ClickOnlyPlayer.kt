package com.kanatron.app.playback

import android.content.Context
import android.os.Process
import com.kanatron.app.core.AudioClockPoint
import com.kanatron.app.platform.PcmOutput
import com.kanatron.app.platform.PlatformPorts

/** Click-only monitor used while recording; it shares the service's focus and PCM output owner. */
internal class ClickOnlyPlayer(
    context: Context,
    private val sampleRate: Int,
    settings: MonitorClick,
    private val onClock: (AudioClockPoint, String, String) -> Unit,
    private val onRoute: (String, String) -> Unit = { _, _ -> }
) {
    private val output: PcmOutput = PlatformPorts.pcmOutputFactory(context.applicationContext).open(sampleRate, 1, bufferBytes = null)
        ?: throw IllegalStateException("Could not create click monitor output")
    private val mixer = ClickPcmMixer(sampleRate, 1, settings)
    private val lock = Any()
    @Volatile private var closed = false
    @Volatile private var playing = false
    @Volatile var error: String? = null
        private set
    private var worker: Thread? = null
    private var routeReported = false

    fun start() {
        synchronized(lock) {
            check(!closed)
            if (worker != null) return
            output.play()
            if (!routeReported) {
                runCatching { onRoute(output.routeKey, output.routeName) }
                routeReported = true
            }
            playing = true
            worker = Thread(::loop, "recording-click-monitor").apply { start() }
        }
    }

    fun pause() {
        playing = false
        runCatching { output.pause() }
    }

    fun resume() {
        if (closed) return
        runCatching { output.play() }.onFailure { error = it.message ?: "Click monitor could not resume" }
        playing = error == null
    }

    fun release() {
        synchronized(lock) {
            if (closed) return
            closed = true
            playing = false
        }
        runCatching { output.pause() }
        try { worker?.join(500L) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        runCatching { output.flush(); output.release() }
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val frames = 480
        val block = ByteArray(frames * 2)
        val routeCheckFrames = sampleRate / 4L
        var routeFrames = routeCheckFrames
        var routeKey = ""
        var routeName = ""
        var startFrame = 0L
        try {
            while (!closed) {
                if (!playing) {
                    try { Thread.sleep(20L) } catch (_: InterruptedException) { return }
                    continue
                }
                block.fill(0)
                mixer.renderInto(block, 0, frames, startFrame)
                var offset = 0
                while (offset < block.size && playing && !closed) {
                    val written = output.write(block, offset, block.size - offset)
                    if (written < 0) error("Audio output failed ($written)")
                    if (written > 0) offset += written
                }
                if (offset < block.size) continue
                startFrame += frames
                routeFrames += frames
                if (routeFrames >= routeCheckFrames) {
                    routeFrames = 0L
                    routeKey = output.routeKey
                    routeName = output.routeName
                    runCatching { onRoute(routeKey, routeName) }
                }
                output.timestamp()?.let { point -> runCatching { onClock(point, routeKey, routeName) } }
            }
        } catch (failure: Throwable) {
            error = failure.message ?: "Click monitor failed"
        }
    }
}
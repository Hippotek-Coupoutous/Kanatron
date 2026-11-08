package com.kanatron.app.tuner

import android.media.AudioRecord
import android.os.SystemClock
import com.kanatron.app.core.TunerPitch
import com.kanatron.app.core.TunerPitchReading
import com.kanatron.app.core.pcm16At
import com.kanatron.app.platform.MICROPHONE_PERMISSION_ERROR
import com.kanatron.app.platform.PcmInput
import com.kanatron.app.platform.PcmInputFactory
import com.kanatron.app.platform.MicrophoneOwnership
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class TunerAudioState(
    val reading: TunerPitchReading? = null,
    val error: String? = null,
    val holding: Boolean = false
)

/** Keeps the last note on screen while a plucked string decays below detection. */
internal class ReadingHold {
    private var last: TunerPitchReading? = null
    private var lastAtMs = 0L

    fun next(reading: TunerPitchReading?, nowMs: Long): TunerAudioState {
        if (reading != null) {
            last = reading
            lastAtMs = nowMs
            return TunerAudioState(reading)
        }
        val held = last
        if (held == null || nowMs - lastAtMs > HOLD_MS) {
            last = null
            return TunerAudioState()
        }
        return TunerAudioState(held, holding = true)
    }

    private companion object {
        const val HOLD_MS = 1500L
    }
}

/** Owns one foreground microphone input and releases it on every stop or failure path. */
internal class TunerAudioSession(private val inputFactory: PcmInputFactory) {
    private val lock = Any()
    private val _state = MutableStateFlow(TunerAudioState())
    val state: StateFlow<TunerAudioState> = _state.asStateFlow()

    @Volatile private var stopRequested = true
    @Volatile private var input: PcmInput? = null
    @Volatile private var worker: Thread? = null
    @Volatile private var a4Hz = TunerPitch.DEFAULT_A4_HZ
    private var microphoneLease: MicrophoneOwnership.Lease? = null

    fun setA4Frequency(value: Double) {
        if (value.isFinite() && value in 400.0..480.0) a4Hz = value
    }

    fun start(): Boolean = synchronized(lock) {
        if (worker?.isAlive == true) return false
        microphoneLease = MicrophoneOwnership.tryAcquire("tuner")
        if (microphoneLease == null) {
            _state.value = TunerAudioState(error = MicrophoneOwnership.busyMessage("tuner"))
            return false
        }
        stopRequested = false
        _state.value = TunerAudioState()
        Thread(::capture, "tuner-audio").also {
            worker = it
            it.start()
        }
        true
    }

    fun stop() {
        val (thread, opened) = synchronized(lock) {
            stopRequested = true
            _state.value = TunerAudioState()
            worker to input
        }
        runCatching { opened?.stop() }
        if (thread != null && thread !== Thread.currentThread()) {
            try {
                thread.join(500)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private fun capture() {
        var opened: PcmInput? = null
        try {
            if (stopRequested) return
            val created = inputFactory.open(listOf(44_100, 48_000, 32_000, 22_050, 16_000), 1)
                ?: throw IllegalStateException("No supported microphone format is available.")
            opened = created
            synchronized(lock) {
                if (stopRequested) return
                input = created
                created.start()
            }

            val bytes = ByteArray(4096)
            val samples = FloatArray(FRAME_SAMPLES)
            val pair = ByteArray(2)
            var pendingBytes = 0
            var sampleCount = 0
            var hold = ReadingHold()
            var holdA4Hz = a4Hz
            var noiseFloor = TunerPitch.QUIET_ROOM_FLOOR
            // Minimum statistics: the floor drops at once and leaks up 0.1 dB per second.
            val floorLeak = Math.pow(10.0, 0.1 / 20.0 * FRAME_SAMPLES / created.sampleRate)
            while (!stopRequested) {
                val count = created.read(bytes)
                if (stopRequested) break
                if (count == AudioRecord.ERROR_DEAD_OBJECT) throw IllegalStateException("Microphone connection was lost.")
                if (count < 0) throw IllegalStateException("Microphone read failed ($count).")
                if (count == 0) continue

                for (index in 0 until count) {
                    pair[pendingBytes++] = bytes[index]
                    if (pendingBytes < 2) continue
                    pendingBytes = 0
                    samples[sampleCount++] = pcm16At(pair, 0) / 32768f
                    if (sampleCount == samples.size) {
                        val frameA4Hz = a4Hz
                        if (frameA4Hz != holdA4Hz) {
                            hold = ReadingHold()
                            holdA4Hz = frameA4Hz
                        }
                        val reading = TunerPitch.detect(samples, created.sampleRate, frameA4Hz, TunerPitch.gateRms(noiseFloor))
                        if (reading == null) noiseFloor = minOf(TunerPitch.rms(samples), noiseFloor * floorLeak)
                        publishWhileActive(hold.next(reading, SystemClock.elapsedRealtime()))
                        sampleCount = 0
                    }
                }
            }
        } catch (failure: SecurityException) {
            publishWhileActive(TunerAudioState(error = MICROPHONE_PERMISSION_ERROR))
        } catch (failure: Throwable) {
            publishWhileActive(TunerAudioState(error = failure.message ?: "Could not start the tuner."))
        } finally {
            opened?.let {
                runCatching { it.stop() }
                runCatching { it.release() }
            }
            synchronized(lock) {
                if (input === opened) input = null
                if (worker === Thread.currentThread()) worker = null
                if (stopRequested) _state.value = TunerAudioState()
                microphoneLease?.close()
                microphoneLease = null
            }
        }
    }

    private fun publishWhileActive(value: TunerAudioState) = synchronized(lock) {
        if (!stopRequested) _state.value = value
    }

    private companion object {
        const val FRAME_SAMPLES = 4096
    }
}
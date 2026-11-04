package com.ottomusic.sketch.tuner

import android.media.AudioRecord
import com.ottomusic.sketch.core.TunerPitch
import com.ottomusic.sketch.core.TunerPitchReading
import com.ottomusic.sketch.platform.MICROPHONE_PERMISSION_ERROR
import com.ottomusic.sketch.platform.PcmInput
import com.ottomusic.sketch.platform.PcmInputFactory
import com.ottomusic.sketch.recording.MicrophoneOwnership
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class TunerAudioState(
    val isListening: Boolean = false,
    val reading: TunerPitchReading? = null,
    val error: String? = null
)

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
        _state.value = TunerAudioState(isListening = true)
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
            var sampleCount = 0
            var pendingLowByte = -1
            while (!stopRequested) {
                val count = created.read(bytes)
                if (stopRequested) break
                if (count == AudioRecord.ERROR_DEAD_OBJECT) throw IllegalStateException("Microphone connection was lost.")
                if (count < 0) throw IllegalStateException("Microphone read failed ($count).")
                if (count == 0) continue

                for (index in 0 until count) {
                    val value = bytes[index].toInt() and 0xff
                    if (pendingLowByte < 0) {
                        pendingLowByte = value
                    } else {
                        samples[sampleCount++] = ((pendingLowByte or (value shl 8)).toShort().toInt() / 32768f)
                        pendingLowByte = -1
                        if (sampleCount == samples.size) {
                            val reading = TunerPitch.detect(samples, created.sampleRate, a4Hz)
                            publishWhileActive(TunerAudioState(isListening = true, reading = reading))
                            sampleCount = 0
                        }
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
                else if (_state.value.isListening) _state.value = _state.value.copy(isListening = false)
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
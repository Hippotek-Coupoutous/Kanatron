package com.kanatron.app.tuner

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import com.kanatron.app.core.TunerPitch
import com.kanatron.app.core.TunerPitchReading
import com.kanatron.app.core.pcm16At
import com.kanatron.app.platform.MICROPHONE_PERMISSION_ERROR
import com.kanatron.app.platform.PcmInput
import com.kanatron.app.platform.PcmInputFactory
import com.kanatron.app.platform.MicrophoneOwnership
import com.kanatron.app.playback.synthesizeClick
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.concurrent.thread
import kotlin.math.abs

internal data class TunerAudioState(
    val reading: TunerPitchReading? = null,
    val error: String? = null,
    val holding: Boolean = false,
    val inTune: Boolean = false
)

/**
 * Turns per-frame readings into a steady display: a new note shows only after it repeats, which
 * rejects attack-transient octave jumps; the shown pitch is a median; the last note holds while the
 * string decays below detection.
 */
internal class ReadingFilter {
    private val recent = ArrayDeque<TunerPitchReading>()
    private val candidates = ArrayList<TunerPitchReading>()
    private var shown = TunerAudioState()
    private var shownAtMs = 0L
    private var inTune = false
    private var inTuneStreak = 0

    fun next(reading: TunerPitchReading?, nowMs: Long): TunerAudioState {
        if (reading == null) {
            candidates.clear()
            return shownUnlessExpired(nowMs).let { if (it.reading == null) it else it.copy(holding = true) }
        }
        if (reading.note == recent.lastOrNull()?.note) {
            candidates.clear()
            recent.addLast(reading)
            if (recent.size > MEDIAN_FRAMES) recent.removeFirst()
        } else {
            if (candidates.firstOrNull()?.note != reading.note) candidates.clear()
            candidates += reading
            if (candidates.size < CONFIRM_FRAMES) return shownUnlessExpired(nowMs)
            recent.clear()
            recent.addAll(candidates)
            candidates.clear()
            forgetTuning()
        }
        val median = recent.sortedBy { it.frequencyHz }[recent.size / 2]
        inTuneStreak = if (abs(median.cents) <= ENTER_IN_TUNE_CENTS) inTuneStreak + 1 else 0
        inTune = if (inTune) abs(median.cents) <= LEAVE_IN_TUNE_CENTS else inTuneStreak >= IN_TUNE_FRAMES
        shown = TunerAudioState(median, inTune = inTune)
        shownAtMs = nowMs
        return shown
    }

    private fun shownUnlessExpired(nowMs: Long): TunerAudioState {
        if (nowMs - shownAtMs > HOLD_MS) {
            recent.clear()
            shown = TunerAudioState()
            forgetTuning()
        }
        return shown
    }

    private fun forgetTuning() {
        inTune = false
        inTuneStreak = 0
    }

    private companion object {
        const val HOLD_MS = 1500L
        const val CONFIRM_FRAMES = 3
        const val MEDIAN_FRAMES = 5
        const val ENTER_IN_TUNE_CENTS = 3.0
        // A pluck's attack rings sharp, then settles: the passing frames must not claim in tune.
        const val IN_TUNE_FRAMES = 5
        // The gap keeps a reading wobbling around 3 cents from flickering the label and re-beeping.
        const val LEAVE_IN_TUNE_CENTS = 5.0
    }
}

/** Slides the pitch window one hop at a time, so the display updates four times per window. */
internal class TunerAnalyzer(private val sampleRate: Int) {
    private val window = FloatArray(FRAME_SAMPLES)
    private var filter = ReadingFilter()
    private var filterA4Hz = 0.0
    private var filled = 0
    private var samplesSeen = 0L
    private var deafUntil = 0L
    private var shown = TunerAudioState()
    private var noiseFloor = TunerPitch.QUIET_ROOM_FLOOR
    // Minimum statistics: the floor drops at once and leaks up 0.1 dB per second.
    private val floorLeak = Math.pow(10.0, 0.1 / 20.0 * HOP_SAMPLES / sampleRate)

    /** Returns a new display state each hop, null between hops. */
    fun add(sample: Float, a4Hz: Double): TunerAudioState? {
        window[filled++] = sample
        samplesSeen++
        if (filled < FRAME_SAMPLES) return null
        // The microphone hears the in-tune beep; until it has left the window, keep showing the note it confirmed.
        val next = if (samplesSeen < deafUntil) shown else listen(a4Hz)
        window.copyInto(window, 0, HOP_SAMPLES)
        filled = FRAME_SAMPLES - HOP_SAMPLES
        if (next.inTune && !shown.inTune) deafUntil = samplesSeen + sampleRate * BEEP_MS / 1000 + FRAME_SAMPLES
        shown = next
        return next
    }

    private fun listen(a4Hz: Double): TunerAudioState {
        if (a4Hz != filterA4Hz) {
            filter = ReadingFilter()
            filterA4Hz = a4Hz
        }
        val reading = TunerPitch.detect(window, sampleRate, a4Hz, TunerPitch.gateRms(noiseFloor))
        if (reading == null) noiseFloor = minOf(TunerPitch.rms(window), noiseFloor * floorLeak)
        return filter.next(reading, samplesSeen * 1000 / sampleRate)
    }

    private companion object {
        const val FRAME_SAMPLES = 4096
        // ponytail: direct O(window x lag) YIN at ~43 frames/s; FFT difference function if low-end phones lag.
        const val HOP_SAMPLES = 1024
    }
}

/** A soft, decaying ping of the tuned note, moved into 440–880 Hz so phone speakers play it. */
internal fun inTuneBeep(noteHz: Double, sampleRate: Int): ShortArray {
    var hz = noteHz
    while (hz < 440.0) hz *= 2.0
    while (hz >= 880.0) hz /= 2.0
    return synthesizeClick(sampleRate, hz, BEEP_AMPLITUDE, BEEP_MS, decayPerSecond = 20.0)
}

internal fun playInTuneBeep(noteHz: Double) = thread(name = "tuner-beep") {
    val pcm = inTuneBeep(noteHz, BEEP_RATE)
    runCatching {
        AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(BEEP_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(pcm.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
    }.getOrNull()?.let { track ->
        try {
            track.write(pcm, 0, pcm.size)
            track.play()
            Thread.sleep(BEEP_MS + 50L)
        } catch (_: Exception) {
        } finally {
            track.release()
        }
    }
}

private const val BEEP_RATE = 48_000
private const val BEEP_MS = 150
private const val BEEP_AMPLITUDE = 0.2

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
            val pair = ByteArray(2)
            var pendingBytes = 0
            val analyzer = TunerAnalyzer(created.sampleRate)
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
                    analyzer.add(pcm16At(pair, 0) / 32768f, a4Hz)?.let(::publishWhileActive)
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
}
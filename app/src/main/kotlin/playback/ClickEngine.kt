package com.ottomusic.sketch.playback

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.ottomusic.sketch.core.SUPPORTED_BPM
import com.ottomusic.sketch.core.frameAtBeat
import com.ottomusic.sketch.core.mixClicks
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

private const val BLOCK_FRAMES = 480

/** Metronome state; [beat] is the absolute index of the last beat heard, -1 before the first one. */
data class ClickState(
    val running: Boolean = false,
    val bpm: Int = 120,
    val beatsPerBar: Int = 4,
    val beat: Long = -1,
)

/** Click track: two synthesized clicks mixed into one low-latency streaming [AudioTrack] by a dedicated thread. */
class ClickEngine(private val sampleRate: Int = 48_000) {

    private val _state = MutableStateFlow(ClickState())

    /** Current metronome state, safe to collect from the UI. */
    val state: StateFlow<ClickState> = _state.asStateFlow()

    private val accent = synthesizeClick(sampleRate, ACCENT_CLICK_HZ, ACCENT_CLICK_AMPLITUDE)
    private val normal = synthesizeClick(sampleRate, NORMAL_CLICK_HZ, NORMAL_CLICK_AMPLITUDE)
    private val lock = Any()
    private var track: AudioTrack? = null
    private var thread: Thread? = null
    private var framePosition = 0L
    private var anchorFrame = 0L
    private var anchorBeat = 0L
    private var milliBpm = 120_000
    private var beatsPerBar = 4

    @Volatile
    private var running = false

    /** Starts the metronome at beat 0; does nothing if it is already running. */
    fun start() {
        synchronized(lock) {
            if (running) return
            val t = openTrack() ?: return
            track = t
            framePosition = 0L
            anchorFrame = 0L
            anchorBeat = 0L
            running = true
            _state.update { it.copy(running = true, beat = -1) }
            thread = Thread({ loop() }, "click-engine").apply { start() }
        }
    }

    /** Stops playback and releases the audio device; never throws. */
    fun stop() {
        var t: Thread? = null
        var a: AudioTrack? = null
        synchronized(lock) {
            running = false
            t = thread
            a = track
            thread = null
            track = null
        }
        try {
            t?.join(500)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        a?.let { closeTrack(it) }
        _state.update { it.copy(running = false) }
    }

    private fun stopFromWorker() {
        synchronized(lock) { if (thread !== Thread.currentThread()) return }
        Thread { stop() }.start()
    }

    /** Releases the engine; same as [stop]. */
    fun release() = stop()

    /** Sets the tempo, clamped to 30..300 BPM; keeps the bar position when running. */
    fun setBpm(bpm: Int) {
        val b = bpm.coerceIn(SUPPORTED_BPM.first, SUPPORTED_BPM.last)
        synchronized(lock) {
            // Already waiting for an anchor beat (two quick changes): that beat simply takes the new tempo.
            if (running && framePosition >= anchorFrame) {
                // Continue the bar from the next beat boundary; the frames before it stay under the old tempo.
                val nextRel = beatsAt(framePosition - anchorFrame, milliBpm) + 1
                anchorFrame += frameAtBeat(nextRel, sampleRate, milliBpm)
                anchorBeat += nextRel
            }
            milliBpm = b * 1000
            _state.update { it.copy(bpm = b) }
        }
    }

    /** Sets the beats per accented bar, clamped to 1..12; 1 means every click is normal. */
    fun setBeatsPerBar(n: Int) {
        val v = n.coerceIn(1, 12)
        synchronized(lock) { beatsPerBar = v }
        _state.update { it.copy(beatsPerBar = v) }
    }

    private fun loop() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
        val block = ShortArray(BLOCK_FRAMES)
        var lastBeat = -1L
        // A worker that outlived stop() (blocked in write) exits as soon as a new start() replaced it.
        while (running && thread === Thread.currentThread()) {
            val anchor: Long
            val beat0: Long
            val tempo: Int
            val perBar: Int
            val start: Long
            val t: AudioTrack
            synchronized(lock) {
                anchor = anchorFrame
                beat0 = anchorBeat
                tempo = milliBpm
                perBar = beatsPerBar
                start = framePosition
                t = track ?: return
            }
            val rel = start - anchor // negative until the first beat after a tempo change
            block.fill(0)
            val clickFor = { beat: Long -> if (perBar > 1 && (beat + beat0) % perBar == 0L) accent else normal }
            if (rel >= 0L) mixClicks(block, rel, sampleRate, tempo, clickFor)
            else if (rel > -BLOCK_FRAMES) {
                // Drops the tail of the last old-tempo click (< 30 ms, inaudible).
                val tail = ShortArray((BLOCK_FRAMES + rel).toInt())
                mixClicks(tail, 0L, sampleRate, tempo, clickFor)
                tail.copyInto(block, (-rel).toInt())
            }
            if (!writeBlock(t, block)) { stopFromWorker(); return }
            val endedAt = rel + BLOCK_FRAMES
            if (endedAt >= 0L) {
                val beat = beat0 + beatsAt(endedAt, tempo)
                if (beat > lastBeat) {
                    lastBeat = beat
                    _state.update { it.copy(beat = beat) }
                }
            }
            synchronized(lock) { framePosition += BLOCK_FRAMES }
        }
    }

    private fun writeBlock(t: AudioTrack, block: ShortArray): Boolean {
        var off = 0
        while (off < block.size) {
            val n = try {
                t.write(block, off, block.size - off)
            } catch (_: IllegalStateException) {
                -1
            }
            if (n <= 0) return false
            off += n
        }
        return true
    }

    private fun openTrack(): AudioTrack? = try {
        val min = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setSampleRate(sampleRate)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(min, sampleRate / 10 * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
            .also { it.play() }
    } catch (_: Exception) {
        null
    }

    private fun closeTrack(t: AudioTrack) {
        try {
            t.pause()
        } catch (_: IllegalStateException) {
        }
        try {
            t.flush()
        } catch (_: IllegalStateException) {
        }
        try {
            t.release()
        } catch (_: IllegalStateException) {
        }
    }

    /** Floor of the beat index at a tempo-relative frame, i.e. the inverse of [frameAtBeat] for negative input too. */
    private fun beatsAt(relativeFrame: Long, tempo: Int): Long =
        Math.floorDiv(relativeFrame * tempo, sampleRate * 60_000L)
}
package com.kanatron.app.recording

import android.content.Context
import android.media.AudioRecord
import android.os.Process
import com.kanatron.app.core.AudioClockPoint
import com.kanatron.app.core.RecordingCore
import com.kanatron.app.core.RecordingLevel
import com.kanatron.app.core.TakeFinalization
import com.kanatron.app.core.WavFormat
import com.kanatron.app.data.RecordingActivePath
import com.kanatron.app.platform.MicrophoneOwnership
import com.kanatron.app.platform.PcmInput
import com.kanatron.app.platform.PlatformPorts
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet

data class RecordingTake(
    val id: String,
    val file: File,
    val sampleRate: Int,
    val channels: Int,
    val frames: Long,
    val error: String?,
    val allSamplesZero: Boolean
)
data class WavRecorderState(
    val phase: RecordingPhase = RecordingPhase.IDLE,
    val frames: Long = 0,
    val sampleRate: Int = 48_000,
    val level: RecordingLevel = RecordingLevel(0f),
    val error: String? = null,
    val inputDevice: String = "System microphone",
    val silenceWarning: Boolean = false,
    val inputSilenced: Boolean = false
)

/** The unfinalised take: its WAV header is still a placeholder, so its format comes from the recorder. */
data class CapturedTake(
    val file: File,
    val sampleRate: Int,
    val channels: Int,
    val frames: Long
)

/** AudioRecord PCM16 recorder. UNPROCESSED is selected only when the platform advertises it; otherwise VOICE_RECOGNITION then MIC. */
class WavRecorder(
    private val context: Context,
    private val onState: (WavRecorderState) -> Unit = {},
    private val onFinished: (RecordingTake) -> Unit,
    private val onClock: (AudioClockPoint, String, String) -> Unit = { _, _, _ -> },
    private val onRoute: (String, String) -> Unit = { _, _ -> }
) {
    companion object {
        const val START_RESERVE_BYTES = 10L * 1024L * 1024L
        const val RECORDING_RESERVE_BYTES = 4L * 1024L * 1024L
    }

    private val mutableState = MutableStateFlow(WavRecorderState())
    val state: StateFlow<WavRecorderState> = mutableState.asStateFlow()
    private val stopRequested = AtomicBoolean(false)
    @Volatile private var paused = false
    @Volatile private var failure: String? = null
    @Volatile private var recorder: PcmInput? = null
    @Volatile private var worker: Thread? = null
    private val id = UUID.randomUUID().toString()
    private val file = File(File(context.filesDir, "recordings").apply { mkdirs() }, "take-$id.partial.wav")
    private var openedRate = 48_000
    private var openedChannels = 1
    private var inputDevice = "System microphone"
    @Volatile private var capturedFrames = 0L
    private var capturedBytes = 0L
    private var silentFrames = 0L
    private var allSamplesZero = true
    @Volatile private var pcmWriteFailed = false
    private var microphoneLease: MicrophoneOwnership.Lease? = null

    fun start(beforeRecording: (PcmInput) -> Unit = {}) {
        check(worker == null) { "Recording already started" }
        pcmWriteFailed = false
        var opened: PcmInput? = null
        try {
            microphoneLease = MicrophoneOwnership.acquire("recording")
            if (!spaceAvailable(START_RESERVE_BYTES)) throw IllegalStateException("Not enough free space to start recording")
            opened = PlatformPorts.pcmInputFactory(context).open(listOf(48_000, 44_100, 32_000, 22_050, 16_000), 1)
                ?: throw IllegalStateException("No supported microphone recording format is available")
            recorder = opened
            openedRate = opened.sampleRate
            openedChannels = opened.channels.coerceAtLeast(1)
            RecordingActivePath.exclusive {
                RandomAccessFile(file, "rw").use { output ->
                    output.setLength(0)
                    output.write(RecordingCore.header(openedRate, openedChannels, 0))
                    output.fd.sync()
                }
                RecordingActivePath.path = file.absolutePath
            }
            beforeRecording(opened)
            opened.start()
            inputDevice = opened.inputDevice
            runCatching { onRoute(opened.routeKey, opened.routeName) }
            publish(WavRecorderState(RecordingPhase.RECORDING, sampleRate = openedRate, inputDevice = inputDevice))
            worker = Thread(::recordLoop, "wav-recorder").apply { start() }
        } catch (failure: Throwable) {
            runCatching { opened?.release() }
            microphoneLease?.close()
            microphoneLease = null
            recorder = null
            RecordingActivePath.exclusive {
                file.delete()
                if (RecordingActivePath.path == file.absolutePath) RecordingActivePath.path = null
            }
            // No worker has consumed audio yet; a failed start must not leave an empty take behind.
            throw failure
        }
    }

    fun pause() {
        if (worker == null || stopRequested.get()) return
        paused = true
        publish(mutableState.value.copy(phase = RecordingPhase.PAUSED, level = RecordingLevel(0f)))
    }

    fun resume() {
        if (worker == null || stopRequested.get()) return
        paused = false
        publish(mutableState.value.copy(phase = RecordingPhase.RECORDING))
    }

    /** Partial PCM range; pause first so the worker stops appending. */
    fun captured(): CapturedTake = CapturedTake(file, openedRate, openedChannels, capturedFrames)

    fun requestStop() {
        stopRequested.set(true)
        runCatching { recorder?.stop() }
    }

    fun setInputSilenced(silenced: Boolean) {
        publish(mutableState.value.copy(inputSilenced = silenced))
    }

    private fun recordLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        var output: FileOutputStream? = null
        try {
            val input = recorder ?: return
            val blockAlign = openedChannels * 2
            // StatFs and route IPCs are throttled by captured-audio count so the loop stays audio-only.
            val spaceCheckBytes = openedRate.toLong() * blockAlign
            var bytesSinceSpaceCheck = 0L
            val routeCheckFrames = openedRate / 4L
            var routeFrames = routeCheckFrames
            var routeKey = ""
            var routeName = ""
            // One reusable native PCM16 read buffer; paused samples are consumed and discarded.
            val buffer = ByteArray(input.readBufferBytes)
            val alignedBuffer = ByteArray(buffer.size + blockAlign - 1)
            val frameAligner = FrameAligner(blockAlign)
            var totalReadBytes = 0L
            if (!spaceAvailable(RECORDING_RESERVE_BYTES)) throw IllegalStateException("Recording stopped because device storage is low")
            output = FileOutputStream(file, true)
            while (!stopRequested.get()) {
                if (bytesSinceSpaceCheck >= spaceCheckBytes) {
                    bytesSinceSpaceCheck = 0L
                    if (!spaceAvailable(RECORDING_RESERVE_BYTES)) throw IllegalStateException("Recording stopped because device storage is low")
                }
                if (routeFrames >= routeCheckFrames) {
                    routeFrames = 0L
                    routeKey = input.routeKey
                    routeName = input.routeName
                    runCatching { onRoute(routeKey, routeName) }
                }
                val inputFrameCursor = totalReadBytes / blockAlign
                val count = input.read(buffer)
                if (count == AudioRecord.ERROR_DEAD_OBJECT) throw IllegalStateException("Microphone connection was lost")
                if (count < 0) {
                    if (stopRequested.get()) break
                    throw IllegalStateException("Microphone read failed ($count)")
                }
                if (count > 0) {
                    totalReadBytes = Math.addExact(totalReadBytes, count.toLong())
                    bytesSinceSpaceCheck += count
                    routeFrames += count / blockAlign
                    input.timestamp()?.let { point ->
                        point.atFrame(inputFrameCursor, openedRate)?.let { startPoint ->
                            runCatching { onClock(startPoint, routeKey, routeName) }
                        }
                    }
                }
                if (paused) {
                    frameAligner.clear()
                    continue
                }
                if (count == 0) continue
                val aligned = frameAligner.align(buffer, count, alignedBuffer)
                if (aligned == 0) continue
                val nextBytes = capturedBytes + aligned
                if (nextBytes > WavFormat.MAX_WAV_BYTES) throw IllegalStateException("WAV size limit reached")
                try {
                    output.write(alignedBuffer, 0, aligned)
                } catch (failure: IOException) {
                    pcmWriteFailed = true
                    throw failure
                }
                capturedBytes = nextBytes
                val frames = aligned / blockAlign
                capturedFrames += frames
                allSamplesZero = allSamplesZero && RecordingCore.allSamplesZero(alignedBuffer, aligned)
                val level = RecordingCore.level(alignedBuffer, aligned)
                silentFrames = if (level.isLow) silentFrames + frames else 0L
                merge {
                    it.copy(
                        frames = capturedFrames,
                        level = if (paused) RecordingLevel(0f) else level,
                        silenceWarning = RecordingCore.isSustainedSilence(silentFrames, openedRate)
                    )
                }
            }
        } catch (failure: Throwable) {
            this.failure = failure.message ?: "Recording failed"
        } finally {
            failure = TakeFinalization.finish(
                initialError = failure,
                syncData = { output?.fd?.sync() },
                closeData = { output?.close() },
                writeHeader = {
                    if (!pcmWriteFailed) {
                        RandomAccessFile(file, "rw").use { outputFile ->
                            outputFile.seek(0)
                            outputFile.write(RecordingCore.header(openedRate, openedChannels, capturedBytes))
                            outputFile.setLength(WavFormat.HEADER_BYTES + capturedBytes)
                            outputFile.fd.sync()
                        }
                    }
                },
                releaseRecorder = {
                    try { recorder?.release() } finally {
                        recorder = null
                        microphoneLease?.close()
                        microphoneLease = null
                    }
                }
            )
            val result = RecordingTake(id, file, openedRate, openedChannels, capturedFrames, failure, allSamplesZero)
            publish(WavRecorderState(
                if (failure == null) RecordingPhase.COMPLETE else RecordingPhase.FAILED, capturedFrames, openedRate,
                RecordingLevel(0f), failure, inputDevice
            ))
            onFinished(result)
        }
    }

    private fun publish(state: WavRecorderState) {
        mutableState.value = state
        onState(state)
    }

    /** Worker blocks merge into the state so a concurrent pause/resume publish is never overwritten. */
    private fun merge(change: (WavRecorderState) -> WavRecorderState) {
        onState(mutableState.updateAndGet(change))
    }

    private fun spaceAvailable(reserve: Long): Boolean {
        val free = PlatformPorts.availableBytes(context.filesDir)
        return RecordingCore.hasFreeSpace(free, reserve)
    }
}
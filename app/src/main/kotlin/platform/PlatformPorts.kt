package com.kanatron.app.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.StatFs
import androidx.core.content.ContextCompat
import com.kanatron.app.core.AudioClockPoint
import java.io.File

internal const val MICROPHONE_PERMISSION_ERROR =
    "Microphone access was denied or revoked. Allow microphone access in Settings and try again."

interface PcmInput {
    val sampleRate: Int
    val channels: Int
    val readBufferBytes: Int get() = 8192
    val inputDevice: String get() = "System microphone"
    val routeKey: String get() = inputDevice
    val routeName: String get() = inputDevice
    val platformAudioRecord: AudioRecord? get() = null
    fun timestamp(): AudioClockPoint? = null
    fun start()
    fun read(dst: ByteArray): Int
    fun stop()
    fun release()
}

fun interface PcmInputFactory {
    fun open(rateCandidates: List<Int>, channels: Int): PcmInput?
}

interface PcmOutput {
    val playedFrames: Long
    val underruns: Int get() = 0
    val bufferSizeInFrames: Int get() = 0
    val bufferCapacityInFrames: Int get() = 0
    val routeKey: String get() = "System output"
    val routeName: String get() = routeKey
    fun timestamp(): AudioClockPoint? = null
    fun play()
    fun pause()
    fun flush()
    fun write(bytes: ByteArray, offset: Int, count: Int): Int
    fun release()
}

fun interface PcmOutputFactory {
    /** [bufferBytes] = null keeps the platform minimum (raised to 16 KB) as the track buffer. */
    fun open(sampleRate: Int, channels: Int, bufferBytes: Int?): PcmOutput?
}

interface RecordingFocus {
    fun request(onPermanentLoss: () -> Unit): Boolean
    fun abandon()
}

internal object PlatformPorts {
    fun pcmInputFactory(context: Context): PcmInputFactory = AndroidPcmInputFactory(context)
    fun pcmOutputFactory(context: Context): PcmOutputFactory = AndroidPcmOutputFactory(context)
    fun recordingFocus(context: Context): RecordingFocus = AndroidRecordingFocus(context)
    fun availableBytes(path: File): Long =
        runCatching { StatFs(path.absolutePath).availableBytes }.getOrElse { path.usableSpace }
}

private class AndroidPcmInputFactory(private val context: Context) : PcmInputFactory {
    override fun open(rateCandidates: List<Int>, channels: Int): PcmInput? {
        require(channels in 1..2)
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException(MICROPHONE_PERMISSION_ERROR)
        }
        val channelMask = if (channels == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
        val unprocessedSupported = context.getSystemService(AudioManager::class.java)
                ?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val sources = (if (unprocessedSupported) listOf(MediaRecorder.AudioSource.UNPROCESSED) else emptyList()) +
            listOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)
        for (rate in rateCandidates) for (source in sources) {
            val minimum = AudioRecord.getMinBufferSize(rate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
            if (minimum <= 0) continue
            // Internal buffer ~250 ms so a late read cannot overflow it; read chunk ~20 ms stays well below it.
            val blockAlign = channels * 2
            val bufferBytes = maxOf(minimum * 4, rate * blockAlign / 4)
            val readBytes = maxOf(minimum, rate * blockAlign / 50)
            val candidate = try {
                AudioRecord(source, rate, channelMask, AudioFormat.ENCODING_PCM_16BIT, bufferBytes)
            } catch (failure: SecurityException) {
                // Permission can be revoked after the check; don't disguise it as format probing failure.
                throw failure
            } catch (_: IllegalArgumentException) {
                continue
            } catch (_: IllegalStateException) {
                continue
            }
            if (candidate.state == AudioRecord.STATE_INITIALIZED) return AndroidPcmInput(candidate, rate, channels, readBytes)
            candidate.release()
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException(MICROPHONE_PERMISSION_ERROR)
        }
        return null
    }
}

private class AndroidPcmInput(
    override val platformAudioRecord: AudioRecord,
    override val sampleRate: Int,
    override val channels: Int,
    override val readBufferBytes: Int
) : PcmInput {
    override val inputDevice: String
        get() = platformAudioRecord.routedDevice?.productName?.toString() ?: "System microphone"
    override val routeKey: String
        get() = platformAudioRecord.routedDevice?.let { "${it.type}:${it.id}" } ?: "unknown-input"

    override fun timestamp(): AudioClockPoint? {
        val timestamp = AudioTimestamp()
        return if (platformAudioRecord.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
            AudioClockPoint(timestamp.framePosition, timestamp.nanoTime)
        } else null
    }

    override fun start() {
        platformAudioRecord.startRecording()
        check(platformAudioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone did not start" }
    }

    override fun read(dst: ByteArray): Int = platformAudioRecord.read(dst, 0, dst.size, AudioRecord.READ_BLOCKING)
    override fun stop() { platformAudioRecord.stop() }
    override fun release() { platformAudioRecord.release() }
}

private class AndroidPcmOutputFactory(private val context: Context) : PcmOutputFactory {
    override fun open(sampleRate: Int, channels: Int, bufferBytes: Int?): PcmOutput? {
        val channelMask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minimum = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        if (minimum <= 0) return null
        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(channelMask).build())
                .setBufferSizeInBytes(bufferBytes ?: maxOf(minimum, 16_384))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrNull()
        if (track?.state != AudioTrack.STATE_INITIALIZED) {
            runCatching { track?.release() }
            return null
        }
        return AndroidPcmOutput(track)
    }
}

private class AndroidPcmOutput(private val track: AudioTrack) : PcmOutput {
    override val playedFrames: Long get() = track.playbackHeadPosition.toLong() and 0xffff_ffffL
    override val underruns: Int get() = track.underrunCount
    override val bufferSizeInFrames: Int get() = track.bufferSizeInFrames
    override val bufferCapacityInFrames: Int get() = track.bufferCapacityInFrames
    override val routeKey: String
        get() = track.routedDevice?.let { "${it.type}:${it.id}" } ?: "unknown-output"
    override val routeName: String
        get() = track.routedDevice?.productName?.toString() ?: "System output"
    override fun timestamp(): AudioClockPoint? {
        val timestamp = AudioTimestamp()
        return if (track.getTimestamp(timestamp)) AudioClockPoint(timestamp.framePosition, timestamp.nanoTime) else null
    }
    override fun play() = track.play()
    override fun pause() = track.pause()
    override fun flush() = track.flush()
    override fun write(bytes: ByteArray, offset: Int, count: Int): Int =
        track.write(bytes, offset, count, AudioTrack.WRITE_BLOCKING)
    override fun release() = track.release()
}

private class AndroidRecordingFocus(context: Context) : RecordingFocus {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var request: AudioFocusRequest? = null

    override fun request(onPermanentLoss: () -> Unit): Boolean {
        val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS) onPermanentLoss()
            }
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            .build()
        request = focusRequest
        return audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    override fun abandon() {
        request?.let(audioManager::abandonAudioFocusRequest)
        request = null
    }
}
package com.ottomusic.sketch.data

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.ottomusic.sketch.core.SUPPORTED_SAMPLE_RATES
import com.ottomusic.sketch.core.WavFormat
import com.ottomusic.sketch.core.putPcm16
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

internal enum class DecodedEncoding { PCM_16, PCM_FLOAT }

internal data class DecoderFormat(
    val sampleRate: Int,
    val channels: Int,
    val durationUs: Long? = null,
    val encoding: DecodedEncoding = DecodedEncoding.PCM_16
)

internal sealed class DecodeEvent {
    data class FormatChanged(val format: DecoderFormat) : DecodeEvent()
    data class Pcm(
        val buffer: ByteBuffer,
        val endOfStream: Boolean = false,
        val release: () -> Unit = {}
    ) : DecodeEvent()
    object TryAgain : DecodeEvent()
    object End : DecodeEvent()
}

internal interface AudioDecoderSession : Closeable {
    val initialFormat: DecoderFormat
    fun nextEvent(): DecodeEvent
}

internal fun interface AudioDecoderFactory {
    fun open(source: File): AudioDecoderSession
}

internal interface PcmWavSink : Closeable {
    fun write(pcm16le: ByteArray)
    fun finish(sampleRate: Int, channels: Int, dataBytes: Long)
}

internal fun interface PcmWavSinkFactory {
    fun open(output: File): PcmWavSink
}

/** The decode loop only sees decoder events, so it stays platform independent. */
internal class AudioDecodePipeline(
    private val decoderFactory: AudioDecoderFactory,
    private val sinkFactory: PcmWavSinkFactory,
    private val checkFreeSpace: (Long) -> Unit = {}
) {
    fun decode(
        source: File,
        output: File,
        sourceName: String,
        cancellation: ImportCancellation,
        progress: (writtenBytes: Long, expectedBytes: Long?) -> Unit
    ): ImportedAudio {
        var complete = false
        try {
            val result = decoderFactory.open(source).use { decoder ->
                var format = decoder.initialFormat
                validateFormat(format)
                format.durationUs?.let { duration ->
                    if (duration > MAX_DURATION_US) throw AudioImportException("Import is limited to ${MAX_DURATION_MINUTES} minutes.")
                    checkFreeSpace(duration * format.sampleRate * format.channels * 2L / 1_000_000L + WavFormat.HEADER_BYTES)
                }
                var written = 0L
                sinkFactory.open(output).use { sink ->
                    var done = false
                    while (!done) {
                        cancellation.check()
                        when (val event = decoder.nextEvent()) {
                            is DecodeEvent.FormatChanged -> {
                                val changed = event.format
                                if (written > 0L && (changed.sampleRate != format.sampleRate || changed.channels != format.channels || changed.encoding != format.encoding)) {
                                    throw AudioImportException("Decoder audio format changed during import.")
                                }
                                format = changed.copy(durationUs = changed.durationUs ?: format.durationUs)
                                validateFormat(format)
                            }
                            is DecodeEvent.Pcm -> {
                                try {
                                    if (event.buffer.hasRemaining()) {
                                        val pcm = toPcm16(event.buffer, format.encoding)
                                        val align = format.channels * 2
                                        if (pcm.size % align != 0) throw AudioImportException("The decoder returned an incomplete audio frame.")
                                        val nextBytes = Math.addExact(written, pcm.size.toLong())
                                        if (nextBytes / align > MAX_DURATION_MINUTES * 60L * format.sampleRate) {
                                            throw AudioImportException("Import is limited to ${MAX_DURATION_MINUTES} minutes.")
                                        }
                                        sink.write(pcm)
                                        written = nextBytes
                                        progress(written, format.durationUs?.let { it * format.sampleRate * format.channels * 2L / 1_000_000L })
                                    }
                                } finally {
                                    event.release()
                                }
                                done = event.endOfStream
                            }
                            DecodeEvent.TryAgain -> Unit
                            DecodeEvent.End -> done = true
                        }
                    }
                    if (written == 0L) throw AudioImportException("No audio samples could be decoded from this file.")
                    sink.finish(format.sampleRate, format.channels, written)
                }
                ImportedAudio(format.sampleRate, format.channels, written / (format.channels * 2L), sourceName)
            }
            complete = true
            return result
        } catch (cancelled: ImportCancelledException) {
            throw cancelled
        } catch (failure: Throwable) {
            if (failure is AudioImportException) throw failure
            if (isOutOfDiskSpace(failure)) throw AudioImportException("Not enough storage to decode this audio file.", failure)
            throw AudioImportException("This audio file is unsupported or unreadable.", failure)
        } finally {
            if (!complete) output.delete()
        }
    }

    private fun validateFormat(format: DecoderFormat) {
        if (format.sampleRate !in SUPPORTED_SAMPLE_RATES || format.channels !in 1..2) {
            throw AudioImportException("Only mono or stereo audio at 8–192 kHz is supported.")
        }
    }

    private fun toPcm16(buffer: ByteBuffer, encoding: DecodedEncoding): ByteArray {
        if (encoding == DecodedEncoding.PCM_16) return ByteArray(buffer.remaining()).also(buffer::get)
        val input = buffer.order(ByteOrder.LITTLE_ENDIAN)
        if (input.remaining() % 4 != 0) throw AudioImportException("Decoder returned incomplete floating-point audio.")
        val samples = input.remaining() / 4
        return ByteArray(samples * 2).also { output ->
            repeat(samples) { index ->
                val value = input.float.coerceIn(-1f, 1f)
                val sample = if (value <= -1f) Short.MIN_VALUE.toInt() else (value * Short.MAX_VALUE).roundToInt()
                putPcm16(output, index * 2, sample)
            }
        }
    }

    private companion object {
        const val MAX_DURATION_MINUTES = 60L
        const val MAX_DURATION_US = MAX_DURATION_MINUTES * 60L * 1_000_000L
    }
}

internal class FilePcmWavSinkFactory : PcmWavSinkFactory {
    override fun open(output: File): PcmWavSink = FilePcmWavSink(output)
}

private class FilePcmWavSink(private val file: File) : PcmWavSink {
    private var stream: FileOutputStream? = FileOutputStream(file).apply {
        write(ByteArray(WavFormat.HEADER_BYTES))
    }

    override fun write(pcm16le: ByteArray) {
        (stream ?: error("WAV output is already finished")).write(pcm16le)
    }

    override fun finish(sampleRate: Int, channels: Int, dataBytes: Long) {
        val data = stream ?: error("WAV output is already finished")
        data.fd.sync()
        data.close()
        stream = null
        val header = java.io.ByteArrayOutputStream(WavFormat.HEADER_BYTES).also {
            WavFormat.writeHeader(it, channels, sampleRate, dataBytes)
        }.toByteArray()
        RandomAccessFile(file, "rw").use { target ->
            target.seek(0)
            target.write(header)
            target.setLength(WavFormat.HEADER_BYTES + dataBytes)
            target.fd.sync()
        }
    }

    override fun close() {
        stream?.close()
        stream = null
    }
}

/** Adapts Android MediaExtractor/MediaCodec output to the tested event loop above. */
internal class AndroidAudioDecoderFactory : AudioDecoderFactory {
    override fun open(source: File): AudioDecoderSession {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(source.absolutePath)
            var selected: MediaFormat? = null
            for (index in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(index)
                if (candidate.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    extractor.selectTrack(index)
                    selected = candidate
                    break
                }
            }
            val track = selected ?: throw AudioImportException("No readable audio track was found.")
            val mime = track.getString(MediaFormat.KEY_MIME) ?: throw AudioImportException("Unsupported audio format.")
            codec = try {
                MediaCodec.createDecoderByType(mime)
            } catch (failure: Throwable) {
                throw AudioImportException("This audio format or codec is not supported.", failure)
            }
            codec.configure(track, null, null, 0)
            codec.start()
            return AndroidAudioDecoderSession(extractor, codec, track.toDecoderFormat())
        } catch (failure: Throwable) {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
            throw failure
        }
    }
}

private class AndroidAudioDecoderSession(
    private val extractor: MediaExtractor,
    private val codec: MediaCodec,
    override val initialFormat: DecoderFormat
) : AudioDecoderSession {
    private val info = MediaCodec.BufferInfo()
    private var inputDone = false
    private var outputDone = false

    override fun nextEvent(): DecodeEvent {
        if (outputDone) return DecodeEvent.End
        if (!inputDone) {
            val inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
            if (inputIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputIndex) ?: throw AudioImportException("Audio decoder input failed.")
                inputBuffer.clear()
                val size = extractor.readSampleData(inputBuffer, 0)
                if (size < 0) {
                    codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    inputDone = true
                } else {
                    codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, extractor.sampleFlags)
                    extractor.advance()
                }
            }
        }
        return when (val index = codec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)) {
            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> DecodeEvent.FormatChanged(codec.outputFormat.toDecoderFormat(initialFormat))
            MediaCodec.INFO_TRY_AGAIN_LATER -> DecodeEvent.TryAgain
            else -> if (index >= 0) {
                val end = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                    val output = codec.getOutputBuffer(index)
                    if (output == null) {
                        codec.releaseOutputBuffer(index, false)
                        throw AudioImportException("Audio decoder output failed.")
                    }
                    try {
                        output.position(info.offset)
                        output.limit(info.offset + info.size)
                        val event = DecodeEvent.Pcm(output.slice(), end) { codec.releaseOutputBuffer(index, false) }
                        if (end) outputDone = true
                        event
                    } catch (failure: Throwable) {
                        runCatching { codec.releaseOutputBuffer(index, false) }
                        throw failure
                    }
                } else if (end) {
                    codec.releaseOutputBuffer(index, false)
                    outputDone = true
                    DecodeEvent.End
                } else {
                    codec.releaseOutputBuffer(index, false)
                    DecodeEvent.TryAgain
                }
            } else DecodeEvent.TryAgain
        }
    }

    override fun close() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
        extractor.release()
    }

    private companion object { const val CODEC_TIMEOUT_US = 10_000L }
}

private fun MediaFormat.toDecoderFormat(fallback: DecoderFormat? = null): DecoderFormat {
    val encoding = if (containsKey(MediaFormat.KEY_PCM_ENCODING)) when (getInteger(MediaFormat.KEY_PCM_ENCODING)) {
        AudioFormat.ENCODING_PCM_16BIT -> DecodedEncoding.PCM_16
        AudioFormat.ENCODING_PCM_FLOAT -> DecodedEncoding.PCM_FLOAT
        else -> throw AudioImportException("The decoded audio encoding is not supported.")
    } else fallback?.encoding ?: DecodedEncoding.PCM_16
    return DecoderFormat(
        sampleRate = if (containsKey(MediaFormat.KEY_SAMPLE_RATE)) getInteger(MediaFormat.KEY_SAMPLE_RATE) else fallback?.sampleRate ?: 0,
        channels = if (containsKey(MediaFormat.KEY_CHANNEL_COUNT)) getInteger(MediaFormat.KEY_CHANNEL_COUNT) else fallback?.channels ?: 0,
        durationUs = if (containsKey(MediaFormat.KEY_DURATION)) getLong(MediaFormat.KEY_DURATION) else fallback?.durationUs,
        encoding = encoding
    )
}
package com.ottomusic.sketch.data

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import com.ottomusic.sketch.core.AacGaplessMetadata
import com.ottomusic.sketch.core.AacPrimingProbe
import com.ottomusic.sketch.core.PcmWavReader
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean

/** AAC-LC in an MPEG-4 container using the device's platform codec and muxer. */
class PlatformAacEncoder : AacEncoder {
    override fun isAvailable(sampleRate: Int, channels: Int, outputDirectory: File): Boolean {
        if (sampleRate !in 8_000..48_000 || channels !in 1..2) return false
        val format = audioFormat(sampleRate, channels, if (channels == 1) 96_000 else 128_000)
        val codecName = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format)
        }.getOrNull() ?: return false

        val probe = ExportActivePaths.createTracked {
            File.createTempFile(".m4a-probe-", ".tmp", outputDirectory)
        }
        var muxer: MediaMuxer? = null
        return try {
            muxer = MediaMuxer(probe.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            true
        } catch (_: IllegalArgumentException) {
            false
        } catch (_: IllegalStateException) {
            false
        } catch (failure: Exception) {
            throw IOException("Could not probe AAC export support", failure)
        } finally {
            try { muxer?.release() } finally {
                try { Files.deleteIfExists(probe.toPath()) } finally { ExportActivePaths.finish(probe) }
            }
        }
    }

    override fun create(
        output: File,
        sampleRate: Int,
        channels: Int,
        bitRate: Int,
        cancelled: AtomicBoolean
    ): AacEncodingSession {
        if (sampleRate !in 8_000..48_000 || channels !in 1..2) throw UnsupportedAacExportException()
        val format = audioFormat(sampleRate, channels, bitRate)
        val name = MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format)
            ?: throw UnsupportedAacExportException()
        val key = PrimingKey(name, sampleRate, channels, bitRate)
        val priming = synchronized(primingCache) {
            primingCache[key] ?: measurePriming(output.parentFile ?: throw IOException("No AAC output folder."), key, cancelled)
                .also { primingCache[key] = it }
        }
        WavExporter.checkCancelled(cancelled)
        return createSession(output, name, sampleRate, channels, bitRate, cancelled, priming)
    }

    private fun createSession(
        output: File, name: String, sampleRate: Int, channels: Int, bitRate: Int,
        cancelled: AtomicBoolean, priming: Int
    ): Session {
        val format = audioFormat(sampleRate, channels, bitRate)
        val muxer = try {
            MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } catch (failure: Exception) {
            throw UnsupportedAacExportException().also { it.initCause(failure) }
        }
        var codec: MediaCodec? = null
        try {
            codec = MediaCodec.createByCodecName(name)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            return Session(codec, muxer, sampleRate, channels, bitRate, cancelled, output, priming)
        } catch (failure: Throwable) {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { muxer.release() }
            throw failure
        }
    }

    private fun measurePriming(directory: File, key: PrimingKey, cancelled: AtomicBoolean): Int {
        WavExporter.checkCancelled(cancelled)
        val encoded = ExportActivePaths.createTracked { File.createTempFile(".m4a-probe-", ".tmp", directory) }
        var decoded: File? = null
        try {
            val marker = AacPrimingProbe.marker()
            val pcm = AacPrimingProbe.pcm(marker, key.channels)
            createSession(encoded, key.name, key.rate, key.channels, key.bitRate, cancelled, 0).use { session ->
                session.queuePcm(pcm, pcm.size, 0L)
                session.finish()
            }
            val wav = ExportActivePaths.createTracked { File.createTempFile(".m4a-probe-", ".tmp", directory) }
            decoded = wav
            val cancellation = ImportCancellation().apply { cancelWithJob { !cancelled.get() } }
            AudioDecodePipeline(AndroidAudioDecoderFactory(), FilePcmWavSinkFactory()).decode(
                encoded, wav, "AAC priming probe", cancellation
            ) { _, _ -> WavExporter.checkCancelled(cancelled) }
            WavExporter.checkCancelled(cancelled)
            val output = PcmWavReader.readPcm16(wav)
            if (output.sampleRate != key.rate || output.channels != key.channels) throw IOException("AAC probe changed its audio format.")
            return AacPrimingProbe.delayFrames(marker, output.samples, key.channels)
        } catch (failure: Throwable) {
            WavExporter.checkCancelled(cancelled)
            throw failure
        } finally {
            runCatching { encoded.delete() }
            ExportActivePaths.finish(encoded)
            decoded?.let { file -> runCatching { file.delete() }; ExportActivePaths.finish(file) }
        }
    }

    private class Session(
        private val codec: MediaCodec,
        private val muxer: MediaMuxer,
        private val sampleRate: Int,
        private val channels: Int,
        private val bitRate: Int,
        private val cancelled: AtomicBoolean,
        private val outputFile: File,
        private val primingFrames: Int
    ) : AacEncodingSession {
        private val blockAlign = channels * 2
        private var muxerStarted = false
        private var track = -1
        private var inputEnded = false
        private var outputEnded = false
        private var closed = false
        private var endPresentationTimeUs = 0L
        private var queuedFrames = 0L
        private var contentFrames = 0L

        override fun queuePcm(bytes: ByteArray, length: Int, presentationTimeUs: Long) {
            require(length > 0 && length <= bytes.size && length % blockAlign == 0)
            var offset = 0
            while (offset < length) {
                checkCancelled()
                val index = inputBuffer()
                val input = codec.getInputBuffer(index) ?: throw IOException("AAC encoder returned no input buffer")
                val count = minOf(length - offset, input.capacity()) / blockAlign * blockAlign
                if (count <= 0) throw IOException("AAC encoder input buffer is too small")
                input.clear()
                input.put(bytes, offset, count)
                val elapsedFrames = offset / blockAlign
                codec.queueInputBuffer(
                    index, 0, count,
                    presentationTimeUs + elapsedFrames * 1_000_000L / sampleRate,
                    0
                )
                endPresentationTimeUs = presentationTimeUs + (elapsedFrames + count / blockAlign) * 1_000_000L / sampleRate
                offset += count
                queuedFrames = Math.addExact(queuedFrames, count / blockAlign.toLong())
                drain(waitForOutput = false)
            }
        }

        override fun finish() {
            checkCancelled()
            if (!inputEnded) {
                contentFrames = queuedFrames
                // Flush the encoder's lookahead so the end of the selected source is retained.
                var remaining = primingFrames
                val silence = ByteArray(minOf(primingFrames, 2048) * blockAlign)
                while (remaining > 0) {
                    val count = minOf(remaining, silence.size / blockAlign)
                    queuePcm(silence, count * blockAlign, queuedFrames * 1_000_000L / sampleRate)
                    remaining -= count
                }
                val index = inputBuffer()
                codec.queueInputBuffer(index, 0, 0, endPresentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                inputEnded = true
            }
            while (!outputEnded) {
                checkCancelled()
                drain(waitForOutput = true)
            }
            if (!muxerStarted) throw IOException("AAC encoder produced no MPEG-4 audio track")
        }

        private fun inputBuffer(): Int {
            while (true) {
                checkCancelled()
                val index = codec.dequeueInputBuffer(TIMEOUT_US)
                if (index >= 0) return index
                drain(waitForOutput = false)
            }
        }

        private fun drain(waitForOutput: Boolean) {
            val info = MediaCodec.BufferInfo()
            while (true) {
                checkCancelled()
                val index = codec.dequeueOutputBuffer(info, if (waitForOutput) TIMEOUT_US else 0)
                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!waitForOutput) return
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (muxerStarted) throw IOException("AAC encoder changed output format twice")
                        // Write raw AAC consistently across codecs; gapless timing is added after
                        // encoding using our measured delay, never a vendor-specific default.
                        val rawFormat = MediaFormat.createAudioFormat(MIME_AAC, sampleRate, channels).apply {
                            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                            val config = codec.outputFormat.getByteBuffer("csd-0")
                                ?: throw IOException("AAC encoder did not expose its codec configuration.")
                            setByteBuffer("csd-0", config.duplicate())
                        }
                        track = muxer.addTrack(rawFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    index >= 0 -> {
                        val output = codec.getOutputBuffer(index)
                        if (info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            if (!muxerStarted || output == null) throw IOException("AAC encoder produced invalid output")
                            output.position(info.offset)
                            output.limit(info.offset + info.size)
                            muxer.writeSampleData(track, output, info)
                        }
                        codec.releaseOutputBuffer(index, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputEnded = true
                            return
                        }
                    }
                }
            }
        }

        private fun checkCancelled() {
            WavExporter.checkCancelled(cancelled)
        }

        override fun close() {
            if (closed) return
            closed = true
            try {
                if (muxerStarted) muxer.stop()
            } finally {
                try { muxer.release() } finally {
                    try { codec.stop() } catch (_: Exception) { }
                    codec.release()
                }
            }
            if (outputEnded && inputEnded && contentFrames > 0L && primingFrames > 0) {
                checkCancelled()
                AacGaplessMetadata.apply(outputFile, primingFrames, contentFrames, sampleRate)
            }
        }

        private companion object {
            const val TIMEOUT_US = 10_000L
        }
    }

    private companion object {
        const val MIME_AAC = "audio/mp4a-latm"
        data class PrimingKey(val name: String, val rate: Int, val channels: Int, val bitRate: Int)
        val primingCache = mutableMapOf<PrimingKey, Int>()

        fun audioFormat(sampleRate: Int, channels: Int, bitRate: Int): MediaFormat =
            MediaFormat.createAudioFormat(MIME_AAC, sampleRate, channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            }
    }
}

package com.ottomusic.sketch.core

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Adds a single audio edit without moving media bytes or rewriting chunk offsets. */
internal object AacGaplessMetadata {
    private const val MAX_MOOV_BYTES = 4 * 1024 * 1024
    private data class Box(val offset: Int, val size: Int, val type: String) {
        val end: Int get() = offset + size
    }

    fun apply(file: File, delayFrames: Int, contentFrames: Long, sampleRate: Int) {
        require(delayFrames >= 0 && contentFrames > 0L && sampleRate > 0)
        if (delayFrames == 0) return
        RandomAccessFile(file, "rw").use { output ->
            var offset = 0L
            var moovOffset = -1L
            var moov: ByteArray? = null
            var moovHeaderBytes = 8
            val fileLength = output.length()
            while (offset < fileLength) {
                if (fileLength - offset < 8L) throw IOException("Incomplete M4A box header.")
                output.seek(offset)
                val smallSize = output.readInt().toLong() and 0xffff_ffffL
                val tag = ByteArray(4).also(output::readFully).toString(Charsets.US_ASCII)
                val headerBytes = if (smallSize == 1L) 16 else 8
                if (fileLength - offset < headerBytes) throw IOException("Incomplete M4A extended box header.")
                val size = if (smallSize == 1L) output.readLong() else smallSize
                if (size < headerBytes || size > fileLength - offset) throw IOException("Unsupported M4A box size.")
                if (tag == "moov") {
                    if (moov != null || size > MAX_MOOV_BYTES) throw IOException("Unsupported M4A movie metadata.")
                    moovOffset = offset
                    moovHeaderBytes = headerBytes
                    output.seek(offset)
                    moov = ByteArray(size.toInt()).also(output::readFully)
                }
                offset += size
            }
            val source = moov ?: throw IOException("M4A movie metadata is missing.")
            val replacement = rewriteMovie(source, moovHeaderBytes, delayFrames, contentFrames, sampleRate)
            // Appending preserves every stco/co64 media offset, including fast-start layouts.
            output.seek(fileLength)
            output.write(replacement)
            output.fd.sync()
            output.seek(moovOffset + 4L)
            output.write("free".toByteArray(Charsets.US_ASCII))
            output.fd.sync()
        }
    }

    private fun rewriteMovie(bytes: ByteArray, headerBytes: Int, delayFrames: Int, frames: Long, rate: Int): ByteArray {
        val children = boxes(bytes, headerBytes, bytes.size)
        val movieHeader = children.singleOrNull { it.type == "mvhd" }
            ?: throw IOException("M4A movie header is missing.")
        val tracks = children.filter { it.type == "trak" }
        if (tracks.size != 1) throw IOException("Gapless export requires one audio track.")
        // Use one audio timebase throughout. Older Android extractors interpret edit media_time
        // in the movie scale instead of the media scale; matching them is also valid ISO BMFF.
        val duration = frames
        setTimescale(bytes, movieHeader, rate)
        setDuration(bytes, movieHeader, duration, movie = true)
        val track = tracks.single()
        val trackChildren = boxes(bytes, track.offset + 8, track.end)
        val trackHeader = trackChildren.singleOrNull { it.type == "tkhd" }
            ?: throw IOException("M4A track header is missing.")
        val media = trackChildren.singleOrNull { it.type == "mdia" }
            ?: throw IOException("M4A media metadata is missing.")
        val mediaChildren = boxes(bytes, media.offset + 8, media.end)
        val mediaHeader = mediaChildren.singleOrNull { it.type == "mdhd" }
            ?: throw IOException("M4A media header is missing.")
        val handler = mediaChildren.singleOrNull { it.type == "hdlr" }
            ?: throw IOException("M4A media handler is missing.")
        if (handler.size < 20 || tag(bytes, handler.offset + 16) != "soun") throw IOException("M4A track is not audio.")
        if (timescale(bytes, mediaHeader) != rate.toLong()) throw IOException("Unexpected M4A audio timescale.")
        setDuration(bytes, trackHeader, duration, movie = false)
        val edit = editList(duration, delayFrames)
        val trackBody = ByteArrayOutputStream()
        for (child in trackChildren) {
            if (child.type != "edts") trackBody.write(bytes, child.offset, child.size)
        }
        // Android 8 resolves elst immediately and needs mdia's rate/duration first.
        // Later extractors defer resolution, so this valid ordering supports both.
        trackBody.write(edit)
        val rewrittenTrack = box("trak", trackBody.toByteArray())
        val movieBody = ByteArrayOutputStream()
        for (child in children) {
            if (child == track) movieBody.write(rewrittenTrack)
            else movieBody.write(bytes, child.offset, child.size)
        }
        return box("moov", movieBody.toByteArray()).also {
            if (it.size > MAX_MOOV_BYTES) throw IOException("M4A movie metadata exceeds its limit.")
        }
    }

    private fun editList(duration: Long, delay: Int): ByteArray {
        if (duration !in 0L..0xffff_ffffL) throw IOException("M4A selection is too long for a gapless edit.")
        val payload = ByteArrayOutputStream()
        DataOutputStream(payload).use { out ->
            // Version 0 is understood by the oldest supported Android extractor.
            out.writeInt(0)
            out.writeInt(1)
            out.writeInt(duration.toInt())
            out.writeInt(delay)
            out.writeShort(1)
            out.writeShort(0)
        }
        return box("edts", box("elst", payload.toByteArray()))
    }

    private fun timescale(bytes: ByteArray, header: Box): Long {
        if (header.size < 12) throw IOException("Incomplete M4A timing header.")
        val version = bytes[header.offset + 8].toInt() and 0xff
        val at = when (version) { 0 -> header.offset + 20; 1 -> header.offset + 28; else -> throw IOException("Unsupported M4A header version.") }
        if (at + 4 > header.end) throw IOException("Incomplete M4A timescale.")
        return u32(bytes, at).also { if (it == 0L) throw IOException("Invalid M4A timescale.") }
    }

    private fun setTimescale(bytes: ByteArray, header: Box, rate: Int) {
        timescale(bytes, header) // Validate the existing timing header before changing it.
        val at = header.offset + if (bytes[header.offset + 8].toInt() == 1) 28 else 20
        ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).putInt(at, rate)
    }

    private fun setDuration(bytes: ByteArray, header: Box, duration: Long, movie: Boolean) {
        if (header.size < 12) throw IOException("Incomplete M4A duration header.")
        val version = bytes[header.offset + 8].toInt() and 0xff
        val at = when (version) {
            0 -> header.offset + if (movie) 24 else 28
            1 -> header.offset + if (movie) 32 else 36
            else -> throw IOException("Unsupported M4A header version.")
        }
        val width = if (version == 1) 8 else 4
        if (at + width > header.end || duration < 0L || (width == 4 && duration > 0xffff_ffffL)) {
            throw IOException("M4A duration is outside its supported range.")
        }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        if (width == 8) buffer.putLong(at, duration) else buffer.putInt(at, duration.toInt())
    }

    private fun boxes(bytes: ByteArray, start: Int, end: Int): List<Box> {
        val result = mutableListOf<Box>()
        var at = start
        while (at < end) {
            if (end - at < 8) throw IOException("Incomplete M4A child box.")
            val size = u32(bytes, at)
            if (size < 8L || size > end - at) throw IOException("Invalid M4A child box.")
            result += Box(at, size.toInt(), tag(bytes, at + 4))
            at += size.toInt()
        }
        return result
    }

    private fun tag(bytes: ByteArray, at: Int) = String(bytes, at, 4, Charsets.US_ASCII)
    private fun u32(bytes: ByteArray, at: Int) = ByteBuffer.wrap(bytes, at, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xffff_ffffL
    private fun box(type: String, payload: ByteArray): ByteArray = ByteArrayOutputStream().also { buffer ->
        DataOutputStream(buffer).use { out ->
            out.writeInt(Math.addExact(payload.size, 8))
            out.write(type.toByteArray(Charsets.US_ASCII))
            out.write(payload)
        }
    }.toByteArray()
}
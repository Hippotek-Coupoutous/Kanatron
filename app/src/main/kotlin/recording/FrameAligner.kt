package com.kanatron.app.recording

/** Carries incomplete PCM frames across reads without allocating per read. */
internal class FrameAligner(private val frameBytes: Int) {
    private val carry = ByteArray(frameBytes.also { require(it > 0) })
    private var carriedBytes = 0

    /** Copies complete frames from [input] into [output], retaining any trailing partial frame. */
    fun align(input: ByteArray, count: Int, output: ByteArray): Int {
        require(count in 0..input.size)
        require(output.size >= count + carriedBytes)
        var inputOffset = 0
        var outputOffset = 0

        if (carriedBytes > 0) {
            val needed = frameBytes - carriedBytes
            val copied = minOf(needed, count)
            input.copyInto(carry, carriedBytes, 0, copied)
            carriedBytes += copied
            inputOffset += copied
            if (carriedBytes == frameBytes) {
                carry.copyInto(output, outputOffset)
                outputOffset += frameBytes
                carriedBytes = 0
            } else {
                return 0
            }
        }

        val remaining = count - inputOffset
        val completeBytes = remaining - remaining % frameBytes
        if (completeBytes > 0) {
            input.copyInto(output, outputOffset, inputOffset, inputOffset + completeBytes)
            outputOffset += completeBytes
            inputOffset += completeBytes
        }
        carriedBytes = count - inputOffset
        if (carriedBytes > 0) input.copyInto(carry, 0, inputOffset, count)
        return outputOffset
    }

    fun clear() {
        carriedBytes = 0
    }
}
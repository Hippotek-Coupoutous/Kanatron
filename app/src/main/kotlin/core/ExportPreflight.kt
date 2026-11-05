package com.ottomusic.sketch.core

object ExportPreflight {
    const val STORAGE_RESERVE_BYTES = 10L * 1024 * 1024

    fun requiredBytes(payloadBytes: Long): Long = maxOf(
        Math.addExact(payloadBytes, WavFormat.HEADER_BYTES.toLong()),
        STORAGE_RESERVE_BYTES
    )

    fun requiredOutputBytes(outputBytes: Long): Long = maxOf(outputBytes, STORAGE_RESERVE_BYTES)

    fun hasEnoughSpace(availableBytes: Long, payloadBytes: Long): Boolean =
        availableBytes >= requiredBytes(payloadBytes)

    fun hasEnoughOutputSpace(availableBytes: Long, outputBytes: Long): Boolean =
        availableBytes >= requiredOutputBytes(outputBytes)
}
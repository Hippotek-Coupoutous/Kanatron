package com.kanatron.app.core

object ExportPreflight {
    const val STORAGE_RESERVE_BYTES = 10L * 1024 * 1024

    fun requiredOutputBytes(outputBytes: Long): Long = Math.addExact(outputBytes, STORAGE_RESERVE_BYTES)

    fun hasEnoughOutputSpace(availableBytes: Long, outputBytes: Long): Boolean =
        availableBytes >= requiredOutputBytes(outputBytes)
}
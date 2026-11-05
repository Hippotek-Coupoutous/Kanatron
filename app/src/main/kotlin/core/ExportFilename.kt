package com.ottomusic.sketch.core

object ExportFilename {
    private val unsupported = Regex("[^A-Za-z0-9 _().-]")

    fun wav(name: String, fallback: String): String {
        fun clean(value: String): String {
            val base = value.trim().replace(unsupported, "_").trimEnd('.', ' ')
            return if (base.endsWith(".wav", ignoreCase = true)) base.dropLast(4) else base
        }
        return "${clean(name).ifBlank { clean(fallback).ifBlank { "Recording" } }}.wav"
    }
}

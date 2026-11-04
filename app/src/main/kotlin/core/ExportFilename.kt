package com.ottomusic.sketch.core

object ExportFilename {
    private val unsupported = Regex("[^A-Za-z0-9 _().-]")

    fun wav(name: String, fallback: String): String = withExtension(name, fallback, "wav")

    fun m4a(name: String, fallback: String): String = withExtension(name, fallback, "m4a")

    fun withExtension(name: String, fallback: String, extension: String): String {
        require(extension.matches(Regex("[A-Za-z0-9]+")))
        fun clean(value: String): String {
            val base = value.trim().replace(unsupported, "_").trimEnd('.', ' ')
            return when {
                base.endsWith(".wav", ignoreCase = true) -> base.dropLast(4)
                base.endsWith(".m4a", ignoreCase = true) -> base.dropLast(4)
                else -> base
            }
        }
        return "${clean(name).ifBlank { clean(fallback).ifBlank { "Recording" } }}.$extension"
    }
}
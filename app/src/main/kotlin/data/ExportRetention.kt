package com.kanatron.app.data

import com.kanatron.app.core.PROJECT_ID
import java.io.File
import java.io.IOException

/** Bounded exports kept for a fixed share grace period. */
internal class ExportRetention(
    root: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val maxBytes: Long = MAX_BYTES
) {
    private val exports = File(root, "exports")

    init { require(maxBytes >= 0L) }

    fun write(
        projectId: String,
        preferredName: String,
        expectedBytes: Long,
        writer: (File) -> Unit
    ): File =
        synchronized(LOCK) {
            require(PROJECT_ID.matches(projectId)) { "Invalid project id" }
            require(expectedBytes >= 0L) { "Invalid export size" }
            if (!exports.isDirectory && !exports.mkdirs()) throw IOException("Could not create the exports folder.")
            cleanup(now())
            val target = uniqueTarget(preferredName)
            makeRoom(expectedBytes, now())
            try {
                writer(target)
                if (!target.isFile) throw IOException("Export did not produce a file.")
                // The committing move stamps the mtime; nothing touches the file afterwards.
                makeRoom(0L, now())
                target
            } catch (failure: Throwable) {
                target.delete()
                throw failure
            }
        }

    fun cleanup() = synchronized(LOCK) { cleanup(now()) }

    private fun cleanup(time: Long) {
        exportFiles().forEach { file -> if (canRetire(file, time)) remove(file) }
    }

    private fun makeRoom(additionalBytes: Long, time: Long) {
        if (additionalBytes > maxBytes) throw IOException("This export exceeds the storage limit. Try a shorter selection.")
        var used = totalBytes()
        if (fits(used, additionalBytes)) return
        for (file in exportFiles().filter { canRetire(it, time) }.sortedBy(File::lastModified)) {
            val size = file.length()
            if (remove(file)) used = (used - size).coerceAtLeast(0L)
            if (fits(used, additionalBytes)) return
        }
        throw IOException("Export storage is full: recent shares are kept for seven days. Try a shorter selection, or share again once older shares expire.")
    }

    private fun canRetire(file: File, time: Long): Boolean = time >= plus(file.lastModified(), SHARE_GRACE_MS)

    private fun uniqueTarget(name: String): File {
        require(name == File(name).name) { "Invalid export filename" }
        val extension = name.substringAfterLast('.', "").lowercase()
        require(extension == "wav") { "Unsupported export format" }
        val stem = name.dropLast(extension.length + 1).ifBlank { "Recording" }
        var suffix = 1
        while (true) {
            // Truncate before the suffix so a capped stem can never make dedupe loop forever.
            val tag = if (suffix == 1) "" else " ($suffix)"
            val candidate = File(exports, stem.take(MAX_NAME - extension.length - 1 - tag.length) + tag + ".$extension")
            if (!candidate.exists()) return candidate
            suffix = Math.addExact(suffix, 1)
        }
    }

    private fun exportFiles(): List<File> = exports.listFiles().orEmpty().filter {
        it.isFile && it.extension.equals("wav", true)
    }

    private fun totalBytes(): Long = try {
        exportFiles().fold(0L) { total, file -> Math.addExact(total, file.length()) }
    } catch (_: ArithmeticException) {
        throw IOException("Export storage usage exceeds the supported range.")
    }

    private fun fits(used: Long, extra: Long): Boolean =
        runCatching { Math.addExact(used, extra) <= maxBytes }.getOrDefault(false)

    private fun remove(file: File): Boolean = file.delete() || !file.exists()

    private fun plus(value: Long, amount: Long): Long = runCatching { Math.addExact(value, amount) }.getOrDefault(Long.MAX_VALUE)

    private companion object {
        val LOCK = Any()
        const val MAX_NAME = 240
        const val SHARE_GRACE_MS = 7L * 24 * 60 * 60 * 1000
        const val MAX_BYTES = 512L * 1024 * 1024
    }
}

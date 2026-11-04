package com.ottomusic.sketch.data

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Bounded, ID-owned exports with a persisted share grace period. */
internal class ExportRetention(
    root: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val maxBytes: Long = MAX_BYTES
) {
    private val exports = File(root, "exports")
    private val metadata = File(root, "export-metadata")
    private val projects = File(root, "projects")

    init { require(maxBytes >= 0L) }

    fun write(
        projectId: String,
        preferredName: String,
        expectedBytes: Long,
        forSharing: Boolean = false,
        writer: (File) -> Unit
    ): File =
        synchronized(LOCK) {
            require(PROJECT_ID.matches(projectId)) { "Invalid project id" }
            require(expectedBytes >= 0L) { "Invalid export size" }
            if (!exports.isDirectory && !exports.mkdirs()) throw IOException("Could not create the exports folder.")
            cleanup(now())
            val target = uniqueTarget(preferredName)
            makeRoom(expectedBytes, now())
            ExportActivePaths.begin(target)
            try {
                writer(target)
                if (!target.isFile) throw IOException("Export did not produce a file.")
                val completedAt = now()
                save(Entry(target.name, projectId, target.extension.lowercase(), completedAt,
                    if (forSharing) completedAt else 0L, 0L))
                makeRoom(0L, now())
                target
            } catch (failure: Throwable) {
                target.delete()
                metadataFile(target.name).delete()
                throw failure
            } finally {
                ExportActivePaths.finish(target)
            }
        }

    fun shared(file: File) = synchronized(LOCK) {
        val target = ownedFile(file) ?: return@synchronized
        val entry = load(target.name) ?: return@synchronized
        save(entry.copy(lastSharedAt = now()))
    }

    fun projectDeleted(projectId: String) = synchronized(LOCK) {
        val time = now()
        metadata.listFiles().orEmpty().forEach { file ->
            val entry = read(file) ?: return@forEach
            if (entry.projectId == projectId) save(entry.copy(retiredAt = time))
        }
        cleanup(time)
    }

    fun cleanup() = synchronized(LOCK) { cleanup(now()) }

    private fun cleanup(time: Long) {
        val projectIds = projects.listFiles { file -> file.isFile && file.extension == "json" }
            .orEmpty().mapTo(mutableSetOf()) { it.nameWithoutExtension }
        metadata.listFiles().orEmpty().forEach { file ->
            val entry = read(file) ?: return@forEach
            if (!File(exports, entry.fileName).isFile) {
                file.delete()
                return@forEach
            }
            if (entry.projectId !in projectIds && entry.retiredAt == 0L) {
                runCatching { save(entry.copy(retiredAt = time)) }
            }
        }
        val files = exportFiles()
        val entries = files.associateWith { load(it.name) }
        val latest = latestFiles(entries)

        files.forEach { file ->
            val entry = entries[file]
            if (canRetire(file, entry, file in latest, time)) remove(file)
        }
    }

    private fun makeRoom(additionalBytes: Long, time: Long) {
        if (additionalBytes > maxBytes) throw IOException("This export exceeds the storage limit. Try a shorter selection.")
        var used = totalBytes()
        if (fits(used, additionalBytes)) return

        val entries = exportFiles().associateWith { load(it.name) }
        val latest = latestFiles(entries)
        val candidates = entries.keys.filter { file -> canRetire(file, entries[file], file in latest, time) }
            .sortedBy { file -> entries[file]?.completedAt ?: file.lastModified() }
        for (file in candidates) {
            val size = file.length()
            if (remove(file)) used = (used - size).coerceAtLeast(0L)
            if (fits(used, additionalBytes)) return
        }
        throw IOException("Export storage is full. Delete an unneeded recording or try a shorter selection. Recent shares are kept for seven days.")
    }

    private fun canRetire(file: File, entry: Entry?, isLatest: Boolean, time: Long): Boolean {
        if (file.absolutePath in ExportActivePaths.snapshot()) return false
        if (entry == null) return time >= plus(file.lastModified(), LEGACY_MAX_AGE_MS)
        if (entry.retiredAt != 0L) {
            return entry.lastSharedAt == 0L || time >= plus(entry.lastSharedAt, SHARE_GRACE_MS)
        }
        if (isLatest) return false
        val protectedUntil = maxOf(plus(entry.completedAt, SHARE_GRACE_MS),
            if (entry.lastSharedAt == 0L) 0L else plus(entry.lastSharedAt, SHARE_GRACE_MS))
        return time >= protectedUntil
    }

    private fun uniqueTarget(name: String): File {
        require(name == File(name).name && name.length <= 240) { "Invalid export filename" }
        val extension = name.substringAfterLast('.', "").lowercase()
        require(extension == "wav" || extension == "m4a") { "Unsupported export format" }
        val stem = name.dropLast(extension.length + 1).ifBlank { "Recording" }
        var suffix = 1
        while (true) {
            val candidate = File(exports, if (suffix == 1) "$stem.$extension" else "$stem ($suffix).$extension")
            if (!candidate.exists()) return candidate
            suffix = Math.addExact(suffix, 1)
        }
    }

    private fun exportFiles(): List<File> = exports.listFiles().orEmpty().filter {
        it.isFile && (it.extension.equals("wav", true) || it.extension.equals("m4a", true))
    }

    private fun latestFiles(entries: Map<File, Entry?>): Set<File> = entries.mapNotNull { (file, entry) ->
        if (entry == null || entry.retiredAt != 0L) null else file to entry
    }.groupBy { (_, entry) -> entry.projectId to entry.extension }
        .mapValues { (_, owned) -> owned.maxWithOrNull(compareBy<Pair<File, Entry>> { it.second.completedAt }.thenBy { it.first.name })?.first }
            .values.filterNotNull().toSet()

    private fun totalBytes(): Long = try {
        exportFiles().fold(0L) { total, file -> Math.addExact(total, file.length()) }
    } catch (_: ArithmeticException) {
        throw IOException("Export storage usage exceeds the supported range.")
    }

    private fun fits(used: Long, extra: Long): Boolean =
        runCatching { Math.addExact(used, extra) <= maxBytes }.getOrDefault(false)

    private fun remove(file: File): Boolean {
        if (file.absolutePath in ExportActivePaths.snapshot()) return false
        if (!file.delete() && file.exists()) return false
        metadataFile(file.name).delete()
        return true
    }

    private fun ownedFile(file: File): File? = runCatching {
        file.canonicalFile.takeIf { it.parentFile == exports.canonicalFile && it.isFile }
    }.getOrNull()

    private fun save(entry: Entry) {
        if (!metadata.isDirectory && !metadata.mkdirs()) throw IOException("Could not save export ownership.")
        val target = metadataFile(entry.fileName)
        val temp = ExportActivePaths.createTracked {
            Files.createTempFile(metadata.toPath(), ".export-owner-", ".tmp").toFile()
        }
        try {
            FileOutputStream(temp).use { output ->
                output.write(listOf(entry.fileName, entry.projectId, entry.extension, entry.completedAt, entry.lastSharedAt, entry.retiredAt)
                    .joinToString("\n").toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            try { Files.deleteIfExists(temp.toPath()) } finally { ExportActivePaths.finish(temp) }
        }
    }

    private fun load(name: String): Entry? = read(metadataFile(name))?.takeIf { it.fileName == name }

    private fun read(file: File): Entry? = runCatching {
        val fields = file.readText(Charsets.UTF_8).split('\n')
        require(fields.size == 6)
        val entry = Entry(fields[0], fields[1], fields[2], fields[3].toLong(), fields[4].toLong(), fields[5].toLong())
        require(PROJECT_ID.matches(entry.projectId) && (entry.extension == "wav" || entry.extension == "m4a"))
        require(entry.fileName == File(entry.fileName).name && entry.fileName.length <= 240)
        require(entry.fileName.substringAfterLast('.', "").lowercase() == entry.extension)
        entry
    }.getOrNull()

    private fun metadataFile(name: String): File = File(metadata, sha256(name) + ".owner")

    private fun plus(value: Long, amount: Long): Long = runCatching { Math.addExact(value, amount) }.getOrDefault(Long.MAX_VALUE)

    private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private data class Entry(
        val fileName: String,
        val projectId: String,
        val extension: String,
        val completedAt: Long,
        val lastSharedAt: Long,
        val retiredAt: Long
    )

    private companion object {
        val LOCK = Any()
        val PROJECT_ID = Regex("[A-Za-z0-9-]{1,80}")
        const val SHARE_GRACE_MS = 7L * 24 * 60 * 60 * 1000
        const val LEGACY_MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000
        const val MAX_BYTES = 512L * 1024 * 1024
    }
}
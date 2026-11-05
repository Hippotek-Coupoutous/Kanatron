package com.kanatron.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.OsConstants
import com.kanatron.app.platform.PlatformPorts
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

internal enum class ImportStage { COPYING, DECODING, COMPLETE }

internal data class ImportProgress(
    val stage: ImportStage,
    val completedBytes: Long,
    val totalBytes: Long?
)

internal class ImportCancellation {
    private val cancelled = AtomicBoolean(false)
    @Volatile private var jobIsActive: (() -> Boolean)? = null
    fun cancel() = cancelled.set(true)
    fun cancelWithJob(active: () -> Boolean) { jobIsActive = active }
    fun check() {
        if (cancelled.get() || jobIsActive?.invoke() == false) throw ImportCancelledException()
    }
}

internal class ImportCancelledException : IOException("Import cancelled")

internal class AudioImportException(message: String, cause: Throwable? = null) : IOException(message, cause)

internal data class ImportedAudio(
    val sampleRate: Int,
    val channels: Int,
    val totalFrames: Long,
    val sourceName: String
)

/** Stages SAF input privately, then decodes it to an immutable-in-practice PCM16 WAV. */
internal class AudioImporter(private val context: Context) {
    fun import(
        uri: Uri,
        output: File,
        cancellation: ImportCancellation,
        onProgress: (ImportProgress) -> Unit
    ): ImportedAudio {
        ImportActivePaths.beginOutput(output)
        var stagedFile: File? = null
        var completed = false
        try {
            val staging = File(context.filesDir, "import-staging").apply { mkdirs() }
            val outputParent = output.parentFile ?: throw AudioImportException("Could not prepare private storage for import.")
            if (!staging.isDirectory || (!outputParent.isDirectory && !outputParent.mkdirs())) {
                throw AudioImportException("Could not prepare private storage for import.")
            }
            val sourceName = displayName(uri)
            val sourceSize = contentSize(uri)
            checkFreeSpace(sourceSize ?: 0L)
            val staged = File.createTempFile("source-", ".bin", staging)
            stagedFile = staged
            ImportActivePaths.beginStaging(staged)
            copyToPrivate(uri, staged, sourceSize, cancellation) { copied, total ->
                onProgress(ImportProgress(ImportStage.COPYING, copied, total))
            }
            val result = decode(staged, output, sourceName, cancellation) { written, expected ->
                onProgress(ImportProgress(ImportStage.DECODING, written, expected))
            }
            completed = true
            onProgress(ImportProgress(ImportStage.COMPLETE, result.totalFrames, result.totalFrames))
            return result
        } catch (failure: Throwable) {
            if (failure is AudioImportException) throw failure
            if (isOutOfDiskSpace(failure)) throw AudioImportException("Not enough storage to import this audio file.", failure)
            throw failure
        } finally {
            stagedFile?.delete()
            if (!completed) output.delete()
            ImportActivePaths.importerFinished(output, stagedFile, completed)
        }
    }

    private fun copyToPrivate(
        uri: Uri,
        destination: File,
        expectedBytes: Long?,
        cancellation: ImportCancellation,
        progress: (Long, Long?) -> Unit
    ) {
        val input = runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
            ?: throw AudioImportException("Could not read this audio file.")
        input.use { source ->
            FileOutputStream(destination).use { target ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                var copied = 0L
                while (true) {
                    cancellation.check()
                    val count = source.read(buffer)
                    if (count < 0) break
                    try {
                        target.write(buffer, 0, count)
                    } catch (failure: IOException) {
                        if (isOutOfDiskSpace(failure)) throw AudioImportException("Not enough storage to stage this audio file.", failure)
                        throw failure
                    }
                    copied += count
                    progress(copied, expectedBytes)
                }
                target.fd.sync()
                if (copied == 0L) throw AudioImportException("This audio file is empty or unreadable.")
            }
        }
    }

    private fun decode(
        source: File,
        output: File,
        sourceName: String,
        cancellation: ImportCancellation,
        progress: (Long, Long?) -> Unit
    ): ImportedAudio {
        output.parentFile?.mkdirs()
        WavFileImporter.importIfWav(source, output, sourceName, cancellation, progress, ::checkFreeSpace)?.let { return it }
        return AudioDecodePipeline(AndroidAudioDecoderFactory(), FilePcmWavSinkFactory(), ::checkFreeSpace)
            .decode(source, output, sourceName, cancellation, progress)
    }

    private fun displayName(uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Imported audio"

    private fun contentSize(uri: Uri): Long? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0).takeIf { it >= 0L } else null
        }
    }.getOrNull()

    private fun checkFreeSpace(requiredBytes: Long) {
        val free = PlatformPorts.availableBytes(context.filesDir)
        if (requiredBytes > 0L && free < requiredBytes) throw AudioImportException("Not enough free storage to import this audio file.")
    }

    internal companion object {
        const val COPY_BUFFER_BYTES = 64 * 1024
    }
}

/** True when any cause reports a full disk. errno is authoritative; the text check stays for wrapped messages. */
internal fun isOutOfDiskSpace(failure: Throwable): Boolean =
    generateSequence(failure) { it.cause }.any { cause ->
        cause is ErrnoException && cause.errno == OsConstants.ENOSPC ||
            cause.message?.contains("ENOSPC", ignoreCase = true) == true ||
            cause.message?.contains("no space", ignoreCase = true) == true
    }
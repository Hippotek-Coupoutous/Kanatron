package com.ottomusic.sketch.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.ottomusic.sketch.platform.PlatformPorts
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

internal enum class ImportStage { COPYING, DECODING, COMPLETE, CANCELLED, FAILED }

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

internal class ImportStateMachine {
    var stage: ImportStage = ImportStage.COPYING
        private set

    fun moveTo(next: ImportStage) {
        val allowed = when (stage) {
            ImportStage.COPYING -> setOf(ImportStage.DECODING, ImportStage.CANCELLED, ImportStage.FAILED)
            ImportStage.DECODING -> setOf(ImportStage.COMPLETE, ImportStage.CANCELLED, ImportStage.FAILED)
            else -> emptySet()
        }
        require(next in allowed) { "Invalid import transition: $stage -> $next" }
        stage = next
    }
}

internal class AudioImportException(message: String, cause: Throwable? = null, val errorCode: String? = null) : IOException(message, cause)

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
        val state = ImportStateMachine()
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
            state.moveTo(ImportStage.DECODING)
            val result = decode(staged, output, sourceName, cancellation) { written, expected ->
                onProgress(ImportProgress(ImportStage.DECODING, written, expected))
            }
            state.moveTo(ImportStage.COMPLETE)
            onProgress(ImportProgress(ImportStage.COMPLETE, result.totalFrames, result.totalFrames))
            return result
        } catch (cancelled: ImportCancelledException) {
            state.moveTo(ImportStage.CANCELLED)
            throw cancelled
        } catch (failure: Throwable) {
            if (state.stage == ImportStage.COPYING || state.stage == ImportStage.DECODING) state.moveTo(ImportStage.FAILED)
            if (failure is AudioImportException) throw failure
            if (isNoSpace(failure)) throw AudioImportException("Not enough storage to import this audio file.", failure)
            throw failure
        } finally {
            stagedFile?.delete()
            if (state.stage != ImportStage.COMPLETE) output.delete()
            ImportActivePaths.importerFinished(output, stagedFile, state.stage == ImportStage.COMPLETE)
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
                        if (isNoSpace(failure)) throw AudioImportException("Not enough storage to stage this audio file.", failure)
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

    private fun isNoSpace(failure: Throwable): Boolean = generateSequence(failure) { it.cause }
        .any { it.message?.contains("ENOSPC", ignoreCase = true) == true || it.message?.contains("no space", ignoreCase = true) == true }

    internal companion object {
        const val COPY_BUFFER_BYTES = 64 * 1024
        val activeDecodePaths: Set<String> get() = ImportActivePaths.activeDecodePaths()
        val activeStagingPaths: Set<String> get() = ImportActivePaths.activeStagingPaths()
        fun finishActiveImport(output: File) = ImportActivePaths.finishOutput(output)
    }
}
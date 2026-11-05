package com.ottomusic.sketch.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.ottomusic.sketch.data.isOutOfDiskSpace
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.AccessDeniedException
import java.nio.file.AtomicMoveNotSupportedException

internal fun exportFailureMessage(failure: Throwable, fallback: String): String {
    val causes = generateSequence(failure) { it.cause }.toList()
    if (causes.any { it is com.ottomusic.sketch.data.ExportCancelledException }) {
        return "Export cancelled. No partial file was saved."
    }
    if (causes.any { it is AtomicMoveNotSupportedException }) {
        return "This storage cannot safely replace the export. Any existing file was left unchanged."
    }
    if (causes.any(::isDestinationPermissionFailure)) {
        return "Export destination is not writable. Try again."
    }
    if (causes.any(::isOutOfDiskSpace)) {
        return "Not enough storage to save this export."
    }
    return failure.message?.takeIf(String::isNotBlank) ?: fallback
}

private fun isDestinationPermissionFailure(failure: Throwable): Boolean = when (failure) {
    is SecurityException, is AccessDeniedException -> true
    is FileNotFoundException -> {
        val message = failure.message.orEmpty()
        message.contains("permission denied", true) || message.contains("EACCES", true) ||
            message.matches(Regex(".*[\\\\/]\\.(?:wav-export|m4a-export|export-owner)-[A-Za-z0-9]+\\.tmp$")) ||
            message.matches(Regex(".*[\\\\/]mix-source-[A-Za-z0-9]+\\.wav$"))
    }
    else -> false
}

internal fun shareExport(context: Context, file: File, title: String = "Share audio") {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = if (file.extension.equals("m4a", true)) "audio/mp4" else "audio/wav"
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newUri(context.contentResolver, file.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, title))
}
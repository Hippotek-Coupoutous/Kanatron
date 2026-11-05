package com.ottomusic.sketch.data

import com.ottomusic.sketch.core.PROJECT_ID_PATTERN
import java.io.File

internal object StartupOrphanSweep {
    private val IMPORT_DECODING_PATH = Regex("import-$PROJECT_ID_PATTERN\\.decoding\\.wav")
    private val STAGING_SOURCE_PATH = Regex("source-[A-Za-z0-9-]{3,}\\.bin")
    private val EXPORT_TEMP_PATH = Regex("\\.(?:wav-export|m4a-export|m4a-probe)-[A-Za-z0-9-]{3,}\\.tmp")
    private val EXPORT_OWNER_TEMP_PATH = Regex("\\.export-owner-[A-Za-z0-9-]{3,}\\.tmp")

    fun sweep(
        recordings: File,
        staging: File,
        exports: File,
        activeDecodePaths: Set<String> = emptySet(),
        activeStagingPaths: Set<String> = emptySet()
    ) {
        removeMatching(recordings, IMPORT_DECODING_PATH, activeDecodePaths)
        removeMatching(staging, STAGING_SOURCE_PATH, activeStagingPaths)
        ExportActivePaths.duringSweep { registered ->
            removeMatching(exports, EXPORT_TEMP_PATH, registered)
            removeMatching(File(exports.parentFile, "export-metadata"), EXPORT_OWNER_TEMP_PATH, registered)
        }
    }

    private fun removeMatching(directory: File, name: Regex, activePaths: Set<String>) {
        directory.listFiles { file -> name.matches(file.name) && file.absolutePath !in activePaths }
            .orEmpty().forEach { it.delete() }
    }
}
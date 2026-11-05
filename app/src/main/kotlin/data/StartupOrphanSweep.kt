package com.ottomusic.sketch.data

import java.io.File

internal object StartupOrphanSweep {
    fun sweep(
        recordings: File,
        staging: File,
        exports: File,
        activeDecodePaths: Set<String> = emptySet(),
        activeStagingPaths: Set<String> = emptySet()
    ) {
        removeMatching(recordings, Regex("import-[A-Za-z0-9-]{1,80}\\.decoding\\.wav"), activeDecodePaths)
        removeMatching(staging, Regex("source-[A-Za-z0-9-]{3,}\\.bin"), activeStagingPaths)
        ExportActivePaths.duringSweep { registered ->
            removeMatching(exports, Regex("\\.(?:wav-export|m4a-export|m4a-probe)-[A-Za-z0-9-]{3,}\\.tmp"), registered)
            removeMatching(File(exports.parentFile, "export-metadata"), Regex("\\.export-owner-[A-Za-z0-9-]{3,}\\.tmp"), registered)
        }
    }

    private fun removeMatching(directory: File, name: Regex, activePaths: Set<String>) {
        directory.listFiles { file -> name.matches(file.name) && file.absolutePath !in activePaths }
            .orEmpty().forEach { it.delete() }
    }
}
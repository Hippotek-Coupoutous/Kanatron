package com.ottomusic.sketch.data

import java.io.File

internal object StartupOrphanSweep {
    fun sweep(
        recordings: File,
        staging: File,
        exports: File,
        activeDecodePaths: Set<String> = emptySet(),
        activeStagingPaths: Set<String> = emptySet(),
        activeExportPaths: Set<String>? = null
    ): List<File> {
        val removed = mutableListOf<File>()
        removeMatching(recordings, Regex("import-[A-Za-z0-9-]{1,80}\\.decoding\\.wav"), activeDecodePaths, removed)
        removeMatching(staging, Regex("source-[A-Za-z0-9-]{3,}\\.bin"), activeStagingPaths, removed)
        ExportActivePaths.duringSweep { registered ->
            val active = activeExportPaths ?: registered
            removeMatching(exports, Regex("\\.(?:wav-export|m4a-export|m4a-probe)-[A-Za-z0-9-]{3,}\\.tmp"), active, removed)
            removeMatching(File(exports.parentFile, "export-metadata"), Regex("\\.export-owner-[A-Za-z0-9-]{3,}\\.tmp"), active, removed)
        }
        return removed
    }

    private fun removeMatching(directory: File, name: Regex, activePaths: Set<String>, removed: MutableList<File>) {
        directory.listFiles { file -> name.matches(file.name) && file.absolutePath !in activePaths }
            .orEmpty().forEach { if (it.delete()) removed += it }
    }
}
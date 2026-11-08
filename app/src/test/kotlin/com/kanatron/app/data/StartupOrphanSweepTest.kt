package com.kanatron.app.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StartupOrphanSweepTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun File.names(): Set<String> = listFiles().orEmpty().map { it.name }.toSet()

    @Test
    fun sweepsOnlyStaleTemporariesAndProtectsInFlightWork() {
        val root = temp.newFolder("root")
        val recordings = File(root, "recordings").apply { mkdirs() }
        val staging = File(root, "import-staging").apply { mkdirs() }
        val exports = File(root, "exports").apply { mkdirs() }

        File(recordings, "import-stale.decoding.wav").writeBytes(ByteArray(4))
        val liveDecode = File(recordings, "import-live.decoding.wav").apply { writeBytes(ByteArray(4)) }
        File(recordings, "import-ccc.wav").writeBytes(ByteArray(4))
        File(recordings, "take-ddd.wav").writeBytes(ByteArray(4))
        File(recordings, "take-eee.partial.wav").writeBytes(ByteArray(4))

        val activeStaging = File.createTempFile("source-", ".bin", staging)
        val releasedStaging = File.createTempFile("source-", ".bin", staging)
        ImportActivePaths.beginStaging(activeStaging)
        ImportActivePaths.beginStaging(releasedStaging)
        ImportActivePaths.beginOutput(liveDecode)
        ImportActivePaths.importerFinished(liveDecode, releasedStaging, completed = true)
        File.createTempFile("source-", ".bin", staging)
        File(staging, "notes.txt").writeBytes(ByteArray(4))

        File.createTempFile(".wav-export-", ".tmp", exports)
        val liveTemp = ExportActivePaths.createTracked { File.createTempFile(".wav-export-", ".tmp", exports) }
        File(exports, "Song.wav").writeBytes(ByteArray(4))
        File(exports, "notes.txt").writeBytes(ByteArray(4))

        try {
            assertEquals(setOf(liveDecode.absolutePath), ImportActivePaths.activeDecodePaths())
            assertEquals(setOf(activeStaging.absolutePath), ImportActivePaths.activeStagingPaths())

            StartupOrphanSweep.sweep(
                recordings,
                staging,
                exports,
                ImportActivePaths.activeDecodePaths(),
                ImportActivePaths.activeStagingPaths()
            )

            assertEquals(
                setOf("import-live.decoding.wav", "import-ccc.wav", "take-ddd.wav", "take-eee.partial.wav"),
                recordings.names()
            )
            assertEquals(setOf(activeStaging.name, "notes.txt"), staging.names())
            assertEquals(setOf(liveTemp.name, "Song.wav", "notes.txt"), exports.names())

            ImportActivePaths.finishOutput(liveDecode)
            ExportActivePaths.finish(liveTemp)

            StartupOrphanSweep.sweep(
                recordings,
                staging,
                exports,
                ImportActivePaths.activeDecodePaths(),
                ImportActivePaths.activeStagingPaths()
            )

            assertEquals(setOf("import-ccc.wav", "take-ddd.wav", "take-eee.partial.wav"), recordings.names())
            assertEquals(setOf(activeStaging.name, "notes.txt"), staging.names())
            assertEquals(setOf("Song.wav", "notes.txt"), exports.names())
        } finally {
            ImportActivePaths.finishOutput(liveDecode)
            ImportActivePaths.finishStaging(activeStaging)
            ImportActivePaths.finishStaging(releasedStaging)
            ExportActivePaths.finish(liveTemp)
        }
    }
}

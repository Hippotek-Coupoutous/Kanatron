package com.kanatron.app.core

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MultitrackRendererBlockSeamTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `render continues across the 1024 frame block boundary`() {
        val root = tempFolder.root
        val source = File(root, "ramp.wav")
        val payload = ByteArray(4_000)
        for (frame in 0 until 2_000) putPcm16(payload, frame * 2, frame)
        source.outputStream().use { output ->
            WavFormat.writeHeader(output, channels = 1, sampleRate = 48_000, dataSize = payload.size.toLong())
            output.write(payload)
        }

        val project = Project(
            id = "p",
            name = "Ramp",
            sampleRate = 48_000,
            channels = 1,
            createdAtEpochMillis = 0L,
            tracks = listOf(
                ProjectTrack(
                    "t", "T", listOf(
                        ProjectClip("c", ProjectAudioSource("ramp.wav", 48_000, 1, 2_000), 0L, TrimRange(0, 2_000))
                    )
                )
            )
        )

        val destination = File(root, "render.wav")
        MultitrackRenderer.renderToWav(project, { resolved -> File(root, resolved.wav) }, destination)

        val bytes = destination.readBytes()
        // 44-byte header + 2 000 frames x 1 channel x 2 bytes.
        assertEquals(4_044, bytes.size)
        assertEquals(4_000L, destination.inputStream().use(WavFormat::read).dataSize)

        val blockFrames = MultitrackRenderer.BLOCK_FRAMES
        // Seam frames plus the final frame index of the 2 000-frame source.
        for (frame in listOf(blockFrames - 1, blockFrames, blockFrames + 1, 1_999)) {
            assertEquals(frame, pcm16At(bytes, 44 + frame * 2))
        }
    }
}

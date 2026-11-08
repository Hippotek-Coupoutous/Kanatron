package com.kanatron.app.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MultitrackRendererMixTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `mix places trims gains mono and mutes`() {
        val root = tempFolder.root
        writeWav(
            File(root, "a.wav"), channels = 2, sampleRate = 48_000, samples = listOf(
                32_000, -32_000,
                32_000, -32_000,
                32_000, 0,
                -32_000, 32_000,
                100, -100,
                0, 0
            )
        )
        writeWav(File(root, "b.wav"), channels = 1, sampleRate = 48_000, samples = listOf(999, 3, -2_000, 300))
        writeWav(File(root, "c.wav"), channels = 1, sampleRate = 48_000, samples = listOf(1_000))
        writeWav(File(root, "d.wav"), channels = 1, sampleRate = 48_000, samples = listOf(32_767))
        writeWav(File(root, "e.wav"), channels = 1, sampleRate = 48_000, samples = listOf(-1_000))

        val project = Project(
            id = "p",
            name = "Mix",
            sampleRate = 48_000,
            channels = 2,
            createdAtEpochMillis = 0L,
            tracks = listOf(
                ProjectTrack(
                    "a", "A", listOf(
                        ProjectClip("c1", ProjectAudioSource("a.wav", 48_000, 2, 6), 0L, TrimRange(0, 6))
                    )
                ),
                ProjectTrack(
                    "b", "B", listOf(
                        ProjectClip(
                            "c2", ProjectAudioSource("b.wav", 48_000, 1, 4), 2L, TrimRange(1, 4),
                            gainPermille = 250
                        )
                    ), gainPermille = 2_000
                ),
                ProjectTrack(
                    "c", "C", listOf(
                        ProjectClip("c3", ProjectAudioSource("c.wav", 48_000, 1, 1), 0L, TrimRange(0, 1)),
                        ProjectClip(
                            "c4", ProjectAudioSource("d.wav", 48_000, 1, 1), 0L, TrimRange(0, 1),
                            muted = true
                        )
                    )
                ),
                ProjectTrack(
                    "d", "D", listOf(
                        ProjectClip("c5", ProjectAudioSource("e.wav", 48_000, 1, 1), 5L, TrimRange(0, 1))
                    ), muted = true
                )
            )
        )

        assertEquals(6L, project.totalFrames)

        val destination = File(root, "mix.wav")
        MultitrackRenderer.renderToWav(project, { source -> File(root, source.wav) }, destination)

        val bytes = destination.readBytes()
        // 44-byte header + 6 frames x 2 channels x 2 bytes.
        assertEquals(68, bytes.size)
        val format = destination.inputStream().use(WavFormat::read)
        assertEquals(2, format.channels)
        assertEquals(48_000, format.sampleRate)
        assertEquals(24L, format.dataSize)
        assertEquals(6L, format.frameCount)

        val actual = IntArray(12) { pcm16At(bytes, 44 + it * 2) }
        assertEquals(
            listOf(
                32_767, -31_000, // 32000 + 1000 clamps; -32000 + 1000
                32_000, -32_000,
                32_002, 2, // 32000 + 3 x (250/1000 x 2000/1000) = 32001.5, rounded half up
                -32_768, 31_000, // -32000 - 1000 clamps; 32000 - 1000
                250, 50, // 100 + 300 x 0.5; -100 + 300 x 0.5
                0, 0
            ),
            actual.toList()
        )

        val source = File(root, "a.wav")
        val before = source.readBytes()
        assertThrows(IllegalArgumentException::class.java) {
            MultitrackRenderer.renderToWav(project, { resolved -> File(root, resolved.wav) }, source)
        }
        assertArrayEquals(before, source.readBytes())
    }

    private fun writeWav(file: File, channels: Int, sampleRate: Int, samples: List<Int>) {
        val payload = ByteArray(samples.size * 2)
        samples.forEachIndexed { index, value -> putPcm16(payload, index * 2, value) }
        file.outputStream().use { output ->
            WavFormat.writeHeader(output, channels, sampleRate, payload.size.toLong())
            output.write(payload)
        }
    }
}

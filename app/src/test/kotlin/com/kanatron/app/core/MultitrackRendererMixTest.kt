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

    @Test
    fun `overdub keeps the take offset and duplicates a mono take`() {
        val root = tempFolder.root
        writeWav(File(root, "base.wav"), channels = 2, sampleRate = 48_000, samples = listOf(100, -100, 200, -200, 300, -300, 400, -400))
        writeWav(File(root, "take.wav"), channels = 1, sampleRate = 48_000, samples = listOf(10, 20))
        val base = project(ProjectTrack("track-main", "Base", listOf(clip("clip-main", "base.wav", 2, 4))), channels = 2)
        val take = clip("clip-main", "take.wav", 1, 2).copy(positionFrames = 3L)

        val destination = File(root, "mix.wav")
        val clipped = MultitrackRenderer.renderToWav(ProjectArrangementActions.overdub(base, take), { File(root, it.wav) }, destination)

        assertEquals(false, clipped)
        assertEquals(listOf(100, -100, 200, -200, 300, -300, 410, -390, 20, 20), pcm(destination))
    }

    @Test
    fun `overdub of a stereo take over a mono backing widens the mix to stereo`() {
        val root = tempFolder.root
        writeWav(File(root, "base.wav"), channels = 1, sampleRate = 48_000, samples = listOf(100, 200))
        writeWav(File(root, "take.wav"), channels = 2, sampleRate = 48_000, samples = listOf(1, -1, 2, -2))
        val base = project(ProjectTrack("track-main", "Base", listOf(clip("clip-main", "base.wav", 1, 2))), channels = 1)

        val mix = ProjectArrangementActions.overdub(base, clip("clip-main", "take.wav", 2, 2))
        val destination = File(root, "mix.wav")
        MultitrackRenderer.renderToWav(mix, { File(root, it.wav) }, destination)

        assertEquals(2, mix.channels)
        assertEquals(listOf(101, 99, 202, 198), pcm(destination))
    }

    @Test
    fun `overdub that sums past full scale clamps and reports clipping`() {
        val root = tempFolder.root
        writeWav(File(root, "base.wav"), channels = 1, sampleRate = 48_000, samples = listOf(32_767, -32_768))
        writeWav(File(root, "take.wav"), channels = 1, sampleRate = 48_000, samples = listOf(32_767, -32_768))
        val base = project(ProjectTrack("track-main", "Base", listOf(clip("clip-main", "base.wav", 1, 2))), channels = 1)

        val mix = ProjectArrangementActions.overdub(base, clip("clip-main", "take.wav", 1, 2))
        val destination = File(root, "mix.wav")

        assertEquals(true, MultitrackRenderer.renderToWav(mix, { File(root, it.wav) }, destination))
        assertEquals(listOf(32_767, -32_768), pcm(destination))
    }

    private fun clip(id: String, wav: String, channels: Int, frames: Long) =
        ProjectClip(id, ProjectAudioSource(wav, 48_000, channels, frames), 0L, TrimRange(0, frames))

    private fun project(track: ProjectTrack, channels: Int) =
        Project("p", "Base", 48_000, channels, 0L, tracks = listOf(track))

    private fun pcm(file: File): List<Int> {
        val bytes = file.readBytes()
        return List((bytes.size - 44) / 2) { pcm16At(bytes, 44 + it * 2) }
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

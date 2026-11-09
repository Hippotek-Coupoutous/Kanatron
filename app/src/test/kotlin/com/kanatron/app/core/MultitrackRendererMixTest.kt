package com.kanatron.app.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MultitrackRendererMixTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `mix places trims and mono sources and clamps the sum`() {
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

        val project = Project(
            id = "p",
            name = "Mix",
            sampleRate = 48_000,
            channels = 2,
            createdAtEpochMillis = 0L,
            tracks = listOf(
                ProjectTrack(
                    "track-main", "Mix", listOf(
                        ProjectClip("c1", ProjectAudioSource("a.wav", 48_000, 2, 6), 0L, TrimRange(0, 6)),
                        ProjectClip("c2", ProjectAudioSource("b.wav", 48_000, 1, 4), 2L, TrimRange(1, 4)),
                        ProjectClip("c3", ProjectAudioSource("c.wav", 48_000, 1, 1), 0L, TrimRange(0, 1))
                    )
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
        assertEquals(6L, format.frameCount)

        // b's 3 frames sit inside the timeline and are cut from their source, so each end ramps over 1 frame (x 1/2).
        assertEquals(
            listOf(
                32_767, -31_000, // 32000 + 1000 clamps; -32000 + 1000
                32_000, -32_000,
                32_002, 2, // 3 x 1/2 = 1.5 rounds half up
                -32_768, 30_000, // -32000 - 2000 clamps; 32000 - 2000
                250, 50, // ±100 + 300 x 1/2
                0, 0
            ),
            pcm(destination)
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
        val take = OverdubMix(base, "take", ProjectAudioSource("take.wav", 48_000, 1, 2), measuredOffsetFrames = 3L, rateCorrectionPpm = 0)

        val destination = File(root, "mix.wav")
        val clipped = MultitrackRenderer.renderToWav(take.arrangement(), { File(root, it.wav) }, destination)

        assertEquals(false, clipped)
        // Edges inside the timeline ramp over half a short clip: the backing's last 2 frames (x 2/3, 1/3)
        // and the take's first frame (x 1/2).
        assertEquals(listOf(100, -100, 200, -200, 200, -200, 138, -128, 20, 20), pcm(destination))
    }

    @Test
    fun `overdub of a stereo take over a mono backing widens the mix to stereo`() {
        val root = tempFolder.root
        writeWav(File(root, "base.wav"), channels = 1, sampleRate = 48_000, samples = listOf(100, 200))
        writeWav(File(root, "take.wav"), channels = 2, sampleRate = 48_000, samples = listOf(1, -1, 2, -2))
        val base = project(ProjectTrack("track-main", "Base", listOf(clip("clip-main", "base.wav", 1, 2))), channels = 1)

        val mix = OverdubMix(base, "take", ProjectAudioSource("take.wav", 48_000, 2, 2), 0L, 0).arrangement()
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

        val mix = OverdubMix(base, "take", ProjectAudioSource("take.wav", 48_000, 1, 2), 0L, 0).arrangement()
        val destination = File(root, "mix.wav")

        assertEquals(true, MultitrackRenderer.renderToWav(mix, { File(root, it.wav) }, destination))
        assertEquals(listOf(32_767, -32_768), pcm(destination))
    }

    @Test
    fun `a cut crossfades with no dip and a kept range fades out where it was cut`() {
        val root = tempFolder.root
        // 8 kHz makes the 5 ms fade 40 frames.
        writeWav(File(root, "steady.wav"), channels = 1, sampleRate = 8_000, samples = List(200) { 1_000 })
        val source = ProjectAudioSource("steady.wav", 8_000, 1, 200)
        val recording = Project("p", "Steady", 8_000, 1, 0L, tracks = listOf(
            ProjectTrack("track-main", "Steady", listOf(ProjectClip("clip-main", source, 0L, TrimRange(0, 200))))
        ))
        val destination = File(root, "edit.wav")

        val cut = Cuts.delete(listOf(TrimRange(0, 200)), TrimRange(50, 100))
        val arrangement = Cuts.arrangement(recording, cut)
        MultitrackRenderer.renderToWav(arrangement, { File(root, it.wav) }, destination)
        assertEquals(Cuts.length(cut), arrangement.totalFrames)
        assertEquals(List(150) { 1_000 }, pcm(destination))

        MultitrackRenderer.renderToWav(Cuts.arrangement(recording, listOf(TrimRange(0, 100))), { File(root, it.wav) }, destination)
        val kept = pcm(destination)
        assertEquals(1_000, kept[59])
        assertEquals(24, kept[99]) // 1000 x 1/41

        // 20 frames cannot hold two 40-frame fades, so each edge ramps over 10.
        MultitrackRenderer.renderToWav(Cuts.arrangement(recording, listOf(TrimRange(100, 120))), { File(root, it.wav) }, destination)
        assertEquals(909, pcm(destination).max()) // 1000 x 10/11
    }

    @Test
    fun `two whole recordings joined meet through a fade, at the first part's rate`() {
        val root = tempFolder.root
        writeWav(File(root, "a.wav"), channels = 1, sampleRate = 8_000, samples = List(100) { 1_000 })
        writeWav(File(root, "b.wav"), channels = 2, sampleRate = 16_000, samples = List(400) { 2_000 })
        val a = ProjectAudioSource("a.wav", 8_000, 1, 100)
        val b = ProjectAudioSource("b.wav", 16_000, 2, 200)
        val base = Project("p", "A", 8_000, 1, 0L, tracks = listOf(ProjectTrack("track-main", "A", listOf(ProjectClip("clip-main", a, 0L, TrimRange(0, 100))))))

        val joined = Cuts.join(base, listOf(a to TrimRange(0, 100), b to TrimRange(0, 200)))
        val destination = File(root, "joined.wav")
        MultitrackRenderer.renderToWav(joined, { File(root, it.wav) }, destination)
        val samples = pcm(destination)

        assertEquals(2, joined.channels)
        assertEquals(200L, joined.totalFrames) // 100 frames of a, then 200 b frames at 16 kHz are 100 at 8 kHz
        assertEquals(1_000, samples[2 * 50]) // well inside a
        assertEquals(24, samples[2 * 99]) // a's last frame: 1000 x 1/41
        assertEquals(49, samples[2 * 100]) // b's first frame: 2000 x 1/41
        assertEquals(2_000, samples[2 * 150])
    }

    @Test
    fun `a clip's EQ shapes that clip alone`() {
        val root = tempFolder.root
        // A 1 kHz tone over a 100 Hz tone; cutting the mids must leave the low clip as it was.
        writeWav(File(root, "tone.wav"), channels = 1, sampleRate = 48_000, samples = List(4_800) { (8_000 * Math.sin(2 * Math.PI * 1_000 * it / 48_000)).toInt() })
        writeWav(File(root, "low.wav"), channels = 1, sampleRate = 48_000, samples = List(4_800) { (8_000 * Math.sin(2 * Math.PI * 100 * it / 48_000)).toInt() })
        val tone = clip("clip-tone", "tone.wav", 1, 4_800)
        val low = clip("clip-low", "low.wav", 1, 4_800)
        fun render(vararg clips: ProjectClip): List<Int> {
            val destination = File(root, "eq.wav")
            MultitrackRenderer.renderToWav(project(ProjectTrack("track-main", "Base", clips.toList()), channels = 1), { File(root, it.wav) }, destination)
            return pcm(destination)
        }
        val dull = Eq(midSteps = -4)

        val both = render(tone.mixed(0, dull), low)
        val toneAlone = render(tone.mixed(0, dull))
        val lowAlone = render(low)
        assertTrue(both.indices.all { kotlin.math.abs(both[it] - toneAlone[it] - lowAlone[it]) <= 1 })
        assertTrue(toneAlone.subList(2_400, 2_500).max() < render(tone).subList(2_400, 2_500).max() / 2)
    }

    private fun clip(id: String, wav: String, channels: Int, frames: Long) =
        ProjectClip(id, ProjectAudioSource(wav, 48_000, channels, frames), 0L, TrimRange(0, frames))

    private fun project(track: ProjectTrack, channels: Int) =
        Project("p", "Base", 48_000, channels, 0L, tracks = listOf(track))

    @Test
    fun `downsampling by three averages a tone above the new Nyquist down to a third`() {
        val root = tempFolder.root
        // 12 kHz at 48 kHz: plain decimation to 16 kHz would fold it to a full-scale 4 kHz alias.
        writeWav(File(root, "tone.wav"), channels = 1, sampleRate = 48_000, samples = List(4_800) { listOf(16_000, 0, -16_000, 0)[it % 4] })
        // A silent frame at 16 kHz gives the project its rate, as a first Clip part would.
        writeWav(File(root, "anchor.wav"), channels = 1, sampleRate = 16_000, samples = listOf(0))
        val tone = ProjectClip("tone", ProjectAudioSource("tone.wav", 48_000, 1, 4_800), 0L, TrimRange(0, 4_800))
        val anchor = ProjectClip("anchor", ProjectAudioSource("anchor.wav", 16_000, 1, 1), 0L, TrimRange(0, 1))
        val project = Project("p", "Tone", 16_000, 1, 0L, tracks = listOf(ProjectTrack("track-main", "Tone", listOf(anchor, tone))))

        val destination = File(root, "tone-16k.wav")
        MultitrackRenderer.renderToWav(project, { File(root, it.wav) }, destination)

        val samples = pcm(destination)
        assertEquals(1_600, samples.size)
        assertEquals(5_333, samples.maxOf { kotlin.math.abs(it) })
    }

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

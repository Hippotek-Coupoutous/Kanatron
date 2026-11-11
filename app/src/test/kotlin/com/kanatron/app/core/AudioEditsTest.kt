package com.kanatron.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AudioEditsTest {
    @get:Rule val folder = TemporaryFolder()
    private val original = listOf(AudioSection(TrimRange(0, 1_000)))

    @Test
    fun `rendered section EQ leaves samples outside its selection untouched`() {
        val sourceFile = folder.newFile("source.wav")
        val samples = List(1_000) { index -> (kotlin.math.sin(index * 0.3) * 2_000).toInt() }
        sourceFile.outputStream().use { output ->
            WavFormat.writeHeader(output, 1, 8_000, samples.size * 2L)
            samples.forEach { sample -> output.write(sample and 255); output.write((sample shr 8) and 255) }
        }
        val source = ProjectAudioSource("source.wav", 8_000, 1, 1_000)
        val project = Project("p", "Recording", 8_000, 1, 0L,
            tracks = listOf(ProjectTrack("t", "Track", listOf(ProjectClip("c", source, 0, TrimRange(0, 1_000))))))
        val sections = AudioEdits.mix(original, TrimRange(200, 600), 1, Eq(lowSteps = 2))
        val output = File(folder.root, "edited.wav")
        MultitrackRenderer.renderToWav(AudioEdits.arrangement(project, sections), { sourceFile }, output)
        val format = output.inputStream().use(WavFormat::read)
        val bytes = output.readBytes()
        val pcm = List(1_000) { index ->
            val at = format.dataOffset.toInt() + index * 2
            ((bytes[at].toInt() and 255) or (bytes[at + 1].toInt() shl 8)).toShort().toInt()
        }
        assertEquals(samples.take(200), pcm.take(200))
        assertEquals(samples.drop(600), pcm.drop(600))
        assertTrue(samples.subList(250, 550) != pcm.subList(250, 550))
    }

    @Test
    fun `EQ changes only the selected timeline range and replacing it preserves surrounding settings`() {
        val firstEq = Eq(lowSteps = 2)
        val first = AudioEdits.mix(original, TrimRange(200, 600), 1, firstEq)
        assertEquals(listOf(
            AudioSection(TrimRange(0, 200)),
            AudioSection(TrimRange(200, 600), 1, firstEq),
            AudioSection(TrimRange(600, 1_000))
        ), first)
        val changed = AudioEdits.mix(first, TrimRange(400, 800), 2, Eq(highSteps = -1))
        assertEquals(AudioSection(TrimRange(200, 400), 1, firstEq), changed[1])
        assertEquals(AudioSection(TrimRange(400, 800), 2, Eq(highSteps = -1)), changed[2])
        assertEquals(AudioSection(TrimRange(800, 1_000)), changed.last())
        assertEquals(1_000L, AudioEdits.length(changed))
        assertEquals(original, AudioEdits.mix(first, TrimRange(0, 1_000), 0, Eq.FLAT))
    }

    @Test
    fun `loop count includes original and the suffix follows all repetitions`() {
        val eq = Eq(midSteps = 1)
        val mixed = AudioEdits.mix(original, TrimRange(200, 400), 1, eq)
        val repeated = AudioEdits.repeat(mixed, TrimRange(200, 400), 3)
        assertEquals(listOf(
            AudioSection(TrimRange(0, 200)),
            AudioSection(TrimRange(200, 400), 1, eq),
            AudioSection(TrimRange(200, 400), 1, eq),
            AudioSection(TrimRange(200, 400), 1, eq),
            AudioSection(TrimRange(400, 1_000))
        ), repeated)
        assertEquals(1_400L, AudioEdits.length(repeated))
        assertEquals(mixed, AudioEdits.repeat(mixed, TrimRange(100, 700), 1))
        assertEquals(2_000L, AudioEdits.length(AudioEdits.repeat(original, TrimRange(0, 1_000), 2)))
        assertThrows(IllegalArgumentException::class.java) { AudioEdits.repeat(original, TrimRange(0, 1_000), 0) }
    }

    @Test
    fun `delete and extraction use edited timeline coordinates across repeated source sections`() {
        val repeated = AudioEdits.repeat(original, TrimRange(200, 400), 3)
        assertEquals(listOf(AudioSection(TrimRange(350, 400)), AudioSection(TrimRange(200, 250))), AudioEdits.slice(repeated, TrimRange(350, 450)))
        val deleted = AudioEdits.delete(repeated, TrimRange(200, 800))
        assertEquals(listOf(AudioSection(TrimRange(0, 200)), AudioSection(TrimRange(400, 1_000))), deleted)
        assertEquals(800L, AudioEdits.length(deleted))
        assertThrows(IllegalArgumentException::class.java) { AudioEdits.delete(original, TrimRange(0, 1_000)) }
    }

    @Test
    fun `a delete that splits a section respects the section limit and a rejoining delete merges`() {
        val full = AudioEdits.repeat(AudioEdits.repeat(original, TrimRange(0, 1_000), 64), TrimRange(0, 64_000), 4)
        assertEquals(Project.MAX_CLIPS, full.size)
        assertThrows(IllegalArgumentException::class.java) { AudioEdits.delete(full, TrimRange(10, 20)) }
        val looped = AudioEdits.repeat(original, TrimRange(0, 1_000), 2)
        assertEquals(listOf(AudioSection(TrimRange(0, 1_000))), AudioEdits.delete(looped, TrimRange(500, 1_500)))
    }

    @Test
    fun `grid uses an exact beat duration and settings round trip with a recording`() {
        val grid = MusicalGrid(true, 2_000, 4, true)
        assertEquals(120.0, grid.bpm, 0.0)
        assertEquals(24_000.0, grid.beatFrames(48_000), 0.0)
        assertEquals(24_000L, grid.snapped(25_000, 48_000))
        assertEquals(48_000L, grid.snapped(46_000, 48_000))
        assertEquals(25_000L, grid.copy(snap = false).snapped(25_000, 48_000))
        val source = ProjectAudioSource("recordings/a.wav", 48_000, 1, 1_000)
        val project = Project("p", "Recording", 48_000, 1, 0L,
            tracks = listOf(ProjectTrack("t", "Track", listOf(ProjectClip("c", source, 0, TrimRange(0, 1_000))))), grid = grid)
        assertEquals(project, Project.fromJson(project.toJson()))
        val arrangement = AudioEdits.arrangement(project, AudioEdits.repeat(original, TrimRange(200, 400), 3))
        // The first and last copies play on through their source, so they merge with their neighbours.
        assertEquals(listOf(0L, 400L, 600L), arrangement.tracks.single().clips.map { it.positionFrames })
        assertEquals(1_400L, arrangement.totalFrames)
        assertEquals(grid, arrangement.grid)
    }
}

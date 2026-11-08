package com.kanatron.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream

class OverdubMixTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val backing = ProjectAudioSource("base.wav", 48_000, 1, 48_000)
    private val base = Project("p", "Base", 48_000, 1, 0L, tracks = listOf(
        ProjectTrack("track-main", "Base", listOf(ProjectClip("clip-main", backing, 0L, TrimRange(0, 48_000))))
    ))
    private val take = ProjectAudioSource("take.wav", 48_000, 1, 4_800)

    @Test
    fun aTakeBeforeTheBackingLosesItsLeadInAndNudgesMoveIt() {
        val early = OverdubMix(base, "take", take, measuredOffsetFrames = -2_400L, rateCorrectionPpm = 0)
        val clip = early.arrangement().tracks.single().clips.last()
        assertEquals(0L, clip.positionFrames)
        assertEquals(TrimRange(2_400, 4_800), clip.trim)

        // +60 ms is 2,880 frames: the take now starts 480 frames into the backing, whole.
        val later = early.nudged(60)!!
        assertEquals(480L, later.takePositionFrames)
        assertEquals(TrimRange(0, 4_800), later.arrangement().tracks.single().clips.last().trim)

        assertNull(early.nudged(OverdubMix.MAX_NUDGE_MILLIS + 1))
        // -50 ms more would trim all 4,800 take frames.
        assertNull(early.nudged(-50))
    }

    @Test
    fun balanceLowersOneSideOnlyAndRenders() {
        assertEquals(listOf(501, 1_000), OverdubMix(base, "take", take, 0L, 0).balanced(2).arrangement().tracks.single().clips.map { it.gainPermille })
        assertEquals(listOf(1_000, 708), OverdubMix(base, "take", take, 0L, 0).balanced(-1).arrangement().tracks.single().clips.map { it.gainPermille })
        assertEquals(OverdubMix.MAX_BALANCE_STEPS, OverdubMix(base, "take", take, 0L, 0).balanced(99).balanceSteps)

        val root = tempFolder.root
        writeMono(File(root, "base.wav"), List(48_000) { 1_000 })
        writeMono(File(root, "take.wav"), List(4_800) { 2_000 })
        val destination = File(root, "mix.wav")
        fun sampleAt(frame: Int) = pcm16At(destination.readBytes(), 44 + 2 * frame)

        // The take sits at 10,000..14,800: backing 1000 x 0.501 alone, then plus the take at full level.
        MultitrackRenderer.renderToWav(OverdubMix(base, "take", take, 10_000L, 0).balanced(2).arrangement(), { File(root, it.wav) }, destination)
        assertEquals(501, sampleAt(5_000))
        assertEquals(2_501, sampleAt(12_000))
        assertEquals(501, sampleAt(20_000))

        // Lowering the take instead leaves the backing whole: 1000 + 2000 x 0.708.
        MultitrackRenderer.renderToWav(OverdubMix(base, "take", take, 10_000L, 0).balanced(-1).arrangement(), { File(root, it.wav) }, destination)
        assertEquals(1_000, sampleAt(5_000))
        assertEquals(2_416, sampleAt(12_000))
    }

    @Test
    fun aRecordingWithGainIsNeverSaved() {
        val mixed = OverdubMix(base, "take", take, 0L, 0).balanced(1).arrangement()
        assertThrows(IllegalArgumentException::class.java) { mixed.toJson() }
    }

    private fun writeMono(file: File, samples: List<Int>) = FileOutputStream(file).use { output ->
        WavFormat.writeHeader(output, 1, 48_000, samples.size * 2L)
        val bytes = ByteArray(samples.size * 2)
        samples.forEachIndexed { index, sample -> putPcm16(bytes, index * 2, sample) }
        output.write(bytes)
    }
}

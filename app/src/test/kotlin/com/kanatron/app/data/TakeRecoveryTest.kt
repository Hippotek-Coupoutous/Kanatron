package com.kanatron.app.data

import com.kanatron.app.core.Project
import com.kanatron.app.core.ProjectAudioSource
import com.kanatron.app.core.ProjectClip
import com.kanatron.app.core.ProjectTrack
import com.kanatron.app.core.RecordingCore
import com.kanatron.app.core.TrimRange
import com.kanatron.app.core.WavFormat
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TakeRecoveryTest {

    @get:Rule
    val temp = TemporaryFolder()

    private class CapturedTake(
        val id: String,
        val file: File,
        val sampleRate: Int,
        val channels: Int,
        val frames: Long,
        val silent: Boolean,
        val draft: Boolean
    )

    @Test
    fun repairsPartialTakeAtRecordedRateAndLeavesDamagedOrLiveAudioUntouched() {
        val root = temp.newFolder("root")
        val projects = File(root, "projects").apply { mkdirs() }
        val recordings = File(root, "recordings").apply { mkdirs() }

        val pcm = ByteArray(1_000) { (it % 251 + 1).toByte() }
        val partial = File(recordings, "take-abc123.partial.wav")
        // 44-byte header + 1000 PCM bytes + one stray byte: repairable = ((1045 - 44) / 2) * 2 = 1000.
        partial.writeBytes(RecordingCore.header(44_100, 1, 0) + pcm + byteArrayOf(0x7F))

        val liveBytes = RecordingCore.header(44_100, 1, 0) + ByteArray(100) { 1 }
        val live = File(recordings, "take-live1.partial.wav").apply { writeBytes(liveBytes) }

        // Header promises 2000 bytes of PCM but only 1000 exist.
        val badBytes = RecordingCore.header(48_000, 1, 2_000) + ByteArray(1_000) { 0x11 }
        val bad = File(recordings, "take-bad999.wav").apply { writeBytes(badBytes) }

        val created = mutableMapOf<String, Project>()
        val captured = mutableListOf<CapturedTake>()
        val recovery = TakeRecovery(
            projects,
            recordings,
            { File(projects, "$it.json") },
            { created[it] },
            { id, file, sampleRate, channels, frames, name, silent, draft ->
                captured += CapturedTake(id, file, sampleRate, channels, frames, silent, draft)
                Project(
                    id = id,
                    name = name,
                    sampleRate = sampleRate,
                    channels = channels,
                    createdAtEpochMillis = 0L,
                    silent = silent,
                    draft = draft,
                    tracks = listOf(
                        ProjectTrack(
                            id = "track-main",
                            name = name,
                            clips = listOf(
                                ProjectClip(
                                    id = "clip-main",
                                    source = ProjectAudioSource("recordings/${file.name}", sampleRate, channels, frames),
                                    positionFrames = 0L,
                                    trim = TrimRange(0L, frames)
                                )
                            )
                        )
                    )
                ).also { created[id] = it }
            }
        )

        RecordingActivePath.path = live.absolutePath
        val recovered = try {
            recovery.recoverTakes()
        } finally {
            RecordingActivePath.path = null
        }

        assertEquals(1, captured.size)
        val take = captured.single()
        assertEquals("abc123", take.id)
        assertEquals("take-abc123.wav", take.file.name)
        assertEquals(44_100, take.sampleRate)
        assertEquals(1, take.channels)
        // 1000 repaired mono PCM bytes / 2 bytes per frame.
        assertEquals(500L, take.frames)
        assertFalse(take.silent)
        assertTrue(take.draft)
        assertEquals(listOf("abc123"), recovered.map(Project::id))

        assertFalse(partial.exists())
        val repaired = File(recordings, "take-abc123.wav")
        assertTrue(repaired.isFile)
        assertEquals(WavFormat.HEADER_BYTES + 1_000L, repaired.length())
        val format = repaired.inputStream().use(WavFormat::read)
        assertEquals(WavFormat.HEADER_BYTES.toLong(), format.dataOffset)
        assertEquals(1_000L, format.dataSize)
        assertEquals(500L, format.frameCount)
        assertEquals(44_100, format.sampleRate)
        assertEquals(1, format.channels)
        assertEquals(16, format.bitsPerSample)
        assertArrayEquals(pcm, repaired.readBytes().copyOfRange(WavFormat.HEADER_BYTES, 1_044))

        assertTrue(live.isFile)
        assertEquals(liveBytes.size.toLong(), live.length())
        assertArrayEquals(liveBytes, live.readBytes())
        assertFalse(File(recordings, "take-live1.wav").exists())

        assertTrue(bad.isFile)
        assertEquals(badBytes.size.toLong(), bad.length())
        assertArrayEquals(badBytes, bad.readBytes())
        assertFalse(File(recordings, "take-bad999.partial.wav").exists())
        assertFalse(created.containsKey("bad999"))
    }
}

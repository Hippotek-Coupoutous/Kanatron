package com.kanatron.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UndoHistoryTest {

    @Test
    fun undoRestoresOneEditPerStepAndRecordsNoOpAsNothing() {
        val fresh = projectWithClip()

        val noOps = listOf(
            ProjectArrangementActions.setTrackMuted(fresh, "track-a", false),
            ProjectArrangementActions.setTrackGain(fresh, "track-a", 1_000),
            ProjectArrangementActions.setPosition(fresh, "track-a", "clip-a", 0),
            ProjectArrangementActions.setClipTrim(fresh, "track-a", "clip-a", TrimRange(0, 96_000))
        )
        noOps.forEach { result ->
            assertEquals(fresh.tracks, result.tracks)
            assertEquals(emptyList<ProjectArrangementSnapshot>(), result.undoHistory)
        }

        val e1 = ProjectArrangementActions.setClipTrim(fresh, "track-a", "clip-a", TrimRange(1_000, 90_000))
        val e2 = ProjectArrangementActions.setPosition(e1, "track-a", "clip-a", 500)
        val e3 = ProjectArrangementActions.setTrackGain(e2, "track-a", 500)
        assertEquals(3, e3.undoHistory.size)

        val undo1 = ProjectArrangementActions.undo(e3)!!
        assertEquals(TrimRange(1_000, 90_000), undo1.firstClip.trim)
        assertEquals(500L, undo1.firstClip.positionFrames)
        assertEquals(1_000, undo1.tracks.first().gainPermille)
        assertEquals(2, undo1.undoHistory.size)

        val undo2 = ProjectArrangementActions.undo(undo1)!!
        assertEquals(TrimRange(1_000, 90_000), undo2.firstClip.trim)
        assertEquals(0L, undo2.firstClip.positionFrames)
        assertEquals(1, undo2.undoHistory.size)

        val undo3 = ProjectArrangementActions.undo(undo2)!!
        assertEquals(TrimRange(0, 96_000), undo3.firstClip.trim)
        assertEquals(0L, undo3.firstClip.positionFrames)
        assertEquals(1_000, undo3.tracks.first().gainPermille)
        assertEquals(emptyList<ProjectArrangementSnapshot>(), undo3.undoHistory)

        assertNull(ProjectArrangementActions.undo(undo3))
    }

    @Test
    fun undoHistoryKeepsNewestStepsWhenAtCap() {
        var project = projectWithClip()
        repeat(Project.MAX_UNDO + 1) { index ->
            project = ProjectArrangementActions.setPosition(project, "track-a", "clip-a", index + 1L)
        }
        assertEquals(Project.MAX_UNDO + 1L, project.firstClip.positionFrames)
        assertEquals(Project.MAX_UNDO, project.undoHistory.size)

        // After 21 edits the retained snapshots are the states after edits 1..20; the newest undo lands on 20.
        assertEquals(Project.MAX_UNDO.toLong(), ProjectArrangementActions.undo(project)!!.firstClip.positionFrames)

        var current = project
        var undoCount = 0
        while (true) {
            val undone = ProjectArrangementActions.undo(current) ?: break
            current = undone
            undoCount++
        }
        assertEquals(Project.MAX_UNDO, undoCount)
        assertEquals(1L, current.firstClip.positionFrames)
    }

    private fun projectWithClip(): Project = Project(
        id = "proj-undo",
        name = "Undo",
        sampleRate = 48_000,
        channels = 1,
        createdAtEpochMillis = 1_700_000_000_000,
        tracks = listOf(
            ProjectTrack(
                id = "track-a",
                name = "Track A",
                clips = listOf(
                    ProjectClip(
                        id = "clip-a",
                        source = ProjectAudioSource("recordings/take-take-a.wav", 48_000, 1, 96_000),
                        positionFrames = 0,
                        trim = TrimRange(0, 96_000)
                    )
                )
            )
        )
    )
}

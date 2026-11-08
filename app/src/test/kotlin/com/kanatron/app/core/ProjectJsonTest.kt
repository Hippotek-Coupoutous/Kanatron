package com.kanatron.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ProjectJsonTest {

    @Test
    fun projectFileRoundTripsEveryFieldAndLoadsLegacyDefaults() {
        val project = roundTripProject()
        assertEquals(project, Project.fromJson(project.toJson()))

        val legacy = Project.fromJson(LEGACY_JSON)
        assertFalse(legacy.silent)
        assertFalse(legacy.draft)
        assertEquals(0, legacy.firstClip.rateCorrectionPpm)
        assertEquals(legacyTracks(), legacy.tracks)
        assertEquals(legacy, Project.fromJson(legacy.toJson()))
    }

    private fun roundTripProject(): Project {
        val escaped = "Quote \" backslash \\ newline \n tab \t é \u0001"
        return Project(
            id = "proj-json",
            name = escaped,
            sampleRate = 48_000,
            channels = 2,
            createdAtEpochMillis = 1_700_000_000_123,
            silent = true,
            // Differs from silent so a swapped JSON key fails the round-trip.
            draft = false,
            tracks = listOf(
                ProjectTrack(
                    id = "track-a",
                    name = escaped,
                    clips = listOf(
                        ProjectClip(
                            id = "clip-a",
                            source = ProjectAudioSource("recordings/take-take-a.wav", 48_000, 2, 96_000),
                            positionFrames = 0,
                            trim = TrimRange(1_200, 96_000),
                            gainPermille = 850,
                            muted = true,
                            rateCorrectionPpm = 750
                        )
                    ),
                    gainPermille = 800,
                    muted = true,
                    solo = true
                ),
                ProjectTrack(
                    id = "track-b",
                    name = "Second",
                    clips = listOf(
                        ProjectClip(
                            id = "clip-b",
                            source = ProjectAudioSource("recordings/import-import-b.wav", 44_100, 2, 44_100),
                            positionFrames = 5_000,
                            trim = TrimRange(0, 44_100),
                            rateCorrectionPpm = -250
                        )
                    ),
                    gainPermille = 1_100
                )
            ),
            undoHistory = listOf(
                ProjectArrangementSnapshot(
                    listOf(
                        ProjectTrack(
                            id = "track-a",
                            name = "Track A",
                            clips = listOf(
                                ProjectClip(
                                    id = "clip-a",
                                    source = ProjectAudioSource("recordings/take-take-a.wav", 48_000, 2, 96_000),
                                    positionFrames = 0,
                                    trim = TrimRange(0, 96_000)
                                )
                            )
                        )
                    )
                )
            )
        )
    }

    private fun legacyTracks(): List<ProjectTrack> = listOf(
        ProjectTrack(
            id = "track-a",
            name = "Track A",
            clips = listOf(
                ProjectClip(
                    id = "clip-a",
                    source = ProjectAudioSource("recordings/take-take-a.wav", 8_000, 1, 8_000),
                    positionFrames = 0,
                    trim = TrimRange(0, 8_000)
                )
            )
        )
    )

    private companion object {
        val LEGACY_JSON = """
            {
              "schemaVersion": 3,
              "id": "proj-legacy",
              "name": "Legacy",
              "sampleRate": 8000,
              "channels": 1,
              "tracks": [
                {
                  "id": "track-a",
                  "name": "Track A",
                  "gainPermille": 1000,
                  "muted": false,
                  "solo": false,
                  "clips": [
                    {
                      "id": "clip-a",
                      "source": {
                        "wav": "recordings/take-take-a.wav",
                        "sampleRate": 8000,
                        "channels": 1,
                        "totalFrames": 8000
                      },
                      "positionFrames": 0,
                      "trim": {"startFrame": 0, "endFrame": 8000},
                      "gainPermille": 1000,
                      "muted": false
                    }
                  ]
                }
              ],
              "undoHistory": [],
              "createdAtEpochMillis": 1700000000123
            }
        """.trimIndent()
    }
}

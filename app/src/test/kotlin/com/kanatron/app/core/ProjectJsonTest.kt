package com.kanatron.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectJsonTest {

    @Test
    fun projectFileRoundTripsEveryField() {
        val escaped = "Quote \" backslash \\ newline \n tab \t é \u0001"
        val project = Project(
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
                        ProjectClip("clip-a", ProjectAudioSource("recordings/take-take-a.wav", 48_000, 2, 96_000), 0, TrimRange(1_200, 96_000), 750),
                        ProjectClip("clip-b", ProjectAudioSource("recordings/import-import-b.wav", 44_100, 2, 44_100), 5_000, TrimRange(0, 44_100), -250)
                    )
                )
            )
        )
        val json = project.toJson()
        assertTrue(json.contains("\"schemaVersion\":4"))
        assertEquals(project, Project.fromJson(json))
    }

    @Test
    fun versionThreeLoadsOnlyWhatStillSoundsTheSame() {
        val legacy = Project.fromJson(LEGACY_JSON)
        assertFalse(legacy.silent)
        assertFalse(legacy.draft)
        assertEquals(TrimRange(0, 8_000), legacy.firstClip.trim)
        assertEquals(0, legacy.firstClip.rateCorrectionPpm)
        assertEquals(legacy, Project.fromJson(legacy.toJson()))

        val track = LEGACY_JSON.substringAfter("\"tracks\": [").substringBefore("\n  ],")
        assertTrue(track.contains("\"clip-a\""))
        val twoTracks = LEGACY_JSON.replace(track, track + "," + track.replace("track-a", "track-b").replace("clip-a", "clip-b"))
        assertThrows(UnsupportedProjectException::class.java) { Project.fromJson(twoTracks) }
        // Only the clip's "muted" ends its object, so it is the one without a trailing comma.
        val mutedClip = LEGACY_JSON.replace("\"muted\": false\n", "\"muted\": true\n")
        assertTrue(mutedClip != LEGACY_JSON)
        assertThrows(UnsupportedProjectException::class.java) { Project.fromJson(mutedClip) }
    }

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

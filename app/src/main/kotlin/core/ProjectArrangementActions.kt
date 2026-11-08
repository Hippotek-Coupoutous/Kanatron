package com.kanatron.app.core

object ProjectArrangementActions {
    fun addTracks(project: Project, tracks: List<ProjectTrack>): Project =
        apply(project, project.tracks + tracks)

    fun removeTrack(project: Project, trackId: String): Project {
        require(project.tracks.size > 1) { "A project must keep at least one track." }
        require(project.tracks.any { it.id == trackId }) { "Track not found." }
        return apply(project, project.tracks.filterNot { it.id == trackId })
    }

    fun updateTrack(project: Project, trackId: String, transform: (ProjectTrack) -> ProjectTrack): Project {
        val nextTracks = project.tracks.map { if (it.id == trackId) transform(it) else it }
        require(nextTracks.any { it.id == trackId }) { "Track not found." }
        return apply(project, nextTracks)
    }

    fun setPosition(project: Project, trackId: String, clipId: String, positionFrames: Long): Project {
        require(positionFrames >= 0L)
        require(project.tracks.any { track -> track.id == trackId && track.clips.any { it.id == clipId } }) { "Clip not found." }
        return updateTrack(project, trackId) { track ->
            track.copy(clips = track.clips.map { clip ->
                if (clip.id == clipId) clip.copy(positionFrames = positionFrames) else clip
            })
        }
    }

    fun setClipTrim(project: Project, trackId: String, clipId: String, trim: TrimRange): Project {
        val clip = project.tracks.firstOrNull { it.id == trackId }?.clips?.firstOrNull { it.id == clipId }
            ?: throw IllegalArgumentException("Clip not found.")
        trim.requireValidFor(clip.source.totalFrames)
        return updateTrack(project, trackId) { track ->
            track.copy(clips = track.clips.map { current -> if (current.id == clipId) current.copy(trim = trim) else current })
        }
    }

    fun setTrackGain(project: Project, trackId: String, gainPermille: Int): Project {
        require(gainPermille in 0..2_000)
        return updateTrack(project, trackId) { it.copy(gainPermille = gainPermille) }
    }

    fun setTrackMuted(project: Project, trackId: String, muted: Boolean): Project =
        updateTrack(project, trackId) { it.copy(muted = muted) }

    fun setTrackSolo(project: Project, trackId: String, solo: Boolean): Project =
        updateTrack(project, trackId) { it.copy(solo = solo) }

    fun setRateCorrection(project: Project, trackId: String, clipId: String, rateCorrectionPpm: Int): Project {
        require(rateCorrectionPpm in -ProjectClip.MAX_RATE_CORRECTION_PPM..ProjectClip.MAX_RATE_CORRECTION_PPM) {
            "Rate correction must be within ±${ProjectClip.MAX_RATE_CORRECTION_PPM} ppm."
        }
        require(project.tracks.any { track -> track.id == trackId && track.clips.any { it.id == clipId } }) { "Clip not found." }
        return updateTrack(project, trackId) { track ->
            track.copy(clips = track.clips.map { clip ->
                if (clip.id == clipId) clip.copy(rateCorrectionPpm = rateCorrectionPpm) else clip
            })
        }
    }

    fun undo(project: Project): Project? {
        val snapshot = project.undoHistory.lastOrNull() ?: return null
        return project.copy(
            tracks = snapshot.tracks,
            undoHistory = project.undoHistory.dropLast(1)
        )
    }

    private fun apply(project: Project, tracks: List<ProjectTrack>): Project {
        if (tracks == project.tracks) return project
        require(tracks.size <= Project.MAX_TRACKS) { "A project supports up to ${Project.MAX_TRACKS} tracks." }
        require(tracks.sumOf { it.clips.size } <= Project.MAX_CLIPS) { "A project supports up to ${Project.MAX_CLIPS} clips." }
        require(MultitrackRenderer.frameCount(tracks, project.sampleRate) > 0L) { "A project must contain audio." }
        var history = (project.undoHistory + ProjectArrangementSnapshot(project.tracks)).takeLast(Project.MAX_UNDO)
        var updated = project.copy(tracks = tracks, undoHistory = history)
        // A normal edit measures one serialisation; only an oversized edit trims history and re-measures.
        var size = updated.jsonBytes()
        if (size > Project.MAX_JSON_BYTES) {
            while (history.isNotEmpty() && size > Project.MAX_JSON_BYTES) {
                history = history.drop(1)
                updated = updated.copy(undoHistory = history)
                size = updated.jsonBytes()
            }
            require(size <= Project.MAX_JSON_BYTES) {
                "This edit is too large to save. The current project was kept."
            }
        }
        return updated
    }

    private fun Project.jsonBytes(): Int = toJson().toByteArray(Charsets.UTF_8).size
}

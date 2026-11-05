package com.ottomusic.sketch.core

object ProjectArrangementActions {
    fun addTrack(project: Project, track: ProjectTrack): Project {
        require(project.isArrangement)
        require(project.tracks.none { it.id == track.id }) { "Track already exists." }
        require((project.tracks.sumOf { it.clips.size } + track.clips.size) <= Project.MAX_CLIPS) {
            "An arrangement supports up to ${Project.MAX_CLIPS} clips."
        }
        return apply(project, project.tracks + track)
    }

    fun updateTrack(project: Project, trackId: String, transform: (ProjectTrack) -> ProjectTrack): Project {
        require(project.isArrangement)
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
        val snapshot = project.arrangementUndoHistory.lastOrNull() ?: return null
        val tracks = snapshot.tracks
        val totalFrames = MultitrackRenderer.frameCount(tracks, project.sampleRate)
        if (totalFrames <= 0L) return null
        val start = snapshot.trim.startFrame.coerceAtMost(totalFrames - 1L)
        val end = snapshot.trim.endFrame.coerceIn(start + 1L, totalFrames)
        return project.copy(
            tracks = tracks,
            totalFrames = snapshot.totalFrames.also { require(it == totalFrames) },
            trim = TrimRange(start, end),
            undoHistory = snapshot.trimUndoHistory,
            arrangementUndoHistory = project.arrangementUndoHistory.dropLast(1)
        )
    }

    private fun apply(project: Project, tracks: List<ProjectTrack>): Project {
        require(project.isArrangement)
        if (tracks == project.tracks) return project
        require(tracks.size <= Project.MAX_TRACKS) { "An arrangement supports up to ${Project.MAX_TRACKS} tracks." }
        require(tracks.sumOf { it.clips.size } <= Project.MAX_CLIPS) { "An arrangement supports up to ${Project.MAX_CLIPS} clips." }
        val totalFrames = MultitrackRenderer.frameCount(tracks, project.sampleRate)
        require(totalFrames > 0L) { "An arrangement must contain audio." }
        val wasFullLength = project.trim.startFrame == 0L && project.trim.endFrame == project.totalFrames
        val start = if (wasFullLength) 0L else project.trim.startFrame.coerceAtMost(totalFrames - 1L)
        val end = if (wasFullLength) totalFrames else project.trim.endFrame.coerceIn(start + 1L, totalFrames)
        val snapshot = ProjectArrangementSnapshot(
            project.trim, project.tracks, project.totalFrames, project.undoHistory
        )
        var history = (project.arrangementUndoHistory + snapshot).takeLast(UndoPolicy.MAX_UNDO)
        var updated = project.copy(
            tracks = tracks,
            totalFrames = totalFrames,
            trim = TrimRange(start, end),
            undoHistory = project.undoHistory.map { it.clampTo(totalFrames) },
            arrangementUndoHistory = history
        )
        // A normal edit measures one serialisation; only an oversized edit trims history and re-measures.
        var size = updated.jsonBytes()
        if (size > Project.MAX_JSON_BYTES) {
            while (history.isNotEmpty() && size > Project.MAX_JSON_BYTES) {
                history = history.drop(1)
                updated = updated.copy(arrangementUndoHistory = history)
                size = updated.jsonBytes()
            }
            require(size <= Project.MAX_JSON_BYTES) {
                "This arrangement edit is too large to save. The current project was kept."
            }
        }
        return updated
    }

    private fun Project.jsonBytes(): Int = toJson().toByteArray(Charsets.UTF_8).size

    private fun TrimRange.clampTo(totalFrames: Long): TrimRange {
        val start = startFrame.coerceIn(0L, totalFrames - 1L)
        val end = endFrame.coerceIn(start + 1L, totalFrames)
        return TrimRange(start, end)
    }
}
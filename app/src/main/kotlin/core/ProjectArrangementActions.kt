package com.kanatron.app.core

object ProjectArrangementActions {
    /** The backing as it played during the take, plus the take, as one arrangement. */
    fun overdub(base: Project, take: ProjectClip): Project = base.copy(
        tracks = base.tracks + ProjectTrack("track-overdub", "Overdub", listOf(take.copy(id = "clip-overdub"))),
        channels = maxOf(base.channels, take.source.channels),
        undoHistory = emptyList()
    )
}

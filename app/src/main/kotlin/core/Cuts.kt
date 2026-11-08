package com.kanatron.app.core

enum class TrimEdge { Start, End }

/** Pending cuts on one recording: the source ranges that play back to back, in timeline frames. */
object Cuts {
    fun length(pieces: List<TrimRange>): Long = pieces.sumOf(TrimRange::frameCount)

    /** Only the timeline frames inside [selection] remain. */
    fun keep(pieces: List<TrimRange>, selection: TrimRange): List<TrimRange> =
        slice(pieces, selection.startFrame, selection.endFrame)

    /** The timeline frames inside [selection] are removed and the two sides close up. */
    fun delete(pieces: List<TrimRange>, selection: TrimRange): List<TrimRange> =
        slice(pieces, 0L, selection.startFrame) + slice(pieces, selection.endFrame, length(pieces))

    /**
     * The recording as back-to-back clips the renderer can play or save. Each piece runs on into the audio
     * it was cut from, under the next piece, so the renderer's edge fades crossfade every join.
     */
    fun arrangement(project: Project, pieces: List<TrimRange>): Project {
        val source = project.firstClip.source
        val overlap = MultitrackRenderer.fadeFrames(project.sampleRate).toLong()
        var position = 0L
        val clips = pieces.mapIndexed { index, piece ->
            val next = pieces.getOrNull(index + 1)
            val tail = if (next == null) 0L else minOf(overlap, next.frameCount, source.totalFrames - piece.endFrame)
            ProjectClip("clip-$index", source, position, TrimRange(piece.startFrame, piece.endFrame + tail))
                .also { position += piece.frameCount }
        }
        return project.copy(tracks = listOf(ProjectTrack("track-main", project.name, clips)), undoHistory = emptyList())
    }

    private fun slice(pieces: List<TrimRange>, start: Long, end: Long): List<TrimRange> {
        val result = mutableListOf<TrimRange>()
        var at = 0L
        for (piece in pieces) {
            val from = maxOf(start, at)
            val to = minOf(end, at + piece.frameCount)
            if (from < to) result += TrimRange(piece.startFrame + from - at, piece.startFrame + to - at)
            at += piece.frameCount
        }
        return result
    }
}

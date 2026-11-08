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
    fun arrangement(project: Project, pieces: List<TrimRange>): Project =
        join(project, pieces.map { project.firstClip.source to it })

    /** Ranges of different recordings back to back, at [base]'s sample rate; mono parts play in both channels. */
    fun join(base: Project, pieces: List<Pair<ProjectAudioSource, TrimRange>>): Project {
        val rate = base.sampleRate
        val overlap = MultitrackRenderer.fadeFrames(rate).toLong()
        var position = 0L
        val clips = pieces.mapIndexed { index, (source, piece) ->
            val next = pieces.getOrNull(index + 1)
            val nextFrames = next?.let { (nextSource, range) -> MultitrackRenderer.clipFrameCount(ProjectClip("next", nextSource, 0L, range), rate) } ?: 0L
            val tail = minOf(minOf(overlap, nextFrames) * source.sampleRate / rate, source.totalFrames - piece.endFrame)
            ProjectClip("clip-$index", source, position, TrimRange(piece.startFrame, piece.endFrame + tail))
                .also { position += MultitrackRenderer.clipFrameCount(it.copy(trim = piece), rate) }
        }
        return base.copy(
            channels = clips.maxOf { it.source.channels },
            tracks = listOf(ProjectTrack("track-main", base.name, clips)),
            undoHistory = emptyList()
        )
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

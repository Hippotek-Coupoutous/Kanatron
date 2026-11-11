package com.kanatron.app.core

import kotlin.math.roundToLong

data class AudioSection(val range: TrimRange, val levelSteps: Int = 0, val eq: Eq = Eq.FLAT) {
    init {
        require(levelSteps in 0..OverdubMix.MAX_BALANCE_STEPS)
    }
}

object AudioEdits {
    fun length(sections: List<AudioSection>): Long = sections.fold(0L) { total, section -> Math.addExact(total, section.range.frameCount) }

    fun slice(sections: List<AudioSection>, range: TrimRange): List<AudioSection> {
        range.requireValidFor(length(sections))
        var position = 0L
        return sections.mapNotNull { section ->
            val start = maxOf(position, range.startFrame)
            val end = minOf(position + section.range.frameCount, range.endFrame)
            val offset = section.range.startFrame - position
            position += section.range.frameCount
            if (end > start) section.copy(range = TrimRange(start + offset, end + offset)) else null
        }
    }

    private fun before(sections: List<AudioSection>, range: TrimRange) =
        if (range.startFrame == 0L) emptyList() else slice(sections, TrimRange(0, range.startFrame))

    private fun after(sections: List<AudioSection>, range: TrimRange): List<AudioSection> {
        val end = length(sections)
        return if (range.endFrame == end) emptyList() else slice(sections, TrimRange(range.endFrame, end))
    }

    fun delete(sections: List<AudioSection>, range: TrimRange): List<AudioSection> {
        range.requireValidFor(length(sections))
        require(range.frameCount < length(sections)) { "Keep at least part of the recording. Use Library to delete a recording." }
        return normalized(before(sections, range) + after(sections, range))
    }

    fun repeat(sections: List<AudioSection>, range: TrimRange, times: Int): List<AudioSection> {
        require(times in 1..64) { "Choose between 1 and 64 plays." }
        val selected = slice(sections, range)
        if (times == 1) return sections
        return normalized(before(sections, range) + List(times) { selected }.flatten() + after(sections, range))
    }

    fun mix(sections: List<AudioSection>, range: TrimRange, levelSteps: Int, eq: Eq): List<AudioSection> =
        normalized(before(sections, range) + slice(sections, range).map { it.copy(levelSteps = levelSteps, eq = eq) } + after(sections, range))

    /** Sections that play on through their source with the same settings become one, so every edit stays within the clip limit. */
    private fun normalized(sections: List<AudioSection>): List<AudioSection> {
        val result = mutableListOf<AudioSection>()
        for (section in sections) {
            val previous = result.lastOrNull()
            if (previous != null && previous.range.endFrame == section.range.startFrame && previous.levelSteps == section.levelSteps && previous.eq == section.eq) {
                result[result.lastIndex] = previous.copy(range = TrimRange(previous.range.startFrame, section.range.endFrame))
            } else result += section
        }
        require(result.size <= Project.MAX_CLIPS) { "This edit has too many sections. Save it before adding more." }
        length(result)
        return result
    }

    /**
     * Where the next section does not continue this one's source, this clip runs on into its source under the next,
     * so the renderer's edge fades crossfade the join instead of dipping.
     */
    fun arrangement(project: Project, sections: List<AudioSection>): Project {
        require(sections.isNotEmpty() && sections.size <= Project.MAX_CLIPS)
        val source = project.firstClip.source
        require(source.sampleRate == project.sampleRate && source.channels == project.channels) { "Sections count frames in the project's own format." }
        val fade = MultitrackRenderer.fadeFrames(project.sampleRate).toLong()
        var position = 0L
        val clips = sections.mapIndexed { index, section ->
            val next = sections.getOrNull(index + 1)?.range
            // ponytail: a section under 10 ms or a cut within 5 ms of the source end still dips; neighbour-aware fade lengths if audible.
            val tail = if (next == null || next.startFrame == section.range.endFrame) 0L
                else minOf(fade, next.frameCount, source.totalFrames - section.range.endFrame)
            ProjectClip("section-$index", source, position, TrimRange(section.range.startFrame, section.range.endFrame + tail))
                .mixed(section.levelSteps, section.eq)
                .also { position = Math.addExact(position, section.range.frameCount) }
        }
        return project.copy(tracks = listOf(ProjectTrack("track-main", project.name, clips)))
    }
}

data class MusicalGrid(
    val enabled: Boolean = false,
    val barMillis: Long = 2_000L,
    val beatsPerBar: Int = 4,
    val snap: Boolean = false
) {
    init {
        require(barMillis in 100L..600_000L)
        require(beatsPerBar in 1..16)
    }

    val bpm: Double get() = 60_000.0 * beatsPerBar / barMillis
    fun beatFrames(sampleRate: Int): Double = barMillis * sampleRate / (1_000.0 * beatsPerBar)
    fun snapped(frame: Long, sampleRate: Int): Long {
        if (!enabled || !snap) return frame.coerceAtLeast(0L)
        val beat = beatFrames(sampleRate)
        return ((frame / beat).roundToLong() * beat).roundToLong().coerceAtLeast(0L)
    }
}

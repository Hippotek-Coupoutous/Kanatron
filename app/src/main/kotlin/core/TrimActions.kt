package com.ottomusic.sketch.core

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.ArrayDeque

enum class TrimEdge { Start, End }

/** Frame-exact editing commands. */
class TrimActions(
    initialRange: TrimRange,
    private val totalFrames: Long,
    private val sampleRate: Int,
    private val maxUndo: Int = UndoPolicy.MAX_UNDO,
    initialHistory: List<TrimRange> = emptyList()
) {
    private val history = ArrayDeque<TrimRange>()

    var range: TrimRange = initialRange
        private set

    init {
        initialRange.requireValidFor(totalFrames)
        require(sampleRate > 0)
        require(maxUndo >= 0)
        require(initialHistory.size <= maxUndo)
        initialHistory.forEach { it.requireValidFor(totalFrames) }
        history.addAll(initialHistory)
    }

    fun historySnapshot(): List<TrimRange> = history.toList()

    fun setRange(startFrame: Long, endFrame: Long): TrimRange {
        val start = startFrame.coerceIn(0L, totalFrames - 1L)
        val end = if (start >= endFrame) minOf(totalFrames, start + 1L)
        else endFrame.coerceIn(start + 1L, totalFrames)
        return apply(TrimRange(start, end))
    }

    fun setRangeMs(startMs: BigDecimal, endMs: BigDecimal, editedEdge: TrimEdge? = null): TrimRange = when (editedEdge) {
        TrimEdge.Start -> setStartAtFrame(millisecondsToFrames(startMs))
        TrimEdge.End -> setEndAtFrame(millisecondsToFrames(endMs))
        null -> setRange(millisecondsToFrames(startMs), millisecondsToFrames(endMs))
    }

    /** Moves whichever boundary is currently selected, retaining at least one frame. */
    fun nudge(edge: TrimEdge, frames: Long): TrimRange = when (edge) {
        TrimEdge.Start -> setStartAtFrame(saturatingAdd(range.startFrame, frames))
        TrimEdge.End -> setEndAtFrame(saturatingAdd(range.endFrame, frames))
    }

    fun setStartAtFrame(frame: Long): TrimRange =
        apply(TrimRange(frame.coerceIn(0L, range.endFrame - 1L), range.endFrame))

    fun setEndAtFrame(frame: Long): TrimRange =
        apply(TrimRange(range.startFrame, frame.coerceIn(range.startFrame + 1L, totalFrames)))

    fun undo(): TrimRange? {
        if (history.isEmpty()) return null
        return history.removeLast().also { range = it }
    }

    private fun apply(next: TrimRange): TrimRange {
        if (next != range && maxUndo > 0) {
            if (history.size == maxUndo) history.removeFirst()
            history.addLast(range)
        }
        range = next
        return range
    }

    private fun millisecondsToFrames(milliseconds: BigDecimal): Long {
        if (milliseconds.signum() <= 0) return 0L
        val frames = milliseconds.multiply(BigDecimal.valueOf(sampleRate.toLong()))
            .divide(THOUSAND)
            .setScale(0, RoundingMode.HALF_UP)
        return if (frames >= BigDecimal.valueOf(totalFrames)) totalFrames else frames.longValueExact()
    }

    private fun saturatingAdd(value: Long, delta: Long): Long = when {
        delta > 0L && value > Long.MAX_VALUE - delta -> Long.MAX_VALUE
        delta < 0L && value < Long.MIN_VALUE - delta -> Long.MIN_VALUE
        else -> value + delta
    }

    private companion object {
        val THOUSAND = BigDecimal.valueOf(1_000L)
    }
}
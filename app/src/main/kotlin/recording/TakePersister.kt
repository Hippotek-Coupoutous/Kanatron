package com.ottomusic.sketch.recording

import android.content.Context
import com.ottomusic.sketch.core.OverdubAlignment
import com.ottomusic.sketch.core.OverdubRoute
import com.ottomusic.sketch.core.OverdubTiming
import com.ottomusic.sketch.core.Project
import com.ottomusic.sketch.core.ProjectArrangementActions
import com.ottomusic.sketch.core.ProjectClip
import com.ottomusic.sketch.core.TrimRange
import com.ottomusic.sketch.data.OverdubCalibrationStore
import com.ottomusic.sketch.data.ProjectRepository

internal data class TakeSnapshot(
    val base: Project?,
    val alignment: OverdubAlignment?,
    val routeInvalidated: Boolean,
    val clockDiscontinuous: Boolean,
    val route: OverdubRoute?,
    val lostFocus: Boolean
)

internal data class SavedTake(
    val project: Project?,
    val error: String?,
    val interruptionNotice: String?,
    val alignmentNotice: String?
)

/** Writes a finished take to disk and appends it to its base project; runs on the saving thread. */
internal class TakePersister(private val context: Context) {

    fun persist(take: RecordingTake, snapshot: TakeSnapshot): SavedTake {
        var project: Project? = null
        var alignmentNotice: String? = null
        var error = take.error
        try {
            val repository = ProjectRepository(context)
            val savedTake = repository.saveTake(
                take.id, take.file, take.sampleRate, take.channels, take.frames,
                recovered = take.error != null, silent = take.allSamplesZero
            )
            project = savedTake
            val base = snapshot.base
            if (savedTake != null && base != null) {
                val alignment = snapshot.alignment
                val route = snapshot.route?.takeIf {
                    alignment != null && !snapshot.routeInvalidated && it.routeKey == alignment.routeKey
                }
                val calibration = route?.let { OverdubCalibrationStore(context).calibration(it) }
                val calibratedPlacement = OverdubTiming.placement(
                    alignment?.positionFrames ?: base.trim.startFrame,
                    calibration.takeIf { alignment != null }
                )
                val correction = OverdubTiming.driftCorrectionPpm(
                    snapshot.clockDiscontinuous,
                    alignment?.rateCorrectionPpm
                )
                val safeCorrection = correction?.takeIf { it in -ProjectClip.MAX_RATE_CORRECTION_PPM..ProjectClip.MAX_RATE_CORRECTION_PPM } ?: 0
                val leadInTrim = if (alignment == null || calibratedPlacement.leadInFrames == 0L) 0L else {
                    OverdubTiming.sourceTrimForLeadIn(
                        calibratedPlacement.leadInFrames, savedTake.sampleRate, base.sampleRate, safeCorrection
                    )
                }
                if (leadInTrim >= savedTake.totalFrames) {
                    project = savedTake
                    alignmentNotice = "The take would start before frame zero. Its original WAV was kept in the library without shifting its audio."
                } else {
                    val arrangement = repository.appendRecordedTake(base.id, savedTake.id, calibratedPlacement.positionFrames)
                    var edited = arrangement
                    val track = arrangement.tracks.firstOrNull { candidate ->
                        candidate.clips.any { it.source.wav == savedTake.sourceWav }
                    }
                    val clip = track?.clips?.firstOrNull { it.source.wav == savedTake.sourceWav }
                    if (track != null && clip != null) {
                        if (correction != null && correction in -ProjectClip.MAX_RATE_CORRECTION_PPM..ProjectClip.MAX_RATE_CORRECTION_PPM && correction != 0) {
                            edited = ProjectArrangementActions.setRateCorrection(edited, track.id, clip.id, correction)
                        }
                        if (leadInTrim > 0L) {
                            val safeStart = leadInTrim.coerceAtMost(savedTake.totalFrames - 1L)
                            edited = ProjectArrangementActions.setClipTrim(
                                edited, track.id, clip.id, TrimRange(safeStart, savedTake.totalFrames)
                            )
                        }
                    }
                    project = if (edited == arrangement) arrangement else repository.saveEditState(edited)
                    alignmentNotice = when {
                        snapshot.routeInvalidated ->
                            "The audio route changed. The take was saved at the backing start."
                        alignment == null ->
                            "Timestamps were unavailable. The take was saved at the backing start."
                        else -> null
                    }
                }
            }
            if (project == null) error = error ?: "Could not save recording"
        } catch (failure: Throwable) {
            error = error ?: failure.message ?: "Could not save recording"
            alignmentNotice = if (project != null) {
                "The take was saved in your library, but could not be added to the backing project."
            } else if (failure.message.orEmpty().contains("header does not match", ignoreCase = true)) {
                "The WAV header did not match the captured audio. Its retained partial was kept for recovery at startup."
            } else {
                "The take was not saved in your library. Its retained partial will be checked for recovery at startup."
            }
        }
        val interruptionNotice = if (snapshot.lostFocus && project != null) {
            "Audio focus was lost. Your recording was saved."
        } else null
        return SavedTake(project, error, interruptionNotice, alignmentNotice)
    }
}

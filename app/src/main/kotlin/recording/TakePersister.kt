package com.kanatron.app.recording

import android.content.Context
import com.kanatron.app.core.OverdubAlignment
import com.kanatron.app.core.OverdubRoute
import com.kanatron.app.core.OverdubTiming
import com.kanatron.app.core.Project
import com.kanatron.app.core.ProjectArrangementActions
import com.kanatron.app.core.ProjectClip
import com.kanatron.app.core.TrimRange
import com.kanatron.app.core.firstClip
import com.kanatron.app.data.OverdubCalibrationStore
import com.kanatron.app.data.ProjectRepository

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

/** Writes a finished take to disk and, when overdubbing, flattens it with its backing into a new recording. */
internal class TakePersister(private val context: Context) {

    fun persist(take: RecordingTake, snapshot: TakeSnapshot): SavedTake {
        var project: Project? = null
        var alignmentNotice: String? = null
        var error = take.error
        try {
            val repository = ProjectRepository(context)
            val savedTake = repository.saveTake(
                take.id, take.file, take.sampleRate, take.channels, take.frames,
                silent = take.allSamplesZero
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
                    alignment?.positionFrames ?: 0L,
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
                if (savedTake.silent) {
                    alignmentNotice = "The take was silent, so it was not mixed."
                } else if (leadInTrim >= savedTake.totalFrames) {
                    alignmentNotice = "The take would start before frame zero. It was saved on its own, not mixed."
                } else {
                    val placed = savedTake.firstClip.copy(
                        positionFrames = calibratedPlacement.positionFrames,
                        trim = TrimRange(leadInTrim, savedTake.totalFrames),
                        rateCorrectionPpm = safeCorrection
                    )
                    val mixed = repository.saveRendered(ProjectArrangementActions.overdub(base, placed))
                    if (mixed == null) {
                        alignmentNotice = "The overdub would clip, so it was not mixed. The take was saved on its own."
                    } else {
                        project = mixed
                        // The mix holds the take now; its separate Library entry and WAV go.
                        runCatching { repository.delete(savedTake.id) }
                        alignmentNotice = when {
                            snapshot.routeInvalidated -> "The audio route changed. The take was mixed at the backing start."
                            alignment == null -> "Timestamps were unavailable. The take was mixed at the backing start."
                            else -> null
                        }
                    }
                }
            }
            if (project == null) error = error ?: "Could not save recording"
        } catch (failure: Throwable) {
            alignmentNotice = if (project != null) {
                "The take was saved on its own, but could not be mixed with the backing: ${failure.message ?: "unknown error"}"
            } else {
                error = error ?: failure.message ?: "Could not save recording"
                if (failure.message.orEmpty().contains("header does not match", ignoreCase = true)) {
                    "The WAV header did not match the captured audio. Its retained partial was kept for recovery at startup."
                } else {
                    "The take was not saved in your library. Its retained partial will be checked for recovery at startup."
                }
            }
        }
        val interruptionNotice = if (snapshot.lostFocus && project != null) {
            "Audio focus was lost. Your recording was saved."
        } else null
        return SavedTake(project, error, interruptionNotice, alignmentNotice)
    }
}

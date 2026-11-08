package com.kanatron.app.recording

import android.content.Context
import com.kanatron.app.core.OverdubAlignment
import com.kanatron.app.core.OverdubMix
import com.kanatron.app.core.OverdubRoute
import com.kanatron.app.core.OverdubTiming
import com.kanatron.app.core.Project
import com.kanatron.app.core.ProjectClip
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
    val alignmentNotice: String?,
    val overdub: OverdubMix?
)

/** Writes a finished take to disk and, when overdubbing, places it over its backing for review. */
internal class TakePersister(private val context: Context) {

    fun persist(take: RecordingTake, snapshot: TakeSnapshot): SavedTake {
        var project: Project? = null
        var overdub: OverdubMix? = null
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
                if (savedTake.silent) {
                    alignmentNotice = "The take was silent, so it was not mixed."
                } else if (error != null) {
                    // Saving a mix deletes the take, which must not be the fate of the only copy of a doubtful file.
                    alignmentNotice = "The take did not finish cleanly, so it was not mixed."
                } else {
                    overdub = OverdubMix(base, savedTake.id, savedTake.firstClip.source, calibratedPlacement.offsetFrames, safeCorrection)
                    alignmentNotice = when {
                        snapshot.routeInvalidated -> "The audio route changed. The take was placed at the backing start."
                        alignment == null -> "Timestamps were unavailable. The take was placed at the backing start."
                        else -> null
                    }
                }
            }
            if (project == null) error = error ?: "Could not save recording"
        } catch (failure: Throwable) {
            alignmentNotice = if (project != null) {
                "The take was saved on its own, but could not be placed over the backing: ${failure.message ?: "unknown error"}"
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
        return SavedTake(project, error, interruptionNotice, alignmentNotice, overdub)
    }
}

package com.ottomusic.sketch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ottomusic.sketch.core.OverdubCalibration
import com.ottomusic.sketch.core.OverdubRoute
import com.ottomusic.sketch.core.OverdubTiming
import com.ottomusic.sketch.data.OverdubCalibrationStore
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun OverdubCalibrationScreen(route: OverdubRoute?, onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { OverdubCalibrationStore(context) }
    val saved = remember(route) { route?.let(store::calibration) }
    val scope = rememberCoroutineScope()
    val routeKey = route?.let { "${it.routeKey}|${it.captureSampleRate}|${it.backingSampleRate}|${it.projectSampleRate}" } ?: "no-route"
    var lagText by rememberSaveable(routeKey) { mutableStateOf(saved?.let { millis(it.measuredLagFrames, it.projectSampleRate) } ?: "0") }
    var uncertaintyText by rememberSaveable(routeKey) { mutableStateOf(saved?.let { millis(it.uncertaintyFrames, it.projectSampleRate) } ?: "1") }
    var nudgeText by rememberSaveable(routeKey) { mutableStateOf(saved?.let { millis(it.manualNudgeFrames, it.projectSampleRate) } ?: "0") }
    var driftText by rememberSaveable(routeKey) { mutableStateOf(saved?.measuredDriftCorrectionPpm?.toString().orEmpty()) }
    var status by rememberSaveable(routeKey) { mutableStateOf<String?>(null) }
    var busy by rememberSaveable(routeKey) { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SynthTopBar("Timing calibration", onBack, backLabel = "Record")
        SynthPanel {
            if (route == null) {
                Text("No identified Layer route yet.", color = Synth.text, style = MaterialTheme.typography.titleMedium)
                Text("Run one Layer take with the wired route you intend to use. Audio Sketch will show its route and rates here. Bluetooth timing is not verified.", color = Synth.muted)
            } else {
                Text(route.displayName, color = Synth.text, style = MaterialTheme.typography.titleMedium)
                Text("Capture ${route.captureSampleRate} Hz · backing ${route.backingSampleRate} Hz · project ${route.projectSampleRate} Hz", color = Synth.muted)
                Text(
                    "Play a known marker from the backing through a wired loopback, record it, then compare onset times in an editor. Enter the measured capture-minus-backing lag and uncertainty. If you measure early and late markers, enter their drift. Leave drift blank to use a valid clock estimate when available. Positive ppm speeds up the source. This stores your timing estimate; it does not detect markers. Check timing on your setup.",
                    color = Synth.muted
                )
                MeasurementField("Measured capture lag (ms)", lagText, { lagText = it }, "Signed; positive means capture is late")
                MeasurementField("Measurement uncertainty (ms)", uncertaintyText, { uncertaintyText = it }, "Enter a non-negative value")
                MeasurementField("Manual nudge (ms)", nudgeText, { nudgeText = it }, "Positive moves the next overdub later")
                MeasurementField("Measured drift correction (ppm)", driftText, { driftText = it }, "Optional measured override; blank uses a valid clock estimate when available")
                status?.let { Text(it, color = if (it.startsWith("Saved")) Synth.cyan else MaterialTheme.colorScheme.error) }
                BigButton(
                    "Save route measurement",
                    onClick = {
                        val lag = lagText.toDoubleOrNull()
                        val uncertainty = uncertaintyText.toDoubleOrNull()
                        val nudge = nudgeText.toDoubleOrNull()
                        val drift = driftText.trim().takeIf(String::isNotEmpty)?.toIntOrNull()
                        if (lag == null || uncertainty == null || nudge == null ||
                            !lag.isFinite() || !uncertainty.isFinite() || !nudge.isFinite() ||
                            lag !in -5_000.0..5_000.0 || uncertainty !in 0.0..5_000.0 || nudge !in -5_000.0..5_000.0 ||
                            (driftText.isNotBlank() && (drift == null || drift !in -2_000..2_000))
                        ) {
                            status = "Enter lag/nudge within ±5000 ms, uncertainty from 0 to 5000 ms, and optional drift within ±2000 ppm."
                        } else {
                            val calibration = OverdubCalibration(
                                routeKey = route.routeKey,
                                captureSampleRate = route.captureSampleRate,
                                backingSampleRate = route.backingSampleRate,
                                projectSampleRate = route.projectSampleRate,
                                measuredLagFrames = OverdubTiming.framesFromMilliseconds(lag, route.projectSampleRate),
                                uncertaintyFrames = OverdubTiming.framesFromMilliseconds(uncertainty, route.projectSampleRate).coerceAtLeast(0L),
                                manualNudgeFrames = OverdubTiming.framesFromMilliseconds(nudge, route.projectSampleRate),
                                measuredDriftCorrectionPpm = drift,
                                measuredAtEpochMillis = System.currentTimeMillis()
                            )
                            busy = true
                            scope.launch {
                                try {
                                    val stored = withContext(Dispatchers.IO) { store.save(route, calibration) }
                                    status = if (stored) "Saved for this exact route and rates. Check timing on your setup."
                                    else "Could not save this route measurement."
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (failure: Throwable) {
                                    status = failure.message ?: "Could not save this route measurement."
                                } finally {
                                    busy = false
                                }
                            }
                        }
                    },
                    Modifier.fillMaxWidth(), primary = true, enabled = !busy
                )
                BigButton("Clear saved measurement", {
                    busy = true
                    scope.launch {
                        try {
                            val cleared = withContext(Dispatchers.IO) { store.clear(route) }
                            if (cleared) {
                                lagText = "0"
                                uncertaintyText = "1"
                                nudgeText = "0"
                                driftText = ""
                                status = "Saved route measurement cleared."
                            } else status = "Could not clear the saved route measurement."
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Throwable) {
                            status = failure.message ?: "Could not clear the saved route measurement."
                        } finally {
                            busy = false
                        }
                    }
                }, Modifier.fillMaxWidth(), enabled = !busy)
            }
        }
    }
}

@Composable
private fun MeasurementField(label: String, value: String, onValue: (String) -> Unit, supportingText: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        supportingText = { Text(supportingText) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii)
    )
}

private fun millis(frames: Long, sampleRate: Int): String =
    String.format(Locale.US, "%.3f", frames * 1_000.0 / sampleRate)
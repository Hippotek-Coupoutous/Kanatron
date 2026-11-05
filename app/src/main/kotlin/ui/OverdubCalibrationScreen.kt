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
    var offsetText by rememberSaveable(routeKey) { mutableStateOf(saved?.let { millis(it.offsetFrames, it.projectSampleRate) } ?: "0") }
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
                    "Positive values move new takes later. Adjust by ear until overdubs line up.",
                    color = Synth.muted
                )
                MeasurementField("Timing offset (ms)", offsetText, { offsetText = it }, "Within ±5000 ms")
                status?.let { Text(it, color = if (it.startsWith("Saved")) Synth.cyan else MaterialTheme.colorScheme.error) }
                BigButton(
                    "Save timing offset",
                    onClick = {
                        val offset = offsetText.toDoubleOrNull()
                        if (offset == null || !offset.isFinite() || offset !in -5_000.0..5_000.0) {
                            status = "Enter a timing offset within ±5000 ms."
                        } else {
                            val calibration = OverdubCalibration(
                                routeKey = route.routeKey,
                                captureSampleRate = route.captureSampleRate,
                                backingSampleRate = route.backingSampleRate,
                                projectSampleRate = route.projectSampleRate,
                                offsetFrames = OverdubTiming.framesFromMilliseconds(offset, route.projectSampleRate)
                            )
                            busy = true
                            scope.launch {
                                try {
                                    val stored = withContext(Dispatchers.IO) { store.save(route, calibration) }
                                    status = if (stored) "Saved for this exact route and rates."
                                    else "Could not save this timing offset."
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (failure: Throwable) {
                                    status = failure.message ?: "Could not save this timing offset."
                                } finally {
                                    busy = false
                                }
                            }
                        }
                    },
                    Modifier.fillMaxWidth(), primary = true, enabled = !busy
                )
                BigButton("Clear saved offset", {
                    busy = true
                    scope.launch {
                        try {
                            val cleared = withContext(Dispatchers.IO) { store.clear(route) }
                            if (cleared) {
                                offsetText = "0"
                                status = "Saved timing offset cleared."
                            } else status = "Could not clear the saved timing offset."
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Throwable) {
                            status = failure.message ?: "Could not clear the saved timing offset."
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
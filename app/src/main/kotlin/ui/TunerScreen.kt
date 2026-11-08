package com.kanatron.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.kanatron.app.core.TunerPitchReading
import com.kanatron.app.platform.PlatformPorts
import com.kanatron.app.tuner.TunerAudioSession
import com.kanatron.app.tuner.playInTuneBeep

@Composable
fun TunerScreen(
    microphoneGranted: Boolean,
    microphoneDenied: Boolean,
    microphoneBlockedReason: String?,
    lifecycle: Lifecycle,
    onRequestMicrophone: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    val session = androidx.compose.runtime.remember(context) {
        TunerAudioSession(PlatformPorts.pcmInputFactory(context))
    }
    val audio by session.state.collectAsState()
    var a4Hz by rememberSaveable { mutableStateOf(440) }
    var foreground by remember(lifecycle) {
        mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }

    DisposableEffect(lifecycle, session) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> foreground = true
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                    foreground = false
                    session.stop()
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            session.stop()
        }
    }
    LaunchedEffect(session, a4Hz) { session.setA4Frequency(a4Hz.toDouble()) }
    val microphoneBusy = microphoneBlockedReason != null
    val canListen = microphoneGranted && !microphoneBusy && foreground
    LaunchedEffect(session, canListen) {
        if (canListen) session.start() else session.stop()
    }

    val reading = audio.reading
    LaunchedEffect(audio.inTune) {
        if (audio.inTune && reading != null) playInTuneBeep(reading.targetHz)
    }
    val status = when {
        microphoneBusy -> microphoneBlockedReason.orEmpty()
        !microphoneGranted -> if (microphoneDenied) "Microphone access is off." else "Allow microphone access to tune."
        audio.error != null -> audio.error.orEmpty()
        !foreground -> "Tuner pauses in the background."
        reading == null -> "Play a note."
        else -> when {
            audio.inTune -> "In tune"
            reading.cents < 0.0 -> "Flat"
            else -> "Sharp"
        } + if (audio.holding) " – holding" else ""
    }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        SynthTopBar("Tuner")

        SynthPanel(Modifier.fillMaxWidth()) {
            Text(
                reading?.note ?: "—",
                modifier = Modifier.fillMaxWidth().semantics {
                    contentDescription = reading?.let { if (audio.holding) "Held note ${it.note}" else "Detected note ${it.note}" } ?: "No stable note detected"
                },
                color = Synth.cyan,
                style = MaterialTheme.typography.displayLarge,
                fontSize = 96.sp,
                lineHeight = 100.sp,
                textAlign = TextAlign.Center
            )
            Text(
                reading?.let { "${formatFrequency(it.frequencyHz)} Hz" } ?: "— Hz",
                modifier = Modifier.fillMaxWidth(),
                color = Synth.text,
                fontSize = 26.sp,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center
            )
            TunerMeter(reading)
            Text(
                status,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = status },
                color = if (audio.inTune) Synth.cyan else Synth.text,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )
            if (reading != null) {
                Text(
                    "${formatCents(reading.cents)} cents",
                    modifier = Modifier.fillMaxWidth(),
                    color = Synth.muted,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center
                )
            }
        }

        SynthPanel(Modifier.fillMaxWidth()) {
            Text("A4 reference", style = MaterialTheme.typography.titleMedium, color = Synth.text)
            Stepper(
                value = "$a4Hz Hz",
                onMinus = { a4Hz = (a4Hz - 1).coerceAtLeast(400) },
                onPlus = { a4Hz = (a4Hz + 1).coerceAtMost(480) },
                minusLabel = "A4 down 1 hertz",
                plusLabel = "A4 up 1 hertz"
            )
        }

        when {
            microphoneBusy -> Unit
            !microphoneGranted -> {
                if (microphoneDenied) Key("App settings", onOpenSettings, Modifier.fillMaxWidth(), description = "Open app settings")
                Key("Allow microphone", onRequestMicrophone, Modifier.fillMaxWidth(), face = Synth.yellow)
            }
            audio.error != null -> Key("Try again", { session.start() }, Modifier.fillMaxWidth(), face = Synth.yellow)
        }
    }
}

@Composable
private fun TunerMeter(reading: TunerPitchReading?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Flat", color = Synth.muted, style = MaterialTheme.typography.labelLarge)
        Canvas(
            Modifier.weight(1f).height(64.dp).semantics {
                contentDescription = reading?.let { "${formatCents(it.cents)} cents from pitch center" } ?: "No stable pitch"
            }
        ) {
            val center = size.width / 2f
            val middle = size.height / 2f
            drawLine(Synth.line, androidx.compose.ui.geometry.Offset(0f, middle), androidx.compose.ui.geometry.Offset(size.width, middle), 2.dp.toPx())
            for (tick in -5..5) {
                val x = center + center * tick / 5f
                val height = if (tick == 0) 28.dp.toPx() else if (tick % 2 == 0) 18.dp.toPx() else 10.dp.toPx()
                drawLine(
                    if (tick == 0) Synth.yellow else Synth.muted,
                    androidx.compose.ui.geometry.Offset(x, middle - height / 2f),
                    androidx.compose.ui.geometry.Offset(x, middle + height / 2f),
                    2.dp.toPx(),
                    StrokeCap.Round
                )
            }
            reading?.let {
                val x = center + center * (it.cents.coerceIn(-50.0, 50.0) / 50.0).toFloat()
                drawLine(Synth.pink, androidx.compose.ui.geometry.Offset(x, 2.dp.toPx()), androidx.compose.ui.geometry.Offset(x, size.height - 2.dp.toPx()), 4.dp.toPx(), StrokeCap.Round)
            }
        }
        Text("Sharp", color = Synth.muted, style = MaterialTheme.typography.labelLarge)
    }
}

private fun formatFrequency(frequency: Double) = String.format(java.util.Locale.ROOT, "%.1f", frequency)
private fun formatCents(cents: Double) = String.format(java.util.Locale.ROOT, "%+.1f", cents)
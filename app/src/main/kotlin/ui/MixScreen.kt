package com.ottomusic.sketch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ottomusic.sketch.core.MultitrackRenderer
import com.ottomusic.sketch.core.Project
import com.ottomusic.sketch.data.ProjectRepository
import com.ottomusic.sketch.data.ImportActivePaths
import com.ottomusic.sketch.playback.WavPlayer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun MixScreen(
    projects: List<Project>,
    repository: ProjectRepository,
    onBack: () -> Unit,
    onSaved: (Project) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val ordered = remember(projects) { projects.sortedByDescending { it.createdAtEpochMillis } }
    var idA by rememberSaveable { mutableStateOf(ordered.firstOrNull()?.id) }
    var idB by rememberSaveable { mutableStateOf(ordered.getOrNull(1)?.id) }
    var offsetMs by rememberSaveable { mutableStateOf(0) }
    var gainA by rememberSaveable { mutableStateOf(1f) }
    var gainB by rememberSaveable { mutableStateOf(1f) }

    var error by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var player by remember { mutableStateOf<WavPlayer?>(null) }
    var previewFile by remember { mutableStateOf<File?>(null) }
    var activeRender by remember { mutableStateOf<AtomicBoolean?>(null) }

    val takeA = ordered.firstOrNull { it.id == idA } ?: ordered.firstOrNull()
    val takeB = ordered.firstOrNull { it.id == idB && it.id != takeA?.id }
        ?: ordered.firstOrNull { it.id != takeA?.id }
    val ready = takeA != null && takeB != null

    val current = player
    val isPlaying = if (current != null) current.state.collectAsState().value.isPlaying else false

    fun stopPreview() {
        val p = player
        player = null
        if (p != null) {
            p.stop()
            p.release()
        }
        previewFile?.delete()
        previewFile = null
    }

    // Any input change drops the stale preview.
    fun update(change: () -> Unit) {
        if (busy) return
        stopPreview()
        error = null
        note = null
        change()
    }

    fun render(save: Boolean) {
        val a = takeA ?: return
        val b = takeB ?: return
        stopPreview()
        error = null
        note = null
        busy = true
        val cancel = AtomicBoolean(false)
        activeRender = cancel
        val mix = MultitrackRenderer.twoTakeMix(
            a, b, (offsetMs.toDouble() * a.sampleRate / 1000.0).roundToLong(),
            (gainA * 1000).roundToInt(), (gainB * 1000).roundToInt()
        )
        scope.launch {
            var output: File? = null
            try {
                val (result, saved) = withContext(Dispatchers.IO) {
                    val id = UUID.randomUUID().toString()
                    val file = if (save) repository.importOutput(id)
                        else File.createTempFile("mix-preview-", ".wav", context.cacheDir)
                    output = file
                    if (save) ImportActivePaths.beginOutput(file)
                    try {
                        val result = repository.renderMix(a, b, mix, file, cancel)
                        val saved = if (save) repository.saveImported(
                            id, file, result.sampleRate, result.channels, result.frames,
                            "Mix " + SimpleDateFormat("MMM d HH:mm", Locale.getDefault()).format(Date())
                        ) else null
                        result to saved
                    } finally {
                        if (save) ImportActivePaths.finishOutput(file)
                    }
                }
                note = if (result.clippedSamples > 0) "Some peaks clipped: lower a level." else null
                if (saved != null) onSaved(saved) else {
                    previewFile = output
                    player = WavPlayer(context, output!!).also { it.play() }
                    output = null
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                error = failure.message ?: "Could not build the mix."
            } finally {
                output?.delete()
                activeRender = null
                busy = false
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            activeRender?.set(true)
            stopPreview()
        }
    }

    val errText = error
    val noteText = note

    Box(Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SynthTopBar("Mix", onBack)

        if (ordered.size < 2) {
            Text("Record or import two takes first.")
        }

        SynthPanel {
            Text("First take", style = MaterialTheme.typography.labelLarge)
            TakePicker(takeA, ordered) { p -> update { idA = p.id } }
            Slider(
                value = gainA,
                onValueChange = { v -> update { gainA = v } },
                valueRange = 0f..1.5f,
                modifier = Modifier.semantics { contentDescription = "First take level" },
            )
        }

        SynthPanel {
            Text("Second take", style = MaterialTheme.typography.labelLarge)
            TakePicker(takeB, ordered) { p -> update { idB = p.id } }
            Slider(
                value = gainB,
                onValueChange = { v -> update { gainB = v } },
                valueRange = 0f..1.5f,
                modifier = Modifier.semantics { contentDescription = "Second take level" },
            )
            Stepper(
                value = "$offsetMs ms",
                onMinus = { update { offsetMs -= 10 } },
                onPlus = { update { offsetMs += 10 } },
                minusLabel = "Second take 10 ms earlier",
                plusLabel = "Second take 10 ms later",
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            BigButton(
                text = if (isPlaying) "Stop" else "Preview",
                onClick = { if (isPlaying) stopPreview() else render(save = false) },
                modifier = Modifier.weight(1f),
                enabled = ready && !busy,
            )
            BigButton(
                text = if (busy) "Working…" else "Save mix",
                onClick = { render(save = true) },
                modifier = Modifier.weight(1f),
                primary = true,
                enabled = ready && !busy,
            )
        }

        if (errText != null) {
            Text(errText, color = MaterialTheme.colorScheme.error)
        }
        if (noteText != null) {
            Text(noteText)
        }
    }
    }
}

@Composable
private fun TakePicker(selected: Project?, projects: List<Project>, onPick: (Project) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { open = true },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
        ) {
            Text(
                text = selected?.name ?: "Select take",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            projects.forEach { p ->
                DropdownMenuItem(
                    text = { Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = {
                        open = false
                        onPick(p)
                    },
                )
            }
        }
    }
}
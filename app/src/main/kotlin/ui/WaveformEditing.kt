package com.kanatron.app.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kanatron.app.core.MusicalGrid
import com.kanatron.app.core.Peak
import com.kanatron.app.core.TrimRange
import java.io.File
import kotlin.math.roundToLong

internal fun DrawScope.drawMusicalGrid(grid: MusicalGrid, rate: Int, from: Long, to: Long) {
    if (!grid.enabled || to <= from) return
    val beat = grid.beatFrames(rate)
    val count = (to - from) / beat
    val step = if (count > 160) grid.beatsPerBar.toLong() * kotlin.math.ceil(count / (160 * grid.beatsPerBar)).toLong() else 1L
    val first = (kotlin.math.floor(from.coerceAtLeast(0L) / beat).toLong() / step) * step
    val last = kotlin.math.ceil(to / beat).toLong()
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = Synth.muted.toArgb()
        textSize = 12.dp.toPx()
    }
    var index = first
    while (index <= last) {
        val x = ((index * beat - from) / (to - from) * size.width).toFloat()
        val bar = index % grid.beatsPerBar == 0L
        drawLine(if (bar) Synth.violet.copy(alpha = 0.6f) else Synth.line, Offset(x, 0f), Offset(x, size.height), if (bar) 1.5.dp.toPx() else 1.dp.toPx())
        if (bar) drawContext.canvas.nativeCanvas.drawText("${index / grid.beatsPerBar + 1}", x + 4.dp.toPx(), 15.dp.toPx(), paint)
        index += step
    }
}

@Stable
internal class WaveformSelection(initial: TrimRange, length: Long) {
    var start by mutableStateOf(initial.startFrame)
    var end by mutableStateOf(initial.endFrame)
    var viewStart by mutableStateOf(0L)
    var viewEnd by mutableStateOf(length)
    var selecting by mutableStateOf(false)
    var confirmed by mutableStateOf(true)
    var edge by mutableStateOf(TrimEdge.Start)

    fun range(length: Long) = TrimRange(start.coerceIn(0L, length - 1), end.coerceIn(start.coerceIn(0L, length - 1) + 1, length))

    fun select(range: TrimRange) {
        start = range.startFrame
        end = range.endFrame
        confirmed = false
    }

    fun setEdge(target: TrimEdge, frame: Long, length: Long) {
        val current = range(length)
        edge = target
        select(if (target == TrimEdge.Start) TrimRange(frame.coerceIn(0L, current.endFrame - 1), current.endFrame)
        else TrimRange(current.startFrame, frame.coerceIn(current.startFrame + 1, length)))
    }

    fun reset(length: Long) {
        select(TrimRange(0L, length))
        confirmed = true
        selecting = false
    }

    fun fit(range: TrimRange) {
        viewStart = range.startFrame
        viewEnd = range.endFrame
    }

    fun resize(length: Long) {
        val range = range(length)
        start = range.startFrame
        end = range.endFrame
        val span = (viewEnd - viewStart).coerceIn(1L, length)
        viewStart = viewStart.coerceIn(0L, length - span)
        viewEnd = viewStart + span
    }

    fun zoom(factor: Double, anchor: Long, length: Long) {
        val previous = (viewEnd - viewStart).coerceAtLeast(1L)
        val span = (previous * factor).roundToLong().coerceIn(minOf(64L, length), length)
        viewStart = (anchor - (anchor - viewStart) * span / previous).coerceIn(0L, length - span)
        viewEnd = viewStart + span
    }

    companion object {
        val Saver = listSaver<WaveformSelection, Any>(
            save = { listOf(it.start, it.end, it.viewStart, it.viewEnd, it.selecting, it.confirmed, it.edge.name) },
            restore = {
                WaveformSelection(TrimRange(it[0] as Long, it[1] as Long), it[3] as Long).apply {
                    viewStart = it[2] as Long
                    selecting = it[4] as Boolean
                    confirmed = it[5] as Boolean
                    edge = TrimEdge.valueOf(it[6] as String)
                }
            }
        )
    }
}

@Composable
internal fun rememberWaveformSelection(key: String, length: Long, initial: TrimRange = TrimRange(0, length)): WaveformSelection {
    val selection = rememberSaveable(key, saver = WaveformSelection.Saver) { WaveformSelection(initial, length) }
    LaunchedEffect(length) { selection.resize(length) }
    return selection
}

@Composable
internal fun WaveformSelectionEditor(
    title: String,
    file: File?,
    peaks: List<Peak>,
    length: Long,
    rate: Int,
    selection: WaveformSelection,
    cursor: Long?,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    grid: MusicalGrid = MusicalGrid(),
    gridOffset: Long = 0L,
    startExpanded: Boolean = false,
    onClose: (() -> Unit)? = null,
    onPositionNudge: ((Long) -> Unit)? = null,
    transport: @Composable () -> Unit = {}
) {
    var expanded by rememberSaveable { mutableStateOf(startExpanded) }
    var menu by remember { mutableStateOf(false) }
    var nudge by rememberSaveable { mutableStateOf(false) }
    var dragging by remember { mutableStateOf<TrimEdge?>(null) }
    val range = selection.range(length)
    val span = (selection.viewEnd - selection.viewStart).coerceIn(1L, length)
    val from = selection.viewStart.coerceIn(0L, length - span)
    val to = from + span

    val landscape = LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val waveformPanel: @Composable (Boolean, Modifier) -> Unit = { large, panelModifier ->
        SynthPanel(panelModifier) {
            if (large) Text(title, style = MaterialTheme.typography.bodySmall, color = Synth.muted, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(
                if (range.frameCount == length) "Whole track · ${clockMillis(length, rate)}"
                else "${clockMillis(range.startFrame, rate)} – ${clockMillis(range.endFrame, rate)} · ${clockMillis(range.frameCount, rate)}",
                style = MaterialTheme.typography.bodyMedium
            )
            Overview(peaks, length, from, to, range, Modifier.fillMaxWidth(), height = if (large) 32.dp else 24.dp, onLongPress = { expanded = true }) { center ->
                selection.viewStart = (center - span / 2).coerceIn(0L, length - span)
                selection.viewEnd = selection.viewStart + span
            }
            Box(if (large) Modifier.fillMaxWidth().weight(1f) else Modifier.fillMaxWidth().height(88.dp)) {
                Waveform(
                    file, peaks, length, rate, from, to, range, cursor, dragging,
                    onEdgeDrag = { edge, frame ->
                        if (enabled) {
                            dragging = edge
                            if (edge != null) selection.setEdge(edge, grid.snapped(frame + gridOffset, rate) - gridOffset, length)
                        }
                    },
                    onZoom = { factor, anchor -> selection.zoom(factor, anchor, length) },
                    onTap = onSeek,
                    selecting = selection.selecting && enabled,
                    onSelection = { chosen ->
                        val first = (grid.snapped(chosen.startFrame + gridOffset, rate) - gridOffset).coerceIn(0, length - 1)
                        val last = (grid.snapped(chosen.endFrame + gridOffset, rate) - gridOffset).coerceIn(first + 1, length)
                        selection.select(TrimRange(first, last))
                    },
                    onLongPress = { expanded = true },
                    grid = grid,
                    gridOffset = gridOffset,
                    handlesEnabled = enabled
                )
            }
            Text(
                if (selection.selecting) "Drag across the waveform, then confirm."
                else if (!selection.confirmed) "Confirm this selection to use the editing tools."
                else if (large) "Drag a handle to adjust. Pinch to zoom."
                else "Long press for full screen. Pinch to zoom.",
                color = Synth.muted, style = MaterialTheme.typography.bodySmall
            )
        }
    }
    val selectionControls: @Composable (Boolean) -> Unit = { large ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Key(if (!large && !selection.confirmed) "Confirm" else "Select", {
                if (!large && !selection.confirmed) { selection.confirmed = true; selection.selecting = false }
                else { selection.selecting = true; selection.confirmed = false }
            }, Modifier.weight(1f), selected = selection.selecting, enabled = enabled, height = KeyHeight.Small,
                face = if (!large && !selection.confirmed) Synth.cyan else Synth.surface)
            if (large) Key("Confirm", { selection.confirmed = true; selection.selecting = false }, Modifier.weight(1f), enabled = enabled && !selection.confirmed, face = Synth.cyan, height = KeyHeight.Small)
            Key("Reset", { selection.reset(length) }, Modifier.weight(1f), enabled = enabled, height = KeyHeight.Small)
            if (!large) Box(Modifier.weight(1f)) {
                Key("More", { menu = true }, Modifier.fillMaxWidth(), height = KeyHeight.Small, enabled = enabled)
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Full-screen waveform") }, onClick = { menu = false; expanded = true })
                    DropdownMenuItem(text = { Text("Nudge…") }, onClick = { menu = false; nudge = true })
                    DropdownMenuItem(text = { Text("Fit selection") }, onClick = { menu = false; selection.fit(range) })
                    DropdownMenuItem(text = { Text("Fit track") }, onClick = { menu = false; selection.fit(TrimRange(0, length)) })
                }
            }
        }
    }
    val precisionControls: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Key("Fit selection", { selection.fit(range) }, Modifier.weight(1f), height = KeyHeight.Small)
            Key("Fit track", { selection.fit(TrimRange(0, length)) }, Modifier.weight(1f), height = KeyHeight.Small)
            Key("Nudge", { nudge = true }, Modifier.weight(1f), height = KeyHeight.Small, enabled = enabled)
        }
    }
    val content: @Composable (Boolean) -> Unit = { large ->
        if (large && landscape) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                waveformPanel(true, Modifier.weight(1f).fillMaxHeight())
                Column(Modifier.width(300.dp).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    selectionControls(true)
                    precisionControls()
                    transport()
                }
            }
        } else Column(if (large) Modifier.fillMaxSize() else modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            waveformPanel(large, if (large) Modifier.weight(1f) else Modifier)
            selectionControls(large)
            if (large) { precisionControls(); transport() }
        }
    }

    if (!startExpanded) content(false)
    if (expanded) Dialog(
        onDismissRequest = { expanded = false; onClose?.invoke() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        KanatronTheme {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SynthTopBar("Waveform", { expanded = false; onClose?.invoke() }, backLabel = "Done")
                content(true)
            }
        }
    }
    if (nudge) NudgeDialog(rate, if (onPositionNudge == null) listOf("Start", "End") else listOf("Start", "End", "Position"), selection.edge.ordinal,
        position = { target -> when (target) { 0 -> range.startFrame; 1 -> range.endFrame; else -> null } },
        onMove = { target, delta ->
            if (target == 2) onPositionNudge?.invoke(delta)
            else {
                val edge = TrimEdge.entries[target]
                selection.setEdge(edge, (if (edge == TrimEdge.Start) range.startFrame else range.endFrame) + delta, length)
            }
        }, onDismiss = { nudge = false })
}

@Composable
internal fun NudgeDialog(rate: Int, targets: List<String>, initialTarget: Int = 0, position: (Int) -> Long? = { null }, onMove: (Int, Long) -> Unit, onDismiss: () -> Unit) {
    val preferences = LocalContext.current.getSharedPreferences("waveform-editing", Context.MODE_PRIVATE)
    var target by rememberSaveable { mutableStateOf(initialTarget) }
    var frames by rememberSaveable { mutableStateOf(preferences.getBoolean("nudge-frames", false)) }
    var amount by rememberSaveable { mutableStateOf(preferences.getString("nudge-amount", "10").orEmpty()) }
    val number = amount.replace(',', '.').toDoubleOrNull()
    val valid = number != null && number.isFinite() && number > 0 && number <= 10_000_000 && (!frames || number % 1.0 == 0.0)
    val step = if (valid) (number * if (frames) 1.0 else rate / 1_000.0).roundToLong().coerceAtLeast(1L) else 0L
    fun move(direction: Int) {
        preferences.edit().putBoolean("nudge-frames", frames).putString("nudge-amount", amount).apply()
        onMove(target, step * direction)
    }
    fun dismiss() {
        if (valid) preferences.edit().putBoolean("nudge-frames", frames).putString("nudge-amount", amount).apply()
        onDismiss()
    }
    AlertDialog(onDismissRequest = ::dismiss, title = { Text("Nudge") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                targets.forEachIndexed { index, label -> Key(label, { target = index }, Modifier.weight(1f), selected = target == index, height = KeyHeight.Small) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Key("ms", { frames = false }, Modifier.weight(1f), selected = !frames, height = KeyHeight.Small)
                Key("Frames", { frames = true }, Modifier.weight(1f), selected = frames, height = KeyHeight.Small)
            }
            position(target)?.let { Text("Frame $it · ${clockMillis(it, rate)}", style = MaterialTheme.typography.bodySmall) }
            OutlinedTextField(amount, { amount = it }, label = { Text("Move by ${if (frames) "frames" else "milliseconds"}") }, singleLine = true,
                isError = !valid, keyboardOptions = KeyboardOptions(keyboardType = if (frames) KeyboardType.Number else KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Key("−$amount ${if (frames) "frames" else "ms"}", { move(-1) }, Modifier.weight(1f), enabled = valid, height = KeyHeight.Small)
                Key("+$amount ${if (frames) "frames" else "ms"}", { move(1) }, Modifier.weight(1f), enabled = valid, height = KeyHeight.Small)
            }
        }
    }, confirmButton = { Key("Done", ::dismiss, Modifier.fillMaxWidth(), face = Synth.cyan, height = KeyHeight.Small) })
}

@Composable
internal fun GridDialog(grid: MusicalGrid, onApply: (MusicalGrid) -> Unit, onDismiss: () -> Unit) {
    var enabled by rememberSaveable { mutableStateOf(grid.enabled) }
    var snap by rememberSaveable { mutableStateOf(grid.snap) }
    var beats by rememberSaveable { mutableStateOf(grid.beatsPerBar.toString()) }
    var duration by rememberSaveable { mutableStateOf((grid.barMillis / 1_000.0).toString()) }
    var tempo by rememberSaveable { mutableStateOf("%.2f".format(java.util.Locale.US, grid.bpm)) }
    val millis = duration.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.1..600.0 }?.let { (it * 1_000).roundToLong() }
    val count = beats.toIntOrNull()?.takeIf { it in 1..16 }
    val valid = millis != null && count != null
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Musical grid") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Key("Show grid", { enabled = !enabled }, Modifier.weight(1f), selected = enabled, height = KeyHeight.Small)
                Key("Snap", { snap = !snap }, Modifier.weight(1f), selected = snap, height = KeyHeight.Small)
            }
            OutlinedTextField(duration, {
                duration = it
                val seconds = it.replace(',', '.').toDoubleOrNull()
                if (seconds != null && seconds > 0 && count != null) tempo = "%.2f".format(java.util.Locale.US, 60.0 * count / seconds)
            }, label = { Text("Seconds per bar") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            OutlinedTextField(beats, {
                beats = it
                val beatCount = it.toIntOrNull()
                if (millis != null && beatCount != null) tempo = "%.2f".format(java.util.Locale.US, 60_000.0 * beatCount / millis)
            }, label = { Text("Beats per bar (1–16)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(tempo, {
                tempo = it
                val bpm = it.replace(',', '.').toDoubleOrNull()
                if (bpm != null && bpm.isFinite() && bpm > 0 && count != null) duration = (60.0 * count / bpm).toString()
                else duration = ""
            }, label = { Text("Beats per minute") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            Text("Bar 1 starts at 0:00. Snapping uses beat boundaries.", style = MaterialTheme.typography.bodySmall)
            if (!valid) Text("Choose 0.1–600 seconds per bar and 1–16 beats.", color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Key("Cancel", onDismiss, Modifier.weight(1f), height = KeyHeight.Small)
            Key("Apply", { onApply(MusicalGrid(enabled, millis!!, count!!, snap)); onDismiss() }, Modifier.weight(1f), face = Synth.cyan, enabled = valid, height = KeyHeight.Small)
        }
    })
}

enum class TrimEdge { Start, End }

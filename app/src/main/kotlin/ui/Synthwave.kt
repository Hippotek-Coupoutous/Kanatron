package com.ottomusic.sketch.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Glyphs drawn on a 24-unit grid, one stroke weight, so no icon dependency is needed. */
enum class SynthIcon { Play, Pause, Stop, Rec, Import, Share, Click, Layer, Trim, Mix, Back, Edit, Delete, Library, Sketch, Tuner }

@Composable
fun SynthGlyph(icon: SynthIcon, tint: Color, modifier: Modifier = Modifier.size(28.dp)) {
    Canvas(modifier) { drawGlyph(icon, tint) }
}

private fun DrawScope.drawGlyph(icon: SynthIcon, tint: Color) {
    val u = size.minDimension / 24f
    fun p(x: Float, y: Float) = Offset(x * u, y * u)
    val stroke = Stroke(width = 2f * u, cap = StrokeCap.Round)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(tint, p(x1, y1), p(x2, y2), 2f * u, StrokeCap.Round)
    when (icon) {
        SynthIcon.Play -> drawPath(Path().apply { moveTo(7 * u, 4.5f * u); lineTo(7 * u, 19.5f * u); lineTo(19.5f * u, 12 * u); close() }, tint)
        SynthIcon.Pause -> { drawRect(tint, p(7f, 4f), Size(3.5f * u, 16 * u)); drawRect(tint, p(13.5f, 4f), Size(3.5f * u, 16 * u)) }
        SynthIcon.Stop -> drawRoundRect(tint, p(5.5f, 5.5f), Size(13 * u, 13 * u), androidx.compose.ui.geometry.CornerRadius(2 * u))
        SynthIcon.Rec -> drawCircle(tint, 7 * u, p(12f, 12f))
        SynthIcon.Import -> { line(12f, 3f, 12f, 15f); line(12f, 15f, 7.5f, 10.5f); line(12f, 15f, 16.5f, 10.5f); line(4f, 15f, 4f, 20f); line(4f, 20f, 20f, 20f); line(20f, 20f, 20f, 15f) }
        SynthIcon.Share -> { drawCircle(tint, 2.5f * u, p(18f, 5f), style = stroke); drawCircle(tint, 2.5f * u, p(6f, 12f), style = stroke); drawCircle(tint, 2.5f * u, p(18f, 19f), style = stroke); line(8.2f, 10.8f, 15.8f, 6.4f); line(8.2f, 13.2f, 15.8f, 17.6f) }
        SynthIcon.Click -> { drawPath(Path().apply { moveTo(9 * u, 3 * u); lineTo(15 * u, 3 * u); lineTo(19 * u, 21 * u); lineTo(5 * u, 21 * u); close() }, tint, style = stroke); line(12f, 15f, 17f, 6f); line(8f, 17f, 16f, 17f) }
        SynthIcon.Layer -> { drawArc(tint, 180f, 180f, false, p(4f, 4f), Size(16 * u, 16 * u), style = stroke); drawRoundRect(tint, p(3f, 13f), Size(4 * u, 7 * u), androidx.compose.ui.geometry.CornerRadius(1.5f * u)); drawRoundRect(tint, p(17f, 13f), Size(4 * u, 7 * u), androidx.compose.ui.geometry.CornerRadius(1.5f * u)) }
        SynthIcon.Trim -> { drawCircle(tint, 3 * u, p(6f, 6f), style = stroke); drawCircle(tint, 3 * u, p(6f, 18f), style = stroke); line(8.5f, 7.5f, 20f, 18f); line(8.5f, 16.5f, 20f, 6f) }
        SynthIcon.Mix -> {
            for (y0 in listOf(8f, 16f)) drawPath(Path().apply {
                moveTo(3 * u, y0 * u); cubicTo(6 * u, y0 * u, 6 * u, (y0 - 4) * u, 9 * u, (y0 - 4) * u)
                cubicTo(12 * u, (y0 - 4) * u, 12 * u, (y0 + 4) * u, 15 * u, (y0 + 4) * u)
                cubicTo(18 * u, (y0 + 4) * u, 18 * u, y0 * u, 21 * u, y0 * u)
            }, tint, style = stroke)
        }
        SynthIcon.Back -> { line(15f, 5f, 8f, 12f); line(8f, 12f, 15f, 19f) }
        SynthIcon.Edit -> { drawPath(Path().apply { moveTo(4 * u, 20 * u); lineTo(8 * u, 20 * u); lineTo(19 * u, 9 * u); lineTo(15 * u, 5 * u); lineTo(4 * u, 16 * u); close() }, tint, style = stroke) }
        SynthIcon.Delete -> { line(4f, 7f, 20f, 7f); line(9f, 7f, 10f, 3.5f); line(10f, 3.5f, 14f, 3.5f); line(14f, 3.5f, 15f, 7f); drawPath(Path().apply { moveTo(6 * u, 7 * u); lineTo(7 * u, 20 * u); lineTo(17 * u, 20 * u); lineTo(18 * u, 7 * u) }, tint, style = stroke) }
        SynthIcon.Library -> for (y in listOf(6f, 12f, 18f)) { drawCircle(tint, 1.4f * u, p(4.5f, y)); line(8.5f, y, 20.5f, y) }
        SynthIcon.Sketch -> drawPath(Path().apply {
            moveTo(2.5f * u, 12 * u); lineTo(6 * u, 12 * u); lineTo(8.5f * u, 5 * u)
            lineTo(12 * u, 19 * u); lineTo(15.5f * u, 8.5f * u); lineTo(18 * u, 12 * u); lineTo(21.5f * u, 12 * u)
        }, tint, style = stroke)
        SynthIcon.Tuner -> {
            line(8f, 3f, 8f, 10f); line(16f, 3f, 16f, 10f)
            drawArc(tint, 0f, 180f, false, p(8f, 4f), Size(8 * u, 12 * u), style = stroke)
            line(12f, 16f, 12f, 21f)
        }
    }
}

/** Big circular transport button (record, play) with its short label underneath; the whole column is the target. */
@Composable
fun RoundButton(
    icon: SynthIcon,
    label: String,
    color: Color,
    onClick: () -> Unit,
    size: Dp,
    enabled: Boolean = true,
    contentColor: Color = Synth.night,
    description: String = label,
    state: String? = null
) {
    Column(
        Modifier.clip(RoundedCornerShape(24.dp)).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = description
                state?.let { stateDescription = it }
            }
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(size).shadow(if (enabled) 14.dp else 0.dp, CircleShape, ambientColor = color, spotColor = color)
                .clip(CircleShape).background(if (enabled) color else Synth.surfaceHi),
            contentAlignment = Alignment.Center
        ) { SynthGlyph(icon, if (enabled) contentColor else Synth.muted.copy(alpha = 0.5f), Modifier.size(size * 0.42f)) }
        Text(label, color = if (enabled) Synth.night else Synth.night.copy(alpha = 0.55f), fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

/** Square tile with a glyph and a short label; [selected] floods it violet for toggles. */
@Composable
fun Tile(icon: SynthIcon, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, selected: Boolean? = null, enabled: Boolean = true, description: String = label) {
    val on = selected == true
    Column(
        modifier.heightIn(min = 92.dp).clip(RoundedCornerShape(18.dp))
            .background(if (on) Synth.violet else Synth.surface)
            .border(1.dp, if (on) Synth.violet else Synth.line, RoundedCornerShape(18.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = description
                if (selected != null) stateDescription = if (on) "On" else "Off"
            }
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        val tint = when { on -> Synth.night; enabled -> Synth.text; else -> Synth.text.copy(alpha = 0.35f) }
        SynthGlyph(icon, tint)
        Text(label, color = tint, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

/** Big back button + title. */
@Composable
fun SynthTopBar(title: String, onBack: () -> Unit, backLabel: String = "Back", action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.heightIn(min = 56.dp).clip(RoundedCornerShape(16.dp)).background(Synth.surface).border(1.dp, Synth.line, RoundedCornerShape(16.dp))
                .clickable(role = Role.Button, onClick = onBack).padding(start = 10.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SynthGlyph(SynthIcon.Back, Synth.text, Modifier.size(24.dp))
            Text(backLabel, color = Synth.text, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp))
        }
        Text(title.uppercase(), style = androidx.compose.material3.MaterialTheme.typography.titleLarge, color = Synth.night, modifier = Modifier.weight(1f).padding(horizontal = 16.dp))
        action?.invoke()
    }
}

@Composable
fun IconSquare(icon: SynthIcon, label: String, onClick: () -> Unit, enabled: Boolean = true) {
    Box(
        Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(Synth.surface).border(1.dp, Synth.line, RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) { SynthGlyph(icon, if (enabled) Synth.text else Synth.text.copy(alpha = 0.35f), Modifier.size(26.dp)) }
}

/** Full-width button, at least 64dp tall. */
@Composable
fun BigButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false, enabled: Boolean = true, icon: SynthIcon? = null) {
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 64.dp), shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(containerColor = if (primary) Synth.pink else Synth.surfaceHi, contentColor = if (primary) Color.White else Synth.text),
        border = if (primary) null else BorderStroke(1.dp, Synth.line)
    ) {
        if (icon != null) { SynthGlyph(icon, if (primary) Color.White else Synth.text, Modifier.size(24.dp)); Spacer(Modifier.width(10.dp)) }
        Text(text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
}

/** [-] value [+] with 64dp buttons. */
@Composable
fun Stepper(value: String, onMinus: () -> Unit, onPlus: () -> Unit, minusLabel: String, plusLabel: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StepKey("−", minusLabel, onMinus)
        Text(value, color = Synth.cyan, fontSize = 26.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.weight(1f))
        StepKey("+", plusLabel, onPlus)
    }
}

@Composable
private fun StepKey(symbol: String, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)).background(Synth.surfaceHi).border(1.dp, Synth.line, RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) { Text(symbol, color = Synth.text, fontSize = 30.sp) }
}

/** Rounded translucent surface. */
@Composable
fun SynthPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Synth.surface.copy(alpha = 0.93f))
            .border(1.dp, Synth.line, RoundedCornerShape(22.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Panels are dark islands on the orange ground: their text is light.
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides Synth.text) { content() }
    }
}
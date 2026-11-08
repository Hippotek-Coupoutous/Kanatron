package com.kanatron.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** Glyphs drawn on a 24-unit grid, one stroke weight, so no icon dependency is needed. */
enum class SynthIcon { Play, Pause, Save, Rec, New, Import, Share, Click, Overdub, Trim, Undo, Clip, More, Back, Delete, Library, Sketch, Tuner }

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
        SynthIcon.Save -> { drawRoundRect(tint, p(4f, 4f), Size(16 * u, 16 * u), androidx.compose.ui.geometry.CornerRadius(2 * u), style = stroke); line(8f, 12.5f, 11f, 15.5f); line(11f, 15.5f, 16.5f, 9f) }
        SynthIcon.Rec -> drawCircle(tint, 7 * u, p(12f, 12f))
        SynthIcon.New -> { line(12f, 5f, 12f, 19f); line(5f, 12f, 19f, 12f) }
        SynthIcon.More -> for (y in listOf(5f, 12f, 19f)) drawCircle(tint, 1.8f * u, p(12f, y))
        SynthIcon.Import -> { line(12f, 3f, 12f, 15f); line(12f, 15f, 7.5f, 10.5f); line(12f, 15f, 16.5f, 10.5f); line(4f, 15f, 4f, 20f); line(4f, 20f, 20f, 20f); line(20f, 20f, 20f, 15f) }
        SynthIcon.Share -> { drawCircle(tint, 2.5f * u, p(18f, 5f), style = stroke); drawCircle(tint, 2.5f * u, p(6f, 12f), style = stroke); drawCircle(tint, 2.5f * u, p(18f, 19f), style = stroke); line(8.2f, 10.8f, 15.8f, 6.4f); line(8.2f, 13.2f, 15.8f, 17.6f) }
        SynthIcon.Click -> { drawPath(Path().apply { moveTo(9 * u, 3 * u); lineTo(15 * u, 3 * u); lineTo(19 * u, 21 * u); lineTo(5 * u, 21 * u); close() }, tint, style = stroke); line(12f, 15f, 17f, 6f); line(8f, 17f, 16f, 17f) }
        SynthIcon.Overdub -> { drawArc(tint, 180f, 180f, false, p(4f, 4f), Size(16 * u, 16 * u), style = stroke); drawRoundRect(tint, p(3f, 13f), Size(4 * u, 7 * u), androidx.compose.ui.geometry.CornerRadius(1.5f * u)); drawRoundRect(tint, p(17f, 13f), Size(4 * u, 7 * u), androidx.compose.ui.geometry.CornerRadius(1.5f * u)) }
        SynthIcon.Trim -> { drawCircle(tint, 3 * u, p(6f, 6f), style = stroke); drawCircle(tint, 3 * u, p(6f, 18f), style = stroke); line(8.5f, 7.5f, 20f, 18f); line(8.5f, 16.5f, 20f, 6f) }
        SynthIcon.Undo -> {
            drawPath(Path().apply { moveTo(5 * u, 10 * u); lineTo(14 * u, 10 * u); cubicTo(22 * u, 10 * u, 22 * u, 20 * u, 14 * u, 20 * u); lineTo(9 * u, 20 * u) }, tint, style = stroke)
            line(9f, 6f, 5f, 10f); line(5f, 10f, 9f, 14f)
        }
        SynthIcon.Clip -> {
            drawRoundRect(tint, p(3f, 5f), Size(11 * u, 5 * u), androidx.compose.ui.geometry.CornerRadius(1.5f * u), style = stroke)
            drawRoundRect(tint, p(10f, 14f), Size(11 * u, 5 * u), androidx.compose.ui.geometry.CornerRadius(1.5f * u), style = stroke)
        }
        SynthIcon.Back -> { line(15f, 5f, 8f, 12f); line(8f, 12f, 15f, 19f) }
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


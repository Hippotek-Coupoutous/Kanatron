package com.kanatron.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shared app palette. */
internal object Synth {
    val night = Color(0xFF0D0221)
    val surface = Color(0xFF1A0939)
    val surfaceHi = Color(0xFF2B1260)
    val line = Color(0xFF4D2C8A)
    val pink = Color(0xFFFF2A6D)
    val yellow = Color(0xFFFFD319)
    val cyan = Color(0xFF2DE2E6)
    val violet = Color(0xFFB67CFF)
    val text = Color(0xFFF7ECFF)
    val muted = Color(0xFFCBB5EC)
    val skyTop = Color(0xFFFFB627)
    val skyMid = Color(0xFFFF7B1C)
    val skyLow = Color(0xFFE8341C)
}

@Composable
fun KanatronTheme(content: @Composable () -> Unit) {
    // Always dark: the outrun look is the product's identity and it is used at night with music.
    val colors = darkColorScheme(
        primary = Synth.pink,
        onPrimary = Color.White,
        secondary = Synth.cyan,
        onSecondary = Synth.night,
        tertiary = Synth.yellow,
        onTertiary = Synth.night,
        background = Synth.night,
        onBackground = Synth.night,
        surface = Synth.surface,
        onSurface = Synth.text,
        surfaceVariant = Synth.surfaceHi,
        onSurfaceVariant = Synth.muted,
        surfaceContainer = Synth.surface,
        surfaceContainerHigh = Synth.surfaceHi,
        surfaceContainerHighest = Synth.surfaceHi,
        outline = Synth.line,
        error = Color(0xFFFF8FA8)
    )
    val base = Typography()
    val display = FontFamily.SansSerif
    val typography = base.copy(
        headlineSmall = base.headlineSmall.copy(fontFamily = display, fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
        titleLarge = TextStyle(fontFamily = display, fontWeight = FontWeight.Bold, fontSize = 22.sp, letterSpacing = 2.sp),
        labelLarge = base.labelLarge.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    )
    val shapes = Shapes(small = RoundedCornerShape(14.dp), medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(22.dp))
    MaterialTheme(colorScheme = colors, typography = typography, shapes = shapes) {
        // Every screen sits on one plain orange, yellowish at the top to red at the bottom; text on it is dark.
        androidx.compose.foundation.layout.Box(
            androidx.compose.ui.Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Synth.skyTop, Synth.skyMid, Synth.skyLow)))
        ) {
            androidx.compose.material3.Surface(color = Color.Transparent, contentColor = colors.onBackground, content = content)
        }
    }
}
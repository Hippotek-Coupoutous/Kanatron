package com.kanatron.app.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The three key heights: transport keys, everyday actions, and small adjusters. */
object KeyHeight {
    val Transport = 80.dp
    val Action = 60.dp
    val Small = 52.dp
}

/** How far a key face stands above its lip; a press closes the gap. */
private val KEY_DEPTH = 5.dp
private val KEY_SHAPE = RoundedCornerShape(14.dp)

/**
 * A key that sinks under the finger: the face stands on a darker lip and drops onto it while pressed.
 * [selected] latches a toggle half way down. Icon and label stack on keys at least [KeyHeight.Transport] tall.
 */
@Composable
fun Key(
    label: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: SynthIcon? = null,
    face: Color = Synth.surface,
    enabled: Boolean = true,
    selected: Boolean? = null,
    description: String = label.orEmpty(),
    state: String? = null,
    sublabel: String? = null,
    height: Dp = KeyHeight.Action,
    role: Role = Role.Button
) {
    val on = selected == true
    val faceColor = when {
        !enabled -> Synth.surface.copy(alpha = 0.6f)
        on -> Synth.violet
        else -> face
    }
    val ink = when {
        !enabled -> Synth.text.copy(alpha = 0.38f)
        faceColor.luminance() > 0.35f -> Synth.night
        else -> Synth.text
    }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(pressed) { if (pressed) haptics.performHapticFeedback(HapticFeedbackType.VirtualKey) }
    val travel by animateDpAsState(
        when {
            pressed && enabled -> KEY_DEPTH - 1.dp
            on -> KEY_DEPTH / 2
            else -> 0.dp
        },
        spring(stiffness = Spring.StiffnessHigh),
        label = "key travel"
    )
    val stacked = height >= KeyHeight.Transport || sublabel != null
    // Min constraints pass through, so a weighted or full-width key stretches its face while a bare key hugs its label.
    Box(
        modifier.heightIn(min = height)
            .clickable(interaction, indication = null, enabled = enabled, role = role, onClick = onClick)
            .semantics {
                contentDescription = description
                // A tab says it is selected; a toggle key says whether it is on.
                if (selected != null) {
                    if (role == Role.Tab) this.selected = on else stateDescription = if (on) "On" else "Off"
                }
                state?.let { stateDescription = it }
            },
        propagateMinConstraints = true
    ) {
        Box(Modifier.matchParentSize().padding(top = KEY_DEPTH).background(lerp(faceColor, Synth.night, 0.55f), KEY_SHAPE))
        val face = Modifier.offset(y = travel)
            .background(Brush.verticalGradient(listOf(lerp(faceColor, Color.White, 0.16f), faceColor)), KEY_SHAPE)
            .border(1.dp, lerp(faceColor, Color.White, 0.3f).copy(alpha = 0.5f), KEY_SHAPE)
            .padding(horizontal = 8.dp, vertical = 6.dp)
        val content: @Composable () -> Unit = {
            icon?.let { SynthGlyph(it, ink, Modifier.size(if (stacked) 28.dp else 22.dp)) }
            // A lone symbol such as − or + stands in for an icon, so it gets icon size.
            label?.let { Text(it.uppercase(), color = ink, style = if (it.length == 1) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.labelLarge, maxLines = 1) }
            sublabel?.let { Text(it, color = ink, style = MaterialTheme.typography.bodyMedium, maxLines = 1) }
        }
        Box(Modifier.padding(bottom = KEY_DEPTH), propagateMinConstraints = true) {
            if (stacked) {
                Column(face, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)) { content() }
            } else {
                Row(face, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) { content() }
            }
        }
    }
}

/** Screen title; sub-screens add a back key that names where it goes. */
@Composable
fun SynthTopBar(title: String, onBack: (() -> Unit)? = null, backLabel: String = "Back", action: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = KeyHeight.Small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        onBack?.let { Key(backLabel, it, icon = SynthIcon.Back, height = KeyHeight.Small, description = "Back to $backLabel") }
        Text(title.uppercase(), style = MaterialTheme.typography.headlineMedium, color = Synth.night, maxLines = 1, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

/** Digits keep one width each, so a running clock does not jitter in the proportional display face. */
@Composable
fun Readout(text: String, color: Color, fontSize: TextUnit, modifier: Modifier = Modifier) {
    val cell = with(LocalDensity.current) { (fontSize * DIGIT_EM).toDp() }
    Row(modifier.clearAndSetSemantics { contentDescription = text }, verticalAlignment = Alignment.CenterVertically) {
        for (char in text) {
            Text(
                char.toString(), color = color, fontSize = fontSize, fontFamily = MaterialTheme.typography.displayLarge.fontFamily,
                textAlign = TextAlign.Center, modifier = if (char.isDigit()) Modifier.width(cell) else Modifier
            )
        }
    }
}

/** The widest digit of the display face, in ems. */
private const val DIGIT_EM = 0.5f

/** [-] value [+] on small keys. */
@Composable
fun Stepper(
    value: String, onMinus: () -> Unit, onPlus: () -> Unit, minusLabel: String, plusLabel: String, modifier: Modifier = Modifier,
    minusEnabled: Boolean = true, plusEnabled: Boolean = true
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // Both keys report the value, so a screen reader hears each change.
        Key("−", onMinus, Modifier.width(64.dp), enabled = minusEnabled, description = minusLabel, state = value, height = KeyHeight.Small)
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Readout(value, Synth.cyan, 28.sp) }
        Key("+", onPlus, Modifier.width(64.dp), enabled = plusEnabled, description = plusLabel, state = value, height = KeyHeight.Small)
    }
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
        CompositionLocalProvider(LocalContentColor provides Synth.text) { content() }
    }
}

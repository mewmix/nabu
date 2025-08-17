@file:Suppress("UnusedParameter")
package com.mewmix.nabu.ui.brutalist

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/* ---------- Palette / Tokens ---------- */
object Brutal {
    val panelBg = Color(0xFF101010)
    val panelHl = Color(0xFF1B1B1B)
    val panelStroke = Color(0xFF2A2A2A)
    val hairline = Color(0xFF3A3A3A)
    val nameplate = Color(0xFF151515)
    val steel = Color(0xFFB7B7B7)
    val amber = Color(0xFFE6B800)
    val red = Color(0xFFFF4040)
    val green = Color(0xFF00FF99)
    val textDim = Color(0xFFBDBDBD)
    val textBright = Color(0xFFEFEFEF)
    val crt = Color(0xFFB8FFC8)

    val mono = FontFamily.Monospace
}

private val PanelShape = RoundedCornerShape(8.dp)
private val BevelStroke = Stroke(width = 1.5f)

/* ---------- Panel Box (container) ---------- */
@Composable
fun PanelBox(
    title: String,
    modifier: Modifier = Modifier,
    inset: Dp = 8.dp,
    headerTrailing: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier
            .background(Brutal.panelBg, PanelShape)
            .border(1.dp, Brutal.panelStroke, PanelShape)
            .drawBehind {
                // subtle panel ribs / gridlines:
                val step = 12f
                drawRect(Brush.linearGradient(listOf(Color.Transparent, Brutal.panelHl.copy(alpha = 0.08f))), blendMode = BlendMode.SrcOver)
                for (y in 0..(size.height / step).toInt()) {
                    drawLine(Brutal.hairline.copy(alpha = 0.15f), Offset(0f, y * step), Offset(size.width, y * step), 1f)
                }
            }
            .padding(inset)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LabelPlate(title)
            Spacer(Modifier.weight(1f))
            if (headerTrailing != null) Row(content = headerTrailing)
        }
        content()
    }
}

/* ---------- Collapsible Section ---------- */
@Composable
fun BrutalSection(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Brutal.panelHl, RoundedCornerShape(6.dp))
            .border(1.dp, Brutal.panelStroke, RoundedCornerShape(6.dp))
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggle() },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                title,
                color = Brutal.textBright,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = Brutal.mono
            )
            Spacer(Modifier.weight(1f))
            Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = "Toggle $title",
                tint = Brutal.textBright,
                modifier = Modifier.graphicsLayer(rotationZ = if (expanded) 180f else 0f)
            )
        }
        if (expanded) {
            Divider(
                color = Brutal.hairline,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            content()
        }
    }
}

/* ---------- Label Plate (engraved name) ---------- */
@Composable
fun LabelPlate(
    text: String,
    modifier: Modifier = Modifier,
    tone: Color = Brutal.nameplate
) {
    Box(
        modifier
            .background(tone, RoundedCornerShape(4.dp))
            .border(1.dp, Brutal.hairline, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = text.uppercase(),
            color = Brutal.textBright,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = Brutal.mono,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/* ---------- Button ---------- */
@Composable
fun BrutalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val bg = if (enabled) Brutal.panelHl else Brutal.panelBg
    val border = if (enabled) Brutal.panelStroke else Brutal.hairline
    Row(
        modifier
            .background(bg, RoundedCornerShape(6.dp))
            .border(1.dp, border, RoundedCornerShape(6.dp))
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        val contentColor = if (enabled) Brutal.textBright else Brutal.textDim
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            ProvideTextStyle(MaterialTheme.typography.labelMedium.copy(fontFamily = Brutal.mono)) {
                content()
            }
        }
    }
}

/* ---------- LED Indicator ---------- */
@Composable
fun Led(
    on: Boolean,
    colorOn: Color = Brutal.green,
    colorOff: Color = Brutal.hairline,
    size: Dp = 10.dp,
    modifier: Modifier = Modifier
) {
    val anim = remember { Animatable(if (on) 1f else 0f) }
    LaunchedEffect(on) {
        anim.animateTo(if (on) 1f else 0f, spring(stiffness = Spring.StiffnessMedium))
    }
    Canvas(
        modifier
            .size(size)
            .padding(1.dp)
    ) {
        val r = min(this.size.width, this.size.height) / 2f
        drawCircle(colorOff, r)
        drawCircle(colorOn.copy(alpha = anim.value), r * 0.92f, blendMode = BlendMode.Screen)
    }
}

/* ---------- Toggle Switch (hardware) ---------- */
@Composable
fun SwitchToggle(
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    ledColor: Color = Brutal.green
) {
    Row(
        modifier = modifier
            .background(Brutal.panelHl, RoundedCornerShape(6.dp))
            .border(1.dp, Brutal.panelStroke, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { onToggle(!checked) },
                    onDragEnd = { /* snap already handled */ },
                    onDragCancel = {}
                ) { _, _ -> /* tap/drag same behavior for simplicity */ }
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Led(on = checked, colorOn = ledColor)
        Spacer(Modifier.width(8.dp))
        Text(label, color = Brutal.textDim, fontFamily = Brutal.mono, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.weight(1f))
        // mechanical slider stub
        Box(
            Modifier
                .size(width = 54.dp, height = 22.dp)
                .background(Brutal.panelBg, RoundedCornerShape(4.dp))
                .border(1.dp, Brutal.hairline, RoundedCornerShape(4.dp))
                .drawBehind {
                    val knobW = size.width / 2.2f
                    val x = if (checked) size.width - knobW - 2f else 2f
                    drawRect(Brutal.steel.copy(alpha = 0.9f), topLeft = Offset(x, 2f), size = Size(knobW, size.height - 4f))
                    drawLine(Brutal.hairline, Offset(size.width / 2f, 0f), Offset(size.width / 2f, size.height), 1f)
                }
        )
    }
}

/* ---------- Rotary Knob (detents + value) ---------- */
@Composable
fun Knob(
    value: Float,                 // 0f..1f
    onChange: (Float) -> Unit,
    steps: Int = 11,              // detents (>=2)
    label: String,
    modifier: Modifier = Modifier,
    enableDetents: Boolean = true,
    minAngleDeg: Float = 135f,    // physical stop
    maxAngleDeg: Float = 405f,    // physical stop
    indicatorColor: Color = Brutal.amber
) {
    val angleRange = maxAngleDeg - minAngleDeg
    val clamped = value.coerceIn(0f, 1f)
    val targetAngle = minAngleDeg + angleRange * clamped

    val angle = remember { Animatable(targetAngle) }
    LaunchedEffect(targetAngle) {
        angle.animateTo(targetAngle, spring(stiffness = Spring.StiffnessMediumLow))
    }

    Column(
        modifier
            .widthIn(min = 80.dp)
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(72.dp)
                .background(Brutal.panelHl, RoundedCornerShape(36.dp))
                .border(1.dp, Brutal.panelStroke, RoundedCornerShape(36.dp))
                .pointerInput(Unit) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        // vertical drag = value change (hardware feel)
                        val delta = -drag.y / 240f
                        var v = (value + delta).coerceIn(0f, 1f)
                        if (enableDetents && steps > 1) {
                            val step = 1f / (steps - 1)
                            v = (v / step).roundToInt() * step
                        }
                        onChange(v)
                    }
                }
                .drawBehind {
                    // dial
                    drawCircle(Brutal.steel.copy(alpha = 0.15f))
                    // ticks
                    val radius = min(size.width, size.height) / 2.1f
                    val tickCount = steps
                    for (i in 0 until tickCount) {
                        val a = Math.toRadians((minAngleDeg + angleRange * (i / (tickCount - 1f))).toDouble()).toFloat()
                        val p1 = Offset(center.x + cos(a) * (radius - 8f), center.y + sin(a) * (radius - 8f))
                        val p2 = Offset(center.x + cos(a) * (radius - 2f), center.y + sin(a) * (radius - 2f))
                        drawLine(Brutal.hairline, p1, p2, 2f)
                    }
                    // indicator
                    val rad = Math.toRadians(angle.value.toDouble()).toFloat()
                    val k1 = Offset(center.x + cos(rad) * (radius - 16f), center.y + sin(rad) * (radius - 16f))
                    val k2 = Offset(center.x + cos(rad) * (radius - 2f), center.y + sin(rad) * (radius - 2f))
                    drawLine(indicatorColor, k1, k2, 4f, cap = StrokeCap.Round)
                    // hub
                    drawCircle(Brutal.steel.copy(alpha = 0.55f), radius = 6f)
                }
        )
        Spacer(Modifier.height(6.dp))
        Text(label, color = Brutal.textDim, fontFamily = Brutal.mono, style = MaterialTheme.typography.labelSmall)
        Text("${((clamped) * 100).roundToInt()}%", color = Brutal.textBright, fontFamily = Brutal.mono, style = MaterialTheme.typography.labelSmall)
    }
}

/* ---------- Segmented Meter (VU / progress) ---------- */
@Composable
fun SegmentedMeter(
    fraction: Float,          // 0..1
    segments: Int = 20,
    dangerFrom: Float = 0.8f,
    modifier: Modifier = Modifier,
    vertical: Boolean = false
) {
    val f = fraction.coerceIn(0f, 1f)
    val lit = (f * segments).roundToInt()

    Canvas(
        modifier
            .then(if (vertical) Modifier.width(16.dp).height(120.dp) else Modifier.height(16.dp).fillMaxWidth())
            .background(Brutal.panelHl, RoundedCornerShape(3.dp))
            .border(1.dp, Brutal.panelStroke, RoundedCornerShape(3.dp))
            .padding(2.dp)
    ) {
        val cell = if (vertical) size.height / segments else size.width / segments
        for (i in 0 until segments) {
            val on = i < lit
            val pos = i * cell
            val rect = if (vertical)
                Rect(0f, size.height - pos - cell + 1f, size.width, size.height - pos - 2f)
            else
                Rect(pos + 1f, 1f, pos + cell - 2f, size.height - 1f)

            val ratio = (i + 1).toFloat() / segments
            val color = when {
                ratio >= dangerFrom -> Brutal.red
                ratio >= dangerFrom * 0.7f -> Brutal.amber
                else -> Brutal.green
            }.copy(alpha = if (on) 0.95f else 0.18f)

            drawRect(color, topLeft = rect.topLeft, size = rect.size)
        }
    }
}

/* ---------- Voice Selector “KnobDropdown” (knob + label) ---------- */
@Composable
fun KnobSelector(
    options: List<String>,
    selectedIndex: Int,
    onSelectedIndex: (Int) -> Unit,
    label: String,
    modifier: Modifier = Modifier
) {
    val steps = options.size.coerceAtLeast(2)
    val value = (selectedIndex.toFloat() / (steps - 1)).coerceIn(0f, 1f)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Knob(
            value = value,
            onChange = { v ->
                val idx = ((steps - 1) * v).roundToInt().coerceIn(0, steps - 1)
                if (idx != selectedIndex) onSelectedIndex(idx)
            },
            steps = steps,
            label = label
        )
        Text(
            options.getOrNull(selectedIndex) ?: "—",
            color = Brutal.textBright,
            fontFamily = Brutal.mono,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/* ---------- Panel Row Utilities ---------- */
@Composable
fun PanelRow(
    name: String,
    modifier: Modifier = Modifier,
    right: @Composable RowScope.() -> Unit
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(Brutal.panelHl, RoundedCornerShape(4.dp))
            .border(1.dp, Brutal.hairline, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(name.uppercase(), color = Brutal.textDim, fontFamily = Brutal.mono, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.weight(1f))
        right()
    }
}

/* ---------- Radial Oscilloscope (waveform polar plot) ---------- */
@Composable
fun RadialOscilloscope(
    samples: List<Float>,  // normalized -1..1, e.g., audio buffer slice
    modifier: Modifier = Modifier,
    traceColor: Color = Brutal.crt,
    gridColor: Color = Brutal.hairline,
    lineWidth: Float = 1.5f,
    size: Dp = 120.dp
) {
    val numSamples = samples.size.coerceAtLeast(1)
    Box(
        modifier
            .size(size)
            .background(Brutal.panelHl, RoundedCornerShape(8.dp))
            .border(1.dp, Brutal.panelStroke, RoundedCornerShape(8.dp))
            .drawBehind {
                /*
                // subtle radial grid
                val center = Offset(size.width / 2, size.height / 2)
                val radius = min(size.width, size.height) / 2f
                drawCircle(gridColor.copy(alpha = 0.3f), radius = radius, center = center, style = Stroke(1f))
                drawCircle(gridColor.copy(alpha = 0.2f), radius = radius / 2, center = center, style = Stroke(1f))
                for (i in 0 until 8) {
                    val angle = (i.toFloat() / 8) * 2 * PI.toFloat()
                    val p1 = Offset(center.x + cos(angle) * (radius / 2), center.y + sin(angle) * (radius / 2))
                    val p2 = Offset(center.x + cos(angle) * radius, center.y + sin(angle) * radius)
                    drawLine(gridColor.copy(alpha = 0.15f), p1, p2)
                }
                */
            }
    ) {
        /*
        Canvas(Modifier.fillMaxSize().padding(4.dp)) {
            val center = Offset(size.width / 2, size.height / 2)
            val radius = min(size.width, size.height) / 2f
            val path = Path()
            var first = true
            for (i in 0 until numSamples) {
                val angle = (i.toFloat() / numSamples) * 2 * PI.toFloat()
                val amp = ((samples[i].coerceIn(-1f, 1f) + 1f) / 2f).coerceIn(0f, 1f)  // 0..1 radial
                val r = (radius * 0.3f) + (radius * 0.7f * amp)  // baseline ring + modulation
                val x = center.x + cos(angle) * r
                val y = center.y + sin(angle) * r
                if (first) {
                    path.moveTo(x, y)
                    first = false
                } else {
                    path.lineTo(x, y)
                }
            }
            drawPath(path, traceColor, style = Stroke(width = lineWidth, cap = StrokeCap.Round))
        }
        */
    }
}

/* ---------- Minimal usage examples ---------- */

@Composable
fun ChatTtsControls(
    isPlaying: Boolean,
    onTogglePlay: (Boolean) -> Unit,
    voices: List<String>,
    voiceIndex: Int,
    onVoiceIndex: (Int) -> Unit,
    gain: Float,
    onGain: (Float) -> Unit,
    meter: Float,
    samples: List<Float>
) {
    PanelBox(title = "Chat · TTS Controls") {
        PanelRow("Transport") {
            SwitchToggle(
                checked = isPlaying,
                onToggle = onTogglePlay,
                label = if (isPlaying) "PLAY" else "STOP"
            )
        }
        Spacer(Modifier.height(8.dp))
        PanelRow("Voice") {
            KnobSelector(
                options = voices,
                selectedIndex = voiceIndex,
                onSelectedIndex = onVoiceIndex,
                label = "VOICE"
            )
        }
        Spacer(Modifier.height(8.dp))
        PanelRow("Gain") {
            Knob(
                value = gain,
                onChange = onGain,
                label = "GAIN"
            )
        }
        Spacer(Modifier.height(8.dp))
        PanelRow("Output") {
            SegmentedMeter(fraction = meter, segments = 24)
        }
        Spacer(Modifier.height(8.dp))
        PanelRow("Scope") {
            RadialOscilloscope(samples = samples)
        }
    }
}

@Composable
fun BookPlayerInstruments(
    reading: Boolean,
    onReadingChange: (Boolean) -> Unit,
    speed: Float,
    onSpeed: (Float) -> Unit,
    progress: Float
) {
    PanelBox(title = "Book · Player") {
        PanelRow("Read") {
            SwitchToggle(checked = reading, onToggle = onReadingChange, label = if (reading) "RUN" else "HALT")
        }
        Spacer(Modifier.height(8.dp))
        PanelRow("Speed") {
            Knob(value = speed, onChange = onSpeed, label = "WPM")
        }
        Spacer(Modifier.height(8.dp))
        PanelRow("Progress") {
            SegmentedMeter(fraction = progress, segments = 32)
        }
    }
}

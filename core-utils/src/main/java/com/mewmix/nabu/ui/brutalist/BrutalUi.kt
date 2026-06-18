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
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import com.mewmix.nabu.ui.design.LocalNabuChrome
import com.mewmix.nabu.ui.design.LocalNabuUiMode
import com.mewmix.nabu.ui.design.NabuUiMode
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
}

private val PanelShape = RoundedCornerShape(8.dp)
private val BevelStroke = Stroke(width = 1.5f)

/* ---------- Panel Box (container) ---------- */
@Composable
fun PanelBox(
    title: String? = null,
    modifier: Modifier = Modifier,
    inset: Dp = 8.dp,
    headerTrailing: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val mode = LocalNabuUiMode.current
    val chrome = LocalNabuChrome.current
    if (mode == NabuUiMode.Modern) {
        val shape = RoundedCornerShape(chrome.panelRadius)
        Column(
            modifier
                .background(
                    brush = Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.surface,
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
                        )
                    ),
                    shape = shape
                )
                .border(chrome.borderWidth, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f), shape)
                .padding(chrome.panelPadding)
        ) {
            if (!title.isNullOrBlank()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .background(MaterialTheme.colorScheme.secondary, RoundedCornerShape(999.dp))
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = title.replaceFirstChar { it.titlecase() },
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.weight(1f))
                    if (headerTrailing != null) Row(content = headerTrailing)
                }
            }
            content()
        }
    } else {
        Column(
            modifier
                .background(Brutal.panelBg, PanelShape)
                .border(1.dp, Brutal.panelStroke, PanelShape)
                .drawBehind {
                    // subtle panel ribs / gridlines:
                    val step = 12f
                    drawRect(
                        Brush.linearGradient(
                            listOf(Color.Transparent, Brutal.panelHl.copy(alpha = 0.08f))
                        ),
                        blendMode = BlendMode.SrcOver
                    )
                    for (y in 0..(size.height / step).toInt()) {
                        drawLine(
                            Brutal.hairline.copy(alpha = 0.15f),
                            Offset(0f, y * step),
                            Offset(size.width, y * step),
                            1f
                        )
                    }
                }
                .padding(inset)
        ) {
            if (!title.isNullOrBlank()) {
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
            }
            content()
        }
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
    val mode = LocalNabuUiMode.current
    val chrome = LocalNabuChrome.current
    val shape = RoundedCornerShape(if (mode == NabuUiMode.Modern) chrome.controlRadius else 6.dp)
    val background = if (mode == NabuUiMode.Modern) {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f)
    } else {
        Brutal.panelHl
    }
    val border = if (mode == NabuUiMode.Modern) {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.36f)
    } else {
        Brutal.panelStroke
    }
    val textColor = if (mode == NabuUiMode.Modern) {
        MaterialTheme.colorScheme.onSurface
    } else {
        Brutal.textBright
    }
    Column(
        modifier
            .fillMaxWidth()
            .background(background, shape)
            .border(chrome.borderWidth, border, shape)
            .padding(if (mode == NabuUiMode.Modern) 14.dp else 16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggle() },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (mode == NabuUiMode.Modern) title.replaceFirstChar { it.titlecase() } else title,
                color = textColor,
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.weight(1f))
            Icon(
                imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse $title" else "Expand $title",
                tint = if (mode == NabuUiMode.Modern) MaterialTheme.colorScheme.primary else Brutal.textBright
            )
        }
        if (expanded) {
            Divider(
                color = if (mode == NabuUiMode.Modern) MaterialTheme.colorScheme.outline.copy(alpha = 0.32f) else Brutal.hairline,
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
    val mode = LocalNabuUiMode.current
    val chrome = LocalNabuChrome.current
    if (mode == NabuUiMode.Modern) {
        Box(
            modifier
                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(chrome.chipRadius))
                .border(chrome.borderWidth, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f), RoundedCornerShape(chrome.chipRadius))
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(
                text = text.replaceFirstChar { it.titlecase() },
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        return
    }
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
    val mode = LocalNabuUiMode.current
    val chrome = LocalNabuChrome.current
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(if (mode == NabuUiMode.Modern) chrome.controlRadius else 6.dp)
    val bg = if (mode == NabuUiMode.Modern) {
        if (enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    } else {
        if (enabled) Brutal.panelHl else Brutal.panelBg
    }
    val border = if (mode == NabuUiMode.Modern) {
        if (enabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.32f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)
    } else {
        if (enabled) Brutal.panelStroke else Brutal.hairline
    }
    val contentColor = if (mode == NabuUiMode.Modern) {
        if (enabled) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        if (enabled) Brutal.textBright else Brutal.textDim
    }
    Row(
        modifier
            .heightIn(min = chrome.buttonHeight)
            .background(bg, shape)
            .border(chrome.borderWidth, border, shape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick
            )
            .padding(horizontal = if (mode == NabuUiMode.Modern) 16.dp else 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            ProvideTextStyle(if (mode == NabuUiMode.Modern) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelMedium) {
                content()
            }
        }
    }
}

@Composable
fun BrutalButtonText(text: String, modifier: Modifier = Modifier) {
    val mode = LocalNabuUiMode.current
    Text(
        text = if (mode == NabuUiMode.Modern) text.toButtonTitle() else text,
        modifier = modifier.fillMaxWidth(),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        softWrap = false,
        textAlign = TextAlign.Center
    )
}

@Composable
fun BrutalIconButton(
    imageVector: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 56.dp
) {
    BrutalButton(
        onClick = onClick,
        modifier = modifier.size(size),
        enabled = enabled
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            modifier = Modifier.size(26.dp)
        )
    }
}

private fun String.toButtonTitle(): String =
    split(" ").joinToString(" ") { part ->
        if (part.all { it == '&' || it == '/' || it == '+' || it == '-' }) {
            part
        } else {
            part.lowercase().replaceFirstChar { it.titlecase() }
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
    val mode = LocalNabuUiMode.current
    val chrome = LocalNabuChrome.current
    val interaction = remember { MutableInteractionSource() }
    if (mode == NabuUiMode.Modern) {
        Row(
            modifier = modifier
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f), RoundedCornerShape(chrome.controlRadius))
                .border(chrome.borderWidth, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f), RoundedCornerShape(chrome.controlRadius))
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    role = Role.Switch
                ) { onToggle(!checked) },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.weight(1f))
            Box(
                Modifier
                    .size(width = 54.dp, height = 30.dp)
                    .background(
                        if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                        RoundedCornerShape(999.dp)
                    )
                    .drawBehind {
                        val radius = 11.dp.toPx()
                        val x = if (checked) size.width - radius - 4.dp.toPx() else radius + 4.dp.toPx()
                        drawCircle(
                            color = Color.White,
                            radius = radius,
                            center = Offset(x, size.height / 2f)
                        )
                    }
            )
        }
        return
    }
    Row(
        modifier = modifier
            .background(Brutal.panelHl, RoundedCornerShape(6.dp))
            .border(1.dp, Brutal.panelStroke, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Switch
            ) { onToggle(!checked) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Led(on = checked, colorOn = ledColor)
        Spacer(Modifier.width(8.dp))
        Text(label, color = Brutal.textDim, style = MaterialTheme.typography.labelMedium)
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
        Text(label, color = Brutal.textDim, style = MaterialTheme.typography.labelSmall)
        Text("${((clamped) * 100).roundToInt()}%", color = Brutal.textBright, style = MaterialTheme.typography.labelSmall)
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
    val mode = LocalNabuUiMode.current
    val chrome = LocalNabuChrome.current
    val shape = RoundedCornerShape(if (mode == NabuUiMode.Modern) chrome.controlRadius else 4.dp)
    Row(
        modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(
                if (mode == NabuUiMode.Modern) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.56f) else Brutal.panelHl,
                shape
            )
            .border(
                chrome.borderWidth,
                if (mode == NabuUiMode.Modern) MaterialTheme.colorScheme.outline.copy(alpha = 0.3f) else Brutal.hairline,
                shape
            )
            .padding(horizontal = if (mode == NabuUiMode.Modern) 12.dp else 8.dp, vertical = if (mode == NabuUiMode.Modern) 10.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (mode == NabuUiMode.Modern) name.replaceFirstChar { it.titlecase() } else name.uppercase(),
            color = if (mode == NabuUiMode.Modern) MaterialTheme.colorScheme.onSurfaceVariant else Brutal.textDim,
            style = MaterialTheme.typography.labelSmall
        )
        Spacer(Modifier.weight(1f))
        right()
    }
}

@Composable
fun BrutalSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    range: ClosedFloatingPointRange<Float> = 0f..1f
) {
    val mode = LocalNabuUiMode.current
    if (mode == NabuUiMode.Modern) {
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            valueRange = range,
            modifier = modifier,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.secondary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        )
        return
    }
    val interactionSource = remember { MutableInteractionSource() }
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(24.dp)
            .pointerInput(interactionSource) {
                detectDragGestures { change, _ ->
                    // map x position to value in range
                    val newValue = (change.position.x / size.width)
                        .coerceIn(0f, 1f) * (range.endInclusive - range.start) + range.start
                    onValueChange(newValue)
                }
            }
    ) {
        val trackY = center.y
        // track
        drawLine(
            color = Brutal.panelBg,
            start = Offset(0f, trackY),
            end = Offset(size.width, trackY),
            strokeWidth = 12f,
            cap = StrokeCap.Round
        )
        // thumb
        val thumbX = ((value - range.start) / (range.endInclusive - range.start)) * size.width
        drawRect(
            color = Brutal.steel,
            topLeft = Offset(thumbX - 12f, trackY - 12f),
            size = Size(24f, 24f)
        )
        drawRect(
            color = Brutal.hairline,
            topLeft = Offset(thumbX - 12f, trackY - 12f),
            size = Size(24f, 24f),
            style = Stroke(width = 2f)
        )
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
fun ChatControls(
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
    PanelBox(title = "Chat Controls") {
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

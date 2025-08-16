package com.example.nabu.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * A radial visualiser that echoes the brutalist design ethos.  When audio is
 * playing a set of geometric bars radiate from the centre in proportion to the
 * provided amplitudes.  When paused the visualiser collapses into a thin ring
 * that gently "breathes" to indicate readiness.
 */
@Composable
fun RadialAudioVisualizer(
    amplitudes: List<Float>,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    barColor: Color = MaterialTheme.colorScheme.primary
) {
    val bars = if (amplitudes.isEmpty()) List(64) { 0f } else amplitudes
    val transition = rememberInfiniteTransition(label = "breath")
    val breath by transition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            tween(durationMillis = 2000, easing = LinearEasing),
            RepeatMode.Reverse
        ),
        label = "breathAnim"
    )

    Canvas(modifier = modifier) {
        val radius = min(size.width, size.height) / 2f
        val barCount = bars.size
        for (i in 0 until barCount) {
            val angle = (2 * PI * i / barCount).toFloat()
            val lineLength = if (isPlaying) radius * bars[i].coerceIn(0f, 1f) else 0f
            val start = Offset(
                x = center.x + cos(angle) * (radius - lineLength),
                y = center.y + sin(angle) * (radius - lineLength)
            )
            val end = Offset(
                x = center.x + cos(angle) * radius,
                y = center.y + sin(angle) * radius
            )
            drawLine(
                color = barColor,
                start = start,
                end = end,
                strokeWidth = 2.dp.toPx()
            )
        }
        if (!isPlaying) {
            drawCircle(
                color = barColor,
                radius = radius * breath,
                style = Stroke(width = 2.dp.toPx())
            )
        }
    }
}

/**
 * Displays overall book progress as a row of discrete horizontal segments,
 * resembling a speaker grille.
 */
@Composable
fun SegmentedChapterProgress(
    totalChapters: Int,
    currentChapter: Int,
    modifier: Modifier = Modifier,
    segmentWidth: Dp = 8.dp,
    spacing: Dp = 4.dp,
    activeColor: Color = MaterialTheme.colorScheme.primary,
    inactiveColor: Color = MaterialTheme.colorScheme.secondary
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(spacing)
    ) {
        repeat(totalChapters) { index ->
            Box(
                modifier = Modifier
                    .width(segmentWidth)
                    .height(4.dp)
                    .background(if (index < currentChapter) activeColor else inactiveColor)
            )
        }
    }
}

/**
 * Simple vertically scrolling list of chapters.  Each chapter is rendered as a
 * uniform block; the currently playing chapter receives a solid fill to
 * distinguish it from the rest.
 */
@Composable
fun ChapterList(
    chapters: List<String>,
    currentIndex: Int,
    modifier: Modifier = Modifier,
    onChapterSelected: (Int) -> Unit
) {
    LazyColumn(modifier = modifier) {
        itemsIndexed(chapters) { index, title ->
            val isCurrent = index == currentIndex
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .background(
                        color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(4.dp)
                    )
                    .clickable { onChapterSelected(index) }
                    .padding(16.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isCurrent) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

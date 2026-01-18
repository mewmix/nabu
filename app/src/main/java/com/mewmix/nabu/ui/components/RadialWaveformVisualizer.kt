package com.mewmix.nabu.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import com.mewmix.nabu.utils.PcmTap
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Radial waveform visualizer that draws audio samples in a polar layout.
 */
@Composable
fun RadialWaveformVisualizer(
    modifier: Modifier = Modifier,
    sampleCount: Int = 1024,
    lineColor: Color = Color.White,
    visible: Boolean = true,
) {
    var samples by remember { mutableStateOf(FloatArray(sampleCount)) }
    LaunchedEffect(visible) {
        while (visible) {
            if (PcmTap.enabled) {
                samples = PcmTap.getLastSamples(sampleCount)
            }
            delay(16)
        }
    }

    AnimatedVisibility(visible) {
        Canvas(modifier = modifier) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = min(size.width, size.height) / 2f
            val path = Path()
            val step = (2 * PI) / sampleCount
            for (i in 0 until sampleCount) {
                val angle = i * step
                val amplitude = (samples[i].coerceIn(-1f, 1f) + 1f) / 2f
                val r = radius * amplitude
                val x = center.x + cos(angle).toFloat() * r
                val y = center.y + sin(angle).toFloat() * r
                if (i == 0) {
                    path.moveTo(x, y)
                } else {
                    path.lineTo(x, y)
                }
            }
            path.close()
            drawPath(path, color = lineColor, style = Stroke(width = 2f, cap = StrokeCap.Round))
        }
    }
}


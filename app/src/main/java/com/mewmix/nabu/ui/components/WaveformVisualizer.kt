package com.mewmix.nabu.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mewmix.nabu.utils.PcmTap
import kotlinx.coroutines.delay

/**
 * Simple waveform visualizer composable. Draws lines based on PCM amplitude.
 */
@Composable
fun WaveformVisualizer(
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
        Canvas(modifier = modifier.fillMaxWidth().height(100.dp)) {
            val width = size.width
            val height = size.height
            val halfHeight = height / 2f
            val step = width / (sampleCount - 1).toFloat()
            for (i in 0 until sampleCount - 1) {
                val x1 = i * step
                val y1 = halfHeight - (samples[i] * halfHeight)
                val x2 = (i + 1) * step
                val y2 = halfHeight - (samples[i + 1] * halfHeight)
                drawLine(
                    color = lineColor,
                    start = Offset(x1, y1),
                    end = Offset(x2, y2),
                    strokeWidth = 2f,
                )
            }
        }
    }
}

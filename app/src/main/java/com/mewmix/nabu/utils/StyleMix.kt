package com.mewmix.nabu.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.pow
import kotlin.math.sqrt

enum class InterpolationMode(val displayName: String) {
    LINEAR("Linear (Better)"),
    SPHERICAL("Spherical")
}

suspend fun mixStyles(
    styleLoader: StyleLoader,
    styles: List<String>,
    weights: Map<String, Float>,
    mode: InterpolationMode
): Array<FloatArray> = withContext(Dispatchers.Default) {
    require(styles.isNotEmpty()) { "At least one style must be selected" }
    require(styles.all { it in weights }) { "All styles must have weights" }

    val styleVectors = styles.map { styleName ->
        styleLoader.getStyleArray(styleName).first()
    }

    val totalWeight = weights.values.sum()
    val normalizedWeights = weights.values.map { it / totalWeight }

    when (mode) {
        InterpolationMode.LINEAR -> linearInterpolation(styleVectors, normalizedWeights)
        InterpolationMode.SPHERICAL -> sphericalInterpolation(styleVectors, normalizedWeights)
    }
}

private fun linearInterpolation(vectors: List<FloatArray>, weights: List<Float>): Array<FloatArray> {
    return arrayOf(
        FloatArray(256) { i ->
            vectors.mapIndexed { idx, vec -> vec[i] * weights[idx] }.sum()
        }
    )
}

private fun sphericalInterpolation(vectors: List<FloatArray>, weights: List<Float>): Array<FloatArray> {
    val normalizedVectors = vectors.map { vec ->
        val norm = sqrt(vec.sumOf { it.toDouble().pow(2) })
        vec.map { (it / norm).toFloat() }.toFloatArray()
    }

    return arrayOf(
        FloatArray(256) { i ->
            normalizedVectors.mapIndexed { idx, vec ->
                vec[i] * weights[idx]
            }.sum()
        }
    )
}

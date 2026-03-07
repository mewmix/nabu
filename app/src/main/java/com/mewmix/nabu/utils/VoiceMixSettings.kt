package com.mewmix.nabu.utils

data class VoiceMixConfig(
    val styles: List<String>,
    val weights: Map<String, Float>,
    val interpolationMode: InterpolationMode,
)

data class VoiceMixFavorite(
    val name: String,
    val styles: List<String>,
    val weights: Map<String, Float>,
    val interpolationMode: InterpolationMode,
)

fun VoiceMixConfig.normalized(defaultStyle: String): VoiceMixConfig {
    val normalizedStyles = styles
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .ifEmpty { listOf(defaultStyle) }
    val normalizedWeights = normalizedStyles.associateWith { style ->
        (weights[style] ?: 1f).coerceIn(0f, 1f)
    }
    return copy(styles = normalizedStyles, weights = normalizedWeights)
}

fun VoiceMixConfig.filterToAvailableStyles(
    availableStyles: Collection<String>,
    fallbackStyle: String,
): VoiceMixConfig {
    if (availableStyles.isEmpty()) {
        return normalized(fallbackStyle)
    }

    val filteredStyles = styles
        .map(String::trim)
        .filter { it in availableStyles }
        .distinct()
        .ifEmpty { listOf(fallbackStyle) }
    val filteredWeights = filteredStyles.associateWith { style ->
        (weights[style] ?: 1f).coerceIn(0f, 1f)
    }
    return copy(styles = filteredStyles, weights = filteredWeights)
}

fun VoiceMixFavorite.toConfig(): VoiceMixConfig =
    VoiceMixConfig(
        styles = styles,
        weights = weights,
        interpolationMode = interpolationMode,
    )

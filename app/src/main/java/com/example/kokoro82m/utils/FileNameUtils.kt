package com.example.kokoro82m.utils

import java.util.Locale

fun buildStyleFileName(styles: List<String>, weights: Map<String, Float>, mode: InterpolationMode): String {
    val parts = styles.map { s ->
        val w = String.format(Locale.US, "%.2f", weights[s] ?: 1f)
        "${s}_$w"
    }
    return (parts + mode.name.lowercase()).joinToString("-")
}

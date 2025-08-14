package com.example.nabu.utils

data class Project(
    val uri: String,
    val name: String = "",
    val styles: List<String>,
    val weights: Map<String, Float>,
    val mode: InterpolationMode,
    val speed: Float,
    val bookmark: Bookmark?,
    val audioPath: String? = null,
    val usePregenerated: Boolean = false,
)

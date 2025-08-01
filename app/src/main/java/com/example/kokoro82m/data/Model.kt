package com.example.kokoro82m.data

data class Model(
    val id: String,
    val name: String,
    val description: String,
    val repo: String,
    val downloadUrl: String,
    val gated: Boolean,
    var isDownloaded: Boolean = false,
    var hasPartial: Boolean = false,
)

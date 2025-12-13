package com.example.nabu.data

enum class ModelType {
    LLM,
    TTS
}

data class Model(
    val id: String,
    val name: String,
    val description: String,
    val repo: String,
    val downloadUrl: String,
    val gated: Boolean,
    val type: ModelType = ModelType.LLM,
    var isDownloaded: Boolean = false,
    var hasPartial: Boolean = false,
)

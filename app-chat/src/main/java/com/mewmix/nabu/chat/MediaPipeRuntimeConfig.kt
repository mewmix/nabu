package com.mewmix.nabu.chat

import com.google.mediapipe.tasks.genai.llminference.LlmInference

data class MediaPipeRuntimeConfig(
    val maxTokens: Int,
    val maxTopK: Int,
    val topK: Int,
    val topP: Float,
    val temperature: Float,
    val randomSeed: Int,
    val preferredBackend: LlmInference.Backend?
)

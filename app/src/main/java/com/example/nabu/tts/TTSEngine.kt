package com.example.nabu.tts

interface TTSEngine : AutoCloseable {
    suspend fun synthesize(
        text: String,
        speed: Float = 1.0f
    ): AudioResult

    val sampleRate: Int
    val name: String
    val provider: String
}

data class AudioResult(
    val wav: FloatArray,
    val sampleRate: Int
)

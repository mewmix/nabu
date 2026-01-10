package com.example.nabu.speech

/**
 * A request to synthesize and play speech.
 */
data class SpeechRequest(
    val text: String,
    val style: String,
    val speed: Float,
    val shouldSave: Boolean = false
)

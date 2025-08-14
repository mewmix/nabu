package com.example.nabu.tts

interface TtsEngine {
    // Returns the list of voice names available for this engine.
    fun getAvailableVoices(): List<String>

    // Generates audio data from text.
    fun generate(text: String, voice: String, speed: Float): FloatArray

    // Returns the native sample rate of the engine's output.
    fun getSampleRate(): Int

    // Cleans up any resources used by the engine.
    fun close()
}
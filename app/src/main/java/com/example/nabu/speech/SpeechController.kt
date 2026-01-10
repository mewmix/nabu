package com.example.nabu.speech

import kotlinx.coroutines.flow.StateFlow

/**
 * Interface for controlling the speech synthesis and playback pipeline.
 */
interface SpeechController {
    /**
     * The current state of the speech pipeline.
     */
    val state: StateFlow<SpeechState>

    /**
     * Start speaking the given request.
     * If already speaking, stops the current speech and starts the new one.
     */
    fun speak(request: SpeechRequest)

    /**
     * Stop the current speech synthesis and playback.
     */
    fun stop()

    /**
     * Pause the current playback (if playing).
     */
    fun pause()

    /**
     * Resume the paused playback (if paused).
     */
    fun resume()
}

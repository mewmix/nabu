package com.example.nabu.speech

/**
 * Represents the current state of the speech synthesis and playback pipeline.
 */
sealed class SpeechState {
    data object Idle : SpeechState()
    data object PreparingModels : SpeechState()
    data class Chunking(val totalChunks: Int) : SpeechState()
    data class Synthesizing(val currentChunk: Int, val totalChunks: Int) : SpeechState()
    data object Buffering : SpeechState()
    data class Playing(val currentChunk: Int, val totalChunks: Int) : SpeechState()
    data class Paused(val currentChunk: Int, val totalChunks: Int) : SpeechState()
    data class Error(val message: String) : SpeechState()

    /**
     * Format the state as a short single-line status message.
     */
    fun toStatusString(): String = when (this) {
        is Idle -> "Ready"
        is PreparingModels -> "Preparing models..."
        is Chunking -> "Chunking text ($totalChunks chunks)..."
        is Synthesizing -> "Synthesizing $currentChunk/$totalChunks..."
        is Buffering -> "Buffering..."
        is Playing -> "Playing $currentChunk/$totalChunks"
        is Paused -> "Paused $currentChunk/$totalChunks"
        is Error -> "Error: $message"
    }
}

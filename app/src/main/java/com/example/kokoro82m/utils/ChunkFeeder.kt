package com.example.kokoro82m.utils

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Feeds text chunks into your existing player WITHOUT changing it.
 * Wire the two TODOs to your real queueing/await functions.
 */
object ChunkFeeder {
    private var job: Job? = null

    fun start(scope: CoroutineScope, textFlow: kotlinx.coroutines.flow.Flow<String>) {
        stop()
        job = scope.launch {
            textFlow.collectLatest { chunk ->
                // TODO: enqueue this chunk into your existing system
                // e.g., Player.enqueueText(chunk) or TtsEngine.speak(chunk)
                enqueueText(chunk)

                // TODO: wait until that chunk finishes (or poll your callback)
                awaitChunkFinished()
            }
        }
    }

    fun stop() { job?.cancel(); job = null }

    // --- Map these two to your real player/tts glue ---
    private suspend fun enqueueText(text: String) {
        // Example:
        // com.example.kokoro82m.audio.Player.enqueue(text)
    }
    private suspend fun awaitChunkFinished() {
        // Example:
        // com.example.kokoro82m.audio.Player.awaitIdle()
    }
}


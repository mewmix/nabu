package com.example.nabu.tts

import com.example.nabu.utils.DebugLogger

class BenchmarkingTTSEngine(val delegate: TTSEngine) : TTSEngine {

    override val sampleRate: Int
        get() = delegate.sampleRate

    override val name: String
        get() = delegate.name

    override val provider: String
        get() = delegate.provider

    override suspend fun synthesize(text: String, speed: Float): AudioResult {
        val start = System.currentTimeMillis()
        DebugLogger.log("BenchmarkingTTSEngine: Starting synthesis for '$name' on '$provider'")
        
        try {
            val result = delegate.synthesize(text, speed)
            val duration = System.currentTimeMillis() - start
            val audioDurationSecs = result.wav.size.toFloat() / result.sampleRate
            val rtf = if (audioDurationSecs > 0) duration / (audioDurationSecs * 1000) else 0f
            
            DebugLogger.log(
                "BenchmarkingTTSEngine: Synthesis complete. " +
                "Time: ${duration}ms, Audio: ${"%.2f".format(audioDurationSecs)}s, RTF: ${"%.2f".format(rtf)}"
            )
            return result
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - start
            DebugLogger.log("BenchmarkingTTSEngine: Synthesis failed after ${duration}ms: ${e.message}")
            throw e
        }
    }

    override fun close() {
        delegate.close()
    }
}

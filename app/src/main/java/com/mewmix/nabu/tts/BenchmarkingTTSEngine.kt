package com.mewmix.nabu.tts

import com.mewmix.nabu.utils.DebugLogger

class BenchmarkingTTSEngine(val delegate: TTSEngine) : TTSEngine {

    override val sampleRate: Int
        get() = delegate.sampleRate

    override val name: String
        get() = delegate.name

    override val provider: String
        get() = delegate.provider

    override suspend fun synthesize(text: String, speed: Float): AudioResult {
        val start = System.currentTimeMillis()
        DebugLogger.log("BenchmarkingTTSEngine: Requesting synthesis. Engine: '$name', Provider: '$provider'")
        DebugLogger.log("BenchmarkingTTSEngine: Input text (length=${text.length}): \"${text.take(50)}${if (text.length > 50) "..." else ""}\"")
        DebugLogger.log("BenchmarkingTTSEngine: Parameters - Speed: $speed")
        
        try {
            val result = delegate.synthesize(text, speed)
            val duration = System.currentTimeMillis() - start
            val audioDurationSecs = result.wav.size.toFloat() / result.sampleRate
            val rtf = if (audioDurationSecs > 0) duration / (audioDurationSecs * 1000) else 0f
            
            DebugLogger.log(
                "BenchmarkingTTSEngine: Synthesis success. " +
                "Wall Time: ${duration}ms, Audio Duration: ${"%.2f".format(audioDurationSecs)}s, RTF: ${"%.4f".format(rtf)}"
            )
            DebugLogger.log("BenchmarkingTTSEngine: Output stats - Sample Rate: ${result.sampleRate}, Samples: ${result.wav.size}")

            return result
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - start
            DebugLogger.log("BenchmarkingTTSEngine: Synthesis FAILED after ${duration}ms")
            DebugLogger.log("BenchmarkingTTSEngine: Error details: ${e.message}")
            e.printStackTrace() // Ensure stacktrace is printed to standard error stream too
            throw e
        }
    }

    override fun close() {
        delegate.close()
    }
}

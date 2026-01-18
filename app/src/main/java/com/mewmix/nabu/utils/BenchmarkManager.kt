package com.mewmix.nabu.utils

import android.content.Context
import android.os.SystemClock

/**
 * Collects benchmarking information for LLM and TTS operations.
 * When enabled via [SettingsManager.isBenchmark], metrics are logged
 * through [DebugLogger] so they can be inspected or saved to file.
 */
object BenchmarkManager {
    private var llmStart: Long = 0L
    private var firstToken: Long = 0L
    private var tokenCount: Int = 0
    private var lastToken: Long = 0L

    fun startLlm() {
        llmStart = SystemClock.elapsedRealtime()
        firstToken = 0L
        tokenCount = 0
        lastToken = llmStart
    }

    fun recordPartial(text: String) {
        val now = SystemClock.elapsedRealtime()
        if (tokenCount == 0) {
            firstToken = now
            DebugLogger.log("LLM time to first token: ${firstToken - llmStart} ms")
        }
        tokenCount += text.trim().split(Regex("\\s+")).size
        val elapsed = now - llmStart
        val tps = tokenCount * 1000f / elapsed.coerceAtLeast(1)
        DebugLogger.log("LLM tokens per second: ${"%.2f".format(tps)}")
        lastToken = now
    }

    fun finishLlm() {
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - llmStart
        DebugLogger.log("LLM total tokens: $tokenCount in $elapsed ms")
        lastToken = now
    }

    fun handoff() {
        val now = SystemClock.elapsedRealtime()
        DebugLogger.log("LLM->TTS handoff delay: ${now - lastToken} ms")
    }

    fun recordTts(bundle: com.mewmix.nabu.kokoro.KokoroBundle?, genTimeMs: Long, audioDurationMs: Long) {
        val ratio = if (genTimeMs > 0) audioDurationMs.toFloat() / genTimeMs else 0f
        val runtime = bundle?.ep?.name ?: "UNKNOWN"
        val graph = bundle?.graphId ?: "n/a"
        DebugLogger.log(
            "TTS Kokoro[$runtime/$graph] gen ${genTimeMs}ms for ${audioDurationMs}ms audio (x${"%.2f".format(ratio)})"
        )
    }

    fun profileSystem(context: Context) {
        PerfKit.profile(context)
    }
}

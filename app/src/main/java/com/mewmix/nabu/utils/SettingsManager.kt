package com.mewmix.nabu.utils

import android.content.Context
import com.mewmix.nabu.chat.LlmRuntimeConfig
import com.mewmix.nabu.chat.LlmRuntimeOverrides
import com.mewmix.nabu.kokoro.RunEp
import kotlin.math.max
import kotlin.math.min

object SettingsManager {
    private const val KEY_INIT_COMPLETE = "init_complete"
    private const val KEY_KOKORO_AUTO_DOWNLOAD = "kokoro_auto_download"
    private const val KEY_SUPERTONIC_MODEL_ID = "supertonic_model_id"
    private const val KEY_LAST_BOOK_URI = "last_book_uri"
    private const val KEY_LLM_THREADS_AUTO = "llm_threads_auto"
    private const val KEY_LLM_THREADS = "llm_threads"
    private const val KEY_LLM_THREADS_BATCH = "llm_threads_batch"
    private const val KEY_LLM_MAX_NEW_TOKENS = "llm_max_new_tokens"
    private const val KEY_LLM_N_CTX = "llm_n_ctx"
    private const val KEY_LLM_N_BATCH = "llm_n_batch"
    private const val KEY_LLM_TTFT_TIMEOUT_MS = "llm_ttft_timeout_ms"
    private const val KEY_LLM_TOTAL_TIMEOUT_MS = "llm_total_timeout_ms"
    private const val KEY_METHOD_TRACING = "method_tracing"
    private const val KEY_API_ENABLED = "api_enabled"

    fun setDebug(context: Context, enabled: Boolean) {
        DatabaseManager.setSetting(context, "debug", if (enabled) "1" else "0")
    }

    fun isDebug(context: Context): Boolean =
        (DatabaseManager.getSetting(context, "debug") ?: "0") == "1"

    fun setBenchmark(context: Context, enabled: Boolean) {
        DatabaseManager.setSetting(context, "benchmark", if (enabled) "1" else "0")
    }

    fun isBenchmark(context: Context): Boolean =
        (DatabaseManager.getSetting(context, "benchmark") ?: "0") == "1"

    fun setMethodTracingEnabled(context: Context, enabled: Boolean) {
        DatabaseManager.setSetting(context, KEY_METHOD_TRACING, if (enabled) "1" else "0")
    }

    fun isMethodTracingEnabled(context: Context): Boolean =
        (DatabaseManager.getSetting(context, KEY_METHOD_TRACING) ?: "0") == "1"

    fun setApiEnabled(context: Context, enabled: Boolean) {
        DatabaseManager.setSetting(context, KEY_API_ENABLED, if (enabled) "1" else "0")
    }

    fun isApiEnabled(context: Context, default: Boolean = false): Boolean {
        val fallback = if (default) "1" else "0"
        return (DatabaseManager.getSetting(context, KEY_API_ENABLED) ?: fallback) == "1"
    }

    fun setStyle(context: Context, style: String) {
        DatabaseManager.setSetting(context, "style", style)
    }

    fun getStyle(context: Context, default: String = "af_sky"): String =
        DatabaseManager.getSetting(context, "style") ?: default

    fun setSpeed(context: Context, speed: Float) {
        DatabaseManager.setSetting(context, "speed", speed.toString())
    }

    fun getSpeed(context: Context, default: Float = 1.0f): Float =
        DatabaseManager.getSetting(context, "speed")?.toFloat() ?: default

    fun setTtsEnabled(context: Context, enabled: Boolean) {
        DatabaseManager.setSetting(context, "tts_enabled", if (enabled) "1" else "0")
    }

    fun isTtsEnabled(context: Context, default: Boolean = true): Boolean {
        val fallback = if (default) "1" else "0"
        return (DatabaseManager.getSetting(context, "tts_enabled") ?: fallback) == "1"
    }

    fun setRuntimePreference(context: Context, ep: RunEp) {
        DatabaseManager.setSetting(context, "kokoro_ep", ep.name)
    }

    fun getRuntimePreference(context: Context, default: RunEp = RunEp.AUTO): RunEp =
        DatabaseManager.getSetting(context, "kokoro_ep")?.let {
            runCatching { RunEp.valueOf(it) }.getOrNull()
        } ?: default

    fun setTtsEngine(context: Context, engine: String) {
        DatabaseManager.setSetting(context, "tts_engine", engine)
    }

    fun getTtsEngine(context: Context, default: String = "kokoro"): String =
        DatabaseManager.getSetting(context, "tts_engine") ?: default

    fun setInitComplete(context: Context, complete: Boolean) {
        DatabaseManager.setSetting(context, KEY_INIT_COMPLETE, if (complete) "1" else "0")
    }

    fun isInitComplete(context: Context): Boolean =
        (DatabaseManager.getSetting(context, KEY_INIT_COMPLETE) ?: "0") == "1"

    fun setKokoroAutoDownload(context: Context, enabled: Boolean) {
        DatabaseManager.setSetting(context, KEY_KOKORO_AUTO_DOWNLOAD, if (enabled) "1" else "0")
    }

    fun isKokoroAutoDownloadEnabled(context: Context, default: Boolean = true): Boolean {
        val fallback = if (default) "1" else "0"
        return (DatabaseManager.getSetting(context, KEY_KOKORO_AUTO_DOWNLOAD) ?: fallback) == "1"
    }

    fun setSupertonicModelId(context: Context, modelId: String?) {
        if (modelId.isNullOrBlank()) {
            DatabaseManager.setSetting(context, KEY_SUPERTONIC_MODEL_ID, "")
        } else {
            DatabaseManager.setSetting(context, KEY_SUPERTONIC_MODEL_ID, modelId)
        }
    }

    fun getSupertonicModelId(context: Context): String? =
        DatabaseManager.getSetting(context, KEY_SUPERTONIC_MODEL_ID)?.ifBlank { null }

    fun setLastBookUri(context: Context, uri: String?) {
        DatabaseManager.setSetting(context, KEY_LAST_BOOK_URI, uri ?: "")
    }

    fun getLastBookUri(context: Context): String? =
        DatabaseManager.getSetting(context, KEY_LAST_BOOK_URI)?.ifBlank { null }

    fun setVibrationsEnabled(context: Context, enabled: Boolean) {
        DatabaseManager.setSetting(context, "vibrations_enabled", if (enabled) "1" else "0")
    }

    fun isVibrationsEnabled(context: Context, default: Boolean = true): Boolean {
        val fallback = if (default) "1" else "0"
        return (DatabaseManager.getSetting(context, "vibrations_enabled") ?: fallback) == "1"
    }

    private fun defaultLlmThreads(): Int {
        val cpuCount = max(1, Runtime.getRuntime().availableProcessors())
        val halfUp = (cpuCount + 1) / 2
        val base = max(2, halfUp)
        return min(6, min(base, cpuCount))
    }

    private fun clampInt(value: Int, minValue: Int, maxValue: Int): Int {
        return value.coerceIn(minValue, maxValue)
    }

    private fun clampLong(value: Long, minValue: Long, maxValue: Long): Long {
        return value.coerceIn(minValue, maxValue)
    }

    fun setLlmThreadsAuto(context: Context, enabled: Boolean) {
        DatabaseManager.setSetting(context, KEY_LLM_THREADS_AUTO, if (enabled) "1" else "0")
    }

    fun isLlmThreadsAuto(context: Context, default: Boolean = true): Boolean {
        val fallback = if (default) "1" else "0"
        return (DatabaseManager.getSetting(context, KEY_LLM_THREADS_AUTO) ?: fallback) == "1"
    }

    fun setLlmThreads(context: Context, threads: Int) {
        DatabaseManager.setSetting(context, KEY_LLM_THREADS, threads.toString())
    }

    fun getLlmThreads(context: Context, default: Int = defaultLlmThreads()): Int {
        return DatabaseManager.getSetting(context, KEY_LLM_THREADS)?.toIntOrNull() ?: default
    }

    fun setLlmThreadsBatch(context: Context, threads: Int) {
        DatabaseManager.setSetting(context, KEY_LLM_THREADS_BATCH, threads.toString())
    }

    fun getLlmThreadsBatch(context: Context, default: Int): Int {
        return DatabaseManager.getSetting(context, KEY_LLM_THREADS_BATCH)?.toIntOrNull() ?: default
    }

    fun setLlmMaxNewTokens(context: Context, value: Int) {
        DatabaseManager.setSetting(context, KEY_LLM_MAX_NEW_TOKENS, value.toString())
    }

    fun getLlmMaxNewTokens(context: Context, default: Int = 64): Int {
        return DatabaseManager.getSetting(context, KEY_LLM_MAX_NEW_TOKENS)?.toIntOrNull() ?: default
    }

    fun setLlmNCtx(context: Context, value: Int) {
        DatabaseManager.setSetting(context, KEY_LLM_N_CTX, value.toString())
    }

    fun getLlmNCtx(context: Context, default: Int = 2048): Int {
        return DatabaseManager.getSetting(context, KEY_LLM_N_CTX)?.toIntOrNull() ?: default
    }

    fun setLlmNBatch(context: Context, value: Int) {
        DatabaseManager.setSetting(context, KEY_LLM_N_BATCH, value.toString())
    }

    fun getLlmNBatch(context: Context, default: Int = 64): Int {
        return DatabaseManager.getSetting(context, KEY_LLM_N_BATCH)?.toIntOrNull() ?: default
    }

    fun setLlmTtftTimeoutMs(context: Context, value: Long) {
        DatabaseManager.setSetting(context, KEY_LLM_TTFT_TIMEOUT_MS, value.toString())
    }

    fun getLlmTtftTimeoutMs(context: Context, default: Long = 10_000L): Long {
        return DatabaseManager.getSetting(context, KEY_LLM_TTFT_TIMEOUT_MS)?.toLongOrNull() ?: default
    }

    fun setLlmTotalTimeoutMs(context: Context, value: Long) {
        DatabaseManager.setSetting(context, KEY_LLM_TOTAL_TIMEOUT_MS, value.toString())
    }

    fun getLlmTotalTimeoutMs(context: Context, default: Long = 60_000L): Long {
        return DatabaseManager.getSetting(context, KEY_LLM_TOTAL_TIMEOUT_MS)?.toLongOrNull() ?: default
    }

    fun getLlmRuntimeConfig(
        context: Context,
        overrides: LlmRuntimeOverrides? = null
    ): LlmRuntimeConfig {
        val cpuCount = max(1, Runtime.getRuntime().availableProcessors())
        val threadsAuto = overrides?.threadsAuto ?: isLlmThreadsAuto(context)
        val desiredThreads = overrides?.nThreads
            ?: if (threadsAuto) defaultLlmThreads() else getLlmThreads(context)
        val nThreads = clampInt(desiredThreads, 1, cpuCount)

        val desiredThreadsBatch = overrides?.nThreadsBatch ?: getLlmThreadsBatch(context, nThreads)
        val nThreadsBatch = clampInt(desiredThreadsBatch, 1, cpuCount)

        val desiredCtx = overrides?.nCtx ?: getLlmNCtx(context)
        val nCtx = clampInt(desiredCtx, 256, 16384)

        val desiredBatch = overrides?.nBatch ?: getLlmNBatch(context)
        val nBatch = clampInt(desiredBatch, 1, 512)

        val desiredMaxTokens = overrides?.maxNewTokens ?: getLlmMaxNewTokens(context)
        val maxNewTokens = clampInt(desiredMaxTokens, 1, 4096)

        val desiredTtft = overrides?.ttftTimeoutMs ?: getLlmTtftTimeoutMs(context)
        val ttftTimeoutMs = clampLong(desiredTtft, 1_000L, 600_000L)

        val desiredTotal = overrides?.totalTimeoutMs ?: getLlmTotalTimeoutMs(context)
        val totalTimeoutMs = clampLong(desiredTotal, 1_000L, 600_000L)

        return LlmRuntimeConfig(
            nCtx = nCtx,
            nBatch = nBatch,
            nThreads = nThreads,
            nThreadsBatch = nThreadsBatch,
            maxNewTokens = maxNewTokens,
            ttftTimeoutMs = ttftTimeoutMs,
            totalTimeoutMs = totalTimeoutMs
        )
    }
}

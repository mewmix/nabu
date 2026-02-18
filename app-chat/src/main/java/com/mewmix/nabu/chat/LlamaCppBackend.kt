package com.mewmix.nabu.chat

import android.content.Context
import com.mewmix.nabu.utils.DebugLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class LlamaCppBackend(
    private val context: Context,
    private val modelPath: String,
    initialConfig: LlmRuntimeConfig
) : LlmBackend {

    private val initLock = Any()
    @Volatile private var config: LlmRuntimeConfig = initialConfig
    private var modelHandle: Long = 0L

    val currentConfig: LlmRuntimeConfig
        get() = config

    fun updateConfig(newConfig: LlmRuntimeConfig) {
        val oldConfig = config
        val needsReinit = modelHandle != 0L &&
            (oldConfig.nCtx != newConfig.nCtx || oldConfig.nBatch != newConfig.nBatch)
        config = newConfig
        if (modelHandle != 0L && LlamaBridge.isAvailable) {
            if (needsReinit) {
                DebugLogger.log("LlamaCppBackend config change requires reinit; closing existing context")
                LlamaBridge.close(modelHandle)
                modelHandle = 0L
            } else {
                LlamaBridge.setThreads(modelHandle, newConfig.nThreads, newConfig.nThreadsBatch)
            }
        }
    }

    override fun initialize() {
        if (modelHandle != 0L) return
        synchronized(initLock) {
            if (modelHandle != 0L) return
            if (!LlamaBridge.isAvailable) {
                DebugLogger.log("LlamaCppBackend: native llama.cpp backend not available")
                return
            }
            DebugLogger.log("LlamaCppBackend initialize start: $modelPath")
            val localConfig = config
            modelHandle = LlamaBridge.init(
                modelPath,
                localConfig.nCtx,
                localConfig.nBatch,
                localConfig.nThreads,
                localConfig.nThreadsBatch
            )
            if (modelHandle == 0L) {
                DebugLogger.log("LlamaCppBackend failed to initialize model")
            } else {
                DebugLogger.log("LlamaCppBackend initialize complete")
            }
        }
    }

    override fun sendMessage(
        conversation: List<LlmMessage>,
        resultListener: (partialResult: String, done: Boolean) -> Unit
    ) {
        if (conversation.isEmpty()) {
            DebugLogger.log("LlamaCppBackend sendMessage received empty conversation; aborting request")
            return
        }
        if (modelHandle == 0L) {
            initialize()
        }
        if (modelHandle == 0L) {
            resultListener("Llama.cpp backend unavailable.", true)
            return
        }

        val prompt = buildConversationPrompt(conversation)
        DebugLogger.log("LlamaCppBackend sendMessage with ${conversation.size} turns")
        CoroutineScope(Dispatchers.IO).launch {
            val localConfig = config
            val callback = object : LlamaBridge.TokenCallback {
                override fun onToken(chunk: String) {
                    if (chunk.isNotEmpty()) {
                        resultListener(chunk, false)
                    }
                }

                override fun onComplete() {
                    resultListener("", true)
                }

                override fun onError(message: String) {
                    DebugLogger.log("LlamaCppBackend generation error: $message")
                    resultListener("Llama.cpp generation error: $message", true)
                }
            }

            val ok = LlamaBridge.generate(
                modelHandle,
                prompt,
                localConfig.maxNewTokens,
                localConfig.ttftTimeoutMs,
                localConfig.totalTimeoutMs,
                callback
            )
            if (!ok) {
                resultListener("Llama.cpp generation failed.", true)
            }
        }
    }

    override fun sendMessage(
        prompt: String,
        resultListener: (partialResult: String, done: Boolean) -> Unit
    ) {
        if (modelHandle == 0L) {
            initialize()
        }
        if (modelHandle == 0L) {
            resultListener("Llama.cpp backend unavailable.", true)
            return
        }

        DebugLogger.log("LlamaCppBackend sendMessage: ${prompt.take(120)}")
        CoroutineScope(Dispatchers.IO).launch {
            val localConfig = config
            val callback = object : LlamaBridge.TokenCallback {
                override fun onToken(chunk: String) {
                    if (chunk.isNotEmpty()) {
                        resultListener(chunk, false)
                    }
                }

                override fun onComplete() {
                    resultListener("", true)
                }

                override fun onError(message: String) {
                    DebugLogger.log("LlamaCppBackend generation error: $message")
                    resultListener("Llama.cpp generation error: $message", true)
                }
            }

            val ok = LlamaBridge.generate(
                modelHandle,
                prompt,
                localConfig.maxNewTokens,
                localConfig.ttftTimeoutMs,
                localConfig.totalTimeoutMs,
                callback
            )
            if (!ok) {
                resultListener("Llama.cpp generation failed.", true)
            }
        }
    }

    override fun supportsImageInput(): Boolean = false

    override fun sendMessage(
        conversation: List<LlmMessage>,
        image: LlmImageInput,
        resultListener: (partialResult: String, done: Boolean) -> Unit
    ) {
        resultListener("Image input is not supported by llama.cpp backend.", true)
    }

    private fun buildConversationPrompt(conversation: List<LlmMessage>): String {
        val sb = StringBuilder()
        conversation.forEach { message ->
            val role = when (message.role.lowercase()) {
                "user" -> "User"
                "assistant", "model" -> "Assistant"
                "system" -> "System"
                else -> message.role
            }
            sb.append(role).append(": ").append(message.content).append("\n")
        }
        sb.append("Assistant: ")
        return sb.toString()
    }

    override fun close() {
        if (modelHandle != 0L && LlamaBridge.isAvailable) {
            LlamaBridge.close(modelHandle)
        }
        modelHandle = 0L
        DebugLogger.log("LlamaCppBackend close")
    }

    override fun cancel() {
        if (modelHandle != 0L && LlamaBridge.isAvailable) {
            LlamaBridge.cancel(modelHandle)
        }
    }
}

package com.mewmix.nabu.chat

import android.content.Context
import com.mewmix.nabu.utils.DebugLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class LlamaCppBackend(
    private val context: Context,
    private val modelPath: String
) : LlmBackend {

    private val initLock = Any()
    private var modelHandle: Long = 0L

    override fun initialize() {
        if (modelHandle != 0L) return
        synchronized(initLock) {
            if (modelHandle != 0L) return
            if (!LlamaBridge.isAvailable) {
                DebugLogger.log("LlamaCppBackend: native llama.cpp backend not available")
                return
            }
            DebugLogger.log("LlamaCppBackend initialize start: $modelPath")
            modelHandle = LlamaBridge.init(modelPath)
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
            val response = LlamaBridge.generate(modelHandle, prompt)
            if (response.isEmpty()) {
                resultListener("", true)
                return@launch
            }
            response.split(" ").forEach { chunk ->
                resultListener("$chunk ", false)
            }
            resultListener("", true)
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
            val response = LlamaBridge.generate(modelHandle, prompt)
            resultListener(response, true)
        }
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
}

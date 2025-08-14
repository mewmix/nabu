package com.example.kokoro.chat

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions
import com.example.nabu.utils.DebugLogger

class LlmInference(
    private val context: Context,
    private val modelPath: String
) {

    private var llmInference: LlmInference? = null

    fun initialize() {
        DebugLogger.log("LlmInference initialize with model $modelPath")
        val options = LlmInferenceOptions.builder()
            .setModelPath(modelPath)
            .build()

        llmInference = LlmInference.createFromOptions(context, options)
    }

    fun sendMessage(prompt: String, resultListener: (partialResult: String, done: Boolean) -> Unit) {
        if (llmInference == null) {
            initialize()
        }

        DebugLogger.log("LlmInference sendMessage: $prompt")
        llmInference?.generateResponseAsync(prompt, resultListener)
    }

    fun close() {
        llmInference?.close()
    }

}

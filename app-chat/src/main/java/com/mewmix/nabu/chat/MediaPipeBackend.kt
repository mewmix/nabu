package com.mewmix.nabu.chat

import android.content.Context
import com.mewmix.nabu.utils.DebugLogger
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions
import java.util.concurrent.atomic.AtomicReference

class MediaPipeBackend(
    private val context: Context,
    private val modelPath: String,
    initialConfig: MediaPipeRuntimeConfig
) : LlmBackend {

    private val initLock = Any()
    @Volatile private var config: MediaPipeRuntimeConfig = initialConfig
    private var llmInference: LlmInference? = null
    private val activeSession = AtomicReference<LlmInferenceSession?>(null)

    val currentConfig: MediaPipeRuntimeConfig
        get() = config

    fun updateConfig(newConfig: MediaPipeRuntimeConfig) {
        val oldConfig = config
        config = newConfig
        val requiresReinit = oldConfig.maxTokens != newConfig.maxTokens ||
            oldConfig.maxTopK != newConfig.maxTopK ||
            oldConfig.preferredBackend != newConfig.preferredBackend
        if (requiresReinit && llmInference != null) {
            val session = activeSession.getAndSet(null)
            session?.cancelGenerateResponseAsync()
            session?.close()
            synchronized(initLock) {
                llmInference?.close()
                llmInference = null
            }
        }
    }

    override fun initialize() {
        if (llmInference != null) return
        synchronized(initLock) {
            if (llmInference != null) return
            val localConfig = config
            DebugLogger.log("MediaPipeBackend initialize with model $modelPath")
            val optionsBuilder = LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .setMaxTokens(localConfig.maxTokens)
                .setMaxTopK(localConfig.maxTopK)
            localConfig.preferredBackend?.let { optionsBuilder.setPreferredBackend(it) }
            llmInference = LlmInference.createFromOptions(context, optionsBuilder.build())
        }
    }

    override fun sendMessage(
        conversation: List<LlmMessage>,
        resultListener: (partialResult: String, done: Boolean) -> Unit
    ) {
        if (conversation.isEmpty()) {
            DebugLogger.log("MediaPipeBackend sendMessage received empty conversation; aborting request")
            return
        }
        if (llmInference == null) {
            initialize()
        }

        val payload = buildConversationPayload(conversation)
        DebugLogger.log("MediaPipeBackend sendMessage with ${conversation.size} turns")
        generateWithSession(payload, resultListener)
    }

    override fun sendMessage(prompt: String, resultListener: (partialResult: String, done: Boolean) -> Unit) {
        if (llmInference == null) {
            initialize()
        }

        DebugLogger.log("MediaPipeBackend sendMessage: $prompt")
        generateWithSession(prompt, resultListener)
    }

    override fun cancel() {
        activeSession.getAndSet(null)?.cancelGenerateResponseAsync()
    }

    private fun buildConversationPayload(conversation: List<LlmMessage>): String {
        val sb = StringBuilder()
        conversation.forEach { message ->
            sb.append("<start_of_turn>${message.role}\n${message.content}<end_of_turn>\n")
        }
        sb.append("<start_of_turn>model\n")
        return sb.toString()
    }

    override fun close() {
        activeSession.getAndSet(null)?.close()
        llmInference?.close()
        llmInference = null
    }

    private fun generateWithSession(
        prompt: String,
        resultListener: (partialResult: String, done: Boolean) -> Unit
    ) {
        val inference = llmInference ?: run {
            resultListener("MediaPipe backend unavailable.", true)
            return
        }
        try {
            val localConfig = config
            val boundedTopK = localConfig.topK.coerceIn(1, localConfig.maxTopK)
            val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
                .setTopK(boundedTopK)
                .setTopP(localConfig.topP)
                .setTemperature(localConfig.temperature)
                .apply {
                    if (localConfig.randomSeed >= 0) {
                        setRandomSeed(localConfig.randomSeed)
                    }
                }
                .build()

            val session = LlmInferenceSession.createFromOptions(inference, sessionOptions)
            val previousSession = activeSession.getAndSet(session)
            previousSession?.cancelGenerateResponseAsync()
            previousSession?.close()
            session.addQueryChunk(prompt)
            session.generateResponseAsync { partialResult, done ->
                resultListener(partialResult.orEmpty(), done)
                if (done) {
                    if (activeSession.compareAndSet(session, null)) {
                        session.close()
                    }
                }
            }
        } catch (t: Throwable) {
            DebugLogger.log("MediaPipeBackend generation error: ${t.message}")
            resultListener("MediaPipe generation failed.", true)
        }
    }
}

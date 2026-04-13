package com.mewmix.nabu.chat

import android.content.Context
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.mewmix.nabu.utils.DebugLogger
import com.google.mediapipe.tasks.genai.llminference.GraphOptions
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

    override fun supportsImageInput(): Boolean = true

    override fun sendMessage(
        conversation: List<LlmMessage>,
        image: LlmImageInput,
        resultListener: (partialResult: String, done: Boolean) -> Unit
    ) {
        if (conversation.isEmpty()) {
            DebugLogger.log("MediaPipeBackend image request received empty conversation; aborting request")
            return
        }
        if (llmInference == null) {
            initialize()
        }

        val payload = buildConversationPayload(conversation)
        DebugLogger.log("MediaPipeBackend sendMessage(image) with ${conversation.size} turns")
        generateWithSession(payload, resultListener, image)
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
        try {
            activeSession.getAndSet(null)?.close()
        } catch (e: Exception) {
            DebugLogger.log("MediaPipeBackend session close error: ${e.message}")
        }
        try {
            llmInference?.close()
        } catch (e: Exception) {
            DebugLogger.log("MediaPipeBackend inference close error: ${e.message}")
        }
        llmInference = null
    }

    private fun generateWithSession(
        prompt: String,
        resultListener: (partialResult: String, done: Boolean) -> Unit,
        image: LlmImageInput? = null
    ) {
        val inference = llmInference ?: run {
            resultListener("MediaPipe backend unavailable.", true)
            return
        }
        
        var attempt = 0
        val maxAttempts = 10
        
        while (attempt < maxAttempts) {
            try {
                val localConfig = config
                val boundedTopK = localConfig.topK.coerceIn(1, localConfig.maxTopK)
                val optionsBuilder = LlmInferenceSession.LlmInferenceSessionOptions.builder()
                    .setTopK(boundedTopK)
                    .setTopP(localConfig.topP)
                    .setTemperature(localConfig.temperature)
                    .apply {
                        if (localConfig.randomSeed >= 0) {
                            setRandomSeed(localConfig.randomSeed)
                        }
                    }
                if (image != null) {
                    optionsBuilder.setGraphOptions(
                        GraphOptions.builder()
                            .setEnableVisionModality(true)
                            .build()
                    )
                }
                val sessionOptions = optionsBuilder.build()

                val session = LlmInferenceSession.createFromOptions(inference, sessionOptions)
                val previousSession = activeSession.getAndSet(session)
                try {
                    previousSession?.cancelGenerateResponseAsync()
                    previousSession?.close()
                } catch (e: Exception) {
                    DebugLogger.log("Ignored error closing previous session: ${e.message}")
                }
                
                if (image != null) {
                    session.addImage(BitmapImageBuilder(image.bitmap).build())
                }
                session.addQueryChunk(prompt)
                session.generateResponseAsync { partialResult, done ->
                    resultListener(partialResult.orEmpty(), done)
                    if (done) {
                        if (activeSession.compareAndSet(session, null)) {
                            try {
                                session.close()
                            } catch (e: Exception) {
                                DebugLogger.log("Ignored error closing session inside callback: ${e.message}")
                            }
                        }
                    }
                }
                return // Success, exit retry loop
            } catch (t: Throwable) {
                if (t is IllegalStateException && t.message?.contains("Previous invocation still processing") == true) {
                    attempt++
                    DebugLogger.log("MediaPipeBackend: Previous invocation still processing. Retrying ($attempt/$maxAttempts)...")
                    Thread.sleep(100)
                } else {
                    DebugLogger.log("MediaPipeBackend generation error: ${t.message}")
                    resultListener("MediaPipe generation failed.", true)
                    return
                }
            }
        }
        
        DebugLogger.log("MediaPipeBackend generation error: Exhausted retries waiting for previous invocation.")
        resultListener("MediaPipe generation failed after retries.", true)
    }
}

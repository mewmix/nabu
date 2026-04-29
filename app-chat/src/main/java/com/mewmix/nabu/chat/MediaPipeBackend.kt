package com.mewmix.nabu.chat

import android.content.Context
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.mewmix.nabu.utils.DebugLogger
import com.google.mediapipe.tasks.genai.llminference.GraphOptions
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class MediaPipeBackend(
    private val context: Context,
    private val modelPath: String,
    initialConfig: MediaPipeRuntimeConfig
) : LlmBackend {
    companion object {
        private const val MODEL_TURN_PREFIX = "<start_of_turn>model\n"
        private const val MAX_BLANK_RETRIES = 1
    }

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
        val image = conversation.lastOrNull { it.images.isNotEmpty() }?.images?.firstOrNull()
        DebugLogger.log("MediaPipeBackend sendMessage with ${conversation.size} turns (hasImage=${image != null})")
        generateWithSession(payload, resultListener, image)
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
        image: LlmImageInput? = null,
        blankRetryCount: Int = 0
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
                DebugLogger.log("MediaPipeBackend: addQueryChunk called, prompt length=${prompt.length}, blankRetryCount=$blankRetryCount")
                val completed = AtomicBoolean(false)
                val firstVisibleTokenSeen = AtomicBoolean(false)
                val visibleResponseSeen = AtomicBoolean(false)
                val timeoutThreads = mutableListOf<Thread>()

                fun finishWithTimeout(message: String) {
                    if (!completed.compareAndSet(false, true)) return
                    timeoutThreads.forEach { it.interrupt() }
                    if (activeSession.compareAndSet(session, null)) {
                        try {
                            session.cancelGenerateResponseAsync()
                        } catch (e: Exception) {
                            DebugLogger.log("Ignored error canceling timed out session: ${e.message}")
                        }
                        try {
                            session.close()
                        } catch (e: Exception) {
                            DebugLogger.log("Ignored error closing timed out session: ${e.message}")
                        }
                    }
                    resultListener(message, true)
                }

                fun retryAfterBlankCompletion(): Boolean {
                    if (blankRetryCount >= MAX_BLANK_RETRIES) {
                        return false
                    }
                    if (!completed.compareAndSet(false, true)) {
                        return true
                    }
                    timeoutThreads.forEach { it.interrupt() }
                    val nudgePrompt = addNoBlankResponseNudge(prompt)
                    DebugLogger.log(
                        "MediaPipeBackend blank completion; retrying once with no-blank response nudge. Original prompt length=${prompt.length}, nudge prompt length=${nudgePrompt.length}"
                    )
                    if (activeSession.compareAndSet(session, null)) {
                        try {
                            session.cancelGenerateResponseAsync()
                        } catch (e: Exception) {
                            DebugLogger.log("Ignored error canceling blank session: ${e.message}")
                        }
                        try {
                            session.close()
                        } catch (e: Exception) {
                            DebugLogger.log("Ignored error closing blank session: ${e.message}")
                        }
                    }
                    Thread {
                        DebugLogger.log("MediaPipeBackend blank retry: starting retry with nudge prompt")
                        generateWithSession(
                            prompt = nudgePrompt,
                            resultListener = resultListener,
                            image = image,
                            blankRetryCount = blankRetryCount + 1
                        )
                    }.start()
                    return true
                }

                val ttftThread = Thread {
                    try {
                        Thread.sleep(localConfig.ttftTimeoutMs)
                        if (!completed.get() && !firstVisibleTokenSeen.get()) {
                            DebugLogger.log("MediaPipeBackend TTFT timeout after ${localConfig.ttftTimeoutMs}ms")
                            finishWithTimeout("MediaPipe generation timed out waiting for first token.")
                        }
                    } catch (_: InterruptedException) {
                    }
                }
                timeoutThreads += ttftThread
                ttftThread.start()

                val totalTimeoutThread = Thread {
                    try {
                        Thread.sleep(localConfig.totalTimeoutMs)
                        if (!completed.get()) {
                            DebugLogger.log("MediaPipeBackend total timeout after ${localConfig.totalTimeoutMs}ms")
                            finishWithTimeout("MediaPipe generation timed out.")
                        }
                    } catch (_: InterruptedException) {
                    }
                }
                timeoutThreads += totalTimeoutThread
                totalTimeoutThread.start()

                session.generateResponseAsync { partialResult, done ->
                    val chunk = partialResult.orEmpty()
                    if (chunk.isNotBlank()) {
                        firstVisibleTokenSeen.set(true)
                        visibleResponseSeen.set(true)
                    }
                    if (completed.get()) {
                        DebugLogger.log("MediaPipeBackend: callback ignored, already completed")
                        return@generateResponseAsync
                    }
                    if (done && !visibleResponseSeen.get() && retryAfterBlankCompletion()) {
                        DebugLogger.log("MediaPipeBackend: done=true but no visible response, retrying")
                        return@generateResponseAsync
                    }
                    if (done) {
                        DebugLogger.log("MediaPipeBackend: done=true, visibleResponseSeen=$visibleResponseSeen, chunk length=${chunk.length}")
                    }
                    resultListener(chunk, done)
                    if (done && completed.compareAndSet(false, true)) {
                        timeoutThreads.forEach { it.interrupt() }
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

    private fun addNoBlankResponseNudge(prompt: String): String {
        if (!prompt.endsWith(MODEL_TURN_PREFIX)) {
            return prompt
        }
        val prefix = prompt.removeSuffix(MODEL_TURN_PREFIX)
        return buildString {
            append(prefix)
            append("<start_of_turn>system\n")
            append("Respond now with at least one visible character. ")
            append("If a tool is appropriate, emit only the exact tool call wrapper. ")
            append("Otherwise answer in one short sentence. ")
            append("Do not return empty output.\n")
            append("<end_of_turn>\n")
            append(MODEL_TURN_PREFIX)
        }
    }
}

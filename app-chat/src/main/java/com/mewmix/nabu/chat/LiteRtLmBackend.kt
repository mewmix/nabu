package com.mewmix.nabu.chat

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.mewmix.nabu.utils.DebugLogger
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class LiteRtLmBackend(
    private val context: Context,
    private val modelPath: String
) : LlmBackend {
    private val initialized = AtomicBoolean(false)
    private val activeConversation = AtomicReference<Conversation?>(null)

    @Volatile
    private var engine: Engine? = null

    override fun initialize() {
        if (initialized.get()) return
        synchronized(this) {
            if (initialized.get()) return
            DebugLogger.log("LiteRtLmBackend initialize with model $modelPath")
            val engineConfig = EngineConfig(
                modelPath = modelPath,
                backend = selectBackend(),
                cacheDir = context.cacheDir.absolutePath
            )
            engine = Engine(engineConfig)
            engine?.initialize()
            initialized.set(true)
        }
    }

    override fun sendMessage(
        conversation: List<LlmMessage>,
        resultListener: (partialResult: String, done: Boolean) -> Unit
    ) {
        val engine = ensureEngine(resultListener) ?: return
        val prepared = prepareConversationInput(conversation)
        if (prepared == null) {
            resultListener("LiteRT-LM request is missing a user message.", true)
            return
        }

        try {
            engine.createConversation(prepared.config).use { liteConversation ->
                activeConversation.set(liteConversation)
                streamMessage(
                    conversation = liteConversation,
                    message = Message.Companion.of(prepared.prompt),
                    resultListener = resultListener
                )
            }
        } catch (t: Throwable) {
            DebugLogger.log("LiteRtLmBackend generation error: ${t.message}")
            resultListener("LiteRT-LM generation failed.", true)
        } finally {
            activeConversation.set(null)
        }
    }

    override fun sendMessage(
        prompt: String,
        resultListener: (partialResult: String, done: Boolean) -> Unit
    ) {
        val engine = ensureEngine(resultListener) ?: return
        try {
            engine.createConversation().use { liteConversation ->
                activeConversation.set(liteConversation)
                streamMessage(
                    conversation = liteConversation,
                    message = Message.Companion.of(prompt),
                    resultListener = resultListener
                )
            }
        } catch (t: Throwable) {
            DebugLogger.log("LiteRtLmBackend prompt generation error: ${t.message}")
            resultListener("LiteRT-LM generation failed.", true)
        } finally {
            activeConversation.set(null)
        }
    }

    override fun cancel() {
        val conversation = activeConversation.getAndSet(null) ?: return
        runCatching {
            conversation.cancelProcess()
            conversation.close()
        }.onFailure { DebugLogger.log("LiteRtLmBackend cancel error: ${it.message}") }
    }

    override fun close() {
        cancel()
        runCatching { engine?.close() }
            .onFailure { DebugLogger.log("LiteRtLmBackend engine close error: ${it.message}") }
        engine = null
        initialized.set(false)
    }

    private fun ensureEngine(
        resultListener: (partialResult: String, done: Boolean) -> Unit
    ): Engine? {
        if (!initialized.get()) {
            initialize()
        }
        return engine ?: run {
            resultListener("LiteRT-LM backend unavailable.", true)
            null
        }
    }

    private fun streamMessage(
        conversation: Conversation,
        message: Message,
        resultListener: (partialResult: String, done: Boolean) -> Unit
    ) {
        var emittedText = ""
        runBlocking {
            conversation.sendMessageAsync(message).collect { chunk ->
                val fullText = chunk.toString()
                val delta = if (fullText.startsWith(emittedText)) {
                    fullText.removePrefix(emittedText)
                } else {
                    fullText
                }
                if (delta.isNotEmpty()) {
                    resultListener(delta, false)
                }
                emittedText = fullText
            }
        }
        resultListener("", true)
    }

    private fun selectBackend(): Backend {
        return when (SettingsBridge.getPreferredBackend(context)) {
            "gpu" -> Backend.GPU
            else -> Backend.CPU
        }
    }

    private fun prepareConversationInput(conversation: List<LlmMessage>): PreparedConversationInput? {
        val systemPrompt = conversation.firstOrNull { it.role == "system" }?.content?.trim().orEmpty()
        val nonSystemMessages = conversation.filterNot { it.role == "system" }
        if (nonSystemMessages.none { it.role == "user" }) return null

        val transcript = buildString {
            nonSystemMessages.forEach { message ->
                val content = message.content.trim()
                if (content.isBlank()) return@forEach
                val roleLabel = when (message.role) {
                    "model" -> "Assistant"
                    else -> "User"
                }
                if (isNotEmpty()) {
                    append("\n\n")
                }
                append(roleLabel)
                append(": ")
                append(content)
            }
            if (isNotEmpty()) {
                append("\n\nAssistant:")
            }
        }.trim()

        if (transcript.isBlank()) return null

        val config = ConversationConfig(
            systemMessage = systemPrompt.takeIf { it.isNotBlank() }?.let(Message.Companion::of),
            tools = emptyList<Any>(),
            samplerConfig = SamplerConfig(
                topK = 40,
                topP = 0.95,
                temperature = 0.8
            )
        )

        return PreparedConversationInput(
            config = config,
            prompt = transcript
        )
    }

    private data class PreparedConversationInput(
        val config: ConversationConfig,
        val prompt: String
    )

    /**
     * Keep the app-chat module independent from SettingsManager in the app module.
     */
    private object SettingsBridge {
        fun getPreferredBackend(context: Context): String {
            val prefs = context.getSharedPreferences("nabu_settings", Context.MODE_PRIVATE)
            return prefs.getString("mediapipe_backend", "cpu").orEmpty()
        }
    }
}

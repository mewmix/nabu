package com.mewmix.nabu.chat

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ToolProvider
import com.mewmix.nabu.utils.DebugLogger
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class LiteRtLmBackend(
    private val context: Context,
    private val modelId: String,
    private val modelPath: String
) : LlmBackend {
    private val initialized = AtomicBoolean(false)
    private val activeConversation = AtomicReference<Conversation?>(null)
    private val initializationError = AtomicReference<String?>(null)

    @Volatile
    private var engine: Engine? = null

    override fun initialize() {
        if (initialized.get()) return
        synchronized(this) {
            if (initialized.get()) return
            DebugLogger.log("LiteRtLmBackend initialize with model $modelId at $modelPath")
            initializationError.set(null)
            val baseBackend = selectBackendForModel(modelPath)
            val isVisionSupported = VisionModelSupport.supportsImageInput(modelId)
            
            val engineConfig = EngineConfig(
                modelPath = modelPath,
                backend = baseBackend,
                visionBackend = if (isVisionSupported) baseBackend else null,
                audioBackend = null, // placeholder for future audio support
                maxNumTokens = null,
                maxNumImages = if (isVisionSupported) 1 else null,
                cacheDir = context.cacheDir.absolutePath
            )
            try {
                engine = Engine(engineConfig)
                engine?.initialize()
                initialized.set(true)
                DebugLogger.log("LiteRtLmBackend initialized modelId=$modelId backend=${engineConfig.backend} vision=${engineConfig.visionBackend != null}")
            } catch (t: Throwable) {
                engine = null
                initialized.set(false)
                val message = t.message ?: t::class.java.simpleName
                initializationError.set(message)
                DebugLogger.log("LiteRtLmBackend initialize failed: $message")
            }
        }
    }

    override fun supportsImageInput(): Boolean = VisionModelSupport.supportsImageInput(modelId)

    override fun sendMessage(
        conversation: List<LlmMessage>,
        image: LlmImageInput,
        resultListener: (partialResult: String, done: Boolean) -> Unit
    ) {
        val engine = ensureEngine(resultListener) ?: return
        val prepared = prepareConversationInput(conversation)
        if (prepared == null) {
            resultListener("LiteRT-LM image request is missing a user message.", true)
            return
        }

        try {
            engine.createConversation(prepared.config).use { liteConversation ->
                activeConversation.set(liteConversation)
                // In LiteRT-LM, images are often passed as parts of the message or via a specific API.
                // Assuming sendMessageAsync supports multimodal content if the engine is configured for vision.
                // We pass the image alongside the prompt.
                streamMessage(
                    conversation = liteConversation,
                    prompt = prepared.prompt,
                    image = image,
                    resultListener = resultListener
                )
            }
        } catch (t: Throwable) {
            DebugLogger.log("LiteRtLmBackend image generation error: ${t.message}")
            resultListener("LiteRT-LM image generation failed.", true)
        } finally {
            activeConversation.set(null)
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
                    prompt = prepared.prompt,
                    image = prepared.image,
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
                    prompt = prompt,
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
        initializationError.get()?.let { error ->
            resultListener("LiteRT-LM initialization failed: $error", true)
            return null
        }
        return engine ?: run {
            resultListener("LiteRT-LM backend unavailable.", true)
            null
        }
    }

    private fun streamMessage(
        conversation: Conversation,
        prompt: String,
        image: LlmImageInput? = null,
        resultListener: (partialResult: String, done: Boolean) -> Unit
    ) {
        var emittedText = ""
        runBlocking {
            val flow = if (image != null) {
                val contents = mutableListOf<Content>()
                if (prompt.isNotBlank()) contents.add(Content.Text(prompt))
                contents.add(Content.ImageBytes(image.toPngBytes()))
                conversation.sendMessageAsync(Contents.of(contents))
            } else {
                conversation.sendMessageAsync(prompt)
            }

            flow.collect { chunk ->
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

    private fun prepareConversationInput(conversation: List<LlmMessage>): PreparedConversationInput? {
        val systemPrompt = conversation.firstOrNull { it.role == "system" }?.content?.trim().orEmpty()
        val nonSystemMessages = conversation.filterNot { it.role == "system" }
        val latestUserMessage = nonSystemMessages.lastOrNull { it.role == "user" }
        if (latestUserMessage == null || (latestUserMessage.content.isBlank() && latestUserMessage.images.isEmpty())) return null

        val latestUserIndex = nonSystemMessages.indexOfLast { it.role == "user" }
        val initialMessages = nonSystemMessages
            .subList(0, latestUserIndex)
            .mapNotNull(::toLiteRtLmMessage)

        val config = ConversationConfig(
            systemInstruction = systemPrompt.takeIf { it.isNotBlank() }?.let(Contents.Companion::of),
            initialMessages = initialMessages,
            tools = emptyList<ToolProvider>(),
            samplerConfig = SamplerConfig(
                topK = 40,
                topP = 0.95,
                temperature = 0.8
            )
        )

        return PreparedConversationInput(
            config = config,
            prompt = latestUserMessage.content,
            image = latestUserMessage.images.firstOrNull()
        )
    }

    private fun toLiteRtLmMessage(message: LlmMessage): Message? {
        val content = message.content.trim()
        val images = message.images
        if (content.isBlank() && images.isEmpty()) return null

        return when (message.role) {
            "model" -> Message.Companion.model(content)
            "user" -> {
                if (images.isEmpty()) {
                    Message.Companion.user(content)
                } else {
                    val contents = mutableListOf<Content>()
                    if (content.isNotBlank()) contents.add(Content.Text(content))
                    images.forEach { contents.add(Content.ImageBytes(it.toPngBytes())) }
                    Message.Companion.user(Contents.of(contents))
                }
            }
            else -> null
        }
    }

    private fun selectBackendForModel(path: String): Backend {
        val normalized = path.lowercase()
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        DebugLogger.log("LiteRtLmBackend selectBackend: path=$path nativeLibDir=$nativeLibDir")
        return if (
            (normalized.contains("qualcomm") || normalized.contains("qcs") || normalized.contains("npu")) &&
            !nativeLibDir.isNullOrBlank()
        ) {
            DebugLogger.log("LiteRtLmBackend selecting NPU backend with nativeLibDir=$nativeLibDir")
            Backend.NPU(nativeLibDir)
        } else {
            DebugLogger.log("LiteRtLmBackend selecting CPU backend")
            Backend.CPU()
        }
    }

    private data class PreparedConversationInput(
        val config: ConversationConfig,
        val prompt: String,
        val image: LlmImageInput? = null
    )

    private fun LlmImageInput.toPngBytes(): ByteArray {
        val output = ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
        return output.toByteArray()
    }
}

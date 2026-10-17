package com.mewmix.nabu.chat

import android.content.Context
import android.content.pm.PackageManager
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ToolProvider
import com.google.ai.edge.litertlm.tool
import com.mewmix.nabu.utils.DebugLogger
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

data class LiteRtLmRuntimeConfig(
    val maxNumTokens: Int = MIN_CONTEXT_TOKENS,
    val topK: Int = 64,
    val topP: Double = 0.95,
    val temperature: Double = 1.0,
    val preferredBackend: String = "default",
    val enableVision: Boolean = true,
    val enableAudio: Boolean = true
) {
    companion object {
        const val MIN_CONTEXT_TOKENS = 2048
    }
}

private fun liteRtLmBackendCandidates(
    context: Context,
    path: String,
    preferredBackend: String?
): List<Backend> {
    val normalized = path.lowercase()
    val nativeLibDir = context.applicationInfo.nativeLibraryDir
    return if (
        (normalized.contains("qualcomm") || normalized.contains("qcs") || normalized.contains("npu")) &&
        !nativeLibDir.isNullOrBlank()
    ) {
        listOf(Backend.NPU(nativeLibDir))
    } else {
        when (preferredBackend?.lowercase()) {
            "cpu" -> listOf(Backend.CPU())
            "gpu" -> listOf(Backend.GPU())
            else -> if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_COMPUTE)) {
                listOf(Backend.GPU(), Backend.CPU())
            } else {
                listOf(Backend.CPU())
            }
        }
    }
}

fun selectLiteRtLmBackendForModel(context: Context, path: String, preferredBackend: String? = null): Backend {
    val candidates = liteRtLmBackendCandidates(context, path, preferredBackend)
    DebugLogger.log(
        "LiteRtLmBackend selectBackend: path=$path nativeLibDir=${context.applicationInfo.nativeLibraryDir} " +
            "preferred=${preferredBackend ?: "default"} candidates=${candidates.joinToString { it.name }}"
    )
    return candidates.first()
}

fun probeLiteRtLmModelCompatibility(
    context: Context,
    modelId: String,
    modelPath: String,
    preferredBackend: String? = null
): LiteRtLmModelCompatibility.Result {
    return LiteRtLmModelCompatibility.probeAll(
        context = context,
        modelId = modelId,
        modelPath = modelPath,
        backend = selectLiteRtLmBackendForModel(context, modelPath, preferredBackend)
    )
}

class LiteRtLmBackend(
    private val context: Context,
    private val modelId: String,
    private val modelPath: String,
    val runtimeConfig: LiteRtLmRuntimeConfig = LiteRtLmRuntimeConfig()
) : LlmBackend {
    private val initialized = AtomicBoolean(false)
    private val activeConversation = AtomicReference<Conversation?>(null)
    private val initializationError = AtomicReference<String?>(null)

    @Volatile
    private var engine: Engine? = null
    @Volatile
    private var compatibility: LiteRtLmModelCompatibility.Result? = null
    @Volatile
    private var activeSupportsImage = false
    @Volatile
    private var activeSupportsAudio = false
    @Volatile
    private var activeBackendName: String? = null

    override fun runtimeDescription(): String {
        val normalized = modelPath.lowercase()
        val accelerator = activeBackendName ?: if (
            normalized.contains("qualcomm") || normalized.contains("qcs") || normalized.contains("npu")
        ) {
            "NPU"
        } else {
            runtimeConfig.preferredBackend.uppercase().takeUnless { it == "DEFAULT" } ?: "AUTO"
        }
        return "LITERT-LM / Engine: $accelerator"
    }

    override fun initialize() {
        if (initialized.get()) return
        synchronized(this) {
            if (initialized.get()) return
            DebugLogger.log("LiteRtLmBackend initialize with model $modelId at $modelPath")
            initializationError.set(null)
            val backendCandidates = liteRtLmBackendCandidates(context, modelPath, runtimeConfig.preferredBackend)
            val resolvedCompatibility = LiteRtLmModelCompatibility.cachedOrStatic(
                context = context,
                modelId = modelId,
                modelPath = modelPath,
                desiredAudio = runtimeConfig.enableAudio && VisionModelSupport.supportsAudioInput(modelId),
                desiredVision = runtimeConfig.enableVision && VisionModelSupport.supportsImageInput(modelId)
            )
            compatibility = resolvedCompatibility
            val isAudioSupported = runtimeConfig.enableAudio && resolvedCompatibility.supportsAudio
            val isVisionSupported = runtimeConfig.enableVision && resolvedCompatibility.supportsUsableVision
            if (runtimeConfig.enableVision && resolvedCompatibility.supportsVision && !isVisionSupported) {
                DebugLogger.log(
                    "LiteRtLmBackend disabling vision for modelId=$modelId because audio+vision initialization failed."
                )
            }
            if (!resolvedCompatibility.supportsText && resolvedCompatibility.text.probed) {
                DebugLogger.log(
                    "LiteRtLmBackend text probe failed for modelId=$modelId: ${resolvedCompatibility.text.error.orEmpty()}"
                )
            }

            var lastError: Throwable? = null
            for ((index, candidate) in backendCandidates.withIndex()) {
                try {
                    val result = initializeForBackend(candidate, isVisionSupported, isAudioSupported)
                    engine = result.engine
                    initialized.set(true)
                    activeBackendName = candidate.name
                    activeSupportsImage = result.config.visionBackend != null
                    activeSupportsAudio = result.config.audioBackend != null
                    compatibility = LiteRtLmModelCompatibility.observeSuccessfulInitialization(
                        context = context,
                        modelId = modelId,
                        modelPath = modelPath,
                        audioEnabled = activeSupportsAudio,
                        visionEnabled = activeSupportsImage
                    )
                    DebugLogger.log(
                        "LiteRtLmBackend initialized modelId=$modelId backend=${result.config.backend} " +
                            "maxTokens=${runtimeConfig.maxNumTokens} vision=$activeSupportsImage audio=$activeSupportsAudio"
                    )
                    return
                } catch (t: Throwable) {
                    lastError = t
                    compatibility = LiteRtLmModelCompatibility.observeFailedInitialization(
                        context = context,
                        modelId = modelId,
                        modelPath = modelPath,
                        audioEnabled = isAudioSupported,
                        visionEnabled = isVisionSupported,
                        error = t.message ?: t::class.java.simpleName
                    )
                    if (index < backendCandidates.lastIndex) {
                        DebugLogger.log(
                            "LiteRtLmBackend ${candidate.name} initialization failed; retrying with " +
                                "${backendCandidates[index + 1].name}: ${t.message ?: t::class.java.simpleName}"
                        )
                    }
                }
            }
            handleInitializationFailure(lastError ?: IllegalStateException("No LiteRT-LM backend candidates"))
        }
    }

    override fun supportsImageInput(): Boolean =
        if (!runtimeConfig.enableVision) false else activeSupportsImage.takeIf { initialized.get() }
            ?: LiteRtLmModelCompatibility.cachedResult(context, modelId, modelPath)?.supportsUsableVision
            ?: VisionModelSupport.supportsImageInput(modelId)

    override fun supportsAudioInput(): Boolean =
        if (!runtimeConfig.enableAudio) false else activeSupportsAudio.takeIf { initialized.get() }
            ?: LiteRtLmModelCompatibility.cachedResult(context, modelId, modelPath)?.supportsAudio
            ?: VisionModelSupport.supportsAudioInput(modelId)

    override fun generateStructured(
        conversation: List<LlmMessage>,
        tools: List<LlmToolDefinition>
    ): LlmStructuredResult? {
        if (tools.isEmpty()) return null
        val activeEngine = ensureEngine { _, _ -> } ?: return LlmStructuredResult()
        val toolProviders = tools.map { definition ->
            tool(object : OpenApiTool {
                override fun getToolDescriptionJsonString(): String =
                    """{"name":${definition.name.jsonQuoted()},"description":${definition.description.jsonQuoted()},"parameters":${definition.parametersJson}}"""

                override fun execute(paramsJsonString: String): String =
                    """{"accepted":true}"""
            })
        }
        val prepared = prepareConversationInput(
            conversation = conversation,
            tools = toolProviders,
            automaticToolCalling = false
        ) ?: return LlmStructuredResult()

        return try {
            activeEngine.createConversation(prepared.config).use { liteConversation ->
                activeConversation.set(liteConversation)
                val response = liteConversation.sendMessage(prepared.prompt)
                val call = response.toolCalls.firstOrNull()
                if (call != null) {
                    LlmStructuredResult(
                        toolCall = LlmStructuredToolCall(
                            name = call.name,
                            arguments = call.arguments
                        )
                    )
                } else {
                    LlmStructuredResult(
                        text = response.toString()
                    )
                }
            }
        } catch (t: Throwable) {
            DebugLogger.logErr("LiteRtLmBackend structured generation error", t)
            LlmStructuredResult()
        } finally {
            activeConversation.set(null)
        }
    }

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
            DebugLogger.logErr("LiteRtLmBackend image generation error", t)
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
                    audio = prepared.audio,
                    resultListener = resultListener
                )
            }
        } catch (t: Throwable) {
            DebugLogger.logErr("LiteRtLmBackend generation error", t)
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
            engine.createConversation(
                ConversationConfig(samplerConfig = buildSamplerConfig())
            ).use { liteConversation ->
                activeConversation.set(liteConversation)
                streamMessage(
                    conversation = liteConversation,
                    prompt = prompt,
                    resultListener = resultListener
                )
            }
        } catch (t: Throwable) {
            DebugLogger.logErr("LiteRtLmBackend prompt generation error", t)
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
        activeBackendName = null
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

    private fun buildEngineConfig(
        backend: Backend,
        enableVision: Boolean,
        enableAudio: Boolean
    ): EngineConfig {
        return EngineConfig(
            modelPath = modelPath,
            backend = backend,
            visionBackend = if (enableVision) backend else null,
            audioBackend = if (enableAudio) backend else null,
            maxNumTokens = runtimeConfig.maxNumTokens,
            maxNumImages = if (enableVision) 1 else null,
            cacheDir = context.cacheDir.absolutePath
        )
    }

    private fun initializeEngine(config: EngineConfig): Engine {
        val candidate = Engine(config)
        return try {
            candidate.initialize()
            candidate
        } catch (t: Throwable) {
            runCatching { candidate.close() }
            throw t
        }
    }

    private fun initializeForBackend(
        backend: Backend,
        enableVision: Boolean,
        enableAudio: Boolean
    ): InitializedEngine {
        val config = buildEngineConfig(backend, enableVision, enableAudio)
        return try {
            InitializedEngine(initializeEngine(config), config)
        } catch (t: Throwable) {
            if (!enableVision || !t.isVisionEncoderSignatureError()) throw t
            DebugLogger.log(
                "LiteRtLmBackend vision init failed for modelId=$modelId; retrying without vision: " +
                    (t.message ?: t::class.java.simpleName)
            )
            val audioOnlyConfig = buildEngineConfig(backend, enableVision = false, enableAudio = enableAudio)
            InitializedEngine(initializeEngine(audioOnlyConfig), audioOnlyConfig)
        }
    }

    private fun handleInitializationFailure(t: Throwable) {
        engine = null
        initialized.set(false)
        activeSupportsImage = false
        activeSupportsAudio = false
        activeBackendName = null
        val message = t.message ?: t::class.java.simpleName
        initializationError.set(message)
        DebugLogger.logErr("LiteRtLmBackend initialize failed: $message", t)
    }

    private fun Throwable.isVisionEncoderSignatureError(): Boolean {
        val fullMessage = buildString {
            append(message.orEmpty())
            cause?.message?.let {
                append('\n')
                append(it)
            }
        }
        return fullMessage.contains("Vision Encoder", ignoreCase = true) &&
            fullMessage.contains("signature", ignoreCase = true)
    }

    private fun streamMessage(
        conversation: Conversation,
        prompt: String,
        image: LlmImageInput? = null,
        audio: LlmAudioInput? = null,
        resultListener: (partialResult: String, done: Boolean) -> Unit
    ) {
        if (audio != null && !supportsAudioInput()) {
            resultListener("LiteRT-LM audio input is not enabled for this model.", true)
            return
        }
        if (audio != null && !audio.bytes.hasWavHeader()) {
            DebugLogger.log(
                "LiteRtLmBackend rejecting non-WAV audio input name=${audio.displayName} bytes=${audio.bytes.size}"
            )
            resultListener("LiteRT-LM audio input expects WAV audio. Record a new voice message or attach a WAV file.", true)
            return
        }
        var emittedText = ""
        runBlocking {
            val flow = if (image != null || audio != null) {
                val contents = mutableListOf<Content>()
                if (audio != null) contents.add(audio.toLiteRtLmContent())
                if (image != null) contents.add(Content.ImageBytes(image.toPngBytes()))
                if (prompt.isNotBlank()) {
                    contents.add(Content.Text(prompt))
                }
                DebugLogger.log(
                    "LiteRtLmBackend sending multimodal content text=${prompt.isNotBlank()} image=${image != null} audio=${audio != null}"
                )
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

    private fun prepareConversationInput(
        conversation: List<LlmMessage>,
        tools: List<ToolProvider> = emptyList(),
        automaticToolCalling: Boolean = true
    ): PreparedConversationInput? {
        val systemPrompt = conversation.firstOrNull { it.role == "system" }?.content?.trim().orEmpty()
        val nonSystemMessages = conversation.filterNot { it.role == "system" }
        val latestUserMessage = nonSystemMessages.lastOrNull { it.role == "user" }
        if (latestUserMessage == null ||
            (latestUserMessage.content.isBlank() && latestUserMessage.images.isEmpty() && latestUserMessage.audios.isEmpty())
        ) return null

        val latestUserIndex = nonSystemMessages.indexOfLast { it.role == "user" }
        val initialMessages = nonSystemMessages
            .subList(0, latestUserIndex)
            .mapNotNull(::toLiteRtLmMessage)

        val config = ConversationConfig(
            systemInstruction = systemPrompt.takeIf { it.isNotBlank() }?.let(Contents.Companion::of),
            initialMessages = initialMessages,
            tools = tools,
            automaticToolCalling = automaticToolCalling,
            samplerConfig = buildSamplerConfig()
        )

        return PreparedConversationInput(
            config = config,
            prompt = latestUserMessage.content,
            image = latestUserMessage.images.firstOrNull(),
            audio = latestUserMessage.audios.firstOrNull()
        )
    }

    private fun toLiteRtLmMessage(message: LlmMessage): Message? {
        val content = message.content.trim()
        val images = message.images
        val audios = message.audios
        if (content.isBlank() && images.isEmpty() && audios.isEmpty()) return null

        return when (message.role) {
            "model" -> Message.Companion.model(content)
            "user" -> {
                if (images.isEmpty() && audios.isEmpty()) {
                    Message.Companion.user(content)
                } else {
                    val contents = mutableListOf<Content>()
                    audios.forEach { contents.add(it.toLiteRtLmContent()) }
                    images.forEach { contents.add(Content.ImageBytes(it.toPngBytes())) }
                    if (content.isNotBlank()) contents.add(Content.Text(content))
                    Message.Companion.user(Contents.of(contents))
                }
            }
            else -> null
        }
    }

    private data class PreparedConversationInput(
        val config: ConversationConfig,
        val prompt: String,
        val image: LlmImageInput? = null,
        val audio: LlmAudioInput? = null
    )

    private data class InitializedEngine(
        val engine: Engine,
        val config: EngineConfig
    )

    private fun buildSamplerConfig() = SamplerConfig(
        topK = runtimeConfig.topK,
        topP = runtimeConfig.topP,
        temperature = runtimeConfig.temperature
    )

    private fun LlmImageInput.toPngBytes(): ByteArray {
        val output = ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
        return output.toByteArray()
    }

    private fun LlmAudioInput.toLiteRtLmContent(): Content {
        val localPath = absolutePath?.takeIf { it.isNotBlank() && File(it).isFile }
        if (localPath != null) {
            DebugLogger.log("LiteRtLmBackend using audio file name=$displayName bytes=${bytes.size} path=$localPath")
            return Content.AudioFile(localPath)
        }
        DebugLogger.log("LiteRtLmBackend using inline audio bytes name=$displayName bytes=${bytes.size}")
        return Content.AudioBytes(bytes)
    }

    private fun ByteArray.hasWavHeader(): Boolean =
        size > 44 &&
            this[0] == 'R'.code.toByte() &&
            this[1] == 'I'.code.toByte() &&
            this[2] == 'F'.code.toByte() &&
            this[3] == 'F'.code.toByte() &&
            this[8] == 'W'.code.toByte() &&
            this[9] == 'A'.code.toByte() &&
            this[10] == 'V'.code.toByte() &&
            this[11] == 'E'.code.toByte()

    private fun String.jsonQuoted(): String =
        com.google.gson.JsonPrimitive(this).toString()
}

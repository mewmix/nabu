package com.mewmix.nabu.api

import android.content.Context
import android.graphics.BitmapFactory
import com.mewmix.nabu.chat.LlamaCppBackend
import com.mewmix.nabu.chat.LlmAudioInput
import com.mewmix.nabu.chat.LlmBackend
import com.mewmix.nabu.chat.LlmImageInput
import com.mewmix.nabu.chat.LlmMessage
import com.mewmix.nabu.chat.MediaPipeBackend
import com.mewmix.nabu.chat.LiteRtLmBackend
import com.mewmix.nabu.chat.VisionModelSupport
import com.mewmix.nabu.data.findDownloadedLlmArtifact
import com.mewmix.nabu.data.ModelManager
import com.mewmix.nabu.data.ModelType
import com.mewmix.nabu.kokoro.KokoroEngine
import com.mewmix.nabu.soprano.SopranoEngine
import com.mewmix.nabu.supertonic.DebugSupertonicEngine
import com.mewmix.nabu.supertonic.normalizeSupertonicLanguage
import com.mewmix.nabu.tts.BenchmarkingTTSEngine
import com.mewmix.nabu.tts.TTSManager
import com.mewmix.nabu.tts.TTSEngine
import com.mewmix.nabu.utils.DebugLogger
import com.mewmix.nabu.utils.PhonemeConverter
import com.mewmix.nabu.utils.SettingsManager
import com.mewmix.nabu.utils.StyleLoader
import com.mewmix.nabu.utils.createAudio
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.cio.CIO
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.Writer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ApiServer(
    private val context: Context,
    private val host: String,
    private val port: Int
) {
    private val lifecycleLock = Any()
    private val backendLock = Any()
    private val requestLock = Mutex()

    private var engine: ApplicationEngine? = null
    private var backend: LlmBackend? = null
    private var backendModelId: String? = null
    private val phonemeConverter by lazy { PhonemeConverter(context) }
    private val styleLoader by lazy { StyleLoader(context) }

    companion object {
        private const val DEFAULT_IMAGE_PROMPT = "Describe this image."
        private const val DEFAULT_TTS_VOICE = "af_sky"
        private const val DEFAULT_API_GENERATION_TIMEOUT_MS = 120_000L
        private val SUPPORTED_TTS_ENGINES = setOf("kokoro", "supertonic", "soprano")
        private val SUPPORTED_TTS_FORMATS = setOf("wav", "json")
        private val SUPPORTED_LLM_REPLY_TTS_FORMATS = setOf("json")
    }

    private data class ApiGenerationOptions(
        val maxTokens: Int? = null,
        val temperature: Float? = null,
        val topP: Float? = null,
        val randomSeed: Int? = null
    )

    fun start() {
        synchronized(lifecycleLock) {
            if (engine != null) return

            val newEngine = embeddedServer(CIO, host = host, port = port) {
                routing {
                    get("/health") {
                        val response = JSONObject().put("ok", true)
                        call.respondText(
                            text = response.toString(),
                            contentType = ContentType.Application.Json,
                            status = HttpStatusCode.OK
                        )
                    }

                    get("/models") {
                        val scope = parseModelScope(call, default = ModelScope.LLM) ?: return@get
                        handleModels(call, scope)
                    }

                    post("/models") {
                        val scope = parseModelScopeFromBody(call, default = ModelScope.LLM) ?: return@post
                        handleModels(call, scope)
                    }

                    get("/tts/models") {
                        handleModels(call, ModelScope.TTS)
                    }

                    get("/v1/models") {
                        val scope = parseModelScope(call, default = ModelScope.LLM) ?: return@get
                        handleOpenAiModels(call, scope)
                    }

                    post("/v1/models") {
                        val scope = parseModelScopeFromBody(call, default = ModelScope.LLM) ?: return@post
                        handleOpenAiModels(call, scope)
                    }

                    get("/v1/tts/models") {
                        handleOpenAiModels(call, ModelScope.TTS)
                    }

                    post("/generate") {
                        handleGenerate(call)
                    }

                    post("/v1/chat/completions") {
                        handleChatCompletions(call)
                    }

                    post("/tts/speech") {
                        handleTextToSpeech(call)
                    }

                    post("/v1/audio/speech") {
                        handleTextToSpeech(call)
                    }
                }
            }

            newEngine.start(wait = false)
            engine = newEngine
            DebugLogger.log("ApiServer started on http://$host:$port")
        }
    }

    fun stop() {
        synchronized(lifecycleLock) {
            engine?.stop(gracePeriodMillis = 1_000, timeoutMillis = 2_000)
            engine = null
        }

        synchronized(backendLock) {
            backend?.close()
            backend = null
            backendModelId = null
        }

        DebugLogger.log("ApiServer stopped")
    }

    fun isRunning(): Boolean = synchronized(lifecycleLock) { engine != null }

    private suspend fun handleGenerate(call: io.ktor.server.application.ApplicationCall) {
        try {
            val body = JSONObject(call.receiveText())
            val requestedModel = body.optString("model").ifBlank { null }
            var prompt = body.optString("prompt").trim()
            val messageArray = body.optJSONArray("messages")
            val stream = body.optBoolean("stream", false)
            val replyTts = parseReplyTtsRequest(body)
            val topLevelImage = parseTopLevelImageInput(body)
            val generationOptions = parseGenerationOptions(body)

            if (messageArray == null && topLevelImage != null && prompt.isEmpty()) {
                prompt = DEFAULT_IMAGE_PROMPT
            }

            if (prompt.isEmpty() && messageArray == null) {
                respondApiError(
                    call = call,
                    status = HttpStatusCode.BadRequest,
                    message = "Provide either 'prompt' or 'messages'."
                )
                return
            }

            if (stream && replyTts != null) {
                respondApiError(
                    call = call,
                    status = HttpStatusCode.BadRequest,
                    message = "'tts' is not supported when stream=true."
                )
                return
            }

            if (stream) {
                if (messageArray != null) {
                    val parsedMessages = parseMessages(messageArray)
                    if (topLevelImage != null) {
                        throw IllegalArgumentException("Do not provide top-level image fields when 'messages' already include image content.")
                    }
                    streamGenerateSse(
                        call = call,
                        requestedModel = requestedModel,
                        requireImageInput = parsedMessages.image != null,
                        requireAudioInput = parsedMessages.audio != null,
                        generationOptions = generationOptions,
                        sendGeneration = { modelId, backend, listener ->
                            ensureImageInputSupported(modelId, backend, parsedMessages.image)
                            ensureAudioInputSupported(modelId, backend, parsedMessages.audio)
                            if (parsedMessages.image != null && parsedMessages.audio == null) {
                                backend.sendMessage(parsedMessages.messages, parsedMessages.image, listener)
                            } else {
                                backend.sendMessage(parsedMessages.messages, listener)
                            }
                        }
                    )
                } else {
                    streamGenerateSse(
                        call = call,
                        requestedModel = requestedModel,
                        requireImageInput = topLevelImage != null,
                        generationOptions = generationOptions,
                        sendGeneration = { modelId, backend, listener ->
                            ensureImageInputSupported(modelId, backend, topLevelImage)
                            if (topLevelImage != null) {
                                backend.sendMessage(
                                    listOf(LlmMessage(role = "user", content = prompt)),
                                    topLevelImage,
                                    listener
                                )
                            } else {
                                backend.sendMessage(prompt, listener)
                            }
                        }
                    )
                }
                return
            }

            val generation = if (messageArray != null) {
                val parsedMessages = parseMessages(messageArray)
                if (topLevelImage != null) {
                    throw IllegalArgumentException("Do not provide top-level image fields when 'messages' already include image content.")
                }
                generateFromMessages(
                    requestedModel = requestedModel,
                    messages = parsedMessages.messages,
                    image = parsedMessages.image,
                    generationOptions = generationOptions
                )
            } else {
                generateFromPrompt(
                    requestedModel = requestedModel,
                    prompt = prompt,
                    image = topLevelImage,
                    generationOptions = generationOptions
                )
            }

            val response = JSONObject()
                .put("model", generation.modelId)
                .put("text", generation.text)
            maybeSynthesizeReplyTts(replyTts, generation.text)?.let { tts ->
                val wavBytes = toWavBytes(tts.audio, tts.sampleRate)
                response.put(
                    "audio",
                    JSONObject()
                        .put("engine", tts.engine)
                        .put("model", tts.modelId)
                        .put("sample_rate", tts.sampleRate)
                        .put("audio_base64", Base64.getEncoder().encodeToString(wavBytes))
                )
            }

            call.respondText(
                text = response.toString(),
                contentType = ContentType.Application.Json,
                status = HttpStatusCode.OK
            )
        } catch (t: Throwable) {
            DebugLogger.logErr("ApiServer /generate failed", t)
            val status = if (t is IllegalArgumentException) {
                HttpStatusCode.BadRequest
            } else {
                HttpStatusCode.InternalServerError
            }
            respondApiError(
                call = call,
                status = status,
                message = t.message ?: "Generation failed"
            )
        }
    }

    private suspend fun handleModels(
        call: io.ktor.server.application.ApplicationCall,
        scope: ModelScope
    ) {
        try {
            val models = listAvailableModels(scope)
            val jsonModels = JSONArray().apply {
                models.forEach { model ->
                    put(
                        JSONObject()
                            .put("id", model.id)
                            .put("name", model.name)
                            .put("backend", modelBackend(model))
                            .put("type", model.type.name.lowercase())
                            .put("capabilities", JSONArray(modelCapabilities(model).toList()))
                    )
                }
            }

            call.respondText(
                text = JSONObject().put("models", jsonModels).toString(),
                contentType = ContentType.Application.Json,
                status = HttpStatusCode.OK
            )
        } catch (t: Throwable) {
            DebugLogger.logErr("ApiServer /models failed", t)
            respondApiError(
                call = call,
                status = HttpStatusCode.InternalServerError,
                message = t.message ?: "Unable to list models"
            )
        }
    }

    private suspend fun handleOpenAiModels(
        call: io.ktor.server.application.ApplicationCall,
        scope: ModelScope
    ) {
        try {
            val models = listAvailableModels(scope)
            val nowSeconds = System.currentTimeMillis() / 1000L
            val jsonModels = JSONArray().apply {
                models.forEach { model ->
                    put(
                        JSONObject()
                            .put("id", model.id)
                            .put("object", "model")
                            .put("created", nowSeconds)
                            .put("owned_by", "local")
                            .put("metadata", JSONObject()
                                .put("type", model.type.name.lowercase())
                                .put("backend", modelBackend(model))
                                .put("capabilities", JSONArray(modelCapabilities(model).toList())))
                    )
                }
            }

            call.respondText(
                text = JSONObject()
                    .put("object", "list")
                    .put("data", jsonModels)
                    .toString(),
                contentType = ContentType.Application.Json,
                status = HttpStatusCode.OK
            )
        } catch (t: Throwable) {
            DebugLogger.logErr("ApiServer /v1/models failed", t)
            respondApiError(
                call = call,
                status = HttpStatusCode.InternalServerError,
                message = t.message ?: "Unable to list models"
            )
        }
    }

    private suspend fun handleChatCompletions(call: io.ktor.server.application.ApplicationCall) {
        try {
            val body = JSONObject(call.receiveText())
            val stream = body.optBoolean("stream", false)
            val replyTts = parseReplyTtsRequest(body)
            val generationOptions = parseGenerationOptions(body)

            val parsedTools = parseTools(body)
            val requestedModel = body.optString("model").ifBlank { null }
            val messageArray = body.optJSONArray("messages")
            if (messageArray == null || messageArray.length() == 0) {
                respondApiError(
                    call = call,
                    status = HttpStatusCode.BadRequest,
                    message = "'messages' is required."
                )
                return
            }

            val parsedMessages = parseMessages(messageArray, parsedTools)
            if (stream && replyTts != null) {
                respondApiError(
                    call = call,
                    status = HttpStatusCode.BadRequest,
                    message = "'tts' is not supported when stream=true."
                )
                return
            }
            if (stream) {
                streamChatCompletionsSse(
                    call = call,
                    requestedModel = requestedModel,
                    payload = parsedMessages,
                    generationOptions = generationOptions
                )
                return
            }

            val generation = generateFromMessages(
                requestedModel = requestedModel,
                messages = parsedMessages.messages,
                image = parsedMessages.image,
                generationOptions = generationOptions
            )
            val nowSeconds = System.currentTimeMillis() / 1000L

            val choice = JSONObject()
                .put("index", 0)

            val toolCallInfo = com.mewmix.nabu.tools.ToolCallProtocol.extractToolCall(generation.text)
            if (toolCallInfo != null) {
                val toolCallId = "call_${System.currentTimeMillis()}"
                val toolCallJson = JSONObject()
                    .put("id", toolCallId)
                    .put("type", "function")
                    .put("function", JSONObject()
                        .put("name", toolCallInfo.toolName)
                        .put("arguments", com.google.gson.Gson().toJson(toolCallInfo.arguments))
                    )
                    
                val responseMessage = JSONObject()
                    .put("role", "assistant")
                    .put("content", JSONObject.NULL)
                    .put("tool_calls", JSONArray().put(toolCallJson as Any))
                choice.put("message", responseMessage)
                choice.put("finish_reason", "tool_calls")
            } else {
                choice.put(
                    "message",
                    JSONObject()
                        .put("role", "assistant")
                        .put("content", generation.text)
                )
                choice.put("finish_reason", "stop")
            }

            val response = JSONObject()
                .put("id", "chatcmpl-${System.currentTimeMillis()}")
                .put("object", "chat.completion")
                .put("created", nowSeconds)
                .put("model", generation.modelId)
                .put("choices", JSONArray().put(choice as Any))
                .put(
                    "usage",
                    JSONObject()
                        .put("prompt_tokens", 0)
                        .put("completion_tokens", 0)
                        .put("total_tokens", 0)
                )
            maybeSynthesizeReplyTts(replyTts, generation.text)?.let { tts ->
                val wavBytes = toWavBytes(tts.audio, tts.sampleRate)
                response.put(
                    "audio",
                    JSONObject()
                        .put("engine", tts.engine)
                        .put("model", tts.modelId)
                        .put("sample_rate", tts.sampleRate)
                        .put("audio_base64", Base64.getEncoder().encodeToString(wavBytes))
                )
            }

            call.respondText(
                text = response.toString(),
                contentType = ContentType.Application.Json,
                status = HttpStatusCode.OK
            )
        } catch (t: Throwable) {
            DebugLogger.logErr("ApiServer /v1/chat/completions failed", t)
            val status = if (t is IllegalArgumentException) {
                HttpStatusCode.BadRequest
            } else {
                HttpStatusCode.InternalServerError
            }
            respondApiError(
                call = call,
                status = status,
                message = t.message ?: "Generation failed"
            )
        }
    }

    private suspend fun streamGenerateSse(
        call: io.ktor.server.application.ApplicationCall,
        requestedModel: String?,
        requireImageInput: Boolean = false,
        requireAudioInput: Boolean = false,
        generationOptions: ApiGenerationOptions = ApiGenerationOptions(),
        sendGeneration: (String, LlmBackend, (String, Boolean) -> Unit) -> Unit
    ) {
        call.respondTextWriter(
            contentType = ContentType.Text.EventStream,
            status = HttpStatusCode.OK
        ) {
            val responseId = "gen-${System.currentTimeMillis()}"
            val created = System.currentTimeMillis() / 1000L
            try {
                requestLock.withLock {
                    val (modelId, activeBackend) = getOrCreateBackend(
                        requestedModel,
                        requireImageInput = requireImageInput,
                        requireAudioInput = requireAudioInput
                    )
                    applyGenerationOptions(activeBackend, generationOptions)

                    val stream = Channel<StreamEvent>(Channel.UNLIMITED)
                    sendGeneration(modelId, activeBackend) { partial, done ->
                        if (partial.isNotEmpty()) {
                            stream.trySend(StreamEvent.Token(partial))
                        }
                        if (done) {
                            stream.trySend(StreamEvent.Done)
                            stream.close()
                            return@sendGeneration
                        }
                    }

                    var finalText = ""
                    for (event in stream) {
                        when (event) {
                            is StreamEvent.Token -> {
                                finalText += event.chunk
                                writeSseData(
                                    JSONObject()
                                        .put("id", responseId)
                                        .put("object", "generate.chunk")
                                        .put("created", created)
                                        .put("model", modelId)
                                        .put("delta", event.chunk)
                                        .toString()
                                )
                            }
                            StreamEvent.Done -> {
                                writeSseData(
                                    JSONObject()
                                        .put("id", responseId)
                                        .put("object", "generate.result")
                                        .put("created", created)
                                        .put("model", modelId)
                                        .put("text", finalText)
                                        .toString()
                                )
                                writeSseDone()
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                DebugLogger.logErr("ApiServer /generate stream failed", t)
                writeSseData(
                    JSONObject()
                        .put("error", JSONObject()
                            .put("message", t.message ?: "Generation failed")
                            .put("type", "api_error"))
                        .toString()
                )
                writeSseDone()
            }
        }
    }

    private suspend fun streamChatCompletionsSse(
        call: io.ktor.server.application.ApplicationCall,
        requestedModel: String?,
        payload: ParsedMessages,
        generationOptions: ApiGenerationOptions
    ) {
        call.respondTextWriter(
            contentType = ContentType.Text.EventStream,
            status = HttpStatusCode.OK
        ) {
            val responseId = "chatcmpl-${System.currentTimeMillis()}"
            val created = System.currentTimeMillis() / 1000L
            try {
                requestLock.withLock {
                    val (modelId, activeBackend) = getOrCreateBackend(
                        requestedModel,
                        requireImageInput = payload.image != null,
                        requireAudioInput = payload.audio != null
                    )
                    applyGenerationOptions(activeBackend, generationOptions)

                    val stream = Channel<StreamEvent>(Channel.UNLIMITED)
                    ensureImageInputSupported(modelId, activeBackend, payload.image)
                    ensureAudioInputSupported(modelId, activeBackend, payload.audio)
                    if (payload.image != null && payload.audio == null) {
                        activeBackend.sendMessage(payload.messages, payload.image) { partial, done ->
                            if (partial.isNotEmpty()) {
                                stream.trySend(StreamEvent.Token(partial))
                            }
                            if (done) {
                                stream.trySend(StreamEvent.Done)
                                stream.close()
                                return@sendMessage
                            }
                        }
                    } else {
                        activeBackend.sendMessage(payload.messages) { partial, done ->
                            if (partial.isNotEmpty()) {
                                stream.trySend(StreamEvent.Token(partial))
                            }
                            if (done) {
                                stream.trySend(StreamEvent.Done)
                                stream.close()
                                return@sendMessage
                            }
                        }
                    }

                    // Initial delta carries role for OpenAI stream parity.
                    writeSseData(
                        JSONObject()
                            .put("id", responseId)
                            .put("object", "chat.completion.chunk")
                            .put("created", created)
                            .put("model", modelId)
                            .put(
                                "choices",
                                JSONArray().put(
                                    JSONObject()
                                        .put("index", 0)
                                        .put("delta", JSONObject().put("role", "assistant"))
                                        .put("finish_reason", JSONObject.NULL)
                                )
                            )
                            .toString()
                    )

                    var finalText = ""
                    val protocol = com.mewmix.nabu.tools.ToolCallProtocol

                    for (event in stream) {
                        when (event) {
                            is StreamEvent.Token -> {
                                finalText += event.chunk
                                
                                // To avoid streaming out raw tool call tags or JSON blocks to OpenCode clients,
                                // we suppress content chunks if the accumulated text matches patterns
                                // that typically start a tool call.
                                if (!shouldSuppressToken(finalText)) {
                                    writeSseData(
                                        JSONObject()
                                            .put("id", responseId)
                                            .put("object", "chat.completion.chunk")
                                            .put("created", created)
                                            .put("model", modelId)
                                            .put(
                                                "choices",
                                                JSONArray().put(
                                                    JSONObject()
                                                        .put("index", 0)
                                                        .put("delta", JSONObject().put("content", event.chunk))
                                                        .put("finish_reason", JSONObject.NULL)
                                                )
                                            )
                                            .toString()
                                    )
                                }
                            }
                            StreamEvent.Done -> {
                                val toolCallInfo = com.mewmix.nabu.tools.ToolCallProtocol.extractToolCall(finalText)
                                if (toolCallInfo != null) {
                                    val toolCallId = "call_${System.currentTimeMillis()}"
                                    // According to OpenAI stream spec, first tool delta provides id/type/name
                                    val firstToolDelta = JSONObject()
                                        .put("index", 0)
                                        .put("id", toolCallId)
                                        .put("type", "function")
                                        .put("function", JSONObject()
                                            .put("name", toolCallInfo.toolName)
                                            .put("arguments", "")
                                        )
                                        
                                    val firstChoice = JSONObject()
                                        .put("index", 0)
                                        .put("delta", JSONObject()
                                            .put("tool_calls", JSONArray().put(firstToolDelta as Any))
                                        )
                                        .put("finish_reason", JSONObject.NULL)

                                    writeSseData(
                                        JSONObject()
                                            .put("id", responseId)
                                            .put("object", "chat.completion.chunk")
                                            .put("created", created)
                                            .put("model", modelId)
                                            .put("choices", JSONArray().put(firstChoice as Any)).toString()
                                    )
                                    
                                    // Second delta provides the arguments payload
                                    val secondToolDelta = JSONObject()
                                        .put("index", 0)
                                        .put("function", JSONObject()
                                            .put("arguments", com.google.gson.Gson().toJson(toolCallInfo.arguments))
                                        )
                                        
                                    val secondChoice = JSONObject()
                                        .put("index", 0)
                                        .put("delta", JSONObject()
                                            .put("tool_calls", JSONArray().put(secondToolDelta as Any))
                                        )
                                        .put("finish_reason", JSONObject.NULL)

                                    writeSseData(
                                        JSONObject()
                                            .put("id", responseId)
                                            .put("object", "chat.completion.chunk")
                                            .put("created", created)
                                            .put("model", modelId)
                                            .put("choices", JSONArray().put(secondChoice as Any)).toString()
                                    )
                                    
                                    // Final chunk signals tool_calls finish reason
                                    val finalChoice = JSONObject()
                                        .put("index", 0)
                                        .put("delta", JSONObject())
                                        .put("finish_reason", "tool_calls")
                                        
                                    writeSseData(
                                        JSONObject()
                                            .put("id", responseId)
                                            .put("object", "chat.completion.chunk")
                                            .put("created", created)
                                            .put("model", modelId)
                                            .put("choices", JSONArray().put(finalChoice as Any)).toString()
                                    )
                                } else {
                                    // Standard close
                                    val stdCloseChoice = JSONObject()
                                        .put("index", 0)
                                        .put("delta", JSONObject())
                                        .put("finish_reason", "stop")
                                        
                                    writeSseData(
                                        JSONObject()
                                            .put("id", responseId)
                                            .put("object", "chat.completion.chunk")
                                            .put("created", created)
                                            .put("model", modelId)
                                            .put(
                                                "choices",
                                                JSONArray().put(stdCloseChoice as Any)
                                            )
                                            .toString()
                                    )
                                }
                                writeSseDone()
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                DebugLogger.logErr("ApiServer /v1/chat/completions stream failed", t)
                writeSseData(
                    JSONObject()
                        .put("error", JSONObject()
                            .put("message", t.message ?: "Generation failed")
                            .put("type", "api_error"))
                        .toString()
                )
                writeSseDone()
            }
        }
    }

    private suspend fun handleTextToSpeech(call: io.ktor.server.application.ApplicationCall) {
        try {
            val bodyText = call.receiveText()
            val body = try {
                if (bodyText.isBlank()) JSONObject() else JSONObject(bodyText)
            } catch (_: Throwable) {
                respondApiError(
                    call = call,
                    status = HttpStatusCode.BadRequest,
                    message = "Invalid JSON body."
                )
                return
            }

            val input = body.optString("input")
                .ifBlank { body.optString("text") }
                .trim()
            if (input.isEmpty()) {
                respondApiError(
                    call = call,
                    status = HttpStatusCode.BadRequest,
                    message = "'input' is required."
                )
                return
            }

            val speed = body.optDouble("speed", 1.0).toFloat()
            if (!speed.isFinite() || speed <= 0f) {
                respondApiError(
                    call = call,
                    status = HttpStatusCode.BadRequest,
                    message = "'speed' must be a positive number."
                )
                return
            }

            val responseFormat = body.optString("response_format")
                .trim()
                .lowercase()
                .ifBlank { "wav" }
            if (responseFormat !in SUPPORTED_TTS_FORMATS) {
                respondApiError(
                    call = call,
                    status = HttpStatusCode.BadRequest,
                    message = "Unsupported response_format '$responseFormat'. Use one of: wav, json."
                )
                return
            }

            val voice = body.optString("voice")
                .ifBlank { body.optString("style") }
                .trim()
                .ifBlank { null }
            val language = body.optString("language")
                .ifBlank { body.optString("lang") }
                .trim()
                .ifBlank { null }
            val requestedEngine = body.optString("engine").trim().lowercase().ifBlank { null }
            val requestedModel = body.optString("model").trim().ifBlank { null }
            val supertonicModel = body.optString("supertonic_model").trim().ifBlank { null }

            val target = resolveTtsTarget(
                requestedEngine = requestedEngine,
                requestedModel = requestedModel,
                requestedSupertonicModel = supertonicModel
            )
            val result = synthesizeTts(
                TtsSynthesisRequest(
                    input = input,
                    speed = speed,
                    voice = voice,
                    language = language,
                    target = target
                )
            )
            val wavBytes = toWavBytes(result.audio, result.sampleRate)

            if (responseFormat == "json") {
                val payload = JSONObject()
                    .put("model", result.modelId)
                    .put("engine", result.engine)
                    .put("sample_rate", result.sampleRate)
                    .put("audio_base64", Base64.getEncoder().encodeToString(wavBytes))
                call.respondText(
                    text = payload.toString(),
                    contentType = ContentType.Application.Json,
                    status = HttpStatusCode.OK
                )
                return
            }

            call.respondBytes(
                bytes = wavBytes,
                contentType = ContentType.parse("audio/wav"),
                status = HttpStatusCode.OK
            )
        } catch (t: Throwable) {
            DebugLogger.logErr("ApiServer TTS failed", t)
            val status = if (t is IllegalArgumentException) {
                HttpStatusCode.BadRequest
            } else {
                HttpStatusCode.InternalServerError
            }
            respondApiError(
                call = call,
                status = status,
                message = t.message ?: "TTS generation failed"
            )
        }
    }

    private suspend fun respondApiError(
        call: io.ktor.server.application.ApplicationCall,
        status: HttpStatusCode,
        message: String
    ) {
        val error = JSONObject()
            .put(
                "error",
                JSONObject()
                    .put("message", message)
                    .put("type", "api_error")
            )

        call.respondText(
            text = error.toString(),
            contentType = ContentType.Application.Json,
            status = status
        )
    }

    private suspend fun generateFromPrompt(
        requestedModel: String?,
        prompt: String,
        image: LlmImageInput? = null,
        generationOptions: ApiGenerationOptions = ApiGenerationOptions()
    ): GenerationResult {
        if (image == null) {
            return requestLock.withLock {
                val (modelId, activeBackend) = getOrCreateBackend(requestedModel, false)
                applyGenerationOptions(activeBackend, generationOptions)
                val text = runGeneration(activeBackend) { listener ->
                    activeBackend.sendMessage(prompt, listener)
                }
                GenerationResult(modelId = modelId, text = text)
            }
        }
        return generateFromMessages(
            requestedModel = requestedModel,
            messages = listOf(LlmMessage(role = "user", content = prompt)),
            image = image,
            generationOptions = generationOptions
        )
    }

    private suspend fun generateFromMessages(
        requestedModel: String?,
        messages: List<LlmMessage>,
        image: LlmImageInput? = null,
        generationOptions: ApiGenerationOptions = ApiGenerationOptions()
    ): GenerationResult {
        if (messages.isEmpty()) {
            throw IllegalArgumentException("'messages' cannot be empty")
        }

        return requestLock.withLock {
            val audio = messages.lastOrNull { it.role == "user" }?.audios?.firstOrNull()
            val imageFromMessage = messages.lastOrNull { it.role == "user" }?.images?.firstOrNull()
            val effectiveImage = image ?: imageFromMessage
            val (modelId, activeBackend) = getOrCreateBackend(
                requestedModel,
                requireImageInput = effectiveImage != null,
                requireAudioInput = audio != null
            )
            applyGenerationOptions(activeBackend, generationOptions)
            ensureImageInputSupported(modelId, activeBackend, effectiveImage)
            ensureAudioInputSupported(modelId, activeBackend, audio)
            val text = runGeneration(activeBackend) { listener ->
                if (image != null && audio == null) {
                    activeBackend.sendMessage(messages, image, listener)
                } else {
                    activeBackend.sendMessage(messages, listener)
                }
            }
            GenerationResult(modelId = modelId, text = text)
        }
    }

    private fun applyGenerationOptions(
        activeBackend: LlmBackend,
        generationOptions: ApiGenerationOptions
    ) {
        when (activeBackend) {
            is LlamaCppBackend -> {
                val runtimeOverrides = com.mewmix.nabu.chat.LlmRuntimeOverrides(
                    maxNewTokens = generationOptions.maxTokens?.coerceIn(1, 4096)
                )
                activeBackend.updateConfig(
                    SettingsManager.getLlmRuntimeConfig(context, overrides = runtimeOverrides)
                )
            }
            is MediaPipeBackend -> {
                val baseConfig = SettingsManager.getMediaPipeRuntimeConfig(context)
                activeBackend.updateConfig(
                    baseConfig.copy(
                        maxTokens = generationOptions.maxTokens?.coerceIn(1, 16_384) ?: baseConfig.maxTokens,
                        topP = generationOptions.topP?.coerceIn(0f, 1f) ?: baseConfig.topP,
                        temperature = generationOptions.temperature?.coerceIn(0f, 2f) ?: baseConfig.temperature,
                        randomSeed = generationOptions.randomSeed?.coerceAtLeast(-1) ?: baseConfig.randomSeed
                    )
                )
            }
            is LiteRtLmBackend -> Unit
        }
    }

    private fun getOrCreateBackend(
        requestedModelId: String?,
        requireImageInput: Boolean = false,
        requireAudioInput: Boolean = false
    ): Pair<String, LlmBackend> {
        synchronized(backendLock) {
            val availableModels = listAvailableModels(ModelScope.LLM)
            if (availableModels.isEmpty()) {
                throw IllegalStateException("No downloaded chat models available")
            }

            val targetModel = if (requestedModelId.isNullOrBlank()) {
                if (requireImageInput) {
                    availableModels.firstOrNull { modelSupportsImageInput(it) }
                        ?: throw IllegalArgumentException(
                            "No downloaded image-capable model available. Download an allowlisted multimodal model and retry."
                        )
                } else if (requireAudioInput) {
                    availableModels.firstOrNull { modelSupportsAudioInput(it) }
                        ?: throw IllegalArgumentException(
                            "No downloaded audio-capable model available. Download an allowlisted audio model and retry."
                        )
                } else {
                    availableModels.first()
                }
            } else {
                val requested = availableModels.firstOrNull { it.id == requestedModelId }
                    ?: throw IllegalArgumentException("Requested model is not available: $requestedModelId")
                if (requireImageInput && !modelSupportsImageInput(requested)) {
                    throw IllegalArgumentException(
                        "Requested model '$requestedModelId' does not support image input."
                    )
                }
                if (requireAudioInput && !modelSupportsAudioInput(requested)) {
                    throw IllegalArgumentException(
                        "Requested model '$requestedModelId' does not support audio input."
                    )
                }
                requested
            }

            if (backend != null && backendModelId == targetModel.id) {
                return targetModel.id to backend!!
            }

            backend?.close()
            backend = null
            backendModelId = null

            val modelDir = File(context.filesDir, "models")
            val artifact = findDownloadedLlmArtifact(modelDir, targetModel.id, targetModel.backend)
                ?: throw IllegalStateException(
                    "Model file not found for ${targetModel.id} (.task/.litertlm/.gguf)"
                )
            val createdBackend: LlmBackend = when (artifact.backend) {
                "llama" -> LlamaCppBackend(
                    context = context,
                    modelPath = artifact.file.absolutePath,
                    initialConfig = SettingsManager.getLlmRuntimeConfig(context)
                )
                "litertlm" -> LiteRtLmBackend(
                    context = context,
                    modelId = targetModel.id,
                    modelPath = artifact.file.absolutePath
                )
                else -> MediaPipeBackend(
                    context = context,
                    modelPath = artifact.file.absolutePath,
                    initialConfig = SettingsManager.getMediaPipeRuntimeConfig(context)
                )
            }

            createdBackend.initialize()
            backend = createdBackend
            backendModelId = targetModel.id

            DebugLogger.log("ApiServer active model: ${targetModel.id} (${targetModel.backend})")
            return targetModel.id to createdBackend
        }
    }

    private fun modelSupportsImageInput(model: com.mewmix.nabu.data.Model): Boolean {
        if (!VisionModelSupport.supportsImageInput(model.id)) return false

        val artifact = findDownloadedLlmArtifact(File(context.filesDir, "models"), model.id, model.backend)
            ?: return false
        return artifact.backend == "mediapipe" || artifact.backend == "litertlm"
    }

    private fun modelSupportsAudioInput(model: com.mewmix.nabu.data.Model): Boolean {
        if (!VisionModelSupport.supportsAudioInput(model.id)) return false

        val artifact = findDownloadedLlmArtifact(File(context.filesDir, "models"), model.id, model.backend)
            ?: return false
        return artifact.backend == "litertlm"
    }

    private suspend fun runGeneration(
        activeBackend: LlmBackend,
        send: (resultListener: (partialResult: String, done: Boolean) -> Unit) -> Unit
    ): String {
        val timeoutMs = when (activeBackend) {
            is LlamaCppBackend -> SettingsManager.getLlmRuntimeConfig(context).totalTimeoutMs
            else -> DEFAULT_API_GENERATION_TIMEOUT_MS
        }

        return try {
            withTimeout(timeoutMs) {
                suspendCancellableCoroutine { continuation ->
                    val responseBuilder = StringBuilder()
                    val finished = AtomicBoolean(false)

                    continuation.invokeOnCancellation {
                        activeBackend.cancel()
                    }

                    try {
                        send { partialResult, done ->
                            if (partialResult.isNotEmpty()) {
                                responseBuilder.append(partialResult)
                            }
                            if (!done) {
                                return@send
                            }

                            if (finished.compareAndSet(false, true)) {
                                continuation.resume(responseBuilder.toString())
                            }
                        }
                    } catch (t: Throwable) {
                        if (finished.compareAndSet(false, true)) {
                            continuation.resumeWithException(t)
                        }
                    }
                }
            }
        } catch (_: TimeoutCancellationException) {
            activeBackend.cancel()
            throw IllegalStateException("Generation timed out after ${timeoutMs}ms.")
        }
    }

    private fun parseGenerationOptions(body: JSONObject): ApiGenerationOptions {
        val maxTokens = listOf("max_tokens", "max_output_tokens")
            .firstNotNullOfOrNull { key -> optInt(body, key) }
        val temperature = optFloat(body, "temperature")
        val topP = optFloat(body, "top_p")
        val randomSeed = optInt(body, "seed")
        return ApiGenerationOptions(
            maxTokens = maxTokens,
            temperature = temperature,
            topP = topP,
            randomSeed = randomSeed
        )
    }

    private fun optInt(body: JSONObject, key: String): Int? {
        if (!body.has(key)) return null
        val raw = body.opt(key) ?: return null
        return when (raw) {
            is Number -> raw.toInt()
            is String -> raw.trim().toIntOrNull()
            else -> null
        }
    }

    private fun optFloat(body: JSONObject, key: String): Float? {
        if (!body.has(key)) return null
        val raw = body.opt(key) ?: return null
        val value = when (raw) {
            is Number -> raw.toFloat()
            is String -> raw.trim().toFloatOrNull()
            else -> null
        } ?: return null
        return if (value.isFinite()) value else null
    }

    private fun parseMessages(input: JSONArray, tools: List<com.mewmix.nabu.tools.Tool> = emptyList()): ParsedMessages {
        val messages = mutableListOf<LlmMessage>()
        var image: LlmImageInput? = null
        var audio: LlmAudioInput? = null

        val systemPromptBuilder = StringBuilder()
        if (tools.isNotEmpty()) {
            val protocol = com.mewmix.nabu.tools.ToolCallProtocol
            systemPromptBuilder.append(protocol.buildSystemPrompt("", tools)).append("\n\n")
        }

        for (i in 0 until input.length()) {
            val item = input.optJSONObject(i) ?: continue
            val rawRole = item.optString("role").trim()
            if (rawRole.isEmpty()) continue
            val role = when (rawRole.lowercase()) {
                "assistant" -> "model"
                "system" -> {
                    val parsed = parseMessageContent(item, "system")
                    if (parsed.content.isNotEmpty()) {
                        systemPromptBuilder.append(parsed.content).append("\n\n")
                    }
                    continue
                }
                else -> rawRole.lowercase()
            }

            val parsed = parseMessageContent(item, role)
            if (parsed.image != null) {
                if (image != null) {
                    throw IllegalArgumentException("Only one image input is supported per request.")
                }
                image = parsed.image
            }
            if (parsed.audio != null) {
                if (audio != null) {
                    throw IllegalArgumentException("Only one audio input is supported per request.")
                }
                audio = parsed.audio
            }
            if (parsed.content.isNotEmpty() || parsed.image != null || parsed.audio != null) {
                messages.add(
                    LlmMessage(
                        role = role,
                        content = parsed.content,
                        images = listOfNotNull(parsed.image),
                        audios = listOfNotNull(parsed.audio)
                    )
                )
            }
        }

        val systemPrompt = systemPromptBuilder.toString().trim()
        if (systemPrompt.isNotEmpty()) {
            messages.add(0, LlmMessage(role = "system", content = systemPrompt))
        }

        if (messages.isEmpty()) {
            throw IllegalArgumentException("'messages' must include at least one non-empty content entry.")
        }
        return ParsedMessages(messages = messages, image = image, audio = audio, tools = tools)
    }

    private fun parseMessageContent(item: JSONObject, role: String): ParsedMessageContent {
        val contentRaw = item.opt("content")
        if (contentRaw is JSONArray) {
            val textParts = mutableListOf<String>()
            var image: LlmImageInput? = null
            var audio: LlmAudioInput? = null
            for (idx in 0 until contentRaw.length()) {
                val part = contentRaw.optJSONObject(idx) ?: continue
                when (part.optString("type").trim().lowercase()) {
                    "text" -> {
                        val text = part.optString("text").trim()
                        if (text.isNotEmpty()) {
                            textParts.add(text)
                        }
                    }
                    "image_url" -> {
                        if (role != "user") {
                            throw IllegalArgumentException("Image input is only supported for user messages.")
                        }
                        if (image != null) {
                            throw IllegalArgumentException("Only one image input is supported per request.")
                        }
                        val imageUrl = part.optJSONObject("image_url")
                            ?.optString("url")
                            ?.trim()
                            ?.ifBlank { null }
                            ?: part.optString("image_url").trim().ifBlank { null }
                            ?: throw IllegalArgumentException("'image_url' entry is missing a usable 'url' value.")
                        image = decodeImageInput(imageUrl)
                    }
                    "input_audio" -> {
                        if (role != "user") {
                            throw IllegalArgumentException("Audio input is only supported for user messages.")
                        }
                        if (audio != null) {
                            throw IllegalArgumentException("Only one audio input is supported per request.")
                        }
                        val inputAudio = part.optJSONObject("input_audio")
                            ?: throw IllegalArgumentException("'input_audio' entry is missing an object payload.")
                        val data = inputAudio.optString("data").trim().ifBlank { null }
                            ?: throw IllegalArgumentException("'input_audio' entry is missing base64 'data'.")
                        val format = inputAudio.optString("format").trim().ifBlank { "wav" }
                        audio = decodeAudioInput(data, format)
                    }
                    else -> Unit
                }
            }
            var content = textParts.joinToString("\n").trim()
            if (image != null && content.isEmpty()) {
                content = DEFAULT_IMAGE_PROMPT
            }
            return ParsedMessageContent(content = content, image = image, audio = audio)
        }

        return ParsedMessageContent(
            content = item.optString("content").trim(),
            image = null,
            audio = null
        )
    }

    private fun decodeAudioInput(raw: String, format: String): LlmAudioInput {
        if (!format.equals("wav", ignoreCase = true)) {
            throw IllegalArgumentException("Only WAV input_audio is supported for LiteRT-LM audio input.")
        }

        val base64Payload = raw.trim().let { value ->
            if (value.startsWith("data:", ignoreCase = true)) {
                val commaIndex = value.indexOf(',')
                if (commaIndex <= 0 || commaIndex >= value.length - 1) {
                    throw IllegalArgumentException("Invalid data URL audio payload.")
                }
                val meta = value.substring(0, commaIndex)
                if (!meta.contains(";base64", ignoreCase = true)) {
                    throw IllegalArgumentException("Only base64 data URL audio payloads are supported.")
                }
                value.substring(commaIndex + 1)
            } else {
                value
            }
        }

        val audioBytes = try {
            Base64.getDecoder().decode(base64Payload)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Audio payload is not valid base64.")
        }
        if (!audioBytes.hasWavHeader()) {
            throw IllegalArgumentException("Audio payload must be a WAV file.")
        }
        return LlmAudioInput(
            bytes = audioBytes,
            displayName = "input_audio.wav"
        )
    }

    private fun parseTopLevelImageInput(body: JSONObject): LlmImageInput? {
        val imageBase64 = body.optString("image_base64").trim().ifBlank { null }
        val imageUrl = when (val raw = body.opt("image_url")) {
            is JSONObject -> raw.optString("url").trim().ifBlank { null }
            is String -> raw.trim().ifBlank { null }
            else -> null
        }
        val imageData = body.optString("image").trim().ifBlank { null }

        val provided = listOfNotNull(imageBase64, imageUrl, imageData)
        if (provided.isEmpty()) return null
        if (provided.size > 1) {
            throw IllegalArgumentException("Provide only one of: image_base64, image_url, image.")
        }
        return decodeImageInput(provided.first())
    }

    private fun decodeImageInput(raw: String): LlmImageInput {
        val value = raw.trim()
        if (value.isEmpty()) {
            throw IllegalArgumentException("Image payload is empty.")
        }
        if (value.startsWith("http://", ignoreCase = true) || value.startsWith("https://", ignoreCase = true)) {
            throw IllegalArgumentException("Remote image URLs are not supported. Provide a data URL or base64 image payload.")
        }

        val base64Payload = if (value.startsWith("data:", ignoreCase = true)) {
            val commaIndex = value.indexOf(',')
            if (commaIndex <= 0 || commaIndex >= value.length - 1) {
                throw IllegalArgumentException("Invalid data URL image payload.")
            }
            val meta = value.substring(0, commaIndex)
            if (!meta.contains(";base64", ignoreCase = true)) {
                throw IllegalArgumentException("Only base64 data URL images are supported.")
            }
            value.substring(commaIndex + 1)
        } else {
            value
        }

        val imageBytes = try {
            Base64.getDecoder().decode(base64Payload)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Image payload is not valid base64.")
        }

        val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            ?: throw IllegalArgumentException("Unable to decode image payload.")
        return LlmImageInput(bitmap = bitmap)
    }

    private fun ensureImageInputSupported(modelId: String, backend: LlmBackend, image: LlmImageInput?) {
        if (image == null) return
        if (!VisionModelSupport.supportsImageInput(modelId)) {
            throw IllegalArgumentException(
                "Model '$modelId' is not allowlisted for image input."
            )
        }
        if (!backend.supportsImageInput()) {
            throw IllegalArgumentException(
                "Backend for model '$modelId' does not support image input."
            )
        }
    }

    private fun ensureAudioInputSupported(modelId: String, backend: LlmBackend, audio: LlmAudioInput?) {
        if (audio == null) return
        if (!VisionModelSupport.supportsAudioInput(modelId)) {
            throw IllegalArgumentException(
                "Model '$modelId' is not allowlisted for audio input."
            )
        }
        if (!backend.supportsAudioInput()) {
            throw IllegalArgumentException(
                "Backend for model '$modelId' does not support audio input."
            )
        }
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

    private suspend fun Writer.writeSseData(payload: String) {
        write("data: ")
        write(payload)
        write("\n\n")
        flush()
    }

    private suspend fun Writer.writeSseDone() {
        write("data: [DONE]\n\n")
        flush()
    }

    private suspend fun synthesizeTts(request: TtsSynthesisRequest): TtsSynthesisResult {
        return requestLock.withLock {
            val modelManager = ModelManager(context)
            val previousEngine = SettingsManager.getTtsEngine(context)
            val previousSupertonicModel = SettingsManager.getSupertonicModelId(context)
            var settingsMutated = false

            try {
                if (request.target.engine != previousEngine) {
                    SettingsManager.setTtsEngine(context, request.target.engine)
                    settingsMutated = true
                }

                if (request.target.engine == "supertonic" &&
                    request.target.supertonicModelId != null &&
                    request.target.supertonicModelId != previousSupertonicModel
                ) {
                    SettingsManager.setSupertonicModelId(context, request.target.supertonicModelId)
                    settingsMutated = true
                }

                val selectedEngine = TTSManager.getEngine(context, modelManager)
                    ?: throw IllegalStateException("No TTS engine available for '${request.target.engine}'")
                val rawEngine = unwrapTtsEngine(selectedEngine)

                val synthesis = when (rawEngine) {
                    is KokoroEngine -> {
                        val selectedVoice = request.voice ?: SettingsManager.getStyle(context, DEFAULT_TTS_VOICE)
                        val phonemes = phonemeConverter.phonemize(request.input)
                        val (audio, sampleRate) = createAudio(
                            phonemes = phonemes,
                            voice = selectedVoice,
                            speed = request.speed,
                            engine = rawEngine,
                            styleLoader = styleLoader
                        )
                        TtsSynthesisResult(
                            audio = audio,
                            sampleRate = sampleRate,
                            modelId = "kokoro",
                            engine = "kokoro"
                        )
                    }
                    is DebugSupertonicEngine -> {
                        request.voice?.let { rawEngine.setStyle(it) }
                        val language = normalizeSupertonicLanguage(request.language ?: SettingsManager.getSupertonicLanguage(context))
                        val result = rawEngine.synthesize(request.input, request.speed, language)
                        val modelId = request.target.supertonicModelId
                            ?: SettingsManager.getSupertonicModelId(context)
                            ?: "supertonic"
                        TtsSynthesisResult(
                            audio = result.wav,
                            sampleRate = result.sampleRate,
                            modelId = modelId,
                            engine = "supertonic"
                        )
                    }
                    is SopranoEngine -> {
                        val result = rawEngine.synthesize(request.input, request.speed)
                        TtsSynthesisResult(
                            audio = result.wav,
                            sampleRate = result.sampleRate,
                            modelId = "soprano-80m-onnx",
                            engine = "soprano"
                        )
                    }
                    else -> {
                        val result = rawEngine.synthesize(request.input, request.speed)
                        TtsSynthesisResult(
                            audio = result.wav,
                            sampleRate = result.sampleRate,
                            modelId = rawEngine.name.lowercase(),
                            engine = rawEngine.name.lowercase()
                        )
                    }
                }

                DebugLogger.log(
                    "ApiServer TTS synthesized engine=${synthesis.engine} model=${synthesis.modelId} samples=${synthesis.audio.size} sr=${synthesis.sampleRate}"
                )
                synthesis
            } finally {
                if (settingsMutated) {
                    SettingsManager.setTtsEngine(context, previousEngine)
                    SettingsManager.setSupertonicModelId(context, previousSupertonicModel)
                }
            }
        }
    }

    private fun parseReplyTtsRequest(body: JSONObject): ReplyTtsRequest? {
        if (!body.has("tts")) return null
        val raw = body.opt("tts")
        if (raw == null || raw == JSONObject.NULL) return null

        if (raw is Boolean) {
            if (!raw) return null
            return ReplyTtsRequest(
                speed = 1.0f,
                voice = null,
                language = null,
                target = resolveTtsTarget(
                    requestedEngine = null,
                    requestedModel = null,
                    requestedSupertonicModel = null
                ),
                responseFormat = "json"
            )
        }

        val ttsBody = raw as? JSONObject
            ?: throw IllegalArgumentException("'tts' must be a boolean or an object.")
        val enabled = if (ttsBody.has("enabled")) ttsBody.optBoolean("enabled", true) else true
        if (!enabled) return null

        val speed = ttsBody.optDouble("speed", 1.0).toFloat()
        if (!speed.isFinite() || speed <= 0f) {
            throw IllegalArgumentException("'tts.speed' must be a positive number.")
        }

        val responseFormat = ttsBody.optString("response_format")
            .trim()
            .lowercase()
            .ifBlank { "json" }
        if (responseFormat !in SUPPORTED_LLM_REPLY_TTS_FORMATS) {
            throw IllegalArgumentException(
                "Unsupported 'tts.response_format' '$responseFormat'. Use one of: ${SUPPORTED_LLM_REPLY_TTS_FORMATS.joinToString()}."
            )
        }

        val voice = ttsBody.optString("voice")
            .ifBlank { ttsBody.optString("style") }
            .trim()
            .ifBlank { null }
        val language = ttsBody.optString("language")
            .ifBlank { ttsBody.optString("lang") }
            .trim()
            .ifBlank { null }

        val requestedEngine = ttsBody.optString("engine").trim().lowercase().ifBlank { null }
        val requestedModel = ttsBody.optString("model").trim().ifBlank { null }
        val requestedSupertonicModel = ttsBody.optString("supertonic_model").trim().ifBlank { null }
        val target = resolveTtsTarget(
            requestedEngine = requestedEngine,
            requestedModel = requestedModel,
            requestedSupertonicModel = requestedSupertonicModel
        )

        return ReplyTtsRequest(
            speed = speed,
            voice = voice,
            language = language,
            target = target,
            responseFormat = responseFormat
        )
    }

    private suspend fun maybeSynthesizeReplyTts(request: ReplyTtsRequest?, text: String): TtsSynthesisResult? {
        if (request == null) return null
        if (request.responseFormat != "json") {
            throw IllegalArgumentException("Only 'json' TTS response format is supported for LLM replies.")
        }
        val normalizedText = text.trim()
        if (normalizedText.isEmpty()) return null

        return synthesizeTts(
            TtsSynthesisRequest(
                input = normalizedText,
                speed = request.speed,
                voice = request.voice,
                language = request.language,
                target = request.target
            )
        )
    }

    private fun unwrapTtsEngine(engine: TTSEngine): TTSEngine =
        if (engine is BenchmarkingTTSEngine) engine.delegate else engine

    private fun resolveTtsTarget(
        requestedEngine: String?,
        requestedModel: String?,
        requestedSupertonicModel: String?
    ): TtsRequestTarget {
        val normalizedEngine = requestedEngine?.trim()?.lowercase()?.ifBlank { null }
        if (normalizedEngine != null && normalizedEngine !in SUPPORTED_TTS_ENGINES) {
            throw IllegalArgumentException(
                "Unsupported TTS engine '$normalizedEngine'. Use one of: ${SUPPORTED_TTS_ENGINES.joinToString()}."
            )
        }

        val downloadedTts = listAvailableModels(ModelScope.TTS).map { it.id }.toSet()
        var modelInferredEngine: String? = null
        var modelInferredSupertonicId: String? = null

        when (requestedModel?.trim()) {
            null, "" -> Unit
            "kokoro", "kokoro-82m", "kokoro-82m-v1.0", "kokoro_fp16", "kokoro_int8" -> {
                modelInferredEngine = "kokoro"
            }
            "soprano-80m-onnx" -> {
                if ("soprano-80m-onnx" !in downloadedTts) {
                    throw IllegalArgumentException("Requested TTS model is not downloaded: soprano-80m-onnx")
                }
                modelInferredEngine = "soprano"
            }
            "supertonic-2-onnx", "supertonic-3-onnx" -> {
                if (requestedModel !in downloadedTts) {
                    throw IllegalArgumentException("Requested TTS model is not downloaded: $requestedModel")
                }
                modelInferredEngine = "supertonic"
                modelInferredSupertonicId = requestedModel
            }
            else -> {
                throw IllegalArgumentException("Requested TTS model is not supported: $requestedModel")
            }
        }

        if (normalizedEngine != null && modelInferredEngine != null && normalizedEngine != modelInferredEngine) {
            throw IllegalArgumentException(
                "Requested engine '$normalizedEngine' does not match model '$requestedModel'."
            )
        }

        val engine = normalizedEngine ?: modelInferredEngine ?: SettingsManager.getTtsEngine(context)
        if (engine !in SUPPORTED_TTS_ENGINES) {
            throw IllegalArgumentException(
                "No supported TTS engine selected. Set one of: ${SUPPORTED_TTS_ENGINES.joinToString()}."
            )
        }

        val explicitSupertonicId = requestedSupertonicModel?.trim()?.ifBlank { null }
        if (engine != "supertonic" && explicitSupertonicId != null) {
            throw IllegalArgumentException("'supertonic_model' requires engine='supertonic'.")
        }

        if (engine == "supertonic") {
            val supertonicModelId = explicitSupertonicId ?: modelInferredSupertonicId
            if (supertonicModelId != null && supertonicModelId !in downloadedTts) {
                throw IllegalArgumentException("Requested supertonic model is not downloaded: $supertonicModelId")
            }
            return TtsRequestTarget(engine = engine, supertonicModelId = supertonicModelId)
        }

        return TtsRequestTarget(engine = engine, supertonicModelId = null)
    }

    private fun toWavBytes(audio: FloatArray, sampleRate: Int): ByteArray {
        require(sampleRate > 0) { "Invalid sample rate: $sampleRate" }
        val pcmBuffer = ByteBuffer.allocate(audio.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in audio) {
            val safeSample = if (sample.isFinite()) sample.coerceIn(-1f, 1f) else 0f
            val pcmValue = (safeSample * Short.MAX_VALUE).toInt().toShort()
            pcmBuffer.putShort(pcmValue)
        }

        val header = createWavHeader(sampleCount = audio.size, sampleRate = sampleRate)
        return ByteArray(header.size + pcmBuffer.array().size).apply {
            System.arraycopy(header, 0, this, 0, header.size)
            System.arraycopy(pcmBuffer.array(), 0, this, header.size, pcmBuffer.array().size)
        }
    }

    private fun createWavHeader(sampleCount: Int, sampleRate: Int): ByteArray {
        val header = ByteArray(44)
        val dataSize = sampleCount * 2
        val totalDataSize = dataSize + 36
        val byteRate = sampleRate * 2

        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()

        header[4] = (totalDataSize and 0xff).toByte()
        header[5] = ((totalDataSize shr 8) and 0xff).toByte()
        header[6] = ((totalDataSize shr 16) and 0xff).toByte()
        header[7] = ((totalDataSize shr 24) and 0xff).toByte()

        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()

        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16
        header[20] = 1
        header[22] = 1

        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()

        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()

        header[32] = 2
        header[34] = 16

        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()

        header[40] = (dataSize and 0xff).toByte()
        header[41] = ((dataSize shr 8) and 0xff).toByte()
        header[42] = ((dataSize shr 16) and 0xff).toByte()
        header[43] = ((dataSize shr 24) and 0xff).toByte()
        return header
    }

    private fun listAvailableModels(scope: ModelScope): List<com.mewmix.nabu.data.Model> {
        val downloaded = ModelManager(context).models.filter { it.isDownloaded }
        return when (scope) {
            ModelScope.LLM -> downloaded.filter { it.type == ModelType.LLM }
            ModelScope.TTS -> downloaded.filter { it.type == ModelType.TTS }
            ModelScope.ALL -> downloaded
        }
    }

    private fun modelBackend(model: com.mewmix.nabu.data.Model): String {
        return if (model.type == ModelType.TTS) {
            "onnx"
        } else {
            model.backend
        }
    }

    private fun modelCapabilities(model: com.mewmix.nabu.data.Model): Set<String> {
        return if (model.type == ModelType.TTS) {
            setOf("tts")
        } else {
            VisionModelSupport.capabilitiesFor(model.id)
        }
    }

    private suspend fun parseModelScope(
        call: io.ktor.server.application.ApplicationCall,
        default: ModelScope
    ): ModelScope? {
        val rawType = call.request.queryParameters["type"]?.trim()?.lowercase()
        return parseModelScopeValue(call, rawType, default)
    }

    private suspend fun parseModelScopeFromBody(
        call: io.ktor.server.application.ApplicationCall,
        default: ModelScope
    ): ModelScope? {
        val body = call.receiveText().trim()
        if (body.isEmpty()) return default

        val rawType = try {
            JSONObject(body).optString("type").trim().lowercase().ifEmpty { null }
        } catch (_: Throwable) {
            respondApiError(
                call = call,
                status = HttpStatusCode.BadRequest,
                message = "Invalid JSON body. Expected object with optional 'type': llm|tts|all."
            )
            return null
        }

        return parseModelScopeValue(call, rawType, default)
    }

    private suspend fun parseModelScopeValue(
        call: io.ktor.server.application.ApplicationCall,
        rawType: String?,
        default: ModelScope
    ): ModelScope? {
        return when (rawType) {
            null, "" -> default
            "llm" -> ModelScope.LLM
            "tts" -> ModelScope.TTS
            "all" -> ModelScope.ALL
            else -> {
                respondApiError(
                    call = call,
                    status = HttpStatusCode.BadRequest,
                    message = "Invalid model type '$rawType'. Use one of: llm, tts, all."
                )
                null
            }
        }
    }

    private enum class ModelScope {
        LLM,
        TTS,
        ALL
    }

    private data class GenerationResult(
        val modelId: String,
        val text: String
    )

    private data class ParsedMessages(
        val messages: List<LlmMessage>,
        val image: LlmImageInput?,
        val audio: LlmAudioInput?,
        val tools: List<com.mewmix.nabu.tools.Tool> = emptyList()
    )

    private data class ParsedMessageContent(
        val content: String,
        val image: LlmImageInput?,
        val audio: LlmAudioInput?
    )

    private fun parseTools(body: JSONObject): List<com.mewmix.nabu.tools.Tool> {
        val toolsArray = body.optJSONArray("tools") ?: return emptyList()
        val parsedTools = mutableListOf<com.mewmix.nabu.tools.Tool>()

        for (i in 0 until toolsArray.length()) {
            val toolItem = toolsArray.optJSONObject(i) ?: continue
            if (toolItem.optString("type") != "function") continue
            val functionObj = toolItem.optJSONObject("function") ?: continue

            val name = functionObj.optString("name")
            val description = functionObj.optString("description", "")
            if (name.isBlank()) continue

            val parameters = mutableMapOf<String, String>()
            val paramsObj = functionObj.optJSONObject("parameters")
            if (paramsObj != null) {
                val propertiesObj = paramsObj.optJSONObject("properties")
                if (propertiesObj != null) {
                    val keys = propertiesObj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val prop = propertiesObj.optJSONObject(key)
                        if (prop != null) {
                            val type = prop.optString("type", "string")
                            val desc = prop.optString("description", "")
                            parameters[key] = "($type) $desc".trim()
                        }
                    }
                }
            }

            parsedTools.add(
                com.mewmix.nabu.tools.Tool(
                    name = name,
                    description = description,
                    parameters = parameters,
                    isAvailable = true
                )
            )
        }
        return parsedTools
    }

    private fun shouldSuppressToken(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false

        // 1. Tagged tool call: <tool_call>...
        if (trimmed.startsWith("<tool_call")) return true
        if (text.contains("<tool_call>") && !text.contains("</tool_call>")) return true

        // 2. Fenced JSON: ```json ... ```
        if (trimmed.startsWith("```")) return true
        if (text.contains("```") && text.indexOf("```") == text.lastIndexOf("```")) return true

        // 3. Raw JSON: { "name": ... }
        // We suppress if it looks like it's starting a JSON object but hasn't closed it yet,
        // unless it's clearly just conversational text that happens to have a brace.
        if (trimmed.startsWith("{") && !trimmed.contains("\n\n") && !trimmed.contains("}")) return true

        return false
    }

    private data class TtsRequestTarget(
        val engine: String,
        val supertonicModelId: String?
    )

    private data class TtsSynthesisRequest(
        val input: String,
        val speed: Float,
        val voice: String?,
        val language: String?,
        val target: TtsRequestTarget
    )

    private data class TtsSynthesisResult(
        val audio: FloatArray,
        val sampleRate: Int,
        val modelId: String,
        val engine: String
    )

    private data class ReplyTtsRequest(
        val speed: Float,
        val voice: String?,
        val language: String?,
        val target: TtsRequestTarget,
        val responseFormat: String
    )

    private sealed interface StreamEvent {
        data class Token(val chunk: String) : StreamEvent
        data object Done : StreamEvent
    }
}

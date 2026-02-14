package com.mewmix.nabu.api

import android.content.Context
import com.mewmix.nabu.chat.LlamaCppBackend
import com.mewmix.nabu.chat.LlmBackend
import com.mewmix.nabu.chat.LlmMessage
import com.mewmix.nabu.chat.MediaPipeBackend
import com.mewmix.nabu.data.ModelManager
import com.mewmix.nabu.data.ModelType
import com.mewmix.nabu.utils.DebugLogger
import com.mewmix.nabu.utils.SettingsManager
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.cio.CIO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

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
                        handleModels(call)
                    }

                    get("/v1/models") {
                        handleOpenAiModels(call)
                    }

                    post("/generate") {
                        handleGenerate(call)
                    }

                    post("/v1/chat/completions") {
                        handleChatCompletions(call)
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
            val prompt = body.optString("prompt").trim()
            val messageArray = body.optJSONArray("messages")

            if (prompt.isEmpty() && messageArray == null) {
                respondApiError(
                    call = call,
                    status = HttpStatusCode.BadRequest,
                    message = "Provide either 'prompt' or 'messages'."
                )
                return
            }

            val generation = if (messageArray != null) {
                generateFromMessages(requestedModel, parseMessages(messageArray))
            } else {
                generateFromPrompt(requestedModel, prompt)
            }

            val response = JSONObject()
                .put("model", generation.modelId)
                .put("text", generation.text)

            call.respondText(
                text = response.toString(),
                contentType = ContentType.Application.Json,
                status = HttpStatusCode.OK
            )
        } catch (t: Throwable) {
            DebugLogger.logErr("ApiServer /generate failed", t)
            respondApiError(
                call = call,
                status = HttpStatusCode.InternalServerError,
                message = t.message ?: "Generation failed"
            )
        }
    }

    private suspend fun handleModels(call: io.ktor.server.application.ApplicationCall) {
        try {
            val models = listAvailableModels()
            val jsonModels = JSONArray().apply {
                models.forEach { model ->
                    put(
                        JSONObject()
                            .put("id", model.id)
                            .put("name", model.name)
                            .put("backend", model.backend)
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

    private suspend fun handleOpenAiModels(call: io.ktor.server.application.ApplicationCall) {
        try {
            val models = listAvailableModels()
            val nowSeconds = System.currentTimeMillis() / 1000L
            val jsonModels = JSONArray().apply {
                models.forEach { model ->
                    put(
                        JSONObject()
                            .put("id", model.id)
                            .put("object", "model")
                            .put("created", nowSeconds)
                            .put("owned_by", "local")
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
            if (body.optBoolean("stream", false)) {
                respondApiError(
                    call = call,
                    status = HttpStatusCode.BadRequest,
                    message = "Streaming is not supported."
                )
                return
            }

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

            val generation = generateFromMessages(requestedModel, parseMessages(messageArray))
            val nowSeconds = System.currentTimeMillis() / 1000L

            val choice = JSONObject()
                .put("index", 0)
                .put(
                    "message",
                    JSONObject()
                        .put("role", "assistant")
                        .put("content", generation.text)
                )
                .put("finish_reason", "stop")

            val response = JSONObject()
                .put("id", "chatcmpl-${System.currentTimeMillis()}")
                .put("object", "chat.completion")
                .put("created", nowSeconds)
                .put("model", generation.modelId)
                .put("choices", JSONArray().put(choice))
                .put(
                    "usage",
                    JSONObject()
                        .put("prompt_tokens", 0)
                        .put("completion_tokens", 0)
                        .put("total_tokens", 0)
                )

            call.respondText(
                text = response.toString(),
                contentType = ContentType.Application.Json,
                status = HttpStatusCode.OK
            )
        } catch (t: Throwable) {
            DebugLogger.logErr("ApiServer /v1/chat/completions failed", t)
            respondApiError(
                call = call,
                status = HttpStatusCode.InternalServerError,
                message = t.message ?: "Generation failed"
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

    private suspend fun generateFromPrompt(requestedModel: String?, prompt: String): GenerationResult {
        return requestLock.withLock {
            val (modelId, activeBackend) = getOrCreateBackend(requestedModel)
            if (activeBackend is LlamaCppBackend) {
                activeBackend.updateConfig(SettingsManager.getLlmRuntimeConfig(context))
            }
            val text = runGeneration { listener ->
                activeBackend.sendMessage(prompt, listener)
            }
            GenerationResult(modelId = modelId, text = text)
        }
    }

    private suspend fun generateFromMessages(requestedModel: String?, messages: List<LlmMessage>): GenerationResult {
        if (messages.isEmpty()) {
            throw IllegalArgumentException("'messages' cannot be empty")
        }

        return requestLock.withLock {
            val (modelId, activeBackend) = getOrCreateBackend(requestedModel)
            if (activeBackend is LlamaCppBackend) {
                activeBackend.updateConfig(SettingsManager.getLlmRuntimeConfig(context))
            }
            val text = runGeneration { listener ->
                activeBackend.sendMessage(messages, listener)
            }
            GenerationResult(modelId = modelId, text = text)
        }
    }

    private fun getOrCreateBackend(requestedModelId: String?): Pair<String, LlmBackend> {
        synchronized(backendLock) {
            val availableModels = listAvailableModels()
            if (availableModels.isEmpty()) {
                throw IllegalStateException("No downloaded chat models available")
            }

            val targetModel = if (requestedModelId.isNullOrBlank()) {
                availableModels.first()
            } else {
                availableModels.firstOrNull { it.id == requestedModelId }
                    ?: throw IllegalArgumentException("Requested model is not available: $requestedModelId")
            }

            if (backend != null && backendModelId == targetModel.id) {
                return targetModel.id to backend!!
            }

            backend?.close()
            backend = null
            backendModelId = null

            val taskFile = File(context.filesDir, "models/${targetModel.id}.task")
            val ggufFile = File(context.filesDir, "models/${targetModel.id}.gguf")
            val isLlama = targetModel.backend == "llama" || (ggufFile.exists() && !taskFile.exists())

            val createdBackend: LlmBackend = if (isLlama) {
                if (!ggufFile.exists()) {
                    throw IllegalStateException("Model file not found: ${ggufFile.absolutePath}")
                }
                LlamaCppBackend(
                    context = context,
                    modelPath = ggufFile.absolutePath,
                    initialConfig = SettingsManager.getLlmRuntimeConfig(context)
                )
            } else {
                if (!taskFile.exists()) {
                    throw IllegalStateException("Model file not found: ${taskFile.absolutePath}")
                }
                MediaPipeBackend(
                    context = context,
                    modelPath = taskFile.absolutePath
                )
            }

            createdBackend.initialize()
            backend = createdBackend
            backendModelId = targetModel.id

            DebugLogger.log("ApiServer active model: ${targetModel.id} (${targetModel.backend})")
            return targetModel.id to createdBackend
        }
    }

    private suspend fun runGeneration(
        send: (resultListener: (partialResult: String, done: Boolean) -> Unit) -> Unit
    ): String {
        return suspendCoroutine { continuation ->
            val responseBuilder = StringBuilder()
            val finished = AtomicBoolean(false)

            try {
                send { partialResult, done ->
                    if (!done) {
                        responseBuilder.append(partialResult)
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

    private fun parseMessages(input: JSONArray): List<LlmMessage> {
        val messages = mutableListOf<LlmMessage>()
        for (i in 0 until input.length()) {
            val item = input.optJSONObject(i) ?: continue
            val rawRole = item.optString("role").trim()
            val content = item.optString("content").trim()
            if (rawRole.isEmpty() || content.isEmpty()) continue

            val role = when (rawRole.lowercase()) {
                "assistant" -> "model"
                else -> rawRole.lowercase()
            }
            messages.add(LlmMessage(role = role, content = content))
        }
        return messages
    }

    private fun listAvailableModels() =
        ModelManager(context).models.filter { it.type == ModelType.LLM && it.isDownloaded }

    private data class GenerationResult(
        val modelId: String,
        val text: String
    )
}

package com.mewmix.nabu.api

import android.content.Context
import com.mewmix.nabu.chat.LlamaCppBackend
import com.mewmix.nabu.chat.LlmBackend
import com.mewmix.nabu.chat.LlmMessage
import com.mewmix.nabu.chat.MediaPipeBackend
import com.mewmix.nabu.data.ModelManager
import com.mewmix.nabu.data.ModelType
import com.mewmix.nabu.kokoro.KokoroEngine
import com.mewmix.nabu.soprano.SopranoEngine
import com.mewmix.nabu.supertonic.DebugSupertonicEngine
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
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.cio.CIO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
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
    private val phonemeConverter by lazy { PhonemeConverter(context) }
    private val styleLoader by lazy { StyleLoader(context) }

    companion object {
        private const val DEFAULT_TTS_VOICE = "af_sky"
        private val SUPPORTED_TTS_ENGINES = setOf("kokoro", "supertonic", "soprano")
        private val SUPPORTED_TTS_FORMATS = setOf("wav", "json")
    }

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
                                .put("backend", modelBackend(model)))
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

    private suspend fun generateFromPrompt(requestedModel: String?, prompt: String): GenerationResult {
        return requestLock.withLock {
            val (modelId, activeBackend) = getOrCreateBackend(requestedModel)
            if (activeBackend is LlamaCppBackend) {
                activeBackend.updateConfig(SettingsManager.getLlmRuntimeConfig(context))
            } else if (activeBackend is MediaPipeBackend) {
                activeBackend.updateConfig(SettingsManager.getMediaPipeRuntimeConfig(context))
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
            } else if (activeBackend is MediaPipeBackend) {
                activeBackend.updateConfig(SettingsManager.getMediaPipeRuntimeConfig(context))
            }
            val text = runGeneration { listener ->
                activeBackend.sendMessage(messages, listener)
            }
            GenerationResult(modelId = modelId, text = text)
        }
    }

    private fun getOrCreateBackend(requestedModelId: String?): Pair<String, LlmBackend> {
        synchronized(backendLock) {
            val availableModels = listAvailableModels(ModelScope.LLM)
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
                    modelPath = taskFile.absolutePath,
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
                        val result = rawEngine.synthesize(request.input, request.speed)
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
            "supertonic-onnx", "supertonic-2-onnx" -> {
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

    private data class TtsRequestTarget(
        val engine: String,
        val supertonicModelId: String?
    )

    private data class TtsSynthesisRequest(
        val input: String,
        val speed: Float,
        val voice: String?,
        val target: TtsRequestTarget
    )

    private data class TtsSynthesisResult(
        val audio: FloatArray,
        val sampleRate: Int,
        val modelId: String,
        val engine: String
    )
}

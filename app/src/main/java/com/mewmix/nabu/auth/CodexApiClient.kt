package com.mewmix.nabu.auth

import android.content.Context
import com.mewmix.nabu.BuildConfig
import com.mewmix.nabu.chat.LlmMessage
import com.mewmix.nabu.utils.DebugLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

class CodexApiClient(
    private val authenticator: CodexAuthenticator = CodexAuthenticator()
) {
    companion object {
        private const val ORIGINATOR = "codex_cli_rs"
        private const val SESSION_PREFS = "codex_oauth"
        private const val SESSION_ID_KEY = "codex_session_id"
        private const val SSE_OPENAI_BETA = "responses=experimental"
        private const val WS_OPENAI_BETA = "responses_websockets=2026-02-06"
        private const val WS_TIMEOUT_MS = 60_000L
        private const val CODEX_RESPONSES_URL = "https://chatgpt.com/backend-api/codex/responses"
    }
    private val websocketClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private data class InputBuildResult(
        val input: JSONArray,
        val userCount: Int,
        val assistantCount: Int
    )

    suspend fun sendPrompt(
        context: Context,
        prompt: String,
        model: String = "gpt-5.3-codex"
    ): Result<String> {
        return sendConversation(
            context = context,
            conversation = listOf(LlmMessage(role = "user", content = prompt)),
            model = model
        )
    }

    suspend fun sendConversation(
        context: Context,
        conversation: List<LlmMessage>,
        model: String = "gpt-5.3-codex"
    ): Result<String> {
        val accessToken = authenticator.getValidAccessToken(context)
            ?: return Result.failure(IllegalStateException("Codex account is not authenticated."))
        val accountId = authenticator.getAccountId(context)
            ?: JwtUtils.codexAccountId(accessToken, null)
            ?: return Result.failure(
                IllegalStateException("Codex account ID is missing from OAuth token.")
            )

        val sessionId = resolveSessionId(context.applicationContext)
            val toolsArray = JSONArray()
            com.mewmix.nabu.tools.ToolRegistry.tools.value
                .filter { it.isAvailable }
                .forEach { tool ->
                    val props = JSONObject()
                    tool.parameters.forEach { (k, v) ->
                        props.put(k, JSONObject().put("type", "string").put("description", v))
                    }
                    toolsArray.put(
                        JSONObject()
                            .put("type", "function")
                            .put(
                                "function",
                                JSONObject()
                                    .put("name", tool.name)
                                    .put("description", tool.description)
                                    .put("strict", false)
                                    .put(
                                        "parameters",
                                        JSONObject()
                                            .put("type", "object")
                                            .put("properties", props)
                                            .put("required", JSONArray(tool.parameters.keys))
                                            .put("additionalProperties", false)
                                    )
                            )
                    )
                }

        val payload = JSONObject()
            .put("model", model)
            .put("store", false)
            .put("stream", true)
            .put("text", JSONObject().put("verbosity", "medium"))
            .put("include", JSONArray().put("reasoning.encrypted_content"))
            .put("tools", toolsArray)
            .put("tool_choice", "auto")
            .put("parallel_tool_calls", true)
            .put("prompt_cache_key", sessionId)
        val instructions = conversation
            .filter { it.role.equals("system", ignoreCase = true) && it.content.isNotBlank() }
            .joinToString("\n\n") { it.content.trim() }
        payload.put("instructions", instructions)
        val fullInput = buildInput(conversation, includeAssistantHistory = true)
        payload.put("input", fullInput.input)
        DebugLogger.log(
            "CodexApiClient: request model=$model input=${fullInput.input.length()} " +
                "user=${fullInput.userCount} assistant=${fullInput.assistantCount} " +
                "instructions=${instructions.isNotBlank()} session_id=$sessionId"
        )
        DebugLogger.log(
            "CodexApiClient: app_version=${BuildConfig.VERSION_NAME} commit=${BuildConfig.GIT_COMMIT_HASH.take(7)} " +
                "endpoint_sse=$CODEX_RESPONSES_URL endpoint_ws=wss://chatgpt.com/backend-api/codex/responses"
        )
        DebugLogger.log("CodexApiClient: payload_shape=${summarizePayload(payload)}")

        val headers = mutableMapOf(
            "Authorization" to "Bearer $accessToken",
            "User-Agent" to "$ORIGINATOR/${BuildConfig.VERSION_NAME} (Android)",
            "originator" to ORIGINATOR,
            "Accept" to "text/event-stream",
            "version" to BuildConfig.VERSION_NAME
        )
        headers["chatgpt-account-id"] = accountId
        headers["session_id"] = sessionId
        DebugLogger.log("CodexApiClient: request_headers=${summarizeHeaders(headers)}")

        val primary = executeRequest(payload, headers)
        if (primary.isSuccess) {
            return primary
        }
        val primaryMessage = primary.exceptionOrNull()?.message.orEmpty()
        if (!primaryMessage.contains("Unsupported content type", ignoreCase = true)) {
            return primary
        }
        if (fullInput.assistantCount <= 0) {
            return primary
        }

        val userOnlyInput = buildInput(conversation, includeAssistantHistory = false)
        val retryPayload = JSONObject(payload.toString()).put("input", userOnlyInput.input)
        DebugLogger.log(
            "CodexApiClient: retrying request with user-only history " +
                "input=${userOnlyInput.input.length()} user=${userOnlyInput.userCount}"
        )
        val retry = executeRequest(retryPayload, headers)
        if (retry.isSuccess) {
            return retry
        }
        val retryMessage = retry.exceptionOrNull()?.message.orEmpty()
        if (!retryMessage.contains("Unsupported content type", ignoreCase = true) || instructions.isBlank()) {
            return retry
        }

        val strictPayload = JSONObject(retryPayload.toString()).apply { remove("instructions") }
        DebugLogger.log("CodexApiClient: retrying request without instructions due content-type error")
        return executeRequest(strictPayload, headers)
    }

    private suspend fun executeRequest(
        payload: JSONObject,
        headers: Map<String, String>
    ): Result<String> {
        DebugLogger.log("CodexApiClient: executeRequest start")
        val codexSse = executeSseRequest(
            url = CODEX_RESPONSES_URL,
            payload = payload,
            headers = headers
        )
        if (codexSse.isSuccess) {
            return codexSse
        }
        DebugLogger.log("CodexApiClient: codex SSE failed: ${codexSse.exceptionOrNull()?.message}")
        val codexMessage = codexSse.exceptionOrNull()?.message.orEmpty()
        if (!codexMessage.contains("Unsupported content type", ignoreCase = true)) {
            DebugLogger.log("CodexApiClient: codex SSE failure is non-content-type; returning early")
            return codexSse
        }

        val betaHeaders = headers.toMutableMap().apply {
            this["OpenAI-Beta"] = SSE_OPENAI_BETA
        }
        DebugLogger.log("CodexApiClient: retrying codex SSE with OpenAI-Beta header")
        val codexSseBeta = executeSseRequest(
            url = CODEX_RESPONSES_URL,
            payload = payload,
            headers = betaHeaders
        )
        if (codexSseBeta.isSuccess) {
            return codexSseBeta
        }
        DebugLogger.log("CodexApiClient: codex SSE (beta) failed: ${codexSseBeta.exceptionOrNull()?.message}")
        val codexBetaMessage = codexSseBeta.exceptionOrNull()?.message.orEmpty()
        if (!shouldTryWebSocketFallback(codexBetaMessage)) {
            DebugLogger.log("CodexApiClient: websocket fallback skipped due non-retriable error")
            return codexSseBeta
        }
        DebugLogger.log("CodexApiClient: attempting websocket fallback after codex SSE failures")
        return executeWebSocketRequest(payload, headers)
    }

    private suspend fun executeSseRequest(
        url: String,
        payload: JSONObject,
        headers: Map<String, String>
    ): Result<String> {
        val sseResponse = withContext(Dispatchers.IO) {
            OAuthHttpClient.postJsonRaw(
                url = url,
                payload = payload,
                headers = headers
            )
        }
        return sseResponse
            .mapCatching { raw -> extractCodexResponseText(raw.body, raw.contentType) }
            .onFailure { error ->
                DebugLogger.log("CodexApiClient: SSE request failed url=$url error=${error.message}")
            }
    }

    private fun buildInput(
        conversation: List<LlmMessage>,
        includeAssistantHistory: Boolean
    ): InputBuildResult {
        val input = JSONArray()
        var userInputCount = 0
        var assistantInputCount = 0

        conversation
            .filterNot { it.role.equals("system", ignoreCase = true) }
            .filter { it.content.isNotBlank() }
            .forEach { message ->
                val normalizedRole = mapRole(message.role)
                val textContent = message.content.trim()

                if (normalizedRole == "assistant") {
                    if (!includeAssistantHistory) return@forEach
                    assistantInputCount += 1

                    val tcMatch = "<tool_call>(.*?)</tool_call>".toRegex(RegexOption.DOT_MATCHES_ALL).find(textContent)
                    if (tcMatch != null) {
                        val beforeText = textContent.substring(0, tcMatch.range.first).trim()
                        if (beforeText.isNotBlank()) {
                            input.put(
                                JSONObject().put("type", "message").put("role", "assistant")
                                    .put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", beforeText)))
                            )
                        }

                        val jsonPayload = runCatching { JSONObject(tcMatch.groupValues[1]) }.getOrNull()
                        val name = jsonPayload?.optString("name")?.ifBlank { null } ?: "unknown"
                        val args = jsonPayload?.opt("arguments")?.toString() ?: "{}"
                        // Deterministic ID mapping for tool_call <-> function_call_output
                        val callId = "call_${name.hashCode()}"

                        input.put(
                            JSONObject()
                                .put("type", "function_call")
                                .put("id", callId)
                                .put("name", name)
                                .put("arguments", args)
                        )
                    } else {
                        input.put(
                            JSONObject().put("type", "message").put("role", "assistant")
                                .put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", textContent)))
                        )
                    }
                } else {
                    userInputCount += 1
                    val trMatch = "<tool_call_result>.*?<name>(.*?)</name>.*?<output>(.*?)</output>.*?</tool_call_result>"
                        .toRegex(RegexOption.DOT_MATCHES_ALL).find(textContent)

                    if (trMatch != null) {
                        val name = trMatch.groupValues[1].trim()
                        val outputText = trMatch.groupValues[2].trim()
                        val callId = "call_${name.hashCode()}"

                        input.put(
                            JSONObject()
                                .put("type", "function_call_output")
                                .put("call_id", callId)
                                .put("output", outputText)
                        )
                    } else {
                        input.put(
                            JSONObject().put("type", "message").put("role", "user")
                                .put("content", JSONArray().put(JSONObject().put("type", "input_text").put("text", textContent)))
                        )
                    }
                }
            }

        if (input.length() == 0) {
            userInputCount = 1
            input.put(
                JSONObject()
                    .put("type", "message")
                    .put("role", "user")
                    .put(
                        "content",
                        JSONArray().put(
                            JSONObject()
                                .put("type", "input_text")
                                .put("text", "Hello")
                        )
                    )
            )
        }
        return InputBuildResult(
            input = input,
            userCount = userInputCount,
            assistantCount = assistantInputCount
        )
    }

    suspend fun fetchUsageSummary(context: Context): Result<String> {
        val accessToken = authenticator.getValidAccessToken(context)
            ?: return Result.failure(IllegalStateException("Codex account is not authenticated."))
        val accountId = authenticator.getAccountId(context)
            ?: JwtUtils.codexAccountId(accessToken, null)
            ?: return Result.failure(IllegalStateException("Codex account ID is missing from OAuth token."))
        val headers = mutableMapOf(
            "Authorization" to "Bearer $accessToken",
            "User-Agent" to "$ORIGINATOR/${BuildConfig.VERSION_NAME} (Android)",
            "Accept" to "application/json",
            "originator" to ORIGINATOR
        )
        headers["chatgpt-account-id"] = accountId

        val response = withContext(Dispatchers.IO) {
            OAuthHttpClient.getJson(
                url = "https://chatgpt.com/backend-api/wham/usage",
                headers = headers
            )
        }

        return response.map { json ->
            val plan = json.optString("plan_type").ifBlank { "unknown plan" }
            val primary = json.optJSONObject("rate_limit")
                ?.optJSONObject("primary_window")
                ?.optDouble("used_percent", Double.NaN)
            if (primary != null && !primary.isNaN()) {
                "$plan (${primary.toInt()}% primary window used)"
            } else {
                plan
            }
        }
    }

    private fun extractOutputText(json: JSONObject): String? {
        json.optString("output_text").takeIf { it.isNotBlank() }?.let { return it }

        val output = json.optJSONArray("output") ?: return null
        val chunks = mutableListOf<String>()
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            val content = item.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val contentItem = content.optJSONObject(j) ?: continue
                val text = contentItem.optString("text").ifBlank { null }
                if (text != null) {
                    chunks += text
                }
                
                val toolCalls = contentItem.optJSONArray("tool_calls")
                if (toolCalls != null) {
                    for (k in 0 until toolCalls.length()) {
                        val tc = toolCalls.optJSONObject(k) ?: continue
                        val tcFunction = tc.optJSONObject("function")
                        val tcName = tcFunction?.optString("name")?.ifBlank { null } ?: tc.optString("name")
                        val tcArgs = tcFunction?.optString("arguments")?.ifBlank { null } ?: tc.optString("arguments") ?: "{}"
                        if (tcName.isNotBlank()) {
                            chunks += "<tool_call>{\"name\":\"$tcName\",\"arguments\":$tcArgs}</tool_call>"
                        }
                    }
                }
            }
        }
        return chunks.joinToString("").trim().ifBlank { null }
    }

    private fun extractCodexResponseText(body: String, contentType: String?): String {
        val loweredContentType = contentType?.lowercase().orEmpty()
        val looksLikeSse = body.contains("data: ") || body.contains("event: ")
        if (!loweredContentType.contains("text/event-stream") && !looksLikeSse) {
            val json = JSONObject(body.ifBlank { "{}" })
            return extractOutputText(json)
                ?: throw IllegalStateException("Codex returned no text output: $json")
        }

        val deltas = StringBuilder()
        var completedText: String? = null
        val chunks = body.split("\n\n")
        for (chunk in chunks) {
            if (chunk.isBlank()) continue
            val data = chunk
                .lineSequence()
                .filter { it.startsWith("data:") }
                .joinToString("\n") { it.removePrefix("data:").trim() }
                .trim()
            if (data.isBlank() || data == "[DONE]") continue

            val event = runCatching { JSONObject(data) }.getOrNull() ?: continue
            when (event.optString("type")) {
                "response.output_text.delta" -> {
                    deltas.append(event.optString("delta"))
                }
                "response.failed" -> {
                    val message = event
                        .optJSONObject("response")
                        ?.optJSONObject("error")
                        ?.optString("message")
                        .orEmpty()
                    throw IllegalStateException(message.ifBlank { "Codex response failed." })
                }
                "error" -> {
                    val message = event.optString("message").ifBlank { event.toString() }
                    throw IllegalStateException(message)
                }
                "response.done", "response.completed" -> {
                    val responseJson = event.optJSONObject("response") ?: continue
                    val message = responseJson.optJSONObject("error")?.optString("message").orEmpty()
                    if (message.isNotBlank()) {
                        throw IllegalStateException(message)
                    }
                    completedText = extractOutputText(responseJson) ?: completedText
                }
            }
        }

        if (!completedText.isNullOrBlank()) {
            return completedText
        }
        val deltaText = deltas.toString().trim()
        if (deltaText.isNotBlank()) {
            return deltaText
        }
        DebugLogger.log("CodexApiClient: SSE response had no output text (len=${body.length})")
        throw IllegalStateException("Codex returned no text output.")
    }

    private fun mapRole(role: String): String {
        return when (role.lowercase()) {
            "assistant", "model" -> "assistant"
            "system" -> "system"
            else -> "user"
        }
    }

    private fun shouldTryWebSocketFallback(message: String): Boolean {
        val lowered = message.lowercase()
        val shouldFallback = lowered.contains("unsupported content type") ||
            lowered.contains("http 404") ||
            lowered.contains("not found") ||
            lowered.contains("http 415")
        DebugLogger.log(
            "CodexApiClient: shouldTryWebSocketFallback=$shouldFallback " +
                "reason='${message.take(260)}'"
        )
        return shouldFallback
    }

    private fun resolveSessionId(context: Context): String {
        val prefs = context.getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE)
        val existing = prefs.getString(SESSION_ID_KEY, null)
        if (!existing.isNullOrBlank()) return existing
        val created = UUID.randomUUID().toString()
        prefs.edit().putString(SESSION_ID_KEY, created).apply()
        return created
    }

    private suspend fun executeWebSocketRequest(
        payload: JSONObject,
        headers: Map<String, String>
    ): Result<String> = withContext(Dispatchers.IO) {
        val completion = CompletableDeferred<Result<String>>()
        val requestBuilder = Request.Builder()
            .url("wss://chatgpt.com/backend-api/codex/responses")
        headers.forEach { (name, value) ->
            requestBuilder.header(name, value)
        }
        requestBuilder.header("OpenAI-Beta", WS_OPENAI_BETA)
        requestBuilder.header("Accept", "application/json")

        val deltas = StringBuilder()
        var completedText: String? = null

        val socket = websocketClient.newWebSocket(
            requestBuilder.build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    DebugLogger.log(
                        "CodexApiClient: websocket opened code=${response.code} " +
                            "message=${response.message} " +
                            "request_id=${response.header("x-request-id") ?: response.header("request-id") ?: "-"} " +
                            "cf_ray=${response.header("cf-ray") ?: "-"}"
                    )
                    val requestJson = JSONObject(payload.toString())
                        .put("type", "response.create")
                        .toString()
                    DebugLogger.log("CodexApiClient: websocket send response.create bytes=${requestJson.length}")
                    webSocket.send(requestJson)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val event = runCatching { JSONObject(text) }.getOrNull() ?: return
                    when (event.optString("type")) {
                        "response.output_text.delta" -> {
                            deltas.append(event.optString("delta"))
                        }
                        "response.failed" -> {
                            val errorMessage = event
                                .optJSONObject("response")
                                ?.optJSONObject("error")
                                ?.optString("message")
                                .orEmpty()
                            if (!completion.isCompleted) {
                                completion.complete(
                                    Result.failure(
                                        IllegalStateException(errorMessage.ifBlank { "Codex websocket response failed." })
                                    )
                                )
                            }
                        }
                        "error" -> {
                            val errorMessage = event.optString("message").ifBlank { event.toString() }
                            if (!completion.isCompleted) {
                                completion.complete(Result.failure(IllegalStateException(errorMessage)))
                            }
                        }
                        "response.done", "response.completed" -> {
                            val responseJson = event.optJSONObject("response")
                            val errorMessage = responseJson
                                ?.optJSONObject("error")
                                ?.optString("message")
                                .orEmpty()
                            if (errorMessage.isNotBlank()) {
                                if (!completion.isCompleted) {
                                    completion.complete(Result.failure(IllegalStateException(errorMessage)))
                                }
                                return
                            }
                            completedText = responseJson?.let { extractOutputText(it) } ?: completedText
                            val deltaText = deltas.toString().trim()
                            val resolvedText = if (!completedText.isNullOrBlank()) completedText.orEmpty() else deltaText
                            if (!completion.isCompleted) {
                                if (resolvedText.isNotBlank()) {
                                    completion.complete(Result.success(resolvedText))
                                } else {
                                    completion.complete(
                                        Result.failure(IllegalStateException("Codex websocket returned no text output."))
                                    )
                                }
                            }
                        }
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val responseCode = response?.code ?: -1
                    val responseMessage = response?.message ?: "-"
                    val contentType = response?.header("Content-Type") ?: "-"
                    val requestId = response?.header("x-request-id")
                        ?: response?.header("request-id")
                        ?: response?.header("openai-request-id")
                        ?: "-"
                    val cfRay = response?.header("cf-ray") ?: "-"
                    val bodyPreview = runCatching { response?.body?.string().orEmpty() }
                        .getOrDefault("")
                        .let { if (it.length > 2000) "${it.take(2000)}..." else it }
                    DebugLogger.log(
                        "CodexApiClient: websocket failure throwable=${t.message} " +
                            "http_code=$responseCode http_message=$responseMessage ct=$contentType " +
                            "request_id=$requestId cf_ray=$cfRay body=$bodyPreview"
                    )
                    if (!completion.isCompleted) {
                        completion.complete(Result.failure(IllegalStateException(t.message ?: "Codex websocket failed", t)))
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    DebugLogger.log("CodexApiClient: websocket closed code=$code reason=$reason")
                    if (!completion.isCompleted) {
                        val deltaText = deltas.toString().trim()
                        val resolvedText = if (!completedText.isNullOrBlank()) completedText.orEmpty() else deltaText
                        if (resolvedText.isNotBlank()) {
                            completion.complete(Result.success(resolvedText))
                        } else {
                            completion.complete(
                                Result.failure(
                                    IllegalStateException(
                                        "Codex websocket closed before completion (code=$code, reason=$reason)."
                                    )
                                )
                            )
                        }
                    }
                }
            }
        )

        try {
            withTimeout(WS_TIMEOUT_MS) { completion.await() }
        } catch (timeout: TimeoutCancellationException) {
            DebugLogger.log("CodexApiClient: websocket timeout after ${WS_TIMEOUT_MS}ms")
            Result.failure(IllegalStateException("Codex websocket request timed out.", timeout))
        } finally {
            runCatching { socket.close(1000, "done") }
        }
    }

    private fun summarizeHeaders(headers: Map<String, String>): String {
        val interesting = linkedMapOf<String, String>()
        listOf(
            "originator",
            "Accept",
            "version",
            "chatgpt-account-id",
            "session_id",
            "OpenAI-Beta",
            "User-Agent"
        ).forEach { key ->
            headers[key]?.let { value ->
                interesting[key] = when {
                    key.equals("chatgpt-account-id", ignoreCase = true) -> value.take(6) + "..."
                    key.equals("session_id", ignoreCase = true) -> value.take(8) + "..."
                    else -> value
                }
            }
        }
        interesting["Authorization"] = if (headers.containsKey("Authorization")) "Bearer ***" else "-"
        return JSONObject(interesting as Map<*, *>).toString()
    }

    private fun summarizePayload(payload: JSONObject): String {
        val input = payload.optJSONArray("input")
        val first = input?.optJSONObject(0)
        val firstRole = first?.optString("role").orEmpty()
        val firstType = first?.optString("type").orEmpty()
        val firstContentType = first
            ?.optJSONArray("content")
            ?.optJSONObject(0)
            ?.optString("type")
            .orEmpty()
        val summary = JSONObject()
            .put("keys", JSONArray(payload.keys().asSequence().toList()))
            .put("model", payload.optString("model"))
            .put("stream", payload.optBoolean("stream"))
            .put("store", payload.optBoolean("store"))
            .put("input_count", input?.length() ?: 0)
            .put("first_input_type", firstType)
            .put("first_input_role", firstRole)
            .put("first_content_type", firstContentType)
            .put("has_instructions", payload.optString("instructions").isNotBlank())
            .put("tools_count", payload.optJSONArray("tools")?.length() ?: 0)
        return summary.toString()
    }
}

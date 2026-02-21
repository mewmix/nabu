package com.mewmix.nabu.auth

import android.content.Context
import com.mewmix.nabu.chat.LlmMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class CodexApiClient(
    private val authenticator: CodexAuthenticator = CodexAuthenticator()
) {
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

        val payload = JSONObject().put("model", model).put("store", false)
        val instructions = conversation
            .filter { it.role.equals("system", ignoreCase = true) && it.content.isNotBlank() }
            .joinToString("\n\n") { it.content.trim() }
        if (instructions.isNotBlank()) {
            payload.put("instructions", instructions)
        }
        val input = JSONArray()
        conversation
            .filterNot { it.role.equals("system", ignoreCase = true) }
            .filter { it.content.isNotBlank() }
            .forEach { message ->
                input.put(
                    JSONObject()
                        .put("role", mapRole(message.role))
                        .put(
                            "content",
                            JSONArray().put(
                                JSONObject()
                                    .put("type", "input_text")
                                    .put("text", message.content)
                            )
                        )
                )
            }
        if (input.length() == 0) {
            input.put(
                JSONObject()
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
        payload.put("input", input)

        val headers = mutableMapOf(
            "Authorization" to "Bearer $accessToken",
            "User-Agent" to "Nabu-Android/1.0",
            "OpenAI-Beta" to "responses=experimental",
            "originator" to "nabu",
            "Accept" to "text/event-stream"
        )
        headers["chatgpt-account-id"] = accountId

        val response = withContext(Dispatchers.IO) {
            OAuthHttpClient.postJson(
                url = "https://chatgpt.com/backend-api/codex/responses",
                payload = payload,
                headers = headers
            )
        }

        return response.map { json ->
            extractOutputText(json) ?: throw IllegalStateException("Codex returned no text output: $json")
        }
    }

    suspend fun fetchUsageSummary(context: Context): Result<String> {
        val accessToken = authenticator.getValidAccessToken(context)
            ?: return Result.failure(IllegalStateException("Codex account is not authenticated."))
        val accountId = authenticator.getAccountId(context)
            ?: JwtUtils.codexAccountId(accessToken, null)
            ?: return Result.failure(IllegalStateException("Codex account ID is missing from OAuth token."))
        val headers = mutableMapOf(
            "Authorization" to "Bearer $accessToken",
            "User-Agent" to "Nabu-Android/1.0",
            "Accept" to "application/json",
            "OpenAI-Beta" to "responses=experimental",
            "originator" to "nabu"
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
                val text = contentItem.optString("text").ifBlank { null } ?: continue
                chunks += text
            }
        }
        return chunks.joinToString("").trim().ifBlank { null }
    }

    private fun mapRole(role: String): String {
        return when (role.lowercase()) {
            "assistant", "model" -> "assistant"
            "system" -> "system"
            else -> "user"
        }
    }
}

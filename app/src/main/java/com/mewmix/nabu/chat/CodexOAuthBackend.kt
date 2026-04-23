package com.mewmix.nabu.chat

import android.content.Context
import com.mewmix.nabu.auth.CodexApiClient
import com.mewmix.nabu.data.OAuthRemoteModels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class CodexOAuthBackend(
    private val context: Context,
    private val model: String = OAuthRemoteModels.DEFAULT_CODEX_MODEL,
    private val apiClient: CodexApiClient = CodexApiClient()
) : LlmBackend {
    private val scope = CoroutineScope(Dispatchers.IO)
    @Volatile private var activeJob: Job? = null

    override fun initialize() {
        // No warmup needed for remote backend.
    }

    override fun sendMessage(
        conversation: List<LlmMessage>,
        resultListener: (partialResult: String, done: Boolean) -> Unit
    ) {
        cancel()
        activeJob = scope.launch {
            val result = apiClient.sendConversation(
                context = context.applicationContext,
                conversation = conversation,
                model = model
            )
            if (!isActive) return@launch
            result.fold(
                onSuccess = { text -> resultListener(text, true) },
                onFailure = { error ->
                    resultListener("Codex request failed: ${error.message ?: "unknown error"}", true)
                }
            )
        }
    }

    override fun sendMessage(
        prompt: String,
        resultListener: (partialResult: String, done: Boolean) -> Unit
    ) {
        sendMessage(
            conversation = listOf(LlmMessage(role = "user", content = prompt)),
            resultListener = resultListener
        )
    }

    override fun cancel() {
        activeJob?.cancel()
        activeJob = null
    }

    override fun close() {
        cancel()
    }
}

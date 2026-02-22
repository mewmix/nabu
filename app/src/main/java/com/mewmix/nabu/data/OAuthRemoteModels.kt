package com.mewmix.nabu.data

import android.content.Context
import com.mewmix.nabu.auth.CodexAuthenticator
import com.mewmix.nabu.auth.GeminiAuthenticator

object OAuthRemoteModels {
    private const val REMOTE_PREFIX = "oauth://"
    private const val PROVIDER_CODEX = "codex"
    private const val PROVIDER_GEMINI = "gemini"

    // Legacy IDs kept for backward compatibility with older conversations.
    const val LEGACY_CODEX_MODEL_ID = "codex-byos-oauth"
    const val LEGACY_GEMINI_MODEL_ID = "gemini-byos-oauth"

    const val DEFAULT_CODEX_MODEL = "gpt-5.3-codex-spark"
    const val DEFAULT_GEMINI_MODEL = "gemini-2.5-flash"

    enum class Provider {
        CODEX,
        GEMINI
    }

    data class RemoteSelection(
        val provider: Provider,
        val modelSlug: String
    )

    private data class RemoteModelSpec(
        val provider: Provider,
        val modelSlug: String,
        val title: String,
        val endpointLabel: String
    ) {
        val id: String = buildId(provider, modelSlug)
    }

    private fun buildId(provider: Provider, modelSlug: String): String {
        val providerId = when (provider) {
            Provider.CODEX -> PROVIDER_CODEX
            Provider.GEMINI -> PROVIDER_GEMINI
        }
        return "$REMOTE_PREFIX$providerId/$modelSlug"
    }

    private fun normalizeProvider(provider: String): Provider? {
        return when (provider.lowercase()) {
            PROVIDER_CODEX -> Provider.CODEX
            PROVIDER_GEMINI -> Provider.GEMINI
            else -> null
        }
    }

    private val codexSpecs = listOf(
        RemoteModelSpec(
            provider = Provider.CODEX,
            modelSlug = "gpt-5.3-codex-spark",
            title = "Codex · GPT-5.3 Codex Spark",
            endpointLabel = "chatgpt.com/backend-api/codex/responses"
        ),
        RemoteModelSpec(
            provider = Provider.CODEX,
            modelSlug = "gpt-5.3-codex",
            title = "Codex · GPT-5.3 Codex",
            endpointLabel = "chatgpt.com/backend-api/codex/responses"
        ),
        RemoteModelSpec(
            provider = Provider.CODEX,
            modelSlug = "gpt-5-codex",
            title = "Codex · GPT-5 Codex",
            endpointLabel = "chatgpt.com/backend-api/codex/responses"
        ),
        RemoteModelSpec(
            provider = Provider.CODEX,
            modelSlug = "codex-mini-latest",
            title = "Codex · Mini Latest",
            endpointLabel = "chatgpt.com/backend-api/codex/responses"
        )
    )

    private val geminiSpecs = listOf(
        RemoteModelSpec(
            provider = Provider.GEMINI,
            modelSlug = DEFAULT_GEMINI_MODEL,
            title = "Gemini · 2.5 Flash",
            endpointLabel = "generativelanguage.googleapis.com/v1beta"
        ),
        RemoteModelSpec(
            provider = Provider.GEMINI,
            modelSlug = "gemini-2.5-pro",
            title = "Gemini · 2.5 Pro",
            endpointLabel = "generativelanguage.googleapis.com/v1beta"
        ),
        RemoteModelSpec(
            provider = Provider.GEMINI,
            modelSlug = "gemini-2.0-flash",
            title = "Gemini · 2.0 Flash",
            endpointLabel = "generativelanguage.googleapis.com/v1beta"
        )
    )

    fun normalizeModelId(modelId: String): String {
        return when (modelId) {
            LEGACY_CODEX_MODEL_ID -> buildId(Provider.CODEX, DEFAULT_CODEX_MODEL)
            LEGACY_GEMINI_MODEL_ID -> buildId(Provider.GEMINI, DEFAULT_GEMINI_MODEL)
            else -> modelId
        }
    }

    fun detectSelection(modelId: String, backend: String? = null): RemoteSelection? {
        when (modelId) {
            LEGACY_CODEX_MODEL_ID -> return RemoteSelection(Provider.CODEX, DEFAULT_CODEX_MODEL)
            LEGACY_GEMINI_MODEL_ID -> return RemoteSelection(Provider.GEMINI, DEFAULT_GEMINI_MODEL)
        }
        if (modelId.startsWith(REMOTE_PREFIX)) {
            val route = modelId.removePrefix(REMOTE_PREFIX)
            val slash = route.indexOf('/')
            if (slash > 0 && slash < route.length - 1) {
                val provider = normalizeProvider(route.substring(0, slash))
                val modelSlug = route.substring(slash + 1).trim()
                if (provider != null && modelSlug.isNotBlank()) {
                    return RemoteSelection(provider, modelSlug)
                }
            }
        }
        return when (backend) {
            "codex_oauth" -> RemoteSelection(Provider.CODEX, DEFAULT_CODEX_MODEL)
            "gemini_oauth" -> RemoteSelection(Provider.GEMINI, DEFAULT_GEMINI_MODEL)
            else -> null
        }
    }

    fun syntheticModelForId(modelId: String, backend: String? = null): Model? {
        val normalizedId = normalizeModelId(modelId)
        val selection = detectSelection(normalizedId, backend) ?: return null
        val providerLabel = when (selection.provider) {
            Provider.CODEX -> "Codex"
            Provider.GEMINI -> "Gemini"
        }
        val endpointLabel = when (selection.provider) {
            Provider.CODEX -> "chatgpt.com/backend-api/codex/responses"
            Provider.GEMINI -> "generativelanguage.googleapis.com/v1beta"
        }
        val backendId = when (selection.provider) {
            Provider.CODEX -> "codex_oauth"
            Provider.GEMINI -> "gemini_oauth"
        }
        return Model(
            id = normalizedId,
            name = "$providerLabel · ${selection.modelSlug}",
            description = "$providerLabel BYOS OAuth (${selection.modelSlug}) via $endpointLabel",
            repo = "",
            downloadUrl = "",
            gated = false,
            type = ModelType.LLM,
            initialIsDownloaded = true,
            initialBackend = backendId
        )
    }

    fun connectedModels(context: Context): List<Model> {
        val appContext = context.applicationContext
        val models = mutableListOf<Model>()

        if (CodexAuthenticator().hasStoredSession(appContext)) {
            models += codexSpecs.map { spec ->
                Model(
                    id = spec.id,
                    name = spec.title,
                    description = "OpenAI BYOS OAuth (${spec.modelSlug}) via ${spec.endpointLabel}",
                    repo = "",
                    downloadUrl = "",
                    gated = false,
                    type = ModelType.LLM,
                    initialIsDownloaded = true,
                    initialBackend = "codex_oauth"
                )
            }
        }

        if (GeminiAuthenticator().hasStoredSession(appContext)) {
            models += geminiSpecs.map { spec ->
                Model(
                    id = spec.id,
                    name = spec.title,
                    description = "Google BYOS OAuth (${spec.modelSlug}) via ${spec.endpointLabel}",
                    repo = "",
                    downloadUrl = "",
                    gated = false,
                    type = ModelType.LLM,
                    initialIsDownloaded = true,
                    initialBackend = "gemini_oauth"
                )
            }
        }

        return models
    }
}

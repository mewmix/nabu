package com.mewmix.nabu.data

import android.content.Context
import com.mewmix.nabu.auth.CodexAuthenticator

object OAuthRemoteModels {
    private const val REMOTE_PREFIX = "oauth://"
    private const val PROVIDER_CODEX = "codex"

    // Legacy IDs kept for backward compatibility with older conversations.
    const val LEGACY_CODEX_MODEL_ID = "codex-byos-oauth"

    const val DEFAULT_CODEX_MODEL = "gpt-5.5"

    enum class Provider {
        CODEX
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
        }
        return "$REMOTE_PREFIX$providerId/$modelSlug"
    }

    private fun normalizeProvider(provider: String): Provider? {
        return when (provider.lowercase()) {
            PROVIDER_CODEX -> Provider.CODEX
            else -> null
        }
    }

    private val codexSpecs = listOf(
        RemoteModelSpec(
            provider = Provider.CODEX,
            modelSlug = "gpt-5.5",
            title = "Codex · GPT-5.5",
            endpointLabel = "chatgpt.com/backend-api/codex/responses"
        ),
        RemoteModelSpec(
            provider = Provider.CODEX,
            modelSlug = "gpt-5.4",
            title = "Codex · GPT-5.4",
            endpointLabel = "chatgpt.com/backend-api/codex/responses"
        ),
    )

    fun normalizeModelId(modelId: String): String {
        return when (modelId) {
            LEGACY_CODEX_MODEL_ID -> buildId(Provider.CODEX, DEFAULT_CODEX_MODEL)
            buildId(Provider.CODEX, "gpt-5.3-codex") -> buildId(Provider.CODEX, DEFAULT_CODEX_MODEL)
            buildId(Provider.CODEX, "gpt-5.2-codex") -> buildId(Provider.CODEX, "gpt-5.4")
            else -> modelId
        }
    }

    fun detectSelection(modelId: String, backend: String? = null): RemoteSelection? {
        when (modelId) {
            LEGACY_CODEX_MODEL_ID -> return RemoteSelection(Provider.CODEX, DEFAULT_CODEX_MODEL)
        }
        val normalizedId = normalizeModelId(modelId)
        if (normalizedId.startsWith(REMOTE_PREFIX)) {
            val route = normalizedId.removePrefix(REMOTE_PREFIX)
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
            else -> null
        }
    }

    fun syntheticModelForId(modelId: String, backend: String? = null): Model? {
        val normalizedId = normalizeModelId(modelId)
        val selection = detectSelection(normalizedId, backend) ?: return null
        val providerLabel = when (selection.provider) {
            Provider.CODEX -> "Codex"
        }
        val endpointLabel = when (selection.provider) {
            Provider.CODEX -> "chatgpt.com/backend-api/codex/responses"
        }
        val backendId = when (selection.provider) {
            Provider.CODEX -> "codex_oauth"
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

        return models
    }
}

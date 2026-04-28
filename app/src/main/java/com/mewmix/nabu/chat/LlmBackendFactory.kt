package com.mewmix.nabu.chat

import android.content.Context
import com.mewmix.nabu.data.Model
import com.mewmix.nabu.data.ModelManager
import com.mewmix.nabu.data.OAuthRemoteModels
import com.mewmix.nabu.data.findDownloadedLlmArtifact
import com.mewmix.nabu.utils.DebugLogger
import com.mewmix.nabu.utils.SettingsManager
import java.io.File

object LlmBackendFactory {
    const val DEFAULT_MAX_CONTEXT_TOKENS = 1024

    data class CreatedBackend(
        val backend: LlmBackend,
        val model: Model,
        val maxContextTokens: Int
    )

    fun create(
        context: Context,
        modelId: String,
        llmOverrides: LlmRuntimeOverrides? = null,
        initializeSynchronously: Boolean = true
    ): CreatedBackend? {
        val appContext = context.applicationContext
        val model = resolveModelById(appContext, modelId) ?: return null
        val remoteSelection = OAuthRemoteModels.detectSelection(model.id, model.backend)
        if (remoteSelection?.provider == OAuthRemoteModels.Provider.CODEX) {
            val backend = CodexOAuthBackend(
                context = appContext,
                model = remoteSelection.modelSlug
            )
            backend.initialize()
            return CreatedBackend(
                backend = backend,
                model = model,
                maxContextTokens = DEFAULT_MAX_CONTEXT_TOKENS
            )
        }

        val artifact = findDownloadedLlmArtifact(File(appContext.filesDir, "models"), model.id, model.backend)
        if (artifact == null) {
            DebugLogger.log("LlmBackendFactory: model file not found for ${model.id} (.task/.litertlm/.gguf)")
            return null
        }

        val backend = when (artifact.backend) {
            "llama" -> {
                val runtimeConfig = SettingsManager.getLlmRuntimeConfig(appContext, llmOverrides)
                LlamaCppBackend(appContext, artifact.file.absolutePath, runtimeConfig).also {
                    if (initializeSynchronously) {
                        it.initialize()
                    }
                }
            }
            "litertlm" -> {
                LiteRtLmBackend(
                    context = appContext,
                    modelPath = artifact.file.absolutePath
                ).also { it.initialize() }
            }
            else -> {
                MediaPipeBackend(
                    context = appContext,
                    modelPath = artifact.file.absolutePath,
                    initialConfig = SettingsManager.getMediaPipeRuntimeConfig(appContext)
                ).also { it.initialize() }
            }
        }

        val maxTokens = when (backend) {
            is LlamaCppBackend -> backend.currentConfig.nCtx
            is MediaPipeBackend -> backend.currentConfig.maxTokens
            else -> DEFAULT_MAX_CONTEXT_TOKENS
        }
        return CreatedBackend(backend = backend, model = model, maxContextTokens = maxTokens)
    }

    fun resolveModelById(context: Context, modelId: String): Model? {
        val modelManager = ModelManager(context.applicationContext)
        val normalizedId = OAuthRemoteModels.normalizeModelId(modelId)
        return modelManager.getModel(normalizedId)?.takeIf { it.isDownloaded }
            ?: modelManager.getModel(modelId)?.takeIf { it.isDownloaded }
            ?: OAuthRemoteModels.syntheticModelForId(normalizedId)
            ?: OAuthRemoteModels.syntheticModelForId(modelId)
    }
}

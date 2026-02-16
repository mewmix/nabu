package com.mewmix.nabu.tts

import android.content.Context
import com.mewmix.nabu.data.ModelType
import com.mewmix.nabu.kokoro.KokoroEngine
import com.mewmix.nabu.supertonic.DebugSupertonicEngine
import com.mewmix.nabu.soprano.SopranoEngine
import com.mewmix.nabu.utils.DebugLogger
import com.mewmix.nabu.utils.OnnxRuntimeManager
import java.io.File
import com.mewmix.nabu.data.ModelManager
import com.mewmix.nabu.data.TtsModelValidator
import ai.onnxruntime.OrtEnvironment
import com.mewmix.nabu.utils.SettingsManager
import com.mewmix.nabu.kokoro.RunEp

object TTSManager {
    private enum class EngineKind {
        KOKORO,
        SUPERTONIC,
        SOPRANO
    }

    private var activeEngine: TTSEngine? = null
    private var activeEngineKind: EngineKind? = null
    private var activeRuntimePreference: RunEp? = null
    private var activeSupertonicModelId: String? = null
    private var activeSopranoModelId: String? = null

    private const val SOPRANO_MODEL_ID = "soprano-80m-onnx"

    private fun unwrapEngine(engine: TTSEngine?): TTSEngine? =
        if (engine is BenchmarkingTTSEngine) engine.delegate else engine

    private fun resolveEngineKind(engine: TTSEngine?): EngineKind? {
        val raw = unwrapEngine(engine) ?: return null
        return when (raw) {
            is KokoroEngine -> EngineKind.KOKORO
            is DebugSupertonicEngine -> EngineKind.SUPERTONIC
            is SopranoEngine -> EngineKind.SOPRANO
            else -> when (raw.name.lowercase()) {
                "kokoro" -> EngineKind.KOKORO
                "supertonic" -> EngineKind.SUPERTONIC
                "soprano" -> EngineKind.SOPRANO
                else -> null
            }
        }
    }

    private fun clearActiveEngineState(closeExisting: Boolean = true) {
        if (closeExisting) {
            activeEngine?.close()
        }
        activeEngine = null
        activeEngineKind = null
        activeRuntimePreference = null
        activeSupertonicModelId = null
        activeSopranoModelId = null
    }

    suspend fun getEngine(context: Context, modelManager: ModelManager): TTSEngine? {
        DebugLogger.log("TTSManager.getEngine: enter")
        val preferredEngine = SettingsManager.getTtsEngine(context)
        val preferredRuntime = SettingsManager.getRuntimePreference(context)
        val preferredSupertonicModel = SettingsManager.getSupertonicModelId(context)

        val inferredActiveKind = activeEngineKind ?: resolveEngineKind(activeEngine).also { activeEngineKind = it }
        DebugLogger.log(
            "Prefs engine=%s runtime=%s supertonicModel=%s active=%s activeKind=%s".format(
                preferredEngine,
                preferredRuntime,
                preferredSupertonicModel,
                activeEngine?.name,
                inferredActiveKind
            )
        )

        if (activeEngine != null) {
            val canReuse = when (preferredEngine) {
                "kokoro" -> inferredActiveKind == EngineKind.KOKORO &&
                    activeRuntimePreference == preferredRuntime
                "supertonic" -> inferredActiveKind == EngineKind.SUPERTONIC &&
                    (preferredSupertonicModel == null || activeSupertonicModelId == preferredSupertonicModel)
                "soprano" -> inferredActiveKind == EngineKind.SOPRANO &&
                    activeSopranoModelId == SOPRANO_MODEL_ID
                else -> false
            }

            if (canReuse) {
                DebugLogger.log("Reusing active engine: %s (%s)".format(activeEngine?.name, inferredActiveKind))
                return activeEngine
            }

            DebugLogger.log(
                "Discarding active engine due to preference mismatch. preferred=%s activeKind=%s runtime=%s activeRuntime=%s supertonicModel=%s activeSupertonic=%s sopranoModel=%s".format(
                    preferredEngine,
                    inferredActiveKind,
                    preferredRuntime,
                    activeRuntimePreference,
                    preferredSupertonicModel,
                    activeSupertonicModelId,
                    activeSopranoModelId
                )
            )
            clearActiveEngineState()
        }

        if (preferredEngine == "soprano") {
            DebugLogger.log("TTSManager: Preference=Soprano. Verifying local model files...")
            val modelId = SOPRANO_MODEL_ID
            val modelDir = File(context.filesDir, "models/$modelId")
            val partialDir = File(context.filesDir, "models/${modelId}_partial")
            val validInTarget = TtsModelValidator.hasAllRequiredFiles(modelId, modelDir)
            val validInPartial = TtsModelValidator.hasAllRequiredFiles(modelId, partialDir)

            val resolvedModelDir = when {
                validInTarget -> modelDir
                validInPartial -> {
                    DebugLogger.log("TTSManager: Soprano complete in partial dir; promoting to final dir")
                    val promoted = runCatching {
                        if (modelDir.exists()) modelDir.deleteRecursively()
                        if (!partialDir.renameTo(modelDir)) {
                            partialDir.copyRecursively(modelDir, overwrite = true)
                            partialDir.deleteRecursively()
                        }
                        modelDir
                    }.getOrElse {
                        DebugLogger.log("TTSManager: Failed to promote partial soprano dir: ${it.message}")
                        partialDir
                    }
                    promoted
                }
                else -> null
            }
            val missing = TtsModelValidator.missingFiles(modelId, modelDir, partialDir)

            if (resolvedModelDir != null) {
                try {
                    DebugLogger.log("TTSManager: Loading Soprano from ${resolvedModelDir.absolutePath}")
                    val engine = SopranoEngine(resolvedModelDir, OrtEnvironment.getEnvironment())
                    activeEngine = BenchmarkingTTSEngine(engine)
                    activeEngineKind = EngineKind.SOPRANO
                    activeRuntimePreference = RunEp.CPU
                    activeSupertonicModelId = null
                    activeSopranoModelId = modelId
                    DebugLogger.log("TTSManager: Switched to Soprano ($modelId)")
                    activeEngine
                    return activeEngine
                } catch (e: Exception) {
                    DebugLogger.logErr("TTSManager: Failed to load Soprano from %s".format(modelDir.absolutePath), e)
                    return null
                }
            } else {
                DebugLogger.log("TTSManager: Soprano selected but missing files: ${missing.joinToString()}")
                return null
            }
        }

        if (preferredEngine == "supertonic") {
            // Only consider Supertonic models, not Soprano
            val ttsModels = modelManager.models.filter { it.type == ModelType.TTS && it.isDownloaded && it.id.startsWith("supertonic") }
            val selectedModel = preferredSupertonicModel?.let { modelId ->
                ttsModels.firstOrNull { it.id == modelId }
            }
            val model = if (preferredSupertonicModel != null) {
                selectedModel
            } else {
                ttsModels.firstOrNull()
            }
            if (model != null) {
                val modelDir = File(context.filesDir, "models/${model.id}")
                try {
                    val engine = DebugSupertonicEngine(modelDir)
                    activeEngine = BenchmarkingTTSEngine(engine)
                    activeEngineKind = EngineKind.SUPERTONIC
                    activeRuntimePreference = null
                    activeSupertonicModelId = model.id
                    activeSopranoModelId = null
                    DebugLogger.log("TTSManager: Switched to Supertonic (${model.name})")
                    return activeEngine
                } catch (e: Exception) {
                    DebugLogger.logErr("TTSManager: Failed to load Supertonic", e)
                    // Fallback to Kokoro? Or just return null?
                    // Let's fall back to Kokoro to be safe.
                }
            } else if (preferredSupertonicModel != null) {
                DebugLogger.log("TTSManager: Supertonic selected model missing: $preferredSupertonicModel")
                return null
            } else {
                DebugLogger.log("TTSManager: Supertonic selected but no model found.")
            }
        }

        // Fallback or default to Kokoro (but not when user explicitly selected Soprano)
        if (preferredEngine == "soprano") {
            DebugLogger.log("TTSManager: Soprano selected; not falling back to Kokoro")
            return null
        }

        try {
            DebugLogger.log("Kokoro fallback: initializing runtime")
            val bundle = OnnxRuntimeManager.initialize(context).getOrNull()
            if (bundle != null) {
                 activeEngine = BenchmarkingTTSEngine(OnnxRuntimeManager.getEngine())
                 activeEngineKind = EngineKind.KOKORO
                 activeRuntimePreference = preferredRuntime
                 activeSupertonicModelId = null
                 activeSopranoModelId = null
                 DebugLogger.log("TTSManager: Switched to Kokoro (fallback or default)")
                 return activeEngine
            }
        } catch (e: Exception) {
             DebugLogger.log("TTSManager: Failed to load Kokoro: ${e.message}")
        }

        return null
    }

    fun close() {
        clearActiveEngineState()
    }
}

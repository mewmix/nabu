package com.example.nabu.tts

import android.content.Context
import com.example.nabu.data.ModelType
import com.example.nabu.data.UserPreferencesRepository
import com.example.nabu.kokoro.KokoroEngine
import com.example.nabu.supertonic.DebugSupertonicEngine
import com.example.nabu.supertonic.SupertonicStyle
import com.example.nabu.utils.DebugLogger
import com.example.nabu.utils.OnnxRuntimeManager
import kotlinx.coroutines.flow.first
import java.io.File
import com.example.nabu.data.ModelManager
import ai.onnxruntime.OrtEnvironment
import com.example.nabu.utils.SettingsManager

object TTSManager {
    private var activeEngine: TTSEngine? = null

    suspend fun getEngine(context: Context, modelManager: ModelManager): TTSEngine? {
        val preferredEngine = SettingsManager.getTtsEngine(context)

        // If we have an active engine, check if it matches the preference.
        if (activeEngine != null) {
            val isSupertonic = activeEngine?.name == "Supertonic"
            if (preferredEngine == "supertonic" && !isSupertonic) {
                activeEngine?.close()
                activeEngine = null
            } else if (preferredEngine == "kokoro" && isSupertonic) {
                activeEngine?.close()
                activeEngine = null
            } else {
                return activeEngine
            }
        }

        // Initialize based on preference. Do NOT fallback silently.
        if (preferredEngine == "supertonic") {
             val ttsModels = modelManager.models.filter { it.type == ModelType.TTS && it.isDownloaded }
             if (ttsModels.isNotEmpty()) {
                 val model = ttsModels.first()
                 val modelDir = File(context.filesDir, "models/${model.id}")
                 try {
                     val engine = DebugSupertonicEngine(modelDir)
                     activeEngine = BenchmarkingTTSEngine(engine)
                     DebugLogger.log("TTSManager: Switched to Supertonic (${model.name})")
                     return activeEngine
                 } catch (e: Exception) {
                     DebugLogger.log("TTSManager: Failed to load Supertonic: ${e.message}")
                     return null // Explicit failure
                 }
             } else {
                 DebugLogger.log("TTSManager: Supertonic selected but no model found.")
                 return null // Explicit failure
             }
        } else {
            // Default to Kokoro
            try {
                // Ensure initialized. If it fails or returns null, we fail.
                val bundle = OnnxRuntimeManager.initialize(context).getOrNull()
                if (bundle != null) {
                     activeEngine = BenchmarkingTTSEngine(OnnxRuntimeManager.getEngine())
                     DebugLogger.log("TTSManager: Switched to Kokoro")
                     return activeEngine
                } else {
                    DebugLogger.log("TTSManager: Kokoro bundle not available.")
                }
            } catch (e: Exception) {
                 DebugLogger.log("TTSManager: Failed to load Kokoro: ${e.message}")
            }
        }

        return null
    }

    fun close() {
        activeEngine?.close()
        activeEngine = null
    }
}

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

object TTSManager {
    private var activeEngine: TTSEngine? = null
    private var activeType: ModelType = ModelType.LLM // default to LLM/Kokoro logic
    // Wait, ModelType.LLM is confusing here. I should use a specific TTS type enum or just check.
    // Let's use string id or a separate enum.

    suspend fun getEngine(context: Context, modelManager: ModelManager): TTSEngine? {
        // Simple logic: check user preference for TTS engine.
        // For now, let's look for a downloaded Supertonic model and use it if preferred.
        // Or we can just expose a method to set the engine.

        if (activeEngine != null) return activeEngine

        // Try to load Supertonic if available and selected
        // We need a preference for "Active TTS Model".
        // For now, let's iterate available TTS models.
        val ttsModels = modelManager.models.filter { it.type == ModelType.TTS && it.isDownloaded }

        if (ttsModels.isNotEmpty()) {
            val model = ttsModels.first() // Just pick first for now
            val modelDir = File(context.filesDir, "models/${model.id}")
             try {
                // Initialize engine
                val engine = DebugSupertonicEngine(modelDir)

                // Pre-load a default style
                val voicesDir = File(modelDir, "voice_styles")
                val styleFile = File(voicesDir, "F1.json")
                if (styleFile.exists()) {
                     // We need to store this style somewhere or pass it to synthesize.
                     // Since we adapted TTSEngine.synthesize(text, speed), we need to handle style internally or via wrapper.
                     // The DebugSupertonicEngine wrapper should handle loading/caching the default style.
                     // But DebugSupertonicEngine currently delegates to SupertonicEngine which needs style in synthesize.
                     // Let's modify DebugSupertonicEngine to hold a default style.

                     // For now, I'll assume we can pass the default style in the wrapper.
                     // I will update DebugSupertonicEngine to load a default style.
                }

                activeEngine = engine
                DebugLogger.log("TTSManager: Switched to Supertonic (${model.name})")
                return activeEngine
            } catch (e: Exception) {
                DebugLogger.log("TTSManager: Failed to load Supertonic: ${e.message}")
            }
        }

        // Fallback to Kokoro
        try {
            val bundle = OnnxRuntimeManager.initialize(context).getOrNull()
            if (bundle != null) {
                 activeEngine = OnnxRuntimeManager.getEngine()
                 DebugLogger.log("TTSManager: Switched to Kokoro")
                 return activeEngine
            }
        } catch (e: Exception) {
             DebugLogger.log("TTSManager: Failed to load Kokoro: ${e.message}")
        }

        return null
    }

    fun close() {
        activeEngine?.close()
        activeEngine = null
    }
}

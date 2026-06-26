package com.mewmix.nabu.chat

import android.content.Context
import com.mewmix.nabu.data.Model
import com.mewmix.nabu.data.ModelType
import com.mewmix.nabu.data.findDownloadedLlmArtifact
import java.io.File

object ModelCapabilityResolver {
    fun capabilitiesFor(context: Context, model: Model): Set<String> {
        if (model.type == ModelType.TTS) return setOf("tts")
        val artifact = findDownloadedLlmArtifact(File(context.filesDir, "models"), model.id, model.backend)
            ?: return VisionModelSupport.capabilitiesFor(model.id)
        return when (artifact.backend) {
            "litertlm" -> LiteRtLmModelCompatibility.capabilitiesFor(
                context = context,
                modelId = model.id,
                modelPath = artifact.file.absolutePath
            ) ?: VisionModelSupport.capabilitiesFor(model.id)
            "mediapipe" -> buildSet {
                add("completion")
                if (VisionModelSupport.supportsImageInput(model.id)) {
                    add("multimodal")
                    add("image")
                }
            }
            else -> setOf("completion")
        }
    }

    fun supportsImageInput(context: Context, model: Model?): Boolean {
        if (model == null || model.type != ModelType.LLM) return false
        val artifact = findDownloadedLlmArtifact(File(context.filesDir, "models"), model.id, model.backend)
            ?: return false
        return when (artifact.backend) {
            "litertlm" -> LiteRtLmModelCompatibility.cachedResult(
                context = context,
                modelId = model.id,
                modelPath = artifact.file.absolutePath
            )?.supportsUsableVision ?: VisionModelSupport.supportsImageInput(model.id)
            "mediapipe" -> VisionModelSupport.supportsImageInput(model.id)
            else -> false
        }
    }

    fun supportsAudioInput(context: Context, model: Model?): Boolean {
        if (model == null || model.type != ModelType.LLM) return false
        val artifact = findDownloadedLlmArtifact(File(context.filesDir, "models"), model.id, model.backend)
            ?: return false
        return when (artifact.backend) {
            "litertlm" -> LiteRtLmModelCompatibility.cachedResult(
                context = context,
                modelId = model.id,
                modelPath = artifact.file.absolutePath
            )?.supportsAudio ?: VisionModelSupport.supportsAudioInput(model.id)
            else -> false
        }
    }
}

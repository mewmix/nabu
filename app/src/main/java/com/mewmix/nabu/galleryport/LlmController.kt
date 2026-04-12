package com.mewmix.nabu.galleryport

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.*
import com.mewmix.nabu.chat.VisionModelSupport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import java.io.File

class LlmController private constructor(val llm: LlmInference) {

    companion object {
        fun bootstrap(ctx: Context): LlmController? {
            val modelManager = com.mewmix.nabu.data.ModelManager(ctx)
            val model = modelManager.models.firstOrNull {
                it.isDownloaded &&
                    it.type == com.mewmix.nabu.data.ModelType.LLM &&
                    it.backend == "mediapipe" &&
                    VisionModelSupport.supportsImageInput(it.id)
            } ?: return null

            val artifact = com.mewmix.nabu.data.findDownloadedLlmArtifact(
                File(ctx.filesDir, "models"),
                model.id,
                model.backend
            ) ?: return null
            if (artifact.backend != "mediapipe") return null


            val opts = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(artifact.file.absolutePath)
                .setMaxTokens(4096)
                .build()
            return LlmController(LlmInference.createFromOptions(ctx, opts))
        }
    }

    fun stream(prompt: String): Flow<String> = callbackFlow {
        llm.generateResponseAsync(prompt) { partialResult, done ->
            partialResult?.let(::trySend)
            if (done) {
                close()
            }
        }
        awaitClose { }
    }
}

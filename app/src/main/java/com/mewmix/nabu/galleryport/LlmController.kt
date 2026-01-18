package com.mewmix.nabu.galleryport

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import java.io.File

class LlmController private constructor(val llm: LlmInference) {

    companion object {
        private const val CHAT_MODEL_ID = "gemma-2b-it-cpu"
        fun bootstrap(ctx: Context): LlmController? {
            val modelManager = com.mewmix.nabu.data.ModelManager(ctx)
            val model = modelManager.getModel(CHAT_MODEL_ID)

            if (model == null || !model.isDownloaded) {
                return null
            }

            val modelFile = File(ctx.filesDir, "models/${model.id}.task")
            if (!modelFile.exists()) return null


            val opts = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelFile.absolutePath)
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

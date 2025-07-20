package com.example.kokoro.galleryport

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.nio.MappedByteBuffer

class LlmController private constructor(val llm: LlmInference) {

    companion object {
        suspend fun bootstrap(ctx: Context): LlmController {
            val f = ModelHub.get(
                ctx,
                "https://huggingface.co/google/gemma-3n-E2B-it-litert-lm-preview/resolve/main/gemma-3n-E2B-it-int4.litertlm",
                "gemma3n.litertlm"
            )
            val opts = LlmInferenceOptions.builder()
                .setTaskBundle(f.asMappedByteBuffer())
                .setMaxContextLength(4096)
                .setPreferredBackend(LlmInference.Backend.CPU)
                .build()
            return LlmController(LlmInference.createFromOptions(ctx, opts))
        }
    }

    fun stream(prompt: String): Flow<String> = callbackFlow {
        llm.generateResponse(prompt) { token ->
            trySend(token.text)
            false
        }
        close()
    }
}

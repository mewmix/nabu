package com.example.kokoro.galleryport

import android.content.Context
import kotlinx.coroutines.flow.Flow

object PromptLabPipeline {
    suspend fun run(ctx: Context, prompt: String): Flow<String> =
        LlmController.bootstrap(ctx).stream(prompt)
}

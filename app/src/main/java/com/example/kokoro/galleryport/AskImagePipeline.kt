package com.example.kokoro.galleryport

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.tasks.vision.core.functional.BitmapImageBuilder
import com.google.mediapipe.tasks.genai.llminference.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

object AskImagePipeline {
    suspend fun run(ctx: Context, bitmap: Bitmap, q: String): Flow<String> = callbackFlow {
        val ctrl = LlmController.bootstrap(ctx)
        val sess = LlmInferenceSession.createFromOptions(
            ctrl.llm,
            LlmInferenceSession.LlmInferenceSessionOptions.builder()
                .setGraphOptions(GraphOptions.builder().setEnableVisionModality(true).build())
                .build()
        )
        sess.addImage(BitmapImageBuilder(bitmap).build())
        sess.addQueryChunk(q)
        sess.generateResponseAsync { res, done ->
            trySend(res.partialText); done
        }
    }
}

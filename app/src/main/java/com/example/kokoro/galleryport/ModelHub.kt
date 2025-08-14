package com.example.kokoro.galleryport

import android.content.Context
import com.example.nabu.data.ModelManager
import java.io.File

object ModelHub {
    suspend fun get(ctx: Context, modelId: String): File? {
        val modelManager = ModelManager(ctx)
        val model = modelManager.getModel(modelId) ?: return null
        if (!model.isDownloaded) return null
        val modelFile = File(ctx.filesDir, "models/${model.id}.task")
        return if (modelFile.exists()) modelFile else null
    }
}

package com.mewmix.nabu.galleryport

import android.content.Context
import com.mewmix.nabu.data.ModelManager
import com.mewmix.nabu.data.findDownloadedLlmArtifact
import java.io.File

object ModelHub {
    suspend fun get(ctx: Context, modelId: String): File? {
        val modelManager = ModelManager(ctx)
        val model = modelManager.getModel(modelId) ?: return null
        if (!model.isDownloaded) return null
        val artifact = findDownloadedLlmArtifact(File(ctx.filesDir, "models"), model.id, model.backend)
            ?: return null
        return if (artifact.backend == "mediapipe") artifact.file else null
    }
}

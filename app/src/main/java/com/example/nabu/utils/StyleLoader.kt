package com.example.nabu.utils

import android.content.Context
import org.jetbrains.bio.npy.NpyArray
import org.jetbrains.bio.npy.NpyFile
import java.io.File
import java.io.FileOutputStream
import com.example.nabu.data.ModelManager

class StyleLoader(private val context: Context) {
    val names: List<String>
        get() {
            val engine = SettingsManager.getTtsEngine(context)
            if (engine == "supertonic") {
                // Find active Supertonic model
                // This is a bit duplicative of TTSManager logic, but acceptable for now.
                // Ideally we'd inject this path.
                val modelManager = ModelManager(context)
                val ttsModels = modelManager.models.filter { it.type == com.example.nabu.data.ModelType.TTS && it.isDownloaded }
                if (ttsModels.isNotEmpty()) {
                    val model = ttsModels.first()
                    val voicesDir = File(context.filesDir, "models/${model.id}/voice_styles")
                    if (voicesDir.exists()) {
                        return voicesDir.listFiles { _, name -> name.endsWith(".json") }
                            ?.map { it.name.removeSuffix(".json") }
                            ?.sorted()
                            ?: emptyList()
                    }
                }
                return emptyList()
            } else {
                return context.assets
                    .list("kokoro/voices")
                    ?.map { it.removeSuffix(".npy") }
                    ?.sorted()
                    ?: emptyList()
            }
        }

    fun getStyleArray(name: String, index: Int = 0): Array<FloatArray> {
        val inputStream = context.assets.open("kokoro/voices/$name.npy")
        val tempFile = File.createTempFile("temp_style", ".npy", context.cacheDir)
        tempFile.deleteOnExit()
        FileOutputStream(tempFile).use { outputStream ->
            inputStream.copyTo(outputStream)
        }

        val npyArray: NpyArray = NpyFile.read(tempFile.toPath())
        val shape = npyArray.shape

        if (shape.isEmpty() || shape.last() != 256) {
            throw IllegalArgumentException("The loaded .npy file must have a shape compatible with (*, ..., 256)")
        }

        val frameCount = shape[0]
        if (index < 0 || index >= frameCount) {
            throw IllegalArgumentException("Index must be between 0 and ${'$'}{frameCount - 1}")
        }

        val blockSize = shape.copyOfRange(1, shape.size).fold(1) { acc, dim -> acc * dim }
        val floatArray = npyArray.asFloatArray()
        val styleArray = Array(1) { FloatArray(256) }
        val offset = index * blockSize

        for (i in 0 until 256) {
            styleArray[0][i] = floatArray[offset + i]
        }

        return styleArray
    }
}

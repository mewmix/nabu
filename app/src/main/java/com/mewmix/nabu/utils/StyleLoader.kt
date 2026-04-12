package com.mewmix.nabu.utils

import android.content.Context
import org.jetbrains.bio.npy.NpyArray
import org.jetbrains.bio.npy.NpyFile
import java.io.File
import java.io.FileOutputStream
import com.mewmix.nabu.data.ModelManager

class StyleLoader(private val context: Context) {
    private var cachedNames: List<String>? = null
    private var lastEngine: String? = null
    private var lastModelId: String? = null

    val names: List<String>
        get() = getNames(null)

    fun getNames(providedEngine: String? = null): List<String> {
        val engine = providedEngine ?: SettingsManager.getTtsEngine(context)
        val modelId = if (engine == "supertonic") SettingsManager.getSupertonicModelId(context) else null
        
        if (cachedNames != null && lastEngine == engine && lastModelId == modelId) {
            return cachedNames!!
        }

        val result = if (engine == "supertonic") {
            val modelManager = ModelManager(context)
            val ttsModels = modelManager.models.filter { it.type == com.mewmix.nabu.data.ModelType.TTS && it.isDownloaded }
            if (ttsModels.isNotEmpty()) {
                val preferredModelId = SettingsManager.getSupertonicModelId(context)
                val model = if (preferredModelId != null) {
                    ttsModels.firstOrNull { it.id == preferredModelId }
                } else {
                    ttsModels.first()
                }
                if (model == null) {
                    emptyList()
                } else {
                    val voicesDir = File(context.filesDir, "models/${model.id}/voice_styles")
                    if (voicesDir.exists()) {
                        voicesDir.listFiles { _, name -> name.endsWith(".json") }
                            ?.map { it.name.removeSuffix(".json") }
                            ?.sorted()
                            ?: emptyList()
                    } else {
                        emptyList()
                    }
                }
            } else {
                emptyList()
            }
        } else if (engine == "kokoro") {
            context.assets
                .list("kokoro/voices")
                ?.map { it.removeSuffix(".npy") }
                ?.sorted()
                ?: emptyList()
        } else {
            emptyList()
        }
        
        cachedNames = result
        lastEngine = engine
        lastModelId = modelId
        return result
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

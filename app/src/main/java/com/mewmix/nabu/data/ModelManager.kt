package com.mewmix.nabu.data

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.mewmix.nabu.R
import org.json.JSONObject
import java.io.File

/**
 * Manages available LLM models for the application.
 *
 * Models from the allowlist are loaded on start and any `.task` files
 * already present in the models directory are treated as user supplied
 * models. Additional models can be added at runtime by calling
 * [addLocalModel].
 */
class ModelManager(private val context: Context) {

    private val _models = mutableStateListOf<Model>()
    val models: SnapshotStateList<Model> get() = _models

    init {
        _models.addAll(loadModels())
    }

    /**
     * Loads models from the bundled allowlist and any pre-existing local
     * `.task` files the user may have added.
     */
    private fun loadModels(): List<Model> {
        val modelList = mutableListOf<Model>()
        val jsonString =
            context.resources.openRawResource(R.raw.model_allowlist).bufferedReader().use { it.readText() }
        val json = JSONObject(jsonString)
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val modelJson = json.getJSONObject(key)
            val typeStr = modelJson.optString("type", "LLM")
            val type = try {
                ModelType.valueOf(typeStr)
            } catch (e: Exception) {
                ModelType.LLM
            }

            val model = Model(
                id = key,
                name = modelJson.getString("name"),
                description = modelJson.getString("description"),
                repo = modelJson.getString("repo"),
                downloadUrl = modelJson.getString("downloadUrl"),
                gated = modelJson.optBoolean("gated", false),
                type = type
            )
            val modelDir = File(context.filesDir, "models")

            if (model.type == ModelType.TTS) {
                val ttsDir = File(modelDir, model.id)
                val downloaded = TtsModelValidator.hasAllRequiredFiles(model.id, ttsDir)

                model.isDownloaded = downloaded
                // Partial check for TTS is simplified or we can check for a temp folder
                val partialDir = File(modelDir, "${model.id}_partial")
                model.hasPartial = !model.isDownloaded && (partialDir.exists() || ttsDir.exists())
            } else {
                val taskFile = File(modelDir, "${model.id}.task")
                val ggufFile = File(modelDir, "${model.id}.gguf")
                val taskValid = taskFile.exists() && taskFile.isFile && taskFile.length() > 0L
                val ggufValid = ggufFile.exists() && ggufFile.isFile && ggufFile.length() > 0L

                if (taskValid) {
                    model.isDownloaded = true
                    model.backend = "mediapipe"
                } else if (ggufValid) {
                    model.isDownloaded = true
                    model.backend = "llama"
                } else {
                    model.isDownloaded = false
                    val taskPart = File(modelDir, "${model.id}.task.part")
                    val ggufPart = File(modelDir, "${model.id}.gguf.part")
                    model.hasPartial = taskPart.exists() || ggufPart.exists() || taskFile.exists() || ggufFile.exists()

                    val jsonBackend = modelJson.optString("backend", "mediapipe")
                    model.backend = jsonBackend
                }
            }
            modelList.add(model)
        }

        // Include any additional models already placed in the models directory
        val modelDir = File(context.filesDir, "models")
        modelDir.listFiles { _, name -> name.endsWith(".task") || name.endsWith(".gguf") }?.forEach { file ->
            if (!file.isFile || file.length() <= 0L) return@forEach
            val id = file.nameWithoutExtension
            if (modelList.none { it.id == id }) {
                val backend = if (file.name.endsWith(".gguf")) "llama" else "mediapipe"
                modelList.add(
                    Model(
                        id = id,
                        name = id,
                        description = "Local model ($backend)",
                        repo = "",
                        downloadUrl = "",
                        gated = false,
                        isDownloaded = true,
                        backend = backend
                    )
                )
            }
        }

        return modelList
    }

    /** Add a model supplied by the user at runtime. */
    fun addLocalModel(model: Model) {
        _models.add(model)
    }

    fun getModel(id: String): Model? {
        return _models.find { it.id == id }
    }

    fun deleteModel(model: Model) {
        val modelDir = File(context.filesDir, "models")
        if (model.type == ModelType.TTS) {
            File(modelDir, model.id).deleteRecursively()
            File(modelDir, "${model.id}_partial").deleteRecursively()
        } else {
            File(modelDir, "${model.id}.task").delete()
            File(modelDir, "${model.id}.task.part").delete()
            File(modelDir, "${model.id}.gguf").delete()
            File(modelDir, "${model.id}.gguf.part").delete()
        }

        if (model.repo.isEmpty() && model.downloadUrl.isEmpty()) {
            _models.remove(model)
        } else {
            model.isDownloaded = false
            model.hasPartial = false
        }
    }
}

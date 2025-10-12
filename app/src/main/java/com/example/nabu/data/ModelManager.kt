package com.example.nabu.data

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.example.nabu.R
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
        if (_models.none { it.id == "chatterbox-en" }) {
            _models.add(
                Model(
                    id = "chatterbox-en",
                    name = "Chatterbox (English, ONNX)",
                    description = "English Chatterbox-ONNX speech synthesis pack",
                    repo = "onnx-community/chatterbox-onnx",
                    downloadUrl = "https://huggingface.co/onnx-community/chatterbox-onnx",
                    gated = false,
                    isDownloaded = com.example.nabu.tts.chatterbox.ChatterboxInstaller.isInstalled(context),
                    hasPartial = false,
                )
            )
        }
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
            val model = Model(
                id = key,
                name = modelJson.getString("name"),
                description = modelJson.getString("description"),
                repo = modelJson.getString("repo"),
                downloadUrl = modelJson.getString("downloadUrl"),
                gated = modelJson.optBoolean("gated", false),
            )
            if (model.id == "chatterbox-en") {
                model.isDownloaded = com.example.nabu.tts.chatterbox.ChatterboxInstaller.isInstalled(context)
                model.hasPartial = false
            } else {
                val modelDir = File(context.filesDir, "models")
                val modelFile = File(modelDir, "${model.id}.task")
                val partialFile = File(modelDir, "${model.id}.task.part")
                model.isDownloaded = modelFile.exists()
                model.hasPartial = !model.isDownloaded && partialFile.exists()
            }
            modelList.add(model)
        }

        // Include any additional models already placed in the models directory
        val modelDir = File(context.filesDir, "models")
        modelDir.listFiles { _, name -> name.endsWith(".task") }?.forEach { file ->
            val id = file.nameWithoutExtension
            if (modelList.none { it.id == id }) {
                modelList.add(
                    Model(
                        id = id,
                        name = id,
                        description = "Local model",
                        repo = "",
                        downloadUrl = "",
                        gated = false,
                        isDownloaded = true,
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
        if (model.id == "chatterbox-en") {
            val dir = com.example.nabu.tts.NabuPaths.chatterboxModelDir(context)
            if (dir.exists()) {
                dir.walkBottomUp().forEach { file ->
                    if (file != dir) file.delete()
                }
                dir.delete()
            }
            model.isDownloaded = false
            model.hasPartial = false
            return
        }
        val modelDir = File(context.filesDir, "models")
        File(modelDir, "${model.id}.task").delete()
        File(modelDir, "${model.id}.task.part").delete()
        if (model.repo.isEmpty() && model.downloadUrl.isEmpty()) {
            _models.remove(model)
        } else {
            model.isDownloaded = false
            model.hasPartial = false
        }
    }
}

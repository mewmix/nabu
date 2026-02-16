package com.mewmix.nabu.data

import java.io.File

object TtsModelValidator {
    private const val SOPRANO_MODEL_ID = "soprano-80m-onnx"

    private val sopranoMinBytes = mapOf(
        "soprano_backbone_kv.onnx" to 200_000_000L,
        "soprano_decoder.onnx" to 100_000L,
        "soprano_decoder.onnx.data" to 20_000_000L,
        "tokenizer.json" to 100_000L
    )

    fun requiredFiles(modelId: String): List<String> {
        return when (modelId) {
            SOPRANO_MODEL_ID -> listOf(
                "soprano_backbone_kv.onnx",
                "soprano_decoder.onnx",
                "soprano_decoder.onnx.data",
                "tokenizer.json"
            )
            else -> emptyList()
        }
    }

    fun missingFiles(modelId: String, primaryDir: File, secondaryDir: File? = null): List<String> {
        val required = requiredFiles(modelId)
        if (required.isEmpty()) return emptyList()
        return required.filter { localPath ->
            val primaryFile = File(primaryDir, localPath)
            val primaryValid = isFileValid(modelId, localPath, primaryFile)
            if (primaryValid) {
                false
            } else {
                val secondaryValid = secondaryDir?.let { dir ->
                    isFileValid(modelId, localPath, File(dir, localPath))
                } ?: false
                !secondaryValid
            }
        }
    }

    fun hasAllRequiredFiles(modelId: String, rootDir: File): Boolean {
        if (!rootDir.exists() || !rootDir.isDirectory) return false
        val required = requiredFiles(modelId)
        if (required.isEmpty()) {
            return rootDir.list()?.isNotEmpty() == true
        }
        return required.all { localPath ->
            isFileValid(modelId, localPath, File(rootDir, localPath))
        }
    }

    fun isFileValid(modelId: String, localPath: String, file: File): Boolean {
        if (!file.exists() || !file.isFile) return false
        val size = file.length()
        if (size <= 0L) return false

        val minBytes = when (modelId) {
            SOPRANO_MODEL_ID -> sopranoMinBytes[localPath]
            else -> null
        }
        return minBytes?.let { size >= it } ?: true
    }
}

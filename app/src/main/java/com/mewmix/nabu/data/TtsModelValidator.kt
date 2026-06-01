package com.mewmix.nabu.data

import java.io.File

object TtsModelValidator {
    private const val SOPRANO_MODEL_ID = "soprano-80m-onnx"
    private const val SUPERTONIC2_MODEL_ID = "supertonic-2-onnx"
    private const val SUPERTONIC3_MODEL_ID = "supertonic-3-onnx"

    private val supertonicBaseFiles = listOf(
        "duration_predictor.onnx",
        "text_encoder.onnx",
        "vector_estimator.onnx",
        "vocoder.onnx",
        "tts.json",
        "unicode_indexer.json"
    )

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
            SUPERTONIC2_MODEL_ID, SUPERTONIC3_MODEL_ID -> supertonicBaseFiles + listOf(
                "voice_styles/F1.json",
                "voice_styles/F2.json",
                "voice_styles/F3.json",
                "voice_styles/F4.json",
                "voice_styles/F5.json",
                "voice_styles/M1.json",
                "voice_styles/M2.json",
                "voice_styles/M3.json",
                "voice_styles/M4.json",
                "voice_styles/M5.json"
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
            SUPERTONIC2_MODEL_ID, SUPERTONIC3_MODEL_ID -> when {
                localPath in supertonicBaseFiles -> 1_000L
                localPath.startsWith("voice_styles/") -> 100L
                else -> null
            }
            else -> null
        }
        return minBytes?.let { size >= it } ?: true
    }
}

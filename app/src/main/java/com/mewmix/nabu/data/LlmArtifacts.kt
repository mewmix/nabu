package com.mewmix.nabu.data

import java.io.File

private val mediaPipeExtensions = listOf("task")
private val liteRtLmExtensions = listOf("litertlm")
private val llamaExtensions = listOf("gguf")
private val allLlmExtensions = mediaPipeExtensions + liteRtLmExtensions + llamaExtensions

data class LlmArtifact(
    val file: File,
    val backend: String,
    val extension: String
)

fun llmExtensionFromDownloadUrl(downloadUrl: String): String {
    val normalized = downloadUrl.lowercase()
    return when {
        ".gguf" in normalized -> "gguf"
        ".litertlm" in normalized -> "litertlm"
        else -> "task"
    }
}

fun findDownloadedLlmArtifact(modelDir: File, modelId: String, backendHint: String? = null): LlmArtifact? {
    val preferredExtensions = when (backendHint) {
        "llama" -> llamaExtensions
        "litertlm" -> liteRtLmExtensions
        "mediapipe" -> mediaPipeExtensions
        else -> mediaPipeExtensions + liteRtLmExtensions + llamaExtensions
    }
    val orderedExtensions = preferredExtensions + allLlmExtensions.filterNot(preferredExtensions::contains)
    return orderedExtensions.firstNotNullOfOrNull { extension ->
        val file = File(modelDir, "$modelId.$extension")
        if (file.exists() && file.isFile && file.length() > 0L) {
            LlmArtifact(file = file, backend = backendForLlmExtension(extension), extension = extension)
        } else {
            null
        }
    }
}

fun hasPartialLlmArtifacts(modelDir: File, modelId: String): Boolean {
    return allLlmExtensions.any { extension ->
        val finalFile = File(modelDir, "$modelId.$extension")
        val partFile = File(modelDir, "$modelId.$extension.part")
        partFile.exists() || (finalFile.exists() && finalFile.length() <= 0L)
    }
}

fun importableLlmMetadata(fileName: String): Pair<String, String>? {
    val normalized = fileName.lowercase()
    return when {
        normalized.endsWith(".gguf") -> stripKnownSuffix(fileName, ".gguf") to "llama"
        normalized.endsWith(".litertlm") -> stripKnownSuffix(fileName, ".litertlm") to "litertlm"
        normalized.endsWith(".task") -> stripKnownSuffix(fileName, ".task") to "mediapipe"
        else -> null
    }
}

fun deleteLlmArtifacts(modelDir: File, modelId: String) {
    allLlmExtensions.forEach { extension ->
        File(modelDir, "$modelId.$extension").delete()
        File(modelDir, "$modelId.$extension.part").delete()
    }
}

fun backendForLlmExtension(extension: String): String =
    when (extension) {
        "gguf" -> "llama"
        "litertlm" -> "litertlm"
        else -> "mediapipe"
    }

fun backendForLlmDownloadUrl(downloadUrl: String): String =
    backendForLlmExtension(llmExtensionFromDownloadUrl(downloadUrl))

private fun stripKnownSuffix(fileName: String, suffix: String): String =
    fileName.substring(0, fileName.length - suffix.length)

package com.mewmix.nabu.data

import com.mewmix.nabu.kokoro.Downloader
import com.mewmix.nabu.kokoro.ManifestProvider
import com.mewmix.nabu.utils.DebugLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

class ModelDownloader(
    private val context: android.content.Context,
    private val userPreferencesRepository: UserPreferencesRepository,
) {
    companion object {
        const val KOKORO_MODEL_ID = "kokoro-default"
        private val activeModelDownloads = mutableSetOf<String>()
        private val sharedProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
        private val sharedDetailedProgress = MutableStateFlow<Map<String, DetailedProgress>>(emptyMap())
    }

    data class DetailedProgress(
        val currentFile: String,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val fraction: Float
    )

    private data class DownloadFileSpec(
        val remotePath: String,
        val localPath: String
    )

    private val scope = CoroutineScope(Dispatchers.IO)
    val progress: StateFlow<Map<String, Float>> = sharedProgress.asStateFlow()
    val detailedProgress: StateFlow<Map<String, DetailedProgress>> = sharedDetailedProgress.asStateFlow()

    private fun tryAcquireDownload(modelId: String): Boolean =
        synchronized(activeModelDownloads) { activeModelDownloads.add(modelId) }

    private fun releaseDownload(modelId: String) {
        synchronized(activeModelDownloads) { activeModelDownloads.remove(modelId) }
    }

    private fun isDownloadActive(modelId: String): Boolean =
        synchronized(activeModelDownloads) { activeModelDownloads.contains(modelId) }

    private suspend fun waitForActiveDownload(modelId: String) {
        while (isDownloadActive(modelId)) {
            delay(200L)
        }
    }

    suspend fun ensureKokoroDefaultDownloaded(): Boolean {
        if (!tryAcquireDownload(KOKORO_MODEL_ID)) {
            waitForActiveDownload(KOKORO_MODEL_ID)
            return Downloader.modelsAvailable(context.applicationContext, ManifestProvider.kokoroV1())
        }
        return try {
            downloadKokoro()
        } finally {
            releaseDownload(KOKORO_MODEL_ID)
        }
    }

    suspend fun ensureModelDownloaded(model: Model): Boolean {
        if (!tryAcquireDownload(model.id)) {
            waitForActiveDownload(model.id)
            return isModelDownloadedOnDisk(model)
        }
        return try {
            if (model.type == ModelType.TTS) {
                downloadTtsModel(model)
            } else {
                downloadLlmModel(model)
            }
            isModelDownloadedOnDisk(model)
        } finally {
            releaseDownload(model.id)
        }
    }

    fun downloadKokoroDefault() {
        scope.launch {
            ensureKokoroDefaultDownloaded()
        }
    }

    fun downloadModel(model: Model) {
        scope.launch {
            ensureModelDownloaded(model)
        }
    }

    private suspend fun downloadKokoro(): Boolean {
        val appContext = context.applicationContext
        val manifest = ManifestProvider.kokoroV1()

        updateProgress(
            modelId = KOKORO_MODEL_ID,
            currentFile = "kokoro",
            downloadedBytes = 0L,
            totalBytes = manifest.files.sumOf { it.sizeBytes },
            fraction = 0f
        )
        DebugLogger.log("ModelDownloader: Starting download of Kokoro")
        try {
            val result = Downloader.ensureModels(appContext, manifest) { current ->
                val fraction = if (current.totalBytes > 0L) {
                    current.downloadedBytes.toFloat() / current.totalBytes.toFloat()
                } else {
                    0f
                }
                updateProgress(
                    modelId = KOKORO_MODEL_ID,
                    currentFile = current.fileId,
                    downloadedBytes = current.downloadedBytes,
                    totalBytes = current.totalBytes,
                    fraction = fraction
                )
            }
            result.getOrThrow()
            DebugLogger.log("ModelDownloader: Kokoro models verified and ready")
            return true
        } catch (e: Exception) {
            DebugLogger.log("ModelDownloader: Error downloading Kokoro: ${e.message}")
            return false
        } finally {
            clearProgress(KOKORO_MODEL_ID)
        }
    }

    private suspend fun downloadTtsModel(model: Model) {
        val modelDir = File(context.filesDir, "models")
        if (!modelDir.exists()) modelDir.mkdirs()

        val targetDir = File(modelDir, model.id)
        val partialDir = File(modelDir, "${model.id}_partial")
        val fileSpecs = ttsFileSpecs(model)
        val token = userPreferencesRepository.hfToken.first()?.trim()?.ifBlank { null }
        val headers = authHeaders(token)
        val baseUrl = ttsBaseUrl(model)

        val targetComplete = hasAllFiles(model.id, targetDir, fileSpecs)
        if (targetComplete) {
            model.isDownloaded = true
            model.hasPartial = false
            DebugLogger.log("ModelDownloader: ${model.name} already downloaded")
            return
        }

        val partialComplete = hasAllFiles(model.id, partialDir, fileSpecs)
        if (partialComplete) {
            if (targetDir.exists()) targetDir.deleteRecursively()
            if (!partialDir.renameTo(targetDir)) {
                partialDir.copyRecursively(targetDir, overwrite = true)
                partialDir.deleteRecursively()
            }
            model.isDownloaded = hasAllFiles(model.id, targetDir, fileSpecs)
            model.hasPartial = false
            if (model.isDownloaded) {
                DebugLogger.log("ModelDownloader: Promoted complete partial bundle for ${model.name}")
                return
            }
        }

        val destinationRoot = when {
            targetDir.exists() && partialDir.exists() -> {
                if (countValidFiles(model.id, partialDir, fileSpecs) > countValidFiles(model.id, targetDir, fileSpecs)) {
                    partialDir
                } else {
                    targetDir
                }
            }
            targetDir.exists() -> targetDir
            partialDir.exists() -> partialDir
            else -> {
                partialDir.mkdirs()
                partialDir
            }
        }
        val fallbackRoot = if (destinationRoot == targetDir) partialDir else targetDir
        if (fallbackRoot.exists()) {
            fileSpecs.forEach { spec ->
                val destFile = File(destinationRoot, spec.localPath)
                if (!TtsModelValidator.isFileValid(model.id, spec.localPath, destFile)) {
                    val fallbackFile = File(fallbackRoot, spec.localPath)
                    if (TtsModelValidator.isFileValid(model.id, spec.localPath, fallbackFile)) {
                        destFile.parentFile?.mkdirs()
                        fallbackFile.copyTo(destFile, overwrite = true)
                    }
                }
            }
        }
        val missingSpecs = fileSpecs.filter { spec ->
            !TtsModelValidator.isFileValid(model.id, spec.localPath, File(destinationRoot, spec.localPath))
        }

        if (missingSpecs.isEmpty()) {
            // If all files are valid in partial dir, promote it to final destination.
            if (destinationRoot == partialDir) {
                if (targetDir.exists()) targetDir.deleteRecursively()
                if (!partialDir.renameTo(targetDir)) {
                    partialDir.copyRecursively(targetDir, overwrite = true)
                    partialDir.deleteRecursively()
                }
            }
            model.isDownloaded = hasAllFiles(model.id, targetDir, fileSpecs)
            model.hasPartial = false
            return
        }

        model.hasPartial = true
        DebugLogger.log("ModelDownloader: Starting download of ${model.name} (TTS)")

        try {
            val totalFiles = missingSpecs.size.coerceAtLeast(1)
            missingSpecs.forEachIndexed { index, spec ->
                val fileUrl = buildFileUrl(baseUrl, spec.remotePath)
                val destFile = File(destinationRoot, spec.localPath)
                val partFile = File(destinationRoot, "${spec.localPath}.part")
                val displayName = spec.localPath.substringAfterLast('/')
                val baseFraction = index.toFloat() / totalFiles.toFloat()

                destFile.parentFile?.mkdirs()
                partFile.parentFile?.mkdirs()
                if (destFile.exists() && !TtsModelValidator.isFileValid(model.id, spec.localPath, destFile)) {
                    destFile.delete()
                }
                if (destFile.exists() && !partFile.exists()) {
                    // Resume into .part to avoid treating interrupted final files as complete.
                    destFile.renameTo(partFile)
                }

                updateProgress(
                    modelId = model.id,
                    currentFile = displayName,
                    downloadedBytes = 0L,
                    totalBytes = -1L,
                    fraction = baseFraction
                )

                DebugLogger.log("ModelDownloader: Downloading ${model.id}/${spec.localPath}")
                downloadWithRetry(
                    fileLabel = "${model.id}/${spec.localPath}",
                    url = fileUrl,
                    target = partFile,
                    headers = headers
                ) { downloadedBytes, totalBytes ->
                    val fileFraction = if (totalBytes > 0L) {
                        downloadedBytes.toFloat() / totalBytes.toFloat()
                    } else {
                        0f
                    }
                    val overall = (index.toFloat() + fileFraction) / totalFiles.toFloat()
                    updateProgress(
                        modelId = model.id,
                        currentFile = displayName,
                        downloadedBytes = downloadedBytes,
                        totalBytes = totalBytes,
                        fraction = overall
                    )
                }

                if (!TtsModelValidator.isFileValid(model.id, spec.localPath, partFile)) {
                    throw IllegalStateException(
                        "Downloaded file is invalid/incomplete: ${spec.localPath} (${partFile.length()} bytes)"
                    )
                }
                if (destFile.exists()) destFile.delete()
                if (!partFile.renameTo(destFile)) {
                    partFile.copyTo(destFile, overwrite = true)
                    partFile.delete()
                }
                DebugLogger.log("ModelDownloader: Completed ${model.id}/${spec.localPath} (${destFile.length()} bytes)")

                val destSize = destFile.length().coerceAtLeast(1L)
                updateProgress(
                    modelId = model.id,
                    currentFile = displayName,
                    downloadedBytes = destSize,
                    totalBytes = destSize,
                    fraction = (index + 1).toFloat() / totalFiles.toFloat()
                )
            }

            if (destinationRoot == partialDir) {
                if (targetDir.exists()) targetDir.deleteRecursively()
                if (!partialDir.renameTo(targetDir)) {
                    partialDir.copyRecursively(targetDir, overwrite = true)
                    partialDir.deleteRecursively()
                }
            }

            model.isDownloaded = hasAllFiles(model.id, targetDir, fileSpecs)
            model.hasPartial = !model.isDownloaded && (partialDir.exists() || targetDir.exists())
            DebugLogger.log("ModelDownloader: Download of ${model.name} completed")
        } catch (e: Exception) {
            model.isDownloaded = hasAllFiles(model.id, targetDir, fileSpecs)
            model.hasPartial = !model.isDownloaded && (partialDir.exists() || targetDir.exists())
            DebugLogger.log("ModelDownloader: Error downloading ${model.name}: ${e.message}")
        } finally {
            clearProgress(model.id)
        }
    }

    private suspend fun downloadLlmModel(model: Model) {
        if (model.downloadUrl.isBlank()) {
            DebugLogger.log("ModelDownloader: Skipping LLM download for ${model.id}; no download URL")
            model.isDownloaded = false
            model.hasPartial = false
            return
        }
        val token = userPreferencesRepository.hfToken.first()?.trim()?.ifBlank { null }
        val headers = authHeaders(token)

        val modelDir = File(context.filesDir, "models")
        if (!modelDir.exists()) modelDir.mkdirs()

        val extension = llmExtension(model)
        val finalFile = File(modelDir, "${model.id}.$extension")
        val tempFile = File(modelDir, "${model.id}.$extension.part")

        if (finalFile.exists() && finalFile.length() <= 0L) {
            DebugLogger.log("ModelDownloader: Removing invalid empty artifact for ${model.id}")
            finalFile.delete()
        }
        if (finalFile.exists() && isLlmArtifactValid(finalFile)) {
            model.isDownloaded = true
            model.hasPartial = false
            if (tempFile.exists()) {
                tempFile.delete()
            }
            DebugLogger.log("ModelDownloader: ${model.name} already downloaded")
            return
        }

        model.hasPartial = tempFile.exists() || finalFile.exists()
        DebugLogger.log("ModelDownloader: Starting download of ${model.name}")

        try {
            downloadWithRetry(
                fileLabel = "${model.id}.${extension}",
                url = model.downloadUrl,
                target = tempFile,
                headers = headers
            ) { downloadedBytes, totalBytes ->
                val fraction = if (totalBytes > 0L) {
                    downloadedBytes.toFloat() / totalBytes.toFloat()
                } else {
                    0f
                }
                updateProgress(
                    modelId = model.id,
                    currentFile = tempFile.name,
                    downloadedBytes = downloadedBytes,
                    totalBytes = totalBytes,
                    fraction = fraction
                )
            }

            if (!isLlmArtifactValid(tempFile)) {
                throw IllegalStateException("Downloaded artifact is empty or invalid: ${tempFile.name}")
            }

            if (finalFile.exists()) finalFile.delete()
            if (!tempFile.renameTo(finalFile)) {
                tempFile.copyTo(finalFile, overwrite = true)
                tempFile.delete()
            }

            model.isDownloaded = isLlmArtifactValid(finalFile)
            model.hasPartial = false
            DebugLogger.log("ModelDownloader: Download of ${model.name} completed")
        } catch (e: Exception) {
            model.isDownloaded = isLlmArtifactValid(finalFile)
            model.hasPartial = !model.isDownloaded && (tempFile.exists() || finalFile.exists())
            DebugLogger.log("ModelDownloader: Error downloading ${model.name}: ${e.message}")
        } finally {
            clearProgress(model.id)
        }
    }

    private fun llmExtension(model: Model): String = llmExtensionFromDownloadUrl(model.downloadUrl)

    private fun isLlmArtifactValid(file: File): Boolean = file.exists() && file.isFile && file.length() > 0L

    private fun isModelDownloadedOnDisk(model: Model): Boolean {
        val modelDir = File(context.filesDir, "models")
        return if (model.type == ModelType.TTS) {
            val targetDir = File(modelDir, model.id)
            val partialDir = File(modelDir, "${model.id}_partial")
            val specs = ttsFileSpecs(model)
            if (hasAllFiles(model.id, targetDir, specs)) {
                true
            } else if (hasAllFiles(model.id, partialDir, specs)) {
                if (targetDir.exists()) targetDir.deleteRecursively()
                if (!partialDir.renameTo(targetDir)) {
                    partialDir.copyRecursively(targetDir, overwrite = true)
                    partialDir.deleteRecursively()
                }
                hasAllFiles(model.id, targetDir, specs) || hasAllFiles(model.id, partialDir, specs)
            } else {
                false
            }
        } else {
            findDownloadedLlmArtifact(modelDir, model.id, model.backend) != null
        }
    }

    private fun ttsFileSpecs(model: Model): List<DownloadFileSpec> {
        if (model.id == "soprano-80m-onnx") {
            return listOf(
                DownloadFileSpec("onnx/soprano_backbone_kv.onnx", "soprano_backbone_kv.onnx"),
                DownloadFileSpec("onnx/soprano_decoder.onnx", "soprano_decoder.onnx"),
                DownloadFileSpec("onnx/soprano_decoder.onnx.data", "soprano_decoder.onnx.data"),
                DownloadFileSpec("tokenizer.json", "tokenizer.json")
            )
        }

        val onnxFiles = listOf(
            "duration_predictor.onnx",
            "text_encoder.onnx",
            "vector_estimator.onnx",
            "vocoder.onnx",
            "tts.json",
            "unicode_indexer.json"
        )
        val voiceStyles = when (model.id) {
            "supertonic-2-onnx", "supertonic-3-onnx" -> listOf(
                "F1.json", "F2.json", "F3.json", "F4.json", "F5.json",
                "M1.json", "M2.json", "M3.json", "M4.json", "M5.json"
            )
            else -> listOf("F1.json", "F2.json", "M1.json", "M2.json")
        }

        return onnxFiles.map { name ->
            DownloadFileSpec(remotePath = "onnx/$name", localPath = name)
        } + voiceStyles.map { name ->
            DownloadFileSpec(remotePath = "voice_styles/$name", localPath = "voice_styles/$name")
        }
    }

    private fun ttsBaseUrl(model: Model): String {
        val raw = model.downloadUrl
        return if (model.id == "soprano-80m-onnx") {
            raw.removeSuffix("/") + "/"
        } else {
            raw.removeSuffix("onnx/").removeSuffix("/") + "/"
        }
    }

    private fun buildFileUrl(baseUrl: String, remotePath: String): String =
        "$baseUrl${remotePath}?download=true"

    private fun hasAllFiles(modelId: String, rootDir: File, specs: List<DownloadFileSpec>): Boolean {
        if (!rootDir.exists()) return false
        return specs.all { spec ->
            TtsModelValidator.isFileValid(modelId, spec.localPath, File(rootDir, spec.localPath))
        }
    }

    private fun countValidFiles(modelId: String, rootDir: File, specs: List<DownloadFileSpec>): Int {
        if (!rootDir.exists()) return 0
        return specs.count { spec ->
            TtsModelValidator.isFileValid(modelId, spec.localPath, File(rootDir, spec.localPath))
        }
    }

    private fun authHeaders(token: String?): Map<String, String> {
        return if (token.isNullOrBlank()) emptyMap() else mapOf("Authorization" to "Bearer $token")
    }

    private suspend fun downloadWithRetry(
        fileLabel: String,
        url: String,
        target: File,
        headers: Map<String, String>,
        maxAttempts: Int = 4,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ) {
        var attempt = 1
        while (true) {
            try {
                Downloader.downloadFile(
                    url = url,
                    target = target,
                    headers = headers,
                    onProgress = onProgress
                ).getOrThrow()
                return
            } catch (e: Exception) {
                if (attempt >= maxAttempts) {
                    throw e
                }
                val backoffMs = 1500L * attempt
                DebugLogger.log(
                    "ModelDownloader: Retry $attempt/$maxAttempts for $fileLabel after error: ${e.message}"
                )
                delay(backoffMs)
                attempt += 1
            }
        }
    }

    private fun updateProgress(
        modelId: String,
        currentFile: String,
        downloadedBytes: Long,
        totalBytes: Long,
        fraction: Float
    ) {
        val safeFraction = fraction.coerceIn(0f, 1f)
        sharedProgress.update { current ->
            current + (modelId to safeFraction)
        }
        sharedDetailedProgress.update { current ->
            current + (modelId to DetailedProgress(
                currentFile = currentFile,
                downloadedBytes = downloadedBytes,
                totalBytes = totalBytes,
                fraction = safeFraction
            ))
        }
    }

    private fun clearProgress(modelId: String) {
        sharedProgress.update { current ->
            if (modelId in current) {
                current - modelId
            } else {
                current
            }
        }
        sharedDetailedProgress.update { current ->
            if (modelId in current) {
                current - modelId
            } else {
                current
            }
        }
    }
}

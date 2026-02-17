package com.mewmix.nabu.data

import com.mewmix.nabu.kokoro.Downloader
import com.mewmix.nabu.kokoro.ManifestProvider
import com.mewmix.nabu.utils.DebugLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

class ModelDownloader(
    private val context: android.content.Context,
    private val userPreferencesRepository: UserPreferencesRepository,
) {
    companion object {
        const val KOKORO_MODEL_ID = "kokoro-default"
        private val activeModelDownloads = mutableSetOf<String>()
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
    private val _progress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val progress: StateFlow<Map<String, Float>> = _progress

    private val _detailedProgress = MutableStateFlow<Map<String, DetailedProgress>>(emptyMap())
    val detailedProgress: StateFlow<Map<String, DetailedProgress>> = _detailedProgress

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

        val allPresent = hasAllFiles(model.id, targetDir, fileSpecs)
        if (allPresent) {
            model.isDownloaded = true
            model.hasPartial = false
            DebugLogger.log("ModelDownloader: ${model.name} already downloaded")
            return
        }

        val destinationRoot = when {
            targetDir.exists() -> targetDir
            partialDir.exists() -> partialDir
            else -> {
                partialDir.mkdirs()
                partialDir
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
                val maxAttempts = 3
                var attempt = 1
                while (true) {
                    try {
                        Downloader.downloadFile(
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
                        }.getOrThrow()
                        break
                    } catch (e: Exception) {
                        if (attempt >= maxAttempts) {
                            throw e
                        }
                        val backoffMs = 1500L * attempt
                        DebugLogger.log(
                            "ModelDownloader: Retry $attempt/$maxAttempts for ${model.id}/${spec.localPath} after error: ${e.message}"
                        )
                        delay(backoffMs)
                        attempt += 1
                    }
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
            Downloader.downloadFile(
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
            }.getOrThrow()

            if (finalFile.exists()) finalFile.delete()
            if (!tempFile.renameTo(finalFile)) {
                throw IllegalStateException("Unable to promote ${tempFile.name} to ${finalFile.name}")
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

    private fun llmExtension(model: Model): String =
        if (model.downloadUrl.lowercase().contains(".gguf")) "gguf" else "task"

    private fun isLlmArtifactValid(file: File): Boolean = file.exists() && file.isFile && file.length() > 0L

    private fun isModelDownloadedOnDisk(model: Model): Boolean {
        val modelDir = File(context.filesDir, "models")
        return if (model.type == ModelType.TTS) {
            hasAllFiles(model.id, File(modelDir, model.id), ttsFileSpecs(model))
        } else {
            val extension = llmExtension(model)
            isLlmArtifactValid(File(modelDir, "${model.id}.$extension"))
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
            "supertonic-2-onnx" -> listOf(
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

    private fun authHeaders(token: String?): Map<String, String> {
        return if (token.isNullOrBlank()) emptyMap() else mapOf("Authorization" to "Bearer $token")
    }

    private fun updateProgress(
        modelId: String,
        currentFile: String,
        downloadedBytes: Long,
        totalBytes: Long,
        fraction: Float
    ) {
        val safeFraction = fraction.coerceIn(0f, 1f)
        _progress.value = _progress.value.toMutableMap().apply {
            put(modelId, safeFraction)
        }
        _detailedProgress.value = _detailedProgress.value.toMutableMap().apply {
            put(
                modelId,
                DetailedProgress(
                    currentFile = currentFile,
                    downloadedBytes = downloadedBytes,
                    totalBytes = totalBytes,
                    fraction = safeFraction
                )
            )
        }
    }

    private fun clearProgress(modelId: String) {
        _progress.value = _progress.value.toMutableMap().apply {
            remove(modelId)
        }
        _detailedProgress.value = _detailedProgress.value.toMutableMap().apply {
            remove(modelId)
        }
    }
}

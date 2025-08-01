package com.example.kokoro82m.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection

class ModelDownloader(
    private val context: Context,
    private val userPreferencesRepository: UserPreferencesRepository,
) {
    private val scope = CoroutineScope(Dispatchers.IO)
    private val _progress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val progress: StateFlow<Map<String, Float>> = _progress

    fun downloadModel(model: Model) {
        scope.launch {
            val token = userPreferencesRepository.hfToken.first()
            val modelDir = File(context.filesDir, "models")
            if (!modelDir.exists()) modelDir.mkdirs()
            val finalFile = File(modelDir, "${model.id}.task")
            val tempFile = File(modelDir, "${model.id}.task.part")

            if (finalFile.exists()) {
                model.isDownloaded = true
                model.hasPartial = false
                return@launch
            }

            val existingSize = if (tempFile.exists()) tempFile.length() else 0L
            model.hasPartial = existingSize > 0

            try {
                val url = URL(model.downloadUrl)
                val connection = url.openConnection() as HttpsURLConnection
                token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
                if (existingSize > 0) {
                    connection.setRequestProperty("Range", "bytes=$existingSize-")
                }
                connection.connect()

                val contentLength = connection.getHeaderFieldInt("Content-Length", -1)
                val total = if (contentLength > 0) contentLength + existingSize else -1
                val input = connection.inputStream

                val output = FileOutputStream(tempFile, existingSize > 0)

                val buffer = ByteArray(1024)
                var bytesRead: Int
                var downloaded = existingSize
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    downloaded += bytesRead
                    if (total > 0) {
                        val p = downloaded.toFloat() / total
                        _progress.value = _progress.value.toMutableMap().apply { put(model.id, p) }
                    }
                }

                output.close()
                input.close()

                tempFile.renameTo(finalFile)
                model.isDownloaded = true
                model.hasPartial = false
            } catch (_: Exception) {
                model.hasPartial = tempFile.exists()
            } finally {
                _progress.value = _progress.value.toMutableMap().apply { remove(model.id) }
            }
        }
    }
}

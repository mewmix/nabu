package com.mewmix.nabu.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.mewmix.nabu.utils.DebugLogger
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection

class DownloadWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        DebugLogger.initialize(applicationContext)
        val modelId = inputData.getString("model_id")
        val downloadUrl = inputData.getString("download_url")
        val hfToken = inputData.getString("hf_token")

        if (modelId.isNullOrEmpty() || downloadUrl.isNullOrEmpty()) {
            return Result.failure()
        }

        return try {
            val modelDir = File(applicationContext.filesDir, "models")
            if (!modelDir.exists()) {
                modelDir.mkdirs()
            }
            val destinationFile = File(modelDir, "$modelId.task")

            DebugLogger.log("DownloadWorker: Starting download for model '$modelId' from: $downloadUrl")

            val url = URL(downloadUrl)
            val connection = url.openConnection() as HttpsURLConnection
            hfToken?.let {
                connection.setRequestProperty("Authorization", "Bearer $it")
                DebugLogger.log("DownloadWorker: Authorization header set for Hugging Face.")
            }
            connection.connect()

            val responseCode = connection.responseCode
            DebugLogger.log("DownloadWorker: HTTP Response Code: $responseCode")

            if (responseCode != HttpsURLConnection.HTTP_OK) {
                DebugLogger.log("DownloadWorker: HTTP error $responseCode for URL: $downloadUrl")
                return Result.failure()
            }

            val inputStream = connection.getInputStream()
            val outputStream = FileOutputStream(destinationFile)

            val buffer = ByteArray(1024)
            var len: Int
            while (inputStream.read(buffer).also { len = it } != -1) {
                outputStream.write(buffer, 0, len)
            }

            outputStream.close()
            inputStream.close()

            DebugLogger.log("DownloadWorker: Model '$modelId' downloaded successfully to ${destinationFile.absolutePath}")
            Result.success()
        } catch (e: Exception) {
            DebugLogger.log("DownloadWorker: Error downloading model '$modelId' from $downloadUrl - ${e.message}")
            Result.failure()
        }
    }
}

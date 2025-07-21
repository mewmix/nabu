package com.example.kokoro82m.data

import android.content.Context
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ModelDownloader(
    private val context: Context,
    private val userPreferencesRepository: UserPreferencesRepository
) {

    fun downloadModel(model: Model) {
        CoroutineScope(Dispatchers.IO).launch {
            val token = userPreferencesRepository.hfToken.first()
            val dataBuilder = Data.Builder()
                .putString("model_id", model.id)
                .putString("download_url", model.downloadUrl)

            token?.let {
                dataBuilder.putString("hf_token", it)
            }

            val downloadWorkRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(dataBuilder.build())
                .build()

            WorkManager.getInstance(context).enqueue(downloadWorkRequest)
        }
    }
}

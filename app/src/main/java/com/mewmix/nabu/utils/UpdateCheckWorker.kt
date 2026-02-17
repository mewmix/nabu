package com.mewmix.nabu.utils

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class UpdateCheckWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            val result = UpdateChecker.checkForUpdate(applicationContext)
            if (result.success) {
                Result.success()
            } else {
                Result.retry()
            }
        } catch (t: Throwable) {
            DebugLogger.log("UpdateCheckWorker failure: ${t.message}")
            Result.retry()
        }
    }
}

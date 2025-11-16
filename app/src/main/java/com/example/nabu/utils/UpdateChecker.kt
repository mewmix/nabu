package com.example.nabu.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import com.example.nabu.utils.getAppVersion
import com.example.nabu.utils.DebugLogger

sealed class UpdateStatus {
    object Idle : UpdateStatus()
    data class Checking(val currentVersion: String) : UpdateStatus()
    data class NoUpdate(val currentVersion: String, val latest: String?) : UpdateStatus()
    data class UpdateAvailable(val current: String, val latest: String, val apkUrl: String) : UpdateStatus()
    data class DownloadInProgress(val latest: String) : UpdateStatus()
    data class DownloadFailed(val latest: String?, val reason: String) : UpdateStatus()
    data class InstallLaunched(val filePath: String) : UpdateStatus()
    data class InstallFailed(val reason: String) : UpdateStatus()
}

data class GithubRelease(
    val tag_name: String,
    val assets: List<Asset>
)

data class Asset(
    val browser_download_url: String,
    val name: String
)

object UpdateChecker {

    @Volatile private var lastStatus: UpdateStatus = UpdateStatus.Idle
    fun getLastStatus(): UpdateStatus = lastStatus

    private const val GITHUB_API_URL = "https://api.github.com/repos/mewmix/nabu/releases/latest"

    suspend fun checkForUpdate(context: Context) {
        val currentVersion = getAppVersion(context)
        lastStatus = UpdateStatus.Checking(currentVersion)
        DebugLogger.log("UpdateChecker: checkForUpdate: starting, current=$currentVersion")
        withContext(Dispatchers.IO) {
            try {
                val url = URL(GITHUB_API_URL)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connect()

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val inputStream = connection.inputStream
                    val response = inputStream.bufferedReader().use { it.readText() }
                    val release = Gson().fromJson(response, GithubRelease::class.java)
                    val latestVersion = release.tag_name
                    val asset = release.assets.firstOrNull { it.name.endsWith(".apk") }
                    DebugLogger.log("UpdateChecker: latestVersion=$latestVersion, assetUrl=${asset?.browser_download_url}")

                    if (isNewerVersion(latestVersion, currentVersion)) {
                        asset?.let {
                            lastStatus = UpdateStatus.UpdateAvailable(currentVersion, latestVersion, it.browser_download_url)
                            downloadAndInstall(context, it.browser_download_url)
                        }
                    } else {
                        lastStatus = UpdateStatus.NoUpdate(currentVersion, latestVersion)
                    }
                } else {
                    lastStatus = UpdateStatus.DownloadFailed(null, "Server error: ${connection.responseCode}")
                }
            } catch (e: Exception) {
                DebugLogger.log("UpdateChecker: checkForUpdate failed", e)
                lastStatus = UpdateStatus.DownloadFailed(null, e.message ?: "Unknown error")
            }
        }
    }

    private fun isNewerVersion(latestVersion: String, currentVersion: String): Boolean {
        DebugLogger.log("UpdateChecker: isNewerVersion: latest=$latestVersion, current=$currentVersion")
        val result = runCatching {
            // Simple version comparison, assumes format "vX.Y.Z" or "X.Y.Z"
            val latest = latestVersion.removePrefix("v").split(".").map { it.toInt() }
            val current = currentVersion.removePrefix("v").split(".").map { it.toInt() }
            val latestMajor = latest.getOrNull(0) ?: 0
            val latestMinor = latest.getOrNull(1) ?: 0
            val latestPatch = latest.getOrNull(2) ?: 0
            val currentMajor = current.getOrNull(0) ?: 0
            val currentMinor = current.getOrNull(1) ?: 0
            val currentPatch = current.getOrNull(2) ?: 0

            if (latestMajor > currentMajor) return@runCatching true
            if (latestMajor == currentMajor && latestMinor > currentMinor) return@runCatching true
            if (latestMajor == currentMajor && latestMinor == currentMinor && latestPatch > currentPatch) return@runCatching true

            false
        }.onFailure {
            DebugLogger.log("UpdateChecker: version compare failed", it)
        }.getOrDefault(false)
        DebugLogger.log("UpdateChecker: isNewerVersion result=$result")
        return result
    }

    private suspend fun downloadAndInstall(context: Context, apkUrl: String) {
        val latestVersion = (lastStatus as? UpdateStatus.UpdateAvailable)?.latest
        lastStatus = UpdateStatus.DownloadInProgress(latestVersion ?: "unknown")
        withContext(Dispatchers.IO) {
            try {
                val url = URL(apkUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connect()

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    DebugLogger.log("UpdateChecker: downloadAndInstall: HTTP ${connection.responseCode}, content-length: ${connection.contentLength}")
                    val inputStream = connection.inputStream
                    val file = File(context.externalCacheDir, "update.apk")
                    val fileOutputStream = FileOutputStream(file)
                    inputStream.copyTo(fileOutputStream)
                    fileOutputStream.close()
                    inputStream.close()
                    DebugLogger.log("UpdateChecker: downloaded to ${file.absolutePath}, size=${file.length()}")

                    installApk(context, file)
                } else {
                    lastStatus = UpdateStatus.DownloadFailed(latestVersion, "Download failed: ${connection.responseCode}")
                }
            } catch (e: Exception) {
                DebugLogger.log("UpdateChecker: downloadAndInstall failed", e)
                lastStatus = UpdateStatus.DownloadFailed(latestVersion, e.message ?: "Unknown error")
            }
        }
    }

    private fun installApk(context: Context, apkFile: File) {
        try {
            val intent = Intent(Intent.ACTION_VIEW)
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                FileProvider.getUriForFile(context, context.packageName + ".provider", apkFile)
            } else {
                Uri.fromFile(apkFile)
            }
            DebugLogger.log("UpdateChecker: installApk: uri=$uri")
            intent.setDataAndType(uri, "application/vnd.android.package-archive")
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            lastStatus = UpdateStatus.InstallLaunched(apkFile.absolutePath)
        } catch (e: Exception) {
            DebugLogger.log("UpdateChecker: installApk failed", e)
            lastStatus = UpdateStatus.InstallFailed(e.message ?: "Unknown error")
        }
    }
}

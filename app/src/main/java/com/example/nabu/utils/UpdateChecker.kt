package com.example.nabu.utils

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

data class GithubRelease(
    val tag_name: String,
    val assets: List<Asset>
)

data class Asset(
    val browser_download_url: String,
    val name: String
)

object UpdateChecker {

    private const val GITHUB_API_URL = "https://api.github.com/repos/mewmix/nabu/releases/latest"

    suspend fun checkForUpdate(context: Context) {
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
                    val currentVersion = getAppVersion(context)

                    if (isNewerVersion(latestVersion, currentVersion)) {
                        val asset = release.assets.firstOrNull { it.name.endsWith(".apk") }
                        asset?.let {
                            downloadAndInstall(context, it.browser_download_url)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("UpdateChecker", "Error checking for updates", e)
            }
        }
    }

    private fun isNewerVersion(latestVersion: String, currentVersion: String): Boolean {
        // Simple version comparison, assumes format "vX.Y.Z" or "X.Y.Z"
        val latest = latestVersion.removePrefix("v").split(".").map { it.toInt() }
        val current = currentVersion.removePrefix("v").split(".").map { it.toInt() }
        val latestMajor = latest.getOrNull(0) ?: 0
        val latestMinor = latest.getOrNull(1) ?: 0
        val latestPatch = latest.getOrNull(2) ?: 0
        val currentMajor = current.getOrNull(0) ?: 0
        val currentMinor = current.getOrNull(1) ?: 0
        val currentPatch = current.getOrNull(2) ?: 0

        if (latestMajor > currentMajor) return true
        if (latestMajor == currentMajor && latestMinor > currentMinor) return true
        if (latestMajor == currentMajor && latestMinor == currentMinor && latestPatch > currentPatch) return true

        return false
    }

    private suspend fun downloadAndInstall(context: Context, apkUrl: String) {
        withContext(Dispatchers.IO) {
            try {
                val url = URL(apkUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connect()

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val inputStream = connection.inputStream
                    val file = File(context.externalCacheDir, "update.apk")
                    val fileOutputStream = FileOutputStream(file)
                    inputStream.copyTo(fileOutputStream)
                    fileOutputStream.close()
                    inputStream.close()

                    installApk(context, file)
                }
            } catch (e: Exception) {
                Log.e("UpdateChecker", "Error downloading or installing update", e)
            }
        }
    }

    private fun installApk(context: Context, apkFile: File) {
        val intent = Intent(Intent.ACTION_VIEW)
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            FileProvider.getUriForFile(context, context.packageName + ".provider", apkFile)
        } else {
            Uri.fromFile(apkFile)
        }
        intent.setDataAndType(uri, "application/vnd.android.package-archive")
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun getAppVersion(context: Context): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "0.0.0"
        } catch (e: Exception) {
            "0.0.0"
        }
    }
}

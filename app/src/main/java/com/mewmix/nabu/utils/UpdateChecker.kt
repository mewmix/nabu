package com.mewmix.nabu.utils

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

data class GithubRelease(
    val tag_name: String = "",
    val html_url: String = "",
    val assets: List<Asset> = emptyList()
)

data class Asset(
    val browser_download_url: String,
    val name: String
)

data class CachedUpdateStatus(
    val updateAvailable: Boolean,
    val currentVersion: String,
    val latestVersion: String?,
    val releaseUrl: String?,
    val apkUrl: String?,
    val lastCheckedAt: Long
)

data class UpdateCheckResult(
    val success: Boolean,
    val updateAvailable: Boolean,
    val latestVersion: String?,
    val releaseUrl: String?,
    val apkUrl: String?,
    val checkedAt: Long
)

object UpdateChecker {

    private const val GITHUB_API_URL = "https://api.github.com/repos/mewmix/nabu/releases/latest"
    private const val UPDATE_CHECK_WORK = "github_release_update_check"
    private const val UPDATE_STARTUP_CHECK_WORK = "github_release_update_startup"
    private const val MIN_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L

    private const val KEY_UPDATE_LAST_CHECK_MS = "update_last_check_ms"
    private const val KEY_UPDATE_LATEST_VERSION = "update_latest_version"
    private const val KEY_UPDATE_RELEASE_URL = "update_release_url"
    private const val KEY_UPDATE_APK_URL = "update_apk_url"
    private const val KEY_UPDATE_AVAILABLE = "update_available"

    fun schedulePeriodicChecks(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val periodic = PeriodicWorkRequestBuilder<UpdateCheckWorker>(12, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniquePeriodicWork(
                UPDATE_CHECK_WORK,
                ExistingPeriodicWorkPolicy.UPDATE,
                periodic
            )
    }

    fun enqueueStartupCheck(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<UpdateCheckWorker>()
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(
                UPDATE_STARTUP_CHECK_WORK,
                ExistingWorkPolicy.REPLACE,
                request
            )
    }

    suspend fun checkForUpdate(context: Context, force: Boolean = false): UpdateCheckResult =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val currentVersion = getAppVersion(context)
            val lastCheckedAt = getLongSetting(context, KEY_UPDATE_LAST_CHECK_MS, 0L)
            if (!force && lastCheckedAt > 0L && now - lastCheckedAt < MIN_CHECK_INTERVAL_MS) {
                return@withContext cachedStatus(context).let { cached ->
                    UpdateCheckResult(
                        success = true,
                        updateAvailable = cached.updateAvailable,
                        latestVersion = cached.latestVersion,
                        releaseUrl = cached.releaseUrl,
                        apkUrl = cached.apkUrl,
                        checkedAt = cached.lastCheckedAt
                    )
                }
            }

            try {
                val url = URL(GITHUB_API_URL)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 10_000
                connection.readTimeout = 15_000
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.setRequestProperty("User-Agent", "nabu-update-checker")
                connection.connect()

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val release = Gson().fromJson(response, GithubRelease::class.java)
                    val latestVersion = release.tag_name.trim().ifBlank { null }
                    val releaseUrl = release.html_url.trim().ifBlank { null }
                    val apkUrl = release.assets
                        .firstOrNull {
                            it.name.endsWith(".apk", ignoreCase = true) &&
                                !it.name.contains("debug", ignoreCase = true)
                        }
                        ?.browser_download_url
                        ?.trim()
                        ?.ifBlank { null }
                        ?: release.assets
                            .firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
                            ?.browser_download_url
                            ?.trim()
                            ?.ifBlank { null }

                    val updateAvailable = latestVersion?.let { isNewerVersion(it, currentVersion) } == true
                    persistUpdateStatus(
                        context = context,
                        checkedAt = now,
                        latestVersion = latestVersion,
                        releaseUrl = releaseUrl,
                        apkUrl = apkUrl,
                        updateAvailable = updateAvailable
                    )

                    return@withContext UpdateCheckResult(
                        success = true,
                        updateAvailable = updateAvailable,
                        latestVersion = latestVersion,
                        releaseUrl = releaseUrl,
                        apkUrl = apkUrl,
                        checkedAt = now
                    )
                }
                persistUpdateStatus(
                    context = context,
                    checkedAt = now,
                    latestVersion = getStringSetting(context, KEY_UPDATE_LATEST_VERSION),
                    releaseUrl = getStringSetting(context, KEY_UPDATE_RELEASE_URL),
                    apkUrl = getStringSetting(context, KEY_UPDATE_APK_URL),
                    updateAvailable = false
                )
                return@withContext UpdateCheckResult(
                    success = false,
                    updateAvailable = false,
                    latestVersion = getStringSetting(context, KEY_UPDATE_LATEST_VERSION),
                    releaseUrl = getStringSetting(context, KEY_UPDATE_RELEASE_URL),
                    apkUrl = getStringSetting(context, KEY_UPDATE_APK_URL),
                    checkedAt = now
                )
            } catch (_: JsonSyntaxException) {
                return@withContext UpdateCheckResult(
                    success = false,
                    updateAvailable = cachedStatus(context).updateAvailable,
                    latestVersion = getStringSetting(context, KEY_UPDATE_LATEST_VERSION),
                    releaseUrl = getStringSetting(context, KEY_UPDATE_RELEASE_URL),
                    apkUrl = getStringSetting(context, KEY_UPDATE_APK_URL),
                    checkedAt = now
                )
            } catch (e: Exception) {
                DebugLogger.log("UpdateChecker: Error checking updates: ${e.message}")
                return@withContext UpdateCheckResult(
                    success = false,
                    updateAvailable = cachedStatus(context).updateAvailable,
                    latestVersion = getStringSetting(context, KEY_UPDATE_LATEST_VERSION),
                    releaseUrl = getStringSetting(context, KEY_UPDATE_RELEASE_URL),
                    apkUrl = getStringSetting(context, KEY_UPDATE_APK_URL),
                    checkedAt = now
                )
            }
        }

    fun cachedStatus(context: Context): CachedUpdateStatus {
        return CachedUpdateStatus(
            updateAvailable = getStringSetting(context, KEY_UPDATE_AVAILABLE) == "1",
            currentVersion = getAppVersion(context),
            latestVersion = getStringSetting(context, KEY_UPDATE_LATEST_VERSION),
            releaseUrl = getStringSetting(context, KEY_UPDATE_RELEASE_URL),
            apkUrl = getStringSetting(context, KEY_UPDATE_APK_URL),
            lastCheckedAt = getLongSetting(context, KEY_UPDATE_LAST_CHECK_MS, 0L)
        )
    }

    private fun persistUpdateStatus(
        context: Context,
        checkedAt: Long,
        latestVersion: String?,
        releaseUrl: String?,
        apkUrl: String?,
        updateAvailable: Boolean
    ) {
        DatabaseManager.setSetting(context, KEY_UPDATE_LAST_CHECK_MS, checkedAt.toString())
        DatabaseManager.setSetting(context, KEY_UPDATE_LATEST_VERSION, latestVersion.orEmpty())
        DatabaseManager.setSetting(context, KEY_UPDATE_RELEASE_URL, releaseUrl.orEmpty())
        DatabaseManager.setSetting(context, KEY_UPDATE_APK_URL, apkUrl.orEmpty())
        DatabaseManager.setSetting(context, KEY_UPDATE_AVAILABLE, if (updateAvailable) "1" else "0")
    }

    private fun getStringSetting(context: Context, key: String): String? =
        DatabaseManager.getSetting(context, key)?.ifBlank { null }

    private fun getLongSetting(context: Context, key: String, default: Long): Long =
        DatabaseManager.getSetting(context, key)?.toLongOrNull() ?: default

    private fun isNewerVersion(latestVersion: String, currentVersion: String): Boolean {
        val latest = parseVersionNumbers(latestVersion)
        val current = parseVersionNumbers(currentVersion)
        val maxParts = maxOf(latest.size, current.size)
        for (i in 0 until maxParts) {
            val l = latest.getOrElse(i) { 0 }
            val c = current.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
        }
        return false
    }

    private fun parseVersionNumbers(version: String): List<Int> =
        Regex("\\d+").findAll(version).map { it.value.toIntOrNull() ?: 0 }.toList()

    private fun getAppVersion(context: Context): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "0.0.0"
        } catch (e: Exception) {
            "0.0.0"
        }
    }
}

package com.mewmix.nabu.utils

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

fun getAppVersion(context: Context): String {
    val packageManager = context.packageManager
    val packageName = context.packageName
    return try {
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(0L)
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0)
        }
        packageInfo?.versionName ?: "Unknown"
    } catch (error: PackageManager.NameNotFoundException) {
        "Unknown"
    }
}

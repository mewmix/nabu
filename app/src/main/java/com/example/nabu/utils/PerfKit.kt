package com.example.nabu.utils

import android.app.ActivityManager
import android.content.Context
import android.os.Debug

/** Simple wrapper to collect basic CPU and memory stats. */
object PerfKit {
    fun profile(context: Context) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        val cpuMs = Debug.threadCpuTimeNanos() / 1_000_000
        DebugLogger.log("PerfKit CPU ${cpuMs}ms availMem ${memInfo.availMem}")
    }
}


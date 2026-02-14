package com.mewmix.nabu.api

import android.content.Context
import com.mewmix.nabu.utils.DebugLogger
import com.mewmix.nabu.utils.SettingsManager

object ApiServerManager {
    const val HOST: String = "127.0.0.1"
    const val PORT: Int = 8455

    private val lock = Any()
    private var server: ApiServer? = null

    fun syncWithSettings(context: Context) {
        if (SettingsManager.isApiEnabled(context)) {
            start(context)
        } else {
            stop()
        }
    }

    fun start(context: Context) {
        synchronized(lock) {
            if (server?.isRunning() == true) {
                return
            }

            val created = ApiServer(
                context = context.applicationContext,
                host = HOST,
                port = PORT
            )

            try {
                created.start()
                server = created
            } catch (t: Throwable) {
                DebugLogger.logErr("Failed to start ApiServer", t)
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            val current = server ?: return
            try {
                current.stop()
            } catch (t: Throwable) {
                DebugLogger.logErr("Failed to stop ApiServer", t)
            } finally {
                server = null
            }
        }
    }

    fun isRunning(): Boolean = synchronized(lock) {
        server?.isRunning() == true
    }
}

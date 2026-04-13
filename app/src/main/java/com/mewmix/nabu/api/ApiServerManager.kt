package com.mewmix.nabu.api

import android.content.Context
import com.mewmix.nabu.utils.DebugLogger
import com.mewmix.nabu.utils.SettingsManager
import java.util.Collections
import java.net.NetworkInterface

object ApiServerManager {
    const val LOCAL_HOST: String = "127.0.0.1"
    const val LAN_HOST: String = "0.0.0.0"
    const val PORT: Int = 8455

    private val lock = Any()
    private var server: ApiServer? = null
    private var boundHost: String? = null
    private var portOverride: Int? = null

    fun syncWithSettings(context: Context) {
        if (!SettingsManager.isApiEnabled(context)) {
            stop()
            return
        }

        val appContext = context.applicationContext
        val desiredHost = configuredHost(appContext)
        val desiredPort = resolvedPort()

        synchronized(lock) {
            if (server?.isRunning() == true && boundHost == desiredHost) {
                return
            }
            stopLocked()
            startLocked(appContext, desiredHost, desiredPort)
        }
    }

    fun start(context: Context) {
        synchronized(lock) {
            if (server?.isRunning() == true) {
                return
            }

            val appContext = context.applicationContext
            val desiredHost = configuredHost(appContext)
            val desiredPort = resolvedPort()
            startLocked(appContext, desiredHost, desiredPort)
        }
    }

    fun configuredHost(context: Context): String {
        return if (SettingsManager.isApiLanEnabled(context)) LAN_HOST else LOCAL_HOST
    }

    fun currentHost(): String? = synchronized(lock) {
        boundHost
    }

    fun currentPort(): Int = synchronized(lock) {
        resolvedPort()
    }

    fun localLanIpAddress(): String? {
        return runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            Collections.list(interfaces)
                .asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { Collections.list(it.inetAddresses).asSequence() }
                .firstOrNull { address ->
                    !address.isLoopbackAddress &&
                        !address.isLinkLocalAddress &&
                        address.hostAddress?.contains(':') == false
                }
                ?.hostAddress
        }.getOrNull()
    }

    fun stop() {
        synchronized(lock) {
            stopLocked()
        }
    }

    private fun startLocked(context: Context, host: String, port: Int) {
        val created = ApiServer(
            context = context,
            host = host,
            port = port
        )

        try {
            created.start()
            server = created
            boundHost = host
        } catch (t: Throwable) {
            DebugLogger.logErr("Failed to start ApiServer", t)
            server = null
            boundHost = null
        }
    }

    private fun stopLocked() {
        val current = server ?: run {
            boundHost = null
            return
        }
        try {
            current.stop()
        } catch (t: Throwable) {
            DebugLogger.logErr("Failed to stop ApiServer", t)
        } finally {
            server = null
            boundHost = null
        }
    }

    fun isRunning(): Boolean = synchronized(lock) {
        server?.isRunning() == true
    }

    private fun resolvedPort(): Int = portOverride ?: PORT

    fun setPortOverrideForTesting(port: Int?) {
        synchronized(lock) {
            portOverride = port
        }
    }
}

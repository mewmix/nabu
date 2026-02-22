package com.mewmix.nabu.auth

import android.net.Uri
import com.mewmix.nabu.utils.DebugLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

internal object OAuthLoopbackReceiver {
    data class Session(
        val redirectUri: String,
        private val callback: CompletableDeferred<Uri?>,
        private val closed: AtomicBoolean,
        private val socket: ServerSocket
    ) {
        suspend fun awaitCallback(timeoutMs: Long): Uri? = withTimeoutOrNull(timeoutMs) {
            callback.await()
        }

        fun close() {
            if (closed.compareAndSet(false, true)) {
                runCatching { socket.close() }
                if (!callback.isCompleted) {
                    callback.complete(null)
                }
            }
        }
    }

    fun start(
        callbackPath: String = "/auth/callback",
        bindHost: String = "127.0.0.1",
        redirectHost: String = bindHost,
        preferredPort: Int? = null
    ): Session {
        val normalizedPath = if (callbackPath.startsWith("/")) callbackPath else "/$callbackPath"
        val callback = CompletableDeferred<Uri?>()
        val closed = AtomicBoolean(false)
        val bindPort = preferredPort?.takeIf { it in 1..65535 } ?: 0
        val serverSocket = ServerSocket(bindPort, 8, InetAddress.getByName(bindHost)).apply {
            soTimeout = 1000
        }
        val port = serverSocket.localPort
        val redirectUri = "http://$redirectHost:$port$normalizedPath"
        DebugLogger.log(
            "OAuthLoopbackReceiver: Listening on $redirectUri (bind=$bindHost:$port)"
        )

        val worker = Thread {
            while (!closed.get()) {
                try {
                    val socket = serverSocket.accept()
                    var shouldStop = false
                    socket.use { client ->
                        val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                        val writer = BufferedWriter(OutputStreamWriter(client.getOutputStream()))

                        val requestLine = reader.readLine().orEmpty()
                        val requestPath = parseRequestPath(requestLine)
                        val requestUri = if (requestPath != null) {
                            Uri.parse("http://$redirectHost:$port$requestPath")
                        } else {
                            null
                        }
                        val matched = requestUri?.path == normalizedPath
                        if (requestLine.isNotBlank()) {
                            DebugLogger.log(
                                "OAuthLoopbackReceiver: request='$requestLine' matched=$matched uri=$requestUri"
                            )
                        }

                        val body = if (matched) {
                            "Authentication completed. Return to Nabu."
                        } else {
                            "Not found."
                        }
                        val status = if (matched) "200 OK" else "404 Not Found"
                        val response = buildString {
                            append("HTTP/1.1 $status\r\n")
                            append("Content-Type: text/plain; charset=utf-8\r\n")
                            append("Connection: close\r\n")
                            append("Content-Length: ${body.toByteArray().size}\r\n\r\n")
                            append(body)
                        }
                        writer.write(response)
                        writer.flush()

                        if (matched && !callback.isCompleted) {
                            callback.complete(requestUri)
                            DebugLogger.log("OAuthLoopbackReceiver: Callback captured ($requestUri)")
                            closed.set(true)
                            runCatching { serverSocket.close() }
                            shouldStop = true
                        }
                    }
                    if (shouldStop) {
                        break
                    }
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (_: Exception) {
                    DebugLogger.log("OAuthLoopbackReceiver: Listener terminated unexpectedly")
                    if (!callback.isCompleted) {
                        callback.complete(null)
                    }
                    closed.set(true)
                    runCatching { serverSocket.close() }
                    break
                }
            }
        }.apply {
            name = "oauth-loopback-$port"
            isDaemon = true
            start()
        }

        return Session(
            redirectUri = redirectUri,
            callback = callback,
            closed = closed,
            socket = serverSocket
        )
    }

    private fun parseRequestPath(requestLine: String): String? {
        if (!requestLine.startsWith("GET ")) return null
        val firstSpace = requestLine.indexOf(' ')
        if (firstSpace < 0) return null
        val secondSpace = requestLine.indexOf(' ', startIndex = firstSpace + 1)
        if (secondSpace < 0) return null
        return requestLine.substring(firstSpace + 1, secondSpace)
    }
}

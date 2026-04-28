package com.mewmix.nabu.auth

import com.mewmix.nabu.utils.DebugLogger
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Interceptor
import okhttp3.dnsoverhttps.DnsOverHttps
import org.json.JSONObject
import java.net.ConnectException
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

internal object OAuthHttpClient {
    private const val MAX_RETRIES = 3
    private const val BASE_RETRY_DELAY_MS = 500L
    private const val DNS_FALLBACK_URL = "https://dns.google/dns-query"

    data class RawHttpResponse(
        val body: String,
        val contentType: String?
    )

    private val loggingInterceptor = Interceptor { chain ->
        val request = chain.request()
        val headers = request.headers.joinToString(separator = ", ") { (name, value) ->
            val safeValue = when {
                name.equals("Authorization", ignoreCase = true) -> "Bearer ***"
                name.equals("Cookie", ignoreCase = true) -> "***"
                name.equals("Set-Cookie", ignoreCase = true) -> "***"
                else -> value
            }
            "$name: $safeValue"
        }
        val bodyType = request.body?.contentType()?.toString() ?: "null"
        DebugLogger.log("OAuthHttpClient OUT: URL=${request.url} | Headers=[$headers] | BodyCT=$bodyType")
        chain.proceed(request)
    }

    private val jsonMediaType = "application/json".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(loggingInterceptor)
        .build()
    private val doHBootstrapClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    private val doHDns = DnsOverHttps.Builder()
        .client(doHBootstrapClient)
        .url(DNS_FALLBACK_URL.toHttpUrl())
        .bootstrapDnsHosts(
            InetAddress.getByName("8.8.8.8"),
            InetAddress.getByName("8.8.4.4"),
            InetAddress.getByName("1.1.1.1"),
            InetAddress.getByName("1.0.0.1")
        )
        .includeIPv6(false)
        .resolvePrivateAddresses(false)
        .build()
    private val doHClient = client.newBuilder()
        .dns(doHDns)
        .build()

    fun postForm(url: String, fields: Map<String, String>): Result<JSONObject> = runCatching {
        val bodyBuilder = FormBody.Builder()
        fields.forEach { (key, value) ->
            bodyBuilder.add(key, value)
        }
        val request = Request.Builder()
            .url(url)
            .post(bodyBuilder.build())
            .header("Accept", "application/json")
            .build()
        executeJson(request)
    }

    fun postJson(
        url: String,
        payload: JSONObject,
        headers: Map<String, String> = emptyMap()
    ): Result<JSONObject> = runCatching {
        val requestBuilder = Request.Builder()
            .url(url)
            .post(payload.toString().toByteArray().toRequestBody(jsonMediaType))
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
        headers.forEach { (key, value) ->
            requestBuilder.header(key, value)
        }
        val request = requestBuilder.build()
        executeJson(request)
    }

    fun postJsonRaw(
        url: String,
        payload: JSONObject,
        headers: Map<String, String> = emptyMap()
    ): Result<RawHttpResponse> = runCatching {
        val requestBuilder = Request.Builder()
            .url(url)
            .post(payload.toString().toByteArray().toRequestBody(jsonMediaType))
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
        headers.forEach { (key, value) ->
            requestBuilder.header(key, value)
        }
        executeRaw(requestBuilder.build())
    }

    fun getJson(url: String, headers: Map<String, String>): Result<JSONObject> = runCatching {
        val requestBuilder = Request.Builder().url(url).get()
        headers.forEach { (key, value) ->
            requestBuilder.header(key, value)
        }
        executeJson(requestBuilder.build())
    }

    private fun executeJson(request: Request): JSONObject {
        val raw = executeRaw(request)
        val text = raw.body
        if (text.isBlank()) {
            return JSONObject()
        }
        return JSONObject(text)
    }

    private fun executeRaw(request: Request): RawHttpResponse {
        var attempt = 0
        var lastError: Throwable? = null
        var forceDoH = false
        while (attempt < MAX_RETRIES) {
            try {
                val activeClient = if (forceDoH) doHClient else client
                activeClient.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        val contentType = response.header("Content-Type").orEmpty()
                        val requestId = response.header("x-request-id")
                            ?: response.header("request-id")
                            ?: response.header("openai-request-id")
                            ?: response.header("x-google-request-id")
                            ?: response.header("x-guploader-uploadid")
                        val cfRay = response.header("cf-ray")
                        val server = response.header("server")
                        val trimmedBody = if (text.length > 4000) "${text.take(4000)}..." else text
                        throw IllegalStateException(
                            "HTTP ${response.code}: $trimmedBody " +
                                "[url=${request.url} method=${request.method} " +
                                "ct=${if (contentType.isBlank()) "-" else contentType} " +
                                "request_id=${requestId ?: "-"} cf_ray=${cfRay ?: "-"} server=${server ?: "-"}]"
                        )
                    }
                    return RawHttpResponse(
                        body = text,
                        contentType = response.header("Content-Type")
                    )
                }
            } catch (error: Throwable) {
                lastError = error
                if (!forceDoH && shouldUseDoHFallback(request, error)) {
                    forceDoH = true
                    DebugLogger.log(
                        "OAuthHttpClient: DNS fallback enabled for ${request.url.host} via $DNS_FALLBACK_URL"
                    )
                }
                val retryable = isRetryableNetworkError(error)
                val finalAttempt = attempt >= MAX_RETRIES - 1
                if (!retryable || finalAttempt) {
                    throw error
                }
                val nextAttempt = attempt + 2
                val delayMs = BASE_RETRY_DELAY_MS * (attempt + 1)
                DebugLogger.log(
                    "OAuthHttpClient: network error ${error.javaClass.simpleName}: ${error.message}; " +
                        "retrying $nextAttempt/$MAX_RETRIES in ${delayMs}ms"
                )
                runCatching { Thread.sleep(delayMs) }
            }
            attempt += 1
        }
        throw (lastError ?: IllegalStateException("OAuth request failed"))
    }

    private fun isRetryableNetworkError(error: Throwable): Boolean =
        error is UnknownHostException ||
            error is SocketTimeoutException ||
            error is ConnectException ||
            error is SSLException

    private fun shouldUseDoHFallback(request: Request, error: Throwable): Boolean =
        error is UnknownHostException &&
            (
                request.url.host.endsWith("openai.com") ||
                    request.url.host.endsWith("chatgpt.com") ||
                    request.url.host.endsWith("googleapis.com")
                )
}

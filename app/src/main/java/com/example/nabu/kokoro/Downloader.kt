package com.example.nabu.kokoro

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.Duration

object Downloader {
    private const val TAG = "KokoroDownloader"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .callTimeout(Duration.ofMinutes(15))
        .connectTimeout(Duration.ofSeconds(30))
        .readTimeout(Duration.ofMinutes(10))
        .build()

    suspend fun ensureModels(ctx: Context, manifest: Manifest): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val root = File(ctx.filesDir, "")
                manifest.files.forEach { file ->
                    val outFile = File(root, file.dest)
                    if (outFile.exists()) {
                        val digest = runCatching { Hash.sha256(outFile) }.getOrNull()
                        if (digest != null && digest.equals(file.sha256, ignoreCase = true)) {
                            return@forEach
                        }
                        Log.w(TAG, "Existing ${file.id} failed verification, redownloading")
                    }

                    val tmpFile = File(outFile.absolutePath + ".part")
                    tmpFile.parentFile?.mkdirs()

                    rangeDownload(file.url, tmpFile)

                    if (!Size.check(tmpFile.length(), file.sizeBytes, tolerance = 0.05)) {
                        throw IllegalStateException(
                            "Size mismatch ${file.id}: expected ${file.sizeBytes}, got ${tmpFile.length()}"
                        )
                    }

                    val digest = Hash.sha256(tmpFile)
                    if (!digest.equals(file.sha256, ignoreCase = true)) {
                        throw IllegalStateException("SHA256 mismatch ${file.id}: expected ${file.sha256}, got $digest")
                    }

                    if (outFile.exists() && !outFile.delete()) {
                        throw IllegalStateException("Unable to replace ${file.dest}")
                    }
                    if (!tmpFile.renameTo(outFile)) {
                        throw IllegalStateException("Failed to atomically promote ${file.dest}")
                    }
                }
            }
        }

    fun modelsAvailable(ctx: Context, manifest: Manifest): Boolean {
        val root = File(ctx.filesDir, "")
        return manifest.files.all { file ->
            val outFile = File(root, file.dest)
            outFile.exists()
        }
    }

    @Throws(IOException::class)
    private fun rangeDownload(url: String, target: File) {
        var existingBytes = if (target.exists()) target.length() else 0L

        val requestBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", "Nabu/1.0 KokoroDownloader")
            .header("Accept-Encoding", "identity")

        if (existingBytes > 0) {
            requestBuilder.header("Range", "bytes=$existingBytes-")
        }

        val request = requestBuilder.build()
        client.newCall(request).execute().use { response ->
            if (response.code == 416) {
                Log.w(TAG, "Server reported range not satisfiable for $url, assuming file complete")
                return
            }

            if (!response.isSuccessful && response.code != 206) {
                throw IOException("Failed to download $url: HTTP ${response.code}")
            }

            val body = response.body ?: throw IOException("Empty body for $url")
            val append = response.code == 206 && existingBytes > 0

            if (!append && existingBytes > 0) {
                // Server ignored range, restart download.
                existingBytes = 0
            }

            FileOutputStream(target, append).use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                    }
                }
            }
        }
    }
}

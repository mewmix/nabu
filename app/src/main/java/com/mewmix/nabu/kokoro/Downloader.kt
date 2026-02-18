package com.mewmix.nabu.kokoro

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

    data class DownloadProgress(
        val fileId: String,
        val downloadedBytes: Long,
        val totalBytes: Long
    )

    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .callTimeout(Duration.ofMinutes(15))
        .connectTimeout(Duration.ofSeconds(30))
        .readTimeout(Duration.ofMinutes(10))
        .build()

    suspend fun ensureModels(
        ctx: Context,
        manifest: Manifest,
        onProgress: (DownloadProgress) -> Unit = {}
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val root = File(ctx.filesDir, "")
                val filesToDownload = manifest.files.filter { file ->
                    val outFile = File(root, file.dest)
                    if (!outFile.exists()) {
                        return@filter true
                    }
                    val digest = runCatching { Hash.sha256(outFile) }.getOrNull()
                    digest == null || !digest.equals(file.sha256, ignoreCase = true)
                }
                val totalBytes = filesToDownload.sumOf { it.sizeBytes }
                var completedBytes = 0L

                filesToDownload.forEach { file ->
                    val outFile = File(root, file.dest)
                    if (outFile.exists()) {
                        Log.w(TAG, "Existing ${file.id} failed verification, redownloading")
                    }

                    val tmpFile = File(outFile.absolutePath + ".part")
                    tmpFile.parentFile?.mkdirs()

                    val startingBytes = if (tmpFile.exists()) tmpFile.length() else 0L
                    if (totalBytes > 0L) {
                        onProgress(DownloadProgress(file.id, completedBytes + startingBytes, totalBytes))
                    }
                    rangeDownload(file.url, tmpFile) { currentBytes, _ ->
                        if (totalBytes > 0L) {
                            onProgress(DownloadProgress(file.id, completedBytes + currentBytes, totalBytes))
                        }
                    }

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
                    completedBytes += file.sizeBytes
                }
            }
        }

    suspend fun downloadFile(
        url: String,
        target: File,
        headers: Map<String, String> = emptyMap(),
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            target.parentFile?.mkdirs()
            rangeDownload(url, target, headers) { downloaded, total ->
                onProgress(downloaded, total)
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
    private fun rangeDownload(
        url: String,
        target: File,
        headers: Map<String, String> = emptyMap(),
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ) {
        var existingBytes = if (target.exists()) target.length() else 0L

        val requestBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", "Nabu/1.0 KokoroDownloader")
            .header("Accept-Encoding", "identity")
        headers.forEach { (name, value) ->
            requestBuilder.header(name, value)
        }

        if (existingBytes > 0L) {
            requestBuilder.header("Range", "bytes=$existingBytes-")
        }

        val request = requestBuilder.build()
        client.newCall(request).execute().use { response ->
            if (response.code == 416) {
                Log.w(TAG, "Server reported range not satisfiable for $url, assuming file complete")
                onProgress(existingBytes, existingBytes)
                return
            }

            if (!response.isSuccessful && response.code != 206) {
                throw IOException("Failed to download $url: HTTP ${response.code}")
            }

            val body = response.body ?: throw IOException("Empty body for $url")
            val append = response.code == 206 && existingBytes > 0L

            if (!append && existingBytes > 0L) {
                // Server ignored range, restart download.
                existingBytes = 0
            }

            val responseLength = body.contentLength().takeIf { it >= 0L }
            val totalBytes = when {
                response.code == 206 -> parseTotalFromContentRange(response.header("Content-Range"))
                responseLength != null -> existingBytes + responseLength
                else -> -1L
            }

            var writtenBytes = existingBytes
            if (!append) {
                writtenBytes = 0L
                onProgress(0L, totalBytes)
            } else {
                onProgress(writtenBytes, totalBytes)
            }

            FileOutputStream(target, append).use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        writtenBytes += read.toLong()
                        onProgress(writtenBytes, totalBytes)
                    }
                }
            }
        }
    }

    private fun parseTotalFromContentRange(contentRange: String?): Long {
        if (contentRange.isNullOrBlank()) return -1L
        // Content-Range example: "bytes 1048576-2097151/734003200"
        val slashIndex = contentRange.lastIndexOf('/')
        if (slashIndex == -1 || slashIndex == contentRange.lastIndex) return -1L
        val totalPart = contentRange.substring(slashIndex + 1).trim()
        if (totalPart == "*") return -1L
        return totalPart.toLongOrNull() ?: -1L
    }

}

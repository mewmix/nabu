package com.example.kokoro.galleryport

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

object ModelHub {
    private val client = OkHttpClient()

    suspend fun get(ctx: Context, url: String, alias: String): File =
        withContext(Dispatchers.IO) {
            val dst = File(ctx.filesDir, alias)
            if (!dst.exists()) {
                client.newCall(Request.Builder().url(url).build()).execute().use { rsp ->
                    dst.outputStream().use { rsp.body!!.byteStream().copyTo(it) }
                }
            }
            dst
        }
}

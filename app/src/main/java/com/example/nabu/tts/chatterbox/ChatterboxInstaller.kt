package com.example.nabu.tts.chatterbox

import android.content.Context
import com.example.nabu.tts.NabuPaths
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object ChatterboxInstaller {
    private const val MODEL_ID = "onnx-community/chatterbox-onnx"

    private val assets = listOf(
        Asset("tokenizer.json", "tokenizer.json"),
        Asset("default_voice.wav", "default_voice.wav"),
        Asset("onnx/speech_encoder.onnx", "onnx/speech_encoder.onnx"),
        Asset("onnx/speech_encoder.onnx_data", "onnx/speech_encoder.onnx_data"),
        Asset("onnx/embed_tokens.onnx", "onnx/embed_tokens.onnx"),
        Asset("onnx/embed_tokens.onnx_data", "onnx/embed_tokens.onnx_data"),
        Asset("onnx/language_model.onnx", "onnx/language_model.onnx"),
        Asset("onnx/language_model.onnx_data", "onnx/language_model.onnx_data"),
        Asset("onnx/conditional_decoder.onnx", "onnx/conditional_decoder.onnx"),
        Asset("onnx/conditional_decoder.onnx_data", "onnx/conditional_decoder.onnx_data"),
    )

    data class Asset(val remote: String, val localRelativePath: String)

    fun isInstalled(context: Context): Boolean {
        val dir = NabuPaths.chatterboxModelDir(context)
        return assets.all { File(dir, it.localRelativePath).exists() }
    }

    fun ensureInstalled(
        context: Context,
        onProgress: (Float) -> Unit = {},
    ) {
        val baseDir = NabuPaths.chatterboxModelDir(context)
        if (!baseDir.exists()) baseDir.mkdirs()
        assets.forEachIndexed { index, asset ->
            val targetFile = File(baseDir, asset.localRelativePath)
            if (targetFile.exists()) {
                val progress = (index + 1).toFloat() / assets.size
                onProgress(progress)
                return@forEachIndexed
            }
            targetFile.parentFile?.mkdirs()
            downloadAsset(asset, targetFile)
            val progress = (index + 1).toFloat() / assets.size
            onProgress(progress)
        }
    }

    private fun downloadAsset(asset: Asset, target: File) {
        val url = URL("https://huggingface.co/$MODEL_ID/resolve/main/${asset.remote}")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 30_000
        }
        connection.inputStream.use { input ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var count: Int
                while (input.read(buffer).also { count = it } != -1) {
                    output.write(buffer, 0, count)
                }
            }
        }
        connection.disconnect()
    }
}

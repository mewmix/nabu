package com.example.nabu.utils

import android.content.Context
import org.jetbrains.bio.npy.NpyArray
import org.jetbrains.bio.npy.NpyFile
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

class StyleLoader(private val context: Context) {
    private val engine = SettingsManager.getTtsEngine(context)

    val names: List<String> = when (engine) {
        TtsEngine.KOKORO -> context.assets.list("kokoro/voices")?.map { it.removeSuffix(".npy") } ?: emptyList()
        TtsEngine.KITTEN -> loadKittenVoiceNames()
    }

    fun getStyleArray(name: String, index: Int = 0): Array<FloatArray> = when (engine) {
        TtsEngine.KOKORO -> loadKokoroStyle(name, index)
        TtsEngine.KITTEN -> loadKittenStyle(name)
    }

    private fun loadKokoroStyle(name: String, index: Int): Array<FloatArray> {
        val inputStream = context.assets.open("kokoro/voices/$name.npy")
        val tempFile = File.createTempFile("temp_style", ".npy", context.cacheDir)
        tempFile.deleteOnExit()
        FileOutputStream(tempFile).use { outputStream ->
            inputStream.copyTo(outputStream)
        }

        val npyArray: NpyArray = NpyFile.read(tempFile.toPath())
        val shape = npyArray.shape

        if (shape.isEmpty() || shape.last() != 256) {
            throw IllegalArgumentException("The loaded .npy file must have a shape compatible with (*, ..., 256)")
        }

        val frameCount = shape[0]
        if (index < 0 || index >= frameCount) {
            throw IllegalArgumentException("Index must be between 0 and ${'$'}{frameCount - 1}")
        }

        val blockSize = shape.copyOfRange(1, shape.size).fold(1) { acc, dim -> acc * dim }
        val floatArray = npyArray.asFloatArray()
        val styleArray = Array(1) { FloatArray(256) }
        val offset = index * blockSize

        for (i in 0 until 256) {
            styleArray[0][i] = floatArray[offset + i]
        }

        return styleArray
    }

    private fun loadKittenVoiceNames(): List<String> {
        return context.assets.open("kitten_tts/voices.npz").use { input ->
            ZipInputStream(input).use { zip ->
                val list = mutableListOf<String>()
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name.endsWith(".npy")) {
                        list.add(entry.name.removeSuffix(".npy"))
                    }
                    entry = zip.nextEntry
                }
                list
            }
        }
    }

    private fun loadKittenStyle(name: String): Array<FloatArray> {
        context.assets.open("kitten_tts/voices.npz").use { input ->
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name == "$name.npy") {
                        val tempFile = File.createTempFile("temp_style", ".npy", context.cacheDir)
                        tempFile.deleteOnExit()
                        FileOutputStream(tempFile).use { output ->
                            zip.copyTo(output)
                        }
                        val npyArray: NpyArray = NpyFile.read(tempFile.toPath())
                        val floatArray = npyArray.asFloatArray()
                        val styleArray = Array(1) { FloatArray(256) }
                        for (i in 0 until 256) {
                            styleArray[0][i] = floatArray[i]
                        }
                        return styleArray
                    }
                    entry = zip.nextEntry
                }
            }
        }
        throw IllegalArgumentException("Voice $name not found in kitten voices")
    }
}


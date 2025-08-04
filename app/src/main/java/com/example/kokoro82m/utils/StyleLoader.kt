package com.example.kokoro82m.utils

import android.content.Context
import org.jetbrains.bio.npy.NpyArray
import org.jetbrains.bio.npy.NpyFile
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream


class StyleLoader(private val context: Context) {
    val names = context.assets.list("kokoro/voices")?.map { it.removeSuffix(".npy") } ?: emptyList()

    fun getStyleArray(name: String, index: Int = 0): Array<FloatArray> {
        val inputStream = context.assets.open("kokoro/voices/$name.npy")
        val tempFile = File.createTempFile("temp_style", ".npy", context.cacheDir)
        tempFile.deleteOnExit()
        FileOutputStream(tempFile).use { outputStream ->
            inputStream.copyTo(outputStream)
        }

        val npyArray: NpyArray = NpyFile.read(tempFile.toPath())

        if (npyArray.shape.size != 3 || npyArray.shape[0] != 511 || npyArray.shape[1] != 1 || npyArray.shape[2] != 256) {
            throw IllegalArgumentException("The loaded .npy file must have the shape (511, 1, 256)")
        }

        if (index < 0 || index >= 511) {
            throw IllegalArgumentException("Index must be between 0 and 510")
        }

        val styleArray = Array(1) { FloatArray(256) }
        val floatArray = npyArray.asFloatArray()

        for (i in 0 until 256) {
            styleArray[0][i] = floatArray[index * 256 + i]
        }

        return styleArray
    }
}

package com.mewmix.nabu.supertonic

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import com.google.gson.JsonParser
import java.io.File
import java.io.FileReader
import java.nio.FloatBuffer
import java.nio.LongBuffer

data class SupertonicResult(val wav: FloatArray, val duration: FloatArray, val sampleRate: Int)

internal data class SupertonicConfig(
    val sampleRate: Int,
    val baseChunkSize: Int,
    val chunkCompress: Int,
    val latentDim: Int
)

internal data class LatentBundle(
    val noisy: Array<Array<FloatArray>>,
    val mask: Array<Array<FloatArray>>
)

data class SupertonicStyle(
    val ttlTensor: OnnxTensor,
    val dpTensor: OnnxTensor
) : AutoCloseable {
    override fun close() {
        ttlTensor.close()
        dpTensor.close()
    }
}

object SupertonicLanguages {
    val available: List<String> = listOf(
        "en", "ko", "ja", "ar", "bg", "cs", "da", "de", "el", "es", "et", "fi",
        "fr", "hi", "hr", "hu", "id", "it", "lt", "lv", "nl", "pl", "pt", "ro",
        "ru", "sk", "sl", "sv", "tr", "uk", "vi", "na"
    )

    fun isValid(language: String): Boolean = language in available
}

internal fun normalizeSupertonicLanguage(language: String?): String {
    val normalized = language?.trim()?.lowercase()?.ifBlank { null } ?: "en"
    require(SupertonicLanguages.isValid(normalized)) {
        "Invalid Supertonic language: $normalized. Available: ${SupertonicLanguages.available.joinToString()}"
    }
    return normalized
}

internal fun loadConfig(modelDir: File): SupertonicConfig {
    val root = JsonParser.parseReader(FileReader(File(modelDir, "tts.json"))).asJsonObject
    val ae = root.getAsJsonObject("ae")
    val ttl = root.getAsJsonObject("ttl")
    return SupertonicConfig(
        sampleRate = ae.get("sample_rate").asInt,
        baseChunkSize = ae.get("base_chunk_size").asInt,
        chunkCompress = ttl.get("chunk_compress_factor").asInt,
        latentDim = ttl.get("latent_dim").asInt
    )
}

internal fun createFloatTensor(arr: Array<Array<FloatArray>>, env: OrtEnvironment): OnnxTensor {
    val shape = longArrayOf(arr.size.toLong(), arr[0].size.toLong(), arr[0][0].size.toLong())
    val flat = FloatArray(arr.size * arr[0].size * arr[0][0].size)
    var idx = 0
    for (b in arr.indices) for (d in arr[b].indices) for (t in arr[b][d].indices) {
        flat[idx++] = arr[b][d][t]
    }
    return OnnxTensor.createTensor(env, FloatBuffer.wrap(flat), shape)
}

internal fun createFloatTensor(arr: Array<FloatArray>, env: OrtEnvironment): OnnxTensor {
    val shape = longArrayOf(arr.size.toLong(), arr[0].size.toLong())
    val flat = FloatArray(arr.size * arr[0].size)
    var idx = 0
    for (b in arr.indices) for (i in arr[b].indices) flat[idx++] = arr[b][i]
    return OnnxTensor.createTensor(env, FloatBuffer.wrap(flat), shape)
}

internal fun createLongTensor(arr: Array<LongArray>, env: OrtEnvironment): OnnxTensor {
    val shape = longArrayOf(arr.size.toLong(), arr[0].size.toLong())
    val flat = LongArray(arr.size * arr[0].size)
    var idx = 0
    for (b in arr.indices) for (i in arr[b].indices) flat[idx++] = arr[b][i]
    return OnnxTensor.createTensor(env, LongBuffer.wrap(flat), shape)
}

internal class UnicodeProcessor(indexerFile: File) {
    private val indexer: LongArray = loadJsonLongArray(indexerFile)

    fun process(textList: List<String>): TextProcessResult {
        val processed = textList.map { preprocess(it) }
        val lengths = processed.map { it.length }
        val maxLen = lengths.maxOrNull() ?: 0
        val textIds = Array(processed.size) { LongArray(maxLen) }
        processed.forEachIndexed { idx, text ->
            val unicodeVals = text.codePoints().toArray()
            for (j in unicodeVals.indices) {
                val mapped = unicodeVals[j].coerceIn(0, indexer.lastIndex)
                textIds[idx][j] = indexer[mapped]
            }
        }
        val mask = getTextMask(lengths.toIntArray())
        return TextProcessResult(textIds, mask)
    }

    private fun preprocess(text: String): String {
        var t = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKD)
        t = t.replace("\u2013", "-")
            .replace("\u2014", "-")
            .replace("\u2015", "-")
            .replace("\u00af", " ")
            .replace("_", " ")
        t = t.replace("\u201c", "\"")
            .replace("\u201d", "\"")
            .replace("\u2018", "'")
            .replace("\u2019", "'")
        t = t.replace(Regex("[\u0300-\u036f]"), "")
        t = t.replace(Regex("\\s+"), " ").trim()
        if (!t.endsWith(".") && !t.endsWith("!") && !t.endsWith("?")) {
            t += "."
        }
        return t
    }

    private fun getTextMask(lengths: IntArray): Array<Array<FloatArray>> {
        val maxLen = lengths.maxOrNull() ?: 0
        return Array(lengths.size) { b ->
            Array(1) {
                FloatArray(maxLen) { idx -> if (idx < lengths[b]) 1f else 0f }
            }
        }
    }

    data class TextProcessResult(
        val textIds: Array<LongArray>,
        val textMask: Array<Array<FloatArray>>
    )
}

internal fun loadJsonLongArray(file: File): LongArray {
    val root = JsonParser.parseReader(FileReader(file)).asJsonArray
    val arr = LongArray(root.size())
    for (i in 0 until root.size()) arr[i] = root[i].asLong
    return arr
}

internal inline fun <T : AutoCloseable, R> T.use(block: (T) -> R): R {
    var thrown: Throwable? = null
    try {
        return block(this)
    } catch (t: Throwable) {
        thrown = t
        throw t
    } finally {
        try {
            this.close()
        } catch (closeEx: Throwable) {
            if (thrown == null) throw closeEx
        }
    }
}

fun loadSupertonicStyle(paths: List<File>, env: OrtEnvironment = OrtEnvironment.getEnvironment(), verbose: Boolean = false): SupertonicStyle {
    require(paths.isNotEmpty()) { "No style files provided" }
    val first = JsonParser.parseReader(FileReader(paths.first())).asJsonObject
    val ttlDims = first.getAsJsonObject("style_ttl").getAsJsonArray("dims")
    val dpDims = first.getAsJsonObject("style_dp").getAsJsonArray("dims")
    val ttlDim1 = ttlDims[1].asLong
    val ttlDim2 = ttlDims[2].asLong
    val dpDim1 = dpDims[1].asLong
    val dpDim2 = dpDims[2].asLong
    val bsz = paths.size.toLong()

    val ttlFlat = FloatArray((bsz * ttlDim1 * ttlDim2).toInt())
    val dpFlat = FloatArray((bsz * dpDim1 * dpDim2).toInt())

    paths.forEachIndexed { i, path ->
        val root = JsonParser.parseReader(FileReader(path)).asJsonObject
        var offset = (i * ttlDim1 * ttlDim2).toInt()
        val ttlData = root.getAsJsonObject("style_ttl").getAsJsonArray("data")
        ttlData.forEach { batch ->
            batch.asJsonArray.forEach { row ->
                row.asJsonArray.forEach { v ->
                    ttlFlat[offset++] = v.asFloat
                }
            }
        }

        offset = (i * dpDim1 * dpDim2).toInt()
        val dpData = root.getAsJsonObject("style_dp").getAsJsonArray("data")
        dpData.forEach { batch ->
            batch.asJsonArray.forEach { row ->
                row.asJsonArray.forEach { v ->
                    dpFlat[offset++] = v.asFloat
                }
            }
        }
    }

    val ttlShape = longArrayOf(bsz, ttlDim1, ttlDim2)
    val dpShape = longArrayOf(bsz, dpDim1, dpDim2)
    val ttlTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(ttlFlat), ttlShape)
    val dpTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(dpFlat), dpShape)
    if (verbose) {
        android.util.Log.d("Supertonic", "Loaded ${paths.size} voice styles")
    }
    return SupertonicStyle(ttlTensor, dpTensor)
}

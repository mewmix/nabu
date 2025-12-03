package com.example.nabu.supertonic

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileReader
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/** Lightweight Kotlin port of the Supertonic Java demo. */
class SupertonicEngine(
    private val modelDir: File,
    env: OrtEnvironment = OrtEnvironment.getEnvironment()
) : AutoCloseable {
    private val config: SupertonicConfig = loadConfig(modelDir)
    private val textProcessor = UnicodeProcessor(File(modelDir, "unicode_indexer.json"))
    private val dpSession: OrtSession
    private val textEncSession: OrtSession
    private val vectorEstSession: OrtSession
    private val vocoderSession: OrtSession

    init {
        val opts = OrtSession.SessionOptions()
        dpSession = env.createSession(File(modelDir, "duration_predictor.onnx").absolutePath, opts)
        textEncSession = env.createSession(File(modelDir, "text_encoder.onnx").absolutePath, opts)
        vectorEstSession = env.createSession(File(modelDir, "vector_estimator.onnx").absolutePath, opts)
        vocoderSession = env.createSession(File(modelDir, "vocoder.onnx").absolutePath, opts)
    }

    suspend fun synthesize(
        text: String,
        style: SupertonicStyle,
        totalStep: Int = 5,
        speed: Float = 1.05f,
        rng: Random = Random.Default,
        env: OrtEnvironment = OrtEnvironment.getEnvironment()
    ): SupertonicResult = synthesize(listOf(text), style, totalStep, speed, rng, env)

    suspend fun synthesize(
        texts: List<String>,
        style: SupertonicStyle,
        totalStep: Int = 5,
        speed: Float = 1.05f,
        rng: Random = Random.Default,
        env: OrtEnvironment = OrtEnvironment.getEnvironment()
    ): SupertonicResult = withContext(Dispatchers.Default) {
        require(texts.isNotEmpty()) { "No input text" }
        val bsz = texts.size

        val textResult = textProcessor.process(texts)
        val textIdsTensor = createLongTensor(textResult.textIds, env)
        val textMaskTensor = createFloatTensor(textResult.textMask, env)

        val dpInputs = mapOf(
            "text_ids" to textIdsTensor,
            "style_dp" to style.dpTensor,
            "text_mask" to textMaskTensor
        )
        val duration = dpSession.run(dpInputs).use { res ->
            val value = res[0].value
            val raw = when (value) {
                is Array<*> -> (value as Array<FloatArray>)[0]
                is FloatArray -> value
                else -> throw IllegalStateException("Unexpected duration type ${value?.javaClass}")
            }
            raw.copyOf().also { arr ->
                for (i in arr.indices) arr[i] = arr[i] / speed
            }
        }

        val textEncInputs = mapOf(
            "text_ids" to textIdsTensor,
            "style_ttl" to style.ttlTensor,
            "text_mask" to textMaskTensor
        )
        val textEncResult = textEncSession.run(textEncInputs)
        val textEmbTensor = textEncResult[0] as OnnxTensor

        val latent = sampleNoisyLatent(duration, rng)
        val totalStepTensor = OnnxTensor.createTensor(env, FloatArray(bsz) { totalStep.toFloat() })

        var xt = latent.noisy
        val latentMask = latent.mask
        repeat(totalStep) { step ->
            val currentStepTensor = OnnxTensor.createTensor(env, FloatArray(bsz) { step.toFloat() })
            val noisyLatentTensor = createFloatTensor(xt, env)
            val latentMaskTensor = createFloatTensor(latentMask, env)
            val textMaskTensor2 = createFloatTensor(textResult.textMask, env)

            val vectorInputs = mapOf(
                "noisy_latent" to noisyLatentTensor,
                "text_emb" to textEmbTensor,
                "style_ttl" to style.ttlTensor,
                "latent_mask" to latentMaskTensor,
                "text_mask" to textMaskTensor2,
                "current_step" to currentStepTensor,
                "total_step" to totalStepTensor
            )
            xt = vectorEstSession.run(vectorInputs).use { res ->
                @Suppress("UNCHECKED_CAST")
                val out = res[0].value as Array<Array<FloatArray>>
                out
            }

            currentStepTensor.close()
            noisyLatentTensor.close()
            latentMaskTensor.close()
            textMaskTensor2.close()
        }

        val finalLatentTensor = createFloatTensor(xt, env)
        val wavBatch = vocoderSession.run(mapOf("latent" to finalLatentTensor)).use { res ->
            res[0].value as Array<FloatArray>
        }

        textIdsTensor.close()
        textMaskTensor.close()
        textEncResult.close()
        totalStepTensor.close()
        finalLatentTensor.close()

        val wav = wavBatch.getOrNull(0) ?: FloatArray(0)
        SupertonicResult(wav = wav, duration = duration, sampleRate = config.sampleRate)
    }

    override fun close() {
        dpSession.close()
        textEncSession.close()
        vectorEstSession.close()
        vocoderSession.close()
    }

    private fun sampleNoisyLatent(duration: FloatArray, rng: Random): LatentBundle {
        val bsz = duration.size
        var maxDur = 0f
        for (d in duration) maxDur = max(maxDur, d)

        val wavLenMax = (maxDur * config.sampleRate).toLong()
        val wavLengths = LongArray(bsz) { (duration[it] * config.sampleRate).toLong() }

        val chunkSize = config.baseChunkSize * config.chunkCompress
        val latentLen = ((wavLenMax + chunkSize - 1) / chunkSize).toInt()
        val latentDim = config.latentDim * config.chunkCompress

        val noisy = Array(bsz) { Array(latentDim) { FloatArray(latentLen) } }
        for (b in 0 until bsz) {
            for (d in 0 until latentDim) {
                for (t in 0 until latentLen) {
                    val u1 = max(1e-10f, rng.nextFloat())
                    val u2 = rng.nextFloat()
                    noisy[b][d][t] = (sqrt(-2.0f * kotlin.math.ln(u1)) * kotlin.math.cos(2.0f * Math.PI.toFloat() * u2))
                }
            }
        }

        val mask = latentMask(wavLengths)
        for (b in 0 until bsz) {
            for (d in 0 until latentDim) {
                for (t in noisy[b][d].indices) {
                    noisy[b][d][t] *= mask[b][0][t]
                }
            }
        }

        return LatentBundle(noisy, mask)
    }

    private fun latentMask(wavLengths: LongArray): Array<Array<FloatArray>> {
        val latentSize = config.baseChunkSize.toLong() * config.chunkCompress.toLong()
        val latentLengths = LongArray(wavLengths.size) { (wavLengths[it] + latentSize - 1) / latentSize }
        val maxLen = latentLengths.maxOrNull() ?: 0

        return Array(wavLengths.size) { b ->
            Array(1) {
                FloatArray(maxLen.toInt()) { idx ->
                    val active = if (idx < latentLengths[b]) 1f else 0f
                    if (idx == 0) active else min(1f, active)
                }
            }
        }
    }

    companion object {
        fun loadStyle(paths: List<File>, env: OrtEnvironment = OrtEnvironment.getEnvironment(), verbose: Boolean = false): SupertonicStyle {
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
    }
}

data class SupertonicResult(val wav: FloatArray, val duration: FloatArray, val sampleRate: Int)

data class SupertonicConfig(
    val sampleRate: Int,
    val baseChunkSize: Int,
    val chunkCompress: Int,
    val latentDim: Int
)

data class LatentBundle(
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

private fun loadConfig(modelDir: File): SupertonicConfig {
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

private fun createFloatTensor(arr: Array<Array<FloatArray>>, env: OrtEnvironment): OnnxTensor {
    val shape = longArrayOf(arr.size.toLong(), arr[0].size.toLong(), arr[0][0].size.toLong())
    val flat = FloatArray(arr.size * arr[0].size * arr[0][0].size)
    var idx = 0
    for (b in arr.indices) for (d in arr[b].indices) for (t in arr[b][d].indices) {
        flat[idx++] = arr[b][d][t]
    }
    return OnnxTensor.createTensor(env, FloatBuffer.wrap(flat), shape)
}

private fun createFloatTensor(arr: Array<FloatArray>, env: OrtEnvironment): OnnxTensor {
    val shape = longArrayOf(arr.size.toLong(), arr[0].size.toLong())
    val flat = FloatArray(arr.size * arr[0].size)
    var idx = 0
    for (b in arr.indices) for (i in arr[b].indices) flat[idx++] = arr[b][i]
    return OnnxTensor.createTensor(env, FloatBuffer.wrap(flat), shape)
}

private fun createLongTensor(arr: Array<LongArray>, env: OrtEnvironment): OnnxTensor {
    val shape = longArrayOf(arr.size.toLong(), arr[0].size.toLong())
    val flat = LongArray(arr.size * arr[0].size)
    var idx = 0
    for (b in arr.indices) for (i in arr[b].indices) flat[idx++] = arr[b][i]
    return OnnxTensor.createTensor(env, LongBuffer.wrap(flat), shape)
}

private class UnicodeProcessor(indexerFile: File) {
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
        t = t.replace("–", "-")
            .replace("‑", "-")
            .replace("—", "-")
            .replace("¯", " ")
            .replace("_", " ")
        t = t.replace("\u201C", "\"")
            .replace("\u201D", "\"")
            .replace("\u2018", "'")
            .replace("\u2019", "'")
        t = t.replace(Regex("[\\u0300-\\u036f]"), "")
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

private fun loadJsonLongArray(file: File): LongArray {
    val root = JsonParser.parseReader(FileReader(file)).asJsonArray
    val arr = LongArray(root.size())
    for (i in 0 until root.size()) arr[i] = root[i].asLong
    return arr
}

private inline fun <T : AutoCloseable, R> T.use(block: (T) -> R): R {
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

package com.mewmix.nabu.supertonic

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.mewmix.nabu.tts.AudioResult
import com.mewmix.nabu.tts.TTSEngine
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

/** 
 * Engine specifically for Supertonic 2 to handle stereo/double-speech issues.
 * Based on SupertonicEngine.
 */
class Supertonic2Engine(
    private val modelDir: File,
    env: OrtEnvironment = OrtEnvironment.getEnvironment()
) : AutoCloseable, ISupertonicEngine {
    private val config: SupertonicConfig = loadConfig(modelDir)
    override val sampleRate: Int
        get() = config.sampleRate
    override val name: String = "Supertonic 2"
    override val provider: String = "CPU"
    private val textProcessor = Supertonic2UnicodeProcessor(File(modelDir, "unicode_indexer.json"))
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

    override suspend fun synthesize(text: String, speed: Float): AudioResult {
        throw UnsupportedOperationException("Supertonic2Engine requires a style. Use the specific synthesize method.")
    }

    override suspend fun synthesize(
        text: String,
        style: SupertonicStyle,
        totalStep: Int,
        speed: Float,
        rng: Random,
        env: OrtEnvironment
    ): SupertonicResult = synthesize(listOf(text), style, totalStep, speed, rng, env)

    override suspend fun synthesize(
        texts: List<String>,
        style: SupertonicStyle,
        totalStep: Int,
        speed: Float,
        rng: Random,
        env: OrtEnvironment
    ): SupertonicResult = withContext(Dispatchers.Default) {
        require(texts.isNotEmpty()) { "No input text" }
        val bsz = texts.size

        val textResult = textProcessor.process(texts, "en")
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

        var wav = wavBatch.getOrNull(0) ?: FloatArray(0) 
        
        // Supertonic 2 fix: Check for double speech/stereo artifact
        // If the output is interleaved stereo, we downmix it.
        // Assuming Supertonic 2 vocoder outputs interleaved [L, R, L, R...]
        // We detect this if we want, or just force it for Supertonic 2.
        // For now, let's implement a simple downmix assuming it is stereo.
        // Since we can't be 100% sure without the config, we rely on the user report.
        
        // However, if the duration calculation is correct, the wav length should match duration * sampleRate.
        // If wav length is roughly 2x duration * sampleRate, it's stereo.
        val expectedSamples = (duration.sum() * config.sampleRate).toInt()
        if (wav.size > expectedSamples * 1.8) {
             // Likely stereo (2 channels). Downmix.
             val mono = FloatArray(wav.size / 2)
             for (i in mono.indices) {
                 val left = wav[i * 2]
                 val right = wav[i * 2 + 1]
                 mono[i] = (left + right) / 2f
             }
             wav = mono
        }

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
}

private class Supertonic2UnicodeProcessor(indexerFile: File) {
    private val indexer: LongArray = loadJsonLongArray(indexerFile)

    fun process(textList: List<String>, lang: String): UnicodeProcessor.TextProcessResult {
        val processed = textList.map { preprocess(it, lang) }
        val unicodeVals = processed.map { it.codePoints().toArray() }
        val lengths = unicodeVals.map { it.size }
        val maxLen = lengths.maxOrNull() ?: 0
        val textIds = Array(processed.size) { LongArray(maxLen) }
        unicodeVals.forEachIndexed { idx, vals ->
            for (j in vals.indices) {
                val mapped = vals[j].coerceIn(0, indexer.lastIndex)
                textIds[idx][j] = indexer[mapped]
            }
        }
        val mask = getTextMask(lengths.toIntArray())
        return UnicodeProcessor.TextProcessResult(textIds, mask)
    }

    private fun preprocess(text: String, lang: String): String {
        var t = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKD)
        t = removeEmojis(t)
        val replacements = mapOf(
            "\u2013" to "-",
            "\u2014" to "-",
            "\u2015" to "-",
            "\u2011" to "-",
            "_" to " ",
            "\u201c" to "\"",
            "\u201d" to "\"",
            "\u2018" to "'",
            "\u2019" to "'",
            "\u00b4" to "'",
            "`" to "'",
            "[" to " ",
            "]" to " ",
            "|" to " ",
            "/" to " ",
            "#" to " ",
            "\u2192" to " ",
            "\u2190" to " "
        )
        replacements.forEach { (from, to) -> t = t.replace(from, to) }
        t = t.replace(Regex("[\u2665\u2606\u2661\u00a9\\\\]"), "")
        t = t.replace("@", " at ")
            .replace("e.g.,", "for example, ")
            .replace("i.e.,", "that is, ")
        t = t.replace(" ,", ",")
            .replace(" .", ".")
            .replace(" !", "!")
            .replace(" ?", "?")
            .replace(" ;", ";")
            .replace(" :", ":")
            .replace(" '", "'")
        while (t.contains("\"\"")) t = t.replace("\"\"", "\"")
        while (t.contains("''")) t = t.replace("''", "'")
        while (t.contains("``")) t = t.replace("``", "`")
        t = t.replace(Regex("\\s+"), " ").trim()
        if (!t.matches(Regex(".*[.!?;:,'\"\\u201C\\u201D\\u2018\\u2019)\\]}…。」』】〉》›»]$"))) {
            t += "."
        }
        return "<${lang}>${t}</${lang}>"
    }

    private fun removeEmojis(text: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val codePoint = Character.codePointAt(text, i)
            val isEmoji = (codePoint in 0x1F600..0x1F64F) ||
                (codePoint in 0x1F300..0x1F5FF) ||
                (codePoint in 0x1F680..0x1F6FF) ||
                (codePoint in 0x1F700..0x1F77F) ||
                (codePoint in 0x1F780..0x1F7FF) ||
                (codePoint in 0x1F800..0x1F8FF) ||
                (codePoint in 0x1F900..0x1F9FF) ||
                (codePoint in 0x1FA00..0x1FA6F) ||
                (codePoint in 0x1FA70..0x1FAFF) ||
                (codePoint in 0x2600..0x26FF) ||
                (codePoint in 0x2700..0x27BF) ||
                (codePoint in 0x1F1E6..0x1F1FF)
            if (!isEmoji) {
                out.appendCodePoint(codePoint)
            }
            i += Character.charCount(codePoint)
        }
        return out.toString()
    }

    private fun getTextMask(lengths: IntArray): Array<Array<FloatArray>> {
        val maxLen = lengths.maxOrNull() ?: 0
        return Array(lengths.size) { b ->
            Array(1) { FloatArray(maxLen) { idx -> if (idx < lengths[b]) 1f else 0f } }
        }
    }
}

package com.mewmix.nabu.soprano

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import com.mewmix.nabu.tts.AudioResult
import com.mewmix.nabu.tts.TTSEngine
import com.mewmix.nabu.utils.DebugLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.Collections
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.exp
import kotlin.random.Random

data class SopranoSamplingConfig(
    val temperature: Float = 0.3f,
    val topK: Int = 50,
    val topP: Float = 0.95f,
    val repetitionPenalty: Float = 1.2f
)

class SopranoEngine(
    private val modelDir: File,
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
) : TTSEngine {

    private val backboneSession: OrtSession
    private val decoderSession: OrtSession
    private val tokenizer: SopranoTokenizer
    private var kvKeyNames: List<String> = emptyList()
    private var kvValNames: List<String> = emptyList()

    // Constants
    private val SAMPLE_RATE = 32000
    private val RECEPTIVE_FIELD = 4
    private val TOKEN_SIZE = 2048
    private val MIN_TOKENS_BEFORE_STOP = 32
    // These are discovered from model IO at runtime
    private var kvDimHead: Long = 128
    private var kvNumHeads: Long = 1
    private var numLayers: Int = 17
    private var vocabSize: Int = 8192
    private var stopTokenId: Long = 3L
    @Volatile private var samplingConfig: SopranoSamplingConfig = SopranoSamplingConfig()
    private val MAX_NEW_TOKENS = 320
    private val TARGET_CHUNK_SIZE = 8

    init {
        val backboneFile = File(modelDir, "soprano_backbone_kv.onnx")
        val decoderFile = File(modelDir, "soprano_decoder.onnx")
        // Decoder external data is usually handled automatically by ORT if in same dir

        if (!backboneFile.exists() || !decoderFile.exists()) {
            throw IllegalStateException("Soprano ONNX files missing in ${modelDir.absolutePath}")
        }

        val options = OrtSession.SessionOptions()
        // options.addFreeDimensionOverride("batch", 1) // Not always available in Java API, but standard

        backboneSession = env.createSession(backboneFile.absolutePath, options)
        decoderSession = env.createSession(decoderFile.absolutePath, options)
        tokenizer = SopranoTokenizer(modelDir)

        Log.i("SopranoEngine", "Initialized Soprano Engine from $modelDir")
        DebugLogger.log("SopranoEngine: init starting sessions at ${modelDir.absolutePath}")
        try {
            val inNames = backboneSession.inputNames.joinToString()
            val outNames = backboneSession.outputNames.joinToString()
            val decIn = decoderSession.inputNames.joinToString()
            val decOut = decoderSession.outputNames.joinToString()
            DebugLogger.log("SopranoEngine: backbone inputs=[$inNames] outputs=[$outNames]")
            DebugLogger.log("SopranoEngine: decoder inputs=[$decIn] outputs=[$decOut]")
            // Discover KV cache dims and layer count from backbone input info
            runCatching {
                // Discover KV input names and shapes from model inputs
                val inputs = backboneSession.inputNames
                val kvGroups = inputs.mapNotNull { name ->
                    val m = Regex("(.+)\\.(key|value)$").find(name)
                    if (m != null && name.contains("past") ) {
                        val prefix = m.groupValues[1]
                        val kind = m.groupValues[2]
                        Triple(prefix, kind, name)
                    } else null
                }.groupBy({ it.first }, { it })

                if (kvGroups.isNotEmpty()) {
                    val keys = mutableListOf<String>()
                    val vals = mutableListOf<String>()
                    val orderedPrefixes = kvGroups.keys.sortedWith(
                        compareBy<String> { prefix ->
                            Regex("past_key_values\\.(\\d+)$").find(prefix)?.groupValues?.get(1)?.toIntOrNull()
                                ?: Int.MAX_VALUE
                        }.thenBy { it }
                    )
                    orderedPrefixes.forEach { prefix ->
                        val triples = kvGroups[prefix].orEmpty()
                        val keyName = triples.firstOrNull { it.second == "key" }?.third
                        val valName = triples.firstOrNull { it.second == "value" }?.third
                        if (keyName != null && valName != null) {
                            keys.add(keyName)
                            vals.add(valName)
                        }
                    }
                    if (keys.isNotEmpty() && vals.size == keys.size) {
                        kvKeyNames = keys
                        kvValNames = vals
                        numLayers = keys.size
                        DebugLogger.log(
                            "SopranoEngine: KV order keys=${kvKeyNames.joinToString(limit = 6, truncated = "...")}"
                        )
                        // Inspect shape from the first key input
                        val tInfo = backboneSession.inputInfo[keys.first()]?.info as? ai.onnxruntime.TensorInfo
                        val shape = tInfo?.shape
                        if (shape != null && shape.size >= 4) {
                            kvNumHeads = if (shape[1] > 0) shape[1] else kvNumHeads
                            kvDimHead = if (shape[3] > 0) shape[3] else kvDimHead
                        }
                    }
                }
            }.onFailure { e -> DebugLogger.log("SopranoEngine: KV discovery failed: ${e.message}") }
            // Discover STOP token id if available
            tokenizer.idFor("[STOP]")?.let { stopTokenId = it.toLong() }
            DebugLogger.log("SopranoEngine: layers=$numLayers heads=$kvNumHeads headDim=$kvDimHead stopId=$stopTokenId")
        } catch (_: Exception) {
            // best-effort logging only
        }
    }

    override val sampleRate: Int = SAMPLE_RATE
    override val name: String = "Soprano"
    override val provider: String = "ONNX/CPU"

    val currentSamplingConfig: SopranoSamplingConfig
        get() = samplingConfig

    fun updateSamplingConfig(config: SopranoSamplingConfig) {
        samplingConfig = config
    }

    override suspend fun synthesize(text: String, speed: Float): AudioResult = withContext(Dispatchers.Default) {
        DebugLogger.log("SopranoEngine.synthesize: textLen=${text.length} speed=$speed")
        // Match the reference web pipeline: clean + prompt batching with [STOP][TEXT]...[START].
        val prompts = preprocessText(text)

        val fullAudio = mutableListOf<Float>()

        for (prompt in prompts) {
            DebugLogger.log("Soprano synthesizing prompt: $prompt")
            val audioChunk = generate(prompt)
            fullAudio.addAll(audioChunk.asList())
        }

        val pcmSafe = normalizeForPcm(fullAudio.toFloatArray())
        val out = AudioResult(pcmSafe, SAMPLE_RATE)
        DebugLogger.log("SopranoEngine.synthesize: produced ${out.wav.size} samples @ ${out.sampleRate} Hz")
        out
    }

    private fun normalizeForPcm(audio: FloatArray): FloatArray {
        if (audio.isEmpty()) return audio

        var peak = 0f
        var hasNonFinite = false
        for (sample in audio) {
            if (!sample.isFinite()) {
                hasNonFinite = true
                continue
            }
            val magnitude = abs(sample)
            if (magnitude > peak) peak = magnitude
        }

        val gain = if (peak > 1f) 0.98f / peak else 1f
        if (!hasNonFinite && gain == 1f) return audio

        if (gain < 1f) {
            DebugLogger.log("SopranoEngine.synthesize: normalizing waveform peak=$peak gain=$gain")
        }

        return FloatArray(audio.size) { idx ->
            val sample = audio[idx]
            val finite = if (sample.isFinite()) sample else 0f
            (finite * gain).coerceIn(-1f, 1f)
        }
    }

    private fun preprocessText(text: String, batchSize: Int = 3, minLength: Int = 30): List<String> {
        val cleaned = SopranoTextNormalizer.cleanText(text.trim())
        var sentences = cleaned.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }.toMutableList()
        if (sentences.isEmpty()) {
            return if (cleaned.isNotBlank()) listOf("[STOP][TEXT]${cleaned}[START]") else emptyList()
        }

        if (minLength > 0 && sentences.size > 1) {
            val merged = mutableListOf<String>()
            var i = 0
            while (i < sentences.size) {
                val cur = sentences[i]
                if (cur.length < minLength) {
                    if (merged.isNotEmpty()) {
                        merged[merged.lastIndex] = (merged.last() + " " + cur).trim()
                    } else if (i + 1 < sentences.size) {
                        sentences[i + 1] = (cur + " " + sentences[i + 1]).trim()
                    } else {
                        merged.add(cur)
                    }
                } else {
                    merged.add(cur)
                }
                i++
            }
            sentences = merged
        }

        val prompts = mutableListOf<String>()
        for (idx in sentences.indices step batchSize) {
            val batch = sentences.subList(idx, minOf(idx + batchSize, sentences.size)).joinToString(" ")
            prompts.add("[STOP][TEXT]$batch[START]")
        }
        return prompts
    }

    private data class GenerationOutcome(
        val audio: FloatArray,
        val tokens: Int,
        val stopDetected: Boolean
    )

    private fun generate(prompt: String): FloatArray {
        val maxAttempts = 3
        var bestOutcome: GenerationOutcome? = null
        var bestScore = Float.POSITIVE_INFINITY

        for (attempt in 1..maxAttempts) {
            val outcome = generateOnce(prompt)
            val seconds = outcome.audio.size.toFloat() / SAMPLE_RATE
            val score = generationScore(outcome, seconds)
            if (score < bestScore) {
                bestScore = score
                bestOutcome = outcome
            }

            val needsRetry = !outcome.stopDetected || seconds < 1.5f || seconds > 12f
            if (!needsRetry || attempt == maxAttempts) {
                if (needsRetry) {
                    DebugLogger.log(
                        "SopranoEngine.generate: accepting best outlier after attempt=$attempt " +
                            "stopDetected=${outcome.stopDetected} duration=${"%.2f".format(seconds)}s tokens=${outcome.tokens}"
                    )
                }
                break
            }
            DebugLogger.log(
                "SopranoEngine.generate: retrying attempt=${attempt + 1} " +
                    "stopDetected=${outcome.stopDetected} duration=${"%.2f".format(seconds)}s tokens=${outcome.tokens}"
            )
        }

        return bestOutcome?.audio ?: FloatArray(0)
    }

    private fun generationScore(outcome: GenerationOutcome, seconds: Float): Float {
        var score = kotlin.math.abs(seconds - 6f)
        if (!outcome.stopDetected) {
            score += 100f
        }
        if (seconds < 1.5f) {
            score += (1.5f - seconds) * 10f
        }
        if (seconds > 12f) {
            score += (seconds - 12f) * 10f
        }
        return score
    }

    private fun generateOnce(prompt: String): GenerationOutcome {
        val inputIds = tokenizer.encode(prompt)
        val promptLen = inputIds.size

        val kvCache = mutableMapOf<String, OnnxTensor>()
        if (kvKeyNames.isNotEmpty() && kvValNames.size == kvKeyNames.size) {
            for (i in kvKeyNames.indices) {
                val keyEmpty = OnnxTensor.createTensor(env, FloatBuffer.allocate(0), longArrayOf(1, kvNumHeads, 0, kvDimHead))
                val valEmpty = OnnxTensor.createTensor(env, FloatBuffer.allocate(0), longArrayOf(1, kvNumHeads, 0, kvDimHead))
                kvCache[kvKeyNames[i]] = keyEmpty
                kvCache[kvValNames[i]] = valEmpty
            }
        } else {
            for (i in 0 until numLayers) {
                val keyEmpty = OnnxTensor.createTensor(env, FloatBuffer.allocate(0), longArrayOf(1, kvNumHeads, 0, kvDimHead))
                val valEmpty = OnnxTensor.createTensor(env, FloatBuffer.allocate(0), longArrayOf(1, kvNumHeads, 0, kvDimHead))
                kvCache["past_key_values.$i.key"] = keyEmpty
                kvCache["past_key_values.$i.value"] = valEmpty
            }
        }

        var currentInputIds = OnnxTensor.createTensor(env, LongBuffer.wrap(inputIds), longArrayOf(1, inputIds.size.toLong()))
        val attentionMaskData = LongArray(promptLen + MAX_NEW_TOKENS) { 1L }
        var currentSeqLen = promptLen
        var currentAttentionMask = OnnxTensor.createTensor(env, LongBuffer.wrap(attentionMaskData.sliceArray(0 until currentSeqLen)), longArrayOf(1, currentSeqLen.toLong()))

        val posIds = LongArray(promptLen) { it.toLong() }
        var currentPositionIds = OnnxTensor.createTensor(env, LongBuffer.wrap(posIds), longArrayOf(1, promptLen.toLong()))

        val hiddenStatesBuffer = mutableListOf<FloatArray>()
        val generatedAudio = mutableListOf<Float>()
        var chunkCounter = TARGET_CHUNK_SIZE

        var seenTokenMask = BooleanArray(vocabSize)
        inputIds.forEach { if (it in 0 until vocabSize) seenTokenMask[it.toInt()] = true }

        var activeResult: OrtSession.Result? = null
        var stopDetected = false
        var generatedTokens = 0

        val decoderChannels = runCatching {
            val inputName = decoderSession.inputNames.first()
            val tInfo = decoderSession.inputInfo[inputName]?.info as? ai.onnxruntime.TensorInfo
            val dim = tInfo?.shape?.getOrNull(1) ?: 512L
            if (dim > 0) dim.toInt() else 512
        }.getOrElse { 512 }

        fun safeClose(closeable: AutoCloseable?) {
            runCatching { closeable?.close() }
        }

        try {
            for (step in 0 until MAX_NEW_TOKENS) {
                val inputs = mutableMapOf<String, OnnxTensor>()
                inputs["input_ids"] = currentInputIds
                inputs["attention_mask"] = currentAttentionMask
                inputs["position_ids"] = currentPositionIds
                inputs.putAll(kvCache)

                val outputs = backboneSession.run(inputs)
                if (activeResult != null) {
                    activeResult?.close()
                } else {
                    kvCache.values.forEach { it.close() }
                }
                kvCache.clear()
                activeResult = outputs

                val logitsTensor = outputs[0] as OnnxTensor
                val lastHiddenStateTensor = outputs[outputs.size() - 1] as OnnxTensor

                if (step == 0) {
                    val shape = logitsTensor.info.shape
                    if (shape.size >= 3) {
                        val vsz = shape[2].toInt()
                        if (vsz != vocabSize) {
                            vocabSize = vsz
                            val newMask = BooleanArray(vocabSize)
                            // rebuild from input ids
                            inputIds.forEach { if (it in 0 until vocabSize) newMask[it.toInt()] = true }
                            seenTokenMask = newMask
                            DebugLogger.log("SopranoEngine: vocabSize=$vocabSize")
                        }
                    }
                }

                for (i in 0 until numLayers) {
                    val kName = if (kvKeyNames.isNotEmpty()) kvKeyNames[i] else "past_key_values.$i.key"
                    val vName = if (kvValNames.isNotEmpty()) kvValNames[i] else "past_key_values.$i.value"
                    val kTensor = outputs.get(1 + i * 2) as OnnxTensor
                    val vTensor = outputs.get(2 + i * 2) as OnnxTensor
                    kvCache[kName] = kTensor
                    kvCache[vName] = vTensor
                }

                var nextToken = sample(logitsTensor, seenTokenMask)
                if (nextToken == stopTokenId && generatedTokens < MIN_TOKENS_BEFORE_STOP) {
                    nextToken = sample(logitsTensor, seenTokenMask, blockedTokenId = stopTokenId.toInt())
                }
                val finished = nextToken == stopTokenId
                if (nextToken >= 0 && nextToken < seenTokenMask.size.toLong()) {
                    seenTokenMask[nextToken.toInt()] = true
                }

                val hiddenData = lastHiddenStateTensor.floatBuffer
                val seqLen = lastHiddenStateTensor.info.shape[1]
                val dim = lastHiddenStateTensor.info.shape[2]
                val offset = (seqLen - 1) * dim
                val lastState = FloatArray(dim.toInt())
                hiddenData.position(offset.toInt())
                hiddenData.get(lastState)

                if (step == 0) {
                    DebugLogger.log("SopranoEngine: hiddenDim=${lastState.size} decoderChannels=$decoderChannels")
                }

                if (step > 0 && !finished) {
                    hiddenStatesBuffer.add(lastState)
                }

                val maxSize = 2 * RECEPTIVE_FIELD + TARGET_CHUNK_SIZE
                if (hiddenStatesBuffer.size > maxSize) {
                    val removeCount = hiddenStatesBuffer.size - maxSize
                    repeat(removeCount) { hiddenStatesBuffer.removeAt(0) }
                }

                if (finished || hiddenStatesBuffer.size >= RECEPTIVE_FIELD + TARGET_CHUNK_SIZE) {
                    if (finished || chunkCounter == TARGET_CHUNK_SIZE) {
                        val windowSize = hiddenStatesBuffer.size
                        if (windowSize > 0) {
                            val decoderInputArr = FloatArray(decoderChannels * windowSize)
                            for (w in 0 until windowSize) {
                                val vec = hiddenStatesBuffer[w]
                                for (d in 0 until decoderChannels) {
                                    decoderInputArr[d * windowSize + w] = if (d < vec.size) vec[d] else 0f
                                }
                            }

                            val decoderInputTensor = OnnxTensor.createTensor(
                                env,
                                FloatBuffer.wrap(decoderInputArr),
                                longArrayOf(1, decoderChannels.toLong(), windowSize.toLong())
                            )
                            val decoderOut = decoderSession.run(
                                Collections.singletonMap(decoderSession.inputNames.first(), decoderInputTensor)
                            )
                            val audioTensor = decoderOut.get(0) as OnnxTensor
                            val audioData = audioTensor.floatBuffer
                            val audioArr = FloatArray(audioData.remaining())
                            audioData.get(audioArr)

                            val len = audioArr.size
                            if (finished) {
                                val startIdx = (len - (RECEPTIVE_FIELD + chunkCounter - 1) * TOKEN_SIZE + TOKEN_SIZE).coerceAtLeast(0)
                                for (k in startIdx until len) generatedAudio.add(audioArr[k])
                            } else {
                                val startIdx = (len - (RECEPTIVE_FIELD + TARGET_CHUNK_SIZE) * TOKEN_SIZE + TOKEN_SIZE).coerceAtLeast(0)
                                val endIdx = (len - RECEPTIVE_FIELD * TOKEN_SIZE + TOKEN_SIZE).coerceAtMost(len)
                                if (startIdx < endIdx) {
                                    for (k in startIdx until endIdx) generatedAudio.add(audioArr[k])
                                }
                            }

                            decoderInputTensor.close()
                            audioTensor.close()
                            decoderOut.close()
                            chunkCounter = 0
                        }
                    }
                    chunkCounter++
                }

                if (finished) {
                    stopDetected = true
                    break
                }

                safeClose(currentInputIds)
                safeClose(currentAttentionMask)
                safeClose(currentPositionIds)
                currentInputIds = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(nextToken)), longArrayOf(1, 1))

                currentSeqLen++
                val nextAttnMask = LongArray(currentSeqLen) { 1L }
                currentAttentionMask = OnnxTensor.createTensor(
                    env,
                    LongBuffer.wrap(nextAttnMask),
                    longArrayOf(1, currentSeqLen.toLong())
                )

                currentPositionIds = OnnxTensor.createTensor(
                    env,
                    LongBuffer.wrap(longArrayOf((currentSeqLen - 1).toLong())),
                    longArrayOf(1, 1)
                )
                generatedTokens++
            }
            DebugLogger.log(
                "SopranoEngine.generate: tokens=$generatedTokens stopDetected=$stopDetected samples=${generatedAudio.size}"
            )

        } finally {
            safeClose(currentInputIds)
            safeClose(currentAttentionMask)
            safeClose(currentPositionIds)
            safeClose(activeResult)
            if (activeResult == null) {
                kvCache.values.forEach { safeClose(it) }
            }
        }

        return GenerationOutcome(
            audio = generatedAudio.toFloatArray(),
            tokens = generatedTokens,
            stopDetected = stopDetected
        )
    }

    private data class TokenScore(val score: Float, val tokenId: Int)

    private fun sample(logits: OnnxTensor, seenMask: BooleanArray, blockedTokenId: Int? = null): Long {
        val floatBuffer = logits.floatBuffer
        // Shape [1, 1, Vocab] -> Last step is what we want?
        // Backbone run returns logits for the whole sequence?
        // JS: `const lastStepOffset = (logitsTensor.dims[1] - 1) * vocabSize;`
        // We need the last row.
        val shape = logits.info.shape // [1, SeqLen, Vocab]
        val seqLen = shape[1].toInt()
        val vocabSize = shape[2].toInt()

        val localSampling = samplingConfig
        val temp = localSampling.temperature.coerceAtLeast(0f)
        val topK = localSampling.topK.coerceAtLeast(1)
        val topP = localSampling.topP.coerceIn(0f, 1f)
        val repPenalty = localSampling.repetitionPenalty.coerceAtLeast(0f)

        val k = minOf(topK, vocabSize)
        val invTemp = if (temp > 0f && temp != 1f) 1f / temp else 1f
        val invRepPenalty = if (repPenalty != 0f) 1f / repPenalty else 1f

        val heap = PriorityQueue<TokenScore>(compareBy { it.score }) // min-heap
        val rowStart = (seqLen - 1) * vocabSize
        floatBuffer.position(rowStart)
        for (tokenId in 0 until vocabSize) {
            val raw = floatBuffer.get()
            if (blockedTokenId != null && tokenId == blockedTokenId) {
                continue
            }
            var s = raw * invTemp
            if (seenMask[tokenId] && repPenalty != 1f) {
                s = if (s < 0f) s * repPenalty else s * invRepPenalty
            }

            if (heap.size < k) {
                heap.add(TokenScore(s, tokenId))
            } else {
                val min = heap.peek()
                if (min != null && s > min.score) {
                    heap.poll()
                    heap.add(TokenScore(s, tokenId))
                }
            }
        }

        if (heap.isEmpty()) return 0L

        val top = heap.toMutableList().sortedByDescending { it.score }
        val maxScore = top.first().score.toDouble()
        val expWeights = DoubleArray(top.size)
        var sumExp = 0.0
        for (i in top.indices) {
            val w = exp(top[i].score.toDouble() - maxScore)
            expWeights[i] = w
            sumExp += w
        }

        if (!(sumExp > 0.0) || !sumExp.isFinite()) {
            return top.first().tokenId.toLong()
        }

        var keep = top.size
        var totalWeight = sumExp
        if (topP < 1f) {
            val threshold = topP * sumExp
            var cumulative = 0.0
            for (i in top.indices) {
                cumulative += expWeights[i]
                keep = i + 1
                if (cumulative >= threshold) break
            }
            totalWeight = cumulative
        }

        var r = Random.nextDouble() * totalWeight
        for (i in 0 until keep) {
            r -= expWeights[i]
            if (r <= 0.0) return top[i].tokenId.toLong()
        }
        return top.first().tokenId.toLong()
    }

    override fun close() {
        try {
            decoderSession.close()
        } catch (_: Exception) {}
        try {
            backboneSession.close()
        } catch (_: Exception) {}
        // OrtEnvironment is a singleton; do not close here.
    }
}

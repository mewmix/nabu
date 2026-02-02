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
import kotlin.math.exp
import kotlin.random.Random

class SopranoEngine(
    private val modelDir: File,
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
) : TTSEngine {

    private val backboneSession: OrtSession
    private val decoderSession: OrtSession
    private val tokenizer: SopranoTokenizer

    // Constants
    private val SAMPLE_RATE = 32000
    private val RECEPTIVE_FIELD = 4
    private val TOKEN_SIZE = 2048
    private val HIDDEN_DIM = 128
    private val NUM_LAYERS = 17
    private val VOCAB_SIZE = 8192
    private val MAX_NEW_TOKENS = 512
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
    }

    override val sampleRate: Int = SAMPLE_RATE
    override val name: String = "Soprano"
    override val provider: String = "ONNX/CPU"

    override suspend fun synthesize(text: String, speed: Float): AudioResult = withContext(Dispatchers.Default) {
        // 1. Normalize
        val normalized = SopranoTextNormalizer.cleanText(text)

        // 2. Preprocess (split sentences)
        val prompts = preprocessText(normalized)

        val fullAudio = mutableListOf<Float>()

        for (prompt in prompts) {
            DebugLogger.log("Soprano synthesizing prompt: $prompt")
            val audioChunk = generate(prompt)
            fullAudio.addAll(audioChunk.asList())
        }

        AudioResult(fullAudio.toFloatArray(), SAMPLE_RATE)
    }

    private fun preprocessText(text: String, batchSize: Int = 3, minLength: Int = 30): List<String> {
        val rawSentences = text.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }

        if (rawSentences.isEmpty()) {
            return if (text.isNotBlank()) listOf("[STOP][TEXT]${text}[START]") else emptyList()
        }

        // Merge short sentences
        val sentences = mutableListOf<String>()
        if (minLength > 0 && rawSentences.size > 1) {
            var current = ""
            for (i in rawSentences.indices) {
                val s = rawSentences[i]
                if (current.isEmpty()) {
                    current = s
                } else if (current.length < minLength) {
                    current = "$current $s"
                } else {
                    sentences.add(current)
                    current = s
                }
            }
            if (current.isNotEmpty()) sentences.add(current)
        } else {
            sentences.addAll(rawSentences)
        }

        // Batch
        val prompts = mutableListOf<String>()
        for (i in sentences.indices step batchSize) {
            val batch = sentences.subList(i, minOf(i + batchSize, sentences.size)).joinToString(" ")
            prompts.add("[STOP][TEXT]$batch[START]")
        }

        return prompts
    }

    private fun generate(prompt: String): FloatArray {
        val inputIds = tokenizer.encode(prompt)
        val promptLen = inputIds.size

        // --- KV Cache Management ---
        // Map: "past_key_values.0.key" -> OnnxTensor
        val kvCache = mutableMapOf<String, OnnxTensor>()
        for (i in 0 until NUM_LAYERS) {
            // Initial empty tensor: [1, 1, 0, 128]
            val emptyFloat = FloatBuffer.allocate(0)
            val tensor = OnnxTensor.createTensor(env, emptyFloat, longArrayOf(1, 1, 0, HIDDEN_DIM.toLong()))
            kvCache["past_key_values.$i.key"] = tensor
            kvCache["past_key_values.$i.value"] = tensor // Reuse same empty object for initial map? better copy.
            // Actually reusing same Java wrapper for empty is fine, ORT might complain if closed twice?
            // Safer to create new wrapper. But buffer is 0.
            // Let's create distinct wrappers just in case.
             val tensorV = OnnxTensor.createTensor(env, FloatBuffer.allocate(0), longArrayOf(1, 1, 0, HIDDEN_DIM.toLong()))
             kvCache["past_key_values.$i.value"] = tensorV
        }

        // --- State Variables ---
        var currentInputIds = OnnxTensor.createTensor(env, LongBuffer.wrap(inputIds), longArrayOf(1, inputIds.size.toLong()))
        val attentionMaskData = LongArray(promptLen + MAX_NEW_TOKENS) { 1L }
        var currentSeqLen = promptLen
        var currentAttentionMask = OnnxTensor.createTensor(env, LongBuffer.wrap(attentionMaskData.sliceArray(0 until currentSeqLen)), longArrayOf(1, currentSeqLen.toLong()))

        val posIds = LongArray(promptLen) { it.toLong() }
        var currentPositionIds = OnnxTensor.createTensor(env, LongBuffer.wrap(posIds), longArrayOf(1, promptLen.toLong()))

        val hiddenStatesBuffer = mutableListOf<FloatArray>()
        val generatedAudio = mutableListOf<Float>()
        var chunkCounter = TARGET_CHUNK_SIZE
        var firstChunk = true

        val seenTokenMask = BooleanArray(VOCAB_SIZE)
        inputIds.forEach { if(it in 0 until VOCAB_SIZE) seenTokenMask[it.toInt()] = true }

        var activeResult: OrtSession.Result? = null

        try {
            for (step in 0 until MAX_NEW_TOKENS) {
                // Prepare Inputs
                val inputs = mutableMapOf<String, OnnxTensor>()
                inputs["input_ids"] = currentInputIds
                inputs["attention_mask"] = currentAttentionMask
                inputs["position_ids"] = currentPositionIds
                inputs.putAll(kvCache)

                // Run Backbone
                val outputs = backboneSession.run(inputs)

                // Process Outputs
                // 1. Update KV Cache from outputs
                // Close previous tensors (Inputs)
                if (activeResult != null) {
                    activeResult?.close() // This closes the KVs from previous step
                } else {
                     // First step: close manual tensors
                     kvCache.values.forEach { it.close() }
                }
                kvCache.clear()
                activeResult = outputs

                // Names in outputs
                val outNames = outputs.names
                // Logits is 0, LastHidden is last. Middle are KVs.
                val logitsTensor = outputs.get(0) as OnnxTensor
                val lastHiddenStateTensor = outputs.get(outNames.last()) as OnnxTensor

                // Correct Loop for KV Extraction
                for (i in 0 until NUM_LAYERS) {
                    val kName = "past_key_values.$i.key"
                    val vName = "past_key_values.$i.value"
                    // Find by name in outputs
                    val kTensor = outputs.get(1 + i * 2) as OnnxTensor // Assuming order
                    val vTensor = outputs.get(2 + i * 2) as OnnxTensor
                    kvCache[kName] = kTensor
                    kvCache[vName] = vTensor
                }

                // 2. Sample Next Token
                val nextToken = sample(logitsTensor, seenTokenMask)
                if (nextToken == 3L) { // [STOP]
                    break
                }
                seenTokenMask[nextToken.toInt()] = true

                // 3. Buffer Hidden State
                // Extract last token's hidden state
                val hiddenData = lastHiddenStateTensor.floatBuffer
                // Shape: [1, SeqLen, HiddenDim]
                // We want the last vector
                val seqLen = lastHiddenStateTensor.info.shape[1]
                val dim = lastHiddenStateTensor.info.shape[2] // 128
                val offset = (seqLen - 1) * dim
                val lastState = FloatArray(dim.toInt())
                hiddenData.position(offset.toInt())
                hiddenData.get(lastState)

                if (step > 0) { // Skip first step (prompt processing)? JS: if (i > 0 && !finished)
                     hiddenStatesBuffer.add(lastState)
                }

                // Cleanup current run tensors
                currentInputIds.close()
                currentAttentionMask.close()
                currentPositionIds.close()
                // Do not close logits/hidden if they are part of activeResult which we keep?
                // Actually, Result.close() closes ALL.
                // We can't selectively close logits/hidden easily without affecting Result if Result owns them.
                // But we can just let them be closed when we close activeResult next loop.
                // It adds a bit of memory overhead (logits tensor stays alive for 1 step), but it's safe.

                // 4. Decoder Step (Streaming)
                 if (hiddenStatesBuffer.size >= RECEPTIVE_FIELD + TARGET_CHUNK_SIZE) {
                     if (chunkCounter == TARGET_CHUNK_SIZE) {
                         val windowSize = hiddenStatesBuffer.size
                         // Prepare decoder input: [1, 512, windowSize]
                         // JS: decoderInput[d * currentWindowSize + w] = window[w][d]; (Transpose?)
                         // JS: [1, 512, currentWindowSize]
                         // window[w] is size 128 (hidden dim).
                         // Wait, JS Decoder input is [1, 512, Window].
                         // Hidden dim is 128?
                         // Check JS again: `decoderInput[d * currentWindowSize + w] = window[w][d]`
                         // d goes 0..512? Hidden state is 128?
                         // Ah, decoder takes 512 channels?
                         // "const hiddenDim = 128;" in JS.
                         // But decoder input loops to 512?
                         // Maybe the hidden state is projected or I misread JS.
                         // Re-read JS: `for (let d = 0; d < 512; d++)`
                         // `window[w][d]` - this implies hidden state has 512 dims?
                         // BUT `const hiddenDim = 128;` in `generationLoop`.
                         // `lastHiddenState.dims[2]` is used.
                         // THIS IS A MISMATCH.
                         // In JS: `const hiddenDim = 128;`
                         // BUT `const decoderInput = new Float32Array(512 * currentWindowSize);`
                         // If `window[w]` only has 128 floats, `window[w][129]` is undefined/NaN.
                         // This suggests `hiddenDim` in JS constant might be wrong OR `window` is different.
                         // Wait, `lastHiddenState` comes from backbone.
                         // Backbone output size?
                         // If model is "Soprano-80M", hidden dim might be 512.
                         // Let's assume `lastHiddenState` tensor provides the truth.
                         // We will use `lastState.size` to determine D.

                         val D = lastState.size
                         val decoderInputArr = FloatArray(D * windowSize) // Flattened [1, D, Window] ?
                         // JS: `decoderInput[d * currentWindowSize + w] = window[w][d]`
                         // Matrix: Rows=D, Cols=Window. Column-major or Row-major?
                         // ORT expects flattened tensor. Shape [1, D, Window].
                         // Flat index: `d * Window + w`.
                         // This corresponds to Row-Major if D is Height and Window is Width.

                         for (w in 0 until windowSize) {
                             val vec = hiddenStatesBuffer[w]
                             for (d in 0 until D) {
                                 decoderInputArr[d * windowSize + w] = vec[d]
                             }
                         }

                         val decoderInputTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(decoderInputArr), longArrayOf(1, D.toLong(), windowSize.toLong()))
                         val decoderOut = decoderSession.run(Collections.singletonMap(decoderSession.inputNames.first(), decoderInputTensor))
                         val audioTensor = decoderOut.get(0) as OnnxTensor
                         val audioData = audioTensor.floatBuffer
                         val audioArr = FloatArray(audioData.remaining())
                         audioData.get(audioArr)

                         // Slice Audio
                         // JS: startIdx = audio.length - (RECEPTIVE_FIELD + targetChunkSize) * TOKEN_SIZE + TOKEN_SIZE
                         // JS: endIdx = audio.length - RECEPTIVE_FIELD * TOKEN_SIZE + TOKEN_SIZE
                         val len = audioArr.size
                         val startIdx = len - (RECEPTIVE_FIELD + TARGET_CHUNK_SIZE) * TOKEN_SIZE + TOKEN_SIZE
                         val endIdx = len - RECEPTIVE_FIELD * TOKEN_SIZE + TOKEN_SIZE

                         if (startIdx >= 0 && endIdx <= len) {
                             for (k in startIdx until endIdx) generatedAudio.add(audioArr[k])
                         }

                         decoderInputTensor.close()
                         audioTensor.close()
                         decoderOut.close() // Close result wrapper

                         chunkCounter = 0
                         firstChunk = false
                     }
                     chunkCounter++
                 }

                 // Trim buffer
                 // JS: if (hiddenStatesBuffer.length > 2 * RECEPTIVE_FIELD + targetChunkSize) splice...
                 val maxSize = 2 * RECEPTIVE_FIELD + TARGET_CHUNK_SIZE
                 if (hiddenStatesBuffer.size > maxSize) {
                     // Remove from start: `hiddenStatesBuffer.splice(0, length - maxSize)`
                     val removeCount = hiddenStatesBuffer.size - maxSize
                     repeat(removeCount) { hiddenStatesBuffer.removeAt(0) }
                 }

                 // 5. Prepare Next Step Inputs
                 currentInputIds = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(nextToken)), longArrayOf(1, 1))

                 currentSeqLen++
                 val nextAttnMask = LongArray(currentSeqLen) { 1L }
                 currentAttentionMask = OnnxTensor.createTensor(env, LongBuffer.wrap(nextAttnMask), longArrayOf(1, currentSeqLen.toLong()))

                 currentPositionIds = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf((currentSeqLen - 1).toLong())), longArrayOf(1, 1))

            } // End Loop

            // Final Flush (if needed) - JS handles finished state specially
             if (hiddenStatesBuffer.isNotEmpty()) {
                  val windowSize = hiddenStatesBuffer.size
                  val D = hiddenStatesBuffer[0].size
                  val decoderInputArr = FloatArray(D * windowSize)
                  for (w in 0 until windowSize) {
                      val vec = hiddenStatesBuffer[w]
                      for (d in 0 until D) {
                           decoderInputArr[d * windowSize + w] = vec[d]
                      }
                  }
                  val decoderInputTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(decoderInputArr), longArrayOf(1, D.toLong(), windowSize.toLong()))
                  val decoderOut = decoderSession.run(Collections.singletonMap(decoderSession.inputNames.first(), decoderInputTensor))
                  val audioTensor = decoderOut.get(0) as OnnxTensor
                  val audioArr = FloatArray(audioTensor.floatBuffer.remaining())
                  audioTensor.floatBuffer.get(audioArr)

                  // Flush logic from JS:
                  // startIdx = audio.length - (RECEPTIVE_FIELD + chunkCounter - 1) * TOKEN_SIZE + TOKEN_SIZE;
                  val len = audioArr.size
                  // Note: chunkCounter here is whatever it ended at
                  val startIdx = len - (RECEPTIVE_FIELD + chunkCounter - 1) * TOKEN_SIZE + TOKEN_SIZE
                  if (startIdx < len && startIdx >= 0) {
                      for (k in startIdx until len) generatedAudio.add(audioArr[k])
                  }

                  decoderInputTensor.close()
                  audioTensor.close()
                  decoderOut.close()
             }

        } finally {
            // Cleanup final tensors
            currentInputIds.close()
            currentAttentionMask.close()
            currentPositionIds.close()
            activeResult?.close()
            // If activeResult was null (first step fail), kvCache might have manual tensors
            if (activeResult == null) {
                kvCache.values.forEach { it.close() }
            }
        }

        return generatedAudio.toFloatArray()
    }

    private fun sample(logits: OnnxTensor, seenMask: BooleanArray): Long {
        val floatBuffer = logits.floatBuffer
        // Shape [1, 1, Vocab] -> Last step is what we want?
        // Backbone run returns logits for the whole sequence?
        // JS: `const lastStepOffset = (logitsTensor.dims[1] - 1) * vocabSize;`
        // We need the last row.
        val shape = logits.info.shape // [1, SeqLen, Vocab]
        val seqLen = shape[1].toInt()
        val vocabSize = shape[2].toInt()

        val scores = FloatArray(vocabSize)
        floatBuffer.position((seqLen - 1) * vocabSize)
        floatBuffer.get(scores)

        // Settings (Hardcoded for now matching JS defaults)
        val temp = 0.3f
        val topK = 50
        val topP = 0.95f
        val repPenalty = 1.2f

        // Apply Repetition Penalty & Temp
        for (i in scores.indices) {
            var s = scores[i] / temp
            if (seenMask[i]) {
                s = if (s < 0) s * repPenalty else s / repPenalty
            }
            scores[i] = s
        }

        // Top-K
        // Sort indices by score
        val indices = scores.indices.sortedByDescending { scores[it] }
        val k = minOf(topK, vocabSize)
        val topKIndices = indices.take(k)

        // Softmax on topK
        val topKScores = topKIndices.map { scores[it] }
        val maxScore = topKScores.maxOrNull() ?: 0f
        val expScores = topKScores.map { exp(it - maxScore) }
        val sumExp = expScores.sum()

        // Top-P
        var cumulative = 0.0
        val filteredIndices = mutableListOf<Int>()
        val filteredProbs = mutableListOf<Double>()

        for (i in topKIndices.indices) {
            val prob = expScores[i] / sumExp
            cumulative += prob
            filteredIndices.add(topKIndices[i])
            filteredProbs.add(prob)
            if (cumulative >= topP) break
        }

        // Sample
        val rand = Random.nextDouble() * filteredProbs.sum() // Rescale if needed
        var curr = 0.0
        for (i in filteredIndices.indices) {
            curr += filteredProbs[i]
            if (curr >= rand) return filteredIndices[i].toLong()
        }

        return filteredIndices.first().toLong()
    }

    override fun close() {
        backboneSession.close()
        decoderSession.close()
    }
}

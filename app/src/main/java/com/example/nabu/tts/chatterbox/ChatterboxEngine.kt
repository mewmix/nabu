package com.example.nabu.tts.chatterbox

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.example.nabu.utils.DebugLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class ChatterboxEngine(
    private val environment: OrtEnvironment,
    private val tokenizer: LlamaTokenizer,
    private val modelDir: File,
    internal val useNnapi: Boolean,
    private val sampleRate: Int = 24_000,
    private val maxNewTokens: Int = 256,
    private val repetitionPenalty: Float = 1.2f,
) {

    private val loaded = AtomicBoolean(false)

    private lateinit var sessEnc: OrtSession
    private lateinit var sessEmbed: OrtSession
    private lateinit var sessLm: OrtSession
    private lateinit var sessDec: OrtSession

    private val START_SPEECH_TOKEN = 6561L
    private val STOP_SPEECH_TOKEN = 6562L

    private val numHiddenLayers = 30
    private val numKvHeads = 16
    private val headDim = 64

    suspend fun load() = withContext(Dispatchers.IO) {
        if (loaded.get()) return@withContext
        synchronized(this@ChatterboxEngine) {
            if (loaded.get()) return@synchronized
            val onnxDir = File(modelDir, "onnx")
            sessEnc = createSession(File(onnxDir, "speech_encoder.onnx"))
            sessEmbed = createSession(File(onnxDir, "embed_tokens.onnx"))
            sessLm = createSession(File(onnxDir, "language_model.onnx"))
            sessDec = createSession(File(onnxDir, "conditional_decoder.onnx"))
            loaded.set(true)
        }
    }

    suspend fun synthesize(text: String, config: ChatterboxConfig): FloatArray = withContext(Dispatchers.Default) {
        require(loaded.get()) { "Chatterbox engine not loaded" }

        val inputIds = tokenizer.encode(text)
        if (inputIds.isEmpty()) return@withContext FloatArray(0)

        val idsLong = inputIds.map { it.toLong() }.toLongArray()
        val positionIds = LongArray(idsLong.size) { idx ->
            val token = idsLong[idx]
            if (token >= START_SPEECH_TOKEN) 0L else idx.toLong() - 1L
        }

        val exaggeration = floatArrayOf(config.exaggeration.coerceIn(0f, 1f))

        val embedInputs = mutableMapOf<String, OnnxTensor>()
        embedInputs["input_ids"] = ChatterboxOnnx.longTensor(environment, idsLong, longArrayOf(1, idsLong.size.toLong()))
        embedInputs["position_ids"] = ChatterboxOnnx.longTensor(environment, positionIds, longArrayOf(1, positionIds.size.toLong()))
        embedInputs["exaggeration"] = ChatterboxOnnx.floatTensor(environment, exaggeration, longArrayOf(1))

        val embedResult = sessEmbed.run(embedInputs)
        val embedValue = embedResult[0].value as Array<Array<FloatArray>>
        val embeds = embedValue[0].map { it.copyOf() }
        embedResult.close()
        embedInputs.values.forEach { it.close() }

        val referenceFile = config.referenceVoicePath?.takeIf { it.isNotEmpty() }?.let { File(it) }
            ?: File(modelDir, "default_voice.wav")
        val audioValues = WavIO.readMonoFloat(referenceFile, sampleRate)
        val audioTensor = ChatterboxOnnx.floatTensor(environment, audioValues, longArrayOf(1, audioValues.size.toLong()))

        val encoderInputs = mapOf<String, OnnxTensor>("audio_values" to audioTensor)
        val encoderOutputs = sessEnc.run(encoderInputs)
        audioTensor.close()

        val condEmb = (encoderOutputs[0].value as Array<Array<FloatArray>>)
        val promptToken = (encoderOutputs[1].value as Array<LongArray>)[0]
        val refXVector = (encoderOutputs[2].value as Array<FloatArray>)[0]
        val promptFeat = (encoderOutputs[3].value as Array<Array<FloatArray>>)
        encoderOutputs.close()

        val embeddings = ArrayList<FloatArray>()
        condEmb[0].forEach { embeddings.add(it.copyOf()) }
        embeds.forEach { embeddings.add(it) }

        val attentionMask = ArrayList<Long>().apply { repeat(embeddings.size) { add(1L) } }

        val pastKeyValues = ArrayList<Pair<FloatArray, LongArray>>()
        repeat(numHiddenLayers * 2) {
            val shape = longArrayOf(1, numKvHeads.toLong(), 0, headDim.toLong())
            pastKeyValues.add(FloatArray(0) to shape)
        }

        val generated = ArrayList<Long>()
        generated.add(START_SPEECH_TOKEN)

        for (step in 0 until maxNewTokens) {
            val embedTensor = ChatterboxOnnx.floatTensor(
                environment,
                flattenEmbeddings(embeddings),
                longArrayOf(1, embeddings.size.toLong(), embeddings.first().size.toLong())
            )
            val maskArray = LongArray(attentionMask.size) { attentionMask[it] }
            val maskTensor = ChatterboxOnnx.longTensor(environment, maskArray, longArrayOf(1, attentionMask.size.toLong()))

            val inputs = HashMap<String, OnnxTensor>()
            inputs["inputs_embeds"] = embedTensor
            inputs["attention_mask"] = maskTensor

            pastKeyValues.forEachIndexed { index, (data, shape) ->
                val tensor = ChatterboxOnnx.floatTensor(environment, data, shape)
                inputs["past_key_values.${index / 2}.${if (index % 2 == 0) "key" else "value"}"] = tensor
            }

            val lmResult = sessLm.run(inputs)
            inputs.values.forEach { it.close() }

            val logitsArray = (lmResult[0].value as Array<Array<FloatArray>>)
            val logits = logitsArray[0].last()
            val history = LongArray(generated.size) { generated[it] }
            val nextToken = ChatterboxSampler.sample(logits, history, repetitionPenalty).toLong()
            generated.add(nextToken)
            var offset = 1
            repeat(numHiddenLayers) { layer ->
                val keyTensor = lmResult[offset++].value as Array<Array<Array<FloatArray>>>
                val valueTensor = lmResult[offset++].value as Array<Array<Array<FloatArray>>>
                val keyData = TensorUtils.flatten4d(keyTensor)
                val valueData = TensorUtils.flatten4d(valueTensor)
                val keyShape = keyTensor.tensorShape()
                val valueShape = valueTensor.tensorShape()
                pastKeyValues[layer * 2] = keyData to keyShape
                pastKeyValues[layer * 2 + 1] = valueData to valueShape
            }
            lmResult.close()

            if (nextToken == STOP_SPEECH_TOKEN) {
                break
            }

            val position = longArrayOf((step + 1).toLong())
            val stepInputs = mutableMapOf<String, OnnxTensor>()
            stepInputs["input_ids"] = ChatterboxOnnx.longTensor(environment, longArrayOf(nextToken), longArrayOf(1, 1))
            stepInputs["position_ids"] = ChatterboxOnnx.longTensor(environment, position, longArrayOf(1, 1))
            stepInputs["exaggeration"] = ChatterboxOnnx.floatTensor(environment, exaggeration, longArrayOf(1))

            val stepResult = sessEmbed.run(stepInputs)
            stepInputs.values.forEach { it.close() }
            val stepEmbed = (stepResult[0].value as Array<Array<FloatArray>>)[0][0]
            stepResult.close()

            embeddings.add(stepEmbed.copyOf())
            attentionMask.add(1L)
        }

        val generatedTail = if (generated.size > 1) generated.subList(1, generated.size - 1) else emptyList<Long>()
        val speechTokens = LongArray(promptToken.size + generatedTail.size) { idx ->
            if (idx < promptToken.size) promptToken[idx] else generatedTail[idx - promptToken.size]
        }

        val decoderInputs = mutableMapOf<String, OnnxTensor>()
        decoderInputs["speech_tokens"] = ChatterboxOnnx.longTensor(environment, speechTokens, longArrayOf(1, speechTokens.size.toLong()))
        decoderInputs["speaker_embeddings"] = ChatterboxOnnx.floatTensor(environment, refXVector, longArrayOf(1, refXVector.size.toLong()))
        val promptFlat = TensorUtils.flatten3d(promptFeat)
        decoderInputs["speaker_features"] = ChatterboxOnnx.floatTensor(
            environment,
            promptFlat,
            longArrayOf(1, promptFeat[0].size.toLong(), promptFeat[0][0].size.toLong())
        )

        val decoderResult = sessDec.run(decoderInputs)
        decoderInputs.values.forEach { it.close() }
        val wav = (decoderResult[0].value as Array<FloatArray>)[0]
        decoderResult.close()

        wav
    }

    fun sampleRate(): Int = sampleRate

    fun close() {
        if (loaded.get()) {
            sessEnc.close()
            sessEmbed.close()
            sessLm.close()
            sessDec.close()
            loaded.set(false)
        }
    }

    private fun createSession(modelFile: File): OrtSession {
        return OrtSession.SessionOptions().use { options ->
            if (useNnapi) {
                try {
                    options.addNnapi()
                } catch (e: OrtException) {
                    DebugLogger.log("ChatterboxEngine: NNAPI unavailable (${e.message}), falling back to CPU")
                }
            }
            environment.createSession(modelFile.absolutePath, options)
        }
    }

    private fun Array<Array<Array<FloatArray>>>.tensorShape(): LongArray {
        return longArrayOf(
            this.size.toLong(),
            this[0].size.toLong(),
            this[0][0].size.toLong(),
            this[0][0][0].size.toLong()
        )
    }

    private fun flattenEmbeddings(data: List<FloatArray>): FloatArray {
        val cols = data.firstOrNull()?.size ?: 0
        val flat = FloatArray(data.size * cols)
        var index = 0
        for (row in data) {
            for (value in row) {
                flat[index++] = value
            }
        }
        return flat
    }
}

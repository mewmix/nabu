package com.example.nabu.kokoro

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtException
import android.util.Log
import com.example.nabu.tts.AudioResult
import com.example.nabu.tts.TTSEngine
import com.example.nabu.utils.DebugLogger
import java.util.Locale

class KokoroEngine(
    private val bundle: KokoroBundle,
    manifest: Manifest
) : TTSEngine {
    private val tag = "KokoroEngine"
    private val env = OrtFactory.env
    private val session = bundle.session

    private val inputNames = session.inputNames
    private val styleName = inputNames.firstOrNull { it.equals("style", ignoreCase = true) }
    private val speedName = inputNames.firstOrNull { it.equals("speed", ignoreCase = true) }
    private val primaryInput: String = resolvePrimaryInput(manifest)

    override val sampleRate: Int
        get() = bundle.sampleRate

    override suspend fun synthesize(text: String, speed: Float): AudioResult {
         // This needs to tokenize and synth. Kokoro logic is complex and separated.
         // 'synth' takes tokens. We need to look at how KokoroEngine is used to bridge this.
         // It seems KokoroEngine as defined here is low level.
         // We might need to implement synthesize by calling the higher level logic or leave it throwing for now and adapt the caller.
         throw UnsupportedOperationException("KokoroEngine requires tokenization first. Use higher level wrappers.")
    }

    override fun close() {
        // bundle is managed externally usually but we can implement close if needed or leave empty if managed by loader
    }

    fun synth(
        tokens: LongArray,
        style: Array<FloatArray>? = null,
        speed: Float? = null
    ): FloatArray {
        require(tokens.isNotEmpty()) { "Input tokens empty" }
        if (styleName != null && style == null) {
            throw IllegalArgumentException("Model requires style input '$styleName'")
        }
        if (speedName != null && speed == null) {
            throw IllegalArgumentException("Model requires speed input '$speedName'")
        }

        OnnxTensor.createTensor(env, arrayOf(tokens)).use { ids ->
            val styleTensor = if (styleName != null) {
                OnnxTensor.createTensor(env, style!!)
            } else null
            val speedTensor = if (speedName != null) {
                OnnxTensor.createTensor(env, floatArrayOf(speed!!))
            } else null

            try {
                val feeds = mutableMapOf<String, OnnxTensor>()
                feeds[primaryInput] = ids
                styleTensor?.let { feeds[styleName!!] = it }
                speedTensor?.let { feeds[speedName!!] = it }

                session.run(feeds).use { outputs ->
                    val tensor = outputs[0] as OnnxTensor
                    val buffer = tensor.floatBuffer
                    val pcm = FloatArray(buffer.remaining())
                    buffer.get(pcm)
                    return pcm
                }
            } catch (err: OrtException) {
                val styleShape = styleTensor?.info?.shape?.joinToString(prefix = "[", postfix = "]")
                val speedShape = speedTensor?.info?.shape?.joinToString(prefix = "[", postfix = "]")
                val message = buildString {
                    append("Kokoro synth failed ep=${bundle.ep} graph=${bundle.graphId}")
                    append(" tokens=${tokens.size} primary=$primaryInput")
                    styleName?.let { append(" style=$it shape=$styleShape") }
                    speedName?.let { append(" speed=$it shape=$speedShape value=$speed") }
                    append(" error=${err.message}")
                }
                DebugLogger.log(message)
                Log.e(tag, message, err)
                throw err
            } finally {
                styleTensor?.close()
                speedTensor?.close()
            }
        }
    }

    override fun toString(): String =
        "KokoroEngine(ep=${bundle.ep}, graph=${bundle.graphId.lowercase(Locale.US)})"

    private fun resolvePrimaryInput(manifest: Manifest): String {
        val inputsLower = inputNames.associateBy { it.lowercase(Locale.US) }
        val manifestInput = manifest.io.singleGraphInput?.lowercase(Locale.US)
        val styleLower = styleName?.lowercase(Locale.US)
        val speedLower = speedName?.lowercase(Locale.US)

        manifestInput?.let { desired ->
            inputsLower[desired]?.let { return it }
            Log.w(
                "KokoroEngine",
                "Manifest primary input '$desired' missing in graph; falling back to detected input names=$inputNames"
            )
        }

        inputNames.firstOrNull { name ->
            val lower = name.lowercase(Locale.US)
            lower != styleLower && lower != speedLower
        }?.let { return it }

        return inputNames.firstOrNull()
            ?: throw IllegalStateException("ONNX graph exposes no inputs")
    }
}

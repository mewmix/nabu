package com.example.nabu.supertonic

import ai.onnxruntime.OrtEnvironment
import com.example.nabu.tts.AudioResult
import com.example.nabu.tts.TTSEngine
import com.example.nabu.utils.DebugLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.random.Random

class DebugSupertonicEngine(
    private val delegate: SupertonicEngine,
    private val modelDir: File
) : AutoCloseable, TTSEngine {

    private var defaultStyle: SupertonicStyle? = null

    constructor(modelDir: File, env: OrtEnvironment = OrtEnvironment.getEnvironment()) : this(
        SupertonicEngine(modelDir, env),
        modelDir
    )

    init {
        loadDefaultStyle()
    }

    private fun loadDefaultStyle() {
        try {
            val styleFile = File(modelDir, "voice_styles/F1.json")
            if (styleFile.exists()) {
                defaultStyle = SupertonicEngine.loadStyle(listOf(styleFile))
                DebugLogger.log("DebugSupertonicEngine: Loaded default style from ${styleFile.name}")
            } else {
                 DebugLogger.log("DebugSupertonicEngine: Default style not found at ${styleFile.absolutePath}")
            }
        } catch (e: Exception) {
            DebugLogger.log("DebugSupertonicEngine: Failed to load default style: ${e.message}")
        }
    }

    override val sampleRate: Int
        get() = delegate.sampleRate

    override suspend fun synthesize(text: String, speed: Float): AudioResult {
         DebugLogger.log("DebugSupertonicEngine: synthesize called via TTSEngine interface")
         val style = defaultStyle ?: throw IllegalStateException("No style loaded. Cannot synthesize.")

         val result = synthesize(text, style, speed = speed)
         return AudioResult(result.wav, result.sampleRate)
    }

    suspend fun synthesize(
        text: String,
        style: SupertonicStyle,
        totalStep: Int = 5,
        speed: Float = 1.05f,
        rng: Random = Random.Default,
        env: OrtEnvironment = OrtEnvironment.getEnvironment()
    ): SupertonicResult {
        DebugLogger.log("DebugSupertonicEngine: synthesizing text='$text', steps=$totalStep, speed=$speed")
        val start = System.currentTimeMillis()
        return try {
            val result = delegate.synthesize(text, style, totalStep, speed, rng, env)
            val duration = System.currentTimeMillis() - start
            DebugLogger.log("DebugSupertonicEngine: synthesis complete in ${duration}ms. Audio duration: ${result.duration.sum()}s")
            result
        } catch (e: Exception) {
            DebugLogger.log("DebugSupertonicEngine: synthesis failed: ${e.message}")
            throw e
        }
    }

    suspend fun synthesize(
        texts: List<String>,
        style: SupertonicStyle,
        totalStep: Int = 5,
        speed: Float = 1.05f,
        rng: Random = Random.Default,
        env: OrtEnvironment = OrtEnvironment.getEnvironment()
    ): SupertonicResult {
        DebugLogger.log("DebugSupertonicEngine: synthesizing batch size=${texts.size}")
        val start = System.currentTimeMillis()
        return try {
            val result = delegate.synthesize(texts, style, totalStep, speed, rng, env)
            val duration = System.currentTimeMillis() - start
            DebugLogger.log("DebugSupertonicEngine: batch synthesis complete in ${duration}ms")
            result
        } catch (e: Exception) {
            DebugLogger.log("DebugSupertonicEngine: batch synthesis failed: ${e.message}")
            throw e
        }
    }

    override fun close() {
        DebugLogger.log("DebugSupertonicEngine: closing engine")
        delegate.close()
    }
}

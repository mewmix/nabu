package com.mewmix.nabu.supertonic

import ai.onnxruntime.OrtEnvironment
import com.mewmix.nabu.tts.AudioResult
import com.mewmix.nabu.tts.TTSEngine
import com.mewmix.nabu.utils.DebugLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.random.Random

class DebugSupertonicEngine(
    private val delegate: ISupertonicEngine,
    private val modelDir: File,
    private val defaultLanguage: String = "en"
) : AutoCloseable, TTSEngine {

    private var defaultStyle: SupertonicStyle? = null

    constructor(
        modelDir: File,
        env: OrtEnvironment = OrtEnvironment.getEnvironment(),
        defaultLanguage: String = "en"
    ) : this(
        if (modelDir.name.contains("supertonic-2") ||
            modelDir.name.contains("supertonic-3") ||
            File(modelDir, "config.json").exists()
        ) {
            Supertonic2Engine(modelDir, env)
        } else {
            SupertonicEngine(modelDir, env)
        },
        modelDir,
        defaultLanguage
    )

    init {
        loadDefaultStyle()
    }

    private fun loadDefaultStyle() {
        try {
            val styleFile = File(modelDir, "voice_styles/F1.json")
            if (styleFile.exists()) {
                defaultStyle = loadSupertonicStyle(listOf(styleFile))
                DebugLogger.log("DebugSupertonicEngine: Loaded default style from ${styleFile.name}")
            } else {
                 DebugLogger.log("DebugSupertonicEngine: Default style not found at ${styleFile.absolutePath}")
            }
        } catch (e: Exception) {
            DebugLogger.log("DebugSupertonicEngine: Failed to load default style: ${e.message}")
        }
    }

    fun setStyle(styleName: String) {
        try {
            val styleFile = File(modelDir, "voice_styles/$styleName.json")
            if (styleFile.exists()) {
                defaultStyle = loadSupertonicStyle(listOf(styleFile))
                DebugLogger.log("DebugSupertonicEngine: Switched style to $styleName")
            } else {
                DebugLogger.log("DebugSupertonicEngine: Style $styleName not found at ${styleFile.absolutePath}")
            }
        } catch (e: Exception) {
            DebugLogger.log("DebugSupertonicEngine: Failed to set style $styleName: ${e.message}")
        }
    }

    override val sampleRate: Int
        get() = delegate.sampleRate

    override val name: String = "Supertonic"
    override val provider: String = "CPU" // Supertonic currently runs on CPU via ONNX Runtime default or configured env

    override suspend fun synthesize(text: String, speed: Float): AudioResult {
         DebugLogger.log("DebugSupertonicEngine: synthesize called via TTSEngine interface")
         val style = defaultStyle ?: throw IllegalStateException("No style loaded. Cannot synthesize.")

         val result = synthesize(text, defaultLanguage, style, speed = speed)
         return AudioResult(result.wav, result.sampleRate)
    }

    suspend fun synthesize(text: String, speed: Float, language: String): SupertonicResult {
        val style = defaultStyle ?: throw IllegalStateException("No style loaded. Cannot synthesize.")
        return synthesize(text, language, style, speed = speed)
    }

    suspend fun synthesize(
        text: String,
        speed: Float,
        totalStep: Int
    ): SupertonicResult {
        val style = defaultStyle ?: throw IllegalStateException("No style loaded. Cannot synthesize.")
        return synthesize(text, style, totalStep = totalStep, speed = speed)
    }

    suspend fun synthesize(
        text: String,
        style: SupertonicStyle,
        totalStep: Int = 8,
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
        text: String,
        language: String,
        style: SupertonicStyle,
        totalStep: Int = 8,
        speed: Float = 1.05f,
        rng: Random = Random.Default,
        env: OrtEnvironment = OrtEnvironment.getEnvironment()
    ): SupertonicResult {
        DebugLogger.log("DebugSupertonicEngine: synthesizing text='$text', lang=$language, steps=$totalStep, speed=$speed")
        val start = System.currentTimeMillis()
        return try {
            val result = delegate.synthesize(text, language, style, totalStep, speed, rng, env)
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
        totalStep: Int = 8,
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

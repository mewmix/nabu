package com.example.nabu.tts.sherpa

import android.content.Context
import com.example.nabu.tts.TtsEngine
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.getOfflineTtsConfig

/**
 * Adapter between the sherpa-onnx OfflineTts API and the app's [TtsEngine] contract.
 *
 * The engine lazily loads the ONNX model bundle from the app's assets directory and reuses a
 * single [OfflineTts] instance for all synthesis requests.  This keeps initialization cost low
 * while still allowing callers to release resources explicitly via [close].
 */
class SherpaTtsEngine(
    private val context: Context,
    private val assetModelDir: String = DEFAULT_MODEL_DIR,
    private val modelFileName: String = DEFAULT_MODEL_FILE,
    private val lexiconFileName: String? = DEFAULT_LEXICON_FILE,
    private val voiceToSpeakerId: Map<String, Int> = mapOf(DEFAULT_VOICE_NAME to 0),
    private val maxThreads: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
) : TtsEngine {

    private val lock = Any()
    private var offlineTts: OfflineTts? = null

    override fun getAvailableVoices(): List<String> =
        if (voiceToSpeakerId.isEmpty()) listOf(DEFAULT_VOICE_NAME) else voiceToSpeakerId.keys.toList()

    override fun generate(text: String, voice: String, speed: Float): FloatArray {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return FloatArray(0)
        }

        val sid = resolveSpeakerId(voice)
        val targetSpeed = speed.coerceIn(MIN_SPEED, MAX_SPEED)
        val audio = acquireTts().generate(trimmed, sid, targetSpeed)
        return audio.samples
    }

    override fun getSampleRate(): Int = acquireTts().sampleRate()

    override fun close() {
        synchronized(lock) {
            offlineTts?.free()
            offlineTts = null
        }
    }

    private fun resolveSpeakerId(voice: String): Int {
        if (voiceToSpeakerId.isEmpty()) {
            return 0
        }
        return voiceToSpeakerId[voice] ?: voiceToSpeakerId.values.first()
    }

    private fun acquireTts(): OfflineTts {
        synchronized(lock) {
            var instance = offlineTts
            if (instance == null) {
                val config = getOfflineTtsConfig(
                    modelDir = assetModelDir,
                    modelName = modelFileName,
                    acousticModelName = "",
                    vocoder = "",
                    voices = "",
                    lexicon = lexiconFileName.orEmpty(),
                    dataDir = "",
                    dictDir = "",
                    ruleFsts = "",
                    ruleFars = "",
                    numThreads = maxThreads,
                )
                instance = OfflineTts(context.assets, config)
                offlineTts = instance
            }
            return instance
        }
    }

    companion object {
        const val DEFAULT_MODEL_DIR = "sherpa_tts/vits-ljs"
        const val DEFAULT_MODEL_FILE = "vits-ljs.onnx"
        const val DEFAULT_LEXICON_FILE = "lexicon.txt"
        const val DEFAULT_VOICE_NAME = "default"

        private const val MIN_SPEED = 0.2f
        private const val MAX_SPEED = 3.0f
    }
}

package com.mewmix.nabu.voicelab

import ai.onnxruntime.OrtEnvironment
import android.content.Context
import android.os.SystemClock
import com.mewmix.nabu.data.ModelManager
import com.mewmix.nabu.data.ModelType
import com.mewmix.nabu.data.TtsModelValidator
import com.mewmix.nabu.kokoro.ManifestProvider
import com.mewmix.nabu.kokoro.RunEp
import com.mewmix.nabu.soprano.SopranoEngine
import com.mewmix.nabu.soprano.SopranoSamplingConfig
import com.mewmix.nabu.supertonic.DebugSupertonicEngine
import com.mewmix.nabu.supertonic.SupertonicLanguages
import com.mewmix.nabu.supertonic.loadSupertonicStyle
import com.mewmix.nabu.utils.OnnxRuntimeManager
import com.mewmix.nabu.utils.PhonemeConverter
import com.mewmix.nabu.utils.StyleLoader
import com.mewmix.nabu.utils.createAudio
import com.mewmix.nabu.utils.createAudioFromStyleVector
import java.io.File
import kotlin.math.roundToInt

class VoiceLabRepository(
    private val context: Context,
    private val modelManager: ModelManager = ModelManager(context),
    private val phonemeConverter: PhonemeConverter = PhonemeConverter(context),
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val styleLoader = StyleLoader(appContext)
    private var activeSupertonic: Pair<String, DebugSupertonicEngine>? = null
    private var activeSoprano: SopranoEngine? = null

    suspend fun engines(): List<VoiceLabEngineInfo> {
        val kokoro = kokoroInfo()
        val supertonic = modelManager.models
            .filter { it.type == ModelType.TTS && it.id.startsWith("supertonic") }
            .map { model ->
                val modelDir = File(appContext.filesDir, "models/${model.id}")
                val missing = TtsModelValidator.missingFiles(model.id, modelDir)
                val ready = missing.isEmpty() && TtsModelValidator.hasAllRequiredFiles(model.id, modelDir)
                VoiceLabEngineInfo(
                    id = model.id,
                    name = model.name,
                    modelId = model.id,
                    provider = "ONNX/CPU",
                    sampleRate = readSupertonicSampleRate(modelDir),
                    modelSizeBytes = directorySize(modelDir).takeIf { it > 0L },
                    isAvailable = ready,
                    status = when {
                        ready -> "Ready"
                        model.isDownloaded -> "Missing files: ${missing.joinToString()}"
                        else -> "Download required"
                    },
                    parameters = listOf(
                        VoiceLabParameter.FloatValue("speed", "Speed", 1.05f, 0.5f, 2.0f),
                        VoiceLabParameter.IntValue("totalStep", "Steps", 8, 1, 12),
                        VoiceLabParameter.ChoiceValue("language", "Language", "en", SupertonicLanguages.available)
                    )
                )
            }
        val soprano = sopranoInfo()
        return listOf(kokoro) + supertonic + listOf(soprano)
    }

    fun voices(engineId: String): List<VoiceLabVoice> {
        return when {
            engineId == KOKORO_ID -> kokoroVoices()
            engineId.startsWith("supertonic") -> supertonicVoices(engineId)
            engineId == SOPRANO_ID -> listOf(
                VoiceLabVoice(
                    id = "soprano-default",
                    displayName = "Default",
                    engineId = SOPRANO_ID,
                    modelId = SOPRANO_ID,
                    metadata = mapOf("selection" to "No alternate voices exposed by current implementation")
                )
            )
            else -> emptyList()
        }
    }

    suspend fun synthesize(request: VoiceLabRequest): VoiceLabSynthesisResult {
        val renderText = VoiceLabText.renderableTextOrNull(request.text)
            ?: throw IllegalArgumentException("Enter script text before rendering.")
        val renderRequest = request.copy(text = renderText)
        val started = SystemClock.elapsedRealtime()
        return when {
            renderRequest.engineId == KOKORO_ID -> synthesizeKokoro(renderRequest, started)
            renderRequest.engineId.startsWith("supertonic") -> synthesizeSupertonic(renderRequest, started)
            renderRequest.engineId == SOPRANO_ID -> synthesizeSoprano(renderRequest, started)
            else -> throw IllegalArgumentException("Unsupported Voice Lab engine: ${renderRequest.engineId}")
        }
    }

    override fun close() {
        activeSupertonic?.second?.close()
        activeSupertonic = null
        activeSoprano?.close()
        activeSoprano = null
    }

    private suspend fun kokoroInfo(): VoiceLabEngineInfo {
        val manifest = ManifestProvider.kokoroV1()
        val result = OnnxRuntimeManager.initialize(appContext, RunEp.AUTO)
        val status = OnnxRuntimeManager.runtimeStatus()
        return VoiceLabEngineInfo(
            id = KOKORO_ID,
            name = "Kokoro",
            modelId = "kokoro-82m",
            provider = status?.ep?.name ?: "ONNX",
            sampleRate = manifest.sampleRate,
            modelSizeBytes = manifest.files.firstOrNull { it.id == "kokoro_int8" }?.sizeBytes,
            isAvailable = result.isSuccess,
            status = result.fold(
                onSuccess = { "Ready (${status ?: "loaded"})" },
                onFailure = { "Unavailable: ${it.message ?: "initialization failed"}" }
            ),
            parameters = listOf(
                VoiceLabParameter.FloatValue("speed", "Speed", 1.0f, 0.5f, 2.0f)
            )
        )
    }

    private fun sopranoInfo(): VoiceLabEngineInfo {
        val modelDir = File(appContext.filesDir, "models/$SOPRANO_ID")
        val missing = TtsModelValidator.missingFiles(SOPRANO_ID, modelDir)
        val ready = missing.isEmpty() && TtsModelValidator.hasAllRequiredFiles(SOPRANO_ID, modelDir)
        return VoiceLabEngineInfo(
            id = SOPRANO_ID,
            name = "Soprano",
            modelId = SOPRANO_ID,
            provider = "ONNX/CPU",
            sampleRate = 32_000,
            modelSizeBytes = directorySize(modelDir).takeIf { it > 0L },
            isAvailable = ready,
            status = if (ready) "Ready" else "Download required: ${missing.joinToString()}",
            parameters = listOf(
                VoiceLabParameter.FloatValue("temperature", "Temperature", 0.3f, 0f, 2f),
                VoiceLabParameter.IntValue("topK", "Top K", 50, 1, 256),
                VoiceLabParameter.FloatValue("topP", "Top P", 0.95f, 0f, 1f),
                VoiceLabParameter.FloatValue("repetitionPenalty", "Repetition Penalty", 1.2f, 0.5f, 2f)
            )
        )
    }

    private fun kokoroVoices(): List<VoiceLabVoice> {
        return appContext.assets.list("kokoro/voices")
            ?.filter { it.endsWith(".npy") }
            ?.map { file ->
                val id = file.removeSuffix(".npy")
                VoiceLabVoice(
                    id = id,
                    displayName = id,
                    engineId = KOKORO_ID,
                    modelId = "kokoro-82m",
                    metadata = mapOf("asset" to "assets/kokoro/voices/$file")
                )
            }
            ?.sortedBy { it.id }
            ?: emptyList()
    }

    private fun supertonicVoices(engineId: String): List<VoiceLabVoice> {
        val voicesDir = File(appContext.filesDir, "models/$engineId/voice_styles")
        return voicesDir.listFiles { _, name -> name.endsWith(".json") }
            ?.map { file ->
                VoiceLabVoice(
                    id = file.name.removeSuffix(".json"),
                    displayName = file.name.removeSuffix(".json"),
                    engineId = engineId,
                    modelId = engineId,
                    metadata = mapOf("asset" to file.absolutePath)
                )
            }
            ?.sortedBy { it.id }
            ?: emptyList()
    }

    private suspend fun synthesizeKokoro(
        request: VoiceLabRequest,
        started: Long,
    ): VoiceLabSynthesisResult {
        val bundle = OnnxRuntimeManager.initialize(appContext, RunEp.AUTO).getOrThrow()
        val voiceId = request.voiceId ?: kokoroVoices().firstOrNull()?.id
            ?: throw IllegalStateException("No Kokoro voices available")
        val phonemes = phonemeConverter.phonemize(request.text)
        val speed = request.parameters.floatValue("speed", 1.0f)
        val (audio, sampleRate) = request.kokoroStyleVector?.let { styleVector ->
            createAudioFromStyleVector(
                phonemes = phonemes,
                voice = styleVector,
                speed = speed,
                engine = OnnxRuntimeManager.getEngine(),
            )
        } ?: createAudio(
            phonemes = phonemes,
            voice = voiceId,
            speed = speed,
            engine = OnnxRuntimeManager.getEngine(),
            styleLoader = styleLoader
        )
        return VoiceLabSynthesisResult(
            audio = audio,
            sampleRate = sampleRate,
            engineId = KOKORO_ID,
            engineName = "Kokoro",
            voiceId = voiceId,
            inputCharacterCount = request.text.length,
            modelId = bundle.graphId,
            modelSizeBytes = ManifestProvider.kokoroV1().files.firstOrNull { it.id == bundle.graphId }?.sizeBytes,
            backend = bundle.ep.name,
            generationTimeMs = SystemClock.elapsedRealtime() - started
        )
    }

    private suspend fun synthesizeSupertonic(
        request: VoiceLabRequest,
        started: Long,
    ): VoiceLabSynthesisResult {
        val modelDir = File(appContext.filesDir, "models/${request.engineId}")
        if (!TtsModelValidator.hasAllRequiredFiles(request.engineId, modelDir)) {
            throw IllegalStateException("Supertonic model is not downloaded: ${request.engineId}")
        }
        val engine = supertonicEngine(request.engineId, modelDir, request.parameters["language"])
        val voiceId = request.voiceId ?: supertonicVoices(request.engineId).firstOrNull()?.id
            ?: throw IllegalStateException("No Supertonic styles available")
        val style = loadSupertonicStyle(listOf(File(modelDir, "voice_styles/$voiceId.json")))
        val result = try {
            engine.synthesize(
                text = request.text,
                language = request.parameters["language"] ?: "en",
                style = style,
                totalStep = request.parameters.intValue("totalStep", 8),
                speed = request.parameters.floatValue("speed", 1.05f),
            )
        } finally {
            style.close()
        }
        return VoiceLabSynthesisResult(
            audio = result.wav,
            sampleRate = result.sampleRate,
            engineId = request.engineId,
            engineName = if (request.engineId.contains("3")) "Supertonic 3" else "Supertonic 2",
            voiceId = voiceId,
            inputCharacterCount = request.text.length,
            modelId = request.engineId,
            modelSizeBytes = directorySize(modelDir).takeIf { it > 0L },
            backend = engine.provider,
            generationTimeMs = SystemClock.elapsedRealtime() - started
        )
    }

    private suspend fun synthesizeSoprano(
        request: VoiceLabRequest,
        started: Long,
    ): VoiceLabSynthesisResult {
        val modelDir = File(appContext.filesDir, "models/$SOPRANO_ID")
        if (!TtsModelValidator.hasAllRequiredFiles(SOPRANO_ID, modelDir)) {
            throw IllegalStateException("Soprano model is not downloaded")
        }
        val engine = activeSoprano ?: SopranoEngine(modelDir, OrtEnvironment.getEnvironment()).also {
            activeSoprano = it
        }
        engine.updateSamplingConfig(
            SopranoSamplingConfig(
                temperature = request.parameters.floatValue("temperature", 0.3f),
                topK = request.parameters.intValue("topK", 50),
                topP = request.parameters.floatValue("topP", 0.95f),
                repetitionPenalty = request.parameters.floatValue("repetitionPenalty", 1.2f),
            )
        )
        val result = engine.synthesize(request.text, speed = 1.0f)
        return VoiceLabSynthesisResult(
            audio = result.wav,
            sampleRate = result.sampleRate,
            engineId = SOPRANO_ID,
            engineName = "Soprano",
            voiceId = "soprano-default",
            inputCharacterCount = request.text.length,
            modelId = SOPRANO_ID,
            modelSizeBytes = directorySize(modelDir).takeIf { it > 0L },
            backend = engine.provider,
            generationTimeMs = SystemClock.elapsedRealtime() - started
        )
    }

    private fun supertonicEngine(
        engineId: String,
        modelDir: File,
        language: String?,
    ): DebugSupertonicEngine {
        val normalizedLanguage = language?.takeIf { it in SupertonicLanguages.available } ?: "en"
        val existing = activeSupertonic
        if (existing != null && existing.first == "$engineId:$normalizedLanguage") {
            return existing.second
        }
        existing?.second?.close()
        return DebugSupertonicEngine(modelDir, defaultLanguage = normalizedLanguage).also {
            activeSupertonic = "$engineId:$normalizedLanguage" to it
        }
    }

    private fun readSupertonicSampleRate(modelDir: File): Int? {
        val file = File(modelDir, "tts.json")
        if (!file.isFile) return null
        return runCatching {
            val text = file.readText()
            Regex("\"sample_rate\"\\s*:\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toInt()
        }.getOrNull()
    }

    private fun directorySize(file: File): Long {
        if (!file.exists()) return 0L
        if (file.isFile) return file.length()
        return file.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    private fun Map<String, String>.floatValue(key: String, default: Float): Float =
        get(key)?.toFloatOrNull() ?: default

    private fun Map<String, String>.intValue(key: String, default: Int): Int =
        get(key)?.toFloatOrNull()?.roundToInt() ?: get(key)?.toIntOrNull() ?: default

    companion object {
        const val KOKORO_ID = "kokoro"
        const val SOPRANO_ID = "soprano-80m-onnx"
    }
}

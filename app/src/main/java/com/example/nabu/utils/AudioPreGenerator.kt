package com.example.nabu.utils

import ai.onnxruntime.OrtSession
import android.content.Context
import com.example.nabu.tts.chatterbox.ChatterboxConfig
import com.example.nabu.tts.chatterbox.ChatterboxRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

suspend fun preGenerateBook(
    context: Context,
    session: OrtSession?,
    phonemeConverter: PhonemeConverter,
    styleLoader: StyleLoader,
    project: Project,
    lines: List<String>,
    onProgress: (Float) -> Unit = {}
) = withContext(Dispatchers.IO) {
    DebugLogger.log("Pre-generate start for ${project.uri}")
    val baseDir = project.audioPath?.let { File(it) }
        ?: File(context.filesDir, "pregenerated/${project.uri.hashCode()}").also { it.mkdirs() }

    if (project.audioPath == null) {
        DatabaseManager.setProject(context, project.copy(audioPath = baseDir.absolutePath))
    }

    val engine = SettingsManager.getTtsEngine(context)
    val mixed = if (engine == TtsEngine.CHATTERBOX) null else mixStyles(styleLoader, project.styles, project.weights, project.mode)

    for ((index, line) in lines.withIndex()) {
        if (DatabaseManager.getAudioLine(context, project.uri, index) != null) continue
        DebugLogger.log("Generating line $index for ${project.uri}")
        val (audio, sampleRate) = when (engine) {
            TtsEngine.KITTEN -> {
                val sessionNonNull = requireNotNull(session) { "Kitten ONNX session not initialized" }
                val vector = requireNotNull(mixed) { "Kitten voice vector missing" }
                val (_, tokens) = KittenPhonemizer.phonemize(line)
                createKittenAudioFromStyleVector(
                    tokens = tokens,
                    voice = vector,
                    speed = project.speed,
                    session = sessionNonNull,
                )
            }
            TtsEngine.KOKORO -> {
                val sessionNonNull = requireNotNull(session) { "Kokoro ONNX session not initialized" }
                val vector = requireNotNull(mixed) { "Kokoro voice vector missing" }
                val phonemes = phonemeConverter.phonemize(line)
                createAudioFromStyleVector(
                    phonemes = phonemes,
                    voice = vector,
                    speed = project.speed,
                    session = sessionNonNull,
                )
            }
            TtsEngine.CHATTERBOX -> {
                val chatterbox = ChatterboxRuntime.getOrLoad(context)
                val audio = chatterbox.synthesize(
                    line,
                    ChatterboxConfig(
                        exaggeration = SettingsManager.getChatterboxExaggeration(context),
                        referenceVoicePath = SettingsManager.getChatterboxReferenceVoice(context)
                    )
                )
                audio to chatterbox.sampleRate()
            }
        }
        val file = File(baseDir, "$index.wav")
        saveAudioInternal(audio, file, sampleRate)
        DatabaseManager.setAudioLine(context, project.uri, index, file.absolutePath)
        val progress = (index + 1).toFloat() / lines.size
        onProgress(progress)
    }
    DebugLogger.log("Pre-generate finished for ${project.uri}")
}

package com.example.nabu.utils

import ai.onnxruntime.OrtSession
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import com.example.nabu.tts.sherpa.SherpaTtsEngine

suspend fun preGenerateBook(
    context: Context,
    session: OrtSession?,
    phonemeConverter: PhonemeConverter,
    styleLoader: StyleLoader?,
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
    val mixed = if (engine == TtsEngine.SHERPA) {
        null
    } else {
        val loader = styleLoader ?: StyleLoader(context)
        mixStyles(loader, project.styles, project.weights, project.mode)
    }

    for ((index, line) in lines.withIndex()) {
        if (DatabaseManager.getAudioLine(context, project.uri, index) != null) continue
        DebugLogger.log("Generating line $index for ${project.uri}")
        val (audio, sampleRate) = when (engine) {
            TtsEngine.KITTEN -> {
                val (_, tokens) = KittenPhonemizer.phonemize(line)
                val actualSession = session
                    ?: throw IllegalStateException("ONNX session required for Kitten pre-generation")
                createKittenAudioFromStyleVector(
                    tokens = tokens,
                    voice = requireNotNull(mixed) { "Missing mixed vector for Kitten engine" },
                    speed = project.speed,
                    session = actualSession,
                )
            }
            TtsEngine.KOKORO -> {
                val actualSession = session
                    ?: throw IllegalStateException("ONNX session required for Kokoro pre-generation")
                val phonemes = phonemeConverter.phonemize(line)
                createAudioFromStyleVector(
                    phonemes = phonemes,
                    voice = requireNotNull(mixed) { "Missing mixed vector for Kokoro engine" },
                    speed = project.speed,
                    session = actualSession,
                )
            }
            TtsEngine.SHERPA -> {
                val voice = project.styles.firstOrNull() ?: SherpaTtsEngine.DEFAULT_VOICE_NAME
                SherpaManager.synthesize(
                    context = context,
                    text = line,
                    voice = voice,
                    speed = project.speed
                )
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

package com.example.nabu.utils

import ai.onnxruntime.OrtSession
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

suspend fun preGenerateBook(
    context: Context,
    session: OrtSession,
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

    val mixed = mixStyles(styleLoader, project.styles, project.weights, project.mode)

    for ((index, line) in lines.withIndex()) {
        if (DatabaseManager.getAudioLine(context, project.uri, index) != null) continue
        DebugLogger.log("Generating line $index for ${project.uri}")
        val engine = SettingsManager.getTtsEngine(context)
        val useRaw = SettingsManager.isRawTextInput(context)
        val (audio, sampleRate) = if (engine == TtsEngine.KITTEN) {
            val (_, tokens) = if (useRaw) {
                KittenPhonemizer.encodeText(line)
            } else {
                KittenPhonemizer.phonemize(line)
            }
            createKittenAudioFromStyleVector(
                tokens = tokens,
                voice = mixed,
                speed = project.speed,
                session = session,
            )
        } else {
            val phonemes = if (useRaw) {
                phonemeConverter.phonemize(line)
            } else {
                line
            }
            createAudioFromStyleVector(
                phonemes = phonemes,
                voice = mixed,
                speed = project.speed,
                session = session,
            )
        }
        val file = File(baseDir, "$index.wav")
        saveAudioInternal(audio, file, sampleRate)
        DatabaseManager.setAudioLine(context, project.uri, index, file.absolutePath)
        val progress = (index + 1).toFloat() / lines.size
        onProgress(progress)
    }
    DebugLogger.log("Pre-generate finished for ${project.uri}")
}

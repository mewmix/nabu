package com.example.nabu.utils

import ai.onnxruntime.OrtSession
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.toList
import java.io.File

fun playBook(
    scope: CoroutineScope,
    session: OrtSession,
    phonemeConverter: PhonemeConverter,
    styleLoader: StyleLoader,
    selectedStyles: List<String>,
    weights: Map<String, Float>,
    mode: InterpolationMode,
    speed: Float,
    lines: List<String>,
    startLine: Int,
    bookUri: Uri?,
    audioPlayer: AudioPlayer,
    context: Context,
    onLineChanged: (Int) -> Unit,
    onFinished: () -> Unit,
    usePregenerated: Boolean,
): Job {
    return scope.launch(Dispatchers.IO) {
        DebugLogger.log("Starting playbook from line $startLine")
        var completed = true
        try {
            val mixedVector = mixStyles(
                styleLoader = styleLoader,
                styles = selectedStyles,
                weights = weights,
                mode = mode,
            )

            suspend fun generateLine(index: Int): Pair<FloatArray, Int> {
                if (usePregenerated && bookUri != null) {
                    val path = DatabaseManager.getAudioLine(context, bookUri.toString(), index)
                    if (path != null) {
                        val audio = loadAudioInternal(File(path))
                        return Pair(audio, index)
                    }
                }

                val line = lines[index]
                val engine = SettingsManager.getTtsEngine(context)
                val audio = if (engine == TtsEngine.KITTEN) {
                    val (_, tokens) = KittenPhonemizer.phonemize(line)
                    val (a, _) = createKittenAudioFromStyleVector(
                        tokens = tokens,
                        voice = mixedVector,
                        speed = speed,
                        session = session,
                    )
                    a
                } else {
                    val phonemes = phonemeConverter.phonemize(line)
                    val chunks = createAudioFlowFromStyleVector(
                        phonemes = phonemes,
                        voice = mixedVector,
                        speed = speed,
                        session = session,
                    ).toList()
                    val total = chunks.sumOf { it.size }
                    val result = FloatArray(total)
                    var pos = 0
                    for (chunk in chunks) {
                        chunk.copyInto(result, pos)
                        pos += chunk.size
                    }
                    result
                }
                return Pair(audio, index)
            }

            var currentIndex = startLine
            if (currentIndex >= lines.size) return@launch

            var nextJob = async { generateLine(currentIndex) }
            var (audio, index) = nextJob.await()

            while (isActive) {
                withContext(Dispatchers.Main) {
                    onLineChanged(index)
                }
                DebugLogger.log("Playing line $index")

                currentIndex = index + 1
                nextJob = if (currentIndex < lines.size) {
                    async { generateLine(currentIndex) }
                } else {
                    null
                }

                audioPlayer.prepare(audio, 0)
                audioPlayer.playBlocking()

                if (audioPlayer.getState() == PlayerState.PAUSED || !isActive) {
                    completed = false
                    nextJob?.cancel()
                    break
                }

                if (nextJob == null) break
                val result = nextJob.await()
                audio = result.first
                index = result.second
            }
        } catch (e: Exception) {
            completed = false
            DebugLogger.log("playBook failed: ${e.localizedMessage}")
        } finally {
            withContext(Dispatchers.Main) {
                if (audioPlayer.getState() != PlayerState.PAUSED) {
                    onLineChanged(-1)
                    if (completed) {
                        onFinished()
                    }
                    audioPlayer.stop()
                }
            }
        }
    }
}


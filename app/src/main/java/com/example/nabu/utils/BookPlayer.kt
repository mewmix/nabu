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
        val mixedVector = mixStyles(
            styleLoader = styleLoader,
            styles = selectedStyles,
            weights = weights,
            mode = mode,
        )

        suspend fun generateAudio(index: Int): Pair<FloatArray, Int> {
            if (usePregenerated && bookUri != null) {
                val path = DatabaseManager.getAudioLine(context, bookUri.toString(), index)
                if (path != null) {
                    val audio = loadAudioInternal(File(path))
                    return Pair(audio, index)
                }
            }
            val line = lines[index]
            val engine = SettingsManager.getTtsEngine(context)
            return if (engine == TtsEngine.KITTEN) {
                val (_, tokens) = KittenPhonemizer.phonemize(line)
                val (audio, _) = createKittenAudioFromStyleVector(
                    tokens = tokens,
                    voice = mixedVector,
                    speed = speed,
                    session = session,
                )
                Pair(audio, index)
            } else {
                val phonemes = phonemeConverter.phonemize(line)
                val (audio, _) = createAudioFromStyleVector(
                    phonemes = phonemes,
                    voice = mixedVector,
                    speed = speed,
                    session = session,
                )
                Pair(audio, index)
            }
        }

        try {
            var currentIndex = startLine
            var nextDeferred = async { generateAudio(currentIndex) }

            while (isActive && currentIndex < lines.size) {
                val (audio, index) = nextDeferred.await()
                currentIndex++
                if (currentIndex < lines.size) {
                    nextDeferred = async { generateAudio(currentIndex) }
                }
                withContext(Dispatchers.Main) {
                    onLineChanged(index)
                }
                DebugLogger.log("Playing line $index")
                audioPlayer.prepare(audio, 0)
                audioPlayer.playBlocking()
                if (audioPlayer.getState() == PlayerState.PAUSED) {
                    completed = false
                    break
                }
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


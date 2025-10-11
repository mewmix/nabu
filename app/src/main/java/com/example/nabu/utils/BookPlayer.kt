package com.example.nabu.utils

import ai.onnxruntime.OrtSession
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.example.nabu.tts.sherpa.SherpaTtsEngine

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

        suspend fun generateLine(index: Int): FloatArray {
            if (usePregenerated && bookUri != null) {
                val path = DatabaseManager.getAudioLine(context, bookUri.toString(), index)
                if (path != null) {
                    return loadAudioInternal(File(path))
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
                audio
            } else {
                val phonemes = phonemeConverter.phonemize(line)
                val chunks = mutableListOf<FloatArray>()
                createAudioFlowFromStyleVector(
                    phonemes = phonemes,
                    voice = mixedVector,
                    speed = speed,
                    session = session,
                ).collect { chunk ->
                    chunks.add(chunk)
                }
                val totalSize = chunks.sumOf { it.size }
                val audio = FloatArray(totalSize)
                var pos = 0
                for (chunk in chunks) {
                    chunk.copyInto(audio, pos)
                    pos += chunk.size
                }
                audio
            }
        }

        try {
            var index = startLine
            var currentDeferred: Deferred<FloatArray>? = null
            var nextDeferred: Deferred<FloatArray>? = null

            if (index < lines.size) {
                currentDeferred = async { generateLine(index) }
            }
            if (index + 1 < lines.size) {
                nextDeferred = async { generateLine(index + 1) }
            }

            while (isActive && currentDeferred != null) {
                val audio = currentDeferred.await()
                withContext(Dispatchers.Main) {
                    onLineChanged(index)
                }
                DebugLogger.log("Playing line $index")

                audioPlayer.prepare(audio, 0)
                audioPlayer.playBlocking()

                if (audioPlayer.getState() == PlayerState.PAUSED) {
                    completed = false
                    nextDeferred?.cancel()
                    break
                }

                index++
                currentDeferred = nextDeferred
                nextDeferred = if (index + 1 < lines.size) {
                    async { generateLine(index + 1) }
                } else {
                    null
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

fun playBookSherpa(
    scope: CoroutineScope,
    selectedStyles: List<String>,
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
        DebugLogger.log("Starting Sherpa playbook from line $startLine")
        var completed = true

        val voice = selectedStyles.firstOrNull() ?: SherpaTtsEngine.DEFAULT_VOICE_NAME

        suspend fun generateLine(index: Int): FloatArray {
            if (usePregenerated && bookUri != null) {
                val path = DatabaseManager.getAudioLine(context, bookUri.toString(), index)
                if (path != null) {
                    return loadAudioInternal(File(path))
                }
            }
            val line = lines[index]
            val (audio, _) = SherpaManager.synthesize(
                context = context,
                text = line,
                voice = voice,
                speed = speed,
            )
            return audio
        }

        try {
            var index = startLine
            var currentDeferred: Deferred<FloatArray>? = null
            var nextDeferred: Deferred<FloatArray>? = null

            if (index < lines.size) {
                currentDeferred = async { generateLine(index) }
            }
            if (index + 1 < lines.size) {
                nextDeferred = async { generateLine(index + 1) }
            }

            while (isActive && currentDeferred != null) {
                val audio = currentDeferred.await()
                withContext(Dispatchers.Main) {
                    onLineChanged(index)
                }
                DebugLogger.log("Playing line $index (Sherpa)")

                audioPlayer.prepare(audio, 0)
                audioPlayer.playBlocking()

                if (audioPlayer.getState() == PlayerState.PAUSED) {
                    completed = false
                    nextDeferred?.cancel()
                    break
                }

                index++
                currentDeferred = nextDeferred
                nextDeferred = if (index + 1 < lines.size) {
                    async { generateLine(index + 1) }
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            completed = false
            DebugLogger.log("playBookSherpa failed: ${e.localizedMessage}")
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

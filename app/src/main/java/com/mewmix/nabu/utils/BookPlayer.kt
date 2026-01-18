package com.mewmix.nabu.utils

import android.content.Context
import android.net.Uri
import com.mewmix.nabu.kokoro.KokoroEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collect
import java.io.File

fun playBook(
    scope: CoroutineScope,
    engine: com.mewmix.nabu.tts.TTSEngine,
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

        val rawEngine = if (engine is com.mewmix.nabu.tts.BenchmarkingTTSEngine) engine.delegate else engine
        val mixedVector = if (rawEngine is KokoroEngine) {
            mixStyles(
                styleLoader = styleLoader,
                styles = selectedStyles,
                weights = weights,
                mode = mode,
            )
        } else {
            null
        }

        suspend fun generateLine(index: Int): FloatArray {
            if (usePregenerated && bookUri != null) {
                val path = DatabaseManager.getAudioLine(context, bookUri.toString(), index)
                if (path != null) {
                    return loadAudioInternal(File(path))
                }
            }
            val line = lines[index]

            if (rawEngine is KokoroEngine && mixedVector != null) {
                val phonemes = phonemeConverter.phonemize(line)
                val chunks = mutableListOf<FloatArray>()
                createAudioFlowFromStyleVector(
                    phonemes = phonemes,
                    voice = mixedVector,
                    speed = speed,
                    engine = rawEngine,
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
                return audio
            } else {
                // Generic TTSEngine (e.g. Supertonic)
                if (rawEngine is com.mewmix.nabu.supertonic.DebugSupertonicEngine && selectedStyles.isNotEmpty()) {
                    rawEngine.setStyle(selectedStyles.first())
                }
                // Note: This blocks until the whole line is synthesized, unlike the flow above.
                // For long lines, this might cause a delay.
                val result = engine.synthesize(line, speed)
                return result.wav
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

                audioPlayer.prepare(audio, engine.sampleRate, 0)
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

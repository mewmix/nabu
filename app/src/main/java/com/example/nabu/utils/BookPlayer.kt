package com.example.nabu.utils

import ai.onnxruntime.OrtSession
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
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
        val audioBuffer = Channel<Pair<FloatArray, Int>>(Channel.BUFFERED)

        // Producer
        val producerJob = launch {
            try {
                val mixedVector = mixStyles(
                    styleLoader = styleLoader,
                    styles = selectedStyles,
                    weights = weights,
                    mode = mode,
                )
                for (index in startLine until lines.size) {
                    if (!isActive) break
                    if (usePregenerated && bookUri != null) {
                        val path = DatabaseManager.getAudioLine(context, bookUri.toString(), index)
                        if (path != null) {
                            val audio = loadAudioInternal(File(path))
                            audioBuffer.send(Pair(audio, index))
                            continue
                        }
                    }
                    val line = lines[index]
                    val engine = SettingsManager.getTtsEngine(context)
                    if (engine == TtsEngine.KITTEN) {
                        val (_, tokens) = KittenPhonemizer.phonemize(line)
                        val (audio, _) = createKittenAudioFromStyleVector(
                            tokens = tokens,
                            voice = mixedVector,
                            speed = speed,
                            session = session,
                        )
                        audioBuffer.send(Pair(audio, index))
                    } else {
                        val phonemes = phonemeConverter.phonemize(line)
                        createAudioFlowFromStyleVector(
                            phonemes = phonemes,
                            voice = mixedVector,
                            speed = speed,
                            session = session,
                        ).collect { chunk ->
                            audioBuffer.send(Pair(chunk, index))
                        }
                    }
                }
            } catch (e: Exception) {
                DebugLogger.log("Audio generation failed: ${e.localizedMessage}")
            } finally {
                audioBuffer.close()
            }
        }

        // Consumer
        try {
            var lastIndex = -1
            for ((audio, index) in audioBuffer) {
                if (!isActive) {
                    completed = false
                    break
                }

                if (index != lastIndex) {
                    withContext(Dispatchers.Main) {
                        onLineChanged(index)
                    }
                    DebugLogger.log("Playing line $index")
                    lastIndex = index
                }

                audioPlayer.prepare(audio, 0)
                audioPlayer.playBlocking()

                if (audioPlayer.getState() == PlayerState.PAUSED) {
                    completed = false
                    producerJob.cancel()
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


package com.example.nabu.utils

import com.example.nabu.kokoro.KokoroEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

private const val MAX_PHONEME_LENGTH = 400

fun createAudio(
    phonemes: String,
    voice: String,
    speed: Float,
    engine: KokoroEngine,
    styleLoader: StyleLoader
): Pair<FloatArray, Int> {
    val styleArray = styleLoader.getStyleArray(voice)
    return createAudioFromStyleVector(phonemes, styleArray, speed, engine)
}

fun createAudioFromStyleVector(
    phonemes: String,
    voice: Array<FloatArray>,
    speed: Float,
    engine: KokoroEngine
): Pair<FloatArray, Int> {
    val batches = PhonemeUtils.splitPhonemes(phonemes, MAX_PHONEME_LENGTH)
    if (batches.size > 1) {
        DebugLogger.log("Phonemes too long, splitting into ${batches.size} batches")
    }

    val audioData = mutableListOf<Float>()
    for (batch in batches) {
        val tokens = paddedTokens(batch)
        val chunk = engine.synth(tokens, style = voice, speed = speed)
        audioData.addAll(chunk.toList())
    }

    return audioData.toFloatArray() to engine.sampleRate
}

fun createAudioFlowFromStyleVector(
    phonemes: String,
    voice: Array<FloatArray>,
    speed: Float,
    engine: KokoroEngine
): Flow<FloatArray> = flow {
    val batches = PhonemeUtils.splitPhonemes(phonemes, MAX_PHONEME_LENGTH)
    if (batches.size > 1) {
        DebugLogger.log("Phonemes too long, streaming ${batches.size} batches")
    }

    for (batch in batches) {
        val tokens = paddedTokens(batch)
        emit(engine.synth(tokens, style = voice, speed = speed))
    }
}

private fun paddedTokens(phonemeBatch: String): LongArray {
    val tokens = Tokenizer.tokenize(phonemeBatch)
    if (tokens.size > MAX_PHONEME_LENGTH) {
        throw IllegalArgumentException(
            "Context length is $MAX_PHONEME_LENGTH, but leave room for the pad token 0 at the start & end"
        )
    }
    val padded = LongArray(tokens.size + 2)
    padded[0] = 0
    tokens.copyInto(padded, destinationOffset = 1)
    padded[padded.lastIndex] = 0
    return padded
}

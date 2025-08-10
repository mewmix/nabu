package com.example.kokoro82m.utils

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import com.example.kokoro82m.utils.DebugLogger
import com.example.kokoro82m.utils.PhonemeUtils

fun createAudio(
    phonemes: String,
    voice: String,
    speed: Float,
    session: OrtSession,
    context: Context,
): Pair<FloatArray, Int> {
    val MAX_PHONEME_LENGTH = 400
    val SAMPLE_RATE = 22050

    val batches = PhonemeUtils.splitPhonemes(phonemes, MAX_PHONEME_LENGTH)
    if (batches.size > 1) {
        DebugLogger.log("Phonemes too long, splitting into ${batches.size} batches")
    }

    val styleLoader = StyleLoader(context)
    val styleArray = styleLoader.getStyleArray(voice)
    val audioData = mutableListOf<Float>()

    for (batch in batches) {
        val tokens = Tokenizer.tokenize(batch)
        if (tokens.size > MAX_PHONEME_LENGTH) {
            throw IllegalArgumentException("Context length is $MAX_PHONEME_LENGTH, but leave room for the pad token 0 at the start & end")
        }

        val paddedTokens = listOf(0L) + tokens.toList() + listOf(0L)
        val tokenTensor = OnnxTensor.createTensor(
            OrtEnvironment.getEnvironment(),
            arrayOf(paddedTokens.toLongArray())
        )
        val styleTensor = OnnxTensor.createTensor(
            OrtEnvironment.getEnvironment(),
            styleArray
        )
        val speedTensor = OnnxTensor.createTensor(
            OrtEnvironment.getEnvironment(),
            floatArrayOf(speed)
        )

        val inputs = mapOf(
            "tokens" to tokenTensor,
            "style" to styleTensor,
            "speed" to speedTensor
        )
        val results = session.run(inputs)
        val audioTensor = results[0].value as FloatArray
        results.close()
        audioData.addAll(audioTensor.toList())
    }

    return Pair(audioData.toFloatArray(), SAMPLE_RATE)
}

fun createAudioFromStyleVector(
    phonemes: String,
    voice: Array<FloatArray>,
    speed: Float,
    session: OrtSession,
): Pair<FloatArray, Int> {
    val MAX_PHONEME_LENGTH = 400
    val SAMPLE_RATE = 22050

    val batches = PhonemeUtils.splitPhonemes(phonemes, MAX_PHONEME_LENGTH)
    if (batches.size > 1) {
        DebugLogger.log("Phonemes too long, splitting into ${batches.size} batches")
    }

    val audioData = mutableListOf<Float>()
    for (batch in batches) {
        val tokens = Tokenizer.tokenize(batch)
        if (tokens.size > MAX_PHONEME_LENGTH) {
            throw IllegalArgumentException("Context length is $MAX_PHONEME_LENGTH, but leave room for the pad token 0 at the start & end")
        }

        val paddedTokens = listOf(0L) + tokens.toList() + listOf(0L)
        val tokenTensor = OnnxTensor.createTensor(
            OrtEnvironment.getEnvironment(),
            arrayOf(paddedTokens.toLongArray())
        )
        val styleTensor = OnnxTensor.createTensor(
            OrtEnvironment.getEnvironment(),
            voice
        )

        val speedTensor = OnnxTensor.createTensor(
            OrtEnvironment.getEnvironment(),
            floatArrayOf(speed)
        )

        val inputs = mapOf(
            "tokens" to tokenTensor,
            "style" to styleTensor,
            "speed" to speedTensor
        )
        val results = session.run(inputs)
        val audioTensor = results[0].value as FloatArray
        results.close()
        audioData.addAll(audioTensor.toList())
    }

    return Pair(audioData.toFloatArray(), SAMPLE_RATE)
}

fun createAudioFlowFromStyleVector(
    phonemes: String,
    voice: Array<FloatArray>,
    speed: Float,
    session: OrtSession,
): Flow<FloatArray> = flow {
    val MAX_PHONEME_LENGTH = 400
    val batches = PhonemeUtils.splitPhonemes(phonemes, MAX_PHONEME_LENGTH)
    if (batches.size > 1) {
        DebugLogger.log("Phonemes too long, streaming ${batches.size} batches")
    }

    for (batch in batches) {
        val tokens = Tokenizer.tokenize(batch)
        if (tokens.size > MAX_PHONEME_LENGTH) {
            throw IllegalArgumentException("Context length is $MAX_PHONEME_LENGTH, but leave room for the pad token 0 at the start & end")
        }

        val paddedTokens = listOf(0L) + tokens.toList() + listOf(0L)
        val tokenTensor = OnnxTensor.createTensor(
            OrtEnvironment.getEnvironment(),
            arrayOf(paddedTokens.toLongArray())
        )
        val styleTensor = OnnxTensor.createTensor(
            OrtEnvironment.getEnvironment(),
            voice
        )
        val speedTensor = OnnxTensor.createTensor(
            OrtEnvironment.getEnvironment(),
            floatArrayOf(speed)
        )

        val inputs = mapOf(
            "tokens" to tokenTensor,
            "style" to styleTensor,
            "speed" to speedTensor
        )
        val results = session.run(inputs)
        val audioTensor = results[0].value as FloatArray
        results.close()
        emit(audioTensor)
    }
}

fun createKittenAudioFromStyleVector(
    tokens: LongArray,
    voice: Array<FloatArray>,
    speed: Float,
    session: OrtSession,
): Pair<FloatArray, Int> {
    val SAMPLE_RATE = 24000

    val tokenTensor = OnnxTensor.createTensor(
        OrtEnvironment.getEnvironment(),
        arrayOf(tokens)
    )
    val styleTensor = OnnxTensor.createTensor(
        OrtEnvironment.getEnvironment(),
        voice
    )
    val speedTensor = OnnxTensor.createTensor(
        OrtEnvironment.getEnvironment(),
        floatArrayOf(speed)
    )

    val inputs = mapOf(
        "input_ids" to tokenTensor,
        "style" to styleTensor,
        "speed" to speedTensor
    )
    val results = session.run(inputs)
    val audioTensor = results[0].value as FloatArray
    results.close()

    val start = 5000
    val end = if (audioTensor.size > 10000) audioTensor.size - 10000 else audioTensor.size
    val trimmed = audioTensor.copyOfRange(start, end)

    return Pair(trimmed, SAMPLE_RATE)
}

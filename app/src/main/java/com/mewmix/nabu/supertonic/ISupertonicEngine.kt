package com.mewmix.nabu.supertonic

import ai.onnxruntime.OrtEnvironment
import com.mewmix.nabu.tts.TTSEngine
import kotlin.random.Random

interface ISupertonicEngine : TTSEngine {
    suspend fun synthesize(
        text: String,
        style: SupertonicStyle,
        totalStep: Int = 5,
        speed: Float = 1.05f,
        rng: Random = Random.Default,
        env: OrtEnvironment = OrtEnvironment.getEnvironment()
    ): SupertonicResult

    suspend fun synthesize(
        texts: List<String>,
        style: SupertonicStyle,
        totalStep: Int = 5,
        speed: Float = 1.05f,
        rng: Random = Random.Default,
        env: OrtEnvironment = OrtEnvironment.getEnvironment()
    ): SupertonicResult
}

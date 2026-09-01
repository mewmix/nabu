package com.mewmix.nabu.voicelab

data class VoiceLabEngineInfo(
    val id: String,
    val name: String,
    val modelId: String,
    val provider: String,
    val sampleRate: Int?,
    val modelSizeBytes: Long?,
    val isAvailable: Boolean,
    val status: String,
    val parameters: List<VoiceLabParameter>,
)

data class VoiceLabVoice(
    val id: String,
    val displayName: String,
    val engineId: String,
    val modelId: String,
    val metadata: Map<String, String> = emptyMap(),
)

sealed class VoiceLabParameter {
    abstract val id: String
    abstract val label: String

    data class FloatValue(
        override val id: String,
        override val label: String,
        val defaultValue: Float,
        val min: Float,
        val max: Float,
        val step: Float? = null,
    ) : VoiceLabParameter()

    data class IntValue(
        override val id: String,
        override val label: String,
        val defaultValue: Int,
        val min: Int,
        val max: Int,
    ) : VoiceLabParameter()

    data class ChoiceValue(
        override val id: String,
        override val label: String,
        val defaultValue: String,
        val choices: List<String>,
    ) : VoiceLabParameter()
}

data class VoiceLabRequest(
    val engineId: String,
    val voiceId: String?,
    val text: String,
    val parameters: Map<String, String>,
    val kokoroStyleVector: Array<FloatArray>? = null,
)

data class VoiceLabSynthesisResult(
    val audio: FloatArray,
    val sampleRate: Int,
    val engineId: String,
    val engineName: String,
    val voiceId: String?,
    val inputCharacterCount: Int,
    val modelId: String,
    val modelSizeBytes: Long?,
    val backend: String,
    val generationTimeMs: Long,
) {
    val audioDurationSeconds: Float
        get() = if (sampleRate > 0) audio.size.toFloat() / sampleRate.toFloat() else 0f

    val realTimeFactor: Float
        get() {
            val audioMs = audioDurationSeconds * 1000f
            return if (audioMs > 0f) generationTimeMs.toFloat() / audioMs else 0f
        }

    val wavSizeBytes: Long
        get() = 44L + audio.size.toLong() * 2L
}

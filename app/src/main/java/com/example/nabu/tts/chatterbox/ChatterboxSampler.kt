package com.example.nabu.tts.chatterbox

internal object ChatterboxSampler {
    fun sample(logits: FloatArray, generated: LongArray, penalty: Float): Int {
        val adjusted = logits.copyOf()
        for (token in generated) {
            val idx = token.toInt()
            if (idx !in adjusted.indices) continue
            val value = adjusted[idx]
            adjusted[idx] = if (value < 0f) value * penalty else value / penalty
        }
        var maxIndex = 0
        var maxValue = Float.NEGATIVE_INFINITY
        for (i in adjusted.indices) {
            val value = adjusted[i]
            if (value > maxValue) {
                maxValue = value
                maxIndex = i
            }
        }
        return maxIndex
    }
}

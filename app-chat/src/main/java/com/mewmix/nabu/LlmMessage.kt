package com.mewmix.nabu.chat

/**
 * Represents a single message within the conversation payload sent to the on-device LLM.
 */
data class LlmMessage(
    val role: String,
    val content: String,
)

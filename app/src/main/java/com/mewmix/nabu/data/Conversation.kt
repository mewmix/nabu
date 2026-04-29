package com.mewmix.nabu.data

import com.google.gson.annotations.SerializedName

data class Conversation(
    val id: Long,
    val title: String,
    val modelId: String?,
    val messages: List<ConversationTurn>,
    val createdAt: Long,
    val updatedAt: Long,
)

data class ConversationSummary(
    val id: Long,
    val title: String,
    val modelId: String?,
    val updatedAt: Long,
)

data class ConversationTurn(
    val role: ConversationRole,
    val content: String,
    val imagePath: String? = null
)

enum class ConversationRole {
    @SerializedName("user")
    USER,

    @SerializedName("agent")
    AGENT
}

fun Conversation.toSummary(): ConversationSummary =
    ConversationSummary(id = id, title = title, modelId = modelId, updatedAt = updatedAt)

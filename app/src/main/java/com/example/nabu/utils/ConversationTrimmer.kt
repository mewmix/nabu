package com.example.nabu.utils

import android.content.Context
import com.example.nabu.data.ConversationTurn

interface ConversationTrimmer {
    fun trim(
        messages: List<ConversationTurn>,
        capTokens: Int,
        context: Context,
        modelId: String
    ): TrimResult
}

data class TrimResult(
    val trimmedMessages: List<ConversationTurn>,
    val tokensEstimatedWhitespace: Int,
    val tokensEstimatedCharsDiv4: Int,
    val truncationApplied: Boolean,
    val truncatedTokensEstimated: Int
)

object DefaultConversationTrimmer : ConversationTrimmer {
    private val TOKEN_REGEX = Regex("\\S+")

    override fun trim(
        messages: List<ConversationTurn>,
        capTokens: Int,
        context: Context,
        modelId: String
    ): TrimResult {
        val totalTokensBefore = messages.sumOf { estimateTokenCount(it.content) }
        val charsBefore = messages.sumOf { it.content.length }

        if (messages.isEmpty() || capTokens <= 0) {
            return TrimResult(emptyList(), 0, 0, false, 0)
        }

        var remainingTokens = capTokens
        val trimmed = ArrayDeque<ConversationTurn>()
        var truncatedCount = 0

        // Process from newest to oldest
        for (turn in messages.asReversed()) {
            if (remainingTokens <= 0) {
                // Determine how many tokens we are skipping (roughly) for the rest of history
                truncatedCount += estimateTokenCount(turn.content)
                continue
            }

            val tokenCount = estimateTokenCount(turn.content)
            if (tokenCount <= remainingTokens) {
                trimmed.addFirst(turn)
                remainingTokens -= tokenCount
            } else {
                val truncatedContent = takeLastTokens(turn.content, remainingTokens)
                val keptTokens = estimateTokenCount(truncatedContent)
                val droppedTokens = tokenCount - keptTokens
                truncatedCount += droppedTokens

                if (truncatedContent.isNotBlank()) {
                    trimmed.addFirst(turn.copy(content = truncatedContent))
                }
                remainingTokens = 0
            }
        }

        val finalMessages = trimmed.toList()
        val totalTokensAfter = finalMessages.sumOf { estimateTokenCount(it.content) }
        val truncationApplied = totalTokensBefore > totalTokensAfter

        // Log the decision
        LlmStructuredLogger.logEvent(
            eventType = "chat_request_prepared",
            context = context,
            modelId = modelId,
            extras = mapOf(
                "context_token_cap_ui" to capTokens,
                "tokens_estimated_whitespace_before" to totalTokensBefore,
                "tokens_estimated_whitespace_after" to totalTokensAfter,
                "tokens_estimated_chars_div4_before" to (charsBefore / 4),
                "truncation_applied" to truncationApplied,
                "truncated_tokens_estimated" to (totalTokensBefore - totalTokensAfter)
            )
        )

        return TrimResult(
            trimmedMessages = finalMessages,
            tokensEstimatedWhitespace = totalTokensAfter,
            tokensEstimatedCharsDiv4 = finalMessages.sumOf { it.content.length } / 4,
            truncationApplied = truncationApplied,
            truncatedTokensEstimated = totalTokensBefore - totalTokensAfter
        )
    }

    private fun estimateTokenCount(text: String): Int {
        if (text.isBlank()) return 0
        return TOKEN_REGEX.findAll(text).count()
    }

    private fun takeLastTokens(text: String, tokenLimit: Int): String {
        if (tokenLimit <= 0) return ""
        val tokens = TOKEN_REGEX.findAll(text).map { it.value }.toList()
        if (tokens.isEmpty()) return ""
        if (tokens.size <= tokenLimit) {
            return text.trim()
        }
        return tokens.takeLast(tokenLimit).joinToString(" ")
    }
}

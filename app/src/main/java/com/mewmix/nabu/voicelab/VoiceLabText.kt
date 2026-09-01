package com.mewmix.nabu.voicelab

object VoiceLabText {
    const val PREVIEW_MAX_CHARS = 220

    fun renderableTextOrNull(text: String): String? =
        text.trim().takeIf { it.isNotEmpty() }

    fun previewText(text: String): String {
        val trimmed = renderableTextOrNull(text) ?: return ""
        if (trimmed.length <= PREVIEW_MAX_CHARS) return trimmed
        val firstSentence = Regex(".*?[.!?](\\s|$)").find(trimmed)?.value?.trim()
        return firstSentence?.takeIf { it.length in 40..240 } ?: trimmed.take(PREVIEW_MAX_CHARS)
    }
}

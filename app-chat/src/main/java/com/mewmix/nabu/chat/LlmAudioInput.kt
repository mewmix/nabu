package com.mewmix.nabu.chat

data class LlmAudioInput(
    val bytes: ByteArray,
    val displayName: String? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LlmAudioInput) return false
        if (!bytes.contentEquals(other.bytes)) return false
        return displayName == other.displayName
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + (displayName?.hashCode() ?: 0)
        return result
    }
}

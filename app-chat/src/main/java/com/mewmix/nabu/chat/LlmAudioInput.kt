package com.mewmix.nabu.chat

data class LlmAudioInput(
    val bytes: ByteArray,
    val displayName: String? = null,
    val absolutePath: String? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LlmAudioInput) return false
        if (!bytes.contentEquals(other.bytes)) return false
        if (displayName != other.displayName) return false
        return absolutePath == other.absolutePath
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + (displayName?.hashCode() ?: 0)
        result = 31 * result + (absolutePath?.hashCode() ?: 0)
        return result
    }
}

package com.mewmix.nabu.core.tts

enum class TextMode { AUTO_G2P, IPA, ARPABET }

data class G2PConfig(
    val langCode: String,
    val preferMisaki: Boolean = true,
    val fallbackEspeak: Boolean = true,
    val maxLen: Int = 2000
)

interface Phonemizer {
    /**
     * Input: raw text (for AUTO_G2P/ARPABET) or IPA (for IPA mode).
     * Output: normalized IPA string Kokoro expects.
     */
    suspend fun toIpa(input: String, mode: TextMode, cfg: G2PConfig): String
}


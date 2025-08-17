package com.mewmix.nabu.core.tts

/**
 * Basic phonemizer contract for converting text to IPA.
 */
interface Phonemizer {
    suspend fun toIpa(input: String, mode: TextMode, cfg: G2PConfig): String
}

/**
 * Text input mode for the phonemizer.
 */
enum class TextMode {
    IPA,
    AUTO_G2P
}

/**
 * Configuration for grapheme-to-phoneme conversion.
 */
data class G2PConfig(val langCode: String)

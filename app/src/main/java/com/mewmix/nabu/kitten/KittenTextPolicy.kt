package com.mewmix.nabu.kitten

import com.mewmix.nabu.core.tts.*

/**
 * Kitten specific text policy that always routes through eSpeak-NG.
 */
class KittenTextPolicy(
    private val espeak: EspeakAdapter
) : Phonemizer {

    private val impl = object : Phonemizer {
        override suspend fun toIpa(input: String, mode: TextMode, cfg: G2PConfig): String {
            require(mode != TextMode.IPA) { "Kitten expects controlled IPA; user-IPA disabled for Kitten" }
            require(espeak.supports(cfg.langCode)) { "eSpeak has no voice for ${cfg.langCode}" }
            val text = normalizeText(input)
            val ipa = espeak.g2pToIpa(text, cfg.langCode) ?: error("eSpeak returned null")
            val norm = normalizeIpa(ipa)
            validateIpa(norm)
            return norm
        }
    }

    override suspend fun toIpa(input: String, mode: TextMode, cfg: G2PConfig): String =
        impl.toIpa(input, TextMode.AUTO_G2P, cfg)

    private fun normalizeText(s: String) =
        s.replace('\u2019','\'').replace('\u2014','-').replace(Regex("\\s+")," ").trim()

    private fun normalizeIpa(ipa: String) =
        ipa.replace(Regex("\\s+")," ").trim()

    private fun validateIpa(ipa: String) {
        // reuse your Kokoro validator; Kitten uses similar inventory
        if (!ipa.matches(Regex("""^[\\p{L}\\p{M}\\p{Zs}\\p{Punct}ˈˌːˑ̃˞˩˨˧˦˥ɪɛæɑɔʊə…-]+$""")))
            error("Invalid IPA content for Kitten")
    }
}

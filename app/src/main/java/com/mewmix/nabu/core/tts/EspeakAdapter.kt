package com.mewmix.nabu.core.tts

/**
 * Minimal abstraction over an eSpeak-NG backed phonemizer.
 */
interface EspeakAdapter {
    /** Returns true if the adapter has a voice for the given language code. */
    fun supports(langCode: String): Boolean

    /** Converts the supplied text into IPA for the requested language. */
    fun g2pToIpa(text: String, langCode: String): String?
}

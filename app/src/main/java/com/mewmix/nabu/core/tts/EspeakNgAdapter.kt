package com.mewmix.nabu.core.tts

/**
 * JNI backed adapter that talks to eSpeak-NG for grapheme to IPA conversion.
 */
class EspeakNgAdapter(
    private val availableVoices: Set<String>
) : EspeakAdapter {
    init {
        // Load the native JNI bridge which internally links against eSpeak-NG.
        System.loadLibrary("espeakng_jni")
    }

    override fun supports(langCode: String): Boolean =
        availableVoices.contains(langCode.lowercase())

    override fun g2pToIpa(text: String, langCode: String): String? =
        nativeG2pToIpa(text, langCode.lowercase())

    private external fun nativeG2pToIpa(text: String, lang: String): String?
}

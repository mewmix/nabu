package com.mewmix.nabu.core.tts

interface MisakiAdapter {
    fun supports(langCode: String): Boolean
    fun g2p(text: String, langCode: String): String?
}

interface EspeakAdapter {
    fun supports(langCode: String): Boolean
    fun g2pToIpa(text: String, langCode: String): String?
}

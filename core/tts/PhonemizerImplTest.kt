package com.mewmix.nabu.core.tts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private class FakeMisaki(
    private val data: Map<String, String> = emptyMap(),
    private val supported: Set<String> = emptySet()
) : MisakiAdapter {
    override fun supports(langCode: String) = supported.contains(langCode)
    override fun g2p(text: String, langCode: String) = data[text]
}

private class FakeEspeak(
    private val data: Map<String, String> = emptyMap(),
    private val supported: Set<String> = emptySet()
) : EspeakAdapter {
    override fun supports(langCode: String) = supported.contains(langCode)
    override fun g2pToIpa(text: String, langCode: String) = data[text]
}

class PhonemizerImplTest {
    @Test
    fun autoG2P_misakiFirst() = kotlinx.coroutines.runBlocking {
        val misaki = FakeMisaki(mapOf("hello" to "hɛlo"), setOf("en-us"))
        val espeak = FakeEspeak()
        val phon = PhonemizerImpl(misaki, espeak)
        val ipa = phon.toIpa("hello", TextMode.AUTO_G2P, G2PConfig("en-us"))
        assertEquals("hɛlo", ipa)
    }

    @Test
    fun autoG2P_fallbackEspeak() = kotlinx.coroutines.runBlocking {
        val misaki = FakeMisaki(supported = emptySet())
        val espeak = FakeEspeak(mapOf("hello" to "hɛlo"), setOf("en-us"))
        val phon = PhonemizerImpl(misaki, espeak)
        val ipa = phon.toIpa("hello", TextMode.AUTO_G2P, G2PConfig("en-us"))
        assertEquals("hɛlo", ipa)
    }

    @Test
    fun arpabet_basicMap() = kotlinx.coroutines.runBlocking {
        val phon = PhonemizerImpl(null, null)
        val ipa = phon.toIpa("HH EH1 L OW0", TextMode.ARPABET, G2PConfig("en-us"))
        assertEquals("h ˈɛ l oʊ", ipa)
    }

    @Test
    fun ipa_rejectBadChars() = kotlinx.coroutines.runBlocking {
        val phon = PhonemizerImpl(null, null)
        assertFailsWith<IllegalArgumentException> {
            phon.toIpa("hello 😊", TextMode.IPA, G2PConfig("en-us"))
        }
    }

    @Test
    fun cache_hit() = kotlinx.coroutines.runBlocking {
        var calls = 0
        val misaki = object : MisakiAdapter {
            override fun supports(langCode: String) = true
            override fun g2p(text: String, langCode: String): String {
                calls++
                return "hɛlo"
            }
        }
        val phon = PhonemizerImpl(misaki, null)
        val cfg = G2PConfig("en-us")
        val first = phon.toIpa("hello", TextMode.AUTO_G2P, cfg)
        val second = phon.toIpa("hello", TextMode.AUTO_G2P, cfg)
        assertEquals(first, second)
        assertEquals(1, calls)
    }
}

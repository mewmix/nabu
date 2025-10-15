package com.example.nabu.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class PhonemeAcronymTest {
    private val converter = PhonemeConverter.fromDictionary(
        mapOf(
            "IT" to "ɪt"
        )
    )

    @Test
    fun `it is not treated as initialism`() {
        val phonemes = converter.phonemize("it")
        assertEquals("ɪt", phonemes)
    }

    @Test
    fun `It at sentence start is not treated as initialism`() {
        val phonemes = converter.phonemize("It")
        assertEquals("ɪt", phonemes)
    }
}


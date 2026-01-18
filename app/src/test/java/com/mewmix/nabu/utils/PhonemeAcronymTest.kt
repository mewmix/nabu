package com.mewmix.nabu.utils

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.robolectric.RobolectricTestRunner
import org.junit.runner.RunWith

@RunWith(RobolectricTestRunner::class)
class PhonemeAcronymTest {
    private lateinit var converter: PhonemeConverter

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        converter = PhonemeConverter(context)
    }

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


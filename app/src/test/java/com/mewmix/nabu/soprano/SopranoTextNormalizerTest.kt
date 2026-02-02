package com.mewmix.nabu.soprano

import org.junit.Assert.assertEquals
import org.junit.Test

class SopranoTextNormalizerTest {
    @Test
    fun testNumberNormalization() {
        assertEquals("one hundred twenty three", SopranoTextNormalizer.cleanText("123"))
        assertEquals("one thousand", SopranoTextNormalizer.cleanText("1000"))
    }

    @Test
    fun testCurrency() {
        assertEquals("ten dollars", SopranoTextNormalizer.cleanText("$10"))
        assertEquals("ten dollars, fifty cents", SopranoTextNormalizer.cleanText("$10.50"))
    }

    @Test
    fun testSpecialChars() {
        assertEquals("hello at world", SopranoTextNormalizer.cleanText("hello@world"))
    }
}

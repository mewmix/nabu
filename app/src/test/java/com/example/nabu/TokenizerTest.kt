package com.example.nabu

import com.example.nabu.utils.Tokenizer
import org.junit.Test
import org.junit.Assert.*

class TokenizerTest {
    @Test
    fun tokenizeValidCharacters() {
        val text = "AB?"
        val tokens = Tokenizer.tokenize(text)
        assertEquals(text.length, tokens.size)
    }

    @Test
    fun tokenizeUnknownSymbolThrows() {
        try {
            Tokenizer.tokenize("\u2603")
            fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun tokenizeLongTextThrows() {
        val longText = "A".repeat(513)
        try {
            Tokenizer.tokenize(longText)
            fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }
}

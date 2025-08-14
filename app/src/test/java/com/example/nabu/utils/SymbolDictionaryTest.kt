package com.example.nabu.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class SymbolDictionaryTest {

    @Test
    fun `replaces numbers with words`() {
        val result = SymbolDictionary.replaceSymbols("100").trim()
        assertEquals("one hundred", result)
    }

    @Test
    fun `replaces large numbers up to a billion`() {
        val million = SymbolDictionary.replaceSymbols("1000000").trim()
        assertEquals("one million", million)

        val billion = SymbolDictionary.replaceSymbols("1000000000").trim()
        assertEquals("one billion", billion)
    }

    @Test
    fun `skips unknown symbols`() {
        val raw = "hello😊world"
        val result = SymbolDictionary.replaceSymbols(raw).replace(Regex("\\s+"), " ").trim()
        assertEquals("hello world", result)
    }
}

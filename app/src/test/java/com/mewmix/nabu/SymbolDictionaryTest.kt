package com.mewmix.nabu

import com.mewmix.nabu.utils.SymbolDictionary
import org.junit.Assert.assertEquals
import org.junit.Test

class SymbolDictionaryTest {
    @Test
    fun replaceSymbolsReplacesDigitsAndHyphen() {
        val input = "Line 1-2"
        val output = SymbolDictionary.replaceSymbols(input)
            .replace("\\s+".toRegex(), " ")
            .trim()
        assertEquals("Line one dash two", output)
    }
}

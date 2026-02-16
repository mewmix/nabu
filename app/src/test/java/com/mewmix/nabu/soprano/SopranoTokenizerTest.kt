package com.mewmix.nabu.soprano

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class SopranoTokenizerTest {
    @Test
    fun parsesTokenizerJsonAndEncodesSpecialTokens() {
        val modelDir = createTempDirectory(prefix = "soprano-tokenizer-test").toFile()
        val tokenizerJson = """
            {
              "model": {
                "vocab": {
                  "[UNK]": 0,
                  "[STOP]": 1,
                  "[TEXT]": 2,
                  "[START]": 3,
                  "h": 4,
                  "i": 5,
                  "hi": 6
                },
                "merges": [
                  "h i",
                  ["x", "y"]
                ]
              }
            }
        """.trimIndent()

        File(modelDir, "tokenizer.json").writeText(tokenizerJson)
        val tokenizer = SopranoTokenizer(modelDir)

        assertArrayEquals(
            longArrayOf(1L, 6L, 3L),
            tokenizer.encode("[STOP]hi[START]")
        )
        assertEquals("hi", tokenizer.decode(longArrayOf(6L)))
    }
}

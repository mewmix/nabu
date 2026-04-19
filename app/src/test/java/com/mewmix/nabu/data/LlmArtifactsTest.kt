package com.mewmix.nabu.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LlmArtifactsTest {

    @Test
    fun extensionFromDownloadUrl_detectsKnownFormats() {
        assertEquals("gguf", llmExtensionFromDownloadUrl("https://example.com/model.gguf"))
        assertEquals("litertlm", llmExtensionFromDownloadUrl("https://example.com/model.litertlm"))
        assertEquals("task", llmExtensionFromDownloadUrl("https://example.com/model.task"))
    }

    @Test
    fun findDownloadedArtifact_returnsLitertlmForMediapipeModels() {
        val modelDir = createTempDir(prefix = "llm-artifacts")
        try {
            File(modelDir, "gemma-4-E2B-it.litertlm").writeText("ok")

            val artifact = findDownloadedLlmArtifact(modelDir, "gemma-4-E2B-it", "mediapipe")

            assertNotNull(artifact)
            assertEquals("litertlm", artifact?.backend)
            assertEquals("litertlm", artifact?.extension)
        } finally {
            modelDir.deleteRecursively()
        }
    }

    @Test
    fun importableMetadata_supportsLitertlm() {
        val importable = importableLlmMetadata("gemma-4-E2B-it.litertlm")

        assertEquals("gemma-4-E2B-it", importable?.first)
        assertEquals("litertlm", importable?.second)
    }

    @Test
    fun partialArtifacts_ignoresCompletedFiles() {
        val modelDir = createTempDir(prefix = "llm-partials")
        try {
            File(modelDir, "gemma3-1b-it-q4.task").writeText("ok")
            assertFalse(hasPartialLlmArtifacts(modelDir, "gemma3-1b-it-q4"))

            File(modelDir, "gemma-4-E2B-it.litertlm.part").writeText("partial")
            assertTrue(hasPartialLlmArtifacts(modelDir, "gemma-4-E2B-it"))
        } finally {
            modelDir.deleteRecursively()
        }
    }

    @Test
    fun importableMetadata_rejectsUnknownExtensions() {
        assertNull(importableLlmMetadata("model.bin"))
    }
}

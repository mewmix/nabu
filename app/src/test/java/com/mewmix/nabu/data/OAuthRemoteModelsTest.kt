package com.mewmix.nabu.data

import org.junit.Assert.assertEquals
import org.junit.Test

class OAuthRemoteModelsTest {

    @Test
    fun normalizeModelId_migratesUnsupportedCodexSlugs() {
        assertEquals(
            "oauth://codex/gpt-5.5",
            OAuthRemoteModels.normalizeModelId("codex-byos-oauth")
        )
        assertEquals(
            "oauth://codex/gpt-5.5",
            OAuthRemoteModels.normalizeModelId("oauth://codex/gpt-5.3-codex")
        )
        assertEquals(
            "oauth://codex/gpt-5.4",
            OAuthRemoteModels.normalizeModelId("oauth://codex/gpt-5.2-codex")
        )
    }

    @Test
    fun detectSelection_usesDefaultCodexModelForBackendFallback() {
        val selection = OAuthRemoteModels.detectSelection("anything", "codex_oauth")

        assertEquals(OAuthRemoteModels.Provider.CODEX, selection?.provider)
        assertEquals("gpt-5.5", selection?.modelSlug)
    }
}

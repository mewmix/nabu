package com.mewmix.nabu.utils

import com.mewmix.nabu.kokoro.ManifestProvider
import org.junit.Assert.assertNotNull
import org.junit.Test

class OnnxRuntimeManagerTest {

    @Test
    fun testManifestParsing() {
        val manifest = ManifestProvider.kokoroV1()
        assertNotNull(manifest)
        assertNotNull(manifest.files)
        assert(manifest.files.isNotEmpty())
    }
}

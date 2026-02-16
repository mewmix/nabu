package com.mewmix.nabu.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionModelSupportTest {
    @Test
    fun allowlistIsStrict() {
        assertTrue(VisionModelSupport.supportsImageInput("gemma-3n-E4B-it-int4"))
        assertFalse(VisionModelSupport.supportsImageInput("gemma3-1b-it-q4"))
        assertFalse(VisionModelSupport.supportsImageInput("qwen2.5-1.5b-instruct-q8"))
    }

    @Test
    fun capabilitiesReflectVisionSupport() {
        assertTrue(VisionModelSupport.capabilitiesFor("gemma-3n-E4B-it-int4").contains("multimodal"))
        assertFalse(VisionModelSupport.capabilitiesFor("gemma3-1b-it-q4").contains("multimodal"))
    }
}

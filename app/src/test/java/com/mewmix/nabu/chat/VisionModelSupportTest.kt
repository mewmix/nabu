package com.mewmix.nabu.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionModelSupportTest {
    @Test
    fun allowlistIsStrict() {
        assertTrue(VisionModelSupport.supportsImageInput("gemma-3n-E4B-it-int4"))
        assertFalse(VisionModelSupport.supportsImageInput("gemma-4-E2B-it"))
        assertFalse(VisionModelSupport.supportsImageInput("gemma3-1b-it-q4"))
        assertFalse(VisionModelSupport.supportsImageInput("qwen2.5-1.5b-instruct-q8"))
    }

    @Test
    fun audioAllowlistIsStrict() {
        assertTrue(VisionModelSupport.supportsAudioInput("gemma-4-E2B-it"))
        assertFalse(VisionModelSupport.supportsAudioInput("gemma-3n-E4B-it-int4"))
        assertFalse(VisionModelSupport.supportsAudioInput("gemma3-1b-it-q4"))
    }

    @Test
    fun capabilitiesReflectMultimodalSupport() {
        assertTrue(VisionModelSupport.capabilitiesFor("gemma-3n-E4B-it-int4").contains("multimodal"))
        assertTrue(VisionModelSupport.capabilitiesFor("gemma-3n-E4B-it-int4").contains("image"))
        assertTrue(VisionModelSupport.capabilitiesFor("gemma-4-E2B-it").contains("multimodal"))
        assertFalse(VisionModelSupport.capabilitiesFor("gemma-4-E2B-it").contains("image"))
        assertTrue(VisionModelSupport.capabilitiesFor("gemma-4-E2B-it").contains("audio"))
        assertFalse(VisionModelSupport.capabilitiesFor("gemma3-1b-it-q4").contains("multimodal"))
    }
}

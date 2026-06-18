package com.mewmix.nabu.chat

object VisionModelSupport {
    // Keep this allowlist strict; only enable image input for model IDs verified in-app.
    private val visionModelIds = setOf(
        "gemma-3n-E4B-it-int4",
        "gemma-4-E2B-it"
    )
    private val audioModelIds = setOf(
        "gemma-4-E2B-it"
    )

    fun supportsImageInput(modelId: String): Boolean = modelId in visionModelIds

    fun supportsAudioInput(modelId: String): Boolean = modelId in audioModelIds

    fun capabilitiesFor(modelId: String): Set<String> {
        return buildSet {
            add("completion")
            if (supportsImageInput(modelId) || supportsAudioInput(modelId)) {
                add("multimodal")
            }
            if (supportsImageInput(modelId)) {
                add("image")
            }
            if (supportsAudioInput(modelId)) {
                add("audio")
            }
        }
    }
}

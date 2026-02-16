package com.mewmix.nabu.chat

object VisionModelSupport {
    // Keep this allowlist strict; only enable image input for model IDs verified in-app.
    private val visionModelIds = setOf(
        "gemma-3n-E4B-it-int4"
    )

    fun supportsImageInput(modelId: String): Boolean = modelId in visionModelIds

    fun capabilitiesFor(modelId: String): Set<String> {
        return if (supportsImageInput(modelId)) {
            setOf("completion", "multimodal")
        } else {
            setOf("completion")
        }
    }
}

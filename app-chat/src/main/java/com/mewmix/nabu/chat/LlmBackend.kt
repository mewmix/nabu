package com.mewmix.nabu.chat

interface LlmBackend {
    fun runtimeDescription(): String = "UNKNOWN"

    fun initialize()

    fun sendMessage(
        conversation: List<LlmMessage>,
        resultListener: (partialResult: String, done: Boolean) -> Unit
    )

    fun sendMessage(
        prompt: String,
        resultListener: (partialResult: String, done: Boolean) -> Unit
    )

    fun supportsImageInput(): Boolean = false

    fun supportsAudioInput(): Boolean = false

    fun sendMessage(
        conversation: List<LlmMessage>,
        image: LlmImageInput,
        resultListener: (partialResult: String, done: Boolean) -> Unit
    ) {
        resultListener("Image input is not supported by this backend.", true)
    }

    fun cancel()

    fun close()
}

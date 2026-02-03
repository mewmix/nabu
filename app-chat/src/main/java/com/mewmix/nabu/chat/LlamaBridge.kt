package com.mewmix.nabu.chat

internal object LlamaBridge {
    val isAvailable: Boolean = try {
        System.loadLibrary("llama_jni")
        true
    } catch (t: Throwable) {
        false
    }

    @JvmStatic external fun init(modelPath: String): Long
    @JvmStatic external fun close(handle: Long)
    @JvmStatic external fun generate(handle: Long, prompt: String): String
}

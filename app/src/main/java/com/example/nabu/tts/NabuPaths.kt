package com.example.nabu.tts

object NabuPaths {
    const val MODEL_ASSET = "kokoro/kokoro-v1.0.int8.onnx"
    const val VOICES_ASSET = "kokoro/voices.bin"
    const val VOICES_DIR = "kokoro/voices" // future per-voice drop-ins

    const val CHATTERBOX_ROOT = "chatterbox"
    const val CHATTERBOX_LANG = "en"

    fun modelsRoot(context: android.content.Context) =
        java.io.File(context.filesDir, "models")

    fun chatterboxModelDir(context: android.content.Context): java.io.File =
        java.io.File(modelsRoot(context), "$CHATTERBOX_ROOT/$CHATTERBOX_LANG")

    fun chatterboxOnnxDir(context: android.content.Context): java.io.File =
        java.io.File(chatterboxModelDir(context), "onnx")
}

package com.mewmix.nabu.engine.kokoro

import com.mewmix.nabu.core.settings.TextModeState
import com.mewmix.nabu.core.tts.G2PConfig
import com.mewmix.nabu.core.tts.Phonemizer

class AudioBuffer

interface KokoroFrontend {
    fun generateFromIpa(ipa: String): AudioBuffer
}

suspend fun synthesize(
    rawInput: String,
    textModeState: TextModeState,
    phonemizer: Phonemizer,
    kokoro: KokoroFrontend
): AudioBuffer {
    val ipa = phonemizer.toIpa(
        input = rawInput,
        mode = textModeState.mode,
        cfg = G2PConfig(langCode = textModeState.langCode)
    )
    return kokoro.generateFromIpa(ipa)
}

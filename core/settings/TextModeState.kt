package com.mewmix.nabu.core.settings

import com.mewmix.nabu.core.tts.TextMode

data class TextModeState(
    val mode: TextMode = TextMode.AUTO_G2P,
    val langCode: String = "en-us"
)

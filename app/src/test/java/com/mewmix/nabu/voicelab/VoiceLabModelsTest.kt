package com.mewmix.nabu.voicelab

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceLabModelsTest {
    @Test
    fun `preview uses first useful sentence for long scripts`() {
        val sentence = "This first sentence should become the preview because it is long enough."
        val rest = " More narration follows ".repeat(20)

        assertEquals(sentence, VoiceLabText.previewText(sentence + rest))
    }

    @Test
    fun `preview falls back to max character window when first sentence is too short`() {
        val script = "Hi. " + "A".repeat(300)

        assertEquals(VoiceLabText.PREVIEW_MAX_CHARS, VoiceLabText.previewText(script).length)
    }

    @Test
    fun `preview and render text reject blank scripts`() {
        assertEquals("", VoiceLabText.previewText(" \n\t "))
        assertEquals(null, VoiceLabText.renderableTextOrNull(" \n\t "))
    }

    @Test
    fun `render text trims surrounding whitespace`() {
        assertEquals("Render this.", VoiceLabText.renderableTextOrNull("  Render this.\n"))
    }

    @Test
    fun `synthesis result reports duration realtime factor and wav size`() {
        val result = VoiceLabSynthesisResult(
            audio = FloatArray(48_000),
            sampleRate = 24_000,
            engineId = "kokoro",
            engineName = "Kokoro",
            voiceId = "af_heart",
            inputCharacterCount = 42,
            modelId = "kokoro_int8",
            modelSizeBytes = 92_361_271,
            backend = "CPU",
            generationTimeMs = 1_000,
        )

        assertEquals(2.0f, result.audioDurationSeconds, 0.0001f)
        assertEquals(0.5f, result.realTimeFactor, 0.0001f)
        assertEquals(96_044L, result.wavSizeBytes)
    }
}

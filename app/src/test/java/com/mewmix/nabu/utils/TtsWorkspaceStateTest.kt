package com.mewmix.nabu.utils

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TtsWorkspaceStateTest {

    private val gson = Gson()

    @Test
    fun legacyAudioWorkspaceRemainsReadable() {
        val state = gson.fromJson(
            """{"text":"Hello","style":"af_sky","speed":1.1}""",
            AudioWorkspaceState::class.java,
        )

        assertEquals("Hello", state.text)
        assertEquals("af_sky", state.style)
        assertEquals(1.1f, state.speed, 0.0001f)
        assertNull(state.selection)
    }

    @Test
    fun mixerSnapshotRoundTripsEngineAndBlend() {
        val original = MixerSnapshot(
            voiceMix = VoiceMixConfig(
                styles = listOf("af_sky", "af_heart"),
                weights = mapOf("af_sky" to 0.7f, "af_heart" to 0.3f),
                interpolationMode = InterpolationMode.LINEAR,
            ),
            speed = 1.15f,
            selection = TtsWorkspaceSelection(
                engineId = "kokoro",
                voiceId = "af_sky",
                parameters = mapOf("speed" to "1.15"),
            ),
        )

        val restored = gson.fromJson(gson.toJson(original), MixerSnapshot::class.java)

        assertEquals(original, restored)
    }
}

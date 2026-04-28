package com.mewmix.nabu.utils

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VoiceMixSettingsTest {

    private lateinit var context: Context
    private val gson = Gson()

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        // Clear settings before each test
        DatabaseManager.setSetting(context, "voice_mix_config", "")
        DatabaseManager.setSetting(context, "voice_mix_favorites", "")
        DatabaseManager.setSetting(context, "voice_mix_favorites_migrated", "")
        DatabaseManager.setVoiceMixFavorites(context, emptyList())
    }

    @Test
    fun getVoiceMixConfig_handlesValidJson() {
        val validJson = """
            {
              "styles": ["af_sky", "af_bella"],
              "weights": {"af_sky": 0.5, "af_bella": 0.5},
              "interpolationMode": "SPHERICAL"
            }
        """.trimIndent()
        DatabaseManager.setSetting(context, "voice_mix_config", validJson)

        val config = SettingsManager.getVoiceMixConfig(context, "af_sky")
        
        assertEquals(listOf("af_sky", "af_bella"), config.styles)
        assertEquals(0.5f, config.weights["af_sky"]!!, 0.01f)
        assertEquals(InterpolationMode.SPHERICAL, config.interpolationMode)
    }

    @Test
    fun getVoiceMixConfig_handlesMissingFields_byFallingBack() {
        // Missing interpolationMode - this would cause NPE if not handled
        val corruptedJson = """
            {
              "styles": ["af_sky"],
              "weights": {"af_sky": 1.0}
            }
        """.trimIndent()
        DatabaseManager.setSetting(context, "voice_mix_config", corruptedJson)

        val config = SettingsManager.getVoiceMixConfig(context, "af_sky")
        
        // Should fall back to default because of IllegalStateException in getVoiceMixConfig
        assertEquals(listOf("af_sky"), config.styles)
        assertEquals(InterpolationMode.LINEAR, config.interpolationMode)
    }

    @Test
    fun getVoiceMixConfig_handlesCorruptedWeights_byFallingBack() {
        val corruptedJson = """
            {
              "styles": ["af_sky"],
              "weights": null,
              "interpolationMode": "LINEAR"
            }
        """.trimIndent()
        DatabaseManager.setSetting(context, "voice_mix_config", corruptedJson)

        val config = SettingsManager.getVoiceMixConfig(context, "af_sky")
        
        assertEquals(listOf("af_sky"), config.styles)
        assertNotNull(config.weights)
    }

    @Test
    fun getVoiceMixFavorites_filtersOutCorruptedEntries() {
        val corruptedFavoritesJson = """
            [
              {
                "name": "Good One",
                "styles": ["af_sky"],
                "weights": {"af_sky": 1.0},
                "interpolationMode": "LINEAR"
              },
              {
                "name": "Bad One",
                "styles": null,
                "weights": {},
                "interpolationMode": "LINEAR"
              }
            ]
        """.trimIndent()
        DatabaseManager.setSetting(context, "voice_mix_favorites", corruptedFavoritesJson)

        val favorites = SettingsManager.getVoiceMixFavorites(context)
        
        assertEquals(1, favorites.size)
        assertEquals("Good One", favorites[0].name)
    }

    @Test
    fun setVoiceMixFavorites_persistsInDatabaseTable() {
        val favorite = VoiceMixFavorite(
            name = "Space Mix",
            styles = listOf("af_sky", "af_bella"),
            weights = mapOf("af_sky" to 0.25f, "af_bella" to 0.75f),
            interpolationMode = InterpolationMode.SPHERICAL
        )

        SettingsManager.setVoiceMixFavorites(context, listOf(favorite))
        DatabaseManager.setSetting(context, "voice_mix_favorites", "")

        val favorites = SettingsManager.getVoiceMixFavorites(context)

        assertEquals(1, favorites.size)
        assertEquals("Space Mix", favorites[0].name)
        assertEquals(listOf("af_sky", "af_bella"), favorites[0].styles)
        assertEquals(0.75f, favorites[0].weights["af_bella"]!!, 0.01f)
        assertEquals(InterpolationMode.SPHERICAL, favorites[0].interpolationMode)
    }
}

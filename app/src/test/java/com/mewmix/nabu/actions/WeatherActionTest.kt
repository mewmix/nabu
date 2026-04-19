package com.mewmix.nabu.actions

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WeatherActionTest {

    @Before
    fun setUp() {
        WeatherAction.resetForTesting()
    }

    @After
    fun tearDown() {
        WeatherAction.resetForTesting()
    }

    @Test
    fun getWeather_formatsFriendlyResponse() {
        WeatherAction.jsonFetcher = { url ->
            when {
                "geocoding-api.open-meteo.com" in url -> JSONObject(
                    """
                        {
                          "results": [
                            {
                              "name": "Seattle",
                              "admin1": "Washington",
                              "country": "United States",
                              "latitude": 47.6062,
                              "longitude": -122.3321
                            }
                          ]
                        }
                    """.trimIndent()
                )
                "api.open-meteo.com" in url -> JSONObject(
                    """
                        {
                          "current": {
                            "temperature_2m": 18.4,
                            "weather_code": 3
                          },
                          "current_units": {
                            "temperature_2m": "°C"
                          }
                        }
                    """.trimIndent()
                )
                else -> null
            }
        }

        val result = WeatherAction.getWeather("Seattle")

        assertFalse(result.isError)
        assertEquals(
            "Weather in Seattle, Washington, United States: 18.4°C, overcast.",
            result.message
        )
    }

    @Test
    fun getWeather_returnsErrorWhenLocationIsMissing() {
        WeatherAction.jsonFetcher = { JSONObject("""{"results": []}""") }

        val result = WeatherAction.getWeather("Atlantis")

        assertTrue(result.isError)
        assertEquals("Location not found.", result.message)
    }
}

package com.mewmix.nabu.actions

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mewmix.nabu.tools.ToolCall
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ActionToolsTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AlarmTimerAction.resetForTesting()
        WeatherAction.resetForTesting()
        WebActionReasoner.resetForTesting()
    }

    @After
    fun tearDown() {
        AlarmTimerAction.resetForTesting()
        WeatherAction.resetForTesting()
        WebActionReasoner.resetForTesting()
    }

    @Test
    fun execute_setTimerRejectsFractionalSeconds() {
        val result = ActionTools.execute(
            context,
            ToolCall("set_timer", mapOf("seconds" to 1.5, "message" to "Tea"))
        )

        assertTrue(result?.isError == true)
        assertEquals("Invalid seconds format.", result?.output)
    }

    @Test
    fun execute_setAlarmPropagatesRangeErrors() {
        val result = ActionTools.execute(
            context,
            ToolCall("set_alarm", mapOf("hour" to 25, "minute" to 0, "message" to "Nope"))
        )

        assertTrue(result?.isError == true)
        assertEquals("Invalid alarm time. Hour must be 0-23 and minute 0-59.", result?.output)
    }

    @Test
    fun execute_getWeatherMarksFetcherFailureAsError() {
        WeatherAction.jsonFetcher = { null }

        val result = ActionTools.execute(
            context,
            ToolCall("get_weather", mapOf("location" to "Seattle"))
        )

        assertTrue(result?.isError == true)
        assertEquals("Failed to geocode location.", result?.output)
    }

    @Test
    fun execute_searchWebContextMarksFetcherFailureAsError() {
        WebActionReasoner.searchPageFetcher = {
            WebActionReasoner.SearchPageResult(errorMessage = "Web search failed: timeout")
        }

        val result = ActionTools.execute(
            context,
            ToolCall("search_web_context", mapOf("query" to "latest weather radar"))
        )

        assertTrue(result?.isError == true)
        assertEquals("Web search failed: timeout", result?.output)
    }

    @Test
    fun execute_searchWebContextReturnsSummarizedHits() {
        WebActionReasoner.searchPageFetcher = {
            WebActionReasoner.SearchPageResult(
                html = """
                    <html><body>
                      <div class="result">
                        <a class="result__a" href="https://example.com/forecast">Forecast</a>
                        <a class="result__snippet">Sunny all week</a>
                      </div>
                    </body></html>
                """.trimIndent()
            )
        }

        val result = ActionTools.execute(
            context,
            ToolCall("search_web_context", mapOf("query" to "forecast"))
        )

        assertFalse(result?.isError ?: true)
        assertEquals("- Forecast: Sunny all week (https://example.com/forecast)", result?.output)
    }
}

package com.mewmix.nabu.actions

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WebActionReasonerTest {

    @Before
    fun setUp() {
        WebActionReasoner.resetForTesting()
    }

    @After
    fun tearDown() {
        WebActionReasoner.resetForTesting()
    }

    @Test
    fun search_decodesDuckDuckGoRedirectUrls() {
        WebActionReasoner.searchPageFetcher = {
            WebActionReasoner.SearchPageResult(
                html = """
                    <html><body>
                      <div class="result">
                        <a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fstorm">Storm Update</a>
                        <div class="result__snippet">Storm arrives at 8:30 PM</div>
                      </div>
                    </body></html>
                """.trimIndent()
            )
        }

        val result = WebActionReasoner.search("storm update")

        assertFalse(result.isError)
        assertEquals(1, result.hits.size)
        assertEquals("https://example.com/storm", result.hits.single().url)
        assertEquals(
            "- Storm Update: Storm arrives at 8:30 PM (https://example.com/storm)",
            WebActionReasoner.summarize(result)
        )
    }

    @Test
    fun search_returnsFetcherErrors() {
        WebActionReasoner.searchPageFetcher = {
            WebActionReasoner.SearchPageResult(errorMessage = "Web search failed with HTTP 503.")
        }

        val result = WebActionReasoner.search("weather radar")

        assertTrue(result.isError)
        assertEquals("Web search failed with HTTP 503.", result.message)
        assertEquals("Web search failed with HTTP 503.", WebActionReasoner.summarize(result))
    }
}

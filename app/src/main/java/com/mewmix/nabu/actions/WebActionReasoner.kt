package com.mewmix.nabu.actions

import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import java.util.concurrent.TimeUnit

object WebActionReasoner {
    data class SearchHit(val title: String, val snippet: String, val url: String)

    data class SearchPageResult(
        val html: String? = null,
        val errorMessage: String? = null
    )

    data class SearchResult(
        val hits: List<SearchHit>,
        val isError: Boolean = false,
        val message: String? = null
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()
    private val monthFormatters = listOf(
        DateTimeFormatter.ofPattern("MMMM d, yyyy h:mm a", Locale.US),
        DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a", Locale.US)
    )
    private val isoFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    internal var searchPageFetcher: (String) -> SearchPageResult = ::fetchSearchPageFromNetwork

    internal fun resetForTesting() {
        searchPageFetcher = ::fetchSearchPageFromNetwork
    }

    fun search(query: String, limit: Int = 5): SearchResult {
        if (query.isBlank()) return SearchResult(emptyList(), isError = true, message = "Missing required parameter: query")
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        val url = "https://html.duckduckgo.com/html/?q=$encoded"
        val page = searchPageFetcher(url)
        if (page.errorMessage != null) {
            return SearchResult(emptyList(), isError = true, message = page.errorMessage)
        }
        val html = page.html?.trim().orEmpty()
        if (html.isBlank()) {
            return SearchResult(emptyList(), isError = true, message = "Web search returned an empty response.")
        }
        val hits = parseSearchHits(html, limit)
        return if (hits.isEmpty()) {
            SearchResult(emptyList(), message = "No web results found.")
        } else {
            SearchResult(hits)
        }
    }

    fun inferTimeFromWeb(request: String): Long? {
        val hits = search(request).hits
        if (hits.isEmpty()) return null
        val now = System.currentTimeMillis()
        val candidates = mutableListOf<Long>()
        hits.forEach { hit ->
            candidates += parseDateTimes("${hit.title} ${hit.snippet}")
        }
        return candidates.sorted().firstOrNull { it > now }
    }

    fun summarize(result: SearchResult): String {
        if (result.hits.isEmpty()) {
            return result.message ?: "No web results found."
        }
        return result.hits.joinToString("\n") { hit ->
            if (hit.snippet.isBlank()) {
                "- ${hit.title} (${hit.url})"
            } else {
                "- ${hit.title}: ${hit.snippet} (${hit.url})"
            }
        }
    }

    internal fun parseSearchHits(html: String, limit: Int = 5): List<SearchHit> {
        val doc = Jsoup.parse(html)
        val nodes = doc.select(".result, .results_links, .web-result")
        val hits = mutableListOf<SearchHit>()
        val seenUrls = linkedSetOf<String>()
        nodes.forEach { node ->
            if (hits.size >= limit) return@forEach
            val hit = parseSearchHit(node) ?: return@forEach
            if (seenUrls.add(hit.url)) {
                hits += hit
            }
        }
        return hits
    }

    private fun parseSearchHit(node: Element): SearchHit? {
        val titleNode = node.selectFirst(".result__a, h2 a, a[data-testid=result-title-a]")
            ?: return null
        val title = titleNode.text().trim()
        val url = normalizeResultUrl(titleNode.attr("href").trim()) ?: return null
        val snippet = node.selectFirst(".result__snippet, .result-snippet, .snippet")?.text().orEmpty().trim()
        if (title.isBlank()) return null
        return SearchHit(title = title, snippet = snippet, url = url)
    }

    internal fun normalizeResultUrl(rawHref: String): String? {
        if (rawHref.isBlank()) return null
        val normalized = when {
            rawHref.startsWith("//") -> "https:$rawHref"
            rawHref.startsWith("/") -> "https://duckduckgo.com$rawHref"
            else -> rawHref
        }
        val encodedTarget = Regex("""[?&]uddg=([^&]+)""").find(normalized)?.groupValues?.get(1)
        return if (encodedTarget != null) {
            URLDecoder.decode(encodedTarget, Charsets.UTF_8.name())
        } else {
            normalized
        }
    }

    private fun fetchSearchPageFromNetwork(url: String): SearchPageResult {
        val request = Request.Builder().url(url).header("User-Agent", "Nabu/1.0").build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return SearchPageResult(errorMessage = "Web search failed with HTTP ${response.code}.")
                }
                SearchPageResult(html = response.body?.string().orEmpty())
            }
        }.getOrElse {
            SearchPageResult(errorMessage = "Web search failed: ${it.message}")
        }
    }

    private fun parseDateTimes(text: String): List<Long> {
        val results = mutableListOf<Long>()
        val isoMatches = Regex("""\b(\d{4}-\d{2}-\d{2})[ T](\d{1,2}:\d{2})\b""")
            .findAll(text)
            .map { "${it.groupValues[1]} ${it.groupValues[2]}" }
            .toList()
        isoMatches.forEach { raw ->
            runCatching {
                LocalDateTime.parse(raw, isoFormatter)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            }.getOrNull()?.let(results::add)
        }

        val monthRegex = Regex("""\b([A-Z][a-z]{2,8}\s+\d{1,2},\s+\d{4}\s+\d{1,2}:\d{2}\s*(AM|PM|am|pm))\b""")
        monthRegex.findAll(text).forEach { m ->
            val raw = m.groupValues[1].uppercase().replace("AM", " AM").replace("PM", " PM").replace("  ", " ").trim()
            monthFormatters.forEach { formatter ->
                try {
                    val parsed = LocalDateTime.parse(raw, formatter)
                        .atZone(ZoneId.systemDefault())
                        .toInstant()
                        .toEpochMilli()
                    results += parsed
                    return@forEach
                } catch (_: DateTimeParseException) {
                }
            }
        }

        val timeOnlyRegex = Regex("""\b(\d{1,2}:\d{2})\s*(AM|PM|am|pm)\b""")
        timeOnlyRegex.findAll(text).forEach { match ->
            val raw = "${LocalDate.now()} ${match.groupValues[1]} ${match.groupValues[2].uppercase()}"
            runCatching {
                LocalDateTime.parse(raw, DateTimeFormatter.ofPattern("yyyy-MM-dd h:mm a", Locale.US))
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            }.getOrNull()?.let(results::add)
        }

        return results
    }
}

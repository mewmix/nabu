package com.mewmix.nabu.actions

import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

object WebActionReasoner {
    private val client = OkHttpClient()
    private val monthFormatters = listOf(
        DateTimeFormatter.ofPattern("MMMM d, yyyy h:mm a", Locale.US),
        DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a", Locale.US)
    )
    private val isoFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    data class SearchHit(val title: String, val snippet: String, val url: String)

    fun search(query: String, limit: Int = 5): List<SearchHit> {
        if (query.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        val url = "https://duckduckgo.com/html/?q=$encoded"
        val req = Request.Builder().url(url).header("User-Agent", "Nabu/1.0").build()
        return runCatching {
            client.newCall(req).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val html = response.body?.string().orEmpty()
                val doc = Jsoup.parse(html)
                doc.select(".result")
                    .take(limit)
                    .mapNotNull { node ->
                        val titleNode = node.selectFirst(".result__a") ?: return@mapNotNull null
                        val snippet = node.selectFirst(".result__snippet")?.text().orEmpty().trim()
                        SearchHit(
                            title = titleNode.text().trim(),
                            snippet = snippet,
                            url = titleNode.attr("href").trim()
                        )
                    }
            }
        }.getOrDefault(emptyList())
    }

    fun inferTimeFromWeb(request: String): Long? {
        val hits = search(request)
        if (hits.isEmpty()) return null
        val now = System.currentTimeMillis()
        val candidates = mutableListOf<Long>()
        hits.forEach { hit ->
            candidates += parseDateTimes("${hit.title} ${hit.snippet}")
        }
        return candidates.sorted().firstOrNull { it > now }
    }

    fun summarize(hits: List<SearchHit>): String {
        if (hits.isEmpty()) return "No web results found."
        return hits.joinToString("\n") { "- ${it.title}: ${it.snippet} (${it.url})" }
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

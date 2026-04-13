package com.mewmix.nabu.actions

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

object FuzzyActionInterpreter {
    private val client = OkHttpClient()
    private val sunsetRegex = Regex("""remind me when the sun (goes down|sets) in (.+)""", RegexOption.IGNORE_CASE)

    fun resolveInstruction(raw: String): ResolvedAction? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null

        val sunsetMatch = sunsetRegex.matchEntire(trimmed)
        if (sunsetMatch != null) {
            val location = sunsetMatch.groupValues[2].trim().trimEnd('.', '!', '?')
            return resolveSunsetReminder(location)
        }

        return null
    }

    private fun resolveSunsetReminder(location: String): ResolvedAction? {
        val encoded = URLEncoder.encode(location, Charsets.UTF_8.name())
        val geoUrl = "https://geocoding-api.open-meteo.com/v1/search?name=$encoded&count=1"
        val geoResp = fetchJson(geoUrl) ?: return null
        val geoResult = geoResp.optJSONArray("results")?.optJSONObject(0) ?: return null
        val lat = geoResult.optDouble("latitude", Double.NaN)
        val lon = geoResult.optDouble("longitude", Double.NaN)
        if (lat.isNaN() || lon.isNaN()) return null

        val today = LocalDate.now(ZoneId.systemDefault())
        val forecastUrl = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&daily=sunset&timezone=auto&start_date=$today&end_date=$today"
        val sunsetResp = fetchJson(forecastUrl) ?: return null
        val sunsetIso = sunsetResp
            .optJSONObject("daily")
            ?.optJSONArray("sunset")
            ?.optString(0)
            ?.trim()
            .orEmpty()
        if (sunsetIso.isBlank()) return null

        val sunsetEpochMs = runCatching { Instant.parse(sunsetIso).toEpochMilli() }.getOrElse {
            runCatching {
                LocalDateTime.parse(sunsetIso).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            }.getOrNull()
        } ?: return null

        val nextTrigger = if (sunsetEpochMs <= System.currentTimeMillis()) {
            sunsetEpochMs + (24L * 60L * 60L * 1000L)
        } else {
            sunsetEpochMs
        }

        return ResolvedAction(
            title = "Sunset reminder: $location",
            instruction = "Sunset is happening now in $location.",
            triggerAtEpochMs = nextTrigger,
            recurrence = ScheduledAction.RECURRENCE_DAILY
        )
    }

    private fun fetchJson(url: String): JSONObject? {
        val request = Request.Builder().url(url).header("User-Agent", "Nabu/1.0").build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                JSONObject(response.body?.string().orEmpty())
            }
        }.getOrNull()
    }

    data class ResolvedAction(
        val title: String,
        val instruction: String,
        val triggerAtEpochMs: Long,
        val recurrence: String
    )
}

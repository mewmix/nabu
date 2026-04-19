package com.mewmix.nabu.actions

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit

object WeatherAction {
    data class WeatherResult(
        val message: String,
        val isError: Boolean = false
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    internal var jsonFetcher: (String) -> JSONObject? = ::fetchJsonFromNetwork

    internal fun resetForTesting() {
        jsonFetcher = ::fetchJsonFromNetwork
    }

    fun getWeather(location: String): WeatherResult {
        val encoded = URLEncoder.encode(location, Charsets.UTF_8.name())
        val geoUrl = "https://geocoding-api.open-meteo.com/v1/search?name=$encoded&count=1"
        val geoResp = jsonFetcher(geoUrl) ?: return WeatherResult("Failed to geocode location.", true)
        val geoResult = geoResp.optJSONArray("results")?.optJSONObject(0)
            ?: return WeatherResult("Location not found.", true)
        val lat = geoResult.optDouble("latitude", Double.NaN)
        val lon = geoResult.optDouble("longitude", Double.NaN)
        if (lat.isNaN() || lon.isNaN()) {
            return WeatherResult("Invalid coordinates returned for $location.", true)
        }

        val name = buildLocationLabel(geoResult, location)

        val weatherUrl = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code&timezone=auto"
        val weatherResp = jsonFetcher(weatherUrl) ?: return WeatherResult("Failed to fetch weather data.", true)
        val current = weatherResp.optJSONObject("current") ?: return WeatherResult("No current weather data.", true)
        val temp = current.optDouble("temperature_2m", Double.NaN)
        if (temp.isNaN()) {
            return WeatherResult("No current weather data.", true)
        }
        val code = current.optInt("weather_code", -1)
        val units = weatherResp.optJSONObject("current_units")
            ?.optString("temperature_2m")
            .orEmpty()
            .ifBlank { "°C" }
        val conditions = describeWeatherCode(code)

        val message = buildString {
            append("Weather in ")
            append(name)
            append(": ")
            append(formatTemperature(temp))
            append(units)
            when {
                conditions != null -> {
                    append(", ")
                    append(conditions)
                    append(".")
                }
                code >= 0 -> {
                    append(" (weather code: ")
                    append(code)
                    append(").")
                }
                else -> append(".")
            }
        }
        return WeatherResult(message)
    }

    internal fun describeWeatherCode(code: Int): String? = when (code) {
        0 -> "clear sky"
        1, 2 -> "partly cloudy"
        3 -> "overcast"
        45, 48 -> "fog"
        51, 53, 55 -> "drizzle"
        56, 57 -> "freezing drizzle"
        61, 63, 65 -> "rain"
        66, 67 -> "freezing rain"
        71, 73, 75, 77 -> "snow"
        80, 81, 82 -> "rain showers"
        85, 86 -> "snow showers"
        95 -> "thunderstorm"
        96, 99 -> "thunderstorm with hail"
        else -> null
    }

    private fun buildLocationLabel(geoResult: JSONObject, fallback: String): String {
        return listOf(
            geoResult.optString("name"),
            geoResult.optString("admin1"),
            geoResult.optString("country")
        )
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(", ")
            .ifBlank { fallback }
    }

    private fun formatTemperature(value: Double): String {
        return if (value % 1.0 == 0.0) {
            value.toInt().toString()
        } else {
            String.format(Locale.US, "%.1f", value)
        }
    }

    private fun fetchJsonFromNetwork(url: String): JSONObject? {
        val request = Request.Builder().url(url).header("User-Agent", "Nabu/1.0").build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                JSONObject(response.body?.string().orEmpty())
            }
        }.getOrNull()
    }
}

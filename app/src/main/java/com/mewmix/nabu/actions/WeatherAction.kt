package com.mewmix.nabu.actions

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

object WeatherAction {
    private val client = OkHttpClient()

    fun getWeather(location: String): String {
        val encoded = URLEncoder.encode(location, Charsets.UTF_8.name())
        val geoUrl = "https://geocoding-api.open-meteo.com/v1/search?name=$encoded&count=1"
        val geoResp = fetchJson(geoUrl) ?: return "Failed to geocode location."
        val geoResult = geoResp.optJSONArray("results")?.optJSONObject(0) ?: return "Location not found."
        val lat = geoResult.optDouble("latitude", Double.NaN)
        val lon = geoResult.optDouble("longitude", Double.NaN)
        val name = geoResult.optString("name", location)
        
        if (lat.isNaN() || lon.isNaN()) return "Invalid coordinates."

        val weatherUrl = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code&timezone=auto"
        val weatherResp = fetchJson(weatherUrl) ?: return "Failed to fetch weather data."
        val current = weatherResp.optJSONObject("current") ?: return "No current weather data."
        val temp = current.optDouble("temperature_2m", Double.NaN)
        val code = current.optInt("weather_code", -1)
        
        return "Weather in $name: Temperature is $temp°C. (Weather code: $code)"
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
}
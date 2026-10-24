package com.dananz.window

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

// Home Assistant over its REST API, signed in as Pane (HaAuth.kt)
object HomeAssistant {
    private fun open(path: String): HttpURLConnection =
        (URL(Ha.url + path).openConnection() as HttpURLConnection).apply {
            connectTimeout = 4000
            readTimeout = 6000
            setRequestProperty("Authorization", "Bearer ${Ha.token()}")
            setRequestProperty("Content-Type", "application/json")
        }

    suspend fun state(entity: String): JSONObject = get("/api/states/$entity")

    suspend fun get(path: String): JSONObject = withContext(Dispatchers.IO) {
        open(path).run { inputStream.bufferedReader().use { JSONObject(it.readText()) } }
    }

    // Every entity, as /api/states lists them
    suspend fun states(): org.json.JSONArray = withContext(Dispatchers.IO) {
        open("/api/states").run { inputStream.bufferedReader().use { org.json.JSONArray(it.readText()) } }
    }

    // A service call; with response = true, what the service answers (as for weather forecasts)
    suspend fun call(domain: String, service: String, data: JSONObject, response: Boolean = false): JSONObject = withContext(Dispatchers.IO) {
        open("/api/services/$domain/$service" + if (response) "?return_response" else "").run {
            requestMethod = "POST"
            doOutput = true
            outputStream.use { it.write(data.toString().toByteArray()) }
            inputStream.bufferedReader().use { it.readText() }.let { if (it.startsWith("{")) JSONObject(it) else JSONObject() }
        }
    }
}

enum class Sky { Clear, Cloudy, Rain }

data class Day(val date: LocalDate, val sky: Sky, val high: Int, val low: Int)
data class Weather(val temp: Int, val sky: Sky, val days: List<Day>, val sunUp: Boolean)

private fun sky(condition: String) = when (condition) {
    "sunny", "clear-night", "windy" -> Sky.Clear
    "rainy", "pouring", "lightning", "lightning-rainy", "hail", "snowy", "snowy-rainy" -> Sky.Rain
    else -> Sky.Cloudy
}

const val FORECAST = "weather.forecast_home"

suspend fun loadWeather(): Weather {
    val now = HomeAssistant.state(FORECAST)
    val sun = HomeAssistant.state("sun.sun")
    val list = HomeAssistant.call("weather", "get_forecasts", JSONObject().put("entity_id", FORECAST).put("type", "daily"), response = true)
        .getJSONObject("service_response").getJSONObject(FORECAST).getJSONArray("forecast")
    val zone = ZoneId.systemDefault()
    val days = (0 until minOf(5, list.length())).map { i ->
        val d = list.getJSONObject(i)
        Day(
            OffsetDateTime.parse(d.getString("datetime")).atZoneSameInstant(zone).toLocalDate(),
            sky(d.getString("condition")),
            Math.round(d.getDouble("temperature")).toInt(),
            Math.round(d.optDouble("templow", d.getDouble("temperature"))).toInt(),
        )
    }
    val attrs = now.getJSONObject("attributes")
    return Weather(Math.round(attrs.getDouble("temperature")).toInt(), sky(now.getString("state")), days, sun.getString("state") == "above_horizon")
}

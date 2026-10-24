package com.dananz.window

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// Sensibo AC controllers, through Sensibo's cloud with the account's API key (home.sensibo.com/me/api).
// Each pod is an AC on the wall, the same big control as Home Assistant's.
// API: https://home.sensibo.com/api/v2 (Sensibo's own SDK: github.com/Sensibo/sensibo-python-sdk)
//
// A Sensibo tile's entity is "sensibo/<pod id>".
object Sensibo {
    private const val API = "https://home.sensibo.com/api/v2"

    private lateinit var sp: SharedPreferences

    var key by mutableStateOf("")
        private set

    data class Pod(val entity: String, val name: String)

    fun load(context: Context) {
        if (::sp.isInitialized) return
        sp = context.getSharedPreferences("sensibo", Context.MODE_PRIVATE)
        key = sp.getString("key", "").orEmpty()
    }

    fun changeKey(v: String) { key = v.trim(); sp.edit().putString("key", key).apply() }

    fun isSensibo(entity: String) = entity.startsWith("sensibo/")
    private fun pod(entity: String) = entity.substringAfter('/')
    const val LABEL = "Sensibo"

    suspend fun pods(): List<Pod> {
        if (key.isEmpty()) return emptyList()
        val list = request("GET", "/users/me/pods", "fields=id,room").getJSONArray("result")
        return (0 until list.length()).map { list.getJSONObject(it) }.map { p ->
            Pod("sensibo/" + p.getString("id"), p.optJSONObject("room")?.optString("name").orEmpty().ifBlank { "Sensibo" })
        }
    }

    // Null when the key is good; else what went wrong
    suspend fun check(): String? = runCatching { request("GET", "/users/me/pods", "fields=id"); null }.getOrElse { it.message ?: "" }

    // The pod's last state as Sensibo sent it, so a change keeps what the wall doesn't show (swing, light)
    private val raw = mutableMapOf<String, JSONObject>()

    suspend fun state(entity: String, was: Ac): Ac {
        val p = request("GET", "/pods/${pod(entity)}", "fields=acState,measurements,remoteCapabilities").getJSONObject("result")
        val s = p.getJSONObject("acState")
        raw[entity] = s
        val mode = AcMode.entries.firstOrNull { it.ha == s.optString("mode") } ?: was.mode
        // The range the remote allows in this mode, in the unit the AC is set to
        val temps = p.optJSONObject("remoteCapabilities")?.optJSONObject("modes")?.optJSONObject(mode.ha)
            ?.optJSONObject("temperatures")?.optJSONObject(s.optString("temperatureUnit", "C"))?.optJSONArray("values")
        val range = temps?.let { a -> (0 until a.length()).map { a.getInt(it) } }.orEmpty()
        return Ac(
            on = s.optBoolean("on"),
            mode = mode,
            temp = s.optInt("targetTemperature", was.temp),
            fan = FANS.indexOfFirst { it.first == s.optString("fanLevel") }.takeIf { it >= 0 } ?: was.fan,
            room = p.optJSONObject("measurements")?.optDouble("temperature")?.takeIf { !it.isNaN() }?.toFloat(),
            min = range.minOrNull() ?: was.min,
            max = range.maxOrNull() ?: was.max,
            step = 1,
        )
    }

    // Sends the whole state at once: one call however many things changed
    suspend fun set(entity: String, ac: Ac) {
        val s = JSONObject((raw[entity] ?: JSONObject()).toString())
            .put("on", ac.on).put("mode", ac.mode.ha).put("targetTemperature", ac.temp).put("fanLevel", FANS[ac.fan].first)
        if (!s.has("temperatureUnit")) s.put("temperatureUnit", "C")
        request("POST", "/pods/${pod(entity)}/acStates", null, JSONObject().put("acState", s))
        raw[entity] = s
    }

    private suspend fun request(method: String, path: String, query: String?, body: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
        val q = listOfNotNull(query, "apiKey=" + URLEncoder.encode(key, "UTF-8")).joinToString("&")
        val c = (URL("$API$path?$q").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 5000
            readTimeout = 8000
            setRequestProperty("Content-Type", "application/json")
            if (body != null) { doOutput = true; outputStream.use { it.write(body.toString().toByteArray()) } }
        }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        when (code) {
            401, 403 -> error("The key wasn't accepted")
            429 -> error("Sensibo asked to slow down")
            !in 200..299 -> error("Sensibo answered $code")
        }
        JSONObject(text)
    }
}

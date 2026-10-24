package com.dananz.window

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.roundToInt

// What a tile on the wall switches, as Home Assistant has it
enum class Kind { Light, Climate, Switch, Fan, Cover, Scene }
data class Tile(val entity: String, val name: String, val kind: Kind)

// A tile's live state. Lights have a level; the rest are on (100) or off (0), and a scene is 0
data class Light(val id: String, val entity: String, val name: String, val level: Int, val kind: Kind = Kind.Light)

// The three lights the room photo knows: their pools light up in the room at night
private val ROOM_IDS = mapOf("light.living_room_ceiling" to "ceiling", "light.kitchen_led" to "kitchen", "light.living_room_miznon_rgb" to "miznon")
fun tileId(entity: String) = ROOM_IDS[entity] ?: entity.replace(Regex("[^a-z0-9]"), "_")

enum class AcMode(val ha: String, val label: String, val doing: String) { Cool("cool", "Cool", "Cooling"), Heat("heat", "Heat", "Heating") }

data class Ac(
    val on: Boolean, val mode: AcMode, val temp: Int, val fan: Int, val room: Float?,
    val min: Int, val max: Int, val step: Int,
)

// Fan speeds in the order the remote walks them; Home Assistant's names
val FANS = listOf("auto" to "Auto", "low" to "Low", "medium" to "Med", "high" to "High")

class Room(private val scope: CoroutineScope) {
    // The tiles on the wall, in order: the first AC is the big control at the row's end, every other
    // tile sits before it
    var lights by mutableStateOf(emptyList<Light>())
        private set
    var acEntity by mutableStateOf<String?>(null)
        private set
    var ac by mutableStateOf(Ac(false, AcMode.Cool, 25, 1, null, 20, 25, 5))
        private set

    // The tiles changed in settings: keep what is known of each, read the rest on the next poll
    fun sync(tiles: List<Tile>) {
        val main = tiles.firstOrNull { it.kind == Kind.Climate }
        acEntity = main?.entity
        lights = tiles.filter { it !== main }.map { t ->
            lights.firstOrNull { it.entity == t.entity }?.copy(name = t.name) ?: Light(tileId(t.entity), t.entity, t.name, 0, t.kind)
        }
        quietUntil = 0
        scope.launch { refresh() }
    }

    // A change made here wins over what a poll reads back, until Home Assistant has caught up
    private var quietUntil = 0L
    private val pending = mutableMapOf<String, Job>()

    suspend fun refresh() {
        if (System.currentTimeMillis() < quietUntil) return
        val fresh = lights.map { l ->
            runCatching {
                val s = HomeAssistant.state(l.entity)
                val st = s.getString("state")
                l.copy(level = when (l.kind) {
                    Kind.Light -> if (st == "on") maxOf(1, (s.getJSONObject("attributes").optInt("brightness", 255) / 255f * 100).roundToInt()) else 0
                    Kind.Cover -> if (st == "open" || st == "opening") 100 else 0
                    Kind.Scene -> 0
                    Kind.Climate -> if (st != "off" && st != "unavailable") 100 else 0
                    else -> if (st == "on") 100 else 0
                })
            }.getOrDefault(l)
        }
        val entity = acEntity
        val acNow = if (entity == null) ac else runCatching {
            val s = HomeAssistant.state(entity)
            val a = s.getJSONObject("attributes")
            val st = s.getString("state")
            Ac(
                on = st != "off",
                mode = AcMode.entries.firstOrNull { it.ha == st } ?: ac.mode,
                temp = a.optDouble("temperature", ac.temp.toDouble()).roundToInt(),
                fan = FANS.indexOfFirst { it.first == a.optString("fan_mode") }.takeIf { it >= 0 } ?: ac.fan,
                room = a.optDouble("current_temperature").takeIf { !it.isNaN() }?.toFloat(),
                min = a.optDouble("min_temp", 16.0).roundToInt(),
                max = a.optDouble("max_temp", 30.0).roundToInt(),
                step = maxOf(1, a.optDouble("target_temp_step", 1.0).roundToInt()),
            )
        }.getOrDefault(ac)
        if (System.currentTimeMillis() < quietUntil) return
        lights = fresh
        ac = acNow
        sentAc = acNow
    }

    fun poll() = scope.launch { while (true) { refresh(); delay(5000) } }

    // Sends the newest value only: a held button or quick presses don't queue a call each
    private fun send(key: String, wait: Long = 250, block: suspend () -> Unit) {
        quietUntil = System.currentTimeMillis() + 4000
        pending[key]?.cancel()
        pending[key] = scope.launch { delay(wait); runCatching { block() } }
    }

    fun setLevel(id: String, level: Int) {
        val l = lights.firstOrNull { it.id == id } ?: return
        val v = if (l.kind == Kind.Light) level.coerceIn(0, 100) else if (level > 0) 100 else 0
        if (l.kind != Kind.Scene) lights = lights.map { if (it.id == id) it.copy(level = v) else it }
        val e = JSONObject().put("entity_id", l.entity)
        send(l.entity) {
            when (l.kind) {
                Kind.Light -> if (v == 0) HomeAssistant.call("light", "turn_off", e) else HomeAssistant.call("light", "turn_on", e.put("brightness_pct", v))
                Kind.Scene -> HomeAssistant.call("scene", "turn_on", e)
                Kind.Cover -> HomeAssistant.call("cover", if (v > 0) "open_cover" else "close_cover", e)
                else -> HomeAssistant.call("homeassistant", if (v > 0) "turn_on" else "turn_off", e)
            }
        }
    }

    // What Home Assistant last heard, so quick changes in a row each still reach it
    private var sentAc: Ac? = null

    fun changeAc(next: Ac) {
        val AC = acEntity ?: return
        ac = next
        send(AC) {
            val before = sentAc ?: next.copy(on = !next.on)
            when {
                !next.on -> HomeAssistant.call("climate", "set_hvac_mode", JSONObject().put("entity_id", AC).put("hvac_mode", "off"))
                else -> {
                    if (!before.on || before.mode != next.mode) HomeAssistant.call("climate", "set_hvac_mode", JSONObject().put("entity_id", AC).put("hvac_mode", next.mode.ha))
                    if (before.temp != next.temp) HomeAssistant.call("climate", "set_temperature", JSONObject().put("entity_id", AC).put("temperature", next.temp))
                    if (before.fan != next.fan) HomeAssistant.call("climate", "set_fan_mode", JSONObject().put("entity_id", AC).put("fan_mode", FANS[next.fan].first))
                }
            }
            sentAc = next
        }
    }

    fun stepTemp(d: Int) = changeAc(ac.copy(on = true, temp = (ac.temp + d * ac.step).coerceIn(ac.min, ac.max)))
}

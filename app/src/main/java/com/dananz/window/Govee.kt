package com.dananz.window

import android.content.Context
import android.content.SharedPreferences
import android.net.wifi.WifiManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.util.UUID

// Govee lights, two ways: on the home network with Govee's LAN API (each device's "LAN Control" on in
// the Govee Home app), or through Govee's cloud with an API key from the app. A device that answers on
// the network is driven there; the key reaches the rest.
// LAN: https://app-h5.govee.com/user-manual/wlan-guide · Cloud: https://developer.govee.com
//
// A Govee tile's entity is "govee/<sku>/<device id>".
object Govee {
    private const val CLOUD = "https://openapi.api.govee.com/router/api/v1"
    private const val GROUP = "239.255.255.250"
    private const val SCAN_PORT = 4001
    private const val REPLY_PORT = 4002
    private const val CONTROL_PORT = 4003

    private lateinit var sp: SharedPreferences
    private lateinit var wifi: WifiManager

    var key by mutableStateOf("")
        private set
    // Devices that answered the last scan: device id → address on the network
    var lan by mutableStateOf(emptyMap<String, Lan>())
        private set
    private var scanned = 0L

    data class Lan(val sku: String, val ip: String)
    data class Device(val entity: String, val name: String, val dims: Boolean)

    fun load(context: Context) {
        if (::sp.isInitialized) return
        sp = context.getSharedPreferences("govee", Context.MODE_PRIVATE)
        wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        key = sp.getString("key", "").orEmpty()
    }

    fun changeKey(v: String) { key = v.trim(); sp.edit().putString("key", key).apply() }

    fun isGovee(entity: String) = entity.startsWith("govee/")
    private fun entity(sku: String, device: String) = "govee/$sku/$device"
    private fun sku(entity: String) = entity.split('/')[1]
    private fun device(entity: String) = entity.substringAfter('/').substringAfter('/')
    fun label(entity: String) = "Govee · ${sku(entity)}"

    // ---------- Finding devices ----------

    // Everything Govee can show: the account's devices with their names, and whatever answers on the
    // network (by model only, when the key doesn't know it)
    suspend fun devices(): List<Device> {
        val cloud = if (key.isEmpty()) emptyList() else runCatching { cloudDevices() }.getOrDefault(emptyList())
        val local = scan()
        val known = cloud.map { device(it.entity) }.toSet()
        return cloud + local.filterKeys { it !in known }.map { (id, l) -> Device(entity(l.sku, id), "Govee ${l.sku}", true) }
    }

    // Is the key good? Null when it is; else what went wrong, for the page to say
    suspend fun check(): String? = runCatching { cloudDevices(); null }.getOrElse { it.message ?: "" }

    private suspend fun cloudDevices(): List<Device> {
        val data = request("GET", "/user/devices", null).getJSONArray("data")
        return (0 until data.length()).map { data.getJSONObject(it) }.map { d ->
            val caps = d.optJSONArray("capabilities")
            val dims = caps != null && (0 until caps.length()).any { caps.getJSONObject(it).optString("instance") == "brightness" }
            Device(entity(d.getString("sku"), d.getString("device")), d.optString("deviceName").ifBlank { d.getString("sku") }, dims)
        }
    }

    // Asks the network who's there; devices answer to port 4002 with their address
    suspend fun scan(): Map<String, Lan> = lanLock.withLock {
        withContext(Dispatchers.IO) {
            val found = mutableMapOf<String, Lan>()
            val lock = runCatching { wifi.createMulticastLock("pane-govee").apply { acquire() } }.getOrNull()
            runCatching {
                replySocket().use { s ->
                    val ask = """{"msg":{"cmd":"scan","data":{"account_topic":"reserve"}}}""".toByteArray()
                    DatagramSocket().use { it.send(DatagramPacket(ask, ask.size, InetAddress.getByName(GROUP), SCAN_PORT)) }
                    val end = System.currentTimeMillis() + 2500
                    while (System.currentTimeMillis() < end) {
                        val msg = receive(s, (end - System.currentTimeMillis()).toInt().coerceAtLeast(1)) ?: break
                        if (msg.second.optString("cmd") != "scan") continue
                        val d = msg.second.getJSONObject("data")
                        found[d.getString("device")] = Lan(d.getString("sku"), d.optString("ip").ifBlank { msg.first })
                    }
                }
            }
            runCatching { lock?.release() }
            scanned = System.currentTimeMillis()
            if (found.isNotEmpty() || lan.isEmpty()) lan = found
            found
        }
    }

    // A fresh look every few minutes, as addresses change
    private suspend fun ensureScan() { if (System.currentTimeMillis() - scanned > 5 * 60_000) scan() }

    // ---------- State and control ----------

    // On, and brightness 1–100 (100 for what doesn't dim); null when neither way answers
    suspend fun state(entity: String): Pair<Boolean, Int>? {
        ensureScan()
        lan[device(entity)]?.let { l ->
            ask(l.ip)?.let { d -> return (d.optInt("onOff") == 1) to d.optInt("brightness", 100) }
        }
        if (key.isEmpty()) return null
        val caps = request("POST", "/device/state", payload(entity)).getJSONObject("payload").getJSONArray("capabilities")
        var on: Boolean? = null
        var level = 100
        for (i in 0 until caps.length()) {
            val c = caps.getJSONObject(i)
            when (c.optString("instance")) {
                "powerSwitch" -> on = c.getJSONObject("state").optInt("value") == 1
                "brightness" -> c.getJSONObject("state").optInt("value", 0).takeIf { it > 0 }?.let { level = it }
            }
        }
        return on?.let { it to level }
    }

    // Level 0 turns it off; above 0 turns it on at that brightness
    suspend fun set(entity: String, level: Int, dims: Boolean) {
        lan[device(entity)]?.let { l ->
            if (level == 0) tell(l.ip, "turn", JSONObject().put("value", 0))
            else {
                tell(l.ip, "turn", JSONObject().put("value", 1))
                if (dims) tell(l.ip, "brightness", JSONObject().put("value", level.coerceIn(1, 100)))
            }
            return
        }
        if (key.isEmpty()) return
        fun cap(type: String, instance: String, value: Int) =
            payload(entity).apply { getJSONObject("payload").put("capability", JSONObject().put("type", type).put("instance", instance).put("value", value)) }
        if (level == 0) request("POST", "/device/control", cap("devices.capabilities.on_off", "powerSwitch", 0))
        else {
            request("POST", "/device/control", cap("devices.capabilities.on_off", "powerSwitch", 1))
            if (dims) request("POST", "/device/control", cap("devices.capabilities.range", "brightness", level.coerceIn(1, 100)))
        }
    }

    // ---------- Wire ----------

    private fun payload(entity: String) = JSONObject().put("requestId", UUID.randomUUID().toString())
        .put("payload", JSONObject().put("sku", sku(entity)).put("device", device(entity)))

    private suspend fun request(method: String, path: String, body: JSONObject?): JSONObject = withContext(Dispatchers.IO) {
        val c = (URL(CLOUD + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 5000
            readTimeout = 8000
            setRequestProperty("Govee-API-Key", key)
            setRequestProperty("Content-Type", "application/json")
            if (body != null) { doOutput = true; outputStream.use { it.write(body.toString().toByteArray()) } }
        }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        when (code) {
            401 -> error("The key wasn't accepted")
            429 -> error("Govee asked to slow down")
            !in 200..299 -> error("Govee answered $code")
        }
        JSONObject(text)
    }

    // One exchange on the LAN at a time: every reply comes back to the same port
    private val lanLock = Mutex()

    private fun replySocket() = DatagramSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(REPLY_PORT)) }

    private fun receive(s: DatagramSocket, timeout: Int): Pair<String, JSONObject>? {
        val buf = ByteArray(2048)
        val p = DatagramPacket(buf, buf.size)
        s.soTimeout = timeout
        return try {
            s.receive(p)
            p.address.hostAddress.orEmpty() to JSONObject(String(p.data, 0, p.length)).getJSONObject("msg")
        } catch (_: SocketTimeoutException) { null }
    }

    private suspend fun tell(ip: String, cmd: String, data: JSONObject) = withContext(Dispatchers.IO) {
        val b = JSONObject().put("msg", JSONObject().put("cmd", cmd).put("data", data)).toString().toByteArray()
        DatagramSocket().use { it.send(DatagramPacket(b, b.size, InetAddress.getByName(ip), CONTROL_PORT)) }
    }

    // devStatus, answered to port 4002
    private suspend fun ask(ip: String): JSONObject? = lanLock.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                replySocket().use { s ->
                    tell(ip, "devStatus", JSONObject())
                    val end = System.currentTimeMillis() + 1200
                    while (System.currentTimeMillis() < end) {
                        val (from, msg) = receive(s, (end - System.currentTimeMillis()).toInt().coerceAtLeast(1)) ?: break
                        if (from == ip && msg.optString("cmd") == "devStatus") return@runCatching msg.getJSONObject("data")
                    }
                    null
                }
            }.getOrNull()
        }
    }
}

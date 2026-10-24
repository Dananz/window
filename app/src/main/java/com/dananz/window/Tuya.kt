package com.dananz.window

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.roundToInt

// Tuya devices from the Smart Life and Tuya Smart apps: lights, plugs, switches and curtains.
// Signing in is Tuya's own device-sharing login, the one Home Assistant's Tuya integration uses: the
// User Code from the app (Me › Settings › Account and Security), then the app scans a QR on the TV. No
// developer account. Window goes through Home Assistant's public registration with Tuya, as Tuya's
// open SDK has it (github.com/tuya/tuya-device-sharing-sdk); requests and answers are AES-GCM sealed.
//
// A Tuya tile's entity is "tuya/<device id>".
object Tuya {
    private const val CLIENT = "HA_3y9q4ak7g4ephrvke"
    private const val SCHEMA = "haauthorize"
    private const val LOGIN = "https://apigw.iotbing.com/v1.0/m/life/home-assistant/qrcode/tokens"

    private lateinit var sp: SharedPreferences

    enum class Phase { Idle, Waiting, Qr }

    var phase by mutableStateOf(Phase.Idle)
        private set
    var qr by mutableStateOf<ImageBitmap?>(null)
        private set
    var linked by mutableStateOf(false)
        private set
    // Signed in as, for the page to say
    var account by mutableStateOf("")
        private set

    private var code = ""
    private var endpoint = ""
    private var terminal = ""
    private var access = ""
    private var refresh = ""
    private var expires = 0L

    data class Device(val entity: String, val name: String, val kind: Kind)

    fun load(context: Context) {
        if (::sp.isInitialized) return
        sp = context.getSharedPreferences("tuya", Context.MODE_PRIVATE)
        code = sp.getString("code", "").orEmpty()
        endpoint = sp.getString("endpoint", "").orEmpty()
        terminal = sp.getString("terminal", "").orEmpty()
        access = sp.getString("access", "").orEmpty()
        refresh = sp.getString("refresh", "").orEmpty()
        expires = sp.getLong("expires", 0)
        account = sp.getString("account", "").orEmpty()
        linked = refresh.isNotEmpty() && endpoint.isNotEmpty()
    }

    private fun save() {
        sp.edit().putString("code", code).putString("endpoint", endpoint).putString("terminal", terminal)
            .putString("access", access).putString("refresh", refresh).putLong("expires", expires).putString("account", account).apply()
        linked = refresh.isNotEmpty() && endpoint.isNotEmpty()
    }

    fun isTuya(entity: String) = entity.startsWith("tuya/")
    private fun device(entity: String) = entity.substringAfter('/')
    const val LABEL = "Tuya"

    // ---------- Signing in ----------

    private var waiting: Job? = null

    // Null when the QR is up; else what went wrong
    suspend fun start(scope: CoroutineScope, userCode: String): String? {
        cancel()
        phase = Phase.Waiting
        val c = userCode.trim()
        val r = runCatching { plain("POST", "$LOGIN?clientid=$CLIENT&usercode=${enc(c)}&schema=$SCHEMA") }.getOrElse { phase = Phase.Idle; return it.message }
        if (!r.optBoolean("success")) { phase = Phase.Idle; return r.optString("msg").ifBlank { "Tuya said no" } }
        val token = r.getJSONObject("result").getString("qrcode")
        qr = qrImage("tuyaSmart--qrLogin?token=$token")
        phase = Phase.Qr
        // The app confirms on the phone; the TV asks every two seconds, for up to five minutes
        waiting = scope.launch {
            repeat(150) {
                delay(2000)
                val o = runCatching { plain("GET", "$LOGIN/$token?clientid=$CLIENT&usercode=${enc(c)}") }.getOrNull() ?: return@repeat
                if (o.optBoolean("success")) {
                    val x = o.getJSONObject("result")
                    code = c
                    endpoint = x.getString("endpoint")
                    terminal = x.optString("terminal_id")
                    access = x.getString("access_token")
                    refresh = x.getString("refresh_token")
                    expires = o.optLong("t", System.currentTimeMillis()) + x.optLong("expire_time", 7200) * 1000
                    account = x.optString("username")
                    save()
                    phase = Phase.Idle; qr = null
                    return@launch
                }
            }
            phase = Phase.Idle; qr = null
        }
        return null
    }

    fun cancel() { waiting?.cancel(); waiting = null; phase = Phase.Idle; qr = null }

    suspend fun signOut() {
        if (linked) runCatching { request("POST", "/v1.0/m/token/terminal/expire", body = JSONObject().put("accessToken", access).put("terminalId", terminal)) }
        code = ""; endpoint = ""; terminal = ""; access = ""; refresh = ""; expires = 0; account = ""
        dims.clear()
        save()
    }

    // ---------- Devices ----------

    // What each status code means for a tile: the light, plug or curtain part of a device
    private fun kindOf(codes: Set<String>): Kind? = when {
        "switch_led" in codes -> Kind.Light
        "control" in codes -> Kind.Cover
        "switch_1" in codes || "switch" in codes -> Kind.Switch
        else -> null
    }

    private fun status(d: JSONObject): Map<String, Any?> {
        val a = d.optJSONArray("status") ?: JSONArray()
        return (0 until a.length()).associate { a.getJSONObject(it).let { o -> o.optString("code") to o.opt("value") } }
    }

    // Every device in every home of the account
    suspend fun devices(): List<Device> {
        if (!linked) return emptyList()
        val homes = request("GET", "/v1.0/m/life/users/homes") as? JSONArray ?: return emptyList()
        return (0 until homes.length()).flatMap { h ->
            val list = request("GET", "/v1.0/m/life/ha/home/devices", JSONObject().put("homeId", homes.getJSONObject(h).get("ownerId").toString())) as? JSONArray
                ?: JSONArray()
            (0 until list.length()).mapNotNull { i ->
                val d = list.getJSONObject(i)
                val kind = kindOf(status(d).keys) ?: return@mapNotNull null
                Device("tuya/" + d.getString("id"), d.optString("name").ifBlank { "Tuya" }, kind)
            }
        }.distinctBy { it.entity }
    }

    suspend fun check(): String? = runCatching { request("GET", "/v1.0/m/life/users/homes"); null }.getOrElse { it.message ?: "" }

    // ---------- State and control ----------

    // A light's brightness code and range, as its specification says (asked once per device)
    private data class Dim(val code: String, val min: Int, val max: Int)
    private val dims = mutableMapOf<String, Dim?>()

    private suspend fun dim(entity: String): Dim? = dims.getOrPut(entity) {
        val fs = runCatching { (request("GET", "/v1.1/m/life/${device(entity)}/specifications") as JSONObject).getJSONArray("functions") }.getOrNull()
            ?: return@getOrPut null
        val all = (0 until fs.length()).map { fs.getJSONObject(it) }
        val f = all.firstOrNull { it.optString("code") == "bright_value_v2" } ?: all.firstOrNull { it.optString("code") == "bright_value" } ?: return@getOrPut null
        val v = runCatching { JSONObject(f.optString("values")) }.getOrNull()
        Dim(f.getString("code"), v?.optInt("min", 10) ?: 10, v?.optInt("max", 1000) ?: 1000)
    }

    private suspend fun read(entity: String): Map<String, Any?>? {
        val a = request("GET", "/v1.0/m/life/ha/devices/detail", JSONObject().put("devIds", device(entity))) as? JSONArray ?: return null
        return if (a.length() == 0) null else status(a.getJSONObject(0))
    }

    // On, and level 1–100 (100 for what doesn't dim); a curtain is on when open
    suspend fun state(entity: String): Pair<Boolean, Int>? {
        val s = read(entity) ?: return null
        return when (kindOf(s.keys)) {
            Kind.Light -> {
                val d = dim(entity)
                val level = (s[d?.code] as? Number)?.let { v -> ((v.toInt() - d!!.min) * 99f / (d.max - d.min) + 1).roundToInt().coerceIn(1, 100) } ?: 100
                (s["switch_led"] == true) to level
            }
            Kind.Cover -> ((s["percent_state"] as? Number)?.toInt()?.let { it > 0 } ?: (s["control"] == "open")) to 100
            Kind.Switch -> ((s["switch_1"] ?: s["switch"]) == true) to 100
            else -> null
        }
    }

    // Level 0 turns it off (closes a curtain); above 0 turns it on at that brightness
    suspend fun set(entity: String, level: Int, kind: Kind) {
        val cmds = JSONArray()
        fun cmd(code: String, value: Any) = cmds.put(JSONObject().put("code", code).put("value", value))
        when (kind) {
            Kind.Light -> {
                cmd("switch_led", level > 0)
                val d = dim(entity)
                if (level > 0 && d != null) cmd(d.code, (d.min + (d.max - d.min) * (level.coerceIn(1, 100) - 1) / 99f).roundToInt())
            }
            Kind.Cover -> cmd("control", if (level > 0) "open" else "close")
            else -> cmd(if (read(entity)?.containsKey("switch_1") == true) "switch_1" else "switch", level > 0)
        }
        request("POST", "/v1.1/m/thing/${device(entity)}/commands", body = JSONObject().put("commands", cmds))
    }

    // ---------- Wire ----------

    private val tokenLock = Mutex()

    private suspend fun fresh() = tokenLock.withLock {
        if (expires - 60_000 > System.currentTimeMillis()) return@withLock
        val (t, r) = send("GET", "/v1.0/m/token/$refresh", null, null)
        val x = r as JSONObject
        access = x.getString("accessToken")
        refresh = x.getString("refreshToken")
        expires = t + x.optLong("expireTime", 7200) * 1000
        save()
    }

    // The result, opened: an object or a list
    private suspend fun request(method: String, path: String, params: JSONObject? = null, body: JSONObject? = null): Any? {
        if (!linked) error("Not signed in")
        fresh()
        return send(method, path, params, body).second
    }

    private suspend fun send(method: String, path: String, params: JSONObject?, body: JSONObject?): Pair<Long, Any?> = withContext(Dispatchers.IO) {
        val rid = UUID.randomUUID().toString()
        val hashKey = hex(MessageDigest.getInstance("MD5").digest((rid + refresh).toByteArray()))
        val secret = hex(hmac(rid.toByteArray(), hashKey.toByteArray())).take(16)
        val q = params?.let { seal(it.toString(), secret) }
        val b = body?.let { seal(it.toString(), secret) }
        val t = System.currentTimeMillis().toString()
        val headers = listOf("X-appKey" to CLIENT, "X-requestId" to rid, "X-sid" to "", "X-time" to t, "X-token" to access)
        val sign = hex(hmac(hashKey.toByteArray(), (headers.filter { it.second.isNotEmpty() }.joinToString("||") { "${it.first}=${it.second}" } + (q ?: "") + (b ?: "")).toByteArray()))
        val c = (URL(endpoint + path + (q?.let { "?encdata=" + enc(it) } ?: "")).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 5000
            readTimeout = 10000
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            setRequestProperty("X-sign", sign)
            setRequestProperty("Content-Type", "application/json")
            if (b != null) { doOutput = true; outputStream.use { it.write(JSONObject().put("encdata", b).toString().toByteArray()) } }
        }
        val code = c.responseCode
        if (code !in 200..299) error("Tuya answered $code")
        val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        if (!o.optBoolean("success")) error(o.optString("msg").ifBlank { "Tuya said no" })
        val r = o.optString("result").takeIf { it.isNotEmpty() }?.let { open(it, secret) }?.let { runCatching { JSONTokener(it).nextValue() }.getOrDefault(it) }
        o.optLong("t", System.currentTimeMillis()) to r
    }

    // The login calls are plain JSON, before there is a token
    private suspend fun plain(method: String, url: String): JSONObject = withContext(Dispatchers.IO) {
        val c = (URL(url).openConnection() as HttpURLConnection).apply { requestMethod = method; connectTimeout = 5000; readTimeout = 10000 }
        JSONObject(((if (c.responseCode < 400) c.inputStream else c.errorStream) ?: error("Tuya answered ${c.responseCode}")).bufferedReader().use { it.readText() })
    }

    // AES-GCM with a 12-letter nonce: base64(nonce) + base64(sealed text and tag)
    private val rng = SecureRandom()
    private fun seal(text: String, secret: String): String {
        val letters = "ABCDEFGHJKMNPQRSTWXYZabcdefhijkmnprstwxyz2345678"
        val nonce = (1..12).map { letters[rng.nextInt(letters.length)] }.joinToString("").toByteArray()
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(secret.toByteArray(), "AES"), GCMParameterSpec(128, nonce)) }
        val b64 = Base64.getEncoder()
        return b64.encodeToString(nonce) + b64.encodeToString(c.doFinal(text.toByteArray()))
    }

    private fun open(data: String, secret: String): String {
        val raw = Base64.getDecoder().decode(data)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(secret.toByteArray(), "AES"), GCMParameterSpec(128, raw, 0, 12)) }
        return String(c.doFinal(raw, 12, raw.size - 12))
    }

    private fun hmac(key: ByteArray, msg: ByteArray) = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(msg) }
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}

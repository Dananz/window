package com.dananz.window

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Base64
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.ServerSocket
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

// Window's sign-in to Home Assistant, as Home Assistant's own apps do it (OAuth with PKCE). Window
// names itself by a small page it serves on the home network (`http://<tv>:8899/`, a local address
// Home Assistant accepts as an app's id), the phone signs in on Home Assistant's own page from a
// QR code, and Home Assistant sends it back to that page with a code the TV trades for its tokens.
// The refresh token keeps Window signed in; it lapses only after 90 days unused.
// A build with `ha.token` in local.properties uses that long-lived token until someone signs in.
object Ha {
    private lateinit var sp: SharedPreferences

    var linked by mutableStateOf(false)
        private set
    // Home Assistant's name for the home, and its version, read once connected
    var home by mutableStateOf("")
        private set
    var version by mutableStateOf("")
        private set

    fun load(context: Context) {
        if (::sp.isInitialized) return
        sp = context.getSharedPreferences("ha", Context.MODE_PRIVATE)
        linked = url.isNotEmpty() && (sp.getString("refresh", null) != null || buildToken.isNotEmpty())
    }

    // A token built in counts until someone signs out (or signs in another way)
    private val buildToken get() = if (::sp.isInitialized && sp.getBoolean("build_token_off", false)) "" else BuildConfig.HA_TOKEN
    val url: String get() = (if (::sp.isInitialized) sp.getString("url", null) else null) ?: BuildConfig.HA_URL
    val server: String get() = url.removePrefix("http://").removePrefix("https://")

    // A token for the next call: the access token while it lasts, a fresh one from the refresh token
    // after that, or the built-in long-lived one. Called off the main thread
    @Synchronized
    fun token(): String {
        val refresh = sp.getString("refresh", null) ?: return buildToken
        val access = sp.getString("access", null)
        if (access != null && System.currentTimeMillis() < sp.getLong("expires", 0) - 60_000) return access
        val r = runCatching { post("/auth/token", mapOf("grant_type" to "refresh_token", "refresh_token" to refresh, "client_id" to sp.getString("client_id", "")!!)) }
            .onFailure { Log.w("Window", "refresh", it) }.getOrNull() ?: return access.orEmpty()
        if (r.has("access_token")) save(r) else if (r.optString("error") == "invalid_grant") forget()
        return sp.getString("access", "")!!
    }

    private fun save(r: JSONObject) {
        sp.edit()
            .putString("access", r.getString("access_token"))
            .putLong("expires", System.currentTimeMillis() + r.optLong("expires_in", 1800) * 1000)
            .apply { r.optString("refresh_token").takeIf { it.isNotEmpty() }?.let { putString("refresh", it) } }
            .apply()
    }

    private fun forget() {
        sp.edit().remove("access").remove("refresh").remove("expires").putBoolean("build_token_off", true).apply()
        linked = false
    }

    // Signed in from the phone: keep the server, the app id the tokens belong to, and the tokens
    fun signedIn(server: String, clientId: String, tokens: JSONObject) {
        sp.edit().putString("url", server).putString("client_id", clientId).putBoolean("build_token_off", true).apply()
        save(tokens)
        linked = true
    }

    // Forgets the tokens here and tells Home Assistant to drop them too
    suspend fun signOut() = withContext(Dispatchers.IO) {
        sp.getString("refresh", null)?.let { t -> runCatching { post("/auth/revoke", mapOf("token" to t)) } }
        forget()
        home = ""; version = ""
    }

    // Who is on the other end: the home's name and Home Assistant's version
    suspend fun check() = withContext(Dispatchers.IO) {
        if (!linked) return@withContext
        runCatching { HomeAssistant.get("/api/config") }.onSuccess {
            home = it.optString("location_name")
            version = it.optString("version")
        }
    }

    fun post(path: String, form: Map<String, String>, base: String = url): JSONObject {
        val c = URL(base + path).openConnection() as HttpURLConnection
        c.connectTimeout = 5000; c.readTimeout = 8000
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        c.outputStream.use { it.write(form.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }.toByteArray()) }
        val body = (if (c.responseCode < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        return runCatching { JSONObject(body) }.getOrDefault(JSONObject())
    }
}

// A Home Assistant found on the network
data class Found(val name: String, val url: String)

// Signing in from the TV: look for Home Assistant on the network, show the QR, wait for the phone
class SignIn(private val context: Context, private val scope: CoroutineScope) {
    enum class Phase { Idle, Search, Found, Qr, Failed }
    var phase by mutableStateOf(Phase.Idle)
        private set
    var found by mutableStateOf(emptyList<Found>())
        private set
    var qr by mutableStateOf<ImageBitmap?>(null)
        private set
    var done by mutableStateOf(0)
        private set

    private var job: Job? = null
    private var server: ServerSocket? = null
    private var listener: NsdManager.DiscoveryListener? = null
    private val nsd get() = context.getSystemService(NsdManager::class.java)

    fun search() {
        cancel()
        phase = Phase.Search
        found = emptyList()
        val l = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(t: String?, e: Int) { phase = Phase.Found }
            override fun onStopDiscoveryFailed(t: String?, e: Int) = Unit
            override fun onDiscoveryStarted(t: String?) = Unit
            override fun onDiscoveryStopped(t: String?) = Unit
            override fun onServiceLost(s: NsdServiceInfo?) = Unit
            @Suppress("DEPRECATION")
            override fun onServiceFound(s: NsdServiceInfo) {
                nsd.resolveService(s, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(s: NsdServiceInfo?, e: Int) = Unit
                    override fun onServiceResolved(s: NsdServiceInfo) {
                        val a = s.attributes
                        fun attr(k: String) = a[k]?.let { String(it) }?.takeIf { it.isNotBlank() }
                        val host = s.host?.hostAddress ?: return
                        val url = attr("internal_url") ?: attr("base_url") ?: "http://$host:${s.port}"
                        val f = Found(attr("location_name") ?: s.serviceName, url.trimEnd('/'))
                        scope.launch(Dispatchers.Main) { if (found.none { it.url == f.url }) { found = found + f; phase = Phase.Found } }
                    }
                })
            }
        }
        listener = l
        runCatching { nsd.discoverServices("_home-assistant._tcp", NsdManager.PROTOCOL_DNS_SD, l) }
        // Nothing within a few seconds: say so, and offer to type the address
        job = scope.launch { kotlinx.coroutines.delay(5000); if (phase == Phase.Search) phase = Phase.Found }
    }

    // The QR for one Home Assistant, and the page the phone comes back to
    fun start(base: String) {
        stopSearch()
        val ip = tvAddress() ?: run { phase = Phase.Failed; return }
        val clientId = "http://$ip:$PORT/"
        val redirect = "http://$ip:$PORT/callback"
        val verifier = random(48)
        val state = random(16)
        val challenge = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        val auth = "$base/auth/authorize?response_type=code&client_id=${enc(clientId)}&redirect_uri=${enc(redirect)}&state=$state&code_challenge=$challenge&code_challenge_method=S256"
        qr = qrImage(auth)
        phase = Phase.Qr
        job = scope.launch(Dispatchers.IO) {
            runCatching {
                ServerSocket(PORT).use { s ->
                    server = s
                    while (true) {
                        val sock = s.accept()
                        val line = sock.getInputStream().bufferedReader().readLine().orEmpty()
                        val path = line.split(" ").getOrNull(1).orEmpty()
                        val q = path.substringAfter('?', "").split("&").mapNotNull { p -> p.split("=", limit = 2).takeIf { it.size == 2 }?.let { it[0] to URLDecoder.decode(it[1], "UTF-8") } }.toMap()
                        val ok = path.startsWith("/callback") && q["state"] == state && q["code"] != null
                        val tokens = if (ok) runCatching { Ha.post("/auth/token", mapOf("grant_type" to "authorization_code", "code" to q["code"]!!, "client_id" to clientId, "code_verifier" to verifier), base) }.getOrNull() else null
                        val signed = tokens?.has("access_token") == true
                        val page = if (path.startsWith("/callback")) (if (signed) "Window is connected. Look at your TV." else "That didn't work. Try again on the TV.") else "Window"
                        sock.getOutputStream().use { o ->
                            val html = "<!doctype html><meta name=viewport content='width=device-width'><link rel=redirect_uri href='$redirect'><body style='font:20px system-ui;background:#0b0b0d;color:#f4f0ea;display:grid;place-items:center;height:90vh;text-align:center'><p>$page</p>"
                            val b = html.toByteArray()
                            o.write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            o.write(b)
                        }
                        sock.close()
                        if (signed) {
                            withContext(Dispatchers.Main) {
                                Ha.signedIn(base, clientId, tokens!!)
                                phase = Phase.Idle
                                done++
                            }
                            scope.launch { Ha.check() }
                            break
                        }
                    }
                }
            }.onFailure { if (phase == Phase.Qr) withContext(Dispatchers.Main) { phase = Phase.Failed } }
            server = null
        }
    }

    fun cancel() {
        stopSearch()
        job?.cancel(); job = null
        runCatching { server?.close() }; server = null
        phase = Phase.Idle
    }

    private fun stopSearch() {
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
        job?.cancel()
    }

    private fun tvAddress(): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        return cm.getLinkProperties(cm.activeNetwork)?.linkAddresses?.map { it.address }?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
    }

    private fun random(n: Int): String {
        val a = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
        val r = SecureRandom()
        return (1..n).map { a[r.nextInt(a.length)] }.joinToString("")
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    companion object { const val PORT = 8899 }
}

// A QR code as an image, one pixel a module: drawn scaled up without smoothing
fun qrImage(text: String): ImageBitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0))
    val b = Bitmap.createBitmap(m.width, m.height, Bitmap.Config.ARGB_8888)
    for (y in 0 until m.height) for (x in 0 until m.width) b.setPixel(x, y, if (m[x, y]) 0xFF0B0B0D.toInt() else -1)
    return b.asImageBitmap()
}

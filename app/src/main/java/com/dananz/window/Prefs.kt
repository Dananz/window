package com.dananz.window

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

// What Window settings set, kept in the "home" preferences and read as Compose state, so the home
// screen and the screensaver follow a change at once
object Prefs {
    private lateinit var sp: SharedPreferences

    // The app opened over Window when the TV turns on; null: none
    var startApp by mutableStateOf<String?>(null)
        private set
    var clock24 by mutableStateOf(true)
        private set
    // The screensaver: 0 time and weather, 1 time only, 2 the landscape only
    var saverShows by mutableIntStateOf(0)
        private set
    // Minutes between the clock's moves on the screensaver; 0 never
    var clockMoves by mutableIntStateOf(2)
        private set
    // Reduced: the camera stays still and the room doesn't drift
    var reduced by mutableStateOf(false)
        private set
    var drift by mutableStateOf(true)
        private set
    var tiles by mutableStateOf(emptyList<Tile>())
        private set
    // The view changes by itself every this many minutes, home screen and screensaver alike; 0: it stays.
    // shuffleEvery keeps the last interval, for the dropdown's Shuffle to turn back on with
    var shuffle by mutableIntStateOf(0)
        private set
    var shuffleEvery by mutableIntStateOf(30)
        private set

    fun load(context: Context) {
        if (::sp.isInitialized) return
        sp = context.getSharedPreferences("home", Context.MODE_PRIVATE)
        startApp = sp.getString("start_app", null)
        clock24 = sp.getBoolean("clock24", true)
        saverShows = sp.getInt("saver_shows", 0)
        clockMoves = sp.getInt("clock_moves", 2)
        reduced = sp.getBoolean("reduced", false)
        drift = sp.getBoolean("drift", true)
        tiles = sp.getString("tiles", null)?.let(::readTiles) ?: DEFAULT_TILES
        shuffle = sp.getInt("shuffle", 0)
        shuffleEvery = sp.getInt("shuffle_every", 30)
        Lang.load(context)
    }

    fun changeStartApp(v: String?) { startApp = v; sp.edit().putString("start_app", v).apply() }
    fun changeClock24(v: Boolean) { clock24 = v; sp.edit().putBoolean("clock24", v).apply() }
    fun changeSaverShows(v: Int) { saverShows = v; sp.edit().putInt("saver_shows", v).apply() }
    fun changeClockMoves(v: Int) { clockMoves = v; sp.edit().putInt("clock_moves", v).apply() }
    fun changeReduced(v: Boolean) { reduced = v; sp.edit().putBoolean("reduced", v).apply() }
    fun changeDrift(v: Boolean) { drift = v; sp.edit().putBoolean("drift", v).apply() }
    fun changeShuffle(minutes: Int) {
        shuffle = minutes
        if (minutes > 0) shuffleEvery = minutes
        sp.edit().putInt("shuffle", minutes).putInt("shuffle_every", shuffleEvery).apply()
    }
    fun changeTiles(v: List<Tile>) {
        tiles = v
        sp.edit().putString("tiles", JSONArray(v.map { JSONObject().put("entity", it.entity).put("name", it.name).put("kind", it.kind.name) }).toString()).apply()
    }

    private fun readTiles(s: String): List<Tile> = runCatching {
        val a = JSONArray(s)
        (0 until a.length()).map { a.getJSONObject(it).let { o -> Tile(o.getString("entity"), o.getString("name"), Kind.valueOf(o.getString("kind"))) } }
    }.getOrDefault(emptyList())

    // A build made with Tomer's Home Assistant token starts with his living room; any other starts
    // empty, and the wall offers to add tiles
    private val DEFAULT_TILES: List<Tile>
        get() = if (BuildConfig.HA_TOKEN.isEmpty()) emptyList() else listOf(
            Tile("light.living_room_ceiling", "Ceiling", Kind.Light),
            Tile("light.kitchen_led", "Kitchen", Kind.Light),
            Tile("light.living_room_miznon_rgb", "Miznon", Kind.Light),
            Tile("climate.living_room_ac", "AC", Kind.Climate),
        )
}

// The TV's own screensaver settings, which Window changes with WRITE_SECURE_SETTINGS and
// WRITE_SETTINGS (both granted over adb by the install script)
object Saver {
    private const val GOOGLE = "com.google.android.apps.tv.dreamx/.service.Backdrop"
    private fun me(context: Context) = ComponentName(context, Screensaver::class.java).flattenToString()

    // The setting can hold the short form (com.dananz.window/.Screensaver, from the scripts) or the long one
    fun isWindow(context: Context): Boolean {
        val mine = ComponentName(context, Screensaver::class.java)
        return Settings.Secure.getString(context.contentResolver, "screensaver_components").orEmpty()
            .split(',').any { ComponentName.unflattenFromString(it.trim()) == mine }
    }

    // On by default: the first time Window can write the TV's settings, its screensaver becomes the
    // TV's. Once only, so switching it off in Window settings stays off
    fun byDefault(context: Context) {
        val sp = context.getSharedPreferences("home", Context.MODE_PRIVATE)
        if (sp.getBoolean("saver_default", false)) return
        if (context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return
        setWindow(context, true)
        sp.edit().putBoolean("saver_default", true).apply()
    }

    fun setWindow(context: Context, on: Boolean) = runCatching {
        if (context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return@runCatching
        Settings.Secure.putString(context.contentResolver, "screensaver_components", if (on) me(context) else GOOGLE)
        Settings.Secure.putInt(context.contentResolver, "screensaver_enabled", 1)
    }

    // When the screensaver starts: the TV dreams when the screen would otherwise go off
    fun afterMinutes(context: Context): Int =
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, 600_000) / 60_000

    fun setAfterMinutes(context: Context, minutes: Int) = runCatching {
        if (Settings.System.canWrite(context)) Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, minutes * 60_000)
    }
}

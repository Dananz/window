package com.dananz.window

import kotlinx.coroutines.delay
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.itemsIndexed
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Bold
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.bold.ArrowsDownUp
import com.adamglin.phosphoricons.bold.CaretLeft
import com.adamglin.phosphoricons.bold.CaretRight
import com.adamglin.phosphoricons.bold.Check
import com.adamglin.phosphoricons.bold.PencilSimple
import com.adamglin.phosphoricons.bold.Plus
import com.adamglin.phosphoricons.bold.X
import com.adamglin.phosphoricons.fill.Fan as FanFill
import com.adamglin.phosphoricons.fill.Globe
import com.adamglin.phosphoricons.fill.House
import com.adamglin.phosphoricons.fill.Info
import com.adamglin.phosphoricons.fill.Key
import com.adamglin.phosphoricons.fill.Keyboard
import com.adamglin.phosphoricons.fill.Trash
import com.adamglin.phosphoricons.fill.Lightbulb
import com.adamglin.phosphoricons.fill.MoonStars
import com.adamglin.phosphoricons.fill.Power
import com.adamglin.phosphoricons.fill.SignOut
import com.adamglin.phosphoricons.fill.Sparkle
import com.adamglin.phosphoricons.fill.SquareSplitHorizontal
import com.adamglin.phosphoricons.fill.Television
import com.adamglin.phosphoricons.fill.Thermometer
import com.adamglin.phosphoricons.fill.Translate
import com.adamglin.phosphoricons.fill.WifiHigh
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// Window settings, in the quick panel's own sheet (prototype settings/B, built into control-center/A):
// OK on Window settings turns the sheet into its sections, OK on one slides its page in, Back slides
// it out. Smart home goes a page deeper, to the tiles on the wall and the Home Assistant sign-in.
// Left and right change a value in place, OK flips a switch or runs an action.

enum class Arrow { Left, Right, Up, Down }

private enum class RowKind { Choice, Toggle, Action, Info, Link }
private class Row(val id: String, val label: String, val kind: RowKind)
private class Section(val id: String, val label: String, val icon: ImageVector, val rows: List<Row>)

private val SECTIONS = listOf(
    Section("home", "Home screen", PhosphorIcons.Fill.House, listOf(
        Row("start", "Open on start", RowKind.Choice), Row("view", "View", RowKind.Choice),
        Row("clock", "Clock", RowKind.Choice), Row("arrange", "Arrange apps", RowKind.Action),
    )),
    Section("lang", "Language", PhosphorIcons.Fill.Translate, listOf(Row("lang", "Language", RowKind.Choice))),
    Section("saver", "Screensaver", PhosphorIcons.Fill.MoonStars, listOf(
        Row("saver", "Window screensaver", RowKind.Toggle), Row("after", "Starts after", RowKind.Choice),
        Row("shows", "Shows", RowKind.Choice), Row("moves", "Clock moves", RowKind.Choice), Row("preview", "Preview", RowKind.Action),
    )),
    Section("smart", "Smart home", PhosphorIcons.Fill.Lightbulb, listOf(Row("tiles", "Tiles on the wall", RowKind.Link), Row("ha", "Home Assistant", RowKind.Link), Row("govee", "Govee", RowKind.Link), Row("sensibo", "Sensibo", RowKind.Link), Row("tuya", "Tuya", RowKind.Link))),
    Section("motion", "Motion", PhosphorIcons.Fill.Sparkle, listOf(Row("motion", "Animations", RowKind.Choice), Row("drift", "Room drifts with the apps", RowKind.Toggle))),
    Section("about", "About", PhosphorIcons.Fill.Info, listOf(Row("version", "Window", RowKind.Info), Row("tv", "TV settings", RowKind.Action))),
)

// An entity Home Assistant has, that could be a tile
data class Entity(val entity: String, val name: String, val kind: Kind)
private val TYPES = listOf(null to "Everything", Kind.Light to "Lights", Kind.Climate to "AC", Kind.Switch to "Switches", Kind.Fan to "Fans", Kind.Cover to "Curtains", Kind.Scene to "Scenes")

// What the settings ask of the home screen
interface SettingsHost {
    val place: Place
    fun setPlace(p: Place)
    val shelf: List<TvApp>
    fun openAllApps()
    fun toast(text: String)
}

class SettingsState(private val context: Context, private val scope: CoroutineScope, private val host: SettingsHost) {
    var open by mutableStateOf(false)
        private set
    var page by mutableStateOf<String?>(null)
        private set
    var sub by mutableStateOf<String?>(null)
        private set
    private var lastPage by mutableStateOf("home")
    private var lastSub by mutableStateOf("tiles")
    var focus by mutableStateOf("sec-home")
        private set
    var preview by mutableStateOf(false)
    // A field being typed in (a tile's new name, Home Assistant's address): the keys are the keyboard's
    var editing by mutableStateOf<String?>(null)
        private set
    var draft by mutableStateOf("")

    private var saverOn by mutableStateOf(false)
    private var after by mutableIntStateOf(10)
    var catalog by mutableStateOf(emptyList<Entity>())
        private set
    var hidden by mutableIntStateOf(0)
        private set
    var type by mutableIntStateOf(0)
        private set
    var moving by mutableStateOf<Int?>(null)
        private set
    val signIn = SignIn(context, scope)

    fun show() {
        open = true; page = null; sub = null; focus = "sec-home"
        saverOn = Saver.isWindow(context)
        after = Saver.afterMinutes(context)
        scope.launch { Ha.check() }
    }

    fun close() { signIn.cancel(); open = false; sub = null; page = null; editing = null; moving = null }

    private val section get() = SECTIONS.first { it.id == (page ?: lastPage) }
    private fun rowOf(id: String) = SECTIONS.flatMap { it.rows }.firstOrNull { "row-${it.id}" == id }

    // ---------- Values ----------

    private fun startChoices() = listOf<String?>(null) + host.shelf.filter { it.shortcut == null }.map { it.id }
    private fun appName(pkg: String?) = pkg?.let { p -> host.shelf.firstOrNull { it.id == p }?.name ?: p } ?: t("Off")

    fun value(id: String): String = when (id) {
        "start" -> appName(Prefs.startApp)
        "view" -> t(host.place.label)
        "clock" -> t(if (Prefs.clock24) "24-hour" else "12-hour")
        "lang" -> when (Lang.choice) { "he" -> "עברית"; "en" -> "English"; else -> t("Follow the TV") }
        "saver" -> t(if (saverOn) "On" else "Off")
        "after" -> if (after > 24 * 60) t("Never") else f("{0} min", after)
        "shows" -> t(listOf("Time and weather", "Time only", "Landscape only")[Prefs.saverShows])
        "moves" -> if (Prefs.clockMoves == 0) t("Never") else t("Every ${Prefs.clockMoves} min")
        "motion" -> if (Prefs.reduced) t("Reduced") else (if (Lang.he) "מלאות" else "Full")
        "drift" -> t(if (Prefs.drift) "On" else "Off")
        "tiles" -> if (Prefs.tiles.size == 1) t("1 tile") else f("{0} tiles", Prefs.tiles.size)
        "ha" -> if (Ha.linked) f("Connected · {0}", Ha.server.substringBefore(':')) else t("Not connected")
        "govee" -> when {
            Govee.key.isNotEmpty() -> t("Key added")
            Govee.lan.isNotEmpty() -> f("{0} on the network", Govee.lan.size)
            else -> t("Not set up")
        }
        "sensibo" -> t(if (Sensibo.key.isNotEmpty()) "Key added" else "Not set up")
        "tuya" -> if (Tuya.linked) t("Connected") else t("Not set up")
        "version" -> BuildConfig.VERSION_NAME
        else -> ""
    }

    fun on(id: String) = when (id) { "saver" -> saverOn; "drift" -> Prefs.drift; else -> false }

    private fun step(id: String, d: Int) {
        fun <T> next(list: List<T>, cur: T) = list[((list.indexOf(cur).coerceAtLeast(0) + d) % list.size + list.size) % list.size]
        when (id) {
            "start" -> Prefs.changeStartApp(next(startChoices(), Prefs.startApp))
            "view" -> host.setPlace(next(Place.entries, host.place))
            "clock" -> Prefs.changeClock24(!Prefs.clock24)
            "lang" -> Lang.set(context, next(listOf("system", "en", "he"), Lang.choice))
            "after" -> next(listOf(5, 10, 15, 30), after).let { after = it; Saver.setAfterMinutes(context, it) }
            "shows" -> Prefs.changeSaverShows(next(listOf(0, 1, 2), Prefs.saverShows))
            "moves" -> Prefs.changeClockMoves(next(listOf(2, 5, 10, 0), Prefs.clockMoves))
            "motion" -> Prefs.changeReduced(!Prefs.reduced)
        }
    }

    // ---------- The remote ----------

    fun ok() {
        val f = focus
        when {
            f.startsWith("sec-") -> enter(f.removePrefix("sec-"))
            f.startsWith("row-") -> {
                val r = rowOf(f) ?: return
                when (r.kind) {
                    RowKind.Choice -> step(r.id, 1)
                    RowKind.Toggle -> when (r.id) {
                        "saver" -> { saverOn = !saverOn; Saver.setWindow(context, saverOn) }
                        "drift" -> Prefs.changeDrift(!Prefs.drift)
                    }
                    RowKind.Link -> openSub(r.id)
                    RowKind.Action -> when (r.id) {
                        "preview" -> preview = true
                        "arrange" -> { close(); host.openAllApps() }
                        "tv" -> runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }
                    RowKind.Info -> {}
                }
            }
            sub == "tiles" -> tilesOk(f)
            sub == "ha" -> haOk(f)
            sub == "govee" -> goveeOk(f)
            sub == "sensibo" -> sensiboOk(f)
            sub == "tuya" -> tuyaOk(f)
        }
    }

    fun move(a: Arrow) {
        val f = focus
        if (sub == "tiles") return tilesMove(f, a)
        if (sub == "ha") return haMove(f, a)
        if (sub == "govee") return goveeMove(f, a)
        if (sub == "sensibo") return listMove(sensiboIds(), f, a)
        if (sub == "tuya") return tuyaMove(f, a)
        if (f.startsWith("sec-")) {
            val i = SECTIONS.indexOfFirst { "sec-${it.id}" == f }
            when (a) {
                Arrow.Up, Arrow.Down -> focus = "sec-${SECTIONS[(i + if (a == Arrow.Down) 1 else -1).coerceIn(0, SECTIONS.lastIndex)].id}"
                Arrow.Right -> enter(SECTIONS[i].id)
                Arrow.Left -> {}
            }
            return
        }
        val r = rowOf(f) ?: return
        val rows = section.rows
        when (a) {
            Arrow.Up, Arrow.Down -> focus = "row-${rows[(rows.indexOf(r) + if (a == Arrow.Down) 1 else -1).coerceIn(0, rows.lastIndex)].id}"
            Arrow.Left, Arrow.Right -> when {
                r.kind == RowKind.Choice -> step(r.id, if (a == Arrow.Right) 1 else -1)
                r.kind == RowKind.Link && a == Arrow.Right -> openSub(r.id)
                a == Arrow.Left -> { focus = "sec-${section.id}"; page = null }
            }
        }
    }

    // False: closed altogether
    fun back(): Boolean {
        if (editing != null) { editing = null; return true }
        if (moving != null) { moving = null; return true }
        if (sub == "ha" && signIn.phase != SignIn.Phase.Idle) { signIn.cancel(); focus = haFirst(); return true }
        if (sub != null) { focus = "row-$sub"; sub = null; return true }
        if (page != null) { focus = "sec-$page"; page = null; return true }
        close()
        return false
    }

    // Straight to the tiles, from the wall's Add tiles
    fun showTiles() { page = "smart"; lastPage = "smart"; openSub("tiles") }

    private fun enter(id: String) { page = id; lastPage = id; focus = "row-${SECTIONS.first { it.id == id }.rows.first().id}" }

    private fun openSub(id: String) {
        sub = id; lastSub = id
        when (id) {
            "tiles" -> { focus = if (Prefs.tiles.isEmpty()) "tw-type" else "tw-w-0"; loadCatalog() }
            "govee" -> { focus = "gv-key"; note = null }
            "sensibo" -> { focus = "sb-key"; note = null }
            "tuya" -> { Tuya.cancel(); focus = tuyaIds().first(); note = null }
            else -> focus = haFirst()
        }
    }

    // ---------- Tiles on the wall ----------

    private val list get() = catalog.filter { TYPES[type].first == null || it.kind == TYPES[type].first }
    private fun tileRows(): List<List<String>> =
        Prefs.tiles.indices.map { listOf("tw-w-$it", "tw-r-$it", "tw-x-$it") } + listOf(listOf("tw-type")) + list.indices.map { listOf("tw-a-$it") }

    private fun loadCatalog() = scope.launch {
        val ha = runCatching { HomeAssistant.states() }.map { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.mapNotNull { o ->
                val id = o.getString("entity_id")
                val name = o.optJSONObject("attributes")?.optString("friendly_name").orEmpty().ifBlank { id }
                val kind = when (id.substringBefore('.')) {
                    "light" -> Kind.Light; "climate" -> Kind.Climate; "switch" -> Kind.Switch
                    "fan" -> Kind.Fan; "cover" -> Kind.Cover; "scene" -> Kind.Scene; else -> return@mapNotNull null
                }
                Entity(id, name.trim(), kind)
            }
        }
        val picked = ha.getOrDefault(emptyList())
        fun show(more: List<Entity>) { catalog = (picked.filter { it.useful() } + more).sortedWith(compareBy({ it.kind.ordinal }, { it.name.lowercase() })) }
        show(emptyList())
        hidden = picked.size - picked.count { it.useful() }
        // Govee's own devices, by key or on the network, join Home Assistant's once they answer
        val govee = runCatching { Govee.devices() }.getOrDefault(emptyList()).map { Entity(it.entity, it.name, if (it.dims) Kind.Light else Kind.Switch) }
        show(govee)
        // Then the cloud accounts: Sensibo's ACs and Tuya's lights, plugs and curtains
        val cloud = govee + runCatching { Sensibo.pods() }.getOrDefault(emptyList()).map { Entity(it.entity, it.name, Kind.Climate) } +
            runCatching { Tuya.devices() }.getOrDefault(emptyList()).map { Entity(it.entity, it.name, it.kind) }
        show(cloud)
        if (ha.isFailure && cloud.isEmpty()) host.toast(t("Not connected"))
    }

    // The parts no one switches from a sofa: light segments, each lamp's settings switches, one-setting scenes
    private fun Entity.useful(): Boolean = when (kind) {
        Kind.Light -> !Regex("segment", RegexOption.IGNORE_CASE).containsMatchIn(entity + name)
        Kind.Switch -> !Regex("nightlight|sync|reverse|freeze|gradient|do not disturb|dream view|permit join|climate react|timer|toggle|power switch", RegexOption.IGNORE_CASE).containsMatchIn(name)
        Kind.Scene -> !name.contains(" / ") && !name.startsWith("Govee to MQTT")
        else -> true
    }

    private fun idx(id: String) = id.substringAfterLast('-').toInt()

    private fun tilesOk(f: String) {
        val tiles = Prefs.tiles
        when {
            f.startsWith("tw-w-") -> moving = if (moving == idx(f)) null else idx(f)
            f.startsWith("tw-r-") -> { draft = tiles[idx(f)].name; editing = f }
            f.startsWith("tw-x-") -> {
                val i = idx(f)
                Prefs.changeTiles(tiles.filterIndexed { k, _ -> k != i })
                focus = if (tiles.size > 1) "tw-x-${minOf(i, tiles.size - 2)}" else "tw-type"
            }
            f == "tw-type" -> type = (type + 1) % TYPES.size
            f.startsWith("tw-a-") -> {
                val e = list.getOrNull(idx(f)) ?: return
                if (tiles.any { it.entity == e.entity }) Prefs.changeTiles(tiles.filter { it.entity != e.entity })
                else {
                    val next = tiles + Tile(e.entity, e.name, e.kind)
                    Prefs.changeTiles(next)
                }
            }
        }
    }

    private fun tilesMove(f: String, a: Arrow) {
        val m = moving
        if (m != null) {
            if (a != Arrow.Up && a != Arrow.Down) return
            val j = m + if (a == Arrow.Down) 1 else -1
            val tiles = Prefs.tiles.toMutableList()
            if (j < 0 || j > tiles.lastIndex) return
            tiles[m] = tiles[j].also { tiles[j] = tiles[m] }
            Prefs.changeTiles(tiles)
            moving = j; focus = "tw-w-$j"
            return
        }
        if (f == "tw-type" && (a == Arrow.Left || a == Arrow.Right)) { type = (type + if (a == Arrow.Right) 1 else TYPES.size - 1) % TYPES.size; return }
        val rows = tileRows()
        val r = rows.indexOfFirst { f in it }
        if (r < 0) { focus = rows.first().first(); return }
        val c = rows[r].indexOf(f)
        when (a) {
            Arrow.Left -> if (c == 0) { focus = "row-tiles"; sub = null } else focus = rows[r][c - 1]
            Arrow.Right -> rows[r].getOrNull(c + 1)?.let { focus = it }
            Arrow.Up, Arrow.Down -> rows.getOrNull(r + if (a == Arrow.Down) 1 else -1)?.let { focus = it[minOf(c, it.lastIndex)] }
        }
    }

    fun commitEdit() {
        val e = editing ?: return
        val v = draft.trim()
        if (e.startsWith("tw-r-") && v.isNotEmpty()) Prefs.changeTiles(Prefs.tiles.mapIndexed { k, x -> if (k == idx(e)) x.copy(name = v) else x })
        if (e == "ha-type" && v.isNotEmpty()) signIn.start((if (v.startsWith("http")) v else "http://$v").trimEnd('/')).also { focus = "ha-cancel" }
        if (e == "gv-key" && v.isNotEmpty()) { Govee.changeKey(v); checkGovee() }
        if (e == "sb-key" && v.isNotEmpty()) { Sensibo.changeKey(v); checkCloud("Sensibo") { Sensibo.check() } }
        if (e == "ty-code" && v.isNotEmpty()) scope.launch {
            busy = true; note = t("Checking…")
            val err = Tuya.start(scope, v)
            busy = false
            note = err?.let { f("That didn't work: {0}", t(it)) }
            focus = tuyaIds().first()
        }
        editing = null
    }

    // ---------- Home Assistant ----------

    private fun haIds(): List<String> = when (signIn.phase) {
        SignIn.Phase.Idle -> if (Ha.linked) listOf("ha-server", "ha-switch", "ha-out") else listOf("ha-switch")
        SignIn.Phase.Search, SignIn.Phase.Qr, SignIn.Phase.Failed -> listOf("ha-cancel")
        SignIn.Phase.Found -> signIn.found.indices.map { "ha-found-$it" } + listOf("ha-type", "ha-cancel")
    }
    private fun haFirst() = haIds().first()

    private fun haOk(f: String) {
        when {
            f == "ha-switch" -> { signIn.search(); focus = "ha-cancel" }
            f == "ha-out" -> scope.launch { Ha.signOut(); focus = "ha-switch" }
            f.startsWith("ha-found-") -> signIn.found.getOrNull(idx(f))?.let { signIn.start(it.url); focus = "ha-cancel" }
            f == "ha-type" -> { draft = "http://"; editing = "ha-type" }
            f == "ha-cancel" -> { signIn.cancel(); focus = haFirst() }
        }
    }

    private fun haMove(f: String, a: Arrow) {
        val ids = haIds()
        when (a) {
            Arrow.Up, Arrow.Down -> focus = ids[(ids.indexOf(f).coerceAtLeast(0) + if (a == Arrow.Down) 1 else -1).coerceIn(0, ids.lastIndex)]
            Arrow.Left -> if (signIn.phase == SignIn.Phase.Idle) { focus = "row-ha"; sub = null }
            Arrow.Right -> {}
        }
    }

    // ---------- Govee ----------

    // What the Govee page last found out, said under its rows
    var note by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set

    private fun goveeIds() = listOf("gv-key", "gv-scan") + if (Govee.key.isNotEmpty()) listOf("gv-clear") else emptyList()

    private fun checkGovee() = scope.launch {
        busy = true
        note = Govee.check()?.let { f("That didn't work: {0}", t(it)) } ?: t("The key works. Add your Govee lights in Tiles on the wall")
        busy = false
    }

    private fun goveeOk(f: String) {
        when (f) {
            "gv-key" -> { draft = ""; editing = "gv-key" }
            "gv-scan" -> scope.launch {
                busy = true; note = t("Looking on your network…")
                val n = Govee.scan().size
                note = if (n == 0) t("Nothing answered. Turn on LAN Control for each light in the Govee Home app") else f("{0} found on the network. Add them in Tiles on the wall", n)
                busy = false
            }
            "gv-clear" -> { Govee.changeKey(""); note = null; focus = "gv-key" }
        }
    }

    private fun goveeMove(f: String, a: Arrow) {
        val ids = goveeIds()
        when (a) {
            Arrow.Up, Arrow.Down -> focus = ids[(ids.indexOf(f).coerceAtLeast(0) + if (a == Arrow.Down) 1 else -1).coerceIn(0, ids.lastIndex)]
            Arrow.Left -> { focus = "row-govee"; sub = null }
            Arrow.Right -> {}
        }
    }

    // ---------- Sensibo and Tuya ----------

    private fun checkCloud(name: String, check: suspend () -> String?) = scope.launch {
        busy = true; note = t("Checking…")
        note = check()?.let { f("That didn't work: {0}", t(it)) } ?: f("Connected. Add your {0} devices in Tiles on the wall", name)
        busy = false
    }

    private fun listMove(ids: List<String>, f: String, a: Arrow) {
        when (a) {
            Arrow.Up, Arrow.Down -> focus = ids[(ids.indexOf(f).coerceAtLeast(0) + if (a == Arrow.Down) 1 else -1).coerceIn(0, ids.lastIndex)]
            Arrow.Left -> { focus = "row-$sub"; sub = null }
            Arrow.Right -> {}
        }
    }

    private fun sensiboIds() = listOf("sb-key") + if (Sensibo.key.isNotEmpty()) listOf("sb-check", "sb-clear") else emptyList()

    private fun sensiboOk(f: String) {
        when (f) {
            "sb-key" -> { draft = ""; editing = "sb-key" }
            "sb-check" -> checkCloud("Sensibo") { Sensibo.check() }
            "sb-clear" -> { Sensibo.changeKey(""); note = null; focus = "sb-key" }
        }
    }

    private fun tuyaIds() = when {
        Tuya.phase != Tuya.Phase.Idle -> listOf("ty-cancel")
        Tuya.linked -> listOf("ty-check", "ty-out")
        else -> listOf("ty-code")
    }

    private fun tuyaOk(f: String) {
        when (f) {
            "ty-code" -> { draft = ""; editing = "ty-code" }
            "ty-cancel" -> { Tuya.cancel(); note = null; focus = tuyaIds().first() }
            "ty-check" -> checkCloud("Tuya") { Tuya.check() }
            "ty-out" -> scope.launch { Tuya.signOut(); note = null; focus = tuyaIds().first() }
        }
    }

    private fun tuyaMove(f: String, a: Arrow) = listMove(tuyaIds(), f, a)

    // A search that found something, or a sign-in that finished, moves the focus with it
    fun follow() {
        if (sub == "ha" && focus !in haIds()) focus = haFirst()
    }

    @Composable
    fun Sheet() {
        val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
        val shown by animateFloatAsState(if (open) 1f else 0f, settle(Motion.SHEET), label = "settings")
        LaunchedEffect(signIn.phase, signIn.found, signIn.done) { follow() }
        LaunchedEffect(signIn.done) { if (signIn.done > 0) host.toast(t("Connected to Home Assistant")) }
        if (!open && shown == 0f) return
        val sign = if (rtl) -1 else 1
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
            Box(
                Modifier.padding(32.dp).width(600.dp).fillMaxHeight()
                    .graphicsLayer {
                        translationX = sign * (1f - shown) * 660.dp.toPx()
                        alpha = shown
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                    }
                    .clip(RoundedCornerShape(40.dp)).background(Color(0xFF131316)),
            ) {
                // Three layers, each sliding in from the far side and pushed a little aside by the next
                Layer(if (page == null && sub == null) 0f else -1f, sign) { Sections() }
                Layer(if (page != null && sub == null) 0f else if (sub != null) -1f else 1f, sign) { Page() }
                Layer(if (sub != null) 0f else 1f, sign) { Deeper() }
            }
        }
    }

    @Composable
    private fun Layer(pos: Float, sign: Int, content: @Composable ColumnScope.() -> Unit) {
        val p by animateFloatAsState(pos, settle(480), label = "layer")
        if (p >= .999f) return
        Column(
            Modifier.fillMaxSize().graphicsLayer {
                translationX = sign * (if (p > 0) p * size.width else p * size.width * .28f)
                alpha = 1f - kotlin.math.abs(p)
            }.padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) { content() }
    }

    @Composable
    private fun Title(text: String, back: Boolean) {
        val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
        Row(Modifier.padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (back) Icon(if (rtl) PhosphorIcons.Bold.CaretRight else PhosphorIcons.Bold.CaretLeft, null, Modifier.size(26.dp), tint = Ink.text.copy(alpha = .55f))
            Text(text, color = Ink.text, fontFamily = Syne, fontWeight = FontWeight.SemiBold, fontSize = if (back) 34.sp else 38.sp)
        }
    }

    @Composable
    private fun ColumnScope.Sections() {
        val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
        Title(t("Window settings"), false)
        SECTIONS.forEach { s ->
            val first = if (s.id == "home") s.rows[1] else s.rows.first { it.kind != RowKind.Action }
            Row(
                Modifier.fillMaxWidth().height(76.dp).ring(focus == "sec-${s.id}", 22.dp).clip(RoundedCornerShape(22.dp))
                    .background(Color.White.copy(alpha = if (focus == "sec-${s.id}") .12f else .06f)).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(Color.White.copy(alpha = .1f)), contentAlignment = Alignment.Center) {
                    Icon(s.icon, null, Modifier.size(22.dp), tint = Ink.text)
                }
                Column(Modifier.weight(1f)) {
                    Text(t(s.label), color = Ink.text, fontFamily = Golos, fontWeight = FontWeight.Medium, fontSize = 20.sp, lineHeight = 24.sp)
                    Text(value(first.id), color = Ink.text.copy(alpha = .55f), fontFamily = Golos, fontSize = 15.sp, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(if (rtl) PhosphorIcons.Bold.CaretLeft else PhosphorIcons.Bold.CaretRight, null, Modifier.size(18.dp), tint = Ink.text.copy(alpha = .4f))
            }
        }
    }

    @Composable
    private fun ColumnScope.Page() {
        val s = section
        Title(t(s.label), true)
        s.rows.forEach { r ->
            val note = if (r.id == "start") (Prefs.startApp?.let { "${t("The TV starts on Window, then opens")} ${appName(it)}" } ?: t("The TV starts on Window")) else null
            SettingRow(r, focus == "row-${r.id}", note)
        }
        Spacer(Modifier.weight(1f))
        Text(t("◀ ▶ change · OK choose · Back"), color = Ink.text.copy(alpha = .45f), fontFamily = Golos, fontSize = 15.sp)
    }

    @Composable
    private fun SettingRow(r: Row, focused: Boolean, note: String?) {
        val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
        Row(
            Modifier.fillMaxWidth().heightIn(min = 80.dp).ring(focused, 22.dp).clip(RoundedCornerShape(22.dp))
                .background(Color.White.copy(alpha = if (focused) .12f else .06f)).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(t(r.label), color = Ink.text, fontFamily = Golos, fontWeight = FontWeight.Medium, fontSize = 20.sp, lineHeight = 24.sp)
                if (note != null) Text(note, color = Ink.text.copy(alpha = .55f), fontFamily = Golos, fontSize = 15.sp, lineHeight = 19.sp)
            }
            val dim = Ink.text.copy(alpha = .6f)
            when (r.kind) {
                RowKind.Toggle -> {
                    val k by animateFloatAsState(if (on(r.id)) 1f else 0f, settle(300), label = "toggle")
                    Box(Modifier.size(60.dp, 34.dp).clip(CircleShape).background(if (on(r.id)) Ink.accent else Color.White.copy(alpha = .18f))) {
                        Box(Modifier.padding(4.dp).size(26.dp).graphicsLayer { translationX = (if (rtl) -1 else 1) * k * 26.dp.toPx() }.clip(CircleShape).background(Color.White))
                    }
                }
                RowKind.Choice -> Row(verticalAlignment = Alignment.CenterVertically) {
                    // The arrows point the way the value moves, flipped with the language
                    Icon(if (rtl) PhosphorIcons.Bold.CaretRight else PhosphorIcons.Bold.CaretLeft, null, Modifier.size(18.dp), tint = if (focused) dim else Color.Transparent)
                    Text(value(r.id), Modifier.widthIn(min = 150.dp), color = Ink.text, fontFamily = Golos, fontWeight = FontWeight.Medium, fontSize = 19.sp, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Icon(if (rtl) PhosphorIcons.Bold.CaretLeft else PhosphorIcons.Bold.CaretRight, null, Modifier.size(18.dp), tint = if (focused) dim else Color.Transparent)
                }
                RowKind.Info -> Text(value(r.id), color = dim, fontFamily = Golos, fontSize = 17.sp)
                RowKind.Link -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(value(r.id), color = dim, fontFamily = Golos, fontSize = 17.sp, maxLines = 1)
                    Icon(if (rtl) PhosphorIcons.Bold.CaretLeft else PhosphorIcons.Bold.CaretRight, null, Modifier.size(20.dp), tint = Ink.text.copy(alpha = .45f))
                }
                RowKind.Action -> Icon(if (rtl) PhosphorIcons.Bold.CaretLeft else PhosphorIcons.Bold.CaretRight, null, Modifier.size(20.dp), tint = Ink.text.copy(alpha = .45f))
            }
        }
    }

    @Composable
    private fun ColumnScope.Deeper() {
        val which = sub ?: lastSub
        Title(t(when (which) { "ha" -> "Home Assistant"; "govee" -> "Govee"; "sensibo" -> "Sensibo"; "tuya" -> "Tuya"; else -> "Tiles on the wall" }), true)
        when (which) { "ha" -> HaPage(); "govee" -> GoveePage(); "sensibo" -> SensiboPage(); "tuya" -> TuyaPage(); else -> TilesPage() }
    }

    // ---------- The tiles page ----------

    @Composable
    private fun ColumnScope.TilesPage() {
        val listState = rememberLazyListState()
        val tiles = Prefs.tiles
        val items = list
        // Keep the focused row in view as the remote walks the list
        val at = when {
            focus.startsWith("tw-a-") -> tiles.size + 3 + idx(focus)
            focus == "tw-type" -> tiles.size + 2
            focus.startsWith("tw-") -> 1 + idx(focus)
            else -> 0
        }
        // Scroll only when the focused row would leave the view, and only as far as it needs: scrolling the
        // whole list on every press is what made a long list stutter on the TV
        LaunchedEffect(at) {
            val info = listState.layoutInfo
            val row = info.visibleItemsInfo.firstOrNull { it.index == at }
            val margin = 96
            when {
                row == null -> listState.scrollToItem((at - 3).coerceAtLeast(0))
                row.offset < info.viewportStartOffset + margin -> listState.animateScrollBy((row.offset - info.viewportStartOffset - margin).toFloat())
                row.offset + row.size > info.viewportEndOffset - margin -> listState.animateScrollBy((row.offset + row.size - info.viewportEndOffset + margin).toFloat())
            }
        }
        val added = remember(tiles) { tiles.mapTo(HashSet()) { it.entity } }
        LazyColumn(Modifier.weight(1f), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("${t("In this order, left to right")} · ${f("{0} tiles", tiles.size)}", color = Ink.text.copy(alpha = .55f), fontFamily = Golos, fontSize = 15.sp) }
            tiles.forEachIndexed { i, x -> item(key = "w" + x.entity) { WallRow(i, x) } }
            item { Text(t("Add from Home Assistant"), Modifier.padding(top = 16.dp), color = Ink.text, fontFamily = Golos, fontWeight = FontWeight.SemiBold, fontSize = 20.sp) }
            item {
                Row(
                    Modifier.fillMaxWidth().height(60.dp).ring(focus == "tw-type", 20.dp).clip(RoundedCornerShape(20.dp))
                        .background(Color.White.copy(alpha = if (focus == "tw-type") .12f else .06f)).padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(t("Show"), Modifier.weight(1f), color = Ink.text.copy(alpha = .6f), fontFamily = Golos, fontSize = 17.sp)
                    Text(if (Lang.he) "▶  ${t(TYPES[type].second)}  ◀" else "◀  ${t(TYPES[type].second)}  ▶", color = Ink.text, fontFamily = Golos, fontWeight = FontWeight.Medium, fontSize = 19.sp)
                }
            }
            itemsIndexed(items, key = { _, e -> "a" + e.entity }) { i, e -> AddRow(i, e, e.entity in added) }
            item { Text(f("{0} light segments and settings switches are left out", hidden), Modifier.padding(top = 8.dp, bottom = 12.dp), color = Ink.text.copy(alpha = .4f), fontFamily = Golos, fontSize = 14.sp) }
        }
    }

    @Composable
    private fun WallRow(i: Int, x: Tile) {
        val m = moving == i
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.weight(1f).heightIn(min = 68.dp).ring(focus == "tw-w-$i", 20.dp).clip(RoundedCornerShape(20.dp))
                    .background(if (m) Ink.accent else Color.White.copy(alpha = if (focus == "tw-w-$i") .12f else .06f)).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val ink = if (m) Color(0xFF1B1308) else Ink.text
                Icon(if (m) PhosphorIcons.Bold.ArrowsDownUp else kindIcon(x.kind), null, Modifier.size(22.dp), tint = ink.copy(alpha = if (m) 1f else .7f))
                Column(Modifier.weight(1f)) {
                    if (editing == "tw-r-$i") EditField() else Text(t(x.name), color = ink, fontFamily = Golos, fontWeight = FontWeight.Medium, fontSize = 19.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val sub = when {
                        m -> t("▲▼ move · OK done")
                        focus == "tw-w-$i" -> t("OK to move")
                        x.kind == Kind.Climate -> t("A big AC control, at the end")
                        else -> source(x.entity)
                    }
                    Text(sub, color = ink.copy(alpha = .5f), fontFamily = Golos, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Round(PhosphorIcons.Bold.PencilSimple, focus == "tw-r-$i")
            Round(PhosphorIcons.Bold.X, focus == "tw-x-$i")
        }
    }

    @Composable
    private fun AddRow(i: Int, e: Entity, added: Boolean) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).ring(focus == "tw-a-$i", 20.dp).clip(RoundedCornerShape(20.dp))
                .background(Color.White.copy(alpha = if (focus == "tw-a-$i") .12f else .04f)).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(kindIcon(e.kind), null, Modifier.size(22.dp), tint = Ink.text.copy(alpha = .7f))
            Column(Modifier.weight(1f)) {
                Text(e.name, color = Ink.text, fontFamily = Golos, fontWeight = FontWeight.Medium, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(source(e.entity), color = Ink.text.copy(alpha = .45f), fontFamily = Golos, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(Modifier.size(34.dp).clip(CircleShape).background(if (added) Ink.accent else Color.White.copy(alpha = .1f)), contentAlignment = Alignment.Center) {
                Icon(if (added) PhosphorIcons.Bold.Check else PhosphorIcons.Bold.Plus, null, Modifier.size(18.dp), tint = if (added) Color(0xFF1B1308) else Ink.text)
            }
        }
    }

    @Composable
    private fun Round(icon: ImageVector, focused: Boolean) {
        Box(
            Modifier.size(56.dp).ring(focused, 28.dp).clip(CircleShape).background(Color.White.copy(alpha = if (focused) .14f else .06f)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, Modifier.size(20.dp), tint = Ink.text) }
    }

    // A field the TV's keyboard types into; Done (or OK) keeps it, Back drops it
    @Composable
    private fun EditField() {
        val fr = remember { FocusRequester() }
        val keyboard = LocalSoftwareKeyboardController.current
        // Some TVs' keyboards wait to be asked: focus the field, then ask for the keyboard
        LaunchedEffect(Unit) { fr.requestFocus(); delay(150); keyboard?.show() }
        BasicTextField(
            draft, { draft = it },
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = .12f)).padding(horizontal = 8.dp, vertical = 4.dp).focusRequester(fr),
            textStyle = TextStyle(color = Ink.text, fontFamily = Golos, fontWeight = FontWeight.Medium, fontSize = 19.sp),
            singleLine = true,
            cursorBrush = SolidColor(Ink.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { commitEdit() }),
        )
    }

    // ---------- The Home Assistant page ----------

    @Composable
    private fun ColumnScope.HaPage() {
        when (signIn.phase) {
            SignIn.Phase.Idle -> {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Color.White.copy(alpha = .06f)).padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(if (Ha.linked) Color(0xFF7BE0A0) else Color.White.copy(alpha = .3f)))
                    Column {
                        Text(t(if (Ha.linked) "Connected" else "Not connected"), color = Ink.text, fontFamily = Golos, fontWeight = FontWeight.Medium, fontSize = 20.sp)
                        Text(
                            if (Ha.linked) listOf(Ha.home, Ha.version.takeIf { it.isNotEmpty() }?.let { "Home Assistant $it" }).filterNotNull().filter { it.isNotEmpty() }.joinToString(" · ")
                            else t("The wall shows no lights or AC until Window is connected"),
                            color = Ink.text.copy(alpha = .55f), fontFamily = Golos, fontSize = 15.sp,
                        )
                    }
                }
                if (Ha.linked) HaRow("ha-server", PhosphorIcons.Fill.House, t("Server"), Ha.server)
                HaRow("ha-switch", PhosphorIcons.Fill.WifiHigh, t(if (Ha.linked) "Connect another Home Assistant" else "Connect Home Assistant"))
                if (Ha.linked) HaRow("ha-out", PhosphorIcons.Fill.SignOut, t("Sign out"), danger = true)
            }
            SignIn.Phase.Search, SignIn.Phase.Found -> {
                Text(t(if (signIn.phase == SignIn.Phase.Search) "Looking for Home Assistant on your network…" else "Found on your network"), color = Ink.text.copy(alpha = .7f), fontFamily = Golos, fontSize = 18.sp)
                signIn.found.forEachIndexed { i, s -> HaRow("ha-found-$i", PhosphorIcons.Fill.House, s.name, s.url.removePrefix("http://").removePrefix("https://")) }
                if (signIn.phase == SignIn.Phase.Found) {
                    if (editing == "ha-type") {
                        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Color.White.copy(alpha = .12f)).padding(16.dp)) { EditField() }
                    } else HaRow("ha-type", PhosphorIcons.Fill.Keyboard, t("Type the address"))
                }
                HaRow("ha-cancel", PhosphorIcons.Bold.X, t("Cancel"))
            }
            SignIn.Phase.Qr, SignIn.Phase.Failed -> {
                Column(Modifier.fillMaxWidth().weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (signIn.phase == SignIn.Phase.Qr) {
                        signIn.qr?.let { q ->
                            Box(Modifier.padding(top = 8.dp).size(280.dp).clip(RoundedCornerShape(24.dp)).background(Color.White).padding(20.dp)) {
                                Image(q, null, Modifier.fillMaxSize(), filterQuality = androidx.compose.ui.graphics.FilterQuality.None)
                            }
                        }
                        Text(t("Scan with your phone to sign in"), color = Ink.text, fontFamily = Golos, fontWeight = FontWeight.Medium, fontSize = 20.sp)
                        Text(t("Your phone opens Home Assistant’s own sign-in. Your password never reaches the TV."), color = Ink.text.copy(alpha = .6f), fontFamily = Golos, fontSize = 15.sp, textAlign = TextAlign.Center)
                        Text(t("Waiting for your phone…"), color = Ink.text.copy(alpha = .5f), fontFamily = Golos, fontSize = 15.sp)
                    } else Text(t("Sign-in didn't work. Try again"), color = Ink.text, fontFamily = Golos, fontSize = 18.sp)
                }
                HaRow("ha-cancel", PhosphorIcons.Bold.X, t("Cancel"))
            }
        }
    }

    // ---------- The Govee page ----------

    @Composable
    private fun ColumnScope.GoveePage() {
        val k = Govee.key
        if (editing == "gv-key") {
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Color.White.copy(alpha = .12f)).padding(16.dp)) { EditField() }
        } else HaRow("gv-key", PhosphorIcons.Fill.Key, t("API key"), if (k.isEmpty()) t("Not set") else "\u2066••••" + k.takeLast(4) + "\u2069")
        HaRow("gv-scan", PhosphorIcons.Fill.WifiHigh, t("Find lights on the network"), if (Govee.lan.isNotEmpty()) f("{0} found", Govee.lan.size) else null)
        if (k.isNotEmpty()) HaRow("gv-clear", PhosphorIcons.Fill.Trash, t("Remove the key"), danger = true)
        note?.let { Text(it, color = Ink.text.copy(alpha = if (busy) .55f else .8f), fontFamily = Golos, fontSize = 16.sp) }
        Spacer(Modifier.weight(1f))
        Text(
            t("On the network: turn on LAN Control for each light in the Govee Home app. Faster, and needs no key.") + "\n\n" +
                t("With a key: in the Govee Home app, Profile › Settings › Apply for API Key. It arrives by email. Type or paste it here (the Google TV app on your phone can paste)."),
            color = Ink.text.copy(alpha = .5f), fontFamily = Golos, fontSize = 15.sp, lineHeight = 21.sp,
        )
    }

    // ---------- The Sensibo and Tuya pages ----------

    @Composable
    private fun ColumnScope.KeyRow(id: String, label: String, value: String, secret: Boolean = true) {
        if (editing == id) {
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Color.White.copy(alpha = .12f)).padding(16.dp)) { EditField() }
        } else HaRow(id, PhosphorIcons.Fill.Key, label, if (value.isEmpty()) t("Not set") else if (secret) "\u2066••••" + value.takeLast(4) + "\u2069" else "\u2066" + value + "\u2069")
    }

    @Composable
    private fun ColumnScope.Note() {
        note?.let { Text(it, color = Ink.text.copy(alpha = if (busy) .55f else .8f), fontFamily = Golos, fontSize = 16.sp) }
    }

    @Composable
    private fun ColumnScope.Help(text: String) {
        Spacer(Modifier.weight(1f))
        Text(text, color = Ink.text.copy(alpha = .5f), fontFamily = Golos, fontSize = 15.sp, lineHeight = 21.sp)
    }

    @Composable
    private fun ColumnScope.SensiboPage() {
        KeyRow("sb-key", t("API key"), Sensibo.key)
        if (Sensibo.key.isNotEmpty()) {
            HaRow("sb-check", PhosphorIcons.Fill.WifiHigh, t("Check the connection"))
            HaRow("sb-clear", PhosphorIcons.Fill.Trash, t("Remove the key"), danger = true)
        }
        Note()
        Help(t("Sign in at home.sensibo.com, open the menu › API, and add a key. Type or paste it here (the Google TV app on your phone can paste). Each Sensibo shows as a big AC control."))
    }

    @Composable
    private fun ColumnScope.TuyaPage() {
        when {
            Tuya.phase == Tuya.Phase.Qr -> {
                Column(Modifier.fillMaxWidth().weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Tuya.qr?.let { q ->
                        Box(Modifier.padding(top = 8.dp).size(280.dp).clip(RoundedCornerShape(24.dp)).background(Color.White).padding(20.dp)) {
                            Image(q, null, Modifier.fillMaxSize(), filterQuality = androidx.compose.ui.graphics.FilterQuality.None)
                        }
                    }
                    Text(t("Scan with Smart Life or Tuya Smart"), color = Ink.text, fontFamily = Golos, fontWeight = FontWeight.Medium, fontSize = 20.sp)
                    Text(t("In the app, tap + at the top, then Scan, and confirm the sign-in on your phone."), color = Ink.text.copy(alpha = .6f), fontFamily = Golos, fontSize = 15.sp, textAlign = TextAlign.Center)
                    Text(t("Waiting for your phone…"), color = Ink.text.copy(alpha = .5f), fontFamily = Golos, fontSize = 15.sp)
                }
                HaRow("ty-cancel", PhosphorIcons.Bold.X, t("Cancel"))
            }
            Tuya.phase == Tuya.Phase.Waiting -> HaRow("ty-cancel", PhosphorIcons.Bold.X, t("Cancel"))
            Tuya.linked -> {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Color.White.copy(alpha = .06f)).padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(Color(0xFF7BE0A0)))
                    Column {
                        Text(t("Connected"), color = Ink.text, fontFamily = Golos, fontWeight = FontWeight.Medium, fontSize = 20.sp)
                        if (Tuya.account.isNotEmpty()) Text("\u2066" + Tuya.account + "\u2069", color = Ink.text.copy(alpha = .55f), fontFamily = Golos, fontSize = 15.sp)
                    }
                }
                HaRow("ty-check", PhosphorIcons.Fill.WifiHigh, t("Check the connection"))
                HaRow("ty-out", PhosphorIcons.Fill.SignOut, t("Sign out"), danger = true)
                Note()
                Help(t("Add your Tuya lights, plugs and curtains in Tiles on the wall."))
            }
            else -> {
                KeyRow("ty-code", t("User Code"), "", secret = false)
                Note()
                Help(t("In Smart Life or Tuya Smart: Me › the gear at the top › Account and Security. The User Code is at the bottom. Type it here, and the TV shows a QR code to scan with the app. No developer account needed."))
            }
        }
    }

    @Composable
    private fun HaRow(id: String, icon: ImageVector, label: String, value: String? = null, danger: Boolean = false) {
        Row(
            Modifier.fillMaxWidth().height(72.dp).ring(focus == id, 22.dp).clip(RoundedCornerShape(22.dp))
                .background(Color.White.copy(alpha = if (focus == id) .12f else .06f)).padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(icon, null, Modifier.size(22.dp), tint = if (danger) Color(0xFFFF9B8A) else Ink.text.copy(alpha = .7f))
            Text(label, Modifier.weight(1f), color = Ink.text, fontFamily = Golos, fontWeight = FontWeight.Medium, fontSize = 19.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (value != null) Text(value, color = Ink.text.copy(alpha = .55f), fontFamily = Golos, fontSize = 16.sp, maxLines = 1)
        }
    }
}

// Where a tile comes from, said under its name
fun source(entity: String) = when {
    Govee.isGovee(entity) -> Govee.label(entity)
    Sensibo.isSensibo(entity) -> Sensibo.LABEL
    Tuya.isTuya(entity) -> Tuya.LABEL
    else -> entity
}

fun kindIcon(k: Kind): ImageVector = when (k) {
    Kind.Light -> PhosphorIcons.Fill.Lightbulb
    Kind.Climate -> PhosphorIcons.Fill.Thermometer
    Kind.Switch -> PhosphorIcons.Fill.Power
    Kind.Fan -> PhosphorIcons.Fill.FanFill
    Kind.Cover -> PhosphorIcons.Fill.SquareSplitHorizontal
    Kind.Scene -> PhosphorIcons.Fill.Sparkle
}

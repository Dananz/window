package com.dananz.window

import androidx.compose.ui.graphics.CompositingStrategy

import androidx.compose.ui.graphics.graphicsLayer


import androidx.compose.animation.core.animateFloatAsState

import android.content.Context
import android.content.Intent
import android.util.Log
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.animation.core.animateFloatAsState as fade
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.time.LocalDate
import java.time.LocalTime

object Off { var parts = emptySet<String>() }

class MainActivity : ComponentActivity() {
    private var apps by mutableStateOf(emptyList<TvApp>())
    private var resumes by mutableIntStateOf(0)
    // A remote button that opens one of Window's panels (Input, the gear), counted so the same one twice opens twice
    private var panel by mutableStateOf<Pair<String, Int>?>(null)
    // Home pressed, counted: back to the main view (everything closed). Not after an app Window opened
    // (`away`): coming back from it, Window stays as it was left
    private var homes by mutableIntStateOf(0)
    private var away = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // For measuring on the TV: `am start ... --es off scene,controls` leaves those parts out
        Off.parts = intent.getStringExtra("off")?.split(",")?.toSet() ?: emptySet()
        Prefs.load(this)
        Ha.load(this)
        Govee.load(this)
        Sensibo.load(this)
        Tuya.load(this)
        panelFrom(intent)
        setContent { DesignCanvas { Home(apps, resumes, panel, homes) { apps = it } } }
        // Window's Home button is an accessibility service, and with any service on, Compose describes
        // the screen to the system after every change. Nothing here is read aloud, so it is left out
        window.decorView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        // Window is the home screen: Back never leaves it (the screen's own keys decide what Back does)
        onBackPressedDispatcher.addCallback(this) {}
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getStringExtra(HomeButton.PANEL) == null) { if (away) away = false else homes++ }
        panelFrom(intent)
    }

    // Every app, TV settings page or system dialog Window opens goes through here
    override fun startActivity(intent: Intent, options: Bundle?) {
        super.startActivity(intent, options)
        away = true
    }

    private fun panelFrom(intent: Intent) {
        intent.getStringExtra(HomeButton.PANEL)?.let { panel = it to (panel?.second ?: 0) + 1 }
    }

    override fun onResume() {
        super.onResume()
        // Home's check in onNewIntent comes first; back by any other way (Back from the app), it's spent
        away = false
        Keys.repair(this)
        Saver.byDefault(this)
        if (Prefs.saverMusic) Music.grant(this)
        // Apps come and go while we are away (installs, updates): read them again each time
        apps = loadApps(this)
        resumes++
    }
}

private enum class Dir { Left, Right, Up, Down }

@Composable
fun Home(apps: List<TvApp>, resumes: Int, panel: Pair<String, Int>?, homes: Int, onApps: (List<TvApp>) -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("home", Context.MODE_PRIVATE) }
    val root = remember { FocusRequester() }
    // The remote's focus, as in the prototype: one id, moved along fixed rows
    var focus by remember { mutableStateOf("app-0") }
    var placeOpen by remember { mutableStateOf(false) }
    var place by remember { mutableStateOf(runCatching { Place.valueOf(prefs.getString("place", "")!!) }.getOrDefault(Place.NewYork)) }
    var day by remember { mutableIntStateOf(0) }
    var weather by remember { mutableStateOf<Weather?>(null) }
    var now by remember { mutableStateOf(LocalTime.now()) }
    var today by remember { mutableStateOf(LocalDate.now()) }
    val scope = rememberCoroutineScope()
    val room = remember { Room(scope) }
    // The control whose big control is open (held OK), and the AC sheet's row
    var grabbed by remember { mutableStateOf<String?>(null) }
    var acRow by remember { mutableIntStateOf(0) }
    var hold by remember { mutableStateOf<Job?>(null) }
    var held by remember { mutableStateOf(false) }
    val lastLevel = remember { mutableMapOf<String, Int>() }
    // The All apps screen: read ahead in the background, and again each time Window comes back
    val all = remember { AllAppsState(context, scope) }
    LaunchedEffect(resumes) { all.load(withContext(Dispatchers.Default) { loadAllApps(context) }) }
    // Quick settings, from the gear before the place pin
    var quickOpen by remember { mutableStateOf(false) }
    val tv = remember { TvState(context) }
    // A line at the foot of the screen for a moment: what just happened
    var toast by remember { mutableStateOf<Pair<String, Int>?>(null) }
    LaunchedEffect(toast) { if (toast != null) { delay(2600); toast = null } }
    // Window settings, in the quick panel's sheet
    val placeNow = { place }
    val setPlaceNow = { p: Place -> place = p; prefs.edit().putString("place", p.name).apply() }
    val appsNow = rememberUpdatedState(apps)
    val say = { text: String -> toast = text to ((toast?.second ?: 0) + 1) }
    val openAll = { quickOpen = false; all.show() }
    val settings = remember {
        SettingsState(context, scope, object : SettingsHost {
            override val place get() = placeNow()
            override fun setPlace(p: Place) = setPlaceNow(p)
            override val shelf get() = appsNow.value
            override fun openAllApps() = openAll()
            override fun toast(text: String) = say(text)
        })
    }
    // The tiles on the wall follow the settings
    LaunchedEffect(Prefs.tiles) { room.sync(Prefs.tiles) }
    val rtl = Lang.he
    // An arrow as it reads on screen: in Hebrew the rows run from the right, so left and right swap
    fun visual(d: Dir) = if (!rtl) d else when (d) { Dir.Left -> Dir.Right; Dir.Right -> Dir.Left; else -> d }
    // An arrow being held: when it went down and when it last stepped (not state: read only by the keys)
    val arrow = remember { longArrayOf(0L, 0L) }
    var arrowHeld by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { root.requestFocus() }
    LaunchedEffect(focus, placeOpen) { Log.d("Window", "focus=$focus open=$placeOpen") }
    LaunchedEffect(Unit) { while (true) { now = LocalTime.now(); today = LocalDate.now(); delay(15_000) } }
    LaunchedEffect(Unit) { room.poll() }
    // The small player under the clock follows what plays on only while the home screen shows
    LifecycleStartEffect(Unit) { Player.start(context); onStopOrDispose { Player.stop() } }
    LaunchedEffect(Unit) { while (true) { runCatching { loadWeather() }.onSuccess { weather = it; Seen.weather = it }.onFailure { Log.w("Window", "weather", it) }; delay(10 * 60_000) } }

    // A place from the dropdown: shown, kept, and the dropdown closed
    fun choose(p: Place) { setPlaceNow(p); placeOpen = false; focus = "place-btn" }
    // Shuffle: a new place now, then one every Prefs.shuffle minutes (another than the one showing)
    fun anotherPlace() = Place.entries.filter { it != place }.random()
    LaunchedEffect(Prefs.shuffle) {
        val every = Prefs.shuffle
        if (every <= 0) return@LaunchedEffect
        while (true) { delay(every * 60_000L); setPlaceNow(anotherPlace()) }
    }

    val dayCount = weather?.days?.size ?: 0
    // Rows, top to bottom: view + forecast, the room's controls, the apps. Left and right stay in
    // the row; up or down lands on the next row's first item.
    val controls = (if (room.lights.any { it.kind == Kind.Light }) listOf("all-lights") else emptyList()) +
        room.lights.map { "light-${it.id}" } + room.acs.map { "ac-${it.id}" }
    val rows = listOf(
        listOf("qs-btn", "place-btn") + (0 until dayCount).map { "day-$it" },
        if (Player.song != null) listOf(PLAYER, PLAYER_NEXT) else emptyList(),
        // Nothing smart set up, or the tiles switched off: no row (tiles are added from Window settings)
        if (Prefs.wall) controls else emptyList(),
        apps.indices.map { "app-$it" },
    ).filter { it.isNotEmpty() }
    // The tile with focus went (switched off, or taken off the wall): on to the apps
    val wallShown = Prefs.wall && controls.isNotEmpty()
    LaunchedEffect(wallShown, controls) {
        val onTile = focus == "all-lights" || focus.startsWith("light-") || focus.startsWith("ac-")
        if (onTile && (!wallShown || focus !in controls)) {
            focus = if (apps.isNotEmpty()) "app-0" else "qs-btn"
            grabbed = null
        }
    }
    val menu = Place.entries.map { "place-${it.name}" } + "place-shuffle"
    // Input or the gear on the remote: quick settings, on the inputs for Input
    LaunchedEffect(panel) {
        val (which, _) = panel ?: return@LaunchedEffect
        tv.refresh()
        placeOpen = false; grabbed = null
        quickOpen = true
        focus = if (which == HomeButton.PANEL_INPUTS) (if (tv.inputs.isNotEmpty()) "qs-in-0" else "qs-in-home") else "qs-vol"
    }
    // The last control focused above the apps, where Back from the apps returns to
    var above by remember { mutableStateOf("all-lights") }
    // (never the player, which goes when the song does)
    LaunchedEffect(focus) { if (!focus.startsWith("app-") && !focus.startsWith(PLAYER) && rows.any { focus in it }) above = focus }
    // The song ended with the remote on the player: up to where the remote was before
    LaunchedEffect(Player.song == null) {
        if (Player.song == null && focus.startsWith(PLAYER)) focus = above.takeIf { a -> rows.any { a in it } } ?: rows.first().first()
    }

    fun move(pressed: Dir) {
        val d = visual(pressed)
        if (placeOpen && (focus in menu || focus == "place-btn")) {
            val i = menu.indexOf(focus)
            focus = when (d) {
                Dir.Down -> menu.getOrElse(i + 1) { focus }
                Dir.Up -> if (i <= 0) "place-btn" else menu[i - 1]
                else -> focus
            }
            return
        }
        val r = rows.indexOfFirst { focus in it }
        if (r < 0) { focus = rows.last().first(); return }
        // Up or Down from the apps' full-screen view leaves it, back to where the remote came from, as Back does
        if (focus.startsWith("app-") && (d == Dir.Up || d == Dir.Down)) {
            focus = above.takeIf { a -> rows.any { a in it } } ?: rows.getOrNull(r - 1)?.first() ?: focus
            return
        }
        val row = rows[r]
        focus = when (d) {
            Dir.Left -> row.getOrElse(row.indexOf(focus) - 1) { focus }
            Dir.Right -> row.getOrElse(row.indexOf(focus) + 1) { focus }
            Dir.Up -> rows.getOrNull(r - 1)?.first() ?: focus
            Dir.Down -> rows.getOrNull(r + 1)?.first() ?: focus
        }
        if (!focus.startsWith("place-")) placeOpen = false
        if (focus.startsWith("day-")) day = focus.removePrefix("day-").toInt()
    }

    // OK on a light or the AC: a press switches it, a hold opens its big control
    fun tap(id: String) {
        val anyOn = room.lights.any { it.level > 0 }
        when {
            id == PLAYER -> Player.toggle()
            id == "all-lights" -> room.lights.filter { it.kind == Kind.Light }.forEach {
                // Off keeps each light's level, so on brings the room back as it was
                if (it.level > 0) lastLevel[it.id] = it.level
                room.setLevel(it.id, if (anyOn) 0 else lastLevel[it.id] ?: 60)
            }
            id.startsWith("ac-") -> room.acUnit(id)?.let { room.changeAc(id, it.ac.copy(on = !it.ac.on)) }
            id.startsWith("light-") -> {
                val l = room.lights.first { "light-${it.id}" == id }
                if (l.kind != Kind.Light) { room.setLevel(l.id, if (l.level > 0) 0 else 100); if (l.kind == Kind.Scene) toast = f("Running {0}", t(l.name)) to (toast?.second ?: 0) + 1; return }
                if (l.level > 0) lastLevel[l.id] = l.level
                room.setLevel(l.id, if (l.level > 0) 0 else lastLevel[l.id] ?: 60)
            }
        }
    }
    fun holdable(id: String) = id == PLAYER || (id.startsWith("light-") && room.lights.any { "light-${it.id}" == id && it.kind == Kind.Light }) || room.acUnit(id) != null
    // Held on the player it opens the app playing, and nothing is grabbed
    fun open(id: String) { if (id == PLAYER) Player.open(context) else { acRow = 0; grabbed = id } }

    fun adjust(pressed: Dir) {
        // Right is always toward the right of the screen: in Hebrew rows run the other way
        val d = visual(pressed)
        val id = grabbed ?: return
        val unit = room.acUnit(id)
        if (unit != null) {
            val ac = unit.ac
            when (d) {
                Dir.Up, Dir.Down -> acRow = (acRow + if (d == Dir.Down) 1 else -1).coerceIn(0, 2)
                else -> {
                    // The fan's segments run with the language; temperature, like the light's level,
                    // goes up to the right of the screen in either
                    val k = if (d == Dir.Right) 1 else -1
                    when (acRow) {
                        0 -> room.stepTemp(id, if (pressed == Dir.Right) 1 else -1)
                        1 -> room.changeAc(id, ac.copy(on = true, mode = AcMode.entries[(ac.mode.ordinal + 1) % AcMode.entries.size]))
                        else -> room.changeAc(id, ac.copy(on = true, fan = (ac.fan + k).coerceIn(0, FANS.lastIndex)))
                    }
                }
            }
        } else {
            val l = room.lights.first { "light-${it.id}" == id }
            room.setLevel(l.id, l.level + if (pressed == Dir.Up || pressed == Dir.Right) 10 else -10)
        }
    }

    fun ok() {
        when {
            focus == "qs-btn" -> { tv.refresh(); quickOpen = true; focus = "qs-vol" }
            focus == "all-lights" -> tap(focus)
            // A switch, fan, cover or scene: not holdable, so OK reaches it here
            focus.startsWith("light-") && !holdable(focus) -> tap(focus)
            focus == PLAYER_NEXT -> Player.next()
            // Opening the list lands on the view that is showing
            focus == "place-btn" -> if (placeOpen) placeOpen = false else { placeOpen = true; focus = "place-${place.name}" }
            // Off, or on with a new place right away
            focus == "place-shuffle" -> if (Prefs.shuffle > 0) { Prefs.changeShuffle(0); placeOpen = false; focus = "place-btn" }
                else { Prefs.changeShuffle(Prefs.shuffleEvery); choose(anotherPlace()) }
            focus.startsWith("place-") -> { Prefs.changeShuffle(0); choose(Place.valueOf(focus.removePrefix("place-"))) }
            focus.startsWith("app-") -> apps.getOrNull(focus.removePrefix("app-").toInt())?.let { app ->
                if (app.shortcut == Shortcut.AllApps) all.show()
                else app.intent?.let { runCatching { context.startActivity(it) }.onFailure { e -> Log.w("Window", "launch ${app.id}", e) } }
            }
        }
    }

    // OK goes down and comes up on a light, the AC or the player (the remote's OK, or the mouse's button):
    // a press switches it when it comes up, a hold opens its big control, and OK on an open one closes it
    fun holdDown() {
        if (hold != null || held) return
        if (grabbed != null) held = true
        else {
            val id = focus
            hold = scope.launch { delay(450); held = true; open(id) }
        }
    }
    fun holdUp() {
        val h = hold
        hold = null
        when {
            h != null && h.isActive -> { h.cancel(); tap(focus) }
            grabbed != null && held && h == null -> { grabbed = null; held = false }
            else -> held = false
        }
    }

    // OK in quick settings
    fun quickOkNow() { when (focus) { "qs-window" -> settings.show(); "qs-saver" -> settings.preview = true; else -> quickOk(tv, focus) } }

    // Back from All apps lands on its card; changed favorites are on the shelf already, from the
    // apps in memory (the banners are read again when Window next comes back)
    fun closeAll() {
        val shelf = if (all.shelfChanged) {
            all.favs.mapNotNull { pkg -> apps.firstOrNull { it.id == pkg } ?: all.apps[pkg] } + apps.filter { it.shortcut != null }
        } else apps
        if (all.shelfChanged) onApps(shelf)
        all.shelfChanged = false
        focus = "app-${shelf.indexOfFirst { it.shortcut == Shortcut.AllApps }.coerceAtLeast(0)}"
    }

    // Home: the main view, from anywhere in Window (the screensaver, settings, quick settings, All apps,
    // the apps' full-screen view); focus goes back up to the last control above the apps
    LaunchedEffect(homes) {
        if (homes == 0) return@LaunchedEffect
        settings.preview = false
        if (settings.open) settings.close()
        quickOpen = false; placeOpen = false; grabbed = null
        hold?.cancel(); hold = null; held = false
        if (all.open) { while (!all.back()) Unit; closeAll() }
        if (!rows.any { focus in it } || focus.startsWith("app-")) focus = above.takeIf { a -> rows.any { a in it } } ?: rows.first().first()
    }

    // Back, whichever screen is up (the remote's Back, Escape, or the mouse's right button)
    fun goBack() {
        when {
            settings.preview -> settings.preview = false
            settings.editing != null -> settings.back()
            settings.open -> if (!settings.back()) focus = "qs-window"
            quickOpen -> { quickOpen = false; focus = "qs-btn" }
            all.open -> if (all.back()) closeAll()
            grabbed != null -> grabbed = null
            // Back closes what is open; from the apps it goes back up to where the remote came from
            placeOpen -> { placeOpen = false; focus = "place-btn" }
            focus.startsWith("app-") -> focus = above.takeIf { a -> rows.any { a in it } } ?: rows.first().first()
        }
    }
    // When Back last came from the mouse, and from a key: a right click may also reach Window as the
    // Back key, and must act once. Only a mouse and a key pair up, so the remote's own presses never do
    val backAt = remember { longArrayOf(-1000L, -1000L) }

    // The mouse, routed to what the remote would do on the same item
    val pointer = remember { PointerHub() }
    // The press that only let go of an open control, or only brought a card to the front: its release does nothing more
    val pressSkip = remember { booleanArrayOf(false) }
    fun pointHome(id: String) {
        if (rows.none { id in it }) return
        focus = id
        if (id.startsWith("day-")) day = id.removePrefix("day-").toInt()
    }
    val pointing = object : Pointing {
        override fun hover(id: String) {
            when {
                settings.preview -> {}
                settings.open -> settings.pointTo(id)
                quickOpen -> if (quickRows(tv).any { id in it }) focus = id
                all.open -> all.pointTo(id)
                // While a control is open the arrows change it: the pointer picks the AC's row and nothing else
                grabbed != null -> if (id.startsWith("acrow-")) acRow = id.removePrefix("acrow-").toInt()
                hold != null || held -> {}
                // The list stays open until a click, as with the remote
                placeOpen -> if (id in menu || id == "place-btn") focus = id
                // The raised shelf centres its focus: a card that slid under a resting pointer would take
                // the focus and slide again, so side cards wait for a click. Nothing else takes it either, or
                // the pointer on its way up from the rest row would cross the tiles and drop the shelf
                focus.startsWith("app-") -> {}
                else -> pointHome(id)
            }
        }
        override fun press(id: String) {
            pressSkip[0] = false
            when {
                settings.open -> settings.pointTo(id)
                quickOpen -> hover(id)
                all.open -> all.pointDown(id)
                grabbed != null -> when {
                    id == grabbed -> holdDown()
                    id == SHEET -> pressSkip[0] = true
                    id.startsWith("acrow-") -> { acRow = id.removePrefix("acrow-").toInt(); pressSkip[0] = true }
                    // A click elsewhere only lets go of the open control
                    else -> { grabbed = null; held = false; pressSkip[0] = true; pointHome(id) }
                }
                hold != null || held -> pressSkip[0] = true
                placeOpen && id == "place-menu" -> pressSkip[0] = true
                placeOpen && id !in menu && id != "place-btn" -> { placeOpen = false; pointHome(id); pressSkip[0] = true }
                // On the raised shelf a click on another card brings it to the front, and one off the shelf
                // only takes the focus there
                focus.startsWith("app-") && id != focus -> { pointHome(id); pressSkip[0] = true }
                else -> {
                    if (id in menu) focus = id else pointHome(id)
                    if (focus == id && holdable(id)) holdDown()
                }
            }
        }
        override fun release(id: String, inside: Boolean) {
            if (pressSkip[0]) { pressSkip[0] = false; return }
            when {
                settings.preview -> {}
                settings.open -> if (inside) settings.click(id)
                quickOpen -> if (inside && focus == id) quickOkNow()
                all.open -> all.pointUp(inside)
                hold != null || held || grabbed != null -> {
                    // Let go outside the item before the hold: nothing, as a press that changed its mind
                    val h = hold
                    if (!inside && h != null && h.isActive) { h.cancel(); hold = null } else holdUp()
                }
                inside && focus == id -> ok()
            }
        }
        override fun wheel(dx: Int, dy: Int) {
            if (settings.preview || settings.editing != null) return
            val sy = Integer.signum(dy)
            val sx = Integer.signum(dx)
            val n = if (dy != 0) kotlin.math.abs(dy) else kotlin.math.abs(dx)
            repeat(n) {
                when {
                    settings.open -> if (sy != 0) settings.move(if (sy > 0) Arrow.Down else Arrow.Up)
                    quickOpen -> if (sy != 0) focus = quickMove(quickRows(tv), focus, sy, across = false)
                    all.open -> if (sy != 0) all.route(0, sy) else all.route(if (rtl) -sx else sx, 0)
                    // Up (or to the right) brightens a light; on the AC it changes the row's value, warmer up
                    grabbed != null -> {
                        val ac = room.acUnit(grabbed!!) != null
                        adjust(when {
                            sy < 0 -> if (ac) Dir.Right else Dir.Up
                            sy > 0 -> if (ac) Dir.Left else Dir.Down
                            sx > 0 -> Dir.Right
                            else -> Dir.Left
                        })
                    }
                    // Along the shelf: down goes on to the next app the way the shelf reads (from the right in
                    // Hebrew); a sideways wheel goes the way it is pushed on screen
                    focus.startsWith("app-") -> move(when {
                        sy > 0 -> if (rtl) Dir.Left else Dir.Right
                        sy < 0 -> if (rtl) Dir.Right else Dir.Left
                        sx > 0 -> Dir.Right
                        else -> Dir.Left
                    })
                }
            }
        }
        override fun back() {
            val t = SystemClock.uptimeMillis()
            if (t - backAt[1] < 400) return
            backAt[0] = t
            goBack()
        }
        // A click on the background lets go of an open control, and closes the place list
        override fun miss() {
            when {
                settings.open || quickOpen || all.open -> {}
                grabbed != null -> { hold?.cancel(); hold = null; held = false; grabbed = null }
                placeOpen -> { placeOpen = false; focus = "place-btn" }
            }
        }
        override fun anyPress(): Boolean {
            if (!settings.preview) return false
            settings.preview = false
            return true
        }
    }
    SideEffect { pointer.target = pointing }

    val appIndex = focus.takeIf { it.startsWith("app-") }?.removePrefix("app-")?.toIntOrNull()
    // Typing in a settings field: the keys go to the keyboard, then back to the remote
    LaunchedEffect(settings.editing) { if (settings.editing == null) runCatching { root.requestFocus() } }
    val night = day == 0 && weather?.sunUp == false
    val sky = weather?.let { if (day == 0) it.sky else it.days.getOrNull(day)?.sky } ?: Sky.Clear

    Box(
        Modifier
            .fillMaxSize()
            .focusRequester(root)
            .focusable()
            .pointerRoot(pointer)
            .onPreviewKeyEvent { e ->
                // Tab would walk Compose's focus off the root, where the keys no longer reach Window
                if (e.key == Key.Tab) return@onPreviewKeyEvent true
                // A keyboard: Space is OK and Backspace is Back, except in a field being typed in
                val typing = settings.editing != null
                val isOk = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter || (e.key == Key.Spacebar && !typing)
                val isBack = e.key == Key.Back || e.key == Key.Escape || (e.key == Key.Backspace && !typing)
                val isArrow = e.key == Key.DirectionLeft || e.key == Key.DirectionRight || e.key == Key.DirectionUp || e.key == Key.DirectionDown
                // A held arrow steps at its own pace, whatever rate the remote repeats at
                if (isArrow) {
                    if (e.type == KeyEventType.KeyUp) { arrowHeld = false; return@onPreviewKeyEvent false }
                    if (e.type == KeyEventType.KeyDown) {
                        val t = SystemClock.uptimeMillis()
                        if (e.nativeKeyEvent.repeatCount == 0) { arrow[0] = t; arrow[1] = t }
                        else {
                            if (t - arrow[1] < Hold.step(t - arrow[0])) return@onPreviewKeyEvent true
                            arrow[1] = t
                            arrowHeld = true
                        }
                    }
                }
                // Back is acted on as it goes down; its way up is kept here too, or Android takes it
                // as Back for the whole app and sends Window behind Google TV's home
                if (isBack && e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent true
                if (isBack) {
                    val t = SystemClock.uptimeMillis()
                    if (t - backAt[0] < 400) return@onPreviewKeyEvent true
                    backAt[1] = t
                }
                // The screensaver's preview: any key ends it
                if (settings.preview) {
                    if (e.type == KeyEventType.KeyDown) settings.preview = false
                    return@onPreviewKeyEvent true
                }
                if (settings.editing != null) {
                    if (isBack) { goBack(); return@onPreviewKeyEvent true }
                    return@onPreviewKeyEvent false
                }
                if (settings.open) {
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent true
                    when {
                        e.key == Key.DirectionLeft -> settings.move(if (rtl) Arrow.Right else Arrow.Left)
                        e.key == Key.DirectionRight -> settings.move(if (rtl) Arrow.Left else Arrow.Right)
                        e.key == Key.DirectionUp -> settings.move(Arrow.Up)
                        e.key == Key.DirectionDown -> settings.move(Arrow.Down)
                        isOk -> if (e.nativeKeyEvent.repeatCount == 0) settings.ok()
                        isBack -> goBack()
                        else -> return@onPreviewKeyEvent false
                    }
                    return@onPreviewKeyEvent true
                }
                if (quickOpen) {
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent true
                    val rows = quickRows(tv)
                    when {
                        focus == "qs-vol" && (e.key == Key.DirectionLeft || e.key == Key.DirectionRight) -> tv.louder((e.key == Key.DirectionRight) != rtl)
                        e.key == Key.DirectionLeft -> focus = quickMove(rows, focus, if (rtl) 1 else -1, across = true)
                        e.key == Key.DirectionRight -> focus = quickMove(rows, focus, if (rtl) -1 else 1, across = true)
                        e.key == Key.DirectionUp -> focus = quickMove(rows, focus, -1, across = false)
                        e.key == Key.DirectionDown -> focus = quickMove(rows, focus, 1, across = false)
                        isOk -> if (e.nativeKeyEvent.repeatCount == 0) quickOkNow()
                        isBack -> goBack()
                        else -> return@onPreviewKeyEvent false
                    }
                    return@onPreviewKeyEvent true
                }
                if (all.open) {
                    // OK is decided on its way up (press) or after 500 ms down (hold: the app's menu)
                    if (isOk) {
                        if (e.type == KeyEventType.KeyDown) all.okDown(e.nativeKeyEvent.repeatCount == 0)
                        else if (e.type == KeyEventType.KeyUp) all.okUp()
                        return@onPreviewKeyEvent true
                    }
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent true
                    when {
                        e.key == Key.DirectionLeft -> all.route(if (rtl) 1 else -1, 0)
                        e.key == Key.DirectionRight -> all.route(if (rtl) -1 else 1, 0)
                        e.key == Key.DirectionUp -> all.route(0, -1)
                        e.key == Key.DirectionDown -> all.route(0, 1)
                        isBack -> goBack()
                        else -> return@onPreviewKeyEvent false
                    }
                    return@onPreviewKeyEvent true
                }
                // Press or hold on the room's controls: decided when OK comes back up
                if (isOk && (holdable(focus) || grabbed != null || hold != null)) {
                    if (e.type == KeyEventType.KeyDown) { if (e.nativeKeyEvent.repeatCount == 0) holdDown() }
                    else if (e.type == KeyEventType.KeyUp) holdUp()
                    return@onPreviewKeyEvent true
                }
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (grabbed != null) {
                    when {
                        e.key == Key.DirectionLeft -> adjust(Dir.Left)
                        e.key == Key.DirectionRight -> adjust(Dir.Right)
                        e.key == Key.DirectionUp -> adjust(Dir.Up)
                        e.key == Key.DirectionDown -> adjust(Dir.Down)
                        isBack -> goBack()
                        else -> return@onPreviewKeyEvent false
                    }
                    return@onPreviewKeyEvent true
                }
                when {
                    e.key == Key.DirectionLeft -> move(Dir.Left)
                    e.key == Key.DirectionRight -> move(Dir.Right)
                    e.key == Key.DirectionUp -> move(Dir.Up)
                    e.key == Key.DirectionDown -> move(Dir.Down)
                    // Once per press: OK held down repeats, and would switch the lights back and forth
                    isOk -> if (e.nativeKeyEvent.repeatCount == 0) ok()
                    isBack -> goBack()
                    else -> return@onPreviewKeyEvent false
                }
                true
            },
    ) {
        CompositionLocalProvider(LocalHeld provides arrowHeld, LocalPointer provides pointer) {
        // The room photo and the camera never mirror
        if ("scene" !in Off.parts) HomeScene(room.lights.associate { it.id to it.level }, sky, night, if (Prefs.reduced) "rest" else viewFor(focus), place, dim = appIndex != null || all.open || quickOpen,
            // Along the shelf the room drifts with the focused app, so moving between apps isn't static
            // (in Hebrew the shelf starts at the right, so it drifts the other way)
            pan = if (!Prefs.drift || Prefs.reduced) 0f else appIndex?.let { if (apps.size > 1) (it / (apps.size - 1f) - .5f) * (if (rtl) -1 else 1) else 0f } ?: 0f,
        )
        CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
        // Laid out in its own box, so the header and the rows take the language's direction
        Box(Modifier.fillMaxSize()) {
        // First, under the header and the player: its row is a list the size of the room above it, and
        // would take the mouse from everything drawn before it
        if ("controls" !in Off.parts && Prefs.wall) ControlsRow(room, focus, grabbed, acRow, dim = appIndex != null)
        val headerAlpha = animateFloatAsState(if (appIndex != null) .55f else 1f, glide(Motion.DIM), label = "header")
        if ("header" !in Off.parts) Box(Modifier.graphicsLayer { compositingStrategy = CompositingStrategy.ModulateAlpha; alpha = headerAlpha.value }) { Clock(now, today, weather, day) }
        Row(
            Modifier.align(Alignment.TopEnd).padding(top = 56.dp, end = 72.dp)
                .graphicsLayer { compositingStrategy = CompositingStrategy.ModulateAlpha; alpha = headerAlpha.value },
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            QuickButton(focus == "qs-btn")
            PlaceSwitch(place, focus == "place-btn", placeOpen, focus)
            Forecast(weather, day, focus)
        }
        MiniPlayer(focus, dim = appIndex != null)
        if (apps.isNotEmpty() && "apps" !in Off.parts) AppsCarousel(apps, appIndex.takeUnless { all.open })
        AllApps(all)
        QuickPanel(quickOpen && !settings.open, focus, tv)
        settings.Sheet()
        Toast(toast?.first)
        }
        }
        if (settings.preview) Dream(preview = true)
        }
    }
}

// What just happened, at the foot of the screen for a moment
@Composable
private fun Toast(text: String?) {
    var last by remember { mutableStateOf("") }
    if (text != null) last = text
    val a by fade(if (text != null) 1f else 0f, settle(Motion.SHEET), label = "toast")
    if (a == 0f) return
    Box(Modifier.fillMaxSize().padding(bottom = 64.dp), contentAlignment = Alignment.BottomCenter) {
        Text(
            last,
            Modifier.graphicsLayer { alpha = a; translationY = (1 - a) * 12.dp.toPx() }.clip(RoundedCornerShape(28.dp))
                .background(androidx.compose.ui.graphics.Color(0xF0131316)).padding(horizontal = 28.dp, vertical = 16.dp),
            color = Ink.text, fontFamily = Golos, fontSize = 20.sp,
        )
    }
}

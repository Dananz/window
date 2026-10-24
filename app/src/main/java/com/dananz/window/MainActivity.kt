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
        setContent { DesignCanvas { Home(apps, resumes, panel) { apps = it } } }
        // Window's Home button is an accessibility service, and with any service on, Compose describes
        // the screen to the system after every change. Nothing here is read aloud, so it is left out
        window.decorView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        // Window is the home screen: Back never leaves it (the screen's own keys decide what Back does)
        onBackPressedDispatcher.addCallback(this) {}
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        panelFrom(intent)
    }

    private fun panelFrom(intent: Intent) {
        intent.getStringExtra(HomeButton.PANEL)?.let { panel = it to (panel?.second ?: 0) + 1 }
    }

    override fun onResume() {
        super.onResume()
        Keys.repair(this)
        // Apps come and go while we are away (installs, updates): read them again each time
        apps = loadApps(this)
        resumes++
    }
}

private enum class Dir { Left, Right, Up, Down }

@Composable
fun Home(apps: List<TvApp>, resumes: Int, panel: Pair<String, Int>?, onApps: (List<TvApp>) -> Unit) {
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
    LaunchedEffect(Unit) { while (true) { runCatching { loadWeather() }.onSuccess { weather = it; Seen.weather = it }.onFailure { Log.w("Window", "weather", it) }; delay(10 * 60_000) } }

    fun choose(p: Place) { place = p; prefs.edit().putString("place", p.name).apply(); placeOpen = false; focus = "place-btn" }

    val dayCount = weather?.days?.size ?: 0
    // Rows, top to bottom: view + forecast, the room's controls, the apps. Left and right stay in
    // the row; up or down lands on the next row's first item.
    val controls = (if (room.lights.any { it.kind == Kind.Light }) listOf("all-lights") else emptyList()) +
        room.lights.map { "light-${it.id}" } + room.acs.map { "ac-${it.id}" }
    val rows = listOf(
        listOf("qs-btn", "place-btn") + (0 until dayCount).map { "day-$it" },
        controls.ifEmpty { listOf("add-tiles") },
        apps.indices.map { "app-$it" },
    ).filter { it.isNotEmpty() }
    val menu = Place.entries.map { "place-${it.name}" } + "place-random"
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
    LaunchedEffect(focus) { if (!focus.startsWith("app-") && rows.any { focus in it }) above = focus }

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
    fun holdable(id: String) = (id.startsWith("light-") && room.lights.any { "light-${it.id}" == id && it.kind == Kind.Light }) || room.acUnit(id) != null
    fun open(id: String) { acRow = 0; grabbed = id }

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
            focus == "add-tiles" -> { tv.refresh(); quickOpen = true; focus = "qs-pane"; settings.show(); settings.showTiles() }
            focus == "all-lights" -> tap(focus)
            // Opening the list lands on the view that is showing
            focus == "place-btn" -> if (placeOpen) placeOpen = false else { placeOpen = true; focus = "place-${place.name}" }
            focus == "place-random" -> choose(Place.entries.filter { it != place }.random())
            focus.startsWith("place-") -> choose(Place.valueOf(focus.removePrefix("place-")))
            focus.startsWith("app-") -> apps.getOrNull(focus.removePrefix("app-").toInt())?.let { app ->
                if (app.shortcut == Shortcut.AllApps) all.show()
                // The Settings card is Window's own settings; the TV's are a row away in the quick panel
                else if (app.shortcut == Shortcut.Settings) { tv.refresh(); quickOpen = true; focus = "qs-pane"; settings.show() }
                else app.intent?.let { runCatching { context.startActivity(it) }.onFailure { e -> Log.w("Window", "launch ${app.id}", e) } }
            }
        }
    }

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
            .onPreviewKeyEvent { e ->
                val isOk = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
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
                if ((e.key == Key.Back || e.key == Key.Escape) && e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent true
                // The screensaver's preview: any key ends it
                if (settings.preview) {
                    if (e.type == KeyEventType.KeyDown) settings.preview = false
                    return@onPreviewKeyEvent true
                }
                if (settings.editing != null) {
                    if (e.key == Key.Back || e.key == Key.Escape) { settings.back(); return@onPreviewKeyEvent true }
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
                        e.key == Key.Back || e.key == Key.Escape -> if (!settings.back()) focus = "qs-pane"
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
                        isOk -> when (focus) { "qs-pane" -> settings.show(); "qs-saver" -> settings.preview = true; else -> quickOk(tv, focus) }
                        e.key == Key.Back || e.key == Key.Escape -> { quickOpen = false; focus = "qs-btn" }
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
                    when (e.key) {
                        Key.DirectionLeft -> all.route(if (rtl) 1 else -1, 0)
                        Key.DirectionRight -> all.route(if (rtl) -1 else 1, 0)
                        Key.DirectionUp -> all.route(0, -1)
                        Key.DirectionDown -> all.route(0, 1)
                        Key.Back, Key.Escape -> if (all.back()) closeAll()
                        else -> return@onPreviewKeyEvent false
                    }
                    return@onPreviewKeyEvent true
                }
                // Press or hold on the room's controls: decided when OK comes back up
                if (isOk && (holdable(focus) || grabbed != null || hold != null)) {
                    if (e.type == KeyEventType.KeyDown) {
                        if (e.nativeKeyEvent.repeatCount == 0 && hold == null && !held) {
                            if (grabbed != null) held = true
                            else {
                                val id = focus
                                hold = scope.launch { delay(450); held = true; open(id) }
                            }
                        }
                    } else if (e.type == KeyEventType.KeyUp) {
                        val h = hold
                        hold = null
                        when {
                            h != null && h.isActive -> { h.cancel(); tap(focus) }
                            grabbed != null && held && h == null -> { grabbed = null; held = false }
                            else -> held = false
                        }
                    }
                    return@onPreviewKeyEvent true
                }
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (grabbed != null) {
                    when (e.key) {
                        Key.DirectionLeft -> adjust(Dir.Left)
                        Key.DirectionRight -> adjust(Dir.Right)
                        Key.DirectionUp -> adjust(Dir.Up)
                        Key.DirectionDown -> adjust(Dir.Down)
                        Key.Back, Key.Escape -> grabbed = null
                        else -> return@onPreviewKeyEvent false
                    }
                    return@onPreviewKeyEvent true
                }
                when (e.key) {
                    Key.DirectionLeft -> move(Dir.Left)
                    Key.DirectionRight -> move(Dir.Right)
                    Key.DirectionUp -> move(Dir.Up)
                    Key.DirectionDown -> move(Dir.Down)
                    // Once per press: OK held down repeats, and would switch the lights back and forth
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> if (e.nativeKeyEvent.repeatCount == 0) ok()
                    // Back closes what is open; from the apps it goes back up to where the remote came from
                    Key.Back, Key.Escape -> when {
                        placeOpen -> { placeOpen = false; focus = "place-btn" }
                        focus.startsWith("app-") -> focus = above
                    }
                    else -> return@onPreviewKeyEvent false
                }
                true
            },
    ) {
        CompositionLocalProvider(LocalHeld provides arrowHeld) {
        // The room photo and the camera never mirror
        if ("scene" !in Off.parts) HomeScene(room.lights.associate { it.id to it.level }, sky, night, if (Prefs.reduced) "rest" else viewFor(focus), place, dim = appIndex != null || all.open || quickOpen,
            // Along the shelf the room drifts with the focused app, so moving between apps isn't static
            // (in Hebrew the shelf starts at the right, so it drifts the other way)
            pan = if (!Prefs.drift || Prefs.reduced) 0f else appIndex?.let { if (apps.size > 1) (it / (apps.size - 1f) - .5f) * (if (rtl) -1 else 1) else 0f } ?: 0f,
        )
        CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
        // Laid out in its own box, so the header and the rows take the language's direction
        Box(Modifier.fillMaxSize()) {
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
        if ("controls" !in Off.parts) ControlsRow(room, focus, grabbed, acRow, dim = appIndex != null)
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

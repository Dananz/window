package com.dananz.window

import android.content.Context
import android.service.dreams.DreamService
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

// Pane's screensaver: the room as Pane shows it, the camera moving in through the window, and as it
// arrives the room and the glass dissolve around the view, which never moves or changes. Then the
// view goes on alone, zoomed in further and drifting from side to side for as long as it runs, with
// the time and the weather in a corner that moves every few minutes: nothing stays lit in one place,
// for an OLED's sake.
// Chosen in Settings > Screensaver, or `settings put secure screensaver_components
// com.dananz.window/.Screensaver`.
class Screensaver : DreamService(), LifecycleOwner, SavedStateRegistryOwner {
    private val life = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = life
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry

    override fun onCreate() {
        super.onCreate()
        Prefs.load(this)
        Ha.load(this)
        saved.performRestore(null)
        life.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        setContentView(ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@Screensaver)
            setViewTreeSavedStateRegistryOwner(this@Screensaver)
            setContent { DesignCanvas { Dream(preview = false) } }
        })
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        life.handleLifecycleEvent(Lifecycle.Event.ON_START)
        life.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onDreamingStopped() {
        life.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        life.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        life.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDetachedFromWindow()
    }
}

// Where the time sits, in turn (start and end follow the language)
private val CORNERS = listOf(Alignment.BottomStart, Alignment.TopEnd, Alignment.BottomEnd, Alignment.TopStart)

// The view alone: this much larger than the screen, so it can drift this far either way
private const val ZOOM = 1.32f
private val DRIFT = (ZOOM - 1) * 1920 / 2

// The screensaver, here and as Pane settings' preview (which any key ends)
@Composable
fun Dream(preview: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val place = remember {
        val prefs = context.getSharedPreferences("home", Context.MODE_PRIVATE)
        runCatching { Place.valueOf(prefs.getString("place", "")!!) }.getOrDefault(Place.NewYork)
    }
    val room = remember { Room(scope) }
    var weather by remember { mutableStateOf<Weather?>(null) }
    var now by remember { mutableStateOf(LocalTime.now()) }
    var today by remember { mutableStateOf(LocalDate.now()) }
    // room: a moment in it; in: the camera to the window; bare: the room dissolving around the view;
    // alone: the view on its own layer, zooming in and drifting
    var step by remember { mutableStateOf("room") }
    var land by remember { mutableStateOf<ImageBitmap?>(null) }
    var corner by remember { mutableIntStateOf(0) }
    val words = remember { Animatable(0f) }
    // From where the room left the view (0) to zoomed in (1), over a long glide
    val zoom = remember { Animatable(0f) }
    val drift = remember { Animatable(0f) }

    LaunchedEffect(Unit) { room.sync(Prefs.tiles); runCatching { loadWeather() }.onSuccess { weather = it } }
    LaunchedEffect(Unit) { while (true) { now = LocalTime.now(); today = LocalDate.now(); delay(10_000) } }
    LaunchedEffect(Unit) {
        delay(1_200)
        step = "in"
        // The frame starts to dissolve while the camera is still arriving, so it goes through the glass
        delay(Motion.CAMERA - 900L)
        step = "bare"
        delay(Motion.SCENE.toLong())
        step = "alone"
        launch { zoom.animateTo(1f, glide(9_000)) }
        if (Prefs.saverShows != 2) words.animateTo(1f, glide(Motion.TEXT_IN * 2))
        while (true) {
            val every = Prefs.clockMoves
            delay((if (every == 0) 10 else every) * 60_000L)
            if (every == 0 || Prefs.saverShows == 2) continue
            words.animateTo(0f, glide(Motion.TEXT_OUT * 2))
            corner = (corner + 1) % CORNERS.size
            words.animateTo(1f, glide(Motion.TEXT_IN * 2))
        }
    }
    LaunchedEffect(step) {
        if (step != "alone") return@LaunchedEffect
        // Side to side and back, about a minute each quarter, for as long as it runs
        while (true) {
            drift.animateTo(1f, tween(60_000, easing = androidx.compose.animation.core.FastOutSlowInEasing))
            drift.animateTo(-1f, tween(120_000, easing = androidx.compose.animation.core.FastOutSlowInEasing))
            drift.animateTo(0f, tween(60_000, easing = androidx.compose.animation.core.FastOutSlowInEasing))
        }
    }

    val w = weather
    val night = w?.let { !it.sunUp } ?: (now.hour < 6 || now.hour >= 18)
    val sky = w?.sky ?: Sky.Clear
    val rtl = Lang.he
    CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides if (rtl) androidx.compose.ui.unit.LayoutDirection.Rtl else androidx.compose.ui.unit.LayoutDirection.Ltr) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (step != "alone") {
            CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr) {
                HomeScene(room.lights.associate { it.id to it.level }, sky, night, if (step == "room") "rest" else "dream", place, dim = false, bare = step == "bare", onLand = { land = it })
            }
        }
        // In rain there is no view of its own behind the fogged glass: the landscape is drawn plain
        val shown = land ?: ImageBitmap.imageResource(if (night) place.night else place.day)
        if (step == "alone") {
            val from = landRect("dream")
            Box(Modifier.fillMaxSize().drawBehind {
                val u = size.width / 1920
                val k = zoom.value
                val tw = 1920f * ZOOM
                val th = tw * 941f / 1672f
                val l = from.left + ((1920 - tw) / 2 - from.left) * k + drift.value * DRIFT * k
                val tp = from.top + ((1080 - th) / 2 - from.top) * k
                val ww = from.width + (tw - from.width) * k
                val hh = from.height + (th - from.height) * k
                drawImage(shown, dstOffset = IntOffset((l * u).toInt(), (tp * u).toInt()), dstSize = IntSize((ww * u).toInt(), (hh * u).toInt()))
            })
        }
        if (Prefs.saverShows != 2) {
            val c = CORNERS[corner]
            val start = c == Alignment.BottomStart || c == Alignment.TopStart
            val top = c == Alignment.TopStart || c == Alignment.TopEnd
            // A soft shade under the words so they read on a bright sky
            Box(
                Modifier.fillMaxSize().graphicsLayer { alpha = words.value * .9f }
                    .background(Brush.radialGradient(listOf(Color(0x99000000), Color.Transparent), radius = 1100f, center = androidx.compose.ui.geometry.Offset(if (start != rtl) 0f else 1920f, if (top) 0f else 1080f))),
            )
            Column(
                Modifier.align(c).padding(horizontal = 96.dp, vertical = 80.dp)
                    .graphicsLayer { alpha = words.value; translationY = (1 - words.value) * 10.dp.toPx() },
                horizontalAlignment = if (start) Alignment.Start else Alignment.End,
            ) {
                Text(now.format(TIME), color = Ink.text, fontFamily = Syne, style = Numbers, fontWeight = FontWeight.Bold, fontSize = 168.sp, lineHeight = 168.sp, letterSpacing = (-5).sp)
                Text(today.format(DATE), color = Ink.text, fontFamily = Golos, fontWeight = FontWeight.Medium, fontSize = 30.sp)
                if (w != null && Prefs.saverShows == 0) {
                    val d = w.days.firstOrNull()
                    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Glyphs.sky(w.sky, true), null, Modifier.size(32.dp), tint = Ink.text)
                        Text("${w.temp}°", color = Ink.text, fontFamily = Golos, fontWeight = FontWeight.Medium, fontSize = 30.sp)
                        Text(
                            skyWord(w.sky) + (d?.let { " · " + f("H {0}° L {1}°", it.high, it.low) } ?: ""),
                            color = Ink.text.copy(alpha = .75f), fontFamily = Golos, fontSize = 30.sp,
                        )
                    }
                }
            }
        }
    }
    }
}

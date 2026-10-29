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
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.asComposeColorFilter
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.layout
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.produceState
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearOutSlowInEasing
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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.ui.draw.clip
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.style.TextOverflow
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.fill.MusicNotes
import java.time.LocalTime

// Window's screensaver: the room as Window shows it, the camera moving in through the window, and as it
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
        Govee.load(this)
        Sensibo.load(this)
        Tuya.load(this)
        saved.performRestore(null)
        life.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        // Black until the first frame, never the theme's empty background
        window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK))
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

// The song's art, in design pixels, and its box's corners
const val ART = 168
const val ART_RADIUS = 16

// Where the time sits, in turn (start and end follow the language)
private val CORNERS = listOf(Alignment.BottomStart, Alignment.TopEnd, Alignment.BottomEnd, Alignment.TopStart)

// The view alone: this much larger than the screen, so it can drift this far either way
private const val ZOOM = 1.12f
// The camera's way to the window: slower than the home screen's moves, eased at both ends
private const val DREAM_CAMERA = 3_200
private val DRIFT = (ZOOM - 1) * 1920 / 2
// The time and the weather drift with the view, this much of its way: two layers moving at their own
// pace, so the words seem to float over the landscape (any further and they'd reach the screen's edge)
private const val PARALLAX = .6f
// Room around the words in their picture, for the shadows (design pixels)
private const val WORDS_PAD = 48
// A soft shadow under the time and the weather, so they read on a bright sky or a lit city
private val BIG_SHADOW = androidx.compose.ui.graphics.Shadow(Color(0x8C000000), androidx.compose.ui.geometry.Offset(0f, 4f), 24f)
private val SHADOW = androidx.compose.ui.graphics.Shadow(Color(0x99000000), androidx.compose.ui.geometry.Offset(0f, 2f), 10f)
// One sweep of the drift, from the middle to a side (a whole cycle is four)
private const val SWEEP = 60_000

// The zoom (0 to 1) and the drift (-1 to 1) by the time since the view was alone, so the panel's surface
// can work them out for the very frame it draws, on its own clock
private const val ZOOM_IN = 9_000f
private fun zoomAt(ms: Float) = GLIDE.transform((ms / ZOOM_IN).coerceIn(0f, 1f))
private fun driftAt(ms: Float): Float {
    val e = androidx.compose.animation.core.FastOutSlowInEasing
    // Side to side and back, for as long as it runs
    val t = ms % (SWEEP * 4f)
    return when {
        t < SWEEP -> e.transform(t / SWEEP)
        t < SWEEP * 3 -> 1 - 2 * e.transform((t - SWEEP) / (SWEEP * 2))
        else -> -1 + e.transform((t - SWEEP * 3) / SWEEP)
    }
}

// The screensaver, here and as Window settings' preview (which any key ends)
@Composable
fun Dream(preview: Boolean) {
    val context = LocalContext.current
    // The real screensaver draws the landscape on the panel's own surface, from the room to the end, so
    // nothing changes sharpness on the way. The settings' preview sits over the home screen and keeps
    // its own black, so it draws the landscape itself
    val land4k = LocalLand.current?.takeIf { !preview }
    val scope = rememberCoroutineScope()
    var place by remember {
        val prefs = context.getSharedPreferences("home", Context.MODE_PRIVATE)
        mutableStateOf(Seen.place ?: runCatching { Place.valueOf(prefs.getString("place", "")!!) }.getOrDefault(Place.NewYork))
    }
    // Shuffle: the place the view is leaving and its picture, under the new one as it fades in (swap 0 to 1)
    var leaving by remember { mutableStateOf<Pair<Place, android.graphics.Bitmap>?>(null) }
    val swap = remember { Animatable(1f) }
    val room = remember { Room(scope) }
    // Where the home screen was, so the screensaver starts from that very frame: the same picture, the
    // same camera and shade, no black and no fade in. It holds still until the view is on its own, so
    // nothing is made again while the camera moves
    val seen = remember { Seen.levels?.let { Triple(it, Seen.sky ?: Sky.Clear, Seen.night ?: false) } }
    val seenView = remember { Seen.view }
    val seenDim = remember { Seen.dim }
    var weather by remember { mutableStateOf(Seen.weather) }
    var now by remember { mutableStateOf(LocalTime.now()) }
    var today by remember { mutableStateOf(LocalDate.now()) }
    // room: a moment in it; in: the camera to the window; bare: the room dissolving around the view;
    // alone: the view on its own layer, zooming in and drifting
    var step by remember { mutableStateOf("room") }
    var land by remember { mutableStateOf<ImageBitmap?>(null) }
    var corner by remember { mutableIntStateOf(0) }
    val words = remember { Animatable(0f) }
    // When the view was alone (System.nanoTime), and the screen's latest frame time: the zoom and the drift
    // are worked out from the two
    var aloneAt by remember { mutableLongStateOf(0L) }
    val frameNs = remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) { room.sync(Prefs.tiles); runCatching { loadWeather() }.onSuccess { weather = it } }
    LaunchedEffect(Unit) { while (true) { now = LocalTime.now(); today = LocalDate.now(); delay(10_000) } }
    LaunchedEffect(Unit) {
        // A breath on the home screen's own frame (longer when there was none to start from)
        delay(if (seen != null) 600 else 1_200)
        step = "in"
        // The room starts to dissolve with the last stretch of the move still to go, so the camera goes
        // through the glass, and has finished by the time the view is on its own
        delay(DREAM_CAMERA - 1_500L)
        step = "bare"
        delay(1_600)
        step = "alone"
        aloneAt = System.nanoTime()
        delay(700)
        words.animateTo(1f, glide(Motion.TEXT_IN * 2))
        while (true) {
            val every = Prefs.clockMoves
            delay((if (every == 0) 10 else every) * 60_000L)
            // The song moves with the clock, even with the clock hidden: nothing stays lit in one place
            if (every == 0 || (Prefs.saverShows == 2 && Music.track == null)) continue
            words.animateTo(0f, glide(Motion.TEXT_OUT * 2))
            corner = (corner + 1) % CORNERS.size
            words.animateTo(1f, glide(Motion.TEXT_IN * 2))
        }
    }
    LaunchedEffect(step) {
        // On the panel's surface the surface keeps its own time, and the screen draws nothing while the view drifts
        if (step != "alone" || land4k != null) return@LaunchedEffect
        while (true) withFrameNanos { frameNs.longValue = it }
    }

    val w = weather
    val night = w?.let { !it.sunUp } ?: (now.hour < 6 || now.hour >= 18)
    val sky = w?.sky ?: Sky.Clear
    // Until the view is alone, the room shows what the home screen showed
    val held = seen != null && step != "alone"
    val rtl = Lang.he
    CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides if (rtl) androidx.compose.ui.unit.LayoutDirection.Rtl else androidx.compose.ui.unit.LayoutDirection.Ltr) {
    // The words' picture at the panel's resolution, and where its corner is (set below, read by the surface)
    var wordsPicture by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    Box(Modifier.fillMaxSize().then(if (land4k == null) Modifier.background(Color.Black) else Modifier)) {
        if (step != "alone") {
            CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr) {
                HomeScene(
                    if (held) seen!!.first else room.lights.associate { it.id to it.level }, if (held) seen!!.second else sky, if (held) seen!!.third else night,
                    if (step == "room") "rest" else "dream", place, dim = step == "room" && seenDim, bare = step == "bare", onLand = { land = it },
                    cameraMs = DREAM_CAMERA, home = false, at = seenView.takeIf { step == "room" }, sharp = land4k != null,
                )
            }
        }
        // In rain there is no view of its own behind the fogged glass: the landscape is drawn plain
        val shown = land ?: ImageBitmap.imageResource(if (night) place.night else place.day)
        // The sharp copy for the zoomed-in view, read while the camera is still on its way in
        val context = LocalContext.current
        val viewNight = if (held) seen!!.third else night
        val hdBitmap by produceState<android.graphics.Bitmap?>(null, place, viewNight) {
            value = withContext(Dispatchers.IO) { decodeHd(context, if (viewNight) place.nightHd else place.dayHd) }
        }
        val hd = hdBitmap?.asImageBitmap()
        // Shuffle in the screensaver: every so often another place fades in, the zoom and the drift going on
        LaunchedEffect(step, Prefs.shuffle) {
            val every = Prefs.shuffle
            if (step != "alone" || every <= 0) return@LaunchedEffect
            while (true) {
                delay(every * 60_000L)
                val old = hdBitmap ?: continue
                val next = Place.entries.filter { it != place }.random()
                // Read before the swap, so the new picture is there from its first frame
                withContext(Dispatchers.IO) { decodeHd(context, if (viewNight) next.nightHd else next.dayHd) } ?: continue
                leaving = place to old
                swap.snapTo(0f)
                place = next
                swap.animateTo(1f, glide(2_400))
                leaving = null
            }
        }
        val sharpIn = remember { Animatable(0f) }
        LaunchedEffect(step, hd) { if (step == "alone" && hd != null) sharpIn.animateTo(1f, glide(1_200)) }
        if (step == "alone") {
            val from = landRect("dream")
            // Where the view is now, in pixels: read in layout and drawing, so a frame moves it without recomposing
            fun where(u: Float, ns: Long = frameNs.longValue, p: Place = place): android.graphics.RectF {
                val ms = if (aloneAt == 0L) 0f else ((ns - aloneAt) / 1e6f).coerceAtLeast(0f)
                val k = zoomAt(ms)
                val drift = driftAt(ms)
                val tw = 1920f * ZOOM
                val th = tw * 941f / 1672f
                val l = from.left + ((1920 - tw) / 2 - from.left) * k + drift * DRIFT * k
                val tp = from.top + ((1080 - th) * p.top - from.top) * k
                val ww = from.width + (tw - from.width) * k
                val hh = from.height + (th - from.height) * k
                return android.graphics.RectF(l * u, tp * u, (l + ww) * u, (tp + hh) * u)
            }
            fun frame(u: Float): Pair<IntOffset, IntSize> = where(u).let { IntOffset(it.left.toInt(), it.top.toInt()) to IntSize(it.width().toInt(), it.height().toInt()) }
            // Graded and clouded as the home screen's copy is; in rain that copy is plain, so this is too
            val graded = land != null
            val sharp = hd
            val me = remember { Any() }
            if (land4k != null) {
                DisposableEffect(land4k) { land4k.claim(me); onDispose { land4k.release(me) } }
                // On the panel's surface, where the room's scene left it: the same picture, sharp throughout,
                // placed by the surface's own thread for each frame it draws
                val bitmap = hdBitmap
                val filter = if (graded) landGrade(sky) else null
                val cloud = if (graded) cloudOf(sky) else 0f
                val picture = wordsPicture?.takeIf { Prefs.saverShows != 2 }
                SideEffect {
                    if (bitmap != null) land4k.animate(me) { ns ->
                        // The words where the screen would put them, in the panel's pixels, floating with the drift,
                        // and the shade under them
                        var placedShade: LandShade? = null
                        val placed = picture?.let { pic ->
                            val k = land4k.k
                            val c = CORNERS[corner]
                            val left = (c == Alignment.BottomStart || c == Alignment.TopStart) != rtl
                            val top = c == Alignment.TopStart || c == Alignment.TopEnd
                            val pad = WORDS_PAD * k
                            val ms = if (aloneAt == 0L) 0f else ((ns - aloneAt) / 1e6f).coerceAtLeast(0f)
                            val a = words.value
                            placedShade = LandShade(((if (left) 0f else 1920f) + driftAt(ms) * DRIFT * zoomAt(ms) * PARALLAX) * k, (if (top) 0f else 1080f) * k, 1100f * k, a * .9f)
                            LandWords(
                                pic,
                                (if (left) 96 * k - pad else land4k.view.panelWidth - 96 * k - pic.width + pad) + driftAt(ms) * DRIFT * zoomAt(ms) * PARALLAX * k,
                                (if (top) 80 * k - pad else land4k.view.panelHeight - 80 * k - pic.height + pad) + (1 - a) * 10 * k,
                                a,
                            )
                        }
                        val going = leaving
                        val layers = if (going == null) listOf(LandLayer(bitmap, where(land4k.k, ns), 1f, filter, cloud, 0f))
                            else listOf(LandLayer(going.second, where(land4k.k, ns, going.first), 1f, filter, cloud, 0f), LandLayer(bitmap, where(land4k.k, ns), swap.value, filter, cloud, 0f))
                        LandFrame(null, layers, placed, placedShade)
                    }
                }
            }
            if (land4k == null) Box(Modifier.fillMaxSize().drawBehind {
                val (at, size) = frame(size.width / 1920)
                // The picture the room left drawn first, the sharp copy fading in over it: no frame jumps
                drawImage(shown, dstOffset = at, dstSize = size, filterQuality = FilterQuality.High)
                val a = sharpIn.value
                if (sharp != null && a > 0f) {
                    drawImage(sharp, dstOffset = at, dstSize = size, alpha = a, colorFilter = if (graded) landGrade(sky)?.asComposeColorFilter() else null, filterQuality = FilterQuality.High)
                    val cloud = if (graded) cloudOf(sky) else 0f
                    if (cloud > 0f) drawRect(
                        Brush.verticalGradient(0f to Color(0x8C96A0AF), .45f to Color(0x40788291), .7f to Color.Transparent, startY = at.y.toFloat(), endY = at.y + size.height.toFloat()),
                        topLeft = androidx.compose.ui.geometry.Offset(at.x.toFloat(), at.y.toFloat()), size = androidx.compose.ui.geometry.Size(size.width.toFloat(), size.height.toFloat()), alpha = cloud * a,
                    )
                }
            })
        }
        val c = CORNERS[corner]
        val start = c == Alignment.BottomStart || c == Alignment.TopStart
        val top = c == Alignment.TopStart || c == Alignment.TopEnd
        // Read in drawing and in the layer, so the drift moves the words without recomposing
        fun float(): Float {
            val ms = if (aloneAt == 0L) 0f else ((frameNs.longValue - aloneAt) / 1e6f).coerceAtLeast(0f)
            return driftAt(ms) * DRIFT * zoomAt(ms) * PARALLAX
        }
        // The song playing, in the corner across from the clock; kept a moment as it fades out
        // Off in Window settings: nothing is read at all
        DisposableEffect(Prefs.saverMusic) {
            val on = Prefs.saverMusic
            if (on) Music.start(context)
            onDispose { if (on) Music.stop() }
        }
        val song = Music.track.takeIf { Prefs.saverMusic }
        var lastSong by remember { mutableStateOf<Track?>(null) }
        val songIn = remember { Animatable(0f) }
        LaunchedEffect(song) { if (song != null) lastSong = song }
        LaunchedEffect(song != null) {
            if (song != null) songIn.animateTo(1f, glide(Motion.TEXT_IN * 2))
            else { songIn.animateTo(0f, glide(Motion.TEXT_OUT * 2)); lastSong = null }
        }
        val clock = Prefs.saverShows != 2
        // A soft shade under each corner's words so they read on a bright sky
        if (land4k == null) Box(
            Modifier.fillMaxSize().drawBehind {
                val u = size.width / 1920
                fun shade(left: Boolean, up: Boolean, a: Float) {
                    if (a > 0f) drawRect(Brush.radialGradient(listOf(Color(0x99000000), Color.Transparent), radius = 1100f * u,
                        center = androidx.compose.ui.geometry.Offset(((if (left) 0f else 1920f) + float()) * u, (if (up) 0f else 1080f) * u)), alpha = a * .9f)
                }
                if (clock) shade(start != rtl, top, words.value)
                shade(start == rtl, !top, words.value * songIn.value)
            },
        )
        lastSong?.let { t ->
            if (land4k == null) Box(
                Modifier.align(if (top) (if (start) Alignment.BottomEnd else Alignment.BottomStart) else (if (start) Alignment.TopEnd else Alignment.TopStart))
                    .padding(horizontal = 96.dp, vertical = 80.dp)
                    .graphicsLayer { alpha = words.value * songIn.value; translationY = (1 - words.value * songIn.value) * 10.dp.toPx(); translationX = float().dp.toPx() },
            ) { Song(t, start = !start, top = !top) }
            // The panel's 4K surface (Panel.SHARP, off for now) draws only the clock's picture: the song
            // would need its own picture made the way wordsPicture is
        }
        if (clock) {
            if (land4k == null) Column(
                Modifier.align(c).padding(horizontal = 96.dp, vertical = 80.dp)
                    .graphicsLayer { alpha = words.value; translationY = (1 - words.value) * 10.dp.toPx(); translationX = float().dp.toPx() },
                horizontalAlignment = if (start) Alignment.Start else Alignment.End,
            ) { Words(now, today, w) }
            else {
                // On the panel's surface: the words drawn at its resolution, into a picture made again when they
                // change (each minute, a new forecast, another corner), which the surface's thread places
                val layer = rememberGraphicsLayer()
                val measured = remember { IntArray(2) }
                val scale = land4k.k
                Box(
                    Modifier.layout { m, cs ->
                        val p = m.measure(Constraints())
                        measured[0] = p.width; measured[1] = p.height
                        layout(0, 0) { p.place(0, 0) }
                    }.drawWithContent { layer.record(size = IntSize(measured[0], measured[1])) { this@drawWithContent.drawContent() } },
                ) {
                    CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density * scale, 1f)) {
                        Column(Modifier.padding(WORDS_PAD.dp), horizontalAlignment = if (start) Alignment.Start else Alignment.End) { Words(now, today, w) }
                    }
                }
                val text = now.format(TIME) + today + w + start + Prefs.saverShows + Lang.he
                LaunchedEffect(text) {
                    // Drawn into the layer on the next frame, then read back
                    repeat(2) { withFrameNanos { } }
                    if (measured[0] > 0) wordsPicture = layer.toImageBitmap().asAndroidBitmap()
                }
            }
        }
    }
    }
}

// The time, the date and the weather, as the screensaver shows them
@Composable
private fun Words(now: LocalTime, today: LocalDate, w: Weather?) {
    Text(now.format(TIME), color = Ink.text, fontFamily = Syne, style = Numbers.copy(shadow = BIG_SHADOW), fontWeight = FontWeight.Bold, fontSize = 168.sp, lineHeight = 168.sp, letterSpacing = (-5).sp)
    Text(today.format(DATE), color = Ink.text, fontFamily = Golos, fontWeight = FontWeight.Medium, fontSize = 30.sp, style = TextStyle(shadow = SHADOW))
    if (w != null && Prefs.saverShows == 0) {
        val d = w.days.firstOrNull()
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Glyphs.sky(w.sky, true), null, Modifier.size(32.dp), tint = Ink.text)
            Text("${w.temp}°", color = Ink.text, fontFamily = Golos, fontWeight = FontWeight.Medium, fontSize = 30.sp, style = TextStyle(shadow = SHADOW))
            Text(
                skyWord(w.sky) + (d?.let { " · " + f("H {0}° L {1}°", it.high, it.low) } ?: ""),
                color = Ink.text.copy(alpha = .75f), fontFamily = Golos, fontSize = 30.sp, style = TextStyle(shadow = SHADOW),
            )
        }
    }
}

// The song any app is playing: the art at the corner's edge, the title and the artist beside it. A new
// song comes in as the last one goes: inside the cover's box the old cover slides out to one side as the
// new one slides in from the other (the box itself stays put), and the words fade out, then in
@Composable
private fun Song(track: Track, start: Boolean, top: Boolean) {
    val reduced = Prefs.reduced
    val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    // The new cover comes from the side the reading goes to: from the right in English, the left in Hebrew
    val from = if (rtl) -1 else 1
    val art: @Composable () -> Unit = {
        Box(Modifier.size(ART.dp).clip(RoundedCornerShape(ART_RADIUS.dp))) {
            AnimatedContent(
                targetState = track,
                contentKey = { it.key },
                transitionSpec = {
                    if (reduced) fadeIn(glide(600)) togetherWith fadeOut(glide(600))
                    else slideInHorizontally(glide(SONG_SLIDE)) { it * from } togetherWith slideOutHorizontally(glide(SONG_SLIDE)) { -it * from }
                },
                label = "cover",
            ) { t ->
                // The art comes after the words for some players: it fades in where the note was
                Crossfade(t.art, animationSpec = glide(700), label = "art") { a ->
                    if (a != null) Image(a, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    else Box(Modifier.fillMaxSize().background(Color(0x26FFFFFF)), contentAlignment = Alignment.Center) {
                        Icon(PhosphorIcons.Fill.MusicNotes, null, Modifier.size(56.dp), tint = Ink.text.copy(alpha = .8f))
                    }
                }
            }
            // On the box, not on the cover: they stay where they are as the covers slide under them
            Playing(Modifier.align(Alignment.BottomStart).padding(14.dp), still = reduced, dark = track.art != null && (if (rtl) track.lightRight else track.lightLeft))
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        if (start) art()
        AnimatedContent(
            targetState = track,
            contentKey = { it.key },
            contentAlignment = if (start) Alignment.CenterStart else Alignment.CenterEnd,
            transitionSpec = { fadeIn(glide(SONG_FADE, delay = SONG_FADE)) togetherWith fadeOut(glide(SONG_FADE)) using SizeTransform(clip = false) },
            label = "words",
        ) { t ->
            Column(Modifier.widthIn(max = 640.dp), horizontalAlignment = if (start) Alignment.Start else Alignment.End) {
                val align = if (start) TextAlign.Start else TextAlign.End
                Text(
                    t.title, color = Ink.text, fontFamily = Golos, fontWeight = FontWeight.Medium,
                    fontSize = 36.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = align, style = TextStyle(shadow = SHADOW),
                )
                if (t.artist.isNotEmpty()) Text(
                    t.artist, Modifier.padding(top = 4.dp), color = Ink.text.copy(alpha = .75f), fontFamily = Golos,
                    fontSize = 28.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = align, style = TextStyle(shadow = SHADOW),
                )
            }
        }
        if (!start) art()
    }
}
// The covers' slide, and each half of the words' fade (out, then in)
private const val SONG_SLIDE = 900
private const val SONG_FADE = 380

// The sound bars on the cover: four bars, bass to treble, rising and falling with what the TV plays (a
// dot each in silence), black on a light cover and white on a dark one, with a soft shadow. When the TV
// won't give its sound, they move by themselves while the song plays; paused, they sink to dots and stay
// still. Drawn from the frame time, without recomposing
private const val BAR = 6f
private const val BAR_GAP = 5f
private const val BAR_HIGH = 30f
// When the bars move by themselves: each bar's two waves (Hz) and where they start, so no two move together
private val BAR_WAVES = listOf(floatArrayOf(.55f, 1.1f, 0f), floatArrayOf(.8f, .45f, 1.9f), floatArrayOf(.4f, .95f, 4.2f), floatArrayOf(1f, .6f, 2.6f))

@Composable
private fun Playing(modifier: Modifier, still: Boolean, dark: Boolean) {
    val frame = remember { mutableLongStateOf(0L) }
    val levels = remember { FloatArray(4) }
    val shown = remember { androidx.compose.runtime.mutableFloatStateOf(1f) }
    LaunchedEffect(still) {
        var last = 0L
        while (true) withFrameNanos { ns ->
            val dt = if (last == 0L) 0f else ((ns - last) / 1e9f).coerceAtMost(.1f)
            last = ns
            val sec = ns / 1e9f
            for (i in 0 until 4) {
                val target = when {
                    !Music.playing -> 0f
                    still -> listOf(.55f, .9f, .4f, .7f)[i]
                    Music.heard -> Music.bands[i]
                    else -> BAR_WAVES[i].let { (a, b, p) -> ((kotlin.math.sin((sec * a + p) * 2 * Math.PI) + kotlin.math.sin((sec * b + p * .7f) * 2 * Math.PI)) / 4 + .5f).toFloat() }
                }
                // Gently: up a little faster than down, never a jump
                val k = 1 - kotlin.math.exp(-dt * (if (target > levels[i]) 9f else 4.5f))
                levels[i] += (target - levels[i]) * k
            }
            val silent = !Music.playing || (Music.heard && !still && (0 until 4).all { Music.bands[it] < .03f })
            Music.quiet(ns, silent)
            shown.floatValue = Music.barsShown(ns)
            frame.longValue = ns
        }
    }
    val density = LocalDensity.current.density
    // White (the words' own, so the bars belong with the title beside them) or black, eased from one to the
    // other as a cover of the other kind slides in
    val ink = remember { Animatable(if (dark) 1f else 0f) }
    LaunchedEffect(dark) { ink.animateTo(if (dark) 1f else 0f, glide(SONG_SLIDE)) }
    val paint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG) }
    Box(modifier.size((BAR * 4 + BAR_GAP * 3).dp, BAR_HIGH.dp).graphicsLayer { alpha = shown.floatValue }.drawBehind {
        frame.longValue
        val u = density
        val d = ink.value
        paint.color = androidx.compose.ui.graphics.lerp(Ink.text, Color(0xFF141414), d).toArgb()
        paint.setShadowLayer(6f * u, 0f, 1f * u, androidx.compose.ui.graphics.lerp(Color(0x59000000), Color(0x26000000), d).toArgb())
        val canvas = drawContext.canvas.nativeCanvas
        for (i in 0 until 4) {
            // Never quite full: the tallest a bar gets is most of its height
            val h = (BAR + (BAR_HIGH - BAR) * .85f * levels[i]) * u
            val x = i * (BAR + BAR_GAP) * u
            val y = (size.height - h) / 2
            canvas.drawRoundRect(x, y, x + BAR * u, y + h, BAR * u / 2, BAR * u / 2, paint)
        }
    })
}

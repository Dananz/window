package com.dananz.window

import androidx.compose.runtime.SideEffect
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.withFrameNanos
import android.graphics.Shader
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector3D
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.animateValueAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt
import androidx.compose.ui.unit.IntOffset
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

// Each view twice: the home screen's (1672 wide), and a 3840 x 2160 copy for the panel's own surface, which fills
// the screen with it zoomed in (made again at 4K by GPT Image 2.5 on Higgsfield from the 1672 one, so the
// detail is drawn, not enlarged; the small copy is the big one scaled down, so the two line up; WebP in
// res/raw, as AVIF failed on the Sony's decoder)
// `top`: what the screensaver keeps of the zoomed-in view, from its top (0) to its bottom (1); .5 crops
// both evenly. Dubai keeps its top, or the Burj Khalifa's spire is cut off
enum class Place(val label: String, val day: Int, val night: Int, val dayHd: Int, val nightHd: Int, val top: Float = .5f) {
    NewYork("New York", R.drawable.land_ny_day, R.drawable.land_ny_night, R.raw.land_ny_day_hd, R.raw.land_ny_night_hd),
    Dubai("Dubai", R.drawable.land_dubai_day, R.drawable.land_dubai_night, R.raw.land_dubai_day_hd, R.raw.land_dubai_night_hd, top = 0f),
    LosAngeles("Los Angeles", R.drawable.land_la_day, R.drawable.land_la_night, R.raw.land_la_day_hd, R.raw.land_la_night_hd),
    Village("Alpine village", R.drawable.land_village_day, R.drawable.land_village_night, R.raw.land_village_day_hd, R.raw.land_village_night_hd),
    TelAviv("Tel Aviv", R.drawable.land_telaviv_day, R.drawable.land_telaviv_night, R.raw.land_telaviv_day_hd, R.raw.land_telaviv_night_hd),
    Amalfi("Amalfi coast", R.drawable.land_amalfi_day, R.drawable.land_amalfi_night, R.raw.land_amalfi_day_hd, R.raw.land_amalfi_night_hd),
    ViceCity("Vice City", R.drawable.land_vice_day, R.drawable.land_vice_night, R.raw.land_vice_day_hd, R.raw.land_vice_night_hd),
}

// Where the camera moves for what has focus: zoom, and the point (fractions) it centres
data class View(val s: Float, val x: Float, val y: Float)
val VIEWS = mapOf(
    "rest" to View(1.03f, .5f, .5f),
    "window" to View(1.32f, .5f, .4f),
    "living" to View(1.25f, .5f, .45f),
    "kitchen" to View(1.65f, .13f, .42f),
    "tv" to View(1.55f, .86f, .5f),
    // In a little closer than at rest, so the room has room to drift along the apps shelf
    "apps" to View(1.08f, .5f, .5f),
    // The screensaver: into the window until the glass fills the screen. Centred above the window's
    // middle, where the view behind it (which moves less than the room) sits centred on the screen
    "dream" to View(2.2f, (Win.L + Win.R) / 2, .36f),
)

fun viewFor(id: String) = when {
    id.startsWith("day-") || id.startsWith("place-") -> "window"
    id == "light-ceiling" || id == "all-lights" -> "living"
    id == "light-kitchen" -> "kitchen"
    id == "light-miznon" || id.startsWith("ac") -> "tv"
    id.startsWith("app-") -> "apps"
    else -> "rest"
}

// Measured on the room photo (fractions of the 1920x1080 frame): the window opening
object Win { const val L = .204f; const val T = .164f; const val R = .797f; const val B = .64f }
const val LAND_W = 1500f
const val LAND_H = LAND_W * 941f / 1672f

// Each real light lights its own part of the room at night: an ellipse (centre and radii in
// fractions) that its share of the lit photo shows through
private data class Pool(val cx: Float, val cy: Float, val rx: Float, val ry: Float, val solid: Float)
private val POOLS = mapOf(
    "ceiling" to Pool(.5f, .08f, .52f, .8f, .3f),
    "kitchen" to Pool(.08f, .36f, .2f, .45f, .35f),
    "miznon" to Pool(.9f, .76f, .16f, .24f, .3f),
)

// The camera as the prototype's CSS transform: translate(x, y) scale(s), each part eased on its
// own from where it is, so a move between two views slides the room at the same pace end to end
internal data class Move(val x: Float, val y: Float, val s: Float)
private val MOVE = TwoWayConverter<Move, AnimationVector3D>({ AnimationVector3D(it.x, it.y, it.s) }, { Move(it.v1, it.v2, it.v3) })

// A camera glide as a function of time (System.nanoTime), so the panel's surface can work out where the
// camera is for the very frame it draws, on its own clock; a new aim starts from wherever it is then
private class Glide(start: Move) {
    private data class Leg(val from: Move, val to: Move, val at: Long, val ms: Float)
    @Volatile private var leg = Leg(start, start, 0L, 1f)
    fun value(ns: Long): Move {
        val l = leg
        val e = GLIDE.transform(((ns - l.at) / 1e6f / l.ms).coerceIn(0f, 1f))
        return Move(l.from.x + (l.to.x - l.from.x) * e, l.from.y + (l.to.y - l.from.y) * e, l.from.s + (l.to.s - l.from.s) * e)
    }
    fun moving(ns: Long) = leg.let { (ns - it.at) / 1e6f < it.ms }
    fun aim(target: Move, ns: Long, ms: Int) { if (target != leg.to) leg = Leg(value(ns), target, ns, ms.toFloat()) }
}

// Where a view puts the room on screen, kept from showing past its edges
internal fun frameOf(v: View) = Move(
    min(0f, max(1920 - 1920 * v.s, 960 - v.s * v.x * 1920)),
    min(0f, max(1080 - 1080 * v.s, 540 - v.s * v.y * 1080)),
    v.s,
)

// Depth: the landscape follows the camera only partly, so it seems far behind the glass
internal fun depthOf(v: View) = Move((v.x - .5f) * 1920 * .3f, (v.y - .5f) * 1080 * .3f, 1 / (1 + (v.s - 1) * .55f))

private val WIN_CX = (Win.L + Win.R) / 2 * 1920
private val WIN_CY = (Win.T + Win.B) / 2 * 1080

// Where the landscape sits on the 1920x1080 screen for a view, once the camera has arrived
fun landRect(view: String): androidx.compose.ui.geometry.Rect {
    val v = VIEWS.getValue(view)
    val c = frameOf(v); val d = depthOf(v)
    val w = LAND_W * d.s * c.s; val h = LAND_H * d.s * c.s
    val cx = c.x + c.s * (WIN_CX + d.x); val cy = c.y + c.s * (WIN_CY + d.y)
    return androidx.compose.ui.geometry.Rect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
}

// A baked scene: the room, with the window open where the landscape shows through, and the
// landscape on its own so it can move at its own depth. In rain the fogged glass is part of the
// room and there is no landscape to draw behind it.
// `outside` is the look without the lights: two bakes with the same one show the same landscape
// hd: the 4K copy of the landscape, for the panel's own surface (null in rain, or on a 1080p screen)
private class Baked(val room: ImageBitmap, val land: ImageBitmap?, val outside: Look, val hd: Bitmap? = null)

// saturate, brightness and contrast, as the CSS filters the prototype used
private fun grade(sat: Float, bright: Float, contrast: Float): ColorMatrixColorFilter {
    val m = android.graphics.ColorMatrix().apply { setSaturation(sat) }
    val t = (1 - contrast) * 128f
    m.postConcat(android.graphics.ColorMatrix(floatArrayOf(contrast, 0f, 0f, 0f, t, 0f, contrast, 0f, 0f, t, 0f, 0f, contrast, 0f, t, 0f, 0f, 0f, 1f, 0f)))
    m.postConcat(android.graphics.ColorMatrix(floatArrayOf(bright, 0f, 0f, 0f, 0f, 0f, bright, 0f, 0f, 0f, 0f, 0f, bright, 0f, 0f, 0f, 0f, 0f, 1f, 0f)))
    return ColorMatrixColorFilter(m)
}
internal fun landGrade(sky: Sky) = LAND_GRADE[sky]
internal fun cloudOf(sky: Sky) = when (sky) { Sky.Clear -> 0f; Sky.Cloudy -> .5f; Sky.Rain -> .75f }
private val LAND_GRADE = mapOf(Sky.Clear to null, Sky.Cloudy to grade(.55f, .82f, .9f), Sky.Rain to grade(.35f, .58f, .8f))
private val ROOM_GRADE = mapOf(Sky.Clear to null, Sky.Cloudy to grade(.85f, .88f, 1f), Sky.Rain to grade(.75f, .74f, 1f))

private data class Look(val place: Place, val night: Boolean, val sky: Sky, val levels: Map<String, Int>)

// The last picture made, so a new HomeScene for the same look (the screensaver's) needn't make it again
private var lastBaked: Pair<Look, Baked>? = null

// What the home screen last showed, for the screensaver to start from
object Seen {
    var levels: Map<String, Int>? = null
    var sky: Sky? = null
    var night: Boolean? = null
    var view: View? = null
    var dim = false
    var place: Place? = null
    var weather: Weather? = null
}

// The living room, baked: the landscape behind the window, rain on the glass, and the room in
// front with each light's pool, all drawn into one picture whenever one of them changes. This
// TV's GPU draws about a third of a gigapixel a second, so one opaque picture a frame is what
// keeps the camera moves and the apps shelf smooth. The camera moves that picture as a whole.
@Composable
// bare: the room and its glass fade away and leave the view outside where it is (the screensaver goes
// through the window). onLand: the landscape as drawn, for whoever takes over from the room
fun HomeScene(
    levels: Map<String, Int>, sky: Sky, night: Boolean, view: String, place: Place, dim: Boolean, pan: Float = 0f, bare: Boolean = false,
    onLand: ((ImageBitmap?) -> Unit)? = null, cameraMs: Int = Motion.CAMERA, home: Boolean = true, at: View? = null,
    sharp: Boolean = true,
) {
    val context = LocalContext.current
    // The panel's own surface for the landscape, when there is one and this scene is the one in front
    val land4k = LocalLand.current?.takeIf { sharp }
    val me = remember { Any() }
    if (land4k != null) DisposableEffect(land4k) { land4k.claim(me); onDispose { land4k.release(me) } }
    val v = VIEWS[view] ?: VIEWS.getValue("rest")
    // A new target mid-move carries the speed the camera already has, so quick presses bend the move
    val view = at ?: View(v.s, v.x + pan * PAN, v.y)
    val camAim = frameOf(view)
    val depthAim = depthOf(view)
    val cam = remember { Glide(camAim) }
    val depth = remember { Glide(depthAim) }
    // The latest frame's time, while the camera moves: what the screen draws by
    val frameNs = remember { mutableLongStateOf(System.nanoTime()) }
    LaunchedEffect(camAim, depthAim) {
        withFrameNanos { ns -> cam.aim(camAim, ns, cameraMs); depth.aim(depthAim, ns, cameraMs); frameNs.longValue = ns }
        while (cam.moving(frameNs.longValue) || depth.moving(frameNs.longValue)) withFrameNanos { frameNs.longValue = it }
    }
    // The home screen leaves what it shows for the screensaver, which starts from that very frame
    if (home) SideEffect { Seen.levels = levels; Seen.sky = sky; Seen.night = night; Seen.view = view; Seen.dim = dim; Seen.place = place }
    // Darkened behind the raised apps; a tint on the one image, not another layer over the screen
    val shade = animateFloatAsState(if (dim) .45f else 0f, glide(Motion.DIM), label = "shade")
    val gone = animateFloatAsState(if (bare) 1f else 0f, glide(Motion.SCENE), label = "bare")

    val look = Look(place, night, sky, POOLS.keys.associateWith { levels[it] ?: 0 })
    // The picture already made for this look, by this screen or the other, shows from the first frame
    val ready = lastBaked?.takeIf { it.first == look }?.second
    var shown by remember { mutableStateOf(ready) }
    var previous by remember { mutableStateOf<Baked?>(null) }
    val fade = remember { Animatable(1f) }
    var last by remember { mutableStateOf(if (ready != null) look else null) }
    LaunchedEffect(Unit) { if (ready != null) onLand?.invoke(ready.land) }
    LaunchedEffect(look) {
        if (last == look && shown != null) return@LaunchedEffect
        // Another day's sky: the words under the clock swap first, and the new sky is made after,
        // so its making can't stall their fade (and a quick run along the days makes none between)
        val l = last
        if (l != null && l.place == look.place && l.levels == look.levels) delay((Motion.TEXT_OUT + Motion.TEXT_IN).toLong())
        val baked = withContext(Dispatchers.Default) { bake(context, look, hd = land4k != null) }
        // Only the lights changed: their pools fade, quicker than a whole new scene
        val ms = when (last?.copy(levels = look.levels)) {
            null -> 300
            look -> if ((look.levels["miznon"] ?: 0) < (last?.levels?.get("miznon") ?: 0)) Motion.MIZNON_OFF else Motion.POOLS
            else -> Motion.SCENE
        }
        last = look
        // Hidden before it is shown, so no frame draws the new picture whole and then fades it in
        fade.snapTo(0f)
        previous = shown
        shown = baked
        lastBaked = look to baked
        onLand?.invoke(baked.land)
        fade.animateTo(1f, glide(ms))
        previous = null
    }

    // The dark bands at the top and the foot, where the text and the controls sit. Drawn from a
    // small picture of the gradient: this GPU samples a texture far faster than it shades a gradient
    val top = remember { bandBitmap(intArrayOf(0x8C07090C.toInt(), 0x0007090C), floatArrayOf(0f, 1f)) }
    val foot = remember { bandBitmap(intArrayOf(0x0007090C, 0xEB07090C.toInt(), 0xEB07090C.toInt()), floatArrayOf(0f, .58f, 1f)) }

    Box(
        Modifier.fillMaxSize().drawBehind {
            val u = size.width / 1920
            val c = cam.value(frameNs.longValue)
            val d = depth.value(frameNs.longValue)
            val tint = shade.value.takeIf { it > 0f }?.let { ColorFilter.tint(Color.Black.copy(alpha = it), BlendMode.SrcAtop) }
            // On the panel's surface: the landscape goes there, in the panel's pixels, and the window is left clear
            val sharpNow = land4k?.takeIf { it.owns(me) }
            // What goes on the surface, placed there by the surface's own thread for each frame it draws
            class Sharp(val bitmap: Bitmap, val alpha: Float, val sky: Sky)
            val sharps = ArrayList<Sharp>(2)
            fun sharpLayer(b: Baked, alpha: Float): Boolean {
                val h = b.hd ?: return false
                if (sharpNow == null) return false
                sharps += Sharp(h, alpha, b.outside.sky)
                return true
            }
            fun scene(b: Baked, alpha: Float, outside: Boolean = true) {
                withTransform({ translate(c.x * u, c.y * u); scale(c.s * u, c.s * u, Offset.Zero) }) {
                    b.land?.takeIf { outside && !sharpLayer(b, alpha) }?.let { land ->
                        fun view() = withTransform({ translate(WIN_CX + d.x, WIN_CY + d.y); scale(d.s, d.s, Offset.Zero) }) {
                            drawImage(land, dstOffset = IntOffset((-LAND_W / 2).toInt(), (-LAND_H / 2).toInt()), dstSize = IntSize(LAND_W.toInt(), LAND_H.toInt()), alpha = alpha, colorFilter = tint)
                        }
                        // Behind the window only: the room covers the rest. Going through the window, all of it
                        if (gone.value > 0f) view()
                        else clipRect(Win.L * 1920, Win.T * 1080, Win.R * 1920, Win.B * 1080) {
                            drawRect(Color(0xFF06080B), alpha = alpha)
                            view()
                        }
                    }
                    drawImage(b.room, dstSize = IntSize(1920, 1080), alpha = alpha * (1f - gone.value), colorFilter = tint)
                }
            }
            val before = previous
            before?.let { scene(it, 1f) }
            // Only the lights changed: the landscape is the same, so it is drawn once, whole, and only
            // the room fades. Fading a second copy in over the first dimmed it for a moment
            shown?.let { scene(it, fade.value, outside = before == null || before.outside != it.outside) }
            sharpNow?.let { l ->
                val k = l.k
                val open = gone.value > 0f
                val dim = shade.value
                l.animate(me) { ns ->
                    val c2 = cam.value(ns); val d2 = depth.value(ns)
                    // A little past the opening: the room covers it, and the room (drawn by the screen, on its
                    // own clock) can be a frame apart from the surface
                    val m = 24f
                    val clip = if (open) null else RectF(k * (c2.x + c2.s * (Win.L * 1920 - m)), k * (c2.y + c2.s * (Win.T * 1080 - m)), k * (c2.x + c2.s * (Win.R * 1920 + m)), k * (c2.y + c2.s * (Win.B * 1080 + m)))
                    val w = LAND_W * d2.s; val hh = LAND_H * d2.s
                    val x = WIN_CX + d2.x - w / 2; val y = WIN_CY + d2.y - hh / 2
                    val dst = RectF(k * (c2.x + c2.s * x), k * (c2.y + c2.s * y), k * (c2.x + c2.s * (x + w)), k * (c2.y + c2.s * (y + hh)))
                    LandFrame(clip, sharps.map { LandLayer(it.bitmap, dst, it.alpha, LAND_GRADE[it.sky], cloudOf(it.sky), dim) })
                }
            }
            val bands = 1f - gone.value
            drawImage(top, dstSize = IntSize(size.width.toInt(), (240 * u).roundToInt()), alpha = bands, colorFilter = tint)
            val fh = (454 * u).roundToInt()
            drawImage(foot, dstOffset = IntOffset(0, size.height.toInt() - fh), dstSize = IntSize(size.width.toInt(), fh), alpha = bands, colorFilter = tint)
        },
    ) {
        if (sky == Sky.Rain) {
            Box(
                Modifier.size(1920.dp, 1080.dp).graphicsLayer {
                    transformOrigin = TransformOrigin(0f, 0f)
                    val c = cam.value(frameNs.longValue)
                    translationX = c.x.dp.toPx()
                    translationY = c.y.dp.toPx()
                    scaleX = c.s; scaleY = c.s
                },
            ) {
                RainRunners(
                    Modifier
                        .offset((Win.L * 1920).dp, (Win.T * 1080).dp)
                        .size(((Win.R - Win.L) * 1920).dp, ((Win.B - Win.T) * 1080).dp),
                )
            }
        }
    }
}

// A vertical gradient, 4 pixels wide, kept on the GPU
private fun bandBitmap(colors: IntArray, stops: FloatArray): ImageBitmap {
    val b = Bitmap.createBitmap(4, 256, Bitmap.Config.ARGB_8888)
    Canvas(b).drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 0f, 256f, colors, stops, Shader.TileMode.CLAMP); isDither = true })
    return (b.copy(Bitmap.Config.HARDWARE, false) ?: b).asImageBitmap()
}

// How far the room drifts across the whole apps shelf, as a fraction of the frame
private const val PAN = .07f

// --- Baking ---

// Bake at the screen's own size: the photos are 1672 wide, so a bigger bake only costs GPU bandwidth
private const val K = 1f
private const val BW = (1920 * K).toInt()
private const val BH = (1080 * K).toInt()
private val cache = mutableMapOf<Int, Bitmap>()

private fun decode(context: Context, res: Int): Bitmap = synchronized(cache) {
    cache.getOrPut(res) { BitmapFactory.decodeResource(context.resources, res, BitmapFactory.Options().apply { inScaled = false }) }
}

private fun bake(context: Context, look: Look, hd: Boolean = false): Baked {
    val out = Bitmap.createBitmap(BW, BH, Bitmap.Config.ARGB_8888)
    val c = Canvas(out)
    val filtered = Paint(Paint.FILTER_BITMAP_FLAG)
    val win = RectF(Win.L * 1920 * K, Win.T * 1080 * K, Win.R * 1920 * K, Win.B * 1080 * K)
    val rain = look.sky == Sky.Rain

    // The landscape on its own, graded for the weather, with low cloud over it
    val land = Bitmap.createBitmap(LAND_W.toInt(), LAND_H.toInt(), Bitmap.Config.ARGB_8888)
    Canvas(land).run {
        val all = RectF(0f, 0f, LAND_W, LAND_H)
        drawBitmap(decode(context, if (look.night) look.place.night else look.place.day), null, all, Paint(filtered).apply { colorFilter = LAND_GRADE[look.sky] })
        val cloud = cloudOf(look.sky)
        if (cloud > 0f) drawRect(all, Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, LAND_H, intArrayOf(0x8C96A0AF.toInt(), 0x40788291, 0), floatArrayOf(0f, .45f, .7f), Shader.TileMode.CLAMP)
            alpha = (cloud * 255).toInt()
        })
    }

    if (rain) {
        // Rain fogs the glass: the view is drawn into the window at the resting depth, beaded and
        // blurred there, so the window is part of the room picture
        c.drawColor(android.graphics.Color.rgb(6, 8, 11))
        val ls = depthOf(VIEWS.getValue("rest")).s
        val at = RectF((WIN_CX - LAND_W * ls / 2) * K, (WIN_CY - LAND_H * ls / 2) * K, (WIN_CX + LAND_W * ls / 2) * K, (WIN_CY + LAND_H * ls / 2) * K)
        c.drawBitmap(land, null, at, filtered)
        wetGlass(out, win, look.night)
    }
    // Glass: a faint sheen across the window
    c.drawRect(win, Paint().apply {
        shader = LinearGradient(win.left, win.top, win.left + 1400 * K, win.top + 600 * K, intArrayOf(0x12FFFFFF, 0, 0, 0x0DFFFFFF, 0), floatArrayOf(0f, .3f, .62f, .8f, 1f), Shader.TileMode.CLAMP)
    })

    // The room: by day one photo; at night the lights-off room with each light's pool
    val frame = RectF(0f, 0f, BW.toFloat(), BH.toFloat())
    if (!look.night) {
        c.drawBitmap(decode(context, R.drawable.room_day), null, frame, Paint(filtered).apply { colorFilter = ROOM_GRADE[look.sky] })
    } else {
        c.drawBitmap(decode(context, R.drawable.room_dark), null, frame, filtered)
        val lit = decode(context, R.drawable.room_night)
        for ((id, pool) in POOLS) {
            val level = (look.levels[id] ?: 0) / 100f
            if (level <= 0f) continue
            // The lit photo through a soft ellipse, as bright as the light is
            val layer = c.saveLayer(frame, null)
            c.drawBitmap(lit, null, frame, Paint(filtered).apply { alpha = (level * 255).toInt() })
            val rx = BW * pool.rx
            c.save()
            c.scale(1f, BH * pool.ry / rx, BW * pool.cx, BH * pool.cy)
            c.drawRect(-BW * 4f, -BH * 4f, BW * 5f, BH * 5f, Paint().apply {
                shader = RadialGradient(BW * pool.cx, BH * pool.cy, rx, intArrayOf(android.graphics.Color.BLACK, android.graphics.Color.BLACK, 0), floatArrayOf(0f, pool.solid, 1f), Shader.TileMode.CLAMP)
                xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            })
            c.restore()
            c.restoreToCount(layer)
        }
    }
    // In rain every pixel is painted, so the GPU can draw it without blending
    if (rain) out.setHasAlpha(false) else land.setHasAlpha(false)
    // Kept in GPU memory, so they are uploaded once and not again on frames that draw them
    val sharp = if (hd && !rain) decodeHd(context, if (look.night) look.place.nightHd else look.place.dayHd) else null
    return Baked(gpu(out), if (rain) null.also { land.recycle() } else gpu(land), look.copy(levels = emptyMap()), sharp)
}

private fun gpu(b: Bitmap): ImageBitmap {
    val g = b.copy(Bitmap.Config.HARDWARE, false) ?: return b.asImageBitmap()
    b.recycle()
    return g.asImageBitmap()
}

// The wet pane, worked out once: the view behind fogs up, and every bead of water on it is a
// small lens showing the view sharp and turned over, with a glint above and a dark rim below
private fun wetGlass(out: Bitmap, win: RectF, night: Boolean) {
    val x0 = win.left.toInt(); val y0 = win.top.toInt()
    val w = win.width().toInt(); val h = win.height().toInt()
    val sharp = Bitmap.createBitmap(out, x0, y0, w, h)
    // Fog: down to a sixteenth and back up, smoothly, twice over
    var fog = Bitmap.createScaledBitmap(sharp, w / 16, h / 16, true)
    fog = Bitmap.createScaledBitmap(fog, w / 4, h / 4, true)
    fog = Bitmap.createScaledBitmap(fog, w, h, true)
    val src = IntArray(w * h).also { sharp.getPixels(it, 0, w, 0, 0, w, h) }
    val px = IntArray(w * h).also { fog.getPixels(it, 0, w, 0, 0, w, h) }
    val lift = if (night) 4 else 8
    for (y in 0 until h) {
        // Fog scatters a little light; mist settles low over the view
        val mist = ((y.toFloat() / h - .45f) / .55f).coerceIn(0f, 1f) * .22f
        val mr = if (night) 41 else 92; val mg = if (night) 46 else 102; val mb = if (night) 53 else 117
        for (x in 0 until w) {
            val i = y * w + x
            val p = px[i]
            var r = (p shr 16 and 255) + lift; var g = (p shr 8 and 255) + lift; var b = (p and 255) + lift
            r = (r + (mr - r) * mist).toInt(); g = (g + (mg - g) * mist).toInt(); b = (b + (mb - b) * mist).toInt()
            px[i] = (255 shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
        }
    }
    val rnd = Random(7)
    val area = w * h
    // Many small beads, fewer large ones
    repeat(area / 520) {
        val r = 1.5f + rnd.nextFloat().let { it * it * it } * 9f * K
        val bx = rnd.nextFloat() * w; val by = rnd.nextFloat() * h
        val ry = r * 1.1f
        for (yy in (by - ry).toInt()..(by + ry).toInt()) {
            if (yy < 0 || yy >= h) continue
            for (xx in (bx - r).toInt()..(bx + r).toInt()) {
                if (xx < 0 || xx >= w) continue
                val nx = (xx - bx) / r; val ny = (yy - by) / ry
                val l = nx * nx + ny * ny
                if (l >= 1f) continue
                // The lens: the view from the other side of the drop, sharp
                val sx = (xx - nx * r * 1.7f).toInt().coerceIn(0, w - 1)
                val sy = (yy - ny * ry * 1.7f - r * .3f).toInt().coerceIn(0, h - 1)
                val s = src[sy * w + sx]
                var cr = (s shr 16 and 255) * 1.14f + 10; var cg = (s shr 8 and 255) * 1.14f + 10; var cb = (s and 255) * 1.14f + 12
                val nz = sqrt(1 - l)
                // A glint up and left, a darker rim below
                val d = (-.45f * nx - .6f * ny + .66f * nz * 1.8f) / 2.1f
                val glint = if (d > 0) Math.pow(d.toDouble(), 18.0).toFloat() * 140f else 0f
                val rim = 1f - (ny.coerceAtLeast(0f) * (1 - nz)) * .35f
                val edge = (1f - l).coerceIn(0f, 1f).let { min(1f, it * 5f) }
                val o = px[yy * w + xx]
                cr = cr * rim + glint; cg = cg * rim + glint; cb = cb * rim + glint
                val fr = (o shr 16 and 255) + ((cr - (o shr 16 and 255)) * edge)
                val fg = (o shr 8 and 255) + ((cg - (o shr 8 and 255)) * edge)
                val fb = (o and 255) + ((cb - (o and 255)) * edge)
                px[yy * w + xx] = (255 shl 24) or (fr.toInt().coerceIn(0, 255) shl 16) or (fg.toInt().coerceIn(0, 255) shl 8) or fb.toInt().coerceIn(0, 255)
            }
        }
    }
    Canvas(out).drawBitmap(px, 0, w, x0.toFloat(), y0.toFloat(), w, h, false, null)
}


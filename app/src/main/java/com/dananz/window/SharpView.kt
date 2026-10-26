package com.dananz.window

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.HandlerThread
import android.view.Choreographer
import android.view.Display
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.staticCompositionLocalOf

// The landscape at the panel's own resolution. Window is drawn at 1920 x 1080 and the TV stretches it
// to 4K; a surface the size of the panel is shown as it is. This TV (MediaTek's "4K/2K UI coexist") only
// does that for a surface that fills the screen and never moves or scales, so the surface stays put
// behind everything and the landscape is drawn into it, a frame at a time, on a thread of its own: where
// the camera has it, clipped to the window, graded and dimmed as the home screen's copy is. The room and
// everything else are drawn over it, with the window left clear.

// One copy of the landscape as the surface draws it, in the panel's pixels
data class LandLayer(val bitmap: Bitmap, val dst: RectF, val alpha: Float, val filter: ColorFilter?, val cloud: Float, val shade: Float)
// The screensaver's time and weather, drawn at the panel's resolution so they are as sharp as the view:
// a picture of them, placed at whole pixels (in between, filtering would soften the letters)
data class LandWords(val bitmap: Bitmap, val x: Float, val y: Float, val alpha: Float)
// The soft dark shade under the screensaver's words: a circle of shadow, centre and radius in the panel's pixels
data class LandShade(val cx: Float, val cy: Float, val radius: Float, val alpha: Float)
// A whole frame: the layers, clipped to the window (null: all of the screen), and the words over them
data class LandFrame(val clip: RectF?, val layers: List<LandLayer>, val words: LandWords? = null, val shade: LandShade? = null)

// Whoever shows the landscape takes the surface; the last to take it draws, and gives it back when it goes
class Land(val view: SharpView) {
    private val owners = ArrayList<Any>()
    fun claim(owner: Any) = synchronized(owners) { owners.remove(owner); owners.add(owner) }
    fun release(owner: Any) = synchronized(owners) { owners.remove(owner) }
    fun owns(owner: Any) = synchronized(owners) { owners.lastOrNull() === owner }
    fun draw(owner: Any, frame: LandFrame) { if (owns(owner)) { view.motion = null; view.show(frame) } }
    // A frame worked out by the surface's own thread for the moment it shows (frame time in nanoseconds):
    // a slow drift then moves by the same step every frame, where handing it positions from the screen's
    // own frames, on another clock, gave it two in one frame and none in the next
    fun animate(owner: Any, frame: (Long) -> LandFrame) { if (owns(owner)) view.motion = frame }
    // Panel pixels to one of Window's 1920
    val k get() = view.panelWidth / 1920f
}

// Null where the panel is no sharper than Window (a 1080p screen, the emulator): everything draws as before
val LocalLand = staticCompositionLocalOf<Land?> { null }

object Panel {
    fun size(context: Context): Pair<Int, Int> {
        // A screensaver's context has no display of its own: ask for the TV's
        val mode = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)?.mode
        return (mode?.physicalWidth ?: 1920) to (mode?.physicalHeight ?: 1080)
    }
    // Off: on the Sony, once a 4K layer has been up, every app's video then plays in the top left quarter
    // of the screen, until the TV restarts
    const val SHARP = false
    fun is4k(context: Context) = SHARP && size(context).first >= 3000
}

// The sharp copies, kept on the GPU; two at most (the one shown and the one it fades from), as each is
// about 33 MB
private val hd = object : LinkedHashMap<Int, Bitmap>(4, .75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Bitmap>?) = size > 2
}
fun decodeHd(context: Context, res: Int): Bitmap? = synchronized(hd) {
    hd[res] ?: runCatching {
        val b = context.resources.openRawResource(res).use { BitmapFactory.decodeStream(it) }
        // Mipmapped: drawn at a fraction of its size (the window at rest, the camera's way in), the GPU
        // reads a smaller copy instead of skipping across all 8 million pixels, faster and without shimmer
        b.setHasMipMap(true)
        (b.copy(Bitmap.Config.HARDWARE, false) ?: b).also { if (it !== b) b.recycle() }
    }.onFailure { android.util.Log.w("Window", "sharp view", it) }.getOrNull()?.also { hd[res] = it }
}

class SharpView(context: Context) : SurfaceView(context), SurfaceHolder.Callback, Choreographer.FrameCallback {
    @Volatile private var target: LandFrame? = null
    @Volatile var motion: ((Long) -> LandFrame)? = null
    private var drawn: LandFrame? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val plain = Paint()
    // At the priority Android gives its own animation threads, so nothing else on the TV delays a frame
    private val thread = HandlerThread("sharp view", android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY).apply { start() }
    // The words' shade, drawn once small and stretched: this GPU samples a texture far faster than it
    // shades a gradient across the screen
    private val shadeDot: Bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).also {
        android.graphics.Canvas(it).drawPaint(Paint().apply {
            shader = android.graphics.RadialGradient(128f, 128f, 128f, 0x99000000.toInt(), 0, Shader.TileMode.CLAMP)
            isDither = true
        })
    }.let { it.copy(Bitmap.Config.HARDWARE, false) ?: it }
    private val handler = Handler(thread.looper)
    private var running = false
    val panelWidth: Int
    val panelHeight: Int

    init {
        holder.addCallback(this)
        val (w, h) = Panel.size(context)
        panelWidth = w; panelHeight = h
        holder.setFixedSize(w, h)
    }

    fun show(frame: LandFrame) { target = frame }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        Choreographer.getInstance().postFrameCallback(this)
        val f = motion?.invoke(frameTimeNanos) ?: target ?: return
        if (f == drawn || !holder.surface.isValid) return
        val c = runCatching { holder.lockHardwareCanvas() }.getOrNull() ?: return
        // This GPU fills about a third of a gigapixel a second, and a 4K frame is 8 million: what the first
        // (opaque) layer covers isn't painted black first
        val first = f.layers.firstOrNull()
        val area = f.clip ?: RectF(0f, 0f, panelWidth.toFloat(), panelHeight.toFloat())
        val covered = first != null && first.alpha >= 1f && first.dst.contains(area)
        if (!covered) c.drawColor(Color.BLACK)
        f.clip?.let { c.save(); c.clipRect(it); if (!covered) c.drawColor(0xFF06080B.toInt()) }
        for (l in f.layers) {
            paint.alpha = (l.alpha * 255).toInt(); paint.colorFilter = l.filter
            c.drawBitmap(l.bitmap, null, l.dst, paint)
            // Low cloud over the view, as the home screen's copy has it baked in
            if (l.cloud > 0f) {
                plain.shader = LinearGradient(0f, l.dst.top, 0f, l.dst.bottom, intArrayOf(0x8C96A0AF.toInt(), 0x40788291, 0), floatArrayOf(0f, .45f, .7f), Shader.TileMode.CLAMP)
                plain.alpha = (l.cloud * l.alpha * 255).toInt()
                c.drawRect(l.dst, plain)
                plain.shader = null
            }
            // Darkened behind the raised apps, as the room is
            if (l.shade > 0f) { plain.color = Color.BLACK; plain.alpha = (l.shade * l.alpha * 255).toInt(); c.drawRect(l.dst, plain) }
        }
        if (f.clip != null) c.restore()
        f.shade?.takeIf { it.alpha > 0f }?.let { sh ->
            paint.alpha = (sh.alpha * 255).toInt(); paint.colorFilter = null
            c.drawBitmap(shadeDot, null, RectF(sh.cx - sh.radius, sh.cy - sh.radius, sh.cx + sh.radius, sh.cy + sh.radius), paint)
        }
        f.words?.takeIf { it.alpha > 0f }?.let { w ->
            plain.alpha = (w.alpha * 255).toInt()
            c.drawBitmap(w.bitmap, kotlin.math.round(w.x), kotlin.math.round(w.y), plain)
        }
        holder.unlockCanvasAndPost(c)
        drawn = f
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        handler.post {
            // Each of the surface's buffers (33 MB at 4K) is made the first time it is drawn into: made now,
            // while the screen is still, not on the first frames of the camera's move
            repeat(3) { runCatching { holder.lockHardwareCanvas() }.getOrNull()?.let { c -> c.drawColor(Color.BLACK); holder.unlockCanvasAndPost(c) } }
            running = true; drawn = null; Choreographer.getInstance().postFrameCallback(this)
        }
    }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { handler.post { drawn = null } }
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // Waits for the thread, so it isn't drawing into a surface that is going away
        val done = java.util.concurrent.CountDownLatch(1)
        handler.post { running = false; Choreographer.getInstance().removeFrameCallback(this); done.countDown() }
        done.await(500, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        thread.quitSafely()
    }
}

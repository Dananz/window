package com.dananz.window

import android.content.Context
import android.content.Intent
import android.graphics.BitmapShader
import android.net.Uri
import android.util.Log
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.tv.material3.Text
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Bold
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.bold.ArrowsOutCardinal
import com.adamglin.phosphoricons.fill.Play
import com.adamglin.phosphoricons.fill.PushPin
import com.adamglin.phosphoricons.fill.PushPinSlash
import com.adamglin.phosphoricons.fill.Trash
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

private const val COLS = 5
private const val HOLD_MS = 500L
private const val TILE_W = 336f
private const val TILE_H = 189f
private const val GAP = 24f
private const val ROW = TILE_H + 44f + GAP
private const val HEAD = 64f
private const val MENU_W = 460f
private const val MENU_H = 500f
private val BACKDROP = Color(0xFF07090C)
private val DANGER = Color(0xFFFF6B5E)
private val DANGER_TEXT = Color(0xFFFF8F85)
private val ON_LIGHT = Color(0xFF111111)

data class AppMenu(val id: String, val at: Int)
data class Confirm(val id: String, val at: Int)
internal data class MenuItem(val key: String, val label: String, val icon: ImageVector)

// The All apps screen's state, kept the way Google TV keeps its own: favorites (the home shelf, in
// its order) above the rest of the apps on the TV, each app shown once. Holding OK on an app opens
// its menu: Open, add to or remove from favorites, Move, Uninstall. Move lifts the app and the
// arrows carry it; OK sets it down.
class AllAppsState(private val context: Context, private val scope: CoroutineScope) {
    var open by mutableStateOf(false)
        private set
    var apps by mutableStateOf(emptyMap<String, TvApp>())
        private set
    var favs by mutableStateOf(emptyList<String>())
        private set
    // Every app's place, favorites' slots included, so one taken off the shelf goes back to its own
    var order by mutableStateOf(emptyList<String>())
        private set
    var focus by mutableStateOf("fav-0")
        private set
    var menu by mutableStateOf<AppMenu?>(null)
        private set
    var moving by mutableStateOf<String?>(null)
        private set
    var confirm by mutableStateOf<Confirm?>(null)
        private set
    // Favorites changed since the screen opened: the home shelf is rebuilt on the way out
    var shelfChanged = false
    private var hold: Job? = null

    val rest: List<String> get() = order.filter { it !in favs }

    // The TV's apps, read again (on start and each return to Window); what is saved is laid over them
    fun load(all: List<TvApp>) {
        apps = all.associateBy { it.id }
        favs = Favorites.read(context).filter { it in apps }
        val saved = Favorites.order(context).filter { it in apps }
        order = saved + all.map { it.id }.filter { it !in saved }
        // An uninstalled app leaves its place to the next one, or the last one when it was last
        menu = menu?.takeIf { it.id in apps }
        confirm = confirm?.takeIf { it.id in apps }
        moving = moving?.takeIf { it in apps }
        val (kind, i) = where(focus) ?: ("fav" to 0)
        focus = when {
            kind == "fav" && favs.isNotEmpty() -> "fav-${min(i, favs.lastIndex)}"
            kind == "fav" -> "all-0"
            else -> "all-${max(0, min(i, rest.lastIndex))}"
        }
    }

    fun show() {
        menu = null; moving = null; confirm = null; shelfChanged = false
        focus = if (favs.isNotEmpty()) "fav-0" else "all-0"
        open = true
    }

    private fun where(fid: String): Pair<String, Int>? {
        val kind = fid.substringBefore('-')
        val i = fid.substringAfter('-').toIntOrNull() ?: return null
        return if (kind == "fav" || kind == "all") kind to i else null
    }
    private fun list(kind: String) = if (kind == "fav") favs else rest
    fun appAt(fid: String): String? = where(fid)?.let { (kind, i) -> list(kind).getOrNull(i) }

    private fun items(id: String): List<MenuItem> {
        val fav = id in favs
        return listOfNotNull(
            MenuItem("open", "Open", PhosphorIcons.Fill.Play),
            MenuItem("fav", if (fav) "Remove from favorites" else "Add to favorites", if (fav) PhosphorIcons.Fill.PushPinSlash else PhosphorIcons.Fill.PushPin),
            MenuItem("move", "Move", PhosphorIcons.Bold.ArrowsOutCardinal),
            MenuItem("uninstall", "Uninstall", PhosphorIcons.Fill.Trash).takeUnless { apps[id]?.system != false },
        )
    }
    internal fun menuItems(id: String) = items(id)

    private fun launch(id: String) {
        val intent = apps[id]?.intent ?: return
        runCatching { context.startActivity(intent) }.onFailure { Log.w("Window", "launch $id", it) }
    }

    private fun saveFavs(next: List<String>) {
        favs = next
        shelfChanged = true
        Favorites.write(context, next)
    }

    private fun saveOrder(next: List<String>) {
        order = next
        Favorites.writeOrder(context, next)
    }

    // Moving among the rest swaps the two apps' places in the full order
    private fun setRest(next: List<String>) {
        val out = order.toMutableList()
        order.indices.filter { order[it] !in favs }.forEachIndexed { k, slot -> out[slot] = next[k] }
        saveOrder(out)
    }

    private fun choose(m: AppMenu) {
        val it = items(m.id).getOrNull(m.at) ?: return
        menu = null
        when (it.key) {
            "open" -> launch(m.id)
            // The app moves between the two lists, and focus goes with it
            "fav" -> if (m.id in favs) {
                val next = favs - m.id
                saveFavs(next)
                focus = "all-${order.filter { id -> id !in next }.indexOf(m.id)}"
            } else {
                focus = "fav-${favs.size}"
                saveFavs(favs + m.id)
            }
            "move" -> moving = m.id
            "uninstall" -> confirm = Confirm(m.id, 0)
        }
    }

    // The system asks once more and may be cancelled; the lists catch up when Window comes back
    private fun uninstall(id: String) {
        confirm = null
        runCatching {
            context.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$id")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Log.w("Window", "uninstall $id", it) }
    }

    private fun tap(fid: String) {
        if (moving != null) { moving = null; return }
        appAt(fid)?.let(::launch)
    }

    // OK goes down: a press opens the app when it comes back up; held, it opens the app's menu
    fun okDown(first: Boolean) {
        confirm?.let { if (first) { if (it.at == 1) uninstall(it.id) else confirm = null }; return }
        menu?.let { if (first) choose(it); return }
        if (where(focus) == null || !first || hold != null) return
        val fid = focus
        hold = scope.launch {
            delay(HOLD_MS)
            val id = appAt(fid)
            if (id != null && moving == null) menu = AppMenu(id, 0)
        }
    }

    fun okUp() {
        val h = hold ?: return
        hold = null
        if (h.isActive) { h.cancel(); tap(focus) }
    }

    fun route(dx: Int, dy: Int) {
        hold?.cancel(); hold = null
        confirm?.let { if (dx != 0) confirm = it.copy(at = if (dx > 0) 1 else 0); return }
        menu?.let { if (dy != 0) menu = it.copy(at = (it.at + dy).coerceIn(0, items(it.id).lastIndex)); return }
        val (kind, i) = where(focus) ?: return
        if (moving != null) {
            // The lifted app trades places with its neighbour, inside its own list
            val l = list(kind)
            val j = i + dx + dy * COLS
            if (j < 0 || j >= l.size) return
            val next = l.toMutableList().also { it[i] = l[j]; it[j] = l[i] }
            if (kind == "fav") saveFavs(next) else setRest(next)
            focus = "$kind-$j"
            return
        }
        val rows = favs.indices.map { "fav-$it" }.chunked(COLS) + rest.indices.map { "all-$it" }.chunked(COLS)
        val r = rows.indexOfFirst { focus in it }
        if (r < 0) return
        val c = rows[r].indexOf(focus)
        focus = if (dx != 0) rows[r].getOrElse(c + dx) { focus }
        else rows.getOrNull(r + dy)?.let { it[min(c, it.lastIndex)] } ?: focus
    }

    // Back closes the dialog, the menu, the move, then the screen; true when the screen closed
    fun back(): Boolean {
        hold?.cancel(); hold = null
        when {
            confirm != null -> confirm = null
            menu != null -> menu = null
            moving != null -> moving = null
            else -> { open = false; return true }
        }
        return false
    }
}

// Stays composed while it leaves, so it can animate out
@Composable
fun AllApps(s: AllAppsState) {
    var mounted by remember { mutableStateOf(false) }
    LaunchedEffect(s.open) { if (s.open) mounted = true else { delay(320); mounted = false } }
    if (s.open || mounted) AllAppsScreen(s)
}

private data class MenuView(val app: TvApp, val items: List<MenuItem>, val at: Int, val fav: Boolean)

@Composable
private fun AllAppsScreen(s: AllAppsState) {
    // Composed hidden first, then shown a frame later, so everything rises in from its start
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(s.open) { if (s.open) withFrameNanos { }; shown = s.open }
    // Once everything has risen in, tiles answer focus and the menu at once, without the entry's delays
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(shown) { settled = false; if (shown) { delay(1100); settled = true } }

    val favs = s.favs
    val rest = s.rest
    val favRows = (favs.size + COLS - 1) / COLS
    // Rows top to bottom, both sections, so the grid can scroll the focused row into view
    fun rowTop(kind: String, i: Int): Float {
        val r = i / COLS
        return if (kind == "fav") HEAD + r * ROW else HEAD + favRows * ROW + 24 + HEAD + r * ROW
    }
    val kind = s.focus.substringBefore('-')
    val n = s.focus.substringAfter('-').toIntOrNull() ?: 0
    val y = if (kind == "fav" || kind == "all") rowTop(kind, n) else 0f
    val scrollTarget = max(0f, y - ROW + 40)
    val scroll = animateFloatAsState(scrollTarget, if (LocalHeld.current) steady() else settle(Motion.SHEET), label = "scroll")

    // The menu opens beside its app: to the right of it, or to the left near the right edge, level
    // with it and kept on screen. It stays put while it closes.
    val anchor = remember { floatArrayOf(0f, 0f, 1f) }
    if (s.menu != null) {
        val c = n % COLS
        val tileX = 72 + c * (TILE_W + GAP)
        val tileY = 140 + y - scrollTarget
        val side = if (c <= 2) 1f else -1f
        anchor[0] = if (side > 0) tileX + TILE_W + 28 else tileX - 28 - MENU_W
        anchor[1] = (tileY - 24).coerceIn(40f, 1080 - MENU_H - 40)
        anchor[2] = side
    }

    // What the menu, dialog and pill show, kept through their fade out
    val menuNow = s.menu?.let { m -> s.apps[m.id]?.let { MenuView(it, s.menuItems(m.id), m.at, m.id in favs) } }
    var menuKept by remember { mutableStateOf<MenuView?>(null) }
    LaunchedEffect(menuNow) { if (menuNow != null) menuKept = menuNow else { delay(Motion.SHEET.toLong()); menuKept = null } }
    val confirmNow = s.confirm?.let { c -> s.apps[c.id]?.let { it to c.at } }
    var confirmKept by remember { mutableStateOf<Pair<TvApp, Int>?>(null) }
    LaunchedEffect(confirmNow) { if (confirmNow != null) confirmKept = confirmNow else { delay(Motion.SHEET.toLong()); confirmKept = null } }
    val movingNow = s.moving?.let { s.apps[it]?.name }
    var movingKept by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(movingNow) { if (movingNow != null) movingKept = movingNow else { delay(Motion.SHEET.toLong()); movingKept = null } }

    val backdrop = animateFloatAsState(if (shown) 1f else 0f, glide(if (shown) 450 else 300), label = "backdrop")
    val title = animateFloatAsState(if (shown) 1f else 0f, settle(480), label = "title")
    val hint = animateFloatAsState(if (shown && s.moving == null) 1f else 0f, settle(Motion.SHEET), label = "hint")
    val pill = animateFloatAsState(if (s.moving != null) 1f else 0f, settle(Motion.SHEET), label = "pill")
    val dim = animateFloatAsState(if (s.confirm != null) .45f else 0f, glide(Motion.SHEET), label = "dim")
    val menuIn = animateFloatAsState(if (s.menu != null) 1f else 0f, settle(Motion.SHEET), label = "menu")
    val dialogIn = animateFloatAsState(if (s.confirm != null) 1f else 0f, settle(Motion.SHEET), label = "dialog")

    Box(Modifier.fillMaxSize()) {
        // The home sinks back as the grid comes up over it
        Box(Modifier.fillMaxSize().drawBehind { if (backdrop.value > 0f) drawRect(BACKDROP, alpha = backdrop.value) })

        // The heading is part of the page: it scrolls up and off with the apps
        Box(Modifier.fillMaxSize().graphicsLayer { translationY = -scroll.value.dp.toPx() }) {
            Text(
                t("Your apps"),
                Modifier.padding(start = 72.dp, top = 72.dp).graphicsLayer {
                    alpha = title.value
                    translationY = -(1f - title.value) * 14.dp.toPx()
                },
                color = Ink.text, fontFamily = Syne, fontWeight = FontWeight.Bold, fontSize = 56.sp, lineHeight = 56.sp,
            )
            Text(
                t("Hold OK for more"),
                Modifier.align(Alignment.TopEnd).padding(top = 72.dp, end = 72.dp).graphicsLayer { alpha = hint.value },
                color = Ink.text.copy(alpha = .5f), fontFamily = Golos, fontSize = 18.sp,
            )
        }

        Box(Modifier.fillMaxSize().padding(start = 72.dp, top = 140.dp).graphicsLayer { translationY = -scroll.value.dp.toPx() }) {
            if (favs.isNotEmpty()) Head(t("Favorites"), t("On the home shelf, in this order"), 0, 0f, shown)
            val restHead = (if (favs.isNotEmpty()) HEAD else 0f) + (if (favRows > 0) favRows * ROW - GAP else 0f) + GAP
            Head(t(if (favs.isNotEmpty()) "More apps" else "All apps"), f("{0} on this TV in all", favs.size + rest.size), 1, restHead, shown)
            favs.forEachIndexed { i, id ->
                val app = s.apps[id] ?: return@forEachIndexed
                key(id) { Tile(app, "fav-$i", s, i / COLS, i % COLS, i / COLS * ROW + HEAD, shown, settled) }
            }
            rest.forEachIndexed { i, id ->
                val app = s.apps[id] ?: return@forEachIndexed
                key(id) { Tile(app, "all-$i", s, favRows + 1 + i / COLS, i % COLS, restHead + HEAD + i / COLS * ROW, shown, settled) }
            }
        }

        // Moving: what the remote does now
        (movingNow ?: movingKept)?.let { name ->
            Row(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp)
                    .graphicsLayer { alpha = pill.value; translationY = (1f - pill.value) * 16.dp.toPx() }
                    .background(Ink.text, CircleShape).padding(horizontal = 28.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Glyph(PhosphorIcons.Bold.ArrowsOutCardinal, 22.dp) { Color.Black }
                Text(f("Moving {0} · arrows move · OK done", name), color = Color.Black, fontFamily = Golos, fontWeight = FontWeight.Medium, fontSize = 20.sp)
            }
        }

        Box(Modifier.fillMaxSize().drawBehind { if (dim.value > 0f) drawRect(Color.Black, alpha = dim.value) })

        // Held: the app's menu, beside it
        (menuNow ?: menuKept)?.let { m -> Menu(m, menuNow?.at ?: m.at, anchor[0], anchor[1], anchor[2], menuIn) }

        // Uninstall asks once
        (confirmNow ?: confirmKept)?.let { (app, at) -> UninstallDialog(app, confirmNow?.second ?: at, dialogIn, Modifier.align(Alignment.Center)) }
    }
}

@Composable
private fun Head(text: String, sub: String, i: Int, top: Float, shown: Boolean) {
    val p = animateFloatAsState(if (shown) 1f else 0f, tween(480, if (shown) 40 + i * 120 else 0, SETTLE), label = "head")
    Row(
        Modifier.offset(y = top.dp).height(HEAD.dp)
            .graphicsLayer { alpha = p.value; translationY = (1f - p.value) * 12.dp.toPx(); compositingStrategy = CompositingStrategy.ModulateAlpha }
            .padding(bottom = 16.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(text, color = Ink.text, fontFamily = Syne, fontWeight = FontWeight.SemiBold, fontSize = 26.sp)
        Spacer(Modifier.width(16.dp))
        Text(sub, color = Ink.text.copy(alpha = .5f), fontFamily = Golos, fontSize = 18.sp)
    }
}

@Composable
private fun Tile(app: TvApp, fid: String, s: AllAppsState, r: Int, c: Int, top: Float, shown: Boolean, settled: Boolean) {
    val on = s.focus == fid
    val lifted = on && s.moving == app.id
    // In: each tile rises a beat after the one before it, row by row. Out: all together, quickly
    val delay = if (shown && !settled) min(60 + r * 55 + c * 22, 520) else 0
    val alpha = animateFloatAsState(
        if (!shown) 0f else if (s.menu != null && !on) .3f else 1f,
        if (settled) settle(Motion.SHEET) else if (shown) tween(520, delay, SETTLE) else glide(220), label = "tile",
    )
    val moveSpec = when {
        !shown -> glide<Float>(260)
        on || lifted || settled -> settle(Motion.FOCUS)
        else -> tween(520, delay, SETTLE)
    }
    val scale = animateFloatAsState(if (!shown) .94f else if (lifted) 1.1f else if (on) 1.06f else 1f, moveSpec, label = "scale")
    val rise = animateFloatAsState(if (shown) 0f else 28f, moveSpec, label = "rise")
    val ring = animateFloatAsState(if (on) 1f else 0f, settle(Motion.FOCUS), label = "ring")
    val ringColor = animateColorAsState(if (lifted) Ink.accent else Ink.text, settle(Motion.FOCUS), label = "ringColor")
    val drop = animateFloatAsState(if (lifted) 1f else 0f, settle(Motion.FOCUS), label = "drop")
    val shader = remember(app.banner) { app.banner?.takeUnless { app.iconOnly }?.let { bannerShader(it) } }
    val shadow = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.BLACK } }

    Column(
        Modifier
            .offset((c * (TILE_W + GAP)).dp, top.dp)
            .zIndex(if (lifted) 3f else if (on) 2f else 1f)
            .width(TILE_W.dp).height((TILE_H + 44f).dp)
            .graphicsLayer {
                this.alpha = alpha.value
                scaleX = scale.value; scaleY = scale.value
                translationY = rise.value.dp.toPx()
                compositingStrategy = CompositingStrategy.ModulateAlpha
            },
    ) {
        Box(
            Modifier.size(TILE_W.dp, TILE_H.dp).drawBehind {
                val radius = 22.dp.toPx()
                // Lifted, it casts a long soft shadow, as the prototype's 0 30px 60px -10px
                if (drop.value > 0f) drawIntoCanvas { canvas ->
                    shadow.setShadowLayer(51.dp.toPx(), 0f, 30.dp.toPx(), Color.Black.copy(alpha = .8f * drop.value).toArgb())
                    val inset = 10.dp.toPx()
                    canvas.nativeCanvas.drawRoundRect(inset, inset, size.width - inset, size.height - inset, radius, radius, shadow)
                }
                // The focus ring: a 4 px gap the colour of the page, then 4 px of ring
                if (ring.value > 0f) {
                    val k = ring.value
                    ringBand(BACKDROP, 0f, 4.dp.toPx() * k, radius)
                    ringBand(ringColor.value, 4.dp.toPx() * k, 8.dp.toPx() * k, radius)
                }
                drawApp(app, shader, radius)
            },
        )
        Text(
            app.name, Modifier.padding(top = 12.dp).padding(horizontal = 4.dp),
            color = if (on) Ink.text else Ink.text.copy(alpha = .6f), fontFamily = Golos, fontWeight = FontWeight.Medium,
            fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

// A band around the card from `from` to `to` outside its edge, as a box-shadow spread draws it
private fun DrawScope.ringBand(color: Color, from: Float, to: Float, radius: Float) {
    val w = to - from
    if (w <= 0f) return
    val mid = (from + to) / 2
    drawRoundRect(
        color, topLeft = Offset(-mid, -mid), size = Size(size.width + mid * 2, size.height + mid * 2),
        cornerRadius = CornerRadius(radius + mid), style = Stroke(w),
    )
}

// The app's card: its colour, then its banner over it, or its icon in the middle when it has none
private fun DrawScope.drawApp(app: TvApp, shader: BitmapShader?, radius: Float) {
    val corner = CornerRadius(radius)
    drawRoundRect(app.color, cornerRadius = corner)
    val banner = app.banner ?: return
    if (shader != null) drawBanner(shader, banner.width, banner.height, corner)
    else {
        val side = (96f / TILE_W * size.width).toInt()
        drawImage(
            banner, dstOffset = IntOffset(((size.width - side) / 2).toInt(), ((size.height - side) / 2).toInt()),
            dstSize = IntSize(side, side),
        )
    }
}

@Composable
private fun AppCard(app: TvApp, w: Dp, h: Dp, radius: Dp) {
    val shader = remember(app.banner) { app.banner?.takeUnless { app.iconOnly }?.let { bannerShader(it) } }
    Box(Modifier.size(w, h).drawBehind { drawApp(app, shader, radius.toPx()) })
}

// A Phosphor glyph whose colour is read while drawing, so a changing colour only redraws it
@Composable
private fun Glyph(icon: ImageVector, size: Dp, tint: () -> Color) {
    val painter = rememberVectorPainter(icon)
    Box(Modifier.size(size).drawBehind { with(painter) { draw(this@drawBehind.size, colorFilter = ColorFilter.tint(tint())) } })
}

@Composable
private fun Menu(m: MenuView, at: Int, left: Float, top: Float, side: Float, shown: androidx.compose.runtime.State<Float>) {
    Column(
        Modifier
            .offset(left.dp, top.dp)
            .width(MENU_W.dp)
            .graphicsLayer {
                alpha = shown.value
                translationX = -side * 24.dp.toPx() * (1f - shown.value)
                val k = .96f + .04f * shown.value
                scaleX = k; scaleY = k
                transformOrigin = TransformOrigin(if (side > 0) 0f else 1f, 0f)
            }
            .background(Color(0xEB101012), RoundedCornerShape(36.dp))
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppCard(m.app, 128.dp, 72.dp, 14.dp)
            Spacer(Modifier.width(16.dp))
            Column {
                Text(m.app.name, color = Ink.text, fontFamily = Golos, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(t(if (m.fav) "On the home shelf" else "Not on the home shelf"), color = Ink.text.copy(alpha = .55f), fontFamily = Golos, fontSize = 16.sp)
            }
        }
        Spacer(Modifier.height(20.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            m.items.forEachIndexed { i, it -> MenuRow(it, i == at) }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            t("▲ ▼ choose · OK · Back closes"), Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
            color = Ink.text.copy(alpha = .5f), fontFamily = Golos, fontSize = 15.sp,
        )
    }
}

@Composable
private fun MenuRow(it: MenuItem, selected: Boolean) {
    val danger = it.key == "uninstall"
    val bg = animateColorAsState(if (selected) (if (danger) DANGER else Ink.text) else Color.Transparent, settle(Motion.FOCUS), label = "rowBg")
    val fg = animateColorAsState(if (selected) ON_LIGHT else if (danger) DANGER_TEXT else Ink.text, settle(Motion.FOCUS), label = "rowFg")
    Row(
        Modifier.fillMaxWidth().height(64.dp)
            .drawBehind { drawRoundRect(bg.value, cornerRadius = CornerRadius(20.dp.toPx())) }
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(it.icon, 22.dp) { fg.value }
        Spacer(Modifier.width(16.dp))
        BasicText(
            t(it.label), style = TextStyle(fontFamily = Golos, fontWeight = FontWeight.Medium, fontSize = 20.sp),
            maxLines = 1, color = { fg.value },
        )
    }
}

@Composable
private fun UninstallDialog(app: TvApp, at: Int, shown: androidx.compose.runtime.State<Float>, modifier: Modifier) {
    Column(
        modifier
            .width(620.dp)
            .graphicsLayer {
                alpha = shown.value
                val k = .95f + .05f * shown.value
                scaleX = k; scaleY = k
            }
            .background(Color(0xF2101012), RoundedCornerShape(40.dp))
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AppCard(app, 160.dp, 90.dp, 18.dp)
        Spacer(Modifier.height(20.dp))
        Text(f("Uninstall {0}?", app.name), color = Ink.text, fontFamily = Syne, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            t("It and everything it keeps on this TV will be removed."), color = Ink.text.copy(alpha = .6f),
            fontFamily = Golos, fontSize = 18.sp, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf("Cancel", "Uninstall").forEachIndexed { i, label ->
                val selected = at == i
                val bg = animateColorAsState(
                    if (selected) (if (i == 1) DANGER else Ink.text) else Color.White.copy(alpha = .08f),
                    settle(Motion.FOCUS), label = "button",
                )
                Box(
                    Modifier.weight(1f).height(64.dp).drawBehind { drawRoundRect(bg.value, cornerRadius = CornerRadius(20.dp.toPx())) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(t(label), color = if (selected) ON_LIGHT else Ink.text, fontFamily = Golos, fontWeight = FontWeight.Medium, fontSize = 20.sp)
                }
            }
        }
    }
}

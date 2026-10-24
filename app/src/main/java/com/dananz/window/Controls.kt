package com.dananz.window

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text

// The room's controls, on the apps' grid: each tile one app card wide, the AC two.
// OK switches a light or the AC on and off; holding OK opens its big control.

private val TILE = RoundedCornerShape(28.dp)
private val WARM = Color(0xFFFFB35C)
private val MODE_TINT = mapOf(AcMode.Cool to Color(0xFF9FD4FF), AcMode.Heat to Color(0xFFFFB08A))

@Composable
private fun Bulb(on: Boolean, all: Boolean = false, kind: Kind = Kind.Light) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(if (on) Ink.accent else Color.White.copy(alpha = .1f)),
        contentAlignment = Alignment.Center,
    ) { Icon(if (kind == Kind.Light) Glyphs.bulb(on, all) else kindIcon(kind), null, Modifier.size(21.dp), tint = if (on) Color(0xFF2A1A06) else Ink.text.copy(alpha = .7f)) }
}

// Draws a child above its parent without taking up room in the row, as the sheets that rise out of a tile
private fun Modifier.above(gap: Int = 16) = layout { m, c ->
    val p = m.measure(c.copy(minWidth = 0, minHeight = 0, maxWidth = 4000, maxHeight = 4000))
    layout(0, 0) { p.place(0, -p.height - gap.dp.roundToPx()) }
}

@Composable
private fun Sheet(open: Boolean, content: @Composable () -> Unit) {
    val a = animateFloatAsState(if (open) 1f else 0f, settle(Motion.SHEET), label = "sheet")
    // Read through derivedStateOf, so the sheet recomposes only as it appears and goes, not every frame
    val shown by remember { derivedStateOf { a.value > 0f } }
    if (!shown) return
    Box(
        Modifier.above().graphicsLayer {
            alpha = a.value
            translationY = (1 - a.value) * 16.dp.toPx()
            val s = .96f + .04f * a.value
            scaleX = s; scaleY = s
        },
    ) { content() }
}

@Composable
fun LightTile(light: Light?, name: String, level: Int, focused: Boolean, open: Boolean, all: Boolean = false) {
    // Switches, fans, curtains, another AC: on or off; a scene runs
    val kind = light?.kind ?: Kind.Light
    // Read only while drawing, so a changing level redraws without recomposing the tile
    val fill = animateFloatAsState(level / 100f, settle(Motion.FILL), label = "fill")
    val dark by remember { derivedStateOf { fill.value > .82f } }
    Column {
        Sheet(open) {
            // Held: a tall brightness bar rises out of the tile
            Column(
                Modifier.size(150.dp, 360.dp).offset(x = 7.dp).clip(RoundedCornerShape(34.dp)).background(Ink.sheet).padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Glyphs.Up, null, Modifier.size(20.dp), tint = Ink.text.copy(alpha = .6f))
                Box(
                    Modifier.padding(vertical = 8.dp).fillMaxWidth().weight(1f).clip(RoundedCornerShape(26.dp)).background(Color.White.copy(alpha = .08f))
                        .drawBehind {
                            val h = size.height * fill.value
                            drawRect(Brush.verticalGradient(listOf(Color(0xFFFFD9A0), WARM), startY = size.height - h, endY = size.height), topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - h), size = androidx.compose.ui.geometry.Size(size.width, h))
                        },
                ) {
                    Text(
                        "$level%", Modifier.align(Alignment.TopCenter).padding(top = 16.dp),
                        color = if (dark) Color(0xFF2A1A06) else Ink.text, fontFamily = Syne, style = Numbers, fontWeight = FontWeight.SemiBold, fontSize = 40.sp,
                    )
                }
                Icon(Glyphs.Down, null, Modifier.size(20.dp), tint = Ink.text.copy(alpha = .6f))
            }
        }
        Box(
            Modifier.size(164.dp, 112.dp).ring(focused, 28.dp, if (open) Ink.accent else Ink.text).clip(TILE).background(Ink.glass)
                .drawBehind {
                    // The tile fills up warm from the bottom, as bright as the light is
                    val h = size.height * fill.value
                    drawRect(Brush.verticalGradient(listOf(Color(0x40FFCD8C), Color(0x8CFFAA50)), startY = size.height - h, endY = size.height), topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - h), size = androidx.compose.ui.geometry.Size(size.width, h))
                }
                .padding(16.dp),
        ) {
            Bulb(level > 0 && kind != Kind.Scene, all, kind)
            Column(Modifier.align(Alignment.BottomStart)) {
                Text(t(name), color = Ink.text, fontFamily = Golos, fontWeight = FontWeight.Medium, fontSize = 19.sp, lineHeight = 22.sp, maxLines = 1)
                val hint = when {
                    open -> t("▲▼ · OK done")
                    focused && all -> t(if (level > 0) "OK all off" else "OK all on")
                    focused -> t(when (kind) { Kind.Light -> "Hold OK to dim"; Kind.Scene -> "OK to run"; Kind.Cover -> "OK open/close"; else -> "OK on/off" })
                    kind == Kind.Scene -> t("Scene")
                    kind == Kind.Cover -> t(if (level > 0) "Opened" else "Closed")
                    kind != Kind.Light -> t(if (level > 0) "On" else "Off")
                    else -> if (level > 0) "$level%" else t("Off")
                }
                Text(hint, color = Ink.text.copy(alpha = .7f), fontFamily = Golos, fontSize = 14.sp, lineHeight = 17.sp)
            }
        }
    }
}

@Composable
fun AcTile(ac: Ac, focused: Boolean, open: Boolean, row: Int) {
    val tint = MODE_TINT.getValue(ac.mode)
    val room = ac.room?.let { "${(it * 10).toInt() / 10f}°" } ?: "–"
    Column {
        Sheet(open) {
            // Held: three rows, temperature, mode and fan. Up and down pick the row, left and right change it
            Column(Modifier.width(420.dp).clip(RoundedCornerShape(34.dp)).background(Ink.sheet).padding(16.dp)) {
                @Composable
                fun line(i: Int, h: Int?, content: @Composable () -> Unit) = Row(
                    Modifier.fillMaxWidth().padding(top = if (i == 0) 0.dp else 8.dp).let { if (h != null) it.height(h.dp) else it }
                        .ring(row == i, 22.dp, Ink.accent).clip(RoundedCornerShape(22.dp))
                        .background(if (row == i) Color.White.copy(alpha = .1f) else Color.Transparent).padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) { content() }
                // The picked segment warms up rather than switching; its colour is read only while drawing
                @Composable
                fun seg(on: Boolean): Modifier {
                    val c = animateColorAsState(if (on) Ink.accent else Color.White.copy(alpha = .07f), settle(Motion.FILL), label = "seg")
                    return Modifier.height(48.dp).clip(RoundedCornerShape(14.dp)).drawBehind { drawRect(c.value) }
                }
                line(0, null) {
                    Column(Modifier.weight(1f)) {
                        Text(f("Set to · {0}° now", room.removeSuffix("°")), color = Ink.text.copy(alpha = .6f), fontFamily = Golos, fontSize = 16.sp)
                        Text("${ac.temp}°", color = Ink.text, fontFamily = Syne, style = Numbers, fontWeight = FontWeight.SemiBold, fontSize = 88.sp, lineHeight = 92.sp)
                    }
                    if (row == 0) Text(t("◀ ▶"), color = Ink.text.copy(alpha = .55f), fontFamily = Golos, fontSize = 17.sp)
                }
                line(1, 72) {
                    AcMode.entries.forEach { m ->
                        val on = m == ac.mode
                        Row(seg(on).weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                            Icon(Glyphs.mode(m, on), null, Modifier.size(18.dp), tint = if (on) Color(0xFF1B1308) else Ink.text)
                            Spacer(Modifier.width(8.dp))
                            Text(t(m.label), color = if (on) Color(0xFF1B1308) else Ink.text, fontFamily = Golos, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, fontSize = 17.sp)
                        }
                    }
                }
                line(2, 72) {
                    Icon(Glyphs.Fan, null, Modifier.size(22.dp), tint = Ink.text.copy(alpha = .6f))
                    FANS.forEachIndexed { i, f ->
                        val on = i == ac.fan
                        Box(seg(on).weight(1f), contentAlignment = Alignment.Center) {
                            Text(t(f.second), color = if (on) Color(0xFF1B1308) else Ink.text, fontFamily = Golos, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, fontSize = 16.sp)
                        }
                    }
                }
                Text(t("▲▼ row · ◀▶ change · OK done"), Modifier.fillMaxWidth().padding(top = 12.dp).wrapContentSize(), color = Ink.text.copy(alpha = .55f), fontFamily = Golos, fontSize = 15.sp)
            }
        }
        Row(
            Modifier.size(344.dp, 112.dp).ring(focused, 28.dp, if (open) Ink.accent else Ink.text).clip(TILE)
                .background(if (ac.on) Brush.radialGradient(listOf(tint.copy(alpha = .32f), Color.Transparent), radius = 500f, center = androidx.compose.ui.geometry.Offset(30f, 0f)) else Brush.linearGradient(listOf(Ink.glass, Ink.glass)))
                .background(if (ac.on) Color(0x990E1218) else Color.Transparent)
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(if (ac.on) tint else Color.White.copy(alpha = .1f)),
                contentAlignment = Alignment.Center,
            ) { Icon(Glyphs.mode(ac.mode, ac.on), null, Modifier.size(24.dp), tint = if (ac.on) Color(0xFF08131C) else Ink.text.copy(alpha = .7f)) }
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Bottom) {
                Text(f("AC · {0}° now", room.removeSuffix("°")), color = Ink.text, fontFamily = Golos, fontWeight = FontWeight.Medium, fontSize = 19.sp, lineHeight = 22.sp)
                val sub = when {
                    open -> t("▲▼ row · ◀▶ change")
                    focused -> t("OK on/off · hold for more")
                    ac.on -> "${t(ac.mode.doing)} · ${t(listOf("Auto fan", "Low fan", "Medium fan", "High fan")[ac.fan])}"
                    else -> t("Off")
                }
                Text(sub, color = Ink.text.copy(alpha = .7f), fontFamily = Golos, fontSize = 14.sp, lineHeight = 17.sp)
            }
            Text(
                "${ac.temp}°", Modifier.graphicsLayer { compositingStrategy = CompositingStrategy.ModulateAlpha; alpha = if (ac.on) 1f else .45f },
                color = Ink.text, fontFamily = Syne, style = Numbers, fontWeight = FontWeight.SemiBold, fontSize = 44.sp, lineHeight = 44.sp,
            )
        }
    }
}

@Composable
fun ControlsRow(room: Room, focus: String, grabbed: String?, acRow: Int, dim: Boolean) {
    val a = animateFloatAsState(if (dim) .4f else 1f, glide(Motion.DIM), label = "dim")
    val lights = room.lights.filter { it.kind == Kind.Light }
    val anyOn = lights.any { it.level > 0 }
    Row(
        Modifier.fillMaxSize().padding(start = 72.dp, end = 72.dp, bottom = 44.dp + 92.dp + 32.dp).graphicsLayer { compositingStrategy = CompositingStrategy.ModulateAlpha; alpha = a.value }
            .wrapContentSize(Alignment.BottomStart),
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Bottom,
    ) {
        if (lights.isNotEmpty()) LightTile(null, "All lights", if (anyOn) lights.maxOf { it.level } else 0, focus == "all-lights", false, all = true)
        room.lights.forEach { l -> LightTile(l, l.name, l.level, focus == "light-${l.id}", grabbed == "light-${l.id}") }
        if (room.acEntity != null) AcTile(room.ac, focus == "ac-card", grabbed == "ac-card", acRow)
        // Nothing on the wall yet: one tile that opens the page that puts things there
        if (room.lights.isEmpty() && room.acEntity == null) AddTiles(focus == "add-tiles")
    }
}

@Composable
private fun AddTiles(focused: Boolean) {
    Row(
        Modifier.size(344.dp, 112.dp).ring(focused, 28.dp).clip(TILE).background(Ink.glass).padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Bulb(false)
        Column {
            Text(t("Add tiles"), color = Ink.text, fontFamily = Golos, fontWeight = FontWeight.Medium, fontSize = 19.sp, lineHeight = 22.sp)
            Text(t("Lights, the AC and more, from Pane settings"), color = Ink.text.copy(alpha = .7f), fontFamily = Golos, fontSize = 14.sp, lineHeight = 17.sp)
        }
    }
}

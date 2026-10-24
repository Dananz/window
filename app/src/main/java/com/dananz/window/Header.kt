package com.dananz.window

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.Crossfade
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.composed
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

// The remote's focus: a 4px ring, 4px outside the element, as in the prototype
fun Modifier.ring(focused: Boolean, radius: Dp, color: Color = Ink.text): Modifier = composed {
    val a = animateFloatAsState(if (focused) 1f else 0f, settle(Motion.FOCUS), label = "ring")
    drawWithContent {
        drawContent()
        if (a.value <= 0f) return@drawWithContent
        val gap = 6.dp.toPx()
        drawRoundRect(
            color.copy(alpha = color.alpha * a.value), topLeft = Offset(-gap, -gap), size = Size(size.width + gap * 2, size.height + gap * 2),
            cornerRadius = CornerRadius(radius.toPx() + gap), style = Stroke(4.dp.toPx()),
        )
    }
}

// The time as settings have it (24-hour or 12-hour), and the date in Pane's language
private val H24 = DateTimeFormatter.ofPattern("HH:mm")
private val H12 = DateTimeFormatter.ofPattern("h:mm")
val TIME: DateTimeFormatter get() = if (Prefs.clock24) H24 else H12
val DATE: DateTimeFormatter get() = if (Lang.he) DateTimeFormatter.ofPattern("EEEE, d בMMMM", Lang.locale) else DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.UK)
val SKY_WORDS = mapOf(Sky.Clear to "Clear", Sky.Cloudy to "Cloudy", Sky.Rain to "Rain")
fun skyWord(s: Sky) = t(SKY_WORDS.getValue(s))

// The clock, large and light, and under it the date and the weather as one quiet line
@Composable
fun Clock(now: LocalTime, today: LocalDate, weather: Weather?, day: Int) {
    Column(Modifier.offset(72.dp, 44.dp)) {
        Text(
            now.format(TIME), color = Ink.text, fontFamily = Syne, style = Numbers, fontWeight = FontWeight.Bold,
            fontSize = 136.sp, lineHeight = 136.sp, letterSpacing = (-4.1).sp,
        )
        Line(today, weather, day)
    }
}

@Composable
private fun Line(today: LocalDate, weather: Weather?, day: Int) {
    // Tucked up under the clock: its digits leave room below them in the line
    Row(Modifier.offset(y = (-8).dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Crossfade(today, animationSpec = glide(Motion.TEXT), label = "date") { Text(it.format(DATE), color = Ink.text, fontFamily = Golos, fontWeight = FontWeight.Medium, fontSize = 24.sp) }
        if (weather?.days?.getOrNull(day) != null) Box(Modifier.width(1.dp).height(22.dp).background(Ink.text.copy(alpha = .3f)))
        // Another day's forecast: the old words fade out and lift away, then the new ones fade in
        // from just below. One value runs the whole swap, read only while drawing
        Swap(weather to day) { (weather, day) ->
            val d = weather?.days?.getOrNull(day) ?: return@Swap
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val sky = if (day == 0) weather.sky else d.sky
                Icon(Glyphs.sky(sky, true), null, Modifier.size(28.dp), tint = Ink.text)
                Text("${if (day == 0) weather.temp else d.high}°", color = Ink.text, fontFamily = Golos, fontWeight = FontWeight.Medium, fontSize = 24.sp)
                val label = if (day == 0) skyWord(sky) else f("{0}: {1}", dayName(d.date), skyWord(sky))
                Text("$label · ${f("H {0}° L {1}°", d.high, d.low)}", color = Ink.text.copy(alpha = .65f), fontFamily = Golos, fontSize = 24.sp)
            }
        }
    }
}

@Composable
private fun <T> Swap(value: T, content: @Composable (T) -> Unit) {
    var shown by remember { mutableStateOf(value) }
    // 1: shown in place; going out it falls to 0 and lifts; coming in it rises from just below
    val k = remember { Animatable(1f) }
    var coming by remember { mutableStateOf(false) }
    LaunchedEffect(value) {
        if (value == shown) { coming = true; k.animateTo(1f, glide(Motion.TEXT_IN)); return@LaunchedEffect }
        coming = false
        k.animateTo(0f, tween(Motion.TEXT_OUT, easing = LinearOutSlowInEasing))
        shown = value
        coming = true
        k.animateTo(1f, glide(Motion.TEXT_IN))
    }
    Box(Modifier.graphicsLayer {
        alpha = k.value
        translationY = (1 - k.value) * (if (coming) 8 else -6).dp.toPx()
    }) { content(shown) }
}

fun dayName(d: LocalDate): String = t(d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.UK))

// The view switch: always just the pin, so nothing in the top row moves; its list names the views
@Composable
fun PlaceSwitch(place: Place, focused: Boolean, open: Boolean, menuFocus: String) {
    // As wide as the pin: the list hangs out of it instead of widening the row
    Box(Modifier.width(96.dp)) {
        Row(
            Modifier.height(96.dp).ring(focused, 22.dp).clip(RoundedCornerShape(22.dp)).background(Ink.glass).padding(horizontal = 34.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Glyphs.Pin, null, Modifier.size(28.dp), tint = Ink.accent)
        }
        val menu = animateFloatAsState(if (open) 1f else 0f, settle(Motion.SHEET), label = "menu")
        val menuShown by remember { derivedStateOf { menu.value > 0f } }
        if (menuShown) {
            Column(
                Modifier.wrapContentWidth(Alignment.Start, unbounded = true).offset(y = 108.dp)
                    .graphicsLayer {
                        alpha = menu.value
                        translationY = -(1 - menu.value) * 12.dp.toPx()
                    }
                    .width(300.dp).clip(RoundedCornerShape(24.dp)).background(Ink.sheet).padding(12.dp),
            ) {
                Text(t("View"), Modifier.padding(start = 20.dp, top = 6.dp, bottom = 6.dp), color = Ink.text.copy(alpha = .55f), fontFamily = Golos, fontSize = 16.sp)
                Place.entries.forEach { p ->
                    val on = p == place
                    Box(
                        Modifier.fillMaxWidth().height(60.dp).ring(menuFocus == "place-${p.name}", 16.dp)
                            .clip(RoundedCornerShape(16.dp)).background(if (on) Color.White.copy(alpha = .06f) else Color.Transparent)
                            .padding(horizontal = 20.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) { Text(t(p.label), color = if (on) Ink.accent else Ink.text, fontFamily = Golos, fontSize = 20.sp) }
                }
                Box(Modifier.padding(12.dp, 8.dp).fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = .1f)))
                Row(
                    Modifier.fillMaxWidth().height(60.dp).ring(menuFocus == "place-random", 16.dp).padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Glyphs.Shuffle, null, Modifier.size(22.dp), tint = Ink.text)
                    Text(t("Surprise me"), color = Ink.text, fontFamily = Golos, fontSize = 20.sp)
                }
            }
        }
    }
}

// Five days; the one you stand on drives the window and the line under the clock
@Composable
fun Forecast(weather: Weather?, day: Int, focus: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        (weather?.days ?: emptyList()).forEachIndexed { i, d ->
            val on = day == i
            Column(
                Modifier.size(92.dp, 96.dp).ring(focus == "day-$i", 22.dp).clip(RoundedCornerShape(22.dp))
                    .background(if (on) Ink.accent.copy(alpha = .26f).compositeOverGlass() else Ink.glass),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
            ) {
                Text(if (i == 0) t("Now") else dayName(d.date), color = Ink.text.copy(alpha = .75f), fontFamily = Golos, fontSize = 17.sp)
                Icon(Glyphs.sky(if (i == 0) weather!!.sky else d.sky, on), null, Modifier.size(26.dp), tint = Ink.text)
                Text("${if (i == 0) weather!!.temp else d.high}°", color = Ink.text, fontFamily = Golos, fontSize = 17.sp)
            }
        }
    }
}

private fun Color.compositeOverGlass(): Color {
    val g = Ink.glass
    val a = alpha + g.alpha * (1 - alpha)
    fun mix(c: Float, b: Float) = (c * alpha + b * g.alpha * (1 - alpha)) / a
    return Color(mix(red, g.red), mix(green, g.green), mix(blue, g.blue), a)
}


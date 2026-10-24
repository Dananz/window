package com.dananz.window

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.tv.material3.LocalTextStyle

object Ink {
    val text = Color(0xFFF4F0EA)
    val accent = Color(0xFFFFC77A)
    val glass = Color(0x800E0E10)
    val sheet = Color(0xE0101012)
}

// Two faces, as in the prototype's Home H: Syne for the clock, the titles and the big numbers,
// Golos Text for everything read. Neither has Hebrew, so each weight carries Google Sans's Hebrew
// letters merged in (scripts/fonts.sh): Hebrew reads in Google Sans while Latin and the numbers
// stay Syne and Golos, as the browser's font fallback does in the prototype. One file per weight,
// since Compose can't pick a weight from a typeface built with its own fallback.
val Syne = FontFamily(
    Font(R.font.syne_400, FontWeight.W400), Font(R.font.syne_500, FontWeight.W500), Font(R.font.syne_600, FontWeight.W600),
    Font(R.font.syne_700, FontWeight.W700), Font(R.font.syne_800, FontWeight.W800),
)
val Golos = FontFamily(
    Font(R.font.golos_400, FontWeight.W400), Font(R.font.golos_500, FontWeight.W500), Font(R.font.golos_600, FontWeight.W600),
    Font(R.font.golos_700, FontWeight.W700), Font(R.font.golos_800, FontWeight.W800), Font(R.font.golos_900, FontWeight.W900),
)
// Syne's own figures rise and drop like lowercase; numbers set large use its even, fixed-width ones
val Numbers = TextStyle(fontFeatureSettings = "lnum, tnum")

// The prototype was drawn on a 1920x1080 canvas. Here 1.dp is one of its pixels, on any screen,
// so every size reads the same as in the design.
@Composable
fun DesignCanvas(content: @Composable () -> Unit) {
    val config = LocalConfiguration.current
    val density = LocalDensity.current
    val widthPx = config.screenWidthDp * density.density
    // Any text that doesn't name a face still gets Golos, never the system font
    CompositionLocalProvider(
        LocalDensity provides Density(widthPx / 1920f, 1f),
        LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = Golos),
        content = content,
    )
}

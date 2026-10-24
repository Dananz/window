package com.dananz.window

import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ShaderBrush
import android.graphics.Shader
import android.graphics.Matrix
import android.graphics.BitmapShader
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sign

// The rest row along the foot of the screen, measured on the 1920x1080 design
private const val ROW_X = 72f
private const val ROW_Y = 944f
private const val ROW_W = 164f
private const val ROW_H = 92f
private const val ROW_GAP = 16f

// The raised shelf: 16:9 cards, the focused one in front, the rest turned away on a curve
private const val CW = 420f
private const val CH = 236f
private const val CY = 560f
private const val NEAR = 490f
private const val STEP = 280f
private const val TILT = 14f
private const val PERSPECTIVE = 1500f

// Animated values are read only inside graphicsLayer blocks, so a moving card redraws on the
// GPU layer without recomposing or measuring again
@Composable
private fun anim(target: Float, ms: Int = Motion.SHELF): State<Float> =
    animateFloatAsState(target, if (LocalHeld.current) steady() else settle(ms), label = "carousel")

// Apps that rest as a row at the foot and, while one has focus, rise to the middle as a
// curved shelf with the focused card in front and its name large above it
@Composable
fun AppsCarousel(apps: List<TvApp>, focused: Int?) {
    // In Hebrew the shelf runs from the right: laid out left to right here, then mirrored by hand,
    // since the cards are placed by their own transforms
    val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr) {
        Shelf(apps, focused, rtl)
    }
}

@Composable
private fun Shelf(apps: List<TvApp>, focused: Int?, rtl: Boolean) {
    val active = focused != null
    val center = focused ?: 0
    val title = anim(if (active) 1f else 0f)
    Box(Modifier.fillMaxSize()) {
        // The name fades over the last one as focus moves along the shelf
        Crossfade(
            if (active) apps[center].name else "",
            Modifier.fillMaxWidth().offset(y = 235.dp).graphicsLayer { compositingStrategy = CompositingStrategy.ModulateAlpha; alpha = title.value },
            settle(Motion.TEXT), label = "title",
        ) { name ->
            Text(
                text = name,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                color = Ink.text,
                fontFamily = Syne,
                fontWeight = FontWeight.Bold,
                fontSize = 110.sp,
                letterSpacing = (-3.3).sp,
                lineHeight = 110.sp,
            )
        }
        apps.forEachIndexed { i, app ->
            val o = i - center
            val ao = abs(o).toFloat()
            val isFocused = focused == i
            // Every card is laid out once at the shelf's size; the rest row is the same card scaled down
            val x = anim(if (active) 960f + sign(o.toFloat()) * (if (ao == 0f) 0f else NEAR + (ao - 1) * STEP) - CW / 2 else ROW_X + i * (ROW_W + ROW_GAP))
            val y = anim(if (active) CY - CH / 2 else ROW_Y)
            val size = anim(if (active) 1f else ROW_W / CW)
            val z = anim(if (!active) 0f else if (ao == 0f) 70f else -ao * 130f)
            val rot = anim(if (active) -sign(o.toFloat()) * min(ao * TILT, 56f) else 0f)
            val shown = anim(if (active && ao > 3) 0f else 1f)
            val dim = anim(if (active) min(.6f, ao * .22f) else 0f)
            val ring = anim(if (isFocused) 1f else 0f, Motion.FOCUS)
            val mark = anim(if (active) 2.2f else 1f)
            val banner = remember(app.banner) { app.banner?.takeUnless { app.iconOnly }?.let { bannerShader(it) } }
            Box(
                Modifier
                    // Nearer cards over farther ones
                    .zIndex(20f - ao)
                    .size(CW.dp, CH.dp)
                    .graphicsLayer {
                        transformOrigin = TransformOrigin(0f, 0f)
                        // translateZ, as the scale a perspective camera would give it, about the card's centre
                        val p = PERSPECTIVE / (PERSPECTIVE - z.value)
                        val k = size.value * p
                        val w = CW * size.value
                        val h = CH * size.value
                        val cx = x.value + w / 2
                        translationX = ((if (rtl) 1920 - cx else cx) - CW * k / 2).dp.toPx()
                        translationY = (y.value + h / 2 - CH * k / 2).dp.toPx()
                        scaleX = k; scaleY = k
                        rotationY = if (rtl) -rot.value else rot.value
                        cameraDistance = PERSPECTIVE * density
                        alpha = shown.value
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                    }
                    // Rounded by drawing the banner as a rounded rect, not by clipping: a clip on a
                    // turned card costs this GPU a mask every frame
                    .drawWithContent {
                        // The rest row keeps the design's corner, not a scaled-down one
                        val corner = CornerRadius((26f / size.value).dp.toPx())
                        drawRoundRect(app.color, cornerRadius = corner)
                        banner?.let { drawBanner(it, app.banner!!.width, app.banner.height, corner) }
                        drawContent()
                        if (dim.value > 0f) drawRoundRect(Color.Black, alpha = dim.value, cornerRadius = corner)
                        if (ring.value > 0f) drawRoundRect(Color.White.copy(alpha = .95f * ring.value), cornerRadius = CornerRadius((26f / size.value).dp.toPx()), style = Stroke((5f / size.value).dp.toPx() * 2))
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (app.shortcut != null) ShortcutMark(app, mark, size)
                else if (app.iconOnly) app.banner?.let { Image(it, null, Modifier.size(96.dp)) }
            }
        }
    }
}

@Composable
private fun ShortcutMark(app: TvApp, mark: State<Float>, size: State<Float>) {
    Column(
        Modifier.graphicsLayer { val s = mark.value / size.value; scaleX = s; scaleY = s },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Google TV by Google's own G (from svgl), in its colours; the others in Phosphor
        if (app.shortcut == Shortcut.GoogleTv) Image(painterResource(R.drawable.google_g), null, Modifier.size(34.dp))
        else Icon(if (app.shortcut == Shortcut.Settings) Glyphs.Gear else Glyphs.Grid, null, Modifier.size(34.dp), tint = Color(0xFFE8EAED))
        Text(t(when (app.shortcut) { Shortcut.Settings -> "Settings"; Shortcut.AllApps -> "All apps"; else -> "Google TV" }), color = Color(0xFFE8EAED), fontFamily = Syne, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
}

// The banner stretched over the card, as a shader a rounded rect can be filled with
fun bannerShader(banner: ImageBitmap) = BitmapShader(banner.asAndroidBitmap(), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)

fun DrawScope.drawBanner(shader: BitmapShader, w: Int, h: Int, corner: CornerRadius) {
    shader.setLocalMatrix(Matrix().apply { setScale(size.width / w, size.height / h) })
    drawRoundRect(ShaderBrush(shader), cornerRadius = corner)
}

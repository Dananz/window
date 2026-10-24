package com.dananz.window

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import kotlin.math.sin
import kotlin.random.Random

// What moves on the rainy window: rain falling outside, and a few heavy drops running down the
// glass, each wiping a lighter trail behind it. The beads that sit still are in the baked scene.
private class Runner(val x: Float, val speed: Float, val r: Float, val phase: Float, val wob: Float)
private class Streak(val x: Float, val y: Float, val z: Float)

@Composable
fun RainRunners(modifier: Modifier) {
    var time by remember { mutableFloatStateOf(0f) }
    val runners = remember { Random(3).let { r -> List(7) { Runner(r.nextFloat(), 40f + r.nextFloat() * 70f, 6f + r.nextFloat() * 4f, r.nextFloat() * 50f, r.nextFloat() * 10f) } } }
    val streaks = remember { Random(5).let { r -> List(110) { Streak(r.nextFloat(), r.nextFloat(), r.nextFloat()) } } }
    LaunchedEffect(Unit) {
        val start = System.nanoTime()
        while (true) withFrameNanos { time = (it - start) / 1e9f }
    }
    Canvas(modifier.clipToBounds()) {
        val w = size.width; val h = size.height
        // Outside, beyond the glass: near streaks longer, faster and brighter
        for (s in streaks) {
            val len = 16f + s.z * 46f
            val fall = 700f + s.z * 900f
            val y = (s.y * (h + len) + time * fall) % (h + len) - len
            val x = (s.x * (w + 200f) - time * (90f + s.z * 120f)) % (w + 200f)
            val xx = if (x < 0) x + w + 200f else x
            drawLine(Color(0xFFD7E1F0).copy(alpha = .05f + s.z * .14f), Offset(xx, y), Offset(xx + len * .12f, y - len), strokeWidth = .6f + s.z * 1.1f, cap = StrokeCap.Round)
        }
        // Heavy drops, each running down at its own pace and wiping a trail
        for (d in runners) {
            val travel = h + 400f
            val y = ((d.phase + time) * d.speed) % travel - 120f
            val x = d.x * w + sin(y * .035f + d.wob) * 5f
            drawLine(
                Brush.verticalGradient(listOf(Color.Transparent, Color.White.copy(alpha = .07f)), startY = y - 320f, endY = y),
                Offset(x, y - 320f), Offset(x, y), strokeWidth = d.r * 1.4f, cap = StrokeCap.Round,
            )
            drawCircle(Color(0x33000814), d.r * 1.08f, Offset(x, y + d.r * .25f))
            drawCircle(
                Brush.radialGradient(listOf(Color.White.copy(alpha = .55f), Color.White.copy(alpha = .12f), Color.Transparent), center = Offset(x - d.r * .35f, y - d.r * .4f), radius = d.r * 1.1f),
                d.r, Offset(x, y),
            )
        }
    }
}

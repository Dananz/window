package com.dananz.window

import androidx.compose.animation.core.AnimationVector
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.AnimationVector3D
import androidx.compose.animation.core.AnimationVector4D
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.VectorizedDurationBasedAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.compositionLocalOf

// Soft start, long settle: the camera, anything crossing the screen, dims and fades
val GLIDE = CubicBezierEasing(.33f, 0f, .15f, 1f)

// Answers at once, lands softly: what the remote waits on
val SETTLE = CubicBezierEasing(.22f, 1f, .36f, 1f)

object Motion {
    const val CAMERA = 2200
    const val FOCUS = 200
    const val SHEET = 380
    const val FILL = 380
    const val SHELF = 760
    const val TEXT = 600
    // Words swapped in place: out first, then the new ones in
    const val TEXT_OUT = 240
    const val TEXT_IN = 480
    const val DIM = 600
    const val POOLS = 900
    // The Miznon strip dims slowly itself, so its pool goes out with it rather than ahead of it
    const val MIZNON_OFF = 2400
    const val SCENE = 1200
}

fun <T> glide(ms: Int, delay: Int = 0): TweenSpec<T> = tween(ms, delay, GLIDE)

// Holding an arrow: after the remote's own pause it steps on its own, a little faster the longer it
// is held (about 6 to 12 steps a second), so a long row or grid can be crossed without tapping
object Hold {
    private const val FIRST = 170L
    private const val LAST = 85L
    private const val RAMP = 1400f
    fun step(heldMs: Long): Long = (FIRST + (LAST - FIRST) * (heldMs / RAMP).coerceAtMost(1f)).toLong()
}

// True while an arrow is held. Moves then glide at a steady pace from step to step instead of
// easing out and starting again each time.
val LocalHeld = compositionLocalOf { false }
fun <T> steady(): TweenSpec<T> = tween(240, easing = LinearEasing)
fun <T> settle(ms: Int): TweenSpec<T> = tween(ms, easing = SETTLE)

// A glide that starts at the speed it is handed. Retargeted halfway, a move bends toward the new
// target instead of stopping dead and starting over; the extra term fades out before the end, so
// it still lands still. GLIDE starts flat, so the speed carries over without a jolt.
class Carry<T>(private val ms: Int, private val easing: Easing = GLIDE) : FiniteAnimationSpec<T> {
    override fun <V : AnimationVector> vectorize(converter: TwoWayConverter<T, V>): VectorizedDurationBasedAnimationSpec<V> =
        object : VectorizedDurationBasedAnimationSpec<V> {
            override val durationMillis = ms
            override val delayMillis = 0
            private val secs = ms / 1000f

            private fun at(nanos: Long) = (nanos / 1e6f / ms).coerceIn(0f, 1f)

            override fun getValueFromNanos(playTimeNanos: Long, initialValue: V, targetValue: V, initialVelocity: V): V {
                val u = at(playTimeNanos)
                val e = easing.transform(u)
                val h = u * (1 - u) * (1 - u) * secs
                val a = initialValue.parts(); val b = targetValue.parts(); val v = initialVelocity.parts()
                return vector(a.size) { i -> a[i] + (b[i] - a[i]) * e + v[i] * h }
            }

            override fun getVelocityFromNanos(playTimeNanos: Long, initialValue: V, targetValue: V, initialVelocity: V): V {
                val u = at(playTimeNanos)
                val d = .001f
                val slope = (easing.transform((u + d).coerceAtMost(1f)) - easing.transform((u - d).coerceAtLeast(0f))) / ((u + d).coerceAtMost(1f) - (u - d).coerceAtLeast(0f))
                val h = (1 - u) * (1 - 3 * u)
                val a = initialValue.parts(); val b = targetValue.parts(); val v = initialVelocity.parts()
                return vector(a.size) { i -> (b[i] - a[i]) * slope / secs + v[i] * h }
            }
        }
}

private fun AnimationVector.parts(): FloatArray = when (this) {
    is AnimationVector1D -> floatArrayOf(value)
    is AnimationVector2D -> floatArrayOf(v1, v2)
    is AnimationVector3D -> floatArrayOf(v1, v2, v3)
    is AnimationVector4D -> floatArrayOf(v1, v2, v3, v4)
}

@Suppress("UNCHECKED_CAST")
private fun <V : AnimationVector> vector(size: Int, f: (Int) -> Float): V = when (size) {
    1 -> AnimationVector1D(f(0))
    2 -> AnimationVector2D(f(0), f(1))
    3 -> AnimationVector3D(f(0), f(1), f(2))
    else -> AnimationVector4D(f(0), f(1), f(2), f(3))
} as V

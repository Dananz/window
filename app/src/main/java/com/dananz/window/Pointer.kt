package com.dananz.window

import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isBackPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput

// A mouse plugged into the TV. Each item the remote can reach says its id with Modifier.point; the
// screen that is up (home, quick settings, Window settings, All apps) decides what that id means,
// the same way it does for the remote's arrows and OK. Android draws the pointer itself.

interface Pointing {
    // The pointer came over an item: the remote's focus goes there, as if the arrows had reached it
    fun hover(id: String)
    // The left button went down on an item, and came back up (inside it or not)
    fun press(id: String)
    fun release(id: String, inside: Boolean)
    // Wheel notches: +y toward the viewer (down), +x to the right of the screen
    fun wheel(dx: Int, dy: Int)
    // The right button or the mouse's own Back button
    fun back()
    // A press anywhere that only ends something (the screensaver's preview); true when it took it
    fun anyPress(): Boolean
    // The left button went down on no item at all
    fun miss()
}

class PointerHub {
    var target: Pointing? = null
    // Compose sends hover again after a layout under a still pointer: only a real move counts, or a
    // list that scrolls to its focus would keep chasing the pointer
    internal var moved = false
    internal var last = Offset.Unspecified
    // The item a button went down on: hover waits until it comes back up
    internal var down: String? = null
}

val LocalPointer = staticCompositionLocalOf<PointerHub?> { null }

// On every item the remote can focus. Not touched: its size, its look
fun Modifier.point(id: String, enabled: Boolean = true): Modifier = composed {
    val hub = LocalPointer.current ?: return@composed this
    val on by rememberUpdatedState(enabled)
    pointerInput(id, hub) {
        try {
            awaitPointerEventScope {
                while (true) {
                    val e = awaitPointerEvent()
                    val c = e.changes.firstOrNull() ?: continue
                    val p = hub.target ?: continue
                    when (e.type) {
                        PointerEventType.Enter, PointerEventType.Move ->
                            if (on && !c.isConsumed && hub.down == null && !c.pressed && hub.moved) { c.consume(); p.hover(id) }
                        PointerEventType.Press ->
                            if (on && !c.isConsumed && hub.down == null && (e.buttons.isPrimaryPressed || c.type == PointerType.Touch)) {
                                c.consume(); hub.down = id; p.press(id)
                            }
                        PointerEventType.Release -> if (hub.down == id && !c.pressed) {
                            c.consume(); hub.down = null
                            val inside = c.position.x in 0f..size.width.toFloat() && c.position.y in 0f..size.height.toFloat()
                            p.release(id, inside)
                        }
                    }
                }
            }
        } finally {
            // Gone while held (a tile taken off the wall): let go of it, as a button released elsewhere
            if (hub.down == id) { hub.down = null; hub.target?.release(id, false) }
        }
    }
}

// Once, on the screen's root: notes real moves, and takes the right button, the wheel and the clicks
// that only end something, before any item sees them
fun Modifier.pointerRoot(hub: PointerHub): Modifier = pointerInput(hub) {
    var wx = 0f
    var wy = 0f
    var swallow = false
    awaitPointerEventScope {
        while (true) {
            val e = awaitPointerEvent(PointerEventPass.Initial)
            val c = e.changes.firstOrNull() ?: continue
            val p = hub.target ?: continue
            when (e.type) {
                PointerEventType.Enter, PointerEventType.Move -> { hub.moved = c.position != hub.last; hub.last = c.position }
                PointerEventType.Press -> when {
                    e.buttons.isSecondaryPressed || e.buttons.isBackPressed -> {
                        c.consume(); swallow = true
                        // Back while the left button still holds an item: that press is called off, not let go on it
                        hub.down?.let { d -> hub.down = null; p.release(d, false) }
                        p.back()
                    }
                    p.anyPress() -> { c.consume(); swallow = true }
                    // The same press once the items have had it: none took it, so it fell on the background
                    e.buttons.isPrimaryPressed || c.type == PointerType.Touch -> {
                        val m = awaitPointerEvent(PointerEventPass.Main).changes.firstOrNull()
                        if (m != null && !m.isConsumed) p.miss()
                    }
                }
                PointerEventType.Release -> if (swallow) { c.consume(); if (e.changes.none { it.pressed }) swallow = false }
                PointerEventType.Scroll -> {
                    // Consumed here, or the tiles list would also scroll by itself. A fine-grained wheel
                    // sends parts of a notch: they add up to one step
                    c.consume()
                    wx += c.scrollDelta.x; wy += c.scrollDelta.y
                    val sx = wx.toInt(); val sy = wy.toInt()
                    wx -= sx; wy -= sy
                    if (sx != 0 || sy != 0) p.wheel(sx, sy)
                }
            }
        }
    }
}

package com.dananz.window

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.provider.Settings
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Bold
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.bold.Bluetooth
import com.adamglin.phosphoricons.bold.MoonStars
import com.adamglin.phosphoricons.bold.WifiHigh
import com.adamglin.phosphoricons.fill.Monitor
import com.adamglin.phosphoricons.fill.SpeakerHigh
import com.adamglin.phosphoricons.fill.SpeakerLow
import com.adamglin.phosphoricons.fill.SpeakerX

// Quick settings: what Window can do for the TV itself. Volume and the HDMI inputs work from here;
// Wi-Fi, Bluetooth, picture and sound open their own page of the TV's Settings, since Android lets
// no other app flip them and Google TV's own panel is closed to other apps.

object QuickGlyphs {
    val Wifi = PhosphorIcons.Bold.WifiHigh
    val Bluetooth = PhosphorIcons.Bold.Bluetooth
    val Picture = PhosphorIcons.Fill.Monitor
    val Speaker = PhosphorIcons.Fill.SpeakerHigh
    val Saver = PhosphorIcons.Bold.MoonStars
    fun volume(muted: Boolean, level: Int): ImageVector = when {
        muted || level == 0 -> PhosphorIcons.Fill.SpeakerX
        level < 40 -> PhosphorIcons.Fill.SpeakerLow
        else -> PhosphorIcons.Fill.SpeakerHigh
    }
}

data class Hdmi(val id: String, val name: String, val connected: Boolean)

class TvState(private val context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)

    var volume by mutableIntStateOf(0)
        private set
    var muted by mutableStateOf(false)
        private set
    var inputs by mutableStateOf(emptyList<Hdmi>())
        private set
    var network by mutableStateOf("")
        private set

    // Read again each time the panel opens: the remote's own volume keys change it behind our back
    fun refresh() {
        volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
        muted = audio.isStreamMute(AudioManager.STREAM_MUSIC)
        val tv = context.getSystemService(TvInputManager::class.java)
        inputs = runCatching {
            tv.tvInputList
                .filter { it.type == TvInputInfo.TYPE_HDMI && it.parentId == null }
                .map { Hdmi(it.id, it.loadLabel(context).toString(), tv.getInputState(it.id) == TvInputManager.INPUT_STATE_CONNECTED) }
                .sortedBy { it.name }
        }.getOrDefault(emptyList())
        network = runCatching {
            val net = context.getSystemService(ConnectivityManager::class.java)
            val caps = net.getNetworkCapabilities(net.activeNetwork)
            when {
                caps == null -> t("Not connected")
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> wifiName() ?: t("Connected")
                else -> t("Connected")
            }
        }.getOrDefault(t("Connected"))
    }

    // The network's name, without its quotes; null when Android hides it (no location permission)
    @Suppress("DEPRECATION")
    private fun wifiName(): String? = context.getSystemService(WifiManager::class.java).connectionInfo.ssid
        ?.removeSurrounding("\"")?.takeIf { it.isNotBlank() && it != WifiManager.UNKNOWN_SSID }

    // One step of the TV's own scale, whatever it is, so each press is heard
    fun louder(up: Boolean) {
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0)
        refresh()
    }

    fun mute() {
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, 0)
        refresh()
    }

    fun switchTo(input: Hdmi) = launch(Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(input.id)))

    fun launch(intent: Intent) {
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    }
}

// The settings that open a page of the TV's Settings, as tiles, two to a row
data class SettingTile(val id: String, val name: String, val icon: ImageVector, val action: String)

val SETTING_TILES = listOf(
    SettingTile("qs-wifi", "Wi-Fi", QuickGlyphs.Wifi, Settings.ACTION_WIFI_SETTINGS),
    SettingTile("qs-bt", "Bluetooth", QuickGlyphs.Bluetooth, "com.android.tv.settings.ACCESSORIES_CONNECTED_DEVICES"),
    SettingTile("qs-pic", "Picture", QuickGlyphs.Picture, Settings.ACTION_DISPLAY_SETTINGS),
    SettingTile("qs-sound", "Sound", QuickGlyphs.Speaker, Settings.ACTION_SOUND_SETTINGS),
    // Not a page of the TV's: Window's own screensaver, started right away
    SettingTile("qs-saver", "Screensaver", QuickGlyphs.Saver, ""),
)

// The panel's rows, top to bottom, for the remote
fun quickRows(tv: TvState): List<List<String>> = listOf(
    listOf("qs-vol"),
    listOf("qs-in-home") + tv.inputs.indices.map { "qs-in-$it" },
    listOf("qs-wifi", "qs-bt"),
    listOf("qs-pic", "qs-sound"),
    listOf("qs-saver"),
    listOf("qs-pane", "qs-tvset"),
)

// Left and right along a row; up and down to the next row, keeping the column
fun quickMove(rows: List<List<String>>, id: String, d: Int, across: Boolean): String {
    val r = rows.indexOfFirst { id in it }
    if (r < 0) return rows.first().first()
    val c = rows[r].indexOf(id)
    if (across) return rows[r].getOrElse(c + d) { id }
    val next = rows.getOrNull(r + d) ?: return id
    return next[minOf(c, next.lastIndex)]
}

// What OK does on the panel; false when the id is not one of its own
fun quickOk(tv: TvState, id: String): Boolean {
    when {
        id == "qs-vol" -> tv.mute()
        id == "qs-in-home" -> {}
        id.startsWith("qs-in-") -> tv.inputs.getOrNull(id.removePrefix("qs-in-").toInt())?.let(tv::switchTo)
        id == "qs-tvset" -> tv.launch(Intent(Settings.ACTION_SETTINGS))
        else -> SETTING_TILES.firstOrNull { it.id == id }?.let { tv.launch(Intent(it.action)) } ?: return false
    }
    return true
}

private val PANEL = RoundedCornerShape(40.dp)
private val BOX = RoundedCornerShape(24.dp)

// The gear before the place pin that opens the panel
@Composable
fun QuickButton(focused: Boolean) {
    Box(
        Modifier.size(96.dp).ring(focused, 22.dp).clip(RoundedCornerShape(22.dp)).background(Ink.glass),
        contentAlignment = Alignment.Center,
    ) { Icon(Glyphs.Gear, null, Modifier.size(30.dp), tint = Ink.text) }
}

// Down the right side, where Google TV keeps its own: volume, inputs, the settings, All settings.
// It slides in from the edge; nothing is drawn while it is away.
@Composable
fun QuickPanel(open: Boolean, focus: String, tv: TvState) {
    val shown by animateFloatAsState(if (open) 1f else 0f, settle(Motion.SHEET), label = "quick")
    val sign = if (androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl) -1 else 1
    if (!open && shown == 0f) return
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
        Column(
            Modifier
                .padding(32.dp)
                .width(540.dp)
                .fillMaxHeight()
                .graphicsLayer {
                    translationX = sign * (1f - shown) * 600.dp.toPx()
                    alpha = shown
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                }
                .clip(PANEL)
                // Solid: nothing shows through it, and an opaque fill is the cheapest for this GPU
                .background(Color(0xFF131316))
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Text(t("Quick settings"), color = Ink.text, fontFamily = Syne, fontWeight = FontWeight.SemiBold, fontSize = 34.sp)
                Spacer(Modifier.weight(1f))
                Text(t(if (focus == "qs-vol") "◀ ▶ volume · OK mute" else "Back to close"), color = Ink.text.copy(alpha = .55f), fontFamily = Golos, fontSize = 17.sp)
            }
            VolumeBar(tv, focus == "qs-vol")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InputChip("Window", focus == "qs-in-home", on = true, live = true, Modifier.weight(1f))
                tv.inputs.forEachIndexed { i, input -> InputChip(input.name, focus == "qs-in-$i", on = false, live = input.connected, Modifier.weight(1f)) }
            }
            SETTING_TILES.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pair.forEach { tile ->
                        val status = when (tile.id) { "qs-wifi" -> tv.network; "qs-bt" -> t("Remotes & accessories"); "qs-pic" -> t("Picture settings"); "qs-saver" -> t("Start now"); else -> t("Sound output") }
                        SettingBox(tile, status, focus == tile.id, Modifier.weight(1f))
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            // Window's own settings, and the TV's
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(Triple("qs-pane", "Window settings", Glyphs.Home), Triple("qs-tvset", "TV settings", Glyphs.Tv)).forEach { (id, label, icon) ->
                    Row(
                        Modifier.weight(1f).height(60.dp).ring(focus == id, 20.dp).clip(RoundedCornerShape(20.dp)).background(Color.White.copy(alpha = if (id == "qs-pane") .1f else .07f)),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(icon, null, Modifier.size(22.dp), tint = Ink.text)
                        Spacer(Modifier.width(12.dp))
                        Text(t(label), color = Ink.text, fontFamily = Golos, fontWeight = FontWeight.Medium, fontSize = 19.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun VolumeBar(tv: TvState, focused: Boolean) {
    val level = if (tv.muted) 0 else tv.volume
    val fill by animateFloatAsState(level / 100f, settle(Motion.FILL), label = "volume")
    Row(
        Modifier.fillMaxWidth().height(84.dp).ring(focused, 24.dp).clip(BOX).background(Color.White.copy(alpha = .08f)).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(QuickGlyphs.volume(tv.muted, level), null, Modifier.size(26.dp), tint = Ink.text)
        Spacer(Modifier.width(16.dp))
        Box(Modifier.weight(1f).height(12.dp).clip(CircleShape).background(Color.White.copy(alpha = .12f))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth().graphicsLayer {
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, .5f)
                scaleX = fill
            }.background(Ink.text, CircleShape))
        }
        Spacer(Modifier.width(16.dp))
        Text("$level", Modifier.width(48.dp), color = Ink.text, fontFamily = Golos, fontWeight = FontWeight.Medium, fontSize = 22.sp)
    }
}

@Composable
private fun InputChip(name: String, focused: Boolean, on: Boolean, live: Boolean, modifier: Modifier) {
    Box(
        modifier.height(56.dp).ring(focused, 18.dp).clip(RoundedCornerShape(18.dp)).background(if (on) Ink.accent else Color.White.copy(alpha = .07f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name, maxLines = 1,
            color = if (on) Color(0xFF1B1308) else Ink.text.copy(alpha = if (live) 1f else .45f),
            fontFamily = Golos, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium, fontSize = 18.sp,
        )
    }
}

@Composable
private fun SettingBox(tile: SettingTile, status: String, focused: Boolean, modifier: Modifier) {
    Column(
        modifier.height(112.dp).ring(focused, 24.dp).clip(BOX).background(Color.White.copy(alpha = .07f)).padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = .1f)), contentAlignment = Alignment.Center) {
            Icon(tile.icon, null, Modifier.size(22.dp), tint = Ink.text)
        }
        Column {
            Text(t(tile.name), color = Ink.text, fontFamily = Golos, fontWeight = FontWeight.Medium, fontSize = 19.sp, lineHeight = 22.sp)
            Text(status, maxLines = 1, color = Ink.text.copy(alpha = .6f), fontFamily = Golos, fontSize = 15.sp, lineHeight = 18.sp)
        }
    }
}

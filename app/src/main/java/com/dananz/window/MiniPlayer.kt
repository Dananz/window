package com.dananz.window

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

// What plays on while the home screen is up (Spotify, YouTube Music: apps that keep playing behind it),
// for the small player under the clock. Its own reading of the media sessions, apart from the
// screensaver's Music: a song paused from here stays, so it can be played again
object Player {
    var song by mutableStateOf<Song?>(null)
        private set
    var playing by mutableStateOf(false)
        private set

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var manager: MediaSessionManager? = null
    private var controllers = emptyList<MediaController>()
    // The session the song comes from: kept while it pauses, until it ends or the home screen goes
    private var source: MediaController? = null
    private var reading: Job? = null
    private var artOf: String? = null
    private var app: Context? = null

    private val changed = MediaSessionManager.OnActiveSessionsChangedListener { list -> follow(list.orEmpty()) }
    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = pick()
        override fun onPlaybackStateChanged(state: PlaybackState?) = pick()
        override fun onSessionDestroyed() = pick()
    }

    // While the home screen shows
    fun start(context: Context) {
        if (manager != null) return
        app = context.applicationContext
        Music.grant(context)
        val m = context.getSystemService(MediaSessionManager::class.java) ?: return
        val me = ComponentName(context, NowPlaying::class.java)
        runCatching {
            m.addOnActiveSessionsChangedListener(changed, me, main)
            manager = m
            follow(m.getActiveSessions(me))
        }.onFailure { Log.w("Window", "mini player can't read the media sessions", it) }
    }

    fun stop() {
        runCatching { manager?.removeOnActiveSessionsChangedListener(changed) }
        manager = null
        follow(emptyList())
        reading?.cancel()
        source = null; song = null; playing = false; artOf = null
    }

    private fun follow(list: List<MediaController>) {
        controllers.forEach { it.unregisterCallback(callback) }
        controllers = list
        list.forEach { it.registerCallback(callback, main) }
        pick()
    }

    // The first session playing, else the one shown before while it is only paused. A song paused in its
    // own app before Home isn't shown: that is a video waiting in YouTube, not music playing on
    private fun pick() {
        val kept = source?.let { s -> controllers.firstOrNull { it.sessionToken == s.sessionToken } }
        val c = controllers.firstOrNull { it.playbackState?.state in PLAYING }
            ?: kept?.takeIf { it.playbackState?.state == PlaybackState.STATE_PAUSED }
        val md = c?.metadata
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
            ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)?.takeIf { it.isNotBlank() }
        source = c?.takeIf { title != null }
        playing = c?.playbackState?.state in PLAYING
        if (c == null || md == null || title == null) { song = null; artOf = null; return }
        val artist = listOf(MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM_ARTIST, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_AUTHOR)
            .firstNotNullOfOrNull { md.getString(it)?.takeIf { s -> s.isNotBlank() } }.orEmpty()
        val now = Song(title, artist, null)
        val same = song?.key == now.key
        if (!same) song = now
        val bitmap = listOf(MediaMetadata.METADATA_KEY_ALBUM_ART, MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            .firstNotNullOfOrNull { md.getBitmap(it) }
        val uri = listOf(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
            .firstNotNullOfOrNull { md.getString(it)?.takeIf { s -> s.isNotBlank() } }
        val from = "${now.key}|${bitmap?.generationId ?: uri}"
        if (from == artOf && same) return
        artOf = from
        if (bitmap == null && uri == null) return
        reading?.cancel()
        reading = scope.launch {
            val art = withContext(Dispatchers.IO) { runCatching { bitmap?.let(::small) ?: read(uri!!) }.getOrNull() } ?: return@launch
            if (song?.key == now.key) song = song?.copy(art = art)
        }
    }

    // OK on the player
    fun toggle() {
        val c = source ?: return
        if (c.playbackState?.state in PLAYING) c.transportControls.pause() else c.transportControls.play()
    }

    fun next() { source?.transportControls?.skipToNext() }

    // OK held on the player: the app it plays in, at its own player screen when it says where that is
    fun open(context: Context) {
        val c = source ?: return
        val shown = c.sessionActivity?.let { pi ->
            runCatching {
                val o = ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                pi.send(context, 0, null, null, null, null, o.toBundle())
            }.isSuccess
        } ?: false
        if (shown) return
        val pm = context.packageManager
        val launch = pm.getLeanbackLaunchIntentForPackage(c.packageName) ?: pm.getLaunchIntentForPackage(c.packageName) ?: return
        runCatching { context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure { Log.w("Window", "open ${c.packageName}", it) }
    }

    private fun read(uri: String): ImageBitmap? {
        val bytes = when {
            uri.startsWith("http") -> URL(uri).openConnection().run { connectTimeout = 5_000; readTimeout = 5_000; getInputStream().use { it.readBytes() } }
            else -> app?.contentResolver?.openInputStream(android.net.Uri.parse(uri))?.use { it.readBytes() }
        } ?: return null
        val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        return small(b)
    }

    // Square, cut from the middle, at twice the player's art size
    private fun small(b: Bitmap): ImageBitmap {
        val side = (ART_SIZE * 2).toInt()
        val crop = minOf(b.width, b.height)
        val src = if (b.config == Bitmap.Config.HARDWARE) b.copy(Bitmap.Config.ARGB_8888, false) else b
        val square = Bitmap.createBitmap(src, (b.width - crop) / 2, (b.height - crop) / 2, crop, crop)
        return Bitmap.createScaledBitmap(square, side, side, true).asImageBitmap()
    }

    private val PLAYING = setOf(
        PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING,
        PlaybackState.STATE_SKIPPING_TO_NEXT, PlaybackState.STATE_SKIPPING_TO_PREVIOUS, PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM,
    )
}

// A song as the player shows it: the art comes on its own, sometimes after the words
data class Song(val title: String, val artist: String, val art: ImageBitmap?) {
    val key get() = "$title\u0000$artist"
}

private const val ART_SIZE = 72f

// Under the clock and the date: the song, and play or pause in the same pill; Next beside it.
// OK on the pill plays or pauses, held it opens the app
@Composable
fun MiniPlayer(focus: String, dim: Boolean) {
    val song = Player.song
    val shown by animateFloatAsState(if (song != null) 1f else 0f, settle(Motion.SHEET), label = "player")
    val faded by animateFloatAsState(if (dim) .55f else 1f, glide(Motion.DIM), label = "player-dim")
    var last by remember { mutableStateOf<Song?>(null) }
    if (song != null) last = song
    val t = last ?: return
    if (shown == 0f) return
    Row(
        Modifier.offset(72.dp, 248.dp).graphicsLayer {
            alpha = shown * faded
            translationY = (1 - shown) * 12.dp.toPx()
        },
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.ring(focus == PLAYER, 28.dp).clip(RoundedCornerShape(28.dp)).background(Ink.glass).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(ART_SIZE.dp).clip(RoundedCornerShape(16.dp)).background(Ink.text.copy(alpha = .12f))) {
                t.art?.let { Image(it, null, Modifier.size(ART_SIZE.dp)) }
            }
            Column(Modifier.widthIn(max = 340.dp)) {
                Text(t.title, color = Ink.text, fontFamily = Golos, fontWeight = FontWeight.Medium, fontSize = 21.sp, lineHeight = 26.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (t.artist.isNotEmpty()) Text(t.artist, color = Ink.text.copy(alpha = .65f), fontFamily = Golos, fontSize = 17.sp, lineHeight = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(if (Player.playing) Glyphs.Pause else Glyphs.Play, null, Modifier.padding(end = 8.dp).size(30.dp), tint = Ink.text)
        }
        Box(
            Modifier.size(64.dp).ring(focus == PLAYER_NEXT, 32.dp).clip(RoundedCornerShape(32.dp)).background(Ink.glass),
            contentAlignment = Alignment.Center,
        ) { Icon(Glyphs.Next, null, Modifier.size(26.dp).graphicsLayer { if (Lang.he) scaleX = -1f }, tint = Ink.text) }
    }
}

const val PLAYER = "player"
const val PLAYER_NEXT = "player-next"

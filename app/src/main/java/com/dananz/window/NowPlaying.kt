package com.dananz.window

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

// Never runs on its own: Android only lets an app read what other apps are playing (their media
// sessions) while one of its notification listeners is switched on. Window turns it on itself with
// WRITE_SECURE_SETTINGS, or `cmd notification allow_listener com.dananz.window/.NowPlaying`
class NowPlaying : NotificationListenerService()

// A song as the screensaver shows it: the art comes on its own, sometimes after the words
// light: whether the cover is light where the sound bars sit, in its bottom left and bottom right corners
data class Track(val title: String, val artist: String, val art: ImageBitmap?, val lightLeft: Boolean = false, val lightRight: Boolean = false) {
    val key get() = "$title\u0000$artist"
}

// What any app is playing (Spotify, YouTube Music, YouTube, AirPlay when its receiver tells Android),
// read from the TV's media sessions while the screensaver is up
object Music {
    var track by mutableStateOf<Track?>(null)
        private set
    // Playing right now: false the moment the player pauses, even while the song is still shown
    var playing by mutableStateOf(false)
        private set
    // How loud each of the sound bars' bands is (bass, low mids, high mids, treble), 0 to 1, read from what
    // the TV is playing; heard: the TV has given any sound at all (some won't, and the bars then make their own)
    val bands = FloatArray(4)
    var heard = false
        private set
    private var ears: android.media.audiofx.Visualizer? = null
    private val peaks = FloatArray(4) { 30f }

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var manager: MediaSessionManager? = null
    private var controllers = emptyList<MediaController>()
    private var users = 0
    private var gone: Job? = null
    private var reading: Job? = null
    private var artOf: String? = null
    private var app: Context? = null

    private fun me(context: Context) = ComponentName(context, NowPlaying::class.java)

    // Switch the listener on, the way Keys.repair does the Home button
    fun grant(context: Context) {
        if (context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return
        val me = me(context).flattenToString()
        val cr = context.contentResolver
        val on = Settings.Secure.getString(cr, "enabled_notification_listeners").orEmpty()
        if (on.split(':').any { ComponentName.unflattenFromString(it) == me(context) }) return
        runCatching { Settings.Secure.putString(cr, "enabled_notification_listeners", if (on.isBlank()) me else "$on:$me") }
    }

    private val changed = MediaSessionManager.OnActiveSessionsChangedListener { list -> follow(list.orEmpty()) }
    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = pick()
        override fun onPlaybackStateChanged(state: PlaybackState?) = pick()
        override fun onSessionDestroyed() = pick()
    }

    // From when the screensaver (or its preview) shows until it ends
    fun start(context: Context) {
        if (users++ > 0) return
        app = context.applicationContext
        grant(context)
        val m = context.getSystemService(MediaSessionManager::class.java) ?: return
        runCatching {
            m.addOnActiveSessionsChangedListener(changed, me(context), main)
            manager = m
            follow(m.getActiveSessions(me(context)))
        }
        listen(context)
    }

    // The spectrum of everything the TV plays (the output mix, Visualizer session 0): levels only, never the
    // sound itself. Needs RECORD_AUDIO, granted over adb by the install script
    private fun listen(context: Context) {
        if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        runCatching {
            val v = android.media.audiofx.Visualizer(0)
            v.enabled = false
            v.captureSize = android.media.audiofx.Visualizer.getCaptureSizeRange()[1].coerceAtMost(1024)
            v.scalingMode = android.media.audiofx.Visualizer.SCALING_MODE_NORMALIZED
            v.setDataCaptureListener(object : android.media.audiofx.Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(v: android.media.audiofx.Visualizer, b: ByteArray, rate: Int) {}
                override fun onFftDataCapture(v: android.media.audiofx.Visualizer, fft: ByteArray, rate: Int) = hear(fft, rate)
            }, android.media.audiofx.Visualizer.getMaxCaptureRate(), false, true)
            v.enabled = true
            ears = v
        }.onFailure { android.util.Log.w("Window", "sound bars can't hear the TV", it) }
    }

    // Each band's loudest bin in decibels, against the loudest it has been lately (falling back 1 dB a
    // second at 20 readings a second): quiet songs fill the bars as loud ones do, and silence is a row of dots.
    // Another app listening to the same output (a second session 0 Visualizer) takes it over until it stops
    private fun hear(fft: ByteArray, rate: Int) {
        val n = fft.size / 2
        val hz = rate / 1000f / fft.size
        val edges = floatArrayOf(40f, 160f, 800f, 3_000f, 10_000f)
        for (b in 0 until 4) {
            val from = (edges[b] / hz).toInt().coerceIn(1, n - 1)
            val to = (edges[b + 1] / hz).toInt().coerceIn(from + 1, n)
            // The band's energy, the mean over its bins: steadier than its loudest bin
            var power = 0f
            for (k in from until to) { val re = fft[2 * k].toFloat(); val im = fft[2 * k + 1].toFloat(); power += re * re + im * im }
            power /= (to - from)
            if (power > 0f && !heard) { heard = true; android.util.Log.d("Window", "sound bars hear the TV") }
            val db = 10 * kotlin.math.log10(maxOf(power, 1f))
            peaks[b] = maxOf(db, peaks[b] - .02f, 24f)
            // The last RANGE decibels under the band's peak fill the bar, and the readings are eased into each
            // other (up faster than down), so the bars breathe with the music rather than flicker
            val level = if (db < FLOOR) 0f else ((db - (peaks[b] - RANGE)) / RANGE).coerceIn(0f, 1f)
            bands[b] += (level - bands[b]) * (if (level > bands[b]) .35f else .12f)
        }
    }
    // Silence (paused, or no sound between songs): the sound bars stay as dots QUIET_HOLD seconds, then fade
    // away over QUIET_FADE, so they're gone 10 s in; sound again brings them back in a third of a second.
    // Kept here, by the clock, so a new song coming in during the silence carries on where the last one was
    private const val QUIET_HOLD = 4f
    private const val QUIET_FADE = 6f
    private var silentSince = 0L
    private var loudSince = 0L
    private var shownAtLoud = 1f

    fun barsShown(ns: Long): Float =
        if (silentSince != 0L) 1 - (((ns - silentSince) / 1e9f - QUIET_HOLD) / QUIET_FADE).coerceIn(0f, 1f)
        else minOf(1f, shownAtLoud + (ns - loudSince) / 1e9f / .3f)

    // Told each frame by the bars (any number of them: the same time gives the same answer)
    fun quiet(ns: Long, silent: Boolean) {
        if (silent && silentSince == 0L) silentSince = ns
        else if (!silent && silentSince != 0L) { shownAtLoud = barsShown(ns); silentSince = 0L; loudSince = ns }
    }

    // Below this many decibels a band is silent; a bar spans this many under its band's recent loudest
    private const val FLOOR = 6f
    private const val RANGE = 30f

    fun stop() {
        if (users == 0 || --users > 0) return
        runCatching { manager?.removeOnActiveSessionsChangedListener(changed) }
        manager = null
        runCatching { ears?.release() }
        ears = null; heard = false; bands.fill(0f)
        silentSince = 0L; shownAtLoud = 1f
        follow(emptyList())
        gone?.cancel(); reading?.cancel()
        track = null; artOf = null
    }

    private fun follow(list: List<MediaController>) {
        controllers.forEach { it.unregisterCallback(callback) }
        controllers = list
        list.forEach { it.registerCallback(callback, main) }
        pick()
    }

    // The first session that is playing (Android lists the one it would send the play key to first)
    private fun pick() {
        val c = controllers.firstOrNull { it.playbackState?.state in PLAYING }
        playing = c?.playbackState?.state == PlaybackState.STATE_PLAYING
        val md = c?.metadata
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
            ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)?.takeIf { it.isNotBlank() }
        if (c == null || md == null || title == null) {
            // Why the song went: what each session says, for the next time the corner blinks between songs
            if (track != null) android.util.Log.d("Window", "now playing gone: " + controllers.joinToString { "${it.packageName}=${it.playbackState?.state}" })
            // Between two songs a player may pause, buffer or skip for a few seconds: only gone once it stays gone
            if (track != null && gone?.isActive != true) gone = scope.launch { delay(8_000); track = null; artOf = null }
            return
        }
        gone?.cancel()
        val artist = listOf(MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM_ARTIST, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_AUTHOR)
            .firstNotNullOfOrNull { md.getString(it)?.takeIf { s -> s.isNotBlank() } }.orEmpty()
        val now = Track(title, artist, null)
        // The art: a picture in the session, else an address to read it from
        val bitmap = listOf(MediaMetadata.METADATA_KEY_ALBUM_ART, MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            .firstNotNullOfOrNull { md.getBitmap(it) }
        val uri = listOf(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
            .firstNotNullOfOrNull { md.getString(it)?.takeIf { s -> s.isNotBlank() } }
        val source = "${now.key}|${bitmap?.generationId ?: uri}"
        if (source == artOf) return
        artOf = source
        reading?.cancel()
        if (bitmap == null && uri == null) { if (track?.key != now.key) track = now; return }
        // A new song waits for its cover a moment, so the two come in together and no blank square flashes
        // between songs; a cover slower than that follows on its own
        val fresh = track?.key != now.key
        reading = scope.launch {
            if (fresh) launch { delay(1_500); if (track?.key != now.key) track = now }
            val art = withContext(Dispatchers.IO) { runCatching { bitmap?.let(::small) ?: read(uri!!) }.getOrNull() }
            if (art == null) { if (track?.key != now.key) track = now; return@launch }
            val light = withContext(Dispatchers.Default) { light(art) }
            track = (track?.takeIf { it.key == now.key } ?: now).copy(art = art.asImageBitmap(), lightLeft = light.first, lightRight = light.second)
        }
    }

    // Whether the cover is light under the sound bars (its bottom corners, where Song puts them), so they
    // can be black there and white on a dark one
    private fun light(b: Bitmap): Pair<Boolean, Boolean> {
        val k = b.width / ART.toFloat()
        val w = (40 * k).toInt(); val h = (30 * k).toInt()
        val y = b.height - (14 * k).toInt() - h
        fun luma(x: Int): Float {
            val px = IntArray(w * h)
            b.getPixels(px, 0, w, x, y, w, h)
            return px.map { (.2126f * (it shr 16 and 255) + .7152f * (it shr 8 and 255) + .0722f * (it and 255)) / 255f }.average().toFloat()
        }
        return (luma((14 * k).toInt()) > .62f) to (luma(b.width - (14 * k).toInt() - w) > .62f)
    }

    private fun read(uri: String): Bitmap? {
        val bytes = when {
            uri.startsWith("http") -> URL(uri).openConnection().run { connectTimeout = 5_000; readTimeout = 5_000; getInputStream().use { it.readBytes() } }
            else -> app?.contentResolver?.openInputStream(android.net.Uri.parse(uri))?.use { it.readBytes() }
        } ?: return null
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let(::small)
    }

    // The art as the screensaver draws it: square, cut from the middle, at twice its design size (a 4K
    // panel's pixels). Square to the corners: the cover's box rounds them, so two covers sliding meet edge to edge
    private fun small(b: Bitmap): Bitmap {
        val side = ART * 2
        val crop = minOf(b.width, b.height)
        val from = android.graphics.Rect((b.width - crop) / 2, (b.height - crop) / 2, (b.width + crop) / 2, (b.height + crop) / 2)
        val out = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
        val canvas = android.graphics.Canvas(out)
        val src = if (b.config == Bitmap.Config.HARDWARE) b.copy(Bitmap.Config.ARGB_8888, false) else b
        canvas.drawBitmap(src, from, android.graphics.Rect(0, 0, side, side), paint)
        return out
    }

    // On the way to the next song counts as playing
    private val PLAYING = setOf(
        PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING,
        PlaybackState.STATE_SKIPPING_TO_NEXT, PlaybackState.STATE_SKIPPING_TO_PREVIOUS, PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM,
    )
}

package com.dananz.window

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

// Google TV sends Home to its own launcher whichever app holds the Home role, so the remote's Home
// is caught here, before the system acts on it, and brings Window up instead, as it was left. With
// Google TV's home switched off it also answers the buttons only that home answered: Input and the
// gear open Window's quick settings. Switched on as an accessibility service (Settings, or `settings
// put secure enabled_accessibility_services`).
class HomeButton : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())

    // Waking from standby, Google TV puts its own home up first, and again a moment later as it
    // settles: Window comes up now and once more after that
    private val wake = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            home()
            main.postDelayed({ home() }, 2_000)
            opening()
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val panel = when (event.keyCode) {
            KeyEvent.KEYCODE_HOME -> null
            KeyEvent.KEYCODE_TV_INPUT, KeyEvent.KEYCODE_TV -> PANEL_INPUTS
            KeyEvent.KEYCODE_SETTINGS -> PANEL_QUICK
            else -> return false
        }
        if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) home(panel)
        return true
    }

    // Just after the TV starts, Google TV's home comes up first: Window takes over from it
    override fun onServiceConnected() {
        Keys.wanted(this, true)
        registerReceiver(wake, IntentFilter(Intent.ACTION_SCREEN_ON), RECEIVER_NOT_EXPORTED)
        Prefs.load(this)
        if (SystemClock.elapsedRealtime() < 3 * 60_000) { home(); opening() }
    }

    // Open on start (Window settings): once Window is up, the chosen app opens over it
    private fun opening() {
        val pkg = Prefs.startApp ?: return
        main.postDelayed({
            val pm = packageManager
            val launch = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg) ?: return@postDelayed
            runCatching { startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }, 3_000)
    }

    // Unbound while the app is alive: someone switched it off in Settings, so it stays off. A force
    // stop or an update kills the app first and never gets here, and Window puts the service back
    override fun onUnbind(intent: Intent?): Boolean {
        Keys.wanted(this, false)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(wake) }
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun home(panel: String? = null) = startActivity(
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(PANEL, panel),
    )

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    companion object {
        const val PANEL = "panel"
        const val PANEL_QUICK = "quick"
        const val PANEL_INPUTS = "inputs"
    }
}

// Android takes an app's accessibility service off when the app is force-stopped or updated. With
// WRITE_SECURE_SETTINGS (granted once over adb by the install script) Window switches it back on
// whenever it starts, unless it was switched off on purpose.
object Keys {
    private fun prefs(context: Context) = context.getSharedPreferences("home", Context.MODE_PRIVATE)

    fun wanted(context: Context, on: Boolean) = prefs(context).edit().putBoolean("keys", on).apply()

    fun repair(context: Context) {
        if (!prefs(context).getBoolean("keys", false)) return
        if (context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return
        val me = ComponentName(context, HomeButton::class.java).flattenToString()
        val cr = context.contentResolver
        val on = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        if (on.split(':').any { it.equals(me, ignoreCase = true) }) return
        runCatching {
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, if (on.isBlank()) me else "$on:$me")
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        }
    }
}

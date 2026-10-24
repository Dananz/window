package com.dananz.window

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.provider.Settings
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

// One card in the apps row: an installed app with the banner it ships for the TV launcher,
// or one of the home's own shortcuts (back to Google TV, Settings)
data class TvApp(
    val id: String,
    val name: String,
    val banner: ImageBitmap?,
    val color: Color,
    val intent: Intent?,
    val shortcut: Shortcut? = null,
    // No banner of its own: the app's icon, drawn small in the middle of the card
    val iconOnly: Boolean = false,
    // Shipped with the TV: it can't be uninstalled
    val system: Boolean = false,
)

enum class Shortcut { AllApps, GoogleTv, Settings }

// Tomer's order, the favorites until they are changed on the All apps screen
private val PINNED = listOf(
    "ar.tvplayer.tv",
    "com.google.android.youtube.tv",
    "com.netflix.ninja",
    "com.plexapp.android",
    "com.google.android.youtube.tvmusic",
    "com.disneyplus.mea",
    "com.apple.atve.androidtv.appletv",
)

// Names the apps don't give themselves in a way that reads well on the home row
private val NAMES = mapOf("ar.tvplayer.tv" to "TiviMate", "com.tcl.tv" to "Live TV")

// The card colour behind a banner, matched to the banner's own background
private val COLORS = mapOf(
    "ar.tvplayer.tv" to Color.White,
    "com.google.android.youtube.tv" to Color(0xFFF2F2F2),
    "com.netflix.ninja" to Color.White,
    "com.plexapp.android" to Color.Black,
    "com.google.android.youtube.tvmusic" to Color.White,
    "com.disneyplus.mea" to Color(0xFF0A2A3D),
    "com.apple.atve.androidtv.appletv" to Color(0xFF1C1C1E),
    "com.tcl.tv" to Color(0xFFD71920),
)

const val GOOGLE_TV = "com.google.android.apps.tv.launcherx"
// The apps on the home shelf, in their saved order, then the home's own cards
fun loadApps(context: Context): List<TvApp> {
    val pm = context.packageManager
    val launchable = pm.queryIntentActivities(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0,
    ).associateBy { it.activityInfo.packageName }
    val apps = Favorites.read(context).mapNotNull { pkg -> launchable[pkg]?.let { tvApp(pm, it, 640, 360, Color(0xFF1C1C1E)) } }
    return apps + shortcuts(context)
}

// The home's own cards at the end of the shelf: All apps, Google TV while it is enabled, Settings
fun shortcuts(context: Context): List<TvApp> {
    val pm = context.packageManager
    val all = TvApp("all", "All apps", null, Color(0xFF1B1D22), null, Shortcut.AllApps)
    // Just in case: the stock home while it is still enabled
    val googleTv = runCatching { pm.getApplicationInfo(GOOGLE_TV, 0).enabled }.getOrDefault(false).takeIf { it }?.let {
        TvApp(
            "googletv", "Google TV home", null, Color(0xFF1B1D22),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(GOOGLE_TV)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            Shortcut.GoogleTv,
        )
    }
    val settings = TvApp("settings", "Settings", null, Color(0xFF1B1D22), Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), Shortcut.Settings)
    return listOfNotNull(all, googleTv, settings)
}

// Every app the TV can launch, A to Z, for the All apps screen
fun loadAllApps(context: Context): List<TvApp> {
    val pm = context.packageManager
    return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)
        .filter { it.activityInfo.packageName != context.packageName }
        .map { tvApp(pm, it, 480, 270, Color(0xFF24262C)) }
        .sortedBy { it.name.lowercase() }
}

// One launchable app: its banner at the given size, or its icon when it ships none
private fun tvApp(pm: PackageManager, info: ResolveInfo, w: Int, h: Int, fallback: Color): TvApp {
    val pkg = info.activityInfo.packageName
    val banner = info.activityInfo.loadBanner(pm) ?: info.activityInfo.applicationInfo.loadBanner(pm)
    return TvApp(
        id = pkg,
        name = NAMES[pkg] ?: info.loadLabel(pm).toString(),
        banner = banner?.toBitmap(w, h)?.asImageBitmap() ?: info.loadIcon(pm)?.toBitmap(160, 160)?.asImageBitmap(),
        color = COLORS[pkg] ?: fallback,
        intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
            .setComponent(ComponentName(pkg, info.activityInfo.name))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
        iconOnly = banner == null,
        system = info.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0,
    )
}

// The home shelf's apps and the All apps screen's own order, as package names, kept across restarts
object Favorites {
    private fun prefs(context: Context) = context.getSharedPreferences("home", Context.MODE_PRIVATE)
    private fun list(s: String?) = s?.split(",")?.filter { it.isNotEmpty() }

    fun read(context: Context): List<String> = list(prefs(context).getString("favorites", null)) ?: PINNED
    fun write(context: Context, pkgs: List<String>) = prefs(context).edit().putString("favorites", pkgs.joinToString(",")).apply()

    // Every app's place on the All apps screen; empty until something is moved
    fun order(context: Context): List<String> = list(prefs(context).getString("order", null)) ?: emptyList()
    fun writeOrder(context: Context, pkgs: List<String>) = prefs(context).edit().putString("order", pkgs.joinToString(",")).apply()
}

fun isInstalled(pm: PackageManager, pkg: String) = runCatching { pm.getPackageInfo(pkg, 0) }.isSuccess

package com.dananz.window

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONArray

// Sets the tiles on the wall from a computer, for setting up a TV without the remote:
// adb shell am broadcast -n com.dananz.window/.TilesReceiver --es tiles '[{"entity":"light.x","name":"X","kind":"Light"}]'
// Only senders holding DUMP (adb's shell, the system) get here: see the manifest
class TilesReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val json = intent.getStringExtra("tiles") ?: return
        val tiles = runCatching {
            val a = JSONArray(json)
            (0 until a.length()).map { a.getJSONObject(it).let { o -> Tile(o.getString("entity"), o.getString("name"), Kind.valueOf(o.getString("kind"))) } }
        }.getOrElse { resultCode = 1; resultData = "bad tiles: ${it.message}"; return }
        Prefs.load(context)
        Prefs.changeTiles(tiles)
        resultData = "${tiles.size} tiles"
    }
}

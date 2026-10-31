package dev.tvremote.phone

import dev.tvremote.phone.scenes.SceneStore
import dev.tvremote.phone.ui.label
import dev.tvremote.phone.ui.loadLang

import dev.tvremote.core.T
import android.annotation.TargetApi
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings tiles (pull down the notification shade, edit, drag them in): the TV's power, and the first scene. They act on
 * the TV used last, through [SceneActivity], without opening the remote.
 */
@TargetApi(24)
abstract class TvTile : TileService() {
    abstract fun job(i: Intent): Intent?

    override fun onClick() {
        val i = job(Intent(this, SceneActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ?: Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)   // nothing to run yet: open the app
        if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        else @Suppress("DEPRECATION") startActivityAndCollapse(i)
    }

    override fun onStartListening() {
        loadLang(this)
        val tile = qsTile ?: return
        paint(tile)
        tile.updateTile()
    }

    open fun paint(tile: Tile) { tile.state = Tile.STATE_INACTIVE }
}

@TargetApi(24)
class PowerTile : TvTile() {
    override fun job(i: Intent): Intent = i.putExtra("do", "power")
    override fun paint(tile: Tile) { super.paint(tile); tile.label = T("tiles.tv_power") }
}

/** Runs the first scene in the list; its name is the tile's label. */
@TargetApi(24)
class SceneTile : TvTile() {
    override fun job(i: Intent): Intent? = SceneStore.list(this).firstOrNull()?.let { i.putExtra("scene", it.optString("id")) }
    override fun paint(tile: Tile) {
        super.paint(tile)
        val sc = SceneStore.list(this).firstOrNull()
        tile.label = sc?.let { (it.optString("icon") + " " + it.optString("name")).trim() } ?: T("tiles.tv_scene")
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = if (sc == null) T("tiles.make_one_in_the") else "TV Remote"
    }
}

/**
 * The scenes changed: the tile shows the new first one next time the shade opens. Named by a string: before Android 7 there
 * are no tiles, and touching the class itself there would fail.
 */
internal fun refreshSceneTile(c: Context) {
    if (Build.VERSION.SDK_INT >= 24) try { TileService.requestListeningState(c, ComponentName(c.packageName, "dev.tvremote.phone.SceneTile")) } catch (ignored: Exception) { }
}

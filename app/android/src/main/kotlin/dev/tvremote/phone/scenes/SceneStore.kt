package dev.tvremote.phone.scenes

import dev.tvremote.phone.R
import dev.tvremote.phone.RemoteWidget
import dev.tvremote.phone.SceneActivity
import dev.tvremote.phone.picker.save
import dev.tvremote.phone.refreshSceneTile
import dev.tvremote.phone.ui.icon

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build
import dev.tvremote.core.scenes.Scenes
import org.json.JSONArray
import org.json.JSONObject

/**
 * This phone's scenes (see Scenes.kt for what a scene is). Edited on the Automation tab of the Manage screen (the web page),
 * run from the remote's 🎬 button, the home screen widget, the Quick Settings tile and the app icon's shortcuts; all of those
 * are refreshed here whenever the list changes.
 */
internal object SceneStore {
    private fun prefs(c: Context) = c.getSharedPreferences("scenes", Context.MODE_PRIVATE)

    fun all(c: Context): JSONArray = try { JSONArray(prefs(c).getString("list", "[]")) } catch (e: Exception) { JSONArray() }

    fun list(c: Context): List<JSONObject> = all(c).let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }

    fun find(c: Context, id: String): JSONObject? = list(c).firstOrNull { it.optString("id") == id }

    fun save(c: Context, scenes: JSONArray): JSONArray {
        val clean = Scenes.clean(scenes)
        prefs(c).edit().putString("list", clean.toString()).apply()
        changed(c)
        return clean
    }

    /** The widget, the tile and the shortcuts show the scenes' names: tell them. */
    fun changed(c: Context) {
        RemoteWidget.update(c)
        refreshSceneTile(c)
        if (Build.VERSION.SDK_INT >= 25) try {
            val sm = c.getSystemService(ShortcutManager::class.java)
            sm.dynamicShortcuts = list(c).take(4).map { sc ->
                ShortcutInfo.Builder(c, "scene-" + sc.optString("id"))
                    .setShortLabel((sc.optString("icon") + " " + sc.optString("name")).trim())
                    .setIcon(Icon.createWithResource(c, R.drawable.icon))
                    .setIntent(Intent(Intent.ACTION_VIEW).setClass(c, SceneActivity::class.java).putExtra("scene", sc.optString("id")))
                    .build()
            }
        } catch (ignored: Exception) {                                // a launcher without shortcuts: nothing to update
        }
    }
}

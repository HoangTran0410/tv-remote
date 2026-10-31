package dev.tvremote.setup

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import dev.tvremote.core.Words
import dev.tvremote.core.scenes.Scenes
import dev.tvremote.setup.rules.Rules
import dev.tvremote.tvui.TvUi
import org.json.JSONArray
import org.json.JSONObject

/**
 * A scene as a tile on TV Home ("Add to TV Home" in the scene editor and list). TV Setup offers TV Home a pinned shortcut
 * (ShortcutManager.requestPinShortcut: TV Home's PinActivity takes any app's pin); the shortcut's intent opens [SceneRunActivity]
 * with the whole cleaned scene inside, so the tile needs no list on the TV and works for a scene made on a phone or a computer.
 * Asked from this TV's own page (a route of [Scenes.Routes]) and from a phone or a computer over ADB (RulesReceiver "scenepin").
 */
internal object ScenePin {
    const val EXTRA = "scene"

    /** Shortcut id of a scene: the same scene pins to the same tile (asking again renames or redraws it). */
    fun id(scene: JSONObject) = "scene-" + scene.optString("id")

    /** The intent a tile starts. Explicit and not exported: only a launcher that holds the shortcut can start it. */
    fun intent(c: Context, scene: JSONObject): Intent =
        Intent(Intent.ACTION_VIEW).setClassName(c, SceneRunActivity::class.java.name).putExtra(EXTRA, scene.toString())

    /** The scene a tile's intent carries, cleaned again (the shortcut file is not trusted more than a request); null: nothing to run. */
    fun scene(i: Intent): JSONObject? {
        val raw = i.getStringExtra(EXTRA) ?: return null
        return try { Scenes.clean(JSONArray().put(JSONObject(raw))).optJSONObject(0) } catch (e: Exception) { null }
    }

    /** The scene's emoji on a dark card, as the tile's picture. */
    fun art(scene: JSONObject): Bitmap {
        val b = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val cv = Canvas(b)
        cv.drawRoundRect(0f, 0f, 256f, 256f, 40f, 40f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TvUi.CARD })
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 128f; textAlign = Paint.Align.CENTER }
        val mark = scene.optString("icon").ifBlank { "🎬" }
        cv.drawText(mark, 128f, 128f - (p.ascent() + p.descent()) / 2, p)
        return b
    }

    /**
     * The scenes as the app's dynamic shortcuts, so TV Home's + Add lists them ("App shortcuts") and one is added with a few presses: same ids,
     * intent and picture as [request]. The latest list replaces the one before (last save wins, wherever it was saved: this TV's own page, or
     * a phone / computer through [Scenes.publishThroughSetup]). A scene that is gone leaves the list; a tile already on the grid keeps
     * working, because it carries the scene itself. Nothing is published for an empty list on a TV that never had any.
     */
    fun publish(c: Context, scenes: JSONArray) {
        val sm = if (Build.VERSION.SDK_INT >= 25) c.getSystemService(ShortcutManager::class.java) else return
        try {
            if (scenes.length() == 0 && sm.dynamicShortcuts.isEmpty()) return
            val list = (0 until scenes.length()).mapNotNull { scenes.optJSONObject(it) }.take(maxOf(sm.maxShortcutCountPerActivity, 1)).map { sc ->
                ShortcutInfo.Builder(c, id(sc)).setShortLabel(sc.optString("name")).setIntent(intent(c, sc)).setIcon(Icon.createWithBitmap(art(sc))).build()
            }
            sm.dynamicShortcuts = list
        } catch (e: Exception) { }                                    // a launcher-less or rate-limited system: the pin button still works
    }

    /** On TV Setup's start: lists this TV's own saved scenes only when nothing is listed yet (the system keeps the list; a phone's later list must not be undone by a restart). */
    fun publishFirst(c: Context, own: JSONArray) {
        if (own.length() == 0 || Build.VERSION.SDK_INT < 25) return
        try { if (c.getSystemService(ShortcutManager::class.java).dynamicShortcuts.isNotEmpty()) return } catch (e: Exception) { return }
        publish(c, own)
    }

    /** Asks the default home to pin [scene]. {pinned} when asked (TV Home takes it at once), {error} when this TV's home cannot. */
    fun request(c: Context, scene: JSONObject): JSONObject {
        if (Build.VERSION.SDK_INT < 26) return JSONObject().put("error", Words.get(Words.lang, "scenepin.unsupported"))   // pinning came in Android 8
        val sm: ShortcutManager? = c.getSystemService(ShortcutManager::class.java)
        if (sm == null || !sm.isRequestPinShortcutSupported) return JSONObject().put("error", Words.get(Words.lang, "scenepin.unsupported"))
        val info = ShortcutInfo.Builder(c, id(scene)).setShortLabel(scene.optString("name")).setIntent(intent(c, scene))
            .setIcon(Icon.createWithBitmap(art(scene))).build()
        val ok = try { sm.requestPinShortcut(info, null) } catch (e: Exception) { false }
        return if (ok) JSONObject().put("ok", true) else JSONObject().put("error", Words.get(Words.lang, "scenepin.unsupported"))
    }
}

/** What a scene tile on TV Home opens: shows nothing, runs the scene over this TV's own ADB link and says how it went. */
class SceneRunActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val scene = ScenePin.scene(intent)
        finish()
        val c = applicationContext
        val ui = Handler(Looper.getMainLooper())
        fun say(t: String) = ui.post { Toast.makeText(c, t, Toast.LENGTH_LONG).show() }
        if (scene == null) { say(Words.get(Words.lang, "headless.this_scene_is_gone")); return }
        say(Words.get(Words.lang, "auto.running", "name" to scene.optString("name")))
        Thread({
            if (!RemoteService.isRunning()) try { RemoteService.start(c, true) } catch (e: Exception) { }
            var adb = RemoteService.adb()
            for (i in 0 until 20) { if (adb != null) break; Thread.sleep(500); adb = RemoteService.adb() }   // TV Setup was asleep: give its ADB link a moment
            if (adb == null) { say(Words.get(Words.lang, "scenepin.no_adb")); return@Thread }
            val failed = Scenes.run(scene.getJSONArray("steps"), Scenes.Shell({ adb.shell(it) }) { m -> Rules.change(c, JSONObject().put("do", "sleep").put("minutes", m)) })
            say(if (failed == null) Words.get(Words.lang, "auto.done", "name" to scene.optString("name")) else Words.get(Words.lang, "auto.done_but", "why" to failed))
        }, "scene-tile").apply { isDaemon = true }.start()
    }
}

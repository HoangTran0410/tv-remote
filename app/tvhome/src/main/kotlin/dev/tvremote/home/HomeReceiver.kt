package dev.tvremote.home

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import dev.tvremote.home.manage.HomeApi
import org.json.JSONObject

/**
 * The settings for a phone or computer that has ADB on this TV (core/ontv/Home.kt). Only the shell can send this broadcast
 * (see the manifest). Extra "set": the fields to change as base64 JSON ([HomeConfig.merge]); none reads. "drop": one of the
 * photos to delete (its file goes, and it leaves the set). "addvideo": the name of a file in the staging folder to take into the media library ("vname": its name, base64). "thumbs": also small pictures of the photos. The answer, base64
 * "tiles" (boolean): the Home on screen checks its grid and tile menus against [Tiles] ([HomeActivity.tilesCheck], dev/test/home_tiles.sh).
 * JSON in the result data, is [HomeApi.snapshot]'s. The settings page TV Home serves itself ([ManageServer]) gives the same answer.
 */
class HomeReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.getBooleanExtra("tiles", false)) return tiles()
        val pin = i.getStringExtra("pin")?.takeIf { it.isNotEmpty() }
        if (pin != null) return pinLink(c, pin)
        i.getStringExtra("link")?.takeIf { it.isNotEmpty() }?.let { b ->      // "what opens this link": asked before a pin, answered at once
            val a = try { HomeApi.link(c, JSONObject(String(Base64.decode(b, Base64.DEFAULT), Charsets.UTF_8))) } catch (e: Exception) { JSONObject().put("error", "pin_bad_link") }
            resultCode = Activity.RESULT_OK; resultData = Base64.encodeToString(a.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            return
        }
        val answer = try {
            val set = i.getStringExtra("set")?.takeIf { it.isNotEmpty() }?.let { JSONObject(String(Base64.decode(it, Base64.DEFAULT), Charsets.UTF_8)) }
            i.getStringExtra("addvideo")?.takeIf { Regex("in[a-z0-9]+\\.(mp4|webm|gif)").matches(it) }?.let { f ->
                val name = i.getStringExtra("vname")?.let { String(Base64.decode(it, Base64.DEFAULT), Charsets.UTF_8) } ?: f
                val why = HomeApi.addVideo(c, java.io.File(dev.tvremote.home.wall.Videos.staging(c), f), name)
                if (why != "ok") throw IllegalStateException("video_$why")
            }
            HomeApi.snapshot(c, set, i.getStringExtra("drop"), i.getBooleanExtra("thumbs", false))
        } catch (e: Exception) {
            JSONObject().put("error", e.message ?: e.javaClass.simpleName)
        }
        resultCode = Activity.RESULT_OK
        resultData = Base64.encodeToString(answer.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    /** Extra "tiles": [HomeActivity.tilesCheck] of the Home on screen (read on its thread), or {"error": "no_home"}. */
    private fun tiles() {
        val async = goAsync()
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                val out = try { HomeActivity.shown?.get()?.tilesCheck() ?: JSONObject().put("error", "no_home") } catch (e: Exception) { JSONObject().put("error", e.toString()) }
                async.resultCode = Activity.RESULT_OK
                async.resultData = Base64.encodeToString(out.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            } finally { async.finish() }
        }
    }

    /** Extra "pin": base64 JSON {"url", "name"?, "img"?}; the answer is the snapshot, or {"error": "pin_bad_link" | "pin_no_app" | "pin_failed"}. The fetch runs off the main thread (goAsync). */
    private fun pinLink(c: Context, b64: String) {
        val async = goAsync()
        Thread({
            val answer = try {
                val code = HomeApi.pin(c, JSONObject(String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8)))
                if (code == "ok") null else JSONObject().put("error", code)
            } catch (e: Exception) { JSONObject().put("error", "pin_bad_link") }
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try {
                    val out = answer ?: try { HomeApi.snapshot(c, null, null, false) } catch (e: Exception) { JSONObject().put("error", e.message ?: e.javaClass.simpleName) }
                    async.resultCode = Activity.RESULT_OK
                    async.resultData = Base64.encodeToString(out.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                } finally { async.finish() }
            }, if (answer == null) 800 else 0)                       // the system confirms a pin through PinActivity first: the snapshot reads it
        }, "pin-link").apply { isDaemon = true }.start()
    }
}

package dev.tvremote.phone

import dev.tvremote.phone.scenes.Headless
import dev.tvremote.phone.scenes.SceneStore
import dev.tvremote.phone.ui.loadLang

import dev.tvremote.core.T
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews

/**
 * The home screen widget: power, mute and volume for the TV used last, and up to three scenes. Each button goes through
 * [SceneActivity], so nothing has to be open.
 */
class RemoteWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) { paint(c, m, ids) }

    companion object {
        private val SCENES = intArrayOf(R.id.w_scene1, R.id.w_scene2, R.id.w_scene3)

        fun update(c: Context) {
            val m = AppWidgetManager.getInstance(c) ?: return
            paint(c, m, m.getAppWidgetIds(ComponentName(c, RemoteWidget::class.java)))
        }

        private fun paint(c: Context, m: AppWidgetManager, ids: IntArray) {
            if (ids.isEmpty()) return
            loadLang(c)
            val v = RemoteViews(c.packageName, R.layout.widget)
            var code = 0
            val tap = { view: Int, extra: (Intent) -> Intent ->
                val i = extra(Intent(c, SceneActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                v.setOnClickPendingIntent(view, PendingIntent.getActivity(c, ++code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            }
            tap(R.id.w_power) { it.putExtra("do", "power") }
            tap(R.id.w_mute) { it.putExtra("do", "mute") }
            tap(R.id.w_down) { it.putExtra("do", "vol_down") }
            tap(R.id.w_up) { it.putExtra("do", "vol_up") }
            v.setOnClickPendingIntent(R.id.w_title, PendingIntent.getActivity(c, 99, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            v.setTextViewText(R.id.w_title, Headless(c).tvName().ifEmpty { "TV Remote" })
            val scenes = SceneStore.list(c)
            SCENES.forEachIndexed { k, id ->
                val sc = scenes.getOrNull(k)
                v.setViewVisibility(id, if (sc == null) View.GONE else View.VISIBLE)
                if (sc != null) {
                    v.setTextViewText(id, (sc.optString("icon") + " " + sc.optString("name")).trim())
                    tap(id) { it.putExtra("scene", sc.optString("id")) }
                }
            }
            v.setViewVisibility(R.id.w_hint, if (scenes.isEmpty()) View.VISIBLE else View.GONE)
            v.setTextViewText(R.id.w_hint, T("remote_widget.make_scenes_in_the"))
            m.updateAppWidget(ids, v)
        }
    }
}

package dev.tvremote.setup.rules

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context

/**
 * Android's usage events from [begin] to now, the one way TV Setup reads them. A box that booted with its clock ahead (an FPT
 * box at 4/9/2027, set right by the network 38 s later) keeps its current stretch of events beginning in that future: a
 * query ending now came back empty until the next reboot, so the app lock saw no app (kids mode let everything open, TV Setup
 * never asked its PIN again) and the day's use stayed 0. Asked years ahead, the current stretch is included; events past now
 * (the minutes in the wrong year) are dropped here.
 */
internal object Usage {
    private const val AHEAD = 20 * 365 * 86_400_000L

    /** [each] gets every event from [begin] to [now] in Android's order; the Event is reused, copy what is kept. */
    inline fun events(c: Context, begin: Long, now: Long, each: (UsageEvents.Event) -> Unit) {
        val ev = (c.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager).queryEvents(begin, now + ahead()) ?: return
        val e = UsageEvents.Event()
        while (ev.hasNextEvent()) { ev.getNextEvent(e); if (e.timeStamp in begin..now) each(e) }
    }

    fun ahead() = AHEAD

    /** The app on screen from the last quarter hour of usage events (usage access is TV Setup's, granted over ADB); "" when none or not known. */
    fun front(c: Context): String = try {
        val now = System.currentTimeMillis()
        var front = ""
        events(c, now - 15 * 60_000L, now) { e ->
            if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) front = e.packageName ?: ""
            else if (e.eventType == UsageEvents.Event.MOVE_TO_BACKGROUND && front == e.packageName) front = ""
        }
        front
    } catch (e: Exception) { "" }
}

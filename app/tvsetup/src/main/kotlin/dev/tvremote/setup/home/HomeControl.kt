package dev.tvremote.setup.home

import android.content.Context
import dev.tvremote.core.ontv.Dim
import dev.tvremote.setup.DimLayer
import dev.tvremote.setup.KeyTestActivity
import dev.tvremote.setup.ManageActivity
import dev.tvremote.setup.PinActivity
import dev.tvremote.setup.RemoteService
import dev.tvremote.setup.rules.Rules
import org.json.JSONObject

/**
 * What TV Home's Control Center may ask of the rules (HomeInfo "control"): the sleep timer set to one of [SLEEP] minutes and kids mode
 * switched ON, named words and nothing else, never a rules JSON from the caller. Kids mode is not switched off from here: TV Home is
 * free in kids mode (Kids.ALWAYS), so a child could undo it from its own panel; "kids:off" only OPENS the PIN screen ([PinActivity] with
 * `kids_off`: the same PIN / Authenticator / backup code / ask a parent and Lockout as anywhere), and that screen changes the rules on a right code.
 * "kids:setup" opens TV Setup's Manage screen on the TV at the lock card, where the PIN is set; "manage:health" at the Health tab; "keytest" the key tester ([KeyTestActivity]).
 * "dim:<pct>" sets the tint's strength ([DimLayer], its colour kept; 0 = off): the panel's brightness bar, a forgotten tint undone from the TV itself;
 * "dimc:<colour>" (a [Dim.COLORS] key) the button beside it, turning the tint on.
 * `arg`: "" asks | "sleep:<minutes>" | "kids:on" | "kids:off" | "kids:setup" | "manage:health" | "dim:<pct>" | "dimc:<colour>". The answer {sleep_at, kids, pin, dim, error?}: error "bad" | "pin"
 * (kids needs a PIN or a code set first) | "failed" (the screen could not open) | "overlay" (this TV does not let TV Setup draw over apps). An old TV Setup answers
 * "bad" to the last two and has no `dim`: TV Home shows no bar then.
 */
object HomeControl {
    val SLEEP = setOf(0, 15, 30, 60, 90, 120)

    /** What a word does: "rules" (changes them here), "pin" / "manage" (only opens a screen, the rules untouched), "ask" (nothing), "bad". */
    fun route(word: String): String = when {
        word.isEmpty() -> "ask"
        word.startsWith("sleep:") && word.substringAfter(':').toIntOrNull() in SLEEP -> "rules"
        word == "kids:on" -> "rules"
        word == "kids:off" -> "pin"
        word == "kids:setup" || word == "manage:health" -> "manage"
        word == "keytest" -> "keytest"
        word.startsWith("dim:") && word.substringAfter(':').toIntOrNull()?.let { it == 0 || it in Dim.MIN_PCT..Dim.MAX_PCT } == true -> "dim"
        word.startsWith("dimc:") && word.substringAfter(':') in Dim.COLORS -> "dimc"
        else -> "bad"
    }

    private fun open(c: Context, i: android.content.Intent): Boolean = try { c.startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (e: Exception) { false }

    /** The screen a "pin" / "manage" word opens. */
    private fun screen(c: Context, word: String): android.content.Intent? = when (route(word)) {
        "pin" -> android.content.Intent(c, PinActivity::class.java).putExtra("kids_off", true)
        "manage" -> android.content.Intent(c, ManageActivity::class.java).putExtra(ManageActivity.PASS, RemoteService.selfPass)
            .apply {
                if (word == "manage:health") putExtra(ManageActivity.TAB, "health")   // TV Home's watched-today live widget and an app's week
                else putExtra(ManageActivity.TAB, "auto").putExtra(ManageActivity.SECTION, if (Rules.kidsMode(Rules.load(c))) "kidsadd" else "lockcard")   // on: the apps a child may open; off: the PIN first
            }
        "keytest" -> android.content.Intent(c, KeyTestActivity::class.java)
        else -> null
    }

    /**
     * That screen for TV Home to start itself: this app is in the background when the panel asks, and Android 10+ drops its own start
     * without a word unless it may draw over others (granted through ADB: a TV without ADB for TV Setup, or the emulator, got nothing;
     * the kids switch closed the panel and nothing came). TV Home is the screen in front, so the start is its own.
     */
    fun handBack(c: Context, word: String): android.app.PendingIntent? = screen(c, word)?.let {
        android.app.PendingIntent.getActivity(c, word.hashCode(), it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** [handOver]: the caller starts the screen itself ([handBack]); a TV Home older than that has this app open it. */
    fun run(c: Context, arg: String?, handOver: Boolean = false): JSONObject {
        var error: String? = null
        val word = arg.orEmpty()
        try {
            when (route(word)) {
                "ask" -> {}
                "rules" ->
                    if (word == "kids:on") Rules.change(c, JSONObject().put("do", "kids").put("mode", "kids"))
                    else Rules.change(c, JSONObject().put("do", "sleep").put("minutes", word.substringAfter(':').toInt()))
                "dim" -> { val p = word.substringAfter(':').toInt()
                    if (!DimLayer.set(c, DimLayer.state(c).put("on", p > 0).also { if (p > 0) it.put("pct", p) })) error = "overlay" }
                "dimc" -> { val st = DimLayer.state(c)
                    if (!st.optBoolean("on")) st.put("pct", 10)                        // off: a light tint (the bar at 90 %; 40 % was too dark, the user 2026-10-30), raised on the bar
                    if (!DimLayer.set(c, st.put("on", true).put("color", word.substringAfter(':')))) error = "overlay" }
                "pin", "manage", "keytest" -> if (!handOver && !open(c, screen(c, word)!!)) error = "failed"
                else -> error = "bad"
            }
        } catch (e: IllegalArgumentException) { error = "pin" }
        if (error == null && route(word) == "rules" && Rules.active(c) && !RemoteService.isRunning()) {
            try { RemoteService.start(c, true) } catch (e: Exception) { }   // the watcher that enforces them, as RulesReceiver starts it
        }
        val o = Rules.load(c)
        val now = System.currentTimeMillis()
        val lock = o.optJSONObject("lock")
        return JSONObject().put("sleep_at", o.optLong("sleep_at").let { if (it > now) it else 0L })
            .put("kids", Rules.kidsMode(o)).put("dim", DimLayer.state(c)).put("pin", lock?.has("hash") == true || lock?.has("totp") == true)
            .also { if (error != null) it.put("error", error) }
    }
}

package dev.tvremote.setup.home

import android.content.Context
import dev.tvremote.core.ontv.Dim
import dev.tvremote.setup.DimLayer
import dev.tvremote.setup.KeyTestActivity
import dev.tvremote.setup.ManageActivity
import dev.tvremote.setup.MicVoice
import dev.tvremote.setup.VoiceActivity
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
 * "dimc:<colour>" (a [Dim.COLORS] key) the button beside it, turning the tint on. "mic:on" / "mic:off": the remote's mic button to our voice
 * ([MicVoice], the same "mic" rule the phone and web switch), so it is found where the TV's other switches are (the user, 2026-11-01).
 * "mic:tv" / "mic:google": who listens, the panel's two-way switch (the user, 2026-11-03): TV Setup Voice ("mic" on, "mic_engine" tv),
 * or the TV's own (Google's Assistant: "mic" off, "mic_engine" auto); "mic:tv" with TV Setup Voice not on the TV opens [VoiceActivity],
 * which offers it, and so does "voice" (dev/flows/voice.md).
 * "home:unlock" opens the same PIN screen (`home_unlock`) for TV Home's "Lock TV Home's layout with the PIN" (HomeConfig `lockLayout`): a right code
 * opens the layout for [HOME_OPEN_MIN] minutes ([homeOpenUntil], in memory: a restart asks again), told to TV Home as `home_open`; no code set, or already open: no screen.
 * `arg`: "" asks | "sleep:<minutes>" | "kids:on" | "kids:off" | "kids:setup" | "manage:health" | "dim:<pct>" | "dimc:<colour>" | "mic:on" | "mic:off"
 * | "mic:tv" | "mic:google" | "voice" | "home:unlock". The answer {sleep_at, kids, pin, dim, mic, mic_engine, voice, home_open, error?}: error "bad" | "pin"
 * (kids needs a PIN or a code set first) | "failed" (the screen could not open) | "overlay" (this TV does not let TV Setup draw over apps). An old TV Setup answers
 * "bad" to the last two and has no `dim`: TV Home shows no bar then.
 */
object HomeControl {
    val SLEEP = setOf(0, 15, 30, 60, 90, 120)

    /** How long a right code opens TV Home's locked layout. */
    const val HOME_OPEN_MIN = 10
    /** Until when (ms) TV Home's layout is open after a right code; 0 = locked. Memory only, so a restart of TV Setup asks again. */
    @Volatile var homeOpenUntil = 0L

    /** What a word does: "rules" (changes them here), "pin" / "manage" (only opens a screen, the rules untouched), "ask" (nothing), "bad". */
    fun route(word: String): String = when {
        word.isEmpty() -> "ask"
        word.startsWith("sleep:") && word.substringAfter(':').toIntOrNull() in SLEEP -> "rules"
        word == "kids:on" || word == "mic:on" || word == "mic:off" || word == "mic:google" -> "rules"
        word == "mic:tv" -> "mictv"                                    // rules when TV Setup Voice is there, else its screen
        word == "voice" -> "voice"
        word == "kids:off" -> "pin"
        word == "home:unlock" -> "homepin"
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
        "homepin" -> if (Rules.hasCode(Rules.load(c)) && homeOpenUntil <= System.currentTimeMillis()) android.content.Intent(c, PinActivity::class.java).putExtra("home_unlock", true) else null
        "keytest" -> android.content.Intent(c, KeyTestActivity::class.java)
        "voice" -> android.content.Intent(c, VoiceActivity::class.java)
        "mictv" -> if (MicVoice.offline(c) == null) android.content.Intent(c, VoiceActivity::class.java) else null
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
                    else if (word == "mic:google") { Rules.change(c, JSONObject().put("do", "mic_engine").put("engine", "auto")); Rules.change(c, JSONObject().put("do", "mic").put("on", false)) }
                    else if (word.startsWith("mic:")) Rules.change(c, JSONObject().put("do", "mic").put("on", word == "mic:on"))
                    else Rules.change(c, JSONObject().put("do", "sleep").put("minutes", word.substringAfter(':').toInt()))
                "dim" -> { val p = word.substringAfter(':').toInt()
                    if (!DimLayer.set(c, DimLayer.state(c).put("on", p > 0).also { if (p > 0) it.put("pct", p) })) error = "overlay" }
                "dimc" -> { val st = DimLayer.state(c)
                    if (!st.optBoolean("on")) st.put("pct", 10)                        // off: a light tint (the bar at 90 %; 40 % was too dark, the user 2026-10-30), raised on the bar
                    if (!DimLayer.set(c, st.put("on", true).put("color", word.substringAfter(':')))) error = "overlay" }
                "pin", "manage", "keytest", "voice", "homepin" -> if (!handOver) screen(c, word)?.let { if (!open(c, it)) error = "failed" }
                "mictv" -> screen(c, word)?.let { if (!handOver && !open(c, it)) error = "failed" } ?: MicVoice.useTv(c)
                else -> error = "bad"
            }
        } catch (e: IllegalArgumentException) { error = "pin" }
        if (error == null && route(word) in setOf("rules", "mictv") && Rules.active(c) && !RemoteService.isRunning()) {
            try { RemoteService.start(c, true) } catch (e: Exception) { }   // the watcher that enforces them, as RulesReceiver starts it
        }
        val o = Rules.load(c)
        val now = System.currentTimeMillis()
        val lock = o.optJSONObject("lock")
        return JSONObject().put("sleep_at", o.optLong("sleep_at").let { if (it > now) it else 0L })
            .put("kids", Rules.kidsMode(o)).put("dim", DimLayer.state(c)).put("mic", o.optBoolean("mic")).put("mic_engine", o.optString("mic_engine").ifEmpty { "auto" }).put("voice", MicVoice.offlineState(c)).put("pin", lock?.has("hash") == true || lock?.has("totp") == true)
            .put("home_open", homeOpenUntil.let { if (it > now) it else 0L })
            .also { if (error != null) it.put("error", error) }
    }
}

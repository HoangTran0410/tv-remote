package dev.tvremote.games

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.tvremote.tvui.dp
import dev.tvremote.tvui.heading
import dev.tvremote.tvui.label
import dev.tvremote.tvui.menuButton
import dev.tvremote.tvui.panelBg
import dev.tvremote.tvui.scrim
import dev.tvremote.tvui.withIcon

/**
 * The cards both kinds of game share: the start card (QR, who is in), the chip that stays a few seconds, the pause card.
 * [onPause] freezes / thaws the game; [onSave] / [onLoad] (null = no save for this game) return a line for the card, [onLoad] null = loaded.
 */
class GameCards(
    private val act: Activity, private val root: FrameLayout, private val server: GameServer,
    private val title: String, private val runs: String?,
    private val onPause: (Boolean) -> Unit, private val onSave: (() -> String)?, private val onLoad: (() -> String?)?,
    private val onQuit: () -> Unit, private val alone: Boolean = true,
    private val quiet: Boolean = false,                   // a page that draws the join QR and the players itself (Pad test): no start card, no chip
    private val withLobby: Boolean = false,                // the lobby before the game: [art] its picture, [maxPlayers] / [minPlayers] what the game takes, [onGo] the game starts
    private val art: Thumbs.Src? = null, private val maxPlayers: Int = 2, private val minPlayers: Int = 1, private val onGo: () -> Unit = {},
    private val kind: String? = null,                    // the lobby's "NES" / "Web" line
    private val onStart: (() -> Unit)? = null,            // a console's Start pressed for player 1: the TV remote has no Start key
    private val onMap: (() -> Unit)? = null,              // learn which remote keys are Start / Select / B (RemoteMap)
) {
    var paused = false; private set
    /** True from the moment the lobby opens until the game starts: the activities keep the pads' and the remote's input from the game meanwhile. */
    var lobby = false; private set
    private var lobbyView: LobbyView? = null
    private var counting = false
    private var warned = false
    private val tick = android.os.Handler(android.os.Looper.getMainLooper())
    private var loadText = ""                                     // before init: the lobby opens there and reads it
    private var players: List<Int> = emptyList()
    private var startShown = true
    private val startPlayers = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; minimumHeight = act.dp(26) }
    private val chipDots = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL }
    private val pauseNote = act.label("", 18f, act.tvColor(R.color.tv_dim)).apply { setPadding(0, act.dp(10), 0, 0) }
    private val pingViews = HashMap<Int, MutableList<TextView>>()
    private lateinit var start: LinearLayout
    private lateinit var chip: LinearLayout
    private lateinit var pauseCard: View
    private lateinit var resumeBtn: View

    /** The keyboard's two pads, in the pause card, only while a real keyboard is plugged in (KeyPads). */
    private val keysNote by lazy {
        act.label(s(R.string.keys_title) + "\n" + s(R.string.keys_p1) + "\n" + s(R.string.keys_p2), 13f, act.tvColor(R.color.tv_dim)).apply {
            setPadding(0, act.dp(14), 0, 0); maxWidth = act.dp(250); visibility = View.GONE }
    }
    private fun card() = act.panelBg()
    private fun s(id: Int) = act.getString(id)

    init {
        start = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(act.dp(32), act.dp(22), act.dp(32), act.dp(22)); background = card(); minimumWidth = act.dp(400)
            addView(act.label(title, 26f).apply { maxLines = 2 })
            addView(act.label(s(R.string.scan_play), 18f, act.tvColor(R.color.tv_dim)).apply { setPadding(0, act.dp(4), 0, act.dp(10)) })
            addView(act.qrView(server.url(), 150))
            addView(act.label(server.url().substringAfter("//").substringBefore('/'), 16f, act.tvColor(R.color.tv_dim)).apply { setPadding(0, act.dp(8), 0, act.dp(4)) })
            if (runs != null) addView(act.label(act.getString(R.string.runs, runs), 14f, 0xFF8892A6.toInt()).apply { maxLines = 1; maxWidth = act.dp(380); ellipsize = android.text.TextUtils.TruncateAt.MIDDLE })
            addView(act.label(s(if (alone) R.string.press_ok else R.string.press_ok_web), 20f).apply { setPadding(0, act.dp(8), 0, act.dp(8)) })
            addView(startPlayers)
        }
        root.addView(start, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        chip = LinearLayout(act).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(act.dp(14), act.dp(8), act.dp(14), act.dp(8)); background = card(); visibility = View.GONE
            addView(act.label(s(R.string.chip), 15f).apply { setPadding(0, 0, act.dp(10), 0) }); addView(chipDots)
        }
        root.addView(chip, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(act.dp(24), act.dp(20), act.dp(24), act.dp(20)) })
        // 50 dp rows: six at 56 made the card 519 of 540 dp tall (11 dp margins; the safe area is 27)
        resumeBtn = act.menuButton(s(R.string.resume), 340, 50, 22f) { resume() }.withIcon(R.drawable.ic_play)
        val col = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            addView(act.heading(s(R.string.paused)))
            addView(resumeBtn)
            if (onStart != null) addView(act.menuButton(s(R.string.press_start), 340, 50, 22f) { resume(); onStart.invoke() }.withIcon(R.drawable.ic_gamepad))
            if (onMap != null) addView(act.menuButton(s(R.string.map_title), 340, 50, 22f) { resume(); onMap.invoke() }.withIcon(R.drawable.ic_keyboard))
            if (onSave != null) addView(act.menuButton(s(R.string.quick_save), 340, 50, 22f) { pauseNote.text = onSave.invoke() }.withIcon(R.drawable.ic_save))
            if (onLoad != null) addView(act.menuButton(s(R.string.quick_load), 340, 50, 22f) { val m = onLoad.invoke(); if (m == null) resume() else pauseNote.text = m }.withIcon(R.drawable.ic_undo))
            addView(act.menuButton(s(R.string.quit), 340, 50, 22f) { onQuit() }.withIcon(R.drawable.ic_close))
            addView(pauseNote)
        }
        val side = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, 0, act.dp(36), 0)
            addView(act.qrView(server.url(), 200))
            addView(act.label(s(R.string.scan_play), 18f, act.tvColor(R.color.tv_dim)).apply { setPadding(0, act.dp(10), 0, 0) })
            addView(keysNote)
        }
        pauseCard = act.scrim(LinearLayout(act).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(act.dp(36), act.dp(26), act.dp(36), act.dp(26)); background = card()
            addView(side); addView(col)
        }).apply { visibility = View.GONE }
        root.addView(pauseCard, FrameLayout.LayoutParams(-1, -1))
        refresh()
        if (withLobby && !quiet) openLobby()
    }

    // ---- the lobby: Ready on the pads or OK on the remote starts the game, a 3-2-1 first when everyone is ready
    private fun openLobby() {
        lobby = true; startShown = false; warned = false
        lobbyView = LobbyView(act, server, title, art, maxPlayers, minPlayers, kind).also { root.addView(it, FrameLayout.LayoutParams(-1, -1)); it.loading(loadText) }
        server.onReady = { act.runOnUiThread { evaluate() } }
        server.onLobbyBtn = { p, b, d -> act.runOnUiThread { lobbyView?.press(p, b, d) } }
        server.lobbyMax = maxPlayers.coerceIn(1, 4); server.setLobby(true)
        onPause(true)
        refresh(); evaluate()
    }

    /** Everyone in is ready (and enough of them): count 3-2-1; someone un-readies or a new pad comes in: stop. */
    private fun evaluate() {
        if (!lobby) return
        val ok = LobbyRule.allReady(server.lobbyPlayers(), minPlayers)
        if (ok && !counting) { counting = true; count(3) }
        else if (!ok && counting) { counting = false; tick.removeCallbacksAndMessages(null); server.countdown(0); lobbyView?.count(0) }
        if (warned && !LobbyRule.tooFew(server.lobbyPlayers(), minPlayers)) { warned = false; lobbyView?.warn("") }
        lobbyView?.render()
    }

    private fun count(n: Int) {
        if (!counting) return
        if (n == 0) { go(); return }
        server.countdown(n); lobbyView?.count(n)
        tick.postDelayed({ count(n - 1) }, 1000)
    }

    private fun go() {
        if (!lobby) return
        tick.removeCallbacksAndMessages(null); counting = false; lobby = false
        lobbyView?.let { root.removeView(it) }; lobbyView = null
        server.countdown(0); server.setLobby(false); server.lobbyMax = 4
        onGo(); onPause(false); refresh(true)
    }

    /** OK on the remote: start now; with fewer players than the game needs the first OK only says so. */
    private fun ok() {
        val ps = server.lobbyPlayers()
        if (LobbyRule.tooFew(ps, minPlayers) && !warned) { warned = true; lobbyView?.warn(s(R.string.few_warn).format(maxOf(ps.size, 1), minPlayers)); return }
        go()
    }

    /** The activity is going: leave the lobby state on the server, so the pads do not show it in the library. */
    fun close() {
        tick.removeCallbacksAndMessages(null); counting = false
        if (lobby) { lobby = false; server.countdown(0); server.setLobby(false); server.lobbyMax = 4 }
        lobbyView?.let { root.removeView(it) }; lobbyView = null           // a closed lobby left on screen took every key and did nothing
    }

    /** The game's page loading, shown in the lobby ("" when loaded). */
    fun loading(text: String) { loadText = text; lobbyView?.loading(text) }

    /** The page is back after its failure card: the lobby again, as at the start. */
    fun reopen() { if (withLobby && !quiet && !lobby) openLobby() }

    // each player's ping (the phone measures it, GameServer hears it) next to the slot dot: green under 30 ms, amber under 80, red above
    private fun paint(v: TextView, ms: Int) {
        v.text = if (ms < 0) "" else "$ms ms"
        v.setTextColor(if (ms < 30) 0xFF34D399.toInt() else if (ms < 80) 0xFFFBBF24.toInt() else 0xFFF87171.toInt())
    }
    private fun pingLabel(p: Int, sp: Float) = act.label("", sp).apply { setPadding(act.dp(8), 0, act.dp(6), 0); pingViews.getOrPut(p) { ArrayList() }.add(this); paint(this, server.pingOf(p)) }
    fun setPing(p: Int, ms: Int) { pingViews[p]?.forEach { paint(it, ms) }; lobbyView?.setPing() }

    /** "Player 2 ✓", or the name the player typed on the phone with the same tick. */
    private fun who(p: Int) = server.nameOf(p)?.let { "$it ✓" } ?: act.getString(R.string.player_n, p)

    fun setPlayers(l: List<Int>) { val flash = l.size != players.size; players = l; refresh(flash); evaluate() }

    // The start card goes when a player joins or OK is pressed. The chip (who is in, with pings) shows 5 s when a phone joins or
    // leaves mid-game; never with nobody in: there is no QR on screen then, it is on the pause card.
    private fun refresh(flash: Boolean = false) {
        if (startShown && players.isNotEmpty()) startShown = false
        start.visibility = if (startShown && !paused && !quiet) View.VISIBLE else View.GONE
        pauseCard.visibility = if (paused) View.VISIBLE else View.GONE
        startPlayers.removeAllViews(); chipDots.removeAllViews(); pingViews.clear()
        players.forEach { p ->
            startPlayers.addView(LinearLayout(act).apply { gravity = Gravity.CENTER; addView(act.dot(p, 14)); addView(act.label(who(p), 18f).apply { text = dev.tvremote.tvui.Marks.text(act, text); maxLines = 1; maxWidth = act.dp(260); ellipsize = android.text.TextUtils.TruncateAt.END }); addView(pingLabel(p, 14f)) })
            chipDots.addView(act.dot(p, 12)); chipDots.addView(pingLabel(p, 12f))
        }
        if (!startShown && !paused && !quiet && !lobby && players.isNotEmpty()) { if (flash) flashChip() } else chip.visibility = View.GONE
    }

    private fun flashChip() {
        chip.animate().cancel(); chip.alpha = 1f; chip.visibility = View.VISIBLE
        chip.animate().alpha(0f).setStartDelay(5000).setDuration(600).withEndAction { chip.visibility = View.GONE }.start()
    }

    fun pause() { paused = true; keysNote.visibility = if (KeyPads.present()) View.VISIBLE else View.GONE; pauseNote.text = ""; onPause(true); refresh(); resumeBtn.requestFocus() }
    fun resume() { paused = false; onPause(false); refresh() }

    /** BACK / MENU toggle the pause card; OK on the start card means "play alone". True = used up. */
    fun key(ev: KeyEvent): Boolean {
        val up = ev.action == KeyEvent.ACTION_UP
        if (lobby) {                                          // nothing reaches the game yet; OK starts it, Back goes to the library
            val lv = lobbyView
            if (!up) { lv?.move(ev.keyCode); return true }                 // up / down / left / right pick a player to send out
            when (ev.keyCode) {
                KeyEvent.KEYCODE_BACK -> if ((lv?.picked ?: 0) > 0) lv?.unpick() else onQuit()
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                    lv?.picked?.takeIf { it > 0 }?.let { lv.unpick(); server.kick(it) } ?: ok()
            }
            return true
        }
        if (ev.keyCode == KeyEvent.KEYCODE_BACK || ev.keyCode == KeyEvent.KEYCODE_MENU) { if (up) if (paused) resume() else pause(); return true }
        if (!paused && startShown && (ev.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || ev.keyCode == KeyEvent.KEYCODE_ENTER)) {
            if (up) { startShown = false; refresh(true) }
            return true
        }
        return false
    }
}

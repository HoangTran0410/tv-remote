package dev.tvremote.games

import android.os.Bundle
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.GLRetroViewData
import com.swordfish.libretrodroid.Variable
import dev.tvremote.tvui.dp
import dev.tvremote.tvui.label
import dev.tvremote.tvui.menuButton
import java.io.File
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

// One console game: the TV remote is player 1's directions (OK = A, Enter = Start), phones come in through GameServer, physical pads
// take the next free slot by device id. The cards (start, pause) are GameCards.
class GameActivity : ComponentActivity() {
    private lateinit var retro: GLRetroView
    private lateinit var server: GameServer
    private lateinit var cards: GameCards
    private lateinit var sys: Systems.Sys
    private lateinit var file: File

    override fun attachBaseContext(b: android.content.Context) = super.attachBaseContext(Lang.wrap(b))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        file = File(intent.getStringExtra("path") ?: return finish())
        sys = Systems.byExt(this, file.extension) ?: return finish()
        if (!file.isFile) {                                           // deleted since the library listed it: back to the (rescanned) library, no crash
            android.widget.Toast.makeText(this, R.string.rom_gone, android.widget.Toast.LENGTH_LONG).show(); return finish()
        }
        if (Cores.path(this, sys) == null) download() else start()
    }

    private var cancelled = false
    private var startedAt = Long.MAX_VALUE
    private var downloading = false
    private var broken = false                                       // the error card is up: its buttons take the D-pad, not the game

    /** The core is not here yet (the catalog has it): fetch it with a progress card, Back cancels. */
    private fun download() {
        downloading = true; cancelled = false
        val title = label(getString(R.string.core_title, sys.label), 28f)
        val pct = label("0 %", 44f)
        val hint = label(getString(R.string.core_hint), 18f, 0xFFAAB0C0.toInt())
        val bar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setBackgroundColor(0xFF0B0D12.toInt())
            addView(title); addView(pct); addView(bar, LinearLayout.LayoutParams(dp(520), dp(16)).apply { topMargin = dp(16); bottomMargin = dp(16) }); addView(hint)
            addView(menuButton(getString(R.string.cancel), 300, 56, 20f) { onBackPressed() }.also { it.requestFocus() })
        })
        Thread {
            val err = Cores.fetch(this, sys, { p -> runOnUiThread { pct.text = "$p %"; bar.progress = p } }, { cancelled })
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                downloading = false
                if (err == null) start() else failed(if (err.isEmpty()) getString(R.string.core_cancelled) else err)
            }
        }.start()
    }

    /** Every way the core can fail ends here: the reason and two buttons, Back goes to the library. */
    private fun failed(reason: String, title: String = getString(R.string.core_title, sys.label)) {
        if (::cards.isInitialized) cards.close()                      // its lobby took every key: OK on Retry / Back started the game instead
        broken = true
        val retry = menuButton(getString(R.string.retry), 300, 56, 20f) { if (Cores.path(this, sys) != null) start() else download() }
        val back = menuButton(getString(R.string.back), 300, 56, 20f) { finish() }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setBackgroundColor(0xFF0B0D12.toInt())
            addView(label(title, 28f))
            addView(label(reason, 22f, 0xFFFCA5A5.toInt()).apply { setPadding(dp(60), dp(12), dp(60), dp(20)) })
            addView(retry); addView(back)
        })
        back.requestFocus()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (downloading) cancelled = true else super.onBackPressed() }

    private fun start() {
        val data = GLRetroViewData(this).apply {
            coreFilePath = Cores.path(this@GameActivity, sys)!!
            gameFilePath = file.path
            systemDirectory = filesDir.path
            savesDirectory = filesDir.path
            // fceumm cannot tell a PAL-only homebrew from NTSC by itself: Region reads the ROM, never its name
            if (sys.id == "nes") variables = arrayOf(Variable("fceumm_region", Region.of(this@GameActivity, file)))
        }
        startedAt = Long.MAX_VALUE                                    // set when the lobby hands over to the game (no picture of the lobby's frozen frame)
        if (::retro.isInitialized) lifecycle.removeObserver(retro)    // Retry after a broken start: the first view goes
        broken = false
        if (!GlCheck.ok(this)) { failed(getString(R.string.no_gl), gameName); return }   // its EGL refuses what GLRetroView would ask: no crash (GlCheck)
        GlCheck.guard { runOnUiThread { if (!isDestroyed) failed(getString(R.string.no_gl), gameName) } }
        retro = GLRetroView(this, data)
        retro.keepScreenOn = false                                    // GLRetroView holds the screen on forever, even with nobody playing: [awake] decides instead
        lifecycle.addObserver(retro)                                  // GLRetroView is a LifecycleObserver; it also needs onCreate
        retro.onCreate(this)
        lifecycleScope.launch { retro.getGLRetroErrors().collect { if (!isDestroyed) failed(getString(R.string.cant_open), gameName) } }   // a broken ROM or core: the reason and Back, not a black screen
        val root = FrameLayout(this).apply { addView(retro) }
        setContentView(root)
        if (!::server.isInitialized) server = GameServer.acquire(this)   // once: onDestroy releases it once
        keys = RemoteMap(this, root) { retro.frameSpeed = if (it) 0 else 1; still(it) }
        cards = GameCards(this, root, server, gameName, null,
            onPause = { retro.frameSpeed = if (it) 0 else 1; still(it) }, onSave = { quickSave() }, onLoad = { quickLoad() }, onQuit = { finish() },
            withLobby = true, kind = sys.label, art = Thumbs.catalog(this, file.name) ?: Thumbs.src(this, sys, file.name), maxPlayers = players.first, minPlayers = players.second,
            onStart = { tapStart() }, onMap = { keys.learn() },
            onGo = { if (server.lobbyPlayers().isEmpty()) android.widget.Toast.makeText(this, R.string.remote_hint, android.widget.Toast.LENGTH_LONG).show()
                startedAt = System.currentTimeMillis(); snapLoop.removeCallbacksAndMessages(null); snapLoop.postDelayed(snapTick, 5000) })
        if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) bind()   // the core was fetched after onResume already ran
    }

    /** Lobby / pause: the picture does not change, so the GL thread stops drawing (frameSpeed 0 still rendered every vsync: the lobby over it cost a core
     *  and ~65 % janky frames on the test box); on resume it draws continuously again. */
    private fun still(on: Boolean) { try { retro.renderMode = if (on) android.opengl.GLSurfaceView.RENDERMODE_WHEN_DIRTY else android.opengl.GLSurfaceView.RENDERMODE_CONTINUOUSLY } catch (e: Throwable) { } }

    /** Start down, then up a moment later: a core reads the pad once a frame, so a press within one frame is missed. */
    private fun tapStart() {
        if (sys.id == "arcade") {                                     // an arcade board starts nothing without a credit: Coin (SELECT), then Start once it counted
            tap(KeyEvent.KEYCODE_BUTTON_SELECT); window.decorView.postDelayed({ if (!isDestroyed && !broken) tap(KeyEvent.KEYCODE_BUTTON_START) }, 700); return
        }
        tap(KeyEvent.KEYCODE_BUTTON_START)
    }
    private fun tap(code: Int) { press(KeyEvent.ACTION_DOWN, code); press(KeyEvent.ACTION_UP, code) }

    private lateinit var keys: RemoteMap
    private val downAt = HashMap<Int, Long>()
    /** Player 1's button, held at least 120 ms: a core reads the pad once a frame, and ⏯, colour keys or the phone's remote
     *  (ADB `input keyevent`) send down and up at once, a press the game would never see. */
    private fun press(action: Int, code: Int) {
        if (action == KeyEvent.ACTION_DOWN) { downAt[code] = android.os.SystemClock.uptimeMillis(); retro.sendKeyEvent(action, code, 0); return }
        val wait = 120 - (android.os.SystemClock.uptimeMillis() - (downAt.remove(code) ?: 0L))
        if (wait > 0) retro.postDelayed({ retro.sendKeyEvent(action, code, 0) }, wait) else retro.sendKeyEvent(action, code, 0)
    }

    /** The catalog's name for a ROM it suggested (an arcade zip is only "robby"), else the file's. */
    private val gameName by lazy { Catalog.entryOf(this, file.name, null)?.optString("name")?.ifEmpty { null } ?: Systems.clean(file.name) }
    private val players by lazy { Catalog.players(this, file.name, null, Systems.maxPlayers(sys.id)) }

    override fun onResume() {
        super.onResume()
        bind()
    }

    private val awake by lazy { Awake(this) }               // the screen stays on while phones play

    private fun bind() {
        if (!::server.isInitialized || !::cards.isInitialized) return
        server.inGame = true
        server.buttons = sys.buttons
        server.clearLayouts(); server.webMode = false; server.setSystem(sys.pad); server.analog = sys.analog
        server.onAxis = { port, x, y -> retro.sendMotionEvent(GLRetroView.MOTION_SOURCE_ANALOG_LEFT, x, y, port) }
        server.onKey = { port, action, code -> retro.sendKeyEvent(action, code, port) }
        server.onPlayers = { l -> runOnUiThread { cards.setPlayers(l) } }
        cards.setPlayers(server.playersNow())
        server.onAdded = { }
        server.onPing = { p, ms -> runOnUiThread { cards.setPing(p, ms) } }
        server.onPlay = { awake.poke() }
        snapLoop.removeCallbacksAndMessages(null); snapLoop.postDelayed(snapTick, 5000)
    }

    // a picture of the play for the library's tile (see Thumbs.snapshot): 5 s in, then every 40 s while the game runs
    private val snapLoop = android.os.Handler(android.os.Looper.getMainLooper())
    private val snapTick = object : Runnable {
        override fun run() {
            if (!::retro.isInitialized || isDestroyed) return
            if (!cards.paused && !cards.lobby) Thumbs.snapshot(this@GameActivity, retro, "g:" + file.name)
            snapLoop.postDelayed(this, 40_000)
        }
    }

    /** The last picture of the game becomes its tile in the library (when there is no box art): taken while the surface is still there. */
    override fun onPause() {
        snapLoop.removeCallbacksAndMessages(null)
        awake.stop(); if (::server.isInitialized) server.onPlay = {}
        if (::retro.isInitialized && System.currentTimeMillis() - startedAt > 3000) Thumbs.snapshot(this, retro, "g:" + file.name, 400)
        super.onPause()
    }

    override fun onDestroy() { cancelled = true; if (::cards.isInitialized) cards.close(); if (::server.isInitialized) GameServer.release(); super.onDestroy() }

    private fun stateFile() = File(Systems.savesDir(this), file.nameWithoutExtension + ".state")
    private fun quickSave(): String = try {
        val f = stateFile(); val tmp = File(f.path + ".tmp")          // the one save is never cut in half (killed, disk full): the old stays until the new is whole
        tmp.writeBytes(retro.serializeState())
        if (!tmp.renameTo(f)) { tmp.delete(); throw java.io.IOException("rename") }
        getString(R.string.saved)
    } catch (e: Exception) { getString(R.string.cant_open) }
    private fun quickLoad(): String? {
        val f = stateFile()
        if (!f.exists()) return getString(R.string.no_save)
        return try { retro.unserializeState(f.readBytes()); null } catch (e: Exception) { getString(R.string.cant_open) }
    }

    override fun dispatchKeyEvent(ev: KeyEvent): Boolean {
        if (!::cards.isInitialized || broken) return super.dispatchKeyEvent(ev)   // still fetching the core, or the error card
        if (keys.key(ev) || cards.key(ev)) return true
        if (cards.paused) return super.dispatchKeyEvent(ev)          // the card's buttons take the D-pad and OK
        if (ev.repeatCount > 0) return true
        if (KeyPads.isKeyboard(ev.device)) KeyPads.lookup(ev.keyCode)?.let { h ->   // a real keyboard: two pads, a seat each
            val port = server.padPort(KeyPads.seat(ev.deviceId, h.half))
            if (port >= 0) retro.sendKeyEvent(ev.action, PadMap.sys(sys.pad, h.code), port)
            return true
        }
        if (GameServer.isPad(ev)) {                                   // a physical pad: its own slot
            val port = server.padPort(ev.deviceId)
            if (port >= 0) retro.sendKeyEvent(ev.action, PadMap.sys(sys.pad, ev.keyCode), port)
            return true
        }
        val code = when (ev.keyCode) {                                // the TV remote is player 1
            KeyEvent.KEYCODE_DPAD_CENTER -> KeyEvent.KEYCODE_BUTTON_A
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> KeyEvent.KEYCODE_BUTTON_START
            else -> keys.button(ev.keyCode) ?: ev.keyCode
        }
        press(ev.action, PadMap.sys(sys.pad, code))
        return true
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (ev.source and InputDevice.SOURCE_JOYSTICK == 0 || !GameServer.hasStick(ev.device) || cards.paused || cards.lobby) return super.dispatchGenericMotionEvent(ev)   // a remote's mic axis is no stick
        val port = server.padPort(ev.deviceId)
        if (port < 0) return true
        retro.sendMotionEvent(GLRetroView.MOTION_SOURCE_DPAD, ev.getAxisValue(MotionEvent.AXIS_HAT_X), ev.getAxisValue(MotionEvent.AXIS_HAT_Y), port)
        retro.sendMotionEvent(GLRetroView.MOTION_SOURCE_ANALOG_LEFT, ev.getAxisValue(MotionEvent.AXIS_X), ev.getAxisValue(MotionEvent.AXIS_Y), port)
        return true
    }
}

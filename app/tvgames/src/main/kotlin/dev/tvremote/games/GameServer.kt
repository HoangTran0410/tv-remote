package dev.tvremote.games

import android.content.Context
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The phones' gamepad: a small HTTP + WebSocket server (plain sockets) that serves assets/pad/ and turns "d A" / "u A" into keys
 * for one player's port. Up to 4 players; a phone keeps its slot for [KEEP_MS] after it drops (same client id), a physical pad
 * is given one by its device id. Every request must carry the app-start token (`?k=`).
 */
class GameServer private constructor(private val ctx: Context) {
    private class Slot {
        var cid: String? = null
        var name: String? = null                           // what the player typed on the phone (null = "Player N")
        var conn: Conn? = null
        var dev = -1                                       // a physical pad's device id
        var freedAt = 0L
        val held = HashSet<Int>()
        var dirs: Set<String> = emptySet()                 // the d-pad keys the virtual stick holds now (cores without analog)
        @Volatile var ping = -1                            // the phone's own median round trip (ms), -1 = not measured yet
        @Volatile var offset = Long.MIN_VALUE              // the TV's clock minus the phone's (ms), from the ping exchange
        @Volatile var ready = false                        // pressed "Sẵn sàng" in the lobby; cleared when the phone drops or a lobby opens
    }

    @Volatile var buttons: List<String> = listOf("A", "B", "START", "SELECT")   // the buttons the running system has: the page shows only those
    @Volatile var analog = false                           // true: sticks go to [onAxis]; false: they are turned into D-pad keys (8-way, dead zone .35)
    @Volatile var onAxis: (port: Int, x: Float, y: Float) -> Unit = { _, _, _ -> }
    /** Which pad the phones draw: nes / snes / gba / ps / web (what the TV is running); sent on join and when it changes. */
    @Volatile var system = "nes"; private set
    fun setSystem(id: String) { system = id; send(0, "{\"sys\":\"$id\"}") }
    @Volatile var webMode = false                          // a web game: any pad button is passed on (the game's layout decides which are shown)
        set(v) { val ch = field != v; field = v; if (ch) send(0, "{\"web\":$v}") }   // the pads offer Reload only in one
    /** A phone's Reload: the web game's page fetched again from its server, not from the WebView's cache. */
    @Volatile var onReload: () -> Unit = {}
    /** The pad page offers "add game" only outside a game, and opens in navigate mode outside one (the phones are told when it changes). */
    @Volatile var inGame = false
        set(v) { val ch = field != v; field = v; if (ch) send(0, "{\"game\":$v}") }
    @Volatile var onKey: (port: Int, action: Int, keyCode: Int) -> Unit = { _, _, _ -> }
    /** Like [onKey] for a phone's press, with the one-way delay the phone's time stamp gives (ms, -1 = unknown). Null = [onKey] alone. */
    @Volatile var onKeyLat: ((port: Int, action: Int, keyCode: Int, latMs: Int) -> Unit)? = null
    /** A phone with the pointer layout: relative move of its crosshair (pad px, already speeded up), or [center] = its Recenter. Web games only; ignored in the lobby. */
    @Volatile var onPointer: (player: Int, dx: Int, dy: Int, center: Boolean) -> Unit = { _, _, _, _ -> }
    /** A phone whose layout asked for {motion}: its attitude, turn rates and acceleration as the pad's "o" (ten integers, WEBGAMES.md). Web games only. */
    @Volatile var onMotion: (player: Int, v: IntArray) -> Unit = { _, _ -> }
    @Volatile var onPing: (player: Int, ms: Int) -> Unit = { _, _ -> }   // a phone's ping changed, player 1..4
    fun pingOf(player: Int): Int = if (player in 1..MAX) slots[player - 1].ping else -1
    @Volatile var onPlayers: (List<Int>) -> Unit = {}      // slots (1..4) now in use, any thread
    /** The lobby (before a game starts): pad presses go to [onLobbyBtn] instead of the game, "ready 0|1" fills [onReady]. Set by GameCards. */
    @Volatile var lobby = false; private set
    @Volatile var lobbyMax = 4                             // seats the lobby's game has: phones beyond them only watch
    @Volatile var onReady: () -> Unit = {}                 // someone's Ready changed, any thread
    @Volatile var onLobbyBtn: (player: Int, name: String, down: Boolean) -> Unit = { _, _, _ -> }   // a button tried in the lobby, player 1..4
    fun setLobby(on: Boolean) {
        if (on) synchronized(slots) { slots.forEachIndexed { i, s -> s.ready = false; release(i) } }   // a key held as the lobby opens went up in the game, not stuck down
        Log.i(TAG, "lobby $on"); lobby = on; send(0, "{\"lobby\":$on,\"max\":$lobbyMax}")
        if (!on) synchronized(slots) { layouts.toList() }.forEachIndexed { i, l -> if (l != null) send(i + 1, "{\"layout\":$l}") }   // held in the lobby
    }
    fun watching(): Int = synchronized(slots) { slots.withIndex().count { (i, s) -> i >= lobbyMax && s.conn != null } }
    fun countdown(n: Int) = send(0, "{\"count\":$n}")      // the lobby's 3-2-1 on the pads, 0 = cancelled
    /** Who is in, for [LobbyRule]: a phone has its own Ready, a physical pad has no button for it and counts as ready. */
    fun lobbyPlayers(): List<LobbyRule.P> = synchronized(slots) {
        slots.mapIndexedNotNull { i, s -> if (i >= lobbyMax) null else if (s.conn != null) LobbyRule.P(i + 1, true, s.ready) else if (s.dev >= 0) LobbyRule.P(i + 1, false, true) else null }
    }
    @Volatile var onAdded: () -> Unit = {}                 // a game or a web link was added
    @Volatile var lastRom: String? = null                  // the file name of the last game sent from a phone
    @Volatile var onText: (player: Int, text: String) -> Unit = { _, _ -> }   // a phone's text field (web games), player 1..4
    @Volatile var onPlay: () -> Unit = {}                  // a phone pressed, moved or typed something (not a ping): Awake keeps the screen on
    private val layouts = arrayOfNulls<String>(MAX)        // per player: the pad layout a web game asked for (JSON), kept for a reconnect
    private var defaultLayout: String? = null
    private val slots = Array(MAX) { Slot() }
    private var server: ServerSocket? = null
    @Volatile var port = 0; private set
    private val prefs = ctx.getSharedPreferences("games", Context.MODE_PRIVATE)
    private val tls = PadTls(ctx)
    /** Kept in the app's prefs (made once): a phone's link and the iPhone's Home Screen icon still work after TV Games was closed and opened again. */
    @Volatile var token: String = prefs.getString("padToken", null) ?: newToken().also { prefs.edit().putString("padToken", it).apply() }
        private set

    /** A new connection code: every phone that scanned the old one gets 403 and is told to scan again. */
    fun rotateToken() {
        token = newToken(); prefs.edit().putString("padToken", token).apply(); publish()
        val cs = synchronized(slots) { slots.mapNotNull { it.conn } }
        cs.forEach { it.text("{\"rescan\":true}"); it.bye() }
    }

    /** The name a player typed, null = the default. Player 1..4. */
    fun nameOf(player: Int): String? = if (player in 1..MAX) synchronized(slots) { slots[player - 1].name } else null

    init {                                                 // who sat where before, so a phone gets its seat back after a restart
        try {
            val a = org.json.JSONArray(prefs.getString("padSeats", "[]"))
            for (i in 0 until minOf(a.length(), MAX)) { val o = a.optJSONObject(i) ?: continue; slots[i].cid = o.optString("c").ifEmpty { null }; slots[i].name = PadNames.clean(o.optString("n")); slots[i].freedAt = SystemClock.uptimeMillis() }
        } catch (e: Exception) { }
    }
    private fun saveSeats() {
        val a = org.json.JSONArray()
        synchronized(slots) { slots.forEach { a.put(org.json.JSONObject().put("c", it.cid ?: "").put("n", it.name ?: "")) } }
        prefs.edit().putString("padSeats", a.toString()).apply()
    }

    /** "port token" in a file the ADB shell can read: the Phone app (the gyroscope pointer) joins as a pad without scanning the QR. */
    private fun publish() { try { java.io.File(ctx.getExternalFilesDir(null), "pad.txt").writeText("$port $token") } catch (e: Exception) { } }

    fun start() {
        val last = prefs.getInt("padPort", 0)                // the same port as last time, so a saved link stays valid
        for (p in listOf(last).filter { it in PORT until PORT + 20 } + (PORT until PORT + 20)) {
            try { server = ServerSocket(p).also { port = p }; break } catch (e: Exception) { }
        }
        if (port > 0) prefs.edit().putInt("padPort", port).apply()
        val s = server ?: return Unit.also { Log.w(TAG, "no free port") }
        (ctx as? android.app.Application)?.registerActivityLifecycleCallbacks(tracker)
        wifiOn()
        tls.start { c -> serve(c) }; presence.attach(); PadService.start(ctx); publish()
        Log.i(TAG, "listening ${url()}")
        Thread({ while (!s.isClosed) try { val c = s.accept(); Thread({ serve(c) }, "pad-conn").apply { isDaemon = true }.start() } catch (e: Exception) { } }, "pad-accept")
            .apply { isDaemon = true }.start()
    }

    /**
     * Navigate mode: a phone's remote keys go to the TV Games screen in front. Android lets an app inject keys into itself only through the view tree
     * (Instrumentation needs INJECT_EVENTS), so the key is dispatched to the resumed activity, and a D-pad key nobody used moves the focus like
     * the system would (focusSearch).
     */
    @Volatile private var top: java.lang.ref.WeakReference<android.app.Activity>? = null
    private val tracker = object : android.app.Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(a: android.app.Activity) { top = java.lang.ref.WeakReference(a) }
        override fun onActivityPaused(a: android.app.Activity) { if (top?.get() === a) top = null }
        override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) {}
        override fun onActivityStarted(a: android.app.Activity) {}
        override fun onActivityStopped(a: android.app.Activity) {}
        override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
        override fun onActivityDestroyed(a: android.app.Activity) {}
    }

    fun nav(name: String) {
        if (name == "HOME") {
            ctx.startActivity(android.content.Intent(ctx, LibraryActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP))
            return
        }
        if (name == "HOLD") {                                              // the pad's OK held: what a long press does there (a tile's menu), else a plain OK
            val a = top?.get() ?: return
            a.runOnUiThread { if (a.currentFocus?.takeIf { it.isLongClickable }?.performLongClick() != true) nav("OK") }
            return
        }
        val code = NAV[name] ?: return
        val a = top?.get() ?: return
        if (name == "BACK" && a is LibraryActivity && a.bare) {            // Back here would leave TV Games and strand the pad (the TV's own remote still can); a menu open over it: Back closes that
            a.runOnUiThread { android.widget.Toast.makeText(a, a.getString(R.string.in_library), android.widget.Toast.LENGTH_SHORT).show() }
            return
        }
        a.runOnUiThread {
            fun ev(act: Int) = SystemClock.uptimeMillis().let { KeyEvent(it, it, act, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD) }
            if (!a.dispatchKeyEvent(ev(KeyEvent.ACTION_DOWN)) && code in FOCUS) {
                val dir = FOCUS.getValue(code)
                val cur = a.currentFocus ?: a.window.decorView
                (cur.focusSearch(dir) ?: cur).requestFocus(dir)
            }
            a.dispatchKeyEvent(ev(KeyEvent.ACTION_UP))
        }
    }

    /**
     * Wi-Fi power save on the TV: the chip sleeps between beacons and parks a frame for up to ~100 ms (more with a long DTIM), on top of
     * every phone's own round trip. The lock keeps it awake while pads are served (low latency mode where Android has it: 10+).
     */
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
    @Suppress("DEPRECATION")
    private fun wifiOn() {
        if (wifiLock != null) return                                  // started again before a stop: a second lock would leave the first held for good
        try {
            val wm = ctx.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager ?: return
            val mode = if (android.os.Build.VERSION.SDK_INT >= 29) android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY else android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wm.createWifiLock(mode, "tvgames:pad").apply { setReferenceCounted(false); acquire() }
        } catch (e: Exception) { Log.w(TAG, "wifi lock: $e") }          // no permission or no Wi-Fi (ethernet): nothing to keep awake
    }
    private fun wifiOff() { try { wifiLock?.takeIf { it.isHeld }?.release() } catch (e: Exception) { }; wifiLock = null }

    fun stop() {
        (ctx as? android.app.Application)?.unregisterActivityLifecycleCallbacks(tracker)
        presence.detach(); PadService.stop(ctx); wifiOff()
        val cs = synchronized(slots) { slots.mapNotNull { it.conn } }
        try { server?.close() } catch (e: Exception) { }; tls.stop()
        offMain { cs.forEach { it.text("{\"closed\":true}"); it.bye() } }     // the pads say "closed" and retry quietly until TV Games is back
    }

    /** The link the QR holds: the TV's Wi-Fi address, the port and the token. */
    // plain http: no certificate warning for whoever only plays by touch; the pad moves to the https port (PadTls) when Tilt is asked for
    fun url(): String = "http://${localIp()}:$port/?k=$token"

    /** What the page's own origin looks like from inside the TV: the web games load from here. */
    fun base(): String = "http://127.0.0.1:$port"

    /** A web game asks for a pad layout: player 1..4, or 0 for everyone (and whoever joins later). null / "null" = the usual pad. */
    fun setLayout(player: Int, json: String?) {
        val j = json?.takeIf { it.length <= 2000 && (it.startsWith("{") || it == "null") }?.takeIf { it != "null" }
        synchronized(slots) {
            if (player == 0) { defaultLayout = j; layouts.fill(null) } else if (player in 1..MAX) layouts[player - 1] = j
        }
        if (lobby && player != 0) return                   // one phone's own layout (a dealt hand) waits until the game starts: the lobby only tries buttons
        send(player, "{\"layout\":${j ?: "null"}}")
    }
    fun clearLayouts() = setLayout(0, null)
    fun say(player: Int, text: String) = send(player, "{\"say\":${org.json.JSONObject.quote(text.take(200))}}")
    fun vibrate(player: Int, ms: Int) = send(player, "{\"vibrate\":${ms.coerceIn(0, 2000)}}")
    /** A socket write on the main thread throws (NetworkOnMainThreadException) and [Conn.frame] would drop the phone: the activities set state there. */
    private val sender = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "pad-send").apply { isDaemon = true } }
    private fun offMain(f: () -> Unit) { if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) sender.execute(f) else f() }
    fun broadcast(msg: String) = send(0, msg)
    fun padCount(): Int = synchronized(slots) { slots.count { it.conn != null } }
    private val presence = Presence(ctx as android.app.Application, this)
    private fun send(player: Int, msg: String) {
        val cs = synchronized(slots) { slots.filterIndexed { i, _ -> player == 0 || player == i + 1 }.mapNotNull { it.conn } }
        offMain { cs.forEach { it.text(msg) } }
    }

    fun addUrl(): String = "http://${localIp()}:$port/add?k=$token"

    // ---- slots ---------------------------------------------------------------------------------------------------------

    private fun reserved(s: Slot, now: Long) = s.conn == null && s.cid != null && now - s.freedAt < KEEP_MS

    /** The slot for a phone: its own again, else the first free one, else one whose keeping time is running. -1 = full. [name]: null = keep what the slot has. */
    private fun assign(cid: String, name: String?, c: Conn): Int {
        val changed: Int
        synchronized(slots) {
            val now = SystemClock.uptimeMillis()
            // the same phone (its Wi-Fi address) already playing under another id: a second browser or tab, whose storage (and so id)
            // is its own. It takes that seat over, as the same id would, instead of a second player (an iPhone's Edge and Chrome were
            // P2 and P3). Two phones behind one address would kick each other: a guest network that isolates phones cannot reach the TV anyway
            val ip = c.sock.inetAddress
            // ... but only of the same kind of phone: an Android and an iPhone behind one address (a repeater in NAT mode, a hotspot) are two players
            val twin = if (slots.any { it.cid == cid && it.conn != null }) -1 else slots.indexOfFirst { o -> o.conn.let { it != null && it !== c && it.sock.inetAddress == ip && it.kind == c.kind } }
            val i = if (twin >= 0) twin else PadNames.pick(slots.map { PadNames.S(it.cid, it.conn != null, it.dev >= 0, reserved(it, now)) }, cid)
            if (i < 0) return -1
            val s = slots[i]
            if (s.cid == cid || i == twin) s.conn?.takeIf { it !== c }?.let { it.text("{\"replaced\":true}"); it.bye() } else s.name = null    // a new phone in a seat: not the last one's name
            if (s.conn !== c) release(i)                   // a reconnect before the old socket was seen dead: what it held went up (the phone sends what it still holds)
            Log.i(TAG, "assign slot ${i + 1}"); s.cid = cid; s.conn = c; s.ready = false
            if (name != null) s.name = name
            changed = i
        }
        saveSeats()
        notifyPlayers()
        return changed
    }

    /** A phone typed its name (empty = back to "Player N"). */
    private fun rename(i: Int, raw: String) {
        val n = PadNames.clean(raw)
        val same = synchronized(slots) { (slots[i].name == n).also { slots[i].name = n } }
        if (same) return
        saveSeats(); notifyPlayers()
    }

    private fun drop(i: Int, c: Conn) {
        synchronized(slots) {
            val s = slots[i]
            if (s.conn !== c) return                       // a reconnect already took the slot over
            release(i)
            s.conn = null; s.freedAt = SystemClock.uptimeMillis(); s.ping = -1; s.offset = Long.MIN_VALUE; s.ready = false
        }
        notifyPlayers(); presence.padsChanged()
    }

    /** Everything slot [i] holds goes up: keys, the stick's D-pad keys, an analog stick back to the middle. Under the slots lock. */
    private fun release(i: Int) {
        val s = slots[i]
        s.held.toList().forEach { onKey(i, KeyEvent.ACTION_UP, it) }; s.held.clear()
        s.dirs = emptySet()
        if (analog && !lobby) onAxis(i, 0f, 0f)
    }

    /** The port of a physical pad (device id), given the first free slot the first time it is seen. -1 = none left. */
    fun padPort(dev: Int): Int {
        var fresh = false
        val r: Int
        synchronized(slots) {
            if (dev in sentOff) return -1
            var i = slots.indexOfFirst { it.dev == dev }
            if (i < 0) {
                val now = SystemClock.uptimeMillis()
                i = slots.indexOfFirst { it.conn == null && it.dev < 0 && !reserved(it, now) }
                if (i >= 0) { slots[i].dev = dev; slots[i].cid = null; slots[i].name = null; fresh = true }   // not the last phone's name
            }
            r = i
        }
        if (fresh) { saveSeats(); notifyPlayers() }
        return r
    }

    private val sentOff = HashSet<Int>()                   // physical pads the TV sent off: their keys take no seat again until unplugged

    /**
     * The TV sends player [player] (1..4) out of the room: a phone hears {kicked} and waits for a tap to join again (a seat as any
     * new phone gets, not this one kept), a physical pad gives up its seat for good.
     */
    fun kick(player: Int) {
        if (player !in 1..MAX) return
        synchronized(slots) {
            val i = player - 1; val s = slots[i]
            release(i)
            s.conn?.let { it.text("{\"kicked\":true}"); it.bye() }
            if (s.dev >= 0) sentOff.add(s.dev)
            Log.i(TAG, "kick p$player"); s.conn = null; s.dev = -1; s.cid = null; s.name = null; s.ready = false; s.ping = -1; s.offset = Long.MIN_VALUE
        }
        saveSeats(); notifyPlayers(); presence.padsChanged(); onReady()
    }

    /** A page closed for good ("bye"): its seat is free at once, not kept the 30 s a dropped link gets. */
    private fun leave(i: Int, c: Conn) {
        synchronized(slots) { val s = slots[i]; if (s.conn === c) { s.cid = null; s.name = null } }
        saveSeats()
    }

    /** Free the slots of pads that were unplugged. */
    fun padGone(present: (Int) -> Boolean) {
        var any = false
        synchronized(slots) { slots.forEach { if (it.dev >= 0 && !present(it.dev)) { it.dev = -1; any = true } }; sentOff.removeAll { !present(it) } }
        if (any) notifyPlayers()
    }

    /** The slots (1..4) in use right now: a screen that starts while phones are already in asks for it. */
    fun playersNow(): List<Int> = synchronized(slots) { slots.indices.filter { slots[it].conn != null || slots[it].dev >= 0 }.map { it + 1 } }

    private fun notifyPlayers() { onPlayers(playersNow()) }

    /** A phone's virtual stick, x / y -1..1 (y down). */
    private fun axis(i: Int, x: Float, y: Float) {
        if (lobby) {                                       // trying the stick in the lobby: the slot lights its direction, the game gets nothing
            val now = dirs(x, y)
            val before = synchronized(slots) { slots[i].dirs.also { slots[i].dirs = now } }
            (before - now).forEach { onLobbyBtn(i + 1, it, false) }; (now - before).forEach { onLobbyBtn(i + 1, it, true) }
            return
        }
        if (analog) { onAxis(i, x.coerceIn(-1f, 1f), y.coerceIn(-1f, 1f)); return }
        val now = dirs(x, y)
        val before = synchronized(slots) { slots[i].dirs.also { slots[i].dirs = now } }
        (before - now).forEach { key(i, false, it) }
        (now - before).forEach { key(i, true, it) }
    }

    /** The press's one-way delay: the TV's clock now minus the phone's time stamp moved onto the TV's clock. -1 = no stamp or no offset yet. */
    private fun delay(i: Int, stamp: Long?): Int {
        val off = slots[i].offset
        if (stamp == null || off == Long.MIN_VALUE) return -1
        return (System.currentTimeMillis() - (stamp + off)).coerceIn(0, 5000).toInt()
    }

    private fun key(i: Int, down: Boolean, name: String, stamp: Long? = null) {
        val code = (if (!webMode) PadMap.phone(system, name) else null) ?: KEYS[name] ?: return
        if (lobby) { onLobbyBtn(i + 1, name, down); return }
        if (!webMode && name !in buttons && name !in DPAD && name !in CHOICE) return
        synchronized(slots) { if (down) slots[i].held.add(code) else slots[i].held.remove(code) }
        val t0 = System.nanoTime()
        val act = if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP
        onKeyLat?.let { it(i, act, code, delay(i, stamp)) } ?: onKey(i, act, code)
        Log.d(TAG, "key p${i + 1} ${if (down) "d" else "u"} $name handled in ${(System.nanoTime() - t0) / 1000} us")
    }

    // ---- HTTP + WebSocket ----------------------------------------------------------------------------------------------

    private inner class Conn(val sock: Socket) {
        @Volatile var kind = ""                                // "ios" / "android" / "other", from the upgrade request's User-Agent
        val out: OutputStream = sock.getOutputStream()
        // One writer thread per phone with its own queue, and every frame leaves in ONE write. Before, a frame went out as 3-4 tiny writes
        // (a TLS record each on the https port, a lone TCP segment each on http) and a broadcast wrote to the phones one after the other on
        // one thread: an iPhone that stopped reading (page frozen, bad Wi-Fi) held up the Android's ping answers and the game's messages.
        private val q = java.util.concurrent.LinkedBlockingQueue<ByteArray>(QUEUE)
        @Volatile private var writer: Thread? = null
        fun close() { try { sock.close() } catch (e: Exception) { }; q.offer(END) }
        /** Closes once what was queued (a "replaced", "closed", "rescan" the phone must read) has been written. */
        fun bye() { if (writer == null || !q.offer(END)) close() }
        fun text(s: String) = frame(1, s.toByteArray())
        fun startWriter() {
            writer = Thread({
                val buf = java.io.ByteArrayOutputStream()
                try {
                    var end = false
                    while (!end) {
                        buf.reset(); var f = q.take()
                        while (true) { if (f === END) { end = true; break }; buf.write(f); f = q.poll() ?: break }   // what piled up meanwhile goes in one write
                        if (buf.size() > 0) { out.write(buf.toByteArray()); out.flush() }
                    }
                } catch (e: Exception) { }
                close()
            }, "pad-out").apply { isDaemon = true; start() }
        }
        fun frame(op: Int, b: ByteArray) {
            val h = when { b.size < 126 -> byteArrayOf((0x80 or op).toByte(), b.size.toByte())
                b.size < 65536 -> byteArrayOf((0x80 or op).toByte(), 126, (b.size shr 8).toByte(), b.size.toByte())
                else -> ByteArray(10).also { it[0] = (0x80 or op).toByte(); it[1] = 127; for (k in 0 until 8) it[2 + k] = (b.size.toLong() shr (8 * (7 - k))).toByte() } }
            if (writer == null) { try { out.write(h + b); out.flush() } catch (e: Exception) { close() }; return }
            if (!q.offer(h + b)) close()                   // 256 frames behind: that phone is gone, let go of it rather than hold memory
        }
    }

    private fun serve(sock: Socket) {
        // the TLS handshake runs in Conn's getOutputStream(): a browser that refuses the self-signed certificate ends it there
        val conn = try { Conn(sock) } catch (e: Exception) { try { sock.close() } catch (x: Exception) { }; return }
        try {
            sock.tcpNoDelay = true
            sock.soTimeout = 15_000                                   // a connection that never says anything (a scanner, Wi-Fi gone) does not keep a thread
            val inp = BufferedInputStream(sock.getInputStream())
            val first = line(inp) ?: return
            val h = HashMap<String, String>()
            while (true) { val l = line(inp) ?: return; if (l.isEmpty()) break; if (h.size > 100) return; val c = l.indexOf(':'); if (c > 0) h[l.substring(0, c).trim().lowercase()] = l.substring(c + 1).trim() }
            val parts = first.split(' ')
            if (parts.size < 2 || (parts[0] != "GET" && parts[0] != "POST")) return reply(conn, 405, "text/plain", "no".toByteArray())
            val path = parts[1].substringBefore('?')
            val q = parts[1].substringAfter('?', "").split('&').associate { it.substringBefore('=') to it.substringAfter('=', "") }
            // the web games' own files (and what they load next to them) go by a cookie set with the first, tokened request
            val cookie = h["cookie"]?.split(';')?.map { it.trim() }?.firstOrNull { it.startsWith("tvk=") }?.substring(4)
            if (q["k"] != token && !(path.startsWith("/webgames/") && cookie == token)) return reply(conn, 403, "text/plain", "forbidden".toByteArray())
            if (parts[0] == "POST") return when (path) { "/upload" -> upload(conn, inp, h, q); "/weblink" -> weblink(conn, q); else -> reply(conn, 404, "text/plain", "no".toByteArray()) }
            if (path.startsWith("/webgames/")) return webfile(conn, path.removePrefix("/webgames/"), q["k"] == token)
            if (path == "/ws" && h["upgrade"]?.lowercase() == "websocket") { conn.kind = kindOf(h["user-agent"]); return socket(conn, inp, h["sec-websocket-key"] ?: return) }
            if (path == "/words.js") return words(conn, h["accept-language"])
            val name = when (path) { "/", "/index.html" -> "index.html"; "/pad.css" -> "pad.css"; "/pad.js" -> "pad.js"; "/add" -> "add.html"; "/layouts.json" -> "layouts.json"; "/manifest.webmanifest" -> "manifest.webmanifest"; "/icon.png" -> "icon.png"; else -> null }
                ?: return reply(conn, 404, "text/plain", "not found".toByteArray())
            var body = if (name == "layouts.json") Catalog.layouts(ctx).toByteArray() else ctx.assets.open("pad/$name").use { it.readBytes() }   // the catalog's pad overrides
            if (name.endsWith(".html") || name.endsWith(".webmanifest")) body = String(body).replace("__K__", token).replace("__TLS__", tls.port.toString()).toByteArray()
            reply(conn, 200, when { name.endsWith(".css") -> "text/css"; name.endsWith(".js") -> "text/javascript"; name.endsWith(".json") -> "application/json"; name.endsWith(".webmanifest") -> "application/manifest+json"; name.endsWith(".png") -> "image/png"; else -> "text/html" } + "; charset=utf-8", body)
        } catch (e: Exception) {
        } finally { conn.close() }                                    // the early returns too (no websocket key, a header cut off)
    }

    /** The pad pages' words as `window.W`: English, overlaid by the first Accept-Language tag that is a language of langs.json. */
    private fun words(c: Conn, accept: String?) {
        fun load(l: String) = try { org.json.JSONObject(String(ctx.assets.open("pad/words/$l.json").use { it.readBytes() }, Charsets.UTF_8)) } catch (e: Exception) { org.json.JSONObject() }
        val lang = (accept ?: "").split(',').firstNotNullOfOrNull { dev.tvremote.i18n.Langs.of(it.substringBefore(';').trim()) } ?: "en"
        val w = load("en")
        if (lang != "en") load(lang).let { o -> o.keys().forEach { w.put(it, o.get(it)) } }
        val js = "window.W = $w; document.documentElement.lang = ${org.json.JSONObject.quote(lang)};"
        reply(c, 200, "application/javascript; charset=utf-8", js.toByteArray(Charsets.UTF_8), "Vary: Accept-Language\r\n")
    }

    private fun webfile(c: Conn, rel0: String, setCookie: Boolean) {
        var rel = rel0
        if (rel.contains("..") || !Regex("[A-Za-z0-9_./-]*").matches(rel)) return reply(c, 404, "text/plain", "no".toByteArray())
        if (rel.isEmpty() || rel.endsWith("/")) rel += "index.html" else if (!rel.substringAfterLast('/').contains('.')) rel += "/index.html"
        val body = try { ctx.assets.open("webgames/$rel").use { it.readBytes() } } catch (e: Exception) { return reply(c, 404, "text/plain", "no".toByteArray()) }
        val type = when (rel.substringAfterLast('.')) { "html" -> "text/html"; "js" -> "text/javascript"; "css" -> "text/css"; "json" -> "application/json"
            "png" -> "image/png"; "svg" -> "image/svg+xml"; "mp3" -> "audio/mpeg"; "ogg" -> "audio/ogg"; else -> "application/octet-stream" }
        reply(c, 200, "$type; charset=utf-8", body, if (setCookie) "Set-Cookie: tvk=$token; Path=/webgames/\r\n" else "")
    }

    /** A web game link from the /add page: name + http(s) address into files/web.json. */
    private fun weblink(c: Conn, q: Map<String, String>) {
        fun dec(s: String?) = try { java.net.URLDecoder.decode(s ?: "", "UTF-8") } catch (e: Exception) { "" }
        val ok = Systems.addWeb(ctx, dec(q["name"]).trim(), dec(q["url"]).trim())
        reply(c, if (ok) 200 else 400, "text/plain", (if (ok) "ok" else "bad").toByteArray())
        if (ok) onAdded()
    }

    /** A game from the phone: the body (a raw file, Content-Length known) goes to files/roms through a temp file. */
    private fun upload(c: Conn, inp: InputStream, h: Map<String, String>, q: Map<String, String>) {
        val len = h["content-length"]?.toLongOrNull() ?: return reply(c, 411, "text/plain", "length".toByteArray())
        val name = safeName(try { java.net.URLDecoder.decode(q["name"] ?: "", "UTF-8") } catch (e: Exception) { "" })
            ?: return reply(c, 415, "text/plain", "type".toByteArray())
        if (len <= 0 || len > MAX_UPLOAD) return reply(c, 413, "text/plain", "size".toByteArray())
        c.sock.soTimeout = 30_000
        val dir = Systems.romsDir(ctx)
        val tmp = File(dir, ".up-${System.nanoTime()}")
        try {
            tmp.outputStream().use { o -> val buf = ByteArray(64 * 1024); var left = len
                while (left > 0) { val r = inp.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (r < 0) throw java.io.EOFException(); o.write(buf, 0, r); left -= r } }
            val dst = File(dir, name)
            if (dst.exists()) dst.delete()
            if (!tmp.renameTo(dst)) throw java.io.IOException("rename")
        } catch (e: Exception) { tmp.delete(); return reply(c, 500, "text/plain", "fail".toByteArray()) }
        reply(c, 200, "text/plain", "ok".toByteArray())
        lastRom = name; onAdded()
    }

    /** The last path part, only plain characters, an extension a core exists for. null = refuse. */
    private fun safeName(raw: String): String? {
        val base = raw.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[^\\p{L}\\p{N} ._()\\[\\]!&'+,-]"), "_").trimStart('.', ' ').take(100)
        val ext = base.substringAfterLast('.', "").lowercase()
        return if (base.length > ext.length + 1 && ext in Systems.uploadExts(ctx)) base else null
    }

    private fun reply(c: Conn, code: Int, type: String, body: ByteArray, extra: String = "") {
        val head = "HTTP/1.1 $code ${if (code == 200) "OK" else "Error"}\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nCache-Control: no-store\r\n${extra}Connection: close\r\n\r\n"
        try { c.out.write(head.toByteArray()); c.out.write(body); c.out.flush() } catch (e: Exception) { }
        c.close()
    }

    private fun socket(c: Conn, inp: InputStream, key: String) {
        val acc = Base64.encodeToString(MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()), Base64.NO_WRAP)
        c.out.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $acc\r\n\r\n".toByteArray()); c.out.flush(); c.startWriter()
        c.sock.soTimeout = 75_000; c.sock.keepAlive = true           // a phone gone (battery, Wi-Fi) frees its seat: it pings every second, a hidden tab once a minute
        var slot = -1; var full = false
        try {
            while (true) {
                val b0 = inp.read(); if (b0 < 0) break
                val op = b0 and 15
                val b1 = inp.read(); if (b1 < 0) break
                var len = (b1 and 127).toLong()
                if (len == 126L) len = ((inp.read() shl 8) or inp.read()).toLong()
                else if (len == 127L) { len = 0; repeat(8) { len = (len shl 8) or inp.read().toLong() } }
                val mask = ByteArray(4); if (b1 and 128 != 0) readFully(inp, mask)
                if (len > 4096) break
                val data = ByteArray(len.toInt()); readFully(inp, data)
                if (b1 and 128 != 0) for (k in data.indices) data[k] = (data[k].toInt() xor mask[k % 4].toInt()).toByte()
                when (op) {
                    8 -> break
                    9 -> c.frame(10, data)
                    1 -> {
                        val msg = String(data)
                        if (slot >= 0 && (msg.length > 1 && msg[1] == ' ' && msg[0] in PLAY || msg == "pc")) onPlay()
                        when {
                            msg.startsWith("hello ") -> {
                                val rest = msg.substring(6).trim()                 // "hello <client id> [name]"
                                val i = assign(rest.substringBefore(' ').take(40), if (' ' in rest) PadNames.clean(rest.substringAfter(' ')) else null, c)
                                if (i < 0) { c.text("{\"full\":true}"); full = true; break }
                                slot = i
                                c.text("{\"slot\":${i + 1},\"game\":$inGame,\"btns\":[${buttons.joinToString(",") { "\"$it\"" }}],\"sys\":\"$system\",\"name\":${org.json.JSONObject.quote(nameOf(i + 1) ?: "")},\"lobby\":$lobby,\"web\":$webMode,\"max\":$lobbyMax,\"ready\":false}")
                                (synchronized(slots) { (if (lobby) null else layouts[i]) ?: defaultLayout })?.let { c.text("{\"layout\":$it}") }
                                if (presence.away) c.text(presence.state())
                                presence.padsChanged()
                            }
                            msg.startsWith("p ") -> c.text(msg.trim() + " " + System.currentTimeMillis())          // the phone's stamp back + the TV's clock
                            slot >= 0 && msg.startsWith("r ") -> msg.split(' ').let { if (it.size == 3) report(slot, it[1].toIntOrNull(), it[2].toLongOrNull()) }
                            slot >= 0 && !lobby && msg.startsWith("m ") -> msg.split(' ').let { if (it.size == 3) onPointer(slot + 1, (it[1].toIntOrNull() ?: 0).coerceIn(-400, 400), (it[2].toIntOrNull() ?: 0).coerceIn(-400, 400), false) }
                            slot >= 0 && !lobby && msg == "pc" -> onPointer(slot + 1, 0, 0, true)
                            // numbers only reach the page: anything else in it and the whole message is dropped
                            slot >= 0 && !lobby && msg.startsWith("o ") -> msg.split(' ').drop(1).mapNotNull { it.toIntOrNull()?.coerceIn(-100000, 100000) }
                                .let { if (it.size == 10) onMotion(slot + 1, it.toIntArray()) }
                            slot >= 0 && msg.startsWith("ready ") -> if (lobby) { slots[slot].ready = msg.trim().endsWith("1"); Log.i(TAG, "ready p${slot + 1} ${slots[slot].ready}"); onReady() }
                            slot >= 0 && msg.startsWith("name") -> rename(slot, msg.substring(4))
                            msg.startsWith("n ") -> nav(msg.substring(2).trim())
                            msg == "open" -> presence.open()
                            slot >= 0 && msg == "bye" -> { leave(slot, c); break }
                            slot >= 0 && msg == "reload" && webMode -> onReload()
                            slot >= 0 && msg.startsWith("a ") -> msg.split(' ').let { if (it.size == 3) axis(slot, (it[1].toIntOrNull() ?: 0) / 100f, (it[2].toIntOrNull() ?: 0) / 100f) }
                            slot >= 0 && msg.startsWith("t ") -> onText(slot + 1, msg.substring(2).take(200))
                            slot >= 0 && msg.length >= 3 && (msg[0] == 'd' || msg[0] == 'u') -> msg.substring(2).trim().split(' ').let { key(slot, msg[0] == 'd', it[0], it.getOrNull(1)?.toLongOrNull()) }
                        }
                    }
                }
            }
        } catch (e: Exception) { Log.w(TAG, "socket p${slot + 1} ended: $e") }
        if (slot >= 0) drop(slot, c)
        if (full) c.bye() else c.close()
    }

    /** A phone's own measure: median round trip and clock offset. */
    private fun report(i: Int, ping: Int?, offset: Long?) {
        if (ping == null || ping !in 0..10_000) return
        slots[i].ping = ping; if (offset != null) slots[i].offset = offset
        onPing(i + 1, ping)
    }

    private fun readFully(i: InputStream, b: ByteArray) { var n = 0; while (n < b.size) { val r = i.read(b, n, b.size - n); if (r < 0) throw java.io.EOFException(); n += r } }
    private fun line(i: InputStream): String? {
        val sb = StringBuilder()
        while (true) { val c = i.read(); if (c < 0) return null; if (c == '\n'.code) break; if (c != '\r'.code) sb.append(c.toChar()); if (sb.length > 8192) return null }
        return sb.toString()
    }

    companion object {
        private const val PLAY = "aduomtn"                 // what a player sends: stick, down / up, pointer, motion, text, menu moves ("p " is a ping)
        private const val TAG = "GameServer"
        private const val PORT = 8780
        private const val MAX = 4
        private const val MAX_UPLOAD = 64L * 1024 * 1024
        private const val QUEUE = 256
        private val END = ByteArray(0)
        private const val KEEP_MS = 30_000L
        fun kindOf(ua: String?): String = when { ua == null -> "other"; Regex("iPhone|iPad|iPod|Macintosh").containsMatchIn(ua) -> "ios"; ua.contains("Android") -> "android"; else -> "other" }
        private val DPAD = setOf("UP", "DOWN", "LEFT", "RIGHT")

        /**
         * A game pad of its own, not the TV remote: some boxes' remotes say GAMEPAD too (FPT's IR remote rtk-ir-hw), and taken
         * as a pad one key gave it seat 1, so a card game moved the hand off the screen. A pad has sticks or presses pad buttons. A
         * stick is an X or hat axis: the FPT Play Bluetooth remote says JOYSTICK for one GENERIC_1 axis (its mic), and every press of it
         * took a seat under the name of the phone that sat there last.
         */
        fun isPad(ev: KeyEvent): Boolean {
            val d = ev.device ?: return false
            return hasStick(d) ||
                (ev.source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD && KeyEvent.isGamepadButton(ev.keyCode))
        }
        fun hasStick(d: InputDevice?): Boolean = d != null && d.sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK &&
            (d.getMotionRange(MotionEvent.AXIS_X) != null || d.getMotionRange(MotionEvent.AXIS_HAT_X) != null)
        /** A stick as D-pad directions: 8-way, dead zone .35. */
        fun dirs(x: Float, y: Float): Set<String> {
            val r = HashSet<String>()
            if (Math.hypot(x.toDouble(), y.toDouble()) < 0.35) return r
            val a = Math.toDegrees(Math.atan2(y.toDouble(), x.toDouble()))     // 0 = right, 90 = down
            if (a > -67.5 && a < 67.5) r.add("RIGHT"); if (a > 112.5 || a < -112.5) r.add("LEFT")
            if (a > 22.5 && a < 157.5) r.add("DOWN"); if (a < -22.5 && a > -157.5) r.add("UP")
            return r
        }
        private val NAV = mapOf("UP" to KeyEvent.KEYCODE_DPAD_UP, "DOWN" to KeyEvent.KEYCODE_DPAD_DOWN, "LEFT" to KeyEvent.KEYCODE_DPAD_LEFT,
            "RIGHT" to KeyEvent.KEYCODE_DPAD_RIGHT, "OK" to KeyEvent.KEYCODE_DPAD_CENTER, "BACK" to KeyEvent.KEYCODE_BACK)
        private val FOCUS = mapOf(KeyEvent.KEYCODE_DPAD_UP to View.FOCUS_UP, KeyEvent.KEYCODE_DPAD_DOWN to View.FOCUS_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT to View.FOCUS_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT to View.FOCUS_RIGHT)
        private val CHOICE = setOf("C", "D", "L2", "R2")                   // answer buttons of web games (A and B are the pad's own)
        private fun newToken(): String = ByteArray(12).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val KEYS = mapOf(
            "UP" to KeyEvent.KEYCODE_DPAD_UP, "DOWN" to KeyEvent.KEYCODE_DPAD_DOWN, "LEFT" to KeyEvent.KEYCODE_DPAD_LEFT, "RIGHT" to KeyEvent.KEYCODE_DPAD_RIGHT,
            "A" to KeyEvent.KEYCODE_BUTTON_A, "B" to KeyEvent.KEYCODE_BUTTON_B, "X" to KeyEvent.KEYCODE_BUTTON_X, "Y" to KeyEvent.KEYCODE_BUTTON_Y,
            "L" to KeyEvent.KEYCODE_BUTTON_L1, "R" to KeyEvent.KEYCODE_BUTTON_R1, "START" to KeyEvent.KEYCODE_BUTTON_START, "SELECT" to KeyEvent.KEYCODE_BUTTON_SELECT,
            "L2" to KeyEvent.KEYCODE_BUTTON_L2, "R2" to KeyEvent.KEYCODE_BUTTON_R2, "C" to KeyEvent.KEYCODE_BUTTON_C, "D" to KeyEvent.KEYCODE_BUTTON_Z,
        )

        private var inst: GameServer? = null
        private var users = 0
        /** One server for the library and the game: started by the first activity, stopped when the last one goes. */
        @Synchronized fun acquire(ctx: Context): GameServer {
            val g = inst ?: GameServer(ctx.applicationContext).also { inst = it; it.start() }
            users++; return g
        }
        /** Gives up now (away too long): the server and its service stop, whatever activities are left. */
        @Synchronized fun shutdown() { inst?.stop(); inst = null; users = 0 }
        @Synchronized fun release() { if (--users <= 0) { inst?.stop(); inst = null; users = 0 } }

        fun localIp(): String {
            val all = try { NetworkInterface.getNetworkInterfaces().toList() } catch (e: Exception) { emptyList() }
            val ips = all.filter { it.isUp && !it.isLoopback }.flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>().filter { it.isSiteLocalAddress }
            return (ips.firstOrNull { it.hostAddress?.startsWith("192.168.") == true } ?: ips.firstOrNull())?.hostAddress ?: "127.0.0.1"
        }
    }
}

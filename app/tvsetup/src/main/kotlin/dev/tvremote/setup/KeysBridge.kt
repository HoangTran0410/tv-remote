package dev.tvremote.setup

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.util.SparseArray
import android.widget.Toast
import android.view.KeyEvent
import dev.tvremote.core.T
import dev.tvremote.setup.keys.KeysProtocol

/**
 * Gives a button back that only the stock launcher answered (see [KeyFix]). The accessibility service that sees the keys is the one of
 * the add-on TV Setup Keys (`app/tvkeys`, so that this app declares none: dev/decisions/apps.md "TV Setup declares no"); it asks this bound service about every key it may
 * care about ([KeysProtocol.INTEREST]) and does what it answers: taken or pass. Kept on in the secure settings by [KeyFix.sync] only
 * while such a key exists. The hot path is a lookup in a small array and nothing else; a key not in [plan] is passed at once, and
 * anything that goes wrong passes the key too.
 *
 * Keys from any device are taken (the remote's, a keyboard, the phone's through the TV Remote protocol): a key in the plan has no
 * handler left, so nothing else could have used it. `input keyevent` over ADB is injected after the filter and never shows here.
 * Not taken while the screen is off or the add-on's KeyCatch is waiting for the key that wakes it (the add-on checks both): that key keeps its old way.
 *
 * Also the filter for "hold to use Input / Settings" ([HoldKeys]): while that rule is on the service stays on even with no dead key.
 * And for [HomeKey]: Home to the launcher picked where the swap is not possible, a number held in another app, the toast when a key
 * the person gave an app opens it.
 */
class KeysBridge : Service() {
    @Volatile private var plan = SparseArray<KeyFix.Rule>()
    @Volatile private var digits = SparseArray<String>()               // a number → its app, opened by a hold outside TV Home
    private val ui = Handler(Looper.getMainLooper())
    private var watching = false
    private val gate = HoldKeys.Gate()
    @Volatile private var mic = false                                 // the rule "mic" and a recognizer on this TV (MicVoice), read in refresh(): no disk in the key path
    @Volatile private var extraOf: KeyList.All? = null                // KeysProtocol.TX_CODES: worked out again only when the site's list changed
    @Volatile private var extra: Set<Int> = emptySet()
    @Volatile private var extraRev = 0

    /** The keys the site's list gives this TV that the add-on does not ask about; in memory, so cheap on the key path. */
    private fun extras(): Int {
        val l = KeyList.now
        if (l !== extraOf) {
            extra = KeyFix.forDevice(Build.MODEL, Build.DEVICE, l.keys).keys - KeysProtocol.INTEREST - KeyList.NEVER
            extraRev = if (extra.isEmpty()) 0 else extra.hashCode() or 1
            extraOf = l
        }
        return extraRev
    }
    @Volatile private var hold = false                                // the rule "holdkeys", read in refresh(): no disk in the key path
    private var armed = -1
    private var hint: Toast? = null
    private val fire = Runnable { if (synchronized(gate) { gate.timer(armed) }) held(armed) }
    @Volatile private var homeDown = 0L                               // the Home press under way (its down time), 0: none
    @Volatile private var homeLong = false
    private val homeHeld = Runnable { if (homeDown != 0L) { homeLong = true; HomeKey.longPress() } }
    @Volatile private var digit = -1                                  // the number held now, and the app on screen when it went down
    @Volatile private var digitFront = ""
    private val digitHeld = Runnable { val d = digit; val p = digits.get(d); if (p != null && digitFront != HomeKey.TV_HOME) { digit = -1; act(KeyFix.userRule(p), d, digitFront) } }

    private val packages = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { refresh() }
    }

    /** The add-on's side of [KeysProtocol.TX_KEY], on a binder thread: answered in microseconds, the toast and the action go to the main thread. */
    private val binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == KeysProtocol.TX_CODES) {
                data.enforceInterface(KeysProtocol.DESCRIPTOR)
                val r = extras(); val x = extra
                reply?.writeNoException(); reply?.writeInt(r); reply?.writeInt(x.size); x.forEach { reply?.writeInt(it) }
                return true
            }
            if (code != KeysProtocol.TX_KEY) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(KeysProtocol.DESCRIPTOR)
            val key = data.readInt(); val action = data.readInt(); val repeat = data.readInt()
            val down = data.readLong(); val at = data.readLong()
            val eat = try { handle(key, action, repeat, down, at) } catch (t: Throwable) { false }
            reply?.writeNoException()
            reply?.writeInt(if (eat) 1 else 0)
            reply?.writeInt(extras())
            return true
        }
    }

    override fun onBind(i: Intent?): IBinder = binder

    override fun onCreate() {
        running = this
        refresh()
        if (!watching) try {
            registerReceiver(packages, IntentFilter().apply {         // a launcher turned off or back on, an app installed: the plan follows
                addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED); addAction(Intent.ACTION_PACKAGE_CHANGED)
                addDataScheme("package")
            })
            watching = true
        } catch (e: Exception) { }
    }

    companion object {
        @Volatile private var running: KeysBridge? = null
        /** The map of TV Home's keys changed: the plan is made again at once (a bridge that is not up yet reads it when the add-on binds). */
        fun poke() { running?.refresh() }
        /** The add-on's service is bound: the key filter is working (the key tester says so). */
        fun bound(): Boolean = running != null

        @Volatile private var captureUntil = 0L
        /** TV Home's assign-a-key page is open ([on]): for 3 minutes (a page that never says it closed lets go by itself) a mapped key passes to the screen under. */
        fun capture(on: Boolean) { captureUntil = if (on) android.os.SystemClock.elapsedRealtime() + 180_000 else 0L }

        /**
         * A key sent by a client (not a finger on the remote): `input keyevent` is injected after the key filter, so the same
         * decision is made here, on request: TV Home's map first, then the dead-key rule. False: nothing to do, press it plainly.
         */
        fun press(c: Context, code: Int): Boolean {
            val rule = try { KeyFix.decide(KeyFix.current(c), code) } catch (e: Exception) { null } ?: return false
            val s = running
            if (s != null) s.ui.post { s.act(rule, code) } else KeyFix.run(c, rule)   // the bridge is up whenever a key is in the plan; its context may start an activity from the background
            return true
        }
    }

    private fun refresh() {
        Thread({
            val p = SparseArray<KeyFix.Rule>()
            try { if (KeyFix.wanted(this)) for ((k, v) in KeyFix.current(this)) p.put(k, v) } catch (e: Exception) { }   // switched off: the service only holds
            try { HomeKey.pkg(this)?.let { p.put(HomeKey.CODE, KeyFix.Rule(KeyFix.LAUNCHER, listOf(it))) } } catch (e: Exception) { }   // asked for on the page: not under the dead-key switch
            val d = SparseArray<String>()
            try { for ((k, v) in KeyFix.digits(this)) d.put(k, v) } catch (e: Exception) { }
            plan = p
            digits = d
            hold = HoldKeys.on(this)
            mic = MicVoice.on(this) && micAvailable()
            if (p.size() == 0 && d.size() == 0) KeyFix.sync(this)                      // nothing left to bring back (and no hold): out of the key filter
        }, "keyfix").apply { isDaemon = true }.start()
    }

    /** A recognizer is there: else the mic button passes to the system, and a notice says so once (the person may have switched the rule on for a TV without one). */
    private fun micAvailable(): Boolean {
        if (MicVoice.available(this)) return true
        if (!MicVoice.saidNoRecognizer(this)) { MicVoice.sayNoRecognizer(this); ui.post { say(T("mic.no_recognizer")) } }
        return false
    }

    private fun handle(code: Int, action: Int, repeat: Int, downTime: Long, eventTime: Long): Boolean {
        KeyTest.sink?.takeIf { KeyTest.live() }?.let { it(code, action, repeat, downTime, eventTime); return true }   // the key tester is open: it shows the key, nothing acts on it
        if (action == KeyEvent.ACTION_DOWN && repeat == 0 && Support.open()) android.util.Log.i("KeyFix", "key $code")   // only while support looks: which code a remote's button sends
        if (code in MicVoice.KEYS) when (MicVoice.step(mic && !MicVoice.broken(), MicActivity.live != null, code, action, repeat, downTime, eventTime).also {
            android.util.Log.i("MicVoice", "key $code action $action repeat $repeat -> $it (on=$mic)")   // adb logcat -s MicVoice: which key a remote's mic button sends
        }) {
            MicVoice.Step.PASS -> return false
            MicVoice.Step.START -> { ui.post { try { MicActivity.open(this) } catch (e: Exception) { } }; return true }
            MicVoice.Step.STOP, MicVoice.Step.RELEASE -> { MicActivity.enough(); return true }
            MicVoice.Step.EAT -> return true
        }
        when (synchronized(gate) { gate.onKey(hold, code, action, repeat, downTime, eventTime) }) {   // binder threads: one key at a time
            HoldKeys.Step.PASS -> { }
            HoldKeys.Step.ARM -> {                                    // a short press does nothing but say how: the hold acts at HOLD_MS
                armed = code
                ui.post { say(HoldKeys.hint(code)) }
                ui.removeCallbacks(fire); ui.postDelayed(fire, HoldKeys.HOLD_MS)
                return true
            }
            HoldKeys.Step.FIRE -> { ui.post { held(code) }; return true }
            HoldKeys.Step.EAT -> { if (action == KeyEvent.ACTION_UP) ui.removeCallbacks(fire); return true }   // released early: the timer must not act
        }
        if (android.os.SystemClock.elapsedRealtime() < captureUntil) return false   // TV Home is asking which key to give an app: it hears the key, nothing opens
        if (code in HomeKey.DIGITS) return number(code, action, repeat)
        val rule = plan.get(code) ?: return false
        if (code == HomeKey.CODE) return home(rule, action, repeat, downTime)
        if (action == KeyEvent.ACTION_DOWN && repeat == 0) ui.post { act(rule, code) }
        return true                                                   // its up and its repeats too, or the app under would see half a press
    }

    /** Home with [HomeKey]'s rule: eaten whole; let go before [HomeKey.LONG_MS] opens the launcher, held is the TV's own long press. */
    private fun home(rule: KeyFix.Rule, action: Int, repeat: Int, downTime: Long): Boolean {
        if (action == KeyEvent.ACTION_DOWN && repeat == 0) {
            homeDown = downTime; homeLong = false
            ui.removeCallbacks(homeHeld); ui.postDelayed(homeHeld, HomeKey.LONG_MS)
        } else if (action == KeyEvent.ACTION_UP) {
            ui.removeCallbacks(homeHeld)
            if (homeDown != 0L && !homeLong) ui.post { act(rule, HomeKey.CODE) }
            homeDown = 0L
        }
        return true
    }

    /**
     * A number the person gave an app: it always passes (the app under keeps its channel numbers); held [HoldKeys.HOLD_MS] outside
     * TV Home, its app opens. On TV Home the press already opened it there (TV Home's own keys), so the hold does nothing more.
     */
    private fun number(code: Int, action: Int, repeat: Int): Boolean {
        if (digits.get(code) == null) return false
        if (action == KeyEvent.ACTION_DOWN && repeat == 0) {
            digit = code; digitFront = HomeKey.TV_HOME                // until the look below says otherwise: never opens twice on TV Home
            ui.removeCallbacks(digitHeld)
            Thread({ val f = Usage.front(this); if (digit == code) digitFront = f }, "digit-front").apply { isDaemon = true }.start()
            ui.postDelayed(digitHeld, HoldKeys.HOLD_MS)
        } else if (action == KeyEvent.ACTION_UP && code == digit) { ui.removeCallbacks(digitHeld); digit = -1 }
        return false
    }

    /** The key was held long enough: what it does when not held back (the dead-key rule if it has one, else the stock launcher's own). */
    private fun held(code: Int) {
        hint?.cancel()
        act(plan.get(code) ?: KeyFix.RULES[code] ?: KeyFix.Rule(KeyFix.INPUT), code)   // the direct HDMI keys land on the input picker
    }

    private fun say(text: String) {
        hint?.cancel()
        hint = try { Toast.makeText(this, text, Toast.LENGTH_SHORT).also { it.show() } } catch (e: Exception) { null }
    }

    /** Does [r]; when it is the person's own key → app, says so unless TV Home is on screen ([front], else looked up now, off the main thread). */
    private fun act(r: KeyFix.Rule, code: Int = 0, front: String? = null) {
        if (!r.mine || r.act != KeyFix.APP) { KeyFix.run(this, r); return }
        Thread({
            val f = front ?: Usage.front(this)                        // before it opens: after, the app on screen is the one opened
            val text = if (HomeKey.say(f)) HomeKey.said(this, code, r.pkgs[0]) else null
            ui.post { KeyFix.run(this, r); if (text != null) say(text) }
        }, "key-said").apply { isDaemon = true }.start()
    }

    override fun onDestroy() {                                        // the add-on let go (its service is off, or it was updated)
        plan = SparseArray()
        digits = SparseArray()
        hold = false
        mic = false
        ui.removeCallbacks(fire); ui.removeCallbacks(homeHeld); ui.removeCallbacks(digitHeld)
        if (running === this) running = null
        if (watching) try { unregisterReceiver(packages) } catch (e: Exception) { }
        watching = false
        super.onDestroy()
    }
}

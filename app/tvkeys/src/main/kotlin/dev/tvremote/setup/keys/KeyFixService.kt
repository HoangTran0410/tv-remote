package dev.tvremote.setup.keys

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.PowerManager
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * The key filter of TV Setup's "dead remote buttons" and "hold to use Input / Settings". Thin on purpose: the plan, the hold timer and
 * what each key does stay in TV Setup (KeyFix, HoldKeys), so this app has to change almost never and never knows a rule. A key that is not
 * in [KeysProtocol.INTEREST] passes with no call at all; one that is goes to TV Setup's [KeysProtocol.BRIDGE], which answers "taken" or
 * "pass" at once. Anything that goes wrong (TV Setup not running yet, gone, slow) passes the key: the remote must never be worse for this.
 *
 * Kept on in the secure settings by TV Setup, only while a dead key or the hold rule needs it.
 */
class KeyFixService : AccessibilityService() {
    private var power: PowerManager? = null
    @Volatile private var bridge: IBinder? = null
    @Volatile private var extra: Set<Int> = emptySet()                // KeysProtocol.TX_CODES: this TV's odd buttons from the site's list
    @Volatile private var rev = 0
    private var bound = false
    private val ui = Handler(Looper.getMainLooper())

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName, b: IBinder) { bridge = b; codes(b) }
        override fun onServiceDisconnected(n: ComponentName) { bridge = null }   // TV Setup's process went: the system brings the binding back with it
        override fun onBindingDied(n: ComponentName) { bridge = null; rebind() }   // TV Setup was updated: the binding is dead, make a new one
        override fun onNullBinding(n: ComponentName) { bridge = null }
    }

    /** TV Setup's "Power off" on a box with no ADB: the system's own power menu (the person still picks in it). Only TV Setup's key may send it. */
    private val menu = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { performGlobalAction(GLOBAL_ACTION_POWER_DIALOG) }
    }
    private var menuOn = false

    override fun onServiceConnected() {
        power = getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (!menuOn) menuOn = try {
            val f = IntentFilter(KeysProtocol.ACTION_POWER_MENU)                 // exported, but only TV Setup's signature permission may send
            if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(menu, f, KeysProtocol.PERMISSION, null, Context.RECEIVER_EXPORTED)
            else registerReceiver(menu, f, KeysProtocol.PERMISSION, null)
            true
        } catch (e: Exception) { false }
        bind()
    }

    private fun unmenu() { if (menuOn) try { unregisterReceiver(menu) } catch (e: Exception) { }; menuOn = false }

    private fun bind() {
        if (bound) return
        bound = try {
            bindService(Intent().setClassName(KeysProtocol.SETUP, KeysProtocol.BRIDGE), conn, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) { false }                              // TV Setup is not here (or not this key's): keys simply pass
        if (!bound) ui.postDelayed({ bind() }, 10000)                 // an update of TV Setup is under way: try again
    }

    private fun unbind() {
        if (bound) try { unbindService(conn) } catch (e: Exception) { }
        bound = false
        bridge = null
    }

    private fun rebind() { unbind(); ui.postDelayed({ bind() }, 1000) }

    override fun onKeyEvent(e: KeyEvent): Boolean {
        if (e.keyCode !in KeysProtocol.INTEREST && e.keyCode !in extra) return false
        if (KeyCatch.live || power?.isInteractive != true) return false   // the key that wakes the screen is KeyCatch's
        val b = bridge ?: return false
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(KeysProtocol.DESCRIPTOR)
            data.writeInt(e.keyCode); data.writeInt(e.action); data.writeInt(e.repeatCount)
            data.writeLong(e.downTime); data.writeLong(e.eventTime)
            b.transact(KeysProtocol.TX_KEY, data, reply, 0)
            reply.readException()
            val eat = reply.readInt() == 1
            val r = if (reply.dataAvail() >= 4) reply.readInt() else 0
            if (r != rev) ui.post { codes(b) }                         // the list changed since: ask off the key path
            eat
        } catch (t: Throwable) { false } finally {
            data.recycle(); reply.recycle()
        }
    }

    /** The extra codes and their rev; an older TV Setup does not know the call: none. */
    private fun codes(b: IBinder) {
        Thread({
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(KeysProtocol.DESCRIPTOR)
                if (b.transact(KeysProtocol.TX_CODES, data, reply, 0)) {
                    reply.readException()
                    val r = reply.readInt()
                    extra = (0 until reply.readInt().coerceIn(0, 64)).map { reply.readInt() }.toSet()
                    rev = r
                }
            } catch (t: Throwable) { } finally { data.recycle(); reply.recycle() }
        }, "codes").apply { isDaemon = true }.start()
    }

    override fun onUnbind(i: Intent?): Boolean { ui.removeCallbacksAndMessages(null); unbind(); unmenu(); return super.onUnbind(i) }
    override fun onDestroy() { ui.removeCallbacksAndMessages(null); unbind(); unmenu(); super.onDestroy() }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) { }
    override fun onInterrupt() { }
}

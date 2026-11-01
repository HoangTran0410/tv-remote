package dev.tvremote.core.adb
import java.io.File
import java.io.IOException
import dev.tvremote.core.WireLog

/** An ADB connection to one TV that is opened when first needed and opened again after it drops. */
class AdbLink(val keyDir: File, val host: String) {
    @Volatile private var adb: Adb? = null
    @Volatile private var dialing: Adb? = null                        // being connected right now: it may sit for minutes waiting for the person at the TV
    @Volatile private var waiting = false                             // the TV is showing "Allow USB debugging?"
    @Volatile private var failed = false                              // the last attempt did not get through
    @Volatile private var dialStart = 0L                              // nanoTime: it stops while the device sleeps, so a dial it froze in is not "stuck"
    @Volatile private var lastTry = 0L                                // state() dials again at most this often after a failure
    @Volatile private var gen = 0                                     // bumped by reconnect(): a dial of an older generation was cut on purpose, not a failure
    private val spawning = java.util.concurrent.atomic.AtomicBoolean()
    private val lock = java.util.concurrent.locks.ReentrantLock()

    /**
     * The connection, made now if there is none. Whoever starts the dial waits for it (minutes, while the TV asks the person);
     * the others don't queue behind it: they give up at once while the TV is asking (the server answers "adb:allow") and
     * after [WAIT_MS] otherwise. Queued for good, the page's requests used to take every thread of TV Setup's server, so
     * nothing answered any more, the pairing code included.
     */
    @Throws(IOException::class)
    fun get(): Adb {
        adb?.let { if (!it.isDead()) return it }
        if (!lock.tryLock()) {
            val until = System.nanoTime() + WAIT_MS * 1_000_000
            while (true) {
                if (waiting) throw IOException(ASKING)
                if (lock.tryLock(200, java.util.concurrent.TimeUnit.MILLISECONDS)) break
                if (System.nanoTime() > until) throw IOException(STILL)
            }
        }
        try {
            adb?.let { if (!it.isDead()) return it }
            val a = Adb(keyDir)
            val mine = gen
            dialStart = System.nanoTime()
            dialing = a
            try {
                val got = a.connectOrShare(host) { waiting = true; WireLog.all.add("·", "adb $host asking on the TV") }   // a TV that takes one client: shared
                WireLog.all.add("·", "adb $host connected")
                waiting = false
                failed = false
                adb = got
                dev.tvremote.core.Census.connected(host) { got.shell(it) }   // the TV's model, once, for the anonymous census (off: nothing happens)
                return got
            } catch (e: IOException) {
                WireLog.all.add("·", "adb $host $e")
                if (mine == gen) failed = true
                try { a.close() } catch (ignored: Exception) { }      // the socket of a refused attempt, not left to the GC
                throw e
            } catch (e: Exception) {
                WireLog.all.add("·", "adb $host $e")
                if (mine == gen) failed = true
                try { a.close() } catch (ignored: Exception) { }
                throw IOException(e.message ?: e.javaClass.simpleName)
            } finally {
                dialing = null
                waiting = false                                       // asked, then refused or left: the question is gone with the dial
                if (mine == gen) lastTry = System.currentTimeMillis()
            }
        } finally {
            lock.unlock()
        }
    }

    fun waitingForUser(): Boolean = waiting

    /** The last dial did not get through: "unknown" is then a TV without ADB being asked again, not a link coming up. */
    fun failing(): Boolean = failed

    /**
     * How things stand, without waiting for anything: ready, unauthorized (the TV is asking the person), unknown (connecting)
     * or closed. Asking is also what starts a connection when there is none, so whoever shows this keeps it alive.
     */
    fun state(): String {
        adb?.let { if (!it.isDead()) return "ready" }
        val d = dialing
        if (d != null && !waiting && (System.nanoTime() - dialStart) / 1_000_000 > 25000) {   // stuck dialing (a network that changed under it): cut it, say so
            failed = true
            try { d.close() } catch (ignored: Exception) { }
            return "closed"
        }
        // one dial at a time, and a TV that is off is asked again every 10 s, not on every poll of every open page
        if (d == null && (!failed || System.currentTimeMillis() - lastTry > 10000) && spawning.compareAndSet(false, true))
            Thread({ try { get() } catch (ignored: Exception) { } finally { spawning.set(false) } }, "adb-link").apply { isDaemon = true }.start()
        // a dial under way after a failure is "unknown", not "closed": the link dropped while the phone slept (or a VPN came up),
        // and a page opened then said "needs ADB" a second before it was back
        return if (waiting) "unauthorized" else if (failed && d == null && !spawning.get()) "closed" else "unknown"
    }

    /** "Try again": a fresh connection, whatever the old one thought of itself. */
    fun reconnect(): String {
        gen++
        close()
        failed = false
        lastTry = 0
        return state()
    }

    /** Back on screen after a while: checks that the connection still answers (in the background), and dials again if not. */
    fun check() {
        val a = adb ?: return
        Thread({ if (!a.alive(4000)) { try { get() } catch (ignored: Exception) { } } }, "adb-check").apply { isDaemon = true }.start()
    }

    /** Never waits, not even for a connection that is still being made: that one is cut off. */
    fun close() {
        val d = dialing
        val a = adb
        adb = null
        waiting = false
        try { d?.close() } catch (ignored: Exception) { }
        try { a?.close() } catch (ignored: Exception) { }
    }

    companion object {
        const val ASKING = "the TV is asking to allow USB debugging"   // Errors.say
        const val STILL = "adb still connecting"
        const val WAIT_MS = 20000L                                    // a dial that gets through takes a second or two, TLS with mDNS a few more
    }
}

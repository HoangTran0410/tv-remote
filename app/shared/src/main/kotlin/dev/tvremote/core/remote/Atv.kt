package dev.tvremote.core.remote
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.concurrent.Executors
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import dev.tvremote.core.SelfSigned
import dev.tvremote.core.WireLog

/**
 * Keys only, for a TV whose ADB debugging is still off: the "Android TV Remote" protocol every Android TV answers on
 * ports 6467 (pairing, once, with the code the TV shows) and 6466 (keys and "open this"). It is what the Google TV phone
 * app speaks, and what the computer web uses through androidtvremote2. No picture, no apps, no files: those need ADB.
 */
class Atv(dir: File, private val host: String) : Remote, CodePairing {
    override val kind = Remote.Kind.ANDROID

    class NotPaired : Exception("not paired")

    private val key: PrivateKey
    private val cert: X509Certificate
    @Volatile private var sock: SSLSocket? = null
    private val out = Executors.newSingleThreadExecutor()
    @Volatile private var live = false
    private val SILENT_MS = 20_000                                    // 4 missed pings; a read timeout drops the link and connect() dials it again
    @Volatile private var closed = false

    /** What the TV calls itself, known once connected. */
    @Volatile override var model = ""
        private set
    @Volatile override var vendor = ""
        private set
    @Volatile override var version = ""                               // of the TV's remote service
        private set
    /** The screen is on (remote_start); null until the TV says. */
    @Volatile override var isOn: Boolean? = null
        private set
    /** level, max, muted, as the TV reports them on connecting and at each change; null until then. */
    @Volatile override var volume: Triple<Int, Int, Boolean>? = null
        private set

    init {
        val k = File(dir, "atv.pk8")
        val c = File(dir, "atv.crt")
        if (!k.exists() || !c.exists()) {
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            c.writeBytes(SelfSigned.certificate(pair.public.encoded, pair.private))
            k.writeBytes(pair.private.encoded)
        }
        key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(k.readBytes()))
        cert = CertificateFactory.getInstance("X.509").generateCertificate(c.inputStream()) as X509Certificate
    }

    override fun ready(): Boolean = live && !closed

    // ---------------------------------------------------------------- pairing (port 6467)
    /** Asks the TV to show its code. Then [finishPairing] with what the person reads there. */
    override fun startPairing() {
        WireLog.all.add("·", "remote $host pairing")
        try { sock?.close() } catch (ignored: Exception) { }              // a retry must not leave the last attempt's socket open
        val s = dial(6467)
        sock = s
        try { s.soTimeout = 15000 } catch (ignored: Exception) { }        // the pairing path only: a TV that stops answering must not hold this for ever
        fun step(field: Int, body: ByteArray, expect: Int) {
            send(s, Pb().int(1, 2).int(2, 200).msg(field, body).bytes())
            answer(s, expect)
        }
        try {
            step(10, Pb().str(1, "atvremote").str(2, "TV Remote").bytes(), 11)
            val hex6 = Pb().int(1, 3).int(2, 6).bytes()                    // the code: 6 hexadecimal characters
            step(20, Pb().msg(1, hex6).int(3, 1).bytes(), 20)
            step(30, Pb().msg(1, hex6).int(2, 1).bytes(), 31)
        } catch (e: Exception) { try { s.close() } catch (ignored: Exception) { }; throw e }   // refused: the TV waits on a dead attempt otherwise
    }

    /** Throws when the code is wrong; the TV then drops the attempt and [startPairing] has to be run again. */
    override fun finishPairing(code: String) {
        val s = sock ?: throw IllegalStateException("startPairing first")
        val c = code.trim().uppercase()
        require(c.length == 6 && c.all { it in "0123456789ABCDEF" }) { "bad code" }
        val secret = secret(cert.publicKey as RSAPublicKey, s.session.peerCertificates[0].publicKey as RSAPublicKey, c) ?: throw IllegalArgumentException("bad code")
        send(s, Pb().int(1, 2).int(2, 200).msg(40, Pb().raw(1, secret).bytes()).bytes())
        answer(s, 41)
        close()
    }

    private fun answer(s: SSLSocket, field: Int) {
        val m = Pb.read(frame(s.inputStream) ?: throw java.io.IOException("the TV closed the pairing"))
        if ((m[2]?.firstOrNull() as? Long ?: 0L) != 200L || m[field] == null) throw java.io.IOException("pairing refused")
    }

    // ---------------------------------------------------------------- remote (port 6466)
    @Volatile private var ime = 0L                                    // the TV's counters for its text field, echoed back with the text
    @Volatile private var field = 0L
    @Volatile private var lefts = 0                                   // times the TV said the focus left its field
    private var imeFromInject = false                                 // remote_ime_key_inject's counter wins over remote_ime_batch_edit's (androidtvremote2 does the same)
    @Volatile private var fieldFromStatus = false                     // the same for the field counter

    /** A text field on the TV that takes text from the keyboard service: what is in it now and its label (a hint such as "Search"). */
    class Field(val text: String, val label: String)

    @Volatile private var shown: Field? = null
    @Volatile private var told: String? = null                        // the field's text as the TV last said it, ours or not
    @Volatile private var onTv: String? = null                        // what the field reads, as the TV said or, silent, as we sent it
    @Volatile private var mends = 0                                   // unanswered steps sent again since the person last typed
    @Volatile private var reports = 0                                 // how many times the TV has said what its field reads
    private val steering = java.util.concurrent.atomic.AtomicBoolean()   // the field is being brought to read as the box
    @Volatile private var enterAfter = -1                             // Enter asked for while steering: the [lefts] it was asked at
    private val steerer = Executors.newSingleThreadExecutor()
    @Volatile private var editedAt = 0L                               // when we last sent text: the TV's echo of it must not overwrite the text typed since
    @Volatile private var deletedAt = 0L                              // when we last sent Backspaces to the field: an insert right after waits for them
    @Volatile private var submitted: String? = null                   // the text Enter went with: a late report of it must not bring the field back
    @Volatile private var submittedAt = 0L
    /** Runs [what] after [ms] (tests: at once or never); a report the echo window held back is looked at again then. */
    internal var later: (Long, () -> Unit) -> Unit = { ms, what -> LATER.schedule(object : java.util.TimerTask() { override fun run() { try { what() } catch (ignored: Exception) { } } }, ms) }
    internal var clock: () -> Long = { System.currentTimeMillis() }
    internal var tap: ((ByteArray) -> Unit)? = null                   // tests: what would go on the wire

    /** The text field that has the focus on the TV, null when none (or the TV does not tell: [keys] then). */
    val currentField: Field? get() = shown

    /** The app in front has told us about a text field that takes text from the TV's keyboard service; see [text] and [keys]. */
    val hasField: Boolean get() = shown != null

    /** Counts every change of [currentField], for a screen that polls. */
    @Volatile var fieldSeq = 0
        private set

    /** Called (on the connection's thread) when [currentField] appears, changes or goes. */
    @Volatile var onField: (() -> Unit)? = null

    private fun setField(f: Field?) {
        shown = f
        fieldSeq++
        try { onField?.invoke() } catch (ignored: Exception) { }
    }

    /** The package on the TV's screen, as the TV reports it ("" until it does). */
    @Volatile override var app = ""
        private set

    /**
     * Blocks until the TV accepts keys. [NotPaired]: the TV does not know this phone yet. [onClosed] is called once, later, if
     * the connection ends for good: a TV that drops it (some do after a link they cannot open) is simply dialled again.
     */
    override fun connect(onClosed: () -> Unit) {
        var s = try { open() } catch (e: Exception) { WireLog.all.add("·", "remote $host " + (if (e is NotPaired) "not paired" else e.toString())); throw e }
        WireLog.all.add("·", "remote $host connected: $vendor $model $version")
        Thread({
            while (true) {
                try { while (true) handle(s, frame(s.inputStream) ?: break) } catch (e: Exception) { WireLog.all.add("·", "remote $host $e") }
                WireLog.all.add("·", "remote $host dropped, dialling again")
                live = false
                if (shown != null) setField(null)                     // the TV may have closed its keyboard while we were away
                try { s.close() } catch (ignored: Exception) { }   // the dropped link, or its descriptor stays until the GC
                var again: SSLSocket? = null
                for (i in 0 until 4) {
                    if (closed) break
                    try { Thread.sleep(600) } catch (ignored: InterruptedException) { }
                    again = try { open() } catch (e: Exception) { null }
                    if (again != null) break
                }
                if (again != null && closed) { try { again.close() } catch (ignored: Exception) { }; break }   // closed while dialling
                s = again ?: break
            }
            if (!closed) { WireLog.all.add("·", "remote $host gone"); onClosed() }
        }, "atv").apply { isDaemon = true }.start()
    }

    /**
     * Dials and answers the TV's greeting. [NotPaired] only when the TV rejects our certificate or lets us in and drops us before
     * a single word: it does not know this phone. Any other drop (a weak link, a TV part-way through starting) is an
     * [java.io.IOException]: callers dial again.
     */
    private fun open(): SSLSocket {
        val s = try { dial(6466) } catch (e: javax.net.ssl.SSLException) {
            if (certRejected(e)) throw NotPaired()
            throw java.io.IOException("TLS: " + e.message, e)
        }
        sock = s
        s.soTimeout = 8000
        var frames = 0
        try {
            while (!live) { val f = frame(s.inputStream) ?: break; frames++; handle(s, f) }
        } catch (slow: java.net.SocketTimeoutException) {             // a weak link is not a reason to ask for a new code
            try { s.close() } catch (ignored: Exception) { }
            throw java.io.IOException("timed out")
        } catch (e: Exception) {
            try { s.close() } catch (ignored: Exception) { }
            if (e is javax.net.ssl.SSLException && certRejected(e)) throw NotPaired()
            throw if (e is java.io.IOException) e else java.io.IOException(e)
        }
        if (!live) {
            try { s.close() } catch (ignored: Exception) { }
            if (frames == 0) throw NotPaired()                        // let in, then dropped without a word: the TV does not know this phone
            throw java.io.IOException("the TV dropped the connection before it was ready")
        }
        s.soTimeout = SILENT_MS                                       // the TV pings every 5 s: silence = a pulled plug or a lost Wi-Fi with no FIN; connect()'s reader dials again
        return s
    }

    private fun handle(s: SSLSocket, body: ByteArray) {
        val m = Pb.read(body)
        if (8 !in m) WireLog.all.add("←", "remote $host " + m.keys.sorted().joinToString(",", "message ") + " (${body.size} B)")   // pings: every 5 s
        m[1]?.let {                                                   // remote_configure: who the TV is; answer with who we are
            val info = Pb.read(it[0] as? ByteArray ?: ByteArray(0))[2]?.firstOrNull() as? ByteArray
            if (info != null) {
                val d = Pb.read(info)
                fun str(f: Int) = (d[f]?.firstOrNull() as? ByteArray)?.toString(Charsets.UTF_8) ?: ""
                model = str(1); vendor = str(2); version = str(6)
            }
            val me = Pb().str(1, "TV Remote").str(2, "tv-remote").int(3, 1).str(4, "1").str(5, "atvremote").str(6, "1.0.0").bytes()
            send(s, Pb().msg(1, Pb().int(1, 622).msg(2, me).bytes()).bytes())
        }
        m[2]?.let {                                                   // remote_set_active
            send(s, Pb().msg(2, Pb().int(1, 622).bytes()).bytes())
            live = true
        }
        m[8]?.let {                                                   // ping
            val n = Pb.read(it[0] as? ByteArray ?: ByteArray(0))[1]?.firstOrNull() as? Long ?: 0L
            send(s, Pb().msg(9, Pb().int(1, n).bytes()).bytes())
        }
        m[40]?.let { isOn = (Pb.read(it[0] as? ByteArray ?: ByteArray(0))[1]?.firstOrNull() as? Long ?: 0L) != 0L }   // remote_start: screen on or off
        m[50]?.let {                                                  // remote_set_volume_level
            val v = Pb.read(it[0] as? ByteArray ?: ByteArray(0))
            fun n(f: Int) = (v[f]?.firstOrNull() as? Long ?: 0L).toInt()
            volume = Triple(n(7), n(6), n(8) != 0)
        }
        m[30]?.let {                                                  // remote_voice_begin: the Assistant listens, under this session
            voiceWait?.offer(Pb.read(it[0] as? ByteArray ?: ByteArray(0))[1]?.firstOrNull() as? Long ?: 0L)
        }
        ime(m)
    }

    /**
     * The text field messages (tronikos/androidtvremote2's remotemessage.proto): 20 remote_ime_key_inject {app_info {1 counter,
     * 12 package}, text_field_status}, 21 remote_ime_batch_edit {1 ime_counter, 2 field_counter}, 22 remote_ime_show_request
     * {2 text_field_status}; a text_field_status is {1 counter_field, 2 value, 3 start, 4 end, 6 label}.
     */
    internal fun ime(m: Map<Int, List<Any>>) {
        fun sub(f: ByteArray?, n: Int) = f?.let { Pb.read(it)[n]?.firstOrNull() as? ByteArray }
        fun status(b: ByteArray?) {                                   // the field's own counter, and what is in it
            if (b == null) return
            val st = Pb.read(b)
            val c = st[1]?.firstOrNull() as? Long ?: 0L
            if (c != 0L) { field = c; fieldFromStatus = true }
            val v = (st[2]?.firstOrNull() as? ByteArray)?.toString(Charsets.UTF_8) ?: ""
            told = v; onTv = v; reports++
            val f = shown ?: return
            if (echo()) { recheck(); return }                         // our own edit coming back: ours is newer
            val l = (st[6]?.firstOrNull() as? ByteArray)?.toString(Charsets.UTF_8) ?: f.label
            if (v != f.text || l != f.label) setField(Field(v, l))
        }
        m[20]?.let {                                                  // remote_ime_key_inject: sent whenever another app comes to the front
            val inj = Pb.read(it[0] as? ByteArray ?: ByteArray(0))
            val info = inj[1]?.firstOrNull() as? ByteArray
            val pkg = sub(info, 12)?.toString(Charsets.UTF_8)
            if (!pkg.isNullOrEmpty()) { if (pkg != app && shown != null) setField(null); app = pkg }
            val c = info?.let { i -> Pb.read(i)[1]?.firstOrNull() as? Long } ?: 0L
            if (c != 0L) { ime = c; imeFromInject = true }
            status(inj[2]?.firstOrNull() as? ByteArray)
        }
        m[21]?.let {                                                  // remote_ime_batch_edit: a text field on the TV has the focus
            val e = Pb.read(it[0] as? ByteArray ?: ByteArray(0))
            if ((e[1]?.firstOrNull() as? Long ?: 0L) == 0L && (e[2]?.firstOrNull() as? Long ?: 0L) == 0L) {   // {0, 0}: the focus left the field (a BRAVIA's Play Store, on a match)
                lefts++
                if (shown != null) setField(null)
                return@let
            }
            if (!imeFromInject) ime = e[1]?.firstOrNull() as? Long ?: 0L
            if (!fieldFromStatus) field = e[2]?.firstOrNull() as? Long ?: 0L   // a BRAVIA says {1, 0} here after a field status of 55: 0 loses every edit
            if (shown == null) setField(Field("", ""))                // its text unknown (a TV that never says it): the box starts empty
        }
        m[22]?.let {                                                  // remote_ime_show_request: the TV's keyboard comes up on a field, with its text
            val st = Pb.read(it[0] as? ByteArray ?: ByteArray(0))[2]?.firstOrNull() as? ByteArray ?: return@let
            val p = Pb.read(st)
            val v = (p[2]?.firstOrNull() as? ByteArray)?.toString(Charsets.UTF_8) ?: ""
            val l = (p[6]?.firstOrNull() as? ByteArray)?.toString(Charsets.UTF_8) ?: ""
            val c = p[1]?.firstOrNull() as? Long ?: 0L
            if (c != 0L) { field = c; fieldFromStatus = true }
            told = v; onTv = v; reports++
            val f = shown
            if (f != null && echo()) { recheck(); return@let }        // our own edit coming back: ours is newer
            if (f == null && v == submitted && clock() - submittedAt < ECHO_MS) return@let   // the text Enter went with, reported late: the field is gone
            if (f == null || f.text != v || f.label != l) setField(Field(v, l))
        }
    }

    /**
     * A report held back as our own echo may be the TV's last word on the field (an insert it dropped): once no edit of ours is
     * that recent, what the TV said wins over what we expected.
     */
    /** A report now is our own edit coming back (or one half way, while steering), not the TV's word. */
    private fun echo() = steering.get() || clock() - editedAt < ECHO_MS

    private fun recheck() {
        val e = editedAt
        later(maxOf(0L, ECHO_MS - (clock() - e)) + 50) {
            val f = shown
            val t = told
            if (editedAt == e && f != null && t != null && t != f.text) setField(Field(t, f.label))
        }
    }

    /** [direction]: [DOWN] and [UP] for a held key, [SHORT] for a tap. */
    override fun key(code: Int, direction: Int) {
        WireLog.all.add("→", "remote $host key $code " + (if (direction == SHORT) "tap" else if (direction == DOWN) "down" else "up"))
        post(Pb().msg(10, Pb().int(1, code.toLong()).int(2, direction.toLong()).bytes()).bytes(), KEY_STALE_MS)
        if (shown != null && direction != DOWN && (code == 3 || code == 4)) setField(null)   // Home / Back: the keyboard goes; the TV says so again when it returns
    }

    /**
     * The key held down for [ms], as a finger on a real remote holds it. Apps that move a pointer with the arrows (TV browsers)
     * move it for as long as the key is down, so a key that goes down and up in the same instant moves nothing there.
     */
    override fun pulse(code: Int, ms: Long) {
        if (closed || sock == null) return
        val down = Pb().msg(10, Pb().int(1, code.toLong()).int(2, DOWN.toLong()).bytes()).bytes()
        val up = Pb().msg(10, Pb().int(1, code.toLong()).int(2, UP.toLong()).bytes()).bytes()
        out.execute {
            val s = sock
            if (!live || s == null) return@execute
            try {
                send(s, down)
                try { Thread.sleep(ms) } catch (ignored: InterruptedException) { }
                send(s, up)
            } catch (e: Exception) { try { s.close() } catch (ignored: Exception) { } }
        }
    }

    /** Puts [t] into the text field that has the focus on the TV, at its caret. Nothing happens when no field has it. */
    override fun text(t: String) {
        if (t.isEmpty()) return
        insert(t)
    }

    /**
     * Inserts [t] at the field's caret through the TV's keyboard service. It inserts, never replaces: a BRAVIA (Google TV 10)
     * with "q" in its field showed "qkodi" after "kodi" was sent as the whole text (measured 2026-11-20), and
     * androidtvremote2's send_text says the same; deleting is Backspace keys ([type]).
     */
    private fun insert(t: String) {
        val edit = Pb().int(1, 1).msg(2, Pb().int(1, t.length.toLong()).int(2, t.length.toLong()).str(3, t).bytes()).bytes()
        post(0) { Pb().msg(21, Pb().int(1, ime).int(2, field).msg(3, edit).bytes()).bytes() }
    }

    /**
     * [del] Backspaces, then [add] inserted, then Enter. With a field the text goes through the keyboard service (accents too)
     * and [currentField] follows what it should now read; without one (an app with its own keyboard, or a TV that never
     * says) one live letter goes as a key, a pasted selection as one insert.
     */
    override fun type(del: Int, add: String, enter: Boolean) {
        val f = shown
        val l = lefts
        if (f == null) {
            repeat(del) { key(67, SHORT) }
            // a pasted selection is several key events sent back to back; TVs drop some of them, so insert it in one edit.
            if (add.isNotEmpty()) { if (!bulk(add) && typable(add)) keys(add) else text(add) }
            if (enter) key(66, SHORT)
            return
        }
        if (told != null && (del > 0 || add.isNotEmpty() || steering.get())) {   // a TV that says its text: steered to the box
            val base = f.text
            val at = try { base.offsetByCodePoints(base.length, -minOf(del, base.codePointCount(0, base.length))) } catch (e: Exception) { 0 }
            shown = Field(base.substring(0, at) + add, f.label)       // no onField: the screen that typed it has it
            mends = 0
            steer(if (enter) l else -1)
            return
        }
        if (del > 0 || add.isNotEmpty()) {
            val base = f.text
            val at = try { base.offsetByCodePoints(base.length, -minOf(del, base.codePointCount(0, base.length))) } catch (e: Exception) { 0 }
            var cut = base.length
            var presses = 0
            while (cut > at) { cut = backspaceFrom(base, cut); presses++ }   // a Backspace takes a whole emoji: what it took too much goes back in
            presses += maxOf(0, del - base.codePointCount(0, base.length))   // all of them: a TV that never says its text has more than we know
            val put = base.substring(cut, at) + add
            val before = editedAt
            editedAt = clock()
            val known = told != null                                  // a TV that says its text: wait for it to read as it should
            shown = Field(base.substring(0, cut) + put, f.label)       // no onField: the screen that typed it has it
            repeat(presses) { backspace() }
            if (presses > 0) deletedAt = clock()
            // an insert waits for the Backspaces before it, these or a moment ago's (live typing sends them one call each)
            if (put.isNotEmpty() && (presses > 0 || clock() - deletedAt < DELETED_WAIT_MS)) settle(if (known) base.substring(0, cut) else null, maxOf(presses, 1))
            // live letters a moment apart: each waits for the TV to show the one before, an insert with the old counter is dropped
            // (a BRAVIA over Tailscale lost 7 of 10 letters typed 60 ms apart when one echo came 800 ms late, measured 2026-11-20)
            else if (put.isNotEmpty() && known && told != base && clock() - before < ECHO_MS) settle(base, 1, 0)
            if (put.isNotEmpty()) insert(put)
            if (enter) settle(if (known) base.substring(0, cut) + put else null, if (put.isEmpty()) presses else 1)
        } else if (enter && clock() - editedAt < ENTER_WAIT_MS) settle(if (told != null) f.text else null, 1)   // live: Enter alone, right after the last letter
        if (enter) submit(l)
    }

    /**
     * Brings the TV's field to read as the box ([shown]), from what the TV last said it reads: a few Backspaces or one insert at a
     * time, each waiting for the TV to say what it did, the next worked out from that. An insert lands at the TV's caret (its
     * end; arrows move the TV's keyboard, not the caret, a BRAVIA, measured) and a key or an insert with an old counter is
     * dropped, so sending all at once against what we expected left the field and the box apart: a long URL edited at its start
     * lost letters, or had its end typed in again (#31). Typing while it runs only moves where it is going. A step the TV
     * says nothing about is taken as done, and looked at again a moment later; a TV that keeps its own text over [STEER_TRIES]
     * tries has its way.
     * [enter]: the [lefts] Enter was asked at, sent once the field reads right.
     */
    private fun steer(enter: Int) {
        editedAt = clock()
        if (enter >= 0) enterAfter = enter
        if (!steering.compareAndSet(false, true)) return             // the running one picks up the new text
        val run = {
            do {
                try { steerOnce() } catch (ignored: Exception) { } finally { steering.set(false) }
                val more = !closed && (enterAfter >= 0 || shown.let { it != null && it.text != onTv })
            } while (more && steering.compareAndSet(false, true))      // typed in the moment it stopped
        }
        if (tap != null || sock == null) run() else try { steerer.execute(run) } catch (e: java.util.concurrent.RejectedExecutionException) { steering.set(false) }
    }

    private fun steerOnce() {
        var have = onTv ?: told ?: ""
        var stuck = 0
        var silent = false
        while (!closed) {
            val f = shown ?: break
            val want = f.text
            if (have == want) break
            var keep = 0
            while (keep < have.length && keep < want.length && have[keep] == want[keep]) keep++
            if (keep > 0 && Character.isHighSurrogate(have[keep - 1])) keep--
            val n = reports
            val expect: String
            var presses = 0
            if (have.length > keep) {
                var cut = have.length
                while (cut > keep && presses < STEER_KEYS) { cut = backspaceFrom(have, cut); presses++ }   // a Backspace takes a whole emoji
                repeat(presses) { backspace() }
                expect = have.substring(0, cut)
            } else {
                insert(want.substring(keep))
                expect = want
            }
            val heard = hear(n, expect, maxOf(presses, 1))
            silent = heard == null
            if (heard == null) { have = expect; continue }            // it said nothing: taken as done, looked at again below
            if (heard == have && ++stuck >= STEER_TRIES) {            // it keeps its own text: its word wins
                setField(Field(heard, f.label))
                break
            }
            if (heard != have) stuck = 0
            have = heard
        }
        onTv = have
        editedAt = clock()
        val e = enterAfter
        enterAfter = -1
        if (e >= 0) submit(e)
        else if (silent) later(ECHO_MS) {                             // the last step went unanswered: an edit dropped without a word is sent again
            val f = shown
            val t = told
            if (!steering.get() && f != null && t != null && t != f.text && mends++ < STEER_TRIES) { onTv = t; steer(-1) }
        }
    }

    /** Waits for the TV to say its field reads [expect] after [n] reports (or to fall quiet); what it last said, or null if nothing. */
    private fun hear(n: Int, expect: String, keys: Int): String? {
        if (tap == null && sock != null) {
            fun now() = System.nanoTime() / 1_000_000
            val end = now() + SETTLE_MAX_MS + SETTLE_PER_MS * keys
            var seen = n
            var quiet = now()
            try {
                while (now() < end && !closed) {
                    val r = reports
                    if (r != n && told == expect) break
                    if (r != seen) { seen = r; quiet = now() } else if (r != n && now() - quiet > QUIET_MS) break   // it said something else and stopped
                    Thread.sleep(15)
                }
            } catch (ignored: InterruptedException) { }
        }
        return if (reports != n) told else null
    }

    /** Backspace for the field: kept longer than other keys when the link is slow, a settle may hold the queue for seconds. */
    private fun backspace() {
        WireLog.all.add("→", "remote $host key 67 tap")
        post(Pb().msg(10, Pb().int(1, 67).int(2, SHORT.toLong()).bytes()).bytes(), FIELD_STALE_MS)
    }

    /** Sets the field's whole text to [t] (empty too): Backspaces for what it holds, then [t]. */
    fun replace(t: String) {
        val f = shown ?: return text(t)
        type(f.text.codePointCount(0, f.text.length), t, false)
    }

    /**
     * Holds the queue until what was sent before has reached the field: each key the TV applies gives the field a new counter,
     * and an insert with the old one is dropped (a BRAVIA, measured: its Backspaces land 100-300 ms apart); before Enter, the
     * TV may say the focus left. Waits for the TV to say the field reads [expect] (then a little more, for what it says next),
     * or, not knowing, a set while; at most [SETTLE_MAX_MS] plus [SETTLE_PER_MS] for each of [n] steps.
     */
    private fun settle(expect: String?, n: Int, after: Long = SETTLE_MIN_MS) {
        if (tap != null || closed || sock == null) return
        out.execute {
            val end = System.nanoTime() / 1_000_000 + SETTLE_MAX_MS + SETTLE_PER_MS * minOf(n, 20)
            fun now() = System.nanoTime() / 1_000_000
            try {
                if (expect == null) Thread.sleep(minOf(end - now(), SETTLE_MIN_MS + SETTLE_PER_MS * minOf(n, 20).toLong()))
                else {
                    while (now() < end && told != expect) Thread.sleep(20)
                    if (after > 0) Thread.sleep(after)
                }
            } catch (ignored: InterruptedException) { }
        }
    }

    /**
     * Enter on the focused field, then the field is forgotten: the app usually moves on and the TV says again if one stays.
     * Not sent when the TV said the focus left the field since [l] (its count when the typing began): the app moved on by itself.
     */
    fun submit(l: Int = lefts) {
        submitted = shown?.text
        submittedAt = clock()
        WireLog.all.add("→", "remote $host key 66 tap")
        post(0) { if (lefts != l) null else Pb().msg(10, Pb().int(1, 66).int(2, SHORT.toLong()).bytes()).bytes() }   // no stale limit: it waits for the text; a BRAVIA's Play Store jumps to "Install" on a match
        if (shown != null) setField(null)
    }

    /**
     * Types [t] as key presses, one per character, the way a keyboard plugged into the TV would: for apps with a keyboard of
     * their own (Downloader), whose fields the TV's keyboard service never hears about. Only what has a key without Shift
     * gets through: capitals arrive in lower case, and characters such as ":" are left out (see [typable]).
     */
    fun keys(t: String) {
        for (c in t) (code(c).takeIf { it != 0 } ?: code(c.lowercaseChar())).let { if (it != 0) key(it, SHORT) }
    }

    // ---------------------------------------------------------------- voice (the 622 we send includes VOICE, 8)
    // What is said into this device goes to the TV's Assistant, as into a voice remote's microphone: the search key, the TV
    // answers with a session (remote_voice_begin), we echo it, then 16-bit PCM, 8 kHz, mono, then remote_voice_end
    // (tronikos/androidtvremote2 found the flow). Its samples go big-endian: a TCL (Google TV 12) heard nothing in
    // little-endian ("no speech detected") and every word in big-endian, measured. The TV closes the connection on a piece
    // over 20 KB; pieces under 8 KB are padded with silence, so the caller sends at least that much at a time but the last.
    @Volatile private var voiceWait: java.util.concurrent.ArrayBlockingQueue<Long>? = null   // not a CompletableFuture: API 24, phones run Android 5

    /** The Assistant listening to this device: its session, or null when the TV named none within 3 s (no Assistant there). */
    fun voiceBegin(): Long? {
        if (!ready()) return null
        val f = java.util.concurrent.ArrayBlockingQueue<Long>(1)
        voiceWait = f
        key(KEYCODE_SEARCH, SHORT)
        val id = try { f.poll(3, java.util.concurrent.TimeUnit.SECONDS) } catch (e: Exception) { null } finally { voiceWait = null }
        if (id != null) post(Pb().msg(30, Pb().int(1, id).bytes()).bytes())
        return id
    }

    /** [pcm]: 16-bit little-endian samples (as recorded), 8 kHz, mono. */
    fun voiceSend(id: Long, pcm: ByteArray) {
        for (i in 0 until pcm.size - 1 step 2) { val t = pcm[i]; pcm[i] = pcm[i + 1]; pcm[i + 1] = t }
        var at = 0
        while (at < pcm.size) {
            val n = minOf(VOICE_MAX, pcm.size - at)
            val piece = ByteArray(maxOf(n, VOICE_MIN)).also { System.arraycopy(pcm, at, it, 0, n) }
            post(Pb().msg(31, Pb().int(1, id).raw(2, piece).bytes()).bytes())
            at += n
        }
    }

    fun voiceEnd(id: Long) = post(Pb().msg(32, Pb().int(1, id).bytes()).bytes())

    /** Opens a link on the TV, or an app by its package name: the app when it is installed, its Play Store page when it is not. */
    override fun launch(link: String) {
        val full = if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(link)) link else "market://launch?id=$link"
        post(Pb().msg(90, Pb().str(1, full).bytes()).bytes())
    }

    /** [staleMs] > 0: dropped if it waited longer than that (keys: 8 queued VOL_UP must not all fire when the link is back). */
    private fun post(message: ByteArray, staleMs: Long = 0) = post(staleMs) { message }

    /** [build] runs when its turn comes (null: nothing goes): a text edit carries the TV's counters, which the keys sent before it change. */
    private fun post(staleMs: Long, build: () -> ByteArray?) {
        tap?.let { t -> build()?.let(t); return }
        if (closed || sock == null) return
        val queued = System.nanoTime()
        out.execute {
            for (i in 0 until 30) { if (live || closed) break; try { Thread.sleep(200) } catch (e: InterruptedException) { return@execute } }   // being dialled again
            val s = sock
            if (!live || s == null) return@execute
            if (isStale(queued, System.nanoTime(), staleMs)) return@execute
            try { build()?.let { send(s, it) } } catch (e: Exception) { try { s.close() } catch (ignored: Exception) { } }
        }
    }

    override fun close() {
        closed = true
        out.shutdown()
        steerer.shutdown()
        val s = sock
        Thread { try { s?.close() } catch (ignored: Exception) { } }.start()   // may be called on the UI thread
    }

    // ---------------------------------------------------------------- plumbing
    private fun dial(port: Int): SSLSocket {
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setKeyEntry("k", key, CharArray(0), arrayOf(cert)) }
        val km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, CharArray(0)) }
        val anyTv = object : X509TrustManager {                       // the TV's certificate is self-made too; the pairing code is what ties the two together
            override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
            override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val ctx = SSLContext.getInstance("TLS").apply { init(km.keyManagers, arrayOf(anyTv), null) }
        val plain = dev.tvremote.core.Lan.connect(host, port, 5000)
        val s = ctx.socketFactory.createSocket(plain, host, port, true) as SSLSocket
        try {
            s.soTimeout = 15000
            s.startHandshake()
            s.soTimeout = 0
        } catch (e: Exception) { try { s.close() } catch (ignored: Exception) { }; throw e }   // a refused handshake (not paired) must not leave the socket open
        return s
    }

    @Synchronized
    private fun send(s: SSLSocket, body: ByteArray) {
        val o = ByteArrayOutputStream()
        Pb.varint(o, body.size.toLong())
        o.write(body)
        s.outputStream.write(o.toByteArray())
        s.outputStream.flush()
    }

    /** Just enough protocol buffers: numbers, strings/bytes and nested messages. */
    internal class Pb {
        private val o = ByteArrayOutputStream()
        fun int(field: Int, v: Long): Pb { varint(o, (field shl 3).toLong()); varint(o, v); return this }
        fun raw(field: Int, v: ByteArray): Pb { varint(o, (field shl 3 or 2).toLong()); varint(o, v.size.toLong()); o.write(v); return this }
        fun str(field: Int, v: String) = raw(field, v.toByteArray(Charsets.UTF_8))
        fun msg(field: Int, v: ByteArray) = raw(field, v)
        fun bytes(): ByteArray = o.toByteArray()

        companion object {
            fun varint(o: ByteArrayOutputStream, value: Long) {
                var v = value
                while (v and 0x7fL.inv() != 0L) { o.write(((v and 0x7f) or 0x80).toInt()); v = v ushr 7 }
                o.write(v.toInt())
            }

            /** field number -> its values in order: Long for numbers, ByteArray for everything with a length. */
            /** A broken message throws IOException, never an index error that would end the reader thread. */
            fun read(b: ByteArray): Map<Int, List<Any>> {
                val out = HashMap<Int, MutableList<Any>>()
                var p = 0
                fun bad(): Nothing = throw java.io.IOException("bad message")
                fun num(): Long { var v = 0L; var s = 0; while (true) { if (p >= b.size || s > 63) bad(); val x = b[p++].toInt(); v = v or ((x and 0x7f).toLong() shl s); if (x and 0x80 == 0) return v; s += 7 } }
                while (p < b.size) {
                    val tag = num().toInt()
                    val v: Any = when (tag and 7) {
                        0 -> num()
                        2 -> { val n = num(); if (n < 0 || n > b.size - p) bad(); b.copyOfRange(p, p + n.toInt()).also { p += n.toInt() } }
                        1 -> { p += 8; if (p > b.size) bad(); 0L }
                        5 -> { p += 4; if (p > b.size) bad(); 0L }
                        else -> bad()
                    }
                    out.getOrPut(tag ushr 3) { ArrayList() }.add(v)
                }
                return out
            }
        }
    }

    companion object {
        /** Keys older than this when the link is back are dropped, not fired all at once (see [post]) */
        internal const val KEY_STALE_MS = 1500L
        private const val SETTLE_MIN_MS = 150L
        private const val SETTLE_PER_MS = 300L
        private const val SETTLE_MAX_MS = 1000L

        internal fun isStale(queuedNs: Long, nowNs: Long, staleMs: Long) = staleMs > 0 && (nowNs - queuedNs) / 1_000_000 > staleMs

        /** The TV's TLS alert that it does not know our certificate, as Java and Conscrypt word it: the only drop that means "not paired". */
        internal fun certRejected(e: Throwable): Boolean =
            Regex("certificate_unknown|bad_certificate|unknown_ca|certificate_required|unknown ca|certificate unknown", RegexOption.IGNORE_CASE)
                .containsMatchIn(generateSequence(e) { it.cause }.joinToString(" ") { it.message ?: "" })

        /** One message: its length, then that many bytes. null at the end of the stream. */
        internal fun frame(i: InputStream): ByteArray? {
            var len = 0
            var shift = 0
            while (true) {
                val b = i.read()
                if (b < 0) return null
                len = len or ((b and 0x7f) shl shift)
                if (b and 0x80 == 0) break
                shift += 7
                if (shift > 21) throw java.io.IOException("bad message")   // over 4 bytes: no real message, and the length would overflow
            }
            if (len > 1 shl 20) throw java.io.IOException("bad message")  // the TV's messages are a few KB: never allocate what a broken one claims
            val buf = ByteArray(len)
            var got = 0
            while (got < len) {
                val n = i.read(buf, got, len - got)
                if (n < 0) return null
                got += n
            }
            return buf
        }

        const val DOWN = 1
        const val UP = 2
        const val SHORT = 3
        const val KEYCODE_SEARCH = 84
        private const val ECHO_MS = 1500L
        private const val DELETED_WAIT_MS = 2000L
        private const val ENTER_WAIT_MS = 3000L
        private const val STEER_KEYS = 10                             // Backspaces sent before waiting for the TV to say
        private const val STEER_TRIES = 3
        private const val QUIET_MS = 400L
        private const val FIELD_STALE_MS = 10_000L
        private val LATER by lazy { java.util.Timer("atv-field", true) }

        /**
         * Where one Backspace from [end] leaves [s], as Android's own Backspace (BaseKeyListener) deletes: a whole emoji with its
         * skin tone, variation selector, keycap or tags, a ZWJ family, a flag's two letters; anything else one code point.
         */
        internal fun backspaceFrom(s: String, end: Int): Int {
            fun prev(j: Int) = if (j > 0) s.offsetByCodePoints(j, -1) else -1
            var i = prev(end)
            if (i < 0) return 0
            while (true) {
                val c = s.codePointAt(i)
                if (c == 0xFE0E || c == 0xFE0F || c in 0x1F3FB..0x1F3FF || c == 0x20E3 || c in 0xE0020..0xE007F) {
                    val p = prev(i); if (p < 0) return i; i = p; continue
                }
                val p = prev(i)
                if (p >= 0 && s.codePointAt(p) == 0x200D) { val q = prev(p); if (q < 0) return p; i = q; continue }
                if (c in 0x1F1E6..0x1F1FF) {                          // a flag: regional letters pair from the start of their run
                    var k = i; var run = 0
                    while (true) { val pp = prev(k); if (pp >= 0 && s.codePointAt(pp) in 0x1F1E6..0x1F1FF) { run++; k = pp } else break }
                    if (run % 2 == 1) return p
                }
                return i
            }
        }
        const val VOICE_MAX = 20 * 1024
        const val VOICE_MIN = 8 * 1024

        /** The key that types [c] without Shift, or 0. */
        private fun code(c: Char): Int = when (c) {
            in 'a'..'z' -> 29 + (c - 'a')
            in '0'..'9' -> 7 + (c - '0')
            ' ' -> 62; '.' -> 56; '/' -> 76; '-' -> 69; ',' -> 55; '=' -> 70; ';' -> 74; '\'' -> 75; '@' -> 77; '+' -> 81
            '#' -> 18; '*' -> 17; '[' -> 71; ']' -> 72; '\\' -> 73; '`' -> 68
            else -> 0
        }

        /** True when [keys] types [t] exactly: every character has a key of its own. */
        fun typable(t: String): Boolean = t.all { code(it) != 0 }

        /** Several letters: a paste, not the one letter at a time live typing sends as it is typed. */
        fun bulk(t: String): Boolean = t.codePointCount(0, t.length) > 1

        /**
         * What proves the code was read off the TV: SHA-256 of both keys (ours first) and the code's last four characters. Its
         * first byte is the code's first two: null when they differ, a mistyped code, caught before the TV drops the attempt.
         */
        internal fun secret(mine: RSAPublicKey, theirs: RSAPublicKey, code: String): ByteArray? {
            val h = MessageDigest.getInstance("SHA-256")
            for (n in listOf(mine.modulus, mine.publicExponent, theirs.modulus, theirs.publicExponent)) h.update(unsigned(n))
            h.update(hex(code.substring(2)))
            return h.digest().takeIf { it[0] == hex(code.substring(0, 2))[0] }
        }

        private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

        /** The number as the TV hashes it: its hexadecimal digits, padded to whole bytes, without a sign byte. */
        private fun unsigned(n: BigInteger): ByteArray = n.toByteArray().let { if (it.size > 1 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
    }
}

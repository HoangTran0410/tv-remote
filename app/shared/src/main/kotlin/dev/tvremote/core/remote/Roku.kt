package dev.tvremote.core.remote
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import dev.tvremote.core.WireLog

/**
 * Roku players and Roku TVs: ECP (External Control Protocol), plain HTTP on port 8060, the way Roku's own phone app sends keys.
 * No pairing and no token; newer Roku OS answers 403 "ECP command not allowed" until "Control by mobile apps" is allowed on
 * the Roku ([LIMITED]). State has no push: [poll] reads the power mode and the app on screen every few seconds.
 */
class Roku(@Suppress("UNUSED_PARAMETER") dir: File, private val host: String, private val port: Int = 8060) : Remote {
    override val kind = Remote.Kind.ROKU
    @Volatile override var model = ""
        private set
    @Volatile override var vendor = "Roku"
        private set
    @Volatile override var version = ""
        private set
    @Volatile override var isOn: Boolean? = null
        private set
    override val volume: Triple<Int, Int, Boolean>? get() = null      // ECP has volume keys only, and no level
    @Volatile override var app = ""
        private set
    @Volatile override var mac = ""
        private set
    override val keys: Set<Int> get() = if (tv) KEYS.keys else KEYS.keys - TV_ONLY

    @Volatile private var live = false
    @Volatile private var closed = false
    @Volatile private var tv = false                                  // a Roku TV: power, volume, channels and inputs
    private val out = Executors.newSingleThreadExecutor { Thread(it, "roku-out").apply { isDaemon = true } }

    override fun ready(): Boolean = live && !closed

    /** [method] [path] → (HTTP code, body); code 0: no answer. */
    private fun http(path: String, method: String = "GET", ms: Int = 3000): Pair<Int, ByteArray> = try {
        val c = dev.tvremote.core.Lan.open(URL("http://$host:$port$path")) as HttpURLConnection
        c.connectTimeout = ms; c.readTimeout = ms; c.requestMethod = method
        if (method == "POST") { c.doOutput = true; c.setFixedLengthStreamingMode(0) }
        try {
            val code = c.responseCode
            code to ((if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes() } ?: ByteArray(0))
        } finally { c.disconnect() }
    } catch (e: IOException) { 0 to ByteArray(0) }

    private fun fetch(path: String): String? = http(path).let { (code, body) -> if (code == 200) String(body, Charsets.UTF_8) else null }

    private fun post(path: String) {
        val (code, body) = http(path, "POST", 2500)
        WireLog.all.add("→", "Roku $host POST $path $code")
        if (code == 403) WireLog.all.add("·", "Roku $host refused: " + String(body, Charsets.UTF_8).take(80))
    }

    /** Blocks until the Roku answers and takes commands; [LIMITED] when "Control by mobile apps" keeps us out. */
    override fun connect(onClosed: () -> Unit) {
        val info = fetch("/query/device-info") ?: throw IOException("${Vidaa.NO_ANSWER} $port")
        WireLog.all.add("←", "Roku $host device-info " + info.replace(Regex("\\s+"), " ").take(400))
        read(info)
        if (field(info, "ecp-setting-mode").lowercase() in setOf("limited", "disabled")) throw IOException(LIMITED)
        val (code, body) = http("/query/active-app")                  // a query refused here means keys are refused too
        if (code == 403) throw IOException(LIMITED + ": " + String(body, Charsets.UTF_8).take(60))
        if (code == 200) app = activeApp(String(body, Charsets.UTF_8))
        live = true
        Thread({ poll(onClosed) }, "roku-poll").apply { isDaemon = true }.start()
    }

    private fun read(info: String) {
        model = field(info, "friendly-model-name").ifEmpty { field(info, "model-name") }
        vendor = field(info, "vendor-name").ifEmpty { "Roku" }
        version = field(info, "software-version")
        val wifi = field(info, "network-type") == "wifi"                // a Roku lists both MACs: the one in use wakes it
        mac = (if (wifi) field(info, "wifi-mac").ifEmpty { field(info, "ethernet-mac") } else field(info, "ethernet-mac").ifEmpty { field(info, "wifi-mac") }).uppercase()
        tv = field(info, "is-tv") == "true"
        field(info, "power-mode").takeIf { it.isNotEmpty() }?.let { isOn = it == "PowerOn" }
    }

    /** The power mode and the app on screen, every 4 s; three misses in a row and the link is over. */
    private fun poll(onClosed: () -> Unit) {
        var missed = 0
        while (!closed) {
            try { Thread.sleep(4000) } catch (e: InterruptedException) { return }
            if (closed) return
            val info = fetch("/query/device-info")
            if (info == null) { if (++missed >= 3) break; continue }
            missed = 0
            field(info, "power-mode").takeIf { it.isNotEmpty() }?.let { isOn = it == "PowerOn" }
            fetch("/query/active-app")?.let { app = activeApp(it) }
        }
        val was = live
        live = false
        if (was && !closed) { WireLog.all.add("·", "Roku $host stopped answering"); onClosed() }
    }

    /** On the keys' thread; nothing once closed (close() and a key may cross). */
    private fun run(task: () -> Unit) {
        try { out.execute { try { task() } catch (gone: InterruptedException) { } } } catch (closing: java.util.concurrent.RejectedExecutionException) { }
    }

    override fun key(code: Int, direction: Int) {
        var name = KEYS[code] ?: return
        if (closed || (!tv && code in TV_ONLY)) return
        if (code == 26) {                                             // a toggle on the remote; ECP has both halves, sent as one press
            if (direction == Atv.UP) return
            name = if (isOn == false) "PowerOn" else "PowerOff"
        }
        run {
            if (!ready()) return@run
            when (if (code == 26) Atv.SHORT else direction) {
                Atv.DOWN -> post("/keydown/$name")
                Atv.UP -> post("/keyup/$name")
                else -> post("/keypress/$name")
            }
            if (code == 26) isOn = name == "PowerOn"
        }
    }

    /** One Lit_ key per character, into the keyboard the Roku shows (there is no way to read or replace what it holds). */
    override fun text(t: String) {
        if (closed) return
        run {
            var i = 0
            while (i < t.length) { val ch = t.codePointAt(i); i += Character.charCount(ch)                   // not codePoints(): API 24
                if (!ready()) return@run
                post("/keypress/Lit_" + URLEncoder.encode(String(Character.toChars(ch)), "UTF-8").replace("+", "%20"))
                Thread.sleep(30)
            }
        }
    }

    /** A channel (app) by its id ("837" YouTube, "12" Netflix), an input ("tvinput.hdmi1"), or a YouTube link to play. */
    override fun launch(link: String) {
        val yt = Regex("(?:youtube\\.com/watch\\?v=|youtu\\.be/)([\\w-]{6,})").find(link)?.groupValues?.get(1)
        val path = when {
            yt != null -> "/launch/$YOUTUBE?contentId=$yt&mediaType=movie"
            Regex("^[\\w.-]{1,80}$").matches(link) -> "/launch/$link"
            else -> return                                            // no web browser on a Roku
        }
        if (closed) return
        run { post(path); if (yt == null) app = link }
    }

    override fun apps(): List<Remote.App> = parseApps(fetch("/query/apps") ?: "").filter { it.second == "appl" }.map { Remote.App(it.first, it.third, "/query/icon/" + it.first) }

    override fun icon(id: String): ByteArray? {
        if (!Regex("^[\\w.-]{1,80}$").matches(id)) return null
        val (code, body) = http("/query/icon/$id")
        return if (code == 200 && body.size > 16) body else null
    }

    /** Roku TVs list their HDMI / AV / antenna inputs among the apps (type "tvin"); a player has none. */
    override fun inputs(): List<Inputs.Input> = parseApps(fetch("/query/apps") ?: "").filter { it.second == "tvin" }.map { (id, _, label) ->
        val lower = id.lowercase()
        val kind = when { "hdmi" in lower -> "hdmi"; "dtv" in lower || "tuner" in lower -> "tuner"; "av" in lower || "cvbs" in lower -> "av"; else -> "other" }
        Inputs.Input(id, kind, if (kind == "hdmi") Regex("(\\d+)$").find(id)?.value?.toIntOrNull() ?: 0 else 0, "", label)
    }

    override fun switchInput(id: String): Boolean {
        if (!Regex("^tvinput\\.[\\w.-]{1,40}$").matches(id) || !ready()) return false
        post("/launch/$id")
        return true
    }

    override fun close() {
        closed = true
        live = false
        out.shutdownNow()
    }

    companion object {
        const val YOUTUBE = "837"
        /** What a 403 means: the Roku keeps phones and computers out until this is allowed (Roku OS 14.1 and later). */
        const val LIMITED = "roku: control by mobile apps is off"

        /** Android key codes to ECP key names; numbers, colours and the Assistant have none and are greyed. */
        val KEYS: Map<Int, String> = mapOf(19 to "Up", 20 to "Down", 21 to "Left", 22 to "Right", 23 to "Select", 66 to "Select",
            4 to "Back", 3 to "Home", 82 to "Info", 165 to "Info", 85 to "Play", 126 to "Play", 127 to "Play", 89 to "Rev", 90 to "Fwd",
            88 to "InstantReplay", 67 to "Backspace", 84 to "Search",
            24 to "VolumeUp", 25 to "VolumeDown", 164 to "VolumeMute", 166 to "ChannelUp", 167 to "ChannelDown",
            26 to "PowerOff", 223 to "PowerOff", 224 to "PowerOn", 170 to "InputTuner")
        /** Only a Roku TV has these: a player leaves power, volume and channels to the TV behind it. */
        val TV_ONLY = setOf(24, 25, 164, 166, 167, 26, 223, 224, 170)

        internal fun field(xml: String, name: String): String =
            Regex("<$name>([^<]*)</$name>").find(xml)?.groupValues?.get(1)?.trim()?.let(::unescape) ?: ""

        /** `<app id="12" type="appl" version="…">Netflix</app>` → (id, type, name). */
        internal fun parseApps(xml: String): List<Triple<String, String, String>> =
            Regex("<app\\s+([^>]*)>([^<]*)</app>").findAll(xml).mapNotNull { m ->
                val attrs = m.groupValues[1]
                val id = Regex("(?:^|\\s)id=\"([^\"]+)\"").find(attrs)?.groupValues?.get(1) ?: return@mapNotNull null
                Triple(id, Regex("(?:^|\\s)type=\"([^\"]+)\"").find(attrs)?.groupValues?.get(1) ?: "appl", unescape(m.groupValues[2].trim()).ifEmpty { id })
            }.toList()

        /** The app on screen, "" on the home screen (the screensaver counts as home). */
        internal fun activeApp(xml: String): String = parseApps(xml).firstOrNull()?.takeIf { it.second == "appl" }?.first ?: ""

        private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
    }
}

package dev.tvremote.core.ontv
import java.io.File
import dev.tvremote.core.adb.Pkg
import java.io.IOException
import java.net.URL
import dev.tvremote.core.adb.Adb

/**
 * Tailscale on the TV with as little done by hand as possible, all over ADB: install the official APK, let it make its VPN without
 * the "Connection request" box, switch it on, and when it still has to be signed in, read the sign-in link off its screen.
 * Whoever opens that link in a browser already signed in to Tailscale adds the TV with one click; nobody types anything on the TV.
 */
class Tailscale(private val adb: Adb, private val dir: File?) {
    companion object {
        /** A TV reached through Tailscale: its 100.64/10 address or a MagicDNS name. */
        fun isAddress(host: String): Boolean = host.endsWith(".ts.net") || Regex("100\\.(?:6[4-9]|[7-9]\\d|1[01]\\d|12[0-7])\\..*").matches(host)
        const val PKG = "com.tailscale.ipn"
        private const val BASE = "https://pkgs.tailscale.com/stable/"
        private const val UI = "/sdcard/tvr-ui.xml"
        /** The buttons on the way to its sign-in code: "Get Started" on the first run, then "Log in". */
        private val LOGIN = Regex("^(get started|continue|log ?in|sign ?in|bắt đầu|đăng nhập|anmelden|connexion|iniciar sesión|ログイン|登录|로그인)$", RegexOption.IGNORE_CASE)
        /** The code under the QR code: the end of https://login.tailscale.com/a/<code>. */
        private val CODE = Regex("^[0-9a-f]{8,20}$")
        private val NODE = Regex("<node [^>]*?text=\"([^\"]*)\"[^>]*?bounds=\"\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]\"")
    }

    fun interface Step { fun at(text: String, percent: Int) }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 20000
        try { return c.inputStream.bufferedReader().readText() } finally { c.disconnect() }
    }

    @Throws(IOException::class)
    fun installed(): Boolean = Pkg.installed({ adb.shell(it) }, PKG)

    /** "100.x.y.z" once it is signed in and on, else "". */
    @Throws(IOException::class)
    fun ip(): String = Regex("inet (100\\.[0-9.]+)/32").find(adb.shell("ip -4 addr show"))?.groupValues?.get(1) ?: ""

    /** The newest official APK, checked against its published SHA-256, then installed through ADB (no "unknown sources" needed). */
    @Throws(Exception::class)
    fun install(step: Step) {
        val name = org.json.JSONObject(get("$BASE?mode=json")).getJSONObject("APKs").getString("universal")
        val sha = get(BASE + name + ".sha256").trim().split(Regex("\\s+"))[0]
        step.at("download", 5)
        val setup = Setup(adb, dir, dev.tvremote.core.Words.lang, Setup.Log { })
        var shown = -1
        val apk = setup.download(BASE + name, "tailscale.apk", sha) { pct -> if (pct / 5 != shown) { shown = pct / 5; step.at("download", 5 + pct * 55 / 100) } }
        try {
            step.at("install", 60)
            val out = setup.install(apk)
            if (!out.contains("Success")) throw IOException("install failed: " + out.trim().lines().lastOrNull())
        } finally { apk.delete() }
    }

    /** Comes back by itself after standby or a restart of the TV, and Android does not put it to sleep. Safe to repeat. */
    @Throws(IOException::class)
    fun keepAlive() {
        adb.shell("dumpsys deviceidle whitelist +$PKG")                // exempt from Doze / battery optimisation
        adb.shell("am set-standby-bucket $PKG active")
        adb.shell("cmd appops set $PKG RUN_IN_BACKGROUND allow; cmd appops set $PKG RUN_ANY_IN_BACKGROUND allow")
        adb.shell("settings put secure always_on_vpn_app $PKG; settings put secure always_on_vpn_lockdown 0")   // the VPN starts with the TV, nothing blocked without it
    }

    /**
     * Installs it when missing, switches it on, and answers "" when the TV is in the tailnet (see [ip]),
     * or the link to sign it in with. Leaves Tailscale's own sign-in screen up on the TV, with the same link as a QR code.
     */
    @Throws(Exception::class)
    fun start(busy: (Boolean) -> Unit = {}, step: Step): String {   // [busy]: while keys are pressed on its screen
        if (!installed()) install(step)
        step.at("start", 80)
        keepAlive()
        adb.shell("cmd appops set $PKG ACTIVATE_VPN allow")           // no "Tailscale wants to set up a VPN connection" box
        adb.shell("pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null")   // nor "Allow notifications?" (Android 13+)
        if (ip().isNotEmpty()) return ""
        adb.shell("am broadcast -a com.tailscale.ipn.CONNECT_VPN -n $PKG/.IPNReceiver")   // signed in but switched off: this is enough
        for (i in 0 until 6) { Thread.sleep(1000); if (ip().isNotEmpty()) return "" }
        AppRestore.run({ adb.shell(it) }, PKG, "am start -n $PKG/.MainActivity 2>&1") { !it.contains("Error") }   // turned off on the TV: brought back once
        busy(true)                                                    // from here keys are pressed on its screen: hands off the TV's remote
        try { return signIn() } finally { busy(false) }
    }

    private fun signIn(): String {
        var pressed = 0
        for (i in 0 until 20) {
            Thread.sleep(1500)
            if (ip().isNotEmpty()) return ""
            val nodes = NODE.findAll(adb.shell("uiautomator dump $UI >/dev/null 2>&1; cat $UI; rm -f $UI")).toList()
            nodes.firstOrNull { CODE.matches(it.groupValues[1]) }?.let { return "https://login.tailscale.com/a/" + it.groupValues[1] }
            val login = nodes.firstOrNull { LOGIN.matches(it.groupValues[1].trim()) }
            // only ever on Tailscale's own screen: OK pressed while another app's box (above all "Allow USB debugging?" for
            // someone else's key) is in front would answer that box instead
            if (!adb.shell("dumpsys window | grep -E 'mCurrentFocus|mFocusedWindow'").let { it.contains(PKG) && !it.contains("UsbDebugging") }) continue
            if (login != null && pressed < 5) {
                val (x1, y1, x2, y2) = login.groupValues.drop(2).map { it.toInt() }
                adb.shell("input tap " + (x1 + x2) / 2 + " " + (y1 + y2) / 2)
                pressed++
            } else if (login == null && i == 4 && pressed == 0) {
                adb.shell("input keyevent 23")                        // a button we cannot read: it is the one in focus on a TV
                pressed++
            }
        }
        throw IOException("no sign-in link on the TV's screen")
    }
}

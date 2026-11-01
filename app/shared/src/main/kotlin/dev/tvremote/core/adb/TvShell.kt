package dev.tvremote.core.adb
/**
 * Shell recipes run on the TV for the Apps tab (clean, back up, restore, log) and the TV card (restart, DNS, quick settings).
 * app/shared/tests/tvshell_cases.json holds what each must give (TvShellTest); all three remotes run these, so they change
 * a TV in exactly the same way. Anything a person typed is checked
 * here first and throws IllegalArgumentException.
 */
object TvShell {
    private val PKG = Pkg.RE
    private val COMP = Regex("[A-Za-z][A-Za-z0-9_.]*/[A-Za-z0-9_.\$]+")
    private val HOST = Regex("(?=.{4,253}\$)[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+")
    private val SINCE = Regex("[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}")
    private val NUM = Regex("-?[0-9]{1,10}")
    private val PERM = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")

    /**
     * A sentence for the TV to look up, not a command: YouTube's results when the TV has YouTube, since the Assistant of Android
     * TV knows no Vietnamese (an FPT Play box spun on it for good) and some TVs have no search at all; else the TV's own search /
     * Assistant. Prints "yt" or "gs" for what took it, nothing when nothing did. YouTube only when enabled: an FPT box ships it switched
     * off, and the link then failed with no search at all.
     */
    fun search(q: String): String {
        val s = q.trim().take(300)
        require(s.isNotEmpty()) { "empty query" }
        return "if pm list packages -e $YOUTUBE 2>/dev/null | grep -qx package:$YOUTUBE; then am start -a android.intent.action.VIEW -d " + Adb.q(youtube(s)) + " -p $YOUTUBE 2>&1 | grep -q Error || echo yt; " +
            "else am start -a android.search.action.GLOBAL_SEARCH --es query " + Adb.q(s) + " 2>&1 | grep -q Error || echo gs; fi"
    }
    const val YOUTUBE = "com.google.android.youtube.tv"
    fun youtube(q: String) = "https://www.youtube.com/results?search_query=" + java.net.URLEncoder.encode(q.trim(), "UTF-8")

    const val CLEAN = "a=\$(stat -f -c '%a %S' /data); pm trim-caches 999G >/dev/null 2>&1; sleep 2; echo \$a \$(stat -f -c '%a %S' /data)"

    const val REBOOT_PREP = "case \"\$(settings get secure always_on_vpn_app)\" in com.tailscale.ipn) ;; *) " +
            "pm path com.tailscale.ipn >/dev/null 2>&1 && ip addr | grep -qE 'inet 100\\.(6[4-9]|[7-9][0-9]|1[01][0-9]|12[0-7])\\.' " +
            "&& { settings put secure always_on_vpn_app com.tailscale.ipn; settings put secure always_on_vpn_lockdown 0; };; esac; echo ok"

    private const val HOME_LIST = "cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME"
    private const val HOME_NOW = "{ " + dev.tvremote.core.ontv.LauncherSwap.RESOLVE_HOME + "; } | tail -n 1"

    /**
     * Shell test: does the TV resolve names right now? A name made up on the spot, that the wildcard services sslip.io and
     * nip.io answer with the address written in it: never in the TV's cache, so a DNS that stopped working cannot pass on
     * answers it gave earlier (www.google.com passed that way on a Sony with Tailscale, and YouTube then failed for good).
     */
    const val NAMES_WORK = "{ r=t\$RANDOM\$RANDOM; timeout 5 ping -c 1 -W 3 \$r.1.2.3.4.sslip.io 2>&1 | grep -q '^PING ' || " +
            "timeout 5 ping -c 1 -W 3 \$r.1.2.3.4.nip.io 2>&1 | grep -q '^PING '; }"

    /** Where TV Setup notes a DNS filter it turned off because names stopped resolving with it ("<host> <seconds>"). */
    const val DNS_OFF = "tvr_dns_off"

    const val TVSET_READ = "echo sdk \$(getprop ro.build.version.sdk); echo dns_mode \$(settings get global private_dns_mode); " +
            "echo dns_host \$(settings get global private_dns_specifier); echo dns_off \$(settings get global $DNS_OFF); echo sleep \$(settings get secure sleep_timeout); " +
            "echo saver \$(settings get secure screensaver_enabled); echo saver_after \$(settings get system screen_off_timeout); " +
            "wm density; echo home \$(" + HOME_NOW + "); " + HOME_LIST + " | grep / | sed 's/^ */launcher /'"

    const val BOOT = "cmd package query-receivers --brief -a android.intent.action.BOOT_COMPLETED 2>/dev/null | grep / | sed 's/^ *//'"

    // then the live system and media volumes with their range: the volume_* keys are what Android last saved, often stale
    const val SETTINGS_READ = "for n in global secure system; do settings list \$n 2>/dev/null | sed \"s/^/\$n /\"; done; " +
        "for s in 1 3; do echo \"vol \$s \$(cmd media_session volume --stream \$s --get 2>/dev/null | grep 'volume is')\"; done"

    fun appInfo(pkg: String): String {
        pkg(pkg)
        return "p=$pkg; dumpsys package \$p | sed -n '/^Packages:/,\$p'; printf '\\043~\\043\\n'; pidof \$p; printf '\\043~\\043\\n'; " +
            "dumpsys meminfo \$p 2>/dev/null | grep -m1 -E '^ *TOTAL'; printf '\\043~\\043\\n'; $BOOT | grep \"^\$p/\""
    }

    fun perm(pkg: String, name: String, on: Boolean): String {
        pkg(pkg)
        if (!name.matches(PERM)) throw IllegalArgumentException("bad permission")
        return "pm ${if (on) "grant" else "revoke"} $pkg $name 2>&1 && echo ok"
    }

    fun backup(pkg: String): String {
        pkg(pkg)
        return "trap '' HUP PIPE; p=$pkg; d=/data/local/tmp/tvr-bk-\$\$; rm -rf \$d; mkdir -p \$d/apk && cd \$d || exit 1; " +
            "for f in \$(pm path \$p | sed 's/^package://'); do ln -s \"\$f\" apk/\${f##*/}; done; " +
            "for k in obb data; do [ -d /sdcard/Android/\$k/\$p ] && ln -s /sdcard/Android/\$k/\$p \$k; done; " +
            "echo \$p > package.txt; dumpsys package \$p | grep -m1 versionName | cut -d= -f2 > version.txt; " +
            "tar -chf - * 2>/dev/null; cd /; rm -rf \$d"
    }

    fun restore(tar: String): String {
        if (!tar.matches(Regex("/data/local/tmp/tvr-rs-[0-9a-f]{8}\\.tar"))) throw IllegalArgumentException("bad restore path")
        return "t=$tar; d=\${t%.tar}; rm -rf \$d; mkdir -p \$d && cd \$d && tar -xf \$t 2>/dev/null; " +
            "ls apk/*.apk >/dev/null 2>&1 || { echo 'not a backup'; cd /; rm -rf \$d \$t; exit; }; " +
            "p=\$(cat package.txt 2>/dev/null); echo \"\$p\" | grep -qE '^" + Pkg.PATTERN + "\$' || p=; " +
            "s=\$(pm install-create -r | tr -dc 0-9); " +
            "for f in apk/*.apk; do cat \$f | pm install-write -S \$(stat -c %s \$f) \$s \${f##*/} - >/dev/null; done; " +
            "pm install-commit \$s 2>&1; " +
            "[ -n \"\$p\" ] && for k in obb data; do [ -d \$k ] && mkdir -p /sdcard/Android/\$k/\$p && cp -R \$k/. /sdcard/Android/\$k/\$p/; done; " +
            "cd /; rm -rf \$d \$t; echo package \$p"
    }

    fun log(pkg: String, since: String = ""): String {
        pkg(pkg)
        if (since.isNotEmpty() && !since.matches(SINCE)) throw IllegalArgumentException("bad time")
        val pick = if (since.isNotEmpty()) "-T '$since'" else "-t 300"
        return "p=\$(pidof $pkg); echo pid \$p; [ -n \"\$p\" ] && logcat -d -v threadtime --pid=\${p%% *} $pick | tail -n 500"
    }

    /**
     * The settings list (Health tab) changes simple settings. These stay read-only, here and on the page (js/tvread.js
     * settingLocked, the same pattern): a wrong value could cut the TV off from every remote (ADB, Wi-Fi, DNS, proxy, VPN),
     * leave it without a keyboard or stuck in a screen reader, open it to unknown apps, or break its first setup.
     */
    val SET_LOCKED = Regex("(?i).*(adb|develop|debug|provision|setup|verif|install|package|vpn|dns|proxy|wifi|airplane|bluetooth|mobile|tether|captive|ntp|" +
        "lock|password|policy|service|input_method|accessibility_enabled|touch_exploration|location|android_id|_uri|component|hidden_api|database|locale|" +
        "density|volume|boot|hdr|formats|user|" +
        "fac|zram|usb_mass|netstats|standby|safe_volume|shop|tuner|tcl|miracast|always_on_vpn|maintenance|earc_status).*")   // the TCL's list: factory flags, vendor switches, the Tailscale VPN
    /** ADB and Wireless debugging may be turned on (nothing is lost, the remotes only gain a way in), never off: js/tvread.js ON_ONLY */
    val ON_ONLY = mapOf("adb_enabled" to "1", "adb_wifi_enabled" to "1")
    /** Volumes in the settings list: Android's saved copy, so they go through the volume command ([Scenes.volume]), not `settings put` */
    val VOLUME = mapOf("volume_system" to 1, "volume_music" to 3)            // the others follow the media volume on a TV
    private val SET_NAME = Regex("[A-Za-z][A-Za-z0-9_.]{0,80}")
    private val SET_VALUE = Regex("[A-Za-z0-9 ._:+-]{0,100}")

    fun setting(table: String, name: String, value: String): String {
        if (table !in setOf("global", "secure", "system")) throw IllegalArgumentException("bad table")
        if (!name.matches(SET_NAME)) throw IllegalArgumentException("bad name")
        val only = ON_ONLY[name]
        if (only != null) { if (table != "global" || value != only) throw IllegalArgumentException("locked") }
        else if (SET_LOCKED.matches(name)) throw IllegalArgumentException("locked")
        if (!value.matches(SET_VALUE)) throw IllegalArgumentException("bad value")
        if (only != null)                                               // Android turns Wireless debugging back off without Wi-Fi: say so
            return "settings put global $name '$value'; sleep 1; [ \"\$(settings get global $name)\" = '$value' ] && echo ok || echo 'turned itself back off'"
        return "settings put $table $name '$value'; echo ok"
    }

    fun tvset(key: String, raw: String): String {
        var value = raw.trim()
        when (key) {
            "dns" -> {
                if (value.isEmpty()) return "settings put global private_dns_mode opportunistic; settings delete global private_dns_specifier >/dev/null 2>&1; settings delete global $DNS_OFF >/dev/null 2>&1; echo ok"
                value = value.lowercase(java.util.Locale.ROOT)
                if (!value.matches(HOST)) throw IllegalArgumentException("bad DNS name")
                return "trap '' HUP PIPE; m=\$(settings get global private_dns_mode); h=\$(settings get global private_dns_specifier); " +
                    "settings put global private_dns_specifier $value; settings put global private_dns_mode hostname; " +
                    "e=\$((\$(date +%s)+30)); while [ \$(date +%s) -lt \$e ]; do sleep 2; " +
                    "$NAMES_WORK && { settings delete global $DNS_OFF >/dev/null 2>&1; echo ok; exit; }; done; " +
                    "if [ \"\$h\" = null ]; then settings delete global private_dns_specifier >/dev/null 2>&1; else settings put global private_dns_specifier \"\$h\"; fi; " +
                    "[ \"\$m\" = null ] && m=opportunistic; settings put global private_dns_mode \$m; echo failed"
            }
            "sleep" -> return "settings put secure sleep_timeout ${int(value, 60000, 86400000, -1)}; echo ok"
            "saver" -> {
                if (value == "off") return "settings put secure screensaver_enabled 0; echo ok"
                return "settings put secure screensaver_enabled 1; settings put system screen_off_timeout ${int(value, 60000, 86400000)}; echo ok"
            }
            "density" -> {
                if (value == "reset") return "wm density reset; echo ok"
                return "wm density ${int(value, 100, 800)}; echo ok"
            }
            "home" -> {
                if (!value.matches(COMP)) throw IllegalArgumentException("bad home screen")
                return "$HOME_LIST | grep -qF '$value' || { echo 'not a home screen'; exit; }; " +
                    "cmd package set-home-activity '$value' >/dev/null 2>&1; echo home \$($HOME_NOW)"
            }
        }
        throw IllegalArgumentException("unknown setting")
    }

    private fun pkg(pkg: String) { if (!pkg.matches(PKG)) throw IllegalArgumentException("bad package name") }

    private fun int(value: String, lo: Long, hi: Long, also: Long? = null): Long {
        if (!value.matches(NUM)) throw IllegalArgumentException("bad number")
        val n = value.toLong()
        if (n != also && n !in lo..hi) throw IllegalArgumentException("out of range")
        return n
    }
}

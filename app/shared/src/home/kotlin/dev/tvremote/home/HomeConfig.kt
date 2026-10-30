package dev.tvremote.home

import org.json.JSONArray
import org.json.JSONObject

/**
 * TV Home's settings, the one place their shape and limits are written: the launcher (app/tvhome) keeps them, the phone and
 * the computer change them over ADB (core/ontv/Home.kt → HomeReceiver). Compiled into both sides, so they never disagree.
 *   dock    favourite apps in their order, at most [DOCK_MAX]
 *   hidden  apps left off the grid
 *   order   the grid's own order, set by moving apps on the TV; an app it does not list goes after those that it does (A-Z), an app
 *           that is gone is ignored
 *   wall    the background on now: a gradient ([PLAIN]), a drawn scene ([ART]), "bing" (the day's Bing picture, kept by TV Home), or one
 *           of the person's photos ([PHOTOS]: "photo" is wall.jpg, "photo2" wall2.jpg ... in TV Home's external files folder, sent by
 *           the client; at most [PHOTO_MAX])
 *   wcol, wdir the person's own wall [OWN]: wcol 1..[OWN_MAX] colours "rrggbb,rrggbb" (one = a solid colour), wdir its direction
 *           ([WDIRS]: "tl_br" top-left to bottom-right … "radial" from the middle). A TV Home that has this reports "ownOk": true
 *   wurl    the page the "web" wall shows (an Immich Kiosk, a dashboard): only http:// or https:// (LAN pages are mostly plain http),
 *           at most [WURL_MAX]; anything else is dropped. "web" with no good wurl is an unknown wall: the default gradient. "web" is
 *           never turned through: choosing it makes [walls] just that one, and a set sent with it elsewhere loses it
 *   vid:<8 hex> a wall too: a looping video or animated GIF from TV Home's media library (app/tvhome wall/Videos.kt: the file is
 *           `videos/<id>.mp4` there, GIFs are turned into mp4 on arrival). Any such id is accepted here; whether its file is there is the
 *           launcher's to know ([Walls.usable]), so a deleted one is skipped by the rotation and falls to Night when shown
 *   vfps, vres  how a GIF or a heavy video is made light at upload ([VFPS], [VRES]); "" = the source's own / the screen's own size. Applies to the next uploads
 *   photo   when a photo last changed, so the launcher reads it again
 *   wfit    how a photo (yours, Bing, a picture theme) fills the screen ([WFITS]): "cover" cropped to fill it, "contain" whole over
 *           itself blurred (a portrait photo). A TV Home that has this reports "fitOk": true
 *   shade   how dark the veil over a photo, a drawn scene or the web page is, 0..100 % in steps of [SHADE_STEP]: 0 none,
 *           [SHADE_DEFAULT] the usual one (darker at the top and the bottom, where the clock and the names are), 100 an even veil
 *           nearly black. A busy page or photo fights the words of the home screen at the usual one. A TV Home that has this
 *           reports "shadeOk": true
 *   walls   the set the background turns through (any of the above, [WALLS_MAX]); never empty, [wall] is what shows now
 *   wevery  how often it moves on to the next of [walls] ([EVERY]): "off", "30m", "1h", "6h", "day" (the first time Home is seen on a
 *           new day), "show" (each time Home comes back on screen). Only while Home is on screen or when it comes back: nothing
 *           runs in between. A TV Home that does this reports "wrotate": true (the page keeps its one-choice list for one that
 *           does not)
 *   clock24 24-hour clock; greet: the greeting under the clock
 *   gstyle  what the greeting says ([GSTYLES]): "time" the time of day ("Good evening"), "name" that and [gname] ("Good evening,
 *           Hoang"), "own" [gtext] as written; one with nothing to show falls back to "time" ([greeting]). gname at most
 *           [GNAME_MAX], gtext at most [GTEXT_MAX], one line. A TV Home that has this reports "greetOk": true
 *   dstyle  the header's second line, "date" by default; gstyle and dstyle take the same [GSTYLES]: also "date" ("Tuesday,
 *           27 October") and "lunar" ("18/9 lunar · Bính Ngọ"); "own" text ([gtext] / [dtext]) has [TOKENS] filled in ([headLine]).
 *           Each line's type ([HEAD_TYPE]): g|d + size (s, m, l), bold, italic, caps, color (white, dim, red). A TV Home that has
 *           these reports "headOk": true
 *   show*   whether each thing TV Home draws on its first screen is there ([SHOWS], all true: nothing is forced on the person):
 *           showClock, showDate (the clock and the day beside it), showNet (the Wi-Fi button), showGear (the settings button; the
 *           Menu key and the clients still reach Customize), showAdd (the "+ Add" tile), showSuggest (the "Get TV Setup" tile),
 *           showWatch (the Play Next / channels row, on top of hiding one app's from its menu). A TV Home older than these
 *           does not report them, so a page offers them only to one that does
 *   watchAt where the Play Next items go ([WATCH_AT], default "bottom"): "top" a row of their own above the apps, "under1" under
 *           the first row of apps, "bottom" after the last row of apps, "tiles" no row: they follow the grid's last tile in its columns.
 *           A TV Home older than this does not report it, so a page offers the choice only to one that does
 *   folders  folders on the app grid: {"<id>": {"name": "...", "apps": [package, ...]}}, at most [FOLDERS_MAX] of [FOLDER_APPS_MAX] apps. A folder is
 *           a grid tile like a live widget: its key "folder:<id>" ([isFolder]) sits in "tiles" and in "order", so it is placed, moved and removed
 *           as any tile; it lives while that key is in "tiles" and it holds an app. An app in a folder is off the grid (and out of
 *           the dock); one app is in one folder. [Folders] holds the operations the TV and the page both do. A TV Home that has this reports "foldersOk": true
 *   lang    TV Home's words: "" the TV's own language, or one of [LANGS] whatever the TV is set to
 *   anim    motion effects (a focused app lifts, rows glide, the screen fades in); off = instant, with a ring. TV Home also
 *           treats the system's "animator duration scale 0" as off
 *   shift   burn-in care for OLED: every minute Home moves one step round a square of at most 6 dp (clock, widgets, top bar; the wallpaper
 *           stays), default false, nothing runs while off. A TV Home that has this reports it in the config; older ones do not.
 *           The "black" wall is the pure #000 one
 *   motion  how much moves while [anim] is on: "" this TV's choice (calm on one with little memory, 2.25 GB or less, or that Android
 *           calls low on RAM), "calm" (no overshoot, shorter, only the tile grows: fewer frames on a weak chip), "full"
 *   focus   how the focused app shows: "glass" (a pane gliding from tile to tile) or "ring" (a blue ring on the tile)
 *   cols    how many app tiles in a row of the grid, [COLS_MIN]..[COLS_MAX] (default [COLS_DEFAULT]). A TV Home that has this reports
 *           "colsOk": true (the page offers the choice only to one that does)
 *   rows    how many rows of apps the first screen shows under the clock or the widgets, tenths allowed (1.2: row 1 and a fifth of
 *           row 2), 1..[ROWS_MAX]; 0 = by itself (1.2 without widgets, right under them with). More than fit stops where the grid
 *           meets the widgets or the clock (TV Home reports that as "rowsMax", and "rowsOk": true)
 *   widgets the widgets between the clock and the favourites, in their order ([WIDGETS]): "system" (memory, storage, network,
 *           ADB), "usage" (the most used apps of the week and the day's app limits, asked of TV Setup: [USAGE]),
 *           "calendar" (the month, the lunar day under every solar day),
 *           "lunar" (the lunar date, the next full moon and Tết), "clock" (an analog clock face, round, sweeping second hand),
 *           "timer" (the sleep timer or bedtime, from TV Setup too;
 *           shown only while one is set), "weather" (the sky now and the next days, Open-Meteo: [place] or, by itself, the device's
 *           location or the internet address's), "air" (the air quality index and PM2.5 at that place, Open-Meteo's air-quality API;
 *           a TV Home older than it drops the key), "recent" (the last apps opened, as icons that open them on OK: TV Setup's HomeInfo
 *           "recent", in the order Android's usage events came, never by the clock), "adblock" (whether Private DNS names an ad-blocking
 *           resolver, [ADBLOCK_DNS]; OK turns it on / off, which needs [WRITE_SECURE] granted over ADB; Android 9+: before it TV Home
 *           leaves it out of "wschema"), "prayer" (the five prayers and the sunrise of today at the weather's place, worked out on the TV
 *           with no network: the next one with a countdown, and the Hijri date; options: method, Asr school, Hijri adjust)
 *           Their order in the list is the order on the screen (the page and the TV's panel move them).
 *   qrs     QR widgets: {"qr1": {tpl, f, cap, show}, ...} up to [HomeQr.QR_MAX], each a template's fields (Wi-Fi, link, text, phone, SMS,
 *           e-mail, contact: [HomeQr]) and a caption; a widget is on the screen when its key is also in "widgets" (the order, the size).
 *           A key with no widget here is dropped from "widgets". The Wi-Fi password lives here, on the TV, and nowhere else. A TV Home
 *           that has this reports "qrOk": true
 *   wsize   how big each widget is: {"system": "big", "lunar": "small", ...}, "big" or "small", the default of a widget when it is
 *           missing ([defaultSize]). [pack] turns the sizes and the order into columns: a big widget is a column, two small widgets
 *           in a row stack in one, a small widget alone is a narrow column; what does not fit waits. A TV Home that has this reports
 *           "wsizes": true (the page offers the old layout chips to one that does not). Replaces "wlayout" (row / big / big2 /
 *           flip / quad), which [clean] still reads once, as sizes giving the same picture, when there are no "wsize" yet
 *   wopt    a widget's own options besides its size, by widget: {"clock": {"face": "minimal", "theme": "light"}, "weather": {"look": "tonal"}}.
 *           What each widget offers is [options] (one schema, reported as "wschema" and rendered by TV Home's settings dialog and the page's);
 *           a missing option is its default. Replaces "wstyle" ({"clock": "light"}), which [clean] and [merge] still read once
 *           ([fromStyle]); a client sends only what it changed, [merge] adds it per widget and option
 *   place   a city for the weather widget, as typed ([PLACE_MAX] characters); empty = automatic
 *   market  (widget) gold (SJC, 9999) and the banks' exchange rates in VND, optionally Bitcoin: public prices fetched by the TV itself,
 *           the addresses in the site's signed list docs/home/feeds.json; options "layout" card / ticker (one scrolling line), "show"
 *           all / gold / rates, "btc". news (widget) headlines with a picture from RSS feeds (the same list, by the TV's language, and
 *           [feeds]: the person's own links, [FEEDS_MAX]); option "src" auto (the list) / mine / both. OK on a headline opens the
 *           reader; OK on the weather widget opens its map. A TV Home that has these reports "infoOk": true
 *   feeds   the person's own RSS links for the news widget, [FEEDS_MAX] at most ([feeds]); set from the page, like [place]
 *   dream   TV Home is the TV's screensaver (the big clock and the wallpaper, [DREAM]). Read from the system, not kept: the
 *           launcher sets `screensaver_components` itself (it holds WRITE_SECURE_SETTINGS once a client has granted it over
 *           ADB) and puts the previous screensaver back when this is switched off
 *   dface   the screensaver's look ([DFACES]): "big" (the big clock and the day, as before), "modern" (StandBy: the Modern clock
 *           face beside the day, the clock widget's own drawing), "digital" (the Digital face, filling the screen)
 *   note    a line from the family, pinned on the home screen until it is emptied ([NOTE_MAX] characters, one line)
 *   names   the person's own names for apps and shortcuts: {"<package or pin:...>": "name"}, at most [NAMES_MAX], each [NAME_MAX] characters;
 *           shown wherever TV Home shows the app's name, and always on a banner tile (two YouTubes look the same). Not a theme key
 *   tiles   live widgets ("tile:<provider package>/<id>", [isLive]) and launcher shortcuts ("pin:<package>/<id>", [Target.isPin]) on the app grid, at most [TILES_MAX]. A provider (TV Setup's RAM
 *           live widget: tile:dev.tvremote.setup/ram, and its sleep timer; TV Channels' tile:dev.tvremote.channels/next) draws its value
 *           itself, in its layout (gauge / next / toggle); the key then sits in dock / hidden / order like an app's package name.
 *           A TV Home that has this reports "tilesOk": true, "tilesAvail" (what the providers offer: [{key, title, kind, layout}]) and
 *           "tilesState": "ok" | "missing" (no TV Setup or one without live widgets) | "untrusted" (signed by another key)
 *           ("tiles", "tilesOk", "tile:" are stored on TVs and sent between apps: kept, they mean live widgets)
 *   keyon   remote keys that open something (default off); keyapps: {"183": target} the key code to its [Target] (a package, "pin:pkg/id"
 *           or "tile:provider/id"; a plain package is how older configs saved it), only keys [keyFree] allows. TV Home answers them on its own screen; TV Setup's key filter answers them everywhere (HomeInfo "keys").
 *           A TV Home that has this reports "keysOk": true, "keysSetup" (TV Setup took the map) and "keysDead" (the keys it found dead)
 *   themes  the person's own themes, [THEMES_MAX]: [{id, name, conf}], conf = the look at the moment of saving ([THEME_KEYS]: background and its
 *           set, shade, video sound, widgets with sizes and looks, grid, focus, motion, clock; NOT the apps, the QR widgets (a Wi-Fi password),
 *           keys, language, screensaver, power button). Kept on the TV with the rest. Changed by ONE field every client sends,
 *           "theme": {"apply": id} | {"save": name} | {"del": id} ([merge] reads it, it is never stored); [ready] are the ready-made
 *           ones (the site's signed docs/home/themes.json: ids, names per language). A TV Home that has this reports "themesOk": true,
 *           "themesBuiltin": [{id, names, conf}] and "theme": the id of the one whose look is on now ("" none). A theme's pictures and videos stay while it
 *           keeps them ([themeUses], Videos.refs)
 *   infold  the Inputs list shows the connected HDMI ports first and folds the rest (jacks always; a port only when the TV says "disconnected" and its
 *           answers can be trusted) behind a "More inputs" row; default off (nothing new runs)
 *   autoon  open [autoapp] (a [Target] too) once each time the TV is turned on (boot or wake from standby); default off. TV Setup does it
 *           (TV Home is not running in standby), so it needs TV Setup, said in the setting ("keysSetup")
 *   autoall "always": open at power-on even when the TV woke into another app or input (default off; the guard of one open per power-on and a
 *           cast just started still hold). With no [autoapp] it brings TV Home (the launcher) up. A TV Setup that has it answers "all": true
 *           to "keys", which TV Home reports as "keysAll"
 */
object HomeConfig {
    const val PKG = "dev.tvremote.home"
    const val RECEIVER = "$PKG/.HomeReceiver"
    const val QUICK_KEYS = "$PKG.QUICK_KEYS"                           // HomeActivity's action: its Quick keys screen (TV Setup's key tester: "assign this key")
    const val PHOTO_QR = "$PKG.PHOTO"                                  // HomeActivity's action: its QR screen for a photo from a phone (TV Setup's page on the TV has no photos)
    const val DREAM = "$PKG/.HomeDream"                               // the screensaver's component, as `screensaver_components` writes it
    const val WRITE_SECURE = "android.permission.WRITE_SECURE_SETTINGS"
    /** Granted over ADB when TV Home is installed or set up, never asked on the TV: the Wi-Fi's name is location to Android (8.1+), and
     *  Android TV's "pair an accessory" screen (Control Center's Bluetooth) refuses to open for an app without BLUETOOTH_CONNECT (12+),
     *  and other apps' Play Next and channels are READ_TV_LISTINGS (apps/Watch.kt); WRITE_SECURE_SETTINGS lets the Ad blocking widget set Private DNS. */
    const val GRANT_WIFI = "pm grant $PKG android.permission.ACCESS_FINE_LOCATION >/dev/null 2>&1; pm grant $PKG android.permission.BLUETOOTH_CONNECT >/dev/null 2>&1; pm grant $PKG android.permission.READ_TV_LISTINGS >/dev/null 2>&1; pm grant $PKG $WRITE_SECURE >/dev/null 2>&1; "
    /**
     * Private DNS names that block ads (DNS-over-TLS, host names only), the first is what the "adblock" widget turns on. The page's
     * `DNS_SETS.ads` in js/tvset.js is the same list (tests/home_adblock.test.js keeps the two equal).
     */
    val ADBLOCK_DNS = listOf("dns.adguard-dns.com", "adblock.dns.mullvad.net", "p2.freedns.controld.com")
    fun adblockHost(host: String?) = host != null && host.lowercase() in ADBLOCK_DNS
    /** Android 9 (API 28) is the first with Private DNS. */
    const val ADBLOCK_MIN_SDK = 28
    const val DOCK_MAX = 6
    const val COLS_MIN = 4; const val COLS_MAX = 8; const val COLS_DEFAULT = 5
    const val ROWS_MAX = 10.0
    private const val HIDDEN_MAX = 300
    private const val ORDER_MAX = 800
    /** "sky" (in [PLAIN]) is a gradient that follows the sun from where the TV is ([Sun]); its colours are worked out on the TV and repainted every [Sun.REPAINT_MS]. */
    const val SKY = "sky"
    /** Plain gradients (HomeActivity.wallColors). */
    val PLAIN = listOf("sky", "night", "ocean", "dusk", "forest", "aurora", "black", "sunset", "ember", "plum", "midnight", "teal", "slate", "graphite", "navy", "wine", "moss")
    /** Scenes TV Home draws itself, no picture file (WallSpec; app/tvhome wall/WallArt.kt). */
    val ART = listOf("dawn", "peaks", "dunes", "waves", "candy", "peach", "meadow", "mist", "bokeh", "canyon")
    /** The person's own wall: a colour or a gradient they make ([wcol], [wdir]). */
    const val OWN = "own"
    const val OWN_MAX = 4
    const val WCOL_DEFAULT = "0b1d3a,14307a,7a2e12"
    val WDIRS = listOf("tl_br", "tb", "lr", "bl_tr", "br_tl", "bt", "rl", "tr_bl", "radial")
    private val HEX6 = Regex("[0-9a-f]{6}")
    /** [s] as the colours it names (at most [OWN_MAX]); bad parts dropped, none good: [WCOL_DEFAULT]. */
    fun wcol(s: String): List<String> = s.lowercase().split(',').map { it.trim().removePrefix("#") }.filter { HEX6.matches(it) }.take(OWN_MAX)
        .ifEmpty { WCOL_DEFAULT.split(',') }
    /** The own wall's colours as ARGB ints. */
    fun ownColors(o: JSONObject?): IntArray = wcol(o?.optString("wcol").orEmpty()).map { (0xFF000000 or it.toLong(16)).toInt() }.toIntArray()
    /** Bing's pictures, downloaded by TV Home: the last 8 days kept, newest first, turned through by the rotation before the next of the set. */
    const val BING = "bing"
    /** A web page as the background and the screensaver (TV Home shows it in a WebView; [wurl]). */
    const val WEB = "web"
    const val WURL_MAX = 500
    val WMODES = listOf("live", "snap")
    val WFITS = listOf("cover", "contain")
    const val SHADE_DEFAULT = 50
    const val SHADE_STEP = 5
    val WSNAPS = listOf(1, 5, 15, 30, 60)
    const val WSNAP_DEFAULT = 5
    private val WURL = Regex("https?://[^\\s/?#]+\\S*", RegexOption.IGNORE_CASE)
    /** [s] trimmed when it is an http(s) address with a host and fits [WURL_MAX]; else "". */
    fun wurl(s: String): String = s.trim().takeIf { it.length <= WURL_MAX && WURL.matches(it) } ?: ""
    const val PHOTO_MAX = 10
    /** The person's photos, one file each: "photo" is the first and the only one older TV Homes know. */
    val PHOTOS = listOf("photo") + (2..PHOTO_MAX).map { "photo$it" }
    /** In the order the pickers list them: Bing, the web page and the person's photos first (the ones people come to change), then the scenes, the plain colours last. */
    val WALLS = listOf(BING, WEB) + PHOTOS + ART + OWN + PLAIN
    /** A video or GIF of the media library: "vid:" + 8 hex (a theme refers to it by this id, and so does "wall"). */
    const val VIDEO_PREFIX = "vid:"
    private val VIDEO = Regex("vid:[0-9a-f]{8}")
    fun isVideo(id: String) = VIDEO.matches(id)
    /** What the library takes: the biggest file as sent, how many it keeps, and the types ([VIDEO_EXT]). */
    const val VIDEO_MAX_BYTES = 200L * 1024 * 1024
    const val VIDEO_LIB_MAX = 12
    val VIDEO_EXT = listOf("mp4", "webm", "gif")
    /** How a GIF or a heavy video is made light, for the NEXT uploads (originals are not kept: see dev/decisions/tv-home.md "TV Home: video"): "" the source's own frame rate / the screen's own size, else frames a second / the short side in px. */
    val VFPS = listOf("", "60", "30", "24", "15")
    val VRES = listOf("", "1080", "720", "480")
    /** Where a client puts a file before TV Home takes it in (HomeReceiver "addvideo" names it); the shell may write there. */
    const val VIDEO_STAGING = "/sdcard/Android/data/$PKG/files/videos/in"
    /** A wall id this config knows: a gradient, a scene, Bing, the page, a photo slot or a library video. */
    fun knownWall(id: String) = id in WALLS || isVideo(id)
    const val WALLS_MAX = 40
    val EVERY = listOf("off", "30m", "1h", "6h", "day", "show")
    /** Where a client puts the first photo (the shell may write there; the app reads it with no permission). */
    const val PHOTO = "/sdcard/Android/data/$PKG/files/wall.jpg"
    /** The file of photo [id] ("photo" → wall.jpg, "photo3" → wall3.jpg), in TV Home's external files folder. */
    fun photoName(id: String) = "wall" + id.removePrefix("photo") + ".jpg"
    fun photoPath(id: String) = PHOTO.substringBeforeLast('/') + "/" + photoName(id)
    fun isPhoto(id: String) = id in PHOTOS
    /** The length of an interval in ms ("day" and "show" are not lengths: 0). */
    fun everyMs(e: String) = when (e) { "30m" -> 30 * 60_000L; "1h" -> 3_600_000L; "6h" -> 6 * 3_600_000L; else -> 0L }
    private const val DAY = 86_400_000L

    /** Whether it is time for the next background: [last] is when it last changed, [off] the time zone's offset (ms) for "day". */
    fun due(every: String, last: Long, now: Long, off: Long): Boolean = when (every) {
        "30m", "1h", "6h" -> now - last >= everyMs(every) || now < last          // a clock set back must not freeze it
        "day" -> Math.floorDiv(now + off, DAY) != Math.floorDiv(last + off, DAY)
        else -> false
    }
    /**
     * How full a bar or ring is, as a colour word: amber from [WARN_AT] %, red from [BAD_AT] % ("ok" / "warn" / "bad"). The ONE place
     * the thresholds live: TV Home's System widget (RAM, storage) and TV Setup's RAM live widget both say it through here; TV Setup's
     * memory notice (Rules, default 90) is a different, chosen number. The web page shows no such level (it has no bars).
     */
    const val WARN_AT = 70
    const val BAD_AT = 85
    fun level(pct: Int): String = if (pct >= BAD_AT) "bad" else if (pct >= WARN_AT) "warn" else "ok"
    /** How long until [due] turns true (for the one timer set while Home is on screen); null when time does not decide it. */
    fun wait(every: String, last: Long, now: Long, off: Long): Long? = when (every) {
        "30m", "1h", "6h" -> (last + everyMs(every) - now).coerceIn(0L, everyMs(every))
        "day" -> (Math.floorDiv(now + off, DAY) + 1) * DAY - off - now
        else -> null
    }
    /** The one after [cur] in [walls] that [usable] allows (a photo gone, Bing not yet fetched: skipped); null when there is none. */
    fun next(walls: List<String>, cur: String, usable: (String) -> Boolean): String? {
        if (walls.isEmpty()) return null
        val at = walls.indexOf(cur)
        for (i in 1..walls.size) {
            val w = walls[Math.floorMod(at + i, walls.size)]
            if (w != cur && usable(w)) return w
        }
        return null
    }
    val WIDGETS = listOf("system", "usage", "calendar", "lunar", "timer", "weather", "air", "recent", "adblock", "clock", "market", "news", "prayer") + HomeQr.KEYS
    /**
     * One option of a widget, the single source of what the settings dialog offers (TV Home's Customize and the page render it, nobody
     * hardcodes a widget's options): [type] "choice" (one of [values]) or "toggle" ("off" / "on", drawn as a switch). [default] is what a
     * missing value means. [store] is where the value is kept: "wopt" ({"clock": {"face": "minimal"}}, only values that are not the
     * default), or "wsize" for the size, which the layout ([pack], the themes, older clients) has always read there.
     * The words go by id: the option's `home.o_<id>`, a value's `home.ov_<value>` (strings.xml / i18n: dots become underscores).
     */
    class Opt(val id: String, val type: String, val values: List<String>, val default: String, val store: String = "wopt")
    private val SIZES = listOf("big", "small")
    val TONAL = setOf("system", "weather", "lunar", "air", "calendar", "usage", "timer", "market", "news", "recent", "adblock", "prayer") + HomeQr.KEYS   // every widget but the clock has the "tonal" look (opaque warm card, app/DESIGN-TV.md "Tonal cards")
    // "solid": an opaque dark widget, the photo hidden under it; "none": no widget at all, the words on the wallpaper with a shadow
    // (a person whose own photo is the point). The clock has no tonal form (its face and theme are its look)
    val LOOKS = listOf("glass", "tonal", "solid", "none")
    private val BARE = listOf("glass", "solid", "none")
    /** What a widget really implements (the drawing code in app/tvhome widgets): the options below are all it offers, every combination is drawn. */
    fun options(k: String): List<Opt> {
        val size = Opt("size", "choice", SIZES, defaultSize(k), "wsize")
        return when {
            k == "clock" -> listOf(size, Opt("face", "choice", listOf("classic", "minimal", "roman", "modern", "digital"), "classic"), Opt("theme", "choice", listOf("dark", "light"), "dark"), Opt("look", "choice", BARE, "glass"))
            k == "market" -> listOf(size, Opt("look", "choice", LOOKS, "glass"), Opt("layout", "choice", listOf("card", "ticker"), "card"), Opt("show", "choice", listOf("all", "gold", "rates"), "all"), Opt("btc", "toggle", emptyList(), "off"))
            k == "calendar" -> listOf(size, Opt("look", "choice", LOOKS, "glass"), Opt("layout", "choice", listOf("month", "date"), "month"))
            k == "news" -> listOf(size, Opt("look", "choice", LOOKS, "glass"), Opt("src", "choice", listOf("auto", "mine", "both"), "auto"))
            k == "prayer" -> listOf(size, Opt("look", "choice", LOOKS, "glass"), Opt("method", "choice", listOf("auto") + PRAYER_METHODS, "auto"),
                Opt("asr", "choice", listOf("standard", "hanafi"), "standard"), Opt("hijri", "choice", listOf("hijri_m1", "hijri_0", "hijri_p1"), "hijri_0"))
            k in TONAL -> listOf(size, Opt("look", "choice", LOOKS, "glass"))
            k in WIDGETS -> listOf(size)
            else -> emptyList()
        }
    }
    /** The prayer widget's calculation methods ("auto": by the TV's time zone), the same ids as `Prayer.METHODS` in app/tvhome (a test holds them equal). */
    val PRAYER_METHODS = listOf("mwl", "isna", "egypt", "ummalqura", "karachi", "jakim", "kemenag", "singapore", "turkey", "tehran")
    private fun optValues(o: Opt) = if (o.type == "toggle") listOf("off", "on") else o.values
    /** The schema as the clients get it ("wschema" in what TV Home reports): {widget: [{id, type, store, default, label, values: [{id, w}]}]}. */
    fun schema(sdk: Int = ADBLOCK_MIN_SDK): JSONObject = JSONObject().also { out ->
        for (k in WIDGETS) if (k != "adblock" || sdk >= ADBLOCK_MIN_SDK) out.put(k, JSONArray(options(k).map { o ->
            JSONObject().put("id", o.id).put("type", o.type).put("store", o.store).put("default", o.default).put("label", "home.o_${o.id}")
                .put("values", JSONArray(optValues(o).map { JSONObject().put("id", it).put("w", "home.ov_$it") }))
        }))
    }
    /** The value of option [id] of widget [k] in a cleaned [conf]: what was chosen, else the default ("" for a widget or option that does not exist). */
    fun optValue(conf: JSONObject, k: String, id: String): String {
        val o = options(k).firstOrNull { it.id == id } ?: return ""
        val v = if (o.store == "wsize") conf.optJSONObject("wsize")?.optString(k) else conf.optJSONObject("wopt")?.optJSONObject(k)?.optString(id)
        return v?.takeIf { it in optValues(o) } ?: o.default
    }
    /** The old "wstyle" of a widget as the options that give the same picture (the clock's "light" was a light classic; "minimal" was forced dark). */
    private fun fromStyle(k: String, s: String): Map<String, String> = when (k) {
        "clock" -> when (s) { "light" -> mapOf("face" to "classic", "theme" to "light"); "minimal", "roman" -> mapOf("face" to s, "theme" to "dark"); "classic" -> mapOf("face" to "classic", "theme" to "dark"); else -> emptyMap() }
        else -> mapOf("look" to s)
    }
    /**
     * "wopt" of [o] cleaned against the schema: unknown widgets, options and values dropped, a value equal to the default not kept. A widget
     * with no entry takes its old "wstyle" ([fromStyle]) when there is one: that is the migration, nothing else reads "wstyle" now.
     */
    fun cleanWopt(o: JSONObject): JSONObject {
        val out = JSONObject(); val given = o.optJSONObject("wopt"); val old = o.optJSONObject("wstyle")
        for (k in WIDGETS) {
            val src = given?.optJSONObject(k)
            val migrated = if (src == null) old?.optString(k)?.takeIf { it.isNotEmpty() }?.let { fromStyle(k, it) } else null
            val e = JSONObject()
            for (op in options(k)) {
                if (op.store != "wopt") continue
                val v = (src?.optString(op.id) ?: migrated?.get(op.id))?.takeIf { it in optValues(op) && it != op.default } ?: continue
                e.put(op.id, v)
            }
            if (e.length() > 0) out.put(k, e)
        }
        return out
    }
    /** [change] (a "wopt" delta, or an older client's "wstyle") on top of [now]'s "wopt", per widget and option: a client sends what it changed. */
    private fun woptMerge(now: JSONObject?, change: JSONObject): JSONObject {
        val out = JSONObject(now?.toString() ?: "{}")
        val delta = change.optJSONObject("wopt"); val old = change.optJSONObject("wstyle")
        for (k in WIDGETS) {
            val d = JSONObject()
            old?.optString(k)?.takeIf { it.isNotEmpty() }?.let { s -> fromStyle(k, s).forEach { (a, b) -> d.put(a, b) } }   // an old look names the whole face
            delta?.optJSONObject(k)?.let { g -> for (x in g.keys()) d.put(x, g.get(x)) }
            if (d.length() == 0) continue
            val e = out.optJSONObject(k) ?: JSONObject()
            for (x in d.keys()) e.put(x, d.get(x))
            out.put(k, e)
        }
        return out
    }
    /** A widget's size when nobody chose: the four large widgets are big, the small pills (lunar, timer, clock, and the note) small. */
    fun defaultSize(k: String) = if (k in BIG_KINDS) "big" else "small"
    private val BIG_KINDS = setOf("system", "usage", "calendar", "weather", "air", "market", "news", "prayer") + HomeQr.KEYS     // a code is read from the sofa: big until chosen otherwise
    /**
     * Widths as weights (a column's share of the row): a big widget, the calendar (seven day columns) a little more, a small column
     * (stacked pills, or one standing) a little less. The row holds [BUDGET] at most: three big widgets and a small column fit; a
     * fourth big widget is 200 dp wide at 1920x1080 and the weather there cuts its words to "…" (measured on a TV), so it waits. [FLOOR]: a few
     * widgets do not stretch across the whole row.
     */
    const val W_BIG = 100; const val W_CAL = 120; const val W_SMALL = 90; const val BUDGET = 415; const val FLOOR = 190
    fun weight(k: String, big: Boolean) = if (!big) W_SMALL else if (k == "calendar") W_CAL else W_BIG
    /** The widgets of one column, top to bottom, and its weight. */
    class Col(val keys: List<String>, val weight: Int)
    /** The columns that fit, left to right, and the widgets that wait (the pinned note, "note", never does). */
    class Packed(val cols: List<Col>, val waiting: List<String>)
    /**
     * The one rule of the layout (js/home.js `homePack` does the same, tests/home_pack_cases.json holds the cases both read): walk
     * the widgets in order, a [big] widget is a column, two small widgets in a row stack in one column, a small widget alone is a narrow
     * column of its own; stop at the first column that would pass [BUDGET], the rest wait. The pinned note ([note]) is the last
     * small widget and keeps its place: the widgets before it give way, from the end.
     */
    fun pack(keys: List<String>, big: Set<String>, note: Boolean = false): Packed {
        fun run(ks: List<String>): Packed {
            val cols = ArrayList<Col>(); var sum = 0; var i = 0
            while (i < ks.size) {
                val k = ks[i]; val isBig = k in big
                val group = if (!isBig && ks.getOrNull(i + 1)?.let { it !in big } == true) listOf(k, ks[i + 1]) else listOf(k)
                val w = weight(k, isBig)
                if (sum + w > BUDGET) break
                cols += Col(group, w); sum += w; i += group.size
            }
            return Packed(cols, ks.drop(i))
        }
        if (!note) return run(keys)
        for (n in keys.size downTo 0) {
            val p = run(keys.take(n) + "note")
            if (p.waiting.isEmpty()) return Packed(p.cols, keys.drop(n))
        }
        return Packed(emptyList(), keys)                                // not reached: the note alone always fits
    }
    /** The sizes in [conf] as the set of big widgets. */
    fun bigs(conf: JSONObject): Set<String> {
        val s = conf.optJSONObject("wsize")
        return WIDGETS.filter { (s?.optString(it)?.takeIf { v -> v == "big" || v == "small" } ?: defaultSize(it)) == "big" }.toSet()
    }
    /** Remote keys a person may give an app. Colour keys: no app answers them in general. Numbers: only on TV Home's own screen. */
    val KEY_COLOURS = listOf(183, 184, 185, 186)                        // PROG_RED, GREEN, YELLOW, BLUE
    val KEY_NUMBERS = (7..16).toList()                                  // KEYCODE_0 .. KEYCODE_9
    /** The keys the stock launcher answered (app/tvsetup KeyFix.RULES; a test there holds the two lists equal): free once their handler is gone; the app buttons (190, 191, 195: KeyFix.APP_KEYS) even while it works. */
    val KEY_DEAD = listOf(170, 178, 190, 191, 195, 192, 284, 176)
    fun keyAllowed(code: Int) = code in KEY_COLOURS || code in KEY_NUMBERS || code in KEY_DEAD
    /** "keyapps" as code to package, the codes allowed and the packages real names. */
    fun keyApps(o: JSONObject?): Map<Int, String> {
        val out = LinkedHashMap<Int, String>()
        if (o != null) for (k in o.keys()) {
            val code = k.toIntOrNull() ?: continue
            val p = o.optString(k)
            if (keyAllowed(code) && Target.valid(p)) out[code] = p       // any [Target]: an app, a shortcut, a live widget
        }
        return out
    }
    const val NOTE_MAX = 140
    const val NAME_MAX = 30
    const val NAMES_MAX = 100
    const val PLACE_MAX = 60
    const val GNAME_MAX = 24
    const val GTEXT_MAX = 60
    val DFACES = listOf("big", "modern", "digital")
    val GSTYLES = listOf("time", "name", "date", "lunar", "own")
    /** What "own" text may ask for, written {date}: the clients offer them as chips; an unknown {word} stays as typed. */
    val TOKENS = listOf("greet", "date", "weekday", "day", "month", "year", "lunar", "canchi")
    val HEAD_SIZES = listOf("s", "m", "l")
    val HEAD_COLORS = listOf("white", "dim", "red")
    /** The type of each header line: the keys and their defaults (the first line small and dim, the second larger and white, as before). */
    val HEAD_TYPE = mapOf("gsize" to "s", "gcolor" to "dim", "dsize" to "m", "dcolor" to "white")
    val HEAD_FLAGS = listOf("gbold", "gitalic", "gcaps", "dbold", "ditalic", "dcaps")

    /** The greeting line: [timed] is the time of day's word in the TV's language ("Good evening"). */
    fun greeting(o: JSONObject, timed: String): String = headLine(o, "g", mapOf("greet" to timed))

    /**
     * One header line, [which] "g" (the greeting, first) or "d" (the date, second), from [t]: the [TOKENS]' values now, plus
     * "lunarLine" (the lunar date as a phrase). A style with nothing to show falls back to the line's own default.
     */
    fun headLine(o: JSONObject, which: String, t: Map<String, String>): String {
        val base = if (which == "g") t["greet"].orEmpty() else t["date"].orEmpty()
        return when (o.optString(which + "style").ifEmpty { if (which == "g") "time" else "date" }) {
            "time" -> t["greet"].orEmpty()
            "name" -> o.optString("gname").takeIf { it.isNotEmpty() }?.let { t["greet"].orEmpty() + ", " + it } ?: t["greet"].orEmpty()
            "date" -> t["date"].orEmpty()
            "lunar" -> t["lunarLine"] ?: base
            "own" -> fill(o.optString(which + "text"), t).ifEmpty { base }
            else -> base
        }
    }
    fun fill(s: String, t: Map<String, String>) = Regex("\\{([a-z]+)\\}").replace(s) { m -> t[m.groupValues[1]] ?: m.value }
    private fun line(s: String, max: Int) = s.replace(Regex("\\s+"), " ").trim().take(max)
    /** The person's own RSS links for the news widget: at most [FEEDS_MAX], each an http(s) address of at most [FEED_URL_MAX]. */
    const val FEEDS_MAX = 5
    const val FEED_URL_MAX = 300
    fun feeds(a: JSONArray?): List<String> = strs(a).map { it.trim() }.filter { it.length <= FEED_URL_MAX && WURL.matches(it) }.distinct().take(FEEDS_MAX)
    /** TV Setup's answer to TV Home about app use (its HomeInfo provider): `call(USAGE, "usage")` → Bundle "json". */
    const val USAGE = "content://dev.tvremote.setup.home"
    /** The first TV Setup with the Telegram bot: TV Home's widget names it, and SetupNeed.MIN "telegram" is this, so both say one number. */
    const val TELEGRAM_SETUP = "0.21.1"
    /** The languages TV Home has words in (res/values-<lang>; English is res/values), "" = the TV's. */
    val MOTIONS = listOf("calm", "full")
    val LANGS = listOf("") + dev.tvremote.i18n.Langs.CODES
    private val PKG_NAME = Regex("[A-Za-z][\\w]*(\\.[\\w]+)+")
    private val LIVE = Regex("tile:[A-Za-z][\\w]*(\\.[\\w]+)+/[a-z][a-z0-9_]{0,15}")
    const val TILES_MAX = 24                                          // live widgets and shortcuts together: the picker adds both; "tiles" and "tile:" are stored on TVs and sent between apps: kept
    /** [k] is a live widget's key ("tile:dev.tvremote.setup/ram"), not an app's package name. */
    fun isLive(k: String) = k.length <= 100 && LIVE.matches(k)
    private val FOLDER = Regex("folder:[a-z0-9]{1,8}")
    const val FOLDERS_MAX = 12
    const val FOLDER_APPS_MAX = 40
    /** [k] is a folder's key ("folder:ab12cd"): a grid tile that holds apps ([Folders]); "folder:" is stored on TVs and sent between apps: kept. */
    fun isFolder(k: String) = FOLDER.matches(k)
    fun folderKey(id: String) = "folder:$id"
    fun folderId(k: String) = k.removePrefix("folder:")
    fun liveKey(provider: String, id: String) = "tile:$provider/$id"
    /** The provider's package of a live widget's key, and its id there. */
    fun liveProvider(k: String) = k.removePrefix("tile:").substringBefore('/')
    fun liveId(k: String) = k.substringAfter('/')

    // ---- themes: the look as a set of existing settings, switched at once ----
    const val THEMES_MAX = 12
    const val THEME_NAME_MAX = 24
    /** The settings a theme holds and an apply switches . */
    val THEME_KEYS = listOf("wall", "walls", "wurl", "wcol", "wdir", "wmode", "wsnap", "wkeep", "wevery", "shade", "wfit",
        "widgets", "wsize", "wopt", "cols", "rows", "focus", "motion", "anim", "clock24", "greet")
    private val THEME_ID = Regex("t[0-9]{1,4}")
    /** What the first screen draws that the person can take off (not in a theme: a look does not hide the way to the settings). */
    /** Where the Play Next items sit on the grid, first the default (the user, 2026-10-26: a row wedged inside their own apps annoys). */
    val WATCH_AT = listOf("bottom", "top", "under1", "tiles")

    val SHOWS = listOf("showClock", "showDate", "showNet", "showGear", "showAdd", "showSuggest", "showWatch")

    /** [c] (a cleaned config) cut down to a theme's settings, the QR widgets left out: their codes (and a Wi-Fi password) are not a look. */
    fun pickTheme(c: JSONObject): JSONObject {
        val out = JSONObject()
        for (k in THEME_KEYS) if (c.has(k) || (k == "wopt" && c.has("wstyle"))) out.put(k, when (k) {
            "widgets" -> JSONArray(strs(c.optJSONArray(k)).filter { it !in HomeQr.KEYS })
            "wopt" -> cleanWopt(c)
            "wsize" -> JSONObject().also { m -> c.optJSONObject(k)?.let { g -> for (x in g.keys()) if (x !in HomeQr.KEYS) m.put(x, g.get(x)) } }
            else -> c.get(k)
        })
        return out
    }

    /**
     * The ready-made themes come from the site's signed list (docs/home/themes.json, [parseReady]); TV Home sets [ready] from the list it
     * kept or the copy packed in the APK, and again when the site's is newer (`Themes.kt` there). Each is a whole look (every key of
     * [THEME_KEYS], [clean] fills the rest), so applying one never leaves a bit of the one before.
     */
    class Ready(val id: String, val names: Map<String, String>, val conf: JSONObject, val live: List<String> = emptyList(), val look: String = "", val thumb: String = "", val by: String = "", val community: Boolean = false) {
        /** The name in [lang], else English, else the id. */
        fun name(lang: String): String = names[lang] ?: names["en"] ?: id
    }
    @Volatile var ready: List<Ready> = emptyList()
    val BUILTIN: List<Pair<String, JSONObject>> get() = ready.map { it.id to it.conf }
    const val READY_MAX = 120                                 // the project's own (~20) and the community's, which the store adds as they are approved
    const val READY_LIVE_MAX = 4
    private val READY_ID = Regex("[a-z][a-z0-9_]{0,15}")

    /** [a] newer than [b] ("0.8.1" vs "0.8.0"); a missing part is 0. */
    fun newer(a: String, b: String): Boolean {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }; val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) { val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }; if (d != 0) return d > 0 }
        return false
    }

    /**
     * The themes of a signed list ([text] is checked by the caller): {"v":1,"themes":[{id, names:{vi,en}, conf, since?, community?, by?}]}. Each conf goes
     * through [clean] and [pickTheme], so a list sets nothing a theme could not (a "wurl" page is allowed here: that is why it is
     * signed). A theme with a `since` newer than [have] (this TV Home's version) waits for the update; an empty list is no list.
     */
    fun parseReady(text: String, have: String): List<Ready> = try {
        val o = JSONObject(text)
        val a = o.optJSONArray("themes")
        val seen = HashSet<String>()
        val out = ArrayList<Ready>()
        if (o.optInt("v") == 1 && a != null) for (i in 0 until a.length()) {
            val t = a.optJSONObject(i) ?: continue
            val id = t.optString("id"); val conf = t.optJSONObject("conf")
            if (!READY_ID.matches(id) || conf == null || !seen.add(id)) continue
            if (t.optString("since").let { it.isNotEmpty() && newer(it, have) }) continue
            val n = t.optJSONObject("names")
            val names = if (n == null) emptyMap() else n.keys().asSequence().filter { LANGS.contains(it) }.associateWith { themeName(n.optString(it)) }.filterValues { it.isNotEmpty() }
            // "tiles": live widgets the theme adds ([doTheme]); "look": a drawn wall or gradient for its picture in the list when its own wall has none (Bing, a page, a video); "thumb": a small https picture of that background, drawn under the preview of the layout
            out += Ready(id, names, pickTheme(clean(conf)), strs(t.optJSONArray("tiles")).filter { isLive(it) }.distinct().take(READY_LIVE_MAX),
                t.optString("look").takeIf { it in ART || it in PLAIN } ?: "", thumb(t.optString("thumb")),
                by = themeName(t.optString("by")), community = t.optBoolean("community") && t.optString("by").isNotBlank())      // "by": the sender's name, community themes only (the store's signed entries)
            if (out.size >= READY_MAX) break
        }
        out
    } catch (e: org.json.JSONException) { emptyList() }

    const val THUMB_MAX = 300
    private val THUMB_URL = Regex("https://[^\\s\"'<>]{4,${THUMB_MAX - 8}}")
    /** A theme's "thumb" (a small picture of its photo / page / video background for its preview): an https address of sane length, else none. */
    fun thumb(s: String): String = s.trim().takeIf { THUMB_URL.matches(it) } ?: ""

    private fun customThemes(o: JSONObject?): List<JSONObject> {
        val a = o?.optJSONArray("themes") ?: return emptyList()
        val seen = HashSet<String>(); val out = ArrayList<JSONObject>()
        for (i in 0 until a.length()) {
            val t = a.optJSONObject(i) ?: continue
            val id = t.optString("id"); val name = themeName(t.optString("name")); val conf = t.optJSONObject("conf")
            if (!THEME_ID.matches(id) || name.isEmpty() || conf == null || !seen.add(id)) continue
            out += JSONObject().put("id", id).put("name", name).put("conf", pickTheme(conf))   // picked, not cleaned again: an apply cleans it
            if (out.size >= THEMES_MAX) break
        }
        return out
    }
    fun themeName(s: String) = s.replace(Regex("\\s+"), " ").trim().take(THEME_NAME_MAX).trim()

    /** The theme [id] (the person's or a ready-made one) as its settings, or null. */
    fun theme(c: JSONObject, id: String): JSONObject? =
        if (id == BEFORE) before(c) else customThemes(c).firstOrNull { it.optString("id") == id }?.optJSONObject("conf") ?: BUILTIN.firstOrNull { it.first == id }?.second

    /** The id of the one automatic slot: the look that was on before the last theme was applied, when it matched no saved theme. */
    const val BEFORE = "before"
    /** The look kept in the [BEFORE] slot ("before" in the config), or null. */
    private fun before(c: JSONObject?): JSONObject? = c?.optJSONObject(BEFORE)?.let { pickTheme(it) }?.takeIf { it.length() > 0 }

    /** [v] as text with the keys of every object in order: two looks are the same whatever order a JSON library kept their keys in. */
    private fun canon(v: Any?): String = when (v) {
        is JSONObject -> v.keys().asSequence().sorted().joinToString(",", "{", "}") { k -> k + ":" + canon(v.get(k)) }
        is JSONArray -> (0 until v.length()).joinToString(",", "[", "]") { canon(v.get(it)) }
        is Number -> if (v.toDouble() == Math.rint(v.toDouble())) v.toLong().toString() else v.toDouble().toString()
        else -> v.toString()
    }

    /** The id of the theme whose look is on now (the person's first), or "". */
    fun themeNow(c: JSONObject): String {
        val now = canon(pickTheme(clean(c)))
        return customThemes(c).firstOrNull { canon(it.optJSONObject("conf")) == now }?.optString("id")
            ?: before(c)?.takeIf { canon(it) == now }?.let { BEFORE }
            ?: BUILTIN.firstOrNull { canon(it.second) == now }?.first ?: ""
    }

    /** Whether a saved theme keeps [wall] (a photo, a video of the library) alive: Videos.refs and the photos' delete ask this. */
    fun themeUses(c: JSONObject, wall: String): Boolean = (customThemes(c).map { it.optJSONObject("conf") } + before(c)).any { conf ->
        if (conf == null) return@any false
        conf.optString("wall") == wall || strs(conf.optJSONArray("walls")).contains(wall)
    }

    /** Only the three words of the "theme" field, each a short string (what [forward] lets through). */
    fun themeOp(o: JSONObject?): JSONObject? {
        if (o == null) return null
        val out = JSONObject()
        o.optString("apply").takeIf { it.length in 1..12 }?.let { out.put("apply", it) }
        o.optString("save").let { themeName(it) }.takeIf { it.isNotEmpty() }?.let { out.put("save", it) }
        o.optString("del").takeIf { it.length in 1..12 }?.let { out.put("del", it) }
        return if (out.length() == 0) null else out
    }

    /** [n] (cleaned) with the "theme" field [op] done: apply (what is gone, [usable], is passed over), save the look now, delete. */
    private fun doTheme(n: JSONObject, op: JSONObject, usable: (String) -> Boolean, liveOk: (String) -> Boolean): JSONObject {
        val mine = customThemes(n).toMutableList()
        op.optString("del").takeIf { it.isNotEmpty() }?.let { id -> if (id == BEFORE) n.remove(BEFORE) else mine.removeAll { it.optString("id") == id } }
        op.optString("save").takeIf { it.isNotEmpty() }?.let { name ->
            val look = pickTheme(clean(n))
            val same = mine.indexOfFirst { it.optString("name").equals(name, ignoreCase = true) }      // the same name: that theme is saved over
            if (same >= 0) mine[same] = JSONObject().put("id", mine[same].optString("id")).put("name", name).put("conf", look)
            else if (mine.size < THEMES_MAX) {
                val id = "t" + ((mine.mapNotNull { it.optString("id").drop(1).toIntOrNull() }.maxOrNull() ?: 0) + 1)
                mine += JSONObject().put("id", id).put("name", name).put("conf", look)
            }
        }
        n.put("themes", JSONArray(mine))
        op.optString("apply").takeIf { it.isNotEmpty() }?.let { id ->
            val conf = theme(n, id) ?: return n
            val now = clean(n)
            // a look saved nowhere would be lost by this switch: kept in the one slot (replaced each time), so there is a way back
            if (id != themeNow(n) && themeNow(n).isEmpty()) n.put(BEFORE, pickTheme(now))
            for (k in conf.keys()) n.put(k, conf.get(k))
            n.put("wopt", cleanWopt(conf)).remove("wstyle")                  // a theme saved before the options has "wstyle": the same looks, as options
            // the QR widgets stay as they are, where they are in the order
            val qr = strs(now.optJSONArray("widgets")).filter { it in HomeQr.KEYS }
            n.put("widgets", JSONArray(strs(conf.optJSONArray("widgets")) + qr))
            n.put("wsize", JSONObject(conf.optJSONObject("wsize")?.toString() ?: "{}").also { m -> now.optJSONObject("wsize")?.let { g -> for (x in g.keys()) if (x in HomeQr.KEYS) m.put(x, g.get(x)) } })
            // a picture or video that is gone is skipped, as the rotation skips it; nothing usable at all: Night
            val walls = strs(conf.optJSONArray("walls")).filter(usable)
            val wall = conf.optString("wall").takeIf(usable) ?: walls.firstOrNull() ?: "night"
            n.put("walls", JSONArray(walls.ifEmpty { listOf(wall) })).put("wall", wall)
            // a ready-made theme's live widgets: put FIRST in the grid and kept beside the person's own (never replaced); one whose provider is not here is skipped
            ready.firstOrNull { it.id == id }?.live?.filter(liveOk)?.takeIf { it.isNotEmpty() }?.let { add ->
                n.put("tiles", JSONArray((add + strs(n.optJSONArray("tiles"))).distinct().take(TILES_MAX)))
                n.put("order", JSONArray((add + strs(n.optJSONArray("order"))).distinct().take(ORDER_MAX)))
            }
        }
        return n
    }

    /** [o] made safe: unknown fields dropped, lists of real package names without repeats, every value in range. */
    fun clean(o: JSONObject): JSONObject {
        val qrs = HomeQr.widgets(o.optJSONObject("qrs"))
        val widgets = o.optJSONArray("widgets")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty().filter { it in WIDGETS && (it !in HomeQr.KEYS || qrs.has(it)) }.distinct()
        val dock = pkgs(o.optJSONArray("dock"), DOCK_MAX)
        val wurl = wurl(o.optString("wurl"))
        val wall = o.optString("wall").takeIf { knownWall(it) && (it != WEB || wurl.isNotEmpty()) } ?: "night"
        // the page is not a stop of the rotation (it never ends, and a turn away from it would not come back): it is the whole set or not in it
        var walls = if (wall == WEB) listOf(WEB)
            else o.optJSONArray("walls")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty().filter { knownWall(it) && it != WEB }.distinct().take(WALLS_MAX)
        if (walls.isEmpty()) walls = listOf(wall)
        val tilesAsked = strs(o.optJSONArray("tiles")).filter { isLive(it) || Target.isPin(it) || isFolder(it) }.distinct().take(TILES_MAX)
        val folders = folders(o.optJSONObject("folders"), dock, tilesAsked.filter { isFolder(it) }.map { folderId(it) }.toSet())
        val folderKeys = folders.keys().asSequence().map { folderKey(it) }.toSet()
        val tiles = tilesAsked.filter { !isFolder(it) || it in folderKeys }
        return JSONObject()
            .put("dock", JSONArray(dock))
            .put("hidden", JSONArray(pkgs(o.optJSONArray("hidden"), HIDDEN_MAX).filter { it !in dock }))
            .put("order", JSONArray(gridKeys(o.optJSONArray("order"), ORDER_MAX).filter { !isFolder(it) || it in folderKeys }))
            .put("wall", if (wall in walls) wall else walls[0])           // what shows is always one of the set
            .put("walls", JSONArray(walls))
            .put("wurl", wurl)
            .put("wcol", wcol(o.optString("wcol")).joinToString(","))
            .put("wdir", o.optString("wdir").takeIf { it in WDIRS } ?: WDIRS[0])
            .put("wmode", o.optString("wmode").takeIf { it in WMODES } ?: "live")
            .put("wsnap", o.optInt("wsnap", WSNAP_DEFAULT).takeIf { it in WSNAPS } ?: WSNAP_DEFAULT)
            .put("wkeep", o.optBoolean("wkeep", false))
            .put("wevery", o.optString("wevery").takeIf { it in EVERY } ?: "off")
            .put("photo", o.optLong("photo", 0).coerceAtLeast(0))
            .put("wfit", o.optString("wfit").takeIf { it in WFITS } ?: "cover")
            .put("shade", o.optInt("shade", SHADE_DEFAULT).coerceIn(0, 100) / SHADE_STEP * SHADE_STEP)
            .put("vfps", o.optString("vfps").takeIf { it in VFPS } ?: "")
            .put("vres", o.optString("vres").takeIf { it in VRES } ?: "")
            .put("clock24", o.optBoolean("clock24", true))
            .put("lang", o.optString("lang").takeIf { it in LANGS } ?: "")
            .put("greet", o.optBoolean("greet", true))
            .put("gstyle", o.optString("gstyle").takeIf { it in GSTYLES } ?: GSTYLES[0])
            .put("gname", line(o.optString("gname"), GNAME_MAX))
            .put("gtext", line(o.optString("gtext"), GTEXT_MAX))
            .put("dstyle", o.optString("dstyle").takeIf { it in GSTYLES && it != "name" } ?: "date")
            .put("dtext", line(o.optString("dtext"), GTEXT_MAX))
            .also { n -> for ((k, d) in HEAD_TYPE) n.put(k, o.optString(k).takeIf { it in (if (k.endsWith("size")) HEAD_SIZES else HEAD_COLORS) } ?: d) }
            .also { n -> for (k in HEAD_FLAGS) n.put(k, o.optBoolean(k, false)) }
            .also { n -> for (k in SHOWS) n.put(k, o.optBoolean(k, true)) }
            .put("infold", o.optBoolean("infold", false))
            .put("watchAt", o.optString("watchAt").takeIf { it in WATCH_AT } ?: WATCH_AT[0])
            .put("anim", o.optBoolean("anim", true))
            .put("shift", o.optBoolean("shift", false))               // burn-in care: Home moves a few px every minute (ui/PixelShift)
            .put("motion", o.optString("motion").takeIf { it in MOTIONS } ?: "")
            .put("focus", o.optString("focus").takeIf { it == "ring" } ?: "glass")
            .put("cols", o.optInt("cols", COLS_DEFAULT).coerceIn(COLS_MIN, COLS_MAX))
            .put("rows", rows(o.optDouble("rows", 0.0)))
            .put("dream", o.optBoolean("dream", false))
            .put("dface", o.optString("dface").takeIf { it in DFACES } ?: "big")
            .put("note", o.optString("note").replace(Regex("\\s+"), " ").trim().take(NOTE_MAX))
            .put("place", o.optString("place").replace(Regex("\\s+"), " ").trim().take(PLACE_MAX))
            .put("feeds", JSONArray(feeds(o.optJSONArray("feeds"))))
            .put("keyon", o.optBoolean("keyon", false))
            .put("keyapps", JSONObject().also { m -> keyApps(o.optJSONObject("keyapps")).toSortedMap().forEach { (k, v) -> m.put(k.toString(), v) } })
            .put("autoon", o.optBoolean("autoon", false))
            .put("autoapp", Target.clean(o.optString("autoapp")))
            .put("autoall", o.optBoolean("autoall", false))
            .put("tiles", JSONArray(tiles))
            .put("folders", folders)
            .put("qrs", qrs)
            .put("names", names(o.optJSONObject("names")))
            .put("themes", JSONArray(customThemes(o)))
            .also { out -> before(o)?.let { out.put(BEFORE, it) } }
            .put("widgets", JSONArray(widgets))
            .put("wsize", sizes(o, widgets))
            .put("wopt", cleanWopt(o))
    }

    /** [r] to tenths, 1..[ROWS_MAX]; anything under 1 (or not a number) is 0, by itself. */
    fun rows(r: Double): Double = if (r.isNaN() || r < 1) 0.0 else Math.round(minOf(r, ROWS_MAX) * 10) / 10.0

    private val WIDGET_ID = Regex("[a-z][a-z0-9_]{0,15}")

    /**
     * What a client sends: [clean], but the widgets it does not know (a newer TV Home's, read back from it) kept in "widgets" and
     * "wsize": an older phone or computer that turned one widget on or resized one dropped the newer ones. TV Home's own [clean] decides.
     */
    fun forward(o: JSONObject): JSONObject {
        val c = clean(o)
        o.optJSONArray("widgets")?.let { a -> c.put("widgets", JSONArray((0 until a.length()).map { a.optString(it) }.filter { WIDGET_ID.matches(it) }.distinct().take(16))) }
        o.optJSONObject("wsize")?.let { g ->
            val out = JSONObject()
            for (k in g.keys()) if (WIDGET_ID.matches(k)) g.optString(k).takeIf { it == "big" || it == "small" }?.let { out.put(k, it) }
            c.put("wsize", out)
        }
        themeOp(o.optJSONObject("theme"))?.let { c.put("theme", it) }   // an operation, not a setting: it is not in [clean]
        // "wopt" is a delta (what the client changed, defaults included: a value set back to the default must reach the TV); the options
        // of widgets this client does not know (a newer TV Home's, read back from it) pass too, TV Home's own [clean] decides
        c.remove("wopt")
        o.optJSONObject("wopt")?.let { g ->
            val out = JSONObject()
            for (k in g.keys()) {
                val e = g.optJSONObject(k) ?: continue
                val x = JSONObject()
                for (i in e.keys()) {
                    val v = e.optString(i)
                    val op = options(k).firstOrNull { it.id == i }
                    if (if (k in WIDGETS) op != null && op.store == "wopt" && v in optValues(op) else WIDGET_ID.matches(k) && WIDGET_ID.matches(i) && WIDGET_ID.matches(v)) x.put(i, v)
                }
                if (x.length() > 0) out.put(k, x)
            }
            if (out.length() > 0) c.put("wopt", out)
        }
        o.optJSONObject("wstyle")?.let { c.put("wstyle", JSONObject().also { w -> for (k in WIDGETS) it.optString(k).takeIf { v -> WIDGET_ID.matches(v) }?.let { v -> w.put(k, v) } }) }   // an older client's looks: [merge] turns them into options
        return c
    }

    /** "wsize" kept for the known widgets with a known size; with none yet, the old "wlayout" as the sizes that gave the same picture. */
    private fun sizes(o: JSONObject, widgets: List<String>): JSONObject {
        val out = JSONObject()
        val given = o.optJSONObject("wsize")
        if (given != null) {
            for (k in WIDGETS) given.optString(k).takeIf { it == "big" || it == "small" }?.let { out.put(k, it) }
            return out
        }
        val bigCount = when (o.optString("wlayout")) { "big" -> 1; "big2" -> 2; "flip" -> 1; "quad" -> 0; else -> -1 }   // "row" or none: the defaults
        if (bigCount < 0) return out
        val flip = o.optString("wlayout") == "flip"
        widgets.forEachIndexed { i, k -> out.put(k, if (if (flip) i == 2 else i < bigCount) "big" else "small") }
        return out
    }

    /** [old] with the fields [change] has (any of them), cleaned. [usable]: whether a wall's file is there, [liveOk] whether a live widget is on offer (a theme's apply passes over one that is not). */
    fun merge(old: JSONObject, change: JSONObject, liveOk: (String) -> Boolean = { true }, usable: (String) -> Boolean = { true }): JSONObject {
        val n = JSONObject(clean(old).toString())
        for (k in change.keys()) if (k != "theme" && k != "wopt" && k != "wstyle") n.put(k, change.get(k))
        if (change.has("wopt") || change.has("wstyle")) n.put("wopt", woptMerge(clean(old).optJSONObject("wopt"), change))
        if (change.has("wall") && !change.has("walls")) n.put("walls", JSONArray().put(change.get("wall")))   // a client that picks one: the set is that one
        themeOp(change.optJSONObject("theme"))?.let { doTheme(n, it, usable, liveOk) }
        return clean(n)
    }

    /** {target: name}: apps and shortcuts only (a live widget has its own title), a name trimmed to one line of [NAME_MAX]; an empty one is no entry. */
    fun names(g: JSONObject?): JSONObject {
        val out = JSONObject()
        if (g == null) return out
        for (k in g.keys().asSequence().sorted()) {
            if (out.length() >= NAMES_MAX) break
            if (!Target.valid(k) || isLive(k)) continue
            val v = g.optString(k).replace(Regex("\\s+"), " ").trim().take(NAME_MAX).trim()
            if (v.isNotEmpty()) out.put(k, v)
        }
        return out
    }

    /**
     * The folders kept: an [asked] id (its key is in "tiles") of 1-8 lowercase letters and digits, a name of one line ([NAME_MAX]),
     * installed-app packages only (no shortcut, widget or folder), none in the dock, each app in one folder (the first by id wins);
     * a folder with no app is no folder. Sorted by id, so the same folders always make the same text.
     */
    fun folders(g: JSONObject?, dock: List<String>, asked: Set<String>): JSONObject {
        val out = JSONObject()
        if (g == null) return out
        val seen = HashSet<String>(dock)
        for (id in g.keys().asSequence().sorted()) {
            if (out.length() >= FOLDERS_MAX) break
            if (!isFolder(folderKey(id)) || id !in asked) continue
            val f = g.optJSONObject(id) ?: continue
            val apps = pkgs(f.optJSONArray("apps"), FOLDER_APPS_MAX).filter { Target.kind(it) == Target.Kind.APP && seen.add(it) }
            if (apps.isEmpty()) continue
            val name = f.optString("name").replace(Regex("\\s+"), " ").trim().take(NAME_MAX).trim()
            out.put(id, JSONObject().put("name", name).put("apps", JSONArray(apps)))
        }
        return out
    }

    /** "order": targets and folder keys ([pkgs] alone would drop the folders). */
    private fun gridKeys(a: JSONArray?, max: Int): List<String> {
        val out = LinkedHashSet<String>()
        if (a != null) for (i in 0 until a.length()) a.optString(i).let { if (Target.valid(it) || isFolder(it)) out.add(it) }
        return out.take(max)
    }

    private fun strs(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }

    private fun pkgs(a: JSONArray?, max: Int): List<String> {
        val out = LinkedHashSet<String>()
        if (a != null) for (i in 0 until a.length()) {
            val p = a.optString(i)
            if (Target.valid(p)) out.add(p)
        }
        return out.take(max)
    }
}

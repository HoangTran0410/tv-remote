package dev.tvremote.core.web
import dev.tvremote.core.adb.Adb
import java.io.IOException
import java.net.URLEncoder
import org.json.JSONObject

/** The project's TV apps from the page: TV Home (settings, themes, photos, videos, wallpapers, pins), TV Channels, TV Games. */
internal class HomeApi(core: ManageCore) : ManagePart(core) {
    override fun route(what: String, post: Boolean, r: HttpServer.Request): HttpServer.Response? {
        when (what) {
            "home" -> return HttpServer.Response.json(dev.tvremote.core.ontv.Home.call({ sh(it) }, if (post) r.json() else null,
                if (!post && r.q("thumbs") == "1") "--ez thumbs true" else "").also { h ->   // TV Home's settings (thumbs: the photos' small pictures too)
                    labels?.own(dev.tvremote.core.ontv.Home.names(h.optJSONObject("config"))) })   // the names given there reach every app list
            "home/theme" -> if (!post) {                          // one theme as a file to keep (<name>.tvtheme.json): a download, like the scenes backup
                val cfg = dev.tvremote.core.ontv.Home.call({ sh(it) }, null).optJSONObject("config") ?: throw IOException("home:missing")
                val id = r.q("id")
                fun find(a: org.json.JSONArray?) = (0 until (a?.length() ?: 0)).map { a!!.getJSONObject(it) }.firstOrNull { it.optString("id") == id }
                val t = find(cfg.optJSONArray("themes")) ?: find(cfg.optJSONArray("themesBuiltin")) ?: throw IOException("home:theme_missing")
                val name = dev.tvremote.home.HomeConfig.themeName(r.q("name").ifEmpty { t.optString("name") }).ifEmpty { id }
                val conf = dev.tvremote.home.HomeConfig.pickTheme(t.optJSONObject("conf") ?: JSONObject())
                val disp = "attachment; filename*=UTF-8''" + URLEncoder.encode(name.replace(Regex("[^\\p{L}\\p{N}_.\\- ()]"), "_") + ".tvtheme.json", "UTF-8").replace("+", "%20")
                if (r.q("media") == "1" && dev.tvremote.core.ontv.ThemeFile.mediaKeys(conf).isNotEmpty()) {   // with its own photos and videos (ThemeFile): streamed through, a video is big
                    val adb = link.get()
                    val keys = dev.tvremote.core.ontv.ThemeFile.mediaKeys(conf)
                    for (k in keys) {                                  // every item there and within the cap, before the first byte goes
                        val sz = adb.shell("stat -c %s " + Adb.q(dev.tvremote.core.ontv.Home.mediaPath(k)!!) + " 2>/dev/null").trim()
                        if (!sz.matches(DIGITS)) throw IOException("home:media_missing")
                        if (sz.toLong() > dev.tvremote.core.ontv.ThemeFile.MEDIA_MAX) throw IOException("home:media_big")
                    }
                    val out = HttpServer.Response.stream("application/json; charset=utf-8", -1) { o ->
                        dev.tvremote.core.ontv.ThemeFile.write(o, name, conf, keys) { k, sink ->
                            val s = adb.exec("cat " + Adb.q(dev.tvremote.core.ontv.Home.mediaPath(k)!!) + " 2>/dev/null"); val b = ByteArray(65536)
                            try { while (true) { val n = s.read(b, 0, b.size, 0); if (n < 0) break; sink(b, n) } } finally { s.close() }
                        }
                    }
                    out.headers["Content-Disposition"] = disp
                    return out
                }
                val doc = JSONObject().put("tvtheme", 1).put("name", name).put("conf", conf)
                val out = HttpServer.Response.of("application/json; charset=utf-8", doc.toString(2).toByteArray())
                out.headers["Content-Disposition"] = disp
                return out
            }
            "home/theme/share" -> {                               // one of my themes to the community store: a job (apply, screenshot, photo, send); the page asks until it ends, or cancels
                val j = shareJob
                if (!post) return HttpServer.Response.json((j ?: dev.tvremote.core.ontv.ThemeShare.Job().also { it.state = "none" }).json())
                val o = r.json()
                if (o.optBoolean("cancel")) { j?.cancel(); return HttpServer.Response.json((j ?: dev.tvremote.core.ontv.ThemeShare.Job()).json()) }
                if (j != null && j.state == "run") throw IOException("themeshare:busy")
                return HttpServer.Response.json(dev.tvremote.core.ontv.ThemeShare.start(link.get(), o.optString("id"), o.optString("by"), r.lang).also { shareJob = it }.json())
            }
            "home/web/check" -> if (!post) return HttpServer.Response.json(dev.tvremote.home.WebCheck.check(r.q("u")))   // the background page, tried from this device: the TV's own answer is on TV Home's page
            "home/media" -> if (!post) {                          // one of the person's own photos / videos as it is on the TV, for a theme file to carry (js/home_media.js)
                val path = dev.tvremote.core.ontv.Home.mediaPath(r.q("id")) ?: throw IllegalArgumentException("photo id")
                val adb = link.get()
                val size = adb.shell("stat -c %s " + Adb.q(path) + " 2>/dev/null").trim()
                if (!size.matches(DIGITS)) throw IOException("home:media_missing")
                return HttpServer.Response.stream(if (path.endsWith(".mp4")) "video/mp4" else "image/jpeg", size.toLong()) { o -> pipe(adb.exec("cat " + Adb.q(path) + " 2>/dev/null"), o) }.also { it.patient = true }
            }
            "home/photo/qr" -> if (post) { dev.tvremote.core.ontv.Home.photoQr { sh(it) }; return HttpServer.Response.json(JSONObject().put("ok", true)) }
            "home/photo/drop" -> if (post) return HttpServer.Response.json(dev.tvremote.core.ontv.Home.drop(link.get(), r.json().optString("id")))
            "home/pin" -> if (post) return HttpServer.Response.json(dev.tvremote.core.ontv.Home.pin({ sh(it) }, r.json()))   // a YouTube link on TV Home's grid
            "home/link" -> if (post) return HttpServer.Response.json(dev.tvremote.core.ontv.Home.link({ sh(it) }, r.json()))   // what the TV opens a pasted link with (the paste box's preview)
            "home/link/open" -> if (post) { val j = r.json(); dev.tvremote.core.ontv.Home.openLink({ sh(it) }, j.optString("url"), j.optString("pkg").ifBlank { null }); return HttpServer.Response.json(JSONObject().put("ok", true)) }
            "youtube/open" -> if (post) { dev.tvremote.core.ontv.Home.openYoutube({ sh(it) }, r.json().optString("url")); return HttpServer.Response.json(JSONObject().put("ok", true)) }
            "home/update" -> if (post) return HttpServer.Response.json(at("install") { dev.tvremote.core.ontv.Home.update(link.get(), dir, r.lang) })
            "channels" -> if (!post) return HttpServer.Response.json(dev.tvremote.core.ontv.Channels.state { sh(it) })   // TV Channels: on the TV, and a newer one
            "channels/manage" -> {                                // favourites and groups (the page's dialog): read, or the whole state saved
                val c = dev.tvremote.core.ontv.Channels
                return HttpServer.Response.json(c.manage({ sh(it) }, if (post) r.json() else null, r.q("cc").ifEmpty { null }, r.q("fresh") == "1", r.q("countries") == "1"))
            }
            "channels/counts" -> if (!post) return HttpServer.Response.json(JSONObject().put("counts", JSONObject(dev.tvremote.core.ontv.Channels.counts() ?: emptyMap<String, Int>())))   // the country picker's numbers (from iptv-org, not the TV)
            "channels/categories" -> if (!post) return HttpServer.Response.json(dev.tvremote.core.ontv.Channels.categories(r.q("cc")))   // the site's file, fetched here (no CORS there)
            "channels/export" -> if (!post) {                      // the file to keep: a download, like the scenes backup
                val c = dev.tvremote.core.ontv.Channels
                val doc = c.manage({ sh(it) }, null, export = true).optJSONObject("export") ?: throw IOException("channels:missing")
                val out = HttpServer.Response.of("application/json; charset=utf-8", doc.toString(2).toByteArray())
                val name = "tvchannels-groups-" + doc.optString("date") + ".json"
                out.headers["Content-Disposition"] = "attachment; filename=\"$name\"; filename*=UTF-8''$name"
                return out
            }
            "channels/import" -> if (post) {                      // {mode: merge | replace, doc}: the TV checks the file and says what it did
                val c = dev.tvremote.core.ontv.Channels
                return HttpServer.Response.json(c.manage({ sh(it) }, null, import = r.json()))
            }
            "channels/install" -> if (post) return HttpServer.Response.json(at("install") { dev.tvremote.core.ontv.Channels.install(link.get(), dir, r.lang) })   // at(): the phone keeps going in the background, with its notification
            "channels/open" -> if (post) {                        // a channel by its name; TV Channels finds it (or shows its search)
                if (!dev.tvremote.core.ontv.Channels.open({ sh(it) }, r.json().optString("q"))) throw IOException("channels:missing")
                return HttpServer.Response.ok()
            }
            "channels/play" -> if (post) {                        // {url}: one stream address, played by TV Channels
                when (dev.tvremote.core.ontv.Channels.playResult({ sh(it) }, r.json().optString("url"))) {
                    dev.tvremote.core.ontv.Channels.Play.OLD -> throw IOException("channels:old")   // there, but from before the link: update it
                    dev.tvremote.core.ontv.Channels.Play.MISSING -> throw IOException("channels:missing")
                    else -> {}
                }
                return HttpServer.Response.ok()
            }
            "games" -> if (!post) return HttpServer.Response.json(dev.tvremote.core.ontv.Games.state { sh(it) })   // TV Games: on the TV, a newer one, or "soon"
            "games/install" -> if (post) return HttpServer.Response.json(at("install") { dev.tvremote.core.ontv.Games.install(link.get(), dir, r.lang) })
            "games/pad" -> if (!post) return HttpServer.Response.json(JSONObject().put("url", dev.tvremote.core.ontv.Games.pad({ sh(it) }, link.host) ?: ""))   // the pad page's address; "" while TV Games is not running
            "games/open" -> if (post) {
                if (!dev.tvremote.core.ontv.Games.open { sh(it) }) throw IOException("games:missing")
                return HttpServer.Response.ok()
            }
            "home/photo" -> if (post) {                           // its background: a JPEG the page already made the screen's size
                if (r.length !in 1..6_000_000) throw IllegalArgumentException("photo too large")
                val b = ByteArray(r.length.toInt()); var off = 0
                while (off < b.size) { val n = r.body.read(b, off, b.size - off); if (n < 0) break; off += n }
                return HttpServer.Response.json(dev.tvremote.core.ontv.Home.photo(link.get(), b.copyOf(off)))
            }
            "home/video" -> if (post) {                           // a video or GIF, streamed to the TV (200 MB never sits in memory here)
                val len = r.length
                if (len !in 1..dev.tvremote.home.HomeConfig.VIDEO_MAX_BYTES) throw IllegalArgumentException("video size")
                return HttpServer.Response.json(dev.tvremote.core.ontv.Home.video(link.get(), r.q("name"), len) { s ->
                    val buf = ByteArray(64 * 1024); var left = len
                    while (left > 0) { val n = r.body.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (n < 0) throw IOException("cut"); s.write(buf, 0, n); left -= n }
                })
            }
            "home/walls" -> if (!post) return HttpServer.Response.json(dev.tvremote.core.ontv.Wallpapers.json(dev.tvremote.core.ontv.Wallpapers.fetch()))   // the wallpaper gallery (signed list)
            "home/walls/thumb" -> {
                val b = dev.tvremote.core.ontv.Wallpapers.thumb(r.q("id"))
                return if (b == null) HttpServer.Response.error(404, "not found") else HttpServer.Response.of("image/jpeg", b)
            }
            "home/wall" -> if (post) return HttpServer.Response.json(dev.tvremote.core.ontv.Wallpapers.apply(link.get(), r.json().optString("id")))
        }
        return null
    }

    @Volatile private var shareJob: dev.tvremote.core.ontv.ThemeShare.Job? = null   // the theme being sent to the store (home/theme/share)

}

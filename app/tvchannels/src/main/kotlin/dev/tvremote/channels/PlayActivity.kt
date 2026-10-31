package dev.tvremote.channels

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.tvremote.tvui.TvUi
import java.util.concurrent.Executors

/**
 * Full screen: one channel, opened by its link (tvchannels://play/<id>) from the list, a pinned shortcut, a phone or voice.
 * ▲▼ and CH+/CH- step through [Lineup], numbers jump to a place in it, OK or ◀ shows it beside the picture, ⟲ (Last channel)
 * goes back to the one before. A stream that does not play falls through to the channel's next stream, then says so.
 */
class PlayActivity : Activity() {
    override fun attachBaseContext(b: android.content.Context) = super.attachBaseContext(Lang.wrap(b))
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor(Providers.screenThread)
    private val guideIo = Executors.newSingleThreadExecutor(Providers.screenThread)         // a slow guide never holds up a stream's address
    private var started = false
    private var player: ExoPlayer? = null
    private lateinit var surface: SurfaceView
    private lateinit var frame: FrameLayout
    private lateinit var spin: ProgressBar
    private lateinit var info: LinearLayout
    private lateinit var infoLogo: ImageView
    private lateinit var infoTile: View                              // light, or dark under a pale logo ([LogoFit.isLight])
    private lateinit var infoName: TextView
    private lateinit var infoSub: TextView
    private lateinit var infoNum: TextView
    private lateinit var infoNow: TextView
    private lateinit var infoBar: ProgressBar
    private lateinit var infoNext: TextView
    private lateinit var guide: LinearLayout
    private lateinit var guideList: RecyclerView
    private lateinit var problem: TextView
    private lateinit var digits: TextView
    private lateinit var side: LinearLayout
    private lateinit var sideList: RecyclerView

    private var single = false                                        // a bare address ([openStream]), not a channel of the lineup
    private var at = -1                                               // place in Lineup
    private var streams: List<Channel> = emptyList()                  // the channel on screen, its streams
    private var stream = 0
    private var asProgressive = false
    private var retried = false
    private var before = -1
    private var typed = ""
    private var videoW = 16; private var videoH = 9; private var ratio = 1f

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val d = { v: Number -> TvUi.dp(this, v).toInt() }
        frame = object : FrameLayout(this) {
            override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, bt: Int) { super.onLayout(changed, l, t, r, bt); fit() }
        }.apply { setBackgroundColor(Color.BLACK) }
        surface = SurfaceView(this)
        frame.addView(surface, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        spin = ProgressBar(this).apply { isIndeterminate = true }
        frame.addView(spin, FrameLayout.LayoutParams(d(56), d(56), Gravity.CENTER))
        problem = TvUi.text(this, "", 18f).apply {
            gravity = Gravity.CENTER; setLineSpacing(0f, 1.25f); setPadding(d(28), d(20), d(28), d(20))
            background = TvUi.round(0xD91B2028.toInt(), TvUi.dp(this@PlayActivity, 18)); visibility = View.GONE
        }
        frame.addView(problem, FrameLayout.LayoutParams(d(560), -2, Gravity.CENTER))
        digits = TvUi.text(this, "", 44f, bold = true).apply {
            setPadding(d(22), d(10), d(22), d(10)); background = TvUi.round(0xB3000000.toInt(), TvUi.dp(this@PlayActivity, 16)); visibility = View.GONE
        }
        frame.addView(digits, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { topMargin = d(32); rightMargin = d(40) })

        info = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(d(20), d(16), d(28), d(16)); visibility = View.GONE
            background = TvUi.round(0xD91B2028.toInt(), TvUi.dp(this@PlayActivity, 20), 1, 0x33FFFFFF)
        }
        infoNum = TvUi.text(this, "", 30f, TvUi.ACCENT, true).apply { minWidth = d(60) }
        info.addView(infoNum)
        val tile = FrameLayout(this).apply { background = TvUi.round(TvUi.TILE, TvUi.dp(this@PlayActivity, 10)) }
        infoTile = tile
        infoLogo = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        tile.addView(infoLogo, FrameLayout.LayoutParams(-1, -1).apply { val m = d(8); setMargins(m, m, m, m) })
        info.addView(tile, LinearLayout.LayoutParams(d(112), d(63)).apply { leftMargin = d(12); rightMargin = d(18) })
        val words = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        infoName = TvUi.text(this, "", 22f, bold = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        infoSub = TvUi.text(this, "", 14f, TvUi.DIM).apply { maxLines = 1; setPadding(0, d(6), 0, 0) }
        words.addView(infoName); words.addView(infoSub)
        infoNow = TvUi.text(this, "", 16f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, d(12), 0, 0) }
        infoBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; progressTintList = android.content.res.ColorStateList.valueOf(TvUi.ACCENT)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x33FFFFFF)
        }
        infoNext = TvUi.text(this, "", 14f, TvUi.DIM).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        words.addView(infoNow); words.addView(infoBar, LinearLayout.LayoutParams(-1, d(4)).apply { topMargin = d(8); bottomMargin = d(8) }); words.addView(infoNext)
        info.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
        frame.addView(info, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(d(48), 0, d(48), d(36)) })

        val mx = resources.getDimensionPixelSize(R.dimen.tv_margin_x); val my = resources.getDimensionPixelSize(R.dimen.tv_margin_y)
        side = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(mx, my, d(12), my); visibility = View.GONE           // text inside the overscan margins; the panel itself runs to the edge
            background = TvUi.round(0xEB14171D.toInt(), 0f)
        }
        side.addView(TvUi.text(this, "", 16f, TvUi.ACCENT, true).apply { tag = "title"; setPadding(d(12), 0, 0, d(12)); maxLines = 1 })
        sideList = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@PlayActivity); adapter = SideList(); itemAnimator = null }
        side.addView(sideList, LinearLayout.LayoutParams(-1, 0, 1f))
        frame.addView(side, FrameLayout.LayoutParams(d(442) + mx - d(12), -1, Gravity.START))

        guide = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(d(12), my, mx, my); visibility = View.GONE
            background = TvUi.round(0xEB14171D.toInt(), 0f)
        }
        guide.addView(TvUi.text(this, "", 16f, TvUi.ACCENT, true).apply { tag = "title"; setPadding(d(12), 0, d(12), d(4)); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        guideList = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@PlayActivity); adapter = GuideList(this@PlayActivity); itemAnimator = null }
        guide.addView(guideList, LinearLayout.LayoutParams(-1, 0, 1f))
        frame.addView(guide, FrameLayout.LayoutParams(d(400) + mx - d(12), -1, Gravity.END))
        setContentView(frame)
        open(intent)
    }

    override fun onNewIntent(i: Intent) { super.onNewIntent(i); setIntent(i); open(i) }

    /** The channel a link names: in the lineup the list gave, else among the favourites, else in its country's list. */
    private fun open(i: Intent?) {
        single = Pins.isStreamLink(i?.data)
        if (single) { openStream(Pins.streamUrl(i?.data)); return }
        Pins.findText(i?.data)?.let { find(it); return }
        if (i?.data?.host == "find") { startActivity(Intent(this, BrowseActivity::class.java)); finish(); return }   // no words: the list
        val id = Pins.id(i?.data) ?: Store.last(this) ?: run { finish(); return }
        Lineup.indexOf(id).takeIf { it >= 0 }?.let { tune(it); return }
        spin.visibility = View.VISIBLE
        io.execute {
            val favs = Store.favourites(this).byChannel()
            // then the person's groups, then the channel last played (an m3u link's, kept whole): TV Home's Keep watching opens it
            val group = Store.groups(this).firstOrNull { g -> g.channels.any { it.id == id } }
            val last = Store.lastChannel(this)?.takeIf { it.id == id }
            // its own country's list, else the one on show: Vietnam's list carries iptv-org's "Cinemax.us" too (its "also" channels)
            // a link without the feed ("VTV1.vn", typed or from an older client) takes the channel's first feed
            val hit = { l: List<List<Channel>> -> (l.firstOrNull { it.first().id == id }
                ?: l.firstOrNull { '@' !in id && it.first().id.substringBefore('@') == id })?.first()?.id }
            val (cc, country, found) = listOf(Ids.country(id), Store.country(this)).filter { it.isNotEmpty() }.distinct().asSequence()
                .map { Catalog.channels(this, it).byChannel().let { l -> Triple(it, l, hit(l)) } }.firstOrNull { it.third != null } ?: Triple("", emptyList(), null)
            val want = found ?: id
            main.post {
                if (isDestroyed) return@post
                when {
                    country.isNotEmpty() -> Lineup.set(country, cc.uppercase())
                    favs.any { it.first().id == id } -> Lineup.set(favs, getString(R.string.favourites))
                    group != null -> Lineup.set(group.channels.byChannel(), group.name)
                    last != null -> Lineup.set(listOf(listOf(last)), last.name)
                    else -> { spin.visibility = View.GONE; fail(getString(R.string.not_found)); return@post }
                }
                tune(Lineup.indexOf(want))
            }
        }
    }

    /** tvchannels://url?u=...: one address, no lineup (so no channel keys, banner or side list); Back leaves to whatever was on the TV before. */
    private fun openStream(url: String?) {
        closeInfo(); showSide(false); showGuide(false); problem.visibility = View.GONE
        if (url == null) { spin.visibility = View.GONE; fail(getString(R.string.bad_link)); return }
        val host = try { java.net.URI(url).host.orEmpty() } catch (e: Exception) { "" }
        streams = listOf(Channel("url:$host", host, "", "", url)); stream = 0; asProgressive = false; retried = false
        start()
    }

    /** tvchannels://find/<text>: the best match in the country on show plays as a play link would; none opens the list searching for it. */
    private fun find(text: String) {
        spin.visibility = View.VISIBLE
        io.execute {
            val cc = Store.country(this)
            val country = Catalog.channels(this, cc).byChannel()
            val mine = (Store.favourites(this) + Store.groups(this).flatMap { it.channels }).map { it.id }.toSet()
            val hit = Find.best(country, text, { it in mine }, { Store.alive(this, it.url) == false })
            main.post {
                if (isDestroyed) return@post
                if (hit == null) {
                    startActivity(Intent(this, BrowseActivity::class.java).putExtra(BrowseActivity.QUERY, text))
                    finish(); return@post
                }
                Lineup.set(country, cc.uppercase())
                tune(Lineup.indexOf(hit.first().id))
            }
        }
    }

    private fun tune(i: Int) {
        val all = Lineup.channels
        if (all.isEmpty()) return
        val n = Math.floorMod(i, all.size)
        if (n != at) before = at
        at = n
        streams = all[n].sortedBy { if (Store.alive(this, it.url) == false) 1 else 0 }   // known-dead streams last
        stream = 0; asProgressive = false; retried = false
        Store.setLast(this, streams.first().id)
        Store.setLastChannel(this, streams.first())
        showInfo()
        (sideList.adapter as SideList).notifyDataSetChanged()
        start()
    }

    private fun start() {
        val ch = streams.getOrNull(stream) ?: return
        problem.visibility = View.GONE
        spin.visibility = View.VISIBLE
        if (Providers.isLink(ch.url)) {                               // a provider's channel: its URL carries a token, asked for now
            val fresh = retried
            main.removeCallbacks(slow)
            io.execute {
                val url = try { Providers.resolve(this, ch.url, fresh) } catch (e: Exception) { null }
                main.post {
                    if (isDestroyed || streams.getOrNull(stream) !== ch) return@post   // changed channel meanwhile
                    if (url == null) next(null) else play(ch, url)
                }
            }
            return
        }
        play(ch, ch.url)
    }

    private fun play(ch: Channel, url: String) {
        if (!started) return                                          // an answer that came after Home: onStart plays it again
        val p = player ?: ExoPlayer.Builder(this).build().also { x ->
            player = x
            x.setVideoSurfaceView(surface)
            x.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(s: Int) {
                    if (s == Player.STATE_READY) { spin.visibility = View.GONE; main.removeCallbacks(slow); if (!single) streams.getOrNull(stream)?.let { Store.setAlive(this@PlayActivity, it.url, true) } }   // a bare address may hold a token: not kept
                    if (s == Player.STATE_BUFFERING) spin.visibility = View.VISIBLE
                }
                override fun onPlayerError(e: PlaybackException) = failed(e)
                override fun onVideoSizeChanged(v: VideoSize) {
                    if (v.width > 0 && v.height > 0) { videoW = v.width; videoH = v.height; ratio = v.pixelWidthHeightRatio; fit() }
                }
            })
        }
        val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true).setConnectTimeoutMs(8000).setReadTimeoutMs(12000)
            .setUserAgent(ch.agent.ifEmpty { Catalog.BROWSER })
        if (ch.referrer.isNotEmpty()) http.setDefaultRequestProperties(mapOf("Referer" to ch.referrer))
        val hls = !asProgressive && !PROGRESSIVE.containsMatchIn(Uri.parse(url).path.orEmpty())
        val item = MediaItem.Builder().setUri(url).apply { if (hls) setMimeType(MimeTypes.APPLICATION_M3U8) }.build()
        val src: MediaSource = if (hls) HlsMediaSource.Factory(http).setAllowChunklessPreparation(true).createMediaSource(item)
            else ProgressiveMediaSource.Factory(http).createMediaSource(item)
        p.setMediaSource(src)
        p.prepare()
        p.playWhenReady = true
        main.removeCallbacks(slow); main.postDelayed(slow, 20_000)
    }

    /** Nothing after 20 s: as good as dead, try the next stream. */
    private val slow = Runnable { if (player?.playbackState != Player.STATE_READY) next(null) }

    private fun failed(e: PlaybackException) {
        if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) { player?.seekToDefaultPosition(); player?.prepare(); return }
        val notHls = e.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
            e.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ||
            e.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED
        if (notHls && !asProgressive) { asProgressive = true; start(); return }     // a plain TS / MP4 behind a URL with no extension
        val code = (e.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
        if (!retried && code != null && code != 403 && code != 451) { retried = true; start(); return }   // a redirect's fresh token can 404 once (HTV7 on a BRAVIA)
        next(e)
    }

    private fun next(e: PlaybackException?) {
        main.removeCallbacks(slow)
        if (!single && shouldMarkDead(Probe.online(this))) streams.getOrNull(stream)?.let { Store.setAlive(this, it.url, false) }
        stream++; asProgressive = false; retried = false
        if (stream < streams.size) { start(); return }
        player?.stop()
        spin.visibility = View.GONE
        val forbidden = (e?.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode.let { it == 403 || it == 451 }
        fail(getString(if (streams.firstOrNull()?.geo == true || forbidden) R.string.cant_play_geo else R.string.cant_play) + "\n" + getString(R.string.fail_hint))
    }

    /** Every stream of this channel failed: OK on the message tries them all again from the first. */
    private fun retryAll() {
        if (streams.isEmpty()) { open(intent); return }               // "not found" / a bad link: look again, never a spinner on nothing
        problem.visibility = View.GONE; spin.visibility = View.VISIBLE
        stream = 0; asProgressive = false; retried = false
        start()
    }

    private fun fail(s: String) { problem.text = s; problem.visibility = View.VISIBLE }

    /** The picture at its own shape, as large as the screen allows (no stretching of a 4:3 channel). */
    private fun fit() {
        val w = frame.width; val h = frame.height
        if (w == 0 || h == 0) return
        val a = videoW * ratio / videoH
        val lw: Int; val lh: Int
        if (w.toFloat() / h > a) { lh = h; lw = (h * a).toInt() } else { lw = w; lh = (w / a).toInt() }
        val lp = surface.layoutParams
        if (lp.width != lw || lp.height != lh) { lp.width = lw; lp.height = lh; surface.layoutParams = lp }
    }

    private fun showInfo() {
        val c = streams.firstOrNull() ?: return
        infoNum.text = "${at + 1}"
        infoName.text = c.name
        infoSub.text = listOfNotNull(c.quality.ifEmpty { null }, c.group.takeUnless { it.isEmpty() || it.equals("undefined", true) }?.let { BrowseActivity.groupName(this, it) }, Lineup.title.ifEmpty { null }).joinToString(" · ")
        Logos.into(infoLogo, c.logo, 200) { got -> infoTile.background = TvUi.round(if (got && Logos.isLight(c.logo)) LogoFit.DARK_TILE else LogoFit.LIGHT_TILE, TvUi.dp(this, 10)) }
        info.visibility = View.VISIBLE
        main.removeCallbacks(hideInfo); main.postDelayed(hideInfo, 5000)
        val ch = streams
        val kept = Guide.known(c.id)
        showNow(kept.orEmpty())
        if (kept == null) guideIo.execute { val got = Guide.of(this, ch); main.post { if (!isDestroyed && streams === ch) showNow(got) } }
    }

    /** The banner's programme lines: what is on, how far in, what comes next ("▶ guide" when there is one). */
    private fun showNow(shows: List<Show>) {
        val on = Guide.now(shows); val then = Guide.after(shows)
        TvUi.show(infoNow, on != null); TvUi.show(infoBar, on != null); TvUi.show(infoNext, then != null)
        if (on != null) {
            infoNow.text = "${Guide.hm(on.start)} – ${Guide.hm(on.end)}   ${on.title}"
            infoBar.progress = ((System.currentTimeMillis() - on.start) * 1000 / (on.end - on.start)).toInt()
        }
        if (then != null) infoNext.text = dev.tvremote.tvui.Marks.text(this, getString(R.string.guide_next, "${Guide.hm(then.start)}  ${Reminders.mark(this, streams.firstOrNull()?.id.orEmpty(), then.start)}${then.title}") + "    ▶ " + getString(R.string.guide))   // ▶ as a line icon
    }

    /** The day's shows on the right; ◀ or Back closes it. */
    private fun showGuide(on: Boolean) {
        guide.visibility = if (on) View.VISIBLE else View.GONE
        if (!on) return
        info.visibility = View.GONE
        val c = streams.firstOrNull() ?: return
        val title = guide.findViewWithTag<TextView>("title"); val titleText = "${getString(R.string.guide)} · ${c.name}"
        title.text = titleText
        val a = guideList.adapter as GuideList
        val ch = streams
        fun fill(shows: List<Show>) {
            title.text = titleText
            if (shows.isEmpty()) { guide.visibility = View.GONE; Toast.makeText(this, R.string.guide_none, Toast.LENGTH_SHORT).show(); return }
            a.set(shows); GuideList.focusOn(guideList, a.onNow)
        }
        Guide.known(c.id)?.let { fill(it); return }
        a.set(emptyList()); title.text = getString(R.string.guide_loading)
        guideIo.execute { val got = Guide.of(this, ch); main.post { if (!isDestroyed && streams === ch && guide.visibility == View.VISIBLE) fill(got) } }
    }

    private var infoHidAt = 0L                                    // when the 5 s timer hid the banner: an OK just after still counts it as shown
    private val hideInfo = Runnable { info.visibility = View.GONE; infoHidAt = SystemClock.uptimeMillis() }
    private fun infoShown() = info.visibility == View.VISIBLE || SystemClock.uptimeMillis() - infoHidAt < 300
    private fun closeInfo() { main.removeCallbacks(hideInfo); info.visibility = View.GONE; infoHidAt = 0 }

    private val jump = Runnable {
        typed.toIntOrNull()?.let { n -> if (n in 1..Lineup.channels.size) tune(n - 1) }
        typed = ""; digits.visibility = View.GONE
    }

    private fun showSide(on: Boolean) {
        side.visibility = if (on) View.VISIBLE else View.GONE
        if (!on) return
        side.findViewWithTag<TextView>("title").text = Lineup.title
        (sideList.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(at.coerceAtLeast(0), TvUi.dp(this, 120).toInt())
        sideList.post { sideList.findViewHolderForAdapterPosition(at)?.itemView?.requestFocus() ?: sideList.getChildAt(0)?.requestFocus() }
    }

    override fun onKeyDown(k: Int, e: KeyEvent): Boolean {
        if (guide.visibility == View.VISIBLE) {
            if (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_DPAD_LEFT) { showGuide(false); return true }
            if (k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN || k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) return super.onKeyDown(k, e)
            showGuide(false)                                          // CH+ / numbers: back to watching, the key does its own
        }
        if (side.visibility == View.VISIBLE) {
            if (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_DPAD_RIGHT) { showSide(false); return true }
            return super.onKeyDown(k, e)
        }
        if (single && k != KeyEvent.KEYCODE_BACK && k != KeyEvent.KEYCODE_DPAD_CENTER && k != KeyEvent.KEYCODE_ENTER && !isMediaKey(k)) return super.onKeyDown(k, e)
        if (e.repeatCount > 0 && k in ZAP_KEYS) return true            // held: one channel per press (the user), not a stream per repeat
        when (k) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> tune(at + 1)
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> tune(at - 1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (problem.visibility == View.VISIBLE) retryAll() else if (single) togglePause() else if (infoShown()) { closeInfo(); showSide(true) } else showInfo()
            KeyEvent.KEYCODE_BACK -> if (infoShown()) closeInfo() else return super.onKeyDown(k, e)
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_MENU -> showSide(true)
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_INFO -> if (info.visibility == View.VISIBLE) showGuide(true) else showInfo()
            KeyEvent.KEYCODE_PROG_RED, KeyEvent.KEYCODE_TV_DATA_SERVICE -> showGuide(true)
            KeyEvent.KEYCODE_LAST_CHANNEL -> if (before >= 0) tune(before)
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY ->
                player?.let { if (it.isPlaying && k != KeyEvent.KEYCODE_MEDIA_PLAY) it.pause() else { it.seekToDefaultPosition(); it.play() } }
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9, in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> {
                if (typed.length < 4) typed += if (k >= KeyEvent.KEYCODE_NUMPAD_0) k - KeyEvent.KEYCODE_NUMPAD_0 else k - KeyEvent.KEYCODE_0
                digits.text = typed; digits.visibility = View.VISIBLE
                main.removeCallbacks(jump); main.postDelayed(jump, 1500)
            }
            else -> return super.onKeyDown(k, e)
        }
        return true
    }

    private fun isMediaKey(k: Int) = k == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || k == KeyEvent.KEYCODE_MEDIA_PAUSE || k == KeyEvent.KEYCODE_MEDIA_PLAY
    private fun togglePause() { player?.let { if (it.isPlaying) it.pause() else it.play() } }

    override fun onStart() {
        super.onStart(); started = true
        // onStop dropped their timers: the banner would stay on the picture, a half-typed number on screen
        if (info.visibility == View.VISIBLE) { main.removeCallbacks(hideInfo); main.postDelayed(hideInfo, 5000) }
        typed = ""; digits.visibility = View.GONE
        if (player == null && streams.isNotEmpty()) start()
    }

    override fun onStop() {
        super.onStop()
        started = false
        main.removeCallbacksAndMessages(null)
        player?.release(); player = null                              // a TV has one or two video decoders: give them back
    }

    override fun onDestroy() { io.shutdownNow(); guideIo.shutdownNow(); super.onDestroy() }

    private inner class SideList : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = Lineup.channels.size
        private val lightTile by lazy { TvUi.round(LogoFit.LIGHT_TILE, TvUi.dp(this@PlayActivity, 6)) }   // lazy: no resources while the activity's fields are built
        private val darkTile by lazy { TvUi.round(LogoFit.DARK_TILE, TvUi.dp(this@PlayActivity, 6)) }
        /** A row's children: 0 the logo tile (letters under a [LogoView]), 1 the name, 2 the quality. */
        override fun onCreateViewHolder(p: ViewGroup, t: Int): RecyclerView.ViewHolder {
            val c = this@PlayActivity
            val row = TvUi.row(c, 0, "", " ") {}.apply { layoutParams = RecyclerView.LayoutParams(-1, -2) }
            val tile = FrameLayout(c)
            tile.addView(TextView(c).apply {
                tag = "letters"; gravity = Gravity.CENTER; textSize = 14f; setTextColor(0xFF5F6368.toInt()); typeface = android.graphics.Typeface.DEFAULT_BOLD
            }, FrameLayout.LayoutParams(-1, -1))
            tile.addView(LogoView(c), FrameLayout.LayoutParams(-1, -1))
            row.addView(tile, 0, LinearLayout.LayoutParams(TvUi.dp(c, 72).toInt(), TvUi.dp(c, 40).toInt()).apply { rightMargin = TvUi.dp(c, 12).toInt() })
            return object : RecyclerView.ViewHolder(row) {}
        }
        override fun onBindViewHolder(h: RecyclerView.ViewHolder, i: Int) {
            val row = h.itemView as LinearLayout
            val c = Lineup.channels[i].first()
            val tile = row.getChildAt(0) as FrameLayout
            val letters = tile.getChildAt(0) as TextView
            letters.text = TvUi.initials(c.name)
            Logos.into(tile.getChildAt(1) as ImageView, c.logo, 160) { got ->        // the same disk-cached logos and tile choice as the grid
                letters.visibility = if (got) View.INVISIBLE else View.VISIBLE
                tile.background = if (got && Logos.isLight(c.logo)) darkTile else lightTile
            }
            (row.getChildAt(1) as TextView).text = "${(i + 1).toString().padStart(3, '\u2007')}   ${c.name}"   // figure spaces: the names line up
            (row.getChildAt(2) as TextView).text = c.quality
            row.isActivated = i == at
            row.alpha = if (Lineup.channels[i].all { Store.alive(this@PlayActivity, it.url) == false }) 0.5f else 1f
            row.setOnClickListener { showSide(false); if (i != at) tune(i) }
        }
    }

    companion object {
        private val ZAP_KEYS = setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_UP,
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN)
        private val PROGRESSIVE = Regex("""\.(mp4|ts|mkv|webm|mp3|aac|flv)$""", RegexOption.IGNORE_CASE)
    }
}

/** A failed stream is marked dead (for 6 hours) only while the network is up: offline says nothing of the stream (as [Probe.check]). */
internal fun shouldMarkDead(online: Boolean) = online

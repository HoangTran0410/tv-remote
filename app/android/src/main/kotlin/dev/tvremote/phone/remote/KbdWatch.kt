package dev.tvremote.phone.remote

import dev.tvremote.phone.MainActivity

import android.view.View
import dev.tvremote.core.adb.TvKeyboard
import dev.tvremote.core.adb.TvKeyboard.Do
import java.util.concurrent.Executors

/**
 * Opens the typing box by itself when the TV's own keyboard comes up (someone focused a search box there), as the page does
 * (js/remote.js, kbdFollow). Needs the ADB session (`tv`): the TV's input method is read through it about once a second, only
 * while the remote is on screen. Turned off in About (pref "kbdAuto", on by default).
 */
internal class KbdWatch(private val a: MainActivity) {
    private val follow = TvKeyboard.Follow()
    private val reader = TvKeyboard.Reader()
    private val work = Executors.newSingleThreadExecutor()
    private var asking = false

    fun closedByPerson() = follow.closedByPerson()
    fun typedByPerson() = follow.typedByPerson()
    fun close() = work.shutdownNow()

    private val tick = object : Runnable {
        override fun run() {
            if (work.isShutdown) return
            a.ui.postDelayed(this, 1000)
            val tv = a.tv
            if (asking || !a.foreground || a.remote.visibility != View.VISIBLE || a.covered() || tv == null || !tv.ready()
                || !a.prefs.getBoolean("kbdAuto", true)) return
            asking = true
            work.execute {
                val shown = try { reader.ask { tv.shell(it) } } catch (e: Exception) { null }
                a.ui.post {
                    asking = false
                    if (shown == null) return@post
                    when (follow.read(shown)) {
                        Do.OPEN -> a.kbdOpen(true)
                        Do.CLOSE -> a.kbdOpen(false)
                        Do.NONE -> {}
                    }
                }
            }
        }
    }

    fun start() { a.ui.removeCallbacks(tick); a.ui.post(tick) }
}

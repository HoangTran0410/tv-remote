package dev.tvremote.home

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import dev.tvremote.home.wall.Walls

/**
 * The screensaver's photo slideshow (HomeConfig "dphoto"): which pictures, in what order. The drawing is DreamBody's; this is only
 * the picking, so it can be tested without a TV. Called only while the screensaver is on screen, off the main thread.
 */
object PhotoShow {
    const val EVERY_MS = 20_000L                                   // one picture; the crossfade is 1.2 s of it
    const val MAX = 300                                            // a camera roll is thousands: a slideshow needs no more, and the walk stays short
    private const val DEPTH = 4                                    // DCIM/Camera/2026/ ... a stick's root: deep enough, never the whole disk
    private val EXT = setOf("jpg", "jpeg", "png", "webp")

    /** A picture file by its name; hidden ones (".thumb.jpg", ".thumbnails/") are the system's, not the person's. */
    fun isPicture(path: String): Boolean {
        val parts = path.replace('\\', '/').split('/').filter { it.isNotEmpty() }
        return parts.isNotEmpty() && parts.none { it.startsWith(".") } && parts.last().substringAfterLast('.', "").lowercase() in EXT
    }

    /** [n] places in a shuffled order that depends only on [seed] (a new one every time the screensaver starts), each exactly once. */
    fun order(n: Int, seed: Long): List<Int> = (0 until n).shuffled(java.util.Random(seed))

    /** The folder(s) of [mode] ("pictures", "dcim", "usb"); "usb" = every storage volume that is not the TV's own ([volumes]: names under /storage). */
    fun roots(mode: String, volumes: List<String>): List<String> = when (mode) {
        "pictures" -> listOf("/sdcard/Pictures")
        "dcim" -> listOf("/sdcard/DCIM")
        "usb" -> volumes.filter { it != "emulated" && it != "self" && !it.startsWith(".") }.map { "/storage/$it" }
        else -> emptyList()
    }

    /** Every picture of [mode]: "mine" = the wallpaper photos sent to TV Home; null = not readable without the permission. */
    fun list(c: Context, mode: String): List<File> {
        if (mode == "mine") return Walls.photos(c).mapNotNull { Walls.file(c, it) }.filter { it.isFile }
        val out = ArrayList<File>()
        for (r in roots(mode, File("/storage").list()?.toList() ?: emptyList())) {
            try {
                File(r).walkTopDown().maxDepth(DEPTH).onEnter { !it.name.startsWith(".") }
                    .filter { it.isFile && isPicture(it.name) && it.length() > 20_000 }   // under 20 KB: an icon or a thumbnail, not a photo
                    .forEach { out.add(it); if (out.size >= MAX) return out }
            } catch (e: SecurityException) { }
        }
        return out
    }

    private fun perm() = if (Build.VERSION.SDK_INT >= 33) "android.permission.READ_MEDIA_IMAGES" else "android.permission.READ_EXTERNAL_STORAGE"

    /** Picking a folder in Customize: a client's ADB grant already did this; on the TV itself the system asks once (Android 6+). */
    fun ask(a: Activity, mode: String) {
        if (mode == "mine" || mode.isEmpty() || Build.VERSION.SDK_INT < 23) return
        if (a.checkSelfPermission(perm()) != PackageManager.PERMISSION_GRANTED) a.requestPermissions(arrayOf(perm()), 7)
    }
}

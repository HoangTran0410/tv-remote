package dev.tvremote.games

import android.app.ActivityManager
import android.content.Context
import android.opengl.EGL14

/**
 * NM-TVBOX1 (Android 9, census 06d05c39): the box says OpenGL ES 3, so LibretroDroid's view asks GLSurfaceView for an ES 3 config, and
 * the box's EGL refuses it: "eglChooseConfig failed" on the GL thread, and the app died. The view cannot be put on ES 2: it sets its
 * renderer in its constructor, and the fields that would change it are hidden from apps since Android 9 (max-target-o, tried on the
 * API 34 emulator). So [ok] asks EGL GLSurfaceView's own question first, and the game says the TV cannot run it; [guard] is the net
 * for a refusal [ok] did not foresee.
 */
object GlCheck {
    private const val ES3_BIT = 0x40                                  // EGL_OPENGL_ES3_BIT_KHR
    private const val ES2_BIT = 4                                     // EGL_OPENGL_ES2_BIT

    /** GLSurfaceView's default config (RGB 888, depth 16) exists for the ES version LibretroDroid will ask (its own rule: 3 when the
     *  system says 3). Not known (EGL will not even start): true, as before. */
    fun ok(c: Context): Boolean = try {
        val es3 = (c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).deviceConfigurationInfo.reqGlEsVersion >= 0x30000
        val d = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)        // the display HWUI shares: never eglTerminate it
        val v = IntArray(2)
        if (d == EGL14.EGL_NO_DISPLAY || !EGL14.eglInitialize(d, v, 0, v, 1)) true
        else {
            val spec = intArrayOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 0,
                EGL14.EGL_DEPTH_SIZE, 16, EGL14.EGL_STENCIL_SIZE, 0, EGL14.EGL_RENDERABLE_TYPE, if (es3) ES3_BIT else ES2_BIT, EGL14.EGL_NONE)
            val n = IntArray(1)
            EGL14.eglChooseConfig(d, spec, 0, null, 0, 0, n, 0) && n[0] > 0
        }
    } catch (e: Throwable) { true }

    /** GLSurfaceView's config errors (thrown on its "GLThread", where nothing of ours can catch them) go to [onFail] on that thread
     *  instead of closing the app; everything else to the handler before, as always. Once per process. */
    fun guard(onFail: () -> Unit) {
        val before = Thread.getDefaultUncaughtExceptionHandler()
        if (before is Guard) { before.onFail = onFail; return }
        Thread.setDefaultUncaughtExceptionHandler(Guard(before, onFail))
    }

    private class Guard(val before: Thread.UncaughtExceptionHandler?, @Volatile var onFail: () -> Unit) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            if (t.name.startsWith("GLThread") && e is IllegalArgumentException && e.message.orEmpty().startsWith("egl")) onFail()
            else before?.uncaughtException(t, e)
        }
    }
}

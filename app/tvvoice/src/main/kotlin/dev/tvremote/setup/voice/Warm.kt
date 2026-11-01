package dev.tvremote.setup.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** [VoiceProtocol.WARM]: the model out of the APK now (~27 MB, a few seconds), not on the first press. Only the shell may send it (DUMP). */
class Warm : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val r = goAsync()
        Thread({ try { VoiceModel.unpack(c) } catch (e: Exception) { } finally { r.finish() } }, "voice-unpack").start()
    }
}

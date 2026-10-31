package dev.tvremote.phone.remote

import dev.tvremote.phone.MainActivity
import dev.tvremote.phone.ui.label
import dev.tvremote.phone.ui.menu

import dev.tvremote.core.T
import android.widget.Toast
import dev.tvremote.core.remote.Inputs
import dev.tvremote.core.remote.Remote

// "Ngõ vào" on the native remote (the "inputs" action of remote.json; the page's inputsMenu in js/remote.js): the TV's real inputs by
// name, a tap switches. Android TV over ADB (dumpsys, Inputs.kt), LG by its remote link. A TV with none to list (a box, the
// emulator), or with only keys (Samsung, an Android TV whose ADB is off): one short line, and the TV's own picker, as the key always did.

private const val INPUT_KEY = 178
private val inputCaches = java.util.concurrent.ConcurrentHashMap<String, Inputs.Cache>()   // per TV, a minute each

internal fun MainActivity.inputsMenu() {
    val tv = tv
    val b = basic
    val lg = b != null && b.kind != Remote.Kind.ANDROID && Remote.Kind.caps(b.kind).contains("inputs")
    if (!lg && (tv == null || !tv.ready())) { opens(INPUT_KEY); return }
    val cache = inputCaches.getOrPut(host ?: "") { Inputs.Cache() }
    Thread {
        val list = try { cache.get { if (lg) b!!.inputs() else Inputs.parse(tv!!.shell(Inputs.READ)) } } catch (e: Exception) { null }
        ui.post {
            if (isFinishing) return@post
            if (list.isNullOrEmpty()) {
                Toast.makeText(this, if (list == null) T("auto.inputs_failed") else T("auto.no_inputs"), Toast.LENGTH_SHORT).show()
                opens(INPUT_KEY)
                return@post
            }
            val items = ArrayList<Triple<String, String, () -> Unit>>()
            for (i in list) items.add(Triple("@input|" + i.label, if (i.connected == false) T("remote.not_plugged") else "") { switchInput(i, lg, cache) })
            items.add(Triple("@menu|" + T("remote.tv_picker"), "") { opens(INPUT_KEY) })
            menu(T("auto.key_input"), -1, items)
        }
    }.start()
}

private fun MainActivity.switchInput(i: Inputs.Input, lg: Boolean, cache: Inputs.Cache) {
    val tv = tv
    val b = basic
    Thread {
        val err = try {
            if (lg) { if (!b!!.switchInput(i.id)) throw IllegalArgumentException("this TV cannot switch inputs from here") }
            else Inputs.switch({ tv!!.shell(it) }, i.id, cache.peek())
            null
        } catch (e: Exception) { e }
        ui.post {
            if (isFinishing) return@post
            Toast.makeText(this, if (err == null) T("remote.switched_to", "name" to i.label) else T("inputs_menu.the_tv_did_not"), Toast.LENGTH_SHORT).show()
        }
    }.start()
}

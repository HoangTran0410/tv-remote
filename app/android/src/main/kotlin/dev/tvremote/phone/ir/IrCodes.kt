package dev.tvremote.phone.ir

import dev.tvremote.phone.picker.name
import dev.tvremote.phone.remote.keys
import dev.tvremote.phone.ui.GREEN
import dev.tvremote.phone.ui.RED

import org.json.JSONObject

// The infrared code sets (assets/ir/codes.json, made by dev/gen/ir_codes.py from Flipper-IRDB): one profile per remote, its keys named
// as in Scenes.KEYS. Plain Kotlin + org.json, so IrCodesTest reads the real file.

/** [kind]: its number among the brand's different remotes (IrCodes.merge), 0 when the brand has one. [models]: the models it stands for,
 *  [ids]: theirs, so a choice saved before they were merged still finds it. */
internal class IrProfile(val id: String, val brand: String, val name: String, val keys: Map<String, String>,
                         val kind: Int = 0, val models: List<String> = listOf(name), val ids: Set<String> = setOf(id))

internal object IrCodes {
    /** Every key a profile may hold, in the order the screen and the file list them. */
    val KEYS = listOf("POWER", "HOME", "BACK", "UP", "DOWN", "LEFT", "RIGHT", "OK", "MENU", "SETTINGS", "VOL_UP", "VOL_DOWN", "MUTE", "CH_UP", "CH_DOWN",
        "INPUT", "PLAY_PAUSE", "STOP", "REWIND", "FFWD", "NEXT", "PREV", "INFO", "GUIDE", "SUBTITLE", "RED", "GREEN", "YELLOW", "BLUE", "NETFLIX", "YOUTUBE", "PRIME") + (0..9).map { it.toString() }

    /** The keys "Find code" can try: one the box surely answers, whatever remote it came with. */
    val PROBES = listOf("POWER", "HOME", "VOL_UP", "MUTE")

    fun parse(text: String): List<IrProfile> {
        val arr = JSONObject(text).getJSONArray("profiles")
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val k = o.getJSONObject("keys")
            val keys = LinkedHashMap<String, String>()
            for (n in k.keys()) keys[n] = k.getString(n)
            IrProfile(o.getString("id"), o.getString("brand"), o.getString("name"), keys)
        }
    }

    /**
     * One profile per remote that is really different: the file's profiles of a brand whose power key has the same protocol and address are one remote under
     * several model names (a hundred Samsung TVs), and a list of model numbers told nobody which to pick. The most common first; each
     * key gets the code most of them agree on.
     */
    fun merge(all: List<IrProfile>): List<IrProfile> = all.map { it.brand }.distinct().flatMap { brand ->
        val groups = all.filter { it.brand == brand }.groupBy { family(it.keys["POWER"] ?: "") }.values.sortedByDescending { it.size }
        groups.mapIndexed { i, g ->
            val keys = LinkedHashMap<String, String>()
            for (k in KEYS) g.mapNotNull { it.keys[k] }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.let { keys[k] = it.key }
            IrProfile(g[0].id, brand, g[0].name, keys, if (groups.size > 1) i + 1 else 0, g.map { it.name }, g.map { it.id }.toSet())
        }
    }

    /** "NEC 4" of "NEC 4 8": a remote's codes share it (one TV's power-on and its toggle differ only in the command). A raw recording is its own. */
    private fun family(code: String): String = if (code.startsWith("RAW")) code else code.split(' ').take(2).joinToString(" ")

    /** The profiles that answer to [key] differently: the first of each distinct code, with how many share it. Find code steps through these, not every model. */
    fun distinct(all: List<IrProfile>, key: String): List<Pair<IrProfile, Int>> {
        val groups = LinkedHashMap<String, MutableList<IrProfile>>()
        for (p in all) p.keys[key]?.let { groups.getOrPut(it) { ArrayList() }.add(p) }
        return groups.values.map { Pair(it.first(), it.size) }
    }
}

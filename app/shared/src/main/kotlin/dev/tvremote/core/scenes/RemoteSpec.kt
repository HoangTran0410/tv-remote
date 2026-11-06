package dev.tvremote.core.scenes
import org.json.JSONArray
import dev.tvremote.core.T
import org.json.JSONObject

/**
 * The remote's keys and menu entries (app/shared/web/remote.json), which the Phone app's native remote and the web remote
 * (js/remote_spec.js) both draw from: add, reorder or rename an entry there and both change. [parse] reads it for one side:
 * [core] is "phone" for the native remote, "web" for a page; that side's own text (the entry's "phone" / "web" fields) lies over the
 * common one, and entries other cores show are left out.
 */
object RemoteSpec {
    /**
     * One key or menu entry. [key]: a name from [Scenes.KEYS]; [action]: what the side's handler map does (with [arg]); [expand] stands
     * in for a list the handler supplies; [needs]: what the TV must be able to do ([Remote.Kind.caps]), else greyed or left out.
     */
    class Entry(
        val id: String, val icon: String, val t: String, val sub: String,
        val key: String?, val action: String?, val arg: String?, val whenCond: String?, val expand: String?, val style: String?,
        val needs: List<String> = emptyList(), val tint: String = "",
        val kinds: Map<String, Pair<String?, String?>> = emptyMap(),   // kind -> (t, icon) override
    ) {
        /** This entry as one kind of TV shows it ("kinds" in remote.json: t and icon only); the entry itself when it has none. */
        fun forKind(kind: String): Entry {
            val (kt, ki) = kinds[kind] ?: return this
            return Entry(id, ki ?: icon, kt ?: t, sub, key, action, arg, whenCond, expand, style, needs, tint, kinds)
        }
        fun can(caps: Set<String>) = needs.all { it in caps }
        /** The words in the app's language ([t] and [sub] are ids in i18n/<lang>.json). */
        fun words(): String = if (t.isEmpty()) "" else T(t)
        fun subWords(): String = if (sub.isEmpty()) "" else T(sub)
    }

    /** One of Roku's hidden screens ("roku" in remote.json): [keys] are [Scenes.KEYS] names, already spelled out ("HOME*3" is three). */
    class RokuScreen(val id: String, val t: String, val keys: List<String>, val tvOnly: Boolean, val ask: String?, val askOk: String?)

    /** The hidden screens and the pauses between presses: [gap] ms, [homeGap] between two Home presses. */
    class RokuScreens(val gap: Long, val homeGap: Long, val screens: List<RokuScreen>)

    /** [simple]: the Simple remote's rows ("simple" in remote.json): the only keys it shows, big; an "expand": "pad" entry is the D-pad. */
    class Spec(val rows: Map<String, List<List<Entry>>>, val menus: Map<String, List<Entry>>, val roku: RokuScreens = RokuScreens(350, 250, emptyList()),
               val simple: List<List<Entry>> = emptyList())

    fun parse(json: String, core: String): Spec {
        val root = JSONObject(json)
        val rows = LinkedHashMap<String, List<List<Entry>>>()
        root.optJSONObject("rows")?.let { r ->
            for (name in r.keys()) rows[name] = r.getJSONArray(name).let { a -> (0 until a.length()).map { i -> entries(a.getJSONArray(i), core) } }
        }
        val menus = LinkedHashMap<String, List<Entry>>()
        root.optJSONObject("menus")?.let { m -> for (name in m.keys()) menus[name] = entries(m.getJSONArray(name), core) }
        val roku = root.optJSONObject("roku")?.let { r ->
            val a = r.getJSONArray("screens")
            RokuScreens(r.getLong("gap"), r.getLong("homeGap"), (0 until a.length()).map { i ->
                val x = a.getJSONObject(i)
                RokuScreen(x.getString("id"), x.getString("t"), x.getString("keys").split(" ").flatMap { tok -> tok.substringBefore("*").let { k -> List(tok.substringAfter("*", "1").toInt()) { k } } },
                    x.optBoolean("tvOnly"), x.optString("ask").ifEmpty { null }, x.optString("askOk").ifEmpty { null })
            })
        } ?: RokuScreens(350, 250, emptyList())
        val simple = root.optJSONObject("simple")?.getJSONArray("rows")?.let { a -> (0 until a.length()).map { i -> entries(a.getJSONArray(i), core) } } ?: emptyList()
        return Spec(rows, menus, roku, simple)
    }

    private fun entries(a: JSONArray, core: String): List<Entry> = (0 until a.length()).mapNotNull { entry(a.getJSONObject(it), core) }

    private fun entry(e: JSONObject, core: String): Entry? {
        e.optJSONArray("cores")?.let { c -> if ((0 until c.length()).none { c.getString(it) == core }) return null }
        val over = e.optJSONObject(core) ?: JSONObject()
        fun text(name: String) = over.optString(name, e.optString(name))
        return Entry(
            e.getString("id"), e.optString("icon"), text("t"), text("sub"),
            e.optString("key").ifEmpty { null }, e.optString("do").ifEmpty { null }, e.optString("arg").ifEmpty { null },
            e.optString("when").ifEmpty { null }, e.optString("expand").ifEmpty { null }, e.optString("style").ifEmpty { null },
            e.optJSONArray("needs")?.let { n -> (0 until n.length()).map { n.getString(it) } } ?: emptyList(),
            e.optString("tint"),
            e.optJSONObject("kinds")?.let { k -> k.keys().asSequence().associateWith { n -> k.getJSONObject(n).let { o -> o.optString("t").ifEmpty { null } to o.optString("icon").ifEmpty { null } } } } ?: emptyMap(),
        )
    }
}

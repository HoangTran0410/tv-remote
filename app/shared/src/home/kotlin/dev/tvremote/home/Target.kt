package dev.tvremote.home

/**
 * The one thing "open this" means, wherever something can be opened: the key map, the app opened at power-on and TV Home's grid.
 * It is a string, so every saved config and every client (phone, computer, TV web page; js/target.js is the same code) can keep it:
 *   com.example.app            an app by its package (the only form before this; saved configs still read as that)
 *   pin:com.example.app/id     a launcher shortcut of that app (LauncherApps: static, dynamic or pinned), [HomeConfig] "pin" keys
 *   tile:dev.tvremote.setup/id a live widget from a provider ([LiveWidgets]); its press runs the provider's action
 *   do:controlcenter           an action of TV Home itself ([ACTIONS]): the Control Center (Quick settings) over any app; for a remote key only
 * Why a prefix and not a JSON object: configs already hold plain strings in dock / hidden / order / keyapps / autoapp, and a
 * plain package name must stay valid in all of them (no migration, an old TV Home still understands what it knew).
 */
object Target {
    enum class Kind { APP, PIN, TILE, ACTION }   // TILE = a live widget: the name is kept, TV Setup's HomeKeys reads it, as "tile:" is stored on TVs

    private val PKG = Regex("[A-Za-z][\\w]*(\\.[\\w]+)+")
    private val PIN = Regex("pin:([A-Za-z][\\w]*(?:\\.[\\w]+)+)/([A-Za-z0-9][\\w.\\-:@]{0,63})")   // a channel shortcut's id is "ch:00sReplay.us@SD": without : and @ it was no target (not offered, not kept)
    private val LIVE = Regex("tile:([A-Za-z][\\w]*(?:\\.[\\w]+)+)/([a-z][a-z0-9_]{0,15})")
    const val MAX = 200
    const val CONTROL = "do:controlcenter"
    val ACTIONS = setOf(CONTROL)
    private const val HOME = "dev.tvremote.home"

    /** What [t] points at, or null when it is not a good target (empty, too long, another shape). */
    fun kind(t: String): Kind? = when {
        t.isEmpty() || t.length > MAX -> null
        t.startsWith("pin:") -> if (PIN.matches(t)) Kind.PIN else null
        t in ACTIONS -> Kind.ACTION
        t.startsWith("tile:") -> if (LIVE.matches(t)) Kind.TILE else null
        PKG.matches(t) -> Kind.APP
        else -> null
    }

    fun valid(t: String) = kind(t) != null
    fun isPin(t: String) = kind(t) == Kind.PIN

    /** The package this target belongs to: the app itself, the app owning the shortcut, the live widget's provider. */
    fun pkg(t: String): String = when (kind(t)) {
        Kind.APP -> t
        Kind.PIN -> t.removePrefix("pin:").substringBefore('/')
        Kind.TILE -> HomeConfig.liveProvider(t)
        Kind.ACTION -> HOME
        null -> ""
    }

    /** The id after the slash (a shortcut's or a tile's); empty for an app. */
    fun id(t: String): String = when (kind(t)) { Kind.PIN, Kind.TILE -> t.substringAfter('/'); Kind.ACTION -> t.substringAfter(':'); else -> "" }

    fun pin(pkg: String, id: String) = "pin:$pkg/$id"

    /** [t] as saved: a good target stays, anything else is "" (so a hand-edited or newer config cannot smuggle in a command line). */
    fun clean(t: String): String = if (valid(t)) t else ""
}

package dev.tvremote.setup.keys

/**
 * What TV Setup and its add-on TV Setup Keys (`app/tvkeys`) agree on; compiled into both. The add-on holds the accessibility services
 * and does nothing but hand a key to TV Setup, which decides (KeyFix, HoldKeys) and answers at once: eat it or pass it.
 */
object KeysProtocol {
    const val ADDON = "dev.tvremote.setup.keys"
    const val SETUP = "dev.tvremote.setup"

    /** TV Setup's bound service the add-on talks to; guarded by [PERMISSION], which only an app with TV Setup's key can hold. */
    const val BRIDGE = "dev.tvremote.setup.KeysBridge"
    const val PERMISSION = "dev.tvremote.setup.permission.KEYS"
    const val DESCRIPTOR = "dev.tvremote.setup.keys.Bridge"

    /** In: key code, action, repeat count, down time, event time. Out: 1 = the key is taken (eat it), 0 = pass it on; then the [TX_CODES] rev (an older TV Setup: none, read as 0). */
    const val TX_KEY = 1
    /** Out: a rev (0: none), a count, the codes: the keys beyond [INTEREST] the site's list gives THIS TV (KeyList), asked again when TX_KEY's rev differs. */
    const val TX_CODES = 2

    /** TV Setup -> the add-on (an explicit broadcast guarded by [PERMISSION]): open the system's own power menu. Only while the service is on; never turns anything off by itself. */
    const val ACTION_POWER_MENU = "dev.tvremote.setup.keys.POWER_MENU"

    /**
     * The only keys the add-on asks about; any other passes with no call at all (every key of a filtering service waits for its
     * answer). A superset of what TV Setup may ever take: the stock launcher's keys (KeyFix.RULES), the colour keys the person may
     * give an app (KeyFix.COLOUR) and the keys of "hold to use Input / Settings" (HoldKeys.KEYS) and the remote's mic button (MicVoice.KEYS: SEARCH, ASSIST, VOICE_ASSIST) and KeyFix.DEVICE_KEYS (130: a Xiaomi MOOQ0's Input button), Home (3: HomeKey on a Chromecast) and the numbers (7..16:
     * HomeKey.DIGITS, held in another app); a test in :tv keeps it so.
     */
    val INTEREST: Set<Int> = setOf(3, 170, 178, 176, 190, 191, 192, 195, 284, 183, 184, 185, 186, 84, 219, 231, 130) + (243..251) + (7..16)
}

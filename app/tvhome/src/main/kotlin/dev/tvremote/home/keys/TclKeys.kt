package dev.tvremote.home.keys

/**
 * A TCL remote's app buttons send TCL's own key codes, not Android's (cleanyourtv TROUBLESHOOTING, the TCL G08 / G09 notes; vgs-cleantv
 * `PHIM_TCL_CHUNG`): the stock home answered them, so with launcherx off (Optimize TV, a home swap) they reach the screen in front and
 * nothing opens. On TV Home's own screen TV Home opens the app. Not through TV Setup's key filter, which would answer them in every
 * app: on TCL Google TV any key-filtering accessibility service turns a held volume key into one step per press (cleanyourtv #763).
 * YouTube is KEYCODE_BUTTON_3 there, a global key TV Setup's [KeyFix] already gives back.
 */
object TclKeys {
    val APPS: Map<Int, List<String>> = mapOf(
        4062 to listOf("com.netflix.ninja"),                           // Netflix
        4065 to listOf("com.google.android.youtube.tv", "com.liskovsoft.smarttubetv.beta", "com.teamsmart.videomanager.tv"),   // YouTube (where the remote sends it so)
        4099 to listOf("com.amazon.amazonvideo.livingroom"),           // Prime Video
        4160 to listOf("com.apple.atve.androidtv.appletv"))            // Apple TV

    /** Other makers may use the same numbers for other buttons: only a TCL's. */
    fun isTcl(maker: String?, brand: String?) = maker.equals("TCL", true) || brand.equals("TCL", true)

    /** The apps [code] opens on this TV, first installed wins; empty = not a TCL app button. */
    fun apps(code: Int, maker: String?, brand: String?): List<String> = if (isTcl(maker, brand)) APPS[code].orEmpty() else emptyList()
}

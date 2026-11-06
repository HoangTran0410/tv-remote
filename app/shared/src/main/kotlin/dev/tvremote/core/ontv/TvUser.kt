package dev.tvremote.core.ontv

/**
 * Which Android user our `pm` commands act on. On a Google TV with a profile switched in (`am get-current-user` is 10+), `pm`
 * without `--user` (and `pm disable-user --user 0`) acts on user 0, so an app turned off by Optimize TV still showed for the
 * profile in front, while the page read user 0 back and looked fine (measured on the API 34 emulator: dev/decisions/tv-setup.md,
 * "Profiles: which user a command acts on"). Writes and reads of an app's on/off state both carry [USER], so they are one user.
 * `settings` and `cmd package query-activities` already follow the user in front and are left alone.
 */
object TvUser {
    /**
     * A shell expression for the user in front, evaluated by the TV each time (a profile may be switched in between). Android before
     * 8 has no `am get-current-user`: the answer is not a number and the expression is 0, as every command was before.
     */
    const val ID = "\$(am get-current-user 2>/dev/null | grep -E '^[0-9]+\$' || echo 0)"   // no `case` inside $( ): old shells choke

    /** The option to put after `pm list packages` / `pm enable` / `pm disable-user`. */
    const val USER = "--user $ID"

    fun disable(pkg: String) = "pm disable-user $USER $pkg"
    fun enable(pkg: String) = "pm enable $USER $pkg"

    /** `pm list packages` with [flags] (`-d`, `-e`, `-3 -e`) for the user in front. */
    fun list(flags: String = "") = "pm list packages" + (if (flags.isEmpty()) "" else " $flags") + " $USER"
}

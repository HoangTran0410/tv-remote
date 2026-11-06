# Flow: blocked apps (an app another package suspended: "App isn't available")

Read [README.md](README.md) first: change the row a fault belongs to, and this file in the same commit.

**Code:** `app/shared/src/main/kotlin/dev/tvremote/core/ontv/Suspended.kt`, `app/shared/src/main/kotlin/dev/tvremote/core/manage/HealthApi.kt`.
The cards and the question: `app/shared/web/js/health.js:drawSusp` (also the Apps tab's, `app/shared/web/js/apps.js`).

**Tests:** `app/shared/src/test/kotlin/dev/tvremote/core/ontv/SuspendedTest.kt`.

## The goal, for the person

An app on the TV opens only "App isn't available" (Google TV: "managed by Google TV"): Android has it suspended, and some package
did that. The Health tab and the top of the Apps tab show a 🔒 card per suspender with its apps; **Unblock** lifts what it blocked,
asking first when that resets the suspender. Not the PIN lock (`lock`): that one is TV Setup's own rule.

## Reading (`Suspended.CMD`, `Suspended.parse`, `Suspended.users`)

One `dumpsys package packages`, filtered on the TV by awk to the lines with `suspended=true` or `suspendingPackage=` (tens of bytes).

| # | The TV prints | Read as | Test |
|---|---|---|---|
| 1 | Android 9-10: `User N: ... suspended=true suspendingPackage=X` on one line | app, suspender X, user N | `android9And10PrintTheSuspenderOnTheUserLine` |
| 2 | Android 11+: `User N: ... suspended=true`, then a `Suspend params:` block, one `suspendingPackage=X` line per suspender | app, each X, the user of the `User` line above | `android14LinesGiveTheAppAndWhoSuspendedIt` |
| 3 | several users (Google TV profiles: a TCL G10 had 0, 10, 11), each with its own suspension | the users per suspender (`Suspended.users`) | `aProfileSuspensionIsLiftedUnderThatUser` |
| 4 | `suspended=true` with no suspender printed | the app, an empty set: listed, no Unblock (the button is off) | `twoSuspendersTwoUsersAndNothingSuspended` |

## Who blocked it, and what Unblock does (`HealthApi` `unsuspend`, `Suspended.lift`)

| # | Suspender | Unblock runs | Asks first | The person sees | Test |
|---|---|---|---|---|---|
| 5 | the shell or the system (`Suspended.SHELL`: root, com.android.shell, android, system; e.g. our own or `pm suspend` by hand) | `pm unsuspend [--user N] <app>` per app and user | no: nothing is reset | "Unblocked" | `aProfileSuspensionIsLiftedUnderThatUser` |
| 6 | the Google TV launcher (`com.google.android.apps.tv.launcherx`: kids profile, screen time; the TCL G10 support case, TV Setup itself among the apps) | `pm clear [--user N] launcherx` per user it locked under: Android lifts all of its suspensions (PackageManagerService.clearApplicationUserData) | yes: its settings, home screen and profiles start over (`health.susp_ask`) | "Unblocked", named "Google TV" | `tclG10LockedByTheGoogleTvLauncher` |
| 7 | Family Link or another parental / device-admin app | `pm clear [--user N] <it>` as row 6; a device owner may refuse the clear, or lock again at once | yes | "{by} blocked them again ..." (`health.susp_still`) when the apps stay blocked after 4 reads 600 ms apart | |
| 8 | an unknown package | as row 7 | yes | its label, or its package name | |
| 9 | the suspender locks them again after the clear (the profile's limit is still on) | the same 4 reads see it | | `health.susp_still`: turn the limit off on the TV, in its profile or screen-time settings | |

User 0 is sent as before (`pm clear X`, no `--user`): every Android takes that form; other users get `--user N`.

## Not covered

- Which profile is in front is not read: every user the suspender blocked under is cleared (row 3).
- The question does not name the user; on a TV with profiles it resets the suspender in each of them.
- Not tested on a real multi-user TV: row 3 is from the dump format, not a capture.

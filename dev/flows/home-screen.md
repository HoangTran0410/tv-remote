# Flow: another launcher as the home screen

Read [README.md](README.md) first: change the row a fault belongs to, and this file in the same commit.

**Code:** `app/shared/src/main/kotlin/dev/tvremote/core/ontv/LauncherSwap.kt` (`LauncherSwap`, `LauncherWatch`),
`app/shared/src/main/kotlin/dev/tvremote/core/ontv/LauncherInstall.kt`, `app/shared/src/main/kotlin/dev/tvremote/core/ontv/Setup.kt`,
`app/shared/src/main/kotlin/dev/tvremote/core/ontv/Rescue.kt`, `app/shared/src/main/kotlin/dev/tvremote/core/manage/SetupApi.kt`,
`app/tvsetup/src/main/kotlin/dev/tvremote/setup/LauncherGuard.kt`, `app/tvsetup/src/main/kotlin/dev/tvremote/setup/Reoptimize.kt`,
`app/tvsetup/src/main/kotlin/dev/tvremote/setup/HomeKey.kt`.

**Tests:** `app/shared/src/test/kotlin/dev/tvremote/core/LauncherTest.kt`: each row names its test.

## The goal, for the person

Pressing Home opens the launcher they picked, on every TV, with nothing to do by hand, and the TV never ends up with no way
home (a boot loop, a black screen, no Wi-Fi). Where the TV does not let the home screen itself change, TV Setup's key filter
sends Home there instead: the person sees the same thing. An error only when neither can work, and it says the one thing to do.

## Two ways to make Home open a launcher

| Way | What is done on the TV | The record (`LauncherSwap.REC`) |
|---|---|---|
| **swap** | the TV's own launchers and the setup wizard's Home off, the new one the HOME role, Home pressed and checked | `off` = what went off, `why` = "" |
| **key** (`LauncherSwap.byKey`) | nothing off; TV Setup's rule `homekey` = the launcher (`HomeKey`: a short Home press opens it, a long one is the TV's own), then up to `LauncherSwap.KEYS_WAIT` s for its key filter to run (`keys` in TV Setup's rules state, `KeysBridge.bound`; the add-on TV Setup Keys goes in first); the role set (not for `boot`) | `off` = [], `why` = `key`, kept once the filter runs; `LauncherSwap.via` says why |

The key way needs TV Setup 0.26.3+ (`SetupNeed` "homekey"). Without it: `SetupNeed.Missing`, every client's one
Install / Update TV Setup question (Optimize TV too: its other steps are done by then); nothing stays off.

## Entry points: all call `LauncherSwap.take`

| Where | Code | After |
|---|---|---|
| Optimize TV (TV Setup, phone, computer) | `app/shared/src/main/kotlin/dev/tvremote/core/ontv/Setup.kt:takeHome` | log "Home screen: TV Home" (`LauncherSwap.homeOpens`); via `wifi` adds `launcher.key_wifi` (forget the Wi-Fi, join again, Optimize again: a full swap) |
| Launcher gallery (page, TV Setup) | `app/shared/src/main/kotlin/dev/tvremote/core/ontv/LauncherInstall.kt:run` | swap: the 30 s Keep question; key: kept at once |
| Settings page, Home screen | `app/shared/src/main/kotlin/dev/tvremote/core/manage/SetupApi.kt:homeSet` | answers `LauncherSwap.homeOpens`; the TV's own launcher picked = the way back |
| A firmware update (TV Setup, daily) | `app/tvsetup/src/main/kotlin/dev/tvremote/setup/Reoptimize.kt:homeBack` | swap: chosen again, or `take` again; key: nothing (the rule survives updates) |

Via `home` and `wifi_unknown` (TVs the swap does not know yet) go to the census: `home:key:<via>`, `launcher:key:<via>`.

## LauncherSwap.take(pkg): the cases, in order

Cases (`app/shared/src/main/kotlin/dev/tvremote/core/ontv/LauncherSwap.kt:WHYS`): crash, home, timeout, interrupted, user, wifi, wifi_unknown, cast, zte, board, boot, keys, key

| # | Told apart by | Way | Test |
|---|---|---|---|
| 0 | not a launcher (no HOME activity) | none: false, nothing touched | `aPackageWithNoHomeActivityIsRefusedUntouched` |
| 1 | a key-way record from before | its rule is dropped first, then the rows below | `theWayBackFromTheKeyFilterLetsHomePassAndANewPickReplacesIt` |
| 2 | Chromecast / Google TV Streamer (`LauncherSwap.CAST_HOSTS`): the wizard off breaks casting | key, via `cast` | `aChromecastKeepsItsWizardForCastingAndTvSetupTakesHome` |
| 3 | `com.control.device` (a ZTE box that factory-resets with no system launcher on) | key, via `zte` | `aZteBoxWithItsResetWatchdogKeepsItsLauncherOn` |
| 4 | a MOKA board (`LauncherSwap.NO_SWAP`): the wizard comes back each boot, a boot loop | key, via `board` | `aMokaBoardGetsHomeByTheKeyFilterAndNothingIsTouched` |
| 5 | no FallbackHome, the launcher not direct-boot aware: a black screen at boot | key, via `boot`, the role NOT set | `noFallbackHomeNeedsALauncherThatStartsBeforeTheUnlock` |
| 6 | the wizard's Home cannot go off alone, and a saved Wi-Fi is the wizard's (`LauncherSwap.wifiSafe` false): off = offline | everything back on, key, via `wifi` (issue #9, a TCL) | `aWizardHoldingTheWifiLeavesHomeToTheKeyFilter` |
| 7 | same, the Wi-Fi's owner cannot be read (`LauncherSwap.wifiSafe` null) | key, via `wifi_unknown` | `aWizardHoldingTheWifiLeavesHomeToTheKeyFilter` |
| 8 | swapped, but Home lands elsewhere (the wizard, a stock launcher, a picker) | everything back on, key, via `home` | `aHomeThatDoesNotLandIsPutBackAndTheKeyFilterHasIt`, `homeIsWhatTheHomeKeyOpensNotOnlyTheRole` |
| 9 | swapped, Home lands on it | swap: pending (the Keep question) or kept | `swapMakesItHomeAndRecordsWhatWentOff` |
| 10 | a step hung so long the watchdog rolled back meanwhile | false, `interrupted` | `aSwapThatOutlivedTheWatchdogIsUndoneAgain` |
| 11 | rows 2-8 with TV Setup older than 0.26.3, or none | `SetupNeed.Missing`, nothing stays off | `aTvSetupTooOldForTheKeyFilterIsAskedForAndNothingStaysOff` |
| 12 | rows 2-8, TV Setup did not take the rule | false, `refused` = the via: the words `launcher.why_<via>` | `everyReasonHasWords` |
| 13 | rows 2-8, the rule taken but the key filter not running after `KEYS_WAIT` s (issue #9, TCLs: Optimize said "through TV Setup", Home went to the TCL launcher) | the rule dropped, no record kept, false, `refused` = `keys`: "kept its own home screen" + turn on TV Setup Keys in Accessibility; to the census (`home:keys`) | `aKeyFilterThatNeverStartsIsNotCalledHome`, `aKeyFilterThatComesUpWhileTheAddOnGoesInKeepsHome` |

## After: the watchdog (`LauncherWatch.check` in TV Setup's `LauncherGuard`, every 3 s while a record is active)

| Record | Seen | Then | The person sees | Test |
|---|---|---|---|---|
| swap, pending | no answer in time | rollback `timeout` | the "went back" notice | `watchdogWaitsForPendingThenRollsBackOnTimeout` |
| swap | Home on the wizard / a picker / the fallback, or the wizard on again (an Acer at boot, a TCL in seconds) | rollback, then key, via `home` | nothing: Home still opens the launcher | `theWizardWholeBackOnAfterABootGoesToTheKeyFilter`, `theWizardsHomeBackOnAfterABootGoesToTheKeyFilter` |
| swap | same, but the key filter does not run (row 13) | rollback, nothing kept | the notice, why `keys` | `theWatchdogSaysSoWhenTheKeyFilterCannotTakeHomeBack` |
| swap | the launcher was uninstalled | rollback `home` (no launcher for the key way) | notice | `watchdogRollsBackOnABadHomeAndOnlyAfterKeepToo` |
| swap or key | the launcher crashed twice in 5 min | rollback `crash` | notice | `theKeyWayIsGuardedForCrashesOnly` |
| key | where Android sends Home | ignored: Home is the filter's | | `theWizardWholeBackOnAfterABootGoesToTheKeyFilter` |
| key | the TV's own home screen came to the front by itself in the last few seconds (Back out of an app, an app closing; an AOC), not after a long Home: `LauncherWatch.cameHome` | `HomeKey.landed` opens the launcher picked | the launcher picked | `theTvsOwnHomeComingUpByItselfOpensTheLauncherPickedInTheKeyWay` |

Key way at boot and on waking (`HomeKey.trigger`, before the watchdog starts): the TV's own home screen on the screen (Google's
launchers, or any of `HomeKey.own`: an AOC's launcher was not one of Google's, so it stayed after a reboot) opens the launcher picked.

## The way back: always `LauncherSwap.rollback`

It turns on what the record turned off, chooses the home before, and drops the `homekey` rule of a key-way record (`LauncherSwap.dropKey`).
Called by: Keep's Go back and the notice (`user`), the gallery's "Use the TV's own home screen", the Settings page when the TV's own
launcher is picked, Optimize's Undo (`app/shared/src/main/kotlin/dev/tvremote/core/ontv/Setup.kt:undo`, before the record is deleted), the watchdog.
A Chromecast or ZTE sent to the key way by 0.32.1 or older has no record: `LauncherSwap.dropKey` tells them by the TV itself
(`aChromecastSentByAnOlderVersionWithNoRecordStillLetsHomePass`). A launcher uninstalled while it has the key: `HomeKey.pkg` finds no
home activity and Home passes to the TV's own.

## "What does Home open?": `LauncherSwap.homeOpens`

A kept key-way record whose launcher is still there: that launcher; else the HOME role (`LauncherSwap.homeNow`). Used where a person
reads it or where Home is pressed for them: Optimize's log, the Settings page (`app/shared/src/main/kotlin/dev/tvremote/core/manage/SetupApi.kt:tvsetRead`),
"TV frozen" (`app/shared/src/main/kotlin/dev/tvremote/core/ontv/Rescue.kt:run`: `input keyevent` passes the filter by, so it opens TV Home itself).
Checkout's "never wipe the home screen" and uninstall's "never the home screen" read the role: in the key way the TV's own launcher
is still the system's home.

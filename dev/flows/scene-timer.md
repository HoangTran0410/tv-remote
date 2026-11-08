# Flow: a scene that starts at a time or on an event (on the TV, by TV Setup)

Read [README.md](README.md) first: change the row a fault belongs to, and this file in the same commit.

**Code:** `app/tvsetup/src/main/kotlin/dev/tvremote/setup/rules/SceneTimer.kt`, `app/tvsetup/src/main/kotlin/dev/tvremote/setup/rules/Guard.kt`,
`app/tvsetup/src/main/kotlin/dev/tvremote/setup/ScenePin.kt`, `app/tvsetup/src/main/kotlin/dev/tvremote/setup/rules/NotifyLater.kt`, `app/shared/src/main/kotlin/dev/tvremote/core/scenes/Scenes.kt`.

**Tests:** `app/tvsetup/src/test/kotlin/dev/tvremote/setup/rules/SceneTimerTest.kt` (it also holds the `NotifyLater.step` tests): each row names its test where a pure one can hold it.

## The goal, for the person

A scene with "Start at" (a time + weekdays, or one date; the page's Scenes card, guide topic `scenes`) runs on the TV at that time with
the phone and the computer off. It never runs late in a pile: a run more than 5 minutes late (`SceneTimer.GRACE_MS`) is skipped.

A scene can also have "Start when" (`on`): an app comes to the front, or the TV turns on from standby (rows 16-22).

## How it runs

No alarm, receiver or thread of its own. `SceneTimer.set` keeps the timed scenes (prefs `scenetimer`, from RulesReceiver "scenes" or
the TV page's save) and `Rules.active` is true while one exists, so `Guard` runs; each `Guard.check` calls `SceneTimer.tick`, which
asks the pure `SceneTimer.step` and starts the due ones through `runNow` (ScenePin.kt) over TV Setup's own ADB link (the shell:
Android 14's block on a background app opening a screen never applies). One cursor per scene (the last moment looked at, ms):
the scene's next time after it (`Scenes.nextAt`, in the TV's time zone of that moment) has come = run. Guard's chain is
`postDelayed` on the main looper (3-60 s, sooner when a scene is near) and survives any Throwable (`Guard.safe`).

## Case by case

| # | Case | What happens | Test |
|---|---|---|---|
| 1 | TV on, TV Setup running, ADB up | runs within a few seconds of its time; "running" and "done" toasts | `newSceneWaitsForItsTimeAndFiresOnce` |
| 2 | saved (or its time changed) after today's time | counts from the save: the next time, not now | `savingAtThePastTimeDoesNotRunIt`, `changedTimeStartsAgainAndOnceEndsWithNothingToWaitFor` |
| 3 | no ADB at its minute (the link in its 5-min backoff, not back yet after standby) | `runNow` waits 10 s, then `SceneTimer.retry` puts the cursor back before the time: the next check (≤ 60 s) tries again, quietly, until 5 min have passed; then "no ADB" once and skipped. Before: dropped at the first try | `aRunWithNoAdbIsTriedAgainInsideTheGraceOnly` |
| 4 | standby, CPU awake (most boxes keep TV Setup running: guide `awake`) | as 1: Guard keeps looking; a "Power on" step wakes the screen | |
| 5 | deep sleep (the CPU stops; `postDelayed` counts uptime, so the check waits) | the check runs on wake; ≤ 5 min late = runs then, later = skipped, nothing shown. Not measured how late on the FPT / BRAVIA | `aLongGapOrAClockJumpForwardRunsNothing` |
| 6 | TV off at the mains, or the box killed TV Setup in standby | nothing runs; on start the missed time is > 5 min old: skipped, no backlog | `aLongGapOrAClockJumpForwardRunsNothing` |
| 7 | boot with a wrong year, then the clock jumps forward | everything between is skipped, never replayed | `aLongGapOrAClockJumpForwardRunsNothing` |
| 8 | the clock jumps back | the cursor is pulled back to now: the scene is not stuck waiting for the old time | `aClockThatGoesBackIsNotStuck` |
| 9 | the time zone changes (or summer time) | the next look reads the new zone: the time is the new zone's; moved into the past by > 5 min = skipped; moved back by an hour after it ran = it can run again that day | `aNewTimezoneCountsFromTheNextLook` |
| 10 | scene edited or removed while a run waits for ADB | the retry is dropped (`SceneTimer.retry` null): the new time counts | `aRunWithNoAdbIsTriedAgainInsideTheGraceOnly` |
| 11 | a one-date scene after its run | nothing left to wait for; it stays in the list | `changedTimeStartsAgainAndOnceEndsWithNothingToWaitFor` |
| 12 | Fire TV (local ADB blocked) or no ADB ever | row 3 each time: tried for 5 min, then "no ADB" | |
| 13 | TV Setup older than `SetupNeed` "scenetime" | the client's save is refused with the update question; nothing timed on the TV | |
| 14 | no timed scene | `SceneTimer.any` false: Guard does not run for it, nothing is read | |
| 15 | an "open app" step: the app turned off, or Android 6 and older (no `cmd package resolve-activity`) | `Scenes.launch`: resolve its TV / phone screen; else `am start -p` by category; still nothing: `AppRestore` brings it back for the user in front and tries once more; then "no launcher screen" | |
| 16 | scene with `on.app`, screen on | each `Guard.check` (every <= 10 s while such a scene exists) reads `Usage.events` since its cursor; an app coming to the front (not another screen of the app already in front) runs the scene once | `anAppComingToTheFrontStartsItsSceneOnce` |
| 17 | the app stays in front, or the person leaves and comes back | stays: nothing again; comes back: a new run | `anAppComingToTheFrontStartsItsSceneOnce` |
| 18 | the scene itself opens that app | events inside its run (+ 5 s) are ignored: it does not start itself | `aSceneThatOpensItsOwnAppDoesNotStartItself` |
| 19 | an event older than 5 min (TV was off, deep sleep) or another app | skipped, no backlog | `anotherAppOrAnOldEventStartsNothing` |
| 20 | first look after TV Setup starts / the clock went back | the cursor starts at now and the app in front (`Usage.front`) is no start | |
| 21 | `on.power`: the TV turns on from standby | the screen-on receiver of `WelcomeWatch` calls `SceneTimer.onPower`; not while that scene runs. A cold boot is not covered (BootReceiver) | `twoScenesOnTheSameAppBothStartAndPowerSkipsARunningOne` |
| 22 | no scene with `on`; screen off | no events are read, no receiver work (`onPower` returns at once); TV Setup older than `SetupNeed` "sceneon": the save is refused with the update question | |
| 23 | no ADB at an event run | `launch` passes `noAdb = { true }`: silent, no "no ADB" on every app opened; no retry (an event is gone) | measured: on an emulator without the link the wait took ~3 min, the scene quiet meanwhile |
| 24 | a notification sent "in 10 min / 20 min / 1 h" (`delay` on the notify request; the page's Notify dialog "When") | `NotifyReceiver` keeps it in prefs `notifylater` (`NotifyLater.add`, pokes Guard); `Guard.check` shows what is due through `NotifyLater.tick`. Not shown if > 10 min late (TV off), dropped if due further away than a day (clock went back); survives TV Setup being killed; none pending = nothing runs. TV Setup older than `SetupNeed` "notifylater": the client asks to update before sending | `aMessageWaitsThenShowsOnceAndNeverInAPile` |

## Checking it

On the TV emulator: save a scene for the next minute (a Notify step), watch `adb logcat -s tvr-guard` and the toasts. Row 3: stop
the ADB link (`adb shell setprop service.adb.tcp.port -1` and restart adbd) a little before, bring it back within 5 min. Row 9:
`adb shell service call alarm 3 s16 Europe/Paris` (or Settings) before the time.

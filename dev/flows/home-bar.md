# Flow: Home over an app shows TV Home's bar

Read [README.md](README.md) first: change the row a fault belongs to, and this file in the same commit. Next to
[home-screen.md](home-screen.md): that one decides which launcher Home opens; this one decides whether Home first shows a bar.

**Code:** `app/tvsetup/src/main/kotlin/dev/tvremote/setup/home/HomeBar.kt`, `app/tvsetup/src/main/kotlin/dev/tvremote/setup/KeysBridge.kt`,
`app/tvsetup/src/main/kotlin/dev/tvremote/setup/home/HomeKeys.kt`, `app/tvsetup/src/main/kotlin/dev/tvremote/setup/keys/KeyFix.kt`,
`app/tvhome/src/main/kotlin/dev/tvremote/home/DockBarActivity.kt`.

**Tests:** `app/tvsetup/src/test/kotlin/dev/tvremote/setup/home/HomeBarTest.kt`: each row names its test.

## The goal, for the person

With the option on (Customize > Keys & startup, the page's Keys tab, "Home key in an app"), Home pressed while an app is open shows a glass bar
over that app: the Home item, TV Home's dock apps, the apps opened last. OK opens one, Back closes the bar and the app is as it was,
Home again (or the Home item) goes to the home screen. Off (default): Home is exactly what it was, nothing extra runs.

## Why this way

The system sends Home to the launcher before any app sees it, and an app in the background cannot draw over another without the
overlay permission. TV Setup's key filter (the add-on TV Setup Keys, `KeysBridge`) is the one piece that sees Home first, and TV Setup
is exempt from Android 14's background-start block (the appop SYSTEM_ALERT_WINDOW, `KeyFix.prepare`, which reads it back and tries again on the next ADB link when it is not "allow"). So the filter eats Home, looks at
what is in front (`Usage.front`), and starts TV Home's see-through activity `DockBarActivity` (theme Ghost, like the Control Center):
the app beneath stays visible and running. No service, receiver or timer of our own; the setting rides TV Home's "keys" push to TV Setup
(`homebar` is kept in `HomeKeys`), and with it off the filter is not asked about Home for the bar.

## Home pressed: the cases

| # | Told apart by | Then | Test |
|---|---|---|---|
| 1 | option off | `KeysBridge` plan has no bar: Home as before (the swap, or the key way's rule `homekey`) | |
| 2 | option on, an app in front | the bar (`KeyFix.BAR`) | `anAppInFrontGetsTheBar` |
| 3 | option on, TV Home in front (home screen, or the bar itself) | Home as before: the rule `homekey`'s launcher, else the HOME intent; from the bar this is the second Home, the bar closes in `onStop` | `aHomeScreenInFrontLetsHomeThrough` |
| 4 | option on, nothing known in front, or the system's bars | Home as before | `nothingKnownOrTheSystemsBarsLetHomeThrough` |
| 5 | Home held 500 ms | the TV's own long press, as in the key way (`HomeKey.longPress`) | |
| 6 | the add-on not installed or not on (no key filter) | Home as before; the page's text says the bar needs TV Setup | |
| 7 | screen off | the add-on does not take keys while it is off: Home wakes the TV as before | |
| 8 | a client's Home: the TV web page (`/api/key` -> `KeysBridge.press`) or the Computer app's ADB fallback (`RulesClient.key`, only when its remote link is down) | the same decision as a real press: option on and an app in front: the bar (`KeysBridge.press` -> `homeBar`); option off: injected, Home as before. The Phone app's ADB link and `input keyevent` skip the filter: no bar there | |

## In the bar

OK on an item opens it (a leanback or launcher intent) and closes the bar; Back closes it only. The apps are `dock` (first
`DOCK_MAX`, apps only) then HomeInfo `recent` (`RECENT_MAX`, event order, never by time) without the dock's; an app behind TV Home's PIN
is left out, and so is the app under the bar (TV Setup passes it as `front`: picking it only restarted it); TV Setup missing: the dock alone. Last, a slim ⚙ (row height, no label) opens Customize at `keys`, where the bar is switched off. The bar scrolls sideways when there are more than the screen holds.

## Android versions

9 and 14 take the same path (a see-through activity of TV Home started by TV Setup); on 14 it needs the appop above, which every ADB
link sets. Without TV Setup nothing starts it, so there is no bar and Home is the system's.

## Other keys (not Home)

A key given "Voice command" (target `do:voice`, `KeyFix.VOICE`, `SetupNeed` "voicekey") opens TV Setup's `MicActivity` from the key
filter, the same way the bar is started; the key is taken only when a voice recognizer is on the TV. Home is never such a key.

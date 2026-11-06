# dev/flows: what the apps do, case by case

A flow file is the one place that says, for a feature with many TV-dependent paths, every case: how it is told apart, what is
done, what the person sees. Code follows the flow, not the other way round.

**Before changing code a flow covers:** read the flow, find the step (the row) the fault belongs to, change that step. A new case
is a new row. **In the same commit:** update the flow so it says what the code now does.

The gate (`node dev/flows/check.js`, in the pre-push hook and `app/shared/tests/flows.test.js`):
- every `path` and `path:Symbol` a flow names must exist (a renamed function breaks it);
- each flow's `Cases:` list must equal the code's own list (for the home screen: `LauncherSwap.WHYS`);
- a commit that changes a file listed under a flow's **Code** without changing the flow fails the push, unless its message has
  a line `Flow: unchanged (<why>)` (a typo, a log line: nothing a row describes).

| Flow | Covers |
|---|---|
| [home-screen.md](home-screen.md) | another launcher (TV Home, the gallery's) as the home screen: Optimize TV, the launcher gallery, the Settings page, the way back |
| [home-bar.md](home-bar.md) | the option "Home key in an app: show a bar": TV Setup's key filter, TV Home's see-through bar, the cases of a Home press |
| [home-tiles.md](home-tiles.md) | TV Home's grid: tile kinds, their menu, hide / remove / folders, and the checks (fuzz test, `home_tiles.sh` on a TV) |
| [voice.md](voice.md) | the remote's mic button: the two switches, who listens (the TV's recognizer or TV Setup Voice) per TV, network and stall |
| [scene-timer.md](scene-timer.md) | a scene with a start time, run by TV Setup: standby, deep sleep, no ADB, clock jumps, time zones |
| [blocked-apps.md](blocked-apps.md) | apps another package suspended ("App isn't available"): who blocked them, per Android version and user, and what Unblock does |

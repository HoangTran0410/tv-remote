# Where Optimize TV's brand knowledge comes from

`app/shared/.../core/ontv/Bloat.kt` (suggested packages, the KEEP list) and the Google TV home swap in `Setup.kt` (setupwraith
before launcherx, check Home, roll back) come from these. Read them again now and then and bring over what is new: into `docs/optimize/bloat.json` (then
`dev/sign/sign_bloat.py`, commit, push: every app reads it from the site when Optimize TV opens, no release needed), and now and
then fold that list into Bloat.kt and empty it. The site's list can add suggestions, drop them, and only add to keep / factory.

Last read: **2026-11-10**, upstream `3e06c1b2` (394 commits after `3c122bc073`), vgs-cleantv 4.0.9 (it republishes upstream's
lists). Into bloat.json: DROP Sony `sonybugreportsys` / `idd.agent` (issue 3608: off, USB Ethernet dies on a Sony VH2; their shared
system uid holds NETWORK_SETTINGS; upstream PR 3609). Checked, already right: Undo turns Home screens on before the wizard (3578: setupwraith
on with launcherx off ended in a factory reset; PRs 3620 / 3653 do the same). Noted, not acted on: 3446 / PR 3680 (setupwraith off kills
casting on a TCL G10: the price of the Home swap, as 2991), 3533 (`com.tcl.usercenter` off: reboot on wake; we never suggest it),
3635 / 3478 / 3508 (stock Home back after a reboot on MiTV / TCL 55P78E), 3613 (XGIMI gear key: a second scancode). ~90 new packages
on one list each (factory menus, TCL, Haier/CVTE, Google preinstalls) not taken yet: one list each, not read line by line.
Before that: **2026-11-06**, upstream `3c122bc073` (573 commits after `3cd274f866`; 82 `debloat.txt` changed, 69 packages no list had,
all on one list each). Into bloat.json: SUGGEST CVTE `tv.infoprovider` (device info reports) / `tv.remotedebug` (a support screen
recorder), KTC `kgzn.inforeport`, `zeasn.deviceportal.asdprovider.ktc` (ads), Jio `stbadservice` / `systemstats` / `rjil.podlogservice`,
`haier.apps.evoSmart`, `sony.dtv.kenhchonloc` (a shop promo, Vietnamese); KEEP `com.tcl.assistant` (TCL BeyondTV's remote mic, from
vgscity/vgs-cleantv 3.5.15); FACTORY Chihi `com.hx.*test`. Not taken: `jp.sony.mybravia` (a home row: a choice), Jio store, Yandex Alice,
LocalCast, DLNA servers, `mk.tv.booster`, KTC launcher / cast, Sony browser / DIAL, AOSP stubs. Issues noted, no code change: 3313 / 3367 /
3304 (key helper enabled but unbound after a reboot: KeyFix already reads the short form Android writes once bound), 3287 (KTC m7332: the
remote is dead after boot until `com.ktc.tvplayer/.activity.RootActivity` opened once; no KTC to try), 3330 (`pm enable` launcherx can
hold Home for good), 3085 (`mitv.service` off: picture menu 30-40 s; not suggested).
Before that: **2026-11-02 (evening)**, upstream main still `3cd274f866`; read the open PRs since 06:00 (3020, 3023-3026, 3035, 3039, 3041, 3043,
3050, 3054, 3062-3065, 3070 and the SHOWCASE ones, which have no lists) and issues 2831, 2853, 2856, 2870, 2925, 2948, 2950, 2973, 2991, 3001, 3037
(2930 has no comments). Into bloat.json: SUGGEST Hisense China `hotelmenu`, `vision.aibook`, Migu `ju.miguvideoTV.cmcc` (preloads, one list
each), Changhong `homwee.store` (a retail-demo receiver, not a store). KEEP Philips `io.mimi.sdk.service` / `tvapp` (HBO Max dropped 33-46 % of
its frames with them off), Skyworth `com.airoha.ble.ota` (the Bluetooth remote's updater), `com.android.sdksandbox`, the Hisense VIDAA China chain from the 85E7Q (voice:
`hispeech`, `keylab.speech.*`, `iflytek.xiri`, `intelligent.speech`, `msvoicetome`, `vidaaassistant` which also handles installs and
permissions; `aiot`, `aiod`, `raycodereceiver`, `hiservice`, `globalui`, `vt.permission.manager`, `widget.pro`). Already ours: `tiletransition`
(HDMI rows switch nothing without it), the Sogou keyboard, `demome` (TV_INPUT_HARDWARE: `Setup` skips it). Not taken: `com.meetingroom` (answers
HOME at -100: Home swap business), `jamdeo.calendar`, TV Booster `mk.tv.booster` (a cleaner), `tv.fun.tvupgrade` / `tpv.xmic.ota` (firmware
updaters, off or on by choice), the AOSP / Google parts the new Skyworth, onn and Hisense lists switch off (already not suggested: user apps
or a choice), streaming and FAST apps. Issues: 2950 (Fire TV stick stops opening Settings after OTA 8.1.8.5 with a list applied: no package
isolated, upstream only warns in the header; nothing of ours suggested near it), 2991 (Google Home control dies after a reboot with
`setupwraith` off; workaround: enable it, restart mediashell, disable again) and 2853 (Sony: `sonysystemservice` polls twice a second with it
off): both are the price of our Google TV Home swap (`Setup.WIZARD`, off before launcherx), the same fact as 2812 below; not acted on, no
package to move. 2948 (MFTR0 standby LED: 关机 is standby on OS3.0.106.0, no package), 3001 (PS5 flicker with a custom launcher as Home, open,
asks for tests), 2973 (known, `LauncherSwap`; upstream's `pm list users` safety check is undecided), 3037, 2925, 2870, 2856, 2831 (launcherx +
`com.android.sdksandbox` off on a REA75P: the sdksandbox line is ours too? it is not in SUGGEST, and `disable.sh` refuses it upstream): no change.
Before that: **2026-11-02 (midday)**, upstream at `3cd274f866` (165 commits after `6e8615eb0b`; 38 `debloat.txt` changed, 11 new; no new PRs or
issues). Into bloat.json: SUGGEST the Android 14 Privacy Sandbox trio `com.android.adservices.api`, `ondevicepersonalization.services`,
`federatedcompute.services` (ad topics / measurement, off on a Xiaomi projector and an onn 4K Pro), `com.google.android.partnersetup`
(RLZ attribution pings, off on 4 lists), `android.autoinstalls.config.fomovie` (Xiaomi projector installer), manuals `xiaomi.mitv.manualhelp`
and `haier.emanual`. Not taken: Acer `com.apps.*` (gdpr / clear: as before), Philips NetTV market apps, VPN / game apps (the owner's own),
`dreamx`, `tvrecommendations`, `backdrop` (screensaver and home rows: a choice), Xiaomi `mitv.toolhouse` / `tvqs.overseas` / `milegal`,
`tcl.messagebox` / `usercenter` / `smartalexa`, Sony sensing camera, Fire OS parts, Haier launcher.
Before that: **2026-11-02**, upstream at `6e8615eb0b` (725 commits after `42fe7b09d6`; 96 `debloat.txt` changed, 47 new, 407 in all;
66 device notes; 123 forks with their own commits, none adding a package upstream lacks; TVSlim unchanged at `19e72fb`). Into bloat.json:
SUGGEST `com.vt.tv.evservice` (Vu / Hisense ACR viewing data), `com.zeasn.services.general` (SHOW_AD, feed), `com.coocaa.messageprovider`
(promo pushes), `com.htc.storeos` (an app store), `com.vestel.smartcenter` (no UI, GET_TASKS + INTERNET), `com.sony.dtv.sonytab` (partner
rows); each off on 4-15 models, kept on none. Into the home swap: issue 2973 (a Coocaa Y74 Pro, Google TV 14: with launcherx off
Skyworth's persistent `com.smartdevice.global.service` throws on every hotkey once the TV has a second user, RescueParty resets settings,
then a factory-reset prompt): `LauncherSwap` takes Home by the key filter there (flow row 3b, via `users`). Not taken: cleaners
(`tcl.tvweishi`, `haier.tvhousekeeper`, `cvte.tv.steward`, `apps.clear`, `vestel.tv.speedup`: no data or ads named), Amazon's Fire OS
parts (`gamehub`, `cloud9`, `kindleconnect`, Alexa pieces: features someone uses), `privacypassservice`, `ffsprovisioneeclient`,
setup / disclaimer screens, Sony `usercontext` and `*recommendation*` (kept on one list; feed the home rows), `tcl.tv.plus` and other
FAST channels (a choice), AOSP core (`htmlviewer`, `backupconfirm`, CTS shims, health connect). Fact not acted on: with launcherx off
Gemini / Assistant answers have nowhere to show ("having a problem processing your request", issue 2812, a Lumio Android 14): the
swap's price on Google TV; the key way keeps launcherx on.
Before that: **2026-10-29 (evening)**, upstream at `42fe7b09d6` (230 commits after `985bbd14`; mostly addons/, the launcher's Halloween
look, tests; 27 `debloat.txt`, 6 new, 81 packages no list had). Into bloat.json: SUGGEST Sharp `sharplabs.dmt.aas.analyticsservice`
(telemetry), `jp.co.sharp.av.android.epopdemo` (shop demo); KEEP `com.youview.poa` (Freeview Play: off took BBC iPlayer, ITVX,
Channel 4 and 5 off a UK Sony, they are HbbTV web apps it opens). Already ours: TCL `showmode` (sound modes, upstream now keeps it too).
Not taken: Sony `sonyselect` / `discovery` / `bivlinfo` (store and content rows: choices), the rest of Sony `b2b.*`, BRAVIA CAM parts,
DLNA / Miracast / AirCast receivers, TCL `channelplus` (an owner turned it back on: its FAST channels leave Live TV), `messagebox`,
`appmarket2`, `onetimeinitializer`, Google `katniss` / `play.games` / `videos` / `music` (user apps), Philips NetTV market apps.
Facts not acted on (no such TV to try on): a Xiaomi Mi TV 4A's firmware GlobalKey answers Input (178) by starting tvlauncher's
`InputsPanelActivity` by name, so with tvlauncher off the key is dead while its handler is alive (our KeyFix takes a key only when
its global handler is gone: #2446); on an onn 4K box and a Hisense lushan the gear sends KEYCODE_NOTIFICATION (83), answered by
launcherx, dead with it off; Xiaomi China firmware (MiTV-MFTR0) injects a short Menu press as a cancelled pair, flags 0x7531 (#1637).
Before that: **2026-10-29**, upstream at `985bbd14` (272 commits after `6ffa7fa5`, v3.1; mostly addons/ and launcher/; 36 `debloat.txt`,
14 new, 35 packages no list had before). Into bloat.json: SUGGEST Sharp `analyticsservice`, `storedemoapplication`, `cocorovision`
(screen / key reports), the PAI installers `autoinstalls.config.sharp.av` / `.vestel.malin`, `hotack.screensaversdk` (ad screensaver);
KEEP `com.android.toofifi` (the AirPlay / Cast / DLNA receiver, hotack-l018), `com.dangbei.dbdownloadrefs` (off: system_server dies,
#2286), ZTE `com.control.device` / `ztestb.basecomp` / `tv.upgrade.service` (#2318). Into LauncherSwap: a ZTE box with
`com.control.device` (B866F) factory-resets a minute after a boot with no system launcher on, so Home goes by TV Setup's key filter there
(`keyOnly`), as on a Chromecast. Not taken: Sharp portals and services (`aquoscity`, `chranking`, `cocoroguide`, `tvremote`, the camera,
DLNA), Coocaa themes, casting / DLNA / Miracast receivers, ANT+, `htc.clearmemory`, `mtc.emanual` (the FAV key opens it), `nordvpn`,
Paravi / U-NEXT, `zeasn ... vstoresubclient` (maybe the only store). Facts not acted on: China-market Xiaomi (`nemo`, PatchWall,
`ro.formovie.launcher_type`) drops every non-system HOME (#2415: TV Home cannot be Home there, our check after the swap rolls it back);
after a system_server hang `dumpsys` prints "Service dumps disabled" until a reboot; a local-VPN ad blocker dies after boot on small
TVs (cached-app kill). Non-Android TVs (Samsung, LG, Roku, Vizio, VIDAA): upstream has no tool, only cleanyour.tv/not-android, menu
paths to turn ACR / ads off by hand plus DNS blocklists (HaGeZi per brand).
Before that: **2026-10-27**, upstream at `6ffa7fa5` (388 commits after `f592b6d2db`). Into bloat.json: SUGGEST PPTV `newtvad` (ads) /
`push.service`, `inspur.adservice` (operator ads), `smartdevice.demonow` (shop demo); FACTORY `arcelik.tv.rctest` (the other new factory
apps match FACTORY already). Not suggested: TCL `interactive` / `ttvs` (upstream calls them analytics; we keep them: the Source key,
728595ce). Into Setup.PROTECTED: the bare AOSP `android.ext.services` / `android.ext.shared` (#1711). Checked, ours had it: setupwraith
answering HOME before launcherx (#1573, `LauncherSwap.badHome`), its Wi-Fi network (`Setup.kt`). New brand lists not taken yet: Sber,
Grundig, Panasonic, Strong, Dyon, PPTV, Home+.
Before that: **2026-10-26**, upstream at `f592b6d2db` (384 commits after `67dc5bee16`, mostly addons/; 67 `debloat.txt`, 16 new). Into bloat.json:
SUGGEST Philips `tpv.asc.taadserver` (ads + telemetry), `funshion.publicity` (promo), `atmaca.atmretailmode` (retail demo); KEEP
`tcl.providers.config` (upstream "never disable, load-bearing" in 4 TCL lists), `tpv.tvinput` (the only TvInputService on a 50PUF7099:
off = no inputs), Realtek `dtv` / `tv.atv` (inputs), `realsil.ota` (the remote's updates); FACTORY NVIDIA `factory*` / `diagtools` /
`developerwidget`, `mediatek.testpq`, `xgimi.minitvfactory`, `skyworthdigital.remotedebug`. Already kept by our patterns, so not
suggested: `skyworthdigital.optimization`, `mediatek.tv.retaildemo`, `MtkDemo`. Held back: TCL `messagebox` / `guard` / `dashboard`
(some owners keep them; `guard` is the only on-screen installer on an AK30A5), `tcl.waterfall.overseas` (a 55P635 boot-looped),
`tpv.app.recommendation` (home rows stay a choice). kalley-r10g dropped `launcherx` + `setupwraith` (#1807: both off, system_server
crashed every boot). Key facts: TCL BeyondTV2's mic is KEYCODE_SEARCH then a click on Home's voice button (#1869); a Netflix key
(4062) on TCL UnionTV is a protected broadcast no key filter sees (#1967); a bound key filter eats a held volume key's repeat (#1878).
Before that: **2026-10-25 (evening)**, upstream at `67dc5bee16` (99 commits after `575d73f0f3`; 12 `debloat.txt`). Into bloat.json:
SUGGEST `jiumao.screen.systemadplayer` (ad player, hy320), Philips `customautologservice` (logging), `tpv.nftgallery`; KEEP Sony
`scrums.action` (the Action Menu and its remote button: upstream un-disabled it on kd-43x8000g, it was mislabelled "usage metrics"),
Philips `ipcontrol` (LAN control, kept by a 65PUS9206 owner); FACTORY (never touched, cleanyourtv #470): Skyworth `testtime`, Allwinner
`dragonbox` / `agingdragonbox` / `dragonatt`, `htc.hyk_test`, `oem.qa`, `sdt.producttest`. Left out: Fire OS internals (`dpcclient`,
`spiderpork`, `neodelegate`, `cirruscloud`, `ivona.*` TTS, `dummy.*` stubs: roles unclear), stores (`master.tv.market`,
`overseas.store.appstore`, `nettv.market`: maybe the only one), Philips `nettvapp*` / `fitness` / Play-Fi (choices), `tcl.esticker`
(upstream disabled it a day only). Facts not acted on yet: Sony VU3 app keys die with launcherx off (scancodes 758 569 653 652); a
key helper eats a held volume key's repeat (Android 12 KeyboardInterceptor); TCL needs `appops set <pkg> APP_AUTO_START allow` for a
third-party screensaver; TCL 32S5400A keeps 500 MB free, `pm trim-caches 4G` lets installs through.
Before that: **2026-10-25**, upstream `Gathin23/cleanyourtv` at `575d73f0f3` (2026-10-25 07:50 UTC, 409 commits after `c965ef4b11`;
66 new or changed `debloat.txt`, 173 distinct packages in their added lines, 141 that nothing of ours held; issues / PRs up to ~#1600).
Went to `docs/optimize/bloat.json` (9 suggested, 6 keep patterns), not Bloat.kt. SUGGEST: Realtek `storemode` (retail demo, kogan-g7),
`karaoke.userguide`, `android.autoinstalls.config.RTKSTBAndroid.device` (partner app installer, rocktek-gx1); Hisense / Vidda `hitv.das`
(telemetry) and `jamdeo.screensaver` (ad screensaver; two cold reboots, vidda-c1s); Sony `touchpad.tutorial`, `shopsettings` (retail demo);
TCL `c2dm.client` (push); `google.android.tvtutorials` (4 lists). KEEP: `com.google.android.tv.remote.service` (the G10 list now keeps it:
disabled, the remote's mic button dies, `AtvSearchAssistantService not found`; it is also what the Android TV Remote protocol on 6466 / 6467
talks to, so our Phone / Computer apps' keys without ADB would die too); Sony `b2b.hotelmode` (disabled: TV Settings opens blank, the
settings provider reads it; kdl-43w950d, kd-55x8000c); Realtek `slices` (Settings slices; kogan-g7, against rocktek-gx1 which turns it
off: KEEP wins); Philips `org.droidtv.tv.agent`; the Anker Nebula projector's `oceanwing.(receiver|nebulalinker)` and
`zhixin.(nebulasmart|remoteautopair|apphelper|projector.settings|nebulasetupwizard)` (remote, accessibility, pairing, keystone). Left out
(about 120): streaming / user apps (Netflix, Disney+, Prime, HBO, Pluto, Hotstar, Spotify, VLC, ES / file managers, MX Player), Google core
(katniss, tvlauncher, tvrecommendations, play.games, tts, setupwraith, launcherx, mediashell, backdrop, partnersetup), AOSP and mainline
(dcolor-gd2 and panavox-google-tv turn off adservices / federatedcompute / healthconnect / backup: as before), Sony `b2b.*` other than
hotelmode (hotel / pro), DLNA / Miracast / Wi-Fi Direct (casting), `osat.*` (USB players), `tvcamera`, WoL services,
`indiametarecommendation` and `tvlaunchercustomizer` (Sony recommendation / home rows stay a choice), TCL's Chinese content (`ffkaraoke`,
`ffeducation`, `vod`, `tliveplay`, `weixin`, `browser`, `wholenetsearch`, `gamebar`, `common.weather`, `notereminder`), Xiaomi `mitv.tvlock`,
`milinkservice`, `smartshare`, `mimusic2`, Hisense `hotelmenu` / `autotest` / `ar.baike` / `foodmanage` / `calendar`, games (`doodle`,
`vectorunit`, `xformgames`), `konka.livewallpaper`, `miniTV` (a choice). Owners' notes: TCL `overseasappshow` re-enables promo packages
at boot (tcl-43p725), `com.cltv.fast` and Haier `gamingbar` come back at boot (Re-optimize covers it). Forks that moved since 2026-10-24: briansilah (daily launcher improvements, nothing for the lists),
maydings/vibe-launcher (2.0 - 2.3 releases, ported `InputOpen` pinning that we already have), itsmeterrylin (Fire TV AFTKM: only
`minitv` new, a choice), thefirstparth (no commits); ~520 other forks only mirror upstream (not compared one by one). Before that:
**2026-10-24**, upstream `Gathin23/cleanyourtv` at `c965ef4b11` (new since `f9f0c5353e`: 12 debloat.txt, 85 packages that
nothing of ours held; Acer ar55ar2851udpro, Haier hr9216eu, Hotack smart2kpjt, Master-G mga3200, Philips 65oled706 / 65pml9506, RCA
rc50a21s, Sankey hi3751v350, Thomson 32he5800, Xiaomi aesp0, TCL 43s5k). SUGGEST (10, pure ads / tracking / promo): Philips
`nettvrecommender` + `recommendation` (Top Picks row, philips-65oled706 / 65pml9506; the mainline three acer / hotack add stay
in "Not taken"), `apps.eshow` (store demo), `apps.mk.wd`
(ClickAd boot receiver), `cvte.industries` (push / ad receiver), `whaletv.logger`, `zeasn.tokenapp` (key tracker),
`zeasn.deviceportal.asdprovider.toptech` / `.certified` (ad portal, rca / hotack), `haier.apps` (promo). KEEP (8 patterns): `printspooler`,
`aml.dial` / `netflix.dial` (cast), `apps.scast`, `creative.fastscreen.tv` (screen mirroring), `sss.authbridge` (set up from phone),
`talkback`, calendar `providers` / `syncadapters` (accounts). Left to "Turn off other apps" (a choice or mixed): streaming and
content apps (`tv.wuaki.apptv` Rakuten TV, Netflix + `tokenmanager`, `boosteroidtv`, `zeasn.whalelive`, Philips `nettvapp*` /
`nettv.market`), TCL `partnercustomizer` and `waterfall.overseas` (one boot loop on a 55P635), `haier.oemtabprovider` and Zeasn
launchers (home), `google.apps.tv.dreamx`, `dreams.*`, `leanbacklauncher.recommendations`, `onetimeinitializer`, `katniss`, Mimi sound,
Philips Alexa / HD+ / M7 / Dropbox, `music` / `musicfx` / `webview_shell` / `health connect` / `wallpaperbackup`, `apps.*` /
`mk.tv.*` Acer-Master-G helpers (gdpr, channels, clear, usbmediaplayer, networkspeed, wifihotspot, audiolink, meeting.service),
factory apps (`stm.factorytest`: the FACTORY pattern). Each merged list is one person's choice on one TV, so only what is plainly
ads / tracking / promo was taken. Before that: upstream `f9f0c5353e` (2026-10-23 16:19 UTC, 252 lists, 1275 packages): no list
disables what we suggest while its notes say keep; the ads / telemetry-like names in 2+ lists that we do not suggest are all in "Not
taken" below (`exhibit`, Philips `nettvrecommender` / `recommendation`, Sony recommendation services, `remotecontrol.logging`).
TROUBLESHOOTING (#1188): a batch is undone when the TV reboots within ~10 s of the last `pm` (package state is written lazily): wait
15 s before a reboot. Before that: 2026-10-22 at `0ed6c5bccc` (2026-10-22 07:27 UTC; no list changed since `0bb7f7edbf`,
only the CVTE home rule #741, kept in KEEP, and the Accounts page #304, in the guide's TV Home topic), PRs up to ~#883 (before that: 2026-10-22 at `0bb7f7edbf`, PRs ~#811, issues ~#833; 2026-10-21 at `9071ec8d2f`, PRs ~#573, issues ~#564; 2026-10-20 at `fd1ee0a94c`, PRs ~#398, issues ~#376; 2026-10-19 at `7377a9be8c`, PRs up to #125, issues up to #120, 388 forks). The project is days old:
most reports are one TV, one day.

UAD read 2026-10-22 (UAD-NG main): of our 142 suggestions 12 are in its list, one marked Expert: `com.xm.webcontent` ("app installing" messages and WebView), dropped in `bloat.json`. Its Unsafe / Expert TV packages that nothing kept yet went to keep: `com.google.android.tv` (Live Channels, the tuner), `tv.axel` (the IR service, volume setup), the `frameworkpackagestubs`. Taken as suggest: Sony `notificationcenter` (nags to use Sony's apps), `smarthelp`. Its TCL entries are phones (camera, compass, SOS): not taken.

TV Slim read 2026-10-24 ([jolabs40/TVSlim](https://github.com/jolabs40/TVSlim) `19e72fb`, Apache-2.0; `TVSlim/core/src/main/assets/catalogue.json`,
140 entries with a risk each + 61 "protected"; the list packed in `TVSlim-TV-1.1.0.apk`, unpacked with `rea extract-artifact`, is byte for
byte the source's). Made from one TCL 65C89K and an NVIDIA Shield. 20 of its entries we suggest too; none of our suggestions is in its
protected list. Its protected list that nothing kept went to keep: `location.fused` (boot loop when off; a cleanyourtv list says the
same), `devicelockcontroller`, the Shield's core services (display, audio, remote and controller pairing, IR, `nvidia.ota`), Philips
`tpvaudiomanager` / `tpvvideomanager` / `productfeatures` / `ui.strings*` / `bluetoothremote`; also `xiaomi.mitv.updateservice` (firmware
updates, as `mitv.upgrade`). Taken as suggest (tested on its Shield): `nvidia.app.messaging`, `shield.registration`, `shield.welcome`.
Not taken: what it turns off that we keep (TCL `dashboard`, `eva`, `guard`, `initsetup`, `interactive`, `ttvs`, `useragreement`,
`factory.view`, Philips `eum`, `aurora`, `xtv`, `sunrise`, MediaTek AirPlay, the Shield's factory apps: the reasons are below), its own
`waterfall.overseas` note agrees (one boot loop on a 55P635); streaming apps, keyboards, Google core and mainline modules (a choice, see
below); Shizuku (a user's app: keep would stop the Apps tab removing it), `com.tpa.kb` (no reason given).

## Sources

| Repo | What is in it |
|---|---|
| [jolabs40/TVSlim](https://github.com/jolabs40/TVSlim) (Apache-2.0, tvslim.app) | `TVSlim/core/src/main/assets/catalogue.json`: entries with a risk (aucun / faible / moyen / eleve) and side effect, `proteges` (never touch, with the reason), known launchers, a few settings |
| [Gathin23/cleanyourtv](https://github.com/Gathin23/cleanyourtv) (was `tv-vibehack`, MIT) | `examples/<tv>/debloat.txt` for ~107 TVs (78 new or changed since 2026-10-19: Sony x15, TCL x15, Philips, Hisense / Vidda / Vu, Xiaomi, Haier, Panasonic, Skyworth, Hitachi, Freebox, JMGO / Wanbo projectors, Fire TV sticks, NVIDIA Shield, onn, CHiQ / AIWA / BenQ "AI PONT"), README "Troubleshooting", PRs and issues with owners' reports |
| [pulgagr/tv-vibehack-firetv](https://github.com/pulgagr/tv-vibehack-firetv) | Fire OS: what can and cannot be turned off, why Home cannot be taken |
| [itsmeterrylin/fire-tv-cleanup](https://github.com/itsmeterrylin/fire-tv-cleanup), FreedomFleming/tv-vibehack | Fire OS 8 |
| [SuryAdelia/tv-vibehack-coocaa](https://github.com/SuryAdelia/tv-vibehack-coocaa) | Coocaa / Skyworth Google TV (not compared yet) |
| [Universal-Debloater-Alliance/universal-android-debloater-next-generation](https://github.com/Universal-Debloater-Alliance/universal-android-debloater-next-generation) (fork of 0x192/universal-android-debloater, which stopped in 2024; GPL-3) | `resources/assets/uad_lists.json`: ~5400 packages, each Recommended / Advanced / Expert / Unsafe. Mostly phones; ~90 TV ones (Google TV, Sony `dtv`, Xiaomi `mitv`). GPL-3 against our MIT: take package names and its warnings, never its descriptions |
| [OmkarK97/webos-vibehack](https://github.com/OmkarK97/webos-vibehack) | LG webOS port (launcher only) |
| [mclhm8182/tvhome-zh](https://github.com/mclhm8182/tvhome-zh) | Chinese version |
| kadeemj, skuhns (Hisense Google TV), tv7 (TCL C6K), EndLess728/MiTV-Debloat, manhashed, nikhilbadyal (Xiaomi), maydings/vibe-launcher, manojm321/jain-tv-launcher, Musicality59 (Sony), codewithbalaji (OnePlus), picajoso (Onn 4K Plus), thefirstparth (`LESSONS.md`) | forks with their own `debloat.txt` or notes |
| Upstream PRs #38 #45 #73 #78 #95 #100 #104, issues #30 #37 #49 #53 #62 #79 #83 #85 #90 #92 #101 | the reports quoted below |

Second read (2026-10-20), taken as KEEP (an owner lost something) and as SUGGEST (disabled and reported fine by the list's owner;
inputs, tuner, system, settings, keyboard, Bluetooth remote, HDMI-CEC, Play services never):
- Hisense / Vidda / Vu: `tiletransition` (HDMI did nothing without it), the smartsense / aiot family (a Hisense went to Android
  Recovery, #45 and hisense-u75, vu-4k-lushan), the VIDAA tuner / input / commonui, the Sogou keyboard (the only one), kpad, favkey.
- Philips: `org.droidtv.*` is the TV engine; `eum` hangs it, `aurora` makes xtv retry in a loop (#234), `nettvbrowser` is HbbTV.
- TCL / Thomson / Hitachi: settings, tvpassthrough, sourcemananger, the key services, TFDS.service, tvos (the Input key, sources).
- Sony: tvapi, smartui (Input menu), tvlin, tvplayer, rcpowerkey, systemupdate, IR blaster; Xiaomi: tvsetup.partnercustomizer
  (BLE remote pairing), mitv.res (the tvqs quick-settings panel is NOT suggested: a Mi Box S owner uses it).
- Realtek, Homwee, CVTE / Haier, Skyworth, Freebox, JMGO, Wanbo, onn, Fire TV sets: the tuner, factory and key services;
  `fusion.android.tv.demo` (MtcRetailDisplay) looks like a retail demo and is the Input button.
- Setup.kt against #350 (the launcher that owns Home stays until another takes it: LauncherSwap opens the new one first and
  rolls back), #214 (setupwraith owns Wi-Fi: only its HOME component goes off, never the package). Not covered: #344 (remote's
  Input / Settings / app buttons dead once launcherx is off; needs a key remap), #339 (prefer the digital tuner, check
  `remote/Inputs.kt`), TVs whose shell refuses to disable the wizard's component (xiaomi mosr4).
- Left out: com.tcl.messagebox (carries the ttvs receiver), exhibit, waterfall.overseas, ttvs (firmware updater + Samba),
  tv.anoki.acr.controller was taken, but `com.tcl.guard` (the only APK installer on China TCL) and `partnercustomizer` stay.

Forks ahead of upstream on 2026-10-19 (owner/repo +commits): briansilah +76, thefirstparth +63, maydings/vibe-launcher +53,
llm4041 +9, erinaldo123araujo-blip +9, quicodomingo +8, G41US +8, ashwkun +8, InclinedTaco +6, gjr08 +6, Joaquinfnz +5,
FreedomFleming +5, tv7 +4, thomasmihaljevic27 +4, Polat477 +4, kadeemj +4, EndLess728/MiTV-Debloat +4, abnkei +4,
4kfiles85 +4, tqchagas +3, roninriddle +3, nikhilbadyal +3, mbruch91/tv-vibehack-Epson-Beamer +3, manojm321/jain-tv-launcher +3,
picajoso/tv-vibehack-javipas +2, Manohith22/cleanyourtv +2, kripors +2, JustHarrisVu +2, imanshu03 +2, bramsuurdje +2,
20nxskypqz +2, wahabxoxo +1, shashi-deep +1, nanda12896 +1, Musicality59/tv-vibehack-oqee +1, manhashed +1,
itsmeterrylin/fire-tv-cleanup +1, essremodel +1, codewithbalaji +1, 9000000 +1 (repo `tv-vibehack` unless named).

## Taken

- **Google TV home**: launcherx answers HOME at priority 2, setupwraith's RecoveryActivity at 1, any installed app at 0,
  FallbackHome at -1000. launcherx off alone → Home lands on RecoveryActivity, system_server crash-loops (`FATAL EXCEPTION IN
  SYSTEM PROCESS: android.display`), Wi-Fi drops every minute, one Sony needed a factory reset (#62, #90). A single component
  is turned off where the shell may; where it may not (`Shell cannot change component state`) the whole setupwraith package, as
  cleanyourtv does, only when `dumpsys wifi` shows no `cname=` of its own (Android deletes those networks with it; forget + re-join
  in Settings hands them to `android.uid.system`), never on sabrina / boreal / kirkwood (it holds Cast's CastInfoProvider: "Untrusted
  device"), first of all and turned on last; it is put back by Undo (a factory reset needs it).
  `set-home-activity` is ignored while launcherx is on and forgotten whenever the set of homes changes: set it last. Fix: `pm
  enable --user 0 com.google.android.apps.tv.launcherx`.
- Only turn off home screens that are on; Google TV ships tvlauncher off.
- One `pm` per shell call: chained, some TVs stopped after the first.
- A default keyboard that is not installed (Baidu on Xiaomi) leaves no keyboard: `ime set`.
- `screencap` on some MediaTek Xiaomi prints `Init wrapper sys mutex successful` before the PNG.
- Suggested by default (telemetry, ads, demo, auto-install only) and KEEP: see `Bloat.kt`, with the brand evidence:
  TCL `suspension` (Input key died, #73), `providers.config` / `initsetup` / `factory.view` (Wi-Fi), `tcl_bt_rcu_service` /
  `autopair` (remote); Xiaomi `com.mitv.livetv` owns the source switcher (#53); `leanbacklauncher.partnercustomizer` gone →
  `com.mediatek.tv.service` crash-loops after the next restart (#30 range); Sony `tvx`, `tvinput.*`, `braviasync*` (#92, #104);
  Fire TV `venezia` (app updates), `tv.settings.v2`, `whisperlink` / `whisperjoin` (casting), Fire OS 8 `aria` (Alexa).

Third read (2026-10-21, 47 new or changed `examples/*/debloat.txt`):
- SUGGEST: the Play Auto Install configs of Hisense, HKC, Sharp, SCBC, SDMC and Vestel; Sony `demomode`; Xiaomi `tvqs`,
  `misightservice`, `cloudcontrol` (pushes apps); TCL `smartlink.core` (silent installer); Philips `sticker`, `frameworklogger`;
  Fire TV `telemetry.emitter`, `democontent.provider`; the loggers / ad services of Vestel, CVTE, XGIMI, Verizon, HTC, Coocaa's demo.
- KEEP: every `*.partnercustomizer` (TCL: the stock home's Inputs icon and remote keys; Realtek: the Inputs button);
  Xiaomi `tvmanager` **moved out of SUGGEST** (XiaoAI's "open <app>" goes through it, xiaomi-mitv-mffu0), upgrade / tvplayer /
  settings / smartpanel / PatchWall `com.mitv.tvhome` (the only home on some); TCL appstatecontroller, rc.ota, UpdatePeripheral,
  useragreement, copydatatotv, guard (TCL China's only installer), cyberui (TCL China's only home); `WifiCountryService`; Philips
  fastip (the SOURCES drawer), exoplayerservice, usbbreakin, dlna; Vestel `tv.*` / `overlay.*`; CVTE tvservice and settings;
  XGIMI keystone / focus / keys; SDMC, Skyworth SDT / HiSilicon, Kalley `com.apps` / `com.mk` key services.

Fourth read (2026-10-22, 483 commits; 83 new or changed `examples/*/debloat.txt`, 626 distinct packages: 70 already suggested,
42 already in KEEP, 2 factory, 512 new). Went to `docs/optimize/bloat.json` (35 suggested, 3 keep patterns), not Bloat.kt:
- SUGGEST: Play Auto Install configs of Askey, Hisense huangshan, Homwee, Realtek, R+, Xiaomi mibox3; Fire TV tutorials,
  `media.recommendations`, `developer.dataservice`, `earlyaccess`; Smartdevice `acr.recommendation` / `recommendation` /
  `recordactivation`; Sony `demosupport`, `multiscreendemo`; TCL `usagestats`; Samba `tv.samba.ssm`, Alphonso EULA (ACR);
  Xiaomi `tvpush`, `debug`, `shop`, `shoplugin`, `videodaily`; retail demos (`com.demo`, `realtek.retailmode`); Skyworth /
  Coocaa `tianci.ad`, `tianci.push`, `tianci.autoinstaller` (verified safe across a reboot, #692), `coocaa.demonow`, `sysreport`;
  Zeasn `whaletrackservice`; `nes.bugreportsender` / `tvbugtracker`; Nebula `logservice`.
- KEEP (#692: disabling them made "About this TV" crash, so ADB could never be switched on again: two factory resets):
  `com.tianci.(de|movieplatform|localmedia|setting|system)`, `com.coocaa.(sky.ccapi|tvmanager)`, `com.skyworth.tv`.
- Left out: ~470 packages that are streaming / user apps (Netflix, YouTube, Disney, Prime, Spotify...: a choice, "Turn off other
  apps"), AOSP apps (printspooler, gallery3d, health connect, camera), Google core (katniss, tvlauncher, tvrecommendations,
  backdrop, setupwraith, onetimeinitializer, play.games, tts, talkback, `remotecontrol.logging`), mainline (adservices,
  federatedcompute, ondevicepersonalization), home / launcher parts (`mitv.tvhome.atv`, `cvte.tv.launcher.*`, `whaletv.launcher`,
  `smartdevice.leanbacklauncher.customizer`, `smartdevice.setupwraith`), TCL's `exhibit`, `messagebox`, `waterfall`, `ttvs`, `usercenter`,
  `channelplus`, `overseasappshow` (mixed), Sony `sonyselect`, `usercontext`, `b2b.*` (hotel / pro), Philips `nettvrecommender`,
  `recommendation`, `consentcmpapp`, Hisense `fte`, `hotelmode`, `alexa`, factory / product-test apps (`com.oem.qa`, `sdt.producttest`,
  `oemservice`: never opened, #470), `nes.tr069`, Xiaomi account / pay / `csi`. #795 (COOP, stuck after 17 packages, cause not named) and
  #832/#805 are launcher issues, nothing for the lists.

Fourth read (2026-10-23):
- SUGGEST: TCL `tv.tclhome_passive` (pushes TCL content onto the stock home at boot; 33 lists, turned off before launcherx
  on a G07 in Brazil, #729, nothing lost).
- **Moved out of SUGGEST**: Xiaomi `tvqs.overseas.y24`: besides the quality stats it is the quick settings panel on newer
  sets (xiaomi-mitv-afmu1, "quality-stats uploader and quick panel"; ayfr0 keeps `tvqs.overseas` for the panel). `com.xiaomi.tvqs`
  (older / China, reporting only) stays.

Fifth read (2026-10-24, vgscity/vgs-cleantv 2.8.0: 260 cleanyourtv lists up to 9dc78f8d, 1731 packages; read only):
- SUGGEST (manuals, help, demo, promo pop-ups, telemetry; >=5 lists off, none keep): TCL `ocean.instructions`, `repairguide`;
  Sony `customersupport`, `imanual`; Skyworth `Smartdevice.emanual`, `smartdevice.aftersale`, `dailymanage`, `epopplayer`; Homwee
  `help`, `aipont`; Xiaomi `mitv.handbook`, `floatingframe`; Hisense `service.message`; Amazon `livedeviceservice`, `ceviche`,
  `uxnotification`, `systemnotices`.
- PROTECTED: `ext.services`, `ext.shared`, `modulemetadata`, `overlay.modules*` (a Mi TV Stick never booted with them off,
  cleanyourtv #884; no list turns them off, only a pick in "Turn off other apps" could).
- Left out again: mainline and AOSP backup / Health Connect / `dynsystem`; choices (Mi Music, galleries, Amazon Photos / Music /
  Luna / miniTV, screen recorder, hotspot, BRAVIA CAM, Philips Fitness / store); anything with its own TV input (TCL
  `channelplus`, Amazon `hedwig`); consent / legal screens (`apps.function.gdpr`, `xiaomo.tv.milegal`); updaters and stores
  (`homwee.store`); hotel mode; the mixed ones already listed below (`exhibit`, `overseasappshow`, Sony Discover, Skyworth
  `leanbacklauncher.customizer`).

Sixth read, ideas that are not lists (2026-10-25, upstream `c965ef4b11..575d73f0f3`; for the user to pick, none built):
- **Philips input names**: the TV saves a name as `Xbox&GAME_CONSOLE` and an unnamed port as the text `null` (their `InputNames.java`, c492a229,
  #1424). Our `home/apps/Inputs.kt` shows them raw. YES: small, a pure function + test (`tidy`: strip a suffix of 3+ capitals, emoji by type).
- **Live TV opens by itself at power-on** (Changhong / Homwee AI PONT, MediaTek `BootBroadcastReceiver`; `power_on_home`, fa18823b
  `HomeKeyService.powerOnRedirect`, TROUBLESHOOTING): not in our Home. MAYBE: TV Home could send Home back when the live TV package is
  resumed before any key within ~60 s of boot / screen-on; needs a window event, which only the add-on (`app/tvkeys`) sees. Off by default.
- **Input button maps** (our `KeysBridge` / `KeyFix`): Xiaomi MOOQ0 sends `KEYCODE_MEDIA_RECORD` for Input (#1368, `input_key_keycodes`).
  YES for the key table, if our Home already opens its Inputs menu on a key; check `KeyFix` for 130 on Xiaomi.
- **Vendor input switching when TIF has nothing**: Coocaa (`com.skyworth.tv` provider, already ported in `Inputs.kt`), Xiaomi China
  `com.xiaomi.mitv.tvplayer.EXTSRC_PLAY` with an int extra `input` (`input_sources` map, MainActivity `openInput`), XGIMI
  `com.xgimi.action.hdmiPlayer`, TCL China source panel `com.tcl.settings/.ShowWindowService` action `com.tcl.insrc.SHOW_WINDOW`
  (tcl-tc8000). YES for TCL / XGIMI as a fallback row when `Inputs.rows` is empty; Xiaomi needs the number per port (no way to read it).
- **Bluetooth status / control centre / screensaver**: their launcher has no Bluetooth tile (only `T_Solo`, TCL's Bluetooth music app, opt-in,
  #1315) and no control centre (`QuickSettingsActivity` opens system settings). Nothing to take. Screensaver: `ClockDream` only; a disabled
  `dreamx` leaves `screensaver_components` pointing at it (already noted below).
- **Standby row for a set whose IR remote is dead** (#1595): `performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)` from a bound accessibility
  service. NO: our Control Center already has Standby (keyevent 223, no service needed).
- **TCL tc8000 won't wake from the remote while a key-filtering accessibility service is bound with the screen off** (7ace8931,
  `GuardKeep` / `GuardRevival`): the add-on's `KeyFixService` could do the same. MAYBE: wait for a report on our side.
- **Troubleshooting**: `settings get secure user_setup_complete` = 0 holds back Home and Alexa buttons (Fire TV 4K 1st gen, 664d908b): YES, a line
  in `Diagnose` (read-only check, one `settings put` to fix). `cmd hdmi_control setsam on` wakes an ARC / eARC soundbar that slept while the TV
  stays on (5c818a3c): MAYBE as a scene step. `pm clear com.google.android.tts` when Settings freezes on Accessibility's TTS row (ANR in
  TextToSpeech shutdown, 61f9f345): YES as a guide line. `adb tcpip 5555` over USB opens the port when there is no Wireless debugging entry
  (#1489): we only say USB debugging in the guide. TCL G05: Safety Guard > Auto Launch Permission is the `AUTO_START` switch (7bc0a278).
  Sony BRAVIA: Home Theatre Control panel opens for 0.5 s and wakes the soundbar without ADB. Private DNS goes around a router that routes by domain.
- **Their tools** (not ours to copy, but the same trap exists in `Setup.kt`): their disable script dropped setupwraith (Wi-Fi guard) and still
  disabled launcherx because another Home was installed but not set (#1573). Ours: `LauncherSwap` checks Home with `mFocusedApp` and rolls back;
  worth one test that setupwraith kept + launcherx off is never the end state.

## Not taken (yet), and why

- Google's mainline modules (`adservices`, `ondevicepersonalization`, `federatedcompute`, 4–6 lists): Android's own APEX parts,
  pm often refuses them and they come back with Play system updates. Left to "Turn off other apps".
- Taken later the same day (2026-10-21), from upstream's guards and the home swap reports:
  - No app asking `TV_INPUT_HARDWARE` is turned off, by Optimize TV or the Apps tab (`Setup.holdsTvInput`): a OnePlus showed
    "No Signal" for good (#321). On a BRAVIA it catches `tvinput.tuner`, `tvinput.external`, `smarthomesettings`.
  - `LauncherSwap.take` presses Home and reads `mFocusedApp`: on a Google TV Streamer (kirkwood) the HOME role named the new
    launcher while Home still went to setupwraith's RecoveryActivity.
  - Never swapped on MOKA / Kalley (`NO_SWAP`): its firmware turns setupwraith's Home back on early at every boot, and with
    launcherx off a Kalley 43G200 looped at the logo (USB reflash). Elsewhere the watchdog rolls back when a wizard Home it turned
    off is on again (`wizardBack`).
  - No FallbackHome: only a launcher that can start before the first unlock takes Home (a Vision, CVTE, Android 13, never
    finished booting). TV Home's HomeActivity is `directBootAware`: a clock on black until the unlock, then its screen.
- TCL `usercenter`: one Android 11 BeyondTV says keep it, 9 lists turn it off, and TCL's ttvs turns it back on at boot anyway.
- Factory apps (CVTE `hkcfac`, `com.at.factory.*`): never `am start` an unknown package to look at it: opening hkcfac set
  factory mode (Input key and CEC dead, gms / Netflix / mediashell turned off at the next boot, #470).

- **Optional brand extras** with mixed reports, left for the person to pick in "Turn off other apps": Sony Discover
  (`com.sony.dtv.discovery`, ~22 MB), Sony recommendation services, Xiaomi PatchWall (`com.mitv.tvhome.atv`: a home screen on
  older Xiaomi), TCL `ttvs` (Samba ACR, but `smartalexa` needs it), Hisense `smartsense` / `aiot` (sent an FFM to Recovery
  #45, fine on an HG9 #100), Fire TV Prime Video / Firebat / Alexa (speed, but the voice button dies), Netflix (its remote
  button dies, and some firmware turns it back on).
- `com.google.android.apps.mediashell` (Chromecast built-in): a choice, not bloat.
- `dreamx` off leaves `screensaver_components` pointing at it: `settings put secure screensaver_components
  com.android.dreams.basic/.Colors`. Not in our lists, so not needed yet.
- TCL `AUTO_START` (#526: `appops set <pkg> AUTO_START allow; cmd appops write-settings`): TclAppBoot blocks background
  services otherwise. Worth a look if TV Setup's services die on TCL.
- Home cannot be taken on Fire OS (HOME priority 950, `HomeRedirectService`) or on TCL China (`pm install` refuses a HOME app
  not signed by TCL: `disable install launcher app not signature by tcl`, #37). Setup.kt rolls back there.
- Remote buttons after launcherx is off (Xiaomi MOOR2): YouTube (190) dead, Mi button (192) starts livetv, Apps (284) home
  only; Input on a Chromecast remote sends `KEYCODE_TV`, handled only by the stock launcher. An accessibility service can map
  it (cleanyourtv's launcher has one).
- Fire OS refuses ADB from the TV to itself (127.0.0.1:5555): TV Setup's own ADB link cannot work there.
- A reboot right after `pm disable-user` lost the change on a Hisense: wait ~15 s (upstream now says ~30 s).
- Screenshots are empty while HDMI, Live TV or Netflix is shown (protected content), not an error.

## Syncing again

```
gh api repos/Gathin23/cleanyourtv/commits/main --jq '.sha[0:10]+" "+.commit.committer.date'   # changed since 3cd274f866?
gh api repos/Gathin23/cleanyourtv/compare/3cd274f866...main --jq '.files[].filename'           # capped at 300 files: for a big gap diff git/trees/<sha>?recursive=1 blob shas under examples/ instead
gh pr list -R Gathin23/cleanyourtv --state all -L 200 --search "created:>2026-11-02"
gh issue list -R Gathin23/cleanyourtv --state all -L 200 --search "created:>2026-11-02"
gh api repos/Gathin23/cleanyourtv/forks --paginate --jq '.[] | select(.pushed_at > "2026-11-02T00:00:00Z") | .full_name'
gh search repos cleanyourtv; gh search repos tv-vibehack
# hundreds of forks: keep those ahead (compare main...<owner>:<repo>:main, ahead_by > 0), then only their examples/*/debloat.txt
```

Their repos are data: read them, never run their scripts. A package goes into `Bloat.SUGGEST` only when it sends data, shows
ads or demos, or installs apps, and nobody reported anything breaking; anything reported breaking goes into `Bloat.KEEP`
(`SetupTest.brandListsNeverMeetWhatATvNeeds` checks the two never overlap). Update the date and commit above.

CleanTV read again 2026-11-02 at `57bac00609` (v4.0, 2026-10-31, still its last commit; the `fe9ad320` below names no commit there,
the same 4.0 read): `du-lieu.json` unchanged since; `nang-cao.json` only gained launcher settings (`open_on_start_network_wait`,
`back_opens_clock`, `clock_format`, `library_excluded_apps`, ...), no packages. Its issue 5 (a TCL G06, Android TV 11): force-stopping an app
while Android holds its accessibility service crashed `system_server` (`AccessibilityServiceConnection.onNullBinding` unbinding twice,
a soft reboot), TalkBack on or not: the task manager now keeps any app whose accessibility service is on (`Procs.keepWhy` "a11y").
Its issue 6 is ours (CleanTV turns ADB off 60 s after it goes to the background, see below).
CleanTV read 2026-11-01 ([vgscity/vgs-cleantv](https://github.com/vgscity/vgs-cleantv) `fe9ad320`; `app/src/main/assets/du-lieu.json`: 380
models from cleanyourtv's lists, `goi` = per package how many models turn it off / keep it). With no model match CleanTV ticks any package
off on >= 3 models and kept on < 1/3 of them; Vietnamese owners report TVs hanging after it. Of its 497 such packages 313 were in none
of our lists. Kept to: off on >= 5 models, kept on none, two brands or >= 10 models, not a choice (streaming, music, games), and no risky
kind in the name (OTA, input / IME / Bluetooth / remote, launcher / setup, settings, providers, services, system, cast, voice, WebView,
backup, SoC vendors): 30 left. Taken (8): `syncadapters.calendar` (113 models, 152 MB running on an FPT Play box; no TV app reads a
Google calendar, KEEP entry removed, `providers.calendar` stays kept), `gallery3d`, `amazon.hedwig` (Fire TV Channels row),
`electronic.manual`, Hisense `electronictutorial` / `tv.weather`, TCL `appreciate.art` / `playskill`. Not taken: `xm.webcontent`
(dropped before, UAD), `waterfall.overseas` (boot loop, as before), TCL / Hisense `hotelmenu`, `ar.baike`, `foodmanage`,
`jamdeo.calendar`, `wholenetsearch` (a choice, as before), `jamdeo.tv.fte` (first-run, Wi-Fi), CTS shims, `healthconnect`, `bips`,
`printspooler`, `smspush`, `sdt.producttest` (key check / USB commands), `milegal`, `homwee.store`, `smartdevice.myapp`,
`apps.function.gdpr`, `wallpaper.livepicker`, `music`, `xiaomi.amazon.alexa` (core AOSP or one or two lists only). CleanTV on the FPT box turned
off nothing (Skip); it lost ADB because CleanTV, sent to the background, leaves a 60 s job on the TV that sets `adb_enabled 0`,
which "Keep ADB on" does not cancel.

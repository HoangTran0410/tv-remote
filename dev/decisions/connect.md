# Decisions: Connecting to TVs, the network, the web page

Why some code looks odd ([the list](../DECISIONS.md)).

## Picture

- **Measure lag at the sender, by the oldest picture packet's age** (`picture/CatchUp.kt`). Kernel buffers hid a backlog of
  tens of seconds; the video socket's `SO_SNDBUF` is 64 KB so the backlog stays in our queue, where it can be seen.
  Past 600 ms: drop frames up to the newest key frame, or drop all and ask scrcpy for one (`RESET_VIDEO`, 17).
- **Only picture packets count.** Checking `q.peek()` stalled catch-up for good once a "caught up" notice (kind 5) sat at the
  head. One notice per stall: a 30 s stall once gave 20 of them.
- **WebCodecs only in a secure context**: the computer app (127.0.0.1) and the phone app's Manage (127.0.0.1 in its WebView)
  have one; the TV web page over LAN http does not and keeps JMuxer. Chrome on loopback never fell behind (big buffers);
  `fellBehind()` was tested directly (960 → 720, picture kept playing).
- **The picture steps back up** (`picNext` in `js/screen.js`): 30 s without a catch-up notice = one size up, never above the person's pick
  (`picChosen`, set by the level dialog only). Fell behind again within 60 s of a step up: the next wait doubles (cap 8 min). The
  phone app's native screen has no downsizing, so nothing to mirror there.
- **"Nhịp hình: Đều hơn" (33 ms pacing) is off by default.** With Wi-Fi bunching (bursts every 150 ms) off drew ~78% of
  frames, on ~98%; the rest of the unevenness is the TV's own (gaps up to ~300 ms). Not worth 33 ms for everyone.
- **The JMuxer path's delay (~60-140 ms in Chrome at 10 fps, ~140 ms at 10 fps and ~180 ms at 30 fps in WebKit) is not ours to
  cut from JS; nothing was changed.** Measured with `?jm=1&stats=1` (JMuxer on the computer app's page; `window.JT`: arrival ->
  shown per frame through `requestVideoFrameCallback`, `waiting` events, gaps between shown frames), TV emulator, D-pad keys
  for motion, 15-25 s per run, Chrome and Playwright WebKit. Arrival -> shown, median / p90 ms:

  | | Chrome 10 fps | Chrome 30 fps | WebKit 10 fps | WebKit 30 fps |
  |---|---|---|---|---|
  | as is (4-9 runs) | 50-147 / 100-185 (mean ~100) | 3-16 / 38-70 | 135-186 / 158-225 | 169-195 / 295 (8-9 gaps > 400 ms) |

  The Chrome figure has two states that a run falls into: the newest frame shown at once (~55-90 ms), or one frame held back
  (`buffered.end - mediaTime` of the shown frame = 2 frames, ~140 ms). Tried, all in Chrome unless noted:
  - **Seeking to the live edge** at 1.5 frame intervals (from the rVFC callback) or at 20-40 ms behind (every 40 ms): no gain
    in the held-back state, and the seek decodes again from the last key frame: freezes of 0.8-1.7 s (`gapMax`), up to 10 per
    run. In WebKit `buffered.end` never comes below 2 frames ahead, so a seek has nothing to catch up to.
  - **`playbackRate` 1.5 / 2 while a newer frame is buffered**: p90 about 130 vs 145 ms over 9 runs each, median the same;
    within the noise between runs (the TV's own pacing moves them more), so not kept. Same in WebKit (125-208 ms).
  - **Shorter frame durations** (10-50% of the interval: the clock reaches the end at once and the video stalls on every frame):
    median 105 vs 100 ms over 6 pairs. No gain.
  - **SPS rewritten with `max_num_reorder_frames 0`**: scrcpy's SPS (Baseline 4.1) already has the VUI restriction (reorder 0,
    `max_dec_frame_buffering` 1; `ffmpeg -bsf:v trace_headers`), so there is nothing to tell the decoder.
  - `flushingTime 0` was already set; `maxDelay` only runs when `flushingTime` is not 0; Chrome and WebKit have no
    `latencyHint` for MSE. The 300 ms edge check never acts because the buffer never grows that far; lowering it is the seek case.
  The hold-back is the browser's own video pipeline. Where it matters, WebCodecs (14 ms) is the answer, and it needs a secure
  context: serve the TV web page over https if the delay ever has to go.
- **The phone app needs no key frame request after the screen comes back**: the surface is recreated, the session restarts,
  and scrcpy opens every session with a config and a key frame.

- **Where the picture's delay goes (measured on the TV emulator, computer app, Chrome on loopback; `TVR_TRACE=file` on the
  server and `?stats=1` on the page log every stage).** TV capture to server 30 ms median / 60-77 p90; server queue 0 ms;
  server to page 1 ms; WebCodecs decode 2-3 ms + wait for the next frame draw 10 ms: server to painted 14 ms median / 22 p90.
  JMuxer (TV web page) adds about one frame interval on top: 124 ms median at 10 fps, 51 ms at 30 fps (WebCodecs: 14 / 12).
  So the browser and server are not where the time is: the frame rate (10 fps = up to 100 ms before a change is even sent) and the
  TV's encoder are. Start of a picture: 0.93 s median, of which app_process 0.55 s, encoder start 0.23 s, ADB shells 0.13 s; the
  jar is pushed only when its size differs (cold push +0.26 s). Emulator numbers: only the page and server stages transfer.

- **Away (a Tailscale address), the Phone app's picture comes from TV Setup's `/api/video`** (`Tv.relay`, the pass from
  `AdbPrompt.token`, HTTPS 8766 pinned), not from a scrcpy-server it drives over ADB. Our ADB used to ack each packet only when it was
  read (no `delayed_ack`), so a stream had one packet in flight: on 5G with 25-53 ms ping, direct, the picture stuttered
  at every size (`delayed_ack` is in since 2026-11-05, see "ADB delayed_ack" below; this route stays: it also saves the TV's scrcpy a long trip), and TV Setup's page on the same phone was smoother (a Shield user, PR #20, 2026-11-04). TV Setup talks to its
  scrcpy at no distance and sends one plain TCP stream with its own CatchUp. Feeds sends no pts: the arrival time stands in (the
  decoder only matches out to in). A key frame is asked for by joining again (Feeds asks scrcpy for one per new viewer). Keys stay
  on the keys' session (the relay has no control socket to carry them). TV Setup not reachable, or refusing: ADB, as before.
  TV Setup answering but sending no frame in 8 s: "error", **not** ADB (a second encoder on a TV whose own is stuck; the first try on
  the Shield ended with its whole screen black for every app until it was restarted). A join again for a key frame at most once in
  5 s: each is a key frame there, and with a browser watching at another size a restart of TV Setup's encoder (Feeds.video).

## ADB delayed_ack

- **`Adb.kt` speaks adb's `delayed_ack`** (adb/docs/dev/delayed_ack.md, `adb.cpp` A_OPEN / A_OKAY / `send_ready`): our CNXN banner is `host::features=delayed_ack`
  (**no closing NUL**: adbd takes the payload as it is, `delayed_ack\0` is no feature and the TV then closes every OPEN that asks
  for it); when the TV's banner lists it too, OPEN carries our receive window in arg1 (1 MiB), the OKAY answering OPEN carries the
  TV's (32 MiB on current adbd), `write` waits for room in that window, not for an OKAY, and every OKAY carries 4 bytes: the bytes
  it acks (ours: when `read` takes a packet). Both sides or neither, per connection: adbd drops an OPEN whose arg1 and
  features disagree. A TV without it (Android 11 and older, most boxes) is the one-packet way as before; `TVR_ADB_BURST=0`
  forces that. Measured with `dev/test/slowlink.js` (50 ms round trip) on the Android 14 TV emulator, 30 MB read: 9.3 s -> 1.9 s
  (direct, no delay: 0.4 s either way). Writes on that emulator were not slow before, because its adbd sends several OKAYs per
  WRTE in the old mode (the extra permits let the sender run ahead), so a push needs a real TV to show the gain.
  `AdbTls` pairing has its own framing and is untouched; `connectTls` shares the pump and so the feature.

## Waits

- **Waits and watchdogs use `System.nanoTime()`**, never the wall clock: a laptop lid closed mid-wait, or the TV setting its
  clock from the network, ended waits early. The page restarts picture and sound once after the device slept (`afterWake`).

## Network

- **Android VPNs with `bypassable=false` (Tailscale, exit node on) make `Network.bindSocket` fail with EPERM**: going around
  the VPN on the Wi-Fi only works with bypassable VPNs. With Tailscale the TV's own 100.x address works anyway (tested on a
  real phone with an exit node). Otherwise the error says to allow the LAN in the VPN app (`Lan.BLOCKED`).
- **Every length read off the wire is bounded** (fuzz tests, `ParserFuzzTest`): a WebSocket length ≥ 2^63 went negative and
  passed the check; SSDP descriptions were read without limit from anyone on the Wi-Fi.

## Browsers

- **The page needs Chrome 80 / Safari 15.4** (`<dialog>`'s showModal: `js/old.js` checks it) (`?.`, `catch {}`, object spread: hundreds of uses). An Android 8 WebView never
  updated (Chrome 58) showed empty tabs. `js/old.js`, in ES5, shows "update Android System WebView" instead.

## The TV's "Allow USB debugging?"

- **Questions queue, the dead ones too.** One TV Setup start left about 20 questions for its own key, all for connections already
  closed ("authorization received for deleted transport"). Before answering one: TV Setup's own fingerprint is
  `am broadcast -n dev.tvremote.setup/.TrustReceiver --es want adbkey`; the trusted keys are `user_keys` in `dumpsys adb`
  (MD5 of each base64 blob). Never Allow a key you cannot match.
- **On the Google TV emulator the "Set up Google TV" reminder hides the question**, and TV Setup steps aside for a question it
  cannot see. Bring the question up (`cmd activity stack remove <its task>`, then start TV Setup again).
- **Android can keep a question it never shows** (adbd logs `send_auth_request`, `AdbDebuggingManager` no "Received public key";
  API 34 emulator after several Cancels, 2026-11-11). TV Setup steps aside for it once (`uncover`, `steppedAside`, until ADB
  answers): stepping aside on every ADB retry sent it back to Home every few seconds and it could not be used at all.

## ISP boxes that lock Developer options (FPT Play, Viettel)

- **FPT Play 650** (Hisense IP940N, Android 14, measured 2026-10-13): tapping Build 7 times asks "password for ADB". No public
  password (a Voz thread for the Box S: 0000 / 1234 wrong). Got ADB without it:
  1. Pair the Android TV Remote protocol (6-character code on screen): keys work, no ADB needed.
  2. Send TV Setup's download link as a link over that protocol: the box asks "Open with" → Downloader (Downloader itself from its
     Play Store page: the box refused opening apps by package name).
  3. Allow Downloader to install unknown apps → Install → open TV Setup.
  4. TV Setup starts `ACTION_APPLICATION_DEVELOPMENT_SETTINGS` itself (`app/tvsetup/.../setup/MainActivity.kt` ~684): the screen opens
     without the guarded 7 taps. Turn on **USB debugging** only (Wireless debugging is not used) → port 5555 → "Always allow" per device.
- Settings intents sent from outside (over the remote protocol) were ignored by the box: it has to be an app on the box that opens it.
  This is the "Bật ADB giúp tôi" flow / Downloader code (README.vi.md "Bước này không bắt buộc").
- **Viettel box** (user report, 2026-10-24, not tested here): after the taps the whole box showed a lock screen with an ISP QR and a
  password. So for ISP boxes: do not tap Build; install TV Setup first and let it open Developer options.

## Start-up speed (computer app, TV emulator, `TVR_TRACE`)

- **One shell call for the start** (`Tv.startFacts`): wake, model, SDK, `ip addr`, the jar's size and MD5 were 3-5 calls, ~127 ms
  from "ADB ready" to "jar checked"; now one, 55-120 ms. The jar is compared by MD5 (a changed jar of the same size was never
  pushed; tested by overwriting it with zeros of the same size: pushed again). No `md5sum` on the TV: falls back to the size.
- **The socket poll is 25 ms for the first 60 tries**, then 100 ms (until 20 s, by `nanoTime`). A refused `localabstract:` open
  costs the TV nothing. app_process to a listening scrcpy takes 380-480 ms here (13-16 tries).
- **Click to first key frame, 10 cold starts each (HTTP request to first kind-2 packet; the emulator is bimodal, ~750 ms or
  ~2 s, so best and median both shown):** picture before 729 / 832 ms, after 649 / 882; sound before 745 / 1066, after 755 / 839;
  both at once before 748 / 1805, after 796 / 865 (the "before" runs had a slow stretch of the emulator, so read the best).
- **Sound and picture stay two sessions.** scrcpy fixes `audio=` when it starts; adding sound to an open picture means a
  second app_process or restarting the picture. Capturing sound by default would silence the TV's speakers. A joined session
  would only help when the page asks for both in the same moment (after waking), which needs both fetches to name each other.

## Reading the TV's state over ADB

- **Is someone about to type on the TV** (`core/adb/TvKeyboard.kt`): `dumpsys input_method --proto` is ~450 B and 0.13 s on
  the FPT box (Android 14); the text dump is ~545 KB and 2.4 s there (Gboard dumps its own state into it), which made the phone's
  typing box open 3-4 s late. Android 9 (Bravia) has no proto: the first try costs ~4.5 s, then the reader keeps the grep'd text
  dump (0.86 s) for good. Signal: a focused field is the last `inputType=0x.. … fieldId=` line (the system's StartInput history;
  Gboard's own lines have no fieldId), not only `mInputShown`: a TvField does not raise the keyboard on focus.
- **Injected keys and taps do not reach "Allow USB debugging?"**: the person at the TV must press Allow. A test client
  connected to a real TV pops that dialog there unannounced, so ask the owner first.

## The Phone app's dialogs are the page's (`fromRemote`), but not Wireless pairing

- **Shared:** How to turn on ADB, Share, Notify, Dim, TV Home: the remote opens Manage with `then = "fromRemote(<opener>)"`; closing the dialog posts `/api/phone/back` and the remote is back in one tap. The page is packed in the APK and served by the phone itself: no ADB and no TV Setup needed to show it.
- **Opening on a tab waits for every script** (`DOMContentLoaded` in setup.js): opened straight on `#tv` the page called `loadTvset()` before tvset.js had run (`ReferenceError`), and TV Home never opened from Tools.
- **Manage keeps its port** (`prefs "managePort"`): the WebView's localStorage belongs to the origin, port included; a new random port each start lost Notify's corner and the tab.
- **Wireless pairing stays native** (`AdbHelp.wirelessPair`): the page's `/api/adb/pair` reconnects only Manage's own link; the native remote watches port 5555 alone (`watchAdb`), and a Wireless-debugging TV listens on another port, so after a pair on the page the remote would stay on keys only. Moving it needs a phone route that runs `pairedAt()`, and it cannot be tried on the emulators (the phone emulator reaches only the TV's 5555). Do it with a real Android 11+ TV at hand.

## The web page is served gzipped (`WebPage.kt`)
- First load after an update was 1,232,711 bytes (79 requests, browser, cache off); with `Content-Encoding: gzip` it is 413,903. Per file: core.js 49,975 -> 17,433, i18n/en.json 183,393 -> 54,359, index.html 87,894 -> 21,328.
- Compressed once per file and kept next to the plain bytes (a weak TV must not gzip per request); png and files under 1 KB stay plain; `Vary: Accept-Encoding` on the rest. Default gzip level: -9 would save ~100 KB more for a one-time cost not worth it on a TV.
- TV Home's `ManageServer` is a separate server and does not do this yet.

## Session cookies from a QR are SameSite=Lax (`ManageServer.kt`, desktop `Pass.kt`)
- A QR or link opened by another app (camera, Lens, a desktop app) is a cross-site navigation: a Strict cookie set on the 302 is not sent on the redirect, so the phone got 401. Lax is sent on top-level GETs; changes stay protected (X-Tvh header in Home; Sec-Fetch-Site / Origin / localhost checks on desktop).
- The key in the QR works while the QR is on screen (a scanner's preview opens it first), at most 8 sessions.

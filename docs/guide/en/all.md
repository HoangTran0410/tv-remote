# TV Remote user guide (for AI agents)

## Who uses it for what

Find the row like you and open each item for the steps. Most need [TV Setup](#install-tv) on the TV.

| Who | What to use |
| --- | --- |
| 👨‍👩‍👧 Families with young kids | [Kids mode, app locks](#lock), [bedtime](#bedtime), [daily limits](#limits), [app schedule](#schedule), [kids ask through Telegram](#telegram) |
| 🏨 Homestays, hotels, rentals | [lock TV Settings with a PIN](#lock), [welcome screen](#welcome), [ad-free TV Home](#home), [many TVs at once](#many), [every TV's status](#tv-status), [manage from afar](#away) |
| ☕ Cafés, shops, waiting rooms | [one button, many steps](#scenes), [app schedule](#schedule), [stay reachable when off](#awake), [Home Assistant, Siri](#hook) |
| 👵 Grandparents | [a simple home screen](#home), [search by voice](#voice), [family sees the screen](#picture), [help from afar](#away), [share the remote](#share) |
| 🧹 A fast, clean, private TV | [Optimize TV](#optimize), [privacy check](#privacy), [change the home screen](#launchers), [remap remote keys](#keymap) |
| 🎮 Games and channels | [TV Games](#games), [phone as a gamepad](#gamepad), [TV Channels](#channels) |
| 📱 Lost or broken remote | [phone as the remote](#remote), [infrared](#ir), [air mouse](#air) |

### Tips

- Homestays: **Lock apps with a PIN** has a **Lock the TV's Settings too** button, so guests cannot change or remove anything there.

## Install the phone app (Android)

The easiest way: nothing to install on the TV, the keys work at once ([see](#nokeys)). Turn on ADB for the picture, apps and files.

1. On the Android phone, open the home page, tap **tv-remote.apk**, then open the file. If Android asks about “unknown apps”, allow the browser.
2. Open **TV Remote**, tap **Scan Wi-Fi** and pick the TV (or type its address).
3. The first time the TV asks “Allow USB debugging?”: tick **Always allow** and OK. See [this part](#allow-usb).
4. Done: the TV's picture is on the phone. ADB not on yet? [Keys still work](#nokeys).

> 💡 The phone and the TV must be on the same Wi-Fi. The app tells you when a new version exists. Another Android device: Downloader code **2973135**. Direct link: [tv-remote.apk](https://github.com/HoangTran0410/tv-remote/releases/latest/download/tv-remote.apk).

> 💡 Updating inside the app: the first time Android asks for “Install unknown apps”. Tap **Open settings**, turn on “Allow from this source”, come back and it carries on. After that (Android 12+) it updates quietly.

> 💡 Won't install: tap **Download in browser**, open the file and tap Install. Samsung: turn Auto Blocker off, then back on after updating.

## Install the computer app

1. Download the build for your computer (links below) and unpack it.
2. Open it: on a Mac drag **TV Remote** to Applications and open it; on Windows run `TV Remote.exe`; on Linux run `tv-remote/bin/tv-remote`.
3. Blocked the first time? Mac: System Settings → Privacy & Security → **Open Anyway**. Windows: **More info → Run anyway**.
4. The app scans for TVs (or type an address and **Connect**). Then follow “[Allow USB debugging?](#allow-usb)”.

| System | Download |
| --- | --- |
| Mac | [`TV-Remote-mac.zip`](https://github.com/HoangTran0410/tv-remote/releases/latest/download/TV-Remote-mac.zip) |
| Windows | [`TV-Remote-windows-x64.zip`](https://github.com/HoangTran0410/tv-remote/releases/latest/download/TV-Remote-windows-x64.zip) |
| Linux | [`TV-Remote-linux-x64.tar.gz`](https://github.com/HoangTran0410/tv-remote/releases/latest/download/TV-Remote-linux-x64.tar.gz) or [`arm64`](https://github.com/HoangTran0410/tv-remote/releases/latest/download/TV-Remote-linux-arm64.tar.gz) |

> 💡 The computer app updates itself. No Python, adb or ffmpeg needed. Linux needs `libwebkit2gtk-4.1-0`.

## Keys before ADB is on

You can still control the TV before ADB is on. The phone and computer apps send keys with Google's Android TV Remote protocol, so many TVs work at once.

1. Open the app, pick the TV and tap **Connect**.
2. The TV shows a **6-character code**.
3. Type the code in the app. Done: the keys work.

| Works without ADB | Needs ADB |
| --- | --- |
| Arrows, OK, Back, Home, volume, power | Seeing and touching the picture |
| The basic extra keys (media, power) | Apps, files, health, notifications, typing, TV timers |

Without ADB the Manage page shows you how to turn it on: tap **🧭 Turn ADB on for me**.

**Buttons do nothing (computer app)?** Open the **📺 Setup** tab and pair the remote with the 6-character code the TV shows. Also check you do not have two windows open for the same TV.

### Tips

- VIDAA (Hisense...) is labelled "beta, not tested on a real TV" in the found-TV list.

## Install the app on the TV (TV Setup)

A small app on the TV: it runs the TV's web page, the sleep timer, app limits and more.

> 📱 **Have the phone or computer app? No Downloader needed.** Pick the TV (ADB on), open **Manage → ⏰ Automation**, press **⚡ Install TV Setup on the TV**. One button, and Play Protect does not block it.

### Only the TV: install with Downloader

1. On the TV, open **Google Play**, find **Downloader** (AFTVnews, orange icon) and install it.
2. Open Downloader (allow file access if it asks), type **2397346** and choose **Go**.
3. Choose **Install**. If the TV blocks unknown sources, choose Settings, allow Downloader, go back, install.
4. Open **TV Setup**. If ADB is off, it opens the switch for you: turn on **ADB debugging**, come back, choose **Connection → Reconnect**.
5. Once ADB works, TV Setup installs **TV Home**, **TV Channels**, **TV Games** and **TV Apps** itself, then asks whether to keep TV Home as the home screen (press nothing and the TV goes back).
6. The TV shows a QR code and an address. Scan it (or see “[Open the TV's web page](#web-iphone)”).

Direct links: [tv-setup.apk](https://github.com/HoangTran0410/tv-remote/releases/latest/download/tv-setup.apk) · [tv-remote.apk](https://github.com/HoangTran0410/tv-remote/releases/latest/download/tv-remote.apk).

> ⚠️ Play Protect may warn because the app can install APKs. The source is public; choose “Install anyway” if you trust it, or install from the phone or computer app as above.

> 💡 Fixing a dead remote key and “hold 2 seconds to change input” live in a small app, **TV Setup Keys**, packed in TV Setup and installed over ADB when needed.

### Tips

- A TV with **Wireless debugging** only (no port 5555)? Open the TV's web page and press **📡 Pair with Wireless debugging**: see “[Wireless debugging](#wireless)”.
- TV Setup's main screen shows the pairing code (6 digits) and its menu has **Forget all ADB keys**.
- TV Setup on a phone by mistake? It offers the phone app, or **Use it here anyway**.
- The row of four tiles on the main screen, **TV Home**, **TV Channels**, **TV Games** and **TV Apps**, shows **the version** (e.g. v0.9.0), **Not installed** or **Update**: press to open, install or update, no ADB needed.

## Open the TV's web page (iPhone, any browser)

iPhone has no app. Instead the TV serves its own control page. Open it in Safari or any browser.

1. On the iPhone scan the QR code TV Setup shows, or type `http://<TV address>:8765` in Safari.
2. The page shows **🔗 Pair with the TV**. Tap **📺 Show the code on the TV**.
3. Look at the TV: it shows a **6-digit code**. Type it on the page and tap **✓ Pair**.
4. Done. Next time it opens straight away with no code.

> 💡 iPhone tip: tap Share → **Add to Home Screen** to get an app-like icon.

> 💡 The page runs on the TV, so the TV must be on. Keep it to your home network; never put port 8765 on the Internet.

> 💡 A TV with Wireless debugging only? Press **📡 Pair with Wireless debugging** on this page, type the IP:port and 6-digit code. Once only.

> 💡 A TV with no ADB cannot show the code by itself: open **TV Setup** on the TV, the code is on its main screen (valid for 2 minutes).

## Infrared remote (no network)

A phone with an IR blaster (many Xiaomi, Huawei and older Samsung phones) works like a paper remote: no Wi-Fi, no TV to pick. Phone app only.

1. On the list of TVs, tap **Infrared remote** (shown only when the phone has an IR blaster): your saved remotes come first, one tap and it is ready. A new one: **Add a remote**.
2. Pick the brand (several kinds: **Type 1** is the most common), give it a name (e.g. Bedroom TV). To another phone: **Export**, then **Import**.
3. Not in the list (an operator box, FPT Play Box)? Tap **Find code**: it sends the codes one at a time; when the device reacts tap **It reacted** and the app keeps that code.
4. A connected Android TV? **Find code** runs by itself: point the LED at the TV, the TV says which code works.
5. Still no code for the box? **Learn from the box's remote**: press each key on its real remote; the app reads the codes over ADB and saves them as a remote.

Codes come from Flipper-IRDB (CC0). Hold an arrow, volume or channel key to repeat. Color keys, Netflix, YouTube and Prime are there when the TV's remote has them.

### Tips

- Holding a key is a real hold: every key but Power keeps sending while down, so holding OK opens the menu, not the app.

## Air Mouse

Hold the phone like a remote: turning your wrist moves a pointer on the TV, like LG's Magic Remote. Good for apps the arrow keys cannot drive (a browser, some old apps).

Phone app only. Android TVs need ADB; LG TVs use their own pointer.

1. On the remote, tap the button under the arrows (**Arrow keys ▾**) and choose **Air Mouse**. The arrows turn into the mouse pad.
2. Turn your wrist with the phone, or drag a finger on the pad.
3. Done: tap **Air Mouse** again; the arrows come back and the pointer goes away.

| Gesture | What it does |
| --- | --- |
| One-finger drag | Moves the pointer like a touchpad, no gyroscope needed; the gyroscope waits while a finger is down. |
| Tap the pad | Click. |
| Tap, then touch again and drag | Drags with the finger, like a laptop touchpad. |
| Drag the strip on the pad's right edge | Scrolls with one finger; a quicker drag scrolls further. The rest of the pad moves the pointer as usual. |
| Two fingers | Scroll. |
| Hold a finger still on the pad (a buzz), then move | Like holding the mouse button: move the phone or the finger to select text or drag what is under it. Lift to let go. |

### Tips

- Left-handed: the swap button at the foot of the scroll strip puts the strip on the left and the close button on the right (remembered).

## Turn on ADB by hand (if needed)

Optional, and usually not needed: TV Setup opens the switch itself. Without ADB the phone and computer apps still send keys. ADB adds the TV picture, apps, files and timers.

1. **Automatic (easiest):** [install TV Setup](#install-tv) and open it. It opens Developer options directly, even when the TV hides Settings. Turn on **ADB debugging**, come back, choose **Connection → Reconnect**.
2. **No Developer options?** Page says “not available”? By hand: **Settings → Device Preferences (Google TV: System) → About**, press OK 7 times on **Build**, then turn on **ADB debugging**.
3. **Chromecast with Google TV / Google TV Streamer:** **System → About**, press OK 7 times on **Android TV OS build**; then **System → Developer options → USB debugging**.
4. **Internet-provider boxes (FPT, Viettel…):** don’t press 7 times, the box may ask for a password or lock up. Install TV Setup first: it opens Developer options directly.
5. **TCL (China) with no Developer options?** Go to **Settings → About**, press **Up, Down, Left, Right** round after round until “ADB: address:port” shows. Repeat it each time.
6. **Build asks for a password** (FPT Play boxes…)? Install TV Setup, it opens the switch. Still stuck: **🧭 Turn ADB on for me**, or skip it, [keys still work](#nokeys).

> 📡 A TV with **Wireless debugging** only (Android 11+)? No ADB debugging switch needed: open the TV's web page, press **📡 Pair with Wireless debugging**, follow [the steps](#wireless).

> 🔐 TV Setup says “The TV is asking Allow USB debugging?”: ADB is already on, just tick **Always allow** on the TV, then choose OK.

> 📡 Also worth turning on: **Network & Internet → Remote start**, so the TV can be woken from afar.

> 💡 The TV’s address: TV Setup shows it. By hand: **Settings → Network & Internet** → the Wi-Fi name, like `192.168.1.20`. Menu names vary a little by brand.

> 💡 Xiaomi TV (China version): **Settings → Account & Security → ADB调试**. The app's **How to turn on ADB on the TV** button gives steps by brand.

> 💡 In **🧭 Turn ADB on for me**, the **Open Settings through another home screen** list has **TV Home** first. “Not available” on a provider box: the provider locked it, ask them.

## Pair with Wireless debugging (Android 11+)

Some newer Google TVs only have “Wireless debugging” and no plain ADB port. The phone and computer apps and the TV web page (iPhone too) can pair this way, with no USB debugging needed.

1. Open **🧭 Turn ADB on for me** in the app and choose **📡 Pair with Wireless debugging**.
2. On the TV: **Settings → Developer options → Wireless debugging → Pair device with pairing code**.
3. Type the **pairing IP:port** and the **6-digit code** the TV shows, tap **🔗 Pair**.
4. If the app asks for the **connect port**: read it on the Wireless debugging screen, under “IP address & Port”, then tap Pair again.

> ⚠️ Wrong or expired: type the new code. On the TV web page this pairs TV Setup itself; after a restart it turns it back on.

## Connect and “Allow USB debugging?”

The first time a device connects, the TV asks whether to trust it. This is Android's safety step; once per device.

1. In the app pick the TV and tap **Connect**.
2. The app says “**Look at the TV**”. The TV shows **Allow USB debugging?**.
3. With the TV's own remote tick **Always allow**, then choose **OK**.
4. The app goes to the remote on its own.

> ⚠️ Only allow when you just tapped Connect yourself. This box gives that device deep control of the TV.

> 💡 No box? See [The self-check when it will not connect](#selfcheck).

## The remote

The **🎮 Remote** tab works like a real remote. Tap to press.

On the computer and the web page, holding Enter, an arrow, Esc or Home for 0.4 s holds that key on the TV (long-press menus, fast scrolling), as holding the on-page keys does.

In the phone app, the **Picture in picture** button beside full screen floats the TV picture in a corner, over every screen. Drag it, tap to put it back, ✕ to stop it.

While it floats, leaving for Home or another app (Android 8+) puts it in the system PiP window. Close that and the TV stops sending the picture.

| Key | What it does |
| --- | --- |
| Power | Turns the TV on or off. A TV in deep standby: ☀️ Wake beside it in the TV list (computer app). |
| Arrows + OK | Move and choose. Phone app: the button under the arrows swaps them for a touchpad or Air Mouse. |
| Apps · Back · Home · Settings | The row under the arrows. Home and Back sit in the middle, easy with either thumb. |
| Volume · Channel | Two long pills; tap top or bottom. |
| Mute · Play/Pause · Prev/Next · Seek | The media row. |
| Speak | Say what the TV should do (e.g. open YouTube). Hold: the TV's Assistant. |
| More keys | Open on the TV, Input, Air Mouse, Timer, Screenshot, Draw, Scenes, Notify, Settings, Subtitles, Info; **Tools**: TV, Dim the screen, Guide, Share, Stop, TV Home, Infrared, game keys. |

**Open on the TV** (in More keys): **Open an installed app** (pick it, with a search), **Get apps** (Play Store, YouTube, Kodi…), open a link, **Send to the TV** (photos, videos, music, clipboard).

> 💡 Paste a stream link (`.m3u8`, `.mp4`) into Open on the TV: TV Channels plays it.

> 💡 TV frozen? More keys → **Tools** → **Unfreeze TV**: closes the open app and goes Home, no unplugging.

> 💡 On the computer, type straight on the Remote tab: arrows, Esc is Back, Enter, Backspace, Home; Cmd/Ctrl+V pastes text.

> 💡 In the phone app, the phone's volume keys change the TV's volume.

> ⌨️ A color key pressed here also opens the app you gave it (needs TV Setup): see [Remote keys](#keymap).

### Tips

- On the phone app, the voice button opens [Quick talk](#quick-talk): hold, speak, let go to send.

## Type on the TV

1. Tap **⌨️ Keyboard** on the Remote tab: the box opens right under the top row.
2. Type in the **Type to TV** box. Accents work.
3. Turn **⚡** on to send as you type. Off: type, then press **↵**.
4. Use **⌫** to delete and **123** for the number pad.

> 💡 Needs ADB. It types into the app's search box on the TV, so select that box on the TV first.

> 💡 Select a text field on the TV, or when the TV keyboard pops up, and the typing box and phone keyboard open by themselves in under a second. Leave the field without typing and it closes.

> 💡 Turn it off in Settings (🛠️) → About → “The TV’s keyboard” → **Don't open it**.

## The TV's clipboard (accents, pasting)

The clipboard holds the text the TV last copied. Use it to read it, copy it to your device, or send text to the TV.

1. More keys → **Open on the TV** → **The TV's clipboard**.
2. See what the TV copied. Tap **Copy here** to take it.
3. To send text: type it, send it up, then paste into the box you are typing in on the TV.

> 💡 Accent tip: put the text in the TV's clipboard, then paste. Needs ADB.

## Voice commands

The **Ask the TV: open YouTube** box (in **⌨️ Keyboard**) understands plain sentences: type and tap **🔍**, or tap **Speak**.

| Say | The TV does |
| --- | --- |
| open Netflix | Opens that app. |
| mute · pause · play | Presses that key. |
| go home · back · next · previous | Navigates. |
| volume up 5 · volume down | Changes volume. |
| turn off · turn on | Power. |
| sleep in 30 · cancel the timer | Sleep timer. |
| open channel BBC News · next channel | Opens it in [TV Channels](#channels). |
| a scene's name | Runs that scene. |

> 💡 Anything else goes to the Assistant or a YouTube search.

> The phrases are a signed list on the site: new ones arrive without an app update.

> 💻 Computer app on Windows, Linux: 🎤 sends your voice straight to the TV's Assistant (Android TVs); the TV does the listening.

The TV remote's mic button: turn on **Mic button opens TV Remote voice** in the Setup tab, Remote buttons card (off by default). Needs a newer TV Setup.

### Tips

- The phone app has [Quick talk](#quick-talk) (hold to talk, a preview of what will happen). The phrases are a signed list on the site: many more everyday commands such as close app, home, settings.

## Quick talk (phone app)

The voice button on the phone app's remote: hold, speak, let go and the TV does it.

1. The first time pick **Hold to talk (fast)** or **Tap, then talk (as before)**; it is remembered.
2. Hold the big button and speak, no dialog. ABOVE your finger the list of what the sentence will do (keys, volume, open an app, timer, scene) updates word by word.
3. Let go to send at once; slide away and let go to cancel.

- Switch back: the **Old way** chip, a long-press on the button, or Settings → Voice button.
- Uses the offline speech pack when the phone has it; asks for the microphone once, and if denied falls back to the old way. The web page does not have it yet.

## See and touch the TV's picture

You see the TV's picture on your device and tap or click as if touching the TV.

1. In the **🖥️ TV screen** card tap **▶ Watch**.
2. Tap the picture to press. Drag to swipe.
3. Tap **⛶** for full screen.
4. Phone app: the **Picture in picture** button beside ⛶ floats the picture over every screen of the app. Drop it anywhere, two fingers resize it, tap to put it back.

| On a computer | On a phone | Result |
| --- | --- | --- |
| Click | Tap | Presses that spot on the TV |
| Drag | Swipe | Swipes on the TV |
| Wheel | Two-finger swipe | Scrolls a list |
| Right-click | (none) | Back |

> 💡 Protected video (Netflix…) shows black with “the TV does not allow its picture here”. That is the app's choice, not a fault.

> 💡 Weak Wi-Fi or an iPhone: pick **Screenshots** instead of **Live**.

💡 Several devices can watch at once; the picture shows **👁 2**. Browsers on one computer app or TV web page share one stream.

## Listen to the TV on your device

Hear the TV in your own headphones without bothering anyone. Needs Android 11 or newer on the TV.

1. Tap **🎧 Listen** beside Watch.
2. Pick **How to listen**: **This device only** (the TV goes quiet) or **TV and this device** (TV on Android 13+).
3. For nicer sound tap **HQ** (160 kbit/s).
4. Sound stutters: tap **Even**. About half a second late, but steady.

> 💡 In the phone app 🎧 keeps playing with the screen off.

## Picture quality and “Frame pace”

Choppy or blurry? Open **🖥️ Picture quality** from the quality button next to ▶ Watch. The defaults suit most TVs.

| Setting | Use it when |
| --- | --- |
| Size (240p … 960p) | Choppy: go smaller. Want sharp: go bigger. |
| Or: Sharpest / Screenshots | Sharpest is on the computer app only. |
| Frames/s · Bitrate | Weak network: lower the bitrate. |
| Encoder | Auto, Hardware or Software. Try another if the picture glitches. |
| TV under 1.5 GB RAM | Holds at 720p, 20 fps to stay smooth. Pick another level and it follows you. |
| Frame pace | **Fastest** (default) shows at once. **Even** adds about 33 ms for smoother motion. Only shown in browsers with WebCodecs. |

> 💡 Choppy picture far from home (over Tailscale) is normal: lower the size and bitrate.

## Use a game controller as the remote

Plug or pair a controller (Xbox, PlayStation…) into the device running the app and drive the TV.

1. Connect the controller (USB or Bluetooth), open the **🎮 Remote** tab and **press any button**. **Controller connected** means it is ready.
2. The **D-pad** (or left stick) is the arrows: the TV's focus follows.
3. **A** is OK: opens what is selected.
4. **B** is Back. The other buttons: the table below.

| Controller | TV key |
| --- | --- |
| D-pad, left stick | Arrows |
| A · B | OK · Back |
| X · Y | Play/Pause · Mute |
| LB · RB | Volume down · up |
| LT · RT | Channel |
| Start · Select | Home · Menu |

Hold an arrow, volume or channel and it repeats. While **watching the TV picture**, A/B/X/Y, LB/RB and Start/Select go to the TV as real gamepad buttons (to play games). LT/RT stay channel keys.

> 💡 The pad does nothing while you type or a dialog is open. It works in the computer app, the TV's web page and the phone app's remote screen.

## Apps on the TV

The **📱 Apps** tab lists installed apps. Type in the search box or filter with the chips: All, Open now, 🚀 Autostart, 🔒 Must keep, ⏸ Turned off.

- **Open now**: recent apps, newest first, with their memory.
- **Switch** goes back to an app, **Close** closes it.
- **Close in bulk**: tick the apps to close (ones the TV needs can't be).

Tap **⋯** on a row:

| Item | What it does |
| --- | --- |
| Open · Stop | Starts or stops the app. |
| Details & permissions | See the app's permissions. |
| Copy package name | For shortcuts and scenes. |
| Back up (download) | Saves the app as a file. |
| Clear data · Turn off · Uninstall | Clean, turn off or remove. A disabled app can be **turned on** again. |

At the top: **🧹 Clean caches**, **📥 Restore** (from a.tar file), **↻ Reload**, **🤖 Ask AI**, **📝 In bulk** (pick many apps at once).

> ⚠️ Do not turn off apps marked 🔒 Must keep. The TV may stop working properly.

### Tips

- 🔒 Must keep marks only TV Setup, Tailscale and TV Home (as home screen). Other apps are ordinary.

## Optimize TV and Undo

A slow TV? Optimize makes apps open faster. Nothing is uninstalled, and **Undo** puts it back.

1. Go to the **📺 Setup** tab → the **⚡ Optimize TV** card.
2. Choose what you want: **Turn off transition animations**, **Use [TV Home](#home) as the home screen**, **Suggested apps to turn off**, **Turn off other apps**.
3. Tap **⚡ Run optimize** and confirm.
4. Not happy? Tap **↩ Undo**.

> 💡 Works on Android TV and Google TV. Where the TV will not let it change, TV Setup still makes Home open the one you picked.

> 💡 Lost the home screen after optimizing? On the TV: Settings → Apps → System apps → Android TV Home (or Google TV Home) → **Enable**.

> 💡 Remote buttons (Input, YouTube, Netflix) dead after the stock launcher is off? TV Setup brings them back (switch off in **Optimize TV** > **Remote buttons**). See [dead keys](#keyfix).

> 🛡️ An app that holds a saved Wi-Fi network or the HDMI / TV inputs is never turned off, cleared or removed: the TV would forget the network or lose its inputs.

> 🔁 After a firmware update TV Setup turns the apps Optimize TV had switched off, and the update turned back on, off again (at boot and daily), and puts TV Home back as the home screen. Undo stops it.

> 💾 Restore script: Undo via adb.

> 💡 Works on real TVs only, not on phones or tablets.

### Tips

## Launcher gallery

Change the TV's home screen. First is [TV Home](#home), this project's own.

1. The **Home screen** card (or **Optimize TV** > **Home screen** on the TV) → pick a launcher.
2. Press **Install and use**.
3. The TV asks **Keep**: press it. No answer in 30 seconds: the old launcher comes back.

- **Use the TV's own launcher**: the original launcher, at any time.
- Google TV: picking a launcher also turns the Google TV launcher off, so the remote's Home key opens the one you picked. **Use the TV's own launcher** puts it back.
- A TV that will not let it change fully (Chromecast, TCL, Kalley, ZTE…): TV Setup makes Home open the one picked; a long press opens the TV's own.
- Wi-Fi owned by the first-run setup: **Forget** it, join again, run it again for a full change.
- Sony BRAVIA, Xiaomi (PatchWall) and older Android TV: picking a launcher also turns the maker's own off, so the Home key no longer goes back to it. TV Setup redoes it if the stock one returns.
- A launcher that crashes twice in a row: TV Setup puts the old one back.
- Tagged **Google Play**: press **Open in Google Play**, install on the TV, then **Use**.
- A launcher without the TV's language is tagged with the one it shows (Android 13+: English).
- Several TVs: **Several TVs** > **Home screen...**

### Tips

- The gallery opens in about 0.4 s: the list first, pictures as they arrive.

## TV Home, the project's home screen

A home screen with no ads, set up from the phone or computer. Install it from the [launcher gallery](#launchers).

1. **TV** tab → **Home screen** card → **Customize TV Home**.
2. **Layout**: drag apps on the grid, then **Save order**.
3. Pick a background and widgets; preview at the top, the TV changes at once.
4. **Themes**: ready-made looks, or **Save the current look as a theme**.

| Widget | Shows |
| --- | --- |
| System | CPU, memory, storage, IP, network speed, ADB |
| Weather | Temperature, the next 3 days (needs internet) |
| Analog clock | A round face, hour and minute hands; size, face (Classic, Minimal, Roman, Modern, Digital) and light / dark theme are picked in ⚙ |
| Lunar date | Lunar day, year name, days to full moon / Tết |
| Month calendar | This month, today circled in red; the Date layout: weekday and a huge day (lunar date: the Lunar date widget) |
| Most used apps | The last 7 days, time left on [app limits](#limits) |
| Timer | When the TV turns off, bedtime |
| Note | Words pinned on the screen |

| Background | What it is |
| --- | --- |
| Suggested | A Việt Nam slideshow, calm videos, a live page; the list updates from the site, no app release |
| Custom | One color or 2-4 blended, 9 directions; on the TV ◀ ▶ change it live |
| Drawn scene | Built in, no internet needed |
| Bing | The last 8 days' pictures; press the Bing tile again on the TV = the day before |
| Wallpapers | NASA pictures, sized to the screen |
| Your photos | Up to 10; a portrait one: **Whole photo**, blurred sides |
| Web page | Paste an http/https link; the TV tries it first |

Pick several; **Change background** rotates them.

**Dim** 0-100 %: readable text on any picture.

| On TV Home | What it is |
| --- | --- |
| Top bar | Two buttons: **Network** (status) and **Settings** = the [Quick settings](#home-control), which holds Inputs and Power off / Restart / Screen off / Standby. |
| Customize | A list of groups, OK opens each: Background, **Look**, **Layout**, Widgets, **System** (screensaver, **Home screen**), **Keys & startup**, Language. |
| Home screen | In **System**: opens TV Setup's launcher picker to switch to another launcher (needs TV Setup) |
| Keys & startup | Remote keys that open apps, an app at power-on |

### Tips

- On the TV: the gear icon at the top right → Customize TV Home, or the Menu key.
- Hold OK on an app: open, pin, move, hide, rename, assign a key, lock or limit its time (asks the parent PIN), force stop, clear cache.
- Each widget **Big** or **Small**; two small ones share a column.
- **Columns**: 4 to 8 apps per row (default 5).
- Home pressed in an app: back on TV Home, on that app.
- **TV Home screensaver**: a big clock and the background while the TV is idle.
- A locked app shows a padlock, a limited one ⏳ and minutes left.
- New version: **Update TV Home** card, settings kept.

TV remote only: Downloader, code **4356026**, then make TV Home the home screen. Direct link: [tv-home.apk](https://github.com/HoangTran0410/tv-remote/releases/latest/download/tv-home.apk).

Google TV: **Accounts & profiles** is empty while TV Home is in use. To switch profile: **Use the TV's own launcher**, switch, then pick TV Home again.

## Market, News, weather map and ⚙

TV Home has two more widgets that read their data straight from the source, with no server in between. They need internet.

| Widget | Shows |
| --- | --- |
| Market | SJC bar and 9999 ring gold, Vietcombank rates, optional Bitcoin. A box or a scrolling ticker. Refreshes ~20 min; offline it keeps the last numbers with their time |
| News | Headlines and pictures from the RSS of VnExpress, Tuoi Tre, Thanh Nien, Dan Tri, VietnamNet (English TV: BBC, NPR, the Guardian, Al Jazeera). Add up to 5 RSS links of your own from the phone. OK opens a big-text reader |
| Weather | OK opens Windy's map full screen at your city (rain, wind, temperature, satellite, PM2.5), a button per layer, **Play the forecast**; Back closes |
| Recently opened | The last 4-6 apps; Left / Right pick, OK opens |
| Ad blocking | OK turns an ad-blocking DNS on / off (AdGuard...). Not the ads inside YouTube videos. If the DNS fails, it goes back in ~10 s |
| Prayer times | Today's five prayers and sunrise at the weather's place, the next with a countdown, and the Hijri date. Worked out on the TV, no internet. Pick the method, Asr school, Hijri ±1 day |

### Each widget's settings

1. **TV** tab → **Customize TV Home** → **Widgets**; switch the widget on.
2. Tap **⚙** on the widget: its own settings box, with a live preview and the layout strip with this widget framed. Each change applies to the TV at once.

> 💡 On the TV Home app and the web page. The sources are in a signed list on the site, so one can move without an app update.

> 💡 A widget’s ⚙ has a **Tonal** style (a warm widget, like weather and system) on every widget except the clock.

## QR widgets and Manage from phone on TV Home

Big QR codes on the home screen, readable from the sofa: guest Wi-Fi, a menu link, a phone number. Up to 4 widgets.

1. **TV** tab → **Customize TV Home** → **Widgets** → **Add a QR**.
2. Pick a template: Wi-Fi, Link, Text, Phone call, SMS, E-mail or Contact, then fill the form. Add a caption under the code if you like.
3. Save. The widget shows on the TV at once; order, size and on/off sit in the widget list.

### Tips

- The Wi-Fi password stays on the TV; turn on **Also write the password as text** if a guest cannot scan.
- A code too long shows its byte count: shorten the text.
- **Manage from phone** (end of the Customize list): the TV shows a QR code; scan it with the phone's camera to open TV Home's own settings page.
- No TV Remote app and no pairing code. The code works once, the session lasts 60 minutes and closes after 10 idle ones.
- Closing the QR on the TV to look at it: the phone carries on; only the old code stops working.
- Session over: open **Manage from phone** on the TV again and scan the new code.
- Tiles on the grid (pinned shortcuts too) come off with **Remove from the grid**: see [Add and remove tiles](#home-add).

## Live widgets in TV Home's app grid

A live widget is a tile in the app grid with live numbers; press it and it does the job. Provided by TV Setup and TV Channels.

1. **TV** tab → **Customize TV Home** → **Add to home screen**.
2. Under **Live widgets**: press a live widget to put it on the grid, press again to take it off.

| Live widget | Pressing it |
| --- | --- |
| Free RAM (TV Setup) | Closes background apps and shows RAM before / after; under 16 MB freed says nothing more to free |
| Sleep timer (TV Setup) | Each press adds 15 min; past 120 min it turns off |
| Keep watching (TV Channels) | Logo, the last channel, now / next; opens the channel list |

### Tips

- No live widgets: install or update TV Setup (Keep watching also needs TV Channels), from the same source.
- A live widget saying TV Setup is from another source means the two apps are signed with different keys.
- The numbers refresh every few seconds while TV Home is on screen.
- The **Add to home screen** list also has pinned shortcuts (VTV1...): see [Add and remove tiles](#home-add). The "Live" badge is now "Widget".
- No TV Setup yet: the grid shows a **Get TV Setup** tile; press to install it (or **Don't suggest again**).

## Add and remove tiles on the home screen

One panel to add, in groups: apps not on the grid, pinned channels / videos, live widgets, shortcuts, the Play Next row. Apps left out at the first run wait here.

1. On the grid press the **＋ Add** tile (or TV tab → Customize → **Add to home screen**).
2. Pinned shortcuts (VTV1...) show with a check mark: press to unpin or pin again. A new item lands at the end of the grid, already picked so you can move it.
3. Remove: hold OK on a tile → **On the home screen** → **Remove from the grid**. A shortcut stays gone, its system pin too.

- Uninstall an app (such as TV Channels) and its shortcuts and live widgets leave the grid, dock, order and picker by themselves, even if it was removed while TV Home was not running.
- The **Play Next** row is what you left half-watched in other apps, scrolling sideways. Hold OK on one: **Row position**, **Pin to the grid**, or **Don't show** that app's items.
- To show an app's items again: hold OK on the app > **On the home screen**, or **＋ Add**.
- Hold OK on any element (weather, the top buttons, ＋ Add) for its small menu, changes show at once. **Edit home screen** lets you pick the clock and widgets too; Back leaves it.
- Pinned shortcuts also have **Move**, in the same order as apps.

## Folders on the grid and the Play Next order

Put several apps in one folder tile (4 small icons and its name). Edit it on the TV or in the TV tab → Customize → Folders.

1. Hold OK on an app → **On the home screen** → **Put in a folder**: pick a folder or **New folder** and name it.
2. OK on the folder opens a panel of its apps; Back closes it. Each app keeps its own menu, with **Take out of the folder**, move to another folder, earlier / later.
3. Hold OK on the folder tile to rename, move it on the grid or delete it (its apps return to the grid). An empty folder disappears.

- An app's PIN lock and time limit still apply inside a folder.
- Play Next row: hold OK on an item → **Row order** → **Latest first** (default: as each app gives it).

## Pin a link to TV Home

Paste a web, YouTube or app link: see which app the TV opens it with, then pin it as a tile or try it on the TV.

1. TV tab → Home screen card → **📌 Pin a link**, paste the link, check the preview.
2. Press **Pin to TV** or **Try on the TV**.

- On the TV: **＋ Add → Add a link**.
- In YouTube, Share → TV Remote asks Pin or Open.
- Remote → Open on the TV → Open a link: any link asks Pin or Try too.
- A TV with a modded YouTube (SmartTube, TizenTube…) opens the right app.

## TV Home themes

A theme changes the whole home screen in one press: background, widgets, widget style, columns and rows, live widgets.

1. On the TV: ⚙ → **Customize TV Home** → **Themes**. Phone, computer: **TV** tab → **Customize TV Home** → **Themes**.
2. Each tile is the home screen in miniature: the real background, where the widgets go, the app grid. Pick one and the TV changes at once.
3. Like the current look: **Save the current look as a theme** and name it. Your photo, video or web page is captured small for its preview.
4. Share: tap ⓘ on a theme → **Export theme** gives a `.tvtheme.json` file; **Import theme** reads it back (**Include photos/videos** carries your own photos and videos, 60 MB each).
5. Send to everyone: ⓘ on your theme → **Share to the store** (phone, computer), enter a display name, **Send**. It shows in **Community** once reviewed; 3 themes a day at most.

| Ready-made | For |
| --- | --- |
| Modern, Morning, Day | Big clock, weather, lunar calendar |
| Rice terraces, Beach sunset, City night | A photo or video background from the web |
| Gamer, Match day, Cinema | With a fitting live widget (Free RAM, Keep watching) |
| Kids, Easy to read, Bedtime | Big text, few things, the sleep timer |

### Tips

- Switching from an unsaved look keeps it as **Before this theme**, so you can go back.
- Hold OK on a theme (web: the ⓘ button) to see what it sets.
- Ready-made themes update from the website, no new TV Home needed.

## Sky by the sun and OLED care

Two options for TV Home's look, worked out on the TV with no network.

1. Pick the **Sky by the sun** background (Customize → Background): the sky goes from night to dawn, day and dusk with the sun.
2. The place is the Weather widget's, else the time zone. Worked out on the TV, no network, repainted every few minutes.
3. OLED screen: pick the **Pure black (OLED)** background, then turn on **Move the screen a little every minute** (Customize → Look). Off by default.
4. The whole home screen moves 1 dp a minute inside a 6 dp square, with no visible jump; the wallpaper stays.

## Quick settings (TV Home)

The Settings button on the top bar opens a panel in Customize's style: Network (Wi-Fi name), Inputs (or Bluetooth when the TV has no inputs), Sleep timer, Kids mode, TV settings, Customize.

Kids mode: tap to turn on; when on, a tap (asks the PIN) picks the apps or turns it off. Needs TV Setup (greyed without it). Round buttons: Clear RAM, Turn off the TV (Screensaver, Screen off, Restart, Power off).

**Brightness** (sun icon): OK to change it, Left / Right in steps of 10 %, Back when done; 100 % = no tint.

The **Tint color** button beside it: Dark, Blue light filter, Yellow, Sepia, Night red, Blue. Needs a newer TV Setup.

Customize → Look → **Show only plugged-in HDMI/AV inputs** (off by default): empty ports go in More inputs.

TV settings opens even on TVs that hide the standard Settings screen (HY300 projectors, Xiaomi, TCL, Fire TV).

## Hold OK on an app in TV Home

Holding OK on an app opens its own menu, in groups. Back on the remote returns to the main menu.

| Group | What is in it |
| --- | --- |
| On the home screen | Move, rename, hide, take off the grid; Edit home screen |
| Start & keys | Open when the TV turns on; assign a number or color key |
| Lock & limits | Lock the app, a daily limit of 15-120 min; asks the parent PIN (made on the TV, 4 digits) |
| Maintenance | Force stop, clear cache, app info, uninstall |

### Tips

- Assigning a key: the page is drawn like the remote, each key showing the app on it. Press a number or color key on the remote to assign it at once.
- Rename: tell two apps with the same name apart (like two YouTubes). The name shows when the tile is focused.
- Only TV Home installed (no TV Setup): the lock still works, TV Home keeps the PIN and the locked apps itself. Daily limits need TV Setup to count time.
- TV Home's lock blocks opening an app from TV Home (grid, dock, assigned keys). The remote's own app button, the Assistant or Recents are NOT blocked: that needs TV Setup.
- Install TV Setup later: the first time you open Lock & limits with the right PIN, the locks move to TV Setup.
- Forgot the PIN: with only TV Home, go to Settings → Apps → TV Home → Clear data (this also clears TV Home's settings).
- With TV Setup: change the PIN from the phone or computer app, no old code needed.
- 5 wrong codes in a row wait 1, 5, 15, then 60 minutes.
- A real kids' lock: lock the Settings app too (kids mode), otherwise a child can uninstall TV Setup from Settings. TV Setup asks the PIN when it or its Manage screen is opened.

## Remote keys: give a key an app, fix dead keys, hold

Color keys, number keys and buttons that no longer do anything can open the app you choose. It needs [TV Home](#home); to work in every app it also needs TV Setup.

1. On the TV: the **⚙** icon at the top → **Quick settings** → **Customize** → **Keys & startup** → **Quick keys**. Phone or computer: the same name, in the **Keys & startup** card.
2. Pick a key's tile, then an app, a shortcut or a live widget. The first pick switches the keys on. Outside TV Home the TV says "TV Setup opened…", so it is not a fault.
3. To open an app when the TV turns on: pick it in the **At power-on** tile. It opens once per power-on, and not if the TV woke into another app.

| Key | When it can be given an app |
| --- | --- |
| Red, green, yellow, blue | Always: no app uses them |
| 0-9 | On TV Home: a press. In other apps: hold 2 s (needs TV Setup) |
| YouTube, Netflix, Prime Video | Always: given another app, the button opens it instead (needs TV Setup) |
| TV, Input, PatchWall, All apps, Settings | Only while the button is dead. A working button is never taken |

> 💡 A greyed tile saying "not on this remote": this remote has no such key. Without TV Setup the keys work on TV Home's own screen only.

> 💡 Dead buttons, hold-to-use and the TV Setup Keys add-on: see [Fix dead keys, hold to switch](#keyfix).

### Tips

- Quick keys use the same keypad: see [Quick keys](#home-quickkeys).

## TV Home Quick keys

Quick keys open an app from the home screen with one number or color key on the remote.

1. **TV** tab → **Customize TV Home** → **Quick keys**.
2. The keypad is the same as "Assign remote keys": 1-9, 0, the 4 color keys, then **At power-on** and the keys the remote lacks. Press a tile to pick an app.
3. An assigned tile shows the app's banner or icon large. Pressing the real number or color key on the remote picks its tile.

- The **At power-on** slot: pick an app = it opens at power-on, clear = off. **Auto-open … at power-on**: opens it (empty: TV Home) even if the TV woke into HDMI or another app, except during a cast.
- Live widgets can be assigned too and show their own icon.

## Fix dead keys, hold to switch

These need TV Setup on the TV and ADB switched on. They run by themselves once on.

| What | How it works |
| --- | --- |
| 🔧 Dead keys | With the stock launcher off, Input, YouTube, Netflix, Prime or All apps may do nothing. TV Setup gives them their old job back by itself. |
| What the old job is | Input opens the input list; YouTube, Netflix, Prime open that app; Settings opens Settings; All apps opens TV Home when nobody answers it. Switch off in [Optimize TV](#optimize). |
| ✋ Hold 2 seconds | Switch on "Hold for 2 seconds to change the input / open Settings" in the Setup tab, Remote buttons card. A quick press of Input or Settings does nothing. |
| From the phone | A press from the [phone remote](#remote) still acts at once, no hold needed. |
| ⌨️ Test remote keys | A TV screen shows each key pressed: name, code, which remote, press or hold, remapped or not. Open it from Optimize TV, TV Home Quick keys or the web page. Exit: hold Back for 2 seconds. |
| 🧩 TV Setup Keys | A small add-on that holds the key catching. TV Setup installs it over ADB when needed: a key to fix, or hold-to-use switched on. |
| Why it is separate | So Google Play Protect does not block TV Setup when you install it with Downloader. |

> 💡 Odd buttons on some TV models (e.g. the Xiaomi MiTV-MOOQ0's Input button) are fixed from a list on the site, with no app update. An odd button on your remote: send a support code.

## TV Channels, free TV channels

An app on the TV that plays channels from iptv-org's public lists and VieON's free channels (Vietnam). No account.

1. **TV** tab → **TV channels** card → **Install TV Channels**.
2. Type a channel's name and press **Open**, or say “open channel BBC News”.
3. Arrange favorites and groups here, not with the D-pad: **TV channels** card → **Manage channels**. Saved at once; the TV updates at once. Needs a newer TV Channels.
4. **Export** / **Import** copy favorites and groups to another TV: merge or replace. The country picker has a search and each country's number of channels.
5. Your own m3u links (up to 5): on the TV, TV Channels → **Add an m3u link**, or **Manage channels** → **🔗 Your links**.
6. On the TV: ▲▼ changes channel, OK shows info, OK again the list, ▶ shows what is on, ▶ again the guide. Hold OK on a channel: its guide, add it to a group, pin it to the home screen.

| On the TV | How |
| --- | --- |
| 🔍 **Search channels** | First row on the left, OK, type a name. The chosen country comes first, then “Other countries”. The first search downloads about 3 MB. |
| 🔔 **Reminders** | In the guide, OK on a programme to come (OK again removes it). Two minutes before, a card shows in the corner: OK watches, Back dismisses. |
| 🔔 **Over other apps** | Needs “display over other apps”: the phone / computer app turns it on when you use the TV channels card. Screen off: no reminder. |

Play a stream link (`.m3u8`, `.mp4`): Remote → More keys → **Open on the TV**, paste it.

A channel that does not play tries its next stream by itself; when all fail, OK tries again. Some channels play only in their own country.

A newer TV Channels is offered by the app itself: **Update now**, **Later** or **Skip this version**.

TV remote only: Downloader, code **8135635**. Direct link: [tv-channels.apk](https://github.com/HoangTran0410/tv-remote/releases/latest/download/tv-channels.apk).

### Tips

## TV Games, games on the TV

An app on the TV that plays retro and web games, with phones as pads (up to 4). Playing needs no ADB and no TV Setup: scan the QR on the TV with a phone.

TV remote only: Downloader, code **2294968**. Direct link: [tv-games.apk](https://github.com/HoangTran0410/tv-remote/releases/latest/download/tv-games.apk).

1. **TV** tab → **TV games** card → **Install TV Games** (needs ADB, only to install). Not in the latest release yet: the card says so.
2. Press **Open TV Games**, then scan the QR on the TV with a phone to use it as a pad.
3. Card and board games (Tiến lên, Color Cards, Tycoon…): each player holds their hand on their phone, taps or swipes to pick cards, then presses a button. Bots take empty seats.
4. Alone with the TV remote: OK is the A button. Start, Select, B: **Back → Map remote keys**, then press the keys you want (color keys, ⏯…). Or **🎮 Gamepad** on the remote.
5. Send someone out of the lobby: ▲ picks them, OK.
6. Tilt games (Balloons, Pool, Paper Planes…): on the pad tap **Turn on tilt**. The browser warns as the pad switches to https: tap **Advanced** then **Proceed** (iPhone adds **Allow**). You keep your seat.
7. A web game stuck or just updated? On the TV hold OK on the web game → **Open the latest version**; on the phone the ⚙ menu → **Reload the game**. Phones stay connected.

### Tips

## TV Apps, free apps for the TV

A list of free, open-source TV apps (SmartTube, Kodi, VLC, Jellyfin…). Each comes straight from its maker's release and is installed only when it carries the maker's signature.

TV remote only: Downloader, code **1082201**. Direct link: [tv-apps.apk](https://github.com/HoangTran0410/tv-remote/releases/latest/download/tv-apps.apk).

1. From a phone or computer: **📱 Apps** tab → **📦 TV Apps** card → **Browse apps** → **Install** (needs ADB).
2. On the TV: open the **TV Apps** app (TV Setup installs it once it has ADB, or from its row of TV apps). Pick a kind, pick an app, press OK. Hold OK: Open, Install again, Details.

- With TV Setup: installs at once, the TV asks nothing. Without it: Android asks first, and the first time TV Apps must be allowed to install apps (it shows how).
- Add-ons (for Stremio, Kodi) are never installed for you; the details page only names them.

## Files and installing APKs

The **📁 Files** tab is the TV's file manager.

| Task | How |
| --- | --- |
| Browse | Tap a folder. Tap **＋ Folder** to make one. |
| Send a file to the TV | Pick a file, or drag and drop onto the page. |
| Play on the TV | Tap **▶ TV** by the file. |
| Install an APK already there | Tap **Install this APK on the TV**. |
| Download · Rename · Delete | In the **⋯** menu. |
| Fetch a link onto the TV | **⬇️ Download to the TV**, paste the link. |
| Install an APK from a link or file | **📦 Install an APK**. |
| Run commands | **⌨️ Terminal** (for the technical). Each command and its output has its own copy button, plus **Copy all** and **Clear**. |

> 💡 Long transfers show progress and a **Stop** button.

### Tips

- An APK that clashes with a TV system app: tap **Install as a separate app**; it is renamed (.copy) and installed beside the built-in one. Not for.apks /.xapk.

## TV health

The **🩺 Health** tab shows how the TV is doing.

| Sub-tab | Shows |
| --- | --- |
| Now | Overview (with the night maintenance result, 02:00-05:00: cache trimmed, most-used apps optimised), system load, temperature, network, Bluetooth, hardware. |
| History | Charts over time, and which apps crash. |
| Sleep | When the TV was on or off. |
| Capabilities | What the TV supports. |

> 💡 History empty (“No data yet”) on a TV that will not let ADB read TV Setup's folder, such as a TCL on Android 11? Update to the newest TV Setup: the app shows an **Update** button.

Slow network? Tap **⚡ Speed test** on the **📶 Network** card: the TV measures its own ping, download and upload (about 15 s) and says whether that is enough for 4K, Full HD or HD.

TV low on memory? Tap **🧹 Free up RAM**, or **⏹ Stop** a running app in the list.

## Privacy check

Open **Health → Privacy** and choose **Check**. The scan runs quietly on the TV without slowing other apps, and apps appear as they are found.

Each app lists what was SEEN (microphone used 12 minutes ago, camera permission, reads notifications, content recognition…); it never claims an app is listening.

- **Look closer**: mic / camera used in the last 24 hours, content recognition (ACR), accessibility. **Sensitive access**: holds a permission or used it long ago. **Low concern**: folded.
- **Revoke** removes the app's mic, camera and location permissions (with **Grant again**); **Disable** shares Optimize TV's undo list. Apps the TV needs have no buttons.
- Every app has the Apps tab’s **⋯** menu. Apps the TV needs (Google’s assistant, the keyboard) cannot be disabled, but one permission (e.g. the microphone) can be taken in **Details & permissions**: asked first, given back the same way.

## Anonymous stats

Once a day each app sends a small note to learn which TVs work well. It feeds the "TVs it ran on" part of the home page.

| Sent | Never sent |
| --- | --- |
| The TV's brand, model, chip, Android version; the app's version; whether ADB / picture / Optimize TV worked; a random ID | IP address, serial number, TV name, installed apps, account |

1. Turn off: **Settings → Send anonymous stats** (the page, the Computer app, the TV) or **Settings → Privacy** (phone app).

> 💡 Off sends nothing at all. On by default. App errors, ADB that cannot connect and rolled-back updates are sent too, with IPs and paths removed. TV Home, Channels and Games follow TV Setup's switch.

## Manage right on the TV

No phone or computer at hand? Do it on the TV itself with the TV's own remote.

1. Open **TV Setup** on the TV.
2. Choose **Manage on this TV**. The TV then asks: scan the QR code to manage on a phone (easier typing), or choose **Manage on the TV**.
3. You get the tabs **Apps**, **Files**, **Health**, **Automation** and **Setup**. Use the arrows and OK to choose. A mouse (Air Mouse, a USB one) clicks and scrolls across the whole screen.
4. Press **Back** to close or leave.

TV Setup also has: **Optimize TV** / **Undo**, **Tailscale**, **Update**, a switch for the web remote, and **Unpair** for every device.

> 💡 It works even when the web remote is switched off.

## TV Setup repairs DNS and ADB by itself

TV Setup watches two things that often cut the TV off, and fixes them itself.

| What | TV Setup does |
| --- | --- |
| A DNS filter (the **🛡️ DNS filter** card) stops working: YouTube says it cannot connect although the TV is online | Checks now and then and when the network changes. If names fail while addresses answer, it **turns the filter off** and tells you on the Manage page. |
| The TV switches **ADB debugging** off | Switches ADB back on (it was granted the right once through ADB). |

> 💡 You need to do nothing. To use a DNS filter again, switch it on in the Setup tab (needs Android 9 or newer).

## Stay reachable when the TV is off

Once the TV is off it can fall into deep sleep and stop answering. This option keeps the chip awake so you can still connect.

1. Open **🩺 Health → Sleep**.
2. Tick **Stay reachable when the TV is off**.
3. Untick and the TV sleeps deeply again.

> 💡 Uses a little more power. Needs TV Setup 0.7.1 or newer. Not offered on TCL TVs, which sleep deeply whatever an app holds.

## Send a notification to the TV

1. More keys → **Tools** → **Notify** (🔔 Notify the TV).
2. Type a **title** (optional) and the **message**.
3. Pick a corner ↖ ↗ ↙ ↘ and the seconds: 3, 5, 10 or 30.
4. Tap **Send**. A small card shows on the TV, then goes away.

> 💡 Needs a recent TV Setup. If it is missing the app asks with one Update button.

## Draw on the TV

1. Tap **✏️** by the picture (or More keys → Draw on the TV).
2. Pick a color and draw on the picture. **↩** undoes a stroke.
3. Add **💬 Text** or a **🖼️ Picture**. For a picture, size it with − / + then tap **✓ Put on TV**.
4. Tap **✓ Done** when finished. To clear: **🗑 Clear all**.

> 💡 The drawing sits over every app and clears after 10 minutes. Needs TV Setup.

## Put a picture on the TV

Two ways: lay a picture **over the TV's screen** (above every app), or **play** a photo or video from your device.

1. Tap **✏️** by the picture, then **🖼️ Picture**.
2. Pick a picture from your device.
3. Size it with **−** / **+**. Changed your mind? Tap **Cancel**.
4. Tap **✓ Put on TV**.

To play it properly: **More keys → Open on the TV → Play a photo / video from the phone** (on a computer: from this device).

> 💡 The overlay clears after 10 minutes, or tap **🗑 Clear all**. Needs TV Setup (if missing the app asks to update).

## TV screenshot

1. Tap **More keys** on the Remote tab.
2. Choose **Screenshot**.
3. The picture is saved to your device, named like `tv-1729512345.png`.

> 💡 Protected apps may come out black.

## Screen off, dim the screen

**Screen off** is in **More keys → Sleep timer**; **Dim the screen** in **More keys → Tools**, and on the TV in **TV Home's Control Center** (the sun bar, the colour button beside it):

| Entry | What it does |
| --- | --- |
| Screen off / on | The screen goes black; sound plays on and the app still controls the TV. To turn it on: tap again, or press the TV's own remote. |
| Dim the screen | A tint over every app: Dark, Blue filter, Yellow, Sepia, Night red, Blue, at 5 to 90%. Stays until you tap Off. |

> 💡 Dimming is truly darker on OLED; an LCD keeps its backlight, but warm colors still cut blue light. Needs TV Setup.

## Sleep timer

1. Tap **More keys → Sleep timer**, or open **⏰ Automation → 😴 Sleep timer**.
2. Pick 30 minutes, 1 hour or 2 hours. Other lengths (15 min, 45 min, 1h30) and **Custom…** (minutes or a time like 23:30) are under **Other times**.
3. To keep listening: **Screen off / on**. The first key on the TV's remote only turns the screen back on; it does not open the app in focus.
4. Cancel with **Cancel the timer**. Want more time: **15 more minutes**.

> 💡 The timer lives on the TV, so it runs even when your phone is off.

## Bedtime

1. Tab **⏰ Automation → 🌙 Bedtime**.
2. Tap **Add a bedtime** and choose the start and end.
3. Save. In that window, if anyone turns the TV on, it warns first and goes off a minute later.

> 💡 Needs TV Setup on the TV. The TV does the turning off itself.

## Welcome screen

1. Tab **⏰ Automation → 🏠 Welcome screen**, tap **Choose a video or picture** (mp4, webm, mkv, jpg, png, webp; 200 MB at most).
2. Choose **Every time it is on** or **After a restart**; for a picture, the seconds. Tap **Show now** to see it at once.
3. Each time the TV turns on, the welcome screen shows. Any key closes it. Several TVs: **📦 Several TVs at once → ⋯ → Set welcome screen**.

> 💡 Needs a newer TV Setup (0.12.0 or later). Not shown again within 2 minutes. The TV does the showing itself.

## App, volume and memory limits

1. Tab **⏰ Automation → ⏳ Daily app limits**.
2. Tap **Add an app limit**, pick the app and minutes per day (30 min to 4 h).
3. When time is up the TV warns, then closes that app.
4. **+30 min** gives the app 30 more minutes today; **Reset** takes them back.

| Card | What it does |
| --- | --- |
| 🔊 Volume limits | By time, max 10 to 90%, for the TV's speakers. Does not work with an external HDMI speaker, nor while 🎧 plays the TV's sound elsewhere (its speakers are silent then). |
| 🧹 Full memory notice | Warns at 80 to 95% memory. Off by default. |

> 💡 All of it lives on the TV and needs TV Setup. It holds even with your device off.

## Lock apps with a PIN

1. Tab **⏰ Automation → 🔒 Lock apps with a PIN**, tap **＋ Add**.
2. Pick the app. The first time, also set a PIN of 4 to 8 digits.
3. Open that app on the TV: it asks for the PIN. Type it with the remote's number keys or the keypad on screen.

Once unlocked it stays open until another app comes up or the TV turns off; opening it again asks again. 5 wrong PINs: a minute's wait.

> 💡 Needs TV Setup. The PIN is kept only on the TV (hashed), never sent anywhere.

1. **🛡️ Kids mode** (same Automation tab): when on, every app asks for a code except the few you allow; TV settings stay locked.
2. Pick the ways to unlock: PIN, **Google Authenticator** (scan the QR, type 6 digits; the TV clock must be right, the page warns if off) or ask a parent on Telegram.
3. Save the 8 **backup codes** when adding Authenticator (each works once). If a child may know the PIN: change it, or turn it off once Authenticator is on.
4. **Open 30 min / 1 hour / 2 hours / Rest of today**, **Lock now**, and the **📺 Total TV time** card per day (+30 min when needed).

> 💡 TV Home → **Quick settings** has a **Kids mode** tile; turning it off asks the PIN.

## App schedule: days and hours

For example: YouTube opens only Monday to Friday, 16:00 to 18:00, at most 30 minutes. Outside those hours the app is closed at once and the TV says why.

1. Tab **⏰ Automation → 📅 App schedule**, tap **＋ Add**.
2. Pick the app, the days it may open, and the start and end time. For a cap on minutes inside the window choose **At most**. To block an app for good tap **Block entirely**.
3. Different hours on different days (Mon–Fri 16:00–18:00, Sat and Sun 08:00–22:00)? Tap **＋ Add a time window**, each with its own days and hours, remove with **✕**. Up to 8; inside any one it may open.

A window that runs past midnight (22:00 to 02:00) belongs to the day it starts on.

No PIN is asked inside the window, unless the app also has a [PIN lock](#lock). For more minutes today use **+30 min** on the app's row.

> 💡 Needs a recent TV Setup (several windows need a newer one). The schedule runs on the TV itself, no phone needs to stay on. It cannot block YouTube Shorts alone, only the whole app.

## Scenes (one tap, many steps)

A scene is a chain of steps run with one tap. For example “Movie”: turn on the TV, open Netflix, set volume 20.

1. Tab **⏰ Automation → 🎬 Scenes → ＋ New scene**.
2. Name it and pick an icon.
3. Tap **＋ Add step** and choose a kind (table below).
4. Tap **▶ Try** to test, **💾 Save** to keep.

| Step | What |
| --- | --- |
| Turn the TV on · off | Power. |
| Open an app · a link | Starts an app or link on the TV. |
| Set the volume · Press a key | Sets a level or presses a key. |
| Switch input | HDMI or another source. |
| Wait | Waits a few seconds between steps. |
| Sleep timer · Send a notification | Like the separate features. |

Run a scene from **More keys → Scenes**, by voice (say its name), or from the phone's widget, Quick Settings tile or shortcut.

**📌 Add to TV Home** (in the scene): the scene becomes a TV Home tile, also in ＋ Add → **Scenes & shortcuts**. Needs a newer TV Setup.

Back up with **⬇️ Export a backup file** and **⬆️ Import from a file**.

## Control the TV from Telegram

Message your own bot to turn the TV off or on, set the volume, open apps, take a screenshot. The bot runs in TV Setup and opens no port to the Internet.

1. In Telegram open **@BotFather**, send `/newbot`, pick a name and a username ending in `bot`.
2. BotFather returns a **token** (`123456789:AAH…`). Whoever has it controls the bot: share it with no one.
3. 🛠️ Settings on the TV or its web page → **Control by Telegram** → **On**, paste the token, **Save**.
4. Press **Make pairing code**: a 6-digit code shows in Settings and on the TV screen, single use, 5 minutes.
5. Open the bot (private chat or group), send `/pair 123456`. Unknown chats get no answer at all.
6. Five wrong tries cancel the code and lock pairing for 15 minutes; a new code unlocks.

Commands:

```
/status  /off  /on  /vol 30  /key HOME
/open netflix  /scene Xem phim  /timer 30 (0 = huỷ/cancel)
/say Ăn cơm nhé  /shot  /remote  /help
```

A new chat gets **Remote only** rights; the owner can raise it to **Full access** in the chat list.

Screenshots pass through Telegram's servers. Stop any time: **Off**, **Remove token** (forgets chats too) or remove one chat.

## Telegram: alerts and bedtime

More bot commands, and the TV messages you when something happens. A paired chat is needed (see the previous topic).

```
/apps  /usage  /now  /scenes
/channel VTV3  /type xin chào
/bedtime  /limits  /schedule  /alerts
```

1. **/apps** shows today's most used apps; **/usage** gives the top 5 and the total screen time.
2. **/now** tells what is playing, with a screenshot button. **/channel** opens a channel in TV Channels.
3. **/bedtime**, **/limits**, **/schedule** show the rules. A **Full access** chat can switch bedtime, block or unblock an app; every change has an **Undo** button.
4. **/alerts** picks what the TV sends by itself: TV on or off, app closed, bedtime, sleep timer, a new device. At most one per kind per minute.

In **🛠️ Settings** you see the bot's status, a QR to pair with one scan, each chat with its rights and alerts, and a 7-day history. The words of /type and /say are never kept.

## Home Assistant · Shortcuts (Siri)

Let iPhone Shortcuts, Siri or Home Assistant control the TV with one link.

1. The **computer app** or the **TV web page** (see “[iPhone](#web-iphone)”) → **🛠️ Settings** at the top. Not in the phone app.
2. The **Home Assistant · Shortcuts** card → **On**.
3. Tap **📋 Copy** on the link.

The link:

```
http://192.168.1.20:8765/api/hook?k=3f9a1c7e5b2d4a60b8c1d9e7f2a4b6c8&key=HOME
```

The TV's address and port, `k=` the secret token, `key=HOME` what to do. Open it in a browser to try.

**iPhone: Shortcuts and Siri**

1. Get the link to the iPhone (Notes, AirDrop), or copy it from the TV web page in Safari.
2. **Shortcuts** app → **+** → **Add Action**.
3. Pick **Get contents of URL**, paste the link in **URL** (keep **GET**).
4. Name it (e.g. “TV Home”), tap **Done**.
5. Say **“Hey Siri, shortcut name”** or tap it. Same Wi-Fi as the TV; the first time, **Allow** the local network.

**Home Assistant**

1. Tap **📋 YAML for Home Assistant**.
2. Open `configuration.yaml` (the File editor add-on), paste at the end, save: ``` rest_command:   tv_key:     url: "http://192.168.1.20:8765/api/hook"     method: post     headers:       x-tvr-hook: "3f9a1c7e5b2d4a60b8c1d9e7f2a4b6c8"     content_type: "application/json"     payload: '{"key": "{{ key }}"}' ```
3. Restart (**Developer tools → YAML → Restart**).
4. Use it in a script or automation. Press Home: ``` action: rest_command.tv_key data:   key: HOME ```
5. Open an app / run a scene: copy the `tv_key` block as `tv_app`, change `payload` to `'{"app": "{{ app }}"}'` or `'{"scene": "{{ scene }}"}'`.

Change the end of the link to do something else (a space is `%20`):

| Add to the link | The TV does |
| --- | --- |
| `key=HOME` | Presses a key (HOME, OK, BACK, MUTE…). |
| `app=com.netflix.ninja` | Opens an app by package name. |
| `scene=Movie` | Runs a scene. |
| `text=hello` | Types text. |
| `volume=20` / `up` / `down` / `mute` | Sets volume (0 to 100), or up, down, mute. |
| `power=on` / `off` / `toggle` | On, off or toggle. |
| `/api/hook/state` | Read only: is the TV on, what is playing. |

Home Assistant sends the token in the `x-tvr-hook` header, out of the logs.

| Where it runs | Port |
| --- | --- |
| TV Setup (recommended, the TV is always on) | 8765 |
| Computer app (opens only while on) | 8766 |

> ⚠️ The link is a key. If it leaks tap **🔄 New token**: the old one dies at once.

> 💡 Home network only; away from home use Tailscale.

### Tips

## Several TVs

1. Save each TV: connect once and it stays in **💾 Saved TVs**.
2. Switch with **📺 Change TV**.
3. To act on many: open **📦 Several TVs at once**, tick the TVs, then choose **📦 Install APK** or **🔁 Restart**.

> 💡 The phone app has the same on its TV list.

> 💡 **🧹 Check-out** (guesthouses): clears the chosen apps' data, sets the volume, signs out paired browsers, goes Home. Asks first.

## See every TV at a glance

Each saved TV has a line: **On**, **Standby** or **No answer**, with its model and the app open. It refreshes every 10 seconds.

**Show screens** (off by default): a small picture of each TV every 5 seconds. LG, Samsung, Roku and TVs without ADB have none.

## Share with others

1. More keys → **Tools** → **📲 Share**.
2. Choose **📺 Share the TV**: a one-time QR that expires in 10 minutes.
3. The other person (same Wi-Fi) scans it and can use the remote.
4. To give them the app: choose **📱 Share the app**.

| Access | Can do |
| --- | --- |
| 🛡️ Full access | Everything, Manage included. |
| 🎮 Remote only | Keys, typing, picture, opening apps, sleep timer, saved scenes, TV channels. No Manage. |
| 👁️ View only | The picture and what is playing. |

> 💡 Change or revoke in **Setup → 📱 Paired devices** (Unpair or Unpair all).

## Away from home (Tailscale)

Tailscale is a free private network joining your devices. No ports opened to the Internet.

1. Tab **📺 Setup → 🌐 Tailscale**, tap **⚡ Install and sign in to Tailscale**.
2. Wait about half a minute, tap **🔑 Open the Tailscale sign-in page** and choose Connect.
3. In the Tailscale admin page choose **Disable key expiry** for the TV so it never drops.
4. Install Tailscale on your device and sign in to the same account.
5. Connect to the TV's `100.x.x.x` address.

A dozing TV wakes with Power, even over Tailscale. A fully off TV needs Wake-on-LAN from inside the home network.

## LG, Samsung, Roku, VIDAA TVs

Beta. Only in the phone app and the computer app.

1. Turn the TV on and join the same Wi-Fi.
2. Scan, pick the TV.
3. If the TV asks to allow, choose Allow (VIDAA asks a 4-digit code).

|  | LG | Samsung | Roku | VIDAA |
| --- | --- | --- | --- | --- |
| Keys, typing, apps, power on, scenes | ✓ | ✓ | ✓ | ✓ |
| Switch input | ✓ |  | ✓ | ✓ |
| Volume to a number | ✓ |  |  | ✓ |
| YouTube links · screen off | ✓ |  |  |  |
| Air Mouse (pointer) | ✓ |  |  |  |

> 💡 Picture, files and app management are Android TV only. If a TV misbehaves: About → **📋 Send the log**.

> 💡 Sony BRAVIA, Xiaomi, TCL… run Android TV, so use them like any Android TV (not the table above). Changing their home screen: [Launcher gallery](#launchers).

## Turn off tracking on Samsung, LG, Roku, VIDAA TVs

These TVs let no app do it for you: it takes the remote, once. Menu names change between years: if the words differ, look under **Privacy**.

In the phone or computer app: **More** › **Tools** › **Turn off tracking** opens Settings and shows these paths.

|  | Tracking | Ads | Mic |
| --- | --- | --- | --- |
| Samsung | Settings › All Settings › General & Privacy › Terms & Privacy › **Viewing Information Services**: Off. | Same place: turn off **Interest-Based Advertisements**, turn **Do not track** on. | Voice › Bixby › **Voice Wake-Up**: Off. |
| LG | All Settings › General › System › Additional Settings › **Live Plus**: Off. | User Agreements: untick **interest-based advertising** and **Who.Where.What?**; Additional Settings › Home Settings: turn off **Home Promotion** and **Content Recommendation**. | Turn off **Hands-free Voice** ("Hi LG"). |
| Roku | Home › Settings › Privacy › Smart TV Experience › **Automatic Content Recognition**: Off. No such menu: my.roku.com › Privacy Settings. Roku sticks have none. | Settings › Privacy › Advertising › **Personalize ads**: Off. | Settings › Privacy › Microphone: block per channel. |
| Hisense VIDAA | Settings › (System ›) Advanced Settings › Enhanced Viewing › **Automatic Content Recognition**: Off, then decline the pop-ups. | Same place: turn off **Personalized Ads** and **Content Recommendation**. | System › Voice Service › **Hands-Free**: Off. |

- **Content tracking** (ACR): the TV samples the picture and sound of whatever is on, HDMI too, to sell ads. Turning it off changes nothing you watch.
- Older menus: Samsung 2019–2021 under Settings › Support › Terms & Privacy; LG 2025 under Support › Privacy & Terms › User Agreements › **Viewing Information**.
- Block more on the network: set the TV's DNS to `94.140.14.14` (AdGuard DNS) in its network settings, or use Pi-hole / NextDNS with [HaGeZi](https://github.com/hagezi/dns-blocklists)'s lists for Samsung, LG and Roku.
- Don't decline the required terms: the TV would lose its apps.
- Android / Google TV: [Privacy check](#privacy) and [Optimize TV](#optimize) do it for you.

Menu paths from [cleanyour.tv](https://cleanyour.tv/not-android).

## Phone app extras

| Feature | How |
| --- | --- |
| The phone's volume keys | Change the TV's volume. |
| Widget · Quick Settings tile · shortcuts | One tap presses a key or runs a scene without opening the app. |
| 🎧 Listen | Keeps going with the screen off. |
| Mirror the phone to the TV | Open on the TV → Mirror the phone’s screen on the TV. |
| Game controller | See [Use a game controller](#gamepad). |

## App settings

1. Tap **🛠️** in the top corner to open **Settings**.
2. **Theme**: system, light or dark. **Language**: Tiếng Việt, English or Русский.
3. **When pressing a key** (phone): vibrate or not. **The TV’s keyboard**: whether the [typing box opens by itself](#typing) when the TV selects a text field.
4. **App on this computer**: **🔄 Check for a new version** and **Quit** (stops the program; the TV is not affected).
5. Further down: [Home Assistant · Shortcuts](#hook), and About has [Send the log](#log).

### Tips

- **Send anonymous stats** (on by default): see [Anonymous stats](#census). In the phone app it is under Privacy.

## The self-check when it will not connect

Cannot connect? The app checks by itself in under 4 seconds and gives **one conclusion with the fix**. No guessing.

| The app says | The fix |
| --- | --- |
| The TV is not answering | The TV is off or asleep. Turn it on, then tap **🔄 Try again**. |
| The TV has a new address | Tap **📍 Use the address …**. |
| This TV only has Wireless debugging | Pair once with the 6-digit code: Developer options → Wireless debugging → Pair with pairing code. |
| ADB debugging is off on the TV | Keys still work. For picture and apps tap **🧭 Turn ADB on for me** or switch it on in Developer options. |
| The TV is waiting for you to allow | Look at the TV: tick “Always allow”, choose OK. |
| This device is on another network than the TV | Join the TV's own Wi-Fi, not a guest network or mobile data. |
| This device is not on Wi-Fi | Turn Wi-Fi on. |
| Tailscale is off on this device | Turn Tailscale on, then try again. |
| A VPN is in the way | In the VPN app allow the local network (LAN), or turn the VPN off. |
| The TV's date and time are wrong | On the TV: Settings → System → Date & time, set automatic. A wrong clock makes the secure connection fail. |
| The TV is on the network but not listening | The TV is dozing. Press power on its own remote and wait a few seconds. |
| This Fire TV never finished its first setup | The Home and Alexa buttons stay dead. Tap **Fix** to mark setup as done. |

Buttons in the box: **🔄 Try again**, **🔍 Scan**, **🧭 Turn ADB on for me**, **Fix** (only for a Fire TV whose setup never finished). Open the **What was checked** list to see what the app tried.

> 💡 The box shows in the computer app, the phone app's Manage screen and the phone app's TV list.

> 💡 ISP boxes (FPT, Viettel) lock Developer options with a password: the app says plainly that the provider locked it; ask the provider or the seller.

## “The browser needs an update”

The phone app's Manage screen uses Android's own browser (WebView). If it is too old, the app shows this notice.

1. Tap **Open the Play Store** in the notice.
2. Update **Android System WebView** (and Chrome if listed).
3. Reopen the app.

## Send the log to get help

1. Open **Settings (🛠️) → About**.
2. Tap **📋 Send the log**. The log is copied.
3. The app copies the log and opens [the send-the-log page](https://tvremote.pages.dev/log): paste it, add a few words, then send it as a GitHub issue or a Facebook message to the author.

> 💡 The log has the commands sent, the TV's answers and errors. No pairing keys, MAC addresses or typed text.

> 💡 If an Android app crashed, the next launch shows “The app crashed last time” with the error and a few lines. Tap **Copy** or **Share**, then paste it into the send-the-log page.

> 💡 An error in the “Something went wrong” box is also sent anonymously (IPs, paths, keys removed) while **Send anonymous stats** is on.

> 💡 A TV nobody can reach (an ISP box, no ADB): on the TV open **TV Setup → Connection → Send to support** and read the 8-digit code to the author.

> 💡 **Let support look for 30 min** only reads the state, never presses or changes anything; stop it any time.

> 💡 For the TV from a phone or computer: **Setup** tab, TV card, **👁️ Let support see this TV**. For the phone or computer app itself: **About**.

## Common problems

| Problem | Try |
| --- | --- |
| Cannot connect | See [The self-check](#selfcheck). Same Wi-Fi? TV on? |
| Buttons do nothing | [Turn ADB on again](#prep-tv), or restart the TV. |
| The TV's page asks for the code again | Pair again once; avoid private windows. |
| Choppy picture | Lower size and bitrate ([Picture quality](#quality)). |
| Tailscale shows no link | Wait a bit, then tap **🔑** again ([Tailscale](#away)). |
| Play Protect warns | Choose “Install anyway”. The source is public. |
| Fire TV: Home / Alexa buttons dead, other keys work | First setup never finished: the app says so at the top, press **Fix** (sets `user_setup_complete` to 1). |
| TV Settings freezes on the text-to-speech page | Run `pm clear com.google.android.tts` (the Terminal, or `adb shell`); the voice downloads again on first use. |
| The TV's page shows no pairing code | A TV with no ADB cannot show it by itself: open TV Setup on the TV, the code is on its main screen (2 minutes). See [Open the TV's web page](#web-iphone). |

# Turn on ADB by hand (if needed)

Optional, and usually not needed: TV Setup opens the switch itself. Without ADB the phone and computer apps still send keys. ADB adds the TV picture, apps, files and timers.

1. **Automatic (easiest):** [install TV Setup](install-tv.md) and open it. It opens Developer options directly, even when the TV hides Settings. Turn on **ADB debugging**, come back, choose **Connection → Reconnect**.
2. **No Developer options?** Page says “not available”? By hand: **Settings → Device Preferences (Google TV: System) → About**, press OK 7 times on **Build**, then turn on **ADB debugging**.
3. **Chromecast with Google TV / Google TV Streamer:** **System → About**, press OK 7 times on **Android TV OS build**; then **System → Developer options → USB debugging**.
4. **Internet-provider boxes (FPT, Viettel…):** don’t press 7 times, the box may ask for a password or lock up. Install TV Setup first: it opens Developer options directly.
5. **TCL (China) with no Developer options?** Go to **Settings → About**, press **Up, Down, Left, Right** round after round until “ADB: address:port” shows. Repeat it each time.
6. **Build asks for a password** (FPT Play boxes…)? Install TV Setup, it opens the switch. Still stuck: **🧭 Turn ADB on for me**, or skip it, [keys still work](nokeys.md).

> 📡 A TV with **Wireless debugging** only (Android 11+)? No ADB debugging switch needed: open the TV's web page, press **📡 Pair with Wireless debugging**, follow [the steps](wireless.md).

> 🛡 **NVIDIA SHIELD:** in Developer options also turn on **Network debugging**, then choose **Reconnect**.

> 🔥 **Fire TV:** ADB is on but TV Setup still cannot connect? Amazon blocks an app on the TV from using its own ADB; use the phone or computer app.

> 🔐 TV Setup says “The TV is asking Allow USB debugging?”: ADB is already on, just tick **Always allow** on the TV, then choose OK.

> 📡 Also worth turning on: **Network & Internet → Remote start**, so the TV can be woken from afar.

> 💡 The TV’s address: TV Setup shows it. By hand: **Settings → Network & Internet** → the Wi-Fi name, like `192.168.1.20`. Menu names vary a little by brand.

> 💡 Xiaomi TV (China version): **Settings → Account & Security → ADB调试**. The app's **Steps to turn on ADB** button gives steps by brand.

> 💡 In **🧭 Turn ADB on for me**, the **Open Settings through another home screen** list has **TV Home** first. “Not available” on a provider box: the provider locked it, ask them.

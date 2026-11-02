# Install the app on the TV (TV Setup)

A small app on the TV: it runs the TV's web page, the sleep timer, app limits and more.

> 📱 **Have the phone or computer app? No Downloader needed.** Pick the TV (ADB on), open **Manage → ⏰ Automation**, press **⚡ Install TV Setup on the TV**. One button, and Play Protect does not block it.

### Only the TV: install with Downloader

1. On the TV, open **Google Play**, find **Downloader** (AFTVnews, orange icon) and install it.
2. Open Downloader (allow file access if it asks), type **2397346** and choose **Go**.
3. Choose **Install**. If the TV blocks unknown sources, choose Settings, allow Downloader, go back, install.
4. Open **TV Setup**. If ADB is off, it opens the switch for you: turn on **ADB debugging**, come back, choose **Connection → Reconnect**.
5. Once ADB works, TV Setup installs **TV Home**, **TV Channels**, **TV Games** and **TV Apps** itself, then asks whether to keep TV Home as the home screen (press nothing and the TV goes back).
6. The TV shows a QR code and an address. Scan it (or see “[Open the TV's web page](web-iphone.md)”).

Direct links: [tv-setup.apk](https://github.com/HoangTran0410/tv-remote/releases/latest/download/tv-setup.apk) · [tv-remote.apk](https://github.com/HoangTran0410/tv-remote/releases/latest/download/tv-remote.apk).

> ⚠️ Play Protect may warn because the app can install APKs. The source is public; choose “Install anyway” if you trust it, or install from the phone or computer app as above.

> 💡 Fixing a dead remote key and “hold 2 seconds to change input” live in a small app, **TV Setup Keys**, packed in TV Setup and installed over ADB when needed.

### Tips

- A TV with **Wireless debugging** only (no port 5555)? Open the TV's web page and press **📡 Pair with Wireless debugging**: see “[Wireless debugging](wireless.md)”.
- TV Setup's main screen shows the pairing code (6 digits); **Connection** has **Forget all ADB keys**.
- TV Setup on a phone by mistake? It offers the phone app, or **Use it here anyway**.
- The row of four tiles on the main screen, **TV Home**, **TV Channels**, **TV Games** and **TV Apps**, shows **the version** (e.g. v0.9.0), **Not installed** or **Update**: press to open, install or update, no ADB needed.

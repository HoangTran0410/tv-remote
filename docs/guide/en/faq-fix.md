# Common problems

| Problem | Try |
| --- | --- |
| Cannot connect | See [The self-check](selfcheck.md). Same Wi-Fi? TV on? |
| The TV's ADB is not on port 5555 | Type the IP with the port: `192.168.1.5:5877` (on a phone's number pad, a comma instead of the colon). The app keeps the port for that TV. Port 5877 (BeeCube) is tried by itself. |
| Buttons do nothing | [Turn ADB on again](prep-tv.md), or restart the TV. |
| The TV's page asks for the code again | Pair again once; avoid private windows. |
| Choppy picture | Lower size and bitrate ([Picture quality](quality.md)). |
| Tailscale shows no link | Wait a bit, then tap **🔑** again ([Tailscale](away.md)). |
| Play Protect warns | Choose “Install anyway”. The source is public. |
| An.apks /.xapk /.apkm says a part is missing or locked | Use the complete file, without a password; the app sends only the parts that fit the TV. |
| Fire TV: TV Setup cannot reach ADB though it is on | Amazon blocks an app on the TV from using its own ADB; use the phone or computer app. |
| Fire TV: Home / Alexa buttons dead, other keys work | First setup never finished: the app says so at the top, press **Fix** (sets `user_setup_complete` to 1). |
| TV Settings freezes on the text-to-speech page | Run `pm clear com.google.android.tts` (the Terminal, or `adb shell`); the voice downloads again on first use. |
| Every app says “App isn't available… managed by Google TV” | Google TV blocked them. The **Apps** (or **Health**) tab shows **🔒 Blocked apps**: press **Unblock** (resets the Google TV home screen). Blocked again at the same hour? Turn off the time limit in the profile. |
| The TV's page shows no pairing code | A TV with no ADB cannot show it by itself: open TV Setup on the TV, the code is on its main screen (2 minutes). See [Open the TV's web page](web-iphone.md). |

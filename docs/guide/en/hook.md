# Home Assistant · Shortcuts (Siri)

Let iPhone Shortcuts, Siri or Home Assistant control the TV with one link.

1. The **computer app** or the **TV web page** (see “[iPhone](web-iphone.md)”) → **🛠️ Settings** at the top. Not in the phone app.
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
| `notify=Someone%20at%20the%20door&title=Doorbell` | Shows a message card on the TV screen (title= and seconds= 1 to 60 are optional). Needs TV Setup. |
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

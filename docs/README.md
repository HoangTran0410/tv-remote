# docs

The product page https://tvremote.pages.dev. Plain files, nothing is generated or built.

**Deploying.** Cloudflare Pages (project `tvremote`) deploys it on every push to main that touches `docs/`. Its build watch
path is `docs/*` (Cloudflare's `*` spans folders; `docs/**` matches nothing and skipped every build). A deployment stuck at
"queued" with `is_skipped` was filtered out, not waiting. Everything here is public, this file included.

**Files**

| | |
|---|---|
| `index.html` | the home page, kept short on purpose: a before / after pair (`img/tv-before` = `dev/shots/home-stock.html`, a made-up stock Google TV home with ads, since the real one needs a Google sign-in; after = `tvhome-widgets-<lang>`), five showcases (a picture, a title, a line, a "Dùng thử" button to `/demo/#remote|home|channels|games`), a 4-step setup with the downloads and links to the guide's install topics. Only a fade-in on scroll. With its JSON-LD for search engines |
| `css/home.css`, `js/home.js` | the home page's own look and script (language, the download for this device, the release number); tokens, nav, buttons and footer come from `css/site.css` |
| `tour.html` | the long animated tour (the old home page: demo, why, features, how it works, comparison, install stories, FAQ), `noindex`, no longer linked (the home page and the guide are enough); kept because the guide's install figures read their stories from it |
| `css/site.css`, `js/site.js` | the tour's look and behaviour (`site.css` also styles the home page) |
| `guide.html`, `css/guide.css`, `js/guide.js` | the user guide (served at `/guide`): written once as Vietnamese + English spans, a menu, offline accent-insensitive search built from the page |
| `dev/release/guide_stale.py` | which guide topics may be out of date: each topic's `data-v` (the release it was checked against, `next` = main, stamped by `release.sh`) and `data-src` (the app files it depends on); `release.sh` stops while any is listed |
| `data/since.json` | the line under each guide topic's title, "Since 0.4.0 (date) · TV Setup ≥ 0.5.0": per topic id `since` (the release; `next` while unreleased, stamped with its date by `dev/release/stamp_since.py` from `release.sh`) and optional `setup` / `phone` / `pc` minimums (TV Setup: `SetupNeed.MIN`); `dates` maps a release to its day (CHANGELOG headings). A new topic needs an entry; `dev/release/guide_stale.py` warns about one without |
| `data/downloader.json` (+ `.sig`) | the Downloader (AFTVnews) short codes of the APKs, listed once and signed (`dev/sign/sign_downloader.py`, the launcher list's key): the apps read it from the site (`core/Downloader.kt`; else GitHub raw, else a packed copy) and use it only when the signature checks. The codes written as text in these pages and the READMEs are copies of it: change them together |
| `js/guide-anim.js` | the guide's four animations (pairing, Allow USB debugging, game controller, Home Assistant), `.ga-*` markup in `guide.html`; the install topics' `.gst` figures take the home page's install stories (`data-from` = the panel's id in `index.html`) and play them over the topic's own steps, a step per part |
| `js/qr.js` | a copy of `app/shared/web/js/qr.js`, kept the same by `app/shared/tests/page.test.js` |
| `js/anim.js`, `lib/gsap.min.js` | the animation runtime and helpers; GSAP 3.13.0 as published |
| `js/demo.js`, `css/demo.css` | the tour's hero demo |
| `js/stories.js`, `css/stories.css` | the tour's How it works and install tabs |
| `demo/` (`index.html`, `sw.js`, `mock.js`) | the live demo at `/demo/`, see below |
| `llms.txt` | the app in plain words for AI agents: keep it true when features or download links change |
| `robots.txt` | for search engines (the sitemap is `_worker.js`'s) |
| `_worker.js` | Cloudflare Pages worker. `/dl/<file>`: the release's file on GitHub. **One address per language:** `/<page>` is served in Vietnamese only, `/en/<page>` in English only (lang, title and description from `data-en` on `<title>` / `<meta name=description>`, canonical, hreflang, the other language's spans removed); old `?lang=` links 301 there; a person's VI / EN pick (`langPick`, nav.js) or a non-Vietnamese browser on a Vietnamese page is sent to the other address, never a crawler. `/guide?t=<topic>`: that topic's name and first lines as title and description, its own canonical. `/sitemap.xml`: the pages and every guide topic, both languages. Not served as a file. Test locally with `npx wrangler pages dev docs` (a plain file server shows both languages in place, as before) |
| `_headers` | Cloudflare Pages response headers: images 1 week, GSAP 30 days (css/js keep the default: revalidated, as their names have no version) |
| `img/` | screenshots (`dev/shots/shots.py`) and the link previews `og-<lang>.jpg` (1200×630, TV Home from `tvhome-widgets-<lang>.webp`: `dev/shots/shots.py og`) |

**Animations** are GSAP timelines written by hand. A timeline reads as a list of "at second t, this changes": `show`,
`press`, `fill`, `type`, `toggle`, `focus`, `pointer`, `steps`, or `track` for anything else. To look at one moment, in the
browser console: `storySeek(document.querySelector(".st-b"), 6.05)`.

**Screenshots** (`img/`: the computer app, the Tailscale box, the TV web page, the phone app, TV Setup, the og previews):

```
dev/shots/shots.py                      # all of them
dev/shots/shots.py web ts tv phone tvapp   # or some
```

It needs the computer app built and, for `tv`, `phone` and `tvapp`, both emulators running (`dev/test/emu.sh`). The TV's picture
is TV Home itself, `img/tvhome-widgets-<lang>.webp` (taken first from the TV emulator: widgets, live widgets, our own apps); the TV is named "TV phòng khách" / "Living room TV". Then check each
`<img>`'s width and height in `index.html`.
New pictures under old names: raise the `?v=` every page puts after `img/…` (one replace across `docs/`), or a browser that saw
the old one keeps it up to a day (`_headers`; it was a week, and the site looked like the last build's for days).

**Live demo** (`/demo/`, a picker of four app cards, no tabs; deep links `#remote` `#home` `#channels` `#games`, `?go=<guide topic>` opens Remote): `index.html` is
the picker (no hash: a card per app, its real icon `img/icon-*.png`, a line and a real screenshot `img/shot-*.jpg`) and, inside a demo, a slim bar ("All apps", the four icons as a switcher, VI / EN, Reset, the download); Back returns to the picker; each app is a page of this folder in a
lazy frame (`<tab>.html?embed=1&lang=`; the frame reports its height, and OK on a tile of TV Home can ask for another tab). Keys:
`tvkit.js` turns the on-screen remote, the keyboard and the hub's forwarded keys into up / down / left / right / ok / back / home / menu
(a long press of OK is menu); `tvkit.css` is the TV frame (a fixed 1280 x 720 picture scaled to the frame) and the remote.
- **Remote** (`remote.html`): the app's real web page (`app/shared/web`, nothing copied here) against a made-up TV. It registers `sw.js` (scope
  `/demo/`) and shows the page in a frame at `/demo/app/`. The worker serves its files from jsDelivr at the git tag in `REF` (top of `sw.js`)
  and asks `mock.js`, in `remote.html` (the worker finds that page by its path), to answer every `/api/...` call; the made-up TV lives in
  that page's memory, so Reset (or a reload) starts again. Install, remove, reboot and the like answer "this is a demo". The picture is a
  drawn screen as screenshots (video is not faked). The page's synchronous reads (words, `remote.json`) are put into `index.html` of the
  app by the worker, since Chrome does not send those to a worker.
- **TV Home** (`home.html`): drawn by hand after `app/tvhome` (clock, dock, grid, long-press menu, wallpapers from `home/*.jpg`) and a made-up
  "before" screen (an illustration). OK on the TV Channels / TV Games tile opens that tab. Own apps use the real `banner.png` (`img/banner-*.png`,
  copies: refresh with the apps' banners).
- **TV Channels** (`channels.html` + `channels-data.js`): 38 channels of iptv-org (public streams) in three sets (International, Vietnam, USA):
  `[country, name, category, logo, stream, plays, light]`. A stream is played with hls.js (1.5.17 from cdnjs, loaded on the first play, muted
  until the Unmute button); `plays` is what a real Chrome measured (a stream needs CORS to play in a page: 26 of the 38 did, the VTV / HTV
  ones do not and show a still with "on the TV this plays the channel"). `light` is the app's logo-tile rule (`LogoFit.kt`) worked out
  offline: re-measure both when the list changes. Leaving the tab stops the video.
- **Look of the TV pages:** taken from real 1920 x 1080 captures of the TV emulator (`img/shot-*.jpg`, `img/tv-home-screen.jpg` = the Remote demo's TV picture, clean TV Home). `home-walls.js` is a verbatim copy of `app/shared/web/js/home_walls.js` (the drawn walls): keep it in sync. Games: the pads sit beside the TV from 900 px, under it on a phone.
- **TV Games** (`games.html` + `pad/`): the library with the catalog's real pictures (`games/img`), and the real quiz (`games/web/quiz`,
  put in a frame with a `window.tvpad` stand-in) played with one or two phones. The phone is the app's real pad page: `pad/pad.js`, `pad.css`,
  `pingmath.js` are unchanged copies of `app/tvgames/src/main/assets/pad/` (and `webgames/_lib/`), `pad/index.html` is its page without the
  manifest; `pad/demo-pad.js` swaps the WebSocket for postMessage to `games.html` (which plays the TV's side of `GameServer.kt`'s protocol),
  turns a mouse press into touches and fixes the language. Refresh the copies after a change to the pad (`cp`, then check `demo-pad.js`
  still matches `connect()`). No NES player here: an NES game shows a card saying so.

To show a newer UI: pick a release tag (a pushed commit also works), check
`https://cdn.jsdelivr.net/gh/HoangTran0410/tv-remote@<ref>/app/shared/web/index.html` answers, change `REF` in `demo/sw.js`,
and add to `mock.js` what the new UI asks for (run the page and watch the console: "demo: no mock for ..."). Old caches go
when the new worker starts. A newer `REF` than the tag the UI was checked against can ask for routes `mock.js` lacks.

**Trying it locally:** `dev/test/serve_docs.py` (like Cloudflare Pages: `/guide` is `guide.html`), then http://localhost:8000 (the demo needs `localhost` or https
for its service worker; `127.0.0.1` also works outside restricted sandboxes).

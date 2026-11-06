# The web UI's design system

The rules for every change to `app/shared/web`: the page the Computer app shows, the TV web page (TV Setup on 8765), the
Phone app's Manage screen, and TV Setup's own Manage screen on the TV (`?ontv=1`, `html[data-ontv]`, D-pad focus in
`js/ontv.js`). Everything here is taken from the code as it is; where code and this file disagree, the tests win, then fix
this file. [AGENTS.md](AGENTS.md) has the short version ("The tabs are made of a few shapes"); this is the reference.

The remote tab (`.rc`, the D-pad, keys from `remote.json`) is laid out by hand to be hit without looking: most rules below
are for the other tabs, dialogs and menus.

## 1. Principles

| Rule | What it means in code |
|---|---|
| Users first, automatic | Do the next step yourself (retry, fall back, re-ask once ADB is up). Ask only what only a person can answer. |
| Errors last, in words a person can act on | Show one only after every fallback failed. Server text goes through `friendly()` (`js/core.js:257`, table `FRIENDLY` above it); `api()` already toasts `common.error_msg` (`js/core.js:137`). A missing / old TV Setup is a question with its button (`needSetup()`, `js/core.js:171`), never a toast. |
| Every wait is seen | `api()` puts `.busy` on the button just clicked (spinner after 150 ms, `css/base.css:23-26`). Longer work: `UI.busy(btn, words)` (`js/ui.js:32`), a `data-busy="45%"` label (`css/base.css:27-28`), a `.bar` (indeterminate: `.bar.busy`, `css/kit.css:4`), or a job in `#jobscard` with Stop (`js/files.js:100`, `/api/cancel`). |
| Long waits can be cancelled | Uploads, installs, scans: a Stop that really ends the work on the server, then `T('files.stopped')`. |
| Short words | Labels of 1-3 words; one `p.note` line of why; details in `title` or a dialog. Tables over paragraphs. |
| Choices look like buttons | Chips, `.item.pick` rows, `menuDlg` items. Not a bare `<select>` for long lists (the phone's WebView turns it into a system list). |

## 2. Tokens (`css/base.css:2-13`; `js/theme.js` sets `data-theme`)

Colours: use only these vars. Both themes are checked for 4.5:1 by `tests/contrast.test.js`.

| Token | Light | Dark | Use |
|---|---|---|---|
| `--bg` | `#f3f4f7` | `#111418` | page; fields (`input` background) |
| `--card` | `#fff` | `#1b1f25` | cards, dialogs, menus |
| `--ink` / `--muted` | `#1a1d23` / `#586272` | `#e9ecf1` / `#a3acb8` | text / secondary text |
| `--line` | `#e1e4ea` | `#2b3139` | borders, `.item` separators |
| `--btn` / `--btn-hi` | `#eceff3` / `#dde2e9` | `#272d35` / `#363e49` | plain button / its hover; row hover |
| `--accent` + `--accent-ink` | `#2f6df6` + `#fff` | `#3366e8` + `#fff` | `.primary` fill, focus ring, bars |
| `--accent-text` | `#1f50c4` | `#7ba2ff` | blue words (links, `.on` text) |
| `--sel` | `#e6edfe` | `#202d4c` | on / chosen tint (with an inset `--accent` edge) |
| `--ok` `--warn` `--bad` | `#106537` `#8f5300` `#b02a2a` | `#46d283` `#f0b34a` `#ff7070` | state words |
| `--ok-bg` `--warn-bg` `--bad-bg` | `#e3f5ea` `#fdf0d8` `#fbe5e5` | `#16301f` `#3a2e12` `#3b1c1c` | `.banner.*`, `.badge.ok`, `.chip.bad` |
| `--boot` | `#5b38c8` | `#b29dff` | the launcher/boot accent only |

Sizes and spacing:

| Token / value | Where |
|---|---|
| `--ctl: 40px` | a field and the button beside it; `.actions` buttons; every non-remote panel control (`css/remote.css:160`) |
| `--ctl-sm: 36px` | `.small`, `.iconbtn` (38px wide), `select` |
| `--chip: 32px` | `.places button` chips, pill-shaped (`999px`) |
| `48px` | the bare `button` / text-field base (`css/base.css:29,45`); the panel rule brings it to `--ctl` |
| `--radius: 14px` | cards, banners, tabs bar. Buttons, fields: `12px`; rows in a picker: `10px` |
| `--gap: 12px` | between a card's children (`css/remote.css:165`, `details.card` in `css/kit.css:66-67`) |
| `14px` | between cards (`main`, `.panel`, `.col`, `.stack`) and card padding |
| `6-8px` | inside a row: `.line` 8, `.actions` 8, `.places`/`.fchips`/`.tools`/`.acts` 6 |

Type scale (system-ui; body `16px/1.45`):

| Size | Element |
|---|---|
| 18px | `h1` (top bar) |
| 16px / 600 | `p.state` |
| 14px | `h3` (600), `details.card > summary` (600), `.item .name`, panel buttons, `.kv` |
| 13px | `p.note`, `p.empty`, `.warnline`, `.small`, `select`, chips, `.muted` |
| 16px / 650, sentence case, `--ink` | `h2` card titles (`css/base.css:20`); words beside it `h2 .lower` (13px, `--muted`) |
| 12px | `.item .name small`, `.mitem small` |

Breakpoints: `max-width:640px` is the phone layout (icon-only `.lbl` in heads/acts/top, tabs as icon over 10px label,
`#jobscard` as a bottom sheet, `css/page.css:7,94,105`); `min-width:900px` the wide layout (`main.wide`). A few one-offs exist
(380/420/440/480, 899). Screenshots are taken at **1100 and 400 px** (`dev/test/layout.js:112`).

Focus: `:focus-visible` = `2px solid var(--accent)`, offset 2 (`css/base.css:37`); on the TV every `:focus` gets `3px`
(`css/page.css:27`). Chip rows keep 6px of room for the ring (`css/kit.css:25`). Never remove an outline.

Motion: `slide(n, show)` to show/hide a block (`js/ui.js:147`); `.flash` for a card a jump lands on; all of it is off
under `prefers-reduced-motion`.

## 3. Components: which one, and how

Build with `el(tag, props, ...kids)` (`js/core.js:13`) and `UI.*` (`js/ui.js:19-56`). Static markup in `index.html` uses
the same classes.

| Need | Use | Not |
|---|---|---|
| A block of a tab | `UI.card(title, { tools, id, cls, fold }, ...body)` → `section.card` + `.card-head` | `el('section', { className: 'card' })` (test fails outside ui.js/core.js) |
| Tools at a card's top right | `UI.head(title, ...tools)` / `o.tools`; tools are `.small` or `.small.iconbtn` | big buttons in a head |
| Rarely used, or advanced | `UI.card(title, { fold: true }, ...)` → `details.card > summary` (14px plain words) | a hand-made toggle |
| The advanced part inside a card | `details.adv > summary` (muted 600), open when something in it is on | a second card |
| A part of Settings | each `.group` and `details.sect` is its own `--card` box on the dialog's `--bg` (`.aboutbox` in `css/page.css`), chips wrap; a long part is `details.sect > summary > h3 + span.sum`: chevron, one muted line under the title (`sectSums()` in `js/setup.js` fills it: the chosen chip, else `p.state`, else the first note); short picks stay open above them | lines between parts (they ran together); a card in a part |
| A card that only says one thing | `UI.say(text, cls)` ("reading…", "nothing", "cannot") | an empty card with a spinner only |
| A tab of many cards (Automation, Setup) | a `section.card` with an `id` folds by itself (`js/fold.js`: title + one line, chevron); the renderer calls `foldSum(id, words, on)`, else its `p.state` is the line; `data-nofold` keeps one open; the head's tools show only open, on their own row under the title | a hand-made toggle per card; a tool beside a fold title |
| A part set apart inside a card (a preset at its top: Kids mode's Quick setup) | `div.part` (line edge, 12px radius, gap `--gap`) | a card in a card |
| One setting / one part of a card | `UI.group(h3Title, ...controls)` → `div.group` (gap 6) | margins |
| Why, under a title or control | `UI.note(t)` → `p.note` | `<small>`, inline colour |
| What is on now | `UI.state(t)` → `p.state` (hidden when empty) | bold `p` |
| An empty list | `UI.list(rows, emptyText)` → `.list` or `p.empty` | a lone "No items" div |
| Something to fix | `UI.warn(t)` → `p.warnline`; page-wide: `.banner.warn/.bad/.ok` | red text by hand |
| Rows of things | `UI.item({ icon, name, sub, extra, acts })` in `.list`; acts are `.small` | tables of buttons |
| A whole row is the button | `button.item.pick` (`.on` when chosen; gets the `--sel` rule, `css/kit.css:98`) | `div` with `onclick` |
| A field + its button | `UI.line(input, btn)` / `form.line`, both `--ctl` (no `.small` in a `form.line`: test) | different heights |
| A card's main buttons | `UI.actions(...btns)` → `.actions` (`.actions.end` to right-align) | buttons loose in the card |
| One of a few choices | `UI.chips([{ label, on, fn }])` → `.places`, one line, scrolls sideways with fades (`js/ui.js:119`) | radio buttons, a wrapping row |
| A filter/sort row above a list | `div.fchips` of buttons, current `.on` + `aria-pressed` | |
| A colour to pick | `input[type=color]` in a `.ownstop` (`--ctl` tall, 44 wide, the colour fills it, `--line` edge; `.small.iconbtn` beside it to remove); several in `.ownstops`, which wraps | a hand-made palette; a hex field alone |
| Key/value facts | `.kv` (dt muted, `.v` bold right) | |
| Facts at a glance (Health → Now) | `.facts` > `.fact`: `UI.icon(name)` on a `--btn` tile, muted name over a bold value; 2 columns at every width | `.kv` when each fact needs its icon |
| A share of a whole, live | `.ring` (`ring()` in `js/health.js`): `--accent` arc on a `--btn` track, `.warn` ≥75 %, `.bad` ≥90 %, the number inside, words under | a pie |
| A figure over the last 60 s | `liveChart()`: 2px lines (`path.lc-a` `--accent`, `path.lc-b` `--boot`), one scale, the values in the row above (hover moves them) | a second y-axis |
| Parts of a whole | `.sbar` > `i.p-a / .p-b / .p-c / .p-f` (2px gaps) + a `.sl-leg` legend with the figures | colour alone |
| A small tag | `.badge` (`.badge.ok`) | coloured spans; on a theme's tile (`.homewalls small.badge`): ▶ Video, 🖼 ×N, 🖼 Slideshow (`homeMediaBadges`) |

```js
const c = UI.card(T('x.title'), { tools: [UI.btn('↻', reload, 'small iconbtn')] },
  UI.note(T('x.why')),
  UI.group(T('x.when'), UI.chips(DAYS.map((d) => ({ label: T('x.' + d), on: d === now, fn: () => pick(d) })))),
  UI.list(rows.map((r) => UI.item({ name: r.name, sub: r.pkg, acts: [UI.btn(T('x.remove'), () => rm(r), 'small danger')] })), T('x.none')),
  UI.actions(UI.btn(T('x.save'), save, 'primary')));
```

### Buttons: the two blues

| Class | Look | Meaning |
|---|---|---|
| (none) | `--btn` grey | an ordinary action |
| `.primary` | `--accent` fill, white, 700 | **does something**: OK, Send, Install, Add. Usually one per card/dialog |
| `.on` / `aria-selected=true` | `--sel` tint, `--accent-text`, inset 1.5px `--accent` | **on or chosen**: a tab, the chip in use, a switch that is on. Never the fill (`tests/css.test.js` last test) |
| `.danger` | red words (`--bad`); red **fill** inside `dialog.ask` (`css/page.css:200`) | removes, stops, resets |
| `.small` | `--ctl-sm`, 13px, padding 0 12 | tools in a head, acts in a list |
| `.iconbtn` | `--ctl-sm` x 38px, icon only; pair with `.small`; give `title` + `aria-label` | ✕, ↻, ⋯ |
| `.linkbtn` | an `<a>` drawn as a button | downloads, external pages |
| `label.filebtn` | a button around a hidden file input | uploads |

Icon + words in tight places: `setLabel(btn, icon, words)` (`js/core.js:12`) makes `.ico` + `.lbl`; under 640px the words
hide and a tap first asks what it does (`js/core.js:100-118`; set `data-asks` if the handler asks already).
A greyed control for an LG/Samsung or a guest is not `disabled`: `applyKind()` marks it and a press says why.

### Dialogs, menus, toasts (never `alert` / `confirm` / `prompt`: the Computer app's web view has none)

| Need | Call |
|---|---|
| Yes / no | `if (!await ask(T('x.q'), { ok: T('x.do'), danger: true })) return;` |
| Type a value | `const v = await ask(T('x.name_q'), { value: old });` (null = cancelled) |
| Pick or type | `ask(text, { value, choices: [{ value, name, tag, sub }] })` → `.askpick` rows |
| Hours + minutes | `askTime(text, minutes, max)` |
| A menu of actions | `menuDlg(title, primaryIdx, [['@icon|Words', 'what it does', fn], ...], currentIdx)` → `.mitem` rows, Back to the menu it came from |
| ⋯ on a row | `popover(anchorBtn, [[icon, words, fn, cls], ...])` → `.pop` |
| One-line result | `toast(T('x.done'))` (stays 2.5-10 s by length) |

`ask()` queues: one question at a time, and the button that asked keeps its spinner. On the TV, Back closes the top
dialog and focus stays inside an open dialog (`js/ontv.js:8,121`).

**A picker with search** (a list too long for chips): copy `chooseApp` (`js/apps.js:60`, tiles) or `chmCountry`
(`js/chan_manage.js:263`, rows). The shape:

```js
function pickThing(title, things, now) {
  return new Promise((done) => {
    let picked = null;
    const q = el('input', { type: 'search', placeholder: T('x.find'), spellcheck: false, autocomplete: 'off' });
    const none = el('p', { className: 'empty', textContent: T('x.none'), hidden: true });
    const rows = things.map((t) => el('button', { type: 'button', className: 'item pick' + (t.id === now ? ' on' : ''),
      onclick: () => { picked = t.id; d.close(); } }, el('div', { className: 'name' }, el('span', { textContent: t.name }))));
    const filter = () => { const s = fold(q.value.trim()); let n = 0;      // fold: no accents needed (chooseApp's)
      rows.forEach((r, i) => { r.hidden = !!s && !fold(things[i].name).includes(s); if (!r.hidden) n++; }); none.hidden = n > 0; };
    q.addEventListener('input', filter);
    q.addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); rows.find((r) => !r.hidden)?.click(); } });
    const d = el('dialog', { className: 'ask thingpick' }, el('div', { className: 'card' },
      UI.head(title, el('button', { type: 'button', className: 'small iconbtn', textContent: '✕', title: T('common.close'),
        'aria-label': T('common.close'), onclick: () => d.close() })),
      q, el('div', { className: 'list thingrows' }, ...rows), none));
    d.addEventListener('close', () => { d.remove(); done(picked); });
    document.body.append(d); d.showModal();
    if (matchMedia('(pointer: fine)').matches) q.focus(); else (rows.find((r) => r.classList.contains('on')) || q).focus();
  });
}
```

Keep: `dialog.ask` > `.card` (gets the flex column + `--gap`), head with ✕, search first, Enter takes the first match,
`p.empty` when nothing matches, the current one `.on` and scrolled into view, focus the search only with a mouse (a phone's
keyboard would cover the list), resolve `null` on close. The scroll box needs `max-height:min(56vh, 520px); overflow-y:auto`
(as `.choosegrid` / `.chmccs`); its row look is section 9's first drift item.

### Tiles

`.tiles` grid (`minmax(104px,1fr)`) of `button.tile` (52px `.appicon`, name clamped to 2 lines): apps only (`css/apps.css:6-12`).
App icons/names come from `appIcon(p)` / `appName(p)` / `onMeta(p, fn)`; in a form, `appChoice(btn, pkg)` (`js/apps.js:90`).

## 4. Icons

Write the emoji first in a title or button label (`'🔍 ' + words`, or in the i18n words). `ICON` (`js/ui.js:62`) turns a
leading emoji into a line icon from the SVG sprite: in `h2`, `h3`, `summary`, `.tabs .ico` (`ICON_TITLES`) it takes its own
hue; in `button`, `label.filebtn`, `a.linkbtn`, `#toast` and the state marks (`ICON_MARKS`: ADB chip, `.fleetstep`, `.ltag`,
`li.ok/.bad`, setup steps) the element's colour. An icon-only button gets `.solo`.
- **Plain marks are icons too** (✓ ✕ ✗ ⏻ ⚠ ★ ☆ ▶ ⏸ ⏹ ↻ ↩): a font draws them at its own size, an old TV not at all. Write them
  first, like an emoji; never mid-sentence: name the button in words ("press Quit", "tap the star"). A favourite's star is
  filled by `.on > .ui-ic.i-star`.
- New emoji in a title → add `'🆕': ['name', hue]` to `ICON` and the path to `dev/gen/gen_icons.py` (`tests/ui.test.js`
  fails otherwise). Hues per tab: `TAB_HUE` (`js/ui.js:76`).
- Content keeps emoji: the remote's keys, app tiles, scene icons, flags, running text (`ICON_SKIP`, `js/ui.js:80`).
- Icon in JS without an emoji: `UI.icon(name, hue)`; svg as `<svg class="ic"><use href="#i-name"/></svg>`.

## 5. Words

- Every word lives in `i18n/<lang>.json` under an id `area.words`: **`vi.json` first** (source), then `en.json` (complete,
  the fallback), then the same ids in the others. A new language = a file + a line in `LANGS` (`js/i18n.js`).
- Scripts: `T('id')`, `T('id', { n, name })` with `{n}` in the words; plurals as `{ "one": "...{n} scene", "other": "...{n} scenes" }`
  picked by `n` (e.g. `auto.imported`, `i18n/en.json:496`). A script writes **no literal words** (`tests/i18n.test.js:104`).
- `index.html`: the element keeps its Vietnamese and names the id: `data-i18n`, `data-i18n-html`, `data-i18n-title`,
  `-aria-label`, `-placeholder`, `-alt`. Every id must be used (`tests/i18n.test.js:90`).
- Kotlin uses the same files (`core/Words.kt`); server errors arrive already in the page's language; English from a library
  gets a line in `FRIENDLY` (`js/core.js`).
- Labels short; English is the longest: check 400 px in English.
- **Words: short.** A note under a field or in a dialog is one or two short sentences (about 15 words), never a paragraph that
  explains how it works: say what the person must know, leave the rest to the guide. No entry over 25 words
  (`tests/short_words.test.js`; `tests/long_words.json` lists the old ones and only shrinks). The same in TV apps' strings.

## 6. Cores, capabilities, the remote

- `<html data-core="pc|tv|phone">` is stamped by the server; `CORE` in JS (`js/core.js:7`). Core-only markup:
  `data-for="pc tv"` (`css/page.css:22` hides the rest). The phone hides `header.top` (its own title bar).
- `html[data-ontv]`: TV Setup's own screen. No Remote tab, every focusable must be reachable with the D-pad
  (`js/ontv.js` moves to the nearest control: use real `button`/`summary`/`input`, not clickable divs), `scroll-padding-top:90px`.
- Capabilities: `data-needs="adb"` (needs ADB), `"control"` / `"full"` (a guest's scope), or any `caps` name from
  `/api/state`. `applyKind()` (`js/core.js:419`) hides cards (`.kind-off`), greys buttons (`data-kind-off`, a press toasts why),
  disables inputs, dims tabs. A new ADB feature: `data-needs="adb"`.
- The remote's keys and menus come from `remote.json` only ([AGENTS.md](AGENTS.md) "The remote screen is drawn from one file"); a new
  `do` needs a handler in `REMOTE_DO` (`js/remote.js`) and `phoneActions` (`PhoneRemote.kt`).
- The page's state: a file sets only its own globals, forgets per-TV state in its `onTvChange()`, writes fields with
  `put(el, 'textContent', v)` (`js/core.js:297`), never `$('#x').textContent = v` (`tests/page.test.js`).

## 7. Don'ts (most are tested)

- No `style="…"` in `index.html`, no `style:` in `el()` props (`tests/ui.test.js`). Need a look twice → a class in `css/kit.css`.
  (Setting a CSS var or a measured position from JS with `.style.setProperty` is fine.)
- No new `min-height: NNpx`: use `--ctl`, `--ctl-sm`, `--chip` (`tests/ui.test.js` has a short allow-list).
- No new colours, no new hex in CSS, no `var(--x, #fallback)`, no undefined vars: use the tokens above. A new token needs both
  themes and a pair in `tests/contrast.test.js`.
- No `--accent` fill for on/chosen; an `--accent` fill must set `color:var(--accent-ink)` (`tests/contrast.test.js`).
- No new button sizes or a fourth kind of chip row. No `.small` beside a `--ctl` field.
- No card without its title first (`h2`, `.card-head`, or `summary`); no margins on a card's children.
- No `confirm()`/`alert()`/`prompt()`; no `<select>` for a long list; no clickable `div`.
- No literal words in JS; no `t("vi","en")` pairs.
- No rule nobody uses, no property set twice for one selector (`tests/css.test.js`). New CSS file → link it in `index.html`
  before `css/kit.css` (kit stays last).
- No error shown while a fallback is left; no wait without a spinner, progress or Stop.

## 8. Before calling it done

1. `node --test app/shared/tests/` (ui, css, page, i18n, contrast, remote).
2. Run the Computer app on a test port (AGENTS.md "Build and run"), screenshot each new/changed screen in Chrome at
   **1100 and 400 px**, both themes; look at them: nothing cut, overflowing, wrapped, chips on one line.
3. `TVR_PASS=… node dev/test/layout.js` (Chromium + WebKit, en + vi, 1100 + 400).
4. Colour changes: `dev/test/contrast.py [URL]`.
5. Before a release with UI changes: `node dev/test/audit.js`, read `/tmp/tvr-audit/index.html`.
6. The TV web page / Manage on the TV emulator (`dev/test/emu.sh`), with the D-pad if it is in Manage; the Phone app's
   Manage screen. Say what was not tested.

## 9. Known drift (as of this writing; not rules to copy)

- `.item.pick` rows are styled four times with near-identical rules: `#fleetlist` (`css/page.css:130`), `.fleetapps`
  (`css/page.css:143`), `.askpick` (`css/page.css:254`), `.chmccs` (`css/apps.css:73`). A new picker should share one rule
  in `css/kit.css` rather than add a fifth.
- Core kit rules live outside `kit.css`: the card's flex/gap (`css/remote.css:165`) and the one-size panel rule
  (`css/remote.css:160-161`); `--gap` and `h3` sit in `css/base.css:51-52`.
- Three chip-row looks: `.places` (pills, `--chip`), `.fchips` (same + `margin-top:4px`), `.seg` (segmented, `min-height:34px`,
  `.seg.mini` 28px, `css/remote.css:54-69`). Tabs are `42px` (`css/page.css:102`).
- Undefined vars and `var(--x, #hex)` fallbacks are gone and `tests/css.test.js` keeps them out (a hover tint is `color-mix(in srgb, var(--ink) 8%, transparent)`; JS-set names are in its allow-list). Still open: raw hex `color:` on the dark surfaces (`.term`, `.screenbox`, `.qkbadge`, `.wallx`).
- Small controls keep their drawn size and get a 44px hit area from an `::after { inset:-8px }` (`.wallx` -12px, `.chstar`); `.seg.mini` buttons are `--ctl-sm`. On a finger (`pointer:coarse`) every `.small` / `.iconbtn` gets one too (`css/kit.css`, at the end).
- Radii are raw px everywhere but cards: 10px x19, 12px x17, 8px x10, 6/9/5/16/7px; only `--radius` is a token.
- Font sizes off the scale: 12.5px, 13.5px, 15px, 17px in dialogs and the about box.
- `.danger` is red text on the page but a red fill inside `dialog.ask` (`css/page.css:200`).
- `chooseApp` builds its head by hand (`js/apps.js:81`); `chmCountry` uses `UI.head` (prefer the latter).
- `js/ui.js:30` is an orphan doc comment (belongs to `UI.say` on line 37).
- `js/core.js:3` calls the computer core "mac"; its value is `pc`.
- Breakpoints: the system is 640/900, plus one-offs at 380, 420, 440, 480 and 899.

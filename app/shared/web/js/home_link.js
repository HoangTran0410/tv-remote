// 🏠 Where TV Home's settings page talks to: the one seam between the page (home.js) and the TV Home on the TV.
//   host    the app that serves the page (TV Setup, the phone, the computer) asks TV Home over ADB: its /api/home... routes
//   direct  the page is TV Home's own (app/tvhome, "Manage from phone": home.html): TV Home answers /h/... itself, no ADB, no TV Setup
// The page asks the same questions either way; HomeConfig's rules (clean / merge) run on the TV in both. A route a side does not
// have is null here and the page hides what needs it (an update through ADB; the wallpaper gallery, which the hosts download).
const HOME_PATHS = {
  host: { read: '/api/home', drop: '/api/home/photo/drop', pin: '/api/home/pin', open: '/api/youtube/open', link: '/api/home/link', linkOpen: '/api/home/link/open', qr: '/api/home/photo/qr', update: '/api/home/update', walls: '/api/home/walls', wall: '/api/home/wall',
    photo: '/api/home/photo', video: '/api/home/video?name=', check: '/api/home/web/check?u=', share: '/api/home/theme/share', media: '/api/home/media?id=', themeFile: 'api/home/theme?download=1', app: { meta: '/api/app/meta?pkg=', icon: '/api/app/icon?pkg=' }, need: '/api/setup/need?f=' },
  direct: { read: '/h/config', drop: '/h/photo/drop', pin: '/h/pin', open: null, link: '/h/link', linkOpen: null, qr: null, update: null, walls: null, wall: null,
    photo: '/h/photo', video: '/h/video?name=', check: '/h/web/check?u=', share: null, media: '/h/media?id=', themeFile: null, app: { meta: '/h/app/meta?pkg=', icon: '/h/app/icon?pkg=' }, need: null },
};

/**
 * [call](path, data, quiet) is the page's api(); [send](path, blob, onPct) puts a picture or a video (onPct: upload progress, 0..100). A call to a route this side does not have
 * answers null, as a failed call does.
 */
function homeLinkFor(direct, call, send) {
  const p = direct ? HOME_PATHS.direct : HOME_PATHS.host;
  const via = (path, data, quiet) => path ? call(path, data, quiet) : Promise.resolve(null);
  return {
    direct,
    app: p.app,
    canUpdate: !!p.update, canGallery: !!p.walls, canQr: !!p.qr, canShare: !!p.share,
    read: (thumbs, quiet) => call(p.read + (thumbs ? '?thumbs=1' : ''), null, quiet),
    write: (change) => call(p.read, change),
    drop: (id) => call(p.drop, { id }),
    pin: (data) => call(p.pin, data), open: (data) => via(p.open, data),   // a YouTube link onto the grid / played on the TV now (home_pin.js)
    link: (data) => via(p.link, data, true),                          // what the TV opens a pasted link with: {kind: app|browser|none, apps, choice} (null: asked nothing, an older TV Home)
    tryOpen: (data) => via(p.linkOpen, data),                         // a link opened once on the TV (any app or browser; the page's "Try on the TV")
    qr: (data) => via(p.qr, data || {}),
    update: () => via(p.update, {}),                                  // a job (files.js ownAppWait)
    walls: (quiet) => via(p.walls, null, quiet),
    wall: (id) => via(p.wall, { id }),
    photo: (blob) => send(p.photo, blob),
    video: (file, onPct) => send(p.video + encodeURIComponent(file.name), file, onPct),   // a video or GIF to the media library (the TV turns a heavy one light; the answer's video.job tells how far)
    mediaUrl: (id) => p.media + encodeURIComponent(id),              // one of the person's own photos / videos as it is on the TV (a theme file carries them)
    themeFileUrl: (id, name, media) => p.themeFile && p.themeFile + '&id=' + encodeURIComponent(id) + '&name=' + encodeURIComponent(name) + (media ? '&media=1' : ''),   // the host makes the file itself (null on TV Home's own page, which makes it in the page)
    share: (data) => via(p.share, data, true),                          // a theme to the community store (a job on the host: ThemeShare.kt); asked with no data = how far it is
    check: (url) => call(p.check + encodeURIComponent(url), null, true),   // can this page be the background: {ok, why, code, title} (WebCheck.kt)
    // TV Setup needs: only a host can ask; TV Home's own page has no TV Setup to ask, and its cards say what they lack
    setup: async (feature) => direct ? true : !!await call(p.need + feature),
  };
}

if (typeof module !== 'undefined') module.exports = { HOME_PATHS, homeLinkFor };

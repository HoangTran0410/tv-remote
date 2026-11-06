// 🏠 TV Home's own settings page (home.html): the same home.js as the other pages, with the few things core.js gives them here.
// TV Home serves it (app/tvhome ManageServer, "Manage from phone") to a phone on the same Wi-Fi: no ADB, no TV Setup, one TV, so there
// is no TV list, no pairing and no state to refresh. The session is a cookie the TV set when the QR's one-time key was used.
window.HOME_DIRECT = true;
window.APP_PATHS = HOME_PATHS.direct.app;                             // the names and pictures of apps come from TV Home itself (appicon.js)
const CORE = 'tv';
const ADB = { state: 'ready' };                                       // nothing to wait for: TV Home does the work itself
const onTvChange = () => {};
// core.js is not loaded here: only the codes TV Home's own server answers (HomeApi.pin) get words; no ADB on this page
const HW_WORDS = { pin_old: 'errors.pin_old', pin_bad_link: 'errors.pin_bad_link', pin_no_app: 'errors.pin_no_app', pin_failed: 'errors.pin_failed' };
const friendly = (m) => { m = String(m || ''); return HW_WORDS[m] ? T(HW_WORDS[m]) : m; };
const lookOffline = () => {};                                         // the update button is the hosts'

// Every call carries X-Tvh (a page of another site cannot send it without a preflight, which TV Home does not answer).
// 401 = the session ended or never was, no answer = the TV stopped serving: the page says how to get a new one instead of a stream of errors.
async function api(path, data, quiet) {
  try {
    const r = await fetch(path, data ? { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-Tvh': '1' }, body: JSON.stringify(data) } : { headers: { 'X-Tvh': '1' } });
    if (r.status === 401) { homeGone(); return null; }
    if (!r.ok) {
      let e = r.statusText; try { e = (await r.json()).error || e; } catch {}
      if (!quiet) toast(T('common.error_msg', { msg: friendly(e) }));
      return null;
    }
    return await r.json();
  } catch { homeGone(); return null; }                                // no answer: the page's server stopped (idle, or TV Home restarted)
}

function homeGone() {
  document.querySelectorAll('dialog[open]').forEach((d) => d.close());
  put('#hwnote', 'textContent', T('homeweb.gone'));
  put('#hwopen', 'hidden', true);
}

// after the last script (home.js) is in: the settings are what this page is for, so they open at once
// (hash #pin = TV Home's QR "Pin from YouTube": the paste box opens first, the settings stay one button away)
document.addEventListener('DOMContentLoaded', () => {
  $('#hwopen').addEventListener('click', homeOpen); $('#hwpin').addEventListener('click', () => homePin());
  if (location.hash === '#pin') homePin(); else homeOpen();
});

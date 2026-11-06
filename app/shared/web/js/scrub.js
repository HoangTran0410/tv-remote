// What an error report must not carry, taken out before the page sends it (errbox.js). The twin of core/Scrub.kt, step for step:
// tests/scrub_cases.json is the table both are held to (scrub.test.js, ScrubTest.kt). No lookbehind: older iPhones' WebKit lacks it.
const Scrub = (() => {
  const H = '[0-9a-fA-F]{1,4}', OCT = '(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)';
  // WireLog.blank: keys, tokens, typed text (its length), MACs, Wi-Fi passwords
  const SECRET = /("(?:client-key|token)"\s*:\s*")[^"]*(")|([?&]token=)[^&\s"]*|(\/bot)\d+:[A-Za-z0-9_-]+/g;
  const TYPED = /("(?:text|Cmd)"\s*:\s*")([^"]*)(")/g;
  const KEEP = ['Click', 'Press', 'Release'];
  const MAC = /\b[0-9A-Fa-f]{2}(?:[:-][0-9A-Fa-f]{2}){5}\b/g;
  const TVPASS = /("(?:setup_pass|relay_\w+)"\s*:\s*")(?:[^"\\]|\\.)*(")/g;   // a saved TV's web-page pass and SSH relay (tvs.json)
  const WIFI = /WIFI:(?:[^\\\n]|\\[^\n])*?;;|("pass"\s*:\s*")(?:[^"\\]|\\.)*(")/g;
  const KEYQ = /([?&](?:k|key|pass|password|pwd|token)=)[^&\s"')]*/g;
  const BEARER = /(Bearer\s+)[A-Za-z0-9._~+/=-]+/g;
  const EMAIL = /[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}/g;
  const WINHOME = /\b[A-Za-z]:[\\/]+Users[\\/]+[^\\/\s:*?"<>|)']+/g;
  const UNIXHOME = /(^|[^\w.-])\/(?:Users|home)\/[^/\s:)'"]+/g;
  const IPV4 = new RegExp('\\b(?:' + OCT + '\\.){3}' + OCT + '\\b', 'g');
  const IPV6 = new RegExp('(^|[^\\w:])((?:' + H + ':){7}' + H + '|(?:' + H + ':){1,6}:(?:' + H + '(?::' + H + '){0,5})?|::(?:' + H + '(?::' + H + '){0,6})?)(?![\\w:])', 'g');

  const blank = (s) => s
    .replace(SECRET, (m, a, b, c, d) => (c ? c + '…' : d ? d + '…' : a + '…' + b))
    .replace(TYPED, (m, a, t, c) => (KEEP.includes(t) ? m : a + '…(' + t.length + ')' + c))
    .replace(MAC, 'xx:xx:xx:xx:xx:xx')
    .replace(WIFI, (m, a, b) => (a ? a + '…' + b : 'WIFI:…'))
    .replace(TVPASS, '$1…$2');

  const text = (s) => blank(String(s == null ? '' : s))
    .replace(KEYQ, '$1…').replace(BEARER, '$1…').replace(EMAIL, 'email@x')
    .replace(WINHOME, '~').replace(UNIXHOME, '$1~')
    .replace(IPV4, 'x.x.x.x').replace(IPV6, '$1x.x.x.x');
  return { text };
})();

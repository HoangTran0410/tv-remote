/* The header of every page of the site, from one list: <script src="js/nav.js" data-page="guide"></script> where the header goes.
   Plain (not deferred) on purpose: it writes the header while the page is read, so nothing jumps and the page's own script, further
   down, finds the language buttons. It also runs the narrow screens' menu (the same links in a sheet under the header). */
(function(){
  var me = document.currentScript, page = me.dataset.page || "", root = me.dataset.root || "";   // root: "../" on a page one folder down (demo/)
  var I = {
    install: '<path d="M12 3v12m0 0l-5-5m5 5l5-5M4 21h16"/>',
    guide: '<path d="M4 5.5A2.5 2.5 0 0 1 6.5 3H20v16H6.5A2.5 2.5 0 0 0 4 21.5Z"/><path d="M4 21.5V5.5M8 7h8"/>',
    apps: '<rect x="3" y="3" width="7" height="7" rx="1.5"/><rect x="14" y="3" width="7" height="7" rx="1.5"/><rect x="3" y="14" width="7" height="7" rx="1.5"/><path d="M14 17.5h7M17.5 14v7"/>',
    tvs: '<rect x="3" y="4" width="18" height="12" rx="2"/><path d="M8 20h8M12 16v4"/>',
    themes: '<circle cx="8" cy="8" r="1.6"/><rect x="3" y="3" width="18" height="18" rx="3"/><path d="M21 15l-5-5L5 21"/>',
    demo: '<circle cx="12" cy="12" r="9"/><path d="M10 8.5v7l6-3.5Z"/>',
    features: '<path d="M12 3l2.2 5.3L20 9l-4.4 3.8L17 18.5 12 15.6 7 18.5l1.4-5.7L4 9l5.8-.7Z"/>',
    log: '<path d="M14 3H6.5A1.5 1.5 0 0 0 5 4.5v15A1.5 1.5 0 0 0 6.5 21h11a1.5 1.5 0 0 0 1.5-1.5V8Z"/><path d="M14 3v5h5M9 13h6M9 17h4"/>',
    ext: '<path d="M7 17L17 7M9 7h8v8"/>',
    down: '<path d="M6 9l6 6 6-6"/>'
  };
  // the same links on every page, in this order. Two kinds, told apart at a glance: the home page's parts ("#…", a scroll there,
  // or the home page from another page) and the other pages, which open in a new tab with ↗: one tapped by mistake from the
  // home page threw the reader off it, Back was the only way to go on reading (the user, 2026-10-25)
  // `more`: under "More ▾" on wide screens, so the bar keeps three links as pages are added (the user, 2026-10-25); the narrow
  // screens' sheet lists them all
  var LINKS = [
    { id: "features", href: "#remote", vi: "Tính năng", en: "Features" },
    { id: "tvs", href: "#tvs", vi: "TV đã thử", en: "Tested TVs" },
    { id: "guide", href: "guide", vi: "Hướng dẫn", en: "Guide", page: true },
    { id: "themes", href: "themes", vi: "Chủ đề", en: "Themes", page: true, more: true },
    { id: "demo", href: "demo/", vi: "Dùng thử", en: "Live demo", page: true, more: true },
    { id: "apps", href: "apps", vi: "Các app nối nhau", en: "How the apps connect", page: true, more: true },
    { id: "log", href: "log", vi: "Gửi nhật ký", en: "Send a log", page: true, more: true }
  ];
  var own = page === "home" || page === "tour";                       // pages with their own Install part
  var install = own ? "#install" : root + "./#install";
  function icon(id){ return '<svg class="ni" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' + I[id] + '</svg>'; }
  function words(l){ return '<span class="vi">' + l.vi + '</span><span class="en">' + l.en + '</span>'; }
  var links = LINKS, main = LINKS.filter(function(l){ return !l.more; }), more = LINKS.filter(function(l){ return l.more; });
  var moreOn = more.some(function(l){ return l.id === page; });
  function href(l){ return l.page ? root + l.href : page === "home" ? l.href : root + "./" + l.href; }
  function link(l, i, all){
    var away = l.page && l.id !== page;                                // another page: a new tab, the reader keeps this one
    return (i > 0 && l.page && !all[i - 1].page ? '<span class="navsep" aria-hidden="true"></span>' : "") +
      '<a href="' + href(l) + '" data-id="' + l.id + '"' + (l.id === page ? ' class="on" aria-current="page"' : "") +
      (away ? ' target="_blank" rel="noopener"' : "") + '>' + icon(l.id) + words(l) + (away ? '<svg class="nx" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' + I.ext + '</svg>' : "") + '</a>';
  }
  var gh = '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M12 .5a11.5 11.5 0 0 0-3.64 22.41c.58.1.79-.25.79-.56v-2c-3.2.7-3.88-1.37-3.88-1.37-.52-1.33-1.28-1.69-1.28-1.69-1.04-.71.08-.7.08-.7 1.15.08 1.76 1.19 1.76 1.19 1.03 1.76 2.69 1.25 3.35.96.1-.75.4-1.25.73-1.54-2.55-.29-5.24-1.28-5.24-5.68 0-1.25.45-2.28 1.18-3.08-.12-.29-.51-1.46.11-3.04 0 0 .97-.31 3.17 1.18a11 11 0 0 1 5.77 0c2.2-1.49 3.17-1.18 3.17-1.18.62 1.58.23 2.75.11 3.04.74.8 1.18 1.83 1.18 3.08 0 4.41-2.69 5.38-5.25 5.67.41.36.78 1.06.78 2.14v3.17c0 .31.21.67.8.56A11.5 11.5 0 0 0 12 .5Z"/></svg>';
  me.insertAdjacentHTML("beforebegin",
    '<nav><div class="wrap">' +
      '<a class="brand" href="' + (page === "home" ? "#top" : root + "./") + '"><img src="' + root + 'img/icon.png" width="32" height="32" alt="">TV Remote</a>' +
      '<div class="navlinks">' + main.map(link).join("") +
        '<div class="navmore"><button type="button" class="navmorebtn' + (moreOn ? ' on' : '') + '" aria-expanded="false" aria-controls="navmenu">' +
          words({ vi: "Thêm", en: "More" }) + '<svg class="nd" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' + I.down + '</svg></button>' +
          '<div class="navmenu" id="navmenu" hidden>' + more.map(function(l, i, a){ return link(l, i, a); }).join("") + '</div></div>' +
      '</div>' +
      '<div class="navtools">' +
        '<a class="btn btn-primary btn-sm navcta" href="' + install + '">' + icon("install") + words({ vi: "Cài đặt", en: "Install" }) + '</a>' +
        '<div class="lang" role="group" aria-label="Language"><button data-set="vi" aria-pressed="true">VI</button><button data-set="en" aria-pressed="false">EN</button></div>' +
        '<a class="btn btn-ghost btn-sm" href="https://github.com/HoangTran0410/tv-remote" target="_blank" rel="noopener" aria-label="GitHub">' + gh + '<span class="gh-label">GitHub</span></a>' +
        '<button class="menubtn" type="button" aria-expanded="false" aria-controls="navsheet" aria-label="Menu"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path class="m1" d="M4 7h16"/><path class="m2" d="M4 12h16"/><path class="m3" d="M4 17h16"/></svg></button>' +
      '</div>' +
    '</div>' +
    '<div class="navsheet" id="navsheet" hidden><div class="wrap">' +
      '<a href="' + install + '">' + icon("install") + words({ vi: "Cài đặt", en: "Install" }) + '</a>' + links.map(link).join("") +
    '</div></div>' +
    '<div class="navprog" aria-hidden="true"></div></nav>');

  // a page the server wrote in one language (_worker.js, langURL): the other language is another address. Remembered as the
  // person's own pick, which sends them there next time; before the page's own handler, which would only swap the words in place
  document.addEventListener("click", function(e){
    var b = e.target.closest(".lang button"); if (!b || !window.langURL) return;
    e.preventDefault(); e.stopPropagation();
    try { localStorage.setItem("langPick", b.dataset.set); } catch(x){}
    if (b.dataset.set !== document.documentElement.dataset.fixed) location.assign(langURL(b.dataset.set));
  }, true);
  // the menu closes on a tap on a link, outside, or Escape, and when the screen grows past the menu's width
  var btn = document.querySelector("nav .menubtn"), sheet = document.getElementById("navsheet");
  function set(open, back){ sheet.hidden = !open; btn.setAttribute("aria-expanded", open); if (!open && back) btn.focus(); }
  btn.onclick = function(){ set(sheet.hidden); if (!sheet.hidden) sheet.querySelector("a").focus({ preventScroll: true }); };
  sheet.addEventListener("click", function(e){ if (e.target.closest("a")) set(false); });
  document.addEventListener("click", function(e){ if (!sheet.hidden && !e.target.closest("nav")) set(false); });
  document.addEventListener("keydown", function(e){ if (e.key === "Escape" && !sheet.hidden) set(false, true); });
  // how far down the page the reader is: a bar along the header's bottom edge, on every page (it was tour.html's only)
  var prog = document.querySelector("nav .navprog"), pbusy = false;
  function bar(){ pbusy = false; var max = document.documentElement.scrollHeight - innerHeight; prog.style.setProperty("--p", max > 0 ? Math.min(1, Math.max(0, scrollY / max)).toFixed(4) : 0); }
  addEventListener("scroll", function(){ if (!pbusy){ pbusy = true; requestAnimationFrame(bar); } }, { passive: true });
  addEventListener("resize", bar); addEventListener("load", bar);
  // the home page: its part in view is lit, like a page's own link elsewhere
  if (page === "home" && "IntersectionObserver" in window) {
    var parts = { features: ["remote", "manage", "home", "channels", "games", "more"], tvs: ["tvs"] }, seen = {};
    var io = new IntersectionObserver(function(es){
      es.forEach(function(e){ seen[e.target.id] = e.isIntersecting; });
      var lit = Object.keys(parts).filter(function(k){ return parts[k].some(function(id){ return seen[id]; }); })[0];
      document.querySelectorAll("nav a[data-id]").forEach(function(a){ a.classList.toggle("on", a.dataset.id === lit); });
      mbtn.classList.toggle("on", more.some(function(l){ return l.id === lit; }));
    }, { rootMargin: "-45% 0px -50% 0px" });
    addEventListener("DOMContentLoaded", function(){ [].concat(parts.features, parts.tvs).forEach(function(id){ var el = document.getElementById(id); if (el) io.observe(el); }); });
  }
  // "More ▾": a click opens it (and a hover, where there is a mouse); a click elsewhere, a link or Escape closes it
  var mbtn = document.querySelector("nav .navmorebtn"), menu = document.getElementById("navmenu"), mwrap = mbtn.parentNode;
  function mset(open, back){ menu.hidden = !open; mbtn.setAttribute("aria-expanded", open); if (!open && back) mbtn.focus(); }
  mbtn.onclick = function(){ mset(menu.hidden); };
  if (matchMedia("(hover:hover)").matches) {
    mwrap.addEventListener("mouseenter", function(){ mset(true); });
    mwrap.addEventListener("mouseleave", function(){ mset(false); });
  }
  menu.addEventListener("click", function(e){ if (e.target.closest("a")) mset(false); });
  document.addEventListener("click", function(e){ if (!menu.hidden && !e.target.closest(".navmore")) mset(false); });
  document.addEventListener("keydown", function(e){ if (e.key === "Escape" && !menu.hidden) mset(false, true); });
  matchMedia("(min-width:1081px)").addEventListener("change", function(m){ if (m.matches) set(false); });
})();

/* The themes page: every theme of the signed list (home/themes.json, ready-made and the community's), a search, tag chips, a sort,
   and a dialog per theme (#<id> opens it). Words are vi + en spans, so the language switch is CSS only; every word that comes from
   the list is set as text, never as markup. Pictures are the small ones until a card is near, a dialog loads the big one. */
(function(){
  var grid = document.getElementById("grid"), chips = document.getElementById("chips"), qi = document.getElementById("q"), sortEl = document.getElementById("sort");
  var count = document.getElementById("count"), empty = document.getElementById("empty"), dlg = document.getElementById("dlg");
  var calm = window.matchMedia && matchMedia("(prefers-reduced-motion: reduce)").matches;
  var ID = /^[a-z0-9][a-z0-9_-]*$/i;
  var TAGS = { video: ["Video", "Video"], slideshow: ["Trình chiếu", "Slideshow"], photo: ["Ảnh", "Photo"], art: ["Vẽ sẵn", "Drawn"], minimal: ["Tối giản", "Minimal"],
    vietnam: ["Việt Nam", "Vietnam"], kids: ["Trẻ em", "Kids"], night: ["Đêm", "Night"], nature: ["Thiên nhiên", "Nature"], city: ["Thành phố", "City"], community: ["Cộng đồng", "Community"] };
  var WIDGETS = { clock: ["Đồng hồ", "Clock"], weather: ["Thời tiết", "Weather"], lunar: ["Lịch âm", "Lunar calendar"], calendar: ["Lịch tháng", "Month"], air: ["Không khí", "Air quality"],
    system: ["Hệ thống", "System"], usage: ["Dùng nhiều", "Most used"], timer: ["Hẹn giờ", "Timer"], note: ["Ghi chú", "Note"], market: ["Thị trường", "Market"], news: ["Tin tức", "News"] };
  var TILES = { ram: ["thẻ RAM", "RAM tile"], sleep: ["thẻ hẹn giờ ngủ", "sleep-timer tile"], next: ["thẻ Xem tiếp", "Keep-watching tile"] };
  var lang = function(){ return document.documentElement.dataset.lang === "en" ? "en" : "vi"; };

  function el(tag, cls, text){ var e = document.createElement(tag); if (cls) e.className = cls; if (text != null) e.textContent = text; return e; }
  function pair(tag, vi, en, cls){ var e = el(tag, cls); e.appendChild(el("span", "vi", vi)); e.appendChild(el("span", "en", en)); return e; }
  function nm(t){ var n = t.names || {}; return [String(n.vi || n.en || t.id), String(n.en || n.vi || t.id)]; }
  function fr(t){ var f = t["for"] || {}; return [String(f.vi || f.en || ""), String(f.en || f.vi || "")]; }
  // the site serves its own files: a list address on this site is read from here (works on localhost and in a preview)
  function local(u){ try { var x = new URL(u); return x.hostname === "tvremote.pages.dev" ? x.pathname.replace(/^\//, "") + x.search : u; } catch(e){ return u; } }
  function param(t, k){ var w = t.conf && t.conf.wurl; if (!w) return ""; try { return new URL(w).searchParams.get(k) || ""; } catch(e){ return ""; } }

  // kind from the settings, not from a label: a video, a slideshow (its list names the pictures) or a photo is a "web" wall; the rest is drawn (or Bing's daily photo)
  function kind(t){
    var c = t.conf || {};
    if (c.wall === "web" && c.wurl){ if (param(t, "v")) return "video"; if (param(t, "s")) return "slideshow"; if (param(t, "i")) return "photo"; return "photo"; }
    return c.wall === "bing" ? "photo" : "art";
  }

  var all = [], slides = {}, shown = [], tag = "", open = null;

  function prep(t, i, community){
    var k = kind(t), tg = (Array.isArray(t.tags) ? t.tags : []).filter(function(x){ return TAGS[x]; });
    [k, community ? "community" : ""].forEach(function(x){ if (x && tg.indexOf(x) < 0) tg.unshift(x); });
    var n = nm(t), f = fr(t), by = String(t.by || "").slice(0, 24);
    var hay = [n[0], n[1], f[0], f[1], by, t.id].concat(tg.map(function(x){ return TAGS[x][0] + " " + TAGS[x][1]; })).join(" ").toLowerCase();
    var norm = hay + " " + hay.normalize("NFD").replace(/[̀-ͯ]/g, "").replace(/đ/g, "d");
    return { t: t, id: String(t.id), k: k, tags: tg, n: n, f: f, by: by, i: i, community: community, hay: norm, list: param(t, "s") };
  }

  // ---- the cards
  function shot(it){ return "img/themes/" + it.id + ".webp?v=11051554"; }
  function fallbacks(it){ return [shot(it), it.t.thumb ? local(String(it.t.thumb)) : ""].filter(Boolean); }
  function setImg(img, srcs){
    var i = 0; img.onerror = function(){ i++; if (i < srcs.length) img.src = srcs[i]; else { img.onerror = null; img.classList.add("none"); } };
    img.src = srcs[0];
  }
  var lazy = "IntersectionObserver" in window ? new IntersectionObserver(function(es){
    es.forEach(function(e){ if (e.isIntersecting){ lazy.unobserve(e.target); var i = e.target.__it; setImg(e.target, fallbacks(i)); } });
  }, { rootMargin: "300px 0px" }) : null;

  // a line icon, not an emoji: 🖼 / ▶ draw differently on every system's font, some as a box
  var BI = { play: '<path d="M7 4l13 8-13 8z"/>', image: '<rect x="3" y="3" width="18" height="18" rx="2"/><circle cx="9" cy="9" r="2"/><path d="m21 15-5-5L5 21"/>' };
  function badge(cls, vi, en, ic){ var e = pair("span", vi, en, "tp-b " + cls);
    if (ic) e.insertAdjacentHTML("afterbegin", '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' + BI[ic] + '</svg>');
    return e; }
  function card(it){
    var b = el("button", "tp-card"); b.type = "button"; b.setAttribute("aria-haspopup", "dialog"); b.dataset.id = it.id;
    var pic = el("span", "tp-pic"), img = el("img"); img.width = 480; img.height = 270; img.alt = ""; img.decoding = "async"; img.__it = it;
    pic.appendChild(img);
    var bs = el("span", "tp-bs");
    if (it.k === "video") bs.appendChild(badge("b-vid", "Video", "Video", "play"));
    if (it.k === "slideshow") bs.appendChild(badge("b-ss", "×" + (slides[it.list] ? slides[it.list].length : "…"), "×" + (slides[it.list] ? slides[it.list].length : "…"), "image"));
    if (it.community) bs.appendChild(badge("b-cm", "Cộng đồng", "Community"));
    pic.appendChild(bs);
    b.appendChild(pic);
    b.appendChild(pair("h3", it.n[0], it.n[1]));
    var p = it.f[0] ? pair("p", it.f[0], it.f[1]) : pair("p", "bởi " + it.by, "by " + it.by);
    b.appendChild(p);
    if (lazy) lazy.observe(img); else setImg(img, fallbacks(it));
    if (it.k === "slideshow") cycle(b, img, it);
    return b;
  }

  // a slideshow card cycles its small pictures (320x180) every second while hovered or focused; reduced motion keeps the still
  function cycle(b, img, it){
    var timer = null, at = 0, first = "", active = false;
    function tick(){ var s = slides[it.list]; if (!s || !s.length) return; at = (at + 1) % s.length; img.classList.remove("none"); img.src = thumbOf(s[at]); new Image().src = thumbOf(s[(at + 1) % s.length]); }
    function on(){
      if (calm || active) return; active = true; first = img.currentSrc || img.src;
      ensureSlides(it.list).then(function(){ if (active && !timer){ timer = setInterval(tick, 1000); tick(); } });
    }
    function off(){ active = false; clearInterval(timer); timer = null; if (at && first) img.src = first; at = 0; }
    b.addEventListener("pointerenter", on); b.addEventListener("pointerleave", off); b.addEventListener("focus", on); b.addEventListener("blur", off);
  }
  function thumbOf(s){ return "home/" + String(s.file).replace(/\.jpg$/, "-t.jpg"); }
  function ensureSlides(name){
    if (slides[name]) return Promise.resolve(slides[name]);
    if (!/^[a-z0-9_-]+$/i.test(name)) return Promise.resolve([]);
    return fetch("home/" + name + ".json").then(function(r){ return r.ok ? r.json() : null; }).then(function(j){
      var a = j && Array.isArray(j.slides) ? j.slides.filter(function(s){ return s && /^[a-z0-9_-]+\.jpg$/i.test(String(s.file)); }) : [];
      slides[name] = a; return a;
    }).catch(function(){ slides[name] = []; return []; });
  }

  // ---- filter, sort, draw
  function render(){
    var q = qi.value.trim().toLowerCase(), qn = q.normalize("NFD").replace(/[̀-ͯ]/g, "").replace(/đ/g, "d");
    shown = all.filter(function(it){
      if (tag && it.tags.indexOf(tag) < 0) return false;
      return !q || it.hay.indexOf(q) >= 0 || it.hay.indexOf(qn) >= 0;
    });
    if (sortEl.value === "name"){ var L = lang(); shown.sort(function(a, b){ return a.n[L === "en" ? 1 : 0].localeCompare(b.n[L === "en" ? 1 : 0], L); }); }
    else shown.sort(function(a, b){ return a.i - b.i; });
    if (lazy) lazy.disconnect();
    grid.textContent = "";
    shown.forEach(function(it){ grid.appendChild(card(it)); });
    empty.hidden = shown.length > 0;
    count.textContent = ""; count.appendChild(pair("span", shown.length + " / " + all.length + " chủ đề", shown.length + " / " + all.length + " themes"));
  }
  function drawChips(){
    var n = {}; all.forEach(function(it){ it.tags.forEach(function(x){ n[x] = (n[x] || 0) + 1; }); });
    chips.textContent = "";
    var order = ["", "video", "slideshow", "photo", "art", "minimal", "vietnam", "kids", "night", "nature", "city", "community"];
    order.forEach(function(x){
      if (x && !n[x]) return;
      var b = el("button", "tp-chip"); b.type = "button"; b.dataset.tag = x; b.setAttribute("aria-pressed", x === tag);
      b.appendChild(x ? pair("span", TAGS[x][0], TAGS[x][1]) : pair("span", "Tất cả", "All")); if (x) b.appendChild(el("i", "", n[x]));
      b.onclick = function(){ tag = x; chips.querySelectorAll(".tp-chip").forEach(function(c){ c.setAttribute("aria-pressed", c.dataset.tag === tag); }); render(); };
      chips.appendChild(b);
    });
  }
  qi.addEventListener("input", render); sortEl.addEventListener("change", render);
  document.addEventListener("langchange", function(){ if (sortEl.value === "name") render(); if (open) fill(open); });

  // ---- the dialog
  var stage = document.getElementById("d-stage"), cap = document.getElementById("d-cap"), stopShow = null;
  function bigSrc(it){ return "img/themes/" + it.id + "-big.webp?v=11051554"; }
  function pictureSrcs(it){
    var s = [bigSrc(it)], i = param(it.t, "i"); if (i) s.push(local(i));
    if (it.t.thumb) s.push(local(String(it.t.thumb)));
    return s;
  }
  function stageImg(srcs){
    var img = el("img"); img.alt = ""; var i = 0;
    img.onerror = function(){ i++; if (i < srcs.length) img.src = srcs[i]; else img.onerror = null; };
    img.src = srcs[0]; return img;
  }
  function fill(it){
    var L = lang();
    document.getElementById("d-name").textContent = it.n[L === "en" ? 1 : 0];
    var f = document.getElementById("d-for"); f.textContent = ""; if (it.f[0]) f.appendChild(pair("span", it.f[0], it.f[1])); else f.appendChild(pair("span", "bởi " + it.by, "by " + it.by));
    var tg = document.getElementById("d-tags"); tg.textContent = "";
    it.tags.forEach(function(x){ tg.appendChild(pair("span", TAGS[x][0], TAGS[x][1], "tp-tag")); });
    facts(it);
  }
  function facts(it){
    var dl = document.getElementById("d-facts"); dl.textContent = "";
    function row(vi, en, node){ dl.appendChild(pair("dt", vi, en)); var dd = el("dd"); dd.appendChild(node); dl.appendChild(dd); }
    var ws = (it.t.conf && it.t.conf.widgets) || [];
    var wn = ws.map(function(w){ return WIDGETS[w] || [w, w]; });
    var tl = (it.t.tiles || []).map(function(x){ var k = String(x).split("/").pop(); return TILES[k] || [k, k]; });
    var vi = wn.concat(tl).map(function(x){ return x[0]; }).join(", ") || "không có thẻ", en = wn.concat(tl).map(function(x){ return x[1]; }).join(", ") || "no cards";
    row("Thẻ", "Cards", pair("span", vi, en));
    if (it.t.src && it.k !== "slideshow") row("Nguồn", "Credits", el("span", "", String(it.t.src).slice(0, 300)));
    if (it.by) row("Tác giả", "By", el("span", "", it.by));
  }
  function show(it){
    open = it; stage.textContent = ""; cap.hidden = true; if (stopShow) { stopShow(); stopShow = null; }
    if (it.k === "video"){
      var v = el("video"); v.muted = true; v.loop = true; v.autoplay = true; v.playsInline = true; v.setAttribute("muted", "");
      var p = param(it.t, "p"), src = param(it.t, "v");
      if (/^https:\/\//.test(p)) v.poster = p; else v.poster = shot(it);
      if (/^https:\/\//.test(src)) v.src = src;
      v.addEventListener("error", function(){ if (!stage.querySelector("img")) stage.appendChild(stageImg(pictureSrcs(it))); });
      stage.appendChild(v); var pr = v.play && v.play(); if (pr && pr.catch) pr.catch(function(){});
      stopShow = function(){ v.pause(); v.removeAttribute("src"); v.load(); };
    } else if (it.k === "slideshow"){
      var img = el("img"); img.alt = ""; stage.appendChild(img);
      ensureSlides(it.list).then(function(s){
        if (open !== it) return;
        if (!s.length){ stage.textContent = ""; stage.appendChild(stageImg(pictureSrcs(it))); return; }
        var at = 0, timer = null;
        var credit = el("span", "tp-credit");
        function go(n){
          at = (n + s.length) % s.length; var sl = s[at];
          img.src = "home/" + sl.file; var nx = new Image(); nx.src = "home/" + s[(at + 1) % s.length].file;
          cap.hidden = false; cap.textContent = "";
          cap.appendChild(pair("b", String(sl.vi || sl.en || ""), String(sl.en || sl.vi || "")));
          if (sl.credit) cap.appendChild(el("small", "", " · " + String(sl.credit).slice(0, 120)));
          cap.appendChild(el("small", "tp-n", "  " + (at + 1) + "/" + s.length));
        }
        function auto(){ if (calm || timer) return; timer = setInterval(function(){ go(at + 1); }, 3500); }
        function halt(){ if (timer) clearInterval(timer); timer = null; }
        go(0); auto();
        var prev = el("button", "tp-nav tp-prev"), next = el("button", "tp-nav tp-next");
        prev.type = next.type = "button"; prev.setAttribute("aria-label", "Previous"); next.setAttribute("aria-label", "Next");
        prev.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M15 5l-7 7 7 7"/></svg>';
        next.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M9 5l7 7-7 7"/></svg>';
        prev.onclick = function(){ halt(); go(at - 1); }; next.onclick = function(){ halt(); go(at + 1); };
        stage.appendChild(prev); stage.appendChild(next);
        stopShow = halt;
      });
    } else stage.appendChild(stageImg(pictureSrcs(it)));
    fill(it);
  }
  function openId(id, push){
    var it = all.filter(function(x){ return x.id === id; })[0]; if (!it) return;
    show(it); if (!dlg.open) dlg.showModal();
    if (push !== false) try { history.replaceState(null, "", "#" + it.id); } catch(e){}
  }
  grid.addEventListener("click", function(e){ var c = e.target.closest(".tp-card"); if (c) openId(c.dataset.id); });
  function close(){ if (dlg.open) dlg.close(); }
  document.getElementById("d-x").onclick = close;
  dlg.addEventListener("click", function(e){ if (e.target === dlg) close(); });
  dlg.addEventListener("close", function(){
    if (stopShow) { stopShow(); stopShow = null; }
    stage.textContent = ""; var id = open && open.id; open = null;
    try { if (location.hash) history.replaceState(null, "", location.pathname + location.search); } catch(e){}
    var c = id && grid.querySelector('[data-id="' + id + '"]'); if (c) c.focus({ preventScroll: true });
  });
  window.addEventListener("hashchange", function(){ var id = location.hash.slice(1); if (id) openId(id, false); else close(); });

  // download: the file the Manage screen's Export makes and its Import reads ({tvtheme:1, name, conf}); backgrounds are links to this site
  document.getElementById("d-dl").onclick = function(){
    if (!open) return;
    var name = open.n[lang() === "en" ? 1 : 0].replace(/\s+/g, " ").trim().slice(0, 24);
    var blob = new Blob([JSON.stringify({ tvtheme: 1, name: name, conf: open.t.conf }, null, 2) + "\n"], { type: "application/json" });
    var a = el("a"); a.href = URL.createObjectURL(blob); a.download = open.id + ".tvtheme.json";
    document.body.appendChild(a); a.click(); a.remove(); setTimeout(function(){ URL.revokeObjectURL(a.href); }, 4000);
  };

  // ---- the list
  fetch("home/themes.json").then(function(r){ return r.ok ? r.json() : null; }).then(function(j){
    var list = j && Array.isArray(j.themes) ? j.themes.filter(function(t){ return t && ID.test(String(t.id)) && t.conf; }) : [];
    var ready = list.filter(function(t){ return t.community !== true; }), comm = list.filter(function(t){ return t.community === true; }).reverse();   // the community's newest = last in the file
    all = ready.map(function(t, i){ return prep(t, i, false); }).concat(comm.map(function(t, i){ return prep(t, ready.length + i, true); }));
    drawChips(); render();
    // the slideshows' lists for the counts, then redraw the badges
    var names = {}; all.forEach(function(it){ if (it.list) names[it.list] = 1; });
    Promise.all(Object.keys(names).map(ensureSlides)).then(function(){
      grid.querySelectorAll(".b-ss").forEach(function(b){ var c = b.closest(".tp-card"), it = all.filter(function(x){ return x.id === c.dataset.id; })[0], n = it && slides[it.list] ? slides[it.list].length : 0; if (n) b.querySelectorAll("span").forEach(function(s){ s.textContent = "×" + n; }); });
    });
    if (location.hash) openId(decodeURIComponent(location.hash.slice(1)), false);
  }).catch(function(){ grid.textContent = ""; empty.hidden = false; });
})();

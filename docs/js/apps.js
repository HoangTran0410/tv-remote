// Draws the apps map and its tables from data/apps-map.json (the one source; the READMEs' mermaid comes from the same file via dev/gen/apps_map.js).
// Both languages are written out as .vi / .en pairs; site.css shows the one in <html data-lang>.
(function(){
  var NS = "http://www.w3.org/2000/svg";
  var GLYPH = {                                                     // 24x24 line icons for the nodes that have no logo
    globe: '<circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3c3 3 3 15 0 18M12 3c-3 3-3 15 0 18"/>',
    mic: '<rect x="9" y="3" width="6" height="11" rx="3"/><path d="M5 11a7 7 0 0 0 14 0M12 18v3"/>',
    terminal: '<rect x="3" y="4" width="18" height="16" rx="2"/><path d="M7 9l3 3-3 3M13 15h4"/>',
    file: '<path d="M6 3h8l4 4v14H6z"/><path d="M14 3v4h4"/>',
    github: '<path d="M9 19c-4 1-4-2-6-2m12 4v-3a3 3 0 0 0-1-2c3 0 6-1.500 6-6a5 5 0 0 0-1-3 4 4 0 0 0 0-3s-1 0-3 1.500a10 10 0 0 0-6 0C8 4 7 4 7 4a4 4 0 0 0 0 3 5 5 0 0 0-1 3c0 4.500 3 6 6 6a3 3 0 0 0-1 2v3"/>',
    download: '<path d="M12 3v12m0 0l-5-5m5 5l5-5M4 21h16"/>',
    net: '<circle cx="6" cy="12" r="2.500"/><circle cx="18" cy="6" r="2.500"/><circle cx="18" cy="18" r="2.500"/><path d="M8.300 11l7.400-4M8.300 13l7.400 4"/>'
  };
  var COLOR = { ok: "var(--green)", partial: "var(--orange)", silent: "var(--red)" };
  var PILL = { ok: { vi: "Báo khi thiếu", en: "Tells you" }, partial: { vi: "Báo một phần", en: "Partly" }, silent: { vi: "Không báo", en: "Quiet" } };
  var HEAD = {
    link: { vi: "Đường nối", en: "Link" }, who: { vi: "Ai cần ai", en: "Who needs whom" }, what: { vi: "Để làm gì · đi đường nào", en: "What for · how" },
    miss: { vi: "Khi thiếu hoặc cũ", en: "If it is missing or old" }, app: { vi: "App", en: "App" }, code: { vi: "Mã Downloader", en: "Downloader code" },
    other: { vi: "Cách khác", en: "Other ways" }, none: { vi: "chưa có", en: "none yet" }, up: { vi: "xem trên sơ đồ", en: "see in graph" },
    to: { vi: "Xem dòng", en: "See row" }, label: { vi: "Đường nối", en: "Link" }
  };
  var ALT = { vi: "Sơ đồ các app kết nối với nhau", en: "Diagram of how the apps connect" };

  function esc(s){ return String(s).replace(/[&<>"]/g, function(c){ return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]; }); }
  function both(o, tag, cls, attrs){                                  // <tag class="vi">..</tag><tag class="en">..</tag>
    return ["vi", "en"].map(function(l){ return "<" + tag + ' class="' + l + (cls ? " " + cls : "") + '"' + (attrs || "") + ">" + esc(o[l]) + "</" + tag + ">"; }).join("");
  }
  function logo(n, size){
    return n.logo ? '<img src="' + esc(n.logo) + '" width="' + size + '" height="' + size + '" alt="" loading="lazy">' : "";
  }

  function draw(map){
    var byId = {}; map.nodes.forEach(function(n){ byId[n.id] = n; });
    var grp = {}; map.groups.forEach(function(g){ grp[g.id] = g; });
    var s = '<svg viewBox="0 0 ' + map.view.w + " " + map.view.h + '" role="group" aria-label="' + esc(ALT.en) + '"><defs>';
    Object.keys(COLOR).forEach(function(k){
      s += '<marker id="am-' + k + '" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto"><path d="M0 0L10 5L0 10z" fill="' + COLOR[k] + '"/></marker>';
    });
    s += "</defs>";
    map.groups.forEach(function(g){
      s += '<rect class="grp" data-g="' + g.id + '" x="' + g.x + '" y="' + g.y + '" width="' + g.w + '" height="' + g.h + '" rx="14"/>';
      ["vi", "en"].forEach(function(l){ s += '<text class="grpl ' + l + '" x="' + g.lx + '" y="' + g.ly + '"' + (g.end ? ' text-anchor="end"' : "") + ">" + esc(g.name[l]) + "</text>"; });
    });
    map.edges.forEach(function(e, i){
      var p = e.p, b = e.bend || 0, d;
      if (e.dir === "v") d = "M" + p[0] + " " + p[1] + " C" + (p[0] + b) + " " + (p[1] + (p[3] - p[1]) / 2) + " " + (p[2] + b) + " " + (p[1] + (p[3] - p[1]) / 2) + " " + p[2] + " " + p[3];   // bend: sideways
      else d = "M" + p[0] + " " + p[1] + " C" + (p[0] + (p[2] - p[0]) / 2) + " " + (p[1] + b) + " " + (p[0] + (p[2] - p[0]) / 2) + " " + (p[3] + b) + " " + p[2] + " " + p[3];
      s += '<path class="e ' + e.told + '" data-i="' + (i + 1) + '" d="' + d + '" marker-end="url(#am-' + e.told + ')"/><path class="hit" data-i="' + (i + 1) + '" tabindex="0" role="button" d="' + d + '"/>';
    });
    map.nodes.forEach(function(n){
      var h = n.h || 56, pad = n.logo || h > 50 ? 60 : 36, y0 = n.y + (h - 40) / 2;
      s += '<g class="node" data-n="' + n.id + '" tabindex="0" role="button"><rect x="' + n.x + '" y="' + n.y + '" width="' + n.w + '" height="' + h + '" rx="10"/>';
      if (n.logo) s += '<image href="' + esc(n.logo) + '" x="' + (n.x + 10) + '" y="' + y0 + '" width="40" height="40"/>';
      else if (n.glyph) {
        var big = h > 50, sc = big ? 1.5 : 1, gy = n.y + (h - 24 * sc) / 2;
        s += '<g class="glyph" transform="translate(' + (n.x + (big ? 12 : 10)) + " " + gy + ") scale(" + sc + ')">' + GLYPH[n.glyph] + "</g>";
        if (big) pad = 56;
      }
      ["vi", "en"].forEach(function(l){
        s += '<text class="nm ' + l + '" x="' + (n.x + pad) + '" y="' + (n.sub ? n.y + h / 2 - 3 : n.y + h / 2 + 4) + '"' + (h <= 50 ? ' font-size="12"' : "") + ">" + esc(n.name[l]) + "</text>";
        if (n.sub) s += '<text class="sub ' + l + '" x="' + (n.x + pad) + '" y="' + (n.y + h / 2 + 14) + '">' + esc(n.sub[l]) + "</text>";
      });
      s += "</g>";
    });
    map.edges.forEach(function(e, i){
      var p = e.p, mx = (p[0] + p[2]) / 2 + (e.dir === "v" ? 0.75 * (e.bend || 0) : 0), my = (p[1] + p[3]) / 2 + (e.dir === "v" ? 0 : 0.75 * (e.bend || 0));
      ["vi", "en"].forEach(function(l){
        var t = (i + 1) + " " + e.tag[l], w = Math.round(14 + 6.4 * t.length);
        s += '<g class="tag ' + l + '" data-i="' + (i + 1) + '"><rect x="' + Math.round(mx - w / 2) + '" y="' + (my - 9) + '" width="' + w + '" height="18" rx="5"/><text x="' + mx + '" y="' + (my + 4) + '" text-anchor="middle" fill="' + COLOR[e.told] + '">' + esc(t) + "</text></g>";
      });
    });
    return s + "</svg>";
  }

  function who(map, id){
    var g = map.groups.filter(function(x){ return x.id === id; })[0];
    if (!g) return both(map.nodes.filter(function(n){ return n.id === id; })[0].name, "span");
    return ["vi", "en"].map(function(l){
      return '<span class="' + l + '">' + esc(map.nodes.filter(function(n){ return n.group === id; }).map(function(n){ return n.name[l]; }).join(", ")) + "</span>";
    }).join("");
  }

  function lbl(h){ return '<span class="lbl">' + both(h, "span") + "</span>"; }   // the column's name, shown only where the table is stacked (narrow screens)

  function edgeTable(map){
    var r = "<table><thead><tr><th>#</th><th>" + both(HEAD.who, "span") + "</th><th>" + both(HEAD.what, "span") + "</th><th>" + both(HEAD.miss, "span") + "</th><th></th></tr></thead><tbody>";
    map.edges.forEach(function(e, i){
      r += '<tr id="link-' + (i + 1) + '" data-i="' + (i + 1) + '"><td class="num">' + (i + 1) + "</td><td>" + lbl(HEAD.who) + who(map, e.from) + " → " + who(map, e.to) + "</td><td>" + lbl(HEAD.what) +
        both(e.what, "span") + ' <span class="how">' + both(e.how, "span") + "</span></td><td>" + lbl(HEAD.miss) + both(e.missing, "span") +
        '</td><td><span class="pill ' + e.told + '">' + both(PILL[e.told], "span") + '</span> <button type="button" class="up" data-up="' + (i + 1) + '">↑ ' + both(HEAD.up, "span") + "</button></td></tr>";
    });
    return r + "</tbody></table>";
  }

  function installTable(map){
    var byId = {}; map.nodes.forEach(function(n){ byId[n.id] = n; });
    var r = "<table><thead><tr><th>" + both(HEAD.app, "span") + "</th><th>" + both(HEAD.code, "span") + "</th><th>" + both(HEAD.other, "span") + "</th></tr></thead><tbody>";
    map.install.forEach(function(a){
      var n = byId[a.app];
      r += '<tr data-n="' + a.app + '"><td class="appc">' + logo(n, 28) + both(n.name, "span") + '</td><td class="mono">' + lbl(HEAD.code) + (a.code ? esc(a.code) : both(HEAD.none, "span")) + "</td><td>" + lbl(HEAD.other) + both(a.other, "span") + "</td></tr>";
    });
    return r + "</tbody></table>";
  }


  // ---- interaction: popover on edges, highlight on nodes / edges / rows -------------------------------------------
  var reduce = window.matchMedia && matchMedia("(prefers-reduced-motion: reduce)").matches;
  function lang(){ return document.documentElement.dataset.lang === "en" ? "en" : "vi"; }
  function members(map, id){                                           // an edge end is a node id or a group id
    var g = map.groups.filter(function(x){ return x.id === id; })[0];
    return g ? map.nodes.filter(function(n){ return n.group === id; }).map(function(n){ return n.id; }) : [id];
  }
  function icon(n){
    if (n.logo) return '<img src="' + esc(n.logo) + '" width="20" height="20" alt="">';
    return n.glyph ? '<svg class="gi" viewBox="0 0 24 24" width="20" height="20" aria-hidden="true"><g>' + GLYPH[n.glyph] + "</g></svg>" : "";
  }
  function chips(map, id){
    return members(map, id).map(function(m){
      var n = map.nodes.filter(function(x){ return x.id === m; })[0];
      return '<span class="chip">' + icon(n) + both(n.name, "span") + "</span>";
    }).join("");
  }
  function names(map, id, l){ return members(map, id).map(function(m){ return map.nodes.filter(function(x){ return x.id === m; })[0].name[l]; }).join(", "); }

  function wire(map){
    var box = document.getElementById("map"), svg = box.querySelector("svg"), pop = document.createElement("div"), pinned = 0, shown = 0, lastType = "mouse", flashT, y0 = 0;
    pop.className = "ampop"; pop.id = "ampop"; pop.hidden = true; pop.setAttribute("role", "dialog"); document.body.appendChild(pop);
    function $all(sel, root){ return Array.prototype.slice.call((root || document).querySelectorAll(sel)); }
    function label(){                                                  // aria-labels follow the page language
      var l = lang();
      $all(".hit", svg).forEach(function(h){ var e = map.edges[h.dataset.i - 1];
        h.setAttribute("aria-label", HEAD.label[l] + " " + h.dataset.i + ": " + names(map, e.from, l) + " → " + names(map, e.to, l) + ". " + e.what[l]); });
      $all(".node", svg).forEach(function(g){ var n = map.nodes.filter(function(x){ return x.id === g.dataset.n; })[0]; g.setAttribute("aria-label", n.name[l] + (n.sub ? ", " + n.sub[l] : "")); });
    }
    label();
    new MutationObserver(label).observe(document.documentElement, { attributes: true, attributeFilter: ["data-lang"] });

    function clearHl(){
      svg.classList.remove("dim");
      $all(".on", document.getElementById("map").closest(".am")).forEach(function(x){ x.classList.remove("on"); });
    }
    function applyHl(edges, nodes, groups){
      clearHl(); svg.classList.add("dim");
      edges.forEach(function(i){ $all('[data-i="' + i + '"]').forEach(function(x){ x.classList.add("on"); }); });
      nodes.forEach(function(n){ $all('[data-n="' + n + '"]').forEach(function(x){ x.classList.add("on"); }); });
      groups.forEach(function(g){ $all('[data-g="' + g + '"]', svg).forEach(function(x){ x.classList.add("on"); }); });
    }
    function hlEdge(i){ var e = map.edges[i - 1]; applyHl([i], members(map, e.from).concat(members(map, e.to)), [e.from, e.to]); }
    function hlNode(id){
      var ed = [], nd = [id], gr = [];
      map.edges.forEach(function(e, k){
        var f = members(map, e.from), t = members(map, e.to);
        if (f.indexOf(id) < 0 && t.indexOf(id) < 0) return;
        ed.push(k + 1); nd = nd.concat(f, t); gr.push(e.from, e.to);
      });
      applyHl(ed, nd, gr);
    }
    function hlFrom(el){ if (el.dataset.i) hlEdge(+el.dataset.i); else if (el.dataset.n) hlNode(el.dataset.n); }

    function popHtml(i){
      var e = map.edges[i - 1];
      return '<div class="ph"><span class="pn">' + i + '</span><span class="pill ' + e.told + '">' + both(PILL[e.told], "span") + "</span></div>" +
        '<div class="pw"><div class="ends">' + chips(map, e.from) + '</div><span class="arr" aria-hidden="true">→</span><div class="ends">' + chips(map, e.to) + "</div></div>" +
        '<p class="pwhat">' + both(e.what, "span") + '</p><p class="phow">' + both(e.how, "span") + '</p><p class="pmiss"><b>' + both(HEAD.miss, "span") + ":</b> " + both(e.missing, "span") + "</p>" +
        '<button type="button" class="pgo" data-go="' + i + '">↓ ' + both(HEAD.to, "span") + " " + i + "</button>";
    }
    function place(x, y, r){                                           // near the pointer / element, flipped and clamped into the viewport
      var vw = document.documentElement.clientWidth, vh = window.innerHeight, m = 8;
      pop.style.maxWidth = Math.min(360, vw - 2 * m) + "px"; pop.style.left = "0px"; pop.style.top = "0px";
      var w = pop.offsetWidth, h = pop.offsetHeight, top = y + 16 + h <= vh - m ? y + 16 : (y - 16 - h >= m ? y - 16 - h : Math.max(m, vh - m - h));
      var left = Math.max(m, Math.min(x - w / 2, vw - m - w));
      pop.style.left = left + "px"; pop.style.top = top + "px";
    }
    function open(i, x, y, pin){
      if (shown !== i) pop.innerHTML = popHtml(i);
      y0 = window.scrollY; shown = i; pinned = pin ? i : (pinned === i ? i : 0); pop.hidden = false; place(x, y); hlEdge(i);
    }
    function close(){ pop.hidden = true; shown = 0; pinned = 0; clearHl(); }
    function anchor(el){ var r = el.getBoundingClientRect(); return el.dataset.i && el.classList.contains("hit") ? midpoint(el, r) : { x: r.left + r.width / 2, y: r.top + r.height / 2 }; }
    function midpoint(path, r){
      try { var len = path.getTotalLength(), pt = path.getPointAtLength(len / 2), m = path.getScreenCTM(); return { x: m.a * pt.x + m.c * pt.y + m.e, y: m.b * pt.x + m.d * pt.y + m.f }; }
      catch(x){ return { x: r.left + r.width / 2, y: r.top + r.height / 2 }; }
    }
    function toRow(i){
      var tr = document.getElementById("link-" + i); if (!tr) return;
      close(); tr.scrollIntoView({ behavior: reduce ? "auto" : "smooth", block: "center" }); flash(tr);
    }
    function flash(el){
      clearTimeout(flashT); $all(".flash").forEach(function(x){ x.classList.remove("flash"); }); void el.offsetWidth; el.classList.add("flash");
      flashT = setTimeout(function(){ el.classList.remove("flash"); }, 1600);
    }
    function hitEl(t){ return t.closest && t.closest("#map [data-i], #map [data-n]"); }

    box.addEventListener("pointerover", function(ev){
      lastType = ev.pointerType; if (ev.pointerType === "touch" || pinned) return;
      var el = hitEl(ev.target); if (!el) return;
      if (el.dataset.i) open(+el.dataset.i, ev.clientX, ev.clientY, false); else { close(); hlFrom(el); }
    });
    box.addEventListener("pointermove", function(ev){ if (ev.pointerType !== "touch" && !pinned && shown && !pop.hidden) { var el = hitEl(ev.target); if (el && el.dataset.i == shown) place(ev.clientX, ev.clientY); } });
    box.addEventListener("pointerout", function(ev){
      if (ev.pointerType === "touch" || pinned) return;
      var to = ev.relatedTarget, el = hitEl(ev.target);
      if (!el || (to && to.closest && hitEl(to) === el)) return;
      close();
    });
    box.addEventListener("focusin", function(ev){
      var el = hitEl(ev.target); if (!el) return;
      if (el.dataset.i) { var a = anchor(el.classList.contains("tag") ? el : box.querySelector('.hit[data-i="' + el.dataset.i + '"]')); open(+el.dataset.i, a.x, a.y, false); }
      else { close(); hlFrom(el); }
    });
    box.addEventListener("focusout", function(ev){ if (!pinned && !(ev.relatedTarget && box.contains(ev.relatedTarget))) close(); });
    box.addEventListener("click", function(ev){
      var el = hitEl(ev.target); if (!el) return;
      if (el.dataset.i) {
        var i = +el.dataset.i;
        if (el.classList.contains("tag") && lastType === "mouse" && ev.detail > 0) { toRow(i); return; }   // mouse click on the number: jump to its row
        if (pinned === i) { close(); return; }
        open(i, ev.clientX || anchor(el).x, ev.clientY || anchor(el).y, true);
      } else if (lastType === "touch") { close(); hlNode(el.dataset.n); pinned = 0; }
    });
    box.addEventListener("keydown", function(ev){
      var el = hitEl(ev.target); if (!el || (ev.key !== "Enter" && ev.key !== " ")) return;
      ev.preventDefault(); if (el.dataset.i) { var a = anchor(el); if (pinned === +el.dataset.i) close(); else open(+el.dataset.i, a.x, a.y, true); }
    });
    pop.addEventListener("click", function(ev){ var b = ev.target.closest("[data-go]"); if (b) toRow(+b.dataset.go); });
    document.addEventListener("pointerdown", function(ev){ if (!pop.hidden && !pop.contains(ev.target) && !box.contains(ev.target)) close(); else if (!pop.hidden && !pop.contains(ev.target) && !hitEl(ev.target)) close(); });
    document.addEventListener("keydown", function(ev){ if (ev.key === "Escape" && !pop.hidden) { var f = shown; close(); var h = box.querySelector('.hit[data-i="' + f + '"]'); if (h && box.contains(document.activeElement)) h.focus({ preventScroll: true }); } });
    window.addEventListener("scroll", function(){ if (!pop.hidden && lastType !== "touch" && Math.abs(window.scrollY - y0) > 4) close(); }, { passive: true });
    window.addEventListener("resize", function(){ if (!pop.hidden) close(); });

    var tables = document.getElementById("edges").closest(".am");
    ["edges", "install"].forEach(function(id){
      var t = document.getElementById(id);
      t.addEventListener("pointerover", function(ev){
        if (ev.pointerType === "touch") return; var tr = ev.target.closest("tr[data-i], tr[data-n]"); if (tr) { if (!pop.hidden) close(); hlFrom(tr); }
      });
      t.addEventListener("pointerleave", function(ev){ if (ev.pointerType !== "touch") clearHl(); });
      t.addEventListener("focusin", function(ev){ var tr = ev.target.closest("tr[data-i], tr[data-n]"); if (tr) hlFrom(tr); });
      t.addEventListener("focusout", function(){ clearHl(); });
    });
    document.getElementById("edges").addEventListener("click", function(ev){
      var b = ev.target.closest("[data-up]"); if (!b) return; var i = +b.dataset.up;
      box.scrollIntoView({ behavior: reduce ? "auto" : "smooth", block: "nearest" }); hlEdge(i);
      var g = svg.querySelector('.e[data-i="' + i + '"]'); if (g) { g.classList.add("flashe"); setTimeout(function(){ g.classList.remove("flashe"); clearHl(); }, 1800); }
    });
    if (/^#link-\d+$/.test(location.hash)) { var r = document.querySelector(location.hash); if (r) flash(r); }
  }

  fetch("data/apps-map.json").then(function(r){ return r.json(); }).then(function(map){
    document.getElementById("map").innerHTML = draw(map);
    document.getElementById("edges").innerHTML = edgeTable(map);
    document.getElementById("install").innerHTML = installTable(map);
    wire(map);
  }).catch(function(){
    document.getElementById("map").textContent = "Could not load the map.";
  });
})();

// "TVs it ran on": draws the census (GET /v1/tvs of the tvr-census Worker, dev/census) into every [data-tvs] element.
// data-limit="8": the home page: that many brands as small tiles (the brand, its count, its top model; ~3 screens of rows on a phone
// before) until the person searches or picks a kind; a tile searches its brand. The rest is tvs.html.
(function(){
  var URL_ = "https://tvr-census.99-hoangtran.workers.dev/v1/tvs";
  var W = {                                                         // the words, both languages
    kinds: [["all", "Tất cả", "All"], ["tv", "Tivi", "TVs"], ["box", "TV Box", "Boxes, sticks"], ["projector", "Máy chiếu", "Projectors"]],
    badge: { tv: ["Tivi", "TV"], box: ["Box", "Box"], projector: ["Máy chiếu", "Projector"] },
    search: ["Tìm hãng, mẫu, mã máy…", "Search brand, model, device…"],
    sum: ["{d} thiết bị · {n} mẫu · {b} hãng", "{d} devices · {n} models · {b} brands"],
    units: ["{n} máy", "{n} TVs"], none: ["Không có mẫu nào khớp.", "No model matches."],
    empty: ["Chưa có dữ liệu. Danh sách sẽ hiện khi các app gửi báo cáo ẩn danh đầu tiên.", "No data yet. The list fills as the apps send their first anonymous reports."],
    off: ["Không tải được danh sách lúc này. Thử lại sau.", "Could not load the list right now. Try again later."],
    big: ["{n} mẫu máy, {b} hãng", "{n} models, {b} brands"],
    load: ["Đang tải…", "Loading…"], more: ["Xem thêm ({n})", "Show more ({n})"], less: ["Thu gọn", "Show less"], all: ["Xem tất cả {b} hãng →", "See all {b} brands →"]
  };
  var SHOW = 3;                                                     // rows per brand before "Show more"
  var lang = function(){ return document.documentElement.dataset.lang === "en" ? 1 : 0; };
  var t = function(k, v){ var s = W[k][lang()]; for (var x in v) s = s.replace("{" + x + "}", v[x]); return s; };
  function esc(s){ return String(s).replace(/[&<>"]/g, function(c){ return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]; }); }
  function norm(s){ return String(s).normalize("NFD").replace(/[̀-ͯ]/g, "").replace(/đ/g, "d").replace(/Đ/g, "D").toLowerCase(); }
  function android(a){ var v = (a || []).slice().sort(function(x, y){ return parseFloat(x) - parseFloat(y); }); return !v.length ? "" : v.length === 1 ? "Android " + v[0] : "Android " + v[0] + "–" + v[v.length - 1]; }

  function mount(el){
    var limit = +el.dataset.limit || 0, data = null, kind = "all", q = "", failed = false, open = {};
    var kindFor = function(m){ return m.kind === "box" || m.kind === "projector" ? m.kind : "tv"; };
    el.innerHTML = '<div class="tv-tools"><input type="search" class="tv-q" autocomplete="off" spellcheck="false" aria-label="Search"><div class="tv-chips" role="group"></div></div><p class="tv-sum"></p><div class="tv-list"></div>';
    var inp = el.querySelector(".tv-q"), chips = el.querySelector(".tv-chips"), sum = el.querySelector(".tv-sum"), list = el.querySelector(".tv-list");
    inp.oninput = function(){ q = norm(inp.value.trim()); draw(); };
    list.onclick = function(e){
      var b = e.target.closest(".tv-fold"); if (b) { open[b.dataset.b] = !open[b.dataset.b]; draw(); }
      var c = e.target.closest(".tv-tile"); if (c) { inp.value = c.dataset.b; inp.oninput(); }
    };
    chips.onclick = function(e){ var b = e.target.closest("button"); if (b) { kind = b.dataset.k; draw(); } };

    function draw(){
      inp.placeholder = t("search");
      var models = data ? data.models : [], counts = { all: models.length, tv: 0, box: 0, projector: 0 };
      models.forEach(function(m){ counts[kindFor(m)]++; });
      chips.innerHTML = W.kinds.map(function(k){
        return '<button type="button" data-k="' + k[0] + '" aria-pressed="' + (kind === k[0]) + '">' + esc(k[1 + lang()]) + ' <span class="n">' + counts[k[0]] + "</span></button>";
      }).join("");
      if (!data && limit && !failed) { sum.innerHTML = "&nbsp;"; list.innerHTML = '<div class="tv-tiles" aria-busy="true">' + new Array(limit + 1).join('<span class="tv-tile tv-wait"><b>&nbsp;</b><span class="n">&nbsp;</span><small>&nbsp;</small></span>') + '</div><span class="btn tv-more tv-wait">&nbsp;</span>'; return; }   // the same room as the tiles: a link to #install is not pushed off screen when the list lands
      if (!data) { sum.textContent = ""; list.innerHTML = '<p class="tv-note">' + esc(failed ? t("off") : t("load")) + "</p>"; return; }
      if (!models.length) { sum.textContent = ""; list.innerHTML = '<p class="tv-note">' + esc(t("empty")) + "</p>"; return; }
      var shown = models.filter(function(m){
        return (kind === "all" || kindFor(m) === kind) && (!q || norm([m.brand, m.model].concat(m.devices || []).join(" ")).indexOf(q) >= 0);
      });
      var groups = {}, order = [];
      shown.forEach(function(m){ var g = groups[m.brand] || (groups[m.brand] = { brand: m.brand, n: 0, rows: [] }); if (!g.rows.length) order.push(g); g.n += m.count; g.rows.push(m); });
      order.sort(function(a, b){ return b.n - a.n || a.brand.localeCompare(b.brand); });
      sum.textContent = t("sum", { d: shown.reduce(function(a, m){ return a + (m.count || 0); }, 0), n: shown.length, b: order.length });
      var cut = limit && !q && kind === "all" && order.length > limit;
      var fold = !q && kind === "all";                                  // a search or a chip shows every match
      var html = cut ? '<div class="tv-tiles">' + order.slice(0, limit).map(function(g){
        var m = g.rows.reduce(function(a, r){ return r.count > a.count ? r : a; });
        return '<button type="button" class="tv-tile" data-b="' + esc(g.brand) + '"><b>' + esc(g.brand) + '</b><span class="n">' + g.n + '</span><small>' + esc(m.model) + " ×" + m.count + "</small></button>";
      }).join("") + "</div>" : order.map(function(g){
        return '<section class="tv-brand"><h3><span>' + esc(g.brand) + '</span><b>' + g.n + "</b></h3>" + g.rows.map(function(m, i){
          if (fold && g.rows.length > SHOW + 1 && !open[g.brand] && i >= SHOW) return "";
          var k = kindFor(m);
          return '<div class="tv-row"><span class="tv-m">' + esc(m.model) + '</span><span class="tv-a">' + esc(android(m.android)) + '</span><span class="tv-c" title="' + esc(t("units", { n: m.count })) + '">×' + m.count + '</span><i class="tv-b k-' + k + '">' + esc(W.badge[k][lang()]) + "</i></div>";
        }).join("") + (fold && g.rows.length > SHOW + 1 ? '<button type="button" class="tv-fold" data-b="' + esc(g.brand) + '" aria-expanded="' + !!open[g.brand] + '">' + esc(open[g.brand] ? t("less") : t("more", { n: g.rows.length - SHOW })) + "</button>" : "") + "</section>";
      }).join("");
      if (!order.length) html = '<p class="tv-note">' + esc(t("none")) + "</p>";
      if (cut) html += '<a class="btn btn-ghost tv-more" href="tvs">' + esc(t("all", { b: order.length })) + "</a>";
      list.innerHTML = html;
    }
    draw();
    new MutationObserver(draw).observe(document.documentElement, { attributes: true, attributeFilter: ["data-lang"] });
    get().then(function(d){ data = d; draw(); }).catch(function(){ failed = true; draw(); });
  }
  var once;                                                         // one request for the list and the headline
  // one more try a moment later: a Worker that threw once (a D1 hiccup) answers with no CORS header, which reads as a failure here
  function load(u){ var f = function(){ return fetch(u).then(function(r){ if (!r.ok) throw 0; return r.json(); }); };
    return f().catch(function(){ return new Promise(function(ok){ setTimeout(ok, 1500); }).then(f); }); }
  function get(){ return once || (once = load(URL_).then(function(d){ return d && d.models ? d : { models: [] }; })); }
  document.querySelectorAll("[data-tvs]").forEach(mount);

  // The headline in big numbers ([data-tvs-big]): its own words stay until the census answers, or when it cannot.
  function big(el){
    var m = null;
    function draw(){ if (m && m.length) el.textContent = t("big", { n: m.length, b: new Set(m.map(function(x){ return x.brand; })).size }); }
    get().then(function(d){ m = d.models; draw(); }).catch(function(){});
    new MutationObserver(draw).observe(document.documentElement, { attributes: true, attributeFilter: ["data-lang"] });
  }
  document.querySelectorAll("[data-tvs-big]").forEach(big);

  // The chart above the list on tvs.html ([data-tvs-chart]): GET /v1/days, devices active per day, stacked by app, plus the running total.
  // Fetched once, only when the chart scrolls into view (nothing is paid before the page shows it). Inline SVG, no library.
  var DAYS = "https://tvr-census.99-hoangtran.workers.dev/v1/days";
  var CW = {
    title: ["Thiết bị mỗi ngày", "Devices per day"], total: ["Tổng đang chạy", "Running total"], active: ["hoạt động", "active"],
    desc: ["Biểu đồ cột: số thiết bị hoạt động mỗi ngày, chia theo app.", "Bar chart: devices active each day, split by app."],
    off: ["Không tải được biểu đồ lúc này.", "Could not load the chart right now."], load: ["Đang tải biểu đồ…", "Loading the chart…"],
    none: ["Chưa có số liệu theo ngày.", "No daily numbers yet."], day: ["Ngày", "Day"], sum: ["Cộng", "Sum"],
    apps: { setup: ["App TV (TV Setup)", "TV app (TV Setup)"], phone: ["App điện thoại", "Phone app"], pc: ["App máy tính", "Computer app"], home: ["TV Home", "TV Home"], other: ["Khác", "Other"] }
  };
  var ORDER = ["setup", "phone", "pc", "home", "other"], COLOR = { setup: "var(--blue)", phone: "var(--orange)", pc: "var(--green)", home: "#e06ad6", other: "var(--dim)" };
  var MAXDAYS = 60;
  var ct = function(k){ return CW[k][lang()]; };
  function perApp(o){ var r = {}, any = 0; for (var k in o || {}) { var n = +o[k] || 0; any += n; r[ORDER.indexOf(k) >= 0 ? k : "other"] = (r[ORDER.indexOf(k) >= 0 ? k : "other"] || 0) + n; } r._ = any; return r; }
  function dayLabel(d){ var p = d.split("-"); return lang() ? p[1] + "/" + p[2] : p[2] + "/" + p[1]; }

  function mountChart(el){
    var data = null, failed = false, sel = -1, started = false, pad = { l: 34, r: 8, t: 8, b: 22 }, H = 150;
    el.innerHTML = '<div class="dc-head"><h2 class="dc-t"></h2><p class="dc-tot"></p></div><div class="dc-plot"></div><p class="dc-read" aria-live="polite"></p><p class="dc-key"></p>';
    var tt = el.querySelector(".dc-t"), tot = el.querySelector(".dc-tot"), plot = el.querySelector(".dc-plot"), read = el.querySelector(".dc-read"), key = el.querySelector(".dc-key");
    function rows(){ return (data.days || []).slice(-MAXDAYS).map(function(d){ var a = perApp(d.active); if (!a._) a = perApp(d.new); return { day: d.day, a: a, sum: a._, total: d.total }; }); }
    function say(r){
      return dayLabel(r.day) + ": " + r.sum + " " + ct("active") + " · " + ORDER.filter(function(k){ return r.a[k]; }).map(function(k){ return CW.apps[k][lang()] + " " + r.a[k]; }).join(", ") + " · " + ct("total") + " " + r.total;
    }
    function draw(){
      tt.textContent = ct("title");
      if (!data) { tot.textContent = ""; key.innerHTML = ""; read.textContent = ""; plot.innerHTML = '<p class="tv-note">' + esc(failed ? ct("off") : ct("load")) + "</p>"; return; }
      var R = rows();
      if (!R.length) { tot.textContent = ""; key.innerHTML = ""; read.textContent = ""; plot.innerHTML = '<p class="tv-note">' + esc(ct("none")) + "</p>"; return; }
      var last = R[R.length - 1], i0 = sel >= 0 && sel < R.length ? sel : R.length - 1;
      tot.innerHTML = "<b>" + (data.installs != null ? data.installs : last.total) + "</b> " + esc(ct("total").toLowerCase());
      var W_ = Math.max(220, plot.clientWidth || 320), iw = W_ - pad.l - pad.r, ih = H - pad.t - pad.b, n = R.length;
      var max = Math.max.apply(null, R.map(function(r){ return r.sum; }).concat(1)), step = 1;
      var nice = [1, 2, 5, 10, 20, 25, 50, 100, 200, 250, 500, 1000, 2000, 5000]; for (var q = 0; q < nice.length; q++) { step = nice[q]; if (max / step <= 4) break; }
      var top = Math.ceil(max / step) * step, slot = iw / Math.max(n, 6), bw = Math.min(Math.max(slot * 0.7, 3), 40);
      var y = function(v){ return pad.t + ih - ih * v / top; };
      var s = '<svg viewBox="0 0 ' + W_ + " " + H + '" width="' + W_ + '" height="' + H + '" role="img" aria-labelledby="dc-ti dc-de"><title id="dc-ti">' + esc(ct("title")) + '</title><desc id="dc-de">' + esc(ct("desc") + " " + say(last)) + "</desc>";
      for (var v = 0; v <= top; v += step) s += '<line class="dc-grid" x1="' + pad.l + '" x2="' + (W_ - pad.r) + '" y1="' + y(v) + '" y2="' + y(v) + '"/><text class="dc-ax" x="' + (pad.l - 6) + '" y="' + (y(v) + 4) + '" text-anchor="end">' + v + "</text>";
      var every = Math.ceil(n / Math.max(1, Math.floor(iw / 52)));
      R.forEach(function(r, i){
        var x = pad.l + slot * i + (slot - bw) / 2, acc = 0, g = '<g class="dc-day' + (i === i0 ? " on" : "") + '" data-i="' + i + '">';
        g += '<rect class="dc-hit" x="' + (pad.l + slot * i) + '" y="' + pad.t + '" width="' + slot + '" height="' + ih + '"/>';
        ORDER.forEach(function(k){ var c = r.a[k]; if (!c) return; var h = ih * c / top; acc += c; g += '<rect class="dc-bar" x="' + x + '" y="' + y(acc) + '" width="' + bw + '" height="' + h + '" fill="' + COLOR[k] + '"/>'; });
        if (i % every === 0 || i === n - 1 && (n - 1) % every >= every / 2) g += '<text class="dc-ax" x="' + (i === n - 1 && n > 2 ? x + bw : x + bw / 2) + '" y="' + (H - 6) + '" text-anchor="' + (i === n - 1 && n > 2 ? "end" : "middle") + '">' + dayLabel(r.day) + "</text>";
        s += g + "</g>";
      });
      plot.innerHTML = s + "</svg>";
      read.textContent = say(R[i0]);
      var used = ORDER.filter(function(k){ return R.some(function(r){ return r.a[k]; }); });
      key.innerHTML = used.map(function(k){ return '<span><i style="background:' + COLOR[k] + '"></i>' + esc(CW.apps[k][lang()]) + "</span>"; }).join("");
      var tb = '<table class="dc-sr"><caption>' + esc(ct("title")) + "</caption><tr><th>" + esc(ct("day")) + "</th>" + used.map(function(k){ return "<th>" + esc(CW.apps[k][lang()]) + "</th>"; }).join("") + "<th>" + esc(ct("sum")) + "</th><th>" + esc(ct("total")) + "</th></tr>";
      R.forEach(function(r){ tb += "<tr><td>" + esc(r.day) + "</td>" + used.map(function(k){ return "<td>" + (r.a[k] || 0) + "</td>"; }).join("") + "<td>" + r.sum + "</td><td>" + r.total + "</td></tr>"; });
      plot.insertAdjacentHTML("beforeend", tb + "</table>");
    }
    function pick(e){ var g = e.target.closest && e.target.closest(".dc-day"); if (g && +g.dataset.i !== sel) { sel = +g.dataset.i; draw(); } }
    plot.onpointerover = pick; plot.onclick = pick;
    plot.onpointerleave = function(){ if (sel >= 0) { sel = -1; draw(); } };
    function start(){
      if (started) return; started = true;
      load(DAYS).then(function(d){ data = d && d.days ? d : { days: [] }; draw(); }).catch(function(){ failed = true; draw(); });
    }
    draw();
    new MutationObserver(draw).observe(document.documentElement, { attributes: true, attributeFilter: ["data-lang"] });
    var rz; window.addEventListener("resize", function(){ clearTimeout(rz); rz = setTimeout(function(){ if (data) draw(); }, 120); });
    if ("IntersectionObserver" in window) { var io = new IntersectionObserver(function(es){ if (es.some(function(e){ return e.isIntersecting; })) { io.disconnect(); start(); } }, { rootMargin: "200px" }); io.observe(el); }
    else start();
  }
  document.querySelectorAll("[data-tvs-chart]").forEach(mountChart);
})();

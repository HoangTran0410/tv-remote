/* The home page: language, copy a code, the download for this device, the release number, a gentle fade-in on scroll. */
(function(){
  var root = document.documentElement;
  var titles = {
    vi: "TV Remote: điều khiển và dọn sạch Android TV bằng điện thoại",
    en: "TV Remote: control and clean up Android TV from your phone"
  };
  function setLang(l){
    root.dataset.lang = l; root.lang = l; document.title = titles[l];
    document.querySelectorAll("img[data-lang-src]").forEach(function(i){ LI(i); });
    document.querySelectorAll(".lang button").forEach(function(b){ b.setAttribute("aria-pressed", b.dataset.set === l); });
    try { localStorage.setItem("lang", l); } catch(e){}
  }
  setLang(root.dataset.lang);
  document.querySelectorAll(".lang button").forEach(function(b){ b.onclick = function(){ setLang(b.dataset.set); }; });

  // copy the Downloader code
  var toast = document.getElementById("toast"), tt;
  function say(s){ toast.textContent = s; toast.classList.add("show"); clearTimeout(tt); tt = setTimeout(function(){ toast.classList.remove("show"); }, 1800); }
  document.querySelectorAll("[data-copy]").forEach(function(b){
    b.onclick = function(){
      var v = b.dataset.copy, en = root.dataset.lang === "en";
      (navigator.clipboard ? navigator.clipboard.writeText(v) : Promise.reject())
        .then(function(){ say((en ? "Copied " : "Đã chép ") + v); })
        .catch(function(){ say((en ? "Code: " : "Mã: ") + v); });
    };
  });

  // the setup steps: one path per kind of device (an Android phone or a computer needs nothing on the TV to start)
  var paths = document.querySelectorAll(".path");
  paths.forEach(function(b){
    b.onclick = function(){
      paths.forEach(function(o){ o.setAttribute("aria-selected", o === b); });
      document.querySelectorAll(".steps4[data-for]").forEach(function(l){ l.hidden = l.dataset.for !== b.dataset.path; });
    };
  });

  // fade in on scroll (nothing moves for people who asked for less motion: site.css)
  var rv = document.querySelectorAll(".reveal");
  if ("IntersectionObserver" in window){
    var io = new IntersectionObserver(function(es){
      es.forEach(function(e){ if (e.isIntersecting){ e.target.classList.add("in"); io.unobserve(e.target); } });
    }, {rootMargin:"0px 0px -8% 0px"});
    rv.forEach(function(el){ io.observe(el); });
  } else rv.forEach(function(el){ el.classList.add("in"); });

  // (one function for every row of the page: "And more", the themes)
  // the row: buttons, arrow keys and the "3 / 8" read the same scroll position; nothing moves by itself
  function carousel(id){
    var t = document.getElementById(id); if (!t) return;
    var cards = t.children, n = document.getElementById(id + "-n"), pv = document.getElementById(id + "-prev"), nx = document.getElementById(id + "-next");
    var calm = window.matchMedia && matchMedia("(prefers-reduced-motion: reduce)").matches;
    // the cards' real pitch, fractions kept (393.6 px): offsetLeft rounds to 394, and i * 394 drifted off the snap point, 9 px by
    // the 30th card, so the snap pulled it there with a jerk once the glide let go
    function step(){ var n = cards.length; return n > 1 ? (cards[n - 1].getBoundingClientRect().left - cards[0].getBoundingClientRect().left) / (n - 1) : t.clientWidth; }
    function upd(){
      var i = Math.min(cards.length - 1, Math.round(t.scrollLeft / step()));
      n.textContent = (i + 1) + " / " + cards.length;
      pv.disabled = t.scrollLeft < 4; nx.disabled = t.scrollLeft > t.scrollWidth - t.clientWidth - 4;
    }
    // our own glide, not scrollBy smooth: that one fought the mandatory snap (a stutter at the end) and a second press started
    // from wherever the first had got to, landing between cards; reduced motion still slides, only shorter (a jump lost people)
    var glide = null;
    function max(){ return t.scrollWidth - t.clientWidth; }
    function to(i){
      var s = step(), last = Math.ceil(max() / s - 0.01); i = Math.max(0, Math.min(i, last));
      var from = t.scrollLeft, end = Math.min(i * s, max()), ms = calm ? 220 : 420, t0 = performance.now();
      if (glide) cancelAnimationFrame(glide.raf);
      glide = {i: i}; t.classList.add("glide");
      (function f(now){
        var k = Math.min(1, (now - t0) / ms), e = 1 - Math.pow(1 - k, 3);   // ease-out cubic
        t.scrollLeft = from + (end - from) * e;
        if (k < 1) glide.raf = requestAnimationFrame(f); else { glide = null; t.classList.remove("glide"); }
      })(t0);
    }
    function go(d){ to((glide ? glide.i : Math.round(t.scrollLeft / step())) + d); }
    pv.addEventListener("click", function(){ go(-1); }); nx.addEventListener("click", function(){ go(1); });
    t.addEventListener("keydown", function(e){
      if (e.target !== t) return;
      if (e.key === "ArrowRight"){ e.preventDefault(); go(1); } else if (e.key === "ArrowLeft"){ e.preventDefault(); go(-1); }
    });
    // a mouse drags the row like a finger (touch already swipes it): snap and smooth off while held, a flick carries on to the
    // card it points at; a drag is never also a click on the card under it
    var drag = null, moved = false;
    t.addEventListener("pointerdown", function(e){
      if (e.pointerType !== "mouse" || e.button !== 0) return;
      if (glide){ cancelAnimationFrame(glide.raf); glide = null; t.classList.remove("glide"); }
      drag = {x: e.clientX, left: t.scrollLeft, id: e.pointerId, lx: e.clientX, lt: e.timeStamp, v: 0}; moved = false;
    });
    t.addEventListener("pointermove", function(e){
      if (!drag || e.pointerId !== drag.id) return;
      var dx = e.clientX - drag.x;
      if (!moved){ if (Math.abs(dx) < 6) return; moved = true; t.classList.add("drag"); t.setPointerCapture(drag.id); }
      t.scrollLeft = drag.left - dx;
      var dt = e.timeStamp - drag.lt; if (dt > 0) drag.v = (e.clientX - drag.lx) / dt;   // px per ms, for the flick
      drag.lx = e.clientX; drag.lt = e.timeStamp;
    });
    function let_go(e){
      if (!drag) return;
      var v = e.timeStamp - drag.lt > 120 ? 0 : drag.v; drag = null;   // held still before letting go: no flick
      if (!moved) return;
      t.classList.add("glide"); t.classList.remove("drag");   // snap stays off: step() below reads layout, and a snap back on there jumped before the glide
      var s = step(), at = t.scrollLeft / s;
      var i = Math.abs(v) > 0.3 ? (v < 0 ? Math.ceil(at) : Math.floor(at)) : Math.round(at);   // a flick goes on to the next card in its way, never two
      to(i);
    }
    t.addEventListener("pointerup", let_go); t.addEventListener("pointercancel", let_go);
    t.addEventListener("click", function(e){ if (moved){ e.preventDefault(); e.stopPropagation(); moved = false; } }, true);
    t.addEventListener("dragstart", function(e){ e.preventDefault(); });   // the pictures and links would start a native drag instead
    t.addEventListener("scroll", function(){ requestAnimationFrame(upd); }, {passive:true});
    window.addEventListener("resize", upd); upd();
  }
  carousel("car"); carousel("th");

  // the themes' pictures load as the row scrolls near (loading=lazy alone is not enough: Chrome pulls a whole scroller at once, and
  // some browsers never fire it inside a horizontal one). Two steps: the row waits until its section is near the screen, then a card
  // is loaded when it is within ~one card of the row's visible part. Without IntersectionObserver every picture loads at once.
  function loadImg(img){ var d = img && img.getAttribute("data-src"); if (d){ img.removeAttribute("data-src"); img.src = d; } }
  function lazyRow(track){
    var imgs = function(){ return Array.prototype.slice.call(track.querySelectorAll("img[data-src]")); };
    if (!("IntersectionObserver" in window)){ imgs().forEach(loadImg); return; }
    var rowIo = new IntersectionObserver(function(es){
      es.forEach(function(e){ if (e.isIntersecting){ loadImg(e.target); rowIo.unobserve(e.target); } });
    }, {root: track, rootMargin: "0px 400px 0px 400px"});
    var wake = new IntersectionObserver(function(es){
      if (!es.some(function(e){ return e.isIntersecting; })) return;
      wake.disconnect(); imgs().forEach(function(i){ rowIo.observe(i); });
    }, {rootMargin: "300px 0px"});
    wake.observe(track);
  }
  lazyRow(document.getElementById("th"));

  // a theme's picture, large: a dialog (Esc, the X and a tap outside close it, arrows go through the themes, the community's too);
  // focus goes back to the card
  var zoom = (function(){
    var d = document.getElementById("th-zoom"); if (!d || !d.showModal) return null;
    var at = 0, from = null, cards = [];
    var img = d.querySelector("img"), nm = d.querySelector(".th-name");
    function all(){ return Array.prototype.slice.call(document.querySelectorAll("#th [data-th], #cm [data-th]")); }
    function show(i){
      cards = all(); if (!cards.length) return;
      at = (i + cards.length) % cards.length;
      var pic = cards[at].querySelector("img"); loadImg(pic);
      var small = pic.src, big = small.replace(/\.webp$/, "-big.webp"), want = at;
      img.src = small; nm.innerHTML = cards[at].querySelector("h3").innerHTML;   // the card's picture at once (already loaded),
      var b = new Image(); b.onload = function(){ if (at === want) img.src = big; }; b.src = big;   // then 1280x720 (~45 KB) only when zoomed
      var nx = cards[(at + 1) % cards.length].querySelector("img"); loadImg(nx);
      new Image().src = nx.src.replace(/\.webp$/, "-big.webp");   // the next one ready
    }
    ["th", "cm"].forEach(function(id){
      var t = document.getElementById(id); if (!t) return;
      t.addEventListener("click", function(e){
        var c = e.target.closest && e.target.closest("[data-th]"); if (!c || !t.contains(c)) return;
        from = c; show(all().indexOf(c)); d.showModal();
      });
    });
    d.querySelector(".th-x").onclick = function(){ d.close(); };
    d.querySelector(".th-p").onclick = function(){ show(at - 1); };
    d.querySelector(".th-n").onclick = function(){ show(at + 1); };
    d.addEventListener("click", function(e){ if (e.target === d) d.close(); });   // the backdrop counts as the dialog itself
    d.addEventListener("keydown", function(e){
      if (e.key === "ArrowRight"){ e.preventDefault(); show(at + 1); } else if (e.key === "ArrowLeft"){ e.preventDefault(); show(at - 1); }
    });
    d.addEventListener("close", function(){ if (from) from.focus(); });
    return d;
  })();

  // the community's row, from the signed list the site already serves (entries with community:true, newest = last in the file);
  // read once, when the section is near. No entry (or no list) = the row stays hidden and takes no room. The names are users' words:
  // set as text, never as markup; the picture's file name only if it is a plain id.
  (function(){
    var box = document.getElementById("cm-box"), tr = document.getElementById("cm"), sec = document.getElementById("themes");
    if (!box || !tr || !sec) return;
    function pair(tag, vi, en, cls){
      var f = document.createDocumentFragment();
      [["vi", vi], ["en", en]].forEach(function(p){ var s = document.createElement("span"); s.className = p[0]; s.textContent = p[1]; f.appendChild(s); });
      var e = document.createElement(tag); if (cls) e.className = cls; e.appendChild(f); return e;
    }
    function build(list){
      var items = list.filter(function(t){ return t && t.community === true && /^[a-z0-9][a-z0-9-]*$/i.test(String(t.id)); }).reverse();
      if (!items.length) return;
      items.forEach(function(t, i){
        var nm = t.names || {}, vi = String(nm.vi || nm.en || t.id), en = String(nm.en || nm.vi || t.id), by = String(t.by || "").slice(0, 60);
        var c = document.createElement("button"); c.type = "button"; c.className = "fc thc"; c.setAttribute("data-th", "c" + i); c.setAttribute("aria-haspopup", "dialog");
        var im = document.createElement("img");
        im.setAttribute("data-src", "img/themes/" + t.id + ".webp"); im.width = 480; im.height = 270; im.alt = ""; im.loading = "lazy"; im.decoding = "async";
        c.appendChild(im); c.appendChild(pair("h3", vi, en));
        if (by) c.appendChild(pair("p", "bởi " + by, "by " + by));
        tr.appendChild(c);
      });
      var own = document.createElement("a"); own.className = "fc thc thc-own"; own.href = "guide#home-themes";
      own.innerHTML = '<span class="thc-ph" aria-hidden="true"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 5v14M5 12h14"/></svg></span>' +
        '<h3><span class="vi">Tạo của bạn</span><span class="en">Create yours</span></h3><p><span class="vi">Chia sẻ chủ đề của bạn</span><span class="en">Share your theme</span></p>';
      tr.appendChild(own);
      box.hidden = false; carousel("cm"); lazyRow(tr);
    }
    function load(){
      fetch("home/themes.json").then(function(r){ return r.ok ? r.json() : null; })
        .then(function(j){ if (j && Array.isArray(j.themes)) build(j.themes); }).catch(function(){});
    }
    if (!("IntersectionObserver" in window)){ load(); return; }
    var io = new IntersectionObserver(function(es){ if (es.some(function(e){ return e.isIntersecting; })){ io.disconnect(); load(); } }, {rootMargin: "600px 0px"});
    io.observe(sec);
  })();

  document.getElementById("yr").textContent = new Date().getFullYear();

  // the big button gets the file for this device; where there is none (iPhone, iPad) it goes to the steps
  (function(){
    var ua = navigator.userAgent, os = /Android/.test(ua) ? "android" : /Windows/.test(ua) ? "windows-x64" : /Mac/.test(ua) && !/iPhone|iPad/.test(ua) && !(navigator.maxTouchPoints > 1) ? "mac" : /Linux/.test(ua) ? (/aarch64|arm64/.test(ua) ? "linux-arm64" : "linux-x64") : "";
    var a = os && document.querySelector('#dl-all a[data-os="' + os + '"]'); if (!a) return;
    document.querySelectorAll("#dl-all a").forEach(function(x){ x.classList.toggle("mine", x === a); });
    document.getElementById("dl-main").href = a.href;
    document.getElementById("dl-main-os").innerHTML = ' <span class="vi">cho</span><span class="en">for</span> ' + a.textContent.trim();
  })();

  // Android on a computer: a QR to scan with the phone (+ the APK anyway); on an Android phone the link just downloads
  (function(){
    var a = document.querySelector('#dl-all a[data-os="android"]'), dlg = document.getElementById("apkqr");
    if (!a || !dlg || typeof qrSvg !== "function") return;
    function open(e){
      if (/Android/.test(navigator.userAgent)) return;
      var q = qrSvg(a.href, 260); if (!q) return;
      e.preventDefault();
      var box = dlg.querySelector(".big"); box.innerHTML = ""; box.appendChild(q);
      dlg.showModal();
    }
    a.addEventListener("click", open);
    dlg.querySelector(".x").addEventListener("click", function(){ dlg.close(); });
    dlg.addEventListener("click", function(e){ if (e.target === dlg) dlg.close(); });      // the backdrop
  })();

  // before / after: the range input moves the clip (--p) and the bar
  document.querySelectorAll(".ba").forEach(function(f){
    f.querySelector(".ba-in").addEventListener("input", function(){ f.style.setProperty("--p", this.value + "%"); });
  });

  // latest release tag in the pill; the page reads fine if GitHub is unreachable
  fetch("https://api.github.com/repos/HoangTran0410/tv-remote/releases/latest")
    .then(function(r){ return r.ok ? r.json() : null; })
    .then(function(j){ if (j && j.tag_name) document.getElementById("ver").textContent = j.tag_name.replace(/^v?/, "v"); })
    .catch(function(){});

  // hero line: downloads (the census worker sums GitHub's .apk / .zip / .tar.gz, the README badge reads it too) and TVs / phones that used it (census); each cached 6 h, a failed part stays hidden
  (function(){
    var box = document.getElementById("h-stats"), H = 6 * 3600e3, got = {};
    function fmt(n, l){ return String(n).replace(/\B(?=(\d{3})+$)/g, l === "vi" ? "." : ","); }
    function show(id, n, plus){
      if (!(n > 0)) return;
      var el = document.getElementById(id), b = el.querySelectorAll("b"), v = n;
      if (plus) v = n >= 1000 ? Math.floor(n / 100) * 100 : n >= 100 ? Math.floor(n / 10) * 10 : n;
      [].forEach.call(b, function(x){ var l = x.closest(".en") ? "en" : "vi"; x.textContent = fmt(v, l) + (plus && v < n ? "+" : ""); });   // the served page keeps one language's (_worker.js)
      el.hidden = false; got[id] = 1;
      document.getElementById("st-sep").hidden = !(got["st-dl"] && got["st-us"]);
      box.hidden = false; requestAnimationFrame(function(){ box.classList.add("on"); });
    }
    function part(key, id, plus, load){
      var c; try { c = JSON.parse(localStorage.getItem(key)); } catch(e){}
      if (c && c.n > 0 && Date.now() - c.t < H) return show(id, c.n, plus);
      load().then(function(n){
        if (!(n > 0)) return;
        try { localStorage.setItem(key, JSON.stringify({ t: Date.now(), n: n })); } catch(e){}
        show(id, n, plus);
      }).catch(function(){});
    }
    part("hs-dl2", "st-dl", true, function(){
      return fetch("https://tvr-census.99-hoangtran.workers.dev/v1/downloads").then(function(r){ if (!r.ok) throw 0; return r.json(); }).then(function(d){ return d && d.downloads; });
    });
    part("hs-us", "st-us", false, function(){
      return fetch("https://tvr-census.99-hoangtran.workers.dev/v1/days").then(function(r){ if (!r.ok) throw 0; return r.json(); }).then(function(d){ return d && d.installs; });
    });
  })();
})();

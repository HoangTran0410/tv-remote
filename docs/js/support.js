// support.html#<code>.<key>: the thread of a log a TV sent (TV Setup shows this link as a QR). The key stays in the fragment,
// which the browser never sends to a server; it goes only in the body to the census worker (dev/census/src/support.js).
(function () {
  var local = new URLSearchParams(location.search).get("api");       // a local `wrangler dev` while testing, nothing else
  var API = (local && /^http:\/\/127\.0\.0\.1:\d+$/.test(local) ? local : "https://tvr-census.99-hoangtran.workers.dev") + "/v1/support/";
  var vi = document.documentElement.dataset.lang !== "en";
  var T = function (v, e) { return vi ? v : e; };
  var m = /^#?(\d{8})\.([0-9a-f]{32})$/.exec(location.hash);
  var thread = document.querySelector("[data-thread]"), form = document.querySelector("[data-say]");
  var about = document.querySelector("[data-about]"), err = document.querySelector("[data-err]");
  var box = document.getElementById("sup-text"), last = 0;

  function note(t) { thread.textContent = ""; var p = document.createElement("p"); p.className = "sup-empty"; p.textContent = t; thread.appendChild(p); }
  if (!m) { note(T("Liên kết này không đủ. Quét lại mã QR trên TV (TV Setup → Gửi cho hỗ trợ).", "This link is incomplete. Scan the QR code on the TV again (TV Setup → Send to support).")); return; }
  var who = { code: m[1], key: m[2] };

  function call(path, extra) {
    // text/plain: a simple request, no CORS preflight (the worker reads the body as JSON whatever its type)
    return fetch(API + path, { method: "POST", headers: { "content-type": "text/plain" }, body: JSON.stringify(Object.assign({}, who, extra || {})) })
      .then(function (r) { return r.json().catch(function () { return {}; }).then(function (j) { j._status = r.status; return j; }); });
  }
  function when(ms) { try { return new Date(ms).toLocaleString(vi ? "vi-VN" : "en-GB", { day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" }); } catch (e) { return ""; } }
  function add(msg) {
    var e = thread.querySelector(".sup-empty"); if (e) e.remove();
    var d = document.createElement("div");
    d.className = "sup-msg " + (msg.who === "tv" ? "tv" : "us");
    d.textContent = msg.text;                                          // text, never HTML: anyone could have written it
    var s = document.createElement("small");
    s.textContent = (msg.who === "tv" ? T("Bạn", "You") : T("Hỗ trợ TV Remote", "TV Remote support")) + " · " + when(msg.at);
    d.appendChild(s); thread.appendChild(d);
    last = Math.max(last, msg.id);
  }
  function load(first) {
    return call("thread", { after: last }).then(function (j) {
      if (j._status === 403) { form.hidden = true; note(T("Cuộc trò chuyện này đã hết hạn (14 ngày) hoặc liên kết sai. Gửi log mới từ TV Setup.", "This talk has expired (14 days) or the link is wrong. Send a new log from TV Setup.")); return; }
      if (!j.msgs) throw 0;
      if (first) {
        thread.textContent = "";
        about.textContent = T("Log từ ", "Log from ") + (j.model || "TV") + " · " + when(j.made);
        form.hidden = false;
      }
      j.msgs.forEach(add);
      if (!last) note(T("Chưa có trả lời. Hỗ trợ đã nhận log của bạn; bạn có thể kể thêm ở dưới.", "No answer yet. Support has your log; you can tell more below."));
    }).catch(function () { if (first) note(T("Không tải được. Kiểm tra mạng rồi tải lại trang.", "Could not load. Check the connection and reload the page.")); });
  }
  form.addEventListener("submit", function (e) {
    e.preventDefault();
    var t = box.value.trim(); if (!t) return;
    var b = form.querySelector("button"); b.disabled = true; err.textContent = "";
    call("say", { text: t }).then(function (j) {
      if (j._status === 429) throw T("Hôm nay bạn đã gửi đủ tin, mai gửi tiếp nhé.", "That is enough messages for today, try again tomorrow.");
      if (j._status === 403) throw T("Cuộc trò chuyện đã hết hạn.", "This talk has expired.");
      if (!j.id) throw T("Chưa gửi được, thử lại.", "Not sent, try again.");
      box.value = ""; return load(false);
    }).catch(function (x) { err.textContent = typeof x === "string" ? x : T("Chưa gửi được, kiểm tra mạng.", "Not sent, check the connection."); })
      .then(function () { b.disabled = false; });
  });
  load(true);
  setInterval(function () { if (!document.hidden) load(false); }, 60000);   // a reply while the page is open
})();

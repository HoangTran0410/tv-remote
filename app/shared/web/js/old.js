// A browser too old for the page's scripts (Chrome before 80: ?. ?? catch {} {...a}) runs none of them and shows empty tabs.
// Seen on an Android 8 phone and old TVs whose "Android System WebView" was never updated. Written in ES5 on purpose: it
// must run where the rest cannot, and says in both languages (i18n.js cannot run either) what to update.
// Chrome 80-85 pass that test but lack replaceChildren (86), which every tab uses: an old TV WebView died on it (0f0bf561).
[Element, DocumentFragment, Document].forEach(function (C) {
  if (C.prototype.replaceChildren) return;
  C.prototype.replaceChildren = function () { while (this.lastChild) this.removeChild(this.lastChild); this.append.apply(this, arguments); };
});
// Chrome before 99 has no canvas roundRect (draw.js, home_walls.js): a Redmi 5A's WebView threw on it (census b1044804). One radius.
if (window.CanvasRenderingContext2D && !CanvasRenderingContext2D.prototype.roundRect) {
  CanvasRenderingContext2D.prototype.roundRect = function (x, y, w, h, r) {
    r = Math.max(0, Math.min(+r || 0, Math.abs(w) / 2, Math.abs(h) / 2));
    this.moveTo(x + r, y);
    this.arcTo(x + w, y, x + w, y + h, r); this.arcTo(x + w, y + h, x, y + h, r);
    this.arcTo(x, y + h, x, y, r); this.arcTo(x, y, x + w, y, r);
    this.closePath();
  };
}
// Safari 14 to 15.3 pass the syntax test but have no <dialog> showModal: every question (ask(), the menus) would throw.
(function () {
  try { new Function('var a = { ...{} }; try { a?.b ?? 0 } catch { }'); if (window.HTMLDialogElement && HTMLDialogElement.prototype.showModal) return; } catch (e) { }
  var vi = /[?&]lang=vi(&|$)/.test(location.search) || (!/[?&]lang=/.test(location.search) && /^vi/i.test(navigator.language || ''));
  var webview = /; wv\)/.test(navigator.userAgent);                // an app's web view: updated as "Android System WebView"
  var store = 'https://play.google.com/store/apps/details?id=com.google.android.webview';
  window.OLD_BROWSER = true;
  document.addEventListener('DOMContentLoaded', function () {
    if (!document.documentElement.getAttribute('data-theme')) document.documentElement.setAttribute('data-theme', /[?&]theme=dark(&|$)/.test(location.search) ? 'dark' : 'light');
    document.documentElement.className += ' tabbed';
    var main = document.createElement('main');
    main.className = 'wide';
    var card = document.createElement('section');
    card.className = 'card old';
    var h = document.createElement('h2');
    h.textContent = vi ? 'Cần cập nhật trình duyệt' : 'The browser needs an update';
    var p = document.createElement('p');
    p.className = 'note';
    p.textContent = webview
      ? (vi ? 'Trang này cần “Android System WebView” mới hơn. Cập nhật nó trên Play Store, rồi mở lại.'
            : 'This page needs a newer “Android System WebView”. Update it on the Play Store, then open this again.')
      : (vi ? 'Trình duyệt này quá cũ để mở trang (cần Chrome 80, Safari 15.4 trở lên). Cập nhật trình duyệt, rồi mở lại.'
            : 'This browser is too old for this page (it needs Chrome 80, Safari 15.4 or newer). Update it, then open this again.');
    card.appendChild(h);
    card.appendChild(p);
    if (webview) {
      var acts = document.createElement('div');
      acts.className = 'actions';
      var b = document.createElement('button');
      b.className = 'primary';
      b.textContent = vi ? 'Mở Play Store' : 'Open the Play Store';
      b.onclick = function () { location.href = store; };          // the app sends links that leave the page to the store / browser
      acts.appendChild(b);
      card.appendChild(acts);
    }
    main.appendChild(card);
    document.body.innerHTML = '';
    document.body.appendChild(main);
  });
})();

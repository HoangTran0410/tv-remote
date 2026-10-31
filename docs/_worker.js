// Cloudflare Pages: every request is the static files', with these exceptions.
// /dl/<file>: the latest release's file on GitHub, behind a link short enough to read out, type and fit a small QR (the apps
// show these; their own updates still go to GitHub, checked by SHA256SUMS). Only the release's names: no open redirect.
// One address per language: the pages are written once with Vietnamese + English spans; /<page> is served in Vietnamese only and
// /en/<page> in English only (lang, title, description, canonical, hreflang, the other language's spans removed). With both
// languages in one page under one address a search engine saw a mixed page with a Vietnamese title, and ?lang=en pointed its
// canonical back at the Vietnamese one: the English site was not in Google at all. Old ?lang= links are sent on (301).
// A guide topic, /guide?t=<topic>, is a page of its own: its name and first lines are the title and description (a chat app's
// link preview never sees a #topic, and a search engine indexes it under its own canonical). guide.js writes ?t= into the address.
// /sitemap.xml is written here from the pages and the guide's topics, so a new topic is listed without a hand edit.
const ORIGIN = "https://tvremote.pages.dev";
const FILES = new Set(["tv-remote.apk", "tv-setup.apk", "tv-home.apk", "tv-channels.apk", "tv-games.apk",
  "TV-Remote-mac.zip", "TV-Remote-windows-x64.zip", "TV-Remote-linux-x64.tar.gz", "TV-Remote-linux-arm64.tar.gz"]);
const PAGES = { "/": true, "/guide": true, "/tvs": true, "/apps": true, "/themes": true, "/log": false };   // true: in the sitemap (log is noindex)
// Runs at the end of <head>: the address's language wins over what the page's own first script picked; a person whose choice
// (the VI / EN buttons, nav.js, "langPick") or, on a Vietnamese page reached from elsewhere, whose browser speaks another language
// is sent to the other address. Never a crawler: it must see each address as it is.
const BOOT = `<script>(function(){var r=document.documentElement,f=r.dataset.fixed;r.dataset.lang=f;r.lang=f;
var vi=location.pathname.replace(/^\\/en(?=\\/)/,"");window.langURL=function(l){return(l==="en"?"/en":"")+vi+location.search+location.hash};
var p;try{p=localStorage.getItem("langPick")}catch(e){}
var w=p==="vi"||p==="en"?p:f==="vi"&&!/^vi\\b/i.test(navigator.language||"")&&document.referrer.indexOf(location.origin)!==0?"en":f;
if(w!==f&&!/bot|crawl|spider|slurp|preview|lighthouse|inspect|headless/i.test(navigator.userAgent))location.replace(langURL(w))})()</script>`;

export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    const dl = /^\/dl\/([A-Za-z0-9.-]+)$/.exec(url.pathname);
    if (dl && FILES.has(dl[1])) return Response.redirect("https://github.com/HoangTran0410/tv-remote/releases/latest/download/" + dl[1], 302);
    if (url.pathname === "/en") return Response.redirect(url.origin + "/en/" + url.search, 301);   // relative links need the slash
    const en = url.pathname.startsWith("/en/"), lang = en ? "en" : "vi";
    const path = en ? url.pathname.slice(3) : url.pathname;
    if (path === "/sitemap.xml" && !en) return sitemap(env, url);
    const page = path.replace(/\.html$/, "").replace(/\/index$/, "/");
    const ask = new URL(url); ask.pathname = path;
    if (PAGES[page] !== undefined) {
      const want = url.searchParams.get("lang");
      if (want === "vi" || want === "en") {                           // an old ?lang= link: its own address now
        const to = new URL(url); to.searchParams.delete("lang"); to.pathname = (want === "en" ? "/en" : "") + page;
        return Response.redirect(to.toString(), 301);
      }
      const res = await env.ASSETS.fetch(new Request(ask, req));
      if (!res.ok || !(res.headers.get("content-type") || "").includes("text/html")) return en ? prefix(res) : res;
      return render(res, env, url, lang, page);
    }
    if (!en) return env.ASSETS.fetch(req);
    const res = await env.ASSETS.fetch(new Request(ask, req));     // /en/css/…, /en/img/…: a page's relative links, the same files
    if ((res.headers.get("content-type") || "").includes("text/html")) {   // a page with no English address (demo/, tour): the one it has
      const to = new URL(ask); return Response.redirect(to.toString(), 302);
    }
    return prefix(res);
  },
};

// a redirect from the files (guide.html -> /guide) keeps the /en/ it came under
function prefix(res) {
  const loc = res.headers.get("location");
  if (!loc || !loc.startsWith("/") || loc.startsWith("/en/")) return res;
  const out = new Response(res.body, res); out.headers.set("location", "/en" + loc); return out;
}

async function render(res, env, url, lang, page) {
  const other = lang === "en" ? "vi" : "en";
  const id = page === "/guide" ? url.searchParams.get("t") : null;
  const topic = id && /^[a-z0-9-]{1,60}$/.test(id) ? await find(await env.ASSETS.fetch(new URL("/guide", url)), id) : null;
  const q = topic && topic.vi ? "?t=" + id : "";
  const at = (l) => ORIGIN + (l === "en" ? "/en" : "") + page + q;
  let title = "", desc = "";
  if (q) { title = topic[lang] + " · TV Remote"; desc = (lang === "en" ? topic.pen : topic.pvi).slice(0, 300); }
  const set = (v) => ({ element(e) { if (v()) e.setAttribute("content", v()); } });
  return new HTMLRewriter()
    .on("html", { element(e) { e.setAttribute("lang", lang); e.setAttribute("data-lang", lang); e.setAttribute("data-fixed", lang); } })
    .on("." + other, { element(e) { e.remove(); } })
    .on("title", { element(e) {
      if (!title) title = lang === "en" ? e.getAttribute("data-en") || "" : "";
      e.removeAttribute("data-en"); if (title) e.setInnerContent(title);
    }, text(t) { if (!title && lang === "vi") title += t.text; } })
    .on('meta[name="description"]', { element(e) {
      if (!desc) desc = lang === "en" ? e.getAttribute("data-en") || "" : e.getAttribute("content") || "";
      e.removeAttribute("data-en"); e.setAttribute("content", desc);
    } })
    .on('meta[property="og:title"], meta[name="twitter:title"]', set(() => title.replace(/ · TV Remote$/, "")))
    .on('meta[property="og:description"], meta[name="twitter:description"]', set(() => desc))
    .on('meta[property="og:image"]', { element(e) { e.setAttribute("content", (e.getAttribute("content") || "").replace(/og-(vi|en)\./, "og-" + lang + ".")); } })
    .on('meta[property="og:url"]', { element(e) { e.setAttribute("content", at(lang)); } })
    .on('link[rel="canonical"]', { element(e) { e.setAttribute("href", at(lang)); } })
    .on('link[rel="alternate"][hreflang]', { element(e) { const h = e.getAttribute("hreflang"); e.setAttribute("href", at(h === "en" ? "en" : "vi")); } })
    .on("a[data-t]", { element(e) { e.setAttribute("href", "guide?t=" + e.getAttribute("data-t")); } })   // the guide's menu: real addresses to follow
    .on("head", { element(e) { e.append(BOOT, { html: true }); } })
    .transform(res);
}

// The topic's heading (vi, en) and its first paragraph that is not a tip (vi, en), read from the page itself: nothing to keep in step
async function find(res, id) {
  const t = { vi: "", en: "", pvi: "", pen: "" };
  let inTopic = false, paras = 0;
  const grab = (key, only) => ({ text(x) { if (inTopic && (!only || only())) t[key] += x.text; } });
  await new HTMLRewriter()
    .on("article.topic", { element(e) {
      inTopic = e.getAttribute("id") === id; paras = 0;
    } })
    .on("article.topic > p:not(.gnote)", { element() { if (inTopic) paras++; } })
    .on("article.topic h3 span.vi", grab("vi"))
    .on("article.topic h3 span.en", grab("en"))
    .on("article.topic > p:not(.gnote) span.vi", grab("pvi", () => paras === 1))
    .on("article.topic > p:not(.gnote) span.en", grab("pen", () => paras === 1))
    .transform(res).arrayBuffer();
  for (const k in t) t[k] = t[k].replace(/\s+/g, " ").trim();
  return t;
}

async function sitemap(env, url) {
  const ids = [];
  await new HTMLRewriter().on("article.topic", { element(e) { ids.push(e.getAttribute("id")); } })
    .transform(await env.ASSETS.fetch(new URL("/guide", url))).arrayBuffer();
  const pages = Object.keys(PAGES).filter((p) => PAGES[p]).concat(ids.map((i) => "/guide?t=" + i));
  const at = (p, l) => ORIGIN + (l === "en" ? "/en" : "") + p.replace(/&/g, "&amp;");
  const alt = (p) => ["vi", "en"].map((l) => `<xhtml:link rel="alternate" hreflang="${l}" href="${at(p, l)}"/>`).join("") +
    `<xhtml:link rel="alternate" hreflang="x-default" href="${at(p, "vi")}"/>`;
  const body = '<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9" xmlns:xhtml="http://www.w3.org/1999/xhtml">\n' +
    pages.flatMap((p) => ["vi", "en"].map((l) => `<url><loc>${at(p, l)}</loc>${alt(p)}</url>`)).join("\n") + "\n</urlset>\n";
  return new Response(body, { headers: { "content-type": "application/xml; charset=utf-8", "cache-control": "public, max-age=3600" } });
}

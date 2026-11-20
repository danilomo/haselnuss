// Haselnuss service worker (doc-12, "The app shell (PWA)", "Offline"; TASK-277).
// app.js registers it with the site's top as its scope. No framework, no build
// step: the build only writes the app's version in below.
//
// What it keeps, and how:
//
//   app files (APP_FILES)        precached at install into this version's own
//                                cache; cache-first. A new build has a new
//                                VERSION, so a new worker with a new cache.
//   books/: pages and images     network first, with a short timeout (BOOK_WAIT);
//                                every successful answer is kept (by its URL
//                                without the query); the kept copy when the
//                                network fails, is slow or answers 5xx; an "offline"
//                                page for a book this device never opened.
//   MathJax from the CDN         cache-first once loaded, so books with maths read
//                                offline.
//   api/...                      never touched: the browser's network, always.
//   catalog/, backlinks.json,    not touched here either: app.js fetches them
//   previews.json                network first and keeps its own copies (the
//                                "haselnuss-catalog" and "haselnuss-data" caches),
//                                since the home screen searches the catalog pages
//                                it has kept when offline. One writer per cache.
//
// Updates: a new worker installs in the background and then waits. There is no
// skipWaiting(): it takes over only once no page of the site is open -- the next
// launch -- so a reader never has the app change under them mid-page (doc-12,
// "Updates"). Its activation drops the previous version's app cache.
"use strict";

// The app's version, written in by the build (haselnuss.site/app-file-bytes):
// a digest of the app files, so it changes exactly when one of them does and
// a device installs the new app (doc-12, "Updates"; TASK-272).
const VERSION = "__HASELNUSS_APP_VERSION__";

const SCOPE = self.registration.scope;                // the site's top, ending in "/"
const SCOPE_PATH = new URL(SCOPE).pathname;
const APP_PREFIX = "haselnuss-app " + SCOPE + " ";    // one site's app caches, any version
const APP_CACHE = APP_PREFIX + VERSION;
const BOOKS_CACHE = "haselnuss-books";
const MATHJAX_CACHE = "haselnuss-mathjax";
// The release html emits and app.js loads (they pin the same one); a later
// release is a different prefix, so it is never served from an older copy.
const MATHJAX_PREFIX = "https://cdn.jsdelivr.net/npm/mathjax@3.2.2/";
const BOOK_WAIT = 4000;   // ms before a kept copy of a book page is shown instead

// Paths below the scope; "" is the home screen at the site's top.
const APP_FILES = ["", "index.html", "app.css", "app.js", "manifest.webmanifest",
                   "icon-192.png", "icon-512.png", "icon-maskable-512.png", "apple-touch-icon.png"];
const APP_SET = new Set(APP_FILES);

self.addEventListener("install", function (event) {
  event.waitUntil(caches.open(APP_CACHE).then(function (cache) {
    // past the HTTP cache: the files of this build, not a copy of the last one's
    return cache.addAll(APP_FILES.map(function (p) { return new Request(SCOPE + p, { cache: "reload" }); }));
  }));
});

self.addEventListener("activate", function (event) {
  event.waitUntil(caches.keys().then(function (keys) {
    return Promise.all(keys.filter(function (k) {
      return k.indexOf(APP_PREFIX) === 0 && k !== APP_CACHE;
    }).map(function (k) { return caches.delete(k); }));
  }).then(function () {
    // pages opened before the first worker existed; an update activates only
    // once no page is open, so this never takes a page from an older worker
    return self.clients.claim();
  }));
});

self.addEventListener("fetch", function (event) {
  const req = event.request;
  if (req.method !== "GET") return;
  if (req.url.indexOf(MATHJAX_PREFIX) === 0) { event.respondWith(mathJax(req)); return; }
  const url = new URL(req.url);
  if (url.origin !== self.location.origin || url.pathname.indexOf(SCOPE_PATH) !== 0) return;
  const rel = url.pathname.slice(SCOPE_PATH.length);
  if (rel.indexOf("api/") === 0) return;                       // never cached
  if (APP_SET.has(rel)) { event.respondWith(appFile(req, rel)); return; }
  if (rel.indexOf("books/") === 0 && !/(^|\/)(backlinks|previews)\.json$/.test(rel)) {
    event.respondWith(bookFile(event));
  }
  // anything else (the catalog, a book's data files) is app.js's or the network's
});

// The page or another client asks for this worker's version, or (on the first
// load, before this worker controlled it) has it keep what that page loaded.
self.addEventListener("message", function (event) {
  const data = event.data || {};
  if (data.type === "version" && event.ports[0]) event.ports[0].postMessage(VERSION);
  if (data.type === "keep" && Array.isArray(data.urls)) event.waitUntil(keep(data.urls.slice(0, 200)));
});

function appFile(req, rel) {
  return caches.open(APP_CACHE).then(function (cache) {
    return cache.match(SCOPE + rel);
  }).then(function (hit) {
    return hit || fetch(req);
  });
}

function storable(r) {
  return r && r.ok && r.type === "basic" && !r.redirected;
}

// The key a book file is kept under: its URL without query or fragment, so
// `?anything` neither makes a new copy nor misses the one kept.
function bookKey(href) {
  const url = new URL(href);
  url.search = "";
  url.hash = "";
  return url.href;
}

// Network first; the copy kept on the device when the network fails, answers
// with a server error (a proxy's 502 while Haselnuss is down), or has not
// answered in BOOK_WAIT; a page saying so for a book never opened here.
function bookFile(event) {
  const req = event.request;
  const key = bookKey(req.url);
  const network = fetch(req).then(function (r) {
    if (storable(r)) {
      const copy = r.clone();
      return caches.open(BOOKS_CACHE).then(function (c) { return c.put(key, copy); })
        .then(function () { return r; }, function () { return r; });
    }
    if (r.status === 404 || r.status === 410) {   // a book removed from the site
      return caches.open(BOOKS_CACHE).then(function (c) { return c.delete(key); })
        .then(function () { return r; }, function () { return r; });
    }
    return r;
  });
  event.waitUntil(network.catch(function () {}));   // the copy is kept even when a kept one was shown
  const late = new Promise(function (resolve) { setTimeout(resolve, BOOK_WAIT, null); });
  const kept = function () {
    return caches.open(BOOKS_CACHE).then(function (c) { return c.match(key); }, function () { return null; });
  };
  return Promise.race([network.catch(function () { return null; }), late]).then(function (r) {
    if (r && r.status < 500) return r;
    return kept().then(function (hit) {
      if (hit) return hit;
      if (r) return r;   // a server error, and nothing kept: show it as it is
      // nothing kept: wait for the network after all, and say so when it fails
      return network.catch(function () { return offline(req); });
    });
  });
}

function offline(req) {
  if (req.mode !== "navigate") return Response.error();
  const home = SCOPE;
  const page = "<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\"/>" +
    "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, viewport-fit=cover\"/>" +
    "<meta name=\"theme-color\" content=\"#f4ecd8\"/><title>Offline - Haselnuss</title>" +
    "<style>html{background:#f4ecd8;color:#3b3226;font:17px/1.5 Georgia,serif}" +
    "main{max-width:32em;margin:18vh auto 0;padding:0 24px}a{color:#8a5a2b}</style></head>" +
    "<body><main id=\"hz-offline\"><h1>This book is not on this device yet</h1>" +
    "<p>You are offline, and this book has not been opened on this device before, " +
    "so there is no copy of it here. Books you have opened can still be read.</p>" +
    "<p>Connect to the internet and open it again to read it.</p>" +
    "<p><a href=\"" + home + "\">&lsaquo; Library</a></p></main></body></html>";
  return new Response(page, {
    status: 503,
    statusText: "Offline",
    headers: { "Content-Type": "text/html; charset=utf-8", "Cache-Control": "no-store" }
  });
}

// MathJax: a kept copy first; otherwise the CDN, asked with CORS (it allows any
// origin), so the copy is a readable answer rather than an opaque one, which
// the browser would count against the site's storage many times over.
function mathJax(req) {
  return caches.open(MATHJAX_CACHE).then(function (cache) {
    return cache.match(req.url).then(function (hit) {
      if (hit) return hit;
      return fetch(new Request(req.url, { mode: "cors", credentials: "omit" })).then(function (r) {
        if (r.ok) return cache.put(req.url, r.clone()).then(function () { return r; }, function () { return r; });
        return r;
      }, function () { return fetch(req); });
    });
  });
}

// What a page loaded before this worker controlled it (the first book opened
// after installing): kept as if it had come through here. Only what this
// worker would itself keep -- book files and MathJax -- and only what is not
// kept yet.
function keep(urls) {
  return Promise.all(urls.map(function (u) {
    let url;
    try { url = new URL(String(u)); } catch (e) { return null; }
    url.hash = "";
    const href = url.href;
    if (href.indexOf(MATHJAX_PREFIX) === 0) {
      return mathJax(new Request(href)).catch(function () {});
    }
    if (url.origin !== self.location.origin || url.pathname.indexOf(SCOPE_PATH) !== 0) return null;
    const rel = url.pathname.slice(SCOPE_PATH.length);
    if (rel.indexOf("books/") !== 0 || /(^|\/)(backlinks|previews)\.json$/.test(rel)) return null;
    const key = bookKey(href);
    return caches.open(BOOKS_CACHE).then(function (c) {
      return c.match(key).then(function (hit) {
        if (hit) return null;
        return fetch(href).then(function (r) { if (storable(r)) return c.put(key, r); });
      });
    }).catch(function () {});
  }));
}

// Haselnuss reader app. Every book page loads this file, deferred, and so does
// the home screen (index.html): the home screen's code runs only where the
// page is the home screen (body#home-app, TASK-274); the reading screen's
// controls (TASK-275) and position saving (TASK-276) get their own section
// below, guarded the same way; the queue of positions waiting for the server
// is shared by both. No framework, no build step.
//
// Everything that comes from the library -- reader names, titles, authors,
// library names -- is put on the page with textContent or as an attribute
// value, never as HTML.
"use strict";
(function () {
  // Where the site's top is: app.js sits there. A deferred classic script
  // still has document.currentScript while it runs.
  var appScript = document.currentScript;
  var ROOT = new URL(".", appScript && appScript.src ? appScript.src : document.baseURI);

  var READER_KEY = "haselnuss.reader";   // localStorage: {id, name} of who reads on this device

  // ===== shared: readers, book names, the queue of positions to send (TASK-276) ========

  function storedReader() {
    try {
      var r = JSON.parse(localStorage.getItem(READER_KEY) || "null");
      return r && typeof r.id === "string" && typeof r.name === "string" ? r : null;
    } catch (e) { return null; }
  }

  // Books named by id: the server answers from the catalog it holds in memory
  // (GET api/books?id=...), so nothing walks catalog pages to find a title.
  // The URLs of the requests for `ids`, sorted, a bounded number of ids each, so
  // the same ids always ask the same URLs (a copy kept offline is found again).
  var BOOKS_PER_REQUEST = 50;   // the server takes up to 100
  function booksUrls(ids) {
    var sorted = Array.from(new Set(ids.map(String))).sort();
    var urls = [];
    for (var i = 0; i < sorted.length; i += BOOKS_PER_REQUEST) {
      urls.push(new URL("api/books?" + sorted.slice(i, i + BOOKS_PER_REQUEST).map(function (id) {
        return "id=" + encodeURIComponent(id);
      }).join("&"), ROOT).href);
    }
    return urls;
  }

  // ---- reading positions: kept on the device until the server has them ----------------
  //
  // One record per reader and book in IndexedDB, {key, reader, book, position,
  // at, pending}: `pending` until a PUT has been answered, the device time `at`
  // it was taken at. A save writes the record and then sends every pending one;
  // so does a page starting, the connection returning ("online") and, while the
  // server cannot be reached, a retry every RETRY_MS. An answer stores the
  // position the server kept (the newer savedAt wins there); a refusal (4xx)
  // drops the record, since sending it again cannot help.
  //
  // savedAt is the device's time moved by how far its clock is from the
  // server's (the Date header of any API answer, when more than SKEW_MIN apart),
  // worked out when the record is sent: a device whose clock is a day ahead is
  // neither refused by the server's one-day bound nor wins over every later save.
  var SKEW_KEY = "haselnuss.clockSkew";
  var DEVICE_KEY = "haselnuss.device";
  var POS_DB = "haselnuss-positions", POS_STORE = "positions";
  var SKEW_MIN = 60000;
  var RETRY_MS = 30000;

  function clockSkew() {
    var n;
    try { n = Number(localStorage.getItem(SKEW_KEY)); } catch (e) { n = 0; }
    return isFinite(n) ? n : 0;
  }
  // Note the server's clock from an answer; true when the skew used changed.
  var clockMeasured = false;   // on this page: no save is sent before it is
  function noteServerClock(response) {
    var server = Date.parse(response.headers.get("Date") || "");
    if (isNaN(server)) return false;
    clockMeasured = true;
    var skew = server - Date.now();
    var next = Math.abs(skew) > SKEW_MIN ? skew : 0;
    if (Math.abs(next - clockSkew()) <= SKEW_MIN / 2) return false;
    try { localStorage.setItem(SKEW_KEY, String(next)); } catch (e) { return false; }
    return true;
  }
  function serverTime(deviceMs) { return new Date(deviceMs + clockSkew()).toISOString(); }
  function deviceName() {
    var d = null;
    try { d = localStorage.getItem(DEVICE_KEY); } catch (e) { /* private mode */ }
    if (d) return d;
    var ua = navigator.userAgent || "";
    var kind = (/iPad|iPhone|Android|Macintosh|Windows|Linux|CrOS/.exec(ua) || ["Device"])[0];
    d = kind + " " + Math.random().toString(36).slice(2, 8);
    try { localStorage.setItem(DEVICE_KEY, d); } catch (e) { /* a new name per page */ }
    return d;
  }
  function positionUrl(reader, book) {
    return new URL("api/readers/" + encodeURIComponent(reader) + "/positions/" + encodeURIComponent(book), ROOT).href;
  }
  function posKey(reader, book) { return reader + "\n" + book; }

  // The database, or null when there is none. An open that does not answer in
  // OPEN_WAIT (one can wait on another page's, e.g. a page kept in the
  // back-forward cache while it was creating the store) counts as none for now
  // and is tried again on the next call. The connection is closed when the page
  // is hidden for good (pagehide), so a cached page holds nothing up.
  var OPEN_WAIT = 3000;
  var dbPromise = null, openDb = null;
  function positionsDb() {
    if (!dbPromise) {
      var p = new Promise(function (resolve) {
        var req, done = false;
        function finish(db) {
          if (done) { if (db) db.close(); return; }
          done = true;
          if (!db && dbPromise === p) dbPromise = null;
          if (db) {
            openDb = db;
            db.onversionchange = function () { closeDb(); };
          }
          resolve(db);
        }
        try { req = indexedDB.open(POS_DB, 1); } catch (e) { finish(null); return; }
        req.onupgradeneeded = function () { req.result.createObjectStore(POS_STORE, { keyPath: "key" }); };
        req.onsuccess = function () { finish(req.result); };
        req.onerror = function () { finish(null); };
        req.onblocked = function () { finish(null); };
        setTimeout(function () { finish(null); }, OPEN_WAIT);
      });
      dbPromise = p;
    }
    return dbPromise;
  }
  // Transactions already made still finish; the next use opens it again.
  function closeDb() {
    if (openDb) openDb.close();
    openDb = null;
    dbPromise = null;
  }
  // `op(store, done)` in one transaction; resolves with what it passed to done,
  // or null when there is no IndexedDB or the transaction fails.
  function withStore(mode, op) {
    return positionsDb().then(function (db) {
      if (!db) return null;
      return new Promise(function (resolve) {
        var t, out = null;
        try { t = db.transaction(POS_STORE, mode); } catch (e) { resolve(null); return; }
        t.oncomplete = function () { resolve(out); };
        t.onerror = function () { resolve(null); };
        t.onabort = function () { resolve(null); };
        try { op(t.objectStore(POS_STORE), function (v) { out = v; }); } catch (e) { resolve(null); }
      });
    });
  }
  function localRecord(reader, book) {
    return withStore("readonly", function (s, done) {
      var req = s.get(posKey(reader, book));
      req.onsuccess = function () { done(req.result || null); };
    });
  }
  // The record's position with its savedAt (a pending one's worked out now).
  function recordPosition(rec) {
    if (!rec || !rec.position) return null;
    if (!rec.pending) return rec.position;
    var p = Object.assign({}, rec.position);
    p.savedAt = serverTime(rec.at);
    return p;
  }
  // The record sent as `rec` has been answered: keep `winner` (the position the
  // server holds) or, with none, forget it -- unless a newer save replaced it.
  function settle(rec, winner) {
    return withStore("readwrite", function (s) {
      var req = s.get(rec.key);
      req.onsuccess = function () {
        var cur = req.result;
        if (!cur || !cur.pending || cur.at !== rec.at) return;
        if (winner) s.put({ key: rec.key, reader: rec.reader, book: rec.book, position: winner, pending: false });
        else s.delete(rec.key);
      };
    });
  }
  // The server's clock, measured once per page before the first save is sent
  // (a HEAD of a position), so a device clock that is fast by less than the
  // server's one-day bound cannot date a save ahead of every later one.
  // Rejects when the server cannot be reached.
  var measuring = null;
  function measureClock(reader, book) {
    if (clockMeasured) return Promise.resolve();
    if (!measuring) {
      measuring = fetch(positionUrl(reader, book), { method: "HEAD", keepalive: true }).then(function (r) {
        measuring = null;
        noteServerClock(r);
      }, function (err) { measuring = null; throw err; });
    }
    return measuring;
  }
  // PUT one record: "sent", "dropped" (refused), "failed" (the server failed:
  // try this one later, go on with the others) or "offline" (no connection).
  function sendRecord(rec, retried) {
    return measureClock(rec.reader, rec.book).then(function () {
      var body = Object.assign({}, rec.position, { savedAt: serverTime(rec.at), device: deviceName() });
      return fetch(positionUrl(rec.reader, rec.book), {
        method: "PUT", headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body), keepalive: true
      });
    }).then(function (r) {
      var moved = noteServerClock(r);
      if (r.ok) {
        return r.json().then(function (answer) {
          return settle(rec, answer && answer.position && typeof answer.position === "object" ? answer.position : null);
        }, function () { return settle(rec, null); }).then(function () { return "sent"; });
      }
      // a clock found to be off: the same save, dated by the server's clock, once
      if (r.status === 400 && moved && !retried) return sendRecord(rec, true);
      if (r.status >= 400 && r.status < 500 && r.status !== 408 && r.status !== 429) {
        return settle(rec, null).then(function () { return "dropped"; });
      }
      return "failed";
    }, function () { return "offline"; });
  }
  // A record the server failed on waits before it is tried again: RETRY_MS,
  // doubling each time up to RETRY_MAX (for this page's life).
  var RETRY_MAX = 30 * 60000;
  var backoff = new Map();   // key -> {at, until, wait}
  function retryLater(delay) {   // the earliest retry asked for wins
    var due = Date.now() + delay;
    if (retryTimer && retryDue <= due) return;
    clearTimeout(retryTimer);
    retryDue = due;
    retryTimer = setTimeout(function () { retryTimer = null; flushPositions(); }, delay);
  }
  var flushing = null, flushAgain = false, retryTimer = null, retryDue = 0;
  // Send every pending record, one at a time; resolves with how many were sent.
  function flushPositions() {
    if (flushing) { flushAgain = true; return flushing; }
    flushing = withStore("readonly", function (s, done) {
      var req = s.getAll();
      req.onsuccess = function () { done(req.result || []); };
    }).then(function (all) {
      var now = Date.now();
      var pending = (all || []).filter(function (r) { return r.pending; });
      var sent = 0;
      function next(i) {
        if (i >= pending.length) return sent;
        var rec = pending[i];
        var b = backoff.get(rec.key);
        if (b && b.at === rec.at && now < b.until) {   // the same save, failed lately
          retryLater(b.until - now);
          return next(i + 1);
        }
        return sendRecord(rec, false).then(function (outcome) {
          if (outcome === "offline") {   // no connection: nothing else gets through either
            retryLater(RETRY_MS);
            return sent;
          }
          if (outcome === "failed") {
            var wait = b && b.at === rec.at ? Math.min(RETRY_MAX, b.wait * 2) : RETRY_MS;
            backoff.set(rec.key, { at: rec.at, until: Date.now() + wait, wait: wait });
            retryLater(wait);
          } else backoff.delete(rec.key);
          if (outcome === "sent") sent++;
          return next(i + 1);
        });
      }
      return next(0);
    }).then(function (n) { return n; }, function () { return 0; }).then(function (n) {
      flushing = null;
      if (flushAgain) {
        flushAgain = false;
        return flushPositions().then(function (m) { return n + m; });
      }
      return n;
    });
    return flushing;
  }
  // Keep `position` (taken now) for `reader` and `book`, and send it. `now`:
  // the page is going away, so the PUT leaves at once beside the write.
  function queuePosition(reader, book, position, now) {
    var rec = { key: posKey(reader, book), reader: reader, book: book, position: position,
                at: Date.now(), pending: true };
    var written = withStore("readwrite", function (s, done) { s.put(rec); done(true); });
    if (now) { sendRecord(rec, false); return written; }
    return written.then(function (ok) {
      return ok ? flushPositions() : sendRecord(rec, false);   // no IndexedDB: just send it
    });
  }
  window.addEventListener("online", function () { flushPositions(); });

  // ===== the app shell: service worker and storage (TASK-277) ============================
  //
  // sw.js keeps the app, the books opened and MathJax on the device (its own
  // comment says how); this registers it, with the site's top as its scope, and
  // asks once per device for storage the browser will not evict early. Where
  // the browser says no, books simply download again.
  //
  // The first page opened on a device loads before any worker controls it, so
  // what it loaded never passed through one; that page tells the worker which
  // files it loaded (as they finish: MathJax fetches its fonts late), and the
  // worker keeps those it would have kept. A page a worker controls needs none.
  var PERSIST_KEY = "haselnuss.persistAsked";
  var KEEP_FOR = 60000;   // ms a first page goes on reporting what it loads

  function startShell() {
    var sw = navigator.serviceWorker;
    if (!sw || !window.isSecureContext) return;
    var first = !sw.controller;
    sw.register(new URL("sw.js", ROOT).href, { scope: ROOT.href }).then(function () {
      return sw.ready;
    }).then(function (reg) {
      if (first && reg.active) reportLoaded(reg.active);
    }).catch(function () { /* no worker: the app works online as before */ });
    askPersist();
  }

  function askPersist() {
    var st = navigator.storage;
    if (!st || !st.persist || !st.persisted) return;
    try { if (localStorage.getItem(PERSIST_KEY)) return; } catch (e) { return; }
    st.persisted().then(function (yes) { return yes || st.persist(); }).then(function () {
      try { localStorage.setItem(PERSIST_KEY, "1"); } catch (e) { /* asked again next time */ }
    }).catch(function () {});
  }

  function reportLoaded(worker) {
    var pending = [location.href.split("#")[0]];
    var timer = null;
    function send() {
      timer = null;
      if (pending.length) worker.postMessage({ type: "keep", urls: pending.splice(0) });
    }
    function add(entries) {
      entries.forEach(function (e) { if (e.name) pending.push(e.name); });
      if (!timer) timer = setTimeout(send, 300);
    }
    add([]);
    if (!window.PerformanceObserver) return;
    try {
      var obs = new PerformanceObserver(function (list) { add(list.getEntries()); });
      obs.observe({ type: "resource", buffered: true });
      setTimeout(function () { obs.disconnect(); }, KEEP_FOR);
    } catch (e) {
      add(performance.getEntriesByType ? performance.getEntriesByType("resource") : []);
    }
  }

  var homeRoot = document.getElementById("home-app");
  if (homeRoot) { startHome(); startShell(); }
  // a book page: the build's inline theme script has already set data-theme
  else if (document.documentElement.hasAttribute("data-theme")) { startReading(); startShell(); }
  // added after the screens' own pagehide work (a last save) has made its transaction
  window.addEventListener("pagehide", closeDb);

  // ===== the reading screen (TASK-275) ==================================================
  //
  // Controls around the book's own HTML, never a new renderer (doc-12, "Reading
  // screen", "Links to other books"): a running header and the percentage read,
  // and on a tap in the middle of the page the bars with Library, Back, the
  // title, Aa, a slider, Contents, Search and Referenced by. Tapping a book link
  // opens its passage in a sheet from the target book's previews.json.
  // Where the reader left off (TASK-276) is saved and put back from here too.

  function startReading() {
    var THEME_KEY = "haselnuss.theme";        // read by the page's inline script too
    var FONT_KEY = "haselnuss.fontSize";      // likewise
    var BACK_KEY = "haselnuss.back";          // sessionStorage: where Back returns to
    var RESTORE_KEY = "haselnuss.restore";    // sessionStorage: a scroll position to put back
    var DATA_CACHE = "haselnuss-data";
    var CATALOG_CACHE = "haselnuss-catalog";  // the home screen's
    var THEMES = ["light", "sepia", "dark"];
    var SIZES = [80, 90, 100, 110, 120, 135, 150, 175, 200];
    var SEARCH_MAX = 100;
    var MATHJAX_SRC = "https://cdn.jsdelivr.net/npm/mathjax@3.2.2/es5/tex-mml-chtml.js";
    var html = document.documentElement;

    function make(tag, cls, text) {
      var e = document.createElement(tag);
      if (cls) e.className = cls;
      if (text !== undefined) e.textContent = text;
      return e;
    }
    function button(cls, text, onClick) {
      var b = make("button", cls, text);
      b.type = "button";
      if (onClick) b.addEventListener("click", onClick);
      return b;
    }
    function clear(node) { while (node.firstChild) node.removeChild(node.firstChild); }
    function fold(s) {
      return String(s).normalize("NFD").replace(/\p{M}+/gu, "").toLowerCase();
    }
    function session(key, value) {
      try {
        if (value === undefined) return JSON.parse(sessionStorage.getItem(key) || "null");
        if (value === null) sessionStorage.removeItem(key);
        else sessionStorage.setItem(key, JSON.stringify(value));
      } catch (e) { /* storage off: no Back across pages */ }
      return null;
    }
    function local(key, value) {
      try {
        if (value === undefined) return localStorage.getItem(key);
        localStorage.setItem(key, String(value));
      } catch (e) { /* private mode: per page only */ }
      return null;
    }

    // ---- pages and books -----------------------------------------------------------------

    // A page's identity: its path, with a trailing index.html dropped.
    function pageKey(url) {
      var u = new URL(url, location.href);
      return u.origin + u.pathname.replace(/index\.html$/, "");
    }
    var here = pageKey(location.href);
    function hereUrl() { return location.href.split("#")[0]; }
    // Another book of this site, or null.
    function otherBook(url) {
      var u;
      try { u = new URL(url, location.href); } catch (e) { return null; }
      if (u.origin !== location.origin) return null;
      if (u.pathname.indexOf(ROOT.pathname + "books/") !== 0) return null;
      return pageKey(u.href) === here ? null : u;
    }
    // The page of the book `id` (library:shelf/slug) and, when given, an id on it.
    function bookUrl(id, anchor) {
      var colon = String(id).indexOf(":");
      if (colon <= 0) return null;
      var parts = [id.slice(0, colon)].concat(id.slice(colon + 1).split("/"));
      if (parts.some(function (p) { return !p || p === "." || p === ".."; })) return null;
      var u = new URL("books/" + parts.map(encodeURIComponent).join("/") + "/index.html", ROOT);
      if (anchor) u.hash = encodeURIComponent(anchor).replace(/%3A/gi, ":");
      return u;
    }
    function titleFromId(id) {
      var slug = String(id).slice(String(id).lastIndexOf("/") + 1).replace(/^[^:]*:/, "");
      return slug.replace(/[-_]+/g, " ");
    }
    var titleEl = document.querySelector("header.title-block h1.title");
    var bookTitle = (titleEl ? titleEl.textContent : document.title).trim() || document.title;

    // ---- data files: network first, the copy kept on the device when offline ---------------

    // Catalog pages go where the home screen keeps them, so its offline search
    // sees them too; backlinks.json and previews.json have their own cache.
    var openCaches = new Map();
    function cacheFor(url) {
      var name = url.indexOf(new URL("catalog/", ROOT).href) === 0 ? CATALOG_CACHE : DATA_CACHE;
      if (!openCaches.has(name)) {
        openCaches.set(name, window.caches
          ? caches.open(name).catch(function () { return null; })
          : Promise.resolve(null));
      }
      return openCaches.get(name);
    }
    var loaded = new Map();   // url -> Promise of JSON, for this page's life
    function fetchJson(url) {
      var p = loaded.get(url);
      if (p) return p;
      var cache = cacheFor(url);
      p = fetch(url).then(function (r) {
        if (!r.ok) throw new Error(url + ": " + r.status);
        var copy = r.clone();
        return cache.then(function (c) {
          if (c) c.put(url, copy).catch(function () {});
        }).then(function () { return r.json(); });
      }).catch(function (err) {
        return cache.then(function (c) { return c ? c.match(url) : null; }).then(function (hit) {
          if (!hit) throw err;
          return hit.json();
        });
      });
      p.catch(function () { loaded.delete(url); });   // try again next time
      loaded.set(url, p);
      return p;
    }

    // Books' titles from the server's catalog (api/books, one request for a
    // list of ids; its answer kept like the data files, so a list seen before
    // is named offline too); a book's id slug until then, or when it is unknown.
    var titles = new Map();   // id -> Promise of its title
    function titlesOf(ids) {
      var wanted = ids.filter(function (id) { return !titles.has(id); });
      if (wanted.length) {
        var all = Promise.all(booksUrls(wanted).map(function (u) {
          return fetchJson(u).then(function (json) { return json && json.books || {}; }, function () { return null; });
        })).then(function (answers) {
          // a request that failed is asked again next time
          if (answers.indexOf(null) >= 0) wanted.forEach(function (id) { titles.delete(id); });
          return Object.assign.apply(null, [{}].concat(answers.filter(Boolean)));
        });
        wanted.forEach(function (id) {
          titles.set(id, all.then(function (books) {
            var b = books[id];
            return b && typeof b.title === "string" && b.title ? b.title : titleFromId(id);
          }));
        });
      }
      return ids.map(function (id) { return titles.get(id); });
    }
    function titleOf(id) { return titlesOf([id])[0]; }

    // ---- Back: following a link to another book, and returning to the exact spot ---------

    function backStack() {
      var s = session(BACK_KEY);
      return Array.isArray(s) ? s : [];
    }
    function rememberBack(target) {
      var s = backStack();
      s.push({ from: here, url: hereUrl(), title: bookTitle, y: window.scrollY,
               h: document.documentElement.scrollHeight, to: pageKey(target.href) });
      session(BACK_KEY, s.slice(-20));
    }
    function go(target) {
      if (otherBook(target.href)) rememberBack(target);
      else { closePanel(); showControls(false); }   // within this book: no Back to itself
      location.href = target.href;
    }
    function goBack() {
      var s = backStack();
      var top = s.pop();
      if (!top) return;
      session(BACK_KEY, s);
      session(RESTORE_KEY, { page: top.from, y: top.y, h: top.h });
      location.href = top.url;
    }
    // Put back a position Back asked for, once now and once more when the page
    // (its images) has loaded, unless the reader has scrolled since.
    function restoreScroll() {
      var r = session(RESTORE_KEY);
      if (!r || r.page !== here) return false;
      session(RESTORE_KEY, null);
      if ("scrollRestoration" in history) history.scrollRestoration = "manual";
      var target = 0;
      function put() {
        var h = document.documentElement.scrollHeight;
        target = r.h && h !== r.h ? Math.round(r.y * h / r.h) : r.y;
        window.scrollTo(0, target);
      }
      put();
      if (document.readyState !== "complete") {
        window.addEventListener("load", function () {
          if (Math.abs(window.scrollY - target) <= 2) put();
        }, { once: true });
      }
      return true;
    }

    // ---- the controls -------------------------------------------------------------------------

    var ui = make("div", "hz");
    ui.id = "hz-reader";
    var running = make("div", "hz-running");
    running.setAttribute("aria-hidden", "true");
    var pctEl = make("div", "hz-pct", "0%");
    pctEl.setAttribute("aria-hidden", "true");

    var top = make("div", "hz-top");
    var topRow = make("div", "hz-row");
    var library = make("a", "hz-library", "‹ Library");
    library.href = ROOT.href;
    var back = button("hz-back", "", goBack);
    back.hidden = true;
    var aa = button("hz-aa", "Aa", function () { openPanel("aa"); });
    aa.setAttribute("aria-label", "Font size and theme");
    topRow.appendChild(library);
    topRow.appendChild(back);
    topRow.appendChild(make("span", "hz-gap"));
    topRow.appendChild(aa);
    var titleLine = make("div", "hz-title", bookTitle);
    top.appendChild(topRow);
    top.appendChild(titleLine);

    var bottom = make("div", "hz-bottom");
    var slider = make("input", "hz-slider");
    slider.type = "range";
    slider.min = "0";
    slider.max = "1000";
    slider.value = "0";
    slider.setAttribute("aria-label", "Move through the book");
    var tools = make("div", "hz-row hz-tools");
    tools.appendChild(button("hz-contents", "Contents", function () { openPanel("contents"); }));
    tools.appendChild(button("hz-search", "Search", function () { openPanel("search"); }));
    tools.appendChild(button("hz-refs", "Referenced by", function () { openPanel("refs"); }));
    bottom.appendChild(slider);
    bottom.appendChild(tools);

    var overlay = make("div", "hz-overlay");
    overlay.hidden = true;
    var sheet = make("div", "hz-sheet");
    sheet.setAttribute("role", "dialog");
    sheet.setAttribute("aria-modal", "true");
    var sheetHead = make("div", "hz-sheet-head");
    var sheetTitle = make("h2", "hz-sheet-title");
    sheetTitle.id = "hz-sheet-title";
    sheet.setAttribute("aria-labelledby", sheetTitle.id);
    sheetHead.appendChild(sheetTitle);
    sheetHead.appendChild(button("hz-close", "Close", closePanel));
    var sheetBody = make("div", "hz-sheet-body");
    sheet.appendChild(sheetHead);
    sheet.appendChild(sheetBody);
    overlay.appendChild(sheet);
    overlay.addEventListener("click", function (e) { if (e.target === overlay) closePanel(); });

    ui.appendChild(running);
    ui.appendChild(pctEl);
    ui.appendChild(top);
    ui.appendChild(bottom);
    ui.appendChild(overlay);
    document.body.appendChild(ui);

    function controlsShown() { return html.classList.contains("hz-show"); }
    function showControls(on) {
      html.classList.toggle("hz-show", on);
      if (on) update();
    }

    // ---- the book's headings, the running header, the percentage, the slider --------------

    function inBook(node) { return !ui.contains(node); }
    var headings = Array.prototype.filter.call(
      document.body.querySelectorAll("h1, h2, h3, h4, h5, h6"),
      function (h) { return inBook(h) && !h.closest("header.title-block, nav, section.footnotes"); });

    function maxScroll() { return Math.max(0, document.documentElement.scrollHeight - window.innerHeight); }
    function shown(e) { return e.getClientRects().length > 0; }
    // Where a heading is on screen; one inside a closed <details> is where the
    // details is, so the tops stay in page order.
    function topOf(h) {
      var e = h;
      while (e && !shown(e)) e = e.parentElement;
      return e ? e.getBoundingClientRect().top : 0;
    }
    // The index in `headings` of the last one the reader can see at or above
    // `line` (a y on screen), or -1.
    function headingAt(line) {
      var lo = 0, hi = headings.length - 1, at = -1;
      while (lo <= hi) {   // the last heading at or above the line
        var mid = (lo + hi) >> 1;
        if (topOf(headings[mid]) <= line) { at = mid; lo = mid + 1; } else hi = mid - 1;
      }
      while (at >= 0 && !shown(headings[at])) at--;   // one the reader can see
      return at;
    }
    function currentHeading() {
      // a heading just under the running header (where a jump puts it) is current
      var at = headingAt(running.getBoundingClientRect().bottom + 20);
      return at >= 0 ? headings[at] : null;
    }
    var queued = false;
    function update() {
      queued = false;
      var max = maxScroll();
      var frac = max > 0 ? Math.min(1, window.scrollY / max) : 1;
      var pct = Math.round(frac * 100);
      pctEl.textContent = pct + "%";
      html.setAttribute("data-percent", String(pct));
      var h = currentHeading();
      running.textContent = h ? h.textContent.replace(/\s+/g, " ").trim() : bookTitle;
      if (!sliding) slider.value = String(Math.round(frac * 1000));
    }
    function schedule() {
      if (queued) return;
      queued = true;
      requestAnimationFrame(update);
    }
    var sliding = false;
    slider.addEventListener("input", function () {
      sliding = true;
      window.scrollTo(0, Number(slider.value) / 1000 * maxScroll());
      sliding = false;
      update();
    });
    window.addEventListener("scroll", schedule, { passive: true });
    window.addEventListener("resize", schedule);
    document.addEventListener("toggle", schedule, true);   // a <details> opened or closed

    // ---- taps: the middle toggles the controls, a book link opens its preview ------------

    // Taps on these do what they do in a plain html build, and nothing else.
    var INTERACTIVE = "a, button, input, select, textarea, label, summary, [role=button], [tabindex]," +
      " [contenteditable], .book-ref, mjx-container";
    document.addEventListener("click", function (e) {
      if (e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
      var t = e.target instanceof Element ? e.target : e.target.parentElement;
      if (!t || ui.contains(t)) return;
      var a = t.closest("a[href]");
      if (a) {
        if (a.classList.contains("book-ref")) {
          e.preventDefault();
          openPreview(a);
          return;
        }
        var other = otherBook(a.href);
        if (other) rememberBack(other);
        return;   // the browser follows it
      }
      if (t.closest(INTERACTIVE)) return;
      var sel = window.getSelection && window.getSelection();
      if (sel && !sel.isCollapsed && String(sel).trim()) return;
      var y = e.clientY / window.innerHeight;
      if (y > 0.2 && y < 0.8) showControls(!controlsShown());
    });
    document.addEventListener("keydown", function (e) {
      if (e.key === "Escape") {
        if (!overlay.hidden) closePanel();
        else if (controlsShown()) showControls(false);
      }
    });

    // ---- the sheet --------------------------------------------------------------------------

    var panel = null;
    var lastFocus = null;
    function openSheet(name, title) {
      panel = name;
      lastFocus = document.activeElement;
      sheet.setAttribute("data-panel", name);
      sheetTitle.textContent = title;
      clear(sheetBody);
      overlay.hidden = false;
      html.classList.add("hz-sheet-open");
      sheetBody.scrollTop = 0;
      return sheetBody;
    }
    function closePanel() {
      panel = null;
      overlay.hidden = true;
      html.classList.remove("hz-sheet-open");
      sheet.removeAttribute("data-panel");
      clear(sheetBody);
      if (lastFocus && lastFocus.focus) try { lastFocus.focus({ preventScroll: true }); } catch (e) { /* gone */ }
    }
    function openPanel(name) {
      if (name === "aa") drawAa(openSheet("aa", "Text"));
      else if (name === "contents") drawContents(openSheet("contents", "Contents"));
      else if (name === "search") drawSearch(openSheet("search", "Search in this book"));
      else if (name === "refs") drawRefs(openSheet("refs", "Referenced by"));
    }
    function status(box, text) {
      var p = make("p", "hz-status", text);
      p.setAttribute("role", "status");
      box.appendChild(p);
      return p;
    }
    // Open every <details> `node` sits in, so it can be seen.
    function reveal(node) {
      for (var d = node.parentElement; d; d = d.parentElement) {
        if (d.tagName === "DETAILS" && !d.open) d.open = true;
      }
    }
    // Jump to `el` in the book, under the running header (CSS scroll-margin-top
    // gives the offset).
    function jumpTo(el) {
      closePanel();
      showControls(false);
      reveal(el);
      el.scrollIntoView({ block: "start" });
      update();
    }

    // ---- Aa: font size and theme, kept on this device ----------------------------------------

    function fontSize() {
      var f = Number(local(FONT_KEY));
      return SIZES.indexOf(f) >= 0 ? f : 100;
    }
    function keepingPlace(change) {
      var max = maxScroll();
      var frac = max > 0 ? window.scrollY / max : 0;
      change();
      window.scrollTo(0, frac * maxScroll());
      update();
    }
    function setFont(f) {
      keepingPlace(function () {
        local(FONT_KEY, f);
        html.style.fontSize = f + "%";
      });
    }
    function setTheme(t) {
      local(THEME_KEY, t);
      html.setAttribute("data-theme", t);
    }
    function drawAa(box) {
      var sizeRow = make("div", "hz-row hz-sizes");
      var label = make("span", "hz-size", fontSize() + "%");
      function step(d) {
        var i = SIZES.indexOf(fontSize()) + d;
        if (i < 0 || i >= SIZES.length) return;
        setFont(SIZES[i]);
        label.textContent = SIZES[i] + "%";
      }
      var smaller = button("hz-smaller", "A−", function () { step(-1); });
      smaller.setAttribute("aria-label", "Smaller text");
      var larger = button("hz-larger", "A+", function () { step(1); });
      larger.setAttribute("aria-label", "Larger text");
      sizeRow.appendChild(smaller);
      sizeRow.appendChild(label);
      sizeRow.appendChild(larger);
      box.appendChild(sizeRow);
      var themeRow = make("div", "hz-row hz-themes");
      THEMES.forEach(function (t) {
        var b = button("hz-theme hz-theme-" + t, t.charAt(0).toUpperCase() + t.slice(1), function () {
          setTheme(t);
          Array.prototype.forEach.call(themeRow.children, function (c) {
            c.setAttribute("aria-pressed", String(c === b));
          });
        });
        b.setAttribute("data-theme-name", t);
        b.setAttribute("aria-pressed", String(html.getAttribute("data-theme") === t));
        themeRow.appendChild(b);
      });
      box.appendChild(themeRow);
    }

    // ---- Contents: the book's own headings -----------------------------------------------------

    function drawContents(box) {
      if (!headings.length) { status(box, "This book has no headings."); return; }
      var ul = make("ul", "hz-list hz-toc");
      headings.forEach(function (h) {
        var li = make("li");
        var b = button("hz-item", h.textContent.replace(/\s+/g, " ").trim(), function () { jumpTo(h); });
        b.style.paddingLeft = (Number(h.tagName.slice(1)) - 1) * 14 + "px";
        li.appendChild(b);
        ul.appendChild(li);
      });
      box.appendChild(ul);
    }

    // ---- Search in this book: text as the page shows it, without case or accents --------------

    var foldedText = new WeakMap();   // text node -> {s, map}
    function folded(node) {
      var f = foldedText.get(node);
      if (f && f.src === node.data) return f;
      var text = node.data, s = "", map = [];
      for (var i = 0; i < text.length;) {
        var ch = String.fromCodePoint(text.codePointAt(i));
        var fc = fold(ch);
        for (var k = 0; k < fc.length; k++) { s += fc[k]; map.push(i); }
        i += ch.length;
      }
      map.push(text.length);
      f = { src: text, s: s, map: map };
      foldedText.set(node, f);
      return f;
    }
    function textNodes() {
      var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, {
        acceptNode: function (n) {
          var p = n.parentElement;
          if (!p || ui.contains(p) || p.closest("script, style, mjx-container")) return NodeFilter.FILTER_REJECT;
          return n.data.trim() ? NodeFilter.FILTER_ACCEPT : NodeFilter.FILTER_SKIP;
        }
      });
      var out = [];
      for (var n = walker.nextNode(); n; n = walker.nextNode()) out.push(n);
      return out;
    }
    function search(q) {
      var want = fold(q.trim()).replace(/\s+/g, " ");
      var hits = [];
      if (want.length < 2) return hits;
      var nodes = textNodes();
      for (var i = 0; i < nodes.length && hits.length <= SEARCH_MAX; i++) {
        var f = folded(nodes[i]);
        var s = f.s.replace(/\s/g, " ");
        for (var at = s.indexOf(want); at >= 0 && hits.length <= SEARCH_MAX; at = s.indexOf(want, at + want.length)) {
          hits.push({ node: nodes[i], start: f.map[at], end: f.map[at + want.length] });
        }
      }
      return hits;
    }
    var lastQuery = "";
    function drawSearch(box) {
      var form = make("form", "hz-search-form");
      var input = make("input", "hz-search-input");
      input.type = "search";
      input.placeholder = "Find in this book";
      input.setAttribute("aria-label", "Find in this book");
      input.autocomplete = "off";
      input.value = lastQuery;
      form.appendChild(input);
      box.appendChild(form);
      var note = status(box, "");
      var ul = make("ul", "hz-list hz-results");
      box.appendChild(ul);
      var timer = null;
      function run() {
        lastQuery = input.value;
        clear(ul);
        var hits = search(input.value);
        var more = hits.length > SEARCH_MAX;
        hits = hits.slice(0, SEARCH_MAX);
        if (fold(input.value.trim()).length < 2) note.textContent = "";
        else if (!hits.length) note.textContent = "Not found in this book.";
        else note.textContent = more ? "The first " + SEARCH_MAX + " places." : hits.length + (hits.length === 1 ? " place." : " places.");
        hits.forEach(function (hit) {
          var text = hit.node.data;
          var li = make("li");
          var b = button("hz-item hz-hit", undefined, function () { showHit(hit); });
          var from = Math.max(0, hit.start - 40), to = Math.min(text.length, hit.end + 40);
          b.appendChild(document.createTextNode((from > 0 ? "…" : "") + text.slice(from, hit.start).replace(/\s+/g, " ")));
          b.appendChild(make("mark", "", text.slice(hit.start, hit.end)));
          b.appendChild(document.createTextNode(text.slice(hit.end, to).replace(/\s+/g, " ") + (to < text.length ? "…" : "")));
          li.appendChild(b);
          ul.appendChild(li);
        });
      }
      input.addEventListener("input", function () { clearTimeout(timer); timer = setTimeout(run, 200); });
      form.addEventListener("submit", function (e) { e.preventDefault(); clearTimeout(timer); run(); });
      if (lastQuery) run();
      setTimeout(function () { input.focus(); }, 0);
    }
    function showHit(hit) {
      closePanel();
      showControls(false);
      reveal(hit.node);
      var range = document.createRange();
      range.setStart(hit.node, hit.start);
      range.setEnd(hit.node, hit.end);
      var r = range.getBoundingClientRect();
      window.scrollTo(0, Math.max(0, window.scrollY + r.top - window.innerHeight / 3));
      var sel = window.getSelection();
      sel.removeAllRanges();
      sel.addRange(range);
      update();
    }

    // ---- Referenced by: backlinks.json, grouped by passage ----------------------------------------

    function drawRefs(box) {
      var note = status(box, "Loading…");
      fetchJson(new URL("backlinks.json", location.href).href).then(function (report) {
        if (panel !== "refs") return;
        var groups = [], byKey = new Map();
        (report.edges || []).forEach(function (e) {
          var from = e.from || {}, to = e.to || {};
          if (typeof from.id !== "string" || !bookUrl(from.id)) return;
          var key = typeof to.passage === "string" ? to.passage : "";
          var g = byKey.get(key);
          if (!g) { g = { key: key, entries: [], seen: new Map() }; byKey.set(key, g); groups.push(g); }
          var fromPassage = typeof from.passage === "string" ? from.passage : "";
          var k = from.id + "#" + fromPassage;
          var entry = g.seen.get(k);
          if (!entry) {
            entry = { id: from.id, passage: fromPassage, kinds: [] };
            g.seen.set(k, entry);
            g.entries.push(entry);
          }
          var kind = e.kind === "quote" ? "quotes" : "links";
          if (entry.kinds.indexOf(kind) < 0) entry.kinds.push(kind);
        });
        note.remove();
        if (!groups.length) { status(box, "No other book refers to this one."); return; }
        titlesOf(groups.reduce(function (ids, g) {   // every citing book named in one request
          return ids.concat(g.entries.map(function (entry) { return entry.id; }));
        }, []));
        groups.forEach(function (g) {
          box.appendChild(make("h3", "hz-group", g.key ? bookTitle + " " + g.key : "The whole book"));
          var ul = make("ul", "hz-list hz-refs-list");
          g.entries.forEach(function (entry) {
            var li = make("li");
            var target = bookUrl(entry.id, entry.passage);
            var b = button("hz-item hz-ref", undefined, function () { go(target); });
            b.setAttribute("data-book", entry.id);
            var name = make("span", "hz-ref-title", titleFromId(entry.id));
            titleOf(entry.id).then(function (t) { name.textContent = t; });
            b.appendChild(name);
            b.appendChild(make("span", "hz-sub",
              (entry.passage ? entry.passage + " · " : "") + entry.kinds.join(" and ")));
            li.appendChild(b);
            ul.appendChild(li);
          });
          box.appendChild(ul);
        });
      }, function () {
        if (panel === "refs") note.textContent = "Cannot load the references. Check the connection.";
      });
    }

    // ---- a book link's preview -----------------------------------------------------------------

    // The preview's HTML is the build's own, sanitised emitter output from this
    // site (the very markup the linked page shows), parsed inert in a <template>:
    // its ids are prefixed so they cannot clash with the page's, and its URLs,
    // relative to previews.json, are made absolute.
    function previewContent(htmlText, base) {
      var t = document.createElement("template");
      t.innerHTML = htmlText;
      var frag = t.content;
      Array.prototype.forEach.call(frag.querySelectorAll("script"), function (s) { s.remove(); });
      // ids and names alike: <img name="createElement"> or name="MathJax" would
      // otherwise shadow document.createElement or window.MathJax while shown
      Array.prototype.forEach.call(frag.querySelectorAll("[id]"), function (e) { e.id = "hz-pv-" + e.id; });
      Array.prototype.forEach.call(frag.querySelectorAll("[name]"), function (e) {
        e.setAttribute("name", "hz-pv-" + e.getAttribute("name"));
      });
      Array.prototype.forEach.call(frag.querySelectorAll("label[for]"), function (e) {
        e.setAttribute("for", "hz-pv-" + e.getAttribute("for"));
      });
      [["href", "[href]"], ["src", "[src]"], ["cite", "[cite]"]].forEach(function (pair) {
        Array.prototype.forEach.call(frag.querySelectorAll(pair[1]), function (e) {
          var v = e.getAttribute(pair[0]);
          if (pair[0] === "href" && v.charAt(0) === "#") { e.setAttribute("href", "#hz-pv-" + v.slice(1)); return; }
          try { e.setAttribute(pair[0], new URL(v, base).href); } catch (err) { e.removeAttribute(pair[0]); }
        });
      });
      return frag;
    }
    // MathJax, configured exactly as the html emitter configures it (its
    // mathjax-config, TASK-217): the page's own when it has maths, else loaded
    // here with that same configuration, copied verbatim (haselnuss.reading-app-test
    // pins the copy to the emitter's), only ever to typeset a preview.
    // Always settles: a script that failed before we looked, or never answers,
    // gives up after MATHJAX_WAIT ms. A failure is not kept, so a later preview
    // tries again.
    var MATHJAX_WAIT = 20000;
    var mathJax = null;
    function loadMathJax() {
      if (mathJax) return mathJax;
      var p = new Promise(function (resolve, reject) {
        setTimeout(function () { reject(new Error("MathJax did not load in time")); }, MATHJAX_WAIT);
        function ready() {
          var M = window.MathJax;
          // typesetPromise exists only once startup is done
          if (M && M.startup && M.startup.promise) {
            M.startup.promise.then(function () {
              if (typeof M.typesetPromise === "function") resolve(M);
              else reject(new Error("MathJax did not start"));
            }, reject);
          } else reject(new Error("MathJax did not start"));
        }
        var s = document.getElementById("MathJax-script");
        if (s) {
          if (window.MathJax && window.MathJax.startup && window.MathJax.startup.promise) ready();
          else { s.addEventListener("load", ready); s.addEventListener("error", reject); }
          return;
        }
        if (window.MathJax) { reject(new Error("MathJax is configured by something else")); return; }
        window.MathJax={loader:{load:['ui/safe']},tex:{packages:{'[-]':['require']},autoload:{html:[],unicode:[]}},options:{safeOptions:{allow:{URLs:'none'}}},startup:{ready:function(){MathJax.startup.defaultReady();var s=MathJax.startup.document.safe;s.filterMethods.filterFontToken=function(_,v){return /^[-\w ,]*$/.test(v)?v:null;};['fontfamily','fontweight','fontstyle'].forEach(function(a){s.filterAttributes.set(a,'filterFontToken');});}}};
        window.MathJax.startup.typeset = false;   // only the preview, never the rest of this page
        s = document.createElement("script");
        s.id = "MathJax-script";
        s.async = true;
        s.src = MATHJAX_SRC;
        s.addEventListener("load", ready);
        s.addEventListener("error", function () {
          // ours failed: take it and its configuration back, so a retry starts clean
          s.remove();
          if (window.MathJax && !window.MathJax.startup.promise) window.MathJax = undefined;
          reject(new Error("MathJax did not load"));
        });
        document.head.appendChild(s);
      });
      mathJax = p;
      p.catch(function () { if (mathJax === p) mathJax = null; });
      return p;
    }

    var previewSeq = 0;
    function openPreview(a) {
      var target;
      try { target = new URL(a.href); } catch (e) { return; }
      var page = new URL(target.href);
      page.hash = "";
      var dataUrl = new URL("previews.json", page);
      var key = a.hasAttribute("data-passage") ? a.getAttribute("data-passage") : null;
      var mine = ++previewSeq;
      var box = openSheet("preview", a.textContent.replace(/\s+/g, " ").trim());
      var note = status(box, "Loading…");
      var content = make("div", "hz-preview");
      var source = make("p", "hz-source");
      var actions = make("div", "hz-row hz-actions");
      var open = button("hz-open", "Open", function () { go(target); });
      actions.appendChild(open);
      actions.appendChild(button("hz-close-preview", "Close", closePanel));
      box.appendChild(content);
      box.appendChild(source);
      box.appendChild(actions);
      var sameSite = otherBook(target.href) || pageKey(target.href) === here;
      if (!sameSite) { note.textContent = "This link leaves the library."; return; }
      fetchJson(dataUrl.href).then(function (json) {
        if (mine !== previewSeq || panel !== "preview") return;
        var list = Array.isArray(json.previews) ? json.previews : [];
        var hit = null;
        if (key !== null) {
          hit = list.filter(function (p) { return (typeof p.passage === "string" ? p.passage : "") === key; })[0] || null;
        } else {   // a page built before links said their passage: only an unambiguous URL
          var same = list.filter(function (p) {
            try { return new URL(p.url, dataUrl).href === target.href; } catch (e) { return false; }
          });
          hit = same.length === 1 ? same[0] : null;
        }
        var book = typeof json.book === "string" ? json.book : "";
        var lib = book.slice(0, Math.max(0, book.indexOf(":")));
        if (book) {
          open.textContent = "Open " + titleFromId(book);
          titleOf(book).then(function (t) { if (mine === previewSeq) open.textContent = "Open " + t; });
        }
        if (!hit || typeof hit.html !== "string") {
          note.textContent = "No preview of this passage.";
          return;
        }
        note.remove();
        content.appendChild(previewContent(hit.html, dataUrl));
        source.textContent = "— " + (typeof hit.title === "string" ? hit.title : "") + (lib ? " · " + lib : "");
        if (hit.cut) content.appendChild(make("p", "hz-more", "Open to read more"));
        if (hit.math) {
          content.setAttribute("data-math", "pending");
          loadMathJax().then(function (M) {
            return M.typesetPromise([content]);
          }).then(function () {
            content.setAttribute("data-math", "done");
          }, function () {
            content.setAttribute("data-math", "failed");   // the TeX shows as written
          });
        }
      }, function () {
        if (mine === previewSeq && panel === "preview") note.textContent = "The preview cannot be loaded. Check the connection.";
      });
    }
    // Inside the sheet: a footnote link stays in the sheet; a link to another
    // book is followed with Back remembered.
    sheetBody.addEventListener("click", function (e) {
      var t = e.target instanceof Element ? e.target : e.target.parentElement;
      var a = t && t.closest("a[href]");
      if (!a || e.defaultPrevented || e.button !== 0) return;
      var raw = a.getAttribute("href");
      if (raw.charAt(0) === "#") {
        e.preventDefault();
        var el = document.getElementById(raw.slice(1));
        if (el && sheetBody.contains(el)) el.scrollIntoView({ block: "nearest" });
        return;
      }
      var other = otherBook(a.href);
      if (other) rememberBack(other);
    });

    // ---- where the reader left off (TASK-276; doc-12, "Where you left off") -----------------
    //
    // A position is taken at the reading line, just under the running header:
    // `id`, the innermost element with an author's label the line crosses (ids
    // the emitter numbers itself -- footnotes, their markers, sticky-note
    // toggles, bibliography entries -- are skipped: one more footnote renumbers
    // them); the nearest heading at or above the line, `{index, text}` -- its
    // order among the book's headings and its text without section number or
    // maths, so a renumbering or MathJax having run does not change it;
    // `offset`, how far into the id's block the line is (0-1), or into the
    // heading's section when there is no id; `percent` of the book. Opening the
    // book tries the id, then a heading with the same text (the one nearest the
    // saved order), then the same order, then the percentage. When the id is
    // gone the offset (into its block) means nothing in the heading's section,
    // so the percentage is used there, kept inside that section.
    //
    // Saved SAVE_DELAY after scrolling stops and when the page is hidden or left,
    // once the reader has moved; put back on opening unless the page was opened
    // at a passage (#hash) or by Back, or the reader has already scrolled. After
    // such an opening the reader must move more than a screen from where the
    // page put them before anything is saved, so a look at a cited passage does
    // not move their place.

    var SAVE_DELAY = 2000;
    var RESTORE_WAIT = 8000;   // ms the saved position is waited for
    var MAX_TEXT = 1024;   // the server's limit on a position's strings
    var MOVED = 40;         // px: closer than this to the last save is no move
    function pageBookId() {
      var base = ROOT.pathname + "books/";
      if (location.pathname.indexOf(base) !== 0) return null;
      var rest = location.pathname.slice(base.length).replace(/(^|\/)index\.html$/, "").replace(/\/$/, "");
      var parts;
      try { parts = rest.split("/").map(decodeURIComponent); } catch (e) { return null; }
      if (parts.length < 2) return null;
      var id = parts[0] + ":" + parts.slice(1).join("/");
      var u = bookUrl(id);
      return u && pageKey(u.href) === here ? id : null;
    }
    var reader = storedReader();
    var bookId = pageBookId();
    var tracking = !!(reader && bookId);
    var positionsReady = false;
    var lastY = null;       // scrollY of the last save or restore
    var moveNeeded = MOVED; // how far from lastY a save needs the reader to be
    var saveTimer = null;

    function readingLine() { return running.getBoundingClientRect().bottom + 8; }
    function headingText(h) {
      var c = h.cloneNode(true);
      Array.prototype.forEach.call(c.querySelectorAll(".section-number, .math, mjx-container, script"),
                                   function (n) { n.remove(); });
      return c.textContent.replace(/\s+/g, " ").trim().slice(0, MAX_TEXT);
    }
    // The section of the heading `i` on screen: from it to the next heading the
    // reader can see, or the end of the page.
    function headingBlock(i) {
      var top = topOf(headings[i]), bottom = null;
      for (var k = i + 1; k < headings.length && bottom === null; k++) {
        if (shown(headings[k])) bottom = topOf(headings[k]);
      }
      if (bottom === null || bottom <= top) bottom = document.documentElement.scrollHeight - window.scrollY;
      return { top: top, height: Math.max(1, bottom - top) };
    }
    // An id the emitter numbers in order rather than one an author wrote: a
    // footnote (fnN) or its marker (fnrefN), a sticky note's toggle, and a
    // bibliography entry (<li id="{section id}-N">). One more footnote earlier
    // in the book renumbers them all, so they cannot find a place again.
    function generatedId(e) {
      var id = e.id;
      if (/^fn(ref)?\d+$/.test(id) || /^sticky-note-toggle-\d+$/.test(id)) return true;
      var m = /^(.+)-\d+$/.exec(id);
      if (m && e.tagName === "LI" && e.parentElement) {
        var owner = e.parentElement.closest("[id]");
        if (owner && owner.id === m[1]) return true;
      }
      return false;
    }
    // The innermost element of the book with an author's id that the line
    // crosses, or null.
    function idAt(line) {
      var best = null, bestTop = -Infinity;
      var els = document.body.querySelectorAll("[id]");
      for (var i = 0; i < els.length; i++) {
        var e = els[i];
        if (e.id.length > MAX_TEXT || !inBook(e) || e.closest("mjx-container, header.title-block")) continue;
        if (generatedId(e)) continue;
        var r = e.getBoundingClientRect();
        if (!r.width && !r.height) continue;
        // `>=`: in document order a descendant comes after its ancestor
        if (r.top <= line && r.bottom > line && r.top >= bestTop) { best = e; bestTop = r.top; }
      }
      return best;
    }
    function capturePosition() {
      var line = readingLine();
      var max = maxScroll();
      var frac = max > 0 ? Math.min(1, Math.max(0, window.scrollY / max)) : 0;
      var el = idAt(line);
      var hi = headingAt(line);
      var block = el ? el.getBoundingClientRect() : hi >= 0 ? headingBlock(hi) : null;
      var offset = block ? Math.min(1, Math.max(0, (line - block.top) / Math.max(1, block.height))) : 0;
      return {
        id: el ? el.id : null,
        heading: hi >= 0 ? { index: hi, text: headingText(headings[hi]) } : null,
        offset: Math.round(offset * 10000) / 10000,
        percent: Math.round(frac * 1000) / 10
      };
    }
    // The heading a saved `{index, text}` names here: same text nearest the
    // saved order, else the same order; {index: -1} when neither.
    function matchHeading(saved) {
      var index = Number.isInteger(saved.index) ? saved.index : -1;
      var text = typeof saved.text === "string" ? saved.text : "";
      if (text) {
        var best = -1;
        headings.forEach(function (h, i) {
          if (headingText(h) === text && (best < 0 || Math.abs(i - index) < Math.abs(best - index))) best = i;
        });
        if (best >= 0) return { index: best, how: "heading" };
      }
      if (index >= 0 && index < headings.length) return { index: index, how: "order" };
      return { index: -1, how: null };
    }
    // Scroll to the saved position `pos`; how it was found ("id", "heading",
    // "order", "percent"), or "none".
    function applyPosition(pos) {
      var line = readingLine();
      var offset = typeof pos.offset === "number" && isFinite(pos.offset) ? Math.min(1, Math.max(0, pos.offset)) : 0;
      var y = null, how = "none";
      var el = typeof pos.id === "string" && pos.id ? document.getElementById(pos.id) : null;
      if (el && inBook(el)) {
        reveal(el);
        var r = el.getBoundingClientRect();
        if (r.width || r.height) { y = window.scrollY + r.top + offset * r.height - line; how = "id"; }
      }
      var hasPercent = typeof pos.percent === "number" && isFinite(pos.percent);
      function percentY() { return Math.min(100, Math.max(0, pos.percent)) / 100 * maxScroll(); }
      if (y === null && pos.heading && typeof pos.heading === "object") {
        var m = matchHeading(pos.heading);
        if (m.index >= 0) {
          reveal(headings[m.index]);
          var b = headingBlock(m.index);
          var top = window.scrollY + b.top;   // on the page
          if (typeof pos.id === "string" && pos.id) {
            // the offset was into the id's block, which is gone: the
            // percentage, kept inside this section (its top without one)
            y = (hasPercent ? Math.min(top + b.height - 1, Math.max(top, percentY() + line)) : top) - line;
          } else {
            y = top + offset * b.height - line;
          }
          how = m.how;
        }
      }
      if (y === null && hasPercent) {
        y = percentY();
        how = "percent";
      }
      if (y === null) return how;
      window.scrollTo(0, Math.max(0, Math.round(y)));
      lastY = window.scrollY;
      update();
      return how;
    }
    function savedMs(pos) {
      var t = pos ? Date.parse(pos.savedAt) : NaN;
      return isNaN(t) ? -Infinity : t;
    }
    function savePosition(leaving) {
      clearTimeout(saveTimer);
      saveTimer = null;
      if (!tracking || !positionsReady) return;
      if (lastY !== null && Math.abs(window.scrollY - lastY) < moveNeeded) return;
      lastY = window.scrollY;
      moveNeeded = MOVED;
      queuePosition(reader.id, bookId, capturePosition(), leaving);
      html.setAttribute("data-position-saves", String(Number(html.getAttribute("data-position-saves") || 0) + 1));
    }
    function restorePosition(openedAtPlace) {
      var startY = window.scrollY;
      function ready() {
        positionsReady = true;
        if (lastY === null) lastY = startY;
        html.setAttribute("data-position-ready", "true");
        if (Math.abs(window.scrollY - lastY) >= moveNeeded) {   // moved while it was looked up
          clearTimeout(saveTimer);
          saveTimer = setTimeout(function () { savePosition(false); }, SAVE_DELAY);
        }
      }
      if (!tracking) { html.setAttribute("data-position", "off"); ready(); return; }
      if (openedAtPlace) {
        // a look at a passage (a #link or Back) is not where the reader is in
        // this book until they read on from it: more than a screen from where
        // the page put them (once it has loaded, since a #hash may move it late)
        moveNeeded = window.innerHeight;
        lastY = window.scrollY;
        if (document.readyState !== "complete") {
          window.addEventListener("load", function () {
            if (moveNeeded !== MOVED) lastY = window.scrollY;
          }, { once: true });
        }
        html.setAttribute("data-position", "skipped");
        ready();
        // nothing was asked of the server, so its clock is not known yet
        measureClock(reader.id, bookId);
        return;
      }
      var fromServer = fetch(positionUrl(reader.id, bookId)).then(function (r) {
        noteServerClock(r);
        if (!r.ok) throw new Error(String(r.status));
        return r.json();
      }).then(function (a) {
        return a && a.position && typeof a.position === "object" ? a.position : null;
      }, function () { return null; });
      var onDevice = localRecord(reader.id, bookId).then(recordPosition, function () { return null; });
      // a server or storage that never answers must not keep the reader from
      // saving: after RESTORE_WAIT the book stays where it is
      var gaveUp = new Promise(function (resolve) { setTimeout(function () { resolve(null); }, RESTORE_WAIT); });
      Promise.race([Promise.all([fromServer, onDevice]), gaveUp]).then(function (got) {
        if (!got) { html.setAttribute("data-position", "timeout"); return; }
        var pos = savedMs(got[1]) > savedMs(got[0]) ? got[1] : got[0];   // the newer; the server's on a tie
        if (!pos) { html.setAttribute("data-position", "none"); return; }
        if (Math.abs(window.scrollY - startY) > 2) { html.setAttribute("data-position", "skipped"); return; }
        html.setAttribute("data-position", applyPosition(pos));
        if (document.readyState !== "complete") {   // images may still move it: once more on load
          var at = window.scrollY;
          window.addEventListener("load", function () {
            if (Math.abs(window.scrollY - at) <= 2) applyPosition(pos);
          }, { once: true });
        }
      }).then(ready, ready);
    }
    window.addEventListener("scroll", function () {
      if (!tracking) return;
      clearTimeout(saveTimer);
      saveTimer = setTimeout(function () { savePosition(false); }, SAVE_DELAY);
    }, { passive: true });
    document.addEventListener("visibilitychange", function () {
      if (document.visibilityState === "hidden") savePosition(true);
    });
    window.addEventListener("pagehide", function () { savePosition(true); });

    // ---- start ----------------------------------------------------------------------------------

    function showBack() {
      var s = backStack();
      var topEntry = s[s.length - 1];
      if (topEntry && topEntry.from === here && topEntry.to !== here) {
        // returned here by the browser's own Back: that entry is spent
        s.pop();
        session(BACK_KEY, s);
        topEntry = s[s.length - 1];
      }
      if (topEntry && topEntry.to === here) {
        back.textContent = "‹ Back to " + topEntry.title;
        back.hidden = false;
      } else back.hidden = true;
    }
    showBack();
    var openedAtPlace = restoreScroll() || !!location.hash;
    window.addEventListener("pageshow", function (e) { if (e.persisted) { showBack(); update(); } });
    update();
    html.setAttribute("data-reader", "ready");
    restorePosition(openedAtPlace);
    flushPositions();   // anything saved while the server could not be reached
  }

  // ===== the home screen ==============================================================

  function startHome() {
    var GAP = 12;          // between covers, px
    var MIN_COVER = 140;   // narrowest cover, px
    var OVERSCAN = 2;      // rows drawn above and below the screen
    var SEARCH_LIMIT = 50; // as the server's /api/search
    var CATALOG_CACHE = "haselnuss-catalog";
    var HEX = /^#[0-9a-fA-F]{6}$/;

    function $(id) { return document.getElementById(id); }
    function make(tag, cls, text) {
      var e = document.createElement(tag);
      if (cls) e.className = cls;
      if (text !== undefined) e.textContent = text;
      return e;
    }
    function clear(node) { while (node.firstChild) node.removeChild(node.firstChild); }
    function fold(s) {
      return String(s).normalize("NFD").replace(/\p{M}+/gu, "").toLowerCase();
    }
    function show(node, on) { node.hidden = !on; }
    function setColour(node, colour) {
      if (HEX.test(colour || "")) node.style.setProperty("--cover", colour);
    }
    // A book's page, relative to the site; each segment encoded so a title-ish
    // slug cannot change what the link means, and only under books/.
    function bookHref(path) {
      var p = String(path || "");
      if (p.indexOf("books/") !== 0 || p.indexOf("..") >= 0) return null;
      return p.split("/").map(encodeURIComponent).join("/");
    }

    // ---- the catalog: pages fetched when needed, kept on the device ------------------

    var pages = new Map();       // url -> Promise of the page's JSON
    var pageData = new Map();    // url -> the page's JSON, once loaded
    var cachePromise = window.caches
      ? caches.open(CATALOG_CACHE).catch(function () { return null; })
      : Promise.resolve(null);

    // Network first; the copy kept on the device when the network fails.
    function fetchCatalogJson(url) {
      return fetch(url).then(function (r) {
        if (!r.ok) throw new Error(url + ": " + r.status);
        var copy = r.clone();
        return cachePromise.then(function (c) {
          if (c) c.put(url, copy).catch(function () {});
        }).then(function () { return r.json(); });
      }).catch(function (err) {
        return cachePromise.then(function (c) {
          return c ? c.match(url) : null;
        }).then(function (hit) {
          if (!hit) throw err;
          return hit.json();
        });
      });
    }
    function pageUrl(lib, n) {
      return "catalog/" + encodeURIComponent(lib.name) + "/" + n + ".json";
    }
    function loadPage(lib, n) {
      var url = pageUrl(lib, n);
      var p = pages.get(url);
      if (!p) {
        p = fetchCatalogJson(url).then(function (json) {
          pageData.set(url, json);
          return json;
        }, function (err) {
          pages.delete(url);   // try again next time it is wanted
          throw err;
        });
        pages.set(url, p);
      }
      return p;
    }

    var libs = [];        // [{name, colour, books, pages, start}] in catalog order
    var pageSize = 500;
    var totalBooks = 0;
    function setIndex(index) {
      pageSize = index["page-size"] || 500;
      var start = 0;
      libs = (index.libraries || []).map(function (l) {
        var lib = { name: l.name, colour: l.colour, books: l.books, pages: l.pages, start: start };
        start += l.books;
        return lib;
      });
      totalBooks = start;
    }
    function libraryNamed(name) {
      for (var i = 0; i < libs.length; i++) if (libs[i].name === name) return libs[i];
      return null;
    }

    // ---- covers ------------------------------------------------------------------------

    function fillCover(node, book, lib) {
      clear(node);
      var href = book && bookHref(book.path);
      if (href) node.setAttribute("href", href); else node.removeAttribute("href");
      node.appendChild(make("span", "t", book ? book.title : ""));
      node.appendChild(make("span", "l", book ? book.library : lib.name));
      setColour(node, book ? book.colour : lib.colour);
      node.classList.toggle("pending", !book);
    }
    function newCover(book, lib) {
      var node = make("a", "cover");
      fillCover(node, book, lib);
      return node;
    }

    // ---- the grid: every cover of `grid libs` as one grid, only those on screen drawn --

    function Grid(host, gridLibs) {
      var segs = gridLibs.map(function (l, i) {
        var start = 0;
        for (var k = 0; k < i; k++) start += gridLibs[k].books;
        return { lib: l, start: start };
      });
      var total = segs.reduce(function (n, s) { return n + s.lib.books; }, 0);
      var surface = make("div", "grid");
      host.appendChild(surface);
      var cells = new Map();   // book number -> {node, loaded}
      var layout = { width: 0, cols: 0, size: 0, rowH: 0 };
      var queued = false;
      var dead = false;

      function locate(i) {
        var lo = 0, hi = segs.length - 1;
        while (lo < hi) {
          var mid = (lo + hi + 1) >> 1;
          if (segs[mid].start <= i) lo = mid; else hi = mid - 1;
        }
        var j = i - segs[lo].start;
        return { lib: segs[lo].lib, page: Math.floor(j / pageSize) + 1, slot: j % pageSize };
      }
      function bookAt(loc) {
        var data = pageData.get(pageUrl(loc.lib, loc.page));
        return data ? data.books[loc.slot] || null : null;
      }
      function drop() {
        cells.forEach(function (c) { surface.removeChild(c.node); });
        cells.clear();
      }
      function render() {
        queued = false;
        if (dead) return;
        var w = host.clientWidth;
        if (!w || !total) return;
        if (w !== layout.width) {
          drop();
          var cols = Math.max(2, Math.floor((w + GAP) / (MIN_COVER + GAP)));
          var size = (w - GAP * (cols - 1)) / cols;
          layout = { width: w, cols: cols, size: size, rowH: Math.round(size * 1.5) + GAP };
          surface.style.height = Math.ceil(total / cols) * layout.rowH + "px";
        }
        var rows = Math.ceil(total / layout.cols);
        var top = -host.getBoundingClientRect().top;
        var first = Math.max(0, Math.floor(top / layout.rowH) - OVERSCAN);
        var last = Math.min(rows - 1, Math.floor((top + window.innerHeight) / layout.rowH) + OVERSCAN);
        var from = first * layout.cols;
        var to = Math.min(total, (last + 1) * layout.cols);
        cells.forEach(function (c, i) {
          if (i < from || i >= to) { surface.removeChild(c.node); cells.delete(i); }
        });
        var wanted = new Map();
        for (var i = from; i < to; i++) {
          var loc = locate(i);
          var book = bookAt(loc);
          var cell = cells.get(i);
          if (!cell) {
            cell = { node: newCover(book, loc.lib), loaded: !!book };
            var row = Math.floor(i / layout.cols), col = i % layout.cols;
            cell.node.style.left = col * (layout.size + GAP) + "px";
            cell.node.style.top = row * layout.rowH + "px";
            cell.node.style.width = layout.size + "px";
            cell.node.style.height = Math.round(layout.size * 1.5) + "px";
            surface.appendChild(cell.node);
            cells.set(i, cell);
          } else if (book && !cell.loaded) {
            fillCover(cell.node, book, loc.lib);
            cell.loaded = true;
          }
          if (!book) wanted.set(pageUrl(loc.lib, loc.page), loc);
        }
        wanted.forEach(function (loc) {
          loadPage(loc.lib, loc.page).then(schedule, function () {});
        });
        host.setAttribute("data-total", String(total));
        host.setAttribute("data-drawn", String(cells.size));
      }
      function schedule() {
        if (queued || dead) return;
        queued = true;
        requestAnimationFrame(render);
      }
      window.addEventListener("scroll", schedule, { passive: true });
      window.addEventListener("resize", schedule);
      render();
      return {
        schedule: schedule,
        destroy: function () {
          dead = true;
          window.removeEventListener("scroll", schedule);
          window.removeEventListener("resize", schedule);
          host.removeChild(surface);
        }
      };
    }

    // ---- readers -----------------------------------------------------------------------

    var reader = null;   // {id, name}
    function remember(r) {
      try { localStorage.setItem(READER_KEY, JSON.stringify({ id: r.id, name: r.name })); } catch (e) { /* private mode */ }
    }
    function initial(name) {
      var ch = Array.from(String(name).trim())[0];
      return ch ? ch.toUpperCase() : "?";
    }
    function postJson(url, body) {
      return fetch(url, { method: "POST", headers: { "Content-Type": "application/json" },
                          body: JSON.stringify(body) });
    }

    var chooser = $("chooser");
    function chooserError(msg) {
      var p = $("chooser-error");
      p.textContent = msg || "";
      show(p, !!msg);
    }
    function openChooser() {
      chooserError("");
      show($("chooser-close"), !!reader);
      show(chooser, true);
      fetch("api/readers").then(function (r) {
        if (!r.ok) throw new Error(String(r.status));
        return r.json();
      }).then(renderReaders, function () {
        renderReaders([]);
        chooserError("Cannot reach the server to list the readers.");
      });
    }
    function closeChooser() { show(chooser, false); }
    function renderReaders(list) {
      var ul = $("reader-list");
      clear(ul);
      list.forEach(function (r) {
        var li = make("li");
        var b = make("button");
        b.type = "button";
        b.appendChild(make("span", "avatar", initial(r.name)));
        b.appendChild(make("span", "", r.name));
        b.addEventListener("click", function () { selectReader(r); });
        li.appendChild(b);
        ul.appendChild(li);
      });
    }
    function selectReader(r) {
      reader = { id: r.id, name: r.name };
      remember(reader);
      closeChooser();
      showAvatar();
      loadContinue();
    }
    function showAvatar() {
      var a = $("avatar");
      a.textContent = reader ? initial(reader.name) : "";
      a.setAttribute("aria-label", reader ? "Reading as " + reader.name + ". Switch reader" : "Switch reader");
      show(a, !!reader);
    }
    $("avatar").addEventListener("click", openChooser);
    $("chooser-close").addEventListener("click", closeChooser);
    document.addEventListener("keydown", function (e) {
      if (e.key === "Escape" && !chooser.hidden && reader) closeChooser();
    });
    $("add-reader").addEventListener("submit", function (e) {
      e.preventDefault();
      var input = $("reader-name");
      var name = input.value.trim();
      if (!name) return;
      chooserError("");
      postJson("api/readers", { name: name }).then(function (r) {
        if (r.status === 201) {
          return r.json().then(function (created) { input.value = ""; selectReader(created); });
        }
        if (r.status === 409) {
          // that reader exists: pick it
          return fetch("api/readers").then(function (l) { return l.json(); }).then(function (list) {
            var want = fold(name);
            var hit = list.filter(function (x) { return fold(x.name) === want; })[0];
            if (hit) selectReader(hit); else chooserError("A reader with that id already exists.");
          });
        }
        if (r.status === 400) { chooserError("Use a name with at least one letter (a-z) or digit."); return; }
        chooserError("Could not add the reader (" + r.status + ").");
      }, function () { chooserError("Cannot reach the server."); });
    });

    function startReaders() {
      reader = storedReader();
      showAvatar();
      fetch("api/readers").then(function (r) {
        if (!r.ok) throw new Error(String(r.status));
        return r.json();
      }).then(function (list) {
        if (reader && list.some(function (x) { return x.id === reader.id; })) return;
        reader = null;
        showAvatar();
        openChooser();
      }, function () {
        // offline: the remembered reader carries on; with none there is no one to ask
        if (!reader) openChooser();
      });
      if (reader) loadContinue();
      // positions saved while the server could not be reached: once sent, the
      // list shows them
      function sendQueued() {
        flushPositions().then(function (sent) { if (sent && reader) loadContinue(); });
      }
      sendQueued();
      window.addEventListener("online", sendQueued);
    }

    // ---- continue reading --------------------------------------------------------------

    var continueSeq = 0;
    function titleFromId(id) {
      var slug = id.slice(id.lastIndexOf("/") + 1).replace(/^[^:]*:/, "");
      return slug.replace(/[-_]+/g, " ");
    }
    function pathFromId(id) {
      var i = id.indexOf(":");
      return i < 0 ? null : "books/" + id.slice(0, i) + "/" + id.slice(i + 1) + "/index.html";
    }
    function loadContinue() {
      var mine = ++continueSeq;
      var box = $("continue"), ul = $("continue-list");
      clear(ul);
      show(box, false);
      if (!reader) return;
      fetch("api/readers/" + encodeURIComponent(reader.id) + "/positions").then(function (r) {
        if (!r.ok) throw new Error(String(r.status));
        return r.json();
      }).then(function (positions) {
        if (mine !== continueSeq) return;
        var items = Object.keys(positions || {}).map(function (id) {
          return { id: id, pos: positions[id] || {} };
        }).filter(function (it) {
          var colon = it.id.indexOf(":");
          return colon > 0 && libraryNamed(it.id.slice(0, colon)) && pathFromId(it.id);
        }).sort(function (a, b) {
          return Date.parse(b.pos.savedAt) - Date.parse(a.pos.savedAt);   // most recent first
        });
        items.forEach(function (it) {
          var lib = libraryNamed(it.id.slice(0, it.id.indexOf(":")));
          var li = make("li");
          var cover = newCover({ title: titleFromId(it.id), library: lib.name, colour: lib.colour,
                                 path: pathFromId(it.id) }, lib);
          var pct = Math.max(0, Math.min(100, Math.round(Number(it.pos.percent) || 0)));
          var bar = make("div", "progress");
          var fill = make("i");
          fill.style.width = pct + "%";
          bar.appendChild(fill);
          li.appendChild(cover);
          li.appendChild(bar);
          li.appendChild(make("span", "pct", pct + "% read"));
          li.setAttribute("data-book", it.id);
          ul.appendChild(li);
          it.li = li; it.cover = cover; it.lib = lib;
        });
        show(box, items.length > 0);
        resolveTitles(items, mine);
      }, function () { /* offline or no such reader: nothing to continue */ });
    }
    // The catalog's entry for each book, asked of the server by id (api/books):
    // its real title and cover; a book the catalog no longer has is not shown.
    function resolveTitles(items, mine) {
      booksUrls(items.map(function (it) { return it.id; })).forEach(function (url) {
        fetch(url).then(function (r) {
          if (!r.ok) throw new Error(String(r.status));
          return r.json();
        }).then(function (answer) {
          if (mine !== continueSeq) return;
          var books = answer && answer.books || {};
          var asked = new Set(new URL(url).searchParams.getAll("id"));
          items.forEach(function (it) {
            if (!asked.has(it.id)) return;
            var b = books[it.id];
            if (b && typeof b === "object") fillCover(it.cover, b, it.lib);
            else it.li.remove();   // not in the catalog (any more)
          });
          show($("continue"), $("continue-list").children.length > 0);
        }, function () { /* offline: keep the placeholders */ });
      });
    }

    // ---- views and tabs ------------------------------------------------------------------

    var allGrid = null, libraryGrid = null;
    var current = "home";
    function route() {
      var h;
      try { h = decodeURIComponent((location.hash || "#home").slice(1)); } catch (e) { h = "home"; }
      var parts = h.split("/");
      var view = ["home", "libraries", "search"].indexOf(parts[0]) >= 0 ? parts[0] : "home";
      var libName = view === "libraries" && parts.length > 1 ? parts.slice(1).join("/") : null;
      current = view;
      ["home", "libraries", "search"].forEach(function (v) {
        show($("view-" + v), v === view);
        if (v === view) $("tab-" + v).setAttribute("aria-current", "page");
        else $("tab-" + v).removeAttribute("aria-current");
      });
      var title = { home: "Library", libraries: "Libraries", search: "Search" }[view];
      if (libraryGrid) { libraryGrid.destroy(); libraryGrid = null; }
      var lib = libName && libraryNamed(libName);
      show($("library-list-wrap"), !(view === "libraries" && lib));
      show($("library-books"), view === "libraries" && !!lib);
      if (view === "libraries" && lib) {
        title = lib.name;
        $("library-name").textContent = lib.name + " (" + lib.books + ")";
        libraryGrid = Grid(document.querySelector('[data-grid="library"]'), [lib]);
      }
      $("screen-title").textContent = title;
      if (view === "home" && allGrid) allGrid.schedule();
      if (view === "search") $("search-input").focus();
    }
    window.addEventListener("hashchange", route);
    $("library-back").addEventListener("click", function () { location.hash = "#libraries"; });

    function renderLibraries() {
      var ul = $("library-list");
      clear(ul);
      libs.forEach(function (l) {
        var li = make("li");
        var b = make("button");
        b.type = "button";
        var sw = make("span", "swatch");
        setColour(sw, l.colour);
        var text = make("span");
        text.appendChild(make("span", "", l.name));
        text.appendChild(make("span", "sub", l.books + (l.books === 1 ? " book" : " books")));
        b.appendChild(sw);
        b.appendChild(text);
        b.addEventListener("click", function () { location.hash = "#libraries/" + encodeURIComponent(l.name); });
        li.appendChild(b);
        ul.appendChild(li);
      });
    }

    // ---- search ----------------------------------------------------------------------------

    // Pages on the device: those loaded in this session plus those kept in the cache.
    function devicePages() {
      var found = new Map();
      pageData.forEach(function (json, url) { found.set(url, json); });
      return cachePromise.then(function (c) {
        if (!c) return found;
        return c.keys().then(function (reqs) {
          var base = new URL("catalog/", document.baseURI).pathname;   // where this site keeps its catalog
          var inCatalog = function (r) {
            var path = new URL(r.url).pathname;
            return path.indexOf(base) === 0 && /^[^/]+\/\d+\.json$/.test(path.slice(base.length));
          };
          var wanted = reqs.filter(inCatalog);
          return Promise.all(wanted.map(function (r) {
            var rel = "catalog/" + new URL(r.url).pathname.slice(base.length);
            if (found.has(rel)) return null;
            return c.match(r).then(function (hit) { return hit ? hit.json() : null; })
              .then(function (json) { if (json) found.set(rel, json); }, function () {});
          }));
        }).then(function () { return found; }, function () { return found; });
      });
    }
    function localSearch(q) {
      var words = fold(q).split(/\s+/).filter(Boolean).slice(0, 16);
      return devicePages().then(function (found) {
        // the catalog's order: libraries as the index lists them, pages by number
        var order = function (u) {
          var m = /^catalog\/([^/]+)\/(\d+)\.json$/.exec(u) || [];
          var at = libs.findIndex(function (l) { return encodeURIComponent(l.name) === m[1]; });
          return [at < 0 ? libs.length : at, m[1] || "", Number(m[2]) || 0];
        };
        var urls = Array.from(found.keys()).sort(function (a, b) {
          var x = order(a), y = order(b);
          return x[0] - y[0] || (x[1] < y[1] ? -1 : x[1] > y[1] ? 1 : 0) || x[2] - y[2];
        });
        var hits = [];
        urls.forEach(function (u) {
          (found.get(u).books || []).forEach(function (b) {
            if (hits.length > SEARCH_LIMIT) return;
            var hay = fold([b.title, (b.authors || []).join(" "), b.library].join(" "));
            if (words.every(function (w) { return hay.indexOf(w) >= 0; })) hits.push(b);
          });
        });
        return { books: hits.slice(0, SEARCH_LIMIT), more: hits.length > SEARCH_LIMIT, pages: urls.length };
      });
    }
    function showResults(res, note) {
      var ul = $("search-results");
      clear(ul);
      res.books.forEach(function (b) {
        var li = make("li");
        var a = make("a");
        var href = bookHref(b.path);
        if (href) a.setAttribute("href", href);
        var sw = make("span", "swatch");
        setColour(sw, b.colour);
        var text = make("span");
        text.appendChild(make("span", "", b.title));
        var by = (b.authors || []).join(", ");
        text.appendChild(make("span", "sub", by ? by + " · " + b.library : b.library));
        a.appendChild(sw);
        a.appendChild(text);
        li.appendChild(a);
        ul.appendChild(li);
      });
      var msg = res.books.length ? "" : "No books found.";
      if (res.more) msg = "Showing the first " + SEARCH_LIMIT + " matches.";
      $("search-status").textContent = (note ? note + " " : "") + msg;
    }
    var searchSeq = 0, searchTimer = null;
    function runSearch() {
      var q = $("search-input").value.trim();
      var mine = ++searchSeq;
      if (!q) { showResults({ books: [] }, ""); $("search-status").textContent = ""; return; }
      fetch("api/search?q=" + encodeURIComponent(q)).then(function (r) {
        if (!r.ok) throw new Error(String(r.status));
        return r.json();
      }).then(function (res) {
        if (mine === searchSeq) { showResults(res, ""); $("search-status").setAttribute("data-mode", "online"); }
      }, function () {
        // offline: the catalog pages already on this device
        localSearch(q).then(function (res) {
          if (mine !== searchSeq) return;
          showResults(res, "Offline: searched the " + res.pages + " catalog page" + (res.pages === 1 ? "" : "s") + " on this device.");
          $("search-status").setAttribute("data-mode", "offline");
        });
      });
    }
    $("search-input").addEventListener("input", function () {
      clearTimeout(searchTimer);
      searchTimer = setTimeout(runSearch, 150);
    });

    // ---- start -------------------------------------------------------------------------------

    function message(text) {
      var m = $("message");
      m.textContent = text || "";
      show(m, !!text);
    }
    fetchCatalogJson("catalog/index.json").then(function (index) {
      setIndex(index);
      renderLibraries();
      allGrid = Grid(document.querySelector('[data-grid="all"]'), libs);
      if (!totalBooks) message("This site has no books yet.");
      route();
      startReaders();
    }, function () {
      message("Cannot load the catalog. Check the connection and reload.");
      startReaders();
    });
    route();
    document.documentElement.setAttribute("data-home", "ready");
  }
})();

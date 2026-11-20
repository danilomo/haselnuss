(ns ^:slow haselnuss.home-app-test
  "The reader app's home screen (TASK-274; doc-12, \"Reader experience\"):
  `resources/haselnuss/site/index.html`, `app.js` and `app.css` driven in a
  real headless Chrome, so the JavaScript runs for real instead of being
  read as text.

  Approach: a headless Chrome over the DevTools protocol
  (`haselnuss.test-browser`, shared with `haselnuss.reading-app-test`),
  evaluating JavaScript in the page and polling for the DOM it expects. The
  pages come from a real `serve/start!` over (1) the site fixture set built
  by `--build-site` and (2) a catalog-only site of 50,000 synthetic books
  (`haselnuss.site/catalog-files` over generated entries; no books, so no
  disk to speak of). Where no Chrome or Chromium is on this machine the
  tests print a note and pass without running: that is the only skip."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [haselnuss.serve :as serve]
            [haselnuss.site :as site]
            [haselnuss.test-browser :as tb :refer [cdp http-json js wait-for]])
  (:import (java.nio.file Files)))

(defn- open-page [b url]
  (cdp b "Page.navigate" {"url" url})
  (wait-for b (str url " home ready") "document.documentElement.getAttribute('data-home') === 'ready'"))

;; --- the sites -------------------------------------------------------------------------

(defn- synthetic-books
  "`n` catalog entries across six libraries, on shelves two or three deep."
  [n]
  (let [libs ["essays" "history" "letters" "manuals" "notes" "quotes"]]
    (for [i (range n)
          :let [lib (nth libs (mod i 6))
                shelf [(str "vol-" (mod (quot i 6) 7)) (str "part-" (mod (quot i 42) 5))]
                slug (format "b-%06d" i)]]
      {:id {:library lib :shelf shelf :slug slug}
       :title (str "Book " (format "%06d" i) " of " lib)
       :authors [(str "Author " (mod i 97))]
       :page (str "books/" lib "/" (str/join "/" shelf) "/" slug "/index.html")})))

(defn- catalog-only-site
  "A folder `serve/start!` accepts: the app files, `.haselnuss/state/` and the
  catalog of `n` synthetic books -- no book pages."
  [n]
  (let [dir (io/file (tb/temp-dir) "big")
        books (synthetic-books n)
        write! (fn [rel ^bytes bs] (let [f (io/file dir rel)] (io/make-parents f) (Files/write (.toPath f) bs ^"[Ljava.nio.file.OpenOption;" (make-array java.nio.file.OpenOption 0))))]
    (.mkdirs (io/file dir ".haselnuss" "state"))
    (doseq [[file-name bs] (site/app-file-bytes)] (write! file-name bs))
    (doseq [[rel bs] (site/catalog-files books ["essays" "history" "letters" "manuals" "notes" "quotes"])]
      (write! rel bs))
    dir))

;; --- tests -----------------------------------------------------------------------------

(deftest home-screen-smoke-test
  (tb/with-site-dir tb/build-fixture-site
    (fn [site-dir]
      (tb/call-with-browser "home-app-test"
                            (fn [b]
                              (let [srv (serve/start! {:dir (str site-dir) :port 0 :reload-interval-ms 0 :threads 4})
                                    base (:url srv)]
                                (try
                                  (is (= 201 (http-json "POST" (str base "api/readers") {"name" "Bia"})))
                                  (is (= 200 (http-json "PUT" (str base "api/readers/bia/positions/reformed%3Acatecismo-heidelberg")
                                                        {"savedAt" "2026-11-15T10:00:00Z" "percent" 40.2 "offset" 0.1
                                                         "id" "" "device" "test" "heading" {"index" 0 "text" "x"}})))
                                  (is (= 200 (http-json "PUT" (str base "api/readers/bia/positions/bible%3Ablivre%2Fromanos")
                                                        {"savedAt" "2026-11-16T10:00:00Z" "percent" 75 "offset" 0
                                                         "id" "" "device" "test" "heading" {"index" 0 "text" "x"}})))
                                  (testing "a malformed hash (a lone percent escape) does not stop the app starting"
                                    (cdp b "Page.navigate" {"url" (str base "#%E0%A4%A")})
                                    (wait-for b "home ready" "document.documentElement.getAttribute('data-home') === 'ready'")
                                    (is (= "Library" (js b "document.getElementById('screen-title').textContent")))
                                    (js b "location.hash = '#libraries'")
                                    (js b "location.hash = '#%E0%A4%A'")
                                    (wait-for b "falls back to home" "document.getElementById('screen-title').textContent === 'Library'"))
                                  (open-page b base)

                                  (testing "first launch asks who is reading, listing the server's readers"
                                    (is (wait-for b "chooser" "!document.getElementById('chooser').hidden && document.querySelectorAll('#reader-list button').length === 1"))
                                    (is (= "Bia" (js b "document.querySelector('#reader-list button').textContent.slice(1)"))))

                                  (testing "choosing a reader shows continue reading, most recent first, with percentages"
                                    (js b "document.querySelector('#reader-list button').click()")
                                    (wait-for b "continue list" "document.querySelectorAll('#continue-list li').length === 2")
                                    (is (js b "document.getElementById('chooser').hidden"))
                                    (is (= "B" (js b "document.getElementById('avatar').textContent")))
                                    (is (= ["75% read" "40% read"] (js b "Array.from(document.querySelectorAll('#continue-list .pct')).map(e => e.textContent)")))
                                    (wait-for b "resolved title" "document.querySelector('#continue-list [data-book=\"reformed:catecismo-heidelberg\"] .t').textContent.startsWith('Catecismo')"))

                                  (testing "the cover grid draws the fixture site's books, library name and colour on each"
                                    (wait-for b "covers" "document.querySelectorAll('[data-grid=all] .cover:not(.pending)').length > 0")
            ;; the grid starts below the fold of a phone: scroll to see all nine
                                    (js b "window.scrollTo(0, document.documentElement.scrollHeight)")
                                    (wait-for b "all covers" "document.querySelectorAll('[data-grid=all] .cover:not(.pending)').length === 9")
                                    (let [covers (js b "Array.from(document.querySelectorAll('[data-grid=all] .cover')).map(c => [c.querySelector('.t').textContent, c.querySelector('.l').textContent, c.getAttribute('href')])")]
                                      (is (= 9 (js b "Number(document.querySelector('[data-grid=all]').dataset.total)")))
                                      (is (= 9 (count covers)))
                                      (is (= "bible" (second (first covers))) "sorted library first")
                                      (is (some #(str/starts-with? (first %) "Catecismo de Heidelberg") covers))
                                      (is (every? #(str/starts-with? (nth % 2) "books/") covers)))
                                    (is (not= (js b "getComputedStyle(document.querySelector('[data-grid=all] .cover')).backgroundColor")
                                              (js b "getComputedStyle(document.querySelector('[data-grid=all] .cover:last-child')).backgroundColor"))
                                        "each library has its own colour")
                                    (js b "window.scrollTo(0, 0)"))

                                  (testing "the choice survives a reload"
                                    (open-page b base)
                                    (wait-for b "avatar" "document.getElementById('avatar').textContent === 'B'")
                                    (Thread/sleep 300)
                                    (is (js b "document.getElementById('chooser').hidden"))
                                    (wait-for b "continue after reload" "document.querySelectorAll('#continue-list li').length === 2"))

                                  (testing "adding a reader by name, then switching; names are text, never markup"
                                    (js b "document.getElementById('avatar').click()")
                                    (wait-for b "chooser again" "!document.getElementById('chooser').hidden && document.querySelectorAll('#reader-list button').length === 1")
                                    (js b "(() => { const i = document.getElementById('reader-name'); i.value = '<i>Zed</i> Ana'; document.getElementById('add-reader').requestSubmit(); })()")
                                    (wait-for b "new reader chosen" "document.getElementById('chooser').hidden && document.getElementById('avatar').textContent === '<'")
                                    (is (= 0 (js b "document.getElementById('continue-list').children.length")) "a new reader has nothing to continue")
                                    (open-page b base)
                                    (wait-for b "avatar" "document.getElementById('avatar').textContent === '<'")
                                    (is (= "<i>Zed</i> Ana" (js b "JSON.parse(localStorage.getItem('haselnuss.reader')).name")))
                                    (js b "document.getElementById('avatar').click()")
                                    (wait-for b "two readers" "document.querySelectorAll('#reader-list button').length === 2")
                                    (is (= 0 (js b "document.querySelectorAll('#reader-list i, #avatar i').length")) "no markup from a name")
                                    (js b "Array.from(document.querySelectorAll('#reader-list button')).find(x => x.textContent.endsWith('Bia')).click()")
                                    (wait-for b "switched" "document.getElementById('avatar').textContent === 'B'"))

                                  (testing "the Libraries tab lists libraries and filters the grid to one"
                                    (js b "location.hash = '#libraries'")
                                    (wait-for b "library list" "document.querySelectorAll('#library-list li').length === 2")
                                    (js b "document.querySelectorAll('#library-list button')[1].click()")
                                    (wait-for b "library grid" "document.querySelectorAll('[data-grid=library] .cover:not(.pending)').length === 4")
                                    (is (= ["reformed"] (js b "Array.from(new Set(Array.from(document.querySelectorAll('[data-grid=library] .cover .l')).map(e => e.textContent)))"))))

                                  (testing "search finds books online, through /api/search"
                                    (js b "location.hash = '#search'")
                                    (js b "(() => { const i = document.getElementById('search-input'); i.value = 'heidelberg'; i.dispatchEvent(new Event('input')); })()")
                                    (wait-for b "online results" "document.querySelectorAll('#search-results li').length === 1 && document.getElementById('search-status').dataset.mode === 'online'")
                                    (is (str/starts-with? (js b "document.querySelector('#search-results li').textContent") "Catecismo de Heidelberg"))
                                    (is (< 0 (js b "caches.open('haselnuss-catalog').then(c => c.keys()).then(k => k.length)")) "catalog pages are kept on the device"))

                                  (testing "offline, search covers the catalog pages already on the device"
                                    (serve/stop! srv)
                                    (js b "(() => { const i = document.getElementById('search-input'); i.value = 'catkins'; i.dispatchEvent(new Event('input')); })()")
                                    (wait-for b "offline results" "document.getElementById('search-status').dataset.mode === 'offline' && document.querySelectorAll('#search-results li').length === 1")
                                    (is (str/includes? (js b "document.getElementById('search-status').textContent") "Offline"))
                                    (is (= "Catkins" (js b "document.querySelector('#search-results li .swatch + span > span').textContent"))))
                                  (finally (try (serve/stop! srv) (catch Exception _ nil))))))))))

(deftest fifty-thousand-book-grid-test
  (tb/with-site-dir #(catalog-only-site 50000)
    (fn [dir]
      (tb/call-with-browser "home-app-test"
                            (fn [b]
                              (tb/with-server dir
                                (fn [srv]
                                  (let [base (:url srv)]
                                    (http-json "POST" (str base "api/readers") {"name" "Cai"})
                                    ;; a book in the middle of a library's 17 catalog pages
                                    (is (= 200 (http-json "PUT" (str base "api/readers/cai/positions/history%3Avol-3%2Fpart-0%2Fb-049999")
                                                          {"savedAt" "2026-11-16T10:00:00Z" "percent" 12})))
                                    (cdp b "Page.navigate" {"url" base})
                                    (wait-for b "home" "document.documentElement.getAttribute('data-home') === 'ready'")
                                    (js b "localStorage.setItem('haselnuss.reader', JSON.stringify({id: 'cai', name: 'Cai'}))")
                                    (open-page b base)
                                    (wait-for b "grid" "document.querySelectorAll('[data-grid=all] .cover:not(.pending)').length > 0")
                                    (let [drawn (fn [] (js b "document.querySelectorAll('[data-grid=all] .cover').length"))
                                          pages (fn [] (js b "performance.getEntriesByType('resource').filter(r => r.name.includes('/catalog/') && r.name.endsWith('.json')).length"))]
                                      (testing "continue reading names a book by asking the server for it, not by reading catalog pages (TASK-276)"
                                        (wait-for b "continue title" "(document.querySelector('#continue-list .t') || {}).textContent === 'Book 049999 of history'")
                                        (is (<= (pages) 3) (str "catalog pages fetched: " (pages)))
                                        (is (= 1 (js b "performance.getEntriesByType('resource').filter(r => r.name.includes('/api/books?')).length"))))
                                      (testing "the grid is the whole catalog, drawn a screen at a time"
                                        (is (= 50000 (js b "Number(document.querySelector('[data-grid=all]').dataset.total)")))
                                        (is (< 0 (drawn) 60) (str "drawn at the top: " (drawn)))
                                        (println "home-app-test: first contentful paint (ms):"
                                                 (js b "Math.round(performance.getEntriesByName('first-contentful-paint')[0].startTime)")
                                                 "covers drawn at the top:" (drawn)))
                                      (testing "scrolling anywhere keeps the DOM bounded and fills the covers in"
                                        (doseq [frac [0.5 0.1 0.93 1.0]]
                                          (js b (str "window.scrollTo(0, (document.documentElement.scrollHeight - innerHeight) * " frac ")"))
                                          (wait-for b (str "covers at " frac)
                                                    "(() => { const cs = Array.from(document.querySelectorAll('[data-grid=all] .cover')); const vis = cs.filter(c => { const r = c.getBoundingClientRect(); return r.bottom > 0 && r.top < innerHeight; }); return vis.length > 0 && vis.every(c => !c.classList.contains('pending')); })()")
                                          (is (< 0 (drawn) 80) (str "drawn at " frac ": " (drawn)))
                                          (is (= (drawn) (js b "document.querySelectorAll('[data-grid=all] .grid > *').length")) "nothing else piles up"))
                                        (is (str/includes? (js b "Array.from(document.querySelectorAll('[data-grid=all] .cover .t')).pop().textContent") "Book ")
                                            "the last cover is a real one")
                                        (is (< (pages) 10) (str "only the pages that were on screen were fetched: " (pages)))
                                        (println "home-app-test: 50,000 books, catalog pages fetched after 4 jumps:" (pages)
                                                 "DOM nodes:" (js b "document.getElementsByTagName('*').length")))
                                      (testing "a long smooth scroll: frame cost stays low"
                                        (js b "window.scrollTo(0, 0)")
                                        (let [ms (js b "new Promise(res => { let n = 0; const t0 = performance.now(); const max = document.documentElement.scrollHeight - innerHeight; (function step() { window.scrollTo(0, (n++ / 120) * max / 4); if (n < 120) requestAnimationFrame(step); else res((performance.now() - t0) / 120); })(); })")]
                                          (println "home-app-test: mean ms per scrolled frame over 120 frames:" (Math/round (double ms)))
                                          (is (< ms 100) "no frame costs anywhere near a visible stall"))
                                        (is (< (drawn) 80))))))))))))

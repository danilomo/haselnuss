(ns ^:slow haselnuss.positions-app-test
  "Where each reader left off (TASK-276; doc-12, \"Where you left off\",
  \"Offline\"): the reading screen saves a position to `--serve` and puts
  it back on opening, driven in real headless Chromes
  (`haselnuss.test-browser`) -- one Chrome per device, each with its own
  profile, so its own storage -- over a real `serve/start!` of a site
  `--build-site` built from books written here.

  The books are plain filler under numbered chapters, each paragraph
  starting with its own tag (`P6-3`), so a test can ask which paragraph is
  at the reading line on any screen size. Without Chrome the tests print a
  note and pass without running."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [haselnuss.cli :as cli]
            [haselnuss.test-browser :as tb :refer [cdp http-json js wait-for]])
  (:import (java.io File StringWriter)))

;; --- the books -------------------------------------------------------------------------------

(def ^:private filler
  (str "Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt"
       " ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis nostrud exercitation ullamco"
       " laboris nisi ut aliquip ex ea commodo consequat."))

(defn- book-text
  "A book of eight chapters of six paragraphs. `:labels?` gives each chapter
  a label (an id on the page); `:duplicated` repeats a block of paragraphs
  in chapter 1 by mistake (`D1-k`); `:headings` overrides chapter titles."
  [title & {:keys [labels? duplicated headings]}]
  (str "---\ntitle: " title "\n---\n\n"
       (str/join "\n\n"
                 (for [c (range 1 9)]
                   (str "# Chapter " c ": " (get headings c (str "Part " c))
                        (when labels? (str " {#sec:c" c "}"))
                        "\n\n"
                        (str/join "\n\n" (for [p (range 1 7)] (str "P" c "-" p " " filler)))
                        (when (and (= 1 c) duplicated)
                          (str "\n\n" (str/join "\n\n" (for [k (range 1 (inc (long duplicated)))]
                                                         (str "D1-" k " " filler " " filler))))))))
       "\n"))

(defn- write! [^File root rel content]
  (let [f (io/file root rel)]
    (io/make-parents f)
    (spit f content)
    f))

(defn- build!
  "Build `root`/src into `root`/site (incrementally when it exists); what
  the build printed."
  [^File root]
  (let [out (StringWriter.) err (StringWriter.)]
    (binding [*out* out *err* err]
      (cli/main* ["--libraries" (str (io/file root "src/libraries.yaml")) "--build-site" (str (io/file root "site"))]
                 {:library-env {} :library-home "/nonexistent/haselnuss-test-home"
                  :haselnuss.cli/check-base-dir (.getCanonicalPath (io/file root "src"))
                  :haselnuss.cli/site-threads 2}))
    (str out err)))

(defn- write-books!
  "The first edition of the three books: each has a block in chapter 1
  that was pasted twice, and two chapter titles with typos."
  [^File root]
  (write! root "src/libraries.yaml" "- name: lib\n  type: filesystem\n  path: lib\n")
  (write! root "src/lib/plain.hdoc" (book-text "Plain" :duplicated 30 :headings {2 "Teh start"}))
  (write! root "src/lib/labelled.hdoc" (book-text "Labelled" :labels? true :duplicated 30 :headings {2 "Teh start"}))
  (write! root "src/lib/retitled.hdoc" (book-text "Retitled" :duplicated 30 :headings {6 "Teh end"})))

(defn- site-of-books []
  (let [root (tb/temp-dir)]
    (write-books! root)
    (build! root)
    (io/file root "site")))

(defn- site-of
  "A site of the library `lib` holding `books`, `{slug text}`."
  [books]
  (let [root (tb/temp-dir)]
    (write! root "src/libraries.yaml" "- name: lib\n  type: filesystem\n  path: lib\n")
    (doseq [[slug text] books] (write! root (str "src/lib/" slug ".hdoc") text))
    (build! root)
    (io/file root "site")))

(defn- long-section-text
  "Chapters 1-3 of six paragraphs, then a long chapter 4 of 24 whose
  paragraphs 19-20 sit in an admonition labelled `label`, then chapter 5."
  [label]
  (let [para (fn [c p] (str "P" c "-" p " " filler))]
    (str "---\ntitle: Relabelled\n---\n\n"
         (str/join "\n\n"
                   (for [c (range 1 6)]
                     (str "# Chapter " c ": Part " c "\n\n"
                          (if (= 4 c)
                            (str (str/join "\n\n" (map #(para 4 %) (range 1 19)))
                                 "\n\n:::{admonition #" label "}\n"
                                 (para 4 19) "\n\n" (para 4 20) "\n:::\n\n"
                                 (str/join "\n\n" (map #(para 4 %) (range 21 25))))
                            (str/join "\n\n" (map #(para c %) (range 1 7)))))))
         "\n")))

(defn- footnoted-text
  "Two chapters whose paragraphs carry long footnotes, so the endnotes are
  several screens long."
  []
  (str "---\ntitle: Footnoted\n---\n\n"
       (str/join "\n\n" (for [c [1 2]]
                          (str "# Chapter " c "\n\n"
                               (str/join "\n\n" (for [p (range 1 6)] (str "P" c "-" p " " filler "[^n" c p "]"))))))
       "\n\n"
       (str/join "\n\n" (for [c [1 2] p (range 1 6)]
                          (str "[^n" c p "]: N" c "-" p " " filler " " filler " " filler)))
       "\n"))

;; --- the page --------------------------------------------------------------------------------

(def ^:private line-js
  "The reading line on screen: just under the running header."
  "(document.querySelector('.hz-running').getBoundingClientRect().bottom + 8)")

(def ^:private paragraphs-js
  "Array.from(document.querySelectorAll('p')).filter(p => !p.closest('#hz-reader'))")

(defn- para-at-line
  "The tag of the paragraph the reading line crosses, or nil."
  [b]
  (js b (str "(() => { const line = " line-js "; const p = " paragraphs-js ".find(p => {"
             " const r = p.getBoundingClientRect(); return r.top <= line && r.bottom > line; });"
             " return p ? p.textContent.split(' ')[0] : null; })()")))

(defn- distance-to
  "How far, in px, the point `frac` into the paragraph `tag` is from the
  reading line."
  [b tag frac]
  (js b (str "(() => { const p = " paragraphs-js ".find(p => p.textContent.startsWith(" (json/write-str (str tag " ")) "));"
             " const r = p.getBoundingClientRect(); return r.top + " frac " * r.height - " line-js "; })()")))

(defn- scroll-to!
  "Scroll so the point `frac` into the paragraph `tag` is at the reading line."
  [b tag frac]
  (js b (str "window.scrollTo(0, window.scrollY + " (distance-to b tag frac) ")"))
  (is (< (Math/abs (double (distance-to b tag frac))) 2) (str "scrolled to " tag)))

(defn- as-reader!
  "Make `id` the reader on this device, as the home screen's chooser does."
  [b base id]
  (cdp b "Page.navigate" {"url" base})
  (wait-for b "home" "document.documentElement.getAttribute('data-home') === 'ready'")
  (js b (str "localStorage.setItem('haselnuss.reader', JSON.stringify({id: " (json/write-str id)
             ", name: " (json/write-str id) "}))"))
  ;; the home screen creates the position store on a device's first visit; a
  ;; page left while that is under way can hold the next page's open up (the
  ;; app gives up on it after a while, but then has no queue)
  (wait-for b "the position store" "indexedDB.databases().then(ds => ds.some(d => d.name === 'haselnuss-positions' && d.version === 1))"))

(defn- open-book!
  "Open `url` and wait until the saved position, if any, has been put back;
  how it was found (`data-position`). Always a fresh load: a navigation
  that changes only the #hash would stay in the page."
  [b url]
  (cdp b "Page.navigate" {"url" "about:blank"})
  (wait-for b "blank" "location.href === 'about:blank'")
  (cdp b "Page.navigate" {"url" url})
  (wait-for b (str url " position") "document.documentElement.getAttribute('data-position-ready') === 'true'")
  (js b "document.documentElement.getAttribute('data-position')"))

(defn- server-position
  "The position the server holds for `reader` and `book`, or nil."
  [base reader book]
  (get (json/read-str (tb/http-get (str base "api/readers/" reader "/positions/" book))) "position"))

(defn- wait-server-position
  "Poll the server until `(pred position)` for `reader` and `book` (up to
  15 s); the position, or nil after failing the test."
  [base reader book what pred]
  (let [deadline (+ (System/currentTimeMillis) 15000)]
    (loop []
      (let [p (server-position base reader book)]
        (cond (and p (pred p)) p
              (> (System/currentTimeMillis) deadline) (do (is false (str "timed out waiting for " what)) nil)
              :else (do (Thread/sleep 100) (recur)))))))

(defn- with-tablet
  "`(f b)` in a second Chrome, a tablet-sized one: another device."
  [f]
  (tb/call-with-browser "positions-app-test"
                        (fn [b]
                          (cdp b "Emulation.setDeviceMetricsOverride" {"width" 820 "height" 1180 "deviceScaleFactor" 2 "mobile" true})
                          (f b))))

;; --- AC #1 and #3: across devices, one position per reader --------------------------------------

(deftest positions-follow-each-reader-across-devices-test
  (tb/with-site-dir site-of-books
    (fn [dir]
      (tb/call-with-browser
       "positions-app-test"
       (fn [phone]
         (tb/with-server dir
           (fn [srv]
             (let [base (:url srv)
                   book (str base "books/lib/plain/index.html")]
               (is (= 201 (http-json "POST" (str base "api/readers") {"name" "Ana"})))
               (is (= 201 (http-json "POST" (str base "api/readers") {"name" "Bia"})))

               (testing "on the phone, Ana's place is saved a couple of seconds after scrolling stops"
                 (as-reader! phone base "ana")
                 (is (= "none" (open-book! phone book)) "nothing saved yet: the book opens at the top")
                 (is (= 0 (js phone "window.scrollY")))
                 (scroll-to! phone "P5-3" 0.5)
                 (Thread/sleep 1000)
                 (is (nil? (server-position base "ana" "lib:plain")) "not while the reader may still be scrolling")
                 (let [p (wait-server-position base "ana" "lib:plain" "Ana's save" some?)]
                   (is (= {"index" 4 "text" "Chapter 5: Part 5"} (get p "heading")))
                   (is (nil? (get p "id")) "the plain book has no labels, so no id")
                   (is (< 0 (get p "offset") 1))
                   (is (< 40 (get p "percent") 95))
                   (is (str/starts-with? (str (get p "device")) "Linux"))))

               (with-tablet
                 (fn [tablet]
                   (testing "on a tablet, Ana's book opens within a screen of the same spot (AC #1)"
                     (as-reader! tablet base "ana")
                     (is (= "heading" (open-book! tablet book)))
                     (let [d (distance-to tablet "P5-3" 0.5)
                           screen (js tablet "innerHeight")]
                       (is (< (Math/abs (double d)) screen) (str "the spot is " d " px from the reading line"))
                       (is (= "P5-3" (para-at-line tablet)))))

                   (testing "Bia on the tablet has her own place, and saving it leaves Ana's alone (AC #3)"
                     (as-reader! tablet base "bia")
                     (is (= "none" (open-book! tablet book)))
                     (scroll-to! tablet "P2-4" 0.3)
                     (let [p (wait-server-position base "bia" "lib:plain" "Bia's save" some?)]
                       (is (= 1 (get-in p ["heading" "index"]))))
                     (is (= 4 (get-in (server-position base "ana" "lib:plain") ["heading" "index"])))
                     (let [files (fn [id] (get (json/read-str (slurp (io/file dir "readers" (str id ".json")))) "positions"))]
                       (is (= ["lib:plain"] (keys (files "ana"))))
                       (is (= ["lib:plain"] (keys (files "bia"))))
                       (is (not= (get-in (files "ana") ["lib:plain" "heading"])
                                 (get-in (files "bia") ["lib:plain" "heading"])))))

                   (testing "back on the phone, Ana is where she left off, not where Bia is"
                     (is (= "heading" (open-book! phone book)))
                     (is (= "P5-3" (para-at-line phone)))
                     (is (< (Math/abs (double (distance-to phone "P5-3" 0.5))) 30)))

                   (testing "and Ana reading further on the tablet moves her place on the phone too"
                     (as-reader! tablet base "ana")
                     (open-book! tablet book)
                     (scroll-to! tablet "P6-2" 0.5)
                     (wait-server-position base "ana" "lib:plain" "Ana's second save" #(= 5 (get-in % ["heading" "index"])))
                     (is (= "heading" (open-book! phone book)))
                     (is (= "P6-2" (para-at-line phone))))

                   (testing "opening at a passage (#hash) does not jump away to the saved place"
                     (is (= "skipped" (open-book! phone (str book "#sec:none"))))
                     (is (= 0 (js phone "window.scrollY"))))

                   (testing "a reader that is not chosen saves nothing"
                     (js phone "localStorage.removeItem('haselnuss.reader')")
                     (is (= "off" (open-book! phone book))))))))))))))

;; --- AC #2: a rebuild that fixed typos earlier in the book ----------------------------------------

(deftest positions-survive-a-rebuild-test
  (tb/with-site-dir site-of-books
    (fn [dir]
      (let [root (.getParentFile ^File dir)]
        (tb/call-with-browser
         "positions-app-test"
         (fn [b]
           (tb/with-server dir
             (fn [srv]
               (let [base (:url srv)
                     url #(str base "books/lib/" % "/index.html")
                     books ["plain" "labelled" "retitled"]]
                 (is (= 201 (http-json "POST" (str base "api/readers") {"name" "Ana"})))
                 (as-reader! b base "ana")
                 (doseq [book books]
                   (open-book! b (url book))
                   (scroll-to! b "P6-3" 0.4)
                   (wait-server-position base "ana" (str "lib:" book) (str book " saved") some?))
                 (is (= "sec:c6" (get (server-position base "ana" "lib:labelled") "id"))
                     "in the labelled book the place is the chapter's label")

                 (testing "the second edition: the pasted-twice block gone, the typos fixed, rebuilt incrementally"
                   (write! root "src/lib/plain.hdoc" (book-text "Plain" :headings {2 "The start"}))
                   (write! root "src/lib/labelled.hdoc" (book-text "Labelled" :labels? true :headings {2 "The start"}))
                   (write! root "src/lib/retitled.hdoc" (book-text "Retitled" :headings {6 "The end"}))
                   (let [out (build! root)]
                     (is (str/includes? out "3 of 3 books rebuilt") out)
                     (is (not (str/includes? out "removed")) out))
                   (is (not (str/includes? (slurp (io/file dir "books/lib/plain/index.html")) "D1-1"))))

                 (doseq [[book how] [["plain" "heading"] ["labelled" "id"] ["retitled" "order"]]]
                   (testing (str book ": the saved place lands on the same section and paragraph, found by " how)
                     (let [saved (server-position base "ana" (str "lib:" book))]
                       (is (= how (open-book! b (url book))))
                       (is (= "P6-3" (para-at-line b)))
                       (is (< (Math/abs (double (distance-to b "P6-3" 0.4))) 60))
                       (testing "(the percentage alone would have landed elsewhere)"
                         (js b (str "window.scrollTo(0, " (get saved "percent") " / 100 * (document.documentElement.scrollHeight - innerHeight))"))
                         (is (not (str/starts-with? (str (para-at-line b)) "P6-")) (para-at-line b)))))))))))))))

;; --- AC #4: offline saves are queued and sent on reconnecting ------------------------------------

(def ^:private queued-js
  "The records of this device's position queue: `[[key pending] ...]`."
  (str "new Promise(res => { const r = indexedDB.open('haselnuss-positions');"
       " r.onsuccess = () => { const q = r.result.transaction('positions').objectStore('positions').getAll();"
       " q.onsuccess = () => { res(q.result.map(x => [x.key, x.pending])); r.result.close(); }; };"
       " r.onerror = () => res(null); })"))

(defn- offline! [b on?]
  (cdp b "Network.emulateNetworkConditions" {"offline" on? "latency" 0 "downloadThroughput" -1 "uploadThroughput" -1}))

(deftest offline-positions-reach-the-server-test
  (tb/with-site-dir site-of-books
    (fn [dir]
      (tb/call-with-browser
       "positions-app-test"
       (fn [b]
         (tb/with-server dir
           (fn [srv]
             (let [base (:url srv)
                   book (str base "books/lib/plain/index.html")
                   stored #(get-in (json/read-str (slurp (io/file dir "readers" "ana.json"))) ["positions" "lib:plain"])]
               (is (= 201 (http-json "POST" (str base "api/readers") {"name" "Ana"})))
               (as-reader! b base "ana")
               (open-book! b book)
               (cdp b "Network.enable" {})
               (testing "a save with no connection is kept on the device, pending"
                 (offline! b true)
                 (wait-for b "offline" "navigator.onLine === false")
                 (scroll-to! b "P4-2" 0.5)
                 (wait-for b "a save" "document.documentElement.getAttribute('data-position-saves') === '1'")
                 (wait-for b "queued" (str queued-js ".then(q => q && q.length === 1 && q[0][1] === true)"))
                 (Thread/sleep 500)
                 (is (nil? (stored)) "the server has nothing yet"))
               (testing "a later save offline replaces it: one record per reader and book"
                 (scroll-to! b "P4-5" 0.5)
                 (wait-for b "a second save" "document.documentElement.getAttribute('data-position-saves') === '2'")
                 (Thread/sleep 300)
                 (is (= [["ana\nlib:plain" true]] (js b queued-js))))
               (testing "when the connection returns the newest one reaches the server, and is no longer pending"
                 (offline! b false)
                 (wait-for b "sent" (str queued-js ".then(q => q && q.length === 1 && q[0][1] === false)"))
                 (let [p (stored)]
                   (is (= 3 (get-in p ["heading" "index"])))
                   (is (= "Chapter 4: Part 4" (get-in p ["heading" "text"])))
                   (is (< (Math/abs (- (.toEpochMilli (java.time.Instant/parse (get p "savedAt")))
                                       (System/currentTimeMillis)))
                          60000))))
               (testing "an older save sent late (a device offline for a while) does not replace a newer one"
                 (http-json "PUT" (str base "api/readers/ana/positions/lib:plain")
                            {"savedAt" (str (.plus (java.time.Instant/now) (java.time.Duration/ofMinutes 5)))
                             "percent" 90 "heading" {"index" 7 "text" "Chapter 8: Part 8"} "offset" 0.1})
                 (offline! b true)
                 (wait-for b "offline again" "navigator.onLine === false")
                 (scroll-to! b "P3-1" 0.5)
                 (wait-for b "a third save" "document.documentElement.getAttribute('data-position-saves') === '3'")
                 (offline! b false)
                 (wait-for b "answered" (str queued-js ".then(q => q && q.length === 1 && q[0][1] === false)"))
                 (is (= 7 (get-in (stored) ["heading" "index"])) "the newer savedAt wins")
                 (is (= "heading" (open-book! b book)))
                 (is (= "P8-1" (para-at-line b)) "and opening the book goes to it"))))))))))

(deftest positions-work-when-the-device-store-never-answers-test
  (tb/with-site-dir site-of-books
    (fn [dir]
      (tb/call-with-browser
       "positions-app-test"
       (fn [b]
         ;; an IndexedDB open that never succeeds nor fails
         (cdp b "Page.enable" {})
         (cdp b "Page.addScriptToEvaluateOnNewDocument"
              {"source" "IDBFactory.prototype.open = function () { return {}; };"})
         (tb/with-server dir
           (fn [srv]
             (let [base (:url srv)
                   book (str base "books/lib/plain/index.html")]
               (is (= 201 (http-json "POST" (str base "api/readers") {"name" "Ana"})))
               (cdp b "Page.navigate" {"url" base})
               (wait-for b "home" "document.documentElement.getAttribute('data-home') === 'ready'")
               (js b "localStorage.setItem('haselnuss.reader', JSON.stringify({id: 'ana', name: 'Ana'}))")
               (is (= "none" (open-book! b book)))
               (testing "a save goes straight to the server"
                 (scroll-to! b "P3-2" 0.5)
                 (wait-server-position base "ana" "lib:plain" "the save" some?))
               (testing "and the saved place is put back from the server alone"
                 (is (= "heading" (open-book! b book)))
                 (is (= "P3-2" (para-at-line b))))))))))))

(deftest a-wrong-device-clock-is-corrected-test
  (tb/with-site-dir site-of-books
    (fn [dir]
      (tb/call-with-browser
       "positions-app-test"
       (fn [b]
         ;; this device's clock is two days fast: past the server's one-day bound
         (cdp b "Page.enable" {})
         (cdp b "Page.addScriptToEvaluateOnNewDocument"
              {"source" (str "(() => { const D = Date, shift = 2 * 86400000;"
                             " function F(...a) { return a.length ? new D(...a) : new D(D.now() + shift); }"
                             " F.prototype = D.prototype; F.now = () => D.now() + shift; F.parse = D.parse; F.UTC = D.UTC;"
                             " Date = F; })()")})
         (tb/with-server dir
           (fn [srv]
             (let [base (:url srv)
                   book (str base "books/lib/plain/index.html")
                   saved-ms #(some-> (server-position base "ana" "lib:plain") (get "savedAt") java.time.Instant/parse .toEpochMilli)
                   close? #(< (Math/abs (- (long %) (System/currentTimeMillis))) 120000)]
               (is (= 201 (http-json "POST" (str base "api/readers") {"name" "Ana"})))
               (as-reader! b base "ana")
               (open-book! b book)
               (is (> (js b "Date.now() - performance.timeOrigin") 86400000) "the page sees the fast clock")
               (testing "the clock is measured against the server's answer, so a save is dated by the server's clock"
                 (scroll-to! b "P3-3" 0.5)
                 (wait-server-position base "ana" "lib:plain" "first save" some?)
                 (is (close? (saved-ms))))
               (testing "with no measurement yet, the server's refusal measures it and the same save is sent again"
                 (js b "localStorage.removeItem('haselnuss.clockSkew')")
                 (scroll-to! b "P5-5" 0.5)
                 (wait-server-position base "ana" "lib:plain" "second save" #(= 4 (get-in % ["heading" "index"])))
                 (is (close? (saved-ms)))
                 (is (< (js b "Number(localStorage.getItem('haselnuss.clockSkew'))") -86400000))
                 (wait-for b "nothing pending" (str queued-js ".then(q => q && q.every(r => r[1] === false))")))))))))))

;; --- the TASK-276 review's fix round -------------------------------------------------------------

(defn- fast-clock!
  "From the next page on, this device's clock runs `ms` ahead."
  [b ms]
  (cdp b "Page.enable" {})
  (cdp b "Page.addScriptToEvaluateOnNewDocument"
       {"source" (str "(() => { const D = Date, shift = " ms ";"
                      " function F(...a) { return a.length ? new D(...a) : new D(D.now() + shift); }"
                      " F.prototype = D.prototype; F.now = () => D.now() + shift; F.parse = D.parse; F.UTC = D.UTC;"
                      " Date = F; })()")}))

(deftest a-renamed-label-falls-back-near-the-spot-test
  (tb/with-site-dir #(site-of {"relabelled" (long-section-text "adm:late")})
    (fn [dir]
      (let [root (.getParentFile ^File dir)]
        (tb/call-with-browser
         "positions-app-test"
         (fn [b]
           (tb/with-server dir
             (fn [srv]
               (let [base (:url srv)
                     book (str base "books/lib/relabelled/index.html")]
                 (is (= 201 (http-json "POST" (str base "api/readers") {"name" "Ana"})))
                 (as-reader! b base "ana")
                 (open-book! b book)
                 (scroll-to! b "P4-19" 0.5)
                 (let [p (wait-server-position base "ana" "lib:relabelled" "the save" some?)]
                   (is (= "adm:late" (get p "id")) "the place is the label of the block near the end of the section")
                   (is (= 3 (get-in p ["heading" "index"]))))
                 (write! root "src/lib/relabelled.hdoc" (long-section-text "adm:later"))
                 (is (str/includes? (build! root) "1 of 1 book rebuilt"))
                 (testing "with the label gone, the heading's section is used, and the place in it is the percentage's"
                   (is (= "heading" (open-book! b book)))
                   (is (contains? #{"P4-18" "P4-19" "P4-20"} (para-at-line b)) (para-at-line b))
                   (is (< (Math/abs (double (distance-to b "P4-19" 0.5))) (js b "innerHeight")))))))))))))

(deftest a-fast-clock-opened-at-a-passage-test
  (tb/with-site-dir site-of-books
    (fn [dir]
      (tb/call-with-browser
       "positions-app-test"
       (fn [b]
         ;; six hours fast: inside the server's one-day bound, so it would be accepted
         (fast-clock! b (* 6 3600000))
         (tb/with-server dir
           (fn [srv]
             (let [base (:url srv)
                   book (str base "books/lib/labelled/index.html")]
               (is (= 201 (http-json "POST" (str base "api/readers") {"name" "Ana"})))
               (as-reader! b base "ana")
               (is (nil? (js b "localStorage.getItem('haselnuss.clockSkew')")) "nothing measured yet")
               (is (= "skipped" (open-book! b (str book "#sec:c3"))))
               (is (< (Math/abs (double (js b "document.getElementById('sec:c3').getBoundingClientRect().top"))) 100)
                   "the page is at the passage")
               (testing "a look at the passage that moves less than a screen saves nothing"
                 (js b "window.scrollBy(0, innerHeight * 0.6)")
                 (Thread/sleep 3000)
                 (is (nil? (js b "document.documentElement.getAttribute('data-position-saves')")))
                 (is (nil? (server-position base "ana" "lib:labelled"))))
               (testing "reading on more than a screen saves, dated by the server's clock"
                 (scroll-to! b "P4-3" 0.5)
                 (let [p (wait-server-position base "ana" "lib:labelled" "the save" some?)
                       at (.toEpochMilli (java.time.Instant/parse (get p "savedAt")))]
                   (is (< (Math/abs (- at (System/currentTimeMillis))) 120000) (get p "savedAt"))
                   (is (= "sec:c4" (get p "id"))))
                 (is (< (js b "Number(localStorage.getItem('haselnuss.clockSkew'))") (* -5 3600000))))
               (testing "leaving the page after a look does not save either"
                 (open-book! b (str book "#sec:c7"))
                 (js b "window.scrollBy(0, innerHeight * 0.5)")
                 (open-book! b (str base "books/lib/plain/index.html"))
                 (Thread/sleep 500)
                 (is (= "sec:c4" (get (server-position base "ana" "lib:labelled") "id"))))))))))))

(deftest a-save-the-server-fails-on-does-not-hold-up-others-test
  (tb/with-site-dir site-of-books
    (fn [dir]
      (tb/call-with-browser
       "positions-app-test"
       (fn [b]
         (tb/with-server dir
           (fn [srv]
             (let [base (:url srv)
                   book (str base "books/lib/plain/index.html")]
               (is (= 201 (http-json "POST" (str base "api/readers") {"name" "Ana"})))
               (is (= 201 (http-json "POST" (str base "api/readers") {"name" "Bia"})))
               ;; Ana's file cannot be read: every PUT for her answers 500
               (spit (io/file dir "readers" "ana.json") "{not json")
               (as-reader! b base "ana")
               (open-book! b book)
               (scroll-to! b "P3-3" 0.5)
               (wait-for b "Ana's save kept, pending" (str queued-js ".then(q => q && q.length === 1 && q[0][1] === true)"))
               (testing "Bia's save on the same device lands, though Ana's (first in the queue) fails again"
                 (as-reader! b base "bia")
                 (open-book! b book)
                 (scroll-to! b "P5-2" 0.5)
                 (wait-server-position base "bia" "lib:plain" "Bia's save" some?)
                 ;; the server has the save before the page has handled its answer
                 (wait-for b "Bia's save answered" (str queued-js ".then(q => q && q.length === 2 && q[1][1] === false)"))
                 (is (= [["ana\nlib:plain" true] ["bia\nlib:plain" false]] (js b queued-js))
                     "Ana's is still kept for later"))))))))))

(deftest generated-ids-are-not-places-test
  (tb/with-site-dir #(site-of {"footnoted" (footnoted-text)})
    (fn [dir]
      (let [page (slurp (io/file dir "books/lib/footnoted/index.html"))]
        (is (str/includes? page "<li id=\"fn7\">") "the endnotes carry the emitter's numbered ids")
        (tb/call-with-browser
         "positions-app-test"
         (fn [b]
           (tb/with-server dir
             (fn [srv]
               (let [base (:url srv)]
                 (is (= 201 (http-json "POST" (str base "api/readers") {"name" "Ana"})))
                 (as-reader! b base "ana")
                 (open-book! b (str base "books/lib/footnoted/index.html"))
                 (scroll-to! b "N2-2" 0.5)
                 (let [p (wait-server-position base "ana" "lib:footnoted" "the save" some?)]
                   (is (nil? (get p "id")) (str "a footnote's number is no place: " (get p "id")))
                   (is (= "Chapter 2" (get-in p ["heading" "text"])))))))))))))

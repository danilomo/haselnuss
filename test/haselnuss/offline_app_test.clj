(ns ^:slow haselnuss.offline-app-test
  "The installable, offline app (TASK-277; doc-12, \"The app shell (PWA)\",
  \"Offline\"): `manifest.webmanifest`, the icons and `sw.js`, driven in a
  real headless Chrome (`haselnuss.test-browser`) over a real
  `serve/start!` of a site `--build-site` built from books written here.

  \"No network\" is DevTools' offline emulation, set on the page and on the
  site's service worker (each is its own target: the page's setting does
  not reach the worker's own fetches), so the server stays up and a test
  can tell a copy kept on the device from an answer it gave. Without
  Chrome the tests print a note and pass without running; maths offline is
  checked only when MathJax's CDN could be reached online, as in
  `haselnuss.reading-app-test`."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [haselnuss.cli :as cli]
            [haselnuss.site :as site]
            [haselnuss.test-browser :as tb :refer [cdp http-json js wait-for]])
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           (java.io File StringWriter)
           (java.net InetSocketAddress URI)
           (java.util.concurrent CountDownLatch Executors)
           (java.net.http HttpClient HttpClient$Redirect HttpRequest HttpRequest$BodyPublishers
                          HttpResponse HttpResponse$BodyHandlers)))

;; --- the books -------------------------------------------------------------------------------

(def ^:private filler
  (str "Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt"
       " ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis nostrud exercitation."))

(defn- book-text
  "Six chapters of five paragraphs, each paragraph starting with its tag
  (`P3-2`); with `maths?`, maths in chapter 1."
  [title & {:keys [maths?]}]
  (str "---\ntitle: " title "\n---\n\n"
       (str/join "\n\n"
                 (for [c (range 1 7)]
                   (str "# Chapter " c "\n\n"
                        (when (and maths? (= 1 c))
                          "The share $s = \\frac{f}{m+f}$ is maths.\n\n$$e^{i\\pi} + 1 = 0$$\n\n")
                        (str/join "\n\n" (for [p (range 1 6)] (str "P" c "-" p " " filler))))))
       "\n"))

(defn- write! [^File root rel content]
  (let [f (io/file root rel)]
    (io/make-parents f)
    (spit f content)
    f))

(defn- build!
  "Build `root`/src into `root`/site (incrementally when it exists)."
  [^File root]
  (binding [*out* (StringWriter.) *err* (StringWriter.)]
    (cli/main* ["--libraries" (str (io/file root "src/libraries.yaml")) "--build-site" (str (io/file root "site"))]
               {:library-env {} :library-home "/nonexistent/haselnuss-test-home"
                :haselnuss.cli/check-base-dir (.getCanonicalPath (io/file root "src"))
                :haselnuss.cli/site-threads 2})))

(defn- site-of-books []
  (let [root (tb/temp-dir)]
    (write! root "src/libraries.yaml" "- name: lib\n  type: filesystem\n  path: lib\n")
    (write! root "src/lib/maths.hdoc" (book-text "Maths" :maths? true))
    (write! root "src/lib/plain.hdoc" (book-text "Plain"))
    (write! root "src/lib/unopened.hdoc" (book-text "Unopened"))
    (build! root)
    (io/file root "site")))

;; --- the page and the worker -----------------------------------------------------------------

(def ^:private line-js
  "(document.querySelector('.hz-running').getBoundingClientRect().bottom + 8)")

(def ^:private paragraphs-js
  "Array.from(document.querySelectorAll('p')).filter(p => !p.closest('#hz-reader'))")

(defn- para-at-line
  "The tag of the paragraph the reading line crosses, or nil."
  [b]
  (js b (str "(() => { const line = " line-js "; const p = " paragraphs-js ".find(p => {"
             " const r = p.getBoundingClientRect(); return r.top <= line && r.bottom > line; });"
             " return p ? p.textContent.split(' ')[0] : null; })()")))

(defn- scroll-to!
  "Scroll so the middle of the paragraph `tag` is at the reading line."
  [b tag]
  (js b (str "(() => { const p = " paragraphs-js ".find(p => p.textContent.startsWith(" (json/write-str (str tag " ")) "));"
             " const r = p.getBoundingClientRect(); window.scrollTo(0, window.scrollY + r.top + r.height / 2 - " line-js "); })()")))

(defn- navigate! [b url]
  (cdp b "Page.navigate" {"url" "about:blank"})
  (wait-for b "blank" "location.href === 'about:blank'")
  (cdp b "Page.navigate" {"url" url}))

(defn- open-book!
  "Open the book page `url` afresh and wait until the saved position, if
  any, has been put back; how it was found (`data-position`)."
  [b url]
  (navigate! b url)
  (wait-for b (str url " position") "document.documentElement.getAttribute('data-position-ready') === 'true'")
  (js b "document.documentElement.getAttribute('data-position')"))

(defn- open-home! [b base]
  (navigate! b base)
  (wait-for b "home" "document.documentElement.getAttribute('data-home') === 'ready'"))

(def ^:private controlled-js
  "navigator.serviceWorker.ready.then(() => !!navigator.serviceWorker.controller)")

(def ^:private version-js
  "The version of the worker controlling the page."
  (str "new Promise(res => { const c = new MessageChannel(); c.port1.onmessage = e => res(e.data);"
       " navigator.serviceWorker.controller.postMessage({type: 'version'}, [c.port2]); })"))

(defn- kept-js
  "True when the cache `cache-name` holds `url`."
  [cache-name url]
  (str "caches.open(" (json/write-str cache-name) ").then(c => c.match(" (json/write-str url) ")).then(r => !!r)"))

(def ^:private worker-urls-js
  "Every URL the service worker's own caches hold (not app.js's catalog and data caches)."
  (str "caches.keys().then(ks => Promise.all(ks.filter(k => k !== 'haselnuss-catalog' && k !== 'haselnuss-data')"
       ".map(k => caches.open(k).then(c => c.keys()).then(rs => rs.map(r => r.url))))).then(a => a.flat())"))

(def ^:private queued-js
  "The records of this device's position queue: `[[key pending] ...]`."
  (str "new Promise(res => { const r = indexedDB.open('haselnuss-positions');"
       " r.onsuccess = () => { const q = r.result.transaction('positions').objectStore('positions').getAll();"
       " q.onsuccess = () => { res(q.result.map(x => [x.key, x.pending])); r.result.close(); }; };"
       " r.onerror = () => res(null); })"))

(def ^:private typeset-js
  "True once MathJax has typeset maths on the page; false after 12 s."
  (str "new Promise(res => { const t0 = Date.now(); (function poll() {"
       " if (document.querySelector('mjx-container')) res(true);"
       " else if (Date.now() - t0 > 12000) res(false); else setTimeout(poll, 100); })(); })"))

(defn- network!
  "Turn the network on or off for the page and for every service worker of
  the site at `base`, each attached once (`sessions`, an atom of target id
  to session) so no older session keeps an older setting."
  [b sessions base on?]
  (let [conditions {"offline" (not on?) "latency" 0 "downloadThroughput" -1 "uploadThroughput" -1}]
    (cdp b "Network.enable" {})
    (cdp b "Network.emulateNetworkConditions" conditions)
    (doseq [t (get (cdp b "Target.getTargets" {}) "targetInfos")
            :when (and (= "service_worker" (get t "type")) (str/starts-with? (str (get t "url")) base))]
      (let [id (get t "targetId")
            sid (or (get @sessions id)
                    (let [s (tb/attach! b id)]
                      (cdp b s "Network.enable" {})
                      (swap! sessions assoc id s)
                      s))]
        (cdp b sid "Network.emulateNetworkConditions" conditions)))
    (wait-for b "navigator.onLine" (str "navigator.onLine === " on?))))

;; --- AC #1: installable ----------------------------------------------------------------------

(deftest installable-test
  (tb/with-site-dir site-of-books
    (fn [dir]
      (testing "the build copies the icons, the manifest and the worker"
        (doseq [f ["manifest.webmanifest" "sw.js" "icon-192.png" "icon-512.png" "icon-maskable-512.png" "apple-touch-icon.png"]]
          (is (.isFile (io/file dir f)) f))
        (doseq [[f px] [["icon-192.png" 192] ["icon-512.png" 512] ["icon-maskable-512.png" 512] ["apple-touch-icon.png" 180]]]
          (let [img (javax.imageio.ImageIO/read (io/file dir f))]
            (is (= [px px] [(.getWidth img) (.getHeight img)]) f))
          (is (< (.length (io/file dir f)) 20000) (str f " stays small"))))
      (testing "index.html has the iOS tags"
        (let [index (slurp (io/file dir "index.html"))]
          (is (str/includes? index "<link rel=\"apple-touch-icon\" href=\"apple-touch-icon.png\"/>"))
          (is (str/includes? index "<meta name=\"apple-mobile-web-app-capable\" content=\"yes\"/>"))
          (is (str/includes? index "<meta name=\"apple-mobile-web-app-title\" content=\"Haselnuss\"/>"))))
      (tb/call-with-browser
       "offline-app-test"
       (fn [b]
         (tb/with-server dir
           (fn [srv]
             (let [base (:url srv)]
               (cdp b "Page.enable" {})
               (open-home! b base)
               (wait-for b "a controlling worker" controlled-js)
               (testing "Chrome finds nothing in the way of installing it from localhost"
                 (is (= [] (get (cdp b "Page.getInstallabilityErrors" {}) "installabilityErrors"))))
               (testing "the manifest parses without errors and says what doc-12 asks"
                 (let [{:strs [errors data url]} (cdp b "Page.getAppManifest" {})
                       m (json/read-str data)]
                   (is (= (str base "manifest.webmanifest") url))
                   (is (= [] errors))
                   (is (= ["Haselnuss" "standalone" "./" "./"] (mapv m ["name" "display" "start_url" "scope"])))
                   (is (= "#f4ecd8" (get m "background_color") (get m "theme_color")) "the sepia theme's paper")
                   (is (= #{["192x192" "any"] ["512x512" "any"] ["512x512" "maskable"]}
                          (set (map (juxt #(get % "sizes") #(get % "purpose")) (get m "icons")))))))
               (testing "the worker is registered for the whole site and asked for lasting storage"
                 (is (= base (js b "navigator.serviceWorker.getRegistration().then(r => r.scope)")))
                 (is (= (str base "sw.js") (js b "navigator.serviceWorker.controller.scriptURL")))
                 (is (= "1" (js b "localStorage.getItem('haselnuss.persistAsked')"))))))))))))

;; --- AC #2 and #4: reading with no network --------------------------------------------------------

(deftest offline-reading-test
  (tb/with-site-dir site-of-books
    (fn [dir]
      (tb/call-with-browser
       "offline-app-test"
       (fn [b]
         (tb/with-server dir
           (fn [srv]
             (let [base (:url srv)
                   sessions (atom {})
                   maths (str base "books/lib/maths/index.html")
                   plain (str base "books/lib/plain/index.html")
                   unopened (str base "books/lib/unopened/index.html")
                   stored #(get-in (json/read-str (slurp (io/file dir "readers" "ana.json"))) ["positions" %])]
               (cdp b "Page.enable" {})
               (testing "the first page a device opens, before any worker, is kept once the worker runs"
                 (open-book! b plain)
                 (wait-for b "a worker" "navigator.serviceWorker.ready.then(r => !!r.active)")
                 (is (zero? (js b "performance.getEntriesByType('navigation')[0].workerStart"))
                     "that page itself loaded with no worker on the way")
                 (wait-for b "the page kept" (kept-js "haselnuss-books" plain)))
               (is (= 201 (http-json "POST" (str base "api/readers") {"name" "Ana"})))
               (open-home! b base)
               (js b "localStorage.setItem('haselnuss.reader', JSON.stringify({id: 'ana', name: 'Ana'}))")
               (wait-for b "a controlling worker" controlled-js)
               (wait-for b "the position store" "indexedDB.databases().then(ds => ds.some(d => d.name === 'haselnuss-positions'))")
               (let [maths-online (do (open-book! b maths) (js b typeset-js))]
                 (when-not maths-online
                   (println "offline-app-test: MathJax CDN unreachable; maths offline not checked"))
                 (testing "a book read online saves its place (on the device and the server)"
                   (scroll-to! b "P4-3")
                   (wait-for b "a save" "document.documentElement.getAttribute('data-position-saves') === '1'")
                   (wait-for b "sent" (str queued-js ".then(q => q && q.some(r => r[0] === 'ana\\nlib:maths' && r[1] === false))"))
                   (is (= "Chapter 4" (get-in (stored "lib:maths") ["heading" "text"]))))
                 (testing "the worker kept the app, the book and MathJax, and never an api/ answer"
                   (let [urls (set (js b worker-urls-js))]
                     (is (every? urls (map #(str base %) ["" "index.html" "app.js" "app.css" "manifest.webmanifest"
                                                          "icon-192.png" "icon-512.png" "icon-maskable-512.png"
                                                          "apple-touch-icon.png"])))
                     (is (urls maths))
                     (is (urls plain))
                     (is (not (urls unopened)))
                     (when maths-online
                       (is (urls "https://cdn.jsdelivr.net/npm/mathjax@3.2.2/es5/tex-mml-chtml.js")))
                     (is (not-any? #(str/includes? % "/api/") urls) (pr-str urls))))
                 (network! b sessions base false)
                 (testing "offline, the app opens and shows its books from the copies on the device"
                   (open-home! b base)
                   (wait-for b "covers" "document.querySelectorAll('[data-grid=\"all\"] .cover').length === 3"))
                 (testing "offline, an opened book reads, at the place saved, maths and all"
                   (is (= "heading" (open-book! b maths)) "put back from the record on the device")
                   (is (= "P4-3" (para-at-line b)))
                   (when maths-online
                     (is (js b typeset-js) "MathJax from the copy the worker kept")))
                 (testing "offline, a save waits in the queue on the device"
                   (scroll-to! b "P2-2")
                   (wait-for b "a save" "document.documentElement.getAttribute('data-position-saves') === '1'")
                   (wait-for b "queued" (str queued-js ".then(q => q && q.some(r => r[0] === 'ana\\nlib:maths' && r[1] === true))"))
                   (is (= "Chapter 4" (get-in (stored "lib:maths") ["heading" "text"])) "the server has not heard of it"))
                 (testing "offline, the first page opened, before the worker, reads too"
                   (is (#{"none" "heading" "id"} (open-book! b plain)))
                   (is (= "Plain" (js b "document.querySelector('h1, .title') && document.title"))))
                 (testing "offline, a book never opened on this device says so"
                   (navigate! b unopened)
                   (wait-for b "the offline page" "!!document.getElementById('hz-offline')")
                   (is (str/includes? (js b "document.body.textContent") "not on this device"))
                   (is (= base (js b "document.querySelector('#hz-offline a').href")) "with a way back to the library"))
                 (network! b sessions base true)
                 (testing "back online, the queued save reaches the server"
                   (open-book! b maths)
                   (wait-for b "sent" (str queued-js ".then(q => q && q.every(r => r[1] === false))"))
                   (is (= "Chapter 2" (get-in (stored "lib:maths") ["heading" "text"]))))
                 (testing "and the book never opened now opens, and is kept"
                   (open-book! b unopened)
                   (is (= "Unopened" (js b "document.title")))
                   (is (js b (kept-js "haselnuss-books" unopened)))))))))))))

;; --- AC #3: a new app takes over on the next launch ------------------------------------------------

(defn- app-js-marker [b]
  (js b "fetch('/app.js').then(r => r.text()).then(t => (t.match(/\\/\\/ marker (\\w+)/) || [null, null])[1])"))

(deftest new-app-on-next-launch-test
  (let [root (tb/temp-dir)]
    (try
      (write! root "src/libraries.yaml" "- name: lib\n  type: filesystem\n  path: lib\n")
      (write! root "src/lib/plain.hdoc" (book-text "Plain"))
      (let [dir (io/file root "site")
            original @#'site/resource-bytes
            build-with-marker! (fn [marker]
                                 (with-redefs [site/resource-bytes
                                               (fn [file-name]
                                                 (let [^bytes bs (original file-name)]
                                                   (if (= "app.js" file-name)
                                                     (.getBytes (str (String. bs "UTF-8") "\n// marker " marker "\n") "UTF-8")
                                                     bs)))]
                                   (build! root)))
            version-on-disk #(second (re-find #"const VERSION = \"([0-9a-f]{16})\";" (slurp (io/file dir "sw.js"))))]
        (build-with-marker! "one")
        (tb/call-with-browser
         "offline-app-test"
         (fn [b]
           (tb/with-server dir
             (fn [srv]
               (let [base (:url srv)
                     book (str base "books/lib/plain/index.html")
                     v1 (version-on-disk)]
                 (cdp b "Page.enable" {})
                 (open-home! b base)
                 (wait-for b "a controlling worker" controlled-js)
                 (open-book! b book)
                 (is (= v1 (js b version-js)))
                 (is (= "one" (app-js-marker b)))
                 (testing "a rebuild that changes nothing leaves sw.js alone, so no new worker"
                   (let [mtime (.lastModified (io/file dir "sw.js"))]
                     (build-with-marker! "one")
                     (is (= v1 (version-on-disk)))
                     (is (= mtime (.lastModified (io/file dir "sw.js"))))
                     (is (= "none" (js b "navigator.serviceWorker.getRegistration().then(r => r.update().then(() => r.installing || r.waiting ? 'new' : 'none'))")))))
                 (build-with-marker! "two")
                 (let [v2 (version-on-disk)]
                   (is (not= v1 v2) "a new app has a new version")
                   (testing "the new worker installs in the background but the open page keeps the old app"
                     (js b "navigator.serviceWorker.getRegistration().then(r => r.update())")
                     (wait-for b "the new worker waiting" "navigator.serviceWorker.getRegistration().then(r => !!r.waiting)")
                     (is (= v1 (js b version-js)))
                     (is (= "one" (app-js-marker b))))
                   (testing "nor does going to another page of the app, or reloading: no change mid-session"
                     (cdp b "Page.navigate" {"url" base})
                     (wait-for b "home" "document.documentElement.getAttribute('data-home') === 'ready'")
                     (is (= v1 (js b version-js)))
                     (cdp b "Page.reload" {})
                     (wait-for b "home again" "document.readyState === 'complete' && document.documentElement.getAttribute('data-home') === 'ready'")
                     (is (= v1 (js b version-js)))
                     (is (= "one" (app-js-marker b))))
                   (testing "on the next launch, with no page of the app open in between, the new app runs"
                     ;; leaving the site lets the waiting worker take over; it
                     ;; activates on its own once no page uses the old one
                     (let [deadline (+ (System/currentTimeMillis) 15000)]
                       (loop []
                         (cdp b "Page.navigate" {"url" "about:blank"})
                         (wait-for b "blank" "location.href === 'about:blank'")
                         (open-home! b base)
                         (wait-for b "a controlling worker" controlled-js)
                         (when (and (not= v2 (js b version-js)) (< (System/currentTimeMillis) deadline))
                           (Thread/sleep 200)
                           (recur))))
                     (is (= v2 (js b version-js)))
                     (is (= "two" (app-js-marker b)))
                     (is (= [(str "haselnuss-app " base " " v2)]
                            (js b "caches.keys().then(ks => ks.filter(k => k.startsWith('haselnuss-app ')))"))
                         "the old app's copies are gone")))))))))
      (finally (tb/delete-tree root)))))

(deftest an-icon-is-part-of-the-version-test
  (testing "changing only an icon gives sw.js a new version, so devices install the new icon"
    (let [version #(second (re-find #"const VERSION = \"([0-9a-f]{16})\";"
                                    (String. ^bytes (get (site/app-file-bytes) "sw.js") "UTF-8")))
          original @#'site/resource-bytes
          before (version)
          after (with-redefs [site/resource-bytes (fn [file-name]
                                                    (let [^bytes bs (original file-name)]
                                                      (if (= "icon-192.png" file-name)
                                                        (doto (aclone bs) (aset-byte (dec (alength bs)) 0))
                                                        bs)))]
                  (version))]
      (is (re-matches #"[0-9a-f]{16}" (str before)))
      (is (not= before after)))))

;; --- a server error is not a reason to lose a kept book ------------------------------------------

(def ^:private ^HttpClient proxy-client
  (-> (HttpClient/newBuilder) (.followRedirects HttpClient$Redirect/NEVER) .build))

(defn- start-proxy!
  "An HTTP server in front of `backend` (a URL ending in `/`), the way a
  reverse proxy sits in front of `--serve`: it passes requests through,
  except that while `(down? path)` it answers 502 itself, and while
  `(hang? path)` it takes the request and never answers -- a VPN up with no
  network behind it (TASK-322). Its URL is `:url`; stop it with
  `stop-proxy!`, which lets the requests it holds go."
  ([backend down?] (start-proxy! backend down? (constantly false)))
  ([backend down? hang?]
   (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
         release (CountDownLatch. 1)
         handler (reify HttpHandler
                   (handle [_ ex]
                     (let [^HttpExchange ex ex
                           raw (str (.getRawPath (.getRequestURI ex))
                                    (some->> (.getRawQuery (.getRequestURI ex)) (str "?")))]
                       (try
                         (cond
                           (hang? (.getPath (.getRequestURI ex)))
                           (.await release)

                           (down? (.getPath (.getRequestURI ex)))
                           (let [body (.getBytes "Bad Gateway from the proxy" "UTF-8")]
                             (.set (.getResponseHeaders ex) "Content-Type" "text/plain; charset=utf-8")
                             (.sendResponseHeaders ex 502 (alength body))
                             (with-open [out (.getResponseBody ex)] (.write out body)))

                           :else
                           (let [method (.getRequestMethod ex)
                                 in-body (.readAllBytes (.getRequestBody ex))
                                 req (cond-> (HttpRequest/newBuilder (URI. (str (subs backend 0 (dec (count backend))) raw)))
                                       (.getFirst (.getRequestHeaders ex) "Content-Type")
                                       (.header "Content-Type" (.getFirst (.getRequestHeaders ex) "Content-Type"))
                                       (.getFirst (.getRequestHeaders ex) "If-None-Match")
                                       (.header "If-None-Match" (.getFirst (.getRequestHeaders ex) "If-None-Match"))
                                       true (.method method (if (pos? (alength in-body))
                                                              (HttpRequest$BodyPublishers/ofByteArray in-body)
                                                              (HttpRequest$BodyPublishers/noBody))))
                                 ^HttpResponse r (.send proxy-client (.build ^java.net.http.HttpRequest$Builder req)
                                                        (HttpResponse$BodyHandlers/ofByteArray))
                                 ^bytes body (.body r)
                                 n (alength body)]
                             (doseq [h ["Content-Type" "ETag" "Date" "Cache-Control" "Location"]
                                     :let [v (.firstValue (.headers r) h)]
                                     :when (.isPresent v)]
                               (.set (.getResponseHeaders ex) h (.get v)))
                             (.sendResponseHeaders ex (.statusCode r) (if (or (zero? n) (= "HEAD" method)) -1 n))
                             (when (and (pos? n) (not= "HEAD" method))
                               (with-open [out (.getResponseBody ex)] (.write out body)))))
                         (catch Exception _ nil)
                         (finally (.close ex))))))]
     (.createContext server "/" handler)
     ;; a thread per request: one held by hang? must not hold up the rest
     (.setExecutor server (Executors/newCachedThreadPool))
     (.start server)
     {:server server :release release
      :url (str "http://127.0.0.1:" (.getPort (.getAddress server)) "/")})))

(defn- stop-proxy! [p]
  (.countDown ^CountDownLatch (:release p))
  (.stop ^HttpServer (:server p) 0))

(deftest a-server-error-shows-the-kept-copy-test
  (tb/with-site-dir site-of-books
    (fn [dir]
      (tb/call-with-browser
       "offline-app-test"
       (fn [b]
         (tb/with-server dir
           (fn [srv]
             (let [down (atom false)
                   front (start-proxy! (:url srv) (fn [path] (and @down (str/starts-with? path "/books/"))))]
               (try
                 (let [base (:url front)
                       plain (str base "books/lib/plain/index.html")
                       unopened (str base "books/lib/unopened/index.html")
                       books-keys "caches.open('haselnuss-books').then(c => c.keys()).then(rs => rs.map(r => r.url))"]
                   (cdp b "Page.enable" {})
                   (open-home! b base)
                   (wait-for b "a controlling worker" controlled-js)
                   (testing "a book opened with a query is kept under its URL without it"
                     (open-book! b (str plain "?from=a-link"))
                     (wait-for b "kept" (kept-js "haselnuss-books" plain))
                     (open-book! b (str plain "?from=elsewhere"))
                     (is (= [plain] (filterv #(str/includes? % "/plain/") (js b books-keys)))))
                   (reset! down true)
                   (testing "the server behind the proxy is down: a kept book shows the copy, not the 502"
                     (is (= 502 (.statusCode ^HttpResponse (.send proxy-client (.build (HttpRequest/newBuilder (URI. plain)))
                                                                  (HttpResponse$BodyHandlers/discarding)))))
                     (navigate! b (str plain "?from=offline"))
                     (wait-for b "the kept page" "document.title === 'Plain' && !!document.querySelector('p')")
                     (is (not (str/includes? (js b "document.body.textContent") "Bad Gateway"))))
                   (testing "a book with no copy shows the server's error as it is"
                     (navigate! b unopened)
                     (wait-for b "the 502" "document.readyState === 'complete' && document.body && document.body.textContent.includes('Bad Gateway')")
                     (is (not (js b "!!document.getElementById('hz-offline')"))))
                   (reset! down false)
                   (testing "the copy is still there once the server is back"
                     (is (js b (kept-js "haselnuss-books" plain)))))
                 (finally (stop-proxy! front)))))))))))

;; --- a network that neither answers nor fails (TASK-322) -------------------------------------------

(defn- search! [b q]
  (js b (str "(() => { const i = document.getElementById('search-input'); i.value = " (json/write-str q) ";"
             " i.dispatchEvent(new Event('input')); })()")))

(def ^:private refs-js
  "Open the Referenced by panel of the book page shown."
  "document.querySelector('.hz-refs').click()")

(deftest a-hanging-network-shows-the-kept-copies-test
  (tb/with-site-dir site-of-books
    (fn [dir]
      (tb/call-with-browser
       "offline-app-test"
       (fn [b]
         (tb/with-server dir
           (fn [srv]
             (let [hung (atom false)
                   front (start-proxy! (:url srv) (constantly false) (fn [_] @hung))]
               (try
                 (let [base (:url front)
                       plain (str base "books/lib/plain/index.html")
                       no-refs "document.body.textContent.includes('No other book refers to this one.')"]
                   (cdp b "Page.enable" {})
                   (is (= 201 (http-json "POST" (str (:url srv) "api/readers") {"name" "Ana"})))
                   (open-home! b base)
                   (js b "localStorage.setItem('haselnuss.reader', JSON.stringify({id: 'ana', name: 'Ana'}))")
                   (wait-for b "a controlling worker" controlled-js)
                   (open-home! b base)
                   (wait-for b "covers online" "document.querySelectorAll('[data-grid=\"all\"] .cover').length === 3")
                   (wait-for b "the catalog kept" (kept-js "haselnuss-catalog" (str base "catalog/index.json")))
                   (wait-for b "the position store" "indexedDB.databases().then(ds => ds.some(d => d.name === 'haselnuss-positions'))")
                   (testing "online: a place saved on the device and the server, and the book's backlinks kept"
                     (open-book! b plain)
                     (scroll-to! b "P4-3")
                     (wait-for b "a save" "document.documentElement.getAttribute('data-position-saves') === '1'")
                     (wait-for b "sent" (str queued-js ".then(q => q && q.some(r => r[0] === 'ana\\nlib:plain' && r[1] === false))"))
                     (js b refs-js)
                     (wait-for b "backlinks online" no-refs))
                   (reset! hung true)
                   (testing "the network hangs: the home screen shows the covers kept on the device"
                     (open-home! b base)
                     (wait-for b "kept covers" "document.querySelectorAll('[data-grid=\"all\"] .cover').length === 3")
                     (is (= "" (js b "document.getElementById('message').textContent"))))
                   (testing "search answers from the kept catalog pages"
                     (js b "location.hash = '#search'")
                     (search! b "plain")
                     (wait-for b "offline results" "document.getElementById('search-status').dataset.mode === 'offline' && document.querySelectorAll('#search-results li').length === 1"))
                   (testing "an opened book is put back at the place kept on the device"
                     (is (= "heading" (open-book! b plain)))
                     (is (= "P4-3" (para-at-line b))))
                   (testing "its backlinks come from the copy kept on the device"
                     (js b refs-js)
                     (wait-for b "kept backlinks" no-refs)))
                 (finally (stop-proxy! front)))))))))))

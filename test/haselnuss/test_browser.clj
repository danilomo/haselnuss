(ns haselnuss.test-browser
  "A real headless Chrome for the reader app's tests (TASK-274, TASK-275):
  `resources/haselnuss/site/` driven for real instead of read as text.

  Chrome is started with `--headless=new --remote-debugging-port=0` and a
  throwaway profile; this namespace talks to it over the DevTools protocol
  with the JDK's own `java.net.http.WebSocket` (no npm, no library),
  evaluating JavaScript in the page and polling for what it expects. Where
  no Chrome or Chromium is on this machine, `call-with-browser` prints a
  note and runs nothing: that is the only skip.

  Cleanup is by handle, never by name: our Chrome and the helpers it
  started are stopped and its profile deleted in a `finally`, as is every
  temporary site (`with-site-dir`) and server (`with-server`)."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [is]]
            [haselnuss.cli :as cli]
            [haselnuss.serve :as serve])
  (:import (java.io File StringWriter)
           (java.net URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers
                          WebSocket WebSocket$Listener)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (java.util.concurrent CompletableFuture ConcurrentHashMap TimeUnit)
           (java.util.concurrent.atomic AtomicLong)))

;; --- files and HTTP -----------------------------------------------------------------------

(defn temp-dir
  "A new, empty temporary folder."
  ^File []
  (.toFile (Files/createTempDirectory "haselnuss-browser" (make-array FileAttribute 0))))

(defn delete-tree
  "Delete `dir` and everything below it."
  [^File dir]
  (doseq [^File f (reverse (file-seq dir))] (.delete f)))

(def ^:private ^HttpClient http (HttpClient/newHttpClient))

(defn http-get
  "The body of a GET of `url`, as text."
  [url]
  (.body (.send http (.build (HttpRequest/newBuilder (URI. ^String url))) (HttpResponse$BodyHandlers/ofString))))

(defn http-json
  "`method` `url` with a JSON `body`; the status."
  [method url body]
  (let [^java.net.http.HttpResponse r
        (.send http (.build (doto (HttpRequest/newBuilder (URI. ^String url))
                              (.header "Content-Type" "application/json")
                              (.method ^String method (HttpRequest$BodyPublishers/ofString (json/write-str body)))))
               (HttpResponse$BodyHandlers/ofString))]
    (.statusCode r)))

;; --- Chrome over the DevTools protocol ------------------------------------------------------

(defn- find-chrome []
  (first (filter #(.canExecute (io/file %))
                 ["/usr/bin/google-chrome" "/usr/bin/google-chrome-stable" "/usr/bin/chromium"
                  "/usr/bin/chromium-browser" "/snap/bin/chromium"])))

(defn- kill-chrome
  "Our own Chrome and the helpers it started, by handle; then its profile."
  [^Process process ^File profile]
  (doseq [^java.lang.ProcessHandle h (iterator-seq (.iterator (.descendants (.toHandle process))))]
    (.destroyForcibly h))
  (.destroyForcibly process)
  (.waitFor process 10 TimeUnit/SECONDS)
  (delete-tree profile))

(declare connect-browser)

(defn- start-browser
  "`{:process :profile :ws :pending :counter}` for a headless Chrome with one
  page, connected. Anything that goes wrong after Chrome is running stops it
  and removes its profile before the exception leaves."
  [chrome]
  (let [profile (temp-dir)
        pb (ProcessBuilder. ^java.util.List (vector chrome "--headless=new" "--no-sandbox" "--disable-gpu"
                                                    "--remote-debugging-port=0" "--no-first-run"
                                                    "--disable-extensions" "--disable-background-networking"
                                                    (str "--user-data-dir=" profile) "about:blank"))
        _ (.redirectErrorStream pb true)
        _ (.redirectOutput pb java.lang.ProcessBuilder$Redirect/DISCARD)
        process (try (.start pb) (catch Throwable t (delete-tree profile) (throw t)))]
    (try (connect-browser process profile)
         (catch Throwable t (kill-chrome process profile) (throw t)))))

(defn- connect-browser [^Process process ^File profile]
  (let [port-file (io/file profile "DevToolsActivePort")
        deadline (+ (System/currentTimeMillis) 30000)
        _ (while (and (not (.exists port-file)) (< (System/currentTimeMillis) deadline)) (Thread/sleep 50))
        _ (when-not (.exists port-file) (throw (ex-info "Chrome did not start" {})))
        _ (Thread/sleep 100)
        port (first (str/split-lines (slurp port-file)))
        targets (json/read-str (http-get (str "http://127.0.0.1:" port "/json/list")))
        page (first (filter #(= "page" (get % "type")) targets))
        pending (ConcurrentHashMap.)
        buf (StringBuilder.)
        listener (reify WebSocket$Listener
                   (onOpen [_ ws] (.request ws 1))
                   (onText [_ ws data final?]
                     (.append buf data)
                     (when final?
                       (let [msg (json/read-str (str buf))]
                         (.setLength buf 0)
                         (when-let [id (get msg "id")]
                           (when-let [^CompletableFuture f (.remove pending id)]
                             (.complete f msg)))))
                     (.request ws 1)
                     nil))
        ws (.join (.buildAsync (.newWebSocketBuilder http) (URI. ^String (get page "webSocketDebuggerUrl")) ^WebSocket$Listener listener))]
    {:process process :profile profile :ws ws :pending pending :counter (AtomicLong.)}))

(defn cdp
  "Send the DevTools command `method` with `params` -- to the page, or to
  the target attached as `session` (`attach!`) -- and return its result."
  ([b method params] (cdp b nil method params))
  ([{:keys [^WebSocket ws ^ConcurrentHashMap pending ^AtomicLong counter]} session method params]
   (let [id (.incrementAndGet counter)
         f (CompletableFuture.)]
     (.put pending id f)
     (.join (.sendText ws (json/write-str (cond-> {"id" id "method" method "params" params}
                                            session (assoc "sessionId" session)))
                       true))
     (let [msg (.get f 60 TimeUnit/SECONDS)]
       (when-let [e (get msg "error")] (throw (ex-info (str method ": " (get e "message")) {:error e})))
       (get msg "result")))))

(defn attach!
  "A session for the DevTools target `target-id` (a service worker, say),
  for `cdp`'s `session`."
  [b target-id]
  (get (cdp b "Target.attachToTarget" {"targetId" target-id "flatten" true}) "sessionId"))

(defn- stop-browser [{:keys [^Process process ^File profile ^WebSocket ws]}]
  (try (.sendClose ws WebSocket/NORMAL_CLOSURE "") (catch Exception _ nil))
  (kill-chrome process profile))

(defn js
  "The value of the JavaScript expression `expr` in the page (a promise is
  awaited), as Clojure data."
  [b expr]
  (let [r (cdp b "Runtime.evaluate" {"expression" expr "returnByValue" true "awaitPromise" true})]
    (when-let [ex (get r "exceptionDetails")]
      (throw (ex-info (str "page error: " (or (get-in ex ["exception" "description"]) (get ex "text"))) {:expr expr})))
    (get-in r ["result" "value"])))

(defn wait-for
  "Poll the JavaScript expression `expr` until it is truthy (up to 15 s);
  its value. Fails the test, with `what`, when it never is."
  [b what expr]
  (let [deadline (+ (System/currentTimeMillis) 15000)]
    (loop []
      (let [v (try (js b expr) (catch Exception _ nil))]
        (cond v v
              (> (System/currentTimeMillis) deadline) (do (is false (str "timed out waiting for " what)) nil)
              :else (do (Thread/sleep 50) (recur)))))))

(defn call-with-browser
  "`(f browser)` in a fresh headless Chrome with a phone-sized page, stopped
  after; prints a note naming `who` and does nothing when there is no Chrome."
  ([f] (call-with-browser "browser test" f))
  ([who f]
   (if-let [chrome (find-chrome)]
     (let [b (start-browser chrome)]
       (try
         (cdp b "Emulation.setDeviceMetricsOverride" {"width" 390 "height" 844 "deviceScaleFactor" 2 "mobile" true})
         (f b)
         (finally (stop-browser b))))
     (println (str who ": no Chrome or Chromium found; the browser test is SKIPPED")))))

;; --- sites and servers -------------------------------------------------------------------

(defn build-site
  "Build the site of the directory file `yaml` into `dir` (default: a new
  temporary folder's `site/`), books' relative paths read below `base`;
  `dir`."
  ([yaml base] (build-site yaml base (io/file (temp-dir) "site")))
  ([yaml base dir]
   (binding [*err* (StringWriter.) *out* (StringWriter.)]
     (cli/main* ["--libraries" (str yaml) "--build-site" (str dir)]
                {:library-env {} :library-home "/nonexistent/haselnuss-test-home"
                 :haselnuss.cli/check-base-dir (.getCanonicalPath (io/file base))
                 :haselnuss.cli/site-threads 2}))
   dir))

(defn build-fixture-site
  "The site fixture set (`test/fixtures/site`) built into a temporary folder."
  []
  (let [src (io/file "test/fixtures/site")]
    (build-site (io/file src "libraries.yaml") src)))

(defn with-site-dir
  "`(f dir)` for the site `(make)` builds in its own temp folder, which is
  deleted after, whatever `f` does."
  [make f]
  (let [dir (make)]
    (try (f dir) (finally (delete-tree (.getParentFile ^File dir))))))

(defn with-server
  "`(f server)` with `--serve` running over the site `dir` on a free port;
  stopped after."
  [dir f]
  (let [srv (serve/start! {:dir (str dir) :port 0 :reload-interval-ms 0 :threads 4})]
    (try (f srv) (finally (try (serve/stop! srv) (catch Exception _ nil))))))

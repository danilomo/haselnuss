(ns haselnuss.serve-test
  "`--serve DIR` (TASK-273; doc-12, \"The server: --serve\"): a real server
  on a free port over the site fixture set (`test/fixtures/site/`) built
  into a temp folder, asked with raw HTTP/1.1 requests -- a socket, not a
  client library, so a path reaches the server exactly as written, `..`
  and all."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [haselnuss.cli :as cli]
            [haselnuss.serve :as serve])
  (:import (java.io ByteArrayOutputStream File IOException StringWriter)
           (java.net Socket)
           (java.nio.file Files LinkOption)
           (java.nio.file.attribute FileAttribute)
           (java.util Random)))

;; --- helpers -----------------------------------------------------------------------

(def ^:private ^File fixture-dir (io/file "test/fixtures/site"))

(defn- temp-dir ^File []
  (.toFile (Files/createTempDirectory "haselnuss-serve" (make-array FileAttribute 0))))

(defn- rel-path [^File root ^File f]
  (str (.relativize (.toPath root) (.toPath f))))

(defn- fixture-copy
  "A copy of the site fixture set in a new folder."
  ^File []
  (let [root (temp-dir)]
    (doseq [^File f (file-seq fixture-dir) :when (.isFile f)]
      (let [target (io/file root (rel-path fixture-dir f))]
        (io/make-parents target)
        (io/copy f target)))
    root))

(defn- build!
  "Build the libraries of `root`'s `libraries.yaml` into the site `dir`;
  the exit status."
  [^File root dir]
  (binding [*err* (StringWriter.) *out* (StringWriter.)]
    (cli/main* ["--libraries" (str (io/file root "libraries.yaml")) "--build-site" (str dir)]
               {:library-env {} :library-home "/nonexistent/haselnuss-test-home"
                :haselnuss.cli/check-base-dir (.getCanonicalPath root)
                :haselnuss.cli/site-threads 2})))

(defn- built-site
  "A fresh copy of the fixture set and a site built from it:
  `{:src File :site File}`."
  []
  (let [src (fixture-copy)
        site (io/file (temp-dir) "site")]
    (is (contains? #{0 1} (build! src site)))
    {:src src :site site}))

(def ^:private shared-site
  "One site for the tests that only read it."
  (delay (built-site)))

(defn- with-server
  "`(f server)`, `server` one `serve/start!` answered for the site `dir`
  on a free port, reloading the catalog on every request; stopped after."
  [dir f]
  (let [srv (serve/start! {:dir (str dir) :port 0 :reload-interval-ms 0 :threads 4})]
    (try (f srv) (finally (serve/stop! srv)))))

(defn- chunked ^bytes [^bytes bs]
  (let [out (ByteArrayOutputStream.)
        w (fn [^String s] (let [b (.getBytes s "ISO-8859-1")] (.write out b 0 (alength b))))]
    (doseq [part (partition-all 4096 (seq bs))]
      (w (str (Integer/toHexString (count part)) "\r\n"))
      (let [b (byte-array part)] (.write out b 0 (alength b)))
      (w "\r\n"))
    (w "0\r\n\r\n")
    (.toByteArray out)))

(defn- index-of-bytes [^bytes hay ^bytes needle]
  (loop [i 0]
    (cond (> (+ i (alength needle)) (alength hay)) -1
          (every? #(= (aget hay (+ i (long %))) (aget needle (long %))) (range (alength needle))) i
          :else (recur (inc i)))))

(defn- request
  "One raw HTTP/1.1 request for `target` (sent exactly as given) to the
  server on `port`: `{:status :headers :body}`, header names lower-cased,
  the body as text. `:body` is text or bytes, sent as
  `Content-Type: application/json` unless `:headers` names another (nil:
  none); `:chunked? true` sends it chunked rather than with a
  Content-Length. `:host` is the `Host` header (default `localhost`; nil
  sends none, a vector one header per entry), `:addr` the address
  connected to (default `127.0.0.1`), `:version` the HTTP version."
  [port method target & {:keys [headers body chunked? host addr version]
                         :or {host "localhost" addr "127.0.0.1" version "1.1"}}]
  (with-open [s (Socket. (str addr) (int port))]
    (.setSoTimeout s 20000)
    (let [out (.getOutputStream s)
          bs (when body (if (bytes? body) body (.getBytes (str body) "UTF-8")))
          headers (cond-> headers
                    (and bs (not (contains? headers "Content-Type"))) (assoc "Content-Type" "application/json"))
          head (str method " " target " HTTP/" version "\r\n"
                    (apply str (for [h (if (vector? host) host (when host [host]))] (str "Host: " h "\r\n")))
                    "Connection: close\r\n"
                    (apply str (for [[k v] headers :when (some? v)] (str k ": " v "\r\n")))
                    (cond chunked? "Transfer-Encoding: chunked\r\n"
                          (contains? headers "Content-Length") nil
                          bs (str "Content-Length: " (alength ^bytes bs) "\r\n"))
                    "\r\n")]
      (.write out (.getBytes head "ISO-8859-1"))
      ;; the server may answer (413) and close before reading it all
      (try (when bs (let [^bytes payload (if chunked? (chunked bs) bs)] (.write out payload)))
           (.flush out)
           (catch IOException _ nil))
      (let [all (let [in (.getInputStream s)
                      buf (ByteArrayOutputStream.)]
                  (try (.transferTo in buf) (catch IOException _ nil))
                  (.toByteArray buf))
            split (index-of-bytes all (.getBytes "\r\n\r\n" "ISO-8859-1"))
            head-text (String. all 0 (int (max 0 split)) "ISO-8859-1")
            [status-line & header-lines] (str/split-lines head-text)
            body (if (neg? split) "" (String. all (int (+ split 4)) (int (- (alength all) split 4)) "UTF-8"))]
        {:status (some-> (second (str/split (str status-line) #" ")) Long/parseLong)
         :headers (into {} (for [l header-lines
                                 :let [[k v] (str/split l #":\s*" 2)]]
                             [(str/lower-case k) v]))
         :body body}))))

(defn- get-json [port target]
  (let [r (request port "GET" target)]
    (assoc r :json (json/read-str (:body r)))))

(defn- send-json [port method target data]
  (let [r (request port method target :body (json/write-str data))]
    (assoc r :json (try (json/read-str (:body r)) (catch Exception _ nil)))))

;; --- refusing to start -------------------------------------------------------------

(deftest refuses-a-folder-that-is-not-a-site-test
  (let [empty-dir (temp-dir)
        half (temp-dir)]
    (.mkdirs (io/file half ".haselnuss" "state"))
    (doseq [[label dir] [["empty" empty-dir] ["missing" (io/file empty-dir "nope")]
                         ["no catalog" half]]]
      (testing label
        (let [e (try (serve/start! {:dir (str dir) :port 0}) nil
                     (catch clojure.lang.ExceptionInfo e e))]
          (is (= ::serve/not-a-site (:type (ex-data e)))))))
    (testing "through the CLI: exit 2, nothing started"
      (let [err (StringWriter.)
            status (binding [*err* err] (cli/main* ["--serve" (str empty-dir)]))]
        (is (= 2 status))
        (is (str/includes? (str err) "not a Haselnuss site"))))))

(deftest cli-parses-serve-flags-test
  (let [run (fn [& args]
              (let [err (StringWriter.)
                    status (binding [*err* err *out* (StringWriter.)] (cli/main* args))]
                {:status status :err (str err)}))]
    (doseq [[args message] [[["--serve" "d" "--build-site" "e"] "--serve cannot be combined with --build-site"]
                            [["--serve" "d" "--check"] "--serve cannot be combined with --check"]
                            [["--serve" "d" "x.hdoc"] "--serve takes no input file"]
                            [["--port" "80" "x.hdoc"] "--port applies only to --serve"]
                            [["--bind" "0.0.0.0" "--build-site" "e"] "--bind applies only to --serve"]
                            [["--serve" "d" "--port" "70000"] "option --port takes a port number"]
                            [["--serve" "d" "--port" "eighty"] "option --port takes a port number"]
                            [["--serve" "d" "--serve" "e"] "--serve given more than once"]
                            [["--serve" "d" "--full"] "--full applies only to --build-site"]
                            [["--host" "home.ts.net" "--build-site" "e"] "--host applies only to --serve"]
                            [["--serve" "d" "--host" "home.ts.net:443"] "--host takes a host name or IP address, without a port"]
                            [["--serve" "d" "--host" "0.0.0.0"] "--host takes a host name"]
                            [["--serve" "d" "--host"] "--host"]]]
      (let [{:keys [status err]} (apply run args)]
        (is (= 2 status) (pr-str args))
        (is (str/includes? err message) (pr-str args err))))
    (testing "the server runs through the CLI, warning about flags it ignores"
      (let [{:keys [site]} @shared-site
            err (StringWriter.)
            seen (atom nil)
            status (binding [*err* err]
                     (cli/main* ["--serve" (str site) "--port" "0" "--bind" "127.0.0.1" "--target" "latex"]
                                {:haselnuss.cli/serve-hook
                                 (fn [server] (reset! seen (:status (request (:port server) "GET" "/"))))}))]
        (is (= 0 status))
        (is (= 200 @seen))
        (is (str/includes? (str err) "--target has no effect with --serve"))
        (is (str/includes? (str err) "haselnuss: serving "))))
    (testing "each --host NAME is answered, and only those"
      (let [seen (atom nil)
            err (StringWriter.)
            status (binding [*err* err]
                     (cli/main* ["--serve" (str (:site @shared-site)) "--port" "0"
                                 "--host" "home.tailnet.ts.net" "--host" "Pi.local"]
                                {:haselnuss.cli/serve-hook
                                 (fn [server]
                                   (reset! seen (mapv #(:status (request (:port server) "GET" "/" :host %))
                                                      ["home.tailnet.ts.net" "pi.local:8080" "other.local"])))}))]
        (is (= 0 status))
        (is (= [200 200 421] @seen))
        (is (str/includes? (str err) (str "haselnuss: answering requests to localhost, home.tailnet.ts.net, "
                                          "pi.local, 127.0.0.1, any loopback address (more with --host NAME)"))
            (str err))))
    (testing "a bare IPv6 --host is told to use brackets"
      (let [{:keys [status err]} (run "--serve" "d" "--host" "::1")]
        (is (= 2 status))
        (is (str/includes? err "an IPv6 address goes in brackets: [::1]") err)))))

;; --- AC #1: files with their types -------------------------------------------------

(deftest serves-pages-app-files-and-json-with-types-test
  (let [{:keys [site]} @shared-site]
    (with-server site
      (fn [srv]
        (let [port (:port srv)]
          (doseq [[target ctype] [["/" "text/html; charset=utf-8"]
                                  ["/index.html" "text/html; charset=utf-8"]
                                  ["/app.js" "text/javascript; charset=utf-8"]
                                  ["/app.css" "text/css; charset=utf-8"]
                                  ["/sw.js" "text/javascript; charset=utf-8"]
                                  ["/manifest.webmanifest" "application/manifest+json; charset=utf-8"]
                                  ["/catalog/index.json" "application/json; charset=utf-8"]
                                  ["/catalog/bible/1.json" "application/json; charset=utf-8"]
                                  ["/books/bible/blivre/romanos/index.html" "text/html; charset=utf-8"]
                                  ["/books/bible/blivre/romanos/" "text/html; charset=utf-8"]
                                  ["/books/bible/blivre/romanos/backlinks.json" "application/json; charset=utf-8"]
                                  ["/books/bible/blivre/romanos/previews.json" "application/json; charset=utf-8"]
                                  ["/books/reformed/catkins/catkins.png" "image/png"]]]
            (testing target
              (let [{:keys [status headers body]} (request port "GET" target)
                    rel (if (str/ends-with? target "/") (str target "index.html") target)]
                (is (= 200 status))
                (is (= ctype (get headers "content-type")))
                (is (= "no-cache" (get headers "cache-control")))
                (is (= "nosniff" (get headers "x-content-type-options")))
                (is (re-matches #"\"[0-9a-f]+\"" (str (get headers "etag"))))
                (when-not (str/ends-with? target ".png")
                  (is (= (slurp (io/file site (subs rel 1))) body))))))
          (testing "a folder without its slash is redirected to it"
            (let [{:keys [status headers]} (request port "GET" "/books/bible/blivre/romanos")]
              (is (= 301 status))
              (is (= "/books/bible/blivre/romanos/" (get headers "location")))))
          (testing "If-None-Match with the ETag answers 304; HEAD gives the length and no body"
            (let [tag (get-in (request port "GET" "/app.js") [:headers "etag"])]
              (is (= 304 (:status (request port "GET" "/app.js" :headers {"If-None-Match" tag}))))
              (is (= 304 (:status (request port "GET" "/app.js" :headers {"If-None-Match" (str "\"x\", W/" tag)}))))
              (is (= 200 (:status (request port "GET" "/app.js" :headers {"If-None-Match" "\"other\""}))))
              (let [{:keys [status headers body]} (request port "HEAD" "/app.js")]
                (is (= 200 status))
                (is (= (str (.length (io/file site "app.js"))) (get headers "content-length")))
                (is (= "" body)))))
          (testing "a file a build replaces gets a new ETag"
            (let [f (io/file site "catalog" "index.json")
                  before (get-in (request port "GET" "/catalog/index.json") [:headers "etag"])
                  tmp (io/file site "catalog" "index.json.new")]
              (spit tmp (slurp f))
              (Files/move (.toPath tmp) (.toPath f)
                          (into-array java.nio.file.CopyOption [java.nio.file.StandardCopyOption/REPLACE_EXISTING]))
              (is (not= before (get-in (request port "GET" "/catalog/index.json") [:headers "etag"])))))
          (testing "only GET and HEAD for files"
            (let [{:keys [status headers]} (request port "POST" "/index.html" :body "x")]
              (is (= 405 status))
              (is (= "GET, HEAD" (get headers "allow"))))))))))

(deftest sandboxes-non-html-files-under-books-test
  (let [{:keys [site]} @shared-site]
    (with-server site
      (fn [srv]
        (let [port (:port srv)
              csp #(get-in (request port "GET" %) [:headers "content-security-policy"])]
          (testing "an image or data file a book has is sandboxed: no script runs if it is opened"
            (is (= "sandbox" (csp "/books/reformed/catkins/catkins.png")))
            (is (= "sandbox" (csp "/books/bible/blivre/romanos/previews.json"))))
          (testing "a book page and the app are not: they run app.js and MathJax"
            (is (nil? (csp "/books/bible/blivre/romanos/index.html")))
            (is (nil? (csp "/app.js")))
            (is (nil? (csp "/index.html"))))
          (testing "nosniff on every answer, errors and the API included"
            (doseq [target ["/nope" "/.haselnuss/lock" "/api/readers" "/api/nope" "/api/search?q=x"]]
              (is (= "nosniff" (get-in (request port "GET" target) [:headers "x-content-type-options"])) target))))))))

;; --- AC #2: what is never served --------------------------------------------------

(def ^:private secret "HASELNUSS-SECRET-273")

(defn- plant-secrets!
  "A secret file outside the site, in `.haselnuss/`, in `readers/`, and
  symbolic links inside the site to the outside one, to `.haselnuss/` and
  to `readers/`; also a link to a file inside the site, which is served."
  [^File site]
  (let [outside (io/file (.getParentFile site) "outside.txt")
        link (fn [rel ^File target]
               (let [l (io/file site rel)]
                 (io/make-parents l)
                 (Files/createSymbolicLink (.toPath l) (.toPath target) (make-array FileAttribute 0))))]
    (spit outside secret)
    (spit (io/file site ".haselnuss" "secret.txt") secret)
    (.mkdirs (io/file site "readers"))
    (spit (io/file site "readers" "x.json") (json/write-str {"name" secret "positions" {}}))
    (link "books/out.txt" outside)
    (link "books/outdir" (.getParentFile site))
    (link "books/hidden" (io/file site ".haselnuss"))
    (link "books/rd" (io/file site "readers"))
    (link "books/rd.json" (io/file site "readers" "x.json"))
    (link "books/app-link.js" (io/file site "app.js"))))

(def ^:private never-served
  ["/.haselnuss/state/" "/.haselnuss/lock" "/.haselnuss/secret.txt" "/.haselnuss"
   "/%2ehaselnuss/secret.txt" "/%2Ehaselnuss/secret.txt" "/.HASELNUSS/secret.txt"
   "/readers/x.json" "/readers/" "/readers" "/%72eaders/x.json" "/readers%2fx.json"
   "/../etc/passwd" "/%2e%2e/etc/passwd" "/%2E%2E/%2E%2E/etc/passwd" "/.%2e/etc/passwd"
   "/%252e%252e/etc/passwd" "/%252E%252E%252Fetc%252Fpasswd" "/books/..%2f..%2foutside.txt"
   "/books/%2e%2e/%2e%2e/outside.txt" "/books/..%5c..%5coutside.txt" "/books/%5c..%5coutside.txt"
   "/..%2foutside.txt" "/%2Fetc%2Fpasswd" "//etc/passwd" "/%2f%2fetc/passwd"
   "/books/out.txt" "/books/outdir/outside.txt" "/books/hidden/secret.txt" "/books/rd/x.json"
   "/books/rd.json" "/books/%00" "/books/x%00.html" "/books/%c0%ae%c0%ae/outside.txt"
   "/books/%e0%80%ae%e0%80%ae/outside.txt" "/books/.%00./outside.txt" "/./index.html"
   "/books/./bible/" "/books//bible/" "/index.html/" "http://localhost/../outside.txt"])

(deftest never-serves-state-readers-or-outside-files-test
  (let [{:keys [site]} (built-site)]
    (plant-secrets! site)
    (with-server site
      (fn [srv]
        (let [port (:port srv)]
          (doseq [target never-served]
            (let [{:keys [status body]} (request port "GET" target)]
              (is (contains? #{400 404} status) (str target " -> " status))
              (is (not (str/includes? body secret)) target)))
          (testing "a link to a file inside the site is served"
            (is (= 200 (:status (request port "GET" "/books/app-link.js")))))
          (testing "a raw backslash path is refused before it reaches a file"
            (is (contains? #{400 404} (:status (request port "GET" "/books\\..\\..\\outside.txt"))))))))))

(def ^:private fuzz-pieces
  "Path segments, plain and encoded, that a traversal tries."
  [".." "%2e%2e" "%2E%2e" ".%2e" "%2e." "%252e%252e" "%c0%ae%c0%ae" "." "%2e" ""
   "..%2f" "..%5c" "%5c.." "%2f" "\\.." ".haselnuss" "%2ehaselnuss" "readers" "%72eaders"
   "secret.txt" "x.json" "outside.txt" "books" "out.txt" "hidden" "rd" "outdir" "%00" "state"])

(deftest seeded-traversal-fuzz-test
  (let [{:keys [site]} (built-site)
        rng (Random. 273)
        pick #(nth fuzz-pieces (.nextInt rng (count fuzz-pieces)))
        cases (vec (repeatedly 600 (fn [] (str "/" (str/join "/" (repeatedly (inc (.nextInt rng 5)) pick))))))
        dangerous? #(re-find #"\.\.|%2e|%252e|%c0|haselnuss|readers|%72|outside|out\.txt|hidden|/rd|outdir" %)]
    (plant-secrets! site)
    (with-server site
      (fn [srv]
        (let [port (:port srv)
              results (mapv (fn [target] [target (request port "GET" target)]) cases)]
          (testing "not vacuous: most cases reach for something they must not get"
            (is (< 400 (count (filter (comp dangerous? first) results)))))
          (doseq [[target {:keys [status body]}] results]
            (is (not (str/includes? body secret)) target)
            (is (contains? (if (= "/" target) #{200} #{301 400 404}) status) (str target " -> " status))))))))

(deftest static-path-test
  (is (= ["index.html"] (serve/static-path "/")))
  (is (= ["books" "a b" "index.html"] (serve/static-path "/books/a%20b/")))
  (is (= ["%2e%2e"] (serve/static-path "/%252e%252e")))
  (doseq [raw ["" "x" "/.." "/%2e%2e" "/%2E." "/a/%2fb" "/a/%5cb" "/a//b" "/readers/x.json"
               "/.haselnuss/lock" "/a/%00" "/a/%zz" "/a/%c0%ae" "/a/%e2%82" "/a/%0a"]]
    (is (nil? (serve/static-path raw)) raw)))

;; --- readers and positions ---------------------------------------------------------

(def ^:private romanos "bible:blivre/romanos")

(deftest ^:slow readers-api-test
  (let [{:keys [site]} (built-site)]
    (with-server site
      (fn [srv]
        (let [port (:port srv)]
          (is (= [] (:json (get-json port "/api/readers"))))
          (testing "a new reader is slugged for its file, its name kept"
            (let [{:keys [status json]} (send-json port "POST" "/api/readers" {"name" "  Ána Maria! "})]
              (is (= 201 status))
              (is (= {"id" "ana-maria" "name" "Ána Maria!"} json)))
            (is (.isFile (io/file site "readers" "ana-maria.json")))
            (is (= [{"id" "ana-maria" "name" "Ána Maria!"}] (:json (get-json port "/api/readers")))))
          (testing "POST and PUT need Content-Type: application/json (415), so another site's
                    page cannot send one without a CORS preflight"
            (doseq [ctype ["text/plain" "application/x-www-form-urlencoded" "multipart/form-data; boundary=x"
                           "application/jsonx" nil]]
              (is (= 415 (:status (request port "POST" "/api/readers" :headers {"Content-Type" ctype}
                                           :body "{\"name\": \"Eve\"}")))
                  (pr-str ctype)))
            (is (= 415 (:status (request port "PUT" (str "/api/readers/ana-maria/positions/" romanos)
                                         :headers {"Content-Type" "text/plain"}
                                         :body "{\"savedAt\": \"2026-11-13T21:04:11Z\"}"))))
            (is (not (.exists (io/file site "readers" "eve.json"))))
            (is (= 201 (:status (request port "POST" "/api/readers"
                                         :headers {"Content-Type" "Application/JSON; charset=utf-8"}
                                         :body "{\"name\": \"Eve\"}")))
                "parameters and case do not matter"))
          (testing "409 when the slug exists, 400 for a name with nothing to slug"
            (is (= 409 (:status (send-json port "POST" "/api/readers" {"name" "ana maria"}))))
            (is (= 400 (:status (send-json port "POST" "/api/readers" {"name" "!!!"}))))
            (testing "a name with no letter a-z or digit says so, not that it has none at all"
              (doseq [n ["Анна" "李娜" "ß"]]
                (let [{:keys [status json]} (send-json port "POST" "/api/readers" {"name" n})]
                  (is (= 400 status) n)
                  (is (str/includes? (get json "error") "a-z") n)
                  (is (not (str/includes? (get json "error") "needs a letter or digit")) n))))
            (is (= 400 (:status (send-json port "POST" "/api/readers" {"name" 3}))))
            (is (= 400 (:status (send-json port "POST" "/api/readers" ["Ana"])))))
          (testing "a name that would be a path is only a slug"
            (is (= {"id" "etc-passwd" "name" "../../etc/passwd"}
                   (:json (send-json port "POST" "/api/readers" {"name" "../../etc/passwd"}))))
            (is (.isFile (io/file site "readers" "etc-passwd.json"))))
          (testing "malformed JSON answers 400"
            (doseq [body ["{\"name\":" "{\"name\": \"x\"} trailing" "" "nul" (apply str (repeat 5000 "["))]]
              (is (= 400 (:status (request port "POST" "/api/readers" :body body))) (subs body 0 (min 20 (count body))))))
          (testing "bodies over 64 KB answer 413, by length or while read chunked"
            (let [big (str "{\"name\": \"" (apply str (repeat (* 70 1024) "a")) "\"}")]
              (is (= 413 (:status (request port "POST" "/api/readers" :body big))))
              (is (= 413 (:status (request port "POST" "/api/readers" :body big :chunked? true))))
              (is (= 413 (:status (request port "POST" "/api/readers"
                                           :headers {"Content-Length" "999999999"} :body "{}"))))))
          (testing "a body just under the cap is read"
            (let [body (str "{\"name\": \"Pad\", \"pad\": \"" (apply str (repeat (- (* 64 1024) 40) "a")) "\"}")]
              (is (<= (count body) (* 64 1024)))
              (is (= 201 (:status (request port "POST" "/api/readers" :body body))))))
          (testing "positions"
            (let [put (fn [reader book pos] (send-json port "PUT" (str "/api/readers/" reader "/positions/" book) pos))
                  pos {"id" "sec:q1" "heading" {"index" 3 "text" "Romanos 1"} "offset" 0.35
                       "percent" 41.2 "savedAt" "2026-11-13T21:04:11Z" "device" "iPad" "extra" "dropped"}]
              (is (= {} (:json (get-json port "/api/readers/ana-maria/positions"))))
              (let [{:keys [status json]} (put "ana-maria" romanos pos)]
                (is (= 200 status))
                (is (= {"book" romanos "position" (dissoc pos "extra") "saved" true} json)))
              (testing "the book id may be percent-encoded, the reader written as its name"
                (is (= 200 (:status (put "Ana%20Maria" "bible%3Ablivre%2Fromanos"
                                         (assoc pos "savedAt" "2026-11-13T22:00:00Z"))))))
              (testing "an older save is not kept, compared as instants"
                (let [{:keys [json]} (put "ana-maria" romanos (assoc pos "savedAt" "2026-11-13T22:30:00+01:00" "percent" 1))]
                  (is (false? (get json "saved")))
                  (is (= "2026-11-13T22:00:00Z" (get-in json ["position" "savedAt"])))))
              (is (= {romanos (assoc (dissoc pos "extra") "savedAt" "2026-11-13T22:00:00Z")}
                     (:json (get-json port "/api/readers/ana-maria/positions"))))
              (testing "unknown readers and books answer 404, bad positions 400"
                (is (= 404 (:status (get-json port "/api/readers/bob/positions"))))
                (is (= 404 (:status (put "bob" romanos pos))))
                (is (= 404 (:status (put "..%2f..%2fetc" romanos pos))))
                (is (= 404 (:status (put "ana-maria" "bible:blivre/nope" pos))))
                (is (= 404 (:status (put "ana-maria" "not a book" pos))))
                (is (= 404 (:status (put "ana-maria" "bible:blivre/romanos%23x" pos))))
                (doseq [bad [(dissoc pos "savedAt") (assoc pos "savedAt" "yesterday")
                             (assoc pos "savedAt" "2026-11-13T21:04:11") (assoc pos "offset" "x")
                             (assoc pos "heading" {"index" "x"}) (assoc pos "device" 3) [pos]]]
                  (is (= 400 (:status (put "ana-maria" romanos bad))) (pr-str bad))))
              (testing "a position stays small: strings of at most 1024 characters, numbers a long or double"
                (let [long-text (apply str (repeat 1025 "x"))
                      before (slurp (io/file site "readers" "ana-maria.json"))]
                  (doseq [bad [(assoc pos "device" long-text) (assoc pos "id" long-text)
                               (assoc pos "heading" {"index" 1 "text" long-text})]]
                    (let [{:keys [status json]} (put "ana-maria" romanos bad)]
                      (is (= 400 status))
                      (is (str/includes? (get json "error") "1024"))))
                  (doseq [field ["offset" "percent"]]
                    (is (= 400 (:status (request port "PUT" (str "/api/readers/ana-maria/positions/" romanos)
                                                 :body (str "{\"savedAt\": \"2026-11-13T23:00:00Z\", \"" field "\": "
                                                            (apply str "1" (repeat 3000 "0")) "}"))))
                        field))
                  (is (= 400 (:status (put "ana-maria" romanos (assoc pos "heading" {"index" (bigint 1e30)})))))
                  (is (= before (slurp (io/file site "readers" "ana-maria.json"))) "nothing refused was stored")
                  (is (= 200 (:status (put "ana-maria" romanos (assoc pos "device" (subs long-text 1)
                                                                      "savedAt" "2026-11-13T23:00:00Z")))))))
              (testing "a savedAt more than a day past the server's clock is refused, so it cannot win for good"
                (let [now (java.time.Instant/now)]
                  (doseq [at ["+999999999-12-31T23:59:59Z" (str (.plus now (java.time.Duration/ofHours 25)))]]
                    (let [{:keys [status json]} (put "ana-maria" romanos (assoc pos "savedAt" at))]
                      (is (= 400 status) at)
                      (is (str/includes? (str (get json "error")) "clock") at)))
                  (is (= 200 (:status (put "ana-maria" romanos (assoc pos "savedAt" (str (.plus now (java.time.Duration/ofHours 1))))))))
                  (is (str/includes? (serve/position (assoc pos "savedAt" "2026-11-15T00:00:01Z")
                                                     (java.time.Instant/parse "2026-11-14T00:00:00Z"))
                                     "clock"))
                  (is (map? (serve/position (assoc pos "savedAt" "2026-11-15T00:00:00Z")
                                            (java.time.Instant/parse "2026-11-14T00:00:00Z")))
                      "exactly a day ahead is still accepted")))
              (testing "wrong methods answer 405"
                (is (= 405 (:status (request port "DELETE" "/api/readers"))))
                (is (= 405 (:status (request port "DELETE" (str "/api/readers/ana-maria/positions/" romanos)))))
                (is (= 405 (:status (request port "POST" "/api/books?id=x" :body "{}"))))
                (is (= 405 (:status (request port "POST" "/api/search?q=x" :body "{}"))))))))))))

;; --- AC #3: concurrent writes ------------------------------------------------------

(deftest concurrent-puts-keep-the-newest-test
  (let [{:keys [site]} (built-site)
        books ["bible:blivre/romanos" "bible:blivre/genesis" "reformed:catecismo-heidelberg"]
        rng (Random. 273)
        ;; every device saves each book at a different time, out of order
        ;; (distinct times, so the newest of each book is one save)
        saves (vec (for [device (range 8) i (range 15)]
                     {:book (nth books (.nextInt rng (count books)))
                      :device (str "d" device)
                      :at (java.time.Instant/ofEpochSecond
                           ;; 2023-2026: in the past, as savedAt must be
                           (+ 1700000000 (* 1000 (.nextInt rng 60000)) (* 15 device) i))}))]
    (with-server site
      (fn [srv]
        (let [port (:port srv)]
          (is (= 201 (:status (send-json port "POST" "/api/readers" {"name" "Ana"}))))
          (let [results (->> saves
                             (group-by :device)
                             vals
                             (mapv (fn [device-saves]
                                     (future
                                       (mapv (fn [{:keys [book device at]}]
                                               (:status (send-json port "PUT" (str "/api/readers/ana/positions/" book)
                                                                   {"savedAt" (str at) "device" device "percent" 1})))
                                             device-saves))))
                             (mapcat deref))]
            (is (= 120 (count results)))
            (is (every? #{200} results)))
          (let [stored (json/read-str (slurp (io/file site "readers" "ana.json")))]
            (is (= "Ana" (get stored "name")))
            (doseq [book books
                    :let [newest (apply max-key #(.getEpochSecond ^java.time.Instant (:at %))
                                        (filter #(= book (:book %)) saves))]]
              (is (= (str (:at newest)) (get-in stored ["positions" book "savedAt"])) book)
              (is (= (:device newest) (get-in stored ["positions" book "device"])) book)))
          (testing "no temporary file is left behind"
            (is (= ["ana.json"] (vec (sort (.list (io/file site "readers"))))))))))))

(deftest newer-test
  (let [a {"savedAt" "2026-11-13T21:04:11Z"}
        b {"savedAt" "2026-11-13T22:04:10+01:00"}]
    (is (= a (serve/newer a b)))
    (is (= a (serve/newer b a)))
    (is (= b (serve/newer {"savedAt" "garbage"} b)))
    (is (= b (serve/newer nil b)))
    (is (= b (serve/newer {"savedAt" "2026-11-13T21:04:09.999Z"} b)) "the earlier one loses")
    (is (= {"savedAt" "2026-11-13T21:04:10.001Z"} (serve/newer {"savedAt" "2026-11-13T21:04:10.001Z"} b))
        "the later one wins, by a millisecond")))

;; --- one position, and books by id (TASK-276) ---------------------------------------

(deftest one-position-and-books-by-id-test
  (let [{:keys [src site]} (built-site)]
    (with-server site
      (fn [srv]
        (let [port (:port srv)
              pos {"id" "sec:q1" "heading" {"index" 3 "text" "Romanos 1"} "offset" 0.35
                   "percent" 41.2 "savedAt" "2026-11-13T21:04:11Z" "device" "iPad"}]
          (is (= 201 (:status (send-json port "POST" "/api/readers" {"name" "Ana"}))))
          (testing "GET of one position: null before a save, the saved one after, the book id encoded or not"
            (is (= {"book" romanos "position" nil} (:json (get-json port "/api/readers/ana/positions/bible%3Ablivre%2Fromanos"))))
            (is (= 200 (:status (send-json port "PUT" (str "/api/readers/ana/positions/" romanos) pos))))
            (is (= {"book" romanos "position" pos} (:json (get-json port (str "/api/readers/ana/positions/" romanos)))))
            (is (= {"book" romanos "position" pos} (:json (get-json port "/api/readers/ana/positions/bible%3Ablivre%2Fromanos"))))
            (is (= 200 (:status (request port "HEAD" (str "/api/readers/ana/positions/" romanos))))))
          (testing "an unknown reader or a malformed book id answers 404; a book not in the catalog, null"
            (is (= 404 (:status (get-json port (str "/api/readers/bob/positions/" romanos)))))
            (is (= 404 (:status (get-json port "/api/readers/ana/positions/not%20a%20book"))))
            (is (= 404 (:status (get-json port "/api/readers/ana/positions/bible:blivre/romanos%23x"))))
            (is (= {"book" "bible:blivre/nope" "position" nil} (:json (get-json port "/api/readers/ana/positions/bible:blivre/nope")))))
          (testing "books by id: each catalog entry asked for, unknown ids left out"
            (let [{:keys [status json headers]} (get-json port "/api/books?id=bible%3Ablivre%2Fromanos&id=reformed:catkins&id=nope:x&id=")]
              (is (= 200 status))
              (is (some? (get headers "date")) "every answer says the server's time, which the app uses to correct a device clock")
              (is (= #{romanos "reformed:catkins"} (set (keys (get json "books")))))
              (is (= {"id" romanos "title" "Romanos" "authors" [] "library" "bible" "shelf" "blivre"
                      "path" "books/bible/blivre/romanos/index.html"}
                     (dissoc (get-in json ["books" romanos]) "colour")))
              (is (re-matches #"#[0-9a-f]{6}" (get-in json ["books" romanos "colour"])))))
          (is (= {"books" {}} (:json (get-json port "/api/books"))))
          (testing "at most 100 ids at a time; a malformed escape is refused"
            (is (= 200 (:status (get-json port (str "/api/books?" (str/join "&" (repeat 100 "id=x")))))))
            (is (= 400 (:status (get-json port (str "/api/books?" (str/join "&" (repeat 101 "id=x")))))))
            (is (= 400 (:status (request port "GET" "/api/books?id=%zz"))))
            (let [query-params #'serve/query-params]
              (testing "the ids are read only until there are too many: nothing past the limit is decoded"
                (is (= ["a" "b" "c"] (query-params "id=a&q=x&id=b&id=c&id=%zz&id=e" "id" 3)))
                (is (thrown? IllegalArgumentException (query-params "id=a&q=x&id=b&id=c&id=%zz&id=e" "id" 4)))
                (is (= ["" "x"] (query-params "id&&id=x&" "id" 10)))
                (is (= [] (query-params "" "id" 10))))))
          (testing "a book a rebuild removed is no longer answered"
            (io/delete-file (io/file src "reformed" "catkins" "book.hdoc"))
            (is (contains? #{0 1} (build! src site)))
            (is (= {"books" {}} (:json (get-json port "/api/books?id=reformed:catkins"))))))))))

;; --- AC #4: search -----------------------------------------------------------------

(defn- titles [result] (mapv #(get % "title") (get-in result [:json "books"])))

(deftest search-sees-a-rebuilt-catalog-test
  (let [{:keys [src site]} (built-site)]
    (with-server site
      (fn [srv]
        (let [port (:port srv)]
          (testing "title, author or library; accents and case folded; every word"
            (is (= ["Romanos"] (titles (get-json port "/api/search?q=ROMANOS"))))
            (is (= ["Gênesis"] (titles (get-json port "/api/search?q=genesis"))))
            (is (= ["Gênesis"] (titles (get-json port "/api/search?q=g%C3%AAn+bible"))))
            (is (= 5 (count (titles (get-json port "/api/search?q=bible")))))
            (is (= [] (titles (get-json port "/api/search?q="))))
            (is (= [] (titles (get-json port "/api/search"))))
            (is (= [] (titles (get-json port "/api/search?q=zzzz")))))
          (testing "a rebuild with a new title is found without a restart"
            (let [f (io/file src "reformed" "dangling.hdoc")]
              (spit f (str/replace (slurp f) "title: A Dangling Reference" "title: Ein Haselnussstrauch"))
              (is (= [] (titles (get-json port "/api/search?q=haselnussstrauch"))))
              (is (contains? #{0 1} (build! src site)))
              (is (= ["Ein Haselnussstrauch"] (titles (get-json port "/api/search?q=haselnussstrauch"))))
              (is (= [] (titles (get-json port "/api/search?q=dangling+reference"))))))
          (testing "a catalog page that cannot be read keeps the catalog there was"
            (let [page (io/file site "catalog" "reformed" "1.json")]
              (spit page "{not json")
              (is (= ["Ein Haselnussstrauch"] (titles (get-json port "/api/search?q=haselnussstrauch")))))))))))

;; --- latency -------------------------------------------------------------------------

;; Not tagged as a timing test (TASK-302), though it asserts a wall-clock bound: the bound
;; has ~30x headroom (60 requests take ~0.05 s) and held in a run that
;; overloaded the machine, while splitting this namespace across two of
;; bin/test's JVMs would build `shared-site` -- and count its assertion --
;; twice, so bin/test's assertion count would no longer equal lein test's.
(deftest kept-alive-requests-do-not-wait-on-delayed-acks-test
  (testing "TCP_NODELAY: with Nagle's algorithm on, every response on a kept-alive
            connection waited ~40 ms for the client's delayed ACK (60 requests: ~2.6 s)"
    (let [{:keys [site]} @shared-site]
      (with-server site
        (fn [srv]
          (let [client (-> (java.net.http.HttpClient/newBuilder)
                           (.version java.net.http.HttpClient$Version/HTTP_1_1)
                           (.build))
                req (.build (java.net.http.HttpRequest/newBuilder
                             (java.net.URI/create (str "http://127.0.0.1:" (:port srv) "/app.js"))))
                fetch #(.statusCode (.send client req (java.net.http.HttpResponse$BodyHandlers/ofString)))
                _ (fetch)
                start (System/nanoTime)
                statuses (vec (repeatedly 60 fetch))
                seconds (/ (- (System/nanoTime) start) 1e9)]
            (is (every? #{200} statuses))
            (is (< seconds 1.5) (str "60 kept-alive requests took " seconds " s"))))))))

;; --- clients that stall or go away --------------------------------------------------

(deftest server-properties-are-set-and-baked-into-the-native-image-test
  (testing "start! sets the JDK server's settings (unless given), and bin/native passes the same"
    (with-server (:site @shared-site)
      (fn [_]
        (doseq [[k v] serve/server-properties]
          (is (some? (System/getProperty k)) k)
          (is (str/includes? (slurp "bin/native") (str "-D" k "=" v)) k))))
    (is (= 30 serve/max-request-seconds))
    (is (= "true" (get serve/server-properties "sun.net.httpserver.nodelay")))))

(def ^:private slow-senders-script
  "Run in a JVM of its own (the JDK server reads its settings once per
  process, and this one's are taken): serve the site `t.site`, open ten
  connections that send half a request and stop -- more than the server's
  eight threads -- wait (8 s at most each) for the server to close them,
  then ask for app.js. Prints `{:status :seconds :closed}`: the status
  line answered (or the error), how long the ten took to be closed, and
  how many were. (A request made while the ten hold every thread waits in
  the queue, and maxReqTime counts that wait too: it is closed with
  them, and a client tries again.)"
  "(require '[haselnuss.serve :as serve])
   (let [srv (serve/start! {:dir (System/getProperty \"t.site\") :port 0})
         port (int (:port srv))
         half (.getBytes \"GET /app.js HTTP/1.1\\r\\nHost: localhost\\r\\n\" \"ISO-8859-1\")
         held (vec (for [_ (range 10)]
                     (let [s (java.net.Socket. \"127.0.0.1\" port)]
                       (.write (.getOutputStream s) half)
                       s)))
         t0 (System/nanoTime)
         closed (count (filter (fn [^java.net.Socket s]
                                 (.setSoTimeout s 8000)
                                 (try (neg? (.read (.getInputStream s)))
                                      (catch java.net.SocketTimeoutException _ false)
                                      (catch java.io.IOException _ true)))
                               held))
         seconds (/ (- (System/nanoTime) t0) 1e9)
         status (with-open [s (java.net.Socket. \"127.0.0.1\" port)]
                  (.setSoTimeout s 8000)
                  (.write (.getOutputStream s)
                          (.getBytes \"GET /app.js HTTP/1.1\\r\\nHost: localhost\\r\\nConnection: close\\r\\n\\r\\n\" \"ISO-8859-1\"))
                  (try (.readLine (java.io.BufferedReader. (java.io.InputStreamReader. (.getInputStream s))))
                       (catch java.io.IOException e (str e))))]
     (prn {:status status :seconds seconds :closed closed})
     (flush)
     (System/exit 0))")

(defn- run-slow-senders
  "`slow-senders-script` in a new JVM on this one's class path, with
  `max-req-time` as `sun.net.httpserver.maxReqTime`: what it printed,
  read."
  [site max-req-time]
  (let [java (str (System/getProperty "java.home") "/bin/java")
        command [java "-cp" (System/getProperty "java.class.path")
                 (str "-Dsun.net.httpserver.maxReqTime=" max-req-time)
                 (str "-Dt.site=" site)
                 "clojure.main" "-e" slow-senders-script]
        pb (doto (ProcessBuilder. ^java.util.List command)
             (.redirectErrorStream true))
        p (.start pb)
        out (future (slurp (.getInputStream p)))]
    (if (.waitFor p 180 java.util.concurrent.TimeUnit/SECONDS)
      (let [text @out
            line (last (filter #(str/starts-with? % "{:status") (str/split-lines text)))]
        (is line text)
        (when line (read-string line)))
      (do (.destroyForcibly p) (is false "the slow-senders JVM did not finish") nil))))

(deftest ^:slow half-sent-requests-do-not-wedge-the-server-test
  (testing "ten connections sending half a request each held all eight threads for
            good before maxReqTime was set; now they are closed after it, and a
            normal request is answered (1 s here, to keep the test fast; 30 s served)"
    (let [{:keys [status seconds closed]} (run-slow-senders (:site @shared-site) 1)]
      (is (= 10 closed))
      (is (and seconds (< seconds 5.0)) (str "closed after " seconds " s"))
      (is (= "HTTP/1.1 200 OK" status)))))

(defn- capture-err
  "`(f)` with the root binding of `*err*` -- what the server's threads
  print to -- captured; what was printed."
  [f]
  (let [w (StringWriter.)
        old *err*]
    (alter-var-root #'*err* (constantly w))
    (try (f) (finally (alter-var-root #'*err* (constantly old))))
    (str w)))

(defn- reset-close!
  "Close the socket `s` with a TCP reset, as a client that vanished does."
  [^Socket s]
  (.setSoLinger s true 0)
  (.close s))

(deftest clients-that-go-away-are-not-internal-errors-test
  (let [{:keys [site]} (built-site)
        big (io/file site "books" "big.txt")]
    (spit big (apply str (repeat (* 8 1024) (apply str (repeat 1023 "x") "\n"))))
    (with-server site
      (fn [srv]
        (let [port (int (:port srv))
              printed
              (capture-err
               (fn []
                 (testing "a client that hangs up mid-response"
                   (dotimes [_ 3]
                     (let [s (Socket. "127.0.0.1" port)]
                       (.write (.getOutputStream s)
                               (.getBytes "GET /books/big.txt HTTP/1.1\r\nHost: localhost\r\n\r\n" "ISO-8859-1"))
                       (.read (.getInputStream s) (byte-array 1024))
                       (reset-close! s))))
                 (testing "a client that hangs up while sending a body"
                   (is (= 201 (:status (send-json port "POST" "/api/readers" {"name" "Ana"}))))
                   (let [s (Socket. "127.0.0.1" port)]
                     (.write (.getOutputStream s)
                             (.getBytes (str "PUT /api/readers/ana/positions/" romanos " HTTP/1.1\r\nHost: localhost\r\n"
                                             "Content-Type: application/json\r\nContent-Length: 500\r\n\r\n{\"savedAt\"")
                                        "ISO-8859-1"))
                     (Thread/sleep 200)
                     (reset-close! s)))
                 (Thread/sleep 1000)
                 ;; the server still answers
                 (is (= 200 (:status (request port "GET" "/app.js"))))))]
          (is (not (str/includes? printed "haselnuss: --serve")) printed))))
    (.delete big)
    (testing "a file a build removes after it was found answers 404, not 500"
      (with-server site
        (fn [srv]
          (let [gone (.toPath (io/file site "books" "gone.html"))]
            (with-redefs [serve/resolve-static (fn [_ _] {:path gone :segments ["books" "gone.html"]})]
              (let [{:keys [status headers]} (request (:port srv) "GET" "/books/gone.html")]
                (is (= 404 status))
                (is (nil? (get headers "etag")))))))))))

(deftest search-limit-test
  (let [books (vec (for [i (range 120)] {"id" (str "l:b" i) "title" (str "Book " i) "authors" ["Ana"] "library" "l"
                                         :haselnuss.serve/haystack (str "book " i "\nana\nl")}))
        r (serve/search {:books books} "book")]
    (is (= 50 (count (get r "books"))))
    (is (true? (get r "more")))
    (is (not-any? #(contains? % :haselnuss.serve/haystack) (get r "books")))
    (is (false? (get (serve/search {:books books} "book 7") "more")))))

(deftest search-memory-shape-test
  (testing "the server keeps the catalog it answers from"
    (let [{:keys [site]} @shared-site
          c (serve/load-catalog (.toRealPath (.toPath ^File site) (make-array LinkOption 0)))]
      (is (= 9 (count (:books c))))
      (is (contains? (:by-id c) romanos))
      (testing "one map per book: :by-id holds the very maps :books does (TASK-278)"
        (is (identical? (get (:by-id c) romanos)
                        (first (filter #(= romanos (get % "id")) (:books c))))))
      (testing "and key names and repeated values are held once, not once per book"
        (let [[a b] (filter #(= "bible" (get % "library")) (:books c))]
          (is (identical? (get a "library") (get b "library")))
          (is (identical? (some #(when (= "title" %) %) (keys a))
                          (some #(when (= "title" %) %) (keys b)))))))))

;; --- TASK-292: only requests made to this server's own names ------------------------

(defn- with-server-opts
  "`with-server` with extra `serve/start!` options (`:hosts`, `:bind`)."
  [dir opts f]
  (let [srv (serve/start! (merge {:dir (str dir) :port 0 :reload-interval-ms 0 :threads 4} opts))]
    (try (f srv) (finally (serve/stop! srv)))))

(def ^:private tailnet "home.tailnet.ts.net")

(defn- every-route
  "`[method target body]` for every kind of request the server answers:
  static files, a folder, a redirect, a missing file, and every /api
  endpoint and method."
  []
  [["GET" "/" nil] ["GET" "/index.html" nil] ["HEAD" "/app.js" nil] ["GET" "/sw.js" nil]
   ["GET" "/catalog/index.json" nil] ["GET" "/books/bible/blivre/romanos/" nil]
   ["GET" "/books/bible/blivre/romanos" nil] ["GET" "/no/such/file" nil]
   ["GET" "/api/readers" nil] ["HEAD" "/api/readers" nil] ["POST" "/api/readers" {"name" "Eve"}]
   ["GET" "/api/readers/ana/positions" nil]
   ["GET" (str "/api/readers/ana/positions/" romanos) nil]
   ["PUT" (str "/api/readers/ana/positions/" romanos) {"savedAt" "2026-11-13T21:04:11Z" "percent" 99}]
   ["GET" "/api/books?id=bible:blivre/romanos" nil] ["GET" "/api/search?q=romanos" nil]
   ["DELETE" "/api/readers" nil] ["GET" "/api/nothing" nil]])

(deftest foreign-host-is-refused-everywhere-test
  (testing "AC #1: a Host that is not the server's own answers 421 for static files and every
            /api endpoint -- before this, GET /api/readers with Host: evil.example answered 200"
    (let [{:keys [site]} (built-site)]
      (with-server-opts site {:hosts [tailnet]}
        (fn [srv]
          (let [port (:port srv)]
            (is (= 201 (:status (send-json port "POST" "/api/readers" {"name" "Ana"}))))
            (is (= 200 (:status (send-json port "PUT" (str "/api/readers/ana/positions/" romanos)
                                           {"savedAt" "2026-11-13T21:04:11Z" "percent" 10}))))
            (doseq [host ["evil.example" "evil.example:8080" "EVIL.example." "localhost.evil.example"
                          "evil.localhost" "127.0.0.1.nip.io" "ts.net" "other.tailnet.ts.net"
                          "home.tailnet.ts.net.evil.example" "0.0.0.0" "[::]" "192.0.2.1" "10.0.0.1:8080"
                          "[2001:db8::1]:8080" "2130706433" "localhost-" "xn--localhost"]
                    [method target body] (every-route)]
              (let [{:keys [status headers] :as r}
                    (request port method target :host host :body (some-> body json/write-str))]
                (is (= 421 status) (pr-str host method target))
                (is (= "nosniff" (get headers "x-content-type-options")))
                (is (not (str/includes? (:body r) "Ana")) (pr-str host method target))))
            (testing "and nothing it sent was done"
              (is (not (.exists (io/file site "readers" "eve.json"))))
              (is (= 10 (get-in (get-json port (str "/api/readers/ana/positions/" romanos))
                                [:json "position" "percent"]))))))))))

(deftest malformed-host-is-refused-test
  (with-server-opts (:site @shared-site) {}
    (fn [srv]
      (let [port (:port srv)]
        (testing "400 for a missing, repeated or malformed Host (RFC 9112)"
          (doseq [[label opts] [["none, HTTP/1.1" {:host nil}]
                                ["none, HTTP/1.0" {:host nil :version "1.0"}]
                                ["two" {:host ["localhost" "localhost"]}]
                                ["two, one foreign" {:host ["localhost" "evil.example"]}]
                                ["empty" {:host ""}]
                                ["a list" {:host "localhost, evil.example"}]
                                ["user info" {:host "evil.example@localhost"}]
                                ["a path" {:host "localhost/x"}]
                                ["a space" {:host "local host"}]
                                ["an underscore" {:host "local_host"}]
                                ["an empty label" {:host "localhost..x"}]
                                ["two trailing dots" {:host "localhost.."}]
                                ["a long port" {:host "localhost:123456"}]
                                ["a named port" {:host "localhost:http"}]
                                ["unclosed bracket" {:host "[::1"}]
                                ["bracketed name" {:host "[localhost]"}]
                                ["bracketed IPv4" {:host "[127.0.0.1]"}]
                                ["zone id" {:host "[fe80::1%25lo]"}]
                                ["bare IPv6" {:host "::1"}]
                                ["latin-1 letter" {:host "localhöst"}]]]
            (is (= 400 (:status (apply request port "GET" "/api/readers" (mapcat identity opts)))) label)))
        (testing "421 for an absolute request target naming another host, whatever Host says"
          (is (= 421 (:status (request port "GET" "http://evil.example/api/readers"))))
          (is (= 421 (:status (request port "GET" "http://evil.example:80/index.html"))))
          (is (= 200 (:status (request port "GET" (str "http://localhost:" port "/api/readers"))))))))))

(deftest own-names-are-accepted-test
  (testing "AC #2: localhost, loopback, the bind address and each --host name, with or without a port"
    (with-server-opts (:site @shared-site) {:hosts ["Home.Tailnet.TS.net" "192.0.2.7" "[2001:db8::7]"]}
      (fn [srv]
        (let [port (:port srv)]
          (doseq [host ["localhost" (str "localhost:" port) "LocalHost" "localhost." "LOCALHOST.:8080"
                        "localhost:" "127.0.0.1" (str "127.0.0.1:" port) "127.0.0.1:80" "127.1.2.3"
                        "[::1]" (str "[::1]:" port) "[0:0:0:0:0:0:0:1]" "[::ffff:127.0.0.1]"
                        tailnet (str tailnet ":443") "HOME.tailnet.ts.net." "192.0.2.7" "192.0.2.7:8080"
                        "[2001:DB8:0::7]:1"]]
            (is (= 200 (:status (request port "GET" "/api/readers" :host host))) host)
            (is (= 200 (:status (request port "GET" "/app.js" :host host))) host)))))
    (testing "a server bound to a name answers to it"
      (with-server-opts (:site @shared-site) {:bind "localhost"}
        (fn [srv]
          (is (= 200 (:status (request (:port srv) "GET" "/" :host "localhost")))))))
    (let [lan (->> (java.util.Collections/list (java.net.NetworkInterface/getNetworkInterfaces))
                   (filter #(.isUp ^java.net.NetworkInterface %))
                   (mapcat #(java.util.Collections/list (.getInetAddresses ^java.net.NetworkInterface %)))
                   (filter #(instance? java.net.Inet4Address %))
                   (remove #(or (.isLoopbackAddress ^java.net.InetAddress %) (.isLinkLocalAddress ^java.net.InetAddress %)))
                   first)]
      (if-not lan
        (println "own-names-are-accepted-test: no non-loopback IPv4 interface; bind-address cases skipped")
        (let [ip (.getHostAddress ^java.net.InetAddress lan)]
          (testing (str "bound to " ip ", that address is accepted; another is not")
            (with-server-opts (:site @shared-site) {:bind ip}
              (fn [srv]
                (let [port (:port srv)]
                  (is (= 200 (:status (request port "GET" "/" :addr ip :host ip))))
                  (is (= 200 (:status (request port "GET" "/" :addr ip :host (str ip ":" port)))))
                  (is (= 200 (:status (request port "GET" "/" :addr ip :host "localhost"))))
                  (is (= 421 (:status (request port "GET" "/" :addr ip :host "192.0.2.1"))))
                  (is (= 421 (:status (request port "GET" "/" :addr ip :host "evil.example"))))))))
          (testing "bound to 0.0.0.0, the address a request arrived on is accepted -- never any host"
            (with-server-opts (:site @shared-site) {:bind "0.0.0.0"}
              (fn [srv]
                (let [port (:port srv)]
                  (is (= 200 (:status (request port "GET" "/" :addr ip :host (str ip ":" port)))))
                  (is (= 200 (:status (request port "GET" "/" :addr "127.0.0.1" :host "localhost"))))
                  (is (= 421 (:status (request port "GET" "/" :addr "127.0.0.1" :host ip)))
                      "an interface address other than the one connected to is not this connection's")
                  (is (= 421 (:status (request port "GET" "/" :addr ip :host "0.0.0.0"))))
                  (is (= 421 (:status (request port "GET" "/" :addr ip :host "evil.example"))))
                  (is (= 421 (:status (request port "GET" "/" :addr ip :host "192.0.2.1")))))))))))))

(deftest foreign-origin-may-not-write-test
  (let [{:keys [site]} (built-site)]
    (with-server-opts site {:hosts [tailnet]}
      (fn [srv]
        (let [port (:port srv)
              here (str "localhost:" port)
              post (fn [host origin reader]
                     (:status (request port "POST" "/api/readers" :host host :headers {"Origin" origin}
                                       :body (json/write-str {"name" reader}))))]
          (testing "a write whose Origin is not the request's own host and port answers 403 and
                    does nothing: foreign, null, not http(s), or another port of the same name"
            (doseq [[host origin] [[here "http://evil.example"] [here "https://evil.example:8080"] [here "null"]
                                   [here "file://"] [here "chrome-extension://abc"]
                                   [here "http://localhost.evil.example"] [here (str "http://" here "/x")]
                                   [here "localhost"] [here ""]
                                   ;; review of TASK-292: another local port passed when only the
                                   ;; host was compared
                                   [here "http://localhost:3000"] [here "http://localhost"]
                                   [here (str "http://127.0.0.1:" port)] ["localhost" "http://localhost:3000"]
                                   ["localhost:80" "https://localhost"] [tailnet (str "https://" tailnet ":8443")]
                                   [(str tailnet ":8443") (str "https://" tailnet)] [tailnet "https://localhost"]]]
              (is (= 403 (post host origin "Eve")) (pr-str host origin)))
            (is (= 403 (:status (request port "PUT" (str "/api/readers/eve/positions/" romanos)
                                         :host here :headers {"Origin" "http://localhost:3000"}
                                         :body "{\"savedAt\": \"2026-11-13T21:04:11Z\"}"))))
            (is (not (.exists (io/file site "readers" "eve.json")))))
          (testing "its own origin (a port left out is the scheme's default), no Origin, and reads
                    from anywhere are let through"
            (doseq [[host origin reader] [[here (str "http://" here) "Ana"]
                                          [(str "127.0.0.1:" port) (str "http://127.0.0.1:" port) "Bea"]
                                          [tailnet (str "https://" tailnet) "Cid"]
                                          [(str tailnet ":443") (str "https://" tailnet) "Cy"]
                                          [tailnet (str "https://" tailnet ":443") "Cyd"]
                                          [(str "LOCALHOST.:" port) (str "HTTP://localhost:" port) "Dan"]
                                          [(str "[::1]:" port) (str "http://[0:0:0:0:0:0:0:1]:" port) "Del"]
                                          ["localhost" "http://localhost" "Dot"]
                                          ["localhost:80" "http://localhost" "Dov"]]]
              (is (= 201 (post host origin reader)) (pr-str host origin)))
            (is (= 201 (:status (send-json port "POST" "/api/readers" {"name" "Eli"}))))
            (is (= 200 (:status (request port "GET" "/api/readers" :headers {"Origin" "http://evil.example"}))))))))))

(deftest host-authority-test
  (is (= {:name "localhost" :port "8080"} (serve/host-authority "LocalHost.:8080")))
  (is (= {:name "localhost" :port nil} (serve/host-authority " localhost ")))
  (is (= "::1" (some-> (serve/host-authority "[::1]:80") :address .getHostAddress (str/replace #"0:0:0:0:0:0:0:1" "::1"))))
  (testing "no letter outside ASCII lower-cases into an allowed name (Kelvin sign, dotted I)"
    (is (nil? (serve/host-authority "Kiosk")))
    (is (nil? (serve/host-authority "İocalhost"))))
  (testing "a name is never looked up: one that does not resolve parses the same"
    (is (= {:name "no-such-host.invalid" :port nil} (serve/host-authority "no-such-host.invalid"))))
  (testing "allowed-hosts refuses a --host with a port, the wildcard, or a non-name"
    (doseq [h ["home:8080" "0.0.0.0" "[::]" "" "a b" "http://home" "[::1]:1"]]
      (is (thrown? IllegalArgumentException (serve/allowed-hosts nil nil [h])) h))
    (is (= #{"localhost" "home.ts.net"} (:names (serve/allowed-hosts "0.0.0.0" nil ["HOME.ts.net."])))))
  (testing "a bare IPv6 --host is told to use brackets; a bracketed one is taken"
    (doseq [h ["::1" "2001:db8::7"]]
      (let [msg (try (serve/allowed-hosts nil nil [h]) nil (catch IllegalArgumentException e (ex-message e)))]
        (is (str/includes? (str msg) (str "an IPv6 address goes in brackets: [" h "]")) msg)))
    (is (not (str/includes? (try (serve/allowed-hosts nil nil ["home:8080"]) "" (catch IllegalArgumentException e (ex-message e)))
                            "brackets")))
    (is (= 1 (count (:addresses (serve/allowed-hosts nil nil ["[::1]"]))))))
  (testing "answers-to lists what is accepted, for the startup line"
    (let [lo (java.net.InetAddress/getByName "127.0.0.1")
          any (java.net.InetAddress/getByName "0.0.0.0")]
      (is (= "localhost, home.ts.net, pi.local, 127.0.0.1, 192.0.2.7, [2001:db8:0:0:0:0:0:7], any loopback address"
             (serve/answers-to (serve/allowed-hosts "127.0.0.1" lo ["Pi.local" "home.ts.net" "192.0.2.7" "[2001:db8::7]"])
                               lo)))
      (is (= "localhost, any loopback address, the address a request arrives on"
             (serve/answers-to (serve/allowed-hosts "0.0.0.0" any []) any))))))

(deftest same-origin-test
  (let [auth serve/host-authority]
    (is (serve/same-origin? (auth "h.ts.net") "https://h.ts.net"))
    (is (serve/same-origin? (auth "h.ts.net:443") "https://H.ts.net."))
    (is (serve/same-origin? (auth "h.ts.net") "http://h.ts.net:80"))
    (is (serve/same-origin? (auth "localhost:8080") "http://localhost:08080"))
    (is (not (serve/same-origin? (auth "localhost:8080") "http://localhost:3000")))
    (is (not (serve/same-origin? (auth "localhost:8080") "http://localhost")))
    (is (not (serve/same-origin? (auth "h.ts.net") "https://h.ts.net:8443")))
    (is (not (serve/same-origin? (auth "localhost:8080") "http://127.0.0.1:8080")))
    (is (not (serve/same-origin? (auth "localhost") "null")))
    (is (not (serve/same-origin? (auth "localhost") "ftp://localhost")))))

(deftest seeded-host-fuzz-test
  (testing "seed 292: a host with a known verdict keeps it under case, a trailing dot and a port,
            and loses it under any inserted character"
    (let [rnd (Random. 292)
          pick (fn [xs] (nth xs (.nextInt rnd (count xs))))
          good ["localhost" "127.0.0.1" "127.1.2.3" "[::1]" "[0:0:0:0:0:0:0:1]" "[::ffff:127.0.0.1]" tailnet]
          bad ["evil.example" "localhost.evil.example" "evil.localhost" "127.0.0.1.nip.io" "0.0.0.0" "[::]"
               "192.0.2.1" "[2001:db8::1]" "home.tailnet.ts.net.evil" "tailnet.ts.net" "localhostx"]
          junk "_@/%,;!~*x#?\\"
          vary-case (fn [^String s] (apply str (map #(if (.nextBoolean rnd) (str/upper-case (str %)) (str %)) s)))
          preserve (fn [^String h]
                     (let [bracket? (str/starts-with? h "[")
                           h (vary-case h)
                           h (if (and (not bracket?) (.nextBoolean rnd)) (str h ".") h)]
                       (if (.nextBoolean rnd)
                         (str h ":" (subs (str (+ 100000 (.nextInt rnd 900000))) 1 (inc (.nextInt rnd 6))))
                         h)))
          corrupt (fn [^String h]
                    (let [i (.nextInt rnd (inc (count h)))]
                      (str (subs h 0 i) (pick junk) (subs h i))))
          cases (vec (for [_ (range 300)]
                       (let [good? (.nextBoolean rnd)
                             base (pick (if good? good bad))
                             corrupt? (< (.nextInt rnd 4) 1)
                             host (cond-> (preserve base) corrupt? corrupt)]
                         {:host host :ok? (and good? (not corrupt?)) :corrupt? corrupt?})))]
      (with-server-opts (:site @shared-site) {:hosts [tailnet]}
        (fn [srv]
          (doseq [{:keys [host ok?]} cases]
            (let [status (:status (request (:port srv) "GET" "/api/readers" :host host))]
              (if ok?
                (is (= 200 status) (pr-str host))
                (is (contains? #{400 421} status) (pr-str host)))))))
      (testing "not vacuous"
        (is (< 100 (count (filter :ok? cases))))
        (is (< 50 (count (filter :corrupt? cases))))
        (is (< 30 (count (filter #(str/ends-with? (str/replace (:host %) #":[0-9]*$" "") ".") cases))))
        (is (< 50 (count (filter #(re-find #":[0-9]*$" (str/replace (:host %) #"^\[.*\]" "")) cases))))))))

;; --- TASK-294: a bounded request target ---------------------------------------------

(deftest request-target-is-bounded-test
  (let [{:keys [site]} (built-site)
        long-q (apply str (repeat 20000 "a"))]
    (with-server site
      (fn [srv]
        (let [port (:port srv)]
          (testing "AC #1: a target over the bound answers 414 for static paths and every endpoint"
            (doseq [[method target _] (every-route)
                    :let [r (request port method (str target (if (str/includes? target "?") "&" "?")
                                                      "pad=" long-q))]]
              (is (= 414 (:status r)) (str method " " target))))
          (testing "a 4 million pair query is refused fast, not walked"
            ;; no wall-clock bound: sending 16 MB dominates and varies under
            ;; load; a 414 is the proof that the pairs were never walked
            (let [r (request port "GET" (str "/api/books?" (apply str (repeat 4000000 "x=1&"))))]
              (is (contains? #{414 nil} (:status r)))))
          (testing "AC #2: an over-long q is refused, never echoed; one at the bound is answered"
            (let [r (request port "GET" (str "/api/search?q=" (apply str (repeat 7000 "a"))))]
              (is (= 400 (:status r)))
              (is (< (count (:body r)) 200)))
            (let [q (apply str (repeat serve/max-query-length "a"))
                  r (get-json port (str "/api/search?q=" q))]
              (is (= 200 (:status r)))
              (is (= q (get-in r [:json "query"])))))
          (testing "a target of exactly the bound is answered, one more is not"
            (let [pad (fn [n] (str "/api/search?q=x&p=" (apply str (repeat (- n (count "/api/search?q=x&p=")) "a"))))]
              (is (= 200 (:status (request port "GET" (pad serve/max-uri-length)))))
              (is (= 414 (:status (request port "GET" (pad (inc serve/max-uri-length))))))))
          (testing "AC #3: /api/books with 100 ids still works"
            (let [ids (str/join "&" (repeat 100 (str "id=" romanos)))
                  r (get-json port (str "/api/books?" ids))]
              (is (= 200 (:status r)))
              (is (contains? (get-in r [:json "books"]) romanos)))))))))

(deftest opaque-request-target-never-reaches-a-route-test
  ;; The JDK server looks a request's context up by `uri.getPath()`, which is
  ;; null for an opaque target (`http:host/x`); that NPEs inside the server
  ;; and it closes the connection, before any handler or filter runs. So
  ;; `bad-target`'s 400 cannot be answered for these (the task asked for one):
  ;; what holds is that nothing is served and the server carries on.
  (let [{:keys [site]} (built-site)]
    (with-server site
      (fn [srv]
        (doseq [method ["GET" "HEAD"]
                target ["http:evil.example/api/readers" "mailto:a@b.c" "http:x"]]
          (is (not= 200 (:status (request (:port srv) method target))) (str method " " target)))
        (testing "a raw path that does not start with / is a 400 (bad-target), though it decodes to one"
          (doseq [method ["GET" "HEAD"]]
            (is (= 400 (:status (request (:port srv) method "%2Fapi/readers"))) method)))
        (is (= 200 (:status (request (:port srv) "GET" "/api/readers"))))))))

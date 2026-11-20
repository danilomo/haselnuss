(ns haselnuss.serve
  "`--serve DIR` (doc-12, \"The server: --serve\"; TASK-273): hand out a
  site `--build-site` built and keep each reader's reading positions. It
  never builds, and needs no restart after a build: every file is read
  when it is asked for, and the catalog search reloads when the build
  changes the catalog.

  Built on the JDK's `com.sun.net.httpserver.HttpServer` with a small
  pool of threads (`start!`); no web framework.

  ## Whose requests

  Before a request reaches a file or the API, its `Host` must name this
  server (`refusal`, TASK-292): `localhost`, a loopback address, the
  address it is bound to (or, bound to `0.0.0.0` or `::`, the address
  the connection arrived on -- never any host), or a name given with
  `--host` (`tailscale serve` passes on its own `*.ts.net` name).
  The port is ignored, case too, and one trailing dot. Otherwise 421 --
  a page that points a DNS name of its own at this machine (DNS
  rebinding) would else read and write every reader's positions -- or
  400 for a missing, repeated or malformed `Host`. A write whose
  `Origin` is not the request's own host and port answers 403. Names
  are only ever compared, never looked up.

  ## Static files

  `GET` (and `HEAD`) of any file under `DIR`, with its `Content-Type`
  (`content-types`), a strong `ETag` (size, modification time and file
  key, so a file replaced by a build's rename gets a new one) answered
  with 304 when `If-None-Match` matches, and `Cache-Control: no-cache`
  (the service worker does the caching). A folder answers its
  `index.html`, after a redirect that adds the trailing `/`.

  A path is taken apart before anything touches the disk
  (`static-path`): split at `/`, each segment percent-decoded exactly
  once as strict UTF-8, and refused -- 404, the same answer as a missing
  file, so nothing tells a probe what exists -- when a segment is empty,
  starts with `.` (`.`, `..`, `.haselnuss`, a build's temporary files),
  holds `/`, `\\`, NUL or another control character, or when the first
  one is `readers`. A double-encoded `%252e%252e` decodes to the literal
  name `%2e%2e`, which is no folder. The file found is then resolved to
  its real path (symbolic links followed) and refused unless it is a
  regular file inside `DIR`'s real path whose path from there passes the
  same segment rules again, so a link inside the site cannot lead out of
  it, nor into `.haselnuss/` or `readers/`.

  Every response carries `X-Content-Type-Options: nosniff`. Every file
  under `books/` that is not an HTML page also carries
  `Content-Security-Policy: sandbox` (no `allow-scripts`): an image or
  other file a book brought along, opened directly, runs no script on the
  site's origin, which also serves the positions API and the service
  worker. A book's HTML page does not get it: the page runs the reader
  app (`app.js`, same origin, which calls the API and registers the
  service worker) and MathJax, and a sandbox without `allow-scripts`
  stops both, while one with `allow-scripts` but no `allow-same-origin`
  gives the page an opaque origin that can neither call the API nor use
  the service worker or storage -- and one with both is no sandbox.

  ## The API

  - `GET /api/readers`: `[{\"id\" \"name\"}]`, every reader, sorted by id.
  - `POST /api/readers` `{\"name\": \"Ana\"}`: makes the reader `ana`
    (`slug`: the name decomposed, its marks dropped, lower-cased, every
    run of other characters a `-`), its name kept as written for display;
    201 with `{\"id\" \"name\"}`, 409 when that id exists, 400 when the
    name has no letter a-z (accents dropped) or digit to make one from --
    so a name in Cyrillic or Chinese alone makes none.
  - `GET /api/readers/<id>/positions`: `{book-id position}`.
  - `PUT /api/readers/<id>/positions/<book-id>`: saves one position,
    keeping whichever of the stored and the sent one has the later
    `savedAt` (compared as instants; a tie takes the sent one). Answers
    200 with `{\"book\" \"position\" \"saved\"}`: the position now
    stored, and whether it is the one sent. The book must be in the
    catalog (404 otherwise), so a reader file holds at most one position
    per book the site has had; one for a book since removed is kept.
  - `GET /api/readers/<id>/positions/<book-id>`: `{\"book\" \"position\"}`,
    the one position (null when there is none), so opening a book need not
    fetch every position the reader has (TASK-276).
  - `GET /api/books?id=<book-id>&id=...`: `{\"books\" {id entry}}`, the
    catalog entry of each id asked for that the catalog holds -- at most
    `books-limit` ids, else 400 -- so the app can name a book it knows
    only by id without reading catalog pages (TASK-276).
  - `GET /api/search?q=...`: `{\"query\" \"books\" \"more\"}`, at most
    `search-limit` catalog entries, in catalog order, whose title,
    authors or library hold every whitespace-separated word of `q`,
    compared without case or accents.

  A reader file is `readers/<id>.json`, `{\"name\" \"positions\"}`, and a
  position is doc-12's shape: `{\"id\" \"heading\" {\"index\" \"text\"}
  \"offset\" \"percent\" \"savedAt\" \"device\"}`, only those keys kept.
  A reader's id in a URL is slugged before it names a file, and a book id
  is only ever a key in the file. Writes to one reader file are
  serialised in the server (`reader-lock`) and each is a temporary file
  renamed over it, so two devices saving at once cannot corrupt it.

  A position's strings (`id`, `heading.text`, `device`) hold at most
  `max-position-text` characters, its numbers fit a long or a double,
  and its `savedAt` is at most `max-future-saved-at` past the server's
  clock; anything else answers 400.

  A POST or PUT must say `Content-Type: application/json`, else 415
  (`json-content-type?`: that keeps another site's page from sending one
  without a CORS preflight). Its body is read through a `max-body` cap:
  a `Content-Length` over it, or a body that turns out longer while being
  read, answers 413 before the rest is read. Malformed JSON or a value of
  the wrong shape answers 400, an unknown reader 404, a method a path
  does not take 405.

  A client has `max-request-seconds` to send a request and
  `max-response-seconds` to take the answer; then its connection is
  closed (`server-properties`).

  ## The catalog in memory

  The search reads `catalog/index.json` and every page it lists into
  memory (`load-catalog`) when the server starts, and again whenever the
  stamps (size, modification time, file key) of those files change,
  checked at most every `:reload-interval-ms` (`current-catalog`). A
  build writes each by rename, so a reload sees whole files; one that
  fails (a page swept between reading the index and the page) keeps the
  catalog it had and tries again on the next request. One that succeeds
  is followed by a `System/gc`, so the old catalog's memory goes back at
  once: with that, the shared keys and values (`sharing-reader`) and the
  young generation `bin/native` caps, the native binary serves the
  50,000-book catalog in about 300 MB with no heap flag (TASK-278).

  No records and no reflection: the native image needs only this
  namespace's `__init` class and the classes it imports."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [haselnuss.book-ref :as book-ref]
            [haselnuss.library :as library]
            [haselnuss.site :as site])
  (:import (com.sun.net.httpserver Headers HttpExchange HttpHandler HttpServer)
           (java.io ByteArrayOutputStream InputStream IOException OutputStream)
           (java.net InetAddress InetSocketAddress URLDecoder)
           (java.nio ByteBuffer)
           (java.nio.charset CharacterCodingException CodingErrorAction StandardCharsets)
           (java.nio.file AtomicMoveNotSupportedException CopyOption Files LinkOption
                          OpenOption Path StandardCopyOption)
           (java.nio.file.attribute BasicFileAttributes FileAttribute)
           (java.util.concurrent ExecutorService Executors ThreadFactory TimeUnit)))

;; --- the site ----------------------------------------------------------------------

(def ^:private no-follow (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
(def ^:private follow (make-array LinkOption 0))

(defn site-root
  "The real path of the site folder `dir`, when it is a built site -- a
  folder holding `.haselnuss/state/` (`haselnuss.site/site?`), which
  every build makes first, and the regular file `catalog/index.json`,
  which every finished build writes -- else throws `ex-info`
  `::not-a-site`."
  ^Path [dir]
  (let [shown (str dir)
        f (io/file shown)
        refuse #(throw (ex-info (str "--serve " shown ": " %) {:type ::not-a-site}))]
    (when (str/blank? shown) (refuse "needs a folder"))
    (when-not (.isDirectory f) (refuse "not a folder"))
    (let [root (.toRealPath (.toPath f) follow)]
      (when-not (site/site? (.toFile root))
        (refuse "not a Haselnuss site (a site holds .haselnuss/state/; build one with --build-site)"))
      (when-not (Files/isRegularFile (.resolve root "catalog/index.json") no-follow)
        (refuse "the site has no catalog/index.json (has --build-site finished a build into it?)"))
      root)))

;; --- paths -------------------------------------------------------------------------

(defn- hex-digit [c]
  (Character/digit (char c) 16))

(defn percent-decode
  "The text of the URL path segment `s` with every `%XX` decoded, read as
  strict UTF-8; nil when an escape is malformed or the bytes are not
  UTF-8. `+` is a plus: this is a path, not a query."
  [^String s]
  (let [out (ByteArrayOutputStream.)
        n (count s)]
    (loop [i 0]
      (if (< i n)
        (let [c (.charAt s i)]
          (if (= \% c)
            (let [hi (when (< (+ i 2) n) (hex-digit (.charAt s (inc i))))
                  lo (when (< (+ i 2) n) (hex-digit (.charAt s (+ i 2))))]
              (when (and hi lo (not (neg? (long hi))) (not (neg? (long lo))))
                (.write out (int (+ (* 16 (long hi)) (long lo))))
                (recur (+ i 3))))
            (let [bs (.getBytes (str c) StandardCharsets/UTF_8)]
              ;; a surrogate pair arrives as two chars; the raw path of a
              ;; URI is ASCII, so this only matters for a direct caller
              (.write out bs 0 (alength bs))
              (recur (inc i)))))
        (try
          (str (.decode (-> StandardCharsets/UTF_8
                            .newDecoder
                            (.onMalformedInput CodingErrorAction/REPORT)
                            (.onUnmappableCharacter CodingErrorAction/REPORT))
                        (ByteBuffer/wrap (.toByteArray out))))
          (catch CharacterCodingException _ nil))))))

(defn- control? [^long cp]
  (or (< cp 0x20) (<= 0x7f cp 0x9f)))

(defn safe-segment?
  "True when the decoded path segment `s` may name a file or folder of the
  site: not empty, not starting with `.` (so neither `.`, `..`, nor
  `.haselnuss` or a build's temporary files), and with no `/`, `\\` or
  control character."
  [s]
  (and (string? s)
       (not= "" s)
       (not (str/starts-with? s "."))
       (not (str/includes? s "/"))
       (not (str/includes? s "\\"))
       (not (some control? (.toArray (.codePoints ^String s))))))

(def ^:private hidden-tops
  "First segments never served as files: the readers' files. (`.haselnuss`
  is refused by `safe-segment?` as any dot-name is.)"
  #{"readers"})

(defn- allowed-segments?
  "True when every one of `segments` is `safe-segment?` and the first is
  not a `hidden-tops` folder."
  [segments]
  (and (seq segments)
       (every? safe-segment? segments)
       (not (hidden-tops (first segments)))))

(defn static-path
  "The decoded segments the raw request path `raw` names, `index.html`
  appended for a path ending in `/`; nil when `raw` is not one the site
  serves (see the namespace doc). Touches no file."
  [^String raw]
  (when (and (string? raw) (str/starts-with? raw "/"))
    (let [parts (str/split (subs raw 1) #"/" -1)
          parts (if (= "" (peek parts)) (conj (pop parts) "index.html") parts)
          segments (mapv percent-decode parts)]
      (when (and (every? some? segments) (allowed-segments? segments))
        segments))))

(defn resolve-static
  "`{:path :segments :dir?}` for the file the request path `raw` names
  under the site's real path `root`: the real path of a regular file
  inside `root` whose path from `root` passes `static-path`'s rules
  again, or `:dir? true` when `raw` names a folder (to be redirected to
  its `/` form); nil for anything else."
  [^Path root raw]
  (when-let [segments (static-path raw)]
    (let [^Path p (reduce (fn [^Path p ^String s] (.resolve p s)) root segments)]
      (try
        (let [real (.toRealPath p follow)]
          (when (.startsWith real root)
            (let [rel (.relativize root real)
                  rel-segments (mapv str (iterator-seq (.iterator rel)))]
              (when (allowed-segments? rel-segments)
                (cond
                  (Files/isRegularFile real follow) {:path real :segments rel-segments}
                  (Files/isDirectory real follow) {:dir? true}
                  :else nil)))))
        (catch IOException _ nil)))))

;; --- responses ---------------------------------------------------------------------

(def content-types
  "The `Content-Type` of a served file by its extension (lower case);
  anything else is `application/octet-stream`."
  {"html" "text/html; charset=utf-8"
   "htm" "text/html; charset=utf-8"
   "css" "text/css; charset=utf-8"
   "js" "text/javascript; charset=utf-8"
   "mjs" "text/javascript; charset=utf-8"
   "json" "application/json; charset=utf-8"
   "webmanifest" "application/manifest+json; charset=utf-8"
   "txt" "text/plain; charset=utf-8"
   "png" "image/png"
   "apng" "image/apng"
   "avif" "image/avif"
   "bmp" "image/bmp"
   "gif" "image/gif"
   "ico" "image/x-icon"
   "jpeg" "image/jpeg"
   "jpg" "image/jpeg"
   "webp" "image/webp"
   "svg" "image/svg+xml"
   "woff" "font/woff"
   "woff2" "font/woff2"
   "pdf" "application/pdf"})

(defn- extension [^String file-name]
  (let [i (str/last-index-of file-name ".")]
    (if (and i (pos? (long i))) (str/lower-case (subs file-name (inc (long i)))) "")))

(defn content-type
  "The `Content-Type` of the file named `file-name`."
  [file-name]
  (get content-types (extension file-name) "application/octet-stream"))

(defn- html-name? [file-name]
  (contains? #{"html" "htm"} (extension file-name)))

(defn- set-header! [^HttpExchange ex ^String k ^String v]
  (.set ^Headers (.getResponseHeaders ex) k v))

(defn- send-bytes!
  "Answer `status` with `body` (bytes; nil or empty for none) of
  `content-type`, and close the exchange."
  [^HttpExchange ex status ^String ctype ^bytes body]
  (when ctype (set-header! ex "Content-Type" ctype))
  (set-header! ex "Cache-Control" "no-cache")
  (let [n (if body (alength body) 0)
        head? (= "HEAD" (.getRequestMethod ex))]
    (.sendResponseHeaders ex (int status) (if (or head? (zero? n)) -1 n))
    (when (and (pos? n) (not head?))
      (with-open [out (.getResponseBody ex)]
        (.write out body)))
    (.close ex)))

(defn- json-body ^bytes [data]
  (.getBytes ^String (json/write-str data) StandardCharsets/UTF_8))

(defn- send-json! [ex status data]
  (send-bytes! ex status "application/json; charset=utf-8" (json-body data)))

(defn- send-error! [ex status message]
  (send-json! ex status {"error" message}))

(defn- etag
  "A strong entity tag for the file with attributes `attrs`: its size,
  modification time and file key (the inode on Linux), so a file a build
  replaced by rename never keeps the old tag."
  [^BasicFileAttributes attrs]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")
        bs (.digest md (.getBytes (str (.size attrs) "|" (.to (.lastModifiedTime attrs) TimeUnit/NANOSECONDS) "|"
                                       (.fileKey attrs))
                                  StandardCharsets/UTF_8))]
    (str "\"" (apply str (map #(format "%02x" (bit-and (long %) 0xff)) (take 12 bs))) "\"")))

(defn- etag-matches?
  "True when the `If-None-Match` header value `header` names `tag` (or is
  `*`), weak tags compared weakly as RFC 9110 says for this header."
  [header tag]
  (when header
    (let [tags (map str/trim (str/split header #","))]
      (boolean (some (fn [t] (or (= "*" t) (= tag (str/replace t #"^W/" "")))) tags)))))

(defn- serve-file*
  [^HttpExchange ex ^Path path segments]
  (let [file-name (peek segments)
        attrs (Files/readAttributes path BasicFileAttributes ^"[Ljava.nio.file.LinkOption;" follow)
        tag (etag attrs)
        size (.size attrs)
        head? (= "HEAD" (.getRequestMethod ex))]
    (set-header! ex "Content-Type" (content-type file-name))
    (set-header! ex "Cache-Control" "no-cache")
    (set-header! ex "ETag" tag)
    (when (and (= "books" (first segments)) (not (html-name? file-name)))
      (set-header! ex "Content-Security-Policy" "sandbox"))
    (cond
      (etag-matches? (.getFirst ^Headers (.getRequestHeaders ex) "If-None-Match") tag)
      (do (.sendResponseHeaders ex 304 -1) (.close ex))

      (or head? (zero? size))
      (do (when head? (set-header! ex "Content-Length" (str size)))
          (.sendResponseHeaders ex 200 -1)
          (.close ex))

      :else
      (with-open [^InputStream in (Files/newInputStream path (make-array OpenOption 0))]
        (.sendResponseHeaders ex 200 size)
        (with-open [^OutputStream out (.getResponseBody ex)]
          (.transferTo in out))
        (.close ex)))))

(defn- serve-file!
  "Answer the regular file `path` (`segments`: its path from the root);
  404 when a build removed it after `resolve-static` found it."
  [^HttpExchange ex ^Path path segments]
  (try
    (serve-file* ex path segments)
    (catch java.nio.file.NoSuchFileException _
      ;; only ever thrown before the response headers went out: the file
      ;; is opened before `sendResponseHeaders`
      (.remove ^Headers (.getResponseHeaders ex) "ETag")
      (.remove ^Headers (.getResponseHeaders ex) "Content-Security-Policy")
      (send-error! ex 404 "not found"))))

(defn- handle-static! [^HttpExchange ex ^Path root]
  (let [method (.getRequestMethod ex)
        raw (.getRawPath (.getRequestURI ex))]
    (if-not (contains? #{"GET" "HEAD"} method)
      (do (set-header! ex "Allow" "GET, HEAD")
          (send-error! ex 405 "method not allowed"))
      (let [found (resolve-static root raw)]
        (cond
          (nil? found) (send-error! ex 404 "not found")
          ;; the raw path passed `static-path`, so it has no empty segment
          ;; and cannot start with `//`: the redirect stays on this site
          (:dir? found) (do (set-header! ex "Location" (str raw "/"))
                            (send-bytes! ex 301 nil nil))
          :else (serve-file! ex (:path found) (:segments found)))))))

;; --- request bodies ----------------------------------------------------------------

(def max-body
  "The largest request body read, in bytes (doc-12: 64 KB)."
  (* 64 1024))

(defn- read-body
  "The request body as bytes, read through the `max-body` cap: nil once
  it is longer (a `Content-Length` over the cap is refused before
  reading anything). Throws `ex-info` `::client-gone` when the client's
  connection fails while the body is read (it hung up, or the server
  closed it after `max-request-seconds`)."
  [^HttpExchange ex]
  (let [declared (some-> (.getFirst ^Headers (.getRequestHeaders ex) "Content-Length") str/trim)
        declared (when declared (try (Long/parseLong declared) (catch NumberFormatException _ -1)))]
    (when-not (and declared (or (neg? (long declared)) (> (long declared) (long max-body))))
      (let [^InputStream in (.getRequestBody ex)
            buf (byte-array 8192)
            out (ByteArrayOutputStream.)]
        (loop []
          (let [n (try (.read in buf 0 (alength buf))
                       (catch IOException e
                         (throw (ex-info "the client's connection failed while sending the body"
                                         {:type ::client-gone} e))))]
            (cond
              (neg? n) (.toByteArray out)
              (> (+ (.size out) n) (long max-body)) nil
              :else (do (.write out buf 0 n) (recur)))))))))

(defn json-content-type?
  "True when the `Content-Type` header value `header` is
  `application/json` (any parameters, such as a charset, ignored). The
  API's POST and PUT require it: a page on another site can send a
  cross-site \"simple\" request -- a form's `text/plain` or
  `application/x-www-form-urlencoded` -- without asking first, but a
  JSON one only after a CORS preflight, which this server never grants."
  [header]
  (and (string? header)
       (= "application/json"
          (.toLowerCase (str/trim (first (str/split header #";" 2))) java.util.Locale/ROOT))))

(defn parse-json
  "The one JSON value in the text `s`, or `::malformed` when `s` is not
  exactly one (trailing text included), or nests too deeply to read.
  `key-fn` and `value-fn` are data.json's options of those names (nil:
  none)."
  ([s] (parse-json s nil nil))
  ([^String s key-fn value-fn]
   (try
     (let [;; data.json pushes back up to 64 characters, as its read-str does
           r (java.io.PushbackReader. (java.io.StringReader. s) 64)
           v (json/read r :eof-error? true :key-fn key-fn :value-fn value-fn)]
       (if (loop []
             (let [c (.read r)]
               (cond (neg? c) true
                     (Character/isWhitespace c) (recur)
                     :else false)))
         v
         ::malformed))
     (catch StackOverflowError _ ::malformed)
     (catch Exception _ ::malformed))))

(defn- parse-json-body
  "`[:ok value]`, or `[:error status message]` (413, 400)."
  [ex]
  (if-let [bs (read-body ex)]
    (let [text (try (str (.decode (-> StandardCharsets/UTF_8
                                      .newDecoder
                                      (.onMalformedInput CodingErrorAction/REPORT)
                                      (.onUnmappableCharacter CodingErrorAction/REPORT))
                                  (ByteBuffer/wrap bs)))
                    (catch CharacterCodingException _ nil))
          v (if text (parse-json text) ::malformed)]
      (if (= ::malformed v)
        [:error 400 "the body is not one JSON value"]
        [:ok v]))
    [:error 413 (str "the body is over " max-body " bytes")]))

(defn- read-json-body
  "`[:ok value]`, or `[:error status message]`: 415 when the request is
  not `json-content-type?` (its body left unread), 413 over `max-body`,
  400 when the body is not one JSON value."
  [^HttpExchange ex]
  (if-not (json-content-type? (.getFirst ^Headers (.getRequestHeaders ex) "Content-Type"))
    [:error 415 "the body must be sent as Content-Type: application/json"]
    (parse-json-body ex)))

;; --- readers -----------------------------------------------------------------------

(defn slug
  "The reader id of the name `s`: decomposed (NFD) with its combining
  marks dropped, lower-cased, every run of characters outside `[a-z0-9]`
  one `-`, none at either end, at most 64 characters; \"\" when nothing
  is left."
  [s]
  (let [s (-> (java.text.Normalizer/normalize (str s) java.text.Normalizer$Form/NFD)
              (str/replace #"\p{M}+" "")
              (.toLowerCase java.util.Locale/ROOT)
              (str/replace #"[^a-z0-9]+" "-")
              (str/replace #"^-+|-+$" ""))
        s (if (> (count s) 64) (subs s 0 64) s)]
    (str/replace s #"-+$" "")))

(def ^:private max-name 200)

(defn- readers-dir ^Path [^Path root] (.resolve root "readers"))

(defn- reader-file
  "The reader file of the id `id` -- already `slug`ged, and so only
  `[a-z0-9-]`."
  ^Path [^Path root ^String id]
  (.resolve (readers-dir root) (str id ".json")))

(defn- read-reader
  "The reader file `p` parsed, `{\"name\" \"positions\"}`, or nil when
  there is none. Throws when it cannot be read or is not that shape."
  [^Path p]
  (when (Files/isRegularFile p no-follow)
    (let [v (parse-json (String. (Files/readAllBytes p) StandardCharsets/UTF_8))]
      (when-not (and (map? v) (map? (get v "positions" {})))
        (throw (ex-info (str "the reader file " p " is not a reader") {:type ::bad-reader-file})))
      (update v "positions" #(or % {})))))

(defn- write-atomically!
  "Write `bytes` to `target` through a temporary file beside it, flushed
  to the disk (`force`) and then renamed over it (atomically where the
  file system allows), so a crash leaves the old file or the new one,
  never an empty one."
  [^Path target ^bytes bs]
  (let [dir (.getParent target)
        tmp (Files/createTempFile dir (str "." (.getFileName target) ".") ".tmp" (make-array FileAttribute 0))]
    (try
      (with-open [ch (java.nio.channels.FileChannel/open
                      tmp ^"[Ljava.nio.file.OpenOption;"
                      (into-array OpenOption [java.nio.file.StandardOpenOption/WRITE
                                              java.nio.file.StandardOpenOption/TRUNCATE_EXISTING]))]
        (let [buf (ByteBuffer/wrap bs)]
          (while (.hasRemaining buf) (.write ch buf)))
        (.force ch true))
      (try
        (Files/move tmp target ^"[Ljava.nio.file.CopyOption;"
                    (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE StandardCopyOption/REPLACE_EXISTING]))
        (catch AtomicMoveNotSupportedException _
          (Files/move tmp target ^"[Ljava.nio.file.CopyOption;"
                      (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING]))))
      (finally (Files/deleteIfExists tmp)))))

(defn- ensure-readers-dir!
  "`readers/` under `root`, made when absent; throws when it is something
  other than a real folder (a symbolic link, a file)."
  ^Path [^Path root]
  (let [d (readers-dir root)]
    (when-not (Files/exists d no-follow)
      (try (Files/createDirectory d (make-array FileAttribute 0))
           (catch java.nio.file.FileAlreadyExistsException _ nil)))
    (when-not (Files/isDirectory d no-follow)
      (throw (ex-info (str d " is not a folder") {:type ::bad-readers-dir})))
    d))

(defn- reader-lock
  "The one lock object of the reader `id` in the server `state`."
  [state id]
  (get (swap! (:locks state) (fn [m] (if (contains? m id) m (assoc m id (Object.))))) id))

(defn list-readers
  "`[{\"id\" \"name\"}]` of every reader file under `root`, sorted by id."
  [^Path root]
  (let [d (readers-dir root)]
    (if-not (Files/isDirectory d no-follow)
      []
      (->> (with-open [s (Files/list d)] (vec (iterator-seq (.iterator s))))
           (keep (fn [^Path p]
                   (let [n (str (.getFileName p))]
                     (when (str/ends-with? n ".json")
                       (let [id (subs n 0 (- (count n) 5))]
                         (when (and (= id (slug id)) (not= "" id))
                           (let [display (try (get (read-reader p) "name") (catch Exception _ nil))]
                             (when (Files/isRegularFile p no-follow)
                               {"id" id "name" (if (string? display) display id)}))))))))
           (sort-by #(get % "id"))
           vec))))

(defn- create-reader! [ex state]
  (let [[outcome v message] (read-json-body ex)]
    (cond
      (not= :ok outcome) (send-error! ex v message)
      (not (and (map? v) (string? (get v "name"))))
      (send-error! ex 400 "expected {\"name\": \"...\"}")
      :else
      (let [display (str/trim (get v "name"))
            id (slug display)]
        (cond
          (> (count display) max-name) (send-error! ex 400 (str "the name is over " max-name " characters"))
          (= "" id) (send-error! ex 400 (str "a reader id keeps only the letters a-z (accents dropped) and the digits 0-9 "
                                             "of a name, and this name has none; add some, e.g. \"Ana\" or \"reader 2\""))
          :else
          (let [root (:root state)
                lock (reader-lock state id)
                created? (locking lock
                           (ensure-readers-dir! root)
                           (let [f (reader-file root id)]
                             (if (Files/exists f no-follow)
                               false
                               (do (write-atomically! f (json-body {"name" display "positions" {}}))
                                   true))))]
            (if created?
              (send-json! ex 201 {"id" id "name" display})
              (send-error! ex 409 (str "the reader " id " exists")))))))))

;; --- positions ---------------------------------------------------------------------

(defn parse-instant
  "The instant of the ISO-8601 date-time `s` with an offset
  (`2026-11-13T21:04:11Z`), or nil."
  [s]
  (when (string? s)
    (try (java.time.Instant/from (.parse java.time.format.DateTimeFormatter/ISO_OFFSET_DATE_TIME ^String s))
         (catch Exception _ nil))))

(defn- bounded-number?
  "True for a JSON number that stays small when stored: an integer that
  fits a long, or a finite double -- not a `BigInteger` a body of
  thousands of digits reads as."
  [x]
  (or (int? x)
      (and (double? x) (not (Double/isNaN x)) (not (Double/isInfinite x)))))

(def max-position-text
  "The most characters a string in a stored position may have (`id`,
  `heading.text`, `device`): every save rewrites the reader's whole file,
  so each position has to stay small."
  1024)

(def max-future-saved-at
  "How far past the server's clock a position's `savedAt` may be. One a
  device with a wrong clock dates years ahead would otherwise win
  against every later save, for good."
  (java.time.Duration/ofDays 1))

(defn- short-string-or-nil? [x]
  (or (nil? x) (and (string? x) (<= (count x) (long max-position-text)))))

(defn position
  "The position `v` (a parsed JSON body) as stored -- only doc-12's keys
  -- or a message saying what is wrong with it. `now` (default: the
  clock) bounds `savedAt` (`max-future-saved-at`)."
  ([v] (position v (java.time.Instant/now)))
  ([v ^java.time.Instant now]
   (let [position-keys ["id" "heading" "offset" "percent" "savedAt" "device"]
         heading (get v "heading")
         saved-at (when (map? v) (parse-instant (get v "savedAt")))
         too-long (str " must be a string of at most " max-position-text " characters, or null")]
     (cond
       (not (map? v)) "expected a JSON object"
       (nil? saved-at) "savedAt must be a date-time with an offset, e.g. 2026-11-13T21:04:11Z"
       (.isAfter ^java.time.Instant saved-at (.plus now ^java.time.Duration max-future-saved-at))
       (str "savedAt " (get v "savedAt") " is more than a day ahead of the server's clock ("
            now "); is the device's clock wrong?")
       (not (short-string-or-nil? (get v "id"))) (str "id" too-long)
       (not (or (nil? heading)
                (and (map? heading)
                     (or (nil? (get heading "index")) (int? (get heading "index")))
                     (short-string-or-nil? (get heading "text")))))
       (str "heading must be {\"index\": integer, \"text\": string of at most "
            max-position-text " characters} or null")
       (not (or (nil? (get v "offset")) (bounded-number? (get v "offset")))) "offset must be a number"
       (not (or (nil? (get v "percent")) (bounded-number? (get v "percent")))) "percent must be a number"
       (not (short-string-or-nil? (get v "device"))) (str "device" too-long)
       :else (cond-> (select-keys v position-keys)
               (map? heading) (assoc "heading" (select-keys heading ["index" "text"])))))))

(defn newer
  "Whichever of the stored position `stored` (nil for none) and the sent
  one `sent` has the later `savedAt` as an instant; `sent` on a tie, or
  when the stored one has none that reads."
  [stored sent]
  (let [a (parse-instant (get stored "savedAt"))
        b (parse-instant (get sent "savedAt"))]
    (if (and a b (.isAfter ^java.time.Instant a ^java.time.Instant b)) stored sent)))

(defn- book-id?
  "True when `s` is a canonical book id (`lib:[shelf/...]slug`), written
  as `format-book-id` writes it."
  [s]
  (and (string? s) (<= (count s) 1024)
       (let [r (book-ref/parse-book-ref s)]
         (and (not (book-ref/malformed? r))
              (nil? (:passage r))
              (= s (book-ref/format-book-id (:id r)))))))

;; --- the catalog -------------------------------------------------------------------

(def search-limit
  "The most books one search answers (doc-12)."
  50)

(defn- fold
  "`s` as search compares it: decomposed, marks dropped, lower-cased --
  `haselnuss.site/collation-key`'s first part."
  [s]
  (first (site/collation-key s)))

(defn- stamp-of [^Path p]
  (try
    (let [a (Files/readAttributes p BasicFileAttributes ^"[Ljava.nio.file.LinkOption;" no-follow)]
      [(.size a) (.to (.lastModifiedTime a) TimeUnit/NANOSECONDS) (str (.fileKey a))])
    (catch IOException _ nil)))

(defn- catalog-paths
  "The catalog files `index` (`catalog/index.json`, parsed) lists, as
  relative paths, index first."
  [index]
  (into ["catalog/index.json"]
        (for [lib (get index "libraries")
              :let [lib-name (get lib "name")
                    pages (get lib "pages")]
              :when (and (library/valid-name? lib-name) (integer? pages))
              n (range 1 (inc (long (min pages 1000000))))]
          (str "catalog/" lib-name "/" n ".json"))))

(def ^:private shared-values
  "The catalog entry keys whose values repeat from book to book (one
  library, shelf or colour for many books; the same authors on many),
  and so are shared by `sharing-reader` rather than held once per book."
  #{"library" "shelf" "colour" "authors"})

(defn- sharing-reader
  "`[key-fn value-fn]` for one `load-catalog`: every object key, and the
  value of every `shared-values` key, read once and then shared -- equal
  strings (and author vectors) become the same object. Without it the
  50,000-book catalog holds a fresh copy of every key name and library
  name per book, which with the reload's second copy was what pushed the
  native server past 512 MB (TASK-278). The table lives only for the one
  load, so nothing a site once held stays in it."
  []
  (let [seen (java.util.HashMap.)
        share (fn [x] (or (.putIfAbsent seen x x) x))]
    [share
     (fn [k v]
       (if (and (contains? shared-values k) (or (string? v) (vector? v)))
         (share (if (vector? v) (mapv #(if (string? %) (share %) %) v) v))
         v))]))

(defn- read-json-file
  ([p] (read-json-file p nil))
  ([^Path p [key-fn value-fn]]
   (let [v (parse-json (String. (Files/readAllBytes p) StandardCharsets/UTF_8) key-fn value-fn)]
     (when (= ::malformed v)
       (throw (ex-info (str p ": not JSON") {:type ::bad-catalog})))
     v)))

(defn- stamps
  "`{relative-path stamp}` of the catalog files `paths` under `root`."
  [^Path root paths]
  (into {} (map (fn [rel] [rel (stamp-of (.resolve root ^String rel))])) paths))

(defn load-catalog
  "The catalog of the site at `root` in memory: `{:stamps :books :by-id}`,
  `:books` every entry of every page -- the page's own map plus
  `::haystack`, its title, authors and library `fold`ed -- in catalog
  order, `:by-id` the same maps by id, the first of an id winning. Keys
  and repeated values are shared (`sharing-reader`). Throws when a file
  is missing or is not the catalog's shape."
  [^Path root]
  (let [reader (sharing-reader)
        index (read-json-file (.resolve root "catalog/index.json"))
        _ (when-not (and (map? index) (sequential? (get index "libraries")))
            (throw (ex-info "catalog/index.json is not a catalog index" {:type ::bad-catalog})))
        paths (catalog-paths index)
        ;; taken before the pages are read: a page a build replaces while
        ;; they are read changes its stamp, and the next request reloads
        before (stamps root paths)
        books (into []
                    (comp (mapcat (fn [rel]
                                    (let [page (read-json-file (.resolve root ^String rel) reader)]
                                      (when-not (and (map? page) (sequential? (get page "books")))
                                        (throw (ex-info (str rel ": not a catalog page") {:type ::bad-catalog})))
                                      (get page "books"))))
                          (filter map?)
                          (map (fn [b]
                                 (assoc b ::haystack
                                        (fold (str/join "\n" (concat [(str (get b "title"))]
                                                                     (map str (let [a (get b "authors")]
                                                                                (if (sequential? a) a [])))
                                                                     [(str (get b "library"))])))))))
                    (rest paths))]
    {:stamps before
     :books books
     ;; the first entry of an id wins, as search's catalog order would show
     ;; it; the very map `:books` holds, not a copy (`books-by-id` drops the
     ;; haystack when it answers)
     :by-id (persistent!
             (reduce (fn [m b]
                       (let [id (get b "id")]
                         (if (or (not (string? id)) (contains? m id)) m (assoc! m id b))))
                     (transient {}) books))}))

(defn- catalog-changed? [^Path root catalog]
  (let [seen (:stamps catalog)]
    (not= seen (stamps root (keys seen)))))

(defn current-catalog
  "The server `state`'s catalog, reloaded first when the catalog files'
  stamps changed -- checked at most every `:reload-interval-ms`. A reload
  that fails keeps the catalog there was."
  [{:keys [root catalog reload-interval-ms]}]
  (let [now (System/nanoTime)
        {:keys [checked-at] :as c} @catalog]
    (if (and checked-at (< (- now (long checked-at)) (* 1000000 (long reload-interval-ms))))
      c
      (locking catalog
        (let [c @catalog]
          (if (and (:checked-at c) (< (- (System/nanoTime) (long (:checked-at c))) (* 1000000 (long reload-interval-ms))))
            c
            (let [fresh (if (catalog-changed? root c)
                          (try (load-catalog root) (catch Exception _ c))
                          c)
                  now-held (reset! catalog (assoc fresh :checked-at (System/nanoTime)))]
              ;; the old catalog is garbage now, and so is everything the
              ;; load read: collect it at once, so the native binary hands
              ;; that memory back instead of growing toward its next
              ;; collection (TASK-278; README, "Serving a site")
              (when-not (identical? fresh c)
                (System/gc))
              now-held)))))))

(defn search
  "`{\"query\" \"books\" \"more\"}`: the first `search-limit` books of the
  `catalog` (`load-catalog`'s) whose title, authors or library hold every
  word of `q`, compared `fold`ed; none for a blank `q`."
  [catalog q]
  (let [words (->> (str/split (fold (str q)) #"\s+") (remove str/blank?) distinct (take 16) vec)
        hits (if (empty? words)
               []
               (into [] (comp (filter (fn [b] (let [^String h (::haystack b)]
                                                (every? #(str/includes? h %) words))))
                              (take (inc (long search-limit))))
                     (:books catalog)))]
    {"query" (str q)
     "books" (mapv #(dissoc % ::haystack) (take search-limit hits))
     "more" (> (count hits) (long search-limit))}))

(def books-limit
  "The most ids one `/api/books` request may ask for."
  100)

(def max-query-length
  "The most characters of `q` `/api/search` accepts (and so echoes)."
  200)

(defn books-by-id
  "`{\"books\" {id entry}}`: the `catalog` (`load-catalog`'s) entry of each
  of `ids` it holds; an id it does not hold is left out."
  [catalog ids]
  {"books" (into {} (keep (fn [id] (when-let [b (get (:by-id catalog) id)] [id (dissoc b ::haystack)]))) ids)})

(defn- query-params
  "The values of the parameter `k` in the raw query string `raw`
  (form-decoded), in order, at most `limit` of them: the string is walked
  pair by pair and the walk stops at the `limit`th match, so a query of
  millions of pairs costs no more than `limit` of them past the point the
  caller refuses it. Throws `IllegalArgumentException` on a malformed
  escape in a pair it reaches."
  [^String raw k limit]
  (when raw
    (let [n (.length raw)]
      (loop [start 0 out (transient [])]
        (if (or (> start n) (>= (count out) (long limit)))
          (persistent! out)
          (let [amp (.indexOf raw "&" (int start))
                end (if (neg? amp) n amp)
                pair (subs raw start end)
                [pk pv] (str/split pair #"=" 2)
                out (if (= k (URLDecoder/decode (str pk) StandardCharsets/UTF_8))
                      (conj! out (URLDecoder/decode (str pv) StandardCharsets/UTF_8))
                      out)]
            (recur (inc end) out)))))))

(defn- query-param
  "The value of the parameter `k` in the raw query string `raw`
  (form-decoded), nil when absent; throws `IllegalArgumentException` on
  a malformed escape."
  [raw k]
  (when raw
    (some (fn [pair]
            (let [[pk pv] (str/split pair #"=" 2)]
              (when (= k (URLDecoder/decode (str pk) StandardCharsets/UTF_8))
                (URLDecoder/decode (str pv) StandardCharsets/UTF_8))))
          (str/split raw #"&"))))

;; --- the API -----------------------------------------------------------------------

(defn- method-not-allowed! [ex allow]
  (set-header! ex "Allow" allow)
  (send-error! ex 405 "method not allowed"))

(defn- with-reader
  "Calls `(f id file)` for the reader the URL segment `raw-id` names (its
  decoded text `slug`ged), or answers 404 when there is none."
  [ex state raw-id f]
  (let [decoded (percent-decode raw-id)
        id (when decoded (slug decoded))]
    (if (or (nil? id) (= "" id) (not (Files/isRegularFile (reader-file (:root state) id) no-follow)))
      (send-error! ex 404 "no such reader")
      (f id (reader-file (:root state) id)))))

(defn- put-position! [ex state id ^Path file raw-book]
  (let [book (percent-decode raw-book)
        body (read-json-body ex)]
    (cond
      (not (book-id? book)) (send-error! ex 404 "not a book id")
      (not= :ok (first body)) (send-error! ex (second body) (nth body 2))
      :else
      (let [sent (position (second body))]
        (cond
          (string? sent) (send-error! ex 400 sent)
          (not (contains? (:by-id (current-catalog state)) book)) (send-error! ex 404 "no such book in the catalog")
          :else
          (let [lock (reader-lock state id)
                [winner saved?]
                (locking lock
                  (let [r (read-reader file)]
                    (if (nil? r)
                      [nil false]
                      (let [stored (get-in r ["positions" book])
                            winner (newer stored sent)]
                        (when-not (identical? winner stored)
                          (write-atomically! file (json-body (assoc-in r ["positions" book] winner))))
                        [winner (identical? winner sent)]))))]
            (if (nil? winner)
              (send-error! ex 404 "no such reader")
              (send-json! ex 200 {"book" book "position" winner "saved" saved?}))))))))

(defn- get-position!
  "Answers `{\"book\" \"position\"}`: the reader file `file`'s position for
  the book `raw-book`, null when it has none. Any well-formed book id is
  asked about, in the catalog or not, so a book since removed still says
  where it was left."
  [ex ^Path file raw-book]
  (let [book (percent-decode raw-book)]
    (if (book-id? book)
      (send-json! ex 200 {"book" book "position" (get-in (read-reader file) ["positions" book])})
      (send-error! ex 404 "not a book id"))))

(defn- handle-api! [^HttpExchange ex state]
  (let [method (.getRequestMethod ex)
        uri (.getRequestURI ex)
        raw (.getRawPath uri)
        parts (str/split (subs raw (count "/api/")) #"/" -1)]
    (cond
      (= ["search"] parts)
      (if (contains? #{"GET" "HEAD"} method)
        (let [q (try (query-param (.getRawQuery uri) "q") (catch IllegalArgumentException _ ::bad))]
          (cond
            (= ::bad q) (send-error! ex 400 "malformed query")
            (> (count q) (long max-query-length))
            (send-error! ex 400 (str "q is at most " max-query-length " characters"))
            :else (send-json! ex 200 (search (current-catalog state) q))))
        (method-not-allowed! ex "GET, HEAD"))

      (= ["books"] parts)
      (if (contains? #{"GET" "HEAD"} method)
        (let [ids (try (query-params (.getRawQuery uri) "id" (inc (long books-limit))) (catch IllegalArgumentException _ ::bad))]
          (cond
            (= ::bad ids) (send-error! ex 400 "malformed query")
            (> (count ids) (long books-limit)) (send-error! ex 400 (str "at most " books-limit " ids at a time"))
            :else (send-json! ex 200 (books-by-id (current-catalog state) ids))))
        (method-not-allowed! ex "GET, HEAD"))

      (= ["readers"] parts)
      (case method
        ("GET" "HEAD") (send-json! ex 200 (list-readers (:root state)))
        "POST" (create-reader! ex state)
        (method-not-allowed! ex "GET, HEAD, POST"))

      (and (= 3 (count parts)) (= "readers" (first parts)) (= "positions" (nth parts 2)))
      (if (contains? #{"GET" "HEAD"} method)
        (with-reader ex state (second parts)
          (fn [_ file] (send-json! ex 200 (get (read-reader file) "positions" {}))))
        (method-not-allowed! ex "GET, HEAD"))

      ;; the book id may arrive with its `/`s as they are or as %2F
      (and (>= (count parts) 4) (= "readers" (first parts)) (= "positions" (nth parts 2)))
      (case method
        "PUT" (with-reader ex state (second parts)
                (fn [id file] (put-position! ex state id file (str/join "/" (drop 3 parts)))))
        ("GET" "HEAD") (with-reader ex state (second parts)
                         (fn [_ file] (get-position! ex file (str/join "/" (drop 3 parts)))))
        (method-not-allowed! ex "GET, HEAD, PUT"))

      :else (send-error! ex 404 "not found"))))

;; --- whose requests: the Host check (TASK-292) -------------------------------------

(def ^:private ipv4-literal
  #"(?:25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])(?:\.(?:25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])){3}")

(defn- ip-literal
  "The address the IP literal `s` writes -- a dotted quad, or the inside
  of `[...]` when `v6?` -- or nil. Never a DNS lookup: a quad is matched
  exactly before `InetAddress/getByName` parses it, and a bracketed
  string is only ever parsed as an IPv6 literal by it."
  ^InetAddress [^String s v6?]
  (if v6?
    (when (and (re-matches #"[0-9A-Fa-f:][0-9A-Fa-f:.]*" s) (str/includes? s ":"))
      ;; UnknownHostException, caught as its superclass: an import of it
      ;; is a Class/forName the native image has no entry for
      (try (InetAddress/getByName (str "[" s "]"))
           (catch IOException _ nil)))
    (when (re-matches ipv4-literal s)
      (InetAddress/getByName s))))

(defn host-authority
  "The host the `Host` value (or URI authority, or the part of an
  `Origin` after its scheme) `s` names, port set aside:
  `{:address InetAddress :port p}` for an IP literal (`127.0.0.1`,
  `[::1]`), `{:name n :port p}` for a name, ASCII lower-cased and one
  trailing dot dropped (`LocalHost.:8080` names `localhost`); `:port`
  is the text after `:`, or nil when there is none. Nil for anything
  else: empty, user info, a path, a zone id, a character outside
  `[A-Za-z0-9.-]` in a name (checked before lower-casing, so no
  non-ASCII letter lower-cases into an allowed name), an empty label,
  a port that is not 0-5 digits."
  [s]
  (when (string? s)
    (let [s (str/trim s)]
      (if (str/starts-with? s "[")
        (when-let [[_ inside port] (re-matches #"\[([^\]]*)\](?::([0-9]{0,5}))?" s)]
          (when-let [a (ip-literal inside true)]
            {:address a :port port}))
        (when-let [[_ host port] (re-matches #"([A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*)\.?(?::([0-9]{0,5}))?" s)]
          (when (<= (count host) 253)
            (if-let [a (ip-literal host false)]
              {:address a :port port}
              {:name (str/lower-case host) :port port})))))))

(defn allowed-hosts
  "What `start!` answers to (`host-allowed?`), from its `:bind` address
  (`bind`, its text; `bound`, what it resolved to) and the extra names
  `hosts` (`--host`): `{:names #{...} :addresses #{...}}`. The names are
  always `localhost`, plus `bind` when it is a name, plus each of
  `hosts`; the addresses are `bound` unless it is the wildcard
  (`0.0.0.0`, `::`), plus each of `hosts` that is an IP literal. Throws
  `IllegalArgumentException` for an entry of `hosts` that is not a bare
  name or IP literal (`host-authority`, no port) or is the wildcard."
  [bind ^InetAddress bound hosts]
  (let [entries (for [h hosts]
                  (let [{:keys [port] :as auth} (host-authority h)
                        ^InetAddress a (:address auth)]
                    (when (or (nil? auth) (some? port) (str/blank? h) (and a (.isAnyLocalAddress a)))
                      (throw (IllegalArgumentException.
                              (str "--host takes a host name or IP address, without a port"
                                   (when (and (nil? auth) (not (str/starts-with? (str/trim (str h)) "["))
                                              (ip-literal (str/trim (str h)) true))
                                     (str "; an IPv6 address goes in brackets: [" (str/trim (str h)) "]"))
                                   "; got " (pr-str h)))))
                    auth))
        bind-auth (host-authority (str bind))]
    {:names (into #{"localhost"} (keep :name) (cons bind-auth entries))
     :addresses (cond-> (into #{} (keep :address) entries)
                  (and bound (not (.isAnyLocalAddress bound))) (conj bound))}))

(defn answers-to
  "What `allowed` (`allowed-hosts`, for a server bound to `bound`)
  accepts, in words for the `--serve` startup line: the names, then the
  addresses, then the loopback addresses and, bound to the wildcard, the
  address a request arrives on."
  [allowed ^InetAddress bound]
  (let [address-text (fn [^InetAddress a]
                       (let [t (.getHostAddress a)]
                         (if (str/includes? t ":") (str "[" t "]") t)))]
    (str/join ", " (concat ["localhost"]
                           (sort (disj (:names allowed) "localhost"))
                           (sort (map address-text (:addresses allowed)))
                           ["any loopback address"]
                           (when (and bound (.isAnyLocalAddress bound))
                             ["the address a request arrives on"])))))

(defn host-allowed?
  "Whether the parsed host `auth` (`host-authority`) is one this server
  answers to: a name in `allowed`'s `:names`, or an address that is a
  loopback address, one of `allowed`'s `:addresses`, or `local` -- the
  address the request's own connection arrived on, which is how a
  server bound to `0.0.0.0` accepts `http://192.168.1.5:8080/` on the
  interface that has that address and no other name. Nil `auth` is
  never allowed. An IP literal is safe to accept where a name is not: a
  page that rebinds a DNS name to this machine still sends that name."
  [allowed ^InetAddress local auth]
  (boolean
   (when auth
     (if-let [n (:name auth)]
       (contains? (:names allowed) n)
       (let [^InetAddress a (:address auth)]
         (or (.isLoopbackAddress a)
             (contains? (:addresses allowed) a)
             (= a local)))))))

(defn- port-number [p]
  (when (seq p) (Long/parseLong p)))

(defn same-origin?
  "Whether the `Origin` header value `origin` names the host and port the
  request's `Host` (`auth`, `host-authority`) does -- the request then
  comes from a page of this very site. A port left out is the scheme's
  default, on both sides: behind `tailscale serve`, `Host: h.ts.net`
  and `Origin: https://h.ts.net` are the same, as are `Host: h:443` and
  `Origin: https://h`. False for `null`, a scheme other than http(s),
  or anything malformed."
  [auth origin]
  (boolean
   (when-let [[_ scheme authority] (re-matches #"(?i)(https?)://(.*)" (str origin))]
     (when-let [o (host-authority authority)]
       (let [default (if (= "https" (str/lower-case scheme)) 443 80)]
         (and (= (dissoc o :port) (dissoc auth :port))
              (= (or (port-number (:port o)) default)
                 (or (port-number (:port auth)) default))))))))

(defn refusal
  "Why the request `ex` must not be answered, `[status message]`, or nil
  when it may be (doc-12 \"The server\", TASK-292). In order:

  - 400 unless there is exactly one `Host` header and it is well formed
    (`host-authority`; RFC 9112 asks a server for 400 there);
  - 421 when its host is not one the server answers to
    (`host-allowed?`), or when the request target is absolute
    (`GET http://other/...`) and its authority is not -- a page that
    rebinds a DNS name of its own to this machine sends its own name,
    and is refused before it can read a file or the API;
  - 403 for a request that is not a `GET` or `HEAD` (the API's writes;
    nothing else takes one) and sends an `Origin` (or several) that is
    not this request's own host and port (`same-origin?`): `null`,
    another scheme, another name, or another port of the same name (a
    page served on `localhost:3000`). A write without an `Origin`
    (curl, an old browser's same-origin request) is let through. A
    reverse proxy must therefore pass `Host` on unchanged, as
    `tailscale serve` does."
  [^HttpExchange ex allowed]
  (let [headers ^Headers (.getRequestHeaders ex)
        hosts (.get headers "Host")
        local (some-> ^InetSocketAddress (.getLocalAddress ex) .getAddress)
        ok? #(host-allowed? allowed local %)
        uri (.getRequestURI ex)
        ;; absolute-form only: the JDK also reads an origin-form target
        ;; `//etc/passwd` as authority `etc`, which is a path here
        target-authority (when (.isAbsolute uri) (.getRawAuthority uri))
        auth (when (= 1 (count hosts)) (host-authority (first hosts)))
        origins (.get headers "Origin")]
    (cond
      (empty? hosts) [400 "missing Host header"]
      (< 1 (count hosts)) [400 "more than one Host header"]
      (nil? auth) [400 "malformed Host header"]
      (or (not (ok? auth))
          (and target-authority (not (ok? (host-authority target-authority)))))
      [421 (str "this server answers only to localhost, the address it listens on and the "
                "names its owner allows; to reach it by another name, start it with --host NAME")]
      (and (seq origins)
           (not (contains? #{"GET" "HEAD"} (.getRequestMethod ex)))
           (not (every? #(same-origin? auth %) origins)))
      [403 "a page of another site may not write to this server"])))

(def max-uri-length
  "The longest request target, as the client sent it, the server answers
  (414 beyond it): comparable to what common servers allow, and far more
  than any request this server's own pages make (a `/api/books` of 100
  ids is under 6 KB). Checked before the target is routed or decoded, so
  no query of millions of pairs is ever walked (TASK-294)."
  8192)

(defn- bad-target
  "`[status message]` when the request target of `ex` is refused before
  routing or decoding, else nil: 414 over `max-uri-length`; 400 for a
  target whose raw path does not start with `/`. That is reachable: the
  JDK routes on the decoded path, so `GET %2Fapi/readers` reaches here
  (decoded `/api/readers`, raw `%2Fapi/readers`) and is refused. (An
  opaque `http:host/x` target has no path at all; the JDK server drops it
  itself -- its context lookup throws on the null path -- before any
  handler runs, so that case never gets here.)"
  [^HttpExchange ex]
  (let [uri (.getRequestURI ex)
        text (.toString uri)
        path (.getRawPath uri)]
    (cond
      (> (.length text) (long max-uri-length)) [414 "request target too long"]
      (or (nil? path) (not (str/starts-with? path "/"))) [400 "malformed request target"])))

(defn- handle!
  "Answer one request against the server `state`."
  [^HttpExchange ex state]
  (try
    (set-header! ex "X-Content-Type-Options" "nosniff")
    (if-let [[status message] (or (bad-target ex) (refusal ex (:allowed state)))]
      (send-error! ex status message)
      (let [raw (.getRawPath (.getRequestURI ex))]
        (if (str/starts-with? (str raw) "/api/")
          (handle-api! ex state)
          (handle-static! ex (:root state)))))
    (catch Throwable t
      ;; The client went away -- it hung up while the response was sent,
      ;; or its connection failed (or timed out) while it sent the body.
      ;; Nothing to answer and nothing wrong with the server: no message.
      ;; An IOException before the response started is the server's own
      ;; (a reader file it cannot write) and is reported.
      (when-not (or (= ::client-gone (:type (ex-data t)))
                    (and (instance? IOException t) (not= -1 (.getResponseCode ex))))
        (binding [*out* *err*]
          (println (str "haselnuss: --serve: " (.getRequestMethod ex) " " (.getRawPath (.getRequestURI ex))
                        ": " (or (ex-message t) (.getName (class t))))))
        (try (send-error! ex 500 "internal error") (catch Throwable _ nil))))
    (finally (.close ex))))

;; --- the server --------------------------------------------------------------------

(def max-request-seconds
  "How long a client may take to send a request -- its headers, and its
  body if it has one (at most `max-body`) -- before the server closes the
  connection. The JDK server reads a request on one of its few threads,
  and without a limit eight clients that send half a request and stop (a
  phone that dropped off the network mid-save, or a deliberate
  slow-sender) hold every thread for good, and the server answers no one
  again."
  30)

(def max-response-seconds
  "How long sending one response may take before the connection is
  closed: long enough for a large book page to a phone on a slow network
  (20 MB at about half a megabit a second), short enough that a client
  that stops reading frees its thread."
  300)

(def server-properties
  "The JDK server's settings `start!` sets, each only when not already
  set (`-D` on the command line wins). Read once, when the server's
  configuration class loads -- so before the first server is made, and
  for every server of the process. The native image reads them at BUILD
  time; `bin/native` passes the same values.

  - `nodelay`: TCP_NODELAY on every connection. The JDK server writes a
    response's headers and body separately, and with Nagle's algorithm
    on, each response on a kept-alive connection waits out the client's
    delayed ACK -- about 40 ms a request (measured: 200 GETs of app.js
    took 8.9 s without it, 0.7 s with it).
  - `maxReqTime`, `maxRspTime`: `max-request-seconds`,
    `max-response-seconds`."
  {"sun.net.httpserver.nodelay" "true"
   "sun.net.httpserver.maxReqTime" (str max-request-seconds)
   "sun.net.httpserver.maxRspTime" (str max-response-seconds)})

(defn start!
  "Serve the site folder `:dir` on `:bind` (default `127.0.0.1`) and
  `:port` (default 8080; 0 picks a free one) with `:threads` threads
  (default 8), answering only requests whose `Host` names it
  (`refusal`): `localhost`, a loopback or the bind address, or one of
  the names (or addresses) in `:hosts` (`--host`). Answers
  `{:server :port :url :answers-to :root ...}` for `stop!`,
  `:answers-to` saying in words which hosts it accepts. Throws `ex-info`
  `::not-a-site` (`site-root`) or `::cannot-start` (the catalog cannot
  be read, the address cannot be resolved or bound, an entry of
  `:hosts` is not a host name or address)."
  [{:keys [dir port bind hosts threads reload-interval-ms]
    :or {port 8080 bind "127.0.0.1" threads 8 reload-interval-ms 1000}}]
  (let [root (site-root dir)
        fail (fn [what ^Throwable e]
               (throw (ex-info (str "--serve " dir ": " what ": " (or (ex-message e) (.getName (class e))))
                               {:type ::cannot-start} e)))
        catalog (try (load-catalog root) (catch Exception e (fail "cannot read the catalog" e)))
        ;; TCP_NODELAY and the time limits (`server-properties`), set
        ;; before the server is made; an explicit setting wins
        _ (doseq [[k v] server-properties]
            (when-not (System/getProperty k)
              (System/setProperty k v)))
        ^InetSocketAddress addr (try (InetSocketAddress. (InetAddress/getByName (str bind)) (int port))
                                     (catch Exception e (fail (str "cannot use the address " bind) e)))
        allowed (try (allowed-hosts bind (.getAddress addr) hosts)
                     (catch IllegalArgumentException e (fail "cannot allow that host" e)))
        state {:root root
               :catalog (atom (assoc catalog :checked-at (System/nanoTime)))
               :locks (atom {})
               :reload-interval-ms reload-interval-ms
               :allowed allowed}
        ^HttpServer server (try (HttpServer/create addr 64)
                                (catch Exception e (fail (str "cannot listen on " bind ":" port) e)))
        counter (atom 0)
        ^ExecutorService pool (Executors/newFixedThreadPool
                               (int threads)
                               (reify ThreadFactory
                                 (newThread [_ r]
                                   (doto (Thread. ^Runnable r (str "haselnuss-serve-" (swap! counter inc)))
                                     (.setDaemon true)))))]
    (.createContext server "/" (reify HttpHandler
                                 (handle [_ ex] (handle! ex state))))
    (.setExecutor server pool)
    (.start server)
    (let [bound (.getAddress server)
          actual (.getPort bound)
          host (.getHostString bound)]
      (assoc state :server server :pool pool :port actual
             :answers-to (answers-to allowed (.getAddress addr))
             :url (str "http://" (if (str/includes? host ":") (str "[" host "]") host) ":" actual "/")))))

(defn stop!
  "Stop a server `start!` answered."
  [{:keys [^HttpServer server ^ExecutorService pool]}]
  (when server (.stop server 0))
  (when pool
    (.shutdownNow pool)
    (.awaitTermination pool 5 TimeUnit/SECONDS)))

(ns haselnuss.site
  "The site a `--build-site DIR` run writes (doc-12, \"The build:
  --build-site\"; TASK-268): the folder checks, the build lock, where each
  book's page goes, writing pages and the reader app's files, and the
  messages a build prints. Scanning the libraries and emitting one book
  are `haselnuss.cli`'s, which hands them in (`write-site!`'s `:emit`), so
  this namespace never depends on the CLI.

  ## The folder

      DIR/
        index.html app.css app.js sw.js manifest.webmanifest   the reader app
        icon-192.png icon-512.png icon-maskable-512.png
        apple-touch-icon.png                                  its icons
        catalog/index.json  catalog/<library>/<n>.json        the catalog, paged
        books/<library>/<shelf...>/<slug>/index.html           one page per book
        books/<library>/<shelf...>/<slug>/backlinks.json       who refers to it
        books/<library>/<shelf...>/<slug>/previews.json        what is linked in it
        books/<library>/<shelf...>/<slug>/<image path>         its images, copied
        readers/                                              --serve's; never touched
        .haselnuss/state/  .haselnuss/lock                     build state and lock
        .haselnuss/images                                      the image copies made

  `DIR` must be empty, absent, or an existing site -- one holding
  `.haselnuss/state/` (`site?`) -- and writable; `preflight!` checks that
  before anything is written, so a mistyped path is refused rather than
  filled. A build then makes `.haselnuss/state/` first and takes
  `.haselnuss/lock` (`acquire-lock!`) before writing a page: an
  operating-system lock on that file (`FileChannel/tryLock`), so a build
  killed outright leaves no stale lock behind, and a second build while
  one runs is refused (exit 2). The saved state
  (`haselnuss.library.state`) relies on it: two saves must never run on
  one site at once.

  ## Pages

  A book's page is the normal `html` emit of the book with one `<link>` to
  `app.css` and one `<script>` for `app.js` added before `</head>`, each a
  path relative to the page, so the page still opens on its own
  (`with-reader-app`), after the inline theme style and script that apply
  the reader's theme before first paint (`theme-head`, TASK-275); its
  viewport gains `viewport-fit=cover`. Each book link on it carries
  `data-passage` (the CLI's site `:book-ref` handler), the key of the
  passage in the target's `previews.json`. Every file is written to a
  temporary file beside it and renamed into place (`write-under!`), so a
  reader -- or `--serve` -- sees the old page or the new one, never part
  of one.

  Every book link and quote source on a page points at the target book's
  page in the site, as a path relative to the citing page
  (`../../bible/blivre/romanos/index.html#v14-8`), whatever `url:`
  template the directory file has (`book-url`, which the CLI hands the
  build as `haselnuss.library.references/books-option`'s `:link-url`;
  doc-12, \"Output folder\"; TASK-269). The fragment is a label's id, a
  range's first label, or a locator passage's `:anchor` -- the id its
  book's `locate` names and its page has (`haselnuss.library.passage`);
  without one the link lands on the top of the page.

  `DIR` itself may be, or sit below, a symbolic link: the build works in
  its real path (`acquire-lock!`'s `:dir`). Below that, no link is
  followed: a page whose folder on the way is a symbolic link is not
  written (an `emit-failed` error), as the sweep never follows one either.

  An image a page shows by a relative path is copied from the book's
  folder to the same path below the page's folder, and only when it is
  a raster web image of at most `max-image-bytes` inside that folder
  whose copy does not land in another book's page folder (`image-src`,
  `locate-image`, `image-collector`; TASK-270): nothing outside a book's
  folder is read for its page (but see `locate-image` on hard links and
  the check-then-open window), nor written outside the page's folder,
  and no two books write one file. Copies are streamed with their
  digest (`copy-image!`). `.haselnuss/images` lists the copies, apart
  from the saved state, so the sweep removes stale ones whatever the
  state's format.

  Pages are emitted on a pool of `:threads` threads (default: the cores)
  and the results are put back in the order of the books, sorted by
  canonical id, so neither the files nor the messages depend on which
  thread finished first.

  Two books that share one canonical id (`x.hdoc` beside `x/book.hdoc`,
  a `duplicate-slug`) are both left unbuilt: no reference can reach
  either, and either page would hide the other.

  Every build makes `DIR` an exact rendering: a page under `books/` that
  no book has -- a book that was deleted, or one that now fails -- is
  removed, with the folders it leaves empty (`sweep!`).

  ## Incremental builds (TASK-272)

  `write-site!` follows a plan (`:plan`): the books to emit, each with the
  reason its line gives, and the pages to keep as they are with what the
  last build saved of them -- `full-plan` for a full build, `haselnuss.
  site.incremental/plan` for the others. A kept page is not read: its
  catalog entry, its images, its data files (unless the plan names them)
  and its warnings come from the saved state, so the site and the summary
  are the ones a clean build gives. The reader app's files are written
  only when their bytes change (or on a build that replaces its files,
  `write-site!`'s `:replace-files?`); `sw.js` carries the app's version
  (`app-file-bytes`).

  ## Data files (TASK-271)

  Beside the pages a build writes what the reader app reads as data
  (doc-12, \"Output folder\", \"Scale\" 4-5), each one only when its
  bytes differ from the file already there (`write-if-changed!`), so an
  unchanged file keeps its modification time -- except on a build with
  `--full`, or a saved state that is missing, unreadable or of another
  format, which replaces each with a new file (`write-site!`'s
  `:replace-files?`; TASK-290):

  - `catalog/index.json` and `catalog/<library>/<n>.json` (`catalog-files`):
    every book whose page this build wrote, by library, in pages of
    `catalog-page-size`.
  - `backlinks.json` beside each page: the book's `--backlinks` JSON report
    (`haselnuss.library.graph.backlinks`).
  - `previews.json` beside each page: every passage of the book other books
    link to, as `!quote` would show it, cut at about `preview-limit`
    characters (`preview-passages`, `cut-passage`; the CLI renders them).

  The README (\"Building a site\") documents the three JSON shapes.

  No records and no reflection: the native image needs only this
  namespace's `__init` class."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [haselnuss.book-ref :as book-ref]
            [haselnuss.library :as library]
            [haselnuss.library.digest :as digest]
            [haselnuss.library.filesystem :as fs]
            [haselnuss.library.graph :as graph]
            [haselnuss.library.graph.backlinks :as backlinks]
            [haselnuss.library.graph.check :as check]
            [haselnuss.library.graph.text :as text]
            [haselnuss.library.passage :as passage]
            [haselnuss.library.state :as state]
            [haselnuss.library.url :as url])
  (:import (java.io File IOException)
           (java.nio.channels FileChannel OverlappingFileLockException)
           (java.nio.file AtomicMoveNotSupportedException CopyOption Files LinkOption OpenOption
                          Path StandardCopyOption StandardOpenOption)
           (java.nio.file.attribute FileAttribute)
           ;; java.text.Normalizer, java.util.Arrays and java.util.Locale
           ;; are written out in full where used, not imported: an import
           ;; is a class lookup by name when the namespace loads, which
           ;; the native image would need reflect-config entries for
           (java.util.concurrent Callable ExecutionException ExecutorService Executors Future)))

;; --- the folder ------------------------------------------------------------------

(defn- sort-by-key
  "`(sort-by keyfn xs)`, as a vector, computing each key once: `sort-by`
  calls `keyfn` on both sides of every comparison, which over the tens of
  thousands of books of a site (a catalog's collation keys, formatted
  ids) costs seconds (TASK-272)."
  [keyfn xs]
  (mapv peek (sort-by first (map (fn [x] [(keyfn x) x]) xs))))

(def app-files
  "The reader app's files, copied from the classpath folder
  `haselnuss/site/` (`resources/haselnuss/site/`) to the top of the site.
  A fixed list rather than a folder listing: a resource folder cannot be
  listed from inside a jar or a native image. The PNGs are the installed
  app's icons (`manifest.webmanifest`, and `apple-touch-icon` for iOS;
  TASK-277), drawn once and kept as they are -- the build makes no image."
  ["index.html" "app.css" "app.js" "sw.js" "manifest.webmanifest"
   "icon-192.png" "icon-512.png" "icon-maskable-512.png" "apple-touch-icon.png"])

(def ^:private no-follow (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))

(defn- real-dir?
  "True when `f` is a folder and not a symbolic link to one."
  [^File f]
  (Files/isDirectory (.toPath f) no-follow))

(defn site?
  "True when the folder `dir` is a Haselnuss site: it holds
  `.haselnuss/state/` (neither of them a symbolic link)."
  [dir]
  (let [hidden (io/file dir ".haselnuss")]
    (and (real-dir? hidden) (real-dir? (state/state-dir dir)))))

(defn- refused [message]
  (ex-info message {:type ::refused}))

(defn- existing-ancestor
  "The nearest of `f` and its parents that exists, or nil."
  ^File [^File f]
  (loop [^File f (.getAbsoluteFile f)]
    (cond (nil? f) nil
          (.exists f) f
          :else (recur (.getParentFile f)))))

(defn preflight!
  "Throws `ex-info` `::refused` unless the site folder `dir` can be built
  into: it is absent (it will be made) or an empty folder, or a site
  (`site?`); and it, or the folder it would be made in, is writable.
  Writes nothing. `command` (default `--build-site`) starts each message."
  ([dir] (preflight! dir "--build-site"))
  ([dir command]
   (let [f (io/file dir)
         shown (str dir)]
     (cond
       (str/blank? shown)
       (throw (refused (str command " needs a folder")))

       (and (.exists f) (not (.isDirectory f)))
       (throw (refused (str command " " shown ": not a folder")))

       (and (.isDirectory f) (seq (.list f)) (not (site? f)))
       (throw (refused (str command " " shown ": the folder is neither empty nor a Haselnuss site"
                            " (a site holds .haselnuss/state/); refusing to write into it")))

       :else
       (let [^File target (if (.exists f) f (existing-ancestor f))]
         (when-not (and target (.isDirectory target) (Files/isWritable (.toPath target))
                        (or (not (.exists (io/file f ".haselnuss")))
                            (Files/isWritable (.toPath (io/file f ".haselnuss")))))
           (throw (refused (str command " " shown ": cannot write to "
                                (if (= target f) "the folder" (str target)))))))))))

(defn acquire-lock!
  "Make `dir` a site -- `dir`, `.haselnuss/state/` -- and take
  `.haselnuss/lock`. Answers `{:dir File ...}`, `:dir` being `dir`'s real
  path (`toRealPath`: a `dir` that is, or sits below, a symbolic link is
  built into where it leads), which everything after uses, and the lock,
  for `release-lock!`. Throws `ex-info` `::lock-held` when another build
  holds it (in this process too), and `::refused` when the folder cannot
  be written. The state folder is made first, so a folder that has been
  locked once is always a site. `command` (default `--build-site`) starts
  each message."
  ([given] (acquire-lock! given "--build-site"))
  ([given command]
   (try
     (when-not (.isDirectory (io/file given))
       (Files/createDirectories (.toPath (io/file given)) (make-array FileAttribute 0)))
     (let [dir (.toFile (.toRealPath (.toPath (io/file given)) (make-array LinkOption 0)))
           lock-file (io/file dir ".haselnuss" "lock")]
       (Files/createDirectories (.toPath (state/state-dir dir)) (make-array FileAttribute 0))
       (when-not (site? dir)
         (throw (refused (str command " " given ": .haselnuss or .haselnuss/state is not a folder"))))
       (let [ch (FileChannel/open (.toPath lock-file)
                                  ^"[Ljava.nio.file.OpenOption;"
                                  (into-array OpenOption [StandardOpenOption/CREATE StandardOpenOption/WRITE]))
             lock (try (.tryLock ch)
                       (catch OverlappingFileLockException _ nil)
                       (catch Throwable t (.close ch) (throw t)))]
         (if lock
           {:dir dir :channel ch :lock lock}
           (do (.close ch)
               (throw (ex-info (str command " " given ": another build holds " lock-file)
                               {:type ::lock-held}))))))
     (catch IOException e
       (throw (ex-info (str command " " given ": cannot write to the folder: "
                            (or (ex-message e) (.getName (class e))))
                       {:type ::refused} e))))))

(defn release-lock!
  "Release a lock `acquire-lock!` answered; nil is ignored."
  [{:keys [^FileChannel channel lock]}]
  (when lock (.release ^java.nio.channels.FileLock lock))
  (when channel (.close channel)))

;; --- writing -----------------------------------------------------------------------

(def ^:private temp-random
  "Picks temporary file names. Made on first use, and hinted where it is
  used rather than here: a class tag on a `def` is a class lookup by name
  when the namespace loads, which stops the native image at startup."
  (delay (java.security.SecureRandom.)))

(def ^:private exclusive-write
  "How a temporary file is opened: made by this very call (`CREATE_NEW`),
  so a file or symbolic link planted under its name is never opened."
  #{StandardOpenOption/CREATE_NEW StandardOpenOption/WRITE LinkOption/NOFOLLOW_LINKS})

(defn- temp-attributes
  "The attributes a temporary file in `dir` is created with: none when
  `public?`, so the operating system gives it what the umask allows an
  ordinary new file (TASK-290), else owner-only where the file system
  has POSIX permissions."
  ^"[Ljava.nio.file.attribute.FileAttribute;" [^Path dir public?]
  (if (and (not public?)
           (contains? (.supportedFileAttributeViews (.getFileSystem dir)) "posix"))
    (into-array FileAttribute
                [(java.nio.file.attribute.PosixFilePermissions/asFileAttribute
                  (java.nio.file.attribute.PosixFilePermissions/fromString "rw-------"))])
    (make-array FileAttribute 0)))

(defn- create-temp!
  "Create a temporary file `.<name>.<digits>.tmp` in `dir`, its name
  random, and answer `[path channel]`, the channel open for writing on
  the file this call made. A clash tries another name."
  [^Path dir ^String base public?]
  (let [attrs (temp-attributes dir public?)]
    (loop [n 0]
      (let [n-name (.nextLong ^java.security.SecureRandom @temp-random)
            p (.resolve dir (str "." base "." (Long/toUnsignedString n-name) ".tmp"))
            ch (try (FileChannel/open p ^java.util.Set exclusive-write attrs)
                    (catch java.nio.file.FileAlreadyExistsException e
                      (when (>= n 100) (throw e))
                      nil))]
        (if ch [p ch] (recur (inc n)))))))

(defn- temp-file-key
  "What identifies the temporary file at `p` (a link is not followed).
  Throws when the name no longer holds a regular file."
  [^Path p]
  (let [^java.nio.file.attribute.BasicFileAttributes attrs
        (Files/readAttributes p java.nio.file.attribute.BasicFileAttributes
                              ^"[Ljava.nio.file.LinkOption;" no-follow)]
    (when-not (.isRegularFile attrs)
      (throw (IOException. (str p " is no longer a regular file"))))
    (.fileKey attrs)))

(defn- write-atomically!
  "Write `content` to `target` through a temporary file in its folder
  (which exists), renamed over it -- atomically where the file system
  allows. `content` is bytes, or a fn of an OutputStream that writes it
  (a streamed copy). The temporary file is removed when anything fails.
  The file is private to its owner (0600) unless `public?`: then it has
  the mode the umask gives an ordinary new file, for a site another
  user's web server reads (TASK-290).

  Everything goes through the channel that created the file; its name is
  never opened again, so a symbolic link swapped in under the name is
  not written through. A name that no longer holds the file created is
  not renamed into place. The rename replaces `target`'s directory
  entry: a link there is not followed and a file hard-linked there keeps
  its content and mode."
  [^File target content public?]
  (let [dir (.getParentFile (.getAbsoluteFile target))
        [^Path tmp ^FileChannel ch] (create-temp! (.toPath dir) (.getName target) public?)]
    (try
      (let [created (temp-file-key tmp)]
        (with-open [out (java.nio.channels.Channels/newOutputStream ch)]
          (if (fn? content)
            (content out)
            (.write out ^bytes content)))
        (when-not (= created (temp-file-key tmp))
          (throw (IOException. (str tmp " was replaced while it was written")))))
      (try
        (Files/move tmp (.toPath target)
                    ^"[Ljava.nio.file.CopyOption;"
                    (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE
                                            StandardCopyOption/REPLACE_EXISTING]))
        (catch AtomicMoveNotSupportedException _
          (Files/move tmp (.toPath target)
                      ^"[Ljava.nio.file.CopyOption;"
                      (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING]))))
      target
      (finally
        (.close ch)
        (Files/deleteIfExists tmp)))))

(defn- real-folder!
  "Make the folder `p` unless it exists; throw unless it is then a folder
  and no symbolic link. `shown` names it in the message."
  [^Path p shown]
  (when-not (Files/exists p no-follow)
    (try (Files/createDirectory p (make-array FileAttribute 0))
         ;; another emit thread made it first
         (catch java.nio.file.FileAlreadyExistsException _ nil)))
  (when-not (Files/isDirectory p no-follow)
    (throw (ex-info (str shown " is a symbolic link or not a folder; refusing to write through it")
                    {:type ::not-a-folder :path (str p)}))))

(defn write-under!
  "Write `content` (bytes, or a fn writing to an OutputStream; see
  `write-atomically!`) to `rel`, a `/`-separated path below the site
  folder `root` (a real path, `acquire-lock!`'s `:dir`), atomically
  (temporary file and rename), making the folders on the way. Throws
  `ex-info` `::not-a-folder`, writing nothing, when a folder on the way
  is a symbolic link or a file: nothing is written outside `root`.
  The file is private (0600) unless `public?` (see `write-atomically!`):
  only the files a web server shows are written public; the default is
  private, as the build's own state must stay."
  ([root rel content] (write-under! root rel content false))
  ([^File root rel content public?]
   (let [parts (str/split rel #"/")]
     (reduce (fn [^Path p part]
               (let [next-p (.resolve p ^String part)]
                 (real-folder! next-p (str/replace (str (.relativize (.toPath root) next-p))
                                                   File/separator "/"))
                 next-p))
             (.toPath root)
             (butlast parts))
     (write-atomically! (io/file root rel) content public?))))

(defn write-if-changed!
  "`write-under!`, as a public file (TASK-290: the app files, the catalog,
  the backlinks and previews are all shown by a web server), the bytes
  `content` to `rel` below the site folder `root` unless the file there
  -- a regular file, reached through real folders only -- already holds
  exactly those bytes, in which case nothing is written and the file
  keeps its modification time (doc-12, \"What gets rebuilt\" step 6;
  TASK-271). With `replace?` it is written whatever is there, as a new
  file with the mode the umask gives now (`write-site!`'s
  `:replace-files?`). Answers true when it wrote."
  ([root rel content] (write-if-changed! root rel content false))
  ([^File root rel ^bytes content replace?]
   (let [parts (str/split rel #"/")
         folders (reductions (fn [^Path p ^String s] (.resolve p s)) (.toPath root) (butlast parts))
         target (.resolve ^Path (last folders) ^String (last parts))
         same? (and (not replace?)
                    (every? #(Files/isDirectory ^Path % no-follow) (rest folders))
                    (Files/isRegularFile target no-follow)
                    (= (alength content) (try (Files/size target) (catch IOException _ -1)))
                    (try (java.util.Arrays/equals content (Files/readAllBytes target))
                         (catch IOException _ false)))]
     (if same?
       false
       (do (write-under! root rel content true) true)))))

(defn- json-bytes
  "`x` as one line of JSON and a newline, UTF-8, with `/` and non-ASCII
  characters written as they are."
  ^bytes [x]
  (.getBytes (str (json/write-str x :escape-slash false :escape-unicode false) "\n") "UTF-8"))

(defn- resource-bytes
  ^bytes [file-name]
  (let [url (io/resource (str "haselnuss/site/" file-name))]
    (when-not url
      (throw (ex-info (str "the reader app file haselnuss/site/" file-name " is missing from this build")
                      {:type ::missing-app-file :name file-name})))
    (with-open [in (io/input-stream url)
                out (java.io.ByteArrayOutputStream.)]
      (io/copy in out)
      (.toByteArray out))))

(def app-version-token
  "What `sw.js` holds where the build writes the app's version
  (`app-file-bytes`)."
  "__HASELNUSS_APP_VERSION__")

(defn app-file-bytes
  "`{file-name bytes}` of the reader app's files (`app-files`) as a build
  writes them: each as the classpath holds it, except that every
  `app-version-token` in `sw.js` is replaced by the app's version -- the
  first 16 hex digits of the SHA-256 over every app file's name, length
  and bytes as the classpath holds them (doc-12, \"Updates\": a new
  version of the app changes the service worker's version string, so a
  device installs it; TASK-272). The version changes exactly when an app
  file does, and is the same on every machine and every run, so an
  unchanged app is written byte for byte as before."
  []
  (let [raw (into (sorted-map) (map (fn [n] [n (resource-bytes n)])) app-files)
        md (java.security.MessageDigest/getInstance "SHA-256")
        _ (doseq [[n ^bytes bs] raw]
            (.update md (.getBytes (str n "\n" (alength bs) "\n") "UTF-8"))
            (.update md bs))
        version (subs (digest/hex (.digest md)) 0 16)]
    (update raw "sw.js" (fn [^bytes bs]
                          (.getBytes (str/replace (String. bs "UTF-8") app-version-token version) "UTF-8")))))

(defn copy-app-files!
  "Write the reader app's files (`app-file-bytes`) to the top of the site
  `dir`, each only when its bytes changed (`write-if-changed!`): a new
  app is copied, with a new service-worker version, whatever else the
  build rebuilds, and an unchanged one keeps its modification times.
  `sw.js` is written last (`app-file-bytes` is sorted by name, and an
  assertion keeps it so): a device that sees the new worker -- which
  precaches the other files at once -- finds the new files already in
  place (TASK-277). With `replace?` each is written even when its bytes
  match (`write-if-changed!`)."
  ([dir] (copy-app-files! dir false))
  ([dir replace?]
   (let [files (app-file-bytes)]
     (assert (= "sw.js" (key (last files))) "sw.js must be the last app file written")
     (doseq [[file-name content] files]
       (write-if-changed! dir file-name content replace?)))))

(defn site-file?
  "True when `rel`, a `/`-separated path below the site folder `root`, is
  a regular file reached through real folders only (no symbolic link on
  the way) -- the files a build writes, as `write-if-changed!` checks
  them."
  [^File root rel]
  (let [parts (str/split rel #"/")
        folders (reductions (fn [^Path p ^String s] (.resolve p s)) (.toPath root) (butlast parts))
        target (.resolve ^Path (last folders) ^String (last parts))]
    (and (every? #(Files/isDirectory ^Path % no-follow) (rest folders))
         (Files/isRegularFile target no-follow))))

;; --- images ------------------------------------------------------------------------

(def image-extensions
  "The file extensions (lower case) of the images a book's page may show
  from the site: the raster formats a browser shows in an `<img>`. Any
  other local file an image names is not copied -- a page cannot make
  the build serve a book's source, its `.bib`, or anything else beside
  it. Not `svg`: an SVG opened directly (not through `<img>`) runs its
  script on the site's origin, which hosts the reader app, its service
  worker and the positions API; it can come back once `--serve` sends
  every file under `books/` with a sandboxing Content-Security-Policy
  (TASK-273)."
  #{"apng" "avif" "bmp" "gif" "ico" "jpeg" "jpg" "png" "webp"})

(def max-image-bytes
  "The largest image, in bytes, a page's copy is made of (50 MiB). A
  larger one is dropped with a warning: a copy is streamed, never held in
  memory, but a site is read in a browser, and one huge file in a
  library should not fill the site's disk or stall the build."
  (* 50 1024 1024))

(defn- control-char? [c]
  (let [c (int c)] (or (< c 0x20) (= c 0x7f))))

(defn- percent-decode
  "`s` with every `%XX` decoded as UTF-8 bytes, as a browser does to a
  relative URL's path when it asks for the file; a `%` not followed by
  two hex digits stays as it is."
  ^String [^String s]
  (let [out (java.io.ByteArrayOutputStream.)
        n (count s)]
    (loop [i 0]
      (when (< i n)
        (let [c (.charAt s i)
              hex (when (and (= \% c) (<= (+ i 3) n))
                    (let [h (subs s (inc i) (+ i 3))]
                      (when (re-matches #"[0-9A-Fa-f]{2}" h) (Integer/parseInt h 16))))]
          (if hex
            (do (.write out (int hex)) (recur (+ i 3)))
            (let [bs (.getBytes (str c) "UTF-8")]
              (.write out bs 0 (alength bs))
              (recur (inc i)))))))
    (String. (.toByteArray out) "UTF-8")))

(defn image-src
  "What a site page does with an image's `src` (doc-12, \"Output
  folder\"; TASK-270):

  - `{:as-is true}`: a URL with a scheme of two letters or more
    (`https:`; by now the sanitizer has dropped every scheme but http,
    https and mailto, `data:` included), a scheme-relative
    `//host/...`, a `#fragment` or a blank src. Nothing is read or
    copied. The scheme is read as a browser and the sanitizer read it:
    tabs and newlines removed, leading spaces and control characters
    stripped.
  - `{:path [segment ...] :suffix s}`: a relative path, leading spaces
    and control characters stripped as a browser strips them,
    percent-decoded as a browser decodes it, without `.` and empty
    segments, naming a file with one of `image-extensions`; `:suffix` is
    the `?query` or `#fragment` it carried, kept on the rewritten src.
  - `{:refused reason}`: an absolute path (`/`, `C:`, `\\\\`), a
    one-letter scheme (`a:b.png`, which a browser reads as one), a
    backslash or control character, a `..` segment, a hidden segment
    (`.git`), or a file that is no web image. Checked AFTER decoding,
    so `%2e%2e` is a `..`.

  Pure: reads nothing."
  [src]
  (let [src (str src)
        trimmed (str/replace src #"^[\u0000- ]+" "")
        cleaned (str/replace trimmed #"[\t\n\r]" "")
        [_ path suffix] (re-matches #"(?s)([^?#]*)(.*)" trimmed)]
    (cond
      (or (str/blank? cleaned) (str/starts-with? cleaned "#"))
      {:as-is true}

      (or (re-find #"^[A-Za-z][A-Za-z0-9+.\-]+:" cleaned) (str/starts-with? cleaned "//"))
      {:as-is true}

      (re-find #"^[A-Za-z]:" cleaned)
      {:refused "it starts with a one-letter scheme or a drive letter"}

      :else
      (let [decoded (percent-decode path)
            segments (remove #(contains? #{"" "."} %) (str/split decoded #"/" -1))
            file-name (last segments)
            ext (some->> file-name (re-find #"\.([^.]+)$") second str/lower-case)]
        (cond
          (or (some control-char? trimmed) (some control-char? decoded))
          {:refused "it contains a control character"}

          (or (str/includes? trimmed "\\") (str/includes? decoded "\\"))
          {:refused "it contains a backslash"}

          (or (str/starts-with? path "/") (str/starts-with? decoded "/"))
          {:refused "it is an absolute path"}

          (some #{".."} segments)
          {:refused "it leaves the book's folder (`..`)"}

          (some #(str/starts-with? % ".") segments)
          {:refused "it names a hidden file or folder"}

          (empty? segments)
          {:refused "it names no file"}

          (not (contains? image-extensions ext))
          {:refused (str "it is not an image a browser shows ("
                         (str/join ", " (sort image-extensions)) ")")}

          :else
          {:path (vec segments) :suffix suffix})))))

(defn locate-image
  "`{:source Path :size n}` for the image `segments` (`image-src`'s
  `:path`) below the book's folder `book-dir` -- `:source` its real
  path -- or `{:refused reason}`: when the book has no folder (a
  generated book), or the file -- its real path, every symbolic link
  followed -- is not a regular file inside `book-dir`'s real path, is
  larger than `max-image-bytes`, or cannot be read. Reads no content.

  What this does not close, by design: a hard link inside the book's
  folder to a file elsewhere is a regular file inside the folder, so it
  is copied (it leaks only to a build with more read access than whoever
  made the link); and the file is opened by its real path after this
  check, so a folder on that path swapped for a symbolic link in
  between is followed -- which takes someone writing into the book's
  folder during the build."
  [book-dir segments]
  (if (nil? book-dir)
    {:refused "the book has no folder of its own (a generated book); only URLs are shown"}
    (try
      (let [base (.toRealPath (.toPath (io/file book-dir)) (make-array LinkOption 0))
            ^Path p (reduce (fn [^Path p ^String s] (.resolve p s)) base segments)]
        (if-not (Files/exists p (make-array LinkOption 0))
          {:refused "there is no such file beside the book"}
          (let [real (.toRealPath p (make-array LinkOption 0))]
            (cond
              (not (.startsWith real base))
              {:refused "it leads outside the book's folder (a symbolic link)"}

              (not (Files/isRegularFile real no-follow))
              {:refused "it is not a regular file"}

              (< max-image-bytes (Files/size real))
              {:refused (str "it is larger than " (quot max-image-bytes (* 1024 1024)) " MiB")}

              :else
              {:source real :size (Files/size real)}))))
      (catch IOException e
        {:refused (str "it cannot be read: " (or (ex-message e) (.getName (class e))))}))))

(defn- stream-image!
  "Stream the file `source` into `out` (an OutputStream, or nil to only
  read it), answering the hex SHA-256 of what was read. Throws
  `ex-info` `::image-too-large` once more than `max-image-bytes` were
  read (the file grew after `locate-image`), so no copy is ever larger."
  ^String [^Path source ^java.io.OutputStream out]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")
        buf (byte-array 65536)]
    (with-open [in (Files/newInputStream source ^"[Ljava.nio.file.OpenOption;" (make-array OpenOption 0))]
      (loop [total 0]
        (let [n (.read in buf)]
          (when (pos? n)
            (when (< max-image-bytes (+ total n))
              (throw (ex-info (str source " grew past " max-image-bytes " bytes while it was copied")
                              {:type ::image-too-large})))
            (.update md buf 0 n)
            (when out (.write out buf 0 n))
            (recur (+ total n))))))
    (digest/hex (.digest md))))

(defn copy-image!
  "Copy the image file `source` into the OutputStream `out` by streaming,
  answering the digest of the bytes copied (`stream-image!`). The caller
  closes `out`."
  ^String [^Path source ^java.io.OutputStream out]
  (stream-image! source out))

(defn image-digest
  "The digest of the image `segments` below `book-dir` as a copy of it
  would have now, or nil when it would not be copied (`locate-image`,
  or a read that fails)."
  [book-dir segments]
  (let [{:keys [source]} (locate-image book-dir segments)]
    (when source
      (try (stream-image! source nil)
           (catch IOException _ nil)
           (catch clojure.lang.ExceptionInfo _ nil)))))

(defn- image-diagnostic [src reason]
  {:type :site-image
   :src src
   :message (str "Dropped the image \"" src "\" from the site page: " reason ".")})

(defn image-collector
  "The images of one book's page, `book-dir` being the folder its file
  is in (nil for a generated book): `{:src f :result g}`. `(f src)`
  answers the src the page uses -- the same relative path, percent-
  encoded, which the build copies the image to below the page's folder
  -- or nil when the image is dropped (`image-src`, `locate-image`), and
  a URL as it is. `(g)` answers `{:images [{:path :source}] :refused
  [{:dir :path :reason}] :diagnostics [...]}`: the images to copy,
  `:path` relative to the page's folder and `:source` the real path to
  copy from; the images `locate-image` refused (what is on disk decided:
  missing, too large, a link out of the folder ...), `:dir` the book's
  folder, for `changed-image-books` (TASK-272); and one warning per
  dropped src. A src is decided once however often the page
  uses it. Two paths that differ only in case (`A.png`, `a.png`) are one
  file on a case-insensitive file system, so the second is dropped. Not
  shared between threads: one per page.

  `site` is `{:page-dir d :page-dir? f}` (`write-site!` hands it to its
  `:emit`): the page's folder, `books/.../<slug>/`, and a predicate true
  of the folder (same form) of any OTHER book's page. An image whose copy
  would land in another book's page folder -- `s.hdoc` showing
  `x/fig.png` beside a book `s/x` -- is dropped, so no two books ever
  write one file, whichever thread runs first. Without `site` (nil)
  nothing is checked."
  ([book-dir] (image-collector book-dir nil))
  ([book-dir {:keys [page-dir page-dir?]}]
   (let [state (atom {:seen {} :images {} :folded {} :diagnostics []})
         in-other-page? (fn [path]
                          (when (and page-dir page-dir?)
                            ;; lower-cased: page folders are lower-case names,
                            ;; and `X/` is `x/` on a case-insensitive file system
                            (some (fn [k] (page-dir? (str/lower-case
                                                      (str page-dir (str/join "/" (take k path)) "/"))))
                                  (range 1 (count path)))))
         decide (fn [src]
                  (let [{:keys [as-is path suffix] why :refused} (image-src src)]
                    (cond
                      as-is [src nil]
                      why [nil (image-diagnostic src why)]
                      :else
                      (let [rel (str/join "/" path)
                            folded (str/lower-case rel)
                            other (get-in @state [:folded folded])
                            clash (and other (not= other rel))
                            nested (and (not clash) (in-other-page? path))
                            found (when-not (or clash nested)
                                    (or (get-in @state [:images rel]) (locate-image book-dir path)))]
                        (cond
                          clash
                          [nil (image-diagnostic src (str "it differs only in case from \"" other "\""))]

                          nested
                          [nil (image-diagnostic src "its copy would land in another book's page folder")]

                          (:refused found)
                          (do
                            ;; a refusal by what is on disk -- no such file,
                            ;; too large, a link out of the folder -- can be
                            ;; lifted without the page changing; the state
                            ;; keeps it so a later build sees that (TASK-272)
                            (when (some? book-dir)
                              (swap! state assoc-in [:refused rel] {:dir (str book-dir) :path rel
                                                                    :reason (:refused found)}))
                            [nil (image-diagnostic src (:refused found))])

                          :else
                          (do (swap! state #(-> %
                                                (assoc-in [:images rel] {:path rel :source (:source found)})
                                                (assoc-in [:folded folded] rel)))
                              [(str (str/join "/" (map #(url/encode % false) path)) suffix) nil]))))))]
     {:src (fn [src]
             (if-let [[answer] (get-in @state [:seen src])]
               answer
               (let [[answer diagnostic] (decide src)]
                 (swap! state #(cond-> (assoc-in % [:seen src] [answer])
                                 diagnostic (update :diagnostics conj diagnostic)))
                 answer)))
      :result (fn []
                (let [{:keys [images diagnostics] refused-images :refused} @state]
                  {:images (vec (vals (into (sorted-map) images)))
                   :refused (vec (vals (into (sorted-map) refused-images)))
                   :diagnostics diagnostics}))})))

(defn- saved-image-path?
  "True when `segments` (a `/`-split path read back from the saved
  state) is one an image could have been copied to or from: no empty,
  `.`, `..` or hidden segment, no backslash or control character, an
  image extension last. The state is the site's own, but a path out of
  it is still checked before anything is read or deleted by it."
  [segments]
  (and (seq segments)
       (every? (fn [^String s]
                 (and (not= "" s) (not (str/starts-with? s "."))
                      (not (str/includes? s "\\")) (not-any? control-char? s)))
               segments)
       (contains? image-extensions
                  (some->> (last segments) (re-find #"\.([^.]+)$") second str/lower-case))))

(defn changed-image-books
  "The BookIds among the saved state's `:books` (BookId -> `{:images
  {site-path {:dir :path :digest}}}`, as `--build-site` saves them) whose
  page shows an image that is now different, gone or no longer readable
  under the same rules (`image-digest`), or whose page refused an image
  (`:refused-images [{:dir :path :reason}]`, `image-collector`'s
  `:refused`) that `locate-image` now refuses for another reason or
  accepts, sorted -- the books an incremental
  build must rebuild when only images changed (doc-12, \"Left to the
  builder\"; TASK-272). Images are not in the graph's `:files`, so this
  is the only place their edits show.

  Every image is read again, on several threads (TASK-279: 2,000 books
  of 500 KB images took 7.3 s on one; image stamps that skip the read
  when the stat is unchanged are a follow-up)."
  [saved-books]
  (let [changed? (fn [[_ {:keys [images refused-images]}]]
                   (or (some (fn [[_ {:keys [dir path digest]}]]
                               (let [segments (when (string? path) (str/split path #"/"))]
                                 (or (not (string? dir))
                                     (not (saved-image-path? segments))
                                     (not= digest (image-digest dir segments)))))
                             images)
                       ;; an image the page refused for what was on disk:
                       ;; refused now for another reason, or not at all
                       ;; (TASK-272)
                       (some (fn [{:keys [dir path reason]}]
                               (let [segments (when (string? path) (str/split path #"/"))]
                                 (or (not (string? dir))
                                     (not (saved-image-path? segments))
                                     (not= reason (:refused (locate-image dir segments))))))
                             refused-images)))]
    (->> (try (doall (pmap #(filterv changed? %) (partition-all 64 (vec saved-books))))
              (catch ExecutionException e (throw (or (.getCause e) e))))
         (into [] (comp cat (map key)))
         (sort-by book-ref/format-book-id)
         vec)))

;; --- pages -------------------------------------------------------------------------

(defn page-path
  "The page of the book `id` relative to the site, as `/`-separated text:
  `books/<library>/<shelf...>/<slug>/index.html`. nil when a part of the
  id is not a valid name (it could not be one; a check, not a path
  sanitiser)."
  [{:keys [library shelf slug]}]
  (let [parts (concat [library] shelf [slug])]
    (when (every? library/valid-name? parts)
      (str/join "/" (concat ["books"] parts ["index.html"])))))

(defn- fragment
  "The id a link to `passage` (a parsed passage, or nil for the whole
  book) lands on: a label's label, a range's first label, a locator's
  `anchor` -- nil without one, and for the whole book."
  [passage anchor]
  (case (:form passage)
    :label (:label passage)
    :range (:from passage)
    :locator anchor
    nil))

(defn book-url
  "The URL, relative to the page of the book `from` (a BookId), of the
  passage `passage` (parsed, or nil for the whole book) of the book `to`
  (its CURRENT BookId), whose resolved Passage's `:anchor` is `anchor`
  (doc-12, \"Output folder\"; TASK-269): the path from the one page's
  folder to the other page -- `../../bible/blivre/romanos/index.html` --
  then `#` and the id it lands on (`fragment`), percent-encoded as a URL
  template's values are (`haselnuss.library.url/encode`; `:` kept), or
  no `#` at all, so the link lands on the top of the page. nil when
  either book has no `page-path`."
  [from to passage anchor]
  (let [from-page (page-path from)
        to-page (page-path to)]
    (when (and from-page to-page)
      (let [from-dir (butlast (str/split from-page #"/"))
            to-parts (str/split to-page #"/")
            shared (count (take-while true? (map = from-dir (butlast to-parts))))
            target (fragment passage anchor)]
        (str (apply str (repeat (- (count from-dir) shared) "../"))
             (str/join "/" (map url/encode (drop shared to-parts)))
             (when (seq target) (str "#" (url/encode target))))))))

(def ^:private emitted-viewport
  "The viewport `<meta>` the html emitter writes on every page."
  "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"/>")

(def ^:private site-viewport
  "A site page's viewport `<meta>`: the emitter's plus `viewport-fit=cover`,
  so an installed app draws under the notch and home bar and the reader
  app pads its controls by the safe-area insets (doc-12, \"The app shell\")."
  "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, viewport-fit=cover\"/>")

(def theme-head
  "The tiny inline `<style>` and `<script>` every site page carries ahead
  of the reader app's stylesheet (TASK-275; doc-12, \"The app shell\":
  \"book pages load with the saved theme before first paint\"). The
  script runs while `<head>` is parsed, before `<body>` exists: it sets
  `data-theme` on `<html>` to the device's stored theme (`light`, `sepia`
  or `dark`; without one, `dark` when the system prefers it, else
  `light`) and the stored font size, as a percentage on `<html>`. The
  style gives each theme its colours without waiting for `app.css` to
  load, so a dark page never shows white first, even when the app's
  stylesheet is slow or missing. Nothing in it comes from the book; the
  two `localStorage` keys are `app.js`'s own."
  (str "<style>"
       "html[data-theme=light]{color-scheme:light;background:#fff;color:#1d1d1d}"
       "html[data-theme=sepia]{color-scheme:light;background:#f4ecd8;color:#3b3226}"
       "html[data-theme=dark]{color-scheme:dark;background:#121212;color:#d6d0c4}"
       "</style>"
       "<script>(function(d){var t,f;"
       "try{t=localStorage.getItem('haselnuss.theme');f=Number(localStorage.getItem('haselnuss.fontSize'))}catch(e){}"
       "if(t!=='light'&&t!=='sepia'&&t!=='dark')"
       "t=window.matchMedia&&matchMedia('(prefers-color-scheme: dark)').matches?'dark':'light';"
       "d.setAttribute('data-theme',t);"
       "if(f>=70&&f<=200)d.style.fontSize=f+'%'"
       "})(document.documentElement)</script>"))

(defn with-reader-app
  "`html`, a page `page` (`page-path`) of the site, with the reader app
  added before `</head>`: `theme-head`, then the app's stylesheet and
  script as paths relative to the page (TASK-268, TASK-275). The
  emitter's viewport `<meta>` gains `viewport-fit=cover` (`site-viewport`)."
  [^String html page]
  (let [up (apply str (repeat (dec (count (str/split page #"/"))) "../"))
        at (str/index-of html "</head>")]
    (when-not at
      (throw (ex-info "the emitted page has no </head>" {:type ::no-head})))
    (let [head (subs html 0 at)
          vp (str/index-of head emitted-viewport)]
      (str (if vp
             (str (subs head 0 vp) site-viewport (subs head (+ vp (count emitted-viewport))))
             (str head site-viewport))
           theme-head
           "<link rel=\"stylesheet\" href=\"" up "app.css\"/>"
           "<script src=\"" up "app.js\" defer></script>"
           (subs html at)))))

(defn- listed
  "Every Book of the Library `lib`: `listed-books` when it has them, else
  the one `find-book` answers for each listed entry, when that is the
  entry's book."
  [lib]
  (if (satisfies? library/ListedBooks lib)
    (vec (library/listed-books lib))
    (vec (for [entry (library/list-books lib)
               :let [found (library/find-book lib (:slug (:id entry)))]
               :when (and (satisfies? library/Book found)
                          (= (:id entry) (:id (library/book-entry found))))]
           found))))

(defn site-books
  "The books of every library in `lib-names` of the directory `dir`, one
  entry per canonical id, sorted by it: `{:id BookId :book Book}` for a
  book to build, or `{:id :duplicates n}` when `n` books from different
  sources share the id (`haselnuss.library.passage/book-source`; a
  generated shelf listing one slug twice is one book, as in the scan). A
  library that fails to list is skipped: the scan reports it."
  [dir lib-names]
  (let [books (mapcat (fn [lib-name]
                        (let [lib (library/find-library dir lib-name)]
                          (when (satisfies? library/Library lib)
                            (try (listed lib) (catch Exception _ [])))))
                      lib-names)
        groups (group-by #(:id (library/book-entry %)) books)]
    (->> groups
         (map (fn [[id group]]
                (let [sources (distinct (map passage/book-source group))]
                  (if (< 1 (count sources))
                    {:id id :duplicates (count sources)}
                    {:id id :book (last group)}))))
         (sort-by-key (comp book-ref/format-book-id :id)))))

;; --- text ---------------------------------------------------------------------------

(def ^:private skipped-fields
  "Fields of a node that hold no text a reader sees: its attributes and a
  directive's target-specific fallback. (Plain string fields -- a link's
  target, a quote's reference -- are never text: only the fields
  `node-text` names are.)"
  #{:attr :fallback})

(defn- kind [x] (when (map? x) (some-> (:t x) keyword)))

(defn- field-order
  "The fields of the node `x` that may hold children, in document order:
  a section's heading before its blocks, else sorted (a map's own order
  is not document order)."
  [x]
  (if (= :section (kind x))
    [:heading :blocks]
    (sort (remove skipped-fields (keys x)))))

(defn- text*
  "`node-text`, leaving out what a footnote or sticky note holds unless
  `notes?`, and section headings unless `headings?`."
  [x notes? headings?]
  (let [rec #(text* % notes? headings?)]
    (cond
      (sequential? x) (apply str (map rec x))
      (not (map? x)) ""
      :else
      (case (kind x)
        (:str :code :code-block) (str (:text x))
        (:math-inline :math-block) (str (:tex x))
        (:space :soft-break :line-break) " "
        :image (str (:alt x))
        (:cross-ref :book-ref) (let [t (:text x)]
                                 (cond (string? t) t
                                       (sequential? t) (rec t)
                                       :else (str (or (:label x) (:ref x)))))
        :section (str (when headings? (rec (:heading x))) (rec (:blocks x)))
        :list (apply str (map rec (:items x)))
        :book-quote (rec (:blocks x))
        (:note :sticky-note) (if notes? (rec (:blocks x)) "")
        ;; a resolved citation's formatted text, else its items
        :cite (if (contains? x :text) (rec (:text x)) (rec (:items x)))
        ;; every other node: the text of its children, field by field
        (apply str (map (fn [k] (rec (get x k))) (field-order x)))))))

(defn node-text
  "The plain text of `x` -- an AST node, or a vector of them -- as a reader
  sees it, in document order: `:str`, `:code` and code-block text, maths
  as its TeX, an image's alt text, a space for each space or break, a
  reference's resolved text, and a footnote's text at its marker. Used
  to measure and cut previews and to give titles as text; deliberately
  permissive, like the html emitter's `<title>` text."
  [x]
  (text* x true true))

(defn- text-length
  "How many characters of the BODY text `x` holds: `node-text` without
  what footnotes hold, which a preview counts after the body."
  ^long [x]
  (count (text* x false true)))

(defn- content-text
  "The body text of `x` without its section headings: what a preview must
  not be without."
  [x]
  (text* x false false))

;; --- previews: cutting a passage --------------------------------------------------

(def preview-limit
  "About how many characters of text a preview holds (doc-12, \"Output
  folder\": \"cut at about 2,000 characters\"). A passage longer than this
  is cut (`cut-passage`) and flagged; the reader offers \"Open to read
  more\"."
  2000)

(def ^:private ellipsis "…")

(defn- hard-cap
  "How long a block that cannot be cut may be to be kept whole in a preview
  of `limit` characters: four times `limit`. A passage that starts with a
  200 KB table must not put all of it in `previews.json`."
  [limit]
  (* 4 limit))

(defn- whitespace-at? [^String s i]
  (Character/isWhitespace (.codePointAt s (int i))))

(defn- trimr*
  "`s` without trailing whitespace (Unicode, as `Character/isWhitespace`
  reads it)."
  [^String s]
  (loop [n (count s)]
    (if (and (pos? n) (Character/isWhitespace (.codePointBefore s (int n))))
      (recur (- n (Character/charCount (.codePointBefore s (int n)))))
      (subs s 0 n))))

(defn- joins-previous?
  "True when the character at `i` of `s` belongs to the character before
  it: the second half of a surrogate pair, a combining mark, a variation
  selector, a zero-width joiner or what follows one. A cut never falls
  there, so `cafe\\u0301` is never left as `cafe`."
  [^String s i]
  (let [i (int i)
        c (.charAt s i)
        cp (.codePointAt s i)
        t (Character/getType cp)]
    (or (Character/isLowSurrogate c)
        (= t (int Character/NON_SPACING_MARK))
        (= t (int Character/ENCLOSING_MARK))
        (= t (int Character/COMBINING_SPACING_MARK))
        (<= 0xFE00 cp 0xFE0F)
        (= 0x200D cp)
        (and (pos? i) (= 0x200D (int (.charAt s (dec i))))))))

(defn- cut-string
  "The first at most `n` (positive, less than its length) characters of
  `s`, trailing whitespace dropped, or nil when nothing is kept. Cut at
  the last whitespace (Unicode) within them, so no word is split -- nil
  when there is none -- unless `hard?`, when a word that does not fit
  is cut inside, but never inside a character as a reader sees it
  (`joins-previous?`)."
  [^String s n hard?]
  (let [kept (if hard?
               (loop [i (int n)]
                 (cond (<= i 0) nil
                       (joins-previous? s i) (recur (dec i))
                       :else (subs s 0 i)))
               (loop [i (int n)]
                 (cond (<= i 0) nil
                       (whitespace-at? s i) (subs s 0 i)
                       :else (recur (dec i)))))
        kept (some-> kept trimr*)]
    (when (seq kept) kept)))

(def ^:private inline-containers
  "Inlines that only wrap other inlines, and so can be cut inside."
  #{:emph :strong :strike :small-caps :sub :sup :link :span :highlight})

(declare cut-inline cut-block)

(defn- cut-seq
  "`[kept cut?]`: the leading `xs` whose body text (`text-length`) fits in
  `left` characters, then what `(cut x left)` keeps of the first that
  does not (nil: none of it), and nothing after it."
  [xs left cut]
  (loop [[x & more :as xs] (seq xs) left left acc []]
    (if-not xs
      [acc false]
      (let [n (text-length x)]
        (if (<= n left)
          (recur more (- left n) (conj acc x))
          [(if-let [part (when (pos? left) (cut x left))] (conj acc part) acc) true])))))

(defn- cut-inline
  "What of the inline `x`, longer than `left`, fits: a `:str` cut at a
  word (`cut-string`; nil when its first word does not fit, so the cut
  falls at the space before it), a wrapping inline cut inside; any other
  (maths, code) is dropped -- or, when `hard?` (a number, `hard-cap`),
  kept whole if its text is at most that long."
  [hard? x left]
  (let [t (kind x)]
    (cond
      (= :str t) (when-let [text (cut-string (:text x) left hard?)] (assoc x :text text))
      (contains? inline-containers t)
      (let [[kept] (cut-seq (:inlines x) left (partial cut-inline hard?))]
        (when (seq kept) (assoc x :inlines kept)))
      (and hard? (<= (text-length x) hard?)) x
      :else nil)))

(defn- cut-block
  "What of the block `b`, longer than `left`, fits: a paragraph's leading
  inlines, a section's heading and leading blocks (nothing when the
  heading alone does not fit), a quote's leading blocks, a list's leading
  items. Any other block (a table, code, maths, a directive) cannot be
  cut: it is dropped -- or, when `hard?` (nil, or a number: `hard-cap`),
  kept whole if its text (`text-length`) is at most `hard?` characters.
  `hard?` also lets a word be cut inside and counts no heading."
  [hard? b left]
  (let [cut (partial cut-block hard?)]
    (case (kind b)
      :para (let [[kept] (cut-seq (:inlines b) left (partial cut-inline hard?))]
              (when (seq kept) (assoc b :inlines kept)))
      :section (let [heading (if hard? 0 (text-length (:heading b)))]
                 (when (< heading left)
                   (assoc b :blocks (first (cut-seq (:blocks b) (- left heading) cut)))))
      :block-quote (let [[kept] (cut-seq (:blocks b) left cut)]
                     (when (seq kept) (assoc b :blocks kept)))
      :list (let [[kept] (cut-seq (:items b) left
                                  (fn [item n]
                                    (let [[blocks] (cut-seq item n cut)]
                                      (when (seq blocks) blocks))))]
              (when (seq kept) (assoc b :items kept)))
      (when (and hard? (<= (text-length b) hard?)) b))))

;; --- previews: the ellipsis, footnotes ---------------------------------------------

(def ^:private breaks #{:space :soft-break :line-break})

(defn- trim-end
  "`inlines` without the spaces, breaks and trailing whitespace at their
  end, inside wrapping inlines too."
  [inlines]
  (loop [v (vec inlines)]
    (if (empty? v)
      v
      (let [x (peek v)
            t (kind x)]
        (cond
          (contains? breaks t) (recur (pop v))
          (= :str t) (let [text (trimr* (str (:text x)))]
                       (if (= "" text) (recur (pop v)) (conj (pop v) (assoc x :text text))))
          (contains? inline-containers t) (let [inner (trim-end (:inlines x))]
                                            (if (empty? inner)
                                              (recur (pop v))
                                              (conj (pop v) (assoc x :inlines inner))))
          :else v)))))

(defn- inlines-with-ellipsis
  [inlines]
  (let [v (trim-end inlines)
        x (peek v)]
    (if (= :str (kind x))
      (conj (pop v) (update x :text str ellipsis))
      (conj v {:t :str :text ellipsis}))))

(defn- with-ellipsis
  "`blocks`, a cut passage, with an ellipsis where the cut fell: at the
  end of the last paragraph (or heading) kept, after its trailing spaces
  are dropped; in a paragraph of its own after a block kept whole."
  [blocks]
  (let [v (vec blocks)
        b (peek v)
        alone {:t :para :inlines [{:t :str :text ellipsis}]}]
    (case (kind b)
      nil (conj v alone)
      :para (conj (pop v) (update b :inlines inlines-with-ellipsis))
      :section (conj (pop v) (if (seq (:blocks b))
                               (update b :blocks with-ellipsis)
                               (update b :heading inlines-with-ellipsis)))
      :block-quote (conj (pop v) (update b :blocks with-ellipsis))
      :list (conj (pop v) (update b :items #(conj (pop %) (with-ellipsis (peek %)))))
      (conj v alone))))

(defn- cut-notes
  "`[blocks cut?]`: the footnotes and sticky notes of `blocks`, in document
  order, kept while their text fits in `budget` characters; the first
  that does not is cut (`cut-block`, with an ellipsis) and every one
  after it dropped, marker and all."
  [blocks budget]
  (let [left (volatile! budget)
        cut? (volatile! false)
        walk (fn walk [x]
               (cond
                 (vector? x) (into [] (keep walk) x)
                 (not (map? x)) x
                 (contains? #{:note :sticky-note} (kind x))
                 (let [n (count (node-text (:blocks x)))]
                   (if (<= n @left)
                     (do (vswap! left - n) x)
                     (let [[kept] (when (pos? @left)
                                    (cut-seq (:blocks x) @left (partial cut-block false)))]
                       (vreset! left 0)
                       (vreset! cut? true)
                       (when (seq kept) (assoc x :blocks (with-ellipsis kept))))))
                 :else
                 (reduce (fn [m k]
                           (let [v (get m k)]
                             (if (or (vector? v) (map? v)) (assoc m k (walk v)) m)))
                         x (field-order x))))]
    [(walk (vec blocks)) @cut?]))

(defn- cut-passage*
  "`{:blocks :cut?}`, before `cut-passage` bounds its size: `blocks` (a passage's) whole when their text
  (`node-text`, footnotes included) is at most `limit` characters, else
  cut to about `limit`, with `:cut?` true. The cut is made on the AST,
  before anything is emitted, so the emitter writes every element it
  opens:

  - The body is counted first, without what its footnotes hold: the
    leading blocks that fit, then what fits of the next (`cut-block`),
    ending before the first word that does not fit, with an ellipsis
    (`with-ellipsis`). Its text is a prefix of the passage's body text,
    at most `limit` characters and the ellipsis.
  - Never an empty preview: when that keeps nothing but headings -- the
    first thing is a table, a directive, code or maths longer than
    `limit`, or a single word is -- the cut is made again keeping that
    block whole, or cutting inside that word (`hard?`). A block is kept
    whole only up to `hard-cap` characters (TASK-291); a larger one is
    left out, and the preview is the headings kept and the ellipsis, or
    the ellipsis alone.
  - Then the footnotes of what was kept, in order, with what is left of
    `limit` (`cut-notes`). When one is cut or dropped, the body ends with
    an ellipsis too: a cut preview always shows one at its end."
  [blocks limit]
  (let [blocks (vec blocks)]
    (if (<= (count (node-text blocks)) limit)
      {:blocks blocks :cut? false}
      (let [[kept body-cut?]
            (if (<= (text-length blocks) limit)
              [blocks false]
              (let [[soft] (cut-seq blocks limit (partial cut-block false))]
                (if (str/blank? (content-text soft))
                  [(first (cut-seq blocks limit (partial cut-block (hard-cap limit)))) true]
                  [soft true])))
            [kept notes-cut?] (cut-notes kept (max 0 (- limit (text-length kept))))]
        {:blocks (if (or body-cut? notes-cut?) (with-ellipsis kept) kept) :cut? true}))))

(def ^:private node-overhead
  "What `serialized-size` counts for each node: about what a node adds to
  `previews.json` besides its strings (the keys, the braces, the commas)."
  32)

(defn- serialized-size
  "About how many characters `x`, an AST node or a vector of them, takes
  in `previews.json`: every string in it (text, `:url`, `:lang`,
  attribute values, `:tex`, ...) plus `node-overhead` for each node, so a
  table of many short cells counts as what it is."
  [x]
  (cond
    (string? x) (count x)
    (map? x) (reduce-kv (fn [n k v] (+ n (serialized-size k) (serialized-size v))) node-overhead x)
    (coll? x) (reduce (fn [n v] (+ n (serialized-size v))) 0 x)
    :else 0))

(defn- size-budget
  "How large (`serialized-size`) a preview of `limit` characters of text may
  be: 32 times `limit`, but at least 64 KB (so a tiny `limit`, as the
  tests use, does not shrink it below one node's worth), 64 KB at the
  default. A preview is `limit`
  characters of text and the markup of what holds them -- links, images,
  a table's rows -- and every preview of the corpus is far below this (the
  largest is a few KB); what reaches it is carrying something else, a
  300 KB URL or attribute, a table of thousands of rows."
  [limit]
  (max 65536 (* 32 limit)))

(defn cut-passage
  "`{:blocks :cut?}`: `blocks` (a passage's) cut to about `limit`
  characters of text (`cut-passage*`), and then bounded (TASK-291): when
  what that keeps is over `size-budget` (`serialized-size`) -- whatever
  the text, a block cut whole or not, a huge URL, attribute or heading,
  many blocks, footnotes -- the preview is the short form, flagged cut: the
  first heading and an ellipsis when the heading (with its attributes) is
  at most an eighth of `size-budget` in size, else a paragraph holding
  only the ellipsis."
  [blocks limit]
  (let [{kept :blocks :as result} (cut-passage* blocks limit)]
    (if (<= (serialized-size kept) (size-budget limit))
      result
      (let [heading (first (filter #(= :section (kind %)) (take 1 (vec blocks))))
            brief (when heading (with-ellipsis [(assoc heading :blocks [])]))]
        {:blocks (if (and brief (<= (serialized-size brief) (quot (size-budget limit) 8)))
                   brief
                   (with-ellipsis []))
         :cut? true}))))

(defn preview-image-src
  "`(fn [src] src')` for the images of a preview of the book whose page
  copied the images `copied` (paths relative to the page's folder, as
  `image-collector` answers them): a URL as it is, a relative src as the
  page rewrote it only when the page copied that file -- so relative to
  `previews.json`'s folder it finds the very copy the page shows -- and
  nil (the alt text is shown) for every other, as on the page: one the
  page refused, a missing file, one whose copy would be another book's."
  [copied]
  (let [copied (set copied)]
    (fn [src]
      (let [{:keys [as-is path suffix]} (image-src src)]
        (cond
          as-is src
          (and path (contains? copied (str/join "/" path)))
          (str (str/join "/" (map #(url/encode % false) path)) suffix)
          :else nil)))))

(defn page-body
  "The content of `<body>` of a page the html emitter wrote: from the end
  of its first `<body>` to its last `</body>`, both the emitter's own."
  [^String html]
  (let [open (str/index-of html "<body>")
        close (str/last-index-of html "</body>")]
    (if (and open close (<= (+ open 6) close))
      (subs html (+ open 6) close)
      "")))

;; --- previews: which passages ------------------------------------------------------

(defn preview-passages
  "The passages of the book `id` (its current canonical BookId) other
  books link to in the graph `g`: every RESOLVED `:link` edge into it
  (`graph/edges-to`) from another book, one entry per distinct passage
  key (`graph/edge-passage-key`, the passage as written without
  whitespace; nil for the whole book) -- `{:key :passage}`, `:passage`
  the parsed passage of the first such edge by what was written --
  ordered as `--backlinks` orders its groups (`backlinks/passage-order`).
  Quotes need no preview: they copy the passage into the quoting page."
  [g id]
  (->> (graph/edges-to g id)
       (filter #(and (= :link (:kind %)) (= :resolved (:status %))
                     (not= id (:id (:from %)))))
       (group-by graph/edge-passage-key)
       (map (fn [[k edges]]
              (let [edge (first (sort-by (comp :written :to) edges))
                    parsed (book-ref/parse-book-ref (:written (:to edge)))]
                {:key k
                 :passage (or (:passage (:to edge))
                              (when-not (book-ref/malformed? parsed) (:passage parsed)))})))
       (sort (fn [a b] (backlinks/passage-order (:key a) (:key b))))
       vec))

(def previews-version
  "The `version` of `previews.json`; changes only when a field is
  removed or changes meaning."
  1)

(defn previews-json
  "The `previews.json` of the book `id`: `previews` as the CLI's renderer
  answered them, `[{:key :ref :title :url :html :cut? :math?}]`. `math`
  (TASK-275) says the html holds maths, so the reader app loads MathJax
  for it only then."
  ^bytes [id previews]
  (json-bytes (array-map "version" previews-version
                         "book" (book-ref/format-book-id id)
                         "limit" preview-limit
                         "previews" (mapv (fn [{passage-key :key ref-text :ref :keys [title url html cut? math?]}]
                                            (array-map "passage" passage-key
                                                       "ref" ref-text
                                                       "title" title
                                                       "url" url
                                                       "html" html
                                                       "cut" (boolean cut?)
                                                       "math" (boolean math?)))
                                          previews))))

;; --- the catalog ------------------------------------------------------------------

(def catalog-page-size
  "How many books one catalog page holds (doc-12, \"Scale\" 5)."
  500)

(def catalog-version
  "The `version` of the catalog files; changes only when a field is
  removed or changes meaning."
  1)

(defn collation-key
  "`s` as the catalog sorts it, the same on every machine (no locale):
  decomposed (NFD) with its combining marks removed and lower-cased under
  `Locale/ROOT`, so `Êxodo` sorts with `exodo` and `Zacarias` after
  `ana`; then `s` itself, so two texts equal under the first never tie."
  [s]
  (let [s (str s)]
    [(-> (java.text.Normalizer/normalize s java.text.Normalizer$Form/NFD)
         (str/replace #"\p{M}+" "")
         (.toLowerCase java.util.Locale/ROOT))
     s]))

(defn library-colour
  "The cover colour of the library `lib-name`, `#rrggbb`: a hue taken from
  the SHA-256 of its name, at a fixed saturation and lightness that keep
  white title text readable, so each library has its own colour and it
  never depends on what else is in the site."
  [lib-name]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")
        bs (.digest md (.getBytes (str lib-name) "UTF-8"))
        hue (mod (+ (* 256 (bit-and (aget bs 0) 0xff)) (bit-and (aget bs 1) 0xff)) 360)
        s 0.45
        l 0.38
        c (* (- 1 (Math/abs (double (- (* 2 l) 1)))) s)
        h' (/ hue 60.0)
        x (* c (- 1 (Math/abs (double (- (mod h' 2.0) 1)))))
        [r g b] (case (int (Math/floor h'))
                  0 [c x 0] 1 [x c 0] 2 [0 c x] 3 [0 x c] 4 [x 0 c] [c 0 x])
        m (- l (/ c 2))
        byte-of (fn [v] (Math/round (double (* 255 (+ v m)))))]
    (format "#%02x%02x%02x" (byte-of r) (byte-of g) (byte-of b))))

(defn- catalog-pages
  "The catalog pages of `books` in the libraries `lib-names` (see
  `catalog-files`), without `index.json`: `{:files {rel bytes} :counts
  {library-name {:books n :pages n}}}`, a count for every library."
  [books lib-names]
  (let [by-lib (group-by (comp :library :id) books)
        ;; a name that could not be a folder could not hold a page either
        libs (sort-by collation-key (filter library/valid-name? (distinct (concat lib-names (keys by-lib)))))
        ;; TASK-279: once per library and per shelf, not per book
        colour (memoize library-colour)
        shelf-key (memoize collation-key)
        entry (fn [{:keys [id title authors page]}]
                (array-map "id" (book-ref/format-book-id id)
                           "title" title
                           "authors" (vec authors)
                           "library" (:library id)
                           "shelf" (str/join "/" (:shelf id))
                           "path" page
                           "colour" (colour (:library id))))
        pages (into {}
                    (map (fn [lib]
                           [lib (->> (get by-lib lib)
                                     (sort-by-key (fn [{:keys [id title]}]
                                                    [(shelf-key (str/join "/" (:shelf id)))
                                                     (collation-key title)
                                                     (book-ref/format-book-id id)]))
                                     (map entry)
                                     (partition-all catalog-page-size)
                                     (mapv vec))]))
                    libs)]
    {:counts (into {} (map (fn [lib] [lib {:books (count (get by-lib lib)) :pages (count (get pages lib))}])) libs)
     :files (into {}
                  (for [lib libs
                        :let [lib-pages (get pages lib)]
                        [i books] (map-indexed vector lib-pages)]
                    [(str "catalog/" lib "/" (inc i) ".json")
                     (json-bytes (array-map "version" catalog-version
                                            "library" lib
                                            "page" (inc i)
                                            "pages" (count lib-pages)
                                            "books" books))]))}))

(defn catalog
  "The catalog (doc-12, \"Scale\" 5) of `books` -- `{:id BookId :title
  text :authors [text] :page path}` for every book whose page this build
  wrote -- and the libraries `lib-names`, as `{:files {relative-path
  bytes} :counts {library-name {:books n :pages n}}}` (see
  `catalog-files` for the files). `others`, `{library-name {:books n
  :pages n}}` (TASK-297), are libraries this build leaves as they are, as
  their last build counted them: `index.json` lists them with those
  counts, and no page of theirs is made or counted."
  [books lib-names others]
  (let [made (catalog-pages (remove #(contains? others (:library (:id %))) books)
                            (remove #(contains? others %) lib-names))
        counts (merge (:counts made) others)]
    {:counts (:counts made)
     :files (into (sorted-map)
                  (assoc (:files made)
                         "catalog/index.json"
                         (json-bytes (array-map "version" catalog-version
                                                "page-size" catalog-page-size
                                                "libraries" (mapv (fn [lib]
                                                                    (array-map "name" lib
                                                                               "colour" (library-colour lib)
                                                                               "books" (:books (get counts lib))
                                                                               "pages" (:pages (get counts lib))))
                                                                  (sort-by collation-key (keys counts)))))))}))

(defn catalog-files
  "`{relative-path bytes}` of the catalog (doc-12, \"Scale\" 5) for
  `books` -- `{:id BookId :title text :authors [text] :page path}` for
  every book whose page this build wrote -- and the libraries
  `lib-names` (each listed, with 0 books when none has a page):

  - `catalog/index.json`: `{\"version\" \"page-size\" \"libraries\" [{\"name\"
    \"colour\" \"books\" \"pages\"}]}`, the libraries sorted by name.
  - `catalog/<library>/<n>.json`, n from 1: `{\"version\" \"library\" \"page\"
    \"pages\" \"books\" [{\"id\" \"title\" \"authors\" \"library\" \"shelf\"
    \"path\" \"colour\"}]}`, at most `catalog-page-size` books a page, sorted
    by shelf (`/`-joined, \"\" for none), then title, then id, each by
    `collation-key`. `path` is the page relative to the site.

  Pure; the same books give the same bytes, whatever their order."
  [books lib-names]
  (:files (catalog books lib-names {})))

(defn- read-catalog-json
  "The catalog file `rel` below the site `root`, parsed; throws when it is
  missing or not JSON."
  [^File root rel]
  (let [f (io/file root rel)
        folders (reductions (fn [^Path p ^String s] (.resolve p s)) (.toPath root) (str/split rel #"/"))]
    ;; a regular file reached through real folders only: nothing below the
    ;; site is followed
    (when-not (and (every? #(Files/isDirectory ^Path % no-follow) (rest (butlast folders)))
                   (Files/isRegularFile ^Path (last folders) no-follow))
      (throw (ex-info (str rel " is missing, or is a symbolic link or below one") {:type ::bad-catalog})))
    (try (json/read-str (slurp f :encoding "UTF-8"))
         (catch Exception _
           (throw (ex-info (str rel " is not JSON") {:type ::bad-catalog}))))))

(defn patch-catalog!
  "TASK-329 (doc-13 section 4): put the books `books` -- `{:id BookId
  :title text :authors [text] :page path}`, as `catalog` takes them --
  into the catalog of the site `root` (a real path, `acquire-lock!`'s
  `:dir`), replacing the entry of an id the catalog has, adding the rest.
  The affected libraries' pages and `catalog/index.json` are read from
  the site, their books gathered and the files made by `catalog` -- the
  one `--build-site` uses -- so the bytes are the ones a build of the
  same books gives; libraries not named are listed in the index with
  the counts it already has. Writes (`write-if-changed!`, a rename) only
  the files whose bytes differ. Answers the relative paths written.
  Throws `ex-info` `::bad-catalog` when the index or a page it lists is
  missing or not a catalog, writing nothing."
  [^File root books]
  (let [index (read-catalog-json root "catalog/index.json")
        libs (get index "libraries")
        count? #(and (integer? %) (not (neg? %)))
        _ (when-not (and (map? index) (sequential? libs) (every? #(and (map? %) (string? (get % "name"))) libs))
            (throw (ex-info "catalog/index.json is not a catalog index" {:type ::bad-catalog})))
        _ (when-not (every? #(and (count? (get % "pages")) (count? (get % "books"))) libs)
            (throw (ex-info "catalog/index.json: a library's \"books\" or \"pages\" is not a non-negative integer"
                            {:type ::bad-catalog})))
        affected (into #{} (map (comp :library :id)) books)
        in-index (into {} (map (fn [l] [(get l "name") l])) libs)
        existing (for [lib affected
                       :let [{n "pages"} (get in-index lib)]
                       i (range 1 (inc (or n 0)))
                       :let [rel (str "catalog/" lib "/" i ".json")
                             page (read-catalog-json root rel)]
                       e (get page "books")]
                   (let [parsed (book-ref/parse-book-ref (get e "id"))]
                     (when-not (:id parsed)
                       (throw (ex-info (str rel " holds an entry with no book ID") {:type ::bad-catalog})))
                     {:id (:id parsed) :title (get e "title") :authors (get e "authors") :page (get e "path")}))
        new-ids (into #{} (map (comp book-ref/format-book-id :id)) books)
        merged (concat (remove #(contains? new-ids (book-ref/format-book-id (:id %))) existing) books)
        others (into {} (comp (remove (fn [[lib _]] (contains? affected lib)))
                              (map (fn [[lib l]] [lib {:books (get l "books") :pages (get l "pages")}])))
                     in-index)
        made (:files (catalog merged (vec affected) others))]
    (into [] (comp (filter (fn [[rel content]] (write-if-changed! root rel content false)))
                   (map key))
          made)))

(defn- in-pool
  "`(f x)` for each of `xs` on `threads` threads, answered in the order of
  `xs`. An exception `f` throws is rethrown here."
  [f xs threads]
  (let [^ExecutorService pool (Executors/newFixedThreadPool (max 1 (int threads)))]
    (try
      (let [futures (mapv (fn [x] (.submit pool ^Callable (fn [] (f x)))) xs)]
        (mapv (fn [^Future fut]
                (try (.get fut)
                     (catch ExecutionException e (throw (or (.getCause e) e)))))
              futures))
      (finally (.shutdownNow pool)))))

(defn- throwable-message [^Throwable t] (or (ex-message t) (.getName (class t))))

;; --- the images manifest ------------------------------------------------------------
;;
;; Which files under books/ are copies a build made, kept apart from the
;; saved state so that a state of another format, or none (a build killed
;; before it saved one), never turns a copy into a "hand-placed" file the
;; sweep keeps forever. `.haselnuss/images` lists the copies of the last
;; finished build, one site path a line; `.haselnuss/images.pending` gets
;; each copy's path appended BEFORE the copy is made, so a killed build's
;; copies are listed too. Both are read leniently -- missing, unreadable or
;; a symbolic link reads as empty -- and every path read is checked again
;; before it is deleted (`remove-stale-images!`). Not guarded, by design:
;; a pending list replaced by a symbolic link makes each append fail (that
;; page is `emit-failed`), and one replaced by a FIFO blocks the append
;; until something reads it -- both need write access to `.haselnuss/`,
;; which already lets anyone rewrite the state and the manifest.

(def ^:private manifest-rel ".haselnuss/images")
(def ^:private pending-rel ".haselnuss/images.pending")
(def ^:private pending-lock (Object.))

(defn- manifest-lines [^File f]
  (try
    (if (Files/isRegularFile (.toPath f) no-follow)
      (with-open [r (io/reader f :encoding "UTF-8")]
        (into [] (remove str/blank?) (line-seq r)))
      [])
    (catch Exception _ [])))

(defn read-image-manifest
  "The site paths the site `dir`'s images manifest and pending list name
  (see above), as a set; empty when they are missing or unreadable."
  [dir]
  (into (set (manifest-lines (io/file dir manifest-rel)))
        (manifest-lines (io/file dir pending-rel))))

(defn images-pending?
  "True when the site `dir` has an images pending list (or anything else
  under its name): a build stopped while it copied images (TASK-296)."
  [dir]
  (not (Files/notExists (.toPath (io/file dir pending-rel)) no-follow)))

(defn- note-pending-image!
  "Append `site-path` to the pending list before it is copied."
  [^File dir site-path]
  (locking pending-lock
    (Files/write (.toPath (io/file dir pending-rel))
                 (.getBytes ^String (str site-path "\n") "UTF-8")
                 ^"[Ljava.nio.file.OpenOption;"
                 (into-array OpenOption [StandardOpenOption/CREATE StandardOpenOption/APPEND
                                         StandardOpenOption/WRITE LinkOption/NOFOLLOW_LINKS]))))

(defn- write-image-manifest!
  "Make `site-paths` the images manifest and drop the pending list."
  [^File dir site-paths]
  (write-under! dir manifest-rel (.getBytes ^String (apply str (map #(str % "\n") (sort site-paths))) "UTF-8"))
  (Files/deleteIfExists (.toPath (io/file dir pending-rel))))

(defn record-image-copies!
  "Add the site paths `site-paths` (images copied beside a page) to the
  site `dir`'s images manifest, keeping every path it and the pending
  list already name, and drop the pending list (its paths are in the
  manifest now): copies a run that does not sweep made (`--build-doc`,
  TASK-328), which the sweep of a later build removes once no page
  copies them any more."
  [^File dir site-paths]
  (write-image-manifest! dir (into (read-image-manifest dir) site-paths)))

(defn page-dir-of
  "The folder of the page `page` (`page-path`), ending in `/`."
  [page]
  (subs page 0 (inc (str/last-index-of page "/"))))

(defn page-site
  "`image-collector`'s `site` for the page `page` (`page-path`):
  `{:page-dir :page-dir?}`, `page-dirs` being the page folders of every
  book that has a page path, this one's included."
  [page page-dirs]
  (let [page-dir (page-dir-of page)]
    {:page-dir page-dir
     :page-dir? (fn [d] (and (not= d page-dir) (contains? page-dirs d)))}))

(defn write-page!
  "Write the page `page` (`page-path`) of the site `dir` from what a
  page's `:emit` answered, `{:html :images :image-dir}`: each image is
  noted in the images pending list, then streamed below the page's
  folder (`copy-image!`), and the page (`with-reader-app`) is written
  last, so a page never shows an image that is not there yet. Answers
  the copies, `{site-path {:dir :path :digest}}`. Throws when a write
  fails. `write-site!` and `--build-doc` (TASK-328) write pages here."
  [^File dir page {:keys [html images image-dir]}]
  (let [page-dir (page-dir-of page)
        copied (into (sorted-map)
                     (map (fn [{:keys [path source]}]
                            (let [site-path (str page-dir path)
                                  digest (volatile! nil)]
                              (note-pending-image! dir site-path)
                              (write-under! dir site-path #(vreset! digest (copy-image! source %)) true)
                              [site-path {:dir (some-> image-dir str) :path path :digest @digest}])))
                     images)]
    (write-under! dir page (.getBytes ^String (with-reader-app html page) "UTF-8") true)
    copied))

(defn- emit-page!
  "Emit and write the page of `entry` (a `site-books` entry with a book):
  `{:id :page :diagnostics :images :authors :refused-images}` once
  written -- the emit's own warnings, the images copied beside the page,
  `{site-path {:dir :path :digest}}`, the authors it answered, for the
  catalog, and the `:refused-images` `:emit` answered (TASK-272), plus
  `:transient? true` when `:emit` answered it (TASK-299) -- or
  `{:id :failed message}`. `emit` is called with
  the book and `{:page-dir :page-dir?}` (`image-collector`'s `site`;
  `page-dirs` is every book's page folder). The images it answers
  (`image-collector`'s `:images`, with the book's folder as `:image-dir`)
  are each noted in the pending list, then streamed below the page's
  folder (`copy-image!`) before the page is written, so a page never
  shows an image that is not there yet."
  [dir emit page-dirs {:keys [id book]}]
  (let [page (page-path id)]
    (if (nil? page)
      {:id id :failed (str "book " (book-ref/format-book-id id) " has no valid site path")}
      (try
        (let [{:keys [diagnostics authors refused-images transient?] :as emitted}
              (emit book (page-site page page-dirs))
              copied (write-page! dir page emitted)]
          (cond-> {:id id :page page :diagnostics (vec diagnostics) :images copied
                   :authors (vec (filter string? authors))
                   :refused-images (vec refused-images)}
            transient? (assoc :transient? true)))
        (catch Exception e
          {:id id :failed (throwable-message e)})
        (catch StackOverflowError _
          {:id id :failed "the document is nested too deeply to emit"})))))

(def ^:private temp-re
  "A temporary file `write-atomically!` leaves when it is killed while
  writing a page, a book's data file or a copied image:
  `.index.html.<digits>.tmp`, `.backlinks.json.<digits>.tmp`,
  `.previews.json.<digits>.tmp` or `.<name>.<image
  extension>.<digits>.tmp`, and nothing else."
  (re-pattern (str "^\\.(?:index\\.html|backlinks\\.json|previews\\.json|[^/]+\\.(?i:"
                   (str/join "|" (sort image-extensions))
                   "))\\.\\d+\\.tmp$")))

(defn- fs-reason
  "Why a sweep's file system call failed (`e`, an IOException or an
  UncheckedIOException), in a few words (TASK-310)."
  [^Throwable e]
  (let [e (if (instance? java.io.UncheckedIOException e) (or (.getCause e) e) e)]
    (if (instance? java.nio.file.AccessDeniedException e)
      "permission denied"
      (throwable-message e))))

(defn- remove-stale-images!
  "Remove each of `stale` (site paths `books/...` of images an earlier
  build copied and this one did not) that is still a regular file, no
  folder on its way being a symbolic link; a path that could not have
  been copied (`saved-image-path?`) is left alone."
  [^File dir stale problem!]
  (doseq [rel stale
          :let [segments (str/split (str rel) #"/")]
          :when (and (= "books" (first segments)) (< 2 (count segments))
                     (saved-image-path? (rest segments)))]
    (let [folders (reductions (fn [^Path p ^String s] (.resolve p s)) (.toPath dir) (butlast segments))
          target (.resolve ^Path (last folders) ^String (last segments))]
      (when (and (every? #(Files/isDirectory ^Path % no-follow) (rest folders))
                 (Files/isRegularFile target no-follow))
        ;; TASK-310: one that cannot be removed is reported, not thrown
        (try (Files/deleteIfExists target)
             (catch IOException e
               (problem! target (str "cannot be removed: " (fs-reason e)))))))))

(def ^:private data-names
  "The file names of a book's data files, beside its page."
  #{"backlinks.json" "previews.json"})

(defn sweep-catalog!
  "Remove from the site `dir`'s `catalog/` every catalog page --
  `catalog/<library>/<n>.json`, `n` digits -- and every temporary file an
  interrupted write of one left (`.<n>.json.<digits>.tmp`, also beside
  `index.json`) that is not in `written` (site paths), then every
  library folder left empty. Nothing else is touched: a file of any
  other name stays, and no symbolic link is followed or removed.

  Answers (TASK-310) the `[site-path what]` of each folder it could not
  list and each file or folder it could not remove, as `sweep!`'s
  `:problems` are; it goes on with the rest instead of throwing."
  [dir written]
  (let [root (io/file dir "catalog")
        problems (volatile! [])
        attempt (fn [^String path what f]
                  (try (f)
                       (catch Exception e
                         (if (or (instance? IOException e) (instance? java.io.UncheckedIOException e))
                           (vswap! problems conj [path (str what (fs-reason e))])
                           (throw e)))))
        children (fn [^File f path]
                   ;; `.listFiles` answers nil for a folder it cannot list
                   (if-let [fs (.listFiles f)]
                     (sort fs)
                     (do (vswap! problems conj [path "cannot be listed: permission denied"]) [])))]
    (when (real-dir? root)
      (doseq [^File f (children root "catalog")
              :let [p (.toPath f)
                    rel (str "catalog/" (.getName f))]]
        (cond
          (and (Files/isRegularFile p no-follow) (re-matches #"\.(?:index|\d+)\.json\.\d+\.tmp" (.getName f)))
          (attempt rel "cannot be removed: " #(Files/deleteIfExists p))

          (real-dir? f)
          (do (doseq [^File g (children f rel)
                      :let [q (.toPath g)
                            file-name (.getName g)]
                      :when (and (Files/isRegularFile q no-follow)
                                 (or (and (re-matches #"\d+\.json" file-name)
                                          (not (contains? written (str rel "/" file-name))))
                                     (re-matches #"\.\d+\.json\.\d+\.tmp" file-name)))]
                (attempt (str rel "/" file-name) "cannot be removed: " #(Files/deleteIfExists q)))
              (attempt rel "cannot be removed: "
                       #(with-open [s (Files/list p)]
                          (when-not (.hasNext (.iterator s))
                            (try (Files/deleteIfExists p)
                                 (catch java.nio.file.DirectoryNotEmptyException _ nil))))))

          :else nil)))
    @problems))

(def ^:private root-temp-re
  "A temporary file `write-atomically!` leaves at the top of the site when
  it is killed while writing a reader-app file: `.<app file>.<digits>.tmp`."
  (re-pattern (str "\\.(?:" (str/join "|" (map #(str/replace % "." "\\.") app-files))
                   ")\\.\\d+\\.tmp")))

(defn sweep-root!
  "Remove from the top of the site `dir` every temporary file an
  interrupted write of a reader-app file left (`root-temp-re`), and
  nothing else: a regular file, no link followed (TASK-272 review)."
  [dir]
  (doseq [^File f (.listFiles (io/file dir))
          :let [p (.toPath f)]
          :when (and (re-matches root-temp-re (.getName f)) (Files/isRegularFile p no-follow))]
    (Files/deleteIfExists p)))

(defn- page-id
  "The canonical id a stale page's relative path `books/<lib>/<...>/<slug>/
  index.html` was written for."
  [rel]
  (let [[lib & more] (butlast (rest (str/split rel #"/")))]
    (str lib ":" (str/join "/" more))))

(defn sweep!
  "Remove from the site `dir`'s `books/` every `index.html` not in
  `written` (a set of `page-path`s), every temporary file an interrupted
  write left, and then every folder left empty. Answers the canonical ids
  of the pages removed, sorted. Symbolic links are neither followed nor
  removed.

  Copied images (TASK-270): `:previous-images` are the site paths the
  images manifest names (`read-image-manifest`: earlier builds' copies,
  a killed one's included), and `:images` those this build copied; each
  previous one not copied again is removed. An image file no manifest
  names -- one put there by hand -- is kept, like any other file that
  is not a page.

  A book's data files (TASK-271): with `:data`, the site paths of the
  `backlinks.json` and `previews.json` this build wrote, every file of
  those two names under `books/` not among them is removed, as a page
  is (both names belong to the build, like `index.html`). Without
  `:data` they are left alone.

  `:folders`, a volatile, is set to the path of every folder left under
  the site's `books/` (`books/` itself first, then in the walk's order;
  empty without one) -- what the record of an unchanged site stamps,
  so that anything a sweep would remove shows (TASK-296).

  `:problems` (TASK-310), a volatile holding a vector: each folder the
  sweep could not list, and each file or folder it could not remove (a
  permission, say), is conjoined to it as `[site-path what]` instead of
  aborting the sweep, which goes on with the rest. A folder it could not
  list is still among `:folders`, as one that holds something unknown.

  `:libraries` (TASK-297), library names: only `books/<library>/` of each
  is looked into (`books/` itself is still listed first among
  `:folders`), for a build that leaves every other library as it is and
  knows nothing was added there since its last sweep. Without it, all of
  `books/`."
  ([dir written] (sweep! dir written {}))
  ([dir written {:keys [images previous-images data folders libraries problems]}]
   (let [root (io/file dir "books")]
     (if-not (real-dir? root)
       (do (when folders (vreset! folders [])) [])
       (let [current (into {} (map (fn [p] [(str/lower-case p) p])) images)
             base-path (.toPath (io/file dir))
             ;; TASK-310: what the sweep could not do is noted, never thrown
             problem! (fn [^Path p what]
                        (when problems
                          (vswap! problems conj
                                  [(str/replace (str (.relativize base-path p)) File/separator "/") what])))
             same-file? (fn [a b]
                          (try (Files/isSameFile (.resolve base-path ^String a) (.resolve base-path ^String b))
                               (catch IOException _ false)))
             ;; a stale path that is a current copy's but for case (A.png
             ;; renamed a.png) IS that copy on a case-insensitive file
             ;; system: it is kept when it is the same file
             _ (remove-stale-images! (io/file dir)
                                     (remove (fn [p]
                                               (let [p (str p)
                                                     twin (get current (str/lower-case p))]
                                                 (and twin (same-file? p twin))))
                                             (remove (set images) previous-images))
                                     problem!)
             base (.toPath (io/file dir))
             ;; every entry with its type, from the walk's own attributes
             ;; (no link followed), rather than a stat per entry: a site of
             ;; 50,000 books holds over 200,000 (TASK-272)
             entries (let [acc (java.util.ArrayList.)
                           walk! (fn [^Path from]
                                   (Files/walkFileTree
                                    from
                                    (reify java.nio.file.FileVisitor
                                      (preVisitDirectory [_ p _]
                                        (.add acc [p :dir])
                                        java.nio.file.FileVisitResult/CONTINUE)
                                      (visitFile [_ p attrs]
                                        (let [^java.nio.file.attribute.BasicFileAttributes a attrs]
                                          (.add acc [p (if (.isRegularFile a) :file :other)]))
                                        java.nio.file.FileVisitResult/CONTINUE)
                                      ;; TASK-310: a folder that cannot be listed is
                                      ;; reported, kept in the walk (its stamp is
                                      ;; recorded) as a folder holding something
                                      ;; unknown, and the walk goes on
                                      (visitFileFailed [_ p e]
                                        (.add acc [p :unlisted])
                                        (problem! p (str "cannot be listed: " (fs-reason e)))
                                        java.nio.file.FileVisitResult/CONTINUE)
                                      (postVisitDirectory [_ _ _]
                                        java.nio.file.FileVisitResult/CONTINUE))))]
                       (if (nil? libraries)
                         (walk! (.toPath root))
                         ;; TASK-297: books/ itself, then each library's
                         ;; folder below it that is a folder (no link
                         ;; followed), as the whole walk would meet them
                         (do (.add acc [(.toPath root) :dir])
                             (doseq [lib (sort libraries)
                                     :let [p (.toPath (io/file root ^String lib))]
                                     :when (Files/isDirectory p no-follow)]
                               (walk! p))))
                       (vec acc))
             root-path (.toPath root)
             n (count entries)
             ;; the walk is depth-first and lists a folder before what it
             ;; holds, so a folder is empty exactly when the next entry is
             ;; not inside it: no folder is listed again to learn that
             empty-dir? (fn [i]
                          (let [[p entry-kind] (nth entries i)]
                            (and (= :dir entry-kind)
                                 (or (= (inc i) n)
                                     (not= p (.getParent ^Path (first (nth entries (inc i)))))))))
             removed (vec (for [[^Path p entry-kind] entries
                                :when (= :file entry-kind)
                                :let [file-name (str (.getFileName p))]
                                :when (or (= "index.html" file-name) (contains? data-names file-name)
                                          (re-matches temp-re file-name))
                                :let [rel (str/replace (str (.relativize base p)) File/separator "/")]
                                :when (or (and (= "index.html" file-name) (not (contains? written rel)))
                                          (and data (contains? data-names file-name)
                                               (not (contains? data rel)))
                                          (re-matches temp-re file-name))]
                            (try (Files/deleteIfExists p)
                                 [p (when (= "index.html" file-name) (page-id rel))]
                                 (catch IOException e
                                   (problem! p (str "cannot be removed: " (fs-reason e)))
                                   nil))))
             removed (filterv some? removed)
             ;; the folders that may be empty now: those that were, and
             ;; those a removed file was in
             candidates (into (into #{} (comp (filter empty-dir?) (map #(first (nth entries %)))) (range n))
                              (map #(.getParent ^Path (first %)))
                              removed)]
        ;; deepest first, so a folder emptied by its children goes too
         (let [gone (loop [queue (into (sorted-set-by (fn [^Path a ^Path b]
                                                        (let [c (compare (.getNameCount b) (.getNameCount a))]
                                                          (if (zero? c) (compare a b) c))))
                                       (filter #(and (.startsWith ^Path % root-path) (not= % root-path)))
                                       candidates)
                           gone #{}]
                      (if-let [^Path p (first queue)]
                        (let [emptied? (and (Files/isDirectory p no-follow)
                                            ;; TASK-310: a folder that cannot be listed
                                            ;; or removed is reported and kept
                                            (try (and (with-open [s (Files/list p)] (not (.hasNext (.iterator s))))
                                                      ;; something written into it meanwhile keeps it
                                                      (try (Files/deleteIfExists p)
                                                           (catch java.nio.file.DirectoryNotEmptyException _ false)))
                                                 (catch Exception e
                                                   (if (or (instance? IOException e) (instance? java.io.UncheckedIOException e))
                                                     (do (problem! p (str "cannot be removed: " (fs-reason e)))
                                                         false)
                                                     (throw e)))))
                              parent (.getParent p)]
                          (recur (cond-> (disj queue p)
                                   (and emptied? (not= parent root-path)) (conj parent))
                                 (cond-> gone emptied? (conj p))))
                        gone))]
           (when folders
             (vreset! folders (into [] (comp (filter #(contains? #{:dir :unlisted} (second %)))
                                             (map first)
                                             (remove gone)
                                             (map str))
                                    entries))))
         (vec (sort (keep peek removed))))))))

;; --- the build ---------------------------------------------------------------------

(defn image-conflicts
  "One `:image-conflict` error per book, of `written` (`emit-page!`
  results), that copied an image to a site path another book copied to
  as well, sorted by path and book: which copy is on disk would depend
  on which thread ran last. `image-collector`'s page-folder check makes
  it impossible -- two pages' folders are either one book's or nested --
  so this is the guard that says so if that ever stops being true.
  Paths are compared lower-cased (and shown so): `X/fig.png` and
  `x/fig.png` are one file on a case-insensitive file system."
  [written]
  (let [claims (reduce (fn [m {:keys [id images]}]
                         (reduce #(update %1 (str/lower-case %2) (fnil conj #{}) id) m (keys images)))
                       {} written)]
    (vec (for [[site-path ids] (sort-by key claims)
               :when (< 1 (count ids))
               id (sort-by book-ref/format-book-id ids)]
           {:type :image-conflict
            :level :error
            :message (str "the image " site-path " is copied by "
                          (str/join " and " (sort (map book-ref/format-book-id ids)))
                          "; the site shows one of them")
            :file nil :line nil :column nil :ref nil :canonical nil
            :book (book-ref/format-book-id id)
            :library (:library id)}))))

(defn- real-path
  "The path `f` names with every symbolic link resolved, or, when that
  cannot be done (it does not exist), as an absolute normalized path."
  ^Path [f]
  (let [p (.toPath (io/file f))]
    (try (.toRealPath p (make-array LinkOption 0))
         (catch IOException _ (.normalize (.toAbsolutePath p))))))

(defn library-roots
  "`{library-name Path}` of the libraries of the graph `g` that have a root
  folder (`haselnuss.library.graph/indexed-libraries`), each as its real
  path (symbolic links resolved: a library whose `path` is a link, or
  lies below one, holds files whose real paths are below the real root,
  TASK-291); empty for a graph without library records."
  [g]
  (if (satisfies? graph/LibraryIndex g)
    (into {} (keep (fn [[lib-name {:keys [root]}]]
                     (when (string? root)
                       [lib-name (real-path root)])))
          (graph/indexed-libraries g))
    {}))

(defn site-position-file
  "How a site file shows the source file `file` of a reference made in
  the library `from-lib`: `<library>/<path below its root>`, the
  `roots` (`library-roots`) tried with the citing book's library first,
  then the deepest root holding it; nil when no library root holds it.
  Never an absolute path: a site is served to other devices, and the
  layout of the machine that built it is not theirs to read.

  The roots are real paths, so the file is looked for by its real path
  first, then as written. So a link to a file shows the place the file
  really is when a root holds that (a link in library A to a file in
  library B shows `B/...`), else the link's own place when a root holds
  that; a link that leads out of every root never shows where it leads."
  [roots from-lib file]
  (when (string? file)
    (let [candidates [(real-path file) (.normalize (.toAbsolutePath (.toPath (io/file file))))]]
      (some (fn [^Path p]
              (let [hits (filter (fn [[_ ^Path root]] (and (.startsWith p root) (not= p root))) roots)
                    [lib ^Path root] (or (first (filter #(= from-lib (key %)) hits))
                                         (first (sort-by (fn [[n ^Path r]] [(- (.getNameCount r)) n]) hits)))]
                (when root
                  (str lib "/" (str/join "/" (map str (.relativize root p)))))))
            candidates))))

(defn site-backlinks-report
  "The `--backlinks` report of the book `id` in the graph `g` as a site
  writes it to `backlinks.json`: each position's `:file` as
  `site-position-file` shows it (nil when no library root holds it)."
  [g id]
  (let [roots (library-roots g)
        fix (fn [entry]
              (if (:position entry)
                (update-in entry [:position :file]
                           #(site-position-file roots (some-> (get-in entry [:from :id]) book-ref/parse-book-ref
                                                              :id :library)
                                                %))
                entry))]
    (-> (backlinks/backlinks-report g id {:base-dir nil})
        (update :edges #(mapv fix %))
        (update :self #(mapv fix %)))))

(defn- write-data!
  "Write the data files of the book whose page `written` (an `emit-page!`
  result) is: `backlinks.json`, its `--backlinks` JSON report in the graph
  `graph` (`site-backlinks-report`), and `previews.json`, what `(previews
  id passages copied)` renders of its `preview-passages`, `copied` being
  the images the page copied (paths relative to its folder) -- each only
  when its bytes changed (`write-if-changed!`). A preview the renderer
  answers as `{:key :failed message}` is left out and reported. Answers
  `{:id :data {site-path written?} :preview-failures [{:key :failed}]}`,
  or `{:id :data-failed message}` when either file could not be made or
  written.

  `which` (TASK-272) names the files to make, `:backlinks` and/or
  `:previews`; `:preview-failures` is answered only when `previews.json`
  was among them. `:replace-files?` writes them even when their bytes
  match (`write-if-changed!`)."
  [dir {:keys [graph previews replace-files?]} {:keys [id page images]} which]
  (let [page-dir (page-dir-of page)
        backlinks-rel (str page-dir "backlinks.json")
        previews-rel (str page-dir "previews.json")
        previews? (contains? which :previews)]
    (try
      (let [report (when (contains? which :backlinks) (site-backlinks-report graph id))
            rendered (when previews? (previews id (preview-passages graph id) (keep :path (vals images))))
            {failures true shown false} (group-by #(contains? % :failed) rendered)]
        (cond-> {:id id
                 :data (cond-> {}
                         report
                         (assoc backlinks-rel (write-if-changed! dir backlinks-rel
                                                                 (.getBytes ^String (backlinks/render-json report)
                                                                            "UTF-8")
                                                                 replace-files?))
                         previews?
                         (assoc previews-rel (write-if-changed! dir previews-rel (previews-json id (vec shown))
                                                                replace-files?)))}
          previews? (assoc :preview-failures (vec failures))))
      (catch Exception e
        {:id id :data-failed (throwable-message e)})
      (catch StackOverflowError _
        {:id id :data-failed "a passage is nested too deeply to preview"}))))

;; --- one book's data files, without the graph (--build-doc, TASK-328) -------------

(defn- read-site-json
  "The JSON data file `rel` below the site `dir`, as `clojure.data.json`
  reads it (string keys), when it is a regular file reached through real
  folders only (`site-file?`); nil when it is not, or does not parse."
  [^File dir rel]
  (when (site-file? dir rel)
    (try (json/read-str (slurp (io/file dir rel) :encoding "UTF-8"))
         (catch Exception _ nil)
         (catch StackOverflowError _ nil))))

(defn- whole-book-id
  "The canonical id text of the book reference `s`, when it is one with
  no passage; nil for anything else."
  [s]
  (when (string? s)
    (let [parsed (book-ref/parse-book-ref s)]
      (when-not (or (book-ref/malformed? parsed) (:passage parsed))
        (book-ref/format-book-id (:id parsed))))))

(defn referring-books
  "The canonical ids (text) of the books the `backlinks.json` beside the
  page `page` (`page-path`) of the site `dir` lists as referring to its
  book -- each edge's `from`; its self-references are not edges --
  distinct and sorted; empty when there is no such file or it cannot be
  read. The books `--build-doc` names `stale` (TASK-328): finding them
  costs no scan."
  [^File dir page]
  (let [report (read-site-json dir (str (page-dir-of page) "backlinks.json"))
        edges (when (map? report) (get report "edges"))]
    (->> (when (sequential? edges) edges)
         (keep (fn [e] (when (map? e) (let [from (get e "from")] (when (map? from) (get from "id"))))))
         (keep whole-book-id)
         distinct
         sort
         vec)))

(defn listed-previews
  "The passages the `previews.json` beside the page `page` of the book
  `id` in the site `dir` lists, in its order, as `preview-passages`
  answers them -- `[{:key :passage}]`, `:passage` parsed from the key
  (nil for the whole book) -- each key once; a key that does not parse as
  a passage of the book is left out. Empty when there is no such file or
  it cannot be read."
  [^File dir id page]
  (let [data (read-site-json dir (str (page-dir-of page) "previews.json"))
        entries (when (map? data) (get data "previews"))
        id-text (book-ref/format-book-id id)]
    (->> (when (sequential? entries) entries)
         (keep (fn [e]
                 (when (map? e)
                   (let [k (get e "passage")]
                     (cond
                       (nil? k) {:key nil :passage nil}
                       (string? k) (let [parsed (book-ref/parse-book-ref (str id-text "#" k))]
                                     (when-not (or (book-ref/malformed? parsed) (nil? (:passage parsed)))
                                       {:key k :passage (:passage parsed)}))
                       :else nil)))))
         (reduce (fn [[seen out] p]
                   (if (contains? seen (:key p)) [seen out] [(conj seen (:key p)) (conj out p)]))
                 [#{} []])
         second)))

(defn empty-backlinks-json
  "The `backlinks.json` of the book `id` with no reference into it: what
  `--build-doc` writes beside a book's first page (TASK-328); the next
  `--build-site` writes the real one."
  ^bytes [id]
  (.getBytes ^String (backlinks/render-json {:version backlinks/report-version
                                             :book (book-ref/format-book-id id)
                                             :passage nil
                                             :edges []
                                             :self []
                                             :summary {:references 0 :books 0 :self 0}})
             "UTF-8"))

(defn rewrite-book-data!
  "The data files `--build-doc` (TASK-328; doc-13, section 4 step 3)
  writes beside the page `page` of the book `id` it has just written to
  the site `dir`, each through `write-if-changed!`: `backlinks.json` is
  kept as it is when it is there (who refers to a book does not change
  when the book's own file does), else an empty report; `previews.json`
  is rendered again for the passages the existing one lists
  (`listed-previews`), read before it is replaced, by `(previews id
  passages copied)` -- `copied` the images the page copied, relative to
  its folder -- leaving out each passage it answers `{:key :failed}` for
  (one that no longer resolves); none when there is no such file.
  Answers the previews left out, `[{:key :failed}]`. Throws when a write
  fails."
  [^File dir id page previews copied]
  (let [page-dir (page-dir-of page)
        backlinks-rel (str page-dir "backlinks.json")
        previews-rel (str page-dir "previews.json")
        passages (listed-previews dir id page)
        rendered (if (seq passages) (previews id passages copied) [])
        {failures true shown false} (group-by #(contains? % :failed) rendered)]
    (when-not (site-file? dir backlinks-rel)
      (write-if-changed! dir backlinks-rel (empty-backlinks-json id)))
    (write-if-changed! dir previews-rel (previews-json id (vec shown)))
    (vec failures)))

(defn- preview-failure-items
  "One `:preview-failed` warning per preview of the data result `result`
  that could not be built, placed at the book's `entry-file`."
  [{:keys [id preview-failures]} entry-file base-dir]
  (for [{k :key :keys [failed]} preview-failures]
    {:type :preview-failed
     :level :warning
     :message (str "the preview of " (book-ref/format-book-id id) (when k (str "#" k))
                   " was not written to previews.json: " failed)
     :file (when entry-file (text/display-path (str entry-file) base-dir))
     :line nil
     :column nil
     :ref nil
     :canonical nil
     :book (book-ref/format-book-id id)
     :library (:library id)}))

(defn- sweep-problem-items
  "The `--check`-style error items of what `sweep!` could not do
  (`:problems`, `[site-path what]`; TASK-310), sorted by path; the
  library is the folder's below `books/`, when it is in one."
  [problems]
  (vec (for [[path what] (reduce (fn [m [p w]] (if (contains? m p) m (assoc m p w)))
                                 (sorted-map) problems)
             :let [[_ lib] (re-matches #"books/([^/]+)/.*" path)]]
         {:type :sweep-failed
          :level :error
          :message (str "the site folder or file " path " " what "; stale output there was not swept")
          :file path :line nil :column nil :ref nil :canonical nil
          :book nil
          :library lib})))

(defn- failure-item
  "The `--check`-style item of a book whose page, or (`:data-failed`) whose
  data files, could not be written."
  [{:keys [id failed data-failed]} entry-file base-dir]
  {:type (if data-failed :data-failed :emit-failed)
   :level :error
   :message (if data-failed
              (str "the data files of " (book-ref/format-book-id id) " (backlinks.json, previews.json)"
                   " were not written: " data-failed)
              (str "the page of " (book-ref/format-book-id id) " was not written: " failed))
   :file (when entry-file (text/display-path (str entry-file) base-dir))
   :line nil
   :column nil
   :ref nil
   :canonical nil
   :book (book-ref/format-book-id id)
   :library (:library id)})

(defn book-lines
  "One message line per book, `verb  id  (reason)`, the ids padded to one
  column."
  [rows]
  (let [width (reduce max 0 (map (comp count second) rows))]
    (mapv (fn [[verb id reason]]
            (str (format "%-8s " verb) id (apply str (repeat (- width (count id)) " "))
                 "  (" reason ")"))
          rows)))

(defn- problem-ids
  "The books the warnings and errors among `items` are in, as id texts:
  the books the items name (`:book`), and the books a library diagnostic
  names (`:books`, a `duplicate-slug`'s) among `library-diagnostics`."
  [items library-diagnostics]
  (let [problem? #(contains? #{:warning :error} (:level %))]
    (into (set (keep :book (filter problem? items)))
          (comp (filter #(not= :info (:level %)))
                (mapcat :books)
                (filter string?))
          library-diagnostics)))

(defn- id-library
  "The library of the canonical id text `id` (`library:shelf/slug`)."
  [^String id]
  (let [i (.indexOf id ":")] (if (neg? i) id (subs id 0 i))))

(def ^:private no-summary
  {:rebuilt 0 :total 0 :up-to-date 0 :not-built 0 :errors 0 :warnings 0 :books 0})

(defn- library-summaries
  "`{library summary}`: the summary counts (`summary-line`'s) of each
  library on its own -- the books rebuilt (`written`), kept, not built
  (`failed`, `duplicates`), of `entries`, and the items of each
  (`:library`); the problem books (`problem-ids`) of each, by the library
  their id names, each id counted as often as books share it -- so the
  summary of the whole site is their sum (TASK-297). Every library of
  `lib-names` has one."
  [{:keys [lib-names entries written kept-pages failed duplicates items library-diagnostics]}]
  (let [fmt (comp book-ref/format-book-id :id)
        of-id (comp :library :id)
        sizes (into {} (map (fn [e] [(fmt e) (or (:duplicates e) 1)])) entries)
        add (fn [m lib k n] (update-in m [lib k] (fnil + 0) n))
        counted (as-> (zipmap lib-names (repeat no-summary)) m
                  (reduce #(add %1 (of-id %2) :rebuilt 1) m written)
                  (reduce #(add %1 (of-id %2) :total (or (:duplicates %2) 1)) m entries)
                  (reduce #(add %1 (of-id %2) :up-to-date 1) m kept-pages)
                  (reduce #(add %1 (of-id %2) :not-built 1) m failed)
                  (reduce #(add %1 (of-id %2) :not-built (:duplicates %2)) m duplicates)
                  (reduce (fn [m {:keys [level library]}]
                            (case level
                              :error (add m library :errors 1)
                              :warning (add m library :warnings 1)
                              m))
                          m items)
                  (reduce #(add %1 (id-library %2) :books (get sizes %2 1))
                          m (problem-ids items library-diagnostics)))]
    (update-vals counted #(merge no-summary %))))

(defn- sum-summaries
  "The summary of the whole site from `library-summaries`'."
  [summaries]
  (reduce #(merge-with + %1 %2) no-summary summaries))

(defn problems-text
  "What a summary counts as outstanding: `no warnings`, or `[E errors,
  ]W warnings in B books`."
  [{:keys [errors warnings books]}]
  (if (zero? (+ errors warnings))
    "no warnings"
    (str (when (pos? errors) (str (text/plural errors "error") ", "))
         (text/plural warnings "warning")
         ;; TASK-310: a problem of the site's own folders is in no book
         (when (pos? books) (str " in " (text/plural books "book"))))))

(defn summary-line
  "The summary: `N of M books rebuilt, K up to date[, U not built];
  [E errors, ]W warnings in B books`."
  [{:keys [rebuilt total up-to-date not-built] :as summary}]
  (str rebuilt " of " (text/plural total "book") " rebuilt, " up-to-date " up to date"
       (when (pos? not-built) (str ", " not-built " not built"))
       "; "
       (problems-text summary)))

(def earlier-note
  "What a line printed again from an earlier build ends with (TASK-308)."
  " (unchanged since the last build)")

(defn- carried-shown
  "TASK-308: the items to print of a build that rebuilt only some books:
  `fresh` (this run's, as they are) and every other of `items` once more,
  its message ending in `earlier-note`, so a warning in a book this run
  did not touch is printed beside the count that includes it and a reader
  can tell it was not found now. Sorted as `--check` sorts."
  [items fresh]
  (let [fresh-set (set fresh)]
    (check/sort-items
     (concat fresh
             (for [it items :when (not (contains? fresh-set it))]
               (update it :message str earlier-note))))))

(defn full-plan
  "The plan of a full build (`write-site!`'s `:plan`) of the `site-books`
  `entries`: every buildable book emitted, `reason` given for each, none
  kept."
  [entries reason]
  {:full? true
   :rebuild (into {} (comp (filter :book) (map (fn [e] [(:id e) reason]))) entries)
   :kept {}
   :data {}})

(defn write-site!
  "Write the site `dir` (already preflighted and locked): the reader app's
  files, a page per book (`site-books` of `:directory` and `:library-names`)
  through `:emit` -- `(fn [Book site] {:html :diagnostics :images
  :image-dir :authors})`, `site` being `image-collector`'s, the diagnostics those
  only emitting finds, the images those to copy beside the page from the
  folder `:image-dir` (`image-collector`), throwing when the book cannot
  be emitted -- on `:threads` threads, then the full-build `sweep!`,
  which also removes the copies the images manifest names and no page
  copied this time, and last the new manifest. A site path two books
  copied to (which `image-collector`'s page-folder check should make
  impossible) is an `:image-conflict` error on each of them
  (`image-conflicts`). `dir` is the real path `acquire-lock!`
  answered. `:report` is the scan's `--check` report
  (`haselnuss.library.graph.check/check-report`) and
  `:library-diagnostics` every library record's diagnostics. Answers
  `{:lines [...] :items [...] :summary {...} :pages {BookId {:page
  :diagnostics :images :authors}} :data {site-path written?}}`: the
  message lines; the report's items, each page's emit diagnostics, one
  `:emit-failed` error per book whose page was not written and one
  `:data-failed` error per book whose data files were not, sorted as
  `--check` sorts; the summary counts; the pages written with their
  emit diagnostics, copied images and authors (`emit-page!`); and every
  data file, true when this build wrote it.

  The data files (TASK-271): given `:graph` (the scan's reference graph)
  and `:previews` -- `(fn [BookId passages copied-images] [{:key :ref
  :title :url :html :cut?} or {:key :failed}])`, rendering
  `preview-passages` -- each written page gets its `backlinks.json`
  (`site-backlinks-report`) and `previews.json` (`write-data!`, on the
  pool), a preview that could not be built being a `:preview-failed`
  warning, and the site its catalog (`catalog-files`) of the pages
  written, title from the book's entry and authors from `:emit`'s
  `:authors`. Each is written only when its bytes changed. The sweep
  then removes data files and catalog pages this build did not write
  (`sweep!`'s `:data`, `sweep-catalog!`). A book left unbuilt -- a
  shared id, a failed page -- has no page, so no catalog entry and no
  data files.

  Incremental builds (TASK-272): `:plan`, `(fn [entries] plan)` over the
  `site-books` entries, answers `{:full? :rebuild {BookId reason} :kept
  {BookId saved} :data {BookId #{:backlinks :previews}}}` (default
  `full-plan` with `:reason`). Only `:rebuild`'s books are emitted (each
  line gives its reason); a `:kept` book keeps its page, and its saved
  entry (`:books` below) stands for it everywhere else -- in the catalog,
  the sweep's pages, images and data files, and the items: its emit
  diagnostics and its preview failures are counted again, so a warning
  in a page this build did not touch is not forgotten. `:data` names the
  data files of kept books to make again. The answer adds `:shown`, the
  items to print -- all of them on a full build, else those of the books
  emitted this run and this run's failures as they are, and every other
  item again with `earlier-note` after its message (TASK-308) -- and `:books`, `{BookId
  {:path :emit-diagnostics :images :refused-images :authors
  :preview-failures :data-failed? :transient?}}` for every page, which the state saves for the next
  run (`:transient?`, TASK-299: its build met a failure of that run, so
  the next one rebuilds it; a preview failure may carry one too). `:hook`, a test seam, is called as `(hook stage x)` after each
  page (`:page`, its result) and data result (`:data`), and before the
  catalog (`:catalog`) and the sweep (`:sweep`): one that throws stands
  for a build killed there.

  Per library (TASK-297): `:libraries` in the answer is `{library-name
  {:summary :items :skipped :conflicts :counts :catalog :copies}}`, what
  the build leaves of each library of `:library-names` once it is
  settled -- its share of the summary of a no-change run
  (`library-summaries`: the site's is their sum), its items, its skipped
  books' `[id reason]`, its image conflicts, its catalog counts
  (`catalog`), its catalog files' digests and its copied images. Given
  `:frozen`, `{library-name that}` saved by an earlier build of libraries
  this one leaves exactly as they are (none of them in `:library-names`),
  the build lists, emits and sweeps only `:library-names` (`books/<lib>/`
  of each, `sweep!`'s `:libraries`) and takes everything else of the
  frozen libraries from there: their copies stay in the manifest, their
  catalog files stay and `index.json` counts them, and the summary,
  items, lines and `:libraries` hold them as well.

  `:replace-files?` (TASK-290; `--full`, or a saved state that is
  missing, unreadable or of another format, but not one that only
  another Haselnuss version saved): the
  app files, data files and catalog are written even when their bytes
  match, as new files, so they take the mode the umask gives now, as the
  pages and images a full build writes do. No existing file's mode is
  ever changed in place, and a file the build does not produce is not
  touched."
  [dir {:keys [directory library-names emit threads report library-diagnostics base-dir reason
               graph previews plan hook frozen replace-files?]
        :or {reason "full build"}}]
  (copy-app-files! dir replace-files?)
  (let [threads (or threads (.availableProcessors (Runtime/getRuntime)))
        hook (or hook (fn [_ _] nil))
        entries (site-books directory library-names)
        buildable (filterv :book entries)
        duplicates (filterv :duplicates entries)
        ;; every book's page folder, the unbuilt ones' too: a folder no
        ;; image of another book may be copied into
        page-dirs (into #{} (keep #(some-> (page-path (:id %)) page-dir-of)) entries)
        ;; TASK-272: what to emit, and the pages kept as they are
        {:keys [rebuild kept full?] data-plan :data} (if plan (plan entries) (full-plan entries reason))
        results (in-pool (fn [entry]
                           (let [r (emit-page! dir emit page-dirs entry)]
                             (hook :page r)
                             r))
                         (filterv #(contains? rebuild (:id %)) buildable)
                         threads)
        written (filterv :page results)
        failed (filterv :failed results)
        kept-pages (->> kept
                        (map (fn [[id saved]] (assoc saved :id id :page (:path saved))))
                        (sort-by-key (comp book-ref/format-book-id :id)))
        pages (into written kept-pages)
        data? (and graph previews)
        ;; TASK-271: each written page's backlinks.json and previews.json,
        ;; and TASK-272 those of a kept page the plan names
        data-results (if data?
                       (in-pool (fn [[page which]]
                                  (let [r (write-data! dir {:graph graph :previews previews
                                                            :replace-files? replace-files?}
                                                       page which)]
                                    (hook :data r)
                                    r))
                                (concat (map #(vector % #{:backlinks :previews}) written)
                                        (keep (fn [page] (some->> (get data-plan (:id page)) (vector page)))
                                              kept-pages))
                                threads)
                       [])
        data-by-id (into {} (map (juxt :id identity)) data-results)
        data-failed (filterv :data-failed data-results)
        ;; a kept page's preview failures stand until its previews are
        ;; rendered again (data files that failed show none, as in a
        ;; full build)
        rendered? (fn [id] (contains? (get data-by-id id) :preview-failures))
        carried? (fn [id] (not (or (rendered? id) (:data-failed (get data-by-id id)))))
        preview-failures (into {} (map (fn [{:keys [id] :as page}]
                                         [id (vec (cond (rendered? id) (:preview-failures (get data-by-id id))
                                                        (carried? id) (:preview-failures page)
                                                        :else []))]))
                               pages)
        titles (into {} (map (fn [{:keys [id book]}]
                               [id (node-text (:title (library/book-entry book)))]))
                     buildable)
        _ (hook :catalog nil)
        {catalog-bytes :files catalog-counts :counts}
        (catalog (map (fn [{:keys [id page authors]}]
                        {:id id :title (titles id) :authors authors :page page})
                      pages)
                 library-names
                 (update-vals frozen :counts))
        catalog-written (into (sorted-map)
                              (map (fn [[rel content]] [rel (write-if-changed! dir rel content replace-files?)]))
                              catalog-bytes)
        own-copies (set (mapcat (comp keys :images) pages))
        copies (into own-copies (mapcat :copies) (vals frozen))
        _ (hook :sweep nil)
        folders (volatile! nil)
        sweep-problems (volatile! [])
        removed (sweep! dir (set (map :page pages))
                        (cond-> {:folders folders
                                 :problems sweep-problems
                                 :images copies
                                 :previous-images (read-image-manifest dir)
                                 :data (when data?
                                         (into #{}
                                               (comp (remove #(:data-failed (get data-by-id (:id %))))
                                                     (mapcat (fn [{:keys [page]}]
                                                               (map #(str (page-dir-of page) %) data-names))))
                                               pages))}
                          frozen (assoc :libraries library-names)))
        _ (vswap! sweep-problems into
                  (sweep-catalog! dir (into (set (keys catalog-bytes)) (mapcat (comp keys :catalog)) (vals frozen))))
        _ (sweep-root! dir)
        _ (write-image-manifest! dir copies)
        conflict-items (image-conflicts pages)
        sweep-items (sweep-problem-items @sweep-problems)
        entry-file (into {} (map (fn [{:keys [id book]}]
                                   [id (when (fs/filesystem-book? book) (fs/entry-file book))]))
                         buildable)
        fresh-failures (into (mapv #(failure-item % (entry-file (:id %)) base-dir) (concat failed data-failed))
                             (mapcat #(preview-failure-items % (entry-file (:id %)) base-dir))
                             (filter :preview-failures data-results))
        carried-failures (mapcat (fn [{:keys [id]}]
                                   (when (carried? id)
                                     (preview-failure-items {:id id :preview-failures (preview-failures id)}
                                                            (entry-file id) base-dir)))
                                 pages)
        ;; what only emitting a page finds, placed at the book's file when
        ;; the diagnostic names none; a kept page's as its build saved them
        emit-items (for [{:keys [id diagnostics emit-diagnostics]} pages
                         d (or diagnostics emit-diagnostics)]
                     (check/book-item d {:book (book-ref/format-book-id id) :lib-name (:library id)
                                         :file (some-> (entry-file id) str) :base-dir base-dir}))
        own-items (concat (:diagnostics report) emit-items fresh-failures carried-failures conflict-items
                          sweep-items)
        frozen-libs (vals frozen)
        items (check/sort-items (concat own-items (mapcat :items frozen-libs)))
        all-conflicts (concat conflict-items (mapcat :conflicts frozen-libs))
        fmt (comp book-ref/format-book-id :id)
        emitted (into #{} (map fmt) results)
        ;; doc-12: the diagnostics of the books rebuilt in this run; the
        ;; summary counts them all
        ;; TASK-308: the others are printed again, marked as earlier
        fresh-shown (check/sort-items (concat (filter #(contains? emitted (:book %))
                                                      (concat (:diagnostics report) emit-items))
                                              fresh-failures all-conflicts sweep-items))
        shown (if full? items (carried-shown items fresh-shown))
        skipped-rows (concat (map (fn [r] ["skipped" (fmt r) (str (:duplicates r) " books share this id")])
                                  duplicates)
                             (for [{:keys [skipped]} frozen-libs [id why] skipped] ["skipped" id why]))
        rows (sort-by second
                      (concat (map (fn [r] ["rebuilt" (fmt r) (get rebuild (:id r) reason)]) written)
                              (map (fn [r] ["failed" (fmt r) "its page could not be emitted"]) failed)
                              skipped-rows))
        removed-rows (map (fn [id] ["removed" id "no longer in the libraries"]) removed)
        ;; TASK-297: counted per library, the frozen ones as they were
        ;; left; the site's summary is the sum
        summaries (library-summaries {:lib-names library-names :entries entries :written written
                                      :kept-pages kept-pages :failed failed :duplicates duplicates
                                      :items own-items :library-diagnostics library-diagnostics})
        summary (sum-summaries (concat (vals summaries) (map :summary frozen-libs)))
        ;; TASK-310: a sweep that left something undone is tried again, and
        ;; its items are never kept in the state (found anew every run)
        settled? (and (empty? failed) (empty? data-failed) (empty? sweep-items))
        catalog-digests (into (sorted-map) (map (fn [[rel content]] [rel (digest/bytes-digest content)])) catalog-bytes)
        by-lib (fn [f xs] (group-by f xs))
        own-items-by-lib (by-lib :library own-items)
        conflicts-by-lib (by-lib :library conflict-items)
        skipped-by-lib (by-lib (comp :library :id) duplicates)
        pages-by-lib (by-lib (comp :library :id) pages)
        libraries (when settled?
                    (merge (into {}
                                 (map (fn [[lib s]]
                                        [lib {:summary (assoc s :rebuilt 0 :up-to-date (count (get pages-by-lib lib)))
                                              :items (check/sort-items (get own-items-by-lib lib))
                                              :skipped (mapv (fn [r] [(fmt r) (str (:duplicates r) " books share this id")])
                                                             (sort-by fmt (get skipped-by-lib lib)))
                                              :conflicts (check/sort-items (get conflicts-by-lib lib))
                                              :counts (get catalog-counts lib)
                                              :catalog (into (sorted-map)
                                                             (filter #(str/starts-with? (key %) (str "catalog/" lib "/")))
                                                             catalog-digests)
                                              :copies (into (sorted-set) (mapcat (comp keys :images))
                                                            (get pages-by-lib lib))}]))
                                 summaries)
                           frozen))]
    {:lines (book-lines (concat rows removed-rows))
     :items items
     :shown shown
     :summary summary
     ;; TASK-296: what the next run prints when nothing has changed by
     ;; then -- it keeps every page and redoes no data file, so it shows
     ;; only the skipped books, the image conflicts and the same items --
     ;; when this run left every page and data file written; nil when one
     ;; failed, which the next run tries again
     :settled (when settled?
                {:lines (book-lines (filter #(= "skipped" (first %)) rows))
                 :items items
                 :shown (carried-shown items (check/sort-items all-conflicts))
                 :summary (sum-summaries (map :summary (vals libraries)))})
     ;; TASK-297: each library's share of that, and what a build that
     ;; leaves the library as it is takes as `:frozen`
     :libraries libraries
     ;; TASK-296: site path -> digest of every catalog file, as written
     ;; (and TASK-297: those of the frozen libraries, as they were)
     :catalog (into catalog-digests (mapcat :catalog) frozen-libs)
     ;; TASK-296: every folder left under books/ after the sweep
     :folders @folders
     :pages (into {} (map (fn [{:keys [id page diagnostics images authors]}]
                            [id {:page page :diagnostics diagnostics :images images :authors authors}]))
                  written)
     ;; TASK-272: what the saved state keeps of every page, written or
     ;; kept, for the next run
     :books (into {}
                  (map (fn [{:keys [id page diagnostics emit-diagnostics images authors refused-images transient?]
                             :as p}]
                         (let [data (get data-by-id id)
                               data-failed (if data (:data-failed data) (:data-failed p))]
                           [id (cond-> {:path page
                                        :emit-diagnostics (vec (or diagnostics emit-diagnostics))
                                        ;; TASK-270: site path -> {:dir :path :digest},
                                        ;; for the sweep and `changed-image-books`
                                        :images (or images {})
                                        ;; TASK-272: images refused for what was on
                                        ;; disk, for `changed-image-books`
                                        :refused-images (vec refused-images)
                                        ;; TASK-271: the catalog's authors
                                        :authors (vec authors)
                                        :preview-failures (preview-failures id)}
                                 data-failed (assoc :data-failed data-failed)
                                 ;; TASK-299: built with a failure of its run
                                 ;; (`emit-page!`), which the next one rebuilds
                                 transient? (assoc :transient? true))])))
                  pages)
     ;; TASK-271: site path -> true when this build wrote the file, false
     ;; when it already held these bytes
     :data (into (sorted-map) (concat (mapcat :data data-results) catalog-written))}))

(defn exit-status
  "0 when the build found no warning or error, else 1."
  [{:keys [errors warnings]}]
  (if (pos? (+ errors warnings)) 1 0))

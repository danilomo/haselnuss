(ns haselnuss.library.state
  "The saved build state of a site (doc-12, \"What gets rebuilt\" steps 1
  and 7, \"Scale\" consequence 2; TASK-265): what `--build-site` remembers
  between runs so the next one can be incremental. This namespace is the
  save/load layer only -- deciding what to rebuild is the build's.

  ## What is saved

  `save-state!` writes, under `DIR/.haselnuss/state/` (`state-dir`):

  - the state format version (`format-version`) and the Haselnuss
    version (`haselnuss.version/version`);
  - `:inputs`, a map of input -> digest the caller supplies: the
    directory file and the `--extensions`/`--transform` files
    (`input-digests` takes them), each keyed by its path or by
    `[role position path]` (`[:transform 0 \"/a.clj\"]`), so the order
    of the files and which flag named each are part of what is compared;
  - the reference graph: every library record and every book record,
    `:edges` included (`graph-data`);
  - `:books`, a map of canonical BookId -> map the caller supplies: each
    book's output path, document hash and `:labels` hashes, in whatever
    shape the build wants;
  - `:files`, a map of path -> `{:size :mtime :digest}` (`file-stamp`).
    Take the stamps BEFORE the files are read for the build: a file
    edited after its stamp then differs from it on the next run and is
    rescanned, whereas a stamp taken after the build could pair a new
    stamp with an old reading and hide the edit for good.
  - `:listing`, a map of library name -> that library's listing cache
    (`haselnuss.library.graph.scan/listing-caches`,
    `haselnuss.library.filesystem/listing-cache`; TASK-266): what the
    next run's listing reuses instead of reading every front matter and
    running every generated shelf's script. It is the listing's own
    stat cache, separate from `:files`: its stamps carry no digest, since
    taking one would read the file.

  Diagnostics and the caller's `:inputs`, `:books`, `:files` and
  `:listing` are
  stripped to plain data first (`plain`): a diagnostic can carry
  anything a provider put in it, and an exception, a function or a
  keyword the reader cannot read back would make the whole state
  unloadable -- so such a value is dropped, not written. The rest of a
  book or library record is plain data already: its schema says so.

  ## Format

  EDN, split per library and read in parallel. doc-12 lets the builder
  pick; measured on the 50,000-book synthetic collection (TASK-265
  notes), one EDN file for the whole graph took ~1.7 s just to read on a
  warm JVM, against ~0.4 s for per-library files read in parallel, and
  a whole load (graph rebuilt) takes ~1.8 s in a fresh JVM. A library of
  more than `part-size` books is split into several parts, so one big
  library does not leave the other cores idle:

      state.edn                      the header, see below
      <library>.<generation>.<i>.edn part i of a library: its library
                                     record and its listing cache's
                                     generated shelves (part 0), and a
                                     share of its book records, its
                                     books' `:books` data, the `:files`
                                     stamps of the files its books
                                     claim and its listing cache's files

  The header holds the versions, the `:inputs`, the generation, each
  library's part count, and the stamps of files no book claims (the
  directory file, say). A part a save did not write again keeps the
  generation in its name (TASK-297): the header's `:part-generations`
  names it, `{library [generation ...]}`, for each library that has
  such a part -- one whose content is exactly the one loaded
  (`save-state!`'s `:previous`), or every part of a library the build
  did not load at all (`:carry`; `load-state`'s `:libraries` loads only
  some). A header without it, as every save before TASK-297 wrote,
  names every part by its own generation. A file claimed by books of several libraries
  goes with the first of them by name. Library names are the grammar's
  `[a-z0-9][a-z0-9-]*`, so they are safe file names and never contain
  the `.` that separates the generation; `load-state` checks the
  header's names before it builds a file name from them.

  EDN rather than JSON (`data.json`, the other reader in the dependency
  set): the state holds keywords, sets and BookId map keys, which JSON
  would need a tagging scheme for, and EDN reads them back as they were.
  Java serialisation was not considered: the native image would need a
  serialisation configuration for every class in the state.

  ## Atomicity

  Every file is written to a temporary file in the same folder and then
  renamed over its name (`write-atomically!`), and the header goes LAST.
  The library files carry a fresh generation in their names, so the old
  header still points at the old, untouched set until the new header
  replaces it in one rename: a save interrupted at any point leaves the
  previous state loadable (or no state, if there was none). (A part kept
  as it was is a file both headers name, and the save never writes it.)
  Files the new header does not name, and temporary files an
  interrupted save left, are deleted only after the new header is in
  place -- and only files whose
  names have the shapes a save writes (`<library>.<hex>.<n>.edn`, and
  `.<that or state.edn>.<digits>.tmp`), so a stray file put in the
  folder by hand survives.

  Saves must NOT run concurrently on one site: each save removes the
  other's parts and temporary files as stale, so one of them may throw
  or leave a header that points at deleted parts (which `load-state`
  then reports `:unreadable`). The caller serialises them; `--build-site`
  holds `.haselnuss/lock` (TASK-268) for the whole build.

  A `.haselnuss` or `.haselnuss/state` that is a symbolic link is
  refused: `save-state!` throws `::symlinked-state-dir` before writing or
  deleting anything, and `load-state` answers `:symlinked`. Following it
  would read, write and delete files in a folder the site does not own.

  ## Loading

  `load-state` never throws for a bad state: it answers `{:usable? true
  …}` or `{:usable? false :reason kw :message string}`, where `:reason`
  is `:missing` (no header), `:unreadable` (a file cannot be read or
  parsed, or holds something that is not a state of this format),
  `:format` (another format version), `:version` (another Haselnuss
  version, or this build does not know its own), `:inputs-changed`
  (`:inputs` were given and differ from the saved ones) or `:symlinked`
  (see Atomicity). Every one of them means: full build. Before reading
  any part, the folder is listed once and every part the header names
  must be in it, so a header claiming absurdly many parts is
  `:unreadable` at once.

  No records and no reflection: the native image needs no configuration
  for this namespace."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [haselnuss.library :as library]
            [haselnuss.library.digest :as digest]
            [haselnuss.library.graph :as graph]
            [haselnuss.library.graph.libraries :as libraries]
            [haselnuss.library.graph.registry :as registry]
            [haselnuss.version :as version])
  (:import (java.io File PushbackReader Writer)
           (java.nio.file AtomicMoveNotSupportedException CopyOption Files LinkOption
                          StandardCopyOption)
           (java.nio.file.attribute BasicFileAttributes FileAttribute)
           (java.util UUID)
           (java.util.concurrent ExecutionException TimeUnit)))

(def format-version
  "The version of the state format. Bump it whenever what is saved, or
  how, changes: a state of any other version is not usable. 2: the
  listing cache (`:listing`, TASK-266) joined the parts. 3: an `:inputs`
  key may be `[role position path]` (TASK-268). 4: `--build-site`'s
  `:books` data carries the images each page copied, `:images {site-path
  {:dir :path :digest}}` (TASK-270); a format-3 state lacks them, and a
  build trusting it would miss image edits. 5: each book's `:books` data
  carries its catalog `:authors` (TASK-271); a format-4 state lacks them,
  and a build trusting it would write a catalog without the authors of
  every book it did not rebuild. 6: each book's `:books` data carries its
  `:preview-failures` (and `:data-failed` when its data files could not be
  written), and `:files` holds the file stamps an incremental build
  compares (TASK-272); a format-5 state lacks both, and a build trusting
  it would stop counting the preview warnings of the books it keeps. 7:
  each book's `:books` data carries the images its page refused for what
  was on disk, `:refused-images`, and a stamp of a symbolic link carries
  its `:link` target (TASK-272 review); a format-6 state lacks both, and a
  build trusting it would miss an image that appeared, or a retargeted
  folder link. 8: a book quoting a locator-built passage records the
  references in it as derived edges (`:via`, TASK-287); a format-7 state
  lacks them, and a build trusting it would not rebuild that book when
  the book such a reference names changes. 9: a book whose front matter
  `title` is not text (`!!binary`, a list, a map) is listed under its slug
  with an `:invalid-front-matter` problem (TASK-286); a format-8 state
  holds the title as the `toString` of what YAML read, and a build
  trusting it would keep that title, and its page, for an unchanged
  book. 10: what is saved is the same, but public site files are now
  written with the umask's mode (TASK-290); a format-9 state belongs to
  a site whose files are 0600, and a build trusting it would keep them
  so, where the build that misses it replaces every output with a new
  file. 11: previews are bounded in size and a library reached through a
  symbolic link shows its positions' files in `backlinks.json`
  (TASK-291); a format-10 site keeps the previews and `file: null`
  values the old build wrote for books it does not rebuild."
  11)

(def header-name
  "The name of the header file inside `state-dir`."
  "state.edn")

(defn state-dir
  "The folder holding the saved state of the site in `dir`:
  `dir/.haselnuss/state`."
  ^File [dir]
  (io/file dir ".haselnuss" "state"))

;; --- plain data ----------------------------------------------------------------

(def ^:private dropped
  "What `plain*` answers for a value it drops."
  (Object.))

(defn- edn-readable?
  "True when the keyword, symbol or character `x` reads back from EDN as
  itself."
  [x]
  (= x (try (edn/read-string (pr-str x)) (catch Exception _ ::unreadable))))

(defn- plain*
  [readable? x]
  (let [kept (comp (map #(plain* readable? %)) (remove #(identical? % dropped)))]
    (cond
      (or (nil? x) (boolean? x) (string? x)) x
      (int? x) (long x)
      (or (double? x) (float? x)) (double x)
      (or (keyword? x) (symbol? x) (char? x)) (if (readable? x) x dropped)
      (map? x) (persistent!
                (reduce-kv (fn [m k v]
                             (let [k (plain* readable? k)
                                   v (plain* readable? v)]
                               (if (or (identical? k dropped) (identical? v dropped))
                                 m
                                 (assoc! m k v))))
                           (transient {})
                           x))
      (set? x) (into #{} kept x)
      (sequential? x) (into [] kept x)
      :else dropped)))

(defn plain
  "`x` as plain data that EDN writes and reads back equal: nil, booleans,
  strings, longs (any integer type), doubles (and floats), keywords,
  symbols and characters that read back as themselves, and maps,
  vectors, sets and sequences of those -- a record becomes a map, a
  sequence a vector. Anything else (an exception, a function, a File, a
  ratio …) is dropped: a map entry whose key or value is dropped goes,
  and so does a dropped element of a collection. nil when `x` itself is
  dropped. (A NaN is kept and reads back, but is never `=` to itself;
  a string holding half of a surrogate pair reads back with `?` in its
  place, as UTF-8 writes it.)"
  [x]
  (let [seen (volatile! {})
        readable? (fn [k]
                    (let [r (get @seen k ::none)]
                      (if (identical? r ::none)
                        (let [r (edn-readable? k)] (vswap! seen assoc k r) r)
                        r)))
        result (plain* readable? x)]
    (when-not (identical? result dropped) result)))

;; --- files ---------------------------------------------------------------------

(def ^:private no-options (make-array LinkOption 0))

(defn file-stamp
  "`{:size bytes :mtime nanoseconds :digest hex-sha256}` for the file at
  `path` (followed through symbolic links), or nil when it is not a
  readable regular file. The digest is `haselnuss.library.digest`'s, so
  it compares with the digests the loaders record."
  [path]
  (try
    (let [p (.toPath (io/file path))
          attrs (Files/readAttributes p BasicFileAttributes ^"[Ljava.nio.file.LinkOption;" no-options)]
      (when (.isRegularFile attrs)
        {:size (.size attrs)
         :mtime (.to (.lastModifiedTime attrs) TimeUnit/NANOSECONDS)
         :digest (digest/bytes-digest (Files/readAllBytes p))}))
    (catch Exception _ nil)))

(defn input-key?
  "True when `k` can key `:inputs`: a path string, or `[role position
  path]` -- a keyword, a non-negative integer and a path string."
  [k]
  (or (string? k)
      (and (vector? k) (= 3 (count k))
           (keyword? (nth k 0)) (nat-int? (nth k 1)) (string? (nth k 2)))))

(defn input-digests
  "`{input digest}` for the input files `inputs` (the directory file,
  `--extensions`, `--transform`), each a path or `[role position path]`
  (`input-key?`; the path may be a File): each one's `file-stamp`
  digest, or `\"missing\"` when it cannot be read, keyed by the path, or
  by the vector with its path as a string. Compared whole by
  `load-state`."
  [inputs]
  (into {}
        (map (fn [input]
               (let [[k path] (if (vector? input)
                                (let [[role i p] input] [[role i (str p)] p])
                                [(str input) input])]
                 [k (or (:digest (file-stamp path)) "missing")])))
        inputs))

(defn write-atomically!
  "Write the file `target` through `write!`, a fn of a `java.io.Writer`
  (UTF-8): into a temporary file in `target`'s folder, then renamed over
  `target` -- atomically where the file system allows it -- so a reader
  sees the old file or the new one, never part of one. The temporary
  file is removed when anything fails."
  [^File target write!]
  (let [dir (.getParentFile (.getAbsoluteFile target))
        tmp (Files/createTempFile (.toPath dir) (str "." (.getName target) ".")
                                  ".tmp" (make-array FileAttribute 0))]
    (try
      (with-open [^Writer w (io/writer (.toFile tmp) :encoding "UTF-8")]
        (write! w))
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
        (Files/deleteIfExists tmp)))))

(defn- pr-plain!
  "Write `x` to `w` exactly as `pr` writes it with `write-edn!`'s
  bindings, without `print-method`'s dispatch for the kinds of value a
  state holds -- nil, booleans, strings, longs, doubles, keywords,
  symbols, characters, and hash, array and sorted maps, vectors and sets
  of those -- and through `pr` itself for anything else (TASK-297: `pr`
  took seconds for one library's part at 50,000 books)."
  [^Writer w x]
  (cond
    (nil? x) (.write w "nil")
    (string? x) (let [^String s x
                      n (.length s)]
                  (.write w "\"")
                  (loop [i 0 from 0]
                    (if (= i n)
                      (.write w s (int from) (int (- n from)))
                      (let [c (.charAt s i)
                            e (case c
                                \newline "\\n" \tab "\\t" \return "\\r" \" "\\\"" \\ "\\\\"
                                \formfeed "\\f" \backspace "\\b" nil)]
                        (if e
                          (do (.write w s (int from) (int (- i from)))
                              (.write w ^String e)
                              (recur (inc i) (inc i)))
                          (recur (inc i) from)))))
                  (.write w "\""))
    (keyword? x) (.write w (str x))
    (or (instance? Long x) (instance? Integer x)) (.write w (str x))
    (boolean? x) (.write w (if x "true" "false"))
    (and (map? x) (or (instance? clojure.lang.PersistentArrayMap x) (instance? clojure.lang.PersistentHashMap x)
                      (instance? clojure.lang.PersistentTreeMap x)))
    (do (.write w "{")
        (loop [s (seq x) first? true]
          (when s
            (let [e (first s)]
              (when-not first? (.write w ", "))
              (pr-plain! w (key e))
              (.write w " ")
              (pr-plain! w (val e))
              (recur (next s) false))))
        (.write w "}"))
    (vector? x) (do (.write w "[")
                    (dotimes [i (count x)]
                      (when (pos? i) (.write w " "))
                      (pr-plain! w (nth x i)))
                    (.write w "]"))
    (or (instance? clojure.lang.PersistentHashSet x) (instance? clojure.lang.PersistentTreeSet x))
    (do (.write w "#{")
        (loop [s (seq x) first? true]
          (when s
            (when-not first? (.write w " "))
            (pr-plain! w (first s))
            (recur (next s) false)))
        (.write w "}"))
    :else (binding [*out* w] (pr x))))

(defn- write-edn!
  [^File target value]
  (write-atomically! target
                     (fn [^Writer w]
                       (binding [*out* w
                                 *print-length* nil
                                 *print-level* nil
                                 *print-meta* false
                                 *print-dup* false
                                 *print-readably* true
                                 *print-namespace-maps* false]
                         (pr-plain! w value)))))

(defn- read-edn
  [^File f]
  (with-open [r (PushbackReader. (io/reader f :encoding "UTF-8"))]
    (edn/read {:eof ::eof} r)))

;; --- the graph as data ---------------------------------------------------------

(defn graph-data
  "The graph `g` as plain data: `{:books {BookId book-record}
  :libraries {name library-record}}`, every book record whole (`:id`
  and `:edges` included). Two graphs with equal `graph-data` answer
  every `ReferenceGraph` and `LibraryIndex` read alike; it is what
  `save-state!` saves and what the round trip is checked by."
  [g]
  {:books (into {}
                (map (fn [[id r]] [id (assoc r :id id :edges (vec (graph/edges-from g id)))]))
                (graph/indexed-books g))
   :libraries (if (satisfies? graph/LibraryIndex g) (graph/indexed-libraries g) {})})

(defn- plain-diagnostics [record]
  (update record :diagnostics #(vec (or (plain %) []))))

;; --- save ----------------------------------------------------------------------

(def part-size
  "About how many books one part file holds: a library of more is split
  into several, so a big library is read on several cores too."
  4000)

(defn- part-file ^File [^File dir lib-name generation i]
  (io/file dir (str lib-name "." generation "." i ".edn")))

(defn- part-generation
  "The generation in the name of part `i` of library `lib-name` in the
  `header`: its own under `:part-generations` (TASK-297: a part a save
  kept as it was, or a library it carried), else the header's."
  [header lib-name i]
  (or (get-in header [:part-generations lib-name i]) (:generation header)))

(defn- part-count
  "How many parts library `lib-name` is saved in, given `records-by-lib`."
  [records-by-lib lib-name]
  (max 1 (long (Math/ceil (/ (count (get records-by-lib lib-name)) (double part-size))))))

(defn- part-of
  "Which of `n` parts the entry keyed `k` (a BookId or a path) goes in."
  [n k]
  (mod (hash k) n))

(defn- pmapv
  "`(vec (pmap f xs))`, rethrowing what `f` threw rather than the
  `ExecutionException` that wraps it."
  [f xs]
  (try
    (vec (pmap f xs))
    (catch ExecutionException e
      (throw (or (.getCause e) e)))))

(defn- check-map! [what x pred]
  (when-not (and (map? x) (every? pred x))
    (throw (ex-info (str "save-state!: " what)
                    {:type ::invalid-state-input :what what}))))

(def ^:private part-name-re
  "The name of a part file a save writes: `<library>.<generation>.<i>.edn`."
  #"[a-z0-9][a-z0-9-]*\.[0-9a-f]{1,64}\.[0-9]{1,9}\.edn")

(def ^:private tmp-name-re
  "The name of a temporary file `write-atomically!` creates for a part
  or the header (`Files/createTempFile` puts digits between)."
  (re-pattern (str "\\.(?:" part-name-re "|" (str/replace header-name "." "\\.")
                   ")\\.[0-9]+\\.tmp")))

(defn- own-file-name?
  "True when `n` has the shape of a file a save writes."
  [n]
  (boolean (or (= header-name n) (re-matches part-name-re n) (re-matches tmp-name-re n))))

(defn- symlinked-dir
  "The first of `dir/.haselnuss` and `dir/.haselnuss/state` that is a
  symbolic link, or nil."
  [dir]
  (let [sdir (state-dir dir)]
    (first (filter #(Files/isSymbolicLink (.toPath ^File %))
                   [(.getParentFile sdir) sdir]))))

(defn- remove-stale!
  "Delete every file in `dir` that `names` (a set) does not hold and
  whose name has the shape of one a save writes (`own-file-name?`): an
  old generation's part, or a temporary file an interrupted save left.
  Nothing else in the folder is touched."
  [^File dir names]
  (doseq [^File f (.listFiles dir)
          :let [n (.getName f)]
          :when (and (.isFile f)
                     (not (contains? names n))
                     (own-file-name? n))]
    (.delete f)))

(defn save-state!
  "Save the build state of the site in `dir` under `state-dir`, the
  header last (see the namespace docstring). `state`:

  - `:graph`, the reference graph (any backend; `LibraryIndex` for the
    library records);
  - `:inputs`, `{input digest}` of the build's input files
    (`input-digests`; default {});
  - `:books`, `{BookId map}` the build's per-book data (default {});
  - `:files`, `{path stamp}` (`file-stamp`; default {});
  - `:listing`, `{library-name listing-cache}`
    (`haselnuss.library.graph.scan/listing-caches`; default {}).

  TASK-297, a save that need not write every part:

  - `:previous`, `load-state`'s `:saved-parts` (`{[library i]
    {:generation :part}}`): a part whose content is exactly the one
    loaded is not written again, the header naming the file it was
    loaded from (`:part-generations`);
  - `:carry`, `{library-name [generation ...]}` (`carried-parts` of the
    header loaded): libraries this save does not hold at all -- a build
    that loaded only some libraries -- whose parts stay as they are and
    stay named. Such a library may have nothing in `:graph`, `:books` or
    `:listing` (`::invalid-state-input`);
  - `:other-claimants`, `{path #{library-name ...}}`: libraries outside
    `:graph` whose books claim the path too (a carried library's). A
    file goes with the first of all its claimants by name, as it does in
    a save of the whole graph -- so one whose first claimant is carried
    is not written here at all: that library's part, kept, holds its
    stamp.

  Answers the header written. Throws on an I/O failure; `ex-info`
  `::invalid-state-input` when `:inputs`, `:books`, `:files` or
  `:listing` is not a map of the right keys -- a caller bug; and `ex-info`
  `::symlinked-state-dir`, before writing or deleting anything, when
  `.haselnuss` or `.haselnuss/state` is a symbolic link. Saves must not
  run concurrently on one site (see the namespace docstring)."
  [dir {g :graph :keys [inputs books files listing previous carry other-claimants]
        :or {inputs {} books {} files {} listing {} previous {} carry {} other-claimants {}}}]
  (when-let [link (symlinked-dir dir)]
    (throw (ex-info (str "save-state!: " link " is a symbolic link; refusing to write the state through it")
                    {:type ::symlinked-state-dir :path (str link)})))
  (check-map! ":inputs must map path strings or [role position path] to digest strings" inputs
              (fn [[k v]] (and (input-key? k) (string? v))))
  (check-map! ":books must map BookIds to maps" books
              (fn [[k v]] (and (map? k) (string? (:library k)) (map? v))))
  (check-map! ":files must map path strings to maps" files
              (fn [[k v]] (and (string? k) (map? v))))
  (check-map! ":listing must map library names to listing caches {:files {path map} :shelves {path map}}"
              listing
              (fn [[k v]] (and (library/valid-name? k) (map? v)
                               (map? (:files v {})) (map? (:shelves v {})))))
  (check-map! ":carry must map library names to vectors of generations" carry
              (fn [[k v]] (and (library/valid-name? k) (vector? v) (seq v)
                               (every? #(and (string? %) (re-matches #"[0-9a-f]{1,64}" %)) v))))
  (let [sdir (state-dir dir)
        _ (.mkdirs sdir)
        generation (subs (str/replace (str (UUID/randomUUID)) "-" "") 0 12)
        {book-records :books lib-records :libraries} (graph-data g)
        book-records (update-vals book-records plain-diagnostics)
        lib-records (update-vals lib-records plain-diagnostics)
        books (plain books)
        files (plain files)
        listing (plain listing)
        file-lib (fn [path]
                   (first (sort (into (set (get other-claimants path))
                                      (map :library)
                                      (graph/books-for-file g path)))))
        ;; (a file whose first claimant is carried is in no part written
        ;; here: the carried part keeps it)
        files-by-lib (group-by #(file-lib (key %)) files)
        lib-names (sort (distinct (concat (keys lib-records)
                                          (map :library (keys book-records))
                                          (map :library (keys books))
                                          (keys listing))))
        _ (when-let [both (seq (filter #(contains? carry %) lib-names))]
            (throw (ex-info (str "save-state!: libraries both carried and saved: " (str/join ", " both))
                            {:type ::invalid-state-input :what :carry})))
        records-by-lib (group-by #(:library (key %)) book-records)
        books-by-lib (group-by #(:library (key %)) books)
        counts (into (sorted-map) (map (fn [l] [l (part-count records-by-lib l)])) lib-names)
        split (fn [entries n] (group-by #(part-of n (key %)) entries))
        parts (for [[lib-name n] counts
                    :let [records (split (get records-by-lib lib-name) n)
                          data (split (get books-by-lib lib-name) n)
                          stamps (split (get files-by-lib lib-name) n)
                          lib-listing (get listing lib-name)
                          listed (split (:files lib-listing) n)]
                    i (range n)
                    :let [part (cond-> {:name lib-name
                                        :part i
                                        :books (mapv val (get records i))
                                        :data (into {} (get data i))
                                        :files (into {} (get stamps i))
                                        :listing (when lib-listing
                                                   {:files (into {} (get listed i))
                                                    :shelves (if (zero? i) (or (:shelves lib-listing) {}) {})})}
                                 (and (zero? i) (contains? lib-records lib-name))
                                 (assoc :library (get lib-records lib-name)))
                          ;; TASK-297: a part exactly as loaded keeps its file
                          ;; (the same count of parts splits books the same way)
                          old (get previous [lib-name i])
                          kept (when (and (map? old) (string? (:generation old)) (= part (:part old)))
                                 (:generation old))]]
                {:lib lib-name :i i :generation (or kept generation) :kept? (some? kept)
                 :file (part-file sdir lib-name (or kept generation) i) :part part})
        written (pmapv (fn [{:keys [^File file part kept?]}]
                         (when-not kept? (write-edn! file part))
                         (.getName file))
                       parts)
        own-generations (into {}
                              (keep (fn [[lib-name ps]]
                                      (when (some :kept? ps) [lib-name (mapv :generation ps)])))
                              (group-by :lib parts))
        header (cond-> {:format format-version
                        :haselnuss (version/version)
                        :generation generation
                        :inputs (plain inputs)
                        :parts (into counts (map (fn [[l gens]] [l (count gens)])) carry)
                        :files (into {} (get files-by-lib nil))}
                 (or (seq own-generations) (seq carry))
                 (assoc :part-generations (into (into (sorted-map) own-generations) carry)))
        carried (for [[lib-name gens] carry
                      [i gen] (map-indexed vector gens)]
                  (.getName (part-file sdir lib-name gen i)))]
    (write-edn! (io/file sdir header-name) header)
    (remove-stale! sdir (into (conj (set written) header-name) carried))
    header))

(defn carried-parts
  "`{library-name [generation ...]}`, the file of each part of each of
  `lib-names` that the (valid) `header` names: what `save-state!`'s
  `:carry` takes for libraries a build did not load (TASK-297)."
  [header lib-names]
  (into {}
        (keep (fn [lib-name]
                (when-let [n (get-in header [:parts lib-name])]
                  [lib-name (mapv #(part-generation header lib-name %) (range n))])))
        lib-names))

;; --- load ----------------------------------------------------------------------

(defn- unusable [reason message]
  {:usable? false :reason reason :message message})

(defn- throwable-message [^Throwable t]
  (or (ex-message t) (.getName (class t))))

(defn- read-part
  "`{:part map}` for part `i` of library `lib-name`, named by `header`,
  or `{:error message}`."
  [^File dir header [lib-name i]]
  (let [f (part-file dir lib-name (part-generation header lib-name i) i)]
    (try
      (let [part (read-edn f)]
        (if (and (map? part) (= lib-name (:name part)) (= i (:part part))
                 (vector? (:books part)) (map? (:data part)) (map? (:files part))
                 (contains? part :listing)
                 (or (nil? (:listing part))
                     (and (map? (:listing part)) (map? (:files (:listing part)))
                          (map? (:shelves (:listing part))))))
          {:part part}
          {:error (str (.getName f) " does not hold part " i " of library " (pr-str lib-name))}))
      (catch Exception e {:error (str (.getName f) ": " (throwable-message e))})
      (catch StackOverflowError _ {:error (str (.getName f) " is nested too deeply")}))))

(def ^:private max-parts
  "The most parts a header may give one library: 400 million books at
  `part-size`, far past any collection, but a bound on what a damaged
  header can make the loader consider."
  100000)

(defn- valid-header?
  "True when `h` is a header whose names are safe to build file names
  from: a hex generation, library names from the grammar, and sane part
  counts."
  [h]
  (and (map? h)
       (string? (:generation h)) (re-matches #"[0-9a-f]{1,64}" (:generation h))
       (map? (:inputs h)) (map? (:files h)) (map? (:parts h))
       (every? (fn [[lib-name n]]
                 (and (library/valid-name? lib-name) (pos-int? n) (<= n max-parts)))
               (:parts h))
       ;; TASK-297: a generation of its own for each part of a library
       (or (nil? (:part-generations h))
           (and (map? (:part-generations h))
                (every? (fn [[lib-name gens]]
                          (and (vector? gens) (= (count gens) (get (:parts h) lib-name))
                               (every? #(and (string? %) (re-matches #"[0-9a-f]{1,64}" %)) gens)))
                        (:part-generations h))))))

(defn- build-graph
  [parts {:keys [backend config] reg :registry
          :or {reg registry/default-registry backend registry/default-backend}}]
  (reduce (fn [g {:keys [library books]}]
            (reduce graph/with-book (cond-> g library (graph/with-library library)) books))
          (libraries/library-indexed (registry/new-graph reg backend config))
          parts))

(defn- load-parts
  "The usable state of `header`'s parts `wanted`, read in parallel, or
  `:unreadable`."
  [^File sdir header opts wanted]
  (let [parts (pmapv #(read-part sdir header %) wanted)]
    (if-let [error (some :error parts)]
      (unusable :unreadable (str "the saved state cannot be read: " error))
      (let [parts (map :part parts)]
        (try
          {:usable? true
           :header header
           ;; TASK-297: what `save-state!`'s `:previous` takes
           :saved-parts (into {}
                              (map (fn [[lib-name i :as k] part]
                                     [k {:generation (part-generation header lib-name i) :part part}])
                                   wanted parts))
           :graph (build-graph parts opts)
           :inputs (:inputs header)
           :books (into {} (mapcat :data) parts)
           :files (into (:files header) (mapcat :files) parts)
           :listing (reduce (fn [m {lib-name :name lib-listing :listing}]
                              (if (nil? lib-listing)
                                m
                                (-> m
                                    (update-in [lib-name :files] (fnil into {}) (:files lib-listing))
                                    (update-in [lib-name :shelves] (fnil into {})
                                               (:shelves lib-listing)))))
                            {}
                            parts)
           :haselnuss (:haselnuss header)
           :format (:format header)
           :generation (:generation header)}
          (catch Exception e
            (unusable :unreadable (str "the saved state is not valid: " (throwable-message e)))))))))

(defn- checked-parts
  "`{:usable? true :header header :wanted [[lib-name i] ...]}` once the
  folder `sdir` is listed once and holds every part the (valid) `header`
  names; else `:unreadable` without reading any part."
  [^File sdir header]
  (let [present (set (.list sdir))
        total (reduce + (vals (:parts header)))
        wanted (for [[lib-name n] (:parts header) i (range n)] [lib-name i])
        missing (when (<= total (count present))
                  (some (fn [[lib-name i]]
                          (let [n (.getName (part-file sdir lib-name (part-generation header lib-name i) i))]
                            (when-not (contains? present n) n)))
                        wanted))]
    (cond
      (> total (count present))
      (unusable :unreadable (str "the saved state names " total " parts, but its folder holds "
                                 (count present) " files"))

      missing
      (unusable :unreadable (str "the saved state cannot be read: " missing " is missing"))

      :else
      {:usable? true :header header :wanted wanted})))

(defn- checked-header
  "Everything `load-state` checks before it reads a part: `checked-parts`
  of the header saved for the site in `dir`, or the reason it is not
  usable (see `load-state`)."
  [dir opts]
  (let [sdir (state-dir dir)
        header-file (io/file sdir header-name)
        current (version/version)
        link (symlinked-dir dir)]
    (cond
      link
      (unusable :symlinked (str link " is a symbolic link; the state is not read through it"))

      (not (.isFile header-file))
      (unusable :missing (str "no saved state in " sdir))

      :else
      (let [header (try (read-edn header-file)
                        (catch Exception e {::error (throwable-message e)})
                        (catch StackOverflowError _ {::error "nested too deeply"}))]
        (cond
          (::error header)
          (unusable :unreadable (str "the saved state cannot be read: " (::error header)))

          (not (and (map? header) (contains? header :format)))
          (unusable :unreadable "the saved state has no header")

          (not= format-version (:format header))
          (unusable :format (str "the saved state has format " (pr-str (:format header))
                                 ", this build reads format " format-version))

          (= version/unknown current)
          (unusable :version "this build does not know its own Haselnuss version")

          (not= current (:haselnuss header))
          (unusable :version (str "the saved state was written by Haselnuss "
                                  (pr-str (:haselnuss header)) ", this is " current))

          (not (valid-header? header))
          (unusable :unreadable "the saved state's header is not valid")

          (and (contains? opts :inputs) (not= (plain (:inputs opts)) (:inputs header)))
          (unusable :inputs-changed
                    "the directory file, --extensions or --transform changed since the state was saved")

          :else
          (checked-parts sdir header))))))

(defn load-state
  "The build state saved for the site in `dir` (see the namespace
  docstring), as `{:usable? true :graph g :inputs :books :files :listing
  :haselnuss :format :generation}` -- the graph rebuilt with the backend `opts`
  names (`:registry`, `:backend`, `:config`, as `scan` takes them) --
  or `{:usable? false :reason kw :message string}`. Never throws for a
  missing, unreadable, foreign or outdated state.

  `opts` `:inputs`, when given, is the `{path digest}` of this run's
  input files (`input-digests`): a state saved with different ones is
  `:inputs-changed`.

  TASK-297: `opts` `:libraries`, when given, a set of library names:
  only their parts are read (every part must still be present), so
  the graph, `:books`, `:files` and `:listing` hold those libraries
  only; `:header` is the header, whose other libraries a save carries
  (`carried-parts`), and `:saved-parts` the parts read, for
  `save-state!`'s `:previous`."
  ([dir] (load-state dir {}))
  ([dir opts]
   (let [checked (checked-header dir opts)]
     (if (:usable? checked)
       (load-parts (state-dir dir) (:header checked) opts
                   (cond->> (:wanted checked)
                     (contains? opts :libraries) (filter (fn [[lib-name]] (contains? (:libraries opts) lib-name)))))
       checked))))

(defn usable-header
  "The header of the state saved for the site in `dir` when `load-state`
  would find it usable as far as it can tell without reading a part --
  this Haselnuss version and state format, a valid header, the `:inputs`
  given in `opts` (as `load-state` takes them) and every part present --
  else nil. Reads the header and lists the state folder, nothing more:
  `--build-site`'s check that nothing changed since the last build
  (TASK-296) asks it, and never loads the graph."
  ([dir] (usable-header dir {}))
  ([dir opts]
   (let [checked (checked-header dir opts)]
     (when (:usable? checked) (:header checked)))))

(defn header-check
  "What `load-state` of the site in `dir` would decide before it reads a
  part: `{:usable? true :header h}` -- this Haselnuss version and state
  format, a valid header and every part present -- or `{:usable? false
  :reason kw :message string}` with `load-state`'s reasons (`:inputs`
  given in `opts` compared as it compares them). Reads the header and
  lists the state folder, nothing more: `--build-doc` (TASK-328) compares
  a site's inputs without loading its graph."
  ([dir] (header-check dir {}))
  ([dir opts]
   (let [checked (checked-header dir opts)]
     (if (:usable? checked)
       {:usable? true :header (:header checked)}
       checked))))

(defn saved-generation
  "The `:generation` of the header saved for the site in `dir`, or nil
  when there is none or it cannot be read. Reads the header only: a
  caller that loaded the state before taking the build lock compares it
  with `load-state`'s `:generation` afterwards, to learn whether another
  build saved a newer state in between (TASK-272)."
  [dir]
  (let [f (io/file (state-dir dir) header-name)]
    (when (and (nil? (symlinked-dir dir)) (.isFile f))
      (try (let [h (read-edn f)] (when (map? h) (:generation h)))
           (catch Exception _ nil)
           (catch StackOverflowError _ nil)))))

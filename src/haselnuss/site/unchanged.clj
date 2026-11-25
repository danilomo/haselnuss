(ns haselnuss.site.unchanged
  "Telling that nothing changed since the last `--build-site` without
  loading its saved state (TASK-296; doc-12, \"Scale\": a no-change run
  under 10 s on 50,000 books).

  An incremental build loads the saved graph, lists every library again,
  refreshes the graph and plans -- and when nothing changed, the plan
  rebuilds nothing and the run prints the same summary as the one before.
  At 50,000 books that is tens of seconds spent finding nothing. So a
  build that leaves the site settled -- every page and data file written
  -- also saves a record of what it looked at, `.haselnuss/unchanged`
  (`save!`), and the next build first checks the record (`check`): when
  every stamp in it still holds, that run's plan would rebuild nothing,
  write no data file and leave the state as it is, so the build prints
  the summary the record holds and stops there. Anything else -- one
  stamp off, a file missing, the record absent or unreadable -- and the
  build goes on exactly as before. A false \"unchanged\" would leave a
  stale site with no warning, so every signal is a cheap, conservative
  one: a stat that differs for any reason (a `touch`, a racy time) sends
  the build the long way, which then decides by content.

  ## What the record holds, and why it is enough

  What an incremental build's plan (`haselnuss.site.incremental/plan`)
  depends on, and the stamp standing for each:

  - The saved state itself: the header must be usable with this run's
    input digests -- version, format, every part present, the same
    directory file, `--extensions` and `--transform` files
    (`haselnuss.library.state/usable-header`) -- and its generation the
    one the record was saved with; no pending list (`incremental/
    pending?`) or images pending list (`site/images-pending?`), which a
    build that stopped partway leaves. The home folder (a `~` in the
    directory file), the working directory (the paths the items show)
    and `--trusted-extensions` must be the same, and so must the build of
    Haselnuss running (`haselnuss.version/build-identity`: development
    builds all share one version, and another build may report or emit
    otherwise); a build that cannot tell its identity saves no record.
  - Nothing only that build's run vouched for: a build that met a
    `haselnuss.library/transient?` failure (a generated shelf's script
    that ran out of time, TASK-299) saves no record, so the next run
    tries the script again rather than print the same failure. The price,
    for a script that always runs out of time (a whole-book shelf slower
    than its `document-timeout-ms`, a loop): every `--build-site` run
    pays its deadline again -- and leaves one more abandoned worker,
    which keeps a CPU busy on JDK 20+ and in the native image -- rebuilds
    its page and the pages quoting it, and saves no record of an
    unchanged site for as long as it keeps timing out. Before TASK-299
    the timeout was saved with the records and paid once.
  - Every file the graph's records claim (their `:files`): its stamp as
    the state saved it -- size, modification time, identity and, for a
    symbolic link, its target (`incremental/check-file`'s stat match) --
    or, for a file that was not there, still not there. A stamp the state
    saved without a trusted time (racy, or never verified) makes the
    build ineligible: the record is not saved.
  - The listing of every library (doc-12 step 2 lists them all again):
    every library of the directory must be a filesystem library whose
    listing is complete (`haselnuss.library.filesystem/listing-walk`):
    the stamp of every folder it looked into, taken before it looked
    (`haselnuss.library.digest/folder-stamp`: modification and change
    time and identity, so an added, removed or renamed entry or a
    permission change shows); the listing cache's stamp of every entry
    file; each generated shelf listed from its cache, whose files must
    be as stamped (`haselnuss.library.generated/cache-files-unchanged?`).
    Equal, the next listing lists the same books with the same
    diagnostics, so the refresh rescans, removes and rewrites nothing.
  - The site: every page, its copied images and its two data files must
    still be files of the site (`incremental/missing-on-disk?`,
    `missing-data`); every catalog file must hold the bytes the build
    wrote; and no image a page shows, or refused for what was on disk,
    may have changed (`site/changed-image-books`, which reads every image
    as the incremental plan does).
  - What the sweep of `books/` (`site/sweep!`) would remove: every folder
    it left there, stamped after it (`site-folder-entries`), so a file or
    folder added anywhere below shows in its folder's stamp -- or, for a
    folder this build wrote into within the racy window, listed, every
    entry's name and kind; `books/` itself as absent when there is none;
    and the images manifest's digest (`manifest-digest`), whose entries
    the sweep removes when no page copied them.

  With all of that equal, the plan the normal path would make is empty:
  no new, pending, unbuilt or changed book, no image change, no book
  added or removed, no page or data file missing, no edge changed, and
  its sweep removes nothing. Its output is then the one
  `haselnuss.site/write-site!` computes as `:settled`, which the record
  holds, and the site it leaves the one the hit leaves.

  The check still writes what a no-change run writes outside `books/`:
  the reader app's files when this Haselnuss's differ
  (`site/copy-app-files!`), and it sweeps temporary files at the top of
  the site and catalog pages no build wrote (`site/sweep-root!`,
  `site/sweep-catalog!`).

  ## Per library: a build of only what changed (TASK-297)

  Everything above is kept per library -- a section of the record each:
  its graph files (a file books of several libraries claim is in each
  of their sections), its listing's entry files and folders, its site
  folders (`books/<library>/` and below), its pages, catalog pages,
  generated shelves and images, and its share of the settled output
  (`haselnuss.site/write-site!`'s `:libraries`: its part of the summary,
  its items, skipped books and image conflicts, its catalog counts and
  copies) -- beside a global section (`books/` itself, and any folder
  below it of no library) and `catalog/index.json`. `examine` checks
  the run's environment, the state, the pending lists, the manifest,
  the index and the global section first: any of them off is a miss.
  Then each library: all of them as stamped is a hit; some of them
  changed is a `:delta`.

  A delta build (`haselnuss.cli/build-site*`'s `::delta`) takes every
  library connected to a changed one through the head's `:links` --
  the libraries a library's books refer to, either way (`delta-scope`)
  -- and loads, lists, plans, builds and sweeps only those, exactly as
  the whole build does them: a library outside that scope refers to none
  of them and none of them to it, so nothing a build of the scope
  changes reaches it, and nothing of it has changed since its last
  build. Its state parts, pages, catalog pages, copies, share of the
  output and record section are carried as that build left them. What
  the refresh finds can still undo that -- a book of the scope that now
  refers to a library outside it, or a library that became available or
  unavailable (every `:unknown-library` message lists them) -- and so
  can another build having saved the state since the record was read;
  then the build falls back to the whole one before it writes a page,
  a data file, the catalog or the state (it has taken the lock and
  cleared this record by then; the whole build writes it again).

  ## Format

  UTF-8 text, written atomically (`haselnuss.library.state/
  write-atomically!`): a format line; the head, one line of EDN (the
  generation, the environment above, the summary to print, the index's
  digest, the global section's stats digest, and per library its stats
  digest, links, generated shelves' cache entries, the images of the
  pages that show or refused any and its share of the settled output);
  then the sections, the global one (`G`) first and the libraries' (`L`
  and the name) in name order, each with one line per stamped path,
  sorted, as its kind (`F` a graph file, `E` a listing cache's entry
  file, `D` a library folder, `S` a site folder stamped, `R` one listed)
  and the path, then one line per page, the page path and its copied
  images; and an end line with the counts of sections and lines, so a
  cut file is no record. Paths are front-coded (the length of the prefix
  shared with the line of the same sort before it in the section, then
  the rest) and escaped (`\\\\`, `\\t`, `\\n`, `\\r`): at 50,000 books
  EDN took seconds to read. The stamps themselves are not written: the
  head holds one SHA-256 over each section's observations at save time
  (`stats-digest`), which `examine` recomputes from fresh stats.

  Plain maps only, no records and no reflection: the native image needs
  this namespace's `__init` class and nothing else."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [haselnuss.library :as library]
            [haselnuss.library.digest :as digest]
            [haselnuss.library.generated :as generated]
            [haselnuss.library.graph :as graph]
            [haselnuss.library.state :as state]
            [haselnuss.site :as site]
            [haselnuss.site.incremental :as incremental])
  (:import (java.io File Writer)
           (java.nio.file Files LinkOption)
           (java.security MessageDigest)
           (java.util.concurrent ExecutionException)))

(def record-rel
  "The record's path below the site folder."
  ".haselnuss/unchanged")

(def ^:private format-line "haselnuss-unchanged 2")

(def ^:private no-follow (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
(def ^:private follow (make-array LinkOption 0))

;; --- escaping and front coding ---------------------------------------------------

(defn escape
  "`s` with `\\`, tab, newline and carriage return written as `\\\\`,
  `\\t`, `\\n` and `\\r`, so it fits on one tab-separated line."
  ^String [^String s]
  (let [n (.length s)]
    (if (and (neg? (.indexOf s "\\")) (neg? (.indexOf s "\t"))
             (neg? (.indexOf s "\n")) (neg? (.indexOf s "\r")))
      s
      (let [sb (StringBuilder. (+ n 8))]
        (dotimes [i n]
          (let [c (.charAt s i)]
            (case c
              \\ (.append sb "\\\\")
              \tab (.append sb "\\t")
              \newline (.append sb "\\n")
              \return (.append sb "\\r")
              (.append sb c))))
        (str sb)))))

(defn unescape
  "The string `escape` made `s` from; nil when `s` is not one it makes."
  [^String s]
  (if (neg? (.indexOf s "\\"))
    s
    (let [sb (StringBuilder.)
          n (count s)]
      (loop [i 0]
        (if (>= i n)
          (str sb)
          (let [c (.charAt s i)]
            (if (not= c \\)
              (do (.append sb c) (recur (inc i)))
              (when (< (inc i) n)
                (when-let [r (case (.charAt s (inc i))
                               \\ \\
                               \t \tab
                               \n \newline
                               \r \return
                               nil)]
                  (.append sb ^char r)
                  (recur (+ i 2)))))))))))

(defn- shared-prefix
  "How many leading chars `a` and `b` share, never ending between the two
  halves of a surrogate pair."
  ^long [^String a ^String b]
  (let [n (min (count a) (count b))
        k (loop [i 0] (if (and (< i n) (= (.charAt a i) (.charAt b i))) (recur (inc i)) i))]
    (if (and (pos? k) (Character/isHighSurrogate (.charAt a (dec k)))) (dec k) k)))

(defn- write-front-coded!
  "Write `s` to `w` front-coded after `prev`: the length of the prefix
  they share (`shared-prefix`), a tab, and the rest of `s`, escaped."
  [^Writer w ^String prev ^String s]
  (let [k (shared-prefix prev s)
        n (.length s)]
    (.write w (String/valueOf k))
    (.write w "\t")
    (if (identical? s (escape s))
      (.write w s (int k) (int (- n k)))
      (.write w (escape (subs s k))))))

(defn- read-front-coded
  "The string a front-coded `prefix-length` and `tail` stand for after
  `prev`; nil when they cannot."
  [^String prev k ^String tail]
  (when (and tail (<= 0 k (count prev)))
    (str (subs prev 0 k) tail)))

;; --- observations ----------------------------------------------------------------

(def ^:private absent-digest
  "The digest the state saves for a graph file that is not there
  (`digest/unreadable` of a `NoSuchFileException`)."
  "unreadable:java.nio.file.NoSuchFileException")

(def ^:private folder-digest
  "The digest the state saves for a graph path that is a folder -- a
  symbolic link to one on an `!include` path (TASK-250): reading a folder
  throws a plain `IOException`."
  "unreadable:java.io.IOException")

(defn- stat-text [{:keys [size mtime] file-key :key}]
  (str "f " size " " mtime " " file-key))

(defn- with-link [obs link]
  (cond-> obs link (str " -> " link)))

(defn saved-file-observation
  "What `file-observation` must answer for a graph file the state stamped
  `stamp` (`incremental/check-file`'s): its stat, or for a path that was
  no regular file the digest of its failed read, and its link target;
  nil when the stamp cannot stand for the file without reading it --
  its time was racy."
  [stamp]
  (when (map? stamp)
    (cond
      (and (some? (:size stamp)) (some? (:mtime stamp))) (with-link (stat-text stamp) (:link stamp))
      (and (nil? (:size stamp)) (string? (:digest stamp)) (str/starts-with? (:digest stamp) "unreadable:"))
      (with-link (str "x " (:digest stamp)) (:link stamp))
      :else nil)))

(defn file-observation
  "What `path`, a file the graph claims, looks like now: its stat as a
  stamp holds it (`digest/stamp`) or, when it is no regular file, the
  digest reading it would give, told without reading it -- for a path
  that certainly does not exist (`Files/notExists`, through links) or is
  a folder, the two failed reads whose digest is known; anything else
  matches no saved stamp -- and its link target when it is a symbolic
  link (`incremental/link-target`). Equal to `saved-file-observation` of
  the stamp the state saved only when `incremental/check-file` finds the
  file unchanged: its stat matches, or it still fails to read the same
  way."
  [path]
  (let [s (digest/stamp path)
        link (incremental/link-target path)
        p (.toPath (io/file path))]
    (with-link (cond
                 s (stat-text s)
                 (Files/notExists p follow) (str "x " absent-digest)
                 (Files/isDirectory p follow) (str "x " folder-digest)
                 :else "unknown")
      link)))

(defn entry-observation
  "What the entry file `path` looks like to the listing cache now
  (`digest/stamp`)."
  [path]
  (if-let [s (digest/stamp path)] (stat-text s) "none"))

(defn folder-observation
  "What the folder `path` looks like now (`digest/folder-stamp`)."
  [path]
  (or (first (digest/folder-stamp path)) "none"))

(defn listing-observation
  "What the site folder `path` holds now, told by listing it: each
  entry's name and whether it is a regular file, a folder (neither
  followed if it is a symbolic link) or anything else, sorted; `none`
  when it is no folder (or a link to one). For a folder of the site
  whose stamp was racy when the record was saved (`site-folder-entries`)."
  [path]
  (let [p (.toPath (io/file path))]
    (if-not (Files/isDirectory p no-follow)
      "none"
      ;; TASK-310: a folder that cannot be listed (the sweep reports it) is
      ;; not a failure here: it reads as itself, apart from any listing
      (try
        (with-open [s (Files/newDirectoryStream p)]
          (str/join "\u0000"
                    (sort (map (fn [^java.nio.file.Path q]
                                 (str (.getFileName q) "\u0001"
                                      (cond (Files/isRegularFile q no-follow) "f"
                                            (Files/isDirectory q no-follow) "d"
                                            :else "o")))
                               s))))
        (catch java.io.IOException _ "unlistable")))))

(def ^:private observe
  {"F" file-observation
   "E" entry-observation
   "D" folder-observation
   "S" folder-observation
   "R" listing-observation})

(def ^:private chunk-size 2048)

(defn- pvec
  "`(vec (pmap f xs))`, rethrowing what `f` threw."
  [f xs]
  (try (vec (pmap f xs))
       (catch ExecutionException e (throw (or (.getCause e) e)))))

(defn site-folder-entries
  "`[[kind path observation] ...]` for the folders of the site's `books/`
  (`books-dir`) that `folders` names (`haselnuss.site/sweep!`'s, left
  after the sweep; empty when there is no `books/`): each stamped
  (`S`, `folder-observation`) when its stamp is trusted now -- added,
  removed or renamed entries change it, so anything a sweep would remove
  shows -- else, a folder this build wrote into within the racy window,
  listed (`R`, `listing-observation`); and `books/` as absent (`S`,
  `none`) when there is none."
  [books-dir folders]
  (if (empty? folders)
    [["S" (str books-dir) "none"]]
    (into []
          cat
          (pvec (fn [part]
                  (mapv (fn [f]
                          (let [now-ms (System/currentTimeMillis)
                                [obs newest-ms] (digest/folder-stamp f)]
                            (if (and obs (< (long newest-ms) (- now-ms digest/racy-window-ms)))
                              ["S" f obs]
                              ["R" f (listing-observation f)])))
                        part))
                (partition-all chunk-size folders)))))

(defn manifest-digest
  "The digest of the site `dir`'s images manifest (`.haselnuss/images`),
  or `none`: a path added to it would make the next sweep remove that
  file (TASK-296)."
  [dir]
  (let [f (io/file dir ".haselnuss" "images")]
    (if (Files/isRegularFile (.toPath f) no-follow)
      (try (digest/bytes-digest (Files/readAllBytes (.toPath f))) (catch Exception _ "unreadable"))
      "none")))

(defn stats-digest
  "One hex SHA-256 over `[kind path observation]` of every entry of
  `entries` (`[kind path]`, in order), `obs` answering the observation
  (a string): computed in chunks on several threads, each chunk's digest
  over its entries' strings -- each as its length and its UTF-16 code
  units -- then one over the chunks' digests in order."
  [entries obs]
  (let [chunk-digest (fn [part]
                       (let [md (MessageDigest/getInstance "SHA-256")
                             ^bytes buf (byte-array 4096)
                             ;; each string as its length and its UTF-16
                             ;; code units, through one buffer: no garbage
                             ;; per entry (see write-record!)
                             feed (fn [^String s]
                                    (let [n (.length s)]
                                      (dotimes [b 4]
                                        (.update md (unchecked-byte (bit-shift-right n (* 8 (- 3 b))))))
                                      (loop [i 0]
                                        (when (< i n)
                                          (let [m (min (- n i) 2048)]
                                            (dotimes [j m]
                                              (let [c (int (.charAt s (+ i j)))]
                                                (aset buf (* 2 j) (unchecked-byte (bit-shift-right c 8)))
                                                (aset buf (inc (* 2 j)) (unchecked-byte c))))
                                            (.update md buf 0 (int (* 2 m)))
                                            (recur (+ i m)))))))]
                         (doseq [[kind path :as entry] part]
                           (feed kind)
                           (feed path)
                           (feed (obs entry)))
                         (digest/hex (.digest md))))
        chunks (pvec chunk-digest (partition-all chunk-size entries))]
    (digest/bytes-digest (.getBytes ^String (str/join "\n" chunks) "UTF-8"))))

;; --- the record ------------------------------------------------------------------

(defn- utf8-safe-paths?
  "True when every string of `paths` survives UTF-8 (`digest/utf8-safe?`),
  told quickly for the usual one that holds no surrogate at all."
  [paths]
  (every? (fn [^String s]
            (or (loop [i 0]
                  (cond (= i (.length s)) true
                        (Character/isSurrogate (.charAt s i)) false
                        :else (recur (inc i))))
                (digest/utf8-safe? s)))
          paths))

(defn- sorted-strings
  "The strings `xs` sorted in their natural order, as a vector. A sort
  through a Clojure comparator took seconds on 100,000 paths sharing a
  long prefix."
  [xs]
  (let [^"[Ljava.lang.String;" arr (into-array String xs)]
    (java.util.Arrays/sort arr)
    (vec arr)))

(defn- section-entries
  "`[[kind path observation] ...]` of one section, sorted by path (see the
  namespace docstring): the graph files `files` as `stamps` saved them,
  the listing cache `listing`'s entry files (where no `F` line holds the
  same), the walk record `walk`'s folders and the site folders
  `site-entries`; and whether every graph file's stamp was trusted."
  [{:keys [files stamps listing walk site-entries]}]
  (let [file-entries (mapv (fn [p] [p (saved-file-observation (get stamps p))]) files)
        file-obs (into {} file-entries)
        folder-obs (:folders walk)
        site-obs (fn [kind] (into {} (keep (fn [[k p obs]] (when (= kind k) [p obs]))) site-entries))
        stamped-obs (site-obs "S")
        listed-obs (site-obs "R")
        ;; an entry file the graph claims under the same path with the
        ;; same stat needs no second line: its `F` observation holds it
        entry-obs (into {}
                        (keep (fn [[p e]]
                                (let [obs (some-> (:stamp e) stat-text)]
                                  (when-not (and obs (= obs (get file-obs p)))
                                    [p obs]))))
                        (:files listing))]
    {:trusted? (not-any? (comp nil? second) file-entries)
     :entries (into []
                    (mapcat (fn [p]
                              (for [[kind m] [["D" folder-obs] ["E" entry-obs] ["F" file-obs]
                                              ["R" listed-obs] ["S" stamped-obs]]
                                    :when (contains? m p)]
                                [kind p (get m p)])))
                    (sorted-strings (distinct (concat (keys file-obs) (keys entry-obs) (keys folder-obs)
                                                      (keys listed-obs) (keys stamped-obs)))))}))

(defn graph-links
  "`{library #{library ...}}` of the graph `g`: for each library whose
  books it records, the other libraries its books' edges lead into
  (`graph/target-key`, a dangling edge's included) -- what a change in
  one library can reach in another (TASK-297). A file books of two
  libraries claim needs no link: it is in both libraries' sections of
  the record, so a change to it changes both."
  [g]
  (reduce (fn [m id]
            (let [lib (:library id)]
              (reduce (fn [m edge]
                        (let [target (first (graph/target-key edge))]
                          (if (and (string? target) (not= target lib))
                            (update m lib (fnil conj #{}) target)
                            m)))
                      (update m lib #(or % #{}))
                      (graph/edges-from g id))))
          {}
          (keys (graph/indexed-books g))))

(defn graph-files-by-library
  "`{library [path ...]}`: the paths the records of each library's books
  in the graph `g` claim (`:files`), a path claimed in several libraries
  under each."
  [g]
  (update-vals (reduce (fn [m [id {:keys [files]}]] (update m (:library id) (fnil into #{}) files))
                       {}
                       (graph/indexed-books g))
               vec))

(defn site-folder-library
  "The library whose part of the site's `books/` (the path `books-dir`)
  the folder `path` is in -- `books/<library>/` or below, `library` one of
  `lib-names` -- or nil: `books/` itself, or a folder of no library."
  [^String books-dir lib-names ^String path]
  (let [prefix (str books-dir File/separator)]
    (when (str/starts-with? path prefix)
      (let [below (subs path (count prefix))
            i (.indexOf below File/separator)
            top (if (neg? i) below (subs below 0 i))]
        (when (contains? lib-names top) top)))))

(defn record
  "The record of a build that leaves the site settled, as data
  (`{:head :sections}`), or `{:ineligible reason}` when the next run could
  not trust it (see the namespace docstring). `build`:

  - `:generation`, the saved state's generation now;
  - `:inputs`, `:home`, `:base-dir`, `:trusted-extensions`;
  - `:output`, `write-site!`'s `:settled` (nil: not settled), and
    `:libraries`, its `:libraries` (each library's share);
  - `:catalog`, `write-site!`'s `:catalog` digests;
  - `:lib-names`, the libraries this build listed and built, each a
    section of the record (every library of the directory, but for a
    build that left some as they were, which are `:carried`);
  - `:books`, the state's `:books` (BookId -> page data) of those;
  - `:files-by-lib`, `{library [path ...]}`, the paths the graph's
    records of each library claim, and `:stamps`, the stamps the state
    saved for them;
  - `:listing`, `scan/listing-caches`, and `:walks`, `scan/listing-walks`;
  - `:links`, `{library #{library ...}}`, the other libraries each
    library's books refer to or share a file with;
  - `:books-dir`, the site's `books/` folder, and `:site-entries`,
    `site-folder-entries` of the folders the sweep left under it, taken
    after it; `:manifest`, `manifest-digest`;
  - `:carried`, `{library {:piece :section}}`: the libraries a build left
    as they were, as the record it started from holds them (TASK-297);
  - `:build`, this build's identity (`haselnuss.version/build-identity`;
    nil when unknown, and then there is no record);
  - `:transient?`, true when the build left something only its own run
    can vouch for (`haselnuss.site.incremental/transient-left?`: a
    generated shelf's script that ran out of time, TASK-299), which the
    next run must retry rather than skip: then there is no record."
  [{:keys [generation inputs home base-dir trusted-extensions output libraries catalog books lib-names
           files-by-lib stamps listing walks links books-dir site-entries manifest carried build
           transient?]}]
  (let [all-libs (into (set lib-names) (keys carried))
        site-by-lib (group-by (fn [[_ p]] (site-folder-library (str books-dir) all-libs p)) site-entries)
        books-by-lib (group-by (comp :library key) books)
        sections (into {}
                       (map (fn [lib]
                              [lib (section-entries {:files (get files-by-lib lib)
                                                     :stamps stamps
                                                     :listing (get listing lib)
                                                     :walk (get walks lib)
                                                     :site-entries (get site-by-lib lib)})]))
                       lib-names)
        global (section-entries {:site-entries (get site-by-lib nil)})
        pages-of (fn [lib]
                   (let [page-images (into {} (map (fn [[_ {:keys [path images]}]]
                                                     [(str path) (vec (sort (map str (keys images))))]))
                                           (get books-by-lib lib))]
                     (mapv (fn [p] [p (get page-images p)]) (sorted-strings (keys page-images)))))
        own (into {}
                  (map (fn [lib]
                         (let [{:keys [entries]} (get sections lib)]
                           [lib {:piece {:digest (when (not-any? (comp nil? #(nth % 2)) entries)
                                                   (stats-digest entries #(nth % 2)))
                                         :links (into (sorted-set) (get links lib))
                                         :shelves (vec (sort-by first (map (fn [[d e]] [d (select-keys e [:script :data])])
                                                                           (:shelves (get listing lib)))))
                                         :images (into {}
                                                       (keep (fn [[id b]]
                                                               (when (or (seq (:images b)) (seq (:refused-images b)))
                                                                 [id (select-keys b [:images :refused-images])])))
                                                       (get books-by-lib lib))
                                         :settled (get libraries lib)}
                                 :section {:library lib
                                           :stats (mapv (fn [[kind path]] [kind path]) entries)
                                           :pages (pages-of lib)}}])))
                  lib-names)
        everything (merge carried own)
        head {:generation generation
              :inputs inputs
              :home home
              :base-dir base-dir
              :trusted-extensions (boolean trusted-extensions)
              :output output
              :index (get catalog "catalog/index.json")
              :global (when (not-any? (comp nil? #(nth % 2)) (:entries global))
                        (stats-digest (:entries global) #(nth % 2)))
              :libraries (into (sorted-map) (map (fn [[lib {:keys [piece]}]] [lib piece])) everything)
              :manifest manifest
              :build build}
        own-entries (into (:entries global) (mapcat (comp :entries val)) sections)]
    (cond
      (nil? output) {:ineligible "a page or data file was not written"}
      transient? {:ineligible "a generated shelf's script ran out of time, which the next run retries"}
      (not (string? build)) {:ineligible "this build does not know its own identity"}
      (empty? site-entries) {:ineligible "the site's books/ folder was not looked at"}
      (not-every? (fn [[_ {:keys [path images]}]] (and (string? path) (every? string? (keys images)))) books)
      {:ineligible "a page has no path"}
      (not (string? generation)) {:ineligible "no saved state"}
      (or (empty? all-libs) (not-every? #(:complete? (get walks %)) lib-names))
      {:ineligible "a library was not listed completely from its listing cache"}
      (not-every? (comp :trusted? val) sections) {:ineligible "a file's stamp is not trusted"}
      (some (comp nil? #(nth % 2)) own-entries) {:ineligible "a listed file's stamp is missing"}
      (not-every? #(map? (get libraries %)) lib-names) {:ineligible "a library's share of the summary is missing"}
      (not (utf8-safe-paths? (concat (map second own-entries)
                                     (mapcat (fn [[_ {:keys [section]}]]
                                               (concat (map first (:pages section)) (mapcat second (:pages section))))
                                             own))))
      {:ineligible "a path does not survive UTF-8"}
      (not= head (state/plain head)) {:ineligible "the summary does not survive EDN"}
      :else {:head head
             :sections (into [{:library nil
                               :stats (mapv (fn [[kind path]] [kind path]) (:entries global))
                               :pages []}]
                             (map (comp :section val))
                             (sort-by key everything))})))

(defn- write-edn-line [^Writer w x]
  (let [^String text (binding [*print-length* nil *print-level* nil *print-meta* false
                               *print-dup* false *print-readably* true *print-namespace-maps* false]
                       (pr-str x))]
    (.write w text)
    (.write w "\n")))

(defn write-record!
  "Write the record `r` (`record`'s, not ineligible) to the site `dir`,
  atomically: the format line, the head, then each section -- `G` for
  the global one, `L` and the library's name for a library's -- with its
  stat lines and its page lines, each front-coded from the line of its
  kind before it in the section, and an end line counting the sections
  and the lines below the head."
  [dir {:keys [head sections]}]
  (state/write-atomically!
   (io/file dir record-rel)
   (fn [^Writer w]
     (.write w (str format-line "\n"))
     (write-edn-line w head)
     ;; written piece by piece: at 50,000 books the garbage of a string
     ;; per line cost the build a full collection
     (let [lines (reduce
                  (fn [n {:keys [library stats pages]}]
                    (if (nil? library)
                      (.write w "G\n")
                      (do (.write w "L\t") (.write w (escape library)) (.write w "\n")))
                    (reduce (fn [prev [^String kind ^String path]]
                              (.write w kind)
                              (write-front-coded! w prev path)
                              (.write w "\n")
                              path)
                            ""
                            stats)
                    (reduce (fn [prev [^String path images]]
                              (.write w "P")
                              (write-front-coded! w prev path)
                              (doseq [i images]
                                (.write w "\t")
                                (.write w (escape i)))
                              (.write w "\n")
                              path)
                            ""
                            pages)
                    (+ n 1 (count stats) (count pages)))
                  0
                  sections)]
       (.write w (str "end " (count sections) " " lines "\n"))))))

(defn record-digest
  "The digest of the record file's bytes in the site `dir`, or nil when
  there is none (or it is no regular file): what tells a delta build,
  under its own lock, that no other build ran since the record was
  examined -- each one that is not a hit clears it first (TASK-297
  review)."
  [dir]
  (let [p (.toPath (io/file dir record-rel))]
    (when (Files/isRegularFile p no-follow)
      (try (digest/bytes-digest (Files/readAllBytes p)) (catch Exception _ nil)))))

(defn clear!
  "Remove the record from the site `dir`: a build that may change the
  site does so before it writes anything."
  [dir]
  (Files/deleteIfExists (.toPath (io/file dir record-rel))))

(defn- parse-long* [s] (try (Long/parseLong s) (catch Exception _ nil)))

(defn- split-tabs
  "The tab-separated fields of `s` from index `from`, empty ones kept."
  [^String s from]
  (loop [i (long from) out (transient [])]
    (let [tab (.indexOf s "\t" (int i))]
      (if (neg? tab)
        (persistent! (conj! out (subs s i)))
        (recur (inc tab) (conj! out (subs s i tab)))))))

(def ^:private stat-kinds #{"F" "E" "D" "R" "S"})

(defn read-record
  "The record `write-record!` wrote to the site `dir` (`{:head
  :sections}`), or nil when there is none or it is not one, whole: the
  global section first, then library sections in name order, each
  library once, every section's stat lines before its page lines and
  the global section without pages."
  [dir]
  (try
    (let [f (io/file dir record-rel)]
      (when (Files/isRegularFile (.toPath f) no-follow)
        (let [lines (str/split (slurp f :encoding "UTF-8") #"\n" -1)
              n (count lines)]
          (when (and (< 3 n) (= format-line (first lines)) (= "" (peek lines)))
            (let [head (edn/read-string (nth lines 1))
                  body (subvec lines 2 (- n 2))
                  [end-word section-count line-count] (str/split (nth lines (- n 2)) #" ")
                  section-count (parse-long* section-count)
                  line-count (parse-long* line-count)]
              (when (and (map? head) (= "end" end-word) section-count line-count
                         (= (count body) line-count))
                (let [field (fn [^String line]
                              (let [tab (.indexOf line "\t")]
                                (when (pos? tab)
                                  [(subs line 0 1) (parse-long* (subs line 1 tab))
                                   (split-tabs line (inc tab))])))
                      sections
                      (loop [[^String line & more] body
                             out (transient [])
                             section nil
                             prev-stat ""
                             prev-page ""]
                        (let [close (fn [] (if section (conj! out (-> section
                                                                      (update :stats persistent!)
                                                                      (update :pages persistent!)))
                                               out))]
                          (cond
                            (nil? line) (persistent! (close))
                            (or (= "G" line) (str/starts-with? line "L\t"))
                            (let [library (when-not (= "G" line) (unescape (subs line 2)))]
                              (when (or (= "G" line) (library/valid-name? library))
                                (recur more (close) {:library library :stats (transient []) :pages (transient [])}
                                       "" "")))
                            :else
                            (let [[kind k [tail & extra]] (field line)
                                  page? (= "P" kind)
                                  path (when (and section k (or page? (contains? stat-kinds kind)))
                                         (read-front-coded (if page? prev-page prev-stat) k (some-> tail unescape)))
                                  extra (mapv unescape extra)]
                              (when (and path (not-any? nil? extra)
                                         ;; stats first, then pages
                                         (or page? (zero? (count (:pages section))))
                                         (or page? (empty? extra)))
                                (if page?
                                  (recur more out (update section :pages conj! [path extra]) prev-stat path)
                                  (recur more out (update section :stats conj! [kind path]) path prev-page)))))))]
                  (when (and sections
                             (= section-count (count sections))
                             (nil? (:library (first sections)))
                             (empty? (:pages (first sections)))
                             (every? :library (rest sections))
                             (= (map :library (rest sections)) (sort (distinct (map :library (rest sections))))))
                    {:head head :sections sections}))))))))
    (catch Exception _ nil)
    (catch StackOverflowError _ nil)))

;; --- the check -------------------------------------------------------------------

(defn- pages-present?
  "True when every page of `pages` (`[path images]`), its copied images
  and its data files are files of the site `dir`."
  [dir pages]
  (let [site-file? (incremental/site-file-checker (io/file dir))]
    (every? true?
            (pvec (fn [part]
                    (every? (fn [[path images]]
                              (let [saved {:path path :images (zipmap images (repeat nil))}]
                                (and (not (incremental/missing-on-disk? site-file? saved))
                                     (empty? (incremental/missing-data site-file? saved)))))
                            part))
                  (partition-all chunk-size pages)))))

(defn- catalog-unchanged?
  "True when every catalog file `catalog` names (site path -> digest) is
  a file of the site `dir` holding those bytes."
  [^File dir catalog]
  (every? (fn [[rel d]]
            (and (site/site-file? dir rel)
                 (= d (try (digest/bytes-digest (Files/readAllBytes (.toPath (io/file dir rel))))
                           (catch Exception _ nil)))))
          catalog))

(defn- observed
  "`stats-digest` of the stat lines `stats` as they look now."
  [stats]
  (stats-digest stats (fn [[kind path]] ((get observe kind) path))))

(defn- library-unchanged?
  "True when nothing the library's section `section` and head entry
  `piece` stand for has changed: its stats digest, its pages and their
  copies and data files, its catalog files, its generated shelves' cache
  files and every image its pages show or refused."
  [^File dir piece {:keys [stats pages]}]
  (and (map? piece)
       (string? (:digest piece))
       (map? (:settled piece))
       (= (:digest piece) (observed stats))
       (catalog-unchanged? dir (:catalog (:settled piece)))
       (every? (fn [[d entry]] (generated/cache-files-unchanged? d entry)) (:shelves piece))
       (pages-present? dir pages)
       (empty? (site/changed-image-books (:images piece)))))

(defn examine
  "What the record in the site `dir` (the real path, locked) tells of
  this run (see the namespace docstring): `{:hit head}` when nothing it
  stamps has changed -- the head's `:output`, `{:lines :items :shown
  :summary}`, being what to print -- or `{:delta {:changed #{library}
  :record record}}` when everything outside the libraries `:changed` is
  as the record holds it: the run's environment, the state, the pending
  lists, the images manifest, `catalog/index.json` and the global
  section, and every other library's section (TASK-297); else nil.
  `env`: this run's `:inputs` (input digests), `:home`, `:base-dir`,
  `:trusted-extensions` and `:build` (nil, unknown: never a hit). Reads
  the state's header and the record, stats every stamped path and page,
  lists the site folders whose stamps were racy, and reads the catalog,
  the images manifest and every image a page shows; writes nothing."
  [^File dir {:keys [inputs home base-dir trusted-extensions build]}]
  (when-let [{:keys [head sections] :as r} (read-record dir)]
    (let [header (state/usable-header dir {:inputs inputs})
          [global & libs] sections]
      (when (and header
                 (string? build)
                 (= build (:build head))
                 (= (:generation header) (:generation head))
                 (= (manifest-digest dir) (:manifest head))
                 (= (state/plain inputs) (:inputs head))
                 (= home (:home head))
                 (= base-dir (:base-dir head))
                 (= (boolean trusted-extensions) (:trusted-extensions head))
                 (map? (:output head))
                 (map? (:libraries head))
                 (= (set (keys (:libraries head))) (set (map :library libs)))
                 (not (incremental/pending? dir))
                 (not (site/images-pending? dir))
                 (string? (:index head))
                 (catalog-unchanged? dir {"catalog/index.json" (:index head)})
                 (string? (:global head))
                 (= (:global head) (observed (:stats global))))
        (let [changed (into (sorted-set)
                            (keep (fn [{:keys [library] :as section}]
                                    (when-not (library-unchanged? dir (get-in head [:libraries library]) section)
                                      library)))
                            libs)]
          (if (empty? changed)
            {:hit head}
            {:delta {:changed changed :record r}}))))))

(defn delta-scope
  "For `examine`'s `:delta` (`{:changed :record}`): `{:libraries L
  :generation :carried {library {:piece :section}} :global-folders [path
  ...]}` -- `:generation` the saved state's the record was saved with,
  `L`, the libraries a build must load, list and build, being every
  library connected to a changed one through the record's `:links`,
  either way (a book of one refers to a book of the other, or they share
  a file), so no library outside `L` refers to one inside it or is
  referred to by one; `:carried`, every other library's head entry and
  section, as the build leaves them; `:global-folders`, the site folders
  the global section stamps, which the build stamps again."
  [{:keys [changed] r :record}]
  (let [{:keys [head sections]} r
        links (into {} (map (fn [[lib piece]] [lib (set (:links piece))])) (:libraries head))
        neighbours (reduce-kv (fn [m lib targets]
                                (reduce (fn [m t] (update m t (fnil conj #{}) lib))
                                        (update m lib (fnil into #{}) targets)
                                        targets))
                              {}
                              links)
        scope (loop [seen (set changed) queue (vec changed)]
                (if-let [lib (peek queue)]
                  (let [fresh (remove seen (get neighbours lib))]
                    (recur (into seen fresh) (into (pop queue) fresh)))
                  seen))
        by-lib (into {} (map (juxt :library identity)) (rest sections))]
    {:libraries scope
     ;; TASK-297 review: the state the record stands beside; a build
     ;; that loads another (one saved since) cannot carry from it
     :generation (:generation head)
     :carried (into {}
                    (keep (fn [[lib piece]]
                            (when-not (contains? scope lib)
                              [lib {:piece piece :section (get by-lib lib)}])))
                    (:libraries head))
     :global-folders (mapv second (:stats (first sections)))}))

(defn carried-claimants
  "`{path #{library ...}}` for the paths of `paths` (a set) that the
  graph files of a `carried` library (`delta-scope`'s, its section's `F`
  lines) hold too: the libraries outside a delta build's graph that
  claim them (`haselnuss.library.state/save-state!`'s
  `:other-claimants`)."
  [carried paths]
  (reduce (fn [m [lib {:keys [section]}]]
            (reduce (fn [m [kind path]]
                      (if (and (= "F" kind) (contains? paths path))
                        (update m path (fnil conj #{}) lib)
                        m))
                    m
                    (:stats section)))
          {}
          carried))

(defn check
  "The head of the record in the site `dir` when nothing it stamps has
  changed (`examine`'s `:hit`), else nil."
  [^File dir env]
  (:hit (examine dir env)))

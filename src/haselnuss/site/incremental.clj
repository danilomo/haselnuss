(ns haselnuss.site.incremental
  "What an incremental `--build-site` rebuilds (doc-12, \"What gets
  rebuilt\" steps 2-6; TASK-272): the file stamps that find changed files,
  and the plan `haselnuss.site/write-site!` follows -- which pages to emit
  and why, which to keep, and which kept pages' data files to make again.
  `haselnuss.cli/build-site` loads the state, refreshes the graph and saves
  the state; this namespace decides, from what they hand it.

  ## File stamps (step 2)

  A stamp is `{:size :mtime :key :digest}`: one `stat` (size, modification
  time, file identity; `haselnuss.library.digest/stamp`) and the SHA-256 of
  the bytes read just AFTER it (`take-stamp`); `{:digest marker}` alone for
  a file that is not there or cannot be read (`haselnuss.library.digest/
  unreadable`). A path that is itself a symbolic link also records its
  `:link` target, read without following it: a link to a folder on an
  `!include` path has no stat or bytes of its own, so retargeting it
  shows only there. A file whose stat matches its saved stamp is unchanged
  without being read; any other is read and compared by digest and
  identity (`check-files`). Two rules keep a stamp honest:

  - It is taken BEFORE the build reads the file (the state's own rule,
    `haselnuss.library.state`): the stamp of every file the saved graph
    names is taken by `check-files`, before the refresh reads anything.
    An edit after the stamp then shows as a different file next run.
  - A modification time within `haselnuss.library.digest/racy-window-ms`
    of the moment of the stamp is saved as nil (TASK-266's racy rule): an
    edit landing in the same timestamp tick, keeping the size, would
    leave the stat unchanged, so such a file is read and compared by
    digest next run.

  A file the build reads that no saved stamp names -- every file of a full
  build, a new book's, a new `!include` -- cannot be stamped before it is
  read: the build does not know it yet. `verified-stamps` stamps it after
  the build and keeps the stamp only when every record claiming the file
  hashes, from digests taken now, to the record's own `:hash`: then they
  are digests of the bytes the scan read, and nothing changed in between.
  A file left without a stamp is treated as changed next run, which
  rescans its books once (and `rebuild/narrowed` finds their content the
  same, so their pages are not rebuilt).

  ## The plan (steps 3-6)

  `plan` answers `{:rebuild {BookId reason} :kept {BookId saved} :data
  {BookId #{:backlinks :previews}}}` over the buildable books of the site
  (`haselnuss.site/site-books`). A book is rebuilt, with the first reason
  that applies, when it is:

  - new (not in the saved graph), named in the pending list of an earlier
    build that stopped before it saved its state (`read-pending`), or has
    no saved page (it failed, or shared its id, last time);
  - in the refresh's rebuild set narrowed by content
    (`haselnuss.library.graph.rebuild/narrowed`): its source changed, or
    a book it refers to did;
  - saved `:transient?` (TASK-299): its last build met a failure of that
    run, a generated shelf's script that ran out of time
    (`haselnuss.library/transient?`), which this run may not;
  - showing an image that changed (`haselnuss.site/changed-image-books`
    over its saved `:images`; images are not in the graph's `:files`);
  - the owner of a page folder another book's page folder was added to or
    removed from (`s` beside a new or vanished `s/x`): which of its
    images it may copy depends on that (`haselnuss.site/image-collector`);
  - missing its page or a copied image on disk.

  Every other buildable book is kept with its saved data. A kept book's
  data files are made again when they are missing, after an earlier
  failure or interruption, `previews.json` when a preview failed for a
  reason of its run (a `:transient?` preview failure, TASK-299), and --
  the narrowing of doc-12 step 6 --
  `backlinks.json` when the edges into it changed between the saved and
  the new graph (`edges-to`, found through the edges of the books the
  refresh rescanned, rewrote or removed), `previews.json` when, besides,
  the passages other books link to in it (`haselnuss.site/
  preview-passages`) changed. A rebuilt book's data files are always made
  again (`write-site!`). The catalog is recomputed whole every run and
  written only where its bytes changed.

  ## The pending list

  Before a page is written, `write-pending!` saves the ids of every book
  the plan rebuilds and of every kept book whose data files it makes
  again in `.haselnuss/pending`; `clear-pending!` removes it once the state
  is saved. A build that stops in between -- killed, or failing --
  leaves it, and the next run that trusts the saved state rebuilds those
  books whatever their files say: their pages may already be the new
  ones, and an edit undone in the meantime would otherwise leave them so.

  Plain maps only, no records and no reflection: the native image needs
  this namespace's `__init` class and nothing else."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [haselnuss.book-ref :as book-ref]
            [haselnuss.library :as library]
            [haselnuss.library.digest :as digest]
            [haselnuss.library.graph :as graph]
            [haselnuss.library.graph.scan :as scan]
            [haselnuss.library.state :as state]
            [haselnuss.site :as site])
  (:import (java.io File Writer)
           (java.nio.file Files LinkOption)))

;; --- parallel helpers ------------------------------------------------------------

(defn- par-mapcat
  "`(mapcat f xs)` as a vector, computed in chunks on several threads; for
  stat-and-read work over tens of thousands of files."
  [f xs]
  (into [] cat (pmap #(into [] (mapcat f) %) (partition-all 256 xs))))

;; --- file stamps -----------------------------------------------------------------

(defn- read-digest
  "The digest of the bytes of `path`, or `digest/unreadable`'s marker."
  [path]
  (try (digest/bytes-digest (Files/readAllBytes (.toPath (io/file path))))
       (catch Exception e (digest/unreadable e))))

(defn link-target
  "The target `path` names when it is itself a symbolic link (read, not
  followed), as text; nil for anything else."
  [path]
  (try (let [p (.toPath (io/file path))]
         (when (Files/isSymbolicLink p) (str (Files/readSymbolicLink p))))
       (catch Exception _ nil)))

(defn- stamp-after
  "The stamp of `path` from the stat `s` taken just before (nil: not a
  regular file) and the link target `link` (`link-target`), and its
  bytes read now; the modification time nil when `s` is racy at
  `now-ms`."
  [path s link now-ms]
  (let [d (read-digest path)]
    (cond-> (if s
              (cond-> (assoc s :digest d)
                (nil? (digest/trusted-stamp s now-ms)) (assoc :mtime nil))
              {:digest d})
      link (assoc :link link))))

(defn take-stamp
  "The stamp of `path` now (see the namespace docstring): its stat and,
  when it is a symbolic link, its target, then the digest of its bytes.
  `now-ms` is the wall clock just before."
  [path now-ms]
  (let [link (link-target path)]
    (stamp-after path (digest/stamp path) link now-ms)))

(defn- same-stat?
  "True when the stat `s` and link target `link` match the saved stamp
  `saved`, whose modification time was not racy."
  [saved s link]
  (and (some? s) (some? (:mtime saved))
       (= (:size saved) (:size s)) (= (:mtime saved) (:mtime s)) (= (:key saved) (:key s))
       (= (:link saved) link)))

(defn check-file
  "`[changed? stamp]` for `path` against its saved stamp `saved` (nil for
  none): unchanged without a read when its stat and link target match;
  else read, and unchanged when digest, identity and link target match.
  The link target matters for a symbolic link to a FOLDER (a `:files`
  entry on an `!include` path, TASK-250): it has no stat or bytes of its
  own, so pointing it elsewhere shows only there. `stamp` is what to
  save: `saved` when the stat matched, else the stamp taken now."
  [path saved now-ms]
  (let [link (link-target path)
        s (digest/stamp path)]
    (if (and saved (same-stat? saved s link))
      [false saved]
      (let [fresh (stamp-after path s link now-ms)]
        [(not (and (map? saved) (= (:digest saved) (:digest fresh)) (= (:key saved) (:key fresh))
                   (= (:link saved) (:link fresh))))
         fresh]))))

(defn check-files
  "`{:changed #{path} :stamps {path stamp}}` for every path of `paths`
  against `saved` (`{path stamp}`): the files that changed since their
  stamps (or have none), and the stamp to save for each (`check-file`).
  Run BEFORE the build reads any of them."
  [paths saved]
  (let [now (System/currentTimeMillis)
        results (par-mapcat (fn [p] [[p (check-file p (get saved p) now)]]) (vec (set paths)))]
    {:changed (into #{} (keep (fn [[p [changed?]]] (when changed? p))) results)
     :stamps (into {} (map (fn [[p [_ s]]] [p s])) results)}))

(defn graph-files
  "Every path the book records of `g` claim (their `:files`), as a set."
  [g]
  (into #{} (mapcat (comp :files val)) (graph/indexed-books g)))

(defn verified-stamps
  "Stamps for `paths`, files the build read without a stamp taken before,
  taken now; each kept only when every record of `g` claiming it hashes,
  from digests taken now of its canonical files, to its `:hash`
  (`haselnuss.library.graph.scan/record-hash`), and every symbolic link
  it holds still leads to one of those files or a folder above one (a
  link retargeted after the read does not). A path left out gets no
  stamp, so the next run treats it as changed."
  [g paths]
  (let [now (System/currentTimeMillis)
        paths (set paths)
        fresh (into {} (par-mapcat (fn [p] [[p (take-stamp p now)]]) (vec paths)))
        records (graph/indexed-books g)
        ids (vec (into #{} (mapcat #(graph/books-for-file g %)) paths))
        bad (into #{}
                  (par-mapcat (fn [id]
                                (let [{:keys [files] record-hash :hash} (get records id)
                                      {canonical true links false} (group-by #(= % (scan/canonical-path %)) files)
                                      digests (into {} (map (fn [p] [p (or (:digest (get fresh p)) (read-digest p))]))
                                                    canonical)
                                      ;; a link the record met must still lead to a
                                      ;; file it read, or a folder above one
                                      leads-in? (fn [link]
                                                  (let [c (scan/canonical-path link)
                                                        prefix (str c File/separator)]
                                                    (some #(or (= c %) (str/starts-with? % prefix)) canonical)))]
                                  (when-not (and (seq canonical)
                                                 (= record-hash (scan/record-hash canonical digests))
                                                 (every? leads-in? links))
                                    (filter paths files))))
                              ids))]
    (apply dissoc fresh bad)))

;; --- the pending list ------------------------------------------------------------

(def ^:private pending-rel ".haselnuss/pending")

(defn write-pending!
  "Save the ids of `plan`'s rebuilt books and of the kept books whose data
  files it makes again in the site `dir`'s pending list, atomically."
  [dir {:keys [rebuild data]}]
  (let [lines (concat (map #(str "page " %) (sort (map book-ref/format-book-id (keys rebuild))))
                      (map #(str "data " %) (sort (map book-ref/format-book-id (keys data)))))]
    (state/write-atomically! (io/file dir pending-rel)
                             (fn [^Writer w] (doseq [l lines] (.write w (str l "\n")))))))

(defn read-pending
  "`{:pages #{BookId} :data #{BookId}}` from the site `dir`'s pending list
  (empty when there is none, or it is not a regular file)."
  [dir]
  (let [f (io/file dir pending-rel)
        lines (try (if (Files/isRegularFile (.toPath f) (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
                     (str/split-lines (slurp f :encoding "UTF-8"))
                     [])
                   (catch Exception _ []))
        ids (fn [prefix]
              (into #{}
                    (keep (fn [l]
                            (when (str/starts-with? l prefix)
                              (let [parsed (book-ref/parse-book-ref (subs l (count prefix)))]
                                (when-not (or (book-ref/malformed? parsed) (:passage parsed))
                                  (:id parsed))))))
                    lines))]
    {:pages (ids "page ") :data (ids "data ")}))

(defn add-pending-pages!
  "Merge `page <id>` lines for the BookIds `ids` into the site `dir`'s
  pending list, keeping every `page` and `data` line already there, and
  write it atomically: the pages `--build-doc` writes without saving the
  state (TASK-328), which the next `--build-site` that trusts the state
  rebuilds whatever their files say -- an edit undone in the meantime
  included -- and which keep it off the unchanged-site shortcut and the
  per-library delta build."
  [dir ids]
  (let [{:keys [pages data]} (read-pending dir)
        texts (fn [xs] (into (sorted-set) (map book-ref/format-book-id) xs))
        lines (concat (map #(str "page " %) (texts (concat pages ids)))
                      (map #(str "data " %) (texts data)))]
    (state/write-atomically! (io/file dir pending-rel)
                             (fn [^Writer w] (doseq [l lines] (.write w (str l "\n")))))))

(defn pending?
  "True when the site `dir` has a pending list, of whatever content, or
  something else under its name: an earlier build stopped before it was
  done (TASK-296)."
  [dir]
  (not (Files/notExists (.toPath (io/file dir pending-rel)) (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))))

(defn clear-pending!
  "Remove the site `dir`'s pending list."
  [dir]
  (Files/deleteIfExists (.toPath (io/file dir pending-rel))))

;; --- the plan ---------------------------------------------------------------------

(defn page-dir
  "The folder of the book `id`'s page, `books/<library>/<shelf...>/<slug>/`,
  or nil when it has no page path."
  [id]
  (when-let [p (site/page-path id)]
    (subs p 0 (inc (str/last-index-of p "/")))))

(defn- ancestor-dirs
  "The folders above the page folder `d`, each ending in `/`."
  [d]
  (let [parts (str/split d #"/")]
    (for [k (range 2 (count parts))]
      (str (str/join "/" (take k parts)) "/"))))

(defn- content-key
  "What `haselnuss.library.graph.rebuild/narrowed` compares of a record."
  [record]
  [(or (:document-hash record) (:hash record)) (:labels record)])

(def ^:private no-follow (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))

(defn site-file-checker
  "`haselnuss.site/site-file?` of the site `dir` as a fn of the relative
  path, each folder on the way checked once however many files below it
  are asked about: tens of thousands of pages share their library's and
  shelves' folders, and checking every folder again for every page and
  data file cost seconds on the 50,000-book collection."
  [^File dir]
  (let [root (.toPath dir)
        folders (java.util.concurrent.ConcurrentHashMap.)
        real-folder? (fn [^String rel]
                       (let [hit (.get folders rel)]
                         (if (some? hit)
                           (boolean hit)
                           (let [v (Files/isDirectory (.resolve root rel) no-follow)]
                             (.put folders rel v)
                             v))))]
    (fn [^String rel]
      (let [[top & more] (butlast (str/split rel #"/"))]
        (and (or (nil? top) (every? real-folder? (reductions #(str %1 "/" %2) top more)))
             (Files/isRegularFile (.resolve root rel) no-follow))))))

(defn missing-on-disk?
  "True when the saved page `saved` (`{:path :images}`) or one of its
  copied images is not a file of the site (`site-file?`, a
  `site-file-checker`)."
  [site-file? saved]
  (not (and (string? (:path saved))
            (site-file? (:path saved))
            (every? site-file? (keys (:images saved))))))

(defn missing-data
  "Which data files of the kept page `saved` are not in the site
  (`site-file?`, a `site-file-checker`)."
  [site-file? saved]
  (let [d (subs (:path saved) 0 (inc (str/last-index-of (:path saved) "/")))]
    (cond-> #{}
      (not (site-file? (str d "backlinks.json"))) (conj :backlinks)
      (not (site-file? (str d "previews.json"))) (conj :previews))))

(defn transient-left?
  "True when a build leaves something only its own run can vouch for
  (TASK-299): a record or library record of the graph `g` holding a
  `haselnuss.library/transient?` diagnostic, or a page of `books` (the
  state's `{BookId page}`) saved `:transient?` or with such a preview
  failure. The next run must then look for itself
  (`haselnuss.site.unchanged`): it retries them."
  [g books]
  (boolean
   (or (some (fn [[_ record]] (some library/transient? (:diagnostics record))) (graph/indexed-books g))
       (some (fn [[_ record]] (some library/transient? (:diagnostics record))) (graph/indexed-libraries g))
       (some (fn [[_ page]] (or (:transient? page) (some :transient? (:preview-failures page)))) books))))

(defn- edge-delta-keys
  "The `graph/target-key`s of every edge from the books `sources` that is
  in one of `g0` and `g1` and not the other: the books whose `edges-to`
  may differ between them are among those keys' books."
  [g0 g1 sources]
  (into #{}
        (comp (mapcat (fn [s]
                        (let [a (set (graph/edges-from g0 s))
                              b (set (graph/edges-from g1 s))]
                          (concat (remove b a) (remove a b)))))
              (keep graph/target-key))
        sources))

(defn plan
  "The plan of an incremental build (see the namespace docstring) for
  `write-site!`'s `:plan`, over the `site-books` `entries`:

  - `:dir`, the site folder (real path);
  - `:old-graph`, the graph the saved state loaded, and `:refresh`, what
    `haselnuss.library.graph.scan/refresh` answered for it (`:graph`,
    `:changes`), `:narrowed` its rebuild set narrowed by content;
  - `:saved`, the saved state's `:books`;
  - `:pending`, `read-pending` of the site.

  Answers `{:rebuild {BookId reason} :kept {BookId saved} :data {BookId
  #{:backlinks :previews}}}`."
  [entries {:keys [dir old-graph refresh narrowed saved pending]}]
  (let [site-file? (site-file-checker (io/file dir))
        g0 old-graph
        g1 (:graph refresh)
        {:keys [rescanned rewritten vanished]} (:changes refresh)
        rescanned (set rescanned)
        before (graph/indexed-books g0)
        after (graph/indexed-books g1)
        buildable (into [] (comp (filter :book) (map :id)) entries)
        narrowed (set narrowed)
        pending-pages (set (:pages pending))
        pending-data (set (:data pending))
        appeared-or-vanished (concat (remove #(contains? before %) (map :id entries)) vanished)
        nested (if (empty? appeared-or-vanished)
                 #{}
                 (let [dir-owner (into {} (keep (fn [id] (some-> (page-dir id) (vector id)))) buildable)]
                   (into #{} (comp (keep page-dir) (mapcat ancestor-dirs) (keep dir-owner))
                         appeared-or-vanished)))
        settled (filterv #(and (contains? saved %) (contains? before %)
                               (not (contains? narrowed %)) (not (contains? pending-pages %)))
                         buildable)
        image-changed (set (site/changed-image-books (select-keys saved settled)))
        missing (set (par-mapcat #(when (missing-on-disk? site-file? (get saved %)) [%]) settled))
        reason (fn [id]
                 (cond
                   (not (contains? before id)) "new book"
                   ;; an earlier build that stopped, or --build-doc (TASK-328)
                   (contains? pending-pages id) "left pending by an earlier run"
                   (not (contains? saved id)) "not built before"
                   (contains? narrowed id) (if (and (contains? rescanned id)
                                                    (not= (content-key (get before id)) (content-key (get after id))))
                                             "source changed"
                                             "a book it refers to changed")
                   ;; TASK-299: its build met a failure of that run
                   (:transient? (get saved id)) "a script ran out of time when it was last built"
                   (contains? image-changed id) "an image it shows changed"
                   (contains? nested id) "a book was added or removed inside its folder"
                   (contains? missing id) "its page was missing"
                   :else nil))
        rebuild (into {} (keep (fn [id] (when-let [r (reason id)] [id r]))) buildable)
        kept (into {} (keep (fn [id] (when-not (contains? rebuild id) [id (get saved id)]))) buildable)
        delta (edge-delta-keys g0 g1 (-> #{} (into rescanned) (into rewritten) (into vanished)))
        edges-changed (into #{}
                            (filter (fn [t]
                                      (and (contains? delta (book-ref/book-key t))
                                           (not= (set (graph/edges-to g0 t)) (set (graph/edges-to g1 t))))))
                            (keys kept))
        data (into {}
                   (par-mapcat (fn [[id saved-page]]
                                 (let [redo? (or (contains? pending-data id) (some? (:data-failed saved-page)))
                                       which (cond-> (missing-data site-file? saved-page)
                                               redo? (into [:backlinks :previews])
                                               ;; TASK-299: a preview its run failed to
                                               ;; build for a reason of that run
                                               (some :transient? (:preview-failures saved-page))
                                               (conj :previews)
                                               (contains? edges-changed id) (conj :backlinks)
                                               (and (contains? edges-changed id)
                                                    (not= (site/preview-passages g0 id)
                                                          (site/preview-passages g1 id)))
                                               (conj :previews))]
                                   (when (seq which) [[id which]])))
                               (vec kept)))]
    {:full? false :rebuild rebuild :kept kept :data data}))

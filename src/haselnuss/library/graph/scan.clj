(ns haselnuss.library.graph.scan
  "Scanning books into reference-graph book records (doc-11, \"Scan
  pipeline & caching\"; TASK-240). `scan-book` turns ONE book into the
  record `haselnuss.library.graph/with-book` writes; `scan` (TASK-241)
  scans every book in scope into a graph, and `refresh` (TASK-244) brings
  such a graph up to date from changed files (see its docstring).

  ## The context

  `context` makes the `ctx` every call takes, once per scan (doc-11: a
  fresh one per `scan` and per `refresh`, so a stale document never leaks
  into a rebuild):

      {:directory     LibraryDirectory-or-nil ; loaded once, up front
       :passages      passage-context         ; haselnuss.library.passage/context
       :books         books-option            ; haselnuss.library.references/books-option
       :own-directory LibraryDirectory-or-nil ; what scan lists; :directory unless given
       :own-passages  passage-context}        ; a book's own reading; :passages unless
                                              ; :own-directory was given (see `context`)

  `:passages` is the per-scan cache: it holds each book's RESOLVED
  document (keyed by library + slug; a second book with the same slug gets
  an entry of its own), and both a book's own scan and
  every reference into it read it there (`passage/resolved-book`,
  `passage/resolve-passage`), so a book quoted by fifty others is loaded
  and resolved once per context. `:books` resolves references through that
  same context, and its `:resolve` answers each reference as written once
  per context (`memoized-resolve`), so a passage -- a generated book's
  `locate` included -- is resolved once however many books cite it. The
  directory, loaded once, holds each library value, whose own caches
  (a filesystem library's listing and each Book's document `delay`) make
  a generated shelf's script load and list its books once, and each
  book's document be produced once, per context (TASK-241 pins this with
  counters).

  ## What `scan-book` does (doc-11, \"scan-book, for one book\")

  1. The book's document comes from `haselnuss.library/document`; a
     filesystem book's has its `!include`s expanded already
     (`haselnuss.library.filesystem`, through `resolver/expand-includes`),
     every chapter's nodes keeping their own file and line.
  2. Edges are collected from that document BEFORE quote expansion
     (`haselnuss.walk/fold`): one per `@{...}` (`:link`) and `!quote`
     (`:quote`) the author wrote, wherever a build would resolve one
     (`ref-scope`). Quoted content is never spliced in here, so the
     references inside it -- the quoted book's own, or its cross-references
     rewritten into book references -- are the quoted book's, reached
     through the quote edge (`haselnuss.library.graph.rebuild`).
     `:from :passage` is the id of the nearest block carrying one, the
     reference's own `!quote` block included (a quote labelled `{#q:1}`
     sits in passage `q:1`); none at book level. Only block ids are
     labels, as for passages. `:from :order` is the reference's place in
     the document order of that same walk, and the record's
     `:label-spans` the order span of each labelled block (`outline`), so
     which references a quoted label or range holds -- however deeply
     nested -- is answered from the record (TASK-242).
  3. Each reference resolves through the books option's `:resolve`, as
     the quote and link steps of a build do. A malformed reference is a
     dangling `:malformed-book-ref` edge, and a `!quote` of the book itself
     a dangling `:quote-cycle` (what a build's quote step reports for it;
     deeper cycles and `:quote-too-deep` are met inside quoted content, so
     they belong to the books quoted). A dangling edge whose book is known
     (`:unknown-label`, `:bad-locator`, ...) keeps it as `:to :id`.
  4. The book's own diagnostics are its load warnings and
     `resolver/resolve-document`'s diagnostics without `:books` -- no
     quote expansion, no link resolution, no lower, no emit -- followed
     by one diagnostic per dangling reference and one `:stale-shelf` info
     per reference whose shelf path is stale (the edge stays `:resolved`),
     each placed at the reference as a build places it
     (`resolver/locate-warning`: `:source-file`, `:source-line`, a
     `path:line: ` message prefix) plus the reference's `:source-column`
     where the parser recorded one, the same as its edge's `:position
     :column` (TASK-248). So the record's `:diagnostics` alone are
     what `--check` reports for the book. `:resolve`'s build-level
     `:diagnostics` -- the directory's load problems, the TARGET books' load
     warnings, an invalid URL template -- are not this book's and are
     dropped (see \"What `scan` reports, and where\").
  5. `:files`, `:hash` and `:labels`, below.

  ## Files and hashes

  `:files` holds canonical paths (`File/getCanonicalPath`, the absolute
  path when that fails): a `.hdoc` book's entry file, the bibliography its
  front matter names and every file its `!include`s reached, a missing
  one listed like any other
  (`haselnuss.library.filesystem/source-files`); a generated book's
  `shelf.clj` and its `:data` files (`haselnuss.library.generated`); none
  for any other book. A caller asking `books-for-file` canonicalises its
  path the same way.

  `:files` also holds every symbolic link met reaching those files
  (`symlinks-on`, TASK-250): an included file, a folder on the way to it
  or the entry file that is a link, and each link a link's target passes
  through -- each as `link-path`, the link's own path with the folders
  above it canonical. A file watcher may report only the link when it is
  retargeted, and the canonical path then names the NEW target, which no
  record holds yet; `refresh` looks a changed path up both ways.

  `:hash` is the hex SHA-256 over the sorted canonical `:files` (the
  links left out: retargeting one changes which file is read, which the
  canonical paths already show), each with the
  SHA-256 of its bytes (a missing or unreadable file hashes as such): file
  content, never a timestamp, so it is the same in every JVM. The bytes
  are the ones the LOAD read (`haselnuss.library.digest`, TASK-244), not
  a second read afterwards, so an edit that lands while a book is being
  scanned never pairs the new bytes' hash with edges built from the old
  ones. A book with no files -- an in-memory one -- hashes its document's
  `canonical-form` instead, so its hash still follows its content.

  `:labels` maps each block id of the RESOLVED document (the first block
  carrying it, as passage resolution picks it) to the hex SHA-256 of that
  block's `canonical-form`: what a quote of the label splices, numbering
  text included, and source positions (metadata) excluded, so moving a
  passage down a file leaves its hash alone.

  ## What `scan` does (TASK-241)

  `(scan ctx scope)` reduces `with-library` and `with-book` into an empty
  graph from the backend registry, library by library in directory order:
  one library record per library in scope, then one book record per book
  the library lists (`haselnuss.library/listed-books`, so two books that
  share a slug are both scanned). Two books that share even the canonical
  id (`x.hdoc` beside `x/book.hdoc`) are both scanned, each from its own
  file, into ONE record under that id (`merged-record`, TASK-250): the
  graph is keyed by BookId and no reference can tell them apart, so
  `--check` reports what is wrong inside either at its own file, line
  and column. References into libraries outside the
  scope still resolve -- the directory holds every library -- so a
  dangling cross-library reference is caught on the citing book.

  The result also satisfies `haselnuss.library.graph/LibraryIndex`
  (`haselnuss.library.graph.libraries/library-indexed`), and the
  library-level diagnostics live in the library records -- not in a pseudo
  book record, which would need a reserved BookId and leak into
  `indexed-books`, `edges-to` and `books-for-file`, and not beside the
  graph, which `check-report` would then need as well:

  - an available library's record (`:status :available`, `:root` for a
    filesystem one) carries its `library-diagnostics` --
    `:duplicate-slug`, `:invalid-book-name`, `:invalid-front-matter`,
    `:unreadable-shelf`, and its generated shelves' `:scripts-disabled`,
    `:script-failed` and `:invalid-generated-book` (a shelf that lists no
    books has no book records) -- plus an `:invalid-url-template` when the
    directory answers an invalid template for it;
  - a library the directory names but could not load gets a record
    `:status :unavailable` holding `find-library`'s `:unavailable-library`
    (its load problem under `:cause`), placed (`:source-file`,
    `:source-line`) at its directory entry. Its books are not scanned, and
    references into it dangle as `:unavailable-library`.

  ## What `scan` reports, and where

  Every problem is reported once, on the record it concerns:

  - a book's own (load warnings, resolve diagnostics, dangling references)
    on its book record;
  - a library's own on its library record; the directory's load problems
    are those of the libraries that failed, each reported on its
    `:unavailable` record when that library is in scope;
  - `:resolve`'s build-level `:diagnostics` are dropped: the directory's
    problems are covered above, and a TARGET book's load warnings and an
    invalid URL template belong to the target's own book or library
    record. So a scope covers its own libraries only: a problem inside a
    book or library OUTSIDE the scope is reported only as it shows on the
    citing edge (a target that fails to load dangles as `:book-failed`,
    one in a failed library as `:unavailable-library`), and fully when
    that library is scanned.

  ## Failure

  `scan` throws only for a scope that is not nil or a collection of names
  (`::invalid-scope`), an unknown library name in it (`::unknown-library`),
  a bad graph backend (`registry/new-graph`), or a
  record a backend rejects -- each a caller or backend bug (doc-11: exit
  2). A library that throws while it is listed, or that the directory
  names but `find-library` then answers nil for, is an `:unavailable`
  record with an `:unavailable-library` diagnostic.

  `scan-book` never throws for a document problem. A book whose document
  cannot be produced or resolved gets a record with no edges, no labels,
  and the diagnostic it failed with -- a `:book-failed`, or the provider's
  own (a generated book's `:script-failed`/`:invalid-generated-book`,
  doc-11's \"Error handling\") -- placed at its file (`:source-file`) when
  it has one; anything else that throws while scanning it becomes a
  `:book-failed` too."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [haselnuss.ast :as ast]
            [haselnuss.book-ref :as book-ref]
            [haselnuss.library :as library]
            [haselnuss.library.digest :as digest]
            [haselnuss.library.directory :as directory]
            [haselnuss.library.filesystem :as fs]
            [haselnuss.library.generated :as generated]
            [haselnuss.library.graph :as graph]
            [haselnuss.library.graph.libraries :as libraries]
            [haselnuss.library.graph.rebuild :as rebuild]
            [haselnuss.library.graph.registry :as registry]
            [haselnuss.library.passage :as passage]
            [haselnuss.library.references :as references]
            [haselnuss.library.url :as url]
            [haselnuss.resolver :as resolver]
            [haselnuss.walk :as walk])
  (:import (java.io File)
           (java.nio.charset StandardCharsets)
           (java.nio.file Files)))

;; --- context -----------------------------------------------------------------

(defn canonical-path
  "`path` as `:files` and `books-for-file` hold it: `File/getCanonicalPath`,
  else the absolute path."
  [path]
  (let [f (io/file path)]
    (try (.getCanonicalPath f) (catch java.io.IOException _ (.getAbsolutePath f)))))

(defn link-path
  "`path` as `:files` holds a symbolic link (TASK-250): every folder above
  it canonical (`canonical-path` of its parent) and its own name kept, so
  the path names the link itself rather than what it points at. The same
  as `canonical-path` for a path that is not a link; `canonical-path`
  itself for one whose name is `.` or `..`, or that has no parent."
  [path]
  (let [f (.getAbsoluteFile (io/file path))
        parent (.getParentFile f)
        leaf (.getName f)]
    (if (or (nil? parent) (contains? #{"" "." ".."} leaf))
      (canonical-path path)
      (str (io/file (canonical-path parent) leaf)))))

(defn lookup-paths
  "The forms `:files` may hold the file at `path` (a path as a file
  watcher reports it, raw) under, for `books-for-file` and
  `haselnuss.library.graph.rebuild/affected-by-files`, which compare
  exactly: `canonical-path`, the file it reaches, and `link-path`, the
  link itself should it be one (TASK-250) -- the same string when it is
  not. `refresh` looks every changed path up this way."
  [path]
  (vec (distinct [(canonical-path path) (link-path path)])))

(def ^:private max-links
  "How many symbolic links `symlinks-on` follows for one path before it
  stops, as the kernel's `ELOOP` does (40 on Linux)."
  40)

(defn symlinks-on
  "The `link-path` of every symbolic link met resolving `path` as the
  operating system does, name by name from the root: a folder link on the
  way, the file itself when it is a link, and every link a link's target
  passes through in turn. `[]` when none, or when resolving fails."
  [path]
  (try
    (let [absolute (.toAbsolutePath (.toPath (io/file path)))
          names (fn [^java.nio.file.Path p] (map str (iterator-seq (.iterator p))))]
      (loop [current (.getRoot absolute) todo (names absolute) links [] budget max-links]
        (if-let [[n & more] (seq todo)]
          (case n
            "." (recur current more links budget)
            ".." (recur (or (.getParent current) current) more links budget)
            (let [candidate (.resolve current ^String n)]
              (if (and (pos? budget) (Files/isSymbolicLink candidate))
                (let [target (Files/readSymbolicLink candidate)]
                  (recur (if (.isAbsolute target) (.getRoot target) current)
                         (concat (names target) more)
                         (conj links (str candidate))
                         (dec budget)))
                (recur candidate more links budget))))
          links)))
    (catch Exception _ [])))

(defn- memoized-resolve
  "`resolve`, a books option's `:resolve`, answering each reference AS
  WRITTEN once per context: the scan's \"resolved passages are cached\"
  (doc-11), so a passage referenced by fifty books is looked up -- and a
  locator `locate`d, which runs a generated shelf's script -- once. Its
  build-level `:diagnostics` are dropped: a scan reports none of them
  through a reference (see the namespace docstring, \"What `scan`
  reports\")."
  [resolve-ref]
  (let [cache (atom {})]
    (fn [written parsed]
      (let [state (swap! cache update written
                         #(or % (delay (dissoc (resolve-ref written parsed) :diagnostics))))]
        @(get state written)))))

(defn- build-directory-opts
  "`directory-opts` (`haselnuss.library.references/directory-loader`'s)
  with the `:filesystem` provider of its registry (default
  `haselnuss.library.directory/default-registry`) rebuilt to parse every
  book with `kinds`, as a build's own directory is (`haselnuss.cli`'s
  `directory-opts`). Unchanged when `kinds` is nil."
  [directory-opts kinds]
  (cond-> directory-opts
    (some? kinds)
    (assoc :registry (directory/register-provider (or (:registry directory-opts)
                                                      directory/default-registry)
                                                  :filesystem (fs/provider {:kinds kinds})))))

(defn context
  "A fresh scan context (see the namespace docstring). `opts`:

  - `:directory`, a LibraryDirectory, or a 0-argument fn answering one or
    nil (`haselnuss.library.references/directory-loader`), called here,
    once -- so a directory file that cannot be read throws now, before any
    book is scanned (doc-11: exit 2); nil, no directory: every reference is
    then a dangling `:no-library-directory`.
  - `:directory-opts`, used when `:directory` is not given: the options of
    `haselnuss.library.references/directory-loader` (`:path`, `:env`,
    `:home`, `:registry`), loaded here the same way.
  - `:kinds`, with `:directory-opts`: the build's parse vocabulary kinds,
    so every filesystem book parses as the build's quoted books do.
  - `:resolve-opts`, the build's `haselnuss.resolver/resolve-document`
    options a book is resolved with (lexicon, directive registry and
    kinds, front-matter and sublabel names, citation styles; see
    `haselnuss.library.passage/context`).
  - `:resolve-fn`, the passage context's test seam replacing
    `resolve-document`.

  - `:own-directory`, a LibraryDirectory or a 0-argument fn answering one
    (called here, once): the directory whose libraries `scan` lists and
    whose books `scan-book` reads as their OWN documents, when they must
    be read differently from how a reference reads them. `--check
    --transform` (TASK-242) is the case: a build rewrites the book it
    builds, never the books it quotes or links to, so the scanned books
    come from a directory whose filesystem provider transforms
    (`haselnuss.library.filesystem`'s `:transform`) and references
    resolve through `:directory`, which does not. A book's own document
    is then resolved in a passage context of its own (`:own-passages`,
    the same resolver options), so the two readings never share a cache
    entry -- and a book that is also referenced is read twice. Default:
    `:directory` itself, one reading, one cache.

  `:directory-opts`, `:kinds` and `:resolve-opts` are the seam for
  `--check` (TASK-242) to scan exactly as a build resolves: the CLI hands
  over the same registry, kinds and resolver options its build would.

  - `:on-scan-book`, a test seam: a fn called with the BookId of each book
    `scan-book` scans in this context, before it is scanned (TASK-244
    counts what `refresh` rescans with it).
  - `:directory-file`, with `:directory`: the path of the directory file
    it was loaded from, if any. With `:directory-opts` the context finds
    it itself. Kept, canonical, as `:directory-file`: a change to that
    file makes `refresh` scan afresh (see `refresh`)."
  ([] (context {}))
  ([{:keys [directory directory-opts kinds resolve-opts resolve-fn own-directory on-scan-book
            directory-file]}]
   (let [load-dir (fn [d] (if (satisfies? library/LibraryDirectory d) d (d)))
         opts (when (and (nil? directory) (some? directory-opts))
                (build-directory-opts directory-opts kinds))
         ;; what `references/directory-loader` does, keeping the file
         file (if opts (directory/find-directory-file opts) directory-file)
         dir (cond
               (some? directory) (load-dir directory)
               (some? file) (directory/build-directory file (directory/read-directory-file file) opts)
               :else nil)
         new-passages #(passage/context (cond-> {:resolve-opts resolve-opts}
                                          resolve-fn (assoc :resolve-fn resolve-fn)))
         passages (new-passages)
         own-dir (when (some? own-directory) (load-dir own-directory))
         books (references/books-option {:directory dir
                                         :context passages
                                         :directory-reported? true})]
     (cond-> {:directory dir
              :own-directory (or own-dir dir)
              :passages passages
              :own-passages (if own-dir (new-passages) passages)
              :books (update books :resolve memoized-resolve)}
       on-scan-book (assoc :on-scan-book on-scan-book)
       (some? file) (assoc :directory-file (canonical-path file))))))

;; --- hashing -----------------------------------------------------------------

(defn- sha256-hex ^String [^String s]
  (digest/bytes-digest (.getBytes s StandardCharsets/UTF_8)))

(defn canonical-form
  "`x` as a string that depends only on its value: maps and sets in a
  fixed order (their entries sorted by their own canonical form), no
  metadata, nothing elided -- so equal values print equal in every JVM,
  whatever order their hash maps happen to iterate in."
  [x]
  (letfn [(form [x]
            (cond
              (map? x) (str "{" (str/join " " (sort (map (fn [[k v]] (str (form k) " " (form v))) x))) "}")
              (set? x) (str "#{" (str/join " " (sort (map form x))) "}")
              (sequential? x) (str "[" (str/join " " (map form x)) "]")
              :else (binding [*print-meta* false *print-length* nil *print-level* nil
                              *print-namespace-maps* false]
                      (pr-str x))))]
    (form x)))

(defn- file-digest
  "The digest of `path`'s bytes as they are now, or a marker when it
  cannot be read: only for a file the load did not read itself."
  [path]
  (try (digest/bytes-digest (Files/readAllBytes (.toPath (io/file path))))
       (catch Exception e (digest/unreadable e))))

(defn- files-hash
  "The record `:hash` over `files` (canonical paths), each with its digest
  in `digests` (canonical path -> the digest of the bytes the load read),
  else `file-digest`; or over `fallback`'s canonical form when there are
  no files."
  [files digests fallback]
  (if (seq files)
    (sha256-hex (str/join "\n" (map #(str % "\t" (or (get digests %) (file-digest %))) (sort files))))
    (sha256-hex (str "no-files\n" (canonical-form fallback)))))

(defn- book-digests
  "Canonical path -> digest of the bytes `book`'s load read
  (`haselnuss.library.digest`), for the files it read."
  [book]
  (reduce-kv (fn [m path d] (digest/record m (canonical-path path) d))
             {}
             (cond
               (fs/filesystem-book? book) (fs/source-digests book)
               (generated/generated-book? book) (generated/file-digests book)
               :else {})))

(defn- book-paths
  "The paths, as the load used them, of every file `book`'s document
  depends on."
  [book]
  (cond
    (fs/filesystem-book? book) (fs/source-files book)
    (generated/generated-book? book) (into [(generated/script-path book)]
                                           (generated/data-files book))
    :else []))

(defn- book-files
  "`{:canonical #{path} :links #{path}}` for `book`: the canonical path of
  every file its document depends on, which `:hash` is taken over, and
  every symbolic link met reaching them (`symlinks-on`), which `:files`
  holds as well so a change reported at the link is claimed too
  (TASK-250)."
  [book]
  (let [paths (book-paths book)]
    {:canonical (into #{} (map canonical-path) paths)
     :links (into #{} (mapcat symlinks-on) paths)}))

(defn- book-file
  "The file a diagnostic about `book` as a whole is placed at: a
  filesystem book's entry file, a generated book's script; nil for any
  other."
  [book]
  (cond
    (fs/filesystem-book? book) (str (fs/entry-file book))
    (generated/generated-book? book) (generated/script-path book)
    :else nil))

;; --- edges -------------------------------------------------------------------

(def ref-scope
  "Where `scan-book` looks for references: every block and inline position
  a build's quote and link steps reach -- `meta.title` included, a
  Directive's target-specific `:fallback` not."
  #{::ast/block ::ast/inline ::ast/cell ::ast/row ::ast/cite-item ::ast/meta})

(defn- reference-kind
  "`:link` for an `@{...}`, `:quote` for a `!quote`, else nil."
  [ref-kind node]
  (when (map? node)
    (case [ref-kind (keyword (:t node))]
      [::ast/inline :book-ref] :link
      [::ast/block :book-quote] :quote
      nil)))

(defn- prefix?
  [prefix path]
  (and (<= (count prefix) (count path))
       (= prefix (subvec path 0 (count prefix)))))

(defn- close-span
  "`spans` with the label of the stack entry `[path id start]` spanning
  `start` through `end`, unless a block that starts earlier carries it
  (blocks close innermost first, so an outer one can close after an
  inner one with the same id)."
  [spans [_ id start] end]
  (let [[old-start] (get spans id)]
    (cond-> spans (or (nil? old-start) (< start old-start)) (assoc id [start end]))))

(defn outline
  "`{:references [...] :label-spans {...}}` for `document`, from one walk
  over every node in `ref-scope`, in document order (pre-order), each
  node counted in turn from 0 -- its ORDER.

  `:references`: every reference written, in document order, as `{:node
  :kind :label :order}`: `:label` is the id of the nearest block enclosing
  the reference -- its own `!quote` block included -- or nil, and
  `:order` its node's order.

  `:label-spans`: label -> `[first last]`, the orders of the first block
  carrying that id and of the last node inside it. A reference lies inside
  the passage a label names exactly when its order is within the label's
  span, and inside a range `from..to` (sibling blocks) exactly when it is
  within `[first-of-from last-of-to]` -- unlabelled blocks between them
  included. So a book record answers, from data alone, which quotes a
  quoted passage holds (`haselnuss.library.graph.check`)."
  [document]
  (let [result (walk/fold
                {:table walk/table
                 :scope ref-scope
                 :visit (fn [{:keys [stack n] :as acc} path ref-kind node]
                          (let [[stack spans] (loop [s stack spans (:spans acc)]
                                                (if (and (seq s) (not (prefix? (first (peek s)) path)))
                                                  (recur (pop s) (close-span spans (peek s) (dec n)))
                                                  [s spans]))
                                id (when (and (= ::ast/block ref-kind) (map? node))
                                     (get-in node [:attr :id]))
                                stack (if (string? id) (conj stack [path id n]) stack)
                                kind (reference-kind ref-kind node)]
                            (cond-> (assoc acc :stack stack :spans spans :n (inc n))
                              kind (update :found conj {:node node
                                                        :kind kind
                                                        :label (second (peek stack))
                                                        :order n}))))}
                {:stack [] :found [] :spans {} :n 0} ::ast/document document)
        last-order (dec (:n result))]
    {:references (:found result)
     :label-spans (reduce #(close-span %1 %2 last-order)
                          (:spans result)
                          (rseq (:stack result)))}))

(defn references
  "Every reference written in `document`, in document order, as `{:node
  :kind :label :order}` (`outline`'s `:references`)."
  [document]
  (:references (outline document)))

(defn- position
  "The Edge `:position` of the reference `node`: its file, line and --
  where the parser placed it on its line (TASK-248) -- 1-based column,
  of the `@` of an `@{...}` or the `!` of a `!quote`."
  [node]
  (let [{:keys [file line column]} (ast/source node)]
    (when (pos-int? line)
      (cond-> {:line line}
        (string? file) (assoc :file file)
        (pos-int? column) (assoc :column column)))))

(defn- with-column
  "`diagnostic` (already located at the line of the edge position `at`)
  carrying `at`'s column as `:source-column`, when it has one. Its
  message keeps the build's `path:line: ` prefix."
  [diagnostic at]
  (let [column (:column at)]
    (cond-> diagnostic
      (and (pos-int? column) (some? (:source-line diagnostic))) (assoc :source-column column))))

(defn- located
  "`diagnostic` about the reference `written` in `node`, placed as a build
  places it, plus its column."
  [node written diagnostic]
  (-> (resolver/locate-warning node (merge {:level :warning} diagnostic {:ref written}))
      (with-column (position node))))

(defn- known-book
  "The BookId a failure's `:canonical` names, or nil."
  [failure]
  (let [parsed (when (string? (:canonical failure)) (book-ref/parse-book-ref (:canonical failure)))]
    (when (and parsed (not (book-ref/malformed? parsed)) (library/valid-book-id? (:id parsed)))
      (:id parsed))))

(defn- ambiguous-slug?
  "True when the library of the book `id` in `ctx`'s directory answers
  `:ambiguous-book` for its slug: several books share its library and
  slug (a `:duplicate-slug`), so no reference -- the book's own included
  -- singles it out, and no build knows it as itself (one is built by
  path, without `:self`; TASK-259). A lookup in the library's listing
  index; no document is read."
  [ctx id]
  (let [dir (:directory ctx)
        lib (when dir (library/find-library dir (:library id)))
        found (when (satisfies? library/Library lib) (library/find-book lib (:slug id)))]
    (= :ambiguous-book (when (library/diagnostic? found) (:type found)))))

(defn- resolve-edge
  "`{:edge Edge :diagnostics [...]}` for the reference `written`, in the
  book `id`, whose unresolved edge is `edge` (`:from`, `:to {:written
  :passage?}`, `:kind`, `:position?`): resolved in `ctx`, each diagnostic
  placed by `locate`. A `!quote` of the book itself is a `:quote-cycle`,
  unless its slug is ambiguous (`ambiguous-slug?`): a build of the book
  then quotes nothing as itself, and the quote is the `:ambiguous-book`
  that resolving it answers (TASK-259)."
  [ctx id written edge locate]
  (let [kind (:kind edge)
        parsed (book-ref/parse-book-ref written)
        dangling (fn [failure to-id]
                   {:edge (cond-> (assoc edge :status :dangling :problem (:type failure))
                            to-id (assoc-in [:to :id] to-id))
                    :diagnostics [(locate failure)]})]
    (cond
      (book-ref/malformed? parsed) (dangling parsed nil)

      (and (= :quote kind) (book-ref/same-book? id (:id parsed)) (not (ambiguous-slug? ctx id)))
      (dangling {:type :quote-cycle
                 :cycle [(book-ref/format-book-id id) (book-ref/format-book-id (:id parsed))]
                 :message (str "quote cycle: !quote " written
                               " names a book that is already being quoted, via "
                               (book-ref/format-book-id id) " -> "
                               (book-ref/format-book-id (:id parsed)))}
                id)

      :else
      (let [{:keys [failure warnings] to-id :id} ((:resolve (:books ctx)) written parsed)]
        (if failure
          (dangling failure (known-book failure))
          {:edge (assoc edge :status :resolved :to (assoc (:to edge) :id to-id))
           :diagnostics (mapv locate warnings)})))))

(defn- resolve-reference
  "`{:edge Edge :diagnostics [...]}` for one reference `{:node :kind
  :label :order}` written in the book `id`."
  [ctx id {:keys [node kind label order]}]
  (let [written (let [r (:ref node)] (if (string? r) r (pr-str r)))
        parsed (book-ref/parse-book-ref written)
        edge (cond-> {:from (cond-> {:id id}
                              label (assoc :passage label)
                              (nat-int? order) (assoc :order order))
                      :to (cond-> {:written written}
                            (and (not (book-ref/malformed? parsed)) (:passage parsed))
                            (assoc :passage (:passage parsed)))
                      :kind kind}
               (position node) (assoc :position (position node)))]
    (resolve-edge ctx id written edge #(located node written %))))

;; --- the record --------------------------------------------------------------

(defn- failed-record
  "The edge-less record of `book` (id `id`), which failed with
  `diagnostic`, placed at the book's file."
  [book id diagnostic]
  (let [{:keys [canonical links]} (try (book-files book) (catch Throwable _ {}))
        digests (try (book-digests book) (catch Throwable _ {}))
        file (book-file book)]
    {:id id
     :hash (files-hash canonical digests diagnostic)
     :files (into (set canonical) links)
     :labels {}
     :edges []
     :diagnostics [(cond-> diagnostic
                     (and file (nil? (:source-file diagnostic))) (assoc :source-file file))]}))

(defn- labels
  "Label -> hash of the labelled block, for a resolved book."
  [{:keys [document ids]}]
  (into {}
        (map (fn [[label path]] [label (sha256-hex (canonical-form (get-in document path)))]))
        (:blocks ids)))

(defn- stale-shelf?
  "True when the resolved `edge` writes a shelf path that is not its
  target's: the one warning a resolved reference carries (a `:stale-shelf`
  info)."
  [edge]
  (let [parsed (book-ref/parse-book-ref (:written (:to edge)))
        shelf (when-not (book-ref/malformed? parsed) (:shelf (:id parsed)))]
    (boolean (and (seq shelf) (not= shelf (:shelf (:id (:to edge))))))))

(defn- edge-diagnostic-count
  "How many diagnostics `resolve-edge` gave `edge`: one for a dangling
  edge, one for a resolved one with a stale shelf path, else none."
  [edge]
  (if (= :dangling (:status edge)) 1 (if (stale-shelf? edge) 1 0)))

(defn- split-diagnostics
  "`[own references]`: `record`'s diagnostics split where `scan-resolved`'s
  order puts the references' runs (one per edge, `edge-diagnostic-count`
  long, last); all its own when they cannot split that way."
  [record]
  (let [diagnostics (vec (:diagnostics record))
        start (- (count diagnostics) (reduce + 0 (map edge-diagnostic-count (:edges record))))]
    (if (neg? start)
      [diagnostics []]
      [(subvec diagnostics 0 start) (subvec diagnostics start)])))

(defn- merged-record
  "The one record of several listed books that share the canonical id
  (`x.hdoc` beside `x/book.hdoc`, a `:duplicate-slug`; TASK-250), from
  their own `records`, in the order of their sources (`book-record`).
  The graph is keyed by BookId, and no reference can tell the books
  apart (`find-book` answers `:ambiguous-book` for the id), so they are
  one record holding all of
  them, never one book's files beside another's labels:

  - `:files`, every file of every book, so a change to any of them
    rescans the id;
  - `:edges`, every book's references, each placed in its own file;
  - `:diagnostics`, every book's own, then every book's references' runs
    -- `scan-resolved`'s order over the merged `:edges`, which `refresh`
    relies on;
  - `:labels` and `:label-spans` empty: no reference can reach a label of
    either book, and their document orders cannot share one span table;
  - `:hash`, over the books' own hashes in order."
  [records]
  (let [parts (mapv split-diagnostics records)]
    {:id (:id (first records))
     :hash (sha256-hex (str "same-id\n" (str/join "\n" (map :hash records))))
     :files (into #{} (mapcat :files) records)
     :labels {}
     :label-spans {}
     :edges (into [] (mapcat :edges) records)
     :diagnostics (-> [] (into (mapcat first) parts) (into (mapcat second) parts))}))

(defn- scan-resolved
  "The record of `book` (id `id`), whose resolved document is `resolved`.

  Its `:diagnostics` are in a fixed order that `refresh` relies on
  (`re-resolved-record`): the book's load warnings, then its own resolve
  diagnostics, then the diagnostics of its references -- for each edge,
  in `:edges` order, exactly the ones `resolve-edge` gave it: one for a
  dangling edge, one `:stale-shelf` info for a resolved edge whose shelf
  path is stale, none otherwise. Keep that order when changing this."
  [ctx book id resolved]
  (let [document (library/document book)
        {refs :references label-spans :label-spans} (outline document)
        scanned (mapv #(resolve-reference ctx id %) refs)
        {:keys [canonical links]} (book-files book)]
    {:id id
     :hash (files-hash canonical (book-digests book) document)
     :files (into canonical links)
     :labels (labels resolved)
     :label-spans label-spans
     :edges (mapv :edge scanned)
     :diagnostics (-> []
                      (into (:load-warnings resolved))
                      (into (:diagnostics resolved))
                      (into (mapcat :diagnostics) scanned))}))

(defn- throwable-message [^Throwable t] (or (ex-message t) (.getName (class t))))

(defn scan-book
  "The book record of `book` (doc-11, \"Book record\";
  `haselnuss.library.graph`'s `BookRecord`), scanned in `ctx` (`context`).
  See the namespace docstring. Never throws for a document problem: a
  book that fails gives an edge-less record carrying the diagnostic."
  [ctx book]
  (let [id (:id (library/book-entry book))
        own-passages (or (:own-passages ctx) (:passages ctx))]
    (when-let [on-scan (:on-scan-book ctx)] (on-scan id))
    (try
      (let [resolved (passage/resolved-book book own-passages)]
        (if (library/diagnostic? resolved)
          (failed-record book id resolved)
          (scan-resolved ctx book id resolved)))
      (catch Throwable t
        (failed-record book id {:type :book-failed
                                :book (book-ref/format-book-id id)
                                :message (str "book " (book-ref/format-book-id id)
                                              " could not be scanned: " (throwable-message t))}))
      (finally
        ;; A book's load warnings are queued on the passage context for a
        ;; build to report once; a scan reports them through each book's
        ;; own record instead, so the queue is only drained here.
        (run! passage/take-book-diagnostics! (distinct [(:passages ctx) own-passages]))))))

;; --- scan ----------------------------------------------------------------------

(defn- failed-library-names
  "The names of the libraries `dir` names but could not load, in the
  order of its load problems."
  [dir]
  (if (satisfies? library/LibraryDiagnostics dir)
    (distinct (keep :library (library/library-diagnostics dir)))
    []))

(defn directory-library-names
  "Every library name the directory `dir` holds -- the usable ones, in
  directory order, then the ones that failed to load -- or `[]` for no
  directory. What `scan` covers when its scope is nil."
  [dir]
  (if (nil? dir)
    []
    (vec (distinct (concat (library/library-names dir) (failed-library-names dir))))))

(defn- scope-names
  "The library names `scope` covers in `dir`, in directory order. Throws
  `::unknown-library` (doc-11: exit 2) when it names one the directory
  does not."
  [dir scope]
  (when-not (or (nil? scope) (and (coll? scope) (not (map? scope)) (every? string? scope)))
    (throw (ex-info (str "scan scope must be nil or a collection of library names, got "
                         (binding [*print-length* 8 *print-level* 3] (pr-str scope)))
                    {:type ::invalid-scope})))
  (let [known (directory-library-names dir)]
    (if (nil? scope)
      known
      (let [wanted (set scope)
            unknown (sort (remove (set known) wanted))]
        (when (seq unknown)
          (throw (ex-info (str "unknown library " (pr-str (first unknown)) "; known: "
                               (if (seq known) (str/join ", " (sort known)) "(none)"))
                          {:type ::unknown-library
                           :libraries (vec unknown)
                           :known (vec (sort known))})))
        (filterv wanted known)))))

(defn- unavailable-record
  "The record of library `lib-name`, which could not be used: `diagnostic`
  is its `:unavailable-library` (`find-library`'s), placed at the
  directory entry its load problem (`:cause`) is located at."
  [lib-name diagnostic]
  (let [{:keys [file line]} (:cause diagnostic)]
    {:name lib-name
     :status :unavailable
     :diagnostics [(cond-> diagnostic
                     (string? file) (assoc :source-file file)
                     (pos-int? line) (assoc :source-line line))]}))

(defn- url-template-problem
  "An `:invalid-url-template` diagnostic for library `lib-name` when
  `dir` answers a template for it that is not valid, else nil --
  `haselnuss.library.references`' own, which a build reports once and a
  scan reports on the library's record."
  [dir lib-name]
  (let [template (when (satisfies? library/UrlTemplates dir) (library/url-template dir lib-name))
        problem (when (some? template) (url/template-problem template))]
    (when problem
      {:type :invalid-url-template
       :level :error
       :library lib-name
       :url template
       :message (str "library " (pr-str lib-name) ": " problem
                     "; its links and quotes have no URL")})))

(defn- library-root
  [lib]
  (when (fs/filesystem-library? lib)
    (canonical-path (fs/library-root lib))))

(defn- entry-record
  "The edge-less record of the listed book `entry` that `find-book` could
  not single out (it answered `found`): a library without `ListedBooks`
  whose slugs collide."
  [entry found]
  (let [id (:id entry)
        diagnostic (if (library/diagnostic? found)
                     found
                     {:type :book-failed
                      :book (book-ref/format-book-id id)
                      :message (str "book " (book-ref/format-book-id id)
                                    " is listed, but its library does not find it")})]
    {:id id
     :hash (files-hash #{} {} diagnostic)
     :files #{}
     :labels {}
     :edges []
     :diagnostics [diagnostic]}))

(defn- library-books
  "`[Book-or-record ...]` for every book `lib` lists: a Book to scan, or
  the record of one that cannot be scanned (`entry-record`)."
  [lib]
  (if (satisfies? library/ListedBooks lib)
    (library/listed-books lib)
    (for [entry (library/list-books lib)
          :let [found (library/find-book lib (:slug (:id entry)))]]
      (if (and (satisfies? library/Book found) (= (:id entry) (:id (library/book-entry found))))
        found
        (entry-record entry found)))))

(defn- scan-directory
  "The directory `scan` lists libraries and books from: the context's
  `:own-directory` (see `context`), else its `:directory`."
  [ctx]
  (if (contains? ctx :own-directory) (:own-directory ctx) (:directory ctx)))

(defn- library-listing
  "`{:record LibraryRecord :books [Book-or-record ...]}` for library
  `lib-name` in `ctx`: its library record, and every book it lists (a
  Book to scan, or the record of one that cannot be, `library-books`) in
  listing order -- none when it is unavailable."
  [ctx lib-name]
  (let [dir (scan-directory ctx)
        lib (library/find-library dir lib-name)]
    (cond
      (library/diagnostic? lib)
      {:record (unavailable-record lib-name lib) :books []}

      (not (satisfies? library/Library lib))
      ;; a directory that names a library it then does not find
      {:record {:name lib-name
                :status :unavailable
                :diagnostics [{:type :unavailable-library
                               :level :warning
                               :library lib-name
                               :message (str "library " (pr-str lib-name)
                                             " is named by the library directory,"
                                             " which then does not find it")}]}
       :books []}

      :else
      (let [listed (try {:books (vec (library-books lib))
                         :diagnostics (if (satisfies? library/LibraryDiagnostics lib)
                                        (vec (library/library-diagnostics lib))
                                        [])}
                        (catch Throwable t
                          {:failure {:type :unavailable-library
                                     :level :warning
                                     :library lib-name
                                     :message (str "library " (pr-str lib-name)
                                                   " could not be listed: " (throwable-message t))}}))]
        (if-let [failure (:failure listed)]
          {:record {:name lib-name :status :unavailable :diagnostics [failure]} :books []}
          (let [template-problem (url-template-problem dir lib-name)
                root (library-root lib)]
            {:record (cond-> {:name lib-name
                              :status :available
                              :diagnostics (cond-> (:diagnostics listed)
                                             template-problem (conj template-problem))}
                       root (assoc :root root))
             :books (:books listed)}))))))

(defn- listed-id
  "The BookId of a listed Book-or-record."
  [book]
  (if (satisfies? library/Book book) (:id (library/book-entry book)) (:id book)))

(defn- listed-groups
  "`[[BookId [Book-or-record ...]] ...]`: the listed `books` grouped by
  id, in the order each id is first listed, each group in listing order.
  A group of more than one is a `:duplicate-slug` within one canonical id
  (`merged-record`)."
  [books]
  (let [by-id (group-by listed-id books)]
    (mapv (fn [id] [id (get by-id id)]) (distinct (map listed-id books)))))

(defn- book-record
  "The record of the listed Book-or-records `books`, which share one id,
  scanned in `ctx`: the one book's own, or `merged-record` of theirs.
  Only books from distinct sources (`passage/book-source`: a filesystem
  book's path on disk) are merged; books that cannot be told apart -- a
  generated shelf listing one slug twice -- read one cached document, so
  they are one book, the last one listed (as before TASK-250). The
  merged books are taken in the order of their sources, not their
  listing order, so the record -- its `:edges`, `:diagnostics` and
  `:hash` -- and every `--check` answer read from it (the first chain
  found through it) are the same however the library lists them
  (TASK-259)."
  [ctx books]
  (let [one (fn [book] (if (satisfies? library/Book book) (scan-book ctx book) book))
        source #(when (satisfies? library/Book %) (passage/book-source %))
        last-of (reduce #(assoc %1 (source %2) %2) {} books)
        distinct-books (mapv last-of (sort-by str (distinct (map source books))))]
    (if (= 1 (count distinct-books))
      (one (first distinct-books))
      (merged-record (mapv one distinct-books)))))

(defn- scan-library
  "`g` with library `lib-name`'s record and the record of every book it
  lists, scanned in `ctx`."
  [ctx g lib-name]
  (let [{:keys [record books]} (library-listing ctx lib-name)]
    (reduce (fn [g [_ group]] (graph/with-book g (book-record ctx group)))
            (graph/with-library g record)
            (listed-groups books))))

(defn scan
  "A reference graph of every book in the libraries `scope` names, scanned
  in `ctx` (`context`; doc-11, \"Scan pipeline & caching\"). See the
  namespace docstring, \"What `scan` does\".

  `scope`: a collection of library names, or nil for every library the
  directory names (`directory-library-names`). A name the directory does
  not hold throws `ex-info` `::unknown-library` (doc-11: exit 2), before
  anything is scanned. `opts`: `:registry`, `:backend` and `:config`, the
  graph backend (`haselnuss.library.graph.registry/new-graph`; default
  `:memory-index`).

  The graph satisfies `haselnuss.library.graph/LibraryIndex` too: one
  library record per library in scope."
  ([ctx scope] (scan ctx scope {}))
  ([ctx scope {:keys [backend config] reg :registry
               :or {reg registry/default-registry backend registry/default-backend}}]
   (let [names (scope-names (scan-directory ctx) scope)]
     (reduce #(scan-library ctx %1 %2)
             (libraries/library-indexed (registry/new-graph reg backend config))
             names))))

;; --- refresh -------------------------------------------------------------------

(defn- under?
  "True when the canonical path `path` is the folder `root` or inside it."
  [^String root ^String path]
  (or (= root path)
      (str/starts-with? path (if (str/ends-with? root File/separator) root (str root File/separator)))))

(defn- fresh-root
  "The canonical root of library `lib-name` in `ctx`'s directory, when it
  is a filesystem library there; else nil."
  [ctx lib-name]
  (let [dir (scan-directory ctx)
        lib (when dir (library/find-library dir lib-name))]
    (when (satisfies? library/Library lib) (library-root lib))))

(defn- libraries-to-relist
  "The names of the libraries `refresh` lists again: the library of every
  book in `claimed`, and every recorded library whose root -- the one its
  record holds, or the one it has in `ctx`'s fresh directory (an
  `:unavailable` record holds none) -- contains one of `paths`."
  [ctx g paths claimed]
  (let [recorded (graph/indexed-libraries g)]
    (into (into #{} (map :library) claimed)
          (for [lib-name (keys recorded)
                :let [roots (keep identity [(:root (get recorded lib-name)) (fresh-root ctx lib-name)])]
                :when (some (fn [p] (some #(under? % p) roots)) paths)]
            lib-name))))

(defn- relist
  "`state` (`{:graph :rescanned :vanished :listing-changed}`) after
  library `lib-name` is listed again in `ctx`: its record replaced, the
  books it no longer lists removed, and every book it lists that is in
  `claimed`, was not indexed, or now comes from a file its record does
  not hold, scanned (the books listed under one id as one record, as in
  `scan`)."
  [ctx claimed {g :graph :as state} lib-name]
  (let [{:keys [record books]} (library-listing ctx lib-name)
        groups (listed-groups books)
        listed (into {} groups)
        order (mapv first groups)
        old-record (get (graph/indexed-libraries g) lib-name)
        old-books (graph/indexed-books g)
        old-ids (into #{} (filter #(= lib-name (:library %))) (keys old-books))
        vanished (remove (set order) old-ids)
        ;; a book listed under an id it had before, but from a file its
        ;; record does not hold (`x.hdoc` beside a new `x/book.hdoc`), is
        ;; another book
        moved? (fn [id]
                 (some (fn [book]
                         (let [file (when (satisfies? library/Book book) (book-file book))]
                           (and (some? file)
                                (not (contains? (:files (get old-books id)) (canonical-path file))))))
                       (get listed id)))
        rescan (filterv #(or (contains? claimed %) (not (contains? old-ids %)) (moved? %)) order)
        g (reduce graph/without-book (graph/with-library g record) vanished)
        g (reduce #(graph/with-book %1 (book-record ctx (get listed %2))) g rescan)]
    (cond-> (-> state
                (assoc :graph g)
                (update :rescanned into rescan)
                (update :vanished into vanished))
      (or (not= record old-record) (not= old-ids (set order)))
      (update :listing-changed conj lib-name)
      ;; the directory's usable libraries changed, which every
      ;; `:unknown-library` message lists
      (not= (:status record) (:status old-record))
      (assoc :status-changed? true))))

(defn- edge-locate
  "`located` for the reference `edge` records: its diagnostics placed at
  its `:position`, as `resolver/locate-warning` (and `located`'s column)
  placed them at its node."
  [edge]
  (let [written (:written (:to edge))
        {:keys [file line]} (:position edge)]
    (fn [diagnostic]
      (let [d (merge {:level :warning} diagnostic {:ref written})]
        (if (some? line)
          (-> (cond-> (assoc d :source-line line)
                file (-> (assoc :source-file file)
                         (update :message #(str file ":" line ": " %))))
              (with-column (:position edge)))
          d)))))

(defn- slice-matches?
  "True when `diagnostics` can be the ones `resolve-edge` gave `edge`:
  each about its reference as written, at its line."
  [edge diagnostics]
  (every? #(and (= (:written (:to edge)) (:ref %))
                (= (:line (:position edge)) (:source-line %)))
          diagnostics))

(defn- re-resolved-record
  "The record `record` (edges `edges`) of a book that was not rescanned,
  with every edge `stale?` answers true for resolved again in `ctx` and
  its diagnostics in place of the old ones -- what scanning the book
  again would give, since nothing else in it depends on another book.

  Relies on `scan-resolved`'s diagnostics order: everything before the
  references' diagnostics is the book's own and is kept; the rest is one
  run per edge, in edge order, `edge-diagnostic-count` long, each about
  the edge's reference as written at its line (`slice-matches?`). nil
  when the diagnostics do not split that way -- a record not built by
  `scan-book`, or one whose order changed -- so the caller scans the book
  instead."
  [ctx record edges stale?]
  (let [counts (mapv edge-diagnostic-count edges)
        diagnostics (vec (:diagnostics record))
        start (- (count diagnostics) (reduce + 0 counts))
        slices (when (<= 0 start)
                 (loop [i start [c & cs] counts out []]
                   (if (nil? c) out (recur (+ i c) cs (conj out (subvec diagnostics i (+ i c)))))))]
    (when (and slices (every? true? (map slice-matches? edges slices)))
      (let [id (:id record)
            redone (mapv (fn [edge slice]
                           (if (stale? edge)
                             (let [{:keys [to] :as bare} (dissoc edge :status :problem)]
                               (resolve-edge ctx id (:written to)
                                             (assoc bare :to (select-keys to [:written :passage]))
                                             (edge-locate edge)))
                             {:edge edge :diagnostics slice}))
                         edges slices)]
        (assoc record
               :edges (mapv :edge redone)
               :diagnostics (into (subvec diagnostics 0 start) (mapcat :diagnostics) redone))))))

(defn- listed-books-of
  "The listed Book-or-records of the book `id` in `ctx` (every one under
  that id, as `scan` records them), or nil."
  [ctx id]
  (not-empty (filterv #(= id (listed-id %)) (:books (library-listing ctx (:library id))))))

(defn- refresh-dependents
  "`g` with the records of the books that were not rescanned, but have an
  edge whose resolution the refresh may have changed, brought up to date:
  an edge into a book in `touched` (by book key, so a book that now
  shares a slug is found too), a dangling edge naming no book into a
  library in `listing-changed` (its `:unknown-book`, `:scripts-disabled`
  ... follows the listing), and, when `status-changed?` (a library became
  available or unavailable), every `:unknown-library` edge, whose message
  lists the directory's usable libraries. Those edges are resolved again
  in `ctx` (`re-resolved-record`); a book whose record cannot be split
  per edge is scanned again instead."
  [ctx g touched listing-changed status-changed?]
  (let [keys-touched (into #{} (map book-ref/book-key) touched)
        stale? (fn [edge]
                 (let [k (graph/target-key edge)]
                   (or (contains? keys-touched k)
                       (and (= :dangling (:status edge))
                            (nil? (:id (:to edge)))
                            (some? k)
                            (contains? listing-changed (first k)))
                       (and status-changed? (= :unknown-library (:problem edge))))))
        by-key (into #{}
                     (comp (mapcat #(graph/edges-to g (book-ref/book-id (:library %) (:slug %))))
                           (map (comp :id :from)))
                     touched)
        by-library (when (seq listing-changed)
                     (filter (fn [id] (some stale? (graph/edges-from g id)))
                             (keys (graph/indexed-books g))))
        candidates (sort-by book-ref/format-book-id (remove touched (into by-key by-library)))
        books (graph/indexed-books g)]
    (reduce (fn [g id]
              (let [edges (graph/edges-from g id)]
                (if-not (some stale? edges)
                  g
                  (if-let [record (re-resolved-record ctx (assoc (get books id) :id id :edges edges)
                                                      edges stale?)]
                    (graph/with-book g record)
                    (if-let [books (listed-books-of ctx id)]
                      (graph/with-book g (book-record ctx books))
                      g)))))
            g
            candidates)))

(defn- refresh-files
  "`refresh` for the canonical `paths`, none of them the directory file."
  [ctx g paths]
  (let [claimed (into #{} (mapcat #(graph/books-for-file g %)) paths)
        relisted (libraries-to-relist ctx g paths claimed)
        in-order (concat (filter relisted (directory-library-names (scan-directory ctx)))
                         (sort (remove (set (directory-library-names (scan-directory ctx))) relisted)))
        {g2 :graph :keys [rescanned vanished listing-changed status-changed?]}
        (reduce (partial relist ctx claimed)
                {:graph g :rescanned [] :vanished [] :listing-changed #{} :status-changed? false}
                in-order)
        touched (into (set rescanned) vanished)
        g2 (refresh-dependents ctx g2 touched listing-changed status-changed?)]
    ;; resolving references queues the target books' load warnings for a
    ;; build to report; a refresh reports them on the books' own records
    (run! passage/take-book-diagnostics! (distinct (keep identity [(:passages ctx) (:own-passages ctx)])))
    {:graph g2
     :rebuild (into (rebuild/affected-by g touched) (rebuild/affected-by g2 touched))}))

(defn refresh
  "The graph `g` (built by `scan`, or by `refresh`) brought up to date
  with the files `changed-files` (paths; created, edited or deleted), in
  the fresh context `ctx` (`context`): `{:graph g' :rebuild #{BookId}}`
  (doc-11, \"refresh, for changed files\"). What a future daemon calls on
  file events.

  1. Each path is canonicalised both ways `:files` holds one
     (`lookup-paths`: the file it reaches, `canonical-path`, and, should
     it be a symbolic link, the link itself, `link-path`; TASK-250) and
     mapped to the books claiming either (`books-for-file`). So retargeting a symlinked
     include, a symlinked folder on its way, or a symlinked entry file
     rescans the books that read through it, even when a watcher reports
     only the link.
  2. A library is listed again (`list-books`, through `ctx`'s fresh
     directory) when it owns a claimed book, or when its root -- in its
     record, or in the fresh directory, which is how a path under a
     library that was `:unavailable` (no `:root`) is placed -- contains a
     changed path. So a new book file, a deleted or renamed entry file,
     and an edited `shelf.clj` or `:data` file (which can add, drop or
     rename the shelf's books) are all seen. Its library record is
     replaced (`with-library`, so its library-level diagnostics follow
     the files), and the books it no longer lists removed
     (`without-book`).
  3. Only the claimed books it still lists and the books it newly lists
     are scanned (`scan-book`); every other record is kept -- except
     that a kept book's edges whose resolution may have changed (an edge
     into a rescanned, new or removed book, by book key; a dangling edge
     naming no book into a library whose listing changed) are resolved
     again from the edge itself (its reference as written and position),
     without scanning the book: so the refreshed graph is the one a
     fresh `scan` of the same files gives (a link into a deleted book
     dangles, a dangling one into a new book resolves).
  4. `:rebuild` is `rebuild/affected-by` of the rescanned and removed
     books on the old graph merged with the same on the new one: the
     dependents of a deleted book, and the quoters of a book that just
     gained a quote, are both covered.

  A library outside the graph's scope (no library record) is never
  listed.

  The directory file: when `changed-files` holds the file `ctx` loaded
  its directory from (`context`'s `:directory-file`), libraries may have
  been added, removed, moved or reconfigured, which re-listing cannot
  follow (the fresh directory and the kept records would disagree). The
  whole graph is then scanned afresh -- `scan` with `opts`' `:scope`
  (default: the libraries the graph has records for that the new
  directory still names; pass nil to take every library it names) and
  `:registry`/`:backend`/`:config` -- and `:rebuild` is every book of
  the old graph and of the new one: everything may have changed. A
  context given a `:directory` without its `:directory-file` cannot tell,
  and refreshes as above."
  ([ctx g changed-files] (refresh ctx g changed-files {}))
  ([ctx g changed-files opts]
   (let [paths (into #{} (mapcat lookup-paths) changed-files)]
     (if (and (some? (:directory-file ctx)) (contains? paths (:directory-file ctx)))
       (let [names (directory-library-names (scan-directory ctx))
             scope (if (contains? opts :scope)
                     (:scope opts)
                     (filterv (set (keys (graph/indexed-libraries g))) names))
             g2 (scan ctx scope (select-keys opts [:registry :backend :config]))]
         {:graph g2
          :rebuild (into (set (keys (graph/indexed-books g))) (keys (graph/indexed-books g2)))})
       (refresh-files ctx g paths)))))

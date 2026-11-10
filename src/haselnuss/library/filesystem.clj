(ns haselnuss.library.filesystem
  "The `:filesystem` library provider (doc-10, \"FileSystem library
  provider\"; TASK-151): a folder tree of `.hdoc` books.

  `filesystem-library` takes a provider entry config, `{:name \"reformed\"
  :path \"/home/me/books/reformed\"}`, so it is registered as the
  `:filesystem` provider's `(fn [entry-config] Library)` unchanged. The
  library directory loader has already made `:path` absolute, expanded a
  leading `~` and normalised it; this namespace does none of that again,
  and only makes a relative `:path` from an in-process caller absolute
  against the working directory.

  ## Layout

      reformed/                        <- library root
        confessions/reformed/          <- shelf confessions/reformed
          heidelberg.hdoc              <- single-file book, slug heidelberg
          westminster/book.hdoc        <- book folder, slug westminster
          westminster/chapters/1.hdoc  <- part of westminster via !include
        _drafts/                       <- ignored

  1. A folder holding `book.hdoc` is a book folder; its slug is the
     folder's name and nothing inside it is scanned for books.
  2. Any other `*.hdoc` directly in a shelf is a single-file book; its
     slug is the file name without `.hdoc`.
  3. Any other folder is a shelf; the library root is always a shelf, so a
     `book.hdoc` at the root is reported as an `:invalid-book-name`.
  4. A name starting with `.` or `_` is ignored, as is any file that is
     not `*.hdoc`.
  5. A book or shelf name that is not the grammar's `name` is skipped with
     an `:invalid-book-name` diagnostic (a shelf with everything under it).
  6. The slug always comes from the name on disk, never from front matter.
  7. A folder holding `shelf.clj` (and no `book.hdoc`, which makes it a
     book folder) is a generated shelf (TASK-158,
     `haselnuss.library.generated`): its books come from the script, and
     nothing else in it is scanned -- its other files are the script's
     data. The library root may be one. A generated book's slug comes
     from the script and follows the same name rule, and takes part in
     the slug index like any other, so a collision with an on-disk book
     is a `:duplicate-slug`.

  ## Scripts

  A generated shelf's script runs only when the entry config says
  `:scripts true` (`scripts: true` in the library directory file); a
  `scripts` that is neither a boolean nor absent makes the config an
  `:invalid-library`. Without it the shelf lists no books and reports
  `:scripts-disabled`. A shelf that lists no books (scripts disabled, or
  `:script-failed` as a whole) also changes what `find-book` answers for
  a slug no listed book has: that shelf's diagnostic, as
  `:scripts-disabled`/`:script-failed` with the shelf's own under
  `:cause`, rather than nil -- the missing book may well be one of its
  own. Generated books are not filesystem books (`filesystem-book?`):
  they have no entry file, include warnings or bibliography.

  ## Per build

  A library value is built once per build (the loader calls the provider
  once per directory load). Its scan -- the walk, every book's front
  matter, the slug index and the scan diagnostics -- is one `delay`, run on
  the first `list-books`, `find-book`, `shelf-tree` or
  `library-diagnostics` call and cached with the value. Each Book in the
  index holds its own `delay` for its document, so a document is parsed at
  most once per build however many times its book is looked up.

  ## Front matter only

  Listing reads each book's front matter and nothing else: the entry file
  is read a character at a time only up to the closing fence, so its body
  is never read, let alone parsed. The fences are the parser's own
  (`parser/front-matter-opening`, `parser/front-matter-closing?`) on lines
  split as the parser splits them -- CRLF line endings and a leading BOM
  normalised as `parser/normalize-source` does -- so what listing calls
  front matter is exactly what `parse` does. An opening fence not closed within
  `max-front-matter-chars` is reported instead of read to the end.

  `title` comes from `haselnuss.parser/front-matter`, so the listed title
  is the same inlines the document's own `meta.title` will be. `lang`,
  `ddc` and `tags` are read from the same YAML and listed only as written:
  `lang` a non-empty string, `ddc` a string (an unquoted `230.10` is a
  YAML number, `230.1`), `tags` a string (a one-tag list) or a list of
  strings. Anything else is left out of the entry with an
  `:invalid-front-matter` diagnostic rather than silently changed.

  ## Documents

  `document` parses the entry file with the parse fn (default
  `haselnuss.parser/parse`, with `:source` the file's path) and expands
  its `!include`s through `haselnuss.resolver/expand-includes`, rooted at
  the entry file, so a book folder's chapters resolve relative to the book
  as they do for a standalone build. As there, an `!include` may reach a
  file outside the book folder, or the library; v1 does not confine it
  (doc-10). The include warnings that pass
  produces (`:unreadable-include`, `:include-cycle`) have no place in a
  Document; `include-warnings` hands them back for the caller to report.
  Filesystem books are not Locatable in v1.

  The same load reads the book's own bibliography -- the file its front
  matter's `bibliography` names, relative to its entry file, as a
  standalone build finds it relative to the document -- for
  `bibliography`; one that cannot be read is an `:unreadable-bibliography`
  among `load-warnings` (TASK-153). So is one that is not a path: the
  parser's located error for it (TASK-209) is a standalone build's, and a
  book that a host build only quotes from warns instead.

  ## Parse vocabulary

  A referencing build hands its kind names to the provider as the
  `:kinds` option (`provider` makes the registry entry). With them, every
  file of a book parses with `{:kinds <the build's> :cite-keys <the
  book's own bibliography keys>}`: the kinds are the build's because the
  book is numbered and named with the build's lexicon, and the cite keys
  are the book's because its citations resolve against its own
  bibliography. Without `:kinds` the parse is the lenient,
  vocabulary-free one.

  ## Identity

  Books are indexed by `book-ref/book-key`. Two books with one slug are
  both listed; `library-diagnostics` reports a `:duplicate-slug` naming
  both canonical ids (`:books`) and both paths on disk (`:paths`), and
  `find-book` on that slug answers `:ambiguous-book` -- whatever shelf a
  reference wrote, since `find-book` is given the slug alone.

  ## Failure

  Nothing here throws out of a protocol method. A config that cannot make
  a library (a bad name, a missing or non-folder `:path`) is an
  `:invalid-library` diagnostic instead of a Library. Beyond doc-10's
  `:invalid-book-name` and `:duplicate-slug`, the scan reports:

  - `:invalid-front-matter` -- a book's front matter could not be read or
    parsed, was not closed within `max-front-matter-chars`, or has a
    `lang`/`ddc`/`tags` value that cannot be listed as written (`:field`
    names which). The book is still listed (titled by its slug when the
    front matter itself failed), so a reference to it still finds it.
  - `:unreadable-shelf` -- a folder that could not be listed.

  A `document` whose entry file (or an included file) cannot be read or
  parsed -- malformed front matter included -- answers a `:book-failed`
  diagnostic naming the book and the error."
  (:require [clj-yaml.core :as yaml]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [haselnuss.book-ref :as book-ref]
            [haselnuss.library :as library]
            [haselnuss.library.generated :as generated]
            [haselnuss.parser :as parser]
            [haselnuss.resolver :as resolver])
  (:import (java.io File)))

;; --- names -------------------------------------------------------------------

(def ^:private book-file-name "The entry file that makes a folder a book folder." "book.hdoc")

(def ^:private extension ".hdoc")

(defn- ignored? [^String file-name]
  (or (str/starts-with? file-name ".") (str/starts-with? file-name "_")))

(defn- valid-name? [s] (library/valid-name? s))

(defn- throwable-message [^Throwable t] (or (ex-message t) (.getName (class t))))

(defn- canonical [id] (book-ref/format-book-id id))

;; --- front matter ------------------------------------------------------------

(def ^:private max-front-matter-chars
  "How much of an entry file listing reads looking for the end of its
  front matter. A front matter is a few lines of YAML; a file whose
  opening `---` is not closed within this is reported rather than read to
  its end, so listing stays cheap even for a huge book."
  65536)

(defn- read-line!
  "The next line of `r`, split as the parser splits `normalize-source`d
  text: on `\\n`, a `\\r` right before it dropped with it (a `\\r\\n` is
  one line ending, TASK-162) and any other `\\r` kept. nil at the end of
  the stream, or `::too-long` once the line would pass `limit` characters.
  Reads one character at a time, so nothing past the line is consumed
  from `r`."
  [^java.io.Reader r limit]
  (let [sb (StringBuilder.)]
    (loop []
      (let [c (.read r)
            n (.length sb)]
        (cond
          (= -1 c) (when (pos? n) (str sb))
          (= 10 c) (if (and (pos? n) (= \return (.charAt sb (dec n))))
                     (.substring sb 0 (dec n))
                     (str sb))
          (>= n limit) ::too-long
          :else (do (.append sb (char c)) (recur)))))))

(defn- front-matter-text
  "The raw YAML of the front matter `r` starts with, nil when it has none,
  or `::too-long` when its opening fence is not closed within
  `max-front-matter-chars`. The fences are the parser's own
  (`parser/front-matter-opening`, `parser/front-matter-closing?`) on lines
  split the parser's way, after a leading `parser/byte-order-mark` is
  dropped from the first as `parser/normalize-source` drops it (TASK-162):
  a leading blank line or an unclosed fence is no front matter here
  exactly as it is to `parse`, and CRLF fences or a BOM are front matter
  to both. Reads nothing past the closing fence."
  [r]
  (when (= parser/front-matter-opening
           (let [line (read-line! r max-front-matter-chars)]
             (if (and (string? line) (str/starts-with? line parser/byte-order-mark))
               (subs line (count parser/byte-order-mark))
               line)))
    (loop [lines [] used 0]
      (let [line (read-line! r (- max-front-matter-chars used))]
        (cond
          (nil? line) nil
          (= ::too-long line) ::too-long
          (parser/front-matter-closing? line) (str/join "\n" lines)
          :else (recur (conj lines line) (+ used 1 (count line))))))))

(defn- default-open-reader
  "A UTF-8 reader over `file`, decoding as `slurp` does, so listing and
  the document load see the same text."
  [^File file]
  (io/reader file :encoding "UTF-8"))

(defn- field-problem
  "A message for a front-matter metadata field that is dropped rather
  than changed silently."
  [field reason]
  {:field field :reason reason})

(defn- metadata-fields
  "`[fields problems]` for the optional `lang`, `ddc` and `tags` in the
  YAML map `data`. Each value is kept only as written: a `ddc` YAML read
  as a number (`230.10` -> `230.1`) or a blank `lang` would change it, and
  `tags` that are not strings cannot be listed, so each is dropped with a
  problem instead."
  [data]
  (let [{:keys [lang ddc tags]} data
        problems (cond-> []
                   (and (contains? data :lang) (not (and (string? lang) (not (str/blank? lang)))))
                   (conj (field-problem :lang (str "lang must be a non-empty string, got "
                                                   (pr-str lang))))
                   (and (contains? data :ddc) (not (string? ddc)))
                   (conj (field-problem :ddc (str "ddc must be a quoted string (YAML read "
                                                  (pr-str ddc) " as a "
                                                  (if (nil? ddc) "null" (.getSimpleName (class ddc)))
                                                  ", which can lose digits such as a"
                                                  " trailing zero); write ddc: \"...\"")))
                   (and (contains? data :tags)
                        (not (string? tags))
                        (not (and (sequential? tags) (every? string? tags))))
                   (conj (field-problem :tags (str "tags must be a string or a list of strings, got "
                                                   (binding [*print-length* 8 *print-level* 3]
                                                     (pr-str tags))))))
        bad (set (map :field problems))]
    [(cond-> {}
       (and (contains? data :lang) (not (bad :lang))) (assoc :lang lang)
       (and (contains? data :ddc) (not (bad :ddc))) (assoc :ddc ddc)
       (and (contains? data :tags) (not (bad :tags))) (assoc :tags (if (string? tags) [tags] (vec tags))))
     problems]))

(defn- read-entry-fields
  "`{:fields {:title? :lang? :ddc? :tags?} :problems [...]}` from `file`'s
  front matter, reading nothing past it. `:problems` are
  `{:field :reason}` maps for what could not be listed as written. Throws
  on an unreadable file or malformed front matter."
  [^File file open-reader]
  (let [yaml-text (with-open [^java.io.Reader r (open-reader file)] (front-matter-text r))]
    (cond
      (nil? yaml-text) {:fields {} :problems []}
      (= ::too-long yaml-text)
      {:fields {}
       :problems [(field-problem nil (str "the front matter opened by --- is not closed within"
                                          " its first " max-front-matter-chars " characters"))]}
      :else
      (let [fm (parser/front-matter (str parser/front-matter-opening "\n" yaml-text "\n---\n")
                                    {:source (str file) :omit-non-path-bibliography? true})
            data (yaml/parse-string yaml-text)
            [fields problems] (metadata-fields (if (map? data) data {}))]
        {:fields (cond-> fields
                   (seq (:title fm)) (assoc :title (:title fm)))
         :problems problems}))))

;; --- books -------------------------------------------------------------------

(defn- bibliography-file
  "The bibliography a book declares as `declared` (its own
  `meta.bibliography`), resolved against the folder of its entry file
  `file` as a standalone build resolves it against the document's folder;
  nil when it declares none."
  ^File [^File file declared]
  (when (and (string? declared) (not (str/blank? declared)))
    (let [f (io/file declared)]
      (if (.isAbsolute f) f (io/file (.getParentFile file) ^String declared)))))

(defn- load-bibliography
  "`[bibliography warnings]` for the book `id`, whose entry file `file`
  declares `declared`: `{}` and no warning when it declares none, the
  loaded CSL-JSON map when it loads, else `{}` and one
  `:unreadable-bibliography` warning."
  [id ^File file declared]
  (let [unreadable (fn [shown reason]
                     [{} [{:type :unreadable-bibliography
                           :book (canonical id)
                           :file (str file)
                           :bibliography shown
                           :message (str "book " (canonical id) " (" file
                                         "): cannot read its bibliography "
                                         (if (string? shown)
                                           shown
                                           (binding [*print-length* 8 *print-level* 3]
                                             (pr-str shown)))
                                         ": " reason)}]])]
    (cond
      (and (some? declared) (not (string? declared)))
      (unreadable declared "bibliography must be a path (a string)")
      :else
      (if-let [bib-file (bibliography-file file declared)]
        (try
          [(resolver/load-bibliography (str bib-file)) []]
          (catch Throwable t (unreadable (str bib-file) (throwable-message t))))
        [{} []]))))

(defn- load-book
  "The `{:document :warnings :bibliography :bibliography-warnings}` of the
  book whose entry file is `file`, or `{:document <:book-failed
  diagnostic> :warnings [] :bibliography {} :bibliography-warnings []}`.

  With `kinds` (a set of kind names), the entry file and every file it
  includes parse with `{:kinds kinds :cite-keys <the book's own
  bibliography keys>}` -- the vocabulary a standalone build of the book
  would use (`haselnuss.cli`), except that the kinds are the referencing
  build's. A bibliography that cannot be read leaves `:cite-keys` out
  (the parser's shape heuristic) rather than turning every citation into
  prose. Without `kinds` the parse is the lenient one, no vocabulary."
  [id ^File file parse kinds]
  (let [fail (fn [what reason]
               {:document {:type :book-failed
                           :book (canonical id)
                           :file (str file)
                           :message (str "book " (canonical id) " (" file ") " what ": " reason)}
                :warnings []
                :bibliography {}
                :bibliography-warnings []})]
    (try
      (let [source (slurp file :encoding "UTF-8")
            ;; Parsing the front matter first reports malformed YAML the
            ;; way `parse` would. A `bibliography` that is not a path is
            ;; the parser's own located error (TASK-209); here it is
            ;; turned into the book's `:unreadable-bibliography` warning
            ;; instead, from the value the error carries, and every parse
            ;; of the book leaves it out of Meta rather than failing the
            ;; host build on it.
            declared (try (:bibliography (parser/front-matter source {:source (str file)}))
                          (catch clojure.lang.ExceptionInfo e
                            (if (= ::parser/bibliography-not-a-path (:type (ex-data e)))
                              (:value (ex-data e))
                              (throw e))))
            [bibliography bib-warnings] (load-bibliography id file declared)
            vocabulary (cond-> {:omit-non-path-bibliography? true}
                         kinds (assoc :kinds kinds)
                         (and kinds (empty? bib-warnings)) (assoc :cite-keys (set (keys bibliography))))
            parse-text (fn [text ^File f] (parse text (assoc vocabulary :source (str f))))
            parse-file (fn [^File f] (parse-text (slurp f :encoding "UTF-8") f))
            {:keys [document warnings]}
            (resolver/expand-includes (parse-text source file)
                                      {:base-dir (.getParentFile file)
                                       :source-path (str file)
                                       :load parse-file})]
        {:document document
         :warnings (vec warnings)
         :bibliography bibliography
         :bibliography-warnings bib-warnings})
      (catch java.io.IOException e
        (fail "cannot be read" (throwable-message e)))
      (catch Throwable t
        (fail "failed to parse" (throwable-message t))))))

(defrecord ^:private FileSystemBook [entry path file loaded]
  library/Book
  (book-entry [_] entry)
  (document [_] (:document @loaded)))

(defn filesystem-book?
  "True when `x` is a book this provider built."
  [x]
  (instance? FileSystemBook x))

(defn book-path
  "The path on disk of a filesystem `book`: its book folder, or its file
  for a single-file book -- the name its slug comes from."
  [book]
  (:path book))

(defn entry-file
  "The entry `.hdoc` file of a filesystem `book`."
  [book]
  (:file book))

(defn include-warnings
  "The `:unreadable-include`/`:include-cycle` warnings expanding a
  filesystem `book`'s `!include`s produced, loading its document if it was
  not loaded yet (the load is cached). Empty for any other value."
  [book]
  (if (filesystem-book? book) (:warnings @(:loaded book)) []))

(defn load-warnings
  "Every warning loading a filesystem `book` produced: its
  `include-warnings`, then an `:unreadable-bibliography` when the
  bibliography its front matter declares cannot be read. Loads the
  document if it was not loaded yet (the load is cached). Empty for any
  other value."
  [book]
  (if (filesystem-book? book)
    (let [{:keys [warnings bibliography-warnings]} @(:loaded book)]
      (into (vec warnings) bibliography-warnings))
    []))

(defn bibliography
  "The CSL-JSON bibliography (as `haselnuss.resolver/load-bibliography`
  returns it) of a filesystem `book`: the file its front matter's
  `bibliography` names, resolved against the folder of its entry file.
  `{}` when it declares none or the file cannot be read (that is one of
  its `load-warnings`). Loads the document if it was not loaded yet; nil
  for any other value."
  [book]
  (when (filesystem-book? book) (:bibliography @(:loaded book))))

;; --- scanning ----------------------------------------------------------------

(def ^:private name-rule
  "the name must be lowercase [a-z0-9-] and start with a letter or digit")

(defn- invalid-book-name
  [lib-name ^File f reason]
  {:type :invalid-book-name
   :library lib-name
   :path (str f)
   :message (str "library " (pr-str lib-name) ": skipped " f ": " reason)})

(defn- candidate
  "What `f`, found in a shelf (the library root when `root?`), is:
  `[:book slug path entry-file]`, `[:shelf name]`, `[:invalid reason]`, or
  nil to ignore it."
  [^File f root?]
  (let [file-name (.getName f)]
    (cond
      (ignored? file-name) nil
      (.isDirectory f)
      (let [entry (io/file f book-file-name)]
        (cond
          (not (valid-name? file-name))
          [:invalid (str (if (.isFile entry) "a book folder" "a shelf") " whose " name-rule)]
          (.isFile entry) [:book file-name f entry]
          :else [:shelf file-name]))
      (and (.isFile f) (str/ends-with? file-name extension))
      (let [slug (subs file-name 0 (- (count file-name) (count extension)))]
        (cond
          (and root? (= book-file-name file-name))
          [:invalid (str "the library root is a shelf, not a book folder; "
                         "put the book in a folder of its own")]
          (valid-name? slug) [:book slug f f]
          :else [:invalid (str "a book file whose " name-rule " before .hdoc")]))
      :else nil)))

(defn- invalid-front-matter
  [lib-name id ^File file {:keys [field reason]}]
  (cond-> {:type :invalid-front-matter
           :library lib-name
           :book (canonical id)
           :path (str file)
           :message (str "library " (pr-str lib-name) ": book " (canonical id) " (" file "): "
                         (if field
                           (str "front-matter field " (name field) " left out of the listing: "
                                reason)
                           (str "front matter cannot be read: " reason)))}
    field (assoc :field field)))

(defn- book-for
  "The Book for `slug` on `shelf`, whose path on disk is `path` and entry
  file `file`, with the `:invalid-front-matter` diagnostics listing it
  found. Front matter that cannot be read at all lists the book under its
  slug; `document` then parses the file itself and reports what it finds."
  [lib-name shelf slug ^File path ^File file {:keys [parse open-reader kinds]}]
  (let [id (book-ref/book-id lib-name shelf slug)
        {:keys [fields problems]}
        (try (read-entry-fields file open-reader)
             (catch Throwable t
               {:fields {} :problems [(field-problem nil (throwable-message t))]}))]
    {:book (->FileSystemBook (library/book-entry-value id fields) (str path) file
                             (delay (load-book id file parse kinds)))
     :diagnostics (mapv #(invalid-front-matter lib-name id file %) problems)}))

(defn- generated-shelf
  "The walk result for the generated shelf in `dir` (TASK-158): its
  script's books, its diagnostics, and under `:unusable` the diagnostic
  that says why it listed no books at all, when it did not."
  [lib-name ^File dir shelf opts]
  (let [{:keys [books diagnostics failure]}
        (generated/load-shelf lib-name shelf dir (:generated opts))]
    {:books books
     :diagnostics diagnostics
     :unusable (if failure [failure] [])}))

(defn- walk
  "Every book under `dir` (on shelf `shelf`), as `{:books :diagnostics
  :unusable}`, `:unusable` being the failures of generated shelves that
  listed no books. A folder holding `shelf.clj` is a generated shelf
  (`haselnuss.library.generated`): its books come from the script, and
  nothing else in it is scanned, as nothing in a book folder is.
  `seen` holds the canonical paths of the folders above `dir`, so a
  symlink back to one of them ends the walk there. That cut-off is
  deliberate and silent: the folder's books are already being listed from
  its first appearance, and a loop is no problem the author has to fix.
  (Two links to one folder that are not a loop list its books twice,
  which `:duplicate-slug` then reports.)"
  [lib-name ^File dir shelf opts seen]
  (let [canonical-dir (try (.getCanonicalPath dir) (catch java.io.IOException _ (str dir)))
        looped? (contains? seen canonical-dir)
        generated? (and (not looped?) (generated/generated-shelf? dir))
        children (when-not (or looped? generated?) (.listFiles dir))
        seen (conj seen canonical-dir)]
    (cond
      looped? {:books [] :diagnostics [] :unusable []}
      generated? (generated-shelf lib-name dir shelf opts)
      (nil? children)
      {:books []
       :diagnostics [{:type :unreadable-shelf
                      :library lib-name
                      :path (str dir)
                      :message (str "library " (pr-str lib-name) ": cannot list folder " dir)}]
       :unusable []}
      :else
      (reduce
       (fn [acc ^File f]
         (let [[kind a b c] (candidate f (empty? shelf))]
           (case kind
             :book (let [{:keys [book diagnostics]} (book-for lib-name shelf a b c opts)]
                     (-> acc
                         (update :books conj book)
                         (update :diagnostics into diagnostics)))
             :shelf (let [sub (walk lib-name f (conj shelf a) opts seen)]
                      (-> acc
                          (update :books into (:books sub))
                          (update :diagnostics into (:diagnostics sub))
                          (update :unusable into (:unusable sub))))
             :invalid (update acc :diagnostics conj (invalid-book-name lib-name f a))
             acc)))
       {:books [] :diagnostics [] :unusable []}
       (sort-by #(.getName ^File %) children)))))

(defn- ambiguity
  "The canonical ids and paths of `books`, which share a slug."
  [books]
  {:books (mapv (comp canonical :id :entry) books)
   :paths (mapv :path books)})

(defn- duplicate-slugs
  [lib-name index]
  (for [[[_ slug] books] (sort-by key index)
        :when (< 1 (count books))
        :let [{:keys [books paths] :as found} (ambiguity books)]]
    (assoc found
           :type :duplicate-slug
           :library lib-name
           :slug slug
           :message (str "library " (pr-str lib-name) " has more than one book with slug "
                         (pr-str slug) ": "
                         (str/join ", " (map #(str %1 " (" %2 ")") books paths))))))

(defn- scan
  [lib-name ^File root opts]
  (try
    (let [{:keys [books diagnostics unusable]} (walk lib-name root [] opts #{})
          entries (mapv :entry books)
          index (group-by (comp book-ref/book-key :id :entry) books)]
      {:entries entries
       :index index
       :tree (library/shelf-tree-of entries)
       :diagnostics (into (vec diagnostics) (duplicate-slugs lib-name index))
       :unusable unusable})
    (catch Throwable t
      {:entries [] :index {} :tree {} :unusable []
       :diagnostics [{:type :unreadable-shelf
                      :library lib-name
                      :path (str root)
                      :message (str "library " (pr-str lib-name) ": scanning " root
                                    " failed: " (throwable-message t))}]})))

;; --- the library -------------------------------------------------------------

(defn- unlisted-generated
  "What `find-book` answers for `slug`, which no listed book has, when the
  generated shelves whose failures are `unusable` listed no books: the
  first one's `:scripts-disabled` or `:script-failed`, since the book may
  well be one of theirs (doc-10: \"the shelf's books report
  scripts-disabled\"). The shelf's own diagnostic is kept under `:cause`."
  [lib-name slug unusable]
  (let [cause (first unusable)
        scripts (mapv :script unusable)]
    {:type (:type cause)
     :level :warning
     :library lib-name
     :slug slug
     :scripts scripts
     :cause cause
     :message (str "library " (pr-str lib-name) " lists no book " (pr-str slug)
                   "; it may be generated by a shelf that listed no books"
                   (when (< 1 (count scripts)) (str " (" (count scripts) " such shelves; the first)"))
                   ": " (:message cause))}))

(defrecord ^:private FileSystemLibrary [lib-name root scanned]
  library/Library
  (library-name [_] lib-name)
  (shelf-tree [_] (:tree @scanned))
  (list-books [_] (:entries @scanned))
  (find-book [_ slug]
    (let [books (when (string? slug)
                  (get (:index @scanned) (book-ref/book-key {:library lib-name :slug slug})))]
      (cond
        (and (empty? books) (string? slug) (seq (:unusable @scanned)))
        (unlisted-generated lib-name slug (:unusable @scanned))
        (empty? books) nil
        (= 1 (count books)) (first books)
        :else
        (let [{:keys [books paths] :as found} (ambiguity books)]
          (assoc found
                 :type :ambiguous-book
                 :library lib-name
                 :slug slug
                 :message (str "slug " (pr-str slug) " in library " (pr-str lib-name)
                               " names more than one book: "
                               (str/join ", " (map #(str %1 " (" %2 ")") books paths))))))))

  library/LibraryDiagnostics
  (library-diagnostics [_] (:diagnostics @scanned)))

(defn library-root
  "The root folder of a filesystem `lib`, as a path string."
  [lib]
  (:root lib))

(defn filesystem-library
  "A Library over the folder `(:path entry-config)`, named
  `(:name entry-config)`, or an `:invalid-library` diagnostic. See the
  namespace docstring.

  `opts` is for in-process callers (a YAML entry cannot carry a fn):
  `:parse`, a `(fn [source {:source path}] Document)` used for every file
  a document load reads, entry and includes alike; default
  `haselnuss.parser/parse`. Its opts also carry
  `:omit-non-path-bibliography? true` (and the vocabulary, with
  `:kinds`): a book's bad `bibliography` is a warning, not a failed
  parse. `:open-reader`, a `(fn [File] java.io.Reader)`
  listing reads each entry file's front matter through (default: a UTF-8
  reader); listing closes it. A test seam for counting what listing
  reads. `:kinds`, the referencing build's set of kind names (the
  `haselnuss.parser` vocabulary's `:kinds`): when given, every book parses
  with it and with `:cite-keys` from its own bibliography (see
  `load-book`); default nil, the lenient parse. `:timeout-ms`,
  `:output-limit` and `:document-output-limit`, the sandbox bounds of the
  calls into a generated shelf's script (defaults in
  `haselnuss.library.generated/load-shelf`). `:on-script-call`, a
  test seam called before every such call (`haselnuss.library.generated/
  load-shelf`). `provider` turns these opts into a registry provider.

  The entry config's own `:scripts` (true, false or absent) says whether
  generated shelves run; see the namespace docstring."
  ([entry-config] (filesystem-library entry-config {}))
  ([entry-config {:keys [parse open-reader kinds timeout-ms output-limit document-output-limit
                         on-script-call]
                  :or {parse parser/parse open-reader default-open-reader}}]
   (let [lib-name (when (map? entry-config) (:name entry-config))
         path (when (map? entry-config) (:path entry-config))
         scripts (when (map? entry-config) (:scripts entry-config))
         root (when (and (string? path) (not (str/blank? path)))
                (.getAbsoluteFile (io/file path)))
         problem (cond
                   (not (map? entry-config)) "the config must be a map"
                   (not (library/valid-name? lib-name))
                   (str "library name " (pr-str lib-name) " must match [a-z0-9][a-z0-9-]*")
                   (not (contains? entry-config :path)) "a filesystem library needs a path"
                   (nil? root) (str ":path must be a non-empty string, got " (pr-str path))
                   (not (.isDirectory ^File root)) (str "no such folder: " root)
                   (not (ifn? parse)) ":parse must be a fn"
                   (not (ifn? open-reader)) ":open-reader must be a fn"
                   (not (or (nil? kinds) (and (set? kinds) (every? string? kinds))))
                   ":kinds must be a set of strings"
                   (not (or (nil? scripts) (boolean? scripts)))
                   (str "scripts must be true or false, got " (pr-str scripts))
                   :else nil)]
     (if problem
       {:type :invalid-library
        :library lib-name
        :message (str "cannot build filesystem library " (pr-str lib-name) ": " problem)}
       (->FileSystemLibrary lib-name (str root)
                            (delay (scan lib-name root {:parse parse
                                                        :open-reader open-reader
                                                        :kinds kinds
                                                        :generated {:scripts? (true? scripts)
                                                                    :timeout-ms timeout-ms
                                                                    :output-limit output-limit
                                                                    :document-output-limit
                                                                    document-output-limit
                                                                    :on-script-call on-script-call}})))))))

(defn provider
  "The `:filesystem` provider -- a `(fn [entry-config] Library)` for
  `haselnuss.library.directory/register-provider` -- that builds every
  library with `opts` (`filesystem-library`'s). This is how a build hands
  its parse vocabulary to the books it references:

      (directory/register-provider directory/default-registry :filesystem
                                   (provider {:kinds kinds}))"
  [opts]
  (fn [entry-config] (filesystem-library entry-config opts)))

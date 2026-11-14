(ns haselnuss.library.graph
  "The reference graph (doc-11, \"Abstraction\"; TASK-238): every book's
  outgoing `@{…}` and `!quote` references, queryable in both directions,
  behind a protocol that a backend implements. `--check`, `--backlinks`,
  the rebuild functions (`affected-by`) and a future daemon read it.

  The protocol sits BESIDE `haselnuss.library`'s `Library`/`Book`, not
  inside them: one edge can span two libraries, and a library provider
  (where books live) and a graph backend (where edges are indexed) are
  different implementers. Backends live in their own namespaces
  (`haselnuss.library.graph.memory-index`) and are chosen through
  `haselnuss.library.graph.registry`.

  ## Values

  A book record is what scanning one book produces, and the unit
  `with-book` writes (doc-11, \"Book record\"):

      {:id          BookId                  ; canonical, as the library lists it
       :hash        string                  ; content hash over :files
       :files       #{path-string}          ; every file the output depends on
       :labels      {label-string hash}     ; label -> hash of the labelled node
       :label-spans? {label-string [first last]} ; document-order span of each label
       :edges       [Edge]                  ; outgoing references
       :diagnostics [diagnostic]}           ; the book's own diagnostics

  An Edge (doc-11, \"Edge\"):

      {:from     {:id BookId :passage? label-string   ; nearest labelled ancestor
                  :order? nat-int}                    ; place in document order
       :to       {:id? BookId :written string :passage? Passage}
       :kind     :link | :quote
       :status   :resolved | :dangling
       :problem? keyword                             ; the diagnostic :type, when dangling
       :position? {:line pos-int :file? string :column? pos-int}}

  As in doc-11's tables, a trailing `?` marks an OPTIONAL key; the key
  itself is written without it (`:problem`, `:passage`, `:id`). `:files`
  is a set of path strings, compared exactly: making them absolute or
  canonical is the scanner's job, so `books-for-file` stays a lookup.

  `:to :written` is the reference exactly as written (`\"bible:romanos#14:8\"`),
  `:to :id` the canonical target when it resolved to a book (a dangling
  `:unknown-label` edge still has one), and `:to :passage` the parsed
  Passage of `haselnuss.book-ref` (`{:form :label :label …}` etc.). A
  resolved edge has a `:to :id` and no `:problem`; a dangling edge has a
  `:problem`. Every edge's `:from :id` is its record's `:id`. `:position`
  is the source position the parser recorded on the reference; a document
  that carries none -- a generated (`shelf.clj`) book's, whose script
  answers plain data, or one read from JSON -- gives edges without one
  (TASK-240).

  `:from :order` and `:label-spans` (TASK-242) place references and
  labelled blocks in one count of the book's nodes in document order
  (`haselnuss.library.graph.scan/outline`): a label's span runs from its
  block to the last node inside it, both inclusive. A reference lies in
  the passage a label names when its order is within the label's span,
  and in a range `from..to` when it is within `[first-of-from
  last-of-to]` -- which is how `--check` follows a quote cycle through a
  quoted passage. Both are optional: a record without them (built by
  hand, or by an older scanner) only lets a reference count as inside a
  label it is directly under.

  `BookRecord` and `Edge` are malli schemas; `valid-book-record?` and
  `explain-book-record` check one.

  A library record (TASK-241) is what a scan found about one library in
  scope, written with the optional `LibraryIndex` protocol's
  `with-library`, never with `with-book`:

      {:name        string                 ; the library's name
       :status      :available | :unavailable
       :root?       string                 ; a filesystem library's root, canonical
       :diagnostics [diagnostic]}          ; library-level, see LibraryIndex

  `LibraryRecord` is its malli schema.

  ## Identity

  Two identities are in play, deliberately different:

  - A RECORD is keyed by its `:id` compared with `=`, shelf included.
    That id is the canonical one the library lists for a book it holds,
    not a reference somebody wrote, so two books that share a slug on
    different shelves (a `:duplicate-slug`) are two records, and
    `with-book` of one never replaces the other. `without-book`,
    `edges-from` and `indexed-books` therefore take and answer canonical
    ids.
  - A TARGET (`edges-to`) is matched in two steps, both in
    `edge-matches-target?` so every backend agrees:
    - Bucket by `book-ref/book-key` (library + slug), the identity
      resolution uses (doc-10, \"Classification scheme\"). An edge's
      `target-key` is `(book-key (:id to))` when it names a book, else
      the key of the BookId parsed from `:written` -- so a dangling
      `:unknown-book` edge is still found by `edges-to` on the book it
      names, which `--backlinks` (dangling-but-identifiable edges) and
      `affected-by` (a dangling edge naming a newly created book) need.
      An edge whose `:written` is malformed has no target key and is
      only reachable through `edges-from`.
    - A RESOLVED edge additionally has to resolve to exactly the queried
      id when that id is canonical (non-empty shelf), so two books that
      share a slug on different shelves never get each other's
      backlinks. Ask with the book's CURRENT canonical id (the CLI
      resolves its argument first); a stale shelf finds no resolved
      edges. A query with an empty shelf -- the short form, which is also
      the canonical id of a book at its library's root -- matches
      resolved edges by book-key alone, so `bible:romanos` reaches
      `bible:scripture/nt/romanos`'s edges; when slugs collide it
      reaches every colliding book's. DANGLING edges always match by
      book-key, even when they carry a `:to :id` (an `:unknown-label`):
      what the author meant is genuinely open there, and a book appearing
      under that slug may change how they resolve.

  ## Passage matching

  `edges-to` with a passage matches only edges whose target passage --
  `:to :passage`, else the passage parsed from `:to :written`
  (`edge-passage-key`, mirroring `target-key`'s fallback for the book) --
  is the same passage exactly as written, after whitespace normalisation
  (doc-11, \"--backlinks\"); overlapping locators are not matched (doc-11,
  Open point 1). The normalisation lives here, in `passage-key`, rather
  than in a backend or in the CLI, so every backend and every caller
  compares the same strings: the passage's text (`format-passage`, which
  reproduces a parsed passage verbatim) with all Unicode whitespace
  removed -- the grammar admits no whitespace inside a passage, so any
  that reaches here is padding, such as a CLI argument's trailing space.

  ## Rules every backend keeps (the contract suite pins them)

  - Values, not mutation: `with-book`/`without-book` return a new graph.
  - Snapshot reads: a graph value never observes a later write.
  - `with-book` replaces every fact whose source is that book -- record,
    edges, file claims -- and is the only write besides `without-book`.
  - Dangling edges are stored and returned, never dropped.
  - `edges-to` answers edges in no particular order (reports sort);
    `edges-from` answers them in the record's order.

  ## Failure

  Misuse is a caller bug, not a document problem, so it throws `ex-info`
  rather than answering a diagnostic: `with-book` on a value that is not
  a valid book record (`::invalid-book-record`, carrying a humanized
  `:explain`), `edges-to` on something that is neither a BookId nor
  `{:id BookId :passage? …}` (`::invalid-target`). doc-11 maps a throwing
  backend to exit code 2."
  (:require [clojure.string :as str]
            [haselnuss.book-ref :as book-ref]
            [haselnuss.library :as library]
            [malli.core :as m]
            [malli.error :as me]))

;; --- protocols ---------------------------------------------------------------

(defprotocol ReferenceGraph
  "The six portable operations (doc-11, \"Abstraction\"). `--check`,
  `--backlinks` and the derived rebuild functions use only these."
  (with-book [g book-record]
    "A new graph in which every fact whose source is `(:id book-record)`
    is replaced by `book-record`'s. Throws on an invalid record.")
  (without-book [g book-id]
    "A new graph without the book `book-id` (canonical) or any of its
    facts. A book that is not indexed leaves the graph as it is.")
  (indexed-books [g]
    "A map of canonical BookId -> `{:hash :files :labels :diagnostics}`,
    the book record without `:id` and `:edges`, for every indexed book.")
  (edges-from [g book-id]
    "The outgoing edges of the book `book-id` (canonical), in the record's
    order; `[]` when it is not indexed.")
  (edges-to [g target]
    "The incoming edges of a target, in no particular order: `target` is a
    BookId (every edge into that book, whole-book and passage alike), or
    `{:id BookId :passage Passage-or-string}` (only edges whose passage
    matches, see `passage-key`; a `{:id …}` without `:passage` is the
    whole book). Dangling edges included; `edge-matches-target?` is the
    exact rule.")
  (books-for-file [g path]
    "The set of canonical BookIds whose record lists the file `path` (a
    string, compared exactly) in `:files`; `#{}` when none does."))

(defprotocol LibraryIndex
  "Library records beside the book records (TASK-241): what a scan found
  about each LIBRARY in scope rather than about one book -- doc-11's
  `duplicate-slug`, `invalid-book-name`, `scripts-disabled`,
  `script-failed`, `invalid-generated-book`, `unavailable-library` -- so
  `check-report` reads them from the graph without listing a library
  again. Kept apart from `ReferenceGraph`'s book records on purpose: a
  library is no book, so its record never shows in `indexed-books`,
  `edges-to`, `books-for-file` or a rebuild set, and no BookId has to be
  reserved for it. The same rules hold: values, snapshot reads, and
  replace-whole-record as the only write, so `refresh` (TASK-244) swaps a
  library's record when it re-lists that library.
  `haselnuss.library.graph.libraries/library-indexed` gives every backend
  an implementation; a backend may implement it natively instead."
  (with-library [g library-record]
    "A new graph in which the record of library `(:name library-record)`
    is `library-record`. Throws on an invalid record.")
  (without-library [g lib-name]
    "A new graph without library `lib-name`'s record. Its books' records,
    if any, are untouched: drop those with `without-book`.")
  (indexed-libraries [g]
    "A map of library name -> library record, for every recorded
    library."))

(defprotocol GraphQuery
  "INTERNAL AND UNSTABLE (doc-11, Open point 10). A backend-native query,
  e.g. Datalog for a DataScript backend. Optional: `:memory-index` does
  not implement it. Nothing outside a backend's own namespace and tests
  may depend on it -- `--check`, `--backlinks` and the derived functions
  use only `ReferenceGraph`, and the reader UI's read surface will be the
  future server's HTTP endpoints. Its shape may change without notice."
  (query [g q inputs]
    "Run the backend-native query `q` with `inputs`."))

;; --- schemas -----------------------------------------------------------------

(def ^:private schema-registry
  (merge
   (m/default-schemas)
   {::book-id [:and
               [:map
                [:library :string]
                [:shelf [:vector :string]]
                [:slug :string]]
               [:fn {:error/message "should be a well-formed BookId"}
                library/valid-book-id?]]

    ::passage [:multi {:dispatch :form}
               [:label [:map [:form [:= :label]] [:label :string]]]
               [:range [:map [:form [:= :range]] [:from :string] [:to :string]]]
               [:locator [:map [:form [:= :locator]] [:locator :string]]]]

    ::position [:map
                [:line pos-int?]
                [:file {:optional true} :string]
                [:column {:optional true} pos-int?]]

    ::edge [:and
            [:map
             [:from [:map
                     [:id [:ref ::book-id]]
                     [:passage {:optional true} :string]
                     [:order {:optional true} nat-int?]]]
             [:to [:map
                   [:id {:optional true} [:ref ::book-id]]
                   [:written :string]
                   [:passage {:optional true} [:ref ::passage]]]]
             [:kind [:enum :link :quote]]
             [:status [:enum :resolved :dangling]]
             [:problem {:optional true} :keyword]
             [:position {:optional true} [:ref ::position]]]
            [:fn {:error/message
                  "a resolved edge needs :to :id and no :problem; a dangling edge needs a :problem"}
             (fn [{:keys [to status problem]}]
               (case status
                 :resolved (and (some? (:id to)) (nil? problem))
                 :dangling (some? problem)
                 false))]]

    ::diagnostic [:fn {:error/message "should be a {:type keyword :message string} diagnostic"}
                  library/diagnostic?]

    ::book-record [:and
                   [:map
                    [:id [:ref ::book-id]]
                    [:hash :string]
                    [:files [:set :string]]
                    [:labels [:map-of :string :string]]
                    [:label-spans {:optional true}
                     [:map-of :string [:and [:tuple nat-int? nat-int?]
                                       [:fn {:error/message "a span must not end before it starts"}
                                        (fn [[a b]] (<= a b))]]]]
                    [:edges [:vector [:ref ::edge]]]
                    [:diagnostics [:vector [:ref ::diagnostic]]]]
                   [:fn {:error/message "every edge's :from :id must be the record's :id"}
                    (fn [{:keys [id edges]}]
                      (every? #(= id (:id (:from %))) edges))]]

    ::library-record [:map
                      [:name [:fn {:error/message "should be a valid library name"}
                              library/valid-name?]]
                      [:status [:enum :available :unavailable]]
                      [:root {:optional true} :string]
                      [:diagnostics [:vector [:ref ::diagnostic]]]]}))

(def Edge
  "Malli schema of a reference edge."
  (m/schema ::edge {:registry schema-registry}))

(def BookRecord
  "Malli schema of a book record, the unit `with-book` writes."
  (m/schema ::book-record {:registry schema-registry}))

(def ^:private book-record-validator (m/validator BookRecord))
(def ^:private book-record-explainer (m/explainer BookRecord))

(defn valid-book-record?
  "True when `x` is a valid book record."
  [x]
  (book-record-validator x))

(defn explain-book-record
  "nil when `x` is a valid book record, else malli's humanized explanation."
  [x]
  (some-> (book-record-explainer x) me/humanize))

(defn check-book-record!
  "`record`, or an `ex-info` (`::invalid-book-record`) when it is not a
  valid book record. Every backend's `with-book` calls this first."
  [record]
  (if (valid-book-record? record)
    record
    (throw (ex-info (str "not a valid book record"
                         (when-let [id (:id record)]
                           (when (library/valid-book-id? id)
                             (str " for " (book-ref/format-book-id id)))))
                    {:type ::invalid-book-record
                     :explain (explain-book-record record)}))))

(def LibraryRecord
  "Malli schema of a library record, the unit `with-library` writes."
  (m/schema ::library-record {:registry schema-registry}))

(def ^:private library-record-validator (m/validator LibraryRecord))
(def ^:private library-record-explainer (m/explainer LibraryRecord))

(defn explain-library-record
  "nil when `x` is a valid library record, else malli's humanized
  explanation."
  [x]
  (some-> (library-record-explainer x) me/humanize))

(defn check-library-record!
  "`record`, or an `ex-info` (`::invalid-library-record`) when it is not
  a valid library record. Every `with-library` calls this first."
  [record]
  (if (library-record-validator record)
    record
    (throw (ex-info (str "not a valid library record"
                         (when (library/valid-name? (:name record))
                           (str " for " (pr-str (:name record)))))
                    {:type ::invalid-library-record
                     :explain (explain-library-record record)}))))

;; --- shared matching rules -----------------------------------------------------

(defn passage-key
  "The string an `edges-to` passage filter compares: `passage` (a parsed
  Passage, or the text after `#`) as written, with every Unicode
  whitespace character removed. nil for nil."
  [passage]
  (when (some? passage)
    (str/replace (if (string? passage) passage (book-ref/format-passage passage))
                 #"\p{IsWhite_Space}+" "")))

(defn- written-ref
  "The reference parsed from `edge`'s `:to :written`, or nil when it is
  malformed."
  [edge]
  (let [parsed (book-ref/parse-book-ref (:written (:to edge)))]
    (when-not (book-ref/malformed? parsed) parsed)))

(defn target-key
  "The book-key `edges-to` finds `edge` under: the resolved target's,
  else that of the BookId parsed from `:to :written`; nil when neither
  names a book. A coarse bucket: `edge-matches-target?` decides."
  [edge]
  (if-let [id (:id (:to edge))]
    (book-ref/book-key id)
    (some-> (written-ref edge) :id book-ref/book-key)))

(defn edge-passage-key
  "The `passage-key` of `edge`'s target passage: its `:to :passage`,
  else the passage parsed from `:to :written`; nil when it names none."
  [edge]
  (passage-key (or (:passage (:to edge)) (:passage (written-ref edge)))))

(def ^:private valid-passage? (m/validator (m/schema ::passage {:registry schema-registry})))

(defn parse-target
  "`edges-to`'s `target` as `{:key book-key :id BookId :passage
  passage-key-or-nil}`: a BookId, or `{:id BookId :passage? …}` whose
  passage is a string or a valid Passage. Throws `::invalid-target` on
  anything else."
  [target]
  (cond
    (library/valid-book-id? target)
    {:key (book-ref/book-key target) :id target :passage nil}

    (and (map? target) (library/valid-book-id? (:id target))
         (let [p (:passage target)]
           (or (nil? p) (string? p) (valid-passage? p))))
    {:key (book-ref/book-key (:id target))
     :id (:id target)
     :passage (passage-key (:passage target))}

    :else
    (throw (ex-info (str "edges-to expects a BookId or {:id BookId :passage …}, got "
                         (binding [*print-length* 8 *print-level* 3] (pr-str target)))
                    {:type ::invalid-target}))))

(defn edge-matches-target?
  "True when `edges-to` with `parsed` (a `parse-target` result) answers
  `edge`. See the namespace docstring, \"Identity\" and \"Passage
  matching\": the edge's `target-key` must be the target's; a RESOLVED
  edge must also resolve to exactly the target's id when that id is
  canonical (has a shelf); and when the target names a passage, the
  edge's `edge-passage-key` must equal it."
  [{k :key id :id p :passage} edge]
  (and (= k (target-key edge))
       (or (not= :resolved (:status edge))
           (empty? (:shelf id))
           (= id (:id (:to edge))))
       (or (nil? p) (= p (edge-passage-key edge)))))

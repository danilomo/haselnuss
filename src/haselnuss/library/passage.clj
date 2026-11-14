(ns haselnuss.library.passage
  "Passage resolution (doc-10, \"Abstract types\": \"Passage resolution is
  shared, not per book\"; TASK-153): one function, `resolve-passage`, turns
  a parsed passage of any book -- `.hdoc`, generated or in-process -- into
  a Passage value `{:ref :title :blocks :url?}`, or a diagnostic. Quotes
  (`!quote`, TASK-154) splice its `:blocks`; links (`@{...}`, TASK-155)
  print its `:title`.

  ## Per build

  A build makes one `context` and passes it to every `resolve-passage`
  call. The context caches each book's RESOLVED document -- its
  `haselnuss.library/document` run through `haselnuss.resolver/
  resolve-document` with the build's own resolver options -- keyed by
  `haselnuss.book-ref/book-key`, so a book is loaded and resolved once per
  build however many passages are taken from it. A locator never needs
  the document and never touches the cache.

  ## What a passage resolves to

  - No passage (a whole-book reference): the book's title (its BookEntry
    `:title`) and every block of its resolved document.
  - `{:form :label}`: the block whose `attr.id` is the label -- the first,
    when the book repeats an id, as a cross-reference resolves -- and a
    title of the book's title, a comma and the label's own text from the
    book's label table, e.g. \"Heidelberg Catechism, Section 3\". The
    words come from the build's lexicon in the BOOK's `meta.lang`, the
    number from the book's own numbering. A label the book does not
    number (an id with no lexicon kind, a node in front matter) prints as
    the label itself.
  - `{:form :range}`: the blocks from the first label through the last,
    which must be siblings -- held in the same vector of the same parent
    -- with the last not before the first. The title names both ends,
    \"Heidelberg Catechism, Section 1–3\" when both are numbered by the same
    word, else \"..., <from> – <to>\".
  - `{:form :locator}`: the book's own `locate` when it satisfies
    `haselnuss.library/Locatable`, normalised by `haselnuss.library/
    passage-value` (so `:ref` is always canonical).

  Only BLOCK ids are labels: a passage is blocks. The blocks are the
  resolved document's, so a cross-reference inside them already carries
  the book's own `:target`/`:text` and a citation its formatted text; ids
  are NOT stripped and headings keep their ids -- keeping quoted content
  out of the host's numbering is the quoting step's job (TASK-154).

  `:ref` is the canonical reference (the book's current shelf). `:url` is
  never set here: URLs come from a library directory entry's template
  (TASK-155), which a book does not know.

  ## Diagnostics

  A failure is returned instead of a Passage, as a diagnostic carrying
  `:book` (the canonical book id) and `:passage` (the passage as text):

  - `:unknown-label` (`:label`) -- no block in the book has that id;
  - `:bad-label-range` (`:from`, `:to`) -- the range's ends are not
    siblings, or its end comes before its start;
  - `:locator-unsupported` -- a locator on a book that is not Locatable;
  - `:bad-locator` -- the book rejected the locator (its `locate` returned
    one, or threw: the message is the book's);
  - whatever the book's `document` answered instead of a document
    (`:book-failed`, `:invalid-book-document`, ...), and `:book-failed`
    when resolving the book's document threw.

  The reference as written, and the file and line it was written on, are
  the caller's to add: only it knows them.

  Warnings about a BOOK rather than one reference -- a filesystem book's
  `:unreadable-include`/`:include-cycle` (`haselnuss.library.filesystem/
  load-warnings`) and an `:unreadable-bibliography` -- are produced once,
  when the book is first loaded, each tagged with `:book`, and queued on
  the context. `take-book-diagnostics!` hands back the ones not yet
  taken, so whichever resolver step drains the queue folds them into its
  own diagnostics and every one is reported exactly once per build. The
  book's own resolver diagnostics (a dangling cross-reference inside the
  book, say) are not reported: they are the book's, and surface when the
  book itself is built.

  ## Bibliography

  A book's citations resolve against its OWN bibliography, never the
  referencing build's: a filesystem book's is the file its front matter
  names, relative to its entry file (`haselnuss.library.filesystem/
  bibliography`); any other book's `meta.bibliography` is read as written.
  One that cannot be read resolves against none and is an
  `:unreadable-bibliography` warning, rather than the exception
  `resolve-document` would throw.

  ## Wiring (for the resolver steps)

  `haselnuss.library.filesystem` requires `haselnuss.resolver`, and so
  does this namespace, so the resolver cannot require either. The quote
  and link steps reach passage resolution through a fn the build layer
  injects, as `!include` already reaches the parser through
  `:includes`' `:load`: `haselnuss.library.references/books-option`
  (TASK-154)."
  (:require [clojure.string :as str]
            [haselnuss.ast :as ast]
            [haselnuss.book-ref :as book-ref]
            [haselnuss.library :as library]
            [haselnuss.library.filesystem :as fs]
            [haselnuss.resolver :as resolver]
            [haselnuss.walk :as walk]))

;; --- context -----------------------------------------------------------------

(def ^:private resolve-opt-keys
  "The `haselnuss.resolver/resolve-document` options a build shares with
  the books it references. `:includes` and `:overlay-sources` are the
  referencing document's own, and `:bibliography` is per book."
  [:lexicon :directive-registry :directive-kinds :front-matter-names :sublabel-names
   :citation-styles])

(defn context
  "A per-build passage-resolution context.

  `:resolve-opts` are the build's own `haselnuss.resolver/resolve-document`
  options; only the lexicon, directive registry and kinds, front-matter
  and sublabel names, and citation styles are kept (see
  `resolve-opt-keys`), so a book numbers and names its labels with the
  same kinds the referencing build knows. `:resolve-fn` replaces
  `resolve-document` itself, `(fn [document opts] {:document :labels
  ...})`; a seam for tests. A book is resolved WITHOUT its own quotes
  expanded (no `:books` option reaches it): the quoting step expands
  them from the host, which is what keeps this cache independent of who
  quoted a book first (TASK-154, `haselnuss.resolver/expand-quotes`)."
  ([] (context {}))
  ([{:keys [resolve-opts resolve-fn]}]
   {:resolve-opts (select-keys resolve-opts resolve-opt-keys)
    :resolve-fn (or resolve-fn resolver/resolve-document)
    :state (atom {:books {} :pending []})}))

(defn take-book-diagnostics!
  "The book-level warnings (see the namespace docstring) produced since
  the last call, in the order the books were loaded; each is returned by
  exactly one call."
  [ctx]
  (let [[old] (swap-vals! (:state ctx) assoc :pending [])]
    (:pending old)))

;; --- loading a book ----------------------------------------------------------

(defn- throwable-message [^Throwable t] (or (ex-message t) (.getName (class t))))

(defn- canonical [id] (book-ref/format-book-id id))

(defn- book-bibliography
  "`[bibliography warnings]` for `book`, whose document is `document`."
  [book id document]
  (if (fs/filesystem-book? book)
    [(fs/bibliography book) []]
    (let [declared (get-in document [:meta :bibliography])
          unreadable (fn [reason]
                       [{} [{:type :unreadable-bibliography
                             :book (canonical id)
                             :bibliography declared
                             :message (str "book " (canonical id) ": cannot read its bibliography "
                                           (binding [*print-length* 8 *print-level* 3]
                                             (pr-str declared))
                                           ": " reason)}]])]
      (cond
        (or (nil? declared) (and (string? declared) (str/blank? declared))) [{} []]
        (not (string? declared)) (unreadable "meta.bibliography must be a path (a string)")
        :else (try
                [(resolver/load-bibliography declared) []]
                (catch Throwable t (unreadable (throwable-message t))))))))

(def ^:private id-scope
  "Where a label can sit: every block and inline position of the body.
  `::meta` is left out (a title carries no label) and so is `::fallback`
  (target-specific degradation content, as for cross-references)."
  #{::ast/block ::ast/inline ::ast/cell ::ast/row ::ast/cite-item})

(defn- id-index
  "`{:blocks {id path} :elsewhere {id description}}` for `document`: the
  path of the first block carrying each id, and what the first non-block
  node carrying one is -- \"an inline span\", \"a table cell\" -- since an
  id there is not a label, but worth naming. (The schema gives rows,
  cells and citation items no `:attr`, but a transform or JSON input can
  still put one there.)"
  [document]
  (walk/fold {:table walk/table
              :scope id-scope
              :visit (fn [acc path ref-kind node]
                       (let [id (get-in node [:attr :id])
                             k (if (= ref-kind ::ast/block) :blocks :elsewhere)]
                         (if (and (string? id) (not (contains? (get acc k) id)))
                           (assoc-in acc [k id]
                                     (case ref-kind
                                       ::ast/block path
                                       ::ast/inline (str "an inline " (some-> (:t node) name))
                                       ::ast/row "a table row"
                                       ::ast/cell "a table cell"
                                       ::ast/cite-item "a citation item"
                                       "a node that is not a block"))
                           acc)))}
             {:blocks {} :elsewhere {}} ::ast/document document))

(defn- book-failed
  [id what t]
  {:type :book-failed
   :book (canonical id)
   :message (str "book " (canonical id) " " what ": " (throwable-message t))})

(defn- load-resolved
  "The resolved form of `book` -- `{:document :labels :ids :diagnostics
  :load-warnings}` -- or the diagnostic it failed with. Queues the book's
  load warnings on `ctx`."
  [ctx book id]
  (let [document (try (library/document book)
                      (catch Throwable t (book-failed id "failed to produce its document" t)))]
    (if (library/diagnostic? document)
      document
      (let [[bibliography bib-warnings] (book-bibliography book id document)
            warnings (mapv #(merge {:book (canonical id)} %)
                           (into (fs/load-warnings book) bib-warnings))
            _ (swap! (:state ctx) update :pending into warnings)
            result (try ((:resolve-fn ctx) document
                                           (assoc (:resolve-opts ctx) :bibliography bibliography))
                        (catch Throwable t (book-failed id "failed to resolve" t)))]
        (if (library/diagnostic? result)
          result
          {:document (:document result)
           :labels (:labels result)
           :ids (id-index (:document result))
           :diagnostics (vec (:diagnostics result))
           :load-warnings warnings})))))

(defn book-source
  "Where `book` comes from, to tell two books with one id apart (TASK-250):
  a filesystem book's path on disk, nil for any other book. Two books
  with one id and one source share a cache entry, so the reference
  graph's scan records them as one book (`haselnuss.library.graph.scan`)."
  [book]
  (when (fs/filesystem-book? book) (fs/book-path book)))

(defn- cache-entry
  "The entry of `ctx`'s cache under `k`, `{:id :source :loaded delay}`,
  made for `book` (whose id is `id`) when there is none yet."
  [ctx book id k]
  (let [state (swap! (:state ctx) update-in [:books k]
                     #(or % {:id id
                             :source (book-source book)
                             :loaded (delay (load-resolved ctx book id))}))]
    (get-in state [:books k])))

(defn- resolved
  "`book`'s resolved form from `ctx`'s cache, loading it on first use.
  Keyed by `book-ref/book-key`, so a book is the same book whatever shelf
  it is reached by."
  [ctx book id]
  @(:loaded (cache-entry ctx book id (book-ref/book-key id))))

(defn resolved-book
  "`book`'s resolved form, from `ctx`'s cache -- the one `resolve-passage`
  reads, so a book is loaded and resolved once per context however it is
  reached -- loading it on first use: `{:document :labels :ids
  :diagnostics :load-warnings}`, `:document` and `:labels` being
  `haselnuss.resolver/resolve-document`'s (run without `:books`, so the
  book's own quotes and links are left as parsed), `:ids` the block-id
  index (`{:blocks {id path} :elsewhere {...}}`), `:diagnostics`
  `resolve-document`'s and `:load-warnings` the book's own load warnings
  (also queued once for `take-book-diagnostics!`). Or the diagnostic the
  book failed with -- what its `document` answered instead, or a
  `:book-failed` when resolving threw. For the reference graph's
  `scan-book` (TASK-240).

  A scan reads every listed book, including two that share a slug (a
  `:duplicate-slug`), which no reference can reach (`find-book` answers
  `:ambiguous-book`). So when the book-key's entry was made for another
  canonical id, `book` gets an entry of its own, keyed by its id, rather
  than the other book's document (TASK-241). Two filesystem books can
  even share the canonical id (`x.hdoc` beside `x/book.hdoc`), so the
  entry must also have been made for `book`'s own path on disk; one made
  for the other file gives `book` an entry keyed by id and path
  (TASK-250)."
  [book ctx]
  (let [id (:id (library/book-entry book))
        source (book-source book)
        entry (cache-entry ctx book id (book-ref/book-key id))]
    (if (and (= id (:id entry)) (= source (:source entry)))
      @(:loaded entry)
      @(:loaded (cache-entry ctx book id [::exact id source])))))

;; --- titles ------------------------------------------------------------------

(defn- label-text
  [labels label]
  (or (:text (get labels label)) label))

(defn- plain-number?
  "True when label-table `entry` prints as `<word> <number>`, so a range
  can share its word."
  [entry]
  (and (string? (:word entry)) (= (:text entry) (str (:word entry) " " (:number entry)))))

(defn- range-text
  [labels from to]
  (let [a (get labels from)
        b (get labels to)]
    (cond
      (= from to) (label-text labels from)
      (and (plain-number? a) (plain-number? b) (= (:word a) (:word b)))
      (str (:text a) "–" (:number b))
      :else (str (label-text labels from) " – " (label-text labels to)))))

(defn- passage-title
  "The book's title inlines, followed by `, <text>` when `text` is given."
  [entry text]
  (cond-> (vec (:title entry))
    text (conj {:t :str :text (str ", " text)})))

;; --- resolution --------------------------------------------------------------

(defn- failure
  [id passage diagnostic-type message extra]
  (merge {:type diagnostic-type
          :book (canonical id)
          :message message}
         (when passage {:passage (book-ref/format-passage passage)})
         extra))

(defn- unknown-label
  [id passage {:keys [elsewhere]} label]
  (failure id passage :unknown-label
           (str "book " (canonical id) " has no label " (pr-str label)
                (when-let [where (get elsewhere label)]
                  (str " (that id is on " where
                       ", and only a block can be quoted or linked to)")))
           {:label label}))

(defn- passage-of
  "The Passage for `passage` of the resolved book `{:document :labels
  :ids}`, or a diagnostic."
  [{:keys [document labels ids]} entry passage]
  (let [id (:id entry)
        ref-text (book-ref/format-book-ref (cond-> {:id id} passage (assoc :passage passage)))
        paths (:blocks ids)]
    (case (:form passage)
      nil {:ref ref-text :title (passage-title entry nil) :blocks (:blocks document)}
      :label
      (let [label (:label passage)]
        (if-let [path (get paths label)]
          {:ref ref-text
           :title (passage-title entry (label-text labels label))
           :blocks [(get-in document path)]}
          (unknown-label id passage ids label)))
      :range
      (let [{:keys [from to]} passage
            from-path (get paths from)
            to-path (get paths to)]
        (cond
          (nil? from-path) (unknown-label id passage ids from)
          (nil? to-path) (unknown-label id passage ids to)
          (= from to)
          {:ref ref-text
           :title (passage-title entry (range-text labels from to))
           :blocks [(get-in document from-path)]}
          (or (not (int? (peek from-path))) (not (int? (peek to-path)))
              (not= (pop from-path) (pop to-path)))
          (failure id passage :bad-label-range
                   (str "book " (canonical id) ": label range " (pr-str from) ".." (pr-str to)
                        " does not name siblings -- both ends must sit in the same"
                        " parent, at the same level")
                   {:from from :to to})
          (< (peek to-path) (peek from-path))
          (failure id passage :bad-label-range
                   (str "book " (canonical id) ": label range " (pr-str from) ".." (pr-str to)
                        " ends before it starts")
                   {:from from :to to})
          :else
          {:ref ref-text
           :title (passage-title entry (range-text labels from to))
           :blocks (subvec (get-in document (pop from-path))
                           (peek from-path) (inc (peek to-path)))})))))

(defn- locate-passage
  [book id passage]
  (let [locator (:locator passage)]
    (if (satisfies? library/Locatable book)
      (let [result (try (library/locate book locator)
                        (catch Throwable t
                          (failure id passage :bad-locator
                                   (str "book " (canonical id) " rejected locator "
                                        (pr-str locator) ": " (throwable-message t))
                                   {:locator locator})))
            value (library/passage-value id locator result)]
        (if (library/diagnostic? value)
          (merge (failure id passage (:type value) (:message value) {}) value)
          value))
      (failure id passage :locator-unsupported
               (str "book " (canonical id) " does not interpret locators, so "
                    (pr-str locator) " cannot be resolved; refer to a label (kind:label) instead")
               {:locator locator}))))

(defn- check-passage!
  [passage]
  (when-not (or (nil? passage)
                (and (map? passage) (#{:label :range :locator} (:form passage))))
    (throw (ex-info (str "resolve-passage expects a passage as haselnuss.book-ref/parse-book-ref"
                         " returns it, or nil, got " (pr-str passage))
                    {:passage passage}))))

(defn resolve-passage
  "The Passage `{:ref :title :blocks :url?}` that `passage` names in
  `book`, or a diagnostic (see the namespace docstring).

  `book` satisfies `haselnuss.library/Book` -- what `find-book` answered,
  once the caller has turned nil and diagnostics into its own
  `:unknown-book`/`:ambiguous-book`. `passage` is the `:passage` of a
  parsed reference (`haselnuss.book-ref/parse-book-ref`), or nil for the
  whole book; anything else is a caller bug and throws. `ctx` is the
  build's `context`, shared by every call in one build."
  [book passage ctx]
  (check-passage! passage)
  (let [entry (library/book-entry book)
        id (:id entry)]
    (if (= :locator (:form passage))
      (locate-passage book id passage)
      (let [loaded (resolved ctx book id)]
        (if (library/diagnostic? loaded)
          (merge (failure id passage (:type loaded) (:message loaded) {}) loaded)
          (passage-of loaded entry passage))))))

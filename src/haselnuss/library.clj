(ns haselnuss.library
  "The abstract types of the library system (doc-10, \"Abstract types\" and
  \"Values\"; TASK-149): the protocols that every provider implements and
  that the resolver depends on, and nothing else -- so the resolver never
  knows whether a book is a `.hdoc` file, a generated shelf or a value
  built in-process. `LibraryDirectory`, `Library` and `Book` are
  required; `Locatable` (a book with locators) and `LibraryDiagnostics`
  (a library that reports scan problems such as `:duplicate-slug`) are
  optional, and so is `UrlTemplates` (a directory whose entries carry a
  URL template, TASK-155).

  ## Values

  A BookId is `haselnuss.book-ref`'s: `{:library :shelf :slug}`.

  A BookEntry is `{:id BookId :title [Inline] :lang? :ddc? :tags?}`, the
  cheap description a library lists without loading any document.

  A Passage is `{:ref canonical-ref :title [Inline] :blocks [Block]
  :url?}`: `:title` is link text, `:blocks` is what a quote splices.

  `:title` is always inlines (a vector of `haselnuss.ast/Inline`). Where
  this namespace accepts a title from a caller it also takes a plain
  string and turns it into a single `:str` inline (`title-inlines`).

  ## Identity

  Resolution compares BookIds by `book-ref/book-key` (library + slug) and
  never by `=` or by using the BookId map as a key: both still see
  `:shelf`, and a reference whose shelf is stale must still resolve
  (doc-10, \"Classification scheme\").

  ## Failure

  No protocol method lets an exception escape. Every failure is a
  diagnostic map in the resolver's existing `{:type :message ...}` shape
  (`diagnostic?`), so a build keeps going. A method that is merely asked
  for something that is not there answers nil (`find-library`,
  `find-book`); turning that into `unknown-library`/`unknown-book` is the
  caller's job, because only the caller knows the reference as written.
  `find-book` on a slug that more than one book holds answers an
  `:ambiguous-book` diagnostic; the collision itself is a `:duplicate-slug`
  from `library-diagnostics`. Likewise `find-library` on a name whose
  library failed to load answers an `:unavailable-library` diagnostic
  (`:level :warning`, the load problem under `:cause`); the load problem
  itself comes from the directory's `library-diagnostics`.

  A diagnostic message never prints an unbounded value: printing is
  capped and schema explanations are truncated, so a lazy, infinite or
  throwing value handed back by a book cannot hang or crash a build.

  The diagnostic types introduced here, beyond doc-10's table:

  - `:invalid-virtual-book` -- `virtual-book` was given a spec it cannot
    build a Book from.
  - `:book-failed` -- a book could not produce its document: a virtual
    book's `:document` fn threw, or a filesystem book's entry file (or a
    file it includes) could not be read or parsed
    (`haselnuss.library.filesystem`, TASK-151, which also adds
    `:invalid-front-matter` and `:unreadable-shelf`).
  - `:invalid-book-document` -- a virtual book's `:document` (or
    `:locate`) fn returned something that is neither a valid value nor a
    diagnostic, or that threw while being checked.
  - `:invalid-library` -- a provider could not build a library from its
    config (`haselnuss.library.memory`).
  - `:invalid-library-directory` -- `library-directory` was given
    something that is not a Library (a diagnostic is kept under `:cause`),
    or a failure that is not a diagnostic naming a library.
  - `:unavailable-library` -- `find-library` on a library that failed to
    load.

  A `:locate` fn that throws is reported as doc-10's `:bad-locator`, with
  the exception's own message: that is the book rejecting the locator."
  (:require [haselnuss.ast :as ast]
            [haselnuss.book-ref :as book-ref]
            [haselnuss.library.url :as url]))

;; --- protocols ---------------------------------------------------------------

(defprotocol LibraryDirectory
  "The ordered list of libraries available to a build."
  (library-names [dir]
    "The names of the usable libraries, in directory order. A library
    that failed to load is not among them.")
  (find-library [dir lib-name]
    "The Library called `lib-name`; an `:unavailable-library` diagnostic
    when the directory names it but it failed to load; nil when the
    directory does not name it at all."))

(defprotocol Library
  "A named collection of books arranged on shelves."
  (library-name [lib]
    "The library's name, the `library` part of every BookId it holds.")
  (shelf-tree [lib]
    "The library's shelves as nested maps of shelf names, e.g.
    `{\"confessions\" {\"reformed\" {}}}`. `{}` when every book sits at the
    root.")
  (list-books [lib]
    "A seq of BookEntry, one per book. Never loads a book's document.")
  (find-book [lib slug]
    "The Book whose slug is `slug`, nil when there is none (including a
    nil or non-string `slug`), or an `:ambiguous-book` diagnostic naming
    every book when more than one has that slug. A provider that knows
    why a book it might hold is missing may answer that diagnostic
    instead of nil: a filesystem library whose generated shelf listed no
    books answers its `:scripts-disabled` or `:script-failed` (TASK-158)."))

(defprotocol Book
  "Anything that yields a Haselnuss document."
  (book-entry [book]
    "The book's BookEntry. Cheap: never loads the document.")
  (document [book]
    "The book's Haselnuss Document (unresolved AST), or a diagnostic
    map."))

(defprotocol Locatable
  "Optional: a book that interprets locators such as `14:8-9`."
  (locate [book locator]
    "The Passage `locator` names, or a diagnostic map."))

(defprotocol LibraryDiagnostics
  "Optional: a library that reports conditions found while scanning it
  rather than while looking one book up -- doc-10's `:duplicate-slug` and
  `:invalid-book-name`. A library that does not satisfy it has none.

  A LibraryDirectory built by `library-directory` satisfies it too: its
  diagnostics are the load problems of the libraries that failed
  (`:duplicate-library`, `:unknown-provider`, `:invalid-library`)."
  (library-diagnostics [lib]
    "A seq of `{:type :message ...}` diagnostics, empty when there are
    none. Never loads a book's document."))

(defprotocol UrlTemplates
  "Optional: a library directory whose entries may carry a URL template
  (doc-10, \"Resolver\": URLs; TASK-155; `haselnuss.library.url`). A
  directory that does not satisfy it has none, so its links are text
  only. A LibraryDirectory built by `library-directory` satisfies it."
  (url-template [dir lib-name]
    "The URL template of library `lib-name`'s entry, or nil when it has
    none."))

;; --- values ------------------------------------------------------------------

(defn diagnostic?
  "True when `x` is a diagnostic map: a keyword `:type` and a string
  `:message`."
  [x]
  (and (map? x) (keyword? (:type x)) (string? (:message x))))

(defn valid-book-id?
  "True when `id` is a well-formed BookId: library, shelf segments and slug
  all match the book-reference grammar's `name`."
  [id]
  (and (map? id)
       (string? (:library id))
       (string? (:slug id))
       (vector? (:shelf id))
       (every? string? (:shelf id))
       (= {:id id} (book-ref/parse-book-ref (book-ref/format-book-id id)))))

(defn valid-name?
  "True when `s` is a valid library name (the grammar's `name`)."
  [s]
  (and (string? s) (valid-book-id? (book-ref/book-id s "x"))))

(defn title-inlines
  "`title` as inlines: a string becomes one `:str` inline, inlines pass
  through, anything else is nil."
  [title]
  (cond
    (string? title) [{:t :str :text title}]
    (and (vector? title) (every? #(ast/valid? ast/Inline %) title)) title
    :else nil))

(def ^:private entry-keys
  "The keys of a BookEntry other than `:id`."
  [:title :lang :ddc :tags])

(defn entry-problem
  "Why the caller-supplied entry fields `entry` cannot make a BookEntry,
  or nil."
  [{:keys [title lang ddc tags] :as entry}]
  (cond
    (not (map? entry)) "the entry must be a map"
    (and (some? title) (nil? (title-inlines title)))
    "the entry's :title must be a string or a vector of inlines"
    (and (some? lang) (not (string? lang))) "the entry's :lang must be a string"
    (and (some? ddc) (not (string? ddc))) "the entry's :ddc must be a string"
    (and (some? tags) (not (and (sequential? tags) (every? string? tags))))
    "the entry's :tags must be a sequence of strings"
    :else nil))

(defn book-entry-value
  "A BookEntry for `id` from the caller-supplied fields in `entry`
  (`:title`, `:lang`, `:ddc`, `:tags`; anything else is dropped). A missing
  title defaults to the slug, so every entry has link text. Assumes
  `entry` has no `entry-problem`."
  [id entry]
  (let [{:keys [title tags]} entry]
    (cond-> (into {:id id} (filter (comp some? val)) (select-keys entry entry-keys))
      true (assoc :title (or (title-inlines title)
                             (title-inlines (:slug id))))
      (some? tags) (assoc :tags (vec tags)))))

(defn- canonical [id] (book-ref/format-book-id id))

;; --- virtual books -----------------------------------------------------------

(def ^:private max-explanation-length
  "How much of a schema explanation a diagnostic message carries."
  400)

(defn- throwable-message [t] (or (ex-message t) (.getName (class t))))

(defn- bounded-explanation
  "A short account of why `value` does not conform to `schema`. Never
  realises or prints an unbounded value: printing is capped, the text is
  truncated, and a throw while explaining (a lazy seq that throws when
  printed) becomes a fixed phrase."
  [schema value]
  (let [text (try (binding [*print-length* 8 *print-level* 4]
                    (or (ast/explain-summary (ast/explain schema value)) "invalid"))
                  (catch Throwable t
                    (str "explaining it threw: " (throwable-message t))))]
    (if (> (count text) max-explanation-length)
      (str (subs text 0 max-explanation-length) "...")
      text)))

(defn- seq-path
  "The path to the first seq (lazy or not) inside `x`, or nil. Descends
  only into maps and vectors and never into a seq itself, so it cannot
  realise -- and hang on, or be thrown at by -- a lazy, infinite or
  throwing seq. The AST holds only vectors, so a valid node never has one."
  ([x] (seq-path x []))
  ([x path]
   (cond
     (seq? x) path
     (map? x) (some (fn [[k v]] (seq-path v (conj path k))) x)
     (vector? x) (some (fn [i] (seq-path (nth x i) (conj path i))) (range (count x)))
     :else nil)))

(defn- seq-problem
  "A message when `x` holds a seq where the AST wants a vector, else nil."
  [x path-prefix]
  (when-let [path (seq-path x)]
    (str "a sequence where a vector is required at " (pr-str (into path-prefix path))
         " (realise it with vec)")))

(defn document-problem
  "Why `result` is not a Haselnuss Document, or nil. Public so a provider
  validating generated documents (`haselnuss.library.generated`, TASK-158)
  checks them exactly as `virtual-book` does. Every seq outside the
  open-ended `:meta` (and in `:meta`'s own `:title`/`:authors`) is caught
  by `seq-path` before malli runs, so neither validation nor the
  explanation ever walks one."
  [result]
  (cond
    (not (map? result)) "expected a map {:meta {...} :blocks [...]}"
    (not (map? (:meta result))) ":meta must be a map"
    (seq-problem (dissoc result :meta) []) (seq-problem (dissoc result :meta) [])
    (seq-problem (select-keys (:meta result) [:title :authors]) [:meta])
    (seq-problem (select-keys (:meta result) [:title :authors]) [:meta])
    (ast/valid? ast/Document result) nil
    :else (bounded-explanation ast/Document result)))

(defn- invalid-book-document
  [id what problem]
  {:type :invalid-book-document
   :book (canonical id)
   :message (str "book " (canonical id) " produced an invalid " what ": " problem)})

(defn- load-document
  "Call `document-fn` once, turning a throw or a non-Document result into a
  diagnostic. Checking the result sits inside the same `try`, because a
  lazy value can throw only when it is first looked at."
  [id document-fn]
  (let [result (try (document-fn)
                    (catch Throwable t
                      {:type :book-failed
                       :book (canonical id)
                       :message (str "book " (canonical id)
                                     " failed to produce its document: "
                                     (throwable-message t))}))]
    (try
      (if (diagnostic? result)
        result
        (if-let [problem (document-problem result)]
          (invalid-book-document id "document" problem)
          result))
      (catch Throwable t
        (invalid-book-document id "document"
                               (str "checking it threw: " (throwable-message t)))))))

(defn passage-problem
  "Why `result` is not a `:locate` result `{:title? :blocks :url?}`, or
  nil. Public for the same reason as `document-problem`."
  [result]
  (cond
    (not (map? result)) "expected a map {:title ... :blocks [...] :url? ...}"
    (not (vector? (:blocks result)))
    ":blocks must be a vector of blocks (realise a lazy seq with vec)"
    (seq-problem (select-keys result [:title :blocks]) [])
    (seq-problem (select-keys result [:title :blocks]) [])
    (and (some? (:title result)) (nil? (title-inlines (:title result))))
    ":title must be a string or a vector of inlines"
    (and (some? (:url result)) (not (string? (:url result)))) ":url must be a string"
    (every? #(ast/valid? ast/Block %) (:blocks result)) nil
    :else (bounded-explanation [:vector ast/Block] (:blocks result))))

(defn passage-value
  "Normalise what a `:locate` fn -- or any book's `locate` -- returned for
  `locator` on the book `id` into a Passage or a diagnostic. `:ref` is
  always the canonical reference, whatever the fn returned; keys other
  than `:title`, `:blocks` and `:url` are dropped. A diagnostic passes
  through; anything that is neither is an `:invalid-book-document`.
  Idempotent: a Passage it returned comes back unchanged."
  [id locator result]
  (let [ref-text (book-ref/format-book-ref
                  {:id id :passage {:form :locator :locator locator}})]
    (try
      (if (diagnostic? result)
        result
        (if-let [problem (passage-problem result)]
          (assoc (invalid-book-document id (str "passage for " (pr-str locator)) problem)
                 :ref ref-text)
          (cond-> {:ref ref-text
                   :title (or (title-inlines (:title result)) (title-inlines ref-text))
                   :blocks (:blocks result)}
            (:url result) (assoc :url (:url result)))))
      (catch Throwable t
        (assoc (invalid-book-document id (str "passage for " (pr-str locator))
                                      (str "checking it threw: " (throwable-message t)))
               :ref ref-text)))))

(defn- run-locate
  "`locate-fn` applied to `locator`, as a Passage or a diagnostic. A
  locator that is not a non-empty string is rejected without calling it."
  [id locate-fn locator]
  (if-not (and (string? locator) (seq locator))
    {:type :bad-locator
     :book (canonical id)
     :locator locator
     :message (str "book " (canonical id) " was asked for locator "
                   (binding [*print-length* 8 *print-level* 4] (pr-str locator))
                   ", but a locator is a non-empty string")}
    (passage-value
     id locator
     (try (locate-fn locator)
          (catch Throwable t
            {:type :bad-locator
             :book (canonical id)
             :locator locator
             :message (str "book " (canonical id) " rejected locator "
                           (pr-str locator) ": " (throwable-message t))})))))

(defrecord ^:private VirtualBook [entry loaded]
  Book
  (book-entry [_] entry)
  (document [_] @loaded))

(defrecord ^:private LocatableVirtualBook [entry loaded locate-fn]
  Book
  (book-entry [_] entry)
  (document [_] @loaded)
  Locatable
  (locate [_ locator] (run-locate (:id entry) locate-fn locator)))

(defn- invalid-virtual-book
  [spec reason]
  {:type :invalid-virtual-book
   :book (let [id (when (map? spec) (:id spec))]
           (if (valid-book-id? id) (canonical id) (pr-str id)))
   :message (str "cannot build a virtual book: " reason)})

(defn virtual-book
  "A Book whose document comes from code (doc-10, \"In-process books\"):

      (virtual-book {:id       (book-ref/book-id \"bible\" [\"scripture\" \"nt\"] \"romanos\")
                     :entry    {:title \"Romanos\" :lang \"pt-BR\"}
                     :document (fn [] doc)
                     :locate   (fn [locator] passage)})   ; optional

  `:id` is the book's BookId, and its `:shelf` is the book's current
  shelf. `:entry` supplies the rest of the BookEntry (see
  `book-entry-value`).

  `:document` is called lazily, on the first `document` call, and at most
  once for the Book this returns: its result -- or the diagnostic a throw
  or an invalid result becomes -- is cached with the Book. Libraries build
  their books once per build, so this is the per-build cache doc-10 asks
  for; a caller that reuses one Book across builds reuses its document
  too, and can call `virtual-book` again for a fresh one.

  The result also satisfies Locatable exactly when `:locate` is given.
  `:locate` returns `{:title :blocks :url?}` (a string `:title` is
  accepted) or a diagnostic; the Passage's `:ref` is always the canonical
  reference. A nil or empty locator is a `:bad-locator` without calling
  `:locate`. It is not cached.

  An invalid spec returns an `:invalid-virtual-book` diagnostic instead
  of a Book."
  [spec]
  (let [{:keys [id]} spec
        entry (or (:entry spec) {})
        document-fn (:document spec)
        locate-fn (:locate spec)]
    (cond
      (not (map? spec)) (invalid-virtual-book spec "the spec must be a map")
      (not (valid-book-id? id))
      (invalid-virtual-book spec (str ":id must be a BookId, got " (pr-str id)))
      (not (ifn? document-fn))
      (invalid-virtual-book spec ":document must be a zero-argument fn")
      (and (some? locate-fn) (not (ifn? locate-fn)))
      (invalid-virtual-book spec ":locate must be a one-argument fn")
      (entry-problem entry) (invalid-virtual-book spec (entry-problem entry))
      :else
      (let [entry (book-entry-value id entry)
            loaded (delay (load-document id document-fn))]
        (if locate-fn
          (->LocatableVirtualBook entry loaded locate-fn)
          (->VirtualBook entry loaded))))))

;; --- shelves -----------------------------------------------------------------

(defn shelf-tree-of
  "The shelf tree the BookEntries in `entries` sit on: every non-empty
  `:shelf` path becomes nested maps of shelf names."
  [entries]
  (reduce (fn [tree {{:keys [shelf]} :id}]
            (if (seq shelf) (update-in tree shelf #(or % {})) tree))
          {}
          entries))

;; --- library directory -------------------------------------------------------

(defn- unavailable-library
  "What `find-library` answers for `lib-name`, whose library failed to
  load with the diagnostic `cause`."
  [lib-name cause]
  {:type :unavailable-library
   :level :warning
   :library lib-name
   :cause cause
   :message (str "library " (pr-str lib-name) " is unavailable: it failed to load ("
                 (:message cause) ")")})

(defrecord ^:private Directory [names by-name failures failed templates]
  LibraryDirectory
  (library-names [_] names)
  (find-library [_ lib-name]
    (or (get by-name lib-name)
        (when-let [cause (get failed lib-name)]
          (unavailable-library lib-name cause))))
  LibraryDiagnostics
  (library-diagnostics [_] failures)
  UrlTemplates
  (url-template [_ lib-name] (get templates lib-name)))

(defn- bounded-pr [x] (binding [*print-length* 8 *print-level* 4] (pr-str x)))

(defn- failure-problem
  "Why `failure` cannot stand for a library that failed to load, given
  the names of the libraries that did, or nil."
  [names failure]
  (cond
    (not (diagnostic? failure))
    (str "a library failure must be a diagnostic, got " (bounded-pr failure))
    (not (valid-name? (:library failure)))
    (str "a library failure must name its library under :library, got "
         (bounded-pr (:library failure)))
    (contains? names (:library failure))
    (str "library " (pr-str (:library failure)) " both loaded and failed")
    :else nil))

(defn- templates-problem
  "Why `templates` cannot be a directory's `:url-templates`, or nil."
  [templates]
  (if-not (map? templates)
    (str ":url-templates must be a map of library name to template, got "
         (bounded-pr templates))
    (some (fn [[lib-name template]]
            (if-not (valid-name? lib-name)
              (str ":url-templates names no valid library: " (bounded-pr lib-name))
              (when-let [problem (url/template-problem template)]
                (str "library " (pr-str lib-name) ": " problem))))
          templates)))

(defn library-directory
  "A LibraryDirectory over `libraries`, an ordered seq of Library values.
  The directory's order is theirs.

  `failures`, default none, are the diagnostics of the libraries that
  could not be loaded, each naming its library under `:library` (as
  `haselnuss.library.directory` builds them). The directory reports them,
  in order, as its `library-diagnostics`, and `find-library` on a name
  they hold answers an `:unavailable-library` diagnostic carrying the
  first of them for that name under `:cause` -- so a reference to a
  library that failed is told apart from one to a library nobody named.
  Several failures may name one library.

  `opts`: `:url-templates`, a map of library name to URL template
  (`haselnuss.library.url`), which the directory answers through
  `UrlTemplates` (TASK-155).

  Returns a diagnostic instead when two libraries share a name
  (`:duplicate-library`), an item is not a Library, a failure is not a
  diagnostic naming a library that is not also among `libraries`, or a
  URL template is invalid (`:invalid-library-directory`). When a bad item
  is itself a diagnostic -- typically a provider that could not build its
  library -- it is kept whole under `:cause`."
  ([libraries] (library-directory libraries []))
  ([libraries failures] (library-directory libraries failures {}))
  ([libraries failures {:keys [url-templates] :or {url-templates {}}}]
   (let [libraries (vec libraries)
         failures (vec failures)]
     (if-let [bad (first (remove #(satisfies? Library %) libraries))]
       (if (diagnostic? bad)
         {:type :invalid-library-directory
          :level :error
          :cause bad
          :message (str "a library could not be built: " (:message bad))}
         {:type :invalid-library-directory
          :level :error
          :message (str "a library directory holds Library values, got " (bounded-pr bad))})
       (let [names (mapv library-name libraries)
             name-set (set names)
             dupe (first (for [[n c] (frequencies names) :when (> c 1)] n))
             problem (or (some #(failure-problem name-set %) failures)
                         (templates-problem url-templates))]
         (cond
           dupe
           {:type :duplicate-library
            :level :error
            :library dupe
            :message (str "two libraries are both named " (pr-str dupe))}
           problem
           {:type :invalid-library-directory :level :error :message problem}
           :else
           (->Directory names (zipmap names libraries) failures
                        (reduce (fn [m {lib-name :library :as f}]
                                  (if (contains? m lib-name) m (assoc m lib-name f)))
                                {} failures)
                        url-templates)))))))

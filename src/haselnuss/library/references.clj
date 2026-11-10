(ns haselnuss.library.references
  "Resolving a book reference in a build (doc-10, \"Resolver\" and \"Error
  handling & diagnostics\"; TASK-154): from the reference as written to a
  Passage of a book of a library in the library directory, or to the
  diagnostic that says why not.

  `haselnuss.resolver` cannot require this namespace -- the providers it
  reaches require the resolver -- so a build hands it in:
  `books-option` returns the `:books` option of `haselnuss.resolver/
  resolve-document`, whose `:resolve` the quote step and the link step
  (TASK-155) call once per reference.

  ## Lazy

  The library directory is given as a 0-argument fn (`directory-loader`
  builds the usual one) and called at most once, on the first reference
  -- so a document with no book reference never loads it (doc-10: \"only
  a document that actually uses a book reference is affected\"). A
  LibraryDirectory value is taken as well, for a caller that has one.
  The fn answering nil means there is no library directory at all.

  ## What `:resolve` answers

  `(resolve written parsed)` -- `written` the reference as written, `parsed` its
  `haselnuss.book-ref/parse-book-ref` -- answers `{:passage Passage :id
  BookId :warnings [...] :diagnostics [...]}`, `:id` being the book's
  current (canonical) id, or `{:failure diagnostic :diagnostics [...]}`.

  `:failure` is one of, each carrying `:ref` and a message naming it:

  - `:no-library-directory` -- no library directory file was found;
  - `:unknown-library` (`:library`) -- the directory has no such library;
  - `:unavailable-library` -- the directory names it, but it failed to
    load (`haselnuss.library/find-library`'s own diagnostic, its load
    problem under `:cause`);
  - `:unknown-book` (`:library`, `:slug`) -- the library has no such book;
  - `:ambiguous-book` -- the library's own diagnostic: more than one book
    has the slug;
  - `:scripts-disabled`/`:script-failed` -- the library's own diagnostic:
    no listed book has the slug, and a generated shelf that might hold it
    listed none (TASK-158);
  - whatever `haselnuss.library.passage/resolve-passage` answered instead
    of a Passage (`:unknown-label`, `:bad-label-range`,
    `:locator-unsupported`, `:bad-locator`, `:book-failed`, ...).

  `:warnings` concern the reference and the reference resolved:
  `:stale-shelf` (`:level :info`, `:written` and `:current` shelf paths)
  when the reference writes a shelf path that is not the book's.

  `:diagnostics` concern the build, and each is handed back exactly once
  per build, by whichever call came first: the library directory's own
  load problems (`haselnuss.library/library-diagnostics`, on the call that
  loaded it) and a book's load warnings (`haselnuss.library.passage/
  take-book-diagnostics!`).

  ## URLs (TASK-155)

  The Passage's `:url` is the one the book's library entry's URL template
  fills in (`haselnuss.library/url-template`, `haselnuss.library.url/
  fill`) for the book's CURRENT id and the passage as written, and there
  is none without a template -- even when a Locatable book's `locate`
  answered one: doc-10 makes the library directory entry the only source
  of a URL.

  A template the directory answers is checked before it is filled
  (`haselnuss.library.url/template-problem`): `haselnuss.library.
  directory` never lets an invalid one through, but another
  `UrlTemplates` directory might. An invalid one is treated as no
  template, and reported once per build per library as an
  `:invalid-url-template` build diagnostic (`:level :error`, `:library`,
  `:url`) -- not located, since the problem is the directory's, not any
  one reference's -- in the `:diagnostics` of the call that met it.

  `:url`, `(url {:id BookId :passage? Passage})`, answers `{:url?
  :diagnostics}` for a reference that is already resolved (a quoted
  cross-reference the quote step settled, whose canonical reference gives
  the book's current id): the URL the template fills in, without looking
  the book up. `:diagnostics` are the directory's load problems when this
  call loaded it, as for `:resolve`.

  A library directory file that cannot be read or is invalid is a hard
  error (doc-10): loading it throws, out of the build."
  (:require [clojure.string :as str]
            [haselnuss.book-ref :as book-ref]
            [haselnuss.library :as library]
            [haselnuss.library.directory :as directory]
            [haselnuss.library.passage :as passage]
            [haselnuss.library.url :as url]))

(defn directory-loader
  "A 0-argument fn loading the library directory from the file
  `haselnuss.library.directory/find-directory-file` picks from `opts`
  (`:path`, `:env`, `:home`), built through `(:registry opts)`, or
  answering nil when there is no such file -- so that \"no directory\" can
  be told apart from an empty one."
  [opts]
  (fn []
    (when-let [file (directory/find-directory-file opts)]
      (directory/build-directory file (directory/read-directory-file file) opts))))

(defn- written-message
  "`message` about the reference `written`, prefixed so it names it."
  [written message]
  (str "book reference " (pr-str written) ": " message))

(defn- failure
  [written diagnostic]
  {:failure (-> diagnostic
                (assoc :ref written)
                (update :message #(written-message written %)))})

(defn- lookup-failure
  [written diagnostic-type message extra]
  (failure written (merge {:type diagnostic-type :level :warning :message message} extra)))

(defn- find-book
  "`{:book Book}` for `parsed`, written `written`, in `dir`, or a
  `{:failure ...}`."
  [dir written {:keys [id]}]
  (let [{lib-name :library slug :slug} id]
    (if (nil? dir)
      (lookup-failure written :no-library-directory
                      (str "no library directory was found (pass --libraries, set $"
                           directory/env-var " or create ~/.config/haselnuss/libraries.yaml)")
                      {})
      (let [lib (library/find-library dir lib-name)]
        (cond
          (nil? lib)
          (let [names (library/library-names dir)]
            (lookup-failure written :unknown-library
                            (str "the library directory has no library " (pr-str lib-name)
                                 (if (seq names)
                                   (str " (it has: " (str/join ", " names) ")")
                                   " (it has none)"))
                            {:library lib-name}))
          (library/diagnostic? lib) (failure written lib)
          :else
          (let [book (library/find-book lib slug)]
            (cond
              (nil? book)
              (lookup-failure written :unknown-book
                              (str "library " (pr-str lib-name) " has no book " (pr-str slug))
                              {:library lib-name :slug slug})
              (library/diagnostic? book) (failure written book)
              :else {:book book})))))))

(defn- stale-shelf
  "The `:stale-shelf` info for the reference `written`, whose shelf path
  is `shelf`, to the book `id`, or nil when it wrote none or the right
  one."
  [written shelf id]
  (when (and (seq shelf) (not= shelf (:shelf id)))
    {:type :stale-shelf
     :level :info
     :written (str/join "/" shelf)
     :current (str/join "/" (:shelf id))
     :canonical (book-ref/format-book-id id)
     :message (written-message written (str "the book is now at "
                                            (book-ref/format-book-id id)
                                            "; the reference still resolves, but its shelf path is stale"))}))

(defn books-option
  "The `:books` option of `haselnuss.resolver/resolve-document` for one
  build: `{:resolve f :url g}` (see the namespace docstring), plus `:self`
  when given.

  `opts`: `:directory`, a 0-argument fn answering the LibraryDirectory or
  nil (called at most once, on the first reference), or a
  LibraryDirectory; `:context`, the build's
  `haselnuss.library.passage/context` (default a fresh one with no
  resolver options -- a build passes its own, so books number with its
  lexicon); `:self`, the BookId of the document being built when it is a
  book itself; `:directory-reported?`, true when the caller has already
  reported the directory's own load problems (the CLI does, having loaded
  the directory itself to find a book given as its input), so they are
  not handed back a second time."
  [{:keys [directory context self directory-reported?]}]
  (let [ctx (or context (passage/context))
        dir (delay (if (satisfies? library/LibraryDirectory directory)
                     directory
                     (when directory (directory))))
        reported (atom (boolean directory-reported?))
        directory-diagnostics
        (fn []
          (let [d @dir]
            (if (and d (compare-and-set! reported false true)
                     (satisfies? library/LibraryDiagnostics d))
              (vec (library/library-diagnostics d))
              [])))
        bad-templates (atom #{})
        url-of
        (fn [id passage]
          (let [d @dir
                lib-name (:library id)
                template (when (satisfies? library/UrlTemplates d)
                           (library/url-template d lib-name))
                problem (when (some? template) (url/template-problem template))]
            (cond
              (nil? template) {:diagnostics []}
              problem
              {:diagnostics
               (let [[old] (swap-vals! bad-templates conj lib-name)]
                 (if (contains? old lib-name)
                   []
                   [{:type :invalid-url-template
                     :level :error
                     :library lib-name
                     :url template
                     :message (str "library " (pr-str lib-name) ": " problem
                                   "; its links and quotes have no URL")}]))}
              :else {:url (url/fill template id passage) :diagnostics []})))
        resolve-ref
        (fn [written parsed]
          (let [found (find-book @dir written parsed)
                build-level (directory-diagnostics)]
            (if-let [book (:book found)]
              (let [{:keys [id]} (library/book-entry book)
                    result (passage/resolve-passage book (:passage parsed) ctx)
                    diagnostics (into build-level (passage/take-book-diagnostics! ctx))]
                (if (library/diagnostic? result)
                  (assoc (failure written (assoc result :canonical (book-ref/format-book-id id)))
                         :diagnostics diagnostics)
                  (let [{u :url url-diagnostics :diagnostics} (url-of id (:passage parsed))]
                    {:passage (if u (assoc result :url u) (dissoc result :url))
                     :id id
                     :warnings (if-let [w (stale-shelf written (:shelf (:id parsed)) id)] [w] [])
                     :diagnostics (into diagnostics url-diagnostics)})))
              (assoc found :diagnostics build-level))))]
    (cond-> {:resolve resolve-ref
             :url (fn [{:keys [id passage]}]
                    (let [build-level (directory-diagnostics)
                          result (url-of id passage)]
                      (update result :diagnostics #(into build-level %))))}
      self (assoc :self self))))

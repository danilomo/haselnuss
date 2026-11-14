(ns haselnuss.library.memory
  "The `:memory` library provider (doc-10, \"Provider registry\";
  TASK-149): books held as values. It is what every later library unit is
  tested through, and the path for embedding Haselnuss in another program.

  `memory-library` takes a provider entry config, `{:name \"bible\" :books
  [...]}`, so it can be registered as the `:memory` provider's
  `(fn [entry-config] Library)` unchanged. Each item of `:books` is either
  a value that already satisfies `haselnuss.library/Book`, or a
  `haselnuss.library/virtual-book` spec map, which is built here -- so a
  spec map gets a fresh document cache every time the library is
  constructed, i.e. once per build.

  ## Indexing

  Books are indexed by `book-ref/book-key` (library + slug), never by the
  BookId map, whose `:shelf` must not take part in identity. Every book's
  library must be this library, so within one library the key is the
  slug.

  Two books sharing a slug are not an error at construction (doc-10 makes
  a slug collision a library-level condition that only breaks references
  to that slug): both are listed, `library-diagnostics` reports one
  `:duplicate-slug` naming both canonical ids, and `find-book` on that slug
  answers an `:ambiguous-book` diagnostic naming both.
  `haselnuss.library/listed-books` hands out every book, both included,
  in listing order (TASK-241).

  ## Failure

  A config that cannot make a library -- a bad name, an item that is
  neither a Book nor a valid spec, a ready-made Book whose `book-entry` is
  not already a BookEntry (string `:title`, non-string `:lang`, ...), a
  book from another library -- returns an `:invalid-library` diagnostic
  instead of a Library, naming every problem. A ready-made Book is
  rejected rather than normalised so that `find-book` can hand back the
  very value it was given, whose `book-entry` then matches the listing."
  (:require [clojure.string :as str]
            [haselnuss.book-ref :as book-ref]
            [haselnuss.library :as library]))

(defn- colliding-ids
  "The canonical ids of `shelved`'s `[entry book]` pairs."
  [shelved]
  (mapv (comp book-ref/format-book-id :id first) shelved))

(defrecord ^:private MemoryLibrary [lib-name entries index tree diagnostics books]
  library/Library
  (library-name [_] lib-name)
  (shelf-tree [_] tree)
  (list-books [_] entries)
  (find-book [_ slug]
    (let [shelved (when (string? slug)
                    (get index (book-ref/book-key {:library lib-name :slug slug})))]
      (cond
        (empty? shelved) nil
        (= 1 (count shelved)) (second (first shelved))
        :else
        (let [ids (colliding-ids shelved)]
          {:type :ambiguous-book
           :library lib-name
           :slug slug
           :books ids
           :message (str "slug " (pr-str slug) " in library " (pr-str lib-name)
                         " names more than one book: " (str/join ", " ids))}))))

  library/LibraryDiagnostics
  (library-diagnostics [_] diagnostics)

  library/ListedBooks
  (listed-books [_] books))

(defn- duplicate-slugs
  "A `:duplicate-slug` diagnostic for every key of `index` held by more
  than one book."
  [lib-name index]
  (for [[[_ slug] shelved] (sort-by key index)
        :when (< 1 (count shelved))
        :let [ids (colliding-ids shelved)]]
    {:type :duplicate-slug
     :library lib-name
     :slug slug
     :books ids
     :message (str "library " (pr-str lib-name) " has more than one book with slug "
                   (pr-str slug) ": " (str/join ", " ids))}))

(defn- bounded-str [x] (binding [*print-length* 8 *print-level* 4] (pr-str x)))

(defn- entry-shape-problem
  "Why `entry`, returned by a ready-made Book, is not already a BookEntry,
  or nil. A virtual-book spec is normalised by `virtual-book`; a Book
  value is held as is, so its entry must already be in shape."
  [entry]
  (let [id (:id entry)]
    (cond
      (not (map? entry)) (str "book-entry must return a map, got " (bounded-str entry))
      (not (library/valid-book-id? id))
      (str "a book's entry has no valid :id: " (bounded-str entry))
      (library/entry-problem (dissoc entry :id))
      (str "book " (book-ref/format-book-id id) ": "
           (library/entry-problem (dissoc entry :id)))
      (or (not= (library/book-entry-value id entry)
                (select-keys entry [:id :title :lang :ddc :tags]))
          ;; `=` equates a list with a vector, so check the type itself
          (and (contains? entry :tags) (not (vector? (:tags entry)))))
      (str "book " (book-ref/format-book-id id)
           ": book-entry must return a BookEntry, with :title as inlines and "
           ":tags as a vector")
      :else nil)))

(defn- build-book
  "The `[entry book]` pair for `item`, or a problem string."
  [lib-name item]
  (let [book (cond
               (satisfies? library/Book item) item
               (map? item) (library/virtual-book item)
               :else nil)
        entry (when (satisfies? library/Book book)
                (try (library/book-entry book)
                     (catch Throwable t (str "book-entry threw: " (ex-message t)))))]
    (cond
      (nil? book) (str "not a Book or a virtual-book spec: " (bounded-str item))
      (library/diagnostic? book) (:message book)
      (string? entry) entry
      (entry-shape-problem entry) (entry-shape-problem entry)
      (not= lib-name (:library (:id entry)))
      (str "book " (book-ref/format-book-id (:id entry))
           " belongs to library " (pr-str (:library (:id entry)))
           ", not " (pr-str lib-name))
      :else [entry book])))

(defn memory-library
  "A Library holding the books in `(:books entry-config)` under the name
  `(:name entry-config)`, or an `:invalid-library` diagnostic. See the
  namespace docstring."
  [entry-config]
  (let [lib-name (:name entry-config)
        items (:books entry-config)
        problems (cond
                   (not (map? entry-config)) ["the config must be a map"]
                   (not (library/valid-name? lib-name))
                   [(str "library name " (pr-str lib-name)
                         " must match [a-z0-9][a-z0-9-]*")]
                   (not (or (nil? items) (sequential? items)))
                   [":books must be a sequence"]
                   :else nil)
        built (when-not problems (mapv #(build-book lib-name %) items))
        problems (or problems (seq (filter string? built)))]
    (if problems
      {:type :invalid-library
       :library lib-name
       :message (str "cannot build memory library " (pr-str lib-name) ": "
                     (str/join "; " problems))}
      (let [entries (mapv first built)
            index (group-by (comp book-ref/book-key :id first) built)]
        (->MemoryLibrary lib-name
                         entries
                         index
                         (library/shelf-tree-of entries)
                         (vec (duplicate-slugs lib-name index))
                         (mapv second built))))))

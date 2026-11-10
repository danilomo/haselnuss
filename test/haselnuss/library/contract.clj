(ns haselnuss.library.contract
  "The provider contract (doc-10, \"Testing strategy\"; TASK-149 AC #4):
  the behaviour every Library provider must have, as one function any
  provider's test namespace runs against a library of its own.

      (deftest contract-test
        (contract/check-provider-contract
         {:library        lib        ; the Library under test
          :expected       contract/sample-expectation
          :document-loads (fn [] n)}))  ; optional

  `:expected` describes what the library was built to hold:

      {:name        \"reformed\"
       :books       [{:id BookId :title string-or-inlines :lang? ...} ...]
       :shelf-tree  {\"confessions\" {\"reformed\" {}}}
       :diagnostics [{:type :invalid-book-name ...} ...]}   ; optional

  `:books` may hold two books with the same slug (on different shelves).
  The contract then requires what doc-10 requires of a slug collision:
  both are listed, `find-book` on that slug answers an `:ambiguous-book`
  diagnostic, and `library-diagnostics` reports a `:duplicate-slug` for
  it. `collision-expectation` is a ready-made colliding fixture.

  `:diagnostics` lists the library diagnostics expected BESIDES the
  `:duplicate-slug`s the collisions imply, each as a map of the keys that
  must match (e.g. `{:type :invalid-book-name}`); it defaults to none. The
  library's `library-diagnostics` (none when it does not satisfy
  `LibraryDiagnostics`) must hold exactly those, each a `{:type :message}`
  diagnostic.

  `sample-expectation` is a ready-made non-colliding fixture; a provider
  whose fixture mirrors it (the `:memory` tests build theirs from
  `sample-books`) gets a known-good expectation for free, and one with its
  own fixture passes its own.

  `:document-loads`, when given, returns how many book documents the
  provider has produced so far (a counter the test wires into its
  fixture). The contract then also proves that listing, shelf-tree,
  library-diagnostics, find-book and book-entry never load a document,
  and that a book's document is loaded at most once however many times it
  is looked up and asked for -- so a provider must cache per build, not
  per `find-book` call.

  The assertions are plain `clojure.test` `is` forms, so they report
  inside whichever `deftest` calls this."
  (:require [clojure.test :refer [is testing]]
            [haselnuss.ast :as ast]
            [haselnuss.book-ref :as book-ref]
            [haselnuss.library :as library]))

;; --- reusable fixtures -------------------------------------------------------

(def sample-expectation
  "A small library: two books on a nested shelf, one on another shelf and
  one at the root, with and without `:lang`/`:ddc`/`:tags`."
  {:name "reformed"
   :books [{:id (book-ref/book-id "reformed" ["confessions" "reformed"] "heidelberg")
            :title "Heidelberg Catechism" :lang "en" :ddc "238.42"
            :tags ["catechism"]}
           {:id (book-ref/book-id "reformed" ["confessions" "reformed"] "westminster")
            :title "Westminster Confession" :lang "en"}
           {:id (book-ref/book-id "reformed" ["creeds"] "apostles")
            :title "Apostles' Creed"}
           {:id (book-ref/book-id "reformed" "notes")
            :title "Notes" :lang "pt-BR"}]
   :shelf-tree {"confessions" {"reformed" {}} "creeds" {}}})

(def collision-expectation
  "A library where two books on different shelves share the slug `same`,
  beside one book that does not collide."
  {:name "shared"
   :books [{:id (book-ref/book-id "shared" ["a"] "same") :title "Same A"}
           {:id (book-ref/book-id "shared" ["b" "c"] "same") :title "Same B"}
           {:id (book-ref/book-id "shared" "other") :title "Other"}]
   :shelf-tree {"a" {} "b" {"c" {}}}})

(defn sample-document
  "A small valid Document for the book `id`: one labelled section."
  [id]
  {:meta {:title [{:t :str :text (:slug id)}]}
   :blocks [{:t :section :level 1
             :heading [{:t :str :text (:slug id)}]
             :attr {:id "sec:intro" :classes [] :props {}}
             :blocks [{:t :para :inlines [{:t :str :text "Text."}]}]}]})

(defn sample-books
  "`virtual-book` specs for `expectation`'s books, each document produced
  by `sample-document`. `on-load` (optional) is called each time a
  document fn runs, so a test can count loads."
  ([expectation] (sample-books expectation (fn [])))
  ([expectation on-load]
   (for [{:keys [id] :as book} (:books expectation)]
     {:id id
      :entry (dissoc book :id)
      :document (fn [] (on-load) (sample-document id))})))

;; --- the contract ------------------------------------------------------------

(defn- valid-entry?
  [lib-name entry]
  (and (map? entry)
       (library/valid-book-id? (:id entry))
       (= lib-name (:library (:id entry)))
       (vector? (:title entry))
       (every? #(ast/valid? ast/Inline %) (:title entry))
       (or (nil? (:lang entry)) (string? (:lang entry)))
       (or (nil? (:ddc entry)) (string? (:ddc entry)))
       (or (nil? (:tags entry)) (and (vector? (:tags entry))
                                     (every? string? (:tags entry))))))

(defn- key-of [entry] (book-ref/book-key (:id entry)))

(defn- slug-frequencies
  "How many of `books` hold each book-key -- the identity resolution uses."
  [books]
  (frequencies (map key-of books)))

(defn- absent-slug
  "A slug that names no book in `expected`."
  [expected]
  (let [slugs (set (map (comp :slug :id) (:books expected)))]
    (first (remove slugs (map #(str "no-such-book-" %) (range))))))

(defn- diagnostics-of [lib]
  (if (satisfies? library/LibraryDiagnostics lib)
    (library/library-diagnostics lib)
    []))

(defn- matches? [expected actual] (= expected (select-keys actual (keys expected))))

(defn- check-diagnostics
  [lib expected-diagnostics]
  (let [actual (diagnostics-of lib)]
    (is (sequential? actual))
    (doseq [d actual]
      (is (library/diagnostic? d) (pr-str d)))
    (is (= (count expected-diagnostics) (count actual))
        (str "expected " (pr-str expected-diagnostics) ", got " (pr-str actual)))
    (doseq [e expected-diagnostics]
      (is (some #(matches? e %) actual) (str "no diagnostic matches " (pr-str e))))))

(defn check-provider-contract
  "Assert that `library` behaves as a Library provider must. See the
  namespace docstring for the arguments."
  [{:keys [library expected document-loads]}]
  (let [loads (or document-loads (constantly nil))
        loads-before (loads)
        lib-name (:name expected)
        expected-counts (slug-frequencies (:books expected))
        colliding (set (for [[k n] expected-counts :when (< 1 n)] (second k)))
        unique (remove (comp colliding :slug :id) (:books expected))]
    (testing "the provider satisfies Library"
      (is (satisfies? library/Library library)))

    (testing "library-name is the configured name"
      (is (= lib-name (library/library-name library))))

    (let [entries (library/list-books library)
          listed (group-by key-of entries)]
      (testing "list-books returns one well-formed BookEntry per book"
        (is (sequential? entries))
        (doseq [entry entries]
          (is (valid-entry? lib-name entry) (pr-str entry)))
        (is (= expected-counts (slug-frequencies entries))
            "the same books, colliding slugs included, by book-key"))

      (testing "each listed entry carries the book's current shelf, title and metadata"
        (doseq [{:keys [id] :as book} (:books expected)
                :let [entry (first (filter #(= id (:id %))
                                           (get listed (book-ref/book-key id))))]]
          (is (some? entry) (str "no entry lists " (book-ref/format-book-id id)))
          (is (= (library/title-inlines (:title book)) (:title entry)))
          (is (= (select-keys book [:lang :ddc :tags])
                 (select-keys entry [:lang :ddc :tags])))))

      (testing "shelf-tree reports every shelf a book sits on"
        (let [tree (library/shelf-tree library)]
          (is (= (:shelf-tree expected) tree))
          (doseq [{{:keys [shelf]} :id} entries :when (seq shelf)]
            (is (map? (get-in tree shelf)) (pr-str shelf)))))

      (testing "library-diagnostics reports each slug collision and the expected scan problems"
        (check-diagnostics library
                           (concat (for [slug (sort colliding)]
                                     {:type :duplicate-slug :slug slug})
                                   (:diagnostics expected))))

      (testing "find-book returns the listed book by slug"
        (doseq [{:keys [id]} unique
                :let [book (library/find-book library (:slug id))]]
          (is (satisfies? library/Book book) (:slug id))
          (when (satisfies? library/Book book)
            (is (= (first (get listed (book-ref/book-key id)))
                   (library/book-entry book))))))

      (testing "find-book on a colliding slug answers :ambiguous-book"
        (doseq [slug colliding
                :let [found (library/find-book library slug)]]
          (is (library/diagnostic? found) slug)
          (is (= :ambiguous-book (:type found)) slug)))

      (testing "find-book answers nil for a slug no book has, without throwing"
        (is (nil? (library/find-book library (absent-slug expected))))
        (is (nil? (library/find-book library nil)))
        (is (nil? (library/find-book library 42))))

      (when document-loads
        (testing "listing, shelf-tree, diagnostics, find-book and book-entry load no document"
          (is (= loads-before (loads)))))

      (testing "document yields a valid Haselnuss Document, the same value each lookup"
        (doseq [{:keys [id]} unique
                :let [first-book (library/find-book library (:slug id))
                      again (library/find-book library (:slug id))]
                ;; a missing book was already reported by find-book's check
                :when (and (satisfies? library/Book first-book)
                           (satisfies? library/Book again))
                :let [doc (library/document first-book)]]
          (is (ast/valid? ast/Document doc) (book-ref/format-book-id id))
          (is (= doc (library/document first-book)))
          (is (= doc (library/document again)))))

      (when document-loads
        (testing "each book's document is loaded at most once across repeated lookups"
          (is (<= (- (loads) loads-before) (count unique))))))))

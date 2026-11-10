(ns haselnuss.library.passage-test
  "Passage resolution (TASK-153): `haselnuss.library.passage/resolve-passage`
  over `:memory` books built from `.hdoc` source, and over the filesystem
  fixture library `test/fixtures/libraries/passages/` -- a book folder
  whose sections come in through `!include` (one of them missing) and
  that cites from its own `refs.json`, and a book whose bibliography is
  missing. Counting is done through injected fns (`:resolve-fn`, a
  counting Book), never `with-redefs`, so nothing here races a namespace
  running concurrently."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [haselnuss.book-ref :as book-ref]
            [haselnuss.library :as library]
            [haselnuss.library.directory :as directory]
            [haselnuss.library.filesystem :as fs]
            [haselnuss.library.memory :as memory]
            [haselnuss.library.passage :as passage]
            [haselnuss.parser :as parser]
            [haselnuss.resolver :as resolver]))

;; --- books -------------------------------------------------------------------

(def ^:private catechism-source
  "---
title: Heidelberg Catechism
lang: en
---

# Comfort {#sec:comfort}

Before the questions.

## Lord's Day 1 {#sec:q1}

What is your only comfort?

## Lord's Day 2 {#sec:q2}

How many things are necessary? See @sec:q1.

## Lord's Day 3 {#sec:q3}

Whence knowest thou thy misery? Look at [this]{#x:inline}.

# Misery {#sec:misery}

::: {#qa:one}
A question with a custom kind.
:::

## Lord's Day 4 {#sec:q4}

Doth not God then wrong man?
")

(def ^:private catecismo-source
  "---
title: Catecismo de Heidelberg
lang: pt-BR
---

# Consolo {#sec:consolo}

Qual é o seu único consolo?
")

(defn- label-of
  "The `attr.id` of block `b`."
  [b]
  (get-in b [:attr :id]))

(defn- text-of
  "The concatenated `:str` text of `inlines`, `:space` as a space."
  [inlines]
  (apply str (map #(case (:t %) :str (:text %) :space " " "") inlines)))

(defn- catechism-library
  "A memory library holding the catechism (with `extra` virtual-book spec
  keys) and the Portuguese catechism."
  ([] (catechism-library {}))
  ([extra]
   (memory/memory-library
    {:name "reformed"
     :books [(merge {:id (book-ref/book-id "reformed" ["confessions"] "heidelberg")
                     :entry {:title "Heidelberg Catechism" :lang "en"}
                     :document (fn [] (parser/parse catechism-source))}
                    extra)
             {:id (book-ref/book-id "reformed" ["confessions"] "catecismo")
              :entry {:title "Catecismo de Heidelberg" :lang "pt-BR"}
              :document (fn [] (parser/parse catecismo-source))}]})))

(defn- heidelberg
  ([] (heidelberg {}))
  ([extra] (library/find-book (catechism-library extra) "heidelberg")))

(defn- resolve*
  "`resolve-passage` of the passage written `text` (after `#`), or of the
  whole book when `text` is nil, in a fresh context unless one is given."
  ([book text] (resolve* book text (passage/context)))
  ([book text ctx]
   (passage/resolve-passage book
                            (when text
                              (:passage (book-ref/parse-book-ref (str "x:y#" text))))
                            ctx)))

;; --- AC #1: a label ----------------------------------------------------------

(deftest label-test
  (testing "a label returns that node's blocks and a title from the book's numbering"
    (let [p (resolve* (heidelberg) "sec:q2")]
      (is (= "reformed:confessions/heidelberg#sec:q2" (:ref p)))
      (is (= "Heidelberg Catechism, Section 1.2" (text-of (:title p))))
      (is (= ["sec:q2"] (mapv label-of (:blocks p))))
      (is (= :section (:t (first (:blocks p)))))
      (is (not (contains? p :url)))
      (testing "-- the blocks are the RESOLVED document's, so a cross-reference
                inside them already reads with the book's own numbering"
        (is (= [{:t :cross-ref :label "sec:q1" :target "sec:q1" :text "Section 1.1"}]
               (filter #(= :cross-ref (:t %))
                       (tree-seq coll? seq (:blocks p))))))))
  (testing "a top-level section and a nested one number as the book numbers them"
    (is (= "Heidelberg Catechism, Section 2"
           (text-of (:title (resolve* (heidelberg) "sec:misery")))))
    (is (= "Heidelberg Catechism, Section 2.1"
           (text-of (:title (resolve* (heidelberg) "sec:q4"))))))
  (testing "the words come from the BOOK's language"
    (let [book (library/find-book (catechism-library) "catecismo")]
      (is (= "Catecismo de Heidelberg, Seção 1"
             (text-of (:title (resolve* book "sec:consolo")))))))
  (testing "the kinds come from the build's lexicon, through the context"
    (let [lexicon (assoc resolver/default-lexicon
                         :qa {:counter :global :words {"en" {:singular "Question"}}})
          ctx (passage/context {:resolve-opts {:lexicon lexicon}})]
      (is (= "Heidelberg Catechism, Question 1"
             (text-of (:title (resolve* (heidelberg) "qa:one" ctx)))))
      (testing "-- and a label no kind numbers prints as the label itself"
        (is (= "Heidelberg Catechism, qa:one"
               (text-of (:title (resolve* (heidelberg) "qa:one")))))
        (is (= ["qa:one"] (mapv label-of (:blocks (resolve* (heidelberg) "qa:one")))))))))

;; --- AC #2: a range ----------------------------------------------------------

(deftest range-test
  (testing "a range returns every sibling from the first label through the last"
    (let [p (resolve* (heidelberg) "sec:q1..sec:q3")]
      (is (= "reformed:confessions/heidelberg#sec:q1..sec:q3" (:ref p)))
      (is (= ["sec:q1" "sec:q2" "sec:q3"] (mapv label-of (:blocks p))))
      (is (= "Heidelberg Catechism, Section 1.1–1.3" (text-of (:title p))))))
  (testing "top-level siblings, and a range of one"
    (is (= ["sec:comfort" "sec:misery"]
           (mapv label-of (:blocks (resolve* (heidelberg) "sec:comfort..sec:misery")))))
    (let [p (resolve* (heidelberg) "sec:q2..sec:q2")]
      (is (= ["sec:q2"] (mapv label-of (:blocks p))))
      (is (= "Heidelberg Catechism, Section 1.2" (text-of (:title p))))))
  (testing "ends numbered by different words name both in full"
    (let [lexicon (assoc resolver/default-lexicon
                         :qa {:counter :global :words {"en" {:singular "Question"}}})
          p (resolve* (heidelberg) "qa:one..sec:q4"
                      (passage/context {:resolve-opts {:lexicon lexicon}}))]
      (is (= ["qa:one" "sec:q4"] (mapv label-of (:blocks p))))
      (is (= "Heidelberg Catechism, Question 1 – Section 2.1" (text-of (:title p))))))
  (testing "an end before its start is bad-label-range"
    (let [d (resolve* (heidelberg) "sec:q3..sec:q1")]
      (is (= :bad-label-range (:type d)))
      (is (library/diagnostic? d))
      (is (= {:book "reformed:confessions/heidelberg" :passage "sec:q3..sec:q1"
              :from "sec:q3" :to "sec:q1"}
             (select-keys d [:book :passage :from :to])))))
  (testing "labels that are not siblings are bad-label-range"
    (is (= :bad-label-range (:type (resolve* (heidelberg) "sec:q1..sec:misery"))))
    (is (= :bad-label-range (:type (resolve* (heidelberg) "sec:comfort..sec:q3"))))
    (is (= :bad-label-range (:type (resolve* (heidelberg) "sec:q3..sec:q4"))))))

(def ^:private attr
  (fn [id] {:id id :classes [] :props {}}))

(def ^:private slotted-document
  "A document with a labelled block in a single-block slot (a figure's
  `:content`), and ids on a table row and cell, which are not blocks."
  {:meta {:title [{:t :str :text "Slots"}]}
   :blocks [{:t :section :level 1 :heading [{:t :str :text "A"}] :attr (attr "sec:a")
             :blocks [{:t :figure
                       :content {:t :math-block :tex "x" :attr (attr "eq:in")}
                       :caption [{:t :str :text "In a slot"}]
                       :attr (attr "fig:f")}
                      {:t :table
                       :head {:cells [{:blocks [{:t :para :inlines [{:t :str :text "h"}]}]}]
                              :attr (attr "x:row")}
                       :rows [{:cells [{:blocks [{:t :para :inlines [{:t :str :text "c"}]}]
                                        :attr (attr "x:cell")}]}]
                       :caption [] :colspec [{:align :left}]
                       :attr (attr "tbl:t")}]}]})

(defn- slotted-book []
  (library/virtual-book {:id (book-ref/book-id "lib" "slots")
                         :entry {:title "Slots"}
                         :document (fn [] slotted-document)}))

(deftest range-in-a-single-block-slot-test
  (testing "a block in a one-block slot (a figure's :content) never makes a range throw"
    (is (not (library/diagnostic? (slotted-book))) "the fixture is a valid book")
    (testing "-- a range of one is that block"
      (let [p (resolve* (slotted-book) "eq:in..eq:in")]
        (is (= [{:t :math-block :tex "x" :attr (attr "eq:in")}] (:blocks p)))))
    (testing "-- any other range with an end there is bad-label-range"
      (is (= :bad-label-range (:type (resolve* (slotted-book) "eq:in..tbl:t"))))
      (is (= :bad-label-range (:type (resolve* (slotted-book) "fig:f..eq:in")))))
    (testing "-- and the label alone is still that block"
      (is (= ["eq:in"] (mapv label-of (:blocks (resolve* (slotted-book) "eq:in"))))))
    (testing "-- siblings around it still range"
      (is (= ["fig:f" "tbl:t"] (mapv label-of (:blocks (resolve* (slotted-book) "fig:f..tbl:t"))))))))

;; --- AC #3: unknown labels and locators --------------------------------------

(deftest unknown-label-test
  (testing "a missing label is unknown-label"
    (let [d (resolve* (heidelberg) "sec:q99")]
      (is (= {:type :unknown-label :book "reformed:confessions/heidelberg"
              :passage "sec:q99" :label "sec:q99"}
             (dissoc d :message)))
      (is (re-find #"has no label \"sec:q99\"" (:message d)))))
  (testing "either end of a range"
    (is (= "sec:q0" (:label (resolve* (heidelberg) "sec:q0..sec:q3"))))
    (is (= "sec:q9" (:label (resolve* (heidelberg) "sec:q1..sec:q9")))))
  (testing "an id on an inline is not a label, and the message says so"
    (let [d (resolve* (heidelberg) "x:inline")]
      (is (= :unknown-label (:type d)))
      (is (re-find #"on an inline span" (:message d)))))
  (testing "so is an id on a table row or cell"
    (is (re-find #"on a table row, and only a block"
                 (:message (resolve* (slotted-book) "x:row"))))
    (is (re-find #"on a table cell, and only a block"
                 (:message (resolve* (slotted-book) "x:cell"))))))

(deftest passage-value-is-idempotent-test
  (let [id (book-ref/book-id "bible" ["nt"] "romanos")
        once (library/passage-value id "14:8" {:title "Romanos 14:8"
                                               :blocks [{:t :para :inlines [{:t :str :text "v"}]}]
                                               :url "https://example.org/r"
                                               :extra 1})]
    (is (= {:ref "bible:nt/romanos#14:8" :title [{:t :str :text "Romanos 14:8"}]
            :blocks [{:t :para :inlines [{:t :str :text "v"}]}] :url "https://example.org/r"}
           once))
    (is (= once (library/passage-value id "14:8" once)))
    (let [d {:type :bad-locator :message "no"}]
      (is (= d (library/passage-value id "14:8" d))))))

(deftest locator-test
  (testing "a locator on a book that is not Locatable is locator-unsupported"
    (let [d (resolve* (heidelberg) "14:8")]
      (is (= {:type :locator-unsupported :book "reformed:confessions/heidelberg"
              :passage "14:8" :locator "14:8"}
             (dissoc d :message)))))
  (let [loads (atom 0)
        book (heidelberg {:document (fn [] (swap! loads inc) (parser/parse catechism-source))
                          :locate (fn [locator]
                                    (case locator
                                      "q1" {:title "Question 1"
                                            :blocks [{:t :para :inlines [{:t :str :text "One"}]}]
                                            :url "https://example.org/q1"}
                                      "no" {:type :bad-locator :message "no such question: no"}
                                      (throw (ex-info (str "cannot read " locator) {}))))})]
    (testing "a Locatable book's locate answers the passage, with the canonical ref"
      (is (= {:ref "reformed:confessions/heidelberg#q1"
              :title [{:t :str :text "Question 1"}]
              :blocks [{:t :para :inlines [{:t :str :text "One"}]}]
              :url "https://example.org/q1"}
             (resolve* book "q1"))))
    (testing "a rejected locator is bad-locator carrying the book's message"
      (let [d (resolve* book "no")]
        (is (= :bad-locator (:type d)))
        (is (= "no such question: no" (:message d)))
        (is (= "reformed:confessions/heidelberg" (:book d))))
      (let [d (resolve* book "zz")]
        (is (= :bad-locator (:type d)))
        (is (re-find #"cannot read zz" (:message d)))))
    (testing "a locator never loads the book's document"
      (is (zero? @loads))))
  (testing "a provider's own Locatable book is guarded the same way"
    (let [id (book-ref/book-id "bible" ["nt"] "romanos")
          book-with (fn [locate-fn]
                      (reify
                        library/Book
                        (book-entry [_] {:id id :title [{:t :str :text "Romanos"}]})
                        (document [_] {:meta {} :blocks []})
                        library/Locatable
                        (locate [_ locator] (locate-fn locator))))]
      (is (= :bad-locator
             (:type (resolve* (book-with (fn [_] (throw (RuntimeException. "verse out of range"))))
                              "14:99"))))
      (is (= :invalid-book-document (:type (resolve* (book-with (fn [_] 42)) "14:8"))))
      (is (= {:ref "bible:nt/romanos#14:8" :title [{:t :str :text "bible:nt/romanos#14:8"}]
              :blocks []}
             (resolve* (book-with (fn [_] {:blocks []})) "14:8"))))))

;; --- AC #4: the whole book ---------------------------------------------------

(deftest whole-book-test
  (testing "no passage is the book's title and its full (resolved) blocks"
    (let [p (resolve* (heidelberg) nil)
          resolved (:document (resolver/resolve-document (parser/parse catechism-source)))]
      (is (= "reformed:confessions/heidelberg" (:ref p)))
      (is (= [{:t :str :text "Heidelberg Catechism"}] (:title p)))
      (is (= (:blocks resolved) (:blocks p)))
      (is (= ["sec:comfort" "sec:misery"] (mapv label-of (:blocks p)))))))

;; --- AC #5: one resolve per book per build -----------------------------------

(defn- counting-book
  "A Book that counts its own `document` calls and does not cache them."
  [id source calls]
  (reify library/Book
    (book-entry [_] {:id id :title [{:t :str :text "Counted"}]})
    (document [_] (swap! calls inc) (parser/parse source))))

(deftest resolved-once-per-build-test
  (let [documents (atom 0)
        resolves (atom 0)
        id (book-ref/book-id "reformed" "counted")
        book (counting-book id catechism-source documents)
        ctx (passage/context {:resolve-fn (fn [document opts]
                                            (swap! resolves inc)
                                            (resolver/resolve-document document opts))})
        results (mapv #(resolve* book % ctx)
                      ["sec:q1" "sec:q1..sec:q3" nil "sec:misery" "sec:nope" "sec:q3..sec:q1"])]
    (testing "every passage was answered"
      (is (= [false false false false true true] (mapv library/diagnostic? results))))
    (testing "the document was loaded and resolved once, however many passages"
      (is (= 1 @documents))
      (is (= 1 @resolves)))
    (testing "a book is the same book whatever shelf it is reached by"
      (resolve* (counting-book (book-ref/book-id "reformed" ["moved"] "counted")
                               catechism-source documents)
                "sec:q2" ctx)
      (is (= 1 @documents)))
    (testing "another build -- another context -- resolves it afresh"
      (resolve* book "sec:q1" (passage/context))
      (is (= 2 @documents))))
  (testing "the build's resolver options reach the book; the per-document ones do not"
    (let [seen (atom nil)
          ctx (passage/context {:resolve-opts {:lexicon resolver/default-lexicon
                                               :front-matter-names #{"abstract"}
                                               :includes {:load (fn [_] (throw (Exception.)))}
                                               :overlay-sources [:x]
                                               :bibliography {"k" {}}}
                                :resolve-fn (fn [document opts]
                                              (reset! seen opts)
                                              (resolver/resolve-document document opts))})]
      (resolve* (heidelberg) "sec:q1" ctx)
      (is (= #{:lexicon :front-matter-names :bibliography} (set (keys @seen))))
      (is (= {} (:bibliography @seen))))))

;; --- failures from the book itself -------------------------------------------

(deftest book-failure-test
  (testing "a book that cannot produce its document fails every passage taken from it"
    (let [book (heidelberg {:document (fn [] (throw (RuntimeException. "disk on fire")))})
          d (resolve* book "sec:q1")]
      (is (= :book-failed (:type d)))
      (is (= "sec:q1" (:passage d)))
      (is (re-find #"disk on fire" (:message d)))
      (is (= :book-failed (:type (resolve* book nil))))))
  (testing "a resolver that throws on the book's document is book-failed, not a throw"
    (let [ctx (passage/context {:resolve-fn (fn [_ _] (throw (IllegalStateException. "bad AST")))})
          d (resolve* (heidelberg) "sec:q1" ctx)]
      (is (= :book-failed (:type d)))
      (is (re-find #"failed to resolve: bad AST" (:message d)))))
  (testing "a passage that is not a parsed passage is a caller bug"
    (is (thrown? clojure.lang.ExceptionInfo
                 (passage/resolve-passage (heidelberg) {:label "sec:q1"} (passage/context))))))

(deftest book-bibliography-test
  (testing "a book's citations resolve against its own bibliography, not the build's"
    (let [dir (.toFile (java.nio.file.Files/createTempDirectory
                        "passage-bib" (make-array java.nio.file.attribute.FileAttribute 0)))
          bib (io/file dir "refs.json")
          _ (spit bib "[{\"id\": \"ursinus1563\", \"title\": \"Catechismus\",
                        \"author\": [{\"family\": \"Ursinus\"}]}]")
          book (heidelberg {:document
                            (fn [] (parser/parse
                                    (str "---\ntitle: T\nbibliography: " bib "\n---\n\n"
                                         "# A {#sec:a}\n\nAs [@ursinus1563] says.\n")))})
          cite (->> (tree-seq coll? seq (:blocks (resolve* book "sec:a")))
                    (filter #(= :cite (:t %)))
                    first)]
      (is (= "[1]" (text-of (:text cite))))))
  (testing "a bibliography that cannot be read resolves against none, with one warning"
    (let [book (heidelberg {:document (fn [] (parser/parse
                                              (str "---\ntitle: T\nbibliography: /nonexistent/refs.json\n---\n\n"
                                                   "# A {#sec:a}\n\nAs [@k] says.\n")))})
          ctx (passage/context)]
      (is (= ["sec:a"] (mapv label-of (:blocks (resolve* book "sec:a" ctx)))))
      (resolve* book "sec:a" ctx)
      (is (= [{:type :unreadable-bibliography :book "reformed:confessions/heidelberg"
               :bibliography "/nonexistent/refs.json"}]
             (mapv #(select-keys % [:type :book :bibliography])
                   (passage/take-book-diagnostics! ctx))))))
  (testing "a meta.bibliography that is not a path is a warning, not silently ignored"
    ;; A provider's own Book, since `virtual-book` already rejects such a
    ;; document against the schema (`:invalid-book-document`).
    (let [book (reify library/Book
                 (book-entry [_] {:id (book-ref/book-id "lib" "odd") :title [{:t :str :text "Odd"}]})
                 (document [_] (assoc-in (parser/parse "# A {#sec:a}\n\nx\n")
                                         [:meta :bibliography] 42)))
          ctx (passage/context)]
      (is (= ["sec:a"] (mapv label-of (:blocks (resolve* book "sec:a" ctx)))))
      (let [[w & more] (passage/take-book-diagnostics! ctx)]
        (is (nil? more))
        (is (= :unreadable-bibliography (:type w)))
        (is (re-find #"must be a path" (:message w))))))
  (testing "-- likewise in a filesystem book's front matter"
    (let [dir (.toFile (java.nio.file.Files/createTempDirectory
                        "passage-bib-list" (make-array java.nio.file.attribute.FileAttribute 0)))
          _ (spit (io/file dir "listed.hdoc")
                  "---\ntitle: Listed\nbibliography: [a.json, b.json]\n---\n\n# A {#sec:a}\n\nx\n")
          book (library/find-book (fs/filesystem-library {:name "tmp" :path (str dir)}) "listed")
          ctx (passage/context)]
      (is (= ["sec:a"] (mapv label-of (:blocks (resolve* book "sec:a" ctx)))))
      (is (= [:unreadable-bibliography] (mapv :type (passage/take-book-diagnostics! ctx))))
      (is (re-find #"must be a path" (:message (first (fs/load-warnings book))))))))

;; --- the filesystem fixture --------------------------------------------------

(def ^:private kinds (into #{} (map name) (keys resolver/default-lexicon)))

(defn- fixture-library
  ([] (fixture-library {:kinds kinds}))
  ([opts]
   (fs/filesystem-library {:name "pass"
                           :path (str (io/file "test/fixtures/libraries/passages"))}
                          opts)))

(defn- inlines-of-type
  [t blocks]
  (filterv #(and (map? %) (= t (:t %))) (tree-seq coll? seq blocks)))

(deftest filesystem-book-test
  (let [lib (fixture-library)
        book (library/find-book lib "heidelberg")
        ctx (passage/context)]
    (testing "labels that come in through !include resolve, titled by the book's numbering"
      (let [p (resolve* book "sec:q1" ctx)]
        (is (= "pass:catechisms/heidelberg#sec:q1" (:ref p)))
        (is (= "Heidelberg Catechism, Section 1.1" (text-of (:title p))))
        (is (= ["sec:q1"] (mapv label-of (:blocks p))))
        (testing "-- and its citation resolves against the book's own refs.json, found
                  relative to its entry file, not the working directory"
          (is (= [{:key "ursinus1563" :mode :author}]
                 (:items (first (inlines-of-type :cite (:blocks p))))))
          (is (= "Ursinus [1]" (text-of (:text (first (inlines-of-type :cite (:blocks p))))))))))
    (testing "a range of included sections"
      (is (= ["sec:q1" "sec:q2" "sec:q3"]
             (mapv label-of (:blocks (resolve* book "sec:q1..sec:q3" ctx))))))
    (testing "the missing include is reported once, tagged with its book"
      (let [[w & more] (passage/take-book-diagnostics! ctx)]
        (is (nil? more))
        (is (= :unreadable-include (:type w)))
        (is (= "pass:catechisms/heidelberg" (:book w)))
        (is (= "parts/missing.hdoc" (:src w))))
      (resolve* book nil ctx)
      (is (= [] (passage/take-book-diagnostics! ctx))))
    (testing "a book whose bibliography is missing still resolves, with a warning"
      (let [nicene (library/find-book lib "nicene")]
        (is (= "Nicene Creed, Section 1"
               (text-of (:title (resolve* nicene "sec:creed" ctx)))))
        (is (= [:unreadable-bibliography]
               (mapv :type (passage/take-book-diagnostics! ctx))))
        (is (= [:unreadable-bibliography] (mapv :type (fs/load-warnings nicene))))
        (is (= {} (fs/bibliography nicene)))))))

(deftest filesystem-vocabulary-test
  (testing "with the build's :kinds, a book parses with its own bibliography's keys"
    (let [book (library/find-book (fixture-library) "heidelberg")
          doc (library/document book)]
      (is (= ["ursinus1563"] (mapv (comp :key first :items) (inlines-of-type :cite doc)))
          "@ursinus1563 is in refs.json; @nobody is not, so it stays prose")
      (is (= ["sec:q1"] (mapv :label (inlines-of-type :cross-ref doc))))
      (is (= {"ursinus1563" "Catechismus"}
             (update-vals (fs/bibliography book) :title)))))
  (testing "without :kinds the parse is the lenient one: both are citations"
    (let [doc (library/document (library/find-book (fixture-library {}) "heidelberg"))]
      (is (= ["ursinus1563" "nobody"]
             (mapv (comp :key first :items) (inlines-of-type :cite doc))))))
  (testing "a book whose bibliography cannot be read keeps the citation heuristic"
    (let [book (library/find-book (fixture-library) "nicene")]
      (is (library/diagnostic? (first (fs/load-warnings book))))))
  (testing "provider hands the opts to every library it builds, as a registry entry"
    (let [registry (directory/register-provider directory/default-registry :filesystem
                                                (fs/provider {:kinds kinds}))
          lib ((:filesystem registry) {:name "pass"
                                       :path (str (io/file "test/fixtures/libraries/passages"))})
          doc (library/document (library/find-book lib "heidelberg"))]
      (is (= ["ursinus1563"] (mapv (comp :key first :items) (inlines-of-type :cite doc))))))
  (testing ":kinds must be a set of strings"
    (is (= :invalid-library (:type (fixture-library {:kinds [:sec]}))))))

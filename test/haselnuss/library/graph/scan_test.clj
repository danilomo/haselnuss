(ns haselnuss.library.graph.scan-test
  "`scan-book` (TASK-240, doc-11 \"Scan pipeline & caching\"): one book ->
  one book record. A temp filesystem library (includes, file positions,
  a book that fails to parse) beside an in-memory one (the targets),
  the committed generated-shelf fixture, and a seeded fuzz of the
  nearest-labelled-ancestor walk against an independent hand-written one."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [haselnuss.ast :as ast]
            [haselnuss.book-ref :as book-ref]
            [haselnuss.library :as library]
            [haselnuss.library.filesystem :as fs]
            [haselnuss.library.graph :as graph]
            [haselnuss.library.graph.scan :as scan]
            [haselnuss.library.memory :as memory]
            [haselnuss.parser :as parser])
  (:import (java.io File)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (java.util Random)))

;; --- fixtures ----------------------------------------------------------------

(defn- temp-dir ^File []
  (.toFile (Files/createTempDirectory "haselnuss-scan" (make-array FileAttribute 0))))

(defn- write!
  ^File [^File dir rel text]
  (let [file (io/file dir rel)]
    (io/make-parents file)
    (spit file text)
    file))

(defn- canonical [^File f] (.getCanonicalPath f))

(def ^:private host-source
  "---
title: Host
---

At book level: @{mem:target}.

!include chapters/ch1.hdoc

# One {#sec:one}

A link @{mem:target#sec:a} and a stale shelf @{mem:old/target#sec:a}.

## Two {#sec:two}

Dangling: @{mem:target#sec:nope}, @{mem:ghost} and @{Bad Ref}.

!quote mem:target#sec:a

::: {.box}
In an unlabelled box: @{mem:target#sec:b}
:::

!quote lib:host

See @sec:missing.
")

(def ^:private chapter-source
  "# Chapter {#sec:ch}

From the chapter: @{mem:target#sec:b}
")

(def ^:private target-source
  "# A {#sec:a}

A cites @{mem:other} and @sec:b.

# B {#sec:b}

B.
")

(defn- mem-library
  []
  (memory/memory-library
   {:name "mem"
    :books [{:id (book-ref/book-id "mem" ["new"] "target")
             :entry {:title "Target"}
             :document (fn [] (parser/parse target-source))}
            {:id (book-ref/book-id "mem" "other")
             :entry {:title "Other"}
             :document (fn [] (parser/parse "Other.\n"))}
            {:id (book-ref/book-id "mem" "broken")
             :entry {:title "Broken"}
             :document (fn [] (throw (ex-info "no document today" {})))}]}))

(defn- setup
  "`{:root File :dir LibraryDirectory :lib Library}` over a fresh temp
  library `lib` holding `files` (rel -> text) and the memory library."
  [files]
  (let [root (temp-dir)]
    (doseq [[rel text] files] (write! root rel text))
    (let [lib (fs/filesystem-library {:name "lib" :path (str root)})]
      {:root root
       :lib lib
       :dir (library/library-directory [lib (mem-library)])})))

(def ^:private host-files
  {"host.hdoc" host-source
   "chapters/ch1.hdoc" chapter-source})

(defn- scan-of
  "The record of `slug` of the library `lib` of `env`, scanned in a fresh
  context (or `ctx`)."
  ([env slug] (scan-of env slug (scan/context {:directory (:dir env)})))
  ([{:keys [lib]} slug ctx]
   (scan/scan-book ctx (library/find-book lib slug))))

(defn- edge-view
  "An edge without its `:from :id` and `:from :order` (pinned on their
  own, `outline-test`), for readable comparisons."
  [edge]
  (update edge :from dissoc :id :order))

(def ^:private target-id (book-ref/book-id "mem" ["new"] "target"))

;; --- AC #1, #2, #3: edges ------------------------------------------------------

(deftest edges-test
  (let [env (setup host-files)
        host-file (str (.getAbsoluteFile (io/file (:root env) "host.hdoc")))
        record (scan-of env "host")
        host-id (book-ref/book-id "lib" "host")
        at (fn [line column] {:line line :file host-file :column column})]
    (is (nil? (graph/explain-book-record record)))
    (is (= host-id (:id record)))
    (is (every? #(= host-id (:id (:from %))) (:edges record)))
    (testing "one edge per reference the author wrote, in document order"
      (is (= [{:from {} :to {:written "mem:target" :id target-id}
               :kind :link :status :resolved :position (at 5 16)}
              {:from {:passage "sec:ch"}
               :to {:written "mem:target#sec:b" :passage {:form :label :label "sec:b"} :id target-id}
               :kind :link :status :resolved}
              {:from {:passage "sec:one"}
               :to {:written "mem:target#sec:a" :passage {:form :label :label "sec:a"} :id target-id}
               :kind :link :status :resolved :position (at 11 8)}
              {:from {:passage "sec:one"}
               :to {:written "mem:old/target#sec:a" :passage {:form :label :label "sec:a"}
                    :id target-id}
               :kind :link :status :resolved :position (at 11 46)}
              {:from {:passage "sec:two"}
               :to {:written "mem:target#sec:nope" :passage {:form :label :label "sec:nope"}
                    :id target-id}
               :kind :link :status :dangling :problem :unknown-label :position (at 15 11)}
              {:from {:passage "sec:two"} :to {:written "mem:ghost"}
               :kind :link :status :dangling :problem :unknown-book :position (at 15 35)}
              {:from {:passage "sec:two"} :to {:written "Bad Ref"}
               :kind :link :status :dangling :problem :malformed-book-ref :position (at 15 52)}
              {:from {:passage "sec:two"}
               :to {:written "mem:target#sec:a" :passage {:form :label :label "sec:a"} :id target-id}
               :kind :quote :status :resolved :position (at 17 1)}
              {:from {:passage "sec:two"}
               :to {:written "mem:target#sec:b" :passage {:form :label :label "sec:b"} :id target-id}
               :kind :link :status :resolved :position (at 20 23)}
              {:from {:passage "sec:two"} :to {:written "lib:host" :id host-id}
               :kind :quote :status :dangling :problem :quote-cycle :position (at 23 1)}]
             ;; the chapter's position is pinned below
             (update (mapv edge-view (:edges record)) 1 dissoc :position)))
      (testing "an included chapter's reference keeps the chapter's own file, line and column (AC #3; TASK-248)"
        (let [{:keys [line file column]} (:position (second (:edges record)))]
          (is (= 3 line))
          (is (= 19 column) "`From the chapter: @{...}`, counted in the chapter's own file")
          (is (= (canonical (io/file (:root env) "chapters/ch1.hdoc")) (canonical (io/file file)))))))
    (testing "references inside quoted content are the quoted book's, not the quoter's (AC #3)"
      (is (not-any? #(= "mem:other" (:written (:to %))) (:edges record)))
      (is (= 2 (count (filter #(= :quote (:kind %)) (:edges record)))))
      (let [target (scan/scan-book (scan/context {:directory (:dir env)})
                                   (library/find-book (library/find-library (:dir env) "mem") "target"))]
        (is (= [{:from {:passage "sec:a"} :to {:written "mem:other"
                                               :id (book-ref/book-id "mem" "other")}
                 :kind :link :status :resolved :position {:line 3 :column 9}}]
               (mapv edge-view (:edges target)))
            "the quoted book's own link is its edge, and a cross-reference (@sec:b) is none")))
    (testing "the included chapter is among :files, with the entry file, canonical (AC #3)"
      (is (= #{(canonical (io/file (:root env) "host.hdoc"))
               (canonical (io/file (:root env) "chapters/ch1.hdoc"))}
             (:files record))))))

(deftest diagnostics-test
  (let [env (setup host-files)
        record (scan-of env "host")
        by-type (group-by :type (:diagnostics record))
        host-file (str (.getAbsoluteFile (io/file (:root env) "host.hdoc")))]
    (testing "the book's own resolve diagnostics (AC #4)"
      (is (= 1 (count (:dangling-cross-ref by-type))) (pr-str (keys by-type)))
      (is (= host-file (:source-file (first (:dangling-cross-ref by-type)))))
      (is (nil? (:source-column (first (:dangling-cross-ref by-type))))
          "a resolver diagnostic is placed on a line only"))
    (testing "one located diagnostic per dangling reference, its type the edge's problem"
      (is (= {:unknown-label 1 :unknown-book 1 :malformed-book-ref 1 :quote-cycle 1}
             (frequencies (keep :problem (:edges record)))))
      (is (= {:unknown-label 1 :unknown-book 1 :malformed-book-ref 1 :quote-cycle 1 :stale-shelf 1}
             ;; only a reference's diagnostics carry :ref
             (frequencies (map :type (filter :ref (:diagnostics record))))))
      (let [d (first (:unknown-label by-type))]
        (is (= 15 (:source-line d)))
        (is (= 11 (:source-column d)) "the reference's column, its edge's (TASK-248)")
        (is (= host-file (:source-file d)))
        (is (str/starts-with? (:message d) (str host-file ":15: "))
            "the message keeps the build's own prefix, which has no column")
        (is (= "mem:target#sec:nope" (:ref d))))
      (is (= {:malformed-book-ref 52 :quote-cycle 1 :unknown-book 35}
             (into {} (map (juxt :type :source-column))
                   (filter (comp #{:malformed-book-ref :quote-cycle :unknown-book} :type)
                           (:diagnostics record))))))
    (testing "a stale shelf is an info diagnostic; its edge stays resolved"
      (let [[d] (:stale-shelf by-type)]
        (is (= :info (:level d)))
        (is (= "mem:old/target#sec:a" (:ref d)))
        (is (= 11 (:source-line d)))
        (is (= 46 (:source-column d))))
      (is (= :resolved (:status (nth (:edges record) 3)))))
    (testing "a target book's load problems are not the quoting book's"
      (is (not-any? #(= "mem:broken" (:book %)) (:diagnostics record))))))

;; --- AC #4: labels and hash ---------------------------------------------------

(deftest labels-and-hash-test
  (let [env (setup host-files)
        record (scan-of env "host")]
    (testing ":labels has every block label of the book, chapters included"
      (is (= #{"sec:ch" "sec:one" "sec:two"} (set (keys (:labels record)))))
      (is (every? #(re-matches #"[0-9a-f]{64}" %) (vals (:labels record))))
      (is (re-matches #"[0-9a-f]{64}" (:hash record))))
    (testing "the same content hashes the same in another context"
      (is (= (dissoc record :diagnostics) (dissoc (scan-of env "host") :diagnostics))))
    (testing "an edit to an included chapter changes :hash, and only that chapter's label"
      (write! (:root env) "chapters/ch1.hdoc" (str chapter-source "\nMore.\n"))
      (let [env' (assoc env :lib (fs/filesystem-library {:name "lib" :path (str (:root env))}))
            edited (scan-of env' "host" (scan/context {:directory (library/library-directory
                                                                   [(:lib env') (mem-library)])}))]
        (is (not= (:hash record) (:hash edited)))
        (is (not= (get-in record [:labels "sec:ch"]) (get-in edited [:labels "sec:ch"])))
        (is (= (get-in record [:labels "sec:one"]) (get-in edited [:labels "sec:one"])))))
    (testing "moving a block down the file leaves its label hash alone"
      (let [a (setup {"b.hdoc" "# X {#sec:x}\n\nText.\n"})
            b (setup {"b.hdoc" "\n\n\n# X {#sec:x}\n\nText.\n"})]
        (is (= (:labels (scan-of a "b")) (:labels (scan-of b "b"))))
        (is (not= (:hash (scan-of a "b")) (:hash (scan-of b "b"))) "but the file's hash moves")))
    (testing "an in-memory book has no files; its hash follows its document"
      (let [mem (library/find-library (:dir env) "mem")
            r (scan/scan-book (scan/context {:directory (:dir env)}) (library/find-book mem "other"))]
        (is (= #{} (:files r)))
        (is (re-matches #"[0-9a-f]{64}" (:hash r)))
        (is (not= (:hash r)
                  (:hash (scan/scan-book (scan/context {:directory (:dir env)})
                                         (library/find-book mem "target")))))))))

(def ^:private cited-source
  "---
title: Cited
bibliography: refs.json
---

# C {#sec:c}

As @smith2020 said.

!include ch.hdoc
")

(def ^:private refs-json
  "[{\"id\": \"smith2020\", \"type\": \"book\", \"title\": \"A Book\",
     \"author\": [{\"family\": \"Smith\", \"given\": \"Ann\"}], \"issued\": {\"date-parts\": [[2020]]}}]")

(deftest bibliography-is-among-files-test
  (let [files {"cited.hdoc" cited-source
               "refs.json" refs-json
               "ch.hdoc" "---\nbibliography: other.json\n---\n\nChapter.\n"
               "other.json" "[]"}
        env (setup files)
        fresh (fn [] (scan-of (assoc env :lib (fs/filesystem-library {:name "lib" :path (str (:root env))}))
                              "cited"))
        record (fresh)]
    (is (nil? (graph/explain-book-record record)))
    (testing "the entry file's bibliography is a file the book depends on, canonical"
      (is (= #{(canonical (io/file (:root env) "cited.hdoc"))
               (canonical (io/file (:root env) "refs.json"))
               (canonical (io/file (:root env) "ch.hdoc"))}
             (:files record))
          "an included chapter's front matter names no bibliography of the book's"))
    (testing "editing it changes :hash"
      (write! (:root env) "refs.json" (str/replace refs-json "A Book" "Another Book"))
      (is (not= (:hash record) (:hash (fresh))))))
  (testing "a missing bibliography is listed, as a missing include is"
    (let [env (setup {"cited.hdoc" cited-source "ch.hdoc" "Chapter.\n"})
          record (scan-of env "cited")]
      (is (nil? (graph/explain-book-record record)))
      (is (contains? (:files record) (canonical (io/file (:root env) "refs.json"))))
      (is (some #(= :unreadable-bibliography (:type %)) (:diagnostics record))))))

(deftest canonical-form-test
  (testing "map and set order never shows, nor metadata"
    (is (= "{:a [#{\"x\" \"y\"}] :b 1}"
           (scan/canonical-form (with-meta {:b 1 :a [#{"y" "x"}]} {:line 3}))))
    (is (= (scan/canonical-form (into {} (map (fn [i] [(keyword (str "k" i)) i])) (range 40)))
           (scan/canonical-form (into {} (map (fn [i] [(keyword (str "k" i)) i])) (reverse (range 40))))))))

(deftest generated-book-test
  (let [root (.getAbsoluteFile (io/file "test/fixtures/libraries/generated"))
        lib (fs/filesystem-library {:name "bible" :path (str root) :scripts true})
        ctx (scan/context {:directory (library/library-directory [lib])})
        record (scan/scan-book ctx (library/find-book lib "romanos"))]
    (is (nil? (graph/explain-book-record record)))
    (is (= #{(canonical (io/file root "scripture/shelf.clj"))
             (canonical (io/file root "scripture/biblia.json"))
             (canonical (io/file root "scripture/books.edn"))}
           (:files record))
        "a generated book's :files are its shelf.clj and :data files (AC #4)")
    (is (= #{"cap:14"} (set (keys (:labels record)))))
    (testing "an ordinary book beside the shelf lists only its own file"
      (is (= #{(canonical (io/file root "commentary/salmos-notas.hdoc"))}
             (:files (scan/scan-book ctx (library/find-book lib "salmos-notas"))))))))

(deftest outline-test
  (testing "TASK-242: every edge has its document order, and each label the span of its
            block, so a quoted label holds exactly the references inside it"
    (let [record (scan-of (setup host-files) "host")
          spans (:label-spans record)
          orders (map #(get-in % [:from :order]) (:edges record))]
      (is (every? nat-int? orders))
      (is (apply < orders) "document order")
      (is (= (set (keys (:labels record))) (set (keys spans)))
          "a span for every label")
      (doseq [{{:keys [passage order]} :from} (:edges record)
              :when passage]
        (is (<= (first (spans passage)) order (second (spans passage))) passage))))
  (testing "nested blocks, an unlabelled sibling, and the first of two blocks with one id"
    (let [doc (parser/parse (str "# Outer {#sec:outer}\n\nText @{l:x}.\n\n## Inner {#sec:inner}\n\n"
                                 "!quote l:y\n\n# Next {#sec:next}\n\nAfter @{l:z}.\n\n"
                                 "# Again {#sec:outer}\n\n!quote l:w\n"))
          {:keys [references label-spans]} (scan/outline doc)
          order-of (fn [w] (some #(when (= w (:ref (:node %))) (:order %)) references))
          in? (fn [w label] (let [[a b] (label-spans label)] (<= a (order-of w) b)))]
      (is (= ["l:x" "l:y" "l:z" "l:w"] (map (comp :ref :node) references)))
      (is (= ["sec:outer" "sec:inner" "sec:next" "sec:outer"] (map :label references)))
      (is (in? "l:x" "sec:outer"))
      (is (in? "l:y" "sec:outer") "nested under sec:inner, still inside sec:outer")
      (is (in? "l:y" "sec:inner"))
      (is (not (in? "l:x" "sec:inner")))
      (is (not (in? "l:z" "sec:outer")))
      (is (not (in? "l:w" "sec:outer")) "the span is the FIRST block carrying the id")
      (is (= #{"sec:outer" "sec:inner" "sec:next"} (set (keys label-spans)))))))

(deftest generated-references-have-no-position-test
  (let [dir (temp-dir)
        _ (write! dir "s/shelf.clj"
                  (str "{:data [\"d.edn\"]\n"
                       " :books (fn [_] [{:slug \"g\"}])\n"
                       " :document (fn [_ _] {:meta {} :blocks [{:t :para :inlines"
                       " [{:t :book-ref :ref \"l:s/g\"}]}]})}\n"))
        _ (write! dir "s/d.edn" "{}")
        lib (fs/filesystem-library {:name "l" :path (str dir) :scripts true})
        record (scan/scan-book (scan/context {:directory (library/library-directory [lib])})
                               (library/find-book lib "g"))]
    (is (nil? (graph/explain-book-record record)))
    (is (= [{:from {} :to {:written "l:s/g" :id (book-ref/book-id "l" ["s"] "g")}
             :kind :link :status :resolved}]
           (mapv edge-view (:edges record)))
        "a script's plain data carries no source position, so neither does its edge")))

;; --- AC #5: failure -----------------------------------------------------------

(deftest failed-books-test
  (testing "a book whose front matter does not parse"
    (let [env (setup {"bad.hdoc" "---\ntitle: [unclosed\n---\n\n@{mem:target}\n"})
          record (scan-of env "bad")
          [d] (:diagnostics record)
          file (str (.getAbsoluteFile (io/file (:root env) "bad.hdoc")))]
      (is (nil? (graph/explain-book-record record)))
      (is (= [] (:edges record)))
      (is (= {} (:labels record)))
      (is (= :book-failed (:type d)))
      (is (= file (:source-file d)) "placed at its file")
      (is (= #{(canonical (io/file file))} (:files record)) "its entry file still counts")))
  (testing "a book whose included chapter does not parse"
    (let [env (setup {"inc.hdoc" "# I {#sec:i}\n\n!include ch.hdoc\n"
                      "ch.hdoc" "---\ntitle: [unclosed\n---\n"})
          record (scan-of env "inc")]
      (is (nil? (graph/explain-book-record record)))
      (is (= [:book-failed] (mapv :type (:diagnostics record))))
      (is (= [] (:edges record)))
      (is (= 2 (count (:files record))) "the chapter that failed is among its files")))
  (testing "an in-memory book whose document throws"
    (let [env (setup {})
          mem (library/find-library (:dir env) "mem")
          record (scan/scan-book (scan/context {:directory (:dir env)}) (library/find-book mem "broken"))]
      (is (nil? (graph/explain-book-record record)))
      (is (= [:book-failed] (mapv :type (:diagnostics record))))
      (is (= #{} (:files record)))))
  (testing "a book that fails to resolve"
    (let [env (setup host-files)
          record (scan-of env "host" (scan/context {:directory (:dir env)
                                                    :resolve-fn (fn [_ _] (throw (ex-info "boom" {})))}))]
      (is (nil? (graph/explain-book-record record)))
      (is (= [] (:edges record)))
      (is (= :book-failed (:type (first (:diagnostics record)))))
      (is (str/includes? (:message (first (:diagnostics record))) "boom"))))
  (testing "a generated book whose :document throws keeps the script's own diagnostic"
    (let [dir (temp-dir)
          _ (write! dir "s/shelf.clj" (str "{:books (fn [_] [{:slug \"g\"}])\n"
                                           " :document (fn [_ _] (throw (ex-info \"nope\" {})))}\n"))
          lib (fs/filesystem-library {:name "l" :path (str dir) :scripts true})
          record (scan/scan-book (scan/context {:directory (library/library-directory [lib])})
                                 (library/find-book lib "g"))]
      (is (nil? (graph/explain-book-record record)))
      (is (= [:script-failed] (mapv :type (:diagnostics record))))
      (is (= #{(canonical (io/file dir "s/shelf.clj"))} (:files record)))))
  (testing "no library directory: every reference dangles, nothing throws"
    (let [env (setup host-files)
          record (scan-of env "host" (scan/context {}))]
      (is (nil? (graph/explain-book-record record)))
      (is (= #{:no-library-directory :malformed-book-ref :quote-cycle}
             (set (keep :problem (:edges record))))))))

;; --- seeded fuzz: nearest labelled ancestor ------------------------------------

(def ^:private seed 240)

(defn- gen-blocks
  "Random blocks `depth` levels deep: sections and divs, labelled or not,
  paragraphs holding links (also inside emphasis and footnotes), and
  quotes. Every reference's `:ref` is unique, `r<n>`, so an expected edge
  can be matched to its node."
  [^Random rnd counter depth]
  (vec (for [_ (range (inc (.nextInt rnd 3)))]
         (let [labelled? (.nextBoolean rnd)
               attr {:classes [] :props {}}
               attr (if labelled? (assoc attr :id (str "x:" (swap! counter inc))) attr)
               next-ref #(str "lib:b" (swap! counter inc))]
           (case (if (pos? depth) (.nextInt rnd 5) (+ 3 (.nextInt rnd 2)))
             0 {:t :section :level 1 :heading [{:t :str :text "h"}] :attr attr
                :blocks (gen-blocks rnd counter (dec depth))}
             1 {:t :directive :name "" :attr attr :blocks (gen-blocks rnd counter (dec depth))}
             2 {:t :para :inlines [{:t :note :blocks (gen-blocks rnd counter (dec depth))}]}
             3 {:t :para :inlines [{:t :str :text "a"}
                                   {:t :book-ref :ref (next-ref)}
                                   {:t :emph :inlines [{:t :book-ref :ref (next-ref)}]}]}
             4 {:t :book-quote :ref (next-ref)})))))

(defn- expected-refs
  "`[[ref kind label] ...]` by a hand-written walk, independent of
  `haselnuss.walk`: the label is the innermost enclosing section's or
  div's id."
  [blocks label]
  (vec (mapcat (fn [b]
                 (case (:t b)
                   (:section :directive) (expected-refs (:blocks b) (or (get-in b [:attr :id]) label))
                   :book-quote [[(:ref b) :quote label]]
                   :para (mapcat (fn [i]
                                   (case (:t i)
                                     :book-ref [[(:ref i) :link label]]
                                     :emph (map (fn [j] [(:ref j) :link label]) (:inlines i))
                                     :note (expected-refs (:blocks i) label)
                                     []))
                                 (:inlines b))))
               blocks)))

(deftest nearest-labelled-ancestor-fuzz-test
  (let [rnd (Random. seed)
        stats (atom {:labelled 0 :book-level 0 :quote 0 :in-note 0})]
    (dotimes [_ 200]
      (let [counter (atom 0)
            blocks (gen-blocks rnd counter 4)
            doc {:meta {} :blocks blocks}
            expected (expected-refs blocks nil)
            actual (mapv (fn [{:keys [node kind label]}] [(:ref node) kind label])
                         (scan/references doc))]
        (is (= expected actual))
        (doseq [[_ kind label] expected]
          (swap! stats update (if label :labelled :book-level) inc)
          (when (= :quote kind) (swap! stats update :quote inc)))
        (swap! stats update :in-note + (count (re-seq #":note" (pr-str blocks))))))
    (testing "the generated cases are not vacuous"
      (is (< 200 (:labelled @stats)))
      (is (< 100 (:book-level @stats)))
      (is (< 100 (:quote @stats)))
      (is (< 50 (:in-note @stats))))))

;; --- TASK-248: columns ------------------------------------------------------------

(defn- written-column
  "The 1-based column, in code points, of `marker` on the 1-based line
  `line` of `source`, or nil when it is not there exactly once."
  [source line marker]
  (let [^String text (nth (str/split source #"\n" -1) (dec line))
        i (str/index-of text marker)]
    (when (and i (= i (str/last-index-of text marker)))
      (inc (.codePointCount text 0 (int i))))))

(defn- ref-positions
  "`{ref-as-written source-position}` of every reference in `source`."
  [source]
  (into {} (map (fn [{:keys [node]}] [(:ref node) (ast/source node)]))
        (scan/references (parser/parse source {:source "f.hdoc"}))))

(def ^:private columns-source
  "---
title: Columns
---

Plain @{a:plain} start, then @{a:second}.

- item *emph @{a:emph}* in
  > quoted @{a:nested}

| a | b |
|---|---|
| x | @{a:cell} |
: cap é @{a:caption} and
more @{a:caption-2}

   !quote a:quote

Multi é𝄞 x @{a:astral} and 日本 @{a:cjk}.

::: {.box}
In a div,	tab @{a:div}
:::

> | a |
> |---|
> | x |
> : boxed @{a:boxed-caption}
>   then @{a:boxed-caption-2}

# Heading @{a:heading}

[link text @{a:link}](https://example.org)

Footnoted[^1].

[^1]: note @{a:note}

Cite [see @{a:in-cite} @knuth].
")

(deftest reference-columns-test
  (let [positions (ref-positions columns-source)]
    (testing "every reference is placed at the column of its `@` (or its `!quote`'s `!`)"
      (is (= {"a:plain" 7 "a:second" 30 "a:emph" 14 "a:nested" 12 "a:cell" 7
              "a:caption" 9 "a:caption-2" 6 "a:quote" 4 "a:astral" 12 "a:cjk" 31
              "a:div" 15 "a:boxed-caption" 11 "a:boxed-caption-2" 10 "a:heading" 11
              "a:link" 12 "a:note" 12 "a:in-cite" 11}
             (into {} (keep (fn [[written {:keys [column]}]] (when column [written column]))) positions)))
      (doseq [[written {:keys [line column]}] positions
              :when column]
        (is (= column (written-column columns-source line
                                      (if (= "a:quote" written) "!quote" (str "@{" written "}"))))
            written)))
    (testing "a citation's prefix is re-parsed on its own, but traced back to the file (TASK-252)"
      (is (= {:file "f.hdoc" :line 38 :column 11} (get positions "a:in-cite"))))))

(defn- fuzz-case
  "The source of one random block holding the reference `r`, of one of
  the shapes a reference's column depends on -- a container prefix, a
  continuation line, a table caption, a `!quote` -- with random text
  (tabs, combining and astral characters, emphasis markers) before it."
  [^Random rnd r]
  (let [pick (fn [xs] (nth xs (.nextInt rnd (count xs))))
        text (fn [n] (apply str (repeatedly (.nextInt rnd (int n))
                                            #(pick ["a" "b" " " " " "é" "é" "𝄞" "日" "\t" "*" "_" "~" "[" "]"
                                                    "!" ":" "." "," "(" ")" "<" "x"]))))
        link (str "@{" r "}")
        line (fn [] (str (text 12) link (text 6)))
        [container continuation] (pick [["" ""] ["> " "> "] ["> " ""] ["- " "  "] ["1. " "   "]
                                        ["> > " "> > "] ["- > " "  > "] ["   " "   "]])]
    (case (int (.nextInt rnd 4))
      0 (str container (line))
      1 (str container "lead words" (pick ["" " \\"]) "\n" continuation (line))
      2 (str container "| a |\n" continuation "|---|\n" continuation "| x |\n" continuation
             ":" (pick [" " "  " "\t"]) (if (.nextBoolean rnd)
                                          (line)
                                          (str "cap\n" continuation (line))))
      3 (str (pick ["" " " "  " "   "]) "!quote " r))))

(deftest reference-columns-fuzz-test
  (let [rnd (Random. 248)
        stats (atom {:generated 0 :found 0 :off-line-0 0 :astral 0})]
    (dotimes [_ 150]
      (let [refs (mapv #(str "f:r" %) (range (inc (.nextInt rnd 6))))
            front (if (.nextBoolean rnd) "---\ntitle: T\n---\n\n" "")
            source (str front (str/join "\n\n" (map #(fuzz-case rnd %) refs)) "\n")
            positions (ref-positions source)]
        (swap! stats update :generated + (count refs))
        (doseq [r refs
                :let [{:keys [line column] :as at} (get positions r)]
                :when at]
          (swap! stats update :found inc)
          (let [marker (if (str/includes? source (str "!quote " r)) (str "!quote " r) (str "@{" r "}"))
                text (nth (str/split source #"\n" -1) (dec line))
                expected (written-column source line marker)]
            (when (pos? (str/index-of text marker)) (swap! stats update :off-line-0 inc))
            (when (re-find #"[\x{10000}-\x{10FFFF}]" (subs text 0 (str/index-of text marker)))
              (swap! stats update :astral inc))
            (is (some? expected) (pr-str source r))
            (is (= expected column) (pr-str source r))))))
    (testing "the generated cases are not vacuous"
      (is (< 400 (:found @stats)) (pr-str @stats))
      (is (< 200 (:off-line-0 @stats)) "most references are not at the start of their line")
      (is (< 50 (:astral @stats)) "astral characters before the reference"))))

;; --- TASK-252: references in a citation's prefix or suffix -------------------------

(defn- in-cite-refs
  "The set of refs of every book reference in a citation item's prefix
  or suffix in `doc`."
  [doc]
  (let [acc (atom #{})
        walk (fn walk [x]
               (cond
                 (and (map? x) (= :cite (:t x)))
                 (doseq [item (:items x)
                         inline (concat (:prefix item) (:suffix item))]
                   (when (= :book-ref (:t inline)) (swap! acc conj (:ref inline)))
                   (walk inline))
                 (map? x) (run! walk (vals x))
                 (sequential? x) (run! walk x)))]
    (walk doc)
    @acc))

(deftest citation-fragment-positions-test
  (let [source (str "---\ntitle: T\n---\n\n"                          ; 1-4
                    "One [see @{c:one} @k] line.\n\n"                  ; 5
                    "Spans [cf.\n  then @{c:span} @k, p. 2\n"          ; 7-8
                    "and @{c:suffix}; also @{c:second}\n@k2] x.\n\n"   ; 9-10
                    "> Q [see\n> it @{c:quoted} @k]\n\n"               ; 12-13
                    "- L [see\n  it @{c:listed} @k]\n\n"               ; 15-16
                    "1. N [see\n   it @{c:ordered} @k]\n\n"            ; 18-19
                    "x [# @{c:guarded} @k; @k2, - @{c:dash}]\n")       ; 21
        doc (parser/parse source {:source "f.hdoc"})]
    (is (= #{"c:one" "c:span" "c:suffix" "c:second" "c:quoted" "c:listed" "c:ordered"
             "c:guarded" "c:dash"}
           (in-cite-refs doc))
        "every reference sits in a prefix or suffix")
    (is (= {"c:one" [5 10] "c:span" [8 8] "c:suffix" [9 5] "c:second" [9 23]
            "c:quoted" [13 6] "c:listed" [16 6] "c:ordered" [19 7] "c:guarded" [21 6]
            "c:dash" [21 30]}
           (into {} (map (fn [[r {:keys [line column file]}]]
                           (is (= "f.hdoc" file) r)
                           [r [line column]]))
                 (ref-positions source))))))

(defn- cite-fuzz-paragraph
  "One paragraph, possibly in a container, holding a citation group with
  one segment per ref of `refs`, each ref in its segment's prefix or
  suffix, random text (tabs, astral characters, emphasis and block
  markers) around it and random line breaks inside the group. Records
  in `stats` which refs it put after a line break of the group, in a
  suffix, and in a container."
  [^Random rnd refs stats]
  (let [pick (fn [xs] (nth xs (.nextInt rnd (count xs))))
        text (fn [n] (apply str (repeatedly (.nextInt rnd (int n))
                                            #(pick ["a" "b" " " " " "é" "é" "𝄞" "日" "\t" "*" "_" "~"
                                                    "!" ":" "." "," "(" ")" "x" "-" "#" "1" "=" "+"]))))
        [container continuation] (pick [["" ""] ["> " "> "] ["> " ""] ["- " "  "] ["1. " "   "]
                                        ["> > " "> > "] ["- > " "  > "]])
        broken? (atom false)
        brk (fn [] (if (zero? (.nextInt rnd 3))
                     (do (reset! broken? true) (str "\n" continuation))
                     " "))
        note (fn [r k] (swap! stats update k conj r))
        segment (fn [i r]
                  (let [link (str "@{" r "}")
                        cite-key (str "@k" i)
                        suffix? (.nextBoolean rnd)
                        seg (if suffix?
                              (str cite-key (pick ["," ", " " " ",\t"]) (text 6) (brk) (text 4) link (text 4))
                              (str (text 8) (brk) (text 3) link (brk) (text 6) " " cite-key (text 3)))]
                    (when suffix? (note r :suffix))
                    (when @broken? (note r :after-break))
                    (when (seq container) (note r :container))
                    seg))
        segments (doall (map-indexed segment refs))]
    (str container (text 10) " [" (str/join (str ";" (brk)) segments) "]" (text 6))))

(deftest citation-fragment-positions-fuzz-test
  (let [rnd (Random. 252)
        stats (atom {:generated 0 :in-cite #{} :suffix #{} :after-break #{} :container #{}})
        checked (atom #{})]
    (dotimes [n 200]
      (let [paras (vec (for [p (range (inc (.nextInt rnd 3)))]
                         (mapv #(str "f:r" n "-" p "-" %) (range (inc (.nextInt rnd 3))))))
            front (if (.nextBoolean rnd) "---\ntitle: T\n---\n\n" "")
            source (str front (str/join "\n\n" (map #(cite-fuzz-paragraph rnd % stats) paras)) "\n")
            doc (parser/parse source {:source "f.hdoc"})
            in-cite (in-cite-refs doc)
            positions (ref-positions source)]
        (swap! stats update :generated + (count (apply concat paras)))
        (swap! stats update :in-cite into in-cite)
        (doseq [r in-cite
                :let [{:keys [line column file]} (get positions r)]]
          (swap! checked conj r)
          (is (= "f.hdoc" file) (pr-str source r))
          (is (some? line) (pr-str source r))
          (is (= (written-column source line (str "@{" r "}")) column) (pr-str source r)))
        ;; and a reference the group did not keep is placed as before
        (doseq [[r {:keys [line column]}] positions
                :when (not (in-cite r))]
          (is (= (written-column source line (str "@{" r "}")) column) (pr-str source r)))))
    (testing "the generated cases are not vacuous"
      (let [{:keys [in-cite suffix after-break container]} @stats
            hit (fn [k] (count (filter in-cite k)))]
        ;; 686 of 749 generated, 339 / 395 / 592 when written
        (is (< 600 (count @checked)) (pr-str (:generated @stats)))
        (is (< 300 (hit suffix)) "references in a suffix")
        (is (< 300 (hit after-break)) "references after a line break inside the group")
        (is (< 450 (hit container)) "references in a quote or list item")))))

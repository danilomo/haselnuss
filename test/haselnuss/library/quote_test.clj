(ns haselnuss.library.quote-test
  "Quote expansion (TASK-154, doc-10 \"Resolver\" step 0b): a `!quote`
  resolved by `haselnuss.resolver/resolve-document` through the `:books`
  option `haselnuss.library.references/books-option` builds -- over
  `:memory` books written as `.hdoc` source, and over the filesystem
  fixture library `test/fixtures/libraries/passages/`, reached through a
  library directory file. Counting is done through injected fns, never
  `with-redefs`."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [haselnuss.ast :as ast]
            [haselnuss.book-ref :as book-ref]
            [haselnuss.emit.html :as html]
            [haselnuss.emit.latex :as latex]
            [haselnuss.library :as library]
            [haselnuss.library.directory :as directory]
            [haselnuss.library.filesystem :as fs]
            [haselnuss.library.memory :as memory]
            [haselnuss.library.passage :as passage]
            [haselnuss.library.references :as references]
            [haselnuss.parser :as parser]
            [haselnuss.resolver :as resolver])
  (:import (java.io File)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

;; --- books -------------------------------------------------------------------

(def ^:private catechism-source
  "---
title: Heidelberg Catechism
lang: en
---

# Comfort {#sec:comfort}

## Lord's Day 1 {#sec:q1}

What is your only comfort?

## Lord's Day 2 {#sec:q2}

How many things? See @sec:q1, and [the span]{#x:span}.

![A tree](tree.png){#fig:tree}

## Lord's Day 3 {#sec:q3}

Whence knowest thou thy misery?

# Misery {#sec:misery}

Before the questions.
")

(def ^:private romans
  "A Locatable virtual book: `14:8` is a verse, anything else is rejected."
  {:id (book-ref/book-id "bible" ["nt"] "romanos")
   :entry {:title "Romanos" :lang "pt-BR"}
   :document (fn [] (parser/parse "---\ntitle: Romanos\n---\n\nTexto.\n"))
   :locate (fn [locator]
             (if (= "14:8" locator)
               {:title "Romanos 14:8"
                :blocks [{:t :para :inlines [{:t :str :text "Porque, se vivemos, para o Senhor vivemos."}]}]}
               (throw (ex-info (str "no verse " locator) {}))))})

(defn- book
  "A virtual-book spec of library `lib`, slug `slug`, on `shelf`, whose
  document is `source` parsed."
  ([lib slug source] (book lib [] slug source))
  ([lib shelf slug source]
   {:id (book-ref/book-id lib shelf slug)
    :entry {:title (str/capitalize slug)}
    :document (fn [] (parser/parse source))}))

(defn- directory
  ([] (directory {}))
  ([{:keys [reformed-extra failures]}]
   (library/library-directory
    [(memory/memory-library
      {:name "reformed"
       :books (into [{:id (book-ref/book-id "reformed" ["confessions"] "heidelberg")
                      :entry {:title "Heidelberg Catechism" :lang "en"}
                      :document (fn [] (parser/parse catechism-source))}
                     (book "reformed" ["a"] "twin" "# A {#sec:a}\n\nA.\n")
                     (book "reformed" ["b"] "twin" "# B {#sec:b}\n\nB.\n")]
                    reformed-extra)})
     (memory/memory-library {:name "bible" :books [romans]})]
    (or failures []))))

(defn- resolve*
  "`resolve-document` of `source` (parsed as the file `host.hdoc`) with the
  `:books` option over `dir` (default `(directory)`), plus `opts`."
  ([source] (resolve* source {}))
  ([source {:keys [dir books-opts resolve-opts]}]
   (resolver/resolve-document
    (parser/parse source {:source "host.hdoc"})
    (assoc resolve-opts :books (references/books-option
                                (merge {:directory (or dir (directory))} books-opts))))))

(defn- quotes
  "Every `:book-quote` in `x`, outermost first."
  [x]
  (filterv #(and (map? %) (= :book-quote (:t %))) (tree-seq coll? seq x)))

(defn- nodes-of
  [t x]
  (filterv #(and (map? %) (= t (:t %))) (tree-seq coll? seq x)))

(defn- text-of
  [inlines]
  (apply str (map #(case (:t %) :str (:text %) :space " " "") inlines)))

(defn- ids-in
  [x]
  (keep #(when (map? %) (get-in % [:attr :id])) (tree-seq coll? seq x)))

(defn- by-type [diagnostics] (group-by :type diagnostics))

;; --- AC #1: label, range and locator -----------------------------------------

(deftest splices-label-range-and-locator-test
  (let [{:keys [document diagnostics]}
        (resolve* "# Host {#sec:host}

!quote reformed:confessions/heidelberg#sec:q1

!quote reformed:heidelberg#sec:q1..sec:q3

!quote bible:romanos#14:8
")
        [label range-quote locator] (quotes document)]
    (is (= [] diagnostics))
    (testing "a label splices that one block"
      (is (= "reformed:confessions/heidelberg#sec:q1" (:ref label)) "the ref stays as written")
      (is (= "reformed:confessions/heidelberg#sec:q1" (:canonical label)))
      (is (= "Heidelberg Catechism, Section 1.1" (text-of (:title label))))
      (is (= [[{:t :str :text "Lord's"} {:t :space} {:t :str :text "Day"} {:t :space}
               {:t :str :text "1"}]]
             (mapv :heading (:blocks label)))))
    (testing "a range splices every sibling, and records the CANONICAL source"
      (is (= "reformed:heidelberg#sec:q1..sec:q3" (:ref range-quote)))
      (is (= "reformed:confessions/heidelberg#sec:q1..sec:q3" (:canonical range-quote)))
      (is (= "Heidelberg Catechism, Section 1.1–1.3" (text-of (:title range-quote))))
      (is (= ["Lord's Day 1" "Lord's Day 2" "Lord's Day 3"]
             (mapv (comp text-of :heading) (:blocks range-quote)))))
    (testing "a locator splices what the book's locate answers"
      (is (= "bible:nt/romanos#14:8" (:canonical locator)))
      (is (= "Romanos 14:8" (text-of (:title locator))))
      (is (= "Porque, se vivemos, para o Senhor vivemos."
             (text-of (:inlines (first (:blocks locator)))))))
    (testing "a whole-book quote splices every block, titled by the book"
      (let [q (first (quotes (:document (resolve* "!quote reformed:heidelberg\n"))))]
        (is (= "reformed:confessions/heidelberg" (:canonical q)))
        (is (= "Heidelberg Catechism" (text-of (:title q))))
        (is (= ["Comfort" "Misery"] (mapv (comp text-of :heading) (:blocks q))))))
    (testing "the result is still a valid document"
      (is (ast/valid? ast/Document document)))
    (testing "a quote inside a container -- the catechism's proof-text shape -- expands too"
      (let [{:keys [document diagnostics]}
            (resolve* ":::{small-collapsable summary=\"Romanos 14:8\" label=\"(2)\"}\n!quote bible:romanos#14:8\n:::\n"
                      {:resolve-opts {:directive-registry #{"small-collapsable"}}})]
        (is (= [] diagnostics))
        (is (= ["bible:nt/romanos#14:8"] (mapv :canonical (quotes document))))
        (is (ast/valid? ast/Document document))))
    (testing "and wherever else a block can sit, a footnote included"
      (let [doc {:meta {}
                 :blocks [{:t :para
                           :inlines [{:t :note
                                      :blocks [{:t :book-quote :ref "bible:romanos#14:8"}]}]}]}
            {:keys [document diagnostics]}
            (resolver/resolve-document doc {:books (references/books-option
                                                    {:directory (directory)})})]
        (is (= [] diagnostics))
        (is (= ["bible:nt/romanos#14:8"] (mapv :canonical (quotes document))))))))

;; --- AC #2: ids, numbering, TOC, list of figures -----------------------------

(def ^:private host-with-figures
  "# Host {#sec:host}

!quote reformed:heidelberg#sec:comfort

![Host tree](host.png){#fig:host}

See @fig:host and @sec:host.
")

(deftest quoted-content-is-outside-the-host-test
  (let [{:keys [document labels diagnostics]} (resolve* host-with-figures)
        q (first (quotes document))]
    (is (= [] diagnostics) "no duplicate-id, kind or dangling warning from quoted content")
    (testing "no attr.id survives anywhere in quoted content, block or inline"
      (is (seq (:blocks q)))
      (is (= [] (ids-in (:blocks q))))
      (is (seq (nodes-of :figure (:blocks q))) "the quoted figure is still there, without its id"))
    (testing "only the host's own nodes are numbered"
      (is (= #{"sec:host" "fig:host"} (set (keys labels))))
      (is (= "Figure 1.1" (:text (get labels "fig:host"))) "the quoted figure takes no number"))
    (testing "the TOC and list of figures hold only the host's own"
      (is (= ["sec:host"] (mapv :id (resolver/derive-toc document labels))))
      (is (= ["fig:host"] (mapv :id (resolver/derive-list-of-figures document labels)))))
    (testing "the HTML prints no number on a quoted heading"
      (let [out (html/emit-document document {:labels labels})]
        ;; TASK-156: one level below the host Section around the quote.
        (is (str/includes? out "<h3>Lord's Day 1</h3>")
            "quoted headings carry no section-number span")
        (is (str/includes? out (str "<blockquote class=\"book-quote\""
                                    " data-source=\"reformed:confessions/heidelberg#sec:comfort\">")))))
    (testing "the LaTeX stars quoted headings and captions, so LaTeX neither numbers nor lists them"
      (let [out (latex/emit-document document {:labels labels})]
        (is (str/includes? out "\\subsection*{Comfort}"))
        (is (str/includes? out "\\subsubsection*{Lord's Day 1}"))
        (is (str/includes? out "\\caption*{A tree}"))
        (is (str/includes? out "\\section{Host}") "the host's own heading is unchanged"))))
  (testing "in a chaptered host, a quoted level-1 heading is no chapter"
    (let [{:keys [labels]} (resolve* (str "---\ntopLevelDivision: chapter\n---\n\n"
                                          "# One {#ch:one}\n\n!quote reformed:heidelberg\n\n"
                                          "# Two {#ch:two}\n\nText.\n"))]
      (is (= "Chapter 2" (:text (get labels "ch:two"))))))
  (testing "an id the host and the book share is no duplicate"
    (let [{:keys [diagnostics labels]}
          (resolve* "# Mine {#sec:q1}\n\n!quote reformed:heidelberg#sec:q1\n\nSee @sec:q1.\n")]
      (is (= [] diagnostics))
      (is (= "Section 1" (:text (get labels "sec:q1")))))))

(deftest quoted-citations-stay-the-books-test
  (let [lib (fs/filesystem-library {:name "pass"
                                    :path (str (io/file "test/fixtures/libraries/passages"))}
                                   {:kinds (into #{} (map name) (keys resolver/default-lexicon))})
        dir (library/library-directory [lib])
        bib {"knuth" {:id "knuth" :type "book" :title "TAOCP"
                      :author [{:family "Knuth" :given "Donald"}]
                      :issued {:date-parts [[1968]]}}}
        {:keys [document diagnostics ordered-keys]}
        (resolve* "Before [@knuth].\n\n!quote pass:heidelberg#sec:q1\n"
                  {:dir dir :resolve-opts {:bibliography bib}})
        cites (nodes-of :cite document)]
    (testing "a quoted citation keeps the text its own book gave it"
      (is (= ["[1]" "Ursinus [1]"] (mapv (comp text-of :text) cites))))
    (testing "and is neither re-resolved nor listed by the host"
      (is (= ["knuth"] ordered-keys))
      (is (not-any? #(= :dangling-citation (:type %)) diagnostics)))))

;; --- AC #3: local cross-references become book references ---------------------

(deftest cross-refs-become-book-refs-test
  (let [{:keys [document diagnostics]} (resolve* "!quote reformed:heidelberg#sec:q2\n")
        q (first (quotes document))]
    (is (= [] diagnostics))
    (is (= [] (nodes-of :cross-ref (:blocks q))))
    (is (= [{:t :book-ref
             :ref "reformed:confessions/heidelberg#sec:q1"
             :passage {:form :label :label "sec:q1"}
             :canonical "reformed:confessions/heidelberg#sec:q1"
             :text [{:t :str :text "Section 1.1"}]}]
           (nodes-of :book-ref (:blocks q)))
        "the book's own text is kept")
    (testing "the rewritten ref parses back to the same book and label"
      (is (= {:id (book-ref/book-id "reformed" ["confessions"] "heidelberg")
              :passage {:form :label :label "sec:q1"}}
             (book-ref/parse-book-ref (:ref (first (nodes-of :book-ref (:blocks q))))))))
    (testing "a reference that dangled in the book has no :canonical and is reported at the !quote"
      (let [dir (library/library-directory
                 [(memory/memory-library
                   {:name "c" :books [(book "c" "a" "# A {#sec:a}\n\nSee @sec:none.\n")]})])
            {:keys [document diagnostics]} (resolve* "\n!quote c:a#sec:a\n" {:dir dir})]
        (is (= [{:t :book-ref :ref "c:a#sec:none" :passage {:form :label :label "sec:none"}
                 :text [{:t :str :text "??"}]}]
               (nodes-of :book-ref document)))
        (is (= [{:type :unknown-label :level :warning :ref "c:a#sec:a" :label "sec:none"
                 :book "c:a" :canonical "c:a#sec:a" :source-file "host.hdoc" :source-line 2}]
               (mapv #(dissoc % :message) diagnostics)))
        (is (str/starts-with? (:message (first diagnostics)) "host.hdoc:2: "))
        (is (= "c:a#sec:a" (:canonical (first (quotes document)))) "the quote itself resolved")))
    (testing "a reference the book never resolved (a locator's blocks) is left for link resolution"
      (let [dir (library/library-directory
                 [(memory/memory-library
                   {:name "c"
                    :books [{:id (book-ref/book-id "c" "v")
                             :document (fn [] {:meta {} :blocks []})
                             :locate (fn [_] {:title "V"
                                              :blocks [{:t :para
                                                        :inlines [{:t :cross-ref :label "sec:x"}]}]})}]})])
            {:keys [document]} (resolver/expand-quotes
                                (parser/parse "\n!quote c:v#1\n" {:source "host.hdoc"})
                                (references/books-option {:directory dir}))
            [link] (nodes-of :book-ref document)]
        (is (= {:t :book-ref :ref "c:v#sec:x" :passage {:form :label :label "sec:x"}} link))
        (is (= {:file "host.hdoc" :line 2} (ast/source link))
            "with no position of its own, it takes the !quote's")
        (testing "which then resolves it (TASK-155), reporting it at the !quote"
          (let [{:keys [document diagnostics]} (resolve* "\n!quote c:v#1\n" {:dir dir})]
            (is (= [[:unknown-label "c:v#sec:x" "host.hdoc" 2]]
                   (mapv (juxt :type :ref :source-file :source-line) diagnostics)))
            (is (= [[{:t :str :text "??"}]] (mapv :text (nodes-of :book-ref document))))))))
    (testing "the host's own cross-references still resolve against the host"
      (let [{:keys [document]} (resolve* "# H {#sec:h}\n\n!quote reformed:heidelberg#sec:q2\n\nSee @sec:h.\n")]
        (is (= ["Section 1"] (mapv :text (nodes-of :cross-ref document))))))))

;; --- AC #4: recursion, cycles and depth ----------------------------------------

(defn- chain-directory
  "A library `c` of books b0 .. b(n-1), each quoting the next; the last
  quotes `last-quote` when given, else nothing."
  [n last-quote]
  (library/library-directory
   [(memory/memory-library
     {:name "c"
      :books (vec (for [i (range n)]
                    (book "c" (str "b" i)
                          (str "# B" i " {#sec:b" i "}\n\nBook " i ".\n\n"
                               (cond
                                 (< i (dec n)) (str "!quote c:b" (inc i) "#sec:b" (inc i) "\n")
                                 last-quote (str "!quote " last-quote "\n")
                                 :else "")))))})]))

(deftest recursive-quotes-test
  (testing "a quote inside quoted content expands, with its own book's references"
    (let [dir (library/library-directory
               [(memory/memory-library
                 {:name "c"
                  :books [(book "c" "a" "# A {#sec:a}\n\n!quote c:b#sec:b\n")
                          (book "c" "b" "# B {#sec:b}\n\nSee @sec:b.\n")]})])
          {:keys [document diagnostics]} (resolve* "!quote c:a#sec:a\n" {:dir dir})
          [outer inner] (quotes document)]
      (is (= [] diagnostics))
      (is (= "c:a#sec:a" (:canonical outer)))
      (is (= "c:b#sec:b" (:canonical inner)))
      (is (= ["c:b#sec:b"] (mapv :ref (nodes-of :book-ref (:blocks inner))))
          "a cross-reference in the nested quote points into ITS book")))
  (testing "eight levels expand; the ninth is quote-too-deep"
    (let [{:keys [document diagnostics]} (resolve* "!quote c:b0#sec:b0\n"
                                                   {:dir (chain-directory 9 nil)})
          qs (quotes document)]
      (is (= 9 (count qs)))
      (is (= 8 (count (filter :canonical qs))))
      (is (= [:quote-too-deep] (mapv :type diagnostics)))
      (is (= "c:b8#sec:b8" (:ref (first diagnostics))))
      (is (= [{:t :para :inlines [{:t :str :text "?? c:b8#sec:b8"}]}] (:blocks (last qs))))))
  (testing "a loop is quote-cycle, not an overflow"
    (let [{:keys [document diagnostics]} (resolve* "!quote c:b0#sec:b0\n"
                                                   {:dir (chain-directory 3 "c:b0#sec:b0")})]
      (is (= [:quote-cycle] (mapv :type diagnostics)))
      (is (= ["c:b0" "c:b1" "c:b2" "c:b0"] (:cycle (first diagnostics))))
      (is (= 3 (count (filter :canonical (quotes document)))))))
  (testing "a book quoting itself is a cycle"
    (let [dir (library/library-directory
               [(memory/memory-library
                 {:name "c" :books [(book "c" "a" "# A {#sec:a}\n\n!quote c:a\n")]})])]
      (is (= [:quote-cycle] (mapv :type (:diagnostics (resolve* "!quote c:a#sec:a\n" {:dir dir})))))))
  (testing "a document that is itself a book cannot quote itself (:self)"
    (let [{:keys [diagnostics]} (resolve* "!quote c:b1#sec:b1\n"
                                          {:dir (chain-directory 2 "c:b0")
                                           :books-opts {:self (book-ref/book-id "c" "b0")}})]
      (is (= [:quote-cycle] (mapv :type diagnostics)))))
  (testing "a book quoted from two places expands the same in both"
    (let [dir (chain-directory 3 nil)
          {:keys [document diagnostics]} (resolve* "!quote c:b1#sec:b1\n\n!quote c:b0#sec:b0\n"
                                                   {:dir dir})
          [first-b1 _ b0 second-b1 _] (quotes document)]
      (is (= [] diagnostics))
      (is (= "c:b1#sec:b1" (:canonical first-b1) (:canonical second-b1)))
      (is (= (:blocks first-b1) (:blocks second-b1)))
      (is (= "c:b0#sec:b0" (:canonical b0))))))

;; --- AC #5: failures leave a placeholder and the build continues --------------

(def ^:private failing-host
  "!quote nolib:x

!quote reformed:nobook

!quote reformed:twin

!quote broken:x

!quote reformed:heidelberg#sec:none

!quote reformed:heidelberg#sec:q1..sec:misery

!quote reformed:heidelberg#14:8

!quote bible:romanos#99:1

!quote Not A Ref

!quote reformed:heidelberg#sec:q1
")

(deftest failures-leave-placeholders-test
  (let [dir (directory {:failures [{:type :invalid-library :level :error :library "broken"
                                    :message "libraries.yaml:3: library \"broken\": bad path"}]})
        {:keys [document] [load-problem & diagnostics] :diagnostics}
        (resolve* failing-host {:dir dir})
        qs (quotes document)
        failed (butlast qs)]
    (testing "the directory's own load problem is reported once, as it is, ahead of the rest"
      (is (= :invalid-library (:type load-problem)))
      (is (not-any? #(= :invalid-library (:type %)) diagnostics)))
    (is (= [:unknown-library :unknown-book :ambiguous-book :unavailable-library :unknown-label
            :bad-label-range :locator-unsupported :bad-locator :malformed-book-ref]
           (mapv :type diagnostics)))
    (testing "each one leaves a placeholder paragraph naming the reference as written"
      (is (every? #(nil? (:canonical %)) failed))
      (is (= (mapv #(str "?? " (:ref %)) failed)
             (mapv #(text-of (:inlines (first (:blocks %)))) failed))))
    (testing "each diagnostic carries the reference as written and its file and line"
      (is (= (mapv :ref failed) (mapv :ref diagnostics)))
      (is (= [1 3 5 7 9 11 13 15 17] (mapv :source-line diagnostics)))
      (is (every? #(= "host.hdoc" (:source-file %)) diagnostics))
      (is (every? #(str/starts-with? (:message %) (str "host.hdoc:" (:source-line %) ": "))
                  diagnostics))
      (is (every? #(str/includes? (:message %) (:ref %)) diagnostics))
      (is (every? #(= :warning (:level %)) diagnostics)))
    (testing "the details survive"
      (let [{:keys [unavailable-library ambiguous-book unknown-label]} (by-type diagnostics)]
        (is (= "broken" (:library (first unavailable-library))))
        (is (= :invalid-library (:type (:cause (first unavailable-library)))))
        (is (= ["reformed:a/twin" "reformed:b/twin"] (:books (first ambiguous-book))))
        (is (= "reformed:confessions/heidelberg" (:canonical (first unknown-label))))))
    (testing "and the build went on: the last quote resolved"
      (is (= "reformed:confessions/heidelberg#sec:q1" (:canonical (last qs)))))
    (testing "the document still emits in both targets"
      (is (str/includes? (html/emit-document document {}) "?? nolib:x"))
      (is (str/includes? (latex/emit-document document {}) "?? nolib:x"))))
  (testing "no library directory at all"
    (let [{:keys [document diagnostics]} (resolve* "!quote reformed:heidelberg\n"
                                                   {:dir (constantly nil)})]
      (is (= [:no-library-directory] (mapv :type diagnostics)))
      (is (= 1 (:source-line (first diagnostics))))
      (is (= "?? reformed:heidelberg"
             (text-of (:inlines (first (:blocks (first (quotes document)))))))))))

(deftest stale-shelf-test
  (let [{:keys [document diagnostics]} (resolve* "\n!quote reformed:creeds/heidelberg#sec:q1\n")]
    (is (= [{:type :stale-shelf :level :info :ref "reformed:creeds/heidelberg#sec:q1"
             :written "creeds" :current "confessions"
             :canonical "reformed:confessions/heidelberg"
             :source-file "host.hdoc" :source-line 2}]
           (mapv #(dissoc % :message) diagnostics)))
    (is (= "reformed:confessions/heidelberg#sec:q1" (:canonical (first (quotes document)))))
    (testing "the short form is not stale"
      (is (= [] (:diagnostics (resolve* "!quote reformed:heidelberg#sec:q1\n")))))))

;; --- AC #6: the directory loads only for a book reference --------------------

(deftest directory-loads-lazily-test
  (let [loads (atom 0)
        counting (fn [] (swap! loads inc) (directory))]
    (testing "a document without a book reference never loads it"
      (resolve* "# Plain {#sec:plain}\n\nNo quotes, @sec:plain.\n" {:dir counting})
      (is (= 0 @loads)))
    (testing "one with several loads it once"
      (let [option (references/books-option {:directory counting})
            doc (parser/parse "!quote reformed:heidelberg#sec:q1\n\n!quote bible:romanos#14:8\n")]
        (resolver/resolve-document doc {:books option})
        (is (= 1 @loads))))
    (testing "without :books a quote is left as parsed"
      (let [doc (parser/parse "!quote reformed:heidelberg#sec:q1\n")
            {:keys [document diagnostics]} (resolver/resolve-document doc)]
        (is (= (:blocks doc) (:blocks document)))
        (is (= [] diagnostics))))))

;; --- the filesystem fixture, through a library directory file -----------------

(defn- temp-dir ^File []
  (.toFile (Files/createTempDirectory "haselnuss-quote" (make-array FileAttribute 0))))

(def ^:private kinds (into #{} (map name) (keys resolver/default-lexicon)))

(defn- write-directory!
  "A library directory file in a fresh temp folder holding `yaml`, whose
  `PASSAGES` stands for the fixture library's absolute path."
  [yaml]
  (let [file (io/file (temp-dir) "libraries.yaml")]
    (spit file (str/replace yaml "PASSAGES"
                            (.getAbsolutePath (io/file "test/fixtures/libraries/passages"))))
    file))

(defn- fs-books-option
  [file ctx]
  (references/books-option
   {:directory (references/directory-loader
                {:path (str file)
                 :registry (directory/register-provider directory/default-registry :filesystem
                                                        (fs/provider {:kinds kinds}))})
    :context ctx}))

(deftest filesystem-library-test
  (let [file (write-directory! "- name: pass\n  type: filesystem\n  path: PASSAGES\n- name: broken\n  type: nowhere\n")
        ctx (passage/context)
        {:keys [document diagnostics]}
        (resolver/resolve-document
         (parser/parse "!quote pass:heidelberg#sec:q3\n\n!quote pass:quoting#sec:quotes\n\n!quote broken:x\n"
                       {:source "host.hdoc"})
         {:books (fs-books-option file ctx)})
        [q3 quoting nicene nowhere broken] (quotes document)]
    (testing "a book folder's included section, its cross-reference rewritten"
      (is (= "pass:catechisms/heidelberg#sec:q3" (:canonical q3)))
      (is (= [["pass:catechisms/heidelberg#sec:q1" "Section 1.1"]]
             (mapv (juxt :ref (comp text-of :text)) (nodes-of :book-ref (:blocks q3))))))
    (testing "quotes inside a filesystem book expand, and fail at the book's own line"
      (is (= "pass:creeds/quoting#sec:quotes" (:canonical quoting)))
      (is (= "pass:creeds/nicene#sec:creed" (:canonical nicene)))
      (is (nil? (:canonical nowhere)))
      (let [w (first (:unknown-book (by-type diagnostics)))
            path (str (io/file "test/fixtures/libraries/passages/creeds/quoting.hdoc"))]
        (is (= "pass:nowhere" (:ref w)))
        (is (= 9 (:source-line w)))
        (is (str/ends-with? (:source-file w) path))))
    (testing "every diagnostic of the build, each once"
      (is (= [:unknown-provider :unreadable-include :unreadable-bibliography :unknown-book
              :unavailable-library]
             (mapv :type diagnostics)))
      (is (nil? (:canonical broken))))))

(deftest directory-loader-test
  (testing "no directory file is nil, so a reference is no-library-directory"
    (let [loader (references/directory-loader {:env {} :home (str (temp-dir))})]
      (is (nil? (loader)))))
  (testing "a directory file that cannot be read stops the build"
    (let [loader (references/directory-loader {:path "/no/such/libraries.yaml"})]
      (is (thrown? clojure.lang.ExceptionInfo
                   (resolver/resolve-document (parser/parse "!quote a:b\n")
                                              {:books (references/books-option {:directory loader})})))))
  (testing "an invalid one too"
    (let [file (write-directory! "- just a string\n")
          loader (references/directory-loader {:path (str file)})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"libraries.yaml:1"
                            (resolver/resolve-document (parser/parse "!quote a:b\n")
                                                       {:books (references/books-option
                                                                {:directory loader})}))))))

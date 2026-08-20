(ns haselnuss.resolver-test
  "Fixtures for the numbering pass (`haselnuss.resolver/number-document`,
  TASK-11), one per acceptance criterion: hierarchical Section numbering by
  nesting depth (AC #1), independent per-kind Figure/Table/MathBlock
  counters honoring section-scoped vs global behavior (AC #2), per-`meta.
  lang` prefix words for the same kind (AC #3), and a custom directive kind
  registered via a caller-supplied lexicon (AC #4); for the CrossRef
  resolution pass (`haselnuss.resolver/resolve-cross-refs`, TASK-12): a
  matching label resolves to the target's computed text (AC #1), a
  `:suppress-prefix` label resolves to the bare number (AC #2), and a
  dangling label produces a warning plus a `??` placeholder instead of a
  crash (AC #3); and for the citation-resolution + bibliography-generation
  pass (`haselnuss.resolver/resolve-citations`, TASK-13): every `Cite` is
  formatted per the selected style (AC #1), a generated bibliography
  Section has one entry per distinct citation key used (AC #2), that
  Section carries an Attr and is genuinely cross-referenceable (AC #3),
  and a citation key with no bibliography match warns instead of crashing
  (AC #4); plus four post-Done clojure-reviewer findings on TASK-13 (see
  its task comments): a default-style (\"numeric\") :mode :author Cite no
  longer renders as a naked, unmarked digit, a Cite mixing :author with
  other-mode items under \"apa\" no longer double-wraps parens, a
  generated bibliography id colliding with a pre-existing node's own id is
  disambiguated (with a warning) instead of silently shadowing it, and an
  unrecognized cslStyle now warns instead of silently falling back; and
  for the document-wide derived-structures pass (`haselnuss.resolver/
  derive-toc`/`derive-list-of-figures`/`derive-list-of-tables`/`derive-
  navigation`, TASK-14): the derived TOC nests exactly like the resolved
  Section tree and carries each section's resolved number (AC #1), the
  derived list-of-figures/list-of-tables include every numbered Figure/
  Table with its caption and number (AC #2), and every section's next/
  previous matches a pre-order walk of the resolved Section tree and is
  never read from any authored field (AC #3); and for the structural-
  diagnostics pass (`haselnuss.resolver/structural-diagnostics`/
  `resolve-document`, TASK-15): two nodes -- Block- or Inline-level (a
  Link/Image/Span's own id) alike -- sharing an id produce a duplicate-id
  diagnostic naming both locations (AC #1), a Directive whose
  name is not in a supplied directive registry produces an
  unknown-directive diagnostic (AC #2), and `resolve-document` returns one
  combined diagnostics collection alongside the resolved document,
  including earlier passes' dangling-ref/cite warnings (AC #3).

  TASK-36 adds the third diagnostic kind TASK-15 deferred: an id prefix
  whose numbering kind disagrees with the AST role of the node it labels
  (`kind-role-mismatch-diagnostic-test`, AC #1/#2), and a Section whose
  id does not resolve to a section-scoped kind and therefore drops a
  level from every descendant's number (`unnumbered-section-id-
  diagnostic-test`, the rest of AC #1). Both surface through
  `resolve-document` alongside every earlier pass's warnings
  (`kind-role-diagnostics-surface-through-resolve-document-test`, AC
  #3).

  TASK-48 adds its sibling: a Directive whose id prefix names a
  different numbering kind than the directive's own name maps to, which
  is one node numbered twice and differently across the two targets
  (`directive-kind-mismatch-diagnostic-test`)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [haselnuss.ast :as ast]
            [haselnuss.resolver :as resolver]))

(def attr
  "A minimal Attr with only `:id` set, per `haselnuss.ast-test`'s own `attr`
  fixture convention."
  {:classes [] :props {}})

(defn- id-attr
  [id]
  (assoc attr :id id))

(defn- section
  [id blocks]
  {:t :section :level 1 :heading [] :blocks blocks :attr (id-attr id)})

(defn- math-block
  [id]
  {:t :math-block :tex "x" :attr (id-attr id)})

(defn- figure
  [id]
  {:t :figure :content {:t :code-block :text "x" :attr attr} :caption [] :attr (id-attr id)})

(defn- table
  [id]
  {:t :table :head {:cells []} :rows [] :caption [] :colspec [] :attr (id-attr id)})

(defn- theorem
  [id]
  {:t :directive :name "theorem" :blocks [] :attr (id-attr id)})

(defn- cross-ref
  ([label] {:t :cross-ref :label label})
  ([label suppress-prefix] {:t :cross-ref :label label :suppress-prefix suppress-prefix}))

(defn- bib-entry
  "A minimal CSL-JSON reference entry (keyword keys, per
  `resolver/load-bibliography`'s convention) for `id`, with `family`/
  `given`/`year`/`title`."
  [id family given year title]
  {:id id
   :author [{:family family :given given}]
   :issued {:date-parts [[year]]}
   :title title})

(defn- cite-item
  ([citation-key mode] {:key citation-key :mode mode})
  ([citation-key mode field inlines] (assoc {:key citation-key :mode mode} field inlines)))

(defn- cite
  [& items]
  {:t :cite :items (vec items)})

(defn- para
  [& inlines]
  {:t :para :inlines (vec inlines)})

(deftest hierarchical-section-numbering-test
  (testing "AC #1: sections number hierarchically by nesting depth"
    (let [doc {:meta {}
               :blocks [(section "sec:one" [])
                        (section "sec:two"
                                 [(section "sec:two-a" [])
                                  (section "sec:two-b"
                                           [(section "sec:two-b-i" [])])])]}
          labels (resolver/number-document doc)]
      (is (= "1" (:number (get labels "sec:one"))))
      (is (= "2" (:number (get labels "sec:two"))))
      (is (= "2.1" (:number (get labels "sec:two-a"))))
      (is (= "2.2" (:number (get labels "sec:two-b"))))
      (is (= "2.2.1" (:number (get labels "sec:two-b-i")))))))

(deftest independent-per-kind-counters-test
  (testing "AC #2: fig/tbl/eq number per their own counter, independent of section numbering"
    (let [doc {:meta {}
               :blocks [(section "sec:intro" [(theorem "thm:a")])
                        (section "sec:body"
                                 [(figure "fig:tree")
                                  (table "tbl:yields")
                                  (section "sec:sub"
                                           [(math-block "eq:one")
                                            (math-block "eq:two")])
                                  (theorem "thm:b")])]}
          labels (resolver/number-document doc)]
      (testing "section-scoped kinds compose with the enclosing section's own number"
        (is (= "2.1" (:number (get labels "fig:tree"))))
        (is (= "2.1" (:number (get labels "tbl:yields"))))
        (is (= "2.1.1" (:number (get labels "eq:one"))))
        (is (= "2.1.2" (:number (get labels "eq:two")))))
      (testing "a global kind's counter runs across the whole document, ignoring sections"
        (is (= "1" (:number (get labels "thm:a"))))
        (is (= "2" (:number (get labels "thm:b")))))
      (testing "each kind's counter is independent of every other kind's"
        (is (= "1" (:number (get labels "sec:intro"))))
        (is (= "2" (:number (get labels "sec:body"))))
        (is (= "2.1" (:number (get labels "sec:sub"))))))))

(deftest lang-specific-prefix-words-test
  (testing "AC #3: the same document resolved with different meta.lang values prints different prefix words"
    (let [doc-of (fn [lang] {:meta {:lang lang} :blocks [(figure "fig:tree")]})
          en (resolver/number-document (doc-of "en"))
          pt (resolver/number-document (doc-of "pt-BR"))]
      (is (= "Figure" (:word (get en "fig:tree"))))
      (is (= "Figura" (:word (get pt "fig:tree"))))
      (is (= "Figure 1" (:text (get en "fig:tree"))))
      (is (= "Figura 1" (:text (get pt "fig:tree"))))
      (is (not= (:word (get en "fig:tree")) (:word (get pt "fig:tree"))))))
  (testing "defaults to \"en\" when meta.lang is absent"
    (let [labels (resolver/number-document {:meta {} :blocks [(figure "fig:tree")]})]
      (is (= "Figure" (:word (get labels "fig:tree")))))))

(deftest custom-directive-kind-test
  (testing "AC #4: a custom directive registering a new kind numbers via its own counter"
    ;; `frm` rather than the `alg` this used to use: TASK-58 made `alg`
    ;; built in, so the closing assertion -- that an UNREGISTERED kind
    ;; numbers nothing -- would have been testing a kind that is now
    ;; registered, and passing for the wrong reason.
    (let [lexicon (merge resolver/default-lexicon
                         {:frm {:counter :global
                                :words {"en" {:singular "Form"}}}})
          doc {:meta {}
               :blocks [(section "sec:intro"
                                 [{:t :directive :name "form" :blocks [] :attr (id-attr "frm:sort")}])
                        {:t :directive :name "form" :blocks [] :attr (id-attr "frm:search")}]}
          labels (resolver/number-document doc lexicon)]
      (is (= {:kind :frm :path [1] :number "1" :word "Form" :text "Form 1"}
             (get labels "frm:sort")))
      (is (= {:kind :frm :path [2] :number "2" :word "Form" :text "Form 2"}
             (get labels "frm:search")))
      (testing "an unregistered kind is simply not numbered (no lexicon entry, no crash)"
        (let [labels (resolver/number-document doc)]
          (is (nil? (get labels "frm:sort"))))))))

(deftest unlabeled-nodes-are-not-numbering-targets-test
  (testing "a node without an id is not a numbering target, mirroring TASK-8 AC #3"
    (let [doc {:meta {}
               :blocks [{:t :figure :content {:t :code-block :text "x" :attr attr}
                         :caption [] :attr attr}
                        (figure "fig:tree")]}
          labels (resolver/number-document doc)]
      (is (= 1 (count labels)))
      (is (= "1" (:number (get labels "fig:tree"))))))
  (testing "an id with no recognized kind prefix is not numbered"
    (let [doc {:meta {} :blocks [(figure "no-colon-here")]}]
      (is (empty? (resolver/number-document doc))))))

(deftest footnote-nested-target-test
  (testing "a labeled Figure nested inside a footnote still numbers"
    (let [doc {:meta {}
               :blocks [{:t :para
                         :inlines [{:t :note :blocks [(figure "fig:tree")]}]}]}
          labels (resolver/number-document doc)]
      (is (= "1" (:number (get labels "fig:tree")))))))

(deftest section-scoped-counter-resets-across-siblings-test
  (testing "a section-scoped kind's counter is keyed by the enclosing
            section's own path, so it independently restarts at 1 in each
            of two different sibling sections rather than running as one
            flat count across the whole document"
    (let [doc {:meta {}
               :blocks [(section "sec:a" [(figure "fig:a1") (figure "fig:a2")])
                        (section "sec:b" [(figure "fig:b1")])]}
          labels (resolver/number-document doc)]
      (is (= "1.1" (:number (get labels "fig:a1"))))
      (is (= "1.2" (:number (get labels "fig:a2"))))
      (is (= "2.1" (:number (get labels "fig:b1")))))))

(deftest unlabeled-section-is-transparent-for-nesting-test
  (testing "an unlabeled Section (a bare ATX heading with no {#id} group,
            the common case per TASK-4/TASK-5) consumes no counter itself
            and does not extend the section path for its own children --
            a labeled descendant numbers exactly as if the unlabeled
            wrapper section were not there at all"
    (let [doc {:meta {}
               :blocks [(section nil [(section "sec:inner" [(figure "fig:tree")])])]}
          labels (resolver/number-document doc)]
      (is (nil? (get labels nil)))
      (is (= "1" (:number (get labels "sec:inner"))))
      (is (= "1.1" (:number (get labels "fig:tree")))))))

(deftest eq-template-formatting-test
  (testing "the `eq` kind's \"Eq. ({n})\" template (sec6.1's own worked
            example) is actually used for :text, not just :number, unlike
            the default \"{word} {n}\" formatting other kinds use"
    (let [doc {:meta {} :blocks [(math-block "eq:one")]}
          labels (resolver/number-document doc)]
      (is (= "1" (:number (get labels "eq:one"))))
      (is (= "Eq." (:word (get labels "eq:one"))))
      (is (= "Eq. (1)" (:text (get labels "eq:one")))))))

;; Cross-reference resolution (`resolve-cross-refs`, TASK-12)

(defn- resolved-cross-refs
  "Every `:cross-ref` node in `resolve-cross-refs`'s resolved document, in
  document order (found via `tree-seq` since a CrossRef can be nested
  arbitrarily deep in Inlines/Blocks)."
  [{:keys [document]}]
  (->> document
       (tree-seq coll? #(cond (map? %) (vals %) (coll? %) (seq %) :else nil))
       (filter #(and (map? %) (= :cross-ref (:t %))))))

(deftest cross-ref-resolves-to-target-label-test
  (testing "AC #1: a CrossRef whose label matches an existing id resolves
            to that target's computed number and prefix word"
    (let [doc {:meta {} :blocks [(figure "fig:tree") (para (cross-ref "fig:tree"))]}
          {:keys [document warnings]} (resolver/resolve-cross-refs doc)
          [resolved] (resolved-cross-refs {:document document})]
      (is (empty? warnings))
      (is (= "fig:tree" (:target resolved)))
      (is (= "Figure 1" (:text resolved))))))

(deftest cross-ref-suppress-prefix-resolves-to-bare-number-test
  (testing "AC #2: a CrossRef with suppressPrefix set resolves to just the
            number, without the kind's prefix word"
    (let [doc {:meta {} :blocks [(figure "fig:tree") (para (cross-ref "fig:tree" true))]}
          {:keys [document warnings]} (resolver/resolve-cross-refs doc)
          [resolved] (resolved-cross-refs {:document document})]
      (is (empty? warnings))
      (is (= "fig:tree" (:target resolved)))
      (is (= "1" (:text resolved))))))

(deftest dangling-cross-ref-produces-warning-and-placeholder-test
  (testing "AC #3: a CrossRef with no matching id produces a build warning
            and a double-question-mark placeholder instead of a crash"
    (let [doc {:meta {} :blocks [(para (cross-ref "fig:missing"))]}
          {:keys [document warnings]} (resolver/resolve-cross-refs doc)
          [resolved] (resolved-cross-refs {:document document})]
      (is (= "??" (:text resolved)))
      (is (nil? (:target resolved)))
      (is (= [{:type :dangling-cross-ref
               :label "fig:missing"
               :message "dangling cross-reference: no target with id \"fig:missing\""}]
             warnings)))))

(deftest cross-ref-resolution-does-not-touch-unrelated-nodes-test
  (testing "resolving a CrossRef leaves sibling nodes and the referenced
            target block untouched"
    (let [fig (figure "fig:tree")
          doc {:meta {} :blocks [fig (para (cross-ref "fig:tree") {:t :space} {:t :str :text "x"})]}
          {:keys [document]} (resolver/resolve-cross-refs doc)]
      (is (= fig (first (:blocks document))))
      (is (= [{:t :space} {:t :str :text "x"}] (rest (:inlines (second (:blocks document)))))))))

(deftest cross-ref-nested-in-structural-positions-test
  (testing "a CrossRef reachable through every nested Inline/Block position
            (sec4.3/4.4) still resolves, not just a top-level Para"
    (let [doc {:meta {:title [(cross-ref "fig:tree")]}
               :blocks [(figure "fig:tree")
                        {:t :section :level 1 :heading [(cross-ref "fig:tree")]
                         :blocks [{:t :list :ordered false :tight true :attr attr
                                   :items [[(para (cross-ref "fig:tree"))]]}
                                  {:t :block-quote
                                   :blocks [(para {:t :emph :inlines [(cross-ref "fig:tree")]})]}
                                  {:t :table
                                   :head {:cells []}
                                   :rows [{:cells [{:blocks [(para (cross-ref "fig:tree"))]}]}]
                                   :caption [(cross-ref "fig:tree")]
                                   :colspec []
                                   :attr attr}
                                  (para {:t :cite
                                         :items [{:key "k" :mode :normal
                                                  :prefix [(cross-ref "fig:tree")]}]})
                                  (para {:t :note :blocks [(para (cross-ref "fig:tree"))]})]
                         :attr attr}]}
          {:keys [document warnings]} (resolver/resolve-cross-refs doc)]
      (is (empty? warnings))
      (is (= 8 (count (resolved-cross-refs {:document document}))))
      (is (every? #(= "Figure 1" (:text %)) (resolved-cross-refs {:document document}))))))

(deftest multiple-dangling-cross-refs-all-produce-warnings-test
  (testing "AC #3: every dangling CrossRef in a document contributes its
            own warning -- distinct missing labels, and repeated
            occurrences of the SAME missing label, are neither dropped
            nor deduplicated"
    (let [doc {:meta {}
               :blocks [(para (cross-ref "fig:missing-a")
                              (cross-ref "fig:missing-b")
                              (cross-ref "fig:missing-a"))]}
          {:keys [document warnings]} (resolver/resolve-cross-refs doc)
          resolved (resolved-cross-refs {:document document})]
      (is (= 3 (count warnings)))
      (is (= ["fig:missing-a" "fig:missing-b" "fig:missing-a"]
             (map :label warnings)))
      (is (every? #(= "??" (:text %)) resolved)))))

(deftest cross-ref-inside-directive-fallback-is-not-walked-test
  (testing "a CrossRef nested inside a Directive's optional :fallback is
            deliberately left untouched (deferred to TASK-17's lower
            pass, not this task's scope) -- it is neither resolved nor
            reported as dangling, unlike every other structural position"
    (let [doc {:meta {}
               :blocks [{:t :directive :name "unknown" :blocks [] :attr attr
                         :fallback {:kind :blocks :blocks [(para (cross-ref "fig:missing"))]}}]}
          {:keys [document warnings]} (resolver/resolve-cross-refs doc)
          fallback-cross-ref (-> document :blocks first :fallback :blocks first :inlines first)]
      (is (empty? warnings))
      (is (= (cross-ref "fig:missing") fallback-cross-ref)))))

(deftest resolve-cross-refs-accepts-caller-supplied-labels-test
  (testing "the 2-arg arity resolves against a caller-supplied label table
            (e.g. built with a custom lexicon, per TASK-11 AC #4's pattern)
            instead of always recomputing it via number-document"
    ;; `frm`, a kind the built-in lexicon does NOT know: TASK-58 made
    ;; `alg` built in, so numbering this document with the DEFAULT
    ;; lexicon would resolve the reference just as well and the test
    ;; would pass whether or not the caller's own table was used --
    ;; which is the one thing it exists to check (found by review).
    (let [doc {:meta {} :blocks [{:t :directive :name "form" :blocks []
                                  :attr (id-attr "frm:sort")}
                                 (para (cross-ref "frm:sort"))]}
          lexicon (merge resolver/default-lexicon
                         {:frm {:counter :global :words {"en" {:singular "Form"}}}})
          labels (resolver/number-document doc lexicon)
          {:keys [document warnings]} (resolver/resolve-cross-refs doc labels)
          [resolved] (resolved-cross-refs {:document document})]
      (is (empty? warnings))
      (is (= "Form 1" (:text resolved)))
      (testing "and with the DEFAULT labels the same reference dangles,
                which is what makes the assertion above about the
                caller's own table rather than about the built-ins"
        (is (= "??" (:text (first (resolved-cross-refs
                                   (resolver/resolve-cross-refs doc))))))))))

;; Citation resolution and bibliography generation (`resolve-citations`, TASK-13)

(defn- resolved-cites
  "Every `:cite` node in `resolve-citations`'s resolved document, in
  document order (found via `tree-seq`, mirroring `resolved-cross-refs`)."
  [{:keys [document]}]
  (->> document
       (tree-seq coll? #(cond (map? %) (vals %) (coll? %) (seq %) :else nil))
       (filter #(and (map? %) (= :cite (:t %))))))

(defn- bibliography-section
  "`resolve-citations`'s appended bibliography Section, or nil if none was
  appended."
  [{:keys [document]}]
  (let [last-block (last (:blocks document))]
    (when (and (= :section (:t last-block))
               (= "sec:bibliography" (:id (:attr last-block))))
      last-block)))

(defn- bibliography-entry-texts
  "The plain-text content of every bibliography-List item in `bib-section`,
  in order (each item is a single Para whose `:inlines` is one `:str` node
  per `resolver/full-reference-text`)."
  [bib-section]
  (->> bib-section :blocks first :items
       (map (fn [item] (apply str (map :text (:inlines (first item))))))))

(def smith-2020
  "A minimal CSL-JSON entry for a single-author 2020 reference, per
  `bib-entry`."
  (bib-entry "smith2020" "Smith" "Ann" 2020 "A Paper"))

(def jones-2019
  "A minimal CSL-JSON entry for a single-author 2019 reference, per
  `bib-entry`."
  (bib-entry "jones2019" "Jones" "Bob" 2019 "Another Paper"))

(def bibliography
  "A tiny two-entry bibliography (key -> CSL-JSON entry) shared by every
  `resolve-citations` test below."
  {"smith2020" smith-2020
   "jones2019" jones-2019})

(deftest cite-formats-per-selected-style-test
  (testing "AC #1: every Cite node is formatted per the style meta.cslStyle
            selects -- the same document resolved with two different
            styles produces different :text for the same Cite"
    (let [doc {:meta {} :blocks [(para (cite (cite-item "smith2020" :normal)))]}
          numeric (resolver/resolve-citations doc bibliography)
          apa (resolver/resolve-citations (assoc-in doc [:meta :csl-style] "apa") bibliography)
          [numeric-cite] (resolved-cites numeric)
          [apa-cite] (resolved-cites apa)]
      (is (empty? (:warnings numeric)))
      (is (empty? (:warnings apa)))
      (is (= [{:t :str :text "["} {:t :str :text "1"} {:t :str :text "]"}]
             (:text numeric-cite)))
      (is (= [{:t :str :text "("} {:t :str :text "Smith, 2020"} {:t :str :text ")"}]
             (:text apa-cite)))
      (is (not= (:text numeric-cite) (:text apa-cite)))))
  (testing "an :author-mode Cite reads as a bare sentence subject -- no
            outer wrapping punctuation, unlike :normal"
    (let [doc {:meta {:csl-style "apa"}
               :blocks [(para (cite (cite-item "smith2020" :author)))]}
          [resolved] (resolved-cites (resolver/resolve-citations doc bibliography))]
      (is (= [{:t :str :text "Smith (2020)"}] (:text resolved)))))
  (testing "a :year-mode Cite (pandoc's -@key suppress-author convention,
            TASK-9) omits the author name"
    (let [doc {:meta {:csl-style "apa"}
               :blocks [(para (cite (cite-item "smith2020" :year)))]}
          [resolved] (resolved-cites (resolver/resolve-citations doc bibliography))]
      (is (= [{:t :str :text "("} {:t :str :text "2020"} {:t :str :text ")"}]
             (:text resolved)))))
  (testing "a CiteItem's own authored :prefix/:suffix Inlines are spliced
            into :text verbatim, not flattened to a string"
    (let [item (cite-item "smith2020" :normal :suffix [{:t :str :text "p. 42"}])
          doc {:meta {} :blocks [(para (cite item))]}
          [resolved] (resolved-cites (resolver/resolve-citations doc bibliography))]
      (is (= [{:t :str :text "["} {:t :str :text "1"} {:t :str :text ", "}
              {:t :str :text "p. 42"} {:t :str :text "]"}]
             (:text resolved)))))
  (testing "an unrecognized cslStyle falls back to the built-in \"numeric\" style"
    (let [doc {:meta {:csl-style "no-such-style"}
               :blocks [(para (cite (cite-item "smith2020" :normal)))]}
          [resolved] (resolved-cites (resolver/resolve-citations doc bibliography))]
      (is (= [{:t :str :text "["} {:t :str :text "1"} {:t :str :text "]"}]
             (:text resolved))))))

(deftest bibliography-has-one-entry-per-distinct-key-test
  (testing "AC #2: repeated citations of the same key produce exactly one
            bibliography entry, and the bibliography holds one entry per
            distinct key actually used"
    (let [doc {:meta {}
               :blocks [(para (cite (cite-item "smith2020" :normal)))
                        (para (cite (cite-item "smith2020" :author)))
                        (para (cite (cite-item "jones2019" :normal)))]}
          {:keys [document]} (resolver/resolve-citations doc bibliography)
          bib-section (bibliography-section {:document document})]
      (is (some? bib-section))
      (is (= ["Smith, A. (2020). A Paper."
              "Jones, B. (2019). Another Paper."]
             (bibliography-entry-texts bib-section)))))
  (testing "the \"author-date\"/\"apa\" style sorts the bibliography
            alphabetically by author surname, independent of citation order"
    (let [doc {:meta {:csl-style "apa"}
               :blocks [(para (cite (cite-item "jones2019" :normal)))
                        (para (cite (cite-item "smith2020" :normal)))]}
          {:keys [document]} (resolver/resolve-citations doc bibliography)
          bib-section (bibliography-section {:document document})]
      (is (= ["Jones, B. (2019). Another Paper."
              "Smith, A. (2020). A Paper."]
             (bibliography-entry-texts bib-section)))))
  (testing "no bibliography Section is appended when no citation resolves
            to a bibliography entry (nothing to list)"
    (let [doc {:meta {} :blocks []}
          {:keys [document]} (resolver/resolve-citations doc bibliography)]
      (is (nil? (bibliography-section {:document document}))))))

(deftest bibliography-section-is-cross-referenceable-test
  (testing "AC #3: the generated bibliography Section carries an Attr with
            an :id, and a CrossRef to that id resolves through the
            ordinary numbering + CrossRef-resolution passes -- it is
            genuinely, not just nominally, cross-referenceable"
    (let [doc {:meta {} :blocks [(para (cite (cite-item "smith2020" :normal)))]}
          {:keys [document]} (resolver/resolve-citations doc bibliography)
          bib-section (bibliography-section {:document document})]
      (is (some? (:attr bib-section)))
      (is (= "sec:bibliography" (:id (:attr bib-section))))
      (let [with-ref (update document :blocks conj (para (cross-ref "sec:bibliography")))
            labels (resolver/number-document with-ref)
            {:keys [document warnings]} (resolver/resolve-cross-refs with-ref labels)
            [resolved] (resolved-cross-refs {:document document})]
        (is (empty? warnings))
        (is (= "sec:bibliography" (:target resolved)))
        (is (= "Section 1" (:text resolved)))))))

(deftest dangling-citation-produces-warning-and-placeholder-test
  (testing "AC #4: a citation key with no matching bibliography entry
            produces a build warning and a \"??\" placeholder instead of
            a crash, and is excluded from the generated bibliography"
    (let [doc {:meta {} :blocks [(para (cite (cite-item "missing2000" :normal)))]}
          {:keys [document warnings]} (resolver/resolve-citations doc bibliography)
          [resolved] (resolved-cites {:document document})]
      (is (= [{:type :dangling-citation
               :key "missing2000"
               :message "dangling citation: no bibliography entry for key \"missing2000\""}]
             warnings))
      (is (= [{:t :str :text "["} {:t :str :text "??"} {:t :str :text "]"}]
             (:text resolved)))
      (is (nil? (bibliography-section {:document document}))))))

(deftest multiple-dangling-citations-all-produce-warnings-test
  (testing "every dangling citation contributes its own warning -- distinct
            missing keys, and repeated occurrences of the SAME missing
            key, are neither dropped nor deduplicated"
    (let [doc {:meta {}
               :blocks [(para (cite (cite-item "missing-a" :normal)
                                    (cite-item "missing-b" :normal)
                                    (cite-item "missing-a" :normal)))]}
          {:keys [warnings]} (resolver/resolve-citations doc bibliography)]
      (is (= 3 (count warnings)))
      (is (= ["missing-a" "missing-b" "missing-a"] (map :key warnings))))))

(deftest cite-nested-in-structural-positions-test
  (testing "a Cite reachable through every nested Inline/Block position
            (sec4.3/4.4) still resolves, not just a top-level Para"
    (let [doc {:meta {:title [(cite (cite-item "smith2020" :normal))]}
               :blocks [{:t :section :level 1 :heading [(cite (cite-item "smith2020" :normal))]
                         :blocks [{:t :list :ordered false :tight true :attr attr
                                   :items [[(para (cite (cite-item "smith2020" :normal)))]]}
                                  {:t :block-quote
                                   :blocks [(para {:t :emph :inlines [(cite (cite-item "smith2020" :normal))]})]}
                                  {:t :table
                                   :head {:cells []}
                                   :rows [{:cells [{:blocks [(para (cite (cite-item "smith2020" :normal)))]}]}]
                                   :caption [(cite (cite-item "smith2020" :normal))]
                                   :colspec []
                                   :attr attr}
                                  (para {:t :note :blocks [(para (cite (cite-item "smith2020" :normal)))]})]
                         :attr attr}]}
          {:keys [document warnings]} (resolver/resolve-citations doc bibliography)]
      (is (empty? warnings))
      (is (= 7 (count (resolved-cites {:document document}))))
      (is (every? #(some? (:text %)) (resolved-cites {:document document}))))))

(deftest cite-nested-in-cite-item-prefix-is-resolved-test
  (testing "a Cite nested inside another CiteItem's own :prefix is also
            resolved (not just the outer Cite) -- both get formatted :text"
    (let [doc {:meta {}
               :blocks [(para (cite (cite-item "smith2020" :normal
                                               :prefix [(cite (cite-item "jones2019" :normal))])))]}
          {:keys [document warnings]} (resolver/resolve-citations doc bibliography)
          [outer] (resolved-cites {:document document})
          inner (first (:prefix (first (:items outer))))]
      (is (empty? warnings))
      (is (some? (:text outer)))
      (is (= :cite (:t inner)))
      (is (some? (:text inner)))
      (is (not= (:text outer) (:text inner))))))

(deftest resolve-citations-without-meta-bibliography-test
  (testing "the 1-arg arity works even when meta.bibliography is absent --
            every citation simply dangles rather than crashing"
    (let [doc {:meta {} :blocks [(para (cite (cite-item "smith2020" :normal)))]}
          {:keys [document warnings]} (resolver/resolve-citations doc)
          [resolved] (resolved-cites {:document document})]
      (is (= 1 (count warnings)))
      (is (= [{:t :str :text "["} {:t :str :text "??"} {:t :str :text "]"}]
             (:text resolved))))))

(deftest resolve-citations-loads-meta-bibliography-from-disk-test
  (testing "the 1-arg arity loads meta.bibliography's path via
            load-bibliography when present"
    (let [file (java.io.File/createTempFile "haselnuss-bib" ".json")]
      (try
        (spit file (str "[{\"id\": \"smith2020\", \"author\": "
                        "[{\"family\": \"Smith\", \"given\": \"Ann\"}], "
                        "\"issued\": {\"date-parts\": [[2020]]}, \"title\": \"A Paper\"}]"))
        (let [doc {:meta {:bibliography (.getPath file)}
                   :blocks [(para (cite (cite-item "smith2020" :normal)))]}
              {:keys [document warnings]} (resolver/resolve-citations doc)
              [resolved] (resolved-cites {:document document})]
          (is (empty? warnings))
          (is (= [{:t :str :text "["} {:t :str :text "1"} {:t :str :text "]"}]
                 (:text resolved))))
        (finally (io/delete-file file true))))))

(deftest numeric-author-mode-cite-is-not-a-naked-digit-test
  (testing "review fix #1: under the DEFAULT \"numeric\" style (no cslStyle
            set), a :mode :author Cite (the bare @key author-in-text form,
            SPEC.md sec5.11, the primary bare-citation form) is no longer a
            naked, unmarked digit indistinguishable from body text -- it
            still shows the entry's author name alongside a bracketed
            number, the conventional way a numeric style marks an
            author-in-text mention"
    (let [doc {:meta {} :blocks [(para (cite (cite-item "smith2020" :author)))]}
          [resolved] (resolved-cites (resolver/resolve-citations doc bibliography))]
      (is (= [{:t :str :text "Smith [1]"}] (:text resolved)))
      (is (not= [{:t :str :text "1"}] (:text resolved)))))
  (testing "a dangling (no bibliography match) :author-mode citation under
            the default numeric style still shows a visible, bracketed
            placeholder marker, not a bare \"??\" indistinguishable from
            body text"
    (let [doc {:meta {} :blocks [(para (cite (cite-item "missing2000" :author)))]}
          {:keys [document warnings]} (resolver/resolve-citations doc bibliography)
          [resolved] (resolved-cites {:document document})]
      (is (= 1 (count warnings)))
      (is (= [{:t :str :text "[??]"}] (:text resolved))))))

(deftest mixed-mode-cite-does-not-double-wrap-parens-test
  (testing "review fix #2: a Cite mixing :author and non-:author CiteItem
            modes under \"apa\" produces one correctly punctuated
            parenthetical, not a doubled/nested paren around the
            :author-mode item's own year"
    (let [doc {:meta {:csl-style "apa"}
               :blocks [(para (cite (cite-item "smith2020" :author)
                                    (cite-item "jones2019" :normal)))]}
          [resolved] (resolved-cites (resolver/resolve-citations doc bibliography))]
      (is (= [{:t :str :text "("} {:t :str :text "Smith, 2020"}
              {:t :str :text "; "} {:t :str :text "Jones, 2019"}
              {:t :str :text ")"}]
             (:text resolved))))))

(deftest unrecognized-csl-style-produces-warning-test
  (testing "review fix #4: a cslStyle naming no registered style produces a
            build warning, consistent with every other \"couldn't resolve
            this\" case in this namespace (dangling cross-ref, dangling
            citation), rather than silently falling back with no trace"
    (let [doc {:meta {:csl-style "chicago"}
               :blocks [(para (cite (cite-item "smith2020" :normal)))]}
          {:keys [warnings]} (resolver/resolve-citations doc bibliography)]
      (is (= [{:type :unrecognized-citation-style
               :style "chicago"
               :message "unrecognized cslStyle \"chicago\": falling back to the built-in \"numeric\" style"}]
             warnings))))
  (testing "no such warning when cslStyle is simply absent -- the numeric
            default is deliberate, not a fallback from something invalid"
    (let [doc {:meta {} :blocks [(para (cite (cite-item "smith2020" :normal)))]}
          {:keys [warnings]} (resolver/resolve-citations doc bibliography)]
      (is (empty? warnings)))))

(deftest bibliography-id-disambiguates-on-collision-test
  (testing "review fix #3: when the document already has a node whose own
            id is \"sec:bibliography\", the generated bibliography Section
            uses a different, non-colliding id instead of silently sharing
            it -- which would otherwise make number-document's label table
            silently keep only the later, generated section's entry -- and
            a build warning names the collision"
    (let [doc {:meta {}
               :blocks [(section "sec:bibliography" [])
                        (para (cite (cite-item "smith2020" :normal)))]}
          {:keys [document warnings]} (resolver/resolve-citations doc bibliography)
          ids (map #(:id (:attr %)) (:blocks document))]
      (is (= ["sec:bibliography" nil "sec:bibliography-2"] ids))
      (is (= [{:type :bibliography-id-collision
               :id "sec:bibliography"
               :resolved-id "sec:bibliography-2"
               :message (str "bibliography id \"sec:bibliography\" is already used by an "
                             "existing node; using \"sec:bibliography-2\" for the generated "
                             "bibliography section instead")}]
             warnings))
      (testing "the author's own original node keeps its own id -- a
                CrossRef to it still resolves to that ORIGINAL node, not
                the generated bibliography now sharing its id prefix"
        (let [with-ref (update document :blocks conj (para (cross-ref "sec:bibliography")))
              labels (resolver/number-document with-ref)
              {:keys [document warnings]} (resolver/resolve-cross-refs with-ref labels)
              [resolved] (resolved-cross-refs {:document document})]
          (is (empty? warnings))
          (is (= "sec:bibliography" (:target resolved)))
          (is (= "Section 1" (:text resolved))))))))

(deftest resolved-document-still-validates-test
  (testing "resolve-citations's output still validates against ast/valid?
            Document -- extra :text keys are fine since malli :map
            schemas are open by default (mirrors TASK-12's own check)"
    (let [doc {:meta {}
               :blocks [(para (cite (cite-item "smith2020" :normal)
                                    (cite-item "missing2000" :normal)))]}
          {:keys [document]} (resolver/resolve-citations doc bibliography)]
      (is (ast/valid? ast/Document document)))))

(deftest resolve-citations-returns-ordered-keys-and-bibliography-id-test
  (testing "TASK-20 addition: resolve-citations also returns :ordered-keys
            (every distinct resolvable key, in the exact order the
            generated bibliography List renders them) and
            :bibliography-id (the appended Section's own attr.id) -- data
            an emitter needs to link an in-text citation to its own
            bibliography-list entry, and which is not otherwise
            recoverable from the resolved document alone"
    (let [doc {:meta {}
               :blocks [(para (cite (cite-item "jones2019" :normal)
                                    (cite-item "smith2020" :normal))
                              (cite (cite-item "smith2020" :normal)))]}
          {:keys [ordered-keys bibliography-id]} (resolver/resolve-citations doc bibliography)]
      (is (= ["jones2019" "smith2020"] ordered-keys))
      (is (= "sec:bibliography" bibliography-id))))
  (testing "author-date/apa's alphabetical bibliography sort is reflected
            in :ordered-keys too, not just first-appearance order -- since
            \"Jones\" sorts before \"Smith\" but \"smith2020\" is cited
            first in the document"
    (let [doc {:meta {:csl-style "apa"}
               :blocks [(para (cite (cite-item "smith2020" :normal)
                                    (cite-item "jones2019" :normal)))]}
          {:keys [ordered-keys]} (resolver/resolve-citations doc bibliography)]
      (is (= ["jones2019" "smith2020"] ordered-keys))))
  (testing "no citation resolves to a bibliography entry -> :ordered-keys
            is empty and :bibliography-id is nil, matching \"no Section
            appended\""
    (let [doc {:meta {} :blocks [(para (cite (cite-item "missing2000" :normal)))]}
          {:keys [ordered-keys bibliography-id]} (resolver/resolve-citations doc bibliography)]
      (is (= [] ordered-keys))
      (is (nil? bibliography-id))))
  (testing "resolve-document re-threads both keys unchanged from
            resolve-citations, so a caller of the full pipeline does not
            need to call resolve-citations a second time itself"
    (let [doc {:meta {} :blocks [(para (cite (cite-item "smith2020" :normal)))]}
          {:keys [ordered-keys bibliography-id]}
          (resolver/resolve-document doc {:bibliography bibliography})]
      (is (= ["smith2020"] ordered-keys))
      (is (= "sec:bibliography" bibliography-id)))))

;; Document-wide derived structures (`derive-toc`/`derive-list-of-figures`/
;; `derive-list-of-tables`/`derive-navigation`, TASK-14)

(defn- heading
  "A single-`:str`-Inline heading, e.g. `(heading \"Intro\")` => `[{:t :str
  :text \"Intro\"}]`, for TOC/navigation tests that need to assert `:heading`
  is actually preserved."
  [text]
  [{:t :str :text text}])

(defn- section-h
  "Like `section`, but with a real `heading` instead of the shared `section`
  fixture's fixed `[]`."
  [id heading-text blocks]
  {:t :section :level 1 :heading (heading heading-text) :blocks blocks :attr (id-attr id)})

(deftest derive-toc-nests-like-resolved-section-tree-test
  (testing "AC #1: the derived TOC nests exactly like the resolved Section
            tree, and every entry carries the resolved number-document
            numbering for its own section -- the same fixture and expected
            numbers as resolver-test's own
            hierarchical-section-numbering-test"
    (let [doc {:meta {}
               :blocks [(section-h "sec:one" "One" [])
                        (section-h "sec:two" "Two"
                                   [(section-h "sec:two-a" "Two A" [])
                                    (section-h "sec:two-b" "Two B"
                                               [(section-h "sec:two-b-i" "Two B i" [])])])]}
          toc (resolver/derive-toc doc)]
      (is (= [{:id "sec:one" :number "1" :text "Section 1" :level 1
               :heading (heading "One") :children []}
              {:id "sec:two" :number "2" :text "Section 2" :level 1
               :heading (heading "Two")
               :children [{:id "sec:two-a" :number "2.1" :text "Section 2.1" :level 1
                           :heading (heading "Two A") :children []}
                          {:id "sec:two-b" :number "2.2" :text "Section 2.2" :level 1
                           :heading (heading "Two B")
                           :children [{:id "sec:two-b-i" :number "2.2.1" :text "Section 2.2.1"
                                       :level 1 :heading (heading "Two B i") :children []}]}]}]
             toc))))
  (testing "an unlabeled Section still has its own TOC entry (nested
            correctly), simply with no computed number/text"
    (let [doc {:meta {} :blocks [(section-h nil "Untitled" [(section-h "sec:child" "Child" [])])]}
          [entry] (resolver/derive-toc doc)]
      (is (nil? (:id entry)))
      (is (nil? (:number entry)))
      (is (nil? (:text entry)))
      (is (= [{:id "sec:child" :number "1" :text "Section 1" :level 1
               :heading (heading "Child") :children []}]
             (:children entry)))))
  (testing "a Section labeled with a different kind's id prefix (e.g. a
            `thm:`-prefixed Section, TASK-11 review finding #1) still nests
            correctly in the TOC -- unlike number-document's own :section-
            path extension, which flattens this case for numbering"
    (let [doc {:meta {} :blocks [(section-h "thm:oops" "Oops" [(section-h "sec:child" "Child" [])])]}
          labels (resolver/number-document doc)
          [entry] (resolver/derive-toc doc)]
      (testing "number-document itself flattens sec:child to top-level \"1\""
        (is (= "1" (:number (get labels "sec:child")))))
      (testing "but the TOC still nests sec:child one level under thm:oops"
        (is (= "Theorem 1" (:text entry)))
        (is (= [{:id "sec:child" :number "1" :text "Section 1" :level 1
                 :heading (heading "Child") :children []}]
               (:children entry))))))
  (testing "the 2-arg arity accepts a caller-supplied labels table instead
            of always recomputing one via number-document"
    (let [doc {:meta {} :blocks [(section-h "sec:one" "One" [])]}
          labels {"sec:one" {:kind :sec :path [42] :number "42" :word "Section" :text "Section 42"}}
          [entry] (resolver/derive-toc doc labels)]
      (is (= "Section 42" (:text entry))))))

(deftest derive-list-of-figures-and-tables-test
  (testing "AC #2: every numbered Figure/Table is included with its caption
            and number, independent of section numbering -- the same
            fixture as resolver-test's own
            independent-per-kind-counters-test"
    (let [doc {:meta {}
               :blocks [(section "sec:intro" [(theorem "thm:a")])
                        (section "sec:body"
                                 [(figure "fig:tree")
                                  (table "tbl:yields")
                                  (section "sec:sub"
                                           [(math-block "eq:one")
                                            (math-block "eq:two")])
                                  (theorem "thm:b")])]}
          lof (resolver/derive-list-of-figures doc)
          lot (resolver/derive-list-of-tables doc)]
      (is (= [{:kind :fig :path [2 1] :number "2.1" :word "Figure" :text "Figure 2.1"
               :id "fig:tree" :caption []}]
             lof))
      (is (= [{:kind :tbl :path [2 1] :number "2.1" :word "Table" :text "Table 2.1"
               :id "tbl:yields" :caption []}]
             lot))
      (testing "eq/thm entries are excluded from both lists -- they are not
                :figure/:table Blocks at all"
        (is (not-any? #(#{:eq :thm} (:kind %)) (concat lof lot))))))
  (testing "a :table Block mislabeled with a `fig:`-prefixed id (or the
            reverse: a :figure Block mislabeled `tbl:`/`thm:`) is excluded
            from both lists -- the node's `:t` alone is not enough, since
            id-prefix-derived kind (TASK-11) and AST node type are two
            independent things (TASK-14 review: without this check, a
            mislabeled :table showed up in the list of tables wearing
            Figure numbering/wording)"
    (let [doc {:meta {}
               :blocks [(table "fig:oops")
                        (figure "thm:oops")]}]
      (is (empty? (resolver/derive-list-of-figures doc)))
      (is (empty? (resolver/derive-list-of-tables doc)))))
  (testing "a Figure/Table's own caption Inlines are preserved verbatim,
            not flattened to a string"
    (let [caption [{:t :str :text "A "} {:t :emph :inlines [{:t :str :text "tree"}]}]
          fig (assoc (figure "fig:tree") :caption caption)
          doc {:meta {} :blocks [fig]}
          [entry] (resolver/derive-list-of-figures doc)]
      (is (= caption (:caption entry)))))
  (testing "an unlabeled Figure/Table (not a numbering target, TASK-11 AC
            #3/TASK-8 AC #3's precedent) is excluded from both lists"
    (let [doc {:meta {}
               :blocks [{:t :figure :content {:t :code-block :text "x" :attr attr}
                         :caption [] :attr attr}
                        {:t :table :head {:cells []} :rows [] :caption [] :colspec [] :attr attr}]}]
      (is (empty? (resolver/derive-list-of-figures doc)))
      (is (empty? (resolver/derive-list-of-tables doc)))))
  (testing "a labeled Figure nested inside a footnote is still found, in
            document order, mirroring resolver-test's own
            footnote-nested-target-test"
    (let [doc {:meta {}
               :blocks [(para (cross-ref "unused"))
                        {:t :para :inlines [{:t :note :blocks [(figure "fig:tree")]}]}]}
          [entry] (resolver/derive-list-of-figures doc)]
      (is (= "fig:tree" (:id entry)))
      (is (= "1" (:number entry)))))
  (testing "the 2-arg arity accepts a caller-supplied labels table instead
            of always recomputing one via number-document"
    (let [doc {:meta {} :blocks [(figure "fig:tree")]}
          labels {"fig:tree" {:kind :fig :path [42] :number "42" :word "Figure" :text "Figure 42"}}
          [entry] (resolver/derive-list-of-figures doc labels)]
      (is (= "Figure 42" (:text entry))))))

(deftest derive-navigation-matches-pre-order-walk-test
  (testing "AC #3: every section's next/previous matches a pre-order walk
            of the resolved Section tree -- same fixture/numbers as
            resolver-test's own hierarchical-section-numbering-test"
    (let [doc {:meta {}
               :blocks [(section-h "sec:one" "One" [])
                        (section-h "sec:two" "Two"
                                   [(section-h "sec:two-a" "Two A" [])
                                    (section-h "sec:two-b" "Two B"
                                               [(section-h "sec:two-b-i" "Two B i" [])])])]}
          nav (resolver/derive-navigation doc)
          ids (map :id nav)]
      (is (= ["sec:one" "sec:two" "sec:two-a" "sec:two-b" "sec:two-b-i"] ids))
      (testing "the first entry has no previous, the last has no next"
        (is (nil? (:previous (first nav))))
        (is (nil? (:next (last nav)))))
      (testing "every other entry's :previous/:next point at its immediate
                pre-order neighbor's own id"
        (is (= ["sec:one" "sec:two" "sec:two-a" "sec:two-b"]
               (map (comp :id :previous) (rest nav))))
        (is (= ["sec:two" "sec:two-a" "sec:two-b" "sec:two-b-i"]
               (map (comp :id :next) (butlast nav)))))
      (testing "a neighbor's own embedded summary carries its number/text/
                heading too, not just its id"
        (is (= {:id "sec:two" :number "2" :text "Section 2" :level 1 :heading (heading "Two")}
               (:next (first nav)))))))
  (testing "an unlabeled Section still occupies its own place in the
            pre-order walk -- its neighbors' next/previous point at it
            (with a nil number/text), it is not skipped over"
    (let [doc {:meta {}
               :blocks [(section-h "sec:one" "One" [])
                        (section-h nil "Untitled" [])
                        (section-h "sec:two" "Two" [])]}
          nav (resolver/derive-navigation doc)]
      (is (= [nil "sec:one" nil] (map (comp :id :previous) nav)))
      (is (= ["sec:one" nil "sec:two"] (map :id nav)))
      (is (= [nil "sec:two" nil] (map (comp :id :next) nav)))))
  (testing "a Section labeled with a different kind's id prefix (e.g. a
            `thm:`-prefixed Section, TASK-11 review finding #1) still nests
            correctly in the navigation pre-order walk -- contrasting
            number-document's own :section-path extension, which flattens
            this same fixture's numbering for sec:child"
    (let [doc {:meta {}
               :blocks [(section-h "thm:oops" "Oops" [(section-h "sec:child" "Child" [])])]}
          labels (resolver/number-document doc)
          nav (resolver/derive-navigation doc)]
      (testing "number-document itself flattens sec:child to top-level \"1\""
        (is (= "1" (:number (get labels "sec:child")))))
      (testing "but navigation still walks thm:oops then sec:child in pre-order"
        (is (= ["thm:oops" "sec:child"] (map :id nav)))
        (is (= "sec:child" (:id (:next (first nav)))))
        (is (= "thm:oops" (:id (:previous (second nav)))))
        (is (nil? (:previous (first nav))))
        (is (nil? (:next (second nav)))))))
  (testing "next/previous are never read from any authored field -- a
            Section carrying its own hand-authored, non-schema :next/
            :previous keys (malli :map schemas are open) is still
            computed purely from AST structure/position, ignoring them"
    (let [bogus-next {:id "should-not-appear-next"}
          bogus-previous {:id "should-not-appear-previous"}
          doc {:meta {}
               :blocks [(assoc (section-h "sec:one" "One" []) :next bogus-next)
                        (assoc (section-h "sec:two" "Two" []) :previous bogus-previous)]}
          nav (resolver/derive-navigation doc)]
      (is (= [nil "sec:one"] (map (comp :id :previous) nav)))
      (is (= ["sec:two" nil] (map (comp :id :next) nav)))))
  (testing "the 2-arg arity accepts a caller-supplied labels table instead
            of always recomputing one via number-document"
    (let [doc {:meta {} :blocks [(section-h "sec:one" "One" [])]}
          labels {"sec:one" {:kind :sec :path [42] :number "42" :word "Section" :text "Section 42"}}
          [entry] (resolver/derive-navigation doc labels)]
      (is (= "Section 42" (:text entry))))))

;; Structural diagnostics pass (`structural-diagnostics`/`resolve-document`, TASK-15)

(deftest duplicate-id-diagnostic-names-both-locations-test
  (testing "AC #1: two Blocks sharing the same id produce a duplicate-id
            diagnostic naming both locations"
    (let [doc {:meta {} :blocks [(figure "fig:tree") (section "fig:tree" [])]}
          diagnostics (resolver/structural-diagnostics doc)]
      (is (= [{:type :duplicate-id
               :id "fig:tree"
               :locations [{:node-type :figure :path [0]}
                           {:node-type :section :path [1]}]
               :message "duplicate id \"fig:tree\": used by 2 nodes (figure, section)"}]
             (filterv #(= :duplicate-id (:type %)) diagnostics)))
      ;; This fixture's second node is a :section wearing a fig: id, so
      ;; TASK-36's kind-role check legitimately fires on it too. Filtered
      ;; rather than removed: the duplicate-id assertion is what this test
      ;; is for, and the pairing is itself realistic.
      (is (= [:duplicate-id :kind-role-mismatch] (map :type diagnostics)))))
  (testing "a collision nested at different structural depths still names
            both locations correctly, via their own block-children path"
    (let [doc {:meta {}
               :blocks [(section "sec:outer" [(figure "fig:dup")])
                        (table "fig:dup")]}
          [diagnostic] (resolver/structural-diagnostics doc)]
      (is (= {:node-type :figure :path [0 0]} (first (:locations diagnostic))))
      (is (= {:node-type :table :path [1]} (second (:locations diagnostic))))))
  (testing "a three-way collision names all three locations, not just the
            first two"
    (let [doc {:meta {}
               :blocks [(figure "fig:dup") (table "fig:dup") (math-block "fig:dup")]}
          [diagnostic] (resolver/structural-diagnostics doc)]
      (is (= 3 (count (:locations diagnostic))))
      (is (= [:figure :table :math-block] (map :node-type (:locations diagnostic))))))
  (testing "distinct ids produce no duplicate-id diagnostic at all"
    (let [doc {:meta {} :blocks [(figure "fig:a") (figure "fig:b")]}]
      (is (empty? (resolver/structural-diagnostics doc)))))
  (testing "an id shared by a Block nested inside another Directive's own
            :blocks is still found (structural walk, not just top-level)"
    (let [doc {:meta {}
               :blocks [(figure "fig:dup")
                        {:t :directive :name "note" :blocks [(table "fig:dup")] :attr attr}]}
          [diagnostic] (resolver/structural-diagnostics doc)]
      (is (= :duplicate-id (:type diagnostic)))
      (is (= [{:node-type :figure :path [0]} {:node-type :table :path [1 0]}]
             (:locations diagnostic)))))
  (testing "review fix (TASK-15): an Inline-level id is in the SAME id
            namespace as a Block-level one -- a Link/Image/Span's own
            Attr.id is NOT out of scope, per SPEC.md sec4.1's
            Attr definition (`id?: string; // unique document-wide
            anchor`), which draws no Block-only distinction"
    (testing "two Spans sharing the same id produce a duplicate-id
              diagnostic naming both Inline locations"
      (let [span-a {:t :span :inlines [] :attr (id-attr "shared")}
            span-b {:t :span :inlines [] :attr (id-attr "shared")}
            doc {:meta {} :blocks [(para span-a span-b)]}
            [diagnostic] (resolver/structural-diagnostics doc)]
        (is (= :duplicate-id (:type diagnostic)))
        (is (= [{:node-type :span :path [0 :inlines 0]}
                {:node-type :span :path [0 :inlines 1]}]
               (:locations diagnostic)))))
    (testing "a Link and an Image sharing the same id also collide, across
              two different Inline variants"
      (let [link {:t :link :target "https://a" :inlines [] :attr (id-attr "shared")}
            image {:t :image :src "a.png" :alt "" :attr (id-attr "shared")}
            doc {:meta {} :blocks [(para link) (para image)]}
            [diagnostic] (resolver/structural-diagnostics doc)]
        (is (= :duplicate-id (:type diagnostic)))
        (is (= [{:node-type :link :path [0 :inlines 0]}
                {:node-type :image :path [1 :inlines 0]}]
               (:locations diagnostic)))))
    (testing "a Block and an Inline sharing the same id collide too -- a
              real Section and an Image, e.g., would render as two
              elements with a duplicate id=\"...\" in any future HTML
              emitter"
      (let [image {:t :image :src "a.png" :alt "" :attr (id-attr "shared")}
            doc {:meta {} :blocks [(section "shared" []) (para image)]}
            [diagnostic] (resolver/structural-diagnostics doc)]
        (is (= :duplicate-id (:type diagnostic)))
        (is (= [{:node-type :section :path [0]}
                {:node-type :image :path [1 :inlines 0]}]
               (:locations diagnostic)))))))

(deftest unknown-directive-diagnostic-test
  (testing "AC #2: a Directive whose name is not in the extension registry
            produces an unknown-directive diagnostic"
    (let [doc {:meta {} :blocks [(theorem "thm:a")]}
          diagnostics (resolver/structural-diagnostics doc)]
      (is (= [{:type :unknown-directive
               :name "theorem"
               :id "thm:a"
               :message "unknown directive \"theorem\": not registered in the extension registry"}]
             diagnostics))))
  (testing "a name present in the supplied directive registry produces no
            diagnostic"
    (let [doc {:meta {} :blocks [(theorem "thm:a")]}]
      (is (empty? (resolver/structural-diagnostics doc #{"theorem"})))))
  (testing "the default registry is empty -- every Directive is unknown
            until a caller supplies a real one"
    (let [doc {:meta {} :blocks [{:t :directive :name "algorithm" :blocks [] :attr attr}]}]
      (is (= 1 (count (resolver/structural-diagnostics doc))))))
  (testing "multiple unknown directives each produce their own diagnostic,
            not deduplicated by name"
    (let [doc {:meta {}
               :blocks [{:t :directive :name "mystery" :blocks [] :attr attr}
                        {:t :directive :name "mystery" :blocks [] :attr attr}
                        (theorem "thm:a")]}
          diagnostics (resolver/structural-diagnostics doc)]
      (is (= 3 (count diagnostics)))
      (is (= ["mystery" "mystery" "theorem"] (map :name diagnostics)))))
  (testing "a Directive nested inside another Directive's own :blocks is
            still found"
    (let [doc {:meta {}
               :blocks [{:t :directive :name "outer" :blocks [(theorem "thm:a")] :attr attr}]}
          diagnostics (resolver/structural-diagnostics doc #{"outer"})]
      (is (= [{:type :unknown-directive :name "theorem" :id "thm:a"
               :message "unknown directive \"theorem\": not registered in the extension registry"}]
             diagnostics))))
  (testing "a Directive nested inside another Directive's own :fallback is
            deliberately NOT walked, mirroring TASK-12's own documented
            :fallback scope limit"
    (let [doc {:meta {}
               :blocks [{:t :directive :name "known" :blocks [] :attr attr
                         :fallback {:kind :blocks :blocks [(theorem "thm:a")]}}]}]
      (is (empty? (resolver/structural-diagnostics doc #{"known"}))))))

(deftest resolve-document-combines-all-diagnostics-test
  (testing "AC #3: resolve-document returns one combined diagnostics
            collection -- a dangling CrossRef, a dangling citation, a
            duplicate id, and an unknown directive all show up together,
            in step order, alongside the fully resolved document"
    (let [doc {:meta {}
               :blocks [(figure "fig:tree")
                        (section "fig:tree" [])
                        (para (cross-ref "fig:missing")
                              (cite (cite-item "missing2000" :normal)))
                        (theorem "thm:a")]}
          {:keys [document diagnostics]} (resolver/resolve-document doc)]
      ;; Citation warnings lead the cross-reference ones since TASK-64,
      ;; which runs citations first so a reference to the generated
      ;; bibliography section can resolve at all. "Step order" is still
      ;; the claim; the steps changed order.
      (is (= [:dangling-citation :dangling-cross-ref :duplicate-id :unknown-directive
              :kind-role-mismatch]
             (map :type diagnostics))
          "TASK-36 adds kind-role-mismatch to the combined list: this fixture's
           second node is a :section wearing this document's fig: id")
      (testing "the document itself is genuinely resolved, not just
                validated -- the CrossRef/Cite are annotated in place"
        (let [[cross-ref-node] (->> document
                                    (tree-seq coll? #(cond (map? %) (vals %) (coll? %) (seq %) :else nil))
                                    (filter #(and (map? %) (= :cross-ref (:t %)))))
              [cite-node] (->> document
                               (tree-seq coll? #(cond (map? %) (vals %) (coll? %) (seq %) :else nil))
                               (filter #(and (map? %) (= :cite (:t %)))))]
          (is (= "??" (:text cross-ref-node)))
          (is (some? (:text cite-node)))))))
  (testing "a document with no diagnosable issues returns an empty
            diagnostics vector, and a citation still generates its
            bibliography section"
    (let [doc {:meta {} :blocks [(para (cite (cite-item "smith2020" :normal)))]}
          {:keys [document diagnostics]} (resolver/resolve-document doc {:bibliography bibliography})]
      (is (empty? diagnostics))
      (is (some? (bibliography-section {:document document})))))
  (testing "duplicate-id checking runs over the FINAL resolved document,
            so a generated bibliography section's own (already-
            disambiguated) id never spuriously collides"
    (let [doc {:meta {}
               :blocks [(section "sec:bibliography" [])
                        (para (cite (cite-item "smith2020" :normal)))]}
          {:keys [diagnostics]} (resolver/resolve-document
                                 doc {:bibliography bibliography})]
      (is (not-any? #(= :duplicate-id (:type %)) diagnostics))
      (is (some #(= :bibliography-id-collision (:type %)) diagnostics))))
  (testing "the opts arity threads a custom lexicon/bibliography/styles/
            directive-registry through the whole pipeline"
    (let [lexicon (merge resolver/default-lexicon
                         {:alg {:counter :global :words {"en" {:singular "Algorithm"}}}})
          doc {:meta {}
               :blocks [{:t :directive :name "algorithm" :blocks [] :attr (id-attr "alg:sort")}
                        (para (cross-ref "alg:sort") (cite (cite-item "smith2020" :normal)))]}
          {:keys [document diagnostics]}
          (resolver/resolve-document doc {:lexicon lexicon
                                          :bibliography bibliography
                                          :citation-styles resolver/default-citation-styles
                                          :directive-registry #{"algorithm"}})
          [resolved-ref] (->> document
                              (tree-seq coll? #(cond (map? %) (vals %) (coll? %) (seq %) :else nil))
                              (filter #(and (map? %) (= :cross-ref (:t %)))))]
      (is (empty? diagnostics))
      (is (= "Algorithm 1" (:text resolved-ref)))))
  (testing "resolve-document's output still validates against ast/valid?
            Document"
    (let [doc {:meta {}
               :blocks [(figure "fig:tree")
                        (para (cross-ref "fig:tree") (cite (cite-item "smith2020" :normal)))]}
          {:keys [document]} (resolver/resolve-document doc {:bibliography bibliography})]
      (is (ast/valid? ast/Document document)))))

;; id-prefix kind vs node role diagnostics (TASK-36)

(deftest kind-role-mismatch-diagnostic-test
  (testing "AC #1: a :section labeled with a non-sec-prefixed id whose
            kind is not section-scoped is reported, and the message names
            the silent consequence -- number-block extends :section-path
            only for a section-scoped kind, so this section contributes
            no level to its own children's numbers"
    (let [doc {:meta {} :blocks [(section "thm:oops" [(figure "fig:inner")])]}
          diagnostics (resolver/structural-diagnostics doc)
          [mismatch skipped] diagnostics]
      (is (= [:kind-role-mismatch :unnumbered-section-id] (map :type diagnostics))
          "two independent problems -- wrong role, and a level dropped from its children")
      (is (= {:id "thm:oops" :kind :thm :node-type :section :expected #{:directive}}
             (select-keys mismatch [:id :kind :node-type :expected])))
      (is (re-find #"children's numbers skip a level" (:message skipped))))
    (testing "and the consequence the second diagnostic describes is
              real: the nested figure numbers as 1, not 1.1"
      (let [doc {:meta {} :blocks [(section "thm:oops" [(figure "fig:inner")])]}]
        (is (= "1" (get-in (resolver/number-document doc) ["fig:inner" :number]))))))
  (testing "AC #2: a :figure/:table/:math-block/Directive labeled with an
            id prefix whose kind doesn't match its own role is reported"
    (let [doc {:meta {} :blocks [(table "fig:mislabeled")
                                 (figure "tbl:mislabeled")
                                 (math-block "thm:mislabeled")
                                 (theorem "eq:mislabeled")]}
          ;; The registry keeps unknown-directive out of the way, so this
          ;; assertion is about the kind-role check alone.
          diagnostics (resolver/structural-diagnostics doc #{"theorem"})]
      (is (= [:kind-role-mismatch :kind-role-mismatch :kind-role-mismatch]
             (map :type diagnostics))
          "the eq:-labeled Directive is NOT a mismatch -- see the directive test below")
      (is (= ["fig:mislabeled" "tbl:mislabeled" "thm:mislabeled"] (map :id diagnostics)))
      (is (= [:table :figure :math-block] (map :node-type diagnostics)))))
  (testing "AC #2's Directive clause, positively: sec: is the one
            built-in kind a Directive cannot legitimately wear, since a
            Directive is never a Section"
    (let [doc {:meta {} :blocks [(theorem "sec:oops")]}
          [diagnostic] (resolver/structural-diagnostics doc #{"theorem"})]
      (is (= :kind-role-mismatch (:type diagnostic)))
      (is (= {:id "sec:oops" :kind :sec :node-type :directive :expected #{:section}}
             (select-keys diagnostic [:id :kind :node-type :expected])))))
  (testing "review finding #7, a recorded decision rather than an
            accident: the check covers EVERY attr-bearing Block, so a
            CodeBlock or List wearing a fig: id is reported too -- sec6
            makes any id-bearing node a target keyed by its prefix, so
            the same mislabeling has the same effect there"
    (let [doc {:meta {} :blocks [{:t :code-block :text "x" :attr (id-attr "fig:code")}
                                 {:t :list :ordered false :tight true :items []
                                  :attr (id-attr "tbl:list")}]}]
      (is (= [:kind-role-mismatch :kind-role-mismatch]
             (map :type (resolver/structural-diagnostics doc))))))
  (testing "a correctly-labeled node of each built-in kind produces no
            diagnostic at all, so this is not simply always-on"
    (let [doc {:meta {} :blocks [(section "sec:ok" [])
                                 (figure "fig:ok")
                                 (table "tbl:ok")
                                 (math-block "eq:ok")
                                 (theorem "thm:ok")]}]
      (is (empty? (resolver/structural-diagnostics doc #{"theorem"})))))
  (testing "a Directive is a legitimate home for fig:/tbl:/eq:/thm: --
            SPEC.md sec8.4's own worked example has a chart directive
            participating in Figure numbering through its own #fig: id --
            so none of those is reported on one"
    (let [directive (fn [id] {:t :directive :name "chart" :blocks [] :attr (id-attr id)})
          doc {:meta {} :blocks [(directive "fig:chart") (directive "tbl:grid")
                                 (directive "eq:formula") (directive "thm:main")]}]
      (is (empty? (resolver/structural-diagnostics doc #{"chart"})))))
  (testing "an id whose prefix names no lexicon kind has no declared role
            to disagree with, so it is not a mismatch -- on a Figure it
            just makes it a non-target, TASK-8 AC #3's own precedent"
    (let [doc {:meta {} :blocks [(figure "widget:x")]}]
      (is (empty? (resolver/structural-diagnostics doc)))))
  (testing "a custom lexicon kind is judged by the role IT declares
            (TASK-11 AC #4's extensibility, carried through to this
            check), and a custom kind declaring no :node-types is never
            role-checked at all"
    (let [lexicon (merge resolver/default-lexicon
                         {:alg {:counter :global :node-types #{:code-block}}
                          :note {:counter :global}})
          doc {:meta {} :blocks [(figure "alg:sort") (figure "note:x")]}
          diagnostics (resolver/structural-diagnostics doc #{} lexicon)]
      (is (= [:kind-role-mismatch] (map :type diagnostics)))
      (is (= "alg:sort" (:id (first diagnostics))))
      (is (= #{:code-block} (:expected (first diagnostics)))))))

(deftest unnumbered-section-id-diagnostic-test
  (testing "AC #1: a :section whose id has no recognized kind prefix at
            all is reported -- it is numbered as nothing and its children
            skip a level -- for both an unprefixed id and an unknown one"
    (let [doc {:meta {} :blocks [(section "intro" [])
                                 (section "part:one" [])]}
          diagnostics (resolver/structural-diagnostics doc)]
      (is (= [:unnumbered-section-id :unnumbered-section-id] (map :type diagnostics)))
      (is (= ["intro" "part:one"] (map :id diagnostics)))
      (is (= [nil :part] (map :kind diagnostics))
          "the diagnostic reports the prefix it did resolve, nil for an unprefixed id")))
  (testing "review finding #5: the two checks really do discriminate. A
            section wearing a fig: id has the WRONG ROLE but keeps its
            level -- fig is section-scoped, so number-block still extends
            :section-path -- and gets only the kind-role mismatch"
    (let [doc {:meta {} :blocks [(section "fig:one" [(figure "fig:inner")])]}]
      (is (= [:kind-role-mismatch] (map :type (resolver/structural-diagnostics doc))))
      (is (= "1.1" (get-in (resolver/number-document doc) ["fig:inner" :number]))
          "the nested figure really does keep its level here, unlike the thm: case")))
  (testing "review finding #1: a section whose kind IS recognized but is
            :global -- the shape every haselnuss.registry-registered kind
            takes, since register's own :kind fragment need not declare
            :node-types -- silently drops its level too, and used to go
            entirely undiagnosed"
    (let [lexicon (merge resolver/default-lexicon {:part {:counter :global}})
          doc {:meta {} :blocks [(section "part:one" [(figure "fig:inner")])]}]
      (is (= [:unnumbered-section-id]
             (map :type (resolver/structural-diagnostics doc #{} lexicon))))
      (is (= "1" (get-in (resolver/number-document doc lexicon) ["fig:inner" :number]))
          "the level really is dropped, so the diagnostic is describing a real effect")))
  (testing "an id-less section is NOT reported -- an unlabeled section is
            documented as intentionally transparent for nesting, which is
            a choice made by omitting the id"
    (let [doc {:meta {} :blocks [{:t :section :level 1 :heading [] :blocks [] :attr attr}]}]
      (is (empty? (resolver/structural-diagnostics doc)))))
  (testing "the same unrecognized prefix on a non-Section is not reported
            -- it only makes that node a non-target, with no effect on
            anything else's numbering"
    (let [doc {:meta {} :blocks [(figure "intro") (table "part:one")]}]
      (is (empty? (resolver/structural-diagnostics doc))))))

(deftest kind-role-diagnostics-surface-through-resolve-document-test
  (testing "AC #3: both new diagnostics reach resolve-document's own
            :diagnostics alongside the dangling-ref/cite/duplicate-id/
            unknown-directive ones, in pass order"
    (let [doc {:meta {}
               :blocks [(para (cross-ref "fig:missing"))
                        (table "fig:mislabeled")
                        (section "intro" [])]}
          {:keys [diagnostics]} (resolver/resolve-document doc)]
      (is (= [:dangling-cross-ref :kind-role-mismatch :unnumbered-section-id]
             (map :type diagnostics)))
      (is (= ["fig:mislabeled" "intro"]
             (map :id (remove #(= :dangling-cross-ref (:type %)) diagnostics))))))
  (testing "resolve-document passes its own :lexicon through, so a custom
            kind is judged by its own declared role rather than reported
            wholesale as unrecognized"
    ;; `frm` again, and for the same reason: with `alg` now built in,
    ;; both assertions below passed against the DEFAULT lexicon, so the
    ;; block no longer showed resolve-document threading its own
    ;; `:lexicon` through at all (found by review).
    (let [lexicon (merge resolver/default-lexicon
                         {:frm {:counter :global :node-types #{:directive}}})
          directive (fn [id] {:t :directive :name "form" :blocks [] :attr (id-attr id)})
          doc {:meta {} :blocks [(directive "frm:sort")]}]
      (is (empty? (:diagnostics (resolver/resolve-document doc {:lexicon lexicon
                                                                :directive-registry #{"form"}}))))
      (is (= [:kind-role-mismatch]
             (map :type (:diagnostics (resolver/resolve-document
                                       {:meta {} :blocks [(figure "frm:sort")]}
                                       {:lexicon lexicon})))))
      (testing "and without it the same document reports only that the
                directive is unregistered -- a kind the lexicon does not
                know is never role-checked at all -- which is what makes
                the assertions above about the option rather than about
                the built-ins"
        (is (= [:unknown-directive]
               (map :type (:diagnostics (resolver/resolve-document doc))))))))
  (testing "the generated bibliography Section does not trip either check
            -- structural-diagnostics runs over the fully resolved
            document, so its own sec: id must be conventional"
    (let [bib {"smith2020" (bib-entry "smith2020" "Smith" "Ann" 2020 "A Paper")}
          doc {:meta {} :blocks [(para (cite (cite-item "smith2020" :normal)))]}
          {:keys [diagnostics]} (resolver/resolve-document doc {:bibliography bib})]
      (is (empty? diagnostics)))))

;; id-prefix kind vs the directive's own mapped kind (TASK-48)

(def ^:private directive-kinds
  "A `directive-name -> kind` map in the shape `haselnuss.emit.latex/
  directive-lexicon-kinds` produces from a directive-environment table.
  Spelled out here rather than imported, so this namespace stays a
  resolver test and the injected-map contract is what is exercised."
  {"theorem" :thm "lemma" :lem "proof" :prf "admonition" :adm})

(defn- named-directive
  [directive-name id]
  {:t :directive :name directive-name :blocks [] :attr (id-attr id)})

(deftest directive-kind-mismatch-diagnostic-test
  (testing "AC #1: a directive whose id-prefix kind disagrees with the
            kind its own name maps to is reported, naming the directive,
            its id and both kinds"
    (let [doc {:meta {} :blocks [(named-directive "lemma" "thm:x")]}
          [diagnostic :as diagnostics] (resolver/structural-diagnostics
                                        doc #{"lemma"} resolver/default-lexicon directive-kinds)]
      (is (= [:directive-kind-mismatch] (map :type diagnostics)))
      (is (= {:name "lemma" :id "thm:x" :kind :thm :expected-kind :lem}
             (select-keys diagnostic [:name :id :kind :expected-kind])))
      (is (re-find #"lemma" (:message diagnostic)))
      (is (re-find #"thm:x" (:message diagnostic)))
      (is (re-find #":lem" (:message diagnostic)))
      (is (re-find #":thm" (:message diagnostic)))))
  (testing "AC #3: an agreeing prefix, no id at all, and a prefix naming
            no lexicon kind each produce nothing -- and neither does a
            directive whose name has no entry in the table, which
            declares no kind to disagree with"
    (let [doc {:meta {} :blocks [(named-directive "lemma" "lem:ok")
                                 {:t :directive :name "lemma" :blocks [] :attr attr}
                                 (named-directive "lemma" "widget:x")
                                 (named-directive "lemma" "plain")
                                 (named-directive "chart" "thm:x")]}]
      (is (empty? (resolver/structural-diagnostics
                   doc #{"lemma" "chart"} resolver/default-lexicon directive-kinds)))))
  (testing "the recorded decision in the other direction: a prefix naming
            a kind the LEXICON knows but no directive maps to is a
            genuine double-numbering and IS reported -- #fig: on a lemma
            numbers as a figure here and as a lemma in native LaTeX"
    (let [doc {:meta {} :blocks [(named-directive "lemma" "fig:x")]}
          diagnostics (resolver/structural-diagnostics
                       doc #{"lemma"} resolver/default-lexicon directive-kinds)]
      (is (= [:directive-kind-mismatch] (map :type diagnostics)))
      (is (= :fig (:kind (first diagnostics))))))
  (testing "the check reaches a directive nested anywhere, like every
            other structural diagnostic"
    (let [doc {:meta {} :blocks [(section "sec:one" [(named-directive "proof" "thm:x")])]}]
      (is (= [:directive-kind-mismatch]
             (map :type (resolver/structural-diagnostics
                         doc #{"proof"} resolver/default-lexicon directive-kinds))))))
  (testing "review finding #3: the overlap with kind-role-mismatch is
            deliberate, on the same terms unnumbered-section-diagnostics
            records for its own -- a {lemma #sec:x} wears a kind that
            labels Sections AND is numbered differently by the two
            targets, and neither problem is visible from the other"
    (let [doc {:meta {} :blocks [(named-directive "lemma" "sec:x")]}
          diagnostics (resolver/structural-diagnostics
                       doc #{"lemma"} resolver/default-lexicon directive-kinds)]
      (is (= [:kind-role-mismatch :directive-kind-mismatch] (map :type diagnostics)))
      (is (every? #(= "sec:x" (:id %)) diagnostics))))
  (testing "AC #4: a caller's own table is judged by ITS entries -- the
            same `{lemma #thm:x}` is silent under a table that maps lemma
            to thm, and an entry the built-ins never heard of is checked"
    (let [custom {"lemma" :thm "aside" :asd}
          lexicon (merge resolver/default-lexicon {:asd {:counter :global}})
          doc {:meta {} :blocks [(named-directive "lemma" "thm:x")
                                 (named-directive "aside" "thm:y")]}
          diagnostics (resolver/structural-diagnostics doc #{"lemma" "aside"} lexicon custom)]
      (is (= [:directive-kind-mismatch] (map :type diagnostics)))
      (is (= {:name "aside" :kind :thm :expected-kind :asd}
             (select-keys (first diagnostics) [:name :kind :expected-kind])))))
  (testing "no table supplied at all (structural-diagnostics' own
            3-arity, and every existing caller of it) reports nothing --
            the mapping lives in an emitter, so the resolver cannot
            invent one"
    (let [doc {:meta {} :blocks [(named-directive "lemma" "thm:x")]}]
      (is (empty? (resolver/structural-diagnostics doc #{"lemma"})))))
  (testing "AC #2: it surfaces through resolve-document as an ordinary
            warning, after kind-role-mismatch, leaving the resolved
            document intact -- nothing here aborts a build"
    (let [doc {:meta {} :blocks [(figure "tbl:mislabeled")
                                 (named-directive "lemma" "thm:x")]}
          {:keys [document diagnostics]}
          (resolver/resolve-document doc {:directive-registry #{"lemma"}
                                          :directive-kinds directive-kinds})]
      (is (= [:kind-role-mismatch :directive-kind-mismatch] (map :type diagnostics)))
      (is (ast/valid? ast/Document document))))
  (testing "and resolve-document defaults it to {}, so a caller that
            passes no table sees exactly what it saw before TASK-48"
    (let [doc {:meta {} :blocks [(named-directive "lemma" "thm:x")]}]
      (is (empty? (:diagnostics (resolver/resolve-document
                                 doc {:directive-registry #{"lemma"}})))))))

;; --- Include expansion (TASK-38) -------------------------------------------

(defn- include-block
  [src]
  {:t :include :src src})

(defn- stub-loader
  "A `:load` serving `docs`, a map of file NAME -> Document, and throwing
  the way a real loader does for anything else. Keyed by name rather than
  by path so a fixture reads as the document it is, while path resolution
  and cycle detection still run against real `java.io.File` semantics --
  `getCanonicalPath` does not require the file to exist."
  [docs]
  (fn [^java.io.File file]
    (or (get docs (.getName file))
        (throw (ex-info (str file " (No such file or directory)") {})))))

(deftest include-expansion-test
  (testing "AC #2: an :include is replaced by the referenced document's
            own Blocks, in its own position"
    (let [doc {:meta {} :blocks [(para {:t :str :text "before"})
                                 (include-block "ch.hdoc")
                                 (para {:t :str :text "after"})]}
          loader (stub-loader {"ch.hdoc" {:meta {} :blocks [(para {:t :str :text "included"})]}})
          {:keys [document warnings]} (resolver/expand-includes doc {:load loader :base-dir "/x"})]
      (is (empty? warnings))
      (is (= [(para {:t :str :text "before"})
              (para {:t :str :text "included"})
              (para {:t :str :text "after"})]
             (:blocks document)))
      (is (ast/valid? ast/Document document))))
  (testing "the included document's own Meta is dropped -- a Meta belongs
            to the document being built, and merging two titles or two
            bibliographies has no defensible answer"
    (let [doc {:meta {:title [{:t :str :text "Outer"}]} :blocks [(include-block "ch.hdoc")]}
          loader (stub-loader {"ch.hdoc" {:meta {:title [{:t :str :text "Inner"}]
                                                 :bibliography "other.json"}
                                          :blocks [(para {:t :str :text "x"})]}})
          {:keys [document]} (resolver/expand-includes doc {:load loader :base-dir "/x"})]
      (is (= {:title [{:t :str :text "Outer"}]} (:meta document)))))
  (testing "expansion recurses, and a nested :src resolves against the
            directory of the file it was WRITTEN in, not the top-level
            document's -- so a chapter's own sibling reference means its
            own sibling wherever it is included from"
    (let [seen (atom [])
          loader (fn [^java.io.File file]
                   (swap! seen conj (.getCanonicalPath file))
                   (case (.getName file)
                     "ch.hdoc" {:meta {} :blocks [(include-block "shared.hdoc")]}
                     "shared.hdoc" {:meta {} :blocks [(para {:t :str :text "deep"})]}))
          doc {:meta {} :blocks [(include-block "chapters/ch.hdoc")]}
          {:keys [document warnings]} (resolver/expand-includes doc {:load loader :base-dir "/x"})]
      (is (empty? warnings))
      (is (= [(para {:t :str :text "deep"})] (:blocks document)))
      (is (= ["/x/chapters/ch.hdoc" "/x/chapters/shared.hdoc"] @seen)
          "the nested include resolved against chapters/, not against /x")))
  (testing "an :include nested inside a Section or a Directive expands
            too -- the parser puts one wherever a block can go"
    (let [loader (stub-loader {"ch.hdoc" {:meta {} :blocks [(para {:t :str :text "in"})]}})
          doc {:meta {} :blocks [(section "sec:a" [{:t :directive :name "note" :attr attr
                                                    :blocks [(include-block "ch.hdoc")]}])]}
          {:keys [document]} (resolver/expand-includes doc {:load loader :base-dir "/x"})]
      (is (= [(para {:t :str :text "in"})]
             (get-in document [:blocks 0 :blocks 0 :blocks])))))
  (testing "AC #3: a cycle is reported as a diagnostic naming the chain
            rather than looping forever or overflowing the stack"
    (let [loader (stub-loader {"a.hdoc" {:meta {} :blocks [(para {:t :str :text "A"})
                                                           (include-block "b.hdoc")]}
                               "b.hdoc" {:meta {} :blocks [(para {:t :str :text "B"})
                                                           (include-block "a.hdoc")]}})
          doc {:meta {} :blocks [(include-block "a.hdoc")]}
          {:keys [document warnings]} (resolver/expand-includes doc {:load loader :base-dir "/x"})]
      (is (= 1 (count warnings)))
      (is (= :include-cycle (:type (first warnings))))
      (is (= ["/x/a.hdoc" "/x/b.hdoc" "/x/a.hdoc"] (:cycle (first warnings))))
      (testing "and everything outside the loop is still spliced, so one
                bad edge does not cost the whole build"
        (is (= [(para {:t :str :text "A"}) (para {:t :str :text "B"})] (:blocks document)))))
    (testing "a self-include is the degenerate cycle and is caught the same way"
      (let [loader (stub-loader {"a.hdoc" {:meta {} :blocks [(include-block "a.hdoc")]}})
            doc {:meta {} :blocks [(include-block "a.hdoc")]}
            {:keys [document warnings]} (resolver/expand-includes doc {:load loader :base-dir "/x"})]
        (is (= [:include-cycle] (mapv :type warnings)))
        (is (= [] (:blocks document)))))
    (testing "a DIAMOND is not a cycle: two files including one shared
              file expand it twice, since nothing revisits itself"
      (let [loader (stub-loader {"a.hdoc" {:meta {} :blocks [(include-block "s.hdoc")]}
                                 "b.hdoc" {:meta {} :blocks [(include-block "s.hdoc")]}
                                 "s.hdoc" {:meta {} :blocks [(para {:t :str :text "S"})]}})
            doc {:meta {} :blocks [(include-block "a.hdoc") (include-block "b.hdoc")]}
            {:keys [document warnings]} (resolver/expand-includes doc {:load loader :base-dir "/x"})]
        (is (empty? warnings))
        (is (= [(para {:t :str :text "S"}) (para {:t :str :text "S"})] (:blocks document))))))
  (testing "AC #4: an unreadable :src produces a diagnostic naming the
            file and the build continues, with the offending Include
            dropped rather than left for an emitter to choke on"
    (let [loader (stub-loader {})
          doc {:meta {} :blocks [(para {:t :str :text "kept"}) (include-block "gone.hdoc")]}
          {:keys [document warnings]} (resolver/expand-includes doc {:load loader :base-dir "/x"})]
      (is (= [:unreadable-include] (mapv :type warnings)))
      (is (= "gone.hdoc" (:src (first warnings))))
      (is (str/includes? (:message (first warnings)) "gone.hdoc"))
      (is (= [(para {:t :str :text "kept"})] (:blocks document)))
      (is (ast/valid? ast/Document document))))
  (testing "with no :load at all the pass is a no-op, which is what a
            caller with no filesystem to read needs -- an AST arriving
            through haselnuss.json's interchange format"
    (let [doc {:meta {} :blocks [(include-block "ch.hdoc")]}]
      (is (= {:document doc :warnings []} (resolver/expand-includes doc)))
      (is (= {:document doc :warnings []} (resolver/expand-includes doc {:base-dir "/x"})))))
  (testing "resolve-document runs expansion FIRST, so an included
            chapter's own figures and sections number, and resolve, as if
            they had been typed into the including file"
    (let [loader (stub-loader {"ch.hdoc" {:meta {} :blocks [(section "sec:m" [(figure "fig:tree")])]}})
          doc {:meta {} :blocks [(para (cross-ref "fig:tree"))
                                 (include-block "ch.hdoc")]}
          {:keys [document diagnostics labels]}
          (resolver/resolve-document doc {:includes {:load loader :base-dir "/x"}})]
      (is (empty? diagnostics))
      (is (= "Figure 1.1" (get-in labels ["fig:tree" :text])))
      (is (= "Figure 1.1" (get-in document [:blocks 0 :inlines 0 :text])))))
  (testing "an expansion diagnostic surfaces through resolve-document,
            ahead of the later passes' own -- it often explains them"
    (let [doc {:meta {} :blocks [(include-block "gone.hdoc")
                                 (para (cross-ref "fig:nope"))]}
          {:keys [diagnostics]} (resolver/resolve-document
                                 doc {:includes {:load (stub-loader {}) :base-dir "/x"}})]
      (is (= [:unreadable-include :dangling-cross-ref] (mapv :type diagnostics))))))

(deftest include-expansion-reaches-every-container-test
  (testing "every Block variant that can hold an :include actually has it
            expanded. Pinned as a set rather than left to inspection: a
            variant reachable by `block-children` but missed by
            `expand-blocks` is an :include that survives to an emitter,
            which is exactly the failure this pass exists to remove.
            Figure's `:content` is the one deliberate exception -- one
            Block slot cannot hold a splice of many"
    (let [loader (stub-loader {"ch.hdoc" {:meta {} :blocks [(para {:t :str :text "in"})]}})
          inc-block (include-block "ch.hdoc")
          expanded (para {:t :str :text "in"})
          containers
          {:section [{:t :section :level 1 :heading [] :blocks [inc-block] :attr attr}
                     [:blocks 0 :blocks]]
           :block-quote [{:t :block-quote :blocks [inc-block]} [:blocks 0 :blocks]]
           :directive [{:t :directive :name "n" :attr attr :blocks [inc-block]} [:blocks 0 :blocks]]
           :list [{:t :list :ordered false :items [[inc-block]] :attr attr} [:blocks 0 :items 0]]
           :table [{:t :table :head {:cells [{:blocks [inc-block] :attr attr}]} :rows []
                    :caption [] :colspec [] :attr attr}
                   [:blocks 0 :head :cells 0 :blocks]]
           ;; A footnote's Blocks. The parser cannot put an :include
           ;; there (flexmark parses a footnote definition), but
           ;; haselnuss.json can, and an emitter reached that way would
           ;; raise ::unresolved-include from inside a note.
           :para [(para {:t :note :blocks [inc-block]}) [:blocks 0 :inlines 0 :blocks]]}]
      (doseq [[variant [block path]] containers]
        (let [{:keys [document]} (resolver/expand-includes
                                  {:meta {} :blocks [block]}
                                  {:load loader :base-dir "/x"})]
          (is (= [expanded] (get-in document path)) (str variant))))))
  (testing "a Figure's single :content slot is documented as NOT expanded
            -- there is nowhere to put more than one Block -- so an
            :include there is left standing for an emitter to report"
    (let [figure-with-include {:t :figure :content (include-block "ch.hdoc") :caption [] :attr attr}
          {:keys [document]} (resolver/expand-includes
                              {:meta {} :blocks [figure-with-include]}
                              {:load (stub-loader {"ch.hdoc" {:meta {} :blocks []}})
                               :base-dir "/x"})]
      (is (= :include (get-in document [:blocks 0 :content :t]))))))

(deftest include-root-cycle-test
  (testing "a loop that comes back around to the ROOT document is a cycle
            like any other. Without :source-path the root is not on the
            stack, and a self-including document spliced its own body in
            before the guard could fire (found by review)"
    (let [root {:meta {} :blocks [(para {:t :str :text "Root"}) (include-block "doc.hdoc")]}
          loader (fn [_] root)
          {:keys [document warnings]} (resolver/expand-includes
                                       root {:load loader
                                             :base-dir "/x"
                                             :source-path "/x/doc.hdoc"})]
      (is (= [:include-cycle] (mapv :type warnings)))
      (is (= ["/x/doc.hdoc" "/x/doc.hdoc"] (:cycle (first warnings))))
      (is (= [(para {:t :str :text "Root"})] (:blocks document))
          "the root's own body appears exactly once")))
  (testing "and an indirect loop back to the root is caught at the edge
            that closes it, not one expansion later"
    (let [root {:meta {} :blocks [(include-block "a.hdoc")]}
          loader (stub-loader {"a.hdoc" {:meta {} :blocks [(para {:t :str :text "A"})
                                                           (include-block "doc.hdoc")]}})
          {:keys [document warnings]} (resolver/expand-includes
                                       root {:load loader
                                             :base-dir "/x"
                                             :source-path "/x/doc.hdoc"})]
      (is (= [:include-cycle] (mapv :type warnings)))
      (is (= [(para {:t :str :text "A"})] (:blocks document))))))

(deftest include-failure-modes-test
  (testing "an included file that itself fails to PARSE is a diagnostic
            like any other unreadable src, not a build abort: :load
            throwing is :load throwing, whatever the reason"
    (let [loader (fn [^java.io.File file]
                   (throw (ex-info (str "unsupported inline construct in " (.getName file)) {})))
          doc {:meta {} :blocks [(para {:t :str :text "kept"}) (include-block "broken.hdoc")]}
          {:keys [document warnings]} (resolver/expand-includes doc {:load loader :base-dir "/x"})]
      (is (= [:unreadable-include] (mapv :type warnings)))
      (is (str/includes? (:message (first warnings)) "unsupported inline construct"))
      (is (= [(para {:t :str :text "kept"})] (:blocks document)))))
  (testing "ids colliding across files are diagnosed exactly as they
            would be within one file -- which is the point of expanding
            before every other pass runs"
    (let [loader (stub-loader {"ch.hdoc" {:meta {} :blocks [(section "sec:a" [])]}})
          doc {:meta {} :blocks [(section "sec:a" []) (include-block "ch.hdoc")]}
          {:keys [diagnostics]} (resolver/resolve-document
                                 doc {:includes {:load loader :base-dir "/x"}})]
      (is (some #(= :duplicate-id (:type %)) diagnostics))))
  (testing "an absolute :src is used as written rather than being
            resolved against the including file's directory"
    (let [seen (atom nil)
          loader (fn [^java.io.File file] (reset! seen (.getPath file)) {:meta {} :blocks []})
          doc {:meta {} :blocks [(include-block "/elsewhere/ch.hdoc")]}]
      (resolver/expand-includes doc {:load loader :base-dir "/x"})
      (is (= "/elsewhere/ch.hdoc" @seen)))))

;; ---------------------------------------------------------------------
;; TASK-53: chapters as the top-level division.
;; ---------------------------------------------------------------------

(defn- chaptered
  "`blocks` as a document that opted into chapters, and the same blocks as
  one that did not -- the pair every test below compares, since AC #4 is
  literally \"a document that does not opt in numbers exactly as today\"."
  [blocks]
  {:chapter {:meta {:top-level-division :chapter} :blocks blocks}
   :plain {:meta {} :blocks blocks}})

(def ^:private chaptered-blocks
  "Two chapters, each with two sections, each section holding one figure
  -- the smallest shape in which chapter-scoped and section-scoped
  numbering give DIFFERENT answers, which is the only shape that can
  fail. Section-path composition would number the last figure 2.1.1;
  chapter composition numbers it 2.1, and a second figure in the same
  chapter's other section 2.2 rather than restarting."
  [{:t :section :level 1 :heading [] :attr (id-attr "ch:bg") :blocks
    [{:t :section :level 2 :heading [] :attr (id-attr "sec:models")
      :blocks [(figure "fig:one") (math-block "eq:mass")]}
     {:t :section :level 2 :heading [] :attr (id-attr "sec:more")
      :blocks [(figure "fig:two") (table "tbl:sizes")]}]}
   {:t :section :level 1 :heading [] :attr (id-attr "ch:res") :blocks
    [{:t :section :level 2 :heading [] :attr (id-attr "sec:found")
      :blocks [(figure "fig:three")]}]}])

(deftest chapter-numbering-test
  (let [{:keys [chapter plain]} (chaptered chaptered-blocks)
        numbers (fn [doc] (into (sorted-map)
                                (map (fn [[id entry]] [id (:number entry)]))
                                (resolver/number-document doc)))]
    (testing "AC #2: a ch: id numbers as a chapter, and a section inside
              one composes onto its number"
      (is (= {"ch:bg" "1" "ch:res" "2" "sec:models" "1.1" "sec:more" "1.2" "sec:found" "2.1"}
             (select-keys (numbers chapter)
                          ["ch:bg" "ch:res" "sec:models" "sec:more" "sec:found"]))))
    (testing "AC #3: a section-scoped figure/table/equation composes with
              the CHAPTER, not with the whole section path, and counts
              per chapter -- so the second figure of chapter 1 is 1.2
              even though it sits in a different section, and chapter 2's
              first figure restarts at 2.1. That is exactly what report/
              book do with the same document (\\thefigure =
              \\thechapter.\\arabic{figure}), and disagreeing with it
              would print a number appearing nowhere"
      (is (= {"fig:one" "1.1" "fig:two" "1.2" "fig:three" "2.1"
              "eq:mass" "1.1" "tbl:sizes" "1.1"}
             (select-keys (numbers chapter)
                          ["fig:one" "fig:two" "fig:three" "eq:mass" "tbl:sizes"]))))
    (testing "AC #4: without the opt-in the same blocks number exactly as
              they do today -- composed against the full section path,
              restarting in each section"
      (is (= {"fig:one" "1.1.1" "fig:two" "1.2.1" "fig:three" "2.1.1"
              "eq:mass" "1.1.1" "tbl:sizes" "1.2.1"}
             (select-keys (numbers plain)
                          ["fig:one" "fig:two" "fig:three" "eq:mass" "tbl:sizes"])))
      (is (= {"ch:bg" "1" "ch:res" "2" "sec:models" "1.1" "sec:more" "1.2" "sec:found" "2.1"}
             (select-keys (numbers plain)
                          ["ch:bg" "ch:res" "sec:models" "sec:more" "sec:found"]))
          "and a ch: id still numbers as a section-scoped kind either way -- what
           the opt-in changes is what a FLOAT composes against, not what a heading is"))))

(deftest chapter-lexicon-words-test
  (testing "AC #2: the chapter kind carries both languages every built-in
            kind already does"
    ;; Two labeled chapters, and the SECOND one is what is asserted, so a
    ;; word-lookup that silently produced no number at all cannot pass by
    ;; matching a bare "1" the way a single-chapter fixture would.
    (let [text (fn [lang]
                 (:text (get (resolver/number-document
                              {:meta (cond-> {:top-level-division :chapter} lang (assoc :lang lang))
                               :blocks [{:t :section :level 1 :heading []
                                         :attr (id-attr "ch:intro") :blocks []}
                                        {:t :section :level 1 :heading []
                                         :attr (id-attr "ch:models") :blocks []}]})
                             "ch:models")))]
      (is (= "Chapter 2" (text nil)))
      (is (= "Chapter 2" (text "en")))
      (is (= "Capítulo 2" (text "pt-BR"))))))

(deftest chapter-cross-reference-test
  (testing "AC #2: @ch:models resolves to its chapter label, in both
            languages -- the reference text, not just the number table"
    (doseq [[lang expected] {"en" "Chapter 1" "pt-BR" "Capítulo 1"}]
      (let [doc {:meta {:top-level-division :chapter :lang lang}
                 :blocks [{:t :section :level 1 :heading [] :attr (id-attr "ch:models")
                           :blocks [(para {:t :cross-ref :label "ch:models"})]}]}
            {:keys [document warnings]} (resolver/resolve-cross-refs
                                         doc (resolver/number-document doc))]
        (is (empty? warnings))
        (is (= expected
               (-> document :blocks first :blocks first :inlines first :text)))))))

(deftest chapter-pre-chapter-float-test
  (testing "TASK-53 AC #3: a figure BEFORE the first chapter has no
            chapter to compose with, and numbers as a bare 1 -- which is
            exactly what report does (\\thefigure guards its chapter
            prefix on \\c@chapter > 0), confirmed against a real
            pdflatex. The chapter's own first figure then restarts at
            1.1, because truncating the path truncates the counter key
            with it"
    (let [fig (fn [id] {:t :figure :attr (id-attr id) :caption []
                        :content {:t :para :inlines []}})
          doc {:meta {:top-level-division :chapter}
               :blocks [(fig "fig:pre")
                        {:t :section :level 1 :heading [] :attr (id-attr "ch:one")
                         :blocks [(fig "fig:a") (fig "fig:b")]}]}]
      (is (= {"fig:pre" "1" "ch:one" "1" "fig:a" "1.1" "fig:b" "1.2"}
             (into {} (map (fn [[id entry]] [id (:number entry)]))
                   (resolver/number-document doc)))))))

(deftest division-kind-mismatch-test
  (let [mismatches (fn [doc]
                     (mapv (juxt :id :expected-kind)
                           (filter (comp #{:division-kind-mismatch} :type)
                                   (resolver/structural-diagnostics doc))))]
    (testing "TASK-53: a level-1 heading labeled #sec: in a CHAPTERED
              document is a silent cross-target drift -- it is emitted as
              \\chapter, so cleveref names a native-mode reference to it
              'Chapter 1' while HTML and computed mode both print
              'Section 1' from the id prefix (confirmed against a real
              pdflatex). The build says so, exactly as it already does
              for a directive whose id prefix names another kind"
      (is (= [["sec:intro" :ch] ["ch:oops" :sec]]
             (mismatches
              {:meta {:top-level-division :chapter}
               :blocks [{:t :section :level 1 :heading [] :attr (id-attr "sec:intro")
                         :blocks [{:t :section :level 2 :heading [] :attr (id-attr "ch:oops")
                                   :blocks []}
                                  {:t :section :level 2 :heading [] :attr (id-attr "sec:ok")
                                   :blocks []}]}]}))))
    (testing "and the mirror case: a #ch: id in a document that never
              opted into chapters, emitted as \\section and read back as
              Section"
      (is (= [["ch:x" :sec]]
             (mismatches {:meta {}
                          :blocks [{:t :section :level 1 :heading [] :attr (id-attr "ch:x")
                                    :blocks []}
                                   {:t :section :level 1 :heading [] :attr (id-attr "sec:y")
                                    :blocks []}]}))))
    (testing "a Section with no id, and one whose prefix names no
              division at all, are other passes' cases and are not
              reported here -- an unlabeled Section is intentionally
              transparent, and a non-division kind on a Section is what
              kind-role-diagnostics is for"
      (is (empty? (mismatches
                   {:meta {:top-level-division :chapter}
                    :blocks [{:t :section :level 1 :heading [] :attr attr :blocks []}
                             {:t :section :level 1 :heading [] :attr (id-attr "ch:fine")
                              :blocks [{:t :section :level 2 :heading []
                                        :attr (id-attr "thm:oops") :blocks []}]}]}))))))

(deftest chapter-structural-scope-test
  (let [fig (fn [id] {:t :figure :attr (id-attr id) :caption [] :content {:t :para :inlines []}})
        sec (fn [level id & blocks]
              {:t :section :level level :heading [] :attr (id-attr id) :blocks (vec blocks)})
        unlabeled (fn [level & blocks]
                    {:t :section :level level :heading [] :attr attr :blocks (vec blocks)})
        numbers (fn [doc] (into (sorted-map)
                                (map (fn [[id entry]] [id (:number entry)]))
                                (resolver/number-document doc)))]
    (testing "TASK-53, found by review: a labeled section OUTSIDE any
              chapter must not share a float counter with chapter one.
              Deriving the chapter from the label path could not tell the
              two apart -- both are [1] -- so every figure in chapter one
              was shifted by whatever sat before it. The whole map below
              is what a real pdflatex prints for the equivalent report
              document, checked side by side: Figure 1 / (1) / 0.1
              Preface, then 1.1 A, 1.2 B with Figures 1.1 and 1.2, then
              2.1 C with Figure 2.1"
      (is (= {"sec:pre" "0.1" "fig:pre" "1" "eq:pre" "1"
              "ch:one" "1" "sec:a" "1.1" "sec:b" "1.2" "fig:a" "1.1" "fig:b" "1.2"
              "ch:two" "2" "sec:c" "2.1" "fig:c" "2.1"}
             (numbers
              {:meta {:top-level-division :chapter}
               :blocks [(sec 2 "sec:pre" (fig "fig:pre")
                             {:t :math-block :tex "x" :attr (id-attr "eq:pre")})
                        (sec 1 "ch:one" (sec 2 "sec:a" (fig "fig:a"))
                             (sec 2 "sec:b" (fig "fig:b")))
                        (sec 1 "ch:two" (sec 2 "sec:c" (fig "fig:c")))]}))
          "a section before the first chapter sets as 0.1 while a float there sets as a
           bare 1 -- report's \\thesection carries the chapter prefix unconditionally,
           its \\thefigure guards it on chapter > 0, and two separate paths are what
           lets both hold at once"))
    (testing "TASK-53, found by review: chapters are counted
              STRUCTURALLY, so a document that labels only the chapters
              it cross-references still agrees with the PDF. With a
              labeled-only count these three sections numbered 1.1, 2.1,
              3.1 -- as though each unlabeled chapter were not there --
              where report prints 1.1, 1.2, 2.1"
      (is (= {"sec:a" "1.1" "sec:b" "1.2" "sec:c" "2.1"
              "fig:a" "1.1" "fig:b" "1.2" "fig:c" "2.1"}
             (numbers
              {:meta {:top-level-division :chapter}
               :blocks [(unlabeled 1 (sec 2 "sec:a" (fig "fig:a"))
                                   (sec 2 "sec:b" (fig "fig:b")))
                        (unlabeled 1 (sec 2 "sec:c" (fig "fig:c")))]}))))
    (testing "and a partly-labeled document numbers the labeled chapter
              by its structural position, not by how many chapters
              happened to carry an id"
      (is (= {"ch:third" "3" "fig:in-third" "3.1"}
             (numbers
              {:meta {:top-level-division :chapter}
               :blocks [(unlabeled 1) (unlabeled 1)
                        (sec 1 "ch:third" (fig "fig:in-third"))]}))))
    (testing "none of it reaches a document that did not opt in: the same
              blocks number against the whole section path, and an
              unlabeled section stays transparent"
      (is (= {"sec:a" "1" "fig:a" "1.1"}
             (numbers {:meta {}
                       :blocks [(unlabeled 1 (sec 2 "sec:a" (fig "fig:a")))]}))))))

;; Sublabels: a numbered node inside a numbered node of its own kind
;; (TASK-56)

(defn- figure-directive
  "A `figure`/`subfigure` directive shaped as the parser builds it, with
  `blocks` as its panels/content."
  [directive-name id blocks]
  {:t :directive :name directive-name :blocks (vec blocks) :attr (id-attr id)})

(def ^:private panel-names
  "The `sublabel-names` a real build passes (`haselnuss.emit.latex/
  sublabel-directive-names`), spelled out here so this namespace keeps
  knowing nothing about that emitter."
  #{"subfigure"})

(defn- panel-labels
  [document]
  (resolver/number-document document resolver/default-lexicon #{} panel-names))

(deftest sublabel-numbering-test
  (testing "TASK-56: a numbered node whose nearest numbered ancestor
            carries the SAME kind takes a letter within it -- the
            parent's own number with a, b, c appended and no separator,
            which is exactly what subcaption prints for a subfigure and
            what a native \\Cref to one resolves to"
    (let [doc {:meta {}
               :blocks [(figure-directive "figure" "fig:sens"
                                          [(figure-directive "subfigure" "fig:temp" [])
                                           (figure-directive "subfigure" "fig:pressure" [])
                                           (figure-directive "subfigure" "fig:flow" [])])
                        (figure "fig:tree")]}
          labels (panel-labels doc)]
      (is (= "1" (get-in labels ["fig:sens" :number])))
      (is (= ["1a" "1b" "1c"] (map #(get-in labels [% :number])
                                   ["fig:temp" "fig:pressure" "fig:flow"])))
      (is (= "Figure 1b" (get-in labels ["fig:pressure" :text]))
          "the reference to a panel still names the kind and the full number")
      (is (= "b" (get-in labels ["fig:pressure" :sublabel]))
          "and the letter alone is what an emitter prints on the panel itself")
      (is (= [1 "b"] (get-in labels ["fig:pressure" :path])))
      (is (= "2" (get-in labels ["fig:tree" :number]))
          "the panels took no number out of the figure sequence")))
  (testing "each parent letters its own panels from a, so two multi-panel
            figures do not continue one another's sequence"
    (let [doc {:meta {}
               :blocks [(figure-directive "figure" "fig:one"
                                          [(figure-directive "subfigure" "fig:one-a" [])
                                           (figure-directive "subfigure" "fig:one-b" [])])
                        (figure-directive "figure" "fig:two"
                                          [(figure-directive "subfigure" "fig:two-a" [])])]}
          labels (panel-labels doc)]
      (is (= ["1a" "1b" "2a"] (map #(get-in labels [% :number])
                                   ["fig:one-a" "fig:one-b" "fig:two-a"])))))
  (testing "a panel must be a DIRECT child of the figure it letters
            within: one buried in a wrapper is laid out by nothing, so
            haselnuss.emit.latex refuses it outright, and this pass has
            to agree with that rather than number a shape that cannot
            be emitted (found by review)"
    (let [doc {:meta {}
               :blocks [(figure-directive "figure" "fig:sens"
                                          [{:t :block-quote :attr attr
                                            :blocks [(figure-directive "subfigure" "fig:temp" [])]}])]}
          labels (panel-labels doc)]
      (is (= "2" (get-in labels ["fig:temp" :number])))
      (is (nil? (get-in labels ["fig:temp" :sublabel])))))
  (testing "a panel of a chaptered document's figure composes onto
            whatever number the parent already got -- the rule is
            'parent number plus letter', so chapter scoping needs no
            second mechanism"
    (let [doc {:meta {:top-level-division :chapter}
               :blocks [(section "ch:one" [(figure-directive
                                            "figure" "fig:sens"
                                            [(figure-directive "subfigure" "fig:temp" [])])])]}
          labels (panel-labels doc)]
      (is (= "1.1" (get-in labels ["fig:sens" :number])))
      (is (= "1.1a" (get-in labels ["fig:temp" :number])))))
  (testing "an ordinary node carries no :sublabel at all, so an emitter
            printing one is looking at a real panel and nothing else"
    (let [labels (panel-labels {:meta {} :blocks [(figure "fig:tree")]})]
      (is (nil? (get-in labels ["fig:tree" :sublabel])))))
  (testing "a DIFFERENT kind nested inside is numbered normally: the rule
            is same-kind nesting, not nesting"
    (let [doc {:meta {}
               :blocks [(figure-directive "figure" "fig:sens"
                                          [{:t :table :head {:cells []} :rows [] :caption []
                                            :colspec [] :attr (id-attr "tbl:inner")}])]}
          labels (panel-labels doc)]
      (is (= "1" (get-in labels ["tbl:inner" :number])))
      (is (nil? (get-in labels ["tbl:inner" :sublabel])))))
  (testing "and a Section never sublabels or is sublabelled, whichever
            side of the nesting it is on -- sections nest through the
            section path already, and a second mechanism on top of it
            would turn Section 1.1 into Section 1a"
    (let [doc {:meta {} :blocks [(section "sec:outer" [(section "sec:inner" [])])]}
          labels (panel-labels doc)]
      (is (= "1.1" (get-in labels ["sec:inner" :number])))
      (is (nil? (get-in labels ["sec:inner" :sublabel])))))
  (testing "and a same-kind nesting whose directive is NOT declared a
            panel is numbered exactly as it always was: a theorem inside
            a theorem is ordinary, legal, and numbered 1, 2 by every
            LaTeX class -- lettering the inner one 1a made this pass
            disagree with a native build about every theorem after it
            (found by review)"
    (let [doc {:meta {}
               :blocks [(figure-directive "theorem" "thm:outer"
                                          [(figure-directive "theorem" "thm:inner" [])])
                        (figure-directive "theorem" "thm:after" [])]}
          labels (panel-labels doc)]
      (is (= ["1" "2" "3"] (map #(get-in labels [% :number])
                                ["thm:outer" "thm:inner" "thm:after"])))
      (is (every? nil? (map #(get-in labels [% :sublabel])
                            ["thm:outer" "thm:inner" "thm:after"])))))
  (testing "and with no sublabel names declared at all -- this pass's own
            default -- nothing is ever a sublabel, so numbering is
            exactly what it was before the concept existed"
    (let [doc {:meta {}
               :blocks [(figure-directive "figure" "fig:sens"
                                          [(figure-directive "subfigure" "fig:temp" [])])]}
          labels (resolver/number-document doc)]
      (is (= "2" (get-in labels ["fig:temp" :number])))
      (is (nil? (get-in labels ["fig:temp" :sublabel]))))))

(deftest sublabel-letters-past-z-test
  (testing "letters run a..z and then aa, ab -- matching LaTeX's own
            \\alph for as long as \\alph works at all (it fails the build
            outright past 26, so there is no letter past that both
            targets could agree on anyway)"
    (let [panels (map #(figure-directive "subfigure" (str "fig:p" %) []) (range 28))
          doc {:meta {} :blocks [(figure-directive "figure" "fig:many" panels)]}
          labels (panel-labels doc)]
      (is (= "1a" (get-in labels ["fig:p0" :number])))
      (is (= "1z" (get-in labels ["fig:p25" :number])))
      (is (= "1aa" (get-in labels ["fig:p26" :number])))
      (is (= "1ab" (get-in labels ["fig:p27" :number]))))))

(deftest derived-lists-cover-float-directives-test
  (testing "TASK-59: a float authored as a DIRECTIVE is listed too --
            `{figure}` is the only spelling a multi-panel figure has, and
            native LaTeX's own .lof lists it like any other float, so a
            list built from :t alone would be missing entries the
            compiled PDF has. Its caption is an attribute rather than an
            Inline vector, and comes back as the one Inline it is"
    (let [doc {:meta {}
               :blocks [(figure-directive "figure" "fig:panels" [])
                        (figure "fig:tree")]}
          doc (assoc-in doc [:blocks 0 :attr :props] {"caption" "Sensitivity"})
          labels (panel-labels doc)
          lof (resolver/derive-list-of-figures doc labels #{"figure"})]
      (is (= ["fig:panels" "fig:tree"] (map :id lof)))
      (is (= [{:t :str :text "Sensitivity"}] (:caption (first lof))))
      (is (= "Figure 1" (:text (first lof))))))
  (testing "but only a directive the CALLER named a float: numbering is
            id-prefix-driven, so an admonition wearing a fig: id numbers
            as a figure -- and listing it would print a caption-less
            'Figure 1' in a list native LaTeX has no entry for (found by
            review)"
    (let [doc {:meta {}
               :blocks [(figure-directive "admonition" "fig:careful" [])
                        (figure-directive "theorem" "tbl:t" [])
                        (figure "fig:tree")]}
          labels (panel-labels doc)]
      (is (= ["fig:tree"]
             (map :id (resolver/derive-list-of-figures doc labels #{"figure"}))))
      (is (empty? (resolver/derive-list-of-tables doc labels #{"figure"})))))
  (testing "and with no float names at all -- this pass's own default --
            only Figure and Table Blocks are listed, exactly as before
            the concept existed"
    (let [doc {:meta {}
               :blocks [(figure-directive "figure" "fig:panels" [])
                        (figure "fig:tree")]}
          labels (panel-labels doc)]
      (is (= ["fig:tree"] (map :id (resolver/derive-list-of-figures doc labels))))))
  (testing "a PANEL is never an entry of its own: \\listoffigures lists
            the figure, not its subfigures. It is excluded by not being
            among the float names -- a panel is laid out by its parent,
            never as a float of its own"
    (let [doc {:meta {}
               :blocks [(figure-directive "figure" "fig:panels"
                                          [(figure-directive "subfigure" "fig:pa" [])
                                           (figure-directive "subfigure" "fig:pb" [])])]}
          labels (panel-labels doc)
          lof (resolver/derive-list-of-figures doc labels #{"figure"})]
      (is (= ["fig:panels"] (map :id lof)))))
  (testing "and a sublabelled node is excluded even by a caller whose own
            table names a panel a float -- the number it carries belongs
            to the figure above it, so an entry for it would print that
            figure's number twice"
    (let [doc {:meta {}
               :blocks [(figure-directive "figure" "fig:panels"
                                          [(figure-directive "subfigure" "fig:pa" [])])]}
          labels (panel-labels doc)
          lof (resolver/derive-list-of-figures doc labels #{"figure" "subfigure"})]
      (is (= ["fig:panels"] (map :id lof)))))
  (testing "a caption-less Figure still lists with an empty caption
            rather than a nil one, so an emitter can render it the one
            way"
    (let [doc {:meta {} :blocks [(figure "fig:tree")]}
          lof (resolver/derive-list-of-figures doc)]
      (is (= [] (:caption (first lof)))))))

(deftest bibliography-id-follows-the-documents-division-test
  (testing "TASK-63: the generated bibliography section is a level-1
            heading, which a chaptered document emits as \\chapter -- so
            its id prefix is ch: there. With sec: it was exactly the
            id-prefix/division disagreement structural-diagnostics warns
            about, and every chaptered document with a bibliography
            warned about a section nobody wrote and nobody could edit"
    (let [doc {:meta {:top-level-division :chapter}
               :blocks [(section "ch:one" [(para (cite (cite-item "smith2020" :normal)))])]}
          {:keys [document bibliography-id]} (resolver/resolve-citations doc bibliography)]
      (is (= "ch:bibliography" bibliography-id))
      (is (= "ch:bibliography" (:id (:attr (last (:blocks document))))))
      (testing "so the document builds clean -- which is the whole point:
                the id is generated here, so no edit to the document
                could have silenced the warning a sec: prefix produced"
        (is (empty? (filter #(= :division-kind-mismatch (:type %))
                            (resolver/structural-diagnostics document)))))
      (testing "and the kind that id resolves to is the division it is
                emitted into, so nothing downstream can name it one way
                in one target and another in the other. Measured on a
                re-run of the numbering pass deliberately: the table
                this pipeline hands its emitters is the one numbering
                ran on BEFORE this section existed, so the section
                itself is unnumbered in both outputs and a reference to
                it resolves in neither -- an older gap this task does
                not close (found by review; see TASK-64)"
        (let [labels (resolver/number-document document)]
          (is (= :ch (:kind (get labels "ch:bibliography"))))
          (is (= "Chapter 2" (:text (get labels "ch:bibliography"))))))))
  (testing "and a document that is not chaptered generates exactly the id
            and the number it always did"
    (let [doc {:meta {}
               :blocks [(section "sec:one" [(para (cite (cite-item "smith2020" :normal)))])]}
          {:keys [document bibliography-id]} (resolver/resolve-citations doc bibliography)]
      (is (= "sec:bibliography" bibliography-id))
      (is (= "Section 2" (:text (get (resolver/number-document document) "sec:bibliography"))))))
  (testing "the collision suffix follows the same prefix, so a chaptered
            document whose author already used ch:bibliography still gets
            a non-colliding id of the right kind"
    (let [doc {:meta {:top-level-division :chapter}
               :blocks [(section "ch:bibliography" [])
                        (para (cite (cite-item "smith2020" :normal)))]}
          {:keys [bibliography-id warnings]} (resolver/resolve-citations doc bibliography)]
      (is (= "ch:bibliography-2" bibliography-id))
      (is (= [:bibliography-id-collision] (map :type warnings))))))

(deftest bibliography-section-is-referenceable-test
  (testing "TASK-64: a cross-reference to the GENERATED bibliography
            section resolves. It never did: citations ran after
            cross-references, so the section did not exist when the
            references were resolved, and the label table an emitter is
            handed is the one numbering ran on -- @sec:bibliography
            printed ?? in both targets while TASK-13 AC #3 claimed it
            was referenceable"
    (let [doc {:meta {}
               :blocks [(section "sec:body" [(para (cite (cite-item "smith2020" :normal))
                                                   (cross-ref "sec:bibliography"))])]}
          {:keys [document labels]} (resolver/resolve-document doc {:bibliography bibliography})
          [resolved] (resolved-cross-refs {:document document})]
      (is (= "sec:bibliography" (:target resolved)))
      (is (= "Bibliography" (:text resolved))
          "by NAME, not by number: the section is appended after numbering, and native LaTeX
           sets the reference list under an unnumbered heading, so a number would be one
           HTML printed and the PDF did not")
      (is (= {:kind :sec :word "Bibliography" :text "Bibliography"}
             (get labels "sec:bibliography")))
      (is (nil? (:number (get labels "sec:bibliography")))
          "and it carries no number at all, so nothing prints one for it")))
  (testing "the word follows the document's own language, like every
            other printed word here"
    (let [doc {:meta {:lang "pt-BR"}
               :blocks [(section "sec:body" [(para (cite (cite-item "smith2020" :normal))
                                                   (cross-ref "sec:bibliography"))])]}
          {:keys [document]} (resolver/resolve-document doc {:bibliography bibliography})
          [resolved] (resolved-cross-refs {:document document})]
      (is (= "Referências" (:text resolved)))))
  (testing "and in a chaptered document the id it resolves against is the
            ch: one TASK-63 gives it, so the two fixes compose"
    (let [doc {:meta {:top-level-division :chapter}
               :blocks [(section "ch:body" [(para (cite (cite-item "smith2020" :normal))
                                                  (cross-ref "ch:bibliography"))])]}
          {:keys [document]} (resolver/resolve-document doc {:bibliography bibliography})
          [resolved] (resolved-cross-refs {:document document})]
      (is (= "ch:bibliography" (:target resolved)))
      (is (= "Bibliography" (:text resolved)))))
  (testing "a document that cites nothing generates no section, so a
            reference to that id dangles exactly as any other reference
            to a node that is not there"
    (let [doc {:meta {}
               :blocks [(section "sec:body" [(para (cross-ref "sec:bibliography"))])]}
          {:keys [document diagnostics]} (resolver/resolve-document doc)
          [resolved] (resolved-cross-refs {:document document})]
      (is (= "??" (:text resolved)))
      (is (= [:dangling-cross-ref] (map :type diagnostics))))))

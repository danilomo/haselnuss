(ns haselnuss.anchor-test
  "Text-quote anchor resolution (`haselnuss.anchor`, TASK-117, doc-8
  sections 1 and 6): finding WHERE in a subtree a quoted run of words is,
  and reporting by name every outcome that is not exactly one place.

  Every fixture here is literal AST data. Nothing in this file parses a
  document, reads a file or runs the CLI (AC #10) -- `haselnuss.anchor` is
  a leaf namespace, and its tests are meant to prove that by being
  runnable without any of that machinery.

  The four resolution outcomes (AC #4, #6, #7), the three disambiguators
  (AC #5), whitespace tolerance (AC #3), a quote spanning several sibling
  inlines (AC #2) and one spanning two paragraphs (AC #8) each have their
  own test. `search-units-reach-every-container-test` is the
  exhaustiveness canary AC #9 asks for, in the shape
  `resolver-test`'s own `overlay-splice-reaches-every-container-test`
  established; `leaf-text-agrees-with-select-test` is AC #2's \"the two
  cannot disagree about what a node's text is\"."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [haselnuss.anchor :as anchor]
            [haselnuss.ast :as ast]
            [haselnuss.select :as select]
            [haselnuss.walk :as walk]))

(def ^:private attr
  {:classes [] :props {}})

(defn- id-attr
  [id]
  (assoc attr :id id))

(defn- s
  [text]
  {:t :str :text text})

(def ^:private space
  {:t :space})

(defn- para
  [& inlines]
  {:t :para :inlines (vec inlines)})

(defn- section
  [id & blocks]
  {:t :section :level 1 :heading [(s "Hazelnuts")] :blocks (vec blocks)
   :attr (id-attr id)})

(defn- resolve-in
  "`resolve-anchor` against a one-paragraph Section built from `inlines`,
  the shape most of these tests want."
  [anchor & inlines]
  (anchor/resolve-anchor (section "sec:intro" (apply para inlines)) anchor))

(defn- diagnostic-types
  [result]
  (mapv :type (:diagnostics result)))

;; --- finding one range (AC #1, #2, #3) -------------------------------------

(deftest quote-inside-one-paragraph-resolves-test
  (testing "AC #1: a quote matching one run inside one paragraph resolves
            to a single range identifying the unit and the start/end
            positions within it"
    (let [{matched :range diagnostics :diagnostics}
          (resolve-in {:text "ripen in early autumn"}
                      (s "Hazelnuts ripen in early autumn and drop soon after."))]
      (is (empty? diagnostics))
      (is (= [:blocks 0 :inlines] (:path matched))
          "the path addresses the inline vector itself, ready for update-in")
      (is (= 10 (:start matched)))
      (is (= 31 (:end matched)))
      (is (= [0 10] (:start-pos matched)))
      (is (= [0 31] (:end-pos matched)))
      (is (= "ripen in early autumn"
             (subs (:text (s "Hazelnuts ripen in early autumn and drop soon after."))
                   (:start matched) (:end matched)))
          "the raw offsets cut exactly the quoted words back out"))))

(deftest quote-spanning-sibling-inlines-resolves-test
  (testing "AC #2: a quote running from a :str, through a whole :emph,
            into another :str is ONE range covering all three -- a quote
            addresses the document, not the parse"
    (let [{matched :range diagnostics :diagnostics}
          (resolve-in {:text "cold frames work"}
                      (s "In cold ") {:t :emph :inlines [(s "frames")]} (s " work well."))]
      (is (empty? diagnostics))
      (is (= [0 3] (:start-pos matched)) "starts inside the leading :str")
      (is (= [2 5] (:end-pos matched)) "ends inside the trailing :str")
      (is (= 3 (:start matched)))
      (is (= 19 (:end matched))))))

(deftest whitespace-is-normalised-test
  (testing "AC #3: a quote whose words are separated in the source by a
            soft line break resolves -- reflowing a paragraph is the
            second most common edit, and an anchor that broke on it would
            be nearly as brittle as a counter"
    (let [{matched :range diagnostics :diagnostics}
          (resolve-in {:text "ripen in early autumn"}
                      (s "Hazelnuts ripen in") {:t :soft-break} (s "early autumn."))]
      (is (empty? diagnostics))
      (is (some? matched))))
  (testing "AC #3: a quote written with irregular internal whitespace
            resolves against ordinary prose"
    (let [{matched :range diagnostics :diagnostics}
          (resolve-in {:text "ripen   in\n  early  autumn"}
                      (s "Hazelnuts ripen in early autumn."))]
      (is (empty? diagnostics))
      (is (= 10 (:start matched)))
      (is (= 31 (:end matched)))))
  (testing "AC #3: explicit :space Inlines between :str leaves are the
            same one space a literal one is"
    (let [{matched :range diagnostics :diagnostics}
          (resolve-in {:text "early autumn"} (s "early") space (s "autumn"))]
      (is (empty? diagnostics))
      (is (= [0 0] (:start-pos matched)))
      (is (= [2 6] (:end-pos matched))))))

(deftest normalise-maps-offsets-back-to-raw-test
  (testing "collapsing a whitespace run maps the single space back to the
            run's FIRST character, and both ends are trimmed away"
    (let [{:keys [text offsets]} (anchor/normalise "  a \n b  ")]
      (is (= "a b" text))
      (is (= [2 3 6] offsets))
      (is (= 3 (count offsets)) "one offset per normalised character"))))

;; --- disambiguation (AC #4, #5, #6) ---------------------------------------

(def ^:private repeated
  "One paragraph in which the quote \"cold frames\" occurs twice, with
  distinct words either side of each occurrence."
  (s "Use cold frames in spring; reuse cold frames in autumn."))

(deftest ambiguous-quote-is-diagnosed-not-guessed-test
  (testing "AC #4: a quote occurring twice with no before=/after=/nth=
            produces :ambiguous-annotation-anchor naming the count, and
            no range"
    (let [{matched :range diagnostics :diagnostics} (resolve-in {:text "cold frames" :target "sec:intro"} repeated)
          d (first diagnostics)]
      (is (nil? matched))
      (is (= [:ambiguous-annotation-anchor] (diagnostic-types {:diagnostics diagnostics})))
      (is (= 2 (:count d)))
      (is (str/includes? (:message d) "occurs 2 times"))
      (is (str/includes? (:message d) "sec:intro"))
      (is (str/includes? (:message d) "nth=")
          "the message says how to fix it, not just that it is broken"))))

(deftest before-after-and-nth-each-disambiguate-test
  (testing "AC #5: the same quote picked out by before=, by after= and by
            nth= each resolves to the intended one of the two"
    (let [first-start (:start (:range (resolve-in {:text "cold frames" :before "Use"} repeated)))
          second-start (:start (:range (resolve-in {:text "cold frames" :before "reuse"} repeated)))]
      (is (= 4 first-start))
      (is (= 33 second-start))
      (is (= first-start (:start (:range (resolve-in {:text "cold frames" :after "in spring"} repeated)))))
      (is (= second-start (:start (:range (resolve-in {:text "cold frames" :after "in autumn"} repeated)))))
      (is (= first-start (:start (:range (resolve-in {:text "cold frames" :nth 1} repeated)))))
      (is (= second-start (:start (:range (resolve-in {:text "cold frames" :nth 2} repeated)))))))
  (testing "before=/after= are compared against the immediate context
            with the separating whitespace trimmed, so an author need not
            write the space into the prop"
    (is (some? (:range (resolve-in {:text "cold frames" :before "Use "} repeated))))
    (is (some? (:range (resolve-in {:text "cold frames" :after " in spring"} repeated)))))
  (testing "a before=/after= that matches neither occurrence is dangling,
            not ambiguous -- the filter ran and nothing survived it"
    (is (= [:dangling-annotation-anchor]
           (diagnostic-types (resolve-in {:text "cold frames" :before "greenhouse"} repeated))))))

(deftest nth-out-of-range-is-diagnosed-test
  (testing "AC #6: nth= beyond the candidate count produces
            :annotation-anchor-out-of-range naming the count, and no range"
    (let [{matched :range diagnostics :diagnostics} (resolve-in {:text "cold frames" :nth 3} repeated)
          d (first diagnostics)]
      (is (nil? matched))
      (is (= :annotation-anchor-out-of-range (:type d)))
      (is (= 2 (:count d)))
      (is (= 3 (:nth d)))
      (is (str/includes? (:message d) "nth=3"))
      (is (str/includes? (:message d) "2 matches"))))
  (testing "a zero or negative nth= is out of range too, diagnosed rather
            than thrown -- an anchor comes from a document"
    (is (= [:annotation-anchor-out-of-range]
           (diagnostic-types (resolve-in {:text "cold frames" :nth 0} repeated))))
    (is (= [:annotation-anchor-out-of-range]
           (diagnostic-types (resolve-in {:text "cold frames" :nth -1} repeated)))))
  (testing "nth=1 against a single unambiguous match is in range"
    (is (some? (:range (resolve-in {:text "early autumn" :nth 1} (s "ripen in early autumn.")))))))

;; --- failing loudly (AC #7, #8) -------------------------------------------

(deftest absent-quote-is-dangling-test
  (testing "AC #7: a quote absent from the target produces
            :dangling-annotation-anchor naming the quote and the target,
            and no range. This is the whole argument for quotes over
            counters: the anchor reports that it no longer holds instead
            of recalculating onto the wrong words"
    (let [{matched :range diagnostics :diagnostics}
          (resolve-in {:text "ripen in late spring" :target "sec:intro"}
                      (s "Hazelnuts ripen in early autumn."))
          d (first diagnostics)]
      (is (nil? matched))
      (is (= :dangling-annotation-anchor (:type d)))
      (is (= "ripen in late spring" (:quote d)))
      (is (= "sec:intro" (:target d)))
      (is (str/includes? (:message d) "ripen in late spring"))
      (is (str/includes? (:message d) "sec:intro"))
      (is (str/includes? (:message d) "update the quote")
          "the message tells an author what to do about a stale anchor")))
  (testing "a blank quote addresses nothing, rather than matching at
            every position"
    (is (= [:dangling-annotation-anchor]
           (diagnostic-types (resolve-in {:text ""} (s "anything")))))
    (is (= [:dangling-annotation-anchor]
           (diagnostic-types (resolve-in {:text "   "} (s "anything"))))))
  (testing "a target naming no id still produces a readable message"
    (let [d (first (:diagnostics (resolve-in {:text "absent"} (s "prose"))))]
      (is (str/includes? (:message d) "the annotated node")))))

(deftest quote-spanning-two-paragraphs-is-dangling-test
  (testing "AC #8: a quote spanning a paragraph boundary matches no
            single search unit and comes back dangling -- never a partial
            or cross-block range"
    (let [target (section "sec:intro"
                          (para (s "Hazelnuts ripen in early autumn."))
                          (para (s "They drop soon after.")))
          {matched :range diagnostics :diagnostics}
          (anchor/resolve-anchor target {:text "early autumn. They drop"})]
      (is (nil? matched))
      (is (= [:dangling-annotation-anchor] (mapv :type diagnostics))))
    (testing "and each half on its own still resolves, so the boundary is
              what failed rather than the prose"
      (let [target (section "sec:intro"
                            (para (s "Hazelnuts ripen in early autumn."))
                            (para (s "They drop soon after.")))]
        (is (some? (:range (anchor/resolve-anchor target {:text "early autumn"}))))
        (is (some? (:range (anchor/resolve-anchor target {:text "They drop"}))))))))

;; --- search units are derived, not enumerated (AC #9) ---------------------

(defn- type-keys-for
  "Every `haselnuss.walk/table` type key belonging to registry key
  `ref-kind`: a `:multi` entry's own variant tags (`::ast/block` ->
  `:section`, `:para`, ...), or the bare un-namespaced key for a plain
  `[:map ...]` entry (`::ast/cite-item` -> `:cite-item`) -- exactly the
  correspondence `haselnuss.walk/node-type` establishes, re-derived here
  by the simplest independent read of the raw schema."
  [ref-kind]
  (let [schema (get ast/registry ref-kind)]
    (if (= :multi (first schema))
      (set (map first (drop 2 schema)))
      #{(keyword (name ref-kind))})))

(def ^:private unit-owning-types
  "Every node type that OWNS a search unit, derived the same way
  `haselnuss.anchor` derives it and from its own two public vars: a
  `[:many ::ast/inline]` field in `haselnuss.walk/table`, on a node type
  reached at a reference kind that is in `anchor/unit-scope` and NOT in
  `anchor/inline-stratum`.

  Derived rather than listed, in the shape `walk-test`'s own
  `every-schema-variant-has-a-table-entry-test` established: a node type
  the schema gains later that can hold Inlines shows up here
  immediately, and the fixture map below then fails until it is covered.
  A hand-written list would simply stay green and stay wrong -- which is
  exactly the failure this whole namespace is built to avoid."
  (let [owning-kinds (remove anchor/inline-stratum anchor/unit-scope)
        owning-types (into #{} (mapcat type-keys-for) owning-kinds)]
    (into #{}
          (keep (fn [[type-key field-specs]]
                  (when (and (contains? owning-types type-key)
                             (some (fn [[_ [arity ref-kind]]]
                                     (and (= :many arity) (= ::ast/inline ref-kind)))
                                   field-specs))
                    type-key)))
          (:children walk/table))))

(deftest search-units-reach-every-container-test
  (testing "AC #9: search units come from haselnuss.walk/table, so a
            section heading, a figure caption, a table cell, a list item
            and a footnote body are all searched. The fixture map is
            checked against `unit-owning-types`, DERIVED from the same
            table -- so this is a canary in walk-test's own shape rather
            than a hand-maintained list that can quietly fall behind the
            schema"
    (let [needle (s "cold frames")
          containers
          {:section {:t :section :level 1 :heading [needle] :blocks [] :attr attr}
           :para (para needle)
           :figure {:t :figure :content (para (s "x")) :caption [needle] :attr attr}
           :table {:t :table :head {:cells []} :rows [] :caption [needle]
                   :colspec [] :attr attr}
           :meta {:meta {:title [needle]} :blocks []}
           ;; TASK-152: a resolved quote's passage title, the one Inline
           ;; field the book-quote Block has.
           :book-quote {:t :book-quote :ref "bible:romanos#14:8" :title [needle]}}
          extra-containers
          {:table-cell {:t :table :head {:cells [{:blocks [(para needle)]}]} :rows []
                        :caption [] :colspec [] :attr attr}
           :list-item {:t :list :ordered false :tight true :items [[(para needle)]] :attr attr}
           :block-quote {:t :block-quote :blocks [(para needle)]}
           :footnote-body (para {:t :note :blocks [(para needle)]})
           :sticky-note-body (para {:t :sticky-note :blocks [(para needle)] :attr attr})
           :highlight (para {:t :highlight :inlines [needle] :attr attr})}]
      (is (= unit-owning-types (set (keys containers)))
          "every unit-owning node type the schema defines has a fixture here")
      (doseq [[container node] (merge containers extra-containers)]
        (testing (str container)
          (let [ref-kind (if (= :meta container) ::ast/document ::ast/block)
                {matched :range diagnostics :diagnostics}
                (anchor/resolve-anchor ref-kind node {:text "cold frames"})]
            (is (empty? diagnostics))
            (is (some? matched)))))))
  (testing "a Fallback's own :poster caption is NOT a search unit, even
            though it is a [:many ::ast/inline] field: a fallback is an
            alternative representation of a directive's content that
            `lower` only chooses between AFTER annotations are applied,
            so counting it would make a phrase restated in both the body
            and the caption falsely ambiguous"
    (let [directive {:t :directive :name "chart" :attr attr
                     :blocks [(para (s "cold frames"))]
                     :fallback {:kind :poster :src "p.png"
                                :caption [(s "cold frames")]}}]
      (is (= [[:blocks 0 :inlines]] (mapv :path (anchor/search-units directive))))
      (is (some? (:range (anchor/resolve-anchor directive {:text "cold frames"}))))))
  (testing "an :emph's own :inlines is NOT a unit of its own -- it is
            already inside the unit its enclosing Para opened, and
            counting it twice would make every emphasised quote ambiguous"
    (let [node (para (s "In ") {:t :emph :inlines [(s "cold frames")]} (s " it works."))]
      (is (= 1 (count (anchor/search-units node))))
      (is (some? (:range (anchor/resolve-anchor node {:text "cold frames"}))))))
  (testing "a footnote body is its own unit rather than part of the
            paragraph carrying the marker, so a quote inside one is
            unambiguous even when the same words sit in the prose"
    (let [node (para (s "Grow cold frames.") {:t :note :blocks [(para (s "Or cold frames."))]})]
      (is (= 2 (count (anchor/search-units node))))
      (is (= [:ambiguous-annotation-anchor]
             (diagnostic-types (anchor/resolve-anchor node {:text "cold frames"}))))
      (is (some? (:range (anchor/resolve-anchor node {:text "Or cold frames"}))))))
  (testing "units come back in document order, so nth= counts the way a
            reader reads"
    (let [node (section "sec:x" (para (s "one")) (para (s "two")))]
      (is (= [[:heading] [:blocks 0 :inlines] [:blocks 1 :inlines]]
             (mapv :path (anchor/search-units node))))))
  (testing "an empty inline vector contributes no unit at all"
    (is (empty? (anchor/search-units (para))))))

;; --- leaf text agrees with haselnuss.select (AC #2) -----------------------

(deftest leaf-text-agrees-with-select-test
  (testing "AC #2: every Inline whose text is a leaf, or a container of
            leaves, flattens identically in haselnuss.anchor and in
            haselnuss.select -- the two cannot disagree about what a
            node's text is"
    (doseq [node [(s "hazelnuts")
                  space
                  {:t :soft-break}
                  {:t :line-break}
                  {:t :emph :inlines [(s "a") space (s "b")]}
                  {:t :strong :inlines [(s "a")]}
                  {:t :strike :inlines [(s "a")]}
                  {:t :small-caps :inlines [(s "a")]}
                  {:t :sub :inlines [(s "a")]}
                  {:t :sup :inlines [(s "a")]}
                  {:t :span :inlines [(s "a")] :attr attr}
                  {:t :link :target "https://example.com" :inlines [(s "a")] :attr attr}
                  {:t :highlight :inlines [(s "a")] :attr attr}
                  {:t :code :text "(+ 1 2)"}
                  {:t :math-inline :tex "x^2"}
                  {:t :image :src "x.png" :alt "a tree" :attr attr}
                  {:t :cross-ref :label "fig:tree"}
                  {:t :cite :items [{:key "smith2020" :mode :normal
                                     :prefix [(s "see")] :suffix [(s "p. 3")]}]}]]
      (testing (str (:t node))
        (is (= (select/flatten-text node) (anchor/inline-text node))))))
  (testing "divergence 2 of 2: a :cite carrying SEVERAL items
            concatenates their prefix/suffix text where flatten-text
            joins them with a space it invents. Pinned rather than left
            implicit, because 'fixing' it would put a character in
            flatten-unit's offset map that no Inline in the document
            accounts for, and TASK-118 has to be able to split at every
            position that map reports"
    (let [two-items {:t :cite
                     :items [{:key "a" :mode :normal :prefix [(s "see")]}
                             {:key "b" :mode :normal :suffix [(s "p. 3")]}]}]
      (is (= "seep. 3" (anchor/inline-text two-items)))
      (is (= "see p. 3" (select/flatten-text two-items)))
      (is (= (count (anchor/inline-text two-items))
             (count (:positions (anchor/flatten-unit [two-items]))))
          "and every character anchor reports does come from a real inline")))
  (testing "divergence 1 of 2 is deliberate and documented: a
            Block-bearing Inline contributes nothing here, because its
            Blocks are search units of their own"
    (doseq [node [{:t :note :blocks [(para (s "footnote"))]}
                  {:t :sticky-note :blocks [(para (s "balloon"))] :attr attr}]]
      (testing (str (:t node))
        (is (= "" (anchor/inline-text node)))
        (is (not= "" (select/flatten-text node))
            "haselnuss.select still reads it, and should -- :text selects
             on what a node says wherever it was written")))))

(deftest flatten-unit-maps-every-character-back-test
  (testing "flatten-unit's offset map holds one [inline-index offset]
            per character of the flattened text, and an inline
            contributing no text occupies no position"
    (let [{:keys [text positions]}
          (anchor/flatten-unit [(s "ab") {:t :cross-ref :label "fig:x"} space (s "c")])]
      (is (= "ab c" text))
      (is (= [[0 0] [0 1] [2 0] [3 0]] positions))
      (is (= (count text) (count positions))))))

;; --- the namespace is a leaf (AC #10) -------------------------------------

(deftest anchor-is-a-leaf-namespace-test
  (testing "AC #10: haselnuss.anchor requires no haselnuss namespace that
            performs I/O -- read off the live namespace rather than the
            source text, so a require added later is caught"
    (let [ns-form (read-string (slurp "src/haselnuss/anchor.clj"))
          require-clauses (filter #(and (seq? %) (= :require (first %))) ns-form)]
      (is (= 1 (count require-clauses)))
      (is (= '(:require [clojure.string :as str]
                        [haselnuss.ast :as ast]
                        [haselnuss.walk :as walk])
             (first require-clauses))
          "read off the SOURCE's own ns form rather than the loaded
           namespace's aliases, so an un-aliased (:require [some.ns]) or
           a :refer would be caught too -- select_test checks its own
           leaf namespace exactly this way")))
  (testing "AC #10: and its own document validity is not assumed -- every
            fixture above is a real AST node"
    (is (ast/valid? ast/Block (section "sec:intro" (para (s "prose")))))))

;; --- applying a resolved range (TASK-118) ---------------------------------

(def ^:private highlight-template
  "The `:highlight` node `apply-annotation` fills the matched run into --
  what TASK-119 will build from a directive's own `color=`/`author=`
  props, and what this suite passes bare."
  {:t :highlight :inlines [] :attr attr})

(defn- sticky
  "A `:sticky-note` carrying one paragraph of `text`."
  [text]
  {:t :sticky-note :blocks [(para (s text))] :attr attr})

(defn- annotate
  "Resolves `quote-text` against `node` and applies `annotation`,
  returning `[document diagnostic-types]`. Fails loudly rather than
  silently testing nothing if the quote did not resolve in the first
  place."
  ([node quote-text] (annotate node quote-text {:highlight highlight-template}))
  ([node quote-text annotation]
   (let [{matched :range} (anchor/resolve-anchor node {:text quote-text})]
     (is (some? matched) (str "fixture quote should resolve: " quote-text))
     (let [{:keys [document diagnostics]} (anchor/apply-annotation node matched annotation)]
       [document (mapv :type diagnostics)]))))

(defn- inlines-of
  [document]
  (:inlines document))

(deftest wrap-whole-sibling-inlines-test
  (testing "AC #1: a range covering whole sibling inlines wraps exactly
            those in one :highlight, leaving the surrounding inlines
            untouched"
    (let [[document diagnostics]
          (annotate (para (s "a ") {:t :emph :inlines [(s "b")]} (s " c")) "b")]
      (is (= [] diagnostics))
      (is (= [(s "a ")
              {:t :highlight :inlines [{:t :emph :inlines [(s "b")]}] :attr attr}
              (s " c")]
             (inlines-of document))))))

(deftest split-a-str-into-three-test
  (testing "AC #2: a range starting and ending inside one :str splits it
            into three, with the middle wrapped"
    (let [[document diagnostics] (annotate (para (s "abcdef")) "cd")]
      (is (= [] diagnostics))
      (is (= [(s "ab")
              {:t :highlight :inlines [(s "cd")] :attr attr}
              (s "ef")]
             (inlines-of document))))))

(deftest partly-covered-container-is-recursed-into-test
  (testing "AC #3: a range partly covering an :emph produces a :highlight
            NESTED inside the :emph, not an :emph moved inside a
            :highlight. One anchor therefore produces two :highlight nodes
            at different depths -- visually seamless, and the only form
            that keeps the tree well-formed"
    (let [[document diagnostics]
          (annotate (para (s "x ") {:t :emph :inlines [(s "yyzz")]} (s " w")) "x yy")]
      (is (= [] diagnostics))
      (is (= [{:t :highlight :inlines [(s "x ")] :attr attr}
              {:t :emph :inlines [{:t :highlight :inlines [(s "yy")] :attr attr}
                                  (s "zz")]}
              (s " w")]
             (inlines-of document)))
      (is (= :emph (:t (second (inlines-of document))))
          "the :emph is still the outer node -- it was not moved inside anything"))))

(deftest range-spanning-a-whole-container-test
  (testing "AC #4: a range from inside one :str, across a whole :strong,
            into another :str produces the fragments needed to cover it,
            with the tree still valid"
    (let [[document diagnostics]
          (annotate (para (s "aa") {:t :strong :inlines [(s "BB")]} (s "cc")) "aBBc")]
      (is (= [] diagnostics))
      (is (= [(s "a")
              {:t :highlight
               :inlines [(s "a") {:t :strong :inlines [(s "BB")]} (s "c")]
               :attr attr}
              (s "c")]
             (inlines-of document)))
      (is (ast/valid? ast/Block document)))))

(deftest boundary-inside-an-atomic-leaf-snaps-outward-test
  (testing "AC #5: a range boundary falling inside a :code snaps outward
            to include it whole and says so -- the quote plainly meant to
            cover it, and a slightly wider highlight is a better failure
            than a missing one, but it is never silent"
    (let [[document diagnostics]
          (annotate (para (s "see ") {:t :code :text "foobar"} (s " end")) "see foo")]
      (is (= [:annotation-anchor-snapped] diagnostics))
      (is (= [{:t :highlight :inlines [(s "see ") {:t :code :text "foobar"}] :attr attr}
              (s " end")]
             (inlines-of document)))))
  (testing "AC #5: and the same for :math-inline, with the diagnostic
            naming which node type forced it"
    (let [node (para (s "let ") {:t :math-inline :tex "x^2+y"} (s " hold"))
          {matched :range} (anchor/resolve-anchor node {:text "let x^2"})
          {:keys [diagnostics]} (anchor/apply-annotation node matched
                                                         {:highlight highlight-template})]
      (is (= [:annotation-anchor-snapped] (mapv :type diagnostics)))
      (is (= :math-inline (:node-type (first diagnostics))))
      (is (str/includes? (:message (first diagnostics)) "math-inline")))))

(deftest book-ref-is-atomic-test
  (testing "TASK-152: a resolved :book-ref is ATOMIC, like a :cross-ref --
            its computed link text is read across but never cut into, so
            a boundary inside it snaps outward over the whole node"
    (let [book-ref {:t :book-ref :ref "bible:romanos#14:8"
                    :text [(s "Romanos 14:8")]}
          [document diagnostics] (annotate (para (s "see ") book-ref (s " now")) "see Romanos")]
      (is (= [:annotation-anchor-snapped] diagnostics))
      (is (= [{:t :highlight :inlines [(s "see ") book-ref] :attr attr}
              (s " now")]
             (inlines-of document))
          "the :book-ref is wrapped whole, its :text untouched")))
  (testing "a quote entirely inside its text does not descend into it either"
    (let [book-ref {:t :book-ref :ref "bible:romanos#14:8"
                    :text [{:t :emph :inlines [(s "Romanos")]} (s " 14:8")]}
          [document diagnostics] (annotate (para (s "see ") book-ref) "Romanos")]
      (is (= [:annotation-anchor-snapped] diagnostics))
      (is (= [(s "see ") {:t :highlight :inlines [book-ref] :attr attr}]
             (inlines-of document)))))
  (testing "and a sticky note edge inside it moves to a seam beside it"
    (let [book-ref {:t :book-ref :ref "bible:x" :text [(s "Romanos 14:8")]}
          node (para (s "see ") book-ref (s " now"))
          {matched :range} (anchor/resolve-anchor node {:text "see Romanos"})
          {:keys [document diagnostics]}
          (anchor/apply-annotation node matched {:sticky-note (sticky "N") :at :end})]
      (is (= [:annotation-anchor-snapped] (mapv :type diagnostics)))
      (is (= [(s "see ") book-ref (sticky "N") (s " now")] (inlines-of document))))))

(deftest sticky-note-is-a-point-not-a-range-test
  (testing "AC #6: a sticky note anchored with at=end is inserted
            immediately after the matched run"
    (let [[document diagnostics]
          (annotate (para (s "one two three")) "two"
                    {:sticky-note (sticky "N") :at :end})]
      (is (= [] diagnostics))
      (is (= [(s "one two") (sticky "N") (s " three")] (inlines-of document)))))
  (testing "AC #6: and with at=start immediately before it"
    (let [[document diagnostics]
          (annotate (para (s "one two three")) "two"
                    {:sticky-note (sticky "N") :at :start})]
      (is (= [] diagnostics))
      (is (= [(s "one ") (sticky "N") (s "two three")] (inlines-of document)))))
  (testing "at= defaults to end, so \"between two words\" is the quote of
            the word before"
    (let [[document _] (annotate (para (s "one two three")) "two"
                                 {:sticky-note (sticky "N")})]
      (is (= [(s "one two") (sticky "N") (s " three")] (inlines-of document)))))
  (testing "doc-8's Acrobat shape: a highlight AND a sticky note on one
            range wrap the run and put the balloon immediately after the
            wrapping node -- built from the two node types, not a third"
    (let [[document diagnostics]
          (annotate (para (s "one two three")) "two"
                    {:highlight highlight-template :sticky-note (sticky "N") :at :end})]
      (is (= [] diagnostics))
      (is (= [(s "one ")
              {:t :highlight :inlines [(s "two")] :attr attr}
              (sticky "N")
              (s " three")]
             (inlines-of document)))))
  (testing "an edge falling inside an indivisible node goes to the
            shallowest position where it does fall between two inlines,
            and says so"
    (let [node (para (s "a ") {:t :code :text "xyz"} (s " b"))
          {matched :range} (anchor/resolve-anchor node {:text "a xy"})
          {:keys [document diagnostics]}
          (anchor/apply-annotation node matched {:sticky-note (sticky "N") :at :end})]
      (is (= [:annotation-anchor-snapped] (mapv :type diagnostics)))
      (is (= [(s "a ") {:t :code :text "xyz"} (sticky "N") (s " b")]
             (inlines-of document))))))

;; --- the invariants (AC #7, #8, #9) ---------------------------------------

(def ^:private strip-sticky-notes-walker
  "A `haselnuss.walk/rewrite-post` walker that removes every
  `:sticky-note` -- see `document-text` for why the text invariant is
  measured with them taken back out."
  {:table walk/table
   :scope walk/every-ref-kind
   :rewrite-fn (fn [_path _ref-kind node] (if (= :sticky-note (:t node)) [] [node]))})

(defn- document-text
  "`node`'s own flattened text with every `:sticky-note` removed first.

  For a `:highlight` this is simply `select/flatten-text`: wrapping a run
  changes no character of it. A `:sticky-note` is different in kind -- its
  body is NEW prose, a comment ABOUT the document rather than text in it
  -- so byte-identical whole-document flattening cannot hold for one, and
  claiming it did would be measuring nothing. Taking the notes back out
  and then comparing is the honest form of the same invariant: the
  annotation added nothing to, and removed nothing from, the document's
  own words."
  [node]
  (select/flatten-text (first (walk/rewrite-post strip-sticky-notes-walker ::ast/block node))))

(deftest applying-an-annotation-preserves-the-text-test
  (testing "AC #7: haselnuss.select/flatten-text over the document is
            byte-identical before and after a highlight is applied --
            the cheapest guard there is against a split that loses or
            duplicates a character"
    (doseq [[label node quote-text]
            [["whole sibling" (para (s "a ") {:t :emph :inlines [(s "b")]} (s " c")) "b"]
             ["inside one str" (para (s "abcdef")) "cd"]
             ["partly over emph" (para (s "x ") {:t :emph :inlines [(s "yyzz")]} (s " w")) "x yy"]
             ["across a strong" (para (s "aa") {:t :strong :inlines [(s "BB")]} (s "cc")) "aBBc"]
             ["snapped over code" (para (s "see ") {:t :code :text "foobar"} (s " end")) "see foo"]
             ["at the very start" (para (s "abcdef")) "ab"]
             ["at the very end" (para (s "abcdef")) "ef"]
             ["the whole unit" (para (s "abcdef")) "abcdef"]]]
      (testing label
        (let [[document _] (annotate node quote-text)]
          (is (= (select/flatten-text node) (select/flatten-text document)))))))
  (testing "AC #7: and with a sticky note taken back out, so is a
            document that got one -- see `document-text`"
    (doseq [at [:start :end]]
      (testing (str at)
        (let [node (para (s "one two three"))
              [document _] (annotate node "two" {:highlight highlight-template
                                                 :sticky-note (sticky "a comment")
                                                 :at at})]
          (is (= (select/flatten-text node) (document-text document))))))))

(deftest applying-an-annotation-yields-a-valid-document-test
  (testing "AC #8: every document produced by applying an annotation
            satisfies ast/valid?"
    (doseq [[label node quote-text annotation]
            [["highlight" (para (s "aa") {:t :strong :inlines [(s "BB")]} (s "cc")) "aBBc"
              {:highlight highlight-template}]
             ["sticky" (para (s "one two three")) "two" {:sticky-note (sticky "N")}]
             ["both" (para (s "one two three")) "two"
              {:highlight highlight-template :sticky-note (sticky "N")}]
             ["nested" (para (s "x ") {:t :emph :inlines [(s "yyzz")]} (s " w")) "x yy"
              {:highlight highlight-template}]]]
      (testing label
        (let [[document _] (annotate node quote-text annotation)]
          (is (ast/valid? ast/Block document)
              (pr-str (ast/explain ast/Block document)))))))
  (testing "a whole Document, reached at ::ast/document, is validated as
            one rather than as a Block"
    (let [document {:meta {} :blocks [(para (s "hazelnuts ripen here"))]}
          {matched :range} (anchor/resolve-anchor ::ast/document document {:text "ripen"})
          {:keys [document diagnostics]}
          (anchor/apply-annotation ::ast/document document matched
                                   {:highlight highlight-template})]
      (is (= [] (mapv :type diagnostics)))
      (is (ast/valid? ast/Document document)))))

(deftest a-placement-failure-drops-the-annotation-whole-test
  (testing "AC #9: a range whose unit has since been rewritten is refused
            -- applying it at moved offsets is exactly the
            plausible-looking-wrong-document this whole design exists to
            avoid -- and the document comes back untouched"
    (let [node (para (s "one two three"))
          {matched :range} (anchor/resolve-anchor node {:text "two"})
          edited (para (s "a sentence inserted first. one two three"))
          {:keys [document diagnostics]}
          (anchor/apply-annotation edited matched {:highlight highlight-template})]
      (is (= edited document) "unchanged, not half-applied")
      (is (= [:annotation-placement-failed] (mapv :type diagnostics)))
      (is (str/includes? (:message (first diagnostics)) "dropped whole"))))
  (testing "AC #9: an annotation with nothing to place is a placement
            failure too, rather than a silent no-op"
    (let [node (para (s "one two three"))
          {matched :range} (anchor/resolve-anchor node {:text "two"})
          {:keys [document diagnostics]} (anchor/apply-annotation node matched {})]
      (is (= node document))
      (is (= [:annotation-placement-failed] (mapv :type diagnostics)))))
  (testing "AC #9: and a result that would not validate is refused, with
            the document left exactly as it was"
    (let [node (para (s "one two three"))
          {matched :range} (anchor/resolve-anchor node {:text "two"})
          {:keys [document diagnostics]}
          (anchor/apply-annotation node matched
                                   {:highlight {:t :highlight :inlines []}})]
      (is (= node document) "an :attr-less :highlight is not a valid Inline")
      (is (= [:annotation-placement-failed] (mapv :type diagnostics))))))

(deftest a-malformed-range-or-annotation-is-refused-test
  (testing "TASK-118 review: an INVERTED range used to emit both outer
            pieces of a split :str and duplicate the text between them --
            silently, in a document ast/valid? still accepted. It is
            refused before anything is rewritten"
    (let [node (para (s "one two three"))
          {matched :range} (anchor/resolve-anchor node {:text "two"})
          {:keys [document diagnostics]}
          (anchor/apply-annotation node (assoc matched :start 9 :end 2)
                                   {:highlight highlight-template})]
      (is (= node document) "unchanged, and in particular not text-duplicated")
      (is (= [:annotation-placement-failed] (mapv :type diagnostics)))
      (is (= (select/flatten-text node) (select/flatten-text document)))))
  (testing "and so is a range running past the end of the run it names,
            which used to drop the annotation with no diagnostic at all"
    (let [node (para (s "one two three"))
          {matched :range} (anchor/resolve-anchor node {:text "two"})]
      (doseq [[label bad] [["past the end" {:start 100 :end 200}]
                           ["negative start" {:start -1 :end 3}]
                           ["non-integer" {:start "a" :end "b"}]]]
        (testing label
          (let [{:keys [document diagnostics]}
                (anchor/apply-annotation node (merge matched bad)
                                         {:highlight highlight-template})]
            (is (= node document))
            (is (= [:annotation-placement-failed] (mapv :type diagnostics))))))))
  (testing "TASK-118 review: a node to place that is not one threw
            ClassCastException out of `assoc` rather than being
            diagnosed. apply-annotation is advertised for a --transform
            rule to call directly, so a rule's own mistake has to come
            back as a diagnostic like every other"
    (let [node (para (s "one two three"))
          {matched :range} (anchor/resolve-anchor node {:text "two"})]
      (doseq [[label annotation] [["a string highlight" {:highlight "oops"}]
                                  ["a string sticky note" {:sticky-note "oops"}]
                                  ["the wrong node type" {:highlight (sticky "x")}]]]
        (testing label
          (let [{:keys [document diagnostics]} (anchor/apply-annotation node matched annotation)]
            (is (= node document))
            (is (= [:annotation-placement-failed] (mapv :type diagnostics)))))))))

(deftest apply-time-diagnostics-name-the-annotation-test
  (testing "TASK-118 review: an :annotation-anchor-snapped that cannot
            say which quote snapped is a warning an author has nowhere to
            take. resolve-anchor copies the quote and target onto every
            range it returns, exactly as its own three diagnostics
            already name both"
    (let [node (section "sec:intro" (para (s "see ") {:t :code :text "foobar"} (s " end")))
          {matched :range} (anchor/resolve-anchor node {:text "see foo" :target "sec:intro"})
          {:keys [diagnostics]} (anchor/apply-annotation node matched
                                                         {:highlight highlight-template})
          d (first diagnostics)]
      (is (= :annotation-anchor-snapped (:type d)))
      (is (= "see foo" (:quote d)))
      (is (= "sec:intro" (:target d)))
      (is (str/includes? (:message d) "see foo"))
      (is (str/includes? (:message d) "sec:intro"))))
  (testing "the message says which boundary actually fell inside the node
            -- it used to claim the run 'ends' there however it began"
    (let [begins (para (s "x") {:t :code :text "abcdef"} (s " tail"))
          {matched :range} (anchor/resolve-anchor begins {:text "def tail"})
          {:keys [diagnostics]} (anchor/apply-annotation begins matched
                                                         {:highlight highlight-template})]
      (is (str/includes? (:message (first diagnostics)) "begins inside"))))
  (testing "and reads with the right article -- 'an image', not 'a image'"
    (let [node (para (s "see ") {:t :image :src "x.png" :alt "a tree" :attr attr} (s " end"))
          {matched :range} (anchor/resolve-anchor node {:text "see a"})
          {:keys [diagnostics]} (anchor/apply-annotation node matched
                                                         {:highlight highlight-template})]
      (is (= [:annotation-anchor-snapped] (mapv :type diagnostics)))
      (is (str/includes? (:message (first diagnostics)) "an image"))))
  (testing "a sticky note's edge landing inside an indivisible node says
            it MOVED, not that anything was widened -- a point cannot be"
    (let [node (para (s "a ") {:t :code :text "xyz"} (s " b"))
          {matched :range} (anchor/resolve-anchor node {:text "a xy"})
          {:keys [diagnostics]} (anchor/apply-annotation node matched
                                                         {:sticky-note (sticky "N") :at :end})]
      (is (= [:annotation-anchor-snapped] (mapv :type diagnostics)))
      (is (str/includes? (:message (first diagnostics)) "was moved to"))
      (is (not (str/includes? (:message (first diagnostics)) "widened")))))
  (testing "TASK-118 review: a highlight AND a sticky note over a snapped
            run used to emit a SECOND diagnostic naming the :highlight
            the very same call had just created. The note's edge is
            measured against the snapped bounds, so there is only ever
            the one real diagnostic"
    (let [node (para (s "see ") {:t :code :text "foobar"} (s " end"))
          {matched :range} (anchor/resolve-anchor node {:text "see foo"})
          {:keys [diagnostics]} (anchor/apply-annotation
                                 node matched {:highlight highlight-template
                                               :sticky-note (sticky "N") :at :end})]
      (is (= [:annotation-anchor-snapped] (mapv :type diagnostics)))
      (is (not= :highlight (:node-type (first diagnostics)))
          "and it names the :code that actually snapped, not the new :highlight"))))

(deftest one-anchor-produces-one-annotation-id-test
  (testing "TASK-118 review: an anchor partly covering a container emits
            several :highlight nodes at different depths, and copying the
            annotation's own id into each made one annotation report
            itself to the resolver as a duplicate id. Only the outermost
            keeps it"
    (let [node (para (s "x ") {:t :emph :inlines [(s "yyzz")]} (s " w"))
          {matched :range} (anchor/resolve-anchor node {:text "x yy"})
          {:keys [document]} (anchor/apply-annotation
                              node matched
                              {:highlight (assoc highlight-template :attr (id-attr "hl:1"))})
          ids (->> (tree-seq coll? seq document)
                   (filter #(and (map? %) (= :highlight (:t %))))
                   (keep #(get-in % [:attr :id])))]
      (is (= 2 (count (filter #(and (map? %) (= :highlight (:t %)))
                              (tree-seq coll? seq document))))
          "two highlights, at two depths")
      (is (= ["hl:1"] (vec ids)) "and exactly one of them carries the id"))))

(deftest range-entirely-inside-one-container-test
  (testing "wrap-inlines' own docstring names this case: when the range
            lies entirely inside one container, the covered run is empty
            and NO top-level highlight is emitted -- the only :highlight
            is the nested one the recursion produced"
    (let [node (para (s "a ") {:t :emph :inlines [(s "bcde")]} (s " f"))
          [document diagnostics] (annotate node "cd")]
      (is (= [] diagnostics))
      (is (= [(s "a ")
              {:t :emph :inlines [(s "b")
                                  {:t :highlight :inlines [(s "cd")] :attr attr}
                                  (s "e")]}
              (s " f")]
             (inlines-of document)))
      (is (= 1 (count (filter #(and (map? %) (= :highlight (:t %)))
                              (tree-seq coll? seq document))))
          "exactly one highlight, and it is the nested one"))))

(deftest a-boundary-on-an-atomic-edge-does-not-snap-test
  (testing "snapping is for a boundary INSIDE an atomic leaf. One that
            falls exactly on its edge needs no widening and must not
            report any -- a diagnostic for a range that was already
            correct is noise an author learns to ignore"
    (doseq [[label quote-text] [["ending at the leaf's start" "see "]
                                ["starting at the leaf's end" " end"]
                                ["covering the leaf whole" "see foobar end"]]]
      (testing label
        (let [node (para (s "see ") {:t :code :text "foobar"} (s " end"))
              [_ diagnostics] (annotate node quote-text)]
          (is (= [] diagnostics)))))))

(deftest snapping-covers-every-atomic-leaf-type-test
  (testing "AC #5 for the atomic leaves beyond :code and :math-inline --
            an :image and a :cite each have text and nothing to split"
    (doseq [[label leaf quote-text]
            [[":image" {:t :image :src "x.png" :alt "a tree" :attr attr} "start a"]
             [":cite" {:t :cite :items [{:key "k" :mode :normal
                                         :prefix [(s "see p3")]}]} "start see"]]]
      (testing label
        (let [node (para (s "start ") leaf (s " end"))
              [document diagnostics] (annotate node quote-text)]
          (is (= [:annotation-anchor-snapped] diagnostics))
          (is (= (select/flatten-text node) (select/flatten-text document))))))))

(deftest annotations-apply-at-every-unit-path-test
  (testing "an annotation is applied wherever a search unit lives, not
            only in a Para's own :inlines -- the range's :path has to
            round-trip through assoc-in for a heading, a caption, a list
            item and a table cell alike"
    (let [needle (s "cold frames")
          containers
          {:heading {:t :section :level 1 :heading [needle] :blocks [] :attr attr}
           :figure-caption {:t :figure :content (para (s "x")) :caption [needle] :attr attr}
           :table-cell {:t :table :head {:cells [{:blocks [(para needle)]}]} :rows []
                        :caption [] :colspec [] :attr attr}
           :list-item {:t :list :ordered false :tight true :items [[(para needle)]] :attr attr}
           :footnote-body (para {:t :note :blocks [(para needle)]})}]
      (doseq [[container node] containers]
        (testing (str container)
          (let [{matched :range} (anchor/resolve-anchor node {:text "cold frames"})
                {:keys [document diagnostics]}
                (anchor/apply-annotation node matched {:highlight highlight-template})]
            (is (= [] (mapv :type diagnostics)))
            (is (ast/valid? ast/Block document)
                (pr-str (ast/explain ast/Block document)))
            (is (= (select/flatten-text node) (select/flatten-text document)))
            (is (some #(and (map? %) (= :highlight (:t %))) (tree-seq coll? seq document))
                "the highlight really was spliced in")))))))

(ns haselnuss.walk-test
  "Fixtures for the schema-derived AST traversal (`haselnuss.walk`,
  TASK-84): `derive-table` parses `:one`/`:many`/`:many-of-many` reference
  shapes correctly from small synthetic schemas, independent of the real
  AST's size, ignores an `:optional` marker, finds a `:multi`'s own
  discriminator field while excluding it from the children it reports, and
  ignores any registry entry not namespaced `haselnuss.ast` (AC #1); the
  real `haselnuss.walk/table` matches doc-6's own worked example verbatim
  and covers Cell/Row/CiteItem/Meta/Document too (AC #1); every field
  appears in schema declaration order, both in the table itself and
  behaviorally, in the order `fold` actually visits nodes (AC #2);
  `rewrite` expands a list-valued position to zero, one or many nodes (AC
  #3) and raises naming the field when a single-valued position comes back
  some other count (AC #4); `fold` visits in document order and reports
  the path to each node, for a `:one`, a `:many` and a `:many-of-many`
  position alike (AC #5); a `:scope` set controls which reference kinds
  either primitive descends into (AC #6); and a canary fails if any
  Block/Inline/Fallback variant in `haselnuss.ast/registry` has no entry
  in the derived table (AC #7)."
  (:require [clojure.test :refer [deftest is testing]]
            [haselnuss.ast :as ast]
            [haselnuss.walk :as walk]))

;; --- shared fixtures -------------------------------------------------------

(def attr
  "A minimal Attr with no `:id` set, per `haselnuss.ast-test`'s own `attr`
  fixture convention."
  {:classes [] :props {}})

(defn- str-inline
  [text]
  {:t :str :text text})

(defn- para
  [& inlines]
  {:t :para :inlines (vec inlines)})

(def block-and-inline
  "The scope most tests use: descend through both Block- and Inline-typed
  positions, mirroring `resolve-block`/`resolve-inline`'s combined
  coverage."
  #{::ast/block ::ast/inline})

;; --- derive-table: parsing rules, pinned against small synthetic schemas --
;; (AC #1) --------------------------------------------------------------

(deftest derive-table-detects-one-many-and-many-of-many-test
  (testing "a bare [:ref k] is :one, [:vector [:ref k]] is :many, and
            [:vector [:vector [:ref k]]] is :many-of-many; a plain scalar
            or a [:vector :string] carries no reference at all"
    (let [registry {::ast/widget
                    [:map
                     [:t [:= :widget]]
                     [:single [:ref ::ast/widget]]
                     [:many [:vector [:ref ::ast/widget]]]
                     [:nested [:vector [:vector [:ref ::ast/widget]]]]
                     [:label :string]
                     [:tags [:vector :string]]]}
          derived (walk/derive-table registry)]
      (is (= [[:single [:one ::ast/widget]]
              [:many [:many ::ast/widget]]
              [:nested [:many-of-many ::ast/widget]]]
             (get-in derived [:children :widget]))))))

(deftest derive-table-ignores-optionality-test
  (testing "an :optional field is parsed exactly like a required one -- a
            position's presence at runtime, not its schema optionality, is
            what rewrite/fold act on"
    (let [registry {::ast/widget
                    [:map
                     [:t [:= :widget]]
                     [:a {:optional true} [:ref ::ast/widget]]
                     [:b [:ref ::ast/widget]]]}
          derived (walk/derive-table registry)]
      (is (= [[:a [:one ::ast/widget]] [:b [:one ::ast/widget]]]
             (get-in derived [:children :widget]))))))

(deftest derive-table-finds-discriminator-and-excludes-tag-field-test
  (testing "the [:= tag] field is recorded as the multi's own discriminator
            and never itself appears as a child-bearing field"
    (let [registry {::ast/widget
                    [:multi {:dispatch (fn [n] (keyword (:kind n)))}
                     [:a [:map [:kind [:= :a]] [:xs [:vector [:ref ::ast/widget]]]]]
                     [:b [:map [:kind [:= :b]]]]]}
          derived (walk/derive-table registry)]
      (is (= {::ast/widget :kind} (:discriminators derived)))
      (is (= [[:xs [:many ::ast/widget]]] (get-in derived [:children :a])))
      (is (= [] (get-in derived [:children :b]))))))

(deftest derive-table-keys-a-plain-map-schema-by-its-bare-registry-name-test
  (testing "a registry entry with no :multi has no tag of its own, so its
            table entry is keyed by the un-namespaced registry key, and it
            gets no discriminators entry at all"
    (let [registry {::ast/widget [:map [:xs [:vector [:ref ::ast/widget]]]]}
          derived (walk/derive-table registry)]
      (is (= [[:xs [:many ::ast/widget]]] (get-in derived [:children :widget])))
      (is (not (contains? (:discriminators derived) ::ast/widget))))))

(deftest derive-table-ignores-non-ast-namespaced-registry-entries-test
  (testing "only haselnuss.ast's own registry keys are walked -- an
            unnamespaced or foreign-namespaced key (malli's own
            default-schemas, merged into the same registry purely so
            [:ref ...] resolves) contributes nothing"
    (let [registry {:string :some-malli-internal-schema
                    :other.ns/thing [:map [:xs [:vector [:ref ::ast/widget]]]]
                    ::ast/widget [:map]}
          derived (walk/derive-table registry)]
      (is (= #{:widget} (set (keys (:children derived))))))))

;; --- table: the real derivation, checked against doc-6's own example -----
;; (AC #1) -----------------------------------------------------------------

(deftest table-matches-doc-6-worked-example-test
  (testing "AC #1: doc-6's own worked example, quoted verbatim, for the
            four node types it names"
    (is (= [[:heading [:many ::ast/inline]]
            [:blocks [:many ::ast/block]]
            [:attr [:one ::ast/attr]]]
           (get-in walk/table [:children :section])))
    (is (= [[:head [:one ::ast/row]]
            [:rows [:many ::ast/row]]
            [:caption [:many ::ast/inline]]
            [:colspec [:many ::ast/col]]
            [:attr [:one ::ast/attr]]]
           (get-in walk/table [:children :table])))
    (is (= [[:items [:many-of-many ::ast/block]]
            [:attr [:one ::ast/attr]]]
           (get-in walk/table [:children :list])))
    (is (= [[:prefix [:many ::ast/inline]]
            [:suffix [:many ::ast/inline]]]
           (get-in walk/table [:children :cite-item])))))

(deftest table-covers-cell-row-meta-and-document-test
  (testing "AC #1: the registry entries with no :t/:kind of their own"
    (is (= [[:blocks [:many ::ast/block]]] (get-in walk/table [:children :cell])))
    (is (= [[:cells [:many ::ast/cell]]] (get-in walk/table [:children :row])))
    (is (= [[:title [:many ::ast/inline]]] (get-in walk/table [:children :meta])))
    (is (= [[:meta [:one ::ast/meta]] [:blocks [:many ::ast/block]]]
           (get-in walk/table [:children :document])))
    (is (= {::ast/block :t ::ast/inline :t ::ast/fallback :kind}
           (:discriminators walk/table)))))

(defn- schema-variant-tags
  "Every variant tag of a `[:multi {:dispatch fn} & variants]` schema --
  re-derives the same tag list `haselnuss.walk/derive-table` does, but by
  the simplest possible independent read of the raw shape (just tags,
  nothing about fields or arity), so this is a genuine cross-check against
  the derivation rather than a restatement of it."
  [multi-schema]
  (map first (drop 2 multi-schema)))

(deftest every-schema-variant-has-a-table-entry-test
  (testing "AC #7: canary -- every Block/Inline/Fallback variant tag in
            haselnuss.ast/registry has a haselnuss.walk/table entry, so a
            variant added to the schema without walker support fails here
            rather than being silently skipped downstream"
    (let [tags (mapcat #(schema-variant-tags (get ast/registry %))
                       [::ast/block ::ast/inline ::ast/fallback])]
      (is (= 33 (count tags)) "11 Block + 18 Inline + 4 Fallback variants")
      (doseq [tag tags]
        (testing (str tag)
          (is (contains? (:children walk/table) tag)))))))

;; --- schema declaration order (AC #2) --------------------------------------

(deftest children-preserve-schema-declaration-order-test
  (testing "AC #2: a node type's own child-bearing fields are ordered
            exactly as sec4.3/sec4.4 declare them, not by map hash order"
    (is (= [:head :rows :caption :colspec :attr]
           (mapv first (get-in walk/table [:children :table]))))
    (is (= [:blocks :attr :fallback]
           (mapv first (get-in walk/table [:children :directive]))))))

;; --- fold: document order + path reporting (AC #2 behaviorally, #5) ------

(defn- visited
  "Runs `fold` over `node` (of reference kind `ref-kind`) with `scope`,
  collecting `[path type]` for every node visited, in visiting order --
  `type` is `node-type`'s own resolved type key, e.g. `:section`/`:cell`."
  [scope ref-kind node]
  (walk/fold {:table walk/table :scope scope
              :visit (fn [acc path visited-kind visited-node]
                       (conj acc [path (walk/node-type (:discriminators walk/table)
                                                       visited-kind visited-node)]))}
             [] ref-kind node))

(deftest fold-visits-a-section-in-document-order-with-paths-test
  (testing "AC #2/#5: heading before blocks (declaration order), each
            list-valued position's own children in vector order, a node
            visited before its descendants -- and the exact path to each"
    (let [section {:t :section :level 1
                   :heading [(str-inline "H1") (str-inline "H2")]
                   :blocks [(para (str-inline "a")) (para (str-inline "b"))]
                   :attr attr}]
      (is (= [[[] :section]
              [[:heading 0] :str]
              [[:heading 1] :str]
              [[:blocks 0] :para]
              [[:blocks 0 :inlines 0] :str]
              [[:blocks 1] :para]
              [[:blocks 1 :inlines 0] :str]]
             (visited block-and-inline ::ast/block section))))))

(deftest fold-reports-a-many-of-many-path-as-outer-then-inner-index-test
  (testing "AC #5: List's :items (:many-of-many) paths as [:items outer
            inner], not a single flattened index"
    (let [a-list {:t :list :ordered false :tight true
                  :items [[(para (str-inline "a"))]
                          [(para (str-inline "b")) (para (str-inline "c"))]]
                  :attr attr}]
      (is (= [[[] :list]
              [[:items 0 0] :para]
              [[:items 0 0 :inlines 0] :str]
              [[:items 1 0] :para]
              [[:items 1 0 :inlines 0] :str]
              [[:items 1 1] :para]
              [[:items 1 1 :inlines 0] :str]]
             (visited block-and-inline ::ast/block a-list))))))

(deftest fold-reports-a-one-position-path-with-no-index-test
  (testing "AC #5: a :one position (Figure's :content) has a path with no
            trailing index -- there is only ever one node there"
    (let [figure {:t :figure :content (para (str-inline "x"))
                  :caption [(str-inline "cap")] :attr attr}]
      (is (= [[[] :figure]
              [[:content] :para]
              [[:content :inlines 0] :str]
              [[:caption 0] :str]]
             (visited block-and-inline ::ast/block figure))))))

(deftest fold-reaches-cell-and-row-kinds-when-scoped-in-test
  (testing "AC #5/#6: a Table's :head/:rows (::row) and a Row's :cells
            (::cell) are only reached when those reference kinds are in
            scope, and the path threads through them like any other
            position"
    (let [scope (conj block-and-inline ::ast/row ::ast/cell)
          a-table {:t :table
                   :head {:cells [{:blocks [(para (str-inline "h1"))]}]}
                   :rows [{:cells [{:blocks [(para (str-inline "r1c1"))]}
                                   {:blocks [(para (str-inline "r1c2"))]}]}]
                   :caption [] :colspec [] :attr attr}]
      (is (= [[[] :table]
              [[:head] :row]
              [[:head :cells 0] :cell]
              [[:head :cells 0 :blocks 0] :para]
              [[:head :cells 0 :blocks 0 :inlines 0] :str]
              [[:rows 0] :row]
              [[:rows 0 :cells 0] :cell]
              [[:rows 0 :cells 0 :blocks 0] :para]
              [[:rows 0 :cells 0 :blocks 0 :inlines 0] :str]
              [[:rows 0 :cells 1] :cell]
              [[:rows 0 :cells 1 :blocks 0] :para]
              [[:rows 0 :cells 1 :blocks 0 :inlines 0] :str]]
             (visited scope ::ast/block a-table))))))

;; --- scope filters descent (AC #6) -----------------------------------------

(deftest scope-excludes-a-reference-kind-from-both-primitives-test
  (testing "AC #6: a scope without ::inline never reaches a Section's
            :heading or a Para's :inlines, for fold or rewrite alike"
    (let [section {:t :section :level 1 :heading [(str-inline "H")]
                   :blocks [(para (str-inline "H"))] :attr attr}
          block-only #{::ast/block}]
      (testing "fold"
        (is (= [[[] :section] [[:blocks 0] :para]]
               (visited block-only ::ast/block section))))
      (testing "rewrite"
        (let [dup (fn [ref-kind node]
                    (if (and (= ref-kind ::ast/inline) (= :str (:t node)))
                      [node node]
                      [node]))
              result (first (walk/rewrite {:table walk/table :scope block-only :rewrite-fn dup}
                                          ::ast/block section))]
          (is (= 1 (count (:heading result))))
          (is (= 1 (count (:inlines (first (:blocks result)))))))))))

(deftest every-ref-kind-is-the-union-of-every-children-ref-kind-test
  (testing "AC #6: every-ref-kind gives a caller the 'no filtering' scope --
            pinned to its exact value so a change to derive-table's ref-kind
            extraction that silently narrowed it would fail here"
    (is (= #{::ast/attr ::ast/block ::ast/cell ::ast/cite-item ::ast/col
             ::ast/fallback ::ast/inline ::ast/meta ::ast/row}
           walk/every-ref-kind))))

;; --- rewrite: 0/1/many at list positions (AC #3) ---------------------------

(deftest rewrite-expands-a-many-position-to-zero-one-or-many-test
  (testing "AC #3: each element of a :many position independently vanishes,
            stays one node, or fans out to several"
    (let [rewrite-fn (fn [ref-kind node]
                       (if (and (= ref-kind ::ast/inline) (= :str (:t node)))
                         (case (:text node) "dup" [node node] "drop" [] [node])
                         [node]))
          section {:t :section :level 1 :heading []
                   :blocks [(para (str-inline "a") (str-inline "dup")
                                  (str-inline "drop") (str-inline "b"))]
                   :attr attr}
          result (first (walk/rewrite {:table walk/table :scope block-and-inline
                                       :rewrite-fn rewrite-fn}
                                      ::ast/block section))]
      (is (= ["a" "dup" "dup" "b"]
             (map :text (:inlines (first (:blocks result)))))))))

(deftest rewrite-many-of-many-rewrites-each-inner-list-independently-test
  (testing "AC #3: List's :items keeps its own outer count (one per
            authored item) while each item's own Blocks can still expand"
    (let [directive->many (fn [ref-kind node]
                            (if (and (= ref-kind ::ast/block) (= :directive (:t node)))
                              [(para (str-inline "x")) (para (str-inline "y"))]
                              [node]))
          directive {:t :directive :name "n" :blocks [] :attr attr}
          a-list {:t :list :ordered false :tight true
                  :items [[directive] [(para (str-inline "kept"))]]
                  :attr attr}
          result (first (walk/rewrite {:table walk/table :scope block-and-inline
                                       :rewrite-fn directive->many}
                                      ::ast/block a-list))]
      (is (= [2 1] (map count (:items result))))
      (is (= ["x" "y"] (map (comp :text first :inlines) (first (:items result))))))))

;; --- rewrite: an arity error at a single-valued position (AC #4) ---------

(deftest rewrite-raises-naming-the-field-when-a-one-position-gets-the-wrong-count-test
  (testing "AC #4: a :one position (Figure's :content) that comes back with
            a count other than one raises, naming the field and the node"
    (let [duplicate-content (fn [ref-kind node]
                              (if (and (= ref-kind ::ast/block) (= :para (:t node)))
                                [node node]
                                [node]))
          figure {:t :figure :content (para (str-inline "x")) :caption [] :attr attr}]
      (try
        (walk/rewrite {:table walk/table :scope block-and-inline :rewrite-fn duplicate-content}
                      ::ast/block figure)
        (is false "expected ::walk/arity-error to be thrown")
        (catch clojure.lang.ExceptionInfo e
          (is (re-find #":content" (.getMessage e)))
          (is (re-find #":figure" (.getMessage e)))
          (let [data (ex-data e)]
            (is (= ::walk/arity-error (:type data)))
            (is (= :content (:field data)))
            (is (= 2 (:count data)))
            (is (= :figure (:t (:node data))))))))))

(deftest rewrite-recurses-into-a-freshly-substituted-nodes-own-children-test
  (testing "a node substituted in by rewrite-fn is itself walked afterwards
            -- mirrors lower-directive substituting a Fallback and then
            still lowering whatever it substituted in"
    (let [substitute-then-dup
          (fn [ref-kind node]
            (cond
              (and (= ref-kind ::ast/block) (= :directive (:t node)))
              [{:t :figure :content (para (str-inline "z")) :caption [] :attr attr}]

              (and (= ref-kind ::ast/inline) (= :str (:t node)))
              [node node]

              :else [node]))
          directive {:t :directive :name "n" :blocks [] :attr attr}
          result (first (walk/rewrite {:table walk/table :scope block-and-inline
                                       :rewrite-fn substitute-then-dup}
                                      ::ast/block directive))]
      (is (= :figure (:t result)))
      (is (= ["z" "z"] (map :text (:inlines (:content result))))))))

;; --- one call from ::document reaches meta.title AND blocks (AC #1) ------

(deftest rewrite-rooted-at-document-reaches-meta-title-and-blocks-together-test
  (testing "AC #1: Document is itself a walkable kind, so meta.title and
            the top-level blocks are both reached from a single rewrite
            call rather than two separately hand-wired ones"
    (let [dup (fn [ref-kind node]
                (if (and (= ref-kind ::ast/inline) (= :str (:t node)))
                  [node node]
                  [node]))
          scope (conj block-and-inline ::ast/meta)
          a-document {:meta {:title [(str-inline "t")]} :blocks [(para (str-inline "b"))]}
          result (first (walk/rewrite {:table walk/table :scope scope :rewrite-fn dup}
                                      ::ast/document a-document))]
      (is (= 2 (count (get-in result [:meta :title]))))
      (is (= 2 (count (:inlines (first (:blocks result)))))))))

;; --- node-type (AC #1 support) ---------------------------------------------

(deftest node-type-dispatches-multi-kinds-and-falls-back-for-plain-maps-test
  (testing "a :multi kind's type comes from its own discriminator field; a
            plain-map kind (no tag of its own) always resolves to its bare
            registry name"
    (let [discriminators (:discriminators walk/table)]
      (is (= :section (walk/node-type discriminators ::ast/block {:t :section})))
      (is (= :poster (walk/node-type discriminators ::ast/fallback {:kind :poster})))
      (is (= :cell (walk/node-type discriminators ::ast/cell {:blocks []})))
      (testing "a JSON-decoded tag arriving as a string still resolves"
        (is (= :section (walk/node-type discriminators ::ast/block {:t "section"})))))))

(ns haselnuss.lower-test
  "Fixtures for the `lower(target, registry)` pass (`haselnuss.lower/lower`,
  TASK-17), one per acceptance criterion: a directive with a native
  renderer registered for the target is left as-is, other than recursively
  lowering its own nested content (AC #1); a directive with no native
  renderer but a registry `:lower` rule is replaced by that rule's output,
  falling through to the directive's own Fallback when the rule declines
  (returns nil/empty) rather than erroring (AC #2); a directive with
  neither is replaced per its own Fallback -- `:blocks` splices in place,
  `:poster` synthesizes a referenceable Figure, `:transform` runs a
  caller-supplied named rule (and an unknown/declining rule name itself
  raises, since there is no further fallback once already applying the
  node's own), and `:drop` omits it entirely (AC #3); and a directive with
  no native renderer, no lower rule, and no Fallback raises `ex-info`
  naming the offending directive instead of silently dropping it (AC #4).
  Also covers traversal coverage (a directive nested in a Section, a List
  item, a Table cell, a footnote, and `meta.title`'s own footnote are all
  found and lowered) and that a directive-free document round-trips
  unchanged."
  (:require [clojure.test :refer [deftest is testing]]
            [haselnuss.ast :as ast]
            [haselnuss.lower :as lower]
            [haselnuss.registry :as registry]))

(def attr
  "A minimal Attr with no `:id` set, per `haselnuss.resolver-test`'s own
  `attr` fixture convention."
  {:classes [] :props {}})

(defn- id-attr
  [id]
  (assoc attr :id id))

(defn- directive
  ([directive-name] (directive directive-name nil []))
  ([directive-name id] (directive directive-name id []))
  ([directive-name id blocks] {:t :directive :name directive-name :blocks blocks
                               :attr (id-attr id)})
  ([directive-name id blocks fallback] (assoc (directive directive-name id blocks)
                                              :fallback fallback)))

(defn- para
  [& inlines]
  {:t :para :inlines (vec inlines)})

(defn- str-inline
  [text]
  {:t :str :text text})

(defn- doc
  [& blocks]
  {:meta {} :blocks (vec blocks)})

(deftest native-renderer-kept-as-is-test
  (testing "AC #1: a directive with a native renderer registered for the
            target is left as-is rather than replaced"
    (let [d (directive "collapsable" "id:1" [] {:kind :drop})
          registry (registry/register {} {:name "collapsable"
                                          :emit {:html (fn [_node _target] :ignored)}})
          lowered (lower/lower (doc d) :html registry)]
      (is (= d (first (:blocks lowered))))))
  (testing "the directive's own :blocks are still recursively lowered even
            though the directive itself is kept -- the emitter still
            walks into them, so any nested custom directive must already
            be lowered too"
    (let [inner (directive "mystery" "x:1" [] {:kind :drop})
          outer (directive "collapsable" "id:1" [inner])
          registry (registry/register {} {:name "collapsable"
                                          :emit {:html (fn [_node _target] :ignored)}})
          lowered (lower/lower (doc outer) :html registry)]
      (is (= "collapsable" (:name (first (:blocks lowered)))))
      (is (= [] (:blocks (first (:blocks lowered)))))))
  (testing "a renderer registered for a different target does not count
            -- the directive falls through to its own fallback instead"
    (let [d (directive "collapsable" "id:1" [] {:kind :drop})
          registry (registry/register {} {:name "collapsable"
                                          :emit {:latex (fn [_node _target] :ignored)}})
          lowered (lower/lower (doc d) :html registry)]
      (is (= [] (:blocks lowered))))))

(deftest registry-lower-rule-test
  (testing "AC #2: a directive with no native renderer but a registry
            lower rule is replaced with that rule's output"
    (let [d (directive "chart" "fig:sales")
          output [(para (str-inline "flattened"))]
          registry (registry/register {} {:name "chart" :lower (fn [_node _target] output)})
          lowered (lower/lower (doc d) :latex registry)]
      (is (= output (:blocks lowered)))))
  (testing "a lower rule can replace a single directive with several
            blocks at once"
    (let [d (directive "chart" "fig:sales")
          output [(para (str-inline "a")) (para (str-inline "b"))]
          registry (registry/register {} {:name "chart" :lower (fn [_node _target] output)})
          lowered (lower/lower (doc d) :latex registry)]
      (is (= output (:blocks lowered)))))
  (testing "a lower rule's own output is itself recursively lowered, in
            case it contains a further custom directive"
    (let [nested (directive "mystery" "x:1" [] {:kind :drop})
          d (directive "chart" "fig:sales")
          registry (registry/register {} {:name "chart" :lower (fn [_node _target] [nested])})
          lowered (lower/lower (doc d) :latex registry)]
      (is (= [] (:blocks lowered)))))
  (testing "a lower rule returning nil correctly falls through to the
            directive's own Fallback rather than erroring"
    (let [d (directive "chart" "fig:sales" [] {:kind :drop})
          registry (registry/register {} {:name "chart" :lower (fn [_node _target] nil)})
          lowered (lower/lower (doc d) :latex registry)]
      (is (= [] (:blocks lowered)))))
  (testing "a lower rule returning an empty vector is treated the same as
            nil -- also falls through to Fallback"
    (let [d (directive "chart" "fig:sales" [] {:kind :drop})
          registry (registry/register {} {:name "chart" :lower (fn [_node _target] [])})
          lowered (lower/lower (doc d) :latex registry)]
      (is (= [] (:blocks lowered))))))

(deftest fallback-blocks-test
  (testing "AC #3 (blocks): a directive with neither a native renderer nor
            a lower rule is flattened to its own Fallback's static blocks"
    (let [fallback-blocks [(para (str-inline "visible")) (para (str-inline "collapsed"))]
          d (directive "collapsable" "id:1" [] {:kind :blocks :blocks fallback-blocks})
          lowered (lower/lower (doc d) :latex {})]
      (is (= fallback-blocks (:blocks lowered)))))
  (testing "a directive nested inside a :blocks fallback's own content is
            itself lowered too"
    (let [nested (directive "mystery" "x:1" [] {:kind :drop})
          d (directive "collapsable" "id:1" [] {:kind :blocks :blocks [nested]})
          lowered (lower/lower (doc d) :latex {})]
      (is (= [] (:blocks lowered))))))

(deftest fallback-poster-test
  (testing "AC #3 (poster): substitutes a pre-rendered image, preserving
            the caption and the directive's own :attr (id) so the id
            already numbered during resolve remains anchorable -- sec8.4's
            own worked example, a chart's poster participating in figure
            numbering through its own #fig: id"
    (let [caption [(str-inline "Sales by quarter")]
          d (directive "chart" "fig:sales" [] {:kind :poster :src "chart.png" :caption caption})
          lowered (lower/lower (doc d) :latex {})
          figure (first (:blocks lowered))]
      (is (= 1 (count (:blocks lowered))))
      (is (= :figure (:t figure)))
      (is (= "fig:sales" (get-in figure [:attr :id])))
      (is (= caption (:caption figure)))
      (is (= {:t :para :inlines [{:t :image :src "chart.png" :alt ""
                                  :attr {:classes [] :props {}}}]}
             (:content figure)))
      (is (ast/valid? ast/Block figure))))
  (testing "a poster fallback with no explicit caption produces an empty one"
    (let [d (directive "chart" "fig:sales" [] {:kind :poster :src "chart.png"})
          lowered (lower/lower (doc d) :latex {})]
      (is (= [] (:caption (first (:blocks lowered))))))))

(deftest fallback-transform-test
  (testing "AC #3 (transform): runs a caller-supplied named rule"
    (let [output [(para (str-inline "computed"))]
          d (directive "babel" "id:1" [] {:kind :transform :rule "flatten-babel"})
          lowered (lower/lower (doc d) :latex {} {:transform-rules {"flatten-babel"
                                                                    (fn [_node _target] output)}})]
      (is (= output (:blocks lowered)))))
  (testing "an unregistered rule name raises a build error rather than
            silently dropping the node -- there is no further fallback
            once already inside applying the directive's own Fallback"
    (let [d (directive "babel" "id:1" [] {:kind :transform :rule "missing"})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"transform rule \"missing\""
                            (lower/lower (doc d) :latex {})))
      (try
        (lower/lower (doc d) :latex {})
        (is false "expected lower to throw")
        (catch clojure.lang.ExceptionInfo e
          (is (= ::lower/unavailable-transform-rule (:type (ex-data e))))
          (is (= "babel" (:name (ex-data e))))
          (is (= "missing" (:rule (ex-data e))))))))
  (testing "a registered rule that itself declines (returns nil) is
            treated the same as an unregistered one -- a build error, not
            a silent drop"
    (let [d (directive "babel" "id:1" [] {:kind :transform :rule "declines"})
          transform-rules {"declines" (fn [_node _target] nil)}]
      (is (thrown? clojure.lang.ExceptionInfo
                   (lower/lower (doc d) :latex {} {:transform-rules transform-rules}))))))

(deftest fallback-unrecognized-kind-test
  (testing "a :fallback with an unrecognized :kind raises a descriptive
            ex-info naming the offending directive and target, instead of
            letting a raw IllegalArgumentException escape from the
            underlying case dispatch"
    (let [d (directive "widget" "id:1" [] {:kind :bogus})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unrecognized fallback"
                            (lower/lower (doc d) :html {})))
      (try
        (lower/lower (doc d) :html {})
        (is false "expected lower to throw")
        (catch clojure.lang.ExceptionInfo e
          (is (= ::lower/unrecognized-fallback-kind (:type (ex-data e))))
          (is (= "widget" (:name (ex-data e))))
          (is (= "id:1" (:id (ex-data e))))
          (is (= :html (:target (ex-data e))))
          (is (= {:kind :bogus} (:fallback (ex-data e))))))))
  (testing "a :fallback with no :kind at all (nil) is treated the same way
            rather than crashing with an undescriptive
            \"No matching clause: nil\""
    (let [d (directive "widget" "id:1" [] {})]
      (try
        (lower/lower (doc d) :html {})
        (is false "expected lower to throw")
        (catch clojure.lang.ExceptionInfo e
          (is (= ::lower/unrecognized-fallback-kind (:type (ex-data e))))
          (is (= "widget" (:name (ex-data e))))
          (is (nil? (:kind (:fallback (ex-data e))))))))))

(deftest fallback-drop-test
  (testing "AC #3 (drop): omits the directive entirely, leaving
            surrounding sibling content untouched"
    (let [sibling-a (para (str-inline "before"))
          sibling-b (para (str-inline "after"))
          d (directive "widget" "id:1" [] {:kind :drop})
          lowered (lower/lower (doc sibling-a d sibling-b) :latex {})]
      (is (= [sibling-a sibling-b] (:blocks lowered))))))

(deftest no-representation-raises-test
  (testing "AC #4: a directive absent from the registry, with no lower
            rule and no fallback, raises instead of silently dropping it"
    (let [d (directive "mystery" "id:1")]
      (try
        (lower/lower (doc d) :html {})
        (is false "expected lower to throw")
        (catch clojure.lang.ExceptionInfo e
          (is (= ::lower/no-representation (:type (ex-data e))))
          (is (= "mystery" (:name (ex-data e))))
          (is (= "id:1" (:id (ex-data e))))
          (is (= :html (:target (ex-data e))))))))
  (testing "a directive registered but with no renderer for the target, no
            lower rule, and no fallback raises the same way -- being
            registered at all is not enough on its own"
    (let [d (directive "mystery" "id:1")
          registry (registry/register {} {:name "mystery"})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no representation"
                            (lower/lower (doc d) :html registry))))))

(deftest traversal-coverage-test
  (testing "a directive nested anywhere the tree reaches -- a Section's
            own blocks, a List item, a Table cell, a footnote nested in a
            Para, and meta.title's own footnote -- is found and lowered"
    (let [d (directive "mystery" "x:1" [] {:kind :drop})
          section {:t :section :level 1 :heading [] :blocks [d] :attr attr}
          a-list {:t :list :ordered false :tight true :items [[d]] :attr attr}
          table {:t :table :head {:cells [{:blocks [d]}]} :rows [] :caption [] :colspec []
                 :attr attr}
          note {:t :note :blocks [d]}
          para-with-footnote (para note)
          document {:meta {:title [note]}
                    :blocks [section a-list table para-with-footnote]}
          lowered (lower/lower document :latex {})]
      (is (= [] (get-in lowered [:blocks 0 :blocks])))
      (is (= [[]] (get-in lowered [:blocks 1 :items])))
      (is (= [] (get-in lowered [:blocks 2 :head :cells 0 :blocks])))
      (is (= [] (get-in lowered [:blocks 3 :inlines 0 :blocks])))
      (is (= [] (get-in lowered [:meta :title 0 :blocks])))))
  (testing "a document with no directives at all round-trips unchanged"
    (let [document (doc (para (str-inline "hello")))]
      (is (= document (lower/lower document :html {})))))
  (testing "a Figure/Table/BlockQuote's own structure is preserved when
            they carry no directive at all"
    (let [figure {:t :figure :content (para (str-inline "img")) :caption [] :attr attr}
          block-quote {:t :block-quote :blocks [(para (str-inline "quoted"))]}
          document (doc figure block-quote)]
      (is (= document (lower/lower document :html {}))))))

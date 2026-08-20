(ns haselnuss.registry-test
  "Fixtures for the extension registry (`haselnuss.registry`, TASK-16), one
  per acceptance criterion: an extension can be registered with a name,
  optional kind, per-target renderers, and an optional lower function (AC
  #1); looking up a directive's name returns its renderer for a given
  target when one is registered (AC #2); an unregistered name is
  distinguishable from a registered name with no renderer for the
  requested target (AC #3); and registering an extension with a kind
  makes that kind available to `haselnuss.resolver/number-document` (AC
  #4). Also checks the registry plugs directly into TASK-15's
  `structural-diagnostics`/`resolve-document` `:directive-registry` option
  with no adapter, per that task's own documented expectation."
  (:require [clojure.test :refer [deftest is testing]]
            [haselnuss.registry :as registry]
            [haselnuss.resolver :as resolver]))

(defn- attr
  [id]
  {:id id :classes [] :props {}})

(defn- directive
  [directive-name id]
  {:t :directive :name directive-name :blocks [] :attr (attr id)})

(deftest register-test
  (testing "AC #1: an extension can be registered with a name, an
            optional kind, per-target renderers, and an optional lower
            function -- every field round-trips through the entry"
    (let [html-renderer (fn [_node] :html-out)
          latex-renderer (fn [_node] :latex-out)
          lower-fn (fn [_node _target] nil)
          registry (registry/register {} {:name "chart"
                                          :kind {:fig {:counter :section-scoped
                                                       :words {"en" {:singular "Figure"}}}}
                                          :emit {:html html-renderer :latex latex-renderer}
                                          :lower lower-fn})
          entry (registry/lookup registry "chart")]
      (is (= "chart" (:name entry)))
      (is (= {:fig {:counter :section-scoped :words {"en" {:singular "Figure"}}}}
             (:kind entry)))
      (is (= html-renderer (get-in entry [:emit :html])))
      (is (= latex-renderer (get-in entry [:emit :latex])))
      (is (= lower-fn (:lower entry)))))
  (testing "every field except :name is optional -- registering with only
            a name still produces a usable entry with an empty renderer
            map and no kind/lower"
    (let [entry (registry/lookup (registry/register {} {:name "collapsable"}) "collapsable")]
      (is (= {:name "collapsable" :kind nil :emit {} :lower nil} entry))))
  (testing "registering the same name again replaces the previous entry
            entirely, mirroring plain assoc semantics"
    (let [registry (-> {}
                       (registry/register {:name "chart" :kind {:fig {}}})
                       (registry/register {:name "chart"}))]
      (is (nil? (:kind (registry/lookup registry "chart"))))))
  (testing "an extension with no :name throws instead of silently
            dropping it or registering it under a bogus key"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"name is required"
                          (registry/register {} {:kind {:fig {}}}))))
  (testing "a blank :name is rejected the same way"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"name is required"
                          (registry/register {} {:name ""}))))
  (testing "register-all folds register over a collection, later entries
            sharing a name winning over earlier ones"
    (let [registry (registry/register-all {} [{:name "chart" :kind {:fig {}}}
                                              {:name "form"}
                                              {:name "chart"}])]
      (is (= #{"chart" "form"} (set (keys registry))))
      (is (nil? (:kind (registry/lookup registry "chart")))))))

(deftest renderer-lookup-test
  (testing "AC #2: looking up a directive's name in the registry returns
            its renderer for a given target when one is registered"
    (let [html-renderer (fn [_node] :html-out)
          registry (registry/register {} {:name "chart" :emit {:html html-renderer}})]
      (is (= html-renderer (registry/renderer (registry/lookup registry "chart") :html)))
      (is (= html-renderer (registry/renderer-for registry "chart" :html))))))

(deftest distinguishability-test
  (testing "AC #3: a name with no registry entry at all is distinguishable
            from a name whose entry exists but has no renderer for the
            requested target"
    (let [registry (registry/register {} {:name "chart" :emit {:html (fn [_] :out)}})]
      (testing "via the composable lookup + renderer functions"
        (is (nil? (registry/lookup registry "form")))
        (is (some? (registry/lookup registry "chart")))
        (is (nil? (registry/renderer (registry/lookup registry "chart") :latex))))
      (testing "via the one-call renderer-for convenience, using two
                distinct, directly comparable tagged results"
        (is (= ::registry/unregistered (registry/renderer-for registry "form" :html)))
        (is (= ::registry/no-renderer (registry/renderer-for registry "chart" :latex)))
        (is (not= (registry/renderer-for registry "form" :html)
                  (registry/renderer-for registry "chart" :latex))))))
  (testing "an :emit target explicitly mapped to nil behaves the same as
            an absent target -- ::no-renderer, not a bare nil that could
            be confused with ::unregistered's own absence"
    (let [registry (registry/register {} {:name "chart" :emit {:html nil}})]
      (is (= ::registry/no-renderer (registry/renderer-for registry "chart" :html)))))
  (testing "an :emit target mapped to a non-callable value is returned
            as-is (this namespace never calls it, only stores it -- a
            renderer's call signature is an emitter-core concern this
            codebase has not yet decided, per register's docstring)"
    (let [registry (registry/register {} {:name "chart" :emit {:html :not-a-fn}})]
      (is (= :not-a-fn (registry/renderer-for registry "chart" :html))))))

(deftest numbering-lexicon-test
  (testing "AC #4: registering an extension with a kind makes that kind
            available to the numbering pass -- a document with no such
            registration numbers nothing under that kind, but the same
            document numbers correctly once number-document is given the
            registry-derived lexicon"
    ;; `frm` rather than the `alg` this used to use: TASK-58 made `alg`
    ;; a BUILT-IN kind, so a document using it now numbers with no
    ;; registration at all -- which would make the first assertion below,
    ;; the one that gives this test its meaning, vacuously false. A kind
    ;; the built-in lexicon does not know is what is being tested.
    (let [registry (registry/register {} {:name "form"
                                          :kind {:frm {:counter :global
                                                       :words {"en" {:singular "Form"}}}}})
          doc {:meta {} :blocks [(directive "form" "frm:one")
                                 (directive "form" "frm:two")]}]
      (is (empty? (resolver/number-document doc)))
      (is (= {"frm:one" {:kind :frm :path [1] :number "1" :word "Form" :text "Form 1"}
              "frm:two" {:kind :frm :path [2] :number "2" :word "Form" :text "Form 2"}}
             (resolver/number-document doc (registry/numbering-lexicon registry))))))
  (testing "registry-lexicon assembles every registered :kind fragment,
            deterministically by name when two extensions happen to
            register the same kind"
    (let [registry (-> {}
                       (registry/register {:name "b-ext" :kind {:alg {:counter :global}}})
                       (registry/register {:name "a-ext" :kind {:alg {:counter :section-scoped}}}))]
      (is (= {:alg {:counter :global}} (registry/registry-lexicon registry)))))
  (testing "numbering-lexicon merges the registry's kinds alongside a
            caller-supplied base, e.g. haselnuss.resolver/default-lexicon,
            without disturbing the base's own built-in kinds"
    ;; `frm` rather than `alg`: TASK-58 made `alg` a built-in, so this
    ;; registration OVERRODE an existing entry rather than being merged
    ;; alongside one -- and the property the testing string names, that a
    ;; registered kind arrives WITHOUT disturbing the base, was covered
    ;; by nothing (found by review).
    (let [registry (registry/register {} {:name "form" :kind {:frm {:counter :global}}})
          lexicon (registry/numbering-lexicon registry resolver/default-lexicon)]
      (is (= (:fig resolver/default-lexicon) (:fig lexicon)))
      (is (= (:alg resolver/default-lexicon) (:alg lexicon))
          "a built-in the registration says nothing about is untouched")
      (is (= {:counter :global} (:frm lexicon)))))
  (testing "and a registration that DOES name a built-in kind replaces
            that entry wholesale, which registry-lexicon's own docstring
            calls out as a hazard worth knowing about"
    (let [registry (registry/register {} {:name "algorithm" :kind {:alg {:counter :global}}})
          lexicon (registry/numbering-lexicon registry resolver/default-lexicon)]
      (is (= {:counter :global} (:alg lexicon)))
      (is (nil? (:words (:alg lexicon))))))
  (testing "an extension with no :kind contributes nothing to
            registry-lexicon"
    (let [registry (registry/register {} {:name "collapsable"})]
      (is (empty? (registry/registry-lexicon registry))))))

(deftest structural-diagnostics-integration-test
  (testing "the registry (a plain map from name to entry) plugs directly
            into haselnuss.resolver/structural-diagnostics's
            :directive-registry option via plain `contains?`, exactly as
            TASK-15 documented it would, with no adapter"
    (let [registry (registry/register {} {:name "chart"})
          doc {:meta {} :blocks [(directive "chart" "fig:sales")
                                 (directive "mystery" "fig:other")]}
          diagnostics (resolver/structural-diagnostics doc registry)]
      (is (= [{:type :unknown-directive :name "mystery" :id "fig:other"
               :message "unknown directive \"mystery\": not registered in the extension registry"}]
             diagnostics))
      (is (empty? (resolver/structural-diagnostics doc (registry/register registry {:name "mystery"})))))))

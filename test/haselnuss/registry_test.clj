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
            [haselnuss.resolver :as resolver]
            [sci.core :as sci]))

(defn- attr
  [id]
  {:id id :classes [] :props {}})

(defn- directive
  [directive-name id]
  {:t :directive :name directive-name :blocks [] :attr (attr id)})

(defn- sample-var-renderer
  "A `def`-backed renderer local to this test file, marked by
  `renderer-v2-test` -- never a shared `clojure.core` Var like `identity`,
  since `renderer-v2` mutates a Var's metadata in place (see its own
  docstring) and this suite must not leave that mutation on a Var some
  unrelated namespace also uses."
  [node ctx]
  [node ctx])

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
      (is (= lower-fn (:lower entry)))
      (is (false? (:trusted entry)) "defaults to untrusted when :trusted? is not supplied")))
  (testing "every field except :name is optional -- registering with only
            a name still produces a usable entry with an empty renderer
            map and no kind/lower"
    (let [entry (registry/lookup (registry/register {} {:name "collapsable"}) "collapsable")]
      (is (= {:name "collapsable" :kind nil :emit {} :lower nil :trusted false} entry))))
  (testing "TASK-89 AC #8: :trusted? true is stored as a boolean :trusted
            on the entry, coerced even from a truthy non-boolean value"
    (let [entry (registry/lookup (registry/register {} {:name "chart" :trusted? true}) "chart")]
      (is (true? (:trusted entry))))
    (let [entry (registry/lookup (registry/register {} {:name "chart" :trusted? :yes}) "chart")]
      (is (true? (:trusted entry)) "coerced to a real boolean, not stored as the truthy value itself")))
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

(deftest renderer-v2-test
  (testing "TASK-89: an unmarked renderer -- the legacy (fn [node target])
            shape every extension registered before TASK-89 used -- is not
            v2, which is what keeps every such registration calling and
            splicing exactly as it always has"
    (is (false? (registry/v2? (fn [_node _target] "html"))))
    (is (false? (registry/v2? nil)) "no renderer at all is not v2 either"))
  (testing "renderer-v2 marks a plain function, which stays just as
            callable as before -- an aware consumer calls it with ctx
            instead of target, but the function itself is unchanged"
    (let [f (fn [node ctx] [node ctx])
          marked (registry/renderer-v2 f)]
      (is (true? (registry/v2? marked)))
      (is (= [:n :c] (marked :n :c)) "still an ordinary 2-argument function")))
  (testing "renderer-v2 marks a Var in place (Var does not implement IObj,
            so with-meta/vary-meta cannot produce a new marked value the
            way they do for a plain function -- alter-meta! is the one
            that works, confirmed live over the nREPL before choosing it),
            returning the same Var rather than a value with-meta could
            have produced. Marks this test file's own local Var, never a
            shared clojure.core one -- alter-meta! is a real, process-wide
            mutation of whatever Var it is handed"
    (let [marked (registry/renderer-v2 #'sample-var-renderer)]
      (is (var? marked))
      (is (= #'sample-var-renderer marked))
      (is (true? (registry/v2? marked)))
      (is (true? (registry/v2? #'sample-var-renderer)) "the Var itself now carries the tag")))
  (testing "TASK-90: renderer-v2 marks a sci.lang.Var -- a DIFFERENT class
            from clojure.lang.Var, the shape a SCI-sandboxed sidecar
            file's own (defn ...) produces (haselnuss.extensions.sidecar)
            -- exactly the same way, since it shares the same relevant
            shape: no IObj (with-meta/vary-meta throw), but IReference
            (alter-meta! works). A throwaway sci context local to this
            test, never anything haselnuss.extensions.sidecar itself
            builds, since this is checking renderer-v2 in isolation"
    (let [sci-var (sci/eval-string* (sci/init {}) "(defn f [node ctx] [node ctx]) #'f")]
      (is (= "sci.lang.Var" (.getName (class sci-var))) "sanity: really a sci.lang.Var, not host Var")
      (is (false? (var? sci-var)) "and specifically not a clojure.lang.Var")
      (is (thrown? ClassCastException (vary-meta sci-var assoc ::x true))
          "confirms sci.lang.Var doesn't implement IObj either, the reason this branch exists")
      (let [marked (registry/renderer-v2 sci-var)]
        (is (= sci-var marked) "the same Var, marked in place")
        (is (true? (registry/v2? marked)))
        (is (true? (registry/v2? sci-var)) "the Var itself now carries the tag")
        (is (= [:n :c] (marked :n :c)) "still callable from host code exactly like the sci fn always was"))))
  (testing "renderer-v2 preserves any metadata the function already
            carried, rather than replacing it outright"
    (let [f (vary-meta (fn [_node _ctx] nil) assoc ::pre-existing true)
          marked (registry/renderer-v2 f)]
      (is (true? (::pre-existing (meta marked))))
      (is (true? (registry/v2? marked)))))
  (testing "a v2-marked renderer round-trips through register/lookup/
            renderer exactly like any other -- the marker travels with the
            function value stored in the registry, not with how it got
            there"
    (let [registry (registry/register {} {:name "chart"
                                          :emit {:html (registry/renderer-v2
                                                        (fn [node _ctx] node))}})]
      (is (true? (registry/v2? (registry/renderer (registry/lookup registry "chart") :html)))))))

(ns haselnuss.extensions.collapsable-test
  "Fixtures for the `collapsable` extension (`haselnuss.extensions.
  collapsable`, TASK-18), the reference built-in built on top of the
  extension registry (TASK-16) and the `lower` pass (TASK-17): a
  collapsable directive renders as an interactive `<details>`/`<summary>`
  element on the `:html` target (AC #1); the same directive renders as
  flattened static content on the `:latex` target via its own `:blocks`
  Fallback (AC #2); and it round-trips through `resolve`/`lower` for both
  targets without a build error (AC #3)."
  (:require [clojure.test :refer [deftest is testing]]
            [haselnuss.ast :as ast]
            [haselnuss.extensions.collapsable :as collapsable]
            [haselnuss.lower :as lower]
            [haselnuss.registry :as registry]
            [haselnuss.resolver :as resolver]))

(def ^:private registry-with-collapsable
  (registry/register {} collapsable/extension))

(defn- para
  [& inlines]
  {:t :para :inlines (vec inlines)})

(defn- str-inline
  [text]
  {:t :str :text text})

(deftest make-test
  (testing "builds a schema-valid :directive node carrying the summary in
            attr.props and the hidden content in :blocks"
    (let [hidden [(para (str-inline "hidden content"))]
          d (collapsable/make "Click to expand" hidden {:id "collapse:1" :classes [] :props {}})]
      (is (ast/valid? ast/Block d))
      (is (= "collapsable" (:name d)))
      (is (= hidden (:blocks d)))
      (is (= "collapse:1" (get-in d [:attr :id])))
      (is (= "Click to expand" (collapsable/summary d)))))
  (testing "derives a :blocks Fallback that flattens the summary (as an
            emphasized Para) ahead of the hidden content, unmodified --
            sec8.4's own \"flatten both parts inline\""
    (let [hidden [(para (str-inline "hidden content"))]
          d (collapsable/make "Click to expand" hidden)]
      (is (= :blocks (get-in d [:fallback :kind])))
      (is (= [(para {:t :strong :inlines [(str-inline "Click to expand")]})
              (para (str-inline "hidden content"))]
             (get-in d [:fallback :blocks])))
      (is (ast/valid? ast/Fallback (:fallback d)))))
  (testing "defaults to an empty, id-less attr when none is supplied"
    (let [d (collapsable/make "Click to expand" [])]
      (is (nil? (get-in d [:attr :id])))
      (is (= [] (get-in d [:attr :classes]))))))

(deftest render-html-ac1-test
  (testing "AC #1: a collapsable directive renders as an interactive
            expand/collapse element on the html target -- a native HTML5
            <details>/<summary> disclosure, always-visible summary plus
            the (collapsed) hidden content, no JavaScript required"
    (let [d (collapsable/make "Click to expand"
                              [(para (str-inline "Hidden content"))]
                              {:id "collapse:1" :classes [] :props {}})
          html (collapsable/render-html d :html)]
      (is (re-find #"<details id=\"collapse:1\" class=\"collapsable\">" html))
      (is (re-find #"<summary>Click to expand</summary>" html))
      (is (re-find #"<p>Hidden content</p>" html))
      (is (< (.indexOf html "<summary>") (.indexOf html "Hidden content")))))
  (testing "escapes HTML-significant characters in both the summary and the
            hidden content rather than splicing them raw"
    (let [d (collapsable/make "<script>&\""
                              [(para (str-inline "<b>&\""))])
          html (collapsable/render-html d :html)]
      (is (not (re-find #"<script>" html)))
      (is (re-find #"&lt;script&gt;&amp;&quot;" html))
      (is (re-find #"&lt;b&gt;&amp;&quot;" html))))
  (testing "renders emphasis/strong/line breaks in the hidden content"
    (let [d (collapsable/make "Click to expand"
                              [(para {:t :emph :inlines [(str-inline "em")]}
                                     {:t :strong :inlines [(str-inline "strong")]}
                                     {:t :line-break})])
          html (collapsable/render-html d :html)]
      (is (re-find #"<em>em</em>" html))
      (is (re-find #"<strong>strong</strong>" html))
      (is (re-find #"<br/>" html))))
  (testing "an unsupported block or inline type in the hidden content
            raises a descriptive ex-info rather than silently mangling or
            dropping it -- this renderer's own documented scope limit"
    (let [d (collapsable/make "x" [{:t :thematic-break}])]
      (try
        (collapsable/render-html d :html)
        (is false "expected render-html to throw")
        (catch clojure.lang.ExceptionInfo e
          (is (= ::collapsable/unsupported-block (:type (ex-data e)))))))
    (let [d (collapsable/make "x" [(para {:t :cross-ref :label "sec:x"})])]
      (try
        (collapsable/render-html d :html)
        (is false "expected render-html to throw")
        (catch clojure.lang.ExceptionInfo e
          (is (= ::collapsable/unsupported-inline (:type (ex-data e)))))))))

(deftest lower-ac1-native-renderer-kept-test
  (testing "AC #1/#3: lowering for :html keeps the collapsable directive
            as-is (its native renderer exists for that target), ready for
            render-html to be called on it -- no build error"
    (let [d (collapsable/make "Click to expand"
                              [(para (str-inline "Hidden content"))]
                              {:id "collapse:1" :classes [] :props {}})
          document {:meta {} :blocks [d]}
          lowered (lower/lower document :html registry-with-collapsable)
          kept (first (:blocks lowered))]
      (is (= d kept))
      (is (re-find #"<details" (collapsable/render-html kept :html))))))

(deftest lower-ac2-blocks-fallback-test
  (testing "AC #2: the same collapsable directive renders as flattened
            static content on the latex target -- no :directive node
            remains, and the summary/hidden content survive in order as
            plain Blocks. An id-BEARING one keeps them inside a
            BlockQuote carrying the whole attr (TASK-51): sec4.3 gives
            Para no Attr, so a flat splice has nowhere to put the id, and
            a reference to this node would point at nothing in the
            emitted output"
    (let [d (collapsable/make "Click to expand"
                              [(para (str-inline "Hidden content"))]
                              {:id "collapse:1" :classes [] :props {}})
          document {:meta {} :blocks [d]}
          lowered (lower/lower document :latex registry-with-collapsable)
          [carrier :as blocks] (:blocks lowered)]
      (is (= 1 (count blocks)))
      (is (= :block-quote (:t carrier)))
      (is (= (:attr d) (:attr carrier)) "the whole attr, not just the id")
      (is (= [(para {:t :strong :inlines [(str-inline "Click to expand")]})
              (para (str-inline "Hidden content"))]
             (:blocks carrier))
          "the flattened content itself is unchanged, and in order")
      (is (every? #(not= :directive (:t %)) (:blocks carrier)))
      (is (ast/valid? ast/Document lowered))))
  (testing "the fallback still applies even with no registry at all -- the
            :html-only native renderer is simply absent for :latex"
    (let [d (collapsable/make "Click to expand" [])
          lowered (lower/lower {:meta {} :blocks [d]} :latex {})]
      (is (= 1 (count (:blocks lowered))))
      (is (= :para (get-in lowered [:blocks 0 :t]))))))

(deftest round-trip-ac3-test
  (testing "AC #3: a collapsable directive nested in a real document (a
            Section) round-trips through resolve, then lower, for both the
            html and latex targets, without a build error"
    (let [d (collapsable/make "Click to expand"
                              [(para (str-inline "Hidden content"))]
                              {:id "collapse:1" :classes [] :props {}})
          section {:t :section :level 1 :heading [(str-inline "Intro")]
                   :blocks [d] :attr {:classes [] :props {}}}
          document {:meta {} :blocks [section]}
          {:keys [document diagnostics]}
          (resolver/resolve-document document {:directive-registry registry-with-collapsable})]
      (is (= [] diagnostics))
      (testing "html target: the directive survives lower and can be rendered"
        (let [lowered (lower/lower document :html registry-with-collapsable)
              directive (get-in lowered [:blocks 0 :blocks 0])]
          (is (= :directive (:t directive)))
          (is (ast/valid? ast/Document lowered))
          (is (re-find #"<details" (collapsable/render-html directive :html)))))
      (testing "latex target: the directive is fully flattened by lower"
        (let [lowered (lower/lower document :latex registry-with-collapsable)]
          (is (every? #(not= :directive (:t %)) (get-in lowered [:blocks 0 :blocks])))
          (is (ast/valid? ast/Document lowered))))))
  (testing "an unregistered/no-registry collapsable still round-trips for
            latex (via its own fallback) and, separately, only fails to
            lower for html once its fallback is also unavailable -- the
            :fallback is what actually carries the degradation guarantee,
            not registration"
    (let [d (collapsable/make "Click to expand" [])
          document {:meta {} :blocks [d]}]
      (is (ast/valid? ast/Document (lower/lower document :latex {})))
      (is (ast/valid? ast/Document (lower/lower document :html {}))))))

(deftest id-referenceability-test
  (testing "review bug fix: an :id set on a collapsable directive's attr is
            emitted as the rendered <details> element's own id=\"...\" HTML
            attribute -- previously silently dropped regardless of what
            attr.id was set to"
    (let [d (collapsable/make "Click to expand" [] {:id "collapse:1" :classes [] :props {}})]
      (is (re-find #"<details id=\"collapse:1\" class=\"collapsable\">"
                   (collapsable/render-html d :html)))))
  (testing "no :id set -> no id=\"...\" attribute at all, rather than an
            empty or nil-string artifact"
    (let [d (collapsable/make "Click to expand" [])]
      (is (re-find #"^<details class=\"collapsable\">" (collapsable/render-html d :html)))))
  (testing "an :id with HTML-significant characters is still escaped, same
            as the summary/hidden-content text"
    (let [d (collapsable/make "x" [] {:id "collapse:\"><script>" :classes [] :props {}})]
      (is (re-find #"id=\"collapse:&quot;&gt;&lt;script&gt;\"" (collapsable/render-html d :html)))))
  (testing "review bug fix: caller-supplied attr.classes are appended to
            the rendered class=\"...\" attribute alongside the
            always-present collapsable class -- previously silently
            dropped entirely, only the hardcoded collapsable literal ever
            reached the output"
    (let [d (collapsable/make "x" [] {:classes ["note" "warning"] :props {}})]
      (is (re-find #"<details class=\"collapsable note warning\">"
                   (collapsable/render-html d :html)))))
  (testing "a class with HTML-significant characters is escaped the same
            way id/summary/content are"
    (let [d (collapsable/make "x" [] {:classes ["a\"b"] :props {}})]
      (is (re-find #"class=\"collapsable a&quot;b\"" (collapsable/render-html d :html)))))
  (testing "an id with NO recognized-kind prefix (e.g. a bare
            \"collapse:1\", used purely for the HTML anchor) is invisible to
            numbering -- haselnuss.resolver/number-document's numbering is
            purely attr.id-prefix-driven, so this is a property of the id
            string chosen, not of collapsable specifically -- and a
            :cross-ref pointing at it dangles, verified end-to-end via the
            real resolver pipeline"
    (let [d (collapsable/make "Click to expand" [] {:id "collapse:1" :classes [] :props {}})
          section {:t :section :level 1 :heading [(str-inline "Intro")]
                   :blocks [d (para {:t :cross-ref :label "collapse:1"})]
                   :attr {:classes [] :props {}}}
          document {:meta {} :blocks [section]}
          {:keys [document diagnostics]}
          (resolver/resolve-document document {:directive-registry registry-with-collapsable})]
      (is (= [{:type :dangling-cross-ref
               :label "collapse:1"
               :message "dangling cross-reference: no target with id \"collapse:1\""}]
             diagnostics))
      (let [cross-ref (get-in document [:blocks 0 :blocks 1 :inlines 0])]
        (is (= :cross-ref (:t cross-ref)))
        (is (nil? (:target cross-ref)))
        (is (= "??" (:text cross-ref))))))
  (testing "corrected claim (this test previously overclaimed that
            collapsable can NEVER be a cross-reference target -- a real
            docstring bug caught by review): giving the collapsable's own
            id a lexicon-recognized prefix (e.g. `fig:`) numbers and
            resolves it directly, exactly like chart's own sec8.4
            mechanism -- no enclosing wrapper node needed, and unrelated to
            collapsable's own :kind registration (or lack of one)"
    (let [d (collapsable/make "Click to expand" [] {:id "fig:tree" :classes [] :props {}})
          document {:meta {} :blocks [d (para {:t :cross-ref :label "fig:tree"})]}
          {:keys [document diagnostics]}
          (resolver/resolve-document document {:directive-registry registry-with-collapsable})]
      (is (empty? diagnostics))
      (let [cross-ref (get-in document [:blocks 1 :inlines 0])]
        (is (= "fig:tree" (:target cross-ref)))
        (is (= "Figure 1" (:text cross-ref)))))))

(deftest render-html-nested-collapsable-test
  (testing "review bug fix: a collapsable nested inside another collapsable's
            own hidden content renders correctly on the html target instead
            of being misreported as an unsupported block -- lower's own AC #1
            branch deliberately keeps a directive with a native :html
            renderer as a :directive node (rather than flattening it), so
            block->html must be able to recognize and render one"
    (let [inner (collapsable/make "Inner summary"
                                  [(para (str-inline "Inner hidden content"))]
                                  {:id "collapse:inner" :classes [] :props {}})
          outer (collapsable/make "Outer summary" [inner]
                                  {:id "collapse:outer" :classes [] :props {}})
          document {:meta {} :blocks [outer]}
          lowered (lower/lower document :html registry-with-collapsable)
          outer' (first (:blocks lowered))
          inner' (first (:blocks outer'))]
      (testing "lower keeps both the outer and the (still nested) inner
                directive as :directive nodes for :html -- neither is
                flattened, since both have a native :html renderer"
        (is (= :directive (:t outer')))
        (is (= :directive (:t inner'))))
      (let [html (collapsable/render-html outer' :html)]
        (is (re-find #"<details id=\"collapse:outer\" class=\"collapsable\">" html))
        (is (re-find #"<summary>Outer summary</summary>" html))
        (is (re-find #"<details id=\"collapse:inner\" class=\"collapsable\">" html))
        (is (re-find #"<summary>Inner summary</summary>" html))
        (is (re-find #"<p>Inner hidden content</p>" html))
        (is (< (.indexOf html "<summary>Outer summary</summary>")
               (.indexOf html "<summary>Inner summary</summary>")))
        (is (= 2 (count (re-seq #"<details" html))))))
    (testing "a nested directive under any name other than \"collapsable\"
              remains an explicit, documented unsupported case (no registry
              access to dispatch it correctly) rather than being silently
              mangled"
      (let [fake-directive {:t :directive :name "not-collapsable"
                            :attr {:classes [] :props {}} :blocks []}
            outer (collapsable/make "Outer" [fake-directive])]
        (try
          (collapsable/render-html outer :html)
          (is false "expected render-html to throw")
          (catch clojure.lang.ExceptionInfo e
            (is (= ::collapsable/unsupported-block (:type (ex-data e))))))))))

(deftest lower-rule-test
  (let [reg (registry/register {} collapsable/extension)
        authored {:t :directive :name "collapsable"
                  :blocks [{:t :para :inlines [{:t :str :text "Hidden."}]}]
                  :attr {:classes [] :props {"summary" "Details"}}}
        flattened [{:t :para :inlines [{:t :strong :inlines [{:t :str :text "Details"}]}]}
                   {:t :para :inlines [{:t :str :text "Hidden."}]}]]
    (testing "an AUTHORED collapsable -- one haselnuss.parser produced,
              which therefore carries no :fallback at all, the parser
              being fallback-inert -- degrades on a non-:html target
              through the registry's own :lower rule instead of aborting
              the build with ::no-representation"
      (is (nil? (:fallback authored)) "the fixture really is the parser-shaped one")
      (is (= flattened (:blocks (lower/lower {:meta {} :blocks [authored]} :latex reg)))))
    (testing "the :lower rule and the :fallback a make-built directive
              carries produce the SAME blocks, so the two routes cannot
              drift apart"
      (let [built (collapsable/make "Details" [{:t :para :inlines [{:t :str :text "Hidden."}]}])]
        (is (= flattened (get-in built [:fallback :blocks])))
        (is (= flattened (:blocks (lower/lower {:meta {} :blocks [built]} :latex reg))))))
    (testing "the :html target still keeps the directive for its own
              native renderer -- the :lower rule must not shadow that"
      (is (= [:directive] (mapv :t (:blocks (lower/lower {:meta {} :blocks [authored]} :html reg))))))))

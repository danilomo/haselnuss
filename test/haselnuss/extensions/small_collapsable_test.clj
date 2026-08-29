(ns haselnuss.extensions.small-collapsable-test
  "Fixtures for the `small-collapsable` extension (`haselnuss.extensions.
  small-collapsable`): a disclosure that is semantically `collapsable` but
  lays out as a word in running text -- inline while collapsed, and
  breaking the line before and after its revealed content when expanded.

  The layout contract is markup plus stylesheet, so it is asserted at both
  ends: the renderer emits the `small-collapsable-body` block wrapper that
  a stylesheet can key on, and `haselnuss.emit.html/default-stylesheet`
  carries the `display:inline` / block-body rules that turn that markup
  into the two states. Neither half is the feature on its own.

  TASK-89 changed how `render-html`'s own output is reached from a test:
  it is now `(fn [directive ctx] -> data)`, not `(fn [directive target]
  -> string)`, so every test that needs its rendered HTML goes through
  `haselnuss.emit.html/emit-document` now (`render` below), the same
  reasoning `haselnuss.extensions.collapsable-test`'s own docstring gives
  in full."
  (:require [clojure.test :refer [deftest is testing]]
            [haselnuss.ast :as ast]
            [haselnuss.emit.html :as html]
            [haselnuss.extensions.collapsable :as collapsable]
            [haselnuss.extensions.small-collapsable :as small-collapsable]
            [haselnuss.lower :as lower]
            [haselnuss.registry :as registry]))

(def ^:private registry-with-both
  (-> {}
      (registry/register collapsable/extension)
      (registry/register small-collapsable/extension)))

(defn- render
  "`directive` rendered through the real HTML emitter core, registering
  both `collapsable` and `small-collapsable`, returning just the
  `<body>`'s own inner HTML."
  [directive]
  (let [out (html/emit-document {:meta {} :blocks [directive]} {:registry registry-with-both})
        [_ body] (re-find #"(?s)<body>(.*)</body>" out)]
    body))

(defn- para
  [& inlines]
  {:t :para :inlines (vec inlines)})

(defn- str-inline
  [text]
  {:t :str :text text})

(deftest make-test
  (testing "builds a schema-valid :directive node under its own name,
            carrying the summary in attr.props and the revealed content
            in :blocks"
    (let [hidden [(para (str-inline "Porque Deus amou o mundo"))]
          d (small-collapsable/make "João 3:16" hidden
                                    {:id "collapse:jo316" :classes [] :props {}})]
      (is (ast/valid? ast/Block d))
      (is (= "small-collapsable" (:name d)))
      (is (= hidden (:blocks d)))
      (is (= "collapse:jo316" (get-in d [:attr :id])))
      (is (= "João 3:16" (collapsable/summary d)))))
  (testing "derives the same :blocks Fallback collapsable derives -- the
            two differ in HTML layout only, and a static target collapses
            nothing either way"
    (let [hidden [(para (str-inline "hidden"))]
          small (small-collapsable/make "Label" hidden)]
      (is (ast/valid? ast/Fallback (:fallback small)))
      (is (= (:fallback (collapsable/make "Label" hidden)) (:fallback small)))))
  (testing "defaults to an empty, id-less attr when none is supplied"
    (let [d (small-collapsable/make "Label" [])]
      (is (nil? (get-in d [:attr :id])))
      (is (= [] (get-in d [:attr :classes]))))))

(deftest render-html-test
  (testing "renders a native <details>/<summary> disclosure under the
            small-collapsable class, with the revealed blocks inside the
            block-level body wrapper the expanded state needs"
    (let [d (small-collapsable/make "João 3:16"
                                    [(para (str-inline "Porque Deus amou o mundo"))]
                                    {:id "collapse:jo316" :classes [] :props {}})
          out (render d)]
      (is (= (str "<details id=\"collapse:jo316\" class=\"small-collapsable\">"
                  "<summary>João 3:16</summary>"
                  "<div class=\"small-collapsable-body\">"
                  "<p>Porque Deus amou o mundo</p>"
                  "</div>"
                  "</details>")
             out))))
  (testing "authored attr.classes are appended after the base class, and
            attr.id is omitted entirely when unset"
    (let [d (small-collapsable/make "Label" [] {:classes ["proof-text"] :props {}})
          out (render d)]
      (is (re-find #"<details class=\"small-collapsable proof-text\">" out))
      (is (not (re-find #"id=" out)))))
  (testing "the summary is HTML-escaped, not spliced raw"
    (let [d (small-collapsable/make "1 João <3> & 4" [])]
      (is (re-find #"<summary>1 João &lt;3&gt; &amp; 4</summary>" (render d)))))
  (testing "an empty body still emits the wrapper, so the collapsed and
            expanded states stay structurally identical"
    (let [out (render (small-collapsable/make "Label" []))]
      (is (re-find #"<div class=\"small-collapsable-body\"></div>" out)))))

(deftest label-test
  (testing "an authored label renders as an inert inline span BEFORE the
            <details>, so a marker introducing a run of disclosures joins
            their flow instead of being a block that breaks the line --
            and is not itself clickable"
    (let [d (small-collapsable/make "1 Coríntios 3:23" []
                                    {:classes [] :props {"label" "(1)"}})
          out (render d)]
      (is (= "(1)" (small-collapsable/label d)))
      (is (re-find #"^<span class=\"small-collapsable-label\">\(1\)</span><details" out))
      (is (not (re-find #"<summary>[^<]*\(1\)" out)))))
  (testing "no label means no span at all -- an unmarked disclosure emits
            exactly what it did before the prop existed"
    (let [out (render (small-collapsable/make "Ref" []))]
      (is (nil? (small-collapsable/label (small-collapsable/make "Ref" []))))
      (is (not (re-find #"small-collapsable-label" out)))
      (is (re-find #"^<details" out))))
  (testing "the marker is HTML-escaped like any other authored text"
    (let [d (small-collapsable/make "Ref" [] {:classes [] :props {"label" "<1>"}})]
      (is (re-find #"<span class=\"small-collapsable-label\">&lt;1&gt;</span>" (render d)))))
  (testing "a static target keeps the marker rather than dropping it: it
            is space-joined onto the summary in the flattened head, since
            nothing else in that form could carry it"
    (let [authored {:t :directive :name "small-collapsable"
                    :attr {:classes [] :props {"summary" "Romanos 8:28" "label" "(8)"}}
                    :blocks [(para (str-inline "hidden"))]}
          lowered (lower/lower {:meta {} :blocks [authored]} :latex registry-with-both)]
      (is (= (para {:t :strong :inlines [(str-inline "(8) Romanos 8:28")]})
             (first (:blocks lowered))))))
  (testing "and make derives the same flattened head, so a built node and
            an authored one degrade identically"
    (let [d (small-collapsable/make "Romanos 8:28" [] {:classes [] :props {"label" "(8)"}})]
      (is (= [(para {:t :strong :inlines [(str-inline "(8) Romanos 8:28")]})]
             (get-in d [:fallback :blocks]))))))

(deftest nesting-test
  (testing "a small-collapsable nests inside its own hidden content"
    (let [inner (small-collapsable/make "Inner" [(para (str-inline "deep"))])
          outer (small-collapsable/make "Outer" [inner])
          out (render outer)]
      (is (= 2 (count (re-seq #"<details" out))))
      (is (< (.indexOf out "<summary>Outer</summary>")
             (.indexOf out "<summary>Inner</summary>")))))
  (testing "a plain collapsable nests inside a small-collapsable, rendered
            by its own native renderer rather than mangled -- through the
            ordinary registry now (TASK-89), not a dispatch map this
            namespace hard-codes \"collapsable\" into"
    (let [inner (collapsable/make "Inner" [(para (str-inline "deep"))])
          outer (small-collapsable/make "Outer" [inner])
          out (render outer)]
      (is (re-find #"<details class=\"collapsable\">" out))
      (is (re-find #"<details class=\"small-collapsable\">" out))))
  (testing "TASK-89: the reverse case -- a small-collapsable nested inside
            a plain collapsable -- now works too, which is a real
            capability change from before this task: closing that gap
            used to mean collapsable requiring this namespace, a require
            cycle, since each namespace's own dispatch map had to know the
            other's directive name by hand. ctx's own :render-blocks
            closes it for free, since dispatch is the ordinary registry
            lookup and neither namespace needs to require the other at
            all (confirmed here: this file requires
            haselnuss.extensions.collapsable, but collapsable.clj itself
            requires neither small-collapsable nor this namespace)"
    (let [inner (small-collapsable/make "Inner" [(para (str-inline "deep"))])
          outer (collapsable/make "Outer" [inner])
          out (render outer)]
      (is (re-find #"<details class=\"collapsable\">" out))
      (is (re-find #"<details class=\"small-collapsable\">" out))
      (is (< (.indexOf out "<summary>Outer</summary>")
             (.indexOf out "<summary>Inner</summary>")))))
  (testing "TASK-89: content outside this extension's former documented
            scope limit now renders instead of raising an error, since the
            hidden content is rendered through the core emitter's own
            :render-blocks -- the same capability change
            collapsable-test's own render-html-ac1-test documents in full
            for the sibling extension"
    (let [d (small-collapsable/make "Label" [{:t :thematic-break}])]
      (is (re-find #"<hr/>" (render d))))))

(deftest lower-test
  (testing "lowering for :html keeps the directive intact, so the emitter
            can dispatch it to the native renderer"
    (let [d (small-collapsable/make "Label" [(para (str-inline "hidden"))])
          lowered (lower/lower {:meta {} :blocks [d]} :html registry-with-both)]
      (is (= :directive (:t (first (:blocks lowered)))))))
  (testing "an AUTHORED directive (no :fallback of its own, as the parser
            builds it) still degrades on a static target rather than
            aborting the build -- the whole reason the :lower rule is
            registered"
    (let [authored {:t :directive :name "small-collapsable"
                    :attr {:classes [] :props {"summary" "Label"}}
                    :blocks [(para (str-inline "hidden"))]}
          lowered (lower/lower {:meta {} :blocks [authored]} :latex registry-with-both)]
      (is (= [(para {:t :strong :inlines [(str-inline "Label")]})
              (para (str-inline "hidden"))]
             (:blocks lowered)))))
  (testing "TASK-51: an id-bearing one keeps that same flattened content
            inside a BlockQuote carrying the whole attr, since a flat
            splice of Paras has nowhere to put an id (sec4.3) and a
            reference to this node would then point at nothing in the
            emitted LaTeX"
    (let [attr {:id "collapse:1" :classes ["aside"] :props {"summary" "Label"}}
          authored {:t :directive :name "small-collapsable" :attr attr
                    :blocks [(para (str-inline "hidden"))]}
          lowered (lower/lower {:meta {} :blocks [authored]} :latex registry-with-both)
          [carrier :as blocks] (:blocks lowered)]
      (is (= 1 (count blocks)))
      (is (= :block-quote (:t carrier)))
      (is (= attr (:attr carrier)))
      (is (= [(para {:t :strong :inlines [(str-inline "Label")]})
              (para (str-inline "hidden"))]
             (:blocks carrier)))
      (is (ast/valid? ast/Document lowered)))))

(deftest stylesheet-contract-test
  (testing "the default stylesheet carries both halves of the layout
            contract: inline while collapsed, block body when expanded"
    (is (re-find #"details\.small-collapsable\{display:inline\}"
                 html/default-stylesheet))
    (is (re-find #"details\.small-collapsable\[open\] \.small-collapsable-body\{display:block"
                 html/default-stylesheet)))
  (testing "the body is display:none while COLLAPSED, and only becomes a
            block under [open]. A permanently-block body overrides the UA
            rule that hides a closed <details>'s content, and that block
            box splits the surrounding inline box -- which puts every
            collapsed label on its own line, the exact behaviour this
            element exists to avoid (caught by measuring the real render)"
    (is (re-find #"details\.small-collapsable \.small-collapsable-body\{display:none\}"
                 html/default-stylesheet)))
  (testing "Chrome's ::details-content pseudo-element is neutralized to
            inline. It is a block box wrapping a <details>'s content, and
            left alone it splits the inline box and stacks every collapsed
            label -- invisible in the emitted markup, found only by
            measuring the render, and the single rule the inline state
            actually depends on in that engine"
    (is (re-find #"details\.small-collapsable::details-content\{display:inline\}"
                 html/default-stylesheet)))
  (testing "the label span gets the trailing gap the emitter's own output
            cannot supply -- it writes no whitespace between the span and
            the <details> after it"
    (is (re-find #"\.small-collapsable-label\{margin-right"
                 html/default-stylesheet)))
  (testing "consecutive labels are kept apart by the summary's own trailing
            margin -- the emitter writes no whitespace between sibling
            blocks, so nothing else would separate them"
    (is (re-find #"details\.small-collapsable summary\{[^}]*margin-right"
                 html/default-stylesheet)))
  (testing "no `>` child combinator: this repo's html tests parse the
            output as XML, where an unescaped `>` in <style> is invalid
            (default-stylesheet's own documented constraint)"
    (is (not (re-find #">" html/default-stylesheet)))))

(deftest extension-v2-and-trust-test
  (testing "TASK-89: the registered :html renderer speaks the v2 contract"
    (is (registry/v2? (get-in small-collapsable/extension [:emit :html]))))
  (testing "TASK-89 AC #8: this extension is registered untrusted -- it
            needs no [:raw ...] escape hatch either"
    (is (false? (:trusted (registry/lookup registry-with-both "small-collapsable"))))))

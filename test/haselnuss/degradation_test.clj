(ns haselnuss.degradation-test
  "The graceful-degradation contract, end to end (TASK-28).

  SPEC.md sec2/sec8.3's promise is that a `.hdoc` always produces a
  valid PDF even when interactive richness is lost, because a custom
  element with no target-native renderer *must* declare a fallback --
  and that the alternative is a build error, never a silently dropped
  node. This namespace checks both halves on the real pipeline: the
  fallback actually reaching the emitted LaTeX, and the missing-fallback
  case actually failing the build.

  One structural constraint shapes how, and it is worth stating rather
  than working around silently: `haselnuss.parser` is fallback-inert --
  it constructs no `:fallback` field at all (see `haselnuss.lower`'s own
  docstring) -- so an *authored* `.hdoc` can never declare one. A
  `:fallback` exists only on a programmatically built node, which is why
  ACs #1 and #2 are driven through `haselnuss.lower/lower` and
  `haselnuss.emit.latex/emit-document` on constructed Documents rather
  than through the CLI, while AC #3, whose failure an author really does
  hit, is driven through the CLI on a real `.hdoc`. (It is also why
  `haselnuss.cli` and `haselnuss.extensions.collapsable` give their
  built-in directives registry `:lower` rules instead of relying on
  per-instance fallbacks.)

  TASK-44 closes this file's own remaining gap: the `:transform`
  variant, whose output reached no emitter anywhere. It is the Fallback
  variant with a caller-supplied extension point, so what it produces is
  arbitrary caller-authored AST rather than something the parser built,
  which is why `lower` now validates what a rule returns and why that is
  asserted here. It is driven through `lower` for the same
  reason ACs #1/#2 above are, and the CLI deliberately does not expose
  `:transform-rules`, because a document it can be handed cannot declare
  a fallback to reach them with (see `haselnuss.cli`'s own docstring).

  TASK-49 then widens that validation to every lowering branch, because
  the reasoning above applies verbatim to a registry `:lower` rule --
  the same signature, and the branch the CLI actually wires up -- to a
  `:blocks` fallback's authored blocks, and to a `:poster`'s `:src`.
  `lowered-block-validation-test` covers all four, each asserting the
  branch the error names, since the fix differs per branch.

  The LaTeX output is compiled by a real `pdflatex`, matching
  `haselnuss.emit.latex-test`'s own convention: \"renders on the latex
  target\" is a claim about LaTeX, not about a string."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [haselnuss.ast :as ast]
            [haselnuss.cli :as cli]
            [haselnuss.emit.html :as html]
            [haselnuss.emit.latex :as latex]
            [haselnuss.lower :as lower]
            [haselnuss.registry :as registry])
  (:import (java.awt.image BufferedImage)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (javax.imageio ImageIO)))

(def ^:private empty-attr {:classes [] :props {}})

(defn- para [text] {:t :para :inlines [{:t :str :text text}]})

(defn- widget
  "A custom `:directive` named \"widget\" carrying `fallback` (or none),
  standing in for any element with no native LaTeX renderer -- the shape
  sec8.3's contract exists for."
  ([] {:t :directive :name "widget" :blocks [(para "Interactive content.")]
       :attr empty-attr})
  ([fallback] (assoc (widget) :fallback fallback))
  ([fallback attr] (assoc (widget) :fallback fallback :attr attr)))

(defn- lower+emit
  "Lowers `blocks` for `:latex` through the real `lower` pass and emits
  them, returning `[lowered-document tex]`. `registry` defaults to an
  empty one, so a widget's only representation is whatever fallback it
  declares."
  ([blocks] (lower+emit blocks {}))
  ([blocks reg]
   (let [document {:meta {} :blocks (vec blocks)}
         lowered (lower/lower document :latex reg)]
     [lowered (latex/emit-document lowered {:registry reg})])))

(defn- compiles?
  "True if `tex` compiles under a real `pdflatex`, run in a fresh temp
  directory holding the poster image the fixtures reference. Returns
  `[ok? output]`."
  [tex]
  (let [dir (str (Files/createTempDirectory "haselnuss-degradation" (make-array FileAttribute 0)))]
    (ImageIO/write (BufferedImage. 1 1 BufferedImage/TYPE_INT_RGB) "png" (io/file dir "poster.png"))
    (spit (io/file dir "doc.tex") tex)
    (let [{:keys [exit out err]} (shell/sh "pdflatex" "-interaction=nonstopmode" "-halt-on-error"
                                           "doc.tex" :dir dir)]
      [(zero? exit) (str out err)])))

(defn- compile-text!
  "Compiles `tex` with three `pdflatex` passes -- enough for `\\Cref` to
  resolve against a settled `.aux` -- and returns `[ok? pdf-text log]`.
  Deliberately not `-halt-on-error`: an unresolved reference is a
  warning, and distinguishing 'printed the wrong number' from 'failed to
  build' is the whole point of reading the text."
  [tex]
  (let [dir (str (Files/createTempDirectory "haselnuss-degradation" (make-array FileAttribute 0)))
        run #(shell/sh "pdflatex" "-interaction=nonstopmode" "doc.tex" :dir dir)]
    (ImageIO/write (BufferedImage. 1 1 BufferedImage/TYPE_INT_RGB) "png" (io/file dir "poster.png"))
    (spit (io/file dir "doc.tex") tex)
    (run)
    (run)
    (let [{:keys [exit out err]} (run)]
      [(zero? exit)
       (:out (shell/sh "pdftotext" (str (io/file dir "doc.pdf")) "-"))
       (str out err)])))

(deftest blocks-fallback-test
  (testing "AC #1: a directive with a blocks fallback renders as
            flattened static content on the latex target"
    (let [fallback {:kind :blocks :blocks [(para "A static substitute.")
                                           (para "With two paragraphs.")]}
          ;; With siblings on both sides, and checked by position: a
          ;; fallback appended at the end of the document rather than
          ;; spliced in place would pass a presence-only assertion
          ;; (TASK-28 review).
          [lowered tex] (lower+emit [(para "Before.") (widget fallback) (para "After.")])]
      (is (ast/valid? ast/Document lowered) (pr-str (ast/explain ast/Document lowered)))
      (is (= ["Before." "A static substitute." "With two paragraphs." "After."]
             (mapv (comp :text first :inlines) (:blocks lowered)))
          "the directive is replaced IN PLACE by exactly its fallback's own blocks, in order")
      (is (str/includes? tex "A static substitute."))
      (is (str/includes? tex "With two paragraphs."))
      (testing "and the directive's own interactive content is dropped
                rather than emitted alongside the substitute -- the
                fallback replaces it, it does not accompany it"
        (is (not (str/includes? tex "Interactive content."))))
      (let [[ok? log] (compiles? tex)]
        (is ok? (str "the degraded document must compile, pdflatex output:\n" log))))))

(deftest poster-fallback-test
  (testing "AC #2: a directive with a poster fallback renders as the
            substitute image PLUS its caption -- both halves, since
            either alone would be a silent loss"
    (let [fallback {:kind :poster :src "poster.png"
                    :caption [{:t :str :text "A chart, as a still image."}]}
          [lowered tex] (lower+emit [(widget fallback)])]
      (is (ast/valid? ast/Document lowered) (pr-str (ast/explain ast/Document lowered)))
      (is (= [:figure] (mapv :t (:blocks lowered))))
      (is (str/includes? tex "\\includegraphics[max width=\\linewidth]{poster.png}") "the substitute image")
      (is (str/includes? tex "\\caption*{A chart, as a still image.}")
          (str "and its caption -- unnumbered, because this poster carries no id, so the "
               "resolver numbered nothing here and HTML prints no number either. A plain "
               "\\caption would step LaTeX's own figure counter and print \"Figure 1\" over "
               "a figure the rest of the document does not know is numbered (TASK-59 review)"))
      (let [[ok? log] (compiles? tex)]
        (is ok? (str "the degraded document must compile, pdflatex output:\n" log)))))
  (testing "AC #2: the directive's own attr.id survives onto the
            synthesized Figure AND is a real figure reference -- sec8.4's
            whole point about a chart's poster participating in figure
            numbering through its own #fig: id.

            Asserted on the rendered PDF, not on the presence of
            `\\label{fig:chart}` in the .tex: a label is not a reference
            target unless a counter was stepped for it, and a
            caption-less figure steps none, so the substring check
            passed while a \\Cref to it printed 'Section 1' (TASK-28
            review). Run with an EMPTY caption deliberately, since that
            is the shape that was broken."
    (let [attr {:id "fig:chart" :classes [] :props {}}
          fallback {:kind :poster :src "poster.png" :caption []}
          [lowered tex] (lower+emit [(widget fallback attr)
                                     {:t :para :inlines [{:t :str :text "See "}
                                                         {:t :cross-ref :label "fig:chart"
                                                          :target "fig:chart"}]}])]
      (is (= "fig:chart" (get-in (first (:blocks lowered)) [:attr :id])))
      (is (str/includes? tex "\\label{fig:chart}"))
      (let [[ok? text log] (compile-text! tex)]
        (is ok? (str "the degraded document must compile, pdflatex output:\n" log))
        (is (str/includes? text "See Figure 1")
            (str "a poster fallback's id must resolve as a FIGURE reference, got:\n" text))
        (is (not (str/includes? text "See Section"))
            "the pre-fix bug printed the enclosing section here")))))

(deftest no-fallback-aborts-the-build-test
  (testing "AC #3: a directive with no target renderer and no fallback
            fails the build on BOTH targets, with the error naming the
            offending directive -- checked on the exception type and the
            name in its ex-data, not merely that something was thrown"
    (doseq [target [:latex :html]]
      (let [e (is (thrown? clojure.lang.ExceptionInfo
                           (lower/lower {:meta {} :blocks [(widget)]} target {})))]
        (is (= :haselnuss.lower/no-representation (:type (ex-data e))) (str target))
        (is (= "widget" (:name (ex-data e))) (str target))
        (is (str/includes? (ex-message e) "widget") (str target)))))
  (testing "AC #3 through the CLI, which is where an author meets it: the
            build exits non-zero, the message names the directive, and no
            output file is left behind on either target"
    (let [dir (str (Files/createTempDirectory "haselnuss-degradation" (make-array FileAttribute 0)))
          input (str (io/file dir "doc.hdoc"))]
      (spit input "# Intro {#sec:intro}\n\n:::{widget}\nInteractive content.\n:::\n")
      (doseq [[target output] [["html" "doc.html"] ["latex" "doc.tex"]]]
        (let [err (java.io.StringWriter.)
              out (java.io.StringWriter.)
              status (binding [*err* err *out* out]
                       (cli/main* [input "--target" target]))
              ;; The ERROR line specifically, not the whole buffer: the
              ;; unknown-directive WARNING also names "widget" and is
              ;; printed first, so grepping the buffer would still pass
              ;; if the error itself named nothing (TASK-28 review).
              error-line (first (filter #(str/starts-with? % "haselnuss: error:")
                                        (str/split-lines (str err))))]
          (is (= 1 status) target)
          (is (some? error-line) (str target ": " err))
          (is (str/includes? error-line "widget")
              (str target ": the error itself must name the directive, got: " error-line))
          (is (not (.exists (io/file dir output))) (str target " must write nothing")))))))

(deftest adjacent-branches-test
  (testing "a drop fallback removes the node entirely -- the one case
            where losing the content IS the declared intent, so it must
            not be confused with the silent drop the contract forbids"
    (let [[lowered tex] (lower+emit [(para "Before.") (widget {:kind :drop}) (para "After.")])]
      (is (= ["Before." "After."] (mapv (comp :text first :inlines) (:blocks lowered)))
          "the node is removed and its siblings keep their order")
      (is (not (str/includes? tex "Interactive content.")))))
  (testing "a registry :lower rule wins over the node's own fallback --
            sec8.3's branch order, and the mechanism haselnuss.cli relies
            on for the built-in directives an author cannot give a
            fallback to"
    (let [reg (registry/register {} {:name "widget"
                                     :lower (fn [_d _t] [(para "From the registry rule.")])})
          fallback {:kind :blocks :blocks [(para "From the fallback.")]}
          [_ tex] (lower+emit [(widget fallback)] reg)]
      (is (str/includes? tex "From the registry rule."))
      (is (not (str/includes? tex "From the fallback.")))))
  (testing "a native renderer for the target wins over both -- the
            directive is kept, not degraded at all"
    (let [reg (registry/register {} {:name "widget"
                                     :emit {:latex (fn [_d _t] "\\textbf{native}")}
                                     :lower (fn [_d _t] [(para "rule")])})
          [lowered tex] (lower+emit [(widget {:kind :drop})] reg)]
      (is (= [:directive] (mapv :t (:blocks lowered))))
      (is (str/includes? tex "\\textbf{native}")))))

;; --- The :transform variant (TASK-44) --------------------------------------
;;
;; The one Fallback variant whose output never reached an emitter anywhere in
;; this suite. That matters more than an ordinary coverage gap: `:transform`
;; is the only variant with a caller-supplied extension point, so what it
;; produces is arbitrary caller-authored AST rather than something the parser
;; built -- nothing else in the pipeline proves such a tree survives emission.

(defn- transform-widget
  "A widget whose fallback is the named `:transform` rule."
  [rule]
  (widget {:kind :transform :rule rule}))

(def ^:private chart-rule
  "A caller-supplied `:transform` rule of the shape sec4.5 describes:
  `(fn [directive target] -> Block[])`, reading the directive's own
  attributes and returning a small tree it built itself. Deliberately
  more than one Block, and target-aware, since both are things a real
  rule does and neither is exercised by returning a single constant."
  {"chart->static"
   (fn [directive target]
     [{:t :para :inlines [{:t :str :text (str "Chart rendered for " (name target) ".")}]}
      {:t :list :ordered false :tight true :attr empty-attr
       :items [[(para (str "Source: " (get-in directive [:attr :props "src"] "unknown")))]]}])})

(deftest transform-fallback-test
  (let [blocks [(para "Before.")
                (assoc (transform-widget "chart->static")
                       :attr {:id "fig:chart" :classes [] :props {"src" "data.csv"}})
                (para "After.")]]
    (testing "AC #1: a :transform fallback lowers through a
              caller-supplied rule and its returned Blocks render on the
              latex target, compiled by a real pdflatex"
      (let [lowered (lower/lower {:meta {} :blocks blocks} :latex {}
                                 {:transform-rules chart-rule})
            tex (latex/emit-document lowered {})]
        (is (ast/valid? ast/Document lowered) (pr-str (ast/explain ast/Document lowered)))
        (is (= ["Before." "Chart rendered for latex." nil "After."]
               (mapv (comp :text first :inlines) (:blocks lowered)))
            "the rule's own blocks are spliced in place, in order, and the list is among them")
        (is (str/includes? tex "Chart rendered for latex."))
        (is (str/includes? tex "Source: data.csv") "including the rule's own second block")
        (is (not (str/includes? tex "Interactive content.")))
        (let [[ok? log] (compiles? tex)]
          (is ok? (str "a transform fallback's output must compile, pdflatex output:\n" log)))))
    (testing "AC #2: and on the html target, so the variant is not
              verified on one target only -- the rule sees the target it
              is lowering for, which is why this is not the same
              assertion twice"
      (let [lowered (lower/lower {:meta {} :blocks blocks} :html {}
                                 {:transform-rules chart-rule})
            html (html/emit-document lowered {})]
        (is (ast/valid? ast/Document lowered) (pr-str (ast/explain ast/Document lowered)))
        (is (str/includes? html "<p>Chart rendered for html.</p>"))
        (is (str/includes? html "<li>Source: data.csv</li>"))
        (is (not (str/includes? html "Interactive content.")))))))

(deftest transform-invalid-result-test
  (testing "TASK-44 AC #3: a rule returning a schema-invalid tree is
            caught at lower time, naming the rule -- not emitted and
            discovered as broken output later. (TASK-44 called this the
            one place a caller hands the pipeline an arbitrary AST
            fragment; it is not, which is what TASK-49's own test below
            is about -- but the error still carries the rule's name,
            which is what this one checks)"
    (doseq [[label bad] [["a block with no :t" {:inlines []}]
                         ["an unknown block type" {:t :widget :blocks []}]
                         ["a Para whose inlines are malformed" {:t :para :inlines [{:t :str}]}]
                         ["not a map at all" "just a string"]
                         ;; nil and false are the shapes a (map ...) or
                         ;; (when ...) rule produces, and the ones a
                         ;; `when-let` on the first invalid block read as
                         ;; "nothing invalid": they sailed through and
                         ;; died in the emitter instead (found by review).
                         ["a nil block" nil]
                         ["a false block" false]
                         ;; And an invalid block that is not the first,
                         ;; so the whole vector has to be scanned.
                         ["an invalid block after a valid one" :second]]]
      (let [returned (if (= bad :second) [(para "Fine.") {:t :nope}] [bad])
            rules {"broken" (fn [_d _t] returned)}
            e (is (thrown? clojure.lang.ExceptionInfo
                           (lower/lower {:meta {} :blocks [(transform-widget "broken")]}
                                        :latex {} {:transform-rules rules}))
                  label)]
        (is (= :haselnuss.lower/invalid-lowered-blocks (:type (ex-data e))) label)
        (is (= :transform (:branch (ex-data e))) label)
        (is (= "broken" (:rule (ex-data e))) label)
        (is (some? (:explanation (ex-data e))) (str label ": must say what was wrong")))))
  (testing "a rule returning a single Block instead of a vector of them
            is reported for what it is, rather than as a bad block --
            scanning a map as a seq yields MapEntries and points the
            caller at the wrong thing (found by review)"
    (let [rules {"single" (fn [_d _t] {:t :para :inlines []})}
          e (is (thrown? clojure.lang.ExceptionInfo
                         (lower/lower {:meta {} :blocks [(transform-widget "single")]}
                                      :latex {} {:transform-rules rules})))]
      (is (= :haselnuss.lower/invalid-lowered-blocks (:type (ex-data e))))
      (is (= :transform (:branch (ex-data e))))
      (is (str/includes? (ex-message e) "a vector of Blocks was expected"))))
  (testing "and a rule returning nothing at all is still the OTHER
            error, since 'returned nonsense' and 'returned nothing' want
            different fixes"
    (let [rules {"empty" (fn [_d _t] [])}
          e (is (thrown? clojure.lang.ExceptionInfo
                         (lower/lower {:meta {} :blocks [(transform-widget "empty")]}
                                      :latex {} {:transform-rules rules})))]
      (is (= :haselnuss.lower/unavailable-transform-rule (:type (ex-data e))))))
  (testing "a VALID tree of course passes, so the check is not simply
            rejecting everything"
    (is (ast/valid? ast/Document
                    (lower/lower {:meta {} :blocks [(transform-widget "chart->static")]}
                                 :latex {} {:transform-rules chart-rule})))))

;; --- Validation of every lowering branch (TASK-49) -------------------------
;;
;; TASK-44 validated a `:transform` rule's result on the reasoning that it was
;; the one place a malformed tree could enter the pipeline. Review disproved
;; that by building one through a registry `:lower` rule -- the same
;; `(fn [directive target] -> Block[])` signature, and the branch the CLI
;; actually wires up. These fixtures are the three branches that reasoning
;; missed, each asserting the branch the error names.

(defn- lower-rule-registry
  "A registry whose \"widget\" entry has no renderer for any target and a
  `:lower` rule returning `blocks` -- `haselnuss.cli/environment-lower-
  rule`'s exact shape, which is what makes this the realistic case
  rather than a contrived one."
  [blocks]
  (registry/register {} {:name "widget" :lower (fn [_directive _target] blocks)}))

(deftest lowered-block-validation-test
  (testing "AC #1: a registry :lower rule returning a malformed block is
            a build error naming the rule's directive and the offending
            block, rather than an invalid Document reaching an emitter"
    (let [e (is (thrown? clojure.lang.ExceptionInfo
                         (lower/lower {:meta {} :blocks [(widget)]} :latex
                                      (lower-rule-registry [{:t :nope}]))))
          data (ex-data e)]
      (is (= :haselnuss.lower/invalid-lowered-blocks (:type data)))
      (is (= :lower-rule (:branch data)) "AC #3: which branch produced it")
      (is (= {:t :nope} (:block data)))
      (is (= "widget" (:name data)))
      (is (some? (:explanation data)))
      (is (str/includes? (ex-message e) "registry :lower rule"))))
  (testing "AC #4: a nil or false block from a :lower rule is caught, not
            read as 'nothing invalid' -- the shape a (map ...) or
            (when ...) rule produces"
    (doseq [bad [nil false]]
      (let [e (is (thrown? clojure.lang.ExceptionInfo
                           (lower/lower {:meta {} :blocks [(widget)]} :latex
                                        (lower-rule-registry [(para "Fine.") bad])))
                  (pr-str bad))]
        (is (= :lower-rule (:branch (ex-data e))) (pr-str bad))
        (is (= bad (:block (ex-data e))) (pr-str bad)))))
  (testing "a :lower rule returning one Block map instead of a vector of
            them is reported for what it is, on this branch too"
    (let [e (is (thrown? clojure.lang.ExceptionInfo
                         (lower/lower {:meta {} :blocks [(widget)]} :latex
                                      (lower-rule-registry (para "Just one.")))))]
      (is (= :lower-rule (:branch (ex-data e))))
      (is (str/includes? (ex-message e) "a vector of Blocks was expected"))))
  (testing "review finding #1: a rule returning something not seqable at
            all reaches the validator too. Dispatching on `seq` threw
            IllegalArgumentException naming neither the rule nor the
            directive -- and `false` is precisely AC #4's (when ...)
            shape, so the branch guard could not be the one place that
            assumed a collection"
    (doseq [bad [false true 42 :keyword]]
      (let [e (is (thrown? clojure.lang.ExceptionInfo
                           (lower/lower {:meta {} :blocks [(widget)]} :latex
                                        (lower-rule-registry bad)))
                  (pr-str bad))]
        (is (= :haselnuss.lower/invalid-lowered-blocks (:type (ex-data e))) (pr-str bad))
        (is (= :lower-rule (:branch (ex-data e))) (pr-str bad))
        (is (= bad (:block (ex-data e))) (pr-str bad))))
    (testing "and the same on the :transform branch, whose own
              'returned nothing' check had the same assumption"
      (let [rules {"scalar" (fn [_d _t] 42)}
            e (is (thrown? clojure.lang.ExceptionInfo
                           (lower/lower {:meta {} :blocks [(transform-widget "scalar")]}
                                        :latex {} {:transform-rules rules})))]
        (is (= :transform (:branch (ex-data e)))))))
  (testing "review finding #2, recorded rather than discovered later:
            validating a branch's RAW output tightens one shape that
            used to work by accident. ast/Block spells every list field
            as a vector, and the recursion normalized seqs on the way
            through, so a rule returning a lazy seq inside a block was
            correct output before and is a build error now. A lazy seq
            OF blocks is still fine -- the top-level check is
            sequential?, not vector?"
    (let [e (is (thrown? clojure.lang.ExceptionInfo
                         (lower/lower {:meta {} :blocks [(widget)]} :latex
                                      (lower-rule-registry
                                       [{:t :block-quote :attr empty-attr
                                         :blocks (map para ["a" "b"])}]))))]
      (is (= :haselnuss.lower/invalid-lowered-blocks (:type (ex-data e))))
      (is (some? (:explanation (ex-data e))) "malli must say which field"))
    (let [lowered (lower/lower {:meta {} :blocks [(widget)]} :latex
                               (lower-rule-registry (map para ["a" "b"])))]
      (is (ast/valid? ast/Document lowered))
      (is (= ["a" "b"] (mapv (comp :text first :inlines) (:blocks lowered))))))
  (testing "a rule DECLINING -- nil or empty, which sec8.3 documents as
            falling through to the fallback -- is still not an error, so
            the validation did not turn a legal shape into a failure"
    (doseq [declined [nil []]]
      (let [document {:meta {} :blocks [(widget {:kind :blocks
                                                 :blocks [(para "Fallback used.")]})]}
            lowered (lower/lower document :latex (lower-rule-registry declined))]
        (is (ast/valid? ast/Document lowered) (pr-str declined))
        (is (= "Fallback used." (-> lowered :blocks first :inlines first :text))
            (pr-str declined)))))
  (testing "AC #2: a :blocks fallback carrying a malformed block is
            caught the same way, naming its own branch -- authored data
            rather than a rule, so the fix is different"
    (let [e (is (thrown? clojure.lang.ExceptionInfo
                         (lower/lower {:meta {} :blocks [(widget {:kind :blocks
                                                                  :blocks [{:t :nope}]})]}
                                      :latex {})))]
      (is (= :haselnuss.lower/invalid-lowered-blocks (:type (ex-data e))))
      (is (= :blocks (:branch (ex-data e))))
      (is (str/includes? (ex-message e) ":blocks fallback"))))
  (testing "AC #2: and a :poster fallback whose :src is not a string --
            which used to synthesize a Figure that emitted
            \\includegraphics{42}"
    (let [e (is (thrown? clojure.lang.ExceptionInfo
                         (lower/lower {:meta {} :blocks [(widget {:kind :poster :src 42})]}
                                      :latex {})))]
      (is (= :poster (:branch (ex-data e))))
      (is (= :figure (:t (:block (ex-data e))))
          "the block reported is the Figure the fallback synthesized")
      (is (str/includes? (ex-message e) ":poster fallback"))))
  (testing "AC #5: every rule shape the rest of this suite exercises
            still lowers to a valid Document -- the built-in extensions
            and haselnuss.cli/build-registry's own environment-lower-
            rule, which is the registry rule a real build runs"
    (let [directive (fn [directive-name id]
                      {:t :directive :name directive-name
                       :blocks [(para "Body.")]
                       :attr (cond-> empty-attr id (assoc :id id))})
          document {:meta {} :blocks [(directive "theorem" "thm:a")
                                      (directive "admonition" "adm:a")
                                      (directive "collapsable" nil)
                                      (directive "small-collapsable" nil)]}
          reg (cli/build-registry {"thm:a" {:text "Theorem 1"}})]
      (doseq [target [:html :latex]]
        (let [lowered (lower/lower document target reg)]
          (is (ast/valid? ast/Document lowered)
              (str target ": " (pr-str (ast/explain ast/Document lowered))))))))
  (testing "and a :drop fallback still lowers to nothing at all, which
            has no block to validate"
    (let [lowered (lower/lower {:meta {} :blocks [(widget {:kind :drop})]} :latex {})]
      (is (= [] (:blocks lowered))))))

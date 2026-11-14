(ns ^:parallel haselnuss.cli-test
  "Fixtures for `haselnuss.cli` (TASK-25): the parse -> resolve -> lower
  -> emit pipeline.

  Driven through the namespace's own pure core (`run`) and file-level
  entry point (`build`) rather than by shelling out to a subprocess --
  that split exists precisely so every acceptance criterion, including
  the stderr and exit-code ones, can be asserted directly. AC #2's
  \"compilable .tex\" is checked literally, with a real `pdflatex`,
  mirroring `haselnuss.emit.latex-test`'s own convention; AC #1's
  \"complete HTML document\" is checked by parsing the output as XML,
  mirroring `haselnuss.emit.html-test`'s own `well-formed?`."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [haselnuss.cli :as cli]
            [haselnuss.json :as json]
            [haselnuss.parser :as parser]
            [haselnuss.registry :as registry])
  (:import (java.awt.image BufferedImage)
           (java.io StringReader)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute PosixFilePermissions)
           (javax.imageio ImageIO)
           (javax.xml.parsers DocumentBuilderFactory)
           (org.xml.sax InputSource)))

(defn- well-formed?
  "True if `html` parses as well-formed XML -- the same dependency-free
  structural proxy `haselnuss.emit.html-test` uses for \"a complete,
  valid HTML document\"."
  [html]
  (try
    (-> (DocumentBuilderFactory/newInstance)
        (.newDocumentBuilder)
        (.parse (InputSource. (StringReader. (str/replace html "<!DOCTYPE html>" "")))))
    true
    (catch Exception _ false)))

(defn- compiles?
  "True if `tex` compiles under a real `pdflatex` run in `dir` (so a
  `\\includegraphics` resolves against the fixture image written there).
  Returns `[ok? output]`, the latter for a failure message carrying the
  real compiler output."
  [dir tex]
  (spit (io/file dir "doc.tex") tex)
  (let [result (shell/sh "pdflatex" "-interaction=nonstopmode" "-halt-on-error" "doc.tex"
                         :dir (str dir))]
    [(zero? (:exit result)) (str (:out result) (:err result))]))

(def ^:private document
  "One document exercising every construct the pipeline has to carry all
  the way through: front matter, a labeled section, a footnote, a
  figure, a cross-reference, a citation, display math, and a built-in
  directive. Deliberately one fixture rather than several, since the
  thing under test is the *wiring* -- a construct that parses and emits
  in isolation can still be lost by a pass running in the wrong order."
  (str "---\n"
       "title: A Small Document\n"
       "authors:\n"
       "  - Ada Lovelace\n"
       "lang: en\n"
       "bibliography: refs.json\n"
       "---\n\n"
       "# Introduction {#sec:intro}\n\n"
       "Some prose with a footnote.[^n]\n\n"
       "[^n]: The footnote body.\n\n"
       "![A hazel tree.](pic.png){#fig:tree}\n\n"
       "See @fig:tree and [@knuth1984].\n\n"
       "$$\nE = mc^2\n$$ {#eq:mass}\n\n"
       ":::{theorem #thm:main}\n"
       "All good things come to an end.\n"
       ":::\n\n"
       "Also @thm:main and @eq:mass.\n"))

(def ^:private bibliography
  "[{\"id\":\"knuth1984\",\"author\":[{\"family\":\"Knuth\",\"given\":\"Donald\"}],\"issued\":{\"date-parts\":[[1984]]},\"title\":\"The TeXbook\"}]")

(defn- fixture-dir!
  "A fresh temp directory holding `source` as `doc.hdoc`, plus the
  `refs.json` its front matter names and a `pic.png` its figure needs.
  Returns the directory as a string."
  ([] (fixture-dir! document))
  ([source]
   (let [dir (str (Files/createTempDirectory "haselnuss-cli-test" (make-array FileAttribute 0)))]
     (spit (io/file dir "doc.hdoc") source)
     (spit (io/file dir "refs.json") bibliography)
     ;; A real 1x1 PNG, not an empty file: pdflatex reads the image for
     ;; real, so a placeholder makes the AC #2 compile fail for a reason
     ;; that has nothing to do with the pipeline (mirrors
     ;; haselnuss.emit.latex-test's own write-test-image!).
     (ImageIO/write (BufferedImage. 1 1 BufferedImage/TYPE_INT_RGB) "png"
                    (io/file dir "pic.png"))
     dir)))

(def ^:private no-libraries
  "TASK-157: what every build here gets in place of the process
  environment and home folder, so no test reads the real
  `$HASELNUSS_LIBRARIES` or `~/.config/haselnuss/libraries.yaml`: an empty
  environment and a home folder that does not exist."
  {:library-env {} :library-home "/nonexistent/haselnuss-test-home"})

(defn- build!
  "`cli/build` over `dir`'s own `doc.hdoc` with `opts`, returning
  `{:output-path :diagnostics :output}` -- the last being the text
  actually written to disk, so assertions read the file rather than
  trusting the return value."
  [dir opts]
  (let [result (cli/build (merge {:input (str (io/file dir "doc.hdoc")) :target "html"}
                                 no-libraries opts))]
    (assoc result :output (slurp (:output-path result)))))

(deftest html-target-test
  (testing "AC #1: the CLI converts a .hdoc to a complete HTML document
            at the requested output path"
    (let [dir (fixture-dir!)
          out-path (str (io/file dir "custom.html"))
          {:keys [output-path output diagnostics]} (build! dir {:output out-path})]
      (is (= out-path output-path))
      (is (well-formed? output))
      (is (str/starts-with? output "<!DOCTYPE html>"))
      (is (empty? diagnostics) (pr-str diagnostics))
      (testing "every construct survived the whole pipeline, not just the parse"
        (is (str/includes? output "<title>A Small Document</title>"))
        (is (str/includes? output "<section id=\"sec:intro\">"))
        (is (str/includes? output "<figure id=\"fig:tree\">"))
        (is (str/includes? output "Figure 1.1: A hazel tree."))
        (is (str/includes? output "<a href=\"#fig:tree\">Figure 1.1</a>"))
        (is (str/includes? output "\\[E = mc^2\\]"))
        (is (str/includes? output "<div id=\"thm:main\" class=\"theorem\">"))
        (is (not (str/includes? output "<blockquote")) "a theorem is not a quotation")
        (is (str/includes? output "<strong>Theorem 1</strong>")
            "the degraded head must carry the resolver's own number, matching the cross-reference to it")
        (is (str/includes? output "Knuth, D. (1984). The TeXbook."))
        (is (str/includes? output "class=\"footnotes\"")))))
  (testing "with no --output, the output path is the input with the
            target's own extension"
    (let [dir (fixture-dir!)
          {:keys [output-path]} (build! dir {})]
      (is (= (str (io/file dir "doc.html")) output-path)))))

(deftest latex-target-test
  (testing "AC #2: the CLI converts the same .hdoc to a .tex document
            that compiles under a real LaTeX toolchain"
    (let [dir (fixture-dir!)
          {:keys [output-path output]} (build! dir {:target "latex"})
          [ok? log] (compiles? dir output)]
      (is (= (str (io/file dir "doc.tex")) output-path))
      (is ok? (str "expected the emitted .tex to compile, pdflatex output:\n" log))
      (is (str/includes? output "\\documentclass{article}"))
      (is (str/includes? output "\\begin{theorem}")
          "TASK-24's mapping table must be reachable from the CLI's own registry")))
  (testing "the same document on both targets: a construct that reaches
            one must reach the other, since a document is authored once.
            LaTeX is compared in computed-numbers mode, the only mode
            that renders citation text itself rather than delegating to
            BibTeX -- native mode's \\citep is correct there, just not
            comparable to HTML's own rendered text"
    (let [dir (fixture-dir!)
          html (:output (build! dir {}))
          tex (:output (build! dir {:target "latex" :computed-numbers true}))]
      (doseq [[label in-html in-tex] [["figure" "<figure" "\\begin{figure}"]
                                      ["math" "\\[E = mc^2\\]" "E = mc^2"]
                                      ["footnote" "class=\"footnotes\"" "\\footnote{"]
                                      ["theorem" "thm:main" "thm:main"]
                                      ["citation" "Knuth" "Knuth"]]]
        (is (str/includes? html in-html) label)
        (is (str/includes? tex in-tex) label)))))

(defn- ast-nodes
  "Every map in decoded AST `ast` (recursively, including `ast` itself)
  that carries a `:t` tag, for querying `json-target-test`'s output by
  node shape rather than by brittle JSON substring matching."
  [ast]
  (->> (tree-seq (fn [x] (or (map? x) (sequential? x)))
                 (fn [x] (if (map? x) (vals x) (seq x)))
                 ast)
       (filter #(and (map? %) (contains? % :t)))))

(deftest json-target-test
  (testing "AC #1 (TASK-80): the CLI dumps the parsed document's own JSON
            interchange representation (haselnuss.json/->json), not a
            resolved/lowered view -- decoding the output back
            (haselnuss.json/json->ast) must round-trip, and what it
            contains must be the RAW parse: an unresolved cross-reference
            with no baked :text, and a directive surviving as a Directive
            rather than degraded to a BlockQuote"
    (let [dir (fixture-dir!)
          {:keys [output-path output diagnostics]} (build! dir {:target "json"})
          nodes (ast-nodes (json/json->ast output))]
      (is (= (str (io/file dir "doc.json")) output-path))
      (is (empty? diagnostics)
          "the resolver never runs for this target, so it can report nothing")
      (is (some #(and (= :section (:t %)) (= "sec:intro" (get-in % [:attr :id]))) nodes))
      (is (some #(and (= :figure (:t %)) (= "fig:tree" (get-in % [:attr :id]))) nodes))
      (is (some #(and (= :cite (:t %)) (= "knuth1984" (:key (first (:items %))))) nodes))
      (is (some #(and (= :directive (:t %)) (= "theorem" (:name %))) nodes)
          "a directive survives as a Directive, not degraded to a BlockQuote")
      (let [cross-ref (some (fn [n] (when (and (= :cross-ref (:t n))
                                               (= "fig:tree" (:label n)))
                                      n))
                            nodes)]
        (is cross-ref)
        (is (not (contains? cross-ref :text))
            "no computed cross-reference text -- resolution never ran"))))
  (testing "AC #2: a genuine parse error aborts the build too, matching
            the html/latex error contract -- nothing written, exception
            thrown"
    (let [dir (fixture-dir! ":::{note}\nunterminated\n")]
      (is (thrown? clojure.lang.ExceptionInfo (build! dir {:target "json"})))
      (is (not (.exists (io/file dir "doc.json"))))))
  (testing "AC #4: latex/html-only flags have no effect on the json
            target, the same way a latex-only flag is already a no-op on
            --target html"
    (let [dir (fixture-dir!)
          plain (:output (build! dir {:target "json"}))
          flagged (:output (build! dir {:target "json" :computed-numbers true
                                        :fragment true :no-stylesheet true}))]
      (is (= plain flagged)))))

(deftest json-target-leaves-book-nodes-unresolved-test
  (testing "TASK-156 AC #3 (doc-10 \"Emitters\"): the json target runs no
            resolve, so a book link and a quote come out exactly as parsed
            -- only `:ref`, as written -- as an `!include` does"
    (let [{:keys [output diagnostics]}
          (cli/run "See @{bible:romanos#14:8}.\n\n!quote reformed:heidelberg#sec:q1..sec:q3\n"
                   {:target "json"})
          nodes (ast-nodes (json/json->ast output))]
      (is (= [] diagnostics))
      (is (= [{:t :book-ref :ref "bible:romanos#14:8"}]
             (filterv #(= :book-ref (:t %)) nodes)))
      (is (= [{:t :book-quote :ref "reformed:heidelberg#sec:q1..sec:q3"}]
             (filterv #(= :book-quote (:t %)) nodes))))))

(deftest json-target-coalesces-prose-test
  (testing "AC #1/#5 (TASK-81): a plain-prose paragraph decodes
            (haselnuss.json/json->ast) to ONE :str Inline per run of
            words, not one per word with :space entries between, and the
            words are joined exactly as authored"
    (let [{:keys [output]} (cli/run "Some prose with several words.\n" {:target "json"})
          para (first (:blocks (json/json->ast output)))]
      (is (= [{:t :str :text "Some prose with several words."}] (:inlines para)))))
  (testing "AC #2: a CommonMark soft line-break (a wrapped source line
            inside one paragraph, not an authored hard break) glues the
            same way :space does"
    (let [{:keys [output]} (cli/run "Line one\nline two.\n" {:target "json"})
          para (first (:blocks (json/json->ast output)))]
      (is (= [{:t :str :text "Line one line two."}] (:inlines para)))))
  (testing "AC #3: an authored hard line-break still appears as its own
            entry rather than being glued into the surrounding text"
    (let [{:keys [output]} (cli/run "End.\\\nnext line.\n" {:target "json"})
          para (first (:blocks (json/json->ast output)))]
      (is (= [{:t :str :text "End."} {:t :line-break} {:t :str :text "next line."}]
             (:inlines para)))))
  (testing "AC #4: spacing on both sides of inline markup survives
            exactly -- neither word is glued to the markup, and no space
            is lost or duplicated at either boundary"
    (let [{:keys [output]} (cli/run "wrong *emphasis* text.\n" {:target "json"})
          para (first (:blocks (json/json->ast output)))]
      (is (= [{:t :str :text "wrong "}
              {:t :emph :inlines [{:t :str :text "emphasis"}]}
              {:t :str :text " text."}]
             (:inlines para))))))

(def ^:private hdoc-target-fixture
  "A small document exercising exactly what the hdoc branch (TASK-98)
  must NOT touch: an unresolved cross-reference and a directive that
  would otherwise be numbered/degraded by a resolve or lower pass."
  (str "# Introduction {#sec:intro}\n\n"
       "See @fig:tree.\n\n"
       "![A hazel tree.](pic.png){#fig:tree}\n\n"
       ":::{theorem #thm:main}\n"
       "All good things come to an end.\n"
       ":::\n"))

(deftest hdoc-target-test
  (testing "AC #1/#2/#6 (TASK-98): --target hdoc writes the parsed
            document back out as canonical .hdoc source, joining the json
            branch exactly -- no resolve, no lower, no registry, and `run`
            stays pure (no file I/O, called directly here)"
    (let [{:keys [output diagnostics]} (cli/run hdoc-target-fixture {:target "hdoc"})]
      (is (empty? diagnostics)
          "the resolver never runs for this target, so it can report nothing")
      (testing "a cross-reference survives unresolved -- the bare @fig:tree
                token, not a baked-in \"Figure 1\""
        (is (str/includes? output "@fig:tree"))
        (is (not (str/includes? output "Figure 1"))))
      (testing "a directive survives as a Directive, not degraded to a
                BlockQuote -- the ::: fence, not a bold head"
        (is (str/includes? output ":::{theorem #thm:main}"))
        (is (not (str/includes? output "<strong>"))))
      (testing "and re-parsing the written .hdoc gives back the identical
                AST this call itself parsed -- the same doc-7 AST
                fixed-point standard haselnuss.emit.hdoc-test holds this
                emitter to, now proven through the CLI's own wiring"
        (is (= (parser/parse hdoc-target-fixture)
               (parser/parse output))))))
  (testing "AC #1: the target is reachable through the file-based build
            too, and --help lists it"
    (let [dir (fixture-dir! hdoc-target-fixture)
          out-path (str (io/file dir "canonical.hdoc"))
          {:keys [output-path]} (build! dir {:target "hdoc" :output out-path})]
      (is (= out-path output-path))
      (is (.exists (io/file out-path)))))
  (testing "AC #5: flags that only mean something to a resolved/lowered
            build -- --computed-numbers, --fragment, --no-stylesheet and
            --overlay -- have no effect here, the same way a latex-only
            flag is already a no-op on --target html"
    (let [plain (:output (cli/run hdoc-target-fixture {:target "hdoc"}))
          flagged (:output (cli/run hdoc-target-fixture
                                    {:target "hdoc" :computed-numbers true
                                     :fragment true :no-stylesheet true
                                     :overlay ["/does/not/exist.hdoc"]}))]
      (is (= plain flagged)
          "even an --overlay path that does not exist has no effect: the
           branch never reaches load-overlays! at all"))))

(deftest computed-numbers-flag-test
  (let [dir (fixture-dir!)
        native (:output (build! dir {:target "latex"}))
        computed (:output (build! dir {:target "latex" :computed-numbers true}))]
    (testing "AC #3: without the flag, LaTeX emits native cross-reference
              and citation commands and lets LaTeX compute the numbers"
      (is (str/includes? native "\\Cref{fig:tree}"))
      (is (str/includes? native "\\begin{theorem}"))
      (is (not (str/includes? native "\\hyperref[fig:tree]")))
      (is (not (str/includes? native "Figure 1.1"))))
    (testing "AC #3: with the flag, the resolver's own computed numbers
              are baked in and nothing depends on LaTeX's counters"
      (is (str/includes? computed "\\hyperref[fig:tree]{Figure 1.1}"))
      (is (str/includes? computed "\\caption*{Figure 1.1: A hazel tree.}"))
      (is (str/includes? computed "\\tag{1.1}"))
      (is (not (str/includes? computed "\\Cref"))))
    (testing "and the computed-numbers output agrees with HTML on every
              number, which is the reason the flag exists"
      (let [html (:output (build! dir {}))]
        (doseq [number ["Figure 1.1" "Theorem 1" "Eq. (1.1)"]]
          (is (str/includes? html number) number)
          (is (str/includes? computed number) number))))
    (testing "both modes still compile"
      (doseq [[label tex] [["native" native] ["computed" computed]]]
        (let [[ok? log] (compiles? (fixture-dir!) tex)]
          (is ok? (str label " should compile, pdflatex output:\n" log)))))))

(deftest diagnostics-are-warnings-test
  (testing "AC #4: dangling references and citations, duplicate ids,
            unknown directives and id-prefix/role mismatches are all
            reported, and the build still produces its output"
    (let [source (str "# Intro {#sec:intro}\n\n"
                      "Missing ref @fig:nope and citation [@nobody2000].\n\n"
                      "## Dup {#sec:intro}\n\n"
                      "## Mislabeled {#fig:oops}\n\n"
                      "text\n")
          dir (fixture-dir! source)
          {:keys [diagnostics output]} (build! dir {})]
      (is (= #{:dangling-cross-ref :dangling-citation :duplicate-id :kind-role-mismatch}
             (set (map :type diagnostics)))
          (pr-str (map :type diagnostics)))
      (is (well-formed? output) "the build still produced a complete document")
      (is (str/includes? output "??") "the dangling reference's placeholder is in the output")
      (is (every? (comp string? :message) diagnostics)
          "every diagnostic carries a message for the CLI to print")))
  (testing "AC #4: an unknown directive IS reported as a warning, but it
            also has no representation for any target -- so the build
            aborts, and the warning is carried on the thrown error so it
            still reaches the user. Without that, the one diagnostic
            naming the offending directive would be swallowed by the
            abort it caused"
    (let [dir (fixture-dir! ":::{widget}\nx\n:::\n")
          e (is (thrown? clojure.lang.ExceptionInfo (build! dir {})))
          carried (:haselnuss.cli/diagnostics (ex-data e))]
      (is (= [:unknown-directive] (map :type carried)))
      (is (str/includes? (:message (first carried)) "widget")))))

(deftest unescapable-image-path-diagnostic-test
  (testing "TASK-78: an Image :src with a raw backslash reaches the CLI's
            own diagnostics on the LaTeX target -- an Image :src is a
            filename this emitter cannot safely rewrite (unlike a Link
            :target, TASK-78's other half, which is silently fixed
            instead), so the build says so rather than exiting 0 on a
            .tex a real pdflatex refuses"
    (let [dir (fixture-dir! (str/replace document "pic.png" "pic\\x.png"))
          {:keys [diagnostics]} (build! dir {:target "latex"})]
      (is (= [:unescapable-image-path] (map :type diagnostics)))
      (is (str/includes? (:message (first diagnostics)) "pic\\x.png"))))
  (testing "and HTML, which has no such hazard, reports nothing"
    (let [dir (fixture-dir! (str/replace document "pic.png" "pic\\x.png"))
          {:keys [diagnostics]} (build! dir {})]
      (is (empty? diagnostics))))
  (testing "TASK-89 review finding #2's own regression: an Image :src with
            an UNBALANCED BRACE -- the other of the two hazards
            image-path-hazards checks for, on ordinary core content, no
            extension or :raw involved -- also reaches the CLI's own
            diagnostics. This used to work before TASK-89 (haselnuss.cli
            called the AST-based check unconditionally), broke silently
            when that call was replaced with a text-based scan whose
            regex could not locate an argument containing a brace, and is
            fixed here by locating the argument's real closing brace
            directly (haselnuss.emit.latex/includegraphics-arg-end)"
    (let [dir (fixture-dir! (str/replace document "pic.png" "pic{x.png"))
          {:keys [diagnostics]} (build! dir {:target "latex"})]
      (is (= [:unescapable-image-path] (map :type diagnostics))
          "must not be silently empty -- that was the regression")
      (is (str/includes? (:message (first diagnostics)) "unbalanced brace"))))
  (testing "and the combined hazard (both a backslash and an unbalanced
            brace in the same path) also reaches the CLI's own
            diagnostics, naming both"
    (let [dir (fixture-dir! (str/replace document "pic.png" "pic\\x{y.png"))
          {:keys [diagnostics]} (build! dir {:target "latex"})]
      (is (= [:unescapable-image-path] (map :type diagnostics)))
      (is (str/includes? (:message (first diagnostics)) "backslash"))
      (is (str/includes? (:message (first diagnostics)) "unbalanced brace")))))

(deftest unescapable-image-path-diagnostic-stray-closing-brace-test
  (testing "TASK-89 review comments #16/#17: an Image :src whose own
            hazard is a stray CLOSING brace with no matching open before
            it (a narrower shape than finding #2's own stray-OPEN
            regression above) is now an ACCEPTED, documented limitation
            rather than something this pipeline detects, for a core
            Image the same as for any other `\\includegraphics{...}`
            occurrence -- haselnuss.cli went through two rounds
            (comment #11's AST-based fallback, comment #12's dedup
            machinery for it) of trying to close this specific gap, both
            of which review comments #16/#17 then found their own real,
            blocking bugs in (a false negative unrelated to this shape at
            all, and a plain regression in ordinary duplicate-diagnostic
            collapsing) -- so the AST-based fallback and the
            reconciliation it needed were removed rather than patched a
            sixth time, and this shape genuinely goes unreported now.
            `unescapable-image-path-text-diagnostics`'s own
            depth-tracking reads the argument as closing at the FIRST
            `}` (this src's own, not a stray one), so it sees a balanced,
            hazard-free argument where the AST value is not -- see that
            function's own docstring, and haselnuss.registry's
            `:trusted?` docstring, for the accepted reasoning. The
            severity is bounded, not silent-success: a real `pdflatex`
            run on a document like this still fails to compile (TASK-89
            review comment #11's own live verification), just without
            this earlier, more specific warning naming the path"
    (let [dir (fixture-dir! (str/replace document "pic.png" "pic}x.png"))
          {:keys [diagnostics]} (build! dir {:target "latex"})]
      (is (empty? diagnostics)
          "the documented, accepted gap -- not silently reintroducing the
           AST-based fallback that review comments #16/#17 found needed
           its own now-removed reconciliation machinery to stay correct"))))

(defn- widget-registry
  "`cli/build-registry`, with one further directive registered: a TRUSTED
  v2 LaTeX renderer named \"widget\" whose entire output is the literal
  `raw-tex` string, spliced verbatim via `[:raw ...]` (TASK-89's own
  escape hatch, gated on `:trusted?`). Meant to be installed with
  `with-redefs` over `cli/build-registry` itself (TASK-89 review comments
  #8/#11/#13's own technique): `haselnuss.cli` has no extension-injection
  hook yet -- that is TASK-90's job -- so this is the only way to route a
  custom directive through the REAL `cli/run`/`cli/build` pipeline today.

  Captures `cli/build-registry`'s CURRENT value in `build-registry`
  before building its own replacement function, rather than referring to
  `cli/build-registry` again from inside the returned closure: inside a
  `with-redefs` binding form the var is not yet rebound while its OWN
  binding expression (a call to this function) runs, so capturing it here
  is what lets the replacement call the ORIGINAL `build-registry`.
  Referring to the var again from inside the closure would call the
  replacement itself once installed, recursing until the stack overflows
  -- found live while building this helper."
  [raw-tex]
  (let [build-registry cli/build-registry
        widget {:name "widget"
                :trusted? true
                :emit {:latex (registry/renderer-v2 (fn [_directive _ctx] [:raw raw-tex]))}}]
    (fn
      ([] (registry/register (build-registry) widget))
      ([labels] (registry/register (build-registry labels) widget)))))

(deftest ^:serial unescapable-image-path-diagnostic-independent-hazards-test
  (testing "TASK-89 review comments #16/#18: an ordinary core Image's own
            hazard and an UNRELATED trusted extension's own independent
            :raw hazard, both STRAY-OPEN-brace shaped -- the one shape
            `unescapable-image-path-text-diagnostics` genuinely detects
            for either kind of content -- are both reported, as two
            separate diagnostics. This used to need real reconciliation
            machinery (TASK-89 review comment #13's own multiset fix, and
            two further blocking bugs found in it, comments #16/#17)
            because an AST-based scan and a text-based scan of
            OVERLAPPING content both fed `:diagnostics`; now that the
            AST-based check is not called from haselnuss.cli at all
            (comments #16/#18), the text-based scan is the ONLY source of
            this diagnostic type, so two genuinely independent hazards
            simply both show up -- there is nothing left to de-duplicate
            or mis-attribute"
    (with-redefs [cli/build-registry (widget-registry "\\includegraphics{bar{baz")]
      (let [{:keys [diagnostics]}
            (cli/run "![alt](foo{qux)\n\n:::{widget}\nx\n:::\n" {:target "latex"})]
        (is (= 2 (count diagnostics))
            "the core image's own hazard and the extension's independent
             one are unrelated and both real -- neither may be dropped")
        ;; "foo{qux}", not "foo{qux": the core image's own hazard never
        ;; finds a true close (the stray `{` desyncs depth, so the
        ;; renderer's own closing brace only brings depth back to 1, not
        ;; 0), so the text-based scan's own documented fallback -- run to
        ;; the end of the line -- captures that trailing `}` too
        ;; (`haselnuss.emit.latex/includegraphics-arg-end`'s own
        ;; docstring). Confirmed live rather than assumed.
        (is (= #{"foo{qux}" "bar{baz"} (set (map :src diagnostics))))))))

(deftest dangling-cross-ref-repeated-reference-is-not-collapsed-test
  (testing "TASK-89 review comment #21: two references to the SAME
            missing cross-reference id -- an ordinary, plausible
            authoring mistake, not a contrived edge -- produce TWO
            :dangling-cross-ref warnings, not one, on both targets. This
            is haselnuss.resolver's own, unmodified design (confirmed by
            its own resolver-test coverage,
            multiple-dangling-cross-refs-all-produce-warnings-test, and
            by its own docstring: one warning per OCCURRENCE, not
            de-duplicated per key) -- haselnuss.cli's own diagnostics
            assembly does not, and structurally need not, de-duplicate
            anything: `diagnostics` (resolver/lower/overlay-level) and
            the final text-based scan are fully disjoint in type and
            source, with nothing left to reconcile between them, so
            `run` simply concatenates them.

            A `distinct` briefly lived in haselnuss.cli's own final
            :diagnostics assembly (comments #19-20) on the belief that it
            RESTORED this exact pre-TASK-89 behavior for a repeated
            dangling reference -- comment #21 checked that directly
            against the actual pre-TASK-89 commit (a git worktree at
            d751e26, this task's own AC #10 baseline) rather than against
            this task's own prose about it, and found the claim false:
            pre-TASK-89 `run` never de-duplicated anything anywhere, and
            two references to the same missing id always produced two
            identical warnings, both surfaced. This test pins THAT, the
            real original (and current) behavior, rather than the
            plausible-sounding but incorrect '1' this task's own review
            briefly shipped and mis-described as a restoration"
    (testing "latex"
      (let [{:keys [diagnostics]}
            (cli/run "See @fig:missing and again @fig:missing.\n" {:target "latex"})]
        (is (= 2 (count diagnostics)))
        (is (every? #(= :dangling-cross-ref (:type %)) diagnostics))))
    (testing "html"
      (let [{:keys [diagnostics]}
            (cli/run "See @fig:missing and again @fig:missing.\n" {:target "html"})]
        (is (= 2 (count diagnostics)))
        (is (every? #(= :dangling-cross-ref (:type %)) diagnostics))))))

(deftest unrecoverable-errors-test
  (testing "AC #5: a directive with no representation for the target and
            no fallback aborts the build, naming the offending directive"
    (let [dir (fixture-dir! ":::{widget}\nx\n:::\n")
          e (is (thrown? clojure.lang.ExceptionInfo (build! dir {:target "latex"})))]
      (is (= :haselnuss.lower/no-representation (:type (ex-data e))))
      (is (= "widget" (:name (ex-data e))))
      (is (not (.exists (io/file dir "doc.tex")))
          "a failed build must leave no output file behind")))
  (testing "AC #5: a parse error aborts the build too"
    (let [dir (fixture-dir! ":::{note}\nunterminated\n")]
      (is (thrown? clojure.lang.ExceptionInfo (build! dir {})))))
  (testing "AC #5 / TASK-138: a missing input file is an input error, not a usage error"
    (let [e (is (thrown? clojure.lang.ExceptionInfo
                         (cli/build {:input "/nonexistent/nope.hdoc" :target "html"})))]
      (is (= :haselnuss.cli/input-error (:type (ex-data e))))))
  (testing "TASK-113 AC #1: a directory given as the input is an input
            error too, but reports that it IS a directory rather than
            reusing the missing-file message -- the path is there, it is
            simply not a file, the same distinction
            `haselnuss.resolver/expand-includes` already draws for an
            `!include` one layer down"
    (let [dir (str (Files/createTempDirectory "haselnuss-cli-test-dir"
                                              (make-array FileAttribute 0)))
          e (is (thrown? clojure.lang.ExceptionInfo
                         (cli/build {:input dir :target "html"})))]
      (is (= :haselnuss.cli/input-error (:type (ex-data e))))
      (is (str/includes? (ex-message e) "directory"))
      (is (str/includes? (ex-message e) dir))
      (is (not (str/includes? (ex-message e) "not found"))
          "the message must not claim the directory is missing")))
  (testing "a document naming a bibliography that is not there fails by
            name, rather than silently dangling every citation"
    (let [dir (fixture-dir!)]
      (io/delete-file (io/file dir "refs.json"))
      (doseq [target ["html" "latex"]]
        (let [e (is (thrown? clojure.lang.ExceptionInfo (build! dir {:target target})) target)]
          (is (= :haselnuss.cli/missing-bibliography (:type (ex-data e))) target)
          (is (str/includes? (ex-message e) (str (io/file dir "refs.json"))) target))))))

(deftest json-hdoc-targets-tolerate-missing-bibliography-test
  (testing "TASK-107 AC #1: a document declaring a bibliography converts
            to --target json and --target hdoc with that bibliography
            absent from disk -- neither target reads its content, only
            (via the parser's own vocabulary) whether a bare @word is a
            citation at all"
    (let [dir (fixture-dir!)]
      (io/delete-file (io/file dir "refs.json"))
      (doseq [target ["json" "hdoc"]
              ;; --output given explicitly for hdoc: its own derived path
              ;; is doc.hdoc, the same as the input, which output-path
              ;; already (correctly) refuses to overwrite -- unrelated to
              ;; this test's own concern.
              :let [out (str (io/file dir (str "out." target)))]]
        (let [{:keys [output-path]} (build! dir {:target target :output out})]
          (is (.exists (io/file output-path)) target)))))
  (testing "AC #4: the meta.bibliography declaration still round-trips
            through the hdoc target unchanged, even though the file it
            names was never opened"
    (let [dir (fixture-dir!)]
      (io/delete-file (io/file dir "refs.json"))
      (let [{:keys [output]} (build! dir {:target "hdoc" :output (str (io/file dir "out.hdoc"))})]
        (is (str/includes? output "bibliography: refs.json")))))
  (testing "AC #2: --in-place succeeds on such a document, since it
            implies --target hdoc"
    (let [dir (fixture-dir!)
          input (str (io/file dir "doc.hdoc"))]
      (io/delete-file (io/file dir "refs.json"))
      (cli/build {:input input :target "hdoc" :in-place true})
      (is (str/includes? (slurp input) "bibliography: refs.json"))))
  (testing "AC #3: the html and latex targets keep failing, naming the
            file, when a bibliography they need is missing -- unaffected
            by the json/hdoc relaxation above"
    (let [dir (fixture-dir!)]
      (io/delete-file (io/file dir "refs.json"))
      (doseq [target ["html" "latex"]]
        (let [e (is (thrown? clojure.lang.ExceptionInfo (build! dir {:target target})) target)]
          (is (= :haselnuss.cli/missing-bibliography (:type (ex-data e))) target))))))

(deftest bibliography-resolution-test
  (testing "a relative meta.bibliography resolves against the DOCUMENT's
            own directory, not the process working directory -- an author
            writes it next to the .hdoc, and the CLI may be run from
            anywhere"
    (let [dir (fixture-dir!)
          {:keys [output]} (build! dir {})]
      (is (str/includes? output "Knuth, D. (1984). The TeXbook."))))
  (testing "--bibliography overrides the document's own declaration"
    (let [dir (fixture-dir!)
          other (str (io/file dir "other.json"))]
      (spit other (str/replace bibliography "The TeXbook" "A Different Book"))
      (let [{:keys [output]} (build! dir {:bibliography other})]
        (is (str/includes? output "A Different Book"))
        (is (not (str/includes? output "The TeXbook")))))))

(deftest built-in-directives-work-on-both-targets-test
  (testing "the two directives the CLI registers out of the box -- a
            LaTeX-native one (theorem) and an HTML-native one
            (collapsable) -- each convert on BOTH targets. Each was
            native on one and had no representation at all on the other
            until a registry :lower rule was added for it, which is a
            build failure an author has no way to work around:
            haselnuss.parser constructs no :fallback field, so sec8.3's
            third branch can never fire for an authored document"
    (let [source (str "# Intro {#sec:intro}\n\n"
                      ":::{theorem #thm:main}\nAll good things end.\n:::\n\n"
                      ":::{collapsable summary=Details}\nThe hidden content.\n:::\n")
          dir (fixture-dir! source)
          html (:output (build! dir {}))
          tex (:output (build! dir {:target "latex"}))]
      (is (well-formed? html))
      (is (str/includes? html "<details") "collapsable is native on html")
      (is (str/includes? html "<strong>Theorem 1</strong>") "theorem degrades on html, numbered")
      (is (str/includes? tex "\\begin{theorem}") "theorem is native on latex")
      (is (str/includes? tex "\\textbf{Details}") "collapsable degrades on latex")
      (let [[ok? log] (compiles? dir tex)]
        (is ok? (str "the degraded latex must still compile, pdflatex output:\n" log))))))

(deftest bibliography-override-reaches-latex-test
  (testing "--bibliography reaches the database native mode hands BibTeX,
            so a build does not resolve citations against one file while
            BibTeX builds the reference list from another. Since TASK-42
            that database is GENERATED from whatever bibliography was
            actually loaded, so the requirement is about its content
            rather than about which filename the .tex names"
    (let [dir (fixture-dir!)
          other (str (io/file dir "other.json"))]
      (spit other (str/replace bibliography "The TeXbook" "A Different Book"))
      (let [{:keys [output bibtex-path]} (build! dir {:target "latex" :bibliography other})]
        (is (str/includes? output "\\bibliography{doc}"))
        (is (some? bibtex-path))
        (let [bib (slurp bibtex-path)]
          (is (str/includes? bib "A Different Book")
              "the generated database must carry the overriding file's own data")
          (is (not (str/includes? bib "The TeXbook"))))))))

(deftest labels-come-from-the-resolver-test
  (testing "the emitters read the same label table every CrossRef's text
            was baked from -- resolve-document returns it rather than
            the CLI recomputing one, so a caption and a reference to it
            can never disagree"
    (let [dir (fixture-dir!)
          html (:output (build! dir {}))]
      (is (str/includes? html "<figcaption>Figure 1.1: A hazel tree.</figcaption>"))
      (is (str/includes? html "<a href=\"#fig:tree\">Figure 1.1</a>")))))

(deftest degraded-directive-head-test
  (testing "TASK-29 review: the degraded head is the environment's OWN
            display word, never an invented one -- an invented head made
            the two targets structurally disagree, HTML showing a bold
            \"Admonition\" heading that appeared nowhere in the PDF.
            Since TASK-40 the admonition prints \"Note\" in both, so the
            word asserted here is the one its LaTeX environment prints,
            not the capitalized directive name"
    (let [source (str "# Intro {#sec:intro}\n\n"
                      ":::{admonition}\nCareful.\n:::\n\n"
                      ":::{proof}\nTrivial.\n:::\n")
          dir (fixture-dir! source)
          html (:output (build! dir {}))]
      (is (not (str/includes? html "<strong>Admonition</strong>"))
          "the degradation must use the environment's head word, not the directive's name")
      (is (str/includes? html "<strong>Note</strong>")
          "which for admonition is \"Note\", matching what hnadmonition prints")
      (is (str/includes? html "<strong>Proof</strong>")
          "proof's amsthm environment DOES print a head, so its degradation reproduces it")
      (is (= (count (re-seq #"<strong>" html)) 2)
          "exactly the two heads, so a third invented one would fail here")
      (testing "and the degraded node still says what it was, so parity
                is not bought by erasing the only marker"
        (is (str/includes? html "<div class=\"admonition\" role=\"note\">"))
        (is (str/includes? html "<div class=\"proof\">")))
      (testing "TASK-139: a theorem's caption= is not written out as an
                invalid attribute (LaTeX never prints it either)"
        (let [dir2 (fixture-dir! ":::{theorem #thm:c caption=\"Named\"}\nBody.\n:::\n")
              html2 (:output (build! dir2 {}))]
          (is (not (str/includes? html2 "caption=")))
          (is (str/includes? html2 "<div id=\"thm:c\" class=\"theorem\">"))))
      (testing "an authored class is kept alongside the directive's name"
        (let [out (:output (build! (fixture-dir! ":::{admonition .warning}\nx\n:::\n") {}))]
          (is (str/includes? out "class=\"warning admonition\"")))))))

(deftest image-width-test
  (testing "TASK-29 review: an authored width reaches LaTeX, not only
            HTML. It used to be dropped for print, so an author whose
            figure ran off the page had no way to fix it from the
            document"
    (let [dir (fixture-dir! "![A tree](pic.png){#fig:t width=50%}\n")]
      (is (str/includes? (:output (build! dir {})) "width=\"50%\""))
      (is (str/includes? (:output (build! dir {:target "latex"}))
                         "\\includegraphics[width=0.5\\linewidth]{pic.png}"))))
  (testing "and with no authored width an image is clamped to the text
            block rather than overflowing the page -- the dogfood
            figure overflowed by 137pt, off the edge of the paper, with
            pdflatex reporting only a warning and exiting 0"
    (let [dir (fixture-dir! "![A tree](pic.png){#fig:t}\n")]
      (is (str/includes? (:output (build! dir {:target "latex"}))
                         "\\includegraphics[max width=\\linewidth]{pic.png}"))))
  (testing "a non-percentage width passes through as a LaTeX dimension"
    (let [dir (fixture-dir! "![A tree](pic.png){#fig:t width=3cm}\n")]
      (is (str/includes? (:output (build! dir {:target "latex"}))
                         "\\includegraphics[width=3cm]{pic.png}")))))

(deftest run-is-pure-test
  (testing "the pure core takes source text and returns the emitted
            document plus diagnostics, writing nothing -- which is what
            makes every assertion above testable without a subprocess"
    (let [{:keys [output diagnostics]} (cli/run "# Hi {#sec:hi}\n\ntext\n" {:target "html"})]
      (is (well-formed? output))
      (is (= [] diagnostics)))))

(defn- main-run
  "Runs `cli/main*` with `args`, capturing stdout and stderr. Returns
  `{:status :out :err}` -- the literal thing AC #4/#5 are about, rather
  than the data structures behind it."
  [& args]
  (let [err (java.io.StringWriter.)
        out (java.io.StringWriter.)
        status (binding [*err* err *out* out] (cli/main* args no-libraries))]
    {:status status :out (str out) :err (str err)}))

(defn- no-warnings?
  "True if `err` -- captured stderr from `main-run` -- carries no
  `haselnuss: warning:` line. NOT `(= \"\" err)`: TASK-100 moved every
  `haselnuss: wrote <path>` progress line from stdout to stderr,
  unconditionally, so a clean build's own stderr carries that line even
  with no diagnostic at all -- only a build with a warning carries this
  one."
  [err]
  (not (str/includes? err "haselnuss: warning:")))

(deftest stderr-and-exit-status-test
  (testing "AC #4: every resolver diagnostic is printed to stderr as a
            warning, and the build still succeeds -- exit 0, output
            written"
    (let [dir (fixture-dir! (str "# Intro {#sec:intro}\n\n"
                                 "Missing @fig:nope and [@nobody2000].\n\n"
                                 "## Dup {#sec:intro}\n\ntext\n"))
          input (str (io/file dir "doc.hdoc"))
          {:keys [status out err]} (main-run input)]
      (is (= 0 status))
      (is (.exists (io/file dir "doc.html")))
      (is (= "" out) "TASK-100: stdout carries the document alone, never a progress line")
      (is (str/includes? err "haselnuss: wrote"))
      (is (= 3 (count (re-seq #"haselnuss: warning: " err))) err)
      (is (str/includes? err "dangling cross-reference"))
      (is (str/includes? err "dangling citation"))
      (is (str/includes? err "duplicate id"))
      (is (not (str/includes? err "haselnuss: error:")))))
  (testing "AC #5: an unrecoverable error prints a clear message to
            stderr and exits non-zero, writing no output"
    (let [dir (fixture-dir! ":::{widget}\nx\n:::\n")
          {:keys [status err]} (main-run (str (io/file dir "doc.hdoc")))]
      (is (= 1 status))
      (is (str/includes? err "haselnuss: error: no representation for directive \"widget\""))
      (is (str/includes? err "name \"widget\"") "the error names the offending directive")
      (is (not (.exists (io/file dir "doc.html"))))))
  (testing "AC #4/#5 together: warnings collected before a failure are
            still printed, and printed BEFORE the error that stopped the
            build -- the warning is often what explains it"
    (let [dir (fixture-dir! ":::{widget}\nx\n:::\n")
          {:keys [status err]} (main-run (str (io/file dir "doc.hdoc")))]
      (is (= 1 status))
      (is (< (.indexOf err "haselnuss: warning: unknown directive")
             (.indexOf err "haselnuss: error:")))))
  (testing "a usage error exits 2 and prints the usage text; --help
            exits 0 and prints it to stdout instead"
    (let [bad (main-run "--nope" "x.hdoc")]
      (is (= 2 (:status bad)))
      (is (str/includes? (:err bad) "haselnuss: error: unknown option"))
      (is (str/includes? (:err bad) "--help"))
      (is (not (str/includes? (:err bad) "Usage: haselnuss"))
          "TASK-138: only a one-line pointer, not the whole usage text")
      (is (< (count (str/split-lines (:err bad))) 4)))
    (let [help (main-run "--help")]
      (is (= 0 (:status help)))
      (is (str/includes? (:out help) "Usage: haselnuss"))
      (is (= "" (:err help))))
    (is (= 2 (:status (main-run))) "no arguments at all is a usage error"))
  (testing "--version exits 0 and prints the version, without needing an
            input file -- it is a question about the program, not a
            request to convert something"
    (let [{:keys [status out err]} (main-run "--version")]
      (is (= 0 status))
      (is (= "" err))
      (is (re-find #"^haselnuss \S+\n$" out))
      (testing "and the version is this project's own, not a placeholder
                or a string that could drift from project.clj"
        (let [declared (nth (read-string (slurp "project.clj")) 2)]
          (is (str/includes? out declared))))))
  (testing "--version is listed in the usage text alongside --help, so a
            user who does not already know it exists can find it"
    (let [{:keys [out]} (main-run "--help")]
      (is (str/includes? out "--version"))))
  (testing "--version behaves like --help in the rest of the parser: help
            wins when both are given, and -- ends option parsing so a
            literal --version becomes an (absent) input path rather than
            printing the version"
    (let [{:keys [out]} (main-run "--help" "--version")]
      (is (str/includes? out "Usage: haselnuss"))
      (is (not (re-find #"(?m)^haselnuss \S+$" out))))
    (let [{:keys [out]} (main-run "--version" "--help")]
      (is (str/includes? out "Usage: haselnuss")))
    (let [{:keys [status err]} (main-run "--" "--version")]
      (is (= 1 status))
      (is (str/includes? err "input file not found: --version")))))

(deftest directory-vs-missing-vs-unreadable-input-test
  (testing "TASK-113: the three ways the input path can fail to open get
            three different, individually correct treatments -- this
            pins all three together so a future change to one cannot
            silently blur it back into another"
    (testing "AC #2: a genuinely nonexistent path is reported as not
              found, exit 1 (TASK-138), no usage text"
      (let [{:keys [status err]} (main-run "/nonexistent/nope.hdoc")]
        (is (= 1 status))
        (is (str/includes? err "haselnuss: error: input file not found: /nonexistent/nope.hdoc"))
        (is (not (str/includes? err "Usage: haselnuss")))))
    (testing "AC #1: a directory is reported as a directory, not as
              missing, exit 1 with no usage text (TASK-138) -- same
              treatment as the nonexistent-path case above except for
              the message itself, which is now true of what is actually
              there"
      (let [dir (str (Files/createTempDirectory "haselnuss-cli-test-dir"
                                                (make-array FileAttribute 0)))
            {:keys [status err]} (main-run dir)]
        (is (= 1 status))
        (is (str/includes? err (str "haselnuss: error: input path is a directory: " dir)))
        (is (not (str/includes? err "not found")))
        (is (not (str/includes? err "Usage: haselnuss")))))
    (testing "AC #3: a file that exists but cannot be read keeps its
              current message and exit code -- unaffected by the
              directory check above, since it is a plain file as far as
              `.isFile` is concerned"
      (let [dir (fixture-dir! "# Hi {#sec:hi}\n\ntext\n")
            input (io/file dir "doc.hdoc")
            path (.toPath input)]
        (Files/setPosixFilePermissions path (PosixFilePermissions/fromString "---------"))
        (if (.canRead input)
          ;; Running as a user this OS lets read its own unreadable
          ;; file (root, most often) -- nothing meaningful to assert.
          (println "skipping AC #3 permission test: file is still readable")
          (let [{:keys [status err]} (main-run (str input))]
            (is (= 1 status))
            (is (str/includes? err (str "haselnuss: error: " input " (Permission denied)")))
            (is (not (str/includes? err "Usage: haselnuss")))))
        (Files/setPosixFilePermissions path (PosixFilePermissions/fromString "rw-r--r--"))))))

(deftest arg-parsing-test
  (let [dir (fixture-dir! "# Hi {#sec:hi}\n\ntext\n")
        input (str (io/file dir "doc.hdoc"))]
    (testing "--flag=value parses the same as --flag value, the GNU-style
              spelling a hand-rolled parser most often forgets"
      (let [{:keys [status]} (main-run (str "--output=" dir "/eq.html") input)]
        (is (= 0 status))
        (is (.exists (io/file dir "eq.html")))))
    (testing "an option whose value is itself flag-shaped is a usage
              error, not a silently-accepted value"
      (let [{:keys [status err]} (main-run "-t" "-o" "out.html" input)]
        (is (= 2 status))
        (is (str/includes? err "requires a value"))))
    (testing "-- ends option parsing, so an input path may start with a dash"
      (let [dashed (io/file dir "-dashed.hdoc")]
        (io/copy (io/file input) dashed)
        (let [{:keys [status]} (main-run "--" (str dashed))]
          (is (= 0 status)))))
    (testing "the build refuses to overwrite its own input rather than
              destroying the document it is reading"
      (let [same (io/file dir "notes.html")]
        (io/copy (io/file input) same)
        (let [{:keys [status err]} (main-run (str same))]
          (is (= 2 status))
          (is (str/includes? err "refusing to overwrite the input file")))))
    (testing "a dotfile input writes beside itself, not a hidden file
              named .html in the current directory"
      (let [dotfile (io/file dir ".hidden")]
        (io/copy (io/file input) dotfile)
        (let [{:keys [status]} (main-run (str dotfile))]
          (is (= 0 status))
          (is (.exists (io/file dir ".hidden.html"))))))))

(deftest destructive-and-invalid-input-test
  (let [dir (fixture-dir! "# Hi {#sec:hi}\n\ntext\n")
        input (str (io/file dir "doc.hdoc"))]
    (testing "-o pointing at the input is refused too, not just the
              derived path -- the guard used to sit in the derived
              branch only, while its own message told the user to pass
              exactly this flag, so `-o victim.hdoc victim.hdoc` quietly
              replaced the source document with its own HTML"
      (let [{:keys [status err]} (main-run "-o" input input)
            after (slurp input)]
        (is (= 2 status))
        (is (str/includes? err "refusing to overwrite the input file"))
        (is (str/includes? after "# Hi") "the source document must be untouched")))
    (testing "a value on a flag that takes none is a usage error, not
              silently discarded -- --computed-numbers=false used to turn
              the flag ON"
      (doseq [arg ["--computed-numbers=false" "--help=nonsense" "--version=nonsense"
                   "--no-stylesheet=false" "--trusted-extensions=nonsense"
                   "--untrusted=false"]]
        (let [{:keys [status err]} (main-run arg input)]
          (is (= 2 status) arg)
          (is (str/includes? err "takes no value") arg))))
    (testing "run/build are public, so they validate the target
              themselves rather than dying in a case clause several
              steps later"
      (let [e (is (thrown? clojure.lang.ExceptionInfo (cli/run "text\n" {:target "pdf"})))]
        (is (= :haselnuss.cli/usage-error (:type (ex-data e))))))))

(deftest bibliography-output-collision-test
  (testing "TASK-81: --target json's default output path (same base name
            as the input, .json extension) collides with a CSL-JSON
            bibliography of that same base name -- exactly the shape of
            examples/hazelnuts.hdoc/examples/hazelnuts.json, and running
            this combination against the real files once silently
            replaced the bibliography with the AST dump"
    (let [dir (fixture-dir! (str "---\nbibliography: doc.json\n---\n\n" "# Hi {#sec:hi}\n\ntext\n"))
          input (str (io/file dir "doc.hdoc"))
          bib (io/file dir "doc.json")
          before (do (spit bib "[]") (slurp bib))
          {:keys [status err]} (main-run "--target" "json" input)]
      (is (= 2 status))
      (is (str/includes? err "refusing to overwrite the bibliography file"))
      (is (= before (slurp bib)) "the bibliography must be left untouched")
      (is (not (.exists (io/file dir "doc.json.json")))
          "the build must not have silently picked a different path either")))
  (testing "an explicit --output pointing at the bibliography is refused
            too, not just the derived default path"
    (let [dir (fixture-dir!)
          input (str (io/file dir "doc.hdoc"))
          bib (str (io/file dir "refs.json"))
          before (slurp bib)
          {:keys [status err]} (main-run "--target" "json" "-o" bib input)]
      (is (= 2 status))
      (is (str/includes? err "refusing to overwrite the bibliography file"))
      (is (= before (slurp bib)) "the bibliography must be left untouched"))))

(deftest hdoc-target-input-overwrite-test
  (testing "AC #3 (TASK-98): hdoc is the first target whose own default
            extension (.hdoc) matches a typical input's, so with no
            --output the derived path IS the input -- refused by the
            existing overwrite guard, and the input left byte-identical"
    (let [dir (fixture-dir! hdoc-target-fixture)
          input (str (io/file dir "doc.hdoc"))
          before (slurp input)
          {:keys [status err]} (main-run "--target" "hdoc" input)]
      (is (= 2 status))
      (is (str/includes? err "refusing to overwrite the input file"))
      (is (= before (slurp input)) "the input must be left byte-identical")))
  (testing "AC #4: an explicit -o pointing at the input is refused the
            same way, not just the derived default path"
    (let [dir (fixture-dir! hdoc-target-fixture)
          input (str (io/file dir "doc.hdoc"))
          before (slurp input)
          {:keys [status err]} (main-run "--target" "hdoc" "-o" input input)]
      (is (= 2 status))
      (is (str/includes? err "refusing to overwrite the input file"))
      (is (= before (slurp input)) "the input must be left byte-identical")))
  (testing "AC #1: --help lists hdoc alongside html/latex/json"
    (let [{:keys [out]} (main-run "--help")]
      (is (str/includes? out "hdoc")))))

(deftest stdout-output-test
  (testing "AC #1/#2 (TASK-100): --output - writes the converted document to
            stdout, byte for byte the same as an ordinary build's own output
            file, for every target -- and \"haselnuss: wrote\" moves to
            stderr, unconditionally, so stdout carries the document alone"
    (doseq [[target extra-args] [["html" []] ["latex" ["--computed-numbers"]] ["json" []] ["hdoc" []]]]
      ;; --computed-numbers on latex only: the default fixture declares a
      ;; bibliography and cites it, so a plain native-mode build would also
      ;; generate a companion .bib -- exactly what AC #4, tested below, is
      ;; about. Bake the numbers in instead, the same way a document
      ;; emitted to HTML too already has to (see `usage`'s own text).
      (let [dir (fixture-dir!)
            input (str (io/file dir "doc.hdoc"))
            expected-path (str (io/file dir "expected.out"))
            {file-status :status file-err :err}
            (apply main-run (concat ["--target" target] extra-args ["-o" expected-path input]))
            {:keys [status out err]}
            (apply main-run (concat ["--target" target] extra-args ["--output" "-" input]))]
        (is (= 0 file-status) (str target ": " file-err))
        (is (= 0 status) (str target ": " err))
        (is (= (slurp expected-path) out)
            (str target ": stdout must carry exactly what a normal build writes to a file"))
        (is (not (str/includes? out "haselnuss:")) (str target ": nothing but the document on stdout"))
        (is (str/includes? err "haselnuss: wrote -") (str target ": the progress line moved to stderr")))))
  (testing "AC #3: the input-overwrite and bibliography-collision guards do
            not fire for --output -, since - is not a path on disk for
            either of them to check against"
    (testing "hdoc target: with a real --output, the derived path -- the one
              neither guard is ever consulted for here -- would otherwise
              collide with the input itself (hdoc-target-input-overwrite-
              test); --output - has no derived path at all"
      (let [dir (fixture-dir! hdoc-target-fixture)
            input (str (io/file dir "doc.hdoc"))
            before (slurp input)
            {:keys [status out err]} (main-run "--target" "hdoc" "--output" "-" input)]
        (is (= 0 status) err)
        (is (= before (slurp input)) "the input itself must be untouched")
        (is (str/includes? out ":::{theorem #thm:main}") "the document reached stdout")))
    (testing "json target: the derived output path would otherwise collide
              with a same-named CSL-JSON bibliography (bibliography-output-
              collision-test); --output - has no derived path to collide,
              and the bibliography survives untouched"
      (let [dir (fixture-dir! (str "---\nbibliography: doc.json\n---\n\n" "# Hi {#sec:hi}\n\ntext\n"))
            input (str (io/file dir "doc.hdoc"))
            bib (io/file dir "doc.json")
            before (do (spit bib "[]") (slurp bib))
            {:keys [status err]} (main-run "--target" "json" "--output" "-" input)]
        (is (= 0 status) err)
        (is (= before (slurp bib)) "the bibliography must be left untouched"))))
  (testing "AC #4: a build that would also have to write a companion file
            BESIDE the document is refused entirely, naming the file that
            has nowhere to go, rather than writing the document to stdout
            and silently dropping its companion"
    (testing "a native-mode LaTeX bibliography -- the default fixture
              declares one and cites it, so an ordinary native build
              generates doc.bib beside doc.tex (generated-bibtex-database-
              test)"
      (let [dir (fixture-dir!)
            input (str (io/file dir "doc.hdoc"))
            {:keys [status err]} (main-run "--target" "latex" "--output" "-" input)]
        (is (= 1 status))
        (is (str/includes? err "has nowhere to go"))
        ;; TASK-109 AC #3: named the exact same way the .tex itself would
        ;; have been (absolute, here, since `input` -- and so the path
        ;; `derived-output-path` names the companion after -- is), not
        ;; the `.getAbsoluteFile`-derived absolute path this used to
        ;; report regardless.
        (is (str/includes? err (str (io/file dir "doc.bib"))))
        (is (not (.exists (io/file dir "doc.bib"))))
        (is (not (.exists (io/file dir "doc.tex")))
            "the document itself must not have been written either")))
    (testing "a --fragment build's own companion preamble -- --computed-
              numbers keeps native mode's own .bib companion out of this
              one, so only the preamble is at stake"
      (let [dir (fixture-dir!)
            input (str (io/file dir "doc.hdoc"))
            {:keys [status err]} (main-run "--target" "latex" "--computed-numbers" "--fragment"
                                           "--output" "-" input)]
        (is (= 1 status))
        (is (str/includes? err "has nowhere to go"))
        (is (str/includes? err "doc-preamble.tex"))
        (is (not (.exists (io/file dir "doc-preamble.tex"))))))
    (testing "a front-matter block's own side file -- a front-matter block
              only ever exists inside a --fragment build, and every
              --fragment build also has a companion preamble
              (`latex/emit-preamble` is unconditional on document
              content), so the preamble refusal above always fires first
              for this combination; there is no build that produces a
              front-matter side file without also producing a preamble,
              so `err` names the preamble here too, not the front-matter
              file -- see the comment at `build`'s own
              `refuse-stdout-companion!` call site. Both stay unwritten
              either way, which is the guarantee this test exists to
              pin down"
      (let [dir (fixture-dir! (str ":::{abstract}\nShort abstract.\n:::\n\n"
                                   "# Intro {#sec:i}\n\ntext\n"))
            input (str (io/file dir "doc.hdoc"))
            {:keys [status err]} (main-run "--target" "latex" "--fragment" "--output" "-" input)]
        (is (= 1 status))
        (is (str/includes? err "has nowhere to go"))
        (is (str/includes? err "doc-preamble.tex"))
        (is (not (.exists (io/file dir "doc-preamble.tex"))))
        (is (not (.exists (io/file dir "doc-abstract-en.tex"))))))
    (testing "and a mode with no companion at all still succeeds"
      (let [dir (fixture-dir!)
            input (str (io/file dir "doc.hdoc"))
            {:keys [status err]} (main-run "--target" "latex" "--computed-numbers" "--output" "-" input)]
        (is (= 0 status) err))))
  (testing "AC #5: a literal file named - is still reachable"
    (testing "--output=- names a literal file called - in the working
              directory, not stdout: the = form is already exempt from the
              flag-shaped value check --output - relies on, so a bare -
              given that way is an ordinary value like any other. Written
              and cleaned up within the test, since - with no directory
              component is necessarily relative to wherever the process
              itself runs, same as it would be for any other tool"
      (let [dir (fixture-dir!)
            input (str (io/file dir "doc.hdoc"))
            dash-file (io/file "-")]
        (try
          (io/delete-file dash-file true)
          (let [{:keys [status out err]} (main-run "--output=-" input)]
            (is (= 0 status) err)
            (is (= "" out) "the document went to the file, not to stdout")
            (is (.isFile dash-file))
            (is (str/starts-with? (slurp dash-file) "<!DOCTYPE html>")))
          (finally
            (io/delete-file dash-file true)))))
    (testing "any other spelling ending in - (as ./- does, relative to
              wherever the process runs) is an ordinary value too, for the
              same reason -- it is not literally the single character -"
      (let [dir (fixture-dir!)
            input (str (io/file dir "doc.hdoc"))
            dash-file (io/file dir "-")
            {:keys [status out err]} (main-run "-o" (str dash-file) input)]
        (is (= 0 status) err)
        (is (= "" out))
        (is (.isFile dash-file))
        (is (str/starts-with? (slurp dash-file) "<!DOCTYPE html>")))))
  (testing "AC #6: --help documents the convention"
    (let [{:keys [out]} (main-run "--help")]
      (is (str/includes? out "stdout")))))

(def ^:private fixture-dir-entries
  "The complete, fixed set of file names `fixture-dir!` writes into every
  directory it creates -- `doc.hdoc` alongside the `refs.json` and
  `pic.png` its own front matter and figure name -- regardless of which
  `source` was given. What `in-place-test` compares a directory listing
  against to prove no stray temp file was left behind, rather than the
  single-file set an --in-place build's own directory would otherwise
  naively be expected to hold."
  #{"doc.hdoc" "refs.json" "pic.png"})

(deftest ^:serial in-place-test
  (testing "AC #1 (TASK-101): --in-place writes the converted document
            over the input file itself, nothing on stdout, and the file
            now holds hdoc's own canonical re-spelling -- proven the
            same AST-fixed-point way hdoc-target-test proves it for an
            ordinary --target hdoc build"
    (let [dir (fixture-dir! hdoc-target-fixture)
          input (str (io/file dir "doc.hdoc"))
          {:keys [status out err]} (main-run "--in-place" input)]
      (is (= 0 status) err)
      (is (= "" out) "the document went to the input file, not stdout")
      (is (str/includes? err (str "haselnuss: wrote " input)))
      (is (= (parser/parse hdoc-target-fixture) (parser/parse (slurp input))))
      (testing "and the directory holds nothing else new -- no leftover
                temp file from the write"
        (is (= fixture-dir-entries (set (.list (io/file dir))))))))
  (testing "AC #2: with no --target at all, --in-place implies hdoc"
    (let [dir (fixture-dir! hdoc-target-fixture)
          input (str (io/file dir "doc.hdoc"))
          {:keys [status]} (main-run "--in-place" input)]
      (is (= 0 status))
      (is (= (parser/parse hdoc-target-fixture) (parser/parse (slurp input))))))
  (testing "AC #2: an explicit --target hdoc is accepted alongside
            --in-place -- it is the implied default made explicit, not a
            conflict"
    (let [dir (fixture-dir! hdoc-target-fixture)
          input (str (io/file dir "doc.hdoc"))
          {:keys [status]} (main-run "--in-place" "--target" "hdoc" input)]
      (is (= 0 status))))
  (testing "AC #2: an explicit --target naming anything else is refused,
            naming the conflict, and the input is left untouched"
    (doseq [target ["html" "latex" "json"]]
      (let [dir (fixture-dir! hdoc-target-fixture)
            input (str (io/file dir "doc.hdoc"))
            before (slurp input)
            {:keys [status err]} (main-run "--in-place" "--target" target input)]
        (is (= 2 status) target)
        (is (str/includes? err "--in-place requires --target hdoc") target)
        (is (str/includes? err target) target)
        (is (= before (slurp input)) "the input must be left byte-identical"))))
  (testing "AC #3: --in-place together with --output is refused, whether
            --output names a real path or the stdout sentinel, and the
            input is left untouched"
    (doseq [output-args [["-o" "elsewhere.hdoc"] ["--output" "-"]]]
      (let [dir (fixture-dir! hdoc-target-fixture)
            input (str (io/file dir "doc.hdoc"))
            before (slurp input)
            {:keys [status err]}
            (apply main-run (concat ["--in-place"] output-args [input]))]
        (is (= 2 status) output-args)
        (is (str/includes? err "--in-place cannot be combined with --output") output-args)
        (is (= before (slurp input)) "the input must be left byte-identical"))))
  (testing "AC #4: a build made to fail partway -- here, a document whose
            own parse fails, since --in-place forces the hdoc target and
            that target has no resolve/lower pass left to fail in --
            leaves the input byte-identical, re-read after the refused
            build rather than trusted from the return value, the same
            discipline destructive-and-invalid-input-test already uses"
    (let [;; A directive fence nested inside a blockquote has no
          ;; container-oblivious raw-line reading that reaches it (see
          ;; haselnuss.emit.hdoc's own check-representable-nested-block!
          ;; docstring), so flexmark reads the `{theorem #thm:x}`
          ;; attribute group as ordinary inline content instead -- a
          ;; genuine, deterministic parser/parse failure, not a
          ;; hand-built AST shape.
          bad "> :::{theorem #thm:x}\n> body\n> :::\n"
          dir (fixture-dir! bad)
          input (str (io/file dir "doc.hdoc"))
          before (slurp input)
          {:keys [status err]} (main-run "--in-place" input)]
      (is (= 1 status) err)
      (is (= before (slurp input)) "the input must be left byte-identical")
      (is (= fixture-dir-entries (set (.list (io/file dir))))
          "no leftover temp file from the attempted write either")))
  (testing "AC #5: the write goes through a temp file in the input's own
            directory and an atomic move, never a direct overwrite of the
            input path itself -- proven by intercepting every `spit` call
            build makes and checking what it was actually called with,
            rather than trusting the source"
    (let [;; A setext heading round-trips as an ATX one (canonical hdoc
          ;; spelling, per haselnuss.emit.hdoc's own docstring), so the
          ;; written file is provably NOT the same bytes as the input --
          ;; unlike hdoc-target-fixture, which is already spelled
          ;; canonically and would round-trip byte-identical, proving
          ;; nothing about whether a write actually happened.
          source "Title\n=====\n\ntext\n"
          dir (fixture-dir! source)
          input (str (io/file dir "doc.hdoc"))
          before (slurp input)
          original-spit spit
          spit-targets (atom [])]
      (with-redefs [spit (fn [f content]
                           (swap! spit-targets conj (str (io/file f)))
                           (original-spit f content))]
        (cli/build {:input input :target "hdoc" :in-place true}))
      (is (seq @spit-targets) "at least one intermediate write happened")
      (is (not (contains? (set @spit-targets) (str (io/file input))))
          "the input path itself must never be spit to directly")
      (is (every? #(= (str (io/file dir)) (str (.getParentFile (io/file %)))) @spit-targets)
          "every intermediate write sits in the SAME directory as the input, not /tmp or
           anywhere else a rename could cross a filesystem boundary")
      (is (not= before (slurp input)) "the build did take effect on the real input")
      (is (str/starts-with? (slurp input) "# Title") "canonicalized to an ATX heading")
      (is (= fixture-dir-entries (set (.list (io/file dir))))
          "and no intermediate file was left behind once the move completed")))
  (testing "AC #6: without --in-place, --target hdoc keeps refusing to
            overwrite its own input -- unaffected by any of the above
            (hdoc-target-input-overwrite-test covers this in full; this
            is a light check that --in-place's own changes did not touch
            the ordinary path)"
    (let [dir (fixture-dir! hdoc-target-fixture)
          input (str (io/file dir "doc.hdoc"))
          before (slurp input)
          {:keys [status err]} (main-run "--target" "hdoc" input)]
      (is (= 2 status))
      (is (str/includes? err "refusing to overwrite the input file"))
      (is (= before (slurp input)))))
  (testing "AC #7: --help documents the flag, including that it is the
            only way to lift the input-overwrite refusal"
    (let [{:keys [out]} (main-run "--help")]
      (is (str/includes? out "--in-place"))
      (is (str/includes? out "only way to lift"))))
  (testing "regression (review): --in-place preserves the input file's own
            POSIX permission bits across the atomic move, rather than the
            JDK's own restrictive `createTempFile` default (owner-only,
            600) landing on the input via `ATOMIC_MOVE` -- reproduced live
            in review as a 644 input silently becoming 600 after a
            successful build, since `Files/createTempFile` with no
            `FileAttribute`s deliberately creates the most restrictive
            permissions it can, and `ATOMIC_MOVE` carries the TEMP file's
            own mode onto the target, not the target's original mode"
    (let [dir (fixture-dir! hdoc-target-fixture)
          input (str (io/file dir "doc.hdoc"))
          path (.toPath (io/file input))
          link-options (make-array java.nio.file.LinkOption 0)
          ;; Deliberately non-default (neither `createTempFile`'s own
          ;; 600 nor a typical umask-derived 644), so the assertion below
          ;; cannot pass by coincidence.
          before-perms (PosixFilePermissions/fromString "rw-r-----")]
      (Files/setPosixFilePermissions path before-perms)
      (let [{:keys [status err]} (main-run "--in-place" input)]
        (is (= 0 status) err)
        (is (= before-perms (Files/getPosixFilePermissions path link-options))
            "the input's permission bits must survive the atomic move
             unchanged"))))
  (testing "regression (review, AC #4): a failure INSIDE write-in-place!
            itself -- e.g. a full disk failing the temp-file write, AFTER
            `run` has already produced the replacement output in full --
            also leaves the input byte-identical with no leftover temp
            file, exercising write-in-place!'s own catch branch directly.
            The AC #4 test above only exercises a failure BEFORE
            output-path/write-in-place! are ever reached (a parser/parse
            error), so it cannot catch a regression here -- e.g. a
            temp file left behind, or the exception being swallowed
            instead of rethrown so build would wrongly report success"
    (let [dir (fixture-dir! hdoc-target-fixture)
          input (str (io/file dir "doc.hdoc"))
          before (slurp input)]
      (with-redefs [spit (fn [& _]
                           (throw (java.io.IOException. "no space left on device")))]
        (let [{:keys [status err]} (main-run "--in-place" input)]
          (is (= 1 status) err)))
      (is (= before (slurp input)) "the input must be left byte-identical")
      (is (= fixture-dir-entries (set (.list (io/file dir))))
          "no leftover temp file from the aborted write"))))

(deftest prose-at-tokens-test
  (testing "TASK-47 AC #1/#2 end to end, which is the only place the
            \"and no build warning\" half is observable: the CLI hands the
            parser the real kind lexicon and the real bibliography keys,
            so a chat handle and a social mention stay prose"
    (let [dir (fixture-dir! (str "---\ntitle: T\nbibliography: refs.json\n---\n\n"
                                 "# Intro {#sec:intro}\n\n"
                                 "Ping @user:homeserver and cc @someone about @sec:intro.\n\n"
                                 "As @knuth1984 has it.\n"))
          input (str (io/file dir "doc.hdoc"))
          {:keys [status err]} (main-run input)
          html (slurp (io/file dir "doc.html"))]
      (is (= 0 status) err)
      (is (no-warnings? err) "no dangling-reference or dangling-citation warning")
      (is (str/includes? html "Ping @user:homeserver and cc @someone about")
          "the prose tokens survive verbatim")
      (is (not (str/includes? html "??")) "and produce no placeholder")
      (testing "while the genuine reference and citation beside them still
                resolve, so the filter is not simply switching @ off"
        (is (str/includes? html "<a href=\"#sec:intro\">Section 1</a>"))
        (is (str/includes? html "Knuth")))))
  (testing "the key set comes from the bibliography actually in force, so
            a --bibliography override supplies it -- a key only the
            override defines still parses as a citation, and one only the
            document's own file defines becomes prose"
    (let [dir (fixture-dir! (str "---\ntitle: T\nbibliography: refs.json\n---\n\n"
                                 "# Intro {#sec:intro}\n\n"
                                 "As @overridden2001 has it, unlike @knuth1984.\n"))
          other (io/file dir "other.json")]
      (spit other (str "[{\"id\":\"overridden2001\",\"title\":\"Elsewhere\","
                       "\"issued\":{\"date-parts\":[[2001]]},"
                       "\"author\":[{\"family\":\"Other\",\"given\":\"A\"}]}]"))
      (let [{:keys [status err]} (main-run "--bibliography" (str other) (str (io/file dir "doc.hdoc")))
            html (slurp (io/file dir "doc.html"))]
        (is (= 0 status) err)
        (is (str/includes? html "Other") "the override's own key resolved")
        (is (str/includes? html "unlike @knuth1984.")
            "and the key only the overridden file defines is prose, not a dangling citation")
        (is (not (str/includes? html "??"))))))
  (testing "the trade-off's load-bearing half: filtering on the KIND means
            the common typo -- a wrong label under a real kind -- still
            warns exactly as it did. Only an unregistered kind goes quiet"
    (let [dir (fixture-dir! "# Intro {#sec:intro}\n\nSee @sec:nope and @nokind:x.\n")
          {:keys [status err]} (main-run (str (io/file dir "doc.hdoc")))
          html (slurp (io/file dir "doc.html"))]
      (is (= 0 status))
      (is (= 1 (count (re-seq #"haselnuss: warning: " err))) err)
      (is (str/includes? err "dangling cross-reference"))
      (is (str/includes? html "@nokind:x"))))
  (testing "native-mode LaTeX is exempt, because there this codebase is
            not the authority: it emits \\Cref/\\citet for LaTeX and
            BibTeX to resolve against a .bib and a \\label namespace it
            cannot see, so filtering would turn a working reference into
            literal text (found by review)"
    (let [dir (fixture-dir! (str "---\ntitle: T\nbibliography: refs.json\n---\n\n"
                                 "# Intro {#sec:intro}\n\n"
                                 "As @einstein1905 showed, see also [@einstein1905], "
                                 "@knuth1984 and @app:a.\n"))
          input (str (io/file dir "doc.hdoc"))]
      (let [{:keys [status err]} (main-run "--target" "latex" input)
            tex (slurp (io/file dir "doc.tex"))]
        (is (= 0 status) err)
        (is (str/includes? tex "\\citet{einstein1905}")
            "a key absent from the CSL-JSON may still be one BibTeX resolves from the .bib")
        (is (str/includes? tex "\\Cref{app:a}")
            "and a prefix the lexicon has no kind for may still be a real LaTeX label"))
      (testing "while --computed-numbers, where the resolver's own answers
                are what gets baked in and an unresolved reference was
                already a ??, is filtered like HTML"
        (let [{:keys [status err]} (main-run "--target" "latex" "--computed-numbers" input)
              tex (slurp (io/file dir "doc.tex"))]
          (is (= 0 status) err)
          (is (str/includes? tex "@einstein1905 showed"))
          (is (str/includes? tex "@app:a"))))))
  (testing "a document with no bibliography at all gets the empty key set,
            not the old heuristic: every bare @word in it is prose, since
            no bare citation could resolve anyway"
    (let [dir (fixture-dir! "# Intro {#sec:intro}\n\ncc @someone on this.\n")
          {:keys [status err]} (main-run (str (io/file dir "doc.hdoc")))]
      (is (= 0 status) err)
      (is (no-warnings? err) err)
      (is (str/includes? (slurp (io/file dir "doc.html")) "cc @someone on this."))))
  (testing "a bracketed citation is explicit syntax, so an unknown key
            there still dangles loudly -- the filter must not silence the
            form an author uses on purpose"
    (let [dir (fixture-dir! "# Intro {#sec:intro}\n\nSee [@nobody2000].\n")
          {:keys [status err]} (main-run (str (io/file dir "doc.hdoc")))]
      (is (= 0 status))
      (is (str/includes? err "dangling citation")))))

(deftest multi-file-include-test
  (testing "TASK-38 end to end: a document split across files converts as
            one, with an included chapter's own section and figure
            numbering and cross-referencing exactly as if they had been
            typed into the including file (AC #1/#2)"
    (let [dir (fixture-dir! (str "---\ntitle: A Book\n---\n\n"
                                 "See @fig:tree in @sec:methods.\n\n"
                                 "!include chapters/methods.hdoc\n"))
          chapters (io/file dir "chapters")]
      (.mkdirs chapters)
      (spit (io/file chapters "methods.hdoc")
            (str "# Methods {#sec:methods}\n\n"
                 "![A hazel tree.](../pic.png){#fig:tree}\n\n"
                 "!include shared.hdoc\n"))
      (spit (io/file chapters "shared.hdoc") "Prose from a nested include.\n")
      (let [{:keys [status err]} (main-run (str (io/file dir "doc.hdoc")))
            html (slurp (io/file dir "doc.html"))]
        (is (= 0 status) err)
        (is (no-warnings? err) "a document whose includes all resolve produces no diagnostics")
        (is (str/includes? html "<span class=\"section-number\">1</span> Methods</h1>")
            "the chapter was spliced in, and numbers as if typed in place")
        (is (str/includes? html "Prose from a nested include.")
            "and its own include resolved against ITS directory, not the book's")
        (is (str/includes? html "<figcaption>Figure 1.1: A hazel tree.</figcaption>")
            "the included figure numbers as if typed in place")
        (is (str/includes? html "<a href=\"#fig:tree\">Figure 1.1</a>")
            "and a reference from the including file resolves to it")
        (is (not (str/includes? html "??"))))))
  (testing "AC #4: a missing file is a diagnostic naming it and the build
            continues -- exit 0, output written, the rest of the document
            intact"
    (let [dir (fixture-dir! (str "# Intro {#sec:intro}\n\nKept.\n\n"
                                 "!include nowhere/missing.hdoc\n\nAlso kept.\n"))
          {:keys [status err]} (main-run (str (io/file dir "doc.hdoc")))
          html (slurp (io/file dir "doc.html"))]
      (is (= 0 status) err)
      (is (str/includes? err (str "haselnuss: warning: " (io/file dir "doc.hdoc")
                                  ":5: cannot include \"nowhere/missing.hdoc\""))
          "TASK-163: and names the !include line that asked for it")
      (is (str/includes? html "Kept."))
      (is (str/includes? html "Also kept."))))
  (testing "AC #3: a cycle is a diagnostic naming the chain, not a hang
            or a stack overflow"
    (let [dir (fixture-dir! "# Intro {#sec:intro}\n\n!include a.hdoc\n")]
      (spit (io/file dir "a.hdoc") "A.\n\n!include b.hdoc\n")
      (spit (io/file dir "b.hdoc") "B.\n\n!include a.hdoc\n")
      (let [{:keys [status err]} (main-run (str (io/file dir "doc.hdoc")))
            html (slurp (io/file dir "doc.html"))]
        (is (= 0 status) err)
        (is (str/includes? err (str "haselnuss: warning: " (io/file dir "b.hdoc") ":3: include cycle"))
            "TASK-163: at the !include that closes the loop, in the file it is written in")
        (is (str/includes? html "A.") "everything outside the loop still converts")
        (is (str/includes? html "B.")))))
  (testing "a loop back to the document being built is a cycle too: the
            CLI puts it on the stack via :source-path, without which a
            self-including file spliced its own body in twice before the
            guard fired (found by review)"
    (let [dir (fixture-dir! "# Intro {#sec:intro}\n\nOnce.\n\n!include doc.hdoc\n")
          {:keys [status err]} (main-run (str (io/file dir "doc.hdoc")))
          html (slurp (io/file dir "doc.html"))]
      (is (= 0 status) err)
      (is (str/includes? err "include cycle"))
      (is (= 1 (count (re-seq #"Once\." html))) "the document's own body appears exactly once")
      (is (not (str/includes? err "duplicate id")))))
  (testing "TASK-105 AC #1: an included file that EXISTS but fails to
            parse fails the whole build naming the file, rather than being
            warned about and dropped -- the identical content converted on
            its own is already a hard failure, and this is now the same
            failure, not a warning that exits 0 with the chapter missing"
    (let [dir (fixture-dir! "# Intro {#sec:intro}\n\nKept.\n\n!include broken.hdoc\n")]
      (spit (io/file dir "broken.hdoc") ":::{!!!}\nx\n:::\n")
      (let [{:keys [status err]} (main-run (str (io/file dir "doc.hdoc")))]
        (is (= 1 status))
        (is (str/includes? err "haselnuss: error:"))
        (is (str/includes? err "cannot include \"broken.hdoc\""))
        (is (not (.exists (io/file dir "doc.html")))
            "nothing is written on an unrecoverable error, same as any other"))))
  (testing "review finding: a warn-and-continue include (a missing file)
            earlier in the document is still printed as a warning when a
            LATER include in the same document turns out to be unparseable
            and fails the build -- and prints BEFORE the error, the same
            warnings-before-error ordering AC #4/#5 already give any other
            failure (see run-test's own coverage of that ordering)"
    (let [dir (fixture-dir! (str "# Intro {#sec:intro}\n\nKept.\n\n"
                                 "!include gone.hdoc\n\n"
                                 "!include broken.hdoc\n"))]
      (spit (io/file dir "broken.hdoc") ":::{!!!}\nx\n:::\n")
      (let [{:keys [status err]} (main-run (str (io/file dir "doc.hdoc")))]
        (is (= 1 status))
        (let [doc (str (io/file dir "doc.hdoc"))
              warning (str "haselnuss: warning: " doc ":5: cannot include \"gone.hdoc\"")
              error (str "haselnuss: error: " doc ":7: cannot include \"broken.hdoc\"")]
          (is (str/includes? err warning))
          (is (str/includes? err error))
          (is (< (.indexOf err warning) (.indexOf err error))))
        (is (not (.exists (io/file dir "doc.html")))))))
  (testing "TASK-105 AC #3: the same fatal failure happens for a malformed
            file reached through a CHAIN of includes, not only a direct one"
    (let [dir (fixture-dir! "# Intro {#sec:intro}\n\nKept.\n\n!include a.hdoc\n")]
      (spit (io/file dir "a.hdoc") "A.\n\n!include b.hdoc\n")
      (spit (io/file dir "b.hdoc") ":::{!!!}\nx\n:::\n")
      (let [{:keys [status err]} (main-run (str (io/file dir "doc.hdoc")))]
        (is (= 1 status))
        (is (str/includes? err "cannot include \"b.hdoc\""))
        (is (not (.exists (io/file dir "doc.html")))))))
  (testing "TASK-105 AC #2: a directory named as a src stays a warning,
            not a build abort -- unlike a parse failure, there is no
            content there to lose"
    (let [dir (fixture-dir! "# Intro {#sec:intro}\n\nKept.\n\n!include subdir\n")]
      (.mkdirs (io/file dir "subdir"))
      (let [{:keys [status err]} (main-run (str (io/file dir "doc.hdoc")))]
        (is (= 0 status) err)
        (is (str/includes? err "cannot include \"subdir\"")))))
  (testing "AC #5: neither emitter raises ::unresolved-include for a
            document whose includes all resolved -- asserted on both
            targets, since the branch lives in each emitter separately"
    (let [dir (fixture-dir! "# Intro {#sec:intro}\n\n!include ch.hdoc\n")]
      (spit (io/file dir "ch.hdoc") "Included prose.\n")
      (doseq [[target extension] [["html" "doc.html"] ["latex" "doc.tex"]]]
        (let [{:keys [status err]} (main-run "--target" target (str (io/file dir "doc.hdoc")))]
          (is (= 0 status) (str target ": " err))
          (is (str/includes? (slurp (io/file dir extension)) "Included prose.") target))))))

(deftest annotation-overlay-test
  (testing "TASK-119 AC #1/#2/#3 end to end through the real CLI: a
            one-line !highlight, a :::{sticky-note} with a body, and a
            :::{highlight} with a body all reach both rendering targets"
    (let [dir (fixture-dir!)
          overlay (str (io/file dir "review.hdoc"))]
      (spit overlay (str "!highlight{target=sec:intro text=\"Introduction\"}\n\n"
                         ":::{sticky-note target=sec:intro text=\"Introduction\" author=\"DM\"}\n"
                         "Was this the rerun trial?\n"
                         ":::\n"))
      (doseq [target ["html" "latex"]]
        (let [{:keys [output diagnostics]} (build! dir {:target target :overlay [overlay]})]
          (is (empty? diagnostics) (str target ": " (pr-str diagnostics)))
          (if (= "html" target)
            (do (is (str/includes? output "<mark") target)
                (is (str/includes? output "sticky-note") target)
                (is (str/includes? output "Was this the rerun trial?") target))
            (do (is (str/includes? output "\\pdfmarkupcomment") target)
                (is (str/includes? output "\\pdfcomment") target)))))))
  (testing "AC #7: every haselnuss.anchor diagnostic reaches the build as
            a NON-FATAL warning naming the quote and the target, and the
            build still exits 0 with the base document intact. This is
            the feature's real failure mode -- the base document was
            edited, the quote no longer matches -- and it is the whole
            argument for quoting over counting that it says so"
    (let [dir (fixture-dir!)
          overlay (str (io/file dir "stale.hdoc"))]
      (spit overlay "!highlight{target=sec:intro text=\"words that are not there\"}\n")
      (let [{:keys [status err]} (main-run "--overlay" overlay (str (io/file dir "doc.hdoc")))]
        (is (= 0 status) err)
        (is (str/includes? err "words that are not there") "naming the quote")
        (is (str/includes? err "sec:intro") "and the target")
        (is (str/includes? err "update the quote") "and what to do about it")
        (is (.exists (io/file dir "doc.html")))
        (is (str/includes? (slurp (io/file dir "doc.html")) "Introduction")
            "with the base document intact"))))
  (testing "AC #5: a colour or an edge outside the recognised set is
            diagnosed by name, the annotation dropped, and the build
            still exits 0"
    (let [dir (fixture-dir!)
          overlay (str (io/file dir "bad.hdoc"))]
      (spit overlay (str "!highlight{target=sec:intro text=\"Introduction\" color=purple}\n\n"
                         ":::{sticky-note target=sec:intro text=\"Introduction\" at=middle}\n"
                         "body\n:::\n"))
      (let [{:keys [status err]} (main-run "--overlay" overlay (str (io/file dir "doc.hdoc")))]
        (is (= 0 status) err)
        (is (str/includes? err "color=purple"))
        (is (str/includes? err "at=middle"))
        (is (not (str/includes? (slurp (io/file dir "doc.html")) "<mark"))))))
  (testing "AC #11: writing highlight or sticky-note directly in the BASE
            document fails the build as an unregistered directive, like
            prepend does -- these are overlay conventions, not document
            syntax"
    (doseq [directive-name ["highlight" "sticky-note" "prepend"]]
      (testing directive-name
        (let [dir (fixture-dir! (str "# Intro {#sec:intro}\n\n"
                                     ":::{" directive-name " target=sec:intro text=\"x\"}\n"
                                     "body\n:::\n"))
              {:keys [status err]} (main-run (str (io/file dir "doc.hdoc")))]
          (is (= 1 status))
          (is (str/includes? err directive-name))))))
  (testing "AC #12: an annotation overlay sitting on disk changes nothing
            about a build that never names it"
    (let [dir (fixture-dir!)
          without (:output (build! dir {}))]
      (spit (io/file dir "unused-review.hdoc")
            "!highlight{target=sec:intro text=\"Introduction\"}\n")
      (is (= without (:output (build! dir {})))))))

(deftest overlay-test
  (testing "AC #1: an unused --overlay file existing on disk changes
            nothing about a build that never names it -- byte-for-byte
            identical output, the headline invariant doc-5 requires"
    (let [dir (fixture-dir!)
          without (:output (build! dir {}))]
      (spit (io/file dir "unused.hdoc") ":::{prepend target=sec:nope}\nx\n:::\n")
      (is (= without (:output (build! dir {}))))))
  (testing "AC #2/#3: a real two-file overlay build works on both HTML
            and LaTeX targets -- prepend/append content from separate
            files accumulates in --overlay order, then in-file order,
            and numbers/cross-references exactly as if authored inline"
    (let [dir (fixture-dir!)
          overlay-a (str (io/file dir "overlay-a.hdoc"))
          overlay-b (str (io/file dir "overlay-b.hdoc"))]
      (spit overlay-a (str ":::{prepend target=sec:intro}\nFrom A, prepended.\n:::\n\n"
                           ":::{append target=sec:intro}\nFrom A, appended.\n:::\n"))
      (spit overlay-b ":::{append target=sec:intro}\nFrom B, appended.\n:::\n")
      (doseq [target ["html" "latex"]]
        (let [{:keys [output]} (build! dir {:target target :overlay [overlay-a overlay-b]})]
          (is (str/includes? output "From A, prepended.") target)
          (is (str/includes? output "From A, appended.") target)
          (is (str/includes? output "From B, appended.") target)
          (is (< (.indexOf output "From A, appended.") (.indexOf output "From B, appended."))
              (str target ": file order, then in-file order"))))))
  (testing "a missing --overlay path is a hard build failure naming it,
            not a diagnostic -- it is a path typed on the command line,
            not a reference embedded in document prose"
    (let [dir (fixture-dir!)
          {:keys [status err]} (main-run "--overlay" (str (io/file dir "nowhere.hdoc"))
                                         (str (io/file dir "doc.hdoc")))]
      (is (= 1 status))
      (is (str/includes? err "overlay file not found"))
      (is (str/includes? err "nowhere.hdoc"))
      (is (not (.exists (io/file dir "doc.html"))))))
  (testing "AC #5: malformed overlay content -- a stray paragraph, a
            missing target=, and a dangling target -- is each a distinct
            warning, dropped, and the build still exits 0 with the rest
            of both the overlay and the base document intact"
    (let [dir (fixture-dir!)
          overlay (str (io/file dir "overlay.hdoc"))]
      (spit overlay (str "A stray paragraph.\n\n"
                         ":::{append}\nno target at all\n:::\n\n"
                         ":::{prepend target=sec:nope}\ndangling\n:::\n"))
      (let [{:keys [status err]} (main-run "--overlay" overlay (str (io/file dir "doc.hdoc")))]
        (is (= 0 status) err)
        (is (str/includes? err "unexpected top-level para"))
        (is (str/includes? err "no target="))
        (is (str/includes? err "does not resolve to a node in the base document"))
        (is (.exists (io/file dir "doc.html")))
        (is (str/includes? (slurp (io/file dir "doc.html")) "Introduction")))))
  (testing "TASK-112: a misspelled directive name in an overlay file --
            e.g. prepnd for prepend -- is named in the warning, along
            with the target= it carries, so an author has a string to
            search their overlay files for"
    (let [dir (fixture-dir!)
          overlay (str (io/file dir "overlay.hdoc"))]
      (spit overlay ":::{prepnd target=ch:intro}\nbody\n:::\n")
      (let [{:keys [status err]} (main-run "--overlay" overlay (str (io/file dir "doc.hdoc")))]
        (is (= 0 status) err)
        (is (str/includes? err "\"prepnd\""))
        (is (str/includes? err "target=ch:intro"))
        (is (.exists (io/file dir "doc.html"))))))
  (testing "an overlay can target a Section that exists only AFTER
            base-document include-expansion -- e.g. a chapter pulled in
            from another file -- since overlay application runs after it"
    (let [dir (fixture-dir! "---\ntitle: A Book\n---\n\n!include chapters/methods.hdoc\n")
          chapters (io/file dir "chapters")
          overlay (str (io/file dir "overlay.hdoc"))]
      (.mkdirs chapters)
      (spit (io/file chapters "methods.hdoc") "# Methods {#sec:methods}\n\nBase prose.\n")
      (spit overlay ":::{append target=sec:methods}\nOverlay prose.\n:::\n")
      (let [{:keys [status err]} (main-run "--overlay" overlay (str (io/file dir "doc.hdoc")))
            html (slurp (io/file dir "doc.html"))]
        (is (= 0 status) err)
        (is (str/includes? html "Base prose."))
        (is (str/includes? html "Overlay prose."))
        (is (< (.indexOf html "Base prose.") (.indexOf html "Overlay prose."))))))
  (testing "AC #4: an !include written inside an overlay file resolves
            against THAT FILE's own directory, with the same cycle
            detection the base document gets -- an overlay that includes
            itself is a cycle, not a hang"
    (let [dir (fixture-dir!)
          overlay-dir (io/file dir "overlays")
          overlay (str (io/file overlay-dir "overlay.hdoc"))]
      (.mkdirs overlay-dir)
      (spit (io/file overlay-dir "shared.hdoc")
            ":::{append target=sec:intro}\nShared overlay prose.\n:::\n")
      (spit overlay "!include shared.hdoc\n")
      (let [{:keys [status err]} (main-run "--overlay" overlay (str (io/file dir "doc.hdoc")))
            html (slurp (io/file dir "doc.html"))]
        (is (= 0 status) err)
        (is (str/includes? html "Shared overlay prose."))))
    (let [dir (fixture-dir!)
          overlay (str (io/file dir "self.hdoc"))]
      (spit overlay "!include self.hdoc\n")
      (let [{:keys [status err]} (main-run "--overlay" overlay (str (io/file dir "doc.hdoc")))]
        (is (= 0 status) err)
        (is (str/includes? err "include cycle")))))
  (testing "TASK-83.2 AC #1/#2: a real --overlay describe merges a prop
            onto the base document's own fig:tree -- a non-Section node
            -- on both HTML and LaTeX targets, and the merged value is
            never rendered as VISIBLE BODY content by either emitter"
    (let [dir (fixture-dir!)
          overlay (str (io/file dir "describe.hdoc"))]
      (spit overlay ":::{describe target=fig:tree alt=\"A hazel tree in early spring.\"}\n:::\n")
      (testing "LaTeX has no generic attr.props target at all (emit/
                latex's own documented limitation) -- the merged value
                is absent from the .tex output entirely"
        (let [{:keys [output]} (build! dir {:target "latex" :overlay [overlay]})]
          (is (not (str/includes? output "A hazel tree in early spring.")))))
      (testing "HTML renders it as a tag attribute on the <figure>
                element -- present in the source, but never as a
                visible text node"
        (let [{:keys [output]} (build! dir {:target "html" :overlay [overlay]})]
          (is (str/includes? output "alt=\"A hazel tree in early spring.\""))
          (is (not (str/includes? output ">A hazel tree in early spring.<")))))))
  (testing "TASK-83.2 AC #3: a describe prop colliding with one already
            set on the target node in the BASE document is a non-fatal
            warning, the build still exits 0, and the base document's
            own value is kept rather than silently overwritten. Uses
            eq:mass (a MathBlock), not fig:tree: a promoted image Figure
            deliberately keeps its OWN authored props on the nested
            Image rather than the Figure itself (`convert-block`'s own
            documented rationale), so it cannot exercise a collision on
            the FIGURE node's own attr.props the way a MathBlock can"
    (let [dir (fixture-dir! (str "# Intro {#sec:intro}\n\n"
                                 "$$\nE = mc^2\n$$ {#eq:mass credit=\"original\"}\n"))
          overlay (str (io/file dir "describe.hdoc"))]
      (spit overlay ":::{describe target=eq:mass credit=\"overlay\"}\n:::\n")
      (let [{:keys [status err]} (main-run "--overlay" overlay (str (io/file dir "doc.hdoc")))
            html (slurp (io/file dir "doc.html"))]
        (is (= 0 status) err)
        (is (str/includes? err "credit"))
        (is (str/includes? err "was not applied"))
        (is (str/includes? html "credit=\"original\""))
        (is (not (str/includes? html "credit=\"overlay\""))))))
  (testing "TASK-115: the same overlay written in sec5.5's one-line
            spelling behaves identically. `describe` carries everything
            in its header and nothing in a body, so this is the form an
            alt-text overlay file is actually written in -- one line per
            figure rather than two -- and it must reach the target node
            with no diagnostics, exactly as the fence above does"
    (let [dir (fixture-dir!)
          overlay (str (io/file dir "describe.hdoc"))]
      (spit overlay "!describe{target=fig:tree alt=\"A hazel tree in early spring.\"}\n")
      (let [{:keys [status err]} (main-run "--overlay" overlay (str (io/file dir "doc.hdoc")))
            html (slurp (io/file dir "doc.html"))]
        (is (= 0 status) err)
        (is (str/includes? html "alt=\"A hazel tree in early spring.\""))
        (is (not (str/includes? html ">A hazel tree in early spring.<")))))))

(deftest describe-overlay-overwritten-by-later-describe-test
  (testing "TASK-110 AC #1/#2: two SEPARATE --overlay files each
            describing the same prop on the same target -- the
            realistic accessibility-pass/translation-pass shape from the
            task -- produce a build warning naming the prop and the
            target, the build still exits 0, and the LATER --overlay's
            value (accumulation order, unchanged by this fix) is what
            ends up in the output"
    (let [dir (fixture-dir!)
          overlay-1 (str (io/file dir "overlay-1.hdoc"))
          overlay-2 (str (io/file dir "overlay-2.hdoc"))]
      (spit overlay-1 ":::{describe target=fig:tree data-note=\"one\"}\n:::\n")
      (spit overlay-2 ":::{describe target=fig:tree data-note=\"two\"}\n:::\n")
      (let [{:keys [status err]} (main-run "--overlay" overlay-1 "--overlay" overlay-2
                                           (str (io/file dir "doc.hdoc")))
            html (slurp (io/file dir "doc.html"))]
        (is (= 0 status) err)
        (is (str/includes? err "data-note"))
        (is (str/includes? err "fig:tree"))
        (is (str/includes? html "data-note=\"two\""))
        (is (not (str/includes? html "data-note=\"one\""))))))
  (testing "TASK-110 AC #2: the same collision, this time as two
            describe directives in ONE overlay file, warns the same way"
    (let [dir (fixture-dir!)
          overlay (str (io/file dir "overlay.hdoc"))]
      (spit overlay (str ":::{describe target=fig:tree data-note=\"one\"}\n:::\n\n"
                         ":::{describe target=fig:tree data-note=\"two\"}\n:::\n"))
      (let [{:keys [status err]} (main-run "--overlay" overlay (str (io/file dir "doc.hdoc")))
            html (slurp (io/file dir "doc.html"))]
        (is (= 0 status) err)
        (is (str/includes? err "data-note"))
        (is (str/includes? err "fig:tree"))
        (is (str/includes? html "data-note=\"two\"")))))
  (testing "TASK-110 AC #4: two describe directives on the same target
            but different props stay silent"
    (let [dir (fixture-dir!)
          overlay-1 (str (io/file dir "overlay-1.hdoc"))
          overlay-2 (str (io/file dir "overlay-2.hdoc"))]
      (spit overlay-1 ":::{describe target=fig:tree data-note=\"a note\"}\n:::\n")
      (spit overlay-2 ":::{describe target=fig:tree credit=\"someone\"}\n:::\n")
      (let [{:keys [status err]} (main-run "--overlay" overlay-1 "--overlay" overlay-2
                                           (str (io/file dir "doc.hdoc")))
            html (slurp (io/file dir "doc.html"))]
        (is (= 0 status) err)
        (is (not (str/includes? err "warning:")) err)
        (is (str/includes? html "data-note=\"a note\""))
        (is (str/includes? html "credit=\"someone\""))))))

(defn- write-file!
  [dir filename content]
  (let [f (io/file dir filename)]
    (spit f content)
    (str f)))

(def ^:private callout-extension-source
  "One author-defined directive -- `callout` -- for `sidecar-extensions-
  test` (TASK-90 AC #13): a labeled note box on `:html` (real hiccup,
  rendering its own `:blocks` through `ctx`'s own `:render-blocks`, the
  same TASK-89 AC #2 composability `haselnuss.extensions.collapsable`
  exercises) and a plain, note-prefixed paragraph on `:latex` -- a
  FRAGMENT (a plain, harmless string literal alongside the same
  `:render-blocks` `Rendered` result), not `haselnuss.ext/raw`, so this
  worked example proves an UNTRUSTED sidecar can already produce real,
  structured, composed output on both targets -- mirroring `collapsable`'s
  own \"no :trusted? -- proving an untrusted extension can still produce
  rich structured output\" precedent."
  (str "{:name \"callout\"\n"
       " :emit {:html (fn [directive ctx]\n"
       "               [:div {:class \"callout\"} ((:render-blocks ctx) (:blocks directive))])\n"
       "        :latex (fn [directive ctx]\n"
       "               [\"Note: \" ((:render-blocks ctx) (:blocks directive))])}}"))

(deftest sidecar-extensions-test
  (testing "AC #1/#13: a sidecar file defines a directive's HTML and
            LaTeX rendering, loaded by --extensions with no recompile,
            and renders end to end on BOTH targets through the real CLI"
    (let [dir (fixture-dir! ":::{callout}\nA warning from the author's own extension.\n:::\n")
          ext (write-file! dir "callout.clj" callout-extension-source)]
      (let [{:keys [output]} (build! dir {:target "html" :extensions [ext]})]
        (is (well-formed? output))
        (is (str/includes? output "<div class=\"callout\">"))
        (is (str/includes? output "A warning from the author's own extension.")))
      (let [{:keys [output]} (build! dir {:target "latex" :extensions [ext]})]
        (is (str/includes? output "Note: A warning from the author's own extension."))
        (testing "and it actually compiles under a real pdflatex, not just
                  string-matches -- the same standard this file already
                  holds every other LaTeX-producing feature to"
          (let [[ok? detail] (compiles? dir output)]
            (is ok? detail))))))
  (testing "omitting --extensions entirely leaves an unrelated build
            byte-for-byte unaffected, and the same directive name is still
            reported as unknown -- --extensions is additive, not a global
            switch that changes how unregistered directives are treated"
    (let [dir (fixture-dir! ":::{callout}\nx\n:::\n")
          {:keys [status err]} (main-run (str (io/file dir "doc.hdoc")))]
      (is (= 1 status))
      (is (str/includes? err "no representation for directive \"callout\""))))
  (testing "a missing --extensions path is a hard build failure naming
            it, not a diagnostic -- mirroring --overlay's own established
            treatment of a path typed on the command line"
    (let [dir (fixture-dir!)
          {:keys [status err]} (main-run "--extensions" (str (io/file dir "nowhere.clj"))
                                         (str (io/file dir "doc.hdoc")))]
      (is (= 1 status))
      (is (str/includes? err "extension file not found"))
      (is (str/includes? err "nowhere.clj"))
      (is (not (.exists (io/file dir "doc.html"))))))
  (testing "AC #10: a malformed sidecar file is refused with an error
            naming it, through the real CLI path -- haselnuss.extensions.
            sidecar-test covers every malformed shape directly; this is
            the one confirming that error actually reaches stderr/exit 1
            here rather than only being thrown internally"
    (let [dir (fixture-dir!)
          ext (write-file! dir "bad.clj" "{:emit {:html (fn [d c] 1)}}")
          {:keys [status err]} (main-run "--extensions" ext (str (io/file dir "doc.hdoc")))]
      (is (= 1 status))
      (is (str/includes? err "malformed extension"))
      (is (str/includes? err "bad.clj"))))
  (testing "AC #3/#6: host interop and reaching this project's own
            classpath from a sidecar file are refused through the real
            CLI path too, not only when haselnuss.extensions.sidecar is
            called directly"
    (let [dir (fixture-dir!)
          ext (write-file! dir "interop.clj"
                           "{:name \"x\" :emit {:html (fn [d c] (System/exit 0))}}")
          {:keys [status err]} (main-run "--extensions" ext (str (io/file dir "doc.hdoc")))]
      (is (= 1 status))
      (is (str/includes? err "System/exit")))))

(def ^:private unsafe-extension-source
  "TASK-215: an untrusted sidecar whose HTML puts a javascript: URL and an
  on* handler on its own hiccup and hands :render-blocks a hand-built link
  with both, and whose LaTeX hands :render-inlines math that \\inputs a
  file -- the TASK-213 reviewer's reproductions, through the real CLI and
  the real sandbox (whose callbacks run on a worker thread)."
  (str "{:name \"evil\"\n"
       " :emit {:html (fn [directive ctx]\n"
       "               [:div {:class \"evil\" :onclick \"alert(0)\"}\n"
       "                [:a {:href \"javascript:alert(1)\"} \"go\"]\n"
       "                ((:render-blocks ctx)\n"
       "                 [{:t :para :attr {:id nil :classes [] :props {}}\n"
       "                   :inlines [{:t :link :target \"javascript:alert(2)\"\n"
       "                              :attr {:id nil :classes [] :props {\"onmouseover\" \"alert(3)\"}}\n"
       "                              :inlines [{:t :str :text \"click\"}]}]}])])\n"
       "        :latex (fn [directive ctx]\n"
       "                 ((:render-inlines ctx) [{:t :math-inline :tex \"\\\\input{/etc/passwd}\"}]))}}"))

(deftest untrusted-extension-output-test
  (let [dir (fixture-dir! ":::{evil}\nx\n:::\n")
        ext (write-file! dir "evil.clj" unsafe-extension-source)]
    (testing "TASK-215: an untrusted extension's javascript: URLs and on*
              handlers -- in its own hiccup and in the AST it renders
              through :render-blocks -- are dropped, each with a warning
              naming the directive and its line"
      (let [{:keys [output diagnostics]} (build! dir {:target "html" :extensions [ext]})]
        (is (well-formed? output))
        (is (str/includes? output "<div class=\"evil\"><a>go</a><p>click</p></div>"))
        (is (not (str/includes? output "javascript:")))
        (is (not (str/includes? output "alert")))
        (is (= 4 (count (filter :directive-name diagnostics))))
        (is (every? #(str/includes? (:message %) "directive \"evil\" (")
                    (filter :directive-name diagnostics)))
        (is (= #{:event-handler-attribute :unsafe-url}
               (set (map :type (filter :directive-name diagnostics))))))
      (let [{:keys [status err]} (main-run "--extensions" ext (str (io/file dir "doc.hdoc")))]
        (is (= 0 status))
        (is (str/includes? err "haselnuss: warning: directive \"evil\""))
        (is (str/includes? err "javascript:alert(2)"))))
    (testing "and its \\input in :render-inlines math fails the LaTeX build,
              naming the directive and the primitive"
      (let [{:keys [status err]} (main-run "--target" "latex" "--extensions" ext
                                           (str (io/file dir "doc.hdoc")))]
        (is (= 1 status))
        (is (str/includes? err "directive \"evil\""))
        (is (str/includes? err "\\input"))
        (is (not (.exists (io/file dir "doc.tex"))))))
    (testing "a :lower rule is held to the same rules as a renderer (TASK-215 review)"
      (let [lower-ext (write-file!
                       dir "evil-lower.clj"
                       (str "{:name \"evil\"\n"
                            " :lower (fn [directive target]\n"
                            "          [{:t :para :attr {:classes [] :props {}}\n"
                            "            :inlines [{:t :link :target \"javascript:alert(2)\"\n"
                            "                       :attr {:classes [] :props {\"onmouseover\" \"x\"}}\n"
                            "                       :inlines [{:t :str :text \"click\"}]}\n"
                            "                      {:t :math-inline :tex (if (= target :latex)\n"
                            "                                              \"\\\\input{/etc/passwd}\" \"x\")}]}])}"))
            {:keys [output diagnostics]} (build! dir {:target "html" :extensions [lower-ext]})]
        (is (str/includes? output "<p>click<span class=\"math inline\">"))
        (is (not (str/includes? output "javascript:")))
        (is (not (str/includes? output "onmouseover")))
        (is (= #{:event-handler-attribute :unsafe-url}
               (set (map :type (filter :directive-name diagnostics)))))
        (let [{:keys [status err]} (main-run "--target" "latex" "--extensions" lower-ext
                                             (str (io/file dir "doc.hdoc")))]
          (is (= 1 status))
          (is (str/includes? err "directive \"evil\""))
          (is (str/includes? err "\\input")))))
    (testing "--trusted-extensions lifts all of it"
      (let [{:keys [output]} (build! dir {:target "html" :extensions [ext]
                                          :trusted-extensions true})]
        (is (str/includes? output "href=\"javascript:alert(1)\""))
        (is (str/includes? output "onclick=\"alert(0)\"")))
      (let [{:keys [output]} (build! dir {:target "latex" :extensions [ext]
                                          :trusted-extensions true})]
        (is (str/includes? output "\\(\\input{/etc/passwd}"))))))

(deftest sidecar-rendered-first-child-test
  (testing "TASK-216: a sandboxed renderer's [:div rendered] renders the nested
            content, untrusted or trusted -- not <div html=\"...\">"
    (let [dir (fixture-dir! ":::{box}\nInside.\n:::\n")
          ext (write-file! dir "box.clj"
                           (str "{:name \"box\" :emit {:html (fn [d c] "
                                "[:div ((:render-blocks c) (:blocks d))])}}"))]
      (doseq [trusted? [false true]]
        (let [{:keys [output]} (build! dir {:target "html" :extensions [ext]
                                            :trusted-extensions trusted?})]
          (is (str/includes? output "<div><p>Inside.</p></div>") (str "trusted? " trusted?))
          (is (not (str/includes? output "html=")))))))
  (testing "and a forged one in that position is refused, naming the directive"
    (let [dir (fixture-dir! ":::{box}\nInside.\n:::\n")
          ext (write-file! dir "forge.clj"
                           (str "{:name \"box\" :emit {:html (fn [d c] "
                                "[:div (assoc ((:render-blocks c) []) "
                                ":html \"<script>alert(1)</script>\")])}}"))
          {:keys [status err]} (main-run "--extensions" ext (str (io/file dir "doc.hdoc")))]
      (is (= 1 status))
      (is (str/includes? err "directive \"box\""))
      (is (str/includes? err "Rendered fragment"))
      (is (not (.exists (io/file dir "doc.html")))))))

(deftest trusted-extensions-test
  (testing "AC #11: without --trusted-extensions, a sidecar's own
            [:raw ...] attempt is refused through the real emitter's
            render-directive -- the same ::untrusted-raw-output TASK-89
            already gives a host-defined untrusted renderer, not a
            second, different mechanism"
    (let [dir (fixture-dir! ":::{widget}\n:::\n")
          ext (write-file! dir "widget.clj"
                           (str "{:name \"widget\" :emit {:latex "
                                "(fn [d c] (haselnuss.ext/raw \"\\\\textbf{RAW}\"))}}"))
          {:keys [status err]} (main-run "--target" "latex" "--extensions" ext
                                         (str (io/file dir "doc.hdoc")))]
      (is (= 1 status))
      (is (str/includes? err "not :trusted"))
      (is (not (.exists (io/file dir "doc.tex"))))))
  (testing "AC #11: --trusted-extensions allows the identical file's
            [:raw ...] output through verbatim, and it defaults to off --
            the SAME file, without the flag, is refused above"
    (let [dir (fixture-dir! ":::{widget}\n:::\n")
          ext (write-file! dir "widget.clj"
                           (str "{:name \"widget\" :emit {:latex "
                                "(fn [d c] (haselnuss.ext/raw \"\\\\textbf{RAW}\"))}}"))
          {:keys [output]} (build! dir {:target "latex" :extensions [ext]
                                        :trusted-extensions true})]
      (is (str/includes? output "\\textbf{RAW}"))))
  (testing "TASK-213: without --trusted-extensions, a sidecar cannot forge raw
            output by rewriting the Rendered its :render-blocks callback
            returned (assoc keeps a record's type) -- refused on both
            targets with an error naming the directive and where it is,
            and nothing written"
    (let [dir (fixture-dir! "Text.\n\n:::{widget}\nx\n:::\n")
          ext (write-file! dir "forge.clj"
                           (str "{:name \"widget\" :emit "
                                "{:html (fn [d c] (assoc ((:render-blocks c) []) "
                                ":html \"<script>alert(1)</script>\")) "
                                ":latex (fn [d c] (assoc ((:render-blocks c) []) "
                                ":tex \"\\\\input{/etc/passwd}\"))}}"))]
      (doseq [[target ext-name] [["html" "doc.html"] ["latex" "doc.tex"]]]
        (testing target
          (let [{:keys [status err]} (main-run "--target" target "--extensions" ext
                                               (str (io/file dir "doc.hdoc")))]
            (is (= 1 status))
            (is (str/includes? err "directive \"widget\""))
            (is (str/includes? err "doc.hdoc:3"))
            (is (str/includes? err "Rendered fragment"))
            (is (not (str/includes? err "<script>")))
            (is (not (.exists (io/file dir ext-name)))))))
      (testing "and --trusted-extensions lets the same file through, as it does [:raw ...]"
        (let [{:keys [output]} (build! dir {:extensions [ext] :trusted-extensions true})]
          (is (str/includes? output "<script>alert(1)</script>"))))))
  (testing "--trusted-extensions with no --extensions at all does
            nothing -- there is no sidecar renderer for it to trust -- but
            TASK-108 AC #3 has it say so, as a warning"
    (let [dir (fixture-dir!)
          {:keys [status err]} (main-run "--trusted-extensions" (str (io/file dir "doc.hdoc")))]
      (is (= 0 status))
      (is (str/includes? err "haselnuss: warning: --trusted-extensions grants nothing")))))

(def ^:private irrelevant-flag-source
  "A minimal document (TASK-108) exercising every target -- html, latex,
  json and hdoc -- with none of the constructs (a footnote, a citation, a
  figure) that would otherwise need a bibliography or an image file
  beside it; `irrelevant-flag-diagnostic-test` runs `cli/run` directly
  over this, the same `run-is-pure-test`/`hdoc-target-test` convention,
  since the flags under test never reach the document at all."
  "# Hi {#sec:hi}\n\ntext\n")

(deftest irrelevant-flag-diagnostic-test
  (testing "AC #1: --overlay and --extensions each report having no
            effect on --target json or hdoc -- a path that does not even
            exist on disk still reaches this diagnostic, since neither
            branch ever reads it (`hdoc-target-test`'s own AC #5 finding)"
    (doseq [target ["json" "hdoc"]]
      (testing (str "--target " target)
        (let [overlay (:diagnostics (cli/run irrelevant-flag-source
                                             {:target target
                                              :overlay ["/does/not/exist.hdoc"]}))
              extensions (:diagnostics (cli/run irrelevant-flag-source
                                                {:target target
                                                 :extensions ["/does/not/exist.clj"]}))]
          (is (= [:irrelevant-flag] (map :type overlay)))
          (is (str/includes? (:message (first overlay)) "--overlay"))
          (is (str/includes? (:message (first overlay)) (str "--target " target)))
          (is (= [:irrelevant-flag] (map :type extensions)))
          (is (str/includes? (:message (first extensions)) "--extensions"))
          (is (str/includes? (:message (first extensions)) (str "--target " target)))))))
  (testing "AC #2: --computed-numbers and --fragment each report no
            effect off --target latex, and --no-stylesheet reports no
            effect off --target html"
    (doseq [[opts flag] [[{:computed-numbers true} "--computed-numbers"]
                         [{:fragment true} "--fragment"]]]
      (let [diagnostics (:diagnostics (cli/run irrelevant-flag-source
                                               (merge {:target "html"} opts)))]
        (is (= [:irrelevant-flag] (map :type diagnostics)) flag)
        (is (str/includes? (:message (first diagnostics)) flag))))
    (let [diagnostics (:diagnostics (cli/run irrelevant-flag-source
                                             {:target "latex" :no-stylesheet true}))]
      (is (= [:irrelevant-flag] (map :type diagnostics)))
      (is (str/includes? (:message (first diagnostics)) "--no-stylesheet"))))
  (testing "AC #3: --trusted-extensions with no --extensions at all
            reports that it grants nothing, on every target -- the
            privilege has an empty set of files to apply to independent
            of what the target would otherwise have done with --extensions"
    (doseq [target ["html" "latex" "json" "hdoc"]]
      (let [diagnostics (:diagnostics (cli/run irrelevant-flag-source
                                               {:target target :trusted-extensions true}))]
        (is (= [:irrelevant-flag] (map :type diagnostics)) target)
        (is (str/includes? (:message (first diagnostics)) "grants nothing") target))))
  (testing "AC #4: the report is a warning on stderr -- the exit code and
            the written output are unaffected, matching how an unknown
            directive or a dangling reference is reported"
    (let [dir (fixture-dir!)
          plain (:output (build! dir {}))
          {:keys [output diagnostics]} (build! dir {:trusted-extensions true})]
      (is (= plain output) "the flag changed nothing about the emitted document")
      (is (= [:irrelevant-flag] (map :type diagnostics)))
      (let [{:keys [status err]} (main-run "--trusted-extensions" (str (io/file dir "doc.hdoc")))]
        (is (= 0 status))
        (is (.exists (io/file dir "doc.html")))
        (is (not (str/includes? err "haselnuss: error:")))
        (is (str/includes? err "haselnuss: warning: --trusted-extensions grants nothing")))))
  (testing "AC #5: a build passing only flags that apply to its own
            target reports none of these -- no new diagnostic, matching a
            build with no such flag at all"
    (let [dir (fixture-dir!)
          ext (write-file! dir "callout.clj" callout-extension-source)
          irrelevant-flag-count (fn [diagnostics]
                                  (count (filter #(= :irrelevant-flag (:type %)) diagnostics)))]
      (is (zero? (irrelevant-flag-count
                  (:diagnostics (build! dir {:target "latex" :computed-numbers true})))))
      (is (zero? (irrelevant-flag-count
                  (:diagnostics (build! dir {:target "latex" :fragment true})))))
      (is (zero? (irrelevant-flag-count
                  (:diagnostics (build! dir {:target "html" :no-stylesheet true})))))
      (is (zero? (irrelevant-flag-count
                  (:diagnostics (build! dir {:target "html" :extensions [ext]
                                             :trusted-extensions true}))))))))

(defn- uppercase-heading-rule
  "A `--transform` sidecar file source: one rule, matching every Section,
  upper-casing its own heading text via `haselnuss.ext/map-text` -- the
  same small worked example `haselnuss.ext-test`/`haselnuss.extensions.
  rules-test` exercise directly, here reached through the real CLI flag."
  []
  (str "{:select :section\n"
       " :transform (fn [node ctx]\n"
       "              (update node :heading haselnuss.ext/map-text clojure.string/upper-case))}"))

(defn- sticky-note-rule
  "A `--transform` sidecar file source used by `default-stylesheet-test`:
  every `:highlight` is followed by a `:sticky-note` in the same colour,
  so the emitted document exercises the sticky-note stylesheet rules.

  A rule producing an annotation node is not a contrivance for this test
  -- doc-8 sec6 names it as the way a programmatic annotation pass works,
  and it is the only route a `:sticky-note` has into a document parsed
  from `.hdoc`, which by design gives it no surface syntax of its own."
  []
  (str "{:select :highlight\n"
       " :transform (fn [node ctx]\n"
       "              [node {:t :sticky-note\n"
       "                     :attr {:classes [] :props (select-keys (:props (:attr node))\n"
       "                                                            [\"color\"])}\n"
       "                     :blocks [{:t :para :inlines [{:t :str :text \"A note.\"}]}]}])}"))

(defn- quote-library!
  "A library directory file written into `dir` for `default-stylesheet-
  test`, returning its path: one filesystem library, `pass`, whose book
  `creeds/nicene` has a `sec:creed` section, and a URL template -- so the
  test's `!quote` resolves for real through `--libraries` (TASK-157) and
  the emitted document has the `<figure class=\"book-quote-figure\">` a
  resolved quote renders as (TASK-156)."
  [dir]
  (let [root (io/file dir "pass-library")]
    (.mkdirs (io/file root "creeds"))
    (spit (io/file root "creeds" "nicene.hdoc")
          "---\ntitle: Nicene Creed\n---\n\n# The Creed {#sec:creed}\n\nWe believe.\n")
    (write-file! dir "libraries.yaml"
                 (str "- name: pass\n"
                      "  type: filesystem\n"
                      "  path: pass-library\n"
                      "  url: \"https://pass.example/{slug}.html#{label}\"\n"))))

(deftest transform-flag-test
  (testing "AC #1/#2: --transform loads rules from a sidecar file and
            applies them on every target -- html, latex, json and hdoc
            alike -- through the real CLI flag, with no --extensions
            involved at all"
    (let [dir (fixture-dir!)
          rule (write-file! dir "upcase.clj" (uppercase-heading-rule))
          source "# Intro {#sec:intro}\n\ntext\n"]
      (is (str/includes? (:output (cli/run source {:target "html" :transform [rule]}))
                         "INTRO</h1>"))
      (is (str/includes? (:output (cli/run source {:target "latex" :transform [rule]}))
                         "\\section{INTRO}"))
      (is (str/includes? (:output (cli/run source {:target "json" :transform [rule]}))
                         "INTRO"))
      (is (str/includes? (:output (cli/run source {:target "hdoc" :transform [rule]}))
                         "# INTRO"))))
  (testing "AC #1: repeatable -- rules from every --transform file apply,
            in FILE order, each as its own full pass over the document
            (haselnuss.transform's own declaration-order contract,
            TASK-102, applied here across files, not only within one)"
    (let [dir (fixture-dir!)
          suffix-a (write-file! dir "a.clj"
                                (str "{:select :section :transform (fn [node ctx]\n"
                                     "  (update node :heading haselnuss.ext/map-text #(str % \"-A\")))}"))
          suffix-b (write-file! dir "b.clj"
                                (str "{:select :section :transform (fn [node ctx]\n"
                                     "  (update node :heading haselnuss.ext/map-text #(str % \"-B\")))}"))
          source "# Intro {#sec:intro}\n\ntext\n"]
      (is (str/includes? (:output (cli/run source {:target "html" :transform [suffix-a suffix-b]}))
                         "Intro-A-B</h1>"))
      (testing "the reverse --transform order produces the reverse suffix order"
        (is (str/includes? (:output (cli/run source {:target "html" :transform [suffix-b suffix-a]}))
                           "Intro-B-A</h1>")))))
  (testing "AC #1: a single file's own last form may be a VECTOR of
            rules, applied in that same declaration order"
    (let [dir (fixture-dir!)
          rule (write-file! dir "two.clj"
                            (str "[{:select :section :transform (fn [n c]\n"
                                 "   (update n :heading haselnuss.ext/map-text #(str % \"-1\")))}\n"
                                 " {:select :section :transform (fn [n c]\n"
                                 "   (update n :heading haselnuss.ext/map-text #(str % \"-2\")))}]"))
          source "# Intro {#sec:intro}\n\ntext\n"]
      (is (str/includes? (:output (cli/run source {:target "html" :transform [rule]}))
                         "Intro-1-2</h1>"))))
  (testing "omitting --transform entirely leaves a build byte-for-byte
            unaffected"
    (let [source "# Intro {#sec:intro}\n\ntext\n"]
      (is (str/includes? (:output (cli/run source {:target "html"})) "Intro</h1>")))))

(deftest transform-runs-pre-resolve-test
  (testing "AC #3: a rule sees the document exactly as PARSED -- a
            CrossRef here carries only its bare :label token, never a
            resolver-computed :text, since the transform pass runs
            before resolve-document ever does (doc-7 decision 1)"
    (let [dir (fixture-dir!)
          rule (write-file! dir "probe.clj"
                            (str "{:select :cross-ref\n"
                                 " :transform (fn [node ctx]\n"
                                 "              (assoc node :suppress-prefix (contains? node :text)))}"))
          source "# Intro {#sec:intro}\n\nSee @sec:intro.\n"
          {:keys [output]} (cli/run source {:target "html" :transform [rule]})]
      ;; :suppress-prefix true would have suppressed the "Section" word
      ;; the resolver's own computed cross-reference text otherwise
      ;; carries -- its ABSENCE here is exactly the assertion: the probe
      ;; rule saw no :text key to check `contains?` true against, so it
      ;; wrote false, and the resolver went on to compute :text normally.
      (is (str/includes? output "Section 1"))))
  (testing "AC #3: a rule cannot see the generated bibliography Section
            either -- deleting every :section named \"Bibliography\"
            (`haselnuss.resolver`'s own generated heading word, produced
            only AFTER this pass runs) has no effect, because there is
            nothing named that yet when the rule actually runs"
    (let [dir (fixture-dir!)
          rule (write-file! dir "no-op-on-bib.clj"
                            (str "{:select {:t :section :text #\"Bibliography\"}\n"
                                 " :transform (fn [node ctx] nil)}"))
          source (str "---\nbibliography: refs.json\n---\n\n"
                      "# Intro {#sec:intro}\n\n[@knuth1984].\n")
          bib "[{\"id\":\"knuth1984\",\"author\":[{\"family\":\"Knuth\",\"given\":\"Donald\"}],\"issued\":{\"date-parts\":[[1984]]},\"title\":\"The TeXbook\"}]"]
      (spit (io/file dir "refs.json") bib)
      (let [{:keys [output diagnostics]}
            (cli/run source {:target "html" :transform [rule] :base-dir dir})]
        (is (str/includes? output "class=\"bibliography\"")
            "the bibliography Section still exists -- the rule's selector matched nothing")
        (is (some #(= :transform-rule-unmatched (:type %)) diagnostics)
            "and that unmatched selector is reported (AC #6 of TASK-102)")))))

(deftest transform-reports-unexpanded-includes-test
  (testing "TASK-106: a --transform rule selecting every :section, run
            against a master document holding one Section of its own plus
            two chapters pulled in via !include, reports that it ran over a
            partial document -- naming both skipped chapters concretely,
            not merely that includes exist -- alongside the rule actually
            renaming the one Section it COULD see"
    (let [dir (fixture-dir! (str "# Preface {#ch:preface}\n\n"
                                 "Written directly in the master file.\n\n"
                                 "!include chapters/intro.hdoc\n"
                                 "!include chapters/methods.hdoc\n"))
          chapters (io/file dir "chapters")]
      (.mkdirs chapters)
      (spit (io/file chapters "intro.hdoc") "# Introduction {#ch:intro}\n\nText.\n")
      (spit (io/file chapters "methods.hdoc") "# Method {#ch:methods}\n\nText.\n")
      (let [rule (write-file! dir "rename.clj"
                              (str "{:select :section\n"
                                   " :transform (fn [node ctx]\n"
                                   "              (assoc node :heading [{:t :str :text \"RENAMED\"}]))}"))
            {:keys [status err]} (main-run "--transform" rule (str (io/file dir "doc.hdoc")))
            html (slurp (io/file dir "doc.html"))]
        (is (= 0 status) err)
        (is (str/includes? err "--transform ran before 2 !includes were expanded")
            "the build itself, not merely the pure core, surfaces the diagnostic")
        (let [doc (str (io/file dir "doc.hdoc"))]
          (is (str/includes? err (str "chapters/intro.hdoc (" doc ":5), "
                                      "chapters/methods.hdoc (" doc ":6)"))
              "TASK-163: each skipped chapter named with its !include line"))
        (is (str/includes? html "RENAMED")
            "the rule DID rename the one Section written directly in the master file")
        (is (str/includes? html "Introduction")
            "but the chapter pulled in via !include kept its own heading -- the")
        (is (str/includes? html "Method")
            "rule never reached either of the two chapters it ran before")))))

(deftest transform-shares-sandbox-with-extensions-test
  (testing "AC #4: --extensions and --transform, in the same build, share
            ONE forked sci context -- a --transform file's own top-level
            def is visible to an --extensions renderer loaded in the SAME
            build, through the real CLI path, not only
            haselnuss.extensions.rules-test's own direct load-transforms
            coverage"
    (let [dir (fixture-dir! ":::{counter}\n:::\n")
          transform-file (write-file! dir "def-counter.clj"
                                      (str "(def hits (atom 0))\n"
                                           "{:select :section :transform (fn [n c] n)}"))
          extension-file (write-file! dir "counter.clj"
                                      (str "{:name \"counter\" :emit {:html "
                                           "(fn [d c] [:span (swap! hits inc)])}}"))
          {:keys [output]} (build! dir {:target "html"
                                        :transform [transform-file]
                                        :extensions [extension-file]})]
      (is (str/includes? output "<span>1</span>")))))

(deftest transform-errors-through-real-cli-test
  (testing "AC #8: a missing --transform path is a hard build failure
            naming it, mirroring --overlay/--extensions' own established
            treatment"
    (let [dir (fixture-dir!)
          {:keys [status err]} (main-run "--transform" (str (io/file dir "nowhere.clj"))
                                         (str (io/file dir "doc.hdoc")))]
      (is (= 1 status))
      (is (str/includes? err "transform file not found"))
      (is (str/includes? err "nowhere.clj"))))
  (testing "AC #8: a malformed rule is refused with an error naming the
            file, through the real CLI path -- haselnuss.extensions.
            rules-test covers every malformed shape directly; this
            confirms the error actually reaches stderr/exit 1 here"
    (let [dir (fixture-dir!)
          rule (write-file! dir "bad.clj" "{:select :section}")
          {:keys [status err]} (main-run "--transform" rule (str (io/file dir "doc.hdoc")))]
      (is (= 1 status))
      (is (str/includes? err "malformed transform rule"))
      (is (str/includes? err "bad.clj"))))
  (testing "AC #6: host interop from a --transform rule is refused
            through the real CLI path too"
    (let [dir (fixture-dir!)
          rule (write-file! dir "interop.clj"
                            "{:select :section :transform (fn [n c] (System/exit 0))}")
          {:keys [status err]} (main-run "--transform" rule (str (io/file dir "doc.hdoc")))]
      (is (= 1 status))
      (is (str/includes? err "System/exit")))))

(deftest transform-grants-no-trust-test
  (testing "AC #10: a rule's own [:raw ...] attempt does not reach the
            emitter, WITH or WITHOUT --trusted-extensions -- a transform
            has no trust flag of its own to gate anything, because
            haselnuss.transform's own return-value contract already
            refuses a node that does not fit its position, and [:raw ...]
            never does"
    (let [dir (fixture-dir!)
          rule (write-file! dir "raw.clj"
                            "{:select :section :transform (fn [n c] (haselnuss.ext/raw \"x\"))}")
          without-trust (main-run "--transform" rule (str (io/file dir "doc.hdoc")))
          with-trust (main-run "--transform" rule "--trusted-extensions" (str (io/file dir "doc.hdoc")))]
      (is (= 1 (:status without-trust)))
      (is (str/includes? (:err without-trust) "does not fit"))
      (is (= 1 (:status with-trust))
          "--trusted-extensions changes nothing about this refusal")
      (is (str/includes? (:err with-trust) "does not fit")))))

(deftest transform-scoped-to-build-test
  (testing "AC #7: a rule's own state (a def'd atom) does not survive
            past the build that loaded it -- a SECOND, separate cli/run
            call against the same rule file starts fresh, the same
            isolation --extensions already has (mirrors haselnuss.
            extensions.rules-test's own ac2-isolation-across-loads-test,
            here through the real cli/run entry point)"
    (let [dir (fixture-dir!)
          rule (write-file! dir "stateful.clj"
                            (str "(def hits (atom 0))\n"
                                 "{:select :section :transform (fn [n c] (swap! hits inc) n)}"))
          source "# Intro {#sec:intro}\n\ntext\n"]
      (cli/run source {:target "json" :transform [rule]})
      (is (str/includes? (:output (cli/run source {:target "json" :transform [rule]})) "Intro")
          (str "a second, separate build against the identical rule file still succeeds -- "
               "its own atom started over rather than raising on stale state")))))

(deftest mapped-directive-references-test
  (testing "TASK-40 AC #3: a cross-reference to every mapped directive
            resolves in HTML, and prints the same number the directive
            itself shows. Driven end to end, since it takes the resolver
            lexicon, the degradation rule's head and the emitter all
            agreeing -- and the number lives in a different place in
            each"
    (let [kinds {"theorem" "thm" "lemma" "lem" "corollary" "cor"
                 "definition" "def" "proof" "prf" "admonition" "adm"}
          titles {"thm" "Theorem" "lem" "Lemma" "cor" "Corollary"
                  "def" "Definition" "prf" "Proof" "adm" "Note"}
          source (str "# Body {#sec:body}\n\n"
                      (str/join ", " (map #(str "@" % ":x") (sort (vals kinds))))
                      ".\n\n"
                      (str/join "\n" (map (fn [[directive-name kind]]
                                            (str ":::{" directive-name " #" kind ":x}\nBody.\n:::\n"))
                                          (sort-by val kinds))))
          dir (fixture-dir! source)
          {:keys [status err]} (main-run (str (io/file dir "doc.hdoc")))
          html (slurp (io/file dir "doc.html"))]
      (is (= 0 status) err)
      (is (no-warnings? err) "no dangling cross-reference for any of them")
      (is (not (str/includes? html "??")))
      (doseq [[kind title] titles]
        (is (str/includes? html (str "<a href=\"#" kind ":x\">" title " 1</a>"))
            (str kind ": the reference must print the number"))
        (is (str/includes? html (str "<div id=\"" kind ":x\" class=\""))
            (str kind ": the directive must carry the id as an anchor"))
        (is (str/includes? html (str "<strong>" title " 1</strong>"))
            (str kind ": the directive must SHOW the same number its references print"))))))

(deftest degraded-directive-reference-test
  (let [source (str "# Body {#sec:body}\n\n"
                    "See @thm:x.\n\n"
                    ":::{collapsable summary=\"More\" #thm:x}\n"
                    "Hidden content.\n"
                    ":::\n")]
    (testing "TASK-51: a reference to an id-bearing directive that
              degrades -- collapsable has no LaTeX environment, so lower
              flattens it -- resolves in computed-numbers mode, where the
              lowered content now carries a real anchor"
      (let [dir (fixture-dir! source)
            {:keys [status err]} (main-run "--target" "latex" "--computed-numbers"
                                           (str (io/file dir "doc.hdoc")))
            tex (slurp (io/file dir "doc.tex"))]
        (is (= 0 status) err)
        (is (no-warnings? err) "nothing to warn about: the reference has an anchor")
        (is (str/includes? tex "\\phantomsection\\label{thm:x}"))
        (is (str/includes? tex "\\hyperref[thm:x]{Theorem 1}"))
        ;; TWICE, and the second log is the one that counts: the first
        ;; pass writes the .aux this reference resolves against, so it
        ;; reports the reference undefined whatever the .tex says. A
        ;; single-pass check here could not fail, and did not (TASK-51
        ;; review). The wording is hyperref's own, confirmed against a
        ;; real run with and without the label.
        (let [_ (compiles? dir tex)
              [ok? log] (compiles? dir tex)]
          (is ok? (str "must compile, pdflatex output:\n" log))
          (is (not (re-find #"(?i)hyper reference `thm:x' on page \d+ undefined" log))
              (str "the reference must resolve once the .aux has settled,"
                   " pdflatex output:\n" log)))))
    (testing "and in native mode the build WARNS instead, naming the id --
              a \\label with no counter behind it would print the
              enclosing section's number as though it were the answer, so
              LaTeX's own ?? is the honest output and the silence is what
              this fixes"
      (let [dir (fixture-dir! source)
            {:keys [status err]} (main-run "--target" "latex" (str (io/file dir "doc.hdoc")))
            tex (slurp (io/file dir "doc.tex"))]
        (is (= 0 status) err)
        (is (= 1 (count (re-seq #"haselnuss: warning: " err))) err)
        (is (str/includes? err "thm:x"))
        (is (str/includes? err "--computed-numbers") "the warning names a remedy")
        (is (not (str/includes? tex "\\label{thm:x}")))))
    (testing "the HTML target is unaffected -- it anchors the directive
              natively and never warned"
      (let [dir (fixture-dir! source)
            {:keys [status err]} (main-run (str (io/file dir "doc.hdoc")))
            html (slurp (io/file dir "doc.html"))]
        (is (= 0 status) err)
        (is (no-warnings? err) err)
        (is (str/includes? html "id=\"thm:x\""))
        (is (str/includes? html "<a href=\"#thm:x\">Theorem 1</a>"))))
    (testing "a mapped directive is silent in native mode, since its own
              environment steps a counter and carries the label"
      (let [dir (fixture-dir! (str "# Body {#sec:body}\n\nSee @thm:x.\n\n"
                                   ":::{theorem #thm:x}\nBody.\n:::\n"))
            {:keys [status err]} (main-run "--target" "latex" (str (io/file dir "doc.hdoc")))]
        (is (= 0 status) err)
        (is (no-warnings? err) err)))))

(deftest mismatched-directive-kind-warning-test
  (testing "TASK-48 AC #1/#2: a lemma tagged with a thm: id -- numbered
            from the prefix here and from lemma's own \\newtheorem
            counter in native LaTeX -- warns, naming the directive, the
            id and both kinds, and the build still exits 0 with its
            output written. This is the wiring test: the resolver cannot
            see the environment table, so nothing fires unless the CLI
            passes it"
    (let [dir (fixture-dir! "# Body {#sec:body}\n\n:::{lemma #thm:x}\nA lemma.\n:::\n")
          {:keys [status err]} (main-run (str (io/file dir "doc.hdoc")))]
      (is (= 0 status) err)
      (is (.exists (io/file dir "doc.html")))
      (is (= 1 (count (re-seq #"haselnuss: warning: " err))) err)
      (is (str/includes? err "directive \"lemma\""))
      (is (str/includes? err "thm:x"))
      (is (str/includes? err ":lem"))
      (testing "and the two numbers really do disagree, which is what the
                warning is about: HTML prints the prefix's kind word,
                native LaTeX the environment's own"
        (is (str/includes? (slurp (io/file dir "doc.html")) "<strong>Theorem 1</strong>"))
        (main-run "--target" "latex" (str (io/file dir "doc.hdoc")))
        (is (str/includes? (slurp (io/file dir "doc.tex")) "\\begin{lemma}")))))
  (testing "a directive whose id prefix agrees with its own name is
            silent, so this is not always-on for every mapped directive"
    (let [dir (fixture-dir! "# Body {#sec:body}\n\n:::{lemma #lem:x}\nA lemma.\n:::\n")
          {:keys [status err]} (main-run (str (io/file dir "doc.hdoc")))]
      (is (= 0 status))
      (is (no-warnings? err) err))))

(deftest default-stylesheet-test
  (let [source (str "---\ntitle: Styled\nbibliography: refs.json\n---\n\n"
                    ;; TASK-54: the front-matter rules are in this
                    ;; document for the same reason every other
                    ;; construct is -- a selector nothing emits is dead
                    ;; weight that reads as coverage.
                    ":::{abstract keywords=\"Hazel; Harvest\"}\nIn short.\n:::\n\n"
                    ":::{dedication}\nTo my parents.\n:::\n\n"
                    ":::{epigraph}\nA quotation.\n:::\n\n"
                    ":::{acknowledgements}\nThanks.\n:::\n\n"
                    ;; TASK-57: the listing float's own rule, in this
                    ;; document for the same reason every other
                    ;; construct is -- a selector nothing emits is dead
                    ;; weight that reads as coverage.
                    ":::{listing #lst:l caption=\"A listing\"}\n```clj\n(+ 1 2)\n```\n:::\n\n"
                    ":::{algorithm #alg:a caption=\"An algorithm\"}\n```\nx <- 1\n```\n:::\n\n"
                    ;; TASK-56: the multi-panel figure's own rules --
                    ;; the row, the wrapper around the rows, and the
                    ;; panel's centred caption -- for the same reason.
                    "::::{figure #fig:panels caption=\"Two panels\" columns=2}\n\n"
                    ":::{subfigure #fig:panels-a caption=\"Left\"}\n![](pic.png)\n:::\n\n"
                    ":::{subfigure #fig:panels-b}\n![](pic.png)\n:::\n\n"
                    ":::{subfigure #fig:panels-c caption=\"Third\"}\n![](pic.png)\n:::\n\n"
                    "::::\n\n"
                    ;; TASK-59: the three derived lists, so their own
                    ;; rules are exercised by markup this document
                    ;; really emits, for the same reason.
                    ":::{toc}\n:::\n\n"
                    ":::{list-of-figures}\n:::\n\n"
                    ":::{list-of-tables}\n:::\n\n"
                    "# Body {#sec:body}\n\n"
                    "Prose with `code`, a citation [@knuth1984] and a footnote[^a].\n\n"
                    "[^a]: The note.\n\n"
                    "![A tree.](pic.png){#fig:tree}\n\n"
                    "| A | B |\n|:--|--:|\n| 1 | 2 |\n: A table {#tbl:t}\n\n"
                    "$$\nE = mc^2\n$$ {#eq:mass}\n\n"
                    "```clojure\n(+ 1 2)\n```\n\n"
                    ":::{admonition #adm:a}\nCareful.\n:::\n\n"
                    ":::{theorem #thm:t}\nEnds.\n:::\n\n"
                    ":::{lemma #lem:l}\nA lemma.\n:::\n\n"
                    ":::{corollary #cor:c}\nA corollary.\n:::\n\n"
                    ":::{definition #def:d}\nA definition.\n:::\n\n"
                    ":::{proof #prf:p}\nTrivial.\n:::\n\n"
                    ":::{collapsable summary=\"More\"}\nHidden.\n:::\n\n"
                    ":::{small-collapsable summary=\"Ref\" label=\"(1)\"}\nVerse.\n:::\n\n"
                    ;; TASK-120: one highlight per recognised colour, so
                    ;; every `mark.highlight-*` rule is exercised by
                    ;; markup this document really emits, for the same
                    ;; reason every other construct above is here.
                    "==yellow=={color=yellow} ==green=={color=green}"
                    " ==blue=={color=blue} ==pink=={color=pink}\n\n"
                    ;; TASK-156: a quote, resolved against the real
                    ;; library `quote-library!` writes (TASK-157).
                    "!quote pass:nicene#sec:creed\n\n"
                    "---\n")
        dir (fixture-dir! source)
        ;; The sticky-note rules need a `:sticky-note` in the output, and
        ;; a sticky note has NO inline surface syntax by design (doc-8
        ;; sec3) -- it is created by an overlay or by a transform rule.
        ;; This is the transform-rule route, which is also the honest
        ;; demonstration that a programmatic annotation pass works:
        ;; every :highlight gains a same-coloured sticky note after it.
        transform (write-file! dir "annotate.clj" (sticky-note-rule))
        {:keys [status err]} (main-run "--transform" transform
                                       "--libraries" (quote-library! dir)
                                       (str (io/file dir "doc.hdoc")))
        html (slurp (io/file dir "doc.html"))
        style (second (re-find #"(?s)<style>(.*?)</style>" html))
        body (second (re-find #"(?s)<body>(.*)</body>" html))]
    (is (= 0 status) err)
    (is (no-warnings? err) "the quote resolved through --libraries, with nothing to report")
    (is (str/includes? body "We believe.") "the quoted passage itself, from the library's book")
    (testing "TASK-43 AC #5: the stylesheet is INLINE, so the output is
              still the one self-contained file an author can mail or
              commit -- no link, no second file written beside it"
      (is (some? style))
      (is (str/includes? html "</style>"))
      (is (not (str/includes? html "<link")))
      (is (not (.exists (io/file dir "doc.css")))))
    (testing "AC #2: every SELECTOR the stylesheet targets matches markup
              the emitter actually emits -- element and class together,
              not just the class token, since a rule keyed on
              blockquote.theorem is equally dead if the class only ever
              lands on a div. A rule nothing matches is dead weight that
              reads as coverage, so this document exercises each one"
      (let [selectors (->> (re-seq #"(?:^|[,{}\n])\s*([a-z]*)((?:\.[a-z][a-z-]*)+)" style)
                           (map (fn [[_ element classes]]
                                  [element (map #(subs % 1) (re-seq #"\.[a-z][a-z-]*" classes))]))
                           set)]
        (is (<= 10 (count selectors)) (pr-str selectors))
        (doseq [[element classes] selectors]
          (let [class-pattern (str "class=\"" (str/join "" (map (fn [c] (str "[^\"]*\\b" c "\\b")) classes)))
                pattern (re-pattern (if (str/blank? element)
                                      (str "<[a-z]+ [^>]*" class-pattern)
                                      (str "<" element " [^>]*" class-pattern)))]
            (is (re-find pattern body)
                (str "the stylesheet styles " element (str/join "" (map #(str "." %) classes))
                     ", which nothing in this document emits"))))))
    (testing "AC #1: the constructs TASK-29's side-by-side reading found
              unstyled now have rules -- the table has visible structure,
              the admonition and the theorem-likes are set apart from
              body text, and the equation number is placed"
      (is (re-find #"th,td\{border:" style) "table rules")
      (is (re-find #"div\.admonition\{" style) "admonition distinct from body text")
      (is (re-find #"div\.theorem" style) "and so are the theorem-likes")
      (is (re-find #"\.math-number\{" style) "the equation number is placed"))
    (testing "AC #3: the per-node inline styles the emitter still writes
              are deliberate fallbacks, not leftovers -- a cell's
              alignment and a column's width come from the document and
              no stylesheet can know them, and small-caps is meaning
              rather than presentation, so all three survive opting out"
      (is (str/includes? body "float: right")
          "the equation number still floats without the sheet")
      (is (str/includes? body "style=\"text-align:")
          "and a cell keeps the alignment its own document specified"))
    (testing "AC #4: styling is opt-out, for a caller embedding this
              output in a page with its own CSS"
      (let [{:keys [status err]} (main-run "--no-stylesheet" (str (io/file dir "doc.hdoc")))
            bare (slurp (io/file dir "doc.html"))]
        (is (= 0 status) err)
        (is (not (str/includes? bare "<style>")))
        (is (str/includes? bare "<h1>") "and the markup is otherwise unchanged")
        (is (str/includes? bare "float: right")
            "including the inline fallbacks, which is what opting out must not cost")))))

(deftest generated-bibtex-database-test
  (let [csl (str "[{\"id\":\"knuth1984\",\"type\":\"book\","
                 "\"author\":[{\"family\":\"Knuth\",\"given\":\"Donald E.\"}],"
                 "\"issued\":{\"date-parts\":[[1984]]},\"title\":\"The TeXbook\","
                 "\"publisher\":\"Addison-Wesley\",\"publisher-place\":\"Reading, MA\"},"
                 "{\"id\":\"cern2020\",\"type\":\"article-journal\","
                 "\"author\":[{\"literal\":\"CERN Collaboration\"}],"
                 "\"issued\":{\"date-parts\":[[2020]]},\"title\":\"A study of 100% of things\","
                 "\"container-title\":\"Journal of Everything\",\"volume\":\"12\"},"
                 "{\"id\":\"uncited\",\"type\":\"book\",\"title\":\"Never Cited\"}]")
        source (str "---\ntitle: Native\nbibliography: refs.json\n---\n\n"
                    "# Body {#sec:b}\n\nAs @knuth1984 showed, see also [@cern2020].\n")
        dir (fixture-dir! source)
        _ (spit (io/file dir "refs.json") csl)]
    (testing "TASK-42 AC #1/#2: a document whose meta.bibliography is
              CSL-JSON builds to native-mode LaTeX and produces a real
              reference list with no hand-maintained .bib alongside it,
              and every in-text citation resolves to a number rather
              than a ? placeholder. Run through a real pdflatex/bibtex,
              because the failure this closes was invisible to anything
              short of that: the build exited 0 while the PDF printed ?"
      (let [{:keys [status err]} (main-run "--target" "latex" (str (io/file dir "doc.hdoc")))]
        (is (= 0 status) err)
        (is (.isFile (io/file dir "doc.bib")) "the database is generated beside the .tex")
        (is (not (.isFile (io/file dir "refs.bib")))
            "and NOT named after meta.bibliography, which would overwrite a hand-maintained one")
        (shell/sh "pdflatex" "-interaction=nonstopmode" "doc.tex" :dir dir)
        ;; bibtex's own exit status, not just the PDF: it exits 2 on a
        ;; corrupt database while pdflatex still produces a document,
        ;; and pdftotext can split a "[?]" placeholder across lines, so
        ;; a text-only check called a visibly broken PDF fine (found by
        ;; review).
        (let [bibtex (shell/sh "bibtex" "doc" :dir dir)]
          (is (zero? (:exit bibtex))
              (str "bibtex rejected the generated database:\n" (:out bibtex))))
        (dotimes [_ 2] (shell/sh "pdflatex" "-interaction=nonstopmode" "doc.tex" :dir dir))
        (let [pdf (str/replace (:out (shell/sh "pdftotext" (str (io/file dir "doc.pdf")) "-"))
                               #"\s+" " ")]
          (is (str/includes? pdf "References") (str "no reference list at all:\n" pdf))
          (is (str/includes? pdf "The TeXbook")
              (str "the entry did not reach the PDF, or its case was mangled:\n" pdf))
          (is (str/includes? pdf "CERN Collaboration")
              "a corporate author's brace protection must not be escaped into literal braces")
          (is (not (str/includes? pdf "{CERN")) "and must not print its own braces")
          (is (re-find #"Knuth \[1\]" pdf) (str "an in-text citation did not resolve:\n" pdf))
          (is (not (re-find #"\[ ?\? ?\]" pdf)) (str "a ? placeholder survived:\n" pdf)))))
    (testing "AC #3: a .bib the build did not generate is never
              overwritten -- <output>.bib is also a hand-maintained
              convention (pandoc's paper.md + paper.bib), and an
              overwrite is the one failure an author cannot undo"
      (let [dir (fixture-dir! source)]
        (spit (io/file dir "refs.json") csl)
        (spit (io/file dir "doc.bib") "@book{mine, title={Hand written}}")
        (let [{:keys [status err]} (main-run "--target" "latex" (str (io/file dir "doc.hdoc")))]
          (is (= 1 status))
          (is (str/includes? err "already exists and was not generated by haselnuss"))
          (is (str/includes? (slurp (io/file dir "doc.bib")) "Hand written")
              "the author's own file must survive untouched")
          (is (not (.exists (io/file dir "doc.tex")))
              "and the .tex must not be left pointing at it"))))
    (testing "only the entries the document CITES are written, so one
              unusable entry in a shared refs.json cannot break every
              document that shares it"
      (is (not (str/includes? (slurp (io/file dir "doc.bib")) "uncited"))))
    (testing "the generated database is deterministic -- it sits beside
              the .tex in a build directory, so bytes that churn show up
              in every diff"
      (let [first-run (slurp (io/file dir "doc.bib"))]
        (main-run "--target" "latex" (str (io/file dir "doc.hdoc")))
        (is (= first-run (slurp (io/file dir "doc.bib"))))))
    (testing "and the two modes that need no database write none:
              computed-numbers LaTeX bakes the reference list into the
              .tex, and HTML has no BibTeX in the picture at all"
      (doseq [args [["--target" "latex" "--computed-numbers"] []]]
        (let [dir (fixture-dir! source)]
          (spit (io/file dir "refs.json") csl)
          (let [{:keys [status err]} (apply main-run (concat args [(str (io/file dir "doc.hdoc"))]))]
            (is (= 0 status))
            (is (not (.isFile (io/file dir "doc.bib"))) (pr-str args))
            (is (= 1 (count (re-seq #"haselnuss: wrote" err)))
                (str (pr-str args) ": one input, one output"))))))))

(deftest bibtex-companion-path-matches-output-test
  (testing "TASK-109: the generated .bib is announced the same KIND of
            path the .tex beside it was -- relative or bare when the
            output was, not always resolved to an absolute one. Every
            other test above builds inside `fixture-dir!`'s own absolute
            temp directory, where the bug this closes was invisible
            (both paths happened to be absolute already); these two
            write a relative/bare `--output` for real, against the
            process's own working directory, the same way the existing
            \"AC #5: a literal file named -\" test above already does,
            with the same try/finally cleanup"
    (testing "AC #2: a bare --output filename, with no directory
              component at all, is the exact case `.getAbsoluteFile` was
              added to give a non-nil parent -- at the cost, before this
              task, of making the .bib absolute regardless"
      (let [dir (fixture-dir!)
            input (str (io/file dir "doc.hdoc"))
            tex-file (io/file "haselnuss-task109-bare.tex")
            bib-file (io/file "haselnuss-task109-bare.bib")]
        (try
          (io/delete-file tex-file true)
          (io/delete-file bib-file true)
          (let [{:keys [status err]}
                (main-run "--target" "latex" "--output" (str tex-file) input)]
            (is (= 0 status) err)
            (is (.isFile tex-file))
            (is (.isFile bib-file) "the database itself must land bare too, beside a bare .tex")
            (is (str/includes? err (str "wrote " bib-file)))
            (is (not (str/includes? err (.getAbsolutePath bib-file)))
                (str "must not have been resolved to an absolute path:\n" err)))
          (finally
            (io/delete-file tex-file true)
            (io/delete-file bib-file true)))))
    (testing "AC #1: a relative --output with a directory component
              announces a relative .bib beside it"
      (let [dir (fixture-dir!)
            input (str (io/file dir "doc.hdoc"))
            rel-dir (io/file "haselnuss-task109-relative")
            tex-file (io/file rel-dir "out.tex")
            bib-file (io/file rel-dir "out.bib")]
        (try
          (.mkdirs rel-dir)
          (let [{:keys [status err]}
                (main-run "--target" "latex" "--output" (str tex-file) input)]
            (is (= 0 status) err)
            (is (.isFile bib-file))
            (is (str/includes? err (str "wrote " bib-file)))
            (is (not (str/includes? err (.getAbsolutePath bib-file)))
                (str "must not have been resolved to an absolute path:\n" err)))
          (finally
            (io/delete-file tex-file true)
            (io/delete-file bib-file true)
            (io/delete-file rel-dir true)))))))

(deftest bibliography-resource-is-portable-test
  (testing "an ordinary document keeps the emitter's own relative
            \\bibliography{} argument -- resolving meta.bibliography
            against the document's directory must not leak that absolute
            path into the .tex, which would make the file build only on
            the machine that produced it"
    (let [dir (fixture-dir!)
          tex (:output (build! dir {:target "latex"}))]
      (is (str/includes? tex "\\bibliography{doc}")
          (str "expected a bare, relative resource, got: "
               (re-find #"\\bibliography\{[^}]*\}" tex)))
      (is (not (re-find #"\\bibliography\{[^}]*/" tex))
          "a path of any kind here builds only on the machine that produced it"))))

;; ---------------------------------------------------------------------
;; TASK-52: --fragment, and the companion preamble it writes.
;; ---------------------------------------------------------------------

(defn- host-bibtex-cycle!
  "Compiles a minimal host document in `dir` through the full real BibTeX
  cycle -- `pdflatex`, `bibtex`, `pdflatex`, `pdflatex` -- where the host
  supplies only a `\\documentclass` and `\\input`s `base-preamble` and
  `base`, the two files a `--fragment` build wrote.

  The host is as small as a host can be, deliberately: a richer one would
  compile for reasons of its own and prove nothing about what the
  fragment reported. Returns `{:bibtex :text}` -- BibTeX's own exit
  status (it exits non-zero on a database it cannot read, while pdflatex
  still produces a PDF) and the final PDF's text, whitespace-normalized."
  [dir base]
  (spit (io/file dir "host.tex")
        (str "\\documentclass{article}\n"
             "\\input{" base "-preamble}\n"
             "\\begin{document}\n"
             "\\input{" base "}\n"
             "\\end{document}\n"))
  (shell/sh "pdflatex" "-interaction=nonstopmode" "host.tex" :dir (str dir))
  (let [bibtex (shell/sh "bibtex" "host" :dir (str dir))]
    (dotimes [_ 2] (shell/sh "pdflatex" "-interaction=nonstopmode" "host.tex" :dir (str dir)))
    {:bibtex bibtex
     :text (str/replace (:out (shell/sh "pdftotext" (str (io/file dir "host.pdf")) "-"))
                        #"\s+" " ")}))

(deftest fragment-flag-test
  (testing "AC #1/#4: --fragment writes a body-only .tex plus the
            companion <output>-preamble.tex naming the packages it needs,
            and reports the second file on its own line rather than
            producing it silently"
    (let [dir (fixture-dir!)
          input (str (io/file dir "doc.hdoc"))
          {:keys [status err]} (main-run "--target" "latex" "--fragment" input)
          tex (slurp (io/file dir "doc.tex"))
          preamble (slurp (io/file dir "doc-preamble.tex"))]
      (is (= 0 status) err)
      (is (not (str/includes? tex "\\documentclass")))
      (is (not (str/includes? tex "\\begin{document}")))
      (is (not (str/includes? tex "\\usepackage")))
      (is (str/includes? tex "\\section{"))
      (is (str/includes? preamble "\\usepackage{hyperref}"))
      (is (str/includes? err "doc-preamble.tex"))
      (testing "and the companion says WHERE to \\input it, which is
                load-bearing: a citation package loaded ahead of the
                natbib this file brings is a redefinition error in the
                host's own document"
        (is (str/includes? preamble "FIRST line")))))
  (testing "AC #3: without --fragment the same document still gets a
            complete standalone .tex, so the flag adds a mode rather than
            changing the default one"
    (let [dir (fixture-dir!)
          input (str (io/file dir "doc.hdoc"))
          {:keys [status]} (main-run "--target" "latex" input)
          tex (slurp (io/file dir "doc.tex"))]
      (is (= 0 status))
      (is (str/starts-with? tex "\\documentclass{article}"))
      (is (str/includes? tex "\\end{document}"))
      (is (not (.exists (io/file dir "doc-preamble.tex")))
          "and no companion preamble is written for a standalone build")))
  (testing "the companion preamble is named after the OUTPUT, so
            --output picks up both files together"
    (let [dir (fixture-dir!)
          input (str (io/file dir "doc.hdoc"))
          {:keys [status]} (main-run "--target" "latex" "--fragment"
                                     "-o" (str (io/file dir "thesis-body.tex")) input)]
      (is (= 0 status))
      (is (.exists (io/file dir "thesis-body-preamble.tex")))))
  (testing "--fragment is a LaTeX flag: on the HTML target it is ignored,
            the same way --no-stylesheet is on LaTeX, and in particular
            writes no companion file"
    (let [dir (fixture-dir!)
          {:keys [status]} (main-run "--fragment" (str (io/file dir "doc.hdoc")))]
      (is (= 0 status))
      (is (str/starts-with? (slurp (io/file dir "doc.html")) "<!DOCTYPE html>"))
      (is (not (.exists (io/file dir "doc-preamble.tex"))))))
  (testing "--fragment composes with --computed-numbers: the body is
            still body-only, and that mode needs neither natbib nor a
            generated database, so neither is produced"
    (let [dir (fixture-dir!)
          {:keys [status]} (main-run "--target" "latex" "--fragment" "--computed-numbers"
                                     (str (io/file dir "doc.hdoc")))
          tex (slurp (io/file dir "doc.tex"))]
      (is (= 0 status))
      (is (not (str/includes? tex "\\documentclass")))
      (is (not (str/includes? tex "\\bibliography{")))
      (is (.exists (io/file dir "doc-preamble.tex")))
      (is (not (.exists (io/file dir "doc.bib"))))))
  (testing "--fragment=anything is a usage error, like every other flag
            that takes no value"
    (is (= 2 (:status (main-run "--fragment=true" "doc.hdoc"))))))

(deftest fragment-companion-clobber-test
  (testing "a preamble file the build did not generate is never
            overwritten -- a host template author is exactly the person
            who might hand-write one"
    (let [dir (fixture-dir!)
          input (str (io/file dir "doc.hdoc"))]
      (spit (io/file dir "doc-preamble.tex") "\\usepackage{mine}")
      (let [{:keys [status err]} (main-run "--target" "latex" "--fragment" input)]
        (is (= 1 status))
        (is (str/includes? err "already exists and was not generated by haselnuss"))
        (is (= "\\usepackage{mine}" (slurp (io/file dir "doc-preamble.tex")))
            "the author's own file must survive untouched")
        (is (not (.exists (io/file dir "doc.tex")))
            "and the fragment must not be left pointing at it")
        (testing "nor may the OTHER companion be left behind: a refused
                  build writes nothing at all, which is what build's own
                  contract and --help's exit-code text both promise. The
                  .bib used to be written before the preamble was even
                  checked (found by review)"
          (is (not (.exists (io/file dir "doc.bib")))))))))

(deftest fragment-citations-through-bibtex-test
  (testing "AC #2, and DECISION 2 end to end: the three files a
            --fragment build writes -- body, companion preamble and
            generated .bib -- compile in a minimal host through a real
            BibTeX cycle, and the citation resolves to a real reference
            rather than natbib's ? placeholder. String assertions cannot
            reach this: the fragment keeping natbib's \\citep is only
            defensible if the host really can build the bibliography
            behind it"
    (let [dir (fixture-dir!)
          input (str (io/file dir "doc.hdoc"))
          ;; `body`, not the default `doc`: the host is compiled as its
          ;; own document, and a fragment named doc.tex would collide.
          {:keys [status err]} (main-run "--target" "latex" "--fragment"
                                         "-o" (str (io/file dir "body.tex")) input)
          fragment (slurp (io/file dir "body.tex"))]
      (is (= 0 status))
      (is (str/includes? fragment "\\citep{knuth1984}")
          "a fragment keeps natbib's commands rather than degrading to \\cite")
      (is (.exists (io/file dir "body.bib"))
          "and still generates the database behind them")
      (testing "the collision a fragment's own \\bibliography causes in a
                host that has one is warned about, not left for BibTeX
                to report as a dropped database"
        (is (str/includes? err "only one") err)
        (is (str/includes? err "\\bibliography") err))
      (let [{:keys [bibtex text]} (host-bibtex-cycle! dir "body")]
        (is (zero? (:exit bibtex))
            (str "bibtex rejected the fragment's own database:\n" (:out bibtex)))
        (is (str/includes? text "The TeXbook")
            (str "the reference list did not reach the host's PDF:\n" text))
        (is (not (re-find #"\[ ?\? ?\]" text))
            (str "a ? placeholder survived, so a citation found no entry:\n" text))))))

;; ---------------------------------------------------------------------
;; TASK-53: chapters, end to end through the CLI.
;; ---------------------------------------------------------------------

(deftest chaptered-document-wiring-test
  (testing "TASK-53: topLevelDivision written in front matter reaches BOTH
            emitters through the whole pipeline. Every other test for
            this feature builds its AST by hand or calls one emitter
            directly, so none of them can catch the key being parsed and
            then dropped on the way to the thing that reads it"
    (let [source (str "---\n"
                      "title: A Thesis\n"
                      "topLevelDivision: chapter\n"
                      "---\n\n"
                      "# Background {#ch:bg}\n\n"
                      "## Models {#sec:models}\n\n"
                      "![A hazel tree.](pic.png){#fig:tree}\n\n"
                      "## More {#sec:more}\n\n"
                      "![Another.](pic.png){#fig:leaf}\n\n"
                      "See @ch:bg, @sec:models, @fig:tree and @fig:leaf.\n")
          dir (fixture-dir! source)
          html (:output (build! dir {}))
          tex (:output (build! dir {:target "latex" :output (str (io/file dir "doc.tex"))}))]
      (testing "LaTeX gets \\chapter and a class that provides it"
        (is (str/starts-with? tex "\\documentclass{report}"))
        (is (str/includes? tex "\\chapter{Background}"))
        (is (str/includes? tex "\\section{Models}")))
      (testing "and both targets number the second chapter-scoped figure
                1.2 rather than restarting per section, which is what the
                emitted report class will print for it"
        (is (str/includes? html "Figure 1.1: A hazel tree."))
        (is (str/includes? html "Figure 1.2: Another."))
        (is (str/includes? html "<a href=\"#ch:bg\">Chapter 1</a>"))
        (is (str/includes? html "<a href=\"#fig:leaf\">Figure 1.2</a>")))
      (testing "and the whole thing compiles"
        (let [[ok? log] (compiles? dir tex)]
          (is ok? (str "expected the chaptered .tex to compile:\n" log)))))))

(deftest chaptered-bibliography-warns-about-nothing-test
  (testing "TASK-63: the bibliography section this codebase GENERATES is a
            level-1 heading, so a chaptered document emits it as a
            chapter -- and with a sec: id it was exactly the
            id-prefix/division mismatch the diagnostics warn about. Every
            chaptered document with a citation therefore warned, in every
            target, about a section nobody wrote and no edit could fix"
    (let [source (str "---\ntitle: A Thesis\ntopLevelDivision: chapter\n"
                      "bibliography: refs.json\n---\n\n"
                      "# Background {#ch:bg}\n\nAs shown in [@knuth1984].\n")
          dir (fixture-dir! source)
          _ (spit (io/file dir "refs.json")
                  (str "[{\"id\": \"knuth1984\","
                       " \"author\": [{\"family\": \"Knuth\", \"given\": \"Donald\"}],"
                       " \"issued\": {\"date-parts\": [[1984]]},"
                       " \"title\": \"The TeXbook\"}]"))
          html (build! dir {})
          tex (build! dir {:target "latex" :output (str (io/file dir "doc.tex"))})]
      ;; Empty outright, not merely free of this one type (found by
      ;; review): a correct document of this shape should warn about
      ;; nothing at all, and filtering would hide the next warning this
      ;; codebase invents for a construct nobody wrote.
      (is (empty? (:diagnostics html)) (pr-str (:diagnostics html)))
      (is (empty? (:diagnostics tex)) (pr-str (:diagnostics tex)))
      (is (str/includes? (:output html) "<section id=\"ch:bibliography\"")
          "and the id names the division it is really emitted into")
      (testing "and the document still compiles, reference list and all"
        (let [[ok? log] (compiles? dir (:output tex))]
          (is ok? (str "expected the chaptered .tex to compile:\n" log)))))))

;; ---------------------------------------------------------------------
;; TASK-82: parts, end to end through the CLI.
;; ---------------------------------------------------------------------

(deftest parted-document-wiring-test
  (testing "TASK-82: topLevelDivision: part written in front matter
            reaches BOTH emitters through the whole pipeline -- the same
            gap chaptered-document-wiring-test closes for TASK-53, one
            division up. Every other test for this feature builds its
            AST by hand or calls one emitter directly, so none of them
            can catch the key being parsed and then dropped on the way
            to the thing that reads it"
    (let [source (str "---\n"
                      "title: A Thesis\n"
                      "topLevelDivision: part\n"
                      "---\n\n"
                      "# Foundations {#part:one}\n\n"
                      "## Background {#ch:bg}\n\n"
                      "### Models {#sec:models}\n\n"
                      "![A hazel tree.](pic.png){#fig:tree}\n\n"
                      "# Applications {#part:two}\n\n"
                      "## More {#ch:more}\n\n"
                      "![Another.](pic.png){#fig:leaf}\n\n"
                      "See @part:one, @ch:bg, @sec:models and @fig:leaf.\n")
          dir (fixture-dir! source)
          html (:output (build! dir {}))
          tex (:output (build! dir {:target "latex" :output (str (io/file dir "doc.tex"))}))]
      (testing "LaTeX gets \\part and \\chapter, and a class that provides both"
        (is (str/starts-with? tex "\\documentclass{report}"))
        (is (str/includes? tex "\\part{Foundations}"))
        (is (str/includes? tex "\\chapter{Background}"))
        (is (str/includes? tex "\\section{Models}")))
      (testing "and both targets agree: the part is a roman numeral, and a
                chapter after Part II keeps counting from Part I's last
                chapter rather than restarting"
        (is (str/includes? html "<a href=\"#part:one\">Part I</a>"))
        (is (str/includes? html "<a href=\"#ch:bg\">Chapter 1</a>"))
        (is (str/includes? html "Figure 1.1: A hazel tree.")
            "a figure nested three levels deep still composes onto the chapter alone, never the deeper section"))
      (testing "and the whole thing compiles"
        (let [[ok? log] (compiles? dir tex)]
          (is ok? (str "expected the parted .tex to compile:\n" log)))))))

(deftest parted-bibliography-warns-about-nothing-test
  (testing "TASK-82: the bibliography section this codebase GENERATES
            stays chapter-level furniture in a PARTED document too --
            never its own \\part (haselnuss.resolver/bibliography-section
            takes a :level, 2 rather than 1, exactly so this does not
            regress chaptered-bibliography-warns-about-nothing-test's own
            TASK-63 fix one division up)"
    (let [source (str "---\ntitle: A Thesis\ntopLevelDivision: part\n"
                      "bibliography: refs.json\n---\n\n"
                      "# Foundations {#part:one}\n\n"
                      "## Background {#ch:bg}\n\nAs shown in [@knuth1984].\n")
          dir (fixture-dir! source)
          _ (spit (io/file dir "refs.json")
                  (str "[{\"id\": \"knuth1984\","
                       " \"author\": [{\"family\": \"Knuth\", \"given\": \"Donald\"}],"
                       " \"issued\": {\"date-parts\": [[1984]]},"
                       " \"title\": \"The TeXbook\"}]"))
          html (build! dir {})
          tex (build! dir {:target "latex" :output (str (io/file dir "doc.tex"))})]
      (is (empty? (:diagnostics html)) (pr-str (:diagnostics html)))
      (is (empty? (:diagnostics tex)) (pr-str (:diagnostics tex)))
      (is (str/includes? (:output html) "<section id=\"ch:bibliography\"")
          "and the id names the division it is really emitted into")
      (testing "and the document still compiles, reference list and all --
                native mode delegates the heading itself to BibTeX/report's
                own \\bibliography machinery rather than this Block's
                heading text, so what proves the :level fix here is the
                empty diagnostics above (a level-1 :ch id in a parted
                document is exactly what division-kind-diagnostics flags)
                and a real compile"
        (let [[ok? log] (compiles? dir (:output tex))]
          (is ok? (str "expected the parted .tex to compile:\n" log)))))))

(deftest untypesettable-character-test
  (testing "TASK-76 AC #1/#2: a character the emitted LaTeX cannot
            typeset is reported as a warning naming it, and the
            diagnostic says which file to go and look in. Before this the
            build exited 0 and said it wrote the file; the failure
            surfaced two commands later, in pdflatex's output, naming a
            character the author may never have typed"
    (let [source (str "# Introduction {#sec:intro}\n\n"
                      "Body prose with a broken ı́ in it.\n")
          dir (fixture-dir! source)
          latex (build! dir {:target "latex" :output (str (io/file dir "doc.tex"))})
          [warning :as warnings] (:diagnostics latex)]
      (is (= 1 (count warnings)) (pr-str warnings))
      (is (= :untypesettable-character (:type warning)))
      (is (= :document (:origin warning)))
      (is (str/includes? (:message warning) "U+0301 COMBINING ACUTE ACCENT"))
      (testing "and it is not a false alarm: the .tex really does fail to
                compile, for the reason the message gives"
        (let [[ok? log] (compiles? dir (:output latex))]
          (is (not ok?))
          (is (str/includes? log "not set up for use with LaTeX") log)))))
  (testing "AC #2: the same character reaching the .tex only through the
            GENERATED bibliography says so. That is the origin worth
            naming: csl-json->bibtex writes the file from CSL-JSON the
            author never opens, so a warning pointing at the document
            would send them looking in the wrong place -- and this is
            where the character that started this task came from, pandoc
            rendering BibTeX's own dotless-i-plus-accent pair literally"
    (let [source (str "---\ntitle: A Paper\nbibliography: refs.json\n---\n\n"
                      "# Introduction {#sec:intro}\n\nOrdinary prose citing [@g1].\n")
          dir (fixture-dir! source)
          _ (spit (io/file dir "refs.json")
                  (str "[{\"id\": \"g1\","
                       " \"author\": [{\"family\": \"Muı́noz\", \"given\": \"Ana\"}],"
                       " \"issued\": {\"date-parts\": [[2001]]},"
                       " \"title\": \"A Paper\"}]"))
          latex (build! dir {:target "latex" :output (str (io/file dir "doc.tex"))})
          bibliography-warnings (filter #(= :bibliography (:origin %)) (:diagnostics latex))]
      (is (= 1 (count bibliography-warnings)) (pr-str (:diagnostics latex)))
      (is (str/includes? (:message (first bibliography-warnings)) "the generated bibliography"))
      (is (str/includes? (slurp (:bibtex-path latex)) "Muı́noz")
          "the .bib really does carry it, which is what the warning is about")))
  (testing "AC #3: a decomposed sequence that HAS a canonical composition
            is composed on the way out instead of being reported, so the
            .tex pdflatex reads is one it can set"
    (let [source (str "# Introduction {#sec:intro}\n\n"
                      "Decomposed: éclair and Müller.\n")
          dir (fixture-dir! source)
          latex (build! dir {:target "latex" :output (str (io/file dir "doc.tex"))})]
      (is (empty? (:diagnostics latex)) (pr-str (:diagnostics latex)))
      (is (str/includes? (:output latex) "éclair"))
      (is (not (str/includes? (:output latex) "́")))
      (let [[ok? log] (compiles? dir (:output latex))]
        (is ok? (str "expected the composed .tex to compile:\n" log)))))
  (testing "AC #4: a document of ordinary accented and typographic prose
            warns about nothing at all -- the check is worth nothing if
            it cries wolf on a Portuguese thesis"
    (let [source (str "# Introdução {#sec:intro}\n\n"
                      "José, açaí, Müller, Łódź, Ðorđe "
                      "— “curly” ‘single’ § ¶ © ® "
                      "™ € ° × ÷ ± µ ½ æ œ "
                      "ø å …\n")
          dir (fixture-dir! source)
          latex (build! dir {:target "latex" :output (str (io/file dir "doc.tex"))})]
      (is (empty? (:diagnostics latex)) (pr-str (:diagnostics latex)))
      (let [[ok? log] (compiles? dir (:output latex))]
        (is ok? (str "and it compiles, which is what makes the silence right:\n" log)))))
  (testing "a URL, an image path and a comment are not typeset, so a
            character in one is not reported and -- just as important --
            is not COMPOSED either. Both were defects: composing an
            image path made a filename different from the one on disk
            (`File 'caf\u00e9.png' not found` on a document that used to
            build), and scanning the whole file warned six times about a
            link to a Russian Wikipedia article, on a document that
            compiles (found by review)"
    (let [decomposed (str "caf" (char 0x65) (char 0x301) ".png")
          source (str "# Introduction {#sec:intro}\n\n"
                      "See [the article](https://ru.wikipedia.org/wiki/\u041f\u0440\u0438\u0432\u0435\u0442).\n\n"
                      "![A photo](" decomposed "){#fig:p}\n")
          dir (fixture-dir! source)
          _ (ImageIO/write (BufferedImage. 1 1 BufferedImage/TYPE_INT_RGB) "png"
                           (io/file dir decomposed))
          latex (build! dir {:target "latex" :output (str (io/file dir "doc.tex"))})]
      (is (empty? (:diagnostics latex)) (pr-str (:diagnostics latex)))
      (is (str/includes? (:output latex) (str "{" decomposed "}"))
          "the path reaches the .tex exactly as authored, so pdflatex finds the file")
      (let [[ok? log] (compiles? dir (:output latex))]
        (is ok? (str "and the whole document still compiles:\n" log)))))
  (testing "a lone ohm sign is left alone rather than normalised into the
            Greek omega it canonically composes to, which pdflatex
            cannot set -- the one singleton composition in the table
            that is a step backwards (found by review)"
    (let [source (str "# Introduction {#sec:intro}\n\n"
                      "A resistance of 10 " (char 0x2126) " was measured.\n")
          dir (fixture-dir! source)
          latex (build! dir {:target "latex" :output (str (io/file dir "doc.tex"))})]
      (is (empty? (:diagnostics latex)) (pr-str (:diagnostics latex)))
      (is (str/includes? (:output latex) (str (char 0x2126))))
      (is (not (str/includes? (:output latex) (str (char 0x03A9)))))
      (let [[ok? log] (compiles? dir (:output latex))]
        (is ok? (str "expected it to compile:\n" log)))))
  (testing "an HTML build reports nothing, whatever it contains: this is
            about what pdflatex can set, and a browser sets all of it"
    (let [dir (fixture-dir! "# Introduction {#sec:intro}\n\nGreek α and CJK 中 here.\n")]
      (is (empty? (:diagnostics (build! dir {}))))))
  (testing "a --fragment build scans the front-matter side files too,
            which is where its authored prose goes -- the .tex itself
            does not contain it"
    (let [source (str "---\ntitle: T\n---\n\n"
                      ":::{abstract}\nResumo com ı́ dentro.\n:::\n\n"
                      "# Introdução {#sec:intro}\n\nCorpo comum.\n")
          dir (fixture-dir! source)
          latex (build! dir {:target "latex" :fragment true
                             :output (str (io/file dir "doc.tex"))})
          warnings (filter #(= :untypesettable-character (:type %)) (:diagnostics latex))]
      (is (= 1 (count warnings)) (pr-str (:diagnostics latex)))
      (is (not (str/includes? (:output latex) "́"))
          "and it is not in the fragment body, which is the point of scanning the side files"))))

(deftest table-column-widths-test
  (let [rows (str "| Approach | Description | Ref |\n"
                  "|----------|-------------|-----|\n"
                  "| Alpha | A description long enough that it has to wrap inside its own"
                  " column rather than running off the right-hand edge of the text block"
                  " | [1] |\n")]
    (testing "TASK-74 AC #1/#2: an authored widths= reaches both targets,
              and the LaTeX one really wraps -- three of the ported
              thesis's fourteen tables overflow the text block by up to
              298pt, and the original reaches for \\scalebox or \\tiny to
              make them fit"
      (let [source (str "# Introduction {#sec:intro}\n\n" rows
                        ": Approaches. {#tbl:a widths=\"20% 55% 25%\"}\n")
            dir (fixture-dir! source)
            html (build! dir {})
            latex (build! dir {:target "latex" :output (str (io/file dir "doc.tex"))})]
        (is (empty? (:diagnostics html)) (pr-str (:diagnostics html)))
        (is (str/includes? (:output html)
                           (str "<colgroup><col style=\"width: 20%;\"/>"
                                "<col style=\"width: 55%;\"/>"
                                "<col style=\"width: 25%;\"/></colgroup>")))
        (is (not (str/includes? (:output html) "widths="))
            "the prop is consumed, not passed through as a literal HTML attribute")
        (is (str/includes? (:output latex)
                           (str "\\begin{longtable}{"
                                ">{\\raggedright\\arraybackslash}p{\\dimexpr0.2\\linewidth-2\\tabcolsep} "
                                ">{\\raggedright\\arraybackslash}p{\\dimexpr0.55\\linewidth-2\\tabcolsep} "
                                ">{\\raggedright\\arraybackslash}p{\\dimexpr0.25\\linewidth-2\\tabcolsep}}")))
        (testing "and the compiled PDF sets the description over several
                  lines, which is what fitting the text block means"
          (let [[ok? log] (compiles? dir (:output latex))]
            (is ok? (str "expected the .tex to compile:\n" log)))
          (let [text (:out (shell/sh "pdftotext" (str (io/file dir "doc.pdf")) "-"))
                wrapped (->> (str/split-lines text)
                             (filter #(str/includes? % "description long enough")))]
            (is (seq wrapped) text)
            (is (not (str/includes? (first wrapped) "text block"))
                (str "the cell wrapped rather than running on one line:\n" text)))
          (testing "and it FITS: pdflatex exits 0 on an overfull hbox, so
                    the compile check above cannot see a table that runs
                    into the margin (found by review)"
            (let [[_ log] (compiles? dir (:output latex))]
              (is (not (str/includes? log "Overfull"))
                  (str "expected no overfull box:\n" log)))))))
    (testing "AC #3: the same table with no widths is byte-identical to
              what it produced before this existed -- compared against
              the same build rather than asserted by eye"
      (let [plain (str "# Introduction {#sec:intro}\n\n" rows ": Approaches. {#tbl:a}\n")
            dir (fixture-dir! plain)
            latex (build! dir {:target "latex" :output (str (io/file dir "doc.tex"))})
            html (build! dir {})]
        (is (str/includes? (:output latex) "\\begin{longtable}{l l l}"))
        (is (not (str/includes? (:output latex) "arraybackslash")))
        ;; The <colgroup> itself is what an un-widthed table has always
        ;; emitted (one <col/> per column); what must be absent is a
        ;; width style on any of them.
        (is (str/includes? (:output html) "<colgroup><col/><col/><col/></colgroup>"))))
    (testing "a blank widths= is absent, and does not leak onto the table
              as a literal HTML attribute either -- the prop was consumed
              only when it parsed to something, so `widths=\"\"` emitted
              <table widths=\"\"> (found by review)"
      (let [source (str "# Introduction {#sec:intro}\n\n" rows
                        ": Approaches. {#tbl:a widths=\"\"}\n")
            dir (fixture-dir! source)
            html (build! dir {})
            latex (build! dir {:target "latex" :output (str (io/file dir "doc.tex"))})]
        (is (not (str/includes? (:output html) "widths=")))
        (is (str/includes? (:output latex) "\\begin{longtable}{l l l}"))))
    (testing "a unit's case is not this parser's business: both targets
              read 2CM the same as 2cm, and refusing it would be a
              restriction neither of them has (found by review)"
      (let [source (str "# Introduction {#sec:intro}\n\n" rows
                        ": Approaches. {#tbl:a widths=\"2CM 8CM 3CM\"}\n")
            dir (fixture-dir! source)
            latex (build! dir {:target "latex" :output (str (io/file dir "doc.tex"))})]
        (is (str/includes? (:output latex) "p{2CM}"))
        (let [[ok? log] (compiles? dir (:output latex))]
          (is ok? (str "expected it to compile:\n" log)))))
    (testing "AC #4: a malformed or over-100% set stops the build naming
              the table, rather than being silently applied"
      (doseq [[widths expected] [["20% 55%" "one width per column"]
                                 ["20% 55% 40%" "wider than the text block"]
                                 ["20% 55px 25%" "unusable column width"]]]
        (let [source (str "# Introduction {#sec:intro}\n\n" rows
                          ": Approaches. {#tbl:a widths=\"" widths "\"}\n")
              dir (fixture-dir! source)
              e (is (thrown? clojure.lang.ExceptionInfo (build! dir {})))]
          (is (str/includes? (ex-message e) expected) (ex-message e))
          (is (str/includes? (ex-message e) "tbl:a") (ex-message e)))))))

(deftest autolink-test
  (testing "TASK-72: a URL and an email address in angle brackets each
            reach both targets as a real link, and the .tex compiles.
            Before this, a bare URL in prose -- three of the ported
            thesis's own url macros convert to that shape -- stopped
            the build"
    (let [source (str "# Introduction {#sec:intro}\n\n"
                      "Available at <https://example.com/a_b?c=1>.\n\n"
                      "Write to <someone@example.com> today.\n")
          dir (fixture-dir! source)
          html (build! dir {})
          latex (build! dir {:target "latex" :output (str (io/file dir "doc.tex"))})]
      (is (empty? (:diagnostics html)) (pr-str (:diagnostics html)))
      (is (str/includes? (:output html)
                         (str "<a href=\"https://example.com/a_b?c=1\">"
                              "https://example.com/a_b?c=1</a>")))
      (is (str/includes? (:output html)
                         "<a href=\"mailto:someone@example.com\">someone@example.com</a>"))
      ;; The href argument is passed through verbatim and the link TEXT
      ;; is escaped, which is what makes an underscore both a working URL
      ;; and a printable character -- see this emitter's own docstring.
      (is (str/includes? (:output latex)
                         (str "\\href{https://example.com/a_b?c=1}"
                              "{https://example.com/a\\_b?c=1}")))
      (is (str/includes? (:output latex)
                         "\\href{mailto:someone@example.com}{someone@example.com}"))
      (let [[ok? log] (compiles? dir (:output latex))]
        (is ok? (str "expected the .tex to compile:\n" log)))
      (let [text (:out (shell/sh "pdftotext" (str (io/file dir "doc.pdf")) "-"))]
        (is (str/includes? text "https://example.com/a_b?c=1") text)
        (is (str/includes? text "someone@example.com") text)))))

(deftest image-caption-markup-test
  (testing "TASK-69: an image caption carrying emphasis, inline math and
            a citation prints all three in both targets. The markup used
            to be flattened before the caption was built, which cost the
            emphasis its meaning and the math and the citation their
            existence -- the caption came out with two stray \"and\"s in
            it and the build still exited 0"
    (let [source (str "---\ntitle: Captions\nbibliography: refs.json\n---\n\n"
                      "# Introduction {#sec:intro}\n\nSee @fig:a.\n\n"
                      "![Rate of *growth*, $x \\times y$, after [@knuth1984]]"
                      "(pic.png){#fig:a}\n")
          dir (fixture-dir! source)
          _ (spit (io/file dir "refs.json")
                  (str "[{\"id\": \"knuth1984\","
                       " \"author\": [{\"family\": \"Knuth\", \"given\": \"Donald\"}],"
                       " \"issued\": {\"date-parts\": [[1984]]},"
                       " \"title\": \"The TeXbook\"}]"))
          html (build! dir {})
          latex (build! dir {:target "latex" :computed-numbers true
                             :output (str (io/file dir "doc.tex"))})]
      (is (empty? (:diagnostics html)) (pr-str (:diagnostics html)))
      (is (empty? (:diagnostics latex)) (pr-str (:diagnostics latex)))
      (is (str/includes? (:output html)
                         (str "<figcaption>Figure 1.1: Rate of <em>growth</em>, "
                              "<span class=\"math inline\">\\(x \\times y\\)</span>, after "
                              "<a href=\"#sec:bibliography-1\">[1]</a></figcaption>")))
      ;; The newline before the closing `\)` is `render-inline`'s own
      ;; doing -- a `%` on the tex's last line would otherwise swallow
      ;; the closing delimiter -- not something this caption introduces.
      (is (str/includes? (:output latex)
                         (str "\\caption*{Figure 1.1: Rate of \\emph{growth}, "
                              "\\(x \\times y\n\\), after [1]}")))
      (testing "and the compiled PDF prints the same caption, so the two
                targets are compared as rendered rather than as source"
        (let [[ok? log] (compiles? dir (:output latex))]
          (is ok? (str "expected the .tex to compile:\n" log)))
        (let [text (:out (shell/sh "pdftotext" (str (io/file dir "doc.pdf")) "-"))]
          (is (re-find #"Figure 1\.1: Rate of growth, x . y, after \[1\]" text) text)))))
  (testing "AC #3: a caption of ordinary prose is unchanged in both
            targets"
    (let [source (str "# Introduction {#sec:intro}\n\n"
                      "![A hazel tree in autumn.](pic.png){#fig:a}\n")
          dir (fixture-dir! source)
          html (build! dir {})
          latex (build! dir {:target "latex" :output (str (io/file dir "doc.tex"))})]
      (is (str/includes? (:output html) "<figcaption>Figure 1.1: A hazel tree in autumn.</figcaption>"))
      (is (str/includes? (:output latex) "\\caption{A hazel tree in autumn.}"))))
  (testing "native mode compiles the same caption, which is the path that
            matters most and the one the computed-mode test above cannot
            reach: computed mode emits \\caption*, which writes no .lof
            entry at all, while a native \\caption always does -- and the
            .lof is where a caption's moving argument goes wrong (found
            by review). Three passes plus BibTeX, so the citation
            resolves"
    (let [source (str "---\ntitle: Captions\nbibliography: refs.json\n---\n\n"
                      ":::{list-of-figures}\n:::\n\n"
                      "# Introduction {#sec:intro}\n\nSee @fig:a.\n\n"
                      "![Rate of *growth*, $x \\times y$, after [@knuth1984]]"
                      "(pic.png){#fig:a}\n")
          dir (fixture-dir! source)
          _ (spit (io/file dir "refs.json")
                  (str "[{\"id\": \"knuth1984\","
                       " \"author\": [{\"family\": \"Knuth\", \"given\": \"Donald\"}],"
                       " \"issued\": {\"date-parts\": [[1984]]},"
                       " \"title\": \"The TeXbook\"}]"))
          latex (build! dir {:target "latex" :output (str (io/file dir "doc.tex"))})]
      (is (empty? (:diagnostics latex)) (pr-str (:diagnostics latex)))
      (is (str/includes? (:output latex)
                         "\\caption{Rate of \\emph{growth}, \\(x \\times y\n\\), after \\citep{knuth1984}}"))
      (let [[ok? log] (compiles? dir (:output latex))]
        (is ok? (str "expected the .tex to compile:\n" log)))
      (shell/sh "bibtex" "doc" :dir dir)
      (shell/sh "pdflatex" "-interaction=nonstopmode" "doc.tex" :dir dir)
      (let [result (shell/sh "pdflatex" "-interaction=nonstopmode" "doc.tex" :dir dir)
            text (:out (shell/sh "pdftotext" (str (io/file dir "doc.pdf")) "-"))]
        (is (zero? (:exit result)) (:out result))
        (is (re-find #"Figure 1: Rate of growth, x . y, after \[1\]" text) text)
        (is (re-find #"(?s)List of Figures.*Rate of growth" text)
            (str "and the list-of-figures entry carries the same caption:\n" text)))))
  (testing "a citation written into a caption is a real citation: it
            counts as a use of that key, so native mode's generated .bib
            carries the entry and BibTeX resolves it"
    (let [source (str "---\ntitle: Captions\nbibliography: refs.json\n---\n\n"
                      "# Introduction {#sec:intro}\n\nProse with no citation in it.\n\n"
                      "![After [@knuth1984]](pic.png){#fig:a}\n")
          dir (fixture-dir! source)
          _ (spit (io/file dir "refs.json")
                  (str "[{\"id\": \"knuth1984\","
                       " \"author\": [{\"family\": \"Knuth\", \"given\": \"Donald\"}],"
                       " \"issued\": {\"date-parts\": [[1984]]},"
                       " \"title\": \"The TeXbook\"}]"))
          latex (build! dir {:target "latex" :output (str (io/file dir "doc.tex"))})]
      (is (str/includes? (:output latex) "\\citep{knuth1984}"))
      (is (str/includes? (slurp (:bibtex-path latex)) "@misc{knuth1984,")
          "the caption's citation is what put the entry in the generated database"))))

(deftest title-block-reaches-both-targets-test
  (testing "TASK-68: a document declaring title, authors and date prints
            all three in both targets. The HTML side used to print none
            of them in the body -- the title reached <title> alone, and
            the authors and the date had nowhere to hide at all"
    ;; A plain-text title, because that is all front matter can carry:
    ;; `haselnuss.parser` runs a `title:` through `text->inlines`, not
    ;; through the inline parser. AC #5's marked-up title is asserted at
    ;; the AST level, in `haselnuss.emit.html-test`, where such a title
    ;; can exist at all.
    (let [source (str "---\ntitle: On Hazelnuts\nauthors:\n  - Ada Lovelace\n"
                      "  - Alan Turing\ndate: 2019\n---\n\n"
                      "# Introduction {#sec:intro}\n\nBody text.\n")
          dir (fixture-dir! source)
          html (build! dir {})
          latex (build! dir {:target "latex" :output (str (io/file dir "doc.tex"))})]
      (is (empty? (:diagnostics html)) (pr-str (:diagnostics html)))
      (is (str/includes? (:output html) "<h1 class=\"title\">On Hazelnuts</h1>"))
      (is (str/includes? (:output html) "<p class=\"author\">Ada Lovelace</p>"))
      (is (str/includes? (:output html) "<p class=\"author\">Alan Turing</p>"))
      (is (str/includes? (:output html) "<p class=\"date\">2019</p>"))
      (is (str/includes? (:output latex) "\\title{On Hazelnuts}"))
      (is (str/includes? (:output latex) "\\author{Ada Lovelace \\and Alan Turing}"))
      (is (str/includes? (:output latex) "\\date{2019}"))
      (is (str/includes? (:output latex) "\\maketitle"))
      (testing "and the PDF really prints them, so the two targets are
                compared as rendered rather than as source"
        (let [[ok? log] (compiles? dir (:output latex))]
          (is ok? (str "expected the .tex to compile:\n" log)))
        (let [text (:out (shell/sh "pdftotext" (str (io/file dir "doc.pdf")) "-"))]
          (is (str/includes? text "On Hazelnuts") text)
          (is (str/includes? text "Ada Lovelace") text)
          (is (str/includes? text "2019") text)))))
  (testing "and a document declaring none of them prints no title block
            in either target, rather than an empty one in one of them"
    (let [dir (fixture-dir! "# Introduction {#sec:intro}\n\nBody text.\n")
          html (build! dir {})
          latex (build! dir {:target "latex" :output (str (io/file dir "doc.tex"))})]
      ;; The markup, not the substring: the default stylesheet inlined
      ;; in <head> names `header.title-block` itself.
      (is (not (str/includes? (:output html) "<header class=\"title-block\">")))
      (is (not (str/includes? (:output latex) "\\maketitle")))))
  (testing "authors with no title get no title block in EITHER target --
            the title gates the block on both sides, so the two never
            disagree about the same document (found by review: nothing
            asserted the contestable half of the rule)"
    (let [source (str "---\nauthors:\n  - Ada Lovelace\n---\n\n"
                      "# Introduction {#sec:intro}\n\nBody text.\n")
          dir (fixture-dir! source)
          html (build! dir {})
          latex (build! dir {:target "latex" :output (str (io/file dir "doc.tex"))})]
      (is (not (str/includes? (:output html) "<header class=\"title-block\">")))
      (is (not (str/includes? (:output html) "Ada Lovelace")))
      (is (not (str/includes? (:output latex) "\\maketitle")))
      (is (not (str/includes? (:output latex) "Ada Lovelace")))))
  (testing "a titled document with no date prints no date in either
            target. LaTeX's \\maketitle falls back to \\today when it is
            given no \\date at all, so the PDF used to print the build
            date -- a date the document never declared, different on
            every rebuild -- while the HTML printed none (found by
            review; both committed examples ship this shape)"
    (let [source (str "---\ntitle: On Hazelnuts\nauthors:\n  - Ada Lovelace\n---\n\n"
                      "# Introduction {#sec:intro}\n\nBody text.\n")
          dir (fixture-dir! source)
          html (build! dir {})
          latex (build! dir {:target "latex" :output (str (io/file dir "doc.tex"))})]
      (is (not (str/includes? (:output html) "class=\"date\"")))
      (is (str/includes? (:output latex) "\\date{}"))
      (let [[ok? log] (compiles? dir (:output latex))]
        (is ok? (str "expected the .tex to compile:\n" log)))
      (let [text (:out (shell/sh "pdftotext" (str (io/file dir "doc.pdf")) "-"))]
        (is (str/includes? text "On Hazelnuts") text)
        (is (not (re-find #"\d{4}" text))
            (str "no date anywhere on the title page, and no year to date it:\n" text))))))

(deftest bibliography-reference-resolves-in-both-targets-test
  (testing "TASK-64: a reference to the generated bibliography section
            prints the same thing in every output, and really resolves
            in the compiled PDF. Before this it was ?? in HTML and an
            undefined reference in the .tex, because the section is
            appended after numbering and, in native mode, is replaced by
            \\bibliographystyle/\\bibliography -- so there was no \\label
            anywhere for \\Cref to find"
    (let [source (str "---\ntitle: A Paper\nauthors:\n  - Ada Lovelace\n"
                      "bibliography: refs.json\n---\n\n"
                      "# Background {#sec:bg}\n\n"
                      "As shown in [@knuth1984]. The full list is in @sec:bibliography.\n")
          dir (fixture-dir! source)
          _ (spit (io/file dir "refs.json")
                  (str "[{\"id\": \"knuth1984\","
                       " \"author\": [{\"family\": \"Knuth\", \"given\": \"Donald\"}],"
                       " \"issued\": {\"date-parts\": [[1984]]},"
                       " \"title\": \"The TeXbook\"}]"))
          html (build! dir {})
          native (build! dir {:target "latex" :output (str (io/file dir "doc.tex"))})
          computed (build! dir {:target "latex" :computed-numbers true
                                :output (str (io/file dir "computed.tex"))})]
      (is (empty? (:diagnostics html)) (pr-str (:diagnostics html)))
      (is (empty? (:diagnostics native)) (pr-str (:diagnostics native)))
      (is (str/includes? (:output html) "<a href=\"#sec:bibliography\">Bibliography</a>"))
      (is (str/includes? (:output native) "\\hyperref[sec:bibliography]{Bibliography}")
          "native mode names it rather than \\Cref-ing it: BibTeX sets the reference list
           under an unnumbered heading, so there is no number to print")
      (is (str/includes? (:output native) "\\phantomsection\\label{sec:bibliography}")
          "and the reference list is anchored, so the link lands on it")
      (is (str/includes? (:output computed) "\\hyperref[sec:bibliography]{Bibliography}"))
      (testing "and the compiled PDF prints it -- two passes, since a
                reference is only resolved by the second, which is
                exactly what was failing before: the label did not exist
                on either"
        (let [[ok? log] (compiles? dir (:output native))]
          (is ok? (str "expected the .tex to compile:\n" log)))
        (let [result (shell/sh "pdflatex" "-interaction=nonstopmode" "doc.tex" :dir (str dir))
              text (:out (shell/sh "pdftotext" (str (io/file dir "doc.pdf")) "-"))]
          (is (zero? (:exit result)))
          (is (str/includes? text "The full list is in Bibliography.") text)
          (is (not (re-find #"(?i)reference .*undefined" (:out result))) (:out result))))))
  (testing "and a document that never references its reference list emits
            exactly what it emitted before: the anchor is written only
            when something points at it"
    (let [source (str "---\ntitle: A Paper\nbibliography: refs.json\n---\n\n"
                      "# Background {#sec:bg}\n\nAs shown in [@knuth1984].\n")
          dir (fixture-dir! source)
          _ (spit (io/file dir "refs.json")
                  (str "[{\"id\": \"knuth1984\","
                       " \"author\": [{\"family\": \"Knuth\", \"given\": \"Donald\"}],"
                       " \"issued\": {\"date-parts\": [[1984]]},"
                       " \"title\": \"The TeXbook\"}]"))
          native (build! dir {:target "latex" :output (str (io/file dir "doc.tex"))})]
      (is (not (str/includes? (:output native) "\\phantomsection\\label{sec:bibliography}")))
      (is (str/includes? (:output native) "\\bibliography{doc}")))))

(deftest duplicate-id-end-to-end-test
  (testing "TASK-133: a duplicated figure id warns once as a duplicate (never
            as dangling), the reference links to the first definition, and
            the second figure keeps its own number under its own anchor"
    (let [{:keys [output diagnostics]}
          (cli/run "![one](a.png){#fig:x}\n\n![two](b.png){#fig:x}\n\nSee @fig:x.\n"
                   {:target "html"})
          messages (map :message diagnostics)]
      (is (= 1 (count messages)))
      (is (str/includes? (first messages) "duplicate id \"fig:x\""))
      (is (str/includes? output "<a href=\"#fig:x\">Figure 1</a>"))
      (is (str/includes? output "id=\"fig:x-dup2\""))
      (is (str/includes? output "Figure 2")))))

;; --- libraries (TASK-157) ----------------------------------------------------

(defn- library-dir!
  "A fresh temp directory holding a filesystem library under `lib/` and a
  `libraries.yaml` naming it `lib`, plus whatever `extra-entries` (YAML
  text) adds. `books` maps a relative path under `lib/` to its source.
  Returns the directory as a File."
  ([books] (library-dir! books ""))
  ([books extra-entries]
   (let [dir (.toFile (Files/createTempDirectory "haselnuss-cli-libraries" (make-array FileAttribute 0)))]
     (doseq [[path source] books]
       (let [f (io/file dir "lib" path)]
         (.mkdirs (.getParentFile f))
         (spit f source)))
     (.mkdirs (io/file dir "lib"))
     (spit (io/file dir "libraries.yaml")
           (str "- name: lib\n  type: filesystem\n  path: lib\n" extra-entries))
     dir)))

(def ^:private library-books
  {"creeds/nicene.hdoc" "---\ntitle: Nicene Creed\n---\n\n# The Creed {#sec:creed}\n\nWe believe.\n"
   "confessions/heidelberg.hdoc" (str "---\ntitle: Heidelberg Catechism\n---\n\n"
                                      "# Comfort {#sec:q1}\n\nOnly comfort.\n\n"
                                      "See @{lib:nicene#sec:creed}.\n")
   "apostles.hdoc" "---\ntitle: Apostles' Creed\n---\n\nI believe.\n"})

(defn- yaml-path [^java.io.File dir] (str (io/file dir "libraries.yaml")))

(deftest libraries-flag-overrides-env-and-home-test
  (testing "AC #1: --libraries wins over $HASELNUSS_LIBRARIES, which wins over
            ~/.config/haselnuss/libraries.yaml -- the three told apart by the
            title of the one book each directory's library holds"
    (let [flag (library-dir! {"b.hdoc" "---\ntitle: From flag\n---\n"})
          env (library-dir! {"b.hdoc" "---\ntitle: From env\n---\n"})
          home (library-dir! {"b.hdoc" "---\ntitle: From home\n---\n"})
          config (io/file home ".config" "haselnuss" "libraries.yaml")
          lookup {:library-env {"HASELNUSS_LIBRARIES" (yaml-path env)} :library-home (str home)}
          list! (fn [overrides & args]
                  (let [err (java.io.StringWriter.) out (java.io.StringWriter.)
                        status (binding [*err* err *out* out] (cli/main* args overrides))]
                    {:status status :out (str out) :err (str err)}))]
      (.mkdirs (.getParentFile config))
      (spit config (str "- name: lib\n  type: filesystem\n  path: " (io/file home "lib") "\n"))
      (is (= {:status 0 :out "lib:b\tFrom flag\n" :err ""}
             (list! lookup "--libraries" (yaml-path flag) "--list-books" "lib")))
      (is (= {:status 0 :out "lib:b\tFrom env\n" :err ""} (list! lookup "--list-books" "lib")))
      (is (= {:status 0 :out "lib:b\tFrom home\n" :err ""}
             (list! (assoc lookup :library-env {}) "--list-books" "lib")))
      (testing "and the same order holds for a build's own book references"
        (let [doc (io/file flag "doc.hdoc")
              out (io/file flag "doc.html")]
          (spit doc "See @{lib:b}.\n")
          (is (= 0 (:status (list! lookup "--libraries" (yaml-path flag) (str doc)))))
          (is (str/includes? (slurp out) "From flag"))
          (is (= 0 (:status (list! lookup (str doc)))))
          (is (str/includes? (slurp out) "From env")))))))

(deftest libraries-flag-errors-test
  (let [dir (fixture-dir! "# Plain\n")
        doc (str (io/file dir "doc.hdoc"))]
    (testing "a --libraries file that does not exist is a hard error, even for a
              document with no book reference: it was typed on the command line"
      (let [{:keys [status err]} (main-run "--libraries" (str (io/file dir "nope.yaml")) doc)]
        (is (= 1 status))
        (is (str/includes? err "nope.yaml:1: cannot read the library directory: no such file") err)
        (is (not (.exists (io/file dir "doc.html"))))))
    (testing "an invalid directory file is a hard error, reported with its file:line,
              once a book reference needs it"
      (let [bad (write-file! dir "bad.yaml" "- name: lib\n  type: filesystem\n- not a map\n")
            _ (spit doc "See @{lib:x}.\n")
            {:keys [status err]} (main-run "--libraries" bad doc)]
        (is (= 1 status))
        (is (str/includes? err "bad.yaml:3: an entry must be a map") err)
        (is (not (.exists (io/file dir "doc.html"))))))
    (testing "--libraries has no effect on --target json/hdoc for a file input"
      (let [libs (library-dir! library-books)
            {:keys [status err]} (main-run "--libraries" (yaml-path libs) "--target" "json" doc)]
        (is (= 0 status))
        (is (str/includes? err "warning: --libraries has no effect on --target json") err)))))

(deftest list-books-test
  (let [libs (library-dir! (assoc library-books
                                  "other/apostles.hdoc" "---\ntitle: Twin\n---\n")
                           "- name: broken\n  type: nope\n")
        yaml (yaml-path libs)]
    (testing "AC #2: canonical ID and plain-text title per book, tab-separated and
              sorted by ID, on stdout; the directory's own load problems and the
              library's scan problems on stderr, the listing unaffected"
      (let [{:keys [status out err]} (main-run "--libraries" yaml "--list-books" "lib")]
        (is (= 0 status) err)
        (is (= (str "lib:apostles\tApostles' Creed\n"
                    "lib:confessions/heidelberg\tHeidelberg Catechism\n"
                    "lib:creeds/nicene\tNicene Creed\n"
                    "lib:other/apostles\tTwin\n")
               out))
        (is (str/includes? err "libraries.yaml:4: library \"broken\" has type \"nope\"") err)
        (is (str/includes? err "more than one book with slug \"apostles\"") err)))
    (testing "AC #2: an unknown library is an error naming the libraries there are"
      (let [{:keys [status out err]} (main-run "--libraries" yaml "--list-books" "zzz")]
        (is (= 1 status))
        (is (= "" out))
        (is (str/includes? err "error: --list-books zzz: the library directory has no library \"zzz\" (it has: lib)") err)))
    (testing "a library that failed to load is an error too, naming why and where"
      (let [{:keys [status err]} (main-run "--libraries" yaml "--list-books" "broken")]
        (is (= 1 status))
        (is (str/includes? err "error: --list-books broken: library \"broken\" is unavailable: it failed to load (")
            err)
        (is (str/includes? err "libraries.yaml:4: ") err)))
    (testing "no library directory at all is an error that says how to give one"
      (let [{:keys [status err]} (main-run "--list-books" "lib")]
        (is (= 1 status))
        (is (str/includes? err "no library directory was found (pass --libraries") err)))
    (testing "a build-only flag given with --list-books is warned about (TASK-108's
              policy), the listing unaffected"
      (let [{:keys [status out err]} (main-run "--libraries" yaml "--list-books" "lib"
                                               "--target" "latex" "-o" "x.tex" "--fragment")]
        (is (= 0 status) err)
        (is (str/includes? out "lib:apostles\tApostles' Creed\n"))
        (doseq [flag ["--target" "--output" "--fragment"]]
          (is (str/includes? err (str "warning: " flag " has no effect with --list-books")) err))))
    (testing "an input given with --list-books is a usage error"
      (let [{:keys [status err]} (main-run "--libraries" yaml "--list-books" "lib" "doc.hdoc")]
        (is (= 2 status))
        (is (str/includes? err "--list-books takes no input") err)))))

(deftest book-id-input-test
  (let [libs (library-dir! library-books "- name: broken\n  type: nope\n")
        yaml (yaml-path libs)
        out (fn [n] (str (io/file libs n)))]
    (testing "AC #3: a book ID compiles that book -- its !include, bibliography
              and positions are the book file's -- and resolves its own book
              references; the directory's load problems are printed once"
      (let [{:keys [status err]} (main-run "--libraries" yaml "lib:heidelberg" "-o" (out "h.html"))
            html (slurp (out "h.html"))
            broken-lines (filter #(str/includes? % "\"broken\"") (str/split-lines err))]
        (is (= 0 status) err)
        (is (str/includes? html "Only comfort."))
        (is (str/includes? html "Nicene Creed, Section 1") "its own link resolved")
        (is (= 1 (count broken-lines)) err)))
    (testing "a stale shelf in the book ID still finds the book, with a note"
      (let [{:keys [status err]} (main-run "--libraries" yaml "lib:old/heidelberg" "-o" (out "s.html"))]
        (is (= 0 status) err)
        (is (str/includes? err (str "book ID \"lib:old/heidelberg\": the book is now at"
                                    " lib:confessions/heidelberg"))
            err)))
    (testing "the book is :self, so a quote back into itself is a cycle"
      (spit (io/file libs "lib" "selfish.hdoc") "# Self {#sec:s}\n\n!quote lib:selfish#sec:s\n")
      (let [{:keys [status err]} (main-run "--libraries" yaml "lib:selfish" "-o" (out "self.html"))]
        (is (= 0 status) err)
        (is (str/includes? err "quote cycle") err)))
    (testing "AC #3: a book ID that cannot be found is a hard error, nothing written"
      (doseq [[args expected]
              [[["lib:nowhere"] "input \"lib:nowhere\" (no such file, so read as a book ID): library \"lib\" has no book \"nowhere\""]
               [["zzz:x"] "the library directory has no library \"zzz\" (it has: lib)"]
               [["broken:x"] "library \"broken\" is unavailable: it failed to load ("]
               [["lib:heidelberg#sec:q1"] "a book ID input names a whole book, not a passage; drop #sec:q1"]]]
        (let [{:keys [status err]} (apply main-run "--libraries" yaml (concat args ["-o" (out "x.html")]))]
          (is (= 1 status) (pr-str args))
          (is (str/includes? err expected) err)
          (is (not (.exists (io/file (out "x.html"))))))))
    (testing "no library directory at all, for a book ID, is a hard error"
      (let [{:keys [status err]} (main-run "lib:heidelberg" "-o" (out "x.html"))]
        (is (= 1 status))
        (is (str/includes? err "no library directory was found (pass --libraries") err)))
    (testing "a book-ID build that stops on an error of its own (a bibliography its
              book names but lacks) still prints the directory's load problems first"
      (spit (io/file libs "lib" "cited.hdoc") "---\ntitle: Cited\nbibliography: missing.json\n---\n\nText.\n")
      (let [{:keys [status err]} (main-run "--libraries" yaml "lib:cited" "-o" (out "c.html"))
            lines (str/split-lines err)]
        (is (= 1 status))
        (is (str/includes? (first lines) "warning: ") err)
        (is (str/includes? (first lines) "library \"broken\" has type \"nope\"") err)
        (is (str/includes? (second lines) "error: bibliography file not found") err)))
    (testing "--in-place is refused with a book ID: it would rewrite a library's book"
      (let [{:keys [status err]} (main-run "--libraries" yaml "--in-place" "lib:heidelberg")]
        (is (= 2 status))
        (is (str/includes? err "--in-place needs a file input, not a book ID") err)))))

(deftest book-id-input-writes-into-working-directory-test
  (testing "AC #3: with no --output, a book ID writes <slug>.<ext> in the current
            directory -- and an existing file of the same name is a file, not a
            book. Run in a separate JVM, since a JVM cannot change its own
            working directory; that one is a temp directory"
    (let [libs (library-dir! library-books)
          cwd (.toFile (Files/createTempDirectory "haselnuss-cli-cwd" (make-array FileAttribute 0)))
          _ (spit (io/file cwd "lib:apostles") "# A file, not the book\n")
          _ (spit (io/file cwd "nicene.hdoc") "# The user's own file\n")
          yaml (yaml-path libs)
          runs [["--libraries" yaml "lib:heidelberg" "--target" "latex"]
                ["lib:apostles"]
                ;; TASK-157 review: the default output exists already --
                ;; the user's own file, or an earlier build -- so it is
                ;; refused on every target, until --output names it.
                ["--libraries" yaml "lib:nicene" "--target" "hdoc"]
                ["--libraries" yaml "lib:heidelberg" "--target" "latex"]
                ["--libraries" yaml "lib:heidelberg" "--target" "latex" "--output" "heidelberg.tex"]]
          form (pr-str
                (list* 'do
                       '(require 'haselnuss.cli)
                       (for [args runs]
                         (list 'println (list 'haselnuss.cli/main* args no-libraries)))))
          {:keys [exit out err]} (shell/sh "java" "-cp" (System/getProperty "java.class.path")
                                           "clojure.main" "-e" form
                                           :dir (str cwd))]
      (is (= 0 exit) err)
      (is (= "0\n0\n1\n1\n0\n" out) err)
      (is (str/includes? (slurp (io/file cwd "heidelberg.tex")) "Only comfort."))
      (is (str/includes? err "haselnuss: wrote heidelberg.tex") err)
      (is (str/includes? (slurp (io/file cwd "lib:apostles.html")) "A file, not the book"))
      (is (= "# The user's own file\n" (slurp (io/file cwd "nicene.hdoc")))
          "a book ID never overwrites an existing file by default")
      (is (str/includes? err (str "haselnuss: error: refusing to overwrite nicene.hdoc, which already"
                                  " exists, with book lib:nicene -- pass --output nicene.hdoc"))
          err)
      (is (str/includes? err "refusing to overwrite heidelberg.tex, which already exists") err)
      (is (= #{"heidelberg.tex" "lib:apostles" "lib:apostles.html" "nicene.hdoc"}
             (set (.list cwd)))
          "nothing written beside the book inside the library"))))

(deftest library-diagnostics-test
  (testing "AC #4: every book-reference diagnostic printed names the reference as
            written and file:line, plus the canonical ID once the book is known;
            the directory's load problems (level :error) print once and fail
            nothing -- the output is written and the status is 0 (doc-10: no
            level stops a build)"
    (let [libs (library-dir! library-books "- name: broken\n  type: nope\n")
          doc (io/file libs "doc.hdoc")
          _ (spit doc (str "# Host\n\n"
                           "See @{lib:nicene#sec:nope}, @{lib:nowhere}, @{zzz:x} and @{broken:x}.\n\n"
                           "Stale @{lib:old/nicene}.\n\n"
                           "!quote lib:nicene#sec:gone\n\n"
                           "!quote broken:y\n"))
          {:keys [status err]} (main-run "--libraries" (yaml-path libs) (str doc))
          lines (filter #(re-find #"^haselnuss: (warning|info): " %) (str/split-lines err))
          ref-lines (filter #(str/includes? % "book reference ") lines)
          line-of (fn [written] (filter #(str/includes? % (str "book reference " (pr-str written))) ref-lines))]
      (is (= 0 status) err)
      (is (.exists (io/file libs "doc.html")))
      (is (= 1 (count (filter #(str/includes? % "library \"broken\" has type \"nope\"")
                              (remove #(str/includes? % "book reference ") lines))))
          err)
      (is (= 7 (count ref-lines)) err)
      ;; TASK-247: the stale shelf path is an info, printed as one
      (doseq [l ref-lines]
        (is (str/starts-with? l (str "haselnuss: " (if (str/includes? l "\"lib:old/nicene\"") "info" "warning")
                                     ": " doc ":"))
            l))
      (is (str/includes? (first (line-of "lib:nicene#sec:nope")) (str doc ":3: ")))
      (is (str/includes? (first (line-of "lib:nicene#sec:nope")) "lib:creeds/nicene"))
      (is (str/includes? (first (line-of "lib:nicene#sec:gone")) (str doc ":7: ")))
      (is (str/includes? (first (line-of "lib:nicene#sec:gone")) "lib:creeds/nicene"))
      (is (str/includes? (first (line-of "lib:old/nicene")) "lib:creeds/nicene"))
      (is (str/includes? (first (line-of "lib:nowhere")) "library \"lib\" has no book \"nowhere\""))
      (is (str/includes? (first (line-of "zzz:x")) "no library \"zzz\""))
      (is (str/includes? (first (line-of "broken:y")) (str doc ":9: ")))
      (is (str/includes? (first (line-of "broken:y")) "libraries.yaml:4: ")))))

(deftest book-urls-are-sanitized-test
  (testing "TASK-156 review: library resolution runs before the sanitize pass, so a
            URL template that fills in a javascript: URL never reaches the output"
    (let [libs (library-dir! library-books)
          _ (spit (io/file libs "libraries.yaml")
                  "- name: lib\n  type: filesystem\n  path: lib\n  url: \"javascript:alert('{slug}')\"\n")
          doc (io/file libs "doc.hdoc")
          _ (spit doc "See @{lib:nicene}.\n\n!quote lib:nicene#sec:creed\n")
          {:keys [status err]} (main-run "--libraries" (yaml-path libs) (str doc))
          html (slurp (io/file libs "doc.html"))]
      (is (= 0 status) err)
      (is (str/includes? html "We believe."))
      (is (not (str/includes? (str/lower-case html) "javascript:")) html)
      (testing "and the sanitize pass is what dropped them, so it ran after resolution"
        (is (str/includes? err "Dropped the book link to \"javascript:") err)
        (is (str/includes? err "Dropped the book quote's source link to \"javascript:") err)))))

(deftest library-options-in-help-test
  (testing "AC #5: --help lists the new options and the BOOK-ID input"
    (let [{:keys [status out]} (main-run "--help")]
      (is (= 0 status))
      (is (str/includes? out "--libraries PATH"))
      (is (str/includes? out "--list-books LIBRARY"))
      (is (str/includes? out "haselnuss [options] BOOK-ID")))))

;; --- TASK-211: a sandboxed result cannot hang the build ----------------------

(def ^:private looping-to-string
  "A reify whose `toString` answers once and loops forever after: printing
  it twice, as the old calling-thread size check and then an emitter's
  error message did, hangs."
  "(let [n (atom 0)] (reify Object (toString [_] (if (< (swap! n inc) 2) \"ok\" (loop [] (recur))))))")

(def ^:private looping-deref
  "A reify whose `deref` -- which printing it calls -- never returns."
  "(reify clojure.lang.IDeref (deref [_] (loop [] (recur))))")

(def ^:private endless-lazy "(map identity (range))")

(defn- bounded-main-run
  "`main-run`, given at most 60 s: `::hung` if it has not returned by then,
  so a regression fails the test instead of hanging the suite."
  [& args]
  (let [f (future (apply main-run args))
        result (deref f 60000 ::hung)]
    (when (= ::hung result) (future-cancel f))
    result))

(deftest sandboxed-result-cannot-hang-the-build-test
  (testing "an --extensions file whose last form, or whose renderer's
            answer, holds an endless lazy value or a reify that loops when
            printed or dereferenced fails the build with an error naming
            the file, on the sandbox worker, rather than hanging it"
    (doseq [[label source fragment]
            [["renderer: endless lazy" (str "{:name \"x\" :emit {:html (fn [d c] [:div " endless-lazy "])}}")
              "-character cap"]
             ["renderer: stateful toString" (str "{:name \"x\" :emit {:html (fn [d c] [:div " looping-to-string "])}}")
              "not plain data"]
             ["renderer: IDeref" (str "{:name \"x\" :emit {:html (fn [d c] [:div " looping-deref "])}}")
              "not plain data"]
             ["last form: stateful toString" looping-to-string "not plain data"]
             ["last form: IDeref renderer" (str "{:name \"x\" :emit {:html " looping-deref "}}") "not plain data"]
             ["last form: endless lazy :kind" (str "{:name \"x\" :emit {:html (fn [d c] \"ok\")} :kind {:k " endless-lazy "}}")
              "-character cap"]]]
      (let [dir (fixture-dir! ":::{x}\nbody\n:::\n")
            ext (write-file! dir "bad-ext.clj" source)
            start (System/nanoTime)
            result (bounded-main-run "--extensions" ext (str (io/file dir "doc.hdoc")))
            elapsed-ms (/ (- (System/nanoTime) start) 1e6)]
        (is (not= ::hung result) label)
        (when (map? result)
          (is (= 1 (:status result)) label)
          (is (str/includes? (:err result) "bad-ext.clj") (str label ": " (:err result)))
          (is (str/includes? (:err result) fragment) (str label ": " (:err result)))
          (is (< elapsed-ms 20000) (str label " took " elapsed-ms "ms"))))))
  (testing "the same for a --transform file, at load and at call time"
    (doseq [[label source fragment]
            [["transform: endless lazy" (str "{:select :para :transform (fn [n c] (assoc n :x " endless-lazy "))}")
              "-character cap"]
             ["transform: stateful toString"
              (str "{:select :para :transform (fn [n c] (assoc n :x " looping-to-string "))}") "not plain data"]
             ["transform: IDeref" (str "{:select :para :transform (fn [n c] (assoc n :x " looping-deref "))}")
              "not plain data"]
             ["last form: stateful toString :scope"
              (str "{:select :para :transform (fn [n c] n) :scope " looping-to-string "}") "not plain data"]
             ["last form: endless lazy" endless-lazy "-character cap"]]]
      (let [dir (fixture-dir! "Some text.\n")
            rule (write-file! dir "bad-rule.clj" source)
            start (System/nanoTime)
            result (bounded-main-run "--transform" rule (str (io/file dir "doc.hdoc")))
            elapsed-ms (/ (- (System/nanoTime) start) 1e6)]
        (is (not= ::hung result) label)
        (when (map? result)
          (is (= 1 (:status result)) label)
          (is (str/includes? (:err result) "bad-rule.clj") (str label ": " (:err result)))
          (is (str/includes? (:err result) fragment) (str label ": " (:err result)))
          (is (< elapsed-ms 20000) (str label " took " elapsed-ms "ms")))))))

;; --- TASK-214: a rule's :select runs in the sandbox --------------------------

(deftest sandboxed-select-cannot-hang-the-build-test
  (testing "a --transform rule whose :select loops, throws with a looping
            reify in its ex-data, or backtracks catastrophically fails the
            build with an error naming the rule and its file, within the
            timeout, rather than hanging it or printing the script's data"
    (doseq [[label select fragment]
            [["looping fn" "(fn [n] (loop [] (recur)))" "did not finish within"]
             ["throwing fn, looping reify in ex-data"
              (str "(fn [n] (throw (ex-info \"select boom\" {:text " looping-to-string
                   " :name " looping-deref "})))")
              "select boom"]
             ["catastrophic regex" "{:text #\"(.*a){12}b\"}" "did not finish within"]]]
      (let [dir (fixture-dir! (str (apply str (repeat 50 "a")) "\n"))
            rule (write-file! dir "bad-select.clj"
                              (str "{:name \"picky\" :select " select " :transform (fn [n c] n)}"))
            start (System/nanoTime)
            result (bounded-main-run "--transform" rule (str (io/file dir "doc.hdoc")))
            elapsed-ms (/ (- (System/nanoTime) start) 1e6)]
        (is (not= ::hung result) label)
        (when (map? result)
          (is (= 1 (:status result)) label)
          (is (str/includes? (:err result) "picky") (str label ": " (:err result)))
          (is (str/includes? (:err result) "bad-select.clj)'s :select") (str label ": " (:err result)))
          (is (str/includes? (:err result) fragment) (str label ": " (:err result)))
          (is (< elapsed-ms 20000) (str label " took " elapsed-ms "ms")))))))

(deftest untrusted-document-test
  (testing "TASK-219: --untrusted refuses document math outside the allow-list, naming
            file:line and the token, and writes nothing"
    (let [dir (fixture-dir! "Intro.\n\nSee\n$\\UseName{input}{secret}$ here.\n")
          input (str (io/file dir "doc.hdoc"))
          {:keys [status err]} (main-run "--target" "latex" "--untrusted" input)]
      (is (= 1 status))
      (is (str/includes? err (str "haselnuss: error: " input ":4: document math uses TeX \\UseName"))
          err)
      (is (str/includes? err "token \"\\\\UseName\"") err)
      (is (not (.exists (io/file dir "doc.tex"))))
      (testing "and without it the same math reaches the LaTeX output as written"
        (let [{:keys [status]} (main-run "--target" "latex" input)]
          (is (= 0 status))
          (is (str/includes? (slurp (io/file dir "doc.tex")) "\\UseName{input}{secret}"))))))
  (testing "an ordinary document builds byte-identically with and without it"
    (let [dir (fixture-dir!)
          plain (:output (build! dir {:target "latex" :output (str (io/file dir "a.tex"))}))
          strict (:output (build! dir {:target "latex" :untrusted true
                                       :output (str (io/file dir "b.tex"))}))]
      (is (= (str/replace plain "\\bibliography{a}" "") (str/replace strict "\\bibliography{b}" "")))))
  (testing "it has no effect off latex, and says so"
    (let [dir (fixture-dir! "A $\\input{x}$.\n")
          input (str (io/file dir "doc.hdoc"))]
      (doseq [target ["html" "json" "hdoc"]]
        (let [{:keys [status err]} (main-run "--target" target "--untrusted"
                                             "-o" (str (io/file dir (str "out." target))) input)]
          (is (= 0 status) target)
          (is (str/includes? err (str "warning: --untrusted has no effect on --target " target))
              err)))))
  (testing "--help describes it"
    (is (str/includes? (:out (main-run "--help")) "--untrusted"))))

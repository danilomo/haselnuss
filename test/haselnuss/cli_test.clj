(ns haselnuss.cli-test
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
            [haselnuss.cli :as cli])
  (:import (java.awt.image BufferedImage)
           (java.io StringReader)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
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

(defn- build!
  "`cli/build` over `dir`'s own `doc.hdoc` with `opts`, returning
  `{:output-path :diagnostics :output}` -- the last being the text
  actually written to disk, so assertions read the file rather than
  trusting the return value."
  [dir opts]
  (let [result (cli/build (merge {:input (str (io/file dir "doc.hdoc")) :target "html"} opts))]
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
        (is (str/includes? output "<blockquote id=\"thm:main\" class=\"theorem\">"))
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
      (is (empty? diagnostics)))))

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
  (testing "AC #5: a missing input file is a usage error"
    (let [e (is (thrown? clojure.lang.ExceptionInfo
                         (cli/build {:input "/nonexistent/nope.hdoc" :target "html"})))]
      (is (= :haselnuss.cli/usage-error (:type (ex-data e))))))
  (testing "a document naming a bibliography that is not there fails by
            name, rather than silently dangling every citation"
    (let [dir (fixture-dir!)]
      (io/delete-file (io/file dir "refs.json"))
      (let [e (is (thrown? clojure.lang.ExceptionInfo (build! dir {})))]
        (is (= :haselnuss.cli/missing-bibliography (:type (ex-data e))))))))

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
        (is (str/includes? html "<blockquote class=\"admonition\">"))
        (is (str/includes? html "<blockquote class=\"proof\">")))
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
        status (binding [*err* err *out* out] (cli/main* args))]
    {:status status :out (str out) :err (str err)}))

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
      (is (str/includes? out "haselnuss: wrote"))
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
      (is (str/includes? (:err bad) "Usage: haselnuss")))
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
      (is (= 2 status))
      (is (str/includes? err "input file not found: --version")))))

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
                   "--no-stylesheet=false"]]
        (let [{:keys [status err]} (main-run arg input)]
          (is (= 2 status) arg)
          (is (str/includes? err "takes no value") arg))))
    (testing "run/build are public, so they validate the target
              themselves rather than dying in a case clause several
              steps later"
      (let [e (is (thrown? clojure.lang.ExceptionInfo (cli/run "text\n" {:target "pdf"})))]
        (is (= :haselnuss.cli/usage-error (:type (ex-data e))))))))

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
      (is (= "" err) "no dangling-reference or dangling-citation warning")
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
      (is (= "" err))
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
        (is (= "" err) "a document whose includes all resolve produces no diagnostics")
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
      (is (str/includes? err "haselnuss: warning: cannot include \"nowhere/missing.hdoc\""))
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
        (is (str/includes? err "haselnuss: warning: include cycle"))
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
  (testing "an included file that itself fails to parse is a warning, not
            a build abort -- the same failure mode as a missing one"
    (let [dir (fixture-dir! "# Intro {#sec:intro}\n\nKept.\n\n!include broken.hdoc\n")]
      (spit (io/file dir "broken.hdoc") ":::{!!!}\nx\n:::\n")
      (let [{:keys [status err]} (main-run (str (io/file dir "doc.hdoc")))]
        (is (= 0 status) err)
        (is (str/includes? err "cannot include \"broken.hdoc\""))
        (is (str/includes? (slurp (io/file dir "doc.html")) "Kept.")))))
  (testing "a directory named as a src is unreadable like any other bad
            path, not an unhandled exception"
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
      (is (= "" err) "no dangling cross-reference for any of them")
      (is (not (str/includes? html "??")))
      (doseq [[kind title] titles]
        (is (str/includes? html (str "<a href=\"#" kind ":x\">" title " 1</a>"))
            (str kind ": the reference must print the number"))
        (is (str/includes? html (str "<blockquote id=\"" kind ":x\" class=\""))
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
        (is (= "" err) "nothing to warn about: the reference has an anchor")
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
        (is (= "" err) err)
        (is (str/includes? html "id=\"thm:x\""))
        (is (str/includes? html "<a href=\"#thm:x\">Theorem 1</a>"))))
    (testing "a mapped directive is silent in native mode, since its own
              environment steps a counter and carries the label"
      (let [dir (fixture-dir! (str "# Body {#sec:body}\n\nSee @thm:x.\n\n"
                                   ":::{theorem #thm:x}\nBody.\n:::\n"))
            {:keys [status err]} (main-run "--target" "latex" (str (io/file dir "doc.hdoc")))]
        (is (= 0 status) err)
        (is (= "" err) err)))))

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
      (is (= "" err) err))))

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
                    "---\n")
        dir (fixture-dir! source)
        {:keys [status err]} (main-run (str (io/file dir "doc.hdoc")))
        html (slurp (io/file dir "doc.html"))
        style (second (re-find #"(?s)<style>(.*?)</style>" html))
        body (second (re-find #"(?s)<body>(.*)</body>" html))]
    (is (= 0 status) err)
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
      (is (re-find #"blockquote\.admonition\{" style) "admonition distinct from body text")
      (is (re-find #"blockquote\.theorem" style) "and so are the theorem-likes")
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
          (let [{:keys [status out]} (apply main-run (concat args [(str (io/file dir "doc.hdoc"))]))]
            (is (= 0 status))
            (is (not (.isFile (io/file dir "doc.bib"))) (pr-str args))
            (is (= 1 (count (re-seq #"haselnuss: wrote" out)))
                (str (pr-str args) ": one input, one output"))))))))

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
          {:keys [status out]} (main-run "--target" "latex" "--fragment" input)
          tex (slurp (io/file dir "doc.tex"))
          preamble (slurp (io/file dir "doc-preamble.tex"))]
      (is (= 0 status) out)
      (is (not (str/includes? tex "\\documentclass")))
      (is (not (str/includes? tex "\\begin{document}")))
      (is (not (str/includes? tex "\\usepackage")))
      (is (str/includes? tex "\\section{"))
      (is (str/includes? preamble "\\usepackage{hyperref}"))
      (is (str/includes? out "doc-preamble.tex"))
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

(ns haselnuss.example-test
  "The v1 dogfood document (TASK-29), kept building.

  `examples/hazelnuts.hdoc` is a real short document -- prose about
  growing hazelnuts, not a fixture assembled to tick constructs -- that
  exercises sections, a figure, a table, display and inline math, two
  citations, a cross-reference to each numbered thing, and two custom
  directives (one LaTeX-native, one HTML-native). It is the acceptance
  test for v1: writing once and getting usable output for both a live
  HTML reading experience and print via LaTeX.

  This namespace is the regression guard around it. AC #2 (\"opens
  correctly in a browser\") and AC #4 (\"reviewed side by side\") were
  verified by hand -- the HTML really was loaded in Chrome, with MathJax
  rendering, the image loading, the cross-reference links working, the
  collapsable toggling, and no console errors; the PDF really was
  rendered and read against it. What a test can keep honest afterwards
  is that the document still converts, still compiles, and still agrees
  with itself across the two targets, which is what this checks."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is testing]]
            [haselnuss.cli :as cli])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- build!
  "Copies the committed example (and the assets it names) into a fresh
  temp directory, converts it to both targets, and returns
  `{:dir :html :latex :diagnostics}`. LaTeX in computed-numbers mode,
  the mode that matches HTML exactly (decision D4, TASK-27)."
  []
  (let [dir (str (Files/createTempDirectory "haselnuss-example" (make-array FileAttribute 0)))
        input (str (io/file dir "hazelnuts.hdoc"))]
    (doseq [f ["hazelnuts.hdoc" "hazelnuts.json"]]
      (io/copy (io/reader (io/resource f)) (io/file dir f)))
    (io/copy (io/input-stream (io/resource "catkins.png")) (io/file dir "catkins.png"))
    (let [html (cli/build {:input input :target "html"})
          latex (cli/build {:input input :target "latex" :computed-numbers true})]
      {:dir dir
       :html (slurp (:output-path html))
       :latex (slurp (:output-path latex))
       :diagnostics (into (vec (:diagnostics html)) (:diagnostics latex))})))

(deftest example-converts-cleanly-test
  (let [{:keys [dir html latex diagnostics]} (build!)]
    (testing "AC #1: the committed example converts to both targets with
              no diagnostics at all -- a document shipped as the
              project's own example should not be teaching an author to
              ignore warnings"
      (is (= [] diagnostics) (pr-str diagnostics)))

    (testing "AC #1: and it really does exercise the constructs the task
              names -- sections, a figure, a citation and a custom
              directive -- so the guard cannot pass on a document that
              lost one"
      (is (re-find #"<section id=\"sec:why\">" html))
      (is (re-find #"<figure id=\"fig:catkins\">" html))
      (is (re-find #"<section id=\"sec:bibliography\"" html))
      (is (re-find #"<details class=\"collapsable\">" html) "the HTML-native directive")
      ;; The example is emitted with --computed-numbers, where a mapped
      ;; environment that steps a counter is swapped for its own
      ;; uncounted form carrying the resolver's head -- still a quote,
      ;; so the admonition looks the same, but numbered and anchored.
      ;; Before TASK-40 it was a bare \begin{hnadmonition} with no
      ;; number and no anchor, and the document could not reference it.
      (is (re-find #"\\begin\{hnadmonitionstar\}\{Note 1\}\\phantomsection\\label\{adm:pollination\}" latex)
          "the LaTeX-native directive, numbered and anchored")
      (is (re-find #"<blockquote id=\"adm:pollination\" class=\"admonition\">" html)
          "and anchored in HTML too")
      (is (re-find #"<a href=\"#adm:pollination\">Note 1</a>" html)
          "TASK-40: the document can reference its own admonition again")
      ;; Named explicitly, because the count comparison above cannot
      ;; notice a construct that vanished from BOTH outputs.
      (is (re-find #"<table" html) "the table survived")
      (is (re-find #"\\begin\{longtable\}" latex) "the table survived to latex")
      (is (re-find #"class=\"math display\"" html) "the display equation survived")
      (is (re-find #"\\begin\{equation\}" latex) "the display equation survived to latex")
      (is (re-find #"class=\"math inline\"" html) "the inline math survived"))

    (testing "AC #3: the emitted .tex compiles to a PDF with a standard
              LaTeX toolchain"
      (spit (io/file dir "hazelnuts.tex") latex)
      (dotimes [_ 2]
        (shell/sh "pdflatex" "-interaction=nonstopmode" "-halt-on-error" "hazelnuts.tex" :dir dir))
      (let [{:keys [exit out]} (shell/sh "pdflatex" "-interaction=nonstopmode" "-halt-on-error"
                                         "hazelnuts.tex" :dir dir)]
        (is (zero? exit) (str "the example must compile, pdflatex output:\n" out))
        (is (.isFile (io/file dir "hazelnuts.pdf")))

        (testing "AC #4: and the rendered PDF agrees with the HTML on
                  every number, citation and section -- compared on the
                  PDF's own text, since that is what a reader sees, and
                  by occurrence count so a number appearing in only one
                  output cannot slip through"
          (let [pdf (:out (shell/sh "pdftotext" (str (io/file dir "hazelnuts.pdf")) "-"))
                occurrences (fn [text item]
                              (count (re-seq (re-pattern (java.util.regex.Pattern/quote item)) text)))]
            (doseq [item ["Figure 1.1" "Table 2.1" "Eq. (2.1)" "Section 1" "Section 2"
                          ;; TASK-40: the admonition's own number, which
                          ;; both the head and the reference to it print
                          "Note 1"
                          "[1]" "[1; 2]" "Crowe" "Schmidt"
                          "Why hazel" "Spacing and yield" "Conclusion" "Bibliography"]]
              (let [h (occurrences html item)]
                ;; Positive, not merely equal: `(= 0 0)` is true, so an
                ;; equality-only check passed on a document with the
                ;; equation, the table and the multi-key citation
                ;; deleted -- demonstrated by review, not theorised.
                (is (pos? h) (str item " must actually appear in the HTML"))
                (is (= h (occurrences pdf item))
                    (str item " must appear the same number of times in both outputs"))))))))))

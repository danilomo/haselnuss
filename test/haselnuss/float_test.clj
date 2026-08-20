(ns haselnuss.float-test
  "Fixtures for the captioned, numbered floats a directive can wrap: a
  code listing (TASK-57), an algorithm (TASK-58), and a figure built
  from subfigure panels (TASK-56).

  One namespace for all three because they are one mechanism -- the same
  `:float` entry shape in the LaTeX directive-environment table, the
  same `float-lower-rule` degradation to a Figure for every other
  target, and the same lexicon shape -- so a test that passes for one
  and fails for another is telling you the mechanism leaked.

  Driven through `haselnuss.cli/build`, the entry point a user actually
  has, and compiled with a real `pdflatex`: a listing that emits but
  does not typeset under a numbered float is not a listing."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [haselnuss.cli :as cli])
  (:import (java.awt.image BufferedImage)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (javax.imageio ImageIO)))

(defn- fixture-dir!
  [source]
  (let [dir (str (Files/createTempDirectory "haselnuss-float" (make-array FileAttribute 0)))]
    (spit (io/file dir "doc.hdoc") source)
    ;; A real 1x1 PNG beside the document, so a fixture holding images
    ;; compiles for real rather than depending on which optional TeX
    ;; Live example images happen to be installed (the same reason
    ;; `haselnuss.emit.latex-test` generates its own).
    (ImageIO/write (BufferedImage. 1 1 BufferedImage/TYPE_INT_RGB) "png"
                   (io/file dir "pic.png"))
    dir))

(defn- build!
  [dir opts]
  (let [result (cli/build (merge {:input (str (io/file dir "doc.hdoc")) :target "html"} opts))]
    (assoc result :output (slurp (:output-path result)))))

(defn- pdf-text
  "The text of `tex` compiled twice in `dir` (twice so \\Cref resolves),
  whitespace-normalized, or nil if it did not compile."
  [dir tex]
  (spit (io/file dir "host.tex") tex)
  (shell/sh "pdflatex" "-interaction=nonstopmode" "host.tex" :dir (str dir))
  (let [result (shell/sh "pdflatex" "-interaction=nonstopmode" "host.tex" :dir (str dir))]
    (when (zero? (:exit result))
      (str/replace (:out (shell/sh "pdftotext" (str (io/file dir "host.pdf")) "-"))
                   #"\s+" " "))))

(def ^:private listing-document
  (str "# Intro {#sec:i}\n\n"
       ":::{listing #lst:dining caption=\"The dining philosophers\"}\n"
       "```clojure\n(defn dine [p] (eat p))\n```\n"
       ":::\n\n"
       ":::{listing #lst:binding caption=\"Late binding\"}\n"
       "```java\nObject o = get();\n```\n"
       ":::\n\n"
       "See @lst:dining and @lst:binding.\n"))

(deftest listing-is-captioned-numbered-and-referenceable-test
  (testing "AC #1/#2: a code block wrapped in a listing directive carries
            a caption and an id, and renders as a captioned numbered
            block in both targets, with @lst:dining printing Listing 1"
    (let [dir (fixture-dir! listing-document)
          html (:output (build! dir {}))
          tex (:output (build! dir {:target "latex"}))]
      (is (str/includes? html "Listing 1: The dining philosophers"))
      (is (str/includes? html "<a href=\"#lst:dining\">Listing 1</a>"))
      (is (str/includes? html "<a href=\"#lst:binding\">Listing 2</a>"))
      (is (str/includes? tex "\\begin{hnlisting}"))
      (is (str/includes? tex "\\caption{The dining philosophers}"))
      (is (str/includes? tex "\\label{lst:dining}"))
      (is (str/includes? tex "\\Cref{lst:dining}"))
      (testing "and native-mode LaTeX prints the same numbers, computed
                from its own float counter and knowing nothing about the
                resolver -- the only check that can catch the two
                disagreeing"
        (let [text (pdf-text dir tex)]
          (is (some? text) "the document must compile")
          (is (str/includes? text "Listing 1: The dining philosophers") text)
          (is (str/includes? text "Listing 2: Late binding") text)
          (is (str/includes? text "See Listing 1 and Listing 2.") text))))))

(deftest listing-pt-br-test
  (testing "AC #2: and under lang pt-BR the reference prints Listagem"
    (let [dir (fixture-dir! (str "---\nlang: pt-BR\n---\n\n" listing-document))
          html (:output (build! dir {}))]
      (is (str/includes? html "<a href=\"#lst:dining\">Listagem 1</a>")))))

(deftest listing-language-survives-test
  (testing "AC #3: the fence language reaches BOTH outputs. HTML has had
            the language-x class convention all along; the LaTeX emitter
            used to discard :lang entirely, so a document said which
            language its code was in and the .tex did not"
    (let [dir (fixture-dir! listing-document)
          html (:output (build! dir {}))
          tex (:output (build! dir {:target "latex"}))]
      (is (str/includes? html "<code class=\"language-clojure\">"))
      (is (str/includes? html "<code class=\"language-java\">"))
      (is (str/includes? tex "clojure"))
      (is (str/includes? tex "java")))))

(deftest bare-code-block-is-unchanged-test
  (testing "AC #4: a code block with no caption and no id gains no float,
            no caption and no label -- the body is the same bare verbatim
            environment it has always been, trailing newline and all"
    (let [dir (fixture-dir! "# I {#sec:i}\n\n```\nplain text\n```\n")
          tex (:output (build! dir {:target "latex"}))]
      (is (str/includes? tex "\\begin{verbatim}\nplain text\n\n\\end{verbatim}"))
      (is (not (str/includes? tex "\\begin{hnlisting}")))
      (is (not (str/includes? tex "\\caption")))
      (is (not (str/includes? tex "% haselnuss: language"))
          "and a fence with no language declares none")))
  (testing "the language comment is the ONE thing a plain fence gained,
            so a document that has one differs from a document that does
            not by exactly that line"
    (let [with-lang (:output (build! (fixture-dir! "# I {#sec:i}\n\n```clj\nx\n```\n")
                                     {:target "latex"}))
          without (:output (build! (fixture-dir! "# I {#sec:i}\n\n```\nx\n```\n")
                                   {:target "latex"}))]
      (is (= without (str/replace with-lang "% haselnuss: language clj\n" ""))))))

(deftest listing-numbering-agrees-across-modes-test
  (testing "found by review: an UNLABELLED captioned listing used to
            skew native mode. LaTeX's float counter steps for every
            \\caption it sees, while the resolver numbers only id-bearing
            nodes -- so one unlabelled listing before a labelled one left
            the PDF reading 'Listing 2' where HTML and computed mode both
            read 'Listing 1', for the same node in the same document. An
            id-less captioned float now takes the caption package's
            unnumbered \\caption*, which is also exactly what HTML does
            with it: no label entry, so the caption prints with no number"
    (let [source (str "# I {#sec:i}\n\n"
                      ":::{listing caption=\"Unlabelled\"}\n```clj\na\n```\n:::\n\n"
                      ":::{listing #lst:real caption=\"First real\"}\n```clj\nb\n```\n:::\n\n"
                      "See @lst:real.\n")
          dir (fixture-dir! source)
          html (:output (build! dir {}))
          native (:output (build! dir {:target "latex"}))
          computed (:output (build! dir {:target "latex" :computed-numbers true
                                         :output (str (io/file dir "computed.tex"))}))]
      (is (str/includes? native "\\caption*{Unlabelled}")
          "the unlabelled one must not step LaTeX's counter")
      (is (str/includes? native "\\caption{First real}"))
      (is (str/includes? html "<a href=\"#lst:real\">Listing 1</a>"))
      (is (str/includes? computed "\\caption*{Listing 1: First real}")
          "computed mode bakes in the resolver's own number")
      (is (str/includes? computed "\\hyperref[lst:real]{Listing 1}"))
      (testing "and a real pdflatex prints the same number the other two do"
        (let [text (pdf-text dir native)]
          (is (some? text))
          (is (str/includes? text "Listing 1: First real") text)
          (is (str/includes? text "See Listing 1.") text)
          (is (not (str/includes? text "Listing 2")) text))))))

(deftest listing-without-a-caption-test
  (testing "an id-bearing listing with NO caption still anchors: a float
            steps its counter from \\caption alone, so without one the
            \\label would bind to whatever was stepped last and a \\Cref
            to it would confidently print a section number -- the bug
            render-figure records for the identical shape"
    (let [dir (fixture-dir! (str "# I {#sec:i}\n\n"
                                 ":::{listing #lst:bare}\n```clj\nx\n```\n:::\n\n"
                                 "See @lst:bare.\n"))
          native (:output (build! dir {:target "latex"}))]
      (is (str/includes? native "\\refstepcounter{hnlisting}"))
      (is (str/includes? native "\\label{lst:bare}"))
      (is (not (str/includes? native "\\caption")))
      (let [text (pdf-text dir native)]
        (is (some? text))
        (is (str/includes? text "See Listing 1.") text))))
  (testing "and one with neither an id nor a caption is just a float
            around the code -- nothing to number, nothing to anchor.
            Scoped to the float itself: the surrounding document has a
            labelled section, and the preamble declares counters of its
            own, so a whole-file search would pass on either"
    (let [dir (fixture-dir! "# I {#sec:i}\n\n:::{listing}\n```clj\nx\n```\n:::\n")
          native (:output (build! dir {:target "latex"}))
          float-body (second (re-find #"(?s)\\begin\{hnlisting\}(.*?)\\end\{hnlisting\}" native))]
      (is (some? float-body))
      (is (not (str/includes? float-body "\\caption")))
      (is (not (str/includes? float-body "\\label")))
      (is (not (str/includes? float-body "\\refstepcounter"))))))

(deftest listing-caption-is-plain-text-test
  (testing "a caption written as an attribute is plain text in BOTH
            targets, not marked-up prose -- an Attr prop is a string by
            schema, so Inlines cannot live there, and an emitter parsing
            markdown would put the parser's job in the wrong layer. The
            point of asserting it is that the two agree: neither target
            quietly renders the markup the other shows literally"
    (let [dir (fixture-dir! (str "# I {#sec:i}\n\n"
                                 ":::{listing #lst:s caption=\"A *starred* word\"}\n"
                                 "```clj\nx\n```\n:::\n"))
          html (:output (build! dir {}))
          tex (:output (build! dir {:target "latex"}))]
      (is (str/includes? html "Listing 1: A *starred* word"))
      (is (not (str/includes? html "<em>starred</em>")))
      (is (str/includes? tex "\\caption{A *starred* word}")))))

;; ---------------------------------------------------------------------
;; TASK-58: a numbered, captioned algorithm -- the same mechanism.
;; ---------------------------------------------------------------------

(def ^:private algorithm-document
  "Pseudocode in the shape the source thesis writes it: keyword-ish step
  heads, nested braces, an arrow and an index expression. All of it is
  content, not syntax, which is the point of keeping the body verbatim."
  (str "# Intro {#sec:i}\n\n"
       ":::{algorithm #alg:bisec caption=\"Bisection\"}\n"
       "```\nKwData: a list L, a value x\n"
       "For i <- 1 to n {\n  If L[i] > x { return i }\n  Else { x <- x - 1 }\n}\n```\n"
       ":::\n\n"
       ":::{algorithm #alg:alg01 caption=\"The other one\"}\n"
       "```\nWhile true { skip }\n```\n"
       ":::\n\n"
       "See @alg:bisec and @alg:alg01.\n"))

(deftest algorithm-is-a-numbered-captioned-float-test
  (testing "AC #1/#2: a block directive wrapping a fenced code block
            yields a numbered, captioned float in LaTeX and an equivalent
            captioned block in HTML, and @alg:alg01 prints Algorithm 2"
    (let [dir (fixture-dir! algorithm-document)
          html (:output (build! dir {}))
          tex (:output (build! dir {:target "latex"}))]
      (is (str/includes? tex "\\begin{hnalgorithm}"))
      (is (str/includes? tex "\\caption{Bisection}"))
      (is (str/includes? tex "\\label{alg:bisec}"))
      (is (str/includes? html "Algorithm 1: Bisection"))
      (is (str/includes? html "<a href=\"#alg:bisec\">Algorithm 1</a>"))
      (is (str/includes? html "<a href=\"#alg:alg01\">Algorithm 2</a>"))
      (testing "and a real pdflatex prints the same numbers, from its own
                float counter, knowing nothing about the resolver"
        (let [text (pdf-text dir tex)]
          (is (some? text) "the document must compile")
          (is (str/includes? text "Algorithm 1: Bisection") text)
          (is (str/includes? text "Algorithm 2: The other one") text)
          (is (str/includes? text "See Algorithm 1 and Algorithm 2.") text))))))

(deftest algorithm-pt-br-test
  (testing "AC #2: and under lang pt-BR a reference prints Algoritmo --
            the SECOND one as well, which is the number the AC actually
            names and the one a broken counter would get wrong"
    (let [dir (fixture-dir! (str "---\nlang: pt-BR\n---\n\n" algorithm-document))
          html (:output (build! dir {}))]
      (is (str/includes? html "<a href=\"#alg:bisec\">Algoritmo 1</a>"))
      (is (str/includes? html "<a href=\"#alg:alg01\">Algoritmo 2</a>"))
      (is (str/includes? html "Algoritmo 2: The other one")))))

(deftest algorithm-computed-numbers-test
  (testing "the mode the cross-format invariant runs in was untested for
            this float (found by review): computed mode must bake in the
            resolver's own number and anchor it, since LaTeX's counters
            are deliberately bypassed there"
    (let [dir (fixture-dir! (str "---\nlang: pt-BR\n---\n\n" algorithm-document))
          tex (:output (build! dir {:target "latex" :computed-numbers true}))]
      (is (str/includes? tex "\\caption*{Algoritmo 1: Bisection}"))
      (is (str/includes? tex "\\caption*{Algoritmo 2: The other one}"))
      (is (str/includes? tex "\\hyperref[alg:bisec]{Algoritmo 1}"))
      (is (str/includes? tex "\\phantomsection\\label{alg:bisec}"))
      (is (not (str/includes? tex "\\refstepcounter{hnalgorithm}"))
          "and the counter is not stepped, since nothing reads it in this mode"))))

(deftest algorithm-without-a-caption-test
  (testing "the caption-less id-bearing branch had a listing test and no
            algorithm counterpart (found by review). A float steps its
            counter from \\caption alone, so without one the \\label would
            bind to whatever was stepped last"
    (let [dir (fixture-dir! (str "# I {#sec:i}\n\n"
                                 ":::{algorithm #alg:bare}\n```\nx\n```\n:::\n\n"
                                 "See @alg:bare.\n"))
          native (:output (build! dir {:target "latex"}))]
      (is (str/includes? native "\\refstepcounter{hnalgorithm}"))
      (is (str/includes? native "\\label{alg:bare}"))
      (let [text (pdf-text dir native)]
        (is (some? text))
        (is (str/includes? text "See Algorithm 1.") text)))))

(deftest algorithm-body-is-verbatim-in-both-targets-test
  (testing "AC #3: the body is verbatim in both targets, so neither can
            render a step differently from the other. The braces, the
            arrow and the index expression are content -- if either
            target were interpreting them, this is where it would show"
    (let [dir (fixture-dir! algorithm-document)
          html (:output (build! dir {}))
          tex (:output (build! dir {:target "latex"}))]
      (doseq [line ["KwData: a list L, a value x"
                    "For i &lt;- 1 to n {"
                    "If L[i] &gt; x { return i }"]]
        (is (str/includes? html line) line))
      (doseq [line ["KwData: a list L, a value x"
                    "For i <- 1 to n {"
                    "If L[i] > x { return i }"]]
        (is (str/includes? tex line) line))
      (is (str/includes? tex "\\begin{verbatim}")
          "verbatim is catcode-literal, which is why no escaping is needed or wanted"))))

(deftest algorithm-without-an-id-test
  (testing "AC #4: an algorithm with no id renders with its caption but
            is not a numbering target -- and its caption is unnumbered in
            both targets, so LaTeX's own float counter cannot run ahead
            of the resolver's"
    (let [dir (fixture-dir! (str "# I {#sec:i}\n\n"
                                 ":::{algorithm caption=\"Unlabelled\"}\n```\nx\n```\n:::\n\n"
                                 ":::{algorithm #alg:real caption=\"Real\"}\n```\ny\n```\n:::\n\n"
                                 "See @alg:real.\n"))
          html (:output (build! dir {}))
          tex (:output (build! dir {:target "latex"}))]
      (is (str/includes? html "Unlabelled"))
      (is (not (str/includes? html "Algorithm 1: Unlabelled")))
      (is (str/includes? html "<a href=\"#alg:real\">Algorithm 1</a>"))
      (is (str/includes? tex "\\caption*{Unlabelled}"))
      (let [text (pdf-text dir tex)]
        (is (some? text))
        (is (str/includes? text "See Algorithm 1.") text)
        (is (not (str/includes? text "Algorithm 2")) text)))))

(deftest listing-and-algorithm-number-independently-test
  (testing "the two floats are separate sequences, as their separate
            \\newfloat counters and separate lexicon kinds both say --
            interleaving them must not make either count the other"
    (let [dir (fixture-dir! (str "# I {#sec:i}\n\n"
                                 ":::{listing #lst:a caption=\"L1\"}\n```\na\n```\n:::\n\n"
                                 ":::{algorithm #alg:a caption=\"A1\"}\n```\nb\n```\n:::\n\n"
                                 ":::{listing #lst:b caption=\"L2\"}\n```\nc\n```\n:::\n\n"
                                 "See @lst:a, @alg:a and @lst:b.\n"))
          html (:output (build! dir {}))
          tex (:output (build! dir {:target "latex"}))]
      (is (str/includes? html "<a href=\"#lst:a\">Listing 1</a>"))
      (is (str/includes? html "<a href=\"#alg:a\">Algorithm 1</a>"))
      (is (str/includes? html "<a href=\"#lst:b\">Listing 2</a>"))
      (let [text (pdf-text dir tex)]
        (is (some? text))
        (is (str/includes? text "See Listing 1, Algorithm 1 and Listing 2.") text)))))

;; A figure composed of subfigure panels (TASK-56)

(def ^:private panels-document
  ;; The heading carries no id deliberately: a `fig` number is
  ;; section-scoped, `article`'s own `\thefigure` is not, and this
  ;; fixture is about panels rather than about that older question.
  (str "# Sensitivity\n\n"
       "::::{figure #fig:sens caption=\"Sensitivity analysis\" columns=2}\n\n"
       ":::{subfigure #fig:temp caption=\"Temperature\"}\n![](pic.png)\n:::\n\n"
       ":::{subfigure #fig:pressure}\n![](pic.png)\n:::\n\n"
       ":::{subfigure #fig:flow caption=\"Flow\"}\n![](pic.png)\n:::\n\n"
       "::::\n\n"
       "![A tree.](pic.png){#fig:tree}\n\n"
       "See @fig:sens, @fig:pressure and @fig:tree.\n"))

(deftest figure-of-panels-shares-one-number-test
  (testing "AC #1: several images under one figure directive render in
            both targets as ONE numbered, captioned, labelled float --
            and take exactly one figure number between them, so the
            plain figure after them is Figure 2 and not Figure 4"
    (let [dir (fixture-dir! panels-document)
          html (:output (build! dir {}))
          tex (:output (build! dir {:target "latex"}))]
      (is (str/includes? html "<figcaption>Figure 1: Sensitivity analysis</figcaption>"))
      (is (str/includes? html "<figure id=\"fig:sens\" class=\"figure\">"))
      (is (str/includes? html "<figure id=\"fig:temp\" class=\"subfigure\">"))
      (is (str/includes? html "<figcaption>Figure 2: A tree.</figcaption>")
          "the panels took no number of their own from the figure sequence")
      (is (str/includes? tex "\\begin{figure}"))
      (is (str/includes? tex "\\begin{subfigure}{0.48\\linewidth}"))
      (is (str/includes? tex "\\caption{Sensitivity analysis}"))
      (is (str/includes? tex "\\label{fig:sens}"))
      (testing "and a real pdflatex, numbering from its own counters and
                knowing nothing about the resolver, prints the same"
        (let [text (pdf-text dir tex)]
          (is (some? text) "the document must compile")
          (is (str/includes? text "Figure 1: Sensitivity analysis") text)
          (is (str/includes? text "Figure 2: A tree.") text))))))

(deftest panel-letters-are-printed-and-referenceable-test
  (testing "AC #2/#3: every panel prints its own letter -- including the
            one whose caption is empty, which is 19 of the 49 panels in
            the thesis this milestone is scoped from -- and a panel
            carrying an id is a real cross-reference target reading
            'Figure 1b' in BOTH targets"
    (let [dir (fixture-dir! panels-document)
          html (:output (build! dir {}))
          tex (:output (build! dir {:target "latex"}))]
      (is (str/includes? html "<figcaption>(a) Temperature</figcaption>"))
      (is (str/includes? html "<figcaption>(b)</figcaption>")
          "an empty caption still prints its letter")
      (is (str/includes? html "<figcaption>(c) Flow</figcaption>"))
      (is (str/includes? html "<a href=\"#fig:pressure\">Figure 1b</a>"))
      (is (str/includes? tex "\\caption{}")
          "the letter comes from the caption command, so the command is emitted empty or not")
      (is (str/includes? tex "\\label{fig:pressure}"))
      (testing "and the compiled PDF prints the same letters and the same
                reference -- the only check that can catch subcaption's
                own lettering disagreeing with the resolver's"
        (let [text (pdf-text dir tex)]
          (is (some? text) "the document must compile")
          (is (str/includes? text "(a) Temperature") text)
          (is (str/includes? text "(b)") text)
          (is (str/includes? text "(c) Flow") text)
          (is (str/includes? text "See Figure 1, Figure 1b and Figure 2.") text))))))

(deftest panel-computed-numbers-test
  (testing "computed-numbers mode bypasses every LaTeX counter, so the
            panel letter has to come from the resolver there -- and must
            still read exactly as native mode and HTML print it"
    (let [dir (fixture-dir! panels-document)
          computed (:output (build! dir {:target "latex" :computed-numbers true
                                         :output (str (io/file dir "computed.tex"))}))]
      (is (str/includes? computed "\\caption*{(a) Temperature}"))
      (is (str/includes? computed "\\caption*{(b)}"))
      (is (str/includes? computed "\\caption*{Figure 1: Sensitivity analysis}")
          "the parent still prints its full label, only the panels print letters")
      (is (str/includes? computed "\\hyperref[fig:pressure]{Figure 1b}"))
      (testing "and it compiles"
        (is (some? (pdf-text dir computed)))))))

(deftest panel-rows-follow-the-authors-columns-test
  (testing "AC #4: the author's own columns= decides the arrangement, in
            both targets -- two per row means a row break after the
            second panel in LaTeX and two row elements in HTML"
    (let [dir (fixture-dir! panels-document)
          html (:output (build! dir {}))
          tex (:output (build! dir {:target "latex"}))]
      (is (= 2 (count (re-seq #"class=\"subfigure-row\"" html))))
      (is (= 1 (count (re-seq #"\\\\\n" tex))) "one row break, between panels two and three")
      (is (= 1 (count (re-seq #"\\hfill" tex)))
          "and one within-row separator: three panels two to a row is a full row and a lone one")))
  (testing "and three columns puts the same three panels in one row, with
            no row break at all and a narrower panel"
    (let [dir (fixture-dir! (str/replace panels-document "columns=2" "columns=3"))
          html (:output (build! dir {}))
          tex (:output (build! dir {:target "latex"}))]
      (is (= 1 (count (re-seq #"class=\"subfigure-row\"" html))))
      (is (not (str/includes? tex "\\\\\n")))
      (is (str/includes? tex "\\begin{subfigure}{0.32\\linewidth}"))
      (is (some? (pdf-text dir tex)) "and it still compiles")))
  (testing "and no columns= at all stacks them one per row -- the one
            arrangement that cannot overflow the line, chosen rather
            than left to the target"
    (let [dir (fixture-dir! (str/replace panels-document " columns=2" ""))
          html (:output (build! dir {}))
          tex (:output (build! dir {:target "latex"}))]
      (is (= 3 (count (re-seq #"class=\"subfigure-row\"" html))))
      (is (= 2 (count (re-seq #"\\\\\n" tex))))
      (is (str/includes? tex "\\begin{subfigure}{0.96\\linewidth}"))))
  (testing "and a columns= that is not a positive integer stops the build
            naming the directive, rather than silently laying the panels
            out some other way"
    (let [dir (fixture-dir! (str/replace panels-document "columns=2" "columns=two"))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"columns=\"two\""
                            (build! dir {:target "latex"}))))))

(deftest figure-nested-in-a-float-test
  (testing "an id-bearing image inside a panel is a Figure Block by the
            time the emitter sees it -- the parser converts a standalone
            one -- and a float inside a float is a fatal pdflatex error
            with no PDF at all. The build stops naming the figure and
            saying where the id belongs, rather than emitting LaTeX that
            cannot compile"
    (let [dir (fixture-dir! (str "# I\n\n"
                                 "::::{figure #fig:sens caption=\"Whole\"}\n\n"
                                 ":::{subfigure #fig:temp}\n![](pic.png){#fig:inner}\n:::\n\n"
                                 "::::\n"))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"nested inside another float"
                            (build! dir {:target "latex"})))))
  (testing "and HTML, where a figure inside a figure is exactly what a
            panel already is, renders it"
    (let [dir (fixture-dir! (str "# I\n\n"
                                 "::::{figure #fig:sens caption=\"Whole\"}\n\n"
                                 ":::{subfigure #fig:temp}\n![](pic.png){#fig:inner}\n:::\n\n"
                                 "::::\n"))
          html (:output (build! dir {}))]
      (is (str/includes? html "<figure id=\"fig:inner\">")))))

(deftest single-image-figure-is-unchanged-test
  (testing "AC #5: the figure spelling that existed before this task --
            a bare id-bearing image -- parses and renders exactly as it
            did, with no panel machinery anywhere near it"
    (let [dir (fixture-dir! "# I\n\n![A tree.](pic.png){#fig:tree}\n\nSee @fig:tree.\n")
          html (:output (build! dir {}))
          tex (:output (build! dir {:target "latex"}))]
      (is (str/includes? html "<figure id=\"fig:tree\">")
          "no class, so nothing lowered it -- the parser built the Figure directly")
      (is (str/includes? html "<figcaption>Figure 1: A tree.</figcaption>"))
      (is (not (str/includes? html "class=\"subfigure")))
      (is (str/includes? tex "\\begin{figure}"))
      (is (not (str/includes? tex "subfigure")))
      (is (str/includes? tex "\\caption{A tree.}"))
      (is (some? (pdf-text dir tex))))))

(deftest panel-numbering-restarts-per-figure-test
  (testing "each figure letters its own panels from a, and a panel takes
            no figure number away from the figures around it"
    (let [dir (fixture-dir! (str "# I\n\n"
                                 "::::{figure #fig:one caption=\"One\"}\n\n"
                                 ":::{subfigure #fig:one-a caption=\"A\"}\n![](pic.png)\n:::\n\n"
                                 ":::{subfigure #fig:one-b caption=\"B\"}\n![](pic.png)\n:::\n\n"
                                 "::::\n\n"
                                 "::::{figure #fig:two caption=\"Two\"}\n\n"
                                 ":::{subfigure #fig:two-a caption=\"A\"}\n![](pic.png)\n:::\n\n"
                                 "::::\n\n"
                                 "See @fig:one-b and @fig:two-a.\n"))
          html (:output (build! dir {}))
          tex (:output (build! dir {:target "latex"}))]
      (is (str/includes? html "<a href=\"#fig:one-b\">Figure 1b</a>"))
      (is (str/includes? html "<a href=\"#fig:two-a\">Figure 2a</a>")
          "the second figure's panels start over at a, under its own number")
      (testing "and the compiled PDF agrees, which is what subcaption's
                own per-float counter reset has to be checked against"
        (let [text (pdf-text dir tex)]
          (is (some? text) "the document must compile")
          (is (str/includes? text "See Figure 1b and Figure 2a.") text))))))

(deftest panels-with-prose-between-them-test
  (testing "found by review: prose between two panels ends the row it
            interrupts, in BOTH targets -- the LaTeX side used to count
            straight through the interruption, so the same document was
            arranged [A B] [C] in the PDF and [A] [B C] in HTML"
    (let [dir (fixture-dir! (str "# I\n\n"
                                 "::::{figure #fig:mixed caption=\"Mixed\" columns=2}\n\n"
                                 ":::{subfigure #fig:a caption=\"A\"}\n![](pic.png)\n:::\n\n"
                                 "Prose between.\n\n"
                                 ":::{subfigure #fig:b caption=\"B\"}\n![](pic.png)\n:::\n\n"
                                 ":::{subfigure #fig:c caption=\"C\"}\n![](pic.png)\n:::\n\n"
                                 "::::\n"))
          html (:output (build! dir {}))
          tex (:output (build! dir {:target "latex"}))]
      (is (= 2 (count (re-seq #"class=\"subfigure-row\"" html))))
      (is (= 1 (count (re-seq #"\\hfill" tex)))
          "B and C share a row; A is alone in the one the prose closed")
      (is (not (str/includes? tex "\\\\\n"))
          "and no row break: a row ended by prose needs none")
      (is (str/includes? tex "\\end{subfigure}\n\nProse between.\n\n\\begin{subfigure}")
          "the prose is its own paragraph, not text flowed through the row")
      (is (some? (pdf-text dir tex)) "and it compiles")))
  (testing "two prose paragraphs inside a paneled float stay two
            paragraphs -- the single-newline join used to merge them"
    (let [dir (fixture-dir! (str "# I\n\n"
                                 "::::{figure #fig:mixed caption=\"Mixed\"}\n\n"
                                 ":::{subfigure #fig:a caption=\"A\"}\n![](pic.png)\n:::\n\n"
                                 "First paragraph.\n\n"
                                 "Second paragraph.\n\n"
                                 "::::\n"))
          tex (:output (build! dir {:target "latex"}))]
      (is (str/includes? tex "First paragraph.\n\nSecond paragraph.")))))

(deftest panels-under-an-unlabelled-figure-test
  (testing "found by review: labelled panels inside a figure with NO id
            of its own cannot agree across targets -- subcaption letters
            them against whatever the figure counter happens to hold, so
            the PDF read Figure 2a for a panel HTML numbered Figure 3.
            The LaTeX build now stops and names the fix"
    (let [dir (fixture-dir! (str "# I\n\n"
                                 "::::{figure caption=\"Whole\" columns=2}\n\n"
                                 ":::{subfigure #fig:a caption=\"A\"}\n![](pic.png)\n:::\n\n"
                                 ":::{subfigure #fig:b caption=\"B\"}\n![](pic.png)\n:::\n\n"
                                 "::::\n"))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"the figure holding it carries no id"
                            (build! dir {:target "latex"})))))
  (testing "and an unlabelled figure whose panels are unlabelled too is
            fine: nothing is a numbering target, so there is nothing for
            the two targets to disagree about"
    (let [dir (fixture-dir! (str "# I\n\n"
                                 "::::{figure caption=\"Whole\" columns=2}\n\n"
                                 ":::{subfigure caption=\"A\"}\n![](pic.png)\n:::\n\n"
                                 ":::{subfigure caption=\"B\"}\n![](pic.png)\n:::\n\n"
                                 "::::\n"))
          html (:output (build! dir {}))
          tex (:output (build! dir {:target "latex"}))]
      (is (str/includes? tex "\\caption*{A}")
          "an id-less panel takes the unnumbered caption, so it steps no letter")
      (is (str/includes? html "<figcaption>A</figcaption>")
          "and HTML prints the same caption with no letter either")
      (is (some? (pdf-text dir tex))))))

(deftest float-nested-in-a-float-test
  (testing "found by review: the float-in-float guard has to cover every
            float, not only a Figure Block. A listing inside a figure
            emitted nested float environments and died in pdflatex with
            'Not in outer par mode' and no PDF"
    (let [dir (fixture-dir! (str "# I\n\n"
                                 "::::{figure #fig:m caption=\"Whole\"}\n\n"
                                 ":::{listing #lst:in caption=\"Inner\"}\n```\nx\n```\n:::\n\n"
                                 "::::\n"))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"nested inside another float"
                            (build! dir {:target "latex"})))))
  (testing "and a figure inside a figure, which this task's own directive
            made writable"
    (let [dir (fixture-dir! (str "# I\n\n"
                                 "::::{figure #fig:m caption=\"Whole\"}\n\n"
                                 ":::{figure #fig:in caption=\"Inner\"}\n![](pic.png)\n:::\n\n"
                                 "::::\n"))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"nested inside another float"
                            (build! dir {:target "latex"})))))
  (testing "a panel is the one float that IS allowed inside another --
            that is what a panel is"
    (let [dir (fixture-dir! (str "# I\n\n"
                                 "::::{figure #fig:m caption=\"Whole\"}\n\n"
                                 ":::{subfigure #fig:in caption=\"Inner\"}\n![](pic.png)\n:::\n\n"
                                 "::::\n"))
          tex (:output (build! dir {:target "latex"}))]
      (is (str/includes? tex "\\begin{subfigure}"))
      (is (some? (pdf-text dir tex))))))

(deftest orphan-panel-across-targets-test
  (testing "a subfigure written outside any figure stops the LaTeX build
            naming it -- subcaption refuses the shape outright"
    (let [dir (fixture-dir! ":::{subfigure #fig:lonely caption=\"Lonely\"}\n![](pic.png)\n:::\n")]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not a direct child of a float"
                            (build! dir {:target "latex"})))))
  (testing "HTML renders it as the plain captioned figure it degrades to,
            and that difference is the honest one: HTML has no float
            concept for the shape to be wrong about, while LaTeX cannot
            typeset it at all (found by review, which is why the README
            no longer states the error unconditionally)"
    (let [dir (fixture-dir! ":::{subfigure #fig:lonely caption=\"Lonely\"}\n![](pic.png)\n:::\n")
          html (:output (build! dir {}))]
      (is (str/includes? html "<figcaption>Figure 1: Lonely</figcaption>")))))

(deftest columns-is-only-read-where-panels-are-test
  (testing "found by review: columns= on a float that can hold no panels
            is not read at all, so a meaningless attribute there cannot
            fail a build -- and it survives as the attribute the author
            wrote, since nothing consumed it"
    (let [dir (fixture-dir! (str "# I\n\n"
                                 ":::{listing #lst:l caption=\"L\" columns=two}\n"
                                 "```\nx\n```\n:::\n"))
          html (:output (build! dir {}))
          tex (:output (build! dir {:target "latex"}))]
      (is (str/includes? html "columns=\"two\""))
      (is (str/includes? tex "\\begin{hnlisting}")))))

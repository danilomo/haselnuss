(ns haselnuss.derived-lists-test
  "The table of contents, list of figures and list of tables an author
  can place in a document (TASK-59).

  Driven through `haselnuss.cli/build`, the entry point a user actually
  has, and compiled with a real `pdflatex`: the whole risk in this
  feature is that the two LaTeX modes build a list two different ways --
  native hands the job to LaTeX's own counters, computed-numbers mode
  renders the resolver's derivations because those counters are
  deliberately bypassed there -- so a list that emits but prints nothing
  is exactly the failure to catch, and only a compiler catches it.

  The numbers those lists print are checked against the numbers the body
  prints in `haselnuss.cross-format-test`, which is where every other
  number in this project is held to the same rule."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [haselnuss.cli :as cli]
            [haselnuss.extensions.derived-lists :as derived-lists])
  (:import (java.awt.image BufferedImage)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (javax.imageio ImageIO)))

(defn- fixture-dir!
  [source]
  (let [dir (str (Files/createTempDirectory "haselnuss-lists" (make-array FileAttribute 0)))]
    (spit (io/file dir "doc.hdoc") source)
    (ImageIO/write (BufferedImage. 1 1 BufferedImage/TYPE_INT_RGB) "png"
                   (io/file dir "pic.png"))
    dir))

(defn- build!
  [dir opts]
  (let [result (cli/build (merge {:input (str (io/file dir "doc.hdoc")) :target "html"} opts))]
    (assoc result :output (slurp (:output-path result)))))

(defn- pdf-text
  "The text of `tex` compiled in `dir`, three times so the .toc/.lof/.lot
  files written by one run are read by the next -- a list is the one
  construct that genuinely needs more than two passes -- whitespace-
  normalized, or nil if it did not compile."
  [dir tex]
  (spit (io/file dir "host.tex") tex)
  (dotimes [_ 2] (shell/sh "pdflatex" "-interaction=nonstopmode" "host.tex" :dir (str dir)))
  (let [result (shell/sh "pdflatex" "-interaction=nonstopmode" "host.tex" :dir (str dir))]
    (when (zero? (:exit result))
      (str/replace (:out (shell/sh "pdftotext" (str (io/file dir "host.pdf")) "-"))
                   #"\s+" " "))))

(def ^:private lists-document
  (str ":::{toc}\n:::\n\n"
       ":::{list-of-figures}\n:::\n\n"
       ":::{list-of-tables}\n:::\n\n"
       "# Background {#sec:bg}\n\n"
       "![A hazel tree.](pic.png){#fig:tree}\n\n"
       "## Method {#sec:method}\n\n"
       "| A | B |\n|:--|--:|\n| 1 | 2 |\n: Measurements {#tbl:m}\n\n"
       "# Results {#sec:res}\n\n"
       "See @fig:tree and @tbl:m.\n"))

(deftest lists-are-placed-where-the-author-writes-them-test
  (testing "AC #1: each of the three is an empty block directive written
            where the list belongs, and each appears there -- ahead of
            the first section here, which is where a thesis puts them"
    (let [dir (fixture-dir! lists-document)
          html (:output (build! dir {}))
          tex (:output (build! dir {:target "latex"}))]
      (is (< (.indexOf html "class=\"toc\"")
             (.indexOf html "class=\"list-of-figures\"")
             (.indexOf html "class=\"list-of-tables\"")
             (.indexOf html "<section id=\"sec:bg\""))
          "in the order written, before the body")
      (is (< (.indexOf tex "\\tableofcontents")
             (.indexOf tex "\\listoffigures")
             (.indexOf tex "\\listoftables")
             (.indexOf tex "\\section{Background}")))))
  (testing "and a document that puts the contents after a section gets it
            there -- the placement is the author's, not a fixed one"
    (let [dir (fixture-dir! (str "# Preface {#sec:pre}\n\nProse.\n\n"
                                 ":::{toc}\n:::\n\n"
                                 "# Background {#sec:bg}\n\nMore.\n"))
          html (:output (build! dir {}))]
      (is (< (.indexOf html "<section id=\"sec:pre\"")
             (.indexOf html "class=\"toc\""))))))

(deftest html-renders-each-list-from-the-derivations-test
  (testing "AC #2: HTML prints each list itself, from the resolver's own
            derivations, with every entry linking to the node it names"
    (let [dir (fixture-dir! lists-document)
          html (:output (build! dir {}))]
      (is (str/includes? html "<nav class=\"toc\"><h1>Contents</h1>"))
      (is (str/includes? html "<a href=\"#sec:bg\"><span class=\"toc-number\">1</span> Background</a>"))
      (is (str/includes? html "<a href=\"#sec:method\"><span class=\"toc-number\">1.1</span> Method</a>"))
      (is (str/includes? html "<a href=\"#fig:tree\">Figure 1.1: A hazel tree.</a>"))
      (is (str/includes? html "<a href=\"#tbl:m\">Table 1.1.1: Measurements</a>"))
      (testing "and the table of contents nests, so it shows the shape of
                the document rather than a flat run of headings"
        (is (str/includes? html
                           (str "<a href=\"#sec:bg\"><span class=\"toc-number\">1</span> Background</a>"
                                "<ol><li><a href=\"#sec:method\"")))))))

(deftest latex-native-mode-emits-the-commands-test
  (testing "AC #3: native mode emits LaTeX's own three commands and lets
            LaTeX build the lists from its own counters -- which is right
            precisely because in that mode those counters ARE the
            document's numbers"
    (let [dir (fixture-dir! lists-document)
          tex (:output (build! dir {:target "latex"}))]
      (is (str/includes? tex "\\tableofcontents"))
      (is (str/includes? tex "\\listoffigures"))
      (is (str/includes? tex "\\listoftables"))
      (is (not (str/includes? tex "\\section*{Contents}"))
          "and renders no list itself, or the document would print each one twice")
      (testing "and a real pdflatex prints all three, with the entries in
                them -- a list that emits but prints nothing is the
                failure this mode can have, and only a compiler sees it"
        (let [text (pdf-text dir tex)]
          (is (some? text) "the document must compile")
          (is (str/includes? text "Contents") text)
          (is (str/includes? text "List of Figures") text)
          (is (str/includes? text "List of Tables") text)
          (is (str/includes? text "A hazel tree.") text)
          (is (str/includes? text "Measurements") text))))))

(deftest latex-computed-mode-renders-the-derived-lists-test
  (testing "AC #3: computed-numbers mode renders the derived list itself.
            It has to: that mode bypasses LaTeX's counters everywhere
            else -- starred sectioning, \\caption* on every float -- and
            \\caption* writes nothing to the .lof at all, so
            \\listoffigures there would print an EMPTY list rather than a
            differently-numbered one"
    (let [dir (fixture-dir! lists-document)
          tex (:output (build! dir {:target "latex" :computed-numbers true
                                    :output (str (io/file dir "computed.tex"))}))]
      (is (not (str/includes? tex "\\tableofcontents")))
      (is (not (str/includes? tex "\\listoffigures")))
      (is (str/includes? tex "\\section*{Contents}"))
      (is (str/includes? tex "\\hyperref[sec:bg]{1\\quad Background}"))
      (is (str/includes? tex "\\hyperref[sec:method]{1.1\\quad Method}")
          "the nested section is in the list too, indented rather than nested")
      (is (str/includes? tex "\\hyperref[fig:tree]{Figure 1.1: A hazel tree.}"))
      (is (str/includes? tex "\\hyperref[tbl:m]{Table 1.1.1: Measurements}"))
      (testing "and it compiles, printing the same entries"
        (let [text (pdf-text dir tex)]
          (is (some? text) "the document must compile")
          (is (str/includes? text "Contents") text)
          (is (str/includes? text "Figure 1.1: A hazel tree.") text)
          (is (str/includes? text "Table 1.1.1: Measurements") text))))))

(deftest computed-mode-list-entry-without-an-id-compiles-test
  (testing "TASK-77: a list entry with no id has neither an \\hspace* nor an
            \\hyperref between `\\noindent` and its own text, so without an
            empty group after the control word TeX read
            `\\noindentIntroduction` as one undefined command -- and ONE
            unlabelled heading anywhere in a document was enough to produce
            no PDF at all. Compiled rather than asserted on the string,
            since the string looked fine"
    (let [dir (fixture-dir! (str ":::{toc}\n:::\n\n"
                                 ":::{list-of-figures}\n:::\n\n"
                                 ;; No id on the chapter, none on the figure:
                                 ;; both are ordinary, and both used to be fatal.
                                 "# Introduction\n\n"
                                 "![A hazel tree.](pic.png)\n\n"
                                 "## Scope {#sec:scope}\n\n"
                                 "Text.\n"))
          tex (:output (build! dir {:target "latex" :computed-numbers true
                                    :output (str (io/file dir "computed.tex"))}))]
      (is (str/includes? tex "\\noindent{}Introduction")
          "the control word must be terminated before the entry's own text")
      (let [text (pdf-text dir tex)]
        (is (some? text) "the document must compile")
        (is (str/includes? text "Introduction") text)))))

(deftest a-document-asking-for-no-lists-is-unchanged-test
  (testing "AC #5: a document that writes no placeholder gets no list, no
            heading and no command -- the feature is entirely opt-in"
    (let [dir (fixture-dir! (str "# Background {#sec:bg}\n\n"
                                 "![A hazel tree.](pic.png){#fig:tree}\n\nSee @fig:tree.\n"))
          html (:output (build! dir {}))
          tex (:output (build! dir {:target "latex"}))]
      (is (not (str/includes? html "<nav")))
      (is (not (str/includes? html "Contents")))
      (is (not (str/includes? tex "\\tableofcontents")))
      (is (not (str/includes? tex "\\listoffigures")))
      (is (not (str/includes? tex "\\listoftables"))))))

(deftest front-matter-is-not-in-any-list-test
  (testing "a heading inside an abstract is outside sectioning by
            definition (TASK-54's body-view, which numbering and every
            derivation built on it already share), so it is not a
            table-of-contents entry either -- asserted rather than
            assumed"
    (let [dir (fixture-dir! (str ":::{abstract}\nIn short.\n\n# Not a section {#sec:nope}\n\n"
                                 "![A front-matter figure.](pic.png){#fig:fm}\n:::\n\n"
                                 ":::{toc}\n:::\n\n:::{list-of-figures}\n:::\n\n"
                                 "# Background {#sec:bg}\n\n"
                                 "![A body figure.](pic.png){#fig:body}\n"))
          html (:output (build! dir {}))]
      (is (str/includes? html "<a href=\"#sec:bg\">"))
      (is (not (str/includes? html "<a href=\"#sec:nope\">")))
      (is (not (str/includes? html "<a href=\"#fig:fm\">")))))
  (testing "and NATIVE mode agrees, which is the half that was missing
            (found by review): LaTeX builds those lists from its own
            counters, so front matter has to be emitted unnumbered or
            the compiled .toc lists a heading no derived list has -- and
            renumbers every body section after it"
    (let [dir (fixture-dir! (str ":::{abstract}\nIn short.\n\n# Not a section {#sec:nope}\n\n"
                                 "![A front-matter figure.](pic.png){#fig:fm}\n:::\n\n"
                                 ":::{toc}\n:::\n\n:::{list-of-figures}\n:::\n\n"
                                 "# Background {#sec:bg}\n\n"
                                 "![A body figure.](pic.png){#fig:body}\n"))
          tex (:output (build! dir {:target "latex"}))]
      (is (str/includes? tex "\\section*{Not a section}")
          "starred, so LaTeX neither numbers it nor writes it to the .toc")
      (is (str/includes? tex "\\caption*{A front-matter figure.}")
          "and unnumbered, so it steps no figure counter and reaches no .lof")
      (is (str/includes? tex "\\section{Background}"))
      (is (str/includes? tex "\\caption{A body figure.}"))
      (testing "and the compiled lists really do contain only the body's
                own, which is the thing the .toc/.lof files decide"
        (is (some? (pdf-text dir tex)) "the document must compile")
        (let [toc (slurp (io/file dir "host.toc"))
              lof (slurp (io/file dir "host.lof"))]
          (is (str/includes? toc "Background") toc)
          (is (not (str/includes? toc "Not a section")) toc)
          (is (str/includes? lof "A body figure.") lof)
          (is (not (str/includes? lof "A front-matter figure.")) lof))))))

(deftest an-unnumbered-float-is-in-no-list-test
  (testing "an id-less captioned table is not a numbering target here, so
            it is in no derived list -- and native mode has to agree, or
            its own .lot carries an entry nothing else has and every
            table after it is a number ahead (found by review). LaTeX's
            longtable steps the table counter even for \\caption*, so
            the step is undone"
    (let [dir (fixture-dir! (str ":::{list-of-tables}\n:::\n\n# Background {#sec:bg}\n\n"
                                 "| A | B |\n|:--|--:|\n| 1 | 2 |\n: An unlabelled table\n\n"
                                 "| C | D |\n|:--|--:|\n| 3 | 4 |\n: A labelled table {#tbl:t}\n"))
          html (:output (build! dir {}))
          tex (:output (build! dir {:target "latex"}))]
      (is (str/includes? html "<a href=\"#tbl:t\">Table 1.1: A labelled table</a>"))
      (is (not (str/includes? html "An unlabelled table</a>")))
      (is (str/includes? tex "\\caption*{An unlabelled table}\\addtocounter{table}{-1}"))
      (testing "and the compiled .lot lists the labelled table, numbered
                as if the unlabelled one had never counted"
        (is (some? (pdf-text dir tex)) "the document must compile")
        (let [lot (slurp (io/file dir "host.lot"))]
          (is (str/includes? lot "A labelled table") lot)
          (is (not (str/includes? lot "An unlabelled table")) lot)
          (is (str/includes? lot "{1}") lot))))))

(deftest list-entries-do-not-re-render-notes-or-links-test
  (testing "found by review: a list entry is rendered from the same
            Inlines the node itself renders, so a footnote in a heading
            was collected twice -- the document grew a duplicate note and
            every note after it was renumbered -- and a link in a heading
            landed inside the entry's own link, which a browser closes
            early"
    (let [dir (fixture-dir! (str ":::{toc}\n:::\n\n"
                                 "# Background[^a] {#sec:bg}\n\n"
                                 "## See [the site](https://example.com) {#sec:link}\n\n"
                                 "Prose.[^b]\n\n"
                                 "[^a]: A heading note.\n\n[^b]: A body note.\n"))
          html (:output (build! dir {}))
          computed (:output (build! dir {:target "latex" :computed-numbers true
                                         :output (str (io/file dir "computed.tex"))}))]
      (is (= 1 (count (re-seq #"A heading note\." html)))
          "one footnote, collected where the author wrote it")
      (is (= 2 (count (re-seq #"<li id=\"fn\d+\"" html)))
          "and the document still has exactly its two notes")
      (is (str/includes? html "<a href=\"#sec:link\"><span class=\"toc-number\">1.1</span> See the site</a>")
          "the link is its own text inside the entry, not a second anchor")
      (is (not (str/includes? computed "\\hyperref[sec:bg]{1\\quad Background\\footnote")))
      (is (str/includes? computed "\\hyperref[sec:link]{1.1\\quad See the site}"))
      (testing "and it compiles"
        (is (some? (pdf-text dir computed)))))))

(deftest the-list-marker-is-never-called-test
  (testing "both emitters draw a list themselves, so the renderer the
            registry holds for a placeholder is a marker -- calling it is
            a named error rather than something meaningless spliced into
            an output (the contract
            haselnuss.extensions.front-matter/front-matter-renderer
            already has)"
    (let [directive {:t :directive :name "toc" :blocks [] :attr {:classes [] :props {}}}
          e (is (thrown? clojure.lang.ExceptionInfo (derived-lists/list-renderer directive :html)))]
      (is (= :haselnuss.extensions.derived-lists/list-marker-called (:type (ex-data e))))
      (is (str/includes? (ex-message e) "\"toc\""))
      (is (str/includes? (ex-message e) ":html")))))

(deftest list-headings-follow-the-documents-language-test
  (testing "the heading each list prints is text this project prints, not
            text the author wrote, so it follows meta.lang the way every
            lexicon word does"
    (let [dir (fixture-dir! (str "---\nlang: pt-BR\n---\n\n" lists-document))
          html (:output (build! dir {}))
          computed (:output (build! dir {:target "latex" :computed-numbers true
                                         :output (str (io/file dir "computed.tex"))}))]
      (is (str/includes? html "<h1>Sumário</h1>"))
      (is (str/includes? html "<h1>Lista de Figuras</h1>"))
      (is (str/includes? html "<h1>Lista de Tabelas</h1>"))
      (is (str/includes? computed "\\section*{Sumário}"))
      (is (str/includes? computed "\\section*{Lista de Figuras}"))))
  (testing "and a language nobody translated it into still gets a heading
            -- English, which is the fallback every other printed word
            here uses"
    (let [dir (fixture-dir! (str "---\nlang: de\n---\n\n" lists-document))
          html (:output (build! dir {}))]
      (is (str/includes? html "<h1>Contents</h1>")))))

(deftest an-empty-list-still-prints-its-heading-test
  (testing "a document with no tables that asks for a list of tables gets
            the heading and an empty list -- which is exactly what
            \\listoftables does with the same document, so the two
            targets agree about a document that has nothing to list"
    (let [dir (fixture-dir! ":::{list-of-tables}\n:::\n\n# Background {#sec:bg}\n\nProse.\n")
          html (:output (build! dir {}))]
      (is (str/includes? html "<nav class=\"list-of-tables\"><h1>List of Tables</h1><ol></ol></nav>")))))

(deftest a-misspelled-placeholder-stops-the-build-test
  (testing "a misspelling is an unregistered directive like any other, so
            the build stops and names it rather than silently emitting
            nothing where the author asked for a list"
    (let [dir (fixture-dir! ":::{table-of-contents}\n:::\n\n# Background {#sec:bg}\n\nProse.\n")]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"table-of-contents"
                            (build! dir {}))))))

(deftest a-figure-directive-is-listed-and-its-panels-are-not-test
  (testing "a figure authored as a DIRECTIVE -- the only spelling a
            multi-panel figure has (TASK-56) -- is in the list of
            figures, because native LaTeX's own .lof lists it; its
            panels are not, because \\listoffigures does not list
            subfigures either"
    (let [dir (fixture-dir! (str ":::{list-of-figures}\n:::\n\n"
                                 "# Results {#sec:res}\n\n"
                                 "::::{figure #fig:panels caption=\"Sensitivity\" columns=2}\n\n"
                                 ":::{subfigure #fig:pa caption=\"A\"}\n![](pic.png)\n:::\n\n"
                                 ":::{subfigure #fig:pb caption=\"B\"}\n![](pic.png)\n:::\n\n"
                                 "::::\n"))
          html (:output (build! dir {}))
          tex (:output (build! dir {:target "latex"}))]
      (is (str/includes? html "<a href=\"#fig:panels\">Figure 1.1: Sensitivity</a>"))
      (is (not (str/includes? html "<a href=\"#fig:pa\">")))
      (testing "and the compiled native PDF lists the same one figure,
                which is what the exclusion has to agree with"
        (let [text (pdf-text dir tex)]
          (is (some? text) "the document must compile")
          (is (str/includes? text "List of Figures") text)
          (is (str/includes? text "Sensitivity") text))))))

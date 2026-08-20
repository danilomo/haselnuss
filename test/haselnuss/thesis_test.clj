(ns haselnuss.thesis-test
  "The thesis-shaped end-to-end fixture (TASK-61).

  Everything else that converts a whole document here is paper-shaped:
  `haselnuss.example-test` builds a short article, and
  `haselnuss.cross-format-test` holds two outputs of one article to each
  other. A book is a different document -- chapters, front matter in two
  languages, three derived lists, a multi-panel figure, a listing and an
  algorithm, images sized two ways -- and every one of those is a place
  the two targets can drift apart. Each landed in its own task with its
  own narrow fixtures; this is the one document that exercises them
  together.

  Two verifications a paper-shaped fixture cannot give, and they are why
  this namespace compiles rather than asserting on strings:

  - the standalone LaTeX compiles under a real `pdflatex`, run three
    times so the `.toc`/`.lof`/`.lot` written by one pass are read by
    the next;
  - a `--fragment` build of the SAME document compiles when `\\input`
    into a minimal host supplying only a class with `\\chapter` and the
    companion preamble. A fragment is not a document on its own, so
    that host is the only thing standing between a working fragment and
    one that silently needs a package nobody was told about -- and by
    now the fragment also writes front-matter side files, so the host
    has to place those too.

  The numbers the fixture prints are held to each other in
  `haselnuss.cross-format-test`, which is where every number in this
  project is compared across targets."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [haselnuss.cli :as cli])
  (:import (java.awt.image BufferedImage)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (javax.imageio ImageIO)))

(def ^:private images
  "The image files the fixture names. Generated rather than committed,
  the same choice `haselnuss.emit.latex-test` makes and for the same
  reason: a 1x1 PNG written here depends on no optional TeX Live
  package and on nothing in the repository."
  ["tree.png" "panel.png" "leaf.png"])

(defn- fixture-dir!
  "A fresh temp directory holding the committed fixture, its
  bibliography and the images it names -- read off the test classpath
  rather than a CWD-relative path, so this does not depend on the
  runner's working directory."
  []
  (let [dir (str (Files/createTempDirectory "haselnuss-thesis" (make-array FileAttribute 0)))]
    (doseq [f ["thesis.hdoc" "thesis.json"]]
      (io/copy (io/reader (io/resource (str "fixtures/" f))) (io/file dir f)))
    (doseq [image images]
      (ImageIO/write (BufferedImage. 1 1 BufferedImage/TYPE_INT_RGB) "png" (io/file dir image)))
    dir))

(defn- build!
  [dir opts]
  (let [result (cli/build (merge {:input (str (io/file dir "thesis.hdoc"))} opts))]
    (assoc result :output (slurp (:output-path result)))))

(defn- log-warnings
  "The LaTeX and pdfTeX warnings in a compile `log`, one per line.

  Read because pdflatex exits 0 on plenty of output nobody wants: the
  duplicate page destinations two `titlepage` abstracts produced
  (TASK-66) failed nothing here, since every compile helper in this
  suite inspects the exit status alone. `Reference ... undefined` and
  `Citation ... undefined` land here too -- the PDF-text assertions
  already catch those, and catching them twice costs nothing."
  [log]
  (->> (str/split-lines (or log ""))
       (filter #(re-find #"(?i)(pdfTeX warning|LaTeX Warning)" %))
       vec))

(defn- compile!
  "Compiles `tex-name` in `dir` for real: pdflatex, bibtex, pdflatex
  twice more, so citations resolve and the list files written by one
  pass are read by the next. Returns `[ok? text log]`, the last of
  which `log-warnings` reads."
  [dir tex-name]
  (let [run (fn [] (shell/sh "pdflatex" "-interaction=nonstopmode" tex-name :dir (str dir)))
        base (str/replace tex-name #"\.tex$" "")]
    (run)
    (shell/sh "bibtex" base :dir (str dir))
    (run)
    (let [result (run)]
      [(zero? (:exit result))
       (when (zero? (:exit result))
         (str/replace (:out (shell/sh "pdftotext" (str (io/file dir (str base ".pdf"))) "-"))
                      #"\s+" " "))
       (str (:out result) (:err result))])))

(deftest thesis-fixture-converts-to-both-targets-test
  (testing "AC #1: one document carrying every construct this milestone
            added converts to both targets -- chapters, a two-language
            abstract with keywords, the front-matter prose family, the
            three derived lists, a multi-panel figure with an
            empty-captioned panel, a captioned listing, an algorithm and
            images sized by scale and by height"
    (let [dir (fixture-dir!)
          html (build! dir {:target "html"})
          tex (build! dir {:target "latex" :output (str (io/file dir "thesis.tex"))})
          page (:output html)
          body (:output tex)]
      (testing "and it builds clean: the only diagnostic a correct
                document of this shape should produce is none at all"
        (is (empty? (:diagnostics html)) (pr-str (:diagnostics html)))
        (is (empty? (:diagnostics tex)) (pr-str (:diagnostics tex))))
      (testing "chapters (TASK-53)"
        (is (str/starts-with? body "\\documentclass{report}"))
        (is (str/includes? body "\\chapter{Introduction}"))
        (is (str/includes? page "<a href=\"#ch:methods\">Chapter 2</a>")))
      (testing "the two abstracts and their keywords (TASK-54)"
        (is (str/includes? page "<h1>Abstract</h1>"))
        (is (str/includes? page "<h1>Resumo</h1>"))
        (is (str/includes? page "Palavras-chave"))
        (is (str/includes? body "\\renewcommand{\\abstractname}{Resumo}"))
        (testing "including the accented Portuguese prose itself, which
                  is the only non-ASCII content here and travels a long
                  encoding path -- classpath resource, temp file, build,
                  inputenc, pdftotext. Every other assertion about this
                  abstract is ASCII and would stay green through a
                  mojibake regression (found by review)"
          (is (str/includes? page "previsões"))
          (is (str/includes? body "previsões"))))
      (testing "the front-matter prose family (TASK-55)"
        (is (str/includes? page "<h1>Acknowledgements</h1>"))
        (is (str/includes? body "\\begin{flushright}\\itshape"))
        (is (str/includes? body "\\begin{center}\\itshape")))
      (testing "the three derived lists (TASK-59)"
        (is (str/includes? page "<nav class=\"toc\">"))
        (is (str/includes? page "<nav class=\"list-of-figures\">"))
        (is (str/includes? page "<nav class=\"list-of-tables\">"))
        (is (str/includes? body "\\tableofcontents")))
      (testing "the multi-panel figure, including the panel with no
                caption of its own (TASK-56)"
        (is (str/includes? page "<figcaption>(b)</figcaption>"))
        (is (str/includes? page "<a href=\"#fig:sens-b\">Figure 2.1b</a>"))
        ;; `\begin{subfigure}` and not its width: 0.48 is a layout
        ;; constant `haselnuss.float-test` already owns, and ordinary
        ;; layout work should not break a book-shaped test whose subject
        ;; is numbering and compilation (found by review).
        (is (str/includes? body "\\begin{subfigure}")))
      (testing "the listing and the algorithm (TASK-57/TASK-58)"
        (is (str/includes? page "Listing 1: The fitting driver"))
        (is (str/includes? body "\\begin{hnlisting}"))
        (is (str/includes? body "\\begin{hnalgorithm}")))
      (testing "and the two image sizings (TASK-60), in BOTH targets --
                AC #1 is about both, and asserting only the LaTeX half
                is how the HTML half's own bug went unseen (found by
                review; fixed as TASK-65)"
        (is (str/includes? body "scale=0.5"))
        (is (str/includes? body "height=3cm"))
        (is (str/includes? page "style=\"zoom: 0.5\""))
        (is (str/includes? page "style=\"height: 3cm\""))
        (is (not (re-find #"<figure[^>]*(?:scale|height)=" page))
            "and the sizing reaches the <img> alone: on the <figure> it would be a literal attribute HTML has no meaning for")))))

(deftest thesis-fixture-compiles-test
  (testing "AC #3: the standalone LaTeX compiles under a real pdflatex,
            and the compiled PDF prints what the document says -- the
            chapter numbers, the panel letters, the three lists and every
            reference resolved. A .tex that emits but does not typeset is
            not output"
    (let [dir (fixture-dir!)
          _ (build! dir {:target "latex" :output (str (io/file dir "thesis.tex"))})
          [ok? text log] (compile! dir "thesis.tex")]
      (is ok? (str "expected the thesis to compile, pdflatex output:\n" log))
      (testing "with a clean log, not merely a zero exit (TASK-66): two
                titlepage abstracts in a report class each reset the page
                counter, so three pages held page 1 and hyperref dropped
                two destinations with a warning apiece -- a compiled,
                exit-0 PDF whose front-matter links landed on whichever
                page won"
        (is (empty? (log-warnings log)) (str/join "\n" (log-warnings log))))
      (when text
        (is (str/includes? text "Chapter 1") text)
        (is (str/includes? text "Contents") text)
        (is (str/includes? text "List of Figures") text)
        (is (str/includes? text "List of Tables") text)
        (is (str/includes? text "(a) Temperature") text)
        (is (str/includes? text "(b)") text)
        (is (str/includes? text "Figure 2.1b") text)
        (is (str/includes? text "Listing 1") text)
        (is (str/includes? text "Algorithm 1") text)
        (testing "and the reference list is really built, which nothing
                  here saw before (found by review): natbib prints a
                  single `[?]` for an unresolved citation, not `??`, so
                  a build with no bibtex run at all passed every
                  assertion above with an empty Bibliography"
          (is (str/includes? text "Donald Knuth") text)
          (is (str/includes? text "follows [1]") text))
        (is (not (re-find #"\?\?" text))
            (str "an unresolved reference survived into the PDF:\n" text))
        (is (not (re-find #"\[\? ?\]" text))
            (str "an unresolved citation survived into the PDF:\n" text))))))

(defn- minimal-host
  "The smallest host a fragment of this document can be `\\input` into: a
  class providing `\\chapter`, the companion preamble, and every
  front-matter side file the build reported, placed where a template
  would place them. Nothing else -- no packages of its own, no
  furniture -- because the point is that the fragment and its companion
  between them name everything the body needs.

  Built from `front-matter-paths`, the list `haselnuss.cli/build`
  returns, rather than from five hardcoded names (found by review): a
  host that hardcodes them stops placing a sixth kind of front matter
  the day one is added, and the test would not notice."
  [front-matter-paths]
  (str "\\documentclass{report}\n"
       "\\input{body-preamble}\n"
       "\\begin{document}\n"
       (apply str (map (fn [path]
                         (str "\\input{"
                              (str/replace (.getName (io/file path)) #"\.tex$" "")
                              "}\n"))
                       front-matter-paths))
       "\\input{body}\n"
       "\\end{document}\n"))

(deftest thesis-fragment-compiles-in-a-minimal-host-test
  (testing "AC #4: a --fragment build of the same document compiles when
            \\input into a host supplying only a class and the reported
            preamble. A fragment cannot load a package for itself, so
            this is the only check that can catch one it needs and does
            not report -- and by now it also writes front-matter side
            files, which the host has to place"
    (let [dir (fixture-dir!)
          {:keys [output preamble-path front-matter-paths diagnostics]}
          (build! dir {:target "latex" :fragment true :output (str (io/file dir "body.tex"))})]
      (is (not (str/includes? output "\\documentclass")))
      (is (not (str/includes? output "\\begin{document}")))
      ;; The front matter left the body rather than being duplicated
      ;; into it -- said by its own content's absence rather than by
      ;; pinning the body's first byte, which would break the day a
      ;; fragment gains the "% Generated by haselnuss" banner every
      ;; other artifact in this mode already carries (found by review).
      (is (not (str/includes? output "Palavras-chave")))
      (is (some? preamble-path) "and its companion preamble was written")
      (is (str/includes? (slurp preamble-path) "\\usepackage{subcaption}")
          "naming a package this body needs and no host would guess")
      (is (= [:fragment-bibliography] (map :type diagnostics))
          "the one warning this mode always gives, since a document may have only one bibliography")
      (is (= ["body-abstract-en.tex" "body-abstract-pt-BR.tex"
              "body-dedication-en.tex" "body-epigraph-en.tex"
              "body-acknowledgements-en.tex"]
             (mapv #(.getName (io/file %)) front-matter-paths))
          "one side file per front-matter block, named for the block and its language")
      (doseq [side front-matter-paths]
        (is (.exists (io/file side)) side))
      (spit (io/file dir "host.tex") (minimal-host front-matter-paths))
      (let [[ok? text log] (compile! dir "host.tex")]
        (is ok? (str "expected the fragment to compile inside the minimal host:\n" log))
        (is (empty? (log-warnings log)) (str/join "\n" (log-warnings log)))
        (when text
          (is (str/includes? text "Chapter 1") text)
          (is (str/includes? text "Figure 2.1b") text)
          (is (str/includes? text "Palavras-chave") text)
          (is (str/includes? text "previsões") text)
          (is (str/includes? text "Donald Knuth") text)
          (is (not (re-find #"\?\?" text))
              (str "an unresolved reference survived into the host's PDF:\n" text))
          (is (not (re-find #"\[\? ?\]" text))
              (str "an unresolved citation survived into the host's PDF:\n" text)))))))

(ns haselnuss.emit.latex-test
  "Fixtures for `haselnuss.emit.latex` (TASK-21, extended by TASK-22): the
  LaTeX emitter core. AC #1 of TASK-21 (\"a document exercising every
  covered block/inline node produces a .tex file that compiles without
  errors under a standard LaTeX toolchain\") is checked *literally* --
  `compiles?` shells out to a real `pdflatex -interaction=nonstopmode
  -halt-on-error` (already installed in this codebase's own dev
  environment; TASK-21's own recorded implementation plan documents
  having verified every LaTeX command choice below against it directly)
  rather than a dependency-free structural proxy the way `haselnuss.emit.
  html-test`'s own `well-formed?` is -- there is no equivalent lightweight
  LaTeX validator to substitute, and that AC's own wording (\"compiles ...
  under a standard LaTeX toolchain\") asks for exactly this. Every other
  AC (TASK-21's #2/#3, and this task's own #1-4 below) gets its own
  dedicated test, mirroring `haselnuss.emit.html-test`'s own per-AC
  structure."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [haselnuss.emit.html :as html]
            [haselnuss.emit.latex :as latex]
            [haselnuss.lower :as lower]
            [haselnuss.registry :as registry]
            [haselnuss.resolver :as resolver])
  (:import (java.awt.image BufferedImage)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (javax.imageio ImageIO)))

(defn- str-inline
  [text]
  {:t :str :text text})

(defn- para
  [& inlines]
  {:t :para :inlines (vec inlines)})

(def ^:private empty-attr
  {:classes [] :props {}})

(defn- write-test-image!
  "Writes a tiny, valid 1x1 PNG to `path` -- generated directly via
  `javax.imageio.ImageIO`, not copied from any bundled LaTeX package
  fixture (e.g. `mwe`'s own `example-image.png`), so this test's own AC
  #1 verification does not depend on which optional TeX Live packages
  happen to be installed on top of the toolchain itself."
  [path]
  (let [image (BufferedImage. 1 1 BufferedImage/TYPE_INT_RGB)]
    (ImageIO/write image "png" (io/file path))))

(defn- compiles?
  "True if `tex` (a complete `.tex` document string) compiles without
  error under a real `pdflatex -interaction=nonstopmode -halt-on-error`,
  run in a fresh temp directory (so `\\includegraphics{pic.png}` resolves
  against a real, self-contained test image written alongside it, see
  `write-test-image!`) -- this namespace's own direct, literal check for
  AC #1. Returns `[ok? shell-result]`, the latter for a test failure
  message to include the real compiler output rather than just `false`."
  [tex]
  (let [dir (Files/createTempDirectory "haselnuss-latex-test" (make-array FileAttribute 0))
        tex-file (io/file (str dir) "doc.tex")]
    (write-test-image! (io/file (str dir) "pic.png"))
    (spit tex-file tex)
    (let [result (shell/sh "pdflatex" "-interaction=nonstopmode" "-halt-on-error" "doc.tex"
                           :dir (str dir))]
      [(zero? (:exit result)) result])))

(defn- assert-compiles!
  [tex]
  (let [[ok? result] (compiles? tex)]
    (is ok? (str "expected doc to compile cleanly, pdflatex output:\n" (:out result) (:err result)))))

(defn- compile-pdf!
  "Compiles `tex` identically to `compiles?` (same fresh-temp-dir /
  `-halt-on-error` setup), but returns the produced `doc.pdf` (a
  `java.io.File`, or `nil` on a failed compile) instead of discarding the
  temp directory -- used by `table-pagination-test`/`table-cell-rich-
  content-test` below, which need to inspect the actual rendered PDF
  (page count, per-page text) beyond the plain compiles-or-not check
  `compiles?`/`assert-compiles!` already cover."
  [tex]
  (let [dir (Files/createTempDirectory "haselnuss-latex-test" (make-array FileAttribute 0))
        tex-file (io/file (str dir) "doc.tex")]
    (write-test-image! (io/file (str dir) "pic.png"))
    (spit tex-file tex)
    (let [result (shell/sh "pdflatex" "-interaction=nonstopmode" "-halt-on-error" "doc.tex"
                           :dir (str dir))]
      (when (zero? (:exit result))
        (io/file (str dir) "doc.pdf")))))

(defn- pdf-page-count
  "The page count of `pdf-file` (a compiled PDF), read via `pdfinfo`
  (already part of this codebase's own dev environment, alongside
  `pdflatex`) -- the direct, literal signal this task's review comment #1
  distinguishes on: the pre-fix `tabular`-inside-a-`table`-float
  implementation confirmed empirically to always produce exactly 1 page
  no matter how many rows overflowed off it, while a real `longtable`
  breaks across as many pages as the content needs."
  [pdf-file]
  (let [{:keys [out]} (shell/sh "pdfinfo" (str pdf-file))
        [_ pages] (re-find #"(?m)^Pages:\s+(\d+)" out)]
    (Long/parseLong pages)))

(defn- pdf-page-text
  "The plain-text content of one `page` (1-indexed) of `pdf-file`, via
  `pdftotext -f page -l page` -- used to confirm specific row text landed
  on the specific physical page pdflatex actually put it on, not merely
  that the document has more than one page."
  [pdf-file page]
  (:out (shell/sh "pdftotext" "-f" (str page) "-l" (str page) (str pdf-file) "-")))

(defn- pdf-text
  "The whole plain-text content of `pdf-file`, via `pdftotext`."
  [pdf-file]
  (:out (shell/sh "pdftotext" (str pdf-file) "-")))

(defn- longest-dark-run
  "The longest uninterrupted run of dark pixels in row `y` of `image`,
  in pixels -- the primitive `has-horizontal-rule?` scans a rendered
  page with."
  [^BufferedImage image y]
  (let [width (.getWidth image)]
    (loop [x 0 run 0 best 0]
      (if (= x width)
        best
        (let [rgb (.getRGB image x y)
              luminance (+ (* 0.299 (bit-and (bit-shift-right rgb 16) 0xff))
                           (* 0.587 (bit-and (bit-shift-right rgb 8) 0xff))
                           (* 0.114 (bit-and rgb 0xff)))
              run' (if (< luminance 128) (inc run) 0)]
          (recur (inc x) run' (max best run')))))))

(defn- horizontal-rule-widths
  "The width, in pixels, of every horizontal rule on the first page of
  `pdf-file`, rasterized at 60dpi via `pdftoppm` -- each row whose
  longest unbroken dark run exceeds 60px, which no body text reaches at
  this resolution.

  `pdftotext` cannot see a rule at all (it is drawn, not typeset as
  text), so TASK-37 AC #2's \"renders as its LaTeX equivalent\" is only
  checkable by looking at pixels. Returns widths rather than a boolean
  (TASK-37 review finding #3) because a page can carry a rule this
  emitter did not emit: LaTeX draws its own `\\footnoterule` at
  `.4\\columnwidth`, about 115px here, so a plain \"is there a long
  dark run?\" check silently passes on any footnote-bearing document.
  Callers assert the *expected* width instead."
  [pdf-file]
  (let [dir (.getParentFile (io/file (str pdf-file)))
        prefix (str (io/file dir "rulepage"))]
    (shell/sh "pdftoppm" "-r" "60" "-png" "-f" "1" "-l" "1" (str pdf-file) prefix)
    (let [image (ImageIO/read (io/file (str prefix "-1.png")))]
      (into [] (comp (map (partial longest-dark-run image)) (filter #(> % 60)))
            (range (.getHeight image))))))

(defn- thematic-break-rule-width?
  "True when `width` is the width `thematic-break-rule` produces at top
  level -- half a 60dpi page's text width, measured at 143px, with a
  tolerance wide enough for rounding but narrow enough to exclude
  LaTeX's own ~115px `\\footnoterule`."
  [width]
  (< 135 width 155))

(def ^:private test-bib
  "A tiny, real BibTeX database written alongside every
  `compile-with-bibtex!` document -- TASK-23 AC #2's own \"the
  bibliography is produced via biblatex/natbib from the same
  bibliography source\" can only be checked by letting a real BibTeX
  build the reference list from a real `.bib` file, so this is the file
  `\\bibliography{refs}` resolves to. Its two keys/authors/years match
  the CSL-JSON fixtures the same tests hand `haselnuss.resolver`, so the
  native-mode and computed-mode outputs below are describing the same
  two sources."
  (str "@book{knuth1984,\n"
       "  author = {Knuth, Donald E.},\n"
       "  title = {The {TeX}book},\n"
       "  year = {1984},\n"
       "  publisher = {Addison-Wesley}\n"
       "}\n"
       "@article{lamport1986,\n"
       "  author = {Lamport, Leslie},\n"
       "  title = {A Document Preparation System},\n"
       "  year = {1986},\n"
       "  journal = {Journal of Stuff}\n"
       "}\n"))

(defn- compile-with-bibtex!
  "Compiles `tex` through the full real BibTeX build cycle -- `pdflatex`,
  `bibtex`, `pdflatex`, `pdflatex` (the standard number of passes needed
  for `\\cite` labels and the reference list to both settle) -- in a
  fresh temp directory holding a real `refs.bib` (`test-bib`) and test
  image, returning `[ok? pdf-file last-log]`.

  Deliberately NOT `-halt-on-error`, unlike `compiles?`: TASK-23 AC #4's
  own dangling reference/citation cases are *expected* to produce
  `pdflatex`/`natbib` warnings, and the point of those tests is that they
  are warnings the build survives, not errors. `ok?` is still the real
  exit status of the final `pdflatex`, so a genuine LaTeX error still
  fails the test."
  [tex]
  (let [dir (Files/createTempDirectory "haselnuss-latex-bib-test" (make-array FileAttribute 0))
        run (fn [] (shell/sh "pdflatex" "-interaction=nonstopmode" "doc.tex" :dir (str dir)))]
    (write-test-image! (io/file (str dir) "pic.png"))
    (spit (io/file (str dir) "refs.bib") test-bib)
    (spit (io/file (str dir) "doc.tex") tex)
    (run)
    (shell/sh "bibtex" "doc" :dir (str dir))
    (run)
    (let [result (run)]
      [(zero? (:exit result)) (io/file (str dir) "doc.pdf") (str (:out result) (:err result))])))

(deftest full-document-test
  (testing "AC #1: a document exercising every covered block/inline node
            emits a .tex document that compiles cleanly under a real
            LaTeX toolchain"
    (let [document
          {:meta {:title [(str-inline "My Document")] :authors ["Jane Doe"] :date "2026-08-07"}
           :blocks
           [{:t :section :level 1 :heading [(str-inline "Intro")] :attr {:id "sec:intro" :classes [] :props {}}
             :blocks
             [(para (str-inline "Plain text with ")
                    {:t :emph :inlines [(str-inline "emphasis")]}
                    (str-inline ", ")
                    {:t :strong :inlines [(str-inline "strong")]}
                    (str-inline ", ")
                    {:t :strike :inlines [(str-inline "strike")]}
                    (str-inline ", ")
                    {:t :small-caps :inlines [(str-inline "small caps")]}
                    (str-inline ", H")
                    {:t :sub :inlines [(str-inline "2")]}
                    (str-inline "O and E=mc")
                    {:t :sup :inlines [(str-inline "2")]}
                    (str-inline ", ")
                    {:t :code :text "(+ 1 2)"}
                    {:t :line-break}
                    (str-inline "a link:")
                    {:t :link :target "https://example.com" :inlines [(str-inline "example")] :attr empty-attr}
                    (str-inline " and a span: ")
                    {:t :span :inlines [(str-inline "spanned")] :attr {:classes ["hl"] :props {}}})
              (para {:t :image :src "pic.png" :alt "a picture" :attr empty-attr})
              {:t :list :ordered false :tight true :attr empty-attr
               :items [[(para (str-inline "item one"))]
                       [(para (str-inline "item two"))]]}
              {:t :list :ordered true :tight true :attr empty-attr
               :items [[(para (str-inline "first"))] [(para (str-inline "second"))]]}
              {:t :code-block :lang "clojure" :text "(+ 1 2) & 50% _foo_ #tag" :attr empty-attr}
              {:t :block-quote :blocks [(para (str-inline "Quoted wisdom."))]}]}]}
          out (latex/emit-document document)]
      (is (str/starts-with? out "\\documentclass{article}"))
      (is (str/includes? out "\\end{document}"))
      (is (re-find #"\\section\{Intro\}\\label\{sec:intro\}" out))
      (is (re-find #"\\emph\{emphasis\}" out))
      (is (re-find #"\\textbf\{strong\}" out))
      (is (re-find #"\\sout\{strike\}" out))
      (is (re-find #"\\textsc\{small caps\}" out))
      (is (re-find #"H\\textsubscript\{2\}O" out))
      (is (re-find #"mc\\textsuperscript\{2\}" out))
      (is (re-find #"\\texttt\{\(\+ 1 2\)\}" out))
      (is (re-find #"\\href\{https://example.com\}\{example\}" out))
      (is (re-find #"spanned" out))
      (is (re-find #"\\includegraphics\[max width=\\linewidth\]\{pic\.png\}" out))
      (is (re-find #"\\begin\{itemize\}\n\\item item one\n\\item item two\n\\end\{itemize\}" out))
      (is (re-find #"\\begin\{enumerate\}\n\\item first\n\\item second\n\\end\{enumerate\}" out))
      (is (re-find #"\\begin\{verbatim\}\n\(\+ 1 2\) & 50% _foo_ #tag\n\\end\{verbatim\}" out))
      (is (re-find #"\\begin\{quote\}\nQuoted wisdom\.\n\\end\{quote\}" out))
      (is (re-find #"\\title\{My Document\}" out))
      (is (re-find #"\\author\{Jane Doe\}" out))
      (is (re-find #"\\date\{2026-08-07\}" out))
      (is (re-find #"\\maketitle" out))
      (assert-compiles! out))))

(deftest section-number-test
  (let [document {:meta {}
                  :blocks [{:t :section :level 1 :heading [(str-inline "Why hazel")]
                            :attr {:id "sec:why" :classes [] :props {}}
                            :blocks [{:t :section :level 2 :heading [(str-inline "Aside")]
                                      :attr empty-attr :blocks []}
                                     {:t :section :level 2 :heading [(str-inline "Spacing")]
                                      :attr {:id "sec:spacing" :classes [] :props {}}
                                      :blocks []}]}]}
        labels {"sec:why" {:number "1" :text "Section 1"}
                "sec:spacing" {:number "1.1" :text "Section 1.1"}}]
    (testing "TASK-41 AC #2: computed-numbers mode prints the resolver's
              own number in the heading, baked into the starred command's
              argument the way \\caption* and \\tag already bake theirs in
              -- so a number appears WITHOUT article's own counter coming
              back, which is the whole reason the command is starred"
      (let [out (latex/emit-document document {:computed-numbers true :labels labels})]
        (is (str/includes? out "\\section*{1\\quad Why hazel}"))
        (is (str/includes? out "\\subsection*{1.1\\quad Spacing}"))
        (testing "AC #3: and an unnumbered Section prints none, leaving
                  no gap where one would have been"
          (is (str/includes? out "\\subsection*{Aside}")))
        (assert-compiles! out)))
    (testing "native mode is untouched: article prints its own numbers
              there, so a baked-in one would appear beside them"
      (let [out (latex/emit-document document {:labels labels})]
        (is (str/includes? out "\\section{Why hazel}"))
        (is (str/includes? out "\\subsection{Spacing}"))
        (is (not (str/includes? out "{1\\quad Why hazel}")))
        (assert-compiles! out)))))

(deftest section-nesting-test
  (testing "AC #2: nested Sections in the AST map to the correct nested
            sectioning commands"
    (let [document
          {:meta {}
           :blocks
           [{:t :section :level 1 :heading [(str-inline "Chapter")] :attr empty-attr
             :blocks
             [(para (str-inline "top"))
              {:t :section :level 2 :heading [(str-inline "Section")] :attr empty-attr
               :blocks
               [(para (str-inline "middle"))
                {:t :section :level 3 :heading [(str-inline "Subsection")] :attr empty-attr
                 :blocks [(para (str-inline "bottom"))]}]}]}]}
          out (latex/emit-document document)]
      (is (re-find #"\\section\{Chapter\}" out))
      (is (re-find #"\\subsection\{Section\}" out))
      (is (re-find #"\\subsubsection\{Subsection\}" out))
      (is (< (.indexOf out "\\section{Chapter}") (.indexOf out "\\subsection{Section}")
             (.indexOf out "\\subsubsection{Subsection}")))
      (assert-compiles! out)))
  (testing "levels 4 and 5 map to \\paragraph/\\subparagraph, and any
            deeper level clamps to \\subparagraph rather than producing an
            invalid command name"
    (let [document {:meta {}
                    :blocks [{:t :section :level 4 :heading [(str-inline "L4")] :attr empty-attr :blocks []}
                             {:t :section :level 5 :heading [(str-inline "L5")] :attr empty-attr :blocks []}
                             {:t :section :level 9 :heading [(str-inline "Deep")] :attr empty-attr :blocks []}]}
          out (latex/emit-document document)]
      (is (re-find #"\\paragraph\{L4\}" out))
      (is (re-find #"\\subparagraph\{L5\}" out))
      (is (re-find #"\\subparagraph\{Deep\}" out))
      (assert-compiles! out))))

(deftest escaping-test
  (testing "AC #3: every character special to TeX in authored text is
            escaped, and the escaped output compiles cleanly"
    (let [document {:meta {}
                    :blocks [(para (str-inline "a & b % c _ d # e $ f { g } h ^ i ~ j \\ k"))]}
          out (latex/emit-document document)]
      (is (re-find #"a \\& b \\% c \\_ d \\# e \\\$ f \\\{ g \\\} h \\textasciicircum\{\} i \\textasciitilde\{\} j \\textbackslash\{\} k"
                   out))
      (assert-compiles! out)))
  (testing "escaping a code block's inline text (\\texttt) handles the
            same special characters, including a literal backslash"
    (let [document {:meta {} :blocks [(para {:t :code :text "a & b \\ c"})]}
          out (latex/emit-document document)]
      (is (re-find #"\\texttt\{a \\& b \\textbackslash\{\} c\}" out))
      (assert-compiles! out))))

(deftest label-id-safety-test
  (testing "the exact fatal repro from this task's review comment #2: a
            Section id with an underscore and colon (`sec:big_o_notation`,
            an entirely ordinary authored id) must NOT be run through
            escape-tex before landing in \\label{} -- the escaped form
            (`sec:big\\_o\\_notation`) is a confirmed fatal compile error
            (\"Missing \\endcsname inserted\"), while the raw id compiles
            cleanly, since hyperref's own \\label/\\newlabel/.aux round
            trip tolerates a raw underscore but not escape-tex's
            backslash-led replacement of it"
    (let [document {:meta {}
                    :blocks [{:t :section :level 2 :heading [(str-inline "Big O Notation")]
                              :attr {:id "sec:big_o_notation" :classes [] :props {}} :blocks []}]}
          out (latex/emit-document document)]
      (is (re-find #"\\label\{sec:big_o_notation\}" out)
          "id must pass through raw, not escape-tex'd (no backslash before the underscore)")
      (is (not (str/includes? out "big\\_o"))
          "the escape-tex'd form that was confirmed fatal must not appear")
      (assert-compiles! out)))
  (testing "an id combining underscore, ampersand, and colon -- all
            confirmed raw-safe inside \\label{} by the review, since
            hyperref relaxes catcodes for them there -- compiles with
            every one of those characters left completely unescaped"
    (let [document {:meta {}
                    :blocks [{:t :section :level 1 :heading [(str-inline "Ampersand")]
                              :attr {:id "sec:big_o_notation&more" :classes [] :props {}} :blocks []}]}
          out (latex/emit-document document)]
      (is (re-find #"\\label\{sec:big_o_notation&more\}" out)
          "underscore, colon, and ampersand must all appear raw and unescaped")
      (assert-compiles! out)))
  (testing "an id containing the 5 characters confirmed unsafe even when
            escape-tex'd (% # \\ { }) gets a readable placeholder
            substitution instead, and still compiles cleanly"
    (let [document {:meta {}
                    :blocks [{:t :section :level 1 :heading [(str-inline "Percent Hash")]
                              :attr {:id "sec:100%_off#tag\\x{y}" :classes [] :props {}} :blocks []}]}
          out (latex/emit-document document)]
      (is (re-find #"\\label\{sec:100-percent-_off-hash-tag-backslash-x-lbrace-y-rbrace-\}" out))
      (assert-compiles! out))))

(deftest label-id-collision-test
  (testing "the exact non-fatal collision from this task's review comment
            #7: `sec:100%_off` (whose `%` gets substituted into the
            `-percent-` placeholder) and the entirely distinct, literally
            authored `sec:100-percent-_off` used to sanitize to the
            identical label text, since a literal `-` already present in
            a source id was indistinguishable from a `-`-delimited
            placeholder introduced by label-id itself -- doubling every
            literal `-` in the id before substituting the 5 unsafe
            characters makes the two ids sanitize to two different
            labels, and both must still compile cleanly"
    (let [doc-1 {:meta {}
                 :blocks [{:t :section :level 1 :heading [(str-inline "Percent Off")]
                           :attr {:id "sec:100%_off" :classes [] :props {}} :blocks []}]}
          doc-2 {:meta {}
                 :blocks [{:t :section :level 1 :heading [(str-inline "Percent Off Literal")]
                           :attr {:id "sec:100-percent-_off" :classes [] :props {}} :blocks []}]}
          out-1 (latex/emit-document doc-1)
          out-2 (latex/emit-document doc-2)
          label-1 (second (re-find #"\\label\{([^}]*)\}" out-1))
          label-2 (second (re-find #"\\label\{([^}]*)\}" out-2))]
      (is (some? label-1) "doc-1 must emit a \\label{}")
      (is (some? label-2) "doc-2 must emit a \\label{}")
      (is (not= label-1 label-2)
          (str "distinct source ids must not collide onto the same label text, got "
               (pr-str label-1) " for both"))
      (is (= "sec:100--percent--_off" label-2)
          "the literal id's own dashes must come through doubled, not raw")
      (assert-compiles! out-1)
      (assert-compiles! out-2)))
  (testing "label-id is deterministic/pure: the same id always sanitizes
            to the same label text"
    (let [doc {:meta {}
               :blocks [{:t :section :level 1 :heading [(str-inline "Repeat")]
                         :attr {:id "sec:100%_off" :classes [] :props {}} :blocks []}]}]
      (is (= (latex/emit-document doc) (latex/emit-document doc))))))

(deftest codeblock-verbatim-collision-test
  (testing "the exact fatal repro from this task's review comment #3: a
            CodeBlock whose :text contains the literal substring
            \\end{verbatim} closes a plain verbatim environment early and
            corrupts the rest of the document as stray LaTeX -- switching
            to the LaTeX kernel's own differently-terminated verbatim*
            variant sidesteps the collision and compiles cleanly"
    (let [document {:meta {}
                    :blocks [{:t :code-block
                              :text "before\n\\end{verbatim}\nafter { unbalanced"
                              :attr {:classes [] :props {}}}]}
          out (latex/emit-document document)]
      (is (str/includes? out "\\begin{verbatim*}\nbefore\n\\end{verbatim}\nafter { unbalanced\n\\end{verbatim*}")
          "must fall back to verbatim*, not plain verbatim, when :text contains \\end{verbatim}")
      (assert-compiles! out)))
  (testing "a CodeBlock whose :text contains both \\end{verbatim} and
            \\end{verbatim*} has no safe verbatim-family delimiter left
            and raises a documented ex-info rather than silently emitting
            broken LaTeX"
    (let [document {:meta {}
                    :blocks [{:t :code-block
                              :text "\\end{verbatim}\n\\end{verbatim*}"
                              :attr {:classes [] :props {}}}]}]
      (try
        (latex/emit-document document)
        (is false "expected emit-document to throw")
        (catch clojure.lang.ExceptionInfo e
          (is (= ::latex/unrepresentable-code-block (:type (ex-data e)))))))))

(deftest directive-dispatch-test
  (testing "a :directive Block with a native :latex renderer registered
            dispatches to it -- proving the registry mechanism reaches
            all the way through the real emitter, mirroring
            haselnuss.emit.html's own equivalent test"
    (let [reg (registry/register {} {:name "stub" :emit {:latex (fn [_d _t] "\\textbf{stub}")}})
          d {:t :directive :name "stub" :blocks [] :attr empty-attr}
          out (latex/emit-document {:meta {} :blocks [d]} {:registry reg})]
      (is (re-find #"\\textbf\{stub\}" out))
      (assert-compiles! out)))
  (testing "a :directive with no registry supplied raises a documented
            ex-info instead of silently dropping it"
    (let [d {:t :directive :name "stub" :blocks [] :attr empty-attr}]
      (try
        (latex/emit-document {:meta {} :blocks [d]})
        (is false "expected emit-document to throw")
        (catch clojure.lang.ExceptionInfo e
          (is (= ::latex/unsupported-block (:type (ex-data e))))))))
  (testing "a :directive whose name has no registry entry at all also
            raises, naming the directive"
    (let [reg (registry/register {} {:name "other" :emit {:latex (fn [_d _t] "x")}})
          d {:t :directive :name "not-registered" :blocks [] :attr empty-attr}]
      (try
        (latex/emit-document {:meta {} :blocks [d]} {:registry reg})
        (is false "expected emit-document to throw")
        (catch clojure.lang.ExceptionInfo e
          (is (= ::latex/unsupported-block (:type (ex-data e)))))))))

(deftest unsupported-node-test
  (testing "the fallback for a node outside the schema entirely. Every
            Block variant haselnuss.ast actually defines is handled now
            (:thematic-break/:include were the last two, TASK-37) --
            asserted exhaustively by block-coverage-test at the end of
            this namespace, where the fixtures it needs are in scope"
    (try
      (latex/emit-document {:meta {} :blocks [{:t :not-a-real-block}]})
      (is false "expected emit-document to throw for an unknown block type")
      (catch clojure.lang.ExceptionInfo e
        (is (= ::latex/unsupported-block (:type (ex-data e)))))))
  (testing "every Inline variant this namespace claims now renders: with
            CrossRef/Cite claimed by TASK-23 (see cross-ref-mode-test/
            cite-mode-test), MathInline/:note by TASK-22 and the rest by
            TASK-21, no Inline variant in haselnuss.ast is left
            unsupported -- so this branch asserts the fallback still
            exists for a node outside the schema entirely, rather than
            naming a real variant that no longer belongs here"
    (try
      (latex/emit-document {:meta {} :blocks [(para {:t :not-a-real-inline})]})
      (is false "expected emit-document to throw for an unknown inline type")
      (catch clojure.lang.ExceptionInfo e
        (is (= ::latex/unsupported-inline (:type (ex-data e))))))))

(deftest figure-test
  (testing "AC #1: an id-bearing Figure emits a figure environment with a
            caption and a native \\label using its id, and compiles"
    (let [document
          {:meta {}
           :blocks [{:t :figure
                     :content {:t :para :inlines [{:t :image :src "pic.png" :alt "a pic" :attr empty-attr}]}
                     :caption [(str-inline "A caption")]
                     :attr {:id "fig:pic" :classes [] :props {}}}]}
          out (latex/emit-document document)]
      (is (re-find #"\\begin\{figure\}" out))
      (is (re-find #"\\includegraphics\[max width=\\linewidth\]\{pic\.png\}" out))
      (is (re-find #"\\caption\{A caption\}" out))
      (is (re-find #"\\label\{fig:pic\}" out))
      (is (re-find #"\\end\{figure\}" out))
      (assert-compiles! out)))
  (testing "a Figure wrapping non-image content (e.g. a CodeBlock, sec4.3
            places no constraint on Figure content beyond \"a Block\")
            renders that content through the ordinary block visitor, and
            an empty caption omits \\caption entirely rather than emitting
            an empty one"
    (let [document
          {:meta {}
           :blocks [{:t :figure
                     :content {:t :code-block :text "(+ 1 2)" :attr empty-attr}
                     :caption []
                     :attr empty-attr}]}
          out (latex/emit-document document)]
      (is (re-find #"\\begin\{verbatim\}\n\(\+ 1 2\)\n\\end\{verbatim\}" out))
      (is (not (str/includes? out "\\caption")))
      (assert-compiles! out))))

(deftest table-test
  (testing "AC #2: a Table honors column alignment and width from
            colspec, plus a caption and a native \\label using its id"
    (let [document
          {:meta {}
           :blocks [{:t :table
                     :head {:cells [{:blocks [(para (str-inline "A"))]}
                                    {:blocks [(para (str-inline "B"))]}
                                    {:blocks [(para (str-inline "C"))]}]}
                     :rows [{:cells [{:blocks [(para (str-inline "1"))]}
                                     {:blocks [(para (str-inline "2"))]}
                                     {:blocks [(para (str-inline "3"))]}]}]
                     :caption [(str-inline "A table")]
                     :colspec [{:align :left :width "2cm"} {:align :center} {:align :right :width "1cm"}]
                     :attr {:id "tbl:x" :classes [] :props {}}}]}
          out (latex/emit-document document)]
      (is (re-find #"\\begin\{longtable\}\{>\{\\raggedright\\arraybackslash\}p\{2cm\} c >\{\\raggedleft\\arraybackslash\}p\{1cm\}\}"
                   out))
      (is (re-find #"A & B & C \\\\" out))
      (is (re-find #"1 & 2 & 3 \\\\" out))
      (is (re-find #"\\caption\{A table\}" out))
      (is (re-find #"\\label\{tbl:x\}" out))
      (assert-compiles! out)))
  (testing "a Cell's own :align overrides its column's colspec entry
            (mirroring haselnuss.emit.html's own cell-align precedent),
            via \\multicolumn so its column's own width is preserved"
    (let [document
          {:meta {}
           :blocks [{:t :table
                     :head {:cells [{:blocks [(para (str-inline "A"))]}]}
                     :rows [{:cells [{:blocks [(para (str-inline "1"))] :align :center}]}]
                     :caption [] :colspec [{:align :left :width "2cm"}] :attr empty-attr}]}
          out (latex/emit-document document)]
      (is (re-find #"\\multicolumn\{1\}\{>\{\\centering\\arraybackslash\}p\{2cm\}\}\{1\}" out))
      (assert-compiles! out)))
  (testing "an actually-spanning cell (:span > 1) uses \\multicolumn with
            a plain alignment letter, the only way to merge columns in a
            tabular environment at all"
    (let [document
          {:meta {}
           :blocks [{:t :table
                     :head {:cells [{:blocks [(para (str-inline "A"))]}
                                    {:blocks [(para (str-inline "B"))]}]}
                     :rows [{:cells [{:blocks [(para (str-inline "spanned"))] :span 2}]}]
                     :caption [] :colspec [{} {}] :attr empty-attr}]}
          out (latex/emit-document document)]
      (is (re-find #"\\multicolumn\{2\}\{l\}\{spanned\}" out))
      (assert-compiles! out)))
  (testing "an empty/absent colspec falls back to a single default `l`
            column rather than emitting a fatal empty preamble (confirmed
            empirically fatal once the array package is loaded)"
    (let [document
          {:meta {}
           :blocks [{:t :table
                     :head {:cells [{:blocks [(para (str-inline "only"))]}]}
                     :rows [] :caption [] :colspec [] :attr empty-attr}]}
          out (latex/emit-document document)]
      (is (re-find #"\\begin\{longtable\}\{l\}" out))
      (assert-compiles! out))))

(deftest table-column-width-percentage-test
  (let [table (fn [colspec]
                {:meta {}
                 :blocks [{:t :table
                           :head {:cells [{:blocks [(para (str-inline "Approach"))]}
                                          {:blocks [(para (str-inline (str "A description long "
                                                                           "enough that it has to "
                                                                           "wrap inside its own "
                                                                           "column rather than "
                                                                           "running off the page")))]}
                                          {:blocks [(para (str-inline "Ref"))]}]}
                           :rows [{:cells [{:blocks [(para (str-inline "1"))]}
                                           {:blocks [(para (str-inline "2"))]}
                                           {:blocks [(para (str-inline "3"))]}]}]
                           :caption [] :colspec colspec :attr empty-attr}]})]
    (testing "TASK-74 AC #1: a percentage column width becomes that
              fraction of \\linewidth, so the column wraps instead of
              overflowing. A raw % could not be passed through: it would
              open a TeX comment and swallow the rest of the preamble"
      (let [out (latex/emit-document
                 (table [{:width "20%"} {:width "55%"} {:width "25%"}]))]
        (is (str/includes? out
                           (str "\\begin{longtable}{"
                                ">{\\raggedright\\arraybackslash}p{\\dimexpr0.2\\linewidth-2\\tabcolsep} "
                                ">{\\raggedright\\arraybackslash}p{\\dimexpr0.55\\linewidth-2\\tabcolsep} "
                                ">{\\raggedright\\arraybackslash}p{\\dimexpr0.25\\linewidth-2\\tabcolsep}}")))
        (is (not (str/includes? out "p{20%}")))
        (assert-compiles! out))
      (testing "and the -2\\tabcolsep is load-bearing rather than
                decoration: p{} sizes the cell TEXT while array adds 6pt
                of separation either side of every column on top of it,
                so a full 100% was guaranteed to overflow the text block
                -- 36pt on a three-column table, on every row (found by
                review, which also caught that pdflatex exits 0 on an
                overfull hbox, so a compile check cannot see it)"
        (let [out (latex/emit-document
                   (table [{:width "20%"} {:width "55%"} {:width "25%"}]))
              [_ result] (compiles? out)]
          (is (not (str/includes? (str (:out result)) "Overfull"))
              (str "expected no overfull box:\n" (:out result))))))
    (testing "a tiny percentage is a plain decimal, not scientific
              notation: 0.05% came out as 5.0E-4, which pdflatex answers
              with Illegal unit of measure (found by review)"
      (let [out (latex/emit-document (table [{:width "0.05%"} {:width "50%"} {:width "25%"}]))]
        (is (str/includes? out "p{\\dimexpr0.0005\\linewidth-2\\tabcolsep}"))
        (is (not (str/includes? out "E-4")))
        (assert-compiles! out)))
    (testing "a cell's own minipage width goes through the same
              conversion: spliced raw, a percentage opened a TeX comment
              inside \\begin{minipage} and killed the compile. Not
              reachable from a pipe table, whose cells are always one
              Para, but :width is a documented pass-through for a caller
              building a Table by hand (found by review)"
      (let [out (latex/emit-document
                 {:meta {}
                  :blocks [{:t :table
                            :head {:cells [{:blocks [(para (str-inline "A"))]}]}
                            :rows [{:cells [{:blocks [(para (str-inline "one"))
                                                      (para (str-inline "two"))]}]}]
                            :caption [] :colspec [{:width "40%"}] :attr empty-attr}]})]
        (is (str/includes? out "\\begin{minipage}[t]{\\dimexpr0.4\\linewidth-2\\tabcolsep}"))
        (is (not (str/includes? out "{40%}")))
        (assert-compiles! out)))
    (testing "an absolute width is still passed through as the authored
              dimension it is, which is the contract :width has always
              had for a caller building a Table by hand"
      (let [out (latex/emit-document (table [{:width "2cm"} {:width "5cm"} {:width "3cm"}]))]
        (is (str/includes? out "p{2cm}"))
        (assert-compiles! out)))
    (testing "AC #3: a table with no widths emits exactly what it did --
              plain alignment letters, no p{} column anywhere"
      (let [out (latex/emit-document (table [{} {:align :center} {:align :right}]))]
        (is (str/includes? out "\\begin{longtable}{l c r}"))
        (is (not (str/includes? out "arraybackslash")))
        (assert-compiles! out)))))

(deftest math-test
  (testing "AC #3: MathInline's raw :tex passes through verbatim,
            unmodified -- including a real LaTeX math command, never
            escape-tex'd"
    (let [document {:meta {} :blocks [(para {:t :math-inline :tex "\\frac{1}{2} \\& x_i"})]}
          out (latex/emit-document document)]
      (is (re-find #"\\\(\\frac\{1\}\{2\} \\& x_i\n\\\)" out)
          "the raw tex, including its own literal backslash/ampersand, must appear completely unescaped, with the closing delimiter on its own line (review comment #4)")
      (assert-compiles! out)))
  (testing "AC #3: an id-bearing MathBlock renders as a numbered, labelable
            equation environment with its raw :tex verbatim, unmodified"
    (let [document {:meta {}
                    :blocks [{:t :math-block :tex "E = mc^2"
                              :attr {:id "eq:mass" :classes [] :props {}}}]}
          out (latex/emit-document document)]
      (is (re-find #"\\begin\{equation\}\nE = mc\^2\n\\label\{eq:mass\}\n\\end\{equation\}" out))
      (assert-compiles! out)))
  (testing "AC #3: an id-less MathBlock renders as plain unnumbered
            display math instead, with its raw :tex verbatim"
    (let [document {:meta {} :blocks [{:t :math-block :tex "y = x" :attr empty-attr}]}
          out (latex/emit-document document)]
      (is (re-find #"\\\[y = x\n\\\]" out)
          "the closing delimiter must be on its own line (review comment #4)")
      (is (not (str/includes? out "equation")))
      (assert-compiles! out))))

(deftest footnote-test
  (testing "AC #4: a footnote emits as a native LaTeX \\footnote at the
            marker's own position"
    (let [document {:meta {}
                    :blocks [(para (str-inline "Hazelnuts are a pome.")
                                   {:t :note :blocks [(para (str-inline "Actually a nut, not a pome."))]})]}
          out (latex/emit-document document)]
      (is (re-find #"pome\.\\footnote\{Actually a nut, not a pome\.\}" out)
          "the \\footnote command must sit directly at the marker's own position, not elsewhere")
      (assert-compiles! out)))
  (testing "a footnote whose own definition holds more than one paragraph
            still compiles (a blank line inside \\footnote{}'s argument is
            an ordinary \\par, allowed since the kernel's own \\footnote is
            defined \\long)"
    (let [document {:meta {}
                    :blocks [(para (str-inline "Outer.")
                                   {:t :note :blocks [(para (str-inline "Para one."))
                                                      (para (str-inline "Para two."))]})]}
          out (latex/emit-document document)]
      (is (re-find #"\\footnote\{Para one\.\n\nPara two\.\}" out))
      (assert-compiles! out)))
  (testing "a footnote nested inside another footnote, and a footnote
            inside a list item, both compile cleanly"
    (let [document {:meta {}
                    :blocks [(para (str-inline "Outer.")
                                   {:t :note :blocks [(para (str-inline "Inner ")
                                                            {:t :note :blocks [(para (str-inline "nested"))]})]})
                             {:t :list :ordered false :tight true :attr empty-attr
                              :items [[(para (str-inline "item ")
                                             {:t :note :blocks [(para (str-inline "footnoted"))]})]]}]}
          out (latex/emit-document document)]
      (is (re-find #"\\footnote\{Inner \\footnote\{nested\}\}" out))
      (is (re-find #"\\item item \\footnote\{footnoted\}" out))
      (assert-compiles! out))))

(deftest table-pagination-test
  (testing "review comment #1, this task's own fatal gap against its own
            description's literal \"tabular/longtable\" wording: a table
            with 100+ rows now compiles AND actually breaks across
            multiple physical pages via longtable, with the first and
            last row each landing on the page pdflatex actually placed
            them on -- not the single-page float overflow this task's
            review confirmed with a real pdflatex silently rendered ~100
            of 119 rows off the physical page while still reporting a
            clean (exit 0) compile"
    (let [row-count 150
          rows (mapv (fn [i]
                       {:cells [{:blocks [(para (str-inline (str "row " i)))]}
                                {:blocks [(para (str-inline (str "value " i)))]}]})
                     (range 1 (inc row-count)))
          document {:meta {}
                    :blocks [{:t :table
                              :head {:cells [{:blocks [(para (str-inline "Row"))]}
                                             {:blocks [(para (str-inline "Value"))]}]}
                              :rows rows
                              :caption [(str-inline "A large table")]
                              :colspec [{} {}]
                              :attr {:id "tbl:large" :classes [] :props {}}}]}
          out (latex/emit-document document)]
      (is (re-find #"\\begin\{longtable\}" out))
      (is (not (str/includes? out "\\begin{table}"))
          "Table must no longer be wrapped in a non-breaking table float")
      (assert-compiles! out)
      (let [pdf (compile-pdf! out)]
        (is (some? pdf) "expected a PDF to be produced")
        (when pdf
          (let [page-count (pdf-page-count pdf)]
            (is (> page-count 1)
                (str "expected the table to break across multiple physical pages via longtable, got "
                     page-count))
            (is (str/includes? (pdf-page-text pdf 1) "row 1")
                "the first row must land on an early page")
            (is (not (str/includes? (pdf-page-text pdf 1) (str "row " row-count)))
                "the last row must NOT already be crammed onto the first page")
            (is (str/includes? (pdf-page-text pdf page-count) (str "row " row-count))
                "the last row must appear on the final page -- proving it was actually
                 typeset there via real pagination, not silently lost off a single
                 oversized, non-breaking float")))))))

(deftest table-cell-rich-content-test
  (testing "review comment #2, this task's own fatal bug: a table cell
            containing a List, BlockQuote, or MathBlock -- none of which
            can even start inside a plain tabular/longtable cell's own
            restricted horizontal mode -- is now wrapped in a minipage
            and compiles cleanly"
    (doseq [[label content-block]
            [["List" {:t :list :ordered false :tight true :attr empty-attr
                      :items [[(para (str-inline "item one"))]
                              [(para (str-inline "item two"))]]}]
             ["BlockQuote" {:t :block-quote :blocks [(para (str-inline "Quoted wisdom."))]}]
             ["MathBlock" {:t :math-block :tex "E = mc^2" :attr empty-attr}]]]
      (testing label
        (let [document {:meta {}
                        :blocks [{:t :table
                                  :head {:cells [{:blocks [(para (str-inline "Col"))]}]}
                                  :rows [{:cells [{:blocks [content-block]}]}]
                                  :caption [] :colspec [{}] :attr empty-attr}]}
              out (latex/emit-document document)]
          (is (str/includes? out "\\begin{minipage}")
              (str label " cell content must be wrapped in a minipage"))
          (assert-compiles! out)))))
  (testing "a cell with two Para blocks renders as two actual separate
            paragraphs (a blank line inside a minipage's own vertical-mode
            body is a real \\par) instead of collapsing onto one run-on
            line the way a bare tabular/longtable cell would silently do"
    (let [document {:meta {}
                    :blocks [{:t :table
                              :head {:cells [{:blocks [(para (str-inline "Col"))]}]}
                              :rows [{:cells [{:blocks [(para (str-inline "Para A"))
                                                        (para (str-inline "Para B"))]}]}]
                              :caption [] :colspec [{}] :attr empty-attr}]}
          out (latex/emit-document document)]
      (is (re-find #"\\begin\{minipage\}\[t\]\{4cm\}\nPara A\n\nPara B\n\\end\{minipage\}" out)
          "the two paragraphs must remain separated by a blank line (a real \\par) inside
           the minipage, not run together onto one line")
      (assert-compiles! out)
      (let [pdf (compile-pdf! out)]
        (is (some? pdf))
        (when pdf
          (let [page-text (pdf-page-text pdf 1)]
            (is (str/includes? page-text "Para A"))
            (is (str/includes? page-text "Para B"))
            (is (not (str/includes? page-text "Para A Para B"))
                "the two paragraphs must not have collapsed onto one run-on line"))))))
  (testing "a nested Table inside a cell cannot be represented at all --
            longtable's own documentation states it must never be used
            inside a minipage/parbox, since a fixed-size box has no page
            of its own to break across -- and raises a documented ex-info
            rather than emitting LaTeX confirmed to fail"
    (let [nested-table {:t :table
                        :head {:cells [{:blocks [(para (str-inline "x"))]}]}
                        :rows [] :caption [] :colspec [{}] :attr empty-attr}
          document {:meta {}
                    :blocks [{:t :table
                              :head {:cells [{:blocks [(para (str-inline "Col"))]}]}
                              :rows [{:cells [{:blocks [nested-table]}]}]
                              :caption [] :colspec [{}] :attr empty-attr}]}]
      (try
        (latex/emit-document document)
        (is false "expected emit-document to throw")
        (catch clojure.lang.ExceptionInfo e
          (is (= ::latex/unrepresentable-cell (:type (ex-data e)))))))))

(deftest math-percent-escaping-test
  (testing "review comment #4, this task's own fatal bug: a literal,
            un-escaped % in :math-inline's raw :tex (AC #3 forbids
            escaping :tex at all) used to be read by TeX as a comment
            that ate the emitter's own closing \\), confirmed fatal
            (\"Missing $ inserted\") -- the closing delimiter now sits on
            its own line, so the % can only ever eat its own line's
            trailing newline"
    (let [document {:meta {} :blocks [(para {:t :math-inline :tex "x = 5% of y"})]}
          out (latex/emit-document document)]
      (is (re-find #"\\\(x = 5% of y\n\\\)" out)
          "raw tex must remain completely unescaped, with the closing delimiter on its own line")
      (assert-compiles! out)))
  (testing "the same fatal repro for an id-less :math-block's \\[...\\]"
    (let [document {:meta {}
                    :blocks [{:t :math-block :tex "P = 5% probability of success" :attr empty-attr}]}
          out (latex/emit-document document)]
      (is (re-find #"\\\[P = 5% probability of success\n\\\]" out))
      (assert-compiles! out))))

;; TASK-23: native vs computed-numbers cross-reference/citation emission.
;;
;; Every test below that asserts on a *native* citation command also
;; compiles its output through the real BibTeX cycle and reads the
;; resulting PDF text (`compile-with-bibtex!`/`pdf-text`), rather than
;; only matching the emitted `.tex` source. That is the review finding
;; #7 lesson from this task's own first implementation: a `\cite` command
;; that merely exists is not evidence the citation it produces is the
;; right one, and the originally-inverted `:normal`/`:author` mapping and
;; the bracket-injection bug were both invisible to source-only
;; assertions.

(def ^:private csl-bibliography
  "The CSL-JSON bibliography `haselnuss.resolver/resolve-citations` is
  handed in the TASK-23 tests below -- the same two sources `test-bib`
  spells for BibTeX, so a native-mode PDF (built by BibTeX from
  `refs.bib`) and a computed-mode PDF (built by the resolver from this)
  are describing the same reference list."
  {"knuth1984" {:id "knuth1984" :author [{:family "Knuth" :given "Donald"}]
                :issued {:date-parts [[1984]]} :title "The TeXbook"}
   "lamport1986" {:id "lamport1986" :author [{:family "Lamport" :given "Leslie"}]
                  :issued {:date-parts [[1986]]} :title "A Document Preparation System"}})

(defn- resolved-with-bibliography
  "Runs `haselnuss.resolver/resolve-citations` over `document` with
  `csl-bibliography`, returning `[resolved-document emit-opts]` where
  `emit-opts` carries the `:bibliography-id` `emit-document` needs to
  emit a *native* bibliography at all. Every native-citation test below
  goes through this, since a native `\\citep` without a `\\bibliography`
  behind it is exactly the incoherent half-delegated state
  `:native-bibliography` exists to prevent."
  [document]
  (let [{:keys [document bibliography-id]} (resolver/resolve-citations document csl-bibliography)]
    [document {:bibliography-id bibliography-id}]))

(deftest cross-ref-native-mode-test
  (testing "AC #1: in default (native) mode every CrossRef emits a native
            ref/cref command built from its own original :label, with no
            baked-in number anywhere -- even though the resolver has
            already computed one onto :text"
    (let [document {:meta {}
                    :blocks [{:t :section :level 1 :heading [(str-inline "Intro")]
                              :attr {:id "sec:intro" :classes [] :props {}}
                              :blocks [(para {:t :cross-ref :label "sec:intro"
                                              :target "sec:intro" :text "Section 1"})]}]}
          out (latex/emit-document document)]
      (is (re-find #"\\Cref\{sec:intro\}" out))
      (is (not (re-find #"Section 1" out))
          "native mode must not bake the resolver's computed label text into the output")
      (assert-compiles! out)))
  (testing "AC #1: a :suppress-prefix CrossRef emits a bare \\ref (the
            number alone), mirroring the bare-:number text the resolver
            itself produces for that same flag"
    (let [document {:meta {}
                    :blocks [{:t :section :level 1 :heading [(str-inline "Intro")]
                              :attr {:id "sec:intro" :classes [] :props {}}
                              :blocks [(para {:t :cross-ref :label "sec:intro" :suppress-prefix true
                                              :target "sec:intro" :text "1"})]}]}
          out (latex/emit-document document)]
      (is (re-find #"\\ref\{sec:intro\}" out))
      (is (not (re-find #"\\Cref" out)))
      (assert-compiles! out)))
  (testing "AC #1: a native \\Cref resolves to a real number in the
            compiled PDF for every target type this emitter labels --
            Section, Figure, Table and an id-bearing MathBlock -- not
            just for the Section case"
    (let [document {:meta {}
                    :blocks [{:t :section :level 1 :heading [(str-inline "Intro")]
                              :attr {:id "sec:intro" :classes [] :props {}}
                              :blocks [(para (str-inline "Refs: ")
                                             {:t :cross-ref :label "fig:tree" :target "fig:tree"}
                                             (str-inline " ")
                                             {:t :cross-ref :label "tbl:data" :target "tbl:data"}
                                             (str-inline " ")
                                             {:t :cross-ref :label "eq:mass" :target "eq:mass"})
                                       {:t :figure
                                        :content (para {:t :image :src "pic.png" :alt "" :attr empty-attr})
                                        :caption [(str-inline "A tree")]
                                        :attr {:id "fig:tree" :classes [] :props {}}}
                                       {:t :table
                                        :head {:cells [{:blocks [(para (str-inline "A"))]}]}
                                        :rows [{:cells [{:blocks [(para (str-inline "1"))]}]}]
                                        :caption [(str-inline "Data")]
                                        :colspec [{:align :left}]
                                        :attr {:id "tbl:data" :classes [] :props {}}}
                                       {:t :math-block :tex "E = mc^2"
                                        :attr {:id "eq:mass" :classes [] :props {}}}]}]}
          out (latex/emit-document document)
          [ok? pdf log] (compile-with-bibtex! out)]
      (is ok? (str "expected the build to succeed, pdflatex output:\n" log))
      (let [text (pdf-text pdf)]
        (is (str/includes? text "Refs: Figure 1 Table 1 Equation (1)")
            (str "every native \\Cref must resolve to a real number, got:\n" text))
        (is (not (str/includes? text "??"))))))
  (testing "a native \\Cref applies the same label-id sanitization
            attr-label does, so a reference and its target still agree on
            an id containing one of the 5 label-unsafe characters"
    (let [document {:meta {}
                    :blocks [{:t :section :level 1 :heading [(str-inline "Off")]
                              :attr {:id "sec:100%_off" :classes [] :props {}}
                              :blocks [(para {:t :cross-ref :label "sec:100%_off"
                                              :target "sec:100%_off" :text "Section 1"})]}]}
          out (latex/emit-document document)]
      (is (re-find #"\\label\{sec:100-percent-_off\}" out))
      (is (re-find #"\\Cref\{sec:100-percent-_off\}" out))
      (assert-compiles! out))))

(deftest cross-ref-computed-mode-test
  (testing "AC #3: in computed-numbers mode every CrossRef emits the
            resolver's own already-computed label text directly, wrapped
            in a hyperref internal link -- no \\ref/\\Cref anywhere, so
            nothing depends on LaTeX's own numbering"
    (let [document {:meta {}
                    :blocks [{:t :section :level 1 :heading [(str-inline "Intro")]
                              :attr {:id "sec:intro" :classes [] :props {}}
                              :blocks [(para {:t :cross-ref :label "sec:intro"
                                              :target "sec:intro" :text "Section 2.3"})]}]}
          out (latex/emit-document document {:computed-numbers true})]
      (is (re-find #"\\hyperref\[sec:intro\]\{Section 2\.3\}" out))
      (is (not (re-find #"\\Cref|\\ref\{" out))
          "computed mode must not delegate any number back to LaTeX")
      (assert-compiles! out)))
  (testing "AC #3: a computed-mode Figure/Table caption carries the
            resolver's own label text via the caption package's
            unnumbered \\caption*, and an id-bearing MathBlock carries an
            amsmath \\tag -- so a \\hyperref reading \"Figure 2.1\" points
            at a caption that also reads \"Figure 2.1\""
    (let [labels {"fig:tree" {:text "Figure 2.1" :number "2.1"}
                  "tbl:data" {:text "Table 2.2" :number "2.2"}
                  "eq:mass" {:text "Eq. (2.3)" :number "2.3"}}
          document {:meta {}
                    :blocks [{:t :figure
                              :content (para {:t :image :src "pic.png" :alt "" :attr empty-attr})
                              :caption [(str-inline "A tree")]
                              :attr {:id "fig:tree" :classes [] :props {}}}
                             {:t :table
                              :head {:cells [{:blocks [(para (str-inline "A"))]}]}
                              :rows [{:cells [{:blocks [(para (str-inline "1"))]}]}]
                              :caption [(str-inline "Data")]
                              :colspec [{:align :left}]
                              :attr {:id "tbl:data" :classes [] :props {}}}
                             {:t :math-block :tex "E = mc^2"
                              :attr {:id "eq:mass" :classes [] :props {}}}]}
          out (latex/emit-document document {:computed-numbers true :labels labels})]
      (is (re-find #"\\caption\*\{Figure 2\.1: A tree\}" out))
      (is (re-find #"\\caption\*\{Table 2\.2: Data\}" out))
      (is (re-find #"\\tag\{2\.3\}" out))
      (is (re-find #"\\phantomsection\\label\{fig:tree\}" out)
          "a \\caption* steps no counter, so hyperref needs an explicit anchor")
      (assert-compiles! out)))
  (testing "review finding #3: computed mode uses the starred sectioning
            command, so `article` cannot number a heading independently
            of the resolver -- the compiled PDF must show the heading
            text and the reference to it agreeing, with no competing
            LaTeX-assigned section number"
    (let [document {:meta {}
                    :blocks [{:t :section :level 1 :heading [(str-inline "Intro")]
                              :attr empty-attr :blocks []}
                             {:t :section :level 1 :heading [(str-inline "Methods")]
                              :attr {:id "sec:methods" :classes [] :props {}}
                              :blocks [(para (str-inline "Back to ")
                                             {:t :cross-ref :label "sec:methods"
                                              :target "sec:methods" :text "Section 1"})]}]}
          out (latex/emit-document document {:computed-numbers true})
          text (pdf-text (compile-pdf! out))]
      (is (re-find #"\\section\*\{Methods\}\\phantomsection\\label\{sec:methods\}" out))
      (is (str/includes? text "Back to Section 1"))
      (is (not (re-find #"(?m)^\s*2\s+Methods" text))
          (str "no LaTeX-assigned heading number may contradict the resolver, got:\n" text))))
  (testing "native mode leaves every one of those to LaTeX's own
            counters: numbered sectioning/\\caption, no
            \\caption*/\\tag/\\phantomsection"
    (let [document {:meta {}
                    :blocks [{:t :section :level 1 :heading [(str-inline "Intro")]
                              :attr {:id "sec:intro" :classes [] :props {}}
                              :blocks [{:t :figure
                                        :content (para {:t :image :src "pic.png" :alt "" :attr empty-attr})
                                        :caption [(str-inline "A tree")]
                                        :attr {:id "fig:tree" :classes [] :props {}}}
                                       {:t :math-block :tex "E = mc^2"
                                        :attr {:id "eq:mass" :classes [] :props {}}}]}]}
          out (latex/emit-document document {:labels {"fig:tree" {:text "Figure 2.1"}}})]
      (is (re-find #"\\section\{Intro\}" out))
      (is (re-find #"\\caption\{A tree\}" out))
      (is (not (re-find #"\\section\*|\\caption\*|\\tag\{|\\phantomsection" out)))
      (assert-compiles! out))))

(deftest cite-native-mode-test
  (let [document {:meta {:bibliography "refs.json"}
                  :blocks [(para (str-inline "P: ")
                                 {:t :cite :items [{:key "knuth1984" :mode :normal}]})
                           (para (str-inline "T: ")
                                 {:t :cite :items [{:key "knuth1984" :mode :author}]})
                           (para (str-inline "Y: ")
                                 {:t :cite :items [{:key "knuth1984" :mode :year}]})
                           (para (str-inline "M: ")
                                 {:t :cite :items [{:key "knuth1984" :mode :normal}
                                                   {:key "lamport1986" :mode :normal}]})
                           (para (str-inline "N: ")
                                 {:t :cite :items [{:key "lamport1986" :mode :normal
                                                    :prefix [(str-inline "see")]
                                                    :suffix [(str-inline "p. 5")]}]})]}
        [resolved opts] (resolved-with-bibliography document)
        out (latex/emit-document resolved opts)]
    (testing "AC #1: every Cite emits a native natbib cite command over
              its own keys, with no baked-in citation text -- a
              multi-key plain Cite collapses into one \\citep{a,b}"
      (is (re-find #"\\citep\{knuth1984\}" out))
      (is (re-find #"\\citep\{knuth1984,lamport1986\}" out))
      ;; Anchored to the paragraph markers, not a bare #"\[1\]" -- the
      ;; preamble's own \newenvironment{hnunnumbered}[1] (TASK-24) would
      ;; otherwise match one.
      (is (not (re-find #"P: \[1\]" out))
          "native mode must not bake the resolver's own formatted citation text in")
      (is (not (re-find #"M: \[1; 2\]" out))))
    (testing "review finding #1: CiteItem :normal (bracketed [@key],
              parenthetical) maps to natbib's parenthetical \\citep and
              :author (bare @key, author-in-text) to its textual \\citet
              -- NOT to \\citeauthor, which drops the citation marker
              entirely"
      (is (re-find #"\\citet\{knuth1984\}" out))
      (is (re-find #"\\citeyear\{knuth1984\}" out))
      (is (not (re-find #"\\citeauthor" out))))
    (testing "AC #1: an item carrying authored prefix/suffix Inlines uses
              natbib's [pre][post] optional arguments, which describe a
              single citation and so cannot be grouped"
      (is (re-find #"\\citep\[\{see\}\]\[\{p\. 5\}\]\{lamport1986\}" out)))
    (testing "review finding #1/#7: the compiled PDF -- not just the .tex
              source -- shows each mode rendering the same citation shape
              the resolver's own numeric style produces for it"
      (let [[ok? pdf log] (compile-with-bibtex! out)]
        (is ok? (str "expected the bibtex build cycle to succeed, pdflatex output:\n" log))
        (let [text (pdf-text pdf)]
          (is (str/includes? text "P: [1]"))
          (is (str/includes? text "T: Knuth [1]"))
          (is (str/includes? text "Y: 1984"))
          (is (str/includes? text "M: [1, 2]"))
          (is (str/includes? text "N: [see 2, p. 5]")))))))

(deftest cite-bracket-injection-test
  (testing "review finding #2: a citation note containing a literal `]`
            cannot close natbib's own optional argument early and spill
            the rest of the note plus the raw citation key into the body
            text -- checked for BOTH sources of one: authored prose
            (which escape-tex deliberately leaves alone, a bracket being
            an ordinary prose character) and a Link :target (which it
            must leave alone, a URL's own brackets being part of the URL)"
    (let [document {:meta {:bibliography "refs.json"}
                    :blocks [(para (str-inline "N: ")
                                   {:t :cite :items [{:key "knuth1984" :mode :normal
                                                      :suffix [(str-inline "p. 5 [sic]")]}]})
                             (para (str-inline "L: ")
                                   {:t :cite :items [{:key "lamport1986" :mode :normal
                                                      :prefix [(str-inline "cf. [a]")]
                                                      :suffix [{:t :link :target "http://x.test/a[1]"
                                                                :inlines [(str-inline "link")]
                                                                :attr empty-attr}]}]})]}
          [resolved opts] (resolved-with-bibliography document)
          out (latex/emit-document resolved opts)
          [ok? pdf log] (compile-with-bibtex! out)]
      (is (re-find #"\\citep\[\]\[\{p\. 5 \[sic\]\}\]\{knuth1984\}" out)
          "an absent prefix still emits natbib's two-argument form, the only one brace-wrapping protects")
      (is (re-find #"\\citep\[\{cf\. \[a\]\}\]\[\{\\href\{http://x\.test/a\[1\]\}" out)
          "a URL's own brackets pass through verbatim, protected by the wrapping braces rather than by escaping")
      (is ok? (str "expected the build to succeed, pdflatex output:\n" log))
      (let [text (pdf-text pdf)]
        (is (str/includes? text "N: [1, p. 5 [sic]]")
            (str "the whole note must survive inside the citation, got:\n" text))
        (is (str/includes? text "L: [cf. [a] 2, link]")
            (str "a Link-bearing note must survive too, got:\n" text))
        (is (not (str/includes? text "knuth1984"))
            "the raw citation key must never leak into the rendered text")))))

(deftest cite-empty-items-test
  (testing "review finding #7: a schema-valid Cite with no items at all
            has no key to cite, so both modes render whatever the
            resolver made of it rather than silently emitting nothing"
    (let [cite {:t :cite :items [] :text [(str-inline "[?]")]}
          document {:meta {:bibliography "refs.json"} :blocks [(para (str-inline "X: ") cite)]}]
      (doseq [opts [{} {:computed-numbers true} {:bibliography-id "sec:bibliography"}]]
        (let [out (latex/emit-document document opts)]
          (is (re-find #"X: \[\?\]" out) (str "for opts " (pr-str opts)))
          (is (not (re-find #"\\citep\{\}" out))))))))

(deftest bibliography-native-mode-test
  (let [document {:meta {:bibliography "refs.json"}
                  :blocks [(para {:t :cite :items [{:key "knuth1984" :mode :normal}]})
                           (para {:t :cite :items [{:key "lamport1986" :mode :normal}]})]}
        [resolved opts] (resolved-with-bibliography document)
        out (latex/emit-document resolved opts)]
    (testing "AC #2: the resolver-generated bibliography Section is
              replaced by \\bibliographystyle/\\bibliography, so BibTeX --
              not this emitter -- builds the reference list from the same
              bibliography source meta.bibliography names"
      (is (re-find #"\\bibliographystyle\{unsrtnat\}" out))
      (is (re-find #"\\bibliography\{refs\}" out)
          "meta.bibliography's extension is stripped, since BibTeX appends .bib itself")
      (is (not (re-find #"Bibliography" out))
          "the resolver-generated Section's own heading must not survive into native mode")
      (is (not (re-find #"The TeXbook" out))
          "no resolver-generated reference text may appear in native mode"))
    (testing "AC #2: end to end, a real bibtex run turns those commands
              into a real reference list in the compiled PDF"
      (let [[ok? pdf log] (compile-with-bibtex! out)]
        (is ok? (str "expected the bibtex build cycle to succeed, pdflatex output:\n" log))
        (let [text (pdf-text pdf)]
          (is (str/includes? text "References"))
          (is (str/includes? text "Knuth"))
          (is (str/includes? text "Lamport")))))
    (testing "review finding #5: \\bibliographystyle and natbib's own
              package options follow the document's declared cslStyle, so
              a native citation renders in the same style the resolver
              would have -- author-date gets round parens and plainnat"
      (let [author-date (assoc-in resolved [:meta :csl-style] "author-date")
            out' (latex/emit-document author-date opts)]
        (is (re-find #"\\usepackage\[round\]\{natbib\}" out'))
        (is (re-find #"\\bibliographystyle\{plainnat\}" out'))
        (let [[ok? pdf log] (compile-with-bibtex! out')]
          (is ok? (str "expected the build to succeed, pdflatex output:\n" log))
          (is (str/includes? (pdf-text pdf) "(Knuth, 1984)")))))
    (testing "review finding #4: with no :bibliography-id (or no
              derivable bib resource) this document will emit no
              \\bibliography at all, so the in-text citations degrade
              WITH the reference list -- the resolver's own formatted
              text beside the resolver's own generated Section, never
              \\citep commands with nothing behind them"
      (doseq [[label doc emit-opts] [["no :bibliography-id" resolved {}]
                                     ["no bib resource" (assoc resolved :meta {}) opts]]]
        (let [out' (latex/emit-document doc emit-opts)]
          (is (not (re-find #"\\bibliography\{|\\citep" out')) label)
          (is (re-find #"The TeXbook" out') label)
          (is (re-find #"\[1\]" out') label)
          (assert-compiles! out'))))))

(deftest bibliography-computed-mode-test
  (let [document {:meta {:bibliography "refs.json"}
                  :blocks [(para {:t :cite :items [{:key "knuth1984" :mode :normal}]})]}
        [resolved opts] (resolved-with-bibliography document)
        out (latex/emit-document resolved (assoc opts :computed-numbers true))]
    (testing "AC #3: computed mode emits the resolver's own already-
              formatted citation text, and renders the generated
              bibliography Section as the ordinary Section it is -- never
              handing anything to BibTeX"
      (is (re-find #"\\section\*\{Bibliography\}" out))
      (is (re-find #"The TeXbook" out))
      (is (re-find #"\[1\]" out) "the resolver's own numeric-style in-text citation text")
      (is (not (re-find #"\\bibliography\{|\\citep|\\citet" out))))
    (testing "the computed-mode document is still a real, compilable
              LaTeX document"
      (assert-compiles! out))))

(deftest dangling-reference-and-citation-test
  (let [document {:meta {:bibliography "refs.json"}
                  :blocks [(para (str-inline "See ")
                                 {:t :cross-ref :label "fig:missing"})
                           (para (str-inline "And ")
                                 {:t :cite :items [{:key "nosuchkey" :mode :normal}]})
                           (para (str-inline "Real ")
                                 {:t :cite :items [{:key "knuth1984" :mode :normal}]})]}
        {resolved :document diagnostics :diagnostics bib-id :bibliography-id}
        (resolver/resolve-document document {:bibliography csl-bibliography})]
    (testing "the fixture really is dangling on both counts, per the
              resolver's own diagnostics -- so the two mode assertions
              below are exercising AC #4's actual case"
      (is (= #{:dangling-cross-ref :dangling-citation} (set (map :type diagnostics)))))
    (testing "AC #4: native mode emits \\Cref/\\citep of the unresolved
              label/key; pdflatex and natbib render their own ??/?
              placeholders plus non-fatal warnings, and the build still
              succeeds"
      (let [out (latex/emit-document resolved {:bibliography-id bib-id})
            [ok? pdf log] (compile-with-bibtex! out)]
        (is (re-find #"\\Cref\{fig:missing\}" out))
        (is (re-find #"\\citep\{nosuchkey\}" out))
        (is ok? (str "a dangling reference must not fail the build, pdflatex output:\n" log))
        (is (str/includes? (pdf-text pdf) "??"))))
    (testing "AC #4: computed mode emits the resolver's own \"??\"
              placeholder as plain text, unlinked -- there is nothing
              valid to link to -- and also compiles"
      (let [out (latex/emit-document resolved {:bibliography-id bib-id :computed-numbers true})]
        (is (re-find #"See \?\?" out))
        (is (not (re-find #"\\hyperref" out)))
        (assert-compiles! out)))))

;; TASK-24: the directive-name -> LaTeX-environment mapping table.

(defn- stub-latex-renderer
  "A named renderer for the Var-backed-renderer case (review finding #4)
  -- `(fn? #'stub-latex-renderer)` is false while `ifn?` is true, which
  is exactly the shape a `fn?` discriminator used to reject."
  [_directive _target]
  "\\textbf{var-stub}")

(defn- directive
  [directive-name id & blocks]
  (cond-> {:t :directive :name directive-name :blocks (vec blocks) :attr empty-attr}
    id (assoc :attr {:id id :classes [] :props {}})))

(def ^:private environment-registry
  "A registry carrying exactly the built-in mapping table's own entries
  -- what a caller must register for `haselnuss.lower/lower` to keep a
  mapped directive as a :directive for the :latex target at all (see
  latex/register-environments)."
  (latex/register-environments {}))

(deftest directive-environment-mapping-test
  (testing "AC #1: a directive with a mapped name emits the corresponding
            LaTeX environment wrapping its own content -- for the amsthm
            \\newtheorem mappings and for the two TASK-40 wrapped in
            counters of their own (proof, admonition)"
    (let [document {:meta {}
                    :blocks [(directive "theorem" "thm:main" (para (str-inline "All things end.")))
                             (directive "proof" nil (para (str-inline "Trivial.")))
                             (directive "admonition" nil (para (str-inline "Careful.")))]}
          out (latex/emit-document document {:registry environment-registry})]
      (is (re-find #"(?s)\\begin\{theorem\}\\label\{thm:main\}\nAll things end\.\n\\end\{theorem\}" out))
      (is (re-find #"(?s)\\begin\{hnproof\}\nTrivial\.\n\\end\{hnproof\}" out))
      (is (re-find #"(?s)\\begin\{hnadmonition\}\nCareful\.\n\\end\{hnadmonition\}" out))
      (is (re-find #"\\newenvironment\{hnproof\}\{\\refstepcounter\{hnproof\}\\begin\{proof\}" out)
          "hnproof still delegates to amsthm's proof, so its typesetting and QED box survive")
      (is (re-find #"\\newtheorem\{theorem\}\{Theorem\}" out)
          "each mapped environment's own declaration comes from its table entry, not from the emitter")
      (assert-compiles! out)))
  (testing "AC #1: the mapped environment's content goes through the
            ordinary block visitor, so arbitrary nested Blocks -- and a
            nested mapped directive -- render with no special casing"
    (let [document {:meta {}
                    :blocks [(directive "theorem" nil
                                        (para (str-inline "Outer."))
                                        {:t :list :ordered false :tight true :attr empty-attr
                                         :items [[(para (str-inline "a"))]]}
                                        (directive "proof" nil (para (str-inline "Inner."))))]}
          out (latex/emit-document document {:registry environment-registry})]
      (is (re-find #"(?s)\\begin\{theorem\}.*\\begin\{itemize\}.*\\begin\{hnproof\}.*\\end\{theorem\}" out))
      (assert-compiles! out)))
  (testing "TASK-23 review finding #6, closed here: a mapped directive
            emits a real \\label, so the built-in thm kind is a genuine
            \\Cref target rather than rendering as LaTeX's own ??"
    (let [document {:meta {}
                    :blocks [(directive "theorem" "thm:main" (para (str-inline "All things end.")))
                             (para (str-inline "See ") {:t :cross-ref :label "thm:main"
                                                        :target "thm:main"})]}
          out (latex/emit-document document {:registry environment-registry})
          ;; Three pdflatex passes, not one: a native \\Cref only resolves
          ;; once the .aux from a previous run exists.
          [ok? pdf log] (compile-with-bibtex! out)
          text (pdf-text pdf)]
      (is ok? (str "expected the build to succeed, pdflatex output:\n" log))
      (is (str/includes? text "See Theorem 1")
          (str "a \\Cref to a mapped directive must resolve to a real number, got:\n" text))
      (is (not (str/includes? text "??"))))))

(deftest directive-environment-data-driven-test
  (testing "AC #2: the mapping is data -- a name the built-in table has
            never heard of renders as its environment purely by being
            added to :directive-environments, with no change to any
            rendering function"
    (let [environments (assoc latex/default-directive-environments
                              "algorithm" {:environment "hnalgorithm" :counter true
                                           :title "Algorithm" :packages ["amsthm"]
                                           :preamble "\\newtheorem{hnalgorithm}{Algorithm}"})
          reg (latex/register-environments {} environments)
          document {:meta {}
                    :blocks [(directive "algorithm" "alg:sort" (para (str-inline "Sort it.")))
                             (para (str-inline "ALG=") {:t :cross-ref :label "alg:sort"
                                                        :target "alg:sort"})]}
          out (latex/emit-document document {:registry reg :directive-environments environments})
          [ok? pdf log] (compile-with-bibtex! out)]
      (is (re-find #"(?s)\\begin\{hnalgorithm\}\\label\{alg:sort\}\nSort it\.\n\\end\{hnalgorithm\}" out))
      (is (re-find #"\\newtheorem\{hnalgorithm\}\{Algorithm\}" out)
          "a custom entry's own declaration reaches the preamble from the table alone")
      (is ok? (str "expected the build to succeed, pdflatex output:\n" log))
      ;; Review finding #2: the point of a data-only entry is that it is
      ;; genuinely REFERENCEABLE, not merely that it compiles -- the
      ;; original version of this fixture emitted a counter-less
      ;; environment while claiming a counter, and \\Cref printed
      ;; "Section 1" with no warning at all.
      (is (str/includes? (pdf-text pdf) "ALG=Algorithm 1")
          (str "a data-only mapped entry must be a real \\Cref target, got:\n" (pdf-text pdf)))))
  (testing "AC #2: :directive-environments replaces the built-in table
            rather than merging, so a mapping can be removed as easily as
            added -- a name dropped from it is no longer mapped"
    (let [document {:meta {} :blocks [(directive "theorem" nil (para (str-inline "x")))]}]
      (try
        (latex/emit-document document {:directive-environments {}})
        (is false "expected emit-document to throw for an unmapped directive")
        (catch clojure.lang.ExceptionInfo e
          (is (= ::latex/unsupported-block (:type (ex-data e))))))))
  (testing "a real registered :latex renderer function still wins over a
            built-in mapping of the same name, so an extension can always
            override one"
    (let [reg (registry/register environment-registry
                                 {:name "theorem" :emit {:latex (fn [_d _t] "\\textbf{custom}")}})
          document {:meta {} :blocks [(directive "theorem" nil (para (str-inline "x")))]}
          out (latex/emit-document document {:registry reg})]
      (is (re-find #"\\textbf\{custom\}" out))
      (is (not (re-find #"\\begin\{theorem\}" out)))
      (assert-compiles! out)))
  (testing "review finding #4: a Var-backed renderer -- the normal idiom
            for a REPL-redefinable one, and accepted by the HTML emitter
            -- also wins. (fn? #'a-var) is false, so discriminating the
            marker with fn? used to silently render the built-in
            environment instead"
    (let [reg (registry/register environment-registry
                                 {:name "theorem" :emit {:latex #'stub-latex-renderer}})
          document {:meta {} :blocks [(directive "theorem" nil (para (str-inline "x")))]}
          out (latex/emit-document document {:registry reg})]
      (is (re-find #"\\textbf\{var-stub\}" out))
      (is (not (re-find #"\\begin\{theorem\}" out)))
      (assert-compiles! out))))

(deftest environment-marker-test
  (testing "review finding #5: registering the mapping table merges into
            an existing same-named entry instead of replacing it, so
            turning on LaTeX environments cannot silently delete an
            extension's :html renderer, :kind fragment or :lower rule"
    (let [base (registry/register {} {:name "admonition"
                                      :kind {:adm {:counter :global}}
                                      :emit {:html (fn [_d _t] "<aside/>")}
                                      :lower (fn [_d _t] nil)})
          merged (latex/register-environments base)
          entry (registry/lookup merged "admonition")]
      (is (= {:adm {:counter :global}} (:kind entry)))
      (is (some? (:lower entry)))
      (is (some? (registry/renderer entry :html)))
      (is (= latex/environment-renderer (registry/renderer entry :latex)))))
  (testing "review finding #3: the marker is never rendered as content.
            It used to be an inert keyword, which is itself IFn, so any
            consumer following the established convention of calling a
            non-nil :emit value spliced the literal :latex into its
            output; it now fails loudly by name instead"
    (let [document {:meta {} :blocks [(directive "theorem" nil (para (str-inline "x")))]}
          out (latex/emit-document document {:registry environment-registry})]
      (is (not (str/includes? out ":latex"))
          "the marker must never reach the output as text"))
    (try
      (latex/environment-renderer {:t :directive :name "theorem"} :html)
      (is false "expected the marker to throw when called as a renderer")
      (catch clojure.lang.ExceptionInfo e
        (is (= ::latex/environment-marker-called (:type (ex-data e))))))))

(deftest directive-environment-preamble-test
  (testing "review finding #6: everything the mapping table contributes
            to the preamble comes FROM the table -- an empty one emits no
            amsthm, no theorem declarations and no unnumbered-environment
            definition, so a custom entry loading a conflicting package
            is not sabotaged by a hardcoded one"
    (let [out (latex/emit-document {:meta {} :blocks []} {:directive-environments {}})]
      (is (not (re-find #"amsthm|newtheorem|hnunnumbered|hnadmonition" out)))
      (assert-compiles! out)))
  (testing "review finding #6: an entry's own :packages are loaded BEFORE
            hyperref, which this namespace's load order documents as
            order-critical -- an entry cannot declare them in :preamble,
            which lands after it"
    (let [environments {"note" {:environment "hnnote" :packages ["mdframed"]
                                :preamble "\\newenvironment{hnnote}{\\begin{mdframed}}{\\end{mdframed}}"}}
          reg (latex/register-environments {} environments)
          document {:meta {} :blocks [(directive "note" nil (para (str-inline "Careful.")))]}
          out (latex/emit-document document {:registry reg :directive-environments environments})]
      (is (< (.indexOf out "\\usepackage{mdframed}") (.indexOf out "\\usepackage{hyperref}")))
      (is (< (.indexOf out "\\usepackage{hyperref}") (.indexOf out "\\newenvironment{hnnote}")))
      (assert-compiles! out)))
  (testing "review finding #7: a :packages/:preamble value shared by two
            entries is emitted once, not once per entry -- the built-in
            table's four \\newtheorem entries all share amsthm"
    (let [out (latex/emit-document {:meta {} :blocks []} {})]
      (is (= 1 (count (re-seq #"\\usepackage\{amsthm\}" out))))))
  (testing "review finding #7: every built-in mapped environment actually
            renders and compiles, not just the two the other tests reach"
    (let [document {:meta {}
                    :blocks (mapv (fn [n] (directive n nil (para (str-inline (str n " body.")))))
                                  ["theorem" "lemma" "corollary" "definition" "proof" "admonition"])}
          out (latex/emit-document document {:registry environment-registry})]
      (doseq [env ["theorem" "lemma" "corollary" "definition" "proof" "hnadmonition"]]
        (is (str/includes? out (str "\\begin{" env "}")) env))
      (assert-compiles! out))))

(deftest directive-environment-computed-mode-test
  (testing "a counter-carrying mapped environment becomes the unnumbered
            variant in computed-numbers mode, headed by the resolver's own
            label text -- so a \\hyperref reading \"Theorem 4\" points at a
            head reading \"Theorem 4\", not at LaTeX's own theorem counter"
    (let [document {:meta {}
                    :blocks [(directive "theorem" "thm:main" (para (str-inline "All things end.")))
                             (directive "theorem" nil (para (str-inline "Unlabeled.")))
                             (para (str-inline "See ") {:t :cross-ref :label "thm:main"
                                                        :target "thm:main" :text "Theorem 4"})]}
          out (latex/emit-document document {:registry environment-registry
                                             :computed-numbers true
                                             :labels {"thm:main" {:text "Theorem 4" :number "4"}}})
          text (pdf-text (compile-pdf! out))]
      (is (re-find #"\\begin\{hnunnumbered\}\{Theorem 4\}\\phantomsection\\label\{thm:main\}" out))
      (is (re-find #"\\begin\{hnunnumbered\}\{Theorem\}\n" out)
          "a mapped directive the resolver numbered nothing for falls back to the entry's own title word")
      (is (not (re-find #"\\begin\{theorem\}" out))
          "computed mode must not use LaTeX's own theorem counter")
      (is (str/includes? text "Theorem 4. All things end."))
      (is (str/includes? text "See Theorem 4"))))
  (testing "a mapped environment with no counter of its own keeps the
            same environment name in both modes -- there is nothing to
            disagree about -- and gets \\phantomsection\\label in computed
            mode, where the label is only a \\hyperref jump target.
            Since TASK-40 no BUILT-IN entry is counter-less, so this
            uses a custom one; that is also the case the rule was
            written for (a caller's own \\newenvironment entry)"
    (let [environments (assoc latex/default-directive-environments
                              "aside" {:environment "hnaside" :lexicon-kind "asd"
                                       :preamble (str "\\newenvironment{hnaside}"
                                                      "{\\begin{quote}}{\\end{quote}}")})
          document {:meta {} :blocks [(directive "aside" "asd:x" (para (str-inline "By the way.")))]}
          out (latex/emit-document document {:registry (latex/register-environments {} environments)
                                             :directive-environments environments
                                             :computed-numbers true})]
      (is (re-find #"\\begin\{hnaside\}\\phantomsection\\label\{asd:x\}" out))
      (assert-compiles! out)))
  (testing "TASK-40: proof and admonition carry counters now, so computed
            mode must swap them for an uncounted form rather than keep a
            LaTeX counter that disagrees with the resolver -- but for
            their OWN uncounted form, not hnunnumbered, so amsthm's QED
            box and the admonition's quote survive (found by review)"
    (let [document {:meta {}
                    :blocks [(directive "proof" "prf:x" (para (str-inline "Trivial.")))
                             (directive "admonition" "adm:y" (para (str-inline "Careful.")))]}
          out (latex/emit-document document {:registry environment-registry
                                             :computed-numbers true
                                             :labels {"prf:x" {:text "Proof 3"}
                                                      "adm:y" {:text "Note 2"}}})]
      (is (re-find #"\\begin\{hnproofstar\}\{Proof 3\}\\phantomsection\\label\{prf:x\}" out))
      (is (re-find #"\\begin\{hnadmonitionstar\}\{Note 2\}\\phantomsection\\label\{adm:y\}" out))
      (is (not (str/includes? out "\\begin{hnunnumbered}"))
          "these two declare their own uncounted form, so the generic one must not be used")
      (is (re-find #"\\newenvironment\{hnproofstar\}\[1\]\{\\begin\{proof\}\[#1\]\}" out)
          "which is still amsthm's proof, so it keeps its QED box")
      (is (re-find #"\\newenvironment\{hnadmonitionstar\}\[1\]\{\\begin\{quote\}" out)
          "and still a quote, so it keeps its indentation")
      (assert-compiles! out))))

(deftest counterless-environment-label-test
  (testing "TASK-24 review finding #1, still the rule: in NATIVE mode a
            counter-less mapped environment gets NO \\label. A label with
            no counter behind it binds to whatever counter was stepped
            last, so a \\Cref to one printed the enclosing section's
            number as though it were the answer -- with zero build
            warnings. Omitting the label degrades it to LaTeX's own
            visible ??. Exercised with a CUSTOM entry, since TASK-40 gave
            every built-in a counter; a caller's own \\newenvironment
            entry is the case the rule now protects."
    (let [environments (assoc latex/default-directive-environments
                              "aside" {:environment "hnaside" :lexicon-kind "asd"
                                       :preamble (str "\\newenvironment{hnaside}"
                                                      "{\\begin{quote}}{\\end{quote}}")})
          document {:meta {}
                    :blocks [{:t :section :level 1 :heading [(str-inline "One")] :attr empty-attr
                              :blocks []}
                             {:t :section :level 1 :heading [(str-inline "Two")] :attr empty-attr
                              :blocks [(directive "aside" "asd:x" (para (str-inline "By the way.")))
                                       (directive "theorem" "thm:t" (para (str-inline "Thm body.")))
                                       (para (str-inline "ASD=")
                                             {:t :cross-ref :label "asd:x" :target "asd:x"}
                                             (str-inline " THM=")
                                             {:t :cross-ref :label "thm:t" :target "thm:t"})]}]}
          out (latex/emit-document document {:registry (latex/register-environments {} environments)
                                             :directive-environments environments})
          [ok? pdf log] (compile-with-bibtex! out)
          text (pdf-text pdf)]
      (is (not (re-find #"\\begin\{hnaside\}\\label" out))
          "a counter-less environment must not carry a label in native mode")
      (is (re-find #"\\begin\{theorem\}\\label\{thm:t\}" out)
          "a counter-carrying one still does")
      (is ok? (str "expected the build to succeed, pdflatex output:\n" log))
      (is (str/includes? text "ASD=??")
          (str "the counter-less reference must be visibly broken, not a wrong section number, got:\n" text))
      (is (not (str/includes? text "ASD=Section"))
          "the pre-fix bug printed the enclosing section here")
      (is (str/includes? text "THM=Theorem 1"))))
  (testing "computed mode is unaffected: the printed text comes from the
            resolver, so the label is only a jump target and is safe"
    (let [document {:meta {}
                    :blocks [(directive "proof" "prf:x" (para (str-inline "Trivial.")))
                             (para (str-inline "PRF=") {:t :cross-ref :label "prf:x"
                                                        :target "prf:x" :text "Proof 1"})]}
          out (latex/emit-document document {:registry environment-registry :computed-numbers true
                                             :labels {"prf:x" {:text "Proof 1"}}})
          text (pdf-text (compile-pdf! out))]
      (is (str/includes? text "PRF=Proof 1")))))

(defn- unligature
  "`s` normalized so an assertion compares what a PDF *typeset* rather
  than what `pdftotext` managed to extract. Applied to both sides of a
  comparison, never to only one.

  Two things get lost in extraction. A TeX f-ligature comes back as a
  control byte rather than as its letters -- \"Definition 1\" extracts
  as \"De<0x1C>nition 1\" -- so ligatures and control bytes are dropped
  from both the expected word and the actual text, leaving a stable
  \"Denition 1\" on each side. And the non-breaking space cleveref
  writes between a reference's name and its number (`Definition~1`)
  comes back as U+00A0, which no plain-space pattern matches."
  [s]
  (-> s
      (str/replace #"ffi|ffl|fi|fl|ff" "")
      (str/replace #"[\x00-\x08\x0b-\x1f]" "")
      (str/replace "\u00a0" " ")))

(deftest every-mapped-directive-is-referenceable-test
  (testing "TASK-40 AC #5: a cross-reference to every mapped directive
            resolves in NATIVE mode to a real number in the compiled
            PDF. Asserted on the PDF text rather than on the presence of
            a \\label, because an unbound label prints the enclosing
            section's number instead -- which is exactly the failure
            proof and admonition used to have"
    (let [;; `:sub` entries are excluded, and cannot be included: a
          ;; panel is by definition laid out BY a float, and
          ;; `subcaption` makes writing one anywhere else a fatal
          ;; pdflatex error ("subfigure outside float") rather than a
          ;; badly numbered reference. A panel's own reference is
          ;; covered where it can be -- inside its figure, in
          ;; `subfigure-reference-test`.
          names (sort (remove #(:sub (get latex/default-directive-environments %))
                              (keys latex/default-directive-environments)))
          kinds (map #(:lexicon-kind (get latex/default-directive-environments %)) names)
          document {:meta {}
                    :blocks [{:t :section :level 1 :heading [(str-inline "One")] :attr empty-attr
                              :blocks []}
                             {:t :section :level 1 :heading [(str-inline "Two")] :attr empty-attr
                              :blocks (into (mapv (fn [directive-name kind]
                                                    (directive directive-name (str kind ":x")
                                                               (para (str-inline "Body."))))
                                                  names kinds)
                                            [(apply para
                                                    (mapcat (fn [kind]
                                                              [(str-inline (str " " kind "="))
                                                               {:t :cross-ref :label (str kind ":x")
                                                                :target (str kind ":x")}])
                                                            kinds))])}]}
          out (latex/emit-document document {:registry environment-registry})
          [ok? pdf log] (compile-with-bibtex! out)
          text (pdf-text pdf)]
      (is ok? (str "expected the build to succeed, pdflatex output:\n" log))
      (doseq [[directive-name kind] (map vector names kinds)]
        (let [title (:title (get latex/default-directive-environments directive-name))]
          ;; Both sides go through `unligature`: pdftotext drops the `fi`
          ;; ligature, so a PDF genuinely reading "Definition 1" comes
          ;; back as "Denition 1". The kind marker goes through it too
          ;; -- `fig=` is itself an f-ligature and extracts as `g=`.
          (is (re-find (re-pattern (str (unligature kind) "=" (unligature title) " \\d"))
                       (unligature text))
              (str directive-name ": expected a real number, got:\n" text))
          (is (not (re-find (re-pattern (str (unligature kind) "=(\\?\\?|Section)"))
                            (unligature text)))
              (str directive-name ": reference is broken or points at a section, got:\n" text)))))))

(deftest counterless-block-label-test
  (testing "TASK-40, the other half of the same gap: a List or CodeBlock
            carrying an id the resolver numbered is anchored in
            computed-numbers mode, where the reference text comes from
            the resolver and \\phantomsection makes the anchor this node"
    (let [document {:meta {}
                    :blocks [{:t :list :ordered false :tight true :items [[(para (str-inline "a"))]]
                              :attr {:id "thm:steps" :classes [] :props {}}}
                             {:t :code-block :text "x = 1"
                              :attr {:id "thm:snippet" :classes [] :props {}}}
                             (para (str-inline "L=") {:t :cross-ref :label "thm:steps"
                                                      :target "thm:steps" :text "Theorem 1"}
                                   (str-inline " C=") {:t :cross-ref :label "thm:snippet"
                                                       :target "thm:snippet" :text "Theorem 2"})]}
          out (latex/emit-document document {:computed-numbers true})
          text (pdf-text (compile-pdf! out))]
      (is (re-find #"\\phantomsection\\label\{thm:steps\}\\begin\{itemize\}" out))
      (is (re-find #"\\phantomsection\\label\{thm:snippet\}\\begin\{verbatim\}" out))
      (is (str/includes? text "L=Theorem 1"))
      (is (str/includes? text "C=Theorem 2"))))
  (testing "and NOT in native mode, deliberately: neither construct steps
            a LaTeX counter, so a \\label would bind to whatever was
            stepped last and print the enclosing section's number as
            though it were the answer -- the same silently-wrong output
            render-environment refuses"
    (let [document {:meta {}
                    :blocks [{:t :list :ordered false :tight true :items [[(para (str-inline "a"))]]
                              :attr {:id "thm:steps" :classes [] :props {}}}
                             {:t :code-block :text "x = 1"
                              :attr {:id "thm:snippet" :classes [] :props {}}}]}
          out (latex/emit-document document {})]
      (is (not (str/includes? out "\\label{thm:steps}")))
      (is (not (str/includes? out "\\label{thm:snippet}")))
      (assert-compiles! out))))

(deftest untypesettable-character-test
  (testing "TASK-76 AC #1/#2: a character the emitted LaTeX cannot
            typeset produces a diagnostic naming it and saying where it
            came from. Before this the build exited 0, said it wrote the
            file, and pdflatex died two commands later in someone else's
            output, naming a character the author may never have typed"
    (let [[warning :as warnings]
          (latex/untypesettable-character-diagnostics "Broken: ı́ here" :bibliography)]
      (is (= 1 (count warnings)))
      (is (= :untypesettable-character (:type warning)))
      (is (= 0x0301 (:code-point warning)))
      (is (= :bibliography (:origin warning)))
      (is (str/includes? (:message warning) "U+0301 COMBINING ACUTE ACCENT"))
      (is (str/includes? (:message warning) "the generated bibliography")
          "the origin is in the message, not only in the map: csl-json->bibtex writes a
           file from data the author never opens, so naming the document would send them
           to the wrong one")
      (is (str/includes? (:message warning) "Broken:")
          "and an excerpt, since a thesis is 300 pages of prose to search by eye")))
  (testing "the same character from the document says so instead"
    (let [[warning] (latex/untypesettable-character-diagnostics "x ́ y" :document)]
      (is (= :document (:origin warning)))
      (is (str/includes? (:message warning) "the document"))))
  (testing "AC #4: ordinary accented and typographic text warns about
            nothing -- precomposed accents, ligatures, dashes, curly
            quotes, an arrow"
    (is (empty? (latex/untypesettable-character-diagnostics
                 "Ordinary: á ç ö ñ ß ā ł ż — “quoted” … → ﬁ" :document))))
  (testing "one diagnostic per distinct character, in code-point order,
            however many times each occurs"
    (let [warnings (latex/untypesettable-character-diagnostics "α β α β α" :document)]
      (is (= [0x03B1 0x03B2] (mapv :code-point warnings)))))
  (testing "a character outside the BMP is reported once, as itself --
            it is a surrogate PAIR in a Java string, and two nameless
            halves would be worse than saying nothing"
    (let [warnings (latex/untypesettable-character-diagnostics "a 😀 b" :document)]
      (is (= [0x1F600] (mapv :code-point warnings)))
      (is (= "😀" (:char (first warnings))))))
  (testing "AC #3: text that composes to a form inputenc accepts is
            composed rather than reported"
    (is (= "éclair" (latex/compose "éclair")))
    (is (empty? (latex/untypesettable-character-diagnostics
                 (latex/compose "éclair") :document))))
  (testing "and the emitter really does compose what it emits, so the
            .tex that reaches pdflatex is the composed one"
    (let [out (latex/emit-document
               {:meta {} :blocks [(para (str-inline "éclair"))]})]
      (is (str/includes? out "éclair"))
      (is (not (str/includes? out "́")))
      (assert-compiles! out)))
  (testing "a pair with no canonical composition is NOT invented into one
            -- the thesis's own dotless-i-plus-acute, which is what
            pandoc makes of BibTeX's \\'{\\i} -- so it survives compose
            and is reported instead. That split is this task's two
            halves: compose what composes, report the rest"
    (is (= "ı́" (latex/compose "ı́")))
    (is (seq (latex/untypesettable-character-diagnostics
              (latex/compose "ı́") :bibliography))))
  (testing "compose touches CLUSTERS, not lone characters: NFC also has
            singleton compositions, and one of them is a step backwards
            here -- U+2126 OHM SIGN normalizes to U+03A9 GREEK CAPITAL
            LETTER OMEGA, which is outside the table while the ohm sign
            is inside it. Whole-string NFC turned a document that
            compiled into one that did not, and warned about a character
            the author never typed (found by review)"
    ;; Written as an escape rather than as the character: U+2126 and
    ;; U+03A9 are indistinguishable in a source file, and this test is
    ;; entirely about which of the two it is.
    (let [ohm (str (char 0x2126))
          out (latex/emit-document {:meta {} :blocks [(para (str-inline (str "10 " ohm)))]})]
      (is (= 0x2126 (int (first (latex/compose ohm)))))
      (is (str/includes? out ohm))
      (is (empty? (latex/untypesettable-character-diagnostics out :document)))
      (assert-compiles! out)))
  (testing "and it is applied to PROSE rather than to the emitted file,
            so an address is left byte-for-byte alone: composing an
            image path made a different filename from the one on disk,
            and pdflatex answered File not found on a document that used
            to build (found by review)"
    ;; Built from code points, not written as a literal: a composed é in
    ;; the source file would make this assertion pass under the very bug
    ;; it exists to catch (found by review).
    (let [decomposed (str "caf" (char 0x65) (char 0x301) ".png")
          out (latex/emit-document
               {:meta {} :blocks [{:t :para :inlines [{:t :image :src decomposed :alt ""
                                                       :attr empty-attr}]}]})]
      (is (str/includes? out (str "{" decomposed "}"))
          "the path reaches the .tex exactly as authored")
      (is (not (str/includes? out "café.png"))
          "and is not quietly composed into a different filename")))
  (testing "a percent sign inside a verbatim block is a printed
            character, not a comment, so what follows it on that line is
            still scanned -- masking it hid exactly the failure this
            task exists to report, narrowed to code blocks (found by
            review)"
    (let [out (latex/emit-document
               {:meta {} :blocks [{:t :code-block :text "x = 100 % 7  # α alpha"
                                   :attr empty-attr}]})]
      (is (= [0x03B1] (mapv :code-point
                            (latex/untypesettable-character-diagnostics out :document))))))
  (testing "a character TeX never typesets is not reported: an address
            argument reaches the PDF as bytes, and a comment is not read
            at all. Warning there is the crying-wolf case AC #4 exists to
            prevent -- a link to a Russian Wikipedia article drew six
            warnings, each promising no PDF, on a document that built"
    (is (empty? (latex/untypesettable-character-diagnostics
                 "\\href{https://ru.wikipedia.org/wiki/Привет}{the article}" :document)))
    (is (empty? (latex/untypesettable-character-diagnostics
                 "\\includegraphics[width=1cm]{Привет.png}" :document)))
    (is (empty? (latex/untypesettable-character-diagnostics
                 "% haselnuss: language Привет" :document)))
    (is (seq (latex/untypesettable-character-diagnostics
              "\\href{https://x}{Привет}" :document))
        "while the LINK TEXT beside it is typeset, and is still reported"))
  (testing "an escaped percent sign is not a comment: without that, one
            authored % masked the rest of a line of real prose"
    (is (seq (latex/untypesettable-character-diagnostics "50\\% of α" :document))))
  (testing "the range table agrees with a real pdflatex, sampled from
            both sides of it -- otherwise nothing in this suite reads
            the table at all, and it could drift from the compiler it
            claims to model"
    (doseq [[ch expected] [["ž" :compiles] ["ﬁ" :compiles] ["→" :compiles]
                           ["α" :fails] ["≤" :fails] ["中" :fails]]]
      (let [out (latex/emit-document {:meta {} :blocks [(para (str-inline (str "X" ch "X")))]})
            warned? (seq (latex/untypesettable-character-diagnostics out :document))
            [ok? result] (compiles? out)]
        (is (= (= :compiles expected) ok?)
            (str ch " expected to " (name expected) ", pdflatex said "
                 (if ok? "ok" (str (:out result)))))
        (is (= (= :fails expected) (boolean warned?))
            (str "and the table has to agree with it about " ch)))))
  (testing "and the character it reports really is one pdflatex refuses,
            checked against the compiler rather than against the table"
    (let [document {:meta {} :blocks [(para (str-inline "Broken: ı́"))]}
          out (latex/emit-document document)
          [ok? result] (compiles? out)]
      (is (seq (latex/untypesettable-character-diagnostics out :document)))
      (is (not ok?) "the warning would be a false alarm if this compiled")
      (is (str/includes? (str (:out result)) "not set up for use with LaTeX")
          (str "and it fails for the reason the message names:\n" (:out result))))))

(defn- link-doc
  "A Document whose one Para is a Link with `target` as both target and
  text -- the shape an autolink (`<url>`) parses to (TASK-72), which
  leaves an author no escape hatch: CommonMark does no backslash-escape
  processing inside an autolink at all, so `<https://x/a\\b>` cannot be
  spelled any other way (unlike `[x](url)`, which could at least quote
  around the character)."
  [target]
  {:meta {} :blocks [(para {:t :link :target target :inlines [(str-inline target)] :attr empty-attr})]})

(def ^:private raw-href-preamble
  "The minimal preamble `\\href` actually needs, for building a raw
  (un-sanitized) `.tex` string by hand -- proving the failure `latex-
  safe-url` exists to fix is real, independent of this emitter, rather
  than asserted from the task's own bug report."
  "\\documentclass{article}\n\\usepackage{hyperref}\n\\begin{document}\n")

(defn- raw-href-doc
  [target]
  (str raw-href-preamble "\\href{" target "}{link}\n\\end{document}\n"))

(deftest latex-safe-url-test
  (testing "TASK-78 AC #1/#2: a raw backslash in a Link :target used to
            compile to something other than the written address and fail
            a real pdflatex two commands later with nothing said about it"
    (let [[ok? result] (compiles? (raw-href-doc "https://example.com/a\\b"))]
      (is (not ok?) "the raw case really does fail, or this test proves nothing")
      (is (str/includes? (str (:out result)) "Hy@tempa")))
    (testing "it is now percent-encoded rather than left to fail -- for
              either link spelling, since an autolink (TASK-72) and an
              ordinary link both lower to the same Link node"
      (let [out (latex/emit-document (link-doc "https://example.com/a\\b"))]
        (is (str/includes? out "\\href{https://example.com/a%5Cb}{"))
        (is (not (re-find #"\\href\{[^}]*\\\\" out))
            "no raw backslash reaches the href target at all")
        (assert-compiles! out))))
  (testing "AC #1/#2: an unbalanced brace -- confirmed to fail a real
            pdflatex with \"File ended while scanning use of
            \\hyper@n@rmalise\" -- is percent-encoded too, and only then
            does the document compile"
    (let [[ok? result] (compiles? (raw-href-doc "https://example.com/a{b"))]
      (is (not ok?) "the raw case really does fail, or this test proves nothing")
      (is (str/includes? (str (:out result)) "hyper")))
    (let [out (latex/emit-document (link-doc "https://example.com/a{b"))]
      (is (str/includes? out "\\href{https://example.com/a%7Bb}{"))
      (assert-compiles! out)))
  (testing "AC #3: an ordinary URL -- including the raw % # & _ ~ ^ $ and
            a BALANCED brace pair hyperref already carries correctly -- is
            unaffected: none of those characters is touched, so the URL
            in the .tex is byte-for-byte the one that was authored"
    (let [ordinary "https://example.com/a?x=1&y=2#frag_here~a^b$c{d}%20"
          out (latex/emit-document (link-doc ordinary))]
      (is (str/includes? out (str "\\href{" ordinary "}")))
      (assert-compiles! out)))
  (testing "AC #4: a URL already percent-encoded (a pre-escaped backslash,
            spelled %5C) is passed through unchanged rather than encoded a
            second time into %255C"
    (let [out (latex/emit-document (link-doc "https://example.com/a%5Cb"))]
      (is (str/includes? out "\\href{https://example.com/a%5Cb}"))
      (is (not (str/includes? out "%255C")))))
  (testing "AC #2: a bibliography url/doi field gets the identical
            treatment, since a raw backslash or unbalanced brace breaks
            its own `field = {value}` grouping the same way it breaks
            \\href's"
    (let [out (latex/csl-json->bibtex {"x" {:id "x"
                                            :URL "https://e.com/a\\b"
                                            :DOI "10.1/a{b"}})]
      (is (str/includes? out "url = {https://e.com/a%5Cb}"))
      (is (str/includes? out "doi = {10.1/a%7Bb}"))))
  (testing "and an ordinary bibliography url/doi -- raw % # & _ ~ ^ $ and
            a balanced brace pair -- is unaffected there too"
    (let [out (latex/csl-json->bibtex {"x" {:id "x" :URL "https://e.com/a_b{c}"
                                            :DOI "10.1/a_b"}})]
      (is (str/includes? out "url = {https://e.com/a_b{c}}"))
      (is (str/includes? out "doi = {10.1/a_b}"))))
  (testing "review finding: a target carrying BOTH hazards at once -- a
            raw backslash and an unbalanced brace -- gets both fixed, not
            just whichever one this function happens to handle first"
    (let [out (latex/emit-document (link-doc "https://example.com/a\\b{c"))]
      (is (str/includes? out "\\href{https://example.com/a%5Cb%7Bc}{"))
      (assert-compiles! out))))

(deftest csl-json-to-bibtex-test
  (testing "TASK-42: a CSL-JSON bibliography converts to a BibTeX
            database -- the direction whose absence was the seam, since
            the resolver reads CSL-JSON while native mode delegates the
            reference list to BibTeX"
    (let [bib {"knuth1984" {:id "knuth1984" :type "book"
                            :author [{:family "Knuth" :given "Donald E."}]
                            :issued {:date-parts [[1984]]}
                            :title "The TeXbook" :publisher "Addison-Wesley"}
               "acme" {:id "acme" :type "report"
                       :author [{:literal "ACME & Co."}]
                       :issued {:date-parts [[2001]]}
                       :title "50% of a title"}}
          out (latex/csl-json->bibtex bib)]
      (is (str/includes? out "@book{knuth1984,"))
      (is (str/includes? out "@techreport{acme,"))
      (is (str/includes? out "author = {Knuth, Donald E.}"))
      (is (str/includes? out "year = {1984}"))
      (testing "a title is double-braced, because every standard .bst but
                @book's applies change.case$ and printed The texbook"
        (is (str/includes? out "title = {{The TeXbook}}")))
      (testing "a corporate author keeps BibTeX's own brace protection,
                which tells it not to split the name -- escaping those
                braces printed a literal {ACME} in the reference list"
        (is (str/includes? out "author = {{ACME \\& Co.}}")))
      (testing "while the text inside is still TeX-escaped, so an
                ampersand or a percent does not break the build"
        (is (str/includes? out "title = {{50\\% of a title}}")))
      (testing "entries come out sorted by key and fields in a fixed
                order, so the same bibliography always produces the same
                bytes -- this file lands in a build directory"
        (is (< (.indexOf out "@techreport{acme") (.indexOf out "@book{knuth1984")))
        (is (= out (latex/csl-json->bibtex bib))))))
  (testing "an unrecognized CSL type becomes misc, BibTeX's own
            catch-all, which requires no fields -- so it still prints
            rather than vanishing"
    (is (str/includes? (latex/csl-json->bibtex {"x" {:id "x" :type "interview" :title "T"}})
                       "@misc{x,")))
  (testing "an empty bibliography is an empty database, not a malformed one"
    (is (= "" (latex/csl-json->bibtex {}))))
  (testing "a chapter and a conference paper are IN a book, so their
            container-title is a booktitle -- BibTeX prints nothing for
            a journal there and the book title vanished (found by
            review)"
    (doseq [[csl-type entry-type] [["chapter" "incollection"] ["paper-conference" "inproceedings"]]]
      (let [out (latex/csl-json->bibtex {"x" {:id "x" :type csl-type :title "T"
                                              :container-title "The Book"}})]
        (is (str/includes? out (str "@" entry-type "{x,")) csl-type)
        (is (str/includes? out "booktitle = {{The Book}}") csl-type))))
  (testing "a report is published by an institution and a thesis by a
            school; a publisher field prints nothing for either"
    (is (str/includes? (latex/csl-json->bibtex {"x" {:id "x" :type "report" :publisher "P"}})
                       "institution = {P}"))
    (is (str/includes? (latex/csl-json->bibtex {"x" {:id "x" :type "thesis" :publisher "P"}})
                       "school = {P}")))
  (testing "a thesis picks its entry type from CSL's own genre, since
            printing \"PhD thesis\" under a master's is wrong rather
            than merely incomplete"
    (is (str/includes? (latex/csl-json->bibtex {"x" {:id "x" :type "thesis"}}) "@phdthesis{"))
    (is (str/includes? (latex/csl-json->bibtex {"x" {:id "x" :type "thesis"
                                                     :genre "Master's thesis"}})
                       "@mastersthesis{")))
  (testing "a brace in a value becomes a BALANCED macro. BibTeX counts
            braces in its own lexer, where a backslash is not an escape,
            so escape-tex's own \\{ ended the entry early and corrupted
            the rest of the database (found by review, by compiling one)"
    (let [out (latex/csl-json->bibtex {"x" {:id "x" :title "a } b { c"}})]
      (is (str/includes? out "\\textbraceright{}"))
      (is (str/includes? out "\\textbraceleft{}"))
      (is (= (count (re-seq #"\{" out)) (count (re-seq #"\}" out)))
          "and the entry's braces balance, which is the property BibTeX actually checks")))
  (testing "a name's particle and suffix go in BibTeX's own slots rather
            than being dropped -- van Gogh printed as \"Gogh\""
    (let [out (latex/csl-json->bibtex
               {"x" {:id "x" :author [{:family "Gogh" :given "Vincent"
                                       :non-dropping-particle "van"}
                                      {:family "King" :given "Martin" :suffix "Jr."}]}})]
      (is (str/includes? out "van Gogh, Vincent"))
      (is (str/includes? out "King, Jr., Martin"))))
  (testing "a URL and a DOI are addresses a reader follows, not prose,
            so they are not TeX-escaped -- a_b printed as \"a b\""
    (let [out (latex/csl-json->bibtex {"x" {:id "x" :URL "https://e.com/a_b" :DOI "10.1/a_b"}})]
      (is (str/includes? out "url = {https://e.com/a_b}"))
      (is (str/includes? out "doi = {10.1/a_b}"))))
  (testing "only the cited keys are emitted, and an entry whose own id
            BibTeX cannot parse is skipped rather than written -- a
            corrupt entry takes the rest of the database down with it"
    (let [bib {"good" {:id "good" :title "G"}
               "other" {:id "other" :title "O"}
               "bad key" {:id "bad key" :title "B"}}]
      (is (str/includes? (latex/csl-json->bibtex bib ["good"]) "@misc{good,"))
      (is (not (str/includes? (latex/csl-json->bibtex bib ["good"]) "other")))
      (is (not (str/includes? (latex/csl-json->bibtex bib) "bad key"))))))

(deftest kind-and-environment-agreement-test
  (testing "TASK-40 AC #4: every mapped directive names a lexicon kind,
            and that kind exists -- so none is anchorable-but-
            unreferenceable, and none is numbered by the resolver while
            the emitter cannot anchor it. The two tables live in
            different namespaces; this is what keeps them honest"
    (doseq [[directive-name spec] latex/default-directive-environments]
      (is (string? (:lexicon-kind spec)) (str directive-name " declares no :kind"))
      (is (contains? resolver/default-lexicon (keyword (:lexicon-kind spec)))
          (str directive-name "'s kind " (pr-str (:lexicon-kind spec)) " is not in the built-in lexicon"))
      (is (contains? (:node-types (get resolver/default-lexicon (keyword (:lexicon-kind spec)))) :directive)
          (str directive-name "'s kind must declare :directive in :node-types, or TASK-36's role "
               "check reports every one of its nodes as mismatched"))
      (is (:counter spec)
          (str directive-name " must step a counter, or render-environment emits no native-mode "
               "\\label for it and every reference to it degrades to ??"))))
  (testing "and the number the resolver would print for a kind agrees
            with the head word the environment prints, so a reference
            reading \"Lemma 1\" points at something that says \"Lemma 1\""
    (doseq [[directive-name spec] latex/default-directive-environments]
      (let [words (get-in resolver/default-lexicon [(keyword (:lexicon-kind spec)) :words "en" :singular])]
        (is (= (:title spec) words)
            (str directive-name ": the environment's head word and the lexicon's own disagree"))))))

;; Anchoring a degraded directive, and saying so when it cannot be (TASK-51)

(defn- quote-block
  [id & blocks]
  {:t :block-quote :attr (cond-> empty-attr id (assoc :id id)) :blocks (vec blocks)})

(deftest degraded-directive-anchor-test
  (testing "TASK-51: a BlockQuote carrying an id is anchored in
            computed-numbers mode -- the node every degraded directive
            lowers into, and the one counterless block type that used to
            drop the id entirely, so even a \\hyperref pointed at nothing"
    (let [document {:meta {} :blocks [(quote-block "thm:x" (para (str-inline "Body.")))]}
          computed (latex/emit-document document {:computed-numbers true})]
      (is (str/includes? computed "\\phantomsection\\label{thm:x}\\begin{quote}")
          "the anchor immediately precedes the quote, so a jump lands on it")
      (assert-compiles! computed)))
  (testing "and NOT in native mode, for the reason :list and :code-block
            are not either: a \\label with no counter behind it records
            whatever counter LaTeX stepped last, so the reference would
            print the enclosing section's number as though it were the
            answer"
    (let [document {:meta {} :blocks [(quote-block "thm:x" (para (str-inline "Body.")))]}
          native (latex/emit-document document {})]
      (is (not (str/includes? native "\\label{thm:x}")))
      (assert-compiles! native)))
  (testing "an id-less BlockQuote is unchanged in both modes -- no stray
            \\phantomsection where there is nothing to anchor"
    (doseq [[label opts] [["native" {}] ["computed" {:computed-numbers true}]]]
      (let [out (latex/emit-document {:meta {} :blocks [(quote-block nil (para (str-inline "B.")))]}
                                     opts)]
        (is (not (str/includes? out "\\phantomsection")) label)
        (is (str/includes? out "\\begin{quote}") label)))))

(deftest unanchored-reference-diagnostics-test
  (let [reference (para {:t :cross-ref :label "thm:x" :target "thm:x" :text "Theorem 1"})]
    (testing "TASK-51: a reference whose target no longer exists in the
              lowered document -- a degradation dropped the id, which a
              flat Para splice must, since sec4.3 gives Para no Attr --
              is reported in BOTH modes, since it anchors nowhere either
              way"
      (doseq [[label opts] [["native" {}] ["computed" {:computed-numbers true}]]]
        (let [document {:meta {} :blocks [reference (para (str-inline "Degraded content."))]}
              [diagnostic :as diagnostics] (latex/unanchored-reference-diagnostics document opts)]
          (is (= [:unanchored-reference] (map :type diagnostics)) label)
          (is (= "thm:x" (:id diagnostic)) label)
          (is (nil? (:node-type diagnostic)) label)
          (is (str/includes? (:message diagnostic) "nothing in the LaTeX output anchors")
              label))))
    (testing "a reference to a counterless block is reported in native
              mode only: computed mode anchors those, so there is nothing
              to warn about there"
      (let [document {:meta {} :blocks [reference (quote-block "thm:x" (para (str-inline "B.")))]}]
        (is (= [:unanchored-reference]
               (map :type (latex/unanchored-reference-diagnostics document {}))))
        (is (= :block-quote (:node-type (first (latex/unanchored-reference-diagnostics
                                                document {})))))
        (is (str/includes? (:message (first (latex/unanchored-reference-diagnostics document {})))
                           "--computed-numbers")
            "the message names a remedy the author can actually apply")
        (is (empty? (latex/unanchored-reference-diagnostics
                     document {:computed-numbers true})))))
    (testing "a reference to a properly anchored node is silent in both
              modes, so this is not a warning on every cross-reference"
      (let [document {:meta {} :blocks [{:t :section :level 1 :heading [] :attr {:id "thm:x"
                                                                                 :classes []
                                                                                 :props {}}
                                         :blocks [reference]}]}]
        (doseq [opts [{} {:computed-numbers true}]]
          (is (empty? (latex/unanchored-reference-diagnostics document opts)) (pr-str opts)))))
    (testing "an id-bearing counterless block nobody references is silent
              too -- this reports references, not nodes"
      (is (empty? (latex/unanchored-reference-diagnostics
                   {:meta {} :blocks [(quote-block "thm:x" (para (str-inline "B.")))]} {}))))
    (testing "a reference inside an unapplied :fallback is not counted:
              that content is degradation material for haselnuss.lower,
              not part of what this emitter renders"
      (let [document {:meta {}
                      :blocks [(assoc (directive "widget" nil (para (str-inline "Live.")))
                                      :fallback {:kind :blocks :blocks [reference]})]}]
        (is (empty? (latex/unanchored-reference-diagnostics document {})))))
    (testing "review finding #1: a DANGLING reference -- one the resolver
              could not resolve, :target nil and :text \"??\" -- draws
              nothing here. resolve-cross-refs already warns about it by
              name, and this pass has nothing to add: the id names
              nothing at all, not merely nothing anchorable. Reported, it
              was a second warning asserting a cause that was not there"
      (let [dangling (para {:t :cross-ref :label "thm:nope" :target nil :text "??"})
            document {:meta {} :blocks [dangling (para (str-inline "Ordinary prose."))]}]
        (doseq [opts [{} {:computed-numbers true}]]
          (is (empty? (latex/unanchored-reference-diagnostics document opts)) (pr-str opts)))))
    (testing "review finding: an attr.id on an Inline is not an anchor --
              LaTeX has no hook for one and this emitter renders spans as
              pass-throughs -- so a reference to a span id is reported,
              not treated as resolved"
      (let [document {:meta {}
                      :blocks [reference
                               {:t :para :inlines [{:t :span
                                                    :attr {:id "thm:x" :classes [] :props {}}
                                                    :inlines [(str-inline "Inline.")]}]}]}]
        (is (= [nil] (map :node-type (latex/unanchored-reference-diagnostics document {}))))))
    (testing "review finding: a mapped directive counts as an anchor only
              when its environment steps a counter -- render-environment
              emits no native-mode \\label without one, for the same
              binds-to-the-wrong-counter reason -- and a caller's table
              is judged by its own entries"
      (let [document {:meta {} :blocks [reference (directive "aside" "thm:x"
                                                             (para (str-inline "B.")))]}
            counterless {"aside" {:environment "hnaside" :lexicon-kind "asd"}}
            counted (assoc-in counterless ["aside" :counter] true)]
        (is (= [:unanchored-reference]
               (map :type (latex/unanchored-reference-diagnostics
                           document {:directive-environments counterless}))))
        (is (empty? (latex/unanchored-reference-diagnostics
                     document {:directive-environments counted})))
        (is (empty? (latex/unanchored-reference-diagnostics
                     document {:directive-environments counterless :computed-numbers true}))
            "computed mode labels it regardless, so there is nothing to warn about")))))

(deftest unescapable-image-path-diagnostics-test
  (testing "TASK-78 AC #1: an Image :src is a filename, not a URI, so a
            raw backslash in one is NOT silently rewritten the way a Link
            :target now is (percent-encoding it would look up a file that
            doesn't exist on disk) -- instead the build warns, naming the
            path, rather than exiting 0 on a .tex a real pdflatex refuses"
    (let [document {:meta {} :blocks [(para {:t :image :src "pic\\x.png" :alt ""
                                             :attr empty-attr})]}
          [diagnostic :as diagnostics] (latex/unescapable-image-path-diagnostics document)]
      (is (= [:unescapable-image-path] (map :type diagnostics)))
      (is (= "pic\\x.png" (:src diagnostic)))
      (is (str/includes? (:message diagnostic) "pic\\x.png"))
      (is (str/includes? (:message diagnostic) "backslash"))
      (testing "and it really is one pdflatex refuses, checked against the
                compiler rather than asserted"
        (let [out (latex/emit-document document)
              [ok? result] (compiles? out)]
          (is (not ok?) "the warning would be a false alarm if this compiled")
          (is (str/includes? (str (:out result)) "Undefined control sequence")
              (str "and it fails for the reason the message names:\n" (:out result)))))))
  (testing "AC #2: an unbalanced brace in an Image :src is reported too"
    (let [document {:meta {} :blocks [(para {:t :image :src "pic{x.png" :alt "" :attr empty-attr})]}
          [diagnostic] (latex/unescapable-image-path-diagnostics document)]
      (is (str/includes? (:message diagnostic) "unbalanced brace"))))
  (testing "AC #3: an ordinary path -- including a balanced brace pair --
            warns about nothing"
    (let [document {:meta {} :blocks [(para {:t :image :src "figures/plot_1{a}.png" :alt ""
                                             :attr empty-attr})]}]
      (is (empty? (latex/unescapable-image-path-diagnostics document)))))
  (testing "one diagnostic per bad Image, in document order, silent for
            every other Image in the same document"
    (let [bad1 {:t :image :src "a\\b.png" :alt "" :attr empty-attr}
          ok {:t :image :src "fine.png" :alt "" :attr empty-attr}
          bad2 {:t :image :src "c{d.png" :alt "" :attr empty-attr}
          document {:meta {} :blocks [(para bad1) (para ok) (para bad2)]}]
      (is (= ["a\\b.png" "c{d.png"]
             (map :src (latex/unescapable-image-path-diagnostics document))))))
  (testing "review finding: a path carrying BOTH hazards at once names
            both in the one diagnostic, not just whichever this function
            happens to check first"
    (let [document {:meta {} :blocks [(para {:t :image :src "a\\b{c.png" :alt ""
                                             :attr empty-attr})]}
          [diagnostic] (latex/unescapable-image-path-diagnostics document)]
      (is (str/includes? (:message diagnostic) "backslash"))
      (is (str/includes? (:message diagnostic) "unbalanced brace")))))

(deftest directive-lexicon-kinds-test
  (testing "TASK-48: the built-in table reads out as a plain
            name -> kind-keyword map, which is what the resolver's
            directive-kind check consumes"
    (let [kinds (latex/directive-lexicon-kinds)]
      (is (= kinds (latex/directive-lexicon-kinds latex/default-directive-environments)))
      (is (= {"theorem" :thm "lemma" :lem "corollary" :cor
              "definition" :def "proof" :prf "admonition" :adm
              ;; TASK-57/TASK-58's captioned floats, numbered from the
              ;; same id-prefix mechanism every other mapped directive
              ;; uses.
              "listing" :lst "algorithm" :alg
              ;; TASK-56: a figure and one of its panels number as the
              ;; SAME kind, which is the whole mechanism behind a
              ;; panel's sublabel -- a numbered node inside a numbered
              ;; node of its own kind. Two names mapping to one kind is
              ;; therefore expected here, not a collision.
              "figure" :fig "subfigure" :fig}
             kinds))
      (is (every? keyword? (vals kinds)))))
  (testing "a caller's own table is read by ITS entries (AC #4), and an
            entry declaring no :lexicon-kind contributes nothing rather
            than a nil-valued one -- a nil kind would compare unequal to
            every real prefix and report every node as mismatched"
    (is (= {"aside" :asd}
           (latex/directive-lexicon-kinds {"aside" {:environment "hnaside" :lexicon-kind "asd"}
                                           "plain" {:environment "hnplain"}})))))

(deftest directive-environment-fallback-contract-test
  (testing "AC #3: a directive with no mapping entry and no registry
            :latex renderer never reaches this emitter at all -- lower's
            own fallback contract handles it, flattening it to its
            declared fallback content"
    (let [document {:meta {}
                    :blocks [(assoc (directive "widget" nil (para (str-inline "interactive")))
                                    :fallback {:kind :blocks
                                               :blocks [(para (str-inline "static substitute"))]})]}
          lowered (lower/lower document :latex environment-registry)
          out (latex/emit-document lowered {:registry environment-registry})]
      (is (empty? (filter #(= :directive (:t %)) (:blocks lowered)))
          "lower must have replaced the unmapped directive before emission")
      (is (re-find #"static substitute" out))
      (assert-compiles! out)))
  (testing "AC #3: with no fallback either, lower aborts naming the
            offending directive -- the build error comes from the lower
            pass, not from this emitter silently dropping it"
    (let [document {:meta {} :blocks [(directive "widget" nil (para (str-inline "interactive")))]}]
      (try
        (lower/lower document :latex environment-registry)
        (is false "expected lower to throw")
        (catch clojure.lang.ExceptionInfo e
          (is (= :haselnuss.lower/no-representation (:type (ex-data e))))
          (is (= "widget" (:name (ex-data e))))))))
  (testing "AC #3: a MAPPED directive survives lower as a :directive,
            which is exactly what the registered environment-extensions
            sentinel buys -- without it, lower would fall back or abort
            and the mapping could never take effect"
    (let [document {:meta {} :blocks [(directive "theorem" nil (para (str-inline "All things end.")))]}
          lowered (lower/lower document :latex environment-registry)]
      (is (= [:directive] (mapv :t (:blocks lowered))))
      (is (thrown? clojure.lang.ExceptionInfo (lower/lower document :latex {}))
          "unregistered, the same directive has no representation at all"))))

(deftest directive-in-table-cell-test
  (testing "a mapped directive renders inside a table cell, wrapped in
            the minipage a non-Para cell needs (see render-cell-content)"
    (let [document {:meta {}
                    :blocks [{:t :table
                              :head {:cells [{:blocks [(para (str-inline "A"))]}]}
                              :rows [{:cells [{:blocks [(directive "theorem" nil
                                                                   (para (str-inline "In a cell.")))]}]}]
                              :caption [] :colspec [{:align :left}] :attr empty-attr}]}
          out (latex/emit-document document {:registry environment-registry})]
      (is (re-find #"(?s)\\begin\{minipage\}.*\\begin\{theorem\}.*\\end\{minipage\}" out))
      (assert-compiles! out)))
  (testing "TASK-24 review: has-nested-table? now walks a :directive too,
            so a Table hidden inside a mapped directive inside a cell is
            still rejected up front rather than emitting a longtable
            inside a minipage, which longtable documents as unusable"
    (let [inner-table {:t :table
                       :head {:cells [{:blocks [(para (str-inline "x"))]}]}
                       :rows [] :caption [] :colspec [{:align :left}] :attr empty-attr}
          document {:meta {}
                    :blocks [{:t :table
                              :head {:cells [{:blocks [(para (str-inline "A"))]}]}
                              :rows [{:cells [{:blocks [(directive "theorem" nil inner-table)]}]}]
                              :caption [] :colspec [{:align :left}] :attr empty-attr}]}]
      (try
        (latex/emit-document document {:registry environment-registry})
        (is false "expected emit-document to reject the nested Table")
        (catch clojure.lang.ExceptionInfo e
          (is (= ::latex/unrepresentable-cell (:type (ex-data e)))))))))

(deftest thematic-break-test
  (testing "TASK-37 AC #2: a ThematicBreak Block renders as a centered
            LaTeX rule, and the rule is really visible on the rendered
            page -- not merely that the document compiles"
    (let [document {:meta {} :blocks [(para (str-inline "Before"))
                                      {:t :thematic-break}
                                      (para (str-inline "After"))]}
          out (latex/emit-document document)]
      (is (re-find #"\\begin\{center\}\\rule\{0\.5\\linewidth\}\{0\.5pt\}\\end\{center\}" out))
      (assert-compiles! out)
      (let [pdf (compile-pdf! out)
            text (pdf-text pdf)]
        (is (str/includes? text "Before"))
        (is (str/includes? text "After"))
        (is (some thematic-break-rule-width? (horizontal-rule-widths pdf))
            (str "the emitted break must produce an actual visible rule on the page, rules found: "
                 (pr-str (horizontal-rule-widths pdf)))))))
  (testing "negative control: the same document WITHOUT the break has no
            such rule, so the check above is not vacuously true -- and a
            footnote's own \\footnoterule, which LaTeX draws unasked, is
            not mistaken for one (review finding #3)"
    (let [document {:meta {} :blocks [(para (str-inline "Before")
                                            {:t :note :blocks [(para (str-inline "A note."))]})
                                      (para (str-inline "After"))]}
          pdf (compile-pdf! (latex/emit-document document))
          widths (horizontal-rule-widths pdf)]
      (is (seq widths) "the fixture really does draw a footnoterule, so this is a real discrimination")
      (is (not (some thematic-break-rule-width? widths)) (pr-str widths)))))

(deftest include-test
  (testing "TASK-37 AC #3: an Include Block that reached an emitter
            unexpanded has no target content to render, so it raises a
            dedicated diagnostic naming the src and the real cause,
            worded identically to haselnuss.emit.html's own so both
            targets fail the same way on the same document. Since
            TASK-38 the cause is 'this AST did not come through a
            pipeline that runs expansion', not 'expansion does not
            exist'"
    (try
      (latex/emit-document {:meta {} :blocks [{:t :include :src "other.hdoc"}]})
      (is false "expected emit-document to throw")
      (catch clojure.lang.ExceptionInfo e
        (is (= ::latex/unresolved-include (:type (ex-data e))))
        (is (str/includes? (ex-message e) "other.hdoc"))
        (is (str/includes? (ex-message e) "it was never expanded"))
        (is (str/includes? (ex-message e) "expand-includes"))))))

(def ^:private all-block-tags
  "Every Block variant tag `haselnuss.ast` defines (sec4.3) -- kept in
  the same shape as `haselnuss.json-test`'s own `all-block-tags`, which
  is the existing precedent in this codebase for pinning a schema's
  variant set from a test."
  #{:section :para :list :code-block :math-block :figure :table
    :block-quote :directive :include :thematic-break})

(defn- block-fixture
  "A minimal Block of variant `tag`, for `block-coverage-test`'s own
  exhaustive sweep. `:directive` uses a mapped name so it has a
  representation without a registry renderer function."
  [tag]
  (case tag
    :section {:t :section :level 1 :heading [(str-inline "H")] :blocks [] :attr empty-attr}
    :para (para (str-inline "x"))
    :list {:t :list :ordered false :tight true :items [[(para (str-inline "x"))]] :attr empty-attr}
    :code-block {:t :code-block :text "x" :attr empty-attr}
    :math-block {:t :math-block :tex "x" :attr empty-attr}
    :figure {:t :figure :content (para (str-inline "x")) :caption [] :attr empty-attr}
    :table {:t :table :head {:cells [{:blocks [(para (str-inline "x"))]}]} :rows []
            :caption [] :colspec [{:align :left}] :attr empty-attr}
    :block-quote {:t :block-quote :blocks [(para (str-inline "x"))] :attr empty-attr}
    :directive (directive "theorem" nil (para (str-inline "x")))
    :include {:t :include :src "other.hdoc"}
    :thematic-break {:t :thematic-break}))

(deftest block-coverage-test
  (testing "review finding #6: no Block variant haselnuss.ast defines is
            ::unsupported-block any more -- asserted exhaustively against
            the schema's own tag set rather than as a prose claim in a
            docstring, so a variant added later cannot quietly go
            unhandled. :include is the one variant that still raises, and
            it raises its own ::unresolved-include (TASK-37 AC #3)"
    (is (= 11 (count all-block-tags)))
    (doseq [tag all-block-tags]
      (let [thrown (try
                     (latex/emit-document {:meta {} :blocks [(block-fixture tag)]}
                                          {:registry environment-registry})
                     nil
                     (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))]
        (is (not= ::latex/unsupported-block thrown) (str tag))
        (is (= (when (= :include tag) ::latex/unresolved-include) thrown) (str tag))))))

(deftest include-message-parity-test
  (testing "review finding #7: the two emitters' ::unresolved-include
            messages are asserted EQUAL, not merely both documented as
            identical -- the wording is duplicated across two namespaces
            with nothing structural keeping it in step"
    (let [block {:t :include :src "other.hdoc"}
          message (fn [emit] (try
                               (emit {:meta {} :blocks [block]})
                               nil
                               (catch clojure.lang.ExceptionInfo e (ex-message e))))
          latex-message (message latex/emit-document)]
      (is (string? latex-message))
      (is (= latex-message (message html/emit-document))))))

;; ---------------------------------------------------------------------
;; TASK-52: a body-only fragment, and the preamble it tells its host to load.
;; ---------------------------------------------------------------------

(defn- host-compiles?
  "True if `fragment` compiles when `\\input` into a MINIMAL host document
  supplying only a `\\documentclass` and `preamble` -- TASK-52 AC #2's
  own literal check, in the same shape as `compiles?` above (a fresh
  temp directory holding a real test image, a real `pdflatex
  -interaction=nonstopmode -halt-on-error`).

  The host is deliberately as small as a host can be: nothing but the
  class and `\\input{body-preamble}`. A richer one would compile for
  reasons of its own and prove nothing about what the emitter reported,
  which is exactly the silently-missing-package failure this task exists
  to close. Returns `[ok? shell-result]`, like `compiles?`."
  [fragment preamble]
  (let [dir (Files/createTempDirectory "haselnuss-fragment-test" (make-array FileAttribute 0))]
    (write-test-image! (io/file (str dir) "pic.png"))
    (spit (io/file (str dir) "body.tex") fragment)
    (spit (io/file (str dir) "body-preamble.tex") preamble)
    (spit (io/file (str dir) "doc.tex")
          (str "\\documentclass{article}\n"
               "\\input{body-preamble}\n"
               "\\begin{document}\n"
               "\\input{body}\n"
               "\\end{document}\n"))
    (let [result (shell/sh "pdflatex" "-interaction=nonstopmode" "-halt-on-error" "doc.tex"
                           :dir (str dir))]
      [(zero? (:exit result)) result])))

(def ^:private fragment-document
  "A document exercising the constructs whose packages the reported
  preamble has to cover -- a strikethrough (`ulem`), an image
  (`graphicx`/`adjustbox`), a table (`array`/`longtable`), display math
  (`amsmath`), a cross-reference (`hyperref`/`cleveref`) and a mapped
  directive (its own `amsthm` \\newtheorem) -- so `host-compiles?` fails
  if any one of them goes unreported, rather than passing on a document
  that needed nothing but the kernel. The math reaches for `\\mathbb`
  deliberately: it is `amssymb`'s, not `amsmath`'s, and a preamble
  carrying only the latter stops the build with an undefined control
  sequence and no PDF (TASK-75)."
  {:meta {:title [(str-inline "Fragment Title")] :authors ["Jane Doe"] :date "2026-08-07"}
   :blocks
   [{:t :section :level 1 :heading [(str-inline "Intro")]
     :attr {:id "sec:intro" :classes [] :props {}}
     :blocks
     [(para (str-inline "Struck ")
            {:t :strike :inlines [(str-inline "through")]}
            (str-inline ", see ")
            {:t :cross-ref :label "sec:intro" :target "sec:intro" :text "Section 1"}
            (str-inline "."))
      (para {:t :image :src "pic.png" :alt "a picture" :attr empty-attr})
      {:t :math-block :tex "e^{i\\pi} + 1 = 0 \\in \\mathbb{R}"
       :attr {:id "eq:euler" :classes [] :props {}}}
      {:t :table :attr empty-attr :caption [(str-inline "A table")]
       :colspec [{:align :left} {:align :right :width "3cm"}]
       :head {:cells [{:blocks [(para (str-inline "Name"))]}
                      {:blocks [(para (str-inline "Count"))]}]}
       :rows [{:cells [{:blocks [(para (str-inline "hazel"))]}
                       {:blocks [(para (str-inline "3"))]}]}]}
      (directive "theorem" "thm:main" (para (str-inline "Every fragment has a host.")))]}]})

(deftest fragment-body-only-test
  (testing "AC #1: --fragment writes only the rendered body -- no
            \\documentclass, no preamble, no \\begin{document}/
            \\end{document} -- and no \\maketitle title block either,
            since a host template that owns the class owns its own title
            page too (see emit-document's :fragment docstring)"
    (let [out (latex/emit-document fragment-document
                                   {:registry environment-registry :fragment true})]
      (is (not (str/includes? out "\\documentclass")))
      (is (not (str/includes? out "\\usepackage")))
      (is (not (str/includes? out "\\begin{document}")))
      (is (not (str/includes? out "\\end{document}")))
      (is (not (str/includes? out "\\maketitle")))
      (is (not (str/includes? out "\\title{")))
      (is (str/starts-with? out "\\section{Intro}"))
      (is (str/includes? out "\\begin{theorem}")))))

(deftest fragment-preamble-matches-standalone-test
  (testing "AC #4: the packages the body depends on are REPORTED, not
            silently assumed -- and reported as exactly the text
            standalone mode inlines for the same document, asserted as an
            equality rather than as two hand-kept lists that agree today"
    (let [opts {:registry environment-registry}
          standalone (latex/emit-document fragment-document opts)
          reported (latex/emit-preamble fragment-document opts)
          between (subs standalone
                        (count "\\documentclass{article}\n")
                        (str/index-of standalone "\\begin{document}\n"))]
      (is (= between reported))
      (is (str/includes? reported "\\usepackage{hyperref}"))
      ;; TASK-75: a fragment cannot load a package for itself, so the
      ;; symbol package its math needs has to be named here too.
      (is (str/includes? reported "\\usepackage{amssymb}"))
      ;; Not a fixed list: this one is contributed by the mapped
      ;; `theorem` directive in the document, through the same
      ;; :directive-environments table the body was rendered against.
      (is (str/includes? reported "\\usepackage{amsthm}"))
      (is (str/includes? reported "\\newtheorem{theorem}{Theorem}"))
      ;; The 1-arity is the documented shape for a caller with no opts to
      ;; give, and it must mean the same thing as passing none.
      (is (= reported (latex/emit-preamble fragment-document))))))

(deftest fragment-body-matches-standalone-body-test
  (testing "AC #3: --fragment changes only what surrounds the body -- the
            rendered body itself is character-for-character what
            standalone mode puts between its own \\maketitle and
            \\end{document}"
    (let [opts {:registry environment-registry}
          standalone (latex/emit-document fragment-document opts)
          fragment (latex/emit-document fragment-document (assoc opts :fragment true))
          start (+ (str/index-of standalone "\\maketitle\n\n") (count "\\maketitle\n\n"))
          body (subs standalone start (str/index-of standalone "\n\\end{document}\n"))]
      (is (= body (str/trim-newline fragment))))))

(deftest fragment-compiles-in-a-host-test
  (testing "AC #2: a fragment \\input into a host document supplying the
            class and the reported packages compiles under a real
            pdflatex -- the only check that a package the body needs was
            not left for the host author to discover as a compile error"
    (let [opts {:registry environment-registry}
          fragment (latex/emit-document fragment-document (assoc opts :fragment true))
          preamble (latex/emit-preamble fragment-document opts)
          [ok? result] (host-compiles? fragment preamble)]
      (is ok? (str "expected the host document to compile cleanly, pdflatex output:\n"
                   (:out result) (:err result))))))

(deftest fragment-keeps-natbib-citations-test
  (testing "AC #5 (DECISION 2): a fragment renders citations exactly as
            standalone native mode does -- natbib \\citep/\\citet, not a
            downgrade to plain \\cite, which has no spelling for the
            author-in-text and year-only forms this model distinguishes.
            natbib is reported in the companion preamble, so the host is
            told what the body was written against"
    (let [document {:meta {:bibliography "refs.json"}
                    :blocks [(para {:t :cite :items [{:key "knuth1984" :mode :normal
                                                      :prefix [] :suffix []}]
                                    :text [(str-inline "[1]")]})
                             (para {:t :cite :items [{:key "knuth1984" :mode :author
                                                      :prefix [] :suffix []}]
                                    :text [(str-inline "Knuth [1]")]})]}
          opts {:bibliography-id "sec:references" :bib-resource "refs"}
          fragment (latex/emit-document document (assoc opts :fragment true))]
      (is (str/includes? fragment "\\citep{knuth1984}"))
      (is (str/includes? fragment "\\citet{knuth1984}"))
      (is (str/includes? (latex/emit-preamble document opts) "{natbib}")))))

;; ---------------------------------------------------------------------
;; TASK-53: chapters as the top-level division.
;; ---------------------------------------------------------------------

(defn- compile-pdf-resolved!
  "`compile-pdf!` run TWICE, so `\\ref`/`\\Cref` and every float number
  resolve from the `.aux` file rather than printing `??` on the first
  pass. Returns the produced `doc.pdf`, or nil if the final pass failed.

  Separate from `compile-pdf!` for the same reason `compile-with-bibtex!`
  is: the number of passes is the thing under test's own requirement, not
  a detail a single-pass helper can absorb. `-halt-on-error` is kept on
  both passes, unlike `compile-with-bibtex!`'s deliberate omission --
  there is no BibTeX step here to produce the expected first-pass
  warnings, so a genuine LaTeX error should still stop the run rather
  than be discovered as a missing PDF two passes later."
  [tex]
  (let [dir (Files/createTempDirectory "haselnuss-chapter-test" (make-array FileAttribute 0))]
    (write-test-image! (io/file (str dir) "pic.png"))
    (spit (io/file (str dir) "doc.tex") tex)
    (let [run (fn [] (shell/sh "pdflatex" "-interaction=nonstopmode" "-halt-on-error" "doc.tex"
                               :dir (str dir)))
          _ (run)
          result (run)]
      (when (zero? (:exit result))
        (io/file (str dir) "doc.pdf")))))

(defn- chapter-figure
  [id]
  {:t :figure :attr {:id id :classes [] :props {}}
   :content (para {:t :image :src "pic.png" :alt "p" :attr empty-attr})
   :caption [(str-inline "A panel")]})

(def ^:private chapter-document
  "Two chapters, the second holding a figure in each of two sections --
  the shape where chapter-scoped and section-scoped numbering differ, so
  a figure and the reference to it can only agree if the emitter and the
  resolver made the same choice."
  {:meta {:top-level-division :chapter}
   :blocks
   [{:t :section :level 1 :heading [(str-inline "Background")]
     :attr {:id "ch:bg" :classes [] :props {}}
     :blocks [{:t :section :level 2 :heading [(str-inline "Models")]
               :attr {:id "sec:models" :classes [] :props {}}
               :blocks [(chapter-figure "fig:one")]}]}
    {:t :section :level 1 :heading [(str-inline "Results")]
     :attr {:id "ch:res" :classes [] :props {}}
     :blocks [{:t :section :level 2 :heading [(str-inline "Found")]
               :attr {:id "sec:found" :classes [] :props {}}
               :blocks [(chapter-figure "fig:two")]}
              {:t :section :level 2 :heading [(str-inline "Also")]
               :attr {:id "sec:also" :classes [] :props {}}
               :blocks [(chapter-figure "fig:three")
                        (para (str-inline "See ")
                              {:t :cross-ref :label "fig:three" :target "fig:three"
                               :text "Figure 2.2"}
                              (str-inline " and ")
                              {:t :cross-ref :label "ch:bg" :target "ch:bg"
                               :text "Chapter 1"}
                              (str-inline "."))]}]}]})

(deftest chapter-sectioning-commands-test
  (testing "AC #1: a chaptered document emits \\chapter for level 1 and
            shifts every deeper level down with it, out to \\subparagraph"
    (let [levels (fn [meta*]
                   (latex/emit-document
                    {:meta meta*
                     :blocks (mapv (fn [n] {:t :section :level n :heading [(str-inline (str "H" n))]
                                            :attr empty-attr :blocks []})
                                   (range 1 7))}))
          chaptered (levels {:top-level-division :chapter})]
      (doseq [[level command] {1 "chapter" 2 "section" 3 "subsection"
                               4 "subsubsection" 5 "paragraph" 6 "subparagraph"}]
        (is (str/includes? chaptered (str "\\" command "{H" level "}")) (str "level " level)))))
  (testing "AC #4: a document that does not opt in emits the five
            commands it has always emitted, with level 6 and beyond
            clamped to \\subparagraph -- spelled out per level rather
            than compared against another run of the same code, since
            two runs of the new branch would agree with each other
            however wrong they both were (found by review)"
    (let [levels (fn [meta*]
                   (latex/emit-document
                    {:meta meta*
                     :blocks (mapv (fn [n] {:t :section :level n
                                            :heading [(str-inline (str "H" n))]
                                            :attr empty-attr :blocks []})
                                   (range 1 8))}))]
      (doseq [meta* [{} {:top-level-division :section}]]
        (let [out (levels meta*)]
          (doseq [[level command] {1 "section" 2 "subsection" 3 "subsubsection"
                                   4 "paragraph" 5 "subparagraph"}]
            (is (str/includes? out (str "\\" command "{H" level "}"))
                (str (pr-str meta*) " level " level)))
          (is (str/includes? out "\\subparagraph{H6}") "level 6 clamps")
          (is (str/includes? out "\\subparagraph{H7}") "and so does anything past it")
          (is (not (str/includes? out "\\chapter")))))))
  (testing "and the chaptered list clamps at its own end too, one level
            deeper than the article one"
    (let [out (latex/emit-document
               {:meta {:top-level-division :chapter}
                :blocks [{:t :section :level 7 :heading [(str-inline "Deep")]
                          :attr empty-attr :blocks []}]})]
      (is (str/includes? out "\\subparagraph{Deep}")))))

(deftest chapter-computed-numbers-test
  (testing "TASK-53: a chaptered document in computed-numbers mode uses
            the starred chapter command with the resolver's own number
            baked in, and the phantomsection anchor that mode needs --
            the combination of the two features was untested (found by
            review), and it is the mode the cross-format invariant runs in"
    (let [labels (resolver/number-document chapter-document)
          out (latex/emit-document chapter-document
                                   {:computed-numbers true :labels labels})]
      (is (str/includes? out "\\chapter*{1\\quad Background}\\phantomsection\\label{ch:bg}"))
      (is (str/includes? out "\\section*{1.1\\quad Models}"))
      (is (str/includes? out "\\caption*{Figure 2.2: A panel}")
          "and a float still carries the chapter-composed number the body's own
           references use")
      (assert-compiles! out))))

(deftest chapter-document-class-test
  (testing "AC #5: a standalone chaptered document is emitted into a class
            that actually provides \\chapter -- article does not, and a
            chaptered document in it fails on its first heading"
    (is (str/starts-with? (latex/emit-document chapter-document) "\\documentclass{report}"))
    (is (str/starts-with? (latex/emit-document {:meta {} :blocks []})
                          "\\documentclass{article}")))
  (testing "and a fragment chooses no class at all, so its host's does --
            but the reported preamble says the body needs \\chapter,
            which is the only warning a file cannot give from inside a
            document it does not control"
    (let [fragment (latex/emit-document chapter-document {:fragment true})]
      (is (not (str/includes? fragment "\\documentclass")))
      (is (str/includes? fragment "\\chapter{Background}")))
    (is (str/includes? (latex/emit-preamble chapter-document) "\\chapter"))
    (is (not (str/includes? (latex/emit-preamble {:meta {} :blocks []}) "\\chapter")))))

(deftest chapter-float-numbering-parity-test
  (testing "AC #3: a section-scoped figure number composes with the
            CHAPTER, and native-mode LaTeX -- which computes its own
            numbers from report's counters, knowing nothing about the
            resolver -- prints exactly the numbers HTML does. This is the
            one assertion that can catch the two disagreeing, since
            everything else in this file reads only what the emitter
            wrote"
    (let [labels (resolver/number-document chapter-document)
          html (html/emit-document chapter-document {:labels labels})
          pdf (compile-pdf-resolved! (latex/emit-document chapter-document))]
      (is (= {"fig:one" "1.1" "fig:two" "2.1" "fig:three" "2.2"}
             (into {} (map (fn [[id entry]] [id (:number entry)]))
                   (select-keys labels ["fig:one" "fig:two" "fig:three"])))
          "the second figure of chapter 2 is 2.2 even though it opens a new section")
      (is (some? pdf) "the chaptered document must compile under a real pdflatex")
      (let [text (str/replace (pdf-text pdf) #"\s+" " ")]
        (doseq [number ["Figure 1.1" "Figure 2.1" "Figure 2.2"]]
          (is (str/includes? text number)
              (str "native-mode LaTeX did not print " number ":\n" text))
          (is (str/includes? html number)
              (str "HTML did not print " number)))
        (testing "and a reference to one resolves to that same number
                  rather than to LaTeX's ?? placeholder"
          (is (str/includes? text "See Figure 2.2 and Chapter 1."))
          (is (not (str/includes? text "??"))))))))

;; ---------------------------------------------------------------------
;; TASK-60: scale and height on images.
;; ---------------------------------------------------------------------

(defn- image-latex
  "The rendered `\\includegraphics` -- with its `\\adjustbox` wrapper, when
  it has one -- that Image props `props` produce, read out of a
  one-image document so this is the emitter's own answer rather than a
  reimplementation of it."
  [props]
  (let [out (latex/emit-document
             {:meta {}
              :blocks [(para {:t :image :src "pic.png" :alt ""
                              :attr {:classes [] :props props}})]})]
    (str/trim (second (re-find #"(?s)\\begin\{document\}\n(.*?)\n\\end\{document\}" out)))))

(defn- measured-boxes
  "The `[width-pt height-pt]` a real pdflatex reports for each of
  `prop-maps`, by boxing the emitted image and printing `\\the\\wd`/
  `\\the\\ht` -- the RENDERED size, not the emitted string. The image is
  200x100, so aspect ratio is visible in the numbers.

  This exists because string assertions cannot see the bug this task's
  review found: `\\includegraphics[width=3cm,scale=0.5]` is perfectly
  legal LaTeX, compiles cleanly, and silently ignores the `scale`. Only
  measuring the box shows it."
  [prop-maps]
  (let [dir (Files/createTempDirectory "haselnuss-image-size" (make-array FileAttribute 0))]
    (ImageIO/write (BufferedImage. 200 100 BufferedImage/TYPE_INT_RGB) "png"
                   (io/file (str dir) "pic.png"))
    (spit (io/file (str dir) "doc.tex")
          (str "\\documentclass{article}\n"
               "\\usepackage{graphicx}\n\\usepackage[export]{adjustbox}\n"
               "\\begin{document}\n\\newsavebox{\\hnbox}\n"
               (apply str
                      (map-indexed
                       (fn [i props]
                         (str "\\savebox{\\hnbox}{" (image-latex props) "}"
                              "M" i ": \\the\\wd\\hnbox\\ x \\the\\ht\\hnbox\\par\n"))
                       prop-maps))
               "\\end{document}\n"))
    (shell/sh "pdflatex" "-interaction=nonstopmode" "-halt-on-error" "doc.tex" :dir (str dir))
    (let [text (str/replace (:out (shell/sh "pdftotext" (str (io/file (str dir) "doc.pdf")) "-"))
                            #"\s+" " ")]
      (mapv (fn [i]
              (let [[_ w h] (re-find (re-pattern (str "M" i ": ([0-9.]+)pt x ([0-9.]+)pt")) text)]
                (when w [(Double/parseDouble w) (Double/parseDouble h)])))
            (range (count prop-maps))))))

(defn- about=
  "True when `a` and `b` agree to within a quarter of a point -- TeX
  rounds to scaled points, so two dimensions that are the same length
  can differ in the last digit."
  [a b]
  (< (abs (- a b)) 0.25))

(defn- image-options
  "Just the `\\includegraphics` option list for `props`."
  [props]
  (second (re-find #"\\includegraphics\[([^]]*)\]" (image-latex props))))

(deftest image-scale-and-height-test
  (testing "AC #1: scale and height reach the LaTeX output -- a
            percentage height against \\textheight, the page dimension a
            vertical percentage can only sensibly mean"
    (is (= "max width=\\linewidth,height=4cm" (image-options {"height" "4cm"})))
    (is (= "max width=\\linewidth,height=0.3\\textheight" (image-options {"height" "30%"})))
    (is (= "\\adjustbox{scale=0.55}{\\includegraphics[max width=\\linewidth]{pic.png}}"
           (image-latex {"scale" "0.55"}))))
  (testing "AC #3: width behaviour is unchanged -- the percentage-to-
            \\linewidth conversion and the max-width default both exactly
            as before"
    (is (= "width=0.5\\linewidth" (image-options {"width" "50%"})))
    (is (= "width=3cm" (image-options {"width" "3cm"})))
    (is (= "max width=\\linewidth" (image-options {})))
    (is (= "\\includegraphics[max width=\\linewidth]{pic.png}" (image-latex {}))
        "and an image with no sizing prop at all gains no wrapper"))
  (testing "a blank value is an ABSENT one, not a value that happens to
            be empty: a bare `width=` is not a legal option and fails the
            compile, and the only other thing an empty attribute could
            sensibly mean is the documented default"
    (is (= (image-latex {}) (image-latex {"scale" "" "height" "" "width" ""})))
    (is (= (image-latex {"width" "3cm"}) (image-latex {"width" " 3cm " "scale" "  "}))))
  (testing "a percentage is converted rather than passed through, and
            this one is fatal rather than cosmetic: a raw % in an option
            list opens a TeX comment that eats the rest of the line,
            closing bracket included. The accepted shape allows a sign
            and a leading dot, because -50% and .5% were confirmed to die
            the same way as 50% did (found by review)"
    (is (= "\\adjustbox{scale=0.5}{\\includegraphics[max width=\\linewidth]{pic.png}}"
           (image-latex {"scale" "50%"})))
    (doseq [value ["50%" "-50%" ".5%" "+25%"]]
      (is (not (str/includes? (image-latex {"scale" value}) "%")) value)
      (is (not (str/includes? (image-latex {"width" value}) "%")) value)))
  (testing "and a value that carries a % but is not a percentage cannot
            be a LaTeX dimension either, so it stops the build naming
            itself rather than producing a .tex confirmed not to compile"
    (let [thrown (try (image-latex {"width" "50%x"}) nil
                      (catch clojure.lang.ExceptionInfo e e))]
      (is (some? thrown))
      (is (= ::latex/invalid-image-size (:type (ex-data thrown))))
      (is (str/includes? (ex-message thrown) "50%x")))))

(deftest image-sizing-props-compose-test
  (testing "AC #4: an image carrying more than one sizing prop resolves
            them without silently dropping one -- checked by MEASURING
            the rendered box, because the string assertions above cannot
            see this. graphicx honours its own `scale` key only when
            neither width nor height is set: `[width=3cm,scale=0.5]` is
            legal, compiles, and ignores the scale (found by review,
            which caught the first implementation shipping exactly
            that). \\adjustbox scales the finished box from outside, so
            it composes with whatever sized it"
    (let [[[nat-w nat-h] [w3-w w3-h] [half3-w half3-h] [h2-w h2-h] [halfh-w halfh-h]
           [s-w s-h]]
          (measured-boxes [{} {"width" "3cm"} {"width" "3cm" "scale" "0.5"}
                           {"height" "2cm"} {"height" "2cm" "scale" "0.5"}
                           {"scale" "0.5"}])]
      (is (about= 200.75 nat-w) "the 200x100 test image at natural size")
      (is (about= 100.37 nat-h))
      (testing "a width alone is honoured, aspect preserved"
        (is (about= 85.36 w3-w))
        (is (about= 42.68 w3-h)))
      (testing "and a scale beside it HALVES that, rather than being
                silently ignored the way graphicx's own scale key is"
        (is (about= (/ w3-w 2) half3-w) (str w3-w " -> " half3-w))
        (is (about= (/ w3-h 2) half3-h)))
      (testing "same beside a height"
        (is (about= (/ h2-w 2) halfh-w) (str h2-w " -> " halfh-w))
        (is (about= (/ h2-h 2) halfh-h)))
      (testing "and scale on its own still means half the natural size,
                so routing every scaled image through the wrapper changed
                nothing for the single-prop case"
        (is (about= (/ nat-w 2) s-w))
        (is (about= (/ nat-h 2) s-h))))))

;; A float panel written outside any float (TASK-56)

(deftest orphan-panel-test
  (testing "TASK-56: a subfigure directive with no float around it is a
            build error naming it, not output. subcaption itself refuses
            the shape -- 'subfigure outside float' is fatal and produces
            no PDF -- so the only choice is between an error that says
            which directive is misplaced and one that does not. The
            message names the real rule (found by review): a panel is
            laid out by the float it sits DIRECTLY inside"
    (let [document {:meta {} :blocks [(directive "subfigure" "fig:lonely"
                                                 (para (str-inline "Body.")))]}]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"not a direct child of a float"
           (latex/emit-document document {:registry environment-registry})))
      (is (= "fig:lonely"
             (:id (try (latex/emit-document document {:registry environment-registry})
                       (catch clojure.lang.ExceptionInfo e (ex-data e))))))))
  (testing "and inside its figure the same panel emits, so the error is
            about placement and nothing else"
    (let [document {:meta {}
                    :blocks [(directive "figure" "fig:whole"
                                        (directive "subfigure" "fig:lonely"
                                                   (para (str-inline "Body."))))]}
          out (latex/emit-document document {:registry environment-registry})]
      (is (str/includes? out "\\begin{subfigure}{0.96\\linewidth}"))
      (assert-compiles! out))))

;; A book's front matter, numbered separately from its body (TASK-66)

(deftest chaptered-front-matter-numbering-test
  (testing "TASK-66: a chaptered document numbers what precedes its body
            in roman and restarts arabic at the body, which is what
            \\frontmatter/\\mainmatter do in a class that has them.
            report does not: \\maketitle and every abstract is a
            titlepage that resets the page counter, so the front matter
            numbered straight into the body and several pages all held
            page 1"
    (let [document {:meta {:title [(str-inline "A Thesis")] :top-level-division :chapter}
                    :blocks [(directive "abstract" nil (para (str-inline "In short.")))
                             {:t :section :level 1 :heading [(str-inline "Background")]
                              :attr empty-attr :blocks [(para (str-inline "Prose."))]}]}
          out (latex/emit-document document {:registry environment-registry})]
      (is (str/includes? out "\\pagenumbering{roman}"))
      (is (str/includes? out "\\pagenumbering{arabic}"))
      (is (< (.indexOf out "\\pagenumbering{roman}")
             (.indexOf out "\\begin{abstract}")
             (.indexOf out "\\pagenumbering{arabic}")
             (.indexOf out "\\chapter{Background}"))
          "roman over the front matter, arabic from the body on")
      (testing "and hyperref's page anchors are off while the counter
                still repeats -- its own documented answer for the
                duplicate-destination warning that produced"
        (is (str/includes? out "\\hypersetup{pageanchor=false}"))
        (is (< (.indexOf out "\\hypersetup{pageanchor=false}")
               (.indexOf out "\\begin{abstract}")
               (.indexOf out "\\hypersetup{pageanchor=true}"))))
      (assert-compiles! out)))
  (testing "and a document that is not chaptered emits none of it: an
            article sets its abstract inline rather than as a titlepage,
            so its page counter never repeats and roman front matter is
            not its convention"
    (let [document {:meta {:title [(str-inline "A Paper")]}
                    :blocks [(directive "abstract" nil (para (str-inline "In short.")))
                             {:t :section :level 1 :heading [(str-inline "Background")]
                              :attr empty-attr :blocks [(para (str-inline "Prose."))]}]}
          out (latex/emit-document document {:registry environment-registry})]
      (is (not (str/includes? out "\\pagenumbering")))
      (is (not (str/includes? out "\\hypersetup{pageanchor")))
      (assert-compiles! out)))
  (testing "nor does a chaptered document with nothing ahead of its body
            -- no title, no front matter, so no page to number apart"
    (let [document {:meta {:top-level-division :chapter}
                    :blocks [{:t :section :level 1 :heading [(str-inline "Background")]
                              :attr empty-attr :blocks [(para (str-inline "Prose."))]}]}
          out (latex/emit-document document {:registry environment-registry})]
      (is (not (str/includes? out "\\pagenumbering")))))
  (testing "and a --fragment build emits none of it either, in any
            document: the host owns its own page numbering, and a
            \\pagenumbering inside an \\input body would silently
            renumber the template's own front matter"
    (let [document {:meta {:title [(str-inline "A Thesis")] :top-level-division :chapter}
                    :blocks [(directive "abstract" nil (para (str-inline "In short.")))
                             {:t :section :level 1 :heading [(str-inline "Background")]
                              :attr empty-attr :blocks [(para (str-inline "Prose."))]}]}
          out (latex/emit-document document {:registry environment-registry :fragment true})]
      (is (not (str/includes? out "\\pagenumbering")))
      (is (not (str/includes? out "\\hypersetup"))))))

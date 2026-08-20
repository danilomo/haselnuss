(ns haselnuss.front-matter-test
  "Fixtures for front-matter prose blocks: an abstract with its language
  tag and keywords (TASK-54), and the acknowledgements/epigraph/
  dedication family that extends the same mechanism (TASK-55).

  One namespace rather than additions to five, because the thing under
  test spans all of them -- the directive is parsed by
  `haselnuss.parser`, kept alive by `haselnuss.lower` through
  `haselnuss.extensions.front-matter`'s registry entries, excluded from
  numbering and the TOC by `haselnuss.resolver`, rendered by both
  emitters, and split into side files by `haselnuss.cli`. A feature whose
  whole contract is \"this is not part of the body\" can only be checked
  where all of those meet.

  The LaTeX output is compiled with a real `pdflatex`, and the fragment's
  side files are `\\input` into a minimal host, mirroring
  `haselnuss.emit.latex-test`'s own convention: an abstract that emits
  but does not typeset is not an abstract."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [haselnuss.cli :as cli]
            [haselnuss.extensions.front-matter :as front-matter]
            [haselnuss.parser :as parser]
            [haselnuss.resolver :as resolver])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(def ^:private document
  "One document carrying two abstracts in different languages -- the
  shape the milestone is scoped from, and the only one that can show a
  per-block language tag doing anything. `meta.lang` is `en`, so the
  Portuguese block's tag has to override it and the English one has to
  fall back to it: a single-abstract fixture would pass either way."
  (str "---\n"
       "title: On Hazelnuts\n"
       "lang: en\n"
       "---\n\n"
       ":::{abstract lang=pt-BR keywords=\"Aveleira; Colheita; Nozes\"}\n"
       "Duas frases de *resumo*, com ênfase.\n\n"
       "Segundo parágrafo do resumo.\n"
       ":::\n\n"
       ":::{abstract keywords=\"Hazel; Harvest\"}\n"
       "Two sentences of *abstract*.\n"
       ":::\n\n"
       "# Introduction {#sec:intro}\n\n"
       "Body text referring to @sec:intro.\n"))

(defn- fixture-dir!
  [source]
  (let [dir (str (Files/createTempDirectory "haselnuss-front-matter" (make-array FileAttribute 0)))]
    (spit (io/file dir "doc.hdoc") source)
    dir))

(defn- build!
  [dir opts]
  (let [result (cli/build (merge {:input (str (io/file dir "doc.hdoc")) :target "html"} opts))]
    (assoc result :output (slurp (:output-path result)))))

(def ^:private front-matter-names
  "The directive names the resolver must treat as front matter -- the
  same set `haselnuss.cli` passes, read from the extension rather than
  spelled out here so a name added to the table cannot be forgotten in
  one place and remembered in the other."
  (set (keys front-matter/blocks)))

(defn- compiles?
  "True if `tex` compiles under a real `pdflatex` run in `dir`. Returns
  `[ok? output]`, mirroring `haselnuss.cli-test`'s own helper."
  [dir tex]
  (spit (io/file dir "host.tex") tex)
  (let [result (shell/sh "pdflatex" "-interaction=nonstopmode" "-halt-on-error" "host.tex"
                         :dir (str dir))]
    [(zero? (:exit result)) (str (:out result) (:err result))]))

(deftest abstract-authoring-test
  (testing "AC #1: an abstract is authorable as marked-up multi-paragraph
            prose carrying a language tag and a keywords list -- checked
            on the parsed structure, since everything below depends on
            the directive really holding Blocks rather than a scalar"
    (let [dir (fixture-dir! document)
          html (:output (build! dir {}))]
      (is (str/includes? html "<em>resumo</em>") "authored emphasis survives")
      (is (str/includes? html "<p>Segundo parágrafo do resumo.</p>")
          "and so does the paragraph break, which a YAML scalar could not have carried"))))

(deftest abstract-two-languages-test
  (testing "AC #2: two abstracts in different languages both survive to
            HTML, each tagged with its own language -- the Portuguese one
            overriding meta.lang, the English one inheriting it"
    (let [dir (fixture-dir! document)
          html (:output (build! dir {}))]
      (is (str/includes? html "<section class=\"abstract\" lang=\"pt-BR\">"))
      (is (str/includes? html "<section class=\"abstract\" lang=\"en\">"))
      (is (str/includes? html "<h1>Resumo</h1>"))
      (is (str/includes? html "<h1>Abstract</h1>"))))
  (testing "AC #2: and to LaTeX, where the tag survives both as the word
            each abstract prints -- \\abstractname renamed inside a group
            so the second block does not inherit the first's word -- and
            as a machine-readable comment, LaTeX having no generic
            language attribute the way HTML does"
    (let [dir (fixture-dir! document)
          tex (:output (build! dir {:target "latex"}))]
      (is (str/includes? tex "% haselnuss front-matter: abstract lang=pt-BR"))
      (is (str/includes? tex "% haselnuss front-matter: abstract lang=en"))
      (is (str/includes? tex "\\begingroup\\renewcommand{\\abstractname}{Resumo}"))
      (is (str/includes? tex "\\begingroup\\renewcommand{\\abstractname}{Abstract}"))
      (testing "and the whole thing compiles"
        (let [[ok? log] (compiles? dir tex)]
          (is ok? (str "expected the document with two abstracts to compile:\n" log)))))))

(deftest abstract-keywords-test
  (testing "AC #3: keywords reach both targets as distinct terms rather
            than one undifferentiated string -- separate elements in
            HTML, so a consumer never re-splits prose"
    (let [dir (fixture-dir! document)
          html (:output (build! dir {}))
          tex (:output (build! dir {:target "latex"}))]
      (is (str/includes? html "<span class=\"keyword\">Aveleira</span>"))
      (is (str/includes? html "<span class=\"keyword\">Colheita</span>"))
      (is (str/includes? html "<span class=\"keyword\">Nozes</span>"))
      (is (str/includes? html "<span class=\"keywords-label\">Palavras-chave</span>"))
      (is (str/includes? html "<span class=\"keywords-label\">Keywords</span>"))
      (is (not (str/includes? html "keywords=\"Aveleira"))
          "and the authored string must not ALSO leak through as an element attribute")
      (is (str/includes? tex "\\noindent\\textbf{Palavras-chave:} Aveleira; Colheita; Nozes"))))
  (testing "the list is built once, here, so both emitters agree on what
            a term is: trimmed, empty segments dropped"
    (let [terms (fn [raw] (front-matter/keywords
                           {:t :directive :name "abstract"
                            :attr {:classes [] :props {"keywords" raw}}}))]
      (is (= ["a" "b"] (terms "a; b")))
      (is (= ["a" "b"] (terms "  a ;  b  ")))
      (is (= ["a" "b"] (terms "a; b;")) "a trailing separator is not a third, empty term")
      (is (= ["Knuth, Donald"] (terms "Knuth, Donald"))
          "a comma is part of a term, which is why the separator is a semicolon")
      (is (= [] (front-matter/keywords {:t :directive :name "abstract"
                                        :attr {:classes [] :props {}}}))))))

(deftest abstract-is-not-body-content-test
  (let [dir (fixture-dir! document)
        parsed (parser/parse (slurp (io/file dir "doc.hdoc")))
        labels (resolver/number-document parsed resolver/default-lexicon front-matter-names)]
    (testing "AC #4: an abstract is never numbered -- it has no entry in
              the label table at all, which is also what makes it
              impossible to cross-reference"
      (is (= #{"sec:intro"} (set (keys labels))) (pr-str labels)))
    (testing "AC #4: and never appears in the TOC, which walks :section
              Blocks and an abstract is deliberately not one"
      (is (= ["sec:intro"] (mapv :id (resolver/derive-toc parsed labels front-matter-names)))))
    (testing "AC #4: a reference to one dangles like a reference to
              anything that is not a target, rather than resolving"
      (let [doc (parser/parse ":::{abstract #abs:one}\nx\n:::\n\nSee @abs:one.\n")
            {:keys [warnings]} (resolver/resolve-cross-refs doc (resolver/number-document doc))]
        (is (= [:dangling-cross-ref] (mapv :type warnings)))))
    (testing "and it is rendered ahead of the body rather than inside it,
              which is what 'outside sectioning' means in the output"
      (let [html (:output (build! dir {}))]
        (is (< (str/index-of html "class=\"abstract\"")
               (str/index-of html "<section id=\"sec:intro\">")))))))

(deftest abstract-nested-is-an-error-test
  (testing "a front-matter block written inside the body fails the build
            naming it: its whole contract is that it is not part of the
            numbered body, and one inside a section is inside the body by
            construction -- emitting it anyway would put an abstract
            under a heading, and in a fragment build silently somewhere
            other than the side file the host was told to \\input"
    (let [dir (fixture-dir! "# Intro {#sec:i}\n\n:::{abstract}\nx\n:::\n")
          thrown (try (build! dir {}) nil (catch clojure.lang.ExceptionInfo e e))]
      (is (some? thrown))
      (is (= :haselnuss.extensions.front-matter/nested-front-matter (:type (ex-data thrown))))
      (is (str/includes? (ex-message thrown) "abstract")))))

(deftest abstract-fragment-side-files-test
  (testing "AC #5: a fragment leaves every front-matter block out of its
            body and writes each as its own side file, holding the prose
            and keywords with NO environment around them -- so the host
            template places it inside whatever it uses, and haselnuss
            never invents \\begin{resumo}"
    (let [dir (fixture-dir! document)
          {:keys [output front-matter-paths]}
          (build! dir {:target "latex" :fragment true
                       :output (str (io/file dir "body.tex"))})
          pt (str (io/file dir "body-abstract-pt-BR.tex"))
          en (str (io/file dir "body-abstract-en.tex"))]
      (is (= [pt en] front-matter-paths)
          "one file per block, named for the block and its language, in document order")
      (is (not (str/includes? output "abstract"))
          "and none of them is in the body fragment")
      (doseq [file [pt en]]
        (let [content (slurp file)]
          (is (not (str/includes? content "\\begin{abstract}")))
          (is (not (str/includes? content "\\begin{resumo}")))))
      (is (str/includes? (slurp pt) "Duas frases de \\emph{resumo}"))
      (is (str/includes? (slurp pt) "\\textbf{Palavras-chave:} Aveleira; Colheita; Nozes"))
      (is (str/includes? (slurp en) "Two sentences of \\emph{abstract}"))
      (testing "and a host that supplies its own environment around them
                compiles -- the only check that the bare content is
                really usable rather than merely bare"
        (let [[ok? log] (compiles? dir
                                   (str "\\documentclass{article}\n"
                                        "\\input{body-preamble}\n"
                                        "\\begin{document}\n"
                                        "\\begin{abstract}\\input{body-abstract-pt-BR}\\end{abstract}\n"
                                        "\\begin{abstract}\\input{body-abstract-en}\\end{abstract}\n"
                                        "\\input{body}\n"
                                        "\\end{document}\n"))]
          (is ok? (str "expected the host document to compile:\n" log))))))
  (testing "a standalone build writes no side files at all: it places
            every block itself"
    (let [dir (fixture-dir! document)
          {:keys [front-matter-paths]} (build! dir {:target "latex"})]
      (is (empty? front-matter-paths))))
  (testing "and a document with no front matter writes none either, on
            any target"
    (let [dir (fixture-dir! "# Intro {#sec:i}\n\ntext\n")]
      (is (empty? (:front-matter-paths (build! dir {:target "latex" :fragment true}))))
      (is (empty? (:front-matter-paths (build! dir {})))))))

(deftest abstract-nesting-check-covers-every-position-test
  (testing "the nesting check reaches every position a Block can be
            nested in, kept in step with the resolver's own
            block-children -- including a footnote's Blocks, which are
            reached through a Para's Inlines and which an earlier version
            missed, so an abstract written inside one failed later in an
            emitter as a marker-called error naming neither the mistake
            nor where it was"
    (let [abstract {:t :directive :name "abstract" :attr {:classes [] :props {}} :blocks []}
          attr {:classes [] :props {}}
          wrap {:section {:t :section :level 1 :heading [] :attr attr :blocks [abstract]}
                :list {:t :list :ordered false :tight true :attr attr :items [[abstract]]}
                :block-quote {:t :block-quote :attr attr :blocks [abstract]}
                :figure {:t :figure :caption [] :attr attr :content abstract}
                :table {:t :table :caption [] :colspec [] :attr attr
                        :head {:cells [{:blocks [abstract]}]} :rows []}
                :directive {:t :directive :name "theorem" :attr attr :blocks [abstract]}
                :note {:t :para :inlines [{:t :note :blocks [abstract]}]}
                :note-in-emph {:t :para :inlines [{:t :emph :inlines
                                                   [{:t :note :blocks [abstract]}]}]}}]
      (doseq [[position block] wrap]
        (let [thrown (try (front-matter/extract {:meta {} :blocks [block]})
                          nil
                          (catch clojure.lang.ExceptionInfo e e))]
          (is (some? thrown) (str "an abstract nested in a " (name position) " slipped through"))
          (is (= :haselnuss.extensions.front-matter/nested-front-matter (:type (ex-data thrown)))
              (name position)))))))

(deftest abstract-produces-no-diagnostics-test
  (testing "a well-formed front-matter block is not an unknown directive,
            a role mismatch or anything else the resolver warns about --
            registering it in the build registry is what makes that true,
            and a build full of spurious warnings is one whose real
            warnings go unread"
    (let [dir (fixture-dir! document)]
      (is (empty? (:diagnostics (build! dir {}))))
      (is (empty? (:diagnostics (build! dir {:target "latex"})))))))

(deftest abstract-numbering-prefix-does-not-number-test
  (testing "AC #4, found by review: an abstract whose id carries a
            NUMBERING-KIND prefix is still not a numbering target. The
            earlier implementation lifted front matter out at emit time,
            long after numbering had run, so :::{abstract #thm:a} was
            numbered Theorem 1 and answered a live @thm:a -- the one AC
            #4 is literally about, passing only because the test used a
            prefix (#abs:) that names no kind at all"
    (let [dir (fixture-dir! (str ":::{abstract #thm:a}\nShort.\n:::\n\n"
                                 "# Intro {#sec:i}\n\nSee @thm:a.\n"))
          {:keys [output diagnostics]} (build! dir {})]
      (is (empty? (filter (fn [[id _]] (= "thm:a" id))
                          (resolver/number-document
                           (parser/parse (slurp (io/file dir "doc.hdoc")))
                           resolver/default-lexicon front-matter-names))))
      (is (not (str/includes? output "Theorem 1"))
          "the abstract must not print a number of its own")
      (is (str/includes? output "??")
          "and a reference to it dangles, like a reference to anything that is not a target")
      (testing "and the build says WHY, rather than leaving the author to
                infer it from a dangling reference to a label they can
                see in their own source"
        (is (some #(= :front-matter-not-numbered (:type %)) diagnostics)
            (pr-str (mapv :type diagnostics)))
        (is (str/includes? (:message (first (filter #(= :front-matter-not-numbered (:type %))
                                                    diagnostics)))
                           "thm:a"))))))

(deftest section-inside-abstract-does-not-number-test
  (testing "AC #4, found by review: a Section written inside an abstract
            used to be numbered from the body's own sequence, land in
            derive-toc as a top-level entry, and push the body's first
            real section to Section 2 -- one authored mistake silently
            renumbering the whole document"
    (let [source (str ":::{abstract}\n## Inner {#sec:inner}\n\nx\n:::\n\n"
                      "# Intro {#sec:i}\n\nSee @sec:i.\n")
          dir (fixture-dir! source)
          parsed (parser/parse source)
          labels (resolver/number-document parsed resolver/default-lexicon front-matter-names)
          {:keys [output diagnostics]} (build! dir {})]
      (is (= #{"sec:i"} (set (keys labels)))
          "nothing inside front matter takes a number")
      (is (= "1" (:number (get labels "sec:i")))
          "and the body's own first section is still Section 1")
      (is (= ["sec:i"] (mapv :id (resolver/derive-toc parsed labels front-matter-names)))
          "nor does it reach the table of contents")
      (is (str/includes? output "<a href=\"#sec:i\">Section 1</a>"))
      (is (some #(= :front-matter-section (:type %)) diagnostics)
          (pr-str (mapv :type diagnostics))))))

(deftest keywords-punctuation-survives-without-the-stylesheet-test
  (testing "AC #3, found by review: the label's colon and the separators
            between terms are real text, not stylesheet ::before/::after
            content. --no-stylesheet is a supported mode, and there the
            keywords line used to read KeywordsHazelHarvest while LaTeX
            printed Keywords: Hazel; Harvest -- the two targets saying
            different things about the same list"
    (let [dir (fixture-dir! document)
          html (:output (build! dir {:no-stylesheet true}))]
      (is (not (str/includes? html "<style>")))
      (is (str/includes? html ">Palavras-chave</span>: "))
      (is (str/includes? html "</span>; <span class=\"keyword\">Colheita</span>")))))

(deftest front-matter-side-file-names-test
  (testing "found by review: the duplicate check runs on the SANITIZED
            suffix, because that is what becomes a filename -- two blocks
            whose language tags differ only in a character that is not
            legal in a path used to write one file twice, losing the
            first block's content while reporting both paths"
    (let [dir (fixture-dir! (str ":::{abstract lang=\"pt/BR\"}\nA.\n:::\n\n"
                                 ":::{abstract lang=\"pt_BR\"}\nB.\n:::\n\n"
                                 "# I {#sec:i}\n\nx\n"))
          {:keys [front-matter-paths]} (build! dir {:target "latex" :fragment true})]
      (is (= 2 (count (set front-matter-paths))) (pr-str front-matter-paths))
      (is (str/includes? (slurp (first front-matter-paths)) "A."))
      (is (str/includes? (slurp (second front-matter-paths)) "B."))))
  (testing "an empty lang attribute is an absent one, not a language
            named the empty string -- it used to beat meta.lang, since an
            empty string is truthy, and name a file <output>-abstract-.tex"
    (let [dir (fixture-dir! "---\nlang: en\n---\n\n:::{abstract lang=\"\"}\nA.\n:::\n")
          {:keys [front-matter-paths]} (build! dir {:target "latex" :fragment true})]
      (is (str/ends-with? (first front-matter-paths) "-abstract-en.tex")
          (pr-str front-matter-paths))))
  (testing "and every side file records its own language INSIDE it, not
            only in its name: the file is what a host template reads, and
            a filename is not something the file can see"
    (let [dir (fixture-dir! document)
          {:keys [front-matter-paths]} (build! dir {:target "latex" :fragment true})]
      (is (str/includes? (slurp (first front-matter-paths))
                         "% haselnuss front-matter: abstract lang=pt-BR")))))

(deftest front-matter-side-file-clobber-test
  (testing "a side file the build did not generate is never overwritten,
            the same guard the .bib and the companion preamble already
            have -- and nothing else is left behind when it refuses"
    (let [dir (fixture-dir! document)
          target (io/file dir "doc-abstract-pt-BR.tex")]
      (spit target "\\textbf{mine}")
      (let [thrown (try (build! dir {:target "latex" :fragment true})
                        nil
                        (catch clojure.lang.ExceptionInfo e e))]
        (is (some? thrown))
        (is (= :haselnuss.cli/front-matter-would-clobber (:type (ex-data thrown))))
        (is (= "\\textbf{mine}" (slurp target)))
        (is (not (.exists (io/file dir "doc.tex"))))
        (is (not (.exists (io/file dir "doc-preamble.tex"))))))))

(deftest front-matter-marker-throws-test
  (testing "the marker registered as every front-matter block's renderer
            is never called by this codebase -- both emitters extract
            these blocks before dispatching anything -- but it is a
            function that THROWS rather than an inert value, because the
            registry is one table shared across targets and a consumer
            calling whatever it finds would otherwise splice something
            meaningless into its output. Same shape and reason as
            latex/environment-renderer"
    (let [thrown (try (front-matter/front-matter-renderer
                       {:t :directive :name "abstract"} :html)
                      nil
                      (catch clojure.lang.ExceptionInfo e e))]
      (is (some? thrown))
      (is (= :haselnuss.extensions.front-matter/front-matter-marker-called
             (:type (ex-data thrown))))
      (is (str/includes? (ex-message thrown) "abstract")))))

(deftest front-matter-in-a-chaptered-report-test
  (testing "the \\abstractname rename works in BOTH classes this emitter
            can produce: report's abstract is a completely different,
            titlepage-based environment from article's, and a document
            that opts into chapters gets report"
    (let [dir (fixture-dir! (str "---\ntitle: T\ntopLevelDivision: chapter\nlang: en\n---\n\n"
                                 ":::{abstract lang=pt-BR keywords=\"a; b\"}\nResumo.\n:::\n\n"
                                 ":::{abstract}\nAbstract.\n:::\n\n"
                                 "# Ch {#ch:a}\n\nx\n"))
          tex (:output (build! dir {:target "latex"}))]
      (is (str/starts-with? tex "\\documentclass{report}"))
      (let [[ok? log] (compiles? dir tex)]
        (is ok? (str "expected the chaptered two-abstract document to compile:\n" log))))))

;; ---------------------------------------------------------------------
;; TASK-55: the named front-matter prose blocks that extend the same
;; mechanism -- acknowledgements, epigraph, dedication.
;; ---------------------------------------------------------------------

(def ^:private prose-document
  "The three prose blocks the thesis has, in the order a book sets them,
  plus a body section to be excluded from."
  (str "---\ntitle: On Hazelnuts\nlang: en\n---\n\n"
       ":::{dedication}\nTo my parents.\n:::\n\n"
       ":::{epigraph}\n*Science is what we understand well enough to explain to a computer.*\n\n"
       "--- Donald Knuth\n:::\n\n"
       ":::{acknowledgements}\nThanks to everyone.\n\nAnd to the rest.\n:::\n\n"
       "# Introduction {#sec:intro}\n\nBody text referring to @sec:intro.\n"))

(deftest prose-blocks-reach-both-targets-test
  (testing "AC #1: acknowledgements, epigraph and dedication are
            authorable and reach both targets -- each set the way a book
            sets it, from the same data table, rather than through three
            branches in two emitters"
    (let [dir (fixture-dir! prose-document)
          html (:output (build! dir {}))
          tex (:output (build! dir {:target "latex"}))]
      (doseq [block-name ["dedication" "epigraph" "acknowledgements"]]
        (is (str/includes? html (str "<section class=\"" block-name "\" lang=\"en\""))
            block-name)
        (is (str/includes? tex (str "% haselnuss front-matter: " block-name " lang=en"))
            block-name))
      (testing "each with the shape its kind calls for"
        (is (str/includes? tex "\\begin{center}\\itshape\nTo my parents."))
        (is (str/includes? tex "\\begin{flushright}\\itshape"))
        (is (str/includes? tex "\\section*{Acknowledgements}"))
        (is (str/includes? html "<h1>Acknowledgements</h1>")))
      (testing "and an epigraph's attribution is the author's own last
                paragraph, carried as prose rather than as a field this
                emitter would have to punctuate"
        (is (str/includes? html "<p>--- Donald Knuth</p>"))
        (is (str/includes? tex "--- Donald Knuth")))
      (testing "the two that print no heading print none in EITHER
                target: a heading in one and not the other is a
                structural disagreement about the same document"
        (is (not (str/includes? html "<h1>Dedication</h1>")))
        (is (not (str/includes? html "<h1>Epigraph</h1>")))
        (is (not (str/includes? tex "{Dedication}")))
        (is (not (str/includes? tex "{Epigraph}"))))
      (testing "and the whole thing compiles"
        (let [[ok? log] (compiles? dir tex)]
          (is ok? (str "expected the document with three prose blocks to compile:\n" log)))))))

(deftest prose-blocks-are-excluded-like-the-abstract-test
  (testing "AC #2: they are excluded from numbering, from the TOC and
            from cross-referencing exactly as the abstract is -- the same
            body-view in the resolver, not a second mechanism"
    (let [parsed (parser/parse prose-document)
          labels (resolver/number-document parsed resolver/default-lexicon front-matter-names)]
      (is (= #{"sec:intro"} (set (keys labels))) (pr-str labels))
      (is (= ["sec:intro"] (mapv :id (resolver/derive-toc parsed labels front-matter-names))))))
  (testing "and a heading written inside one is diagnosed, not silently
            unnumbered, for all three kinds"
    (doseq [block-name ["acknowledgements" "epigraph" "dedication"]]
      (let [dir (fixture-dir! (str ":::{" block-name "}\n## Inner {#sec:x}\n\ny\n:::\n\n"
                                   "# I {#sec:i}\n\nz\n"))
            {:keys [diagnostics]} (build! dir {})]
        (is (some #(= :front-matter-section (:type %)) diagnostics) block-name)))))

(deftest unrecognised-front-matter-name-fails-the-build-test
  (testing "AC #3: a misspelled front-matter block name stops the build
            naming it, rather than being silently dropped -- a document
            that quietly loses a page is worse than one that fails to
            convert. This is INHERITED, not built: a name absent from the
            table is simply an unregistered directive, which the resolver
            already warns about and lower already refuses to represent.
            Asserted because it is the whole failure mode this task is
            designed against"
    (doseq [target ["html" "latex"]]
      (let [dir (fixture-dir! ":::{acknowledgments}\nThanks.\n:::\n\n# I {#sec:i}\n\nx\n")
            thrown (try (build! dir {:target target}) nil (catch clojure.lang.ExceptionInfo e e))]
        (is (some? thrown) target)
        (is (= :haselnuss.lower/no-representation (:type (ex-data thrown))) target)
        (is (str/includes? (ex-message thrown) "acknowledgments")
            "the message must name the misspelling, not the category")
        (testing "and the warning that explains it is carried alongside,
                  so an author sees both halves"
          (is (some #(= :unknown-directive (:type %))
                    (:haselnuss.cli/diagnostics (ex-data thrown)))))))))

(deftest prose-blocks-fragment-side-files-test
  (testing "AC #4: each reaches a host template as its own side file with
            no environment around it, exactly as the abstract does -- the
            mechanism is shared, so this is a check that nothing about
            the three new kinds bypasses it"
    (let [dir (fixture-dir! prose-document)
          {:keys [output front-matter-paths]}
          (build! dir {:target "latex" :fragment true
                       :output (str (io/file dir "body.tex"))})]
      (is (= [(str (io/file dir "body-dedication-en.tex"))
              (str (io/file dir "body-epigraph-en.tex"))
              (str (io/file dir "body-acknowledgements-en.tex"))]
             front-matter-paths)
          "one file per block, in document order")
      (doseq [file front-matter-paths]
        (let [content (slurp file)]
          (is (not (str/includes? content "\\begin{center}")) file)
          (is (not (str/includes? content "\\begin{flushright}")) file)
          (is (not (str/includes? content "\\section*")) file)))
      (is (not (str/includes? output "Donald Knuth"))
          "and none of them is left in the body fragment")
      (testing "and a host placing them in environments of its own compiles"
        (let [[ok? log] (compiles? dir
                                   (str "\\documentclass{article}\n"
                                        "\\input{body-preamble}\n"
                                        "\\begin{document}\n"
                                        "\\begin{center}\\input{body-dedication-en}\\end{center}\n"
                                        "\\begin{flushright}\\input{body-epigraph-en}\\end{flushright}\n"
                                        "\\section*{Thanks}\\input{body-acknowledgements-en}\n"
                                        "\\input{body}\n"
                                        "\\end{document}\n"))]
          (is ok? (str "expected the host document to compile:\n" log)))))))

(deftest prose-blocks-in-a-chaptered-report-test
  (testing "found by review: the three new shapes were only ever compiled
            in article, and one of them is class-dependent. In a
            chaptered document an acknowledgements page is chapter-level
            furniture, so it uses \\chapter* -- \\section* set it beneath
            every chapter title in the one document shape this milestone
            exists for"
    (let [dir (fixture-dir! (str "---\ntitle: T\ntopLevelDivision: chapter\nlang: en\n---\n\n"
                                 ":::{dedication}\nTo my parents.\n:::\n\n"
                                 ":::{epigraph}\nA quotation.\n:::\n\n"
                                 ":::{acknowledgements}\nThanks.\n:::\n\n"
                                 "# Ch {#ch:a}\n\nx\n"))
          tex (:output (build! dir {:target "latex"}))]
      (is (str/starts-with? tex "\\documentclass{report}"))
      (is (str/includes? tex "\\chapter*{Acknowledgements}"))
      (is (not (str/includes? tex "\\section*{Acknowledgements}")))
      (let [[ok? log] (compiles? dir tex)]
        (is ok? (str "expected the chaptered document with three prose blocks to compile:\n"
                     log)))))
  (testing "and a document that did not opt in still gets \\section*"
    (let [dir (fixture-dir! ":::{acknowledgements}\nThanks.\n:::\n\n# I {#sec:i}\n\nx\n")]
      (is (str/includes? (:output (build! dir {:target "latex"}))
                         "\\section*{Acknowledgements}")))))

(deftest prose-blocks-print-their-own-language-test
  (testing "AC #1: every one of the three new rows carries both languages,
            like every kind in the resolver's own lexicon -- the pt-BR
            half was unasserted, so a missing or misspelled word would
            have shipped (found by review)"
    (let [dir (fixture-dir! (str "---\nlang: pt-BR\n---\n\n"
                                 ":::{acknowledgements}\nObrigado.\n:::\n\n"
                                 ":::{epigraph}\nUma citação.\n:::\n\n"
                                 ":::{dedication}\nAos meus pais.\n:::\n\n"
                                 "# I {#sec:i}\n\nx\n"))
          html (:output (build! dir {}))
          tex (:output (build! dir {:target "latex"}))]
      (is (str/includes? html "<h1>Agradecimentos</h1>"))
      (is (str/includes? tex "\\section*{Agradecimentos}"))
      (testing "and the two that print no heading still carry their word
                as the section's accessible name, which is what stops a
                heading-less <section> from being an unlabelled region"
        (is (str/includes? html "aria-label=\"Epígrafe\""))
        (is (str/includes? html "aria-label=\"Dedicatória\""))
        (is (not (str/includes? html "aria-label=\"Acknowledgements\""))
            "a headed block names itself with its heading and needs no label")))))

(deftest emphasis-agrees-between-targets-in-an-italic-block-test
  (testing "found by review: \\emph inside \\itshape toggles OUT of
            italic, which is typographically right and which a browser
            does not do on its own -- so the same authored *word* came
            out upright in the PDF and italic in HTML. One document, two
            renderings of one piece of markup. LaTeX's behaviour is the
            correct one, so the stylesheet makes HTML agree"
    (let [dir (fixture-dir! (str ":::{epigraph}\n*Quoted.*\n:::\n\n"
                                 ":::{dedication}\n*Also quoted.*\n:::\n\n"
                                 "# I {#sec:i}\n\nx\n"))
          html (:output (build! dir {}))]
      (is (str/includes? html "section.epigraph em,section.dedication em{font-style:normal}"))
      (is (str/includes? html "<em>Quoted.</em>")
          "the emphasis itself is still authored markup, still emitted"))))

(deftest prose-blocks-stay-out-of-the-toc-when-typeset-test
  (testing "AC #2 at the typesetting level, not only at the AST level: a
            starred sectioning command writes nothing into the .toc, so a
            real pdflatex run over the emitted document produces a table
            of contents holding the body's own headings and none of the
            front matter. The AST-level check cannot see this -- it is
            LaTeX, not this codebase, that decides what a .toc gets"
    (let [dir (fixture-dir! (str "---\ntitle: T\ntopLevelDivision: chapter\n---\n\n"
                                 ":::{dedication}\nTo my parents.\n:::\n\n"
                                 ":::{acknowledgements}\nThanks.\n:::\n\n"
                                 "# Real {#ch:real}\n\n## Inner {#sec:in}\n\nx\n"))
          tex (:output (build! dir {:target "latex"}))
          ;; `\tableofcontents` is injected rather than authored: emitting
          ;; one is TASK-59's job, and this test is about what the
          ;; front-matter shapes contribute to a .toc, not about how one
          ;; gets requested.
          with-toc (str/replace tex "\\begin{document}\n"
                                "\\begin{document}\n\\tableofcontents\n")]
      (spit (io/file dir "host.tex") with-toc)
      (dotimes [_ 2]
        (shell/sh "pdflatex" "-interaction=nonstopmode" "host.tex" :dir dir))
      (let [toc (slurp (io/file dir "host.toc"))]
        (is (str/includes? toc "Real"))
        (is (str/includes? toc "Inner"))
        (is (not (str/includes? toc "Acknowledgements")) toc)
        (is (not (str/includes? toc "parents")) toc)))))

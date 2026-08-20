(ns haselnuss.cross-format-test
  "The cross-format numbering invariant (TASK-27): one authored document
  converted to both targets must agree, exactly, on every computed
  number and on its bibliography.

  This is the invariant that makes Haselnuss useful as a lingua franca --
  the task's own words: \"authoring once must not mean numbering drifts
  between targets.\" Everything here is therefore written as a
  *comparison between the two outputs*, never as two independent
  assertions about strings. A test that separately checks each output
  contains \"Figure 1.1\" passes just as well when both are wrong, and
  cannot fail when the two drift apart -- which is the only thing this
  file exists to catch.

  LaTeX is generated in computed-numbers mode (AC #4). Native mode
  deliberately hands numbering to LaTeX and citation formatting to
  BibTeX (decision D4), so its `.tex` carries no numbers at all to
  compare -- that is the mode's purpose, not a gap.

  The fixture lives in `test/fixtures/` as real files rather than a
  string in this namespace, because AC #1 is about a document, and
  because the same fixture is what a human would open to check the two
  outputs by eye."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [haselnuss.cli :as cli])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(def ^:private referenced-ids
  "Every id the fixture cross-references from its body text. Asserted
  explicitly so an extraction that silently found nothing cannot pass
  the equality check below by comparing two empty maps."
  #{"fig:tree" "fig:leaf" "eq:mass" "eq:energy" "sec:background" "sec:results" "tbl:yields"})

(defn- build-outputs!
  "Converts the committed fixture to both targets in a fresh temp
  directory (copying the fixture and the images its figures name), and
  returns `{:html :latex}` -- the two emitted documents as strings.

  Both go through `haselnuss.cli/build`, the same entry point the
  packaged jar uses, so this compares what a user actually gets rather
  than what the emitters would produce given hand-built inputs."
  []
  (let [dir (str (Files/createTempDirectory "haselnuss-xformat" (make-array FileAttribute 0)))
        input (str (io/file dir "cross-format.hdoc"))]
    ;; Read off the test classpath rather than a CWD-relative path, so
    ;; this does not depend on the runner's working directory. No image
    ;; files are written: nothing here compiles the .tex, and both
    ;; emitters reference a figure's `src` without reading it.
    (doseq [f ["cross-format.hdoc" "cross-format.json"]]
      (io/copy (io/reader (io/resource (str "fixtures/" f))) (io/file dir f)))
    {:html (slurp (:output-path (cli/build {:input input :target "html"})))
     :latex (slurp (:output-path (cli/build {:input input :target "latex"
                                             :computed-numbers true})))}))

(defn- body-only
  "`text` with its DERIVED LISTS removed (TASK-59): the `<nav>` blocks in
  HTML, and the `\\section*{Contents}`/`\\section*{List of Figures}`
  runs in computed-numbers LaTeX.

  Necessary because a list entry is a link to a numbering target, in
  exactly the shape a cross-reference is -- so without this the
  reference extractors below would count every list entry as a
  reference, and \"the two targets print the same number for every
  reference\" would silently become a different claim. The lists get
  their own extraction and their own equality check further down."
  [text]
  (-> text
      ;; `<nav[^>]*class=` and not `<nav class=`: the moment a
      ;; placeholder in the fixture carries an id of its own the markup
      ;; is `<nav id="..." class="toc">`, and a literal match would
      ;; silently stop stripping (found by review).
      (str/replace #"(?s)<nav[^>]*class=\"(?:toc|list-of-figures|list-of-tables)\"[^>]*>.*?</nav>" "")
      ;; `\\chapter*` as well as `\\section*`: a chaptered document sets
      ;; each list heading as a chapter, so a section-only pattern left
      ;; the thesis fixture's own lists inside the body it exists to
      ;; strip.
      (str/replace #"(?s)\\(?:chapter|section)\*\{(?:Contents|List of Figures|List of Tables)\}.*?(?=\\chapter|\\section)"
                   "")))

(defn- build-thesis-outputs!
  "The same two builds for the thesis-shaped fixture (TASK-61): one
  chaptered document carrying every construct this milestone added,
  converted to both targets.

  A second fixture rather than more constructs in the first, because
  the two documents are different SHAPES and the numbering questions
  they ask are different: a paper numbers figures against sections, a
  book numbers them against chapters, and `ch`, `lst` and `alg` exist
  only in the second. `haselnuss.thesis-test` compiles it; this
  namespace only compares the two outputs to each other."
  []
  (let [dir (str (Files/createTempDirectory "haselnuss-thesis-xformat"
                                            (make-array FileAttribute 0)))
        input (str (io/file dir "thesis.hdoc"))]
    (doseq [f ["thesis.hdoc" "thesis.json"]]
      (io/copy (io/reader (io/resource (str "fixtures/" f))) (io/file dir f)))
    {:html (slurp (:output-path (cli/build {:input input :target "html"})))
     :latex (slurp (:output-path (cli/build {:input input :target "latex"
                                             :computed-numbers true})))}))

(defn- html-reference-numbers
  "The `[id text]` pairs HTML rendered for every cross-reference --
  every `<a href=\"#id\">text</a>` whose id is one of
  `referenced-ids`, restricted so a citation's own link into the
  bibliography list is not mistaken for a cross-reference.

  A *bag* of pairs (a frequency map), not a map keyed by id: the fixture
  references `eq:mass` twice, and `into {}` silently kept only the last
  one -- so rewriting just the first occurrence's number left the two
  sides comparing equal (TASK-27 review). Every occurrence now has to
  match, in both count and text."
  [html]
  (frequencies
   (into []
         (comp (map (fn [[_ id text]] [id text]))
               (filter (comp referenced-ids first)))
         (re-seq #"<a href=\"#([^\"]+)\">([^<]*)</a>" html))))

(defn- latex-reference-numbers
  "The `[id text]` pairs LaTeX rendered for every cross-reference --
  every `\\hyperref[id]{text}`, computed-numbers mode's own form for a
  resolved CrossRef. A frequency map, for the same reason as
  `html-reference-numbers`."
  [tex]
  (frequencies
   (mapv (fn [[_ id text]] [id text])
         (re-seq #"\\hyperref\[([^\]]+)\]\{([^}]*)\}" tex))))

(defn- printed-numbers
  "A map from target id to the number printed ON that target itself,
  extracted with `pattern` (whose two groups are id and number, in the
  order `order` names).

  Keyed by id deliberately, and this is the point: an unanchored
  `re-find` for `\\tag{1.1}` proves only that *some* equation printed
  1.1, never that `eq:mass` did -- so swapping two equations' numbers in
  both targets left every such assertion green while each equation
  contradicted the references to it (TASK-27 review, and the exact
  failure TASK-24's own review found)."
  [pattern order text]
  (into {} (map (fn [[_ a b]] (if (= :id-first order) [a b] [b a])))
        (re-seq pattern text)))

(defn- bibliography-entries
  "The reference-list entries in `text`, in document order, one per
  `pattern` match, whitespace-normalized so the two targets' own line
  wrapping does not masquerade as a difference in content.

  A vector, not a set: the bibliography is an ordered list in both
  targets and an in-text citation's number is a *position* in it, so
  comparing sets let the two agree on content while `[1]` meant Knuth in
  one output and Lamport in the other (TASK-27 review)."
  [pattern text]
  (mapv (fn [[_ entry]] (str/trim (str/replace entry #"\s+" " ")))
        (re-seq pattern text)))

(defn- listed-numbers
  "The `{id number-text}` pairs a DERIVED LIST printed (TASK-59):
  everything `pattern` matched inside `text`, where `pattern`'s two
  groups are the entry's id and the text it printed for it.

  Extracted per id like every other number in this namespace, and for
  the same reason: a list is a third place the same number is printed --
  beside the node itself and beside every reference to it -- by a third
  code path, so it is a third place the number can drift."
  [pattern text]
  (into {} (map (fn [[_ id printed]] [id (str/trim printed)])) (re-seq pattern text)))

(deftest cross-format-numbering-invariant-test
  (let [{:keys [html latex]} (build-outputs!)
        html-refs (html-reference-numbers (body-only html))
        latex-refs (latex-reference-numbers (body-only latex))
        ;; The number printed on each target itself, keyed by that
        ;; target's own id -- so "the figure says what references to it
        ;; say" is checked per node, not per document.
        html-figures (printed-numbers #"<figure id=\"([^\"]+)\">.*?<figcaption>([^:<]+):"
                                      :id-first html)
        ;; Scoped to the figure environment (TASK-59): a longtable's
        ;; caption has the identical shape, so an unscoped pattern
        ;; started reporting the fixture's table as a figure the moment
        ;; the fixture grew one.
        latex-figures (printed-numbers
                       #"(?s)\\begin\{figure\}.*?\\caption\*\{([^:}]+):[^}]*\}\s*\\phantomsection\\label\{([^}]+)\}"
                       :number-first latex)
        html-equations (printed-numbers
                        #"<div id=\"([^\"]+)\"[^>]*class=\"math display\"[^>]*><span class=\"math-number\"[^>]*>\(([^)]+)\)</span>"
                        :id-first html)
        latex-equations (printed-numbers
                         #"(?s)\\tag\{([^}]+)\}\s*\\label\{([^}]+)\}" :number-first latex)
        ;; TASK-41: the number a Section prints in its own heading.
        ;; Extracted per id, like the figures and equations above, so a
        ;; heading and the references to it cannot drift apart -- which
        ;; is exactly what they did before TASK-41, when neither target
        ;; printed one at all and "See Section 1" sat above a heading
        ;; with no number in it.
        html-sections (printed-numbers
                       #"<section id=\"([^\"]+)\"[^>]*><h\d><span class=\"section-number\">([^<]+)</span>"
                       :id-first html)
        latex-sections (printed-numbers
                        #"\\(?:sub)*section\*\{([0-9.]+)\\quad [^}]*\}\\phantomsection\\label\{([^}]+)\}"
                        :number-first latex)]
    (testing "AC #1: the fixture really does contain figures, equations
              and citations referenced from the body text -- checked
              against the outputs, so the invariant below cannot pass
              vacuously on a fixture that lost a construct"
      (is (= referenced-ids (set (map first (keys html-refs)))) (pr-str html-refs))
      (is (= referenced-ids (set (map first (keys latex-refs)))) (pr-str latex-refs))
      (is (= 8 (reduce + (vals html-refs)))
          "eight reference occurrences, one of them a repeat, so a last-wins extraction cannot hide a drift")
      (is (re-find #"<figure id=\"fig:tree\">" html))
      (is (re-find #"class=\"math display\"" html))
      (is (re-find #"<section id=\"sec:bibliography\"" html)))

    (testing "AC #2: html and latex report the SAME number for every
              figure and equation reference -- one equality between the
              two extracted bags, so a drift in either direction, at any
              occurrence, fails"
      (is (= html-refs latex-refs)
          (str "cross-format numbering drift:\n  html:  " (pr-str (sort html-refs))
               "\n  latex: " (pr-str (sort latex-refs)))))

    (testing "AC #2: and the numbers are per-section, so the invariant is
              being checked on a document where numbering could actually
              diverge rather than one where everything is 1"
      (is (= {["fig:tree" "Figure 1.1"] 1
              ["fig:leaf" "Figure 2.1"] 1
              ["eq:mass" "Eq. (1.1)"] 2
              ["eq:energy" "Eq. (2.1)"] 1
              ["sec:background" "Section 1"] 1
              ["sec:results" "Section 2"] 1
              ["tbl:yields" "Table 2.1"] 1}
             html-refs)))

    (testing "each target also agrees with ITSELF, per node: the number
              printed on a given figure/equation is the number every
              reference to THAT id prints. TASK-24's review found the
              opposite failure -- outputs agreeing with each other while
              both contradicted their own captions -- and an unanchored
              search for the number would not catch two nodes' numbers
              being swapped"
      (is (= {"fig:tree" "Figure 1.1" "fig:leaf" "Figure 2.1"} html-figures))
      (is (= html-figures latex-figures) (pr-str [html-figures latex-figures]))
      (is (= {"eq:mass" "1.1" "eq:energy" "2.1"} html-equations))
      (is (= html-equations latex-equations) (pr-str [html-equations latex-equations]))
      (testing "AC #4 of TASK-41: a section heading's own number, in both
                targets, so it cannot drift from the references to it --
                the drift this whole test exists for, on the one node
                type it did not previously cover"
        (is (= {"sec:background" "1" "sec:results" "2"} html-sections))
        (is (= html-sections latex-sections) (pr-str [html-sections latex-sections])))
      (testing "and each printed number is the one its own references use"
        (doseq [[id number] html-equations]
          (is (contains? html-refs [id (str "Eq. (" number ")")]) id))
        (doseq [[id label] html-figures]
          (is (contains? html-refs [id label]) id))
        (doseq [[id number] html-sections]
          (is (contains? html-refs [id (str "Section " number)]) id))))

    (testing "TASK-59 AC #4: the number printed in a derived LIST is the
              number printed on the node itself and by every reference
              to it -- three code paths for one number, in both targets.
              The lists are built from the resolver's derivations in
              HTML and in computed-numbers LaTeX alike, so a drift
              between them is a drift between two documents that claim
              to be the same one"
      (let [html-listed (listed-numbers
                         #"<nav class=\"list-of-figures\">.*?</nav>|<a href=\"#([^\"]+)\">([^:<]+):"
                         (or (second (re-find #"(?s)<nav class=\"list-of-figures\">(.*?)</nav>" html)) ""))
            latex-listed (listed-numbers
                          #"\\hyperref\[([^\]]+)\]\{([^:}]+):"
                          (or (second (re-find #"(?s)\\section\*\{List of Figures\}(.*?)\\section"
                                               latex)) ""))
            html-tables (printed-numbers
                         #"<table id=\"([^\"]+)\"><caption>([^:<]+):" :id-first html)
            latex-tables (printed-numbers
                          #"(?s)\\begin\{longtable\}.*?\\caption\*\{([^:}]+):[^}]*\}\\phantomsection\\label\{([^}]+)\}"
                          :number-first latex)
            html-listed-tables (listed-numbers
                                #"<a href=\"#([^\"]+)\">([^:<]+):"
                                (or (second (re-find #"(?s)<nav[^>]*class=\"list-of-tables\"[^>]*>(.*?)</nav>"
                                                     html)) ""))
            latex-listed-tables (listed-numbers
                                 #"\\hyperref\[([^\]]+)\]\{([^:}]+):"
                                 (or (second (re-find #"(?s)\\section\*\{List of Tables\}(.*)"
                                                      latex)) ""))
            html-toc (listed-numbers
                      #"<a href=\"#([^\"]+)\"><span class=\"toc-number\">([^<]+)</span>"
                      (or (second (re-find #"(?s)<nav class=\"toc\">(.*?)</nav>" html)) ""))
            latex-toc (listed-numbers
                       #"\\hyperref\[([^\]]+)\]\{([0-9.]+)\\quad"
                       (or (second (re-find #"(?s)\\section\*\{Contents\}(.*?)\\section" latex)) ""))]
        (testing "the lists are not empty, so the equalities below cannot
                  pass by comparing two empty maps"
          (is (= #{"fig:tree" "fig:leaf"} (set (keys html-listed))) (pr-str html-listed))
          (is (= #{"sec:background" "sec:results"} (set (keys html-toc))) (pr-str html-toc)))
        (is (= html-listed latex-listed)
            (str "list-of-figures drift:\n  html:  " (pr-str html-listed)
                 "\n  latex: " (pr-str latex-listed)))
        (is (= html-figures html-listed)
            "the list of figures prints the number the figure itself prints")
        (is (= html-toc latex-toc)
            (str "table-of-contents drift:\n  html:  " (pr-str html-toc)
                 "\n  latex: " (pr-str latex-toc)))
        (is (= html-sections html-toc)
            "the table of contents prints the number the heading itself prints")
        (testing "and the list of TABLES, the third derivation, held to
                  the same rule -- it was the one this fixture did not
                  exercise at all (found by review)"
          (is (= {"tbl:yields" "Table 2.1"} html-tables) (pr-str html-tables))
          (is (= html-tables latex-tables) (pr-str [html-tables latex-tables]))
          (is (= html-tables html-listed-tables)
              "the list of tables prints the number the table itself prints")
          (is (= html-listed-tables latex-listed-tables)
              (str "list-of-tables drift:\n  html:  " (pr-str html-listed-tables)
                   "\n  latex: " (pr-str latex-listed-tables))))))

    (testing "AC #3: the two bibliographies contain the same entries, in
              the same ORDER -- an in-text citation's number is a
              position in that list, so equal-as-sets would let [1] mean
              a different source in each output"
      (let [html-entries (bibliography-entries #"<li id=\"sec:bibliography-\d+\">(.*?)</li>" html)
            latex-entries (bibliography-entries
                           #"(?s)\\item (.*?)(?=\n\\item |\n\\end\{enumerate\})" latex)]
        (is (= 2 (count html-entries)) (pr-str html-entries))
        (is (= html-entries latex-entries)
            (str "bibliography drift:\n  html:  " (pr-str html-entries)
                 "\n  latex: " (pr-str latex-entries)))
        (testing "and each in-text citation number resolves to the same
                  entry in both, which is what makes the order matter"
          (doseq [[position source] {1 "Knuth" 2 "Lamport"}]
            (is (str/includes? (nth html-entries (dec position)) source))
            (is (str/includes? (nth latex-entries (dec position)) source))
            (is (str/includes? html (str "[" position "]")))
            (is (str/includes? latex (str "[" position "]")))))
        (testing "a multi-key citation agrees too"
          (is (str/includes? html "[1; 2]"))
          (is (str/includes? latex "[1; 2]")))))))

(def ^:private thesis-referenced-ids
  "Every id the thesis fixture cross-references from its body text --
  one per kind this milestone added, plus the older ones, so the
  comparison below cannot pass by comparing two empty bags."
  #{"ch:methods" "sec:model" "fig:tree" "fig:sens" "fig:sens-b" "fig:leaf"
    "tbl:yield" "eq:mass" "lst:dine" "alg:bisect"})

(deftest thesis-cross-format-numbering-invariant-test
  (testing "TASK-61 AC #2: the invariant covers the kinds this milestone
            added -- ch, lst and alg, and a subfigure's sublabel -- on a
            document shaped like the one they exist for. Those numbers
            are computed by paths the paper fixture never exercises: a
            chapter counts structurally, a listing and an algorithm count
            globally against their own float counters, and a panel's
            letter is composed onto whatever number its figure already
            got"
    (let [{:keys [html latex]} (build-thesis-outputs!)
          html-refs (frequencies
                     (into []
                           (comp (map (fn [[_ id text]] [id text]))
                                 (filter (comp thesis-referenced-ids first)))
                           (re-seq #"<a href=\"#([^\"]+)\">([^<]*)</a>" (body-only html))))
          latex-refs (frequencies
                      (into []
                            (comp (map (fn [[_ id text]] [(str/replace id "--" "-") text]))
                                  (filter (comp thesis-referenced-ids first)))
                            (re-seq #"\\hyperref\[([^\]]+)\]\{([^}]*)\}" (body-only latex))))]
      (testing "the fixture really does reference one of each, so the
                equality below cannot pass vacuously"
        (is (= thesis-referenced-ids (set (map first (keys html-refs)))) (pr-str html-refs)))
      (testing "and the numbers are the chaptered ones -- a figure
                composed against its chapter, a panel against its
                figure, a listing and an algorithm against nothing"
        (is (= {["ch:methods" "Chapter 2"] 1
                ["sec:model" "Section 2.1"] 1
                ["fig:tree" "Figure 1.1"] 1
                ["fig:sens" "Figure 2.1"] 1
                ;; The repeats matter: the fixture refers to the panel,
                ;; the algorithm and the table twice each, and a
                ;; last-wins extraction would hide a drift in one of the
                ;; two occurrences (the shape TASK-27's own review
                ;; found).
                ["fig:sens-b" "Figure 2.1b"] 2
                ["fig:leaf" "Figure 2.2"] 1
                ["tbl:yield" "Table 2.1"] 2
                ["eq:mass" "Eq. (2.1)"] 2
                ["lst:dine" "Listing 1"] 1
                ["alg:bisect" "Algorithm 1"] 2}
               html-refs)
            (pr-str html-refs)))
      (is (= html-refs latex-refs)
          (str "cross-format numbering drift on the thesis fixture:\n  html:  "
               (pr-str (sort html-refs)) "\n  latex: " (pr-str (sort latex-refs))))
      (testing "and each of those numbers is what the NODE ITSELF prints,
                per id, in both targets -- the half a reference bag
                cannot see (found by review). An algorithm captioned
                'Algorithm 2' while every reference to it read 'Algorithm
                1' failed nothing before this"
        (let [decode (fn [m] (into {} (map (fn [[id text]] [(str/replace id "--" "-") text])) m))
              ;; `(?s)`, because a listing's and an algorithm's own body
              ;; is a code block with real newlines in it -- without it
              ;; the two floats this test exists to cover were the two
              ;; the pattern silently skipped.
              html-floats (merge (printed-numbers
                                  #"(?s)<figure id=\"([^\"]+)\"[^>]*>.*?<figcaption>([^:<]+):"
                                  :id-first html)
                                 ;; A Table is a `<table>` with a
                                 ;; `<caption>`, not a `<figure>`; the
                                 ;; LaTeX side needs no such split, since
                                 ;; every float there captions the same
                                 ;; way.
                                 (printed-numbers
                                  #"<table id=\"([^\"]+)\"><caption>([^:<]+):"
                                  :id-first html))
              latex-floats (printed-numbers
                            #"\\caption\*\{([^:}]+):[^}]*\}\s*\\phantomsection\\label\{([^}]+)\}"
                            :number-first latex)
              html-chapters (printed-numbers
                             #"<section id=\"(ch:[^\"]+)\"[^>]*><h1><span class=\"section-number\">([^<]+)</span>"
                             :id-first html)
              latex-chapters (printed-numbers
                              #"\\chapter\*\{([0-9.]+)\\quad [^}]*\}\\phantomsection\\label\{(ch:[^}]+)\}"
                              :number-first latex)
              html-panels (printed-numbers
                           #"<figure id=\"(fig:sens-[^\"]+)\" class=\"subfigure\">.*?<figcaption>\(([a-z])\)"
                           :id-first html)
              latex-panels (printed-numbers
                            #"\\caption\*\{\(([a-z])\)[^}]*\}\s*\\phantomsection\\label\{(fig:sens--[^}]+)\}"
                            :number-first latex)]
          (testing "the floats -- figure, listing and algorithm captions
                    alike, since all three are one float mechanism"
            (is (= {"fig:tree" "Figure 1.1"
                    "fig:sens" "Figure 2.1"
                    "fig:leaf" "Figure 2.2"
                    "tbl:yield" "Table 2.1"
                    "lst:dine" "Listing 1"
                    "alg:bisect" "Algorithm 1"}
                   html-floats)
                (pr-str html-floats))
            (is (= html-floats (decode latex-floats))
                (str "body-number drift:\n  html:  " (pr-str html-floats)
                     "\n  latex: " (pr-str (decode latex-floats)))))
          (testing "the chapter headings, which no fixture in this suite
                    compared before -- the paper test's own pattern
                    cannot match \\chapter*"
            (is (= {"ch:intro" "1" "ch:methods" "2"} html-chapters) (pr-str html-chapters))
            (is (= html-chapters (decode latex-chapters))))
          (testing "and the panel letters, on the panels themselves"
            (is (= {"fig:sens-a" "a" "fig:sens-b" "b" "fig:sens-c" "c"} html-panels)
                (pr-str html-panels))
            (is (= html-panels (decode latex-panels))))
          (testing "and every one of those printed numbers is the one its
                    own references print"
            (doseq [[id text] html-floats]
              (is (or (contains? html-refs [id text])
                      (not (contains? thesis-referenced-ids id)))
                  (str id " prints " text " but is referenced as something else"))))))
      (testing "and the three derived lists print those same numbers --
                the third place each is written, by a third code path"
        (let [html-listed (listed-numbers
                           #"<a href=\"#([^\"]+)\">([^:<]+):"
                           (or (second (re-find #"(?s)<nav[^>]*class=\"list-of-figures\"[^>]*>(.*?)</nav>"
                                                html)) ""))
              latex-listed (listed-numbers
                            #"\\hyperref\[([^\]]+)\]\{([^:}]+):"
                            (or (second (re-find #"(?s)\\chapter\*\{List of Figures\}(.*?)\\chapter"
                                                 latex)) ""))]
          (is (= {"fig:tree" "Figure 1.1" "fig:sens" "Figure 2.1" "fig:leaf" "Figure 2.2"}
                 html-listed)
              (pr-str html-listed))
          (is (= html-listed (into {} (map (fn [[id text]] [(str/replace id "--" "-") text]))
                                   latex-listed))
              (str "list-of-figures drift:\n  html:  " (pr-str html-listed)
                   "\n  latex: " (pr-str latex-listed)))
          ;; A panel is in none of them either -- a list of figures
          ;; lists the figure, not its subfigures -- which the exact
          ;; map above already says, so it needs no assertion of its own
          ;; (found by review).
          )))))

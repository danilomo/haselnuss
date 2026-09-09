(ns haselnuss.emit.hdoc-test
  "Fixtures for `haselnuss.emit.hdoc` (TASK-95): the `.hdoc` emitter's own
  half of Block (`:section`/`:para`/`:thematic-break`/`:block-quote`) plus
  every Inline variant. `haselnuss.parser/parse` is the oracle throughout
  (this namespace's own docstring, doc-7 decision 4): every test proves a
  round trip by actually parsing, emitting, and re-parsing real `.hdoc`
  source rather than asserting on the emitted string's own shape, so a
  test here would fail the same way a real caller's build would."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [haselnuss.ast :as ast]
            [haselnuss.emit.hdoc :as hdoc]
            [haselnuss.parser :as parser]
            [haselnuss.select :as select]))

(defn- fixed-point?
  "True if `(parse (emit (parse src))) = (parse src)` -- the AST fixed
  point doc-7/TASK-99 hold this emitter to, checked directly against the
  real parser rather than against the emitted text's own shape."
  [src]
  (let [document (parser/parse src)]
    (= document (parser/parse (hdoc/emit-document document)))))

(defn- str-inline [text] {:t :str :text text})
(def ^:private space {:t :space})
(def ^:private empty-attr {:classes [] :props {}})

(defn- para
  "A minimal :para Block wrapping `inlines`, for a table-cell fixture that
  needs one directly rather than through a full document parse."
  [& inlines]
  {:t :para :inlines (vec inlines)})

(deftest emit-document-entry-point-test
  (testing "AC #1: (emit-document document) and (emit-document document
            opts) both work, matching haselnuss.emit.html/emit-document's
            own two-arity shape"
    (let [document (parser/parse "Hello *world*.\n")]
      (is (string? (hdoc/emit-document document)))
      (is (string? (hdoc/emit-document document {})))
      (is (= (hdoc/emit-document document) (hdoc/emit-document document {}))))))

(deftest declared-default-dispatch-test
  (testing "AC #2: an unrecognized Block :t throws ex-info naming it, not
            a silent drop or a generic failure (TASK-88's own contract).
            Every real haselnuss.ast/registry Block variant has a handler
            after TASK-97 (block-and-inline-coverage-test proves that by
            a subset check), so this uses a genuinely bogus tag rather
            than a real one that merely hadn't been implemented yet"
    (let [document {:meta {} :blocks [{:t :not-a-real-block-type}]}
          e (is (thrown? clojure.lang.ExceptionInfo (hdoc/emit-document document)))]
      (is (= ::hdoc/unsupported-block (:type (ex-data e))))
      (is (= :not-a-real-block-type (:t (:block (ex-data e)))))
      (is (str/includes? (ex-message e) ":not-a-real-block-type"))))
  (testing "an unrecognized Inline :t throws the same way"
    (let [document {:meta {} :blocks [{:t :para :inlines [{:t :bogus}]}]}
          e (is (thrown? clojure.lang.ExceptionInfo (hdoc/emit-document document)))]
      (is (= ::hdoc/unsupported-inline (:type (ex-data e))))
      (is (str/includes? (ex-message e) ":bogus"))))
  (testing "dispatch is a plain :t -> handler MAP, not a case/defmulti:
            emit-document's own :block-handlers/:inline-handlers opt
            merges a document-specific handler in for one call, with no
            edit to this namespace at all -- proving `get` semantics
            rather than mere behavior"
    (let [document {:meta {} :blocks [{:t :list :ordered false :tight true
                                       :items [] :attr empty-attr}]}
          out (hdoc/emit-document document {:block-handlers {:list (fn [_ctx _block fns] ["<LIST>" fns])}})]
      (is (= "<LIST>\n" out)))))

(deftest front-matter-round-trip-test
  (testing "AC #3: every key haselnuss.parser/parse-front-matter maps
            round-trips through emit-document -- title, authors, date,
            bibliography, cslStyle, lang and topLevelDivision all
            together, in one document"
    (let [src (str "---\n"
                   "title: \"A long title with a colon: yes\"\n"
                   "authors:\n  - Ada Lovelace\n  - Alan Turing\n"
                   "date: 2026-01-01\n"
                   "bibliography: refs.json\n"
                   "cslStyle: apa\n"
                   "lang: en\n"
                   "topLevelDivision: chapter\n"
                   "---\n\nBody text.\n")]
      (is (fixed-point? src))
      (let [document (parser/parse src)]
        (is (= "A long title with a colon: yes"
               (str/join (map (fn [inline] (case (:t inline) :str (:text inline) :space " " ""))
                              (:title (:meta document))))))
        (is (= #{:title :authors :date :bibliography :csl-style :lang :top-level-division}
               (set (keys (:meta document))))))))
  (testing "a single `author` (singular key) round-trips too, even though
            it is always re-spelled back out under the plural `authors`
            key -- Meta itself carries no memory of which was written"
    (is (fixed-point? "---\nauthor: Solo Author\n---\n\nBody.\n")))
  (testing "a document with no front matter at all writes none back,
            rather than an empty block -- doc-7's own \"re-spells
            canonically\" standard applied to the simplest case"
    (let [document (parser/parse "Just a paragraph.\n")
          out (hdoc/emit-document document)]
      (is (not (str/starts-with? out "---")))
      (is (= document (parser/parse out)))))
  (testing "a document whose very first Block is a bare thematic break,
            with no front matter, is guarded rather than left to put a
            bare `---` at the very top of the file (which would trigger
            haselnuss.parser/split-front-matter's own front-matter-open
            check): emit-document forces an empty front-matter block
            instead, since the break's own spelling cannot be escaped
            without ceasing to be a thematic break"
    (let [document {:meta {} :blocks [{:t :thematic-break}
                                      {:t :para :inlines [(str-inline "after")]}]}
          out (hdoc/emit-document document)]
      (is (str/starts-with? out "---\n---\n\n"))
      (is (= document (parser/parse out))))))

(defn- registry-variant-tags
  "Every variant tag of a `[:multi {:dispatch fn} & variants]` registry
  entry, by the simplest independent read of the raw schema data."
  [ref-kind]
  (set (map first (drop 2 (get ast/registry ref-kind)))))

(def ^:private all-block-tags
  "Every Block variant `haselnuss.ast/registry` defines (sec4.3), DERIVED
  from the registry -- see `every-inline-tag` for why the hand-pinned set
  this replaces was a canary that could not fire."
  (registry-variant-tags ::ast/block))

(def ^:private every-inline-tag
  "Every Inline variant `haselnuss.ast/registry` defines (sec4.4),
  DERIVED from the registry rather than pinned.

  It used to be a hand-written set, compared with `=` against the handler
  map and never against the schema -- so it stayed green with eighteen
  stale tags the whole time `:highlight` and `:sticky-note` existed
  unhandled here, which is precisely the failure it advertises itself as
  catching (found by the TASK-116 review, fixed with TASK-123 since this
  is the task that had to add both handlers anyway)."
  (registry-variant-tags ::ast/inline))

(deftest block-and-inline-coverage-test
  (testing "TASK-97 AC #1: every Block variant haselnuss.ast/registry
            defines has a handler, proved by a subset check against the
            schema's own pinned tag set rather than by inspection -- a
            variant added to the registry later and left unhandled here
            fails this assertion instead of being silently unwritable"
    (is (= 11 (count all-block-tags)) "the derivation found the schema's own Block variants")
    (is (= all-block-tags (set (keys hdoc/built-in-block-handlers)))))
  (testing "TASK-95 AC #4: every Inline variant has a handler"
    (is (= 20 (count every-inline-tag)) "the derivation found the schema's own Inline variants")
    (is (= every-inline-tag (set (keys hdoc/built-in-inline-handlers)))))
  (testing "a document combining every Block variant and every ordinary
            Inline (a chaptered Section, a Para holding every ordinary
            Inline, a BlockQuote, a ThematicBreak, a tight unordered List,
            a fenced CodeBlock, an attributed MathBlock, an attributed
            pipe Table with a caption, a standalone-image Figure, a named
            Directive, an unexpanded Include, a nested Section and a
            multi-block-holding footnote) round-trips as one AST fixed
            point -- exercising `used-tags`-style coverage in one real
            document rather than only in isolated per-construct fixtures"
    (is (fixed-point?
         (str "# Chapter {#ch:one .intro key=\"two words\"}\n\n"
              "Some *emph* and **strong** and ***both*** and `code` and "
              "$x^2$ and @ch:one and @knuth1984 and "
              "[@smith2020, p. 3] and [text](http://x.com/(y)){.cls} and "
              "![inline alt](p.png) and [span]{.note}.\n\n"
              "> quoted line one\n> quoted line two\n>\n> second paragraph\n\n"
              "---\n\n"
              "- item one\n- item two\n\n"
              "```clj\n(+ 1 2)\n```\n\n"
              "$$\nx^2 = 4\n$$ {#eq:sq}\n\n"
              "| h1 | h2 |\n| :-- | --: |\n| c1 | c2 |\n: caption {#tbl:t}\n\n"
              "![A tree](tree.png){#fig:tree}\n\n"
              ":::{note}\nDirective body.\n:::\n\n"
              "!include other.hdoc\n\n"
              "## Sub {#ch:two}\n\nMore text and a note[^1].\n\n"
              "[^1]: The footnote body.\n")))))

(deftest note-round-trip-test
  (testing "a :note (footnote) whose own :blocks hold only a :para -- the
            common case -- round-trips, including a nested footnote
            reference inside its own body getting a strictly later
            number (mirroring haselnuss.emit.html/render-note's own
            reservation order)"
    (is (fixed-point? (str "First.[^a] Second.[^b]\n\n"
                           "[^a]: Definition A.\n\n"
                           "[^b]: Definition B.\n"))))
  (testing "a genuinely unknown Block :t inside a :note's own :blocks
            still throws ::unsupported-block naming it, not :note itself
            -- proving :note's own scope boundary; TASK-97 closed the
            real gap this test used to demonstrate (a :code-block inside
            a :note is fully supported now, see note-richer-content-test)"
    (let [document {:meta {}
                    :blocks [{:t :para
                              :inlines [(str-inline "See.")
                                        {:t :note
                                         :blocks [{:t :not-a-real-block-type}]}]}]}
          e (is (thrown? clojure.lang.ExceptionInfo (hdoc/emit-document document)))]
      (is (= ::hdoc/unsupported-block (:type (ex-data e))))
      (is (= :not-a-real-block-type (:t (:block (ex-data e)))))))
  (testing "a :directive/:include inside a :note's own :blocks throws
            ::unrepresentable-nested-block instead (TASK-97): both are
            genuinely unrepresentable there, but for a different reason
            than an unknown :t -- see check-representable-nested-block!'s
            own docstring"
    (let [document {:meta {}
                    :blocks [{:t :para
                              :inlines [(str-inline "See.")
                                        {:t :note :blocks [{:t :include :src "x.hdoc"}]}]}]}
          e (is (thrown? clojure.lang.ExceptionInfo (hdoc/emit-document document)))]
      (is (= ::hdoc/unrepresentable-nested-block (:type (ex-data e))))
      (is (= :include (:t (ex-data e))))))
  (testing "a :note whose own :blocks hold TWO :para blocks round-trips:
            the second block is indented rather than left to escape the
            footnote definition on re-parse across the blank line
            separating it from the first (TASK-95 review comment #2's own
            silent-corruption reproduction)"
    (is (fixed-point? (str "See.[^1]\n\n"
                           "[^1]: Body.\n\n"
                           "    Second paragraph.\n"))))
  (testing "a :note whose own :blocks hold a :para followed by a
            :block-quote round-trips the same way, proving the
            continuation indentation generalizes to a second block of a
            DIFFERENT (still supported) type, not merely a second :para"
    (is (fixed-point? (str "See.[^1]\n\n"
                           "[^1]: Body.\n\n"
                           "    > Quoted.\n"))))
  (testing "the exact hand-built AST from TASK-95 review comment #2:
            emit-document followed by re-parsing reproduces the original
            document exactly, rather than silently dropping the
            :block-quote as a stray top-level Block after the paragraph
            that held the note reference"
    (let [document {:meta {}
                    :blocks [{:t :para
                              :inlines [(str-inline "See.")
                                        {:t :note
                                         :blocks [{:t :para :inlines [(str-inline "Body.")]}
                                                  {:t :block-quote
                                                   :blocks [{:t :para :inlines [(str-inline "Quoted.")]}]}]}]}]}]
      (is (= document (parser/parse (hdoc/emit-document document)))))))

(deftest escaping-round-trip-test
  (testing "AC #5: literal text containing each of the minimum-required
            markup characters re-parses to the same Inlines, checked
            against a hand-built AST (not real .hdoc source) since a
            document that legitimately produces a raw ':::'/'---'-led
            :str from ordinary parsing does not exist -- the parser
            itself would already read those as a directive-fence/
            thematic-break attempt at the point they occur, which is
            exactly the risk this AC is about protecting a TRANSFORM-
            or JSON-produced :str from"
    (doseq [text ["*" "_" "[" "]" "`" "$" "@" "!" "#" ":" ":::" "---" "!important"
                  ;; TASK-122: `==` opens a highlight now, so a literal
                  ;; one in a :str has to be escaped or the round trip
                  ;; INVENTS a :highlight nobody wrote. A lone `=` is
                  ;; deliberately still unescaped -- see
                  ;; `escapes-as-highlight-marker?` -- so both forms are
                  ;; here, and so is the run of three that would break
                  ;; if only the first `=` of a run were escaped.
                  "=" "==" "===" "====" "==marked=="]]
      (let [document {:meta {} :blocks [{:t :para :inlines [(str-inline text)]}]}]
        (is (= document (parser/parse (hdoc/emit-document document))) (pr-str text)))))
  (testing "the same literal text, immediately followed by ! before a
            real [ character from an adjacent inline, does not combine
            into an unintended image (the exact scenario AC #5 names)"
    (let [document {:meta {}
                    :blocks [{:t :para
                              :inlines [(str-inline "look!") space
                                        {:t :span :inlines [(str-inline "not-an-image")]
                                         :attr {:classes ["x"] :props {}}}]}]}]
      (is (= document (parser/parse (hdoc/emit-document document))))))
  (testing "a leading # / ::: / --- each stay literal text on a real
            document round trip (real .hdoc source this time, escaped by
            haselnuss.parser itself so the fixture is unambiguous)"
    (is (fixed-point? "\\# not a heading\n"))
    (is (fixed-point? "\\::: not a fence\n"))
    (is (fixed-point? "\\--- not a break\n")))
  (testing "a mid-paragraph line composed only of hyphens (introduced by
            a hard line break) does not turn into a thematic break or a
            setext heading on re-parse -- the general case
            escape-line-start-hazard exists for, beyond AC #5's own
            narrower \"top of file\" example"
    (let [document {:meta {}
                    :blocks [{:t :para
                              :inlines [(str-inline "before") {:t :line-break}
                                        (str-inline "---") {:t :line-break}
                                        (str-inline "after")]}]}]
      (is (= document (parser/parse (hdoc/emit-document document)))))))

(deftest heading-attribute-group-round-trip-test
  (testing "AC #6: a heading attribute block ({#id .class key=val}) is
            written and re-reads to the same Attr, including a value
            holding spaces (quoted, TASK-39's own syntax)"
    (let [src "# Title {#sec:intro .highlight .wide lang=en note=\"two words\"}\n\nBody.\n"]
      (is (fixed-point? src))
      (let [document (parser/parse src)
            attr (:attr (first (:blocks document)))]
        (is (= {:id "sec:intro" :classes ["highlight" "wide"]
                :props {"lang" "en" "note" "two words"}}
               attr))))))

(deftest lossy-role-span-test
  (testing "AC #7: :strike/:small-caps/:sub/:sup are written as the role
            span an author would use ([text]{.kind}), and re-parsing
            that spelling yields a :span, never the original tag"
    (doseq [t [:strike :small-caps :sub :sup]]
      (let [document {:meta {} :blocks [{:t :para :inlines [{:t t :inlines [(str-inline "x")]}]}]}
            out (hdoc/emit-document document)
            reparsed (parser/parse out)
            inline (first (:inlines (first (:blocks reparsed))))]
        (is (= :span (:t inline)) t)
        (is (= [(name t)] (:classes (:attr inline))) t))))
  (testing "lossy-role-span-diagnostics reports one diagnostic per
            occurrence, naming the node's own type and its path, run
            separately from emit-document itself (mirroring
            haselnuss.emit.latex/unanchored-reference-diagnostics's own
            shape)"
    (let [document {:meta {}
                    :blocks [{:t :para
                              :inlines [{:t :strike :inlines [(str-inline "a")]}
                                        {:t :sup :inlines [(str-inline "b")]}]}]}
          diagnostics (hdoc/lossy-role-span-diagnostics document)]
      (is (= [{:type ::hdoc/lossy-role-span :t :strike :path [:blocks 0 :inlines 0]}
              {:type ::hdoc/lossy-role-span :t :sup :path [:blocks 0 :inlines 1]}]
             diagnostics))))
  (testing "a document with none of the four reports no diagnostics"
    (let [document (parser/parse "Ordinary *emph* text.\n")]
      (is (empty? (hdoc/lossy-role-span-diagnostics document))))))

(deftest link-image-destination-test
  (testing "a link/image destination with a balanced parenthesis (a
            Wikipedia-style URL) round-trips bare"
    (is (fixed-point? "[a](http://en.wikipedia.org/wiki/Hazel_(plant))\n")))
  (testing "a destination whose own paren is unbalanced round-trips via
            the <...> angle-bracket form"
    (let [document {:meta {}
                    :blocks [{:t :para
                              :inlines [{:t :link :target "http://x.com/y)z"
                                         :inlines [(str-inline "a")] :attr empty-attr}]}]}]
      (is (= document (parser/parse (hdoc/emit-document document))))))
  (testing "a destination containing whitespace has no .hdoc spelling in
            this parser at all (confirmed at a REPL against both
            destination forms) and is reported rather than silently
            truncated"
    (let [document {:meta {}
                    :blocks [{:t :para
                              :inlines [{:t :link :target "http://x.com/a b"
                                         :inlines [(str-inline "a")] :attr empty-attr}]}]}
          e (is (thrown? clojure.lang.ExceptionInfo (hdoc/emit-document document)))]
      (is (= ::hdoc/unrepresentable-destination (:type (ex-data e)))))))

(deftest attribute-value-double-quote-conflict-test
  (testing "a prop value containing both a single and a double quote has
            no representable spelling under TASK-39's own quoting syntax
            (haselnuss.parser's own documented limitation on the read
            side) and is reported rather than silently mangled"
    (let [document {:meta {}
                    :blocks [{:t :section :level 1 :heading [(str-inline "T")]
                              :blocks []
                              :attr {:classes [] :props {"note" "a \"quote\" and an 'apostrophe'"}}}]}
          e (is (thrown? clojure.lang.ExceptionInfo (hdoc/emit-document document)))]
      (is (= ::hdoc/unrepresentable-attr-value (:type (ex-data e)))))))

(deftest cite-affix-semicolon-test
  (testing "a CiteItem's own :suffix containing a literal `;` (review
            comment #4 on TASK-95) is reported rather than silently
            corrupted on re-parse. NOTE this is NOT a round-trip test:
            confirmed at a REPL that backslash-escaping the `;` (the
            fix TASK-95's own comment thread first proposed) does NOT
            actually make this round-trip -- haselnuss.parser's own
            citation-bracket recognition (citation-bracket-content?) and
            item extraction (cite-bracket-node->cite) both split a
            bracket's RAW source text on every `;` unconditionally,
            before backslash-escape resolution ever runs, so escaping
            alone cannot hide a `;` from that split. render-cite-item
            instead throws the reviewer's own suggested alternative,
            ::unrepresentable-cite-affix, naming the CiteItem's :key --
            see this namespace's own docstring (the CiteItem
            prefix/suffix bullet under OTHER KNOWN, NARROW GAPS) for
            the full empirical account"
    (let [document {:meta {}
                    :blocks [{:t :para
                              :inlines [{:t :cite
                                         :items [{:key "smith04" :mode :normal
                                                  :suffix [(str-inline "p. 3; see also p.9")]}]}]}]}
          e (is (thrown? clojure.lang.ExceptionInfo (hdoc/emit-document document)))]
      (is (= ::hdoc/unrepresentable-cite-affix (:type (ex-data e))))
      (is (= "smith04" (:key (ex-data e))))))
  (testing "the same for a :prefix containing a literal `;`"
    (let [document {:meta {}
                    :blocks [{:t :para
                              :inlines [{:t :cite
                                         :items [{:key "smith04" :mode :normal
                                                  :prefix [(str-inline "see; also")]}]}]}]}
          e (is (thrown? clojure.lang.ExceptionInfo (hdoc/emit-document document)))]
      (is (= ::hdoc/unrepresentable-cite-affix (:type (ex-data e))))))
  (testing "a CiteItem with no `;` in either affix is unaffected -- this
            fix does not touch always-escaped-chars, so the common case
            (sec5.11's own worked example) still round-trips exactly as
            before"
    (is (fixed-point? "[@knuth1984, p. 42]\n")))
  (testing "ordinary prose containing `;` OUTSIDE a citation bracket is
            unaffected: this fix does not add `;` to always-escaped-
            chars, so a bare `;` in prose still round-trips unescaped"
    (is (fixed-point? "A sentence; with a semicolon.\n"))))

(deftest list-round-trip-test
  (testing "TASK-97 AC #2: ordered and unordered, both tight and loose, round-trip
            -- always re-numbered from 1 (haselnuss.ast/registry's own
            :list schema has no start-number field, so haselnuss.parser
            never even reads what the source started at)"
    (is (fixed-point? "- a\n- b\n"))
    (is (fixed-point? "- a\n\n- b\n"))
    (is (fixed-point? "1. a\n1. b\n"))
    (let [document (parser/parse "9. a\n9. b\n")]
      (is (= "1. a\n2. b\n" (hdoc/emit-document document)))))
  (testing "TASK-97 AC #2: an item holding more than one Block round-trips -- the
            real thesis shape this AC's own wording names (TASK-70): an
            ordered list whose items each hold a :para then a :math-block,
            separated by a blank line (loose), the ONLY spelling either
            construct has as a later item Block at all"
    (is (fixed-point? (str "1. First:\n\n   $$\n   x = 1\n   $$ {#eq:a}\n\n"
                           "2. Second:\n\n   $$\n   y = 2\n   $$ {#eq:b}\n"))))
  (testing "a TIGHT item's later Block round-trips when it is one of
            list-item-safe-continuation-types -- a nested List, a
            BlockQuote and a fenced CodeBlock, each confirmed at a REPL to
            start a new Block with no blank line before it"
    (is (fixed-point? "- a\n  - nested\n"))
    (is (fixed-point? "- a\n  > q\n"))
    (is (fixed-point? "- a\n  ```\n  code\n  ```\n")))
  (testing "a TIGHT item whose later Block is NOT one of those types has
            no .hdoc spelling and throws ::unrepresentable-tight-list-item
            naming the offending type and its index, rather than emitting
            a spelling that reparses to something else (a :thematic-break
            there is read as a SETEXT HEADING underline instead, silently
            changing the Block's own type, per this namespace's own
            docstring)"
    (let [document {:meta {} :blocks [{:t :list :ordered false :tight true
                                       :items [[{:t :para :inlines [(str-inline "a")]}
                                                {:t :thematic-break}]]
                                       :attr empty-attr}]}
          e (is (thrown? clojure.lang.ExceptionInfo (hdoc/emit-document document)))]
      (is (= ::hdoc/unrepresentable-tight-list-item (:type (ex-data e))))
      (is (= :thematic-break (:t (ex-data e))))
      (is (= 1 (:index (ex-data e))))))
  (testing "a :directive/:include nested inside a list item has no .hdoc
            spelling at all (this namespace's own docstring: both are
            recognized by a raw, container-oblivious line scan on the
            read side) and throws ::unrepresentable-nested-block naming
            the type and \"a list item\", rather than emitting a fence
            that either splits the list in two or is never recognized as
            a directive at all"
    (let [document {:meta {} :blocks [{:t :list :ordered false :tight false
                                       :items [[{:t :directive :name "n" :blocks []
                                                 :attr empty-attr}]]
                                       :attr empty-attr}]}
          e (is (thrown? clojure.lang.ExceptionInfo (hdoc/emit-document document)))]
      (is (= ::hdoc/unrepresentable-nested-block (:type (ex-data e))))
      (is (= :directive (:t (ex-data e))))
      (is (str/includes? (ex-message e) "a list item")))))

(deftest list-marker-hazard-test
  (testing "TASK-97 (comment #1 on TASK-95): literal text shaped like a
            bullet or ordered list marker at a physical line's start
            round-trips rather than degrading into an unintended :list on
            re-parse -- checked against hand-built, already-tokenized
            Inlines (the shape haselnuss.parser/text->inlines itself
            would produce for such text, a :str/:space sequence, not one
            merged :str), since that is what escape-line-start-hazard
            actually has to defuse"
    (doseq [inlines [[(str-inline "-") space (str-inline "item")]
                     [(str-inline "+") space (str-inline "item")]
                     [(str-inline "12.") space (str-inline "item")]
                     [(str-inline "12)") space (str-inline "item")]
                     [(str-inline "-")]
                     [(str-inline "1.")]]]
      (let [document {:meta {} :blocks [{:t :para :inlines inlines}]}]
        (is (= document (parser/parse (hdoc/emit-document document))) (pr-str inlines)))))
  (testing "`*` needs no dedicated hazard check: it is already in
            always-escaped-chars (shared with emphasis), so a literal
            `* item` is escaped before this hazard is ever checked"
    (let [document {:meta {} :blocks [{:t :para
                                       :inlines [(str-inline "*") space (str-inline "item")]}]}]
      (is (= document (parser/parse (hdoc/emit-document document))))))
  (testing "an ordinary hyphen NOT shaped like a marker (no trailing
            whitespace, or not at a physical line's start at all) is
            unaffected"
    (let [document {:meta {}
                    :blocks [{:t :para
                              :inlines [(str-inline "-item") space (str-inline "stays") space
                                        (str-inline "fine")]}]}]
      (is (= document (parser/parse (hdoc/emit-document document)))))
    (let [document {:meta {}
                    :blocks [{:t :para
                              :inlines [(str-inline "not") space (str-inline "a") space
                                        (str-inline "-") space (str-inline "marker")]}]}]
      (is (= document (parser/parse (hdoc/emit-document document)))))))

(deftest code-block-round-trip-test
  (testing "TASK-97 AC #3: a fenced code block round-trips, its language surviving"
    (is (fixed-point? "```clj\n(+ 1 2)\n```\n")))
  (testing "TASK-97 AC #3: a code block whose body contains a backtick run
            round-trips -- the fence lengthens (code-block-fence-for)
            past it rather than closing early"
    (is (fixed-point? "````\n```\nnested\n```\n````\n")))
  (testing "an empty code block, and an indented (no-language) code block,
            both round-trip"
    (is (fixed-point? "```\n```\n"))
    (is (fixed-point? "    indented code\n    line2\n")))
  (testing "a backtick-free body never gets a 1-backtick fence -- a fenced
            code BLOCK (unlike an inline :code span) is only recognized
            with 3+ backticks at all; a real bug this namespace's own
            code-block-fence-for exists to fix, distinct from
            code-fence-for's own inline-span minimum"
    (let [document {:meta {} :blocks [{:t :code-block :text "plain\n" :attr empty-attr}]}]
      (is (str/starts-with? (hdoc/emit-document document) "```"))
      (is (= document (parser/parse (hdoc/emit-document document))))))
  (testing "a language containing whitespace or a backtick has no .hdoc
            spelling (it would break the fence's own opening line) and
            throws ::unrepresentable-lang naming it"
    (let [document {:meta {} :blocks [{:t :code-block :lang "has space" :text "x" :attr empty-attr}]}
          e (is (thrown? clojure.lang.ExceptionInfo (hdoc/emit-document document)))]
      (is (= ::hdoc/unrepresentable-lang (:type (ex-data e)))))))

(deftest math-block-round-trip-test
  (testing "a single-line source re-spells to the two-line fenced form
            (this namespace's own docstring: never the single-line
            shorthand) yet still round-trips as the same AST"
    (is (fixed-point? "$$ x^2 $$ {#eq:a}\n")))
  (testing "a multi-line body keeps the internal alignment the author
            wrote, only the shared indentation stripped"
    (is (fixed-point? (str "$$\nr = \\begin{cases}\n"
                           "  1, & x > 0 \\\\\n  0, & \\text{otherwise}\n"
                           "\\end{cases}\n$$\n"))))
  (testing "a math block with no attribute group at all round-trips"
    (is (fixed-point? "$$\nx = 1\n$$\n"))))

(deftest table-round-trip-test
  (testing "TASK-97 AC #4: alignments, column widths and a trailing caption line
            (including its own id) all round-trip together"
    (is (fixed-point? (str "| h1 | h2 |\n| :-- | --: |\n| c1 | c2 |\n"
                           ": My caption {#tbl:a widths=\"30% 70%\"}\n"))))
  (testing "TASK-97 AC #4: a literal `|` inside a cell round-trips via its own
            backslash escape -- the one character this namespace escapes
            outside always-escaped-chars, since it is only hazardous
            inside a table row"
    (is (fixed-point? "| a\\|b | c |\n| --- | --- |\n| x | y |\n")))
  (testing "a caption line carrying only an attribute group (an id, no
            caption text) round-trips"
    (is (fixed-point? "| h1 |\n| --- |\n| c1 |\n: {#tbl:b}\n")))
  (testing "a plain table with no caption, id, class or width writes no
            trailing caption line at all -- doc-7's own \"re-spells
            canonically\" simplification applied to the simplest table"
    (let [out (hdoc/emit-document (parser/parse "| h1 |\n| --- |\n| c1 |\n"))]
      (is (not (str/includes? out ":")))
      (is (fixed-point? "| h1 |\n| --- |\n| c1 |\n"))))
  (testing "a cell holding anything other than exactly one :para Block has
            no .hdoc spelling (a pipe-table cell is inherently one
            physical line of inline content) and throws
            ::unrepresentable-table-cell rather than corrupting the row"
    (let [document {:meta {}
                    :blocks [{:t :table
                              :head {:cells [{:blocks [(para (str-inline "h")) (para (str-inline "h2"))]}]}
                              :rows [] :caption [] :colspec [{}] :attr empty-attr}]}
          e (is (thrown? clojure.lang.ExceptionInfo (hdoc/emit-document document)))]
      (is (= ::hdoc/unrepresentable-table-cell (:type (ex-data e))))))
  (testing "a cell whose own rendered text would span more than one
            physical line (a :soft-break/:line-break Inline) throws the
            same way rather than corrupting the pipe-table row"
    (let [document {:meta {}
                    :blocks [{:t :table
                              :head {:cells [{:blocks [(para (str-inline "h") {:t :soft-break} (str-inline "h2"))]}]}
                              :rows [] :caption [] :colspec [{}] :attr empty-attr}]}
          e (is (thrown? clojure.lang.ExceptionInfo (hdoc/emit-document document)))]
      (is (= ::hdoc/unrepresentable-table-cell (:type (ex-data e))))))
  (testing "a spanning cell (:span other than 1) has no GFM pipe-table
            spelling at all -- TablesExtension/COLUMN_SPANS is disabled on
            the read side, so this is unreachable from real parsing, but
            still throws rather than silently dropping the span"
    (let [document {:meta {}
                    :blocks [{:t :table
                              :head {:cells [{:blocks [(para (str-inline "h"))] :span 2}]}
                              :rows [] :caption [] :colspec [{}] :attr empty-attr}]}
          e (is (thrown? clojure.lang.ExceptionInfo (hdoc/emit-document document)))]
      (is (= ::hdoc/unrepresentable-table-cell (:type (ex-data e)))))))

(deftest figure-round-trip-test
  (testing "the standalone-image shorthand round-trips, its id/classes/
            props all reconstructed into one attribute group"
    (is (fixed-point? "![A tree](tree.png){#fig:tree .wide scale=0.5}\n")))
  (testing "a Figure whose :content is anything other than a :para
            wrapping exactly one :image has no .hdoc spelling at all --
            haselnuss.parser never builds a :figure any other way (this
            namespace's own docstring) -- and throws
            ::unsupported-figure-content naming the actual shape"
    (let [document {:meta {}
                    :blocks [{:t :figure
                              :content {:t :code-block :text "x" :attr empty-attr}
                              :caption [] :attr (assoc empty-attr :id "fig:x")}]}
          e (is (thrown? clojure.lang.ExceptionInfo (hdoc/emit-document document)))]
      (is (= ::hdoc/unsupported-figure-content (:type (ex-data e)))))))

(deftest directive-round-trip-test
  (testing "TASK-97 AC #5: a directive's own name and attributes round-trip"
    (is (fixed-point? ":::{theorem #thm:a .important key=val}\nBody.\n:::\n")))
  (testing "TASK-97 AC #5: a directive nested inside another directive round-trips
            -- a fixed three-colon fence at every nesting depth (this
            namespace's own docstring explains why that is always safe)"
    (is (fixed-point? ":::{outer}\ntext1\n:::{inner}\ntext2\n:::\ntext3\n:::\n"))
    (is (fixed-point? ":::{a}\n:::{b}\n:::{c}\nz\n:::\n:::\n:::\n")))
  (testing "a nameless, attributeless directive round-trips via an EMPTY
            {} header, always headered rather than bare -- both source
            spellings (a bare ::: fence and an explicit :::{}) parse to
            the identical :name \"\"/default :attr, so this is the one
            canonical spelling doc-7's own standard picks"
    (is (fixed-point? ":::\nBody.\n:::\n")))
  (testing "an :include nested inside a :directive's own :blocks
            round-trips normally -- unlike inside a :list/:block-quote/
            :note, parse-directive-region recognizes both via the same
            raw-line recursion at any depth, never via container
            indentation (this namespace's own docstring)"
    (is (fixed-point? ":::{note}\n!include other.hdoc\n:::\n"))))

(deftest one-line-directive-round-trip-test
  (testing "TASK-115 AC #1: a body-less named directive is written in
            sec5.5's one-line spelling, with NO group at all when it
            carries no attributes -- `!toc`, never `!toc{}`"
    (is (= "!toc\n" (hdoc/emit-document (parser/parse "!toc\n"))))
    (is (fixed-point? "!toc\n")))
  (testing "TASK-115 AC #1: attributes ride along in the same sorted-key
            order and with the same quoting the fence header already
            uses -- `attr-tokens` is shared between the two spellings"
    (is (= "!reveal{answer=\"c d\" question=\"a b\"}\n"
           (hdoc/emit-document (parser/parse "!reveal{question=\"a b\" answer=\"c d\"}\n"))))
    (is (fixed-point? "!reveal{question=\"a b\" answer=\"c d\"}\n"))
    (is (fixed-point? "!fig-note{#note:a .aside k=v}\n")))
  (testing "TASK-115 AC #2: this emitter is the format's canonical
            formatter and the AST records no trace of which spelling its
            author wrote, so a body-less directive written as a fence
            comes back as the one-line form -- a normalization, and still
            an AST fixed point"
    (is (= "!toc\n" (hdoc/emit-document (parser/parse ":::{toc}\n:::\n"))))
    (is (fixed-point? ":::{toc}\n:::\n"))
    (is (= "!list-of-figures\n" (hdoc/emit-document (parser/parse ":::{list-of-figures}\n:::\n")))))
  (testing "TASK-115 AC #3: a directive WITH a body keeps the fence --
            the one-line form has no body to put it in"
    (is (= ":::{note}\nBody.\n:::\n" (hdoc/emit-document (parser/parse ":::{note}\nBody.\n:::\n"))))
    (is (fixed-point? ":::{note}\nBody.\n:::\n")))
  (testing "TASK-115 AC #3: a NAMELESS body-less directive keeps the
            fence too. `!{}` is not the grammar, so there is nothing to
            write; both source spellings still canonicalize to `:::{}`"
    (is (= ":::{}\n:::\n" (hdoc/emit-document (parser/parse ":::{}\n:::\n"))))
    (is (= ":::{}\n:::\n" (hdoc/emit-document (parser/parse ":::\n:::\n"))))
    (is (fixed-point? ":::{}\n:::\n")))
  (testing "TASK-115 AC #3: a directive actually NAMED \"include\" keeps
            the fence, because `include` is reserved in the one-line
            spelling -- emitting `!include` would come back as an
            ordinary paragraph, losing the directive entirely"
    (is (= ":::{include}\n:::\n" (hdoc/emit-document (parser/parse ":::{include}\n:::\n"))))
    (is (fixed-point? ":::{include}\n:::\n")))
  (testing "TASK-115 AC #3: and a :name that is not shaped like a
            directive name keeps the fence. `haselnuss.parser` can only
            ever produce a legal one, but `haselnuss.transform`'s rules
            build Directives directly and `haselnuss.ast` types `:name`
            as a plain `:string`, so this emitter cannot assume it"
    (doseq [dname ["my directive" "2fast" "a.b" "-lead" "" "include"]]
      (let [document {:meta {} :blocks [{:t :directive :name dname
                                         :attr empty-attr :blocks []}]}]
        (is (str/starts-with? (hdoc/emit-document document) ":::{") dname))))
  (testing "the fence those names fall back to is a FALLBACK, not a
            promise of representability: a name the fence header's own
            grammar cannot spell either has no .hdoc spelling at all and
            does not survive a re-parse. That hole predates this emitter
            (the fence header has always used the same name production);
            it is pinned here so the fallback is not mistaken for a
            round trip it never was"
    (doseq [dname ["" "include"]]
      (let [document {:meta {} :blocks [{:t :directive :name dname
                                         :attr empty-attr :blocks []}]}]
        (is (= document (parser/parse (hdoc/emit-document document))) dname)))
    (doseq [dname ["my directive" "2fast"]]
      (let [document {:meta {} :blocks [{:t :directive :name dname
                                         :attr empty-attr :blocks []}]}]
        (is (thrown? clojure.lang.ExceptionInfo
                     (parser/parse (hdoc/emit-document document)))
            dname))))
  (testing "a one-line directive nested inside a fence round-trips, and
            the enclosing fence is unaffected by the inner spelling"
    (is (= ":::{outer}\n!toc\n:::\n" (hdoc/emit-document (parser/parse ":::{outer}\n!toc\n:::\n"))))
    (is (fixed-point? ":::{outer}\n!toc\n:::\n"))))

(deftest include-round-trip-test
  (testing "TASK-97 AC #7: an !include line round-trips"
    (is (fixed-point? "!include chapters/a.hdoc\n")))
  (testing "TASK-97 AC #7: !include is NOT expanded -- the Block stays exactly
            :include with its own :src, never replaced by another file's
            content, both before and after a round trip"
    (let [document (parser/parse "!include chapters/a.hdoc\n")]
      (is (= [{:t :include :src "chapters/a.hdoc"}] (:blocks document)))
      (is (= document (parser/parse (hdoc/emit-document document))))))
  (testing "a path containing whitespace round-trips via quoting"
    (is (fixed-point? "!include \"a long name.hdoc\"\n")))
  (testing "a path starting with a quote character but containing NO
            whitespace still round-trips via quoting -- unreachable from a
            real haselnuss.parser/parse (a leading unmatched quote is
            rejected at the ORIGINAL parse, so :src can never start with
            one there), but reachable from a hand-built or transform-
            produced :include node. Before the fix, quote-include-path
            only quoted on whitespace, so this was written back bare and
            threw ::malformed-include on re-parse instead of round-
            tripping (review comment #2 on TASK-97)"
    (let [double-quoted {:meta {} :blocks [{:t :include :src "\"quotedname.hdoc"}]}
          single-quoted {:meta {} :blocks [{:t :include :src "'quoted.hdoc"}]}]
      (is (= double-quoted (parser/parse (hdoc/emit-document double-quoted))))
      (is (= single-quoted (parser/parse (hdoc/emit-document single-quoted))))))
  (testing "a path containing whitespace AND both quote characters has no
            .hdoc spelling and throws ::unrepresentable-include-path"
    (let [document {:meta {} :blocks [{:t :include :src "a \"quote' path.hdoc"}]}
          e (is (thrown? clojure.lang.ExceptionInfo (hdoc/emit-document document)))]
      (is (= ::hdoc/unrepresentable-include-path (:type (ex-data e)))))))

(deftest note-richer-content-test
  (testing "TASK-97: a :note whose own :blocks hold a :list now
            round-trips -- the exact case TASK-95's own docstring
            deferred to this task, no longer throwing
            ::unsupported-block"
    (is (fixed-point? "See.[^1]\n\n[^1]: - a\n\n    - b\n"))))

;; --- Annotations back to .hdoc source (TASK-123, doc-8 sec3) --------------

(defn- round-trips-from-source?
  "Whether `src` survives parse -> emit -> parse with an identical AST --
  the property that makes `--target hdoc` a formatter rather than a
  lossy dump. Returns the re-parsed blocks on failure so a message can
  show what changed."
  [src]
  (let [document (parser/parse src)
        back (parser/parse (hdoc/emit-document document))]
    (if (= (:blocks document) (:blocks back)) true (:blocks back))))

(deftest highlight-round-trips-exactly-test
  (testing "AC #1: a :highlight emits ==...== and re-parses to an
            identical node -- an EXACT round trip, unlike the four role
            spans, because a highlight has a surface spelling of its own"
    (doseq [src ["Hazelnuts ==ripen in early autumn== and drop.\n"
                 "==A whole paragraph is marked.==\n"
                 "a ==b== c ==d== e\n"
                 "*emph with ==mark== inside it*\n"
                 "A ==run with *emph*, `code` and $x^2$ in it== here.\n"]]
      (testing (pr-str src)
        (is (true? (round-trips-from-source? src))))))
  (testing "AC #2: a :highlight carrying an id, classes or props emits
            the attribute group AFTER the closer and re-parses
            identically"
    (doseq [src ["==x=={#hl:a}\n"
                 "==x=={.important}\n"
                 "==x=={note=check}\n"
                 "==x=={#hl:a .important .urgent note=check}\n"]]
      (testing (pr-str src)
        (is (true? (round-trips-from-source? src)))))
    (is (str/includes? (hdoc/emit-document
                        (parser/parse "==x=={#hl:a .important}\n"))
                       "==x=={#hl:a .important}")
        "the group is written after the closing ==, which is where it parses from"))
  (testing "AC #3: a :highlight whose marked text itself contains an = or
            a == emits an escaped form that re-parses identically -- an
            unescaped one would close the highlight early, or open a
            second one inside it"
    (doseq [text ["a = b" "a == b" "===" "====" "=" "a=b" "a==b==c" "=a=" "==a=="
                  ;; The `\=` family, which is where the first fix was
                  ;; wrong by one case: a trailing `\=` in the escaped
                  ;; text is just as often an escaped BACKSLASH followed
                  ;; by a bare `=`, and treating it as already-escaped
                  ;; let the `=` escape out of the node -- the highlight
                  ;; silently moved its own edge.
                  "a\\=" "\\=" "\\" "a\\" "\\\\=" "=\\=" "a\\=b"]]
      (testing (pr-str text)
        (let [document {:meta {} :blocks [{:t :para
                                           :inlines [{:t :highlight
                                                      :inlines [(str-inline text)]
                                                      :attr {:classes [] :props {}}}]}]}
              emitted (hdoc/emit-document document)
              back (parser/parse emitted)
              highlights (filter #(= :highlight (:t %)) (tree-seq coll? seq (:blocks back)))]
          (is (= 1 (count highlights))
              (str "exactly one highlight should survive: " (pr-str emitted)))
          ;; Asserted on the HIGHLIGHT, not on the paragraph. The
          ;; paragraph's own flattened text is unchanged when a character
          ;; merely moves from inside the highlight to a sibling :str, so
          ;; the paragraph-level assertion this replaces passed with the
          ;; bug in place (found by review).
          (is (= text (select/flatten-text (first highlights)))
              (str "and the MARKED text unchanged: " (pr-str emitted)))
          (is (= 1 (count (:inlines (first (:blocks back)))))
              (str "with nothing left outside it: " (pr-str emitted))))))))

(deftest nested-highlight-degrades-to-a-role-span-test
  (testing "TASK-123 review: `==...==` cannot spell one highlight inside
            another -- the inner `==` closes the outer opener, so
            `==a ==b== c==` parses as ONE highlight over `a ==b`. Written
            naively it emitted four `=` characters that were never in the
            document and left the tail unmarked. Two overlapping
            annotations produce exactly this shape, so it is not
            hypothetical"
    (let [attr {:classes [] :props {}}
          document {:meta {}
                    :blocks [{:t :para
                              :inlines [(str-inline "Hazelnuts ")
                                        {:t :highlight :attr attr
                                         :inlines [(str-inline "ripen ")
                                                   {:t :highlight :attr attr
                                                    :inlines [(str-inline "in early")]}
                                                   (str-inline " autumn")]}
                                        (str-inline " and drop.")]}]}
          emitted (hdoc/emit-document document)
          back (parser/parse emitted)]
      (is (= "Hazelnuts ripen in early autumn and drop."
             (select/flatten-text (first (:blocks back))))
          (str "not one character invented or lost: " (pr-str emitted)))
      (is (= 1 (count (filter #(= :highlight (:t %)) (tree-seq coll? seq (:blocks back)))))
          "the outer highlight survives as a highlight")
      (is (= 1 (count (filter #(and (= :span (:t %))
                                    (contains? (set (:classes (:attr %))) "highlight"))
                              (tree-seq coll? seq (:blocks back)))))
          "and the inner one as a role span, content-preserving and kind-lossy")
      (is (= [:haselnuss.emit.hdoc/lossy-nested-highlight]
             (mapv :type (hdoc/lossy-role-span-diagnostics document)))
          "reported the same way every other kind-lossy round trip is")))
  (testing "a highlight with no highlight inside it is NOT diagnosed --
            it is an exact round trip, and a warning would be noise"
    (is (= [] (hdoc/lossy-role-span-diagnostics
               {:meta {} :blocks [{:t :para
                                   :inlines [{:t :highlight
                                              :inlines [(str-inline "x")]
                                              :attr {:classes [] :props {}}}]}]})))))

(deftest sticky-note-attr-loss-is-named-test
  (testing "TASK-123 review: sec4.3 gives :note no :attr at all, so the
            footnote a sticky note degrades to has nowhere to carry one
            -- its id, color= and author= are simply gone. The diagnostic
            used to name only the footnote number, which would let an
            author believe an id survived that did not"
    (let [document {:meta {}
                    :blocks [{:t :para
                              :inlines [{:t :sticky-note
                                         :blocks [(para (str-inline "A comment."))]
                                         :attr {:id "sn:1" :classes []
                                                :props {"color" "green" "author" "DM"}}}]}]}
          emitted (hdoc/emit-document document)
          [diagnostic] (hdoc/lossy-role-span-diagnostics document)]
      (is (not (str/includes? emitted "sn:1")) "the id really is gone")
      (is (not (str/includes? emitted "green")))
      (is (str/includes? (:message diagnostic) "no Attr"))
      (is (str/includes? (:message diagnostic) "id"))
      (is (str/includes? (:message diagnostic) "author=")))))

(deftest sticky-note-degrades-to-a-footnote-test
  (testing "AC #4: a :sticky-note emits a footnote marker plus its
            definition, preserving every block of its body. It has no
            inline surface syntax by design (doc-8 sec3), so this is
            content-preserving and kind-lossy -- exactly the contract
            :strike/:small-caps/:sub/:sup already round-trip under"
    (let [document {:meta {}
                    :blocks [{:t :para
                              :inlines [(str-inline "text")
                                        {:t :sticky-note
                                         :blocks [(para (str-inline "First block."))
                                                  (para (str-inline "Second block."))]
                                         :attr {:classes [] :props {}}}]}]}
          emitted (hdoc/emit-document document)
          back (parser/parse emitted)
          note (first (filter #(= :note (:t %)) (tree-seq coll? seq (:blocks back))))]
      (is (str/includes? emitted "[^1]"))
      (is (some? note) "it comes back as a footnote")
      (is (= 2 (count (:blocks note))) "with every block of the body preserved")
      (is (str/includes? emitted "First block."))
      (is (str/includes? emitted "Second block."))))
  (testing "AC #5: a :sticky-note produces a ::lossy-sticky-note
            diagnostic through the SAME path as the existing lossy role
            spans, rather than a second reporting shape"
    (let [document {:meta {}
                    :blocks [{:t :para
                              :inlines [{:t :sticky-note :blocks [(para (str-inline "x"))]
                                         :attr {:classes [] :props {}}}
                                        {:t :strike :inlines [(str-inline "y")]}]}]}
          diagnostics (hdoc/lossy-role-span-diagnostics document)]
      (is (= [:haselnuss.emit.hdoc/lossy-sticky-note :haselnuss.emit.hdoc/lossy-role-span]
             (mapv :type diagnostics))
          "both kinds of loss, in document order, from the one function")
      (is (= :sticky-note (:t (first diagnostics))))
      (is (str/includes? (:message (first diagnostics)) "footnote number")
          "and it states the cost, which is the one thing a footnote adds")))
  (testing "AC #6: a document mixing real footnotes with degraded sticky
            notes emits both with distinct, correctly ordered markers and
            definitions. The degradation goes through render-note itself
            rather than a copy of it, so the two share one numbering and
            one deferral mechanism and cannot collide (doc-8 pitfall 5)"
    (let [document {:meta {}
                    :blocks [{:t :para
                              :inlines [(str-inline "a")
                                        {:t :note :blocks [(para (str-inline "real one"))]}
                                        (str-inline " b")
                                        {:t :sticky-note :blocks [(para (str-inline "sticky"))]
                                         :attr {:classes [] :props {}}}
                                        (str-inline " c")
                                        {:t :note :blocks [(para (str-inline "real two"))]}]}]}
          emitted (hdoc/emit-document document)
          back (parser/parse emitted)
          notes (filter #(= :note (:t %)) (tree-seq coll? seq (:blocks back)))]
      (is (= "a[^1] b[^2] c[^3]\n\n[^1]: real one\n\n[^2]: sticky\n\n[^3]: real two\n" emitted)
          "three distinct markers in position order, three definitions in the same order")
      (is (= 3 (count notes)))
      (is (= ["real one" "sticky" "real two"]
             (mapv (fn [note] (select/flatten-text (first (:blocks note)))) notes))
          "and each definition came back attached to its own marker"))))

(deftest annotated-document-round-trips-test
  (testing "AC #7: a document containing each new node type survives the
            round trip -- the highlight exactly, the sticky note as a
            footnote carrying all of its content"
    (let [document {:meta {}
                    :blocks [{:t :para
                              :inlines [(str-inline "Hazelnuts ")
                                        {:t :highlight :inlines [(str-inline "ripen")]
                                         :attr {:id "hl:a" :classes [] :props {}}}
                                        (str-inline " and drop.")
                                        {:t :sticky-note
                                         :blocks [(para (str-inline "Check the notebook."))]
                                         :attr {:classes [] :props {}}}]}]}
          emitted (hdoc/emit-document document)
          back (parser/parse emitted)
          again (hdoc/emit-document back)]
      (is (= emitted again)
          "emit is stable: a second pass over the re-parsed document is byte-identical")
      (is (some #(= :highlight (:t %)) (tree-seq coll? seq (:blocks back))))
      (is (= "hl:a" (->> (tree-seq coll? seq (:blocks back))
                         (filter #(= :highlight (:t %)))
                         first :attr :id))
          "the highlight keeps its id through the round trip")
      (is (some #(= :note (:t %)) (tree-seq coll? seq (:blocks back)))))))

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
            [clojure.walk :as walk]
            [haselnuss.ast :as ast]
            [haselnuss.emit.hdoc :as hdoc]
            [haselnuss.emit.html :as html]
            [haselnuss.json :as json]
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
    (is (= 12 (count all-block-tags)) "the derivation found the schema's own Block variants")
    (is (= all-block-tags (set (keys hdoc/built-in-block-handlers)))))
  (testing "TASK-95 AC #4: every Inline variant has a handler"
    (is (= 21 (count every-inline-tag)) "the derivation found the schema's own Inline variants")
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

(deftest book-ref-and-quote-round-trip-test
  (testing "TASK-152 AC #4: both nodes are written back verbatim, the ref
            exactly as written -- short form, malformed or not"
    (let [src (str "See @{bible:romanos#14:8}, @{reformed:confessions/reformed/heidelberg#sec:q1}"
                   " and @{not a ref}.\n\n"
                   "!quote bible:romanos#14:8-9\n\n"
                   ":::{note}\n!quote Bad:Ref\n:::\n")
          out (hdoc/emit-document (parser/parse src))]
      (is (str/includes? out "See @{bible:romanos#14:8}, @{reformed:confessions/reformed/heidelberg#sec:q1} and @{not a ref}."))
      (is (str/includes? out "\n!quote bible:romanos#14:8-9\n"))
      (is (str/includes? out ":::{note}\n!quote Bad:Ref\n:::"))
      (is (fixed-point? src))))
  (testing "resolved fields are computed content and are not written back"
    (let [doc {:meta {}
               :blocks [{:t :para :inlines [{:t :book-ref :ref "bible:romanos#14:8"
                                             :canonical "bible:scripture/nt/romanos#14:8"
                                             :text [(str-inline "Romanos 14:8")]}]}
                        {:t :book-quote :ref "bible:romanos#14:8"
                         :blocks [(para (str-inline "Porque"))]}]}]
      (is (= "@{bible:romanos#14:8}\n\n!quote bible:romanos#14:8\n" (hdoc/emit-document doc)))))
  (testing "literal text that looks like a book ref is escaped at its brace,
            since `\\@{x}` would re-parse as an attribute group"
    (is (fixed-point? "A literal @\\{not:a-ref} and @\\{a *b*} here.\n"))
    (is (= "a@\\{x}\n"
           (hdoc/emit-document {:meta {} :blocks [(para (str-inline "a@") (str-inline "{x}"))]}))
        "an `@` ending one text run still guards a `{` opening the next"))
  (testing "a ref with no spelling throws rather than re-parsing differently"
    (doseq [node [{:t :para :inlines [{:t :book-ref :ref "a}b"}]}
                  {:t :para :inlines [{:t :book-ref :ref " "}]}
                  {:t :book-quote :ref " bible:x"}
                  {:t :book-quote :ref "bible:x\nmore"}]]
      (let [e (is (thrown? clojure.lang.ExceptionInfo (hdoc/emit-document {:meta {} :blocks [node]})))]
        (is (= ::hdoc/unrepresentable-book-ref (:type (ex-data e))) (pr-str node)))))
  (testing "a :book-quote inside a list item, like an :include, has no spelling"
    (let [doc {:meta {} :blocks [{:t :list :ordered false :tight false :attr empty-attr
                                  :items [[(para (str-inline "x")) {:t :book-quote :ref "bible:x"}]]}]}
          e (is (thrown? clojure.lang.ExceptionInfo (hdoc/emit-document doc)))]
      (is (= ::hdoc/unrepresentable-nested-block (:type (ex-data e))))))
  (testing "a directive named quote is not written as a `!quote` line"
    (is (= ":::{quote}\n:::\n"
           (hdoc/emit-document {:meta {} :blocks [{:t :directive :name "quote" :blocks [] :attr empty-attr}]})))
    (is (fixed-point? ":::{quote}\n:::\n"))))

(deftest undefined-bracket-round-trip-test
  (testing "TASK-160 AC #3: an undefined `[...]` that keeps a book ref,
            cross-ref, citation or inline math inside it re-parses to the
            same nodes -- its brackets are `:str` text, so they are written
            escaped and cannot turn back into a link or a citation group"
    (doseq [src ["[see @{bible:romanos#14:8}]\n"
                 "[$x^2$]\n"
                 "[@smith2020; @{bible:x}]\n"
                 "[see @fig:x; and more]\n"
                 "[see *this*] and [foo bar]\n"
                 "[$y$][nope] and [$y$][]\n"
                 "[$z$][nope]{.c}\n"
                 "[see\n@fig:x; z]\n"
                 ;; Edge spaces, which flexmark leaves outside the children.
                 "[ foo ]\n" "[ $x$ ]\n" "[ foo bar ]\n" "[ *a* ]\n" "[ @{a:b} ]\n"
                 "[$x$ ][nope]\n" "[ $z$ ][nope]{.c}\n"
                 ;; Prose touching the bracket, as in a thesis table header.
                 "CLC[$i$] and x[$i$]y\n"]]
      (is (fixed-point? src) src)))
  (testing "the defined constructs still round-trip as themselves"
    (doseq [src ["[see @fig:x]\n" "[see $x$]{.c}\n" "[@{a:b}](u)\n"]]
      (is (fixed-point? src) src))))

(deftest undefined-image-ref-round-trip-test
  (testing "TASK-166 AC #3: an undefined image reference that keeps math,
            emphasis, a book ref or a cross-ref in its alt text re-parses to
            the same nodes -- its `!`, brackets and `[ref]` are `:str` text,
            written escaped, so they cannot turn back into an image"
    (doseq [src ["![$x$][nope]\n" "![a *b*]\n" "![x][]\n" "![$y$][]\n" "![x][nope]\n"
                 "![@{a:b}]\n" "![see @fig:x; z]\n" "![see\n@fig:x; @{b:c} z]\n"
                 "![]\n" "![][nope]\n"
                 ;; Edge spaces, which flexmark leaves outside the children.
                 "![ foo ]\n" "![ $x$ ][nope]\n" "![ *a* ]\n"
                 ;; Prose touching the syntax, a doubled `!`, and nesting.
                 "x![$i$]y\n" "!![$x$]\n" "[![$x$]]\n" "![![$y$]]\n"
                 ;; TASK-161's `{` mark still applies to the text after it
                 ;; and inside it.
                 "![$x$]\\{x}\n" "![a \\{x} $y$]\n" "x![$i$]y \\{z}\n"]]
      (is (fixed-point? src) src)))
  (testing "a defined image reference still round-trips as an image"
    (is (fixed-point? "![$x$][r]\n\n[r]: a.png\n"))))

(deftest image-ref-attribute-group-round-trip-test
  (testing "TASK-169 AC #3: a defined reference image with an attribute
            group is written as the inline image it parsed to -- the AST
            keeps no reference form -- and re-parses to the same nodes"
    (doseq [[src out] [["![x][r]{.c}\n\n[r]: a.png \"T\"\n" "![x](a.png \"T\"){.c}\n"]
                       ["![x][]{.c}\n\n[x]: a.png\n" "![x](a.png){.c}\n"]
                       ["![x]{.c}\n\n[x]: a.png\n" "![x](a.png){.c}\n"]
                       ["![*x* $y$][r]{#fig:a .c w=1}\n\n[r]: a.png\n"
                        "![*x* $y$](a.png){#fig:a .c w=1}\n"]]]
      (is (= out (hdoc/emit-document (parser/parse src))) src)
      (is (fixed-point? src) src)))
  (testing "an undefined one is its escaped literal text in an attributed
            Span, and re-parses to the same nodes"
    (doseq [src ["![x][nope]{.c}\n" "![$x$][]{.c}\n" "![x]{.c}\n" "![ $z$ ][nope]{#i}\n"]]
      (is (fixed-point? src) src))
    ;; TASK-170: the `!` keeps no backslash, the escaped `[` after it
    ;; opens nothing.
    (is (= "[!\\[x\\]\\[nope\\]]{.c}\n" (hdoc/emit-document (parser/parse "![x][nope]{.c}\n"))))))

(defn- emit-src
  "`src` parsed and written back out."
  [src]
  (hdoc/emit-document (parser/parse src)))

(deftest attributed-span-in-link-and-spaced-citation-round-trip-test
  (testing "TASK-184 AC #3: an attributed span inside link text is written
            as one and reads back as the same link"
    (doseq [[src out] [["[a [s]{.c}](u)\n" "[a [s]{.c}](u)\n"]
                       ["[See [@doe]{.c}](u)\n" "[See [@doe]{.c}](u)\n"]
                       ["[See [[@doe]]{.c}](u)\n" "[See [[@doe]]{.c}](u)\n"]
                       ["x[a [*s* `c`]{.c}y](u)\\{x}\n" "x[a [*s* `c`]{.c}y](u)\\{x}\n"]
                       ["[a [s]{.c}][r]\n\n[r]: v\n" "[a [s]{.c}](v)\n"]
                       ["[a ![s]{.c}](u)\n" "[a [!\\[s\\]]{.c}](u)\n"]
                       ["[a [x] [s]{.c}](u)\n" "[a \\[x\\] [s]{.c}](u)\n"]
                       ["[b [c]{.x}]{.y}\n" "[b [c]{.x}]{.y}\n"]
                       ["# [a [s]{.c}](u) {#h}\n" "# [a [s]{.c}](u) {#h}\n"]
                       ;; A span holding a link; a link around such a span
                       ;; is literal text (CommonMark), escaped on writing.
                       ["[x [y](u) z]{.c}\n" "[x [y](u) z]{.c}\n"]
                       ["[[x [y](v)]{.c}](u)\n" "\\[[x [y](v)]{.c}\\](u)\n"]
                       ["[a [b [y](v)]{.c}](u)\n" "\\[a [b [y](v)]{.c}\\](u)\n"]]]
      (is (= out (emit-src src)) src)
      (is (fixed-point? src) src)
      (is (= out (emit-src out)) out)))
  (testing "TASK-184 AC #3: a citation bracket with its group after a space
            round-trips as `[s] {.c}` does -- the space goes, as a Span --
            and stays a citation where the group is a heading's or a
            caption's"
    (doseq [[src out] [["[@doe] {.c}\n" "[@doe]{.c}\n"]
                       ["[s] {.c}\n" "[s]{.c}\n"]
                       ["See [@doe]\n{.c}\n" "See [@doe]{.c}\n"]
                       ["[See [@doe] {.c}](u)\n" "[See [@doe]{.c}](u)\n"]
                       ["# See [@doe] {#sec:x}\n" "# See [@doe] {#sec:x}\n"]
                       ["| a |\n|---|\n| b |\n: See [@doe] {#tbl:x}\n"
                        "| a |\n| --- |\n| b |\n: See [@doe] {#tbl:x}\n"]]]
      (is (= out (emit-src src)) src)
      (is (fixed-point? src) src))))

(deftest attributed-span-over-defined-reference-round-trip-test
  (testing "TASK-190 AC #3: a span in link text over a defined label is
            written as the link holding the span, which reads back as the
            same document -- on its own, and with the definition still in
            scope, where the defined label must not cost the link again"
    (doseq [[src out] [["[r]: v\n\n[a [r]{.c}](u)\n" "[a [r]{.c}](u)\n"]
                       ["[r]: v\n\n[a [r] {.c}](u)\n" "[a [r]{.c}](u)\n"]
                       ["[r]: v\n\n[a [r]\n{.c}](u)\n" "[a [r]{.c}](u)\n"]
                       ["[r]: v\n\n[[r]{.c}](u)\n" "[[r]{.c}](u)\n"]
                       ;; Edge whitespace goes, defined or not (review).
                       ["[r]: v\n\n[a [ r ]{.c}](u)\n" "[a [r]{.c}](u)\n"]
                       ["[a [ r ]{.c}](u)\n" "[a [r]{.c}](u)\n"]
                       ["[r]: v\n\n[a [r]{.c}][r]\n" "[a [r]{.c}](v)\n"]
                       ["[r]: v\n\n# [a [r]{.c}](u) {#h}\n" "# [a [r]{.c}](u) {#h}\n"]
                       ;; Unchanged: `[r]{.c}` is a Span, and a plain `[r]`
                       ;; in link text is a link that cancels the outer one.
                       ["[r]: v\n\n[r]{.c}\n" "[r]{.c}\n"]
                       ["[r]: v\n\n[a [r]](u)\n" "\\[a [r](v)\\](u)\n"]]]
      (is (= out (emit-src src)) src)
      (is (fixed-point? src) src)
      (is (= (:blocks (parser/parse src)) (:blocks (parser/parse (str "[r]: v\n\n" out)))) src))))

(defn- nested-link-doc
  "A document whose one paragraph is a link to `u` holding `inner`."
  [inner]
  {:meta {}
   :blocks [{:t :para
             :inlines [{:t :link :target "u" :attr empty-attr
                        :inlines [(str-inline "a") space inner]}]}]})

(deftest autolink-in-link-text-round-trip-test
  (testing "TASK-192: an autolink inside link text is written `<uri>`, the
            one spelling that reads back as a link inside a link -- at any
            depth of Inlines under the link, and verbatim, with nothing in
            the URI escaped"
    (doseq [[src out] [["[a <http://x>](u)\n" "[a <http://x>](u)\n"]
                       ["[a [c <http://y>]{.k}](v)\n" "[a [c <http://y>]{.k}](v)\n"]
                       ["*[<http://z>](w)*\n" "*[<http://z>](w)*\n"]
                       ["[a *b <http://z>*](w)\n" "[a *b <http://z>*](w)\n"]
                       ["[<HTTP://x?a=*b*_c_>](u)\n" "[<HTTP://x?a=*b*_c_>](u)\n"]
                       ["[a <a+b.c-d:x\\y{.c}[z]`q`$m$>](u)\n" "[a <a+b.c-d:x\\y{.c}[z]`q`$m$>](u)\n"]
                       ["[a\n<http://x>](u)\n" "[a\n<http://x>](u)\n"]
                       ["- [a <http://x>](u){.c}\n" "- [a <http://x>](u){.c}\n"]
                       ["> [a <http://x/|y>](u)\n" "> [a <http://x/|y>](u)\n"]
                       ["# [a <http://x>](u) {#h}\n" "# [a <http://x>](u) {#h}\n"]
                       ["| [a <http://x>](u) |\n|---|\n| b |\n" "| [a <http://x>](u) |\n| --- |\n| b |\n"]]]
      (is (= out (emit-src src)) src)
      (is (fixed-point? src) src)
      (is (= out (emit-src out)) out)))
  (testing "an autolink anywhere else keeps the `[uri](uri)` spelling, so a
            document with none in link text is written as before"
    (is (= "[http://q](http://q) [b](u)\n" (emit-src "<http://q> [b](u)\n")))
    (is (= "[a](u)[^1]\n\n[^1]: [http://n](http://n)\n"
           (emit-src "[a](u)[^1]\n\n[^1]: <http://n>\n"))))
  (testing "a link in link text that is not such an autolink is refused,
            since no spelling reads back as a link inside a link"
    (doseq [inner [{:t :link :target "v" :attr empty-attr :inlines [(str-inline "w")]}
                   {:t :link :target "http://x" :title "t" :attr empty-attr :inlines [(str-inline "http://x")]}
                   {:t :link :target "http://x" :attr {:classes ["c"] :props {}} :inlines [(str-inline "http://x")]}
                   {:t :link :target "mailto:m e@x" :attr empty-attr :inlines [(str-inline "m e@x")]}
                   {:t :link :target "a:x" :attr empty-attr :inlines [(str-inline "a:x")]}]]
      (let [e (try (hdoc/emit-document (nested-link-doc inner)) nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (= ::hdoc/unrepresentable-nested-link (:type (ex-data e))) (pr-str inner))
        (is (str/includes? (str (ex-message e)) "link inside link text") (pr-str inner)))))
  (testing "TASK-200: an autolink holding `@` is refused where the link text
            would then read as a citation -- `<…>` admits no escape, and
            `[a <me@x.org>](u)` is a citation of `x` to the parser"
    (doseq [inner [{:t :link :target "mailto:me@x.org" :attr empty-attr :inlines [(str-inline "me@x.org")]}
                   {:t :link :target "http://x/@k" :attr empty-attr :inlines [(str-inline "http://x/@k")]}]]
      (let [e (try (hdoc/emit-document (nested-link-doc inner)) nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (= ::hdoc/unrepresentable-nested-link (:type (ex-data e))) (pr-str inner))
        (is (str/includes? (str (ex-message e)) "autolink holding `@` inside link text") (pr-str inner))
        (is (str/includes? (str (ex-message e)) "bracketed citation") (pr-str inner)))))
  (testing "and so is a `|` in one inside a table cell, where the cell's own
            `\\|` escape would become part of the URI"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"link inside link text"
                          (emit-src "| [a <http://x/\\|y>](u) |\n|---|\n| b |\n")))))

(defn- cite-affix-link-doc
  "A document whose one paragraph is a citation of `k` whose prefix (or,
  with `suffix?`, suffix) is `see` and then `link`."
  [link suffix?]
  {:meta {}
   :blocks [{:t :para
             :inlines [{:t :cite
                        :items [{:key "k" :mode :normal
                                 (if suffix? :suffix :prefix) [(str-inline "see") space link]}]}]}]})

(deftest autolink-in-citation-text-round-trip-test
  (testing "TASK-199 AC #1: an autolink in a citation's prefix or suffix is
            written `<uri>` and reads back as the same citation -- an `@`
            in a suffix's URI, and an email autolink there, included"
    (doseq [[src out] [["[@k, see <http://x>]\n" "[@k, see <http://x>]\n"]
                       ["[see <http://x> @k]\n" "[see <http://x> @k]\n"]
                       ["[see <http://x> -@k, p. <http://y> and <z:w>]\n"
                        "[see <http://x> -@k, p. <http://y> and <z:w>]\n"]
                       ["[@a, <http://x>; @b, <me@y.org>]\n" "[@a, <http://x>; @b, <me@y.org>]\n"]
                       ["[@k, see <http://x/@j>]\n" "[@k, see <http://x/@j>]\n"]
                       ["[@k, see <me@x.org>]\n" "[@k, see <me@x.org>]\n"]
                       ["[@k, see <mailto:me@x.org>]\n" "[@k, see <mailto:me@x.org>]\n"]
                       ["[@k, <HTTP://x?a=*b*_c_{.c}\\y>]\n" "[@k, <HTTP://x?a=*b*_c_{.c}\\y>]\n"]
                       ["[see\n<http://x> @k]\n" "[see\n<http://x> @k]\n"]
                       ["[@k, see\n<http://x>]\n" "[@k, see\n<http://x>]\n"]
                       ["[a [@k, <http://x>]](u)\n" "[a [@k, <http://x>]](u)\n"]
                       ["[[@k, <http://x>]]{.c}\n" "[[@k, <http://x>]]{.c}\n"]
                       ["> - [@k, <http://x>]\n" "> - [@k, <http://x>]\n"]
                       ["| [@k, <http://x>] |\n|---|\n| b |\n" "| [@k, <http://x>] |\n| --- |\n| b |\n"]
                       ;; Unchanged: an autolink outside the citation.
                       ["x [@k, <http://x>] <http://q>\n" "x [@k, <http://x>] [http://q](http://q)\n"]]]
      (is (= out (emit-src src)) src)
      (is (fixed-point? src) src)
      (is (= out (emit-src out)) out)
      ;; Not vacuous: what reads back holds a citation with the link in it.
      (is (re-find #":t :cite, :items \[.*:t :link" (pr-str (parser/parse out))) out)))
  (testing "TASK-199 AC #1: a link in citation text that is no such autolink
            is refused -- a `[` would end the parser's bracket scan, an `@`
            in a prefix would be the key, a `[`/`]` in the URI ends or
            closes the bracket, a backtick or `$` opens a span to the scan"
    (doseq [[link suffix?] [[{:t :link :target "v" :attr empty-attr :inlines [(str-inline "w")]} true]
                            [{:t :link :target "http://x" :title "t" :attr empty-attr
                              :inlines [(str-inline "http://x")]} true]
                            [{:t :link :target "http://x" :attr {:classes ["c"] :props {}}
                              :inlines [(str-inline "http://x")]} true]
                            [{:t :link :target "http://x/@j" :attr empty-attr :inlines [(str-inline "http://x/@j")]} false]
                            [{:t :link :target "mailto:me@x.org" :attr empty-attr :inlines [(str-inline "me@x.org")]} false]
                            [{:t :link :target "mailto:m e@x" :attr empty-attr :inlines [(str-inline "m e@x")]} true]
                            [{:t :link :target "http://x]y" :attr empty-attr :inlines [(str-inline "http://x]y")]} true]
                            [{:t :link :target "http://x[y" :attr empty-attr :inlines [(str-inline "http://x[y")]} true]
                            [{:t :link :target "http://x`y" :attr empty-attr :inlines [(str-inline "http://x`y")]} true]
                            [{:t :link :target "http://x$y" :attr empty-attr :inlines [(str-inline "http://x$y")]} false]]]
      (let [e (try (hdoc/emit-document (cite-affix-link-doc link suffix?)) nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (= ::hdoc/unrepresentable-cite-link (:type (ex-data e))) (pr-str link))
        (is (str/includes? (str (ex-message e)) "citation's prefix or suffix") (pr-str link)))))
  (testing "a `;` in the URI is the affix check's, as for any affix text"
    (let [link {:t :link :target "http://x;y" :attr empty-attr :inlines [(str-inline "http://x;y")]}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cannot represent a `;`"
                            (hdoc/emit-document (cite-affix-link-doc link true))))))
  (testing "and a `|` in a table cell, whose own `\\|` escape would become
            part of the URI"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"citation's prefix or suffix"
                          (emit-src "| [@k, <http://x/|y>] |\n|---|\n| b |\n")))))

(defn- link-text-doc
  "A document whose one paragraph is a link to `u` holding `inlines`."
  [& inlines]
  {:meta {} :blocks [(para {:t :link :target "u" :attr empty-attr :inlines (vec inlines)})]})

(defn- emit-error
  "The `ex-info` emitting `document` throws, or nil."
  [document]
  (try (hdoc/emit-document document) nil
       (catch clojure.lang.ExceptionInfo e e)))

(deftest at-autolink-in-link-text-round-trip-test
  (testing "TASK-200 AC #2: an autolink holding `@` -- an email autolink or a
            URI -- is written `<…>` in link text that would not read as a
            citation, and in a citation's suffix inside link text"
    (doseq [[src out] [["[a; b <c@d.e>](u)\n" "[a; b <c@d.e>](u)\n"]
                       ["[a; b <http://x/@k>](u)\n" "[a; b <http://x/@k>](u)\n"]
                       ["[<c@d.e>; b](u)\n" "[<c@d.e>; b](u)\n"]
                       ["[a\\@b; c <me@x.org>](u)\n" "[a\\@b; c <me@x.org>](u)\n"]
                       ["[*a; <c@d.e>*](u)\n" "[*a; <c@d.e>*](u)\n"]
                       ["[[a; <c@d.e>](u)]{.c}\n" "[[a; <c@d.e>](u)]{.c}\n"]
                       ["| [a; <c@d.e>](u) |\n|---|\n| b |\n" "| [a; <c@d.e>](u) |\n| --- |\n| b |\n"]
                       ["[a [@k, <http://x/@j>]](u)\n" "[a [@k, <http://x/@j>]](u)\n"]
                       ["[a [@k, <me@x.org>]](u)\n" "[a [@k, <me@x.org>]](u)\n"]
                       ["[a [@k, <http://x/@j>]; @z](u)\n" "[a [@k, <http://x/@j>]; @z](u)\n"]]]
      (is (= out (emit-src src)) src)
      (is (fixed-point? src) src)
      (is (= out (emit-src out)) out)
      ;; Not vacuous: what reads back is a link holding the autolink.
      (is (re-find #"\{:t :link, :target \"u\", :inlines \[.*:t :link, :target \"(mailto:)?[^\"]*@"
                   (pr-str (parser/parse out)))
          out)))
  (testing "TASK-200: a link in a citation's affix inside link text is
            refused under the citation's own rules, and says so"
    (let [e (emit-error (link-text-doc {:t :cite
                                        :items [{:key "k" :mode :normal
                                                 :prefix [{:t :link :target "http://x/@j" :attr empty-attr
                                                           :inlines [(str-inline "http://x/@j")]}]}]}))]
      (is (= ::hdoc/unrepresentable-cite-link (:type (ex-data e))))
      (is (str/includes? (str (ex-message e)) "citation's prefix or suffix")))))

(deftest link-text-reading-as-citation-test
  (testing "TASK-200 AC #1: a link whose text holds a bare cross-reference
            or author citation in every `;`-segment would re-parse as a
            citation, and is refused"
    (doseq [inlines [[{:t :cross-ref :label "fig:x"}]
                     [{:t :cite :items [{:key "doe" :mode :author}]}]
                     [(str-inline "see") space {:t :cross-ref :label "fig:x"}
                      (str-inline ";") space {:t :cite :items [{:key "doe" :mode :author}]}]
                     [{:t :emph :inlines [{:t :cross-ref :label "fig:x"}]}]
                     ;; The `@` in prose is escaped, the cross-reference's cannot be.
                     [(str-inline "x@y") space {:t :cross-ref :label "fig:x"}]]]
      (let [e (emit-error (apply link-text-doc inlines))]
        (is (= ::hdoc/unrepresentable-link-text (:type (ex-data e))) (pr-str inlines))
        (is (str/includes? (str (ex-message e)) "re-parse as a bracketed citation") (pr-str inlines)))))
  (testing "the same source mostly parses as a citation"
    (is (= :cite (-> (parser/parse "[@fig:x](u)\n") :blocks first :inlines first :t))))
  (testing "TASK-207: a `;` and whitespace before the `]` no longer make a
            link (TASK-200 refused what they made) -- the source is the
            citation `[@k;](u)` is, followed by `(u)`, and round-trips"
    (doseq [[src out] [["[@k; ](u)\n" "[@k](u)\n"]
                       ["[@fig:x; ](u)\n" "[@fig:x](u)\n"]
                       ["[@a; @b;\t](u)\n" "[@a; @b](u)\n"]]]
      (is (= :cite (-> (parser/parse src) :blocks first :inlines first :t)) src)
      (is (= out (emit-src src)) src)
      (is (fixed-point? src) src)))
  (testing "so the link text `@k;` is hand-built only, and still refused"
    (doseq [[inlines error-type] [[[{:t :cite :items [{:key "k" :mode :author}]} (str-inline ";")]
                                   ::hdoc/unrepresentable-link-text]
                                  [[{:t :cite :items [{:key "k" :mode :author}]} (str-inline ";") space]
                                   ::hdoc/unrepresentable-link-text]
                                  [[{:t :link :target "mailto:c@d.e" :attr empty-attr
                                     :inlines [(str-inline "c@d.e")]} (str-inline ";")]
                                   ::hdoc/unrepresentable-nested-link]]]
      (let [e (emit-error (apply link-text-doc inlines))]
        (is (= error-type (:type (ex-data e))) (pr-str inlines))
        (is (str/includes? (str (ex-message e)) "re-parse as a bracketed citation") (pr-str inlines)))))
  (testing "a segment with no unescapable `@` keeps the link"
    (doseq [inlines [[{:t :cross-ref :label "fig:x"} (str-inline ";") space (str-inline "b")]
                     [{:t :cite :items [{:key "doe" :mode :author}]} (str-inline ";") space (str-inline "x@y")]]]
      (let [document (apply link-text-doc inlines)
            out (hdoc/emit-document document)]
        (is (= (:blocks document) (:blocks (parser/parse out))) out)))))

(deftest bracket-led-inline-in-citation-text-test
  (testing "TASK-200: a span, image or other `[`-led Inline in a citation's
            prefix or suffix would end the parser's bracket scan, and is
            refused"
    (doseq [[affix inlines] [[:suffix [{:t :span :attr {:classes ["c"] :props {}}
                                        :inlines [{:t :link :target "http://x" :attr empty-attr
                                                   :inlines [(str-inline "http://x")]}]}]]
                             [:prefix [{:t :span :attr {:classes ["c"] :props {}} :inlines [(str-inline "s")]}]]
                             [:suffix [{:t :image :src "i.png" :alt "a" :attr empty-attr}]]
                             [:suffix [{:t :small-caps :inlines [(str-inline "s")]}]]]]
      (let [e (emit-error {:meta {} :blocks [(para {:t :cite :items [{:key "k" :mode :normal affix inlines}]})]})]
        (is (= ::hdoc/unrepresentable-cite-text (:type (ex-data e))) (pr-str inlines))
        (is (str/includes? (str (ex-message e)) "citation \"k\"") (pr-str inlines))
        (is (str/includes? (str (ex-message e)) "would not read back as a citation segment") (pr-str inlines)))))
  (testing "so is a hand-built key whose `@` would be no marker, with the
            same message naming it"
    (doseq [k ["" "é" ".x"]]
      (let [e (emit-error {:meta {} :blocks [(para {:t :cite :items [{:key k :mode :normal}]})]})]
        (is (= ::hdoc/unrepresentable-cite-text (:type (ex-data e))) (pr-str k))
        (is (str/includes? (str (ex-message e)) (str "citation " (pr-str k) ":")) (pr-str k)))))
  (testing "the source shape is no bracketed citation to the parser -- a
            literal `[`, an author citation, then the span"
    (is (= [:str :cite :str :space :span :str]
           (mapv :t (-> (parser/parse "[@k, [<http://x>]{.c}]\n") :blocks first :inlines))))
    (is (= "\\[@k, [[http://x](http://x)]{.c}\\]\n" (emit-src "[@k, [<http://x>]{.c}]\n")))))

(defn- cite-doc
  "A document whose one paragraph is a Cite of one item with `cite-key` in
  `mode`."
  [cite-key mode]
  {:meta {} :blocks [(para {:t :cite :items [{:key cite-key :mode mode}]})]})

(deftest cite-key-outside-grammar-test
  (testing "TASK-207 AC #2: a bracketed citation key the parser would not
            read back whole -- one starting with a key character but going
            on past the grammar -- is refused, naming the key, in either
            bracketed mode"
    (doseq [k ["a.b" "müller" "a/b" "a b" "k]" "a,b" "a;b" "x@y" "abé"]
            mode [:normal :year]]
      (let [e (emit-error (cite-doc k mode))]
        (is (= ::hdoc/unrepresentable-cite-text (:type (ex-data e))) (pr-str k mode))
        (is (= k (:key (ex-data e))) (pr-str k mode))
        (is (str/includes? (str (ex-message e)) (str "citation " (pr-str k) ":")) (pr-str k mode))
        (is (str/includes? (str (ex-message e)) "bracketed citation key") (pr-str k mode)))))
  (testing "and so is an author-in-text key the bare scan would cut short or
            read as a cross-reference"
    (doseq [k ["a.b" "a/b" "a-" "a_" "" "a:b" ":a" "x@y"]]
      (let [e (emit-error (cite-doc k :author))]
        (is (= ::hdoc/unrepresentable-cite-text (:type (ex-data e))) (pr-str k))
        (is (str/includes? (str (ex-message e)) (str "citation " (pr-str k) ":")) (pr-str k))
        (is (str/includes? (str (ex-message e)) "author-in-text key") (pr-str k)))))
  (testing "while a key inside each grammar round-trips -- a bare author key
            may be any letter, as the bare scan reads Unicode letters"
    (doseq [[k mode] [["a" :normal] ["a-" :normal] ["a:b-c_d" :year] ["_" :normal] ["9" :normal]
                      ["müller" :author] ["a-b_c9" :author] ["é" :author]]]
      (let [document (cite-doc k mode)]
        (is (= (:blocks document) (:blocks (parser/parse (hdoc/emit-document document)))) (pr-str k mode)))))
  (testing "a cross-reference label the bare scan would cut short, or with
            no `:` (it would read as a citation), is refused naming it"
    (doseq [label ["fig:a.b" "fig:a/b" "fig:x:" "fig:x-" "fig" "" "fig:a b"]]
      (let [e (emit-error {:meta {} :blocks [(para {:t :cross-ref :label label})]})]
        (is (= ::hdoc/unrepresentable-cross-ref (:type (ex-data e))) (pr-str label))
        (is (= label (:label (ex-data e))) (pr-str label))
        (is (str/includes? (str (ex-message e)) (str "cross-reference " (pr-str label) ":")) (pr-str label)))))
  (testing "seeded fuzz: every key is either refused or read back whole, in
            every mode"
    (let [rng (java.util.Random. 207)
          alphabet "ab9_-:.é/ ;@,]"
          rand-key (fn [] (apply str (repeatedly (inc (.nextInt rng 5))
                                                 #(.charAt alphabet (.nextInt rng (count alphabet))))))]
      (doseq [k (repeatedly 300 rand-key)
              mode [:normal :year :author]]
        (let [document (cite-doc k mode)
              e (emit-error document)]
          (when-not e
            (is (= (:blocks document) (:blocks (parser/parse (hdoc/emit-document document))))
                (pr-str k mode))))))))

(deftest at-in-bracket-text-round-trip-test
  (testing "TASK-198 AC #1: an `@` in link text is escaped where the bracket
            would otherwise re-parse as a citation -- an email autolink,
            link text, and the same link nested in emphasis, a span, a
            heading, a table cell, a footnote or another wrapped line"
    (doseq [[src out] [["[write me\\@x.org](u)\n" "[write me\\@x.org](u)\n"]
                       ["<me@x.org>\n" "[me\\@x.org](mailto:me@x.org)\n"]
                       ["Mail <me@x.org> now.\n" "Mail [me\\@x.org](mailto:me@x.org) now.\n"]
                       ["[*me\\@x.org*](u)\n" "[*me\\@x.org*](u)\n"]
                       ["[me\\@x.org\nfoo](u)\n" "[me\\@x.org\nfoo](u)\n"]
                       ["[a\\@b-c and d\\@e:f](u)\n" "[a\\@b-c and d\\@e:f](u)\n"]
                       ["[x <http://a.b> me\\@x.org](u)\n" "[x <http://a.b> me\\@x.org](u)\n"]
                       ["[[me\\@x.org](u)]{.c}\n" "[[me\\@x.org](u)]{.c}\n"]
                       ["# H [me\\@x.org](u)\n" "# H [me\\@x.org](u)\n"]
                       ["| [me\\@x.org](u) |\n|---|\n| b |\n" "| [me\\@x.org](u) |\n| --- |\n| b |\n"]
                       ["x[^1]\n\n[^1]: [me\\@x.org](u)\n" "x[^1]\n\n[^1]: [me\\@x.org](u)\n"]
                       ["> - [me\\@x.org](u)\n" "> - [me\\@x.org](u)\n"]]]
      (is (= out (emit-src src)) src)
      (is (fixed-point? src) src)
      (is (= out (emit-src out)) out)))
  (testing "TASK-198: in a citation's prefix, an `@` that would be taken for
            the key is escaped; a suffix follows the key and keeps its own"
    (doseq [[src out] [["[pre x\\@y @key, suf]\n" "[pre x\\@y @key, suf]\n"]
                       ["[see\\@doe @k]\n" "[see\\@doe @k]\n"]
                       ["[@doe, see x@y]\n" "[@doe, see x@y]\n"]
                       ["[see [me\\@x.org](u) @k]\n" "\\[see [me\\@x.org](u) @k\\]\n"]]]
      (is (= out (emit-src src)) src)
      (is (fixed-point? src) src)
      (is (= out (emit-src out)) out)))
  (testing "TASK-198 AC #2: an `@` where no citation would be read is left
            bare -- a bracket with a segment holding no key, an `@` before
            no key character, a code span, a span or role span (its
            attribute group makes it no citation), a span inside link text
            (its `[` ends the citation scan) -- and an image's alt escapes
            every `@` as before"
    (doseq [[src out] [["[a@b; c](u)\n" "[a@b; c](u)\n"]
                       ["[a@ b](u)\n" "[a@ b](u)\n"]
                       ["[`x@y` z](u)\n" "[`x@y` z](u)\n"]
                       ["[a\\@b]{.c}\n" "[a@b]{.c}\n"]
                       ["[a [b\\@c]{.d}](u)\n" "[a [b@c]{.d}](u)\n"]
                       ["a@b [c](u)\n" "a@b [c](u)\n"]
                       ["![a@b](u)\n" "![a\\@b](u)\n"]
                       ["[plain *text*](u) and [s]{.c}\n" "[plain *text*](u) and [s]{.c}\n"]]]
      (is (= out (emit-src src)) src)
      (is (fixed-point? src) src)
      (is (= out (emit-src out)) out))
    (is (= "[x@y]{.sub}\n"
           (hdoc/emit-document {:meta {} :blocks [(para {:t :sub :inlines [(str-inline "x@y")]})]})))))

(deftest literal-angle-bracket-round-trip-test
  (testing "TASK-203 AC #1: a literal `<` that would re-parse as an
            autolink, raw inline HTML or an HTML block is written `\\<` --
            in a paragraph, a heading, link text, a citation prefix and
            suffix, a table cell, an image's alt, a footnote, a quote, and
            where the construct runs on through later inlines"
    (doseq [[src out] [["a \\<http://x> b\n" "a \\<http://x> b\n"]
                       ["a \\<me@x.org> b\n" "a \\<me@x.org> b\n"]
                       ["a \\<b> c \\</b> d\n" "a \\<b> c \\</b> d\n"]
                       ["a \\<br/> \\<a href='u' x>\n" "a \\<br/> \\<a href='u' x>\n"]
                       ["a \\<!-- x --> \\<? y ?> \\<!X z>\n" "a \\<!-- x --> \\<? y ?> \\<!X z>\n"]
                       ;; Escaping its `[` alone breaks a CDATA section.
                       ["a \\<![CDATA[ w ]]>\n" "a <!\\[CDATA\\[ w \\]\\]>\n"]
                       ;; Opening a physical line: an HTML block, even unclosed.
                       ["\\<div\n" "\\<div\n"]
                       ["x\n\\<div y\n" "x\n\\<div y\n"]
                       ["x\n  \\</DIV\n" "x\n\\</DIV\n"]
                       ["x\n\\<script\n" "x\n\\<script\n"]
                       ["x\n\\<!--\n" "x\n\\<!--\n"]
                       ["x\n\\<?y\n" "x\n\\<?y\n"]
                       ;; flexmark takes a namespace prefix on a block tag name.
                       ["x\n\\<ab:div\n" "x\n\\<ab:div\n"]
                       ["\\<http:div\n" "\\<http:div\n"]
                       ["# h \\<http://x>\n" "# h \\<http://x>\n"]
                       ["[a \\<http://x> \\<b>](u)\n" "[a \\<http://x> \\<b>](u)\n"]
                       ["[see \\<http://x> @k]\n" "[see \\<http://x> @k]\n"]
                       ["[@k, see \\<me@x.org> \\<b>]\n" "[@k, see \\<me@x.org> \\<b>]\n"]
                       ["[@k, x\n\\<div y]\n" "[@k, x\n\\<div y]\n"]
                       ["| a \\<b> |\n|---|\n| \\<http://x> |\n" "| a \\<b> |\n| --- |\n| \\<http://x> |\n"]
                       ;; An alt escapes every `>` and `!`, but the construct can
                       ;; close after the image.
                       ["![a \\<? b](u) ?>\n" "![a \\<? b](u) ?>\n"]
                       ["![x \\<a b=](u) c>\n" "![x \\<a b=](u) c>\n"]
                       ["![a <b title=\"u\"\\> c](u)\n" "![a <b title=\"u\"\\> c](u)\n"]
                       ["x[^1]\n\n[^1]: \\<http://x>\n" "x[^1]\n\n[^1]: \\<http://x>\n"]
                       ["> - \\<b>\n" "> - \\<b>\n"]
                       ;; The construct closes in a later inline.
                       ["a \\<http://*x*>\n" "a \\<http://*x*>\n"]
                       ["a \\<b title=\"*x*\">\n" "a \\<b title=\"*x*\">\n"]
                       ["a \\<http://x`y`>\n" "a \\<http://x`y`>\n"]
                       ["a \\<a b=](u) c>\n" "a \\<a b=\\](u) c>\n"]
                       ["a \\<!-- [l](u) -->\n" "a \\<!-- [l](u) -->\n"]
                       ;; A real autolink in link or citation text keeps its spelling.
                       ["[a <http://x> \\<http://y>](u)\n" "[a <http://x> \\<http://y>](u)\n"]
                       ["[@k, <http://x> \\<http://y>]\n" "[@k, <http://x> \\<http://y>]\n"]]]
      (is (= out (emit-src src)) src)
      (is (fixed-point? src) src)
      (is (= out (emit-src out)) out)
      ;; Not vacuous: the source still holds the text it would lose bare.
      (is (str/includes? (pr-str (parser/parse src)) "<") src)))
  (testing "TASK-203 AC #1: hand-built text, split over several `:str`s"
    (doseq [[inlines out] [[[(str-inline "<http://x>")] "\\<http://x>\n"]
                           [[(str-inline "a <") (str-inline "b>")] "a \\<b>\n"]
                           [[(str-inline "a <") {:t :emph :inlines [(str-inline "b")]} (str-inline ">")]
                            "a <*b*>\n"]
                           [[(str-inline "<me@x.org>")] "\\<me@x.org>\n"]
                           [[(str-inline "x") {:t :soft-break} (str-inline "<") (str-inline "div")]
                            "x\n\\<div\n"]]]
      (let [document {:meta {} :blocks [(apply para inlines)]}
            emitted (hdoc/emit-document document)]
        (is (= out emitted) (pr-str inlines))
        (is (= (parser/parse emitted) (parser/parse (emit-src emitted))) (pr-str inlines)))))
  (testing "TASK-203 AC #2: a `<` that can start none of them stays bare"
    (doseq [src ["a < b\n" "a <= b\n" "a <b c\n" "x <div\n" "x\n<svg\n" "x\n<foo y\n" "<\n" "1 <2 >3\n"
                 "a <!x\n" "a <? b\n" "a <http:// x>\n" "a <@x>\n" "a </ab:b>\n" "<ab:divx\n" "[a < b](u)\n"
                 "[@k, a < b]\n" "| a < b |\n| --- |\n| c |\n" "![a < b](u)\n" "`<b>` and $x<y$\n"]]
      (is (= src (emit-src src)) src)
      (is (fixed-point? src) src)))
  (testing "fuzz: every source built from `<` and what can follow it
            round-trips"
    (let [pieces ["a" " " "\n" "\\<" "\\<b" "\\<http:" "\\</b" "\\<!--" "\\<?" "\\<div" "<" ">" "http:"
                  "//x" "me@x.org" "b" "div" "/" "!--" "-->" "?" "?>" "!X" "=" "\"" "*e*" "`c`" "[l](u)"
                  "](u)" "[@k, " "]" "![i](u)" "{.c}"]
          rng (java.util.Random. 203)
          srcs (repeatedly 3000 #(str (apply str (repeatedly (inc (.nextInt rng 8))
                                                             (fn [] (nth pieces (.nextInt rng (count pieces))))))
                                      "\n"))
          parsed (keep (fn [src] (try [src (parser/parse src)] (catch Exception _ nil))) srcs)
          emitted (keep (fn [[src document]]
                          (try [src document (hdoc/emit-document document)]
                               (catch clojure.lang.ExceptionInfo _ nil)))
                        parsed)]
      (is (< 300 (count (filter (fn [[_ _ out]] (str/includes? out "\\<")) emitted)))
          "enough generated documents need a `\\<` that the fuzz is not vacuous")
      (is (< 1000 (count (filter (fn [[_ _ out]] (re-find #"(?<!\\)<" out)) emitted)))
          "and enough keep a bare `<`")
      (doseq [[src document out] emitted]
        (is (= document (parser/parse out)) (pr-str src))))))

(deftest figure-caption-escapes-over-whole-line-test
  (testing "TASK-206 AC #1: a figure caption's `<`, `{` and `!` are
            decided over the whole written image line, so a construct the
            title closes is escaped"
    (doseq [[src out] [["![x \\<!--](u \"-->\"){#fig:a}\n" "![x \\<!--](u \"-->\"){#fig:a}\n"]
                       ["![x \\<?](u \"?>\"){#fig:a}\n" "![x \\<?](u \"?>\"){#fig:a}\n"]
                       ["![x \\<!--](u \"-->\"){#fig:a .c k=v}\n" "![x \\<!--](u \"-->\"){#fig:a .c k=v}\n"]
                       ["![//x \\<!--](u \"--><{\"){#fig:a}\n" "![//x \\<!--](u \"--><{\"){#fig:a}\n"]
                       ["![*e* \\<!-- @k](u \"!! -->\"){#fig:a}\n" "![*e* \\<!-- @k](u \"!! -->\"){#fig:a}\n"]
                       ["![a \\<? b\nc](u \"d ?>\"){#fig:a}\n" "![a \\<? b\nc](u \"d ?>\"){#fig:a}\n"]
                       ;; `{` and `!`: escaped where the line needs it, bare
                       ;; where it does not, whatever the title holds.
                       ["![x \\{.c](u \"}\"){#fig:a}\n" "![x \\{.c](u \"}\"){#fig:a}\n"]
                       ["![x \\{](u \"}\"){#fig:a}\n" "![x {](u \"}\"){#fig:a}\n"]
                       ["![x \\{a](u \"}\"){#fig:a}\n" "![x {a](u \"}\"){#fig:a}\n"]
                       ["![x \\{#y}](u \"}\"){#fig:a}\n" "![x {\\#y}](u \"}\"){#fig:a}\n"]
                       ["![x !](u \"[\"){#fig:a}\n" "![x !](u \"[\"){#fig:a}\n"]
                       ["![x \\![l](v)](u){#fig:a}\n" "![x \\![l](v)](u){#fig:a}\n"]
                       ;; The caption no longer opens a physical line: `![`
                       ;; precedes it, so a needless escape goes.
                       ["![\\!x](u){#fig:a}\n" "![!x](u){#fig:a}\n"]
                       ["![\\<div](u){#fig:a}\n" "![<div](u){#fig:a}\n"]
                       ["![\\<?](u){#fig:a}\n" "![<?](u){#fig:a}\n"]]]
      (is (= out (emit-src src)) src)
      (is (fixed-point? src) src)
      (is (= out (emit-src out)) out)
      (is (= :figure (:t (first (:blocks (parser/parse src))))) src)))
  (testing "TASK-206 AC #1: hand-built captions, split over several `:str`s"
    (doseq [[caption title out]
            [[[(str-inline "x <!") (str-inline "--")] "-->" "![x \\<!--](u \"-->\"){#fig:a}\n"]
             [[(str-inline "x <?")] "?>" "![x \\<?](u \"?>\"){#fig:a}\n"]
             [[(str-inline "x <b")] ">" "![x <b](u \">\"){#fig:a}\n"]]]
      (let [document {:meta {} :blocks [{:t :figure
                                         :content (para {:t :image :src "u" :title title :alt "" :attr empty-attr})
                                         :caption caption
                                         :attr (assoc empty-attr :id "fig:a")}]}
            emitted (hdoc/emit-document document)]
        (is (= out emitted) (pr-str caption))
        (is (= (parser/parse emitted) (parser/parse (emit-src emitted))) (pr-str caption)))))
  (testing "TASK-206: a U+0000 in the destination, title or an attribute
            value is no escape mark -- it is written as U+FFFD, what
            re-parsing it would read anyway"
    (let [document {:meta {} :blocks [{:t :figure
                                       :content (para {:t :image :src "a\u0000b" :title "t\u0000"
                                                       :alt "" :attr (assoc empty-attr :props {"k" "v\u0000"})})
                                       :caption [(str-inline "x <")]
                                       :attr (assoc empty-attr :id "fig:a")}]}
          emitted (hdoc/emit-document document)]
      (is (not (str/includes? emitted "\u0000")) emitted)
      (is (= "![x <](a\uFFFDb \"t\uFFFD\"){#fig:a k=\"v\uFFFD\"}\n" emitted))))
  (testing "TASK-206 AC #2: figures without such constructs emit unchanged"
    (doseq [src ["![Cap](f.png){#fig:f}\n" "![Cap *e* \\{x}](f.png){#fig:f .w}\n"
                 "![A **b** `c` $x$ [l](u)](f.png \"Tip\"){#fig:f .w k=v}\n" "![a < b](u){#fig:a}\n"
                 "![a <b](u){#fig:a}\n" "![x !](u){#fig:a}\n" "![x {](u){#fig:a}\n" "![a@b \\@k](u){#fig:a}\n"
                 "![one\ntwo](u \"t\"){#fig:a}\n"]]
      (is (= src (emit-src src)) src)
      (is (fixed-point? src) src)))
  (testing "TASK-206: an image in a paragraph, and its alt text, were
            already decided over the whole block"
    (doseq [[src out] [["x ![x \\<!--](u \"-->\")\n" "x ![x <\\!--](u \"-->\")\n"]
                       ["x ![x \\<?](u \"?>\")\n" "x ![x \\<?](u \"?>\")\n"]
                       ["x ![x \\{](u \"}\"){.c}\n" "x ![x {](u \"}\"){.c}\n"]]]
      (is (= out (emit-src src)) src)
      (is (fixed-point? src) src)))
  (testing "TASK-206 (scope widened in review): every caption line after
            the first opens a physical line, so it gets a paragraph's
            line-start hazard escaping -- top level, in a list item and in
            a block quote; a code span's continuation line is left alone"
    (doseq [[src out] [["![a\n\\- b](u){#fig:a}\n" "![a\n\\- b](u){#fig:a}\n"]
                       ["![a\n\\+ b](u){#fig:a}\n" "![a\n\\+ b](u){#fig:a}\n"]
                       ["![a\n1\\. b](u){#fig:a}\n" "![a\n1\\. b](u){#fig:a}\n"]
                       ["![a\n2\\) b](u){#fig:a}\n" "![a\n2\\) b](u){#fig:a}\n"]
                       ["![a\n\\# b](u){#fig:a}\n" "![a\n\\# b](u){#fig:a}\n"]
                       ["![a\n\\> b](u){#fig:a}\n" "![a\n\\> b](u){#fig:a}\n"]
                       ["![a\n\\---\nc](u){#fig:a}\n" "![a\n\\---\nc](u){#fig:a}\n"]
                       ["![a\n\\|---|\nb](u){#fig:a}\n" "![a\n\\|---|\nb](u){#fig:a}\n"]
                       ["![a\n\\- b](u \"t\"){#fig:a .c}\n" "![a\n\\- b](u \"t\"){#fig:a .c}\n"]
                       ["- x\n\n  ![a\n  \\- b](u){#fig:a}\n" "- x\n\n  ![a\n  \\- b](u){#fig:a}\n"]
                       ["- ![a\n  1\\. b](u){#fig:a}\n" "- ![a\n  1\\. b](u){#fig:a}\n"]
                       ["> ![a\n> \\+ b](u){#fig:a}\n" "> ![a\n> \\+ b](u){#fig:a}\n"]
                       ["![a `x\n2. y`](u){#fig:a}\n" "![a `x\n2. y`](u){#fig:a}\n"]
                       ;; `!toc](u){#fig:a}` is no one-line directive: the
                       ;; escape the caption alone used to get is dropped.
                       ["![a\n\\!toc](u){#fig:a}\n" "![a\n!toc](u){#fig:a}\n"]
                       ["![a\n!toc](u){#fig:a}\n" "![a\n!toc](u){#fig:a}\n"]]]
      (is (= out (emit-src src)) src)
      (is (fixed-point? src) src)
      (is (= out (emit-src out)) out)
      (is (some #(= :figure (:t %)) (tree-seq coll? seq (parser/parse src))) src)))
  (testing "TASK-206 fuzz: figures whose captions -- over several lines,
            some opening with a list marker, `#`, `>` or `---` -- and
            titles mix `<`, `{`, `!`, `@`, `-->` and `?>` round-trip"
    (let [pieces ["a" " " "\n" "\\<" "\\<b" "\\<!--" "\\<?" "\\<div" "<" ">" "-->" "?>" "\\{" "\\{.c" "\\{#x"
                  "}" "=" "\\!" "!" "@k" "a@b" "\\@x" "*e*" "`c`" "[l](u)" "\"" "http:" "//x"
                  "\n\\- " "\n\\+ " "\n1\\. " "\n\\# " "\n\\> " "\n\\---" "\n!toc"]
          title-pieces ["a" " " "-->" "?>" ">" "}" "{" "<" "!" "@" "\\\"" "x"]
          rng (java.util.Random. 206)
          gen (fn [ps n] (apply str (repeatedly (inc (.nextInt rng (int n)))
                                                #(nth ps (.nextInt rng (count ps))))))
          srcs (repeatedly 3000 #(str "![" (gen pieces 7) "](u"
                                      (when (.nextBoolean rng) (str " \"" (gen title-pieces 5) "\"")) ")"
                                      (nth ["{#fig:a}" "{#fig:a .c k=v}"] (.nextInt rng 2)) "\n"))
          figures (keep (fn [src]
                          (let [document (try (parser/parse src) (catch Exception _ nil))]
                            (when (= :figure (:t (first (:blocks document))))
                              [src document (hdoc/emit-document document)])))
                        srcs)]
      (is (< 1500 (count figures)) "most generated sources parse as figures, so the fuzz is not vacuous")
      (is (< 50 (count (filter (fn [[_ document out]]
                                 (and (re-find #"\\<(?:!--|\?)" out)
                                      (re-find #"-->|\?>" (str (-> document :blocks first :content :inlines first :title)))))
                               figures)))
          "enough figures escape a `<` whose construct their title closes")
      (is (< 200 (count (filter (fn [[_ _ out]] (re-find #"\n(?:\\[-+#>]|1\\\.)" out)) figures)))
          "enough figures escape a marker opening a later caption line")
      (doseq [[src document out] figures]
        (is (= document (parser/parse out)) (pr-str src))))))

(deftest literal-brace-round-trip-test
  (testing "TASK-161 AC #1/#3: a literal `{x}` straight after each inline
            is written escaped and re-parses as the same text, not as an
            attribute group attached to that inline"
    (doseq [[inline src] [["book ref" "@{a}\\{x}\n"]
                          ["cross-reference" "@sec:a\\{x}\n"]
                          ["inline code" "`c`\\{x}\n"]
                          ["inline math" "$m$\\{x}\n"]
                          ["link" "[l](u)\\{x}\n"]
                          ["emphasis" "*a*\\{x}\n"]
                          ["strong" "**a**\\{x}\n"]
                          ["image" "![i](u)\\{x}\n"]
                          ["span" "[s]{.c}\\{x}\n"]
                          ["highlight" "==h==\\{x}\n"]
                          ["strike" "~~s~~\\{x}\n"]
                          ["citation" "[@doe]\\{x}\n"]
                          ;; TASK-175: a citation-shaped bracket with a
                          ;; group straight after it is a Span.
                          ["span of a citation" "[@doe]{.c}\\{x}\n"]
                          ["span of a citation group" "[see @doe, p. 4]{.c}\\{x}\n"]
                          ["span around a citation" "[[@doe]]{.c}\\{x}\n"]]]
      (is (= src (emit-src src)) inline)
      (is (fixed-point? src) inline)))
  (testing "TASK-175 AC #2: the citation-shaped shapes around an escaped
            brace round-trip, including the one the writer makes of an
            undefined attributed image reference holding a citation"
    (doseq [src ["[@doe] \\{x}\n" "![@doe][s]{.c}\\{x}\n" "# See [@doe]{#sec:x}\n"
                 "| a |\n|---|\n| b |\n: See [@doe]{#tbl:x}\n"]]
      (is (fixed-point? src) src))
    (is (= "[!\\[@doe\\]\\[s\\]]{.c}\\{x}\n" (emit-src "![@doe][s]{.c}\\{x}\n"))))
  (testing "flexmark reads `{x}` as a group wherever it sits, not only after
            an inline, and its whitespace runs across spaces and a line
            break -- so a brace is escaped wherever it would otherwise open
            one, in every place inline text is written"
    (doseq [src ["a \\{x} b\n" "\\{x y}\n" "a \\{x\ny} b\n" "# h \\{x}\n" "- \\{x}\n"
                 "> \\{x\n> y}\n" "[a \\{x}](u)\n" "![\\{x}](u)\n" "| a |\n| --- |\n| \\{x} |\n"
                 "x[^1]\n\n[^1]: \\{x}\n" "[see \\{x} @doe]\n" "a \\{ x } b\n"
                 ;; The group runs through the emphasis's own markup.
                 "\\{x .a*b*}\n"]]
      (is (= src (emit-src src)) src)
      (is (fixed-point? src) src)))
  (testing "a hand-built `{x}` text run after an inline, which the parser
            never splits this way, still comes out escaped"
    (doseq [inline [{:t :code :text "c"} {:t :math-inline :tex "m"} {:t :cross-ref :label "sec:a"}
                    {:t :book-ref :ref "a"} {:t :link :target "u" :inlines [(str-inline "l")] :attr empty-attr}]]
      (let [doc {:meta {} :blocks [(para inline (str-inline "{x}"))]}
            out (hdoc/emit-document doc)]
        (is (str/ends-with? out "\\{x}\n") out)
        (is (= [(str-inline "{x}")] (rest (:inlines (first (:blocks (parser/parse out)))))) out))))
  (testing "AC #2: a `{` that would not open a group gains no backslash"
    (doseq [src ["a {} b\n" "a {1} b\n" "a {x,y} b\n" "a \\{x\n" "*a \\{x*\n" "*a \\{x* y}\n"
                 "`c` {1}\n" "a \\{x *y*} b\n" "set {x/y} here\n" "a \\{x `c`}\n"]]
      (let [out (emit-src src)]
        (is (= (str/replace src "\\{" "{") out) src)
        (is (fixed-point? src) src))))
  (let [run (apply str (repeat 20000 " y"))]
    (testing "TASK-172: a `{x` before a run far too long for flexmark's
              regex, with no `}` to close it, is judged by the stackless
              matcher like a short one -- prose, so no needless backslash"
      (let [src (str "a {x" run "\n")]
        (is (= src (emit-src src)))
        (is (fixed-point? src))))
    (testing "TASK-172: the same run that a `}` does close still gets its
              `\\{`, so the written source re-parses as text rather than
              raising `::attribute-group-too-long`"
      (let [src (str "a \\{x" run "} b\n")]
        (is (= src (emit-src src)))
        (is (fixed-point? src)))
      (let [out (hdoc/emit-document {:meta {} :blocks [(para (str-inline (str "a {x" run "} b")))]})]
        (is (str/starts-with? out "a \\{x y"))
        (is (= (str "a {x" run "} b")
               (apply str (map #(case (:t %) :str (:text %) :space " ")
                               (:inlines (first (:blocks (parser/parse out)))))))))))
  (testing "a U+0000 in a hand-built node is written as U+FFFD, what
            CommonMark reads it as, and never mistaken for the emitter's
            own brace mark -- raw code and TeX keep every other character"
    (let [nul (str (char 0))
          fffd (str (char 0xFFFD))]
      (doseq [[inline expected] [[{:t :code :text (str nul "{x}")} {:t :code :text (str fffd "{x}")}]
                                 [{:t :str :text (str nul "{x}")} {:t :str :text (str fffd "{x}")}]
                                 [{:t :math-inline :tex (str "a" nul "b")}
                                  {:t :math-inline :tex (str "a" fffd "b")}]]]
        (let [out (hdoc/emit-document {:meta {} :blocks [(para inline)]})]
          (is (= [expected] (:inlines (first (:blocks (parser/parse out))))) (pr-str out)))))))

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

(deftest heading-line-break-round-trip-test
  (testing "TASK-174 AC #1: a heading holding a hard or soft line break is
            written in setext form and re-parses to the same heading,
            attribute group included, rather than as an ATX heading whose
            second line becomes a paragraph"
    (doseq [[src out] [["Setext\\\nline\n---\n" "Setext\\\nline\n---\n"]
                       ["Setext  \nline\n---\n" "Setext\\\nline\n---\n"]
                       ["Setext\nline\n---\n" "Setext\nline\n---\n"]
                       ["Setext\nline {#a .b}\n===\n\nBody.\n" "Setext\nline {#a .b}\n===\n\nBody.\n"]
                       ["*a\nb*\n---\n" "*a\nb*\n---\n"]
                       ["a[^1]\nb\n===\n\n[^1]: n\n" "a[^1]\nb\n===\n\n[^1]: n\n"]
                       ["- Setext\\\n  line\n  ---\n" "- Setext\\\n  line\n  ---\n"]
                       ["> Setext\\\n> line\n> ---\n" "> Setext\\\n> line\n> ---\n"]
                       ["one\ntwo\n===\n\nthree\nfour\n---\n\n### five\n"
                        "one\ntwo\n===\n\nthree\nfour\n---\n\n### five\n"]]]
      (is (= out (hdoc/emit-document (parser/parse src))) (pr-str src))
      (is (fixed-point? src) (pr-str src))))
  (testing "setext text starts at column 0, so a heading line shaped like a
            block opener or an underline is escaped as a paragraph's is"
    (doseq [src ["a\n\\---\n---\n" "\\> q\nb\n---\n" "\\::: d\nb\n===\n" "\\# h\nb\n---\n"
                 "\\- x\nb\n===\n" "1\\. x\nb\n===\n" "a\n\\=\n===\n"]]
      (is (fixed-point? src) (pr-str src))))
  (testing "fuzz: every setext heading built from line-start-hazard pieces
            round-trips"
    (let [pieces ["a" "b c" " " "\n" "\\\n" "  \n" "-" "---" "=" "# h" "> q" "::: d" "- i" "1. i"
                  "*e*" "[l](u)" "`c`" "[^1]" "{#x}" " {#y .z}" "\\-" "!"]
          rng (java.util.Random. 174)
          srcs (repeatedly 2000 #(str (apply str (repeatedly (inc (.nextInt rng 6))
                                                             (fn [] (nth pieces (.nextInt rng (count pieces))))))
                                      (nth ["\n---\n" "\n===\n"] (.nextInt rng 2))
                                      "\n[^1]: n\n"))
          ;; `Exception`, not only `ExceptionInfo`: a source opening with
          ;; `---` can reach front-matter parsing and fail there with an
          ;; IllegalArgumentException, a parser gap of its own.
          parsed (keep (fn [src] (try [src (parser/parse src)] (catch Exception _ nil))) srcs)
          spans-lines? (fn [block] (some #(#{:soft-break :line-break} (:t %)) (:heading block)))]
      (is (< 50 (count (filter (fn [[_ document]] (some spans-lines? (:blocks document))) parsed)))
          "enough generated headings span lines that the fuzz is not vacuous")
      (doseq [[src document] parsed]
        (is (= document (parser/parse (hdoc/emit-document document))) (pr-str src)))))
  (testing "a level 3-6 heading cannot span lines in any spelling, so one
            holding a break throws rather than writing something else"
    (doseq [level [3 6]
            break [{:t :soft-break} {:t :line-break}]]
      (let [document {:meta {}
                      :blocks [{:t :section :level level :attr empty-attr :blocks []
                                :heading [(str-inline "a") break (str-inline "b")]}]}
            e (try (hdoc/emit-document document) nil (catch clojure.lang.ExceptionInfo e e))]
        (is (= {:type ::hdoc/unrepresentable-heading-break :level level} (ex-data e))))))
  (testing "a heading whose lines include a blank one -- a break at its
            start or end, or two in a row -- throws rather than ending the
            setext heading early or (with an attribute group filling the
            blank line) dropping the break"
    (let [a (str-inline "a")
          b (str-inline "b")
          soft {:t :soft-break}
          hard {:t :line-break}]
      (doseq [level [1 2]
              [heading attr] [[[a hard] empty-attr]
                              [[a soft] empty-attr]
                              [[soft] empty-attr]
                              [[soft a] empty-attr]
                              [[a soft soft b] empty-attr]
                              [[a soft] (assoc empty-attr :id "x")]
                              [[soft] (assoc empty-attr :id "x")]
                              [[a hard soft b] (assoc empty-attr :id "x")]]]
        (let [document {:meta {}
                        :blocks [{:t :section :level level :attr attr :blocks [] :heading heading}]}
              e (try (hdoc/emit-document document) nil (catch clojure.lang.ExceptionInfo e e))]
          (is (= {:type ::hdoc/unrepresentable-heading-break :level level} (ex-data e))
              (pr-str level heading attr))))))
  (testing "a hard break never leaves a blank line before the last (its `\\`
            stays on its own line), and a trailing one re-parses when the
            attribute group fills the last line -- the parser produces
            exactly these headings"
    (doseq [src ["\\\na\n---\n" "a\\\n\\\nb\n---\n" "a\n\\\nb\n===\n"
                 "a\\\n{#x}\n---\n" "a\\\n\\\n{#x}\n---\n" "\\\n{#x}\n===\n"]]
      (is (fixed-point? src) (pr-str src))))
  (testing "AC #2: a heading without a break is still written in ATX form"
    (is (= "# One {#a}\n\n## Two\n\n###### Six\n"
           (hdoc/emit-document (parser/parse "One {#a}\n===\n\nTwo\n---\n\n###### Six\n"))))))

(deftest heading-ending-in-attributed-inline-test
  (let [c-attr (assoc empty-attr :classes ["c"])
        x [(str-inline "x")]
        span {:t :span :inlines [(str-inline "s")] :attr c-attr}
        cite-span (fn [mode] (assoc span :inlines [{:t :cite :items [{:key "doe" :mode mode}]}]))
        ;; every Inline the writer spells with a trailing `{...}` group,
        ;; each carrying an Attr that round-trips exactly
        exact [span (cite-span :author) (cite-span :normal)
               (assoc span :inlines [{:t :code :text "c"}])
               {:t :link :target "u" :inlines x :attr c-attr}
               {:t :image :src "u" :alt "x" :attr c-attr}
               {:t :highlight :inlines x :attr c-attr}]
        ;; the role spans, always `[x]{.sub}`: kind-lossy by design, they
        ;; re-parse as a `:span` with their class
        roles (for [t [:small-caps :sub :sup]] {:t t :inlines x})
        role-as-span (fn [role] {:t :span :inlines x :attr (assoc empty-attr :classes [(name (:t role))])})
        own-attr (assoc empty-attr :id "x")
        document (fn [level heading attr]
                   {:meta {} :blocks [{:t :section :level level :attr attr :blocks [] :heading heading}]})
        thrown (fn [d] (try (hdoc/emit-document d) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))]
    (testing "TASK-183 AC #1: a heading with its own attribute group that ends
              in an attributed inline (a span -- plain, holding a citation in
              either mode, or around code -- a link, an image, a highlight)
              re-parses to the same heading and inline, ATX at every level
              and setext"
      (doseq [level (range 1 7)
              last-inline exact
              heading [[(str-inline "a") {:t :space} last-inline]
                       [last-inline]]]
        (let [d (document level heading own-attr)]
          (is (= d (parser/parse (hdoc/emit-document d))) (pr-str level heading))))
      (doseq [level [1 2]
              break [{:t :soft-break} {:t :line-break}]
              last-inline exact]
        (let [d (document level [(str-inline "a") break (str-inline "b") {:t :space} last-inline] own-attr)
              out (hdoc/emit-document d)]
          (is (str/ends-with? out (str "{.c} {#x}\n" (if (= 1 level) "===" "---") "\n")) out)
          (is (= d (parser/parse out)) out)))
      (is (= "# a [s]{.c} {#x}\n" (hdoc/emit-document (document 1 [(str-inline "a") {:t :space} span]
                                                                own-attr))))
      (is (fixed-point? "# See [@doe]{.c} {#x}\n"))
      (is (fixed-point? "## See [[@doe]]{.c} {#x .y}\n"))
      (doseq [src ["# a [x](u){.c} {#h}\n" "# a ![x](u){.c} {#h}\n" "# a ==x=={.c} {#h}\n"
                   "# a `x`{.c} {#h}\n"]]
        (is (fixed-point? src) (pr-str src))))
    (testing "a role span ending a heading with its own group keeps its
              content and class, re-parsing as the `:span` it always does"
      (doseq [role roles]
        (let [d (document 3 [(str-inline "a") {:t :space} role] own-attr)]
          (is (= (document 3 [(str-inline "a") {:t :space} (role-as-span role)] own-attr)
                 (parser/parse (hdoc/emit-document d)))
              (pr-str role)))))
    (testing "TASK-183 AC #1: with no attribute group of its own no spelling
              keeps the inline's group on the inline, so the write throws
              (naming the level and the inline's :t) rather than re-parsing
              as the heading's own Attr"
      (doseq [level (range 1 7)
              last-inline (concat exact roles)]
        (is (= {:type ::hdoc/unrepresentable-heading-attr-inline :level level :t (:t last-inline)}
               (thrown (document level [(str-inline "a") {:t :space} last-inline] empty-attr)))
            (pr-str level last-inline)))
      (doseq [last-inline exact
              tail [[{:t :space}] [(str-inline "  ")] [(str-inline "")]
                    [{:t :space} (str-inline "") {:t :space}]]]
        (is (= {:type ::hdoc/unrepresentable-heading-attr-inline :level 1 :t (:t last-inline)}
               (thrown (document 1 (into [(str-inline "a") {:t :space} last-inline] tail) empty-attr)))
            (pr-str last-inline tail)))
      (doseq [last-inline exact]
        (is (= {:type ::hdoc/unrepresentable-heading-attr-inline :level 2 :t (:t last-inline)}
               (thrown (document 2 [(str-inline "a") {:t :soft-break} last-inline] empty-attr)))
            (pr-str last-inline))))
    (testing "AC #2: a heading ending in an inline WITHOUT an attribute group
              is written and re-parsed as before"
      (doseq [last-inline [{:t :link :target "u" :inlines x :attr empty-attr}
                           {:t :image :src "u" :alt "x" :attr empty-attr}
                           {:t :highlight :inlines x :attr empty-attr}
                           {:t :strike :inlines x}
                           {:t :code :text "c"}
                           {:t :book-ref :ref "Rom 1:1"}
                           {:t :cite :items [{:key "doe" :mode :normal}]}]
              attr [empty-attr own-attr]]
        (let [d (document 1 [(str-inline "a") {:t :space} last-inline] attr)]
          (is (= d (parser/parse (hdoc/emit-document d))) (pr-str last-inline attr))))
      (is (= "# a [x](u)\n\n## a ==x== {#x}\n"
             (hdoc/emit-document
              {:meta {}
               :blocks [{:t :section :level 1 :attr empty-attr
                         :heading [(str-inline "a") {:t :space} {:t :link :target "u" :inlines x :attr empty-attr}]
                         :blocks [{:t :section :level 2 :attr own-attr :blocks []
                                   :heading [(str-inline "a") {:t :space}
                                             {:t :highlight :inlines x :attr empty-attr}]}]}]}))))
    (testing "AC #2: a heading whose attributed inline is not last, or that ends
              in anything else, is written as before"
      (doseq [[src out] [["# a [s]{.c} b\n" "# a [s]{.c} b\n"]
                         ["# See [@doe]{.c} more\n" "# See [@doe]{.c} more\n"]
                         ["# a [s]{.c}\n" "# a \\[s\\] {.c}\n"]
                         ["# Title {#sec:x}\n" "# Title {#sec:x}\n"]
                         ["# See [@doe]{#sec:x}\n" "# See [@doe] {#sec:x}\n"]]]
        (is (= out (hdoc/emit-document (parser/parse src))) (pr-str src))
        (is (fixed-point? src) (pr-str src))))))

(deftest heading-ending-in-literal-brace-group-test
  (let [document (fn [level heading attr]
                   {:meta {} :blocks [{:t :section :level level :attr attr :blocks [] :heading heading}]})
        own-attr (assoc empty-attr :id "h")
        ;; literal text shaped like a group, closed or not, and how it is
        ;; written: the `{` escaped, except behind a `#`, which is always
        ;; escaped itself and so leaves the brace opening nothing
        written {"{.c}" "\\{.c}" "{#x}" "{\\#x}" "{k=v}" "\\{k=v}"
                 "{.c" "\\{.c" "{#x" "{\\#x" "{k=v" "\\{k=v"}
        groups (keys written)]
    (testing "TASK-189 AC #1: a heading whose text ends in a literal
              attribute-like brace group is written with the brace
              escaped and re-parses to the same heading, ATX at every
              level, with and without its own group"
      (doseq [level (range 1 7)
              text groups
              attr [empty-attr own-attr]]
        (let [d (document level [(str-inline "a") space (str-inline text)] attr)
              out (hdoc/emit-document d)]
          (is (= (str (apply str (repeat level "#")) " a " (written text)
                      (when (seq (:id attr)) " {#h}") "\n")
                 out))
          (is (= d (parser/parse out)) out)))
      (is (= "# a \\{.c}\n" (hdoc/emit-document (document 1 [(str-inline "a") space (str-inline "{.c}")] empty-attr))))
      (is (= "### a \\{.c} {#h}\n"
             (hdoc/emit-document (document 3 [(str-inline "a") space (str-inline "{.c}")] own-attr)))))
    (testing "TASK-189 AC #1: and as a setext heading, the group ending its
              last line"
      (doseq [level [1 2]
              break [{:t :soft-break} {:t :line-break}]
              text groups
              attr [empty-attr own-attr]]
        (let [d (document level [(str-inline "a") break (str-inline "b") space (str-inline text)] attr)
              out (hdoc/emit-document d)]
          (is (str/ends-with? out (str " " (written text) (when (seq (:id attr)) " {#h}") "\n"
                                       (if (= 1 level) "===" "---") "\n"))
              out)
          (is (= d (parser/parse out)) out))))
    (testing "TASK-189: the escaped source is a fixed point, in a heading
              and in a paragraph"
      (doseq [src ["# a \\{.c}\n" "## a \\{.c} {#h}\n" "a\\\nb \\{.c}\n===\n"
                   "a \\{.c}\n" "a \\{#x} b \\{k=v\n"]]
        (is (fixed-point? src) (pr-str src))))))

(deftest lossy-role-span-test
  (testing "AC #7: :small-caps/:sub/:sup are written as the role
            span an author would use ([text]{.kind}), and re-parsing
            that spelling yields a :span, never the original tag"
    (doseq [t [:small-caps :sub :sup]]
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
                              :inlines [{:t :small-caps :inlines [(str-inline "a")]}
                                        {:t :sup :inlines [(str-inline "b")]}]}]}
          diagnostics (hdoc/lossy-role-span-diagnostics document)]
      (is (= [{:type ::hdoc/lossy-role-span :t :small-caps :path [:blocks 0 :inlines 0]}
              {:type ::hdoc/lossy-role-span :t :sup :path [:blocks 0 :inlines 1]}]
             diagnostics))))
  (testing "TASK-137: a :strike is NOT lossy -- it round-trips as ~~...~~ --
            except one nested inside another, which is reported"
    (let [plain {:meta {} :blocks [{:t :para :inlines [{:t :strike :inlines [(str-inline "a")]}]}]}
          nested {:meta {} :blocks [{:t :para
                                     :inlines [{:t :strike
                                                :inlines [{:t :strike :inlines [(str-inline "a")]}]}]}]}]
      (is (empty? (hdoc/lossy-role-span-diagnostics plain)))
      (is (= [{:type ::hdoc/lossy-role-span :t :strike :path [:blocks 0 :inlines 0]}]
             (hdoc/lossy-role-span-diagnostics nested)))
      (is (= plain (parser/parse (hdoc/emit-document plain))))
      (is (str/includes? (hdoc/emit-document plain) "~~a~~"))
      (is (str/includes? (hdoc/emit-document nested) "[a]{.strike}"))))
  (testing "a document with none of the four reports no diagnostics"
    (let [document (parser/parse "Ordinary *emph* text.\n")]
      (is (empty? (hdoc/lossy-role-span-diagnostics document))))))

(deftest strike-round-trips-exactly-test
  (testing "TASK-137 AC #1: ~~x~~ is a fixed point through --target hdoc"
    (doseq [src ["Hazelnuts ~~ripen in early autumn~~ and drop.\n"
                 "~~A whole paragraph is struck.~~\n"
                 "a ~~b~~ c ~~d~~ e\n"
                 "*emph with ~~strike~~ inside it*\n"
                 "~~strike with *emph*, `code` and $x^2$~~\n"
                 "~~a ==highlight== inside~~ and ==~~strike~~ in a highlight==\n"
                 "A literal \\~~ pair and a lone ~ tilde and ~/path.\n"]]
      (testing (pr-str src)
        (is (true? (fixed-point? src))))))
  (testing "a struck run whose text begins or ends with ~ keeps its edges"
    (doseq [text ["~" "~a" "a~" "~a~" "\\~"]]
      (let [document {:meta {} :blocks [{:t :para :inlines [{:t :strike :inlines [(str-inline text)]}]}]}]
        (testing (pr-str text)
          (is (= document (parser/parse (hdoc/emit-document document)))))))))

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
      (is (= ::hdoc/unrepresentable-destination (:type (ex-data e))))))
  (let [link-doc (fn [target]
                   {:meta {}
                    :blocks [(para {:t :link :target target :inlines [(str-inline "a")] :attr empty-attr})]})]
    (testing "TASK-204 AC #1: a `\\` the parser would read as an escape --
              before ASCII punctuation, or last, before the closing `)` or
              `>` -- is doubled in either destination form; one before
              anything else stays single, and every one reads back"
      (doseq [[target out] [["ab:=\\[" "[a](ab:=\\\\[)\n"]
                            ["a\\" "[a](a\\\\)\n"]
                            ["a\\\\" "[a](a\\\\\\\\)\n"]
                            ["a\\(b)" "[a](a\\\\(b))\n"]
                            ["a\\b" "[a](a\\b)\n"]
                            ["a\\é" "[a](a\\é)\n"]
                            ["a\\)" "[a](<a\\\\)>)\n"]
                            ["a\\<" "[a](<a\\\\\\<>)\n"]
                            ["x)\\" "[a](<x)\\\\>)\n"]
                            ["a\\b)" "[a](<a\\b)>)\n"]]]
        (let [document (link-doc target)
              emitted (hdoc/emit-document document)]
          (is (= out emitted) (pr-str target))
          (is (= document (parser/parse emitted)) (pr-str target)))))
    (testing "TASK-204 AC #2: destinations without a backslash emit as
              before"
      (doseq [[target out] [["http://x.com/a?b=c&d=[e]" "[a](http://x.com/a?b=c&d=[e])\n"]
                            ["http://en.wikipedia.org/wiki/Hazel_(plant)"
                             "[a](http://en.wikipedia.org/wiki/Hazel_(plant))\n"]
                            ["http://x.com/y)z" "[a](<http://x.com/y)z>)\n"]
                            ["a<b" "[a](<a\\<b>)\n"]]]
        (is (= out (hdoc/emit-document (link-doc target))) (pr-str target)))))
  (testing "TASK-204: an image, a figure and a reference-style link with a
            backslash in the destination or the title read back the same,
            while a `<uri>` autolink in link text keeps its `\\` verbatim
            (no escape processing there)"
    (doseq [src ["x ![i](p\\\\[ \"t\\\\\")\n"
                 "![Cap](f\\\\[.png \"Tip\"){#fig:f}\n"
                 "[r]\n\n[r]: a\\\\[ \"t\\\"\"\n"
                 "<ab:=\\[>\n"
                 "[a <ab:=\\[>](u\\\\[)\n"]]
      (is (fixed-point? src) src))
    (is (= "[a <ab:=\\[>](u\\\\[)\n" (emit-src "[a <ab:=\\[>](u\\\\[)\n"))))
  (testing "TASK-204 fuzz: links, images and figures whose destination and
            title mix backslashes, punctuation and parens round-trip"
    (let [pieces ["a" "é" "\\" "\\" "(" ")" "[" "]" "<" ">" "=" ":" "\"" "'" "&" "*" "`" "{" "}" "#" "!" "/"]
          rng (java.util.Random. 204)
          gen (fn [] (apply str (repeatedly (inc (.nextInt rng 7))
                                            #(nth pieces (.nextInt rng (count pieces))))))
          node (fn [t target title]
                 (cond-> (if (= t :link)
                           {:t :link :target target :inlines [(str-inline "a")] :attr empty-attr}
                           {:t :image :src target :alt "a" :attr empty-attr})
                   title (assoc :title title)))
          documents (repeatedly
                     2000
                     (fn []
                       (let [target (gen)
                             title (when (.nextBoolean rng) (gen))
                             block (case (.nextInt rng 3)
                                     0 (para (str-inline "x") space (node :link target title))
                                     1 (para (str-inline "x") space (node :image target title))
                                     2 {:t :figure :content (para (node :image target title))
                                        :caption [(str-inline "a")] :attr (assoc empty-attr :id "fig:f")})]
                         {:meta {} :blocks [block]})))]
      (is (< 500 (count (filter #(re-find #"\\\p{Punct}|\\$"
                                          (let [b (first (:blocks %))
                                                n (if (= :figure (:t b))
                                                    (-> b :content :inlines first)
                                                    (last (:inlines b)))]
                                            (or (:target n) (:src n))))
                                documents)))
          "enough destinations hold a backslash the parser would read as an escape that the fuzz is not vacuous")
      (doseq [document documents]
        (let [out (hdoc/emit-document document)]
          (is (= document (parser/parse out)) out))))))

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

(deftest attribute-backslash-test
  (letfn [(doc-with [attr]
            {:meta {}
             :blocks [{:t :directive :name "note" :attr attr :blocks []}
                      {:t :section :level 1 :heading [(str-inline "T")] :blocks [] :attr attr}]})]
    (testing "TASK-197: a value holding a backslash is quoted, since the
              parser refuses one in an unquoted value, and round-trips"
      (doseq [v ["a\\b" "v\\" "\\"]]
        (let [document (doc-with {:classes [] :props {"k" v}})]
          (is (= document (parser/parse (hdoc/emit-document document))) (pr-str v)))))
    (testing "an id, class or key holding one has no spelling the parser
              reads back, and is refused rather than written"
      (doseq [attr [{:id "x\\" :classes [] :props {}}
                    {:classes ["c\\d"] :props {}}
                    {:classes [] :props {"k\\" "v"}}]]
        (let [e (is (thrown? clojure.lang.ExceptionInfo (hdoc/emit-document (doc-with attr))) (pr-str attr))]
          (is (= ::hdoc/unrepresentable-attr-token (:type (ex-data e))) (pr-str attr)))))))

(defn- attr-value-contexts
  "One document per place an attribute group is written (TASK-205), each
  carrying `attr`: the flexmark-read heading, span, link and image, and
  the header-tokenizer-read `:::` fence, `!name{...}` line and
  display-math closing fence."
  [attr]
  {:heading {:meta {} :blocks [{:t :section :level 1 :heading [(str-inline "T")] :blocks [] :attr attr}]}
   :span {:meta {} :blocks [(para (str-inline "a") space {:t :span :inlines [(str-inline "b")] :attr attr})]}
   :link {:meta {} :blocks [(para (str-inline "a") space
                                  {:t :link :target "u" :inlines [(str-inline "b")] :attr attr})]}
   :image {:meta {} :blocks [(para (str-inline "a") space {:t :image :src "u.png" :alt "b" :attr attr})]}
   :fence {:meta {} :blocks [{:t :directive :name "note" :attr attr :blocks [(para (str-inline "x"))]}]}
   :bang {:meta {} :blocks [{:t :directive :name "toc" :attr attr :blocks []}]}
   :math {:meta {} :blocks [{:t :math-block :tex "x" :attr attr}]}})

(defn- code-points
  "The string of code points `cps`: keeps non-ASCII and invisible
  characters in these fixtures readable in the source."
  [& cps]
  (apply str (map #(String. (Character/toChars (int %))) cps)))

(deftest attribute-value-quoting-test
  (let [nbsp (code-points 0xa0) em-space (code-points 0x2003)]
    (testing "TASK-205 AC #1: a value holding a character flexmark's
              attribute grammar rejects bare (`\"'=<>{}``), whitespace the
              header tokenizer splits on, or nothing at all is quoted and
              reads back in every context"
      (doseq [[v token] [[">" "k=\">\"}"] ["a=b" "k=\"a=b\"}"] ["<a" "k=\"<a\"}"]
                         ["a`b" "k=\"a`b\"}"] ["{" "k=\"{\"}"] ["a}b" "k=\"a}b\"}"]
                         ["a'b" "k=\"a'b\"}"] ["a\"b" "k='a\"b'}"] ["" "k=\"\"}"]
                         [(str "a" nbsp "b") (str "k=\"a" nbsp "b\"}")]
                         [(str "a" em-space "b") (str "k=\"a" em-space "b\"}")]]
              [context document] (attr-value-contexts {:classes [] :props {"k" v}})
              :when (not (and (= context :math) (str/includes? v "}")))]
        (let [out (hdoc/emit-document document)]
          (is (str/includes? out token) (pr-str context v out))
          (is (= document (parser/parse out)) (pr-str context v out)))))
    (testing "TASK-205 AC #2: a value that needs no quotes is written bare,
              exactly as before"
      (doseq [v ["a" "3cm" "(1)" "a.b-c:d/e#f_g" "x!?*+,;@$%&|~^[]"
                 (code-points 0xca 0x78) (code-points 0x4e2d 0x6587) (code-points 0x65 0x301)]
              [context document] (attr-value-contexts {:classes [] :props {"k" v}})]
        (let [out (hdoc/emit-document document)]
          (is (str/includes? out (str "k=" v "}")) (pr-str context v out))
          (is (= document (parser/parse out)) (pr-str context v out))))))
  (testing "TASK-205 fuzz: values over all ASCII punctuation, spaces and
            non-ASCII read back in every context; a value holding both
            quote kinds is refused as before. Left out of the alphabet:
            line terminators (no line-bound group can hold one) and, in
            a display-math group only, `}` (`math-single-line-re`'s
            `[^}]*`) -- values no spelling reads back, not quoting gaps"
    (let [pieces (into (mapv str "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~")
                       (concat ["a" "Z" "0" " " "  " "\t"]
                               (map code-points [0xe9 0x4e2d 0xa0 0x2003 0x3000 0x200b 0x7f 0x1f600])
                               [(code-points 0x65 0x301)]))
          rng (java.util.Random. 205)
          gen (fn [] (apply str (repeatedly (.nextInt rng 6) #(nth pieces (.nextInt rng (count pieces))))))
          results (for [_ (range 3000)
                        :let [v (gen)]
                        [context document] (attr-value-contexts {:classes [] :props {"k" v}})
                        :when (not (and (= context :math) (str/includes? v "}")))]
                    [context v document])]
      (is (< 1000 (count (filter (fn [[_ v]] (re-find #"[\"'=<>{}`]" v)) results)))
          "enough values hold a character flexmark refuses bare that the fuzz is not vacuous")
      (doseq [[context v document] results]
        (if (and (str/includes? v "\"") (str/includes? v "'"))
          (let [e (is (thrown? clojure.lang.ExceptionInfo (hdoc/emit-document document)) (pr-str context v))]
            (is (= ::hdoc/unrepresentable-attr-value (:type (ex-data e))) (pr-str context v)))
          (let [out (hdoc/emit-document document)]
            (is (= document (parser/parse out)) (pr-str context v out))))))))

(defn- thrown-attr-value
  "The `ex-data` of the `::hdoc/unrepresentable-attr-value` that emitting
  `document` throws, or nil when it throws anything else or nothing."
  [document]
  (try (hdoc/emit-document document) nil
       (catch clojure.lang.ExceptionInfo e
         (when (= ::hdoc/unrepresentable-attr-value (:type (ex-data e)))
           (assoc (ex-data e) :message (ex-message e))))))

(deftest attribute-value-context-refusal-test
  (let [terminators (code-points 0x0a 0x0d 0x85 0x2028 0x2029)]
    (testing "TASK-221: a line terminator in a `:::` header, a `!name{...}`
              line or a display-math group is refused naming the context"
      (doseq [t (map str terminators)
              :let [documents (attr-value-contexts {:classes [] :props {"k" (str "a" t "b")}})]
              [context fragment] [[:fence "`:::` fence header"] [:bang "`!name{...}` line"]
                                  [:math "display-math closing fence"]]]
        (let [data (thrown-attr-value (documents context))]
          (is (some? data) (pr-str context t))
          (is (str/includes? (str (:message data)) fragment) (pr-str context t (:message data))))))
    (testing "and in an id, a class or a key, which no quoting reaches"
      (doseq [attr [{:id "a\nb" :classes [] :props {}}
                    {:classes [(str "a" (code-points 0x2028))] :props {}}
                    {:classes [] :props {(str "k" (code-points 0x85)) "v"}}]
              context [:fence :bang :math]]
        (is (some? (thrown-attr-value ((attr-value-contexts attr) context))) (pr-str context attr))))
    (testing "a heading's group refuses `\\n` and `\\r`"
      (doseq [t ["\n" "\r"]]
        (let [data (thrown-attr-value (:heading (attr-value-contexts {:classes [] :props {"k" (str "a" t "b")}})))]
          (is (some? data) (pr-str t))
          (is (str/includes? (str (:message data)) "heading") (pr-str t)))))
    (testing "a `}` in a display-math group is refused, however it is quoted"
      (doseq [attr [{:classes [] :props {"k" "a}b"}} {:classes [] :props {"k" "}"}}]]
        (let [data (thrown-attr-value (:math (attr-value-contexts attr)))]
          (is (some? data) (pr-str attr))
          (is (str/includes? (str (:message data)) "display-math") (pr-str attr)))))
    (testing "the same values elsewhere keep working: `}` in the other
              contexts, a line terminator nowhere it was written before"
      (doseq [context [:heading :span :link :image :fence :bang]]
        (let [document ((attr-value-contexts {:classes [] :props {"k" "a}b"}}) context)]
          (is (= document (parser/parse (hdoc/emit-document document))) (pr-str context)))))))

(defn- table-cell-doc
  "A table document whose one body cell holds `inlines`."
  [inlines]
  (let [table (first (:blocks (parser/parse "| h |\n|---|\n| x |\n")))]
    {:meta {}
     :blocks [(assoc-in table [:rows 0 :cells 0 :blocks] [(apply para inlines)])]}))

(deftest attribute-group-pipe-in-table-cell-test
  (testing "TASK-221 AC #2: a `|` inside an attribute group in a table cell
            is written bare (the row is split group-aware), every other
            `|` in the cell still escaped"
    (doseq [[src out] [["| h |\n|---|\n| [b]{k=\"a|b\"} |\n" "| h |\n| --- |\n| [b]{k=a|b} |\n"]
                       ["| h |\n|---|\n| [b]{.a|b #c|d} |\n" "| h |\n| --- |\n| [b]{#c|d .a|b} |\n"]
                       ["| h |\n|---|\n| x\\|y [b]{k=\"|\"} |\n" "| h |\n| --- |\n| x\\|y [b]{k=|} |\n"]
                       ["| h |\n|---|\n| ![i](u.png){k=\"a|b\"} |\n" "| h |\n| --- |\n| ![i](u.png){k=a|b} |\n"]
                       ["| h |\n|---|\n| [b](u){k=\"a|b\"} |\n" "| h |\n| --- |\n| [b](u){k=a|b} |\n"]
                       ["| h |\n|---|\n| ==b=={k=\"a|b\"} |\n" "| h |\n| --- |\n| ==b=={k=a|b} |\n"]
                       ["| h | g |\n|---|---|\n| [b]{k=\"a|b\"} | [c]{k=\"|\"} |\n"
                        "| h | g |\n| --- | --- |\n| [b]{k=a|b} | [c]{k=|} |\n"]]]
      (is (= (:blocks (parser/parse src)) (:blocks (parser/parse (emit-src src)))) src)
      (is (= out (emit-src src)) src)
      (is (fixed-point? src) src))))

(deftest attribute-group-pipe-in-table-cell-fuzz-test
  (testing "TASK-221 fuzz: values, ids and classes holding `|` round-trip in
            every inline that carries a group inside a table cell"
    (let [pieces ["a" "|" "||" " " "b" "-" "." ":" "\"" "'" "=" "{" "}" "`" "x y"]
          rng (java.util.Random. 221)
          gen (fn [] (apply str (repeatedly (inc (.nextInt rng 5)) #(nth pieces (.nextInt rng (count pieces))))))
          make (fn [kind v]
                 (let [attr (case kind
                              :value {:classes [] :props {"k" v}}
                              :class {:classes [(str/replace v #"[\s\"'=<>{}`]" "_")] :props {}}
                              :id {:id (str/replace v #"[\s\"'=<>{}`]" "_") :classes [] :props {}})]
                   (for [inline [{:t :span :inlines [(str-inline "b")] :attr attr}
                                 {:t :link :target "u" :inlines [(str-inline "b")] :attr attr}
                                 {:t :image :src "u.png" :alt "b" :attr attr}
                                 {:t :highlight :inlines [(str-inline "b")] :attr attr}]]
                     [attr (table-cell-doc [(str-inline "p|q") space inline])])))
          cases (for [kind [:value :class :id] _ (range 300) c (make kind (gen))] c)]
      (is (< 1000 (count (filter (fn [[attr _]] (some #(str/includes? (str %) "|") (concat (vals (:props attr)) (:classes attr) [(:id attr)]))) cases)))
          "enough cases hold a `|` in the group that the fuzz is not vacuous")
      (doseq [[attr document] cases]
        (if (some #(and (str/includes? % "\"") (str/includes? % "'")) (vals (:props attr)))
          (is (some? (thrown-attr-value document)) (pr-str attr))
          (let [out (hdoc/emit-document document)]
            (is (= document (parser/parse out)) (pr-str attr out))))))))

(deftest code-span-pipe-in-table-cell-test
  (testing "TASK-225: a `|` in a code span in a table cell is written bare
            (the parser splits a row code-span-aware and keeps `\\|` in a
            span as backslash plus pipe), text outside the span still escaped"
    (doseq [[src out] [["| h |\n|---|\n| `a|b` |\n" "| h |\n| --- |\n| `a|b` |\n"]
                       ["| h | i |\n|---|---|\n| `a||b` | `|` |\n" "| h | i |\n| --- | --- |\n| `a||b` | `|` |\n"]
                       ["| h |\n|---|\n| ``a|`b`` |\n" "| h |\n| --- |\n| ``a|`b`` |\n"]
                       ["| h |\n|---|\n| `a|{x}` |\n" "| h |\n| --- |\n| `a|{x}` |\n"]
                       ["| h |\n|---|\n| ` a|b ` |\n" "| h |\n| --- |\n| ` a|b ` |\n"]
                       ["| h |\n|---|\n| `a\\|b` |\n" "| h |\n| --- |\n| `a\\|b` |\n"]
                       ["| h |\n|---|\n| x\\|y `a|b` p\\|q |\n" "| h |\n| --- |\n| x\\|y `a|b` p\\|q |\n"]]]
      (is (= (:blocks (parser/parse src)) (:blocks (parser/parse (emit-src src)))) src)
      (is (= out (emit-src src)) src)
      (is (fixed-point? src) src)))
  (testing "a code span outside a table keeps its pipe as before"
    (is (= "`a|b`\n" (emit-src "`a|b`\n")))))

(deftest code-span-pipe-in-table-cell-fuzz-test
  (testing "TASK-225 fuzz: code text over `|`, backticks, braces, spaces and
            backslashes, beside plain text with pipes, round-trips in a cell"
    (let [pieces ["a" "|" "||" " " "b" "`" "{" "}" "{x}" "\\" "@" "!" "<" "=" "*" "_" "x y"]
          rng (java.util.Random. 225)
          gen (fn [] (apply str (repeatedly (inc (.nextInt rng 6)) #(nth pieces (.nextInt rng (count pieces))))))
          cases (for [_ (range 3000)
                      :let [t (gen)]
                      :when (not (str/blank? t))]
                  [t (table-cell-doc [(str-inline "p|q") space {:t :code :text t} space (str-inline "r|s")])])
          emitted (map (fn [[t d]] [t d (try (hdoc/emit-document d) (catch Exception _ nil))]) cases)
          written (filter (fn [[_ _ out]] out) emitted)]
      (is (< 500 (count (filter (fn [[t _ out]] (and out (str/includes? t "|"))) written)))
          "enough cases hold a `|` and were written that the fuzz is not vacuous")
      (doseq [[t document out] written]
        (is (= document (parser/parse out)) (pr-str t out))))))

(deftest book-ref-pipe-in-table-cell-test
  (testing "TASK-227: a `|` in an `@{ref}` in a table cell is written bare (the
            parser splits a row `@{`-group-aware and keeps `\\|` there as
            backslash plus pipe); unbalanced `{` in the ref and a stray `{`
            elsewhere in the cell round-trip too"
    (doseq [[src out] [["| h | i |\n|---|---|\n| @{| | {x}  |\n" "| h | i |\n| --- | --- |\n| @{| | {x} |\n"]
                       ["| h | i |\n|---|---|\n| @{a\\|{.c}  |\n" "| h | i |\n| --- | --- |\n| @{a\\|{.c} |\n"]
                       ["| h | i |\n|---|---|\n| @{a|b} | c |\n" "| h | i |\n| --- | --- |\n| @{a|b} | c |\n"]
                       ["| h | i |\n|---|---|\n| @{a|b} @{c|d} | x\\|y |\n" "| h | i |\n| --- | --- |\n| @{a|b} @{c|d} | x\\|y |\n"]
                       ["| h | i |\n|---|---|\n| {x | y |\n" "| h | i |\n| --- | --- |\n| {x | y |\n"]]]
      (is (= (:blocks (parser/parse src)) (:blocks (parser/parse (emit-src src)))) src)
      (is (= out (emit-src src)) src)
      (is (fixed-point? src) src))))

(deftest table-cell-brace-pipe-fuzz-test
  (testing "TASK-227 fuzz: cell source over pipes, braces, `@{`, backticks and
            `\\|` either round-trips or is refused with an ex-info"
    (let [pieces ["a" "|" "||" " " "@{" "{" "}" "`" "\\|" "\\" "{.c}" "[b]" "x" "@" "{x}"]
          rng (java.util.Random. 227)
          gen (fn [] (apply str (repeatedly (inc (.nextInt rng 6)) #(nth pieces (.nextInt rng (count pieces))))))
          docs (for [_ (range 6000)
                     :let [d (try (parser/parse (str "| h | i |\n|---|---|\n| " (gen) " |\n"))
                                  (catch Exception _ nil))]
                     :when (and d (= [:table] (map :t (:blocks d))))]
                 d)
          outs (map (fn [d] [d (try (hdoc/emit-document d)
                                    (catch clojure.lang.ExceptionInfo e e))])
                    docs)
          written (filter (comp string? second) outs)]
      (is (< 2000 (count written)) "enough cases were written that the fuzz is not vacuous")
      (is (< 40 (count (filter (fn [[d _]] (and (str/includes? (pr-str d) ":book-ref")
                                                (str/includes? (pr-str d) "|"))) written)))
          "enough written cases hold a book ref and a pipe")
      (doseq [[d out] written]
        (is (= d (parser/parse out)) (pr-str out)))
      (doseq [[_ e] outs :when (not (string? e))]
        (is (some? (:type (ex-data e))) (ex-message e))))))

(deftest math-pipe-in-table-cell-test
  (testing "TASK-230: a `|` in `$..$` in a table cell is written bare (the parser
            splits a row math-aware and keeps `\\|` there as backslash plus
            pipe); `!quote a|b` in a cell is just a cell split, and round-trips"
    (doseq [[src out] [["| h | i |\n|---|---|\n| $a|b$ | z |\n" "| h | i |\n| --- | --- |\n| $a|b$ | z |\n"]
                       ["| h | i |\n|---|---|\n| $a\\|b$ | z |\n" "| h | i |\n| --- | --- |\n| $a\\|b$ | z |\n"]
                       ["| h | i |\n|---|---|\n| x $a|b$ y\\|z | $c||d$ |\n" "| h | i |\n| --- | --- |\n| x $a|b$ y\\|z | $c||d$ |\n"]
                       ["| h | i |\n|---|---|\n| !quote a|b | z |\n" "| h | i |\n| --- | --- |\n| \\!quote a | b | z |\n"]
                       ["| h | i |\n|---|---|\n| !quote a\\|b | z |\n" "| h | i |\n| --- | --- |\n| \\!quote a\\|b | z |\n"]]]
      (is (= (:blocks (parser/parse src)) (:blocks (parser/parse (emit-src src)))) src)
      (is (= out (emit-src src)) src)
      (is (fixed-point? src) src))))

(deftest table-cell-math-pipe-fuzz-test
  (testing "TASK-230 fuzz: cell source over `$`, pipes, `\\|`, backticks and
            `@{` either round-trips or is refused with an ex-info"
    (let [pieces ["a" "|" "||" " " "$" "$x" "x$" "\\|" "\\" "`" "@{" "{" "}" "b" "$a|b$" "$a\\|b$"]
          rng (java.util.Random. 230)
          gen (fn [] (apply str (repeatedly (inc (.nextInt rng 6)) #(nth pieces (.nextInt rng (count pieces))))))
          docs (for [_ (range 6000)
                     :let [d (try (parser/parse (str "| h | i |\n|---|---|\n| " (gen) " |\n"))
                                  (catch Exception _ nil))]
                     :when (and d (= [:table] (map :t (:blocks d))))]
                 d)
          outs (map (fn [d] [d (try (hdoc/emit-document d)
                                    (catch clojure.lang.ExceptionInfo e e))])
                    docs)
          written (filter (comp string? second) outs)]
      (is (< 2000 (count written)) "enough cases were written that the fuzz is not vacuous")
      (is (< 40 (count (filter (fn [[d _]] (and (str/includes? (pr-str d) ":math-inline")
                                                (str/includes? (pr-str d) "|"))) written)))
          "enough written cases hold inline math and a pipe")
      (doseq [[d out] written]
        (is (= d (parser/parse out)) (pr-str out)))
      (doseq [[_ e] outs :when (not (string? e))]
        (is (some? (:type (ex-data e))) (ex-message e))))))

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
  (testing "a trailing `;` in an affix still throws, although
            parser/cite-segments drops the empty segment after it"
    (let [document {:meta {}
                    :blocks [{:t :para
                              :inlines [{:t :cite
                                         :items [{:key "smith04" :mode :normal
                                                  :suffix [(str-inline "p. 3;")]}]}]}]}
          e (is (thrown? clojure.lang.ExceptionInfo (hdoc/emit-document document)))]
      (is (= ::hdoc/unrepresentable-cite-affix (:type (ex-data e))))))
  (testing "an affix that renders empty has no `;` to report and emits"
    (let [document {:meta {}
                    :blocks [{:t :para
                              :inlines [{:t :cite
                                         :items [{:key "smith04" :mode :normal
                                                  :prefix [(str-inline "")]}]}]}]}]
      (is (string? (hdoc/emit-document document)))))
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
            -- items are re-numbered consecutively from the list's own
            start number (TASK-129)"
    (is (fixed-point? "- a\n- b\n"))
    (is (fixed-point? "- a\n\n- b\n"))
    (is (fixed-point? "1. a\n1. b\n"))
    (let [document (parser/parse "9. a\n9. b\n")]
      (is (= "9. a\n10. b\n" (hdoc/emit-document document)))))
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
            a spelling that reparses to something else (a :para after a
            nested list would lazily continue that list's last paragraph)"
    (let [document {:meta {} :blocks [{:t :list :ordered false :tight true
                                       :items [[{:t :list :ordered false :tight true
                                                 :items [[{:t :para :inlines [(str-inline "b")]}]]
                                                 :attr empty-attr}
                                                {:t :para :inlines [(str-inline "a")]}]]
                                       :attr empty-attr}]}
          e (is (thrown? clojure.lang.ExceptionInfo (hdoc/emit-document document)))]
      (is (= ::hdoc/unrepresentable-tight-list-item (:type (ex-data e))))
      (is (= :para (:t (ex-data e))))
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

(deftest adjacent-lists-stay-apart-test
  (testing "TASK-177 AC #1: adjacent bullet lists alternate `-`/`*`, so they
            re-parse as the same separate lists, tight or loose as before"
    (doseq [[src out] [["+ x\n- y\n" "- x\n\n* y\n"]
                       ["* x\n- y\n" "- x\n\n* y\n"]
                       ["- x\n* y\n+ z\n" "- x\n\n* y\n\n- z\n"]
                       ["- a\n\n- b\n\n* c\n" "- a\n\n- b\n\n* c\n"]
                       ["- a\n\n  b\n+ c\n- d\n" "- a\n\n  b\n\n* c\n\n- d\n"]]]
      (is (= out (hdoc/emit-document (parser/parse src))) (pr-str src))
      (is (fixed-point? src) (pr-str src))))
  (testing "TASK-177 AC #2: adjacent ordered lists alternate `.`/`)`, keeping
            each list's own start number"
    (doseq [[src out] [["1. a\n1) b\n" "1. a\n\n1) b\n"]
                       ["3. a\n4. b\n7) c\n2. d\n" "3. a\n4. b\n\n7) c\n\n2. d\n"]
                       ["1. a\n\n2. b\n5) c\n" "1. a\n\n2. b\n\n5) c\n"]]]
      (is (= out (hdoc/emit-document (parser/parse src))) (pr-str src))
      (is (fixed-point? src) (pr-str src))))
  (testing "a bullet list next to an ordered one, or lists apart in
            different sections, keep the usual markers"
    (doseq [[src out] [["- a\n\n1. b\n" "- a\n\n1. b\n"]
                       ["1) a\n+ b\n" "1. a\n\n- b\n"]
                       ["# S\n\n- a\n\n# T\n\n+ b\n" "# S\n\n- a\n\n# T\n\n- b\n"]]]
      (is (= out (hdoc/emit-document (parser/parse src))) (pr-str src))
      (is (fixed-point? src) (pr-str src))))
  (testing "every Block sequence alternates: a list item, a block quote, a
            footnote body and a directive body; a nested list starts afresh"
    (doseq [[src out] [["- a\n  - b\n  + c\n" "- a\n  - b\n  * c\n"]
                       ["* - a\n  + b\n" "- - a\n  * b\n"]
                       ["- a\n- b\n\n+ - c\n  + d\n" "- a\n- b\n\n* - c\n  * d\n"]
                       ["> - a\n> + b\n" "> - a\n>\n> * b\n"]
                       ["x[^1]\n\n[^1]: - a\n\n    + b\n" "x[^1]\n\n[^1]: - a\n\n    * b\n"]
                       [":::{note}\n1. a\n1) b\n:::\n" ":::{note}\n1. a\n\n1) b\n:::\n"]]]
      (is (= out (hdoc/emit-document (parser/parse src))) (pr-str src))
      (is (fixed-point? src) (pr-str src))))
  (testing "a list next to a thematic break, whichever way either is spelled"
    (doseq [src ["* a\n\n***\n\n* b\n" "- a\n\n---\n\n+ b\n" "+ a\n- b\n\n***\n"]]
      (is (fixed-point? src) (pr-str src))))
  (testing "fuzz: every source built from list-marker lines round-trips"
    (let [pieces ["- a" "+ b" "* c" "1. d" "2) e" "3. f" "" "" "  - g" "  + h" "  1) i"
                  "> - j" "> + k" "---" "***" "p" "  q" "- [ ] t" "* [x] u"]
          rng (java.util.Random. 177)
          srcs (repeatedly 2000 #(str (str/join "\n" (repeatedly (inc (.nextInt rng 8))
                                                                 (fn [] (nth pieces (.nextInt rng (count pieces))))))
                                      "\n"))
          parsed (keep (fn [src] (try [src (parser/parse src)] (catch Exception _ nil))) srcs)
          adjacent? (fn [blocks]
                      (some (fn [[x y]] (and (= :list (:t x) (:t y))
                                             (= (:ordered x) (:ordered y))))
                            (partition 2 1 blocks)))]
      (is (< 200 (count (filter (fn [[_ document]] (adjacent? (:blocks document))) parsed)))
          "enough generated documents hold adjacent lists that the fuzz is not vacuous")
      (doseq [[src document] parsed]
        (is (= document (parser/parse (hdoc/emit-document document))) (pr-str src))))))

(deftest nested-list-not-a-thematic-break-test
  (testing "TASK-179 AC #1: a one-item list nested down to an empty item is
            spelled `+ ` at the level that would complete a thematic break,
            at any depth and with any marker mix"
    (doseq [[src out] [["- - +\n" "+ - - \n"]
                       ["* - +\n" "+ - - \n"]
                       ["+ - *\n" "+ - - \n"]
                       ["- * -\n" "+ - - \n"]
                       ["+ + + +\n" "- + - - \n"]
                       ["* * * * +\n" "- - + - - \n"]
                       ["- - - + -\n" "- - + - - \n"]
                       ["- a\n\n- - +\n" "+ a\n\n+ - - \n"]
                       ["- - +\n- b\n" "+ - - \n+ b\n"]
                       ["- x\n+ - - +\n" "- x\n\n* + - - \n"]
                       ["- x\n+ - +\n" "- x\n\n* - - \n"]
                       ["> - - +\n" "> + - - \n"]
                       ["- a\n  - - +\n" "- a\n  + - - \n"]]]
      (is (= out (hdoc/emit-document (parser/parse src))) (pr-str src))
      (is (fixed-point? src) (pr-str src))))
  (testing "an item whose first Block is a thematic break is the same hazard
            (`- ---` is a break), and takes `+ ` the same way"
    (doseq [[src out] [["- ***\n" "+ ---\n"]
                       ["* ___\n" "+ ---\n"]
                       ["- - ***\n" "- + ---\n"]]]
      (is (= out (hdoc/emit-document (parser/parse src))) (pr-str src))
      (is (fixed-point? src) (pr-str src))))
  (testing "TASK-179 AC #2: lists that form no break-shaped line, and empty
            items, emit as before"
    (doseq [[src out] [["- +\n" "- - \n"]
                       ["- - x\n" "- - x\n"]
                       ["- - -x\n" "- - -x\n"]
                       ["- 1. +\n" "- 1. - \n"]
                       ["1. - +\n" "1. - - \n"]
                       ["- foo\n-\n- bar\n" "- foo\n- \n- bar\n"]
                       ["- a\n\n  -\n" "- a\n\n  - \n"]
                       ["- a\n  + - \n" "- a\n  - - \n"]]]
      (is (= out (hdoc/emit-document (parser/parse src))) (pr-str src))
      (is (fixed-point? src) (pr-str src))))
  (testing "fuzz: lines of nested markers, depth 1-5, round-trip"
    (let [markers ["- " "* " "+ " "1. " "2) "]
          contents ["" "" "" "x" "---" "***" "_ _ _"]
          rng (java.util.Random. 179)
          line (fn []
                 (str (nth ["" "" "  " "> "] (.nextInt rng 4))
                      (str/join (repeatedly (inc (.nextInt rng 5)) #(nth markers (.nextInt rng (count markers)))))
                      (nth contents (.nextInt rng (count contents)))))
          srcs (repeatedly 2000 #(str (str/join (nth ["\n" "\n\n"] (.nextInt rng 2))
                                                (repeatedly (inc (.nextInt rng 4)) line))
                                      "\n"))
          ;; A tight item holding, say, a list then a paragraph is a separate,
          ;; documented ::unrepresentable-tight-list-item refusal, not this
          ;; task's hazard, so such documents are left out. (A paragraph then
          ;; a thematic break, `- a\n  ***`, was one until TASK-181.)
          emittable? (fn [document]
                       (try (hdoc/emit-document document) true
                            (catch clojure.lang.ExceptionInfo e
                              (if (= ::hdoc/unrepresentable-tight-list-item (:type (ex-data e)))
                                false
                                (throw e)))))
          parsed (filter (fn [[_ document]] (emittable? document))
                         (keep (fn [src] (try [src (parser/parse src)] (catch Exception _ nil))) srcs))]
      (is (< 1000 (count parsed)) "the refusal above leaves most generated documents in")
      (is (< 200 (count (filter (fn [[_ document]] (re-find #"(?m)^(> )?[-* ]*\+ " (hdoc/emit-document document)))
                                parsed)))
          "enough generated documents need a `+ ` list that the fuzz is not vacuous")
      (doseq [[src document] parsed]
        (is (= document (parser/parse (hdoc/emit-document document))) (pr-str src))))))

(deftest heading-in-tight-list-item-test
  (testing "TASK-180 AC #1: a tight item holding a heading (a :section whose
            Blocks are the rest of the item) followed by more content writes
            no blank line, so the list re-parses tight -- setext and ATX
            headings, nested lists and sections, and every Block type safe
            there"
    (doseq [[src out] [["- x\n  -\n  +\n" "- ## x\n  - \n"]
                       ["- a\n  b\n  -\n  + c\n" "- a\n  b\n  ---\n  - c\n"]
                       ["- a\n  b\n  ===\n  > q\n- z\n" "- a\n  b\n  ===\n  > q\n- z\n"]
                       ["- # h\n  - c\n" "- # h\n  - c\n"]
                       ["- # h\n  p\n  > q\n- z\n" "- # h\n  p\n  > q\n- z\n"]
                       ["1. ## h\n   ```\n   k\n   ```\n2. z\n" "1. ## h\n   ```\n   k\n   ```\n2. z\n"]
                       ["- a\n  - # h\n    - c\n" "- a\n  - # h\n    - c\n"]
                       ["- # h\n  ## i\n  p\n- z\n" "- # h\n  ## i\n  p\n- z\n"]
                       ["- p\n  # h\n  q\n" "- p\n  # h\n  q\n"]
                       ["> - # h\n>   p\n" "> - # h\n>   p\n"]
                       ;; TASK-177's marker alternation holds inside the section too.
                       ["- # h\n  - a\n  + b\n" "- # h\n  - a\n  * b\n"]]]
      (is (= out (hdoc/emit-document (parser/parse src))) (pr-str src))
      (is (fixed-point? src) (pr-str src))
      (is (true? (:tight (some #(when (= :list (:t %)) %) (tree-seq coll? seq (parser/parse out)))))
          (pr-str src))))
  (testing "TASK-180 AC #2: a loose item keeps its blank lines, now empty
            rather than indented to a line of spaces"
    (doseq [[src out] [["- # h\n\n  p\n" "- # h\n\n  p\n"]
                       ["- a\n  b\n  ---\n\n  > q\n\n- z\n" "- a\n  b\n  ---\n\n  > q\n\n- z\n"]
                       ["- # h\n\n  - a\n\n  - b\n" "- # h\n\n  - a\n\n  - b\n"]]]
      (is (= out (hdoc/emit-document (parser/parse src))) (pr-str src))
      (is (fixed-point? src) (pr-str src))))
  (testing "a later Block in a tight item's section obeys the same rule as a
            later Block of the item: two paragraphs would merge, so refused"
    (let [p (para (str-inline "p"))
          document {:meta {}
                    :blocks [{:t :list :ordered false :tight true
                              :items [[{:t :section :level 1 :heading [(str-inline "h")]
                                        :blocks [p p] :attr empty-attr}]]}]}
          e (try (hdoc/emit-document document) nil (catch clojure.lang.ExceptionInfo e e))]
      (is (= ::hdoc/unrepresentable-tight-list-item (:type (ex-data e))))
      (is (= :para (:t (ex-data e))))
      (is (= 1 (:section-level (ex-data e))) "the index counts the section's Blocks, and says so")
      (is (str/includes? (ex-message e) "of a level-1 section"))))
  (testing "a footnote reached from a tight item's section heading is not
            that item's content: its own sections keep their blank lines"
    (let [src "- # h [^1]\n  p\n\n[^1]: # f\n\n    q\n\n    r\n"]
      (is (fixed-point? src))
      (is (true? (:tight (first (:blocks (parser/parse (hdoc/emit-document (parser/parse src))))))))))
  (testing "TASK-180 AC #3 fuzz: list items holding headings and more content
            round-trip, with no line of only spaces"
    (let [pieces ["- a" "+ b" "1. c" "  # h" "  ## i" "  x" "  y" "  -" "  ===" "  ---" "  > q"
                  "  - l" "  + m" "  ```\n  k\n  ```" "    - n" "    ## j" "" "- # g" "* d"]
          rng (java.util.Random. 180)
          srcs (repeatedly 2000 #(str (str/join "\n" (repeatedly (inc (.nextInt rng 7))
                                                                 (fn [] (nth pieces (.nextInt rng (count pieces))))))
                                      "\n"))
          ;; A paragraph after a paragraph a blank line had to close (in a
          ;; list, say) is the separate, documented
          ;; ::unrepresentable-tight-list-item refusal -- rare since
          ;; TASK-186 lets one follow a code block or a heading.
          emitted (keep (fn [src]
                          (when-let [document (try (parser/parse src) (catch Exception _ nil))]
                            (try [src document (hdoc/emit-document document)]
                                 (catch clojure.lang.ExceptionInfo e
                                   (when-not (= ::hdoc/unrepresentable-tight-list-item (:type (ex-data e)))
                                     (throw e))))))
                        srcs)
          heading-in-tight-item? (fn [blocks]
                                   (some (fn [block]
                                           (and (= :list (:t block)) (:tight block)
                                                (some #(= :section (:t (first %))) (:items block))))
                                         blocks))]
      (is (< 1900 (count emitted)) "the refusal leaves most generated documents in")
      (is (< 200 (count (filter (fn [[_ document]] (heading-in-tight-item? (:blocks document))) emitted)))
          "enough generated documents hold a heading in a tight item that the fuzz is not vacuous")
      (doseq [[src document out] emitted]
        (is (= document (parser/parse out)) (pr-str src))
        (is (not (re-find #"(?m)^ +$" out)) (pr-str src))))))

(defn- section-block
  "A :section of `level` headed `heading-text` holding `blocks`."
  [level heading-text & blocks]
  {:t :section :level level :heading [(str-inline heading-text)] :blocks (vec blocks) :attr empty-attr})

(defn- in-every-sequence
  "`[context document]` pairs placing Block sequence `blocks` in every
  sequence the writer renders, `context` being the name its refusal gives."
  [blocks]
  (let [doc (fn [& bs] {:meta {} :blocks (vec bs)})
        a-list (fn [tight?] {:t :list :ordered false :tight tight? :items [blocks] :attr empty-attr})]
    [["the document" (apply doc blocks)]
     ["a level-1 section" (doc (apply section-block 1 "outer" blocks))]
     ["a directive" (doc {:t :directive :name "box" :attr empty-attr :blocks blocks})]
     ["a block quote" (doc {:t :block-quote :blocks blocks})]
     ["a list item" (doc (a-list true))]
     ["a list item" (doc (a-list false))]
     ["a list item" (doc {:t :list :ordered false :tight true :attr empty-attr
                          :items [[(apply section-block 1 "outer" blocks)]]})]
     ["a note" (doc (para (str-inline "x") {:t :note :blocks blocks}))]]))

(deftest block-after-section-test
  (testing "TASK-194 AC #1: after a :section, any Block but a section of the
            same or a higher level would re-parse inside it, so it is refused
            in every sequence, naming the sequence and the position"
    (doseq [[after offending] [[(section-block 2 "h") (para (str-inline "p"))]
                               [(section-block 2 "h") (section-block 3 "deeper")]
                               [(section-block 2 "h") {:t :thematic-break}]
                               [(section-block 2 "h") {:t :block-quote :blocks [(para (str-inline "q"))]}]
                               [(section-block 2 "h" (section-block 3 "i")) (section-block 3 "j")]]
            [context document] (in-every-sequence [(para (str-inline "a")) after offending])
            ;; A section's own Blocks are checked as "a level-1 section".
            :let [context (if (and (= "a list item" context)
                                   (= :section (:t (ffirst (:items (first (:blocks document)))))))
                            "a level-1 section"
                            context)
                  e (try (hdoc/emit-document document) nil (catch clojure.lang.ExceptionInfo e e))]]
      (is (= ::hdoc/unrepresentable-block-after-section (:type (ex-data e))) (pr-str [context offending]))
      (is (= {:context context :index 2 :t (:t offending) :after-level 2}
             (select-keys (ex-data e) [:context :index :t :after-level]))
          (pr-str [context offending]))
      (is (= (when (= :section (:t offending)) 3) (:level (ex-data e))))
      (is (str/includes? (ex-message e) (str "at index 2 of " context ", directly after a level-2 section")))))
  (testing "a directive after a section at the document's top level is refused
            the same way: the parser folds it into the section too"
    (let [document {:meta {} :blocks [(section-block 1 "h") {:t :directive :name "box" :attr empty-attr
                                                             :blocks [(para (str-inline "q"))]}]}
          e (try (hdoc/emit-document document) nil (catch clojure.lang.ExceptionInfo e e))]
      (is (= [::hdoc/unrepresentable-block-after-section :directive]
             ((juxt :type :t) (ex-data e))))))
  (testing "a section followed by sections of the same or a higher level is
            written and round-trips, in every sequence"
    (doseq [[context document] (in-every-sequence [(para (str-inline "a"))
                                                   (section-block 3 "h" (para (str-inline "p")) (section-block 4 "i"))
                                                   (section-block 3 "j")
                                                   (section-block 2 "k" (para (str-inline "q")))])
            ;; The outer level-1 section wraps them, so their levels nest.
            :let [out (hdoc/emit-document document)]]
      (is (= document (parser/parse out)) (pr-str context))))
  (testing "TASK-194 (widened): a section holding a section of the same or a
            higher level is refused -- it would re-parse as a sibling --
            wherever the child sits in its Blocks and wherever the parent sits"
    (doseq [[parent idx level] [[(section-block 2 "a" (section-block 2 "b")) 0 2]
                                [(section-block 2 "a" (section-block 1 "b")) 0 1]
                                [(section-block 2 "a" (para (str-inline "p")) (section-block 2 "b")) 1 2]
                                [(section-block 2 "a" (section-block 3 "b") (section-block 2 "c")) 1 2]]
            [_ document] (in-every-sequence [parent])
            ;; In in-every-sequence's outer level-1 section the level-2 parent
            ;; is itself valid, so only its child is refused there too.
            :let [e (try (hdoc/emit-document document) nil (catch clojure.lang.ExceptionInfo e e))]]
      (is (= {:type ::hdoc/unrepresentable-nested-section :index idx :parent-level 2 :level level}
             (ex-data e))
          (pr-str parent))
      (is (str/includes? (ex-message e) (str "level-" level " section at index " idx
                                             " inside a level-2 section")))))
  (testing "a section of a deeper level nested in one round-trips, however deep"
    (doseq [[_ document] (in-every-sequence [(section-block 2 "a" (para (str-inline "p"))
                                                            (section-block 4 "b" (section-block 6 "c"))
                                                            (section-block 3 "d"))])]
      (is (= document (parser/parse (hdoc/emit-document document)))))))

(deftest parsed-sections-never-refused-test
  (testing "TASK-194 AC #2: no parsed document holds a Block after a section
            that is not a same-or-higher-level section, so the refusal never
            fires on one -- fuzz over headings, prose, quotes, lists, notes and
            directives"
    (let [pieces ["# a" "## b" "### c" "p" "" "> # q" "> r" ">" "- # l" "  m" "  ## n" "- o"
                  ":::{box}" ":::" "x[^1]" "[^1]: # f" "    g" "    ## h" "!toc" "$$\nx\n$$"]
          rng (java.util.Random. 194)
          srcs (repeatedly 2000 #(str (str/join "\n" (repeatedly (inc (.nextInt rng 8))
                                                                 (fn [] (nth pieces (.nextInt rng (count pieces))))))
                                      "\n"))
          refusal (fn [document]
                    (try (hdoc/emit-document document) nil
                         (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))
          parsed (keep (fn [src] (try [src (parser/parse src)] (catch Exception _ nil))) srcs)
          section-then-more? (fn [document]
                               (some (fn [node]
                                       (and (map? node) (vector? (:blocks node))
                                            (some #(= :section (:t %)) (butlast (:blocks node)))))
                                     (tree-seq coll? seq document)))]
      (is (< 150 (count (filter (comp section-then-more? second) parsed)))
          "enough generated documents hold a section followed by more Blocks that the fuzz is not vacuous")
      (is (< 60 (count (filter (fn [[_ document]]
                                 (some #(and (= :section (:t %)) (some (comp #{:section} :t) (:blocks %)))
                                       (tree-seq coll? seq document)))
                               parsed)))
          "enough generated documents nest a section in a section")
      (doseq [[src document] parsed]
        (is (not (#{::hdoc/unrepresentable-block-after-section ::hdoc/unrepresentable-nested-section}
                  (refusal document)))
            (pr-str src))))))

(deftest setext-heading-after-open-paragraph-in-tight-item-test
  (testing "TASK-185 AC #1: a setext heading after a block quote in a tight
            item keeps the quote's closing `>` line, so its text does not
            lazily continue the quote's paragraph -- directly, in a section,
            through nested quotes, and after a quote ending in a table or a
            quote holding a list only deeper down"
    (doseq [[src out] [["- > q\n  >\n  a\n  b\n  ---\n" "- > q\n  >\n  a\n  b\n  ---\n"]
                       ["- > q\n  >\n  a\n  b\n  ===\n- z\n" "- > q\n  >\n  a\n  b\n  ===\n- z\n"]
                       ["- # h\n  > q\n  >\n  a\n  b\n  ---\n" "- # h\n  > q\n  >\n  a\n  b\n  ---\n"]
                       ["- ## h\n  > q\n  >\n  a\n  b\n  ---\n" "- ## h\n  > q\n  >\n  a\n  b\n  ---\n"]
                       ["- > > q\n  > >\n  a\n  b\n  ---\n" "- > > q\n  >\n  a\n  b\n  ---\n"]
                       ["- > > - x\n  >\n  a\n  b\n  ---\n" "- > > - x\n  >\n  a\n  b\n  ---\n"]
                       ["- > # g\n  > p\n  >\n  a\n  b\n  ---\n" "- > # g\n  >\n  > p\n  >\n  a\n  b\n  ---\n"]
                       ["- > | a | b |\n  > | --- | --- |\n  > | c | d |\n  >\n  a\n  b\n  ---\n"
                        "- > | a | b |\n  > | --- | --- |\n  > | c | d |\n  >\n  a\n  b\n  ---\n"]
                       ;; A closed quote needs no `>` line, and none is written.
                       ["- > ```\n  > k\n  > ```\n  a\n  b\n  ---\n" "- > ```\n  > k\n  > ```\n  a\n  b\n  ---\n"]]]
      (is (= out (hdoc/emit-document (parser/parse src))) (pr-str src))
      (is (fixed-point? src) (pr-str src))
      (is (true? (:tight (first (:blocks (parser/parse out))))) (pr-str src))))
  (testing "TASK-185 review: a quote reached through the last items of nested
            lists gets its `>` line at its own indent, the sum of those
            items' marker widths -- bullets, ordered markers of any width,
            and TASK-177's alternate marker -- and every list stays tight"
    (doseq [src ["- - > q\n    >\n  a\n  b\n  ---\n"
                 "- 1. > q\n     >\n  a\n  b\n  ---\n"
                 "- - > > q\n    >\n  a\n  b\n  ---\n"
                 "- - x\n    > q\n    >\n  a\n  b\n  ---\n"
                 "- 9. x\n  10. > q\n      >\n  a\n  b\n  ---\n"
                 "- - - > q\n      >\n  a\n  b\n  ---\n"
                 "- - # g\n    > q\n    >\n  a\n  b\n  ---\n"
                 "- # h\n  - > q\n    >\n  a\n  b\n  ---\n"
                 "1. - > q\n     >\n   a\n   b\n   ---\n"]]
      (is (= src (hdoc/emit-document (parser/parse src))) (pr-str src))
      (is (fixed-point? src) (pr-str src))
      (is (every? :tight (filter #(= :list (:t %)) (tree-seq coll? seq (parser/parse src)))) (pr-str src)))
    (let [src "- - a\n  + > q\n    >\n  a\n  b\n  ---\n"]
      (is (= "- - a\n  * > q\n    >\n  a\n  b\n  ---\n" (hdoc/emit-document (parser/parse src))))
      (is (fixed-point? src))))
  (testing "an ATX heading interrupts the paragraph wherever it is, so it
            needs no `>` line and none is written"
    (doseq [src ["- > q\n  # h\n" "- - x\n  # h\n" "- > - x\n  ## h\n" "- p\n  # h\n"
                 "- # h\n  > q\n  ## i\n" "- # h\n  - x\n  ## i\n"]]
      (is (= src (hdoc/emit-document (parser/parse src))) (pr-str src))
      (is (fixed-point? src) (pr-str src))))
  (testing "TASK-185 AC #1: where no line ends the paragraph and keeps the
            list tight -- a paragraph, a list, or a quote whose own last
            Block is a list (a `>` line makes the list loose there) -- the
            setext heading is refused, naming what it follows"
    (let [setext {:t :section :level 2 :heading [(str-inline "a") {:t :soft-break} (str-inline "b")]
                  :blocks [] :attr empty-attr}
          p (para (str-inline "p"))
          lst (fn [& blocks] {:t :list :ordered false :tight true :items [(vec blocks)] :attr empty-attr})
          bq (fn [& blocks] {:t :block-quote :blocks (vec blocks)})
          item-doc (fn [& blocks] {:meta {} :blocks [(apply lst blocks)]})
          refusal (fn [document]
                    (try (hdoc/emit-document document) nil (catch clojure.lang.ExceptionInfo e e)))]
      (doseq [[document after leaf] [[(item-doc p setext) :para :para]
                                     [(item-doc (lst p) setext) :list :para]
                                     [(item-doc (lst (bq (lst p))) setext) :list :para]
                                     [(item-doc (bq (lst p)) setext) :block-quote :para]
                                     [(item-doc (bq {:t :section :level 1 :heading [(str-inline "g")]
                                                     :blocks [(lst p)] :attr empty-attr})
                                                setext)
                                      :block-quote :para]
                                     [(item-doc (apply lst (:blocks (parser/parse "$$\nx\n$$\n"))) setext)
                                      :list :math-block]
                                     [(item-doc {:t :section :level 2 :heading [(str-inline "h")]
                                                 :blocks [(lst p)] :attr empty-attr}
                                                setext)
                                      :section :para]]
              :let [e (refusal document)]]
        (is (= ::hdoc/unrepresentable-tight-list-item (:type (ex-data e))) (pr-str document))
        (is (= {:t :section :index 1 :after after :leaf leaf}
               (select-keys (ex-data e) [:t :index :after :leaf])))
        (is (str/includes? (ex-message e) "multi-line (setext) heading")))
      (testing "inside a tight item's section the error says so"
        (let [document (item-doc {:t :section :level 1 :heading [(str-inline "h")] :blocks [p setext]
                                  :attr empty-attr})
              e (refusal document)]
          (is (= 1 (:section-level (ex-data e))))
          (is (str/includes? (ex-message e) "of a level-1 section"))))))
  (testing "TASK-185 AC #2 fuzz: tight items mixing block quotes, lists and
            setext headings round-trip or are refused, never silently changed"
    (let [pieces ["- a" "1. c" "  > q" "  >" "  > - l" "  > > r" "  - m" "  x" "  y" "  ---" "  ==="
                  "  # h" "  ## i" "  ```\n  k\n  ```" "" "- # g" "  >\n  x\n  y\n  ---" "  x\n  y\n  ==="
                  "  - > s" "  1. > t" "    > u" "    >" "     >" "  - - > v\n      >"
                  "    >\n  x\n  y\n  ---" "     >\n  x\n  y\n  ===" "- - > w" "- 1. > z"]
          rng (java.util.Random. 185)
          srcs (repeatedly 3000 #(str (str/join "\n" (repeatedly (inc (.nextInt rng 7))
                                                                 (fn [] (nth pieces (.nextInt rng (count pieces))))))
                                      "\n"))
          emitted (keep (fn [src]
                          (when-let [document (try (parser/parse src) (catch Exception _ nil))]
                            (try [src document (hdoc/emit-document document)]
                                 (catch clojure.lang.ExceptionInfo e
                                   (when-not (= ::hdoc/unrepresentable-tight-list-item (:type (ex-data e)))
                                     (throw e))))))
                        srcs)
          quote-then-setext? (fn [[_ _ out]] (re-find #"(?m)^  >\n  [^>\s].*\n  [^>\s].*\n  (---|===)$" out))
          nested-quote-then-setext? (fn [[_ _ out]]
                                      (re-find #"(?m)^ {4,}>\n  [^>\s].*\n  [^>\s].*\n  (---|===)$" out))]
      (is (< 2000 (count emitted)) "the refusal leaves most generated documents in")
      (is (< 20 (count (filter quote-then-setext? emitted)))
          "enough documents close a quote before a setext heading that the fuzz is not vacuous")
      (is (< 20 (count (filter nested-quote-then-setext? emitted)))
          "enough documents close a quote inside a nested item the same way")
      (doseq [[src document out] emitted]
        (is (= document (parser/parse out)) (pr-str src))))))

(deftest thematic-break-in-tight-list-item-test
  (testing "TASK-181 AC #1: a thematic break after another Block of a tight
            item is written `***`, which interrupts the paragraph before it
            (`---` would be its setext underline) and ends a block quote's or
            nested list's lazy continuation, so the item re-parses the same
            and the list stays tight -- at every nesting level"
    (doseq [[src out] [["- a\n  ***\n" "- a\n  ***\n"]
                       ["- a\n  - - -\n" "- a\n  ***\n"]
                       ["* a\n  ___\n" "- a\n  ***\n"]
                       ["1. a\n   ***\n2. b\n" "1. a\n   ***\n2. b\n"]
                       ["- a\n  ***\n- b\n" "- a\n  ***\n- b\n"]
                       ["- a\n  ***\n  ***\n" "- a\n  ***\n  ***\n"]
                       ["- > q\n  ---\n" "- > q\n  ***\n"]
                       ["- > > q\n  ***\n" "- > > q\n  ***\n"]
                       ["- - a\n  ***\n" "- - a\n  ***\n"]
                       ["- - > q\n  ***\n" "- - > q\n  ***\n"]
                       ["- - a\n  + b\n  ***\n" "- - a\n  * b\n  ***\n"]
                       ["- $$\n  x\n  $$\n  ***\n" "- $$\n  x\n  $$\n  ***\n"]
                       ["- ```\n  k\n  ```\n  ***\n" "- ```\n  k\n  ```\n  ***\n"]
                       ["- # h\n  p\n  ***\n" "- # h\n  p\n  ***\n"]
                       ["- a\n  b\n  ---\n  ***\n" "- a\n  b\n  ---\n  ---\n"]
                       ["- a\n  ***\n  # h\n  > q\n" "- a\n  ***\n  # h\n  > q\n"]
                       ;; A break ends the paragraph: a setext heading after it is safe.
                       ["- a\n  ***\n  b\n  c\n  ---\n" "- a\n  ***\n  b\n  c\n  ---\n"]
                       ["- a\n  - b\n    ***\n" "- a\n  - b\n    ***\n"]
                       ["> - a\n>   ***\n" "> - a\n>   ***\n"]
                       ["- * * *\n  ***\n" "+ ---\n  ***\n"]]]
      (is (= out (hdoc/emit-document (parser/parse src))) (pr-str src))
      (is (fixed-point? src) (pr-str src))
      (is (every? :tight (filter #(= :list (:t %)) (tree-seq coll? seq (parser/parse out)))) (pr-str src))
      (is (some #(= :thematic-break (:t %)) (tree-seq coll? seq (parser/parse out))) (pr-str src))))
  (testing "TASK-181 AC #2: a thematic break anywhere else is still `---`"
    (doseq [[src out] [["- a\n\n  ***\n" "- a\n\n  ---\n"]
                       ["- ***\n" "+ ---\n"]
                       ["- # h\n  ***\n" "- # h\n  ---\n"]
                       ["> a\n>\n> ***\n" "> a\n>\n> ---\n"]
                       ["- a\n  > ***\n" "- a\n  > ---\n"]
                       ["a\n\n***\n" "a\n\n---\n"]]]
      (is (= out (hdoc/emit-document (parser/parse src))) (pr-str src))
      (is (fixed-point? src) (pr-str src))))
  (testing "TASK-186: a paragraph after the break (refused under TASK-181) is
            written on the next line, the break ending it"
    (let [document {:meta {} :blocks [{:t :list :ordered false :tight true
                                       :items [[(para (str-inline "a")) {:t :thematic-break}
                                                (para (str-inline "b"))]]
                                       :attr empty-attr}]}
          out (hdoc/emit-document document)]
      (is (= "- a\n  ***\n  b\n" out))
      (is (= document (parser/parse out)))))
  (testing "TASK-181 fuzz: tight items mixing paragraphs, quotes, lists and
            thematic breaks round-trip or are refused, never silently changed"
    (let [pieces ["- a" "* b" "1. c" "  x" "  ***" "  ---" "  - - -" "  ___" "  > q" "  - l" "  + m"
                  "    ***" "    y" "  # h" "  ```\n  k\n  ```" "" "> - r" ">   ***"]
          rng (java.util.Random. 181)
          srcs (repeatedly 2000 #(str (str/join "\n" (repeatedly (inc (.nextInt rng 6))
                                                                 (fn [] (nth pieces (.nextInt rng (count pieces))))))
                                      "\n"))
          emitted (keep (fn [src]
                          (when-let [document (try (parser/parse src) (catch Exception _ nil))]
                            (try [src document (hdoc/emit-document document)]
                                 (catch clojure.lang.ExceptionInfo e
                                   (when-not (= ::hdoc/unrepresentable-tight-list-item (:type (ex-data e)))
                                     (throw e))))))
                        srcs)]
      (is (< 1900 (count emitted)) "the refusal leaves most generated documents in")
      (is (< 200 (count (filter (fn [[_ _ out]] (re-find #"(?m)^ *(> +)?\*\*\*$" out)) emitted)))
          "enough documents write a `***` break in a tight item that the fuzz is not vacuous")
      (doseq [[src document out] emitted]
        (is (= document (parser/parse out)) (pr-str src))))))

(deftest paragraph-after-closed-block-in-tight-list-item-test
  (testing "TASK-186 AC #1: a paragraph after a Block that ends on a line
            nothing lazily continues -- a fenced code block, a heading with
            nothing under it, a break, an empty quote, or a quote, list or
            section ending in one -- is written on the next line and the list
            re-parses tight"
    (doseq [[src out] [["- # h\n  ```\n  k\n  ```\n  p\n" "- # h\n  ```\n  k\n  ```\n  p\n"]
                       ["- a\n  ```\n  k\n  ```\n  p\n" "- a\n  ```\n  k\n  ```\n  p\n"]
                       ["1. a\n   ```\n   k\n   ```\n   p\n2. z\n" "1. a\n   ```\n   k\n   ```\n   p\n2. z\n"]
                       ["- a\n  ***\n  b\n" "- a\n  ***\n  b\n"]
                       ["- a\n  # h\n  p\n" "- a\n  # h\n  p\n"]
                       ["- > ```\n  > k\n  > ```\n  p\n" "- > ```\n  > k\n  > ```\n  p\n"]
                       ["- - ```\n    k\n    ```\n  p\n" "- - ```\n    k\n    ```\n  p\n"]
                       ["- - > ```\n    > k\n    > ```\n  p\n" "- - > ```\n    > k\n    > ```\n  p\n"]
                       ["- # h\n  > ```\n  > k\n  > ```\n  p\n" "- # h\n  > ```\n  > k\n  > ```\n  p\n"]
                       ["- - # h\n  p\n" "- - # h\n  p\n"]
                       ["- > # h\n  p\n" "- > # h\n  p\n"]
                       ["- - a\n    b\n    ---\n  p\n" "- - a\n    b\n    ---\n  p\n"]
                       ["- > ***\n  p\n" "- > ---\n  p\n"]
                       ["- - ***\n  p\n" "- + ---\n  p\n"]
                       ["- >\n  p\n" "- >\n  p\n"]
                       ["- a\n  >\n  p\n" "- a\n  >\n  p\n"]
                       ["- a\n  ***\n  b\n  ```\n  k\n  ```\n  c\n- z\n" "- a\n  ***\n  b\n  ```\n  k\n  ```\n  c\n- z\n"]]]
      (is (= out (hdoc/emit-document (parser/parse src))) (pr-str src))
      (is (fixed-point? src) (pr-str src))
      (is (every? :tight (filter #(= :list (:t %)) (tree-seq coll? seq (parser/parse out)))) (pr-str src))))
  (testing "TASK-186: a paragraph after a block quote whose paragraph is
            still open gets the quote's closing `>` line first, as a setext
            heading does there (TASK-185) -- directly, in a section, and
            through a nested list's last item"
    (doseq [src ["- > q\n  >\n  p\n"
                 "- a\n  > q\n  >\n  y\n  x\n"
                 "- # h\n  > q\n  >\n  p\n"
                 "- - > q\n    >\n  p\n"
                 "- > | a | b |\n  > | --- | --- |\n  > | c | d |\n  >\n  p\n"
                 "- > # g\n  >\n  > q\n  >\n  p\n"]]
      (is (= src (hdoc/emit-document (parser/parse src))) (pr-str src))
      (is (fixed-point? src) (pr-str src))
      (is (every? :tight (filter #(= :list (:t %)) (tree-seq coll? seq (parser/parse src)))) (pr-str src))))
  (testing "TASK-186 AC #2: a paragraph after a Block that leaves a paragraph
            open with no line to close it -- a paragraph, display math, a
            table, a figure, or a list, a quote holding a list, or a section
            ending in one -- would lazily continue it, so is still refused;
            so is one after a list ending in an empty item"
    (let [p (para (str-inline "p"))
          lst (fn [& blocks] {:t :list :ordered false :tight true :items [(vec blocks)] :attr empty-attr})
          bq (fn [& blocks] {:t :block-quote :blocks (vec blocks)})
          section (fn [& blocks] {:t :section :level 1 :heading [(str-inline "h")] :blocks (vec blocks)
                                  :attr empty-attr})
          parsed (fn [src] (first (:blocks (parser/parse src))))
          refusal (fn [document]
                    (try (hdoc/emit-document document) nil (catch clojure.lang.ExceptionInfo e e)))]
      ;; TASK-201: a table only with a caption line, which `p` would
      ;; continue; `p` alone ends a bare table and is written after it.
      (doseq [prev [p (parsed "$$\nx\n$$\n") (parsed "| a | b |\n| --- | --- |\n| c | d |\n: c {#tbl:a}\n")
                    (parsed "![a](b.png)\n") (lst p) (bq (lst p))
                    {:t :list :ordered false :tight true :items [[]] :attr empty-attr}]
              :let [e (refusal {:meta {} :blocks [(lst prev p)]})]]
        (is (= {:type ::hdoc/unrepresentable-tight-list-item :t :para :index 1}
               (select-keys (ex-data e) [:type :t :index]))
            (pr-str prev))
        (is (str/includes? (ex-message e) (str "after a " (pr-str (:t prev)))) (pr-str prev)))
      ;; TASK-194: after a section, a paragraph would re-parse inside it
      ;; whatever the section ends in, so that refusal comes first.
      (doseq [prev [(section p) (section (lst p))]
              :let [e (refusal {:meta {} :blocks [(lst prev p)]})]]
        (is (= {:type ::hdoc/unrepresentable-block-after-section :t :para :index 1}
               (select-keys (ex-data e) [:type :t :index]))
            (pr-str prev)))
      (testing "inside a tight item's section too"
        (let [e (refusal {:meta {} :blocks [(lst (section (bq (lst p)) p))]})]
          (is (= {:type ::hdoc/unrepresentable-tight-list-item :t :para :index 1 :section-level 1
                  :after :block-quote :leaf :para}
                 (ex-data e)))
          (is (str/includes? (ex-message e) "is a paragraph directly after a :block-quote ending in a :para"))))))
  (testing "TASK-186 fuzz: tight items mixing paragraphs with code blocks,
            headings, breaks, quotes and lists round-trip or are refused,
            never silently changed"
    (let [pieces ["- a" "1. c" "  x" "  y" "  ***" "  # h" "  ## i" "  ```\n  k\n  ```" "  > q" "  >"
                  "  > ```\n  > k\n  > ```" "  - l" "  - ```\n    k\n    ```" "    z" "  $$\n  m\n  $$"
                  "  > # g" "  - # g" "  a\n  b\n  ---" "- # g" "  - > r\n    >"]
          rng (java.util.Random. 186)
          ;; Every source starts in a tight item, so most pieces land in one.
          srcs (repeatedly 3000 #(str "- a\n" (str/join "\n" (repeatedly (inc (.nextInt rng 7))
                                                                         (fn [] (nth pieces (.nextInt rng (count pieces))))))
                                      "\n"))
          emitted (keep (fn [src]
                          (when-let [document (try (parser/parse src) (catch Exception _ nil))]
                            (try [src document (hdoc/emit-document document)]
                                 (catch clojure.lang.ExceptionInfo e
                                   (when-not (= ::hdoc/unrepresentable-tight-list-item (:type (ex-data e)))
                                     (throw e))))))
                        srcs)
          ;; A tight item (or section in one) holding a :para right after
          ;; a non-:para Block: exactly the shape TASK-186 lets through.
          para-after-block? (fn [blocks]
                              (some (fn [[a b]] (and (= :para (:t b)) (not= :para (:t a))))
                                    (partition 2 1 blocks)))
          ;; An item's Blocks and, recursively, those of a section in them.
          tight-runs (fn tight-runs [blocks]
                       (cons blocks (mapcat #(when (= :section (:t %)) (tight-runs (:blocks %))) blocks)))
          new-shape? (fn [[_ document]]
                       (some (fn [node]
                               (and (map? node) (= :list (:t node)) (:tight node)
                                    (some para-after-block? (mapcat tight-runs (:items node)))))
                             (tree-seq coll? seq (:blocks document))))]
      (is (< 2500 (count emitted)) "the refusal leaves most generated documents in")
      (is (< 300 (count (filter new-shape? emitted)))
          "enough documents write a paragraph right after another Block in a tight item that the fuzz is not vacuous")
      (doseq [[src document out] emitted]
        (is (= document (parser/parse out)) (pr-str src))))))

(deftest paragraph-like-block-after-closed-block-in-tight-list-item-test
  (testing "TASK-193 AC #1: display math (also with an attribute group, and
            TASK-195's indented TeX), a table or a figure after a Block that
            ends on a line nothing lazily continues, or after a quote's
            closing `>` line, is written on the next line and the list
            re-parses tight -- directly, in a section, and nested"
    (doseq [prev ["- a\n  ```\n  k\n  ```" "- a\n  ***" "- a\n  >" "- > ---" "- >" "- + ---"
                  "- - # h" "- > # h" "- - a\n    b\n    ---" "- > ```\n  > k\n  > ```"
                  "- - ```\n    k\n    ```" "- # h\n  ```\n  k\n  ```" "- # h\n  > ```\n  > k\n  > ```"
                  ;; close-open-paragraph's `>` line, as before a paragraph
                  "- > q\n  >" "- - > q\n    >" "- # h\n  > q\n  >"]
            block ["$$\n  x\n  $$" "$$\n  x\n  $$ {#eq:a}" "$$\n      - x\n      $$"
                   "| a | b |\n  | --- | --- |\n  | c | d |" "![c](d.png){#fig:a}"]
            :let [src (str prev "\n  " block "\n- z\n")
                  document (parser/parse src)]]
      (is (some #(#{:math-block :table :figure} (:t %)) (tree-seq coll? seq document)) (pr-str src))
      (is (= src (hdoc/emit-document document)) (pr-str src))
      (is (fixed-point? src) (pr-str src))
      (is (every? :tight (filter #(= :list (:t %)) (tree-seq coll? seq document))) (pr-str src))))
  (testing "TASK-193: in a list nested in a quote or a footnote, and one after
            another with a break between"
    (doseq [src ["> - a\n>   ***\n>   $$\n>   x\n>   $$\n"
                 "> - a\n>   ***\n>   | a | b |\n>   | --- | --- |\n>   | c | d |\n"
                 "- - a\n    ***\n    $$\n    x\n    $$\n"
                 "1. a\n   ```\n   k\n   ```\n   $$\n   x\n   $$\n2. z\n"
                 "- a\n  ***\n  $$\n  x\n  $$\n  ***\n  | a | b |\n  | --- | --- |\n  | c | d |\n"]]
      (is (= src (hdoc/emit-document (parser/parse src))) (pr-str src))
      (is (fixed-point? src) (pr-str src))
      (is (every? :tight (filter #(= :list (:t %)) (tree-seq coll? seq (parser/parse src)))) (pr-str src))))
  (testing "TASK-193 AC #2: after a Block leaving a paragraph open with no line
            to close it, each would lazily continue that paragraph, so is
            still refused, naming itself -- in the item and in its section"
    (let [p (para (str-inline "p"))
          parsed (fn [src] (first (:blocks (parser/parse src))))
          lst (fn [& blocks] {:t :list :ordered false :tight true :items [(vec blocks)] :attr empty-attr})
          bq (fn [& blocks] {:t :block-quote :blocks (vec blocks)})
          section (fn [& blocks] {:t :section :level 1 :heading [(str-inline "h")] :blocks (vec blocks)
                                  :attr empty-attr})
          refusal (fn [document]
                    (try (hdoc/emit-document document) nil (catch clojure.lang.ExceptionInfo e e)))]
      (doseq [[block noun] [[(parsed "$$\nx\n$$\n") "display math block"]
                            [(parsed "| a | b |\n| --- | --- |\n| c | d |\n") "table"]
                            [(parsed "![c](d.png){#fig:a}\n") "figure"]]
              ;; TASK-201: one after a table is pinned in
              ;; block-after-table-in-tight-list-item-test instead.
              prev [p (parsed "$$\ny\n$$\n") (lst p) (bq (lst p)) (section p)]
              :let [e (refusal {:meta {} :blocks [(lst prev block)]})
                    ;; TASK-194: after a section it would re-parse inside it.
                    refusal-type (if (= :section (:t prev))
                                   ::hdoc/unrepresentable-block-after-section
                                   ::hdoc/unrepresentable-tight-list-item)]]
        (is (= {:type refusal-type :t (:t block) :index 1}
               (select-keys (ex-data e) [:type :t :index]))
            (pr-str [prev block]))
        (when-not (= :section (:t prev))
          (is (str/includes? (ex-message e) (str "is a " noun " directly after a " (pr-str (:t prev))))
              (pr-str [prev block])))
        (let [e (refusal {:meta {} :blocks [(lst (section (bq (lst p)) block))]})]
          (is (= {:type ::hdoc/unrepresentable-tight-list-item :t (:t block) :index 1 :section-level 1
                  :after :block-quote :leaf :para}
                 (ex-data e))
              (pr-str block))))))
  (testing "TASK-193 fuzz: tight items mixing display math, tables and figures
            with code blocks, headings, breaks, quotes and lists round-trip or
            are refused, never silently changed"
    (let [pieces ["  x" "  ***" "  # h" "  ```\n  k\n  ```" "  > q" "  >" "  > q\n  >" "  - l" "  - ***"
                  "  $$\n  m\n  $$" "  $$\n      - m\n  $$" "  | a | b |\n  | --- | --- |\n  | c | d |"
                  "  ![c](d.png){#fig:a}" "  > ***" "- # g"]
          rng (java.util.Random. 193)
          srcs (repeatedly 3000 #(str "- a\n" (str/join "\n" (repeatedly (inc (.nextInt rng 6))
                                                                         (fn [] (nth pieces (.nextInt rng (count pieces))))))
                                      "\n"))
          results (keep (fn [src]
                          (when-let [document (try (parser/parse src) (catch Exception _ nil))]
                            (try [src document (hdoc/emit-document document)]
                                 (catch clojure.lang.ExceptionInfo e
                                   (when-not (= ::hdoc/unrepresentable-tight-list-item (:type (ex-data e)))
                                     (throw e))
                                   [src document nil]))))
                        srcs)
          emitted (filter #(nth % 2) results)
          new-types #{:math-block :table :figure}
          ;; A tight item (or section in one) holding display math, a table
          ;; or a figure right after a Block that is not a paragraph: the
          ;; shape TASK-193 lets through.
          tight-runs (fn tight-runs [blocks]
                       (cons blocks (mapcat #(when (= :section (:t %)) (tight-runs (:blocks %))) blocks)))
          new-shape? (fn [[_ document]]
                       (some (fn [node]
                               (and (map? node) (= :list (:t node)) (:tight node)
                                    (some (fn [blocks]
                                            (some (fn [[a b]] (and (new-types (:t b)) (not= :para (:t a))))
                                                  (partition 2 1 blocks)))
                                          (mapcat tight-runs (:items node)))))
                             (tree-seq coll? seq (:blocks document))))]
      ;; 2956 emitted, 542 of the new shape at seed 193. The 44 refused
      ;; until TASK-201 (each a Block right after a table) now all write;
      ;; block-after-table-in-tight-list-item-test exercises what is still
      ;; refused there, and AC #2 above this one's refusals.
      (is (< 2500 (count emitted)) "the refusal leaves most generated documents in")
      (is (= (count results) (count emitted)) "no piece here leaves a Block that cannot be written")
      (is (< 400 (count (filter new-shape? emitted)))
          "enough documents write display math, a table or a figure right after another Block in a tight item")
      (doseq [[src document out] emitted]
        (is (= document (parser/parse out)) (pr-str src))))))

(deftest block-after-table-in-tight-list-item-test
  (testing "TASK-201 AC #1: a Block whose first line cannot continue the
            table above it -- no unescaped `|`, no setext underline, no
            caption line -- ends that table and is written on the next
            line; the list re-parses tight -- as the item's first Block,
            after a closed Block, in a section, and nested"
    (doseq [prev ["- " "- a\n  ***\n  " "- a\n  ```\n  k\n  ```\n  " "- # h\n  " "- a\n  ***\n  # h\n  "]
            ;; A line wholly in `[...]` -- `[@k]`, `[@a; @b]` -- was
            ;; refused until TASK-208 turned off flexmark's MultiMarkdown
            ;; caption line, which dropped it.
            block ["z" "z\n  y | x" "\\: z" "\\---" "z `a`" "$$\n  x\n  $$" "$$\n  a | b\n  $$ {#eq:a}"
                   "$$\n      - x\n      $$" "![c](d.png){#fig:a}" "[@k] and" "[@k]{.x}" "\\[z\\]" "[x](y)"
                   "z\n  [@k]" "$$\n      [@k]\n      $$" "[@k]" "[@a; @b]" "[-@k]" "[@k]\n  more"]
            :let [src (str prev "| a | b |\n  | --- | --- |\n  | c | d |\n  " block "\n- w\n")
                  document (parser/parse src)]]
      (is (some #(= :table (:t %)) (tree-seq coll? seq document)) (pr-str src))
      (is (= src (hdoc/emit-document document)) (pr-str src))
      (is (fixed-point? src) (pr-str src))
      (is (every? :tight (filter #(= :list (:t %)) (tree-seq coll? seq document))) (pr-str src)))
    (doseq [src ["1. | a | b |\n   | --- | --- |\n   | c | d |\n   z\n2. w\n"
                 "> - | a | b |\n>   | --- | --- |\n>   | c | d |\n>   $$\n>   x\n>   $$\n"
                 "- - | a | b |\n    | --- | --- |\n    | c | d |\n    ![c](d.png){#fig:a}\n"
                 "- | a | b |\n  | --- | --- |\n  | c | d |\n  z\n  ***\n  | e | f |\n  | --- | --- |\n  | g | h |\n  y\n"
                 "- | a | b |\n  | --- | --- |\n  | c | d |\n  [^1]\n- w\n\n[^1]: n\n"]]
      (is (= src (hdoc/emit-document (parser/parse src))) (pr-str src))
      (is (fixed-point? src) (pr-str src))
      (is (every? :tight (filter #(= :list (:t %)) (tree-seq coll? seq (parser/parse src)))) (pr-str src))))
  (testing "TASK-201 AC #2: a Block whose first line would join the table as
            a row, or whose underline would make it heading text, or that
            follows a caption line or a table in a nested list, is still
            refused"
    (let [parsed (fn [src] (first (:blocks (parser/parse src))))
          tbl (parsed "| a | b |\n| --- | --- |\n| c | d |\n")
          lst (fn [& blocks] {:t :list :ordered false :tight true :items [(vec blocks)] :attr empty-attr})
          section (fn [& blocks] {:t :section :level 1 :heading [(str-inline "h")] :blocks (vec blocks)
                                  :attr empty-attr})
          refusal (fn [document]
                    (try (hdoc/emit-document document) nil (catch clojure.lang.ExceptionInfo e e)))]
      ;; Each reads back otherwise: `z | y`, `| z`, `z|` and `\|---|`
      ;; (TASK-171's escaped delimiter row) as one more row, the
      ;; following table's rows as rows of the first, a `|` in a figure's
      ;; alt text refused conservatively (flexmark keeps it a figure), and a
      ;; setext heading swallowing the table as heading text.
      (doseq [block [(para (str-inline "z | y")) (para (str-inline "| z")) (para (str-inline "z|"))
                     (para (str-inline "|---|")) tbl (parsed "![c | e](d.png){#fig:a}\n")
                     (parsed "z\ny\n---\n")]
              [document data] [[(lst tbl block) {}]
                               [(lst (section tbl block)) {:section-level 1}]]
              :let [e (refusal {:meta {} :blocks [document]})]]
        (is (= (merge {:type ::hdoc/unrepresentable-tight-list-item :t (:t block) :index 1
                       :after :table :leaf :table}
                      data)
               (ex-data e))
            (pr-str block))
        (is (str/includes? (ex-message e) "would continue that table") (pr-str block)))
      (testing "after a captioned table, whose caption line the next line
                continues, and after a table ending a nested list, whose
                last item the next line lazily continues"
        (doseq [[prev leaf-note] [[(parsed "| a | b |\n| --- | --- |\n| c | d |\n: cap {#tbl:x}\n") "table"]
                                  [(parsed "| a | b |\n| --- | --- |\n| c | d |\n: {#tbl:x}\n") "table"]
                                  [(lst tbl) "paragraph"]]
                block [(para (str-inline "z")) (parsed "$$\nx\n$$\n") (parsed "![c](d.png){#fig:a}\n")]
                :let [e (refusal {:meta {} :blocks [(lst prev block)]})]]
          (is (= {:type ::hdoc/unrepresentable-tight-list-item :t (:t block) :index 1
                  :after (:t prev) :leaf :table}
                 (ex-data e))
              (pr-str [prev block]))
          (is (str/includes? (ex-message e) (str "would continue that " leaf-note)) (pr-str [prev block]))))))
  (testing "TASK-201 fuzz: tight items mixing tables with paragraphs (with
            and without a `|`), display math, figures, setext and ATX
            headings, breaks, quotes and lists round-trip or are refused,
            never silently changed"
    (let [t "  | a | b |\n  | --- | --- |\n  | c | d |"
          pieces ["  x" "  x | y" "  \\| x" "  x\n  y | z" "  ***" "  # h" "  k\n  ---" "  > q" "  - l"
                  "  - | a | b |\n    | --- | --- |\n    | c | d |" "  $$\n  m\n  $$" "  $$\n  a | b\n  $$"
                  t t (str t "\n  : cap") "  ![c](d.png){#fig:a}" "  ![c | e](d.png){#fig:a}" "  \\: x" "- # g"
                  "  [@k]" "  [@a; @b]\n  x" "  [@k] and"]
          rng (java.util.Random. 201)
          srcs (repeatedly 3000 #(str "- a\n  ***\n" (str/join "\n" (repeatedly (inc (.nextInt rng 6))
                                                                                (fn [] (nth pieces (.nextInt rng (count pieces))))))
                                      "\n"))
          results (keep (fn [src]
                          (when-let [document (try (parser/parse src) (catch Exception _ nil))]
                            (try [src document (hdoc/emit-document document)]
                                 (catch clojure.lang.ExceptionInfo e
                                   (when-not (= ::hdoc/unrepresentable-tight-list-item (:type (ex-data e)))
                                     (throw e))
                                   [src document nil]))))
                        srcs)
          emitted (filter #(nth % 2) results)
          ;; A tight item (or section in one) holding a Block of
          ;; `open-paragraph-types` right after a table: the shape TASK-201
          ;; lets through.
          tight-runs (fn tight-runs [blocks]
                       (cons blocks (mapcat #(when (= :section (:t %)) (tight-runs (:blocks %))) blocks)))
          new-shape? (fn [[_ document]]
                       (some (fn [node]
                               (and (map? node) (= :list (:t node)) (:tight node)
                                    (some (fn [blocks]
                                            (some (fn [[a b]] (and (= :table (:t a))
                                                                   (#{:para :math-block :figure} (:t b))))
                                                  (partition 2 1 blocks)))
                                          (mapcat tight-runs (:items node)))))
                             (tree-seq coll? seq (:blocks document))))
          refused (remove #(nth % 2) results)]
      ;; 2942 emitted, 58 refused (each a paragraph or figure after a table
      ;; it would continue), 284 of the new shape at seed 201; the bracket
      ;; swap below changes 228 documents and writes all of them (TASK-208;
      ;; under TASK-201 it was 2940/60/270, and 137 of 219 swapped refused).
      (is (< 2500 (count emitted)) "the refusal leaves most generated documents in")
      (is (< 40 (count refused)) "enough documents are still refused that AC #2 is exercised")
      (is (< 200 (count (filter new-shape? emitted)))
          "enough documents write a Block right after a table in a tight item that the fuzz is not vacuous")
      (doseq [[src document out] emitted]
        (is (= document (parser/parse out)) (pr-str src)))
      ;; Swap a paragraph whose first line is wholly in `[...]` (or nearly
      ;; so) into every paragraph-after-table slot of the emitted
      ;; documents. Refused until TASK-208 turned off flexmark's
      ;; MultiMarkdown caption line, which dropped such a line; every
      ;; swapped document now writes and reads back.
      (let [brackets (mapv #(first (:blocks (parser/parse %)))
                           ["[@k]\n" "[@a; @b]\n" "[-@k]\n" "[^1]\n\n[^1]: n\n" "[@k]\nmore\n"
                            "[@k] and\n" "[@k]{.x}\n" "\\[z\\]\n"])
            pick (atom -1)
            swap-in (fn [x]
                      (if (and (vector? x) (seq x) (every? #(and (map? %) (:t %)) x))
                        (into [(first x)] (map (fn [a b]
                                                 (if (and (= :table (:t a)) (= :para (:t b)))
                                                   (nth brackets (mod (swap! pick inc) (count brackets)))
                                                   b))
                                               x (rest x)))
                        x))
            swapped (keep (fn [[src document]]
                            (let [document' (walk/postwalk swap-in document)]
                              (when (not= document document')
                                (try [src document' (hdoc/emit-document document')]
                                     (catch clojure.lang.ExceptionInfo e
                                       (when-not (= ::hdoc/unrepresentable-tight-list-item (:type (ex-data e)))
                                         (throw e))
                                       [src document' nil])))))
                          emitted)
            written (filter #(nth % 2) swapped)]
        (is (= (count swapped) (count written)) "no swapped-in bracket line is refused")
        (is (< 100 (count written)) "enough swapped-in paragraphs are written that the swap is not vacuous")
        (doseq [[src document out] written]
          (is (= document (parser/parse out)) (pr-str [src out])))))))

(deftest adjacent-block-quotes-in-tight-list-item-test
  (let [parsed (fn [src] (first (:blocks (parser/parse src))))
        q (parsed "> a\n")
        lst (fn [tight & items] {:t :list :ordered false :tight tight :items (vec items) :attr empty-attr})
        section (fn [& blocks] {:t :section :level 1 :heading [(str-inline "h")] :blocks (vec blocks)
                                :attr empty-attr})
        refusal (fn [document]
                  (try (hdoc/emit-document document) nil (catch clojure.lang.ExceptionInfo e e)))]
    (testing "TASK-187 AC #1: no tight spelling separates two quotes -- a `>`
              line continues the first, and only a blank line ends it, making
              the list loose -- so the parser never yields this shape"
      (is (= [[(parsed "> a\nb\n")]] (:items (parsed "- > a\n  > b\n"))))
      (is (= [[(parsed "> a\n>\n> b\n")]] (:items (parsed "- > a\n  >\n  > b\n"))))
      (is (false? (:tight (parsed "- > a\n\n  > b\n")))))
    (testing "TASK-187 AC #1: a block quote right after a quote, or a section
              ending in one, in a tight item (or a section in one) is refused
              rather than merged into it"
      (doseq [[item after level] [[[q q] :block-quote nil]
                                  [[(parsed "> > a\n") q] :block-quote nil]
                                  [[(parsed ">\n") (parsed ">\n")] :block-quote nil]
                                  [[(parsed "> - x\n") q] :block-quote nil]
                                  [[(parsed "p\n") q q] :block-quote nil]
                                  [[(section q q)] :block-quote 1]]
              :let [e (refusal {:meta {} :blocks [(lst true item)]})]]
        (is (= (cond-> {:type ::hdoc/unrepresentable-tight-list-item :t :block-quote
                        :index (if level 1 (dec (count item))) :after after}
                 level (assoc :section-level level))
               (ex-data e))
            (pr-str item))
        (is (str/includes? (ex-message e) "is a block quote directly after a") (pr-str item)))
      ;; TASK-194: a quote after a section would re-parse inside it whatever
      ;; the section ends in (loose too), so that refusal comes first.
      (doseq [item [[(section q) q] [(section (section q)) q]]
              :let [e (refusal {:meta {} :blocks [(lst true item)]})]]
        (is (= {:type ::hdoc/unrepresentable-block-after-section :context "a list item" :index 1
                :t :block-quote :after-level 1}
               (ex-data e))
            (pr-str item))))
    (testing "TASK-187: a quote after a nested list ending in one, or after a
              Block between them, is still written tight and stays two quotes"
      (doseq [src ["- - > a\n  > b\n" "- 1. > a\n  > b\n" "- - # h\n    > a\n  > b\n"
                   "- # h\n  - > a\n  > b\n" "- > a\n  ***\n  > b\n" "- > a\n  # h\n  > b\n"
                   "- > a\n  ```\n  k\n  ```\n  > b\n" "- > a\n  >\n  p\n  > b\n"]]
        (is (= src (hdoc/emit-document (parser/parse src))) (pr-str src))
        (is (fixed-point? src) (pr-str src))
        (is (= 2 (count (filter #(= :block-quote (:t %)) (tree-seq coll? seq (parser/parse src)))))
            (pr-str src))))
    (testing "TASK-187: everywhere else adjacent quotes get a blank line (a
              bare `>` one inside a quote) and stay two"
      (doseq [[blocks out] [[[q q] "> a\n\n> a\n"]
                            [[(section q q)] "# h\n\n> a\n\n> a\n"]
                            [[{:t :block-quote :blocks [q q]}] "> > a\n>\n> > a\n"]
                            [[(lst false [q q])] "- > a\n\n  > a\n"]
                            [[(lst true [q] [q])] "- > a\n- > a\n"]
                            [[{:t :directive :name "d" :blocks [q q] :attr empty-attr}] ":::{d}\n> a\n\n> a\n:::\n"]
                            [[(para (str-inline "x") {:t :note :blocks [q q]})] "x[^1]\n\n[^1]: > a\n\n    > a\n"]]
              :let [document {:meta {} :blocks blocks}]]
        (is (= out (hdoc/emit-document document)) (pr-str blocks))
        (is (= document (parser/parse out)) (pr-str blocks))))
    (testing "TASK-187 fuzz: tight items built from quotes, nested lists and
              sections ending in quotes, and Blocks between them, round-trip
              or are refused, never silently merged"
      (let [pieces (mapv parsed ["> a\n" "> > a\n" ">\n" "> - x\n" "- > a\n" "1. > a\n" "- - > a\n"
                                 "```\nk\n```\n" "***\n" "p\n" "> a\n>\n> b\n"])
            rng (java.util.Random. 187)
            run (fn [n] (vec (repeatedly n #(nth pieces (.nextInt rng (count pieces))))))
            ;; A Block after a :section in the same run would re-parse into
            ;; that section, so a section only ever ends a run, one level
            ;; deeper than the section it is in.
            item (fn item [level]
                   (cond-> (run (inc (.nextInt rng 4)))
                     (and (< level 3) (.nextBoolean rng))
                     (conj (assoc (apply section (item (inc level))) :level level))))
            documents (repeatedly 3000 #(hash-map :meta {} :blocks [(lst true (item 1))]))
            results (map (fn [document]
                           (try [document (hdoc/emit-document document)]
                                (catch clojure.lang.ExceptionInfo e
                                  (is (= ::hdoc/unrepresentable-tight-list-item (:type (ex-data e))))
                                  [document nil])))
                         documents)
            emitted (filter second results)
            quotes-apart? (fn [[document]]
                            (some (fn [node]
                                    (and (map? node) (= :list (:t node)) (:tight node)
                                         (some (fn [blocks]
                                                 (some (fn [[a b]] (and (= :list (:t a))
                                                                        (= :block-quote (:t b))))
                                                       (partition 2 1 blocks)))
                                               (:items node))))
                                  (tree-seq coll? seq (:blocks document))))]
        (is (< 300 (count emitted)) "enough documents survive the refusals that the fuzz is not vacuous")
        (is (< 500 (- (count results) (count emitted))) "enough documents hold two quotes in a row to refuse")
        (is (< 50 (count (filter quotes-apart? emitted)))
            "enough documents write a quote right after a nested list ending in one")
        (doseq [[document out] emitted]
          (is (= document (parser/parse out)) (pr-str document)))))))

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
    (is (fixed-point? "$$\nx = 1\n$$\n")))
  (testing "a math block inside a block quote is written with `> ` on every
            line and reads back as the SAME MathBlock -- it used to come back
            a :para, since the parser saw the quote's own `> ` on the TeX and
            closing lines and matched no fence (TASK-188). Built directly,
            since a fixed point alone would also hold for a :para both ways"
    (let [math {:t :math-block :tex "a\n  b" :attr {:id "eq:q" :classes [] :props {}}}]
      (doseq [[context block]
              [["a quote" {:t :block-quote :blocks [math]}]
               ["a quote, after a paragraph" {:t :block-quote :blocks [(para (str-inline "x")) math]}]
               ["a nested quote" {:t :block-quote :blocks [{:t :block-quote :blocks [math]}]}]
               ["a list item in a quote"
                {:t :block-quote
                 :blocks [{:t :list :ordered true :tight false :attr empty-attr
                           :items [[(para (str-inline "x")) math]]}]}]
               ["a quote in a list item"
                {:t :list :ordered false :tight false :attr empty-attr
                 :items [[(para (str-inline "x")) {:t :block-quote :blocks [math]}]]}]]]
        (let [document {:meta {} :blocks [block]}
              out (hdoc/emit-document document)]
          (is (= (:blocks document) (:blocks (parser/parse out)))
              (str "math block in " context " should round-trip, emitted: " (pr-str out)))))))
  (testing "the authored `> $$` spelling is a MathBlock and a fixed point"
    (let [src "> $$\n> x = 1\n> $$ {#eq:q}\n"]
      (is (= [:block-quote] (mapv :t (:blocks (parser/parse src)))))
      (is (= [:math-block] (mapv :t (:blocks (first (:blocks (parser/parse src)))))))
      (is (fixed-point? src)))))

(deftest contained-math-block-lines-test
  (let [math (fn [tex attr] {:t :math-block :tex tex :attr attr})
        p (para (str-inline "x"))
        ;; Every container a contained math block is read back from one
        ;; flexmark Paragraph in, as [name block-holding-`m`].
        contexts (fn [m]
                   [["a quote" {:t :block-quote :blocks [m]}]
                    ["a quote, after a paragraph" {:t :block-quote :blocks [p m]}]
                    ["a nested quote" {:t :block-quote :blocks [{:t :block-quote :blocks [m]}]}]
                    ["a tight list item" {:t :list :ordered false :tight true :attr empty-attr
                                          :items [[m] [p]]}]
                    ["a tight list item, before a code block"
                     {:t :list :ordered false :tight true :attr empty-attr
                      :items [[m {:t :code-block :text "k\n" :attr empty-attr}]]}]
                    ["a loose list item, after a paragraph"
                     {:t :list :ordered true :tight false :attr empty-attr :items [[p m] [p]]}]
                    ["a nested list item" {:t :list :ordered false :tight true :attr empty-attr
                                           :items [[{:t :list :ordered true :tight true :attr empty-attr
                                                     :items [[m]]}]]}]
                    ["a quote in a list item" {:t :list :ordered false :tight false :attr empty-attr
                                               :items [[p {:t :block-quote :blocks [m]}]]}]
                    ["a footnote" {:t :para :inlines [(str-inline "a") {:t :note :blocks [m]}]}]
                    ["a footnote, after a paragraph"
                     {:t :para :inlines [(str-inline "a") {:t :note :blocks [p m]}]}]])
        attrs [empty-attr {:id "eq:x" :classes ["c"] :props {}}]
        emit (fn [block] (hdoc/emit-document {:meta {} :blocks [block]}))
        refusal (fn [block] (try (emit block) nil (catch clojure.lang.ExceptionInfo e e)))]
    (testing "TASK-195 AC #1: TeX lines that would start a Block -- a list
              marker, a heading, a quote, a code fence, a break or setext
              underline, a table delimiter row, a directive fence, an
              include, an HTML block, a footnote definition -- are indented
              4 more spaces with the closing fence, and the math block
              reads back the same in every container, its alignment and
              attribute group included"
      (doseq [tex ["- \\frac{a}{b}" "a\n- b" "a\n+ x" "a\n* x" "a\n1. x" "a\n2) x" "a\n# x" "a\n> x"
                   "a\n```\nb" "a\n~~~" "a\n***" "a\n---" "a\n===" "a\n___" "a | b\n|---|---|"
                   "a\n|---|" "a\n:---:" "a\n:::" "a\n:::{draft}" "a\n!include{x.hdoc}"
                   "a\n!quote{q}" "a\n<div>" "a\n[^1]: x" "a\n[r]: /u" "a\n  - b\n    c"
                   "\\begin{cases}\n  1 & x \\\\\n- 1 & y\n\\end{cases}"]
              attr attrs
              [context block] (contexts (math tex attr))]
        (let [out (emit block)]
          (is (str/includes? out "    $$") (str context ": " (pr-str out)))
          (is (= [block] (:blocks (parser/parse out)))
              (str "math " (pr-str tex) " in " context " should round-trip, emitted: " (pr-str out))))))
    (testing "TASK-195 AC #2: a blank (or whitespace-only) TeX line would end
              the one Paragraph a contained block is read from, so it is
              refused, naming the TeX"
      (doseq [tex ["a\n\nb" "a\n   \nb" "a\n\n- b"]
              [context block] (contexts (math tex empty-attr))
              :let [e (refusal block)]]
        (is (= {:type ::hdoc/unrepresentable-math-block :tex tex} (ex-data e)) context)
        (is (str/includes? (ex-message e) "blank line") context)))
    (testing "a contained block with no TeX at all is the bare fence pair"
      (is (= "> $$\n> $$\n" (emit {:t :block-quote :blocks [(math "" empty-attr)]})))
      (is (fixed-point? "> $$\n> $$\n"))
      (is (fixed-point? "- $$\n  $$ {#eq:e}\n")))
    (testing "TASK-195 AC #3: contained math with no such line is written
              exactly as before"
      (is (= "> $$\n> a\n>   b \\\\ c\n> $$ {#eq:x .c}\n"
             (emit {:t :block-quote :blocks [(math "a\n  b \\\\ c" (second attrs))]})))
      (is (= "- $$\n  \\frac{a}{b}\n  $$\n- x\n"
             (emit {:t :list :ordered false :tight true :attr empty-attr
                    :items [[(math "\\frac{a}{b}" empty-attr)] [p]]})))
      (testing "-- a leading number or `=` that is no list marker or setext
                underline included (the corpus has `1 \\leq ...` in list items)"
        (let [src "1. $$\n   1 \\leq x\n   = a + b\n   $$\n"]
          (is (= src (emit-src src)))
          (is (fixed-point? src)))))
    (testing "TASK-195 AC #3: top-level and directive-body math, cut out by
              the raw line scan before flexmark sees it, keeps block-starting
              and blank TeX lines unindented"
      (doseq [src ["$$\n- x\n# y\n$$ {#eq:a}\n" "$$\na\n\nb\n$$\n" ":::{draft}\n$$\n- x\n> y\n$$\n:::\n"]]
        (is (= src (emit-src src)))))
    (testing "TASK-195 fuzz: random TeX lines in random containers round-trip,
              or are refused for a blank line, never silently changed"
      (let [pool ["a" "\\frac{a}{b}" "  x & y \\\\" "- c" "+ d" "* e" "1. f" "10) g" "# h" "> i" "```"
                  "~~~" "---" "***" "===" "|---|" "| j |" ":::" "!include{k}" "<p>" "[^1]: l"
                  "    m" "" "  " "\\end{x}" "1 \\leq n" "= o" "==" "2.5"
                  ;; TASK-196: fence lines (refused), tab-indented lines
                  ;; (refused), and their indented / mid-line near misses.
                  "$$" "$$ {#q}" " $$" "$$p$$" "\tq" "  \t- r" "s\tt"]
            rng (java.util.Random. 195)
            texes (repeatedly 400 (fn []
                                    (let [lines (vec (repeatedly (inc (.nextInt rng 4))
                                                                 #(nth pool (.nextInt rng (count pool)))))]
                                      ;; As parsed, :tex is trimmed at both ends.
                                      (str/trim (str/join "\n" lines)))))
            results (for [tex texes
                          :let [all (contexts (math tex empty-attr))
                                [context block] (nth all (.nextInt rng (count all)))]]
                      [tex context block (try (emit block) (catch clojure.lang.ExceptionInfo e e))])]
        (is (< 100 (count (filter #(and (string? (peek %)) (str/includes? (peek %) "    $$")) results)))
            "enough block-starting TeX is written that the fuzz is not vacuous")
        (doseq [[tex context block out] results]
          (if (string? out)
            (is (= [block] (:blocks (parser/parse out))) (str (pr-str tex) " in " context))
            (is (and (= ::hdoc/unrepresentable-math-block (:type (ex-data out)))
                     (some #(or (str/blank? %) (parser/math-fence-line? %) (re-find #"^[ \t]*\t" %))
                           (str/split tex #"\n" -1)))
                (str (pr-str tex) " in " context))))))
    (testing "TASK-196 AC #1: a TeX line that is itself a `$$` fence --
              bare, with trailing blanks, or with an attribute group --
              would close the block early on re-parse, so it is refused
              at top level, in a directive body and in every container"
      (doseq [tex ["a\n$$\nb" "a\n$$  \nb" "a\n$$ {#q}\nb" "a\n$${.c}" "$$" "a\n$$"]
              [context block] (into [["top level" (math tex empty-attr)]
                                     ["a directive body" {:t :directive :name "draft" :attr empty-attr
                                                          :blocks [(math tex empty-attr)]}]]
                                    (contexts (math tex empty-attr)))
              :let [e (refusal block)]]
        (is (= {:type ::hdoc/unrepresentable-math-block :tex tex} (ex-data e)) (str (pr-str tex) " in " context))
        (is (str/includes? (str (ex-message e)) "`$$` fence") context)))
    (testing "TASK-196: `$$` lines the parser does not read as a fence -- an
              indented one, one with TeX after it, an unclosed group -- still
              round-trip, including through the contained 4-space indent"
      (doseq [tex ["a\n $$\nb" "a\n  $$ {#q}\nb" "a\n$$x$$\nb" "a\n$$ {#q\nb" "a\n$$x"]
              [context block] (into [["top level" (math tex empty-attr)]] (contexts (math tex empty-attr)))]
        (is (= [block] (:blocks (parser/parse (emit block)))) (str (pr-str tex) " in " context))))
    (testing "TASK-196 AC #2: inside a container a TeX line indented by a tab
              reads back with the tab expanded to spaces, so it is refused;
              at top level and in a directive body the raw line scan keeps
              the tab, and a tab later in a line is kept everywhere"
      (doseq [tex ["a\n\tb" "a\n\t- b" "a\n  \tb" "a\n    \\\\\n\tb"]
              [context block] (contexts (math tex empty-attr))
              :let [e (refusal block)]]
        (is (= {:type ::hdoc/unrepresentable-math-block :tex tex} (ex-data e)) (str (pr-str tex) " in " context))
        (is (str/includes? (str (ex-message e)) "tab") context))
      (doseq [tex ["a\n\tb" "a\n\t- b" "a\n  \tb"]
              block [(math tex empty-attr)
                     {:t :directive :name "draft" :attr empty-attr :blocks [(math tex empty-attr)]}]]
        (is (= [block] (:blocks (parser/parse (emit block)))) (pr-str block)))
      (doseq [[context block] (contexts (math "a\nb\tc &\td" empty-attr))]
        (is (= [block] (:blocks (parser/parse (emit block)))) context))
      (testing "-- an authored tab-indented contained line already reads back
                as spaces, so the writer never sees the tab: a fixed point"
        (is (fixed-point? "- $$\n  a\n  \tb\n  $$\n"))
        (is (fixed-point? "> $$\n> a\n> \tb\n> $$\n"))))))

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

(deftest cite-across-lines-round-trip-test
  (testing "TASK-165 AC #3: a hard-wrapped citation group is written back
            on one line -- the AST does not record a break between items
            or around a key -- and re-parses to the same Cite"
    (doseq [[src out] [["[see\n@smith2020]\n" "[see @smith2020]\n"]
                       ["[@a;\n@b]\n" "[@a; @b]\n"]
                       ["[see\n@fig:x]\n" "[see @fig:x]\n"]
                       ["[@a\n; -@b\n, p. 4]\n" "[@a; -@b, p. 4]\n"]
                       ["> [see\n> @a; @b]\n" "> [see @a; @b]\n"]
                       ["- [see\n  @a]\n" "- [see @a]\n"]]]
      (testing (pr-str src)
        (is (= out (hdoc/emit-document (parser/parse src))))
        (is (true? (round-trips-from-source? src))))))
  (testing "a break INSIDE a prefix or suffix is in the AST, so it is
            written back as a break, and still re-parses to the same Cite
            in every container"
    (doseq [[src out] [["[see\nalso @a, p.\n4]\n" "[see\nalso @a, p.\n4]\n"]
                       ["[see  \nalso @a]\n" "[see\\\nalso @a]\n"]
                       ["> [see\n> also @a]\n" "> [see\n> also @a]\n"]
                       ["- [see\n  also @a]\n" "- [see\n  also @a]\n"]]]
      (testing (pr-str src)
        (is (= out (hdoc/emit-document (parser/parse src))))
        (is (true? (round-trips-from-source? src))))))
  (testing "an affix line of `-`/`=` runs is escaped, since the parser reads
            the affix cut off at the `@`/`;`/`]` on its own (TASK-165
            review)"
    (doseq [[src out] [["[see\n--- @a]\n" "[see\n\\--- @a]\n"]
                       ["[see\n\\--- @a]\n" "[see\n\\--- @a]\n"]
                       ["[@a, p. 4\n-; @b]\n" "[@a, p. 4\n\\-; @b]\n"]
                       ["[@a, p. 4\n===]\n" "[@a, p. 4\n\\=\\==]\n"]]]
      (testing (pr-str src)
        (is (= out (hdoc/emit-document (parser/parse src))))
        (is (true? (round-trips-from-source? src))))))
  (testing "a stray `[` before a link, footnote or bracket on the next line
            round-trips with them intact"
    (doseq [src ["In [0, 1) it holds.\nAs @smith2020 shows [here](http://x).\n"
                 "In [0, 1) it holds.\nAs @smith2020 shows[^1].\n\n[^1]: Note.\n"
                 "In [0, 1) it holds.\nAs @smith2020 shows in [Fig. 2].\n"]]
      (testing (pr-str src)
        (is (true? (round-trips-from-source? src))))))
  (testing "a line a kept break starts is defused like any paragraph line"
    (let [src "[see\\\n\\- x @a]\n"]
      (is (= [(str-inline "see") {:t :line-break} (str-inline "-") {:t :space} (str-inline "x")]
             (-> (parser/parse src) :blocks first :inlines first :items first :prefix)))
      (is (= src (hdoc/emit-document (parser/parse src))))
      (is (true? (round-trips-from-source? src))))))

(deftest cite-code-math-and-block-like-affix-round-trip-test
  (testing "TASK-167 AC #3: a citation group with a `]` inside a code span
            or inline math is written back as the same group"
    (doseq [src ["a [x `code]` and @b]\n"
                 "[x $f]$ and @b]\n"
                 "[see `a[0]` @b]\n"
                 "[x ``a`]`` @b]\n"
                 "[x $a]$ @b; @c]\n"
                 "[x `a;b` @c]\n"
                 "[`@x` y @a]\n"
                 "[x `a]@b` y @c]\n"
                 "[x $a;b$ @c; @d]\n"
                 "[@a, `x;y`]\n"]]
      (testing (pr-str src)
        (is (= src (hdoc/emit-document (parser/parse src))))
        (is (true? (round-trips-from-source? src))))))
  (testing "an indented fragment keeps its markup and a leading fence run
            stays literal (TASK-167 review)"
    (doseq [[src out] [["[    *x* @a]\n" "[*x* @a]\n"]
                       ["- foo [@a;\n      *x* @b]\n" "- foo [@a; *x* @b]\n"]
                       ["[``` x\nfoo `` @a]\n" "[\\`\\`\\` x\nfoo \\`\\` @a]\n"]]]
      (testing (pr-str src)
        (is (= out (hdoc/emit-document (parser/parse src))))
        (is (true? (round-trips-from-source? src))))))
  (testing "a prefix or suffix starting like a block keeps its break and
            gains no space; its opening marker is escaped where the
            emitter's per-line escaping would escape it anywhere else"
    (doseq [[src out] [["[# x @a]\n" "[\\# x @a]\n"]
                       ["[- x @a]\n" "[\\- x @a]\n"]
                       ["[> x @a]\n" "[> x @a]\n"]
                       ["[+ x @a]\n" "[\\+ x @a]\n"]
                       ["[1. x @a]\n" "[1\\. x @a]\n"]
                       ["[# x\nfoo @a]\n" "[\\# x\nfoo @a]\n"]
                       ["[- x\nfoo @a]\n" "[\\- x\nfoo @a]\n"]
                       ["[> x\nfoo @a]\n" "[> x\nfoo @a]\n"]
                       ["[1. x\nfoo @a]\n" "[1\\. x\nfoo @a]\n"]
                       ["[@a, # x]\n" "[@a, \\# x]\n"]
                       ["[@a, + x\nfoo]\n" "[@a, \\+ x\nfoo]\n"]
                       ["[@a, > x\n---]\n" "[@a, > x\n\\---]\n"]
                       ["[@a;\n2. x @b]\n" "[@a; 2\\. x @b]\n"]]]
      (testing (pr-str src)
        (is (= out (hdoc/emit-document (parser/parse src))))
        (is (true? (round-trips-from-source? src))))))
  (testing "TASK-171 AC #2: an affix whose lines look like a table keeps
            its break, and its delimiter-row line is written escaped"
    (doseq [[src out] [["[| a |\n|---| @c]\n" "[| a |\n\\|---| @c]\n"]
                       ["[| a |\n| b |\n|---| @c]\n" "[| a |\n| b |\n\\|---| @c]\n"]
                       ["[*x* |\n--- | --- @c]\n" "[*x* |\n\\--- | --- @c]\n"]
                       ["[| a |\n    | :-: | @c]\n" "[| a |\n\\| :-: | @c]\n"]
                       ["[- a |\n|---| @c]\n" "[\\- a |\n\\|---| @c]\n"]
                       ["[@c, | a |\n|---|]\n" "[@c, | a |\n\\|---|]\n"]]]
      (testing (pr-str src)
        (is (= out (hdoc/emit-document (parser/parse src))))
        (is (true? (round-trips-from-source? src))))))
  (testing "TASK-171: a paragraph line shaped like a delimiter row is
            written escaped, so the paragraph does not re-parse as a table"
    (doseq [[src out] [["| a |\n\\|---|\n" "| a |\n\\|---|\n"]
                       ["a | b\n\\--- | ---\n" "a | b\n\\--- | ---\n"]]]
      (testing (pr-str src)
        (is (= out (hdoc/emit-document (parser/parse src))))
        (is (true? (round-trips-from-source? src))))))
  (testing "TASK-171 review: a line a code span continues onto is code, so
            no line-start escape is written into it -- in a paragraph, an
            affix, a container or a setext heading"
    (doseq [src ["| a |\n`x\n|---|\ny`\n"
                 "a `x\n2. y`\n"
                 "foo\n[| a `x\n|---|\ny` @c]\n"
                 "[@c, `x\n|---|`]\n"
                 "> a `x\n> 2. y`\n"
                 "a `x\n2. y`\nSetext\n===\n"]]
      (testing (pr-str src)
        (is (= src (hdoc/emit-document (parser/parse src))))
        (is (true? (round-trips-from-source? src)))))))

(deftest image-alt-line-break-round-trip-test
  (testing "TASK-182 AC #2: a newline in an image's :alt -- which only a
            multi-line code span puts there, the parser flattening a soft
            break to a space -- is written as a code span holding it, and
            re-parses to the same alt"
    (doseq [[src out alt] [["a ![l `x\n2. y`](u)\n" "a ![l x`\n`2. y](u)\n" "l x\n2. y"]
                           ["![`x\n`](u)\n" "![x`\n`](u)\n" "x\n"]
                           ["![`\nx`](u)\n" "![`\n`x](u)\n" "\nx"]
                           ["a ![x\\``\ny`](u)\n" "a ![x\\``\n`y](u)\n" "x`\ny"]
                           ;; Two in a row: an empty image keeps the spans apart.
                           ["a ![`x\n`*`\ny`*](u)\n" "a ![x`\n`![]()`\n`y](u)\n" "x\n\ny"]
                           ["> a ![`x\n> 2. y`](u)\n" "> a ![x`\n> `2. y](u)\n" "x\n2. y"]
                           ["a[^1]\n\n[^1]: b ![`x\n    2. y`](u)\n"
                            "a[^1]\n\n[^1]: b ![x`\n    `2. y](u)\n" "x\n2. y"]
                           ["Head ![`x\n2. y`](u)\n---\n" "Head ![x`\n`2. y](u)\n---\n" "x\n2. y"]]]
      (testing (pr-str src)
        (is (= [alt] (keep #(when (= :image (:t %)) (:alt %))
                           (tree-seq coll? seq (parser/parse src)))))
        (is (= out (hdoc/emit-document (parser/parse src))))
        (is (true? (round-trips-from-source? src)))))))

(defn- strings-in
  "Every string in `x`, however deeply nested."
  [x]
  (filter string? (tree-seq coll? seq x)))

(deftest error-text-without-marks-test
  (testing "TASK-182 AC #1: an error naming rendered text -- a `;` in a
            citation affix, a table cell on more than one line -- shows it
            without the emitter's internal U+0000 mark, which a `{`, a `!`
            and a newline in a code span each leave in it until the whole
            document is written"
    (let [marked [(str-inline "a{b} !x;") space {:t :code :text "p\nq"}]
          cite {:meta {} :blocks [{:t :para :inlines [{:t :cite :items [{:key "k" :mode :normal
                                                                         :suffix marked}]}]}]}
          cell {:meta {} :blocks [{:t :table
                                   :head {:cells [{:blocks [(apply para marked)]}]}
                                   :rows [] :caption [] :colspec [{}] :attr empty-attr}]}]
      (doseq [[document error-type] [[cite ::hdoc/unrepresentable-cite-affix]
                                     [cell ::hdoc/unrepresentable-table-cell]]]
        (testing (name error-type)
          (let [e (is (thrown? clojure.lang.ExceptionInfo (hdoc/emit-document document)))]
            (is (= error-type (:type (ex-data e))))
            ;; The text as written: a table cell's `{` escape is decided.
            (is (re-find #"a\\*\{b\} !x; `p\\nq`" (ex-message e)))
            (is (not-any? #(str/includes? % "\u0000") (cons (ex-message e) (strings-in (ex-data e)))))))))))

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
                                        {:t :sup :inlines [(str-inline "y")]}]}]}
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

(def ^:private plain-punctuation-opts
  "The vocabularies `haselnuss.cli` hands the parser for a document with a
  `fig` kind and no bibliography."
  {:kinds #{"fig"} :cite-keys #{}})

(deftest plain-punctuation-is-not-escaped-test
  (let [src (str "Links: see this. Undo: now\n\n"
                 "Is 7 > 3 true? cc @someone and user@example.com and "
                 "https://example.com/auto\n")
        document (parser/parse src plain-punctuation-opts)
        out (hdoc/emit-document document plain-punctuation-opts)]
    (testing "TASK-135 AC #1: a colon, a mid-line >, and an @ that is not a reference come out bare"
      (is (not (str/includes? out "\\")) out)
      (is (str/includes? out "Links: see this. Undo: now"))
      (is (str/includes? out "7 > 3"))
      (is (str/includes? out "cc @someone and user@example.com"))
      (is (str/includes? out "https://example.com/auto")))
    (testing "AC #2: and it round-trips"
      (is (= document (parser/parse out plain-punctuation-opts))))))

(deftest hazardous-punctuation-is-still-escaped-test
  (testing "what WOULD re-parse differently keeps its backslash, and round-trips"
    (doseq [[src expected]
            [["\\> not a quote\n" "\\> not a quote"]
             ["a\\\n\\> not a quote\n" "\\> not a quote"]
             ["\\::: not a fence\n" "\\::: not a fence"]
             ["see \\@fig:x and \\@fig:y\n" "see \\@fig:x"]]]
      (let [document (parser/parse src plain-punctuation-opts)
            out (hdoc/emit-document document plain-punctuation-opts)]
        (is (str/includes? out expected) out)
        (is (= document (parser/parse out plain-punctuation-opts)) src))))
  (testing "a real reference is not escaped, and an @ is escaped under the shape heuristics when no vocabulary is given"
    (let [document (parser/parse "x \\@someone and @fig:x\n" plain-punctuation-opts)
          out (hdoc/emit-document document plain-punctuation-opts)]
      (is (str/includes? out "x @someone and @fig:x") out))
    (let [document (parser/parse "x \\@someone\n")
          out (hdoc/emit-document document)]
      (is (str/includes? out "x \\@someone") out)
      (is (= document (parser/parse out))))))

(deftest bang-is-escaped-only-where-it-opens-something-test
  (testing "TASK-170 AC #1: a `!` that opens nothing comes out bare"
    (doseq [[src out] [["Hi!\n" "Hi!\n"] ["!!\n" "!!\n"] ["a ! b\n" "a ! b\n"]
                       ["end!\nnext\n" "end!\nnext\n"] ["end !\n" "end !\n"]
                       ;; Prose after the name, and a reserved name alone,
                       ;; are paragraphs to the parser's whole-line scan.
                       ["!Wow, look\n" "!Wow, look\n"] ["!toc, said\n" "!toc, said\n"]
                       ["!include\n" "!include\n"] ["!quote\n" "!quote\n"] ["a !toc\n" "a !toc\n"]
                       ;; Not at a line start, so not a one-line directive.
                       ["*\\!toc*\n" "*!toc*\n"]
                       ;; Before an escaped `[`, which opens no image.
                       ["!\\[x\\]\n" "!\\[x\\]\n"] ["![x][nope]\n" "!\\[x\\]\\[nope\\]\n"]
                       ["!![$x$]\n" "!!\\[$x$\\]\n"]
                       ;; Before something other than a bracket.
                       ["**!**[l](u)\n" "**!**[l](u)\n"] ["!`c`\n" "!`c`\n"] ["!$x$\n" "!$x$\n"]
                       ;; A prefix line of a wrapped citation group is not a
                       ;; whole-line construct: it does not end at the name.
                       ["a [x\n\\!toc @doe] b\n" "a [x\n!toc @doe] b\n"]]]
      (is (= out (emit-src src)) src)
      (is (fixed-point? src) src)))
  (testing "a `!` straight before a `[` -- whichever inline writes the
            bracket -- keeps its backslash, so it opens no image"
    (doseq [src ["\\![l](u)\n" "x\\![l](u)\n" "\\![s]{.c}\n" "a\\![^1]\n\n[^1]: n\n" "\\![@doe]\n"
                 "!\\![l](u)\n" "*a*\\![l](u)\n"]]
      (is (= src (emit-src src)) src)
      (is (fixed-point? src) src)))
  (testing "a `!` opening a whole-line construct keeps its backslash: at the
            block's start, after a soft or hard break, behind leading spaces,
            and in a list item's continuation line, which the parser's raw
            line scan reads too"
    (doseq [[src out] [["\\!toc\n" "\\!toc\n"] ["a\n\\!toc\n" "a\n\\!toc\n"]
                       ["a\\\n\\!toc\n" "a\\\n\\!toc\n"] ["  \\!toc\n" "\\!toc\n"]
                       ["\\!include x.hdoc\n" "\\!include x.hdoc\n"]
                       ["\\!quote bible:x\n" "\\!quote bible:x\n"]
                       ["a\n\\!quote bible:x\n" "a\n\\!quote bible:x\n"]
                       ["x\n\\!toc \\{x}\n" "x\n\\!toc \\{x}\n"]
                       ["- a\n  \\!toc\n" "- a\n  \\!toc\n"]]]
      (is (= out (emit-src src)) src)
      (is (fixed-point? src) src)))
  (testing "a hand-built split the parser never makes is judged over the
            whole block, not one text run"
    (doseq [[inlines out] [[[(str-inline "!") {:t :link :target "u" :inlines [(str-inline "l")] :attr empty-attr}]
                            "\\![l](u)\n"]
                           [[(str-inline "!") (str-inline "toc")] "\\!toc\n"]
                           [[(str-inline "a") {:t :soft-break} (str-inline "!") (str-inline "include x")]
                            "a\n\\!include x\n"]
                           [[(str-inline "!") {:t :soft-break} (str-inline "toc")] "!\ntoc\n"]]]
      (let [doc {:meta {} :blocks [(apply para inlines)]}
            emitted (hdoc/emit-document doc)]
        (is (= out emitted) (pr-str inlines))
        (is (= (parser/parse emitted) (parser/parse (hdoc/emit-document (parser/parse emitted))))
            (pr-str inlines)))))
  (testing "fuzz: every source built from `!`-adjacent pieces that parses
            re-parses to the same document after a round trip"
    (let [pieces ["!" "!" "\\!" "[l](u)" "[s]{.c}" "\\[" "]" "toc" "include x" "quote a:b" " " "\n" "\\\n"
                  ;; No `-`: a line of it after a hard break makes a
                  ;; setext heading holding a line break, which no ATX
                  ;; heading can spell. It failed the same way before
                  ;; TASK-170, a gap of its own rather than this one's.
                  ;; `\{x}` is in since TASK-175, which made an attributed
                  ;; span holding a citation (`![@doe][s]{.c}\{x}`)
                  ;; re-parse.
                  "a" "*e*" "[^1]" "@doe" "[@doe]" "  " "`c`" "\\{x}"]
          rng (java.util.Random. 170)
          srcs (repeatedly 3000 #(str (apply str (repeatedly (inc (.nextInt rng 7))
                                                             (fn [] (nth pieces (.nextInt rng (count pieces))))))
                                      "\n\n[^1]: n\n"))
          parsed (keep (fn [src] (try [src (parser/parse src)] (catch clojure.lang.ExceptionInfo _ nil))) srcs)]
      (is (< 2000 (count parsed)) "most generated sources parse, so the fuzz is not vacuous")
      (doseq [[src document] parsed]
        (is (= document (parser/parse (hdoc/emit-document document))) (pr-str src))))))

(deftest footnote-labels-are-renumbered-test
  (testing "TASK-135 AC #3: the AST keeps no footnote label, so the emitter numbers them in order of first reference"
    (let [document (parser/parse "A[^one] B[^two].\n\n[^two]: Second.\n\n[^one]: First.\n")
          out (hdoc/emit-document document)]
      (is (str/includes? out "A[^1] B[^2]."))
      (is (str/includes? out "[^1]: First."))
      (is (= document (parser/parse out))))))

(deftest footnote-definitions-stay-in-their-chunk-test
  (testing "TASK-173 AC #1: the parser resolves a marker only within its own
            chunk, which every column-0 :::, !name, !include, !quote and $$
            line cuts, so a definition is written before the next cut"
    (doseq [src ["a[^1]\n\n[^1]: n\n\n:::{note}\nx\n:::\n"
                 "a[^1]\n\n[^1]: n\n\n$$\nx\n$$\n\nb[^2]\n\n[^2]: m\n"
                 "a[^1]\n\n[^1]: n\n\n!aside{#x}\n"
                 "a[^1]\n\n[^1]: n\n\n!include x.hdoc\n"
                 "a[^1]\n\n[^1]: n\n\n!quote @{Gen 1:1}\n"
                 ;; A marker in an earlier section, the cut in a later one.
                 "# A\n\npara[^1]\n\n[^1]: n\n\n## B\n\n$$\nx\n$$\n"]]
      (is (fixed-point? src) (pr-str src))))
  (testing "a directive body is its own chunk sequence: a marker inside it
            is defined before the closing fence, at any nesting depth"
    (doseq [src [":::{note}\nin[^1]\n\n[^1]: n\n:::\n"
                 (str ":::{note}\nin[^1] body\n\n[^1]: n\n\n:::{tip}\ny[^2]\n\n[^2]: q\n:::\n\n"
                      "after[^3]\n\n[^3]: r\n:::\n\nout[^4]\n\n[^4]: s\n")]]
      (is (fixed-point? src) (pr-str src)))
    (let [out (hdoc/emit-document (parser/parse ":::{note}\nin[^1]\n\n[^1]: n\n:::\n"))]
      (is (= ":::{note}\nin[^1]\n\n[^1]: n\n:::\n" out)
          "the definition is the body's last block, inside the fence")))
  (testing "markers in a heading, a table, a list item, a block quote and a
            note's own body all reach a definition written before the cut;
            $$ inside a block quote or list item is not a cut"
    (doseq [src [(str "# H[^1]\n\n| a | b[^2] |\n|---|---|\n| c[^3] | d |\n\n- item[^4]\n\n"
                      "> q[^5]\n>\n> $$\n> x\n> $$\n\n"
                      "[^1]: h\n[^2]: t\n[^3]: c\n[^4]: l\n[^5]: bq\n\n!include x.hdoc\n")
                 "a[^1]\n\n[^1]: outer[^2]\n\n[^2]: inner\n\n:::{note}\nx\n:::\n"
                 "- item\n\n    $$\n    x\n    $$\n\n  more[^1]\n\n[^1]: n\n"]]
      (is (fixed-point? src) (pr-str src))))
  (testing "numbering keeps running across chunks, as before"
    (let [out (hdoc/emit-document (parser/parse "a[^x]\n\n[^x]: n\n\n$$\nx\n$$\n\nb[^y]\n\n[^y]: m\n"))]
      (is (= "a[^1]\n\n[^1]: n\n\n$$\nx\n$$\n\nb[^2]\n\n[^2]: m\n" out))))
  (testing "AC #3: with no marker before a cut, the definitions still all go
            at the end of the document, exactly as before"
    (is (= ":::{note}\nx\n:::\n\na[^1] b[^2]\n\n[^1]: n\n\n[^2]: m\n"
           (hdoc/emit-document (parser/parse ":::{note}\nx\n:::\n\na[^1] b[^2]\n\n[^1]: n\n\n[^2]: m\n"))))))

(defn- note-bodies
  "Every :note's own :blocks in `document`, in document order (a note
  nested in another note's body comes right after it)."
  [document]
  (let [acc (volatile! [])]
    (walk/prewalk (fn [x] (when (and (map? x) (= :note (:t x))) (vswap! acc conj (:blocks x))) x)
                  document)
    @acc))

(defn- canonical-gates
  "TASK-255 AC #5: for a valid `src`, the canonical `.hdoc` writer's output
  re-parses, to the same AST and the same note bodies, and emitting that
  re-parse again gives the same bytes. Returns the first emission."
  [src]
  (let [document (parser/parse src)
        first-out (hdoc/emit-document document)
        reparsed (try (parser/parse first-out)
                      (catch clojure.lang.ExceptionInfo e
                        (is false (str "canonical output does not re-parse: " (ex-message e)
                                       "\n" first-out))
                        nil))]
    (when reparsed
      (is (= document reparsed) first-out)
      (is (seq (note-bodies document)) "the case holds a note at all")
      (is (= (note-bodies document) (note-bodies reparsed)) first-out)
      (is (= first-out (hdoc/emit-document reparsed)) "second emission is byte-identical"))
    first-out))

(deftest footnote-canonical-scope-test
  (testing "TASK-255 AC #1: the external report's minimal case -- a note
            inside a directive body -- keeps its definition inside the fence"
    (is (= (str "---\ntopLevelDivision: chapter\n---\n\n"
                ":::{dedication}\nBefore[^1]\n\n[^1]: Unique note words\n:::\n")
           (canonical-gates (str "---\ntopLevelDivision: chapter\n---\n\n"
                                 ":::{dedication}\nBefore[^n]\n\n[^n]: Unique note words\n:::\n")))))
  (testing "TASK-255 AC #1: notes before and after a figure (directive and
            standalone-image spellings) and an admonition, each defined
            on its own side"
    (doseq [src ["a[^1]\n\n[^1]: before fig\n\n:::{figure #fig:a}\n![Cap](a.png)\n:::\n\nb[^2]\n\n[^2]: after fig\n"
                 "a[^1]\n\n[^1]: before fig\n\n![Cap](a.png){#fig:a}\n\nb[^2]\n\n[^2]: after fig\n"
                 (str "---\ntopLevelDivision: chapter\n---\n\n"
                      "Before[^before].\n\n[^before]: Note belonging to the prose before the admonition.\n\n"
                      ":::{admonition .note}\nAdvice without a footnote.\n:::\n\n"
                      "After[^after].\n\n[^after]: Note belonging to the prose after the admonition.\n")]]
      (testing (pr-str src) (canonical-gates src)))
    (let [out (canonical-gates (str "a[^1]\n\n[^1]: before\n\n:::{admonition .note}\nx\n:::\n\n"
                                    "b[^2]\n\n[^2]: after\n"))]
      (is (= "a[^1]\n\n[^1]: before\n\n:::{admonition .note}\nx\n:::\n\nb[^2]\n\n[^2]: after\n" out))))
  (testing "TASK-255 AC #2: notes on both sides of a nested directive inside
            one outer directive, with a marker repeated in the first chunk.
            Whether the repeat gets its own definition or shares one is the
            writer's choice; only that the first chunk is self-contained is
            pinned"
    (let [out (canonical-gates
               (str "---\ntopLevelDivision: chapter\n---\n\n"
                    "::::{dedication}\nFirst[^first], repeated[^first].\n\n"
                    "[^first]: First note, referenced twice before the nested directive.\n\n"
                    ":::{admonition .note}\nInside[^inside].\n\n"
                    "[^inside]: Note owned by the nested admonition body.\n:::\n\n"
                    "Last[^last].\n\n[^last]: Note owned by the final text chunk of the dedication.\n::::\n"))
          before (subs out 0 (str/index-of out ":::{admonition"))
          markers (set (map second (re-seq #"\[\^([^\]]+)\](?!:)" before)))
          defined (set (map second (re-seq #"(?m)^\[\^([^\]]+)\]:" before)))]
      (is (str/includes? before "First note, referenced twice before the nested directive.")
          "the first note's body is written before the nested fence")
      (is (seq markers))
      (is (every? defined markers)
          (str "every marker before the nested fence is defined there: " (pr-str before))))
    (testing "and a marker repeated across chunks, each chunk defining its own copy"
      (canonical-gates (str "a[^x] b[^x]\n\n[^x]: same\n\n:::{note}\nc[^y]\n\n[^y]: same\n:::\n\n"
                            "d[^x]\n\n[^x]: same\n"))))
  (testing "TASK-255 AC #3: different bodies under one source label in
            sibling and nested scopes are not substituted for each other"
    (doseq [[src bodies] [[(str ":::{note}\na[^n]\n\n[^n]: first body\n:::\n\n"
                                ":::{tip}\nb[^n]\n\n[^n]: second body\n:::\n\nc[^n]\n\n[^n]: third body\n")
                           ["first" "second" "third"]]
                          [(str "::::{outer}\na[^n]\n\n[^n]: outer body\n\n"
                                ":::{inner}\nb[^n]\n\n[^n]: inner body\n:::\n\n"
                                "c[^n]\n\n[^n]: tail body\n::::\n")
                           ["outer" "inner" "tail"]]]]
      (testing (pr-str src)
        (canonical-gates src)
        (is (= bodies (map #(-> % first :inlines first :text) (note-bodies (parser/parse src))))
            "the fixture really does hold distinct bodies (first words), in this order"))))
  (testing "TASK-255 AC #3: a note whose body holds another note, inside a
            directive and after it; a multi-paragraph note body in a
            directive and outside one"
    (doseq [src [(str ":::{note}\na[^o]\n\n[^o]: outer[^i] text\n\n[^i]: inner\n:::\n\n"
                      "b[^o]\n\n[^o]: outer2[^i]\n\n[^i]: inner2\n")
                 (str ":::{note}\na[^m]\n\n[^m]: First para.\n\n    Second para.\n\n    > quoted\n:::\n\n"
                      "after[^z]\n\n[^z]: one\n\n    two\n")]]
      (testing (pr-str src) (canonical-gates src))))
  (testing "TASK-255 AC #4: notes either side of an !include line, at top
            level and inside a directive; the :include stays unexpanded"
    (doseq [src ["a[^1]\n\n[^1]: before include\n\n!include x.hdoc\n\nb[^2]\n\n[^2]: after include\n"
                 ":::{note}\na[^1]\n\n[^1]: before\n\n!include x.hdoc\n\nb[^2]\n\n[^2]: after\n:::\n"]]
      (testing (pr-str src)
        (is (str/includes? (canonical-gates src) "\n!include x.hdoc\n"))
        (is (some #(= {:t :include :src "x.hdoc"} %)
                  (tree-seq coll? seq (parser/parse src)))))))
  (testing "TASK-255 AC #6: a plain document with no directive passes the
            same gates and keeps both note bodies"
    (let [out (canonical-gates "Para one[^a] and[^b].\n\nPara two[^a].\n\n[^a]: A.\n\n[^b]: B.\n")]
      (is (str/includes? out "]: A.\n") out)
      (is (str/includes? out "]: B.\n") out)))
  (testing "TASK-255 AC #6: undefined and cyclic references still fail to
            parse -- including a definition the other side of a directive
            or an include, which is outside its marker's chunk"
    (doseq [[src error-type] [["a[^nope]\n" ::parser/undefined-footnote]
                              [":::{note}\na[^1]\n:::\n\n[^1]: n\n" ::parser/undefined-footnote]
                              ["a[^1]\n\n:::{note}\nx\n:::\n\n[^1]: n\n" ::parser/undefined-footnote]
                              ["a[^1]\n\n!include x.hdoc\n\n[^1]: n\n" ::parser/undefined-footnote]
                              ["a[^x]\n\n[^x]: b[^x]\n" ::parser/cyclic-footnote]
                              ["a[^x]\n\n[^x]: b[^y]\n\n[^y]: c[^x]\n" ::parser/cyclic-footnote]
                              [":::{note}\na[^x]\n\n[^x]: b[^x]\n:::\n" ::parser/cyclic-footnote]]]
      (is (= error-type (try (parser/parse src) nil
                             (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))
          (pr-str src)))))

(deftest link-and-image-title-test
  (testing "TASK-140: a title survives parse, the hdoc round trip, HTML and
            JSON -- inline and reference-style, link and image, and with
            quote and backslash characters in it"
    (let [src (str "[a](u \"T\")\n\n"
                   "![i](p.png \"Say \\\"hi\\\" \\\\ ok\")\n\n"
                   "[r1][r] ![r2][r] [c](u)\n\n"
                   "[r]: v 'Ref title'\n")
          document (parser/parse src)
          inlines (mapcat :inlines (:blocks document))
          by-t (fn [t] (filter #(= t (:t %)) inlines))
          out (hdoc/emit-document document)]
      (is (= ["T" "Ref title" nil] (map :title (by-t :link))))
      (is (= ["Say \"hi\" \\ ok" "Ref title"] (map :title (by-t :image))))
      (is (ast/valid? ast/Document document))
      (is (= document (parser/parse out)) out)
      (is (not (contains? (last (by-t :link)) :title))
          "an untitled link carries no :title key at all")
      (let [html (html/emit-document document)]
        (is (str/includes? html "<a href=\"u\" title=\"T\">"))
        (is (str/includes? html "title=\"Say &quot;hi&quot; \\ ok\"")))
      (is (= document (json/json->ast (json/->json document)))))))

(deftest figure-title-test
  (testing "TASK-140: a standalone figure image keeps its title through hdoc"
    (let [document (parser/parse "![Cap](f.png \"Tip\"){#fig:f}\n")]
      (is (= "Tip" (-> document :blocks first :content :inlines first :title)))
      (is (= document (parser/parse (hdoc/emit-document document)))))))

(defn- code-para-doc
  "A document of one paragraph `a <code text> b`, built by hand."
  [text]
  {:meta {} :blocks [{:t :para :inlines [(str-inline "a") space {:t :code :text text}]}]})

(defn- refused-code-span
  "The ex-data when writing `document` is refused as an unrepresentable
  code span, else nil."
  [document]
  (try (hdoc/emit-document document) nil
       (catch clojure.lang.ExceptionInfo e
         (let [data (ex-data e)]
           (when (= ::hdoc/unrepresentable-code-span (:type data)) data)))))

(deftest code-span-edge-backtick-test
  (testing "TASK-226: a code text starting or ending with a backtick has no
            spelling (the parser keeps padding verbatim), so it is refused
            rather than written with a baked-in extra space"
    (doseq [text ["`x" "x`" "`" "``" "x\n```" "`x\ny" "a`b`"]]
      (let [data (refused-code-span (code-para-doc text))]
        (if (or (str/starts-with? text "`") (str/ends-with? text "`"))
          (is (= text (:text data)) (pr-str text))
          (is (nil? data) (pr-str text))))))
  (testing "an internal backtick run and a padded-by-the-author span still round-trip"
    (doseq [text ["a`b" "a``b" " x " " `x` " "x\ny`z"]]
      (let [d (code-para-doc text)]
        (is (= (:blocks d) (:blocks (parser/parse (hdoc/emit-document d)))) (pr-str text))))))

(deftest code-span-continuation-block-start-test
  (testing "TASK-222 AC #1: a continuation line that would start a block is
            refused, naming the line, in a paragraph"
    (doseq [[text line n] [["x\n- y" "- y" 2] ["x\n+ y" "+ y" 2] ["x\n1. y" "1. y" 2]
                           ["x\n1) y" "1) y" 2] ["x\n* y" "* y" 2] ["x\n# y" "# y" 2]
                           ["x\n> y" "> y" 2] ["x\n~~~" "~~~" 2] ["x\n  - y" "  - y" 2]
                           ["x\ny\n- z" "- z" 3] ["x\n- y\nz" "- y" 2]
                           ["x\n---\ny" "---" 2] ["x\n```\ny" "```" 2] ["x\n- \ny" "- " 2]
                           ["x\n\ny" "" 2] ["x\n  \ny" "  " 2] ["x\ny\n\n" "" 3]
                           ["x\n:::{.a}" ":::{.a}" 2] ["x\n::: y" "::: y" 2]
                           ["x\n<div>" "<div>" 2] ["x\n<p>y" "<p>y" 2] ["x\n<!-- y" "<!-- y" 2]
                           ["x\n[^1]: y" "[^1]: y" 2] ["x\ny\n  [^n]:" "  [^n]:" 3]]]
      (testing (pr-str text)
        (let [data (refused-code-span (code-para-doc text))]
          (is (some? data))
          (is (= line (:line data)))
          (is (= n (:line-number data)))))))
  (testing "the message names the line"
    (is (re-find #"line 2 \"- y\""
                 (try (hdoc/emit-document (code-para-doc "x\n- y"))
                      (catch clojure.lang.ExceptionInfo e (ex-message e))))))
  (testing "TASK-222 AC #1: in a figure caption, a heading, a block quote, a
            list item, a footnote, emphasis and a link"
    (let [code {:t :code :text "x\n- y"}
          wrap (fn [& inlines] {:t :para :inlines (vec inlines)})
          docs [{:meta {} :blocks [{:t :figure
                                    :content (wrap {:t :image :src "u" :title nil :alt "" :attr empty-attr})
                                    :caption [code]
                                    :attr (assoc empty-attr :id "fig:a")}]}
                {:meta {} :blocks [{:t :block-quote :blocks [(wrap code)]}]}
                {:meta {} :blocks [(wrap {:t :emph :inlines [code]})]}
                {:meta {} :blocks [(wrap {:t :link :target {:url "u"} :inlines [code]})]}
                {:meta {} :blocks [(wrap (str-inline "a") {:t :note :blocks [(wrap code)]})]}]]
      (doseq [d docs]
        (is (= "- y" (:line (refused-code-span d))) (pr-str d)))))
  (testing "TASK-222 AC #2: continuation lines that start no block emit unchanged"
    (doseq [src ["a `x\n2. y`\n" "a `x\n0. y`\n" "a `x\n-`\n" "a `x\n#`\n" "a `x\n#y`\n"
                 "a `x\n--- y`\n" "a `x\n---`\n" "a `x\n|---|`\n" "a `x\n    - y`\n"
                 "a `x\ny\nz`\n" "a `x\n-y`\n" "a `x\n1.y`\n" "a `x\n10. y`\n"
                 "a `x\n+`\n" "a `x\n`\n" "a `x\n  `\n" "a `x\n\\<div>`\n" "> a `x\n> 2. y`\n" "a `x\n\\- y`\n"]]
      (testing (pr-str src)
        (is (= src (emit-src src)))
        (is (true? (round-trips-from-source? src)))))))

(deftest code-span-trailing-newline-fence-test
  (testing "TASK-228: a text ending in a newline with a backtick run of two or
            more would write a 3+ backtick closing fence at line start; it is
            refused in a paragraph, setext heading, list item and link text"
    (let [set-code (fn [src text]
                     (walk/postwalk #(if (= :code (:t %)) (assoc % :text text) %)
                                    (parser/parse src)))]
      (doseq [text ["a ``\n" "``\n" "a ```\n" "a``b\nc ``\n"
                    ;; a blank last line: 0-3 spaces, any tab, or 4+ spaces then a tab
                    "a ``\n  " " ``\n " "a``\n " "\n``\n  " "a ``\n   " "a ``\n\t" "a ``\n \t"
                    "a ``\n  \t" "a ``\n    \t" "a ``\n\t\t"]
              src ["a `x` b\n" "a `x` b\n===\n" "# a `x` b\n" "- a `x` b\n" "[a `x`](u)\n"
                   "> a `x`\n" "*a `x`*\n"]]
        (let [d (set-code src text)
              data (refused-code-span d)]
          (is (some? data) (pr-str [text src]))
          (is (= text (:text data)) (pr-str [text src]))))))
  (testing "a last line of four or more plain spaces is no fence opener and round-trips"
    (doseq [text ["a ``\n    " "a ``\n      " "x``y\n     "]]
      (let [d (code-para-doc text)]
        (is (nil? (refused-code-span d)) (pr-str text))
        (is (= (:blocks d) (:blocks (parser/parse (hdoc/emit-document d)))) (pr-str text)))))
  (testing "a trailing newline with at most a single backtick still round-trips"
    (doseq [text ["a\n" "a `\n" "a`b\n" "a ` b\n" "\n" "a\nb\n" "a `\n  " "a\n   "]]
      (let [d (code-para-doc text)]
        (is (nil? (refused-code-span d)) (pr-str text))
        (is (= (:blocks d) (:blocks (parser/parse (hdoc/emit-document d)))) (pr-str text))))))

(deftest code-span-leading-tab-test
  (testing "TASK-229: a continuation line starting with a tab (after any
            spaces) is refused, naming the line, in every context"
    (let [set-code (fn [src text]
                     (walk/postwalk #(if (= :code (:t %)) (assoc % :text text) %)
                                    (parser/parse src)))]
      (doseq [[text line n] [["a\n\tx" "\tx" 2] ["a\n\t" "\t" 2] ["a\n \t" " \t" 2]
                             ["a\n  \tx" "  \tx" 2] ["a\n    \tx" "    \tx" 2]
                             ["a\nb\n\t\tx" "\t\tx" 3] ["a\n\tx\n\ty" "\tx" 2]]
              src ["a `x` b\n" "a `x` b\n===\n" "# a `x` b\n" "- a `x` b\n" "[a `x`](u)\n"
                   "> a `x`\n" "*a `x`*\n"]]
        (let [data (refused-code-span (set-code src text))]
          (is (some? data) (pr-str [text src]))
          (is (= line (:line data)) (pr-str [text src]))
          (is (= n (:line-number data)) (pr-str [text src]))))
      (is (re-find #"line 2 \"\\tx\""
                   (try (hdoc/emit-document (code-para-doc "a\n\tx"))
                        (catch clojure.lang.ExceptionInfo e (ex-message e)))))))
  (testing "spaces only, and a tab after text, still round-trip"
    (doseq [text ["a\n  x" "a\n    x" "a\n x" "a\nx\ty" "a\n  x\ty" "a\t\nb" "\ta\nb" "a\n    " "a\n  "]]
      (let [d (code-para-doc text)]
        (is (nil? (refused-code-span d)) (pr-str text))
        (is (= (:blocks d) (:blocks (parser/parse (hdoc/emit-document d)))) (pr-str text))))))

(defn- set-code-text
  "`src` parsed, with every `:code` text replaced by `text`."
  [src text]
  (walk/postwalk #(if (= :code (:t %)) (assoc % :text text) %) (parser/parse src)))

(deftest code-span-carriage-return-test
  (testing "TASK-231: CR next to a CR or newline is refused everywhere (the
            parser normalises it), any CR in a heading or table cell"
    (doseq [text ["a\r\nb" "a\r\rb" "a\n\rb"]
            src ["a `x` b\n" "# a `x` b\n" "- a `x` b\n" "> a `x`\n" "[a `x`](u)\n" "*`x`*\n"
                 "| a `x` |\n|---|\n| b |\n"]]
      (let [data (refused-code-span (set-code-text src text))]
        (is (true? (:carriage-return data)) (pr-str [text src]))))
    (doseq [text ["a\rb" "a\r" "\ra"]
            src ["# a `x` b\n" "| a `x` |\n|---|\n| b |\n"]]
      (is (true? (:carriage-return (refused-code-span (set-code-text src text)))) (pr-str [text src]))))
  (testing "a lone CR in a paragraph, list item, quote, footnote, emphasis or
            link is kept by the parser: a source holding one is emitted and
            re-parses to the same document (CRLF sources too)"
    (doseq [src ["a `x\ry` b\n" "`x\ry` b\n" "`\rx` b\n" "- a `x\ry` b\n" "- a\n  - `x\ry` b\n"
                 "> a `x\ry` b\n" "*a `x\ry`* b\n" "[a `x\ry`](u)\n" "a[^1]\n\n[^1]: a `x\ry`\n"
                 "a `x\r\ny` b\n" "- a `x\r\ny` b\n" "> a `x\r\ny`\n"
                 ;; nested containers and a setext heading, each round-tripping on HEAD
                 "1. a\n   - `x\ry\r  z` b\n" "a[^1]\n\n[^1]: - `x\ry\r  z`\n" "> - `x\ry\r  z`\n"
                 "`\na\r`\n===\n" "a `\r`\n` b\n===\n"]]
      (let [d (parser/parse src)]
        (is (some #(= :code (:t %)) (tree-seq coll? seq d)) (pr-str src))
        (is (= d (parser/parse (hdoc/emit-document d))) (pr-str src)))))
  (testing "a lone CR is a line end to the parser, so the line checks see it:
            each text is refused or re-parses to the same document"
    (doseq [text ["a\r\t" "\r\t" "a\r \n" " \r\t" "x\r``y" "a\r \nb" "x\r- y" "x\r# y" "x ``\r "]
            src ["a `x` b\n" "- a `x` b\n" "> a `x` b\n" "*a `x`*\n" "a[^1]\n\n[^1]: a `x`\n"]]
      (let [d (set-code-text src text)]
        (or (refused-code-span d)
            (is (= d (parser/parse (hdoc/emit-document d))) (pr-str [text src]))))))
  (testing "no-regression sweep (from the HEAD-vs-tree probe): parsed sources
            holding a CR in a code span, over contexts x texts of up to 3
            characters from backtick, space, a, LF, CR, tab and CRLF, are
            written and re-parse equal at least as often as on HEAD (the
            count below is HEAD's; some HEAD mismatches remain, now refused
            or still mismatching, which this does not assert on)"
    (let [alphabet ["`" " " "a" "\n" "\r" "\t" "\r\n"]
          texts (->> (iterate (fn [xs] (for [x xs a alphabet] (str x a))) [""])
                     (take 4) (apply concat) (filter #(str/includes? % "\r")))
          ctxs ["a %s b\n" "- a %s b\n" "1. a\n   - %s b\n" "> - %s b\n" "a[^1]\n\n[^1]: - %s\n" "*a %s* b\n"
                "[a %s](u)\n" "# a %s b\n" "a %s b\n===\n" "%s\n===\n" "> # a %s\n" "| a %s |\n|---|\n| b |\n"]
          freq (frequencies
                (for [c ctxs t texts
                      :let [s (format c (str "`" t "`"))
                            d (try (parser/parse s) (catch Exception _ nil))]
                      :when (some #(= :code (:t %)) (tree-seq coll? seq d))]
                  (if (refused-code-span d)
                    :refused
                    (try (if (= d (parser/parse (hdoc/emit-document d))) :ok (do (println "MISMATCH" (pr-str s)) :bad))
                         (catch clojure.lang.ExceptionInfo _ :other-refusal)))))]
      (is (<= 855 (:ok freq 0)) (pr-str freq))))
  (testing "a code text without one still round-trips"
    (let [d (code-para-doc "a\nb")]
      (is (nil? (refused-code-span d)))
      (is (= (:blocks d) (:blocks (parser/parse (hdoc/emit-document d))))))))

(deftest code-span-opening-fence-at-line-start-test
  (testing "TASK-231: a multi-line span whose text holds a run of two backticks
            and whose first line holds none, at the start of a line (first in
            a paragraph, list item, nested item, quote, heading, or after a
            soft break), would open a code block: refused"
    (doseq [text ["a\n``a" "a\n`` " "a\n``a\tb" "\n``a" "a\n``a\n``b" "a\n\n``a" "a\n`` \tb"]
            src ["`x` b\n" "- `x` b\n" "- a\n  - `x` b\n" "> `x` b\n" "# `x` b\n"
                 "a\n`x` b\n" "- a\n  b\n  `x` c\n"]]
      (let [data (refused-code-span (set-code-text src text))]
        (is (some? data) (pr-str [text src]))
        (is (or (:opening-fence-line-start data) (:leading-tab data)) (pr-str [text src])))))
  (testing "elsewhere it is written and round-trips: after other text on the
            line, in emphasis or link text, or with a backtick on the first line"
    (doseq [[text src] [["a\n``a" "a `x` b\n"] ["a\n``a" "- a `x` b\n"] ["a\n``a" "- a\n  - b `x` c\n"]
                        ["a\n``a" "*`x`* b\n"] ["a\n``a" "[`x`](u)\n"]
                        ["a`\n``b" "- `x` b\n"] ["a``b\nc" "`x` b\n"] ["a``b" "- a\n  - `x` b\n"]
                        ["a\nb" "- `x` b\n"] ["a\n`b" "- `x` b\n"]]]
      (let [d (set-code-text src text)]
        (is (nil? (refused-code-span d)) (pr-str [text src]))
        (is (= d (parser/parse (hdoc/emit-document d))) (pr-str [text src]))))))

(deftest code-span-continuation-fuzz-test
  (testing "TASK-231 fuzz (seed 231): over contexts (nested list, quote, heading,
            line start) and texts with CRs and backtick-pair starts, the
            writer refuses or round-trips -- neither side vacuous"
    (let [rng (java.util.Random. 231)
          pick (fn [xs] (nth xs (.nextInt rng (count xs))))
          srcs ["a `x` b\n" "`x` b\n" "- a `x` b\n" "- `x` b\n" "- a\n  - `x` b\n" "> `x` b\n"
                "# `x` b\n" "a\n`x` b\n" "*`x`* b\n" "[`x`](u)\n" "| `x` |\n|---|\n| b |\n"
                "a[^1]\n\n[^1]: a `x`\n"]
          starts ["" "``" "`` " "``a" "a``" "a" "a`" " " "\t" "``\t" "- " "x" "# " "> " "---" "``` "]
          results (doall
                   (for [_ (range 1500)]
                     (let [n (inc (.nextInt rng 3))
                           ;; separators: LF, a lone CR (a line end to the parser), CRLF
                           text (apply str (pick ["a" "" "a b" "a`"])
                                       (repeatedly n #(str (pick ["\n" "\r" "\r" "\r\n"]) (pick starts) (pick ["" "y" "z w"]))))
                           ;; No two-space line after a CR: in a list item or footnote the
                           ;; container strips its indent from it (hand-built only).
                           document (set-code-text (pick srcs) text)
                           refused (refused-code-span document)]
                       (cond (:carriage-return refused) :refused-cr
                             (:opening-fence-line-start refused) :refused-fence
                             refused :refused
                             :else (let [emitted (try (hdoc/emit-document document) (catch Exception _ nil))]
                                     ;; nil: refused for another reason (a newline in a table cell)
                                     (cond (nil? emitted) :refused-other
                                           (= document (parser/parse emitted))
                                           (if (re-find #"\r" text) :ok-cr :ok)
                                           :else (do (println "MISMATCH" (pr-str text) (pr-str emitted)) :bad)))))))
          freq (frequencies results)]
      (is (zero? (:bad freq 0)) (pr-str freq))
      (is (< 10 (:refused-cr freq 0)) (pr-str freq))
      (is (< 10 (:refused-fence freq 0)) (pr-str freq))
      (is (< 100 (:ok-cr freq 0)) "lone-CR texts that are written and round-trip")
      (is (< 50 (:ok freq 0)) (pr-str freq)))))

(defn- has-code? [d]
  (boolean (some #(and (map? %) (= :code (:t %))) (tree-seq coll? seq d))))

(deftest code-span-line-end-indent-test
  (testing "TASK-232: a list item or footnote strips its indent from the line
            after a lone CR as it does after a newline, so the writer indents
            after a CR too; each parsed source re-parses to the same document"
    (doseq [src ["- a `x\r\ty` b\n" "- a `x\r    y` b\n" "- a `x\r    y\r  z` b\n"
                 "1. a\n   - `x\r      y`\n" "- a\n\n  b `x\r    y`\n"
                 "a[^1]\n\n[^1]: a `x\r        y`\n" "a[^1]\n\n[^1]: a\n\n    b `x\r        y`\n"
                 ;; CRLF and LF in a footnote whose first block is a list
                 "a[^1]\n\n[^1]: - `\r\n`\n" "a[^1]\n\n[^1]: - `a\r\nb`\n" "a[^1]\n\n[^1]: - `a\n  b`\n"
                 "a[^1]\n\n[^1]: 1. `a\n b`\n" "a[^1]\n\n[^1]: - `a\r    b`\n"
                 ;; a line after a CR in a quote is lazy: no indent added there
                 "> - `x\r  y`\n" "- > `x\r  y`\n"]]
      (let [d (parser/parse src)]
        (is (has-code? d) (pr-str src))
        (is (= d (parser/parse (hdoc/emit-document d))) (pr-str src)))))
  (testing "no parsed source that round-trips as a list item, footnote or quote
            with a code span holding a CR or newline is refused or broken, and
            (seed 232) the sweep is not vacuous"
    (let [alphabet ["`" " " "a" "\n" "\r" "\t" "\r\n" "  " "    "]
          rng (java.util.Random. 232)
          pick (fn [xs] (nth xs (.nextInt rng (count xs))))
          ctxs ["- a %s b\n" "1. a\n   - %s b\n" "> - %s b\n" "- > %s\n" "a[^1]\n\n[^1]: a %s\n"
                "a[^1]\n\n[^1]: - %s\n" "a[^1]\n\n[^1]: 1. a %s\n\n    b\n" "a[^1]\n\n[^1]: a\n\n    - b %s\n"
                "- a\n\n  b %s\n" "- a\n  - %s b\n" "> a %s\n"]
          freq (frequencies
                (for [_ (range 1500)
                      :let [t (apply str (repeatedly (inc (.nextInt rng 5)) #(pick alphabet)))
                            s (format (pick ctxs) (str "`" t "`"))
                            d (try (parser/parse s) (catch Exception _ nil))]
                      ;; the span itself must hold the line end (a backtick in `t` can end it early)
                      :when (some #(and (map? %) (= :code (:t %)) (re-find #"[\r\n]" (:text %)))
                                  (tree-seq coll? seq d))]
                  (if (refused-code-span d)
                    :refused
                    (if (= d (parser/parse (hdoc/emit-document d)))
                      (if (re-find #"\r" t) :ok-cr :ok)
                      (do (println "MISMATCH" (pr-str s)) :bad)))))]
      (is (zero? (:bad freq 0)) (pr-str freq))
      (is (< 100 (:ok-cr freq 0)) (pr-str freq))
      (is (< 100 (:ok freq 0)) (pr-str freq))))
  (testing "an empty code text has no spelling (two backticks are text): refused"
    (is (true? (:empty (refused-code-span (code-para-doc "")))))
    (is (true? (:empty (refused-code-span (set-code-text "- a `x` b\n" "")))))))

(deftest code-span-continuation-fuzz-test-222
  (testing "TASK-222 fuzz (seed 222): over generated multi-line spans the
            writer either refuses, or writes something that parses back to
            the very same document -- and neither side is vacuous"
    (let [rng (java.util.Random. 222)
          pick (fn [xs] (nth xs (.nextInt rng (count xs))))
          starts ["" " " "  " "- " "-" "+ " "* " "1. " "1) " "2. " "0. " "# " "#" "> " ">" "~~~" "```"
                  "---" "===" "***" "|---|" "a" "$$" "    " ":::" "<" "<div>" "<!--" "[^" "[^1]: "
                  "``" "a ``" "a `" "   " "      "
                  ;; TASK-229: tabs
                  "\t" " \t" "    \t" "\t- " "a\t" "\t\t"]
          results (doall
                   (for [_ (range 400)]
                     (let [n (inc (.nextInt rng 3))
                           ;; TASK-228: some texts end in a newline.
                           text (str (str/join "\n" (cons "x" (repeatedly n #(str (pick starts) (pick ["" "y" " y" "z w"])))))
                                     (when (zero? (.nextInt rng 3)) "\n"))
                           document (code-para-doc text)
                           refused (refused-code-span document)]
                       (if refused
                         (cond (:trailing-newline-fence refused) :refused-trailing
                               (:leading-tab refused) :refused-tab
                               :else :refused)
                         (let [emitted (hdoc/emit-document document)]
                           (if (= (-> document :blocks first :inlines)
                                  (some-> emitted parser/parse :blocks first :inlines))
                             (if (= 1 (count (:blocks (parser/parse emitted)))) :ok :bad)
                             (do (println "MISMATCH" (pr-str text) (pr-str emitted)) :bad)))))))
          freq (frequencies results)]
      (is (zero? (:bad freq 0)) (pr-str freq))
      (is (< 20 (:refused freq 0)) (pr-str freq))
      (is (< 5 (:refused-trailing freq 0)) (pr-str freq))
      (is (< 5 (:refused-tab freq 0)) (pr-str freq))
      (is (< 20 (:ok freq 0)) (pr-str freq)))))

(deftest at-token-with-underscore-round-trip-test
  (testing "TASK-223 AC #1: text holding an `@` token that is not a reference
            is written so the part before an escaped `_` does not read as one"
    (doseq [[text out] [["@0_é1:1" "\\@0\\_é1:1"]
                        ["x@a:b_c" "x@a:b\\_c"]
                        ["@ab_c:d" "\\@ab\\_c:d"]
                        ;; Unchanged: a reference was already escaped, and
                        ;; an `@` after a word character is prose.
                        ["@_x" "\\@\\_x"]
                        ["me@x_y" "me@x\\_y"]
                        ["@x_y" "\\@x\\_y"]
                        ["a_b" "a\\_b"]]]
      (let [document {:meta {} :blocks [(para (str-inline text))]}]
        (is (= (str out "\n") (hdoc/emit-document document)) (pr-str text))
        (is (= (:blocks document) (:blocks (parser/parse (hdoc/emit-document document)))) (pr-str text)))))
  (testing "in link text and a bracket that is no citation"
    (doseq [text ["@0_é1:1" "[@ααc1:__:c_cÄ_ß]" "a@0_é1:1"]]
      (doseq [inline [(str-inline text)
                      {:t :link :target "u" :attr empty-attr :inlines [(str-inline text)]}]]
        (let [document {:meta {} :blocks [(para inline)]}]
          (is (= (:blocks document) (:blocks (parser/parse (hdoc/emit-document document)))) (pr-str text))))))
  (testing "TASK-223 AC #2 seeded fuzz: text over `@`, `_`, `:`, `-`, letters
            and digits round-trips, plain and in link text"
    (let [rng (java.util.Random. 223)
          alphabet "@@__::-ab09éα*"
          rand-text (fn [] (apply str (repeatedly (+ 2 (.nextInt rng 9))
                                                  #(.charAt alphabet (.nextInt rng (count alphabet))))))
          results (for [text (repeatedly 3000 rand-text)
                        wrap [identity #(vector {:t :link :target "u" :attr empty-attr :inlines %})]
                        :let [document {:meta {} :blocks [{:t :para :inlines (vec (wrap [(str-inline text)]))}]}
                              e (emit-error document)]]
                    (cond
                      e :refused
                      (= (:blocks document) (:blocks (parser/parse (hdoc/emit-document document)))) :ok
                      :else (do (println "MISMATCH" (pr-str text)) :bad)))
          freq (frequencies results)
          hits (count (filter #(re-find #"@[^_]*_" %) (repeatedly 3000 rand-text)))]
      (is (zero? (:bad freq 0)) (pr-str freq))
      (is (< 3000 (:ok freq 0)) (pr-str freq))
      (is (< 500 hits) "enough texts hold an `@` before an `_` that the fuzz is not vacuous"))))

(deftest trailing-tab-and-footnote-code-block-test
  (testing "TASK-233: a tab before a line end is a :space the parser keeps (trailing
            spaces it drops), so the writer writes the tab back"
    (doseq [src ["a `x`\t\nb\n" "a\t\nb\n" "a \t\nb\n" "a\t\t\nb\n" "- a\t\n  b\n" "*a\t\nb*\n"
                 "a[^1]\n\n[^1]: a\t\n    b\n" "> a\t\n> b\n" "a\t\r\nb\n" "- a `\t\nx`\t\nb\n"]]
      (let [d (parser/parse src)]
        (is (= d (parser/parse (hdoc/emit-document d))) (pr-str src))))
    (is (= "a\t\nb\n" (hdoc/emit-document (parser/parse "a\t\nb\n"))))
    (is (= "a c\nb\n" (hdoc/emit-document {:meta {} :blocks [{:t :para :inlines [{:t :str :text "a"} {:t :space}
                                                                                 {:t :str :text "c"} {:t :soft-break}
                                                                                 {:t :str :text "b"}]}]}))
        "a :space not directly before a line end stays a space"))
  (testing "TASK-233: a footnote whose first block is an indented code block"
    (doseq [src ["a[^1]\n\n[^1]:     - a\n" "a[^1]\n\n[^1]:     code\n" "a[^1]\n\n[^1]:     a\n      b\n"
                 "a[^1]\n\n[^1]:      - a\n" "a[^1]\n\n[^1]:    - a\n" "a[^1]\n\n[^1]:     a\n\n         b\n"
                 "a[^1]\n\n[^1]: ```\n     - a\n    ```\n" "a[^1]\n\n[^1]: ```js\n      x\n\n y\n    ```\n"]]
      (let [d (parser/parse src)]
        (is (some #(and (map? %) (= :code-block (:t %))) (tree-seq coll? seq d)) (pr-str src))
        (is (= d (parser/parse (hdoc/emit-document d))) (pr-str src)))))
  (testing "TASK-233: a footnote-first code block that already round-tripped is
            written byte-identically (the extra column only for a body line
            that starts with a space)"
    (doseq [src ["a[^1]\n\n[^1]: ```\n    x\n    ```\n"
                 "a[^1]\n\n[^1]: ````\n    x ```\n    y\n    ````\n"
                 "a[^1]\n\n[^1]: ```js\n    x\n\n    y\n    ```\n"
                 "a[^1]\n\n[^1]: ```\n    ```\n"
                 "a[^1]\n\n[^1]: ```\n\n    ```\n"
                 "a[^1]\n\n[^1]: ```\n    x\n    ```\n\n    b\n"]]
      (let [d (parser/parse src)]
        (is (= src (hdoc/emit-document d)) (pr-str src))
        (is (= d (parser/parse src)) (pr-str src)))))
  (testing "seeded sweep (seed 233): tab line ends and footnote-first code blocks round-trip, not vacuously"
    (let [rng (java.util.Random. 233)
          pick (fn [xs] (nth xs (.nextInt rng (count xs))))
          ctxs ["%s\n" "- a %s\n" "> a %s\n" "a[^1]\n\n[^1]: a %s\n" "*a %s*\n" "a[^1]\n\n[^1]:     %s\n"
                "a[^1]\n\n[^1]:      %s\n" "a[^1]\n\n[^1]: ```\n     %s\n    ```\n" "a[^1]\n\n[^1]: x\n\n         %s\n"]
          alphabet ["a" " " "\t" "\n" "`x`" "*b*" "  " "-"]
          freq (frequencies
                (for [_ (range 2500)
                      :let [t (apply str (repeatedly (inc (.nextInt rng 5)) #(pick alphabet)))
                            s (format (pick ctxs) t)
                            d (try (parser/parse s) (catch Exception _ nil))]
                      :when (and d (not (re-find #"(?m)^ *\t" s)))]
                  (cond (not= d (parser/parse (hdoc/emit-document d))) (do (println "MISMATCH" (pr-str s)) :bad)
                        (re-find #"\t\n" s) :ok-tab
                        (re-find #"\[\^1\]: +(```|\S)" s) :ok-fn
                        :else :ok)))]
      (is (zero? (:bad freq 0)) (pr-str freq))
      (is (< 100 (:ok-tab freq 0)) (pr-str freq))
      (is (< 100 (:ok-fn freq 0)) (pr-str freq)))))

(deftest footnote-in-list-and-quoted-fence-lines-test
  (testing "TASK-234: a footnote defined inside a one-item loose list is written there"
    (doseq [src ["- a[^1]\n\n  [^1]: aa\n" "- a[^1]\n\n  [^1]:     -aa\n" "1. a[^1]\n\n   [^1]: aa\n"
                 "- a[^1] b[^2]\n\n  [^1]: x\n\n  [^2]: y\n" "> - a[^1]\n>\n>   [^1]: aa\n"
                 "- a[^1]\n\n  [^1]: x\n\n      code\n"]]
      (let [d (parser/parse src)]
        (is (= d (parser/parse (hdoc/emit-document d))) (pr-str src)))))
  (testing "TASK-234: whitespace-only lines of a fenced block in a quote keep their text"
    (doseq [src ["> ```\n> \t \t \n> ```" "> ```\n>   \n> ```" "> ```\n> a\n>  \n> b\n> ```"
                 "> - ```\n>   \t\n>   ```\n"]]
      (let [d (parser/parse src)]
        (is (= d (parser/parse (hdoc/emit-document d))) (pr-str src)))))
  (testing "TASK-234: output that already round-tripped is unchanged"
    (doseq [src ["- a[^1]\n\n- b\n\n[^1]: aa\n" "> a[^1]\n\n[^1]: aa\n"
                 "> ```\n> a\n>\n> b\n> ```\n" "> a\n>\n> b\n"]]
      (let [d (parser/parse src)
            e (hdoc/emit-document d)]
        (is (= d (parser/parse e)) (pr-str src))
        (is (= src e) (pr-str src)))))
  (testing "TASK-234: a footnote code line of only spaces and tabs has no spelling and is refused"
    (let [d (parser/parse "a[^1]\n\n[^1]: ```\n\ta    \n    ```\n")]
      (is (= " \n" (-> d :blocks first :inlines last :blocks first :text)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"only spaces or tabs"
                            (hdoc/emit-document d)))))
  (testing "seeded sweep (seed 234): none is bad, and the paths are hit"
    (let [rng (java.util.Random. 234)
          pick (fn [xs] (nth xs (.nextInt rng (count xs))))
          ctxs ["- a[^1]\n\n  [^1]: %s\n" "1. a[^1]\n\n   [^1]: %s\n" "> ```\n> %s\n> ```\n"
                "> - ```\n>   %s\n>   ```\n" "- a[^1]\n\n  [^1]: x\n\n      %s\n"
                "a[^1]\n\n[^1]: ```\n%s\n    ```\n" "- a[^1]\n\n  [^1]: ```\n      %s\n      ```\n"]
          alphabet ["a" " " "\t" "\n" "-" "    " "b"]
          freq (frequencies
                (for [_ (range 2500)
                      :let [t (apply str (repeatedly (inc (.nextInt rng 4)) #(pick alphabet)))
                            s (format (pick ctxs) t)
                            d (try (parser/parse s) (catch Exception _ nil))
                            e (when d (try (hdoc/emit-document d) (catch clojure.lang.ExceptionInfo _ ::refused)))]
                      :when (and d (not (re-find #"\r" s)))]
                  (cond (= ::refused e) :refused
                        (not= d (parser/parse e)) (do (println "MISMATCH" (pr-str s)) :bad)
                        (re-find #"^- a\[\^1\]" s) :ok-list-note
                        (re-find #"(?m)^> .*[ \t]+$|^>\s*[ \t]\n" s) :ok-quote-ws
                        :else :ok)))]
      (is (zero? (:bad freq 0)) (pr-str freq))
      (is (< 100 (:ok-list-note freq 0)) (pr-str freq))
      (is (< 20 (:ok-quote-ws freq 0)) (pr-str freq)))))

(deftest footnote-in-nested-list-test
  (testing "TASK-235: a footnote defined in the outer item of a nested list is written there"
    (doseq [src ["- - a[^1]\n\n  [^1]: x\n" "- - - a[^1]\n\n  [^1]: x\n" "1. - a[^1]\n\n   [^1]: x\n"
                 "> - - a[^1]\n>\n>   [^1]: x\n"
                 "- a[^1] b[^2]\n\n  [^1]: \n\n  [^2]: y\n\n* - \n"
                 "- a[^1]\n\n  [^1]: x\n\n* b\n"]]
      (let [d (parser/parse src)
            e (hdoc/emit-document d)]
        (is (= d (parser/parse e)) (pr-str src))
        (is (= src e) (pr-str src)))))
  (testing "TASK-235: output that already round-tripped is unchanged"
    (doseq [src ["- - a[^1]\n\n    [^1]: x\n" "- a[^1]\n\n- b\n\n[^1]: x\n" "- - a\n\n- b[^1]\n\n[^1]: x\n"]]
      (let [d (parser/parse src)
            e (hdoc/emit-document d)]
        (is (= d (parser/parse e)) (pr-str src))
        (is (= src e) (pr-str src))))))

(defn- emphasis-round-trip
  "`[emitted round-trips?]` for the parsed `src`."
  [src]
  (let [d (parser/parse src)
        e (hdoc/emit-document d)]
    [e (= d (parser/parse e))]))

(deftest adjacent-and-nested-emphasis-round-trip-test
  (testing "TASK-237: emphasis that touches another is written with the other delimiter"
    (doseq [[src expected] [["*e*_e_\n" "*e*_e_\n"]
                            ["_e_*e*\n" "*e*_e_\n"]
                            ["*_e_* b\n" "*_e_* b\n"]
                            ["*@k#_[-_* b\n" "*@k\\#_\\[-_* b\n"]
                            ["*a*_b_*c*\n" "*a*_b_*c*\n"]
                            ["__a__**b**\n" "**a**__b__\n"]
                            ["_*a*_\n" "*_a_*\n"]
                            ["**_a_**\n" "**_a_**\n"]
                            ["_e_*e*@k\n" "_e_*e*@k\n"]]]
      (let [[e ok?] (emphasis-round-trip src)]
        (is ok? (pr-str src))
        (is (= expected e) (pr-str src)))))
  (testing "TASK-237: a spelling that already round-tripped is written as before"
    (doseq [[src expected] [["***a***\n" "***a***\n"] ["**a**__b__ c\n" "**a**__b__ c\n"]
                            ["*a **b***\n" "*a **b***\n"] ["**a _b_**\n" "**a *b***\n"]
                            ["*a* _b_\n" "*a* *b*\n"] ["*a*x\n" "*a*x\n"] ["*a***b**\n" "*a***b**\n"]
                            ["*1. *e** b\n" "*1. *e** b\n"] ["****c****\n" "****c****\n"]
                            ["*_a_ b*\n" "**a* b*\n"] ["*b _a_*\n" "*b *a**\n"]
                            ["*a*~~b~~\n" "*a*~~b~~\n"] ["==a====b==\n" "==a====b==\n"]]]
      (let [[e ok?] (emphasis-round-trip src)]
        (is ok? (pr-str src))
        (is (= expected e) (pr-str src)))))
  (testing "TASK-237: when no spelling survives the writer refuses instead of writing another document"
    (let [src "_1*ab*__\n"
          ex (try (hdoc/emit-document (parser/parse src)) (catch clojure.lang.ExceptionInfo x x))]
      (is (instance? clojure.lang.ExceptionInfo ex) (pr-str src))
      (is (= ::hdoc/unrepresentable-emphasis (:type (ex-data ex))))))
  (testing "TASK-237: seeded sweep (seed 237): every source round-trips or is refused, not vacuously"
    (let [rng (java.util.Random. 237)
          toks ["*" "_" "**" "__" "a" "b" "ab" " " "." "x y" "1" "*" "_"]
          freq (frequencies
                (for [_ (range 6000)
                      :let [s (str (apply str (repeatedly (+ 2 (.nextInt rng 10)) #(nth toks (.nextInt rng (count toks))))) "\n")
                            d (try (parser/parse s) (catch Exception _ nil))
                            e (when d (try (hdoc/emit-document d)
                                           (catch clojure.lang.ExceptionInfo x
                                             (when (= ::hdoc/unrepresentable-emphasis (:type (ex-data x))) ::refused))))]
                      :when d]
                  (cond (= ::refused e) :refused
                        (= d (parser/parse e)) (if (re-find #"_" e) :ok-underscore :ok)
                        :else (do (println "MISMATCH" (pr-str s) (pr-str e)) :bad))))]
      (is (zero? (:bad freq 0)) (pr-str freq))
      (is (< 1000 (:ok freq 0)) (pr-str freq))
      (is (< 20 (:ok-underscore freq 0)) (pr-str freq)))))

;; --- TASK-258: one long line emits in linear time ----------------------------

(deftest long-line-emit-time-test
  (testing "TASK-258: a one-line paragraph of ~136k characters, emphasis and book
            references throughout, is written back in time linear in its length.
            Each Inline copied everything written so far (`(str text inline-text)`)
            and walked it again for its last character (`(last text)`), so this
            took ~22 s on JDK 17; now ~1 s. No code spans: on JDK 17 flexmark's
            own backtick matching is quadratic (TASK-253)."
    (let [src (str (str/join " " (repeat (quot 136000 13) "*a* @{b:x} w")) "\n")
          doc (parser/parse src)
          t (System/nanoTime)
          out (hdoc/emit-document doc)
          secs (/ (- (System/nanoTime) t) 1e9)]
      (is (< secs 8.0) (str (count src) " characters on one line took " secs " s to emit"))
      (testing "and the output is the source itself"
        (is (= src out)))))
  (testing "TASK-258: what the emitter reads off the text written so far -- its last
            character -- still decides `_` (not after a letter or digit) and an
            escaped `{` after `@` (TASK-152)"
    (doseq [[src out] [["*a* _b_\n" "*a* *b*\n"] ["x*a*_b_\n" "x*a*_b_\n"]
                       ["1*a*_b_\n" "1*a*_b_\n"] [". *a*_b_\n" ". *a*_b_\n"]
                       ["x@\\{b:x}\n" "x@\\{b:x}\n"] ["x@ \\{b:x}\n" "x@ \\{b:x}\n"]]]
      (is (= out (emit-src src)) (pr-str src)))))

;; --- TASK-261: very many Blocks emit in linear time ---------------------------

(deftest many-blocks-emit-time-test
  (testing "TASK-261: 20k code blocks (~4.2 MB of source) are written back in
            time linear in their number. Each Block copied everything written so
            far (`(str text \"\\n\\n\" block-text)`), so this took ~14 s on JDK 17;
            now well under a second. Code blocks, because each is cheap to render
            on its own and large to copy."
    (let [block (str "```\n" (apply str (repeat 200 "x")) "\n```\n")
          src (str/join "\n" (repeat 20000 block))
          doc (parser/parse src)
          t (System/nanoTime)
          out (hdoc/emit-document doc)
          secs (/ (- (System/nanoTime) t) 1e9)]
      (is (< secs 5.0) (str "20000 code blocks took " secs " s to emit"))
      (testing "and the output is the source itself"
        (is (= src out)))))
  (testing "TASK-261: a footnote of very many Blocks, and a loose list item of very
            many Blocks, are joined with one builder too, every Block written in
            order (5000 of each is a correctness check with a bound, not a
            regression trap: the old joins only became slow well beyond it)"
    (let [n 5000
          note-src (str "a[^1]\n\n[^1]: p0\n" (str/join (map #(str "\n    p" % "\n") (range 1 n))))
          item-src (str "- p0\n" (str/join (map #(str "\n  p" % "\n") (range 1 n))))
          [note-doc item-doc] (map parser/parse [note-src item-src])
          t (System/nanoTime)
          outs (mapv hdoc/emit-document [note-doc item-doc])
          secs (/ (- (System/nanoTime) t) 1e9)]
      (is (< secs 5.0) (str n "-block footnote and list item took " secs " s to emit"))
      (is (= [note-src item-src] outs))))
  (testing "TASK-261: the builder joins exactly as `join-blocks` folded over the same
            pieces does -- a blank text so far (nil, empty, whitespace) is REPLACED by
            the next piece, not appended to -- over every sequence of up to four
            pieces drawn from nil, \"\", \" \", \"\\n\", \"a\" and \" b\""
    (let [join-blocks @#'hdoc/join-blocks
          joiner @#'hdoc/blank-line-joiner
          pieces [nil "" " " "\n" "a" " b"]
          seqs (reduce (fn [acc _] (into acc (for [s acc :when (< (count s) 4) p pieces] (conj s p))))
                       #{[]} (range 4))]
      (is (= 1555 (count seqs)) "every sequence of length 0-4")
      (doseq [s seqs]
        (let [join! (joiner)]
          (run! join! s)
          (is (= (reduce join-blocks "" s) (join!)) (pr-str s)))))))

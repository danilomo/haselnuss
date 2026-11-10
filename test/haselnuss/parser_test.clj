(ns haselnuss.parser-test
  "Golden-fixture tests for haselnuss.parser (TASK-4, TASK-5): a source
  string parsed and compared against its expected AST with `=`, with every
  fixture also checked valid against `ast/Document`. Covers front matter ->
  Meta (AC #1), heading nesting into Section blocks across increasing,
  decreasing, skipped, and skip-down-then-partially-back-up levels (AC #2),
  one fixture per CommonMark construct in scope (AC #3) including
  reference-style/shortcut links and images and their undefined-reference
  fallback, CommonMark backslash-escape resolution in plain text, image alt
  text, and link/image destination URLs (inline and reference-style alike),
  a combined document exercising several constructs together, a negative
  case for an unsupported construct (TASK-4 ACs); and TASK-5's attribute
  groups (`{#id .class key=val}`, sec4.1/5.3) attaching to a heading's
  Section, an image, and (via Span, since Code has no Attr field) an inline
  code span, bracketed text producing an inline Span (sec5.6), and malformed
  attribute groups raising a diagnostic rather than being silently dropped
  (TASK-5 ACs #1-#4); plus four review-driven regression fixtures added
  after a TASK-5 review pass: a heading attribute group with no space
  before the `{` (previously crashed on flexmark's internal `TextBase`
  node instead of attaching, AC #1), ordinary unrelated prose that merely
  precedes a well-formed `{...}` group (same underlying crash, worse since
  the text was never intended to invoke this syntax at all), full/
  collapsed reference-style links (`[text][ref]`/`[text][]`) followed by
  an attribute group keeping their resolved link target instead of
  silently becoming a target-less Span (a regression against TASK-4), and
  a fenced code block's info-string attribute-group attempt raising a
  diagnostic instead of silently dropping or corrupting `:lang`; plus
  TASK-6's block directives (sec5.5) -- a minimal three-colon fence with a
  name and id attribute producing a Directive node (AC #1), a directive
  nested inside another via a longer outer colon-run (AC #2), an
  unterminated directive fence raising a diagnostic (AC #3), a bare fence
  with no header at all, a directive sitting among ordinary prose/headings
  at the same level (confirming section-folding still spans correctly
  across it), and a malformed directive header raising the same
  no-silent-drop diagnostic philosophy as TASK-5's attribute groups; plus
  TASK-7's math syntax (sec5.7) -- single-`$`-delimited inline math with
  its raw TeX preserved exactly, including LaTeX characters CommonMark
  itself gives other meaning (backslash, braces, caret, underscore,
  asterisk) (AC #1); single-line and multi-line `$$ ... $$` display math
  (AC #2); an id (and combined id/class) attribute group attaching to a
  MathBlock's Attr (AC #3); plain currency/dollar text -- including the
  well-known pandoc `$20,000 and $30,000` case, a backslash-escaped `\\$`,
  and a stray mid-paragraph `$$` -- staying literal instead of being
  misparsed as math, and an unterminated multi-line display math attempt
  falling back to literal text the same way rather than raising a
  diagnostic, since `$` collides with plain-text use far more often than
  a directive's own dedicated colon-fence syntax does (AC #4); a
  malformed MathBlock attribute group raising the same no-silent-drop
  diagnostic philosophy as TASK-5/TASK-6's own attribute/header groups;
  and TASK-8's figures/pipe tables (sec5.8/5.9) -- a standalone image with
  an id becoming a numbered Figure whose caption is its own alt text (AC
  #1), a standalone image with no id (with or without other attributes)
  staying an ordinary Para/Image (AC #3); a pipe table with a trailing
  `: caption {#id}` line becoming a Table with head row, body rows,
  per-column alignment, caption, and Attr (AC #2), a pipe table with no
  such trailer still parsing to a Table node with an empty caption and no
  id (AC #3), and a `:`-shaped paragraph separated from a table by a
  blank line not being absorbed as its caption (confirms the adjacency
  check isn't over-eager); and TASK-9's cross-reference/citation syntax
  (sec5.10/5.11) -- a bare `@prefix:label` reference parsing to CrossRef
  with that label (AC #1), a single bracketed citation combining a
  locator/suffix with multiple `;`-separated keys parsing to one Cite node
  with one CiteItem per key (AC #2), a bare author-in-text citation
  parsing to a Cite node with mode `:author` (AC #3), and bare CrossRefs/
  citations side by side with an ordinary link and an ordinary bracketed
  shortcut reference, confirming none of the four is misread as another
  (AC #4); plus five TASK-9 review-driven regression fixtures: a
  citation segment's prefix/suffix text that mini-parses to a
  non-Paragraph block (list item, heading, blockquote, code fence,
  thematic break) neither crashing the whole parse nor silently dropping
  its literal text, a bracket made up only of `;` characters correctly
  declined instead of misread as a zero-CiteItem citation, a
  backslash-escaped `[\\@doe99]` staying fully literal like the bare
  `\\@` form already does, and a bare colon-containing `@token` that
  doesn't look like a plausible kind prefix (e.g. `@3:00pm`) staying
  literal text instead of becoming a nonsensical CrossRef; and TASK-10's
  footnote syntax (sec5.12) -- a `[^label]` marker paired with a matching
  `[^label]: ...` definition elsewhere producing an inline Note carrying
  the definition's own Blocks at the marker's position (AC #1), an
  undefined marker raising a parse diagnostic rather than falling back to
  literal text (AC #2), and multiple footnotes in one document -- markers
  side by side, definitions written afterward in the opposite order --
  each resolving to their own correct definition (AC #3)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [haselnuss.ast :as ast]
            [haselnuss.parser :as parser]
            [haselnuss.resolver :as resolver]))

(def attr
  "A minimal but complete Attr, matching every node this parser produces
  with no attribute group (TASK-5, sec4.1/5.3) attached."
  {:classes [] :props {}})

(defn- str-inline
  "A `:str` Inline wrapping `text`."
  [text]
  {:t :str :text text})

(def space
  "A `:space` Inline, matching `text->inlines`'s output for any interior
  whitespace run."
  {:t :space})

(deftest front-matter-test
  (testing "a front-matter block populates every named Meta field (AC #1)"
    (let [source (str "---\n"
                      "title: On Hazelnuts\n"
                      "author: [Danilo Oliveira, Jane Doe]\n"
                      "date: 2026-07-24\n"
                      "bibliography: refs.bib\n"
                      "cslStyle: apa\n"
                      "lang: pt-BR\n"
                      "---\n"
                      "Body.\n")
          doc (parser/parse source)]
      (is (= {:title [(str-inline "On") space (str-inline "Hazelnuts")]
              :authors ["Danilo Oliveira" "Jane Doe"]
              :date "2026-07-24"
              :bibliography "refs.bib"
              :csl-style "apa"
              :lang "pt-BR"}
             (:meta doc)))
      (is (ast/valid? ast/Document doc))))
  (testing "a singular `authors` key and a scalar (non-list) author both work"
    (is (= ["Solo Author"] (:authors (:meta (parser/parse "---\nauthors: Solo Author\n---\nx\n")))))
    (is (= ["Solo Author"] (:authors (:meta (parser/parse "---\nauthor: Solo Author\n---\nx\n"))))))
  (testing "no front matter yields an empty Meta"
    (is (= {} (:meta (parser/parse "Just a paragraph.\n")))))
  (testing "TASK-53: topLevelDivision names the top-level division, and
            its absence leaves Meta exactly as before -- a document that
            never heard of the key must not acquire a field"
    (is (= :chapter (:top-level-division
                     (:meta (parser/parse "---\ntopLevelDivision: chapter\n---\nx\n")))))
    (is (= :section (:top-level-division
                     (:meta (parser/parse "---\ntopLevelDivision: section\n---\nx\n")))))
    (is (not (contains? (:meta (parser/parse "---\nlang: en\n---\nx\n")) :top-level-division)))
    (is (ast/valid? ast/Document (parser/parse "---\ntopLevelDivision: chapter\n---\nx\n"))))
  (testing "TASK-53: an unrecognized topLevelDivision stops the build
            naming the value, rather than silently defaulting -- the
            default is the very thing being overridden, so a typo would
            otherwise drop every chapter from the output and renumber
            every figure with nothing said"
    (doseq [bad ["chapters" "Chapter" "book" ""]]
      (let [thrown (try (parser/parse (str "---\ntopLevelDivision: " bad "\n---\nx\n"))
                        nil
                        (catch clojure.lang.ExceptionInfo e e))]
        (is (some? thrown) (pr-str bad))
        (is (= :haselnuss.parser/unknown-top-level-division (:type (ex-data thrown))))
        (is (str/includes? (ex-message thrown) "topLevelDivision"))))))

(deftest section-nesting-test
  (testing "increasing levels nest each heading under the previous one (AC #2)"
    (let [doc (parser/parse "# A\n## A.1\nx\n")]
      (is (= [{:t :section :level 1 :heading [(str-inline "A")] :attr attr
               :blocks [{:t :section :level 2 :heading [(str-inline "A.1")] :attr attr
                         :blocks [{:t :para :inlines [(str-inline "x")]}]}]}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc))))
  (testing "a shallower heading closes every deeper open section (AC #2)"
    (let [doc (parser/parse "# A\n## A.1\nx\n# B\ny\n")]
      (is (= [{:t :section :level 1 :heading [(str-inline "A")] :attr attr
               :blocks [{:t :section :level 2 :heading [(str-inline "A.1")] :attr attr
                         :blocks [{:t :para :inlines [(str-inline "x")]}]}]}
              {:t :section :level 1 :heading [(str-inline "B")] :attr attr
               :blocks [{:t :para :inlines [(str-inline "y")]}]}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc))))
  (testing "a skipped level (h1 -> h3) still nests under the open h1 (AC #2)"
    (let [doc (parser/parse "# A\n### Deep\nx\n")]
      (is (= [{:t :section :level 1 :heading [(str-inline "A")] :attr attr
               :blocks [{:t :section :level 3 :heading [(str-inline "Deep")] :attr attr
                         :blocks [{:t :para :inlines [(str-inline "x")]}]}]}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc))))
  (testing "skipping down then partially back up (h1 -> h3 -> h2) closes only
            the h3, not the h1, since h2 is shallower than h3 but not shallower
            than h1 (AC #2 -- the trickiest case for fold-sections' stack
            invariant)"
    (let [doc (parser/parse "# A\n### Deep\n## B\ny\n")]
      (is (= [{:t :section :level 1 :heading [(str-inline "A")] :attr attr
               :blocks [{:t :section :level 3 :heading [(str-inline "Deep")] :attr attr
                         :blocks []}
                        {:t :section :level 2 :heading [(str-inline "B")] :attr attr
                         :blocks [{:t :para :inlines [(str-inline "y")]}]}]}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc)))))

(deftest emphasis-and-strong-test
  (testing "emphasis and strong parse to :emph/:strong Inlines (AC #3)"
    (let [doc (parser/parse "a *em* **strong**\n")]
      (is (= [{:t :para
               :inlines [(str-inline "a") space
                         {:t :emph :inlines [(str-inline "em")]} space
                         {:t :strong :inlines [(str-inline "strong")]}]}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc)))))

(deftest inline-code-test
  (testing "inline code parses to a :code Inline (AC #3)"
    (let [doc (parser/parse "see `x := 1`\n")]
      (is (= [{:t :para :inlines [(str-inline "see") space {:t :code :text "x := 1"}]}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc)))))

(deftest fenced-code-block-test
  (testing "a fenced code block with a language tag parses to :code-block (AC #3)"
    (let [doc (parser/parse "```clojure\n(+ 1 2)\n```\n")]
      (is (= [{:t :code-block :text "(+ 1 2)\n" :attr attr :lang "clojure"}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc))))
  (testing "a fenced code block with no info string has no :lang"
    (let [doc (parser/parse "```\nplain\n```\n")]
      (is (= [{:t :code-block :text "plain\n" :attr attr}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc)))))

(deftest blockquote-test
  (testing "a blockquote parses to a :block-quote Block wrapping its Paragraphs (AC #3)"
    (let [doc (parser/parse "> one\n> two\n")]
      (is (= [{:t :block-quote
               :blocks [{:t :para :inlines [(str-inline "one") {:t :soft-break} (str-inline "two")]}]}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc)))))

(deftest list-test
  (testing "a tight unordered list with nesting parses to :list (AC #3)"
    (let [doc (parser/parse "- one\n- two\n  - nested\n")]
      (is (= [{:t :list :ordered false :tight true
               :items [[{:t :para :inlines [(str-inline "one")]}]
                       [{:t :para :inlines [(str-inline "two")]}
                        {:t :list :ordered false :tight true
                         :items [[{:t :para :inlines [(str-inline "nested")]}]]
                         :attr attr}]]
               :attr attr}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc))))
  (testing "an ordered list parses with :ordered true"
    (let [doc (parser/parse "1. a\n2. b\n")]
      (is (= [{:t :list :ordered true :tight true
               :items [[{:t :para :inlines [(str-inline "a")]}]
                       [{:t :para :inlines [(str-inline "b")]}]]
               :attr attr}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc))))
  (testing "a blank line between items makes the list loose (:tight false)"
    (let [doc (parser/parse "- one\n\n- two\n")]
      (is (= [{:t :list :ordered false :tight false
               :items [[{:t :para :inlines [(str-inline "one")]}]
                       [{:t :para :inlines [(str-inline "two")]}]]
               :attr attr}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc)))))

(deftest link-and-image-test
  (testing "a link parses to a :link Inline with its target and text (AC #3)"
    (let [doc (parser/parse "[a link](http://example.com/x)\n")]
      (is (= [{:t :para
               :inlines [{:t :link :target "http://example.com/x"
                          :inlines [(str-inline "a") space (str-inline "link")]
                          :attr attr}]}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc))))
  (testing "an image parses to an :image Inline with flattened alt text (AC #3)"
    (let [doc (parser/parse "![a tree](tree.png)\n")]
      (is (= [{:t :para
               :inlines [{:t :image :src "tree.png" :alt "a tree" :attr attr}]}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc)))))

(deftest autolink-test
  (testing "TASK-72 AC #1: a URL in angle brackets -- CommonMark's
            autolink, and how a URL is normally written in prose --
            parses as a Link whose target and text are the URL. It used
            to stop the build as an unsupported construct"
    (let [doc (parser/parse "Available at <https://www.dropbox.com/a_b?c=1#d>.\n")]
      (is (= [{:t :link :target "https://www.dropbox.com/a_b?c=1#d"
               :inlines [(str-inline "https://www.dropbox.com/a_b?c=1#d")]
               :attr attr}]
             (filter #(= :link (:t %)) (:inlines (first (:blocks doc))))))
      (is (ast/valid? ast/Document doc))))
  (testing "AC #2: an email autolink gets the mailto: target CommonMark
            gives it, which flexmark leaves off (a MailLink's own getUrl
            is empty), while its text stays the bare address"
    (let [doc (parser/parse "Write to <someone@example.com> today.\n")]
      (is (= [{:t :link :target "mailto:someone@example.com"
               :inlines [(str-inline "someone@example.com")]
               :attr attr}]
             (filter #(= :link (:t %)) (:inlines (first (:blocks doc))))))
      (is (ast/valid? ast/Document doc))))
  (testing "a backslash in an autolink is part of the URI, not an escape:
            CommonMark does no escape processing inside one at all. This
            is the decision a refactor could quietly undo -- routing
            autolinks through the ordinary Link branch would unescape
            them -- so it is pinned rather than left to the docstring"
    (let [autolinked (parser/parse "At <https://example.com/a\\_b>.\n")
          ordinary (parser/parse "At [x](https://example.com/a\\_b).\n")
          link-of (fn [doc] (first (filter #(= :link (:t %)) (:inlines (first (:blocks doc))))))]
      (is (= "https://example.com/a\\_b" (:target (link-of autolinked))))
      (is (= [(str-inline "https://example.com/a\\_b")] (:inlines (link-of autolinked))))
      (is (= "https://example.com/a_b" (:target (link-of ordinary)))
          "while an ordinary link destination IS unescaped, which is the contrast")))
  (testing "AC #3: angle brackets around something that is not a URI are
            still prose"
    (let [doc (parser/parse "Angle <3 apples> here.\n")]
      (is (empty? (filter #(= :link (:t %)) (:inlines (first (:blocks doc))))))
      (is (= [(str-inline "Angle") space (str-inline "<3") space (str-inline "apples>")
              space (str-inline "here.")]
             (:inlines (first (:blocks doc)))))))
  (testing "AC #4: a raw HTML tag still takes the unsupported-construct
            path, since it is not an autolink. Where CommonMark draws
            that line is worth pinning rather than assuming: `<notauri>`
            is a valid HTML open tag by its rules -- a tag name and
            nothing else -- and so is `<not a uri>`, whose words parse as
            attributes, while `<3 apples>` cannot be one because a tag
            name may not start with a digit. So the first two are raw
            HTML and the third is prose"
    (doseq [source ["Raw <b>html</b> here.\n"
                    "A <notauri> here.\n"
                    "A <not a uri> here.\n"]]
      (let [e (is (thrown? clojure.lang.ExceptionInfo (parser/parse source)))]
        (is (= ::parser/unsupported-node (:type (ex-data e))) source)))))

(deftest backslash-escape-test
  (testing "a backslash-escaped ASCII punctuation character resolves to the
            bare character in a plain-text run, not a literal backslash
            (CommonMark sec2.4; verified this parser previously kept the
            backslash, diverging from flexmark's own HtmlRenderer)"
    (let [doc (parser/parse "a \\* b\n")]
      (is (= [{:t :para :inlines [(str-inline "a") space (str-inline "*") space (str-inline "b")]}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc))))
  (testing "a backslash before a non-punctuation character (e.g. a letter or
            digit) is not an escape and stays a literal backslash"
    (let [doc (parser/parse "\\a \\1\n")]
      (is (= [{:t :para :inlines [(str-inline "\\a") space (str-inline "\\1")]}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc))))
  (testing "the same unescaping applies to an image's flattened alt text"
    (let [doc (parser/parse "![a \\* tree](tree.png)\n")]
      (is (= [{:t :para :inlines [{:t :image :src "tree.png" :alt "a * tree" :attr attr}]}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc))))
  (testing "the same unescaping applies to an inline link's destination URL --
            `\\)` is CommonMark's documented way to include a literal
            parenthesis in a link destination (sec2.4/sec6.3), and `.getUrl`
            returns the same raw, still-escaped source span as a Text node"
    (let [doc (parser/parse "[a](/uri\\)x)\n")]
      (is (= [{:t :para :inlines [{:t :link :target "/uri)x"
                                   :inlines [(str-inline "a")]
                                   :attr attr}]}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc))))
  (testing "the same unescaping applies to an inline image's destination URL"
    (let [doc (parser/parse "![a](tree\\)x.png)\n")]
      (is (= [{:t :para :inlines [{:t :image :src "tree)x.png" :alt "a" :attr attr}]}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc))))
  (testing "the same unescaping applies to a reference-style definition's URL,
            resolved through a LinkRef"
    (let [doc (parser/parse "[a][r]\n\n[r]: /uri\\)x\n")]
      (is (= [{:t :para :inlines [{:t :link :target "/uri)x"
                                   :inlines [(str-inline "a")]
                                   :attr attr}]}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc)))))

(deftest reference-style-link-and-image-test
  (testing "a full reference link `[text][ref]` resolves through its `[ref]:
            url` definition (AC #3 -- CommonMark treats reference-style
            links as the same construct as inline-syntax links, not a
            separate one)"
    (let [doc (parser/parse "[a link][ref]\n\n[ref]: http://example.com/x \"title\"\n")]
      (is (= [{:t :para
               :inlines [{:t :link :target "http://example.com/x"
                          :title "title"
                          :inlines [(str-inline "a") space (str-inline "link")]
                          :attr attr}]}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc))))
  (testing "a shortcut reference link `[ref]` (no separate link text) resolves
            the same way"
    (let [doc (parser/parse "[ref]\n\n[ref]: http://example.com/x\n")]
      (is (= [{:t :para
               :inlines [{:t :link :target "http://example.com/x"
                          :inlines [(str-inline "ref")]
                          :attr attr}]}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc))))
  (testing "a reference image `![alt][ref]` resolves through its definition,
            with alt text flattened the same way as an inline-syntax image"
    (let [doc (parser/parse "![alt][ref]\n\n[ref]: tree.png\n")]
      (is (= [{:t :para :inlines [{:t :image :src "tree.png" :alt "alt" :attr attr}]}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc))))
  (testing "a reference link/image with no matching definition falls back to
            its own literal source text, matching CommonMark (an undefined
            reference is not an error, it is plain text) and flexmark's own
            HtmlRenderer (its content is still parsed, though: see
            `undefined-bracket-keeps-inline-nodes-test`)"
    (let [doc (parser/parse "[undefined][nope]\n")]
      (is (= [{:t :para :inlines [(str-inline "[undefined][nope]")]}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc))))
  (testing "the `[ref]: url` definition line itself contributes no visible
            Block -- it is metadata, not content, wherever it appears
            relative to its reference"
    (let [doc (parser/parse "[a][r]\n\n[r]: http://x\n\nAfter.\n")]
      (is (= [{:t :para :inlines [{:t :link :target "http://x"
                                   :inlines [(str-inline "a")]
                                   :attr attr}]}
              {:t :para :inlines [(str-inline "After.")]}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc))))
  (testing "reference resolution works inside a blockquote and a list item too,
            not only at the top level"
    (let [doc (parser/parse "> [a][r]\n\n[r]: http://x\n")]
      (is (= [{:t :block-quote
               :blocks [{:t :para :inlines [{:t :link :target "http://x"
                                             :inlines [(str-inline "a")]
                                             :attr attr}]}]}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc)))
    (let [doc (parser/parse "- [a][r]\n\n[r]: http://x\n")]
      (is (= [{:t :list :ordered false :tight true
               :items [[{:t :para :inlines [{:t :link :target "http://x"
                                             :inlines [(str-inline "a")]
                                             :attr attr}]}]]
               :attr attr}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc)))))

(deftest undefined-bracket-keeps-inline-nodes-test
  (testing "TASK-160 AC #1: inside a `[...]` that is no defined construct,
            a book ref, cross-ref, citation and inline math each stay their
            own node, the brackets literal text around them"
    (doseq [[src inlines]
            [["[see @{bible:romanos#14:8}]\n"
              [(str-inline "[see") space {:t :book-ref :ref "bible:romanos#14:8"} (str-inline "]")]]
             ["[$x^2$]\n"
              [(str-inline "[") {:t :math-inline :tex "x^2"} (str-inline "]")]]
             ;; Not a citation group: its second segment has no `@key`
             ;; (`citation-bracket-content?`), so `@smith2020` is the bare,
             ;; author-in-text form.
             ["[@smith2020; @{bible:x}]\n"
              [(str-inline "[") {:t :cite :items [{:key "smith2020" :mode :author}]}
               (str-inline ";") space {:t :book-ref :ref "bible:x"} (str-inline "]")]]
             ["[see @fig:x; and more]\n"
              [(str-inline "[see") space {:t :cross-ref :label "fig:x"} (str-inline ";")
               space (str-inline "and") space (str-inline "more]")]]
             ["[see *this*]\n"
              [(str-inline "[see") space {:t :emph :inlines [(str-inline "this")]} (str-inline "]")]]
             ["[$y$][nope]\n"
              [(str-inline "[") {:t :math-inline :tex "y"} (str-inline "][nope]")]]
             ["[$y$][]\n"
              [(str-inline "[") {:t :math-inline :tex "y"} (str-inline "][]")]]
             ;; flexmark leaves edge spaces out of the children; they stay
             ;; `:space` nodes, not part of the bracket's word.
             ["[ foo ]\n" [(str-inline "[") space (str-inline "foo") space (str-inline "]")]]
             ["[ foo bar ]\n"
              [(str-inline "[") space (str-inline "foo") space (str-inline "bar") space (str-inline "]")]]
             ["[ $x$ ]\n" [(str-inline "[") space {:t :math-inline :tex "x"} space (str-inline "]")]]
             ["[ *a* ]\n"
              [(str-inline "[") space {:t :emph :inlines [(str-inline "a")]} space (str-inline "]")]]
             ["[ @{a:b} ]\n" [(str-inline "[") space {:t :book-ref :ref "a:b"} space (str-inline "]")]]
             ["[$x$ ][nope]\n"
              [(str-inline "[") {:t :math-inline :tex "x"} space (str-inline "][nope]")]]
             ;; Prose touching a bracket is one word with it.
             ["CLC[$i$] and x[$i$]y\n"
              [(str-inline "CLC[") {:t :math-inline :tex "i"} (str-inline "]") space (str-inline "and")
               space (str-inline "x[") {:t :math-inline :tex "i"} (str-inline "]y")]]]]
      (let [doc (parser/parse src)]
        (is (= [{:t :para :inlines inlines}] (:blocks doc)) src)
        (is (ast/valid? ast/Document doc) src))))
  (testing "a bracket of plain text is still one word per run, as before"
    (is (= [(str-inline "[foo]")] (-> (parser/parse "[foo]\n") :blocks first :inlines)))
    (is (= [(str-inline "[foo") space (str-inline "bar]")]
           (-> (parser/parse "[foo bar]\n") :blocks first :inlines)))
    (is (= [(str-inline "[@fig:x]")] (-> (parser/parse "[\\@fig:x]\n") :blocks first :inlines))
        "an escaped `@` is still prose"))
  (testing "an undefined reference with an attribute group keeps its nodes
            inside the Span that carries the group"
    (is (= [{:t :span
             :inlines [(str-inline "[") {:t :math-inline :tex "z"} (str-inline "][nope]")]
             :attr {:classes ["c"] :props {}}}]
           (-> (parser/parse "[$z$][nope]{.c}\n") :blocks first :inlines)))
    (is (= [{:t :span
             :inlines [(str-inline "[") space {:t :math-inline :tex "z"} space (str-inline "][nope]")]
             :attr {:classes ["c"] :props {}}}]
           (-> (parser/parse "[ $z$ ][nope]{.c}\n") :blocks first :inlines))))
  (testing "a node inside a bracket spanning lines keeps its OWN line, not
            the line the bracket opens on"
    (let [inlines (-> (parser/parse "a\n[see\n@fig:x; @{b:c} z]\n" {:source "doc.hdoc"})
                      :blocks first :inlines)
          line-of (fn [t] (:line (ast/source (first (filter #(= t (:t %)) inlines)))))]
      (is (= 3 (line-of :cross-ref)))
      (is (= 3 (line-of :book-ref)))))
  (testing "AC #2: defined bracket constructs parse exactly as before"
    (is (= [{:t :cite :items [{:key "fig:x" :mode :normal :prefix [(str-inline "see")]}]}]
           (-> (parser/parse "[see @fig:x]\n") :blocks first :inlines))
        "every segment has an `@key`: a citation group, as sec5.11 says")
    (is (= [{:t :cite :items [{:key "smith2020" :mode :normal} {:key "doe99" :mode :normal}]}]
           (-> (parser/parse "[@smith2020; @doe99]\n") :blocks first :inlines)))
    (is (= [{:t :span :inlines [(str-inline "see") space {:t :math-inline :tex "x"}]
             :attr {:classes ["c"] :props {}}}]
           (-> (parser/parse "[see $x$]{.c}\n") :blocks first :inlines)))
    (is (= [{:t :link :target "http://x" :inlines [{:t :math-inline :tex "x"}] :attr attr}]
           (-> (parser/parse "[$x$]\n\n[$x$]: http://x\n") :blocks first :inlines)))
    (is (= [{:t :link :target "u" :inlines [{:t :book-ref :ref "a:b"}] :attr attr}]
           (-> (parser/parse "[@{a:b}](u)\n") :blocks first :inlines)))))

(deftest undefined-image-ref-keeps-inline-nodes-test
  (testing "TASK-166 AC #1: an undefined image reference keeps the nodes in
            its alt text, `![`, `]` and any `[ref]` literal text around them
            -- the same fallback TASK-160 gave an undefined `[...]`"
    (doseq [[src inlines]
            [["![$x$][nope]\n"
              [(str-inline "![") {:t :math-inline :tex "x"} (str-inline "][nope]")]]
             ["![a *b*]\n"
              [(str-inline "![a") space {:t :emph :inlines [(str-inline "b")]} (str-inline "]")]]
             ["![$y$][]\n"
              [(str-inline "![") {:t :math-inline :tex "y"} (str-inline "][]")]]
             ["![@{a:b}]\n"
              [(str-inline "![") {:t :book-ref :ref "a:b"} (str-inline "]")]]
             ["![see @fig:x; z]\n"
              [(str-inline "![see") space {:t :cross-ref :label "fig:x"} (str-inline ";")
               space (str-inline "z]")]]
             ;; Plain alt text is one word per run, as before.
             ["![x][]\n" [(str-inline "![x][]")]]
             ["![x][nope]\n" [(str-inline "![x][nope]")]]
             ;; No children at all: the raw span, as before.
             ["![]\n" [(str-inline "![]")]]
             ["![][nope]\n" [(str-inline "![][nope]")]]
             ;; Edge spaces flexmark leaves outside the children stay
             ;; `:space` nodes.
             ["![ foo ]\n" [(str-inline "![") space (str-inline "foo") space (str-inline "]")]]
             ["![ $x$ ][nope]\n"
              [(str-inline "![") space {:t :math-inline :tex "x"} space (str-inline "][nope]")]]
             ;; Prose touching the syntax is one word with it.
             ["x![$i$]y\n"
              [(str-inline "x![") {:t :math-inline :tex "i"} (str-inline "]y")]]
             ;; Nested inside an undefined bracket, and inside itself.
             ["[![$x$]]\n" [(str-inline "[![") {:t :math-inline :tex "x"} (str-inline "]]")]]
             ["![![$y$]]\n" [(str-inline "![![") {:t :math-inline :tex "y"} (str-inline "]]")]]]]
      (let [doc (parser/parse src)]
        (is (= [{:t :para :inlines inlines}] (:blocks doc)) src)
        (is (ast/valid? ast/Document doc) src))))
  (testing "a node inside an image reference spanning lines keeps its OWN
            line, and the line break stays a soft break"
    (let [inlines (-> (parser/parse "a\n![see\n@fig:x; @{b:c} z]\n" {:source "doc.hdoc"})
                      :blocks first :inlines)
          line-of (fn [t] (:line (ast/source (first (filter #(= t (:t %)) inlines)))))]
      (is (= [:str :soft-break :str :soft-break :cross-ref :str :space :book-ref :space :str]
             (mapv :t inlines)))
      (is (= 3 (line-of :cross-ref)))
      (is (= 3 (line-of :book-ref)))))
  (testing "AC #2: a defined image reference parses exactly as before"
    (is (= [{:t :image :src "a.png" :alt "x" :attr attr}]
           (-> (parser/parse "![$x$][r]\n\n[r]: a.png\n") :blocks first :inlines)))
    (is (= [{:t :image :src "a.png" :alt "a b" :attr attr}]
           (-> (parser/parse "![a *b*]\n\n[a *b*]: a.png\n") :blocks first :inlines)))
    (is (= [{:t :image :src "a.png" :alt "x" :attr attr}]
           (-> (parser/parse "![x][]\n\n[x]: a.png\n") :blocks first :inlines)))))

(deftest thematic-break-test
  (testing "a thematic break parses to :thematic-break (AC #3)"
    (let [doc (parser/parse "before\n\n---\n\nafter\n")]
      (is (= [{:t :para :inlines [(str-inline "before")]}
              {:t :thematic-break}
              {:t :para :inlines [(str-inline "after")]}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc)))))

(deftest combined-document-test
  (testing "front matter plus several CommonMark constructs together (AC #4)"
    (let [source (str "---\n"
                      "title: Sample\n"
                      "lang: en\n"
                      "---\n"
                      "# Intro\n"
                      "\n"
                      "A paragraph with *emphasis*, **strong**, and `code`.\n"
                      "\n"
                      "- one\n"
                      "- two\n"
                      "\n"
                      "> A quote.\n"
                      "\n"
                      "```clojure\n"
                      "(+ 1 2)\n"
                      "```\n"
                      "\n"
                      "![alt](tree.png)\n"
                      "\n"
                      "---\n"
                      "\n"
                      "## Sub-section\n"
                      "\n"
                      "[a link](http://example.com)\n")
          doc (parser/parse source)]
      (is (ast/valid? ast/Document doc))
      (is (= {:title [(str-inline "Sample")] :lang "en"} (:meta doc)))
      (is (= [:section] (mapv :t (:blocks doc))))
      (let [top (first (:blocks doc))]
        (is (= 1 (:level top)))
        (is (= [(str-inline "Intro")] (:heading top)))
        (is (= [:para :list :block-quote :code-block :para :thematic-break :section]
               (mapv :t (:blocks top))))
        (let [sub (last (:blocks top))]
          (is (= :section (:t sub)))
          (is (= 2 (:level sub)))
          (is (= [(str-inline "Sub-section")] (:heading sub)))
          (is (= [{:t :para
                   :inlines [{:t :link :target "http://example.com"
                              :inlines [(str-inline "a") space (str-inline "link")]
                              :attr attr}]}]
                 (:blocks sub))))))))

(deftest attribute-attachment-test
  (testing "an attribute group after a heading attaches id, classes, and
            props to that Section's Attr (TASK-5 AC #1)"
    (let [doc (parser/parse "## Introduction {#sec:intro .note lang=en}\n")]
      (is (= [{:t :section :level 2 :heading [(str-inline "Introduction")] :blocks []
               :attr {:id "sec:intro" :classes ["note"] :props {"lang" "en"}}}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc))))
  (testing "a heading with no attribute group still gets the default Attr"
    (let [doc (parser/parse "## Plain\n")]
      (is (= [{:t :section :level 2 :heading [(str-inline "Plain")] :blocks [] :attr attr}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc))))
  (testing "an attribute group with NO space before the `{` also attaches
            (TASK-5 AC #1; a review finding: flexmark wraps the heading's
            preceding text in an internal `TextBase` bookkeeping node
            whenever it sits immediately before a successfully-parsed
            AttributesNode, and the parser previously had no branch for
            that node class at all, throwing `::unsupported-node` instead
            of attaching -- this must produce the identical Section Attr
            as the with-space form above)"
    (let [doc (parser/parse "## Introduction{#sec:intro .note lang=en}\n")]
      (is (= [{:t :section :level 2 :heading [(str-inline "Introduction")] :blocks []
               :attr {:id "sec:intro" :classes ["note"] :props {"lang" "en"}}}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc))))
  (testing "an attribute group after an image attaches directly to that
            Image's own Attr, since Image already has an Attr field in the
            AST (TASK-5 AC #2). Uses a class/prop group with no `#id` so
            this stays a plain Para+Image rather than TASK-8's later
            id-bearing-standalone-image-becomes-a-Figure promotion (AC #1
            of TASK-8, covered by figure-test) -- this test's own point is
            the attribute-merge mechanism itself, independent of that
            downstream Paragraph/Figure decision."
    (let [doc (parser/parse "![A hazel tree](tree.png){.photo width=60%}\n")]
      (is (= [{:t :para
               :inlines [{:t :image :src "tree.png" :alt "A hazel tree"
                          :attr {:classes ["photo"] :props {"width" "60%"}}}]}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc))))
  (testing "an attribute group after an inline code span wraps it in a Span
            carrying the parsed Attr, since Code has no Attr field of its
            own in the AST at all (sec4.4) -- unlike heading/image, the
            group cannot attach directly to the code span's own node
            (TASK-5 AC #2)"
    (let [doc (parser/parse "`x := 1`{.code-term}\n")]
      (is (= [{:t :para
               :inlines [{:t :span :inlines [{:t :code :text "x := 1"}]
                          :attr {:classes ["code-term"] :props {}}}]}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc))))
  (testing "an attribute group after an inline link attaches directly to
            that Link's own Attr, the same direct-merge mechanism as image
            (not one of TASK-5's named ACs by name, but the identical
            Attr-bearing shape as Image)"
    (let [doc (parser/parse "[text](http://example.com){.ext #lnk}\n")]
      (is (= [{:t :para
               :inlines [{:t :link :target "http://example.com"
                          :inlines [(str-inline "text")]
                          :attr {:id "lnk" :classes ["ext"] :props {}}}]}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc)))))

(deftest bracketed-span-test
  (testing "bracketed text followed by an attribute group produces a Span
            node carrying the enclosed inlines and the parsed Attr (TASK-5
            AC #3)"
    (let [doc (parser/parse "Consider the [free monoid]{.term #def:free-monoid}.\n")]
      (is (= [{:t :para
               :inlines [(str-inline "Consider") space (str-inline "the") space
                         {:t :span
                          :inlines [(str-inline "free") space (str-inline "monoid")]
                          :attr {:id "def:free-monoid" :classes ["term"] :props {}}}
                         (str-inline ".")]}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc))))
  (testing "the Span mechanism composes with other inline constructs, e.g.
            bracketed text nested inside strong emphasis"
    (let [doc (parser/parse "**bold [term]{.x} text**\n")]
      (is (= [{:t :para
               :inlines [{:t :strong
                          :inlines [(str-inline "bold") space
                                    {:t :span :inlines [(str-inline "term")]
                                     :attr {:classes ["x"] :props {}}}
                                    space (str-inline "text")]}]}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc))))
  (testing "a bracket label that also matches a `[label]: url` reference
            definition elsewhere still becomes a Span when immediately
            followed by an attribute group, taking priority over reference
            resolution -- sec5.6 span syntax is a distinct surface form
            from a reference link (sec6.3/6.4), and is the same generic
            mechanism sec5.10's `[text]{ref=...}` cross-reference role
            syntax relies on regardless of any incidental definition"
    (let [doc (parser/parse "[term]{.x}\n\n[term]: http://example.com\n")]
      (is (= [{:t :para
               :inlines [{:t :span :inlines [(str-inline "term")]
                          :attr {:classes ["x"] :props {}}}]}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc))))
  (testing "a FULL reference-style link (`[text][ref]`, two distinct
            bracket pairs) followed by an attribute group is NOT a
            bracket-only Span -- unlike the shortcut case above, it keeps
            its resolved link target, with the parsed Attr merged
            directly into the resulting `:link` (the same direct-merge
            mechanism as an inline Link/Image, AC #2). This guards a
            TASK-5 regression against TASK-4's reference-link resolution:
            the dispatch previously keyed off the LinkRef Java class
            alone, which full/collapsed reference links share with the
            bracket-only shortcut form, so this used to silently discard
            the resolved URL and produce a target-less Span instead"
    (let [doc (parser/parse "[a link][ref]{.c}\n\n[ref]: http://example.com\n")]
      (is (= [{:t :para
               :inlines [{:t :link :target "http://example.com"
                          :inlines [(str-inline "a") space (str-inline "link")]
                          :attr {:classes ["c"] :props {}}}]}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc))))
  (testing "a COLLAPSED reference-style link (`[text][]`, an explicit but
            empty second bracket) followed by an attribute group behaves
            the same way -- keeps its resolved link target with the Attr
            merged in, not a Span, for the same reason as the full-
            reference case above"
    (let [doc (parser/parse "[a link][]{.c}\n\n[a link]: http://example.com\n")]
      (is (= [{:t :para
               :inlines [{:t :link :target "http://example.com"
                          :inlines [(str-inline "a") space (str-inline "link")]
                          :attr {:classes ["c"] :props {}}}]}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc))))
  (testing "a full reference-style link with NO matching definition,
            followed by an attribute group, falls back to CommonMark's
            own literal-text behavior (sec6.3/6.4, same as with no
            attribute group at all) -- but the attribute group itself
            must not be silently dropped either (AC #4's no-silent-drop
            philosophy applied here too), so the literal fallback text is
            wrapped in a Span carrying the parsed Attr, the same device
            already used for a Code span (which likewise has no `:attr`
            field of its own)"
    (let [doc (parser/parse "[a][nope]{.c}\n")]
      (is (= [{:t :para
               :inlines [{:t :span :inlines [(str-inline "[a][nope]")]
                          :attr {:classes ["c"] :props {}}}]}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc)))))

(deftest image-ref-attribute-group-test
  (testing "TASK-169 AC #1: a defined reference-style image -- full,
            collapsed and shortcut -- followed by an attribute group parses
            to the same blocks as the inline image it stands for, title
            included"
    (doseq [[src inline-src]
            [["![x][r]{.c}\n\n[r]: a.png\n" "![x](a.png){.c}\n"]
             ["![x][]{.c}\n\n[x]: a.png\n" "![x](a.png){.c}\n"]
             ["![x]{.c}\n\n[x]: a.png\n" "![x](a.png){.c}\n"]
             ["![x][r]{.c k=v}\n\n[r]: a.png \"T\"\n" "![x](a.png \"T\"){.c k=v}\n"]
             ;; Defined after a paragraph that uses it, mid-sentence.
             ["a ![x][r]{#i} b\n\n[r]: a.png\n" "a ![x](a.png){#i} b\n"]]]
      (let [doc (parser/parse src)]
        (is (= (:blocks (parser/parse inline-src)) (:blocks doc)) src)
        (is (ast/valid? ast/Document doc) src)))
    (is (= [{:t :image :src "a.png" :alt "x" :attr {:classes ["c"] :props {}}}]
           (-> (parser/parse "![x][r]{.c}\n\n[r]: a.png\n") :blocks first :inlines))))
  (testing "a lone id-bearing reference image is promoted to a figure the
            same way an inline one is -- id and classes on the figure, and
            the caption keeping the alt text's markup"
    (doseq [[src inline-src]
            [["![*x* $y$][r]{#fig:a .c w=1}\n\n[r]: a.png\n" "![*x* $y$](a.png){#fig:a .c w=1}\n"]
             ["![As in @fig:b]{#fig:a}\n\n[As in @fig:b]: a.png\n" "![As in @fig:b](a.png){#fig:a}\n"]
             ["![x][]{#fig:a}\n\n[x]: a.png\n" "![x](a.png){#fig:a}\n"]]]
      (let [doc (parser/parse src)]
        (is (= :figure (:t (first (:blocks doc)))) src)
        (is (= (:blocks (parser/parse inline-src)) (:blocks doc)) src)
        (is (ast/valid? ast/Document doc) src)))
    (is (= [{:t :emph :inlines [(str-inline "x")]} space {:t :math-inline :tex "y"}]
           (-> (parser/parse "![*x* $y$][r]{#fig:a}\n\n[r]: a.png\n") :blocks first :caption))))
  (testing "AC #2: an undefined image reference followed by an attribute
            group keeps TASK-166's inline nodes, wrapped in a Span carrying
            the group -- as an undefined `[a][nope]{.c}` is"
    (doseq [[src inlines]
            [["![x][nope]{.c}\n" [(str-inline "![x][nope]")]]
             ["![$x$][]{.c}\n" [(str-inline "![") {:t :math-inline :tex "x"} (str-inline "][]")]]
             ["![x]{.c}\n" [(str-inline "![x]")]]
             ["![ $z$ ][nope]{.c}\n"
              [(str-inline "![") space {:t :math-inline :tex "z"} space (str-inline "][nope]")]]]]
      (let [doc (parser/parse src)]
        (is (= [{:t :para :inlines [{:t :span :inlines inlines :attr {:classes ["c"] :props {}}}]}]
               (:blocks doc))
            src)
        (is (ast/valid? ast/Document doc) src)))
    (is (= [{:t :para :inlines [{:t :span :inlines [(str-inline "![x][nope]")]
                                 :attr {:id "fig:a" :classes [] :props {}}}]}]
           (:blocks (parser/parse "![x][nope]{#fig:a}\n")))
        "an id on an undefined one goes on the Span: there is no image to promote"))
  (testing "the attributed Span for an undefined image or link reference
            carries the reference's own source line"
    (doseq [src ["hi\n\n![x][nope]{#a}\n" "hi\n\n[x][nope]{#a}\n" "hi\n\n![$x$]{.c}\n"]]
      (let [span (-> (parser/parse src {:source "doc.hdoc"}) :blocks second :inlines first)]
        (is (= :span (:t span)) src)
        (is (= {:file "doc.hdoc" :line 3} (select-keys (ast/source span) [:file :line])) src)))))

(defn- flexmark-attributes-tag
  "flexmark-ext-attributes' own `AttributeParsing.ATTRIBUTES_TAG`, read
  reflectively (the class and field are package-private)."
  ^java.util.regex.Pattern []
  (let [options (doto (com.vladsch.flexmark.util.data.MutableDataSet.)
                  (.set com.vladsch.flexmark.parser.Parser/EXTENSIONS
                        (java.util.List/of (com.vladsch.flexmark.ext.attributes.AttributesExtension/create))))
        parsing-class (Class/forName "com.vladsch.flexmark.ext.attributes.internal.AttributeParsing")
        ctor (doto (.getDeclaredConstructor parsing-class
                                            (into-array Class [com.vladsch.flexmark.ast.util.Parsing]))
               (.setAccessible true))
        instance (.newInstance ctor (object-array [(com.vladsch.flexmark.ast.util.Parsing. options)]))
        field (doto (.getDeclaredField parsing-class "ATTRIBUTES_TAG") (.setAccessible true))]
    (.get field instance)))

(defn- flexmark-group-at?
  [^String s i]
  (let [m (.matcher (flexmark-attributes-tag) s)]
    (.region m i (.length s))
    (.lookingAt m)))

(defn- parses-as-group?
  "True if `src` is refused because some `{` in it was read as an attribute
  group with nothing to attach to."
  [src]
  (try (parser/parse src) false
       (catch clojure.lang.ExceptionInfo e
         (contains? #{::parser/unsupported-node ::parser/malformed-attributes} (:type (ex-data e))))))

(defn- brace-indices
  "Every index of `s` holding a `{`."
  [^String s]
  (filter #(= \{ (.charAt s %)) (range (count s))))

(defn- no-mismatches
  "The message for a fuzz test's single `(is (zero? (count mismatches)))`:
  how many samples disagreed, and the first few of them. The assertion is
  on the count, not the collection, so clojure.test's `actual:` line
  prints a number rather than every mismatching sample."
  [mismatches]
  (str (count mismatches) " mismatching samples, first: " (pr-str (take 5 mismatches))))

(deftest attribute-group-at-test
  (testing "TASK-161: `attribute-group-at?` agrees with flexmark's own
            pattern, and with what the parser actually does, over a fixed
            pseudo-random sample of brace-heavy text -- each disagreement
            collected, the whole sample asserted once (TASK-172)"
    (let [alphabet ["{" "}" "x" "X" "_" "1" " " "=" "," "'" "\"" "#" "." "-" ":" "\n" "\t" "*" "/"
                    "<" ">" "`" "\\" "é" "ß" "\f" "\r" " "]
          rnd (java.util.Random. 161)
          samples (vec (for [_ (range 3000)]
                         (str "a {" (apply str (repeatedly (inc (.nextInt rnd 7))
                                                           #(nth alphabet (.nextInt rnd (count alphabet)))))
                              " b")))
          flexmark-mismatches (vec (for [s samples
                                         i (brace-indices s)
                                         :when (not= (flexmark-group-at? s i) (parser/attribute-group-at? s i))]
                                     [s i]))
          ;; Against the parser itself, where the parser's own stricter
          ;; check for an attempted group cannot fire first, nothing else
          ;; (a code span, an escape, inline HTML) claims the text, and no
          ;; blank line -- `\r\n`, `\r` or `\n` twice, only spaces or tabs
          ;; between -- splits the paragraph. Atomic groups, so a lone
          ;; `\r\n` is one line ending, not two.
          parser-mismatches (vec (for [s samples
                                       :when (not (re-find #"(?>\r\n|\r|\n)[ \t]*(?>\r\n|\r|\n)|\{[#.]|\{[A-Za-z_][\w-]*=|[`\\<>]" s))
                                       :when (not= (parses-as-group? (str s "\n"))
                                                   (boolean (some #(parser/attribute-group-at? s %) (brace-indices s))))]
                                   s))]
      (is (zero? (count flexmark-mismatches)) (no-mismatches flexmark-mismatches))
      (is (zero? (count parser-mismatches)) (no-mismatches parser-mismatches))))
  (testing "the cases the emitter relies on"
    (is (parser/attribute-group-at? "a {x} b" 2))
    (is (parser/attribute-group-at? "{x\ny}" 0) "whitespace runs cross a line break")
    (is (parser/attribute-group-at? "{ x }" 0))
    (doseq [s ["{}" "{ }" "{1}" "{x,y}" "{x" "{x*}" "{x\\}" "{{x}"]]
      (is (not (parser/attribute-group-at? s 0)) s))))

(deftest attribute-group-closes-test
  (testing "TASK-168: the parser's stackless matcher answers exactly as
            flexmark's `ATTRIBUTES_TAG` does, over a fixed pseudo-random
            sample biased toward the pattern's own tokens, quotes, `=` and
            whitespace -- the places it backtracks. Each disagreement is
            collected and the whole sample asserted once (TASK-172)"
    (let [closes? @#'parser/attribute-group-closes?
          alphabet ["{" "}" "}" "x" "X" "_" "1" " " " " "=" "=" "," "'" "\"" "#" "." "-" ":"
                    "\n" "\t" "\u000B" "\f" "\r" " " "<" "`" "\\" "é" "\u0000" "\u007F" "😀"]
          rnd (java.util.Random. 168)
          samples (for [_ (range 20000)]
                    (str "{" (apply str (repeatedly (inc (.nextInt rnd 10))
                                                    #(nth alphabet (.nextInt rnd (count alphabet)))))))
          mismatches (vec (for [s samples
                                i (brace-indices s)
                                :when (not= (flexmark-group-at? s i) (closes? s i))]
                            [s i]))]
      (is (zero? (count mismatches)) (no-mismatches mismatches))
      (doseq [s ["{x}" "{ x }" "{x= y}" "{a= b=c}" "{a b}" "{#i .c k='v }' x}" "{k=\"}\"}"
                 "{x=}" "{.c\n.d}" "{:a}"]]
        (is (closes? s 0) s))
      (doseq [s ["{}" "{ }" "{1}" "{x,y}" "{x" "{{x}" "{a=b=c}" "{#}" "{k='v}" "{x y z"]]
        (is (not (closes? s 0)) s)))))

(deftest long-run-after-brace-test
  (let [run (apply str (repeat 20000 " y"))]
    (testing "TASK-168: a bare `{` before a 20000-word run with no `}` to
              close it is prose, as flexmark reads a shorter one -- it no
              longer overflows the stack"
      (let [[block & more] (:blocks (parser/parse (str "Para {x" run ".\n")))]
        (is (nil? more))
        (is (= :para (:t block)))
        (is (= (str "Para {x" run ".")
               (apply str (map #(case (:t %) :str (:text %) :space " ") (:inlines block)))))))
    (testing "TASK-168: a run that a `}` does close is a group too long for
              flexmark's regex: a located parse error, not a
              StackOverflowError"
      (let [src (str "---\ntitle: T\n---\nFirst.\n\nSee\nthe\tset {x" run "} here.\n")
            e (is (thrown? clojure.lang.ExceptionInfo (parser/parse src {:source "doc.hdoc"})))]
        (is (= ::parser/attribute-group-too-long (:type (ex-data e))))
        (is (= 7 (:source-line (ex-data e))))
        (is (= 9 (:source-column (ex-data e))))
        (is (str/starts-with? (ex-message e) "doc.hdoc:7:9: attribute group too long"))
        (is (instance? StackOverflowError (ex-cause e)))))
    (testing "and inside a citation's prefix, which is parsed again on its
              own, the error still names the paragraph's line"
      (let [src (str "First.\n\nText [see {x" run "} @knuth1984].\n")
            e (is (thrown? clojure.lang.ExceptionInfo (parser/parse src)))]
        (is (= ::parser/attribute-group-too-long (:type (ex-data e))))
        (is (= 3 (:source-line (ex-data e))))))))

(deftest malformed-attribute-group-test
  (testing "an unclosed attribute group after a heading raises a parse
            diagnostic rather than silently dropping the attempt (TASK-5
            AC #4)"
    (let [e (is (thrown? clojure.lang.ExceptionInfo (parser/parse "## Heading {#sec:x\n")))]
      (is (= ::parser/malformed-attributes (:type (ex-data e))))))
  (testing "an unclosed attribute group after bracketed text raises the
            same diagnostic"
    (let [e (is (thrown? clojure.lang.ExceptionInfo (parser/parse "[free monoid]{.term\n")))]
      (is (= ::parser/malformed-attributes (:type (ex-data e))))))
  (testing "a group flexmark's own attributes extension rejects outright
            (an invalid token composed only of punctuation, closed by a
            `}`) also raises the diagnostic rather than being silently
            left as literal text"
    (let [e (is (thrown? clojure.lang.ExceptionInfo (parser/parse "[x]{#id !!!}\n")))]
      (is (= ::parser/malformed-attributes (:type (ex-data e))))))
  (testing "a group flexmark's own attributes extension *does* successfully
            parse but that fits none of Haselnuss's exactly-three attribute
            forms (#id/.class/key=val) -- a bare word -- also raises the
            diagnostic, since accepting it silently (as flexmark itself
            would) is exactly the AC #4 failure mode this guards against"
    (let [e (is (thrown? clojure.lang.ExceptionInfo (parser/parse "[x]{#id bareword}\n")))]
      (is (= ::parser/malformed-attributes (:type (ex-data e)))))
    (let [e (is (thrown? clojure.lang.ExceptionInfo (parser/parse "`x`{bareword}\n")))]
      (is (= ::parser/malformed-attributes (:type (ex-data e))))))
  (testing "ordinary prose that merely contains a literal `{` is not
            misflagged as an attempted attribute group -- only text shaped
            like a genuine attempt (`{#`, `{.`, or `{word=`) trips the
            diagnostic"
    (let [doc (parser/parse "The set {1, 2, 3} is finite.\n")]
      (is (= [{:t :para
               :inlines [(str-inline "The") space (str-inline "set") space
                         (str-inline "{1,") space (str-inline "2,") space
                         (str-inline "3}") space (str-inline "is") space
                         (str-inline "finite.")]}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc))))
  (testing "TASK-189: a backslash-escaped `{` is a literal brace, never an
            attempted group -- closed or not, in a paragraph or a heading,
            with or without the heading's own group after it"
    (doseq [[src expected]
            [["a \\{.c}\n" {:t :para :inlines [(str-inline "a") space (str-inline "{.c}")]}]
             ["a \\{#x} b\n" {:t :para :inlines [(str-inline "a") space (str-inline "{#x}") space (str-inline "b")]}]
             ["a \\{k=v}\n" {:t :para :inlines [(str-inline "a") space (str-inline "{k=v}")]}]
             ["a \\{.c\n" {:t :para :inlines [(str-inline "a") space (str-inline "{.c")]}]
             ["*x*\\{.c}\n" {:t :para :inlines [{:t :emph :inlines [(str-inline "x")]} (str-inline "{.c}")]}]
             ["# a \\{.c}\n" {:t :section :level 1 :attr {:classes [] :props {}} :blocks []
                              :heading [(str-inline "a") space (str-inline "{.c}")]}]
             ["# a \\{.c\\}\n" {:t :section :level 1 :attr {:classes [] :props {}} :blocks []
                                :heading [(str-inline "a") space (str-inline "{.c}")]}]
             ["# a \\{.c} {#id}\n" {:t :section :level 1 :attr {:id "id" :classes [] :props {}} :blocks []
                                    :heading [(str-inline "a") space (str-inline "{.c}")]}]
             ["a \\{.c}\n---\n" {:t :section :level 2 :attr {:classes [] :props {}} :blocks []
                                 :heading [(str-inline "a") space (str-inline "{.c}")]}]]]
      (is (= [expected] (:blocks (parser/parse src))) (pr-str src))))
  (testing "TASK-189: only an escaped brace is exempt -- an escaped
            backslash before a bare brace (`\\\\{.c`) still leaves the
            brace bare, and is still refused"
    (doseq [src ["# a \\\\{.c\n" "a \\\\\\\\{#x\n"]]
      (let [e (is (thrown? clojure.lang.ExceptionInfo (parser/parse src)) (pr-str src))]
        (is (= ::parser/malformed-attributes (:type (ex-data e))) (pr-str src))))))

(deftest backslash-in-attribute-token-test
  (testing "TASK-197: a backslash in an id, a class, or an unquoted value
            is a located error on every construct a group attaches to --
            including a bare `{` closed by an escaped `}`, which flexmark
            reads as a group with the backslash kept in the token"
    (doseq [[src line] [["# a {.c\\}\n" 1]
                        ["# a {#x\\}\n" 1]
                        ["# a {k=v\\}\n" 1]
                        ["Para.\n\n## a {#sec:a\\b}\n" 3]
                        ["See [s]{.c\\x}.\n" 1]
                        ["See [l](u){k=a\\b}.\n" 1]
                        ["See `c`{#a\\b}.\n" 1]
                        ["See ==h=={.a\\b}.\n" 1]
                        ["![cap](a.png){#fig:a\\b}\n" 1]
                        ["![cap](a.png){#fig:a width=a\\b}\n" 1]
                        ["| a |\n|---|\n| b |\n: cap {#tbl:a\\b}\n" 4]
                        ["$$ x $$ {#eq:a\\b}\n" 1]]]
      (let [e (is (thrown? clojure.lang.ExceptionInfo (parser/parse src {:source "d.hdoc"})) (pr-str src))]
        (is (= ::parser/malformed-attributes (:type (ex-data e))) (pr-str src))
        (is (= line (:source-line (ex-data e))) (pr-str src))
        (is (str/includes? (ex-message e) "cannot contain a backslash") (pr-str src)))))
  (testing "a directive header and a one-line `!name{...}` refuse it as a
            malformed directive, as they refuse any other bad header token,
            with the token's column"
    (doseq [[src col] [[":::{note #a\\b}\nx\n:::\n" 10]
                       [":::{note .c\\}\nx\n:::\n" 10]
                       [":::{note k=a\\b}\nx\n:::\n" 10]
                       ["!toc{#a\\b}\n" 6]]]
      (let [e (is (thrown? clojure.lang.ExceptionInfo (parser/parse src {:source "d.hdoc"})) (pr-str src))]
        (is (= ::parser/malformed-directive (:type (ex-data e))) (pr-str src))
        (is (= 1 (:source-line (ex-data e))) (pr-str src))
        (is (= col (:source-column (ex-data e))) (pr-str src)))))
  (testing "a quoted value keeps its backslashes verbatim, on both
            tokenizers, as before"
    (doseq [[src get-attr] [["# a {k=\"a\\b\"}\n" (comp :attr first :blocks)]
                            ["# a {k='a\\b'}\n" (comp :attr first :blocks)]
                            [":::{note k=\"a\\b\"}\nx\n:::\n" (comp :attr first :blocks)]
                            [":::{note k='a\\b'}\nx\n:::\n" (comp :attr first :blocks)]
                            ["$$ x $$ {k=\"a\\b\"}\n" (comp :attr first :blocks)]
                            ["!toc{k=\"a\\b\"}\n" (comp :attr first :blocks)]]]
      (is (= {:classes [] :props {"k" "a\\b"}} (get-attr (parser/parse src))) (pr-str src))))
  (testing "a valid group still parses as before"
    (is (= {:id "sec:a" :classes ["c"] :props {"k" "v" "w" "x y"}}
           (:attr (first (:blocks (parser/parse "# a {#sec:a .c k=v w=\"x y\"}\n"))))))))

(deftest unsupported-construct-test
  (testing "a construct this parser does not yet handle raises a clear, catchable error"
    (let [e (is (thrown? clojure.lang.ExceptionInfo (parser/parse "<div>raw html</div>\n")))]
      (is (= ::parser/unsupported-node (:type (ex-data e))))))
  (testing "an attribute group this parser deliberately does not attach
            anywhere (a fenced code block's attribute-attachment syntax is
            out of TASK-5's scope -- see the task's implementation plan/
            comments) raises the same unsupported-construct error rather
            than silently ignoring or mis-rendering it as prose"
    (let [e (is (thrown? clojure.lang.ExceptionInfo
                         (parser/parse "```\ncode\n```\n{.foo}\n")))]
      (is (= ::parser/unsupported-node (:type (ex-data e))))))
  (testing "ordinary, unrelated prose that happens to precede a
            syntactically well-formed `{...}` attribute group raises the
            same documented unsupported-construct error (matching
            `**bold**{.x}` below) rather than crashing on flexmark's
            internal `TextBase` bookkeeping node -- a TASK-5 review
            finding worse than a merely malformed group (AC #4's
            concern), since this text was never intended to invoke
            attribute-attachment syntax at all"
    (let [e (is (thrown? clojure.lang.ExceptionInfo
                         (parser/parse "This is {.checking} nothing.\n")))]
      (is (= ::parser/unsupported-node (:type (ex-data e))))))
  (testing "an attribute group after strong emphasis (not one of the
            attachable targets) raises the same error -- confirms the
            fix for the TextBase case above stayed consistent with this
            already-existing, already-documented fallback rather than
            changing its behavior"
    (let [e (is (thrown? clojure.lang.ExceptionInfo (parser/parse "**bold**{.x}\n")))]
      (is (= ::parser/unsupported-node (:type (ex-data e))))))
  (testing "a fenced code block whose info string looks like an attempted
            attribute-group attachment on the opening-fence line (e.g.
            ```clojure {.numberLines}```) raises a clear diagnostic
            instead of silently dropping the attempt -- previously this
            resolved `:lang` to just \"clojure\", discarding
            `{.numberLines}` with no diagnostic at all (fenced-code-block
            attribute attachment
            itself remains deliberately out of TASK-5's scope, but
            silently dropping an attempt at it violates AC #4's
            no-silent-drop philosophy just as much as any other node
            type)"
    (let [e (is (thrown? clojure.lang.ExceptionInfo
                         (parser/parse "```clojure {.numberLines}\n(+ 1 2)\n```\n")))]
      (is (= ::parser/unsupported-node (:type (ex-data e))))))
  (testing "a fenced code block with NO language, whose entire info
            string is an attempted attribute group (e.g.
            ```{.numberLines}```), raises the same diagnostic instead of
            silently corrupting `:lang` into the literal string
            \"{.numberLines}\""
    (let [e (is (thrown? clojure.lang.ExceptionInfo
                         (parser/parse "```{.numberLines}\n(+ 1 2)\n```\n")))]
      (is (= ::parser/unsupported-node (:type (ex-data e)))))))

(deftest directive-test
  (testing "a minimal three-colon fenced directive with a name and id
            attribute parses to a Directive node carrying that name, Attr,
            and its nested Blocks (TASK-6 AC #1)"
    (let [doc (parser/parse ":::{note #n:1}\nHello.\n:::\n")]
      (is (= [{:t :directive :name "note" :attr {:id "n:1" :classes [] :props {}}
               :blocks [{:t :para :inlines [(str-inline "Hello.")]}]}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc))))
  (testing "a bare fence with no header at all still parses, with an empty
            name and the default Attr (sec5.5: both are optional)"
    (let [doc (parser/parse ":::\ntext\n:::\n")]
      (is (= [{:t :directive :name "" :attr attr
               :blocks [{:t :para :inlines [(str-inline "text")]}]}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc))))
  (testing "a directive whose header is only a bare name (no other
            attributes), matching SPEC.md sec5.5's own `::::{warning}`
            worked example exactly"
    (let [doc (parser/parse "::::{warning}\nDo not feed hazelnuts.\n::::\n")]
      (is (= [{:t :directive :name "warning" :attr attr
               :blocks [{:t :para :inlines [(str-inline "Do") space (str-inline "not") space
                                            (str-inline "feed") space (str-inline "hazelnuts.")]}]}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc)))))

(deftest nested-directive-test
  (testing "a directive nested inside another parses correctly when the
            outer fence uses more colons than the inner one (TASK-6 AC #2)"
    (let [doc (parser/parse "::::{outer}\n:::{inner}\nx\n:::\n::::\n")]
      (is (= [{:t :directive :name "outer" :attr attr
               :blocks [{:t :directive :name "inner" :attr attr
                         :blocks [{:t :para :inlines [(str-inline "x")]}]}]}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc))))
  (testing "a directive at the same nesting level as ordinary prose and a
            heading still folds correctly into that heading's Section --
            the directive itself is not treated as closing the section
            just because it was parsed as a separate chunk from the
            surrounding text"
    (let [doc (parser/parse "# A\n:::{note}\ninner\n:::\nMore under A.\n")]
      (is (= [{:t :section :level 1 :heading [(str-inline "A")] :attr attr
               :blocks [{:t :directive :name "note" :attr attr
                         :blocks [{:t :para :inlines [(str-inline "inner")]}]}
                        {:t :para :inlines [(str-inline "More") space (str-inline "under") space
                                            (str-inline "A.")]}]}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc)))))

(deftest unterminated-directive-test
  (testing "an unterminated directive fence raises a clear parse
            diagnostic rather than silently swallowing the rest of the
            document (TASK-6 AC #3)"
    (let [e (is (thrown? clojure.lang.ExceptionInfo (parser/parse ":::{note}\nHello.\n")))]
      (is (= ::parser/unterminated-directive (:type (ex-data e))))))
  (testing "an unterminated OUTER fence around a properly-closed inner
            directive raises the same diagnostic"
    (let [e (is (thrown? clojure.lang.ExceptionInfo
                         (parser/parse "::::{outer}\n:::{inner}\nx\n:::\n")))]
      (is (= ::parser/unterminated-directive (:type (ex-data e))))))
  (testing "the diagnostic's :open-line ex-data field reports the actual
            1-based source line where the unclosed fence opened, not the
            end-of-input line count (TASK-6 review: :open-line used to
            always equal (count lines) regardless of where the fence
            opened)"
    (let [e (is (thrown? clojure.lang.ExceptionInfo
                         (parser/parse "a\nb\n:::{note}\nc\nd\ne\n")))]
      (is (= 3 (:open-line (ex-data e))))))
  (testing "for a nested unterminated OUTER fence, :open-line reports
            where the outer fence itself opened, not the inner one"
    (let [e (is (thrown? clojure.lang.ExceptionInfo
                         (parser/parse "x\n::::{outer}\n:::{inner}\ny\n:::\n")))]
      (is (= 2 (:open-line (ex-data e)))))))

(deftest directive-fence-inside-code-block-test
  (testing "a plain fenced code block whose content contains a line of 3+
            colons is not misread as a directive fence -- it must not
            throw ::unterminated-directive on this otherwise-valid
            CommonMark document (TASK-6 review: code-fence shielding)"
    (let [doc (parser/parse "Some paragraph.\n\n```\n:::\n```\n\nMore text.\n")]
      (is (= [{:t :para :inlines [(str-inline "Some") space (str-inline "paragraph.")]}
              {:t :code-block :text ":::\n" :attr attr}
              {:t :para :inlines [(str-inline "More") space (str-inline "text.")]}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc))))
  (testing "a fenced code block containing a pair of colon-run lines
            around ordinary content stays one single, uncorrupted
            :code-block instead of being silently split into a bogus
            nameless directive plus mangled code blocks (TASK-6 review:
            the silent-corruption case, worse than the spurious throw
            above since it raised no diagnostic at all)"
    (let [doc (parser/parse "Some paragraph.\n\n```\n:::\ncode line\n:::\n```\n\nMore text.\n")]
      (is (= [{:t :para :inlines [(str-inline "Some") space (str-inline "paragraph.")]}
              {:t :code-block :text ":::\ncode line\n:::\n" :attr attr}
              {:t :para :inlines [(str-inline "More") space (str-inline "text.")]}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc))))
  (testing "a real directive fence still works correctly immediately
            around a fenced code block that itself contains a colon-run
            line, confirming the shielding does not also swallow genuine
            directive fences outside the code block"
    (let [doc (parser/parse ":::{note}\n```\n:::\n```\n:::\n")]
      (is (= [{:t :directive :name "note" :attr attr
               :blocks [{:t :code-block :text ":::\n" :attr attr}]}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc)))))

(deftest malformed-directive-header-test
  (testing "a directive header with more than one bare name token raises a
            diagnostic rather than silently keeping the first (mirrors
            TASK-5 AC #4's no-silent-drop philosophy applied to this new
            construct)"
    (let [e (is (thrown? clojure.lang.ExceptionInfo (parser/parse ":::{foo bar}\nx\n:::\n")))]
      (is (= ::parser/malformed-directive (:type (ex-data e))))))
  (testing "a directive header not shaped like a single `{...}` group at
            all raises the same diagnostic"
    (let [e (is (thrown? clojure.lang.ExceptionInfo (parser/parse ":::foo\nx\n:::\n")))]
      (is (= ::parser/malformed-directive (:type (ex-data e))))))
  (testing "a directive header token that is neither a bare name, `#id`,
            `.class`, nor `key=val` raises the same diagnostic"
    (let [e (is (thrown? clojure.lang.ExceptionInfo (parser/parse ":::{!!!}\nx\n:::\n")))]
      (is (= ::parser/malformed-directive (:type (ex-data e)))))))

(deftest bang-directive-test
  (testing "TASK-114 AC #1: a bare `!name` line is a complete directive --
            sec5.5's second spelling, for a directive whose whole content
            lives in its header, so there is no body and no closing fence"
    (let [doc (parser/parse "!toc\n")]
      (is (= [{:t :directive :name "toc" :attr attr :blocks []}] (:blocks doc)))
      (is (ast/valid? ast/Document doc))))
  (testing "TASK-114 AC #2: props reach the AST through the one-line form
            exactly as they do through a fence header, spaces and all --
            examples/reveal.clj's own question/answer shape, which is the
            case this spelling exists for"
    (let [doc (parser/parse "!reveal{question=\"a b\" answer=\"c d\"}\n")]
      (is (= [{:t :directive :name "reveal"
               :attr {:classes [] :props {"question" "a b" "answer" "c d"}}
               :blocks []}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc))))
  (testing "TASK-114 AC #3: the group carries the plain sec5.3 three-form
            grammar -- `#id`, `.class` and `key=val` together"
    (let [doc (parser/parse "!fig-note{#note:a .aside k=v}\n")]
      (is (= [{:t :directive :name "fig-note"
               :attr {:id "note:a" :classes ["aside"] :props {"k" "v"}}
               :blocks []}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc))))
  (testing "whitespace between the name and its group is tolerated, as it
            is between a colon run and its own header (`fence-line` trims)"
    (is (= [{:t :directive :name "toc" :attr {:classes [] :props {"x" "1"}} :blocks []}]
           (:blocks (parser/parse "!toc {x=1}\n")))))
  (testing "a `}` inside a quoted value works, because the group is
            captured greedily to the LAST `}` on the line -- the same
            property a `:::` header has and a display-math group does not"
    (is (= {"a" "}"} (-> (parser/parse "!reveal{a=\"}\"}\n") :blocks first :attr :props))))
  (testing "up to 3 leading spaces is still a directive, matching
            `include-line-re`/`directive-fence-re`'s own CommonMark
            leaf-block indentation tolerance; at 4 it is an indented code
            block like any other over-indented line"
    (is (= [{:t :directive :name "toc" :attr attr :blocks []}]
           (:blocks (parser/parse "   !toc\n"))))
    (is (= [{:t :code-block :text "!toc\n" :attr attr}]
           (:blocks (parser/parse "    !toc\n")))))
  (testing "the whole design claim, asserted directly: the two spellings
            are the same MECHANISM, not merely similar ones -- a one-line
            directive and the fence it replaces parse to the identical
            node, so nothing downstream can tell them apart"
    (doseq [[one-line fenced] [["!toc\n" ":::{toc}\n:::\n"]
                               ["!reveal{a=\"x y\"}\n" ":::{reveal a=\"x y\"}\n:::\n"]
                               ["!note{#n:a .aside}\n" ":::{note #n:a .aside}\n:::\n"]]]
      (is (= (:blocks (parser/parse fenced)) (:blocks (parser/parse one-line))) one-line)))
  (testing "TASK-114 AC #6: a one-line directive nests inside a `:::`
            fence -- both spellings are recognized by the same raw-line
            recursion, at any depth"
    (let [doc (parser/parse ":::{note}\n!toc\n:::\n")]
      (is (= [{:t :directive :name "note" :attr attr
               :blocks [{:t :directive :name "toc" :attr attr :blocks []}]}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc)))
    (is (= [{:t :directive :name "a" :attr attr
             :blocks [{:t :directive :name "b" :attr attr
                       :blocks [{:t :directive :name "toc" :attr attr :blocks []}]}]}]
           (:blocks (parser/parse "::::{a}\n:::{b}\n!toc\n:::\n::::\n")))))
  (testing "it ends the run of text before it and starts a fresh one
            after, exactly as a `!include` line and a directive fence do"
    (is (= [{:t :para :inlines [(str-inline "Before.")]}
            {:t :directive :name "toc" :attr attr :blocks []}
            {:t :para :inlines [(str-inline "After.")]}]
           (:blocks (parser/parse "Before.\n!toc\nAfter.\n"))))))

(deftest bang-directive-non-collision-test
  (testing "TASK-114 AC #4: `!include <path>` is still an Include -- text
            after the name that is not a `{...}` group means this was
            never a one-line directive"
    (is (= [{:t :include :src "chapters/a.hdoc"}]
           (:blocks (parser/parse "!include chapters/a.hdoc\n")))))
  (testing "TASK-114 AC #4: a bare `!include` is still an ordinary
            paragraph. `include` is a RESERVED name in this form
            precisely so that it stays one: promoting it to a directive
            named \"include\" would turn a mistyped include into a
            plausible-looking wrong document"
    (is (= [{:t :para :inlines [(str-inline "!include")]}]
           (:blocks (parser/parse "!include\n")))))
  (testing "trailing text that is not a group leaves the line as prose,
            the same tolerance `include-line` gives a bare `!include`"
    (is (= [{:t :para :inlines [(str-inline "!toc") space (str-inline "extra")
                                space (str-inline "words")]}]
           (:blocks (parser/parse "!toc extra words\n")))))
  (testing "TASK-114 AC #8: a CommonMark image at the margin is untouched
            -- `[` is not a name character, so `![` can never open this
            construct"
    (is (= [{:t :para :inlines [{:t :image :src "img.png" :alt "alt" :attr attr}]}]
           (:blocks (parser/parse "![alt](img.png)\n")))))
  (testing "TASK-114 AC #5: a `!name` line inside a fenced code block is
            code content, not a directive -- the shielding
            `parse-directive-region` already consults for a colon run
            covers this construct for free"
    (is (= [{:t :code-block :text "!toc\n" :attr attr}]
           (:blocks (parser/parse "```\n!toc\n```\n")))))
  (testing "TASK-114 AC #5: and inside a `$$` display-math block it is
            raw TeX, for the same reason"
    (is (= [{:t :math-block :tex "x = !toc" :attr attr}]
           (:blocks (parser/parse "$$\nx = !toc\n$$\n")))))
  (testing "a `\\!` at the start of the line escapes the construct back to
            prose: the line no longer begins with `!`, so this raw-line
            scan skips it and flexmark unescapes it as it always has --
            the way out for a paragraph that really does start `!figure`"
    (is (= [{:t :para :inlines [(str-inline "!figure")]}]
           (:blocks (parser/parse "\\!figure\n"))))))

(deftest bang-directive-shares-fence-consequences-test
  (testing "the two consequences `include-line-test` pins for `:::` and
            `!include` side by side hold for a `!name` line too, and are
            asserted here for the same reason they are asserted there --
            so a future change cannot quietly make the three differ.
            First: it is block-level, so one indented into a list ends
            that list and starts a new one after, rather than joining the
            item"
    (is (= [{:t :list :ordered false :tight true
             :items [[{:t :para :inlines [(str-inline "item")]}]] :attr attr}
            {:t :directive :name "toc" :attr attr :blocks []}
            {:t :list :ordered false :tight true
             :items [[{:t :para :inlines [(str-inline "two")]}]] :attr attr}]
           (:blocks (parser/parse "- item\n  !toc\n- two\n")))))
  (testing "second: it splits the run of text around it into separate
            parse chunks, so a footnote definition on one side no longer
            sees its use on the other -- the diagnostic, not silence"
    (let [e (is (thrown? clojure.lang.ExceptionInfo
                         (parser/parse "text[^a]\n!toc\n\n[^a]: note\n")))]
      (is (= ::parser/undefined-footnote (:type (ex-data e)))))))

(deftest malformed-bang-directive-test
  (testing "TASK-114 AC #7: once `!name{` has committed the line to being
            a directive, a group that never closes is a diagnostic rather
            than something that quietly falls back to prose (this file's
            own no-silent-drop ethos, AC #4 of TASK-5)"
    (doseq [source ["!toc{unclosed\n" "!toc{a=\"unterminated}\n" "!toc{a=\"x\"y}\n"]]
      (let [e (is (thrown? clojure.lang.ExceptionInfo (parser/parse source)) source)]
        (is (= ::parser/malformed-directive (:type (ex-data e))) source))))
  (testing "TASK-114 AC #3: a bare word inside the group is a diagnostic,
            not a second name -- the name has already been given outside
            the braces, so what is left is the plain sec5.3 grammar"
    (let [e (is (thrown? clojure.lang.ExceptionInfo (parser/parse "!toc{foo}\n")))]
      (is (= ::parser/malformed-directive (:type (ex-data e))))))
  (testing "a group token that is neither `#id`, `.class` nor `key=val`
            raises the same diagnostic a `:::` header raises for it"
    (let [e (is (thrown? clojure.lang.ExceptionInfo (parser/parse "!toc{!!!}\n")))]
      (is (= ::parser/malformed-directive (:type (ex-data e))))))
  (testing "trailing text AFTER a closed group is an error too, not the
            prose a never-opened group would have left. Once a `{`
            commits the line, the whole rest of it must be one group --
            `!toc{a=1} trailing` is a mistake worth naming, where
            `!toc extra words` (no group at all) is just a paragraph"
    (let [e (is (thrown? clojure.lang.ExceptionInfo (parser/parse "!toc{a=1} trailing\n")))]
      (is (= ::parser/malformed-directive (:type (ex-data e)))))
    (is (= [{:t :para :inlines [(str-inline "!toc") space (str-inline "extra")
                                space (str-inline "words")]}]
           (:blocks (parser/parse "!toc extra words\n")))))
  (testing "a RESERVED name written with a group is refused by name.
            Without this `!include{src=\"a.hdoc\"}` reaches flexmark and
            dies as an ::unsupported-node \"unsupported inline
            construct\", a diagnostic naming neither !include nor the
            rule; a bare `!include` still stays the paragraph it is"
    (doseq [source ["!include{}\n" "!include{src=\"a.hdoc\"}\n"]]
      (let [e (is (thrown? clojure.lang.ExceptionInfo (parser/parse source)) source)]
        (is (= ::parser/malformed-directive (:type (ex-data e))) source)
        (is (= "include" (:name (ex-data e))) source)))
    (is (= [{:t :para :inlines [(str-inline "!include")]}]
           (:blocks (parser/parse "!include\n"))))))

(deftest quoted-attribute-value-test
  (let [directive-props (fn [source]
                          (-> (parser/parse source) :blocks first :attr :props))]
    (testing "AC #1: a directive header carries a quoted value containing
              spaces, and the spaces reach the AST intact. `collapsable`'s
              summary IS its human-readable label, so a one-word label was
              the only kind expressible before this"
      (is (= {"summary" "A note on squirrels"}
             (directive-props ":::{collapsable summary=\"A note on squirrels\"}\nx\n:::\n")))
      (testing "single quotes work the same, matching flexmark's own
                grammar for an ordinary attribute group"
        (is (= {"summary" "A note on squirrels"}
               (directive-props ":::{collapsable summary='A note on squirrels'}\nx\n:::\n")))))
    (testing "AC #2: an ordinary attribute group accepts the same quoted
              form. flexmark's AttributesExtension tokenizes this one, so
              this asserts the two paths agree rather than that this file
              implements it"
      (is (= {"title" "A hazel tree"}
             (-> (parser/parse "![A tree.](t.png){#fig:x title=\"A hazel tree\"}\n")
                 :blocks first :content :inlines first :attr :props)))
      (is (= {"title" "A hazel tree"}
             (-> (parser/parse "# Hi {#sec:hi title=\"A hazel tree\"}\n\ntext\n")
                 :blocks first :attr :props)))
      (testing "and so does a display-math group -- the third site, and
                the other one this file tokenizes itself, so unlike the
                two above this one does exercise split-attr-tokens"
        (is (= {"note" "mass energy"}
               (-> (parser/parse "$$\nE = mc^2\n$$ {#eq:mass note=\"mass energy\"}\n")
                   :blocks first :attr :props)))))
    (testing "AC #3: a quote inside a value is escaped by switching the
              delimiter -- there is no backslash escape, because flexmark
              rejects one outright and the two group syntaxes must agree"
      (is (= {"summary" "A \"note\" on squirrels"}
             (directive-props ":::{collapsable summary='A \"note\" on squirrels'}\nx\n:::\n")))
      (is (= {"summary" "It's a note"}
             (directive-props ":::{collapsable summary=\"It's a note\"}\nx\n:::\n")))
      (is (= {"title" "A \"hazel\" tree"}
             (-> (parser/parse "![A tree.](t.png){#fig:x title='A \"hazel\" tree'}\n")
                 :blocks first :content :inlines first :attr :props))
          "the ordinary group escapes the same way"))
    (testing "AC #4: an unterminated quoted value raises the group's own
              malformed diagnostic rather than silently truncating"
      (let [e (is (thrown? clojure.lang.ExceptionInfo
                           (parser/parse ":::{collapsable summary=\"A note}\nx\n:::\n")))]
        (is (= ::parser/malformed-directive (:type (ex-data e)))))
      (let [e (is (thrown? clojure.lang.ExceptionInfo
                           (parser/parse "$$\nx\n$$ {#eq:a note=\"unclosed}\n")))]
        (is (= ::parser/malformed-attributes (:type (ex-data e)))))
      (let [e (is (thrown? clojure.lang.ExceptionInfo
                           (parser/parse "![A tree.](t.png){#fig:x title=\"A hazel tree}\n")))]
        (is (= ::parser/malformed-attributes (:type (ex-data e))))))
    (testing "AC #5: the unquoted forms all parse exactly as before"
      (let [directive (first (:blocks (parser/parse ":::{theorem #thm:main .tight key=val}\nx\n:::\n")))]
        (is (= "theorem" (:name directive)))
        (is (= {:classes ["tight"] :props {"key" "val"} :id "thm:main"} (:attr directive))))
      (testing "including a value or id containing an apostrophe, which a
                naive tokenizer would read as an opening quote and then
                report as unterminated. A quote only opens a run where a
                value can start -- right after `key=`"
        (is (= "thm:it's" (-> (parser/parse ":::{theorem #thm:it's}\nx\n:::\n")
                              :blocks first :attr :id))))
      (testing "and a still-rejected unquoted multi-word value, which is
                two tokens and so two bare names"
        (let [e (is (thrown? clojure.lang.ExceptionInfo
                             (parser/parse ":::{collapsable summary=A note}\nx\n:::\n")))]
          (is (= ::parser/malformed-directive (:type (ex-data e)))))))
    (testing "an empty quoted value is a present-but-empty prop, not a
              dropped one"
      (is (= {"summary" ""}
             (directive-props ":::{collapsable summary=\"\"}\nx\n:::\n"))))
    (testing "a quote opens a value only after a complete `key=`, never
              inside an #id or .class token. Matching that unanchored let
              #thm:a=\"x y\" through as the id `thm:a=x y` -- a form the
              grammar does not have, which then reached output as a
              \\label (found by review)"
      (doseq [source [":::{theorem #thm:a=\"x y\"}\nb\n:::\n"
                      ":::{theorem .cls=\"a b\"}\nb\n:::\n"]]
        (let [e (is (thrown? clojure.lang.ExceptionInfo (parser/parse source)) source)]
          (is (= ::parser/malformed-directive (:type (ex-data e))) source))))
    (testing "text after a closing quote is an error, not silently glued
              on: key=\"a b\"x used to yield the value `a bx`. flexmark
              rejects the same input on its own path"
      (let [e (is (thrown? clojure.lang.ExceptionInfo
                           (parser/parse ":::{d title=\"a b\"x}\nb\n:::\n")))]
        (is (= ::parser/malformed-directive (:type (ex-data e)))))
      (let [e (is (thrown? clojure.lang.ExceptionInfo
                           (parser/parse ":::{d s=\"a b\"c=\"d\"}\nb\n:::\n")))]
        (is (= ::parser/malformed-directive (:type (ex-data e))))))
    (testing "a bare value that merely STARTS with a quote is now an
              unterminated value rather than a literal token: `a='Fermat`
              used to parse to the value `'Fermat`. Recorded as a
              deliberate behavior change, since flexmark rejects it too"
      (let [e (is (thrown? clojure.lang.ExceptionInfo
                           (parser/parse ":::{d a='Fermat}\nb\n:::\n")))]
        (is (= ::parser/malformed-directive (:type (ex-data e))))))
    (testing "a } inside a quoted value works where the group is matched
              greedily -- a directive header and flexmark's own group --
              but not in display math, whose fence regex ends the group at
              the first }. That limit is [^}]*'s and predates quoting"
      (is (= {"t" "a } b"} (directive-props ":::{d t=\"a } b\"}\nx\n:::\n")))
      (is (= {"t" "a } b"}
             (-> (parser/parse "![t](t.png){#fig:x t=\"a } b\"}\n")
                 :blocks first :content :inlines first :attr :props))))))

(deftest inline-math-test
  (testing "single-`$`-delimited inline math parses to MathInline with the
            raw TeX preserved exactly (TASK-7 AC #1)"
    (let [doc (parser/parse "Consider $e^{i\\pi}+1=0$ carefully.\n")]
      (is (= [{:t :para :inlines [(str-inline "Consider") space
                                  {:t :math-inline :tex "e^{i\\pi}+1=0"} space
                                  (str-inline "carefully.")]}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc))))
  (testing "special LaTeX characters that CommonMark itself gives other
            meaning to -- backslash, braces, caret, underscore, asterisk --
            all survive untouched inside the math span rather than being
            backslash-unescaped or parsed as emphasis/strong (AC #1)"
    (let [doc (parser/parse "Let $a_i * b^j$ be a term.\n")]
      (is (= [{:t :para :inlines [(str-inline "Let") space
                                  {:t :math-inline :tex "a_i * b^j"} space
                                  (str-inline "be") space (str-inline "a") space
                                  (str-inline "term.")]}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc)))))

(deftest display-math-test
  (testing "a whole `$$ ... $$` display math block on one line parses to
            MathBlock, with no attribute group at all (TASK-7 AC #2)"
    (let [doc (parser/parse "$$ e^{i\\pi} + 1 = 0 $$\n")]
      (is (= [{:t :math-block :tex "e^{i\\pi} + 1 = 0" :attr attr}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc))))
  (testing "a `$$` fence spanning multiple lines parses to MathBlock, with
            the raw TeX lines joined by newline (AC #2)"
    (let [doc (parser/parse "$$\nx = 1\ny = 2\n$$\n")]
      (is (= [{:t :math-block :tex "x = 1\ny = 2" :attr attr}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc))))
  (testing "a display math block sitting among ordinary prose and a
            heading still folds correctly into that heading's Section,
            confirming this file's line-scan approach to display math
            composes correctly with section folding"
    (let [doc (parser/parse "# A\n$$ x = 1 $$\nMore under A.\n")]
      (is (= [{:t :section :level 1 :heading [(str-inline "A")] :attr attr
               :blocks [{:t :math-block :tex "x = 1" :attr attr}
                        {:t :para :inlines [(str-inline "More") space (str-inline "under") space
                                            (str-inline "A.")]}]}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc)))))

(deftest display-math-attribute-test
  (testing "an id attribute group following a single-line display math
            block attaches to the MathBlock's Attr, making it
            referenceable (TASK-7 AC #3)"
    (let [doc (parser/parse "$$ e^{i\\pi} + 1 = 0 $$ {#eq:euler}\n")]
      (is (= [{:t :math-block :tex "e^{i\\pi} + 1 = 0"
               :attr {:id "eq:euler" :classes [] :props {}}}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc))))
  (testing "an id/class attribute group following a multi-line display
            math block's own closing fence attaches the same way (AC #3)"
    (let [doc (parser/parse "$$\nx = 1\ny = 2\n$$ {#eq:sys .highlight}\n")]
      (is (= [{:t :math-block :tex "x = 1\ny = 2"
               :attr {:id "eq:sys" :classes ["highlight"] :props {}}}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc)))))

(deftest math-currency-test
  (testing "the well-known pandoc `$NNN,NNN and $NNN,NNN` currency case
            stays entirely literal rather than being misparsed as math,
            since neither `$` is a valid opener/closer pair under the
            whitespace-adjacency heuristic (TASK-7 AC #4)"
    (let [doc (parser/parse "$20,000 and $30,000.\n")]
      (is (= [{:t :para :inlines [(str-inline "$20,000") space (str-inline "and") space
                                  (str-inline "$30,000.")]}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc))))
  (testing "two plain currency mentions in the same sentence, with no
            adjacent real math, both stay literal (AC #4)"
    (let [doc (parser/parse "It costs $5 and $10 today.\n")]
      (is (= [{:t :para :inlines [(str-inline "It") space (str-inline "costs") space
                                  (str-inline "$5") space (str-inline "and") space
                                  (str-inline "$10") space (str-inline "today.")]}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc))))
  (testing "a currency range with no space before the second `$`
            (`$5/$10`, `$5-$10`, `$5,$10`) stays entirely literal rather
            than misparsing `$5/` as math and leaving a stray `10`,
            because a candidate closing `$` immediately followed by a
            digit fails the digit-adjacency half of the heuristic even
            though it isn't preceded by whitespace (TASK-7 review)"
    (doseq [text ["Tickets are $5/$10 today." "Tickets are $5-$10 today."
                  "Tickets are $5,$10 today."]]
      (let [doc (parser/parse (str text "\n"))]
        (is (= 1 (count (:blocks doc))))
        (is (= :para (:t (first (:blocks doc)))))
        (is (not-any? #(= :math-inline (:t %)) (:inlines (first (:blocks doc)))))
        (is (ast/valid? ast/Document doc)))))
  (testing "a backslash-escaped `\\$` (CommonMark sec2.4) resolves to a
            literal `$` and never even reaches the math trigger (AC #4)"
    (let [doc (parser/parse "This costs \\$5 today.\n")]
      (is (= [{:t :para :inlines [(str-inline "This") space (str-inline "costs") space
                                  (str-inline "$5") space (str-inline "today.")]}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc))))
  (testing "a stray `$$` in the middle of a paragraph (not display math,
            since it is not the start of its own line) stays fully
            literal rather than leaving a stray single `$` next to a
            bogus MathInline (AC #4)"
    (let [doc (parser/parse "Save $$ for later.\n")]
      (is (= [{:t :para :inlines [(str-inline "Save") space (str-inline "$$") space
                                  (str-inline "for") space (str-inline "later.")]}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc))))
  (testing "an unterminated multi-line display math attempt (no closing
            `$$` fence anywhere in the document) falls back to literal
            text rather than raising a diagnostic, unlike a directive's
            own dedicated colon-fence syntax (AC #4: `$` collides with
            far more common plain-text use)"
    (let [doc (parser/parse "$$\nx = 1\nno closer\n")]
      (is (= [{:t :para :inlines [(str-inline "$$") {:t :soft-break}
                                  (str-inline "x") space (str-inline "=") space (str-inline "1")
                                  {:t :soft-break}
                                  (str-inline "no") space (str-inline "closer")]}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc)))))

(deftest malformed-math-attribute-group-test
  (testing "a display math block's trailing attribute group token that is
            neither `#id`, `.class`, nor `key=val` raises a diagnostic
            rather than silently keeping/dropping it -- sec5.7's math
            attribute group is the plain sec5.3 grammar, not a directive
            header's bare-name form (TASK-7, mirroring TASK-5 AC #4's
            no-silent-drop philosophy)"
    (let [e (is (thrown? clojure.lang.ExceptionInfo (parser/parse "$$ x $$ {bareword}\n")))]
      (is (= ::parser/malformed-attributes (:type (ex-data e)))))))

(deftest unterminated-math-fence-bounded-scan-test
  (testing "an unterminated bare `$$` opener (with no content of its own,
            matching `math-fence-line-re` and therefore actually reaching
            `math-fence-bounded-close-index`) does not greedily pair with
            a later, unrelated `$$ ... $$` block across a blank line/
            heading/paragraph -- the opener falls back to literal text and
            everything after it, including the heading, still parses
            correctly (TASK-7 review: previously the whole span between
            the stray opener and the unrelated closer was silently
            absorbed into one bogus MathBlock, and the heading never
            became a Section at all)"
    (let [doc (parser/parse "$$\n\n# Heading\n\npara\n\n$$ x = 1 $$\n")]
      (is (= [{:t :para :inlines [(str-inline "$$")]}
              {:t :section :level 1 :heading [(str-inline "Heading")] :attr attr
               :blocks [{:t :para :inlines [(str-inline "para")]}
                        {:t :math-block :tex "x = 1" :attr attr}]}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc)))))

(deftest math-block-inside-list-item-test
  (testing "a display-math block indented inside a list item stays in that
            item: ONE list, both items numbered in one sequence, each
            holding its own MathBlock with its own id. The fence used to be
            pulled out of the line stream before flexmark saw the list,
            which split the list in two and restarted its numbering
            (TASK-70)"
    (let [doc (parser/parse (str "1. First:\n\n   $$\n   x = 1\n   $$ {#eq:a}\n\n"
                                 "2. Second:\n\n   $$\n   y = 2\n   $$ {#eq:b}\n"))]
      (is (= [{:t :list :ordered true :tight false :attr attr
               :items [[{:t :para :inlines [(str-inline "First:")]}
                        {:t :math-block :tex "x = 1" :attr {:id "eq:a" :classes [] :props {}}}]
                       [{:t :para :inlines [(str-inline "Second:")]}
                        {:t :math-block :tex "y = 2" :attr {:id "eq:b" :classes [] :props {}}}]]}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc))))
  (testing "a fence written at the start of a line is still handled by the
            line pre-pass, unchanged"
    (is (= [{:t :math-block :tex "z = 3" :attr {:id "eq:top" :classes [] :props {}}}]
           (:blocks (parser/parse "$$\nz = 3\n$$ {#eq:top}\n")))))
  (testing "an INDENTED fence at the top level -- which the pre-pass no
            longer claims -- is still a MathBlock, recognized on the
            paragraph instead"
    (is (= [{:t :math-block :tex "w = 4" :attr attr}]
           (:blocks (parser/parse "  $$\n  w = 4\n  $$\n")))))
  (testing "the whole paragraph has to be the block: one that merely starts
            with a fence and carries prose after the closer stays prose,
            since a wrong guess inside a container silently swallows a
            sentence"
    (is (= [:list] (mapv :t (:blocks (parser/parse "1. $$ x $$ and prose after\n"))))))
  (testing "the TeX keeps the alignment the author wrote inside the formula:
            only the indentation every continuation line shares is stripped"
    (let [item (first (get-in (first (:blocks (parser/parse "- x\n\n  $$\n  a\n    b\n  $$\n")))
                              [:items 0]))
          math (second (get-in (first (:blocks (parser/parse "- x\n\n  $$\n  a\n    b\n  $$\n")))
                               [:items 0]))]
      (is (= :para (:t item)))
      (is (= {:t :math-block :tex "a\n  b" :attr attr} math)))))

(deftest math-block-inside-block-quote-test
  (testing "a display-math block written inside a block quote with `> ` on
            every line is a MathBlock in that quote, as it already was in a
            list item, a footnote and a directive. The `> ` the quote puts
            on the TeX and closing lines used to reach the fence match, so
            the block stayed prose (TASK-188)"
    (let [eq {:t :math-block :tex "a\n  b" :attr {:id "eq:q" :classes [] :props {}}}]
      (is (= [{:t :block-quote :blocks [eq]}]
             (:blocks (parser/parse "> $$\n> a\n>   b\n> $$ {#eq:q}\n"))))
      (is (= [{:t :block-quote :blocks [{:t :block-quote :blocks [eq]}]}]
             (:blocks (parser/parse "> > $$\n> > a\n> >   b\n> > $$ {#eq:q}\n")))
          "a nested quote's own `> > ` is stripped the same way")
      (is (= [{:t :block-quote
               :blocks [{:t :list :ordered false :tight false :attr attr
                         :items [[{:t :para :inlines [(str-inline "x")]} eq]]}]}]
             (:blocks (parser/parse "> - x\n>\n>   $$\n>   a\n>     b\n>   $$ {#eq:q}\n")))
          "a list item inside a quote"))
    (is (= [{:t :block-quote :blocks [{:t :math-block :tex "x" :attr attr}]}]
           (:blocks (parser/parse "> $$\n  x\n  $$\n")))
        "the lazily continued spelling, which already worked, is unchanged"))
  (testing "the whole paragraph still has to be the block: an unclosed fence
            in a quote stays prose"
    (is (= [:para] (mapv :t (:blocks (first (:blocks (parser/parse "> $$\n> x\n"))))))))
  (testing "a math block in a footnote body and in a directive body, the
            other containers, is unaffected"
    (is (= [:para :math-block]
           (->> (parser/parse "Text[^1].\n\n[^1]: a\n\n    $$\n    x\n    $$\n")
                :blocks first :inlines (filter #(= :note (:t %))) first :blocks (mapv :t))))
    (is (= [:math-block]
           (->> (parser/parse "::: {.note}\n$$\nx\n$$\n:::\n") :blocks first :blocks (mapv :t))))))

(deftest math-block-blank-line-test
  (testing "a display-math block whose TeX contains blank lines is one
            MathBlock, not a fence that ends at the first of them -- a
            `cases` environment spacing out its branches is ordinary LaTeX,
            and under the old rule the rest of the body spilled into prose
            and failed the build on `{cases}` several lines from the cause
            (TASK-71)"
    (let [doc (parser/parse (str "$$\n"
                                 "r = \\begin{cases}\n"
                                 "\n"
                                 "  1, & x > 0 \\\\\n"
                                 "\n"
                                 "  0, & \\text{otherwise}\n"
                                 "\n"
                                 "\\end{cases}\n"
                                 "$$ {#eq:coa}\n"))]
      (is (= [{:t :math-block
               :tex (str "r = \\begin{cases}\n\n  1, & x > 0 \\\\\n\n"
                         "  0, & \\text{otherwise}\n\n\\end{cases}")
               :attr {:id "eq:coa" :classes [] :props {}}}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc))))
  (testing "the guard the bound exists for still holds: a stray opener
            followed by a blank line and a HEADING does not pair with a
            later fence, and the heading still becomes a Section"
    (let [doc (parser/parse "$$\n\n# Heading\n\npara\n\n$$\nx = 1\n$$\n")]
      (is (= [:para :section] (mapv :t (:blocks doc))))
      (is (= [(str-inline "$$")] (:inlines (first (:blocks doc)))))
      (is (ast/valid? ast/Document doc))))
  (testing "the other three block markers end the scan the same way a
            heading does -- a code fence, a directive fence and a thematic
            break -- since a line of TeX begins with none of them"
    (doseq [marker ["```\ncode\n```" ":::{note}\nx\n:::" "---"]]
      (let [doc (parser/parse (str "$$\n\n" marker "\n\n$$\n"))]
        (is (= [(str-inline "$$")] (:inlines (first (:blocks doc))))
            (str "opener should stay literal before " (pr-str marker))))))
  (testing "a LIST marker is deliberately not a boundary: a display-math
            line can begin with `-` (a negative leading term), and calling
            that a boundary would put back the false negative this rule
            removes"
    (let [doc (parser/parse "$$\nx =\n\n- \\frac{a}{b}\n$$\n")]
      (is (= [:math-block] (mapv :t (:blocks doc))))
      (is (ast/valid? ast/Document doc))))
  (testing "an unterminated fence at end of input is still literal text"
    (let [doc (parser/parse "$$\nunterminated\n")]
      (is (= [:para] (mapv :t (:blocks doc))))
      (is (ast/valid? ast/Document doc)))))

(deftest directive-fence-inside-math-block-test
  (testing "a `:::`-shaped line occurring inside a legitimate multi-line
            `$$ ... $$` display math block is treated as ordinary TeX
            content, not misinterpreted as a directive fence -- mirroring
            TASK-6's own code-fence shielding one layer up (TASK-7
            review: previously the directive scanner ran over the whole
            document before display math was ever considered, orphaning
            both `$$` lines as literal text and wrapping the content in a
            phantom directive)"
    (let [doc (parser/parse "$$\n:::\nx = 1\n:::\n$$\n")]
      (is (= [{:t :math-block :tex ":::\nx = 1\n:::" :attr attr}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc)))))

(deftest figure-test
  (testing "a standalone image with an id attribute becomes a numbered
            Figure whose caption is its own alt text, and whose content
            is that same image (TASK-8 AC #1, sec5.8's own worked example).
            The id MOVES to the Figure rather than being copied: leaving
            it on the inner Image too made every figure in every document
            trip haselnuss.resolver/structural-diagnostics' duplicate-id
            check (found while reviewing TASK-36)"
    (let [doc (parser/parse "![A hazel tree in autumn.](tree.png){#fig:tree}\n")]
      (is (= [{:t :figure
               :content {:t :para
                         :inlines [{:t :image :src "tree.png" :alt "A hazel tree in autumn."
                                    :attr {:classes [] :props {}}}]}
               :caption [(str-inline "A") space (str-inline "hazel") space (str-inline "tree")
                         space (str-inline "in") space (str-inline "autumn.")]
               :attr {:id "fig:tree" :classes [] :props {}}}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc))))
  (testing "the promoted Figure produces no duplicate-id diagnostic, which
            is the whole point of moving rather than copying the id"
    (let [doc (parser/parse "![A tree](tree.png){#fig:tree}\n")]
      (is (empty? (resolver/structural-diagnostics doc)))))
  (testing "the Image keeps its own props -- presentational, and
            meaningful on the image element itself -- while the id and
            the classes move to the Figure, which is the numbering
            target, the anchor, and what a stylesheet reaches for.
            Copying the props onto both put a sizing prop on the emitted
            <figure> as a literal HTML attribute (TASK-65)"
    (let [doc (parser/parse "![A tree](tree.png){#fig:tree .photo w=3cm}\n")
          [figure] (:blocks doc)
          [image] (get-in figure [:content :inlines])]
      (is (= {:classes ["photo"] :props {"w" "3cm"}} (:attr image)))
      (is (= {:id "fig:tree" :classes ["photo"] :props {}} (:attr figure)))))
  (testing "a standalone image with no id stays an ordinary Para/Image --
            not a numbering target downstream (TASK-8 AC #3) -- even when
            it does carry other attributes (a class here), confirming
            Figure promotion is gated specifically on `:id`, not on the
            mere presence of an attribute group at all"
    (let [doc (parser/parse "![a tree](tree.png){.photo}\n")]
      (is (= [{:t :para
               :inlines [{:t :image :src "tree.png" :alt "a tree"
                          :attr {:classes ["photo"] :props {}}}]}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc)))))

(deftest figure-caption-markup-test
  (testing "TASK-69: an image caption carrying emphasis, strong, inline
            math and a citation reaches the Figure's caption as those
            Inlines. It used to be flattened by flexmark's own
            TextCollectingVisitor first --
            which cost the emphasis its meaning and the math and the
            citation their existence, since that visitor collects no text
            at all for this parser's own synthetic nodes, so the caption
            emitted two stray \"and\"s and nothing else"
    (let [doc (parser/parse (str "![Caption with *emph* and **strong** and $x \\times y$"
                                 " and [@germanconcept]](tree.png){#fig:a}\n"))
          [figure] (:blocks doc)]
      (is (= [(str-inline "Caption") space (str-inline "with") space
              {:t :emph :inlines [(str-inline "emph")]} space
              (str-inline "and") space
              {:t :strong :inlines [(str-inline "strong")]} space
              (str-inline "and") space
              {:t :math-inline :tex "x \\times y"} space
              (str-inline "and") space
              {:t :cite :items [{:key "germanconcept" :mode :normal}]}]
             (:caption figure)))
      (is (ast/valid? ast/Document doc))))
  (testing "a cross-reference in a caption is a CrossRef there too, so
            the resolver numbers it like any other"
    (let [doc (parser/parse "![As in @fig:tree](leaf.png){#fig:leaf}\n")
          [figure] (:blocks doc)]
      (is (= {:t :cross-ref :label "fig:tree"} (last (:caption figure))))))
  (testing "AC #3: a caption of ordinary prose is unchanged -- the same
            Str/Space vector `text->inlines` produced before"
    (let [doc (parser/parse "![A hazel tree in autumn.](tree.png){#fig:tree}\n")
          [figure] (:blocks doc)]
      (is (= [(str-inline "A") space (str-inline "hazel") space (str-inline "tree")
              space (str-inline "in") space (str-inline "autumn.")]
             (:caption figure)))))
  (testing "the image's own :alt stays a plain string -- an HTML alt=
            attribute is character data -- but it is now flattened from
            the same converted Inlines rather than by flexmark's
            TextCollectingVisitor, so nothing vanishes from it either.
            The visitor collected no text at all for this parser's own
            synthetic math and citation nodes, which is how an alt came
            out as \"Rate of , after \" (found by review: the caption was
            fixed and the same defect survived in the attribute a screen
            reader actually receives)"
    (let [doc (parser/parse (str "![Rate of *growth*, $x \\times y$, after [@knuth]"
                                 " and @sec:y](r.png){#fig:a}\n"))
          [figure] (:blocks doc)]
      (is (= "Rate of growth, x \\times y, after [@knuth] and @sec:y"
             (get-in figure [:content :inlines 0 :alt])))))
  (testing "an alt with no id -- an ordinary inline image, never promoted
            to a Figure -- is flattened the same way, since the defect
            was in the flattening rather than in the promotion"
    (let [doc (parser/parse "![Rate of $x$](r.png)\n")
          [para] (:blocks doc)]
      (is (= "Rate of x" (get-in para [:inlines 0 :alt])))))
  (testing "a citation's prefix and locator survive into the alt too, and
            each mode keeps the spelling it was written in -- dropping a
            locator would be the same silent loss in smaller print
            (found by review)"
    (let [alt (fn [source] (get-in (first (:blocks (parser/parse source))) [:inlines 0 :alt]))]
      (is (= "After [see @knuth1984 p. 42]" (alt "![After [see @knuth1984, p. 42]](t.png)\n")))
      (is (= "As @knuth1984 shows" (alt "![As @knuth1984 shows](t.png)\n"))
          "the bare author form has no brackets in the source and gets none here")
      (is (= "Year [-@knuth1984]" (alt "![Year [-@knuth1984]](t.png)\n")))
      (is (= "Two [@a; @b]" (alt "![Two [@a; @b]](t.png)\n")))))
  (testing "a soft line break inside an alt is a space in the attribute
            and a :soft-break in the caption, which both targets render
            as the same word space -- so a caption wrapped over two
            source lines typesets exactly as it did (AC #3)"
    (let [doc (parser/parse "![line one\n   line two](t.png){#fig:f}\n")
          [figure] (:blocks doc)]
      (is (= "line one line two" (get-in figure [:content :inlines 0 :alt])))
      (is (= {:t :soft-break} (nth (:caption figure) 3)))))
  (testing "a HARD line break is authored intent and survives as one --
            \\\\ in the .tex, <br/> in the HTML -- rather than being
            flattened to a space the way it was"
    (let [doc (parser/parse "![one  \n   two](t.png){#fig:h}\n")
          [figure] (:blocks doc)]
      (is (= {:t :line-break} (second (:caption figure)))))))

(deftest figure-caption-footnote-test
  (testing "TASK-69: a footnote inside an image's alt text is refused,
            naming itself. LaTeX cannot set a footnote inside a float:
            native mode fails the compile outright (Argument of
            \\caption@ydblarg has an extra }, no PDF at all) and
            computed mode typesets the marker and silently loses the note
            body -- both confirmed against a real pdflatex. Refused in
            the PARSER so it holds in both targets at once; an
            emitter-side guard would have failed the LaTeX build and
            passed the HTML one on the same source (found by review)"
    (let [e (is (thrown? clojure.lang.ExceptionInfo
                         (parser/parse (str "![A caption with a footnote[^n] in it]"
                                            "(p.png){#fig:b}\n\n[^n]: The note body.\n"))))]
      (is (= ::parser/footnote-in-image-alt (:type (ex-data e))))
      (is (str/includes? (ex-message e) "footnote"))))
  (testing "and in an inline image in prose, which becomes no caption and
            no float at all -- the reason is that an alt text is a plain
            string and a footnote has no plain-text form, not anything
            about LaTeX, so it holds here too (found by review: the
            first message talked about floats and would have read as a
            non-sequitur on this document)"
    (let [e (is (thrown? clojure.lang.ExceptionInfo
                         (parser/parse (str "See ![a photo[^n] here](pic.png) inline.\n\n"
                                            "[^n]: Body.\n"))))]
      (is (= ::parser/footnote-in-image-alt (:type (ex-data e))))
      (is (not (str/includes? (ex-message e) "float")))))
  (testing "and the message names WHICH image, since a thesis has a
            hundred of them -- :src rides in the ex-data, which
            haselnuss.cli's own error-detail-keys already prints"
    (let [e (is (thrown? clojure.lang.ExceptionInfo
                         (parser/parse "![alt[^n]](p.png)\n\n[^n]: Body.\n")))]
      (is (= "p.png" (:src (ex-data e))))
      (is (str/includes? (ex-message e) "p.png")))))

(deftest figure-caption-is-prose-test
  (testing "TASK-69: an alt text is prose now that it is converted rather
            than flattened, so the constructs this parser does not
            support in prose it does not support there either -- an HTML
            entity, a raw inline HTML tag. Each raises the same
            documented error it raises in a paragraph, rather than being
            silently flattened into the caption (the old behaviour), and
            that is the trade this task's AC #1 asks for: render it or
            name it, never drop it"
    (doseq [source ["![Caption &copy; 2019](p.png){#fig:d}\n"
                    "![Caption <b>bold</b>](p.png){#fig:d}\n"]]
      (let [e (is (thrown? clojure.lang.ExceptionInfo (parser/parse source)))]
        (is (= ::parser/unsupported-node (:type (ex-data e))) source))))
  (testing "and the same constructs raise the same error in an ordinary
            paragraph, which is what makes this consistency rather than
            a new restriction on captions"
    (doseq [source ["Prose with &copy; in it.\n"
                    "Prose with <b>bold</b> in it.\n"]]
      (let [e (is (thrown? clojure.lang.ExceptionInfo (parser/parse source)))]
        (is (= ::parser/unsupported-node (:type (ex-data e))) source)))))

(deftest table-column-widths-test
  (let [table "| A | B | C |\n|---|---|---|\n| 1 | 2 | 3 |\n"
        parse-table (fn [caption] (first (:blocks (parser/parse (str table caption)))))]
    (testing "TASK-74 AC #1: a table can give its columns widths, written
              in the attribute group the caption line already carries --
              the one place a pipe table has for per-table metadata. The
              AST's Col has carried a :width since sec4.3 and nothing an
              author could write ever set it"
      (let [t (parse-table ": Caption {#tbl:x widths=\"20% 50% 30%\"}\n")]
        (is (= [{:width "20%"} {:width "50%"} {:width "30%"}] (:colspec t)))
        (is (ast/valid? ast/Document {:meta {} :blocks [t]}))))
    (testing "the widths prop is CONSUMED rather than left on the Table's
              own Attr: haselnuss.emit.html writes a prop as a literal
              HTML attribute, and there is no widths attribute -- the
              same reason TASK-65 keeps an image's sizing props off the
              enclosing figure"
      (is (= {:id "tbl:x" :classes [] :props {}}
             (:attr (parse-table ": Caption {#tbl:x widths=\"20% 50% 30%\"}\n")))))
    (testing "alignment and width compose: the delimiter row still says
              how a column is set, and the caption line how wide it is"
      (let [t (first (:blocks (parser/parse
                               (str "| A | B |\n|:--|--:|\n| 1 | 2 |\n"
                                    ": Caption {widths=\"30% 70%\"}\n"))))]
        (is (= [{:align :left :width "30%"} {:align :right :width "70%"}] (:colspec t)))))
    (testing "AC #3: a table with no widths is exactly what it was --
              the Col carries no :width key at all, not an empty one"
      (is (= [{} {} {}] (:colspec (parse-table ": Caption {#tbl:x}\n")))))
    (testing "AC #4: a count that disagrees with the columns is reported,
              naming the table -- the mistake an edited table makes"
      (let [e (is (thrown? clojure.lang.ExceptionInfo
                           (parse-table ": Caption {#tbl:x widths=\"20% 50%\"}\n")))]
        (is (= ::parser/column-width-mismatch (:type (ex-data e))))
        (is (str/includes? (ex-message e) "tbl:x"))
        (is (str/includes? (ex-message e) "3 column"))))
    (testing "AC #4: a value that is not a width both targets understand
              is reported. px is CSS-only and \\linewidth is LaTeX-only:
              either would set one target and silently do nothing in the
              other"
      (doseq [bad ["5px" "\\linewidth" "0.2" "wide"]]
        (let [e (is (thrown? clojure.lang.ExceptionInfo
                             (parse-table (str ": Caption {#tbl:x widths=\"20% " bad " 30%\"}\n"))))]
          (is (= ::parser/invalid-column-width (:type (ex-data e))) bad))))
    (testing "AC #4: percentages summing past 100% are reported -- the
              same mistake this feature exists to fix, arrived at from
              the other direction"
      (let [e (is (thrown? clojure.lang.ExceptionInfo
                           (parse-table ": Caption {#tbl:x widths=\"20% 50% 40%\"}\n")))]
        (is (= ::parser/column-widths-too-wide (:type (ex-data e))))
        (is (str/includes? (ex-message e) "110"))))
    (testing "the sum is exact decimal arithmetic, not floating point: a
              three-column table split 33.33/33.33/33.34 is one an
              author would really write, and refusing it for a
              floating-point crumb would be a worse bug than the one the
              check exists to catch"
      (is (= [{:width "33.33%"} {:width "33.33%"} {:width "33.34%"}]
             (:colspec (parse-table ": Caption {#tbl:x widths=\"33.33% 33.33% 33.34%\"}\n"))))
      (is (= [{:width "100%"} {:width "0%"} {:width "0%"}]
             (:colspec (parse-table ": Caption {#tbl:x widths=\"100% 0% 0%\"}\n")))
          "and exactly 100% is not over it"))
    (testing "and absolute widths are carried as written, with no sum
              check: 2cm and 20% cannot be added up, and refusing a table
              for mixing units would be inventing a restriction rather
              than catching an error"
      (is (= [{:width "2cm"} {:width "5cm"} {:width "3cm"}]
             (:colspec (parse-table ": Caption {#tbl:x widths=\"2cm 5cm 3cm\"}\n"))))
      (is (= [{:width "60%"} {:width "5cm"} {:width "60%"}]
             (:colspec (parse-table ": Caption {#tbl:x widths=\"60% 5cm 60%\"}\n")))))))

(deftest pipe-table-test
  (testing "a pipe table with a trailing `: caption {#id}` line becomes a
            Table with head row, body rows, per-column alignment, caption,
            and Attr (TASK-8 AC #2, sec5.9's own worked example)"
    (let [doc (parser/parse (str "| Nut      | Yield |\n"
                                 "|:---------|------:|\n"
                                 "| Hazel    |   9.1 |\n"
                                 "| Walnut   |   7.4 |\n"
                                 ": Nut yields by species. {#tbl:yields}\n"))]
      (is (= [{:t :table
               :head {:cells [{:blocks [{:t :para :inlines [(str-inline "Nut")]}] :align :left}
                              {:blocks [{:t :para :inlines [(str-inline "Yield")]}] :align :right}]}
               :rows [{:cells [{:blocks [{:t :para :inlines [(str-inline "Hazel")]}] :align :left}
                               {:blocks [{:t :para :inlines [(str-inline "9.1")]}] :align :right}]}
                      {:cells [{:blocks [{:t :para :inlines [(str-inline "Walnut")]}] :align :left}
                               {:blocks [{:t :para :inlines [(str-inline "7.4")]}] :align :right}]}]
               :caption [(str-inline "Nut") space (str-inline "yields") space (str-inline "by")
                         space (str-inline "species.")]
               :colspec [{:align :left} {:align :right}]
               :attr {:id "tbl:yields" :classes [] :props {}}}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc))))
  (testing "a pipe table with no caption/id trailer at all still parses to
            a Table node -- head/rows/colspec but an empty caption and
            `default-attr` (no `:id`), i.e. not a numbering target
            downstream (TASK-8 AC #3)"
    (let [doc (parser/parse "| A | B |\n|---|---|\n| 1 | 2 |\n")]
      (is (= [{:t :table
               :head {:cells [{:blocks [{:t :para :inlines [(str-inline "A")]}]}
                              {:blocks [{:t :para :inlines [(str-inline "B")]}]}]}
               :rows [{:cells [{:blocks [{:t :para :inlines [(str-inline "1")]}]}
                               {:blocks [{:t :para :inlines [(str-inline "2")]}]}]}]
               :caption []
               :colspec [{} {}]
               :attr attr}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc))))
  (testing "a `: ...`-shaped paragraph separated from a table by a blank
            line is NOT absorbed as that table's caption -- sec5.9's
            trailing-caption-line syntax requires immediate adjacency, so
            the table gets an empty caption/default Attr and the unrelated
            paragraph converts on its own, confirming the caption-detection
            boundary isn't over-eager"
    (let [doc (parser/parse "| A | B |\n|---|---|\n| 1 | 2 |\n\n: not a caption\n")]
      (is (= [{:t :table
               :head {:cells [{:blocks [{:t :para :inlines [(str-inline "A")]}]}
                              {:blocks [{:t :para :inlines [(str-inline "B")]}]}]}
               :rows [{:cells [{:blocks [{:t :para :inlines [(str-inline "1")]}]}
                               {:blocks [{:t :para :inlines [(str-inline "2")]}]}]}]
               :caption []
               :colspec [{} {}]
               :attr attr}
              {:t :para :inlines [(str-inline ":") space (str-inline "not") space (str-inline "a")
                                  space (str-inline "caption")]}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc))))
  (testing "a caption trailer is still detected when the table sits inside
            a blockquote or a list item, not just at the top level -- the
            caption-line check must read a paragraph's *content* text
            (stripped of the enclosing `> `/list marker), not its raw
            source text, or the leading marker corrupts the `: caption`
            match (TASK-8 review regression: this previously only had
            ad-hoc REPL verification, no committed fixture)"
    (let [bq-doc (parser/parse (str "> | A | B |\n"
                                    "> |---|---|\n"
                                    "> | 1 | 2 |\n"
                                    "> : In a blockquote. {#tbl:bq}\n"))]
      (is (= [{:t :block-quote
               :blocks [{:t :table
                         :head {:cells [{:blocks [{:t :para :inlines [(str-inline "A")]}]}
                                        {:blocks [{:t :para :inlines [(str-inline "B")]}]}]}
                         :rows [{:cells [{:blocks [{:t :para :inlines [(str-inline "1")]}]}
                                         {:blocks [{:t :para :inlines [(str-inline "2")]}]}]}]
                         :caption [(str-inline "In") space (str-inline "a") space
                                   (str-inline "blockquote.")]
                         :colspec [{} {}]
                         :attr {:id "tbl:bq" :classes [] :props {}}}]}]
             (:blocks bq-doc)))
      (is (ast/valid? ast/Document bq-doc)))
    (let [list-doc (parser/parse (str "- | A | B |\n"
                                      "  |---|---|\n"
                                      "  | 1 | 2 |\n"
                                      "  : In a list item. {#tbl:li}\n"))]
      (is (= [{:t :list :ordered false :tight true :attr attr
               :items [[{:t :table
                         :head {:cells [{:blocks [{:t :para :inlines [(str-inline "A")]}]}
                                        {:blocks [{:t :para :inlines [(str-inline "B")]}]}]}
                         :rows [{:cells [{:blocks [{:t :para :inlines [(str-inline "1")]}]}
                                         {:blocks [{:t :para :inlines [(str-inline "2")]}]}]}]
                         :caption [(str-inline "In") space (str-inline "a") space
                                   (str-inline "list") space (str-inline "item.")]
                         :colspec [{} {}]
                         :attr {:id "tbl:li" :classes [] :props {}}}]]}]
             (:blocks list-doc)))
      (is (ast/valid? ast/Document list-doc)))))

(deftest bracket-line-under-table-test
  (testing "TASK-208 AC #1: a line wholly in `[...]` directly under a
            table -- flexmark's MultiMarkdown caption shape, which the
            parser used to drop -- is an ordinary paragraph after the
            table, parsed exactly as it is after a blank line, at the top
            level, in a block quote and in a list item"
    (doseq [[open cont] [["" ""] ["> " "> "] ["- " "  "]]
            line ["[@k]" "[@a; @b]" "[-@k]" "[^1]" "[note]" "  [note]  " "[@k]\nmore" "[x](y)"]
            :let [table (str open "| A | B |\n" cont "|---|---|\n" cont "| 1 | 2 |\n")
                  body (str/join "\n" (map #(str cont %) (str/split-lines line)))
                  note-def (when (str/includes? line "^1") "\n[^1]: n\n")
                  doc (parser/parse (str table body "\n" note-def))
                  apart (parser/parse (str table cont "\n" body "\n" note-def))
                  blocks (fn [d] (let [b (:blocks d)]
                                   (if (= "" open) b (let [c (first b)]
                                                       (if (= :list (:t c)) (first (:items c)) (:blocks c))))))]]
      (is (= (blocks apart) (blocks doc)) (pr-str [open line]))
      (is (= [:table :para] (mapv :t (blocks doc))) (pr-str [open line]))
      (is (= [] (:caption (first (blocks doc)))) (pr-str [open line]))
      (is (ast/valid? ast/Document doc) (pr-str [open line]))))
  (testing "the citation, footnote and text it holds survive"
    (let [after (fn [src] (second (:blocks (parser/parse (str "| A |\n|---|\n" src)))))]
      (is (= {:t :para :inlines [{:t :cite :items [{:key "k" :mode :normal}]}]} (after "[@k]\n")))
      (is (= :note (:t (first (:inlines (after "[^1]\n\n[^1]: n\n"))))))
      (is (= {:t :para :inlines [(str-inline "[note]")]} (after "[note]\n")))
      ;; A head-only table: the line right under the delimiter row.
      (is (= {:t :para :inlines [{:t :cite :items [{:key "k" :mode :normal}]}]}
             (second (:blocks (parser/parse "| A |\n|---|\n[@k]\n")))))))
  (testing "TASK-208 AC #2: a Haselnuss `: caption` line, with or without an
            attribute group, still captions the table, and a bracket line
            after a blank line is its own paragraph"
    (let [doc (parser/parse "| A |\n|---|\n| 1 |\n: Cap. {#tbl:x .c}\n\n[@k]\n")]
      (is (= [(str-inline "Cap.")] (:caption (first (:blocks doc)))))
      (is (= {:id "tbl:x" :classes ["c"] :props {}} (:attr (first (:blocks doc)))))
      (is (= [:table :para] (mapv :t (:blocks doc)))))))

(deftest cross-ref-test
  (testing "SPEC.md's own worked example -- four bare `@prefix:label`
            references in one sentence -- each parses to a CrossRef with
            that exact `prefix:label` string as its label (TASK-9 AC #1)"
    (let [doc (parser/parse "As shown in @fig:tree and proved in @thm:main, see also @sec:intro and @eq:euler.\n")]
      (is (= [{:t :para
               :inlines [(str-inline "As") space (str-inline "shown") space (str-inline "in") space
                         {:t :cross-ref :label "fig:tree"} space (str-inline "and") space
                         (str-inline "proved") space (str-inline "in") space
                         {:t :cross-ref :label "thm:main"} (str-inline ",") space (str-inline "see") space
                         (str-inline "also") space {:t :cross-ref :label "sec:intro"} space
                         (str-inline "and") space {:t :cross-ref :label "eq:euler"} (str-inline ".")]}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc)))))

(deftest citation-bracket-test
  (testing "a single bracketed citation combining a locator/suffix on its
            first key with a second, `;`-separated key with no locator at
            all parses to one Cite node with one CiteItem per key (TASK-9
            AC #2)"
    (let [doc (parser/parse "Hazelnuts are optimal [@knuth1984, p. 42; @smith2020].\n")]
      (is (= [{:t :para
               :inlines [(str-inline "Hazelnuts") space (str-inline "are") space (str-inline "optimal") space
                         {:t :cite
                          :items [{:key "knuth1984" :mode :normal
                                   :suffix [(str-inline "p.") space (str-inline "42")]}
                                  {:key "smith2020" :mode :normal}]}
                         (str-inline ".")]}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc))))
  (testing "a citation item's own prefix text (before its `@key`) is parsed
            into Inlines too, not only its suffix"
    (let [doc (parser/parse "See [see @doe99, chap. 3].\n")]
      (is (= [{:t :para
               :inlines [(str-inline "See") space
                         {:t :cite
                          :items [{:key "doe99" :mode :normal
                                   :prefix [(str-inline "see")]
                                   :suffix [(str-inline "chap.") space (str-inline "3")]}]}
                         (str-inline ".")]}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc)))))

(deftest citation-bracket-across-lines-test
  (testing "TASK-165 AC #1: a hard-wrapped citation group parses to the
            same Cite as its one-line form, wherever the break falls"
    (doseq [[wrapped one-line] [["[see\n@smith2020]" "[see @smith2020]"]
                                ["[@a;\n@b]" "[@a; @b]"]
                                ["[@a\n; @b]" "[@a; @b]"]
                                ["[see\n@fig:x]" "[see @fig:x]"]
                                ["[@a,\np. 4]" "[@a, p. 4]"]
                                ["[@a\n, p. 4]" "[@a, p. 4]"]
                                ["[@a;\n-@b, p. 4\n]" "[@a; -@b, p. 4]"]
                                ["x [see\n@a; @b] y" "x [see @a; @b] y"]]]
      (testing (pr-str wrapped)
        (let [inlines (fn [s] (-> (parser/parse (str s "\n")) :blocks first :inlines))]
          (is (= (inlines one-line) (inlines wrapped)))
          (is (some #(= :cite (:t %)) (inlines wrapped)))))))
  (testing "a break inside a prefix or suffix stays a break there"
    (is (= [{:t :cite :items [{:key "a" :mode :normal
                               :prefix [(str-inline "see") {:t :soft-break} (str-inline "also")]
                               :suffix [(str-inline "p.") {:t :soft-break} (str-inline "4")]}]}]
           (-> (parser/parse "[see\nalso @a, p.\n4]\n") :blocks first :inlines))))
  (testing "inside a block quote and a list item, whose continuation lines
            carry a prefix or indentation"
    (is (= [{:t :block-quote
             :blocks [{:t :para
                       :inlines [{:t :cite :items [{:key "a" :mode :normal :prefix [(str-inline "see")]}
                                                   {:key "b" :mode :normal}]}]}]}]
           (:blocks (parser/parse "> [see\n> @a; @b]\n"))))
    (is (= [{:t :cite :items [{:key "a" :mode :normal :prefix [(str-inline "see")]}]}]
           (-> (parser/parse "- [see\n  @a]\n") :blocks first :items first first :inlines))))
  (testing "a stray unclosed `[` does not pair with a later link's,
            footnote marker's or bracket's `]`, on one line or across a
            break (TASK-165 review)"
    (doseq [sep ["\n" " "]]
      (testing (pr-str sep)
        (let [inlines (fn [s] (-> (parser/parse s) :blocks first :inlines))
              author {:t :cite :items [{:key "smith2020" :mode :author}]}
              lead [(str-inline "In") space (str-inline "[0,") space (str-inline "1)") space
                    (str-inline "it") space (str-inline "holds.") (if (= "\n" sep) {:t :soft-break} space)
                    (str-inline "As") space author space (str-inline "shows")]]
          (is (= (conj lead space {:t :link :target "http://x" :inlines [(str-inline "here")] :attr attr}
                       (str-inline "."))
                 (inlines (str "In [0, 1) it holds." sep "As @smith2020 shows [here](http://x).\n"))))
          (is (= (conj lead {:t :note :blocks [{:t :para :inlines [(str-inline "Note.")]}]} (str-inline "."))
                 (inlines (str "In [0, 1) it holds." sep "As @smith2020 shows[^1].\n\n[^1]: Note.\n"))))
          (is (= (conj lead space (str-inline "in") space (str-inline "[Fig.") space (str-inline "2]."))
                 (inlines (str "In [0, 1) it holds." sep "As @smith2020 shows in [Fig. 2].\n"))))))))
  (testing "a continuation line cut short at the `@`/`;`/`]` is not read as
            a setext underline or thematic break in the prefix/suffix
            (TASK-165 review)"
    (let [items (fn [s] (-> (parser/parse s) :blocks first :inlines first :items))
          brk {:t :soft-break}]
      (is (= [{:key "a" :mode :normal :prefix [(str-inline "see") brk (str-inline "---")]}]
             (items "[see\n--- @a]\n")))
      (is (= [{:key "a" :mode :normal :prefix [(str-inline "see") brk (str-inline "---")]}]
             (items "[see\n\\--- @a]\n")))
      (is (= [{:key "a" :mode :normal :prefix [(str-inline "see") brk (str-inline "___")]}]
             (items "[see\n___ @a]\n")))
      (is (= [{:key "a" :mode :normal
               :suffix [(str-inline "p.") space (str-inline "4") brk (str-inline "-")]}
              {:key "b" :mode :normal}]
             (items "[@a, p. 4\n-; @b]\n")))
      (is (= [{:key "a" :mode :normal
               :suffix [(str-inline "p.") space (str-inline "4") brk (str-inline "===")]}]
             (items "[@a, p. 4\n===]\n")))))
  (testing "a blank line still ends the paragraph, and the bracket with it"
    (is (= [[(str-inline "[see")]
            [{:t :cite :items [{:key "a" :mode :author}]} (str-inline ";") space
             {:t :cite :items [{:key "b" :mode :author}]} (str-inline "]")]]
           (map :inlines (:blocks (parser/parse "[see\n\n@a; @b]\n"))))))
  (testing "AC #2: the Cite takes the line of its opening `[`, and every
            reference after it keeps its own line"
    (let [doc (parser/parse "---\ntitle: T\n---\n\na [see\n@b]\n@fig:y\n[@c;\n@d] @e\n"
                            {:source "doc.hdoc"})
          refs (filter #(#{:cite :cross-ref} (:t %)) (-> doc :blocks first :inlines))]
      (is (= [[:cite ["b"] 5] [:cross-ref nil 7] [:cite ["c" "d"] 8] [:cite ["e"] 9]]
             (map (fn [r] [(:t r) (some->> (:items r) (mapv :key)) (:line (ast/source r))]) refs)))
      (is (every? #(= "doc.hdoc" (:file (ast/source %))) refs)))))

(deftest citation-bracket-code-math-and-block-like-affix-test
  (let [inlines (fn [s] (-> (parser/parse s) :blocks first :inlines))
        items (fn [s] (-> (inlines s) first :items))
        brk {:t :soft-break}]
    (testing "TASK-167 AC #1: a `]` or `[` inside a code span or inline math
              is that span's content, so it neither closes the citation group
              nor ends the search for its `]`"
      (is (= [(str-inline "a") space
              {:t :cite :items [{:key "b" :mode :normal
                                 :prefix [(str-inline "x") space {:t :code :text "code]"} space
                                          (str-inline "and")]}]}]
             (inlines "a [x `code]` and @b]\n")))
      (is (= [{:key "b" :mode :normal
               :prefix [(str-inline "x") space {:t :math-inline :tex "f]"} space (str-inline "and")]}]
             (items "[x $f]$ and @b]\n")))
      (is (= [{:key "b" :mode :normal :prefix [(str-inline "see") space {:t :code :text "a[0]"}]}]
             (items "[see `a[0]` @b]\n")))
      (is (= [{:key "b" :mode :normal :prefix [(str-inline "x") space {:t :code :text "a`]"}]}]
             (items "[x ``a`]`` @b]\n"))
          "a code span closes only at a backtick run of its own length")
      (is (= [{:key "a" :mode :normal :suffix [(str-inline "p.") brk {:t :code :text "]"}]}]
             (items "[@a, p.\n`]`]\n"))
          "on a wrapped line too")
      (is (= [{:key "b" :mode :normal :prefix [(str-inline "x") space {:t :math-inline :tex "a]"}]}
              {:key "c" :mode :normal}]
             (items "[x $a]$ @b; @c]\n"))))
    (testing "a backtick run or `$` that opens no span is literal, so a `]`
              after it still closes the bracket"
      (doseq [src ["[x `` ` ] @b]\n" "[x $$ ] @b]\n" "[x $ ] @b]\n" "[x $a ] @b]\n"]]
        (testing (pr-str src)
          (is (not= :cite (:t (first (inlines src)))))
          (is (= {:t :cite :items [{:key "b" :mode :author}]}
                 (last (butlast (inlines src))))))))
    (testing "AC #2: a prefix or suffix whose first line starts like a block
              is inline text inside the citation, on one line or wrapped"
      (doseq [[src expected]
              [["[# x @a]\n" [{:key "a" :mode :normal :prefix [(str-inline "#") space (str-inline "x")]}]]
               ["[- x @a]\n" [{:key "a" :mode :normal :prefix [(str-inline "-") space (str-inline "x")]}]]
               ["[> x @a]\n" [{:key "a" :mode :normal :prefix [(str-inline ">") space (str-inline "x")]}]]
               ["[+ x @a]\n" [{:key "a" :mode :normal :prefix [(str-inline "+") space (str-inline "x")]}]]
               ["[1. x @a]\n" [{:key "a" :mode :normal :prefix [(str-inline "1.") space (str-inline "x")]}]]
               ["[1) x @a]\n" [{:key "a" :mode :normal :prefix [(str-inline "1)") space (str-inline "x")]}]]
               ["[# *x* @a]\n" [{:key "a" :mode :normal
                                 :prefix [(str-inline "#") space {:t :emph :inlines [(str-inline "x")]}]}]]
               ["[# x\nfoo @a]\n" [{:key "a" :mode :normal
                                    :prefix [(str-inline "#") space (str-inline "x") brk (str-inline "foo")]}]]
               ["[- x\nfoo @a]\n" [{:key "a" :mode :normal
                                    :prefix [(str-inline "-") space (str-inline "x") brk (str-inline "foo")]}]]
               ["[> x\nfoo @a]\n" [{:key "a" :mode :normal
                                    :prefix [(str-inline ">") space (str-inline "x") brk (str-inline "foo")]}]]
               ["[1. x\nfoo @a]\n" [{:key "a" :mode :normal
                                     :prefix [(str-inline "1.") space (str-inline "x") brk (str-inline "foo")]}]]
               ["[@a, # x]\n" [{:key "a" :mode :normal :suffix [(str-inline "#") space (str-inline "x")]}]]
               ["[@a, + x\nfoo]\n" [{:key "a" :mode :normal
                                     :suffix [(str-inline "+") space (str-inline "x") brk (str-inline "foo")]}]]
               ["[@a, > x\n---]\n" [{:key "a" :mode :normal
                                     :suffix [(str-inline ">") space (str-inline "x") brk (str-inline "---")]}]]
               ["[@a;\n2. x @b]\n" [{:key "a" :mode :normal}
                                    {:key "b" :mode :normal :prefix [(str-inline "2.") space (str-inline "x")]}]]]]
        (testing (pr-str src)
          (is (= expected (items src))))))
    (testing "a fragment that is indented code only for its leading spaces
              keeps its inline markup (TASK-167 review)"
      (let [emph-x [{:t :emph :inlines [(str-inline "x")]}]]
        (is (= [{:key "a" :mode :normal :prefix emph-x}] (items "[    *x* @a]\n")))
        (is (= [{:key "a" :mode :normal :prefix emph-x}] (items "[\n    *x* @a]\n")))
        (is (= [{:key "a" :mode :normal} {:key "b" :mode :normal :prefix emph-x}]
               (-> (parser/parse "- foo [@a;\n      *x* @b]\n")
                   :blocks first :items first first :inlines last :items)))))
    (testing "a leading fence run is escaped whole, so it cannot pair with a
              later backtick run the paragraph read as literal (TASK-167
              review)"
      (is (= [{:key "a" :mode :normal
               :prefix [(str-inline "```") space (str-inline "x") brk (str-inline "foo") space
                        (str-inline "``")]}]
             (items "[``` x\nfoo `` @a]\n")))
      (is (= [{:key "a" :mode :normal :prefix [(str-inline "~~~") space (str-inline "x")]}]
             (items "[~~~ x @a]\n"))))
    (testing "TASK-171 AC #1: an affix whose lines look like a table stays
              inline text, keeping its breaks and its markup"
      (let [bar (str-inline "|")
            row [bar space (str-inline "a") space bar]]
        (doseq [[src expected]
                [["[| a |\n|---| @c]\n" [{:key "c" :mode :normal :prefix (conj row brk (str-inline "|---|"))}]]
                 ["[| a |\n| b |\n|---| @c]\n"
                  [{:key "c" :mode :normal
                    :prefix (-> row (conj brk bar space (str-inline "b") space bar brk (str-inline "|---|")))}]]
                 ["[*x* |\n--- | --- @c]\n"
                  [{:key "c" :mode :normal
                    :prefix [{:t :emph :inlines [(str-inline "x")]} space bar brk (str-inline "---") space bar
                             space (str-inline "---")]}]]
                 ["[| a |\n    | :-: | @c]\n"
                  [{:key "c" :mode :normal :prefix (conj row brk bar space (str-inline ":-:") space bar)}]]
                 ["[- a |\n|---| @c]\n"
                  [{:key "c" :mode :normal
                    :prefix [(str-inline "-") space (str-inline "a") space bar brk (str-inline "|---|")]}]]
                 ["[@c, | a |\n|---|]\n" [{:key "c" :mode :normal :suffix (conj row brk (str-inline "|---|"))}]]]]
          (testing (pr-str src)
            (is (= expected (items src)))))
        (testing "with no line escaped to get there, so a code span over the
                  delimiter row keeps its text (TASK-171 review)"
          (is (= [{:key "c" :mode :normal
                   :prefix [bar space (str-inline "a") space {:t :code :text "x\n|---|\ny"}]}]
                 (-> (parser/parse "foo\n[| a `x\n|---|\ny` @c]\n") :blocks first :inlines last :items))))))
    (testing "a `;` or `@` inside a code span or inline math neither splits
              the group nor is taken as a key"
      (is (= [{:key "c" :mode :normal :prefix [(str-inline "x") space {:t :code :text "a;b"}]}]
             (items "[x `a;b` @c]\n")))
      (is (= [{:key "a" :mode :normal :prefix [{:t :code :text "@x"} space (str-inline "y")]}]
             (items "[`@x` y @a]\n")))
      (is (= [{:key "c" :mode :normal
               :prefix [(str-inline "x") space {:t :code :text "a]@b"} space (str-inline "y")]}]
             (items "[x `a]@b` y @c]\n")))
      (is (= [{:key "c" :mode :normal :prefix [(str-inline "x") space {:t :math-inline :tex "a;b"}]}
              {:key "d" :mode :normal}]
             (items "[x $a;b$ @c; @d]\n")))
      (is (= [{:key "a" :mode :normal :suffix [{:t :code :text "x;y"}]}]
             (items "[@a, `x;y`]\n")))
      (is (= [(str-inline "[") {:t :code :text "@x"} space (str-inline "y]")]
             (inlines "[`@x` y]\n"))
          "a bracket whose only `@` is inside a code span is no citation"))
    (testing "escapes and empty segments split as before"
      (is (= [{:key "a" :mode :normal}] (items "[@a;]\n")))
      (is (= [(str-inline "[a") space (str-inline ";") space (str-inline "b") space
              {:t :cite :items [{:key "c" :mode :author}]} (str-inline "]")]
             (inlines "[a \\; b @c]\n"))))))

(deftest citation-trailing-blank-segment-test
  (testing "TASK-207: a whitespace-only last segment after a `;` is dropped
            as an empty one is, so `[@k; ]` is the citation `[@k;]` is, and
            `[@k; ](u)` the citation `[@k;](u)` is, followed by `(u)`"
    (doseq [[src cite] [["[@k; ]\n" {:t :cite :items [{:key "k" :mode :normal}]}]
                        ["[@k;  \t]\n" {:t :cite :items [{:key "k" :mode :normal}]}]
                        ["[@k;\n]\n" {:t :cite :items [{:key "k" :mode :normal}]}]
                        ["[@a; @b; ]\n" {:t :cite :items [{:key "a" :mode :normal} {:key "b" :mode :normal}]}]
                        ["[@k, p. 1; ]\n" {:t :cite :items [{:key "k" :mode :normal
                                                             :suffix [(str-inline "p.") space (str-inline "1")]}]}]
                        ["[-@k; ; ]\n" {:t :cite :items [{:key "k" :mode :year}]}]]]
      (is (= [cite] (-> (parser/parse src) :blocks first :inlines)) src))
    (doseq [[blank bare] [["[@k; ](u)\n" "[@k;](u)\n"] ["[@fig:x; ](u)\n" "[@fig:x;](u)\n"]]]
      (is (= (parser/parse bare) (parser/parse blank)) blank)
      (is (= [:cite :str] (mapv :t (-> (parser/parse blank) :blocks first :inlines))) blank)))
  (testing "only a trailing one: a blank segment between two keys still
            makes the bracket no citation, as an empty one does"
    (doseq [src ["[@a; ; @b]\n" "[@a;; @b]\n"]]
      (is (= :str (-> (parser/parse src) :blocks first :inlines first :t)) src)))
  (testing "a bracket of nothing but `;` and whitespace is still no citation"
    (doseq [src ["[; ]\n" "[ ]\n" "[ ; ; ]\n"]]
      (is (every? #{:str :space} (map :t (-> (parser/parse src) :blocks first :inlines))) src)))
  (testing "`cite-segments` keeps a lone whitespace-only text whole: it has
            no `;` to cut at"
    (is (= [" "] (parser/cite-segments " ")))
    (is (= ["x"] (parser/cite-segments "x; ")))
    (is (= [] (parser/cite-segments "; ")))))

(deftest citation-author-in-text-test
  (testing "a bare, unbracketed `@key` citation (no colon, sec5.11's
            'author-in-text' form) parses to a Cite node with a single
            CiteItem whose mode is `:author` (TASK-9 AC #3)"
    (let [doc (parser/parse "Bare author-in-text: @knuth1984 showed that hazelnuts keep well.\n")]
      (is (= [{:t :para
               :inlines [(str-inline "Bare") space (str-inline "author-in-text:") space
                         {:t :cite :items [{:key "knuth1984" :mode :author}]} space
                         (str-inline "showed") space (str-inline "that") space (str-inline "hazelnuts") space
                         (str-inline "keep") space (str-inline "well.")]}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc)))))

(deftest cross-ref-citation-disambiguation-test
  (testing "a bare `@prefix:label` and a bare `@key` side by side each
            become their own distinct node -- a CrossRef and an
            author-in-text Cite, respectively -- never confused for one
            another (TASK-9 AC #4)"
    (let [doc (parser/parse "See @fig:tree, then @knuth1984.\n")]
      (is (= [{:t :para
               :inlines [(str-inline "See") space {:t :cross-ref :label "fig:tree"} (str-inline ",") space
                         (str-inline "then") space {:t :cite :items [{:key "knuth1984" :mode :author}]}
                         (str-inline ".")]}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc))))
  (testing "a bracketed citation right next to an ordinary link and an
            ordinary bracketed shortcut reference (no `@` at all) -- only
            the citation becomes a Cite node; the other two brackets are
            untouched by this parser's own citation-bracket detection (AC
            #4, the other direction: an ordinary bracket is never
            misread as a citation)"
    (let [doc (parser/parse "See [a link](http://example.com), [a shortcut], and [@jones2019].\n")]
      (is (= [{:t :para
               :inlines [(str-inline "See") space
                         {:t :link :target "http://example.com"
                          :inlines [(str-inline "a") space (str-inline "link")]
                          :attr attr}
                         ;; `shortcut],` is one word since TASK-160 -- what
                         ;; the escaped `\[a shortcut\],` re-parses to.
                         (str-inline ",") space (str-inline "[a") space (str-inline "shortcut],")
                         space (str-inline "and") space
                         {:t :cite :items [{:key "jones2019" :mode :normal}]}
                         (str-inline ".")]}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc)))))

;; --- TASK-9 review regression coverage --------------------------------------
;;
;; A clojure-reviewer pass on TASK-9 found four real bugs (findings #1-#4)
;; with no fixture coverage at all: `mini-parse-inlines`/
;; `citation-segment->item` assumed a citation segment's own prefix/suffix
;; text always mini-parses to a single Paragraph, which crashed the whole
;; document parse whenever it instead mini-parsed to a non-Paragraph block
;; (a list item, blockquote, code fence, heading, or thematic break --
;; findings #1/#2, fixed together since they share one root cause);
;; `citation-bracket-content?`'s `every?` check was vacuously true over the
;; empty vector `String/split` yields for an all-`;` bracket, misreading
;; `[;]`/`[;;;]` as citations with zero CiteItems (finding #3); and the
;; bracket form didn't honor CommonMark's `\@` backslash-escape the bare
;; form already does, leaking the escaping backslash into the AST as a
;; spurious `:prefix` (finding #4). A fifth, lower-priority finding (#5)
;; about the bare cross-ref colon heuristic having no shape validation is
;; partially, cheaply addressed (see `cross-ref-prefix-shape?`) and its
;; own remaining, deliberately accepted scope limit documented in that
;; section's own docstring rather than fixed here.

(deftest citation-prefix-suffix-non-paragraph-fragment-test
  (testing "a citation segment's prefix/suffix text that starts with (or
            entirely is) a CommonMark block marker -- a bullet/ordered
            list marker, an ATX heading `#`, a blockquote `>`, a fenced-
            code backtick run, or a thematic-break run -- mini-parses to
            a non-Paragraph block instead of an ordinary Paragraph.
            Previously this crashed the whole document parse (a
            BulletListItem/OrderedListItem has Block children
            `convert-inline` has no branch for -- review finding #1) or,
            for an empty block, silently dropped the user's literal text
            as `[]` instead of the CiteItem's `:prefix`/`:suffix` (review
            finding #2). Since TASK-167 the fragment's opening marker is
            escaped and it is parsed again as an ordinary Paragraph, so
            the trailing space the old literal fallback kept is trimmed
            like any prefix's."
    (doseq [[source cite-key mode field expected]
            [;; Crash repros (finding #1): a dash immediately before `@`
             ;; (mode :year, prefix "-" alone), a dash-space prefix, a
             ;; dash suffix (with and without a locator after it), and an
             ;; ordered-list marker prefix.
             ["[--@smith04]\n" "smith04" :year :prefix [(str-inline "-")]]
             ["[- @doe99]\n" "doe99" :normal :prefix [(str-inline "-")]]
             ["[@doe99 -]\n" "doe99" :normal :suffix [(str-inline "-")]]
             ["[@doe99, - foo]\n" "doe99" :normal :suffix
              [(str-inline "-") space (str-inline "foo")]]
             ["[1. @doe99]\n" "doe99" :normal :prefix [(str-inline "1.")]]
             ;; Data-loss repros (finding #2): an empty heading,
             ;; blockquote, fenced-code marker, and thematic break.
             ["[# @doe99]\n" "doe99" :normal :prefix [(str-inline "#")]]
             ["[> @doe99]\n" "doe99" :normal :prefix [(str-inline ">")]]
             ["[``` @doe99]\n" "doe99" :normal :prefix [(str-inline "```")]]
             ["[*** @doe99]\n" "doe99" :normal :prefix [(str-inline "***")]]]]
      (testing (str "source " (pr-str source) " never crashes and keeps its literal text")
        (let [doc (parser/parse source)]
          (is (= [{:t :para
                   :inlines [{:t :cite
                              :items [(assoc {:key cite-key :mode mode} field expected)]}]}]
                 (:blocks doc)))
          (is (ast/valid? ast/Document doc)))))))

(deftest citation-bracket-no-marker-declined-test
  (testing "a bracket made up only of `;` characters -- with no `@key`
            marker anywhere, not even one -- is declined the same way an
            ordinary empty `[]` (or `[@]`) already is, not misclassified
            as a citation with zero CiteItems (TASK-9 review finding #3:
            `every?` over the *empty* vector `(str/split \";\" #\";\")`
            yields is vacuously true, since `String/split` drops trailing
            empty tokens, so the old check wrongly accepted these)"
    (let [doc (parser/parse "[;]\n")]
      (is (= [{:t :para :inlines [(str-inline "[;]")]}] (:blocks doc)))
      (is (ast/valid? ast/Document doc))))
  (testing "the same holds for a run of several bare semicolons"
    (let [doc (parser/parse "[;;;]\n")]
      (is (= [{:t :para :inlines [(str-inline "[;;;]")]}] (:blocks doc)))
      (is (ast/valid? ast/Document doc)))))

(deftest citation-bracket-escaped-at-sign-test
  (testing "a bracket whose only `@` is backslash-escaped stays fully
            literal, exactly like the bare `\\@fig:tree` form already
            honors that same CommonMark sec2.4 escape, instead of still
            being swallowed into a citation with the escaping backslash
            leaking into the AST as a spurious `:prefix` value (TASK-9
            review finding #4)"
    (let [doc (parser/parse "[\\@doe99]\n")]
      (is (= [{:t :para :inlines [(str-inline "[@doe99]")]}] (:blocks doc)))
      (is (ast/valid? ast/Document doc)))))

(deftest citation-bracket-attribute-group-test
  (let [c-attr {:classes ["c"] :props {}}
        author (fn [k] {:t :cite :items [{:key k :mode :author}]})
        inlines-of (fn [src] (-> (parser/parse src) :blocks first :inlines))]
    (testing "TASK-175 AC #1: a citation-shaped bracket with an attribute
              group straight after it is a Span (sec5.6), its content
              parsed in place, and a following `\\{x}` is literal text"
      (doseq [[src inlines]
              [["[@doe]{.c}\n" [{:t :span :inlines [(author "doe")] :attr c-attr}]]
               ["[@doe]{.c}\\{x}\n"
                [{:t :span :inlines [(author "doe")] :attr c-attr} (str-inline "{x}")]]
               ["[see @doe, p. 4]{.c}\\{x}\n"
                [{:t :span
                  :inlines [(str-inline "see") space (author "doe") (str-inline ",") space
                            (str-inline "p.") space (str-inline "4")]
                  :attr c-attr}
                 (str-inline "{x}")]]
               ;; What the .hdoc writer makes of an undefined attributed
               ;; image reference holding a citation (TASK-170).
               ["[!\\[@doe\\]\\[s\\]]{.c}\\{x}\n"
                [{:t :span :inlines [(str-inline "![") (author "doe") (str-inline "][s]")] :attr c-attr}
                 (str-inline "{x}")]]
               ;; The way to attach a group to a bracketed citation.
               ["[[@doe]]{.c}\n"
                [{:t :span :inlines [{:t :cite :items [{:key "doe" :mode :normal}]}] :attr c-attr}]]]]
        (is (= inlines (inlines-of src)) src)
        (is (ast/valid? ast/Document (parser/parse src)) src)))
    (testing "without a group straight after it the bracket is still a
              citation, and an escaped brace after it is still text"
      (is (= [{:t :cite :items [{:key "doe" :mode :normal}]} (str-inline "{x}")]
             (inlines-of "[@doe]\\{x}\n")))
      (is (= [{:t :cite :items [{:key "doe" :mode :normal}]} space (str-inline "{x}")]
             (inlines-of "[@doe] \\{x}\n")))
      (is (= [{:t :cite :items [{:key "doe" :mode :normal}]} (str-inline "{x")]
             (inlines-of "[@doe]{x\n"))
          "a `{` that opens no group leaves the citation alone"))
    (testing "a second group after the first is still an error, as after
              any other Span"
      (doseq [src ["[@doe]{.c}{x}\n" "[s]{.c}{x}\n"]]
        (is (= ::parser/unsupported-node
               (try (parser/parse src) nil
                    (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))
            src)))
    (testing "a group that ends a heading or a table caption is that
              block's own, so the bracket before it stays a citation"
      (let [cite {:t :cite :items [{:key "doe" :mode :normal}]}]
        (doseq [src ["# See [@doe]{#sec:x}\n" "See [@doe]{#sec:x}\n===\n"]]
          (is (= [{:t :section :level 1 :heading [(str-inline "See") space cite] :blocks []
                   :attr (assoc attr :id "sec:x")}]
                 (:blocks (parser/parse src)))
              src))
        (let [table (first (:blocks (parser/parse "| a |\n|---|\n| b |\n: See [@doe]{#tbl:x}\n")))]
          (is (= [(str-inline "See") space cite] (:caption table)))
          (is (= "tbl:x" (:id (:attr table)))))
        (is (= [(str-inline "See") space {:t :span :inlines [(author "doe")] :attr c-attr} space
                (str-inline "more")]
               (-> (parser/parse "# See [@doe]{.c} more\n") :blocks first :heading))
            "one with more heading text after it is the bracket's")))))

(deftest citation-bracket-spaced-attribute-group-test
  (let [c-attr {:classes ["c"] :props {}}
        author (fn [k] {:t :cite :items [{:key k :mode :author}]})
        cite {:t :cite :items [{:key "doe" :mode :normal}]}
        refused? (fn [src]
                   (= ::parser/unsupported-node
                      (try (parser/parse src) nil
                           (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))]
    (testing "TASK-184 AC #2: whitespace between a citation-shaped bracket
              and its group still makes a Span, as `[s] {.c}` is one --
              flexmark attaches a group across the whitespace before it"
      (doseq [[src s-src] [["[@doe] {.c}\n" "[s] {.c}\n"]
                           ["[@doe]\n{.c}\n" "[s]\n{.c}\n"]
                           ["x [@doe]\t {.c}\n" "x [s]\t {.c}\n"]]]
        (is (= (-> (parser/parse "[@doe]{.c}\n") :blocks first :inlines last)
               {:t :span :inlines [(author "doe")] :attr c-attr}
               (-> (parser/parse src) :blocks first :inlines last))
            src)
        (is (= {:t :span :inlines [(str-inline "s")] :attr c-attr}
               (-> (parser/parse s-src) :blocks first :inlines last))
            s-src)
        (is (ast/valid? ast/Document (parser/parse src)) src)))
    (testing "flexmark drops that whitespace only where the group ends its
              text, so with more text after it both are refused alike"
      (doseq [src ["x [@doe] {.c} y\n" "x [s] {.c} y\n" "# See [@doe] {.c} more\n" "# See [s] {.c} more\n"]]
        (is (refused? src) src)))
    (testing "a spaced group that ends a heading or a table caption is the
              block's, as after `[s]`, so the bracket stays a citation"
      (is (= [{:t :section :level 1 :heading [(str-inline "See") space cite] :blocks []
               :attr (assoc attr :id "sec:x")}]
             (:blocks (parser/parse "# See [@doe] {#sec:x}\n"))))
      (is (= [(str-inline "See") space (str-inline "[s]")]
             (-> (parser/parse "# See [s] {#sec:x}\n") :blocks first :heading)))
      (let [table (first (:blocks (parser/parse "| a |\n|---|\n| b |\n: See [@doe] {#tbl:x}\n")))]
        (is (= [(str-inline "See") space cite] (:caption table)))
        (is (= "tbl:x" (:id (:attr table))))))))

(deftest attributed-span-in-link-text-test
  (let [c-attr {:classes ["c"] :props {}}
        author (fn [k] {:t :cite :items [{:key k :mode :author}]})
        span (fn [& inlines] {:t :span :inlines (vec inlines) :attr c-attr})
        link (fn [target & inlines] {:t :link :target target :inlines (vec inlines) :attr attr})
        inlines-of (fn [src] (-> (parser/parse src) :blocks first :inlines))]
    (testing "TASK-184 AC #1: an attributed span inside link text -- which
              flexmark flattens back into the link's text, as a link may
              not hold a link -- parses to a link containing the span"
      (doseq [[src inlines]
              [["[a [s]{.c}](u)\n" [(link "u" (str-inline "a") space (span (str-inline "s")))]]
               ["[See [@doe]{.c}](u)\n" [(link "u" (str-inline "See") space (span (author "doe")))]]
               ["[See [@doe] {.c}](u)\n" [(link "u" (str-inline "See") space (span (author "doe")))]]
               ["[See [[@doe]]{.c}](u)\n"
                [(link "u" (str-inline "See") space (span {:t :cite :items [{:key "doe" :mode :normal}]}))]]
               ;; Content flexmark parsed stays parsed, and text either side
               ;; of the bracket stays one word with it.
               ["x[a [*s* `c`]{.c}y](u)\n"
                [(str-inline "x")
                 (link "u" (str-inline "a") space (span {:t :emph :inlines [(str-inline "s")]} space
                                                        {:t :code :text "c"})
                       (str-inline "y"))]]
               ;; A full reference-style link flattens its text the same way.
               ["[a [s]{.c}][r]\n\n[r]: v\n" [(link "v" (str-inline "a") space (span (str-inline "s")))]]
               ;; An undefined `![s]` is the Span it is outside a link.
               ["[a ![s]{.c}](u)\n" [(link "u" (str-inline "a") space (span (str-inline "![s]")))]]
               ["[a [!\\[s\\]]{.c}](u)\n" [(link "u" (str-inline "a") space (span (str-inline "![s]")))]]
               ;; An escaped bracket is text, and an unattributed one stays
               ;; literal around a span.
               ["[a \\[x [s]{.c}](u)\n"
                [(link "u" (str-inline "a") space (str-inline "[x") space (span (str-inline "s")))]]
               ["[a [x] [s]{.c}](u)\n"
                [(link "u" (str-inline "a") space (str-inline "[x]") space (span (str-inline "s")))]]]]
        (is (= inlines (inlines-of src)) src)
        (is (ast/valid? ast/Document (parser/parse src)) src)))
    (testing "a span holding a span, which flexmark never forms as a
              bracket for the same reason, nests"
      (is (= [{:t :span :inlines [(str-inline "b") space {:t :span :inlines [(str-inline "c")]
                                                          :attr {:classes ["x"] :props {}}}]
               :attr {:classes ["y"] :props {}}}]
             (inlines-of "[b [c]{.x}]{.y}\n")))
      (is (= [(link "u" (str-inline "a") space
                    {:t :span :inlines [(str-inline "b") space (span (str-inline "c")) space (str-inline "d")]
                     :attr {:classes ["y"] :props {}}}
                    space (str-inline "e"))]
             (inlines-of "[a [b [c]{.c} d]{.y} e](u)\n"))))
    (testing "a span holding a link parses (the same mechanism), and a link
              around that span's bracket is literal text, as CommonMark
              makes a link around a link"
      (is (= [(span (str-inline "x") space (link "u" (str-inline "y")) space (str-inline "z"))]
             (inlines-of "[x [y](u) z]{.c}\n")))
      (is (= [(str-inline "[") (span (str-inline "x") space (link "v" (str-inline "y"))) (str-inline "](u)")]
             (inlines-of "[[x [y](v)]{.c}](u)\n")))
      (is (= [(str-inline "[a") space (span (str-inline "b") space (link "v" (str-inline "y")))
              (str-inline "](u)")]
             (inlines-of "[a [b [y](v)]{.c}](u)\n"))))
    (testing "a regrouped Span carries its own bracket's source line, in link
              text and nested across lines"
      (let [doc (parser/parse "p\n\n[a\n[s]{.c}](u)\n\n[b\n[c\nd]{.x}\ne]{.y}\n" {:source "doc.hdoc"})
            [_ link-para nested-para] (:blocks doc)
            in-link (-> link-para :inlines first :inlines peek)
            outer (-> nested-para :inlines first)
            inner (->> (:inlines outer) (filter #(= :span (:t %))) first)]
        (is (= [:link :span :span :span] [(-> link-para :inlines first :t) (:t in-link) (:t outer) (:t inner)]))
        (is (= {:file "doc.hdoc" :line 4} (select-keys (ast/source in-link) [:file :line])))
        (is (= {:file "doc.hdoc" :line 6} (select-keys (ast/source outer) [:file :line])))
        (is (= {:file "doc.hdoc" :line 7} (select-keys (ast/source inner) [:file :line])))))
    (testing "a group no bracket owns is still refused"
      (doseq [src ["[a [s]{.c}{x}](u)\n" "[a \\[s\\]{.c}](u)\n" "[a s\\]{.c}](u)\n"]]
        (is (= ::parser/unsupported-node
               (try (parser/parse src) nil
                    (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))
            src)))))

(deftest attributed-span-over-defined-reference-test
  (let [c-attr {:classes ["c"] :props {}}
        span (fn [& inlines] {:t :span :inlines (vec inlines) :attr c-attr})
        link (fn [target & inlines] {:t :link :target target :inlines (vec inlines) :attr attr})
        inlines-of (fn [src] (-> (parser/parse src) :blocks first :inlines))
        error-of (fn [src] (try (parser/parse src) nil
                                (catch Exception e [(class e) (ex-data e)])))]
    (testing "TASK-190 AC #1/#2: a span in link text over a *defined* label
              parses to the link holding the span, as it does undefined --
              glued, spaced or wrapped, in a quote or a table cell"
      (doseq [defs ["" "[r]: v\n\n"]
              body ["[a [r]{.c}](u)\n" "[a [r] {.c}](u)\n" "[a [r]\n{.c}](u)\n" "[a [ r ]{.c}](u)\n"]]
        (let [src (str defs body)]
          (is (= [(link "u" (str-inline "a") space (span (str-inline "r")))] (inlines-of src)) src)
          (is (ast/valid? ast/Document (parser/parse src)) src)))
      (is (= [(link "u" (span (str-inline "r")))] (inlines-of "[r]: v\n\n[[r]{.c}](u)\n")))
      (is (= [(link "v" (str-inline "a") space (span (str-inline "r")))] (inlines-of "[r]: v\n\n[a [r]{.c}][r]\n")))
      (is (= [(link "u" (str-inline "a") space
                    {:t :emph :inlines [(span {:t :emph :inlines [(str-inline "r")]})]})]
             (inlines-of "[*r*]: v\n\n[a *[*r*]{.c}*](u)\n")))
      (is (= [(link "u" (str-inline "a") space (span (str-inline "r")))]
             (-> (parser/parse "[r]: v\n\n> [a [r]\n> {.c}](u)\n") :blocks first :blocks first :inlines)))
      (is (= [(link "u" (str-inline "x") space (span (str-inline "r")))]
             (-> (parser/parse "[r]: v\n\n| a |\n|---|\n| [x [r]{.c}](u) |\n")
                 :blocks first :rows first :cells first :blocks first :inlines))))
    (testing "the Span carries its bracket's source line"
      (let [in-link (-> (parser/parse "[r]: v\n\n[a\n[r]{.c}](u)\n" {:source "doc.hdoc"})
                        :blocks first :inlines first :inlines peek)]
        (is (= {:file "doc.hdoc" :line 4} (select-keys (ast/source in-link) [:file :line])))))
    (testing "everything else about a defined label is unchanged: `[r]{.c}`
              is a Span, `![r]{.c}` and `[x][r]{.c}` keep their target
              (TASK-169), a heading's trailing group leaves `[r]` a link,
              and a plain `[r]` in link text still cancels the outer link"
      (is (= [(span (str-inline "r"))] (inlines-of "[r]: v\n\n[r]{.c}\n")))
      (is (= [(span (str-inline "r"))] (inlines-of "[r]: v\n\n[r] {.c}\n")))
      ;; Edge whitespace goes, as flexmark trims it from a LinkRef's text.
      (is (= [(span (str-inline "r"))] (inlines-of "[r]: v\n\n[ r ]{.c}\n")))
      (is (= [(link "u" (str-inline "a") space (span (str-inline "r")))]
             (inlines-of "[r]: v\n\n[a [ r ]{.c}](u)\n")))
      (is (= [{:t :image :src "v" :alt "r" :attr c-attr}] (inlines-of "[r]: v\n\n![r]{.c}\n")))
      (is (= [(assoc (link "v" (str-inline "x")) :attr c-attr)] (inlines-of "[r]: v\n\n[x][r]{.c}\n")))
      (is (= [(link "u" (str-inline "a") space {:t :image :src "v" :alt "r" :attr c-attr})]
             (inlines-of "[r]: v\n\n[a ![r] {.c}](u)\n")))
      (let [section (first (:blocks (parser/parse "[r]: v\n\n# See [r] {#sec:x}\n")))]
        (is (= [(str-inline "See") space (link "v" (str-inline "r"))] (:heading section)))
        (is (= "sec:x" (:id (:attr section)))))
      (is (= [(str-inline "[a") space (link "v" (str-inline "r")) (str-inline "](u)")]
             (inlines-of "[r]: v\n\n[a [r]](u)\n")))
      (is (= [(str-inline "[a") space (assoc (link "v" (str-inline "x")) :attr c-attr) (str-inline "](u)")]
             (inlines-of "[r]: v\n\n[a [x][r]{.c}](u)\n"))))
    (testing "AC #2: a reference link in link text with a spaced group --
              which flexmark dissolves the outer link around and then fails
              on -- is a located ex-info, not a raw IllegalStateException"
      (doseq [src ["[r]: v\n\n[a [x][r] {.c}](u)\n" "[x]: v\n\n[a [x][] {.c}](u)\n"]]
        (let [[cls data] (error-of src)]
          (is (= clojure.lang.ExceptionInfo cls) src)
          (is (= ::parser/unparsable-markup (:type data)) src)
          (is (= 3 (:source-line data)) src))))
    (testing "only that flexmark failure is translated: any other
              IllegalStateException is not taken for a document error"
      (let [untracked? #'parser/untracked-node-error?]
        (is (untracked? (IllegalStateException. "Parent node: Link{text=a} of TextBase{text= } is not tracked, some post processor forgot to call tracker.nodeAdded().")))
        (is (not (untracked? (IllegalStateException. "something else went wrong"))))
        (is (not (untracked? (IllegalStateException.))))
        (is (not (untracked? (RuntimeException. "x is not tracked"))))))
    (testing "a regrouped span in link text loses its edge whitespace as a
              bracket flexmark forms does -- the same inlines as at top
              level, defined or not; a line break stays in both"
      (doseq [defs ["" "[r]: v\n\n"]
              bracket ["[ r ]" "[ r\n]" "[\nr ]" "[ *r* ]" "[ ]"]]
        (let [src (str defs "[a " bracket "{.c}](u)\n")
              expected (-> (inlines-of (str defs bracket "{.c}\n")) first :inlines)]
          (is (= [(link "u" (str-inline "a") space (apply span expected))] (inlines-of src)) src)))
      (is (= [(link "u" (str-inline "a") space (span (str-inline "r") {:t :soft-break}))]
             (inlines-of "[a [ r\n]{.c}](u)\n")))
      (is (= [{:t :span :inlines [(str-inline "b") space {:t :span :inlines [(str-inline "c")]
                                                          :attr {:classes ["x"] :props {}}}]
               :attr {:classes ["y"] :props {}}}]
             (inlines-of "[ b [ c ]{.x} ]{.y}\n"))))))

(deftest cross-ref-bare-prefix-shape-test
  (testing "a bare `@token` containing a colon whose part before the
            colon does not look like a plausible sec5.10 kind prefix
            (letter-first, like `fig`/`thm`/`sec`/`eq`) is left as
            ordinary literal text rather than misread as a nonsensical
            `CrossRef{label: ...}` -- e.g. an ordinary time mention
            (TASK-9 review finding #5, the cheap partial fix applied;
            see `cross-ref-prefix-shape?`'s own docstring for the
            remaining, deliberately accepted scope limit)"
    (let [doc (parser/parse "The meeting is at @3:00pm today.\n")]
      (is (= [{:t :para
               :inlines [(str-inline "The") space (str-inline "meeting") space (str-inline "is") space
                         (str-inline "at") space (str-inline "@3:00pm") space (str-inline "today.")]}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc)))))

(deftest cross-ref-trailing-punctuation-test
  (testing "a trailing colon after a bare cross-reference is prose, not part
            of the label -- `@sec:intro:` introducing a formula or a list
            resolved against `sec:intro:`, matched nothing and dangled, while
            every other punctuation mark that can follow a reference already
            worked because none of them is a `ref-token-char?` (TASK-73)"
    (let [doc (parser/parse "See @sec:intro: it matters.\n")]
      (is (= [{:t :para
               :inlines [(str-inline "See") space
                         {:t :cross-ref :label "sec:intro"} (str-inline ":") space
                         (str-inline "it") space (str-inline "matters.")]}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc))))
  (testing "the same for the other two separators the token alphabet admits
            and a real label cannot end with -- a hyphen (an em dash written
            `--` right after a reference) and an underscore"
    (is (= [{:t :cross-ref :label "sec:intro"} (str-inline "--") space
            (str-inline "yes.")]
           (drop 2 (:inlines (first (:blocks (parser/parse "See @sec:intro-- yes.\n")))))))
    (is (= [{:t :cross-ref :label "sec:intro"} (str-inline "_") space (str-inline "yes.")]
           (drop 2 (:inlines (first (:blocks (parser/parse "See @sec:intro_ yes.\n"))))))))
  (testing "a colon INSIDE a label is untouched: only a trailing one is prose"
    (is (= [{:t :cross-ref :label "fig:a:b"}]
           (take 1 (:inlines (first (:blocks (parser/parse "@fig:a:b resolves.\n"))))))))
  (testing "a bare citation key followed by a colon behaves the same way"
    (let [doc (parser/parse "Bare @knuth1984: yes.\n")]
      (is (= [{:t :cite :items [{:key "knuth1984" :mode :author}]} (str-inline ":")]
             (take 2 (drop 2 (:inlines (first (:blocks doc)))))))
      (is (ast/valid? ast/Document doc))))
  (testing "an `@` followed by nothing but separators is literal text, not a
            reference to the empty label"
    (let [doc (parser/parse "Just @: punctuation.\n")]
      (is (= [(str-inline "Just") space (str-inline "@:") space (str-inline "punctuation.")]
             (:inlines (first (:blocks doc)))))
      (is (ast/valid? ast/Document doc)))))

(deftest known-vocabulary-test
  (let [inlines (fn [source opts] (:inlines (first (:blocks (parser/parse source opts)))))
        vocabulary {:kinds #{"fig" "sec"} :cite-keys #{"knuth1984"}}]
    (testing "AC #1: a Matrix-style handle survives as plain text once the
              caller says which kinds exist. Its shape is exactly a
              genuine @kind:label's, so nothing but the real kind set can
              tell them apart"
      (is (= [(str-inline "Ping") space (str-inline "@user:homeserver") space (str-inline "now.")]
             (inlines "Ping @user:homeserver now.\n" vocabulary))))
    (testing "AC #2: a social-style bare mention survives as plain text
              once the caller says which citation keys exist"
      (is (= [(str-inline "cc") space (str-inline "@someone") space (str-inline "on") space
              (str-inline "this.")]
             (inlines "cc @someone on this.\n" vocabulary))))
    (testing "AC #3: a genuine reference and a genuine citation key still
              parse to exactly the nodes they always did"
      (is (= [(str-inline "See") space
              {:t :cross-ref :label "fig:tree"} space
              (str-inline "and") space
              {:t :cite :items [{:key "knuth1984" :mode :author}]} space
              (str-inline "here.")]
             (inlines "See @fig:tree and @knuth1984 here.\n" vocabulary))))
    (testing "AC #4: with no vocabulary supplied the pre-TASK-47 shape
              heuristics are used unchanged, so this namespace keeps its
              independence from the resolver -- the same two inputs still
              become a CrossRef and a Cite"
      (is (= [(str-inline "Ping") space {:t :cross-ref :label "user:homeserver"} space
              (str-inline "now.")]
             (inlines "Ping @user:homeserver now.\n" {})))
      (is (= [(str-inline "cc") space {:t :cite :items [{:key "someone" :mode :author}]} space
              (str-inline "on") space (str-inline "this.")]
             (inlines "cc @someone on this.\n" {}))))
    (testing "AC #5's trade-off, asserted rather than only described: with
              a vocabulary supplied, an unregistered kind and an unknown
              citation key become prose instead of dangling references.
              The author sees their own text where they wrote it, in
              place of a `??`"
      (is (= [(str-inline "@fgi:tree")] (inlines "@fgi:tree\n" vocabulary)))
      (is (= [(str-inline "@nobody2000")] (inlines "@nobody2000\n" vocabulary))))
    (testing "an empty vocabulary is a caller saying there are none, not
              a caller saying nothing -- unlike nil, which means it did
              not supply one"
      (is (= [(str-inline "@fig:tree")] (inlines "@fig:tree\n" {:kinds #{}})))
      (is (= [(str-inline "@knuth1984")] (inlines "@knuth1984\n" {:cite-keys #{}}))))
    (testing "a bracketed citation is explicit syntax and is not filtered
              by :cite-keys -- so an unknown key there still becomes a
              Cite, and still dangles loudly in the resolver"
      (is (= [{:t :cite :items [{:key "nobody2000" :mode :normal}]}]
             (inlines "[@nobody2000]\n" vocabulary))))
    (testing "a vocabulary of keywords -- the obvious mistake, since a
              lexicon is keyed by keyword -- is refused rather than
              matching nothing and silently turning every reference in
              every document into prose"
      (doseq [opts [{:kinds #{:fig}} {:cite-keys #{:knuth1984}}]]
        (let [e (is (thrown? clojure.lang.ExceptionInfo (parser/parse "@fig:tree\n" opts)) opts)]
          (is (= ::parser/invalid-parse-option (:type (ex-data e))) opts))))))

(deftest book-ref-test
  (testing "TASK-152 AC #1: `@{…}` is a :book-ref inline holding the ref
            exactly as written"
    (is (= [(str-inline "See") space {:t :book-ref :ref "bible:romanos#14:8"} (str-inline ".")]
           (:inlines (first (:blocks (parser/parse "See @{bible:romanos#14:8}.\n"))))))
    (is (= [{:t :book-ref :ref "reformed:confessions/reformed/heidelberg#sec:q1..sec:q3"}]
           (:inlines (first (:blocks (parser/parse "@{reformed:confessions/reformed/heidelberg#sec:q1..sec:q3}\n")))))))
  (testing "AC #2: a bare `@reformed:heidelberg` is still a local cross-reference"
    (is (= [{:t :cross-ref :label "reformed:heidelberg"}]
           (:inlines (first (:blocks (parser/parse "@reformed:heidelberg\n"))))))
    (is (= [{:t :cross-ref :label "fig:tree"} space {:t :book-ref :ref "bible:romanos"}]
           (:inlines (first (:blocks (parser/parse "@fig:tree @{bible:romanos}\n")))))))
  (testing "it is recognized whatever the vocabularies, which only filter bare tokens"
    (is (= [{:t :book-ref :ref "bible:romanos"}]
           (:inlines (first (:blocks (parser/parse "@{bible:romanos}\n" {:kinds #{} :cite-keys #{}})))))))
  (testing "a malformed ref is NOT a parse error: it is kept as written for
            the resolver's `malformed-book-ref` warning"
    (doseq [written ["not a ref" "Bad:Ref" "bible" "bible:romanos#" " bible:x "]]
      (is (= [{:t :book-ref :ref written}]
             (:inlines (first (:blocks (parser/parse (str "@{" written "}\n"))))))
          written)))
  (testing "it is written with braces even after a word character, unlike a bare token"
    (is (= [(str-inline "x") {:t :book-ref :ref "a:b"}]
           (:inlines (first (:blocks (parser/parse "x@{a:b}\n")))))))
  (testing "what is not a book ref stays literal text"
    (doseq [[src text] [["@{}\n" "@{}"] ["@{open\n" "@{open"] ["@\\{a:b}\n" "@{a:b}"]]]
      (is (= [(str-inline text)] (:inlines (first (:blocks (parser/parse src))))) src))
    (is (= [(str-inline "@{a") {:t :soft-break} (str-inline "b")]
           (:inlines (first (:blocks (parser/parse "@{a\nb\n")))))
        "an unclosed one stays text across the line break")
    (is (= [{:t :code :text "@{a:b}"}]
           (:inlines (first (:blocks (parser/parse "`@{a:b}`\n")))))))
  (testing "a `{…}` group right after an `@` that is not a book ref -- an
            escaped `\\@{x}`, or one spanning a line -- names the right
            spelling instead of a bare unsupported-construct error"
    (doseq [src ["a \\@{x} b\n" "a @{a\nb} c\n"]]
      (let [e (is (thrown? clojure.lang.ExceptionInfo (parser/parse src)) src)]
        (is (= ::parser/unsupported-node (:type (ex-data e))) src)
        (is (str/includes? (ex-message e) "write `@\\{` for a literal `@{`") src))))
  (testing "its content is never parsed as markup"
    (is (= [{:t :book-ref :ref "a*b*:_c_"}]
           (:inlines (first (:blocks (parser/parse "@{a*b*:_c_}\n")))))))
  (testing "inside an image's alt text it flattens to its spelling"
    (is (= "see @{bible:x}"
           (-> (parser/parse "![see @{bible:x}](a.png){#fig:a}\n")
               :blocks first :content :inlines first :alt)))))

(defn- parse-failure
  "The ExceptionInfo parsing `src` throws, or nil."
  [src]
  (try (parser/parse src) nil (catch clojure.lang.ExceptionInfo e e)))

(deftest orphan-attribute-group-test
  (testing "TASK-191 AC #1: a group after whitespace mid-text names what it
            is separated from and says to write it right after that, or to
            escape the brace -- still the same unsupported-node refusal"
    (doseq [[src what fix] [["x [s] {.c} y\n" "the `]`" "write it right after the `]`, as in `[text]{.c}`"]
                            ["x [s]\n{.c} y\n" "the `]`" "write it right after the `]`"]
                            ["x [@k] {.c} y\n" "the `]`" "write it right after the `]`"]
                            ["[a [s] {.c} b](u)\n" "the `]`" "write it right after the `]`"]
                            ["x [r] {.c} y\n\n[r]: u\n" "the `]`" "write it right after the `]`"]
                            ["[a [r] {.c} b](u)\n\n[r]: u\n" "the `]`" "write it right after the `]`"]
                            ["x [a](u) {.c} y\n" "the link" "write it right after the link's `)`"]
                            ["x ![a](u) {.c} y\n" "the image" "write it right after the image's `)`"]
                            ["x `c` {.c} y\n" "the code span" "write it right after the closing backtick"]
                            ["x ==h== {.c} y\n" "the highlight" "write it right after the closing `==`"]]]
      (let [e (parse-failure src)]
        (is (= ::parser/unsupported-node (:type (ex-data e))) src)
        (is (= "{.c}" (:text (ex-data e))) src)
        (is (str/starts-with? (ex-message e) (str "attribute group `{.c}` is separated by whitespace from " what " before it"))
            src)
        (is (str/includes? (ex-message e) fix) src)
        (is (str/ends-with? (ex-message e) "or write `\\{` for a literal `{`") src))))
  (testing "after whitespace with nothing attachable before it"
    ;; A `]` that closes nothing a group could take -- a lone one, an
    ;; escaped one, a footnote marker's -- gets no right-after-the-`]` hint:
    ;; following it would still be refused.
    (doseq [src ["x {.c} y\n" "x **b** {.c} y\n" "# H {.c} x\n"
                 "x ] {.c} y\n" "x \\] {.c} y\n" "x \\\\] {.c} y\n" "x [a\\] {.c} y\n"
                 "x [^1] {.c} y\n\n[^1]: n\n"]]
      (let [e (parse-failure src)]
        (is (= ::parser/unsupported-node (:type (ex-data e))) src)
        (is (str/starts-with? (ex-message e) "attribute group `{.c}` follows whitespace in the middle of text") src)
        (is (str/includes? (ex-message e) "write `\\{` for a literal `{`") src))))
  (testing "the other orphan shapes: straight after unattachable text, a
            second group, a group opening the text"
    (doseq [[src message] [["x{.c} y\n" "attribute group `{.c}` follows something it cannot attach to"]
                           ["x **b**{.c}\n" "attribute group `{.c}` follows something it cannot attach to"]
                           ["x [s]{.c}{ #d } y\n" "attribute group `{ #d }` follows another group, `{.c}`; one construct takes one group, so merge them into `{.c #d}`"]
                           ["[a [s]{.c}{.d} b](u)\n" "merge them into `{.c .d}`"]
                           ["{.c} y\n" "attribute group `{.c}` starts the text, with nothing before it to attach to"]]]
      (let [e (parse-failure src)]
        (is (= ::parser/unsupported-node (:type (ex-data e))) src)
        (is (str/includes? (ex-message e) message) src))))
  (testing "what already attached still does: no accepted input changes"
    (is (= [(str-inline "x") {:t :space}
            {:t :span :inlines [(str-inline "s")] :attr {:classes ["c"] :props {}}}]
           (:inlines (first (:blocks (parser/parse "x [s] {.c}\n"))))))
    (is (str/includes? (ex-message (parse-failure "a \\@{x} b\n")) "write `@\\{` for a literal `@{`"))))

(deftest quote-line-test
  (testing "TASK-152 AC #1: a whole-line `!quote` is a :book-quote Block
            holding the ref as written, splitting the text around it the
            way `!include` does"
    (is (= [{:t :para :inlines [(str-inline "Before.")]}
            {:t :book-quote :ref "bible:romanos#14:8-9"}
            {:t :para :inlines [(str-inline "After.")]}]
           (:blocks (parser/parse "Before.\n!quote bible:romanos#14:8-9\nAfter.\n")))))
  (testing "it sits in whatever contains it: a section, a directive body"
    (is (= [{:t :book-quote :ref "a:b"}]
           (:blocks (first (:blocks (parser/parse "# H {#sec:h}\n\n!quote a:b\n"))))))
    (is (= [{:t :book-quote :ref "bible:romanos#14:8"}]
           (:blocks (first (:blocks (parser/parse (str ":::{small-collapsable summary=\"Romanos 14:8\" label=\"(2)\"}\n"
                                                       "!quote bible:romanos#14:8\n:::\n"))))))))
  (testing "a malformed ref is kept whole, trailing blanks trimmed, for the resolver to report"
    (is (= [{:t :book-quote :ref "bible: romanos 14"}]
           (:blocks (parser/parse "  !quote   bible: romanos 14  \n")))))
  (testing "shielded by code fences, like `!include`"
    (is (= [{:t :code-block :text "!quote a:b\n" :attr attr}]
           (:blocks (parser/parse "```\n!quote a:b\n```\n")))))
  (testing "a bare `!quote` is a paragraph, and `!quote{…}` a diagnostic: `quote`
            is reserved as a one-line directive name, like `include`"
    (is (= [{:t :para :inlines [(str-inline "!quote")]}] (:blocks (parser/parse "!quote\n"))))
    (let [e (is (thrown? clojure.lang.ExceptionInfo (parser/parse "!quote{ref=a:b}\n")))]
      (is (= ::parser/malformed-directive (:type (ex-data e))))
      (is (str/includes? (ex-message e) "!quote <book-ref>"))))
  (testing "mid-sentence it is prose"
    (is (= [{:t :para :inlines [(str-inline "see") space (str-inline "!quote") space (str-inline "a:b")]}]
           (:blocks (parser/parse "see !quote a:b\n"))))))

(deftest include-line-test
  (testing "TASK-38 AC #1: a whole-line !include produces an :include
            Block carrying its src, ending the run of text before it and
            starting a fresh one after -- so it is a Block in its own
            right, not something swallowed into a paragraph"
    (is (= [{:t :para :inlines [(str-inline "Before.")]}
            {:t :include :src "chapters/methods.hdoc"}
            {:t :para :inlines [(str-inline "After.")]}]
           (:blocks (parser/parse "Before.\n\n!include chapters/methods.hdoc\n\nAfter.\n")))))
  (testing "a path containing spaces is quoted, the same way TASK-39
            quotes an attribute value"
    (is (= [{:t :include :src "a long name.hdoc"}]
           (:blocks (parser/parse "!include \"a long name.hdoc\"\n"))))
    (is (= [{:t :include :src "a long name.hdoc"}]
           (:blocks (parser/parse "!include 'a long name.hdoc'\n")))))
  (testing "it is recognized in the same line scan as a directive fence,
            so it inherits that scan's shielding: an !include inside a
            code fence, or indented into a code block, is content"
    (is (= [{:t :code-block :text "!include ch.hdoc\n" :attr attr}]
           (:blocks (parser/parse "```\n!include ch.hdoc\n```\n"))))
    (is (= [{:t :code-block :text "!include ch.hdoc\n" :attr attr}]
           (:blocks (parser/parse "    !include ch.hdoc\n")))))
  (testing "the whole line must match, so prose is never misread"
    (is (= [{:t :para :inlines [(str-inline "!include")]}]
           (:blocks (parser/parse "!include\n"))))
    (is (= [{:t :para :inlines [(str-inline "see") space (str-inline "!include") space
                                (str-inline "ch.hdoc")]}]
           (:blocks (parser/parse "see !include ch.hdoc\n")))))
  (testing "an include inside a section belongs to that section, so where
            it is written decides where its content lands"
    (let [blocks (:blocks (parser/parse "# H {#sec:h}\n\n!include ch.hdoc\n"))]
      (is (= [{:t :include :src "ch.hdoc"}] (:blocks (first blocks))))))
  (testing "an include inside a directive body is an include there too"
    (is (= [{:t :include :src "ch.hdoc"}]
           (:blocks (first (:blocks (parser/parse ":::{note}\n!include ch.hdoc\n:::\n")))))))
  (testing "a quoted path follows TASK-39's contract, not just its
            spelling: unterminated, empty, or with anything after the
            closing quote is a diagnostic, not a silently truncated path"
    (doseq [line ["!include \"a.hdoc\n" "!include \"\"\n" "!include \"a\" \"b\"\n" "!include 'a\n"]]
      (let [e (is (thrown? clojure.lang.ExceptionInfo (parser/parse line)) line)]
        (is (= ::parser/malformed-include (:type (ex-data e))) line))))
  (testing "an apostrophe inside an UNQUOTED path is an ordinary
            character -- quoting only starts at the first character, the
            same rule split-attr-tokens uses for a value"
    (is (= [{:t :include :src "it's.hdoc"}] (:blocks (parser/parse "!include it's.hdoc\n")))))
  (testing "two behaviors an !include shares with a ::: directive fence,
            asserted side by side so a future change cannot quietly make
            them differ: both split a list they are indented into, and
            both cut a footnote definition off from a marker on the
            other side of them"
    (doseq [construct [":::{note}\n  x\n  :::" "!include c.hdoc"]]
      (is (= [:list (if (str/starts-with? construct ":") :directive :include) :list]
             (mapv :t (:blocks (parser/parse (str "1. one\n\n  " construct "\n\n2. two\n")))))
          construct)
      (let [e (is (thrown? clojure.lang.ExceptionInfo
                           (parser/parse (str "Use[^a]\n\n" construct "\n\n[^a]: def\n")))
                  construct)]
        (is (str/includes? (ex-message e) "footnote marker has no matching definition")
            construct)))))

(deftest front-matter-without-body-test
  (testing "front-matter reads the Meta without parsing the body, which is
            what lets haselnuss.cli load the bibliography named in the
            front matter *before* parsing the document that names it"
    (is (= {:bibliography "refs.json" :lang "en"}
           (parser/front-matter "---\nbibliography: refs.json\nlang: en\n---\n\n# H\n\ntext\n"))))
  (testing "a document with no front matter has empty Meta, not an error"
    (is (= {} (parser/front-matter "# H\n\ntext\n"))))
  (testing "a body that would fail to parse is never reached, so the front
            matter of a broken document is still readable"
    (is (= {:lang "en"} (parser/front-matter "---\nlang: en\n---\n\n:::{!!!}\nx\n:::\n")))))

(deftest footnote-test
  (testing "a `[^label]` marker paired with a matching `[^label]: ...`
            definition elsewhere in the document produces an inline Note
            carrying the definition's own Blocks at the marker's position
            (TASK-10 AC #1); the definition line itself contributes no
            content of its own at its own source position"
    (let [doc (parser/parse (str "Hazelnuts are a pome.[^actually]\n"
                                 "\n"
                                 "[^actually]: They are a nut, not a pome.\n"))]
      (is (= [{:t :para
               :inlines [(str-inline "Hazelnuts") space (str-inline "are") space (str-inline "a") space
                         (str-inline "pome.")
                         {:t :note
                          :blocks [{:t :para
                                    :inlines [(str-inline "They") space (str-inline "are") space
                                              (str-inline "a") space (str-inline "nut,") space
                                              (str-inline "not") space (str-inline "a") space
                                              (str-inline "pome.")]}]}]}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc))))
  (testing "a footnote marker with no matching definition anywhere in the
            document raises a parse diagnostic rather than silently
            falling back to literal text the way an undefined ordinary
            link reference does (TASK-10 AC #2)"
    (let [e (is (thrown? clojure.lang.ExceptionInfo
                         (parser/parse "See this claim.[^missing]\n")))]
      (is (= ::parser/undefined-footnote (:type (ex-data e))))
      (is (= "missing" (:label (ex-data e))))))
  (testing "multiple footnotes in one document each resolve to their own
            correct definition, independent of definition order: two
            markers appear together in one paragraph but their
            definitions are written afterward in the *opposite* order
            (TASK-10 AC #3)"
    (let [doc (parser/parse (str "First note.[^a] Second note.[^b]\n"
                                 "\n"
                                 "[^b]: Definition B.\n"
                                 "\n"
                                 "[^a]: Definition A.\n"))]
      (is (= [{:t :para
               :inlines [(str-inline "First") space (str-inline "note.")
                         {:t :note :blocks [{:t :para :inlines [(str-inline "Definition") space (str-inline "A.")]}]}
                         space (str-inline "Second") space (str-inline "note.")
                         {:t :note :blocks [{:t :para :inlines [(str-inline "Definition") space (str-inline "B.")]}]}]}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc)))))

(deftest cyclic-footnote-test
  (testing "a self-referential footnote definition -- its own body contains
            a `[^label]` marker resolving right back to itself -- raises a
            catchable parse diagnostic instead of recursing forever and
            crashing with an uncaught StackOverflowError (clojure-reviewer
            finding against TASK-10: `convert-inline`'s Footnote branch
            called `convert-blocks` unconditionally on every resolved
            reference, with no guard against a definition whose own body
            resolves back to itself)"
    (let [e (is (thrown? clojure.lang.ExceptionInfo
                         (parser/parse (str "See.[^self]\n"
                                            "\n"
                                            "[^self]: Text referencing itself [^self] again.\n"))))]
      (is (= ::parser/cyclic-footnote (:type (ex-data e))))
      (is (= "self" (:label (ex-data e))))))
  (testing "a mutually-cyclical pair of footnote definitions -- `[^ca]`'s
            body refers to `[^cb]`, whose own body refers back to
            `[^ca]` -- raises the same diagnostic rather than crashing,
            reporting the label that was already active when the cycle
            closed (`ca`, the outermost marker actually converted, not
            `cb`)"
    (let [e (is (thrown? clojure.lang.ExceptionInfo
                         (parser/parse (str "See.[^ca]\n"
                                            "\n"
                                            "[^ca]: refers to [^cb].\n"
                                            "\n"
                                            "[^cb]: refers back to [^ca].\n"))))]
      (is (= ::parser/cyclic-footnote (:type (ex-data e))))
      (is (= "ca" (:label (ex-data e))))))
  (testing "the SAME label referenced by two separate, non-nested markers in
            one document is NOT mistaken for a cycle -- each marker starts
            its own independent `active-footnotes` walk, so two sibling
            references to one footnote each resolve to their own
            structurally-equal Note content rather than either one
            spuriously raising ::cyclic-footnote"
    (let [doc (parser/parse (str "First.[^dup] Second.[^dup]\n"
                                 "\n"
                                 "[^dup]: Shared note.\n"))
          note {:t :note :blocks [{:t :para :inlines [(str-inline "Shared") space (str-inline "note.")]}]}]
      (is (= [{:t :para
               :inlines [(str-inline "First.") note space (str-inline "Second.") note]}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc)))))

(deftest footnote-multi-block-definition-test
  (testing "a footnote definition spanning more than one Block (a second
            paragraph indented to stay part of the same definition, per
            CommonMark's own continuation-line convention) carries every
            one of its Blocks in the Note's :blocks, not just the first"
    (let [doc (parser/parse (str "Hazelnuts are versatile.[^multi]\n"
                                 "\n"
                                 "[^multi]: First paragraph.\n"
                                 "\n"
                                 "    Second paragraph, indented.\n"))]
      (is (= [{:t :para
               :inlines [(str-inline "Hazelnuts") space (str-inline "are") space (str-inline "versatile.")
                         {:t :note
                          :blocks [{:t :para :inlines [(str-inline "First") space (str-inline "paragraph.")]}
                                   {:t :para :inlines [(str-inline "Second") space (str-inline "paragraph,") space
                                                       (str-inline "indented.")]}]}]}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc))))
  (testing "an empty footnote definition (`[^label]:` with nothing
            following) converts to a Note with no Blocks at all, which is
            schema-valid (`ast/Document`'s Note has no minimum Block count)
            rather than crashing"
    (let [doc (parser/parse "See.[^empty]\n\n[^empty]:\n")]
      (is (= [{:t :para :inlines [(str-inline "See.") {:t :note :blocks []}]}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc)))))

(deftest footnote-nested-construct-test
  (testing "a footnote definition's body is run back through full inline
            conversion, not just plain text -- emphasis, a cross-reference
            (TASK-9), and inline math (TASK-7) inside a footnote all
            convert correctly as Note content"
    (let [doc (parser/parse (str "Text.[^n]\n"
                                 "\n"
                                 "[^n]: See *emphasis*, @fig:tree, and $x^2$ inline.\n"))]
      (is (= [{:t :para
               :inlines [(str-inline "Text.")
                         {:t :note
                          :blocks [{:t :para
                                    :inlines [(str-inline "See") space
                                              {:t :emph :inlines [(str-inline "emphasis")]} (str-inline ",") space
                                              {:t :cross-ref :label "fig:tree"} (str-inline ",") space
                                              (str-inline "and") space
                                              {:t :math-inline :tex "x^2"} space (str-inline "inline.")]}]}]}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc)))))

(deftest footnote-in-block-container-test
  (testing "a footnote marker and its matching definition both inside a
            table cell resolve and convert correctly -- the definition,
            like at the top level, contributes no content of its own at
            its own position, only inside the marker's Note"
    (let [doc (parser/parse (str "| A |\n"
                                 "|---|\n"
                                 "| See.[^tc] |\n"
                                 "\n"
                                 "[^tc]: Table cell note.\n"))]
      (is (= [{:t :table
               :head {:cells [{:blocks [{:t :para :inlines [(str-inline "A")]}]}]}
               :rows [{:cells
                       [{:blocks
                         [{:t :para
                           :inlines [(str-inline "See.")
                                     {:t :note
                                      :blocks [{:t :para
                                                :inlines [(str-inline "Table") space (str-inline "cell") space
                                                          (str-inline "note.")]}]}]}]}]}]
               :caption []
               :colspec [{}]
               :attr attr}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc))))
  (testing "a footnote marker and its matching definition both inside a list
            item resolve and convert correctly"
    (let [doc (parser/parse (str "- See.[^li]\n"
                                 "\n"
                                 "  [^li]: List item note.\n"))]
      (is (= [{:t :list :ordered false :tight false
               :items [[{:t :para
                         :inlines [(str-inline "See.")
                                   {:t :note
                                    :blocks [{:t :para
                                              :inlines [(str-inline "List") space (str-inline "item") space
                                                        (str-inline "note.")]}]}]}]]
               :attr attr}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc))))
  (testing "a footnote marker and its matching definition both inside a
            blockquote resolve and convert correctly, and the definition is
            dropped from the blockquote's own rendered Blocks the same way
            it is dropped from the top-level document (`block-items`'s
            FootnoteBlock filter applies generically to every block-
            collecting container)"
    (let [doc (parser/parse "> See.[^bq]\n>\n> [^bq]: Blockquote note.\n")]
      (is (= [{:t :block-quote
               :blocks [{:t :para
                         :inlines [(str-inline "See.")
                                   {:t :note
                                    :blocks [{:t :para
                                              :inlines [(str-inline "Blockquote") space (str-inline "note.")]}]}]}]}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc))))
  (testing "a footnote marker and its matching definition both inside the
            same directive resolve and convert correctly -- consistent
            with this file's documented per-chunk-only reference-
            resolution narrowing, since a directive's content is parsed as
            its own independent flexmark Document"
    (let [doc (parser/parse (str ":::{note}\n"
                                 "See.[^d]\n"
                                 "\n"
                                 "[^d]: Directive-local note.\n"
                                 ":::\n"))]
      (is (= [{:t :directive :name "note" :attr attr
               :blocks [{:t :para
                         :inlines [(str-inline "See.")
                                   {:t :note
                                    :blocks [{:t :para
                                              :inlines [(str-inline "Directive-local") space
                                                        (str-inline "note.")]}]}]}]}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc)))))

(deftest footnote-link-reference-label-namespace-test
  (testing "`[^1]` (a footnote marker) and `[1]: url` (an ordinary link
            reference definition) in the same document do not collide --
            footnotes and `[ref]: url` link references are separate label
            namespaces, each resolving to its own independent target"
    (let [doc (parser/parse (str "See.[^1] and [1].\n"
                                 "\n"
                                 "[^1]: Footnote one.\n"
                                 "\n"
                                 "[1]: http://example.com/one\n"))]
      (is (= [{:t :para
               :inlines [(str-inline "See.")
                         {:t :note :blocks [{:t :para :inlines [(str-inline "Footnote") space (str-inline "one.")]}]}
                         space (str-inline "and") space
                         {:t :link :target "http://example.com/one" :inlines [(str-inline "1")] :attr attr}
                         (str-inline ".")]}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc)))))

;; --- Highlights: `==marked text==` (TASK-122, doc-8 section 3, sec5.6) -----

(defn- highlight
  "A `:highlight` Inline (sec4.4) wrapping `inlines`, with the default
  Attr every one this parser produces without an attribute group carries."
  [& inlines]
  {:t :highlight :inlines (vec inlines) :attr attr})

(deftest highlight-test
  (testing "AC #1: a ==run of words== inside a paragraph parses to a
            :highlight carrying its inlines and the default :attr"
    (let [doc (parser/parse "Hazelnuts ==ripen in early autumn== and drop.\n")]
      (is (= [{:t :para
               :inlines [(str-inline "Hazelnuts") space
                         (highlight (str-inline "ripen") space (str-inline "in") space
                                    (str-inline "early") space (str-inline "autumn"))
                         space (str-inline "and") space (str-inline "drop.")]}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc))))
  (testing "two highlights in one paragraph are two separate nodes, not
            one spanning run"
    (let [doc (parser/parse "a ==b== c ==d== e\n")]
      (is (= [{:t :para :inlines [(str-inline "a") space (highlight (str-inline "b"))
                                  space (str-inline "c") space (highlight (str-inline "d"))
                                  space (str-inline "e")]}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc)))))

(defn- strike [& inlines]
  {:t :strike :inlines (vec inlines)})

(deftest strikethrough-test
  (testing "TASK-137 AC #1: ~~x~~ parses to :strike, with ordinary markup inside"
    (let [doc (parser/parse "a ~~gone *now*~~ b\n")]
      (is (= [{:t :para
               :inlines [(str-inline "a") space
                         (strike (str-inline "gone") space {:t :emph :inlines [(str-inline "now")]})
                         space (str-inline "b")]}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc))))
  (testing "a lone ~, a spaced ~~ and an unclosed ~~ stay literal text"
    (doseq [src ["a ~ b\n" "~/path\n" "a ~~ b\n" "~~unclosed\n" "\\~~x~~\n"]]
      (testing (pr-str src)
        (is (not-any? #(= :strike (:t %))
                      (tree-seq coll? seq (:blocks (parser/parse src))))))))
  (testing "a strike inside a table cell parses"
    (is (ast/valid? ast/Document (parser/parse "| a |\n|---|\n| ~~x~~ |\n")))))

(deftest task-list-items-are-plain-text-test
  (testing "TASK-137 AC #2 (decision): GFM task-list markers are NOT a
            construct -- the item is an ordinary list item whose text
            starts with the literal [ ] / [x]"
    (let [doc (parser/parse "- [ ] todo\n- [x] done\n")
          texts (->> (tree-seq coll? seq (:blocks doc))
                     (filter #(and (map? %) (= :str (:t %))))
                     (map :text))]
      (is (ast/valid? ast/Document doc))
      (is (= [:list] (mapv :t (:blocks doc))))
      (is (some #(str/includes? % "[") texts)))))

(deftest highlight-nested-markup-test
  (testing "AC #2: emphasis, a link, inline code, inline math, a
            cross-reference and a citation all survive inside a highlight.
            This is the requirement that settles the parsing mechanism: a
            highlight's content is ordinary markup, so it is a flexmark
            DelimiterProcessor (what `*emph*` itself is built on) rather
            than an InlineParserExtension reading an opaque span"
    (let [doc (parser/parse "A ==run *emph*, [l](u), `c`, $x^2$, @fig:t, @knuth== here.\n")]
      (is (= [{:t :para
               :inlines [(str-inline "A") space
                         (highlight (str-inline "run") space
                                    {:t :emph :inlines [(str-inline "emph")]}
                                    (str-inline ",") space
                                    {:t :link :target "u" :inlines [(str-inline "l")] :attr attr}
                                    (str-inline ",") space
                                    {:t :code :text "c"}
                                    (str-inline ",") space
                                    {:t :math-inline :tex "x^2"}
                                    (str-inline ",") space
                                    {:t :cross-ref :label "fig:t"}
                                    (str-inline ",") space
                                    {:t :cite :items [{:key "knuth" :mode :author}]})
                         space (str-inline "here.")]}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc))))
  (testing "a highlight nests inside emphasis, and inside a link"
    (let [doc (parser/parse "*emph with ==mark== in it* and [==marked==](u).\n")]
      (is (= [{:t :para
               :inlines [{:t :emph :inlines [(str-inline "emph") space (str-inline "with") space
                                             (highlight (str-inline "mark")) space
                                             (str-inline "in") space (str-inline "it")]}
                         space (str-inline "and") space
                         {:t :link :target "u" :inlines [(highlight (str-inline "marked"))]
                          :attr attr}
                         (str-inline ".")]}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc))))
  (testing "a highlight in image alt text contributes its words to the
            flattened `:alt` string rather than vanishing"
    (let [doc (parser/parse "![a ==marked== alt](i.png)\n")]
      (is (= "a marked alt" (get-in (:blocks doc) [0 :inlines 0 :alt])))
      (is (ast/valid? ast/Document doc)))))

(deftest highlight-in-every-container-test
  (testing "AC #3: a highlight parses in a heading, a figure caption, a
            table cell and a list item, not only in a plain paragraph"
    (let [heading (parser/parse "# A ==marked== heading\n")
          figure (parser/parse "![A ==marked== caption](i.png){#fig:a}\n")
          table (parser/parse "| a | b |\n|---|---|\n| ==c== | d |\n")
          a-list (parser/parse "- item with ==mark==\n")]
      (is (= [(str-inline "A") space (highlight (str-inline "marked")) space
              (str-inline "heading")]
             (get-in (:blocks heading) [0 :heading])))
      (is (= [(str-inline "A") space (highlight (str-inline "marked")) space
              (str-inline "caption")]
             (get-in (:blocks figure) [0 :caption])))
      (is (= [{:t :para :inlines [(highlight (str-inline "c"))]}]
             (get-in (:blocks table) [0 :rows 0 :cells 0 :blocks]))
          "a highlight in a table cell -- the case that crashed flexmark's
           own table pre-processor until the synthetic node was given a
           real source span")
      (is (= [{:t :para :inlines [(str-inline "item") space (str-inline "with") space
                                  (highlight (str-inline "mark"))]}]
             (get-in (:blocks a-list) [0 :items 0])))
      (doseq [doc [heading figure table a-list]]
        (is (ast/valid? ast/Document doc))))))

(deftest highlight-attribute-group-test
  (testing "AC #4: an attribute group after the closing == attaches its
            id, classes and props to the :highlight itself -- no wrapping
            Span, since unlike a Code span a :highlight carries an :attr
            of its own (sec4.4)"
    (let [doc (parser/parse "Tagged ==text=={#hl:a .important note=check} here.\n")]
      (is (= [{:t :para
               :inlines [(str-inline "Tagged") space
                         {:t :highlight :inlines [(str-inline "text")]
                          :attr {:id "hl:a" :classes ["important"] :props {"note" "check"}}}
                         space (str-inline "here.")]}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc)))))

(deftest highlight-does-not-disturb-existing-equals-syntax-test
  (testing "AC #5, hazard 1 of 2 (doc-8 pitfall 2): a setext heading
            underline is still a setext heading, at every length. It is a
            BLOCK-level construct, settled before inline parsing runs at
            all, which is why the delimiter processor cannot reach it --
            asserted rather than assumed, in the same way TASK-7's `$`
            math had to be shown not to eat `$5`"
    (doseq [underline ["=" "==" "===" "===================="]]
      (let [doc (parser/parse (str "Heading\n" underline "\n\nBody.\n"))]
        (testing (str "underline of length " (count underline))
          (is (= [{:t :section :level 1 :heading [(str-inline "Heading")]
                   :blocks [{:t :para :inlines [(str-inline "Body.")]}]
                   :attr attr}]
                 (:blocks doc)))
          (is (ast/valid? ast/Document doc))))))
  (testing "AC #5, hazard 2 of 2: key=val inside an attribute group is
            unchanged, and so is a quoted value that itself contains =="
    (let [doc (parser/parse "[t]{#p key=val other=\"a==b\"} tail.\n")]
      (is (= [{:t :para
               :inlines [{:t :span :inlines [(str-inline "t")]
                          :attr {:id "p" :classes [] :props {"key" "val" "other" "a==b"}}}
                         space (str-inline "tail.")]}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc))))
  (testing "a lone = and a lone == in running prose stay literal text --
            neither is a delimiter run that opens anything"
    (let [doc (parser/parse "x = y and z == w here\n")]
      (is (= [{:t :para :inlines [(str-inline "x") space (str-inline "=") space
                                  (str-inline "y") space (str-inline "and") space
                                  (str-inline "z") space (str-inline "==") space
                                  (str-inline "w") space (str-inline "here")]}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc)))))

(deftest highlight-unclosed-and-escaped-test
  (testing "AC #6: an unclosed == stays literal text rather than
            swallowing the rest of the paragraph"
    (let [doc (parser/parse "An unclosed ==thing keeps going.\n")]
      (is (= [{:t :para :inlines [(str-inline "An") space (str-inline "unclosed") space
                                  (str-inline "==thing") space (str-inline "keeps") space
                                  (str-inline "going.")]}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc))))
  (testing "AC #6: \\== escapes the construct -- an ordinary CommonMark
            backslash escape, since = is already escapable punctuation, so
            the surviving single = never reaches the two-character minimum"
    (let [doc (parser/parse "Escaped \\==not a highlight== here.\n")]
      (is (= [{:t :para :inlines [(str-inline "Escaped") space (str-inline "==not") space
                                  (str-inline "a") space (str-inline "highlight==") space
                                  (str-inline "here.")]}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc)))))

(deftest highlight-is-shielded-inside-verbatim-constructs-test
  (testing "AC #7: == inside inline code is content"
    (let [doc (parser/parse "Code `a == b` here.\n")]
      (is (= [{:t :para :inlines [(str-inline "Code") space {:t :code :text "a == b"}
                                  space (str-inline "here.")]}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc))))
  (testing "AC #7: == inside a fenced code block is content"
    (let [doc (parser/parse "```\nx ==y== z\n```\n")]
      (is (= [{:t :code-block :text "x ==y== z\n" :attr attr}] (:blocks doc)))
      (is (ast/valid? ast/Document doc))))
  (testing "AC #7: == inside display math is content"
    (let [doc (parser/parse "$$\na ==b== c\n$$\n")]
      (is (= [{:t :math-block :tex "a ==b== c" :attr attr}] (:blocks doc)))
      (is (ast/valid? ast/Document doc))))
  (testing "AC #7: == inside INLINE math is raw TeX, not a highlight"
    (let [doc (parser/parse "Let $a ==b$ hold.\n")]
      (is (= [{:t :para :inlines [(str-inline "Let") space {:t :math-inline :tex "a ==b"}
                                  space (str-inline "hold.")]}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc)))))

(deftest highlight-delimiter-run-semantics-test
  (testing "TASK-122 review: the delimiter-run behaviour this construct
            defines, pinned rather than left to whatever flexmark
            happens to do. A run longer than two opens with two of its
            characters and leaves the rest as literal text -- deliberately
            NOT emphasis's rule-of-three, which has no meaning for a
            construct with exactly one length"
    (let [doc (parser/parse "===three===\n")]
      (is (= [{:t :para :inlines [(str-inline "=")
                                  (highlight (str-inline "three"))
                                  (str-inline "=")]}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc))))
  (testing "an opener flanked by whitespace on its inner side opens
            nothing -- CommonMark's own left-flanking rule, the same one
            that stops `* not emph *`"
    (let [doc (parser/parse "a == b == c\n")]
      (is (= [{:t :para :inlines [(str-inline "a") space (str-inline "==") space
                                  (str-inline "b") space (str-inline "==") space
                                  (str-inline "c")]}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc))))
  (testing "an unclosed == earlier in a paragraph does not consume a
            properly closed pair later in it"
    (let [doc (parser/parse "stray == then ==marked== after\n")]
      (is (= [{:t :para :inlines [(str-inline "stray") space (str-inline "==") space
                                  (str-inline "then") space
                                  (highlight (str-inline "marked")) space
                                  (str-inline "after")]}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc))))
  (testing "a highlight spanning a soft line break keeps the break inside
            it, so the anchor resolver's own whitespace normalisation
            sees exactly the words the author wrote"
    (let [doc (parser/parse "A ==run across\ntwo lines== here.\n")]
      (is (= [{:t :para :inlines [(str-inline "A") space
                                  (highlight (str-inline "run") space (str-inline "across")
                                             {:t :soft-break}
                                             (str-inline "two") space (str-inline "lines"))
                                  space (str-inline "here.")]}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc))))
  (testing "a highlight nested directly inside another is one node, not
            two -- the inner pair closes the outer opener"
    (let [doc (parser/parse "x ==a ==b== c== y\n")]
      (is (= :highlight (get-in (:blocks doc) [0 :inlines 2 :t])))
      (is (ast/valid? ast/Document doc))))
  (testing "== at the very start and the very end of a paragraph"
    (let [doc (parser/parse "==whole line==\n")]
      (is (= [{:t :para :inlines [(highlight (str-inline "whole") space (str-inline "line"))]}]
             (:blocks doc)))
      (is (ast/valid? ast/Document doc)))))

(def ^:private windows-twin-source
  "A document touching every line-based rule: front matter, `!include`,
  `!quote`, a one-line `!name{}` directive, a fenced directive, a code
  fence, display math, a table, a list, a block quote and a hard break."
  (str "---\ntitle: Twin\nauthor: [A, B]\n---\n"
       "\n# Intro {#sec:a}\n"
       "\n!include chapters/one.hdoc\n"
       "\n!quote bible:romanos#14:8\n"
       "\n!note{.x}\n"
       "\n:::{note #n1}\nhello [t]{#sp}  \nnext line\n:::\n"
       "\n```clj\n(+ 1 2)\n  indented\n```\n"
       "\n$$\nx = 1\n$$ {#eq:one}\n"
       "\n| a | b |\n|---|---|\n| 1 | 2 |\n"
       "\n- one\n- two\n\n> quoted\n> more\n"))

(deftest crlf-and-byte-order-mark-test
  (let [lf (parser/parse windows-twin-source)
        crlf-source (str/replace windows-twin-source "\n" "\r\n")]
    (testing "TASK-162 AC #1: CRLF parses to the same document as LF"
      (is (= lf (parser/parse crlf-source)))
      (is (= [{:t :str :text "Twin"}] (get-in lf [:meta :title])))
      (is (= ["A" "B"] (get-in lf [:meta :authors])))
      (is (= #{:include :book-quote :directive :code-block :math-block :table :list :block-quote}
             (into #{} (comp (mapcat :blocks) (map :t)) (:blocks lf))))
      (let [strings (filter string? (tree-seq coll? seq (parser/parse crlf-source)))]
        (is (some #(str/includes? % "(+ 1 2)") strings) "the walk reaches the code block's text")
        (is (not-any? #(str/includes? % "\r") strings) "no \\r survives in any string of the AST"))
      (is (= (parser/front-matter windows-twin-source) (parser/front-matter crlf-source))))
    (testing "TASK-162 AC #2: a leading BOM is ignored, with LF or CRLF"
      (is (= lf (parser/parse (str "\uFEFF" windows-twin-source))))
      (is (= lf (parser/parse (str "\uFEFF" crlf-source))))
      (is (= (:meta lf) (parser/front-matter (str "\uFEFF" crlf-source)))))
    (testing "only the leading BOM and only CRLF pairs are normalised"
      (is (= "a\uFEFFb\rc\n\n" (parser/normalize-source "\uFEFFa\uFEFFb\rc\r\n\r\n")))
      (is (= "x" (parser/normalize-source "x"))))))

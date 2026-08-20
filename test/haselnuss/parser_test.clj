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
            HtmlRenderer"
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
      (is (ast/valid? ast/Document doc)))))

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
                         (str-inline ",") space (str-inline "[a") space (str-inline "shortcut]")
                         (str-inline ",") space (str-inline "and") space
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
            finding #2); both are fixed by falling back to the raw
            source text as literal Inlines instead of trusting the
            mini-parse result blindly."
    (doseq [[source cite-key mode field expected]
            [;; Crash repros (finding #1): a dash immediately before `@`
             ;; (mode :year, prefix "-" alone), a dash-space prefix, a
             ;; dash suffix (with and without a locator after it), and an
             ;; ordered-list marker prefix.
             ["[--@smith04]\n" "smith04" :year :prefix [(str-inline "-")]]
             ["[- @doe99]\n" "doe99" :normal :prefix [(str-inline "-") space]]
             ["[@doe99 -]\n" "doe99" :normal :suffix [(str-inline "-")]]
             ["[@doe99, - foo]\n" "doe99" :normal :suffix
              [(str-inline "-") space (str-inline "foo")]]
             ["[1. @doe99]\n" "doe99" :normal :prefix [(str-inline "1.") space]]
             ;; Data-loss repros (finding #2): an empty heading,
             ;; blockquote, fenced-code marker, and thematic break.
             ["[# @doe99]\n" "doe99" :normal :prefix [(str-inline "#") space]]
             ["[> @doe99]\n" "doe99" :normal :prefix [(str-inline ">") space]]
             ["[``` @doe99]\n" "doe99" :normal :prefix [(str-inline "```") space]]
             ["[*** @doe99]\n" "doe99" :normal :prefix [(str-inline "***") space]]]]
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

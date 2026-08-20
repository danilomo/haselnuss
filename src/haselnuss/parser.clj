(ns haselnuss.parser
  "Parses Haselnuss `.hdoc` source documents into the `haselnuss.ast` AST
  (`SPEC.md` sec5): a leading YAML front-matter block
  populates `Meta`, and standard CommonMark constructs -- paragraphs, ATX
  headings (folded into nested Section blocks by level), emphasis/strong,
  inline code and fenced/indented code blocks, blockquotes, ordered/
  unordered lists, links, images, and thematic breaks -- populate `:blocks`.
  \"Links\"/\"images\" covers both inline syntax (`[text](url)`,
  `![alt](src)`) and reference-style/shortcut syntax (`[text][ref]`,
  `[ref]`, `![alt][ref]` resolved against a `[ref]: url` definition) --
  CommonMark treats these as the same construct, not a separate one, and an
  undefined reference falls back to its own literal text rather than
  erroring, per CommonMark sec6.3/6.4. Backslash-escaped ASCII punctuation
  (sec2.4, e.g. `a \\* b`) resolves to the bare character in every
  plain-text run, in image alt text, and in link/image destination URLs
  (e.g. `\\)` inside a URL to include a literal parenthesis).

  Front matter is a simple, well-defined line-delimited block, so its outer
  shape is detected by hand; the actual YAML *content* is parsed by
  `clj-yaml` (wraps snakeyaml), not reimplemented here.

  Block directives (TASK-6, sec5.5: a fence of 3+ colons, an optional name
  and attribute group `{name #id .class key=val}` on the opening line,
  nested content, and a closing fence of at least as many colons, with
  nesting expressed by a longer colon-run on the outer fence) are, likewise,
  detected by hand over the raw source lines rather than via a flexmark
  extension: unlike TASK-5's attribute groups, flexmark has no bundled
  extension for this Pandoc-style fenced-div syntax, and a correct custom
  `BlockParserFactory` covering arbitrary nesting is a much larger
  investment than this hand-rolled recursive-descent line scan (see
  `parse-directive-region`), which mirrors this file's own established
  `split-front-matter` pattern. Each directive's own content, and each
  contiguous run of non-directive lines, is parsed by a *separate* flexmark
  call rather than one whole-document parse; a documented consequence is
  that reference-style link definitions (`[ref]: url`) only resolve within
  the chunk/directive they are parsed in, not document-wide, whenever the
  document contains at least one directive fence -- out of TASK-6's
  acceptance criteria and not exercised by any existing fixture, consistent
  with this file's established practice of narrowing scope explicitly (see
  `fenced-lang`'s docstring for TASK-5's own precedent). TASK-38's
  `!include` line is recognized in the same scan and splits the same
  way, so it inherits that consequence exactly -- see the \"Include
  lines\" section for the two behaviors this shares with a fence.
  Because this
  raw-line scan runs *before* flexmark, it independently tracks backtick/
  tilde code-fence state itself (`annotate-code-fences`) so a colon run
  inside a plain fenced code block's own content -- SPEC.md sec5.5 shows
  directive syntax presented that way as a worked example -- is treated as
  ordinary code content rather than misread as a real directive fence.

  CommonMark itself is parsed by flexmark-java rather than a hand-rolled
  parser: it is spec-compliant (correct emphasis delimiter-run matching,
  tight/loose list detection, etc. without reimplementing CommonMark's
  trickier corners) and -- important for TASK-5 through TASK-10, which
  layer attributes/directives/roles/math/cross-refs/citations/footnotes on
  top of this same surface syntax -- exposes a documented Java extension
  API (custom block/inline parsers, delimiter processors) that those later
  parser tasks are expected to build on, rather than swapping frontends.
  flexmark's own parse tree is converted into this project's AST by a small
  `cond`/`instance?` dispatch per node class; an unrecognized node class
  throws a catchable `ex-info` rather than silently mis-converting, per this
  codebase's established error-handling ethos (see `haselnuss.json`).

  Figures and pipe tables (TASK-8, sec5.8/5.9) build on flexmark's own GFM
  pipe-table extension (`TablesExtension`, registered on `cm-parser`
  alongside TASK-5's `AttributesExtension`) for the table grid itself
  (head/body rows, column alignment), but sec5.9's own
  `: caption text {#id}` trailing-caption-line syntax is not something that
  extension recognizes on its own -- confirmed via javap against the actual
  flexmark-ext-tables 0.64.8 jar that its `WITH_CAPTION` option only
  recognizes MultiMarkdown-style `[Caption]` bracket captions, a different,
  unrelated syntax -- so it is hand-detected against the already-parsed
  tree instead (see `table-caption-paragraph?`/`table-caption-and-attr`,
  used from `block-items`), the same general approach (detect the shape
  flexmark actually produces, don't fight it) TASK-5's attribute-attachment
  code and this section's own `convert-children` already take. A standalone
  image with an id becomes a numbered `Figure` (see `convert-block`'s
  Paragraph case) purely by inspecting the already-converted Inlines of an
  ordinary Paragraph -- no new flexmark-level recognition needed there at
  all, since TASK-5 already attaches an image's attribute group directly to
  its own `:attr`.

  CommonMark-extension footnotes (TASK-10, sec5.12: an inline `[^label]`
  marker plus a block-level `[^label]: ...` definition elsewhere in the
  document, combining into an inline `Note` carrying the definition's
  Blocks at the marker's position) are, unlike TASK-7 through TASK-9's
  math/cross-ref/citation syntax, handled by a real, bundled flexmark
  extension (`FootnoteExtension`, registered on `cm-parser` alongside
  TASK-5/TASK-8's `AttributesExtension`/`TablesExtension`) rather than a
  hand-rolled custom inline parser or raw-line scan: confirmed via javap
  against the actual flexmark-ext-footnotes 0.64.8 jar, plus a live nREPL
  check of its actual parsed output, that it already provides everything
  this construct needs -- a `Footnote` inline node for the marker and a
  `FootnoteBlock` block node for the definition, wired together by its own
  automatic, order-independent, whole-Document reference resolution
  (`Footnote.getReferenceNode(Document)`, `nil` when no definition
  matches) -- the same resolution mechanism this file's own `resolve-
  reference`/`RefNode` already relies on for `[ref]: url` link reference
  definitions, and inheriting that exact same, already-documented
  per-chunk-only narrowing (see `parse-directive-region`'s own docstring):
  whenever the document contains at least one directive fence or
  display-math block, each independently-parsed chunk gets its own
  independent flexmark Document, so a footnote definition -- like a link
  reference definition -- only resolves within the chunk it is parsed in,
  not document-wide. Not exercised by any existing fixture, consistent
  with this file's established practice of narrowing scope explicitly.

  Unlike a `[ref]: url` link reference definition, a footnote definition's
  own body is itself further Blocks that get run back through this same
  conversion (`convert-blocks`, see `convert-inline`'s `Footnote` branch)
  -- and that body can itself contain a `[^label]` marker resolving back
  to the same FootnoteBlock (self-reference) or to another FootnoteBlock
  that eventually cycles back to it (mutual reference), which would
  otherwise recurse forever and crash with an uncaught
  `StackOverflowError` instead of any catchable diagnostic (review
  finding against this task). Guarded by threading an `active-footnotes`
  set -- the labels of every FootnoteBlock currently being expanded on the
  current call stack -- through every one of `convert-blocks`/
  `convert-block`/`block-items`/`convert-inlines`/`convert-inline`/
  `convert-children` and their block-container-specific callers
  (`list-item-blocks`, `table-cell->cell`, etc.), the same way `document`
  itself is already threaded through this same call graph for reference
  resolution (TASK-4/TASK-9): a `Footnote` marker whose resolved label is
  already in `active-footnotes` throws `ex-info` (`:type
  ::cyclic-footnote`) instead of expanding its (already-being-expanded)
  definition again. A fresh independent parse/re-parse scope (a
  directive/math chunk's own flexmark Document, or one of this file's
  per-fragment mini-documents, e.g. a table caption or citation prefix/
  suffix) always starts this set back at `#{}`, exactly as it starts a
  fresh per-chunk `[ref]: url` resolution scope -- only a genuine
  Footnote-expansion recursive call extends the set an existing caller
  already holds."
  (:require [clj-yaml.core :as yaml]
            [clojure.string :as str]
            [haselnuss.ast :as ast])
  (:import (com.vladsch.flexmark.ast AutoLink BlockQuote Code DelimitedLinkNode
                                     DelimitedNodeImpl Emphasis FencedCodeBlock
                                     HardLineBreak Heading Image ImageRef
                                     IndentedCodeBlock Link LinkRef ListBlock ListItem
                                     MailLink OrderedList Paragraph Reference RefNode
                                     SoftLineBreak StrongEmphasis Text TextBase
                                     ThematicBreak)
           (com.vladsch.flexmark.ext.attributes AttributeNode AttributesExtension
                                                AttributesNode)
           (com.vladsch.flexmark.ext.footnotes Footnote FootnoteBlock FootnoteExtension)
           (com.vladsch.flexmark.ext.tables TableBlock TableBody TableCell
                                            TableCell$Alignment TableHead
                                            TablesExtension)
           (com.vladsch.flexmark.parser InlineParserExtension
                                        InlineParserExtensionFactory
                                        LightInlineParser Parser)
           (com.vladsch.flexmark.util.ast Block Document Node)
           (com.vladsch.flexmark.util.data MutableDataSet)
           (com.vladsch.flexmark.util.sequence BasedSequence)
           (java.time ZoneOffset)
           (java.util Date)))

(def ^:private default-attr
  "The Attr value for any node this parser produces with no attribute
  group (`{#id .class key=val}`, sec5.3) attached: no id, no classes, no
  props."
  {:classes [] :props {}})

;; --- Inline math (TASK-7, sec5.7) -------------------------------------------
;;
;; Single-`$`-delimited inline math (`$e^{i\pi}+1=0$` -> MathInline, AC #1) is
;; recognized via flexmark's documented custom-inline-parser-extension
;; mechanism (`InlineParserExtensionFactory`/`InlineParserExtension`, wired
;; into `cm-parser` below via `customInlineParserExtensionFactory`) -- the
;; same "custom inline parsers" extension point this namespace's own
;; docstring calls out as what TASK-5 through TASK-10 build on. This is a
;; different situation from TASK-5's attribute groups or a delimiter-run
;; construct like emphasis/strong (also `DelimitedNodeImpl` subclasses, see
;; `math-inline-class`): math content is raw TeX that must never be
;; interpreted as further markdown (AC #1, "including special LaTeX
;; characters"), so it needs the same "grab the verbatim span, don't
;; recurse into it" treatment CommonMark itself gives backtick code spans --
;; exactly what a custom inline-parser extension provides, since it is
;; triggered directly by the core character scanner at the `$` itself,
;; before any other special-character handling reaches that position
;; (confirmed via javap/REPL: unlike a `DelimiterProcessor`, which only runs
;; *after* the whole paragraph has already been provisionally tokenized for
;; every other special character -- too late to stop e.g. a backtick inside
;; `$...$` from starting its own, separate code span).
;;
;; Display math (`$$ ... $$`, sec5.7 AC #2/#3) is deliberately NOT handled
;; by this same inline mechanism: a MathBlock is a top-level Block (a
;; sibling of Paragraph, sec4.3), never something that can legitimately
;; live nested inside one, so it is instead recognized by hand over raw
;; lines -- see `extract-math-chunks`, in the block-directives section
;; below, alongside the block-directive/code-fence scanning it deliberately
;; mirrors.

(defn- math-inline-node
  "A fresh flexmark inline Node representing one MathInline construct
  (sec5.7's single-`$` syntax): `whole-span` is the whole matched span
  (opening `$` through closing `$` inclusive, for `getChars`/segment
  bookkeeping), `text` the raw TeX between them. Built via `proxy` over
  `DelimitedNodeImpl` -- the same base `Code` itself extends (see
  `convert-inline`'s `Code` branch) -- purely for its ready-made opening-
  marker/text/closing-marker storage and its `getSegments` implementation
  (the only abstract method `Node` itself declares), since this file has
  no other need for a dedicated compiled class. Every call anywhere
  produces an instance of the exact same dynamically generated class
  (Clojure's `proxy` caches the generated class by its base-class list,
  not by call site), which is what lets `math-inline-node?` recognize one
  reliably -- see that function for why plain `instance?` would not do."
  ^DelimitedNodeImpl [^BasedSequence whole-span ^BasedSequence open-marker
                      ^BasedSequence text ^BasedSequence close-marker]
  (let [node (proxy [DelimitedNodeImpl] [])]
    (.setChars ^Node node whole-span)
    (.setOpeningMarker node open-marker)
    (.setText node text)
    (.setClosingMarker node close-marker)
    node))

(def ^:private math-inline-class
  "The exact runtime class every `math-inline-node` instance shares (see
  its docstring) -- captured once (via a disposable instance built purely
  to read its class off) so `math-inline-node?` can recognize one by exact
  class identity rather than `instance?`, which `Code`, `Emphasis`, and
  `StrongEmphasis` (also concrete `DelimitedNodeImpl` subclasses, confirmed
  via javap) would otherwise satisfy too."
  (class (proxy [DelimitedNodeImpl] [])))

(defn- math-inline-node?
  "True if `node` is one of this file's own synthetic MathInline nodes
  (see `math-inline-node`/`math-inline-class`), as opposed to any other
  flexmark node type -- including an unrelated `DelimitedNodeImpl`
  subclass such as `Code`/`Emphasis`/`StrongEmphasis`."
  [node]
  (identical? math-inline-class (class node)))

(defn- inline-math-closing-index
  "The absolute index within `s` (a `LightInlineParser`'s whole
  `getInput`, i.e. the enclosing paragraph/heading/etc.'s full raw text) of
  the `$` that closes the opening one at `open-index`, or nil if there is
  none. Sec5.7 AC #1/#4: a candidate closing `$` must satisfy *both* halves
  of pandoc's own dollar-math heuristic -- not preceded by whitespace, AND
  not immediately followed by a digit -- and must fall on the same source
  line as the opener (inline math spanning a soft line break is out of
  this task's chosen scope). The digit-adjacency half specifically guards
  a currency-range mention like `$5/$10`, `$5-$10`, or `$5,$10` (no space
  before the second `$`): without it, the first `$...$` pair found (`$5/`)
  would be misparsed as math and the trailing `10` left as stray literal
  text (TASK-7 review) -- unlike the space-separated `$5 and $10` case,
  where the second `$` is preceded by whitespace and already fails the
  first half alone. A `$` that fails either check is treated as ordinary
  content and scanning continues past it, exactly like pandoc's own
  dollar-math parser -- a known, accepted quirk of that same choice
  (not unique to this implementation) is that an earlier, unrelated `$` in
  the same line can still end up absorbed into a later, validly-closed
  pair's content (e.g. `$5 and $x$` pairs the *whole* `5 and $x` as one
  span, rather than leaving `$5` as literal currency next to a separate
  `$x$` math span); deliberately not solved here, since disambiguating it
  in general is inherently ambiguous, and this file's own test fixtures
  (AC #4) stick to plain currency mentions with no adjacent real math on
  the same line, where this heuristic is unambiguous."
  [^String s open-index n]
  (loop [j (inc open-index)]
    (cond
      (>= j n) nil
      (= (.charAt s j) \newline) nil
      (and (= (.charAt s j) \$)
           (not (Character/isWhitespace (.charAt s (dec j))))
           (or (>= (inc j) n) (not (Character/isDigit (.charAt s (inc j))))))
      j
      :else (recur (inc j)))))

(defn- parse-inline-math!
  "This file's `InlineParserExtension/parse` for the `$` trigger character
  (see `inline-math-extension-factory`): true if it consumed something at
  `p`'s current index (either a literal `$$` or a genuine MathInline node),
  false to fall through to flexmark's own default single-character literal
  handling for the `$` at that position (sec5.7 AC #1/#4)."
  [^LightInlineParser p]
  (let [^BasedSequence input (.getInput p)
        s (.toString input)
        n (.length input)
        i (.getIndex p)]
    (cond
      ;; "$$": never single-$ math -- display math is recognized only at
      ;; the block level (`extract-math-chunks`), never reaching this
      ;; extension at all. Both characters are consumed as literal text in
      ;; one step so the *second* `$` is never independently re-offered as
      ;; a fresh opening attempt on its own, which would otherwise let
      ;; e.g. "$$double$$" mid-paragraph turn into a stray literal `$`
      ;; plus a MathInline("double") plus another stray literal `$`.
      (and (< (inc i) n) (= (.charAt s (inc i)) \$))
      (do (.appendText p (.subSequence input (int i) (int (+ i 2))))
          (.setIndex p (+ i 2))
          true)

      ;; An opening `$` immediately followed by whitespace, or at the very
      ;; end of input, can never start valid math (AC #4) -- fall through
      ;; to ordinary single-character literal handling.
      (or (>= (inc i) n) (Character/isWhitespace (.charAt s (inc i))))
      false

      :else
      (if-let [close (inline-math-closing-index s i n)]
        (do (.appendNode p (math-inline-node (.subSequence input (int i) (int (inc close)))
                                             (.subSequence input (int i) (int (inc i)))
                                             (.subSequence input (int (inc i)) (int close))
                                             (.subSequence input (int close) (int (inc close)))))
            (.setIndex p (inc close))
            true)
        false))))

(def ^:private inline-math-extension-factory
  "The `InlineParserExtensionFactory` registered on `cm-parser` (via
  `customInlineParserExtensionFactory`) for the `$` trigger character --
  see this section's own docstring for why inline math needs this
  mechanism rather than TASK-5's attribute-group post-processing or a
  `DelimiterProcessor`. Stateless (all state is read from the
  `LightInlineParser` argument passed to `parse` each call), so `apply`
  can return a fresh, equally stateless `InlineParserExtension` every time
  without needing to cache/reuse one. Built via `proxy` rather than
  `reify`: `InlineParserExtensionFactory` extends `Function<LightInlineParser,
  InlineParserExtension>`, and `reify`-ing an interface with both that
  generic `Function/apply` and the factory's own covariantly-typed `apply`
  hits a `reify`/`deftype` limitation (confirmed via REPL: \"Mismatched
  return type: apply, expected: InlineParserExtension, had:
  java.lang.Object\") that `proxy`'s reflective method dispatch does not."
  (proxy [InlineParserExtensionFactory] []
    (getCharacters [] "$")
    (getAfterDependents [] #{})
    (getBeforeDependents [] #{})
    (affectsGlobalScope [] false)
    (apply [_lp]
      (reify InlineParserExtension
        (finalizeDocument [_ _])
        (finalizeBlock [_ _])
        (parse [_ p] (parse-inline-math! p))))))

;; --- Cross-references and citations (TASK-9, sec5.10/5.11) -----------------
;;
;; Two more `@`-triggered syntaxes, both recognized the same way as TASK-7's
;; inline math -- a custom `InlineParserExtensionFactory`, triggered directly
;; by the core character scanner *before* any other special-character
;; handling reaches that position (see the "Inline math" section's own
;; docstring for why this mechanism, not a `DelimiterProcessor` or
;; post-processing, is needed here too):
;;
;; - A bare `@prefix:label` (sec5.10, e.g. `@fig:tree`) is a CrossRef (AC #1).
;; - A bare `@key` with no colon (sec5.11, "author-in-text") is a Cite with
;;   one CiteItem, mode `:author` (AC #3).
;; - `[@key, locator; @key2]` (sec5.11) is a Cite with one CiteItem per
;;   `;`-separated key, each with optional prefix/suffix Inlines (AC #2).
;;
;; Confirmed via REPL that `proxy`'s generated class is cached purely by its
;; base-class list, not by call site or which methods are overridden at
;; construction: reusing TASK-7's own bare `[DelimitedNodeImpl]` combination
;; for any of these three new node types would silently alias its class with
;; `math-inline-class` itself the moment both kinds of node existed in the
;; same document, breaking every one of these `-node?` predicates' (and
;; `math-inline-node?`'s) class-identity dispatch. Each new node type below
;; therefore adds its own zero-method `definterface` marker to the proxy's
;; base-class list purely to force a distinct class -- the marker itself is
;; never otherwise referenced.
;;
;; Disambiguation (AC #4) is what decides *which* of the three a given `@`
;; becomes, decided before any AST is built, not after:
;; - Inside a `[...]` bracket is unconditionally a citation (sec5.11 itself
;;   frames this as *the* disambiguator against a bare CrossRef) --
;;   `parse-cite-bracket!` claims the character scanner's `[` itself, ahead
;;   of flexmark's own core bracket/link handling for that same character
;;   (confirmed via CFR-decompiled `InlineParserImpl.parseInline` bytecode,
;;   0.64.8: a registered extension for a character runs, and can decline by
;;   returning false, strictly *before* that same character's `switch`
;;   branch -- including `case '['`), so an ordinary link/text bracket with
;;   no `@key`-shaped marker in it is left completely untouched.
;; - Bare (unbracketed), a colon inside the token means CrossRef, no colon
;;   means author-in-text Cite (sec5.10's own kind:label framing vs.
;;   sec5.11's plain citation keys, e.g. `knuth1984`/`smith2020` -- none of
;;   SPEC.md's own examples of either shape contradict this).
;; - `parse-cross-ref-or-cite!` additionally declines outright (leaving a
;;   literal `@`) when the character immediately before it is itself a
;;   token character: an email/username-style `@` (`user@example.com`) has
;;   exactly this shape, and no SPEC.md `@`-reference example is ever
;;   preceded by one -- always whitespace or punctuation instead (e.g.
;;   "in @fig:tree", ": @knuth1984").
;;
;; WHICH `@`-TOKENS ARE REFERENCES AT ALL (TASK-47). Deciding this from shape
;; alone cannot work, because an unrelated colon convention -- a Matrix/Element
;; handle `@user:homeserver`, an IRC-ish nick -- has exactly the shape of a
;; genuine `@kind:label`, and a social mention "cc @someone" has exactly the
;; shape of a citation key. Shape alone turned both into references that then
;; resolved to nothing: a build warning and a `??` where the author had written
;; ordinary prose.
;;
;; So this parser accepts two optional vocabularies from its caller
;; (`parse`'s opts, bound to `*known-kinds*`/`*known-cite-keys*` for the
;; duration of one parse):
;;
;;   :kinds      the set of valid kind prefixes -- built-in plus whatever a
;;               custom directive registered (sec8.2). This is the resolver's
;;               label lexicon (sec6.1), which `haselnuss.cli` already
;;               assembles BEFORE parsing, so handing it over costs nothing.
;;   :cite-keys  the set of keys the loaded bibliography actually defines.
;;
;; Neither is required. With neither supplied the shape heuristics below are
;; used unchanged, so `haselnuss.parser` still works standalone and keeps its
;; independence from the resolver (AC #4) -- which is why the vocabularies are
;; passed in as data rather than looked up.
;;
;; THE TRADE-OFF, recorded because it cannot be avoided (AC #5). Once a
;; vocabulary is supplied, an `@`-token outside it is left as literal text,
;; with no warning -- so a reference to an unregistered kind (`@fgi:tree`, or
;; a kind whose directive was never registered) and a citation key missing
;; from the bibliography now silently become prose, where before each produced
;; a dangling-* warning and a visible `??`. That is the better failure of the
;; two: the author sees their own text, in their own document, in the place
;; they wrote it. `??` is not more informative for a reader and is worse for
;; an author, and the more common typo -- a wrong *label* under a real kind,
;; `@fig:tre` -- still warns exactly as it did. A caller that would rather
;; have the warning simply does not supply the vocabulary.
;;
;; That last sentence is not rhetorical. `haselnuss.cli` supplies these only
;; where Haselnuss itself is what resolves a reference. Native-mode LaTeX is
;; not: it emits `\Cref`/`\citet` for LaTeX and BibTeX to resolve against a
;; `.bib` and a `\label` namespace nothing here can see, so a token outside
;; *these* vocabularies may still be a perfectly good reference there.
;; Filtering it would replace working output with literal text rather than
;; with a `??`, which inverts the argument above (found by review). See
;; `haselnuss.cli/run`.
;;
;; The shape heuristics used when no vocabulary is supplied:
;; `cross-ref-prefix-shape?` requires the part before the first colon to look
;; like a plausible sec5.10 kind prefix (letter-first), which declines a time
;; mention like `@3:00pm` (prefix `"3"`) but cannot tell a real kind from a
;; lookalike. A colon-free `@word` is unconditionally a citation key. Both are
;; exactly what this file did before TASK-47.

(definterface ^:private CrossRefNodeTag)
(definterface ^:private CiteAuthorNodeTag)
(definterface ^:private CiteBracketNodeTag)

(defn- ref-token-char?
  "True if `c` may appear inside a bare cross-reference/citation-key token
  (sec5.10/5.11): a letter, digit, underscore, hyphen, or colon -- every
  character SPEC.md's own `@fig:tree`/`@thm:main`/`@sec:intro`/`@eq:euler`/
  `@knuth1984`/`@smith2020`/`@jones2019` examples use. Deliberately excludes
  `.` (unlike, say, pandoc's own more permissive citation-key alphabet):
  none of SPEC.md's own examples need a period inside a key, and including
  one would make a token immediately followed by ordinary sentence-final
  punctuation (`@fig:tree.` at a sentence's end) swallow that period into
  the label -- the same class of trailing-punctuation ambiguity TASK-7's own
  dollar-math heuristic documents and deliberately does not attempt to
  fully solve either."
  [c]
  (or (Character/isLetterOrDigit ^char c) (= c \_) (= c \-) (= c \:)))

(def ^:private ref-token-tail-punctuation
  "The `ref-token-char?` characters a real token cannot END with, and which
  are therefore prose when they are the last thing scanned.

  A label is `prefix:label` and a citation key is a word: both finish on a
  letter or a digit. The other three characters the alphabet admits are
  separators -- they join two parts, so a trailing one has nothing to join.
  Without this, `as shown in @fig:x:` scans the label as `fig:x:`, which
  matches no target and dangles, and `see @fig:x--` swallows the dash
  (TASK-73). Comma, period, semicolon and parenthesis never had the problem:
  they are not `ref-token-char?` at all, so the scan stops before them."
  #{\: \- \_})

(defn- ref-token-end
  "The end index (exclusive) of the token in `s` (length `n`) starting at
  `start`: the maximal run of `ref-token-char?` characters, less any
  trailing `ref-token-tail-punctuation`. Equal to `start` itself when there
  is no valid token to scan there at all -- including when the whole run was
  separators (`@:`), which is punctuation with an `@` in front of it rather
  than a reference to anything."
  [^String s start n]
  (loop [j start]
    (if (and (< j n) (ref-token-char? (.charAt s j)))
      (recur (inc j))
      (loop [j j]
        (if (and (> j start) (ref-token-tail-punctuation (.charAt s (dec j))))
          (recur (dec j))
          j)))))

(defn- cross-ref-node
  "A fresh synthetic Node for sec5.10's bare `@prefix:label` cross-reference
  syntax (AC #1): `whole-span` is the matched span, `@` through the label's
  last character inclusive. `.getText` on the result is the plain
  `prefix:label` string -- resolving the prefix into a kind is TASK-12's
  job, not this parser's (`haselnuss.ast`'s CrossRef carries a single
  opaque `:label` string, per SPEC.md sec4.4). Built the same way as
  TASK-7's `math-inline-node` (a `proxy` over `DelimitedNodeImpl`, for its
  ready-made segment/text storage) plus this file's own `CrossRefNodeTag`
  marker (see this section's own docstring for why)."
  ^DelimitedNodeImpl [^BasedSequence whole-span]
  (let [node (proxy [DelimitedNodeImpl CrossRefNodeTag] [])
        n (.length whole-span)]
    (.setChars ^Node node whole-span)
    (.setOpeningMarker node (.subSequence whole-span 0 1))
    (.setText node (.subSequence whole-span 1 n))
    (.setClosingMarker node (.subSequence whole-span n n))
    node))

(def ^:private cross-ref-class
  "The exact runtime class every `cross-ref-node` instance shares -- see
  `math-inline-class`'s own docstring for why this file recognizes a
  synthetic node by exact class identity rather than `instance?`."
  (class (proxy [DelimitedNodeImpl CrossRefNodeTag] [])))

(defn- cross-ref-node?
  "True if `node` is one of this file's own synthetic CrossRef nodes (see
  `cross-ref-node`/`cross-ref-class`)."
  [node]
  (identical? cross-ref-class (class node)))

(defn- cite-author-node
  "A fresh synthetic Node for sec5.11's bare, author-in-text `@key`
  citation (AC #3): `whole-span` is the matched span, `@` through the key's
  last character inclusive. `.getText` on the result is the plain
  citation key. See `cross-ref-node`'s own docstring for the shared
  `proxy`/marker-interface construction and why it is needed."
  ^DelimitedNodeImpl [^BasedSequence whole-span]
  (let [node (proxy [DelimitedNodeImpl CiteAuthorNodeTag] [])
        n (.length whole-span)]
    (.setChars ^Node node whole-span)
    (.setOpeningMarker node (.subSequence whole-span 0 1))
    (.setText node (.subSequence whole-span 1 n))
    (.setClosingMarker node (.subSequence whole-span n n))
    node))

(def ^:private cite-author-class
  "The exact runtime class every `cite-author-node` instance shares -- see
  `cross-ref-class`'s own docstring."
  (class (proxy [DelimitedNodeImpl CiteAuthorNodeTag] [])))

(defn- cite-author-node?
  "True if `node` is one of this file's own synthetic bare author-in-text
  Cite nodes (see `cite-author-node`/`cite-author-class`)."
  [node]
  (identical? cite-author-class (class node)))

(def ^:private cross-ref-prefix-shape-re
  "A plausible sec5.10 kind-prefix shape: a letter, then any run of
  `ref-token-char?` word/hyphen characters -- the shape of every one of
  SPEC.md's own kind prefixes (`fig`/`thm`/`sec`/`eq`). Used only by
  `cross-ref-prefix-shape?`, this section's own docstring documents both
  what this cheaply catches and what it deliberately cannot."
  #"[A-Za-z][\w-]*")

(def ^:private ^:dynamic *known-kinds*
  "The set of valid kind prefixes for this parse, or nil when the caller
  supplied none (see this section's own docstring). nil and `#{}` mean
  different things on purpose: nil falls back to
  `cross-ref-prefix-shape?`, while an empty set is a caller saying there
  are no kinds, so no `@a:b` is a cross-reference."
  nil)

(def ^:private ^:dynamic *known-cite-keys*
  "The set of citation keys the bibliography defines for this parse, or
  nil when the caller supplied none -- in which case any colon-free
  `@word` is a citation key, as it was before TASK-47. See
  `*known-kinds*` on why nil and `#{}` differ."
  nil)

(defn- cross-ref-prefix-shape?
  "True if the portion of colon-containing ref token `token` before its
  *first* colon looks like a plausible sec5.10 kind prefix (see
  `cross-ref-prefix-shape-re`) rather than incidental non-identifier text
  -- e.g. rejects the `\"3\"` in `\"3:00pm\"` (a time mention, not a real
  `@kind:label`). A narrow, cheap shape check only, and the fallback used
  when no `:kinds` vocabulary was supplied: it cannot distinguish a
  genuine Haselnuss kind prefix from an unrelated but identically-shaped
  colon convention, e.g. a Matrix/Element chat handle
  `@user:homeserver`."
  [^String token]
  (boolean (re-matches cross-ref-prefix-shape-re (subs token 0 (str/index-of token \:)))))

(defn- cross-ref-token?
  "True if colon-containing `token` should become a CrossRef: its kind
  prefix is in `*known-kinds*` when the caller supplied one, else it
  merely has to look like a kind prefix."
  [^String token]
  (if *known-kinds*
    (contains? *known-kinds* (subs token 0 (str/index-of token \:)))
    (cross-ref-prefix-shape? token)))

(defn- cite-token?
  "True if colon-free `token` should become an author-in-text Cite: it is
  a key `*known-cite-keys*` defines when the caller supplied that set,
  else any token at all, as before TASK-47."
  [^String token]
  (or (nil? *known-cite-keys*) (contains? *known-cite-keys* token)))

(defn- parse-cross-ref-or-cite!
  "This file's `InlineParserExtension/parse` for the `@` trigger character
  (see the extension factory below): true if it consumed a bare CrossRef or
  author-in-text Cite at `p`'s current index, false to fall through to
  flexmark's own default single-character literal handling for the `@` at
  that position (AC #1/#3/#4) -- `@` is not otherwise special to CommonMark,
  so declining here is exactly as safe as TASK-7's own `$` fallback.

  Declines when nothing at all follows the `@` that could start a token
  (end of input, whitespace, punctuation), when the character immediately
  *before* the `@` is itself a `ref-token-char?` -- guarding against
  misreading an email/username-style `@` (`user@example.com`) as a
  reference (see this section's own docstring) -- or when the token is
  not one this document's vocabulary recognizes (`cross-ref-token?` /
  `cite-token?`). None of the three synthetic node types is a good fit
  for an `@`-token that is not a reference, so it is left as ordinary
  literal text instead, the same way invalid/absent math content falls
  through to plain-text handling rather than being forced into some node
  type anyway."
  [^LightInlineParser p]
  (let [^BasedSequence input (.getInput p)
        s (.toString input)
        n (.length input)
        i (.getIndex p)
        start (inc i)
        end (ref-token-end s start n)]
    (if (or (= end start) (and (pos? i) (ref-token-char? (.charAt s (dec i)))))
      false
      (let [token (subs s start end)
            whole (.subSequence input (int i) (int end))
            colon? (str/includes? token ":")]
        (if-not (if colon? (cross-ref-token? token) (cite-token? token))
          false
          (do (.appendNode p (if colon? (cross-ref-node whole) (cite-author-node whole)))
              (.setIndex p end)
              true))))))

(def ^:private cross-ref-cite-extension-factory
  "The `InlineParserExtensionFactory` registered on `cm-parser` for the `@`
  trigger character -- see `inline-math-extension-factory`'s own docstring
  for why `proxy` (not `reify`) is used here too."
  (proxy [InlineParserExtensionFactory] []
    (getCharacters [] "@")
    (getAfterDependents [] #{})
    (getBeforeDependents [] #{})
    (affectsGlobalScope [] false)
    (apply [_lp]
      (reify InlineParserExtension
        (finalizeDocument [_ _])
        (finalizeBlock [_ _])
        (parse [_ p] (parse-cross-ref-or-cite! p))))))

(defn- cite-bracket-node
  "A fresh synthetic Node for sec5.11's bracketed citation syntax (AC #2):
  `whole-span` is the matched span, the opening `[` through the closing `]`
  inclusive. `.getText` on the result is the raw text strictly *between*
  the brackets (still unparsed -- see `cite-bracket-node->cite`, which does
  the actual per-`;`-segment parsing once `convert-inline` reaches it). See
  `cross-ref-node`'s own docstring for the shared `proxy`/marker-interface
  construction and why it is needed."
  ^DelimitedNodeImpl [^BasedSequence whole-span]
  (let [node (proxy [DelimitedNodeImpl CiteBracketNodeTag] [])
        n (.length whole-span)]
    (.setChars ^Node node whole-span)
    (.setOpeningMarker node (.subSequence whole-span 0 1))
    (.setText node (.subSequence whole-span 1 (dec n)))
    (.setClosingMarker node (.subSequence whole-span (dec n) n))
    node))

(def ^:private cite-bracket-class
  "The exact runtime class every `cite-bracket-node` instance shares -- see
  `cross-ref-class`'s own docstring."
  (class (proxy [DelimitedNodeImpl CiteBracketNodeTag] [])))

(defn- cite-bracket-node?
  "True if `node` is one of this file's own synthetic bracketed Cite nodes
  (see `cite-bracket-node`/`cite-bracket-class`)."
  [node]
  (identical? cite-bracket-class (class node)))

(def ^:private citation-item-marker-re
  "An *unescaped* `@key` citation-key marker (sec5.11) occurring anywhere
  within a string -- used by `citation-bracket-content?` to decide whether
  a bracket's raw content looks like a citation at all, not to extract the
  key itself (see `citation-segment-re` for that). The leading
  `(?<!\\\\)` negative lookbehind skips a backslash-escaped `\\@`
  (CommonMark sec2.4) entirely, so it never counts as a real marker --
  matching how the bare `@` form already honors that same escape (review
  finding #4: `[\\@doe99]` must not be swallowed into a citation any more
  than `\\@fig:tree` is read as a bare CrossRef)."
  #"(?<!\\)@[\w:-]+")

(defn- citation-bracket-content?
  "True if `inner` (the raw text strictly between a `[` and its candidate
  matching `]`) looks enough like sec5.11's bracketed citation syntax to
  claim the whole bracket (AC #4): `inner` contains at least one real,
  unescaped `@key` marker *and* every `;`-separated segment contains at
  least one of its own (see `citation-item-marker-re`). An ordinary link/
  text bracket with no unescaped `@` at all -- an empty bracket (`[]`),
  one made up only of `;` characters (`[;]`/`[;;;]`), or one whose only
  `@` is backslash-escaped (`[\\@doe99]`) -- is declined here and left for
  flexmark's own default bracket/backslash-escape handling instead.

  The up-front `(re-find citation-item-marker-re inner)` check exists
  because `every?` over an *empty* collection is vacuously true: review
  finding #3 caught that `(str/split \";\" #\";\")` (and `\";;;\"`, etc.)
  is `[]`, not `[\"\"]`, since `String/split` drops *trailing* empty
  tokens -- so the old code's bare `every?` wrongly accepted `[;]`/
  `[;;;]` (zero `@` markers at all, zero segments after the split) as a
  citation, producing a nonsensical zero-CiteItem `Cite` node. Requiring
  at least one real marker to exist anywhere in `inner` first closes that
  gap without changing the split's own behavior for any other input: an
  empty bracket (`[]`, `(str/split \"\" #\";\")` => `[\"\"]`) and a
  genuine multi-key citation are both unaffected.

  A known, deliberately accepted false-positive limitation (mirroring
  TASK-7's own accepted heuristic tradeoffs, e.g. its dollar-math currency
  case): ordinary bracketed prose that happens to contain an email/
  username-style `@word` token in every semicolon-separated segment (an
  unlikely combination) is still claimed as a citation, since this check
  has no email-shape awareness of its own the way `parse-cross-ref-or-
  cite!`'s bare-form adjacency check does."
  [inner]
  (and (re-find citation-item-marker-re inner)
       (every? #(re-find citation-item-marker-re %) (str/split inner #";"))))

(defn- cite-bracket-close-index
  "The absolute index within `s` (length `n`) of the `]` that closes the
  opening `[` at `open-index`, or nil if there is none. Mirrors TASK-7's
  own `inline-math-closing-index`: restricted to the same source line (a
  closing `]` is never searched for past a newline), and a backslash
  escapes the very next character (an escaped `\\]` does not close it).
  Deliberately does not track nested `[`/`]` pairs at all (like TASK-7's
  own heuristic, a documented, accepted scope reduction) -- no SPEC.md
  citation example ever nests a bracket inside another."
  [^String s open-index n]
  (loop [j (inc open-index)]
    (cond
      (>= j n) nil
      (= (.charAt s j) \newline) nil
      (and (= (.charAt s j) \\) (< (inc j) n)) (recur (+ j 2))
      (= (.charAt s j) \]) j
      :else (recur (inc j)))))

(defn- parse-cite-bracket!
  "This file's `InlineParserExtension/parse` for the `[` trigger character
  (see the extension factory below): true if it consumed a whole bracketed
  citation at `p`'s current index, false to fall through to flexmark's own
  default bracket/link handling for the `[` at that position (AC #2/#4).

  Confirmed via CFR-decompiled `InlineParserImpl.parseInline` bytecode
  (0.64.8) that a registered extension's `false` return here is safe: the
  core scanner only reaches its own `case '['` (`parseOpenBracket`, the
  mechanism behind ordinary links/images/reference definitions) *after*
  every registered extension for that same character has already declined
  -- so an ordinary link/text bracket, or one flexmark ultimately can't
  resolve at all, is untouched by this ever having run at all."
  [^LightInlineParser p]
  (let [^BasedSequence input (.getInput p)
        s (.toString input)
        n (.length input)
        i (.getIndex p)]
    (if-let [close (cite-bracket-close-index s i n)]
      (if (citation-bracket-content? (subs s (inc i) close))
        (do (.appendNode p (cite-bracket-node (.subSequence input (int i) (int (inc close)))))
            (.setIndex p (inc close))
            true)
        false)
      false)))

(def ^:private cite-bracket-extension-factory
  "The `InlineParserExtensionFactory` registered on `cm-parser` for the `[`
  trigger character -- see `inline-math-extension-factory`'s own docstring
  for why `proxy` (not `reify`) is used here too."
  (proxy [InlineParserExtensionFactory] []
    (getCharacters [] "[")
    (getAfterDependents [] #{})
    (getBeforeDependents [] #{})
    (affectsGlobalScope [] false)
    (apply [_lp]
      (reify InlineParserExtension
        (finalizeDocument [_ _])
        (finalizeBlock [_ _])
        (parse [_ p] (parse-cite-bracket! p))))))

(def ^:private ^Parser cm-parser
  "A single, reusable flexmark Parser for CommonMark's core constructs plus
  TASK-5's attribute-group syntax (`{#id .class key=val}`, sec5.3), TASK-7's
  single-`$`-delimited inline math (see `inline-math-extension-factory`),
  TASK-8's GFM pipe tables (`TablesExtension`, sec5.9 -- the caption-
  line syntax on top of it is hand-detected instead, see this namespace's
  own docstring), and TASK-9's `@`-triggered cross-reference/citation
  syntax (see `cross-ref-cite-extension-factory`/
  `cite-bracket-extension-factory`, sec5.10/5.11), and TASK-10's
  `[^label]`/`[^label]: ...` footnote syntax (`FootnoteExtension`, sec5.12
  -- see this namespace's own docstring for why this one, unlike TASK-7
  through TASK-9, needs no hand-rolled recognition of its own): the
  `AttributesExtension` is confirmed (via javap/REPL
  against the actual flexmark-ext-attributes 0.64.8 jar) to attach an
  `AttributesNode` as the immediate next sibling after a Heading's text, an
  Image, a Link, a Code span, or a `LinkRef` (bracket-only or reference-
  style alike), whenever there is no gap between them -- exactly the
  adjacency this file's attribute-attachment/Span conversion logic
  (`parse-attr-group`, `convert-children`, `pop-trailing-attr-group`)
  relies on. Whether a space precedes the `{` does NOT gate whether the
  group attaches at all (re-verified via REPL after a TASK-5 review finding
  contradicted this docstring's earlier, incorrect claim that it did): a
  heading's or paragraph's plain-text run immediately before a
  *successfully parsed* AttributesNode is *sometimes* wrapped by flexmark
  in an internal `TextBase` container purely for its own delimiter
  bookkeeping -- re-confirmed via REPL (TASK-8) that this wrapping is
  data-dependent (present for some plain-text runs, absent for others, with
  no simple rule this file relies on) rather than a fixed rule keyed on
  position or a preceding space -- see the `TextBase` branch of
  `convert-inline`, which exists solely to see through this wrapper
  whenever it does appear. Parser instances are immutable and safe to reuse
  (flexmark's own documented usage pattern). Hinted `^Parser` so `parse`'s
  `(.parse cm-parser ...)` call resolves without reflection."
  (.build (.customInlineParserExtensionFactory
           (.customInlineParserExtensionFactory
            (.customInlineParserExtensionFactory
             (Parser/builder (doto (MutableDataSet.)
                               (.set Parser/EXTENSIONS
                                     (java.util.List/of (AttributesExtension/create)
                                                        (TablesExtension/create)
                                                        (FootnoteExtension/create)))))
             inline-math-extension-factory)
            cross-ref-cite-extension-factory)
           cite-bracket-extension-factory)))

(def ^:private escapable-punctuation
  "ASCII punctuation characters a CommonMark backslash escape (sec2.4) may
  precede: `!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~`. A backslash before any
  other character (whitespace, alphanumerics) is not an escape and is left
  as a literal backslash."
  (set "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"))

(defn- unescape-backslashes
  "Strips the backslash from every CommonMark backslash-escape (sec2.4) in
  `s`: a `\\` immediately followed by an ASCII punctuation character
  collapses to just that character. flexmark's own node classes (Text,
  RefNode/LinkRef/ImageRef's raw fallback text, and TextCollectingVisitor's
  flattened output) all preserve the *raw* source span, backslash
  included, so this must run on every plain-text/alt-text string this
  parser produces or a construct like `a \\* b` would keep its escaping
  backslash instead of resolving to `a * b` (verified against flexmark's
  own HtmlRenderer, which does resolve it)."
  [^String s]
  (let [n (count s)]
    (loop [i 0 out (StringBuilder.)]
      (if (>= i n)
        (.toString out)
        (let [c (.charAt s i)]
          (if (and (= c \\) (< (inc i) n) (escapable-punctuation (.charAt s (inc i))))
            (recur (+ i 2) (.append out (.charAt s (inc i))))
            (recur (inc i) (.append out c))))))))

(declare alt-text)

(defn- cite-alt-text
  "One `:cite` Inline as the plain text an image's alt attribute gets
  (`alt-text`): its authored spelling, since a citation carries no
  formatted `:text` until `haselnuss.resolver` runs and an alt text is
  fixed at parse time.

  `@key` for the bare author form and `[@key]` for the bracket form,
  which is what was written; a year-only item keeps its `-@`. A prefix
  and a locator suffix are included -- `[see @knuth1984, p. 42]` becomes
  `[see @knuth1984 p. 42]` -- because dropping them would be the same
  silent loss this task exists to remove, in smaller print. What is NOT
  reproduced is the punctuation CSL uses to introduce a locator: this is
  a readable plain-text rendering of what the author wrote, not a
  byte-exact reconstruction of the source."
  [{:keys [items]}]
  (letfn [(item [{cite-key :key :keys [mode prefix suffix]}]
            (->> [(alt-text prefix)
                  (str (if (= :year mode) "-@" "@") cite-key)
                  (alt-text suffix)]
                 (remove str/blank?)
                 (str/join " ")))]
    (if (= :author (:mode (first items)))
      (str "@" (:key (first items)))
      (str "[" (str/join "; " (map item items)) "]"))))

(defn- alt-text
  "An image's `:alt` -- the plain string sec4.4 requires -- flattened from
  its own already-converted Inlines rather than from flexmark's own
  TextCollectingVisitor, which is what this parser used to flatten it
  with (TASK-69).

  The distinction is not cosmetic. That visitor collects the text of
  Text nodes and nothing else, so this parser's own
  synthetic math and citation nodes contributed NOTHING to the alt text:
  `![Rate of $x$, after [@knuth]](r.png)` produced `\"Rate of , after \"`,
  with the build exiting 0. Flattening the converted Inlines instead
  keeps every authored word in the attribute -- math as its TeX, a
  citation as the keys it cites, a cross-reference as the id it points at
  -- while leaving `:alt` the plain string an HTML `alt=` attribute has
  to be. `figure-caption` is where the same content keeps its markup.

  Mirrors `haselnuss.emit.html/plain-text-inline`, which does this for
  `<title>` for the same reason: a string that has to be character data
  is not a licence for it to be a lossy one.

  A footnote is the one thing refused rather than flattened -- see
  `note-in-alt!`."
  [inlines]
  (str/join
   (map (fn [inline]
          (case (:t inline)
            :str (:text inline)
            (:space :soft-break :line-break) " "
            (:emph :strong :strike :small-caps :sub :sup :span :link) (alt-text (:inlines inline))
            :code (:text inline)
            :image (:alt inline)
            :math-inline (:tex inline)
            ;; The authored spelling of each, since neither carries text
            ;; of its own until the resolver runs: an alt text is fixed
            ;; at parse time and the resolver never revisits it.
            :cross-ref (str "@" (:label inline))
            :cite (cite-alt-text inline)
            ""))
        inlines)))

(defn- note-in-alt!
  "Throws (`:type ::footnote-in-image-alt`) if `inlines` -- an image with
  `src`'s own converted alt Inlines -- contain a footnote anywhere.

  The reason is representational, and it is the same in every target: an
  `:alt` is a plain `:string` by schema (sec4.4), and a footnote has no
  plain-text form at all. Its whole content is Blocks living somewhere
  else in the document, so there is nothing for `alt-text` to flatten it
  to -- a marker with no note, or a note body spliced into an attribute,
  would each be a different lie. That reasoning is why this lives in the
  parser rather than in an emitter, and why the message says nothing
  about LaTeX: it holds for an inline image in prose that becomes no
  caption at all, which a LaTeX-shaped rule would not have covered
  (found by review, which also pointed out that arguing from LaTeX in a
  target-agnostic layer cuts against this repo's own precedent -- the
  float-inside-a-float guard is in `haselnuss.emit.latex` for exactly
  that reason).

  Rendering it was checked before being refused, since AC #1 allows
  either. There is no rendering: an id-bearing image becomes a Figure and
  its alt text becomes the `\\caption`, where `\\footnote` fails the
  compile outright in native mode (`Argument of \\caption@ydblarg has an
  extra }`, no PDF at all), `\\protect\\footnote` typesets the note twice
  with disagreeing numbers, and `\\caption[short]{... \\footnote{...}}`
  compiles and loses the note body -- all three confirmed against a real
  pdflatex. So rendering it would have meant a document whose HTML prints
  a footnote its PDF does not.

  The remedy is in the message, and it is what a typesetter would do
  anyway: put the note in the prose around the image, which is where a
  reader looks for it. `src` is in the `ex-data` so the message names
  which image -- a thesis has a hundred of them."
  [src inlines]
  (letfn [(note? [inline]
            (or (= :note (:t inline))
                (some note? (:inlines inline))
                (some note? (:items inline))
                (some note? (concat (:prefix inline) (:suffix inline)))))]
    (when (some note? inlines)
      (throw (ex-info
              (str "footnote inside the alt text of image " (pr-str src)
                   ": an alt text is plain text, and a footnote has no plain-text form"
                   " -- its content is blocks elsewhere in the document, so there is nothing"
                   " to put in the attribute. Put the note in the prose around the image")
              {:type ::footnote-in-image-alt :src src})))))

(defn- text->inlines
  "Splits plain text `s` into a vector of `:str`/`:space` Inlines, mirroring
  CommonMark's Str/Space atomicity (sec4.4): each run of non-whitespace is
  its own `:str`, each run of whitespace collapses to one `:space` --
  shared by front-matter `title` and every plain-text run in the body."
  [s]
  (->> (re-seq #"\S+|\s+" s)
       (mapv (fn [tok] (if (str/blank? tok) {:t :space} {:t :str :text tok})))))

;; --- Attribute groups (TASK-5, sec4.1/5.3) ----------------------------------
;;
;; `AttributesExtension` reliably *positions* an `AttributesNode` as the
;; immediate next sibling after an attachable construct (confirmed via
;; javap/REPL against the actual jar; see `cm-parser`), but it is more
;; permissive than Haselnuss's own grammar -- it happily accepts a bare word
;; with no `#`/`.`/`=` -- and it never itself signals a parse failure: any
;; input it can't fully parse (an unclosed `{`, or a group it rejects
;; outright) is simply left as literal text. So this parser treats
;; `AttributesNode`'s *position* as authoritative but re-validates its
;; *content* itself (`attribute-token`), and separately watches for the
;; "flexmark gave up and left literal text" case (`attr-attempt-re`) to
;; satisfy AC #4 (a diagnostic, not a silent drop) in both failure modes.

(defn- attribute-token
  "One flexmark `AttributeNode` (a single token inside a `{...}` group) as
  a `[:id id]` / `[:class class]` / `[:prop key value]` vector, per
  Haselnuss's exactly-three attribute forms (sec4.1/5.3: `#id`, `.class`,
  `key=val`). `.isId`/`.isClass` identify the first two; anything else
  with a non-blank `.getAttributeSeparator` (the `=` itself) is a
  `key=val` pair. `.getValue` has already had any surrounding quotes
  removed, so `key=\"two words\"` arrives here as one node with a
  two-word value and needs nothing from this function -- quoting on this
  path is entirely flexmark's, which is why `split-attr-tokens` (the
  tokenizer for the two groups this file splits itself) was written to
  match its grammar rather than invent one. A bare word (no `#`, `.`, or
  `=` at all) fits none of
  these three forms -- flexmark's own extension accepts it regardless, so
  this must be caught explicitly -- and throws `ex-info`
  (`:type ::malformed-attributes`) rather than silently becoming an
  unlabeled prop or being dropped (AC #4)."
  [^AttributeNode node]
  (cond
    (.isId node) [:id (.toString (.getValue node))]
    (.isClass node) [:class (.toString (.getValue node))]
    (not (str/blank? (.toString (.getAttributeSeparator node))))
    [:prop (.toString (.getName node)) (.toString (.getValue node))]
    :else
    (throw (ex-info "malformed attribute group: token is not #id, .class, or key=val"
                    {:type ::malformed-attributes
                     :token (.toString (.getChars node))}))))

(defn- parse-attr-group
  "An `AttributesNode` (a whole `{...}` group) as a `haselnuss.ast` Attr
  map. A repeated `#id` or `key` keeps only its last occurrence (`assoc`/
  `update ... assoc` naturally overwrite); repeated `.class` tokens all
  accumulate into `:classes`, in source order."
  [^AttributesNode node]
  (reduce (fn [attr [kind a b]]
            (case kind
              :id (assoc attr :id a)
              :class (update attr :classes conj a)
              :prop (update attr :props assoc a b)))
          default-attr
          (map attribute-token (.getChildren node))))

(def ^:private attr-attempt-re
  "Matches a `{` immediately followed by `#`, `.`, or an identifier `=` --
  the start of what looks like an attempted `{#id .class key=val}`
  attribute group (sec5.3). Deliberately narrow (an unadorned `{` alone,
  or `{}`/`{=val}`, does not match) so ordinary prose that merely contains
  a literal brace is not misflagged; only text that looks like a genuine
  attempt trips it."
  #"\{(?:[#.]|[A-Za-z_][\w-]*=)")

(defn- check-no-attribute-attempt!
  "Throws `ex-info` (`:type ::malformed-attributes`) if raw source text
  `s` still contains what looks like an attempted attribute group (see
  `attr-attempt-re`). `AttributesExtension` splits any group it can fully
  parse out of its surrounding Text node entirely (confirmed via REPL:
  the successfully-parsed span is gone from what remains), so by the time
  `s` is a Text node's own content, any such match is one flexmark
  *tried* to parse here and left as-is -- either unclosed (no `}` before
  this run of text ends) or a group it rejected outright (e.g. one
  containing a token that itself is not valid syntax at all) -- never a
  case this parser should silently pass through as prose (AC #4)."
  [^String s]
  (when (re-find attr-attempt-re s)
    (throw (ex-info "malformed attribute group: unclosed, or invalid and rejected outright"
                    {:type ::malformed-attributes :text s}))))

;; --- Section folding -------------------------------------------------------
;;
;; ATX headings arrive as siblings of the blocks that follow them; AC #2
;; requires them folded into nested Section blocks by level instead. This is
;; a small pure stack of "open" sections: opening a heading of level N closes
;; every currently open section whose level is >= N (so a shallower or
;; equal-level heading ends the previous one), then pushes a new open
;; section; anything left open at the end of the input is closed too.

(defn- close-top
  "Pops the innermost open section off `stack`, finalizes it into a
  `:section` Block, and appends it to the (now) top of the stack. `:attr`
  is whatever the heading's own trailing attribute group parsed to (or
  `default-attr` if it had none -- see `pop-trailing-attr-group`,
  TASK-5 AC #1), carried on the open-section frame since `open-section`."
  [stack]
  (let [top (peek stack)
        below (pop stack)]
    (conj (pop below)
          (update (peek below) :blocks conj
                  {:t :section
                   :level (:level top)
                   :heading (:heading top)
                   :blocks (:blocks top)
                   :attr (:attr top)}))))

(defn- open-section
  "Closes every open section at `level` or deeper, then pushes a new open
  section at `level` with heading Inlines `heading` and Attr `attr`."
  [stack level attr heading]
  (let [stack (loop [s stack]
                (if (and (> (count s) 1) (>= (:level (peek s)) level))
                  (recur (close-top s))
                  s))]
    (conj stack {:level level :attr attr :heading heading :blocks []})))

(defn- append-block
  "Appends completed Block `block` to the innermost open section on `stack`."
  [stack block]
  (conj (pop stack) (update (peek stack) :blocks conj block)))

(defn- heading-marker
  "A transient marker (never itself an AST node) standing in for an ATX/
  setext heading in the flat sequence `fold-sections` walks. `attr` is the
  Section's own Attr, parsed from a trailing attribute group if the
  heading had one (TASK-5 AC #1), or `default-attr` otherwise."
  [level attr heading]
  {::heading true :level level :attr attr :heading heading})

(defn- fold-sections
  "Given a flat sequence mixing completed Blocks and `heading-marker`s,
  returns the top-level `:blocks` vector with every heading folded into a
  nested `:section` Block by level (AC #2)."
  [items]
  (let [final (reduce (fn [stack item]
                        (if (::heading item)
                          (open-section stack (:level item) (:attr item) (:heading item))
                          (append-block stack item)))
                      [{:level 0 :blocks []}]
                      items)
        closed (loop [s final] (if (> (count s) 1) (recur (close-top s)) s))]
    (:blocks (peek closed))))

;; --- Inline conversion ------------------------------------------------------

(declare convert-inline attach-link-ref-attr convert-blocks)

(defn- bracket-only-link-ref?
  "True if `node` is a *bracket-only* LinkRef -- `[text]` with no second
  `[ref]`/`[]` bracket at all -- as opposed to a full reference-style link
  (`[text][ref]`) or a collapsed one (`[text][]`), which both carry a
  second, explicit bracket pair even though `.getReference` and
  `.isReferenceTextCombined` alone do not reliably distinguish all three
  shapes (confirmed via javap/REPL against the real flexmark-ext-
  attributes 0.64.8 jar: `isReferenceTextCombined` is `true` for *both*
  the bracket-only and the collapsed form). What does reliably
  distinguish them is `.getTextClosingMarker`: flexmark only populates a
  LinkRef's separate *text* segment (as opposed to folding text and
  reference into one combined segment) when the source genuinely has two
  bracket pairs -- for a true single-bracket form, this segment is left
  blank/unset. This matters because only the bracket-only form is TASK-5
  AC #3's inline-Span syntax (sec5.6); the other two remain ordinary
  reference-style links (TASK-4) and must not lose their resolved target
  to a same-shaped `LinkRef` Java class check."
  [^LinkRef node]
  (str/blank? (.toString (.getTextClosingMarker node))))

(defn- convert-children
  "Converts an explicit sequence of flexmark inline nodes `children` to a
  vector of `haselnuss.ast` Inlines, flattening (each child yields 0+
  Inlines, e.g. a Text node with internal whitespace yields several).
  `document` is threaded through purely so a LinkRef/ImageRef reachable
  from any depth can resolve its `[ref]: url` definition -- see
  `convert-inline`. `active-footnotes` is threaded through the same way,
  purely so a `Footnote` reachable from any depth can detect a resolution
  cycle -- see `convert-inline`'s `Footnote` branch and this namespace's
  own docstring.

  Implements TASK-5's attribute-attachment/inline-Span mechanism
  (sec4.1/5.3/5.6): looks one position ahead for a trailing
  `AttributesNode` (confirmed via javap/REPL to be flexmark's own
  positioning for a `{#id .class key=val}` group immediately after an
  attachable construct -- see `cm-parser`) immediately after:
    - a bracket-only `LinkRef` (`[text]` with no second `[ref]`/`[]`
      bracket, see `bracket-only-link-ref?`) -> an inline Span carrying
      that bracket's own enclosed Inlines and the parsed Attr,
      unconditionally -- taking priority over this LinkRef's normal
      reference-resolution/literal-fallback handling, since `[text]{...}`
      (sec5.6) is a distinct surface form from a reference link
      (sec6.3/6.4), not a special case of one (confirmed by sec5.10's
      `[text]{ref=...}` cross-reference role syntax, which relies on this
      same generic Span mechanism regardless of whether `text` happens to
      also match some unrelated `[text]: url` definition) (AC #3);
    - a full or collapsed reference-style `LinkRef` (`[text][ref]` /
      `[text][]`, i.e. any `LinkRef` that is *not* bracket-only) ->
      `attach-link-ref-attr` (TASK-4/TASK-5 interaction: this must keep
      the resolved link target rather than silently becoming a
      target-less Span, which is what this same-Java-class check used to
      do before this was distinguished from the bracket-only case above);
    - an `Image` or `Link` (already has its own `:attr` in the AST) ->
      merges the parsed Attr directly into that node's own `:attr` (AC #2,
      image case, and by the same mechanism, Link);
    - a `Code` inline span (has no `:attr` field in the AST at all, per
      `haselnuss.ast`/SPEC.md sec4.4) -> wraps it in a Span whose sole
      Inline is that Code node and whose Attr is the parsed group (AC #2,
      code-span case).
  Anything else immediately followed by an AttributesNode (e.g.
  `**bold**{.x}`) is deliberately left alone here, falling through to
  `convert-inline`'s existing default `::unsupported-node` error on the
  `AttributesNode` itself -- out of this task's explicit scope, and not a
  silently-ignored no-op, consistent with this file's established
  error-handling ethos."
  [^Document document active-footnotes children]
  (let [children (vec children)
        n (count children)]
    (loop [i 0 acc []]
      (if (>= i n)
        acc
        (let [child (nth children i)
              nxt (when (< (inc i) n) (nth children (inc i)))
              attrs-node (when (instance? AttributesNode nxt) nxt)]
          (cond
            (and attrs-node (instance? LinkRef child) (bracket-only-link-ref? child))
            (recur (+ i 2)
                   (conj acc {:t :span
                              :inlines (convert-children document active-footnotes (.getChildren ^Node child))
                              :attr (parse-attr-group attrs-node)}))

            (and attrs-node (instance? LinkRef child))
            (recur (+ i 2)
                   (into acc (attach-link-ref-attr document active-footnotes child (parse-attr-group attrs-node))))

            (and attrs-node (or (instance? Image child) (instance? Link child)))
            (recur (+ i 2)
                   (conj acc (assoc (first (convert-inline document active-footnotes child))
                                    :attr (parse-attr-group attrs-node))))

            (and attrs-node (instance? Code child))
            (recur (+ i 2)
                   (conj acc {:t :span
                              :inlines (convert-inline document active-footnotes child)
                              :attr (parse-attr-group attrs-node)}))

            :else
            (recur (inc i) (into acc (convert-inline document active-footnotes child)))))))))

(defn- convert-inlines
  "Converts every child of `node` to Inlines -- see `convert-children`,
  which does the actual work; this is the Node-based convenience wrapper
  every existing call site outside heading conversion uses (heading
  conversion needs to pop a trailing AttributesNode off the child list
  before converting the rest -- see `pop-trailing-attr-group`)."
  [^Document document active-footnotes ^Node node]
  (convert-children document active-footnotes (.getChildren node)))

(defn- resolve-reference
  "The flexmark `Reference` a LinkRef/ImageRef `node`'s label resolves to
  against `document`'s collected `[ref]: url` definitions (sec4.7 of the
  CommonMark spec covers link reference definitions; they may appear
  anywhere in the document, before or after the reference that uses them,
  which is why this needs the whole `document`, not just a local lookup).
  nil if `node`'s label has no matching definition -- CommonMark does not
  treat that as an error, it falls back to literal text (sec6.3/6.4),
  matching flexmark's own HtmlRenderer behavior (verified via REPL: an
  undefined `[foo][nope]` renders as the literal text `[foo][nope]`, not a
  broken link)."
  ^Reference [^Document document ^RefNode node]
  (.getReferenceNode node document))

(defn- autolink
  "The `:link` Inline for a CommonMark autolink (sec6.5) -- a bare URI or
  email address in angle brackets, `<https://example.com>` or
  `<someone@example.com>` -- with `scheme` prepended to the target
  (TASK-72).

  An autolink is a Link whose text is its own target, which is the whole
  of what this has to say; the AST needed nothing new for it. `scheme` is
  `\"\"` for a URI autolink, whose text already carries one, and
  `\"mailto:\"` for an email autolink, which CommonMark gives that scheme
  and which flexmark leaves off (`MailLink.getUrl` is empty -- confirmed
  at the REPL -- so `.getText` is what both node types are read through).

  No `unescape-backslashes`, unlike every other text run this file
  produces: CommonMark does no backslash-escape processing inside an
  autolink at all, so a backslash in one is part of the URI. That is the
  one decision here a refactor could quietly undo -- routing these
  through `convert-inline`'s own `Link` branch would restore the
  unescaping -- so `haselnuss.parser-test/autolink-test` pins it with a
  URL that has a backslash in it.

  Neither node type is a `Link` subclass (both extend `DelimitedLinkNode`,
  checked rather than assumed): the `Link` branch above cannot swallow
  them, and neither branch here is dead. Worth verifying, because an
  `AutoLink` that WAS a `Link` would still have passed its test -- its
  `.getUrl` equals its own text."
  [^DelimitedLinkNode node scheme]
  (let [text (.toString (.getText node))]
    [{:t :link
      :target (str scheme text)
      :inlines (text->inlines text)
      :attr default-attr}]))

(defn- literal-ref-text
  "The literal-text fallback for a LinkRef/ImageRef `node` whose reference
  is undefined (see `resolve-reference`): its own raw source span,
  backslash-unescaped like any other plain text."
  [^Node node]
  (text->inlines (unescape-backslashes (.toString (.getChars node)))))

(defn- attach-link-ref-attr
  "`convert-children`'s handling for a full/collapsed reference-style
  LinkRef `child` (i.e. `bracket-only-link-ref?` is false) that has a
  trailing attribute group, already parsed into Attr `attr`: returns the
  resulting vector of Inlines. Fixes a TASK-5 regression against TASK-4's
  reference-link resolution, where treating every LinkRef the same as a
  bracket-only one silently discarded the resolved link target and
  produced a target-less Span instead.

  When `child`'s reference resolves (see `resolve-reference`), `attr`
  merges directly into the resulting `:link` Inline -- the same
  direct-merge mechanism as an inline Link/Image (AC #2). When it does
  not resolve, CommonMark's own literal-text fallback (sec6.3/6.4, see
  `literal-ref-text`) applies as it would with no attribute group at all,
  but the attribute group itself must still not be silently dropped (AC
  #4's no-silent-drop philosophy applied here too): since plain text has
  no `:attr` field of its own (per `haselnuss.ast`), the literal fallback
  text is wrapped in a Span carrying `attr`, the same device already used
  for a Code span (which likewise has no `:attr` field of its own)."
  [^Document document active-footnotes ^LinkRef child attr]
  (if-let [reference (resolve-reference document child)]
    [{:t :link
      :target (unescape-backslashes (.toString (.getUrl reference)))
      :inlines (convert-inlines document active-footnotes child)
      :attr attr}]
    [{:t :span :inlines (literal-ref-text child) :attr attr}]))

;; --- Citation item parsing (TASK-9, sec5.11) --------------------------------
;;
;; `cite-bracket-node` (see that section, above) stores its bracket's raw,
;; still-unparsed inner text; the actual per-`;`-segment parsing happens
;; here, once `convert-inline` reaches it, since it needs `convert-inlines`
;; (already in scope by this point in the file) to turn a segment's own
;; prefix/suffix text into real Inlines.

(defn- mini-parse-inlines
  "Parses freestanding text `s` (a citation segment's own prefix/locator/
  suffix text, sec5.11) as its own independent flexmark mini-document and
  returns its Inlines, or nil if `s` is blank -- mirrors TASK-8's
  `table-caption-and-attr`, which established this same per-fragment
  mini-reparse pattern for an isolated span of raw source text that still
  needs full inline parsing (emphasis, code spans, etc., not just plain
  text) of its own. Returning nil (rather than `[]`) for blank input lets
  callers use it directly as a `cond->`/`when-let` test to decide whether
  to set a CiteItem's optional `:prefix`/`:suffix` field at all.

  Only trusts the mini-parse's result when the mini-document's first
  child is actually a Paragraph -- i.e. `s` really did mini-parse to
  ordinary inline-shaped content. `s` is arbitrary user-written text with
  no guarantee it avoids CommonMark's own block-level syntax (review
  findings #1/#2): text starting with a list marker (`-`/`1.`), a
  blockquote `>`, a fenced-code backtick/tilde run, an ATX `#`, or a
  thematic-break run of `*`/`-`/`_` mini-parses to a BulletListItem/
  OrderedListItem/BlockQuote/FencedCodeBlock/Heading/ThematicBreak
  instead. Blindly calling `convert-inlines` on one of those crashes
  outright whenever it has non-inline Block children of its own --
  `convert-inline` has no branch for a Block appearing where an Inline is
  expected, and the resulting uncaught `ex-info` took down the *whole*
  document parse, not just this one citation (finding #1, e.g.
  `[--@smith04]`/`[- @doe99]`/`[@doe99 -]`/`[1. @doe99]`) -- and silently
  *drops* the user's literal text when it has no children at all (an
  empty heading/blockquote/code-fence/thematic-break mini-parses with
  zero children, so the old code returned `[]` instead of the source
  text, contradicting this very docstring's own \"nil, not `[]`, when
  blank\" contract -- finding #2, e.g. `[# @doe99]`/`[> @doe99]`/
  `` [``` @doe99] ``/`[*** @doe99]`).

  Falling back to treating the *raw* source text `s` as plain literal
  content (via `text->inlines`, the same device already used for an
  undefined link-reference's literal fallback) fixes both: it never
  recurses into a non-Paragraph block's own children (no crash), and it
  always preserves every character the user wrote (no data loss), at the
  cost of not interpreting markup (emphasis, code spans, etc.) inside a
  prefix/suffix that happens to also look like a block construct -- a
  deliberate, narrow trade-off, consistent with this file's other
  documented heuristic scope limits, since a citation's own prefix/suffix
  text is never expected to need block-level markup in the first place."
  [^String s]
  (when-not (str/blank? s)
    (let [mini-doc (.parse cm-parser s)
          mini-para (first (.getChildren mini-doc))]
      (if (instance? Paragraph mini-para)
        (convert-inlines mini-doc #{} mini-para)
        (text->inlines (unescape-backslashes s))))))

(defn- strip-leading-comma
  "`s`, trimmed, with one leading `,` (and any whitespace right after it)
  removed if present -- sec5.11's own worked example separates a citation
  key from its locator/suffix text with `, ` (`@knuth1984, p. 42` ->
  suffix `\"p. 42\"`), but a locator separated by whitespace alone (no
  comma at all) is left untouched, so both shapes end up with the same
  clean suffix text."
  [s]
  (str/replace-first (str/trim s) #"^,\s*" ""))

(def ^:private citation-segment-re
  "Splits one `;`-separated citation segment (sec5.11, already known via
  `citation-bracket-content?` to contain an unescaped `@key` marker) into:
  any prefix text before the key (group 1, lazy so it stops at the
  *first* unescaped `@` a dash/key could start at); an optional `-`
  immediately before the `@` (group 2) -- pandoc's own well-known
  suppress-author/\"year-only\" convention (SPEC.md documents
  `haselnuss.ast`'s CiteItem `:mode :year` as \"year-only\" without
  spelling out a trigger syntax of its own, and reusing pandoc's matches
  this project's own stated \"muscle memory\" preference for cross-refs,
  decision D3, applied the same way here); the key itself (group 3,
  `ref-token-char?`'s alphabet); and any trailing locator/suffix text
  (group 4, `strip-leading-comma` cleans it up). The same `(?<!\\\\)`
  negative lookbehind as `citation-item-marker-re` sits directly before
  the mandatory `@` literal, so a backslash-escaped `\\@` earlier in the
  segment (CommonMark sec2.4) is skipped over as ordinary prefix text
  rather than mistaken for the key's own start (review finding #4) --
  consistent with `citation-bracket-content?` already having required
  the segment to contain a *later*, genuinely unescaped `@key` marker
  before this regex ever runs on it at all."
  #"^(.*?)(-)?(?<!\\)@([\w:-]+)\s*(.*)$")

(defn- citation-segment->item
  "One sec5.11 CiteItem (AC #2) for a single `;`-separated citation segment
  `seg` -- see `citation-segment-re`. `:prefix`/`:suffix` are set only when
  their respective text (trimmed, and for suffix with `strip-leading-comma`
  applied) is non-blank (see `mini-parse-inlines`); `:mode` is `:year` when
  the segment had a suppress-author `-` immediately before its `@`,
  `:normal` otherwise (a bare, author-in-text Cite -- sec5.11's other
  mode -- is a wholly different, unbracketed construct, see
  `cite-author-node`, and never reaches this function at all)."
  [seg]
  (let [[_ prefix dash cite-key suffix] (re-matches citation-segment-re seg)
        prefix-inlines (mini-parse-inlines prefix)
        suffix-inlines (mini-parse-inlines (strip-leading-comma suffix))]
    (cond-> {:key cite-key :mode (if dash :year :normal)}
      prefix-inlines (assoc :prefix prefix-inlines)
      suffix-inlines (assoc :suffix suffix-inlines))))

(defn- cite-bracket-node->cite
  "The `:cite` Inline (AC #2) for a `cite-bracket-node`: one CiteItem (see
  `citation-segment->item`) per `;`-separated segment of its raw inner
  text, in source order."
  [^DelimitedNodeImpl node]
  {:t :cite
   :items (mapv citation-segment->item (str/split (.toString (.getText node)) #";"))})

(defn- convert-inline
  "Converts one flexmark inline `node` to a vector of 0+ `haselnuss.ast`
  Inlines. `document` is the enclosing flexmark Document, needed only to
  resolve LinkRef/ImageRef (reference-style and shortcut-reference links/
  images, e.g. `[text][ref]`, `[ref]`, `![alt][ref]`) against its `[ref]:
  url` definitions. Throws `ex-info` (`:type ::unsupported-node`) for any
  node class this parser does not yet handle.

  `TextBase` is treated transparently (its children converted and
  flattened straight through, exactly like an ordinary Text run): it is
  never a real Haselnuss/CommonMark construct of its own -- flexmark
  wraps a plain-text run in one purely as internal delimiter-matching
  bookkeeping whenever that run sits immediately before a *successfully
  parsed* AttributesNode (`{#id .class key=val}`, sec5.3), regardless of
  whether a space separates them (confirmed via javap/REPL: this wrapping
  is unconditional on that adjacency, contrary to this file's own
  earlier, incorrect claim -- see `cm-parser`'s docstring). A TASK-5
  review finding: without this branch, a heading with no space before
  its attribute group (`## Title{#id}`) crashed here instead of attaching
  (AC #1), and, worse, so did *any* unrelated plain-text run immediately
  before a well-formed `{...}` group elsewhere in a document (e.g. \"This
  is {.checking} nothing.\"), even though that text is not adjacent to
  anything Haselnuss recognizes as an attribute-attachment target. With
  this branch, the former now attaches correctly
  (`pop-trailing-attr-group` already pops the trailing AttributesNode
  before this is reached), and the latter falls through to
  `convert-children`'s already-documented, deliberate fallback -- the
  *next* loop iteration's `convert-inline` call on the AttributesNode
  itself hits this same function's `::unsupported-node` default, exactly
  as `**bold**{.x}` already does -- rather than crashing on this internal
  node type.

  A node satisfying `math-inline-node?` (TASK-7, sec5.7 AC #1) is this
  file's own synthetic MathInline node (see `math-inline-node`/
  `inline-math-extension-factory`); its `.getText` is used directly,
  bypassing `unescape-backslashes`/`text->inlines` entirely, since AC #1
  requires the raw TeX preserved exactly, including special LaTeX
  characters, not reinterpreted as CommonMark escapes/plain-text runs the
  way an ordinary Text node's content is.

  `cross-ref-node?`/`cite-author-node?`/`cite-bracket-node?` (TASK-9,
  sec5.10/5.11) are this file's own synthetic CrossRef/Cite nodes -- see
  that section, above, for how each is recognized and disambiguated at
  parse time (AC #4), and `cite-bracket-node->cite` for the bracketed
  form's own per-`;`-segment conversion (AC #2).

  A `Footnote` node (TASK-10, sec5.12, a real flexmark node class -- see
  `cm-parser`'s own docstring for why this construct needs no hand-rolled
  recognition of its own) resolves to the `FootnoteBlock` its label
  matches (`.getReferenceNode`, already document-wide and order-
  independent -- AC #3) and becomes a `:note` Inline carrying that
  FootnoteBlock's own Blocks (`convert-blocks`, since a FootnoteBlock is
  just another block-collecting container, exactly like BlockQuote) at
  the marker's own position (AC #1). An unresolved label
  (`.getReferenceNode` returns nil, i.e. no matching definition exists at
  all, or not within this same chunk's own flexmark Document -- see
  `cm-parser`'s docstring) throws `ex-info` (`:type ::undefined-footnote`)
  instead: a deliberate divergence from this same function's undefined-
  LinkRef/ImageRef literal-text fallback (sec6.3/6.4) immediately below,
  since AC #2 explicitly asks for a parse diagnostic here rather than a
  silent fallback.

  A *resolved* label already present in `active-footnotes` (the labels of
  every FootnoteBlock currently being expanded on the current call stack
  -- see this namespace's own docstring) means expanding this marker would
  recurse into a definition still being expanded higher up this same call
  stack -- a self-referential (`[^self]` inside `[^self]:`'s own body) or
  mutually-cyclical (`[^ca]` inside `[^cb]:`'s body, `[^cb]` inside
  `[^ca]:`'s) footnote definition, which would otherwise recurse forever
  and crash with an uncaught `StackOverflowError` (review finding against
  this task) rather than any catchable diagnostic. Throws `ex-info`
  (`:type ::cyclic-footnote`) instead, the same no-silent-crash treatment
  as the undefined-footnote case immediately above; only once a label is
  confirmed *not* already active does its definition actually get expanded
  (`convert-blocks`), with that label added to the set threaded into that
  one recursive call so a marker anywhere inside its own body -- at any
  depth, in any block-collecting container -- can see it."
  [^Document document active-footnotes ^Node node]
  (cond
    (instance? Text node)
    (let [raw (.toString (.getChars node))]
      (check-no-attribute-attempt! raw)
      (text->inlines (unescape-backslashes raw)))
    (instance? TextBase node) (convert-children document active-footnotes (.getChildren node))
    (instance? SoftLineBreak node) [{:t :soft-break}]
    (instance? HardLineBreak node) [{:t :line-break}]
    (math-inline-node? node)
    [{:t :math-inline :tex (.toString (.getText ^DelimitedNodeImpl node))}]
    (cross-ref-node? node)
    [{:t :cross-ref :label (.toString (.getText ^DelimitedNodeImpl node))}]
    (cite-author-node? node)
    [{:t :cite :items [{:key (.toString (.getText ^DelimitedNodeImpl node)) :mode :author}]}]
    (cite-bracket-node? node) [(cite-bracket-node->cite node)]
    (instance? Footnote node)
    (let [label (.toString (.getText ^Footnote node))
          footnote-block (.getReferenceNode ^Footnote node document)]
      (cond
        (nil? footnote-block)
        (throw (ex-info "footnote marker has no matching definition"
                        {:type ::undefined-footnote :label label}))

        (contains? active-footnotes label)
        (throw (ex-info "footnote definition cycles back to itself"
                        {:type ::cyclic-footnote :label label}))

        :else
        [{:t :note :blocks (convert-blocks document (conj active-footnotes label) footnote-block)}]))
    (instance? Code node) [{:t :code :text (.toString (.getText ^Code node))}]
    (instance? StrongEmphasis node) [{:t :strong :inlines (convert-inlines document active-footnotes node)}]
    (instance? Emphasis node) [{:t :emph :inlines (convert-inlines document active-footnotes node)}]
    (instance? Image node)
    ;; TASK-69: the alt text is CONVERTED, not flattened by flexmark's
    ;; own TextCollectingVisitor -- see `alt-text` for what that costs
    ;; and buys, and
    ;; `figure-caption` for where the same Inlines keep their markup.
    (let [alt-inlines (convert-inlines document active-footnotes node)
          src (unescape-backslashes (.toString (.getUrl ^Image node)))]
      (note-in-alt! src alt-inlines)
      [{:t :image
        :src src
        :alt (alt-text alt-inlines)
        :attr default-attr}])
    (instance? Link node) [{:t :link
                            :target (unescape-backslashes (.toString (.getUrl ^Link node)))
                            :inlines (convert-inlines document active-footnotes node)
                            :attr default-attr}]
    ;; Both of CommonMark's autolink spellings (sec6.5). flexmark's core
    ;; parser already
    ;; produces these two node types and this parser simply had no branch
    ;; for either, so a bare URL in angle brackets -- how a URL is
    ;; normally written in prose -- took the documented
    ;; unsupported-construct path and stopped the build (TASK-72).
    (instance? AutoLink node) (autolink node "")
    (instance? MailLink node) (autolink node "mailto:")
    (instance? ImageRef node)
    (if-let [reference (resolve-reference document node)]
      (let [alt-inlines (convert-inlines document active-footnotes node)
            src (unescape-backslashes (.toString (.getUrl reference)))]
        (note-in-alt! src alt-inlines)
        [{:t :image
          :src src
          :alt (alt-text alt-inlines)
          :attr default-attr}])
      (literal-ref-text node))
    (instance? LinkRef node)
    (if-let [reference (resolve-reference document node)]
      [{:t :link
        :target (unescape-backslashes (.toString (.getUrl reference)))
        :inlines (convert-inlines document active-footnotes node)
        :attr default-attr}]
      (literal-ref-text node))
    :else (throw (ex-info "unsupported inline construct"
                          {:type ::unsupported-node
                           :class (.getName (class node))
                           :text (.toString (.getChars node))}))))

;; --- Block conversion --------------------------------------------------------

(declare convert-blocks)

(defn- code-block
  "A `:code-block` Block from `node` (a Fenced- or IndentedCodeBlock), whose
  literal text is read via `getContentChars` -- unlike the raw `getChars`
  span, this already has each line's block-level indent stripped, which
  matters for IndentedCodeBlock (no Text children to fall back on) and is
  correct for FencedCodeBlock too. Hinted `^Block` (the nearest common
  ancestor of Fenced- and IndentedCodeBlock that declares
  `getContentChars`, since a single param can't carry both concrete
  types) so this resolves without reflection on every code block
  converted."
  [^Block node lang]
  (cond-> {:t :code-block :text (.toString (.getContentChars node)) :attr default-attr}
    lang (assoc :lang lang)))

(defn- fenced-lang
  "The language tag of a fenced code block's info string (its first
  whitespace-delimited word), or nil if the info string is blank.

  Throws `ex-info` (`:type ::unsupported-node`) if the info string looks
  like an attempted attribute-group attachment on the fence's opening
  line (`{#id .class key=val}`, sec5.3 -- reusing `attr-attempt-re`'s
  shape check) -- e.g. `` ```clojure {.numberLines} `` or a bare
  `` ```{.numberLines} `` with no language at all. TASK-5 deliberately
  does not implement fenced-code-block attribute attachment (see the
  task's implementation notes/comments), and a review finding confirmed
  that, unlike headings/images/links/code spans, flexmark's attributes
  extension never actually splits such a group out of the info string
  into its own `AttributesNode` either way (`.getInfo` returns the
  literal whole string, extension-untouched, verified via javap/REPL) --
  so left unhandled, this previously either silently dropped the
  attempted group with no diagnostic at all (the `clojure {.numberLines}`
  case, which kept only the correct `\"clojure\"` :lang) or silently
  corrupted `:lang` into the literal string `\"{.numberLines}\"` (the
  no-language case). Both are exactly the silent-drop/silent-corruption
  AC #4 exists to prevent, applied to this node type too, even though
  full attachment support here remains out of scope."
  [^FencedCodeBlock node]
  (let [info (str/trim (.toString (.getInfo node)))]
    (when-not (str/blank? info)
      (when (re-find attr-attempt-re info)
        (throw (ex-info "fenced code block attribute attachment is not supported"
                        {:type ::unsupported-node
                         :class (.getName (class node))
                         :text info})))
      (first (str/split info #"\s+")))))

(defn- list-item-blocks
  "The Blocks contained in one ListItem, with any headings inside it
  section-folded the same way as any other block container."
  [^Document document active-footnotes ^ListItem item]
  (convert-blocks document active-footnotes item))

(defn- pop-trailing-attr-group
  "`[attr children]` for any `node` (a Heading, TASK-5 AC #1, or -- TASK-8
  -- the mini-document Paragraph produced by `table-caption-and-attr` for a
  table's caption line): if its *last* child is an AttributesNode (sec5.3,
  e.g. `## Title {#id .class}`), pops it off and returns its parsed Attr
  alongside the remaining children (the heading's actual text, or the
  caption's own text); otherwise `default-attr` and every child unchanged.
  An AttributesNode anywhere *other* than the last position (flexmark
  permits a group mid-heading, e.g. `## H {#a} more`) is left in place for
  `convert-children`'s own default handling rather than specially
  recognized here -- only a group trailing the whole node is popped, and
  this parser does not (yet) merge multiple attribute groups on one node.
  Only ever calls `.getChildren` (declared on `Node` itself), so any
  concrete flexmark node class works, not only Heading."
  [^Node node]
  (let [children (vec (.getChildren node))]
    (if (and (seq children) (instance? AttributesNode (peek children)))
      [(parse-attr-group (peek children)) (pop children)]
      [default-attr children])))

;; --- Figures and pipe tables (TASK-8, sec5.8/5.9) ---------------------------
;;
;; A standalone image with an id becomes a numbered Figure (sec5.8); see
;; `convert-block`'s Paragraph case, which only needs to inspect an already-
;; converted Paragraph's Inlines -- TASK-5's attribute-attachment machinery
;; already merges an image's trailing `{#id ...}` group directly into its
;; own `:attr`, so no new flexmark-level recognition is needed here at all.
;;
;; A pipe table (sec5.9) is recognized by flexmark's own `TablesExtension`
;; (registered on `cm-parser`) into TableBlock/TableHead/TableBody/TableRow/
;; TableCell nodes; `convert-table` walks those into this project's Row/
;; Cell/Col. Its own optional `: caption text {#id ...}` trailing-caption-
;; line, however, is NOT something that extension recognizes (see this
;; namespace's own docstring for why `WITH_CAPTION` doesn't apply here) --
;; flexmark parses that line as an ordinary trailing Paragraph, sitting as
;; the TableBlock's very next sibling. `table-caption-paragraph?`/
;; `table-caption-and-attr` detect and convert that shape; `block-items` is
;; where a qualifying pair is merged into one `:table` Block (see its own
;; docstring).

(def ^:private table-caption-re
  "The sec5.9 table-caption-line marker: up to 3 leading spaces (mirroring
  this file's other leaf-block indentation tolerances), a `:`, then at
  least one space/tab before the caption text itself."
  #"^ {0,3}:[ \t]+")

(defn- table-caption-paragraph?
  "True if `candidate` is a Paragraph that both (a) immediately follows
  `table` with no blank line in between -- checked via
  `getEndLineNumber`/`getStartLineNumber` rather than any node shape, since
  a blank line between two block siblings leaves no node of its own in
  flexmark's tree at all -- and (b) whose *content* text (`.getContentChars`
  -- like `code-block`'s own use of it, this strips each line's block-level
  marker, e.g. a blockquote's leading `> `, that raw `.getChars` would
  otherwise still carry, confirmed via REPL that a caption line inside a
  blockquote is otherwise misdetected) starts with `table-caption-re`.
  `candidate` may be nil (there is no next sibling)."
  [^Node table candidate]
  (and (instance? Paragraph candidate)
       (= (inc (.getEndLineNumber table)) (.getStartLineNumber ^Node candidate))
       (boolean (re-find table-caption-re (.toString (.getContentChars ^Paragraph candidate))))))

(defn- table-caption-and-attr
  "`[caption attr]` for a qualifying caption Paragraph `para` (see
  `table-caption-paragraph?`): strips the leading `table-caption-re` marker
  from `para`'s own *content* text (`.getContentChars`, already free of any
  enclosing container's line marker -- see `table-caption-paragraph?`) and
  re-parses the remainder as its own, independent mini-document via
  `cm-parser` -- mirroring this file's established per-chunk-independence
  pattern (e.g. `math-chunk-items`) -- rather than surgically editing
  flexmark's own Text/TextBase nodes to strip the marker in place. This
  sidesteps needing to rely on exactly when flexmark wraps a plain-text run
  in `TextBase` ahead of a successfully-parsed AttributesNode, confirmed
  via REPL to be data-dependent (see `cm-parser`'s docstring) --
  `convert-inline`'s `TextBase` branch already sees through it
  transparently either way, once the caption's own mini-parse produces it.
  A documented consequence shared with every other per-chunk mini-parse in
  this file: a reference-style link inside a caption only resolves against
  `[ref]: url` definitions within the caption's own single line, not the
  whole document."
  [^Paragraph para]
  (let [stripped (str/replace-first (.toString (.getContentChars para)) table-caption-re "")
        mini-doc (.parse cm-parser stripped)
        mini-para (first (.getChildren mini-doc))]
    (if mini-para
      (let [[attr children] (pop-trailing-attr-group mini-para)]
        [(convert-children mini-doc #{} children) attr])
      [[] default-attr])))

(defn- table-cell-align
  "`(.getAlignment cell)` (`TableCell$Alignment/LEFT`/`CENTER`/`RIGHT`, or
  null for a column with no explicit alignment, e.g. a plain `---`
  separator) as this project's `:left`/`:center`/`:right`, or nil."
  [^TableCell cell]
  (condp identical? (.getAlignment cell)
    TableCell$Alignment/LEFT :left
    TableCell$Alignment/CENTER :center
    TableCell$Alignment/RIGHT :right
    nil))

(defn- head-cell->col
  "One `haselnuss.ast` Col (sec4.3) for a head `TableCell`: `:align` if the
  column has one (see `table-cell-align`), and no `:width` -- a GFM pipe
  table's delimiter row carries no column-width data at all. A width
  comes from the caption line's own attribute group instead, see
  `column-widths`."
  [^TableCell cell]
  (if-let [align (table-cell-align cell)] {:align align} {}))

(def ^:private column-width-re
  "A single column width: a number and one of the units both targets
  understand (TASK-74).

  The units are the intersection deliberately, not a union: a `:width`
  reaches LaTeX as the argument of a `p{}` column and HTML as a CSS
  `width`, so `px` (which LaTeX has no notion of) and `\\linewidth`
  (which CSS has none of) would each set one target and silently do
  nothing in the other -- the disagreement this project exists to
  prevent. A percentage is the portable way to say the same thing and is
  what the syntax is for."
  ;; Case-insensitive: `2CM` is a length to both LaTeX and CSS, and
  ;; refusing it would be this parser inventing a restriction neither
  ;; target has (found by review).
  #"(?i)(\d+(?:\.\d+)?)(%|cm|mm|in|pt|pc|em|ex)")

(defn- column-widths
  "The per-column `:width` strings for a table whose caption line carried
  a `widths=\"...\"` attribute (TASK-74), or nil when it carried none --
  `columns` is how many columns the table actually has, used to check the
  two agree.

  Whitespace-separated, one entry per column, all or nothing. That is
  the answer to \"what happens to a column with no width in a table where
  others have one\": it cannot be written. A partial list would have to
  invent a rule for the remainder -- share what is left, or leave those
  columns natural and let LaTeX mix `p{}` and `l` columns, which sets
  their baselines differently -- and neither is something a reader could
  guess from the source. A list that lines up with the columns is.

  Refused, naming the table, rather than silently applied (AC #4):
  - a count that disagrees with the number of columns, which is the
    mistake an edited table makes;
  - a value that is not a number and one of `column-width-re`'s units;
  - percentages summing to more than 100%, which is the mistake this
    feature exists to fix, arrived at from the other direction. Only
    checked when EVERY entry is a percentage -- `20% 3cm` cannot be
    added up, and refusing to carry a table because it mixes units
    would be inventing a restriction rather than catching an error."
  [attr columns]
  (when-let [raw (not-empty (str/trim (str (get-in attr [:props "widths"]))))]
    (let [id (:id attr)
          entries (str/split raw #"\s+")
          named (if id (str "table " (pr-str id)) "table")]
      (when-not (= columns (count entries))
        (throw (ex-info
                (str named " has " columns " column(s) but its widths= names "
                     (count entries) ": write one width per column, or none at all")
                {:type ::column-width-mismatch :id id :text raw})))
      (doseq [entry entries]
        (when-not (re-matches column-width-re entry)
          (throw (ex-info
                  (str named " has an unusable column width " (pr-str entry)
                       ": a width is a number and one of % cm mm in pt pc em ex")
                  {:type ::invalid-column-width :id id :text entry}))))
      ;; Summed as exact decimals rather than as doubles: `33.33 33.33
      ;; 33.34` is a table an author would really write, and a check that
      ;; refused it for a floating-point crumb would be a worse bug than
      ;; the one it exists to catch.
      (let [percentages (keep (fn [entry]
                                (let [[_ n unit] (re-matches column-width-re entry)]
                                  (when (= "%" unit) (bigdec n))))
                              entries)
            total (reduce + 0M percentages)]
        (when (and (= (count percentages) (count entries))
                   (pos? (compare total 100M)))
          (throw (ex-info
                  (str named " has column widths summing to " total
                       "%, which is wider than the text block it has to fit")
                  {:type ::column-widths-too-wide :id id :text raw}))))
      (vec entries))))

(defn- table-cell->cell
  "One `haselnuss.ast` Cell (sec4.3) for a flexmark `TableCell`: its own
  inline content wrapped in a single `:para` Block, since `Cell.blocks` is
  `Block[]` generically (sec4.3's own Notes: a cell can, in principle, hold
  any Block) even though a pipe-table cell's syntax only ever produces
  inline content. `:align` mirrors the column's own (see
  `table-cell-align`); `:span` is omitted when 1, the non-spanning default
  every cell gets when `TablesExtension/COLUMN_SPANS` is left disabled (as
  here -- GFM pipe tables have no column-span syntax of their own)."
  [^Document document active-footnotes ^TableCell cell]
  (cond-> {:blocks [{:t :para :inlines (convert-inlines document active-footnotes cell)}]}
    (table-cell-align cell) (assoc :align (table-cell-align cell))
    (not= 1 (.getSpan cell)) (assoc :span (.getSpan cell))))

(defn- table-row->row
  "One `haselnuss.ast` Row (sec4.3) for a flexmark `TableRow` (used for the
  head row and every body row alike)."
  [^Document document active-footnotes ^Node row]
  {:cells (mapv #(table-cell->cell document active-footnotes %) (.getChildren row))})

(defn- convert-table
  "The `:table` Block (sec4.3) for a flexmark `TableBlock` `node`, with
  `caption`/`attr` already resolved by the caller (either from a qualifying
  caption paragraph, see `table-caption-and-attr`, or `[]`/`default-attr`
  when there is none, AC #3): `:head` from the TableBlock's single
  TableHead child's own single TableRow; `:rows` from its TableBody child's
  TableRows (`[]` if there is no TableBody at all, i.e. a table with no
  body rows); `:colspec` from the head row's own cells (one Col per
  column, see `head-cell->col`)."
  [^Document document active-footnotes ^TableBlock node caption attr]
  (let [children (vec (.getChildren node))
        head-node (first (filter #(instance? TableHead %) children))
        body-node (first (filter #(instance? TableBody %) children))
        head-row (first (.getChildren ^Node head-node))
        body-rows (if body-node
                    (mapv #(table-row->row document active-footnotes %) (.getChildren ^Node body-node))
                    [])
        colspec (mapv head-cell->col (.getChildren ^Node head-row))
        widths (column-widths attr (count colspec))]
    {:t :table
     :head (table-row->row document active-footnotes head-row)
     :rows body-rows
     :caption caption
     :colspec (if widths
                (mapv (fn [col width] (assoc col :width width)) colspec widths)
                colspec)
     ;; The prop is consumed here and must not also ride along on the
     ;; Table's own Attr: `haselnuss.emit.html` renders a prop as a
     ;; literal HTML attribute, and there is no `widths` attribute --
     ;; the same reason TASK-65 keeps an image's sizing props off the
     ;; enclosing <figure>.
     ;; Dissoc'd on the PROP's presence rather than on the parsed
     ;; result: `widths=""` parses to nothing (blank is absent) but is
     ;; still a prop, and left on the Attr it reached the HTML as a
     ;; literal `<table widths="">` -- the very leak this exists to
     ;; prevent (found by review).
     :attr (update attr :props dissoc "widths")}))

(defn- standalone-figure-image
  "The lone `:image` Inline of `inlines` if it qualifies as sec5.8's
  standalone-image-with-an-id Figure (AC #1) -- `inlines` is *exactly* one
  Inline, that Inline is an `:image`, and its own `:attr` (already merged
  directly from a trailing `{#id ...}` attribute group by TASK-5's
  `convert-children`) has an `:id` -- nil otherwise, including for a
  standalone image with no id at all (AC #3: it stays a plain `:para`/
  `:image`, not a numbering target)."
  [inlines]
  (when (and (= 1 (count inlines))
             (= :image (:t (first inlines)))
             (:id (:attr (first inlines))))
    (first inlines)))

(defn- figure-caption
  "The caption Inlines for the Figure a standalone id-bearing image
  becomes (see `standalone-figure-image`): the flexmark Image/ImageRef
  node's own children run through `convert-inlines`, so an alt text
  carrying emphasis, strong, inline math, a citation or a cross-reference
  reaches the caption as those Inlines rather than as flattened text
  (TASK-69).

  `paragraph` is the flexmark Paragraph, not the converted Inlines,
  because the conversion is where the content was lost: the AST's Image
  carries `:alt` as a plain `:string` (sec4.4, and an HTML `alt=`
  attribute is character data by definition), so the caption used to be
  `text->inlines` over that string. Flattening cost emphasis and strong
  their meaning but kept their words; it cost inline math and citations
  everything, since flexmark's own TextCollectingVisitor collects no
  text at all for this parser's own synthetic math and citation nodes --
  a caption authored with three constructs in it emitted two stray
  \"and\"s and nothing else.

  The image's own `:alt` is the same content flattened to the plain
  string sec4.4 requires (`alt-text`): it is the `alt=` attribute, an
  alternative *for* the image, while this is the caption printed beside
  it. Nothing is dropped on either side.

  The image's children are therefore converted twice for a promoted
  figure -- once by `convert-inline` for the alt string, once here for
  the caption. The AST's Image has nowhere to keep Inlines between the
  two passes (sec4.4 gives it `alt: string`), so a pure re-conversion of
  a handful of nodes is the price of not inventing a field the spec does
  not have.

  `image` is the already-converted Image Inline, used only for the
  fallback: a Paragraph that converts to a lone Image always holds that
  Image as a direct child (an `ImageRef` cannot reach here, since a
  promoted figure needs an id and only an `Image` takes an attribute
  group -- see `convert-children`), and if that ever stops being true
  the caption is the flattened alt text rather than nothing, which sec4.3
  requires to be a vector."
  [^Document document active-footnotes ^Node paragraph image]
  (if-let [image-node (first (filter #(instance? Image %) (.getChildren paragraph)))]
    (convert-inlines document active-footnotes image-node)
    (text->inlines (:alt image))))

(declare math-paragraph-block)

(defn- convert-block
  "Converts one flexmark block-level `node` (never a Heading -- those are
  intercepted by `convert-blocks` for section folding) to a
  `haselnuss.ast` Block. `document` is threaded through to `convert-inlines`
  for LinkRef/ImageRef resolution (see `convert-inline`). Throws `ex-info`
  (`:type ::unsupported-node`) for any node class this parser does not yet
  handle.

  A Paragraph whose converted Inlines are exactly a single, id-bearing
  Image (see `standalone-figure-image`) becomes a `:figure` Block instead
  of an ordinary `:para` (sec5.8 AC #1): `:content` is a `:para` Block
  holding that same Image (sec4.3 requires Figure's content to be a Block,
  and Image is an Inline, so a wrapping paragraph is how a bare image
  becomes valid Figure content -- the same shape sec5.8 itself describes
  for a non-image figure, \"a figure directive wrapping the content\"),
  `:caption` is the image's own alt text as real Inlines (see
  `figure-caption`, which converts it rather than flattening it), and
  `:attr` is the image's own already-parsed Attr (TASK-5).

  The authored `:id` *moves* to the Figure rather than being copied onto
  it, and so do the `:classes`; the `:props` stay on the Image alone.
  Every part of that attribute group was written on the image, and only
  the parts that are about the FIGURE belong on the node this
  synthesizes: the id, because the Figure is the numbering target and
  the anchor, and the classes, because sec4.1 gives them the job of
  selecting behaviour and a figure-level class is what a stylesheet
  reaches for. A prop is presentational and belongs to the image it was
  written on -- and copying it onto both put a `scale` and a `height`
  prop on the emitted `<figure>` as literal HTML attributes, which is
  exactly what `haselnuss.emit.html/image-attr` moves them out of
  `:props` to prevent (`scale` is not an HTML attribute at all, and a
  length like `3cm` is an invalid value for HTML's own `height`). Found by TASK-61's
  thesis fixture, the first document to convert a sized, id-bearing
  figure end to end (TASK-65). Copying it left
  two nodes wearing one id, which
  `haselnuss.resolver/structural-diagnostics` correctly reported as a
  duplicate id -- so every figure in every document produced a spurious
  build warning (found while reviewing TASK-36; confirmed by parsing
  `![A tree](tree.png){#fig:tree}` and running `resolve-document`).
  The Figure is the right owner: it is the numbering target and the
  cross-reference anchor, and sec4.1's ids are document-unique. The
  Image keeps its `:classes`/`:props`, which are presentational and
  meaningful on the image element itself."
  [^Document document active-footnotes ^Node node]
  (cond
    ;; Consulted before the inlines are converted, not after: a display-math
    ;; body is raw TeX, and running `\begin{cases}` through inline
    ;; conversion reads the `{cases}` as an attribute group and fails the
    ;; build (TASK-70).
    (some? (math-paragraph-block node)) (math-paragraph-block node)

    (instance? Paragraph node)
    (let [inlines (convert-inlines document active-footnotes node)]
      (if-let [image (standalone-figure-image inlines)]
        {:t :figure
         :content {:t :para :inlines [(update image :attr dissoc :id)]}
         :caption (figure-caption document active-footnotes node image)
         :attr (assoc (:attr image) :props {})}
        {:t :para :inlines inlines}))
    (instance? ThematicBreak node) {:t :thematic-break}
    (instance? BlockQuote node) {:t :block-quote :blocks (convert-blocks document active-footnotes node)}
    (instance? FencedCodeBlock node) (code-block node (fenced-lang node))
    (instance? IndentedCodeBlock node) (code-block node nil)
    (instance? ListBlock node) {:t :list
                                :ordered (instance? OrderedList node)
                                :tight (.isTight ^ListBlock node)
                                :items (mapv #(list-item-blocks document active-footnotes %) (.getChildren node))
                                :attr default-attr}
    :else (throw (ex-info "unsupported block construct"
                          {:type ::unsupported-node
                           :class (.getName (class node))
                           :text (.toString (.getChars node))}))))

(defn- block-items
  "The flat sequence of completed Blocks and `heading-marker`s for `node`'s
  children (AC #2 of TASK-4), *before* `fold-sections` folds any heading
  into a nested `:section` -- factored out of `convert-blocks` (which is
  just this plus `fold-sections`) so TASK-6's `segment-items` can merge the
  items from several independently-parsed chunks (a directive's content
  interspersed with the plain text around it) into one flat sequence and
  fold sections over the *merged* result exactly once, rather than once per
  chunk (which would incorrectly treat a directive as ending every
  currently open section instead of nesting inside one, when it in fact
  sits at the same level as the surrounding prose).

  A `Reference` child (a `[ref]: url` link reference definition --
  CommonMark sec4.7, not this project's own SPEC.md) is dropped rather
  than converted: flexmark keeps it in the tree (as a
  sibling of the blocks around it, wherever the definition line appears)
  purely so `resolve-reference` can look it up, but per CommonMark it
  contributes no visible content of its own (confirmed against flexmark's
  own HtmlRenderer, which renders nothing for the definition line). A
  `FootnoteBlock` child (TASK-10, sec5.12's `[^label]: ...` footnote
  definition) is dropped the same way and for the same reason: it too is
  kept in the tree purely so `convert-inline`'s `Footnote` branch can look
  it up via `.getReferenceNode`, and contributes no content of its own at
  its own source position -- its Blocks appear only inside the `:note`
  Inline at its marker's position instead.

  TASK-8 AC #2/#3: a `TableBlock` immediately followed by a qualifying
  caption paragraph (see `table-caption-paragraph?`) is looked up one
  sibling ahead and both are consumed together into one `:table` Block
  (`convert-table`, with the caption paragraph's own caption/attr, see
  `table-caption-and-attr`); a `TableBlock` with no such following sibling
  converts on its own, with an empty caption and `default-attr` (AC #3:
  still a `:table` node, just not an id-bearing numbering target).

  `active-footnotes` is threaded straight through to every Block/Inline
  conversion below purely so a `Footnote` reachable from any depth can
  detect a resolution cycle -- see `convert-inline`'s `Footnote` branch
  and this namespace's own docstring."
  [^Document document active-footnotes ^Node node]
  (let [children (vec (remove #(or (instance? Reference %) (instance? FootnoteBlock %))
                              (.getChildren node)))
        n (count children)]
    (loop [i 0 acc []]
      (if (>= i n)
        acc
        (let [child (nth children i)
              nxt (when (< (inc i) n) (nth children (inc i)))]
          (cond
            (instance? Heading child)
            (let [[attr heading-children] (pop-trailing-attr-group child)]
              (recur (inc i)
                     (conj acc (heading-marker (.getLevel ^Heading child) attr
                                               (convert-children document active-footnotes heading-children)))))

            (and (instance? TableBlock child) (table-caption-paragraph? child nxt))
            (let [[caption attr] (table-caption-and-attr nxt)]
              (recur (+ i 2) (conj acc (convert-table document active-footnotes child caption attr))))

            (instance? TableBlock child)
            (recur (inc i) (conj acc (convert-table document active-footnotes child [] default-attr)))

            :else
            (recur (inc i) (conj acc (convert-block document active-footnotes child)))))))))

(defn- convert-blocks
  "Converts every child of `node` to top-level Blocks, folding ATX/setext
  headings into nested `:section` Blocks by level (AC #2 of TASK-4). Applied
  uniformly to any block-collecting container (the document itself,
  blockquotes, list items), not only the document's own top level.
  `document` is the enclosing flexmark Document (threaded down to
  `convert-inline` for LinkRef/ImageRef resolution); at the top level it is
  `node` itself. `active-footnotes` (TASK-10 cycle guard, see this
  namespace's own docstring) is threaded down the same way; at any true
  top level (not a recursive Footnote-body expansion) it is `#{}`. See
  `block-items` for the pre-fold-sections step this wraps."
  [^Document document active-footnotes ^Node node]
  (fold-sections (block-items document active-footnotes node)))

;; --- Block directives (TASK-6, sec5.5) --------------------------------------
;;
;; A directive fence -- 3+ colons, an optional `{name #id .class key=val}`
;; header, nested content, and a closing fence of at least as many colons as
;; its own opening one -- is recognized by hand over the document body's raw
;; lines, *before* any of it reaches flexmark (see this namespace's own
;; docstring for why: no flexmark extension exists for this syntax, and a
;; correct custom `BlockParserFactory` is a much larger investment than this
;; hand-rolled scan). `parse-directive-region` turns the raw lines into a
;; tree of :text/:directive segments; `segments->blocks` (used from `parse`)
;; converts that tree into Blocks, reusing `block-items`/`fold-sections` so
;; heading nesting still spans correctly across a directive sitting among
;; plain paragraphs at the same level.
;;
;; Because this scan runs over raw lines before flexmark ever sees them, it
;; must independently recognize backtick/tilde fenced code blocks (CommonMark
;; sec4.5) and skip colon-run lines that fall *inside* one -- otherwise a
;; plain code block whose own content happens to contain a line of 3+ colons
;; (SPEC.md sec5.5 itself shows directive syntax presented as a worked
;; example inside a fenced code block) gets misread as a real directive
;; fence (TASK-6 review). `annotate-code-fences` does this in one forward
;; pass, independent of and prior to `parse-directive-region`'s own colon-run
;; scan.

(def ^:private code-fence-open-re
  "A backtick/tilde code fence's *opening* line (CommonMark sec4.5): up to 3
  leading spaces, then a run of 3+ of the same fence character. Only the
  captured run's character and length are used (to recognize this fence's
  own later closing line) -- the rest of the line (an optional info string)
  is not otherwise needed here."
  #"^ {0,3}(`{3,}|~{3,}).*$")

(defn- code-fence-close-re
  "A compiled regex matching a line that closes a code fence previously
  opened with fence character `fence-char` and run length `fence-len`: up to
  3 leading spaces, then a run of *at least* `fence-len` of that same
  character, then nothing but trailing whitespace (CommonMark sec4.5: unlike
  the opening line, a closing fence may not carry an info string -- any
  other trailing content just makes this an ordinary content line of the
  fence, not its close). Compiled once when a fence opens (see
  `code-fence-open`) rather than once per content line, since a code block
  can run to many lines."
  [fence-char fence-len]
  (re-pattern (str "^ {0,3}" (java.util.regex.Pattern/quote (str fence-char)) "{" fence-len ",}\\s*$")))

(defn- code-fence-open
  "The compiled closing-fence regex (see `code-fence-close-re`) if `line`
  opens a backtick/tilde code fence (see `code-fence-open-re`), nil
  otherwise."
  [line]
  (when-let [[_ run] (re-matches code-fence-open-re line)]
    (code-fence-close-re (first run) (count run))))

(defn- annotate-code-fences
  "`lines` (the whole document body's raw lines) paired one-for-one with a
  boolean, true iff that line falls inside an *open* backtick/tilde fenced
  code block (CommonMark sec4.5) -- strictly after its opening fence line,
  through and including its closing fence line, or through end-of-input if
  the fence is never closed. Computed once, in a single forward pass wholly
  independent of colon-run directive-fence detection, so
  `parse-directive-region`'s own scan can consult the flag on each line and
  skip a colon run that is really code-block content rather than a genuine
  directive fence."
  [lines]
  (loop [i 0 open nil acc []]
    (if (>= i (count lines))
      acc
      (let [line (nth lines i)]
        (if open
          (recur (inc i) (when-not (re-matches open line) open) (conj acc [line true]))
          (recur (inc i) (code-fence-open line) (conj acc [line false])))))))

(def ^:private directive-fence-re
  "A directive fence line (sec5.5): up to 3 leading spaces (mirroring
  CommonMark's own leaf-block indentation tolerance for e.g. a thematic
  break), then a run of 3+ colons, then the rest of the line -- its header
  (`{name #id .class key=val}`, all optional) if this is an opening fence,
  or nothing but trailing whitespace if this is a closing one."
  #"^ {0,3}(:{3,})(.*)$")

(defn- fence-line
  "`[colon-count trimmed-header]` if `line` matches `directive-fence-re`,
  nil otherwise."
  [line]
  (when-let [[_ colons header] (re-matches directive-fence-re line)]
    [(count colons) (str/trim header)]))

;; --- Quoted attribute values (TASK-39) -------------------------------------
;;
;; A value containing a space needs quoting, and the syntax is `key="value"` or
;; `key='value'` -- chosen because flexmark's own `AttributesExtension` already
;; implements exactly that for an ordinary `{#id key=val}` group, which it owns
;; the tokenizer for (`attribute-token` only sees tokens flexmark has already
;; split and unquoted). Inventing a different spelling for the two groups this
;; file tokenizes itself would have given the same document two attribute
;; grammars depending on where the attribute was written.
;;
;; ESCAPING a quote means switching the delimiter: `'a "quoted" word'` and
;; `"it's"` both work, on both paths. There is deliberately no backslash
;; escape, even though pandoc has one, because flexmark rejects
;; `key="a \"b\" c"` outright and supporting it here would have made the
;; directive header accept syntax an ordinary group refuses. The cost,
;; recorded rather than papered over: a value containing BOTH a single and a
;; double quote is not expressible anywhere.
;;
;; The two tokenizers agree on the quoting syntax itself; they were never
;; identical grammars and still are not. Known divergences, each verified by
;; probing both paths rather than assumed:
;;
;;   - `key= "a b"` (space between `=` and the quote) is accepted by flexmark
;;     and rejected here. Closing it would mean deciding what the existing
;;     `{key= val}` -- today two tokens -- becomes, which is a change to
;;     unquoted parsing that this task has no reason to make.
;;   - `{#sec:it's}` parses here (a quote can only open a run after a `key=`,
;;     so an apostrophe in an id is an ordinary character) and is rejected by
;;     flexmark. That predates quoting: flexmark rejected it before too.
;;   - A `}` inside a quoted value works on both of these paths (a directive
;;     header is matched with a greedy `\{(.*)\}`), but NOT in a display-math
;;     group, whose `math-single-line-re` ends the group at the first `}` and
;;     so does not recognise it as an attribute group at all. That is
;;     `[^}]*`'s doing and predates quoting.

(def ^:private attr-value-start-re
  "A token that is a complete `key=` and nothing more -- the one position
  where a quote opens a quoted value. Anchored (used with `re-matches`):
  with an unanchored search `#thm:a=` would match its own tail and let
  `{theorem #thm:a=\"x y\"}` parse to the id `thm:a=x y`, which is not a
  form the grammar has (found by review). Deliberately the same key
  production `directive-header-token` uses for `key=val`."
  #"[A-Za-z_][\w-]*=")

(defn- split-attr-tokens
  "The inside of a `{...}` group as a vector of whitespace-separated
  tokens, with a quoted value kept as one token and its quotes removed.

  A quote character only opens a quoted run where a *value* can start --
  immediately after a `key=` (see `attr-value-start-re`) -- so an
  apostrophe anywhere else stays an ordinary character and `{#sec:it's}`
  tokenizes exactly as it did before quoting existed. That mirrors
  flexmark's grammar, where quoting is a property of the value
  production and not of the token as a whole.

  Two shapes throw `ex-info` with `error-type` (its caller's own
  `::malformed-directive` or `::malformed-attributes`), carrying `text`,
  rather than being quietly accepted -- both would otherwise turn a typo
  into a plausible-looking wrong document (AC #4), and flexmark rejects
  both on its own path:

  - an unterminated quote, which would swallow the rest of the group;
  - anything but whitespace after a closing quote, so `key=\"a\"x` is an
    error rather than the value `ax`."
  [inner error-type text]
  (loop [[c & more] (seq inner), token "", tokens [], quote-ch nil, closed? false]
    (cond
      (nil? c)
      (if quote-ch
        (throw (ex-info (str "unterminated " quote-ch "-quoted attribute value")
                        {:type error-type :text text}))
        (cond-> tokens (seq token) (conj token)))

      quote-ch
      (if (= c quote-ch)
        (recur more token tokens nil true)
        (recur more (str token c) tokens quote-ch false))

      (and closed? (not (Character/isWhitespace ^char c)))
      (throw (ex-info (str "unexpected " c " after a closing quote in an attribute value")
                      {:type error-type :text text}))

      (and (contains? #{\" \'} c) (re-matches attr-value-start-re token))
      (recur more token tokens c false)

      (Character/isWhitespace ^char c)
      (recur more "" (cond-> tokens (seq token) (conj token)) nil false)

      :else (recur more (str token c) tokens nil false))))

(defn- directive-header-token
  "One token inside a directive's `{...}` header group (sec5.5) as
  `[:name name]` / `[:id id]` / `[:class class]` / `[:prop key value]` --
  the same three forms as `attribute-token` (`#id`, `.class`, `key=val`)
  plus a bare identifier, which is exactly the form `attribute-token`
  rejects as malformed for an ordinary attribute group but which sec5.5
  dedicates to a directive's `name` (e.g. `theorem` in
  `{theorem #thm:main}`) -- the mechanism by which a directive selects its
  behavior in the extension registry (sec8.2). Hand-rolled on plain text
  rather than reusing `attribute-token`, since a directive header is never
  attached to the tree by flexmark's `AttributesExtension` at all (there is
  no preceding attachable inline/block for it to attach after -- the fence
  line has nothing but colons before it) -- there is no `AttributeNode` to
  dispatch on here in the first place. Throws `ex-info`
  (`:type ::malformed-directive`) for a token that fits none of these four
  forms, per this file's no-silent-drop error-handling ethos (AC #4 of
  TASK-5, applied here to this new construct too)."
  [tok]
  (cond
    (str/starts-with? tok "#") [:id (subs tok 1)]
    (str/starts-with? tok ".") [:class (subs tok 1)]
    (re-matches #"[A-Za-z_][\w-]*=.*" tok)
    (let [[k v] (str/split tok #"=" 2)] [:prop k v])
    (re-matches #"[A-Za-z_][\w-]*" tok) [:name tok]
    :else (throw (ex-info "malformed directive header: token is not a name, #id, .class, or key=val"
                          {:type ::malformed-directive :token tok}))))

(defn- parse-directive-header
  "`[name attr]` for a directive's opening-fence header text `header` (the
  fence line's content after its colon run, already trimmed) -- `header`
  must be a single `{...}` group (sec5.5, e.g. `{theorem #thm:main}`); an
  empty group (`{}`) or one with no bare name token at all yields an empty
  `:name` (`\"\"`), since `haselnuss.ast`'s Directive requires a `:string`
  there unconditionally even though sec5.5 says a directive's name (like
  its attribute group) is optional. At most one bare name token is
  allowed; more than one, or `header` not shaped like a single
  brace-delimited group at all, throws `ex-info`
  (`:type ::malformed-directive`) rather than silently keeping the first or
  dropping the rest."
  [header]
  (if-let [inner (second (re-matches #"\{(.*)\}" header))]
    (let [tokens (split-attr-tokens inner ::malformed-directive header)
          [dname attr] (reduce (fn [[dname attr] tok]
                                 (let [[kind a b] (directive-header-token tok)]
                                   (case kind
                                     :name (if dname
                                             (throw (ex-info "directive header has more than one name token"
                                                             {:type ::malformed-directive :text header}))
                                             [a attr])
                                     :id [dname (assoc attr :id a)]
                                     :class [dname (update attr :classes conj a)]
                                     :prop [dname (update attr :props assoc a b)])))
                               [nil default-attr]
                               tokens)]
      [(or dname "") attr])
    (throw (ex-info "malformed directive header: expected a single {...} group"
                    {:type ::malformed-directive :text header}))))

;; --- Include lines (TASK-38, sec4.3) ---------------------------------------
;;
;; SPEC.md defines the Include *node* and marks multi-file a later phase; the
;; surface syntax is this task's choice. It is a line whose entire content is
;; `!include <path>`:
;;
;;     !include chapters/methods.hdoc
;;     !include "chapters/a long name.hdoc"
;;
;; Chosen over the alternatives for three reasons. `!` already means "pull
;; external content in here" in CommonMark (`![alt](src)`), so the shape is
;; not arbitrary. A whole-line match cannot collide with anything CommonMark
;; gives meaning to, and requiring the WHOLE line makes an accidental match in
;; prose essentially impossible -- an author who really wants the literal text
;; indents it four spaces or wraps it in backticks, which shields it here the
;; same way it shields a `:::` fence. And it is one line, unlike the
;; `:::{include src=...}` / `:::` pair reusing the directive fence would have
;; needed, for a construct a multi-file document uses once per chapter.
;;
;; Recognized in `parse-directive-region`'s own line scan rather than later,
;; so it inherits that scan's shielding for free: an `!include` inside a code
;; fence or a display-math block is content, not an include.
;;
;; Two consequences of being recognized in that scan, both shared with a
;; `:::` directive fence and both worth knowing before writing one:
;;
;; - It is a block-level construct, not part of a list item. An `!include`
;;   indented into a list (up to 3 spaces, the same tolerance every other
;;   block construct here allows) ends the list and starts a new one after,
;;   exactly as a `:::` fence at that indent does. Write it at the margin.
;; - It splits the run of text around it into separate chunks before
;;   flexmark sees them, so a footnote or link-reference DEFINITION on one
;;   side of an include and its use on the other no longer see each other --
;;   again exactly as a `:::` fence between them already behaves. Keep a
;;   definition in the same chunk as its use.

(def ^:private include-line-re
  "A whole-line `!include <path>`: up to 3 leading spaces (matching
  `directive-fence-re`'s own CommonMark leaf-block indentation
  tolerance), the literal `!include`, at least one space, then the path
  (group 1) with trailing whitespace trimmed off. The path must be
  non-empty -- a bare `!include` is just a paragraph."
  #"^ {0,3}!include[ \t]+(\S.*?)[ \t]*$")

(defn- include-line
  "The `:src` of `line` if it is an `!include` line, else nil. A path
  wrapped in matching quotes has them stripped, so a path containing a
  space can be written -- the same quoting TASK-39 chose for attribute
  values, for the same reason (it is what flexmark already does, so a
  document has one quoting convention rather than two).

  And the same *contract* as TASK-39's, not merely the same spelling
  (found by review): a path that opens with a quote must close it at the
  end of the line with nothing after it, and must not be empty. Anything
  else throws `ex-info` (`:type ::malformed-include`), rather than
  quietly including some prefix of what was written."
  [line]
  (when-let [raw (second (re-matches include-line-re line))]
    (if (contains? #{\" \'} (first raw))
      (let [quoted (re-matches #"\"([^\"]*)\"|'([^']*)'" raw)
            path (when quoted (or (nth quoted 1) (nth quoted 2)))]
        (when-not (seq path)
          (throw (ex-info (if quoted
                            "malformed !include: the quoted path is empty"
                            (str "malformed !include: a quoted path must close at the end of "
                                 "the line, with nothing after it"))
                          {:type ::malformed-include :line line})))
        path)
      raw)))

(defn- parse-directive-region
  "Recursive-descent scan of `lines` (the whole document body's lines,
  paired one-for-one with a shielded flag by `annotate-fences` and shared
  unchanged across every recursive call -- only `i` moves) starting at
  index `i`. `open-len` is nil at the top level, or the colon-run length of
  the directive fence this call's content is nested inside of otherwise;
  `open-line` is nil alongside it at the top level, or the 1-based source
  line number where that enclosing fence's opening line itself was found.
  Returns `[segments next-i]`: `segments` is this level's own flat vector
  of `{:type :text :lines [...]}` /
  `{:type :directive :name .. :attr .. :segments [...]}` /
  `{:type :include :src ..}` (TASK-38) entries in source
  order, and `next-i` is the index of the line right after this level's own
  closing fence (irrelevant at the top level, where there is none to
  consume).

  A line flagged shielded (inside an open backtick/tilde code fence, or
  inside an open multi-line `$$` display-math fence -- see
  `annotate-fences`) is never treated as a directive fence at all,
  regardless of its shape -- it is code-block/math content, kept verbatim
  for whichever chunk eventually parses it as CommonMark or raw TeX
  (SPEC.md sec5.5 itself shows directive syntax presented as a worked
  example inside a fenced code block, so this shielding is required, not
  just defensive; a TASK-7 review finding extended the same requirement to
  a display-math fence's own interior -- see this file's own review
  history for repros of the corruption both cases prevent). Otherwise,
  a fence line (see `fence-line`) with a blank rest (nothing but the colon
  run itself) closes the innermost currently-open level if its colon count
  is >= `open-len` (sec5.5's \"closing fence of at least as many colons\");
  otherwise -- including when `open-len` is nil, i.e. there is nothing open
  to close -- it opens a new nested, headerless directive instead. A fence
  line with a non-blank rest (a header) is unambiguously an *opening* fence
  (a closing fence has nothing to say beyond its own colons) and therefore
  always opens a new nested directive regardless of colon count relative to
  `open-len`.

  This means the \"outer fence needs a longer colon-run than the inner
  one\" framing of AC #2/sec5.5 is only actually *load-bearing* for a bare,
  headerless nested directive: if such a fence's own colon-run were not
  shorter than the level it opens inside of, its own later closing line
  (also headerless) would satisfy the *outer* level's close condition first
  and end the outer directive early instead of nesting. A headered nested
  directive (`:::{name ...}`, the only form sec5.5's own worked examples
  ever show) always opens on sight regardless of relative colon-run length
  -- verified an inner headered directive with *more* colons than its
  headered outer still nests correctly -- so colon-run ordering is not
  enforced there at all; this is more permissive than, but does not
  contradict, sec5.5's stated rule.

  Throws `ex-info` (`:type ::unterminated-directive`, with `:open-line` set
  to the enclosing fence's own opening line number) if `open-len` is
  non-nil and `lines` is exhausted before this level's own closing fence is
  found (AC #3)."
  [lines i open-len open-line]
  (loop [i i segments [] text-buf []]
    (letfn [(flush-text [segs] (if (seq text-buf) (conj segs {:type :text :lines text-buf}) segs))]
      (if (>= i (count lines))
        (if open-len
          (throw (ex-info "unterminated directive fence: no matching closing fence found"
                          {:type ::unterminated-directive :open-line open-line}))
          [(flush-text segments) i])
        (let [[line shielded?] (nth lines i)]
          (if-let [[len header] (and (not shielded?) (fence-line line))]
            (if (and open-len (str/blank? header) (>= len open-len))
              [(flush-text segments) (inc i)]
              (let [[dname attr] (if (str/blank? header) ["" default-attr] (parse-directive-header header))
                    [inner next-i] (parse-directive-region lines (inc i) len (inc i))]
                (recur next-i
                       (conj (flush-text segments) {:type :directive :name dname :attr attr :segments inner})
                       [])))
            (if-let [src (and (not shielded?) (include-line line))]
              ;; Ends the run of text before it and starts a fresh one
              ;; after, exactly as a directive fence does -- so an
              ;; `!include` written mid-paragraph splits that paragraph
              ;; in two rather than disappearing into it.
              (recur (inc i) (conj (flush-text segments) {:type :include :src src}) [])
              (recur (inc i) segments (conj text-buf line)))))))))

;; --- Display math (TASK-7, sec5.7) ------------------------------------------
;;
;; A `$$ ... $$` display math fence, optionally followed by an attribute
;; group (sec5.7's own worked example: `$$ e^{i\pi} + 1 = 0 $$ {#eq:euler}`,
;; AC #2/#3), is recognized by hand over each `:text` segment's own raw
;; lines -- see the "Inline math" section's own docstring, near the top of
;; this file, for why display math cannot reach that inline mechanism at
;; all: a MathBlock is a top-level Block, never legitimately nested inside
;; a Paragraph. `extract-math-chunks` mirrors `parse-directive-region`'s
;; own established hand-rolled-line-scan approach (which in turn mirrors
;; this file's `annotate-code-fences`), but needs none of that function's
;; recursive nesting: a display math fence cannot itself contain further
;; block structure, so only its own opening and closing lines need finding.

(def ^:private math-single-line-re
  "A whole display math block on one line (sec5.7's own worked example):
  `$$` at the start of the line, the raw TeX (group 1, greedy so a line
  with more than two `$$` occurrences closes at the *last* one), `$$`
  again, then an optional trailing attribute group (group 2, sec5.3's
  `{#id .class key=val}`, AC #3) and only whitespace to end of line.

  Anchored at column 0. An INDENTED fence belongs to whatever container
  indented it -- a list item, most often -- and is recognized there, on
  the flexmark Paragraph, by `math-paragraph-block`. Matching it here
  instead tore the block out of its item and split the list around it
  (TASK-70)."
  #"^\$\$(.*)\$\$[ \t]*(\{[^}]*\})?[ \t]*$")

(def ^:private math-fence-line-re
  "A display math fence line with no content of its own: `$$` at the start
  of the line, then only an optional trailing attribute group (group 1)
  and whitespace to end of line. Used for *both* a multi-line block's
  opening line (never carries an attribute group in practice, since
  sec5.7's own example attaches it to the closing `$$` instead, but this
  shape does not otherwise distinguish opener from closer -- see
  `extract-math-chunks`) and its closing line.

  Anchored at column 0, for the reason `math-single-line-re` gives."
  #"^\$\$[ \t]*(\{[^}]*\})?[ \t]*$")

(defn- math-attr-group
  "The Attr for a display math fence's optional trailing `{...}` group
  text `group-text` (including its own braces, as captured by
  `math-single-line-re`/`math-fence-line-re`'s own attribute-group group),
  or `default-attr` if `group-text` is nil/blank (no group at all).
  Reuses `directive-header-token`'s tokenizer rather than duplicating it,
  but rejects that function's `:name` token kind: sec5.7's math attribute
  group is the plain three-form sec5.3 grammar (`#id`/`.class`/`key=val`),
  never a directive's own bare-name fourth form, so a bare word here
  throws `ex-info` (`:type ::malformed-attributes`) exactly like
  `attribute-token` does for an ordinary attribute group (AC #4's no-
  silent-drop philosophy applied to this construct too)."
  [group-text]
  (if (str/blank? group-text)
    default-attr
    (let [inner (second (re-matches #"\{(.*)\}" group-text))
          tokens (split-attr-tokens inner ::malformed-attributes group-text)]
      (reduce (fn [attr tok]
                (let [[kind a b] (directive-header-token tok)]
                  (case kind
                    :id (assoc attr :id a)
                    :class (update attr :classes conj a)
                    :prop (update attr :props assoc a b)
                    :name (throw (ex-info "malformed attribute group: token is not #id, .class, or key=val"
                                          {:type ::malformed-attributes :token tok})))))
              default-attr
              tokens))))

(def ^:private block-marker-line-re
  "A line that opens a new CommonMark block with a marker of its own: an
  ATX heading, a fenced code opener, a directive fence (sec5.5), or a
  thematic break.

  Used by `math-scan-boundary?` to decide where a tentative `$$` opener
  stops looking for its closer. What is in the set matters less than what
  is deliberately out of it: a LIST marker and a BLOCKQUOTE marker are not
  here, because a line of display math can begin with `-` (a negative
  leading term) or `>` (a comparison), and calling those boundaries would
  put the same false negative this rule exists to remove back in a
  different place. None of the four below can begin a line of TeX."
  #"^ {0,3}(#{1,6}[ \t]|```|~~~|:::|-{3,}[ \t]*$|\*{3,}[ \t]*$|_{3,}[ \t]*$)")

(defn- math-scan-boundary?
  "True when the line at index `i` in `lines` (length `n`) ends a tentative
  `$$` opener's search for its closer: a blank line whose next non-blank
  line opens a new block (`block-marker-line-re`).

  The bound exists so that an accidentally-unterminated `$$` -- a
  note-to-self line -- cannot pair with the first later `$$`-shaped line
  anywhere in the document and swallow every heading and paragraph in
  between into one bogus MathBlock (a TASK-7 review finding, covered by
  `unterminated-math-fence-bounded-scan-test`).

  It used to be the blank line ALONE, on the stated assumption that a
  legitimate multi-line `$$` block's own TeX content does not contain one.
  Real documents falsify that: a `cases` environment with its branches
  separated by blank lines is ordinary LaTeX, and under the old rule the
  fence ended at the first of them and spilled the rest of the body into
  prose -- where the build failed on `{cases}`, several lines from the
  cause (TASK-71).

  A blank line followed by a marker keeps the guard the old rule was for,
  because that is the shape a swallowed document has: the stray opener's
  victim is the heading or the fence that follows it. A blank line
  followed by more TeX is not a boundary, because that is what a formula
  looks like."
  [lines i n]
  (and (str/blank? (nth lines i))
       (if-let [next-i (->> (range (inc i) n)
                            (remove #(str/blank? (nth lines %)))
                            first)]
         (boolean (re-find block-marker-line-re (nth lines next-i)))
         ;; Trailing blank lines with nothing after them: an opener that
         ;; reaches here has no closer either way, so calling this a
         ;; boundary only shortens a scan whose answer is already nil.
         true)))

(defn- math-fence-bounded-close-index
  "The index within `lines` of the line that closes a tentative bare
  `math-fence-line-re` opener at index `i`, searching *only* up to (not
  including) the next `math-scan-boundary?` line after `i`, or up to `n`
  (the end of `lines`) if there is none -- never past it. Without this bound, an
  accidentally-unterminated/stray `$$` opener would greedily pair with the
  first later `$$`-shaped line *anywhere* in `lines`, silently absorbing
  every blank line, heading, and paragraph in between into one bogus
  MathBlock and desyncing whatever legitimate content follows (TASK-7
  review repro: a stray `$$` note-to-self line, followed by a blank line,
  a heading, a paragraph, a blank line, and then an unrelated real
  `$$ ... $$` block, got entirely swallowed as one MathBlock, and the
  heading never became a Section at all). Where that boundary falls is
  `math-scan-boundary?`'s to say; it used to be any blank line, which
  forbade a `cases` environment from spacing out its branches (TASK-71).
  nil if no such closer exists
  within that bounded range, in which case the opener falls back to
  literal text exactly as when there is no closer anywhere at all (AC #4).
  Shared by `extract-math-chunks` (the per-segment scan that actually
  extracts a MathBlock chunk) and `annotate-fences` (the whole-document
  pre-pass that shields a legitimate multi-line `$$` block's own interior
  from directive-fence detection) so both agree on exactly the same
  block's extent."
  [lines i n]
  (let [boundary (or (->> (range (inc i) n)
                          (filter #(math-scan-boundary? lines % n))
                          first)
                     n)]
    (->> (range (inc i) boundary)
         (filter #(re-matches math-fence-line-re (nth lines %)))
         first)))

(defn- dedent
  "`lines` with the whitespace every CONTINUATION line shares stripped off
  the front of each.

  flexmark starts a Paragraph at its first non-space character but keeps
  the source indentation of the lines after it, so a math block written
  inside a list item comes back as `\"$$\\n    x = 1\\n    $$\"`. Removing the
  shared prefix rather than all leading space keeps whatever alignment the
  author put INSIDE the formula, which is the part a reader of the .tex
  cares about."
  [lines]
  (if (< (count lines) 2)
    (vec lines)
    (let [indents (->> (rest lines)
                       (remove str/blank?)
                       (map #(count (re-find #"^[ \t]*" %))))
          shared (if (seq indents) (apply min indents) 0)]
      (into [(first lines)]
            (map (fn [line]
                   (if (>= (count line) shared) (subs line shared) (str/triml line))))
            (rest lines)))))

(defn- math-paragraph-block
  "The MathBlock for flexmark Paragraph `node` when the paragraph is
  ENTIRELY a display-math block, else nil.

  `extract-math-chunks` handles a `$$` fence written at the start of a line
  and deliberately no longer handles an indented one (see
  `math-single-line-re`): pulling an indented fence out of the line stream
  tore it out of the list item that indented it, splitting the list in two
  and restarting its numbering (TASK-70). An indented fence therefore
  reaches flexmark, which -- knowing nothing about `$$` -- puts it in an
  ordinary Paragraph of the container it belongs to. Recognizing it HERE
  keeps it there, so the block is a child of its list item, its quote or
  its directive, with its id, its number and its cross-reference intact.

  `.getChars` is read rather than the converted inlines because the body is
  raw TeX: `\\begin{cases}` run through inline conversion is read as an
  attribute group and fails the build.

  The whole paragraph must be the block. A paragraph that merely STARTS
  with a fence and carries prose after the closer is left as prose --
  conservative on purpose, since the container that indented it is exactly
  the context in which \"where does this block end\" is least obvious, and
  a wrong guess here silently swallows a sentence."
  [^Node node]
  (when (instance? Paragraph node)
    (let [lines (dedent (str/split-lines (str/trimr (.toString (.getChars node)))))]
      (cond
        (and (= 1 (count lines)) (re-matches math-single-line-re (first lines)))
        (let [[_ tex group] (re-matches math-single-line-re (first lines))]
          {:t :math-block :tex (str/trim tex) :attr (math-attr-group group)})

        (and (>= (count lines) 2)
             (re-matches math-fence-line-re (first lines))
             ;; ...and the opener carries no attribute group of its own,
             ;; which would make it a closer and the paragraph two blocks.
             (str/blank? (second (re-matches math-fence-line-re (first lines))))
             (re-matches math-fence-line-re (peek lines))
             ;; No fence in between: an interior one would mean the
             ;; paragraph holds a block plus something else.
             (not-any? #(re-matches math-fence-line-re %) (subvec lines 1 (dec (count lines)))))
        (let [[_ group] (re-matches math-fence-line-re (peek lines))]
          {:t :math-block
           :tex (str/trim (str/join "\n" (subvec lines 1 (dec (count lines)))))
           :attr (math-attr-group group)})

        :else nil))))

(defn- extract-math-chunks
  "Splits one `:text` segment's own `lines` (plain strings, see
  `parse-directive-region`) into a vector of `{:type :text :lines [...]}` /
  `{:type :math-block :tex .. :attr ..}` chunks in source order (sec5.7 AC
  #2/#3), leaving every other line untouched for `segment-items`'
  flexmark-based conversion exactly as before TASK-7.

  A line inside an open backtick/tilde code fence (`annotate-code-fences`,
  recomputed for `lines` alone -- this segment's own lines are already
  independent of the rest of the document, per this file's established
  per-chunk-independence precedent, see `segment-items`) is never treated
  as a math fence, mirroring `parse-directive-region`'s own shielding.
  `math-single-line-re` is tried first (most specific: a whole block on
  one line); otherwise a bare `math-fence-line-re` line is a tentative
  opener, and `math-fence-bounded-close-index` scans forward -- bounded at
  the next blank line, so an unrelated later `$$`-shaped line across a
  paragraph/heading break is never mistaken for this opener's own closer
  (TASK-7 review; see that function's docstring) -- for the next line
  matching that same shape to close it. If no such closing line exists
  within that bound, the opening line is left as ordinary text instead of
  raising a diagnostic: unlike a directive's explicit, deliberate
  colon-fence syntax, `$$` collides with much more common plain-text use
  (AC #4's currency-text concern extended to the block level), so an
  unterminated attempt falls back to literal text rather than erroring."
  [lines]
  (let [annotated (annotate-code-fences lines)
        n (count lines)]
    (loop [i 0 chunks [] text-buf []]
      (letfn [(flush-text [cs] (if (seq text-buf) (conj cs {:type :text :lines text-buf}) cs))]
        (if (>= i n)
          (flush-text chunks)
          (let [line (nth lines i)
                in-code-fence? (second (nth annotated i))]
            (cond
              in-code-fence?
              (recur (inc i) chunks (conj text-buf line))

              (re-matches math-single-line-re line)
              (let [[_ tex group] (re-matches math-single-line-re line)]
                (recur (inc i)
                       (conj (flush-text chunks) {:type :math-block :tex (str/trim tex) :attr (math-attr-group group)})
                       []))

              (re-matches math-fence-line-re line)
              (if-let [close-i (math-fence-bounded-close-index lines i n)]
                (let [[_ group] (re-matches math-fence-line-re (nth lines close-i))
                      tex (str/join "\n" (subvec lines (inc i) close-i))]
                  (recur (long (inc close-i))
                         (conj (flush-text chunks) {:type :math-block :tex (str/trim tex) :attr (math-attr-group group)})
                         []))
                (recur (inc i) chunks (conj text-buf line)))

              :else
              (recur (inc i) chunks (conj text-buf line)))))))))

(defn- math-chunk-items
  "The flat Block/heading-marker items (see `block-items`) for one chunk
  produced by `extract-math-chunks`: a `:text` chunk is parsed as its own
  independent flexmark mini-document (the same per-chunk-independence
  caveat as `segment-items`'s own `:text` case, one level finer); a
  `:math-block` chunk becomes its own completed Block directly, bypassing
  flexmark for its raw TeX content entirely (sec5.7 AC #2/#3)."
  [math-chunk]
  (case (:type math-chunk)
    :text (let [fm-doc (.parse cm-parser ^String (str/join "\n" (:lines math-chunk)))]
            (block-items fm-doc #{} fm-doc))
    :math-block [{:t :math-block :tex (:tex math-chunk) :attr (:attr math-chunk)}]))

(declare segments->blocks)

(defn- segment-items
  "The flat Block/heading-marker items (see `block-items`) for one segment
  produced by `parse-directive-region`: a `:text` segment is first split
  into finer `:text`/`:math-block` chunks (`extract-math-chunks`, TASK-7)
  and each converted (`math-chunk-items`) and merged back into one flat
  sequence, in source order; a `:directive` segment becomes a single-
  element vector holding its completed `:directive` Block, whose own
  `:blocks` come from recursively converting its own nested segments the
  same way (AC #1/#2 of TASK-6)."
  [segment]
  (case (:type segment)
    :text (mapcat math-chunk-items (extract-math-chunks (:lines segment)))
    ;; TASK-38: the Block itself; `haselnuss.resolver/expand-includes`
    ;; later replaces it with the referenced document's own Blocks.
    :include [{:t :include :src (:src segment)}]
    :directive [{:t :directive
                 :name (:name segment)
                 :attr (:attr segment)
                 :blocks (segments->blocks (:segments segment))}]))

(defn- segments->blocks
  "Converts a vector of `parse-directive-region` segments into Blocks,
  merging every segment's own flat items (see `segment-items`) into one
  sequence and folding sections over it exactly once (AC #1/#2)."
  [segments]
  (fold-sections (mapcat segment-items segments)))

;; --- Fence shielding (TASK-6/TASK-7, sec5.5/5.7) ----------------------------
;;
;; `parse-directive-region` runs its colon-run directive-fence scan over the
;; *whole document's* raw lines, before `extract-math-chunks` (or flexmark)
;; ever sees any of it (see `parse`) -- so anything that must be shielded
;; from being misread as a directive fence has to be recognized in this same
;; whole-document pre-pass, not per-segment. `annotate-code-fences` already
;; does this for backtick/tilde code fences (TASK-6 review). A `$$`-delimited
;; display-math fence's own interior needs the identical treatment (TASK-7
;; review): a `:::`-shaped line meant as literal TeX content inside an
;; intended multi-line display-math block was otherwise misread as a real
;; directive fence, the same class of bug one layer up.

(defn- annotate-fences
  "`lines` (the whole document body's raw lines) paired one-for-one with a
  boolean, true iff that line falls inside an open backtick/tilde code
  fence (`annotate-code-fences`) OR inside an open multi-line `$$`
  display-math fence -- either way, shielded from
  `parse-directive-region`'s own colon-run directive-fence detection.

  Code-fence state is computed first (`annotate-code-fences`) and takes
  priority: a line already inside a code fence is never additionally
  considered as a math-fence opener, mirroring `extract-math-chunks`'s own
  `in-code-fence?` check. A `math-single-line-re` line (a whole `$$ ... $$`
  block on one line) needs no shielding of its own -- nothing else on that
  line can look like a directive fence -- so only a genuine
  `math-fence-line-re` opener's *own* bounded block (found via
  `math-fence-bounded-close-index`, the same blank-line-bounded scan
  `extract-math-chunks` uses, so both agree on the same block's extent) is
  marked shielded, its interior and closing line true, its own opening line
  false -- mirroring `annotate-code-fences`'s identical open-line-false/
  content-and-close-line-true shape. An opener with no closer within that
  bound is left unshielded (false), exactly like `extract-math-chunks`'s
  own literal-text fallback (AC #4): a stray, never-closed `$$` must not
  shield anything after it either."
  [lines]
  (let [code-annotated (annotate-code-fences lines)
        n (count lines)]
    (loop [i 0 acc []]
      (if (>= i n)
        acc
        (let [line (nth lines i)
              in-code-fence? (second (nth code-annotated i))]
          (cond
            in-code-fence?
            (recur (inc i) (conj acc [line true]))

            (re-matches math-single-line-re line)
            (recur (inc i) (conj acc [line false]))

            (re-matches math-fence-line-re line)
            (if-let [close-i (math-fence-bounded-close-index lines i n)]
              (recur (long (inc close-i))
                     (into (conj acc [line false])
                           (map #(vector (nth lines %) true) (range (inc i) (inc close-i)))))
              (recur (inc i) (conj acc [line false])))

            :else
            (recur (inc i) (conj acc [line false]))))))))

;; --- Front matter ------------------------------------------------------------

(defn- split-front-matter
  "If `source` begins with a YAML front-matter block -- a line of exactly
  `---`, then any number of lines, then a line of exactly `---` or `...`
  (SPEC.md sec5.2) -- returns `[yaml-text body-text]`; otherwise
  `[nil source]` (no front matter, `source` is the whole body)."
  [source]
  (let [lines (vec (str/split source #"\n" -1))]
    (if (= "---" (first lines))
      (let [end-idx (->> (subvec lines 1)
                         (keep-indexed (fn [i l] (when (contains? #{"---" "..."} l) i)))
                         first)]
        (if end-idx
          [(str/join "\n" (subvec lines 1 (inc end-idx)))
           (str/join "\n" (subvec lines (+ end-idx 2)))]
          [nil source]))
      [nil source])))

(defn- coerce-date
  "A front-matter `date` scalar as a plain `:string`: clj-yaml parses an
  unquoted YAML date (e.g. `2026-07-24`) into a `java.util.Date` at
  midnight UTC, so that case is converted back to its ISO-8601 date string
  via UTC rather than the local timezone; any other scalar (e.g. an
  already-quoted string) is used as-is via `str`."
  [v]
  (if (instance? Date v)
    (-> ^Date v .toInstant (.atZone ZoneOffset/UTC) .toLocalDate .toString)
    (str v)))

(defn- coerce-authors
  "A front-matter `author`/`authors` value as a vector of strings: a YAML
  sequence (`[A, B]`) maps element-wise, a bare scalar becomes a single-
  element vector."
  [v]
  (if (sequential? v) (mapv str v) [(str v)]))

(def ^:private top-level-divisions
  "The `topLevelDivision` front-matter values (TASK-53), each mapped to
  its own `haselnuss.ast` Meta keyword. Named after pandoc's own
  `--top-level-division`, so an author who knows that tool already knows
  this key; written in front matter rather than passed on the command
  line because it changes what every number in the document *means*, and
  a document whose figure numbering depends on how it was invoked is one
  whose cross-references cannot be checked by reading it."
  {"section" :section
   "chapter" :chapter})

(defn- coerce-top-level-division
  "A front-matter `topLevelDivision` scalar as its Meta keyword
  (`top-level-divisions`), throwing `ex-info` (`:type
  ::unknown-top-level-division`) for anything else.

  A hard error, not a warning-and-default, because the default is the
  thing being overridden: a document that writes `topLevelDivision:
  chapters` (or `Chapter`, or `book`) and is silently given `:section`
  loses every `\\chapter` in the output and renumbers every figure in
  it, with nothing said. This codebase's established convention is that
  a construct with no representation stops the build naming itself
  (`haselnuss.lower`'s own `::no-representation`) rather than being
  quietly dropped; a front-matter value with no meaning is the same
  case. Only this one key is validated -- an unrecognized *key* is still
  ignored, since Meta is open by design (sec4.2) and ignoring an unknown
  key is how a document stays forward-compatible."
  [v]
  (let [text (str/trim (str v))]
    (or (get top-level-divisions text)
        (throw (ex-info (str "unknown topLevelDivision " (pr-str text)
                             " (expected one of: "
                             (str/join ", " (sort (keys top-level-divisions))) ")")
                        {:type ::unknown-top-level-division :text text})))))

(defn- parse-front-matter
  "Parses `yaml-text` (the raw front-matter block, or nil if there was
  none) into a `haselnuss.ast` Meta map, mapping exactly the fields AC #1
  names: `title` (wrapped into Inlines, since `Meta.title` is `Inline[]`),
  `author`/`authors` (either key), `date`, `bibliography`, `cslStyle` (->
  `:csl-style`), and `lang` -- plus `topLevelDivision` (->
  `:top-level-division`, TASK-53). Any other front-matter key is ignored
  -- a deliberate scope limit to what AC #1 asks for, not a claim that
  Meta can't carry more later."
  [yaml-text]
  (if (str/blank? yaml-text)
    {}
    (let [data (yaml/parse-string yaml-text)]
      (cond-> {}
        (contains? data :title) (assoc :title (text->inlines (str (:title data))))
        (or (contains? data :author) (contains? data :authors))
        (assoc :authors (coerce-authors (or (:authors data) (:author data))))
        (contains? data :date) (assoc :date (coerce-date (:date data)))
        (contains? data :bibliography) (assoc :bibliography (str (:bibliography data)))
        (contains? data :cslStyle) (assoc :csl-style (str (:cslStyle data)))
        (contains? data :lang) (assoc :lang (str (:lang data)))
        (contains? data :topLevelDivision)
        (assoc :top-level-division (coerce-top-level-division (:topLevelDivision data)))))))

;; --- Entry point --------------------------------------------------------------

(defn front-matter
  "The `haselnuss.ast` Meta `parse` would produce for source `s`, without
  parsing its body.

  Exists for the chicken-and-egg TASK-47 creates: `parse`'s `:cite-keys`
  option needs the bibliography, and which bibliography to load is
  `meta.bibliography`, which is in the document being parsed. Reading
  just the front matter is cheaper and far less surprising than parsing
  twice, and the front matter is by definition self-contained -- it ends
  at its own closing `---` before any body construct begins."
  [s]
  (parse-front-matter (first (split-front-matter s))))

(defn parse
  "Parses Haselnuss `.hdoc` source `s` into a `haselnuss.ast/Document`.

  `opts` may carry the two vocabularies that decide which `@`-tokens are
  references at all (TASK-47; see this file's cross-reference/citation
  comment block for the trade-off they impose):

  - `:kinds`, the set of valid kind-prefix strings (`\"fig\"`, `\"sec\"`,
    plus whatever a custom directive registered). Absent, any prefix
    *shaped* like a kind is accepted.
  - `:cite-keys`, the set of citation-key strings the bibliography
    defines. Absent, any colon-free `@word` is a citation.

  Both are optional and both default to the pre-TASK-47 behavior, so
  this namespace still parses standalone with no resolver in sight.

  Throws `ex-info` (`:type ::unsupported-node`) if `s` contains a
  CommonMark construct this parser does not yet handle (e.g. raw HTML), and
  `ex-info` (`:type ::invalid-ast`) if the constructed AST unexpectedly
  fails to validate against `ast/Document` -- a bug in this parser, not a
  caller error, since every path above is expected to always produce a
  conforming Document; this is a safety net, not routine validation."
  ([s] (parse s {}))
  ([s {:keys [kinds cite-keys]}]
   ;; Both vocabularies are matched against substrings of the source, so
   ;; a set of keywords -- the obvious mistake, since a lexicon is keyed
   ;; by keyword -- would match nothing and silently turn every
   ;; reference in every document into prose, with no warning anywhere.
   ;; Refuse it, the way this file refuses every other malformed input
   ;; rather than dropping it (found by review).
   (doseq [[k v] [[:kinds kinds] [:cite-keys cite-keys]]]
     (when (and v (not (every? string? v)))
       (throw (ex-info (str "parse option " k " must be a set of strings")
                       {:type ::invalid-parse-option :option k :value v}))))
   ;; The whole body, validation included, runs inside the binding: the
   ;; vocabularies are read from deep inside flexmark's inline parser
   ;; (`parse-cross-ref-or-cite!`), and anything realized after the
   ;; binding closed would see the defaults instead.
   (binding [*known-kinds* kinds
             *known-cite-keys* cite-keys]
     (let [[yaml-text body] (split-front-matter s)
           [segments] (parse-directive-region (annotate-fences (str/split body #"\n" -1)) 0 nil nil)
           document {:meta (parse-front-matter yaml-text)
                     :blocks (segments->blocks segments)}]
       (when-not (ast/valid? ast/Document document)
         (throw (ex-info "parser produced an AST that does not conform to haselnuss.ast/Document"
                         {:type ::invalid-ast
                          :explanation (ast/explain ast/Document document)})))
       document))))

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
  lines\" section for the two behaviors this shares with a fence. So does
  TASK-114's one-line `!name{...}` directive, sec5.5's second spelling for
  a directive with no body at all -- see the \"One-line directives\"
  section for why the body-less case earned its own spelling and why `!`
  rather than a shorter colon run.
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
  unrelated syntax. That option is on by default and is turned OFF in
  `build-cm-parser` (TASK-208): it made a line wholly in `[...]` directly
  under a table (`[@k]`, `[^1]`, `[note]`) a `TableCaption`, which this
  namespace never converted, so the citation or note silently vanished;
  off, the line is an ordinary paragraph, exactly as after a blank line.
  The `: caption` line is hand-detected against the already-parsed
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
  already holds.

  ## Highlights (TASK-122, sec5.6)

  `==marked text==` is a `:highlight` Inline (sec4.4), and it is the one
  construct here recognized by a flexmark `DelimiterProcessor` rather
  than an `InlineParserExtension`. The reason is a requirement none of
  the four constructs above has: a highlight's content is ORDINARY
  MARKUP, which has to be parsed as such, and `LightInlineParser` -- all
  an `InlineParserExtension` is handed -- offers no way to parse a span
  of input back into inline nodes. Every extension above reads its
  matched span as an opaque string precisely because its content IS
  opaque. A `DelimiterProcessor` is what `*emph*` and `**strong**`
  themselves are built on, so this is the older and more ordinary of the
  two mechanisms rather than a new one, and it supplies left/right
  flanking, nesting and unmatched-delimiter fallback for free. See
  `highlight-delimiter-processor` and the section comment above it."
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
           (com.vladsch.flexmark.ext.attributes AttributeNode AttributesNode)
           (com.vladsch.flexmark.ext.attributes.internal AttributesInlineParserExtension$Factory
                                                         AttributesNodePostProcessor$Factory)
           (com.vladsch.flexmark.ext.footnotes Footnote FootnoteBlock FootnoteExtension)
           (com.vladsch.flexmark.ext.tables TableBlock TableBody TableCell
                                            TableCell$Alignment TableHead
                                            TablesExtension)
           (com.vladsch.flexmark.parser InlineParser InlineParserExtension
                                        InlineParserExtensionFactory
                                        LightInlineParser LinkRefProcessor
                                        LinkRefProcessorFactory Parser)
           (com.vladsch.flexmark.parser.core.delimiter Bracket Delimiter)
           (com.vladsch.flexmark.parser.delimiter DelimiterProcessor)
           (com.vladsch.flexmark.util.ast Block Document Node)
           (com.vladsch.flexmark.util.data MutableDataSet NullableDataKey)
           (com.vladsch.flexmark.util.sequence BasedSequence Escaping)
           (java.time ZoneOffset)
           (java.util Date)
           (org.yaml.snakeyaml.error Mark MarkedYAMLException)))

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

(def ^:private ^NullableDataKey input-cache-key
  "The document data key `input-cache` keeps its `object-array` under."
  (NullableDataKey. "haselnuss.parser/input-cache"))

(defn- input-cache
  "What the inline extensions derive from `input`, an inline parser's whole
  input (one paragraph's text), from flexmark `document`: an `object-array`
  of the input itself, its String, and the slots `input-derived` fills.
  The `$`, `@` and `[` extensions are offered every one of their trigger
  characters in the paragraph, and each needs the text as a String;
  converting it afresh on every call made a paragraph's inline parse
  quadratic in its length (TASK-253: a 136k character line took over half
  a minute). The input object stays the same for the whole paragraph and
  is immutable, so what was derived from it is kept in the document's data
  and reused while the input is the same object. Held per document, so it
  goes with the document and is never shared between threads."
  ^objects [^Document document ^BasedSequence input]
  (let [^objects cached (.get input-cache-key document)]
    (if (and cached (identical? input (aget cached 0)))
      cached
      (let [fresh (object-array [input (.toString input) nil nil])]
        (.set input-cache-key document fresh)
        fresh))))

(defn- input-string
  "The String of inline parser input `input` (see `input-cache`)."
  ^String [^Document document ^BasedSequence input]
  (aget (input-cache document input) 1))

(def ^:private math-stops-slot
  "`input-cache` slot of `inline-math-stops`."
  2)

(def ^:private book-ref-stops-slot
  "`input-cache` slot of `book-ref-stops`."
  3)

(defn- input-derived
  "`(f s)` for the String `s` of inline parser input `input`, computed once
  per input and kept in `input-cache` slot `slot`."
  [^Document document ^BasedSequence input slot f]
  (let [^objects cached (input-cache document input)
        slot (int slot)]
    (or (aget cached slot)
        (let [v (f (aget cached 1))]
          (aset cached slot v)
          v))))

(defn- inline-math-stops
  "An `int-array` of `(inc (count s))` entries: entry `i` is the first index
  `j >= i` of `s` that holds a line break or a `$` that
  `inline-math-closing-index` would accept as a close (its predecessor not
  whitespace, no digit after it), or `(count s)` when there is none. Built
  backwards in one pass, so a paragraph full of unclosed `$` -- `$5 w $5 w`
  -- is not scanned to its line's end once per `$` (TASK-253). Index 0 is
  never a close: a close is searched for from one past an opener."
  ^ints [^String s]
  (let [n (.length s)
        stops (int-array (inc n))]
    (aset stops n n)
    (loop [j (dec n)]
      (when (>= j 0)
        (let [c (.charAt s j)
              stop? (or (= c \newline)
                        (and (= c \$)
                             (pos? j)
                             (not (Character/isWhitespace (.charAt s (dec j))))
                             (or (>= (inc j) n) (not (Character/isDigit (.charAt s (inc j)))))))]
          (aset stops j (int (if stop? j (aget stops (inc j)))))
          (recur (dec j)))))
    stops))

(defn- inline-math-closing-index-at
  "`(inline-math-closing-index s open-index (count s))`, looked up in
  `stops`, the `inline-math-stops` of `s`."
  [^String s ^ints stops open-index]
  (let [j (aget stops (int (inc (long open-index))))]
    (when (and (< j (.length s)) (= \$ (.charAt s j)))
      j)))

(defn- parse-inline-math!
  "This file's `InlineParserExtension/parse` for the `$` trigger character
  (see `inline-math-extension-factory`): true if it consumed something at
  `p`'s current index (either a literal `$$` or a genuine MathInline node),
  false to fall through to flexmark's own default single-character literal
  handling for the `$` at that position (sec5.7 AC #1/#4)."
  [^LightInlineParser p]
  (let [^BasedSequence input (.getInput p)
        s (input-string (.getDocument p) input)
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
      (if-let [close (inline-math-closing-index-at
                      s (input-derived (.getDocument p) input math-stops-slot inline-math-stops) i)]
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

(defn bare-reference-token?
  "True if a bare `@token` would scan `token` back whole (`ref-token-end`,
  sec5.10/5.11): non-empty, every character `ref-token-char?`, and none of
  `ref-token-tail-punctuation` at its end. Whether it then reads as a
  cross-reference or an author citation is its colon's to say, and whether
  it is one at all the vocabularies' (`reference-at?`). Public because
  `haselnuss.emit.hdoc` must refuse a hand-built label or key the scan would
  cut short (TASK-207): `@a.b` is the citation `a` and the text `.b`."
  [token]
  (boolean (and (string? token)
                (pos? (count token))
                (= (count token) (ref-token-end token 0 (count token))))))

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

(def ^:private ^:dynamic *source-file*
  "The `:source` path of the parse in progress, or nil (TASK-143): the file a
  node's recorded position names."
  nil)

(def ^:private ^:dynamic *body-line-offset*
  "How many lines the front matter took off the top of the file being
  parsed (TASK-143): a body line's number in the file is its body number
  plus this."
  0)

(def ^:private ^:dynamic *line-base*
  "The FILE line number (1-based) of line 0 of the flexmark document being
  converted, or nil when its lines cannot be placed in the file (a
  citation prefix/suffix re-parsed without a `locate`, see
  `mini-parse-inlines`). A flexmark node's file
  line is `*line-base*` plus its `.getLineNumber` (TASK-143)."
  nil)

(def ^:private ^:dynamic *line-starts*
  "A `long-array` of the start offset of every line of the flexmark document
  being converted, computed once per document so that locating a node is a
  binary search rather than flexmark's `Node.getLineNumber`, which walks
  the text up to the node on every call (TASK-143)."
  nil)

(def ^:private ^:dynamic *offset-column*
  "nil when every line of the flexmark document being converted is a line
  of the file as written, so a node's column on its line there is its
  file column (TASK-248). A document re-parsed from text cut out of the
  file (a table caption, `table-caption-and-attr`), whose lines lost a
  marker or a container prefix, binds instead a function from an offset
  of its own text to the FILE column of that character, or nil when the
  character is not one of the file's."
  nil)

(defn- line-starts
  "The `long-array` of line start offsets of `text`."
  ^longs [^CharSequence text]
  (let [n (.length text)]
    (long-array (cons 0 (for [i (range n) :when (= \newline (.charAt text i))] (inc i))))))

(defn- doc-line-starts
  "`line-starts` of flexmark `document`'s source text."
  ^longs [^com.vladsch.flexmark.util.ast.Document document]
  (line-starts (.getChars document)))

(defn- offset-line
  "The 0-based line of offset `offset` of a text whose line start offsets
  are `starts`."
  [^longs starts offset]
  (let [idx (java.util.Arrays/binarySearch starts (long offset))]
    (if (neg? idx) (- (- idx) 2) idx)))

(defn- node-line
  "The 0-based line of flexmark `node` within the document whose
  `*line-starts*` is bound."
  [^com.vladsch.flexmark.util.ast.Node node]
  (offset-line *line-starts* (.getStartOffset node)))

;; A node's column is counted in code points from the start of its line
;; (TASK-248). Counting them afresh for every node made a document with one
;; long line quadratic in its length -- every reference, emphasis or span
;; on a 136k character line walked the line from its start (TASK-253). A
;; code point is one char except a surrogate pair, which is two, so the
;; column is the char distance less the pairs in between, and the pairs
;; are found once per document.

(def ^:private ^:dynamic *surrogate-pairs*
  "nil, or `[text pairs]` for the flexmark document whose `*line-starts*`
  are bound: `text` is its source, the base sequence of its nodes' chars,
  and `pairs` its `surrogate-pair-ends`. `offset-column-fn` uses `pairs`
  only for that very `text`, and counts code points as before for any
  other."
  nil)

(defn- surrogate-pair-ends
  "The ascending `long-array` of every index `k` of `text` holding a low
  surrogate right after a high one at `k - 1`: the second char of each
  surrogate pair, one code point spread over two chars."
  ^longs [^CharSequence text]
  (let [n (.length text)]
    (long-array (for [k (range 1 n)
                      :when (and (Character/isLowSurrogate (.charAt text k))
                                 (Character/isHighSurrogate (.charAt text (dec k))))]
                  k))))

(defn- doc-surrogate-pairs
  "`*surrogate-pairs*` for flexmark `document`."
  [^com.vladsch.flexmark.util.ast.Document document]
  (let [text (.getChars document)]
    [text (surrogate-pair-ends text)]))

(defn- code-point-column
  "1 plus `(Character/codePointCount text line-start offset)`, from the
  `surrogate-pair-ends` `pairs` of `text` instead of its chars: the pairs
  wholly inside `[line-start, offset)` are those ending at `k` with
  `line-start < k < offset`, each two chars but one code point. A pair
  cut by either end counts its one char there as one code point, as
  `codePointCount` does."
  [^longs pairs line-start offset]
  (let [below (fn [x]
                (let [idx (java.util.Arrays/binarySearch pairs (long x))]
                  (if (neg? idx) (- (inc idx)) idx)))]
    (inc (- (- (long offset) (long line-start))
            (- (long (below offset)) (long (below (inc (long line-start)))))))))

(defn- offset-column-fn
  "A function from an offset of `text` -- the source of the flexmark
  document whose `*line-starts*` (and `*offset-column*`) are bound now
  -- to the 1-based FILE column of that character, counted in code
  points (a tab is one), or nil when it is not placed in the file
  (TASK-248). Captures the bindings, so it can place a document re-parsed
  from this one's text."
  [^CharSequence text]
  (or *offset-column*
      (let [starts ^longs *line-starts*
            [pairs-text pairs] *surrogate-pairs*
            pairs (when (identical? pairs-text text) pairs)]
        (fn [offset]
          (let [line-start (aget starts (int (offset-line starts offset)))]
            (if pairs
              (code-point-column pairs line-start offset)
              (inc (Character/codePointCount text (int line-start) (int offset)))))))))

(defn- node-file-column
  "The 1-based FILE column of flexmark `node`'s first character, or nil
  (`offset-column-fn`)."
  [^com.vladsch.flexmark.util.ast.Node node]
  ((offset-column-fn (.getBaseSequence (.getChars node))) (.getStartOffset node)))

(defn- at-node
  "`n` (an AST node) carrying the source position of flexmark `node`
  (TASK-143, see `haselnuss.ast/with-source`) -- its line and, where it
  is known, its column (TASK-248, `node-file-column`) -- or `n` unchanged
  when this conversion has no `*line-base*`."
  [^com.vladsch.flexmark.util.ast.Node node n]
  (if *line-base*
    (ast/with-source n *source-file* (+ *line-base* (node-line node)) (node-file-column node))
    n))

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

;; BOOK REFERENCES (TASK-152, doc-10 "Surface syntax in .hdoc"). `@{ref}`
;; links to a passage of another book. The braces are required precisely
;; because a bare `@reformed:heidelberg` already means a local cross-ref
;; (sec5.10) and must keep meaning one: `{` is not a `ref-token-char?`, so
;; nothing above ever claimed `@{`, and recognizing it first changes no
;; existing parse.
;;
;; The parser decides only that the author WROTE a book reference, not
;; whether it is a well-formed one. The text between the braces is kept as
;; written and never run through `haselnuss.book-ref/parse-book-ref` here:
;; doc-10 makes a malformed reference a `malformed-book-ref` WARNING, the
;; build continuing with a `??`, which is the resolver's to report (it is
;; the pass that has diagnostics and the node's source position to name).
;; A parse error would instead stop the whole build over one typo'd link,
;; which no other reference in this format does. So the syntactic rule is
;; deliberately loose: `@{`, then non-blank text with no line break, up to
;; the FIRST `}`. `@{}`, `@{ }`, and an `@{` with no `}` on its line stay
;; literal text. Unlike a bare `@token`, a preceding token character does
;; not decline it (`x@{a:b}` is a book ref): the braces are an explicit
;; marker, which an email address never contains.

(definterface ^:private BookRefNodeTag)

(defn- book-ref-end
  "The end index (exclusive, just past the `}`) of a `@{ref}` book
  reference whose `@` is at index `i` of `s`, or nil when there is none
  there (see the BOOK REFERENCES comment above for the rule).

  `stops` is a no-argument function giving the `book-ref-stops` of `s`,
  called only when there is a `@{` at `i`. The first `}`, `\\n` or `\\r`
  after the `{` must be a `}`: a line break before the first `}` would be
  inside the ref. Looking it up there, and testing the ref for blankness
  only up to its first non-blank character, keeps a line full of `@{`
  with no `}` linear (TASK-253): finding the `}` used to scan to the end
  of the paragraph for each one."
  [^String s i stops]
  (let [n (.length s)
        open (inc i)]
    (when (and (< (inc open) n) (= \@ (.charAt s i)) (= \{ (.charAt s open)))
      (let [close (aget ^ints (stops) (int (inc open)))]
        (when (and (< close n) (= \} (.charAt s close)))
          (when (loop [k (inc open)]
                  (and (< k close)
                       (or (not (Character/isWhitespace (.charAt s k)))
                           (recur (inc k)))))
            (inc close)))))))

(defn- book-ref-stops
  "An `int-array` of `(inc (count s))` entries: entry `i` is the first index
  `j >= i` of `s` holding `}`, `\\n` or `\\r`, or `(count s)` when there is
  none (see `book-ref-end`)."
  ^ints [^String s]
  (let [n (.length s)
        stops (int-array (inc n))]
    (aset stops n n)
    (loop [j (dec n)]
      (when (>= j 0)
        (aset stops j (int (if (#{\} \newline \return} (.charAt s j)) j (aget stops (inc j)))))
        (recur (dec j))))
    stops))

(defn- book-ref-node
  "A fresh synthetic Node for a `@{ref}` book reference: `whole-span` runs
  from the `@` through the `}`, and `.getText` is the ref between the
  braces. Built like `cross-ref-node`."
  ^DelimitedNodeImpl [^BasedSequence whole-span]
  (let [node (proxy [DelimitedNodeImpl BookRefNodeTag] [])
        n (.length whole-span)]
    (.setChars ^Node node whole-span)
    (.setOpeningMarker node (.subSequence whole-span 0 2))
    (.setText node (.subSequence whole-span 2 (dec n)))
    (.setClosingMarker node (.subSequence whole-span (dec n) n))
    node))

(def ^:private book-ref-class
  "The exact runtime class every `book-ref-node` instance shares -- see
  `cross-ref-class`'s own docstring."
  (class (proxy [DelimitedNodeImpl BookRefNodeTag] [])))

(defn- book-ref-node?
  "True if `node` is one of this file's own synthetic BookRef nodes."
  [node]
  (identical? book-ref-class (class node)))

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
  type anyway.

  A `@{ref}` book reference (TASK-152, see the BOOK REFERENCES comment
  above) is tried first; its `{` can never start a bare token, so it takes
  nothing from either of the other two."
  [^LightInlineParser p]
  (let [^BasedSequence input (.getInput p)
        s (input-string (.getDocument p) input)
        n (.length input)
        i (.getIndex p)
        start (inc i)
        end (ref-token-end s start n)]
    (if-let [book-end (book-ref-end s i #(input-derived (.getDocument p) input book-ref-stops-slot book-ref-stops))]
      (do (.appendNode p (book-ref-node (.subSequence input (int i) (int book-end))))
          (.setIndex p (int book-end))
          true)
      (if (or (= end start) (and (pos? i) (ref-token-char? (.charAt s (dec i)))))
        false
        (let [token (subs s start end)
              whole (.subSequence input (int i) (int end))
              colon? (str/includes? token ":")]
          (if-not (if colon? (cross-ref-token? token) (cite-token? token))
            false
            (do (.appendNode p (if colon? (cross-ref-node whole) (cite-author-node whole)))
                (.setIndex p end)
                true)))))))

(defn reference-at?
  "True if the `@` at index `i` of `s` would parse as a bare cross-reference
  or author-in-text citation -- the same decision `parse-cross-ref-or-cite!`
  makes, over the same `ref-token-end` / preceding-character / vocabulary
  checks, so `haselnuss.emit.hdoc` can tell an `@` that needs a backslash
  (it would re-parse as a reference) from one that is prose (`cc @someone`
  with no such citation key, `user@example.com`).

  `opts` takes the same `:kinds` / `:cite-keys` vocabularies `parse` does
  (nil meaning the shape heuristics, as there). `i` is taken to be at the
  start of `s` when 0: a caller escaping one text run cannot see the
  character before it, and treats that as not a token character.

  A `@{ref}` book reference (TASK-152) is deliberately NOT covered: a
  backslash before its `@` does not make it prose (the `{…}` left behind
  is then read as an attribute group), so `haselnuss.emit.hdoc` escapes
  the `{` instead."
  [^String s i {:keys [kinds cite-keys]}]
  (let [n (.length s)
        start (inc i)
        end (ref-token-end s start n)]
    (boolean
     (and (> end start)
          (not (and (pos? i) (ref-token-char? (.charAt s (dec i)))))
          (binding [*known-kinds* kinds
                    *known-cite-keys* cite-keys]
            (let [token (subs s start end)]
              (if (str/includes? token ":")
                (cross-ref-token? token)
                (cite-token? token))))))))

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

(defn- backtick-run-end
  "The index just past the run of backticks in `s` (length `n`) that
  starts at `i`."
  [^String s i n]
  (loop [k i]
    (if (and (< k n) (= (.charAt s k) \`)) (recur (inc k)) k)))

(defn- inline-span-end-index
  "The index just past whatever inline construct opens at index `j` of `s`
  (length `n`), for a scan that must not look inside a code span or inline
  math (`cite-bracket-close-index`). A backtick run is a code span up to
  the next run of the SAME length (CommonMark sec6.1), or literal on its
  own when no such run follows; a `$` follows `parse-inline-math!`: `$$`
  and a `$` before whitespace are literal, anything else is math up to
  `inline-math-closing-index`, or literal when that finds no close. Any
  other character is just itself."
  [^String s j n]
  (case (.charAt s j)
    \` (let [run-end (backtick-run-end s j n)
             len (- run-end j)]
         (loop [k run-end]
           (cond
             (>= k n) run-end
             (= (.charAt s k) \`) (let [e (backtick-run-end s k n)]
                                    (if (= len (- e k)) e (recur e)))
             :else (recur (inc k)))))
    \$ (cond
         (and (< (inc j) n) (= (.charAt s (inc j)) \$)) (+ j 2)
         (or (>= (inc j) n) (Character/isWhitespace (.charAt s (inc j)))) (inc j)
         :else (if-let [close (inline-math-closing-index s j n)] (inc close) (inc j)))
    (inc j)))

(defn- top-level-indices
  "The indices of `s` whose character is not inside a code span or inline
  math span (see `inline-span-end-index`). A bracket's `;` separators and
  `@key` markers are only ever read at these (TASK-167), so `` `a;b` `` or
  `$x@y$` stays the content of its span. A backslash-escaped character
  opens no span but is still listed, as it was before spans were
  skipped: an escaped `\\;` still splits (see
  `haselnuss.emit.hdoc/check-no-cite-affix-semicolon!`), and
  `cite-key-index` rejects an escaped `\\@` by the backslash before it."
  [^String s]
  (let [n (.length s)]
    (loop [j 0 acc (transient [])]
      (cond
        (>= j n) (persistent! acc)
        (and (= (.charAt s j) \\) (< (inc j) n)) (recur (+ j 2) (conj! acc (inc j)))
        :else (let [e (long (inline-span-end-index s j n))]
                (recur e (if (= e (inc j)) (conj! acc j) acc)))))))

(defn- cite-segment-spans
  "`[start end]` within `inner` of each segment `cite-segments` returns,
  in order -- where each one sits, so that a reference in a segment's
  prefix or suffix can be placed in the file (TASK-252)."
  [^String inner]
  (let [cuts (filterv #(= \; (.charAt inner (int %))) (top-level-indices inner))
        spans (mapv vector (cons 0 (map inc cuts)) (conj cuts (count inner)))
        text (fn [[start end]] (subs inner start end))]
    (loop [spans spans]
      (if (and (seq spans)
               (or (= "" (text (peek spans)))
                   (and (> (count spans) 1) (str/blank? (text (peek spans))))))
        (recur (pop spans))
        spans))))

(defn cite-segments
  "The `;`-separated segments of a citation bracket's raw inner text
  `inner` (sec5.11), split only at a `;` in `top-level-indices` -- one
  inside a code span or inline math belongs to that span (TASK-167).
  Trailing empty segments are dropped, as `String/split` drops them, so
  `@a;` is the one segment `@a`. So is a trailing segment of nothing but
  whitespace after a `;` (TASK-207): `@a; ` is `@a` too, as it reads, rather
  than a second segment with no key that made `[@a; ]` no citation while
  `[@a;]` was one -- and `[@a; ](u)` a link whose text, `@a;`, no spelling
  could write back. A whitespace-only `inner` with no `;` at all is kept as
  its one segment (it has no key either way). Public because
  `haselnuss.emit.hdoc` must know exactly which `;` this split would cut
  at."
  [^String inner]
  (mapv (fn [[start end]] (subs inner start end)) (cite-segment-spans inner)))

(defn- cite-key-index
  "`[at key-end]` for the first `@key` citation-key marker (sec5.11) in
  segment `seg`, or nil if it has none: `at` is the index of its `@`,
  `key-end` the index just past its key. The `@` must be in
  `top-level-indices` (not inside a code span or inline math, TASK-167)
  and not directly after a backslash, so an escaped `\\@doe99` is never a
  marker (review finding #4 on TASK-9: `[\\@doe99]` must not be swallowed
  into a citation any more than `\\@fig:tree` is read as a bare CrossRef);
  the key is a run of word characters, `:` and `-`."
  [^String seg]
  (some (fn [at]
          (when (and (= \@ (.charAt seg (int at)))
                     (not (and (pos? at) (= \\ (.charAt seg (int (dec at)))))))
            (when-let [cite-key (re-find #"^[\w:-]+" (subs seg (inc at)))]
              [at (+ (inc at) (count cite-key))])))
        (top-level-indices seg)))

(defn bracket-cite-key?
  "True if `cite-key` is read back whole after the `@` of a bracketed
  citation segment (sec5.11) -- a run of ASCII word characters, `:` and
  `-`, the key `cite-key-index` scans. Public because `haselnuss.emit.hdoc`
  must refuse a hand-built key outside it (TASK-207): `[@a.b]` reads as key
  `a` with suffix `.b`, `[@müller]` as key `m`."
  [cite-key]
  (boolean (and (string? cite-key) (re-matches #"[\w:-]+" cite-key))))

(defn- citation-bracket-content?
  "True if `inner` (the raw text strictly between a `[` and its candidate
  matching `]`) looks enough like sec5.11's bracketed citation syntax to
  claim the whole bracket (AC #4): it has at least one `;`-separated
  segment (`cite-segments`) and every segment contains a real `@key`
  marker of its own (`cite-key-index`). An ordinary link/text bracket
  with no unescaped `@` at all -- an empty bracket (`[]`), one made up
  only of `;` characters (`[;]`/`[;;;]`), or one whose only `@` is
  backslash-escaped (`[\\@doe99]`) -- is declined here and left for
  flexmark's own default bracket/backslash-escape handling instead.

  The `seq` check exists because `every?` over an *empty* collection is
  vacuously true: review finding #3 caught that splitting `;` (and
  `;;;`, etc.) leaves no segments at all, since trailing empty ones are
  dropped, and a bare `every?` wrongly accepted `[;]`/`[;;;]` as a
  citation, producing a nonsensical zero-CiteItem `Cite` node.

  A known, deliberately accepted false-positive limitation (mirroring
  TASK-7's own accepted heuristic tradeoffs, e.g. its dollar-math currency
  case): ordinary bracketed prose that happens to contain an email/
  username-style `@word` token in every semicolon-separated segment (an
  unlikely combination) is still claimed as a citation, since this check
  has no email-shape awareness of its own the way `parse-cross-ref-or-
  cite!`'s bare-form adjacency check does. Since a citation group may
  span lines (TASK-165), so may such a bracket: `[the list\\nat
  foo@bar.com]` is a citation of key `bar` just as its one-line form is.
  An `@` or `;` inside a code span or inline math is that span's content
  (TASK-167): `` [`@x` y] `` is no citation, `` [x `a;b` @c] `` is one."
  [inner]
  (let [segments (cite-segments inner)]
    (and (seq segments) (every? cite-key-index segments))))

(defn- cite-bracket-close-index
  "The absolute index within `s` (length `n`) of the `]` that closes the
  opening `[` at `open-index`, or nil if there is none. A backslash
  escapes the very next character (an escaped `\\]` does not close it).
  Deliberately does not track nested `[`/`]` pairs at all (like TASK-7's
  own heuristic, a documented, accepted scope reduction) -- no SPEC.md
  citation example ever nests a bracket inside another.

  The search crosses line breaks (TASK-165): a hard-wrapped citation group
  (`[see\\n@smith2020]`, `[@a;\\n@b]`) is the same group as its one-line
  form. TASK-9 had stopped it at a newline only to mirror
  `inline-math-closing-index`, and since TASK-160 that silently turned a
  wrapped group into author-in-text citations or cross-references inside
  literal brackets. `s` is one paragraph's inline text, so the search
  never leaves the paragraph: a blank line still ends it.

  An unescaped `[` met before the `]` ends the search with nil: nesting
  is unsupported anyway, and without this stop a stray unclosed `[` (a
  half-open interval `[0, 1)`) would pair with the first `]` anywhere
  later in the paragraph -- typically a link's, a `[^1]` footnote
  marker's or a `[Fig. 2]` -- and swallow that construct into one
  citation (TASK-165 review). It also ends each scan at the next `[`, so
  a paragraph full of unclosed `[` is no longer quadratic in its length.

  A code span or inline math span is stepped over whole (TASK-167): a `]`
  or `[` inside `` `a[0]` `` or `$f]$` is content of that span, exactly
  as flexmark's own left-to-right scan reads it, so it neither closes the
  bracket nor ends the search -- see `inline-span-end-index`."
  [^String s open-index n]
  (loop [j (inc open-index)]
    (cond
      (>= j n) nil
      (and (= (.charAt s j) \\) (< (inc j) n)) (recur (+ j 2))
      (= (.charAt s j) \[) nil
      (= (.charAt s j) \]) j
      :else (recur (inline-span-end-index s j n)))))

(declare attribute-group-end-index)

(def ^:private ^:dynamic *caption-parse*
  "True while `table-caption-and-attr` runs flexmark over a table caption
  line, whose trailing attribute group is the table's -- see
  `attributed-bracket?`."
  false)

(defn- attributed-bracket?
  "True if the citation-shaped bracket closing at index `close` of `s` (the
  inline input `p` is parsing) has an attribute group straight after its
  `]` that belongs to the bracket itself (TASK-175). Such a bracket is a
  Span (sec5.6) whatever its content, exactly as `[text]{...}` is one even
  when `text` matches a link reference definition (see `convert-
  children`): `parse-cite-bracket!` declines it, and flexmark's own
  bracket handling reads it as a bracket-only LinkRef plus that group,
  its content parsed in place -- in `[@doe]{.c}` the `@doe` is an
  author-in-text citation. An attributed bracketed citation is written
  with the Span around it, `[[@doe]]{.c}`.

  Not a cite carrying the group, or a Span around the bracketed cite: the
  .hdoc writer spells a Span whose content holds a citation (an undefined
  `![@doe][s]{.c}`, say) as `[!\\[@doe\\]\\[s\\]]{.c}`, and reading that as
  a bracketed citation would turn the document into a different one with
  no error, with no other spelling to avoid it.

  A group that ends a heading's text or a table caption line is not the
  bracket's but that block's own (`pop-trailing-attr-group`), so `# See
  [@doe]{#sec:x}` keeps its citation, as it always has.

  Whitespace between the `]` and the `{` -- spaces, tabs, a line break --
  still counts as straight after (TASK-184): flexmark attaches a group to
  the inline before it across whitespace when the group ends its
  paragraph, heading or link text, so `[s] {.c}` there is a Span, and
  `[@doe] {.c}` is one for the same reason. Mid-text (`x [s] {.c} y`) the
  group stays an orphan even when this is true, refused with TASK-191's
  message (`orphan-group-message`). The heading and caption
  exception reads the same way: `# See [@doe] {#sec:x}` keeps its
  citation, as `# See [s] {#sec:x}` gives its group to the heading."
  [^LightInlineParser p ^String s close]
  (when-let [end (attribute-group-end-index
                  s (loop [j (inc close)]
                      (if (and (< j (count s)) (Character/isWhitespace (.charAt s j))) (recur (inc j)) j)))]
    (not (and (or (instance? Heading (.getBlock p)) *caption-parse*)
              (str/blank? (subs s (inc end)))))))

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
  resolve at all, is untouched by this ever having run at all.

  A bracket with an attribute group straight after its `]` is declined
  too (TASK-175) -- see `attributed-bracket?`."
  [^LightInlineParser p]
  (let [^BasedSequence input (.getInput p)
        s (input-string (.getDocument p) input)
        n (.length input)
        i (.getIndex p)]
    (if-let [close (cite-bracket-close-index s i n)]
      (if (and (citation-bracket-content? (subs s (inc i) close))
               (not (attributed-bracket? p s close)))
        (do (.appendNode p (cite-bracket-node (.subSequence input (int i) (int (inc close)))))
            (.setIndex p (inc close))
            true)
        false)
      false)))

(defn citation-bracket-at?
  "True if the `[` at index `i` of `s` opens a bracket whose content reads
  as a bracketed citation (sec5.11) -- the content half of
  `parse-cite-bracket!`'s decision, over the same `cite-bracket-close-
  index` and `citation-bracket-content?`. The other half, an attribute
  group straight after the `]` (`attributed-bracket?`), needs the inline
  parser's own state and is left to the caller. Public because
  `haselnuss.emit.hdoc` must know when link text it writes would re-parse
  as a citation instead (TASK-198)."
  [^String s i]
  (boolean
   (when-let [close (cite-bracket-close-index s i (.length s))]
     (citation-bracket-content? (subs s (inc i) close)))))

;; --- Attributed brackets over a defined reference (TASK-190) ---------------
;;
;; `[r]{.c}` is a Span (sec5.6) even when `[r]: v` is defined -- see
;; `convert-children` -- but flexmark has already made it a *defined*
;; LinkRef by then, and a defined LinkRef is a link: when an outer
;; `[a [r]{.c}](u)` closes, `InlineParserImpl.parseCloseBracket` sees a
;; non-tentative LinkRef among the new Link's children and, since a link may
;; not hold a link, unlinks the Link and puts its children back in the
;; paragraph with a literal `](u)` after them (confirmed by decompiling
;; 0.64.8). The link is lost before `regroup-attributed-brackets` (TASK-184)
;; could see it, and with whitespace before the group (`[a [r] {.c}](u)`)
;; the attributes post-processor then trips over the orphaned Link and
;; throws a raw IllegalStateException.
;;
;; `parseCloseBracket` asks the registered `LinkRefProcessor`s before it
;; looks a bracket up among the definitions, so `attributed-ref-processor`
;; claims exactly that bracket -- a bracket-only, non-image `[label]` whose
;; label is defined and which `attributed-bracket?` says has a group of its
;; own -- and makes it an `attributed-ref-node` instead, which is not a link:
;; an outer link forms around it, and `convert-children` makes it the Span a
;; bracket-only LinkRef makes. An undefined label is left alone (flexmark's
;; tentative LinkRef never cancels an outer link, and TASK-184 regroups it),
;; and so is everything else, so no other bracket parses differently.
;;
;; The processor needs what `parse-cite-bracket!` gets handed -- the inline
;; input, the index past the `]`, the block, the open bracket -- but a
;; `LinkRefProcessor` is only given the document. Both are made per
;; document by the same `InlineParserImpl`, so `cite-bracket-extension-
;; factory` records that parser in the document's data under
;; `inline-parser-key`, and the processor reads it back.

(def ^:private ^NullableDataKey inline-parser-key
  "The document data key `cite-bracket-extension-factory` records the
  document's own `InlineParser` under, for `attributed-ref-processor`."
  (NullableDataKey. "haselnuss.parser/inline-parser"))

(definterface ^:private AttributedRefNodeTag)

(defn- attributed-ref-node
  "A fresh synthetic Node for the `bracket` (`[label]`) that
  `attributed-ref-processor` claims -- built like `highlight-node`, a
  `proxy` over `DelimitedNodeImpl` plus a marker interface, so
  `attributed-ref-node?` can tell it by exact class. flexmark moves the
  bracket's already-parsed content into it as children."
  ^DelimitedNodeImpl [^BasedSequence bracket]
  (let [n (.length bracket)]
    (proxy [DelimitedNodeImpl AttributedRefNodeTag]
           [(.subSequence bracket 0 1) (.subSequence bracket 1 (dec n)) (.subSequence bracket (dec n) n)])))

(def ^:private attributed-ref-class
  "The exact runtime class every `attributed-ref-node` shares -- see
  `math-inline-class`'s own docstring."
  (class (attributed-ref-node (BasedSequence/of "[x]"))))

(defn- attributed-ref-node? [node]
  (identical? attributed-ref-class (class node)))

(defn- defined-reference
  "The `Reference` the `bracket` (`[label]`, brackets included) names
  among `document`'s definitions, or nil -- the lookup `parseCloseBracket`
  itself makes for a bracket-only reference."
  ^Reference [^Document document ^BasedSequence bracket]
  (.get ^java.util.Map (.get Parser/REFERENCES document)
        (Escaping/normalizeReferenceChars bracket true)))

(defn- attributed-defined-ref?
  "True if the `bracket` that `document`'s inline parser has just
  closed is one `attributed-ref-processor` claims (see this section's
  comment): its opener is a `[`, not a `![` (a defined `![x]{.c}` stays
  the image TASK-169 makes) and has no bracket inside it (flexmark reads
  no bracket-only reference from it then), its label is defined, and
  `attributed-bracket?` -- TASK-175's rule for a citation-shaped bracket
  -- says the group after its `]` is its own."
  [^Document document ^BasedSequence bracket]
  (let [^InlineParser ip (.get inline-parser-key document)]
    (boolean
     (when ip
       (let [^Bracket opener (.getLastBracket ip)
             ^BasedSequence input (.getInput ip)
             close (dec (.getIndex ip))]
         (and opener (not (.isImage opener)) (not (.isBracketAfter opener))
              (< -1 close (.length input)) (= \] (.charAt input close))
              (defined-reference document bracket)
              (attributed-bracket? ip (input-string document input) close)))))))

(defn- trim-edge-text!
  "Trims the leading whitespace of `node`'s first child and the trailing
  whitespace of its last, when those are Text -- what flexmark's
  `collapseLinkRefChildren` does to a LinkRef's children, so `[ r ]{.c}`
  gives the same Span whether or not `r` is defined."
  [^Node node]
  (let [first-child (.getFirstChild node)
        last-child (.getLastChild node)]
    (if (identical? first-child last-child)
      (when (instance? Text first-child)
        (.setChars first-child (.trim (.getChars first-child))))
      (do (when (instance? Text first-child)
            (.setChars first-child (.trimStart (.getChars first-child))))
          (when (instance? Text last-child)
            (.setChars last-child (.trimEnd (.getChars last-child))))))))

(def ^:private attributed-ref-processor
  "The `LinkRefProcessorFactory` registered on `cm-parser` -- see this
  section's comment. Bracket nesting level 0 and no `!` prefix: it only
  ever looks at a plain `[label]`. `adjustInlineText`/`allowDelimiters`
  keep the bracket's content and its emphasis delimiters exactly as an
  ordinary link's."
  (reify LinkRefProcessorFactory
    (getWantExclamationPrefix [_ _] false)
    (getBracketNestingLevel [_ _] 0)
    (^LinkRefProcessor apply [_ ^Document document]
      (reify LinkRefProcessor
        (getWantExclamationPrefix [_] false)
        (getBracketNestingLevel [_] 0)
        (isMatch [_ bracket] (attributed-defined-ref? document bracket))
        (createNode [_ bracket] (attributed-ref-node bracket))
        (adjustInlineText [_ _ node] (.getChildChars ^Node node))
        (allowDelimiters [_ _ _ _] true)
        (updateNodeElements [_ _ node] (trim-edge-text! node))))))

(def ^:private cite-bracket-extension-factory
  "The `InlineParserExtensionFactory` registered on `cm-parser` for the `[`
  trigger character -- see `inline-math-extension-factory`'s own docstring
  for why `proxy` (not `reify`) is used here too. Applying it also records
  the document's inline parser for `attributed-ref-processor` (TASK-190)."
  (proxy [InlineParserExtensionFactory] []
    (getCharacters [] "[")
    (getAfterDependents [] #{})
    (getBeforeDependents [] #{})
    (affectsGlobalScope [] false)
    (apply [lp]
      (let [^LightInlineParser lp lp]
        (.set inline-parser-key (.getDocument lp) lp))
      (reify InlineParserExtension
        (finalizeDocument [_ _])
        (finalizeBlock [_ _])
        (parse [_ p] (parse-cite-bracket! p))))))

;; --- Highlights (TASK-122, doc-8 section 3, sec5.6) ------------------------
;;
;; `==marked text==` is a `:highlight` Inline (sec4.4). Unlike TASK-7's `$`
;; math and TASK-9's `@` cross-references/citations, this construct is NOT an
;; `InlineParserExtension`, and the reason is a requirement none of those
;; three have: a highlight's content is ORDINARY MARKUP -- emphasis, a link,
;; inline code, inline math, a cross-reference, a citation -- which has to be
;; parsed as such (AC #2). `LightInlineParser`, the only thing an
;; `InlineParserExtension` is handed, offers no way to recursively parse a
;; span of input back into inline nodes; every existing extension here reads
;; its matched span as an opaque string precisely because its content IS
;; opaque (raw TeX, a bare label, a citation key).
;;
;; `DelimiterProcessor` is flexmark's own mechanism for exactly this shape,
;; and it is the one `**strong**` and `*emph*` themselves are built on
;; (`EmphasisDelimiterProcessor`) -- so this is not a new mechanism, it is
;; the older and more ordinary of the two. It also gets the hard parts
;; right for free: delimiter runs are matched by CommonMark's own
;; left/right-flanking rules, the content between a matched pair is parsed
;; as inlines before this code ever sees it, and an UNMATCHED `==` stays
;; literal text (AC #6) rather than swallowing the rest of the paragraph.
;;
;; The two risks doc-8 pitfall 2 names are handled by that same machinery
;; rather than by anything here, which is why they are tested rather than
;; guarded:
;;
;; - A setext heading underline is a BLOCK-level construct, settled by the
;;   document parser before inline parsing runs at all, so no delimiter
;;   processor can reach one however long the run of `=`.
;; - `key=val` inside an attribute group is untouched, because a lone `=`
;;   is never a delimiter run: `getMinLength` is 2, enforced by flexmark
;;   before a `Delimiter` object exists at all. `key==val` is a separate
;;   matter and NOT this construct's doing -- flexmark's own attribute
;;   grammar rejects a bare `=` inside an unquoted value, `key=va=l`
;;   identically, with or without this processor registered (verified
;;   against a bare parser). Write `key="a==b"` for a value containing
;;   one, which works and is tested.
;;
;; `\==` is an ordinary CommonMark backslash escape (`=` is already in
;; `escapable-punctuation`), so the escaped `=` becomes literal text and the
;; single `=` left over never reaches `getMinLength` 2. The `.hdoc` emitter
;; escapes a literal `==` back out for the same reason (see
;; `haselnuss.emit.hdoc/escapes-as-highlight-marker?`).
;;
;; ONE THING THIS DOES COST, stated rather than discovered: a malformed
;; attribute group whose contents happen to contain a `==` run no longer
;; reaches `check-no-attribute-attempt!`. `a {key==val==} b` used to raise
;; `::malformed-attribute-group`; it now parses as prose with a highlight
;; in it. The diagnostic fires on a `{`-led run that flexmark left as
;; text, and a highlight consuming part of that run means there is no
;; longer a single text node with the whole `{...}` in it to notice.

(definterface ^:private HighlightNodeTag)

(defn- highlight-node
  "A fresh synthetic Node for one `==marked text==` construct, built the
  same way as `math-inline-node`/`cross-ref-node` -- a `proxy` over
  `DelimitedNodeImpl` plus this file's own zero-method marker interface,
  so `highlight-node?` can recognize one by exact class identity (see
  `math-inline-class`'s own docstring for why `instance?` would not do).

  Unlike those two, this node's CHILDREN carry its content: flexmark's
  own delimiter machinery moves the already-parsed inline nodes between
  the matched `==` pair into it (`process` below), so `.getText` is never
  read and `convert-inline` recurses through `convert-inlines` instead.

  `open-marker`/`close-marker` are the two matched `==` runs, and setting
  them is NOT optional bookkeeping: `moveNodesBetweenDelimitersTo`
  derives the node's own `chars` span from them, and a node left with an
  empty span crashes any later pass that reads its source offsets -- a
  highlight inside a table cell raised `StringIndexOutOfBoundsException`
  from flexmark's own table parser before this was set. Mirrors
  flexmark's `EmphasisDelimiterProcessor`, which builds its Emphasis/
  StrongEmphasis from `getTailChars`/`getLeadChars` the same way."
  ^DelimitedNodeImpl [^BasedSequence open-marker ^BasedSequence close-marker]
  (let [node (proxy [DelimitedNodeImpl HighlightNodeTag] [])]
    (.setOpeningMarker node open-marker)
    (.setClosingMarker node close-marker)
    node))

(def ^:private highlight-class
  "The exact runtime class every `highlight-node` instance shares -- see
  `math-inline-class`'s own docstring."
  (class (highlight-node BasedSequence/NULL BasedSequence/NULL)))

(defn- highlight-node? [node]
  (identical? highlight-class (class node)))

(def ^:private highlight-delimiter-length
  "How many `=` characters open and close a highlight. Two, not one: a
  single `=` is ordinary punctuation (`x = y`), and a setext heading
  underline is a whole line of them."
  2)

(defn- highlight-delimiter-processor
  "The `DelimiterProcessor` registered on `cm-parser` for `==` (see this
  section's own comment for why a delimiter processor rather than an
  `InlineParserExtension`).

  `canBeOpener`/`canBeCloser` defer to CommonMark's own left/right-
  flanking analysis, which flexmark has already computed and passes in --
  the same rule `*emph*` uses, so `a == b` (flanked by spaces on both
  sides) opens nothing while `==marked==` does. `getDelimiterUse` returns
  2 only when both runs are long enough, and 0 otherwise, which is how a
  delimiter processor declines a pair without consuming it.

  `process` builds the node and hands it to `moveNodesBetweenDelimitersTo`,
  flexmark's own splice: every already-parsed inline node between the two
  markers becomes a child of it. That is the whole reason nested markup
  works here with no code of its own.

  Built with `reify` rather than `proxy` (unlike this file's
  `InlineParserExtensionFactory`s, which need `proxy` for a covariant
  `apply`): `DelimiterProcessor` declares no overloaded or covariantly
  typed method, so `reify` is enough and costs no reflective dispatch."
  []
  (reify DelimiterProcessor
    (getOpeningCharacter [_] \=)
    (getClosingCharacter [_] \=)
    (getMinLength [_] highlight-delimiter-length)
    (getDelimiterUse [_ opener closer]
      (if (and (>= (.length opener) highlight-delimiter-length)
               (>= (.length closer) highlight-delimiter-length))
        highlight-delimiter-length
        0))
    (unmatchedDelimiterNode [_ _inline-parser _delimiter-run] nil)
    (canBeOpener [_ _before _after left-flanking _right-flanking
                  _before-is-punctuation _after-is-punctuation
                  _before-is-whitespace _after-is-whitespace]
      left-flanking)
    (canBeCloser [_ _before _after _left-flanking right-flanking
                  _before-is-punctuation _after-is-punctuation
                  _before-is-whitespace _after-is-whitespace]
      right-flanking)
    (skipNonOpenerCloser [_] false)
    (process [_ opener closer delimiters-used]
      (let [^BasedSequence open-marker (.getTailChars ^Delimiter opener delimiters-used)
            ^BasedSequence close-marker (.getLeadChars ^Delimiter closer delimiters-used)
            node (highlight-node open-marker close-marker)]
        (.moveNodesBetweenDelimitersTo ^Delimiter opener node ^Delimiter closer)
        ;; Set LAST, and set explicitly: `moveNodesBetweenDelimitersTo`
        ;; fills in the node's `text` but leaves its own `chars` the
        ;; empty sequence it was constructed with, and any later pass
        ;; that reads this node's source span off `chars` then fails on
        ;; a zero-length base -- flexmark's own table pre-processor
        ;; calls `getChildChars` and raised
        ;; `StringIndexOutOfBoundsException` for a highlight in a table
        ;; cell before this line existed.
        (.setChars ^Node node (.baseSubSequence open-marker
                                                (.getStartOffset open-marker)
                                                (.getEndOffset close-marker)))))))

;; --- Strikethrough (TASK-137, sec5.6) --------------------------------------
;;
;; `~~struck text~~` is a `:strike` Inline (sec4.4), GFM's own spelling. It is
;; the same shape as a highlight -- ordinary markup inside, an unmatched run
;; stays literal text -- so it is built exactly the same way, as a second
;; `DelimiterProcessor` (see the Highlights section above for why). It is
;; hand-rolled rather than flexmark's `StrikethroughExtension` because that
;; one lives in a separate artifact (`flexmark-ext-gfm-strikethrough`) that
;; would join the native-image reflection surface for the sake of one
;; delimiter, and its node type would still need converting here. A single
;; `~` is never a delimiter (`getMinLength` is 2), so `~/x` and `a ~ b`
;; stay prose. `:strike` has no `:attr` (sec4.4), so unlike a highlight a
;; following attribute group is not accepted.

(definterface ^:private StrikeNodeTag)

(defn- strike-node
  "A fresh synthetic Node for one `~~struck text~~` construct; see
  `highlight-node` for why the markers are set and why a `proxy` over
  `DelimitedNodeImpl` with a marker interface."
  ^DelimitedNodeImpl [^BasedSequence open-marker ^BasedSequence close-marker]
  (let [node (proxy [DelimitedNodeImpl StrikeNodeTag] [])]
    (.setOpeningMarker node open-marker)
    (.setClosingMarker node close-marker)
    node))

(def ^:private strike-class
  "The exact runtime class every `strike-node` shares -- see
  `math-inline-class`'s own docstring."
  (class (strike-node BasedSequence/NULL BasedSequence/NULL)))

(defn- strike-node? [node]
  (identical? strike-class (class node)))

(def ^:private strike-delimiter-length 2)

(defn- strike-delimiter-processor
  "The `DelimiterProcessor` registered on `cm-parser` for `~~`; see
  `highlight-delimiter-processor`, which this mirrors."
  []
  (reify DelimiterProcessor
    (getOpeningCharacter [_] \~)
    (getClosingCharacter [_] \~)
    (getMinLength [_] strike-delimiter-length)
    (getDelimiterUse [_ opener closer]
      (if (and (>= (.length opener) strike-delimiter-length)
               (>= (.length closer) strike-delimiter-length))
        strike-delimiter-length
        0))
    (unmatchedDelimiterNode [_ _inline-parser _delimiter-run] nil)
    (canBeOpener [_ _before _after left-flanking _right-flanking
                  _before-is-punctuation _after-is-punctuation
                  _before-is-whitespace _after-is-whitespace]
      left-flanking)
    (canBeCloser [_ _before _after _left-flanking right-flanking
                  _before-is-punctuation _after-is-punctuation
                  _before-is-whitespace _after-is-whitespace]
      right-flanking)
    (skipNonOpenerCloser [_] false)
    (process [_ opener closer delimiters-used]
      (let [^BasedSequence open-marker (.getTailChars ^Delimiter opener delimiters-used)
            ^BasedSequence close-marker (.getLeadChars ^Delimiter closer delimiters-used)
            node (strike-node open-marker close-marker)]
        (.moveNodesBetweenDelimitersTo ^Delimiter opener node ^Delimiter closer)
        (.setChars ^Node node (.baseSubSequence open-marker
                                                (.getStartOffset open-marker)
                                                (.getEndOffset close-marker)))))))

;; --- Attribute groups at the inline parser (TASK-168) ------------------------
;;
;; flexmark-ext-attributes' own inline extension tries `ATTRIBUTES_TAG` (its
;; `AttributeParsing` regex) at EVERY `{` the inline parser reaches. Java's
;; regex engine recurses once per repetition of that pattern's token loop, so a
;; `{x` followed by about a thousand space-separated words -- with or without a
;; `}` somewhere after them -- overflows the stack mid-parse, and the parse
;; died with a bare `StackOverflowError` no caller could locate or report.
;;
;; So this file registers the extension's `{` parser itself, in front of a
;; decision flexmark cannot get wrong that way: `attribute-group-closes?`, the
;; same language as `ATTRIBUTES_TAG` recognized by an iterative state set,
;; linear and stackless. Where it says no, flexmark's regex would have failed
;; too and its extension returned false, so this one returns false and the `{`
;; stays prose exactly as before -- now also for an unclosed run of any length.
;; Where it says yes, flexmark's own extension does the parsing, unchanged;
;; only a group that genuinely closes after a thousand-odd tokens can still
;; overflow there, and that one call -- nothing else in the parse -- is where
;; the overflow is caught and turned into a located `::attribute-group-too-
;; long` error. Not a bigger thread stack: that only moves the threshold.

(def ^:private group-open 1)
(def ^:private group-hash-start 2)
(def ^:private group-hash 4)
(def ^:private group-name 8)
(def ^:private group-after-token 16)
(def ^:private group-eq 32)
(def ^:private group-eq-space 64)
(def ^:private group-value 128)
(def ^:private group-single 256)
(def ^:private group-double 512)
(def ^:private group-quoted 1024)
(def ^:private group-sep 2048)

(def ^:private group-may-close
  "The states a `}` ends the group from: every state but the opening one
  (a group holds at least one token), a `#`/`.` with nothing after it yet,
  and the inside of a quoted value."
  (bit-or group-hash group-name group-after-token group-eq group-eq-space
          group-value group-quoted group-sep))

(defn- group-ws?
  "Java regex `\\s`, which `ATTRIBUTES_TAG` is compiled without
  UNICODE_CHARACTER_CLASS for: ASCII whitespace only."
  [c]
  (case c (\space \tab \newline \u000B \formfeed \return) true false))

(defn- group-value-char?
  "`[^\"'=<>{}`\\x00-\\x20]`: a character of an unquoted value or of an
  `#id`/`.class` token."
  [c]
  (and (> (int c) 0x20) (neg? (.indexOf "\"'=<>{}`" (int c)))))

(defn- group-name-start? [c]
  (or (<= (int \a) (int c) (int \z)) (<= (int \A) (int c) (int \Z)) (= c \_) (= c \:)))

(defn- group-name-char? [c]
  (or (group-name-start? c) (<= (int \0) (int c) (int \9)) (= c \.) (= c \-)))

(defn- group-step
  "The state set after reading `c` from state set `st`. Each state is a
  point in `ATTRIBUTES_TAG`'s `{TOKEN [= VALUE] (WS TOKEN [= VALUE])* WS*}`:
  `group-after-token` holds after a token and whitespace, which may yet be
  the `\\s*` before an `=` or the separator before the next token, and
  `group-eq-space` after an `=` and whitespace, where the value is optional
  so a token may start instead -- the two places the regex backtracks."
  ^long [^long st c]
  (let [in? (fn [states] (not (zero? (bit-and st (long states)))))
        ws (group-ws? c)
        value-char (group-value-char? c)
        token-from (bit-or group-open group-after-token group-eq-space group-sep)]
    (cond-> 0
      (and ws (in? group-open)) (bit-or group-open)
      (and (or (= c \#) (= c \.)) (in? token-from)) (bit-or group-hash-start)
      (and value-char (in? (bit-or group-hash-start group-hash))) (bit-or group-hash)
      (and (group-name-start? c) (in? token-from)) (bit-or group-name)
      (and (group-name-char? c) (in? group-name)) (bit-or group-name)
      (and ws (in? (bit-or group-hash group-name group-after-token))) (bit-or group-after-token)
      (and (= c \=) (in? (bit-or group-hash group-name group-after-token))) (bit-or group-eq)
      (and ws (in? (bit-or group-eq group-eq-space))) (bit-or group-eq-space)
      (and value-char (in? (bit-or group-eq group-eq-space group-value))) (bit-or group-value)
      (and (= c \') (in? (bit-or group-eq group-eq-space))) (bit-or group-single)
      (and (not= c \') (in? group-single)) (bit-or group-single)
      (and (= c \") (in? (bit-or group-eq group-eq-space))) (bit-or group-double)
      (and (not= c \") (in? group-double)) (bit-or group-double)
      (or (and (= c \') (in? group-single)) (and (= c \") (in? group-double))) (bit-or group-quoted)
      (and ws (in? (bit-or group-value group-quoted group-sep))) (bit-or group-sep))))

(defn- attribute-group-end-index
  "The index within `s` of the `}` that closes the attribute group opening
  at index `i`, or nil if `ATTRIBUTES_TAG` does not match there -- see
  `attribute-group-closes?`, the yes/no form of the same question.
  `parse-cite-bracket!` needs the index itself, to tell a group that ends
  its heading or caption from one with more text after it."
  [^CharSequence s i]
  (let [n (.length s)]
    (when (and (< i n) (= \{ (.charAt s i)))
      (loop [j (inc i) st (long group-open)]
        (cond
          (or (zero? st) (>= j n)) nil
          (and (= \} (.charAt s j)) (not (zero? (bit-and st (long group-may-close))))) j
          :else (recur (inc j) (group-step st (.charAt s j))))))))

(defn- attribute-group-closes?
  "True if `ATTRIBUTES_TAG` matches at index `i` of `s` -- the `{` there
  opens what flexmark reads as an attribute group. The same answer as
  `.lookingAt` with flexmark-ext-attributes 0.64.8's own `ATTRIBUTES_TAG`
  from `i` (`haselnuss.parser-test` reads that field reflectively and
  checks the two against each other, so a flexmark upgrade that changes
  the pattern fails there), but by one
  pass over a set of states rather than a backtracking regex, so its depth
  does not grow with the input. Reads only as far as the group's `}`, or
  until no state is left: in prose that is usually a few characters."
  [^CharSequence s i]
  (some? (attribute-group-end-index s i)))

(def ^:private ^:dynamic *chunk-parse*
  "True while `math-chunk-items` runs flexmark over a chunk's own text, so
  an error raised inside that parse can give its position as a chunk line
  and column. Unbound (false) for the mini-documents conversion parses
  later (a citation's prefix, a table caption), whose lines are not the
  chunk's."
  false)

(defn- attribute-group-too-long
  "The located `ex-info` for a `{` at index `i` of `input` whose attribute
  group overflowed flexmark's regex -- see this section's comment. `:text`
  is the start of its line from the `{`, for `chunk-body-line` to find."
  [^BasedSequence input i cause]
  (let [base (.toString (.getBaseSequence input))
        offset (.getStartOffset (.subSequence input (int i) (int (inc i))))
        line-start (inc (.lastIndexOf base "\n" (int (dec offset))))
        line-end (let [k (.indexOf base "\n" (int offset))] (if (neg? k) (.length base) k))]
    (ex-info "attribute group too long to parse: a `{` that the attribute syntax would read up to a `}` far later (write `\\{` if it is prose)"
             (cond-> {:type ::attribute-group-too-long
                      :text (subs base offset (min line-end (+ offset 40)))}
               *chunk-parse* (assoc :chunk-line (inc (count (filter #{\newline} (subs base 0 offset))))
                                    :chunk-column (inc (.codePointCount base (int line-start) (int offset)))))
             cause)))

(def ^:private attributes-extension-factory
  "The `InlineParserExtensionFactory` registered on `cm-parser` for `{` in
  place of flexmark-ext-attributes' own (see this section's comment), whose
  extension it wraps. `proxy`, not `reify`: see
  `inline-math-extension-factory`."
  (let [flexmark-factory (AttributesInlineParserExtension$Factory.)]
    (proxy [InlineParserExtensionFactory] []
      (getCharacters [] "{")
      (getAfterDependents [] #{})
      (getBeforeDependents [] #{})
      (affectsGlobalScope [] false)
      (apply [lp]
        (let [^InlineParserExtension flexmark (.apply flexmark-factory ^LightInlineParser lp)]
          (reify InlineParserExtension
            (finalizeDocument [_ ip] (.finalizeDocument flexmark ip))
            (finalizeBlock [_ ip] (.finalizeBlock flexmark ip))
            (parse [_ p]
              (let [^LightInlineParser p p
                    input (.getInput p)
                    i (.getIndex p)]
                (and (attribute-group-closes? input i)
                     (try (.parse flexmark p)
                          (catch StackOverflowError e
                            (throw (attribute-group-too-long input i e)))))))))))))

(defn- build-cm-parser
  "A flexmark Parser with every construct `cm-parser`'s docstring lists,
  and pipe tables only when `tables?` -- see `cm-parser` and
  `fragment-parser`, the two it builds."
  ^Parser [tables?]
  (.build (.customDelimiterProcessor
           (.customDelimiterProcessor
            (.customInlineParserExtensionFactory
             (.customInlineParserExtensionFactory
              (.customInlineParserExtensionFactory
               ;; TASK-168: `AttributesExtension/extend`, by hand -- its
               ;; post-processor, and `attributes-extension-factory` in
               ;; place of its inline parser. Its `parserOptions` only
               ;; adjusts fenced-code-info options this parser never sets.
               (.customInlineParserExtensionFactory
                (.postProcessorFactory
                 (.linkRefProcessorFactory
                  (Parser/builder (doto (MutableDataSet.)
                                    ;; TASK-208: no MultiMarkdown `[caption]`
                                    ;; line -- see this namespace's docstring.
                                    (.set TablesExtension/WITH_CAPTION false)
                                    (.set Parser/EXTENSIONS
                                          (if tables?
                                            (java.util.List/of (TablesExtension/create)
                                                               (FootnoteExtension/create))
                                            (java.util.List/of (FootnoteExtension/create))))))
                  attributed-ref-processor)
                 (AttributesNodePostProcessor$Factory.))
                attributes-extension-factory)
               inline-math-extension-factory)
              cross-ref-cite-extension-factory)
             cite-bracket-extension-factory)
            (highlight-delimiter-processor))
           (strike-delimiter-processor))))

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
  through TASK-9, needs no hand-rolled recognition of its own), and
  TASK-122's `==marked text==` highlights (sec5.6), the one construct
  here registered as a `DelimiterProcessor` rather than an
  `InlineParserExtension` -- see `highlight-delimiter-processor` and the
  section comment above it for why its content being ORDINARY MARKUP is
  what settles that: the
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
  whenever it does appear. One `LinkRefProcessor` is registered too,
  `attributed-ref-processor` (TASK-190), so an attributed bracket over a
  defined reference does not cost an enclosing link its link. Parser
  instances are immutable and safe to reuse
  (flexmark's own documented usage pattern). Hinted `^Parser` so `parse`'s
  `(.parse cm-parser ...)` call resolves without reflection."
  (build-cm-parser true))

(def ^:private ^Parser fragment-parser
  "`cm-parser` without pipe tables, built once, for `mini-parse-inlines`:
  a citation's prefix/suffix sits inside a paragraph, so it can never be
  a table, but parsed on its own its first line starts the paragraph,
  and any later delimiter row (`|---|`) then makes a table of it --
  escaping the first line does not help, `\\| a |` is still a row
  (TASK-171). Leaving the extension out, rather than escaping the row,
  keeps a code span that runs over the row intact (TASK-171 review)."
  (build-cm-parser false))

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

(defn- with-title
  "`node` (a `:link` or `:image` Inline) with `:title` set to the
  backslash-unescaped text of `title` (a flexmark BasedSequence, the
  `\"Title\"` of `[text](url \"Title\")` or of a reference definition),
  when there is one. An absent or empty title leaves `:title` OUT rather
  than `\"\"`: the key is optional in `haselnuss.ast`, and a node with no
  title stays exactly the shape it always had (TASK-140)."
  [node ^BasedSequence title]
  (let [text (unescape-backslashes (.toString title))]
    (cond-> node
      (seq text) (assoc :title text))))

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
            (:emph :strong :strike :small-caps :sub :sup :span :link :highlight)
            (alt-text (:inlines inline))
            :code (:text inline)
            :image (:alt inline)
            :math-inline (:tex inline)
            ;; The authored spelling of each, since neither carries text
            ;; of its own until the resolver runs: an alt text is fixed
            ;; at parse time and the resolver never revisits it.
            :cross-ref (str "@" (:label inline))
            :book-ref (str "@{" (:ref inline) "}")
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

(defn- join-inlines
  "Inlines `a` followed by Inlines `b`, with a `:str` ending `a` and a
  `:str` starting `b` merged into one (TASK-160). Text with no space
  between is one word, and one `:str` -- which is what flexmark gives the
  same characters when they arrive as a single text run, as they do when
  the `.hdoc` writer escapes an undefined bracket's `[`/`]` (`x\\[$i$\\]`
  re-parses with `x[` as one word). Without this, `x[$i$]` is `x` `[`
  `$i$` ... and does not round-trip."
  [a b]
  (let [last-a (peek a)
        first-b (first b)]
    (if (and (= :str (:t last-a)) (= :str (:t first-b)))
      (-> (pop a)
          (conj (assoc last-a :text (str (:text last-a) (:text first-b))))
          (into (subvec b 1)))
      (into a b))))

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

(defn- backslash-in-token!
  "Throws `ex-info` (`:type error-type`, carrying `:token`) if `s` -- the
  `what` part (\"an id\", \"a class\", \"a key\", \"an unquoted value\") of
  the attribute token `token` -- contains a backslash (TASK-197, sec5.3).
  A token has no escapes: flexmark keeps the backslash of `{#x\\}` in the
  id, where it would reach a LaTeX label or a cross-reference as a broken
  one rather than as a message. Quote a value that needs a backslash."
  [what ^String s token error-type]
  (when (str/includes? s "\\")
    (throw (ex-info (str "malformed attribute group: " what " cannot contain a backslash"
                         " (an attribute token has no escapes; quote a value that needs one)")
                    {:type error-type :token token}))))

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
  unlabeled prop or being dropped (AC #4).

  A backslash in an id, a class, a key or an UNQUOTED value is refused the
  same way (`backslash-in-token!`, TASK-197); a quoted value keeps its
  backslashes verbatim, as before."
  [^AttributeNode node]
  (let [token (.toString (.getChars node))
        value (.toString (.getValue node))]
    (cond
      (.isId node) (do (backslash-in-token! "an id" value token ::malformed-attributes) [:id value])
      (.isClass node) (do (backslash-in-token! "a class" value token ::malformed-attributes) [:class value])
      (not (str/blank? (.toString (.getAttributeSeparator node))))
      (let [k (.toString (.getName node))]
        (backslash-in-token! "a key" k token ::malformed-attributes)
        (when (str/blank? (.toString (.getOpeningMarker node)))
          (backslash-in-token! "an unquoted value" value token ::malformed-attributes))
        [:prop k value])
      :else
      (throw (ex-info "malformed attribute group: token is not #id, .class, or key=val"
                      {:type ::malformed-attributes
                       :token token})))))

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

(def ^:private unescaped-attr-attempt-re
  "`attr-attempt-re` for raw inline source, where backslash escapes are
  still in the text: only a `{` that is NOT itself escaped -- one behind
  an even run of backslashes, none included, so `\\\\{.c` (an escaped
  backslash, then a bare brace) still counts -- is an attempt. A `\\{` is
  a literal brace like any other CommonMark escape (sec2.4), and flexmark
  already leaves `\\{.c}` as plain text rather than splitting a group out
  of it; before TASK-189 this check read it as an unclosed group all the
  same and refused it, so a literal `{.c}` -- which the `.hdoc` emitter
  writes as `\\{.c}` -- could never be written back."
  #"(?<!\\)(?:\\\\)*\{(?:[#.]|[A-Za-z_][\w-]*=)")

(defn- check-no-attribute-attempt!
  "Throws `ex-info` (`:type ::malformed-attributes`) if raw source text
  `s` still contains what looks like an attempted attribute group (see
  `attr-attempt-re`) behind an unescaped `{` (`unescaped-attr-attempt-re`,
  TASK-189: `\\{.c}` is literal text). `AttributesExtension` splits any group it can fully
  parse out of its surrounding Text node entirely (confirmed via REPL:
  the successfully-parsed span is gone from what remains), so by the time
  `s` is a Text node's own content, any such match is one flexmark
  *tried* to parse here and left as-is -- either unclosed (no `}` before
  this run of text ends) or a group it rejected outright (e.g. one
  containing a token that itself is not valid syntax at all) -- never a
  case this parser should silently pass through as prose (AC #4)."
  [^String s]
  (when (re-find unescaped-attr-attempt-re s)
    (throw (ex-info "malformed attribute group: unclosed, or invalid and rejected outright"
                    {:type ::malformed-attributes :text s}))))

(defn attribute-group-at?
  "True if the `{` at index `i` of `s` (raw inline source, backslash
  escapes still in it) would be read by flexmark as an attribute group --
  so `haselnuss.emit.hdoc` can tell a literal `{` that needs a backslash
  (`{x}`, which re-parses as a group and is refused) from one that is
  prose (`{}`, `{1}`, `{x,y}`, an unclosed `{x`), TASK-161. Like
  `reference-at?`, the emitter's half of a decision the parser makes --
  and the very check the parser makes it with, `attribute-group-closes?`
  (TASK-172): stackless, so a `{x` before a run of any length gets an
  answer, never a `StackOverflowError`. Wider than `attr-attempt-re`: a
  bare name is a valid token to flexmark (`{x}`), though not to
  Haselnuss, and its whitespace runs cross a line break."
  [^String s i]
  (attribute-group-closes? s i))

(defn attribute-attempt-at?
  "True if the `{` at index `i` of `s` (raw inline source) opens what
  `check-no-attribute-attempt!` reads as an attempted attribute group --
  `{#`, `{.` or `{key=` (`attr-attempt-re`) -- whether or not it closes.
  Left bare and unclosed (`{.c`), such a brace is refused; escaped, it is
  literal text (TASK-189). So `haselnuss.emit.hdoc` escapes a literal `{`
  that this or `attribute-group-at?` holds for."
  [^String s i]
  (let [m (re-matcher attr-attempt-re s)]
    (.region m (int i) (.length s))
    (.lookingAt m)))

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
                  (with-meta {:t :section
                              :level (:level top)
                              :heading (:heading top)
                              :blocks (:blocks top)
                              :attr (:attr top)}
                    (meta (:source top)))))))

(defn- open-section
  "Closes every open section at `level` or deeper, then pushes a new open
  section at `level` with heading Inlines `heading` and Attr `attr`.
  `source` is an empty map whose metadata is the heading's source position
  (TASK-143), or nil."
  [stack level attr heading source]
  (let [stack (loop [s stack]
                (if (and (> (count s) 1) (>= (:level (peek s)) level))
                  (recur (close-top s))
                  s))]
    (conj stack {:level level :attr attr :heading heading :blocks [] :source source})))

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
                          (open-section stack (:level item) (:attr item) (:heading item) (:source item))
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

;; --- Attributed brackets flexmark flattened (TASK-184) ----------------------
;;
;; When an inline Link forms -- and any LinkRef or ImageRef too --
;; flexmark's `InlineParserImpl.parseCloseBracket` calls
;; `collapseLinkRefChildren` on it, which turns every LinkRef child (and
;; every undefined ImageRef child) back into text merged with its
;; neighbours (confirmed by decompiling 0.64.8): a link may not hold a
;; link. A Span is not a link, but `[text]{...}` is a LinkRef until
;; `convert-children` reads it, so in `[a [s]{.c}](u)` the Link's children
;; arrive as the text `a [s]` and a group with nothing to attach to; a
;; citation-shaped bracket `parse-cite-bracket!` declined (`[See
;; [@doe]{.c}](u)`) the same way, its `@doe` left between the text pieces.
;; A bracket holding a span, `[b [c]{.x}]{.y}`, never forms at all for the
;; same reason, and leaves the same shape.
;;
;; The bracket is still in the source, so `regroup-attributed-brackets`
;; finds it again: it splits the text at its unescaped brackets, matches
;; them the way flexmark does (a `]` closes the latest open `[`), and makes
;; a bracket whose `]` the group follows a Span over what lies between.
;; flexmark has already parsed that content into nodes -- the citation,
;; emphasis, code -- so the Span keeps them. Every other bracket goes back
;; into the text it came from, unchanged.

(defn- bracket-pieces
  "The text of flexmark `Text` `node` split around each unescaped `[` and
  `]` -- an unescaped `!` straight before a `[` goes with it, as flexmark
  opens an image there -- as `{:node t :origin node :kind k}`, `t` a
  detached `Text` over the piece's own source span and `k` one of `:open`,
  `:image-open`, `:close` or `:text`."
  [^Text node]
  (let [text (.getChars node)
        s (.toString text)
        n (count s)
        piece (fn [from to kind]
                {:node (Text. (.subSequence text (int from) (int to))) :origin node :kind kind})
        cut (fn [out start i to kind]
              (cond-> out
                (< start i) (conj (piece start i :text))
                true (conj (piece i to kind))))]
    (loop [i 0 start 0 out []]
      (if (>= i n)
        (cond-> out (< start n) (conj (piece start n :text)))
        (let [c (.charAt s i)]
          (cond
            (= c \\) (recur (+ i 2) start out)
            (and (= c \!) (< (inc i) n) (= \[ (.charAt s (inc i))))
            (recur (+ i 2) (+ i 2) (cut out start i (+ i 2) :image-open))
            (= c \[) (recur (inc i) (inc i) (cut out start i (inc i) :open))
            (= c \]) (recur (inc i) (inc i) (cut out start i (inc i) :close))
            :else (recur (inc i) start out)))))))

(defn- bracket-items
  "`children` as the items `regroup-attributed-brackets` matches over:
  each `Text` as its `bracket-pieces`, a `TextBase` as its own children's
  items (it is only flexmark's wrapper, see `convert-inline`), an
  AttributesNode as `:attrs`, anything else as an opaque `:node`."
  [children]
  (mapcat (fn [^Node c]
            (cond
              (instance? Text c) (bracket-pieces c)
              (instance? TextBase c) (bracket-items (.getChildren c))
              (instance? AttributesNode c) [{:node c :kind :attrs}]
              :else [{:node c :kind :node}]))
          children))

(defn- items->nodes
  "Items back to what `convert-children` converts: adjacent pieces of one
  `Text` merged again, a piece run covering a whole `Text` as that very
  node, and a regrouped bracket as an `::attributed-bracket` map."
  [items]
  (let [runs (reduce (fn [acc {:keys [origin] :as it}]
                       (let [prev (peek acc)
                             ^Node prev-node (:node prev)
                             ^Node node (:node it)]
                         (if (and origin (identical? origin (:origin prev))
                                  (= (.getEndOffset prev-node) (.getStartOffset node)))
                           (conj (pop acc)
                                 (assoc prev :node (Text. (.baseSubSequence (.getChars prev-node)
                                                                            (.getStartOffset prev-node)
                                                                            (.getEndOffset node)))))
                           (conj acc it))))
                     [] items)]
    (mapv (fn [{:keys [kind ^Node node ^Node origin] :as it}]
            (cond
              (= kind :span) {::attributed-bracket true
                              :open (:node (:open it))
                              :image? (= :image-open (:kind (:open it)))
                              :content (items->nodes (:content it))
                              :close (:node (:close it))
                              :attrs (:attrs it)}
              (and origin (= (.getStartOffset node) (.getStartOffset origin))
                   (= (.getEndOffset node) (.getEndOffset origin)))
              origin
              :else node))
          runs)))

(defn- trim-edge-items
  "`items` (a regrouped bracket's content) with the leading whitespace of a
  first `:text` piece and the trailing whitespace of a last one trimmed --
  what flexmark's `collapseLinkRefChildren` does to the children of a
  bracket it does form, so `[a [ r ]{.c}](u)` holds the Span(r) that
  `[ r ]{.c}` is (TASK-190 review). A line break is a node of its own and
  stays, as it does there."
  [items]
  (let [trim (fn [items idx f]
               (let [it (get items idx)]
                 (if (= :text (:kind it))
                   (assoc items idx (assoc it :node (Text. ^BasedSequence (f (.getChars ^Node (:node it))))))
                   items)))]
    (if (empty? items)
      items
      (-> (vec items)
          (trim 0 #(.trimStart ^BasedSequence %))
          (trim (dec (count items)) #(.trimEnd ^BasedSequence %))))))

(defn- regroup-attributed-brackets
  "`children` with each bracket flexmark flattened into text ahead of its
  own attribute group (see this section's comment) made an
  `::attributed-bracket` again, or `children` itself when there is none --
  only an AttributesNode straight after text can be one, and anything
  else is converted exactly as before."
  [children]
  (if-not (some (fn [[a b]] (and (instance? AttributesNode b)
                                 (or (instance? Text a) (instance? TextBase a))))
                (map vector children (rest children)))
    children
    (let [items (vec (bracket-items children))
          n (count items)
          out (loop [i 0 out [] stack ()]
                (if (>= i n)
                  out
                  (let [it (nth items i)
                        nxt (get items (inc i))]
                    (case (:kind it)
                      (:open :image-open) (recur (inc i) (conj out it) (conj stack (count out)))
                      :close (if (and (seq stack) (= :attrs (:kind nxt)))
                               (let [o (peek stack)]
                                 (recur (+ i 2)
                                        (conj (subvec out 0 o) {:kind :span :open (nth out o)
                                                                :content (trim-edge-items (subvec out (inc o)))
                                                                :close it :attrs (:node nxt)})
                                        (pop stack)))
                               (recur (inc i) (conj out it) (if (seq stack) (pop stack) stack)))
                      (recur (inc i) (conj out it) stack)))))]
      (if (some #(= :span (:kind %)) out)
        (items->nodes out)
        children))))

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
    - a reference-style `ImageRef` (full `![x][r]`, collapsed `![x][]` or
      shortcut `![x]`) -> `attach-link-ref-attr` too (TASK-169): a defined
      one becomes the `:image` an inline `![x](u){...}` would, an
      undefined one TASK-166's fallback in an attributed Span;
    - an `Image` or `Link` (already has its own `:attr` in the AST) ->
      merges the parsed Attr directly into that node's own `:attr` (AC #2,
      image case, and by the same mechanism, Link);
    - a `Code` inline span (has no `:attr` field in the AST at all, per
      `haselnuss.ast`/SPEC.md sec4.4) -> wraps it in a Span whose sole
      Inline is that Code node and whose Attr is the parsed group (AC #2,
      code-span case).
  A `==highlight=={...}` (TASK-122) merges the group directly into the
  `:highlight`'s own `:attr`, the same way an Image's or a Link's does
  and for the same reason: sec4.4 gives it an `Attr` of its own, so it
  needs no wrapping Span.

  A bracket flexmark flattened back into text ahead of its group -- a
  span inside link text, `[a [s]{.c}](u)`, or one holding a span --
  is found again first (`regroup-attributed-brackets`, TASK-184) and
  makes the same Span a bracket-only LinkRef does.

  Anything else immediately followed by an AttributesNode (e.g.
  `**bold**{.x}`) is deliberately left alone here, falling through to
  `convert-inline`'s `::unsupported-node` error on the `AttributesNode`
  itself, worded by `orphan-group-message` (TASK-191) -- not a
  silently-ignored no-op, consistent with this file's established
  error-handling ethos."
  [^Document document active-footnotes children]
  (let [children (regroup-attributed-brackets (vec children))
        n (count children)]
    (loop [i 0 acc []]
      (if (>= i n)
        acc
        (let [child (nth children i)
              nxt (when (< (inc i) n) (nth children (inc i)))
              attrs-node (when (instance? AttributesNode nxt) nxt)]
          (cond
            ;; A bracket and its group flexmark flattened into text
            ;; (TASK-184): the same Span a bracket-only LinkRef makes, and
            ;; for `![` the same one an undefined ImageRef does, its `![`
            ;; and `]` literal around the content.
            (::attributed-bracket child)
            (let [{:keys [open close content image? attrs]} child]
              (recur (inc i)
                     (conj acc (at-node open
                                        {:t :span
                                         :inlines (convert-children document active-footnotes
                                                                    (if image? (concat [open] content [close]) content))
                                         :attr (parse-attr-group attrs)}))))

            ;; `attributed-ref-node`: a bracket-only `[r]{.c}` over a
            ;; defined `r` (TASK-190), the same Span.
            (and attrs-node (or (attributed-ref-node? child)
                                (and (instance? LinkRef child) (bracket-only-link-ref? child))))
            (recur (+ i 2)
                   (conj acc (at-node child
                                      {:t :span
                                       :inlines (convert-children document active-footnotes (.getChildren ^Node child))
                                       :attr (parse-attr-group attrs-node)})))

            ;; An ImageRef (`![x][r]`, `![x][]`, `![x]`) takes the same
            ;; path as a full/collapsed LinkRef (TASK-169): an image has no
            ;; bracket-only Span form to compete with.
            (and attrs-node (or (instance? LinkRef child) (instance? ImageRef child)))
            (recur (+ i 2)
                   (into acc (attach-link-ref-attr document active-footnotes child (parse-attr-group attrs-node))))

            ;; A `:highlight` carries an `:attr` of its own (sec4.4), so
            ;; `==text=={.important #hl:a}` merges directly into it, the
            ;; same way an Image's or a Link's group does -- no wrapping
            ;; Span, unlike a Code span or a bracket-only LinkRef.
            (and attrs-node (or (instance? Image child) (instance? Link child)
                                (highlight-node? child)))
            (recur (+ i 2)
                   (conj acc (assoc (first (convert-inline document active-footnotes child))
                                    :attr (parse-attr-group attrs-node))))

            (and attrs-node (instance? Code child))
            (recur (+ i 2)
                   (conj acc (at-node child
                                      {:t :span
                                       :inlines (convert-inline document active-footnotes child)
                                       :attr (parse-attr-group attrs-node)})))

            ;; `join-inlines`, not `into`: an undefined bracket's literal
            ;; `[`/`]` is one word with prose touching it (`x[$i$]`),
            ;; TASK-160.
            :else
            (recur (inc i) (join-inlines acc (convert-inline document active-footnotes child)))))))))

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

(defn- undefined-link-ref
  "The Inlines for a LinkRef `node` whose reference is undefined (see
  `resolve-reference`) -- `[see @{bible:x}]`, `[$x^2$]`, `[foo][nope]`:
  its brackets (and any `[ref]`) kept as literal text around its OWN
  converted children (TASK-160).

  flexmark has already parsed the bracket's content into those children
  -- a cross-reference, a citation, inline math, a book reference,
  emphasis -- so converting them keeps every one, where `literal-ref-text`
  re-read the whole span as prose and flattened them all. What lies
  outside the children is the bracket syntax itself, taken from the raw
  source on each side and unescaped like any other text. That text is
  split by `text->inlines` like any other, because it is not always bare
  syntax: flexmark leaves a bracket's edge whitespace out of the children,
  so `[ foo ]` has `\"[ \"` and `\" ]\"` outside them, which must stay
  `:space` nodes. `join-inlines` then makes a bracket one word with the
  text it touches, so a plain `[foo]` is still the single `:str`
  `\"[foo]\"`, exactly what a re-parse of the escaped `\\[foo\\]` gives.

  An undefined ImageRef -- `![$x$][nope]`, `![a *b*]` -- takes the same
  path (TASK-166): its children are its alt text and its span starts at
  the `!`, so the leading syntax is `![` and nothing else changes.

  A LinkRef/ImageRef with no children at all has nothing to keep and
  falls back to `literal-ref-text`."
  [^Document document active-footnotes ^Node node]
  (let [first-child (.getFirstChild node)
        last-child (.getLastChild node)]
    (if (nil? first-child)
      (literal-ref-text node)
      (let [base (.getBaseSequence (.getChars node))
            raw (fn [from to]
                  (text->inlines (unescape-backslashes (str (.subSequence base (int from) (int to))))))]
        (-> (raw (.getStartOffset node) (.getStartOffset first-child))
            (join-inlines (convert-inlines document active-footnotes node))
            (join-inlines (raw (.getEndOffset last-child) (.getEndOffset node))))))))

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
  for a Code span (which likewise has no `:attr` field of its own).

  A reference-style ImageRef `child` -- full `![x][r]`, collapsed `![x][]`
  or shortcut `![x]` -- takes the same two paths (TASK-169): a defined one
  is the `:image` `convert-inline` makes for it with `attr` set, which is
  exactly what an inline `![x](a.png){...}` gives, so a lone one in a
  paragraph is promoted to a figure the same way (see `figure-caption`);
  an undefined one is TASK-166's literal fallback in an `attr`-bearing
  Span, like an undefined LinkRef's. Either way the defined node is
  converted by `convert-inline` itself, so its link/image branch stays
  the one place that shape is built."
  [^Document document active-footnotes ^RefNode child attr]
  (if (resolve-reference document child)
    [(assoc (first (convert-inline document active-footnotes child)) :attr attr)]
    ;; `at-node`: the Span is a node this conversion synthesizes, so it
    ;; takes the reference's own line, as a bracket-only Span does.
    [(at-node child {:t :span :inlines (undefined-link-ref document active-footnotes child) :attr attr})]))

;; --- Citation item parsing (TASK-9, sec5.11) --------------------------------
;;
;; `cite-bracket-node` (see that section, above) stores its bracket's raw,
;; still-unparsed inner text; the actual per-`;`-segment parsing happens
;; here, once `convert-inline` reaches it, since it needs `convert-inlines`
;; (already in scope by this point in the file) to turn a segment's own
;; prefix/suffix text into real Inlines.

(def ^:private block-line-re
  "A line that is only a setext-heading underline (one run of `=` or `-`)
  or a thematic break (three or more of one of `*`/`-`/`_`, spaces
  allowed between), capturing its indent (group 1) and the rest (group
  2)."
  #"([ \t]*)((?:=+|-+|(?:\*[ \t]*){3,}|(?:-[ \t]*){3,}|(?:_[ \t]*){3,})[ \t]*)")

(defn- guard-last-line
  "`s` with a backslash before the first character of its last line when
  `s` has more than one line and that line matches `block-line-re` -- see
  `mini-parse-inlines`. A backslash before punctuation reads back as the
  bare character, so this changes no text, only what the line can start."
  [^String s]
  (let [nl (.lastIndexOf s "\n")]
    (if-let [[_ indent body] (when (>= nl 0) (re-matches block-line-re (subs s (inc nl))))]
      (str (subs s 0 (inc nl)) indent "\\" body)
      s)))

(defn- guard-first-line
  "`s` (already left-trimmed) with a backslash before what its first line
  opens with -- the `.`/`)` after a run of 1-9 digits (an ordered-list
  marker's own digits cannot be escaped), else EVERY character of its
  leading run of one ASCII punctuation character, else `s` unchanged --
  see `mini-parse-inlines`. The whole run, not just its first character:
  escaping one backtick of `` ``` `` would leave a `` `` `` run that can
  pair with a later one into a code span the paragraph never had
  (TASK-167 review). Only ever applied to a fragment whose first line
  parsed as some block other than a paragraph even without its
  indentation, so the escaped run was a block marker there, never inline
  markup."
  [^String s]
  (if-let [[_ digits] (re-find #"^(\d{1,9})[.)]" s)]
    (str digits "\\" (subs s (count digits)))
    (if (and (seq s) (re-matches #"\p{Punct}" (subs s 0 1)))
      (let [run (count (take-while #(= % (first s)) s))]
        (str (str/join (repeat run (str "\\" (first s)))) (subs s run)))
      s)))

(defn- fragment-source-offsets
  "An `int-array` giving, for each character of `parsed`, the offset in
  `s` of the character it is, or -1 for a backslash `guard-last-line` or
  `guard-first-line` inserted (TASK-252). `parsed` is `s` from offset
  `start` on with only backslashes inserted, so matching each of its
  characters to the next equal one of `s` places every character other
  than a backslash exactly -- an inserted backslash right before a
  written one may take that one's offset, which no reference starts at."
  ^ints [^String s start ^String parsed]
  (let [n (count parsed)
        m (count s)
        offsets (int-array n -1)]
    (loop [i 0 j (long start)]
      (when (< i n)
        (if (and (< j m) (= (.charAt parsed (int i)) (.charAt s (int j))))
          (do (aset offsets i (int j)) (recur (inc i) (inc j)))
          (recur (inc i) j))))
    offsets))

(defn- fragment-placement
  "`[line-base offset-column line-starts]` -- the `*line-base*`,
  `*offset-column*` and `*line-starts*` to convert flexmark `mini-doc`
  with, a citation fragment `s` re-parsed from offset `start` on (see
  `mini-parse-inlines`), placed in the file by `locate` (TASK-252) -- or
  nil when none of its characters is placed. A fragment keeps every line
  break it had in the paragraph, so its line `k` is file line
  `line-base + k`; the inserted backslashes and the indent padding of a
  quote or list continuation line have no column."
  [^String s start ^Document mini-doc locate]
  (let [text (str (.getChars mini-doc))
        n (count text)
        offsets (fragment-source-offsets s start text)
        at (fn [i]
             (when (< -1 i n)
               (let [j (aget offsets (int i))]
                 (when-not (neg? j) (locate j)))))
        starts (line-starts text)
        line-base (some (fn [i] (when-let [[line] (at i)] (- line (offset-line starts i))))
                        (range n))]
    (when line-base
      [line-base (fn [offset] (second (at offset))) starts])))

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
  text is never expected to need block-level markup in the first place.

  Both ends of `s` are partial lines of the paragraph, though, so the
  fallback above is a last resort rather than the rule. Its FIRST line
  starts right after the `[`, `;` or key that began the fragment, so in
  the paragraph it was mid-line text, but here it starts a line and can
  open a heading, list item, blockquote, fence or HTML block (`[# x @a]`,
  `[- x\\nfoo @a]`, `[@a, 1. x]`), or be indented code merely for its
  four leading spaces (`- foo [@a;\\n      *x* @b]`). When the mini-parse
  does not start with a Paragraph, the fragment is parsed again without
  its leading whitespace, and only if that is still no Paragraph does
  `guard-first-line` escape its opening marker for a third parse
  (TASK-167) -- an indented `*x*` keeps its emphasis. Plain text then keeps its
  inline markup and its line breaks, which the fallback lost (it also
  kept a trailing space a Paragraph trims). A wrapped citation (TASK-165)
  gives `s` line breaks, and its LAST line is cut short at the `@`, `;`
  or `]` that ended the segment, so it can be a bare `---`/`===`/`***`
  that was ordinary text in the paragraph but would be a setext underline
  or thematic break here -- `guard-last-line` escapes it first. Its middle
  lines are whole paragraph lines that already did not start a block
  there, with one exception that needs the lines before it: a delimiter
  row (`|---|`). It makes a table of the lines above it when they all
  hold a `|` and start the paragraph, which the fragment's first line
  does even where the paragraph's did not (`[| a |\\n|---| @c]`, TASK-171).
  So the fragment is parsed with `fragment-parser`, which has no tables
  at all -- a fragment of a paragraph never is one.

  `locate`, when given, places `s` in the file: a function from an offset
  of `s` to `[line column]` of that character there (`column` nil when it
  has none), or nil when the character is not one of the file's. With it,
  a reference in the fragment carries its file position (TASK-252, see
  `fragment-placement`); without it, or when this conversion has no
  `*line-base*`, it carries none."
  [^String s locate]
  (when-not (str/blank? s)
    (let [^String guarded (guard-last-line s)
          ^String trimmed (str/triml guarded)
           ;; Where `trimmed` starts in `s`: `guard-last-line` inserts a
           ;; backslash, never whitespace, so it is `s`'s own indent.
          indent (- (count s) (count (str/triml s)))
          parse (fn ^Document [^String text] (.parse fragment-parser text))
          para-first? (fn [^Document d] (instance? Paragraph (first (.getChildren d))))
          [^Document mini-doc start] (or (some (fn [[text start]]
                                                 (let [d (parse text)] (when (para-first? d) [d start])))
                                               [[guarded 0] [trimmed indent]])
                                         [(parse (guard-first-line trimmed)) indent])
          mini-para (first (.getChildren mini-doc))]
      (if (instance? Paragraph mini-para)
        (let [[line-base offset-column starts] (when (and locate *line-base*)
                                                 (fragment-placement s start mini-doc locate))]
          (binding [*line-base* line-base
                    *offset-column* offset-column
                    *line-starts* starts]
            (convert-inlines mini-doc #{} mini-para)))
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

(defn- citation-segment-parts
  "Splits one `;`-separated citation segment `seg` (sec5.11, already known
  via `citation-bracket-content?` to contain an `@key` marker) into
  `[prefix dash cite-key suffix]`, around its FIRST marker
  (`cite-key-index`): any prefix text before the key; `\"-\"` when a `-`
  sits immediately before the `@`, else nil -- pandoc's own well-known
  suppress-author/\"year-only\" convention (SPEC.md documents
  `haselnuss.ast`'s CiteItem `:mode :year` as \"year-only\" without
  spelling out a trigger syntax of its own, and reusing pandoc's matches
  this project's own stated \"muscle memory\" preference for cross-refs,
  decision D3, applied the same way here); the key itself; and any
  trailing locator/suffix text with its leading whitespace dropped
  (`strip-leading-comma` cleans it up further). A backslash-escaped `\\@`
  earlier in the segment (CommonMark sec2.4, review finding #4), or an
  `@` inside a code span or inline math (TASK-167), is not a marker, so
  it stays ordinary prefix text. Prefix and suffix may span a line
  break, as a citation group may (TASK-165, see
  `cite-bracket-close-index`)."
  [^String seg]
  (let [[at key-end] (cite-key-index seg)
        dash? (and (pos? at) (= \- (.charAt seg (int (dec at)))))]
    [(subs seg 0 (if dash? (dec at) at))
     (when dash? "-")
     (subs seg (inc at) key-end)
     (str/triml (subs seg key-end))]))

(defn- node-column
  "The 1-based column of flexmark `node`'s first character on its own line
  of the text that was parsed (TASK-142)."
  [^com.vladsch.flexmark.util.ast.Node node]
  (let [seq-chars (.getChars node)
        text (str (.getBaseSequence seq-chars))
        off (.getStartOffset seq-chars)]
    (inc (.codePointCount text (int (inc (.lastIndexOf text "\n" (int (dec off))))) (int off)))))

(defn- orphan-group-message
  "What to tell the author about an attribute group `node` that
  `convert-children` found nothing to attach to (TASK-191). Only the
  wording is chosen here; which inputs are refused is unchanged.

  flexmark keeps a group that follows whitespace in the middle of text as
  its own node -- it drops that whitespace only when the group ends its
  paragraph, heading or link text -- so `x [s] {.c} y` arrives here, as do
  a second group (`[s]{.c}{.d}`), one opening the text, and one straight
  after something no group attaches to (`**b**{.c}`). Each gets a message
  naming the fix. The `]` case also reads the source, because a bracket
  can reach here flattened into text (`regroup-attributed-brackets`); a
  lone or escaped `]`, or a footnote marker's, takes the general wording,
  since writing the group right after it would still be refused."
  [^AttributesNode node]
  (let [span (.getChars node)
        base (.getBaseSequence span)
        group (str span)
        literal "write `\\{` for a literal `{`"
        prev (.getPrevious node)
        char-at (fn [i] (when (>= i 0) (.charAt base (int i))))
        space-at? (fn [i] (and (>= i 0) (Character/isWhitespace (.charAt base (int i)))))
        blank-before? (space-at? (dec (.getStartOffset span)))]
    (cond
      (instance? AttributesNode prev)
      (let [inner (fn [s] (str/trim (subs s 1 (dec (count s)))))
            prev-group (str (.getChars ^Node prev))]
        (str "attribute group `" group "` follows another group, `" prev-group
             "`; one construct takes one group, so merge them into `{"
             (inner prev-group) " " (inner group) "}`, or " literal))

      (nil? prev)
      (str "attribute group `" group "` starts the text, with nothing before it to attach to; "
           literal)

      blank-before?
      (let [p (loop [p prev]
                (if (and p (str/blank? (str (.getChars ^Node p)))) (recur (.getPrevious ^Node p)) p))
            end (loop [i (dec (.getStartOffset span))]
                  (if (space-at? i) (recur (dec i)) i))
            escaped? (fn [i] (odd? (count (take-while #{\\} (map char-at (range (dec i) -1 -1))))))
            ;; A `]` that closes a bracket a group could take: a reference
            ;; bracket, or one flattened into text (`[a [s] {.c}](u)`),
            ;; found as an unescaped `[` -- not a footnote's `[^` -- before
            ;; any other `]` in that text. Not a lone or escaped `]`.
            opens? (fn [^Node p]
                     (let [lo (.getStartOffset (.getChars p))]
                       (loop [i (dec end)]
                         (cond
                           (< i lo) false
                           (escaped? i) (recur (dec i))
                           (= \] (char-at i)) false
                           (= \[ (char-at i)) (not= \^ (char-at (inc i)))
                           :else (recur (dec i))))))
            bracket? (and (= \] (char-at end)) (not (escaped? end))
                          (or (instance? LinkRef p) (instance? ImageRef p) (attributed-ref-node? p)
                              (and (or (instance? Text p) (instance? TextBase p)) (opens? p))))
            [what fix] (cond
                         (instance? Image p) ["the image" "write it right after the image's `)`, as in `![alt](src){.c}`"]
                         (instance? Link p) ["the link" "write it right after the link's `)`, as in `[text](url){.c}`"]
                         (instance? Code p) ["the code span" "write it right after the closing backtick"]
                         (highlight-node? p) ["the highlight" "write it right after the closing `==`, as in `==text=={.c}`"]
                         bracket? ["the `]`" "write it right after the `]`, as in `[text]{.c}`"])]
        (if what
          (str "attribute group `" group "` is separated by whitespace from " what
               " before it; a group attaches only to what it directly follows, so " fix ", or " literal)
          (str "attribute group `" group "` follows whitespace in the middle of text, where it has "
               "nothing to attach to; write it right after a `[text]` span, link, image, code span or "
               "highlight, as in `[text]{.c}`, or " literal)))

      :else
      (str "attribute group `" group "` follows something it cannot attach to; in running text a "
           "group attaches only to a `[text]` span, link, image, code span or highlight, so wrap "
           "the text in brackets, as in `[text]{.c}`, or " literal))))

(defn- orphan-group-error
  "The located `::unsupported-node` error for an attribute group `node`
  with nothing to attach to, worded by `orphan-group-message` (TASK-191)."
  [^AttributesNode node]
  (ex-info (orphan-group-message node)
           {:type ::unsupported-node
            :chunk-line (inc (.getLineNumber node))
            :chunk-column (node-column node)
            :class (.getName (class node))
            :text (.toString (.getChars node))}))

(defn- citation-segment->item
  "One sec5.11 CiteItem (AC #2) for a single `;`-separated citation segment
  `seg` -- see `citation-segment-parts`. `:prefix`/`:suffix` are set only when
  their respective text (trimmed, and for suffix with `strip-leading-comma`
  applied) is non-blank (see `mini-parse-inlines`); `:mode` is `:year` when
  the segment had a suppress-author `-` immediately before its `@`,
  `:normal` otherwise (a bare, author-in-text Cite -- sec5.11's other
  mode -- is a wholly different, unbracketed construct, see
  `cite-author-node`, and never reaches this function at all).

  `locate`, from an offset of `seg` to its file position or nil (see
  `mini-parse-inlines`), places the prefix and suffix (TASK-252); nil
  places neither."
  [^String seg locate]
  (let [[prefix dash cite-key suffix] (citation-segment-parts seg)
        suffix-text (strip-leading-comma suffix)
        ;; Where the suffix text starts in `seg`: it is the segment's tail
        ;; with leading whitespace and one comma (and the whitespace after
        ;; it) gone, and with trailing whitespace trimmed.
        suffix-start (- (count (str/trimr seg)) (count suffix-text))
        prefix-inlines (mini-parse-inlines prefix locate)
        suffix-inlines (mini-parse-inlines suffix-text
                                           (when locate #(locate (+ suffix-start (long %)))))]
    (cond-> {:key cite-key :mode (if dash :year :normal)}
      prefix-inlines (assoc :prefix prefix-inlines)
      suffix-inlines (assoc :suffix suffix-inlines))))

(defn- cite-bracket-node->cite
  "The `:cite` Inline (AC #2) for a `cite-bracket-node`: one CiteItem (see
  `citation-segment->item`) per `;`-separated segment of its raw inner
  text (`cite-segments`), in source order.

  A reference in a prefix or suffix is placed in the file through the
  bracket's own text (TASK-252): `.getIndexOffset` takes an offset of it
  to the converted document's, -1 for the padding flexmark puts in place
  of a quote's or list item's continuation-line indent, and that
  document's `*line-base*`/`*line-starts*`/`offset-column-fn` take it on
  to a file line and column, as `at-node` does for a node."
  [^DelimitedNodeImpl node]
  (let [^BasedSequence text (.getText node)
        inner (.toString text)
        locate (when *line-base*
                 (let [column (offset-column-fn (.getBaseSequence text))
                       starts *line-starts*
                       line-base (long *line-base*)]
                   (fn [i]
                     (let [offset (.getIndexOffset text (int i))]
                       (when-not (neg? offset)
                         [(+ line-base (long (offset-line starts offset))) (column offset)])))))]
    {:t :cite
     :items (mapv (fn [[start end]]
                    (citation-segment->item (subs inner start end)
                                            (when locate #(locate (+ (long start) (long %))))))
                  (cite-segment-spans inner))}))

(declare convert-inline)

(defn- convert-inline-node
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
    ;; TASK-152: the ref exactly as written, unescaped and unvalidated --
    ;; see the BOOK REFERENCES comment.
    (book-ref-node? node)
    [{:t :book-ref :ref (.toString (.getText ^DelimitedNodeImpl node))}]
    (cite-author-node? node)
    [{:t :cite :items [{:key (.toString (.getText ^DelimitedNodeImpl node)) :mode :author}]}]
    (cite-bracket-node? node) [(cite-bracket-node->cite node)]
    ;; TASK-122: `==marked text==`. Unlike the three synthetic nodes
    ;; above, this one's content is ordinary markup that flexmark has
    ;; already parsed into its CHILDREN (see
    ;; `highlight-delimiter-processor`), so it recurses like an
    ;; `Emphasis`/`StrongEmphasis` rather than reading `.getText`.
    (highlight-node? node)
    [{:t :highlight
      :inlines (convert-inlines document active-footnotes node)
      :attr default-attr}]
    ;; TASK-137: `~~struck text~~`, converted like a highlight minus the
    ;; `:attr` (sec4.4 gives `:strike` none).
    (strike-node? node)
    [{:t :strike
      :inlines (convert-inlines document active-footnotes node)}]
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
      [(with-title {:t :image
                    :src src
                    :alt (alt-text alt-inlines)
                    :attr default-attr}
         (.getTitle ^Image node))])
    (instance? Link node) [(with-title {:t :link
                                        :target (unescape-backslashes (.toString (.getUrl ^Link node)))
                                        :inlines (convert-inlines document active-footnotes node)
                                        :attr default-attr}
                             (.getTitle ^Link node))]
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
        [(with-title {:t :image
                      :src src
                      :alt (alt-text alt-inlines)
                      :attr default-attr}
           (.getTitle reference))])
      ;; The same fallback as an undefined LinkRef (TASK-166): the alt
      ;; text's children are kept, `![`, `]` and any `[ref]` literal
      ;; around them -- the node's span starts at its `!`.
      (undefined-link-ref document active-footnotes node))
    (instance? LinkRef node)
    (if-let [reference (resolve-reference document node)]
      [(with-title {:t :link
                    :target (unescape-backslashes (.toString (.getUrl reference)))
                    :inlines (convert-inlines document active-footnotes node)
                    :attr default-attr}
         (.getTitle reference))]
      (undefined-link-ref document active-footnotes node))
    ;; `attributed-ref-processor` only makes one ahead of its own group,
    ;; which `convert-children` takes with it. One arriving alone is a bug
    ;; here, not a document error: a link made from it could sit inside
    ;; the link around it, which flexmark would not have allowed.
    ;; `attributed-bracket?` claims it across whitespace too, which only
    ;; attaches when the group ends the text: `x [r] {.c} y` is the
    ;; document error TASK-191 explains, reported at the group.
    (attributed-ref-node? node)
    (let [nxt (.getNext node)
          group (loop [n nxt]
                  (if (and n (str/blank? (str (.getChars ^Node n)))) (recur (.getNext ^Node n)) n))]
      ;; Only across a blank gap: a group touching the bracket that still
      ;; arrives here is `convert-children`'s bug, not the author's.
      (if (and (instance? AttributesNode group) (not (identical? group nxt)))
        (throw (orphan-group-error group))
        (throw (ex-info "internal error: an attributed bracket over a defined reference lost its attribute group"
                        {:type ::internal-error
                         :chunk-line (inc (.getLineNumber node))
                         :chunk-column (node-column node)
                         :text (.toString (.getChars node))}))))
    ;; TASK-152: a `{…}` straight after an `@` that `book-ref-end` declined
    ;; -- `\@{x}`, or an `@{` whose `}` is on a later line -- reaches here
    ;; as an attribute group. Say what was probably meant.
    (and (instance? AttributesNode node)
         (let [span (.getChars node)
               off (.getStartOffset span)]
           (and (pos? off) (= \@ (.charAt (.getBaseSequence span) (dec off))))))
    (throw (ex-info (str "`@{` starts a book reference, written `@{ref}` on one line; "
                         "write `@\\{` for a literal `@{`")
                    {:type ::unsupported-node
                     :chunk-line (inc (.getLineNumber node))
                     :chunk-column (node-column node)
                     :class (.getName (class node))
                     :text (.toString (.getChars node))}))
    ;; TASK-191: any other group `convert-children` left here had nothing
    ;; to attach to -- the same refusal, with what to write instead.
    (instance? AttributesNode node)
    (throw (orphan-group-error node))
    :else (throw (ex-info "unsupported inline construct"
                          {:type ::unsupported-node
                           :chunk-line (inc (.getLineNumber node))
                           :chunk-column (node-column node)
                           :class (.getName (class node))
                           :text (.toString (.getChars node))}))))

(defn- convert-inline
  "`convert-inline-node`'s Inlines, each one that can be the subject of a
  diagnostic -- a cross-reference, a citation, or anything carrying an Attr
  (and so possibly an id) -- recording `node`'s source position (TASK-143).
  So does inline math (TASK-219): `--untrusted` refuses math by its line,
  and the LaTeX build warns about a link it drops from math the same way.
  Plain text and breaks are left bare: there are very many of them and no
  diagnostic names one.

  An Inline that already has a position keeps it: it was recorded from
  its own, more precise node -- a cross-reference on the third line of an
  undefined `[...]` that starts on the second (TASK-160)."
  [^Document document active-footnotes ^Node node]
  (mapv (fn [inline]
          (if (and (or (:attr inline) (contains? #{:cross-ref :book-ref :cite :math-inline} (:t inline)))
                   (nil? (ast/source inline)))
            (at-node node inline)
            inline))
        (convert-inline-node document active-footnotes node)))

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

(defn- caption-offset-column
  "`*offset-column*` for a caption re-parsed from `content` (a caption
  paragraph's `.getContentChars`, of the document whose `*line-starts*`
  is bound now) with its first `marker` characters stripped (TASK-248):
  offset `i` of the re-parsed text is `content`'s character `i + marker`,
  traced back to its offset in that document -- or to none, for the
  padding flexmark puts in place of a continuation line's indent."
  [^BasedSequence content marker]
  (let [n (.length content)
        column (offset-column-fn (.getBaseSequence content))]
    (fn [offset]
      (let [i (+ (long offset) (long marker))]
        (when (< i n)
          (let [base-offset (.getIndexOffset content (int i))]
            (when-not (neg? base-offset) (column base-offset))))))))

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
  (let [content-chars (.getContentChars para)
        content (.toString content-chars)
        stripped (str/replace-first content table-caption-re "")
        ^Document mini-doc (binding [*caption-parse* true] (.parse cm-parser stripped))
        mini-para (first (.getChildren mini-doc))]
    (if mini-para
      (let [[attr children] (pop-trailing-attr-group mini-para)]
        ;; The caption is parsed on its own, so its line 0 is the line the
        ;; caption paragraph starts on (TASK-143); its columns are traced
        ;; back through `content` (TASK-248).
        [(binding [*line-base* (some-> *line-base* (+ (.getLineNumber para)))
                   *offset-column* (when *line-base*
                                     (caption-offset-column content-chars
                                                            (- (count content) (count stripped))))
                   *line-starts* (when *line-base* (doc-line-starts mini-doc))]
           (convert-children mini-doc #{} children))
         attr])
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
  Image's flexmark node -- an `Image`, or since TASK-169 a defined
  `ImageRef`, which takes an attribute group too (see `convert-children`)
  -- as a direct child, and if that ever stops being true the caption is
  the flattened alt text rather than nothing, which sec4.3 requires to be
  a vector. Missing the `ImageRef` here was not hypothetical: it silently
  flattened a reference-style figure's caption to that alt text."
  [^Document document active-footnotes ^Node paragraph image]
  (if-let [image-node (first (filter #(or (instance? Image %) (instance? ImageRef %))
                                     (.getChildren paragraph)))]
    (convert-inlines document active-footnotes image-node)
    (text->inlines (:alt image))))

(declare math-paragraph-block)

(declare convert-block)

(defn- convert-block-node
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
    (instance? ListBlock node) (cond-> {:t :list
                                        :ordered (instance? OrderedList node)
                                        :tight (.isTight ^ListBlock node)
                                        :items (mapv #(list-item-blocks document active-footnotes %) (.getChildren node))
                                        :attr default-attr}
                                 ;; CommonMark sec5.3: an ordered list's start number is
                                 ;; authored content (TASK-129); only recorded when not
                                 ;; the default 1.
                                 (and (instance? OrderedList node)
                                      (not= 1 (.getStartNumber ^OrderedList node)))
                                 (assoc :start (.getStartNumber ^OrderedList node)))
    :else (throw (ex-info "unsupported block construct"
                          {:type ::unsupported-node
                           :chunk-line (inc (.getLineNumber node))
                           :chunk-column (node-column node)
                           :class (.getName (class node))
                           :text (.toString (.getChars node))}))))

(defn- convert-block
  "`convert-block-node`'s Block, recording `node`'s source position
  (TASK-143)."
  [^Document document active-footnotes ^Node node]
  (at-node node (convert-block-node document active-footnotes node)))

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
                     (conj acc (cond-> (heading-marker (.getLevel ^Heading child) attr
                                                       (convert-children document active-footnotes heading-children))
                                 *line-base* (assoc :source (at-node child {}))))))

            (and (instance? TableBlock child) (table-caption-paragraph? child nxt))
            (let [[caption attr] (table-caption-and-attr nxt)]
              (recur (+ i 2) (conj acc (at-node child (convert-table document active-footnotes child caption attr)))))

            (instance? TableBlock child)
            (recur (inc i) (conj acc (at-node child (convert-table document active-footnotes child [] default-attr))))

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
  "The inside of a `{...}` group as a vector of `[token start quoted?]`
  triples (`start` is the 0-based offset of the token's first character
  within `inner`; `quoted?` is true when the token's value was quoted, so
  `header-token-at` lets a backslash through there and nowhere else,
  TASK-197) of whitespace-separated tokens, with a quoted value kept as one
  token and its quotes removed.

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
  (loop [[c & more] (seq inner), i 0, token "", start 0, tokens [], quote-ch nil, closed? false, quoted? false]
    (let [i' (inc i)
          flushed (cond-> tokens (seq token) (conj [token start quoted?]))]
      (cond
        (nil? c)
        (if quote-ch
          (throw (ex-info (str "unterminated " quote-ch "-quoted attribute value")
                          {:type error-type :text text}))
          flushed)

        quote-ch
        (if (= c quote-ch)
          (recur more i' token start tokens nil true true)
          (recur more i' (str token c) start tokens quote-ch false true))

        (and closed? (not (Character/isWhitespace ^char c)))
        (throw (ex-info (str "unexpected " c " after a closing quote in an attribute value")
                        {:type error-type :text text}))

        (and (contains? #{\" \'} c) (re-matches attr-value-start-re token))
        (recur more i' token start tokens c false true)

        (Character/isWhitespace ^char c)
        (recur more i' "" start flushed nil false false)

        :else (recur more i' (str token c) (if (empty? token) i start) tokens nil false quoted?)))))

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

(defn- header-token-at
  "`(directive-header-token tok)`, with a malformed-token error given where
  the token sits: `:group-text` (the whole `{...}` group) and
  `:token-offset` (0-based in the group, braces counted; `start` is the
  offset within the group's inside), so `offending-column` needs no text
  search for the token (TASK-142).

  A backslash in an id, a class or an unquoted value (`quoted?` false) is
  refused with `error-type`, as `attribute-token` refuses it on flexmark's
  path (TASK-197); a name or key cannot hold one, `directive-header-token`'s
  own productions refuse it."
  [tok start quoted? group error-type]
  (try (let [[kind a b :as parsed] (directive-header-token tok)]
         (case kind
           :id (backslash-in-token! "an id" a tok error-type)
           :class (backslash-in-token! "a class" a tok error-type)
           :prop (when-not quoted? (backslash-in-token! "an unquoted value" b tok error-type))
           nil)
         parsed)
       (catch clojure.lang.ExceptionInfo e
         (throw (ex-info (if (and (= error-type ::malformed-attributes)
                                  (= ::malformed-directive (:type (ex-data e))))
                           ;; A display math group is not a directive header
                           ;; (TASK-220): `directive-header-token`'s wording
                           ;; would name the wrong construct.
                           "malformed attribute group: token is not #id, .class, or key=val"
                           (ex-message e))
                         (assoc (ex-data e) :type error-type :group-text group :token-offset (inc start))
                         (ex-cause e))))))

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
          [dname attr] (reduce (fn [[dname attr] [tok start quoted?]]
                                 (let [[kind a b] (header-token-at tok start quoted? header ::malformed-directive)]
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

(defn- attr-group-attr
  "The Attr for a `{...}` attribute group's own text `group-text` (its
  braces included), or `default-attr` when `group-text` is nil/blank (no
  group at all). Reuses `directive-header-token`'s tokenizer rather than
  duplicating it, but rejects that function's `:name` token kind: this is
  the plain three-form sec5.3 grammar (`#id`/`.class`/`key=val`), never a
  directive header's own bare-name fourth form, so a bare word here throws
  `ex-info` exactly like `attribute-token` does for an ordinary attribute
  group -- this file's no-silent-drop ethos (AC #4 of TASK-5) applied to
  every construct carrying one of these groups. A non-blank `group-text`
  that is not a single brace-delimited group at all throws for the same
  reason, rather than quietly yielding `default-attr` as if no group had
  been written.

  `error-type` is the caller's own diagnostic keyword -- sec5.7's display
  math group passes `::malformed-attributes` (see `math-attr-group`), a
  sec5.5 `!name{...}` line passes `::malformed-directive` (see
  `bang-directive-line`) -- so an author gets the diagnostic naming the
  construct they actually wrote, not the one this helper happens to be
  shared with."
  [group-text error-type]
  (if (str/blank? group-text)
    default-attr
    (if-let [inner (second (re-matches #"\{(.*)\}" group-text))]
      (reduce (fn [attr [tok start quoted?]]
                (let [[kind a b] (header-token-at tok start quoted? group-text error-type)]
                  (case kind
                    :id (assoc attr :id a)
                    :class (update attr :classes conj a)
                    :prop (update attr :props assoc a b)
                    :name (throw (ex-info "malformed attribute group: token is not #id, .class, or key=val"
                                          {:type error-type :token tok
                                           :group-text group-text :token-offset (inc start)})))))
              default-attr
              (split-attr-tokens inner error-type group-text))
      (throw (ex-info "malformed attribute group: expected a single {...} group"
                      {:type error-type :text group-text})))))

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
                          {:type ::malformed-include :line line
                           :body-column (inc (.codePointCount ^String line 0 (int (.lastIndexOf ^String line ^String raw))))})))
        path)
      raw)))

;; --- Quote lines (TASK-152, doc-10) ------------------------------------------
;;
;; `!quote <book-ref>` on a line of its own transcludes a passage of another
;; book: the format's second whole-line block construct, recognized in the
;; same raw-line scan as `!include` and so sharing every consequence the
;; comment above lists (shielded by code and math fences, block-level only,
;; splits the text around it).
;;
;; As with `@{ref}` (see BOOK REFERENCES), the parser keeps the reference
;; exactly as written -- the rest of the line, trimmed -- and leaves judging
;; it to the resolver, which reports a malformed one as doc-10's
;; `malformed-book-ref` warning rather than stopping the build. There is no
;; quoting: a well-formed reference never contains whitespace, so a line
;; whose rest does is a malformed reference, kept whole so the warning can
;; show what was written. A bare `!quote` stays a paragraph, exactly like a
;; bare `!include`, because `quote` is a reserved one-line directive name
;; (`bang-directive-reserved-names`).

(def ^:private quote-line-re
  "A whole-line `!quote <ref>`: up to 3 leading spaces, the literal
  `!quote`, at least one space or tab, then the ref (group 1) with
  trailing whitespace trimmed off."
  #"^ {0,3}!quote[ \t]+(\S.*?)[ \t]*$")

(defn- quote-line
  "The ref, as written, of `line` if it is a `!quote` line, else nil."
  [line]
  (second (re-matches quote-line-re line)))

;; --- One-line directives (TASK-114, sec5.5) --------------------------------
;;
;; A directive whose whole content lives in its header props still paid for
;; a closing `:::` line. That shape is not a corner case here: sec5.5's own
;; `toc`/`list-of-figures`/`list-of-tables` are "empty placeholders", every
;; `describe` overlay directive is one (so an alt-text overlay file paid a
;; closing fence once per figure), and a sidecar directive can put all of
;; its content in props too (`examples/reveal.clj`'s `question`/`answer`).
;; So sec5.5 gains a second SPELLING -- not a second mechanism -- for
;; exactly that case: a whole line
;;
;;     !toc
;;     !reveal{question="Why does a lone hazel set no nuts?" answer="..."}
;;
;; which is a Directive with an empty `:blocks` and no closing fence. The
;; AST is untouched, so nothing in the resolver, registry, lower pass, JSON
;; interchange or the HTML/LaTeX emitters knows this form exists.
;;
;; `!` rather than a shorter colon run (`::{name}`), a self-closing
;; `:::{name}:::`, or the name outside a `::name{...}`: `!include` above
;; already established a whole-line `!` block construct recognized in this
;; same raw-line scan, so this joins an existing family instead of opening
;; a second one. Two costs, recorded rather than papered over:
;;
;; - `:::` and `!` now both introduce a directive. The mitigation is that
;;   `!` introduces ONLY the body-less form, so which one to write is
;;   decided by whether there is a body, never by taste.
;; - The name sits OUTSIDE the braces here and inside them on a fence.
;;   Contained by making the group after `!name` the ordinary sec5.3
;;   attribute group (`attr-group-attr`, shared with sec5.7's math group)
;;   rather than a second directive-header grammar: a bare word inside it
;;   is an error, because the name has already been given.
;; - A line that is nothing but `!word` now means a directive rather than
;;   prose. For an unregistered name that is loud (the build stops and
;;   names it), but for a REGISTERED one -- a lone `!figure` written as a
;;   sentence fragment -- it silently becomes an empty directive instead
;;   of text. Accepted because the line must be exactly `!word` with
;;   nothing else on it, which prose almost never is, and because `\!word`
;;   escapes it (the backslash means the line no longer starts with `!`,
;;   so this scan skips it and flexmark unescapes it as usual).
;;
;; `include` is RESERVED here. Without that, a bare `!include` -- today an
;; ordinary paragraph, since `include-line-re` requires a path -- would
;; silently become a directive named "include", which is a typo turned
;; into a plausible-looking wrong document, the thing this file refuses
;; everywhere else.
;;
;; Both consequences the `!include` comment above lists apply unchanged: a
;; `!name` line is block-level (indent one into a list and the list ends,
;; exactly as a `:::` fence there does), and it splits the run of text
;; around it into separate chunks before flexmark sees them.
;;
;; One known gap, shared with `!include` and left alone deliberately so
;; that the two cannot diverge: `block-marker-line-re` -- which bounds an
;; unterminated `$$` opener's search for a closer -- lists `:::` but
;; neither bang form, so a `!name` line sitting between a stray `$$` and a
;; later `$$`-shaped line is swallowed into that bogus MathBlock, where a
;; heading in the same position would have bounded the scan. Closing it
;; means closing it for `!include` too, which is a change to a construct
;; this section does not own.

(def ^:private bang-directive-line-re
  "A one-line directive: up to 3 leading spaces (matching
  `include-line-re`/`directive-fence-re`'s own CommonMark leaf-block
  indentation tolerance), a `!`, the directive's name (group 1), then the
  rest of the line (group 2) -- its `{...}` attribute group if it has one,
  or nothing but whitespace if it does not. The name production is
  deliberately `directive-header-token`'s own, so a directive name cannot
  come to mean two different things depending on which spelling it is
  written in."
  #"^ {0,3}!([A-Za-z_][\w-]*)(.*)$")

(def ^:private bang-directive-reserved-names
  "Directive names this form does not spell. `include` is the only one,
  and note where it actually bites: `parse-directive-region` tries
  `include-line` first, so a real `!include <path>` never reaches
  `bang-directive-line` at all. What is left for this set to catch is the
  PATHLESS remainder -- a bare `!include`, an ordinary paragraph today
  which stays one, and `!include{...}`, refused by name (see
  `bang-directive-line`) rather than left to fail later as something
  else. Quietly promoting either to a directive named \"include\" would
  turn a mistyped include into a plausible-looking wrong document, which
  this file refuses everywhere else.

  ADDING A NAME HERE means adding it to
  `haselnuss.emit.hdoc/bang-directive-reserved-names` too. That namespace
  keeps its own copy on purpose (no emitter requires this one), and it
  decides which directives it may write in this spelling -- so a name
  reserved here but not there would be emitted as `!thatname` and read
  back as something else.

  `quote` joined for the same reason when `!quote <ref>` arrived
  (TASK-152): `quote-line` is tried first, so only a ref-less `!quote` or
  a `!quote{...}` ever reaches this set."
  #{"include" "quote"})

(defn- bang-directive-line
  "`[name attr]` if `line` is a one-line body-less directive (see this
  section's own comment), nil if it is not one at all.

  What follows the name decides, in exactly three cases:

  - nothing, or only whitespace: a directive with no attributes (`!toc`);
  - a `{`: the line is COMMITTED to being a directive, and the whole rest
    of it must then be exactly one `{...}` group. Both `!toc{unclosed`
    and `!toc{a=1} trailing` are therefore errors (`attr-group-attr` with
    `::malformed-directive` -- the same diagnostic the same mistake earns
    on a `:::` header), never a quiet fall back to prose;
  - anything else -- a path (`!include chapters/a.hdoc`), or any other
    trailing text (`!toc extra words`): this was never a one-line
    directive and stays prose, matching `include-line`'s own \"a bare
    `!include` is just a paragraph\" tolerance at the other end.

  A reserved name (`bang-directive-reserved-names`) never yields a
  directive, but is not simply ignored either: written WITH a group it
  throws, naming the rule. `!include{src=\"a.hdoc\"}` is a plausible
  thing to try, and without this it reaches flexmark and fails as an
  `::unsupported-node` \"unsupported inline construct\" -- a diagnostic
  naming neither `!include` nor the reason."
  [line]
  (when-let [[_ dname tail] (re-matches bang-directive-line-re line)]
    (let [group (str/trim tail)
          reserved? (contains? bang-directive-reserved-names dname)]
      (cond
        (str/blank? group) (when-not reserved? [dname default-attr])
        (not (str/starts-with? group "{")) nil
        reserved? (throw (ex-info (str "\"" dname "\" is not a one-line directive name: "
                                       (if (= "quote" dname)
                                         "a quote is written `!quote <book-ref>` (doc-10)"
                                         "an include is written `!include <path>` (sec4.3)"))
                                  {:type ::malformed-directive :name dname :line line}))
        :else [dname (attr-group-attr group ::malformed-directive)]))))

;; Error locations (TASK-134). An `ex-info` raised while the document body is
;; being scanned or converted carries `:body-line`, the 1-based line within
;; the BODY (the text after the front matter) that it came from. `parse`
;; is the one place that knows the front matter's length and the source's
;; name, so it alone turns `:body-line` into the final `:source-line` and
;; the `path:line:` message prefix.

(defn- located
  "`e` rethrown-ready: the same `ex-info` with `:body-line` set to `line`
  (and `:body-column`, 1-based, when `column` is non-nil) unless some inner,
  more precise site already set a line."
  ([e line] (located e line nil))
  ([^clojure.lang.ExceptionInfo e line column]
   (if (:body-line (ex-data e))
     e
     (ex-info (ex-message e)
              (cond-> (assoc (ex-data e) :body-line line)
                column (assoc :body-column column))
              (ex-cause e)))))

(defn- offending-column
  "The 1-based column in `text` (one source line) of what a hand-parsed
  error `data` points at, counted in code points with a tab as one column.
  A malformed token carries `:group-text` and `:token-offset` (recorded
  where the group is tokenized); the group ends its line, so it is found
  from the end and an earlier look-alike cannot be mistaken for the token.
  Otherwise the error's `:text` (a whole header or group), `:name` or
  `:token` is searched for. Nil when none is found (TASK-142)."
  [^String text data]
  (letfn [(col [i] (inc (.codePointCount text 0 (int i))))
          (find-from-end [needle]
            (when-not (str/blank? (str needle))
              (let [i (.lastIndexOf text ^String (str needle))]
                (when (>= i 0) i))))]
    (or (when-let [g (and (:token-offset data) (find-from-end (:group-text data)))]
          (col (+ g (:token-offset data))))
        (some-> (some find-from-end [(:text data) (:name data)]) col)
        (let [needle (str (:token data))]
          (when-not (str/blank? needle)
            (let [i (.indexOf text needle)]
              (when (>= i 0) (col i))))))))

(defn- at-line
  "Calls the thunk `f`, adding `:body-line` `line` to any `ex-info` it
  throws that does not already name a line, and `:body-column` where the
  error's offending token can be found on `line-text` (TASK-142)."
  ([line f] (at-line line nil f))
  ([line line-text f]
   (try (f)
        (catch clojure.lang.ExceptionInfo e
          (throw (located e line (or (:body-column (ex-data e))
                                     (when line-text (offending-column line-text (ex-data e))))))))))

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
  `{:type :include :src .. :line ..}` (TASK-38, TASK-163) /
  `{:type :quote :ref .. :line .. :column ..}` (TASK-152) entries in source
  order, and `next-i` is the index of the line right after this level's own
  closing fence (irrelevant at the top level, where there is none to
  consume). A `:directive` entry comes from either of sec5.5's two
  spellings: a `:::` fence, whose own `:segments` are this function
  recurring over the lines up to its closing fence, or a one-line
  `!name{...}` (`bang-directive-line`, TASK-114), whose `:segments` is
  empty by construction.

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
    (letfn [(flush-text [segs] (if (seq text-buf)
                                 ;; `:start` is the 0-based body index of the
                                 ;; segment's first line: the buffered lines
                                 ;; are exactly the ones just before `i`.
                                 (conj segs {:type :text :lines text-buf :start (- i (count text-buf))})
                                 segs))]
      (if (>= i (count lines))
        (if open-len
          (throw (ex-info "unterminated directive fence: no matching closing fence found"
                          {:type ::unterminated-directive :open-line open-line :body-line open-line}))
          [(flush-text segments) i])
        (let [[line shielded?] (nth lines i)]
          (if-let [[len header] (and (not shielded?) (fence-line line))]
            (if (and open-len (str/blank? header) (>= len open-len))
              [(flush-text segments) (inc i)]
              (let [[dname attr] (if (str/blank? header)
                                   ["" default-attr]
                                   (at-line (inc i) line #(parse-directive-header header)))
                    [inner next-i] (parse-directive-region lines (inc i) len (inc i))]
                (recur next-i
                       (conj (flush-text segments) {:type :directive :name dname :attr attr :segments inner
                                                    :line (inc i)})
                       [])))
            (if-let [src (and (not shielded?) (at-line (inc i) line #(include-line line)))]
              ;; Ends the run of text before it and starts a fresh one
              ;; after, exactly as a directive fence does -- so an
              ;; `!include` written mid-paragraph splits that paragraph
              ;; in two rather than disappearing into it.
              (recur (inc i) (conj (flush-text segments) {:type :include :src src :line (inc i)}) [])
              (if-let [written (and (not shielded?) (quote-line line))]
                ;; TASK-152: splits the text around it exactly as
                ;; `!include` does.
                ;; `:column` is the `!`'s (TASK-248): `quote-line-re`
                ;; allows only spaces before it.
                (recur (inc i) (conj (flush-text segments) {:type :quote :ref written :line (inc i)
                                                            :column (inc (str/index-of line "!"))})
                       [])
                (if-let [[dname attr] (and (not shielded?) (at-line (inc i) line #(bang-directive-line line)))]
                  ;; TASK-114: a one-line body-less directive. Its
                  ;; `:segments` is empty by construction (this form has no
                  ;; body and consumes no further lines), so it needs no
                  ;; recursion and cannot be left unterminated.
                  (recur (inc i)
                         (conj (flush-text segments)
                               {:type :directive :name dname :attr attr :segments [] :line (inc i)})
                         [])
                  (recur (inc i) segments (conj text-buf line)))))))))))

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

(defn math-fence-line?
  "True when `line` is a display math fence line (`math-fence-line-re`):
  exactly the lines that close an open `$$` block, at column 0 of the raw
  line scan and on a contained block's dedented Paragraph lines alike.
  `haselnuss.emit.hdoc` refuses display math whose TeX holds one, since it
  would end the block early on re-parse (TASK-196)."
  [line]
  (boolean (re-matches math-fence-line-re line)))

(defn- math-attr-group
  "The Attr for a display math fence's optional trailing `{...}` group
  text `group-text` (including its own braces, as captured by
  `math-single-line-re`/`math-fence-line-re`'s own attribute-group group),
  or `default-attr` if `group-text` is nil/blank (no group at all).
  `attr-group-attr` (which this is now a thin naming wrapper over, sharing
  it with sec5.5's `!name{...}` line) does the work: sec5.7's math
  attribute group is the plain three-form sec5.3 grammar
  (`#id`/`.class`/`key=val`), never a directive's own bare-name fourth
  form, so a bare word here throws `ex-info`
  (`:type ::malformed-attributes`) exactly like `attribute-token` does for
  an ordinary attribute group (AC #4's no-silent-drop philosophy applied
  to this construct too)."
  [group-text]
  (attr-group-attr group-text ::malformed-attributes))

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
  ;; One forward pass that stops at whichever comes first, the closer or the
  ;; boundary (TASK-261). Finding the boundary first scanned to the next one
  ;; even when the closer was the very next line, and a document whose blank
  ;; lines are never followed by a block marker has no boundary at all, so
  ;; every opener walked the rest of the document: 2000 `$$` blocks took 16 s
  ;; to parse. Same answer: a boundary line is blank and a fence line is not.
  (loop [j (inc i)]
    (cond (>= j n) nil
          (math-scan-boundary? lines j n) nil
          (re-matches math-fence-line-re (nth lines j)) j
          :else (recur (inc j)))))

(defn- dedent
  "`lines` with the whitespace every CONTINUATION line shares stripped off
  the front of each.

  `math-paragraph-block` reads a Paragraph's content lines, from which
  flexmark has already stripped the container's own prefix (a list item's
  indentation, a quote's `> `), but a paragraph indented by itself keeps
  that indentation on the lines after its first: `  $$` / `  w = 4` /
  `  $$` at the top level comes back as `\"$$\\n  w = 4\\n  $$\"` once
  trimmed. Removing the shared prefix rather than all leading space keeps
  whatever alignment the author put INSIDE the formula, which is the part
  a reader of the .tex cares about."
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

(defn- closing-line-located
  "`e`, an attribute-group error raised for a contained display math
  block's closing `$$` line, with `:chunk-line` and `:chunk-column` set to
  where `close-line` (that line's flexmark content line) sits in the text
  flexmark parsed (TASK-202). The column is found on the whole raw source
  line, so a container's prefix -- a quote's `> `, a list item's
  indentation, which `.getContentLines` has stripped -- is counted."
  [^clojure.lang.ExceptionInfo e ^BasedSequence close-line]
  (let [text (str (.getBaseSequence close-line))
        off (.getStartOffset close-line)
        start (inc (.lastIndexOf text "\n" (int (dec off))))
        end (let [k (.indexOf text "\n" (int off))] (if (neg? k) (count text) k))
        column (offending-column (subs text start end) (ex-data e))]
    (ex-info (ex-message e)
             (cond-> (assoc (ex-data e) :chunk-line (inc (count (filter #{\newline} (subs text 0 start)))))
               column (assoc :chunk-column column))
             (ex-cause e))))

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

  The paragraph's own source lines are read rather than the converted
  inlines because the body is raw TeX: `\\begin{cases}` run through inline
  conversion is read as an attribute group and fails the build. They are
  read as `.getContentLines` -- each line with its container's own prefix
  already stripped by flexmark -- rather than by splitting `.getChars`,
  which spans the raw source and so keeps a block quote's `> ` on every
  line after the first: `> $$` / `> x` / `> $$` came back as
  `\"$$\\n> x\\n> $$\"`, matched no fence and stayed prose, while the same
  block reached the writer's round trip as a MathBlock (TASK-188).
  Unlike `.getChars`, the first content line keeps its own leading
  indentation (`  $$` at the top level), so the joined text is trimmed at
  both ends; `dedent` then applies to whatever indentation the later lines
  have left once the container's own prefix is gone.

  The whole paragraph must be the block. A paragraph that merely STARTS
  with a fence and carries prose after the closer is left as prose --
  conservative on purpose, since the container that indented it is exactly
  the context in which \"where does this block end\" is least obvious, and
  a wrong guess here silently swallows a sentence."
  [^Node node]
  (when (instance? Paragraph node)
    (let [lines (-> (str/split-lines (str/trim (str/join (.getContentLines ^Paragraph node))))
                    dedent)]
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
           :attr (try (math-attr-group group)
                      (catch clojure.lang.ExceptionInfo e
                        (throw (closing-line-located e (last (.getContentLines ^Paragraph node))))))})

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
  ([lines] (extract-math-chunks lines 0))
  ([lines base]
   ;; `base` is the 0-based body index of `lines`' first line (TASK-134);
   ;; every `:text` chunk records its own `:start` from it.
   (let [annotated (annotate-code-fences lines)
         n (count lines)]
     (loop [i 0 chunks [] text-buf []]
       (letfn [(flush-text [cs] (if (seq text-buf)
                                  (conj cs {:type :text :lines text-buf :start (+ base (- i (count text-buf)))})
                                  cs))
               (math-attr [group] (at-line (+ base i 1) #(math-attr-group group)))]
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
                        (conj (flush-text chunks) {:type :math-block :tex (str/trim tex) :attr (math-attr group)
                                                   :line (+ base i 1)})
                        []))

               (re-matches math-fence-line-re line)
               (if-let [close-i (math-fence-bounded-close-index lines i n)]
                 (let [open-group (second (re-matches math-fence-line-re line))
                       _ (when open-group
                           ;; The group belongs on the closing line (sec5.7);
                           ;; dropping it here would lose it silently (TASK-220).
                           (at-line (+ base i 1) line
                                    #(throw (ex-info (str "a display math block's attribute group goes on its "
                                                          "closing `$$` line, not the opening one: move `"
                                                          open-group "` there")
                                                     {:type ::malformed-attributes :text open-group}))))
                       close-line (nth lines close-i)
                       [_ group] (re-matches math-fence-line-re close-line)
                       tex (str/join "\n" (subvec lines (inc i) close-i))
                       ;; The group is on the CLOSING line, so that is
                       ;; where its error is, with the column of the
                       ;; offending token (TASK-202).
                       attr (at-line (+ base close-i 1) close-line #(math-attr-group group))]
                   (recur (long (inc close-i))
                          (conj (flush-text chunks) {:type :math-block :tex (str/trim tex) :attr attr
                                                     :line (+ base i 1)})
                          []))
                 (recur (inc i) chunks (conj text-buf line)))

               :else
               (recur (inc i) chunks (conj text-buf line))))))))))

(defn- chunk-body-line
  "The 1-based body line an error raised while converting the `:text`
  `chunk` came from. `data` is the error's `ex-data`: a throw site that had
  a flexmark node recorded its `:chunk-line` (1-based within the chunk);
  failing that, the first chunk line containing the error's `:text` (or
  `:token`), and failing that the chunk's own first line."
  [text-chunk data]
  (let [start (:start text-chunk 0)
        needle (some-> (or (:text data) (:token data)) str (str/split #"\n" 2) first)
        idx (when-not (str/blank? needle)
              (first (keep-indexed (fn [k l] (when (str/includes? l needle) k)) (:lines text-chunk))))]
    (cond
      (:chunk-line data) (+ start (:chunk-line data))
      idx (+ start idx 1)
      :else (inc start))))

(defn- untracked-node-error?
  "True if `e` is the failure flexmark's node tracker raises when a
  post-processor meets a node it was never told about (`NodeClassifierVisitor`,
  0.64.8: \"Parent node: ... is not tracked, some post processor forgot to
  call tracker.nodeAdded().\") -- see `parse-chunk-text`."
  [^Throwable e]
  (and (instance? IllegalStateException e)
       (str/includes? (str (.getMessage e)) "is not tracked")))

(defn- parse-chunk-text
  "flexmark's Document for one `:text` chunk's `text`, with an
  IllegalStateException from flexmark's own post-processing turned into an
  `ex-info` that `math-chunk-items` locates (TASK-190). The one known way
  to reach it is a *defined* reference link inside link text with an
  attribute group after whitespace, `[a [x][r] {.c}](u)`: a link cannot
  hold a link, so flexmark dissolves the outer one, and the attributes
  post-processor then fails on what that left. `:text` is the dissolved
  link's text where flexmark's message names it, for `chunk-body-line`.
  Only that failure (`untracked-node-error?`) is translated: any other
  IllegalStateException -- one of this file's own extensions going wrong,
  say -- propagates unchanged rather than posing as a document error."
  ^Document [^String text]
  (try (binding [*chunk-parse* true] (.parse cm-parser text))
       (catch IllegalStateException e
         (when-not (untracked-node-error? e) (throw e))
         (throw (ex-info (str "markup flexmark cannot parse, likely a reference link inside link text "
                              "with an attribute group after a space: a link cannot hold a link, "
                              "so remove the outer one")
                         {:type ::unparsable-markup
                          :text (second (re-find #"(?s)\{text=(.*?), url=" (str (.getMessage e))))}
                         e)))))

(defn- math-chunk-items
  "The flat Block/heading-marker items (see `block-items`) for one chunk
  produced by `extract-math-chunks`: a `:text` chunk is parsed as its own
  independent flexmark mini-document (the same per-chunk-independence
  caveat as `segment-items`'s own `:text` case, one level finer); a
  `:math-block` chunk becomes its own completed Block directly, bypassing
  flexmark for its raw TeX content entirely (sec5.7 AC #2/#3)."
  [math-chunk]
  (case (:type math-chunk)
    ;; Realized here, inside the try, because a lazy seq escaping would
    ;; throw its conversion error with no location. The flexmark parse is
    ;; inside it too: an attribute group can fail there (TASK-168).
    :text (try (let [fm-doc (parse-chunk-text (str/join "\n" (:lines math-chunk)))]
                 (binding [*line-base* (+ *body-line-offset* (:start math-chunk 0) 1)
                           *line-starts* (doc-line-starts fm-doc)
                           *surrogate-pairs* (doc-surrogate-pairs fm-doc)]
                   (vec (block-items fm-doc #{} fm-doc))))
               (catch clojure.lang.ExceptionInfo e
                 (throw (located e (chunk-body-line math-chunk (ex-data e)) (:chunk-column (ex-data e))))))
    :math-block [(ast/with-source {:t :math-block :tex (:tex math-chunk) :attr (:attr math-chunk)}
                   *source-file* (+ *body-line-offset* (:line math-chunk)))]))

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
    :text (mapcat math-chunk-items (extract-math-chunks (:lines segment) (:start segment 0)))
    ;; TASK-38: the Block itself; `haselnuss.resolver/expand-includes`
    ;; later replaces it with the referenced document's own Blocks.
    ;; TASK-163: positioned like `:book-quote`, so an unreadable or
    ;; cyclic include can name the `!include` line that caused it.
    :include [(ast/with-source {:t :include :src (:src segment)}
                *source-file* (+ *body-line-offset* (:line segment)))]
    ;; TASK-152: the ref as written; the resolver's quote expansion
    ;; (doc-10 step 0b) fills in the rest.
    :quote [(ast/with-source {:t :book-quote :ref (:ref segment)}
              *source-file* (+ *body-line-offset* (:line segment)) (:column segment))]
    :directive [(ast/with-source {:t :directive
                                  :name (:name segment)
                                  :attr (:attr segment)
                                  :blocks (segments->blocks (:segments segment))}
                  *source-file* (+ *body-line-offset* (:line segment)))]))

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

;; --- Input normalisation -----------------------------------------------------
;;
;; TASK-162: a `.hdoc` saved with Windows line endings or a UTF-8
;; byte-order mark is the same document as its plain-LF, BOM-less twin.
;; Rather than teach every line-based rule (the front-matter fences, the
;; whole-line `!include`/`!quote`/`!name` scan, the directive and math
;; fences) about a trailing `\r`, the source is normalised once, at the
;; two public entry points that take source text (`parse`, `front-matter`).
;; Every reader of a `.hdoc` file -- the CLI's input, the `!include` loads
;; (CLI and filesystem library alike), a filesystem book -- reaches the
;; parser through one of them, so an included file is normalised the same
;; way as the file including it.
;;
;; Positions survive it: `\r\n` -> `\n` keeps one line per line, so every
;; line number is unchanged; a line loses only the `\r` at its very end,
;; so every column before it is unchanged. The one column that moves is
;; on line 1 of a file with a BOM, which an editor does not display as a
;; character either -- so it moves to the column the author sees.
;;
;; A lone `\r` (classic Mac OS) is left alone, and a file using it is
;; inconsistent today: flexmark already treats a lone `\r` as a line
;; break, while this parser's own line rules (front matter, the
;; whole-line scan, fences, positions) split on `\n` only. Making the two
;; agree is out of TASK-162's scope.

(def byte-order-mark
  "The UTF-8 byte-order mark as Java decodes it, one leading `U+FEFF`.
  Public for the same reason as `front-matter-opening`."
  "\uFEFF")

(defn normalize-source
  "`s` with one leading `byte-order-mark` removed and every `\\r\\n`
  turned into `\\n` (see the comment above). `parse` and `front-matter`
  apply it themselves; a caller need not."
  ^String [^String s]
  (let [s (if (str/starts-with? s byte-order-mark) (subs s (count byte-order-mark)) s)]
    (if (str/includes? s "\r\n") (str/replace s "\r\n" "\n") s)))

;; --- Front matter ------------------------------------------------------------

(def front-matter-opening
  "The exact first line (of `normalize-source`d text split on `\\n`: no
  BOM, no `\\r\\n`, no leading blank line) that opens a front-matter block
  (SPEC.md sec5.2). Public so a reader that streams only the front
  matter -- the filesystem library's listing (TASK-151) -- applies the
  very same rule as `parse`."
  "---")

(defn front-matter-closing?
  "True when `line` (of `normalize-source`d text split on `\\n`) closes a
  front-matter block."
  [line]
  (contains? #{"---" "..."} line))

(defn- split-front-matter
  "If `source` begins with a YAML front-matter block -- a line of exactly
  `---`, then any number of lines, then a line of exactly `---` or `...`
  (SPEC.md sec5.2) -- returns `[yaml-text body-text]`; otherwise
  `[nil source]` (no front matter, `source` is the whole body)."
  [source]
  (let [lines (vec (str/split source #"\n" -1))]
    (if (= front-matter-opening (first lines))
      (let [end-idx (->> (subvec lines 1)
                         (keep-indexed (fn [i l] (when (front-matter-closing? l) i)))
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
  "The `topLevelDivision` front-matter values (TASK-53, extended by
  TASK-82's `\"part\"`), each mapped to its own `haselnuss.ast` Meta
  keyword. Named after pandoc's own `--top-level-division`, so an author
  who knows that tool already knows this key; written in front matter
  rather than passed on the command line because it changes what every
  number in the document *means*, and a document whose figure numbering
  depends on how it was invoked is one whose cross-references cannot be
  checked by reading it."
  {"section" :section
   "chapter" :chapter
   "part" :part})

;; An unknown topLevelDivision is a hard error, not a warning-and-default
;; (see `front-matter-top-level-division`), because the default is the
;; thing being overridden: a document that writes `topLevelDivision:
;; chapters` (or `Chapter`, or `book`) and is silently given `:section`
;; loses every `\chapter` in the output and renumbers every figure in it,
;; with nothing said. This codebase's convention is that a construct with
;; no representation stops the build naming itself
;; (`haselnuss.lower`'s `::no-representation`); a front-matter value with
;; no meaning is the same case. An unrecognized *key* is still ignored,
;; since Meta is open by design (sec4.2).

(defn- yaml-kind
  "How a front-matter author would name the YAML value `data` -- the whole
  block when it is not a map (TASK-178), or one field's value that has the
  wrong kind (TASK-209's `bibliography`)."
  [data]
  (cond
    (string? data) "a string"
    (map? data) "a map"
    (sequential? data) "a list"
    (number? data) "a number"
    (boolean? data) "a boolean"
    (inst? data) "a date"
    :else "some other kind of value"))

(defn- yaml-root-line
  "The line, in the whole file, where the YAML document `yaml-text`'s
  root value starts -- past any leading comments or blank lines -- or the
  first line inside the fences when snakeyaml cannot say. Same arithmetic
  as the TASK-144 marks: 0-based YAML line, plus the opening `---`, plus
  one to count from 1. Only called on the error path, so composing the
  text a second time costs nothing a valid document pays."
  [yaml-text]
  (or (try (some-> (.compose (org.yaml.snakeyaml.Yaml.) (java.io.StringReader. ^String yaml-text))
                   .getStartMark
                   .getLine
                   (+ 2))
           (catch Exception _ nil))
      2))

(defn- front-matter-not-a-map!
  "Throws the located error for front matter whose YAML parsed to `data`,
  a value that is not a map (TASK-178). Same shape as a TASK-144 YAML
  error: `:source-line` (and `:source-file` when `source` is given) in the
  data, `path:line: ` ahead of the message when there is a path."
  [yaml-text data source]
  (let [line (yaml-root-line yaml-text)
        message (str "front matter must be a YAML map of `key: value` fields, but this one is "
                     (yaml-kind data))]
    (throw (ex-info (if source (str source ":" line ": " message) message)
                    (cond-> {:type ::front-matter-not-a-map :source-line line}
                      source (assoc :source-file source))))))

(defn- yaml-key-line
  "The line, in the whole file, of the last top-level `key:` named `k` (a
  string) in the YAML map `yaml-text` -- the last because that is the
  occurrence clj-yaml keeps when a key repeats -- or `yaml-root-line` when
  snakeyaml cannot place it. Same arithmetic and same error-path-only
  second pass as `yaml-root-line`."
  [yaml-text k]
  (or (try (let [node (.compose (org.yaml.snakeyaml.Yaml.) (java.io.StringReader. ^String yaml-text))]
             (when (instance? org.yaml.snakeyaml.nodes.MappingNode node)
               (some->> (.getValue ^org.yaml.snakeyaml.nodes.MappingNode node)
                        (map #(.getKeyNode ^org.yaml.snakeyaml.nodes.NodeTuple %))
                        (filter #(and (instance? org.yaml.snakeyaml.nodes.ScalarNode %)
                                      (= k (.getValue ^org.yaml.snakeyaml.nodes.ScalarNode %))))
                        last
                        (#(.getLine (.getStartMark ^org.yaml.snakeyaml.nodes.Node %)))
                        (+ 2))))
           (catch Exception _ nil))
      (yaml-root-line yaml-text)))

(defn- bibliography-not-a-path!
  "Throws the located error for a front-matter `bibliography` whose value
  `v` is not a string (TASK-209): before, `str` turned a list into the
  path `clojure.lang.LazySeq@...` and a number into `\"42\"`, and the build
  then failed to find a nonsense file. Located at the `bibliography:` key,
  TASK-178's shape otherwise: `:source-line` (and `:source-file` when
  `source` is given) in the data, `path:line: ` ahead of the message when
  there is a path. `:value` is what YAML read, for a caller that reports
  the mistake itself rather than failing on it (`haselnuss.library.
  filesystem`)."
  [yaml-text v source]
  (let [line (yaml-key-line yaml-text "bibliography")
        message (str "front-matter bibliography must be a path to a CSL-JSON file (a string),"
                     " but this one is " (yaml-kind v)
                     (when (or (number? v) (boolean? v) (inst? v))
                       "; quote it to use it as a file name"))]
    (throw (ex-info (if source (str source ":" line ": " message) message)
                    (cond-> {:type ::bibliography-not-a-path :source-line line :value v}
                      source (assoc :source-file source))))))

(defn- front-matter-bibliography
  "Meta `cond->` step for the front-matter YAML map `data`'s
  `bibliography` (TASK-209). A string is the path, as written -- a blank
  one included, which every consumer already reads as no bibliography; a
  YAML null (`bibliography:` with nothing after it, or `~`) is no
  bibliography and leaves the key out; anything else is
  `bibliography-not-a-path!`'s located error, or, with
  `omit-non-path?`, left out of Meta for a caller that reports it
  itself."
  [m data yaml-text source omit-non-path?]
  (let [v (:bibliography data)]
    (cond
      (string? v) (assoc m :bibliography v)
      (or (nil? v) omit-non-path?) m
      :else (bibliography-not-a-path! yaml-text v source))))

(defn- located-front-matter-error!
  "Throws `error-type` as the located error for the top-level front-matter key
  `k` (a string): `:source-line` (and `:source-file` when `source` is
  given) in the data merged with `extra`, `path:line: ` ahead of
  `message` when there is a path (TASK-224; same shape as TASK-209's)."
  [yaml-text k source error-type message extra]
  (let [line (yaml-key-line yaml-text k)]
    (throw (ex-info (if source (str source ":" line ": " message) message)
                    (cond-> (assoc extra :type error-type :source-line line)
                      source (assoc :source-file source))))))

(defn- front-matter-string
  "Meta `cond->` step for the front-matter field `k` (a keyword) that must
  be a string, stored as `meta-key` (TASK-224: `str` used to turn a list
  into `clojure.lang.LazySeq@...` and a number into `\"42\"`). A string is
  kept as written; a YAML null leaves the key out; anything else is a
  located `::front-matter-not-a-string` error, or, with `omit?`, left out
  of Meta for a caller that reports it itself."
  [m k meta-key data yaml-text source omit?]
  (let [v (get data k)]
    (cond
      (string? v) (assoc m meta-key v)
      (or (nil? v) omit?) m
      :else (located-front-matter-error!
             yaml-text (name k) source ::front-matter-not-a-string
             (str "front-matter " (name k) " must be a string, but this one is " (yaml-kind v)
                  (when (or (number? v) (boolean? v) (inst? v))
                    "; quote it to use it as a string"))
             {:key k :value v}))))

(defn- front-matter-top-level-division
  "Meta `cond->` step for the front-matter `topLevelDivision`: its Meta
  keyword, or an unknown value's located `::unknown-top-level-division`
  error (TASK-224: at the key's line, `path:L:`-prefixed), or, with
  `omit?`, left out of Meta."
  [m data yaml-text source omit?]
  (let [v (:topLevelDivision data)
        text (str/trim (str v))]
    (cond
      (get top-level-divisions text) (assoc m :top-level-division (get top-level-divisions text))
      omit? m
      :else (located-front-matter-error!
             yaml-text "topLevelDivision" source ::unknown-top-level-division
             (str "unknown topLevelDivision " (pr-str text)
                  " (expected one of: " (str/join ", " (sort (keys top-level-divisions))) ")")
             {:text text}))))

(defn yaml-error-message
  "snakeyaml's error `e` as one line: its context and problem, each with
  the line and column of its mark, a line
  being the mark's (0-based) line plus `line-offset` -- where the YAML
  starts in the file the message names. snakeyaml's own message instead
  quotes each mark's source line with a `^` under the column, several
  lines that every stderr line now escapes into one long one (TASK-247)
  and that the line and column say anyway. Its own message when it
  carries neither a context nor a problem. Shared with
  `haselnuss.library.directory` for a `libraries.yaml`."
  [^MarkedYAMLException e line-offset]
  (let [at (fn [^Mark m] (when m (str " (line " (+ (.getLine m) (long line-offset))
                                      ", column " (inc (.getColumn m)) ")")))
        context (when-let [c (.getContext e)] (str c (at (.getContextMark e))))
        problem (when-let [p (.getProblem e)] (str p (at (.getProblemMark e))))
        parts (remove str/blank? [context problem])]
    (if (seq parts) (str/join ": " parts) (ex-message e))))

(defn- parse-front-matter
  "Parses `yaml-text` (the raw front-matter block, or nil if there was
  none) into a `haselnuss.ast` Meta map, mapping exactly the fields AC #1
  names: `title` (wrapped into Inlines, since `Meta.title` is `Inline[]`),
  `author`/`authors` (either key), `date`, `bibliography`, `cslStyle` (->
  `:csl-style`), and `lang` -- plus `topLevelDivision` (->
  `:top-level-division`, TASK-53). Any other front-matter key is ignored
  -- a deliberate scope limit to what AC #1 asks for, not a claim that
  Meta can't carry more later.

  A YAML syntax error is rethrown as `ex-info` (`:type
  ::malformed-front-matter`, TASK-144) carrying `:source-line`, the line in
  the whole file (YAML's own line, 0-based in the mark, plus 2: the
  opening `---` and the 1-based count), and `:source-file` when `source`
  is given; the message gains `path:line: ` ahead of snakeyaml's own
  unchanged text. An error with no mark is rethrown as it came.

  YAML that parses to something other than a map -- a string, a list, a
  number, a boolean -- is `front-matter-not-a-map!`'s located error
  (TASK-178). YAML that parses to nothing at all (an empty block, only
  comments, an explicit `null`/`~`) is no fields, as before.

  A `bibliography` that is not a path is `front-matter-bibliography`'s
  located error (TASK-209) unless `omit-non-path-bibliography?`."
  ([yaml-text] (parse-front-matter yaml-text nil {}))
  ([yaml-text source {:keys [omit-non-path-bibliography? omit-invalid-front-matter?]}]
   (if (str/blank? yaml-text)
     {}
     (let [data (try (yaml/parse-string yaml-text)
                     (catch org.yaml.snakeyaml.error.MarkedYAMLException e
                       (let [mark (or (.getProblemMark e) (.getContextMark e))
                             line (when mark (+ (.getLine mark) 2))
                             ;; the YAML starts on the line after the opening ---
                             message (yaml-error-message e 2)]
                         (if line
                           (throw (ex-info (if source (str source ":" line ": " message) message)
                                           (cond-> {:type ::malformed-front-matter :source-line line}
                                             source (assoc :source-file source))
                                           e))
                           (throw e)))))]
       (when-not (or (nil? data) (map? data))
         (front-matter-not-a-map! yaml-text data source))
       (cond-> {}
         (contains? data :title) (assoc :title (text->inlines (str (:title data))))
         (or (contains? data :author) (contains? data :authors))
         (assoc :authors (coerce-authors (or (:authors data) (:author data))))
         (contains? data :date) (assoc :date (coerce-date (:date data)))
         (contains? data :bibliography)
         (front-matter-bibliography data yaml-text source
                                    (or omit-non-path-bibliography? omit-invalid-front-matter?))
         (contains? data :cslStyle)
         (front-matter-string :cslStyle :csl-style data yaml-text source omit-invalid-front-matter?)
         (contains? data :lang)
         (front-matter-string :lang :lang data yaml-text source omit-invalid-front-matter?)
         (contains? data :topLevelDivision)
         (front-matter-top-level-division data yaml-text source omit-invalid-front-matter?))))))

;; --- Entry point --------------------------------------------------------------

(defn front-matter
  "The `haselnuss.ast` Meta `parse` would produce for source `s`, without
  parsing its body.

  Exists for the chicken-and-egg TASK-47 creates: `parse`'s `:cite-keys`
  option needs the bibliography, and which bibliography to load is
  `meta.bibliography`, which is in the document being parsed. Reading
  just the front matter is cheaper and far less surprising than parsing
  twice, and the front matter is by definition self-contained -- it ends
  at its own closing `---` before any body construct begins.

  `opts` takes `:source` as `parse` does (TASK-144): a YAML error then
  names `path:line:`. It also takes `parse`'s
  `:omit-non-path-bibliography?` and `:omit-invalid-front-matter?`."
  ([s] (front-matter s {}))
  ([s {:keys [source omit-non-path-bibliography? omit-invalid-front-matter?]}]
   (parse-front-matter (first (split-front-matter (normalize-source s))) source
                       {:omit-non-path-bibliography? omit-non-path-bibliography?
                        :omit-invalid-front-matter? omit-invalid-front-matter?})))

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

  `:source`, a path string, names where `s` came from (TASK-134). A parse
  error that can be placed gets `:source-line` (1-based, counted in the
  whole file, front matter included) in its `ex-data` whether or not
  `:source` is given; with it, `:source-file` too, and the message gains a
  `path:line: ` prefix ahead of its unchanged wording. Where the offending
  token is known, the error also carries `:source-column` (1-based, counted
  in code points of the source line, a tab being one column) and the
  prefix is `path:line:column: ` (TASK-142). This holds for a
  malformed front-matter YAML block too (TASK-144).

  A front-matter `bibliography` that is not a path -- a list, a number,
  a map -- is such a located error too (`::bibliography-not-a-path`,
  TASK-209, at the `bibliography:` key's line), unless
  `:omit-non-path-bibliography?` is true: then it is left out of Meta, for
  a caller that reports the mistake itself and must still parse the
  document (`haselnuss.library.filesystem`, whose books warn rather than
  fail a host build). A `cslStyle` or `lang` that is not a string
  (`::front-matter-not-a-string`) and an unknown `topLevelDivision`
  (`::unknown-top-level-division`) are located the same way (TASK-224),
  and `:omit-invalid-front-matter?` leaves those out of Meta too (it
  implies `:omit-non-path-bibliography?`).

  `s` is `normalize-source`d first (TASK-162): CRLF line endings and a
  leading UTF-8 byte-order mark parse exactly as their LF, BOM-less
  equivalent, at the same lines and columns.

  Throws `ex-info` (`:type ::unsupported-node`) if `s` contains a
  CommonMark construct this parser does not yet handle (e.g. raw HTML), and
  `ex-info` (`:type ::invalid-ast`) if the constructed AST unexpectedly
  fails to validate against `ast/Document` -- a bug in this parser, not a
  caller error, since every path above is expected to always produce a
  conforming Document; this is a safety net, not routine validation."
  ([s] (parse s {}))
  ([s {:keys [kinds cite-keys source omit-non-path-bibliography? omit-invalid-front-matter?]}]
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
             *known-cite-keys* cite-keys
             *source-file* source]
     (let [s (normalize-source s)
           [yaml-text body] (split-front-matter s)
           ;; Lines the front matter took off the top: a body line's
           ;; number in the file is its body number plus this (TASK-134).
           fm-lines (- (count (str/split s #"\n" -1)) (count (str/split body #"\n" -1)))]
       (binding [*body-line-offset* fm-lines]
         (try
           (let [[segments] (parse-directive-region (annotate-fences (str/split body #"\n" -1)) 0 nil nil)
                 document {:meta (parse-front-matter yaml-text source
                                                     {:omit-non-path-bibliography? omit-non-path-bibliography?
                                                      :omit-invalid-front-matter? omit-invalid-front-matter?})
                           :blocks (segments->blocks segments)}]
             (when-not (ast/valid? ast/Document document)
               (throw (ex-info "parser produced an AST that does not conform to haselnuss.ast/Document"
                               {:type ::invalid-ast
                                :explanation (ast/explain ast/Document document)})))
             document)
           (catch clojure.lang.ExceptionInfo e
             (if-let [body-line (:body-line (ex-data e))]
               ;; The message keeps its wording; `path:line: ` goes in front
               ;; (TASK-134). With no `:source` there is no path to print,
               ;; so only the data carries the line.
               (let [line (+ body-line fm-lines)
                     column (:body-column (ex-data e) (:chunk-column (ex-data e)))]
                 (throw (ex-info (if source
                                   (str source ":" line (when column (str ":" column)) ": " (ex-message e))
                                   (ex-message e))
                                 (-> (ex-data e)
                                     (dissoc :body-line :chunk-line :body-column :chunk-column)
                                     (assoc :source-line line)
                                     (cond-> column (assoc :source-column column))
                                     (cond-> source (assoc :source-file source)))
                                 (ex-cause e))))
               (throw e)))))))))

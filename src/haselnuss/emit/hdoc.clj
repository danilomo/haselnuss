(ns haselnuss.emit.hdoc
  "The `.hdoc` emitter (TASK-95, completed by TASK-97): the third of the
  three targets in `SPEC.md` sec10, and the first that writes back the
  same source format the parser reads. Follows the shape TASK-88 gave
  `haselnuss.emit.html`/`haselnuss.emit.latex` -- a plain `:t -> handler`
  map (`built-in-block-handlers`/`built-in-inline-handlers`) looked up via
  `get`, with a DECLARED `unsupported-block!`/`unsupported-inline!` default
  that throws naming the `:t`, not a `case`, not a `defmulti` -- and the
  same entry point shape, `(emit-document document)`/`(emit-document
  document opts)`.

  TASK-95's own half: the front-matter block (every key
  `haselnuss.parser/parse-front-matter` maps), `:section` (ATX headings by
  level, with its own trailing attribute group), `:para`,
  `:thematic-break`, `:block-quote`, and every `Inline` variant --
  including `:note` (a footnote), which is technically an Inline yet also
  named in TASK-97's own description; TASK-95 implemented it because its
  own AC #4 (\"every Inline variant\") left no room not to (see comment #1
  on that task). A `:note` whose own `:blocks` holds two or more Blocks
  round-trips correctly as long as every one of them is individually a
  Block type this namespace supports (`render-note-blocks` indents every
  Block after the first, the same continuation treatment a footnote
  definition needs -- see its own docstring).

  TASK-97's own half: every remaining Block variant --
  `:list` (ordered/unordered, tight/loose, an item holding several
  Blocks), `:code-block` (fenced, with its language and a fence long
  enough to survive an internal backtick run), `:math-block` (`$$ ... $$`,
  optionally attributed), `:table` (a pipe table with alignment, column
  widths and a trailing caption line), `:figure` (the standalone-image
  shorthand ONLY -- see below), `:directive` (the generic `:::` fence
  nested to arbitrary depth, or sec5.5's one-line `!name{...}` when it
  has no body -- see below) and `:include` (back to its `!include` line,
  UNEXPANDED -- see below). `haselnuss.ast/registry` now has no Block
  variant left unhandled (`block-and-inline-coverage-test` proves this by
  a subset check against the schema's own tag set, not by inspection).

  TWO THINGS THAT FOLLOW FROM SITTING PRE-RESOLVE (doc-7 decision 4),
  EACH STATED HERE SO A LATER READER DOES NOT TAKE THEM FOR OVERSIGHTS:

  1. `!include` is NOT expanded. `render-include` writes the Block back to
     its own `!include <path>` line, unconditionally -- it never inlines
     the referenced document's own content, even though
     `haselnuss.resolver/expand-includes` could be run first to produce
     that content. `--target hdoc` (TASK-98) preserves a multi-file
     document as multiple files this way, which is the RIGHT behaviour for
     a source-to-source pass: expanding here would silently turn an
     `!include`-based multi-file document into a single merged file on
     every save, and a scripted transformation (this milestone's own
     purpose) could never again reach into the included file on its own --
     the split the author chose would be gone the first time this emitter
     ran.
  2. A `{figure}`/`{subfigure}` an author writes as an explicit directive
     (sec5.5's own generic `:::` syntax, e.g. `:::{figure #fig:x}`) is
     still a plain `:directive` here, not a `:figure`: `render-directive`
     handles it exactly like any other named directive, name and
     attributes round-tripping through whichever generic directive
     spelling fits it -- so a `{figure}` carrying actual content keeps
     the fence, while a body-less `:::{figure #fig:x}` comes back as
     `!figure{#fig:x}`, the same normalization every other body-less
     directive gets. Only
     `haselnuss.parser`'s standalone-image shorthand
     (`![alt](src){#fig:id}`, sec5.8, `standalone-figure-image`) ever
     produces an actual `:figure` Block AT PARSE TIME -- `render-figure`
     is written against exactly that one shape and throws
     `::unsupported-figure-content` for anything else, per its own
     docstring. The `:figure` Blocks `haselnuss.cli/float-lower-rule`
     synthesizes from a `{figure}`/`{subfigure}` directive are POST-LOWER
     and never reach this emitter at all (THE CONTRACT below), so this
     namespace never has to choose a `.hdoc` spelling for one.

  THE CONTRACT (doc-7 decision 4): this emitter takes `document` exactly
  AS PARSED -- no `resolve-document`, no `lower`, no
  `haselnuss.registry`. That is not a simplification available to trim
  later, it is the shape the whole milestone is built on: a resolved
  document carries computed section/figure/table numbers and resolved
  cross-reference text, and a lowered one carries target-specific shapes
  (an admonition turned into a styled `:block-quote`, a poster substituted
  for un-lowerable content) -- neither has an honest `.hdoc` spelling, so
  reaching either pass before this emitter runs would silently bake
  computed content back into source. `haselnuss.cli`'s existing `--target
  json` branch already stops at exactly this same point in the pipeline
  (parser/parse, nothing after) for the identical reason; TASK-98 puts
  `--target hdoc` on that same branch.

  THE ROUND-TRIP STANDARD (doc-7 decision 4, TASK-99's own job to pin):
  `parse(emit(parse(src))) == parse(src)`, an AST FIXED POINT, not text
  identity -- this emitter re-spells canonically (a fixed emphasis
  delimiter, ATX headings, a fixed quoting convention), so
  `emit(parse(src))` differs from `src` for almost any real document, on
  purpose. \"The parser is the oracle\": every escaping decision below was
  checked by actually calling `haselnuss.parser/parse` on the emitted
  text at a REPL and confirming the resulting AST, not by reasoning about
  CommonMark from memory.

  LISTS (TASK-97 AC #2). `:ordered` picks the marker (`- ` or `N. `,
  always numbered from 1 regardless of what the source actually started
  at -- `haselnuss.ast/registry`'s own `:list` schema has no field to
  remember a start number in the first place, so `haselnuss.parser`
  already discards it, and re-spelling from 1 is doc-7's own \"re-spells
  canonically\" standard applied to the simplest case). An item's OWN
  first Block sits directly after its marker with no indentation of its
  own (`haselnuss.parser` lazily continues a `:para`'s later physical
  lines with none, confirmed at a REPL, the same as a footnote
  definition's own first Block -- see `render-note-blocks`); every Block
  after an item's first is indented by the marker's own rendered width
  (`indent-lines`, `indent-block-continuation`'s own generalization) so
  it stays part of the item on re-parse.

  A LOOSE item's later Blocks (`:tight false`) are always separated from
  the one before by a blank line, unconditionally safe for ANY Block
  type here (mirrors `render-note-blocks`'s own `:note` continuation, and
  is in fact the ONLY spelling for a `:math-block`/`:table`/`:figure` as
  a later item Block at all -- confirmed at a REPL that each of those
  needs its OWN complete flexmark Paragraph, which only a preceding blank
  line produces; TASK-70's own real thesis example, an ordered list whose
  items each hold a `:para` then a `:math-block`, is exactly this shape).
  A TIGHT item's later Blocks (`:tight true`) have NO blank line before
  them and so need a Block type CommonMark recognizes as starting a new
  Block on sight, with no ambiguity a blank line would otherwise resolve
  -- confirmed at a REPL for exactly `list-item-safe-continuation-types`
  (`:section`, `:block-quote`, a nested `:list`, a fenced `:code-block`);
  a `:para` can never appear there (two adjacent `:para`s with no blank
  line between them just become ONE paragraph's own lazy continuation,
  which is why `:tight` can never even be true for that shape from real
  parsing), and neither can `:thematic-break` (confirmed at a REPL: `---`
  directly under a paragraph's own text with no blank line is read as a
  SETEXT HEADING underline for that text, not a thematic break, so this
  spelling would silently change the Block's own type on re-parse rather
  than merely failing to round-trip). `render-list-item-blocks` throws
  `::unrepresentable-tight-list-item` for any other type in that
  position, naming it, rather than emitting a spelling this parser would
  read back differently.

  A `:directive`/`:include` can never legitimately appear inside a
  `:list` item (or a `:block-quote`, or a `:note`) at all: unlike every
  other Block, both are recognized by `haselnuss.parser`'s own
  `parse-directive-region` -- a scan over RAW document lines that runs
  BEFORE flexmark ever sees them and knows nothing about container
  indentation. Confirmed at a REPL that a `:::` fence indented the 1-3
  spaces a `- `-item's own content needs is caught by that TOP-LEVEL scan
  and splits the list in two rather than nesting (the same behaviour
  `include-line`'s own docstring already documents for `!include`), while
  one indented 4+ (inside a `>` quote, or a two-digit ordered item) is
  simply never recognized as a directive at all and stays literal prose.
  Neither spelling round-trips, so `check-representable-nested-block!`
  throws `::unrepresentable-nested-block` up front, naming the type and
  its container, rather than emitting either. This does NOT apply to a
  `:directive`/`:include` nested inside ANOTHER `:directive`'s own
  `:blocks` -- see DIRECTIVES below -- since that nesting is recognized by
  the very same raw-line mechanism, recursively, not by container
  indentation at all.

  CODE AND MATH BLOCKS. `:code-block` reuses `code-fence-for` (already
  built for an inline `:code` span) to pick a backtick-fence run one
  longer than the longest backtick run its own `:text` contains, so a
  body holding backticks can never close the fence early; `:text` itself
  is spliced in completely verbatim (a fenced code block's content is
  raw, un-escaped text on the read side, and `annotate-code-fences`
  already shields it from `:::`/`$$` misdetection on the way in). A
  `:math-block` is always written as the two-line `$$`/`$$` fenced form
  (never the single-line `$$ ... $$ {...}` shorthand, even for a one-line
  `:tex`): sidesteps needing to reason about how many literal `$$`
  occurrences `:tex` itself might contain, which the single-line form's
  own greedy-to-the-LAST-`$$` grammar would otherwise make ambiguous.
  `:tex` is spliced in verbatim too, on the same \"the read side stores
  raw TeX, unescaped\" grounds as `:code-block`'s own `:text`; a `:tex`
  containing a bare `$$`-only PHYSICAL line has no `.hdoc` spelling (it
  would close the fence early) but is unreachable from an as-parsed
  document by construction -- the SAME ambiguity would already have
  closed the fence at that point on the way IN, so no as-parsed `:tex`
  can ever contain one.

  TABLES (TASK-97 AC #4). A pipe table's own alignment row comes from
  `:colspec`'s per-column `:align` (`:---`/`---:`/`:---:`/`---`); its
  column widths are recovered from `:colspec`'s own per-column `:width`
  ONLY when EVERY column carries one (`column-widths`'s own \"all or
  nothing\" rule on the read side, mirrored exactly here) and folded back
  into a `widths=\"...\"` prop on the caption line's own attribute group,
  the one place `haselnuss.parser` ever reads it from. The caption line
  itself (`: caption {#id ...}`) is written only when there is something
  to say (a non-empty caption, an id, a class, a non-`widths` prop, or a
  reconstructed `widths=`) -- a plain table with none of those stays a
  bare pipe table with no trailing line at all, the same \"re-spells
  canonically\" simplification `render-front-matter` already applies to
  an empty Meta. A cell's own `|` is escaped (the ONE character this
  namespace escapes outside `always-escaped-chars`, since it is only
  hazardous inside a table row and nowhere else `escape-text` runs);
  a cell holding anything other than EXACTLY one `:para` Block, or a
  `:para` whose rendered text contains a literal newline, has no `.hdoc`
  spelling at all (a pipe-table cell is inherently one physical line of
  inline content, per `table-cell->cell`'s own docstring) and throws
  `::unrepresentable-table-cell` rather than corrupting the row; the same
  throw covers a `:span` other than 1, since `TablesExtension/
  COLUMN_SPANS` is disabled on the read side (`convert-table`'s own
  docstring) and GFM pipe tables have no column-span syntax to write one
  back into regardless.

  FIGURES. `render-figure` only ever handles the ONE shape
  `haselnuss.parser` produces (see the \"figure vs. directive\" point
  above): `:content` must be a `:para` wrapping exactly one `:image`, or
  it throws `::unsupported-figure-content` naming the actual shape rather
  than guessing at a spelling. The image's own bracket text is rendered
  from the FIGURE's own `:caption` Inlines (`render-inlines`, the same
  richer conversion `haselnuss.parser/figure-caption` used to build it),
  NOT from the image's flattened `:alt` string -- re-parsing the caption's
  own rendered markup reproduces both fields at once, exactly the way
  `haselnuss.parser/alt-text` derived `:alt` FROM the same bracket
  content in the first place. The written attribute group combines the
  Figure's own `:id` with the content Image's own `:classes`/`:props`
  (never the Figure's own, always-empty-`:props`/duplicate-`:classes`
  copy -- `convert-block`'s own docstring explains why the two Attrs
  hold what they do), reconstructing the single group an author wrote.

  DIRECTIVES (TASK-97 AC #5, TASK-115). A directive with NO BODY is
  written in sec5.5's one-line spelling, `!name{...}` -- `!toc`, with no
  group at all when it has no attributes. The AST records no trace of
  which spelling its author wrote, so the choice is made from the
  document model alone: a directive is one-line iff the form can
  represent it losslessly (`bang-spellable?` -- empty `:blocks`, a name
  the parser would accept back, and not the reserved `include`), and
  keeps the fence otherwise. \"Otherwise\" is a fallback, not a promise
  that the fence can spell every remaining name -- see
  `bang-spellable?`'s own docstring for the pre-existing hole it does
  not close. One
  visible consequence, and the intended one: this emitter is the format's
  canonical formatter, so `--in-place` rewrites an author's existing
  `:::{toc}` / `:::` pair to `!toc`.

  Every directive FENCE this namespace writes -- opening AND nested
  opening alike -- carries a `{...}` header,
  even an EMPTY one (`{}`) for a nameless, attributeless directive: a
  non-blank header always opens a new level regardless of its own
  colon-run length relative to any enclosing one (`parse-directive-region`
  own docstring), so a FIXED three-colon fence (`:::`) is safe at every
  nesting depth without this namespace ever having to compute a longer
  run for an outer level the way an author writing by hand must (sec5.5's
  own \"closing fence of at least as many colons\" rule only binds a
  HEADERLESS -- i.e. genuinely blank-rest -- fence line, which this
  namespace only ever writes for a CLOSE, and every open here is closed
  by the very next same-depth `:::` after its own content, confirmed at
  a REPL for three levels of nesting). `:include`/`:directive` Blocks
  nested inside another `:directive`'s own `:blocks` round-trip normally
  (unlike the list/quote/note case above): `parse-directive-region`
  recognizes both via the SAME raw-line recursion regardless of depth,
  never via container indentation.

  ESCAPING (the piece most likely to hold the bugs). Literal text (an
  Inline `:str`'s own `:text`) is escaped UNCONDITIONALLY, character by
  character, wherever it appears -- `escape-text` backslash-escapes `\\`,
  `` ` ``, `*`, `_`, `[`, `]`, `$`, `@`, `!`, `#`, `:`, and `>` every time
  they occur, never only in the position where they would actually be
  misread. That mirrors `haselnuss.emit.latex/escape-tex`'s own
  unconditional style (both escape `#` regardless of position, for
  example) rather than tracking exactly where a physical *line* starts,
  which this emitter would otherwise have to recompute by hand: a
  `:soft-break`/`:line-break` inside a single `:para` introduces a fresh
  line boundary that an ATX heading or a directive fence can interrupt a
  paragraph at just as readily as the paragraph's own first character can
  (confirmed at a REPL -- CommonMark's paragraph-interruption rules apply
  per physical line, not only at a block's very first line), so
  \"escape only at a genuine line start\" is not actually simpler than
  \"escape everywhere\": it needs the same reasoning repeated at every
  break. Backslash-escaping any of these twelve characters is always
  safe regardless of what precedes or follows it (CommonMark sec2.4 --
  confirmed at a REPL for every one of them, including `!` immediately
  before `[`, the one case AC #5 calls out by name: escaping the bracket
  alone is already sufficient, since an escaped `[` is not a real link
  delimiter for `!` to combine with, but `!` is escaped too rather than
  leaving that as a special case one character in this set gets and the
  rest don't).

  The one hazard `escape-text` cannot cover this way is a `-`/`=`-only
  physical line (a thematic break, or a setext-heading underline) --
  hyphen is far too common in ordinary prose to escape unconditionally
  the way `#`/`:` are. `escape-line-start-hazard` instead checks each
  physical line of a rendered `:para` (recursively, wherever one occurs,
  including nested in a `:block-quote`) for a line composed ENTIRELY of
  `-`/`=`/whitespace, and escapes its first significant character only
  then -- covering AC #5's own \"leading `---`\" case as the special
  case where that line is the very first line of the document, and the
  more general mid-paragraph case a soft break can introduce, with one
  mechanism. A document whose very first Block is a bare `:thematic-
  break` with no front matter at all is a distinct hazard `emit-document`
  itself guards (see its own docstring): the break's OWN spelling is
  \"---\", which cannot be escaped without ceasing to BE a thematic break,
  so that one case is resolved by forcing an (empty) front-matter block
  instead of by escaping.

  TWO MORE physical-line-start hazards, discovered and left for TASK-97 by
  comment #1 on TASK-95, `escape-line-start-hazard` also guards (the same
  \"too common in ordinary prose to escape unconditionally\" reasoning as
  `-`/`=` applies to both): a literal `- item`/`+ item` (a bullet-list
  marker character followed by whitespace, or ending the line on its own)
  degrades into an unintended `:list` on re-parse, and a literal
  `12. item`/`12) item` (1-9 digits then `.`/`)` then whitespace) degrades
  into an unintended ordered `:list` the same way -- both confirmed at a
  REPL against a hand-built `:str`. `*` needs no equivalent guard: it is
  already in `always-escaped-chars` (bullet markers share it with
  emphasis), so a literal `* item` is already escaped before
  `escape-line-start-hazard` ever sees it. The ordered case cannot escape
  the marker's own leading digit -- confirmed at a REPL that a backslash
  before a digit does not survive `haselnuss.parser/unescape-backslashes`
  at all, since a digit is not in `escapable-punctuation` -- so it escapes
  the `.`/`)` delimiter instead, which is escapable and is exactly what
  stops the marker from being recognized as one (confirmed at a REPL:
  `12\\. item` re-parses to the literal string `\"12.\"`, not a list).

  A curly brace immediately followed by `#`/`.`/an identifier `=` (the
  start of what looks like an attribute-group attempt) has NO safe
  spelling in `.hdoc` at all, escaped or not: confirmed at a REPL that
  `haselnuss.parser/check-no-attribute-attempt!` rejects
  `\\{#id}` exactly as it rejects the unescaped form, since that check
  runs against raw source text, before backslash-escape resolution. A
  `:str` literally containing `{#`/`{.`/`{key=` is therefore unreachable
  from actual parsing (nothing an author writes produces such a `:str`
  in the first place) and is left unescaped here rather than worked
  around; not in scope, not tested.

  FIVE INLINES WITH NO `.hdoc` SURFACE SYNTAX AT ALL (AC #7, doc-7 \"What
  this does not do\"): `:strike`, `:small-caps`, `:sub`, `:sup` and --
  since TASK-123 -- `:sticky-note` are schema-valid
  (`haselnuss.ast/registry`) and both existing emitters render them, but
  `haselnuss.parser` produces none of them. The first four are written
  here as the role span an author would reach for (`[text]{.strike}`, the
  same `[text]{...}` construct that already produces a `:span`), since
  re-parsing that spelling yields a `:span`, not the original tag; a
  `:sticky-note` carries Blocks rather than Inlines, so it is written as
  a footnote instead -- the one construct in this grammar that does carry
  Blocks from an inline position. `:highlight` is NOT in this list: it
  has a surface spelling of its own (`==...==`, sec5.6) and is an exact
  round trip, except nested inside another highlight, which that spelling
  cannot express (`render-highlight`). `lossy-role-span-diagnostics` is the
  separate, pure function a caller runs over `document` BEFORE emitting
  it to get one diagnostic per occurrence naming the node and its path
  (mirroring `haselnuss.emit.latex/unanchored-reference-diagnostics`'s
  own shape: a diagnostics function alongside `emit-document`, not folded
  into its return value, so `emit-document` keeps returning a bare
  string exactly like `haselnuss.emit.html`/`haselnuss.emit.latex` do).

  OTHER KNOWN, NARROW GAPS, each accepted rather than solved because none
  is reachable from an as-parsed document and each is cheap to state
  plainly:

  - A `:link`/`:image` whose `:target`/`:src` contains whitespace has no
    `.hdoc` spelling in this parser build: confirmed at a REPL that
    NEITHER the bare nor the `<...>`-angle-bracket destination form
    tolerates an embedded space here (the angle-bracket form is used
    only for an otherwise-unbalanced-parenthesis destination, which it
    does handle). `render-destination` throws naming the target rather
    than silently truncating it; unreachable from real parsing, since
    `haselnuss.parser` itself could never have produced such a target in
    the first place (the same two destination forms are all it has to
    work with).
  - A `:code` (inline code span) whose own text starts or ends with a
    backtick is a narrow, accepted gap: the standard `fence + padding
    space` protection this namespace's `render-code-span` otherwise
    applies works for an INTERNAL backtick run (confirmed at a REPL), but
    this parser build does not strip the CommonMark-standard padding
    space back out of a code span's stored text the way the spec
    describes (confirmed: `` ` a ` `` round-trips to `\" a \"`, not
    `\"a\"`) -- so a padded edge backtick round-trips with an extra space
    baked in rather than failing outright. A narrow, stated limitation,
    not a silent one.
  - `:cross-ref`'s own `:suppress-prefix` and a `:span`/`:block-quote`'s
    own non-default `:attr` (the latter two only ever populated by a
    lowering pass or a hand-built/JSON AST, never by
    `haselnuss.parser`) are assumed absent/default here, the same
    as-parsed assumption `haselnuss.parser` itself already guarantees for
    every document this emitter is actually run against; SPEC.md itself
    notes the role-form cross-reference syntax that WOULD set
    `:suppress-prefix` \"is not parsed\" at all.
  - A CiteItem's own `:prefix`/`:suffix` text (sec4.4/5.11) containing a
    literal `;` has NO `.hdoc` spelling at all, unlike every other
    character `always-escaped-chars` covers (review comment #4 on
    TASK-95): `haselnuss.parser`'s own citation-bracket recognition
    (`citation-bracket-content?`) and item extraction
    (`cite-bracket-node->cite`) both split a bracket's raw inner text on
    EVERY `;` character unconditionally, with no backslash-escape
    awareness at all -- confirmed at a REPL that backslash-escaping a
    `;` there does NOT prevent that split: the segment on the far side
    of it, having lost its own `@key` marker to the split, fails
    `citation-bracket-content?`'s own recognition check and degrades the
    WHOLE bracket to plain text on re-parse, exactly the silent
    corruption the review comment reported, unfixed by escaping alone.
    The same class of gap as the `{#`/`{.`/`{key=` case above: the check
    that matters runs against raw source text, before backslash-escape
    resolution ever happens, so no escape sequence can hide a `;` from
    it. `render-cite-item` therefore throws `ex-info` (`:type
    ::unrepresentable-cite-affix`) naming the CiteItem's own `:key`
    rather than emitting a spelling that silently corrupts on re-parse
    (the reviewer's own alternative suggestion, mirroring
    `render-destination`/`quote-attr-value`'s existing throw pattern for
    their own unrepresentable inputs) -- unreachable from real parsing
    in the first place (`haselnuss.parser` has no HTML-entity support of
    its own, confirmed at a REPL: `&semi;`/`&#59;` are both an
    `::unsupported-node` there, not a literal `;`), the same
    \"hand-built/JSON/scripted-transformation only\" reachability every
    other gap in this list already has."
  (:require [clj-yaml.core :as yaml]
            [clojure.string :as str]
            [haselnuss.ast :as ast]
            [haselnuss.walk :as walk]))

;; --- Escaping (see this namespace's own docstring for the design) ----------

(def ^:private always-escaped-chars
  "Every character `escape-text` backslash-escapes UNCONDITIONALLY,
  wherever it occurs in a literal text run -- see this namespace's own
  docstring for why unconditional beats position-tracked here. `>` is
  included beyond AC #5's own stated minimum: a literal `>` at a
  physical line's start is a block-quote marker exactly as readily as a
  leading `#`/`:::` is a heading/directive one (confirmed at a REPL: a
  hand-built `:str \">\"` round-trips only once `>` joins this set), and
  it is common enough to be worth the same unconditional treatment while
  rare enough in ordinary prose not to cost much readability."
  (set "\\`*_[]$@!#:>"))

(defn- escapes-as-highlight-marker?
  "True when the character at `i` in `s` is an `=` immediately followed by
  another one -- the opening or closing marker of a `==highlight==`
  (TASK-122, sec5.6).

  `=` is deliberately NOT in `always-escaped-chars`: a lone `=` is
  ordinary punctuation, common in prose (`x = y`) and in an attribute
  group (`key=val`), and escaping every one of them would be unreadable
  for no gain. Only a RUN of two or more is a delimiter, and escaping
  each `=` that has another after it is exactly enough to break every
  such run -- `==` becomes `\\==`, which re-parses as a literal `=` then
  a lone `=`, never a delimiter of the minimum length two.

  Without this a literal `==` written in the source came back out
  unescaped and re-parsed as a `:highlight` nobody wrote: the round trip
  invented a node."
  [^String s i]
  (and (= \= (.charAt s i))
       (< (inc i) (.length s))
       (= \= (.charAt s (inc i)))))

(defn- escape-text
  "Backslash-escapes every character in `always-escaped-chars` found in
  `s`, plus every `=` that opens a `==` run
  (`escapes-as-highlight-marker?`), leaving everything else untouched.
  The inverse of `haselnuss.parser/unescape-backslashes` for exactly
  this character set: re-parsing the result always resolves each escape
  back to the bare character (CommonMark sec2.4), regardless of what
  precedes or follows it."
  [s]
  (apply str
         (map-indexed (fn [i c]
                        (if (or (contains? always-escaped-chars c)
                                (escapes-as-highlight-marker? s i))
                          (str \\ c)
                          c))
                      s)))

(def ^:private hazard-line-re
  "Matches a physical line composed ENTIRELY of `-`/`=`/whitespace, with
  at least one `-` or `=` -- the shape of a thematic break (`---`, `- -
  -`) or a setext-heading underline (any run of `-` or `=` alone), the
  one hazard `escape-text` cannot rule out unconditionally (see this
  namespace's own docstring). Captures the leading whitespace (group 1),
  the line's first significant character (group 2, `-` or `=`) and
  everything after it (group 3), so `try-escape-heading-hazard` can
  reinsert exactly one backslash without disturbing the rest of the
  line."
  #"([ \t]*)([-=])([ \t=-]*)")

(defn- try-escape-heading-hazard
  "`line` with a backslash inserted before its first significant
  character if the WHOLE line matches `hazard-line-re` (a bare `-`/`=`
  run, TASK-95's own thematic-break/setext-underline hazard), else nil."
  [line]
  (when-let [[_ lead c tail] (re-matches hazard-line-re line)]
    (str lead \\ c tail)))

(def ^:private bullet-marker-hazard-re
  "Matches a physical line shaped like a CommonMark bullet-list marker: up
  to 3 leading spaces/tabs (group 1, the usual leaf-block indentation
  tolerance), one of `-`/`+`/`*` (group 2), then either nothing else or
  at least one space/tab and the rest of the line (group 3) -- the TASK-97
  hazard this namespace's own docstring records (comment #1 on TASK-95).
  `*` needs no entry here: it is already in `always-escaped-chars`, so a
  literal `*` never reaches this check unescaped in the first place."
  #"([ \t]{0,3})([-+*])([ \t].*|)")

(defn- try-escape-bullet-hazard
  "`line` with a backslash inserted before its own bullet-marker character
  if the WHOLE line matches `bullet-marker-hazard-re`, else nil."
  [line]
  (when-let [[_ lead c tail] (re-matches bullet-marker-hazard-re line)]
    (str lead \\ c tail)))

(def ^:private ordered-marker-hazard-re
  "Matches a physical line shaped like a CommonMark ordered-list marker: up
  to 3 leading spaces/tabs (group 1), 1-9 digits (group 2 -- CommonMark's
  own limit on an ordered marker's digit count, sec5.2 of the CommonMark
  spec), a `.` or `)` (group 3), then either nothing else or at least one
  space/tab and the rest of the line (group 4) -- the other TASK-97 hazard
  this namespace's own docstring records."
  #"([ \t]{0,3})(\d{1,9})([.)])([ \t].*|)")

(defn- try-escape-ordered-hazard
  "`line` with a backslash inserted before its own marker's `.`/`)`
  delimiter if the WHOLE line matches `ordered-marker-hazard-re`, else
  nil. The delimiter, not the leading digit: a digit is not in
  `haselnuss.parser/escapable-punctuation`, so a backslash placed before
  one does not survive `haselnuss.parser/unescape-backslashes` at all
  (confirmed at a REPL) -- escaping the delimiter instead is what
  actually stops the marker from being recognized as one."
  [line]
  (when-let [[_ lead digits delim tail] (re-matches ordered-marker-hazard-re line)]
    (str lead digits \\ delim tail)))

(defn- escape-line-start-hazard
  "`line` with a backslash inserted to defuse whichever physical-line-start
  hazard it matches (`try-escape-heading-hazard`, `try-escape-bullet-
  hazard`, `try-escape-ordered-hazard`, tried in that order -- mutually
  exclusive in practice, since each targets a different marker shape),
  otherwise `line` unchanged. Applied to every physical line of a
  rendered `:para` (recursively -- wherever a `:para` occurs, including
  nested inside a `:block-quote` or a `:list` item, per this namespace's
  own docstring)."
  [line]
  (or (try-escape-heading-hazard line)
      (try-escape-bullet-hazard line)
      (try-escape-ordered-hazard line)
      line))

;; --- Attribute groups (sec4.1/5.3, mirroring TASK-39's own quoting) --------

(defn- quote-attr-value
  "`v` (a `key=val` attribute value, sec5.3/TASK-39) wrapped in whichever
  quote character `v` itself does not contain -- double preferred, single
  as the fallback -- or throws `ex-info` (`:type
  ::unrepresentable-attr-value`) naming `k` when `v` contains BOTH: a
  value with both is not expressible in `.hdoc`'s own quoting syntax at
  all (`haselnuss.parser`'s own TASK-39 comment block states this same
  limitation from the read side), so this is reported rather than
  silently mangled."
  [k v]
  (cond
    (not (str/includes? v "\"")) (str "\"" v "\"")
    (not (str/includes? v "'")) (str "'" v "'")
    :else (throw (ex-info
                  (str "attribute " (pr-str k) "'s value contains both a single and a double quote, "
                       "which .hdoc's own quoting syntax cannot represent: " (pr-str v))
                  {:type ::unrepresentable-attr-value :key k :value v}))))

(defn- render-attr-value
  "One `key=val` attribute value `v`: quoted (`quote-attr-value`) when it
  contains whitespace (TASK-39 requires quoting there) or would otherwise
  be misread as OPENING a quoted value (starts with `\"`/`'`, per
  `haselnuss.parser/attr-value-start-re`), bare otherwise."
  [k v]
  (if (or (re-find #"\s" v) (and (seq v) (contains? #{\" \'} (first v))))
    (quote-attr-value k v)
    v))

(defn- attr-tokens
  "Attr `attr` (sec4.1: `:id`/`:classes`/`:props`) as the vector of `{...}`
  attribute-group tokens (`#id`, `.class`, `key=val`) an author would
  write inside the braces, WITHOUT the braces themselves -- shared by
  `attr-group` (AC #6) and `directive-header-text` (TASK-97 AC #5), which
  prepends a directive's own name token ahead of the same tokens. `:props`
  is rendered in sorted key order for a stable, deterministic spelling
  (it is a plain map, with no ordering of its own to preserve)."
  [{:keys [id classes props]}]
  (cond-> []
    id (conj (str "#" id))
    true (into (map #(str "." %) classes))
    true (into (map (fn [[k v]] (str k "=" (render-attr-value k v)))
                    (sort-by key props)))))

(defn- attr-group
  "Attr `attr` as a `{#id .class key=val}` attribute-group fragment (AC
  #6), or `\"\"` when `attr` carries none of the three -- the same
  \"nothing to show\" convention `haselnuss.emit.html/attr-attrs` uses."
  [attr]
  (let [tokens (attr-tokens attr)]
    (if (seq tokens) (str "{" (str/join " " tokens) "}") "")))

;; --- Link/image destinations (sec6.3/6.4 of CommonMark) --------------------

(defn- balanced-destination?
  "True if `s` (a link/image destination) can be written as a bare,
  unbracketed CommonMark destination: every `)` has an earlier matching
  `(` (confirmed at a REPL: this parser's bare-destination scanner
  tolerates a balanced inner `(...)` -- e.g. a Wikipedia-style URL ending
  in `(plant)` -- but stops at the first UNMATCHED `)`, treating
  everything after it as ordinary trailing text)."
  [s]
  (loop [i 0 depth 0]
    (cond
      (neg? depth) false
      (>= i (count s)) (zero? depth)
      (= (nth s i) \() (recur (inc i) (inc depth))
      (= (nth s i) \)) (recur (inc i) (dec depth))
      :else (recur (inc i) depth))))

(defn- render-destination
  "`target` (a Link's `:target` or an Image's `:src`, sec4.4) as a
  CommonMark link destination: bare when `balanced-destination?` holds
  and `target` carries no `<`/`>` of its own, else the `<...>` angle-
  bracket form (`<`, `>` and `\\` backslash-escaped inside it, per
  CommonMark's own rule for that form) -- confirmed at a REPL that this
  combination round-trips a destination with an unbalanced paren
  correctly either way.

  Throws `ex-info` (`:type ::unrepresentable-destination`) naming
  `target` when it contains whitespace: confirmed at a REPL that NEITHER
  destination form this parser accepts tolerates an embedded space (the
  angle-bracket form's own CommonMark-specified support for one is not
  honored by this parser build), so there is no spelling to fall back to
  -- see this namespace's own docstring for why that is not reachable
  from an as-parsed document in the first place."
  [target]
  (when (re-find #"\s" target)
    (throw (ex-info
            (str "hdoc emitter cannot represent a link/image destination containing whitespace: "
                 (pr-str target))
            {:type ::unrepresentable-destination :target target})))
  (if (and (balanced-destination? target) (not (re-find #"[<>]" target)))
    target
    (str "<" (-> target
                 (str/replace "\\" "\\\\")
                 (str/replace "<" "\\<")
                 (str/replace ">" "\\>"))
         ">")))

;; --- Inline code spans (CommonMark sec6.1) ----------------------------------

(defn- code-fence-for
  "A run of backticks one longer than the longest consecutive backtick
  run inside `text`, so a fence built from it can never be closed early
  by any run `text` contains internally (see `render-code-span`'s own
  docstring for the one edge case this alone does not cover). Used for an
  inline `:code` span, where even a single backtick is already a valid
  fence -- `code-block-fence-for` is the TASK-97 variant enforcing a
  fenced code BLOCK's own minimum of three."
  [text]
  (let [runs (map count (re-seq #"`+" text))]
    (apply str (repeat (inc (apply max 0 runs)) "`"))))

(defn- code-block-fence-for
  "`code-fence-for`, floored at three backticks -- a fenced code BLOCK
  (unlike an inline `:code` span) is only recognized as a fence at all
  with a run of 3 or more (CommonMark sec4.5, `code-fence-open-re`'s own
  `{3,}` on the read side); confirmed at a REPL that a bare `code-fence-
  for` result on a backtick-free body (a single backtick) round-trips to
  an inline `:code` span instead of a `:code-block` -- a real bug this
  function exists to fix."
  [text]
  (apply str (repeat (max 3 (count (code-fence-for text))) "`")))

(defn- render-code-span
  "Renders inline `Code`'s own `:text` (sec4.4) as a backtick-fenced code
  span: `` `text` ``, with the fence lengthened (`code-fence-for`) past
  any internal backtick run, and a single padding space added on each
  side ONLY when `text` itself starts or ends with a backtick (confirmed
  at a REPL: without it, a backtick directly touching the fence merges
  into one longer run and the span fails to close at all -- e.g.
  `` `x` `` with content `` `x`` `` mis-parses as literal text). Not
  padded merely for starting/ending in a plain space: standard CommonMark
  has the reader strip exactly one such padding space back off on
  re-parse, but this parser build does not (confirmed at a REPL:
  `` ` a ` `` round-trips to `\" a \"`, not `\"a\"`) -- so, for THIS
  parser, adding a padding space the text does not already need would
  only bake in an extra one on re-parse (confirmed at a REPL to be the
  actual cause of a real mismatch before this was narrowed to the
  backtick-only condition). See this namespace's own docstring for the
  one narrow gap that remains: a `:text` that itself starts or ends with
  a backtick still round-trips with one extra space baked in, since the
  padding IS structurally required there and this parser keeps it."
  [text]
  (let [fence (code-fence-for text)
        pad? (or (str/starts-with? text "`") (str/ends-with? text "`"))
        padding (if pad? " " "")]
    (str fence padding text padding fence)))

;; --- Inline dispatch (TASK-88) ----------------------------------------------

(declare render-inlines)
(declare render-block)
(declare render-blocks)

(defn- render-wrapped
  "Renders `inline`'s own `:inlines` through `render-inlines`, then wraps
  the result between `open` and `close` -- the shared shape behind
  `:emph`/`:strong`. Safe with no delimiter-run care of its own: an
  `:emph`/`:strong` node reachable from an as-parsed document was only
  ever recognized as one because its own boundary content is already
  delimiter-flanking-compliant (CommonMark's own emphasis rule), so
  wrapping it back in the same bare `*`/`**` reproduces exactly the
  source that produced it -- including the identical-adjacent-delimiter
  case (`***text***`, `:emph` wrapping `:strong`), which round-trips
  correctly precisely because concatenation introduces no separator
  between the two delimiter runs (confirmed at a REPL)."
  [ctx inline footnotes open close]
  (let [[text footnotes'] (render-inlines ctx (:inlines inline) footnotes)]
    [(str open text close) footnotes']))

(def ^:private role-span-classes
  "The `.class` `render-role-span` writes for each of the four Inline
  variants with no `.hdoc` surface syntax of their own (AC #7, this
  namespace's own docstring) -- the class name an author would actually
  reach for, matching the task's own `[text]{.strike}` example."
  {:strike "strike" :small-caps "small-caps" :sub "sub" :sup "sup"})

(defn- render-role-span
  "Renders one of `:strike`/`:small-caps`/`:sub`/`:sup` as the role span
  an author would use for that effect (AC #7): `[text]{.kind}`. Re-
  parsing this always yields a `:span`, never the original tag -- see
  `lossy-role-span-diagnostics`, the separate function a caller runs
  ahead of emission to report every occurrence."
  [ctx inline footnotes]
  (let [[text footnotes'] (render-inlines ctx (:inlines inline) footnotes)]
    [(str "[" text "]{." (role-span-classes (:t inline)) "}") footnotes']))

(defn- render-link
  "Renders Link `inline` (sec4.4: `:target`/`:inlines`/`:attr`) as
  `[text](destination)`, plus a trailing `attr-group` when `:attr`
  carries anything (AC #6's own attribute-group syntax, attached here the
  same way `haselnuss.parser` reads it back -- immediately after the
  closing `)`, no space)."
  [ctx inline footnotes]
  (let [[text footnotes'] (render-inlines ctx (:inlines inline) footnotes)
        destination (render-destination (:target inline))
        group (attr-group (:attr inline))]
    [(str "[" text "](" destination ")" group) footnotes']))

(defn- render-image
  "Renders Image `inline` (sec4.4: `:src`/`:alt`/`:attr`) as
  `![alt](src)`, plus a trailing `attr-group`. `:alt` is a plain string
  (unlike Link's `:inlines`), so it is escaped as literal text
  (`escape-text`) rather than rendered as Inlines -- mirroring how
  `haselnuss.parser/alt-text` flattens it back down to a string on
  re-parse regardless of what markup this bracket's own content
  contains, escaped plain text is the only spelling that reproduces the
  same string both ways."
  [inline]
  (let [alt (escape-text (:alt inline))
        destination (render-destination (:src inline))
        group (attr-group (:attr inline))]
    (str "![" alt "](" destination ")" group)))

(defn- render-span
  "Renders Span `inline` (sec4.4/5.6: `:inlines`/`:attr`) as
  `[text]{attr}`. An as-parsed `:span` always carries a non-empty `:attr`
  (`haselnuss.parser/convert-children` never builds one otherwise -- see
  this namespace's own docstring), so `attr-group` here is assumed
  non-empty for any `:span` this emitter is actually run against; an
  empty group would not even be recognized as an attribute group on
  re-parse (confirmed at a REPL: `[text]{}` stays literal text)."
  [ctx inline footnotes]
  (let [[text footnotes'] (render-inlines ctx (:inlines inline) footnotes)
        group (attr-group (:attr inline))]
    [(str "[" text "]" group) footnotes']))

(defn- render-flat-prose
  "Renders `inlines` (a CiteItem's own `:prefix`/`:suffix`, sec4.4/5.11)
  to a single line of escaped text, discarding any footnotes accumulator
  (a citation prefix/suffix can never structurally contain a `:note`:
  `haselnuss.parser/mini-parse-inlines` re-parses it as its own isolated
  fragment with no footnote DEFINITIONS of its own in scope, so a
  `[^label]` marker there would already have thrown
  `::undefined-footnote` at the original parse -- confirmed by that
  function's own docstring). `escape-line-start-hazard` is applied once,
  not per physical line: a citation segment can never itself span a
  source line (`haselnuss.parser/cite-bracket-close-index` never crosses
  one), so the whole rendered result is always one line."
  [ctx inlines]
  (let [[text _] (render-inlines ctx inlines [])]
    (escape-line-start-hazard text)))

(defn- check-no-cite-affix-semicolon!
  "Throws `ex-info` (`:type ::unrepresentable-cite-affix`) naming
  `cite-key` and `rendered` when a CiteItem's own rendered prefix/suffix
  text contains a literal `;`: `haselnuss.parser`'s own citation-bracket
  recognition (`citation-bracket-content?`) and item extraction
  (`cite-bracket-node->cite`) both split a bracket's RAW source text on
  every `;` character unconditionally, before backslash-escape
  resolution ever runs -- confirmed at a REPL that backslash-escaping
  the `;` here does NOT prevent that split, the same class of gap this
  namespace's own docstring documents for `{#`/`{.`/`{key=` immediately
  after a brace. There is therefore no `.hdoc` spelling for a `;` in a
  CiteItem's own prefix/suffix at all; reported here (review comment #4
  on TASK-95) rather than silently corrupting the whole citation bracket
  on re-parse the way it did before this check existed."
  [cite-key rendered]
  (when (str/includes? rendered ";")
    (throw (ex-info
            (str "hdoc emitter cannot represent a `;` in citation " (pr-str cite-key)
                 "'s prefix/suffix text: " (pr-str rendered))
            {:type ::unrepresentable-cite-affix :key cite-key :text rendered}))))

(defn- render-cite-item
  "Renders one CiteItem `item` (sec4.4: `:key`/`:prefix`/`:suffix`/
  `:mode`) as one `;`-separated segment of a bracketed citation
  (`haselnuss.parser/citation-segment-re`'s own grammar, written back
  rather than read): `prefix -@key, suffix`, `-` present only for
  `:mode :year`. The suffix separator is always written as `, ` (comma
  space) regardless of how the ORIGINAL source separated it -- the AST
  never records that choice (`strip-leading-comma` throws it away on the
  way in), and both a comma-led and a bare-space-led original reduce to
  the identical cleaned suffix text either way, so `, ` is the one
  canonical spelling this emitter always picks (doc-7's own \"re-spells
  canonically\" standard). `check-no-cite-affix-semicolon!` guards both
  `prefix` and `suffix` before assembly: see its own docstring for why a
  `;` there has no `.hdoc` spelling at all, escaped or not."
  [ctx {cite-key :key :keys [mode prefix suffix]}]
  (let [rendered-prefix (when (seq prefix) (render-flat-prose ctx prefix))
        rendered-suffix (when (seq suffix) (render-flat-prose ctx suffix))]
    (some->> rendered-prefix (check-no-cite-affix-semicolon! cite-key))
    (some->> rendered-suffix (check-no-cite-affix-semicolon! cite-key))
    (str (when rendered-prefix (str rendered-prefix " "))
         (when (= :year mode) "-")
         "@" cite-key
         (when rendered-suffix (str ", " rendered-suffix)))))

(defn- render-cite
  "Renders Cite `inline` (sec4.4: `:items`) as either the bare author-
  in-text form (`@key`, when `:items` is the single, prefix/suffix-free,
  `:mode :author` item ONLY that shape ever produces --
  `haselnuss.parser/cite-author-node` is the only path that ever
  constructs one, mirroring `haselnuss.parser/cite-alt-text`'s own,
  identical test) or the bracketed form (`[item; item...]`,
  `render-cite-item` per `;`-separated segment)."
  [ctx {:keys [items]}]
  (if (= :author (:mode (first items)))
    (str "@" (:key (first items)))
    (str "[" (str/join "; " (map (partial render-cite-item ctx) items)) "]")))

(defn- indent-lines
  "Indents every physical line of `text` by `width` spaces -- shared by
  `indent-block-continuation` (a footnote definition's own fixed 4-space
  continuation) and `render-list-item-blocks` (TASK-97, whose own
  continuation width is a list item's marker width, not a fixed
  constant)."
  [width text]
  (let [prefix (apply str (repeat width \space))]
    (str/join "\n" (map #(str prefix %) (str/split text #"\n" -1)))))

(defn- indent-continuation-lines
  "Indents every physical line of `text` by `width` spaces, EXCEPT its own
  first line -- the shape a container's FIRST Block needs (TASK-97): its
  first physical line sits directly after the marker that introduced the
  whole span (`\"[^1]: \"`, a list item's own `\"- \"`/`\"1. \"`), which
  lazy continuation covers with no indent of its own, but every physical
  line AFTER that first one needs the SAME indent every later Block gets
  (`indent-lines`) -- not merely tolerates it, per `indent-block-
  continuation`'s own docstring, but actually NEEDS it whenever that
  first Block's own rendering contains a genuinely blank physical line of
  its own (only possible for a LOOSE `:list`, TASK-97's own addition: two
  loose items joined by a bare blank line, confirmed at a REPL to
  otherwise end the footnote/list-item's own continuation early and
  strand the second item as a stray top-level Block, the identical
  failure mode TASK-95 review comment #2 already found and fixed for a
  :note's SECOND Block -- this is the same bug, reached through the
  FIRST Block instead once a Block type with its own internal blank line
  became renderable)."
  [width text]
  (let [lines (str/split text #"\n" -1)
        prefix (apply str (repeat width \space))]
    (str/join "\n" (cons (first lines) (map #(str prefix %) (rest lines))))))

(defn- indent-block-continuation
  "Indents every physical line of `text` (a rendered Block, ALWAYS one
  after the first inside a `:note`'s own `:blocks`) by four spaces --
  the CommonMark footnote-definition continuation this parser's
  `FootnoteExtension` requires so a blank-line-separated SECOND (or
  later) top-level Block stays part of the definition on re-parse,
  rather than escaping it as a stray top-level Block of its own
  (confirmed at a REPL: after the blank line, the footnote's own
  content column is a fixed 4, unrelated to the marker's own textual
  width -- `[^10]:` needs the identical 4, not 6 -- and 3 is not
  enough). The same \"indent every continuation line\" treatment
  CommonMark/pandoc-style footnotes use, mirroring how
  `render-block-quote` prefixes every line of ITS OWN nested content
  with `> ` for the identical reason -- confirmed at a REPL that this
  is safe even where it is not strictly required (a lazily continued
  physical line WITHIN one Block, e.g. a `:para`'s own internal soft
  break, tolerates the same four-space indent without turning into a
  nested indented code block, since it only restates the footnote's own
  existing content column rather than adding a further one)."
  [text]
  (indent-lines 4 text))

(def ^:private unrepresentable-nested-block-types
  "The two Block types `check-representable-nested-block!` refuses inside
  a `:block-quote`, a `:note`, or a `:list` item (TASK-97's own docstring
  section on lists explains why: both are recognized by a raw, container-
  oblivious line scan on the read side, so no indentation reaches them
  correctly from inside a flexmark container)."
  #{:directive :include})

(defn- check-representable-nested-block!
  "Throws `ex-info` (`:type ::unrepresentable-nested-block`) naming
  `block`'s own `:t` and `context` (a short description of the container,
  e.g. \"a block quote\") if `block` is a `:directive`/`:include` -- see
  `unrepresentable-nested-block-types`'s own docstring for why neither has
  any `.hdoc` spelling in that position at all. A no-op for every other
  Block type."
  [context block]
  (when (contains? unrepresentable-nested-block-types (:t block))
    (throw (ex-info
            (str "hdoc emitter cannot represent a " (pr-str (:t block)) " nested inside " context
                 ": haselnuss.parser recognizes ::: directive fences, !name lines and !include "
                 "lines via a raw, "
                 "container-oblivious line scan, so no indentation makes this reachable from real parsing")
            {:type ::unrepresentable-nested-block :t (:t block) :context context}))))

(defn- render-note-blocks
  "Renders a `:note`'s own `:blocks` to `[content footnotes']`: the first
  Block follows the `[^n]: ` marker directly on its own first physical
  line, every line AFTER that first one indented
  (`indent-continuation-lines`, TASK-97 -- see its own docstring for why
  even the first Block needs this now, not merely tolerates it); every
  Block after the first is separated by a blank line and indented in
  full (`indent-block-continuation`) so it stays inside the footnote
  definition on re-parse rather than silently escaping it. `footnotes`
  threads through every Block in order, so a nested `:note` reached from
  a later top-level Block still gets a strictly later number than one
  reached from an earlier one. A `:directive`/`:include` anywhere in
  `blocks` throws via `check-representable-nested-block!` before any
  rendering starts (TASK-97): neither has a `.hdoc` spelling inside a
  footnote definition, for the same raw-line-scan reason a list item or
  a block quote can't hold one either."
  [ctx blocks footnotes]
  (run! (partial check-representable-nested-block! "a note") blocks)
  (reduce-kv (fn [[text footnotes] idx block]
               (let [[block-text footnotes'] (render-block ctx block footnotes)
                     piece (if (zero? idx)
                             (indent-continuation-lines 4 block-text)
                             (indent-block-continuation block-text))]
                 [(if (zero? idx) piece (str text "\n\n" piece)) footnotes']))
             ["" footnotes]
             (vec blocks)))

(defn- render-note
  "Renders footnote marker `note` (an Inline `:note`, sec4.4/5.12) as
  `[^n]`, its sequential number reserved BEFORE rendering `note`'s own
  `:blocks` (so a nested footnote inside them gets a strictly later
  number, mirroring `haselnuss.emit.html/render-note`'s identical
  reservation order) -- `emit-document` appends the deferred
  `[^n]: ...` definition for every entry `footnotes'` accumulates, once,
  after the whole body. `note`'s own `:blocks` recurse through the
  ordinary Block dispatch via `render-note-blocks` (AC #6), which indents
  every physical line after the first (`indent-continuation-lines`/
  `indent-block-continuation`, see their own docstrings) so a footnote
  holding two or more Blocks of ANY type this namespace supports --
  including, after TASK-97, a `:list`/`:table`/etc. -- round-trips
  correctly rather than silently corrupting; only a genuinely unknown
  `:t`, or a `:directive`/`:include` (`check-representable-nested-block!`
  -- neither has a `.hdoc` spelling inside a footnote definition at all),
  still throws."
  [ctx note footnotes]
  (let [n (inc (count footnotes))
        marker (str "[^" n "]")
        reserved (conj footnotes {:number n :text nil})
        [content footnotes'] (render-note-blocks ctx (:blocks note) reserved)
        filled (mapv (fn [entry] (if (= n (:number entry)) (assoc entry :text content) entry))
                     footnotes')]
    [marker filled]))

(defn- escape-highlight-boundary
  "`text` (a highlight's own already-escaped rendered content) with a
  leading or trailing `=` backslash-escaped.

  `escape-text` cannot see this one: it escapes an `=` that has another
  `=` AFTER IT IN THE SAME STRING, and a highlight's boundary `=` has
  the emitter's own `==` delimiter next to it instead, in a string that
  does not exist yet. Left alone, a highlight of the single character
  `=` emitted `=====`, which re-parses as no highlight at all -- the
  round trip lost the node (found by test)."
  [text]
  ;; The one string whose leading and trailing `=` are the SAME character.
  ;; Tested before anything is prefixed, and by equality rather than by a
  ;; suffix: a `\=` at the end of the already-escaped text is just as
  ;; often an escaped BACKSLASH followed by a bare `=` (`escape-text`
  ;; writes `\\` for a literal backslash), and treating that as
  ;; "already escaped" let the `=` escape out of the node -- the
  ;; highlight silently moved its own edge (found by review, fuzzing
  ;; every string over {= \ a} up to length four).
  (let [single-equals? (= "=" text)
        text (cond-> text (str/starts-with? text "=") (->> (str "\\")))]
    (cond-> text
      (and (not single-equals?) (str/ends-with? text "="))
      (as-> escaped (str (subs escaped 0 (dec (count escaped))) "\\=")))))

(defn- render-highlight
  "Renders a `:highlight` (sec4.4) as `==marked text==`, plus its own
  attribute group when it carries one (TASK-123 AC #1/#2). An EXACT
  round trip, unlike the four role spans: a highlight has a surface
  spelling of its own (sec5.6), so the node this writes re-parses to the
  same node.

  EXCEPT when one highlight is nested inside another, which two
  overlapping annotations produce and which `==...==` cannot spell at
  all: the inner `==` closes the outer opener, so `==a ==b== c==` parses
  as ONE highlight over `a ==b`. Written naively it emitted four `=`
  characters that were never in the document and left the tail unmarked
  (found by review). The inner one is written as a role span instead --
  `[text]{.highlight}`, which re-parses as a `:span` -- which is
  content-preserving and kind-lossy, exactly the contract `:strike`/
  `:small-caps`/`:sub`/`:sup` already round-trip under, and is reported
  the same way."
  [ctx inline footnotes]
  (let [[text footnotes'] (render-inlines (assoc ctx :in-highlight? true)
                                          (:inlines inline) footnotes)]
    [(if (:in-highlight? ctx)
       (str "[" text "]" (attr-group (update (:attr inline) :classes
                                             (fnil conj []) "highlight")))
       (str "==" (escape-highlight-boundary text) "==" (attr-group (:attr inline))))
     footnotes']))

(def built-in-inline-handlers
  "The built-in `:t -> (fn [ctx inline footnotes] -> [rendered
  footnotes'])` table `render-inline` dispatches through (TASK-88): every
  Inline variant `haselnuss.ast` defines (AC #4)."
  {:str (fn [_ctx inline footnotes] [(escape-text (:text inline)) footnotes])
   :space (fn [_ctx _inline footnotes] [" " footnotes])
   :soft-break (fn [_ctx _inline footnotes] ["\n" footnotes])
   ;; A trailing backslash before the line ending (confirmed at a REPL to
   ;; round-trip to :line-break), not two-or-more trailing spaces: no
   ;; invisible whitespace left sitting at the end of a line.
   :line-break (fn [_ctx _inline footnotes] ["\\\n" footnotes])
   :emph (fn [ctx inline footnotes] (render-wrapped ctx inline footnotes "*" "*"))
   :strong (fn [ctx inline footnotes] (render-wrapped ctx inline footnotes "**" "**"))
   :strike render-role-span
   :small-caps render-role-span
   :sub render-role-span
   :sup render-role-span
   :code (fn [_ctx inline footnotes] [(render-code-span (:text inline)) footnotes])
   ;; Raw TeX, verbatim, no escaping: an as-parsed MathInline's own :tex
   ;; is guaranteed to start/end on non-whitespace and never cross a
   ;; newline (haselnuss.parser/inline-math-closing-index's own scan
   ;; rules), so it is always safe to splice directly between $ signs.
   :math-inline (fn [_ctx inline footnotes] [(str "$" (:tex inline) "$") footnotes])
   :link render-link
   :image (fn [_ctx inline footnotes] [(render-image inline) footnotes])
   :span render-span
   ;; :label is always already ref-token-char?'s own alphabet
   ;; (letter/digit/_/-/:), which needs no escaping of its own.
   :cross-ref (fn [_ctx inline footnotes] [(str "@" (:label inline)) footnotes])
   :cite (fn [ctx inline footnotes] [(render-cite ctx inline) footnotes])
   :note render-note
   ;; TASK-123 (doc-8 sec3). A `:highlight` has a surface spelling of its
   ;; own, so this is an EXACT round trip -- `render-wrapped` plus the
   ;; attribute group `==text=={.important #hl:a}` re-parses into the
   ;; same node.
   :highlight render-highlight
   ;; A `:sticky-note` has NO inline surface syntax, by design (doc-8
   ;; sec3), so it writes back as a footnote: the marker at its position
   ;; and its own Blocks as the definition. Content-preserving and
   ;; kind-lossy, exactly the contract :strike/:small-caps/:sub/:sup
   ;; already round-trip under -- and it goes through `render-note`
   ;; itself rather than a copy of it, so a degraded note shares the one
   ;; numbering and deferral mechanism and cannot collide with a real
   ;; footnote's number (doc-8 pitfall 5).
   :sticky-note render-note})

(defn- unsupported-inline!
  "The declared default `render-inline` dispatches an unrecognized Inline
  `:t` to (TASK-88 AC #3): throws `ex-info` (`:type ::unsupported-inline`)
  naming it."
  [_ctx inline _footnotes]
  (throw (ex-info
          (str "hdoc emitter does not support inline type " (pr-str (:t inline)))
          {:type ::unsupported-inline :inline inline})))

(defn- render-inline
  "Renders one Inline to `[text footnotes']`: looks `inline`'s own `:t`
  up in `ctx`'s `:inline-handlers` table (TASK-88 AC #1 --
  `built-in-inline-handlers` merged with any document-specific additions
  `emit-document`'s own `:inline-handlers` opt supplied) and calls the
  handler found there, or `unsupported-inline!` when there is none."
  [ctx inline footnotes]
  ((get (:inline-handlers ctx) (:t inline) unsupported-inline!) ctx inline footnotes))

(defn- render-inlines
  "`render-inline` folded over every Inline in `inlines`, in order,
  concatenating rendered text and threading `footnotes` throughout."
  [ctx inlines footnotes]
  (reduce (fn [[text footnotes] inline]
            (let [[inline-text footnotes'] (render-inline ctx inline footnotes)]
              [(str text inline-text) footnotes']))
          ["" footnotes]
          inlines))

;; --- Block dispatch (TASK-88) ------------------------------------------------

(defn- render-para
  "Renders Para `block` (sec4.3: `:inlines`) as its own rendered text,
  with `escape-line-start-hazard` applied to every physical line (this
  namespace's own docstring: the one hazard `escape-text` cannot cover
  unconditionally) -- the only Block variant in this namespace's scope
  whose own content is UNPREFIXED and so can genuinely start a physical
  line; `render-section`'s heading text always follows `# `, and
  `render-block-quote` always prefixes every line with `> `, so neither
  needs this check of its own."
  [ctx block footnotes]
  (let [[text footnotes'] (render-inlines ctx (:inlines block) footnotes)
        escaped (str/join "\n" (map escape-line-start-hazard (str/split text #"\n" -1)))]
    [escaped footnotes']))

(defn- render-block-quote
  "Renders BlockQuote `block` (sec4.3: `:blocks`) as its own nested
  content (`render-blocks`) with `> ` prefixed on every physical line --
  a blank separator line between two nested blocks becomes a bare `>`
  (no trailing space) rather than an actually-empty line, since an
  empty line would end the quote early rather than continuing it. An
  as-parsed `:block-quote` never carries a populated `:attr` --
  `haselnuss.parser`'s own BlockQuote conversion has no attribute-group
  syntax to attach one from at all (see this namespace's own docstring)
  -- so `:attr` is not rendered here. A `:directive`/`:include` anywhere
  in `:blocks` throws via `check-representable-nested-block!` before any
  rendering starts (TASK-97): see that function's own docstring."
  [ctx block footnotes]
  (run! (partial check-representable-nested-block! "a block quote") (:blocks block))
  (let [[inner footnotes'] (render-blocks ctx (:blocks block) footnotes)
        prefixed (str/join "\n"
                           (map (fn [line] (if (str/blank? line) ">" (str "> " line)))
                                (str/split inner #"\n" -1)))]
    [prefixed footnotes']))

(defn- render-section
  "Renders Section `block` (sec4.3: `:level`/`:heading`/`:blocks`/
  `:attr`) as an ATX heading (`#` * `:level`, a space, the heading's own
  rendered Inlines) plus a trailing `attr-group` when `:attr` carries
  anything (AC #6), then its own nested Blocks after a blank line. A
  Section's own heading text is never at risk of the leading-`#`/`:::`/
  `---` hazards `render-para` guards against: it always follows `# `
  (or `## `, ...), never column 0, and an ATX heading is a single
  physical line by construction (`haselnuss.parser` never produces a
  `:soft-break`/`:line-break` inside one)."
  [ctx block footnotes]
  (let [[heading-text footnotes'] (render-inlines ctx (:heading block) footnotes)
        marker (str (apply str (repeat (:level block) "#")) " ")
        group (attr-group (:attr block))
        heading-line (str marker heading-text (when (seq group) (str " " group)))
        [body footnotes''] (render-blocks ctx (:blocks block) footnotes')]
    [(if (str/blank? body) heading-line (str heading-line "\n\n" body)) footnotes'']))

;; --- Code and math blocks (TASK-97) ------------------------------------------

(defn- check-representable-lang!
  "Throws `ex-info` (`:type ::unrepresentable-lang`) naming `lang` if it
  contains whitespace or a backtick, either of which would break the
  fenced code block's own opening line -- unreachable from real parsing
  (`fenced-lang` only ever extracts a single whitespace-delimited token),
  a narrow guard against a hand-built/transform-produced `:code-block`
  only. A no-op for nil `lang` (no language at all)."
  [lang]
  (when (and lang (re-find #"[\s`]" lang))
    (throw (ex-info (str "hdoc emitter cannot represent code-block language " (pr-str lang))
                    {:type ::unrepresentable-lang :lang lang}))))

(defn- render-code-block
  "Renders CodeBlock `block` (sec4.3: `:lang`/`:text`) as a fenced code
  block, the fence (`code-block-fence-for`) one backtick longer than the
  longest backtick run `:text` itself contains, and never shorter than
  three -- a fenced code BLOCK, unlike an inline `:code` span, is only
  recognized as a fence at all with 3+ backticks -- so a body holding
  backticks can never close it early. `:text` is spliced in completely
  verbatim -- a fenced code block's content is raw, unescaped text on the
  read side (`code-block`'s own docstring in `haselnuss.parser`) -- with
  exactly one trailing newline ensured before the closing fence so it
  always lands on its own physical line, whether or not `:text`
  (as-parsed, always `getContentChars`-terminated) already carries one."
  [_ctx block footnotes]
  (check-representable-lang! (:lang block))
  (let [lang (:lang block)
        text (:text block)
        fence (code-block-fence-for text)
        body (if (or (str/blank? text) (str/ends-with? text "\n")) text (str text "\n"))]
    [(str fence lang "\n" body fence) footnotes]))

(defn- render-math-block
  "Renders MathBlock `block` (sec4.3/sec5.7: `:tex`/`:attr`) as the
  two-line `$$`/`$$ {...}` fenced form (never the single-line `$$ ... $$`
  shorthand -- this namespace's own docstring explains why), `:tex`
  spliced in verbatim between the fence lines (raw TeX on the read side,
  same as `:code-block`'s own `:text`) and `attr-group` trailing the
  CLOSING fence, matching `math-attr-group`'s own read-side placement."
  [_ctx block footnotes]
  (let [group (attr-group (:attr block))]
    [(str "$$\n" (:tex block) "\n$$" (when (seq group) (str " " group))) footnotes]))

;; --- Figures (sec5.8, TASK-97) -----------------------------------------------

(defn- render-figure
  "Renders Figure `block` (sec4.3: `:content`/`:caption`/`:attr`) back to
  the standalone-image shorthand (`![alt](src){#id ...}`) -- the ONLY
  shape `haselnuss.parser` ever builds a `:figure` from (this namespace's
  own docstring). Throws `ex-info` (`:type ::unsupported-figure-content`)
  naming `block` if `:content` is not exactly a `:para` wrapping one
  `:image`, rather than guessing at a spelling for a shape this parser
  never produces. The bracket text comes from the Figure's own `:caption`
  Inlines (`render-inlines`, the SAME richer conversion
  `haselnuss.parser/figure-caption` used to build it from the original
  source), not from the Image's flattened `:alt` string -- re-parsing the
  rendered caption reproduces both fields at once. The written attribute
  group combines the Figure's own `:id` with the content Image's own
  `:classes`/`:props` (never the Figure's own duplicate `:classes`/
  always-empty `:props` -- `convert-block`'s own docstring in
  `haselnuss.parser` explains why the two Attrs hold what they do)."
  [ctx block footnotes]
  (let [content (:content block)
        image (when (= :para (:t content)) (first (:inlines content)))]
    (when-not (and image (= :image (:t image)) (= 1 (count (:inlines content))))
      (throw (ex-info
              (str "hdoc emitter can only represent a :figure whose :content is the standalone-image "
                   "shorthand (a :para wrapping exactly one :image), not " (pr-str content))
              {:type ::unsupported-figure-content :block block})))
    (let [[caption-text footnotes'] (render-inlines ctx (:caption block) footnotes)
          destination (render-destination (:src image))
          group (attr-group (assoc (:attr image) :id (:id (:attr block))))]
      [(str "![" caption-text "](" destination ")" group) footnotes'])))

;; --- Directives and includes (sec5.5/TASK-38, TASK-97) ----------------------

(def ^:private directive-fence "3-colon fence text, fixed at every nesting depth -- this namespace's own docstring explains why." ":::")

(def ^:private bang-directive-marker
  "The `!` opening sec5.5's one-line directive spelling (TASK-114), the
  form this namespace writes for a directive with no body at all."
  "!")

(def ^:private bang-directive-name-re
  "A directive `:name` the one-line spelling can actually write:
  `haselnuss.parser/bang-directive-line-re`'s own name production,
  restated here because a `:name` reaching this emitter has not
  necessarily come from that parser (see `bang-spellable?`). Anchored,
  used with `re-matches`."
  #"[A-Za-z_][\w-]*")

(def ^:private bang-directive-reserved-names
  "Directive names the one-line spelling refuses, mirroring
  `haselnuss.parser/bang-directive-reserved-names`. Emitting `!include`
  for a directive named \"include\" would not round-trip: the parser
  reserves that name, so the line would come back as an ordinary
  paragraph (or, with a group, as a diagnostic) rather than the directive
  that was written.

  Kept as a copy rather than read from the parser because no emitter
  requires `haselnuss.parser` -- the two are pointed at each other in
  both docstrings instead, and
  `one-line-directive-round-trip-test` pins the round trip for every name
  in this set, so a name reserved on one side and not the other fails
  there rather than silently."
  #{"include"})

(defn- bang-spellable?
  "True if Directive `block` can be written in sec5.5's one-line
  `!name{...}` spelling rather than a `:::` fence -- the AST records no
  trace of which form its author wrote (TASK-114 deliberately added
  none), so this emitter picks from the document model alone, and the
  test is simply whether the one-line form can represent this node
  losslessly.

  Three things disqualify a directive, each because re-parsing the
  one-line output would NOT give the same node back:

  - a non-empty `:blocks`, since the one-line form has no body at all;
  - a `:name` that is blank, or that is not shaped like a directive name
    (`bang-directive-name-re`) -- `haselnuss.parser` can only ever
    produce a legal one, but `haselnuss.transform`'s rules build
    Directives directly and `haselnuss.ast` types `:name` as a plain
    `:string`, so `\"\"` (the nameless `:::{}` an author can also write)
    and `\"my directive\"` both reach here;
  - a reserved `:name` (`bang-directive-reserved-names`).

  Everything else keeps the fence. That is the right fallback, but it is
  NOT a claim that the fence can always represent what is left: a `:name`
  the fence header's own grammar cannot spell either -- `\"my directive\"`
  (two name tokens on re-parse), `\"2fast\"`, `\"a.b\"` -- has no `.hdoc`
  spelling in either form, and emits a `:::{my directive}` fence that
  will not re-parse. That hole predates this predicate, since the fence
  header has always used the same name production, and is left where it
  is rather than quietly absorbed here."
  [block]
  (let [dname (:name block)]
    (boolean (and (empty? (:blocks block))
                  (string? dname)
                  (re-matches bang-directive-name-re dname)
                  (not (contains? bang-directive-reserved-names dname))))))

(defn- directive-header-text
  "A directive's own `{name #id .class key=val}` header (sec5.5),
  ALWAYS written -- even `{}`, empty, for a nameless attributeless
  directive -- so every opening fence this namespace writes is
  unambiguously headered (this namespace's own docstring: a non-blank
  header always opens a new level regardless of colon-run length, which
  is what lets `directive-fence` stay a fixed three colons at every
  nesting depth)."
  [directive-name attr]
  (let [tokens (into (if (seq directive-name) [directive-name] []) (attr-tokens attr))]
    (str "{" (str/join " " tokens) "}")))

(defn- render-directive
  "Renders Directive `block` (sec4.3/sec5.5: `:name`/`:blocks`/`:attr`) as
  a generic `:::{name ...}` ... `:::` fence, its own nested `:blocks`
  recursing through the ordinary Block dispatch (AC #5) -- including a
  nested `:directive`/`:include`, which round-trips normally here (unlike
  inside a `:list`/`:block-quote`/`:note`, see this namespace's own
  docstring): `haselnuss.parser/parse-directive-region` recognizes both
  via the same raw-line recursion at any depth, never via container
  indentation. `:fallback` (sec4.5) is not rendered: it exists purely for
  a target that cannot render a directive natively, and this emitter
  always can (it just writes the fence back), so nothing is lost by
  leaving it out of the round trip -- a `:fallback` never survives a
  parse in the first place (`haselnuss.parser` never sets one; only
  `haselnuss.registry`'s own directive rules do, post-resolve, outside
  THE CONTRACT this emitter runs under)."
  [ctx block footnotes]
  (if (bang-spellable? block)
    [(str bang-directive-marker (:name block) (attr-group (:attr block))) footnotes]
    (let [header (directive-header-text (:name block) (:attr block))
          [body footnotes'] (render-blocks ctx (:blocks block) footnotes)]
      [(str directive-fence header (when (seq body) (str "\n" body)) "\n" directive-fence) footnotes'])))

(defn- quote-include-path
  "`src` (an Include's own `:src`, sec4.3/TASK-38) quoted (matching
  double or single quotes, double preferred, mirroring `quote-attr-value`
  and `include-line`'s own \"same contract as TASK-39\" docstring) when it
  contains whitespace OR would otherwise be misread as OPENING a quoted
  path (starts with `\"`/`'`, the same hazard `render-attr-value` guards
  for an attribute value two functions above it in this file -- an
  unreachable shape from real `haselnuss.parser/parse` output, since a
  leading unmatched quote is rejected at the ORIGINAL parse, but reachable
  from a hand-built or transform-produced `:include` node), bare
  otherwise. Throws `ex-info` (`:type ::unrepresentable-include-path`)
  naming `src` when it needs quoting AND contains both quote characters,
  the one shape neither quoting form can carry."
  [src]
  (cond
    (not (or (re-find #"\s" src) (and (seq src) (contains? #{\" \'} (first src))))) src
    (not (str/includes? src "\"")) (str "\"" src "\"")
    (not (str/includes? src "'")) (str "'" src "'")
    :else (throw (ex-info
                  (str "hdoc emitter cannot represent include path " (pr-str src)
                       ", which contains both quote characters and needs quoting")
                  {:type ::unrepresentable-include-path :src src}))))

(defn- render-include
  "Renders Include `block` (sec4.3/TASK-38: `:src`) back to its own
  `!include <path>` line, UNCONDITIONALLY -- never expanded, per THE
  CONTRACT and this namespace's own docstring."
  [_ctx block footnotes]
  [(str "!include " (quote-include-path (:src block))) footnotes])

;; --- Tables (sec5.9, TASK-97) -------------------------------------------------

(defn- table-align-marker
  "The GFM pipe-table alignment-row marker for Col `align` (sec4.3):
  `:---`/`---:`/`:---:`/`---`, mirroring `table-cell-align`'s own inverse
  mapping on the read side."
  [align]
  (case align
    :left ":---"
    :right "---:"
    :center ":---:"
    "---"))

(defn- check-simple-table-cell!
  "Throws `ex-info` (`:type ::unrepresentable-table-cell`) naming `cell`
  if it does not hold EXACTLY one `:para` Block (a pipe-table cell is
  inherently one physical line of inline content -- `table-cell->cell`'s
  own docstring in `haselnuss.parser`), or if its own `:span` is anything
  but 1 (`TablesExtension/COLUMN_SPANS` is disabled on the read side,
  `convert-table`'s own docstring, so GFM pipe tables here have no
  column-span syntax to write one back into regardless -- unreachable
  from real parsing either way, a hand-built/transform-produced Cell
  only)."
  [cell]
  (when-not (and (= 1 (count (:blocks cell))) (= :para (:t (first (:blocks cell)))))
    (throw (ex-info
            (str "hdoc emitter can only represent a table cell holding exactly one :para Block, not "
                 (pr-str (:blocks cell)))
            {:type ::unrepresentable-table-cell :cell cell})))
  (when (and (:span cell) (not= 1 (:span cell)))
    (throw (ex-info
            (str "hdoc emitter cannot represent a spanning table cell (:span " (:span cell)
                 "): GFM pipe tables have no column-span syntax")
            {:type ::unrepresentable-table-cell :cell cell}))))

(defn- render-table-cell
  "Renders Cell `cell` to `[text footnotes']`: its own single `:para`'s
  Inlines (`check-simple-table-cell!` already confirmed there is exactly
  one), with every literal `|` backslash-escaped -- the one character
  this namespace escapes OUTSIDE `always-escaped-chars`, since it is only
  hazardous inside a table row and nowhere else `escape-text` runs
  (confirmed at a REPL: `\\|` inside a pipe-table cell round-trips to a
  literal `|`). Throws `ex-info` (`:type ::unrepresentable-table-cell`)
  naming `cell` if the rendered text contains a literal newline (a
  `:soft-break`/`:line-break` Inline): a pipe-table row is inherently one
  physical line, and no `.hdoc` spelling keeps a cell's content on more
  than one."
  [ctx cell footnotes]
  (check-simple-table-cell! cell)
  (let [[text footnotes'] (render-inlines ctx (:inlines (first (:blocks cell))) footnotes)]
    (when (str/includes? text "\n")
      (throw (ex-info
              (str "hdoc emitter cannot represent a table cell spanning more than one physical line: "
                   (pr-str text))
              {:type ::unrepresentable-table-cell :cell cell})))
    [(str/replace text "|" "\\|") footnotes']))

(defn- render-table-row
  "Renders Row `row` to `[cell-texts footnotes']`, `render-table-cell`
  over every one of its own Cells in order."
  [ctx row footnotes]
  (reduce (fn [[cells footnotes] cell]
            (let [[text footnotes'] (render-table-cell ctx cell footnotes)]
              [(conj cells text) footnotes']))
          [[] footnotes]
          (:cells row)))

(defn- pipe-row
  "`cells` (already-rendered cell texts) joined into one `| a | b |`
  pipe-table row line."
  [cells]
  (str "| " (str/join " | " cells) " |"))

(defn- table-widths-prop
  "The `widths=\"...\"` value to fold back into the caption line's own
  attribute group for `colspec` (Table's own `:colspec`, sec4.3), or nil
  when any column lacks a `:width` -- `column-widths`'s own \"all or
  nothing\" rule on the read side (its docstring in `haselnuss.parser`),
  mirrored exactly here rather than writing a partial list that function
  would refuse to read back."
  [colspec]
  (when (and (seq colspec) (every? :width colspec))
    (str/join " " (map :width colspec))))

(defn- render-table-caption-line
  "Renders Table `block`'s own trailing `: caption {#id ...}` line to
  `[line footnotes']` (`line` nil when there is nothing to say -- no
  caption text, no id, no class, no non-`widths` prop, and no
  reconstructible `widths=` -- the same \"re-spells canonically\"
  simplification `render-front-matter` already applies to an empty
  Meta). `table-widths-prop` folds the column widths back into the
  attribute group's own `widths` prop, the one place
  `haselnuss.parser/column-widths` ever reads it from."
  [ctx block footnotes]
  (let [{:keys [caption colspec attr]} block
        widths (table-widths-prop colspec)
        attr' (cond-> attr widths (assoc-in [:props "widths"] widths))
        group (attr-group attr')
        [caption-text footnotes'] (render-inlines ctx caption footnotes)]
    (if (and (str/blank? caption-text) (str/blank? group))
      [nil footnotes]
      [(str ": " caption-text (when (seq group) (str (when (seq caption-text) " ") group))) footnotes'])))

(defn- render-table
  "Renders Table `block` (sec4.3/sec5.9: `:head`/`:rows`/`:caption`/
  `:colspec`/`:attr`) as a GFM pipe table: header row, alignment row
  (`table-align-marker` per `:colspec` column), every body row, then an
  optional trailing caption line (`render-table-caption-line`)."
  [ctx block footnotes]
  (let [{:keys [head rows colspec]} block
        [head-cells footnotes1] (render-table-row ctx head footnotes)
        alignment-row (pipe-row (map table-align-marker (map :align colspec)))
        [row-lines footnotes2] (reduce (fn [[lines footnotes] row]
                                         (let [[cells footnotes'] (render-table-row ctx row footnotes)]
                                           [(conj lines (pipe-row cells)) footnotes']))
                                       [[] footnotes1]
                                       rows)
        [caption-line footnotes3] (render-table-caption-line ctx block footnotes2)
        lines (into [(pipe-row head-cells) alignment-row] row-lines)]
    [(str (str/join "\n" lines) (when caption-line (str "\n" caption-line))) footnotes3]))

;; --- Lists (sec5.6, TASK-97) --------------------------------------------------

(def ^:private list-item-safe-continuation-types
  "Block types confirmed at a REPL to start a new Block within a TIGHT
  list item with NO blank line before them -- this namespace's own
  docstring (the LISTS section) explains why exactly these four and no
  others: `:section`, `:block-quote`, a nested `:list` and a fenced
  `:code-block`."
  #{:section :block-quote :list :code-block})

(defn- render-list-item-blocks
  "Renders one list item's own `blocks` (a vector, sec4.3's `:items`
  entry) to `[text footnotes']`. `marker` is that item's own rendered
  marker text (`\"- \"`/`\"1. \"`/...), whose WIDTH is the indent every
  Block after the first needs (`indent-lines`), and every physical line
  AFTER the first Block's own first line needs too
  (`indent-continuation-lines`, TASK-97 -- see its own docstring for why:
  a loose nested `:list` as the item's first Block has its OWN internal
  blank line, which otherwise ends the item's continuation early the same
  way a `:note`'s own first Block can) -- only that very first physical
  line, directly after the marker, needs none (lazy continuation covers
  it). `tight?` decides how a later Block is attached: LOOSE always
  separates with a blank line (safe for any Block type, this namespace's
  own docstring); TIGHT has no blank line and so only allows a Block type
  in `list-item-safe-continuation-types`, throwing `ex-info` (`:type
  ::unrepresentable-tight-list-item`) naming the offending type otherwise
  -- see this namespace's own docstring for why. `check-representable-
  nested-block!` runs over every Block in `blocks` up front (a
  `:directive`/`:include` has no spelling in EITHER position)."
  [ctx marker tight? blocks footnotes]
  (run! (partial check-representable-nested-block! "a list item") blocks)
  (let [width (count marker)]
    (reduce-kv
     (fn [[text footnotes] idx block]
       (let [[block-text footnotes'] (render-block ctx block footnotes)]
         (cond
           (zero? idx) [(indent-continuation-lines width block-text) footnotes']
           (not tight?) [(str text "\n\n" (indent-lines width block-text)) footnotes']
           (contains? list-item-safe-continuation-types (:t block))
           [(str text "\n" (indent-lines width block-text)) footnotes']
           :else
           (throw (ex-info
                   (str "hdoc emitter cannot represent a tight list item whose block at index " idx
                        " is " (pr-str (:t block)) " with no blank line before it")
                   {:type ::unrepresentable-tight-list-item :t (:t block) :index idx})))))
     ["" footnotes]
     (vec blocks))))

(defn- render-list
  "Renders List `block` (sec4.3: `:ordered`/`:tight`/`:items`) as a bullet
  (`\"- \"`) or ordered (`\"N. \"`, always numbered from 1 -- this
  namespace's own docstring explains why) list, its items joined by a
  blank line when `:tight` is false or a bare newline when true (the
  SAME boolean that governs whether an item's own later Blocks, see
  `render-list-item-blocks`, get a blank line or not -- both follow from
  the identical CommonMark tightness rule). An as-parsed `:list` never
  carries a populated `:attr` -- `haselnuss.parser` has no attribute-group
  syntax that attaches to a whole list at all (see this namespace's own
  docstring) -- so `:attr` is not rendered here, the same convention
  `render-block-quote` follows for its own identical situation."
  [ctx block footnotes]
  (let [{:keys [ordered tight items]} block]
    (reduce-kv
     (fn [[text footnotes] idx item-blocks]
       (let [marker (if ordered (str (inc idx) ". ") "- ")
             [item-text footnotes'] (render-list-item-blocks ctx marker tight item-blocks footnotes)
             piece (str marker item-text)]
         [(if (zero? idx) piece (str text (if tight "\n" "\n\n") piece)) footnotes']))
     ["" footnotes]
     (vec items))))

(def built-in-block-handlers
  "The built-in `:t -> (fn [ctx block footnotes] -> [rendered
  footnotes'])` table `render-block` dispatches through (TASK-88): every
  Block variant `haselnuss.ast` defines (AC #1) -- TASK-95's own
  (`:section`/`:para`/`:thematic-break`/`:block-quote`) plus TASK-97's
  (`:list`/`:code-block`/`:math-block`/`:figure`/`:table`/`:directive`/
  `:include`)."
  {:section render-section
   :para render-para
   ;; The construct's OWN spelling is literally "---"; emit-document
   ;; guards the one case this cannot escape (a bare :thematic-break as
   ;; the document's very first Block, with no front matter) separately
   ;; -- see this namespace's own docstring.
   :thematic-break (fn [_ctx _block footnotes] ["---" footnotes])
   :block-quote render-block-quote
   :list render-list
   :code-block render-code-block
   :math-block render-math-block
   :figure render-figure
   :table render-table
   :directive render-directive
   :include render-include})

(defn- unsupported-block!
  "The declared default `render-block` dispatches an unrecognized Block
  `:t` to (TASK-88 AC #3): throws `ex-info` (`:type ::unsupported-block`)
  naming it. `haselnuss.ast/registry` has no Block variant left unhandled
  after TASK-97 (`block-and-inline-coverage-test` proves this by a subset
  check), so this path is only ever reached for a genuinely unknown `:t`
  now -- a future schema addition, or a bogus tag."
  [_ctx block _footnotes]
  (throw (ex-info
          (str "hdoc emitter does not support block type " (pr-str (:t block)))
          {:type ::unsupported-block :block block})))

(defn- render-block
  "Renders one Block to `[text footnotes']`: looks `block`'s own `:t` up
  in `ctx`'s `:block-handlers` table (TASK-88 AC #1) and calls the
  handler found there, or `unsupported-block!` when there is none."
  [ctx block footnotes]
  ((get (:block-handlers ctx) (:t block) unsupported-block!) ctx block footnotes))

(defn- render-blocks
  "`render-block` folded over every Block in `blocks`, in order,
  joining each rendered Block with a blank line (CommonMark's own
  block separator) and threading `footnotes` throughout."
  [ctx blocks footnotes]
  (reduce (fn [[text footnotes] block]
            (let [[block-text footnotes'] (render-block ctx block footnotes)]
              [(if (str/blank? text) block-text (str text "\n\n" block-text)) footnotes']))
          ["" footnotes]
          blocks))

;; --- Front matter (sec5.2) ---------------------------------------------------

(defn- flatten-title
  "Meta `:title` (sec4.2: `Inline[]`, always exactly the `:str`/`:space`
  tokens `haselnuss.parser/text->inlines` produces for front matter,
  never anything richer -- `parse-front-matter` only ever builds it that
  way) flattened back to the plain string it came from: `:str` text
  joined, `:space` as a literal space. The exact inverse of
  `text->inlines` for that same restricted shape, so writing it back to
  YAML and re-parsing it reproduces the identical Inlines vector."
  [inlines]
  (str/join (map (fn [inline] (case (:t inline) :str (:text inline) :space " " "")) inlines)))

(defn- meta-yaml
  "Meta `document-meta` (sec4.2) as YAML front-matter text (no `---`
  delimiters of its own -- `render-front-matter` adds those), covering
  every key `haselnuss.parser/parse-front-matter` maps (AC #3): `title`
  (`flatten-title`), `authors` (always the plural key, regardless of
  whether the ORIGINAL source used singular `author` -- `parse-front-
  matter` accepts either and Meta itself has no memory of which), `date`,
  `bibliography`, `cslStyle`, `lang`, `topLevelDivision` (`:top-level-
  division`'s own keyword name). Returns nil when `document-meta` carries
  none of the seven (`render-front-matter`'s own \"no front matter block
  at all\" case); `clj-yaml`'s own `generate-string` handles all the
  actual quoting -- confirmed at a REPL to round-trip a colon, a quote
  and a hash inside a value correctly on its own, so none of that is
  reimplemented here."
  [document-meta]
  (let [pairs (cond-> []
                (:title document-meta) (conj [:title (flatten-title (:title document-meta))])
                (:authors document-meta) (conj [:authors (vec (:authors document-meta))])
                (:date document-meta) (conj [:date (:date document-meta)])
                (:bibliography document-meta) (conj [:bibliography (:bibliography document-meta)])
                (:csl-style document-meta) (conj [:cslStyle (:csl-style document-meta)])
                (:lang document-meta) (conj [:lang (:lang document-meta)])
                (:top-level-division document-meta)
                (conj [:topLevelDivision (name (:top-level-division document-meta))]))]
    (when (seq pairs)
      (yaml/generate-string (into (array-map) pairs) :dumper-options {:flow-style :block}))))

(defn- render-front-matter
  "`document-meta` as a whole `---\\n...\\n---\\n\\n` front-matter block
  (sec5.2), or nil when `meta-yaml` returns nil (no front matter written
  at all -- `haselnuss.parser/split-front-matter` treats its absence and
  an empty document meta identically, so this is a safe, canonical
  simplification for a Meta with nothing to write, per doc-7's own
  \"re-spells canonically\" standard)."
  [document-meta]
  (when-let [yaml (meta-yaml document-meta)]
    (str "---\n" yaml "---\n\n")))

;; --- Footnote definitions ----------------------------------------------------

(defn- render-footnote-definitions
  "The deferred `[^n]: text` definition for every entry `footnotes`
  accumulated (`render-note`), one per blank-line-separated block, or nil
  when there are none -- `emit-document` appends this after the whole
  body, mirroring `haselnuss.emit.html`'s own end-of-document footnote
  list."
  [footnotes]
  (when (seq footnotes)
    (str "\n\n"
         (str/join "\n\n" (map (fn [{:keys [number text]}] (str "[^" number "]: " text)) footnotes)))))

;; --- Entry point --------------------------------------------------------------

(defn emit-document
  "Emits `document` (a `haselnuss.ast/Document` exactly AS PARSED -- no
  `resolve-document`, no `lower`, no `haselnuss.registry`, see this
  namespace's own docstring) as `.hdoc` source text.

  `opts` (default `{}`) accepts `:block-handlers`/`:inline-handlers`
  (TASK-88): extra `:t -> handler` entries merged ON TOP of
  `built-in-block-handlers`/`built-in-inline-handlers` for this call
  alone, the same opt-in extension point `haselnuss.emit.html`/
  `haselnuss.emit.latex`'s own `emit-document` already offers.

  The document's own front matter (`render-front-matter`) comes first,
  then the rendered body (`render-blocks`), then every deferred footnote
  definition (`render-footnote-definitions`).

  GUARDS one hazard `escape-text`/`escape-line-start-hazard` cannot: a
  document with NO front matter (`meta` maps none of the seven keys)
  whose very first top-level Block is a bare `:thematic-break` would
  otherwise put a bare `---` as the file's own first line -- exactly
  `haselnuss.parser/split-front-matter`'s own front-matter-open trigger
  (`(= \"---\" (first lines))`). Unlike a `:para`'s own literal text,
  a ThematicBreak's spelling IS `---`; escaping it would stop it being a
  thematic break at all. So this forces an (empty) front-matter block
  instead, purely to keep that one line from being column 0 in the
  output -- confirmed at a REPL that the resulting `---\\n---\\n\\n---\\n`
  re-parses to the identical `{:meta {} :blocks [{:t :thematic-break}]}`
  either way."
  ([document] (emit-document document {}))
  ([document opts]
   (let [ctx {:block-handlers (merge built-in-block-handlers (:block-handlers opts))
              :inline-handlers (merge built-in-inline-handlers (:inline-handlers opts))}
         front-matter (render-front-matter (:meta document))
         [body footnotes] (render-blocks ctx (:blocks document) [])
         guard-needed? (and (nil? front-matter)
                            (= :thematic-break (:t (first (:blocks document)))))
         front-matter (or front-matter (when guard-needed? "---\n---\n\n"))
         footnote-text (render-footnote-definitions footnotes)]
     (str front-matter body (or footnote-text "") "\n"))))

;; --- Lossy role-span diagnostics (AC #7) -------------------------------------

(def ^:private lossy-role-span-types
  "The four Inline variants `render-role-span` degrades to a `:span` on
  re-parse (this namespace's own docstring, AC #7)."
  #{:strike :small-caps :sub :sup})

(defn- lossy-role-span-visit
  "`haselnuss.walk/fold`'s own `:visit` for `lossy-role-span-diagnostics`:
  appends one `{:type ::lossy-role-span :t :node-path}` diagnostic per
  node whose `:t` is in `lossy-role-span-types`, naming the node's own
  type and its `haselnuss.walk/fold`-computed path, and one `{:type
  ::lossy-sticky-note ...}` per `:sticky-note` (TASK-123).

  A `:sticky-note` is the same KIND of loss for the same reason -- it has
  no `.hdoc` surface syntax, so it comes back as something else that
  carries all of its content -- so it is reported through this one path
  rather than a second reporting shape. Its own type, though, because
  what it degrades to is a footnote rather than a `:span`, and the two
  need different things said about them: a footnote takes a number."
  [acc path _ref-kind node]
  (cond
    (contains? lossy-role-span-types (:t node))
    (conj acc {:type ::lossy-role-span :t (:t node) :path path})

    ;; A `:highlight` is an EXACT round trip except nested inside another
    ;; one, which `==...==` cannot spell -- see `render-highlight`. Only
    ;; the inner one is lossy, so this fires on a highlight that has one
    ;; inside it rather than on every highlight.
    (and (= :highlight (:t node))
         (some #(= :highlight (:t %)) (rest (tree-seq coll? seq node))))
    (conj acc {:type ::lossy-nested-highlight :t :highlight :path path
               :message (str "one highlight nested inside another has no `==...==` spelling --"
                             " the inner `==` would close the outer one -- so the inner"
                             " highlight was written as a `[text]{.highlight}` role span,"
                             " which re-parses as a span")})

    (= :sticky-note (:t node))
    (conj acc {:type ::lossy-sticky-note :t :sticky-note :path path
               ;; The Attr loss is named, not just the footnote number.
               ;; sec4.3 gives `:note` no `:attr` at all, so a footnote
               ;; has nowhere to carry one -- and a diagnostic that
               ;; listed only the number would let an author believe an
               ;; id survived that did not (found by review).
               :message (str "a sticky note has no .hdoc syntax of its own, so it was written"
                             " back as a footnote: every block of its body is preserved, but a"
                             " footnote carries no Attr, so its id, color= and author= are"
                             " lost, and it takes a footnote number")})

    :else acc))

(def ^:private lossy-role-span-walker
  "The `haselnuss.walk/fold` walker for `lossy-role-span-diagnostics`:
  every reference kind the schema has (`walk/every-ref-kind`), since a
  `:strike`/`:small-caps`/`:sub`/`:sup`/`:sticky-note` node could in
  principle be reached from anywhere an Inline can occur."
  {:table walk/table :scope walk/every-ref-kind :visit lossy-role-span-visit})

(defn lossy-role-span-diagnostics
  "Every node in `document` this emitter can only write back as
  something of a different kind, in document order (AC #7; TASK-123):
  `:strike`/`:small-caps`/`:sub`/`:sup` as `{:type ::lossy-role-span :t
  node-type :path path}`, and `:sticky-note` as `{:type
  ::lossy-sticky-note ...}` -- run this BEFORE `emit-document` writes
  `document` out, mirroring `haselnuss.emit.latex/unanchored-reference-
  diagnostics`'s own shape: a separate, pure diagnostics function a
  caller runs alongside emission, not folded into `emit-document`'s own
  return value (which stays a bare string, matching
  `haselnuss.emit.html`/`haselnuss.emit.latex`)."
  [document]
  (walk/fold lossy-role-span-walker [] ::ast/document document))

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
  numbered from the list's optional `:start` (TASK-129), else from 1,
  then consecutively -- doc-7's \"re-spells canonically\" for the item numbers;
  a list directly after a sibling list of its own kind takes `* `/`N) `
  instead, TASK-177, or the two would re-parse as one; and a bullet list
  with an item line CommonMark would read as a thematic break, such as
  `- - - ` for a one-item list nested down to an empty item, takes `+ `,
  TASK-179, see `render-list`). An item's OWN
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
  is the only spelling for a `:math-block`/`:table`/`:figure` as a later
  item Block after one leaving a Paragraph open (below) -- confirmed at a
  REPL that each of those needs its OWN complete flexmark Paragraph,
  which there only a preceding blank line produces; TASK-70's own real
  thesis example, an ordered list
  whose items each hold a `:para` then a `:math-block`, is exactly this
  shape).
  A TIGHT item's later Blocks (`:tight true`) have NO blank line before
  them and so need a Block type CommonMark recognizes as starting a new
  Block on sight, with no ambiguity a blank line would otherwise resolve
  -- confirmed at a REPL for exactly `list-item-safe-continuation-types`
  (`:section`, `:block-quote`, a nested `:list`, a fenced `:code-block`,
  a `:thematic-break`); a `:para` -- or a `:math-block`/`:table`/`:figure`,
  which need the same fresh Paragraph (TASK-193) -- only where the Block
  before it ends on
  a line nothing lazily continues (TASK-186, `closed-leaf-types`: a
  fenced code block, a heading with nothing under it, a break, an empty
  quote, at the end of any quote, list or section that ends in one) or
  where that Paragraph can be closed first (below) -- after a paragraph,
  or a `:math-block`/`:table`/`:figure` (flexmark Paragraphs while
  parsed), it would just become that paragraph's own lazy continuation --
  except after a table, whose rows a line with no unescaped `|` (and not a
  setext underline or a `:` caption line) ends, so a Block starting with such a
  line is written right after it (TASK-201, `table-continuing-line?`).
  A `:thematic-break` there is written
  `***`, not the usual `---` (TASK-181, `tight-continuation-ctx`): `---`
  directly under a paragraph's own text with no blank line is read as a
  SETEXT HEADING underline for that text, silently changing the Block's
  type, while `***` interrupts the paragraph (confirmed at a REPL, also
  after a block quote or nested list ending in one). `render-list-item-blocks` throws
  `::unrepresentable-tight-list-item` for any other type in that
  position, naming it, rather than emitting a spelling this parser would
  read back differently. A `:section` there is safe only while its
  heading is ATX: a setext heading's text lines lazily continue a
  Paragraph the Block before it ends in, as a `:para`'s do, so
  `close-open-paragraph` writes a bare `>` line before any of them where that
  Paragraph is in a block quote and refuses the same way otherwise
  (TASK-185, TASK-186). A `:block-quote` there is refused directly after
  another quote, or a section ending in one (`ends-in-open-block-quote?`,
  TASK-187): its `>` lines would continue that quote, and only a blank
  line ends a quote (after a section, TASK-194's refusal below comes
  first: the quote would re-parse inside the section). Everywhere else two adjacent quotes already have a
  blank line between them and stay two.

  SECTIONS HAVE NO CLOSING LINE (TASK-194). The parser folds every Block
  after a heading into its section until a heading of the same or a
  higher level, in every Block sequence (the document, a section, a
  directive body, a block quote, a list item, a footnote). So after a
  `:section` the only Block any sequence can hold is another `:section`
  of the same or a higher (smaller) level; `check-blocks-after-sections!`
  refuses anything else -- a paragraph, a directive, a deeper section --
  with `::unrepresentable-block-after-section`, naming the sequence and
  the index. For the same reason a section's own `:blocks` may hold only
  sections of a DEEPER level (`check-nested-section-levels!`,
  `::unrepresentable-nested-section`): a child of the same or a higher
  level would re-parse as a sibling. Only a hand-built or transformed AST reaches it: the schema
  in `haselnuss.ast` does not encode this, and the HTML and LaTeX
  emitters render such a document as it stands.

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

  FOOTNOTE LABELS ARE NOT PRESERVED (TASK-135): `:note` carries its Blocks
  and no label, so `[^one]` is written back as `[^1]`, numbered in order of
  first reference. Intended, and documented in the README.

  TASK-135 NARROWED the next paragraph: `:`, `>` and `@` are no longer
  escaped unconditionally. `:` and `>` get a backslash only where they
  start a physical line (`:::` fence, `>` quote marker -- `render-inlines`
  tracks that as `ctx`'s `:line-start?`), and `@` only where
  `haselnuss.parser/reference-at?` says it would re-parse as a reference
  -- or, in link text and a citation prefix, as a `@key` marker that turns
  the bracket into a citation or steals its key (TASK-198,
  `resolve-at-marks`). TASK-170 did the same for `!`: it gets a backslash
  only where it would open an image (a `[` straight after it, whichever inline wrote the
  bracket) or a whole-line `!include`/`!quote`/`!name` construct, decided
  over the whole block's source like a `{` (`bang-needs-escape?`). An
  image's alt text, written by `render-image`, still escapes every `!`.
  Everything below still holds for the other eight characters.

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
  delimiter for `!` to combine with -- which is what TASK-170 later
  relied on to stop escaping that `!`).

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

  A literal `{` is escaped where flexmark would otherwise read a group
  there (`{x}`, `{x y}`, `{.c}`, `{#id}`), or where it starts what the
  parser takes for an attempted group even unclosed (`{.c`, `{#`,
  `{key=`), wherever it sits, and nowhere else (TASK-161, TASK-189,
  `escape-mark`). Since TASK-189 the parser reads an escaped
  `\\{.c}`/`\\{#id}`/`\\{key=v}`/`\\{.c` as the literal text it is
  (`haselnuss.parser/check-no-attribute-attempt!` used to refuse it as
  an unclosed group, so a heading or paragraph ending in a literal
  `{.c}` could not be written back).

  A literal `<` is escaped the same deferred way (TASK-203), where the
  block's source from it on would re-parse as an autolink (`<http://x>`,
  `<me@x.org>` -- a `:link` nobody wrote), as raw inline HTML (`<b>`,
  `</b>`, `<!-- x -->`, `<? x ?>`, which the parser refuses as an
  unsupported inline construct), or, opening a physical line, as the start
  of an HTML block (`<div`, `<?`, likewise refused) -- `lt-needs-escape?`.
  `a < b`, `x <y` and the like stay bare.

  FOUR INLINES WITH NO `.hdoc` SURFACE SYNTAX AT ALL (AC #7, doc-7 \"What
  this does not do\"): `:small-caps`, `:sub`, `:sup` and -- since
  TASK-123 -- `:sticky-note` are schema-valid
  (`haselnuss.ast/registry`) and both existing emitters render them, but
  `haselnuss.parser` produces none of them. (`:strike` was a fifth until
  TASK-137: `~~...~~` is its own spelling, an exact round trip except one
  strike nested inside another, written as a `[text]{.strike}` role span
  -- `render-strike`.) The first three are written
  here as the role span an author would reach for (`[text]{.sub}`, the
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
    backtick has no `.hdoc` spelling: this parser does not strip
    CommonMark's padding space from a code span's stored text
    (confirmed: `` ` a ` `` reads back as `\" a \"`), so padding the
    edge backtick would bake a space in, and unpadded it merges with the
    fence. `check-code-span-lines!` refuses it (`::unrepresentable-code-span`,
    TASK-226); parsed sources cannot produce one.
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
    EVERY `;` outside a code span or inline math, with no backslash-escape
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
    other gap in this list already has.
  - Two bracket shapes the parser reads as something else are refused
    (TASK-200). Link text with a bare cross-reference, author citation or
    `@`-holding autolink in every `;`-segment re-parses as a bracketed
    citation (`::unrepresentable-link-text`/`::unrepresentable-nested-
    link`, see `check-link-text-not-citation!`); source shaped that way is
    a citation (since TASK-207 that includes `[@k; ](u)`, whose blank last
    segment once made it a link with text `@k;`). A citation segment that
    no longer reads as one -- a span, image or other `[`-led Inline in its
    prefix or suffix -- is hand-built only (`::unrepresentable-cite-text`,
    see `check-cite-segment!`).
  - A hand-built citation key or cross-reference label the parser would
    not read back whole is refused (TASK-207): `[@a.b]` is the key `a`
    with suffix `.b`, `@fig:a.b` the label `fig:a` then `.b`
    (`::unrepresentable-cite-text`/`::unrepresentable-cross-ref`, see
    `check-cite-key!`/`check-cross-ref-label!`)."
  (:require [clj-yaml.core :as yaml]
            [clojure.string :as str]
            [clojure.walk :as cwalk]
            [haselnuss.ast :as ast]
            [haselnuss.parser :as parser]
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

(def ^:private contextually-escaped-chars
  "The three characters of `always-escaped-chars` that `escape-text`, given
  a `ctx` to decide with, escapes only where they would re-parse
  differently (TASK-135), not everywhere: `:` and `>` only where they can
  open a block (`:::` directive fence, `>` quote marker -- the first
  non-blank character of a physical line), and `@` only where
  `haselnuss.parser/reference-at?` says it would become a reference.
  Everywhere else they are ordinary prose (`Links: x`, `7 > 3`,
  `user@example.com`) and escaping them rewrote the author's text for
  nothing."
  #{\: \> \@})

(defn- needs-escape?
  "True when the character at `i` of `s` has to be backslash-escaped under
  `ctx` (see `escape-text`)."
  [^String s i ctx]
  (let [c (.charAt s i)]
    (cond
      (not (contains? always-escaped-chars c)) false
      (not (contains? contextually-escaped-chars c)) true
      (= c \@) (or (parser/reference-at? s i ctx)
                   ;; TASK-223: `_` is written `\_`, and a backslash ends
                   ;; the token, so the part before the first `_` is what
                   ;; the written text reads as a reference.
                   (let [u (.indexOf s "_" (int (inc i)))]
                     (and (pos? u) (parser/reference-at? (subs s 0 u) i ctx))))
      :else (str/blank? (subs s 0 i)))))

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
  (let [c (.charAt s i)]
    ;; `~` the same way (TASK-137): a run of two is a `~~strike~~` marker.
    (and (or (= \= c) (= \~ c))
         (< (inc i) (.length s))
         (= c (.charAt s (inc i))))))

(defn- escapes-as-book-ref-brace?
  "True when the character at `i` in `s` is a `{` straight after an `@` --
  in `s` itself, or, at `i` 0, at the end of the text rendered just before
  `s` (`ctx`'s `:after-at?`, tracked by `render-inlines`) -- i.e. the
  opening of a `@{ref}` book reference (TASK-152).

  The brace, not the `@`, because `\\@{x}` does not re-parse as prose: the
  `{x}` left behind is read as an attribute group and refused. `@\\{x}`
  is a literal `@` then a literal `{`. Unconditional on whether a `}`
  follows, because one may follow in a LATER inline (`@\\{a *b*}`), which
  this per-run check cannot see."
  [^String s i ctx]
  (and (= \{ (.charAt s i))
       (if (pos? i) (= \@ (.charAt s (dec i))) (boolean (:after-at? ctx)))))

(def ^:private ^Character escape-mark
  "Put by `escape-text` before every literal `{`, `!` and `<` it does not
  escape itself, and turned into a backslash or nothing by
  `resolve-escape-marks` (TASK-161 for `{`, TASK-170 for `!`, TASK-203 for
  `<`). The character after the mark says which it stands for.

  Whether a `{` needs a backslash depends on text `escape-text` cannot see:
  flexmark reads `{x}` as an attribute group wherever it sits
  (`haselnuss.parser/attribute-group-at?`), and the group can run on past
  the end of this text run, through `:space`s, line breaks and the markup
  of later inlines, up to a `}` several inlines away. So the decision is
  deferred until the whole block's inline source exists. A `!` is the same:
  whether a `[` follows it, or whether it opens a physical line, is often
  decided by the NEXT inline (a link, a span, a footnote marker) or the
  break before it (`bang-needs-escape?`).

  U+0000 because no `.hdoc` source can hold one: CommonMark replaces it
  with U+FFFD on reading. A hand-built or transformed node can still carry
  one -- in a `:str`, a `:code` span, a `:math-inline`'s TeX -- so the
  outermost `render-inlines` first replaces every U+0000 in its Inlines
  with U+FFFD (`replace-nul`), exactly what re-parsing the written source
  would have produced anyway. After that, every U+0000 in the block's
  text is a mark, and raw content is never touched by resolving them."
  \u0000)

(def ^:private ^String code-break
  "How a line ending INSIDE a code span is written until the whole
  document is: an `escape-mark` before the newline. The line it starts
  is code, not the start of a paragraph line, so `escape-line-starts`
  must leave it alone -- a backslash there would be literal code text
  (`` `x\\n2. y` `` written `2\\. y`, TASK-171 review). `resolve-escape-
  marks` keeps the mark, and `emit-document` drops it last, since a block
  quote or list only prefixes the line. (An inline math span cannot hold
  a newline -- see its handler.)"
  (str escape-mark "\n"))

(defn- without-marks
  "Rendered text `s` with every `escape-mark` removed, for an error that
  names it (TASK-182): the mark is this namespace's own bookkeeping, not
  part of any spelling, and `replace-nul` guarantees every U+0000 in
  rendered text is one. A `{` or `!` whose escape was still undecided is
  shown bare, and a `code-break` as the newline it is written as."
  [^String s]
  (str/replace s (str escape-mark) ""))

(defn- replace-nul
  "`inlines` with every U+0000 in every string inside them -- text, raw
  code and TeX, targets, attribute values -- replaced by U+FFFD, the
  character CommonMark reads it as (see `escape-mark`). `inlines` itself,
  untouched, when none holds one, which is every as-parsed document."
  [inlines]
  (let [nul? (fn [x] (and (string? x) (str/includes? x (str escape-mark))))]
    (if (some nul? (tree-seq coll? seq inlines))
      (cwalk/postwalk (fn [x] (if (nul? x) (str/replace x (str escape-mark) (str (char 0xFFFD))) x)) inlines)
      inlines)))

(declare bang-directive-reserved-names)

(def ^:private include-or-quote-line-re
  "A physical line, from its `!` on, that `haselnuss.parser`'s raw-line
  scan reads as an `!include <path>` or a `!quote <ref>` line
  (`include-line-re`/`quote-line-re` there, minus the leading spaces,
  which `bang-needs-escape?` checks itself). `(?s)` so that it matches at
  least whatever the parser's would: a superset only costs a backslash."
  #"(?s)!(?:include|quote)[ \t]+\S.*")

(def ^:private bang-name-line-re
  "A physical line, from its `!` on, shaped like a one-line directive
  (`haselnuss.parser/bang-directive-line-re`): the name (group 1) and the
  rest of the line (group 2), which `whole-line-bang?` then judges as the
  parser's `bang-directive-line` does."
  #"(?s)!([A-Za-z_][\w-]*)(.*)")

(defn- whole-line-bang?
  "True when `line` -- a physical line from its `!` on -- would re-parse as
  one of the whole-line `!` constructs: an `!include`/`!quote` line, a
  one-line directive (`!name` alone, or `!name` then a `{...}` group), or
  a reserved name WITH a group, which the parser refuses outright. A
  reserved name alone (`!quote`, `!include`) is a paragraph there, and so
  is `!name` followed by any other text (`!Wow, look`)."
  [line]
  (boolean
   (or (re-matches include-or-quote-line-re line)
       (when-let [[_ dname tail] (re-matches bang-name-line-re line)]
         (let [group (str/trim tail)]
           (if (str/blank? group)
             (not (contains? bang-directive-reserved-names dname))
             (str/starts-with? group "{")))))))

(defn- bang-needs-escape?
  "True when the `!` at `p` of `plain` (a whole block's inline source with
  every `escape-mark` removed) has to be written `\\!` (TASK-170): when
  the next character is a `[` -- `![` opens an image or an image
  reference, whichever inline wrote the bracket (a link, a span, a
  footnote marker, a citation group) -- or when only whitespace precedes
  it on its physical line and the line is a `whole-line-bang?` construct.

  A `[` written as text is always `\\[` (`always-escaped-chars`), so a `!`
  before one is left bare: the escaped bracket opens nothing.

  The physical lines are the block's own. A container adds a prefix to
  them (`- `, `> `, a footnote's indent, a heading's `# `) and only ever
  adds one, so a `!` that opens a line of the written document opens one
  here too; the reverse does not hold, and a `!` behind such a prefix is
  escaped when it need not be -- always a valid spelling, rare, and
  cheaper than threading every container's prefix down to here."
  [^String plain p]
  (let [next-p (inc p)]
    (or (and (< next-p (.length plain)) (= \[ (.charAt plain next-p)))
        (let [line-start (inc (.lastIndexOf plain "\n" (int (dec p))))
              line-end (let [e (.indexOf plain "\n" (int p))] (if (neg? e) (.length plain) e))]
          (and (str/blank? (subs plain line-start p))
               (whole-line-bang? (subs plain p line-end)))))))

(def ^:private autolink-or-inline-html-re
  "What a `<` can start that flexmark reads as something other than a
  literal `<` mid-line (TASK-203): a URI or email autolink (CommonMark
  sec6.5) or raw inline HTML (sec6.6) -- an open tag, a closing tag, a
  comment, a processing instruction, a declaration or a CDATA section.
  Matched with `lookingAt` at the `<`, over a whole block's inline source:
  any of these can run on past the text run the `<` sits in, through the
  markup of later inlines (`<http://x` then `*y*` then `>` is one
  autolink, since an autolink binds tighter than emphasis).

  Each alternative is a superset of what flexmark accepts where the two
  differ (an email's domain labels, a comment's contents), which only
  costs a backslash. Checked at a REPL: `<!-->`/`<!--->` and
  `<!-- a -- b -->` are no inline HTML to flexmark, and are matched here
  anyway; a lowercase `<!x y>` is a declaration to it."
  (re-pattern
   (str "<(?:"
        ;; URI autolink.
        "[A-Za-z][A-Za-z0-9+.-]{1,31}:[^<>\\x00-\\x20]*>"
        ;; Email autolink.
        "|[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9.-]+>"
        ;; Open tag.
        "|[A-Za-z][A-Za-z0-9-]*"
        "(?:\\s+[A-Za-z_:][A-Za-z0-9_.:-]*"
        "(?:\\s*=\\s*(?:[^\"'=<>`\\x00-\\x20]+|'[^']*'|\"[^\"]*\"))?)*"
        "\\s*/?>"
        ;; Closing tag.
        "|/[A-Za-z][A-Za-z0-9-]*\\s*>"
        ;; Comment, processing instruction, declaration, CDATA.
        "|!--(?s:.*?)-->|\\?(?s:.*?)\\?>|![A-Za-z][^>]*>|!\\[CDATA\\[(?s:.*?)\\]\\]>"
        ")")))

(def ^:private html-block-tag-names
  "Every tag name that starts an HTML block at the start of a physical
  line even unclosed (`<div`, `</div`) -- flexmark's default
  `Parser/HTML_BLOCK_TAGS` (CommonMark's type 6, plus `math`), and type 1's
  `script`/`pre`/`style`/`textarea`. Checked at a REPL that `x\\n<div`
  and `x\\n<script` re-parse as an HTML block, which the parser refuses,
  and `x\\n<svg`/`x\\n<foo` as a paragraph."
  ["address" "article" "aside" "base" "basefont" "blockquote" "body" "caption"
   "center" "col" "colgroup" "dd" "details" "dialog" "dir" "div" "dl" "dt"
   "fieldset" "figcaption" "figure" "footer" "form" "frame" "frameset"
   "h1" "h2" "h3" "h4" "h5" "h6" "head" "header" "hr" "html" "iframe"
   "legend" "li" "link" "main" "math" "menu" "menuitem" "meta" "nav"
   "noframes" "ol" "optgroup" "option" "p" "param" "section" "source"
   "summary" "table" "tbody" "td" "tfoot" "th" "thead" "title" "tr" "track"
   "ul" "script" "pre" "style" "textarea"])

(def ^:private html-block-start-re
  "What a `<` opening a physical line can start that flexmark reads as an
  HTML block with nothing more on the line (CommonMark sec4.6, types 1-6):
  a block tag name, opening or closing, in any case, that the next
  character does not continue -- a superset of the spec's `[ \\t>]`,
  `/>` or line end, which only costs a backslash -- or a comment, a
  processing instruction, a declaration or a CDATA section, opened. Type
  7, a complete tag alone on its line, is `autolink-or-inline-html-re`'s
  open or closing tag anyway.

  flexmark (0.64) also takes a block tag name behind an XML namespace
  prefix here, and only here: `<ab:div` and `<http:div` open an HTML
  block (found by this namespace's TASK-203 fuzz), while `</ab:b>` mid-line
  is no inline HTML to it."
  (re-pattern
   (str "</?(?:[_A-Za-z][_A-Za-z.0-9-]*:)?(?i:" (str/join "|" html-block-tag-names) ")(?![A-Za-z0-9-])"
        "|<!--|<\\?|<![A-Za-z]|<!\\[CDATA\\[")))

(defn- lt-needs-escape?
  "True when the `<` at `p` of `plain` (a whole block's inline source with
  every `escape-mark` removed) has to be written `\\<` (TASK-203): when
  `plain` from it on reads as an autolink or raw inline HTML
  (`autolink-or-inline-html-re`), or when only whitespace precedes it on
  its physical line and it opens an HTML block (`html-block-start-re`).
  Both re-parse as something else -- an autolink as a `:link`, HTML as the
  parser's unsupported-construct refusal -- and `\\<` as the bare `<`.

  The physical lines are the block's own, so as for `bang-needs-escape?`
  a `<` behind a container's prefix, a heading's `# `, or at the start of
  a table cell is judged as if it opened a line: escaped, when it need
  not be, only before a block tag name."
  [^String plain p]
  (let [m (fn [re] (.lookingAt (.region (re-matcher re plain) (int p) (.length plain))))
        line-start (inc (.lastIndexOf plain "\n" (int (dec p))))]
    (or (m autolink-or-inline-html-re)
        (and (str/blank? (subs plain line-start p))
             (m html-block-start-re)))))

(defn- resolve-escape-marks
  "`text` with each `escape-mark` replaced by a backslash where the
  character after it needs one, and dropped everywhere else -- a `{`,
  `!` or `<` that needs no escape keeps none (TASK-135). A `<` needs one
  where it would re-parse as an autolink or raw HTML (`lt-needs-escape?`,
  TASK-203). A `{` needs one where it
  would re-parse as an attribute group (`haselnuss.parser/attribute-group-
  at?`, TASK-161 -- stackless since TASK-172, so a `{x` before a run of
  any length is judged rather than escaped to be safe) or would be refused
  as an attempted one (`haselnuss.parser/attribute-attempt-at?`, `{.c`
  unclosed -- TASK-189), a `!`
  where it would open an image or a whole-line construct
  (`bang-needs-escape?`, TASK-170). `text` must be a whole block's inline
  source, as the parser will see it: both are decided over it with every
  mark removed."
  [^String text]
  (if (neg? (.indexOf text (int escape-mark)))
    text
    (let [plain (str/replace text (str escape-mark) "")
          sb (StringBuilder.)]
      (loop [i 0 p 0]
        (if (< i (.length text))
          (let [c (.charAt text i)]
            (if (= c escape-mark)
              (do (case (.charAt plain p)
                    ;; A `code-break`: kept for `escape-line-starts`.
                    \newline (.append sb escape-mark)
                    \! (when (bang-needs-escape? plain p) (.append sb \\))
                    \< (when (lt-needs-escape? plain p) (.append sb \\))
                    ;; TASK-198: resolved by `resolve-at-marks` before here;
                    ;; one that got this far needs no escape.
                    \@ nil
                    ;; TASK-221: a `|` inside an attribute group in a table
                    ;; cell; `render-table-cell` reads the mark.
                    \| (.append sb escape-mark)
                    (when (or (parser/attribute-group-at? plain p)
                              (parser/attribute-attempt-at? plain p))
                      (.append sb \\)))
                  (recur (inc i) p))
              (do (.append sb c)
                  (recur (inc i) (inc p)))))
          (str sb))))))

(defn- escape-text
  "Backslash-escapes every character in `always-escaped-chars` found in
  `s`, plus every `=` that opens a `==` run
  (`escapes-as-highlight-marker?`) and every `{` that would open a `@{…}`
  book reference (`escapes-as-book-ref-brace?`), leaving everything else
  untouched -- except that every other `{`, and every `<`, is preceded
  by an `escape-mark`, which the outermost `render-inlines` resolves
  (TASK-161, TASK-203), and so, given a `ctx`, is every `!` (TASK-170).
  The inverse of `haselnuss.parser/unescape-backslashes` for exactly
  this character set: re-parsing the result always resolves each escape
  back to the bare character (CommonMark sec2.4), regardless of what
  precedes or follows it.

  With a `ctx` (the 2-arity form), `:`, `>` and `@` are escaped only
  where they would re-parse differently (TASK-135): `:` and `>` when
  nothing but whitespace precedes them in `s` AND `ctx`'s `:line-start?`
  is true (absent means true, the safe answer), `@` when
  `haselnuss.parser/reference-at?` says it would become a reference under
  `ctx`'s `:kinds`/`:cite-keys` vocabularies, and `!` is marked for
  `resolve-escape-marks` to decide. Under `ctx`'s `::at-marks` (link text
  and a citation prefix, TASK-198) every other `@` is marked too, for
  `resolve-at-marks` to decide. The 1-arity form keeps escaping all of
  them, for callers with no position to go on -- an image's alt text,
  written into its own brackets where a `!` is never resolved."
  ([s]
   (escape-text s nil))
  ([s ctx]
   (apply str
          (map-indexed (fn [i c]
                         (cond
                           ;; TASK-170: decided later, by `resolve-escape-marks`.
                           (and ctx (= \! c)) (str escape-mark c)
                           (or (if ctx
                                 (and (needs-escape? s i ctx)
                                      (or (not (contains? #{\: \>} c))
                                          (:line-start? ctx true)))
                                 (contains? always-escaped-chars c))
                               (escapes-as-highlight-marker? s i)
                               (escapes-as-book-ref-brace? s i ctx))
                           (str \\ c)
                           ;; TASK-198: decided later, by `resolve-at-marks`.
                           (and (= \@ c) (::at-marks ctx)) (str escape-mark c)
                           ;; TASK-161 (`{`), TASK-203 (`<`, the alt text's
                           ;; too): decided later, by `resolve-escape-marks`.
                           (or (= \{ c) (= \< c)) (str escape-mark c)
                           :else c))
                       s))))

(defn- cite-key-marker-at?
  "True when the `@` at `p` of `plain` is followed by a citation-key
  character -- a word character, `:` or `-`, the key
  `haselnuss.parser/cite-key-index` reads after an unescaped `@` -- so
  that a citation bracket would take it for a `@key` marker (TASK-198)."
  [^String plain p]
  (let [next-p (inc p)]
    (boolean (and (< next-p (.length plain))
                  (re-matches #"[\w:-]" (str (.charAt plain next-p)))))))

(defn- resolve-at-marks
  "`text` with each `escape-mark` before an `@` (put there by `escape-text`
  under `::at-marks`, TASK-198) replaced by a backslash where
  `(escape-at? plain p)` is true for that `@`'s index `p` in `plain` --
  `text` with every mark removed -- and dropped everywhere else. Every
  other mark is kept for `resolve-escape-marks`.

  Inside brackets an `@` the bare-reference rule leaves as prose
  (`me@x.org`: `haselnuss.parser/reference-at?` is false after a word
  character) can still be read as a `@key` marker by the parser's
  citation-bracket scan, which looks at the raw text first: `[me@x.org](u)`
  re-parses as a citation of key `x` followed by a literal `(u)`. The
  caller writing the bracket knows its whole content, so it decides there."
  [^String text escape-at?]
  (if (neg? (.indexOf text (str escape-mark \@)))
    text
    (let [plain (without-marks text)
          sb (StringBuilder.)
          n (.length text)]
      (loop [i 0 p 0]
        (if (< i n)
          (let [c (.charAt text i)]
            (cond
              (and (= c escape-mark) (< (inc i) n) (= \@ (.charAt text (inc i))))
              (do (when (escape-at? plain p) (.append sb \\))
                  (recur (inc i) p))
              ;; Any other mark stands before a character of `plain`.
              (= c escape-mark) (do (.append sb c) (recur (inc i) p))
              :else (do (.append sb c) (recur (inc i) (inc p)))))
          (str sb))))))

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

(def ^:private table-delimiter-hazard-re
  "Matches a physical line shaped like a pipe table's delimiter row
  (`|---|`, `--- | ---`, `| :-: |`): only `|`/`-`/`:`/whitespace, with at
  least one `|` and one `-` (the lookaheads), capturing its indent (group
  1), first significant character (group 2) and the rest (group 3). Under
  a paragraph line holding a `|` it turns the paragraph into a table
  (TASK-171), at any indent; with no `|` at all it is never a delimiter
  row to flexmark, and a bare `---` is `hazard-line-re`'s anyway. Mirrors
  `haselnuss.parser`'s own `table-delimiter-line-re`."
  #"(?=[^|]*\|)(?=[^-]*-)([ \t]*)([|:-])([|: \t-]*)")

(defn- try-escape-table-delimiter-hazard
  "`line` with a backslash inserted before its first significant
  character if the WHOLE line matches `table-delimiter-hazard-re`, else
  nil: `\\|---|` is one cell `\\|---`, no delimiter row, and reads back as
  the bare `|---|`. Every such line, not only one under a `|`-holding
  line -- the same over-escaping `try-escape-heading-hazard` accepts."
  [line]
  (when-let [[_ lead c tail] (re-matches table-delimiter-hazard-re line)]
    (str lead \\ c tail)))

(defn- escape-line-start-hazard
  "`line` with a backslash inserted to defuse whichever physical-line-start
  hazard it matches (`try-escape-heading-hazard`, `try-escape-bullet-
  hazard`, `try-escape-ordered-hazard`, `try-escape-table-delimiter-
  hazard`, tried in that order -- each escapes the line's first marker
  character, so whichever matches first defuses the line), otherwise
  `line` unchanged. Applied to every physical line of a
  rendered `:para` (recursively -- wherever a `:para` occurs, including
  nested inside a `:block-quote` or a `:list` item, per this namespace's
  own docstring)."
  [line]
  (or (try-escape-heading-hazard line)
      (try-escape-bullet-hazard line)
      (try-escape-ordered-hazard line)
      (try-escape-table-delimiter-hazard line)
      line))

(defn- escape-line-starts
  "`escape-line-start-hazard` applied to every physical line of `text`
  except one a `code-break` starts: that line continues a code span, so
  it opens no block and a backslash there would be literal code text."
  [text]
  (let [lines (str/split text #"\n" -1)]
    (str/join "\n" (cons (escape-line-start-hazard (first lines))
                         (map (fn [prev line]
                                (if (str/ends-with? prev (str escape-mark))
                                  line
                                  (escape-line-start-hazard line)))
                              lines (rest lines))))))

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

(defn- bare-attr-value-code-point?
  "True if code point `cp` is safe in an UNQUOTED attribute value on every
  path the parser reads one (TASK-205). The same writer output lands in
  two grammars:

  - flexmark's `ATTRIBUTES_TAG` (headings, spans, links, images ...),
    whose unquoted value is `[^\"'=<>{}`\\x00-\\x20]+` (mirrored by
    `haselnuss.parser`'s `group-value-char?`) -- so `{k=>}` is not a
    group at all and fails to re-parse;
  - the hand-written header tokenizer (`:::` fences, `!name{...}`, the
    display-math closing fence), which splits on `Character/isWhitespace`
    -- wider than flexmark's ASCII `\\s` -- after matching the line with
    `.`, which stops at `\\u0085`/`\\u2028`/`\\u2029`.

  Rather than reproduce both, this is a known-safe class, and anything
  outside it is quoted: printable ASCII except the characters flexmark
  rejects, a backslash (TASK-197) and DEL, plus non-ASCII letters, digits
  and combining marks. Quoting is never wrong where a bare value would
  have been read back, so being conservative here costs only quotes."
  [cp]
  (if (< cp 0x80)
    (and (< 0x20 cp 0x7f) (neg? (.indexOf "\"'=<>{}`\\" (int cp))))
    (or (Character/isLetterOrDigit (int cp))
        (contains? #{Character/NON_SPACING_MARK Character/COMBINING_SPACING_MARK
                     Character/ENCLOSING_MARK}
                   (Character/getType (int cp))))))

(defn- render-attr-value
  "One `key=val` attribute value `v`: bare when it is non-empty and every
  code point is `bare-attr-value-code-point?` (TASK-205), quoted
  (`quote-attr-value`) otherwise -- which covers whitespace (TASK-39), a
  backslash (TASK-197), a leading quote that would otherwise open a quoted
  value (`haselnuss.parser/attr-value-start-re`), and `\"'=<>{}`` that
  flexmark's grammar rejects bare. The empty value is quoted too: flexmark
  reads a bare `{k=}` as the lone token `k`, which the parser refuses,
  while `k=\"\"` reads back as `\"\"` on every path."
  [k ^String v]
  (if (and (seq v)
           (.allMatch (.codePoints v) (reify java.util.function.IntPredicate
                                        (test [_ cp] (bare-attr-value-code-point? cp)))))
    v
    (quote-attr-value k v)))

(defn- check-no-backslash-in-token!
  "Throws `ex-info` (`:type ::unrepresentable-attr-token`) when `s`, an
  attribute's id, class or key (`what`), contains a backslash: the parser
  refuses one there (TASK-197) and only a VALUE can be quoted, so no
  `.hdoc` spelling would read back -- reported here rather than written
  as a group the re-parse rejects."
  [what s]
  (when (str/includes? s "\\")
    (throw (ex-info (str "attribute " what " " (pr-str s) " contains a backslash, "
                         "which .hdoc's attribute syntax cannot represent")
                    {:type ::unrepresentable-attr-token :part what :text s}))))

(defn- attr-tokens
  "Attr `attr` (sec4.1: `:id`/`:classes`/`:props`) as the vector of `{...}`
  attribute-group tokens (`#id`, `.class`, `key=val`) an author would
  write inside the braces, WITHOUT the braces themselves -- shared by
  `attr-group` (AC #6) and `directive-header-text` (TASK-97 AC #5), which
  prepends a directive's own name token ahead of the same tokens. `:props`
  is rendered in sorted key order for a stable, deterministic spelling
  (it is a plain map, with no ordering of its own to preserve)."
  [{:keys [id classes props]}]
  (some->> id (check-no-backslash-in-token! "id"))
  (run! #(check-no-backslash-in-token! "class" %) classes)
  (run! #(check-no-backslash-in-token! "key" %) (keys props))
  (cond-> []
    id (conj (str "#" id))
    true (into (map #(str "." %) classes))
    true (into (map (fn [[k v]] (str k "=" (render-attr-value k v)))
                    (sort-by key props)))))

(defn- attr-group
  "Attr `attr` as a `{#id .class key=val}` attribute-group fragment (AC
  #6), or `\"\"` when `attr` carries none of the three -- the same
  \"nothing to show\" convention `haselnuss.emit.html/attr-attrs` uses."
  ([attr]
   (let [tokens (attr-tokens attr)]
     (if (seq tokens) (str "{" (str/join " " tokens) "}") "")))
  ([ctx attr]
   ;; TASK-221: inside a table cell, `render-table-cell` escapes every `|`
   ;; it finds, but the parser splits a row group-aware and reads `\|`
   ;; inside a group as a backslash (refused bare, kept when quoted). A
   ;; `|` in a group is therefore marked, not escaped: `resolve-escape-
   ;; marks` keeps the mark and `render-table-cell` passes the pipe on.
   (cond-> (attr-group attr)
     (::in-table-cell ctx) (str/replace "|" (str escape-mark "|")))))

(def ^:private line-terminators
  "Every character the header tokenizer's `.` refuses to match, or that
  ends a line: a group holding one cannot be written on a single line."
  (re-pattern (str "[\n\r" (char 0x85) (char 0x2028) (char 0x2029) "]")))

(defn- check-group-fits!
  "Throws `ex-info` (`:type ::unrepresentable-attr-value`, naming
  `context`) when `text`, an `attr-group` fragment or the line header
  built around one, matches `bad-re` (TASK-221): `what` describes a
  character the line it is written on cannot carry. `line-terminators`
  for the header-tokenizer contexts."
  [context text bad-re what]
  (when (re-find bad-re text)
    (throw (ex-info (str "hdoc cannot write an attribute group holding " what " in " context ": "
                         (pr-str text))
                    {:type ::unrepresentable-attr-value :context context :text text}))))

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

(defn- escape-destination-backslashes
  "`target` with each `\\` that the parser would otherwise read as an
  escape doubled (TASK-204). Both destination forms process CommonMark
  backslash escapes (sec6.3; confirmed at a REPL for each), so a `\\`
  right before ASCII punctuation is dropped on re-parse -- `ab:=\\[`
  written bare reads back `ab:=[` -- while one before anything else is
  kept literally, and is left as it is here (the no-needless-escape
  rule TASK-135 set for text). A `\\` ending `target` is doubled too:
  the next character written is the destination's own closing `)` or
  `>`, which it would otherwise escape."
  [target]
  (str/replace target #"\\(?=\p{Punct}|\z)" "\\\\\\\\"))

(defn- render-destination
  "`target` (a Link's `:target` or an Image's `:src`, sec4.4) as a
  CommonMark link destination: bare when `balanced-destination?` holds
  and `target` carries no `<`/`>` of its own, else the `<...>` angle-
  bracket form (`<` and `>` backslash-escaped inside it, per CommonMark's
  own rule for that form) -- confirmed at a REPL that this combination
  round-trips a destination with an unbalanced paren correctly either
  way. In either form a `\\` that would read as an escape is doubled
  (`escape-destination-backslashes`); the balance check is unaffected,
  since `\\\\(` reads as a literal `\\` and then a paren that counts.

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
    (escape-destination-backslashes target)
    (str "<" (-> target
                 escape-destination-backslashes
                 (str/replace "<" "\\<")
                 (str/replace ">" "\\>"))
         ">")))

(defn- render-title
  "The ` \"Title\"` suffix of a link/image `(destination \"Title\")` for
  `node`'s `:title` (TASK-140), or `\"\"` when it has none. `\\` and `\"`
  are backslash-escaped, the CommonMark spelling of either inside a
  double-quoted title, which `haselnuss.parser` reads back."
  [node]
  (if-let [title (:title node)]
    (str " \"" (-> title (str/replace "\\" "\\\\") (str/replace "\"" "\\\"")) "\"")
    ""))

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
  any internal backtick run. Never padded: this parser keeps a code
  span's content verbatim (confirmed at a REPL: `` ` a ` `` reads back as
  `\" a \"`, and `` `` `x `` `` as `\" `x \"`), so a padding space would
  be baked into the text on re-parse. A `text` that starts or ends with a
  backtick therefore has no spelling at all (a backtick touching a fence
  merges into one longer run); `check-code-span-lines!` refuses it
  before this is reached (TASK-226)."
  [text]
  (let [fence (code-fence-for text)]
    (str fence text fence)))

;; --- Multi-line code spans (TASK-222) ----------------------------------------

(defn- code-line-block-start?
  "True if `line`, a continuation line of a multi-line code span (one
  after a newline in its text), would start a block when written out, so
  that it breaks the paragraph the span sits in. `last?` marks the span's
  last line, which the closing fence follows on the same physical line --
  so a marker must have text after it there (`- y`, not `-`: the line is
  really `-` + fence), while a line of any other kind (an empty marker, a
  bare `---`, a backtick fence) is only a hazard when nothing follows.
  Covers what interrupts a paragraph: a bullet or a `1.`/`1)` ordered
  marker (`2. y` does not), an ATX heading, a block quote, a tilde fence,
  a `:::` directive fence, a `<` (HTML block, which the parser rejects)
  and a `[^` (footnote definition), and for a line ending in a newline a
  blank or whitespace-only line (it ends the paragraph; as the last line
  the closing fence is on it, so it is not blank), a thematic break, a
  setext underline and a backtick fence."
  [^String line last?]
  (let [at-start (fn [re] (boolean (re-find (re-pattern (str "^[ \\t]{0,3}" re)) line)))
        ;; After a list marker: a space and then, on a line the span goes
        ;; on past, something (an empty item cannot interrupt a paragraph).
        item (if last? "[ \\t]" "[ \\t]+\\S")]
    (or (at-start (str "[-+*]" item))
        (at-start (str "0*1[.)]" item))
        (at-start (str "#{1,6}" (if last? "[ \\t]" "(?:[ \\t]|$)")))
        (at-start ">")
        (at-start "~{3,}")
        (at-start ":::")
        (at-start "<")
        (at-start "\\[\\^")
        (and (not last?)
             (or (boolean (re-matches #"[ \t]*" line))
                 (boolean (re-matches hazard-line-re line))
                 (boolean (re-matches #"[ \t]{0,3}([*_])(?:[ \t]*\1){2,}[ \t]*" line))
                 (at-start "`{3,}[^`]*$"))))))

(defn- check-code-span-lines!
  "Throws `ex-info` (`:type ::unrepresentable-code-span`) naming the line
  and its number (from 2) when a continuation line of the code span with
  `text` would start a block (`code-line-block-start?`). Such a line has no
  `.hdoc` spelling: written out it breaks the paragraph, and a backslash
  to stop that would be literal code text (TASK-171). Also refused: a
  text holding a run of two or more backticks whose last line is empty or
  blank (TASK-228), where the 3+ closing fence would open a code block.
  Also refused: a continuation line starting with a tab, after any
  spaces (TASK-229). Also refused (TASK-231): a carriage return next to
  another CR or a newline (`\\r\\n`, `\\n\\r`, `\\r\\r`: the parser normalises
  them), and ANY carriage return when `plain-cr-breaks?` (the span is in a
  table cell (`render-heading` does the same for an ATX heading), where a lone `\\r` is a line ending that
  re-parses differently). A lone `\\r` elsewhere (paragraph, list item,
  quote, footnote, emphasis, link) is kept verbatim by the parser, so it
  is written as is -- `haselnuss.parser` produces such spans from source.
  Because the parser also treats that lone `\\r` as a line end, every
  line-based check here splits on `\\r` as well as `\\n`. Also refused when
  `line-start?` (the span opens a physical line): a multi-line text whose
  first line holds no backtick and that holds a run of two or more, whose
  3+ opening fence then reads as a code-block fence (`- ```a` / `  ``a``` b`);
  a fence the first line's own backtick (or the closing fence) shares a
  line with is no fence opener. Parsed sources cannot produce most of
  these, a hand-built `:code` can."
  [^String text line-start? plain-cr-breaks?]
  (let [lines (str/split text #"\r|\n" -1)
        n (count lines)]
    ;; TASK-232: two backticks are no empty span (the parser reads them as
    ;; text), so an empty text has no spelling; the parser never produces one.
    (when (= "" text)
      (throw (ex-info "hdoc emitter cannot represent a code span with empty text"
                      {:type ::unrepresentable-code-span :text text :empty true})))
    (when (or (re-find #"\r[\r\n]|\n\r" text)
              (and plain-cr-breaks? (str/includes? text "\r")))
      (throw (ex-info (str "hdoc emitter cannot represent a code span holding this carriage return: "
                           (pr-str text))
                      {:type ::unrepresentable-code-span :text text :carriage-return true})))
    (when (and line-start?
               (<= 3 (count (code-fence-for text)))
               (> n 1)
               (not (str/includes? (first lines) "`")))
      (throw (ex-info (str "hdoc emitter cannot represent a multi-line code span at the start of a"
                           " line whose text holds a run of two or more backticks (its opening"
                           " fence would open a code block): " (pr-str text))
                      {:type ::unrepresentable-code-span :text text :opening-fence-line-start true})))
    (when (or (str/starts-with? text "`") (str/ends-with? text "`"))
      (throw (ex-info (str "hdoc emitter cannot represent a code span whose text starts or ends"
                           " with a backtick: " (pr-str text))
                      {:type ::unrepresentable-code-span :text text :edge-backtick true})))
    ;; TASK-228: a text ending in a newline, or in a newline and then only
    ;; blanks, puts the closing fence at the start of its line (or after
    ;; those blanks), and a fence of three or more backticks there (text
    ;; holding a run of two or more) reads as a code-block fence in any
    ;; context. Confirmed with the parser: it opens a block after 0-3
    ;; spaces, after any tab, and after four or more spaces only when a tab
    ;; follows them; only a last line of four or more plain spaces is safe.
    ;; Never padded, so no spelling: refused.
    (when (and (<= 3 (count (code-fence-for text)))
               (let [last-line (peek lines)]
                 (and (pos? (dec n))
                      (re-matches #"[ \t]*" last-line)
                      (not (re-matches #" {4,}" last-line)))))
      (throw (ex-info (str "hdoc emitter cannot represent a code span ending in a newline (and"
                           " then only blanks) whose text holds a run of two or more backticks"
                           " (its closing fence would start a line as a code-block fence): "
                           (pr-str text))
                      {:type ::unrepresentable-code-span :text text :line (peek lines) :line-number n
                       :trailing-newline-fence true})))
    (doseq [[i line] (map-indexed vector (rest lines))]
      (when (code-line-block-start? line (= (inc i) (dec n)))
        (throw (ex-info (str "hdoc emitter cannot represent a code span whose line "
                             (+ i 2) " " (pr-str line) " would start a block")
                        {:type ::unrepresentable-code-span :line line :line-number (+ i 2)
                         :text text})))
      ;; TASK-229: a continuation line whose leading whitespace holds a tab
      ;; (`\tx`, ` \tx`, `    \tx`, or a tab-only last line) does not
      ;; re-parse to the same text, in any context -- confirmed with the
      ;; parser; the same lines with spaces only, and a tab after text, are
      ;; fine. No spelling exists (a code span is never padded or escaped).
      (when (re-find #"^ *\t" line)
        (throw (ex-info (str "hdoc emitter cannot represent a code span whose line "
                             (+ i 2) " " (pr-str line) " starts with a tab")
                        {:type ::unrepresentable-code-span :line line :line-number (+ i 2)
                         :text text :leading-tab true}))))))

;; --- Inline dispatch (TASK-88) ----------------------------------------------

(declare render-inlines)
(declare render-block)
(declare render-blocks)
(declare render-tight-section-body)

(defn- concat-rendered-kv
  "`[text footnotes']`: `(render idx x footnotes)` -- itself `[piece
  footnotes']`, the piece carrying whatever separator it needs from the
  one before -- over every `x` of `xs` with its index, in order, threading
  `footnotes` and concatenating the pieces.

  One StringBuilder for the whole sequence (TASK-261, as TASK-258 did for
  a paragraph's inlines): `(str text piece)` per Block copied everything
  written so far each time, so a footnote or list item of very many Blocks
  emitted in quadratic time."
  [render footnotes xs]
  (let [sb (StringBuilder.)
        footnotes' (reduce-kv (fn [footnotes idx x]
                                (let [[piece footnotes'] (render idx x footnotes)]
                                  (.append sb (str piece))
                                  footnotes'))
                              footnotes
                              (vec xs))]
    [(str sb) footnotes']))

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
  between the two delimiter runs (confirmed at a REPL).

  \"Safe\" holds only until two delimiter runs of the same character
  touch: `*e*` then `_e_` as siblings written `*e**e*` reads back as ONE
  emphasis, `*_e_*` written `**e**` as a strong (TASK-237).
  `choose-emphasis-delim` (called from `render-inlines`, which knows the
  neighbours) picks `_` for a node whose `*` spelling would touch a
  neighbouring or enclosing run, and passes the choice as `::delim`;
  every spelling that works with `*` is left exactly as it was."
  [ctx inline footnotes kind]
  (let [d (or (::delim ctx) "*")
        mark (case kind :emph d :strong (str d d))
        [text footnotes'] (render-inlines (assoc ctx ::parent-emphasis {:kind kind :delim d})
                                          (:inlines inline) footnotes)]
    [(str mark text mark) footnotes']))

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

(def ^:private nested-autolink-re
  "The URI autolinks `render-bracket-autolink` writes as `<uri>` in a
  citation prefix (where an `@` would be the segment's key):
  CommonMark's own absolute-URI form (sec6.5, a 2-32 character scheme, a
  `:`, then no whitespace, control character, `<` or `>`), minus `@`.
  Inside brackets `haselnuss.parser`'s citation scan reads `@key` before
  flexmark sees the autolink, and in a prefix the first one is the key --
  the same reason an email autolink, which is all `@`, is not written
  there."
  #"[A-Za-z][A-Za-z0-9+.\-]{1,31}:[^\s\x00-\x1f\x7f<>@]*")

(def ^:private cite-affix-autolink-re
  "The URI autolinks `render-bracket-autolink` writes as `<uri>` in link
  text or a citation's suffix (TASK-199, TASK-200): `nested-autolink-re`'s
  absolute URI with its `@` allowed back. A suffix follows its segment's
  key, and `haselnuss.parser`'s scan takes only a segment's FIRST `@key`
  (confirmed at a REPL: `[@k, see <http://x/@j>]` is a citation of `k`
  whose suffix holds the link). In link text the `@` is a key marker to
  the scan, which `render-link` asks about the whole bracket: `[a <http://x/@k>](u)`
  reads as a citation and is refused there, `[a; b <http://x/@k>](u)`
  (a segment with no key) is a link holding the autolink."
  #"[A-Za-z][A-Za-z0-9+.\-]{1,31}:[^\s\x00-\x1f\x7f<>]*")

(def ^:private email-autolink-re
  "CommonMark's email autolink address (sec6.5), the text of a Link whose
  target is `mailto:` plus that text -- the only link `<me@x.org>` reads
  back as."
  #"[a-zA-Z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*")

(defn- bracket-autolink-text
  "The text `<…>` holds when Link `inline` can be written as an autolink
  where `ctx` puts it (see `render-bracket-autolink`), else nil."
  [ctx inline]
  (let [target (:target inline)
        text (let [t (:text (first (:inlines inline)))]
               (when (and (nil? (:title inline))
                          (= "" (attr-group (:attr inline)))
                          (= [{:t :str :text t}] (:inlines inline)))
                 t))
        ;; TASK-200: an `@` is fine anywhere but a citation prefix; in link
        ;; text `render-link` refuses the bracket if it reads as a citation.
        at-ok? (not= :prefix (::in-cite-affix ctx))
        uri (cond
              (nil? text) nil
              (= text target) (when (re-matches (if at-ok? cite-affix-autolink-re nested-autolink-re) target)
                                text)
              (= target (str "mailto:" text)) (when (and at-ok? (re-matches email-autolink-re text))
                                                text))]
    (when (and uri
               (not (and (::in-table-cell ctx) (str/includes? uri "|")))
               (not (and (::in-cite-affix ctx) (re-find #"[\[\]`$]" uri))))
      uri)))

(defn- render-bracket-autolink
  "Renders Link `inline` found inside another Link's text (TASK-192) or a
  citation's prefix or suffix (`::in-cite-affix`, TASK-199). CommonMark
  lets no link contain a link, so `[text](destination)` in link text
  re-parses as literal brackets around the inner link and the outer one
  is lost; in a citation its `[` ends `haselnuss.parser`'s bracket scan
  (`cite-bracket-close-index`), so the citation is lost instead. The only
  link either can hold after a parse is an autolink (`[a <http://x>](u)`,
  `[@k, see <http://x>]`), and that spelling is the one that goes back
  in. It is written `<uri>` verbatim, without `escape-text`: an autolink
  does no backslash or markup processing at all (see
  `haselnuss.parser/autolink`), so its text is exactly what is read back.

  A URI autolink (`cite-affix-autolink-re`) and an email autolink
  (`<me@x.org>`) are accepted, except that a citation prefix takes
  neither an `@` in the URI nor an email autolink (`nested-autolink-re`):
  there the `@` is read as the segment's key. In link text an `@` is a key
  marker to the citation scan too, and cannot be escaped inside `<…>`, so
  `render-link` refuses the bracket when it would read as a citation
  (TASK-200). In a citation, a `[` or `]` in the URI ends or closes the
  bracket early, and a backtick or `$` opens a code or math span to the
  scan, whose end depends on the rest of the paragraph -- so none is
  accepted there. A `;` would split the citation;
  `check-no-cite-affix-semicolon!` already refuses that for any affix
  text. A citation's affix keeps these rules inside link text as well:
  the citation's own `[` already makes the link text no citation.

  Throws `ex-info` for any other Link there -- a title, an attribute
  group, text that is not its own target, or a target the rules above do
  not accept -- because no `.hdoc` spelling re-parses to it:
  `::unrepresentable-cite-link` in citation text (inside link text or
  not), `::unrepresentable-nested-link` in other link text. So does a
  target holding `|` inside a table cell (`::in-table-cell`):
  `render-table-cell` escapes every `|` in the cell, and an autolink
  keeps that backslash as part of its URI (confirmed at a REPL, for both
  `<http://x/|y>` and `<http://x/\\|y>` in a cell)."
  [ctx inline]
  (if-let [uri (bracket-autolink-text ctx inline)]
    (str "<" uri ">")
    (throw (if-not (::in-cite-affix ctx)
             (ex-info
              (str "hdoc emitter cannot represent a link inside link text unless it is an autolink"
                   " (`<scheme:...>` or `<address@host>`, with no `|` in a table cell; its text its"
                   " own target; no title or attributes): "
                   (pr-str inline))
              {:type ::unrepresentable-nested-link :inline inline})
             (ex-info
              (str "hdoc emitter cannot represent a link in a citation's prefix or suffix unless it is"
                   " an autolink (`<scheme:...>` with no `[`, `]`, backtick or `$`, nor `|` in a table"
                   " cell, and no `@` in a prefix; or, in a suffix, `<address@host>`; its text its own"
                   " target; no title or attributes): "
                   (pr-str inline))
              {:type ::unrepresentable-cite-link :inline inline})))))

(defn- link-text-inlines
  "Every Inline in Link `inline`'s text, at any depth of `:inlines` (a
  span, emphasis) -- not into a citation, whose affixes are its own
  bracket's."
  [inline]
  (rest (tree-seq (comp sequential? :inlines) :inlines inline)))

(defn- check-link-text-not-citation!
  "Throws `ex-info` when Link `inline`'s written `text` (its `@`s already
  resolved by `render-link`) would still re-parse as a bracketed citation
  (`haselnuss.parser/citation-bracket-at?`, TASK-200), since `[text](u)`
  then reads as a citation followed by a literal `(u)`.
  `::unrepresentable-nested-link` when the text holds an autolink with an
  `@`, whose `<…>` admits no escape; `::unrepresentable-link-text`
  otherwise, the `@` then being a bare cross-reference's or author
  citation's own marker.

  The source such a Link would come from is a citation, so only a
  hand-built AST reaches here. Until TASK-207 parsed source did too:
  `[@k; ](u)` was a link, its blank last segment holding no key, with the
  text `@k;`; `haselnuss.parser/cite-segments` now drops that segment as
  it drops an empty one, so it is the citation `[@k;](u)` is."
  [inline text]
  (let [shown (without-marks text)]
    (when (parser/citation-bracket-at? (str "[" shown "]") 0)
      (throw (if (some #(and (= :link (:t %)) (str/includes? (str (:target %)) "@"))
                       (link-text-inlines inline))
               (ex-info
                (str "hdoc emitter cannot represent an autolink holding `@` inside link text that"
                     " would re-parse as a bracketed citation (every `;`-separated segment has an"
                     " `@key` marker that cannot be escaped): " (pr-str shown))
                {:type ::unrepresentable-nested-link :inline inline :text shown})
               (ex-info
                (str "hdoc emitter cannot represent link text whose cross-references or citations"
                     " make it re-parse as a bracketed citation (an `@key` in every `;`-separated"
                     " segment): " (pr-str shown))
                {:type ::unrepresentable-link-text :inline inline :text shown}))))))

(defn- render-link
  "Renders Link `inline` (sec4.4: `:target`/`:inlines`/`:attr`) as
  `[text](destination)`, plus a trailing `attr-group` when `:attr`
  carries anything (AC #6's own attribute-group syntax, attached here the
  same way `haselnuss.parser` reads it back -- immediately after the
  closing `)`, no space).

  A Link inside another Link's text, at any depth of Inlines between them
  (a span, emphasis), goes through `render-bracket-autolink` instead
  (TASK-192): `::in-link-text` marks the text, and `render-block` clears
  it for a footnote's own Blocks. So does a Link in a citation's prefix or
  suffix (`::in-cite-affix`, TASK-199). A Link at any other position keeps
  this spelling even when it is an autolink, so documents without one in
  link or citation text are written byte-for-byte as before.

  An `@` in the text is escaped where the bracket would otherwise re-parse
  as a bracketed citation (TASK-198): `[me@x.org](mailto:me@x.org)`, an
  email autolink, is `[me\\@x.org](mailto:me@x.org)`. The text is
  rendered under `::at-marks` and `resolve-at-marks` decides over all of
  it, with the parser's own test (`haselnuss.parser/citation-bracket-
  at?`), backslashing every `@` that would be a key marker only then:
  `[a@b; c](u)` has a segment with no marker, so it is no citation and
  keeps its bare `@` (TASK-135). The marks of a span or emphasis inside
  the text reach here too, and the span's `[` alone makes the bracket no
  citation (the parser's scan stops at it).

  Some `@`s cannot be escaped: a bare cross-reference or author citation
  (`@fig:x`, `@doe`) is written as its key marker, and an autolink
  holding `@` (`<me@x.org>`, TASK-200) is written verbatim. If the text
  still reads as a citation once every other `@` is escaped -- every
  `;`-segment holds one of them -- no spelling of this Link re-parses to
  it (source shaped that way is a citation), so it is refused (`check-
  link-text-not-citation!`)."
  [ctx inline footnotes]
  (if (or (::in-link-text ctx) (::in-cite-affix ctx))
    [(render-bracket-autolink ctx inline) footnotes]
    (let [[marked footnotes'] (render-inlines (assoc ctx ::in-link-text true ::at-marks true)
                                              (:inlines inline) footnotes)
          citation? (parser/citation-bracket-at? (str "[" (without-marks marked) "]") 0)
          text (resolve-at-marks marked (fn [plain p] (and citation? (cite-key-marker-at? plain p))))
          destination (render-destination (:target inline))
          group (attr-group ctx (:attr inline))]
      (when citation? (check-link-text-not-citation! inline text))
      [(str "[" text "](" destination (render-title inline) ")" group) footnotes'])))

(defn- render-alt
  "An image's `:alt` string written as the content of its brackets: every
  line `escape-text`, and every newline a code span holding just that
  newline (TASK-182). `haselnuss.parser/alt-text` flattens a soft or hard
  break to a space but keeps a code span's text as it is, so code is the
  only spelling a newline in `:alt` (which only a multi-line code span
  can put there) is read back from; `` a ![l `x\\n2. y`](u) `` used to
  come back as `l x 2. y`. The newline is a `code-break`, so the line it
  starts is left alone like any code span's.

  Two newlines in a row cannot share one span -- that is a blank line,
  which ends the paragraph -- nor sit in two adjacent ones, whose
  backticks would merge into one run, so an empty image `![]()` goes
  between them: it parses, and flattens to its own empty alt."
  [alt]
  (let [lines (str/split alt #"\n" -1)
        break (str "`" code-break "`")]
    (str (escape-text (first lines))
         (apply str (map-indexed (fn [i line]
                                   (str break
                                        (if (and (= "" line) (< (inc i) (dec (count lines))))
                                          "![]()"
                                          (escape-text line))))
                                 (rest lines))))))

(defn- render-image
  "Renders Image `inline` (sec4.4: `:src`/`:alt`/`:attr`) as
  `![alt](src)`, plus a trailing `attr-group`. `:alt` is a plain string
  (unlike Link's `:inlines`), so it is escaped as literal text
  (`escape-text`) rather than rendered as Inlines -- mirroring how
  `haselnuss.parser/alt-text` flattens it back down to a string on
  re-parse regardless of what markup this bracket's own content
  contains, escaped plain text is the only spelling that reproduces the
  same string both ways -- except for a line break, which `render-alt`
  writes as code."
  [ctx inline]
  (let [alt (render-alt (:alt inline))
        destination (render-destination (:src inline))
        group (attr-group ctx (:attr inline))]
    (str "![" alt "](" destination (render-title inline) ")" group)))

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
        group (attr-group ctx (:attr inline))]
    [(str "[" text "]" group) footnotes']))

(defn- render-flat-prose
  "Renders `inlines` (a CiteItem's own `:prefix`/`:suffix`, sec4.4/5.11)
  to escaped text, discarding any footnotes accumulator
  (a citation prefix/suffix can never structurally contain a `:note`:
  `haselnuss.parser/mini-parse-inlines` re-parses it as its own isolated
  fragment with no footnote DEFINITIONS of its own in scope, so a
  `[^label]` marker there would already have thrown
  `::undefined-footnote` at the original parse -- confirmed by that
  function's own docstring).

  A citation group may be hard-wrapped (TASK-165), but the AST keeps a
  line break only where it fell INSIDE a prefix or suffix (a
  `:soft-break`/`:line-break` there), so that is the only break written
  back; one between items, or around a key, is lost on parsing and the
  group is written on one line, which re-parses to the same AST. A kept
  break is written as `\"\\n\"` like anywhere else in a paragraph.
  `escape-line-start-hazard` is applied to every physical line of the
  affix ITSELF, not only by `render-para` to the paragraph's: the
  paragraph line `--- @a]` is harmless, but the parser reads the affix
  cut off at the `@` (`---`) as a fragment of its own, where it would
  be a setext underline (TASK-165 review).

  With `prefix?`, every `@` that could be read as a key marker is escaped
  (TASK-198): the parser takes a segment's FIRST `@key` for its key, so
  `[see x@y @doe]` would re-parse as a citation of `y`. A suffix follows
  the key and needs none, so outside a prefix no `@` is marked -- nor
  inherited from link text the citation sits in (`::at-marks`).

  `::in-cite-affix` (`:prefix` or `:suffix`) tells `render-link` where it
  is, so an autolink here is written `<uri>` (TASK-199, see
  `render-bracket-autolink`)."
  [ctx inlines prefix?]
  (let [[text _] (render-inlines (assoc ctx
                                        :line-start? false
                                        ::at-marks prefix?
                                        ::in-cite-affix (if prefix? :prefix :suffix))
                                 inlines [])]
    (escape-line-starts (resolve-at-marks text cite-key-marker-at?))))

(defn- check-no-cite-affix-semicolon!
  "Throws `ex-info` (`:type ::unrepresentable-cite-affix`) naming
  `cite-key` and `rendered` when a CiteItem's own rendered prefix/suffix
  text contains a literal `;`: `haselnuss.parser`'s own citation-bracket
  recognition (`citation-bracket-content?`) and item extraction
  (`cite-bracket-node->cite`) both split a bracket's RAW source text on
  every `;` outside a code span or inline math, before backslash-escape
  resolution ever runs -- confirmed at a REPL that backslash-escaping
  the `;` here does NOT prevent that split, the same class of gap this
  namespace's own docstring documents for `{#`/`{.`/`{key=` immediately
  after a brace. There is therefore no `.hdoc` spelling for a `;` in a
  CiteItem's own prefix/suffix at all; reported here (review comment #4
  on TASK-95) rather than silently corrupting the whole citation bracket
  on re-parse the way it did before this check existed.

  A `;` inside a code span or inline math is the one exception: the split
  (`haselnuss.parser/cite-segments`) skips those spans since TASK-167, so
  the check asks that same function whether it would cut `rendered` at
  all, rather than looking for the character. An empty `rendered` has
  nothing to split (`cite-segments` returns `[]` for it), so it passes.
  The text it reports is `without-marks` (TASK-182)."
  [cite-key rendered]
  (when (and (seq rendered) (not= [rendered] (parser/cite-segments rendered)))
    (let [shown (without-marks rendered)]
      (throw (ex-info
              (str "hdoc emitter cannot represent a `;` in citation " (pr-str cite-key)
                   "'s prefix/suffix text: " (pr-str shown))
              {:type ::unrepresentable-cite-affix :key cite-key :text shown})))))

(defn- check-cite-segment!
  "Throws `ex-info` (`:type ::unrepresentable-cite-text`) naming
  `cite-key` when `segment`, one CiteItem written by `render-cite-item`,
  would not read back as a citation segment
  (`haselnuss.parser/citation-bracket-at?` over `[segment]`, TASK-200):
  a span, image or other `[`-led Inline in its prefix or suffix
  (`[@k, [<http://x>]{.c}]`) ends the parser's bracket scan at its `[`,
  and the citation is lost. A hand-built `:key` outside the key grammar
  (`\"\"`, `\"é\"`, `\"a.b\"`) is refused before this, by `check-cite-key!`
  (TASK-207). The parser never produces either."
  [cite-key segment]
  (let [shown (without-marks segment)]
    (when-not (parser/citation-bracket-at? (str "[" shown "]") 0)
      (throw (ex-info
              (str "hdoc emitter cannot represent citation " (pr-str cite-key)
                   ": its written segment would not read back as a citation segment (a `[`-led"
                   " inline such as a span or image in its prefix or suffix ends the bracket): "
                   (pr-str shown))
              {:type ::unrepresentable-cite-text :key cite-key :text shown})))))

(defn- check-cite-key!
  "Throws `ex-info` (`:type ::unrepresentable-cite-text`) naming
  `cite-key` when the parser would not read it back whole after its `@`
  (TASK-207): in a bracketed citation (`author?` false, either mode) the
  key `haselnuss.parser/bracket-cite-key?` admits, so `[@a.b]` is not
  written as the key `a` with the suffix `.b` nor `[@müller]` as the key
  `m`; as a bare author citation (`author?` true) a whole
  `haselnuss.parser/bare-reference-token?` with no `:`, since `@a.b` is
  the citation `a` followed by `.b` and `@a:b` a cross-reference. The
  parser never produces such a key; a hand-built or JSON AST can."
  [cite-key author?]
  (when-not (if author?
              (and (parser/bare-reference-token? cite-key) (not (str/includes? cite-key ":")))
              (parser/bracket-cite-key? cite-key))
    (throw (ex-info
            (str "hdoc emitter cannot represent citation " (pr-str cite-key) ": "
                 (if author?
                   (str "an author-in-text key must be letters, digits, `_` and `-`, ending on a"
                        " letter or digit, or `@" cite-key "` would not read back as it")
                   (str "a bracketed citation key must be ASCII word characters, `:` and `-`,"
                        " or `[@" cite-key "]` would not read back as it")))
            {:type ::unrepresentable-cite-text :key cite-key}))))

(defn- check-cross-ref-label!
  "Throws `ex-info` (`:type ::unrepresentable-cross-ref`) naming `label`
  when a bare `@label` would not read back as this CrossRef (TASK-207): the
  scan must take it whole (`haselnuss.parser/bare-reference-token?`, so not
  `fig:a.b`, read as `fig:a` then `.b`), and it must hold a `:`, or it
  reads as an author citation. The parser never produces such a label."
  [label]
  (when-not (and (parser/bare-reference-token? label) (str/includes? label ":"))
    (throw (ex-info
            (str "hdoc emitter cannot represent cross-reference " (pr-str label)
                 ": a label must be `prefix:name` in letters, digits, `_`, `-` and `:`, ending on"
                 " a letter or digit, or `@" label "` would not read back as it")
            {:type ::unrepresentable-cross-ref :label label}))))

(defn- render-cite-item
  "Renders one CiteItem `item` (sec4.4: `:key`/`:prefix`/`:suffix`/
  `:mode`) as one `;`-separated segment of a bracketed citation
  (`haselnuss.parser/citation-segment-parts`'s own grammar, written back
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
  (check-cite-key! cite-key false)
  (let [rendered-prefix (when (seq prefix) (render-flat-prose ctx prefix true))
        rendered-suffix (when (seq suffix) (render-flat-prose ctx suffix false))]
    (some->> rendered-prefix (check-no-cite-affix-semicolon! cite-key))
    (some->> rendered-suffix (check-no-cite-affix-semicolon! cite-key))
    (let [segment (str (when rendered-prefix (str rendered-prefix " "))
                       (when (= :year mode) "-")
                       "@" cite-key
                       (when rendered-suffix (str ", " rendered-suffix)))]
      (check-cite-segment! cite-key segment)
      segment)))

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
    (do (check-cite-key! (:key (first items)) true)
        (str "@" (:key (first items))))
    (str "[" (str/join "; " (map (partial render-cite-item ctx) items)) "]")))

(defn- indent-after-line-ends
  "`text` with `prefix` inserted at the start of every non-empty line after
  the first, an empty line staying empty (TASK-180): an indented blank line
  would be a line of nothing but spaces, which CommonMark reads as the same
  blank line, so the indent only adds trailing whitespace. A line starts
  after a newline AND after a lone carriage return (TASK-232): the only CR
  in rendered text is inside a code span (the parser reads one elsewhere as
  a soft break), where a list item or footnote strips its own indent from
  the line after it just as it does after a newline, so the line must carry
  the prefix to keep the text it had. Not inside a block quote (`cr?`
  false): the line after a CR there carries no `>`, so it is a lazy
  continuation that keeps its text as written."
  [^String prefix ^String text cr?]
  (str/replace text (if cr? #"(?<=[\r\n])(?=[^\r\n])" #"(?<=\n)(?=[^\r\n])")
               (java.util.regex.Matcher/quoteReplacement prefix)))

(defn- indent-lines
  "Indents every non-empty physical line of `text` by `width` spaces
  (`indent-after-line-ends`) -- shared by `indent-block-continuation` (a footnote
  definition's own fixed 4-space continuation) and
  `render-list-item-blocks` (TASK-97, whose own continuation width is a
  list item's marker width, not a fixed constant)."
  [width text cr?]
  (let [prefix (apply str (repeat width \space))]
    (subs (indent-after-line-ends prefix (str "\n" text) cr?) 1)))

(defn- indent-continuation-lines
  "Indents every non-empty physical line of `text` by `width` spaces
  (`indent-after-line-ends`), EXCEPT its own first line -- the shape a container's
  FIRST Block needs (TASK-97): its first physical line sits directly after the marker that introduced the
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
  [width text cr?]
  (indent-after-line-ends (apply str (repeat width \space)) text cr?))

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
  [text cr?]
  (indent-lines 4 text cr?))

(def ^:private unrepresentable-nested-block-types
  "The Block types `check-representable-nested-block!` refuses inside
  a `:block-quote`, a `:note`, or a `:list` item (TASK-97's own docstring
  section on lists explains why: all are recognized by a raw, container-
  oblivious line scan on the read side, so no indentation reaches them
  correctly from inside a flexmark container). `:book-quote` (TASK-152)
  is a `!quote` line, recognized by that same scan."
  #{:directive :include :book-quote})

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
                 ": haselnuss.parser recognizes ::: directive fences, !name lines, !include "
                 "and !quote lines via a raw, "
                 "container-oblivious line scan, so no indentation makes this reachable from real parsing")
            {:type ::unrepresentable-nested-block :t (:t block) :context context}))))

(defn- check-blocks-after-sections!
  "Throws `ex-info` (`:type ::unrepresentable-block-after-section`)
  unless every Block in `blocks` (one Block sequence: the document's, a
  section's, a directive body, a block quote, a list item, a footnote)
  that directly follows a `:section` is itself a `:section` whose
  `:level` is at most that one's (TASK-194). A section has no closing
  line: `haselnuss.parser`'s `fold-sections` folds every Block after a
  heading into it until a heading of the same or a higher level, in
  every container (confirmed at a REPL for each of those sequences), so
  a paragraph, a directive or a deeper section written after one
  re-parses INSIDE it. Only a hand-built or transformed AST holds one --
  the parser never produces it. `context` describes the sequence (e.g.
  \"a block quote\"); the ex-data names it, the offending Block's
  `:index` and `:t`, the section's `:after-level` and, for a section,
  its own `:level`."
  [context blocks]
  (doseq [[idx prev block] (map vector (range 1 ##Inf) blocks (rest blocks))
          :when (= :section (:t prev))
          :when (not (and (= :section (:t block)) (<= (:level block) (:level prev))))]
    (throw (ex-info
            (str "hdoc cannot write a " (pr-str (:t block))
                 (when (= :section (:t block)) (str " of level " (:level block)))
                 " at index " idx " of " context ", directly after a level-" (:level prev)
                 " section: a section has no closing line, so it would re-parse inside that"
                 " section; only a section of level <= " (:level prev) " may follow one")
            (cond-> {:type ::unrepresentable-block-after-section :context context :index idx
                     :t (:t block) :after-level (:level prev)}
              (= :section (:t block)) (assoc :level (:level block)))))))

(defn- check-nested-section-levels!
  "Throws `ex-info` (`:type ::unrepresentable-nested-section`) when Section
  `section` holds, directly in its own `:blocks`, a `:section` whose
  `:level` is at most its own (TASK-194): a heading of the same or a
  higher level closes the open section in `haselnuss.parser`'s
  `fold-sections`, so the child would re-parse as a sibling (or an
  ancestor's sibling) rather than nested. The ex-data names the `:index`
  and both levels (`:parent-level`, `:level`). Only a hand-built or
  transformed AST holds one."
  [section]
  (doseq [[idx block] (map-indexed vector (:blocks section))
          :when (and (= :section (:t block)) (<= (:level block) (:level section)))]
    (throw (ex-info
            (str "hdoc cannot write a level-" (:level block) " section at index " idx
                 " inside a level-" (:level section) " section: a heading of the same or a higher"
                 " level ends the section, so it would re-parse outside it; a nested section"
                 " needs a level greater than " (:level section))
            {:type ::unrepresentable-nested-section :index idx
             :parent-level (:level section) :level (:level block)}))))

(defn- alternate-list-markers
  "One boolean per Block in `blocks`, true for a `:list` that directly
  follows a `:list` of the same `:ordered` kind which was itself written
  with the usual marker (TASK-177). CommonMark starts a new list only at a
  change of bullet character or ordered delimiter, so two such siblings
  written alike re-parse as ONE list, loose because of the blank line
  between them. Markers alternate `-`/`*` and `.`/`)` down a run, so a
  sequence without adjacent same-kind lists is written exactly as before."
  [blocks]
  (first
   (reduce (fn [[flags prev prev-alternate?] block]
             (let [alternate? (and (= :list (:t prev) (:t block))
                                   (= (boolean (:ordered prev)) (boolean (:ordered block)))
                                   (not prev-alternate?))]
               [(conj flags alternate?) block alternate?]))
           [[] nil false]
           blocks)))

(defn- list-marker-ctx
  "`ctx` with `::alternate-list-marker` set when `alternate?`, the flag
  `render-list` reads (`alternate-list-markers`)."
  [ctx alternate?]
  (cond-> ctx alternate? (assoc ::alternate-list-marker true)))

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
  (check-blocks-after-sections! "a note" blocks)
  ;; TASK-234: a footnote definition reads a line of only spaces and tabs as
  ;; blank, in any spelling (measured), so a code block directly in a note
  ;; with such a line has none. The parser yields one only from a leading-tab
  ;; line such as `[^1]: ```` / TAB `a` + 4 spaces, where it keeps the
  ;; spaces after the tab's content.
  (doseq [block blocks
          :when (= :code-block (:t block))
          line (str/split (str (:text block)) #"\n" -1)
          :when (re-matches #"[ \t]+" line)]
    (throw (ex-info (str "hdoc cannot write a code block with a line of only spaces or tabs in a footnote: "
                         (pr-str line))
                    {:type ::unrepresentable-footnote-code-line :line line})))
  (let [alternates (alternate-list-markers blocks)]
    (concat-rendered-kv
     (fn [idx block footnotes]
                 ;; TASK-173: a footnote body is never a chunk sequence, and
                 ;; its own number is still reserved (no text) while it renders.
                 ;; TASK-177: a list after a same-kind sibling takes the other marker.
                 ;; TASK-180: a note reached from a tight item's section heading
                 ;; is not that item's content, so its sections are not tight.
                 ;; TASK-232: a list that opens the definition sits on the marker's
                 ;; line, where flexmark counts its content column one further
                 ;; (measured: 4 + marker width + 1; a bullet's lines need 7
                 ;; columns, `1.` 8, not 6 and 7), so its lines take 5, not 4,
                 ;; after a newline or a lone CR: a code span's line starting
                 ;; with spaces in it read back with them.
                 ;; TASK-233: the same one-column shift for a fenced code block
                 ;; that opens the definition (measured: the fence on the marker's
                 ;; line strips up to one more leading space from each body line),
                 ;; so only a line with a leading space needs 5, not 4 (the
                 ;; others, and the closing fence, keep the usual 4).
       (let [first-list? (and (zero? idx) (= :list (:t block)))
             first-code? (and (zero? idx) (= :code-block (:t block)))
             cr? (not (::no-cr-indent ctx))
             ctx (list-marker-ctx (dissoc ctx :chunk-level? ::tight-list-item) (alternates idx))
             [block-text footnotes'] (render-block ctx block footnotes)
             piece (if (zero? idx)
                     (if first-code?
                       (str/replace block-text
                                    (if cr? #"(?<=[\r\n])(?=([^\r\n]))" #"(?<=\n)(?=([^\r\n]))")
                                    (fn [[_ c]] (if (= " " c) "     " "    ")))
                       (indent-continuation-lines (if first-list? 5 4) block-text cr?))
                     (indent-block-continuation block-text cr?))]
         [(if (zero? idx) piece (str "\n\n" piece)) footnotes']))
     footnotes
     blocks)))

(defn- render-note
  "Renders footnote marker `note` (an Inline `:note`, sec4.4/5.12) as
  `[^n]`, its sequential number reserved BEFORE rendering `note`'s own
  `:blocks` (so a nested footnote inside them gets a strictly later
  number, mirroring `haselnuss.emit.html/render-note`'s identical
  reservation order) -- the deferred `[^n]: ...` definition for every
  entry `footnotes'` accumulates is written once, at the end of the
  parser chunk holding the marker (`flush-footnote-definitions`,
  TASK-173), which is the end of the document unless a directive,
  include, `!quote` or display-math block follows. `note`'s own
  `:blocks` recurse through the ordinary Block dispatch via
  `render-note-blocks` (AC #6), which indents
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
        ;; TASK-261: the reservation is entry n-1 of the vector this namespace
        ;; builds, so fill it in place; walking every footnote for each one
        ;; made a document of many footnotes quadratic. The walk stays for an
        ;; accumulator of any other shape.
        filled (if (and (vector? footnotes') (= n (:number (get footnotes' (dec n)))))
                 (assoc-in footnotes' [(dec n) :text] content)
                 (mapv (fn [entry] (if (= n (:number entry)) (assoc entry :text content) entry))
                       footnotes'))]
    [marker filled]))

(defn- escape-highlight-boundary
  "`text` (a highlight's own already-escaped rendered content) with a
  leading or trailing `=` backslash-escaped.

  `escape-text` cannot see this one: it escapes an `=` that has another
  `=` AFTER IT IN THE SAME STRING, and a highlight's boundary `=` has
  the emitter's own `==` delimiter next to it instead, in a string that
  does not exist yet. Left alone, a highlight of the single character
  `=` emitted `=====`, which re-parses as no highlight at all -- the
  round trip lost the node (found by test).

  With a `marker` (a one-character string, default `=`) it does the same
  for a `~~strike~~` boundary (TASK-137)."
  ([text] (escape-highlight-boundary text "="))
  ([text marker]
   ;; The one string whose leading and trailing marker are the SAME character.
   ;; Tested before anything is prefixed, and by equality rather than by a
   ;; suffix: a `\=` at the end of the already-escaped text is just as
   ;; often an escaped BACKSLASH followed by a bare `=` (`escape-text`
   ;; writes `\\` for a literal backslash), and treating that as
   ;; "already escaped" let the `=` escape out of the node -- the
   ;; highlight silently moved its own edge (found by review, fuzzing
   ;; every string over {= \ a} up to length four).
   (let [single? (= marker text)
         text (cond-> text (str/starts-with? text marker) (->> (str "\\")))]
     (cond-> text
       (and (not single?) (str/ends-with? text marker))
       (as-> escaped (str (subs escaped 0 (dec (count escaped))) "\\" marker))))))

(defn- render-strike
  "Renders a `:strike` as `~~struck text~~` (TASK-137, sec5.6), an exact
  round trip. As with a highlight, a strike nested inside another cannot
  be spelled (the inner `~~` closes the outer one), so the inner one is
  written as a `[text]{.strike}` role span, content-preserving and
  kind-lossy, and reported by `lossy-role-span-diagnostics`."
  [ctx inline footnotes]
  (let [[text footnotes'] (render-inlines (assoc ctx :in-strike? true)
                                          (:inlines inline) footnotes)]
    [(if (:in-strike? ctx)
       (str "[" text "]{.strike}")
       (str "~~" (escape-highlight-boundary text "~") "~~"))
     footnotes']))

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
       (str "[" text "]" (attr-group ctx (update (:attr inline) :classes
                                                 (fnil conj []) "highlight")))
       (str "==" (escape-highlight-boundary text) "==" (attr-group ctx (:attr inline))))
     footnotes']))

;; --- Book references (TASK-152, doc-10) --------------------------------------
;;
;; Both nodes are written back with their `:ref` exactly as the author wrote
;; it -- never re-formatted through `haselnuss.book-ref/format-book-ref`,
;; which would turn a short form into a canonical one, or a malformed ref
;; into nothing -- and with every resolved field (`:canonical`, `:text`,
;; `:title`, `:blocks`, `:url`) dropped: they are computed content, which
;; THE CONTRACT keeps out of source, exactly as a resolved cross-ref's text
;; is.

(defn- check-spellable-book-ref!
  "Throws `ex-info` (`:type ::unrepresentable-book-ref`) when `ref` (a
  `:book-ref`/`:book-quote`'s own `:ref`) cannot be written back so that it
  re-parses to itself: blank, a line break, or a construct-specific
  hazard -- edge whitespace on a `!quote` line (which is trimmed), a `}`
  inside `@{…}` (the first one closes it). Unreachable from real parsing,
  which cannot produce any of these; a hand-built, JSON or
  transform-produced node can."
  [t written]
  (when (or (not (string? written)) (str/blank? written) (re-find #"[\r\n]" written)
            (if (= :book-ref t)
              (str/includes? written "}")
              (not= written (str/trim written))))
    (throw (ex-info (str "hdoc emitter cannot represent the " (pr-str t) " reference " (pr-str written)
                         ", which would not re-parse as written")
                    {:type ::unrepresentable-book-ref :t t :ref written}))))

(defn- render-book-ref
  "`@{ref}`, the ref as written (see this section's comment). TASK-227:
  inside a table cell a `|` in the ref is marked, not escaped (as
  `attr-group` does): the parser splits a row `@{`-group-aware and keeps
  `\\|` there as backslash plus pipe, so `@{a|b}` must be written bare."
  [ctx inline footnotes]
  (check-spellable-book-ref! :book-ref (:ref inline))
  [(cond-> (str "@{" (:ref inline) "}")
     (::in-table-cell ctx) (str/replace "|" (str escape-mark "|")))
   footnotes])

(defn- render-book-quote
  "`!quote ref`, the ref as written (see this section's comment)."
  [_ctx block footnotes]
  (check-spellable-book-ref! :book-quote (:ref block))
  [(str "!quote " (:ref block)) footnotes])

(def built-in-inline-handlers
  "The built-in `:t -> (fn [ctx inline footnotes] -> [rendered
  footnotes'])` table `render-inline` dispatches through (TASK-88): every
  Inline variant `haselnuss.ast` defines (AC #4)."
  {:str (fn [ctx inline footnotes] [(escape-text (:text inline) ctx) footnotes])
   ;; TASK-233: the parser drops trailing spaces before a line end but keeps
   ;; a trailing tab as a `:space`, so a `:space` right before a
   ;; `:soft-break` is written as the tab that made it.
   :space (fn [ctx _inline footnotes] [(if (::tab-space? ctx) "\t" " ") footnotes])
   :soft-break (fn [_ctx _inline footnotes] ["\n" footnotes])
   ;; A trailing backslash before the line ending (confirmed at a REPL to
   ;; round-trip to :line-break), not two-or-more trailing spaces: no
   ;; invisible whitespace left sitting at the end of a line.
   :line-break (fn [_ctx _inline footnotes] ["\\\n" footnotes])
   :emph (fn [ctx inline footnotes] (render-wrapped ctx inline footnotes :emph))
   :strong (fn [ctx inline footnotes] (render-wrapped ctx inline footnotes :strong))
   :strike render-strike
   :small-caps render-role-span
   :sub render-role-span
   :sup render-role-span
   :code (fn [ctx inline footnotes]
           (check-code-span-lines! (:text inline) (::code-line-start? ctx)
                                   ;; A lone CR breaks in a table cell (and in an
                                   ;; ATX heading, see `render-heading`).
                                   (boolean (::in-table-cell ctx)))
           ;; TASK-225: the parser splits a table row code-span-aware and
           ;; reads `\|` in a span as backslash plus pipe, so a `|` in code
           ;; text is marked (as `attr-group` does), not escaped:
           ;; `render-table-cell` passes the marked pipe on bare.
           (cond-> (str/replace (render-code-span (:text inline)) "\n" code-break)
             (::in-table-cell ctx) (str/replace "|" (str escape-mark "|"))
             true (vector footnotes)))
   ;; Raw TeX, verbatim, no escaping: an as-parsed MathInline's own :tex
   ;; is guaranteed to start/end on non-whitespace and never cross a
   ;; newline (haselnuss.parser/inline-math-closing-index's own scan
   ;; rules), so it is always safe to splice directly between $ signs.
   ;; TASK-230: in a table cell a `|` is marked, not escaped: the parser
   ;; splits a row math-aware and keeps `\|` inside `$..$` as backslash
   ;; plus pipe (as for a code span and a book ref), so it is written bare.
   :math-inline (fn [ctx inline footnotes]
                  [(cond-> (str "$" (:tex inline) "$")
                     (::in-table-cell ctx) (str/replace "|" (str escape-mark "|")))
                   footnotes])
   :link render-link
   :image (fn [ctx inline footnotes] [(render-image ctx inline) footnotes])
   :span render-span
   ;; A parsed :label is always already ref-token-char?'s own alphabet
   ;; (letter/digit/_/-/:), which needs no escaping of its own; a
   ;; hand-built one outside it is refused (TASK-207).
   :cross-ref (fn [_ctx inline footnotes]
                (check-cross-ref-label! (:label inline))
                [(str "@" (:label inline)) footnotes])
   :book-ref render-book-ref
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

(defn- choose-emphasis-delim
  "TASK-237: the delimiter character (`\"*\"` or `\"_\"`) to write the
  `:emph`/`:strong` at `idx` of `inlines` with, in `::emphasis-mode`
  `:local` (in `:star` mode, and without a mode, always `*`: the spelling
  before TASK-237). `*` unless that run would touch another
  run of the same character and read back as something else. This is a
  candidate, not a verdict: `render-inlines` re-reads the paragraph it makes
  and keeps it only if the emphasis nesting survives, because whether runs
  that touch really fuse depends on what surrounds them (`*1. *e**` reads
  back fine, `**c*x*` does not). The cases, established against the real
  parser:

  - a sibling right before it (`prev`, the previous sibling's `{:kind :delim}`
    when it is an emphasis too): `*a*` `*b*` and `**a**` `**b**` fuse
    (`*a**b*` is ONE emphasis), an emphasis next to a strong does not
    (`*a***b**` is fine);
  - the enclosing emphasis (`::parent-emphasis`) when this is its first or
    last child: the same kind fuses (`**c**` for an emphasis in an emphasis),
    except a strong that is the ONLY child of a strong (`****c****` reads back
    right); a lone emphasis in a strong (`***c***`) reads back as the reverse
    nesting. An emphasis in a strong at an edge beside other content, and a
    strong in an emphasis, are fine.

  `_` is the alternative. It cannot open right after an alphanumeric nor
  close right before one (CommonMark's intraword rule), and between two `_`
  runs every pairing fuses. With no safe candidate the node keeps `*` and
  the verification in `render-inlines` refuses the document.
  `inlines` is the sibling vector and `idx` the node's position in it,
  `prev-char` the last character written before it (nil when none). When
  the next sibling is an emphasis of the same kind (which must take the
  other character) and the sibling after THAT cannot
  follow a `_` closer, this node takes the `_` instead, so that run is the one
  that can bear it."
  [ctx inlines idx prev prev-char]
  (let [inline (nth inlines idx)
        next-inline (get inlines (inc idx))
        n (count inlines)
        kind (:t inline)
        parent (::parent-emphasis ctx)
        first? (zero? idx)
        last? (= idx (dec n))
        only? (and first? last?)
        underscore-blocked-before? (fn [x]
                                     (or (and (= :str (:t x)) (seq (:text x))
                                              (Character/isLetterOrDigit (char (first (:text x)))))
                                         ;; `_` is a citation-key character, so a bare `@key`
                                         ;; or `@label` right after `_e_` is not read as one
                                         (contains? #{:cite :cross-ref} (:t x))))
        conflicts? (fn [d]
                     (or (and prev (= d (:delim prev)) (or (= kind (:kind prev)) (= d "_")))
                         (and parent (= d (:delim parent)) (or first? last?)
                              (if (= d "_")
                                true
                                (or (and (= kind (:kind parent))
                                         (not (and only? (= kind :strong))))
                                    (and only? (= :strong (:kind parent)) (= kind :emph)))))))
        alnum? (fn [c] (and c (Character/isLetterOrDigit (char c))))
        can-use-underscore? (and (not (and (pos? idx) (alnum? prev-char)))
                                 (not (and next-inline (underscore-blocked-before? next-inline))))
        hand-over? (and next-inline (= kind (:t next-inline))
                        (when-let [after (get inlines (+ idx 2))] (underscore-blocked-before? after)))]
    (cond (not= :local (::emphasis-mode ctx)) "*"
          (and hand-over? can-use-underscore? (not (conflicts? "_"))) "_"
          (not (conflicts? "*")) "*"
          (and (not (conflicts? "_")) can-use-underscore?) "_"
          :else "*")))

(defn- render-inlines-once
  "`render-inline` folded over every Inline in `inlines`, in order,
  concatenating rendered text and threading `footnotes` throughout.

  The OUTERMOST call -- the one a Block makes, not one an Inline makes
  for its own children -- holds the block's whole inline source, so it is
  the one that resolves `escape-text`'s `escape-mark`s (TASK-161). Every
  Inline is rendered with `:in-inline?` set, and `render-block` clears
  it, so a footnote's own Blocks resolve their own."
  [ctx inlines footnotes]
  (let [inlines (vec (cond-> inlines (not (:in-inline? ctx)) replace-nul))
        ;; TASK-258: one StringBuilder, and only its last character looked
        ;; at, rather than `(str text inline-text)` and `(last text)` per
        ;; Inline: both walked everything written so far, so a paragraph
        ;; on one long line took time quadratic in its length.
        sb (StringBuilder.)
        [footnotes']
        (reduce (fn [[footnotes line-start? code-start? prev-emphasis idx] [inline next-inline]]
                  (let [n (.length sb)
                        prev-char (when (pos? n) (.charAt sb (dec n)))
                        emphasis? (contains? #{:emph :strong} (:t inline))
                        delim (when emphasis?
                                (choose-emphasis-delim ctx inlines idx prev-emphasis prev-char))
                        [inline-text footnotes'] (render-inline (assoc ctx
                                                                       ::parent-emphasis nil
                                                                       ::delim delim
                                                                       ::tab-space? (and (= :space (:t inline))
                                                                                         (= :soft-break (:t next-inline)))
                                                                       :line-start? line-start?
                                                                       ;; TASK-231: a code span opening a
                                                                       ;; physical line (`check-code-span-lines!`).
                                                                       ::code-line-start? code-start?
                                                                       :in-inline? true
                                                                       ;; TASK-152: `escapes-as-book-ref-brace?`.
                                                                       :after-at? (= \@ prev-char))
                                                                inline footnotes)]
                    (.append sb (str inline-text))
                    [footnotes'
                     ;; Whether the NEXT inline can open a physical line
                     ;; (TASK-135): after a break, or while only blanks
                     ;; have been written since the last one.
                     (case (:t inline)
                       (:soft-break :line-break) true
                       :space line-start?
                       false)
                     (case (:t inline)
                       (:soft-break :line-break) true
                       :space code-start?
                       false)
                     (when emphasis? {:kind (:t inline) :delim delim})
                     (inc idx)]))
                ;; An inline nested in another (emphasis, a link) or a table
                ;; cell follows its opening marker, so it starts no line.
                [footnotes (:line-start? ctx true)
                 (and (not (:in-inline? ctx)) (not (::in-table-cell ctx)) (:line-start? ctx true))
                 nil 0]
                (map vector inlines (concat (rest inlines) [nil])))
        text (str sb)]
    [(cond-> text (not (:in-inline? ctx)) resolve-escape-marks) footnotes']))

(defn- emphasis-risky?
  "TASK-237: whether any `:emph`/`:strong` in `inlines` (at any depth) is
  next to another emphasis or holds one as a direct child, the only places
  where its delimiter run can touch (or be read against) another's. Cheap and
  over-approximate by design: `render-inlines` verifies before it believes it,
  and prose with no emphasis in emphasis, or two emphases in a row, never
  pays for it."
  [inlines]
  (let [em? (fn [x] (and (map? x) (contains? #{:emph :strong} (:t x))))
        risky-siblings? (fn [xs]
                          (or (some (fn [[a b]] (and (em? a) (em? b))) (partition 2 1 xs))
                              (some (fn [x] (and (em? x) (some em? (:inlines x)))) xs)))]
    ;; every sequence of siblings anywhere in the tree: a block's inlines, an
    ;; emphasis's children, a citation's prefix and suffix
    (boolean (some risky-siblings? (filter sequential? (tree-seq coll? seq inlines))))))

(defn- emphasis-skeleton
  "The emphasis nesting of `x` (inlines, or any tree holding them) alone:
  `[[:emph [\"text\" [:strong [...]]]]]`, every other node spliced out (what
  it holds, a link's text or a citation's affixes, kept in place). The words
  inside an emphasis are kept, any blank as one space (a fusion like `*e**e*`
  shows in its text too), the text outside every emphasis is not. What
  `render-inlines` compares a re-read paragraph against: a fused or
  mis-nested emphasis changes it, a re-spelled link or merged text does not."
  ([x] (emphasis-skeleton x false))
  ([x in-emphasis?]
   (let [merge-text (fn [xs]
                      (vec (reduce (fn [acc y] (if (and (string? y) (string? (peek acc)))
                                                 (conj (pop acc) (str (peek acc) y))
                                                 (conj acc y)))
                                   [] xs)))]
     (cond (and (map? x) (contains? #{:emph :strong} (:t x)))
           [[(:t x) (emphasis-skeleton (:inlines x) true)]]
           (and (map? x) in-emphasis? (contains? #{:str :space :soft-break} (:t x)))
           [(case (:t x) :str (:text x) " ")]
           (map? x) (merge-text (mapcat #(emphasis-skeleton (val %) in-emphasis?) (sort-by (comp str key) x)))
           (sequential? x) (merge-text (mapcat #(emphasis-skeleton % in-emphasis?) x))
           :else []))))

(defn- reread-emphasis-skeleton
  "The `emphasis-skeleton` of what the parser makes of the rendered inline
  `text` as a one-paragraph document, or nil when it cannot say (it threw,
  e.g. on a footnote marker with no definition, or the text is not one
  paragraph)."
  [text]
  (try (let [blocks (:blocks (parser/parse (str (escape-line-starts (resolve-escape-marks text)) "\n")))]
         (when (and (= 1 (count blocks)) (= :para (:t (first blocks))))
           (emphasis-skeleton (:inlines (first blocks)))))
       (catch Exception _ nil)))

(defn- render-inlines
  "`render-inlines-once`, plus TASK-237's care for emphasis that would touch:
  `*e*` followed by `_e_` is two emphases, but written `*e**e*` it reads back
  as ONE; an emphasis inside an emphasis at an edge fuses the same way.

  A block's inline source with no such shape (`emphasis-risky?`, nearly
  all of them) is rendered exactly as before, `*` and `**` throughout. A
  risky one is rendered twice: the `*` spelling, which stays whenever the
  parser reads it back with the emphasis nesting it had (so everything that
  round-tripped before is byte-identical), and otherwise the `:local`
  spelling of `choose-emphasis-delim`, which alternates in `_`. When neither
  reads back right it throws `::unrepresentable-emphasis`. Where the parser
  cannot be asked (`reread-emphasis-skeleton` nil) the `*` spelling stands.
  Only the outermost call of a block decides; the nested calls follow its
  `::emphasis-mode`."
  [ctx inlines footnotes]
  (if (or (and (:in-inline? ctx) (::emphasis-mode ctx)) (not (emphasis-risky? inlines)))
    (render-inlines-once (cond-> ctx (not (::emphasis-mode ctx)) (assoc ::emphasis-mode :star)) inlines footnotes)
    (let [star (render-inlines-once (assoc ctx ::emphasis-mode :star) inlines footnotes)
          local (render-inlines-once (assoc ctx ::emphasis-mode :local) inlines footnotes)
          want (emphasis-skeleton inlines)
          have (reread-emphasis-skeleton (first star))]
      (cond (or (nil? have) (= want have)) star
            (and (not= (first star) (first local))
                 (= want (reread-emphasis-skeleton (first local)))) local
            :else (throw (ex-info (str "hdoc emitter cannot write this emphasis so that it reads back "
                                       "with the same nesting: neither `*` nor `_` spelling of the "
                                       "touching runs survives")
                                  {:type ::unrepresentable-emphasis :inlines inlines}))))))

;; --- Block dispatch (TASK-88) ------------------------------------------------

(defn- render-para
  "Renders Para `block` (sec4.3: `:inlines`) as its own rendered text,
  with `escape-line-start-hazard` applied to every physical line (this
  namespace's own docstring: the one hazard `escape-text` cannot cover
  unconditionally) -- the only Block variant in this namespace's scope
  whose own content is UNPREFIXED and so can genuinely start a physical
  line, apart from a heading that spans lines: an ATX heading's text
  always follows `# `, but `render-heading` writes a multi-line one in
  setext form, at column 0, and applies the same check itself.
  `render-block-quote` always prefixes every line with `> `, so it
  needs no check of its own."
  [ctx block footnotes]
  (let [[text footnotes'] (render-inlines ctx (:inlines block) footnotes)]
    [(escape-line-starts text) footnotes']))

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
  (check-blocks-after-sections! "a block quote" (:blocks block))
  (let [[inner footnotes'] (render-blocks (assoc ctx ::no-cr-indent true) (:blocks block) footnotes)
        prefixed (str/join "\n"
                           ;; TASK-234: a line of only spaces and tabs is a code
                           ;; line (a fenced block's blank line keeps them), so it
                           ;; keeps its marker's space and its text; any other
                           ;; blank line is the bare `>`.
                           (map (fn [line] (if (and (str/blank? line) (not (re-matches #"[ \t]+" line)))
                                             ">"
                                             (str "> " line)))
                                (str/split inner #"\n" -1)))]
    [prefixed footnotes']))

(def ^:private setext-underlines
  "The setext-heading underline for each level that has one (TASK-174):
  CommonMark's setext form only spells levels 1 and 2."
  {1 "===" 2 "---"})

(defn- ends-in-attr-group?
  "True when the built-in writer spells Inline `inline`, standing at the
  top level of a heading, with a trailing `{...}` attribute group
  (TASK-183): a `:span`, `:link`, `:image` or `:highlight` whose Attr
  carries anything (a `:code` with an Attr is a `:span` around it, see
  `haselnuss.parser/convert-children`), and the `:small-caps`/`:sub`/
  `:sup` role spans, always `[text]{.sub}` (`render-role-span`). Not a
  top-level `:strike` (`~~text~~`; only a nested one is a role span), nor
  a `:book-ref` (`@{ref}` is not an attribute group, confirmed at a
  REPL)."
  [inline]
  (case (:t inline)
    (:span :link :image :highlight) (boolean (seq (attr-group (:attr inline))))
    (:small-caps :sub :sup) true
    false))

(defn- render-heading
  "The heading of Section `block` as `[text footnotes']`, given its own
  rendered Inlines `heading-text` and attribute group `group` (possibly
  empty).

  A heading on one line is written in ATX form (`#` * `:level`, a space,
  the text, then the group), whose text is never at risk of the
  leading-`#`/`:::`/`---` hazards `render-para` guards against: it
  always follows `# ` (or `## `, ...), never column 0.

  A heading whose text spans lines -- a `:soft-break`/`:line-break`
  anywhere in its Inlines, which `haselnuss.parser` produces from a
  setext heading (`Setext\\\\` / `line` / `---`) -- cannot be ATX: an ATX
  heading ends at its line ending, so the rest would re-parse as a
  paragraph of its own (TASK-174). Levels 1 and 2 are written in setext
  form instead: the text's own lines, the group on the last of them
  (where the parser reads it back as the heading's Attr, confirmed at a
  REPL), then `===`/`---`. Setext text starts at column 0 like a
  paragraph's, so every line gets `escape-line-start-hazard` -- a line
  of `-` inside the heading would otherwise end it early.

  Throws `ex-info` (`:type ::unrepresentable-heading-break`, naming the
  level) where no spelling re-parses to the same heading, like this
  emitter's other gaps: a level 3-6 heading with a break (ATX is one
  line), or a heading with a blank line -- a soft break first or twice
  in a row, or any break last. A blank line ends the setext text, so the
  rest re-parses as a paragraph or the break is dropped. The one blank
  line that does re-parse is a last one the group fills after a hard
  break, `a\\\\` / ` {#x}` / `---` (confirmed at a REPL, and the parser
  produces exactly that heading from that source); after a soft break
  the group's line reads back without the break. A hard break leaves its
  `\\\\` on its own line, so it never makes an earlier line blank. No
  other blank line is reachable from parsing, only from a hand-built or
  transformed AST.

  A heading with no attribute group of its own whose last Inline (past
  any trailing `:space` or blank `:str`, which re-parsing trims) is
  written with a trailing group (`ends-in-attr-group?`) throws too
  (TASK-183, `:type ::unrepresentable-heading-attr-inline`, naming the
  level and the Inline's `:t`): `# a [s]{.c}` ends the heading in that
  group, and a group ending a heading is always the heading's own
  (`haselnuss.parser`'s `pop-trailing-attr-group`), so it would re-parse
  as a heading Attr around a literal `[s]` (or a link or image stripped
  of its Attr) -- for a span holding a citation, as a bracketed citation
  of a different mode. No spelling avoids that, all confirmed at a REPL:
  flexmark reads no empty group (`{}` and `{ }` re-parse as literal
  text, `{#}`/`{.}` are malformed), ATX closing hashes (`# a [s]{.c} #`)
  and setext form leave the group last, and nothing that could follow
  the Inline converts to zero Inlines. With a group of its own the
  heading's group follows the Inline's and each reads back to its owner
  (`# a [s]{.c} {#x}`). As-parsed, a group there was always the
  heading's, so only a hand-built or transformed AST reaches this."
  [block heading-text group]
  (let [level (:level block)
        unrepresentable! (fn [why]
                           (throw (ex-info (str "hdoc cannot write a level-" level " heading " why)
                                           {:type ::unrepresentable-heading-break :level level})))
        ;; Trailing whitespace (`:space`, a blank `:str`) is trimmed off a
        ;; heading on re-parse, so the group before it still ends the line.
        last-inline (->> (rseq (vec (:heading block)))
                         (drop-while #(or (= :space (:t %))
                                          (and (= :str (:t %)) (str/blank? (:text %)))))
                         first)]
    (when (and (empty? group) (ends-in-attr-group? last-inline))
      (throw (ex-info (str "hdoc cannot write a level-" level " heading ending in a " (name (:t last-inline))
                           " with an attribute group but without a group of its own: the "
                           (name (:t last-inline)) "'s group would re-parse as the heading's")
                      {:type ::unrepresentable-heading-attr-inline :level level :t (:t last-inline)})))
    (if-not (str/includes? heading-text "\n")
      (str (when-let [c (->> (tree-seq coll? seq (:heading block))
                             (filter #(and (map? %) (= :code (:t %)) (str/includes? (:text %) "\r")))
                             first)]
             ;; TASK-231: an ATX heading ends at a lone CR (a setext one,
             ;; written when the heading spans lines, keeps it).
             (throw (ex-info (str "hdoc emitter cannot represent a code span holding a carriage return"
                                  " in a single-line heading: " (pr-str (:text c)))
                             {:type ::unrepresentable-code-span :text (:text c) :carriage-return true})))
           (apply str (repeat level "#")) " " heading-text (when (seq group) (str " " group)))
      (let [underline (or (setext-underlines level)
                          (unrepresentable! (str "holding a line break: "
                                                 "only a level 1 or 2 (setext) heading spans lines")))
            lines (str/split heading-text #"\n" -1)
            group-fills-last? (and (seq group) (= :line-break (:t (peek (vec (:heading block))))))]
        (when (some str/blank? (cond-> lines group-fills-last? butlast))
          (unrepresentable! (str "with a blank line (a line break at its start or end, or two "
                                 "in a row): a blank line ends a setext heading")))
        (str (escape-line-starts heading-text)
             (when (seq group) (str " " group)) "\n" underline)))))

(defn- render-section
  "Renders Section `block` (sec4.3: `:level`/`:heading`/`:blocks`/
  `:attr`) as its heading (`render-heading`: ATX, or setext when the
  heading spans lines) carrying a trailing `attr-group` when `:attr`
  carries anything (AC #6), then its own nested Blocks after a blank
  line.

  In a TIGHT list item (`::tight-list-item`, set by
  `render-list-item-blocks`, TASK-180) the nested Blocks follow with no
  blank line (`render-tight-section-body`): CommonMark has no sections,
  so they are the item's own direct children, and a blank line between
  any two of them makes the list loose."
  [ctx block footnotes]
  (check-nested-section-levels! block)
  (check-blocks-after-sections! (str "a level-" (:level block) " section") (:blocks block))
  (let [[heading-text footnotes'] (render-inlines ctx (:heading block) footnotes)
        group (attr-group (:attr block))
        _ (check-group-fits! "a heading's attribute group" group #"[\n\r]" "a line break")
        heading-line (render-heading block heading-text group)
        tight? (::tight-list-item ctx)
        [body footnotes''] (if tight?
                             (render-tight-section-body ctx block footnotes')
                             (render-blocks ctx (:blocks block) footnotes'))]
    [(cond (str/blank? body) heading-line
           tight? (str heading-line "\n" body)
           :else (str heading-line "\n\n" body))
     footnotes'']))

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

(defn- block-start-tex-line?
  "True when TeX line `line`, written as a continuation line of the
  Paragraph a contained `:math-block` is read from, MIGHT start a Block
  instead (TASK-195). Deliberately conservative -- the cost of a false
  positive is only 4 spaces of indentation the parser strips again --
  so it mostly looks at the first non-space character alone: a list
  marker (`-`, `+`, `*`), a heading (`#`), a quote (`>`), a code fence (a
  backtick, `~`), a thematic break (`-`, `*`, `_`), a table row or
  delimiter row (`|`, `:`, `-`), a directive fence (`:`), an
  `!include`/`!quote` line (`!`), an HTML block (`<`), a footnote or link
  reference definition (`[`), and a `$` (a fence of its own). Two
  characters common at the start of an ordinary TeX line are narrowed to
  the shape that actually starts a Block, so such math is still written
  unchanged: a digit only as an ordered-list marker (digits, then `.` or
  `)` -- `1 \\leq x` is not one), and `=` only as a whole setext
  underline (`= a + b` is not one). A backslash, the usual start of a TeX
  line, is never one."
  [line]
  (boolean (or (re-find #"^[ \t]*[-+*_#>`~|:!<\[$]" line)
               (re-find #"^[ \t]*[0-9]+[.)]" line)
               (re-find #"^[ \t]*=+[ \t]*$" line))))

(defn- render-math-block
  "Renders MathBlock `block` (sec4.3/sec5.7: `:tex`/`:attr`) as the
  two-line `$$`/`$$ {...}` fenced form (never the single-line `$$ ... $$`
  shorthand -- this namespace's own docstring explains why), `:tex`
  spliced in verbatim between the fence lines (raw TeX on the read side,
  same as `:code-block`'s own `:text`) and `attr-group` trailing the
  CLOSING fence, matching `math-attr-group`'s own read-side placement.

  Inside a container (`::contained?`: a block quote, list item or footnote
  -- anywhere but column 0 of a chunk sequence) the block is read back
  only as one whole flexmark Paragraph (`haselnuss.parser`'s
  `math-paragraph-block`), so two more things hold there (TASK-195):

  - a TeX line that would START a Block (`- \\frac{a}{b}`, `# x`, `> x`,
    a code fence, a table delimiter row ...; `block-start-tex-line?`)
    would interrupt that Paragraph, so when any line would, every TeX
    line and the closing fence are indented 4 more spaces: an indented
    line never interrupts a Paragraph, and the parser's `dedent` strips
    exactly the indentation those lines share, so the TeX's own
    alignment is kept;
  - a blank TeX line would END that Paragraph, and nothing spells one
    inside it, so it throws `ex-info` (`:type
    ::unrepresentable-math-block`) naming the TeX.

  - a TeX line whose leading whitespace holds a tab reads back with the
    tab expanded to spaces by column (flexmark strips the container's
    prefix from a tab-expanded line), so it can never come back as
    written; it throws the same `::unrepresentable-math-block`. A tab
    later in a line, or anywhere at column 0, is kept (TASK-196).

  At column 0 the raw line scan extracts the block before flexmark sees
  it, so neither applies and the output there is unchanged.

  In every context, a TeX line that is itself a closing fence
  (`haselnuss.parser/math-fence-line?`: `$$`, optionally with an
  attribute group and trailing blanks, at the start of the line) would
  end the block there on re-parse, so it throws the same
  `::unrepresentable-math-block` (TASK-196). An indented `$$` line is
  kept: the parser only matches a fence at the start of a (dedented)
  line, and the contained 4-space indentation is stripped exactly."
  [ctx block footnotes]
  (let [group (attr-group (:attr block))
        _ (check-group-fits! "a display-math closing fence" group line-terminators "a line terminator")
        ;; `math-fence-line-re` reads its group as `{[^}]*}`.
        _ (check-group-fits! "a display-math closing fence"
                             (if (seq group) (subs group 0 (dec (count group))) "") #"\}" "a `}`")
        tex (:tex block)
        closing (str "$$" (when (seq group) (str " " group)))
        tex-lines (str/split tex #"\n" -1)
        refuse (fn [why]
                 (throw (ex-info (str "hdoc emitter cannot represent display math " why "; TeX " (pr-str tex))
                                 {:type ::unrepresentable-math-block :tex tex})))]
    (cond
      (some parser/math-fence-line? tex-lines)
      (refuse (str "with a line that is itself a `$$` fence: it would close the block there "
                   "(indent that line to keep it in the TeX)"))

      (not (::contained? ctx))
      [(str "$$\n" tex "\n" closing) footnotes]

      ;; No TeX at all reads back from the bare fence pair.
      (= "" tex)
      [(str "$$\n" closing) footnotes]

      (some str/blank? tex-lines)
      (throw (ex-info (str "hdoc emitter cannot represent display math with a blank line inside "
                           "a block quote, list item or footnote: there the block is one "
                           "paragraph, which a blank line ends; TeX " (pr-str tex))
                      {:type ::unrepresentable-math-block :tex tex}))

      (some #(re-find #"^[ \t]*\t" %) tex-lines)
      (refuse (str "with a line indented by a tab inside a block quote, list item or footnote: "
                   "there the tab reads back as spaces"))

      (some block-start-tex-line? tex-lines)
      [(str "$$\n" (str/join "\n" (map #(str "    " %) (conj tex-lines closing)))) footnotes]

      :else
      [(str "$$\n" tex "\n" closing) footnotes])))

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
    ;; TASK-206: the caption's escape marks are decided over the whole
    ;; written line, not the caption alone -- a `<!--`, `<?`, `{` or `!`
    ;; in it can be closed (or opened onto) by the destination, title or
    ;; attribute group after it, as `render-inlines` decides a paragraph's
    ;; over its whole block. So the caption is rendered as an inner run
    ;; (`:in-inline?`, which leaves its marks unresolved), and the NULs
    ;; the outermost `render-inlines` would have replaced are replaced
    ;; here, over the whole figure: the destination, title and attribute
    ;; group are written without `escape-text`, so they carry no marks,
    ;; and after this no U+0000 of theirs can be taken for one.
    ;;
    ;; Every caption line after the first opens a physical line, so it
    ;; gets `render-para`'s line-start hazard escaping (`- b`, `1. b`,
    ;; `---`, ...) -- `escape-line-starts` over the whole line, which skips
    ;; a line a `code-break` starts (TASK-171) and never matches the first,
    ;; which opens with `![`.
    (let [{:keys [caption attr]} (replace-nul block)
          image (replace-nul image)
          [caption-text footnotes'] (render-inlines (assoc ctx :in-inline? true) caption footnotes)
          destination (render-destination (:src image))
          group (attr-group (assoc (:attr image) :id (:id attr)))]
      [(-> (str "![" caption-text "](" destination (render-title image) ")" group)
           resolve-escape-marks
           escape-line-starts)
       footnotes'])))

;; --- Footnote definitions per parser chunk (TASK-173) ------------------------
;;
;; `haselnuss.parser` resolves a `[^n]` marker only against definitions in
;; the same CHUNK: the raw line scan (`parse-directive-region`, then
;; `extract-math-chunks`) cuts the body at every column-0 line it
;; recognizes -- a `:::` fence, opening or closing, a one-line `!name{...}`,
;; `!include`, `!quote`, and a `$$` display-math block -- and each piece
;; of prose between two cuts is its own flexmark Document. A directive's
;; body is cut the same way, at its own fences, so a marker inside a body
;; never sees a definition outside it, nor the reverse.
;;
;; So a definition is written at the end of the chunk holding its marker:
;; just before the next Block that starts a chunk, just before a
;; directive's closing fence, or -- the only place before TASK-173, and
;; still the place for everything after the last cut -- at the end of the
;; document. Only Blocks in a document, section or directive Block
;; sequence can cut: a `$$` in a list item or a footnote is indented, one
;; in a block quote is behind `> `, and a directive, include or `!quote`
;; cannot be nested in any of those (`check-representable-nested-block!`).
;; A document with no marker before a cut emits exactly what it did.
;;
;; Known limits, all through TASK-88's `:block-handlers` extension point:
;; a custom handler for a NEW Block type that writes its own column-0 cut
;; line is not recognized as a cut (only the built-in types below are),
;; and a custom handler that REPLACES the built-in `:directive` one skips
;; `render-directive`'s before-the-closing-fence flush, so a marker in its
;; body is defined after the fence, out of reach. Likewise (TASK-177), a
;; custom handler REPLACING the built-in `:list` one sees
;; `::alternate-list-marker` in its ctx and must hand off to `render-list`
;; (or honour the flag itself), or adjacent same-kind lists merge again.
;; And (TASK-180) one REPLACING the built-in `:section` one sees
;; `::tight-list-item` inside a tight list item and must write no blank
;; line in the section (or hand off to `render-section`), or the list
;; re-parses loose. And (TASK-181) one REPLACING the built-in
;; `:thematic-break` one sees `::break-after-line` when the break is a
;; later Block of a tight list item and must write `***` there, or its
;; `---` turns the paragraph above it into a setext heading.

(def ^:private chunk-boundary-types
  "The Block types written as a column-0 line `haselnuss.parser`'s raw
  scan cuts a chunk at (see the section comment above)."
  #{:directive :include :book-quote :math-block})

(def ^:private chunk-level-containers
  "The Block types whose own `:blocks` are still a sequence of parser
  chunks, so `render-block` keeps `:chunk-level?` set for them alone."
  #{:section :directive})

(defn- chunk-boundary?
  "True when `block`, rendered under `ctx`, starts a new parser chunk."
  [ctx block]
  (boolean (and (:chunk-level? ctx) (chunk-boundary-types (:t block)))))

(defn- join-blocks
  "`text` and `block-text` joined by a blank line, or `block-text` alone
  when `text` is still blank."
  [text block-text]
  (if (str/blank? text) block-text (str text "\n\n" block-text)))

(defn- blank-line-joiner
  "A stateful `join-blocks` over a whole sequence (TASK-261): `(join!
  block-text)` joins one more piece onto the text so far exactly as
  `(join-blocks text block-text)` would, and `(join!)` returns that text.

  `join-blocks` itself copies the text so far for every piece, which made
  `render-blocks` quadratic in the number of Blocks (80k paragraphs took
  ~12 s). While the text is still blank -- the only state in which
  `join-blocks` REPLACES it rather than appending to it -- it is kept as
  the last piece itself (nil and whitespace included, as `join-blocks`
  returns them); the first non-blank piece starts one StringBuilder that
  every later piece is appended to after a blank line, and text holding
  a non-blank character never becomes blank again."
  []
  (let [blank-text (volatile! "")
        sb (volatile! nil)]
    (fn
      ([] (if-let [^StringBuilder b @sb] (str b) @blank-text))
      ([block-text]
       (if-let [^StringBuilder b @sb]
         (.append (.append b "\n\n") (str block-text))
         (if (str/blank? block-text)
           (vreset! blank-text block-text)
           (vreset! sb (StringBuilder. (str block-text)))))
       nil))))

(defn- flush-footnote-definitions
  "`[defs footnotes']`: `defs` the `[^n]: text` definitions for every
  entry of `footnotes` not yet written, blank-line separated (nil when
  there are none), and `footnotes'` the same vector with those entries
  marked `:written?`. Entries stay in the vector, so `render-note`'s
  numbering by count keeps running across chunks."
  [footnotes]
  (let [pending (remove :written? footnotes)]
    (if (empty? pending)
      [nil footnotes]
      [(str/join "\n\n" (map (fn [{:keys [number text]}] (str "[^" number "]: " text)) pending))
       (mapv #(assoc % :written? true) footnotes)])))

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
  there rather than silently. `quote` (TASK-152) for the same reason:
  `!quote` is the book-quote line."
  #{"include" "quote"})

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
    (let [group (attr-group (:attr block))]
      (check-group-fits! "a `!name{...}` line" group line-terminators "a line terminator")
      [(str bang-directive-marker (:name block) group) footnotes])
    (let [_ (check-blocks-after-sections! "a directive" (:blocks block))
          header (directive-header-text (:name block) (:attr block))
          _ (check-group-fits! "a `:::` fence header" header line-terminators "a line terminator")
          [body footnotes'] (render-blocks ctx (:blocks block) footnotes)
          ;; TASK-173: the body's last chunk ends at the closing fence, so
          ;; a definition for a marker in it is written before that fence.
          [defs footnotes'] (if (:chunk-level? ctx)
                              (flush-footnote-definitions footnotes')
                              [nil footnotes'])
          body (cond-> body defs (join-blocks defs))]
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
  one), with every literal `|` backslash-escaped except one inside an
  attribute group (TASK-221: the parser splits a row group-aware, and
  reads `\\|` there as a backslash; `attr-group` marks it) or a code span
  (TASK-225: likewise code-span-aware; the `:code` renderer marks it) -- the one character
  this namespace escapes OUTSIDE `always-escaped-chars`, since it is only
  hazardous inside a table row and nowhere else `escape-text` runs
  (confirmed at a REPL: `\\|` inside a pipe-table cell round-trips to a
  literal `|`). Throws `ex-info` (`:type ::unrepresentable-table-cell`)
  naming `cell` if the rendered text contains a literal newline (a
  `:soft-break`/`:line-break` Inline): a pipe-table row is inherently one
  physical line, and no `.hdoc` spelling keeps a cell's content on more
  than one. The text it reports is `without-marks` (TASK-182)."
  [ctx cell footnotes]
  (check-simple-table-cell! cell)
  (let [[text footnotes'] (render-inlines (assoc ctx ::in-table-cell true)
                                          (:inlines (first (:blocks cell))) footnotes)]
    (when (str/includes? text "\n")
      (throw (ex-info
              (str "hdoc emitter cannot represent a table cell spanning more than one physical line: "
                   (pr-str (without-marks text)))
              {:type ::unrepresentable-table-cell :cell cell})))
    ;; TASK-221: a `|` an attribute group marked (`attr-group`) stays bare,
    ;; since the parser reads a row group-aware; every other one is escaped.
    [(-> text
         (str/replace #"(?<!\u0000)\|" "\\\\|")
         (str/replace (str escape-mark "|") "|"))
     footnotes']))

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

(defn- table-caption-attr
  "Table `block`'s own `:attr` with `table-widths-prop` folded into its
  `widths` prop, as its caption line's attribute group writes it."
  [{:keys [colspec attr]}]
  (let [widths (table-widths-prop colspec)]
    (cond-> attr widths (assoc-in [:props "widths"] widths))))

(defn- table-caption-line?
  "True unless Table `block` certainly renders with NO trailing caption
  line (`render-table-caption-line`): it has no caption Inlines and an
  empty attribute group. A caption whose Inlines render blank counts as
  one -- the safe side for `close-open-paragraph` (TASK-201), which only
  writes a Block right after a table that has none."
  [block]
  (boolean (or (seq (:caption block)) (not (str/blank? (attr-group (table-caption-attr block)))))))

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
  (let [group (attr-group (table-caption-attr block))
        [caption-text footnotes'] (render-inlines ctx (:caption block) footnotes)]
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
  docstring (the LISTS section) explains why exactly these five and no
  others: `:section`, `:block-quote`, a nested `:list`, a fenced
  `:code-block` and a `:thematic-break` (spelled `***` there, TASK-181,
  see `tight-continuation-ctx`)."
  #{:section :block-quote :list :code-block :thematic-break})

(defn- tight-continuation-ctx
  "`ctx` for rendering `block` as a LATER Block of a tight list item (or
  of a `:section`'s Blocks in one), on the line directly after the Block
  before it (TASK-181). A `:thematic-break` there is spelled `***` rather
  than `---` (`::break-after-line`): `---` under a paragraph's text is
  its setext-heading underline, while `***` interrupts a Paragraph and
  ends a block quote's or list's lazy continuation alike (confirmed at a
  REPL after a `:para`, a `:block-quote`, nested and `*`-spelled `:list`s,
  a `:math-block` and in a section). The key is set for that one Block
  only, so nothing it contains, and no break anywhere else, changes."
  [ctx block]
  (cond-> ctx (= :thematic-break (:t block)) (assoc ::break-after-line true)))

(def ^:private thematic-break-line-re
  "CommonMark's thematic break: up to three spaces of indentation, then
  three or more of ONE of `-`, `*`, `_`, with any spaces or tabs between
  and after them (TASK-179)."
  #"^ {0,3}([-*_])[ \t]*(?:\1[ \t]*){2,}$")

(defn- thematic-break-line?
  "True when the physical line `line` re-parses as a thematic break."
  [line]
  (boolean (re-matches thematic-break-line-re line)))

(defn- first-line
  "`s` up to (not including) its first newline."
  [s]
  (first (str/split s #"\n" 2)))

(def ^:private open-paragraph-types
  "Block types `haselnuss.parser`'s flexmark reads as a Paragraph while it
  is being parsed (a `:math-block`/`:table`/`:figure` is one recognized
  only once the Paragraph is complete, see this namespace's own
  docstring): the next line, if it is not one that starts a new Block on
  sight, continues it -- lazily, through any block quote or list item it
  ends -- even with no container prefix of its own (TASK-185)."
  #{:para :math-block :table :figure})

(def ^:private open-paragraph-noun
  "How a refusal names a Block of one of `open-paragraph-types`."
  {:para "paragraph" :math-block "display math block" :table "table" :figure "figure"})

(defn- tail-chain
  "`block` followed by its last Block, that Block's last Block, and so on
  down to a leaf: a `:section`'s or `:block-quote`'s last Block, a
  `:list`'s last item's last Block. A container with nothing in it (an
  empty quote, a heading with no Blocks, an empty last item) ends the
  chain. Whatever `block` renders as ends with the leaf's own last line."
  [block]
  (let [last-child (case (:t block)
                     (:section :block-quote) (peek (vec (:blocks block)))
                     :list (peek (vec (peek (vec (:items block)))))
                     nil)]
    (cons block (when last-child (lazy-seq (tail-chain last-child))))))

(def ^:private closed-leaf-types
  "Block types that, as the leaf of a Block's `tail-chain`, end on a line
  nothing can lazily continue, so a `:para` may follow on the very next
  line and still start a Paragraph of its own (TASK-186) -- so may a
  `:math-block`, `:table` or `:figure`, each read back from a Paragraph of
  its own (TASK-193, confirmed at a REPL after every leaf here, also with
  TASK-195's indented TeX and a math attribute group): a fenced
  `:code-block` ends at its closing fence, a `:thematic-break` is its own
  line, and a `:section` or `:block-quote` is a leaf only when it has no
  Blocks -- a bare heading line (ATX, or a setext heading's underline) or
  a bare `>` line, neither of which leaves a Paragraph open. Confirmed at a
  REPL, directly in a tight item, in a section in one, and at the end of a
  block quote or nested list item. A `:list` leaf (an empty last item) is
  in neither this nor `open-paragraph-types`, so a `:para` after one is
  refused: its bare marker under a paragraph is already a setext
  underline, and nothing here pins what follows one."
  #{:code-block :thematic-break :section :block-quote})

(defn- ends-in-open-block-quote?
  "True when the line after `prev`'s last line, at `prev`'s own indent,
  would still be inside a block quote `prev` ends with (TASK-187): `prev`
  is a `:block-quote`, or a `:section` whose `tail-chain` reaches one
  through `:section`s only (a heading adds no container). A `>` line there
  CONTINUES that quote -- `- > a` / `  > b` and `- # h` / `  > a` / `  > b`
  each re-parse as ONE quote, and `- > > a` / `  > b` reads `b` into the
  inner quote's paragraph. A nested `:list` in the chain is not open
  there: the `>` line, indented less than that list's items, starts a new
  quote in the outer item (`- - > a` / `  > b` is two quotes, tight;
  confirmed at a REPL, also through a section in the nested item). Since
  TASK-194 a quote directly after a `:section` never gets here: it would
  re-parse inside the section whatever the section ends in, so
  `check-blocks-after-sections!` refuses it first."
  [prev]
  (= :block-quote (:t (first (drop-while #(= :section (:t %)) (tail-chain prev))))))

(defn- check-tight-list-item-continuation!
  "Throws `ex-info` (`:type ::unrepresentable-tight-list-item`) unless
  `block`, at index `idx` (> 0) of a tight list item's Blocks or of a
  `:section`'s Blocks inside one, can be written on the line after
  `prev`, the Block before it, with no blank line: any Block in
  `list-item-safe-continuation-types` (this namespace's own docstring says
  why), or a `:para` (TASK-186) -- or a `:math-block`, `:table` or
  `:figure` (TASK-193), each likewise one of `open-paragraph-types` --
  after a Block whose `tail-chain` ends in one of `closed-leaf-types` or
  `open-paragraph-types`. After the latter its first line would lazily
  continue the open Paragraph, so `close-open-paragraph` either closes it
  with a `>` line, writes it after a table its first line ends (TASK-201),
  or refuses -- a bare `:para`, a `:math-block`/`:figure`, or a list
  ending in one has no such line.

  A `:block-quote` is refused after a Block that `ends-in-open-block-quote?`
  (TASK-187): its `>` lines would continue that quote, merging the two,
  and nothing ends a quote without a blank line -- a bare `>` line is a
  blank line INSIDE it, and a lazy line would continue its paragraph (an
  empty Paragraph to stand between them does not exist) -- so only a
  blank line, which makes the list loose, keeps them apart. `level` is
  that `:section`'s `:level` when `idx` counts its Blocks rather than the
  item's, carried in the message and as `:section-level` in the ex-data."
  ([idx prev block] (check-tight-list-item-continuation! idx prev block nil))
  ([idx prev block level]
   (let [where (str "hdoc emitter cannot represent a tight list item whose block at index " idx
                    (when level (str " of a level-" level " section in it")))
         data (cond-> {:type ::unrepresentable-tight-list-item :t (:t block) :index idx}
                level (assoc :section-level level))]
     (when-not (or (contains? list-item-safe-continuation-types (:t block))
                   (and (contains? open-paragraph-types (:t block))
                        (let [leaf (:t (last (tail-chain prev)))]
                          (or (contains? closed-leaf-types leaf) (contains? open-paragraph-types leaf)))))
       (throw (ex-info (str where " is " (pr-str (:t block)) " with no blank line before it, after a "
                            (pr-str (:t prev)))
                       data)))
     (when (and (= :block-quote (:t block)) (ends-in-open-block-quote? prev))
       (throw (ex-info (str where " is a block quote directly after a " (pr-str (:t prev))
                            (when (= :section (:t prev)) " ending in a block quote")
                            ": its `>` lines would continue that quote, and only a blank "
                            "line, which makes the list loose, ends it")
                       (assoc data :after (:t prev))))))))

(defn- last-item-marker-width
  "The width of the marker `render-list` writes before List `block`'s last
  item: 2 for any bullet (`- `, `* ` or `+ `, TASK-177/179), the item's
  number plus `. ` or `) ` for an ordered list. Every later line of that
  item is indented by exactly this much."
  [block]
  (if (:ordered block)
    (+ 2 (count (str (+ (or (:start block) 1) (dec (count (:items block)))))))
    2))

(def ^:private unescaped-pipe-re
  "Finds a `|` that no backslash escapes: one preceded by an even run
  (possibly empty) of backslashes, each pair a literal backslash."
  #"(?:^|[^\\])(?:\\\\)*\|")

(defn- table-continuing-line?
  "True when physical line `line`, written directly under a pipe table
  that ends a Paragraph-in-progress (TASK-201), would NOT end that table:
  it holds an unescaped `|` (`unescaped-pipe-re` -- `z | y`, also
  TASK-171's `\\|---|`, is read as one more row; `z \\| y` is not, nor is
  a `|` in TeX on a LATER line, since only this first line decides), it
  is a setext underline (`hazard-line-re`: `---`/`===` makes the table's
  lines heading text), a delimiter row (`table-delimiter-hazard-re`,
  already ruled out by the `|` it needs), or a caption line (`:` first,
  `haselnuss.parser`'s `table-caption-re`, which would attach to the
  table). A line wholly in `[...]` (`[@k]`, `[^1]`, `[@a; @b]`) is safe:
  TASK-201 refused it, because flexmark's MultiMarkdown caption line
  turned it into a `TableCaption` the parser dropped, but `cm-parser` has
  `WITH_CAPTION` off since TASK-208 and reads it as an ordinary
  paragraph. Confirmed at a REPL. Conservative: a `|` inside a figure's
  caption or a code span is refused, though flexmark does not read either
  as a cell separator."
  [line]
  (boolean (or (re-find unescaped-pipe-re line)
               (re-matches hazard-line-re line)
               (re-matches table-delimiter-hazard-re line)
               (re-find #"^[ \t]*:" line))))

(defn- close-open-paragraph
  "`block-text`, the rendering of `block` at index `idx` (> 0) of a TIGHT
  list item's Blocks (or, `level` given, of a `:section`'s Blocks in one),
  made safe to write on the line after `prev`, the Block before it
  (TASK-185, TASK-186).

  Only a setext heading and a Block of `open-paragraph-types` (a `:para`,
  or, TASK-193, a `:math-block`/`:table`/`:figure`, each read back from a
  Paragraph of its own) are at risk: their first lines are
  not ones that start a Block on sight, so after a Block ending in an
  open Paragraph (`tail-chain`, `open-paragraph-types`) they lazily
  continue that Paragraph -- `- > q` / `  a` / `  ---` reads `a` into the
  quote, or after a bare `:para` takes the paragraph in as heading text;
  `- > q` / `  p` reads `p` into the quote the same way. An ATX heading
  line interrupts a Paragraph and a block quote's or list's lazy
  continuation alike (confirmed at a REPL), so it needs nothing; a
  rendered `:section` starts with `#` exactly when its heading is ATX
  (setext text has any leading `#` escaped). After any other leaf
  (`closed-leaf-types`) nothing is open, so neither needs anything either.

  The one spelling that ends the Paragraph and keeps the list tight is a
  blank line INSIDE a block quote: when `prev`'s tail runs through
  sections (which add no container) to a `:block-quote`, a bare `>` line
  closes everything open inside it -- `- > q` / `  >` / `  a` / `  ---`,
  exactly what a source with that shape is parsed from. flexmark makes
  the list loose instead when that quote's own last Block (sections
  aside) is a `:list` (confirmed at a REPL: `- > - x` / `  >` / `  a`),
  and nothing ends a Paragraph outside a quote without a blank line, so
  every other open tail throws `ex-info` (`:type
  ::unrepresentable-tight-list-item`) naming the Block's index and
  `prev`'s and the leaf's types.

  The tail may also reach that quote through the LAST items of nested
  `:list`s: the `>` line then sits at the quote's own indent, the sum of
  those items' marker widths (`last-item-marker-width`) -- `- - > q` /
  `    >` / `  a` / `  ---`, again the source's own spelling, which keeps
  every list tight (confirmed at a REPL). A quote reached the other way
  round, a list inside it, is the loose case above.

  A `:table` leaf (TASK-201) is open too, but a line that cannot continue
  it ENDS it: the rest of its Paragraph reads back as a Block of its own,
  as at top level. So when the leaf is a table of the item's own (`prev`,
  or reached through `:section`s only -- through a nested `:list` the
  line would lazily continue that list's last item), with no caption line
  (`table-caption-line?`: a caption is a paragraph line the next one
  continues), `block-text` is written as is if its first line is not
  `table-continuing-line?`. A setext heading stays refused whatever its
  first line: its underline makes the table's lines heading text.
  Confirmed at a REPL directly in a tight item, after a closed Block,
  and in a section in one."
  [idx prev block block-text level]
  (let [chain (tail-chain prev)
        leaf (last chain)
        setext? (and (= :section (:t block)) (not (str/starts-with? block-text "#")))]
    (if-not (and (or setext? (contains? open-paragraph-types (:t block)))
                 (contains? open-paragraph-types (:t leaf)))
      block-text
      (let [[lead [outer & inside]] (split-with #(#{:section :list} (:t %)) chain)
            indent (transduce (comp (filter #(= :list (:t %))) (map last-item-marker-width)) + lead)
            ;; TASK-201: the table is this item's own (sections add no
            ;; container); through a nested list the line would lazily
            ;; continue that list's item instead.
            own-table? (and (= :table (:t outer)) (not-any? #(= :list (:t %)) lead))]
        (cond
          (and (= :block-quote (:t outer))
               (not= :list (:t (first (drop-while #(= :section (:t %)) inside)))))
          (str (apply str (repeat indent \space)) ">\n" block-text)

          (and own-table? (not setext?) (not (table-caption-line? outer))
               (not (table-continuing-line? (first-line block-text))))
          block-text

          :else
          (throw (ex-info
                  (str "hdoc emitter cannot represent a tight list item whose block at index " idx
                       (when level (str " of a level-" level " section in it"))
                       " is a " (if setext? "multi-line (setext) heading" (open-paragraph-noun (:t block)))
                       " directly after a " (pr-str (:t prev))
                       (when-not (identical? prev leaf) (str " ending in a " (pr-str (:t leaf))))
                       ": the " (if setext? "heading's text" (open-paragraph-noun (:t block)))
                       (if own-table?
                         (str " would continue that table (a line holding an unescaped `|`, a setext "
                              "underline, a line wholly in `[...]`, which flexmark reads as its caption, "
                              "or any line after a caption line), and only a blank line, "
                              "which makes the list loose, ends it")
                         (str " would continue that paragraph, and only a blank "
                              "line, which makes the list loose, ends it")))
                  (cond-> {:type ::unrepresentable-tight-list-item :t (:t block) :index idx
                           :after (:t prev) :leaf (:t leaf)}
                    level (assoc :section-level level)))))))))

(defn- render-tight-section-body
  "Renders the `:blocks` of `section` inside a TIGHT list item to
  `[text footnotes']`, each on the line after the one before with no
  blank line between (TASK-180; `render-section` puts the first directly
  under the heading, unindented -- the enclosing item indents them all).
  A heading ends at its own line (ATX) or underline (setext), so the
  FIRST Block may be of any type -- confirmed at a REPL for a `:para`,
  `:thematic-break`, `:math-block`, `:table`, `:code-block`,
  `:block-quote`, `:list` and `:section` -- while every later one is
  `check-tight-list-item-continuation!`ed exactly as a later Block of the
  item itself is. Adjacent same-kind lists alternate their markers as in
  `render-blocks` (TASK-177)."
  [ctx section footnotes]
  (let [blocks (:blocks section)
        alternates (alternate-list-markers blocks)]
    (concat-rendered-kv
     (fn [idx block footnotes]
       (when (pos? idx)
         (check-tight-list-item-continuation! idx (nth blocks (dec idx)) block (:level section)))
       (let [[block-text footnotes'] (render-block (cond-> (list-marker-ctx ctx (alternates idx))
                                                     (pos? idx) (tight-continuation-ctx block))
                                                   block footnotes)]
         [(if (zero? idx)
            block-text
            (str "\n" (close-open-paragraph idx (nth blocks (dec idx)) block block-text
                                            (:level section))))
          footnotes']))
     footnotes
     blocks)))

(defn- render-list-item-blocks
  "Renders one list item's own `blocks` (a vector, sec4.3's `:items`
  entry) to `[text footnotes']`. `marker` is that item's marker text
  (`\"- \"`/`\"1. \"`/...); only its WIDTH is used -- a bullet list may be
  respelled `+ ` afterwards (TASK-179), at the same width -- the indent every
  Block after the first needs (`indent-lines`), and every physical line
  AFTER the first Block's own first line needs too
  (`indent-continuation-lines`, TASK-97 -- see its own docstring for why:
  a loose nested `:list` as the item's first Block has its OWN internal
  blank line, which otherwise ends the item's continuation early the same
  way a `:note`'s own first Block can) -- only that very first physical
  line, directly after the marker, needs none (lazy continuation covers
  it). `tight?` decides how a later Block is attached: LOOSE always
  separates with a blank line (safe for any Block type, this namespace's
  own docstring); TIGHT has no blank line and so only allows what
  `check-tight-list-item-continuation!` does -- a Block type in
  `list-item-safe-continuation-types`, or a `:para` (or `:math-block`/
  `:table`/`:figure`, TASK-193) after a Block that ends closed or can be
  closed by `close-open-paragraph` (TASK-186) --
  throwing `ex-info` (`:type
  ::unrepresentable-tight-list-item`) naming the offending type otherwise
  -- see this namespace's own docstring for why. `check-representable-
  nested-block!` runs over every Block in `blocks` up front (a
  `:directive`/`:include` has no spelling in EITHER position)."
  [ctx marker tight? blocks footnotes]
  (run! (partial check-representable-nested-block! "a list item") blocks)
  (check-blocks-after-sections! "a list item" blocks)
  (let [width (count marker)
        cr? (not (::no-cr-indent ctx))
        alternates (alternate-list-markers blocks)
        ;; TASK-180: a :section's own Blocks are this item's direct children too.
        ctx (cond-> ctx tight? (assoc ::tight-list-item true))]
    (concat-rendered-kv
     (fn [idx block footnotes]
       (when (and tight? (pos? idx))
         (check-tight-list-item-continuation! idx (nth blocks (dec idx)) block))
       (let [[block-text footnotes'] (render-block (cond-> (list-marker-ctx ctx (alternates idx))
                                                     (and tight? (pos? idx)) (tight-continuation-ctx block))
                                                   block footnotes)]
         (cond
           (zero? idx) [(indent-continuation-lines width block-text cr?) footnotes']
           (not tight?) [(str "\n\n" (indent-lines width block-text cr?)) footnotes']
           :else [(str "\n" (indent-lines width (close-open-paragraph
                                                 idx (nth blocks (dec idx)) block block-text nil)
                                          cr?))
                  footnotes'])))
     footnotes
     blocks)))

(defn- notes-added
  "The entries of footnote accumulator `footnotes'` past the first `(count
  footnotes)` -- the notes reserved since `footnotes`. A `subvec` of a
  vector (TASK-261: `drop` walked every earlier footnote, once per
  one-item loose list), `drop` of anything else."
  [footnotes footnotes']
  (if (and (vector? footnotes') (<= (count footnotes) (count footnotes')))
    (subvec footnotes' (count footnotes))
    (drop (count footnotes) footnotes')))

(defn- render-list
  "Renders List `block` (sec4.3: `:ordered`/`:tight`/`:items`) as a bullet
  (`\"- \"`) or ordered (`\"N. \"`, numbered from `:start`, default 1,
  see this namespace's own docstring) list, its items joined by a
  blank line when `:tight` is false or a bare newline when true (the
  SAME boolean that governs whether an item's own later Blocks, see
  `render-list-item-blocks`, get a blank line or not -- both follow from
  the identical CommonMark tightness rule). An as-parsed `:list` never
  carries a populated `:attr` -- `haselnuss.parser` has no attribute-group
  syntax that attaches to a whole list at all (see this namespace's own
  docstring) -- so `:attr` is not rendered here, the same convention
  `render-block-quote` follows for its own identical situation.

  Under `::alternate-list-marker` (`alternate-list-markers`, TASK-177)
  it spells bullets `* ` and ordered items `N) ` instead, so it stays a
  list of its own after a sibling list of the same kind.

  A bullet list any of whose items would START on a `thematic-break-line?`
  -- a one-item list nested down to an empty item (`- - - `), or an item
  whose first Block is a `:thematic-break` (`- ---`) -- is spelled `+ `
  instead (TASK-179): CommonMark reads such a line as a thematic break,
  not as list items, so the list would vanish. `+` is never a break
  character and has the same width, so every item's text is unchanged;
  checked at every nesting level, the level that would complete the break
  takes `+` and every level outside it then starts with a different
  character. The previous and next sibling lists of the same kind are
  never `+` (a `*` list never forms the break: its first item's nested
  lists are `-`, and a `:thematic-break` that starts an item is spelled
  `---` -- only a LATER Block of a tight item is `***`, TASK-181, never
  on a marker's line), so they stay apart."
  [ctx block footnotes]
  (let [{:keys [ordered tight items start]} block
        first-n (or start 1)
        ;; TASK-177: the next list's spelling when this one directly follows
        ;; a list of its own kind; item content starts afresh.
        alternate? (::alternate-list-marker ctx)
        ctx (dissoc ctx ::alternate-list-marker)
        bullet (if alternate? "* " "- ")
        marker-for (fn [idx]
                     (cond
                       (not ordered) bullet
                       alternate? (str (+ first-n idx) ") ")
                       :else (str (+ first-n idx) ". ")))
        [rendered footnotes'] (reduce-kv
                               (fn [[rendered footnotes] idx item-blocks]
                                 (let [marker (marker-for idx)
                                       [item-text footnotes'] (render-list-item-blocks
                                                               ctx marker tight item-blocks footnotes)]
                                   [(conj rendered [marker item-text]) footnotes']))
                               [[] footnotes]
                               (vec items))
        ;; TASK-234: a loose list of one item holding one paragraph has no blank
        ;; line to be loose by; the parser makes it so only when a footnote
        ;; definition sits in the item after a blank line. So the definitions of
        ;; the notes in that paragraph are written there, not at the chunk's end.
        [rendered footnotes'] (if (and (not tight) (= 1 (count items)) (= 1 (count (first items)))
                                       ;; TASK-235: or one nested list, whose notes the outer
                                       ;; item defines the same way.
                                       (#{:para :list} (:t (ffirst items)))
                                       (seq (remove :written? (notes-added footnotes footnotes'))))
                                (let [[marker item-text] (first rendered)
                                      mine (remove :written? (notes-added footnotes footnotes'))
                                      defs (str/join "\n\n" (map (fn [{:keys [number text]}] (str "[^" number "]: " text)) mine))
                                      width (count marker)]
                                  [[[marker (str item-text "\n\n" (indent-lines width defs (not (::no-cr-indent ctx))))]]
                                   (into (subvec footnotes' 0 (count footnotes))
                                         (map #(assoc % :written? true)) (notes-added footnotes footnotes'))])
                                [rendered footnotes'])
        ;; TASK-179: a bullet item line that reads as a thematic break makes the
        ;; whole list `+ ` (same width, so the item text stands as rendered).
        rendered (if (and (not ordered)
                          (some (fn [[marker item-text]]
                                  (thematic-break-line? (first-line (str marker item-text))))
                                rendered))
                   (mapv (fn [[_ item-text]] ["+ " item-text]) rendered)
                   rendered)]
    [(str/join (if tight "\n" "\n\n") (map (fn [[marker item-text]] (str marker item-text)) rendered))
     footnotes']))

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
   ;; -- see this namespace's own docstring. A later Block of a tight list
   ;; item is `***` instead (`tight-continuation-ctx`, TASK-181).
   :thematic-break (fn [ctx _block footnotes] [(if (::break-after-line ctx) "***" "---") footnotes])
   :block-quote render-block-quote
   :list render-list
   :code-block render-code-block
   :math-block render-math-block
   :figure render-figure
   :table render-table
   :directive render-directive
   :include render-include
   :book-quote render-book-quote})

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
  ;; A Block's content starts a physical line wherever its parent Inline
  ;; sat (a footnote body, say), so TASK-135's line-start flag resets here,
  ;; and it is its own inline source, so TASK-161's `:in-inline?` does too.
  ((get (:block-handlers ctx) (:t block) unsupported-block!)
   (cond-> (-> ctx
               (assoc :line-start? true)
               (dissoc :in-inline?)
               ;; TASK-192: a footnote's Blocks are not the link text its
               ;; marker sits in (nor, TASK-198, its `@`s bracket content,
               ;; nor, TASK-199, citation text).
               (dissoc ::in-link-text ::in-table-cell ::at-marks ::in-cite-affix ::emphasis-mode)
               ;; TASK-195: whether this Block is written at column 0 of a
               ;; chunk sequence or inside a container's prefix.
               (assoc ::contained? (not (:chunk-level? ctx))))
     ;; TASK-173: only a section or a directive body is still a sequence
     ;; of parser chunks; everything else nests its Blocks in a container
     ;; the raw line scan never splits.
     (not (chunk-level-containers (:t block))) (dissoc :chunk-level?)
     ;; TASK-180: a tight item's direct children run on through its sections
     ;; only; any other container's Blocks are no longer that item's.
     (not= :section (:t block)) (dissoc ::tight-list-item))
   block footnotes))

(defn- render-blocks
  "`render-block` folded over every Block in `blocks`, in order,
  joining each rendered Block with a blank line (CommonMark's own
  block separator) and threading `footnotes` throughout.

  At chunk level (`chunk-boundary?`, TASK-173), every footnote definition
  still pending is written just BEFORE a Block that starts a new parser
  chunk, so it stays in the chunk that holds its marker.

  A `:list` directly after a sibling list of its own kind is written with
  the other marker (`alternate-list-markers`, TASK-177)."
  [ctx blocks footnotes]
  (let [join! (blank-line-joiner)
        footnotes' (reduce (fn [footnotes [block alternate?]]
                             (let [[defs footnotes] (if (chunk-boundary? ctx block)
                                                      (flush-footnote-definitions footnotes)
                                                      [nil footnotes])
                                   _ (when defs (join! defs))
                                   [block-text footnotes'] (render-block (list-marker-ctx ctx alternate?)
                                                                         block footnotes)]
                               (join! block-text)
                               footnotes'))
                           footnotes
                           (map vector blocks (alternate-list-markers blocks)))]
    [(join!) footnotes']))

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
  accumulated (`render-note`) that has not already been written at the
  end of an earlier chunk (`flush-footnote-definitions`), one per
  blank-line-separated block, or nil when there are none -- `emit-document`
  appends this after the whole body, mirroring `haselnuss.emit.html`'s
  own end-of-document footnote list."
  [footnotes]
  (when-let [defs (first (flush-footnote-definitions footnotes))]
    (str "\n\n" defs)))

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

  `opts` also takes `:kinds`/`:cite-keys`, the same vocabularies
  `haselnuss.parser/parse` does (TASK-135): an `@word` in literal text is
  backslash-escaped only if it would re-parse as a reference under them,
  so pass the ones the document was parsed with. Absent, the parser's
  shape heuristics decide, which escapes more.

  The document's own front matter (`render-front-matter`) comes first,
  then the rendered body (`render-blocks`), then every deferred footnote
  definition not already written at the end of an earlier parser chunk
  (`render-footnote-definitions`; TASK-173, see \"Footnote definitions per
  parser chunk\").

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
              :inline-handlers (merge built-in-inline-handlers (:inline-handlers opts))
              :kinds (:kinds opts)
              :cite-keys (:cite-keys opts)
              ;; TASK-173: the document's own Blocks are a chunk sequence.
              :chunk-level? true}
         _ (check-blocks-after-sections! "the document" (:blocks document))
         front-matter (render-front-matter (:meta document))
         [body footnotes] (render-blocks ctx (:blocks document) [])
         guard-needed? (and (nil? front-matter)
                            (= :thematic-break (:t (first (:blocks document)))))
         front-matter (or front-matter (when guard-needed? "---\n---\n\n"))
         footnote-text (render-footnote-definitions footnotes)]
     ;; Every mark is resolved but a `code-break`'s.
     (str/replace (str front-matter body (or footnote-text "") "\n") code-break "\n"))))

;; --- Lossy role-span diagnostics (AC #7) -------------------------------------

(def ^:private lossy-role-span-types
  "The four Inline variants `render-role-span` degrades to a `:span` on
  re-parse (this namespace's own docstring, AC #7)."
  #{:small-caps :sub :sup})

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

    ;; Likewise a `:strike` (TASK-137): exact unless nested in another.
    (and (= :strike (:t node))
         (some #(= :strike (:t %)) (rest (tree-seq coll? seq node))))
    (conj acc {:type ::lossy-role-span :t :strike :path path})

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

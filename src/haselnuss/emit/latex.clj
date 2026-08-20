(ns haselnuss.emit.latex
  "The LaTeX emitter core (TASK-21, extended by TASK-22): a visitor over a
  resolved-and-lowered `haselnuss.ast/Document`, producing one complete,
  compilable `.tex` document string (`emit-document`) -- a
  `\\documentclass` plus a minimal preamble, then the rendered body.

  Or, under `emit-document`'s own `:fragment` opt (TASK-52), *only* that
  body: no class, no preamble, no `\\begin{document}`, for `\\input`-ing
  into a host document whose layout someone else owns. A fragment cannot
  load a package for itself, so the ones its body needs are reported
  separately by `emit-preamble` -- see both functions' own docstrings for
  that decision, and for what a citation renders as inside a fragment.

  Front-matter blocks -- an abstract and its keywords (TASK-54) -- are
  lifted out of the body by `haselnuss.extensions.front-matter/extract`
  before anything is rendered. A standalone document places each in
  LaTeX's own `abstract` environment after the title block; a fragment
  emits none of them inline and hands them to its caller through
  `emit-front-matter`, one `\\input`-able side file each. See both
  functions for that decision.

  `meta.topLevelDivision: chapter` (TASK-53) makes a level-1 Section a
  `\\chapter` and shifts every deeper level down with it
  (`chapter-section-commands`), and a standalone document is emitted into
  `report` rather than `article`, which has no `\\chapter` at all (see
  `document-class` for why `report` and not `book`). The numbering side
  of that opt-in lives in `haselnuss.resolver/scope-path`: a chaptered
  class resets the figure/table/equation counters per chapter and prints
  `\\thechapter.\\arabic{figure}`, and the resolver composes the same
  way, so a native-mode `\\Cref` and an HTML reference name the same
  number. A document that does not opt in is emitted exactly as before.

  Scope, confirmed against the sibling backlog tasks that split this
  emitter's work (TASK-22/23/24) and against `haselnuss.emit.html`'s own
  precedent for the equivalent HTML-core/HTML-extended split (TASK-19/20):
  Section, Para, List, CodeBlock, BlockQuote, the purely-textual/recursive
  Inline nodes (`:emph`/`:strong`/`:strike`/`:small-caps`/`:sub`/`:sup`/
  `:code`), Link, Image, and Span (TASK-21); Figure, Table, MathInline/
  MathBlock and footnotes (`:note`, TASK-22); resolved CrossRef and Cite,
  in both of the two emission modes described below (TASK-23). The
  directive-name -> LaTeX-environment mapping *table* itself is TASK-24's
  (this namespace only owns the generic registry-dispatch *mechanism* a
  directive with a native `:latex` renderer goes through, per
  `haselnuss.registry`'s own docstring naming \"TASK-19/21\" as the
  emitter cores responsible for calling a registered `:emit` fn -- see
  `render-directive`). `:thematic-break` renders as a centered `\\rule`
  (TASK-37 AC #2); an `:include` that reaches this emitter unexpanded
  has no target content to render, so it raises a dedicated
  `::unresolved-include` naming the real cause (TASK-37 AC #3),
  identically worded to `haselnuss.emit.html`'s own, so both targets
  fail the same way on the same document. Since TASK-38 that is a
  should-not-happen for anything converted through `haselnuss.cli`,
  which always runs `haselnuss.resolver/expand-includes`; what still
  reaches it are the routes that skip that pass
  (`haselnuss.json/json->ast`, or `resolve-document` with no
  `:includes` loader). Anything outside the schema
  entirely still raises a documented `ex-info` (`::unsupported-block`/
  `::unsupported-inline`) rather than being silently dropped or mangled,
  matching this codebase's established no-silent-drop convention.

  Two emission modes (TASK-23, sec10 decision D4), selected by
  `emit-document`'s own `:computed-numbers` opt. The resolver runs
  identically in both -- the modes differ only in whether this emitter
  *uses* the numbers/citation text the resolver already computed, or
  hands that job to LaTeX itself:

  - **Native mode** (default, `:computed-numbers` false -- decision D4's
    own default): a resolved CrossRef renders as a native `\\Cref{}`
    (or a bare `\\ref{}` when its own `:suppress-prefix` is set) built
    from the node's *original* `:label`, never from the resolver's
    already-computed `:text`; a Cite renders as native `natbib`
    commands over its own items' `:key`s; and the resolver-generated
    bibliography Section is replaced outright by `\\bibliographystyle`/
    `\\bibliography`, so BibTeX -- not this emitter -- builds the
    reference list from the same bibliography source the resolver read
    (AC #1/#2). LaTeX therefore computes every number and formats every
    citation, which is what makes a native-mode `.tex` file behave like
    hand-written LaTeX for a print workflow.

  - **Computed-numbers mode** (`:computed-numbers` true): every CrossRef
    renders the resolver's own already-computed `:text` as literal text
    (wrapped in a `\\hyperref[]{}` internal link when it resolved to a
    real target, so it is still clickable), every Cite renders the
    resolver's own already-formatted `:text` Inlines, and the generated
    bibliography Section renders as the ordinary Section it is (AC #3).
    Nothing depends on LaTeX's own counters or on BibTeX. This is the
    mode that guarantees exact numbering parity with `haselnuss.emit.
    html` for one source document emitted to both targets.

    Because that parity is the whole point of the mode (\"bakes in the
    resolver's already-computed numbers\", per this task's own
    description), computed-numbers mode also stops LaTeX numbering the
    *targets*, using the starred, uncounted variant of every construct
    that would otherwise carry a counter of its own: a Section renders
    `\\section*{}` carrying the resolver's own number baked into its
    heading text (TASK-41 -- starred so `article`'s counter stays out,
    and the number written in so a numbered heading still shows one,
    matching `haselnuss.emit.html`'s own
    `<span class=\"section-number\">`), a Figure/Table renders `\\caption*{}`
    (the `caption` package's unnumbered variant) carrying the resolver's
    own label text from `:labels`, and an id-bearing MathBlock carries
    an `\\tag{}` (`amsmath`) of the resolver's own number rather than
    `equation`'s own counter. Otherwise a `\\hyperref` reading
    \"Figure 2.3\" would point at a caption reading \"Figure 1\", and
    the two emitted targets would disagree with each other inside the
    same PDF. Because a starred construct steps no counter, hyperref has
    no anchor of its own to record there, so a computed-mode Section/
    Figure/Table `\\label` is preceded by `\\phantomsection` (see
    `unnumbered-label`). An id-bearing MathBlock whose id has no
    entry in `:labels` at all (an unrecognized kind prefix, so the
    resolver never numbered it) has no computed number to `\\tag` with,
    and renders as amsmath's unnumbered `equation*` rather than falling
    back to `equation`'s own counter -- otherwise LaTeX printed a number
    beside an equation `haselnuss.emit.html` printed none beside, which
    is the drift this mode exists to remove (TASK-27 review).

  A dangling reference or citation (AC #4) emits cleanly in both modes,
  never crashing this emitter: native mode emits `\\Cref`/`\\citep` of
  the unresolved label/key, which `pdflatex`/`natbib` render as their own
  `??`/`?` placeholder plus a non-fatal build warning (confirmed
  empirically -- the same placeholder convention `haselnuss.resolver`
  itself already uses); computed mode emits the resolver's own `\"??\"`
  text, unlinked (there is no target to link to).

  Two consequences of the `:native-bibliography` gating worth stating
  outright, since they narrow AC #1/#4's own wording (TASK-23 review
  follow-up): \"every Cite emits a native cite command\" holds in native
  mode *provided this document emits a native bibliography at all* --
  i.e. `emit-document` was given both `:bibliography-id` and a
  `:bib-resource` (see `render-cite`). And in a document whose citations
  *all* dangle, `haselnuss.resolver/resolve-citations` generates no
  bibliography Section at all, so `:bibliography-id` is nil and even
  native mode renders the resolver's own `[??]` text rather than
  `\\citep{nosuchkey}`. AC #4 still holds either way -- the document
  emits, compiles, and shows a visible placeholder -- but via the
  resolver's placeholder rather than natbib's.

  A narrowed gap (TASK-23 review finding #6, largely closed by TASK-24),
  recorded here rather than left silent: a node emits a `\\label` only if
  this namespace renders one for it -- Section, Figure, Table, an
  id-bearing MathBlock, and (TASK-24) a Directive with a mapped
  environment. A CrossRef the resolver resolved *successfully* against a
  target of any *other* node type still renders as LaTeX's own `??`
  (native) or an undefined hyper-reference (computed), indistinguishable
  in the output from a genuinely dangling reference. The realistic case
  -- the built-in `thm` kind, authored as a Directive -- is now covered:
  `render-environment` emits the label inside the mapped environment, so
  a `\\Cref{thm:main}` resolves to a real \"Theorem 1\". TASK-40
  extended that to every directive the mapping table covers: `proof`
  and `admonition` step counters of their own now, so a native-mode
  `\\Cref` to either resolves to a real number in the compiled PDF
  instead of `??`, and every one of them has a lexicon kind, so it can
  be referenced at all.

  Two cases still degrade to `??` in native mode, both deliberately, and
  both visibly rather than silently:

  - a List/CodeBlock `attr.id`. It *can* be a numbering target -- any id
    whose prefix is a recognized kind is -- but neither construct steps
    a LaTeX counter, so a native-mode `\\label` would bind to whatever
    was stepped last and print that as though it were the answer.
    `counterless-label` therefore anchors these in computed-numbers mode
    only (TASK-40), where the reference text comes from the resolver and
    `\\phantomsection` makes the anchor this node rather than its
    section.
  - an *unmapped* directive with no native renderer, which
    `haselnuss.lower/lower` replaces with its fallback content before
    this emitter ever sees it. Where that content keeps the directive's
    own `attr` -- which every built-in degradation does, since
    `haselnuss.cli/environment-lower-rule` and both collapsable
    extensions wrap it in a BlockQuote -- it lands in the case above and
    is anchored in computed mode, unanchored in native, for the same
    reason (TASK-51 corrected this entry: it used to say the id's
    survival was the fallback's own concern, which stopped being true
    once the fallback carried it onto a node this emitter renders). A
    `:blocks` fallback that drops the id genuinely does lose it, and
    that remains the fallback author's own concern (see
    `haselnuss.lower`'s note on that variant).

  Native mode says so rather than only doing it: `unanchored-reference-
  diagnostics` reports every reference that will print `??` for this
  reason, and `haselnuss.cli` prints them as build warnings.

  Span (a judgment call flagged via a task comment, not spelled out in
  TASK-21's own description the way TASK-19's HTML description spelled
  out \"...links, images, spans and footnotes\"): rendered as a plain
  pass-through of its own `:inlines` -- LaTeX has no generic CSS-class-
  equivalent hook, so `attr.classes`/`attr.props` are not translated to
  anything (a documented limitation, not a silent drop of the *node*
  itself, which is genuinely rendered). Figure/Table below share this same
  documented `:classes`/`:props` limitation.

  Footnotes (`:note`, AC #4): unlike `haselnuss.emit.html`, this namespace
  needs no marker/footnote-list accumulator at all, because LaTeX's own
  `\\footnote{...}` command is self-numbering and self-placing --
  `render-inline`'s `:note` case renders straight to `\\footnote{render-
  blocks of :blocks}`, confirmed empirically to compile cleanly even for
  a multi-paragraph footnote body (a blank line inside the macro argument
  is an ordinary `\\par`, allowed because the kernel's own `\\footnote` is
  defined `\\long`) and for a footnote nested inside another footnote or
  inside a list item.

  Math (AC #3): `:math-inline`'s raw `:tex` passes through completely
  verbatim (no escaping at all -- unlike `escape-tex`'d prose, this *is*
  already TeX) wrapped in `\\(...\\)` (core, no package). `:math-block`
  needs a numbered-vs-unnumbered choice this AC's wording alone does not
  settle, resolved by parity with Section/Figure/Table (and confirmed by
  `SPEC.md` sec6/6.1, which lists `MathBlock` alongside
  Section/Figure/Table as a `kind eq` cross-reference *target* carrying an
  `attr.id`, and sec10/decision D4, which makes native `\\label`/`\\ref`
  this target's own default rendering): an id-bearing MathBlock renders as
  a numbered, labelable `equation` environment (core LaTeX2e, no package)
  with `attr-label` immediately after its raw `:tex`, so a later TASK-23
  `\\ref`/`\\eqref` has a real target to point at; an id-less MathBlock
  renders as plain unnumbered display math (`\\[...\\]`, core) instead --
  mirroring how an id-less Figure/Table below never gets `attr-label`
  either. `:attr.classes`/`:attr.props` share Span's own documented
  limitation (no generic LaTeX target).

  A raw `%` inside `:tex` (review comment #4, this task's own fatal bug,
  same adjacency class as TASK-21's own label-escaping/verbatim-collision
  bugs): AC #3 forbids escaping `:tex` at all, so a literal, unescaped `%`
  in authored math content (entirely plausible, e.g. \"5% probability\"
  typed straight into a math span) is read by TeX as a comment that eats
  the rest of its own physical *source line* -- confirmed empirically
  fatal (\"Missing $ inserted\") for both `:math-inline`'s `\\(tex\\)` and
  an id-less `:math-block`'s `\\[tex\\]`, since both used to concatenate
  their closing delimiter onto that *same* line as the raw `:tex`, so a
  trailing `%` swallowed the emitter's own closing syntax along with it.
  The id-bearing `equation` path was never affected, because its template
  already puts `tex`/`\\label{...}`/`\\end{equation}` each on their own
  line. Fixed the same way here: the closing delimiter now always sits on
  its own line (`\\(tex\\n\\)` / `\\[tex\\n\\]`) -- a `%` on `:tex`'s own
  last line can still eat that line's own trailing newline, but the
  closing delimiter, now on the *next* line, is untouched. `:tex` itself
  is still never escaped (AC #3 holds); only the emitter's own
  surrounding structure changed.

  Figure (AC #1): `\\begin{figure}\\n{render-block of :content}\\n
  [\\caption{render-inlines of :caption}]\\nattr-label\\n\\end{figure}` --
  `:content` is rendered through the ordinary `render-block` visitor (sec4.3
  places no constraint on Figure content beyond \"a Block\"; a real Figure's
  content is most often a `:para` wrapping a single `:image`, per
  `haselnuss.parser`'s own standalone-figure-image conversion, but can in
  principle be any Block, e.g. a CodeBlock -- confirmed against this
  codebase's own `resolver_test.clj` fixtures), so a Figure wrapping a
  CodeBlock whose text collides with `verbatim`'s own terminator still gets
  `verbatim-env`'s existing collision handling for free. `\\caption` is
  omitted entirely when `:caption` is empty (mirroring `haselnuss.emit.
  html`'s own render-figure/render-caption precedent of omitting an empty
  `<figcaption>`), in which case a `\\refstepcounter{figure}` steps the
  counter the missing `\\caption` would have (see `render-figure`);
  `attr-label` (below) is always emitted -- `\"\"` when
  `:attr.id` is unset, so an id-less Figure still floats/numbers via LaTeX's
  own `figure` counter exactly as an id-less one would, just with nothing
  to `\\ref` later.

  Table (AC #2, using `longtable` -- review comment #1, this task's own
  fatal gap against its own description's literal \"tabular/longtable\"
  wording): `\\begin{longtable}{table-preamble}\\n[\\caption{...}
  attr-label \\\\]\\n\\hline\\n{head row}\\n\\hline\\n\\endfirsthead\\n
  \\hline\\n{head row}\\n\\hline\\n\\endhead\\n{body rows}\\n\\hline\\n
  \\end{longtable}` -- confirmed empirically that plain `tabular` inside a
  `table` float (this task's first, since-replaced implementation) has no
  native page-break support at all: a 119-row/2-col table compiled (exit
  0) but rendered only ~19 of its 119 rows inside the visible page area
  (`LaTeX Warning: Float too large for page by 892.19998pt`), silently
  losing the rest -- a materially worse failure mode than a compile error
  since it *looks* successful. `longtable` (a standard, always-available
  package, added to the fixed preamble) is exactly the core-LaTeX
  replacement for this: it is not a float at all, so it breaks across
  pages on its own, and it repeats the head row via `\\endfirsthead`/
  `\\endhead` (material before `\\endfirsthead` prints once, on the first
  page only; material before the following `\\endhead` repeats on every
  page after) -- both sections here render the same `head` Row via
  `render-row`, since no AC asks for a distinct \"(continued)\" variant.
  `\\caption`/`\\label` move out of `table`'s own float-caption convention
  entirely: `longtable` redefines `\\caption` to work standalone (still
  incrementing the shared `table` counter a later TASK-23 `\\ref` can
  point at, via the same `attr-label`/`label-id` helpers Section/Figure
  already use), typeset once as the environment's own first \"row\",
  terminated by `\\\\` like any other row, immediately before the
  `\\endfirsthead` head block -- confirmed empirically to compile and to
  place the caption above the table on its first page only, not repeated
  on every subsequent page. Column alignment/width (`:colspec`, sec4.3)
  become the `longtable` environment's own column-preamble tokens
  (`col-format`, unchanged by the `tabular`->`longtable` switch --
  `longtable`'s own preamble syntax is identical): plain `l`/`c`/`r` when
  a column has no `:width` (default `l` when it has no `:align` either,
  the same default a bare column needs structurally), or -- confirmed
  empirically needing `\\usepackage{array}` (still in the fixed preamble)
  -- `>{\\raggedright|\\centering|\\raggedleft\\arraybackslash}p{width}`
  when it does. `:width` is spliced in raw, not `escape-tex`'d: like Link
  `:target`/Image `:src`, it is documented as expecting a valid LaTeX
  dimension string (e.g. `\"3cm\"`), not arbitrary text or a CSS-style
  percentage. An empty/absent `:colspec` (structurally possible per
  sec4.3, e.g. a hand-authored Table with no header row at all) would
  otherwise splice an empty `{}` preamble -- confirmed empirically fatal
  once `array` is loaded (\"Package array Error: Empty preamble\") -- so
  `table-preamble` defensively falls back to a single default `{l}` column
  instead, a documented limitation for this pathological shape rather
  than a crash.

  A Cell's own `:align` (sec4.3) overrides its column's `colspec` entry,
  mirroring `haselnuss.emit.html`'s own `cell-align` precedent exactly (own
  align first, else the `colspec` entry at that column index, tracked via
  `render-row`'s own running `col-index` that advances by each cell's own
  `:span`, default 1, again mirroring `haselnuss.emit.html/render-row`).
  Because a plain `longtable` column's own alignment is fixed for the
  whole column, honoring a *per-cell* override -- or an actually-spanning
  cell, `:span` > 1, the only way to merge columns at all -- needs
  `\\multicolumn` (core, no package): `render-cell` emits a cell's own
  content bare (inheriting its column's own format, including any
  `:width`) only when it neither spans nor overrides alignment; a
  non-spanning override reuses `col-format` (with that same column's own
  `:width` preserved, only `:align` replaced) so overriding alignment
  alone never silently drops a column's width; an actually-spanning cell
  instead uses a plain `align-letter` (`l`/`c`/`r`, no `:width` at all) --
  correctly merging N columns' widths into one `p{}` needs real arithmetic
  over `array`'s own column-separation dimensions that no AC requires, so
  this is a documented, narrower limitation for that one case
  specifically, not a silent drop.

  A Cell's own content (review comment #2, this task's own fatal bug): a
  plain `tabular`/`longtable` cell is restricted horizontal mode, which a
  bare List/BlockQuote/MathBlock/nested-Table cannot even start in at all
  (confirmed empirically fatal, one distinct error per shape -- \"missing
  \\item\", a `quote` trivlist error, \"Not in outer par mode\", \"Missing
  $ inserted\" respectively), and in which a blank-line paragraph break
  between two Para blocks is inert, so `render-blocks`' own `\"\\n\\n\"`
  join silently collapses two Paras onto one run-on line instead of two
  visible paragraphs. `render-cell` (via `render-cell-content`) now wraps
  any Cell whose `:blocks` is not exactly one Para in `\\begin{minipage}
  [t]{width}...\\end{minipage}` -- `minipage`, not `\\parbox{width}{...}`,
  deliberately: `\\parbox`'s argument is read as an ordinary delimited
  macro argument, tokenized under the catcodes active at the point it is
  *read*, which is exactly why a CodeBlock's own `verbatim`-family
  environment (needing to change catcodes itself *before* its own body is
  ever tokenized) cannot survive inside one; `minipage` is a real
  environment scanned for its own `\\end{minipage}` boundary the normal
  way, so a CodeBlock nests inside it safely, and a blank line between two
  Paras is a real `\\par` again once inside `minipage`'s own vertical-mode
  body. `width` is the Cell's own column `:width` from `colspec` when
  present, else `default-cell-width` (see `cell-width`) -- an arbitrary
  but reasonable fixed dimension, since no AC specifies a numeric default
  and there is no reliable way to infer one from the table's own overall
  width without knowing the surrounding page geometry. A single-Para Cell
  is left completely unwrapped, unchanged from this task's original
  behavior, both to keep the common all-text-table case visually
  unconstrained by an arbitrary box width and to avoid perturbing every
  existing fixture's exact output.

  One shape a `minipage` wrapper genuinely cannot make safe, even in
  principle: a nested Table. `longtable`'s own documentation states
  plainly it must never be used inside a `minipage`/`\\parbox` at all (a
  fixed-size box has no page of its own to break across), so switching
  Table rendering to `longtable` above means a Cell whose own `:blocks`
  contain a nested Table, anywhere, cannot be represented in this
  implementation -- `has-nested-table?` walks a Cell's own Block tree
  (Section/List/BlockQuote/Figure's own nested Blocks) and `render-cell-
  content` raises a documented `ex-info` (`::unrepresentable-cell`) rather
  than emit LaTeX confirmed to fail, matching this namespace's established
  no-silent-mangle convention (`verbatim-env`'s own double-terminator
  case, `table-preamble`'s own empty-colspec fallback). No AC requires a
  nested Table inside a Cell to render at all; this is a disclosed,
  narrower limitation, not a silent drop.

  Directive dispatch: a `:directive` Block surviving `haselnuss.lower/
  lower` for the `:latex` target always carries a native `:latex`
  renderer in the registry (`lower`'s own AC #1 branch guarantees this --
  see its docstring). `render-directive` resolves it in two steps, in
  order: a real renderer *function* registered for the directive's own
  `:name` in an optional `:registry` passed via `emit-document`'s `opts`
  is called with the same `(fn [directive target] -> string)` signature
  `haselnuss.extensions.collapsable`/`haselnuss.emit.html` already
  established; otherwise the directive's name is looked up in the
  directive-name -> LaTeX-environment mapping table (TASK-24,
  `default-directive-environments`, replaceable via
  `emit-document`'s own `:directive-environments` opt), and its content
  is wrapped in that environment by `render-environment`. Neither is the
  same \"no representation\" failure as any other unsupported block --
  though in a full pipeline that branch is unreachable, since
  `lower` has already applied the fallback contract or aborted.

  That mapping table is *data*, not a per-directive branch in this
  namespace's visitor (TASK-24 AC #2): `render-block`/`render-directive`
  never name a directive, so adding a mapped environment is an entry in
  a map plus the matching `register-environments` call, with no edit to
  any rendering function -- an entry brings its own `\\usepackage` and
  environment declaration with it (see `preamble`). That registration is
  what makes `lower` keep a mapped directive at all; see
  `environment-renderer` for why the value stored there is a marker this
  emitter recognizes by identity rather than an ordinary renderer.

  (Today's one built-in *extension*, `collapsable`, registers no `:latex`
  renderer at all -- TASK-18 AC #2 -- so `lower` always resolves it to
  its `:blocks` fallback before this emitter sees it. The function-
  renderer path is exercised directly by this namespace's own tests via
  a hand-registered stub.)

  LaTeX command choices (AC #1/#3), each verified empirically against a
  real `pdflatex` in this codebase's own dev environment before being
  committed to here, not merely from memory of LaTeX semantics -- see
  this task's own recorded implementation plan for the verification
  matrix:
  - `escape-tex` (AC #3) handles ordinary authored text (`:str`, authored
    Meta strings, `:code`'s own text -- see below): every character
    special to TeX (`\\ { } $ & # % _ ^ ~`) is replaced in a single pass
    over the original characters (a per-character lookup, not a chain of
    sequential `clojure.string/replace` calls) -- a sequential-`replace`
    chain would re-scan and mangle a *previous* step's own replacement
    text (e.g. a naive \"escape `\\\\` first, then `{`/`}`\" chain
    corrupts `\\textbackslash{}` right back into
    `\\textbackslash\\{\\}`, since the `{`/`}` step has no way to tell
    the braces *it* just introduced apart from the original text's own),
    so this instead maps every original character to its own final
    replacement exactly once.
  - `:sub`/`:sup` -> `\\textsubscript{}`/`\\textsuperscript{}` -- both
    core LaTeX (the 2020 LaTeX kernel release folded `\\textsubscript` in
    alongside the long-standing `\\textsuperscript`), no extra package.
  - `:strike` -> `\\sout{}`, requiring `\\usepackage[normalem]{ulem}` --
    base LaTeX has no strikethrough command at all.
  - `:code` -> `\\texttt{escape-tex text}` -- ordinary text escaping is
    correct here (unlike a Link/Image target, below) since this is a
    normal macro argument, not hyperref's own specially-scanned URL
    argument.
  - `:code-block` -> a `verbatim`-family environment (core, no package):
    its content is copied catcode-literally up to a matching `\\end{...}`,
    so a CodeBlock's own `:text` needs zero *escaping* -- the simplest
    choice that still guarantees AC #1's \"standard LaTeX toolchain\"
    compiles with no extra required package, at the cost of no syntax
    highlighting (out of scope; no AC asks for it). Escaping is not the
    whole story, though: this task's review comment #3 found that `:text`
    containing the literal substring `\\end{verbatim}` closes the
    environment early and corrupts the rest of the document as stray
    LaTeX -- a realistic hazard for a documentation tool whose own code
    samples can show `.tex` source. `verbatim-env` picks `verbatim` or,
    when that substring is present, the LaTeX kernel's own differently-
    terminated `verbatim*` variant instead (see its own docstring).
  - `:link`/`:image` -> `\\href{target}{...}`/`\\includegraphics{src}`,
    requiring `hyperref`/`graphicx`. Their own `target`/`src` are passed
    through mostly **verbatim**, not `escape-tex`-ed: confirmed empirically
    that hyperref's `\\href` URL argument already passes a raw
    `% # & _ { }` through unescaped when the braces are balanced, but a
    raw backslash breaks it fatally, and running the *full* `escape-tex`
    text-escaping over a URL breaks it even worse (`TeX capacity
    exceeded` from feeding hyperref's own verbatim-like URL scanner a
    wall of macro calls it doesn't expect there) -- so both fields are
    documented as expecting a valid URI/filename with no raw backslash,
    the pragmatic reading matching RFC 3986 (backslash is not a legal
    URI character at all) and `graphicx`'s own confirmed tolerance for a
    raw `_` in a filename argument (no escaping needed there either). A
    Link `:target` and a bibliography `url`/`doi` field are a URI, so a
    document that does not honour that documented expectation is not
    left to fail silently: `latex-safe-url` (TASK-78) percent-encodes the
    one or two things confirmed to break compilation -- a raw backslash
    always, and `{`/`}` only when unbalanced, since a balanced pair is
    the confirmed-working case above and must be left exactly as
    authored. An Image `:src` is a FILENAME rather than a URI, where the
    same rewrite would look up a file that doesn't exist on disk, so it
    stays fully verbatim and a build instead warns when it carries either
    hazard (`unescapable-image-path-diagnostics`).
  - Section -> `\\label{label-id id}` immediately after `\\section{...}`
    (etc.) when `attr.id` is present -- not required by any of this
    task's own ACs, but a direct parity call with `haselnuss.emit.html`'s
    own precedent (that namespace already renders a Section's `attr.id`
    as a real HTML anchor via `attr-attrs`, entirely independent of
    TASK-20's later cross-ref *resolution* work -- it is what makes a
    Section a valid link target at all); flagged via a task comment. Uses
    `label-id`, NOT `escape-tex` -- this task's review comment #2 found
    running a Section's `attr.id` through `escape-tex` fatally broke
    compilation for exactly the realistic id shape this project's own
    id convention uses (an underscore, e.g. `sec:big_o_notation`),
    confirmed with a real `pdflatex`: `\\label`'s argument is written raw
    to the `.aux` file and read back by `\\newlabel`, a mechanism that
    tolerates raw `_ & ~ ^ $` but is corrupted by any of `escape-tex`'s
    own backslash-led replacements of them (see `label-id`'s own
    docstring for the full character-by-character findings). A Section's
    `attr.classes`/`attr.props` have no generic LaTeX target and are not
    translated, the same documented limitation as Span's."
  (:require [clojure.string :as str]
            [haselnuss.extensions.derived-lists :as derived-lists]
            [haselnuss.extensions.front-matter :as front-matter]
            [haselnuss.registry :as registry]))

(def ^:private combining-cluster-re
  "A base character followed by one or more combining marks -- the only
  thing `compose` touches. `(?s)` so a base character at the end of a
  line is still one."
  #"(?s).\p{M}+")

(defn compose
  "`s` with every base-plus-combining-mark cluster written as the single
  character it canonically composes to (TASK-76).

  Applied to authored PROSE on its way out, in `escape-tex` -- which is
  the one gate every piece of typeset text passes through, in the `.tex`
  and in the generated `.bib` alike. A decomposed sequence is not
  something pdflatex can set: `e` followed by U+0301 COMBINING ACUTE
  ACCENT stops the compile with \"Unicode character not set up for use
  with LaTeX\", while the identical-looking U+00E9 sets perfectly.
  Composing is the fix that needs no author action.

  Two deliberate limits, each of which was a defect before it was a
  limit.

  It normalizes CLUSTERS, not the whole string, because NFC also has
  SINGLETON compositions -- a lone character rewritten to a different
  one -- and one of them is a step backwards here: U+2126 OHM SIGN
  normalizes to U+03A9 GREEK CAPITAL LETTER OMEGA, and Greek is outside
  `typesettable-ranges` while the ohm sign is inside it. Whole-string
  NFC therefore turned `10 Ω` -- which compiled -- into a `.tex` that
  did not, and warned about a character the author never typed, which is
  this task's own complaint pointed at itself (found by review; the
  other two singletons in the table, U+2329 and U+232A, are harmless).
  Restricting it to clusters keeps exactly the fix and drops exactly the
  hazard: a singleton has no combining mark to match.

  And it is applied to prose rather than to the emitted file, because
  not every character in a `.tex` is text. An image path, an `\\href`
  target and a `.bib`'s own `url` field are byte strings that have to
  match something outside the document -- a file on disk, a server --
  and `café.png` composed is a DIFFERENT filename from the decomposed
  one macOS stores, so composing it produced `File 'café.png' not
  found` on a document that used to build (found by review). Prose is
  the only thing this is allowed to touch.

  What it does not do at all is invent compositions Unicode does not
  define. The pair this task was found through -- U+0131 LATIN SMALL
  LETTER DOTLESS I followed by U+0301, which is what pandoc makes of
  BibTeX's own `\\'{\\i}` -- has no canonical composition, so it survives
  untouched and is reported by `untypesettable-character-diagnostics`
  instead. That split is the task's own two halves: compose what
  composes, report the rest."
  [s]
  (str/replace s combining-cluster-re
               (fn [cluster]
                 (java.text.Normalizer/normalize cluster java.text.Normalizer$Form/NFC))))

(def ^:private typesettable-ranges
  "Every non-ASCII code point this emitter's own output can be typeset
  with, as inclusive `[low high]` ranges (TASK-76).

  DERIVED, not guessed: these are the characters `\\DeclareUnicodeCharacter`d
  by inputenc's own `utf8enc.dfu` (TeX Live 2023,
  `texmf-dist/tex/latex/base/utf8enc.dfu`), minus the Cyrillic block. The
  subtraction is the one systematic exception and it is real: utf8enc.dfu
  declares U+0400-U+04FF, but those declarations expand to `\\CYR...`
  commands that the T1 font encoding this emitter loads does not provide,
  so U+0410 and U+0439 both fail to compile despite being declared. That
  is 217 of the file's 566 declarations; the remaining 349 code points
  compress to the 69 ranges below.

  Checked against a real pdflatex rather than trusted: 114 single-character
  documents were compiled against this emitter's own preamble (utf8
  inputenc + T1 fontenc) -- 64 hand-picked plus 50 sampled at random, half
  from inside this table and half from outside -- and the prediction
  \"ASCII, or in one of these ranges\" matched the compiler 114 times out
  of 114.

  A range guess would have been wrong in both directions, which is why
  this is a table and not a rule: U+2192 RIGHTWARDS ARROW compiles and
  U+2264 LESS-THAN OR EQUAL TO does not; U+1E1F compiles and the
  neighbouring U+0149 does not. Greek, CJK, and the mathematical operators
  are all outside it -- a document wanting those wants XeLaTeX or LuaLaTeX,
  which is a different output target, not a wider table here."
  [[0x00A0 0x0125] [0x0128 0x0137] [0x0139 0x013E] [0x0141 0x0148] [0x014A 0x0165]
   [0x0168 0x017E] [0x0192 0x0192] [0x01C4 0x01D4] [0x01E2 0x01E3] [0x01E6 0x01EB]
   [0x01F0 0x01F0] [0x01F4 0x01F5] [0x0218 0x021B] [0x0232 0x0233] [0x0237 0x0237]
   [0x02C6 0x02C7] [0x02D8 0x02D9] [0x02DB 0x02DD] [0x0E3F 0x0E3F] [0x1E02 0x1E03]
   [0x1E0D 0x1E0D] [0x1E1E 0x1E21] [0x1E25 0x1E25] [0x1E30 0x1E31] [0x1E37 0x1E37]
   [0x1E43 0x1E43] [0x1E45 0x1E45] [0x1E47 0x1E47] [0x1E5B 0x1E5B] [0x1E63 0x1E63]
   [0x1E6D 0x1E6D] [0x1E8E 0x1E91] [0x1E9E 0x1E9E] [0x1EF2 0x1EF3] [0x200C 0x200C]
   [0x2010 0x2016] [0x2018 0x201A] [0x201C 0x201E] [0x2020 0x2022] [0x2026 0x2026]
   [0x2030 0x2031] [0x2039 0x203B] [0x203D 0x203D] [0x2044 0x2044] [0x204E 0x204E]
   [0x2052 0x2052] [0x20A1 0x20A1] [0x20A4 0x20A4] [0x20A6 0x20A6] [0x20A9 0x20A9]
   [0x20AB 0x20AC] [0x20B1 0x20B1] [0x2103 0x2103] [0x2116 0x2117] [0x211E 0x211E]
   [0x2120 0x2120] [0x2122 0x2122] [0x2126 0x2127] [0x212E 0x212E] [0x2190 0x2193]
   [0x2329 0x232A] [0x2422 0x2423] [0x25E6 0x25E6] [0x25EF 0x25EF] [0x266A 0x266A]
   [0x27E8 0x27E9] [0x3008 0x3009] [0xFB00 0xFB06] [0xFEFF 0xFEFF]])

(defn- typesettable?
  "True if code point `cp` is one pdflatex can set in this emitter's own
  output: ASCII, or listed in `typesettable-ranges`."
  [cp]
  (or (< cp 0x80)
      (boolean (some (fn [[low high]] (and (>= cp low) (<= cp high))) typesettable-ranges))))

(def ^:private tex-escapes
  "Every character special to TeX, mapped to its own literal replacement
  text -- see `escape-tex`'s own docstring for why this is applied as a
  single per-character pass rather than a chain of sequential
  `clojure.string/replace` calls."
  {\\ "\\textbackslash{}"
   \{ "\\{"
   \} "\\}"
   \$ "\\$"
   \& "\\&"
   \# "\\#"
   \% "\\%"
   \_ "\\_"
   \^ "\\textasciicircum{}"
   \~ "\\textasciitilde{}"})

(defn- escape-tex
  "Escapes every character special to TeX in `s` (AC #3) so authored text
  is always safe to splice directly into an ordinary LaTeX text argument:
  `\\`, `{`, `}`, `$`, `&`, `#`, `%`, `_`, `^`, `~` (`tex-escapes`). Each
  original character is mapped to its own final replacement exactly once
  -- see this namespace's own docstring for why that single-pass shape
  matters here.

  NOT safe for a `\\label{}` argument -- see `label-id` below, which
  exists specifically because this function is the wrong tool there.

  Composes as well as escapes (TASK-76). This is the one gate every
  piece of typeset text passes through -- the `.tex` body, the title
  block, a caption, and every prose field of the generated `.bib`
  through `escape-bibtex` -- and it is the layer that KNOWS the string
  is prose rather than a filename or a URL, which is what makes it the
  right place to do it. See `compose`."
  [s]
  (compose (apply str (map (fn [c] (tex-escapes c c)) s))))

(def ^:private label-unsafe-replacements
  "The only characters NOT safe to splice raw into a `\\label{}` argument,
  each mapped to a readable ASCII placeholder -- see `label-id`'s own
  docstring for the empirical findings (this task's review comment #2)
  behind this exact set. Every character NOT in this map -- including
  `_ & ~ ^ $` and this project's own `:`-delimited id convention
  (`sec:`/`fig:`/etc, sec6.1) -- passes through `label-id` completely
  unescaped, confirmed via a real `pdflatex` (TeX Live 2023) that
  hyperref's own `\\label`/`\\newlabel`/`.aux` round trip tolerates all of
  them raw. `%` and `#` are unsafe raw (a `%` opens a TeX comment that
  eats the rest of the line including the closing `}`; a bare `#` outside
  macro parameter text is a fatal parameter-character error); a literal
  backslash and unbalanced braces are unsafe for the structurally
  distinct reason that `\\label`'s argument is written raw to the `.aux`
  file and read back by `\\newlabel` (a stray backslash there corrupts
  that read-back macro call, confirmed empirically for every one of
  `escape-tex`'s own backslash-led replacements too -- `\\_`, `\\%`,
  `\\$`, etc. -- which is exactly why `escape-tex` itself must never be
  used here) and braces would desynchronize `\\label`'s own argument
  grouping. None of these five characters is a realistic part of this
  project's own id convention, so a readable placeholder (rather than
  silently dropping the character) is a pragmatic, documented limitation
  -- see `label-id`'s own docstring for how the `-`-delimited shape of
  these placeholders (`-percent-` etc) is made collision-free against a
  source id that already happens to contain a literal `-`."
  {\% "-percent-"
   \# "-hash-"
   \\ "-backslash-"
   \{ "-lbrace-"
   \} "-rbrace-"})

(defn- label-id
  "Transforms Attr `id` into text safe to splice as a `\\label{}`
  argument -- deliberately NOT `escape-tex` (see that function's own
  docstring, this function's docstring, and this task's review comment
  #2 for why running a Section's `attr.id` through the prose-escaping
  `escape-tex` is a fatal bug: raw `_ & ~ ^ $` are fine inside `\\label{}`
  but `escape-tex`'s own *escaped* forms of them are not, since a literal
  backslash corrupts the `\\label`/`.aux`/`\\newlabel` round trip).

  Only `label-unsafe-replacements`' own 5 characters (`% # \\ { }` --
  confirmed unsafe both raw and via `escape-tex`) are substituted with a
  readable placeholder; every other character -- including this
  project's own colon/underscore id convention (`sec:big_o_notation`,
  etc) and the confirmed-raw-safe `& ~ ^ $` -- passes through untouched.

  Collision-free by construction (this task's review comment #7 found
  the original single-pass version was NOT: `sec:100%_off` and the
  entirely distinct `sec:100-percent-_off` both sanitized to the
  identical `sec:100-percent-_off`, since a literal `-` already present
  in a source id is indistinguishable from a `-`-delimited placeholder
  this function itself introduces). Fixed with the standard two-pass
  escaping technique -- first double every literal `-` already in `id`
  (`-` -> `--`), THEN substitute the 5 unsafe characters into their
  `-word-`-shaped placeholders. Because a literal `-` can now only ever
  appear doubled, a placeholder's own lone leading/trailing `-` can never
  have come from the source id -- it can only have been introduced by
  this function's own substitution step, which runs strictly after (and
  is never re-applied to its own output, so a placeholder's dashes are
  never themselves re-doubled). This makes the whole transformation
  reversible in principle (a decoder can always tell an escaped literal
  `-` (`--`) apart from a substituted placeholder (`-word-`), since no
  placeholder word itself contains a `-`), which is exactly what makes
  two different ids always sanitize to two different labels. Still
  deterministic/pure: the same `id` always produces the same output.

  Every reference *to* a label must apply this exact same transformation
  to produce a matching argument, or it silently points at nothing:
  `render-cross-ref` (TASK-23, native `\\ref`/`\\Cref` mode and computed
  mode's own `\\hyperref[]{}` alike) does, which is what keeps a
  reference and its target agreeing on the sanitized spelling of an id
  containing any of the 5 unsafe characters."
  [id]
  (let [dash-escaped (apply str (map (fn [c] (if (= c \-) "--" c)) id))]
    (apply str (map (fn [c] (label-unsafe-replacements c c)) dash-escaped))))

(defn- balanced-braces?
  "True if every `{` in `s` is closed by a later `}` and no `}` closes one
  that was never opened -- i.e. `s` could sit inside another pair of
  braces without desynchronizing it. Used by `latex-safe-url` (TASK-78):
  hyperref's own `\\href` URL argument tolerates a raw, BALANCED `{ }`
  (confirmed empirically, `render-inline`'s own docstring), but
  `<https://example.com/a{b>` -- one unmatched `{` -- fails a real
  pdflatex with \"File ended while scanning use of \\hyper@n@rmalise\":
  the stray brace shifts where `\\href`'s own closing `}` is read as
  ending the URL argument versus starting the next one."
  [^String s]
  (loop [i 0 depth 0]
    (if (>= i (.length s))
      (zero? depth)
      (let [c (.charAt s i)]
        (cond
          (= c \{) (recur (inc i) (inc depth))
          (= c \}) (and (pos? depth) (recur (inc i) (dec depth)))
          :else (recur (inc i) depth))))))

(defn- latex-safe-url
  "Rewrites `url` (a Link `:target` or a bibliography `url`/`doi` field)
  so the two ways this emitter has confirmed break `hyperref`/BibTeX's own
  scanning of it, left raw, cannot appear (TASK-78):

  - A literal backslash is percent-encoded (`%5C`) UNCONDITIONALLY: RFC
    3986 does not admit a raw backslash into a URI at all, so this is the
    correct spelling rather than a guess, and it is not in the safe-raw
    set `render-inline`'s own docstring documents (`% # & _ { } ~ ^ $`
    minus `\\`) -- confirmed empirically that a raw one is read as the
    start of a LaTeX command (`<https://example.com/a\\b>` fails pdflatex
    with \"Illegal parameter number in definition of \\Hy@tempa\").
  - `{`/`}` are percent-encoded (`%7B`/`%7D`) ONLY when `url`'s own braces
    are NOT balanced (`balanced-braces?`) -- a balanced pair is confirmed
    to compile raw and must be left alone (AC#3: hyperref already carries
    it correctly, and rewriting a URL that already works would be an
    unrequested, visible change to it). An unbalanced one breaks
    compilation outright (`balanced-braces?`'s own docstring), and rather
    than guess which single brace is \"the\" offending one, every brace in
    an unbalanced target is encoded.

  Never touches `%` itself, so a URL already percent-encoded passes
  through completely unchanged rather than being encoded a second time
  (AC#4) -- this needs no extra case: with only `\\ { }` ever rewritten,
  there is no `%` in the input this function could double up."
  [^String url]
  (let [escaped (str/replace url "\\" "%5C")]
    (if (balanced-braces? url)
      escaped
      (-> escaped (str/replace "{" "%7B") (str/replace "}" "%7D")))))

(def ^:private section-commands
  "The LaTeX sectioning commands (article class), deepest-nesting-first
  indexed from Section `:level` 1 (AC #2: \"section/subsection/
  subsubsection/...\"). Only 5 entries exist (article has no sectioning
  command deeper than `\\subparagraph`, unlike HTML's 6 heading levels) --
  `section-command` clamps into this range."
  ["section" "subsection" "subsubsection" "paragraph" "subparagraph"])

(def ^:private chapter-section-commands
  "`section-commands` for a document whose top-level division is the
  chapter (TASK-53): the same list with `\\chapter` in front, so level 1
  is a chapter and every deeper level shifts down one, out to
  `\\subparagraph` at level 6.

  Six entries, not five, and that is the point of a separate list rather
  than an offset applied to the one above: a chaptered document's
  deepest authored heading genuinely sits one level further down (the
  thesis this was scoped from bottoms out at `\\paragraph` under a
  chapter, which is level 5 here and would have been clamped away as
  level 6 of the five-entry list)."
  (into ["chapter"] section-commands))

(defn- section-command
  "The sectioning command name (no leading backslash) for Section `level`
  in `ctx`'s own division, clamped into the active list's range (sec4.3
  places no bound on `:level`, but neither class has a command beyond
  `\\subparagraph`) -- mirrors `haselnuss.emit.html/heading-tag`'s own
  `h1`-`h6` clamp.

  `ctx`'s `:chapters` selects the list: `chapter-section-commands` for a
  document that opted into chapters (`meta.topLevelDivision`),
  `section-commands` otherwise, which is every document written before
  that key existed."
  [ctx level]
  (let [commands (if (:chapters ctx) chapter-section-commands section-commands)]
    (nth commands (min (dec (count commands)) (max 0 (dec level))))))

(declare render-inlines)
;; Needed one definition earlier than haselnuss.emit.html's own equivalent
;; declare-placement (html.clj only ever needs render-blocks forward-
;; declared for its List/BlockQuote helpers): render-inline's own :note
;; case (AC #4) calls render-blocks directly, and render-figure/
;; render-table (AC #1/#2, defined below render-inline but above the
;; render-block dispatcher that calls them) call render-block/
;; render-blocks both.
(declare render-blocks)
(declare render-block)

(defn- render-wrapped
  "Renders `inline`'s own `:inlines` through `render-inlines`, wrapped
  between `open` and `close` command fragments -- the shared shape behind
  every purely-recursive Inline variant this namespace covers (`:emph`/
  `:strong`/`:strike`/`:small-caps`/`:sub`/`:sup`/`:span`)."
  [ctx inline open close]
  (str open (render-inlines ctx (:inlines inline)) close))

(def ^:private percentage-re
  "A sizing value written as a percentage, with the number captured --
  shared by an image's own `width`/`height`/`scale` (`image-size`) and a
  table column's `:width` (`col-width`), which face the same hazard for
  the same reason.

  Deliberately permissive about the number's shape -- an optional sign,
  and a leading `.` with no integer part -- because the alternative is
  not \"the value is ignored\" but a fatal compile. A `%` that reaches an
  `\\includegraphics` option list opens a TeX comment which eats the rest
  of the line, closing bracket included: `scale=-50%`, `scale=.5%` and
  `width=-20%` were each confirmed to die with `File ended while
  scanning use of \\Gin@ii` (found by review, which caught that an
  earlier, narrower pattern closed the common case and left these open).
  Anything still carrying a `%` after this is rejected outright by
  `image-size`."
  #"([+-]?(?:\d+(?:\.\d+)?|\.\d+))%")

(defn- percentage-fraction
  "The captured number `n` of a `percentage-re` match as a plain decimal
  fraction, for splicing in front of a LaTeX length.

  Exact decimal arithmetic, and `toPlainString`, because a double gets
  this wrong twice over: `0.05%` came out as `5.0E-4`, which pdflatex
  answers with `! Illegal unit of measure` (found by review of TASK-74),
  and a repeating fraction would print more digits than anyone wrote.
  Dividing by 100 always terminates, so there is no rounding mode to
  choose."
  [n]
  (.toPlainString (.divide (bigdec n) 100M)))

(defn- image-size
  "The LaTeX value for image sizing prop `prop` of Attr `attr`, or nil
  when it is absent or blank.

  Blank is ABSENT, not a value that happens to be empty: `{width=}` used
  to emit a bare `width=`, which is not a legal option and fails the
  compile, and the only other thing an empty attribute could sensibly
  mean is the documented default.

  A percentage becomes a fraction of `dimension` -- `\\linewidth` for a
  width, `\\textheight` for a height, and nothing at all for `scale`,
  whose argument is a bare multiplier rather than a length.

  Anything else is passed through as an authored LaTeX dimension, the
  same contract Table `:colspec` `:width` already documents -- EXCEPT a
  value still containing a `%`, which cannot be a LaTeX dimension and
  which would silently produce a `.tex` file that does not compile.
  That raises `ex-info` (`::invalid-image-size`) naming the prop and the
  value, this namespace's established alternative to emitting output
  confirmed to fail."
  [attr prop dimension]
  (when-let [value (not-empty (str/trim (str (get-in attr [:props prop]))))]
    (if-let [[_ n] (re-matches percentage-re value)]
      (str (percentage-fraction n) dimension)
      (if (str/includes? value "%")
        (throw (ex-info
                (str "image " prop " " (pr-str value) " is not a LaTeX dimension: a percentage"
                     " must be a plain number followed by %, and any other % opens a TeX"
                     " comment that swallows the rest of the \\includegraphics options")
                {:type ::invalid-image-size :prop prop :text value}))
        value))))

(defn- graphics-options
  "The `\\includegraphics[...]` option list for Image Attr `attr` --
  everything except `scale`, which `render-image` applies from the
  outside (see there for why it cannot go here).

  An authored `width` prop (`![x](y.png){width=50%}` / `{width=3cm}`)
  becomes a real `width=` option: a percentage is relative to
  `\\linewidth`, the LaTeX equivalent of what a browser does with the
  same value on an `<img>`, and any other value is passed through as a
  LaTeX dimension. Without this the prop reached HTML and was silently
  dropped for print -- an author who noticed their figure running off
  the page had no way at all to fix it from the document.

  `height` (TASK-60) works the same way, against `\\textheight` rather
  than `\\linewidth` -- the page dimension a vertical percentage can
  only sensibly mean.

  With no `width` prop, `max width=\\linewidth` (from `adjustbox`,
  loaded with its `export` option so `\\includegraphics` accepts it):
  an image wider than the text block is scaled down to fit and a
  smaller one is left alone. A bare `\\includegraphics` overflows the
  page for any image wider than the text block -- TASK-29's own dogfood
  figure did, by 137pt, off the edge of the paper -- which is precisely
  the \"usable output for print without hand-tuning LaTeX\" this
  project exists for, failing silently (`pdflatex` reports an Overfull
  hbox warning and exits 0)."
  [attr]
  (str/join ","
            (cond-> []
              (image-size attr "width" "\\linewidth")
              (conj (str "width=" (image-size attr "width" "\\linewidth")))
              ;; The overflow guard applies whenever no explicit width was
              ;; authored, including alongside a height or a scale.
              (nil? (image-size attr "width" "\\linewidth"))
              (conj "max width=\\linewidth")
              (image-size attr "height" "\\textheight")
              (conj (str "height=" (image-size attr "height" "\\textheight"))))))

(defn- render-image
  "Renders Image Inline `image` as `\\includegraphics`, wrapped in
  `\\adjustbox{scale=...}` when the Image carries a `scale` prop.

  The wrapper, rather than graphicx's own `scale=` option, and this is
  AC #4 of TASK-60 (found by review, which caught the first
  implementation getting it exactly backwards). Measured on a real
  200x100 image:

      width=3cm                        85.36pt x 42.68pt
      width=3cm,scale=0.5              85.36pt x 42.68pt   <- scale INERT
      height=2cm                      113.81pt x 56.91pt
      height=2cm,scale=0.5            113.82pt x 56.91pt   <- scale INERT
      adjustbox{scale=0.5}{width=3cm}  42.68pt x 21.34pt   <- composed

  graphicx's `scale` key is honoured only when neither `width` nor
  `height` is set; given either, it is silently ignored. So passing all
  three as options would have DROPPED an authored prop -- the one thing
  AC #4 forbids -- and would have made the two targets disagree about
  the size of exactly the figures carrying more than one prop, since
  CSS `zoom` composes with a set width where graphicx's `scale` does
  not. `adjustbox` (already in the preamble, for `max width`) scales
  the finished box from outside, which composes with whatever sized it.

  Applied uniformly whenever `scale` is present, not only when it would
  otherwise be dropped: `\\adjustbox{scale=0.5}{\\includegraphics[max
  width=\\linewidth]{...}}` measures 100.37 x 50.19, identical to the
  bare `scale=0.5` it replaces, so one code path costs nothing and there
  is no second shape to keep in step.

  `:src` is passed through verbatim, not `escape-tex`-ed -- see this
  namespace's own docstring on `graphicx`'s filename argument."
  [image]
  (let [attr (:attr image)
        scale (image-size attr "scale" "")
        graphics (str "\\includegraphics[" (graphics-options attr) "]{" (:src image) "}")]
    (if scale
      (str "\\adjustbox{scale=" scale "}{" graphics "}")
      graphics)))

(defn- render-cross-ref
  "Renders resolved CrossRef `cross-ref` (sec4.4: `:label`, plus the
  `:target`/`:text` `haselnuss.resolver/resolve-cross-refs` annotates it
  with) in whichever of this namespace's two modes `ctx` selects -- see
  the namespace docstring for the full rationale (AC #1/#3/#4).

  Native mode reads only `:label`, the *original* target id, never the
  resolver's computed `:text`: `\\Cref{label-id label}` normally (AC #1),
  or a bare `\\ref{label-id label}` when the node's own
  `:suppress-prefix` is set, which is exactly the prefix-word-vs-bare-
  number distinction `resolve-cross-ref` itself draws for its `:text`.
  `\\Cref` (capitalized, `cleveref`) over lowercase `\\cref` deliberately:
  the resolver's own label words are capitalized (\"Section 2\",
  \"Figure 2.3\" -- see `haselnuss.resolver/default-lexicon`), so the
  capitalized form is the one that reads the same across this project's
  two targets. A label with no matching `\\label` anywhere is not
  special-cased at all: `pdflatex` renders `\\Cref`/`\\ref` of an unknown
  label as its own `??` placeholder plus a non-fatal
  \"Reference ... undefined\" warning (confirmed empirically), which is
  both AC #4's \"emits without crashing\" and the identical placeholder
  `resolve-cross-ref` puts in `:text`.

  Computed-numbers mode renders the resolver's own `:text` as ordinary
  escaped text (AC #3), wrapped in `\\hyperref[label-id target]{...}` --
  hyperref's own by-label internal link, so a computed-mode reference is
  still clickable in the PDF without `\\ref` recomputing anything. A
  dangling reference (`:target` nil) renders as the bare `??` text with
  no link at all, mirroring `haselnuss.emit.html/render-cross-ref`'s own
  identical branch (there is nothing valid to link to), and avoiding the
  \"Hyper reference undefined\" warning a `\\hyperref` to a nonexistent
  label would otherwise raise."
  [{:keys [computed-numbers bibliography-id]} {:keys [label suppress-prefix target text]}]
  (cond
    computed-numbers
    (let [body (escape-tex (or text "??"))]
      (if target
        (str "\\hyperref[" (label-id target) "]{" body "}")
        body))

    ;; A reference to the GENERATED bibliography section, in native mode
    ;; (TASK-64). `\Cref` cannot serve it: BibTeX sets the reference
    ;; list under an unnumbered heading, so there is no number to print
    ;; and the reference came out as `??` with a "Reference undefined"
    ;; warning. What it prints instead is the section's own name, which
    ;; is what the resolver put in `:text` and what every other target
    ;; prints for it -- the same `\hyperref` shape computed mode uses,
    ;; and safe for the same reason: a word needs no counter.
    (and bibliography-id (= bibliography-id (or target label)))
    (str "\\hyperref[" (label-id (or target label)) "]{" (escape-tex (or text "??")) "}")

    :else
    (str (if suppress-prefix "\\ref{" "\\Cref{") (label-id label) "}")))

(def ^:private natbib-cite-commands
  "The `natbib` command each CiteItem `:mode` (sec4.4) maps to in native
  mode -- chosen to match, command for command, what
  `haselnuss.resolver`'s own styles produce for that same mode, so the
  two emission modes describe the *same* citation rather than merely
  both citing something (TASK-23 review finding #1, which found this
  mapping originally inverted):

  - `:normal` -- the bracketed `[@key]` form, a parenthetical citation
    -> `\\citep`, natbib's parenthetical command. Confirmed empirically:
    `[1]` under `numbers`/`unsrtnat`, `(Knuth, 1984)` under
    `round`/`plainnat` -- exactly `numeric-item-core`/`author-date-item-
    core`'s own output for `:normal` (see `natbib-configs` for why those
    package options are selected from `meta.cslStyle`).
  - `:author` -- the bare `@key` form, an author-in-text citation
    (`haselnuss.parser` sec5.11) -> `\\citet`, natbib's textual command:
    `Knuth [1]`/`Knuth (1984)`, again matching the resolver's own bare-
    citation text. NOT `\\citeauthor`, which prints the author alone and
    silently drops the year/number -- the whole citation marker.
  - `:year` -- pandoc's `-@key` suppress-author form -> `\\citeyear`,
    the one natbib command that prints a year with no author, matching
    `author-date-item-core`'s own `:year` branch.

  A missing/unknown mode falls through to `\\citep` (see `cite-command`),
  the same way `resolve-cite-node` treats any non-`:author`/`:year` mode
  as `:normal`."
  {:normal "\\citep"
   :author "\\citet"
   :year "\\citeyear"})

(defn- cite-command
  "The `natbib` command name for CiteItem `mode` (`natbib-cite-commands`,
  defaulting to `\\citep` for a missing/unknown mode)."
  [mode]
  (get natbib-cite-commands mode "\\citep"))

(defn- cite-note-args
  "The `natbib` optional-argument fragment for CiteItem `item`'s own
  authored `:prefix`/`:suffix` Inlines (sec4.4), rendered through the
  ordinary Inline pipeline -- or `\"\"` when the item carries neither.

  Always the *two*-argument form (`[pre][post]`, with a bare `[]` pre and
  a `{}` post standing in for an absent one), and always with each note
  wrapped in an explicit brace group: `[{pre}][{post}]`. Both details are
  load-bearing, and both were established empirically (TASK-23 review
  finding #2 and its follow-up):

  - A note can contain a `]` that natbib would otherwise read as the end
    of its own optional argument, truncating the citation and spilling
    the rest of the note plus the raw citation key into the body text as
    visible garbage. The realistic sources are an authored `\\]` in
    prose (`[@knuth1984, p. 5 \\[sic\\]]`) and a Link `:target`/Image
    `:src`/raw `:tex`, none of which `escape-tex` touches -- correctly,
    since a bracket is an ordinary character in prose and a URL's own
    brackets must survive verbatim.
  - Brace-wrapping fixes that, but *only* in the two-argument form:
    confirmed with a real `pdflatex` that `\\citep[{p. 5 [sic]}]{k}`
    (one argument) still truncates at the inner `]`, while
    `\\citep[][{p. 5 [sic]}]{k}` and `\\citep[{cf. [a]}][{p. 5 [sic]}]{k}`
    both typeset the whole note correctly -- as does a note containing a
    `\\href` whose URL itself contains `[1]`. Emitting both arguments
    unconditionally is therefore the shape that protects *any* rendered
    content, without this namespace having to escape brackets inside
    fields (a URL, raw TeX) where escaping them would itself be wrong."
  [ctx {:keys [prefix suffix]}]
  (when (or (seq prefix) (seq suffix))
    (str (if (seq prefix) (str "[{" (render-inlines ctx prefix) "}]") "[]")
         "[{" (when (seq suffix) (render-inlines ctx suffix)) "}]")))

(defn- plain-cite-item?
  "True when CiteItem `item` needs no natbib command or optional argument
  of its own -- an ordinary `:normal`-mode citation with no authored
  `:prefix`/`:suffix` -- and can therefore be folded into one shared
  multi-key `\\cite{a,b}` with its siblings (see `render-cite`)."
  [{:keys [prefix suffix mode]}]
  (and (= :normal mode) (empty? prefix) (empty? suffix)))

(defn- render-cite
  "Renders resolved Cite `cite` (sec4.4: `:items`, plus the `:text`
  `haselnuss.resolver/resolve-citations` formats it into) in whichever of
  this namespace's two modes `ctx` selects -- see the namespace docstring
  (AC #1/#3/#4).

  Native mode ignores `:text` entirely and emits natbib commands over the
  items' own `:key`s (AC #1), letting natbib and BibTeX format the
  citation from the same source `\\bibliography` names (AC #2): one
  shared `\\citep{a,b,c}` when *every* item is plain (`plain-cite-item?`,
  the overwhelmingly common shape, and the only one natbib can render as
  a single properly-collapsed citation), else one command per item --
  `cite-command` per its own `:mode`, `cite-note-args` for its own
  `:prefix`/`:suffix` -- joined by `\"; \"`, since natbib's `[pre][post]`
  optional arguments describe a single citation and cannot be given
  per-key inside a grouped one. A `:key` with no BibTeX entry is not
  special-cased: natbib renders its own `?` placeholder plus a non-fatal
  \"Citation undefined\" warning (AC #4, confirmed empirically). Keys are
  spliced raw, not `escape-tex`-ed -- like a Link `:target`, a citation
  key is documented as a BibTeX key, not authored prose (and
  `haselnuss.parser`'s own key alphabet, `[A-Za-z0-9_:-]`, contains
  nothing TeX-special anyway).

  Native mode falls back to the *computed* rendering below in the two
  cases where emitting a natbib command would produce a citation with
  nothing behind it (TASK-23 review finding #4/#7):

  - `ctx`'s `:native-bibliography` is false -- this document will emit no
    `\\bibliography` at all (see `emit-document`'s own opts and
    `generated-bibliography-section?`), so every `\\citep` in it would
    typeset as natbib's undefined-citation `?` right beside a
    perfectly good resolver-generated reference list. Falling back keeps
    that pair coherent instead of half-delegating to a BibTeX run that
    is not going to happen.
  - `:items` is empty (schema-valid, sec4.4) -- there is no key to cite,
    and a bare `\\citep{}` is not a citation. Rendering `:text` keeps
    whatever the resolver made of it rather than silently emitting
    nothing at all.

  Computed-numbers mode renders the resolver's own already-formatted
  `:text` Inlines through the ordinary pipeline (AC #3), exactly as
  `haselnuss.emit.html/render-cite` does with the same field."
  [ctx {:keys [items text]}]
  (if (or (:computed-numbers ctx)
          (not (:native-bibliography ctx))
          (empty? items))
    (render-inlines ctx text)
    (if (every? plain-cite-item? items)
      (str "\\citep{" (str/join "," (map :key items)) "}")
      (str/join "; "
                (map (fn [item]
                       (str (cite-command (:mode item)) (cite-note-args ctx item)
                            "{" (:key item) "}"))
                     items)))))

(defn- render-inline
  "Renders one Inline (`haselnuss.ast/Inline`) to a LaTeX fragment. Covers
  this task's documented Inline scope (see namespace docstring); every
  other variant raises `ex-info` (`:type ::unsupported-inline`)."
  [ctx inline]
  (case (:t inline)
    :str (escape-tex (:text inline))
    :space " "
    :soft-break "\n"
    :line-break "\\\\\n"
    :emph (render-wrapped ctx inline "\\emph{" "}")
    :strong (render-wrapped ctx inline "\\textbf{" "}")
    ;; A stylistic strikethrough (base LaTeX has no such command at all;
    ;; `ulem`'s `\sout` is the standard choice, mirroring
    ;; `haselnuss.emit.html`'s own `<s>` -- presentational, not `<del>`'s
    ;; "this was deleted" edit semantics).
    :strike (render-wrapped ctx inline "\\sout{" "}")
    :small-caps (render-wrapped ctx inline "\\textsc{" "}")
    :sub (render-wrapped ctx inline "\\textsubscript{" "}")
    :sup (render-wrapped ctx inline "\\textsuperscript{" "}")
    :code (str "\\texttt{" (escape-tex (:text inline)) "}")
    ;; Link :target passed through `latex-safe-url`, not `escape-tex` --
    ;; see namespace docstring for why (hyperref's own URL-argument
    ;; scanning, not ordinary text escaping, and confirmed empirically
    ;; that escape-tex actively breaks it) and `latex-safe-url`'s own
    ;; docstring for the two characters it does rewrite (TASK-78).
    :link (render-wrapped ctx inline (str "\\href{" (latex-safe-url (:target inline)) "}{") "}")
    ;; Image :src likewise passed through verbatim -- see namespace
    ;; docstring (graphicx's own filename-argument handling already
    ;; tolerates a raw underscore etc; no AC requires rendering :alt,
    ;; which LaTeX has no visual equivalent for).
    :image (render-image inline)
    :span (render-wrapped ctx inline "" "")
    ;; A footnote marker is its own definition's Blocks, sec4.4 -- LaTeX's
    ;; own \footnote is self-numbering/self-placing, so (unlike
    ;; haselnuss.emit.html) no footnotes accumulator is threaded anywhere
    ;; in this namespace at all (AC #4, see namespace docstring).
    :note (str "\\footnote{" (render-blocks ctx (:blocks inline)) "}")
    ;; Raw :tex passed through completely verbatim (AC #3) -- this is
    ;; already TeX, not authored prose, so escape-tex must never run over
    ;; it (see namespace docstring). The closing delimiter sits on its own
    ;; line, NOT concatenated onto :tex's own last line (review comment
    ;; #4, this task's own fatal bug): an un-escaped, entirely plausible
    ;; `%` in :tex would otherwise TeX-comment out the emitter's own
    ;; closing "\)" along with the rest of that line.
    :math-inline (str "\\(" (:tex inline) "\n\\)")
    ;; TASK-23: both mode-dependent -- see each function's own docstring.
    :cross-ref (render-cross-ref ctx inline)
    :cite (render-cite ctx inline)
    (throw (ex-info
            (str "latex emitter does not support inline type " (pr-str (:t inline)))
            {:type ::unsupported-inline :inline inline}))))

(defn- render-inlines
  "`render-inline` mapped and concatenated over every Inline in `inlines`,
  in order."
  [ctx inlines]
  (apply str (map (partial render-inline ctx) inlines)))

(defn- attr-label
  "A `\\label{label-id id}` fragment for Attr `attr`'s own `:id`, or `\"\"`
  when unset -- shared by every referenceable node this namespace renders
  (Section: TASK-21; Figure/Table/an id-bearing MathBlock: this task, see
  namespace docstring) as the anchor a later TASK-23 native `\\ref`/
  `\\cref` needs to point at, independent of that later resolution task.
  Uses `label-id`, NOT `escape-tex` -- see `label-id`'s own docstring:
  TASK-21's review comment #2 found running a Section's `attr.id` through
  `escape-tex` fatally broke compilation for the exact realistic id shape
  this project's own convention uses (`sec:big_o_notation`, an underscore
  and colon), confirmed with a real `pdflatex`. `:classes`/`:props` have
  no generic LaTeX target and are not rendered at all, documented as a
  limitation there and on Span alike."
  [attr]
  (if-let [id (:id attr)]
    (str "\\label{" (label-id id) "}")
    ""))

(defn- unnumbered-label
  "`attr-label` for a node whose surrounding construct steps no counter in
  computed-numbers mode, prefixed there with `\\phantomsection`
  (TASK-23). In native mode a Section/Figure/Table `\\label` follows a
  real numbered `\\section`/`\\caption`, which has stepped its counter and
  left hyperref a proper anchor to record; in computed mode all three
  become their starred, uncounted variants (`\\section*`,
  `\\caption*` -- see `caption-command`), so without `\\phantomsection`
  (hyperref's own \"make an anchor right here\" command) a computed-mode
  `\\hyperref` to one of them would silently jump to whatever counter was
  stepped last, confirmed empirically to be the *enclosing section*.
  Empty (like `attr-label`) when `attr` has no `:id`: there is nothing to
  anchor."
  [ctx attr]
  (let [label (attr-label attr)]
    (if (and (seq label) (:computed-numbers ctx))
      (str "\\phantomsection" label)
      label)))

(defn- section-number-prefix
  "The number a numbered Section prints ahead of its own heading text in
  computed-numbers mode, or `\"\"` (TASK-41).

  Computed mode only, and that is the point: native mode uses the
  unstarred sectioning command, so `article` prints its own number and
  a baked-in one would appear beside it. The DECISION this implements --
  print the bare number, print nothing for an unnumbered Section, and
  why -- is recorded once, in
  `haselnuss.emit.html/section-number-html`, since both emitters had to
  make the same one and it is a document-level choice rather than a
  LaTeX one.

  Separated from the heading by `\\quad`, not by a space: that is what
  `article`'s own `\\@seccntformat` puts between a number and its
  heading, so a computed-mode heading sets identically to the native
  one it is standing in for. A plain space set a visibly narrower gap
  (found by review) -- the one detail on which \"what LaTeX's own
  `\\section` does\" would otherwise not have held."
  [ctx attr]
  (if-let [number (and (:computed-numbers ctx) (:number (get (:labels ctx) (:id attr))))]
    (str (escape-tex number) "\\quad ")
    ""))

(defn- counterless-label
  "`unnumbered-label` for a node that steps NO LaTeX counter in either
  mode -- a List or a CodeBlock (TASK-40) -- so it is empty in native
  mode rather than merely unstarred.

  The distinction matters and is the whole point of the separate
  function. `unnumbered-label`'s callers (Section, Figure, Table) render
  a real counted construct in native mode, so their `\\label` binds to
  something. A List or CodeBlock never does, in either mode, and a
  `\\label` with no counter behind it binds to whatever was stepped
  last: a `\\Cref` to a numbered list would confidently print the
  enclosing section's number. Omitting it leaves LaTeX's own `??` and a
  \"Reference undefined\" warning -- visibly broken, which is what
  `render-environment` chose for the same hazard. Computed mode has no
  hazard at all, since the reference text comes from the resolver and
  `\\phantomsection` anchors the jump here."
  [ctx attr]
  (when (:computed-numbers ctx)
    (unnumbered-label ctx attr)))

(defn- caption-command
  "The `\\caption{...}`/`\\caption*{...}` command for a Figure/Table with
  Attr id `id` and authored `caption` Inlines (sec4.3), or nil when there
  is nothing to caption at all (the caller then omits it entirely rather
  than emitting an empty one, mirroring `haselnuss.emit.html/render-
  caption`'s own nil contract).

  Native mode emits LaTeX's own numbered `\\caption{...}`, so `figure`/
  `longtable`'s own counters produce the number -- and omits it entirely
  for an uncaptioned float, unchanged from TASK-22's behavior.

  Computed-numbers mode (TASK-23) instead emits the `caption` package's
  unnumbered `\\caption*{...}`, carrying the resolver's own label text
  for `id` from `ctx`'s `:labels` (a `haselnuss.resolver/number-document`
  table) ahead of the authored caption -- `\"{label text}: {caption}\"`,
  the exact shape `haselnuss.emit.html/render-caption` produces, which is
  what makes the two targets' captions agree verbatim. `\\caption*` is
  used even when `id` has no label entry (an id-less or unnumbered
  float), so computed mode never falls back to LaTeX's own counter for
  some floats and not others -- which would number a document's floats
  inconsistently with each other.

  A SUBLABELED target (TASK-56 -- a subfigure panel, whose `:labels`
  entry carries a `:sublabel`) prints `\"(a) {caption}\"` in computed
  mode rather than `\"Figure 1.1a: {caption}\"`, because that is what
  `subcaption` itself prints inside the float natively, and what
  `haselnuss.emit.html/render-caption` prints for the same node. The
  full \"Figure 1.1a\" belongs in a REFERENCE to the panel, which is
  where `haselnuss.resolver` still puts it -- a panel that announced
  its own parent's number beside its letter would say something no
  target says."
  [ctx id caption]
  (let [computed? (or (:computed-numbers ctx)
                      ;; An id-LESS float, and anything inside front
                      ;; matter, is unnumbered in every other target and
                      ;; has to be unnumbered here too: LaTeX's own
                      ;; counter steps for every `\caption` it sees,
                      ;; while `haselnuss.resolver` numbers only
                      ;; id-bearing body nodes -- so a plain `\caption`
                      ;; on either put an entry in the compiled
                      ;; `.lof`/`.lot` that no derived list has, and
                      ;; pushed every float after it one number ahead of
                      ;; the number every reference prints (found by
                      ;; review; `render-float` already made this choice
                      ;; for a float directive, and this is the same
                      ;; rule for a Figure or a Table).
                      (nil? id)
                      (:unnumbered ctx))
        entry (get (:labels ctx) id)
        sublabel (:sublabel entry)
        label-text (when computed? (if sublabel (str "(" sublabel ")") (:text entry)))
        separator (if sublabel " " ": ")
        body (when (seq caption) (render-inlines ctx caption))]
    (cond
      (and label-text body) (str "\\caption*{" (escape-tex label-text) separator body "}")
      label-text (str "\\caption*{" (escape-tex label-text) "}")
      (and computed? body) (str "\\caption*{" body "}")
      body (str "\\caption{" body "}")
      :else nil)))

(defn- render-list-item
  "Renders one List item's own Block vector `item-blocks` to a LaTeX
  fragment via `render-blocks`, the content of one `\\item`."
  [ctx item-blocks]
  (render-blocks ctx item-blocks))

(defn- render-list
  "Renders List `block` (sec4.3: `:ordered`/`:items`) as an `itemize`/
  `enumerate` environment (per `:ordered`; both core LaTeX, no package),
  one `\\item` per entry in `:items`. `:tight`/`:attr` carry no LaTeX
  rendering here: LaTeX's own list spacing is independent of CommonMark's
  tight/loose distinction (no AC ties this task to reproducing that
  visual difference), and `:attr`'s `:id`/`:classes`/`:props` have no
  generic environment-level LaTeX target (mirrors Section/Span's own
  documented `:classes`/`:props` limitation -- unlike Section, a List's
  own `:attr.id` is also left unlabeled here, since a `\\label` placed
  directly after `\\begin{itemize}`, before any `\\item` has run, would
  capture no meaningful counter value the way a Section's own numbered
  heading command does)."
  [ctx {:keys [ordered items]}]
  (let [env (if ordered "enumerate" "itemize")]
    (str "\\begin{" env "}\n"
         (apply str (map (fn [item-blocks] (str "\\item " (render-list-item ctx item-blocks) "\n"))
                         items))
         "\\end{" env "}")))

(defn- verbatim-env
  "The `verbatim`-family environment name safe to wrap CodeBlock `text` in
  without it closing early -- plain `\"verbatim\"` unless `text` itself
  contains the literal substring `\\end{verbatim}`, a realistic hazard
  this task's review comment #3 confirmed is fatal (`pdflatex`: \"LaTeX
  Error: \\begin{document} ended by \\end{verbatim}\"), and entirely
  plausible for a documentation tool like this one whose own code samples
  can show `.tex` source (e.g. a tutorial about verbatim environments, or
  a doc about this very emitter). LaTeX's `verbatim` scanner matches its
  *own* environment's exact `\\end{...}` spelling, so switching to the
  LaTeX kernel's own differently-named `verbatim*` variant (no extra
  package -- it ships alongside `verbatim` itself; the only visible
  difference is interword spaces rendered as a visible sqcup marker)
  sidesteps the collision entirely, the standard idiomatic fix for this
  well-known hazard. `text` containing both terminator spellings at once
  (`\\end{verbatim}` AND `\\end{verbatim*}`) has no safe environment name
  left in this pair to fall back to, and is rejected outright (`ex-info`,
  `:type ::unrepresentable-code-block`) rather than silently emitting
  broken LaTeX -- matching this codebase's no-silent-mangle convention."
  [text]
  (cond
    (not (str/includes? text "\\end{verbatim}")) "verbatim"
    (not (str/includes? text "\\end{verbatim*}")) "verbatim*"
    :else
    (throw (ex-info
            "code block text contains both \\end{verbatim} and \\end{verbatim*}, no safe verbatim delimiter remains"
            {:type ::unrepresentable-code-block :text text}))))

(defn- render-figure
  "Renders Figure `block` (sec4.3: `:content`/`:caption`/`:attr`) as a
  `figure` environment (AC #1) -- see namespace docstring for the full
  rationale (content via the ordinary `render-block` visitor, `\\caption`
  omitted when `:caption` is empty, `attr-label` always emitted)."
  [ctx {:keys [content caption attr]}]
  ;; A float inside a float is not a shape LaTeX has: it fails the build
  ;; with \"Not in outer par mode\" and produces no PDF at all. The
  ;; reachable way to write one is an id-bearing image inside a
  ;; subfigure panel or a listing -- `haselnuss.parser` turns a
  ;; standalone id-bearing image into a Figure Block, so the id lands on
  ;; a float rather than on the panel that was meant to carry it. Named
  ;; here rather than left to pdflatex, and never silently unwrapped:
  ;; the two ids would then be two numbers for one panel.
  (when (:in-float ctx)
    (throw (ex-info (str "figure " (pr-str (:id attr))
                         " is nested inside another float, which LaTeX cannot typeset;"
                         " put the id on the enclosing directive instead")
                    {:type ::nested-float :id (:id attr)})))
  (let [cap (caption-command ctx (:id attr) caption)]
    (str "\\begin{figure}\n"
         (render-block ctx content) "\n"
         (when cap (str cap "\n"))
         ;; A `figure` float steps its own counter only from `\caption`,
         ;; so an id-bearing figure with no caption used to emit a
         ;; `\label` bound to whatever counter was stepped last -- a
         ;; `\Cref` to it printed "Section 1" instead of "Figure 1",
         ;; confidently and with no warning (TASK-28 review; the same
         ;; class of bug as TASK-24 review finding #1). `\refstepcounter`
         ;; steps and binds it without printing anything, which is
         ;; exactly what a caption-less numbered figure needs. Native
         ;; mode only: computed mode never emits `\caption` either, but
         ;; its reference text comes from the resolver and its
         ;; `\phantomsection` already supplies the anchor.
         (when (and (:id attr) (not cap) (not (:computed-numbers ctx)))
           "\\refstepcounter{figure}")
         (unnumbered-label ctx attr)
         "\n\\end{figure}")))

(defn- align-letter
  "The `tabular` column-type letter for Col/Cell `:align` (sec4.3):
  `l`/`c`/`r` for `:left`/`:center`/`:right`, defaulting to `l` (a bare
  `tabular` column needs some letter; `:left` is the same default a plain
  unaligned column would visually behave like anyway)."
  [align]
  (case align
    :center "c"
    :right "r"
    "l"))

(defn- col-width
  "One Table Col `:width` (sec4.3) as a LaTeX dimension (TASK-74).

  A percentage becomes that fraction of `\\linewidth`, MINUS the column
  separation `array` puts either side of every column. Two parts, each
  measured rather than assumed.

  The fraction, because a raw `%` reaching a `p{}` argument opens a TeX
  comment that swallows the rest of the line, closing brace included --
  the same hazard `image-size` documents, and the reason `\\linewidth` is
  the length: a percentage is the one spelling of a column width that
  means the same thing in both targets, CSS reading it against the
  table's own box.

  And `-2\\tabcolsep`, because `p{}` sizes only the cell TEXT while
  `array` adds 6pt of separation on each side of every column on top of
  it. Without the subtraction the largest set the parser accepts -- a
  full 100% -- was guaranteed to overflow: the README's own
  `20% 55% 25%` example reported `Overfull \\hbox (36.0pt too wide) in
  alignment` on every row, 12pt per column, while the HTML of the same
  document fitted (found by review; pandoc makes the same adjustment for
  the same reason). Only a percentage is adjusted: an absolute `2cm` is
  a width the author measured, and quietly making it 1.79cm would be
  answering a different question.

  Anything else is passed through as the authored dimension it is, the
  contract `:width` has always documented. `haselnuss.parser` accepts
  only a number and a unit both targets understand, so what arrives here
  from a document is always one of the two; a caller building a Table by
  hand still gets the pass-through."
  [width]
  (let [width (str/trim (str width))]
    (if-let [[_ n] (re-matches percentage-re width)]
      (str "\\dimexpr" (percentage-fraction n) "\\linewidth-2\\tabcolsep")
      width)))

(defn- col-format
  "The `tabular`-preamble token for one Table Col/Cell-derived `{:align?
  :width?}` map (sec4.3): a bare `align-letter` when `:width` is unset, or
  -- confirmed empirically to need `array`'s `>{...}` column-modifier
  syntax (now in this namespace's own fixed preamble, see namespace
  docstring) -- `>{\\raggedright|\\centering|\\raggedleft\\arraybackslash}
  p{width}` when it is, the width through `col-width` (a percentage
  becomes a `\\linewidth` fraction; anything else is spliced in raw, see
  namespace docstring for why: it is a LaTeX dimension string, not
  authored prose needing `escape-tex`)."
  [{:keys [align width]}]
  (if width
    (str ">{" (case align :center "\\centering" :right "\\raggedleft" "\\raggedright")
         "\\arraybackslash}p{" (col-width width) "}")
    (align-letter align)))

(defn- table-preamble
  "The full `{...}` column-preamble string for Table `colspec` (sec4.3),
  one `col-format` token per Col, space-joined -- or a single defensive
  `{l}` fallback when `colspec` is empty (see namespace docstring: an
  empty preamble is a confirmed fatal `array`-package error once that
  package is loaded, not a mere cosmetic gap)."
  [colspec]
  (str/join " " (map col-format (if (seq colspec) colspec [{}]))))

(defn- cell-align
  "The effective alignment for Table Cell `cell` at `col-index` (sec4.3):
  `cell`'s own `:align` if present, else `colspec`'s own entry at that
  index (nil if neither) -- mirrors `haselnuss.emit.html/cell-align`
  exactly."
  [colspec col-index cell]
  (or (:align cell) (:align (nth colspec col-index nil))))

(def ^:private default-cell-width
  "The `minipage` width (see `render-cell-content`) used for a non-trivial
  Cell whose own column has no declared `:width` in `colspec` -- an
  arbitrary but reasonable fixed dimension, mirroring this namespace's
  own existing treatment of `:width` as a raw LaTeX dimension string with
  no unit conversion: no AC specifies a numeric default, and there is no
  reliable way to infer one from the table's own overall width without
  knowing the surrounding page geometry."
  "4cm")

(defn- cell-width
  "The effective `minipage` width for a Cell at `col-index`: `colspec`'s
  own `:width` at that column when present, else `default-cell-width`. An
  actually-spanning cell (`:span` > 1) uses only its starting column's own
  width -- the same documented, narrower limitation already in place for
  a spanning cell's `\\multicolumn` alignment (see namespace docstring):
  correctly merging N columns' widths into one needs real arithmetic over
  `array`'s own column-separation dimensions that no AC requires."
  [colspec col-index]
  ;; Through `col-width` like every other use of a `:width`, so a
  ;; percentage becomes a real length here too: spliced raw, a `40%`
  ;; opened a TeX comment inside `\\begin{minipage}[t]{...}` and killed
  ;; the compile outright (found by review). Unreachable from a pipe
  ;; table, whose cells are always a single Para, but `:width` is a
  ;; documented pass-through for a caller building a Table by hand.
  (if-let [width (:width (nth colspec col-index nil))]
    (col-width width)
    default-cell-width))

(defn- has-nested-table?
  "True when `blocks` contains a `:table` Block anywhere, walking every
  nesting shape this AST allows a Block to carry other Blocks through
  (`:section`/`:list`/`:block-quote`/`:figure`/`:directive`) -- used by
  `render-cell-content` to reject a Cell containing a nested Table before
  ever trying to render it (see that function's own docstring, and the
  namespace docstring's own `longtable`-cannot-nest-in-a-`minipage`
  rationale, for why this specific shape cannot be made safe at all).

  `:directive` is walked for the same reason as the rest, and matters
  from TASK-24 on: a mapped directive renders as a real environment
  wrapping its own `:blocks` right there in the cell, so a Table inside
  one reaches `longtable` inside a `minipage` exactly as a Table inside
  a BlockQuote would. Before TASK-24 a surviving `:directive` in a cell
  was near-unreachable (only a registry renderer function could produce
  one, and its output is opaque to this visitor anyway)."
  [blocks]
  (boolean
   (some (fn [block]
           (case (:t block)
             :table true
             :section (has-nested-table? (:blocks block))
             :list (some has-nested-table? (:items block))
             :block-quote (has-nested-table? (:blocks block))
             :figure (has-nested-table? [(:content block)])
             :directive (has-nested-table? (:blocks block))
             false))
         blocks)))

(defn- simple-cell-content?
  "True when Cell `blocks` is exactly one Para -- the only shape that can
  render bare inside a plain `tabular`/`longtable` cell (restricted
  horizontal mode) without a `minipage` wrapper (see `render-cell-
  content`). Anything else -- more than one Block (even two Paras: a
  blank-line paragraph break is inert in restricted horizontal mode and
  would otherwise silently collapse into one run-on line) or a single
  non-Para Block (List/BlockQuote/MathBlock/etc, none of which can even
  start in restricted horizontal mode at all, confirmed fatal by this
  task's review comment #2) -- needs that wrapper."
  [blocks]
  (and (= 1 (count blocks)) (= :para (:t (first blocks)))))

(defn- render-cell-content
  "Renders Cell `blocks` for splicing into a `longtable` cell at
  `col-index`: bare `render-blocks` output for the one shape safe there
  as-is (a single Para, `simple-cell-content?`), else that same content
  wrapped in `\\begin{minipage}[t]{cell-width}...\\end{minipage}` (review
  comment #2, this task's own fatal bug) -- `minipage`, not
  `\\parbox{}{}`, deliberately: see namespace docstring for why (a
  CodeBlock's own `verbatim`-family environment cannot survive being read
  as an ordinary macro argument the way `\\parbox`'s own body is). A
  blank-line paragraph break between two Paras is a real `\\par` again
  once inside `minipage`'s own vertical-mode body, so two Paras render as
  two visually separate paragraphs rather than colliding onto one line.

  A nested Table (`has-nested-table?`) is the one shape this cannot make
  safe even in principle -- `longtable` must never be used inside a
  `minipage`/`\\parbox` at all (see namespace docstring) -- so this raises
  a documented `ex-info` (`:type ::unrepresentable-cell`) instead of
  emitting LaTeX confirmed to fail, matching this namespace's established
  no-silent-mangle convention."
  [ctx colspec col-index blocks]
  (when (has-nested-table? blocks)
    (throw (ex-info
            "table cell contains a nested Table, which cannot be represented: longtable cannot be used inside a minipage/parbox"
            {:type ::unrepresentable-cell :blocks blocks})))
  (let [content (render-blocks ctx blocks)]
    (if (simple-cell-content? blocks)
      content
      (str "\\begin{minipage}[t]{" (cell-width colspec col-index) "}\n" content "\n\\end{minipage}"))))

(defn- render-cell
  "Renders Table Cell `cell` (sec4.3: `:blocks`/`:align?`/`:span?`) at
  `col-index` to a `longtable` cell fragment (`render-cell-content` over
  its own `:blocks`, see that function's own docstring for the `minipage`
  wrapping rationale) -- see namespace docstring for the full
  `\\multicolumn` rationale: bare content for a plain, non-spanning,
  non-overriding cell (it inherits its column's own `table-preamble`
  format, `:width` included); `\\multicolumn{1}{col-format...}{...}`
  (preserving that column's own `:width`) when only `:align` is
  overridden; `\\multicolumn{span}{align-letter}{...}` (no `:width`, a
  documented narrower limitation) for an actually-spanning cell."
  [ctx colspec col-index cell]
  (let [span (or (:span cell) 1)
        align (cell-align colspec col-index cell)
        content (render-cell-content ctx colspec col-index (:blocks cell))]
    (cond
      (not= span 1)
      (str "\\multicolumn{" span "}{" (align-letter align) "}{" content "}")

      (:align cell)
      (str "\\multicolumn{1}{"
           (col-format {:align align :width (:width (nth colspec col-index nil))})
           "}{" content "}")

      :else content)))

(defn- render-row
  "Renders Table Row `row` (sec4.3: `:cells`) as one `longtable` row: its
  own Cells rendered via `render-cell` and joined by `&`, terminated by
  `\\\\`, threading a running `col-index` across `:cells` -- incremented
  by each cell's own `:span` (default 1), mirroring `haselnuss.emit.html/
  render-row` -- so a later cell's own `cell-align` lookup still lines up
  with `colspec` correctly even after an earlier spanning cell."
  [ctx colspec {:keys [cells]}]
  (let [[cell-strs] (reduce (fn [[acc col-index] cell]
                              [(conj acc (render-cell ctx colspec col-index cell))
                               (+ col-index (or (:span cell) 1))])
                            [[] 0]
                            cells)]
    (str (str/join " & " cell-strs) " \\\\")))

(defn- render-table
  "Renders Table `block` (sec4.3: `:head`/`:rows`/`:caption`/`:colspec`/
  `:attr`) as a `longtable` environment (AC #2, review comment #1 -- see
  namespace docstring for the full rationale for replacing this task's
  original `table`-wrapped `tabular`, which was confirmed empirically to
  silently lose the vast majority of a realistically-sized table's own
  rows off the physical page rather than break across pages at all).
  `\\caption`/`attr-label` (when either is non-empty) form `longtable`'s
  own first \"row\", terminated by `\\\\`, printed once on the table's
  first page only; the head Row (`render-row`) then repeats via
  `\\endfirsthead`/`\\endhead` -- identically on the first page and every
  page after, since no AC asks for a distinct \"(continued)\" head
  variant. `\\hline` above/below the head row (both `\\endfirsthead` and
  `\\endhead` blocks) and below the last body row (core, no package -- no
  AC requires `booktabs`' own `\\toprule`/`\\midrule`/`\\bottomrule`
  styling)."
  [ctx {:keys [head rows caption colspec attr]}]
  (let [cap (caption-command ctx (:id attr) caption)
        cap-label (str cap
                       ;; `longtable`'s `\caption*` steps the table
                       ;; counter even though it prints no number --
                       ;; unlike a float's, which does not (confirmed
                       ;; with a real pdflatex, twice: an unlabeled
                       ;; table left the next labelled one reading
                       ;; "Table 2" in the PDF and its own `.lot` while
                       ;; every other target read "Table 1"). Undoing
                       ;; the step is the standard idiom for it, and it
                       ;; is what keeps an unnumbered table from
                       ;; shifting every numbered one after it (found by
                       ;; review of TASK-59). Native mode only: computed
                       ;; mode stars every caption in the document, so
                       ;; nothing there reads that counter at all and a
                       ;; correction would be noise.
                       (when (and cap
                                  (not (:computed-numbers ctx))
                                  (str/includes? cap "\\caption*"))
                         "\\addtocounter{table}{-1}")
                       (unnumbered-label ctx attr))
        head-row (render-row ctx colspec head)]
    (str "\\begin{longtable}{" (table-preamble colspec) "}\n"
         (when (seq cap-label) (str cap-label " \\\\\n"))
         "\\hline\n" head-row "\n\\hline\n"
         "\\endfirsthead\n"
         "\\hline\n" head-row "\n\\hline\n"
         "\\endhead\n"
         (str/join "\n" (map (partial render-row ctx colspec) rows))
         (when (seq rows) "\n")
         "\\hline\n"
         "\\end{longtable}")))

(def unnumbered-environment
  "The name of the one environment `preamble` defines for this namespace's
  own use (TASK-24): a `\\newenvironment` taking the head text as its
  single argument, typeset in the same bold-head/italic-body shape
  `amsthm`'s own plain theorem style uses. It is what a mapped, normally-
  numbered directive environment becomes in computed-numbers mode, where
  the head text is the resolver's own already-computed label rather than
  a LaTeX counter (see `render-environment`).

  Deliberately a plain `\\newenvironment` and not `amsthm`'s own
  `\\newtheorem*`: confirmed empirically that `\\newtheorem*{X}{}` plus
  the resolver's label as the optional title argument typesets as
  \"(Theorem 4).\" -- parenthesized, because amsthm formats an optional
  title as a *note* beside the theorem's own name -- while this
  environment produces exactly \"Theorem 4.\", matching both amsthm's
  numbered output and the `\\hyperref` text pointing at it."
  "hnunnumbered")

(def default-directive-environments
  "The built-in directive-name -> LaTeX-environment mapping table
  (TASK-24 AC #1/#2). This is *data*: `render-directive` looks a
  Directive's own `:name` up here and wraps its `:blocks` in the named
  environment, so adding a mapped environment -- or replacing this whole
  table via `emit-document`'s `:directive-environments` opt -- never
  requires touching this namespace's visitor logic (AC #2).

  Each entry is `{:environment :counter? :title? :packages? :preamble?}`:

  - `:environment` -- the LaTeX environment name wrapping the
    directive's own content.
  - `:counter` -- true exactly when that environment steps a LaTeX
    counter of its own (`\\newtheorem` does; `\\newenvironment` and
    `amsthm`'s `proof` do not). An explicit key rather than something
    inferred from `:title` (TASK-24 review finding #2, which found the
    inference silently wrong for the table's own documented custom-entry
    example): it drives two decisions `render-environment` cannot get
    right by guessing -- whether a native-mode `\\label` would bind to
    anything real, and whether computed-numbers mode must swap in
    `unnumbered-environment` for the same reason `\\section*`/
    `\\caption*` exist there (a LaTeX theorem counter counts every
    theorem, while `haselnuss.resolver` counts only id-bearing ones, so
    the two disagree the moment a document contains one unlabeled
    theorem).
  - `:title` -- the head word this environment prints of its own
    accord, or absent when it prints none. Computed-numbers mode uses
    it when the resolver assigned a `:counter` node no number at all,
    and a caller degrading these directives for another target uses it
    to reproduce the same head (`haselnuss.cli`). Its ABSENCE would be
    meaningful -- a degradation inventing a head an environment does
    not print would make the two targets structurally disagree about
    the same document (found by TASK-29's dogfood) -- but every
    built-in entry carries one since TASK-40 gave `admonition` a
    printed, numbered head of its own.
  - `:lexicon-kind` -- the name of the `haselnuss.resolver` lexicon kind
    whose id prefix numbers this directive (TASK-40). The two tables
    live in different namespaces because they answer different
    questions -- what a document calls this thing, and what LaTeX calls
    it -- but they must agree, since a kind the emitter cannot anchor
    produces references to numbers that appear nowhere, and an
    anchorable environment with no kind can never be referenced at all.
    `kind-and-environment-agreement-test` fails if they drift. Spelled
    `:lexicon-kind` and not `:kind` deliberately: `haselnuss.registry`
    already uses `:kind` for something else in a neighbouring map -- a
    whole `LabelLexicon` merge fragment, not a name -- and one of these
    tables' entries being folded into the other is exactly the mistake
    the shared spelling would invite.
  - `:unnumbered-environment` -- the environment computed-numbers mode
    swaps this one for, taking the head text as its single argument.
    Optional: without it a `:counter` entry falls back to
    `unnumbered-environment`, which is right for the `\\newtheorem`
    family and wrong for anything whose own typesetting matters (see
    `render-environment`).
  - `:packages` -- `\\usepackage` names this entry needs, emitted before
    `hyperref` (this namespace's load order is documented as
    order-critical, so an entry cannot be left to declare its own
    packages in `:preamble` after it -- TASK-24 review finding #6).
  - `:preamble` -- the environment declaration itself, emitted once per
    distinct value, after `cleveref`.

  `admonition` maps to a `quote`-based environment defined here rather
  than to a package like `mdframed`/`tcolorbox`: base LaTeX has no
  admonition concept at all, and TASK-21 AC #1's \"compiles under a
  standard LaTeX toolchain\" is worth more than a nicer frame that
  needs an install.

  Every entry carries a counter since TASK-40. `proof` and `admonition`
  did not, which made them unanchorable: `render-environment` refuses
  to emit a native-mode `\\label` for a counter-less environment,
  because with no counter stepped LaTeX binds it to whatever was
  stepped last and a `\\Cref` to a proof confidently prints the
  enclosing section's number. Both now step a `\\newcounter` of their
  own via `\\refstepcounter`, and both print that number, so a
  reference to one names something a reader can actually find."
  {"theorem" {:environment "theorem" :counter true :title "Theorem" :lexicon-kind "thm"
              :packages ["amsthm"] :preamble "\\newtheorem{theorem}{Theorem}"}
   "lemma" {:environment "lemma" :counter true :title "Lemma" :lexicon-kind "lem"
            :packages ["amsthm"] :preamble "\\newtheorem{lemma}{Lemma}"}
   "corollary" {:environment "corollary" :counter true :title "Corollary" :lexicon-kind "cor"
                :packages ["amsthm"] :preamble "\\newtheorem{corollary}{Corollary}"}
   "definition" {:environment "definition" :counter true :title "Definition" :lexicon-kind "def"
                 :packages ["amsthm"] :preamble "\\newtheorem{definition}{Definition}"}
   ;; amsthm's own `proof` prints a head but steps no counter, so it was
   ;; unanchorable: a native-mode `\\Cref` to one printed the enclosing
   ;; section's number or nothing at all. `hnproof` wraps it in a counter
   ;; of its own and passes the number through `proof`'s optional head
   ;; argument, which keeps amsthm's typesetting and its QED box while
   ;; making `\\refstepcounter` the last counter stepped before the
   ;; `\\label` -- which is what binds the two (TASK-40).
   "proof" {:environment "hnproof" :counter true :title "Proof" :lexicon-kind "prf"
            :unnumbered-environment "hnproofstar"
            :packages ["amsthm"]
            :preamble (str "\\newcounter{hnproof}"
                           "\\newenvironment{hnproof}"
                           "{\\refstepcounter{hnproof}\\begin{proof}[Proof~\\thehnproof]}"
                           "{\\end{proof}}"
                           "\\newenvironment{hnproofstar}[1]{\\begin{proof}[#1]}{\\end{proof}}"
                           "\\crefname{hnproof}{proof}{proofs}")}
   ;; Same treatment, and the same reason. An admonition prints its own
   ;; "Note N." head now: a numbered node that shows no number would make
   ;; every reference to it a number the reader cannot find, which is the
   ;; failure `render-environment` already refuses to ship for a
   ;; counter-less environment. `:title` follows, so the degraded target
   ;; prints the same head (see `haselnuss.cli/environment-title`).
   "admonition" {:environment "hnadmonition" :counter true :title "Note" :lexicon-kind "adm"
                 :unnumbered-environment "hnadmonitionstar"
                 :preamble (str "\\newcounter{hnadmonition}"
                                "\\newenvironment{hnadmonition}"
                                "{\\refstepcounter{hnadmonition}\\begin{quote}\\itshape"
                                "\\textbf{Note~\\thehnadmonition.} }"
                                "{\\end{quote}}"
                                "\\newenvironment{hnadmonitionstar}[1]"
                                "{\\begin{quote}\\itshape\\textbf{#1.} }{\\end{quote}}"
                                "\\crefname{hnadmonition}{note}{notes}")}
   ;; TASK-57, and the first `:float` entry. Named `hnlisting` rather
   ;; than `listing`, following the `hn` prefix `hnproof`/`hnadmonition`/
   ;; `hnunnumbered` already established here: this preamble is `\\input`
   ;; by host documents in fragment mode (TASK-52), and a `\\newfloat`
   ;; taking a common name is a redefinition error in someone else's
   ;; document. Confirmed by review for the sibling `algorithm` entry --
   ;; `\\newfloat{algorithm}` followed by `\\usepackage{algorithm2e}`,
   ;; the package the source thesis's own algorithms are written in,
   ;; dies with `Command \\algorithm already defined`. `\\floatname` and
   ;; `\\crefname` mean the printed words are unaffected.
   ;;
   ;; The standard `float`
   ;; package's own `\\newfloat` is what makes a listing a real float --
   ;; a counter, a `\\caption`, a list-of file -- with no `listings` or
   ;; `minted` install, keeping TASK-21 AC #1's \"compiles under a
   ;; standard LaTeX toolchain\" true. Confirmed with a real pdflatex,
   ;; including that a `verbatim` body inside the float compiles, that
   ;; `\\Cref` resolves to \"Listing 1\", and that the counter stays
   ;; global across chapters (which is why the `lst` lexicon kind is
   ;; `:global` rather than section-scoped).
   "listing" {:environment "hnlisting" :counter true :float true
              :title "Listing" :lexicon-kind "lst"
              :packages ["float"]
              :preamble (str "\\newfloat{hnlisting}{htbp}{lol}"
                             "\\floatname{hnlisting}{Listing}"
                             "\\crefname{hnlisting}{Listing}{Listings}")}
   ;; TASK-58, and deliberately the SAME mechanism as `listing` rather
   ;; than a second one: a `\\newfloat` float around a verbatim body.
   ;;
   ;; The body stays verbatim, decided with the user rather than
   ;; inferred. The source thesis has two algorithm2e algorithms whose
   ;; steps use `\\KwData`, `\\For`, `\\If`, `\\Else` and `\\While` with
   ;; brace nesting and inline math mixed in; reconstructing that as
   ;; structured control flow would mean a keyword convention and an
   ;; algorithmicx mapping for two algorithms, and would give the two
   ;; targets something to disagree about. A verbatim body is the one
   ;; shape neither can render differently from the other. Confirmed
   ;; with a real pdflatex that a brace-heavy pseudocode body inside
   ;; this float typesets literally and compiles.
   "algorithm" {:environment "hnalgorithm" :counter true :float true
                :title "Algorithm" :lexicon-kind "alg"
                :packages ["float"]
                :preamble (str "\\newfloat{hnalgorithm}{htbp}{loa}"
                               "\\floatname{hnalgorithm}{Algorithm}"
                               "\\crefname{hnalgorithm}{Algorithm}{Algorithms}")}
   ;; TASK-56, and SPEC sec5.8's own `figure` directive -- specified
   ;; from the beginning, never built until now. LaTeX's OWN `figure`
   ;; environment, not an `hn`-prefixed `\\newfloat` like `listing` and
   ;; `algorithm`: a figure is not a new kind of float that needs
   ;; inventing, it is the one every class already defines, already
   ;; numbers per chapter, and already writes to the `.lof` file. The
   ;; `![](x){#fig:y}` shorthand keeps rendering through `render-figure`
   ;; into the same environment, so the two spellings of a figure are
   ;; the same float with the same counter.
   "figure" {:environment "figure" :counter true :float true
             :title "Figure" :lexicon-kind "fig"}
   ;; A panel of a multi-panel figure (TASK-56). `:sub` is what makes it
   ;; one: a `:sub` entry is not rendered standing on its own line like
   ;; any other float, it is laid out BY its parent float, which is the
   ;; only node that knows how many panels share a row (see
   ;; `render-float-blocks`). `subcaption`'s own `subfigure` environment
   ;; takes that width as its argument, prints the `(a)`/`(b)` letter
   ;; from a `\\caption` even when the caption is empty, and makes a
   ;; `\\Cref` to a panel resolve to "Figure 1.1a" -- the parent's own
   ;; number with the letter appended, which is exactly what
   ;; `haselnuss.resolver` computes for it. All three confirmed with a
   ;; real pdflatex, in a chaptered document.
   ;;
   ;; Same `:lexicon-kind` as its parent, deliberately: a panel IS a
   ;; figure for numbering purposes, and it is that sameness -- a
   ;; numbered node inside a numbered node of the same kind -- that the
   ;; resolver reads as "sublabel" with no directive name anywhere in
   ;; the rule.
   "subfigure" {:environment "subfigure" :counter true :float true :sub true
                :title "Figure" :lexicon-kind "fig"
                :packages ["subcaption"]}})

(def counterless-block-types
  "The Block types this emitter renders with no LaTeX counter behind
  them, and therefore anchors in computed-numbers mode only -- see
  `counterless-label`. Public because it is the whole content of a
  build-time warning a caller can only give if it knows this set
  (`unanchored-reference-diagnostics`)."
  #{:block-quote :list :code-block})

(def ^:private anchor-bearing-block-types
  "The Block types whose `attr.id` this emitter can turn into a
  `\\label` at all (`counterless-block-types` among them, in computed
  mode only). Everything else carrying an `attr.id` -- notably the
  Inline `:span`/`:link`/`:image` variants, which LaTeX has no anchor
  hook for and which this namespace renders as pass-throughs -- is not
  an anchor, and `unanchored-reference-diagnostics` must not count one
  as if it were (found by review of TASK-51)."
  #{:section :figure :table :math-block :directive :block-quote :list :code-block})

(defn- ast-nodes
  "Every AST node (any map carrying a `:t`) reachable from `document`, in
  no particular order. A generic `tree-seq` rather than a hand-written
  visitor: this is a read-only scan, so it needs none of `render-block`'s
  per-variant knowledge, and a visitor that had to be kept in step with
  the AST is exactly how a scan like this rots.

  `:fallback` is the one field not walked, mirroring every other pass's
  scope limit for it (`haselnuss.resolver/resolve-block`,
  `kind-role-diagnostics`): it is degradation material for
  `haselnuss.lower`, and in a lowered document either already applied or
  never going to be, so counting a reference inside one would warn about
  text that is not in the output."
  [document]
  (->> (tree-seq coll?
                 (fn [node] (if (map? node) (vals (dissoc node :fallback)) (seq node)))
                 document)
       (filter #(and (map? %) (keyword? (:t %))))))

(defn unanchored-reference-diagnostics
  "One `{:type :unanchored-reference :id :node-type :message}` warning per
  CrossRef in `document` (a LOWERED Document, so what an emitter will
  actually see) whose target this emitter will not anchor, in document
  order (TASK-51). `opts` is `emit-document`'s own, read for
  `:computed-numbers`.

  Two shapes, because degradation can lose a reference's target in two
  different ways, and both were silent:

  - **The id is not in the lowered document at all.** A directive's
    degradation dropped it -- sec4.3 gives Para no `Attr`, so a fallback
    that splices bare Paras has nowhere to put one. The reference points
    at nothing in *either* mode, so this is reported in both.
  - **The id is carried only by a `counterless-block-types` block**, and
    this is native mode. Those are anchored in computed mode
    (`counterless-label`) and deliberately not in native, where a
    `\\label` with no counter behind it records whatever counter LaTeX
    stepped last -- so a `\\Cref` to a degraded directive would print
    the enclosing section's number as though it were the answer, the
    same confidently-wrong output `render-environment` refuses to emit.
    The remedy is in the message: `--computed-numbers`, or a directive
    the environment table maps, which steps a counter of its own.

  This is the emitter's knowledge, not the resolver's: the resolver
  resolved these references successfully, and every one of them works on
  the HTML target. What decides them is which nodes *this* emitter
  labels, so the check lives here and `haselnuss.cli` prints the result
  through the same warning path as every resolver diagnostic.

  Scope, stated rather than left to be discovered:

  - A reference the resolver could NOT resolve (`:target nil`, `:text
    \"??\"`) is skipped entirely. `resolve-cross-refs` already warns
    about it by name, and this pass has nothing to add: the id names
    nothing anywhere, not merely nothing anchorable. Without that gate
    every dangling reference drew a second warning asserting a cause
    that was not there (found by review).
  - Only `anchor-bearing-block-types` count as anchors. An `attr.id` on
    an Inline `:span`/`:link`/`:image` is not one -- LaTeX has no hook
    for it and this namespace renders those as pass-throughs.
  - A mapped directive counts only when it will really carry a
    `\\label`: `render-environment` refuses one in native mode for an
    environment declaring no `:counter`, for the same
    binds-to-the-wrong-counter reason. `opts`' own
    `:directive-environments` (default `default-directive-environments`)
    is read for that, so a caller's table is judged by its own entries.
  - Not covered, and still silent: a Section deeper than LaTeX's
    `secnumdepth`, whose `\\label` binds to the enclosing numbered
    level. That is a pre-existing hazard of the Section renderer rather
    than of degradation, and reporting it needs a document-level notion
    of depth this function does not have."
  [document opts]
  (let [nodes (ast-nodes document)
        computed? (boolean (:computed-numbers opts))
        environments (:directive-environments opts default-directive-environments)
        anchors? (fn [node]
                   (and (contains? anchor-bearing-block-types (:t node))
                        (or computed?
                            (not= :directive (:t node))
                            (:counter (get environments (:name node))))))
        anchored (into {}
                       (comp (filter anchors?)
                             (keep (fn [node]
                                     (when-let [id (get-in node [:attr :id])]
                                       [id (:t node)]))))
                       nodes)
        counterless? (if computed?
                       (constantly false)
                       #(contains? counterless-block-types %))]
    (into []
          (comp (filter #(and (= :cross-ref (:t %)) (:target %)))
                (keep (fn [{:keys [label]}]
                        (let [node-type (get anchored label)]
                          (cond
                            (nil? node-type)
                            {:type :unanchored-reference
                             :id label
                             :node-type nil
                             :message (str "cross-reference to \"" label "\" resolves, but"
                                           " nothing in the LaTeX output anchors that id:"
                                           " it prints ?? there. A directive whose"
                                           " degradation drops its own attr is the usual"
                                           " cause")}

                            (counterless? node-type)
                            {:type :unanchored-reference
                             :id label
                             :node-type node-type
                             :message (str "cross-reference to \"" label "\" points at a "
                                           (name node-type)
                                           ", which steps no LaTeX counter, so native-mode"
                                           " output prints ?? for it: build with"
                                           " --computed-numbers, or use a directive with a"
                                           " mapped environment")})))))
          nodes)))

(defn- image-path-hazards
  "What, if anything, `src` carries that `\\includegraphics` cannot: a
  literal backslash (read as the start of a LaTeX command -- confirmed
  empirically, `![alt](pic\\x.png)` fails a real pdflatex with
  \"Undefined control sequence\") and/or an unbalanced brace (see
  `balanced-braces?`'s own docstring for the matching `\\href` failure;
  `\\includegraphics`'s own argument grouping is exactly as vulnerable to
  it). A seq of human-readable phrases, in that order, or nil when `src`
  has neither -- used by `unescapable-image-path-diagnostics` (TASK-78)
  to build one message naming everything wrong with a given path, rather
  than one warning per hazard for the same path."
  [^String src]
  (seq (cond-> []
         (str/includes? src "\\")
         (conj "a backslash, which LaTeX reads as the start of a command")
         (not (balanced-braces? src))
         (conj "an unbalanced brace, which desynchronizes \\includegraphics's own argument grouping"))))

(defn unescapable-image-path-diagnostics
  "One `{:type :unescapable-image-path :src :message}` warning per Image
  in `document` (a LOWERED Document) whose `:src` carries a character
  `\\includegraphics` cannot carry (TASK-78, `image-path-hazards`), in
  document order.

  Unlike a Link `:target` or a bibliography `url`/`doi` field
  (`latex-safe-url`), an Image `:src` is a FILENAME rather than a URI:
  percent-encoding a raw backslash there would look up a file that does
  not exist on disk -- graphicx resolves `:src` against the real
  filesystem, and composing a different name from the one an author
  wrote is exactly the silent-corruption hazard `untypesettable-
  character-diagnostics` already documents refusing for the same field.
  So there is no fix to apply here, only a warning naming what is wrong
  and where -- the same trade this emitter makes for every other
  character or construct it cannot safely rewrite on an author's behalf."
  [document]
  (into []
        (comp (filter #(= :image (:t %)))
              (keep (fn [{:keys [src]}]
                      (when-let [hazards (image-path-hazards src)]
                        {:type :unescapable-image-path
                         :src src
                         :message (str "image path \"" src "\" contains "
                                       (str/join " and " hazards)
                                       "; \\includegraphics will not compile it")}))))
        (ast-nodes document)))

(def ^:private origin-words
  "How `untypesettable-character-diagnostics` names each place emitted
  text can come from (TASK-76 AC #2). The generated bibliography is the
  one that matters most: `csl-json->bibtex` writes a file from CSL-JSON
  the author never opens, so \"somewhere in your document\" would send
  them looking in the wrong file."
  {:document "the document"
   :bibliography "the generated bibliography"})

(defn- excerpt
  "A short one-line window of `text` around index `i`, for a diagnostic
  that has to say WHERE in a 300-page document the character is. Runs of
  whitespace collapse to single spaces so an excerpt spanning a line
  break still prints on one line.

  The window is moved to code-point boundaries before slicing: cut in
  the middle of a surrogate pair it printed a lone half, which a
  terminal renders as a replacement character (found by review)."
  [^String text i]
  (let [start (.offsetByCodePoints text (max 0 (- i 24)) 0)
        end (min (.length text) (+ i 25))
        end (if (and (< end (.length text)) (Character/isLowSurrogate (.charAt text end)))
              (inc end)
              end)]
    (-> (subs text start end)
        (str/replace #"\s+" " ")
        str/trim)))

(def ^:private untypeset-command-re
  "The commands this emitter writes whose braced argument is an ADDRESS
  rather than typeset text -- a URL, a file on disk, a BibTeX database
  name. TeX never sets these characters, so a character outside
  `typesettable-ranges` inside one compiles perfectly well and must not
  be reported (found by review: a link to a Russian Wikipedia article
  drew six warnings, each promising no PDF, on a document that built).

  A `.bib`'s own `url` and `doi` fields are the same thing in the other
  file, and are matched here too -- one pass over either kind of output."
  #"(?s)\\(?:href|url|includegraphics|bibliography|input)(?:\[[^\]]*\])?\{[^{}]*\}|(?m)^\s*(?:url|doi)\s*=\s*\{[^{}]*\}")

(def ^:private tex-comment-re
  "A TeX comment: an unescaped `%` to the end of its line. TeX reads none
  of it, so a character there is not one it has to set. The lookbehind
  keeps an escaped `\\%` -- what `escape-tex` makes of an authored
  percent sign -- from swallowing the rest of a line of real prose."
  #"(?m)(?<!\\)%.*$")

(def ^:private verbatim-boundary-re
  "The `\\begin`/`\\end` of a verbatim-family environment (see
  `verbatim-env` for why there are two spellings), with `end` captured so
  a line can be told from the other.

  Inside one, `%` is a percent sign like any other character rather than
  a comment -- which is the one place `tex-comment-re` would be wrong,
  and wrong in the direction this task exists to prevent: a code block
  holding `x = 100 % 7  # alpha` had everything after the `%` masked, so
  a character pdflatex refuses went unreported (found by review)."
  #"\\(begin|end)\{verbatim\*?\}")

(defn- mask-untypeset
  "`text` with every region TeX will not typeset replaced by spaces, so
  `untypesettable-character-diagnostics` looks only at what actually has
  to be set. Spaces rather than deletion: every index in the result still
  addresses the same character in the original, which is what lets the
  diagnostic quote a real excerpt around a real offset.

  Comment-masking is skipped inside a verbatim environment, where a `%`
  is a printed character rather than a comment; address-masking is not,
  since neither `\\href` nor `\\includegraphics` means anything in there
  either -- a line matching one is verbatim text that happens to look
  like a command, and its argument is as typeset as the rest of it. That
  is a narrow, deliberate under-report: a code block showing an
  `\\includegraphics` line whose filename holds a character pdflatex
  cannot set goes unreported."
  [text]
  (letfn [(blank [m] (apply str (repeat (count m) \space)))]
    ;; Addresses first: a raw `%` inside a URL would otherwise read as a
    ;; comment and mask the rest of that line of prose with it.
    (let [addressed (str/replace text untypeset-command-re blank)]
      ;; `split` with a -1 limit, not `split-lines`: every index in the
      ;; result has to address the same character as in the original, and
      ;; `split-lines` drops trailing empties (and treats \r\n as one
      ;; separator), either of which would shift the excerpt offsets.
      (->> (str/split addressed #"\n" -1)
           (reduce (fn [[lines verbatim?] line]
                     (let [boundary (second (re-find verbatim-boundary-re line))]
                       [(conj lines (if verbatim?
                                      line
                                      (str/replace line tex-comment-re blank)))
                        (case boundary
                          "begin" true
                          "end" false
                          verbatim?)]))
                   [[] false])
           first
           (str/join "\n")))))

(defn untypesettable-character-diagnostics
  "One `{:type :untypesettable-character :char :code-point :origin
  :message}` warning per distinct character in `text` that pdflatex
  cannot set (`typesettable?`), in code-point order (TASK-76). `origin`
  is `:document` or `:bibliography`, and names the file the author has to
  go and look in (`origin-words`).

  `text` is EMITTED output -- a `.tex` or a generated `.bib` -- not the
  AST, and that is the point rather than a shortcut. Half of what reaches
  a LaTeX build was never typed into the `.hdoc`: `csl-json->bibtex`
  writes a whole database out of CSL-JSON, and that is exactly where the
  character that started this task came from (pandoc renders BibTeX's own
  `\\'{\\i}` as U+0131 followed by U+0301, which nothing in the pipeline
  noticed and which no chapter containing that citation would compile).
  Scanning what is about to be written catches generated and authored
  text with one pass, and cannot drift out of step with the emitter the
  way a parallel walk over the AST would.

  A warning rather than an error, deliberately, and for the same reason
  `haselnuss.resolver`'s dangling-reference diagnostic is one: the
  document IS written, and a document that fails to compile with a
  warning naming the character is strictly better than the silence this
  replaces -- the build exited 0, said it wrote the file, and pdflatex
  died two commands later in someone else's output. It is also not this
  emitter's place to refuse a document over a character; the author may
  know exactly what they are doing and be about to run LuaLaTeX on it.

  Prose that has already been through `escape-tex` has been composed
  there, so a decomposed sequence that HAS a canonical composition never
  reaches here (AC #3) -- only what genuinely cannot be set does.

  What TeX will not typeset at all is masked out first
  (`mask-untypeset`): a comment, and the address arguments of `\\href`,
  `\\includegraphics` and their kind. Those characters reach the PDF as
  bytes rather than as glyphs, so warning about them would be the
  crying-wolf case AC #4 exists to prevent."
  [^String text origin]
  ;; Walked by CODE POINT rather than by char: a character outside the
  ;; BMP is a surrogate pair in a Java string, and reporting one as two
  ;; characters with no names would be worse than saying nothing.
  (let [^String typeset (mask-untypeset text)
        firsts (loop [i 0 acc {}]
                 (if (>= i (.length typeset))
                   acc
                   (let [cp (.codePointAt typeset i)]
                     (recur (+ i (Character/charCount cp))
                            (if (or (typesettable? cp) (contains? acc cp))
                              acc
                              (assoc acc cp i))))))]
    (mapv (fn [[cp i]]
            (let [ch (String. (Character/toChars cp))]
              {:type :untypesettable-character
               :char ch
               :code-point cp
               :origin origin
               :message (str (origin-words origin (name origin))
                             " contains " ch " (U+" (format "%04X" cp) " "
                             (Character/getName cp)
                             "), which the emitted LaTeX cannot typeset: pdflatex stops with"
                             " \"Unicode character not set up for use with LaTeX\" and writes"
                             " no PDF at all. Near: \"" (excerpt text i) "\"")}))
          (sort-by key firsts))))

(defn directive-lexicon-kinds
  "A plain `{directive-name kind-keyword}` map read off `environments`'
  own `:lexicon-kind` entries (default `default-directive-environments`),
  e.g. `{\"lemma\" :lem, \"proof\" :prf, ...}`. An entry declaring no
  `:lexicon-kind` contributes nothing.

  This exists for `haselnuss.resolver/directive-kind-diagnostics`
  (TASK-48), which warns when a directive's `attr.id` prefix names a
  different kind than the directive itself does -- `{lemma #thm:x}`,
  which HTML numbers from the prefix and native-mode LaTeX numbers from
  the environment's own counter, producing two different numbers for one
  node with no warning.

  The conversion lives here, beside the table it reads, rather than in
  the resolver: the resolver deliberately knows nothing about LaTeX
  environments, so what it takes is this plain map. Passing it through
  is also what makes a caller-supplied table judged by its OWN
  `:lexicon-kind` entries rather than by the built-ins (TASK-48 AC #4) --
  the same table a caller hands `emit-document`'s
  `:directive-environments` opt is the one to hand this."
  ([] (directive-lexicon-kinds default-directive-environments))
  ([environments]
   (into {}
         (keep (fn [[directive-name spec]]
                 (when-let [kind (:lexicon-kind spec)]
                   [directive-name (keyword kind)])))
         environments)))

(defn float-directive-names
  "The set of directive names in `environments` (default
  `default-directive-environments`) this emitter lays out as a FLOAT of
  its own -- the `:float` entries that are not panels of another one:
  `#{\"listing\" \"algorithm\" \"figure\"}` in the built-in table.

  `haselnuss.resolver` takes this as the set of directives its
  list-of-figures/list-of-tables derivations may list (TASK-59). The
  selector cannot be \"any directive whose id prefix names the kind\":
  numbering is id-prefix-driven by design, so `{admonition #fig:careful}`
  numbers as a figure and would otherwise be listed as one, printing a
  caption-less \"Figure 1.1\" in a list native LaTeX's own `.lof` has no
  entry for (found by review). What belongs in a list of figures is what
  the emitter typesets as a figure float, which is a fact this table
  holds and the resolver has no way to know.

  Panels are excluded, and that is the same rule from the other side:
  `\\listoffigures` lists the figure, never its subfigures."
  ([] (float-directive-names default-directive-environments))
  ([environments]
   (into #{}
         (keep (fn [[directive-name spec]]
                 (when (and (:float spec) (not (:sub spec))) directive-name)))
         environments)))

(defn sublabel-directive-names
  "The set of directive names in `environments` (default
  `default-directive-environments`) whose nodes are PANELS of the float
  above them -- the `:sub` entries, `#{\"subfigure\"}` in the built-in
  table (TASK-56).

  Here beside the table it reads, and for the same reason
  `directive-lexicon-kinds` is: `haselnuss.resolver` deliberately knows
  nothing about LaTeX environments, so what it takes is this plain set
  (`number-document`'s own `sublabel-names`). Without it the resolver
  would have to letter every same-kind nesting -- including a theorem
  inside a theorem, which every LaTeX class numbers 1, 2 and which this
  emitter has no panel machinery for at all."
  ([] (sublabel-directive-names default-directive-environments))
  ([environments]
   (into #{}
         (keep (fn [[directive-name spec]] (when (:sub spec) directive-name)))
         environments)))

(defn environment-renderer
  "The marker `register-environments` stores as a mapped directive's own
  `:latex` renderer (TASK-24). Registering *something* there is the whole
  point: `haselnuss.lower/lower`'s own AC #1 branch keeps a `:directive`
  for a target only when the registry reports a renderer for it, so
  without this a mapped directive would fall through to its fallback
  contract and the mapping could never take effect. But this emitter does
  not render a mapped directive *through* that value -- it renders from
  `default-directive-environments`, where the recursive `render-blocks`
  it needs for the directive's own content lives, which the registry's
  `(fn [directive target])` signature gives an extension no way to reach
  (see `haselnuss.extensions.collapsable`'s own docstring on that same
  limitation). `render-directive` therefore recognizes this marker by
  identity and never calls it.

  Deliberately a *function that throws*, not an inert value (TASK-24
  review findings #3/#4). It has to survive two different consumers:
  `render-directive` compares it by identity, so its callability is
  irrelevant there; but every *other* consumer of `:emit` -- notably
  `haselnuss.emit.html/render-directive` -- calls whatever non-nil value
  it finds, and the registry is one table shared across targets. An
  inert keyword (this function's first version) is itself `IFn`, so such
  a consumer would splice the literal `:latex` into its output and ship
  silently corrupted HTML; a function that throws makes the same mistake
  a loud, named error instead."
  [directive target]
  (throw (ex-info
          (str "the latex directive-environment marker was called as a renderer for directive "
               (pr-str (:name directive)) " on target " (pr-str target)
               ": it is a marker recognized by haselnuss.emit.latex/render-directive only, "
               "never a renderer for another target")
          {:type ::environment-marker-called :block directive :target target})))

(defn register-environments
  "Registers `environments` (default `default-directive-environments`)
  into `registry`, so `haselnuss.lower/lower` keeps every mapped
  directive as a `:directive` for the `:latex` target (see
  `environment-renderer`). Required, not optional, for a mapping to take
  effect at all.

  Merges into an existing entry of the same name rather than replacing
  it -- only `[:emit :latex]` is written (TASK-24 review finding #5).
  `haselnuss.registry/register` is documented last-write-wins, so
  building these as whole extension maps and folding them in with
  `register-all` would silently discard a same-named extension's own
  `:html` renderer, `:kind` lexicon fragment and `:lower` rule. Since the
  registry is one table shared across targets, that is exactly what
  turning on LaTeX theorem/admonition environments would have done to an
  `admonition` extension that also renders HTML.

  A name with no existing entry is registered fresh, carrying only
  `:emit {:latex environment-renderer}`: no `:kind` (a mapped
  directive's numbering comes from its own `attr.id` prefix like any
  other node, TASK-11) and no `:lower` (it has a native representation
  and never needs one)."
  ([registry] (register-environments registry default-directive-environments))
  ([registry environments]
   (reduce (fn [reg directive-name]
             (if-let [entry (registry/lookup reg directive-name)]
               (assoc reg directive-name (assoc-in entry [:emit :latex] environment-renderer))
               (registry/register reg {:name directive-name
                                       :emit {:latex environment-renderer}})))
           registry
           (sort (keys environments)))))

(defn panel-columns
  "How many panels of a multi-panel float share one row (TASK-56 AC #4):
  the float directive's own `columns` attribute as a positive long,
  defaulting to **1** -- one panel per row.

  Public, and called by `haselnuss.cli`'s own HTML-side degradation
  rather than re-read there, because the whole point of the attribute is
  that both targets arrange the panels the SAME way: two readers of one
  prop is two places for a default to drift, and a figure that reads
  \"(a) (b) / (c)\" in the PDF and \"(a) / (b) (c)\" in HTML is a
  document that disagrees with itself about how it is read.

  One per row is the default because it is the only arrangement no
  document can be hurt by: panels stack, nothing overflows the text
  block, and the author who wants them side by side says so. Picking 2
  -- or \"as many as fit\" -- would be the target deciding the
  arrangement, which is exactly what AC #4 rules out.

  A `columns` that is not a positive integer raises `ex-info` (`:type
  ::invalid-columns`) naming the float, rather than quietly falling back
  to the default: `columns=\"two\"` is a typo whose only symptom would
  otherwise be a silently different layout."
  [directive]
  (let [raw (get-in directive [:attr :props "columns"])
        columns (when raw (parse-long (str/trim raw)))]
    (cond
      (nil? raw) 1
      (and columns (pos? columns)) columns
      :else
      (throw (ex-info (str "columns=" (pr-str raw) " on directive " (pr-str (:name directive))
                           " is not a positive integer")
                      {:type ::invalid-columns
                       :name (:name directive)
                       :id (get-in directive [:attr :id])
                       :columns raw})))))

(defn- sub-float-spec
  "The `:sub` float entry (TASK-56) Block `block` is a panel of, or nil
  when `block` is not a panel at all -- `block`'s own directive name
  looked up in `ctx`'s `:directive-environments` table. Read off the
  table rather than compared against a directive name here, so a
  document replacing that table brings its own panels with it and this
  namespace goes on naming none."
  [ctx block]
  (when (= :directive (:t block))
    (let [spec (get (:directive-environments ctx) (:name block))]
      (when (:sub spec) spec))))

(defn- panel-width
  "The `\\linewidth` fraction one panel takes when `columns` of them share
  a row: `\"0.48\"` for two, `\"0.32\"` for three.

  Integer arithmetic and deliberately not `format`: `\"%.2f\"` follows
  the default locale, which prints `0,48` under a comma-decimal one
  (pt-BR, this project's own second document language) and hands LaTeX a
  width it cannot read. Ninety-six hundredths rather than a hundred
  leaves the `\\hfill` between panels something to distribute, and
  flooring the division keeps a full row from summing past
  `\\linewidth` for any row narrower than a hundredth of the line -- past
  96 columns the clamp takes over and the row does overflow, which is
  far beyond the 26 panels `\\alph` itself can letter (see
  `haselnuss.resolver`)."
  [columns]
  (let [hundredths (max 1 (quot 96 (max 1 columns)))]
    (str "0." (when (< hundredths 10) "0") hundredths)))

(defn- panel-separator
  "What separates panel `n` (0-based) from the one before it inside a
  float laid out `columns` to a row: nothing at all before the first,
  `\\\\` -- a plain row break, which is what a row break inside a float
  is in LaTeX -- when this panel opens a new row, and `\\hfill` when it
  continues one.

  Single newlines around it, never a blank one: a blank line between
  two `subfigure` boxes ends the paragraph they share and stacks them
  vertically whatever widths they were given."
  [columns n]
  (cond
    (zero? n) ""
    (zero? (mod n columns)) "\n\\\\\n"
    :else "\n\\hfill\n"))

(declare render-float)

(defn- render-float-blocks
  "The CONTENT of float `directive`: its own `:blocks`, with any `:sub`
  panels among them (TASK-56) rendered as sized, row-arranged
  sub-floats instead of as freestanding blocks, and everything else
  rendered by the ordinary `render-block` visitor.

  A float holding no panels at all -- every listing, every algorithm,
  every single-image figure -- takes `render-blocks` unchanged, blank
  lines and all, so nothing about this task changes what those emit,
  and a `columns` attribute on such a float is never even read.

  Non-panel content between two panels ENDS the row it interrupts, and
  is set as its own paragraph: it is a paragraph the author wrote, and
  running it through the row would flow it around the panel boxes
  (visible in the compiled PDF, found by review). Ending the row is
  also exactly what `haselnuss.cli/panel-rows` does for the degraded
  target, which is what keeps the two arrangements the same -- the
  point of AC #4."
  [ctx directive]
  (let [;; Everything rendered from here down is INSIDE a float, which
        ;; is the one thing `render-figure` and `render-float` have to
        ;; know: LaTeX refuses a float inside a float outright (\"Not in
        ;; outer par mode\", fatal, no PDF).
        ctx (assoc ctx :in-float true)]
    (if-not (some (partial sub-float-spec ctx) (:blocks directive))
      (render-blocks ctx (:blocks directive))
      (let [columns (panel-columns directive)
            width (panel-width columns)]
        (:out
         (reduce (fn [{:keys [out n panel?] :as acc} block]
                   (if-let [spec (sub-float-spec ctx block)]
                     (assoc acc
                            :out (str out
                                      (cond (str/blank? out) ""
                                            panel? (panel-separator columns n)
                                            ;; The first panel of a row
                                            ;; opened by intervening
                                            ;; prose: a blank line, so
                                            ;; the row starts a
                                            ;; paragraph of its own.
                                            :else "\n\n")
                                      (render-float ctx spec block width))
                            :n (inc n)
                            :panel? true)
                     (assoc acc
                            :out (str out (when-not (str/blank? out) "\n\n")
                                      (render-block ctx block))
                            :n 0
                            :panel? false)))
                 {:out "" :n 0 :panel? false}
                 (:blocks directive)))))))

(defn- render-float
  "Renders a `:float` directive (TASK-57's own entry shape) as its
  environment: content first, then `caption-command`'s `\\caption` built
  from the directive's own `caption` attribute -- the same function, and
  so the same \"Listing 3: text\" shape and the same computed-mode
  `\\caption*`, that a Figure and a Table already use -- then the label.

  `width`, when given, is the `\\linewidth` fraction this float occupies
  as a PANEL of another one (TASK-56): it becomes the `subfigure`
  environment's own required argument. A panel also always emits its
  caption command, empty caption or not, because that command is what
  makes `subcaption` print the panel's `(a)`/`(b)` letter -- 19 of the
  49 panels in the thesis this milestone is scoped from have no caption
  text and are still referred to by letter in the prose (AC #2).

  Three shapes are refused here rather than emitted, each because
  LaTeX cannot typeset it at all and the alternative is a build error
  that says which directive is misplaced or one that does not:

  - `::orphan-panel` -- a `:sub` entry reached with NO width, meaning
    it is not a direct child of a float laying panels out. `subcaption`
    itself refuses the shape (\"Package subcaption Error: subfigure
    outside float\", fatal, no PDF). Silently promoting it to a float of
    its own is the third option and the worst: it would renumber the
    document around a typo.
  - `::nested-float` -- a non-panel float inside another float, the
    same failure `render-figure` refuses for a Figure Block (\"Not in
    outer par mode\", fatal, no PDF). A listing inside a figure is the
    reachable spelling.
  - `::unnumbered-panel-parent` -- an id-bearing panel that the
    resolver numbered as an ordinary float rather than as a sublabel,
    which happens when the figure holding it carries no id of its own.
    `subcaption` letters a panel against whatever the figure counter
    happens to hold, so the PDF prints \"Figure 2a\" for a panel HTML
    calls \"Figure 3\" -- two documents disagreeing silently, with the
    letters composed onto some other figure's number (found by review).
    The fix the message names is the real one: give the enclosing
    figure an id.

  An id-LESS captioned float gets `\\caption*` even in native mode,
  because LaTeX's float counter steps for every `\\caption` while the
  resolver numbers only id-bearing nodes, and one unlabeled listing
  between two labeled ones otherwise left the PDF a number ahead of HTML
  for the same node (found by review). The same holds a panel's letters
  in step with the resolver's. An id-BEARING float with no caption gets
  a `\\refstepcounter` instead, for the reason `render-figure` records
  for the identical shape: a float steps its counter from `\\caption`
  alone, so the `\\label` would otherwise bind to whatever was stepped
  last."
  [ctx {:keys [environment] a-sub :sub} directive width]
  (let [directive-id (get-in directive [:attr :id])
        entry (get (:labels ctx) directive-id)]
    (when (and a-sub (not width))
      (throw (ex-info (str "directive " (pr-str (:name directive))
                           " (id " (pr-str directive-id)
                           ") is a float panel that is not a direct child of a float;"
                           " a panel belongs immediately inside the directive that lays"
                           " panels out")
                      {:type ::orphan-panel
                       :name (:name directive)
                       :id directive-id})))
    (when (and (not a-sub) (:in-float ctx))
      (throw (ex-info (str "directive " (pr-str (:name directive))
                           " (id " (pr-str directive-id)
                           ") is a float nested inside another float, which LaTeX cannot"
                           " typeset")
                      {:type ::nested-float
                       :name (:name directive)
                       :id directive-id})))
    (when (and a-sub entry (not (:sublabel entry)))
      (throw (ex-info (str "panel " (pr-str directive-id)
                           " was numbered as a float of its own rather than as a panel,"
                           " because the figure holding it carries no id;"
                           " give that figure an id so both targets letter the panel"
                           " against the same number")
                      {:type ::unnumbered-panel-parent
                       :name (:name directive)
                       :id directive-id}))))
  (let [id (get-in directive [:attr :id])
        caption (get-in directive [:attr :props "caption"])
        computed? (:computed-numbers ctx)
        cap (caption-command ctx id (when (seq caption) [{:t :str :text caption}]))
        ;; A panel with no caption text still needs the command itself:
        ;; the letter comes from `\caption`, not from the text in it.
        cap (or cap (when (and a-sub (not computed?)) "\\caption{}"))
        cap (if (and cap (not id) (not computed?))
              (str/replace-first cap "\\caption{" "\\caption*{")
              cap)]
    (str "\\begin{" environment "}"
         ;; `subfigure` takes its width as a required argument.
         (when a-sub (str "{" width "\\linewidth}"))
         "\n"
         (render-float-blocks ctx directive)
         "\n"
         (when cap (str cap "\n"))
         (when (and id (not cap) (not computed?))
           (str "\\refstepcounter{" environment "}"))
         (unnumbered-label ctx (:attr directive))
         "\n\\end{" environment "}")))

(defn- render-environment
  "Renders Directive `directive` as its mapped LaTeX environment `spec`
  (TASK-24 AC #1), its own `:blocks` rendered through the ordinary
  `render-blocks` visitor so nested content -- including another mapped
  directive -- works with no special casing.

  Native mode uses `spec`'s own `:environment`. Computed-numbers mode
  swaps a `:counter` environment for an uncounted one taking the head as
  its argument -- `spec`'s own `:unnumbered-environment` when it names
  one, else `unnumbered-environment` -- whose head is the resolver's own
  label text for this node's id from `ctx`'s `:labels` (e.g. \"Theorem
  4\"), or `spec`'s own `:title` word alone when the resolver numbered
  nothing here. So a `\\hyperref` reading \"Theorem 4\" points at a head
  reading \"Theorem 4\". An entry with no counter at all is used
  unchanged in both modes; there is nothing to disagree about.

  `:unnumbered-environment` exists because `:counter` would otherwise
  carry two meanings at once (found by review): \"steps a LaTeX
  counter\", which is true of every built-in entry since TASK-40 and is
  what drives the native-mode `\\label`, and \"its uncounted form is the
  bold-head/italic-body `hnunnumbered`\", which is only true of the
  `\\newtheorem` family. Routing `proof` and `admonition` through
  `hnunnumbered` in computed mode discarded exactly the typesetting
  those two entries exist for -- amsthm's QED box, and the admonition's
  `quote` indentation -- and reopened the HTML/LaTeX structural
  disagreement `haselnuss.cli/environment-title` refuses to ship.

  A `:float` entry (TASK-57) takes a different shape entirely and is
  handled first, by `render-float` -- captions, panels and their
  arrangement are its own, and none of the mode-and-counter reasoning
  below applies to a construct whose number comes from a `\\caption`.

  Whether an id-bearing mapped directive gets a `\\label` at all is
  mode- and counter-dependent (TASK-24 review finding #1, a confirmed
  silently-wrong-output bug in this function's first version):

  - Native mode, counter-carrying environment: `\\label` immediately
    after `\\begin{...}`, LaTeX's own idiom for a theorem-like
    environment -- that is what binds it to the environment's own
    counter, and it is what makes the built-in `thm` kind a real
    `\\Cref` target (TASK-23 review finding #6, deferred to this task).
  - Native mode, counter-less environment (any custom
    `\\newenvironment`-based entry that declares no `:counter`; no
    built-in is one since TASK-40): **no `\\label` at all**.
    Emitting one looks harmless but is worse than useless: with no
    counter stepped, LaTeX binds the label to whatever counter *was*
    last stepped, so a `\\Cref` to a `proof` prints \"Section 2\" -- a
    confident wrong answer, with zero build warnings, confirmed
    empirically. Omitting the label makes the same reference print
    LaTeX's own `??` plus a real \"Reference undefined\" warning:
    visibly broken beats silently wrong, and it is the same degradation
    this namespace's docstring already records for any other
    unanchorable target.
  - Computed mode, either kind: `\\phantomsection\\label`. Safe here
    precisely because the printed reference text comes from the
    resolver, not from a counter -- the label serves only as the
    `\\hyperref` jump target, and `\\phantomsection` is what makes that
    target this node rather than the enclosing section (the same reason
    `unnumbered-label` uses it for `\\section*`/`\\caption*`)."
  [ctx {:keys [environment counter title] a-float :float unnumbered :unnumbered-environment
        :as spec}
   directive]
  (if a-float
    (render-float ctx spec directive nil)
    (let [computed? (:computed-numbers ctx)
          numbered? (boolean (and counter (not computed?)))
          env (if (and counter computed?) (or unnumbered unnumbered-environment) environment)
          head (when (and counter computed?)
                 (str "{" (escape-tex (or (:text (get (:labels ctx) (get-in directive [:attr :id])))
                                          title))
                      "}"))
          label (when (or numbered? computed?) (attr-label (:attr directive)))]
      (str "\\begin{" env "}" head
           (when (seq label) (str (when computed? "\\phantomsection") label))
           "\n"
           (render-blocks ctx (:blocks directive))
           "\n\\end{" env "}"))))

(defn- toc-line
  "One line of a computed-mode table of contents: the entry's own number
  and heading, indented one em per level below the first, wrapped in a
  `\\hyperref` when it has an id to point at.

  `\\noindent` and `\\par` rather than a nested `itemize`: LaTeX gives up
  at four levels of list nesting (\"Too deeply nested\"), which a thesis
  with subsubsections reaches, and a table of contents is a run of lines
  rather than a bulleted list anyway. `\\hspace*` because a leading
  `\\hspace` at the start of a line is discarded.

  The `{}` after `\\noindent` is load-bearing (TASK-77). An entry at depth
  0 with no id has neither an `\\hspace*` nor a `\\hyperref` between the
  control word and its own text, so `\\noindent` ran straight into the
  heading and TeX read `\\noindentIntroduction` as one undefined command --
  one unlabelled chapter anywhere in a document was enough to produce no
  PDF at all."
  [ctx {:keys [id number heading level]}]
  (let [body (str (when number (str (escape-tex number) "\\quad "))
                  (render-inlines ctx (derived-lists/entry-inlines heading)))
        depth (max 0 (dec (or level 1)))]
    (str "\\noindent{}"
         (when (pos? depth) (str "\\hspace*{" depth "em}"))
         (if id (str "\\hyperref[" (label-id id) "]{" body "}") body)
         "\\par\n")))

(defn- toc-lines
  "`toc-line` over the whole TOC tree in document order -- an entry, then
  its own `:children`, recursively. Flattened rather than nested,
  because the nesting is already visible in each line's own indentation
  and in the numbers themselves, and because LaTeX's own
  `\\tableofcontents` sets a table of contents as exactly this: a run of
  lines."
  [ctx entries]
  (mapcat (fn [entry] (cons (toc-line ctx entry) (toc-lines ctx (:children entry))))
          entries))

(defn- float-list-line
  "One line of a computed-mode list of figures or list of tables: the
  entry's own label text and caption -- \"Figure 1.1: A tree\" -- the same
  shape `caption-command` prints on the float itself, wrapped in a
  `\\hyperref` when it has an id.

  `\\noindent{}` for the reason `toc-line` gives: without the empty group,
  an entry with no id runs the control word into its own text."
  [ctx {:keys [id text caption]}]
  (let [body (str (escape-tex text)
                  (when (seq caption)
                    (str ": " (render-inlines ctx (derived-lists/entry-inlines caption)))))]
    (str "\\noindent{}"
         (if id (str "\\hyperref[" (label-id id) "]{" body "}") body)
         "\\par\n")))

(defn- render-derived-list
  "Renders one list placeholder -- `{toc}`, `{list-of-figures}`,
  `{list-of-tables}` (TASK-59) -- in whichever of the two modes this
  emitter is in, and the difference between them is the whole reason
  this is not a registry `:lower` rule:

  - NATIVE mode emits `spec`'s own command (`\\tableofcontents`,
    `\\listoffigures`, `\\listoftables`) and lets LaTeX build the list
    from its own counters. That is right here precisely because in this
    mode LaTeX's numbers ARE the document's -- the same delegation
    `\\Cref` and BibTeX already get.
  - COMPUTED-NUMBERS mode renders the derived list itself, as lines of
    text with `\\hyperref` links, exactly as it renders a computed
    cross-reference. Those commands cannot be used there: that mode
    bypasses LaTeX's counters everywhere else -- starred sectioning
    commands, `\\caption*` on every float -- and `\\caption*` writes
    NOTHING to the `.lof` file (confirmed by compiling a document with
    one `\\caption` and one `\\caption*` and reading the `.lof`). So
    `\\listoffigures` there would print an EMPTY list, not merely a
    differently-numbered one.

  The heading in computed mode is the starred sectioning command of the
  document's own top division, matching what `\\tableofcontents` sets
  natively and what a front-matter block already does here."
  [ctx spec directive]
  (if-not (:computed-numbers ctx)
    ;; `\phantomsection` before the label in BOTH modes, not just the
    ;; computed one (found by review): a list steps no counter of its
    ;; own in either, so a bare `\label` would bind to whatever was
    ;; stepped last, and `:::{toc #contents}` was an anchor in HTML and
    ;; in computed mode and no anchor at all in the default build.
    (str (when (get-in directive [:attr :id])
           (str "\\phantomsection" (attr-label (:attr directive)) "\n"))
         (:command spec))
    (let [entries (get (:derived-lists ctx) (:derivation spec) [])
          lines (if (= :toc (:derivation spec))
                  (toc-lines ctx entries)
                  (map (partial float-list-line ctx) entries))]
      (str "\\" (if (:chapters ctx) "chapter" "section") "*{"
           (escape-tex (derived-lists/heading-word spec (:lang ctx))) "}\n"
           (unnumbered-label ctx (:attr directive))
           (apply str lines)))))

(defn- render-directive
  "Renders Directive `directive`, in the order this namespace resolves its
  three possible representations:

  1. A real `:latex` renderer registered for `directive`'s own `:name`
     in `ctx`'s `:registry` -- called with the `(fn [directive target]
     -> string)` signature `haselnuss.extensions.collapsable`/
     `haselnuss.emit.html` established, its return value spliced in
     verbatim. First, so a custom extension can always override a
     built-in environment mapping for the same name. \"Real\" means
     anything callable that is not `environment-renderer`: recognizing
     the marker by identity, rather than by asking whether the value is
     `fn?`, is what keeps an ordinary Var or multimethod renderer
     working (TASK-24 review finding #4 -- `(fn? #'my-ns/render)` is
     false, so a `fn?` test silently rejected two normal idioms the
     HTML emitter still accepts).
  2. Otherwise, an entry for that name in `ctx`'s `:directive-
     environments` mapping table (TASK-24) -> `render-environment`.
  3. Otherwise `ex-info` (`:type ::unsupported-block`) naming the
     directive, exactly like any other unsupported block type. In a full
     `parse -> resolve -> lower -> emit` pipeline this branch is
     unreachable: a directive with neither representation is already
     handled by `haselnuss.lower/lower`'s own fallback contract, or
     aborted there with `::no-representation` (TASK-24 AC #3), long
     before this emitter sees it."
  [{:keys [registry directive-environments] :as ctx} {directive-name :name :as directive}]
  (let [renderer (some-> registry (registry/lookup directive-name) (registry/renderer :latex))
        spec (get directive-environments directive-name)
        list-spec (derived-lists/spec directive)]
    (cond
      ;; A list placeholder is drawn here, from the resolver's own
      ;; derivations or by handing the job to LaTeX (TASK-59), rather
      ;; than through the marker the registry holds for it -- which of
      ;; the two depends on the emission MODE, something no registry
      ;; renderer is given. First, like every other case here, so the
      ;; marker is never called.
      list-spec (render-derived-list ctx list-spec directive)

      (and (ifn? renderer) (not= renderer environment-renderer))
      (renderer directive :latex)

      spec (render-environment ctx spec directive)

      :else
      (throw (ex-info
              (str "latex emitter has no representation for directive " (pr-str directive-name)
                   ": no :latex renderer in the registry, and no entry in the"
                   " directive-environment mapping table")
              {:type ::unsupported-block :block directive})))))

(defn- bibliography-block
  "The native-mode replacement (TASK-23 AC #2) for the resolver-generated
  bibliography Section: `\\bibliographystyle{bib-style}` plus
  `\\bibliography{bib-resource}`, the pair that makes BibTeX build the
  reference list itself from the same bibliography source, instead of
  this emitter printing `haselnuss.resolver/bibliography-section`'s own
  already-formatted text.

  `bib-resource` is spliced raw, not `escape-tex`-ed -- like a Link
  `:target` or an Image `:src`, it is documented as a filename BibTeX
  itself resolves (see `bib-resource-name` for how it is derived from
  `meta.bibliography` by default, and why it names a BibTeX `.bib`
  database rather than the CSL-JSON file the resolver itself reads)."
  [{:keys [bib-resource bib-style bibliography-id bibliography-referenced?]}]
  (str
   ;; `\phantomsection` + `\label` so a reference to the generated
   ;; section still lands here (TASK-64). Safe where a `\label` normally
   ;; is not, and for the same reason computed mode's own anchors are:
   ;; what a reference to this prints is the word "Bibliography", never
   ;; a number, so there is no counter for the label to bind to wrongly
   ;; -- see `haselnuss.resolver/bibliography-label-entry`.
   ;; Only when the document really does reference it, so a document
   ;; that never mentions its reference list emits exactly what it
   ;; emitted before that reference could resolve.
   (when (and bibliography-id bibliography-referenced?)
     (str "\\phantomsection" (attr-label {:id bibliography-id}) "\n"))
   "\\bibliographystyle{" bib-style "}\n"
   "\\bibliography{" bib-resource "}"))

(defn- generated-bibliography-section?
  "True when Section `block` is the bibliography Section
  `haselnuss.resolver/resolve-citations` generated -- its own `attr.id`
  matching `ctx`'s `:bibliography-id` opt (that pass's own returned
  value) -- *and* this document is emitting a native BibTeX bibliography
  at all (`ctx`'s `:native-bibliography`, see `emit-document`).

  Both conditions matter, and they degrade together with `render-cite`'s
  own identical `:native-bibliography` check, so a document never ends up
  half-delegated (TASK-23 review finding #4). Without `:bibliography-id`
  this namespace has no way to tell a generated bibliography Section from
  a hand-authored Section that happens to be about references (Section
  carries no marker of its own); without a `:bib-resource` there is no
  `.bib` database `\\bibliography{}` could name. Either way the whole
  document -- reference list *and* every in-text citation -- falls back
  to the resolver's own already-formatted output, which is complete and
  self-consistent on its own, rather than emitting `\\citep` commands
  with no bibliography for BibTeX to resolve them against."
  [ctx block]
  (and (:native-bibliography ctx)
       (= (:bibliography-id ctx) (get-in block [:attr :id]))))

(def ^:private thematic-break-rule
  "A `:thematic-break` Block's LaTeX rendering (TASK-37 AC #2): a
  centered horizontal `\\rule`, half the enclosing text width. `\\rule`
  is core LaTeX -- no package -- and this is byte-for-byte what Pandoc's
  own LaTeX writer emits for the same CommonMark construct (verified
  against pandoc 3.1.3, including the `0.5pt` thickness), so a converted
  document looks like what a reader of converted Markdown expects.

  Preferred over the `\\hrulefill` this task's own AC #2 offers as an
  alternative, for a reason measured rather than assumed: `\\hrulefill`
  is a *fill*, so its width is decided by whatever else shares its line,
  while `\\rule{0.5\\linewidth}` is always half of the enclosing text
  width. On rendered pages at 60dpi, `\\hrulefill` came out 274px alone
  between two paragraphs but 197px on a line that also carried text;
  this `\\rule` is 143px at top level. Both still *scale* with context
  -- the same break measures ~123px inside a `quote` and ~132px inside
  `itemize`, since `\\linewidth` is the enclosing width -- but that is
  the rule tracking its container, which is what a break should do,
  rather than tracking its neighbouring text."
  "\\begin{center}\\rule{0.5\\linewidth}{0.5pt}\\end{center}")

(defn- unresolved-include-message
  "The `::unresolved-include` message for Include Block `block` (TASK-37
  AC #3) -- identical wording to `haselnuss.emit.html`'s own (asserted
  equal by test, not merely by convention), so both targets fail the
  same way on the same document. Names the `:src` and the real cause: an
  emitter has no target content to render, because expansion is
  `haselnuss.resolver/expand-includes`' job and it did not happen here.
  Since TASK-38 that pass exists and `haselnuss.cli` always runs it, so
  the routes still reaching this error are the two that skip it -- an
  AST built through `haselnuss.json/json->ast`, and a caller invoking
  `resolve-document` with no `:includes` loader."
  [block]
  (str "cannot emit an :include block for " (pr-str (:src block))
       ": it was never expanded, so there is no target content to render"
       " (haselnuss.resolver/expand-includes does that, and needs an :includes"
       " :load option -- haselnuss.cli always supplies one, so this AST did not"
       " come through it)"))

(defn- render-block
  "Renders one Block (`haselnuss.ast/Block`) to a LaTeX fragment. Covers
  every Block variant `haselnuss.ast` defines except `:include`
  (Section/Para/List/CodeBlock/BlockQuote/Directive -- TASK-21;
  Figure/Table/MathBlock -- TASK-22; `:thematic-break` -- TASK-37);
  `:include` raises `::unresolved-include` and anything outside the
  schema entirely raises `::unsupported-block` -- see this namespace's
  own docstring for why."
  [ctx block]
  (case (:t block)
    :section
    (if (generated-bibliography-section? ctx block)
      (bibliography-block ctx)
      ;; TASK-23 review finding #3: computed-numbers mode uses the
      ;; *starred* sectioning command. `article` numbers every heading it
      ;; is given, while the resolver numbers only `sec:`-labeled ones --
      ;; so an unstarred computed-mode heading reads "2 Methods" beside a
      ;; cross-reference to it reading "Section 1", the exact self-
      ;; contradiction `\caption*`/`\tag` exist to prevent.
      ;;
      ;; TASK-41: starred, and then the resolver's own number baked into
      ;; the heading text, exactly as `\caption*` and `\tag` already bake
      ;; in theirs. Before this, neither target printed a section number
      ;; at all, so a document could say "See Section 1" above a heading
      ;; reading only "Why hazel". Baking it in is what prints a number
      ;; WITHOUT reintroducing article's own counter, which is the whole
      ;; reason the command is starred. `section-number-prefix` prints
      ;; the bare number and nothing for an unnumbered Section; see
      ;; `haselnuss.emit.html/section-number-html` for the decision and
      ;; why an unnumbered heading needs nothing to fill the gap.
      (str "\\" (section-command ctx (:level block))
           (when (or (:computed-numbers ctx) (:unnumbered ctx)) "*")
           "{" (section-number-prefix ctx (:attr block))
           (render-inlines ctx (:heading block)) "}"
           (unnumbered-label ctx (:attr block)) "\n"
           (render-blocks ctx (:blocks block))))

    :para (render-inlines ctx (:inlines block))

    ;; A List or CodeBlock carrying an id the resolver DID number (its
    ;; prefix is a recognized kind) is anchored in computed-numbers mode
    ;; only (TASK-40). Neither construct steps a LaTeX counter, so in
    ;; native mode a `\label` here would bind to whatever counter was
    ;; stepped last and a `\Cref` to it would confidently print the
    ;; enclosing section's number -- the same silently-wrong output
    ;; `render-environment` refuses to emit, and the reason these two
    ;; stay `??` there. Computed mode has no such hazard: the printed
    ;; reference text comes from the resolver, and `\phantomsection`
    ;; (via `unnumbered-label`) makes the anchor this node rather than
    ;; its section.
    :list (str (counterless-label ctx (:attr block)) (render-list ctx block))

    ;; verbatim is catcode-literal: block's own :text needs zero escaping
    ;; (see namespace docstring for why this, over a package requiring a
    ;; syntax-highlighting dependency no AC asks for) -- but see
    ;; `verbatim-env` for the one hazard escaping alone can't fix: :text
    ;; containing the literal substring "\end{verbatim}" itself.
    :code-block
    (let [env (verbatim-env (:text block))]
      (str (counterless-label ctx (:attr block))
           ;; TASK-57 AC #3. `verbatim` has no language concept, and
           ;; pulling in `listings`/`minted` to get one is a dependency
           ;; no AC asks for and would cost this emitter the "compiles
           ;; under a standard toolchain" property TASK-21 AC #1 set. A
           ;; comment carries the authored value to anything that reads
           ;; the `.tex` -- a later highlighting pass, a human, a diff --
           ;; instead of discarding it, which is what happened before.
           (when-let [lang (:lang block)]
             (str "% haselnuss: language " (escape-tex lang) "\n"))
           "\\begin{" env "}\n" (:text block) "\n\\end{" env "}"))

    ;; Same counterless treatment as :list/:code-block above, and it
    ;; matters more than for either of those (TASK-51): a BlockQuote is
    ;; what an id-bearing directive lowers INTO when it has no native
    ;; renderer for this target -- both collapsable extensions build one
    ;; carrying the directive's own `attr` -- so this was the one place
    ;; such a directive's anchor disappeared entirely, leaving even
    ;; computed mode's `\hyperref` pointing at nothing. (A directive the
    ;; environment table maps never takes that path here: it has a
    ;; native `:latex` renderer, so `haselnuss.cli/environment-lower-rule`
    ;; degrades it for other targets only.)
    :block-quote
    (str (counterless-label ctx (:attr block))
         "\\begin{quote}\n" (render-blocks ctx (:blocks block)) "\n\\end{quote}")

    :figure (render-figure ctx block)

    :table (render-table ctx block)

    ;; An id-bearing MathBlock is a numbered, labelable `equation`;
    ;; id-less is plain unnumbered display math -- see namespace docstring
    ;; for the full rationale. `:tex` passed through completely verbatim
    ;; (AC #3), same as :math-inline above.
    :math-block
    (let [{:keys [tex attr]} block
          ;; TASK-23: in computed-numbers mode a numbered equation carries
          ;; the resolver's own number via amsmath's \tag, so it agrees
          ;; with what a \hyperref to it prints, instead of `equation`'s
          ;; own independent counter. No entry in :labels means there is
          ;; no computed number to tag with -- documented in the namespace
          ;; docstring as falling back to LaTeX's counter.
          tag (when (:computed-numbers ctx)
                (:number (get (:labels ctx) (:id attr))))]
      (cond
        ;; Computed mode, id-bearing, but the resolver assigned no
        ;; number (an unrecognized kind prefix): amsmath's unnumbered
        ;; `equation*`, with `\\phantomsection` giving hyperref an
        ;; anchor the uncounted environment cannot. Falling back to
        ;; `equation`'s own counter here -- which this branch used to do
        ;; -- reintroduced exactly the cross-format drift computed mode
        ;; exists to remove: LaTeX printed a number beside the equation
        ;; while `haselnuss.emit.html` printed none, since it has no
        ;; computed number to print either (TASK-27 review).
        (and (:id attr) (:computed-numbers ctx) (not tag))
        (str "\\phantomsection\n\\begin{equation*}\n" tex "\n"
             (attr-label attr) "\n\\end{equation*}")

        (:id attr)
        (str "\\begin{equation}\n" tex "\n"
             (when tag (str "\\tag{" (escape-tex tag) "}\n"))
             (attr-label attr) "\n\\end{equation}")
        ;; Closing delimiter on its own line -- same fatal raw-`%` fix as
        ;; :math-inline above; the id-bearing `equation` branch above was
        ;; never affected since its own template already separates
        ;; tex/label/end onto their own lines.
        :else (str "\\[" tex "\n\\]")))

    :directive (render-directive ctx block)

    :thematic-break thematic-break-rule

    :include (throw (ex-info (unresolved-include-message block)
                             {:type ::unresolved-include :block block}))

    (throw (ex-info
            (str "latex emitter does not support block type " (pr-str (:t block)))
            {:type ::unsupported-block :block block}))))

(defn- render-blocks
  "`render-block` mapped over every Block in `blocks`, in order, joined by
  blank lines -- LaTeX's own paragraph-break convention -- so consecutive
  Blocks (most commonly two `:para`s, which render as bare inline content
  with no wrapping environment of their own) remain visually distinct
  paragraphs rather than running together as one."
  [ctx blocks]
  (str/join "\n\n" (map (partial render-block ctx) blocks)))

(defn- front-matter-body
  "The rendered CONTENT of front-matter directive `block` (TASK-54): its
  prose through the ordinary `render-blocks` visitor, then -- when it
  carries any -- a keywords line, each term separated by the same
  separator the author wrote them with.

  Deliberately no environment of any kind, not even LaTeX's own
  `abstract`. This is exactly what a `--fragment` build writes to the
  side file a host template `\\input`s, and the host is the one that
  knows whether that content belongs inside `abstract`, abntex2's
  `resumo`, or something else again. `front-matter-standalone` adds the
  environment for the case where this emitter owns the whole document.

  The keywords line is a paragraph of its own, bold-labelled, rather
  than being folded into the prose: it is a list of terms, and both
  targets print it as one so the two documents say the same thing. The
  separator between terms is `front-matter/keyword-join`, paired with
  the separator the author writes them with, so a document's keywords
  cannot be written one way and printed another."
  ;; Everything inside a front-matter block is OUTSIDE the numbered
  ;; document -- `haselnuss.resolver/body-view` removes it before
  ;; numbering, so nothing in here has a number. Native mode has to be
  ;; told, because LaTeX numbers what it is given: an unstarred
  ;; `\section` inside an abstract took a body section number and
  ;; landed in the compiled `.toc`, and a `\caption` on a figure there
  ;; took a figure number and landed in the `.lof` -- in both cases
  ;; ahead of the body's own, and in neither case in the lists this
  ;; project derives (found by review of TASK-59). `:unnumbered` is what
  ;; `render-block` and `caption-command` read to star both.
  [ctx doc-lang block]
  (let [ctx (assoc ctx :unnumbered true)
        terms (front-matter/keywords block)]
    (str (render-blocks ctx (:blocks block))
         (when (seq terms)
           (str "\n\n\\noindent\\textbf{"
                (escape-tex (front-matter/keyword-label block doc-lang))
                ":} "
                (escape-tex (str/join front-matter/keyword-join terms)))))))

(defn- front-matter-comment
  "The machine-readable tag line a front-matter block carries in LaTeX
  output: `% haselnuss front-matter: abstract lang=pt-BR`.

  LaTeX has no generic language attribute the way HTML does, so without
  this a `.tex` file records a block's language only in the word it
  happens to print -- readable by a person, invisible to anything else.
  It leads BOTH the standalone rendering and the fragment side file
  (found by review: the side file is the artifact a host actually
  consumes, and it used to record its language nowhere but in its own
  filename, which nothing inside the file could see)."
  [doc-lang block]
  (str "% haselnuss front-matter: " (:name block)
       " lang=" (front-matter/lang block doc-lang) "\n"))

(defn- front-matter-standalone
  "Front-matter directive `block` as a standalone document places it
  (TASK-54, extended by TASK-55), set per its own `:shape` from
  `haselnuss.extensions.front-matter/blocks` -- data, so a fourth kind
  of front matter is a row in that table rather than a branch here.

  Every shape leads with `front-matter-comment`, the machine-readable
  language tag, and every one of them is core LaTeX2e needing no package
  at all. Confirmed compiling together in a `report` document with a
  `\\tableofcontents` present, none of the four appearing in it:

  - `:abstract` -- LaTeX's own `abstract` environment, with
    `\\abstractname` renamed inside `\\begingroup`/`\\endgroup`. The
    group is what makes \"in its own language\" work for a document
    carrying more than one: the rename is undone at the group's end, so
    a Portuguese Resumo followed by an English Abstract prints two
    different words rather than the last one twice. babel's
    `\\selectlanguage` was the alternative and would mean loading babel
    and carrying a BCP-47-to-babel-name table for a heading word.
  - `:heading` -- a starred sectioning command over ordinary prose.
    Starred, so LaTeX neither numbers it nor writes it into the `.toc`,
    agreeing with the exclusion `haselnuss.resolver/body-view` enforces
    upstream; an UNstarred one would print a body number over content
    the resolver never numbered. WHICH command follows the document's
    own top-level division (TASK-53): `\\chapter*` in a chaptered
    document, `\\section*` otherwise. An acknowledgements page in a
    thesis is chapter-level furniture, and `\\section*` set it beneath
    every chapter title in the one document shape this milestone exists
    for (found by review).
  - `:epigraph` -- `flushright` in italic. Its attribution is the
    author's own last paragraph, not a field this emitter punctuates.
  - `:dedication` -- `center` in italic.

  A note about the two italic shapes, because it is a real cross-target
  hazard and the fix is not here (found by review). `\\emph` inside
  `\\itshape` toggles *out* of italic -- which is typographically right,
  and which a browser does not do on its own, so the same authored
  emphasis came out upright in the PDF and italic in HTML.
  `haselnuss.emit.html/default-stylesheet` carries the matching rule
  rather than this namespace dropping `\\itshape`: LaTeX's behaviour is
  the correct one, so HTML is the side that was made to agree."
  [ctx doc-lang block]
  (let [body (front-matter-body ctx doc-lang block)
        word (escape-tex (front-matter/heading-word block doc-lang))
        shape (front-matter/shape block)]
    (str (front-matter-comment doc-lang block)
         (case shape
           :abstract (str "\\begingroup\\renewcommand{\\abstractname}{" word "}\n"
                          "\\begin{abstract}\n" body "\n\\end{abstract}\n\\endgroup")
           :heading (str "\\" (if (:chapters ctx) "chapter" "section") "*{" word "}\n" body)
           :epigraph (str "\\begin{flushright}\\itshape\n" body "\n\\end{flushright}")
           :dedication (str "\\begin{center}\\itshape\n" body "\n\\end{center}")
           ;; Not reachable for any row of the built-in table, but the
           ;; table is the documented extension point, and a row added
           ;; without a `:shape` should fail the way everything else in
           ;; this namespace does -- named -- rather than as a bare
           ;; "No matching clause" from `case` (found by review).
           (throw (ex-info
                   (str "front-matter block " (pr-str (:name block))
                        " has no LaTeX shape " (pr-str shape)
                        ": every entry in haselnuss.extensions.front-matter/blocks needs a"
                        " :shape this emitter knows how to set")
                   {:type ::unsupported-front-matter-shape
                    :name (:name block) :shape shape}))))))

(defn- meta-preamble
  "The `\\title{}`/`\\author{}`/`\\date{}`/`\\maketitle` fragment for
  Document `meta` (sec4.2), or `\"\"` when `meta` carries no `:title` at
  all -- mirrors `haselnuss.emit.html/emit-document`'s own title handling,
  but omits `\\maketitle` entirely rather than emitting a title-less one
  (unlike HTML's `<title>`, which is mandatory for a valid document even
  with placeholder text, LaTeX's `\\maketitle` with no `\\title` at all
  prints a stale/undefined \"Title\" placeholder in some class
  configurations -- there is no equivalent requirement here to work
  around). `:authors` (a plain string vector, sec4.2) are joined with
  `\\and` (LaTeX's own multi-author separator); `:date` is rendered as
  plain escaped text (both `escape-tex`-ed, being plain strings, not
  Inline vectors), and `\\date{}` is emitted even when there is none, so
  `\\maketitle` cannot fall back to `\\today` and print a date the
  document never declared; `:title` is rendered through the normal Inline
  pipeline (`render-inlines`), so authored markup inside a title (e.g.
  `\\emph{}`) still renders -- confirmed empirically to compile correctly
  even with `\\includegraphics`/`\\sout`/`\\href` inside `\\title`'s own
  moving argument (this task's own recorded plan)."
  [ctx {:keys [title authors date]}]
  (if (seq title)
    (str "\\title{" (render-inlines ctx title) "}\n"
         (when (seq authors)
           (str "\\author{" (str/join " \\and " (map escape-tex authors)) "}\n"))
         ;; Always emitted, even with nothing in it: `\\maketitle` with no
         ;; `\\date` at all falls back to `\\today`, so a document that
         ;; declares no date printed the build date in the PDF and
         ;; nothing at all in the HTML -- one document disagreeing with
         ;; itself, and a PDF whose title page changed every time it was
         ;; rebuilt (TASK-68 review). `\\date{}` prints no date, which is
         ;; what `haselnuss.emit.html/title-block` does by omitting the
         ;; paragraph. A blank `date:` takes the same branch, for the
         ;; same reason.
         "\\date{" (when (seq date) (escape-tex date)) "}\n"
         "\\maketitle\n\n")
    ""))

(def ^:private natbib-configs
  "How each `meta.cslStyle` (`haselnuss.resolver/default-citation-styles`)
  maps to native mode's own natbib setup: `:options` (natbib's package
  options) and `:bst` (the `\\bibliographystyle`). Chosen so that
  natbib's own rendering of a `\\citep`/`\\citet` matches, character for
  character, what that same style's `:item-core` produces in computed
  mode -- all four combinations confirmed empirically against a real
  `pdflatex`/`bibtex` in this codebase's own dev environment:

  - `\"numeric\"` -> `[numbers]` + `unsrtnat`. `\\citep` -> `[1]`,
    `\\citet` -> `Knuth [1]` -- exactly `numeric-item-core`'s own two
    shapes. `unsrtnat` over `plainnat` because it numbers entries in
    order of first citation, which is the order `resolve-citations`'
    own numeric style assigns numbers in.
  - `\"author-date\"`/`\"apa\"` -> `[round]` + `plainnat`. `\\citep` ->
    `(Knuth, 1984)`, `\\citet` -> `Knuth (1984)` -- exactly `author-
    date-item-core`'s own shapes. The `round` option is load-bearing:
    natbib's *default* punctuation for these is square brackets
    (`[Knuth, 1984]`), which would disagree with the resolver's own
    `(`/`)` wrap.

  Both `.bst` files are natbib-aware, which `\\citet`/`\\citeyear` need
  to have author/year fields to read at all. An absent or unrecognized
  `cslStyle` falls back to `\"numeric\"`, mirroring `resolve-citations`'
  own identical default (see `natbib-config`)."
  {"numeric" {:options "numbers" :bst "unsrtnat"}
   "author-date" {:options "round" :bst "plainnat"}
   "apa" {:options "round" :bst "plainnat"}})

(defn- natbib-config
  "`natbib-configs`' entry for Document `meta`'s own `:csl-style`,
  defaulting to the `\"numeric\"` entry for an absent or unrecognized
  style -- the same fallback `haselnuss.resolver/resolve-citations`
  itself applies, so the two passes never disagree about which style a
  document is in."
  [doc-meta]
  (get natbib-configs (:csl-style doc-meta) (get natbib-configs "numeric")))

(defn- document-class
  "The `\\documentclass` a standalone document is emitted into: `report`
  when `chapters?` (`meta.topLevelDivision: chapter`, TASK-53), else
  `article`, which is what this emitter has always written.

  DECISION (TASK-53 AC #5). `article` has no `\\chapter` at all, so a
  chaptered document emitted into it does not merely look wrong -- it
  fails to compile on the first heading. `report` is the smallest
  standard class that provides one: it is `article` plus chapters and
  the counter resets that come with them (`\\thefigure` becomes
  `\\thechapter.\\arabic{figure}`, which is precisely the numbering
  `haselnuss.resolver/scope-path` matches), and it needs no package
  install, which TASK-21 AC #1's \"compiles under a standard LaTeX
  toolchain\" has kept true throughout this emitter.

  `book` was the alternative and is rejected here: it adds two-sided
  layout, `\\frontmatter`/`\\mainmatter` division and chapter openings on
  recto pages -- real book typesetting decisions this emitter has no
  business making on an author's behalf, and none of which a document
  can currently express. A document that wants them wants a template of
  its own, which is what `emit-document`'s `:fragment` mode (TASK-52) is
  for: there this emitter chooses no class at all, and the host's own
  `book`/`abntex2`/whatever owns the question."
  [chapters?]
  (if chapters? "report" "article"))

(defn- preamble
  "The package preamble every emitted document includes (TASK-21 AC #1),
  given `natbib-options` (see `natbib-config`): `inputenc`/`fontenc` for
  UTF-8 authored text under `pdflatex`, `ulem` (`normalem`, so `\\emph`
  keeps italicizing rather than `ulem`'s own default of underlining it)
  for `\\sout` (`:strike`), `graphicx` for `\\includegraphics`
  (`:image`), `array` for a Table's own `>{...}p{}` column-alignment/
  width tokens (`col-format` -- see namespace docstring), `longtable` for
  Table itself (replacing TASK-22's own since-fixed `tabular`-inside-a-
  `table`-float bug -- see namespace docstring), plus TASK-23's own four:
  `amsmath` for a computed-mode MathBlock's `\\tag` (and `amssymb`
  beside it -- math is stored and emitted as raw TeX, so this emitter
  cannot know which symbols a formula reaches for, and an author has no
  way to add a `\\usepackage` from a `.hdoc`; without it a `\\mathbb{R}`
  stops the build with an undefined control sequence and no PDF at all),
  `caption` for a
  computed-mode Figure/Table's `\\caption*`, `natbib` for native-mode
  `\\citep`/`\\citet`/`\\citeyear`, and `cleveref` for native-mode
  `\\Cref`.

  Load order is not cosmetic: `hyperref` goes near-last (LaTeX's own
  documented convention, to avoid conflicting with commands other
  packages redefine), but `cleveref` must come *after* `hyperref` --
  that is cleveref's own documented requirement, and the one exception
  to hyperref-goes-last. Every package is loaded in both modes rather
  than varying by mode: they are harmless when unused, and only natbib's
  own *options* (which depend on the document's declared citation style,
  not on the mode) differ between two emitted documents.

  Everything TASK-24 contributes is derived from `environments` (the
  active directive-environment mapping table) rather than hardcoded, so
  the table really is self-sufficient and an empty one produces none of
  it (TASK-24 review finding #6, which found a hardcoded `amsthm` here
  making a data-only entry that loads `ntheorem` fatally conflict):

  - every distinct `:packages` name in the table, `\\usepackage`d
    *before* `hyperref`. An entry cannot be left to load its own package
    from `:preamble`, which lands after the order-critical
    `hyperref`/`cleveref` pair.
  - the `unnumbered-environment` definition, but only when some entry is
    a `:counter` one -- it exists solely for what computed-numbers mode
    turns those into.
  - one line per distinct `:preamble` in the table.

  Both derived lists are deduplicated and emitted in directive-name
  order, so the same table always produces byte-identical output.
  Declarations come after `cleveref` -- confirmed empirically that
  cleveref still names a `\\newtheorem` environment declared below it
  (\"Theorem 1\"), which is what makes a native `\\Cref` to a mapped
  directive read correctly.

  `chapters?` adds no package -- `\\chapter` comes from the class, not
  from a preamble line -- but it does add a comment saying so (TASK-53).
  This same text is what `emit-preamble` hands a fragment's host, and a
  host is free to pick any class it likes; a body full of `\\chapter`
  handed to an `article`-based template fails on its first heading, and
  a one-line comment is the only warning this emitter can give from
  inside a file it does not control."
  [natbib-options environments chapters?]
  (let [specs (map environments (sort (keys environments)))
        packages (distinct (mapcat :packages specs))]
    (str (when chapters?
           (str "% This body uses \\chapter: the document class must provide it"
                " (report, book, abntex2, ...).\n"))
         "\\usepackage[utf8]{inputenc}\n"
         "\\usepackage[T1]{fontenc}\n"
         "\\usepackage[normalem]{ulem}\n"
         "\\usepackage{graphicx}\n"
         ;; `export` makes adjustbox's own keys (notably `max width`)
         ;; available directly on `\\includegraphics` -- see
         ;; `graphics-options` for why every image needs one.
         "\\usepackage[export]{adjustbox}\n"
         "\\usepackage{array}\n"
         "\\usepackage{longtable}\n"
         "\\usepackage{amsmath}\n"
         "\\usepackage{amssymb}\n"
         "\\usepackage{caption}\n"
         "\\usepackage[" natbib-options "]{natbib}\n"
         (apply str (map (fn [p] (str "\\usepackage{" p "}\n")) packages))
         "\\usepackage{hyperref}\n"
         "\\usepackage{cleveref}\n"
         (when (some :counter specs)
           (str "\\newenvironment{" unnumbered-environment "}[1]"
                "{\\par\\medskip\\noindent\\textbf{#1.}\\itshape}{\\par\\medskip}\n"))
         (apply str (map (fn [line] (str line "\n")) (distinct (keep :preamble specs)))))))

(def ^:private csl-type-entries
  "CSL-JSON `type` -> BibTeX entry type. Anything absent maps to `misc`,
  which is BibTeX's own catch-all and requires no fields, so an
  unrecognized type degrades to an entry that still compiles and still
  prints rather than to no entry at all.

  `thesis` resolves per entry rather than here, since CSL keeps the
  degree in `genre` and printing \"PhD thesis\" under a master's is
  wrong rather than merely incomplete (found by review) -- see
  `bibtex-entry-type`."
  {"article-journal" "article"
   "article-magazine" "article"
   "article-newspaper" "article"
   "book" "book"
   "chapter" "incollection"
   "paper-conference" "inproceedings"
   "report" "techreport"
   "manuscript" "unpublished"
   "webpage" "misc"})

(defn- bibtex-entry-type
  "The BibTeX entry type for CSL `entry`. Only `thesis` needs the entry
  itself: CSL puts the degree in `genre`, and BibTeX has two types."
  [{csl-type :type :keys [genre]}]
  (if (= "thesis" csl-type)
    (if (re-find #"(?i)master" (str genre)) "mastersthesis" "phdthesis")
    (get csl-type-entries csl-type "misc")))

(defn- container-field
  "The BibTeX field CSL's `container-title` becomes, which depends on the
  entry type: a chapter or a conference paper is IN a book, so BibTeX
  wants `booktitle` and prints nothing for a `journal` (found by review
  -- `Warning--empty booktitle`, and the book title vanished)."
  [entry-type]
  (if (contains? #{"incollection" "inproceedings"} entry-type) "booktitle" "journal"))

(defn- publisher-field
  "The BibTeX field CSL's `publisher` becomes. A report is published by
  an `institution` and a thesis by a `school`; giving either a
  `publisher` prints nothing at all."
  [entry-type]
  (case entry-type
    "techreport" "institution"
    ("phdthesis" "mastersthesis") "school"
    "publisher"))

(def ^:private csl-field-names
  "CSL-JSON field -> BibTeX field, for the fields whose mapping does not
  depend on the entry type. Ordered as a vector so a generated entry's
  own field order is stable across runs -- a `.bib` this writes sits
  beside the `.tex` in a build directory, and a file whose bytes change
  for no reason is a file that shows up in every diff."
  [[:volume "volume"]
   [:issue "number"]
   [:page "pages"]
   [:edition "edition"]
   [:note "note"]])

(defn- escape-bibtex
  "`escape-tex` for a value going into a `.bib` database, with the two
  brace characters replaced by their brace-BALANCED macros rather than
  by `escape-tex`'s own `\\{`/`\\}`.

  BibTeX counts braces in its own lexer, where a backslash is not an
  escape (found by review, by compiling one): a single `}` inside a
  value ended the entry early, `bibtex` exited 2, and the PDF printed a
  `?` for a *different* entry -- exactly the silent-wrong-output class
  this task exists to close. `\\textbraceleft{}` and
  `\\textbraceright{}` typeset the same character and balance.

  Safe to apply after `escape-tex` because every `\\{`/`\\}` in its
  output came from an original brace: its own multi-character
  replacements (`\\textbackslash{}` and friends) all put a letter before
  their `{`."
  [s]
  (-> (escape-tex (str s))
      (str/replace "\\{" "\\textbraceleft{}")
      (str/replace "\\}" "\\textbraceright{}")))

(defn- bibtex-name
  "One CSL-JSON name object in BibTeX's own `von Last, Jr, First` name
  syntax, TeX-ready, or a brace-protected `:literal` for a corporate
  author with no given/family split.

  Escapes its own parts and then adds any braces, never the other way
  round. Braces are BibTeX syntax here -- they say \"this is one name,
  do not split it\" -- so escaping them printed a literal
  `\\{CERN Collaboration\\}` in the reference list.

  Particles and suffixes go in BibTeX's own slots rather than being
  dropped (found by review: `van Gogh` printed as \"Gogh\", `King Jr.`
  as \"King\")."
  [{:keys [family given literal suffix]
    non-dropping :non-dropping-particle dropping :dropping-particle}]
  (if literal
    (str "{" (escape-bibtex literal) "}")
    (let [von (str/join " " (remove str/blank? [non-dropping dropping]))
          last-part (str/trim (str (when (seq von) (str (escape-bibtex von) " "))
                                   (escape-bibtex (or family ""))))
          parts (remove str/blank? [last-part
                                    (when (seq suffix) (escape-bibtex suffix))
                                    (when (seq given) (escape-bibtex given))])]
      (if (seq family)
        (str/join ", " parts)
        (escape-bibtex (str (or given "")))))))

(defn- bibtex-raw-field
  "A `  field = {value}` line for `value` that is already TeX-ready, or
  nil when it is blank. Braced, not quoted, so a value containing a
  quote needs no further care."
  [field value]
  (when (seq (str value))
    (str "  " field " = {" value "}")))

(defn- bibtex-field
  "`bibtex-raw-field` for an authored string, escaped for the `.bib`."
  [field value]
  (bibtex-raw-field field (some-> (not-empty (str value)) escape-bibtex)))

(defn- bibtex-title-field
  "A title field, wrapped in a SECOND pair of braces.

  Every standard `.bst` except `@book`'s own entry applies
  `change.case$` to a title, so `The TeXbook` came out as
  `The texbook` -- demonstrated by review on this project's own example
  document, where the native path disagreed with both HTML and computed
  mode. The extra braces are BibTeX's documented way to say \"this text
  is already cased\"."
  [field value]
  (bibtex-raw-field field (some-> (not-empty (str value))
                                  escape-bibtex
                                  (->> (str "{"))
                                  (str "}"))))

(defn- bibtex-entry
  "One CSL-JSON reference entry as a BibTeX entry string, keyed by its
  own `:id` -- the same key every `\\cite` in the emitted document
  already uses, which is what makes the two agree by construction."
  [{:keys [id author editor issued] :as entry}]
  (let [entry-type (bibtex-entry-type entry)
        year (some-> issued :date-parts first first str)
        fields (concat [(bibtex-raw-field "author" (str/join " and " (map bibtex-name author)))
                        (bibtex-raw-field "editor" (str/join " and " (map bibtex-name editor)))
                        (bibtex-field "year" year)
                        (bibtex-title-field "title" (:title entry))
                        (bibtex-title-field (container-field entry-type) (:container-title entry))
                        (bibtex-field (publisher-field entry-type) (:publisher entry))
                        (bibtex-field "address" (:publisher-place entry))]
                       (map (fn [[csl-key field]] (bibtex-field field (get entry csl-key)))
                            csl-field-names)
                       ;; A URL and a DOI are addresses a reader follows,
                       ;; not prose: escaping them turned `a_b` into
                       ;; `a b` in the printed address (found by review),
                       ;; the same hazard this namespace already documents
                       ;; for a Link `:target`. Passed through
                       ;; `latex-safe-url` rather than raw, for the same
                       ;; reason and by the same rule as `:target` itself
                       ;; (TASK-78): a raw backslash or unbalanced brace
                       ;; here breaks the surrounding `field = {value}`
                       ;; grouping every bit as fatally as it breaks
                       ;; `\href`'s own.
                       [(bibtex-raw-field "doi" (some-> (:DOI entry) latex-safe-url))
                        (bibtex-raw-field "url" (some-> (:URL entry) latex-safe-url))])]
    (str "@" entry-type "{" id ",\n"
         (str/join ",\n" (remove nil? fields))
         "\n}")))

(def ^:private bibtex-key-re
  "A citation key BibTeX can actually parse: no whitespace, comma, brace,
  quote, `@`, `=`, `#`, or `%`, any of which end or corrupt an entry.
  Every key `haselnuss.parser` can produce is already of this shape (its
  own token alphabet is letters, digits, `_`, `-` and `:`), so this
  guards a bibliography file rather than a document."
  #"[^\s,{}\"@=#%\\]+")

(defn csl-json->bibtex
  "The cited entries of `bibliography` -- a
  `haselnuss.resolver/load-bibliography` map from citation key to
  CSL-JSON entry -- as the text of a BibTeX `.bib` database (TASK-42).

  This is the converter whose absence was the seam: the resolver reads
  CSL-JSON, BibTeX reads `.bib`, and native-mode LaTeX delegates the
  reference list to BibTeX. Without it, a document declaring
  `bibliography: refs.json` emitted `\\bibliography{refs}` at a
  `refs.bib` nothing produced, and the build still exited 0 while every
  citation in the PDF printed `?`.

  `keys-to-emit` is the set of keys the document actually cites
  (`resolve-citations`' own `:ordered-keys`). Emitting only those, and
  not the whole file, keeps one unusable entry in a shared `refs.json`
  from breaking every document that shares it (found by review) -- and
  an entry whose own `:id` BibTeX cannot parse (see `bibtex-key-re`) is
  skipped rather than written, since a corrupt entry takes the rest of
  the database down with it. The 1-arity emits everything, for a caller
  with no citation list to hand.

  Entries come out sorted by key, and each entry's fields in a fixed
  order, so the same bibliography always produces the same bytes -- the
  generated file lives beside the `.tex` in a build directory, where a
  file that churns shows up in every diff.

  Deliberately lossy in one direction only: a CSL field with no BibTeX
  equivalent is dropped rather than invented into a `note`, and an
  unrecognized CSL `type` becomes `misc`, which requires no fields. The
  reverse direction -- reading `.bib` -- is still not implemented, and
  is not needed: this makes CSL-JSON the single source both worlds read
  from."
  ([bibliography] (csl-json->bibtex bibliography (set (keys bibliography))))
  ([bibliography keys-to-emit]
   (->> (sort-by key bibliography)
        (filter (fn [[k entry]]
                  (and (contains? (set keys-to-emit) k)
                       (re-matches bibtex-key-re (str (:id entry))))))
        (map (comp bibtex-entry val))
        (str/join "\n\n"))))

(defn bib-resource-name
  "The `\\bibliography{}` argument (`bibliography-block`) derived from
  `path` -- `meta.bibliography`, sec7 -- by stripping its file extension,
  since BibTeX appends `.bib` to whatever it is given. nil for a nil or
  empty `path`, and nil when stripping leaves nothing behind (a
  dot-leading name like `\".json\"` -- TASK-23 review finding #5: an
  empty string is truthy in Clojure and would have defeated
  `emit-document`'s own guard), which is what makes
  `generated-bibliography-section?`/`render-cite` fall back together to
  the resolver's own output.

  This default is now the FALLBACK path, not the usual one (TASK-42).
  `haselnuss.resolver/load-bibliography` reads CSL-JSON while BibTeX
  reads a `.bib` database, and stripping the extension was the
  convention that let one `meta.bibliography: refs.json` name both --
  provided a sibling `refs.bib` existed, which nothing produced. A
  document that had no such sibling still exited 0 while every citation
  in the PDF printed `?`.

  `csl-json->bibtex` closes that: `haselnuss.cli` generates the `.bib`
  from the CSL-JSON it already loaded, writes it beside the `.tex`, and
  passes its name as `:bib-resource` -- so native mode needs no
  hand-maintained database at all. This function still derives the name
  for a caller that does not generate one, and `:bib-resource`
  overrides either way for a document whose BibTeX database is named
  something else entirely.

  Public so a caller overriding the bibliography (e.g.
  `haselnuss.cli`'s own `--bibliography`) can derive the matching
  `:bib-resource` the same way, rather than resolving citations against
  one file while naming another in the emitted `\\bibliography{}`."
  [path]
  (when (seq path)
    (let [stripped (str/replace path #"\.[^./\\]*$" "")]
      (when (seq stripped) stripped))))

(defn- emit-context
  "The rendering `ctx` every visitor in this namespace threads, plus the
  two document-level values `emit-document`/`emit-preamble` both need to
  build the preamble (`:natbib-options` and the active `:environments`
  table). Shared by those two entry points deliberately: a fragment's
  companion preamble is only trustworthy if it is computed from the same
  document and the same options as the body it accompanies, and one
  function producing both is what makes that true by construction rather
  than by two call sites agreeing."
  [document opts]
  (let [doc-meta (:meta document)
        {natbib-options :options natbib-bst :bst} (natbib-config doc-meta)
        computed? (boolean (:computed-numbers opts))
        chapters? (= :chapter (:top-level-division doc-meta))
        bibliography-id (:bibliography-id opts)
        bib-resource (or (:bib-resource opts) (bib-resource-name (:bibliography doc-meta)))
        environments (:directive-environments opts default-directive-environments)]
    {:natbib-options natbib-options
     :environments environments
     :chapters chapters?
     :ctx {:registry (:registry opts)
           :directive-environments environments
           :computed-numbers computed?
           ;; TASK-53: read from the document itself, never from an opt,
           ;; so the class, the sectioning commands and the resolver's
           ;; own float numbering are all decided by the same
           ;; `meta.topLevelDivision` and cannot be set to three
           ;; different things by three callers.
           :chapters chapters?
           :labels (:labels opts {})
           ;; TASK-59: the document's own language for a list's printed
           ;; heading, and the three derived lists themselves, keyed by
           ;; derivation. Handed in rather than derived here for the
           ;; reason `:labels` is: they are built from the same resolved
           ;; document the numbering ran over.
           :lang (get doc-meta :lang "en")
           :derived-lists (:derived-lists opts {})
           :bibliography-id bibliography-id
           :bib-resource bib-resource
           :bib-style (:bib-style opts natbib-bst)
           ;; TASK-64: whether anything in the document refers to the
           ;; generated bibliography section. Read once here rather than
           ;; where the reference list is emitted, which sees one Block
           ;; and cannot know -- and read at all so a document that
           ;; never mentions its reference list emits exactly what it
           ;; emitted before that reference could resolve.
           :bibliography-referenced?
           (boolean (and bibliography-id
                         (some (fn [node]
                                 (and (= :cross-ref (:t node))
                                      (= bibliography-id (or (:target node) (:label node)))))
                               (ast-nodes document))))
           ;; The single flag both `render-cite` and `generated-
           ;; bibliography-section?` consult, so in-text citations and
           ;; the reference list are always produced by the same one of
           ;; the two mechanisms (TASK-23 review finding #4).
           :native-bibliography (boolean (and (not computed?) bibliography-id bib-resource))}}))

(defn emit-preamble
  "The `\\usepackage` lines and environment declarations `document`'s own
  emitted body depends on, as a standalone string -- byte-identical to
  what `emit-document` inlines between `\\documentclass` and
  `\\begin{document}` for the same `document` and the same `opts` (both
  go through `emit-context`, so the two cannot drift).

  This is DECISION 1 of TASK-52, recorded here because it is the whole
  answer to \"how does a fragment tell its host what to load\": a
  fragment cannot `\\usepackage` anything itself -- by the time it is
  `\\input`, the host is long past its own preamble -- so the packages
  the body needs have to reach the host author somehow, and the two
  candidates were a list printed to stderr or a file the host can
  `\\input`. This emits the file, and `haselnuss.cli` writes it beside
  the fragment as `<output>-preamble.tex`.

  A file, not a stderr list, for three reasons. It is machine-usable:
  `\\input{thesis-body-preamble}` in the host's own preamble is one line
  that stays correct as the document grows a package it did not need
  before, where a transcribed list silently rots the first time a
  directive with its own `:packages` is added. It survives the build:
  stderr scrolls past in a `make` run and is gone, while the file sits
  beside the `.tex` the host is already `\\input`-ing. And it carries
  the *declarations* too -- an entry in `default-directive-environments`
  contributes a `\\newtheorem`/`\\newenvironment` line as well as a
  package name, and a printed list of package names has nowhere to put
  those, so it would report only half of what the body needs.

  WHERE the host `\\input`s it is load-bearing, and nothing in this file
  can enforce it, so `haselnuss.cli/preamble-file-instructions` writes
  the rule into the generated file's own header where the person placing
  the line will read it. The rule is: first, ahead of the template's own
  `\\usepackage` lines. A package loaded here can be loaded again below
  with no options and nothing happens; the reverse is an option clash,
  and a citation package loaded ahead of the `natbib` this file brings
  is a redefinition error -- confirmed against a real `pdflatex` with
  abntex2cite, the very template this task was scoped from, which dies
  with `Command \\citetext already defined` when the `\\input` comes
  second and compiles when it comes first.

  A host that would rather not load some of these can read the file and
  take the lines it wants; what it cannot do is guess. See
  `emit-document`'s `:fragment` opt."
  ([document] (emit-preamble document {}))
  ([document opts]
   (let [{:keys [natbib-options environments chapters]} (emit-context document opts)]
     (preamble natbib-options environments chapters))))

(defn emit-document
  "Emits `document` (a resolved-and-lowered `haselnuss.ast/Document`) as
  one complete, compilable `.tex` document string: a `\\documentclass`
  (`document-class`, chosen from `meta.topLevelDivision`), this
  namespace's own fixed `preamble`, an optional `meta-preamble` title
  block, then the rendered body (`render-blocks`).

  `opts` (default `{}`) accepts:

  - `:registry` -- a `haselnuss.registry` map used to dispatch any
    surviving `:directive` Block to its own native `:latex` renderer (see
    this namespace's own docstring); omitted, a `:directive` in
    `document` always raises `::unsupported-block`.
  - `:computed-numbers` (default false) -- selects this namespace's
    emission mode (TASK-23): false is decision D4's own native mode
    (`\\Cref`/`\\cite`/BibTeX), true bakes in the resolver's own computed
    numbers and citation text. See the namespace docstring.
  - `:labels` (default `{}`) -- a `haselnuss.resolver/number-document`
    label table, read in computed-numbers mode for Figure/Table caption
    text and an id-bearing MathBlock's `\\tag`. Mirrors `haselnuss.emit.
    html/emit-document`'s own same-named opt exactly, including its
    contract that a caller re-runs `number-document` over the *fully
    resolved* document and passes the result straight through. Ignored
    in native mode, where LaTeX computes the numbers itself.
  - `:bibliography-id` (default nil) -- `haselnuss.resolver/resolve-
    document`'s own same-named return value, the generated bibliography
    Section's `attr.id`. Native mode replaces exactly that Section with
    `bibliography-block` (AC #2); see `generated-bibliography-section?`
    for the two conditions and for how a document with neither degrades
    (whole-document, not half-delegated).
  - `:bib-resource` (default: `meta.bibliography` with its extension
    stripped, see `bib-resource-name`) -- the BibTeX database
    `\\bibliography{}` names in native mode.
  - `:bib-style` (default: `natbib-config`'s own `:bst` for the
    document's `meta.cslStyle`) -- the `\\bibliographystyle{}` native
    mode requests.
  - `:derived-lists` (default `{}`) -- the resolver's own derived
    table of contents / list of figures / list of tables, keyed by
    derivation (`:toc`/`:list-of-figures`/`:list-of-tables`), which a
    `{toc}`/`{list-of-figures}`/`{list-of-tables}` placeholder in
    `document` prints in computed-numbers mode (TASK-59; native mode
    emits `\\tableofcontents` and needs none of it). Without it such a
    placeholder renders its heading over an empty list.
  - `:directive-environments` (default `default-directive-environments`)
    -- the directive-name -> LaTeX-environment mapping table (TASK-24).
    Replaces the built-in table wholesale rather than merging with it,
    so a caller can remove a built-in mapping as easily as add one;
    `(merge latex/default-directive-environments {\"algorithm\" {...}})`
    is the additive form. A caller must also register the matching
    `register-environments` so `haselnuss.lower/lower` keeps those
    directives (see `register-environments`).
  - `:fragment` (default false) -- emit ONLY the rendered body (TASK-52):
    no `\\documentclass`, no preamble, no `\\begin{document}`/
    `\\end{document}`, and no `meta-preamble` title block. The result is
    not a compilable document on its own; it is meant to be `\\input`
    into a host document that owns its own class, page furniture and
    preamble, which is the shape a real thesis/report template needs
    (an unmodified `main.tex` doing `\\input{thesis-body}`).

    The title block goes with the rest of the furniture, deliberately:
    a host template that supplies its own class also supplies its own
    title page -- the case this was built for hand-sets a capa, a folha
    de rosto and a ficha catalografica -- and a stray `\\maketitle` in
    the middle of an `\\input` body would typeset a second, competing
    one. `meta.title`/`:authors`/`:date` still reach a caller through
    `document`'s own `:meta`; they are simply not this fragment's to
    place.

    The packages the emitted body needs do not vanish with the preamble
    -- see `emit-preamble`, which is DECISION 1 of this task: they are
    reported to the host author as a companion file it can `\\input`,
    written by `haselnuss.cli` as `<output>-preamble.tex`.

    DECISION 2 of the same task, citations. A fragment renders them
    EXACTLY as standalone native mode does -- natbib's `\\citep`/
    `\\citet`/`\\citeyear`, with natbib itself among the packages
    `emit-preamble` reports, and with `haselnuss.cli` still generating
    the `.bib` beside the fragment. It would have been possible to
    degrade to plain LaTeX2e `\\cite` on the theory that a host template
    owns its own citation package; that was rejected because `\\cite`
    can express only one of the three forms this document model already
    distinguishes. `\\citet`'s author-in-text citation (\"Knuth [1]\")
    and `\\citeyear`'s bare year have no `\\cite` spelling at all, so
    the downgrade would silently discard an authored distinction --
    exactly the kind of quiet loss this codebase refuses elsewhere. The
    cost is pushed to the host instead, where it is both visible and
    one line long: a template using another citation package (abntex2cite,
    say, whose `\\citep` is `\\cite`) aliases the three commands it does
    not have, having been told by the reported preamble that natbib is
    what the body was written against. The `.bib` is still generated for
    the same reason it is in standalone mode -- the body cites keys, and
    a fragment shipped without the database behind them typesets `?` for
    every one of them.

    One consequence of that, found by review and worth stating rather
    than leaving for a host author to discover: a fragment whose
    document has a bibliography also emits `\\bibliographystyle`/
    `\\bibliography`, and LaTeX allows exactly ONE `\\bibliography` per
    document. A host template keeping its own gets BibTeX's `Illegal,
    another \\bibdata command`, one of the two databases dropped and
    every citation against it unresolved. Emitting nothing there was the
    alternative and is worse: the document's own reference list is
    authored content, and dropping it to dodge a collision would lose a
    whole section in silence. So it is emitted, and `haselnuss.cli`
    warns on every such build, naming the command the host has to
    remove."
  ([document] (emit-document document {}))
  ([document opts]
   (let [{:keys [ctx natbib-options environments chapters]} (emit-context document opts)
         doc-lang (get-in document [:meta :lang])
         [front-matter body-blocks] (front-matter/extract document)
         body (render-blocks ctx body-blocks)]
     (if (:fragment opts)
       ;; A fragment carries no front matter at all: each block goes to
       ;; its own side file instead (`emit-front-matter`), so the host
       ;; template places it. See that function for the decision.
       (str body "\n")
       (let [;; A chaptered document with anything ahead of its body --
             ;; a title block, front matter, or both -- numbers that
             ;; part separately (TASK-66). See `front-matter-numbering`
             ;; for what LaTeX does without it.
             numbered-front-matter? (and chapters
                                         (or (seq front-matter)
                                             (seq (get-in document [:meta :title]))))]
         (str "\\documentclass{" (document-class chapters) "}\n"
              (preamble natbib-options environments chapters)
              "\\begin{document}\n"
              (when numbered-front-matter? "\\hypersetup{pageanchor=false}\n")
              (meta-preamble ctx (:meta document))
              (when numbered-front-matter? "\\pagenumbering{roman}\n")
              (apply str (map (fn [block]
                                (str (front-matter-standalone ctx doc-lang block) "\n\n"))
                              front-matter))
              (when numbered-front-matter?
                "\\clearpage\n\\pagenumbering{arabic}\n\\hypersetup{pageanchor=true}\n")
              body
              "\n\\end{document}\n"))))))

;; Why a chaptered standalone document numbers its front matter
;; separately (TASK-66) -- the three commands `emit-document` emits for
;; it look like decoration and are not.
;;
;;
;; `report` sets `\maketitle` and each `abstract` as a `titlepage`, and
;; every `titlepage` resets the page counter to 1. So a thesis with a
;; title and two abstracts had three physical pages all holding page 1:
;; the front matter numbered arabic straight into the body -- chapter 1
;; beginning on printed page 6 -- and `hyperref`, which names a page
;; destination after the number the page prints, emitted three `page.1`
;; destinations and dropped two of them with a `pdfTeX warning (ext4):
;; destination with the same identifier` for each. A link into the front
;; matter then landed on whichever page won.
;;
;; The fix is what a book class does with `\frontmatter`/`\mainmatter`
;; and `report` has no command for: roman page numbers over the front
;; matter, arabic restarting at the body. `\hypersetup{pageanchor=false}`
;; brackets the part where the counter still repeats -- hyperref's own
;; documented answer for exactly this -- and is turned back on at the
;; body, where numbering becomes monotonic. Confirmed with a real
;; pdflatex on the thesis fixture: front matter i..v, chapter 1 on page
;; 1, and no warning of any kind in the log.
;;
;; Only for a chaptered document, and only when something precedes the
;; body: an `article` sets its abstract inline rather than as a
;; titlepage, so its counter never repeats and it needs none of this.

(defn emit-front-matter
  "The front-matter blocks of `document` (TASK-54), as a vector of
  `{:name :lang :content}` -- one entry per top-level front-matter
  directive, in document order, its `:content` rendered with NO
  environment around it at all.

  This is the fragment-mode half of the placement decision
  `haselnuss.extensions.front-matter` records: `haselnuss.cli` writes
  each entry as its own `\\input`-able side file, named for the block and
  its language, and the host template places it inside whatever
  environment it uses. Haselnuss must not emit `\\begin{resumo}` -- that
  environment is abntex2's, and template furniture is out of scope for
  this milestone -- so the content arrives bare and the host wraps it.

  The alternative was to render each block inline in the body fragment,
  where it was authored. That is wrong by construction for the case this
  exists for: a thesis template's own front matter comes before its
  `\\input` of the body, so an abstract inside the body would land after
  the capa, the folha de rosto and the ficha catalográfica -- pages away
  from where a reader looks for the Resumo, and somewhere the template
  has no way to move it.

  `:lang` is returned beside the content rather than being written into
  it, because it is what distinguishes two otherwise-identical side
  files and it is the caller that names them.

  Standalone mode ignores this entirely: `emit-document` places each
  block itself, in whatever shape its kind calls for -- LaTeX's own
  `abstract` environment, a starred sectioning command, a right-set or
  centred italic group (see `front-matter-standalone`)."
  ([document] (emit-front-matter document {}))
  ([document opts]
   (let [{:keys [ctx]} (emit-context document opts)
         doc-lang (get-in document [:meta :lang])
         [front-matter _] (front-matter/extract document)]
     (mapv (fn [block]
             {:name (:name block)
              :lang (front-matter/lang block doc-lang)
              ;; The tag line leads the content, not just the file name:
              ;; the side file is what a host template reads, and a
              ;; filename is not something the file itself can see.
              :content (str (front-matter-comment doc-lang block)
                            (front-matter-body ctx doc-lang block)
                            "\n")})
           front-matter))))

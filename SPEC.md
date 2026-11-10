# Haselnuss — Format Specification & Roadmap

> **Status:** Draft v0.1 · **Working name:** Haselnuss · **File extension:** `.hdoc`
>
> A neutral, human-writable document format meant to be the *lingua franca* of digital
> documents: authored in a markdown-like syntax with the structural strength of LaTeX, stored
> as a canonical AST, and converted losslessly-where-possible to LaTeX→PDF, HTML, EPUB, DITA,
> and beyond.

## How to read this file

This is the document model every namespace under `src/` is written against and cites by
section number — `sec4` for the AST, `sec5` for the surface syntax, `sec6` for
cross-references, `sec8` for extensibility, `sec9` for the resolver, `sec10` for the emitters.
It was written before the implementation and is kept here, in the repository, so the contract
can be read from a clone rather than from one machine's sibling directory. (The three
extension namespaces this milestone added -- the front-matter blocks, the derived lists --
cite the tasks that created them rather than a section here, since the specification has no
section about either.)

It is a **specification and a roadmap together**, so parts of it describe behaviour that does
not exist. Those parts are marked inline, as a block quote opening with a bold
"Not implemented." followed by what is actually the case:

```
> **Not implemented.** …what the code does instead.
```

A section with no such note describes what the code does. Where the two disagreed, the note
says what the code does and the specification text is left standing, because renumbering or
rewriting it would invalidate the section references those docstrings carry.

Two whole chapters are roadmap rather than description: §13 (migration from the TypeScript
reader this format was extracted from) and §15 (milestones). §12's library list names the
TypeScript ecosystem the design was sketched against; the implementation is Clojure, and its
own choices are recorded in `README.md` and in each namespace's docstring.

---

## 1. Motivation

LaTeX is superb for *producing print* (PDF) but poor at *producing everything else* — HTML,
EPUB, a live web reader. Plain Markdown is the opposite: trivial to render to HTML but missing
the structural machinery that serious documents need — stable IDs, cross-references,
citations and bibliographies, numbered/captioned figures and tables, math, and open-ended
environments.

Haselnuss fills the gap with a **neutral canonical format**:

- **Author once** in a comfortable markdown-superset.
- **Store** the document as a structured AST (the true interchange format).
- **Convert** to any target through dedicated emitters, with numbering and references resolved
  identically across every output.

This repository already contains the seed of the idea: `src/components/Types.tsx` is a small
document AST (`Article → Section → Paragraph → Element`) serialized as JSON and rendered to
HTML by React components. This spec generalizes that seed into a full pipeline.

### Goals

1. A human-writable surface syntax that reuses Markdown and adds LaTeX-strength structure.
2. Stable IDs and cross-references (sections, figures, tables, equations, theorems…).
3. Citations + bibliography with pluggable citation styles.
4. Captioned, auto-numbered figures and tables; inline and display math.
5. Open-ended custom environments and **dynamic/interactive elements** that degrade
   predictably on static targets.
6. Multiple output targets from one source, with identical numbering everywhere.

### Non-goals (v1)

- A WYSIWYG editor (a live preview reader is in scope; a visual editor is not).
- Perfect round-trip fidelity from *arbitrary* LaTeX/HTML back into Haselnuss.
- Re-implementing a TeX typesetter or a CSL engine (we reuse existing ones).

---

## 2. Design principles

- **Two canonical roles, no contradiction.** The `.hdoc` *markdown text* is the **source of
  truth you edit and commit** (git-diffable, human-first — decision D2). The *AST/JSON* is the
  **canonical processing & interchange representation** other tools consume. Markdown is what
  you author; the AST is what the pipeline and third parties speak.
- **Resolve once, format-independently.** Numbering, cross-reference resolution, and citation
  formatting happen a single time in the resolver, so every output agrees.
- **Everything referenceable carries an `Attr`.** IDs are the anchor for the entire
  cross-reference system.
- **Extend through three mechanisms, not infinite syntax.** Attributes, named directives/roles,
  and a resolver pass express "all of LaTeX" without inventing new syntax per feature.
- **Custom richness must degrade predictably.** A dynamic element that a target cannot render
  natively *must* declare a fallback (or a transform rule), so a `.hdoc` always produces a
  valid PDF.
- **Reuse solved problems.** Parsing frontend, CSL engine, math rendering, and the PDF backend
  are libraries, not things we rebuild.

---

## 3. Architecture

```
 .hdoc source (markdown++)
        │  parser frontend (djot / markdown-it) → normalize
        ▼
   Haselnuss AST  ──────────────  JSON  ◀── the interchange "lingua franca"
        │  resolve(ast, ctx)  — numbering · crossref · citations · TOC · validation
        ▼
  Resolved AST
        │  lower(target, registry)  — per-target handling of custom / dynamic elements
        ▼                             (native render · special-rule transform · fallback · drop)
  Lowered AST (per target)
        │  emit(target)  — visitor over the node set
        ├──▶ HTML   (generalized React components) → live reader / preview
        ├──▶ LaTeX  → PDF (tectonic)
        ├──▶ EPUB   (HTML emitter + packaging)
        └──▶ DITA   (later)
```

Two invariants make the whole thing work:

- **Numbering / references / citations are computed once**, in `resolve`, before any target is
  chosen → identical numbering across PDF/HTML/EPUB.
- **Custom / dynamic elements are reduced once per target**, in `lower`, so emitters only ever
  see nodes they know how to render.

> **Not implemented as drawn.** The pipeline is exactly this — parse, resolve, lower, emit —
> but the frontend is flexmark (a Java CommonMark parser) with this project's own extension
> syntax layered on it, not djot or `markdown-it`; and of the four targets only HTML and
> LaTeX exist (§10). One step the diagram omits: multi-file documents are spliced before
> numbering, so an included chapter numbers exactly as if it had been typed in place.

---

## 4. The document model (AST)

The AST is the contract. Types below are the normative definition (an evolution of
`src/components/Types.tsx`). A companion **JSON Schema** is derived from these types and is the
validation authority for `.json` interchange files.

### 4.1 Attributes

Every referenceable or styleable node carries an `Attr`:

```ts
type Attr = {
  id?: string;                        // unique document-wide anchor, e.g. "fig:tree"
  classes: string[];                  // e.g. ["theorem"], ["term"]
  props: Record<string, string>;      // arbitrary key=value metadata
};
```

- `id` is the anchor for cross-references. Convention: prefix by kind — `sec:`, `fig:`, `tbl:`,
  `eq:`, `thm:` — so a reference's prefix determines its kind.
- `classes` select a directive/role's behavior (e.g. `theorem`, `warning`, `collapsable`).
- `props` carry configuration for custom elements (e.g. `exports=both` on a code block).

### 4.2 Document & metadata

```ts
type Document = { meta: Meta; blocks: Block[] };

type Meta = {
  title?: Inline[];
  authors?: string[];
  date?: string;                      // ISO 8601
  bibliography?: string;              // path to .bib / CSL-JSON
  cslStyle?: string;                  // citation style id, e.g. "apa"
  lang?: string;                      // BCP-47, e.g. "pt-BR"
  topLevelDivision?: "section" | "chapter";  // what a level-1 heading is
  [k: string]: unknown;               // open-ended front matter
};
```

`topLevelDivision` (added with chapters) decides what a level-1 `Section` is: with `chapter`
every level-1 heading becomes a chapter, deeper levels shift down with it, and a
section-scoped number composes against the enclosing chapter rather than the whole section
path — which is what a chaptered LaTeX class does with the same document. Its default,
`section`, is every document written before the key existed. See §6.1.

### 4.3 Block nodes

```ts
type Block =
  | { t: "Section";    level: number; heading: Inline[]; blocks: Block[]; attr: Attr }
  | { t: "Para";       inlines: Inline[] }
  | { t: "List";       ordered: boolean; tight: boolean; items: Block[][]; attr: Attr }
  | { t: "CodeBlock";  lang?: string; text: string; attr: Attr }
  | { t: "MathBlock";  tex: string; attr: Attr }                 // display math, referenceable
  | { t: "Figure";     content: Block; caption: Inline[]; attr: Attr }
  | { t: "Table";      head: Row; rows: Row[]; caption: Inline[]; colspec: Col[]; attr: Attr }
  | { t: "BlockQuote"; blocks: Block[] }
  | { t: "Directive";  name: string; blocks: Block[]; attr: Attr; fallback?: Fallback }
  | { t: "Include";    src: string }                             // multi-file (implemented)
  | { t: "BookQuote";  ref: string; canonical?: string; title?: Inline[];
      blocks?: Block[]; url?: string }                           // `!quote <ref>` (doc-10)
  | { t: "ThematicBreak" };

type Row = { cells: Cell[] };
type Cell = { blocks: Block[]; align?: "left" | "center" | "right"; span?: number };
type Col  = { align?: "left" | "center" | "right"; width?: string };
```

Notes:

- **`Section` nests** (via nested `blocks`) rather than being a flat list. `level` is retained
  for convenience and for surface syntax that uses `#`/`##`. Nesting is what enables
  hierarchical numbering (`2.3.1`).
- **`Directive`** is the open-ended block environment — theorem, proof, admonition, and every
  custom/dynamic element. See §8.
- **`Figure` / `Table` / `MathBlock`** are first-class, captioned/numbered, and referenceable
  via their `attr.id`.

### 4.4 Inline nodes

```ts
type Inline =
  | { t: "Str";       text: string }
  | { t: "Space" } | { t: "SoftBreak" } | { t: "LineBreak" }
  | { t: "Emph";      inlines: Inline[] }
  | { t: "Strong";    inlines: Inline[] }
  | { t: "Strike";    inlines: Inline[] }
  | { t: "SmallCaps"; inlines: Inline[] }
  | { t: "Sub";       inlines: Inline[] }
  | { t: "Sup";       inlines: Inline[] }
  | { t: "Code";      text: string }
  | { t: "MathInline";tex: string }
  | { t: "Link";      target: string; title?: string; inlines: Inline[]; attr: Attr }
  | { t: "Image";     src: string; title?: string; alt: string; attr: Attr }
  | { t: "Span";      inlines: Inline[]; attr: Attr }            // inline role carrier
  | { t: "CrossRef";  label: string; suppressPrefix?: boolean }  // → "Figure 3" (resolved)
  | { t: "BookRef";   ref: string; passage?: Passage; canonical?: string;
      text?: Inline[]; url?: string }                            // `@{ref}` (doc-10)
  | { t: "Cite";      items: CiteItem[] }
  | { t: "Note";      blocks: Block[] }                          // footnote
  | { t: "Highlight"; inlines: Inline[]; attr: Attr }            // annotation: marked run
  | { t: "StickyNote";blocks: Block[]; attr: Attr };             // annotation: balloon

type CiteItem = {
  key: string;
  prefix?: Inline[];
  suffix?: Inline[];
  mode: "normal" | "author" | "year";   // [@k] | @k (author-in-text) | year-only
};
```

**`BookRef` / `BookQuote`** link to and quote a passage of another book (doc-10, the
libraries-and-books design). `ref` is the book reference exactly as written — the parser
never validates it, so a malformed one reaches the resolver and becomes a
`malformed-book-ref` warning rather than a parse error. Every other field is optional and
filled by resolution; `Passage` is `{ form: "label", label } | { form: "range", from, to } |
{ form: "locator", locator }`, `haselnuss.book-ref`'s own shape.

The lossy `FontType` (which lumped `em`/`it`/`strong`) from the current model is replaced by
distinct `Emph`/`Strong`/`Sub`/`Sup`/… nodes.

**`Highlight` / `StickyNote`** are the two annotation nodes — what a PDF reader lays over
someone else's document without editing it. `Highlight` is structurally a `Span`: a wrapping
inline that marks a run of text and changes nothing about it. `StickyNote` is structurally a
`Note` — a footnote is already "an inline marker whose content lives elsewhere and is revealed
on demand", which is a sticky note with different presentation — **plus the `Attr` that `Note`
has never had**, so an annotation can be given an id and cross-referenced. Neither is a `Span`
carrying a class: classes select directive/role *behaviour* (§4.1), and `Note` has nothing to
hang a class on. A `Highlight` is written `==marked text==` (§5.6); a `StickyNote` has no
surface syntax and is created by an overlay or a transform rule.

Recognised `attr.props` on both: `color` (`yellow|green|blue|pink`, default `yellow`) and
`author`. `StickyNote` additionally takes `style` (`annotation|footnote`), read by the LaTeX
target alone. `at=` is **not** a node prop: it tells the overlay directive which edge of the
annotated run to place the node at (§5.5), and is consumed there. Both node types
render on every target: `<mark>` and an inline disclosure in HTML, real PDF annotation
objects in LaTeX, and `==…==` plus a footnote in `.hdoc`. A `StickyNote` whose body a PDF
annotation string cannot hold degrades to a `\footnote` with a diagnostic naming why — §4.5's
own philosophy applied to a node rather than a directive.

### 4.5 Fallback

Static substitute for targets that cannot render a custom/dynamic element natively (see §8):

```ts
type Fallback =
  | { kind: "blocks";    blocks: Block[] }                       // flatten to static AST
  | { kind: "poster";    src: string; caption?: Inline[] }       // pre-rendered image/snapshot
  | { kind: "transform"; rule: string }                         // named rule run at lower-time
  | { kind: "drop" };                                            // omit entirely
```

---

## 5. Surface syntax (markdown++)

The surface reuses CommonMark and adds **exactly three** extension mechanisms — attributes,
directives, and roles/spans — plus concrete syntaxes for math, cross-refs, and citations. The
reference flavor is **Djot** (whose native attributes/divs/spans map almost 1:1 to the AST);
`markdown-it` + plugins is an accepted alternative frontend.

### 5.1 Reused from CommonMark

Paragraphs, ATX headings (`#`…`######`), emphasis (`*`/`_`), strong (`**`), inline code and
fenced code blocks, blockquotes, ordered/unordered lists, links, images, thematic breaks.

Two GFM additions are decided here:

- **Strikethrough is in.** `~~struck text~~` produces a `Strike` (§4.4), with ordinary markup
  allowed inside. A single `~` is plain text. `Strike` carries no `Attr`, so an attribute group
  after the closer is not accepted. It renders as `<s>` in HTML and `\sout{}` in LaTeX, and
  round-trips through `--target hdoc` as `~~…~~` (a strike nested inside another strike
  cannot be spelled, and is written back as a `[text]{.strike}` role span).
- **Task lists are out.** `- [ ] item` and `- [x] item` are *not* a construct: they are
  ordinary list items whose text begins with the literal characters `[ ]` / `[x]`. A document
  format has no use for an interactive checkbox, and a `ListItem` has nowhere to record the
  state. A document that wants a checked/unchecked marker writes the symbol it wants.

### 5.2 Front matter

A YAML block at the very top populates `Meta`:

```markdown
---
title: On Hazelnuts
author: [Danilo Oliveira]
date: 2026-07-24
bibliography: refs.json
cslStyle: apa
lang: pt-BR
topLevelDivision: chapter
---
```

The parser maps exactly seven keys — `title`, `author`/`authors` (either spelling), `date`,
`bibliography`, `cslStyle`, `lang` and `topLevelDivision` — and ignores every other key
rather than carrying it into `Meta`. `Meta` itself stays open-ended, so that is a scope
limit rather than a claim about the model.

The block must be a YAML map. One that holds nothing (no lines, only comments, or `null`)
is no fields; one that is a string, a list, a number or a boolean — `---`, a bare title
line, `---` is the easy way to write one — is a parse error naming the file and the line
its value starts on.

> **Not implemented.** `bibliography` reads CSL-JSON only. A `.bib` file is not parsed;
> instead, a native-mode LaTeX build *generates* one from the CSL-JSON alongside its `.tex`,
> which is what makes `\\bibliography{}` work with no hand-maintained database.

Prose that belongs to the document but not to its numbered body — an abstract, its keywords,
acknowledgements, an epigraph, a dedication — is **not** front-matter YAML: it is multi-
paragraph marked-up prose, so it is written as directives (§5.5).

### 5.3 Attributes

`{#id .class key=val}` attaches to the preceding/enclosing element — headings, spans, images,
code blocks, directives:

```markdown
## Introduction {#sec:intro}
`x := 1`{.code-term}
![A hazel tree](tree.png){#fig:tree width=60%}
```

An attribute token has no escapes. A backslash in an id, a class, a key or an unquoted
value is a build error naming the line (and, in a directive header or a `!name{…}` line,
the column) rather than a token that keeps it — an id with one would reach a LaTeX label or
a cross-reference broken. This covers `{.c\}`: the escaped `}` does not hide the group, it
leaves a class `c\`, and is refused. A value that needs a backslash is quoted —
`k="a\b"` or `k='a\b'` — and keeps it verbatim; a quote character inside a quoted value
is written by switching delimiters, never with a backslash. (TASK-197)

### 5.4 Sections & headings

Headings create `Section` nodes. Nesting is by level; each heading may carry an id:

```markdown
# Results {#sec:results}
## Sub-result A {#sec:results-a}
```

### 5.5 Directives — block environments

Fenced with `:::` (or more colons for nesting), an optional name and attributes:

```markdown
:::{theorem #thm:main}
For all ε > 0, there exists δ > 0 such that …
:::

::::{warning}
Do not feed hazelnuts to squirrels you intend to keep.
::::
```

A directive with **no body at all** — everything it carries is in its header — is written on
one line instead, `!name{…}`, with no closing fence:

```markdown
!toc
!list-of-figures

!reveal{question="Why does a lone hazel set no nuts?" answer="It is self-incompatible."}
```

This is a second *spelling* of the same mechanism, not a fourth extension mechanism (§2): it
produces the identical `Directive` node with an empty `blocks`, and nothing downstream — the
resolver, the registry, the `lower` pass, the emitters, the JSON interchange — can tell which
spelling was written. Three rules define it:

- The attribute group is optional, so `!toc` is complete. When present it is the plain §5.3
  group (`#id`, `.class`, `key=val`) — the name is already given outside the braces, so a bare
  word inside them is an error rather than a second name.
- The line carries no body, and what follows the name decides whether it is a directive at
  all. Trailing text that never opens a group leaves the line as prose (`!toc extra words`),
  matching `!include`'s own tolerance. But a `{` commits the line: from there the rest of it
  must be exactly one group, so both `!toc{unclosed` and `!toc{a=1} trailing` are build
  errors rather than silently prose.
- `include` and `quote` are **reserved**: `!include <path>` is §4.3's include line and
  `!quote <book-ref>` the book-quote line (a `BookQuote`, doc-10). A bare `!include` or
  `!quote` with nothing after it stays an ordinary paragraph, and `!include{…}` or
  `!quote{…}` is a build error naming the right spelling. A directive that really is named
  `quote` (or `include`) is still written with a fence, `:::{quote}` … `:::`. (Before
  `!quote` existed, a bare `!quote` was a body-less directive named `quote`, and
  `!quote some words` was prose; it is now a `BookQuote` whose ref is `some words`.)

`!` puts this beside `!include` and `!quote` — the format's other whole-line block constructs — rather than
opening a second family with a shorter colon run. Which spelling to write is decided by whether
the directive has a body, never by taste.

The directive `name` (or first class) selects behavior via the extension registry (§8). This is
the mechanism by which "all of LaTeX's environments" are expressible without new syntax.

The built-in directive names, and what each is:

| name | what it is |
| ---- | ---------- |
| `theorem`, `lemma`, `corollary`, `definition`, `proof` | the theorem-like family; numbered, referenceable, `amsthm` in LaTeX |
| `admonition` | a numbered, referenceable note |
| `abstract`, `acknowledgements`, `epigraph`, `dedication` | front-matter prose: outside sectioning, outside numbering, outside cross-referencing. Each takes `lang` (per block, so two languages can sit in one document) and `keywords` (semicolon-separated, since a comma appears inside a keyword far more often than a semicolon does), though an abstract is the one that usually carries them |
| `listing` | a captioned, numbered code float, `caption` on the fence line |
| `algorithm` | the same mechanism for pseudocode; its body stays verbatim in both targets |
| `figure`, `subfigure` | §5.8 |
| `toc`, `list-of-figures`, `list-of-tables` | empty placeholders that print the resolver's derived lists where the author writes them; having no body, each can take the one-line form (`!toc`) |
| `collapsable`, `small-collapsable` | the reference extensions (§8.4) |

A directive name that is not registered stops the build and names it, rather than being
dropped or passed through.

#### Overlay directive conventions

Five directive names are recognised **only inside a file named by `--overlay`**, never in a
base document, where each is an ordinary unregistered directive and stops the build like any
other. Every one is addressed by `target=`, an `attr.id` in the base document, and every one
is dropped with a named, non-fatal diagnostic rather than being applied to a guess.

| name | what it does |
| ---- | ------------ |
| `prepend`, `append` | inject the directive's own body at the start or end of the Section `target=` names |
| `describe` | merge every prop but `target=` into that node's own `attr.props`; body-less, so written in the one-line form |
| `highlight` | wrap the run of words `text=` quotes inside `target=` in a `Highlight` (§4.4). With a body, also places a `StickyNote` immediately after it |
| `sticky-note` | place a `StickyNote` carrying the directive's body at one edge of that run |

`highlight` and `sticky-note` address a **run of words**, not a node, and say which run by
quoting it: `text=` is the quote, and `before=`/`after=`/`nth=` disambiguate one that occurs
more than once inside the target. Quoting rather than counting is what makes an annotation
survive the base document being edited elsewhere — and what makes it fail *loudly*, by name,
when the quoted words themselves change, rather than silently reanchoring onto the wrong
text. Both also take `color` and `author`; `sticky-note` additionally takes `at` and, on the
LaTeX target, `style` (§4.4).

An annotation must not renumber the document it annotates, so a Block whose own subtree holds
anything that would take a number — a Figure, Table, MathBlock or Section, a footnote, or any
Block carrying an `attr.id` — is diagnosed and dropped from a `sticky-note` body. A body left
empty by that rule drops the whole annotation rather than leaving a mark with no comment on
it.

### 5.6 Roles / spans — inline environments

`[text]{.class key=val}` produces an inline `Span`:

```markdown
Consider the [free monoid]{.term #def:free-monoid}.
```

A Span may sit anywhere inline text does, including inside link text and inside another Span:
`[see [the monoid]{.term}](https://example.org)` is a link holding a Span, and
`[a [b]{.x}]{.y}` is a Span holding one. Only a link may not hold a link. The bracket is a
Span even where its text names a link reference definition (`[b]: url`), so it does not count
as a link there either: `[see [b]{.term}](https://example.org)` is still a link holding a Span.

`==marked text==` produces a `Highlight` (§4.4) — an annotation over a run of words:

```markdown
Hazelnuts ==ripen in early autumn== and drop soon after.
```

An attribute group after the closing `==` attaches to the `Highlight` itself, exactly as one
after a `Link` or an `Image` does:

```markdown
Hazelnuts ==ripen in early autumn=={#hl:autumn .important}.
```

This is a primitive like `**strong**`, not a fourth extension mechanism: §2's "exactly three"
is about *extension* mechanisms. Djot's own `{=highlighted=}` was considered and rejected,
because `{` already opens an attribute group in this surface and `{=` would have to be
disambiguated from one.

`StickyNote`, the other annotation node, deliberately has **no** inline surface syntax.
Nothing in this grammar carries `Block`s inline except a footnote reference and its
definition, and inventing a second such construct to write something whose entire purpose is
to be authored from a separate file is the wrong trade. A sticky note is created by an
overlay or by a transform rule.

### 5.7 Math

- Inline: `$e^{i\pi}+1=0$` → `MathInline`.
- Display: `$$ … $$`, optionally with an id, → `MathBlock`:

```markdown
$$ e^{i\pi} + 1 = 0 $$ {#eq:euler}
```

Raw TeX is stored verbatim in `.tex`. It passes through to LaTeX untouched and is rendered by
KaTeX/MathJax for HTML/EPUB.

A display block may also sit inside a container -- a list item, a footnote, a directive or a
block quote -- and stays that container's child, id and number included. Each of its lines
carries the container's own prefix, like any other Block's: the item's indentation, or `> `
on every line in a quote (TASK-188):

```markdown
> $$
> e^{i\pi} + 1 = 0
> $$ {#eq:euler}
```

Inside a list item, footnote or quote the block is recognised only when it is a whole
paragraph of its own, so its TeX can hold no blank line and no line that would start another
Block there (a `- ` item, a `#` heading, a `>` quote, a code fence). A directive's body has no
such limit: its `$$` lines sit at column 0, like the top level's.

### 5.8 Figures

An image on its own line, given an id, becomes a numbered `Figure` whose caption is the alt
text (or an explicit caption):

```markdown
![A hazel tree in autumn.](tree.png){#fig:tree}
```

For non-image figures (e.g. a chart directive), use a `figure` directive wrapping the content
and a `caption` attribute:

```markdown
::::{figure #fig:sens caption="Sensitivity analysis" columns=2}

:::{subfigure #fig:temp caption="Temperature"}
![](temp.png)
:::

:::{subfigure #fig:pressure}
![](pressure.png)
:::

::::
```

A `subfigure` inside it is a **panel**: the whole figure takes one number, one caption and one
label, and each panel takes a letter within it — `fig:sens` is *Figure 1.1* and `fig:pressure`
is *Figure 1.1b*. The letter follows the id, not the caption: a panel with an empty caption
still prints its `(b)`, and a panel with no id prints no letter and is not a reference target.

That numbering is structural rather than a rule about the two names: a numbered node directly
inside a numbered node of the *same kind* takes a letter within it. It is exactly what LaTeX's
`subcaption` prints, so a native PDF, a `--computed-numbers` PDF and the HTML all read
*Figure 1.1b* for the same panel.

`columns=N` breaks the panels to a new row after every Nth, in both outputs; prose between two
panels ends the row it interrupts; without it panels stack one per row. A `figure` directive is
listed in the list of figures like any other figure; its panels are not.

A panel must sit directly inside the figure that lays it out, and a float may not nest inside
another float — LaTeX can typeset neither, so both stop the build naming the directive.

### 5.9 Tables

Pipe tables (CommonMark/GFM style) with an optional caption line and id:

```markdown
| Nut      | Yield |
|:---------|------:|
| Hazel    |   9.1 |
| Walnut   |   7.4 |
: Nut yields by species. {#tbl:yields}
```

### 5.10 Cross-references

Bare `@prefix:label`; the **prefix picks the kind** and drives the printed word:

```markdown
As shown in @fig:tree and proved in @thm:main, see also @sec:intro and @eq:euler.
```

`@fig:tree` renders as "Figure 3" (hyperlinked). To reference without the prefix word, use the
role form `[3]{ref=fig:tree}` or an explicit `[]{ref=… suppressPrefix}`.

> **Not implemented.** The role form has no parser branch: `[3]{ref=fig:tree}` parses as an
> ordinary `Span` carrying a `ref` prop, and nothing resolves it. The AST's `CrossRef` does
> carry `suppressPrefix`, and the resolver honours it (printing the bare number), so what is
> missing is only the surface syntax that would set it.

A bare `@token` whose prefix names no numbering kind, and no citation key, is left as prose —
so "cc @someone" in a paragraph is not a dangling reference.

`@{…}` with braces is not a cross-reference but a link to another book (`@{bible:romanos#14:8}`,
a `BookRef`), and a line `!quote <book-ref>` quotes one (a `BookQuote`) — doc-10. The braces
are what keep a bare `@reformed:heidelberg` meaning a local cross-reference. Write `@\{` for
a literal `@{`. A link resolves alongside the cross-references, to the passage's title as its
text ("Heidelberg Catechism, Section 3") and a URL when the book's library entry has a URL
template; one that cannot be resolved reads `??` with a warning, as a dangling `@fig:x` does.
A filled-in template value is percent-encoded and can never set the URL's scheme, and an empty
`{shelf}` (a book at the library's root) takes the `/` after it with it, so no `//` appears
(doc-10, "URLs").

### 5.11 Citations

Bracketed `@key` is a **citation** (disambiguated from bare-`@` cross-refs):

```markdown
Hazelnuts are optimal [@knuth1984, p. 42] and widely enjoyed [@smith2020; @jones2019].
Bare author-in-text: @knuth1984 showed that …
```

A bracket is a citation when every `;`-separated segment holds an `@key`; a key in brackets is a
run of ASCII letters, digits, `_`, `:` and `-` (`[@a.b]` is the key `a` with the suffix `.b`).
A trailing `;` ends the last item rather than opening an empty one, and so does a trailing `;`
followed by nothing but whitespace: `[@k;]` and `[@k; ]` are both the citation `[@k]`, and
`[@k; ](u)` is that citation followed by the text `(u)`, as `[@k;](u)` is — not a link. An empty
or blank segment between two items still makes the bracket no citation (`[@a; ; @b]`).

A bracket with an attribute group straight after it is a `Span` (§5.6) whatever it holds, so
`[@doe]{.c}` is a Span around the author-in-text citation `@doe`; an attributed bracketed
citation is written with the Span around it, `[[@doe]]{.c}`. A group that ends a heading or a
table caption is that block's own (§5.3), and the bracket before it stays a citation.

Whitespace between the `]` and the group — spaces, tabs, a line break — counts as straight
after, for a citation-shaped bracket exactly as for any other: `[@doe] {.c}` is the Span
`[@doe]{.c}` is, as `[s] {.c}` is the Span `[s]{.c}` is, and the space is not kept. A group
attaches across whitespace only where it ends its text, so `x [@doe] {.c} y` is refused like
`x [s] {.c} y`; and a spaced group that ends a heading or a table caption is still that block's,
so `# See [@doe] {#sec:x}` keeps its citation, as `# See [s] {#sec:x}` keeps a literal `[s]`.

### 5.12 Footnotes

CommonMark-extension footnotes map to inline `Note`:

```markdown
Hazelnuts are a pome.[^actually]

[^actually]: They are a nut, not a pome.
```

---

## 6. Cross-reference model

- Any `Section`, `Figure`, `Table`, `MathBlock`, or referenceable `Directive` with an `attr.id`
  is a **target**. Its *kind* comes from the id prefix (`sec:`/`fig:`/`tbl:`/`eq:`/`thm:`…) or
  from a directive's declared kind.
- The resolver assigns each target a number from a per-kind counter, respecting section nesting
  (e.g. "Figure 2.3", "Theorem 4").
- A `CrossRef{label}` inline is replaced/annotated with the target's computed label + link.
  Referencing produces "Figure 3", "Section 2.1", "Eq. (5)", "Theorem 4" — the prefix word comes
  from the **label lexicon** (below).
- **Primary syntax** is bare `@prefix:label`; the role form `[text]{ref=…}` is the alias for
  custom link text or suppressing the prefix word (decision D3). *(The role form is not
  implemented -- see §5.10.)*
- **Dangling references** (a `@fig:x` with no matching id) produce a build warning and a visible
  `??` placeholder, mirroring LaTeX. An id written on an *inline* node (an image sharing its
  paragraph with text or another image; only an image alone in its paragraph is a figure) is not a
  numbering target and stays unresolved, but its warning names the node kind (`id "fig:x" is on an
  inline image, which is not referenceable`) rather than saying no target exists.

### 6.1 The label lexicon (settles how kinds print, per language)

A kind (`sec`/`fig`/`tbl`/`eq`/`thm`/…) maps to counter behavior and a per-language prefix word:

```ts
type LabelLexicon = Record<string /* kind */, {
  counter: "section-scoped" | "global";       // "Figure 2.3" vs "Theorem 4"
  words: Record<string /* BCP-47 lang */, { singular: string; template?: string }>;
}>;
// e.g. thm → { counter: "global",
//              words: { en: { singular: "Theorem" }, "pt-BR": { singular: "Teorema" } } }
// e.g. eq  → { counter: "section-scoped",
//              words: { en: { singular: "Eq.", template: "Eq. ({n})" } } }
```

Built-in kinds ship a default lexicon; custom directives register their `kind` (§8.2) and add
lexicon entries. The resolver picks words using `meta.lang`. This settles decision D5.

The built-in kinds are `ch` (Chapter), `sec`, `fig`, `tbl`, `eq` — all section-scoped — and
`thm`, `lem`, `cor`, `def`, `prf`, `adm`, `lst` (Listing), `alg` (Algorithm), all global. Each
entry also carries `nodeTypes`, the AST node roles that kind conventionally labels, which is
what makes an id-prefix/role disagreement (`#fig:` on a theorem directive) reportable at all;
numbering itself is driven by the id prefix and never by the node type.

`lst` and `alg` are global rather than section-scoped because native LaTeX numbers them with a
`float`-package counter, which carries no per-chapter reset — so a section-scoped kind here
would print 1.1 beside LaTeX's own 1.

In a document whose `topLevelDivision` is `chapter` (§4.2), a section-scoped kind on anything
that is not itself a Section composes against the enclosing **chapter** rather than the whole
section path: the third figure of chapter 5 is *Figure 5.3* wherever in the chapter it sits,
which is what a chaptered class prints for the same document. Chapters are counted
structurally — every level-1 heading is one, labelled or not — because that is what LaTeX
counts.

**LaTeX-target rule (decision D4):** for the LaTeX emitter, emit *native* `\label`/`\ref`/`\cref`
and let LaTeX number — **this is the default**. A `--computed-numbers` build flag instead bakes
the resolver's numbers in (useful when one document is emitted to several targets and must match
exactly). For HTML/EPUB/DITA we always emit the *computed* numbers. Either way the resolver runs
for every target — for validation and derived structures.

---

## 7. Citations & bibliography

- Bibliographic data comes from `meta.bibliography` (BibTeX or CSL-JSON).
- The `meta.cslStyle` selects a CSL style; formatting is delegated to a CSL engine
  (`citeproc-js` + `citation-js` for BibTeX↔CSL-JSON). **We do not re-implement CSL.**

> **Not implemented.** There is no CSL engine here and no CSL style file is ever read.
> `meta.cslStyle` selects one of two hand-written formatters -- `numeric` (the default, and
> the fallback for an unrecognized name) and `author-date`, which `apa` aliases -- each of
> which formats an entry and its in-text form directly. A real CSL implementation is a large
> dependency for a document tool that needed two styles; adding one later means adding a
> third entry to that table, since every consumer goes through it. Input is CSL-JSON only
> (§5.2); a native-mode LaTeX build generates the `.bib` BibTeX needs from it.
- The resolver formats every `Cite` in-place and appends a bibliography block (a generated
  `Section`/`List` of formatted references) which is itself referenceable.
- For the LaTeX target the **default** is native `\cite` + `biblatex`/`natbib`, letting LaTeX
  build the bibliography (decision D4); `--computed-numbers` switches to the resolver's
  CSL-formatted citations + generated bibliography for exact cross-target parity.

---

## 8. Extensibility: custom & dynamic elements

The format must carry richness that only *some* targets can express — interactive charts,
forms, org-babel-style executable code, collapsibles — and degrade **predictably** on targets
that cannot (PDF/EPUB/DITA). This mirrors Jupyter/Observable multiple display representations
and org-babel's `:exports`. The AST stays neutral; three pieces make it work.

### 8.1 The node

Any custom element is a `Directive{ name, attr, blocks, fallback? }` (block) or a `Span{ attr }`
(inline). `attr.props` carries configuration/data; `blocks` carries the source/spec (e.g. a
`CodeBlock` of chart data or babel source); `fallback` declares what static targets receive.

### 8.2 The extension registry

A pluggable, per-target renderer table — where "the possibilities are numerous" lives, entirely
outside the core:

```ts
interface Extension {
  name: string;                                 // "chart" | "form" | "babel" | "collapsable" | …
  kind?: string;                                // referenceable counter kind, e.g. "fig"
  emit: {                                        // native renderers, per target
    html?: Renderer;
    latex?: Renderer;
    epub?: Renderer;
    dita?: Renderer;
  };
  lower?: (node: Directive, target: Target) => Block[] | null;  // optional "special rule"
}
```

### 8.3 The `lower(target, registry)` pass

Runs after `resolve`, before `emit`. For each custom node it selects **one** representation the
target can handle, so emitters never meet an unknown node:

```
if   registry[name].emit[target] exists      → keep it (emitter renders natively)
elif registry[name].lower(node, target)      → replace with the special-rule output
elif node.fallback                           → apply it (blocks | poster | transform | drop)
else                                         → BUILD ERROR: no representation for this target
```

The final branch is the **graceful-degradation contract**: a custom element that a target
cannot render natively *must* provide a fallback (or a registry `lower` rule). This guarantees a
`.hdoc` always yields a valid PDF even when interactivity is lost.

### 8.4 Worked examples

| Element        | `emit.html`            | Static-target handling                                             |
|----------------|------------------------|--------------------------------------------------------------------|
| `collapsable`  | interactive `<button>` | `fallback: {kind:"blocks"}` — flatten both parts inline            |
| `chart`        | live JS widget         | `lower` executes the spec → `{kind:"poster"}` image + caption      |
| `babel`        | live/editable code     | `props.exports` (`code`/`results`/`both`/`none`) → fallback policy |
| `form`         | real inputs            | drop, or `{kind:"poster"}` snapshot / static field list            |

`collapsable` is exactly today's `Collapsable` React component, re-expressed as a directive —
the simplest instance of the whole mechanism. `chart`'s poster participates in figure numbering
through its `#fig:` id.

> **Not implemented.** Of the four, only `collapsable` exists (with a `small-collapsable`
> variant beside it); `chart`, `babel` and `form` are illustrations of the mechanism, not
> built-in extensions, so the `{chart}` example below would stop a build naming the directive
> (§5.5). The mechanism itself is real and is what those rows describe: an extension
> registers a per-target renderer and/or a `lower` rule, and a directive with neither for the
> chosen target falls back to its own `fallback` — `blocks`, `poster`, `transform` or `drop`,
> all four of which are implemented and tested. What no built-in does is *execute* anything
> (§8.5), which is the half of the `chart`/`babel` rows that does not exist.

Surface syntax reuses the directive form:

```markdown
:::{chart #fig:sales exports=both}
{ "type": "bar", "data": [ ["Q1", 3], ["Q2", 5] ] }
:::
```

A custom element whose configuration is *entirely* in `props` — no spec, no source, nothing in
`blocks` — takes §5.5's one-line spelling instead, which is the common shape for a small
author-defined directive:

```markdown
!flashcard{front="Corylus avellana" back="Common hazel"}
```

### 8.5 Execution & trust model (settles D6)

> **Not implemented, and unreachable so far, for what the rules below actually describe: a
> `babel` block or `lower` rule executing code to *produce* a poster or a `results` block.**
> There is still no `babel` block and no `--allow-exec` flag, because no built-in extension
> needs to run anything to produce its own content — a `lower` rule bundled with this
> codebase is a plain Clojure function a *caller* registers, not code a document's own author
> supplies, so the trust boundary there stays the ordinary one: whoever runs the build chose
> the code it loads.
>
> A *different* surface does run author-supplied code today, in a real sandbox, and is not a
> roadmap item: `--extensions` (§8.2's own registry, populated from a sidecar `.clj` file
> naming a directive's own renderer) and `--transform` (a sidecar `.clj` file of node-rewrite
> rules that runs on the parsed document ahead of `resolve`, including for the `.hdoc` target,
> §10) are both evaluated inside one forked sandbox per build — no host interop, no file or
> process access, no `require` reaching this machine's own classpath, a wall-clock timeout,
> and captured, size-capped output. That is the same "an author's own script, not a boundary
> against a determined attacker" trust model the rules below anticipate for an executing
> extension, applied to a directive's own rendering/rewriting code rather than to a
> `lower`-time computation — its bounds, and their honest limits, are documented where an
> author actually reads them, the README's "Author-defined extensions" and "Scripted
> transformations" sections, rather than restated here.
>
> **What an untrusted extension may emit (settled 2026-11-10, TASK-215).** The sandbox bounds
> what an extension's code can *do*; this bounds what it can *emit*. The project owner chose
> to sanitise untrusted output rather than declare every extension fully trusted. An
> extension whose registration is not `:trusted` — every `--extensions` file unless the build
> passes `--trusted-extensions`, and every built-in registered without it — is held to:
>
> - the `[:raw]` and `Rendered` gates TASK-89/TASK-213 already set (refused);
> - an HTML element allow-list of structural, text-level, list, table, image/media and inert
>   interactive elements, so no `script`, `style`, `link`, `meta`, `base`, `iframe`, `object`,
>   `embed`, form controls, `svg`, `math`, `template` or custom elements (refused);
> - the document sanitiser's attribute rules on its hiccup — `on…` handlers and URL attributes
>   failing the `http`/`https`/`mailto`/relative scheme test dropped — plus `is` and
>   malformed attribute names (dropped, with a warning);
> - the document sanitiser itself on the AST it hands `:render-blocks`/`:render-inlines` and
>   on the Blocks its `lower` rule returns (dropped, with a warning), on both targets;
> - a TeX **allow-list** on every string of that AST an emitter writes unescaped: math may use
>   only ordinary mathematical control words, a few safe control symbols and `\begin`/`\end`
>   of amsmath's matrix and alignment environments (an `array` spec of `l c r |` only); any
>   other string may hold no control sequence, and an image path or citation key none of
>   `% # & $ ^ ~`; `^^`, unbalanced braces and an allowed word run on into `@`/`:` are refused.
>   A string the directive itself holds where TeX is spliced is exempt, since the document's
>   own TeX is trusted; prose it holds (a code sample) is not (refused).
>
> The rule behind refuse-versus-drop: what a document can cause (a URL copied from a prop) is
> neutralised with a warning, as the document pass does; what only extension code can cause
> fails the build naming the directive and its source line. `--trusted-extensions` lifts all
> of it. The TeX check is strict but best-effort — an allow-list reasoning about TeX's
> tokenisation under the document's normal catcodes, not a TeX interpreter. A `--transform`
> rule is outside it: its output is sanitised as the document is and its TeX counts as the
> document's. The README's "What an untrusted extension may emit" has the exact lists.
>
> **The document's own TeX (settled 2026-11-10, TASK-219).** By default the document author is
> trusted in the LaTeX build: math reaches pdflatex as written. Two exceptions apply. First,
> on every LaTeX build, `\href`, `\url` and the four-argument `\hyperref` are dropped from
> document math, with a located warning: `\href`/`\hyperref` keep their text and `\url` is
> removed, so `$\href{javascript:…}{x}$` makes no PDF link. A CSL-JSON `URL`/`DOI` with an
> unsafe scheme is likewise left out of the generated `.bib`, with a warning. Second, `--untrusted` is an opt-in strict mode. It holds every string of the
> resolved document that the LaTeX build splices as TeX to the allow-list above, with no
> exemption for authored TeX. That covers math, image paths, citation keys and attribute
> values, and includes included files, overlays and transform output. The first string
> outside the allow-list fails the build with `file:line` and the token. The flag is LaTeX-only:
> HTML math is already confined by MathJax's configuration (TASK-217), and json/hdoc filter
> nothing. It is independent of `--trusted-extensions`. Without it the document author's TeX is
> trusted, so the LaTeX build carries no script only under `--untrusted`. Dropping the three
> link commands is a convenience: the pdfTeX primitives (`\pdfstartlink`, `\pdfannot`,
> `\special{pdf:…}`) still make links, and only `--untrusted` stops every route. The README's "Converting
> a document you do not trust" states the user-facing model.

Any `lower` rule or `babel` block that *executes code* (to produce a poster/results) is
**off by default**. The rules:

1. **Default build runs no untrusted code.** With execution disabled, an executable element uses
   its **cached** representation (a committed poster image / `results` block). A `.hdoc` therefore
   always builds — to PDF *and* HTML — without ever running author-supplied code.
2. **Execution is opt-in and sandboxed.** A `--allow-exec` flag (plus a per-document/per-project
   trust marker) enables `lower`-time execution, which runs in a sandbox — subprocess with no
   network/filesystem-write by default, or WASM/container — never in the build's own process.
3. **Interactive HTML widgets are inert until the reader acts.** Emitted widgets (forms, live
   charts, editable `babel`) render but never auto-execute arbitrary code on load without the
   same trust gate; a `babel` block ships its cached output and only re-runs on explicit user
   action in a trusted context.
4. **Provenance is recorded.** When execution produces a representation, the resolver stamps the
   node with a hash of the source + a `generated: true` marker so stale caches are detectable.

This keeps the format safe to open and convert by default, while allowing a computational
workflow (org-babel-style) under explicit trust.

---

## 9. The resolver pass

Pure function `resolve(ast, ctx) → { ast: ResolvedAST, meta, diagnostics }`:

1. **Number** every labeled node — per-kind counters, respecting section nesting.
2. **Resolve `CrossRef`** to computed label + link; emit a warning for dangling refs.
3. **Resolve `Cite`** via the CSL engine and build the bibliography block. (Via one of the
   two built-in formatters -- see §7's note.)
4. **Derive** the table of contents, list-of-figures/tables, and section `next`/`previous`
   links (these are *derived*, never authored — a fix over the current model where
   `SectionType.next/previous` are stored by hand).
5. **Collect diagnostics** — dangling refs/cites, duplicate ids, unknown directive names. A reference to a duplicated id resolves to its first definition (with the duplicate-id warning, not a dangling one); later nodes keep their own numbers.

The resolver is target-independent. Target-specific reduction of custom elements happens later,
in `lower` (§8.3).

### 9.1 Book references: links, quotes and libraries

A document can reach another **book** — a `.hdoc` file in a library, or a book generated by
code — through a **book reference** (doc-10, the libraries-and-books design, is the full
design record; this section is the contract).

**Syntax.**

```
book-ref   = book-id [ "#" passage ]
book-id    = library ":" [ shelf-path "/" ] slug       ; reformed:confessions/reformed/heidelberg
name       = [a-z0-9] *[a-z0-9-]                      ; library, each shelf segment, slug
passage    = label [ ".." label ]                     ; sec:q1, or sec:q1..sec:q3
           | locator                                  ; 14:8-9 — anything not starting "kind:"
```

| Form | Written | Node |
| --- | --- | --- |
| Link (inline) | `@{bible:romanos#14:8}` | `BookRef` |
| Quote (block) | `!quote bible:romanos#14:8-9`, a line of its own | `BookQuote` |

The braces of `@{…}` are required: a bare `@reformed:heidelberg` stays a local cross-reference
(§5.10), and `@\{` is a literal `@{`. `!quote` is reserved beside `!include` (§5.5) and may
stand wherever a block may, a directive's body included:

```markdown
:::{small-collapsable summary="Romanos 14:8" label="(2)"}
!quote bible:romanos#14:8
:::
```

The **canonical** form of a book ID includes its shelf path; a reference may omit it
(`bible:romanos`). Resolution uses only library + slug, so a book moved to another shelf is
still found, with a `stale-shelf` note. A passage starting `kind:` is a label or a range of
sibling labels in document order; anything else is a locator, which only a book that
understands locators (a generated or in-process book) can answer.

**Nodes** (§4.3, §4.4). The parser keeps only `ref`, as written, and never validates it.
Resolution fills in the rest:

| Node | After resolution |
| --- | --- |
| `BookRef` | `passage` (as written; absent for a whole book), `canonical` (the canonical reference), `text` (link text the book supplies: "Heidelberg Catechism, Section 1.1", "Romanos 14:8"), `url` when the library has a URL template |
| `BookQuote` | `canonical`, `title` (the passage's title, e.g. "Romanos 14:8"), `blocks` (the quoted passage), `url` when the library has a URL template |

The `json` and `hdoc` targets run no resolution and keep both nodes as written; the `hdoc`
emitter writes `@{…}` and `!quote …` back verbatim.

**Resolution.** Quotes expand right after `!include`s (and overlays), before numbering; links
resolve beside cross-references (§9 step 2). Quoted content stays outside the host's
numbering: every `attr.id` in it is stripped, its headings are unnumbered and absent from the
TOC and lists of figures/tables, and a local `@prefix:label` inside it becomes a `BookRef` to
the source book. Quotes inside quotes expand recursively, up to depth 8. The library
directory — a YAML list from `--libraries`, `$HASELNUSS_LIBRARIES` or
`~/.config/haselnuss/libraries.yaml` (README "Libraries and books") — is read only when a
document actually holds a book reference. A URL is never guessed: it comes only from the
library entry's `url` template (`{library}`, `{shelf}`, `{slug}`, `{label}`, `{locator}`).

**Rendering.**

| Target | `BookRef` | `BookQuote` |
| --- | --- | --- |
| HTML | `<a class="book-ref" href=…>text</a>`, or a `<span class="book-ref">` without a URL | `<figure class="book-quote-figure"><blockquote class="book-quote" data-source="<canonical>" cite="<url>">…</blockquote><figcaption>— <cite>title</cite></figcaption></figure>` |
| LaTeX | the text, or `\href{url}{text}` | a `quote` environment whose last line is the source, `--- title`, flush right |

A reference that cannot be resolved never stops the build: a link prints `??`, a quote a
`?? <ref>` placeholder paragraph, and a diagnostic names the reference as written and its
`file:line`.

**Diagnostics.** None stops a build. Reference diagnostics carry `:level` (`warning`, or `info`
for `stale-shelf`); the library directory's are `error`, meaning its file needs fixing.

| `:type` | Level | When |
| --- | --- | --- |
| `malformed-book-ref` | warning | The text in `@{…}` or after `!quote` does not match the grammar |
| `no-library-directory` | warning | A document uses a book reference and no directory file exists |
| `unknown-library` | warning | The directory names no such library |
| `unavailable-library` | warning | The directory names it, but its entry failed to load (cause attached) |
| `unknown-book` | warning | The library has no book with that slug |
| `ambiguous-book` | warning | More than one book has the slug (see `duplicate-slug`) |
| `stale-shelf` | info | The written shelf path is not the book's current one; it still resolves |
| `unknown-label` | warning | The passage label is not in the book |
| `bad-label-range` | warning | A range's end precedes its start, or its labels are not siblings |
| `locator-unsupported` | warning | A locator on a book that does not answer locators |
| `bad-locator` | warning | The book rejected the locator; the message is the book's |
| `quote-cycle` / `quote-too-deep` | warning | Quotes quote themselves, or nest deeper than 8 |
| `book-failed` | warning | A book's document cannot be read, parsed or produced |
| `unreadable-include` / `include-cycle` | warning | Inside a quoted filesystem book, once per build |
| `unreadable-bibliography` | warning | A quoted book's own bibliography cannot be read |
| `duplicate-slug` | warning | Library scan: two books share a slug; both are named |
| `invalid-book-name` | warning | Library scan: a file or folder name is not a valid `name` |
| `invalid-front-matter` | warning | Library scan: a book's front matter cannot be read or listed as written |
| `unreadable-shelf` | warning | Library scan: a folder cannot be listed |
| `scripts-disabled` | warning | A generated shelf (`shelf.clj`) is used while its entry lacks `scripts: true` |
| `script-failed` | warning | A `shelf.clj` threw, timed out, exceeded its output cap or answered something unusable |
| `invalid-book-document` | warning | An in-process virtual book's `:document` or `:locate` fn returned something that is neither valid AST nor a diagnostic |
| `invalid-generated-book` | warning | A generated document, passage or listing entry fails the AST schema |
| `unsafe-url` | warning | A filled-in book URL has a scheme other than `http`, `https` or `mailto` and is dropped, as a link's is (README "Converting a document you do not trust") |
| `duplicate-library` | error | Two directory entries share a name; neither is loaded |
| `unknown-provider` | error | A directory entry names an unregistered `type` |
| `invalid-library` | error | A directory entry's `url` template is invalid, or its provider could not build it |
| `invalid-url-template` | error | A custom directory answered an invalid URL template; that library gets no URLs |

A library directory file that cannot be read, or is not a valid list of entries, is a hard
error, as is a book ID given as command-line input that cannot be found.

---

## 10. Emitters / conversion targets

Each target implements a visitor over the node set:

```ts
interface Emitter<T> {
  document(d: Document): T; section(s): T; para(p): T; figure(f): T; table(t): T;
  crossRef(r): T; cite(c): T; directive(d): T; /* … one method per node kind … */
}
```

- **HTML** — a self-contained document: MathJax for math, an opt-out default stylesheet, one
  `<section>` per Section, `<figure>`/`<figcaption>` for floats, footnotes collected into a
  list at the end.
- **LaTeX → PDF** — a configurable directive-name → environment mapping table; figures →
  `figure` + `\caption` + `\label`; math passthrough; native `\ref`/`\cite`.
- **.hdoc** — writes the parsed AST straight back out as canonical `.hdoc` source (§5): the
  same pre-`resolve`, pre-`lower` document the parser produced, not the visitor's own
  resolved/numbered view — a Section carries no computed number, and a `!include` line is
  written back unexpanded rather than followed. It re-spells canonically (one emphasis
  delimiter, ATX headings, normalized wrapping) rather than preserving the original bytes,
  so round-tripping through it is an AST fixed point, not a byte-identity guarantee — and,
  since §8.5's sidecar rules run on the parsed document before this target's own early
  return, this is also the target `--transform` (§8.5) writes a rewritten document back out
  through.
- **EPUB** — reuse HTML emitter output + packaging (nav/OPF/spine, zip).
- **DITA** — later; an XML mapping over the same visitor.

> **Not implemented.** Three of those five exist: HTML, LaTeX and `.hdoc`. EPUB and DITA have
> no emitter, and a target with none stops the build naming the directive it cannot represent
> rather than emitting something. The HTML emitter is Clojure that writes markup, not
> generalized React components — the TypeScript reader this format was extracted from is not
> part of this repository — and the PDF backend is whatever `pdflatex` the reader has, not
> `tectonic`.

Two things the LaTeX emitter does that the specification above does not mention, both needed
by a real thesis:

- **Emission mode.** Native mode is the default and is described in §6.1's D4 rule. A
  `--computed-numbers` build instead bakes the resolver's numbers in, which means starred
  sectioning commands, `\caption*` everywhere and `\hyperref` in place of `\Cref`.
- **Fragment mode.** `--fragment` emits the body alone — no `\documentclass`, no preamble, no
  `\begin{document}` — for `\input` into a host document that owns its own class and page
  furniture, which is the shape an institutional thesis template needs. Because a fragment
  cannot load a package for itself, the build writes a companion `<output>-preamble.tex`
  naming everything the body needs, and each front-matter block (§5.5) as its own
  `\input`-able side file, so the template can place an abstract inside whatever environment
  it uses. A standalone build instead chooses its own class: `report` for a chaptered
  document, `article` otherwise.

---

## 11. JSON interchange format

The AST serializes to JSON verbatim (each node is a tagged object with its `t` discriminant).
This JSON — not the markdown — is the canonical interchange artifact and what other tools
consume. A JSON Schema generated from §4's types validates any `.json` document. The existing
`public/books/*.json` files are an *older* shape and are migrated (§12).

The schema is generated from the malli schemas in `haselnuss.ast` — the same definitions the
passes validate against, so the two cannot drift — rather than from the TypeScript types
above; §4 and those schemas are a 1:1 port of each other.

> **Not implemented.** Migrating the old reader's JSON (§13), and the `public/books/*.json`
> files it names, which are not in this repository.

---

## 12. Reused libraries (build our AST/emitters, don't re-solve solved problems)

| Concern          | Reuse                                                                          |
|------------------|--------------------------------------------------------------------------------|
| Parsing frontend | `@djot/djot` (native attrs/divs/spans) — or `markdown-it` + attrs/container/footnote/texmath |
| Citations        | `citeproc-js` (CSL) + `citation-js` (BibTeX ↔ CSL-JSON)                         |
| Math             | store raw TeX; render with `KaTeX` (HTML), passthrough (LaTeX)                 |
| PDF              | `tectonic`                                                                     |
| EPUB packaging   | zip + hand-rolled OPF/nav (or a minimal epub-gen-style helper)                 |

The AST and all emitters are *ours*; the above are frontends/engines behind stable adapters.

---

## 13. Migration from the current model

- Write a one-time converter `oldJSON → Haselnuss AST` so the existing reader keeps working with
  `public/books/heidelberg.json`, `cfw.json`, etc.
- Key transforms: `FontType` → `Emph`/`Strong`; flat `Section[]` → nested sections; string+
  separator `ListType` → real `List`; `Collapsable` → `Directive{name:"collapsable"}` with a
  `blocks` fallback.
- Point the Python scrapers (`python-scrapper/`) at the new AST, or have them emit `.hdoc`.

---

## 14. Decisions

The six original open questions are now settled as follows. Each is threaded into the sections
noted; they are defaults, not one-way doors.

| # | Decision | Rationale | Reversibility |
|---|----------|-----------|---------------|
| **D1** | **Name = Haselnuss; extension = `.hdoc`.** | Repo already carries the name; `.hdoc` ("haselnuss doc") is self-descriptive and unclaimed. Freeze before the JSON Schema is published. | Easy now (rename/find-replace); costly once documents exist in the wild. |
| **D2** | **Markdown text is the source of truth; JSON AST is the derived interchange form.** (§2) | Human authoring is the primary workflow; `.hdoc` text diffs cleanly in git. JSON is generated, machine-consumed, not hand-edited. | The pipeline is symmetric, so a future JSON-canonical mode is additive. |
| **D3** | **Cross-refs: bare `@prefix:label` is primary; `[text]{ref=…}` is the alias.** (§5.10, §6) | Matches pandoc-crossref muscle memory; the role form covers custom link text / prefix suppression. | Both are parsed, so preference can flip without breaking documents. *(Only the bare form is parsed — §5.10.)* |
| **D4** | **LaTeX target uses native `\label`/`\ref`/`\cref`/`\cite` by default; `--computed-numbers` bakes resolver numbers in.** (§6.1, §7) | Let LaTeX do the numbering it excels at; computed mode exists for exact multi-target parity. | Per-build flag — chosen at conversion time, not baked into the document. |
| **D5** | **A label lexicon maps each kind → counter behavior + per-language prefix words; custom directives register their `kind`.** (§6.1, §8.2) | Centralizes "Figure/Theorem/Eq." wording, supports i18n via `meta.lang`, and keeps numbering rules out of emitters. | Data-driven table; extend or localize freely. |
| **D6** | **Code execution (`babel`/`lower` rules) is off by default; opt-in via `--allow-exec` + trust marker, sandboxed; cached representations otherwise.** (§8.5) | A `.hdoc` must be safe to open and convert without running author code; computation is available under explicit trust. | Trust gate is a build-time policy; can tighten/loosen without format changes. |

**Remaining judgment call (not blocking):** D1's exact extension string. If you dislike `.hdoc`,
the only edits are this table, the schema `$id`, and doc examples — say the word and I'll swap it.

---

## 15. Roadmap / milestones

A thin vertical slice first, then breadth.

| # | Milestone | Deliverable |
|---|-----------|-------------|
| 0 | **AST contract** | `src/format/ast.ts` (types incl. `Directive.fallback`) + generated JSON Schema |
| 1 | **Parser** | djot/markdown-it → AST: front matter, attributes, directives, math, crossref/cite syntax |
| 2 | **Resolver** | numbering + crossref + citations + TOC + dangling-ref validation |
| 3 | **Extensibility core** | extension registry + `lower` pass, with `collapsable` as the first built-in (proves interactive-HTML vs. PDF-fallback end-to-end on existing content) |
| 4 | **HTML emitter** | generalize existing React components → live preview in the app |
| 5 | **LaTeX → PDF** | LaTeX emitter + tectonic; **dogfood on a real paper you're writing** |
| 6 | **Breadth** | `chart`/`babel` extensions (exercise `lower` special-rule/poster); EPUB emitter |
| 7+| **Later** | DITA emitter; migrate scraped texts; multi-file `Include` |

---

## 16. Verification strategy

- **Golden AST tests** — small `.hdoc` fixtures → assert parsed AST JSON (extend the existing
  `*.test.tsx` pattern, e.g. `Paragraph.test.tsx`).
- **Resolver tests** — fixtures asserting numbers, resolved refs, and *expected warnings* for
  dangling refs/cites.
- **Cross-format numbering invariant** — one fixture with figures/eqs/citations; assert the HTML
  and LaTeX emitters produce the *same* numbers and bibliography.
- **Degradation contract** — a fixture with a `collapsable` + a `chart`: assert HTML renders them
  interactive, LaTeX/EPUB apply the fallback (flattened blocks / poster image), and a custom
  element with *no* fallback and no target renderer raises a build error.
- **End-to-end dogfood** — author one real short paper in `.hdoc`; build a PDF (tectonic) and
  open the HTML in the app side-by-side. This single document is the acceptance test for v1.
- **Regression** — run the migration converter on `public/books/heidelberg.json` & `cfw.json`;
  confirm the existing reader renders them unchanged.

> **Not implemented.** The last strategy, which needs the old reader's files; and the
> `tectonic` backend the dogfood entry names — the suite compiles with whatever `pdflatex`
> is installed.

Every other strategy above exists. What the suite adds beyond this list is compilation: several namespaces run a real `pdflatex` and read
the PDF back, because an emitter that produces plausible-looking LaTeX which does not typeset
has produced nothing. The dogfood document is `examples/hazelnuts.hdoc`; a second, book-shaped
fixture (`test/fixtures/thesis.hdoc`) covers chapters, front matter, the derived lists,
subfigures, listings and algorithms, standalone and as a fragment inside a host template.
```

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
  | { t: "Link";      target: string; inlines: Inline[]; attr: Attr }
  | { t: "Image";     src: string; alt: string; attr: Attr }
  | { t: "Span";      inlines: Inline[]; attr: Attr }            // inline role carrier
  | { t: "CrossRef";  label: string; suppressPrefix?: boolean }  // → "Figure 3" (resolved)
  | { t: "Cite";      items: CiteItem[] }
  | { t: "Note";      blocks: Block[] };                         // footnote

type CiteItem = {
  key: string;
  prefix?: Inline[];
  suffix?: Inline[];
  mode: "normal" | "author" | "year";   // [@k] | @k (author-in-text) | year-only
};
```

The lossy `FontType` (which lumped `em`/`it`/`strong`) from the current model is replaced by
distinct `Emph`/`Strong`/`Sub`/`Sup`/… nodes.

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
| `toc`, `list-of-figures`, `list-of-tables` | empty placeholders that print the resolver's derived lists where the author writes them |
| `collapsable`, `small-collapsable` | the reference extensions (§8.4) |

A directive name that is not registered stops the build and names it, rather than being
dropped or passed through.

### 5.6 Roles / spans — inline environments

`[text]{.class key=val}` produces an inline `Span`:

```markdown
Consider the [free monoid]{.term #def:free-monoid}.
```

### 5.7 Math

- Inline: `$e^{i\pi}+1=0$` → `MathInline`.
- Display: `$$ … $$`, optionally with an id, → `MathBlock`:

```markdown
$$ e^{i\pi} + 1 = 0 $$ {#eq:euler}
```

Raw TeX is stored verbatim in `.tex`. It passes through to LaTeX untouched and is rendered by
KaTeX/MathJax for HTML/EPUB.

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

### 5.11 Citations

Bracketed `@key` is a **citation** (disambiguated from bare-`@` cross-refs):

```markdown
Hazelnuts are optimal [@knuth1984, p. 42] and widely enjoyed [@smith2020; @jones2019].
Bare author-in-text: @knuth1984 showed that …
```

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
  `??` placeholder, mirroring LaTeX.

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

### 8.5 Execution & trust model (settles D6)

> **Not implemented, and unreachable so far.** Nothing here executes author-supplied code:
> there is no `babel` block, no `--allow-exec` flag and no sandbox, because no built-in
> extension needs to run anything. A `lower` rule is a Clojure function a *caller* registers,
> so the trust boundary today is the ordinary one — whoever runs the build chose the code it
> loads. The rules below stand as the policy any executing extension would have to meet.

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
5. **Collect diagnostics** — dangling refs/cites, duplicate ids, unknown directive names.

The resolver is target-independent. Target-specific reduction of custom elements happens later,
in `lower` (§8.3).

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
- **EPUB** — reuse HTML emitter output + packaging (nav/OPF/spine, zip).
- **DITA** — later; an XML mapping over the same visitor.

> **Not implemented.** Two of those four exist: HTML and LaTeX. EPUB and DITA have no emitter,
> and a target with none stops the build naming the directive it cannot represent rather than
> emitting something. The HTML emitter is Clojure that writes markup, not generalized React
> components — the TypeScript reader this format was extracted from is not part of this
> repository — and the PDF backend is whatever `pdflatex` the reader has, not `tectonic`.

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

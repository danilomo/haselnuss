# Haselnuss

A document toolkit: parser, resolver, and HTML/LaTeX exporters, built with Clojure/Leiningen.

**[`SPEC.md`](SPEC.md) is the format reference** — the document model, the surface syntax, the
cross-reference and citation model, extensibility, and what each emitter owes the others. Every
namespace under `src/` is written against it and cites it by section number. It is a
specification *and* a roadmap, so the parts describing behaviour that does not exist say so
inline; this README documents what you can write today.

## Project layout

- `haselnuss.ast` — AST data structures
- `haselnuss.json` — AST <-> JSON interchange (serialization, deserialization, and a
  malli.json-schema-generated JSON Schema)
- `haselnuss.parser` — parses `.hdoc` source documents into an AST: a hand-rolled
  YAML front-matter block (parsed with `clj-yaml`) plus CommonMark constructs parsed
  by `flexmark-java` and converted into `haselnuss.ast` nodes
- `haselnuss.resolver` — expands multi-file includes, numbers labeled nodes, resolves
  cross-references and citations, derives the TOC/lists of figures and tables, and
  collects structural diagnostics
- `haselnuss.emit.html` — HTML exporter
- `haselnuss.emit.latex` — LaTeX exporter
- `haselnuss.cli` — command-line entry point

## Usage

Build the standalone jar and convert a document:

```
lein uberjar
java -jar target/uberjar/haselnuss.jar paper.hdoc                      # -> paper.html
java -jar target/uberjar/haselnuss.jar paper.hdoc --target latex       # -> paper.tex
java -jar target/uberjar/haselnuss.jar paper.hdoc --target json        # -> paper.json
java -jar target/uberjar/haselnuss.jar --help
java -jar target/uberjar/haselnuss.jar --version                        # -> haselnuss <version>
```

The jar's name deliberately carries no version, so `--version` is how you tell which build
one on disk is. It reads the version Leiningen packed in from `project.clj`, so the two
cannot drift.

`--output -` writes the converted document to stdout instead of to a file, the POSIX
convention, for every target -- so a build can be piped straight into another program:

```
java -jar target/uberjar/haselnuss.jar paper.hdoc --output - | less
```

Every other progress line ("`haselnuss: wrote <path>`") already goes to stderr; a
resolver diagnostic and an error do too, so `--output -` did not have to add a special
case there, only obey the same rule everywhere: stdout carries the converted document and
nothing else. The one thing `-` cannot mean here is a real file called `-`: the two
overwrite/collision guards below have no path to check it against, and a build that would
also have to write a companion file -- a native-mode LaTeX `.bib`, a `--fragment`
preamble, a front-matter side file -- is refused instead, naming the file that has
nowhere to go, rather than silently dropping it. A document that genuinely needs a file
named `-` still gets one, through `./-` (a different string, so unaffected) or
`--output=-` (an explicit value, exempt from the stdin/stdout convention the same way
`--flag=value` is exempt from `-t -o out.html doc.hdoc`'s own "value looks like the next
flag" rejection).

`--in-place` writes the converted document back over the file it was read from -- the one
flag in this tool whose whole job is deliberately overwriting the document you asked it to
convert, so it is treated with the care that implies:

```
java -jar target/uberjar/haselnuss.jar paper.hdoc --in-place   # reformats paper.hdoc in place
```

It implies `--target hdoc` when no `--target` is given at all -- only `.hdoc` output can
honestly overwrite a `.hdoc` input, and `hdoc` is the only target that produces it -- and is
refused, naming the conflict, together with `--output` (two destinations is a mistake, not
a merge, whether that second destination is a real path or `-`) or with a `--target` other
than `hdoc`. The write itself goes through a temp file in the SAME directory as the input,
moved into place with an atomic filesystem rename only once the whole build has already
succeeded -- so a build that fails partway (a parse error, a construct the target cannot
represent, a full disk) leaves the input exactly as it was, never half-written.

Every other build in this tool refuses outright to write over the file it read from --
`haselnuss doc.hdoc --target hdoc` derives `doc.hdoc` as its own default output path and is
refused for exactly that reason, the same way `--target json` is refused below for a
same-named bibliography. `--in-place` is the ONLY flag that lifts that refusal, and only for
itself: pass it, and the tool trusts that overwriting the input is what you meant. Leave it
off, and `--target hdoc` with no `--output` keeps refusing, as it always has.

`--target json` is not a rendering target like the other two: it writes the parsed
document's own `haselnuss.json` interchange representation (SPEC.md sec11) -- no
resolve or lower pass runs, so it carries no computed section/figure numbers, no
resolved cross-reference text, and no bibliography. `--computed-numbers`, `--fragment`
and `--no-stylesheet` have no effect on it, the same way a LaTeX-only flag is already
a no-op on `--target html`.

Its prose is written more compactly than `haselnuss.json`'s own faithful encoding
would be: CommonMark tokenizes a paragraph one word per `:str` node, with a `:space`/
`:soft-break` node between each pair -- needed so an inline construct or a bare `@key`
has a clean token boundary, but noisy for a human or another tool reading the JSON
directly. The json target folds each consecutive run of those back into a single
`:str`, so `"wrong."`/`{"t":"space"}`/`"answer"` becomes one `{"t":"str","text":"wrong.
answer"}` -- but only up to the next node that is not a word or a space (an authored
`:line-break`, or markup like `:emph`/`:cross-ref`/`:cite`), which still ends the run
and keeps the space on either side of it. This is display-only: it is not part of
`haselnuss.json`'s own round-trip contract, which stays exactly as faithful as before.

With no `--output`, the json target's default output path is the input's own base name
with a `.json` extension -- the same extension a CSL-JSON bibliography almost always
carries. A document whose bibliography happens to share that base name (as this repo's
own `examples/hazelnuts.hdoc`/`examples/hazelnuts.json` do) would have that bibliography
silently overwritten by `haselnuss hazelnuts.hdoc --target json`; the build refuses this,
the same way it already refuses to overwrite its own `.hdoc` input.

### GFM strikethrough and task lists

`~~struck text~~` is strikethrough (a `:strike`; `<s>` in HTML, `\sout` in LaTeX). Task-list
markers are **not** supported: `- [ ] item` and `- [x] item` are ordinary list items whose
text starts with the literal characters `[ ]` / `[x]`. See SPEC §5.1 for the decision.

### Title, authors, date

```
---
title: A modeling framework for hazelnut yields
authors:
  - Ada Lovelace
  - Alan Turing
date: 2019
---
```

All three print in both targets: LaTeX sets them with `\maketitle`, and HTML opens `<body>`
with a `<header class="title-block">` holding an `<h1 class="title">` and one
`<p class="author">` per author over a `<p class="date">`. The title also becomes the
page's `<title>`, flattened to text, since an HTML `<title>` is character data.

The title is what decides whether there is a title block at all. A document that declares
none of the three gets none — no empty heading, no stray `\maketitle` — and, because the
rule is the same on both sides, a document that declares authors but *no* title gets none
either, in either target. That is deliberate: the point of the rule is that one document
does not print its own metadata in one target and swallow it in the other.

A document with no `date` prints no date. That takes saying because LaTeX does the
opposite on its own: `\maketitle` with no `\date` at all falls back to `\today`, so an
undated document used to get the *build* date on its PDF title page — a date it never
declared, different every time it was rebuilt — and nothing at all in its HTML. An empty
`\date{}` is emitted instead. Write `date: 2019` to print one.

### Links

Both CommonMark spellings work — `[text](url)`, and a bare URI in angle brackets:

```
Available at <https://example.com/dataset>, or write to <someone@example.com>.
```

An autolink is a link whose text is its own target; an email one gets the `mailto:` scheme
CommonMark gives it. Angle brackets around something that is *not* a URI stay prose
(`<3 apples>`), while a raw HTML tag stops the build the way raw HTML does everywhere else
— and by CommonMark's rules `<notauri>` is a tag, not prose, because a bare word in angle
brackets is a valid HTML open tag.

A link or image may carry a CommonMark title, `[text](url "Tip")` or `![alt](src 'Tip')`,
including in a reference definition. It is kept in the document and written as the `title`
attribute in HTML and back out by `--target hdoc`; LaTeX has nowhere to put it and drops it
without a warning.

### Converting a document you do not trust

The parser refuses raw HTML, and the HTML build goes one step further so that a page
converted from someone else's document does not carry script. The LaTeX build makes the
same promise only under `--untrusted` (see "Math in the LaTeX build" below). By default it
trusts the document author's TeX: math, and attribute values such as an image's `width`,
reach pdflatex as written, so they can make a PDF link or read a file. Three things are
removed before output on both targets, each with a warning on stderr that names it (the
build still succeeds):

- A link or image whose URL has a scheme other than `http`, `https` or `mailto` —
  `javascript:`, `data:`, `vbscript:` and anything else. Relative paths and `#fragment`
  targets are kept. A dropped link leaves its text behind; a dropped image leaves its alt
  text. Tabs, newlines and letter case inside the scheme (`JaVa\tScript:`) do not get past it.
- An attribute whose name begins with `on` (`onclick`, `onerror`, ...), whether it came
  from an attribute group (`{onclick="..."}`) or from a `describe` prop; and an attribute
  whose value fails the URL test above and whose name is on this closed list: `href`,
  `src`, `poster`, `action`, `formaction`, `xlink:href`, `data`, `srcset`, `ping`,
  `background`, `cite`, `longdesc`, `dynsrc` and `lowsrc` (every entry of a `srcset` or
  `ping` list is tested).
  The list is closed because an attribute is only known to be a URL by name; one not on it
  passes as written.
- A directive's `poster` fallback image whose `src` fails the URL test: the fallback is
  replaced by a `drop`, so the directive renders nothing rather than an unsafe image.

Math is not filtered on the way out — it reaches the page as the TeX you wrote — so HTML
output loads one exact MathJax release (3.2.2) and configures it so that math cannot make
a link or set page-level attributes:

- `\href`, `\class`, `\cssId`, `\style` and `\unicode` are left undefined and `\require`
  is removed, so each renders as a red undefined-macro error instead.
- MathJax's `safe` extension drops every URL the math produces, so
  `\mmlToken{mi}[href="javascript:..."]{x}` makes no link either. It also drops an `id` or
  `class` that does not start with `mjx-`, and it reduces a `style` (from `\mmlToken` or
  `\bbox`) to a short allow-list of properties: colour, border, margin, padding, font,
  opacity and a few more, with bounded sizes and no `javascript:` or `data:`.
- A token's `fontfamily`, `fontweight` and `fontstyle`, which MathJax copies into the
  page's CSS as written, are kept only when they are letters, digits, spaces, commas,
  hyphens or underscores.

Ordinary math renders exactly as before. The configuration is an inline `<script>`, so a
Content-Security-Policy that forbids inline script also stops it — and stops a
`javascript:` link from running, too.

**Math in the LaTeX build.** LaTeX output writes the document's math as the TeX you wrote,
and pdflatex runs it — so by default math may do anything TeX can, including read a file
(`\input`) or define macros. That is fine for your own document. Two things limit it:

- Always, flag or not: `\href`, `\url` and the four-argument
  `\hyperref{url}{category}{name}{text}` are dropped from math, each with a warning naming
  the link and its `file:line`. `$\href{javascript:alert(1)}{x}$` becomes `{x}` (the text
  stays, and the PDF has no link), and `\url{...}` is removed with its URL. No ordinary
  formula needs a link. This is a convenience, not a boundary. It covers those three
  commands as written, and TeX has other direct ways to make a link: the pdfTeX primitives
  `\pdfstartlink` and `\pdfannot`, `\special{pdf:...}`, `\csname href\endcsname`. Only
  `--untrusted` stops every route.
- `--untrusted` (latex only) holds the document's own TeX to the allow-list an untrusted
  extension's output is held to (see "What an untrusted extension may emit" below): math
  may use only ordinary mathematical commands, a few spacing and escape symbols, and
  `\begin`/`\end` of amsmath's matrix and alignment environments (an `array` column spec
  of `l c r |` only). An image path or citation key may hold no command and none of
  `% # & $ ^ ~`. Any other string the LaTeX build writes without escaping, such as an
  attribute value, may hold no command at all. The first string outside these rules fails
  the build. The error names the file, the line and the token
  (`doc.hdoc:12: document math uses TeX \input, ...`), and nothing is written. A document
  that keeps to ordinary math builds byte-for-byte as it does without the flag. The check
  is deliberately over-strict outside math. A backslash or a lone brace is refused even in
  a string the emitter would have escaped, such as an author name or a link target. An
  image `width=0.5\textwidth` is refused too; write `width=50%` instead.

`--untrusted` has no effect, and says so, on `--target html` (MathJax is already
configured as described above) and on `json`/`hdoc`, which filter nothing. It covers the
whole resolved document: included files, overlays and a `--transform` rule's output. The
bibliography's generated `.bib` is covered on every LaTeX build: a CSL-JSON `URL` or `DOI`
whose scheme is not `http`, `https` or `mailto` is left out of it, with a warning, since
BibTeX's `\url` would make it a live link. The flag does not cover what an extension emits. That is governed by `--trusted-extensions`, so the
two flags are independent. The check is strict but best-effort, the same as for extensions:
it reasons about how TeX tokenises under the document's normal catcodes, and it is not a TeX
interpreter.

This is the whole of the promise for the document itself. It is **not** a general HTML
sanitiser: other attributes (including `style`) pass through as written, and `--target
json` and `--target hdoc` write the document as parsed and do no filtering. An
`--extensions` file loaded *without* `--trusted-extensions` is held to the same rules and a
few more (see "What an untrusted extension may emit" under "Author-defined extensions"), but
`--extensions`/`--transform` still run author-supplied code, the TeX check there is strict
but best-effort, a `--transform` rule's output is filtered only as the document is, and
`--trusted-extensions` lifts every check — so do not load them from a source you do not
trust. If you serve converted output to others, still apply a Content-Security-Policy.

### Bibliographies

`meta.bibliography` names a CSL-JSON file, and that is the only bibliography format a
document needs to keep:

```
---
bibliography: refs.json
---
```

HTML, and LaTeX under `--computed-numbers`, render the reference list themselves from that
file. Native-mode LaTeX instead emits `\bibliography{}` and lets BibTeX build the list — so
a native build also writes a second file, `<output>.bib`, generated from the same CSL-JSON:

```
$ haselnuss paper.hdoc --target latex
haselnuss: wrote paper.tex
haselnuss: wrote paper.bib
$ pdflatex paper && bibtex paper && pdflatex paper && pdflatex paper
```

The generated database is named after the *output*, not after `meta.bibliography`, so a
`refs.bib` you maintain by hand is never in the line of fire. It also carries a header
marking it as generated: a build replaces its own file and refuses to touch one it did not
write, so an existing `paper.bib` stops the build with a message rather than being eaten.
It contains only the entries the document cites, so one unusable entry in a shared
`refs.json` cannot break every document that shares it.

Reading a `.bib` as input is not supported; converting one to CSL-JSON once is the way in.

### Characters LaTeX cannot set

pdflatex reads a fixed set of characters, and a document is free to contain something
outside it. Where it does, the build says so:

```
haselnuss: warning: the generated bibliography contains ́ (U+0301 COMBINING ACUTE
ACCENT), which the emitted LaTeX cannot typeset: pdflatex stops with "Unicode character
not set up for use with LaTeX" and writes no PDF at all. Near: "author = {Muı́noz, Ana}"
```

The warning names the character, where in the text it sits, and which of the two files it
came from — the document, or the `.bib` generated from your CSL-JSON, which is a file you
never wrote and would not think to search. That last one is not hypothetical: pandoc
renders BibTeX's `\'{\i}` as a dotless i followed by a combining accent, and a chapter
citing such an entry will not compile.

Text that *composes* is composed rather than reported: an `e` followed by a combining
acute is written out as `é`, which pdflatex sets perfectly. Only what has no composition
is left to warn about. Ordinary accented and typographic prose — `á ç ö ñ ß ā ł ż`, dashes,
curly quotes, `€`, `×`, ligatures — warns about nothing, which is the property that makes
the check worth having.

Both halves apply to prose, and only to prose. An image path, a link target and a `url`
field in the generated `.bib` are addresses that have to match something outside the
document — a file on disk, a server — so they are neither composed (composing `café.png`
makes a filename different from the one macOS stores) nor reported (a link to a Russian
Wikipedia article typesets no Cyrillic at all: those bytes go into the PDF as an address).
A TeX comment is not read either way.

The set is not a guess. It is inputenc's own `\DeclareUnicodeCharacter` table minus the
Cyrillic block, whose declarations need a font encoding this emitter does not load, and it
was checked against a real pdflatex for 114 characters, half of them sampled at random.
Greek, CJK and the mathematical operators are outside it: a document that wants those
wants XeLaTeX or LuaLaTeX, which is a different output target rather than a wider table.

It is a warning, not an error. The `.tex` is still written — an author may know exactly
what they are doing and be about to run a different engine on it.

### Addresses LaTeX cannot carry

The characters above are a *typesetting* problem — pdflatex reads them and cannot set the
glyph. An address can also fail for a *syntax* reason instead, with every character in it
perfectly ordinary ASCII: a raw backslash is read as the start of a LaTeX command, and an
unbalanced brace desynchronizes the argument grouping of the command carrying it. Both
compile as something other than the address that was written, or fail outright, and both
used to do so silently — the build exited 0, said it wrote the file, and pdflatex or
BibTeX failed two commands later on an address the build never mentioned.

A link target (either spelling, `[text](url)` or `<url>`) and a bibliography `url`/`doi`
field are URIs, so a document that runs into this is fixed rather than reported: a raw
backslash is percent-encoded (`%5C`) unconditionally — not a legal URI character to begin
with, per RFC 3986 — and a brace is percent-encoded (`%7B`/`%7D`) only when the address's
braces do not balance, since a balanced pair is the ordinary, already-working case from
the section above and is left exactly as authored. An address already percent-encoded is
never encoded a second time.

An image path is a filename rather than a URI, where the same rewrite would look up a file
that does not exist on disk — the same reason it is exempt from the character-composing
half above. There the build instead warns, naming the path and what is wrong with it, the
same trade the emitter makes for any other address it cannot safely rewrite.

### Styling

HTML output carries a default stylesheet inlined in `<head>`, so a converted document is
legible on its own with no second file to keep alongside it — the table has rules, an
admonition and a theorem are set apart from body text, and the equation number sits with
its equation. It styles only the classes the emitter already emits, uses no downloaded
fonts, and sets neither a background nor a text colour, so it reads on a light or a dark
browser default and an author's own CSS can override any of it.

Pass `--no-stylesheet` to leave it out, for embedding the output in a page that brings its
own CSS. The handful of inline styles the emitter writes per node — a cell's alignment, a
column's width, the equation number's float, small-caps — stay either way: a stylesheet
cannot know an authored value, and small-caps is meaning rather than presentation.

The one external fetch HTML output can make is the MathJax 3.2.2 script from a CDN (with the
extensions it loads from there, including the `safe` one "Converting a document you do not
trust" describes). It is included only when the document actually contains math, so a
math-free page makes no third-party request. Library callers can pass `{:mathjax false}` to `emit-document` to
omit it even then (for offline use); there is no CLI flag for that.

### Abstracts and keywords

An abstract is a block directive, because it is multi-paragraph marked-up prose rather than
a scalar a YAML front-matter key could hold:

```
:::{abstract lang=pt-BR keywords="Aveleira; Colheita; Nozes"}
Duas frases de *resumo*, com ênfase.

Segundo parágrafo do resumo.
:::
```

`lang` is per block, not per document, so a Portuguese *Resumo* and an English *Abstract*
can sit in the same document and each be labelled correctly; without one, a block inherits
`meta.lang`. The heading word is printed, not authored — *Abstract* / *Resumo* — and the
tag reaches HTML as a real `lang` attribute and LaTeX both as the word the abstract prints
and as a machine-readable comment.

Keywords are a list, separated by semicolons. A comma appears inside a single keyword
(*Knuth, Donald*) far more often than a semicolon does, so a comma-separated list could not
be split back apart. They reach HTML as one element per term and LaTeX as a labelled line.

An abstract is never numbered, never appears in the table of contents and cannot be
cross-referenced — it is not a body section, and writing one inside the body fails the build
rather than quietly putting it under a heading.

Under `--fragment` each abstract is written as its own side file, named for the block and
its language (`thesis-body-abstract-pt-BR.tex`), holding the prose and keywords with
**no environment around them**, so your template
puts it inside whatever it uses (`abstract`, abntex2's `resumo`, something else). Rendering
it inline would land it after the template's own capa and folha de rosto, pages from where
a reader looks for it, and nowhere the template could move it from.

### Acknowledgements, epigraph, dedication

The same mechanism carries the other prose that belongs to a document but not to its
numbered body:

```
:::{dedication}
To my parents.
:::

:::{epigraph}
Science is what we understand well enough to explain to a computer.

--- Donald Knuth
:::

:::{acknowledgements}
Thanks to everyone who read a draft.
:::
```

Each is set the way a book sets it — the acknowledgements under an unnumbered heading, the
epigraph right-set and italic, the dedication centred — and the two that print no heading
print none in *either* target, since a title over a dedication reads as a mistake. An
epigraph's attribution is just its last paragraph: it is prose you wrote, so nothing here
punctuates it for you.

The epigraph and the dedication are already italic, so there is no need to mark them up as
such — and `*emphasis*` written *inside* them reverts to upright, which is what emphasis
inside italic text means typographically. LaTeX does that on its own; the default
stylesheet makes HTML do the same, so the two outputs agree.

They are excluded from numbering, the table of contents and cross-referencing exactly as an
abstract is, and under `--fragment` each gets its own side file the same way. A misspelled
name (`acknowledgments`) is not a front-matter block at all — it is an unregistered
directive, so the build stops and names it rather than dropping a page in silence.

Capa, folha de rosto, ficha catalográfica and an ata de defesa are deliberately *not* here:
those are template furniture, which is what `--fragment` exists to leave to the template.

### Chapters

A document whose top-level division is the chapter says so in its front matter (the key is
named after pandoc's `--top-level-division`):

```
---
topLevelDivision: chapter
---
```

A level-1 heading then becomes a `\chapter` and every deeper level shifts down with it, out
to `\subparagraph` at level 6; a `#ch:` id numbers as a chapter, so `@ch:background` prints
*Chapter 5* (*Capítulo 5* under `lang: pt-BR`). Standalone LaTeX output moves from `article`,
which has no `\chapter` at all, to `report` — `book` was not chosen, since its two-sided
layout and `\frontmatter`/`\mainmatter` division are typesetting decisions a document cannot
currently express and the converter has no business making. A document that wants them wants
`--fragment` and a template of its own.

The part with consequences beyond two heading commands is numbering. A chaptered LaTeX class
resets the figure, table and equation counters per chapter and prints `\thechapter.\arabic
{figure}`, so the third figure of chapter 5 is *Figure 5.3* wherever in the chapter it sits.
Haselnuss composes those numbers the same way, so a reference, the caption it points at and
the LaTeX-built number in a native-mode PDF all agree. Chapters are counted structurally —
every level-1 heading is one, whether or not it carries an id — because that is what LaTeX
counts, so labelling only the chapters you cross-reference does not shift the numbering of
the ones you did not. Without the key nothing changes: a figure still composes with its
whole section path, exactly as before.

It is front matter rather than a command-line flag on purpose. It changes what every number
in the document *means*, and a document whose figure numbering depends on how it was invoked
is one whose cross-references cannot be checked by reading it. An unrecognized value stops
the build naming it, since silently falling back to sections would drop every chapter from
the output and renumber every figure with nothing said.

Tagging a level-1 heading `#sec:` in a chaptered document is a build warning. It is emitted
as `\chapter`, so native-mode LaTeX names a reference to it *Chapter 1* while HTML and
`--computed-numbers` both read the id prefix and print *Section 1* — one node, two words,
in two outputs of the same document. The mirror case, a `#ch:` id with no `topLevelDivision`
key, warns for the same reason. The generated bibliography section follows the same rule
rather than being an exception to it: it is a level-1 heading, so in a chaptered document its
id is `ch:bibliography`, and a document that cites anything no longer warns about a section it
never wrote. (That section prints no number in either output, and a cross-reference *to* it
does not resolve in either — LaTeX sets a reference list under an unnumbered heading, and this
tool numbers the document before generating it.)

### Rendering into someone else's template

`--fragment` (LaTeX only) emits just the body — no `\documentclass`, no preamble, no
`\begin{document}` — for `\input`-ing into a host document that owns its own class, page
furniture and layout. That is the shape a thesis or report template needs: an unmodified
`main.tex` keeps the capa, the folha de rosto and the forty `\usepackage` lines its
institution requires, and Haselnuss supplies only the chapters.

A fragment cannot load a package for itself, so the ones its body needs are written
alongside it as `<output>-preamble.tex` for the host to `\input`:

```
$ haselnuss thesis.hdoc --target latex --fragment -o thesis-body.tex
haselnuss: wrote thesis-body.tex
haselnuss: wrote thesis-body-preamble.tex
```

```latex
\documentclass{abntex2}
\input{thesis-body-preamble}   % FIRST, ahead of your own \usepackage lines
\usepackage[num]{abntex2cite}
\begin{document}
  ...your own title pages...
  \input{thesis-body}
\end{document}
```

**Put that `\input` first.** A package loaded there can be loaded again below with no
options and nothing happens; the reverse is an option clash, and a citation package loaded
ahead of the `natbib` the companion brings is a redefinition error — `\usepackage[num]
{abntex2cite}` before the `\input` fails with `Command \citetext already defined`, and
compiles when the two are the other way round. The generated file says so in its own header
too.

The companion is generated from the same document and options as the body, so it stays
correct as the document grows a construct that needs a new package. Like the generated
`.bib`, it carries a header marking it as generated: a build replaces its own file and
refuses to touch one it did not write. A host that would rather not load some of these can
read the file and take the lines it wants — what it cannot do is guess.

One thing the host has to give up: a fragment whose document has a bibliography emits its
own `\bibliography{}`, and LaTeX allows only one per document, so the template's own has to
go. The build warns about this every time rather than letting BibTeX drop a database and
leave the citations unresolved. Emitting nothing there would have avoided the collision by
silently losing the document's reference list, which is worse.

The title block goes with the rest of the furniture: a template that supplies its own class
supplies its own title page, and a stray `\maketitle` inside an `\input` body would set a
second, competing one. Citations do not degrade — a fragment emits the same natbib
`\citep`/`\citet` standalone native mode does, and still generates the `.bib`, because plain
`\cite` has no spelling for the author-in-text and year-only forms. A template using another
citation package aliases those three commands, having been told by the companion preamble
that natbib is what the body was written against.

### Captioned code listings

A fenced code block wrapped in a `listing` directive becomes a numbered, captioned,
cross-referenceable float:

````
:::{listing #lst:dining caption="The dining philosophers"}
```clojure
(defn dine [p] (eat p))
```
:::

The script of @lst:dining shows the deadlock.
````

`@lst:dining` prints *Listing 1*. The caption goes on the directive rather than on the fence
line, because an attribute group there is a parse error — the info string is a language word
and nothing else. It is plain text, not marked-up prose: an attribute value is a string by
schema, and parsing markdown inside an emitter would put the parser's job in the wrong
layer. The short spelling for a figure — `![...](pic.png){#fig:x}` — is the one caption that
*is* marked-up prose, since what is written there is markdown to begin with; see
[Markup in a figure caption](#markup-in-a-figure-caption).

In LaTeX this is a real float, built with the standard `float` package rather than
`listings` or `minted`: nothing has to be installed beyond a plain TeX distribution, which
is a property this project's LaTeX output has kept from the start. In HTML it is a
`<figure class="listing">` with its `<figcaption>`, the same shape a figure gets, so the
number and caption are composed from the same table in both.

The fence language reaches both outputs — `class="language-clojure"` in HTML, and a
`% haselnuss: language clojure` comment in the `.tex`. `verbatim` has no language concept,
and a comment carries the authored value to whatever reads the file next rather than
dropping it, which is what happened before.

A code block with no caption and no id is untouched: still a bare `verbatim`, no float, no
number. One with a caption but no id is deliberately *unnumbered* in both outputs, so that
an unlabelled listing between two labelled ones cannot leave LaTeX's own count one ahead of
the number every reference prints.

One caveat shared with every other kind: in native LaTeX mode the words come from cleveref
and the float's own `\floatname`, both of which are English. `lang: pt-BR` reaches HTML and
`--computed-numbers` LaTeX, which print *Listagem 1*; a native-mode PDF prints *Listing 1*.

### Algorithms

Pseudocode uses the same mechanism, with its own numbering:

````
:::{algorithm #alg:bisec caption="Bisection"}
```
KwData: a list L, a value x
For i <- 1 to n {
  If L[i] > x { return i }
}
```
:::

@alg:bisec terminates because the interval halves.
````

The body stays **verbatim** in both outputs. Reconstructing it as structured control flow
would mean a keyword convention and a mapping onto some LaTeX algorithm package, and would
give the two targets something to disagree about; a verbatim body is the one shape neither
can render differently from the other. Braces, arrows and index expressions are content.

`@alg:bisec` prints *Algorithm 1* (*Algoritmo 1* in HTML and `--computed-numbers` under
`lang: pt-BR`; a native-mode PDF prints the English name, as for every other kind).
Algorithms and listings are separate sequences.

The LaTeX environments are named `hnalgorithm` and `hnlisting` rather than the obvious
`algorithm` and `listing`. That matters under `--fragment`: the companion preamble is
`\input` into someone else's document, and `\newfloat{algorithm}` there collides with
`algorithm2e` — a package a thesis template may well already load. The printed words are
unaffected.

### Figures built from panels

A figure can hold several images under one number, one caption and one label. The panels are
`subfigure` directives inside a `figure` one — note the longer outer fence, which is what
lets the inner fences nest:

```
::::{figure #fig:sens caption="Sensitivity analysis" columns=2}

:::{subfigure #fig:temp caption="Temperature"}
![](temp.png)
:::

:::{subfigure #fig:pressure}
![](pressure.png)
:::

:::{subfigure #fig:flow caption="Flow"}
![](flow.png)
:::

::::

@fig:sens summarises it; @fig:pressure is the one to look at.
```

The whole figure is *Figure 1*; its panels are *Figure 1a*, *1b* and *1c*, and each is a
cross-reference target of its own. A panel prints its `(a)` even when its caption is empty,
because the letter is what the prose refers to — in the thesis this was built for, 19 of 49
panels have no caption text and are still cited by letter. The letter follows the id, not the
caption: a panel with no id of its own is not a numbering target in either output and prints
its caption with no letter, which also keeps its lettered siblings in step.

The figure itself needs an id whenever its panels have one. LaTeX letters a panel against
the figure's own number, so an unlabelled figure would have its panels lettered against some
*other* figure's number; the build stops and says so rather than letting the two outputs
disagree.

The numbers are not a special case anywhere: a numbered node whose nearest numbered ancestor
is of the *same kind* takes a letter within it instead of a number of its own. That is
exactly what `subcaption` prints in LaTeX, so the native PDF, the `--computed-numbers` PDF
and the HTML all read *Figure 1b* for the same panel, and the panels take no numbers out of
the figure sequence — the next plain figure is *Figure 2*.

`columns` is the author's, not the target's: with `columns=2` the panels break to a new row
after every second one, in both outputs. Left out, panels stack one per row — the one
arrangement that cannot overflow the line. Prose written between two panels ends the row it
interrupts, in both outputs. A `columns` that is not a positive integer stops the build and
names the directive rather than laying the panels out some other way; on a float that holds
no panels at all — a listing, an algorithm — it is never read.

In LaTeX this is the class's own `figure` float with `subcaption` panels; in HTML a
`<figure class="subfigure">` per panel inside a row element inside the parent `<figure>`. A
`subfigure` written outside a figure — or buried in a quote inside one, rather than sitting
directly in it — fails the *LaTeX* build naming it, because `subcaption` refuses that shape
outright; HTML, which has no float concept for it to be wrong about, renders it as the plain
captioned figure it degrades to. The same asymmetry covers a float inside a float — a listing
or a figure inside a figure — which LaTeX cannot typeset at all.

A panel's id goes on the `subfigure` fence, not on the image inside it: an id-bearing
standalone image is a figure of its own, and a figure inside a float is the shape above.
Writing one stops the LaTeX build naming it rather than producing a `.tex` that fails.

A single-image figure is untouched by all of this — `![A tree.](tree.png){#fig:tree}` is
still the short spelling, and still renders exactly as it did.

### Markup in a figure caption

The short spelling's caption is marked-up prose, not a plain string:

```
![Rate of *growth*, $x \times y$, after [@knuth1984]](rate.png){#fig:rate}
```

Emphasis, strong, inline math, a citation and a cross-reference all render in the caption,
in both targets, and a citation written there counts as a use of that key — it reaches the
reference list and the generated `.bib` like any other. Before this, the caption was
flattened to text before it was built: the emphasis kept its words and lost its meaning,
and the math and the citation were dropped outright, leaving `Rate of growth, , after`
with the build still exiting 0.

The `alt` attribute the HTML `<img>` carries is the same content flattened to a plain
string, because an `alt=` attribute is character data by definition — the emphasis keeps
its words, the math becomes its TeX, and a citation becomes the keys it cites, so the
attribute a screen reader receives is a sentence rather than `Rate of , after`.

This applies to *every* image's alt text, not only to one that becomes a figure: an
inline image in a sentence, a panel inside a `subfigure`, and a reference-style image all
parse their alt text the same way. What differs is only whether there is a caption to
print the markup in.

One construct is refused rather than rendered. A footnote in an alt text fails the build,
naming the image: an alt text is plain text, and a footnote has no plain-text form — its
content is blocks living elsewhere in the document, so there is nothing to put in the
attribute. Put the note in the prose around the image. (It would not have typeset either:
`\footnote` inside a `\caption` fails the LaTeX compile outright, and the two ways of
forcing it through either number the note twice or lose its body.)

And because an alt text is prose, whatever this parser refuses in prose it refuses there
too — an HTML entity (`&copy;`), a raw inline tag (`<b>`), an attribute group, a footnote
marker with no definition. Each stops the build exactly as it does in a paragraph, where
before it was quietly flattened into the caption. Adding `{#fig:x}` to an image is not
what changes this: the alt text of an image with no id is read the same way.

This is the one place the two spellings differ. A `figure` directive's `caption=` is an
attribute value, and an attribute value is a string by schema, so `caption="Panel with
$x$"` prints the dollar signs — see [Captioned code listings](#captioned-code-listings)
for why parsing markdown inside an emitter would put the parser's job in the wrong layer.
Write the short spelling when a caption needs markup.

### Tables

A GFM pipe table, with an optional caption line directly under it — no blank line between
them, or it is a paragraph rather than that table's caption — carrying the table's own
attribute group:

```
| Approach | Description                    | Ref |
|:---------|:-------------------------------|----:|
| Alpha    | Fits the text block            | [1] |
: Approaches compared. {#tbl:approaches widths="20% 55% 25%"}
```

The delimiter row says how each column is *set* — `:---` left, `---:` right, `:--:`
centred — and `widths` says how *wide* it is. Without widths a wide table runs off the
page: LaTeX sets each column to its natural width and has no reason to break a line
inside one. With them, each column becomes a real wrapping column (`p{}` in LaTeX, a
`<col>` width in HTML), and the text inside it wraps.

A percentage is relative to the text block in print and to the table in a browser, which
is the one spelling that means the same thing in both. It is also the one that fits: LaTeX
sizes a column's *text*, and the `array` package adds 6pt of separation either side of
every column on top of that, so a percentage has that separation subtracted from it and a
full 100% really occupies the text block rather than overflowing it by a few points per
column. An absolute length works too — `cm mm in pt pc em ex`, in either case — and is
passed through as measured, since `2cm` is a width someone counted and quietly making it
1.79cm would answer a different question.

`px` and `\linewidth` are refused: each would size one target and silently do nothing in
the other.

Widths are all or nothing: one entry per column, or none at all. That is deliberate, and
it is the answer to what a column with no width would do in a table where the others have
one — a partial list would have to invent a rule for the rest, and neither candidate rule
is something a reader could guess from the source. A count that disagrees with the
columns, a value that is not a length, and percentages summing past 100% each stop the
build naming the table.

### Sizing an image

An image takes `width`, `height` and `scale`:

```
![A hazel tree.](tree.png){#fig:tree width=60%}
![A catkin.](catkin.png){scale=0.55}
![A leaf.](leaf.png){height=4cm}
```

`width` and `height` are lengths — a percentage is relative to the text block and the text
height respectively, anything else is passed through as written. `scale` is a multiple of
the image's *natural* size, which is a different quantity: the two only coincide for one
particular image, which is why a `scale` cannot be rewritten as a `width` without guessing.

Several at once compose rather than compete: `width=3cm scale=0.5` sets the image to 3cm and
then halves it, giving 1.5cm. That is *not* what `\includegraphics` does on its own — its
own `scale` key is silently ignored whenever a width or a height is also given — so a scaled
image is wrapped in `\adjustbox`, which scales the finished box from outside. Passing all
three as plain options would have dropped an authored prop without a word, and would have
left the same document describing two different sizes in its two outputs.

A percentage means what it says in each place: `width=60%` is 60% of the text block,
`height=40%` is 40% of the text height, and `scale=50%` is simply half — the same as
`scale=0.5`. Writing a percentage where LaTeX wants a bare number is not a small mistake to
make silently, so it is converted rather than passed through: a raw `%` in an
`\includegraphics` option list opens a TeX comment that swallows the rest of the line and
fails the build. An empty value (`width=`) means the same as writing nothing.

In HTML, `scale` becomes CSS `zoom`, the one property that is the same operation: a
multiplier on the used size that still reflows the text around it. A percentage width would
be relative to the container, and `transform: scale()` does not reflow — either would make
the two outputs disagree about how large a figure is. In a browser without `zoom` the image
renders at its natural size, which is what a bare `\includegraphics` does too. A percentage
`height` becomes `vh` there rather than `%`: a CSS percentage height resolves against the
containing block, which for an image in normal flow has none, so it would quietly do
nothing. `width` is the one prop that still differs between the two: it has been a literal
HTML `width` attribute since long before this, and a browser reads that as a pixel count, so
`width=60%` sizes the print output exactly and the browser not at all.

### Table of contents, list of figures, list of tables

Each is a body-less directive written exactly where the list belongs — a thesis puts the
lists between the abstract and the contents, a report puts the contents first and no lists at
all, and neither position is fixed here. Having no body, each takes the one-line form:

```
!toc

!list-of-figures

!list-of-tables
```

The fence spelling (`:::{toc}` and a closing `:::`) parses to exactly the same thing and
keeps working. It is only longer, and `--in-place` rewrites it to the line above.

The entries come from the resolver's own derivations, which have existed since the numbering
pass was written and until now had no consumer. A document that asks for none of the three is
untouched; a misspelled name is an unregistered directive like any other, so the build stops
and names it.

The two LaTeX modes build the lists differently, and have to. Native mode emits
`\tableofcontents`, `\listoffigures` and `\listoftables` and lets LaTeX build each from its
own counters — right there, because in that mode LaTeX's numbers *are* the document's.
`--computed-numbers` renders the list itself, as lines with `\hyperref` links: that mode
bypasses LaTeX's counters everywhere else, and `\caption*` writes nothing at all to the `.lof`
file, so those commands would print an *empty* list rather than a differently-numbered one.
HTML always renders from the derivations, as a `<nav>` with a nested `<ol>` whose entries link
to their targets.

The heading each list prints — *Contents*, *List of Figures*, *List of Tables*, or *Sumário*,
*Lista de Figuras*, *Lista de Tabelas* under `lang: pt-BR` — is text this tool prints rather
than text the author wrote, so it follows the document's language like every other such word.

A figure written as a `figure` directive is listed like any other, since LaTeX's own `.lof`
lists it; subfigure panels are not, since `\listoffigures` does not list them either. A
directive that merely *numbers* as a figure — an admonition wearing a `#fig:` id — is not: what
belongs in a list of figures is what gets typeset as one.

Front matter is in none of the three, in either target and either mode: a heading inside an
abstract is set unnumbered, and a figure there gets an unnumbered caption, so LaTeX's own
`.toc` and `.lof` leave both out exactly as the derived lists do. The same rule covers an
unlabelled captioned float anywhere: it is not a numbering target here, so it must not be one
in the PDF either, or every float after it would print a number one ahead of what every
reference to it says.

### Multi-file documents

A line whose whole content is `!include <path>` splices another `.hdoc` file in at that
position:

```
!include chapters/methods.hdoc
!include "chapters/a long name.hdoc"
```

The path resolves against the directory of the file the line is written in, so a chapter's
own includes mean its own siblings wherever it is included from. The included file's Blocks
are spliced; its front matter is dropped, since a title or a bibliography belongs to the
document being built. Everything downstream sees one document, so an included chapter's
figures and sections number, cross-reference and diagnose exactly as if they had been typed
in place — including a reference written in the including file to a label defined in the
included one.

A missing file and an include cycle are both build warnings naming the file, not errors:
the offending include is dropped and the rest of the document still converts. An included
file that exists but fails to parse is different: it fails the whole build, naming the
file, exactly as the same content would if it were the top-level document being
converted — parsing it can drop authored content (a whole chapter, in the worst case), so
it does not get the warn-and-drop treatment the other two do. All three are reported at the
`!include` line that caused them, as `path:line:` before the message when the document has
a path (input read from stdin has none, so its messages carry no prefix). To write the literal text,
indent it four spaces or wrap it in backticks — the same shielding that protects a `:::`
directive fence.

`--computed-numbers` makes the LaTeX output carry the resolver's own
computed numbers and citation text instead of emitting `\Cref`/`\cite`
for LaTeX and BibTeX to resolve. Use it when the same document is also
emitted to HTML and the numbering has to match exactly; without it, the
`.tex` behaves like hand-written LaTeX and needs a BibTeX run for its
bibliography.

Resolver diagnostics — dangling references and citations, duplicate ids,
unknown directives, id-prefix/role mismatches, and a directive whose id
prefix names a different kind than the directive itself does (`:::{lemma
#thm:x}`, which native-mode LaTeX numbers as a lemma and everything else
as a theorem) — are printed to stderr as warnings and do not stop the
build.

A reference to an id written on an inline node -- an image that shares its
paragraph with text or another image is an inline image, not a figure; only
`![a](a.png){#fig:x}` alone in its paragraph is -- is not resolved (it renders
as `??`), but its warning says the id is on an inline image, which is not
referenceable, rather than that no target exists. Put the image in a paragraph
of its own to make it referenceable.

A duplicated id is warned about once, and a reference to it (`@fig:x`)
resolves to its **first** definition rather than being reported as
dangling. Each later node using that id keeps its own number (the second
`{#fig:x}` figure is still Figure 2) and is emitted under a distinct
`<id>-dup<n>` id, so the output has no repeated anchors.

Exit codes, so a `make`-style tool can act on them:

| code | meaning |
| ---- | ------- |
| 0 | the document was converted and written |
| 1 | the build failed (no representation for the target, a parse error, a missing or unreadable input file, a book ID or `--list-books` library that cannot be found, an invalid library directory file); nothing was written |
| 2 | the command line was wrong; the error and a one-line pointer to `--help` are printed |

### Libraries and books

A document can link to a passage of another book, `@{reformed:heidelberg#sec:q1}`, and
quote one, a `!quote reformed:heidelberg#sec:q1..sec:q3` line of its own (doc-10). The
books live in **libraries**, listed in a **library directory** file -- a YAML list:

```yaml
- name: reformed
  type: filesystem
  path: ~/books/reformed          # relative paths are relative to this file
  url: "https://example.org/reformed/{slug}.html#{label}"   # optional link template
```

Each entry needs a `name` (lowercase `[a-z0-9-]`, unique in the file) and a `type`;
`filesystem` takes a `path`, and may take `url` and `scripts`. A broken entry costs only
its own library: an unknown `type` or a library that cannot be built is reported, and the
other entries still load.

A `filesystem` library is a folder tree, and the folder path is the book's shelf:

```
~/books/reformed/                  <- library root (its name comes from the entry)
  confessions/                     <- shelf
    reformed/                      <- shelf confessions/reformed
      heidelberg.hdoc              <- single-file book, slug heidelberg
      westminster/                 <- book folder (it holds book.hdoc), slug westminster
        book.hdoc                  <- the book's entry document
        chapters/ch01.hdoc         <- part of the book through !include, not a book
  _drafts/                         <- ignored (a leading _ or .)
```

- A folder holding `book.hdoc` is a **book folder**: its slug is the folder name and
  everything inside it is that book's (its `!include`s resolve as they always do).
- Any other `*.hdoc` file is a **single-file book**, its slug the file name.
- Any other folder is a shelf. Names starting with `.` or `_` are ignored, and a file or
  folder whose name is not lowercase `[a-z0-9-]` is skipped with an `invalid-book-name`
  warning.
- The slug comes from the name on disk, never from front matter, and must be unique in the
  whole library (two books sharing one are a `duplicate-slug` warning, and a reference to
  that slug fails as `ambiguous-book`). Listing a library reads only front matter.

A book's canonical ID is `library:shelf/.../slug`, e.g. `reformed:confessions/reformed/
heidelberg`, but a reference only needs `library:slug`; a shelf path that no longer
matches (the book moved) still resolves, with a `stale-shelf` note. A passage after `#` is
a label (`#sec:q1`), a range of labels (`#sec:q1..sec:q3`), or a **locator** the book
itself interprets (`#14:8-9`).

#### Generated shelves and `scripts: true`

A shelf folder holding `shelf.clj` is a **generated shelf**: its books come from that
script rather than from `.hdoc` files -- for instance a whole Bible from one data file.
The script's last form is a map:

```clojure
{:data     ["almeida.json"]               ; files beside it the host reads and parses
 :books    (fn [data] [{:slug "romanos" :title "Romanos" :lang "pt-BR"} ...])
 :document (fn [data slug] {:meta {...} :blocks [...]})   ; the AST as plain data
 :locate   (fn [data slug locator] {:title "Romanos 14:8" :blocks [...]})}   ; optional
```

`data` maps each `:data` path, as written, to its parsed value (`.json`, `.yaml`/`.yml`,
`.edn`); the paths must stay inside the shelf folder. Everything else in that folder is
the script's data, not books. The script runs in the same sandbox as `--extensions` (no
file, network or process access, a 5 s timeout, output caps), every result is checked
against the AST schema, and a failure costs only that shelf (`script-failed`,
`invalid-generated-book`). The sandbox has no `parse-long`; parse digits with
`read-string`.

Scripts are **off by default**. Only the library's own entry can switch them on --
no document can:

```yaml
- name: bible
  type: filesystem
  path: ~/books/bible
  scripts: true
```

Without it the shelf's books are missing and a reference to one says why
(`scripts-disabled`).

A worked example is committed beside the example documents: `examples/libraries/bible/`
is a library whose `blivre/shelf.clj` serves a subset of the Bíblia Livre (BLIVRE), a
Portuguese Bible under CC BY 3.0 Brasil -- exactly the verses
`examples/catecismo-heidelberg.hdoc` cites -- and that document quotes every proof text
from it, with `examples/libraries/libraries.yaml` enabling the script. The library's own
README records the edition, the licence evidence, the attribution and what was changed.

```
:::{small-collapsable summary="Romanos 14:8" label="(2)"}
!quote bible:romanos#14:8
:::
```

(see "The Heidelberg catechism example" below for the build command).

Each quote renders as a `<blockquote class="book-quote" data-source="bible:blivre/romanos#14:8">`
with a "— Romanos 14:8" caption in HTML, and as a `quote` environment closed by that
source line in LaTeX.

The directory file is the first of these that exists:

1. `--libraries <file>` (which must exist);
2. `$HASELNUSS_LIBRARIES`;
3. `~/.config/haselnuss/libraries.yaml`.

None at all only matters to a document that uses a book reference.

```
haselnuss --libraries libs.yaml paper.hdoc               # resolve its book references
haselnuss --libraries libs.yaml --list-books reformed    # what is there to cite?
haselnuss --libraries libs.yaml reformed:heidelberg --target latex   # -> ./heidelberg.tex
```

`--list-books <library>` prints the canonical ID and title of every book, one per line,
tab-separated and sorted by ID. An unknown library is an error, and a build-only flag
given with it (`--target`, `--output`, ...) is warned about as having no effect.

A **book ID as input** builds that book: the argument is taken as a book ID when it
parses as one and no file or folder of that name exists. The output goes to `<slug>.<ext>`
in the current directory by default, not into the library -- and never over a file that
is already there, on any target: the name is one you never typed, so an existing
`<slug>.<ext>` (your own `apostles.hdoc`, or an earlier build) is refused until
`--output` names it. A book ID that cannot be
found is an error, and so is an unknown library or a library that failed to load. So is
no library directory at all. `--in-place` is refused with a book ID.

Book references resolve on the `html` and `latex` targets; `json` and `hdoc` keep them as
written, so `--libraries` there is warned about as having no effect (except to find a
book-ID input). A reference that cannot be resolved renders `??` (a quote: a `?? <ref>`
placeholder paragraph). It is reported as a warning naming the reference as written and
its `file:line`, plus the book's canonical ID once the book is known. The library
directory's own problems -- an entry with an unknown `type`, a library that failed to load,
a name defined twice -- are printed once per build, located at their line in the
directory file. None of these fails the build: the output is still written and the exit
code is 0. A directory file that cannot be read, or is not a valid list of libraries,
does fail it.

### Overlays

A repeatable `--overlay PATH` flag layers one or more separate `.hdoc` files onto the
base document at build time, without the base document ever referencing them:

```
haselnuss paper.hdoc --overlay alt-text.hdoc --overlay editorial-notes.hdoc
```

Each overlay file is an ordinary `.hdoc` document whose top-level blocks are expected to
be entirely `prepend`/`append` directives, each carrying `target=<id>` — the `attr.id` of
the Section (part, chapter, or section, any level) in the *base* document to patch:

```
:::{prepend target=ch:intro}
Extra intro material, injected before the chapter's own first block.
:::

:::{append target=sec:results}
A browser-only interactive chart, injected after the chapter's own last block.
:::
```

`prepend` injects its own blocks at the start of the target Section's own content;
`append` injects them at the end. Injection happens before numbering, so spliced content
numbers and cross-references exactly as if it had been typed into the base document —
including a target Section that only exists after the base document's own `!include`s
have been expanded (e.g. a chapter pulled in from another file). Multiple `prepend`/
`append` directives naming the same target, across one or more `--overlay` files,
accumulate in `--overlay` order, then in each file's own top-to-bottom order.

An `!include` written inside an overlay file resolves against *that file's own*
directory, with the same cycle detection the base document gets — an overlay that
includes itself is reported as a cycle, not a hang.

Every other case is a distinct, non-fatal build warning, and the offending content is
dropped rather than guessed at or silently merged into the base document: a `target=`
that is missing or blank, a `target=` that does not resolve to anything in the base
document, and any top-level block in an overlay file that is not a recognized `prepend`/
`append`/`describe` directive (a stray paragraph, or a directive name this build does not
recognize). A `prepend`/`append`/`describe` directive's own id, if it is given one, is
discarded once its content is spliced into the target — also warned about, since it can
otherwise look referenceable and silently never be. A `--overlay` path that does not name
a file is a hard build failure naming the path, unlike a build-time diagnostic — it is a
path typed on the command line, not a reference embedded in document prose.

Omitting `--overlay` entirely builds the base document byte-for-byte identically to a
build with no overlay mechanism at all, whether or not an unused overlay file happens to
exist on disk. `--overlay` has no effect on `--target json`, matching `--bibliography`'s
own existing behavior there.

Writing a `prepend`/`append`/`describe` directive directly in the *base* document, rather
than in a file named by `--overlay`, is not a way to use this feature — it is an ordinary,
unregistered directive there, so it warns as `unknown directive` and then fails the build
like any other undefined one.

### `describe`

`describe` attaches flat metadata to *any* referenceable node, not only a Section —
a Figure, Table, MathBlock, or a referenceable Directive too — addressed the same way,
by `attr.id`:

```
!describe{target=fig:tree alt="A hazelnut tree in early spring bloom, seen from the orchard path."}
```

`describe` carries everything in its header and nothing in a body, so it is written in the
one-line form — which is what makes an alt-text overlay one line per figure rather than two.
The fence spelling parses identically and still works.

Every prop other than `target` merges into the target node's own `attr.props`. This needs
no schema change (`props` already exists on every node's `Attr`) and is invisible to both
emitters by default: HTML renders it as an ordinary tag attribute on the target element
(present in the markup, never as visible page text), with one exception: on a Figure,
`alt` is not a valid `<figure>` attribute and assistive technology reads it from `<img>`,
so `alt` replaces the alt of the figure's own `<img>` and is never emitted on `<figure>`.
A figure made of subfigure panels has no image of its own, so `alt` on the parent is
dropped; `describe` each panel by its own id instead. Every other prop (and `alt` on a
non-figure target) lands as an attribute on the target's own element. LaTeX has no generic `props`
target at all, so a `describe`d prop never appears in the `.tex` output. That is exactly
the "present but not shown unless something opts in" shape a use case like a per-figure
textual description — for an eventual audiobook or accessibility pass — wants.

A prop key colliding with one already set on the target node — one the base document's
own author wrote — is its own non-fatal build warning, and the target's own value is kept
rather than silently overwritten. A prop key set by *more than one* `describe` — whether
the two directives sit in one overlay file or in two named separately on the command
line — is also a non-fatal build warning naming the prop and the target: the later
`describe`, in `--overlay` order then in-file order, still wins (accumulation order is
unchanged), but which value was discarded is no longer silent. Setting *different* props
on the same target from more than one `describe` is not a collision and stays silent.
`describe` shares every other diagnostic `prepend`/`append` already has: a missing or
blank `target=`, a `target=` that resolves to nothing, its own id (if given one) being
discarded, and being dropped as unrecognized top-level content when misspelled.

Not in scope: a multi-paragraph or richly-marked-up description. `attr.props` is a flat
`string -> string` map, so `describe` only fits a caption-length string per key — a richer
description (its own cross-references, math, multiple paragraphs) would need an actual
AST addition, and is left for a future extension.

### Annotations

A PDF reader lets you lay two things over someone else's document without editing it: a
**highlight**, a coloured wash over a run of text, and a **sticky note**, a balloon wedged
between two words that opens on click. Two more overlay directives do exactly that, and
because they are overlay directives the base document never references them — the review
lives in its own file and the document stays as its author wrote it.

```
!highlight{target=sec:why text="wind-pollinated and self-incompatible"}

:::{highlight target=sec:why text="four or five years" color=green author="DM"}
Worth saying which cultivars this holds for.
:::

:::{sticky-note target=sec:spacing text="three metres is the usual compromise" at=end author="DM"}
Is this the spacing the table models, or the one you actually planted?
:::
```

A worked example ships in `examples/review.hdoc`, a review pass over `examples/hazelnuts.hdoc`:

```sh
lein uberjar
cd examples && mkdir -p out && cp catkins.png out/
java -jar ../target/uberjar/haselnuss.jar hazelnuts.hdoc --overlay review.hdoc \
  --output out/reviewed.html
java -jar ../target/uberjar/haselnuss.jar hazelnuts.hdoc --overlay review.hdoc \
  --target latex --computed-numbers --output out/reviewed.tex
pdflatex -output-directory=out out/reviewed.tex   # twice: the annotation quads settle
pdflatex -output-directory=out out/reviewed.tex   # on the second pass
```

That is the "Example" section's own recipe with `--overlay review.hdoc` added, writing to
`reviewed.*` so the annotated build sits beside the plain one rather than overwriting the
thing you would compare it against. `mkdir -p out` is not optional: the converter writes the
file you name and does not create directories for it. Nor is the second `pdflatex` pass, the
way it nearly is for cross-references alone — a PDF annotation's `/QuadPoints`, the boxes
that make a highlight follow the line wrap, are written from positions recorded on the
previous run, so one pass produces a PDF with no highlight objects in it at all.

#### How an annotation says where it goes

An annotation is not addressed to a *node*. It is addressed to **a run of words inside
one**, and it says which run by **quoting it**:

- `target=` is required and names an `attr.id`, as every overlay directive's does. It scopes
  the search, which is what lets the quote stay short. Unlike `prepend`/`append` (which need
  a Section) and `describe` (which needs a referenceable node), an annotation accepts any
  node carrying the id — it is searching inside it, not patching it.
- `text=` is required: the words to mark.
- `before=` / `after=` / `nth=` disambiguate a quote that occurs more than once inside the
  target, in that order of desperation. `before=`/`after=` name the text immediately
  either side; `nth=` is a 1-based index among what survives them.

Quoting rather than counting is the whole design. A node index would address a *parse*
detail — whether `foo bar` is one text node or two is flexmark's business, not the
document's — and would drift silently the moment a sentence was inserted above it: the
build would still succeed and the highlight would still render, over the wrong words. A
quote spans emphasis, links and line breaks indifferently, because it is matched against
the flattened text; whitespace is normalised on both sides, so reflowing a paragraph does
not break it; and it is legible in a diff and greppable in the base document.

#### Props both directives take

| prop | meaning |
| --- | --- |
| `color` | `yellow` (default), `green`, `blue` or `pink`. A closed set: the value has to map to both a CSS colour and a PDF annotation colour, so an unrecognised one is a diagnostic rather than a silently unstyled annotation. |
| `author` | who made the annotation. A `data-author` attribute in HTML, and the annotation's own author and subject in the PDF. Present in the markup, never visible page text. |
| `at` | `start` or `end` (default `end`), **`sticky-note` only**: which edge of the matched run the balloon sits at. "Between two words" is the quote of the word before, with the default. |
| `style` | `annotation` or `footnote`, **`sticky-note` only**, and **LaTeX only**: see the degradation below. |

That table is the whole vocabulary — an annotation recognises exactly those props plus the
addressing ones (`target`, `text`, `before`, `after`, `nth`), and **anything else is a
warning and drops the annotation**. A misspelled `colour=green` used to be silently ignored,
which looked like an annotation that simply came out unstyled, so it says so now:

```
haselnuss: warning: this "highlight" directive carries colour=, which is not a prop an
annotation recognises (a misspelled color= or author= would otherwise be silently ignored);
the annotation for "four or five years" was dropped
```

Everything the directive itself can get wrong is diagnosed the same way, and each drops only
its own annotation: a missing or blank `text=`, a `color=` or `at=` outside its set, an
`nth=` that is not a whole number, `at=` or `style=` written on a `highlight` (both belong to
a balloon), a `style=` outside `annotation|footnote`, and a `sticky-note` with no body — an
empty balloon says nothing. The overlay diagnostics `prepend`/`append`/`describe` already
have apply too: a missing or blank `target=`, a `target=` that resolves to nothing, and a
directive carrying its own id, which is discarded.

A `highlight` **with a body** is a highlight plus a sticky note — the reader's "highlight
with a comment" — built from the two nodes rather than a third.

An annotation never renumbers the document it annotates. Anything in a sticky note's body
that would take a number — a Figure, Table, display equation or Section, a footnote, or any
block carrying an `attr.id` at all, since numbering keys on an id's prefix rather than on a
node's type — would take that number out of the base document's own sequence and push
everything after it along. So the block carrying it is diagnosed and dropped and the rest of
the body is kept; if that leaves nothing, the whole annotation is dropped rather than a mark
with no comment on it. The check looks at a block's whole subtree, not just its own type: a
figure nested inside a block quote inside a list item shifts the document exactly as much as
one written at the top of the body.

#### What each target does with them

**HTML.** A highlight is a `<mark>`; a sticky note is an inline disclosure whose balloon
floats over the text — click to open, click to dismiss, no JavaScript. `color=` becomes a
class rather than an inline style, so anyone bringing their own CSS (`--no-stylesheet`, or
the emitter's `:stylesheet` option) can restate it; an inline style could not be overridden.

The balloon is a hidden checkbox and its `<label>`, not a `<details>`, and the reason is
worth knowing if you are styling it: everything a sticky note emits sits inside a `<p>`, and
`<details>` is flow content, so an HTML parser *closes the paragraph* when it meets one. The
markup a sticky note emits is phrasing content throughout, which is what keeps the sentence
around it intact.

**LaTeX.** Both become **real PDF annotations** — objects in the page's own `/Annots` that
Foxit or Acrobat shows as a clickable highlight and a dismissible balloon, not ink drawn on
the page. This needs two `pdflatex` passes for the annotation geometry to settle — the
`/QuadPoints` that make a highlight follow the line wrap are written from positions recorded
on the previous run, so one pass produces a PDF with no highlight objects in it at all. The
recipe above runs it twice for exactly that reason.

A PDF annotation's body is a plain string, so a sticky note whose body is more than flat
prose — math, a citation, emphasis, more than one paragraph — is emitted as an ordinary
`\footnote` instead, with a warning naming what forced it. Nothing is lost and the note
stays attached at the right point; the cost is a footnote number, and the warning says so.
`style=footnote` forces that form; `style=annotation` cannot force the reverse, because the
constraint is PDF's rather than this program's, and says so when asked.

#### When an anchor goes stale

This is the failure mode you will actually meet, and it is the reason the design quotes
rather than counts. Edit the base document so the quoted words are gone, and the build
tells you by name and drops that one annotation:

```
haselnuss: warning: no text matching "wind-pollinated and self-incompatible" was found in
"sec:why" -- the annotation was dropped. If the base document was edited, update the quote
to match its current wording.
```

The other outcomes are diagnosed the same way, and each names the annotation it is about:

| what happened | warning |
| --- | --- |
| the quote is not in the target | `no text matching "…" was found in "…" -- the annotation was dropped. If the base document was edited, update the quote to match its current wording.` |
| it occurs more than once and nothing disambiguates it | `"…" occurs 2 times in "…" -- the annotation was dropped rather than applied to a guess. Add before=, after= or nth= to say which one is meant.` |
| `nth=` is past the end | `nth=9 is out of range: "…" has 2 matches in "…" -- the annotation was dropped.` |
| `before=`/`after=` rejected every occurrence — a different mistake, with a different fix | `"…" was found in "…", but no occurrence of it has before="…" immediately before it -- the annotation was dropped. Check the context props against the document's current wording.` |
| a boundary falls inside something that cannot be split — inline code, math, an image | `the quoted run "…" in "…" ends inside a code, which has nothing to split -- the annotation was widened to cover it whole` |
| a sticky note's `at=` edge falls inside one | `the sticky note anchored at "…" in "…" has its end edge inside a code, which is not a position between two inlines -- it was moved to the end of that node` |

The last two are the only entries here that still apply the annotation: the quote plainly
meant to cover what it landed inside, and a slightly wider highlight — or a balloon at the
nearest real seam — is a better failure than a missing one, but it is never silent. Every
other row drops the annotation it names, and only that one.

None of these is fatal. The build finishes, exits 0, and produces the document with the
annotations that still hold. **The fix is to update the quote in the overlay file** to
match the document's current wording.

**Re-anchoring is not implemented**, and that is a deliberate boundary rather than an
oversight. When a quote goes stale, something has to update the overlay file, and today
that is a person with `grep` — which is workable precisely because a quote is legible and
greppable. A `--reanchor` mode that fuzzy-matched a stale quote against the current
document and rewrote the overlay would need a similarity metric, a confidence threshold,
and a policy for writing to a file you did not name as an output; the last of those is the
real obstacle, and none of the three has an obvious answer. What this milestone owes such a
mode is the diagnostic that makes staleness visible, which is the table above.

Writing `highlight` or `sticky-note` directly in the *base* document is not a way to use
this feature, exactly as with `prepend`/`append`/`describe`: it is an ordinary unregistered
directive there, and fails the build.

### Author-defined extensions

A repeatable `--extensions PATH` flag loads a plain Clojure file defining a custom
directive — its own name, and how it renders on the `html` and `latex` targets — with no
recompile:

```
haselnuss paper.hdoc --extensions callout.clj
```

`callout.clj`:

```clojure
{:name "callout"
 :emit {:html (fn [directive ctx]
                [:div {:class "callout"} ((:render-blocks ctx) (:blocks directive))])
        :latex (fn [directive ctx]
                 ["Note: " ((:render-blocks ctx) (:blocks directive))])}}
```

The file's last form must evaluate to a map like this one — the same `{:name :kind?
:emit? :lower?}` shape every built-in extension already uses internally — or a vector of
several, for a file defining more than one directive at once. `:emit`'s `:html`/`:latex`
functions take the directive being rendered and a rendering context whose `:render-blocks`
callback renders the directive's own nested content through the *real* emitter, so a
custom directive supports every construct the core one does, composed rather than
hand-rolled — the way `["Note: " ...]` above is a plain, escaped string followed by
already-rendered content, not a hand-built string. `:html`'s return value is hiccup-shaped
data (`[tag attrs? & children]`); a plain string or keyword, on either target, is escaped
exactly like authored prose. A directive with no representation on some other target
(`json`, or a target this file's own `:emit` map omits) needs a `:lower` rule — see
`haselnuss.registry`'s own docstring for the full shape, `:kind`'s numbering-lexicon
fragment included.

Loaded extensions are scoped to the one build that loaded them: nothing a `--extensions`
file defines is visible to a later, separate conversion, even in the same process, and an
unused `--extensions` file changes nothing about a build that never names it.

The file is evaluated in a sandbox with **no host interop, no file reads, no process
execution, and no `require` reaching anything on this machine's classpath** — an attempt
at any of these is refused, and a build that never terminates is stopped rather than left
to hang. Output a directive's renderer produces is capped in size, and anything the file
itself prints during evaluation is captured rather than mixed into the build's own output.
An extension entry that does not match the expected shape is refused with an error naming
the file.

A separate flag, `--trusted-extensions`, allows every `--extensions` file loaded in that
run to emit genuinely verbatim, unescaped output (`haselnuss.ext/raw`) — needed for
something that must be script or markup exactly as written, a browser-only interactive
widget being the motivating case — and lifts every filter described under "What an
untrusted extension may emit" below. It defaults to off, and a sidecar file can never grant
itself this on its own. Without it, what `:render-blocks`/`:render-inlines` hand back may be
returned only exactly as handed back, in the same call: one changed (with `assoc`, `merge`,
`into`, …) or kept from an earlier call fails the build with an error naming the directive
and its line, since it would otherwise be verbatim output by another route.

**What an untrusted extension may emit.** Without `--trusted-extensions`, an extension's
output — what its renderers return and render through their callbacks, and what its `:lower`
rule returns — is filtered by the same rules as the document ("Converting a document you do
not trust"), plus two refusals. The rule of thumb: what a *document* can cause — a URL or attribute a
renderer copied from the directive's props — is dropped with a warning naming the directive
and its line, and the build goes on; what only the *extension's own code* can cause fails
the build with an error naming the directive, its line and the offending tag or command.

- **HTML elements** must be on an allow-list: `a abbr address article aside audio b bdi bdo
  blockquote br button caption cite code col colgroup data dd del details dfn div dl dt em
  figcaption figure footer h1`–`h6 header hgroup hr i img ins kbd label li main mark meter
  nav ol p picture pre progress q rp rt ruby s samp section small source span strong sub
  summary sup table tbody td tfoot th thead time tr track u ul var video wbr`. Anything else
  — `script`, `style`, `link`, `meta`, `base`, `iframe`, `object`, `embed`, `form` and its
  controls, `svg`, `math`, `template`, a custom element — is refused.
- **HTML attributes**: an `on…` handler, an attribute from the closed URL list whose value
  fails the scheme test (every `srcset`/`ping` entry included), `is` (which turns an element
  into a custom element), and an attribute name that is not a well-formed name
  (`"x onclick"`) are each dropped with a warning. `style` passes, as it does on the
  document.
- **AST** — what a renderer hands `:render-blocks`/`:render-inlines`, and the Blocks a
  `:lower` rule returns — goes through the document pass first: a hand-built link to
  `javascript:…` renders as its text, an `onmouseover` prop is dropped, each with a warning,
  on both targets.
- **TeX** in that AST is held to an **allow-list**, and the build is refused at the first
  token outside it. In math (`:tex`), the allowed control words are ordinary mathematics:
  Greek letters; large operators and named functions (`\sum`, `\int`, `\lim`, `\sin`,
  `\log`, …); binary operators, relations and arrows (`\cdot`, `\le`, `\equiv`, `\in`,
  `\subseteq`, `\to`, `\Rightarrow`, …); symbols (`\infty`, `\partial`, `\nabla`, `\ldots`,
  …); delimiters and sizing (`\left`, `\right`, `\middle`, `\big`…`\Bigg`, `\langle`, …);
  accents (`\hat`, `\bar`, `\vec`, `\widehat`, `\overline`, `\overbrace`, …); fonts
  (`\mathrm`, `\mathbf`, `\mathit`, `\mathsf`, `\mathtt`, `\mathcal`, `\mathbb`,
  `\mathfrak`, `\boldsymbol`) and text (`\text`, `\textrm`, `\textbf`, `\textit`, `\mbox`,
  `\operatorname`); `\frac`, `\dfrac`, `\tfrac`, `\sqrt`, `\binom`, `\boxed`, `\phantom`;
  spacing and style (`\quad`, `\qquad`, `\displaystyle`, `\limits`, …); `\bmod`, `\pmod`;
  `\tag`, `\notag`, `\nonumber`, `\hline`. The control symbols `\,` `\;` `\!` `\:` `\>`
  `\ ` `\\` `\{` `\}` `\|` `\#` `\$` `\%` `\&` `\_` are allowed, and `\begin`/`\end` only
  around `matrix`, `pmatrix`, `bmatrix`, `Bmatrix`, `vmatrix`, `Vmatrix`, `smallmatrix`,
  `cases`, `aligned`, `alignedat`, `gathered`, `split` or `array` — with `array`'s column
  spec limited to `l`, `c`, `r` and `|` (after an optional `[t]`/`[b]`/`[c]`) and
  `alignedat`'s to a column count, since a `*{n}{…}` repeat hangs pdflatex and `@{}`, `!{}`,
  `>{}`, `<{}` and `p{}` carry arbitrary TeX. `\label` and `\color` are
  not on the list (a label writes to the `.aux` file and can collide with the document's);
  `src/haselnuss/sanitize.clj` (`tex-math-words`) has the complete list. Every other string
  the LaTeX emitter writes unescaped — an image path, a citation key — may hold no control
  sequence at all, and an image path or citation key none of `%`, `#`, `&`, `$`, `^`, `~`
  either. (An image's alt text is never TeX: LaTeX does not render it.) In either, `^^` notation, an unbalanced brace, and an allowed word
  directly followed by `@` or `:` (the start of a longer `\makeatletter` or expl3 name such
  as `\tex_input:D`) are refused.

  This check is strict but **best-effort**: TeX is a programmable macro language, and an
  allow-list is far harder to get past than the deny-list it replaced (which an expl3
  `\ior_open:Nn`, `\UseName{input}` and `\begin{input}` all got through), but it reasons
  about how TeX tokenises under the document's normal catcodes rather than running TeX.
  TeX the *document* wrote is exempt: a string the directive itself holds where TeX is
  spliced (its own math, its props) passes, so a renderer passing the directive's own body
  through renders the author's math exactly as it would render outside the directive. Prose
  the document wrote does not count — a code sample's `\input{...}` that an extension moves
  into a math node is refused.

A renderer's own LaTeX output needs no further filter: on that target a returned string is
always escaped, so the only ways to emit raw TeX are `haselnuss.ext/raw` and the callbacks,
both covered above. All of this applies to every registration that is not `:trusted`,
the built-in `collapsable` included (which emits nothing it would catch). A `--transform`
rule is not an extension in this sense: its output runs through the document pass like
authored content, and its TeX counts as the document's own.

`examples/reveal.clj` and `examples/reveal-demo.hdoc` are a worked example of exactly
this: a `reveal` directive — a small quiz prompt with a button that reveals its answer —
whose `html` rendering needs `--trusted-extensions` (an inline `<script>`, spliced in via
`haselnuss.ext/raw`, that an ordinary escaped hiccup child could not carry intact) and
whose `latex` rendering is a distinct, print-appropriate fallback: the question followed
directly by its answer, with no click for a PDF to defer to and no `:raw`/trust involved
at all on that half. Building it without `--trusted-extensions` fails loudly with the
same error a host-defined untrusted renderer gets; `examples/reveal.clj`'s own header
comment has the exact commands to try both ways.

**What this does and does not protect against.** This sandbox is for an author running
their own file: it is built to catch a mistake — a typo that tries to read a file, a
copy-pasted loop that never terminates, a stray debug `println` — not to withstand a
determined attacker. It does not run in a separate, killable process, so a single runaway
computation inside the sandbox is stopped from hanging the *build*, but is not guaranteed
to be reclaimed instantly. Treat `--extensions` the way you would treat running someone
else's script directly: fine for your own files, or a collaborator's; not a boundary for
converting a document from someone you do not trust.

### Scripted transformations

A repeatable `--transform PATH` flag loads a plain Clojure file of *rules* — each one a node
selector paired with a rewrite function — and applies them to the parsed document before
anything else touches it:

```
haselnuss paper.hdoc --transform tidy.clj
```

`tidy.clj`:

```clojure
{:select :section
 :transform (fn [node _ctx]
              (update node :heading haselnuss.ext/map-text clojure.string/upper-case))}
```

The file's last form must evaluate to a rule map like this one, or a vector of several —
the same "last form is the contract" convention `--extensions` uses. A rule is `{:select
:transform :name? :scope?}`: `:select` decides which nodes it touches, `:transform` is
called on each one as `(fn [node ctx] -> ...)`, and an optional `:name` labels the rule in
any diagnostic or error it produces (falling back to its position among the file's own
rules plus its own `:select`, so a message is never anonymous).

**The selector vocabulary.** A selector is exactly one of four shapes:

| shape                        | matches                                                        |
| ----------------------------- | --------------------------------------------------------------- |
| a keyword                     | shorthand for `{:t <keyword>}` — every node of that type        |
| a map                         | every entry must match (AND)                                    |
| a vector                      | any element, itself a selector, must match (OR)                 |
| a function `(fn [node] -> truthy)` | called directly on the node                                 |

A map key reads the node's own same-named field — `{:t :list :ordered true}` needs no
special case — except four keys that read something not sitting at the top of the node:

| key      | reads                                                                    |
| -------- | -------------------------------------------------------------------------- |
| `:id`    | `(get-in node [:attr :id])`                                              |
| `:class` | membership in `(get-in node [:attr :classes])`                           |
| `:prop`  | a `{k v}` map, every entry matched against `(get-in node [:attr :props])`|
| `:text`  | the node's own flattened plain text — every `:str`/code/math leaf reachable from it, markup stripped |

Every value, under any key, is matched the same way regardless of which key it sits under:
a set means membership, a regex means `re-find` against the string form of the actual value
(so `{:t #"^section$"}` matches the keyword `:section`), a function is applied to the actual
value, and anything else — a string, a number, a keyword, `nil` — is compared with `=`. That
one rule is what gives `{:id #"^fig:"}`, `{:class #{"warn" "info"}}` and `{:level (fn [n] (>
n 2))}` for free, with no special case written for any of them.

**The return-value contract.** `:transform`'s own return value is the whole vocabulary a
rule has for changing the tree:

- a node map — replaces the node
- `nil` — deletes it
- a sequence of nodes — splices them in where it was

`ctx` (the function's second argument, the same two-argument shape a sidecar `:emit`
renderer already takes) carries `:path` and `:ref-kind` (`haselnuss.walk`'s own path vector
and reference kind for the matched node) and `:meta`, the document's own front matter,
read-only. Rules apply in file order, then declaration order within a file, each one as one
full pass over the whole document before the next rule runs — "uppercase every heading,
THEN drop every draft block" means exactly what it says, not first-match-wins at a single
node, and a rule's own replacement is never re-visited by that same rule, so a rule that
wraps its match in a fresh parent still terminates. Delete and splice are not legal
everywhere: a position with room for exactly one node (a Figure's own content, the document
root) refuses any other count, naming the rule and the field. A returned node that does not
fit where it landed is refused too, with the same schema explanation `haselnuss.ast/explain`
gives anywhere else in this tool. A rule whose selector matches nothing in the whole
document is not silently a no-op: it is a warning naming the rule, on stderr, the same as a
resolver diagnostic — a rule that runs and visibly does nothing is the easiest kind of
authoring mistake to miss on your own.

`--transform` runs immediately after parsing, before overlays, extensions, resolving or
lowering — and before the `json`/`hdoc` targets' own early return, so it is the one flag
that has an effect on *every* target, including those two. It composes with every output
mode above exactly as any other build does: `--output PATH`, `--output -` (piped straight
out, nothing else on stdout), and `--in-place` — which, with no explicit `--target`, is what
turns a `--transform` file into a way to rewrite a document's own source in place, with the
same atomicity and refusal guarantees `--in-place` above already has, unchanged here.

The sidecar file runs in the identical sandbox `--extensions` uses — shared as one forked
context per build when a run passes both flags — so the same bounds apply verbatim: no host
interop, no file reads, no process execution, no `require` reaching this machine's
classpath, a wall-clock timeout, and captured, size-capped output. `--trusted-extensions`
grants a `--transform` rule nothing at all: a rule's return value is plain AST data only — a
node, `nil`, or a sequence of nodes — never `haselnuss.ext/raw`'s verbatim escape hatch, so
there is nothing here for that flag to gate. **What this does and does not protect against**
is the same as above, restated for a rule rather than a directive renderer: this sandbox is
for an author running their own file: it is built to catch a mistake — a typo that tries to
read a file, a copy-pasted loop that never terminates, a stray debug `println` — not to
withstand a determined attacker. It does not run in a separate, killable process, so a
single runaway computation inside the sandbox is stopped from hanging the *build*, but is
not guaranteed to be reclaimed instantly. Treat `--transform` the way you would treat
running someone else's script directly: fine for your own files, or a collaborator's; not a
boundary for converting a document from someone you do not trust.

Three limitations worth knowing before reaching for this, each at the point a document
actually meets it:

- **Includes are not expanded.** `--transform` sits before `resolve`, and expanding an
  `!include` line into the file it names is `resolve`'s own job (see "Multi-file
  documents" above) — so a rule's selector only ever sees the including file's own
  top-level content, never reaches into a file it `!include`s, no matter how broad its
  `:select` is. When the document still holds one or more unexpanded `!include`s at that
  point, the build says so: a `:transform-partial-document` warning, naming every skipped
  path concretely (`--transform ran before 2 !includes were expanded, so these rules never
  saw: chapters/intro.hdoc, chapters/methods.hdoc`), fires once per build whenever
  `--transform` runs at all — whether or not any rule went on to match something in the
  including file itself. A document with no `!include` at all produces none of this.
- **`--in-place` still re-spells canonically.** `--target hdoc`, and therefore `--in-place`
  (which implies it), always writes back one emphasis delimiter, ATX headings and
  normalized wrapping — the same note under "Usage" above — so running a `--transform` file
  whose every rule matches nothing at all still rewrites the document: the diff is the
  formatter's own canonical re-spelling, not evidence a rule silently changed something.
  Two parts of that re-spelling worth knowing: footnotes are **renumbered** in order of
  first reference (`[^one]` becomes `[^1]`), because the parsed document keeps no
  footnote label to write back; and a backslash is written only where the character would
  otherwise read differently (`>` or `:::` at the start of a line, an `@word` that is a
  real reference), so `Links: x`, `7 > 3` and `cc @someone` stay as written.
- **Four `Inline` variants have no `.hdoc` spelling of their own.** `:small-caps`, `:sub`,
  `:sup` and `:sticky-note` are schema-valid and every emitter renders
  them, but nothing in `.hdoc`'s own surface syntax ever parses into one — the one way to
  meet this is a rule's own `:transform` constructing one directly (wrapping matched text in
  `{:t :small-caps ...}`, say), or, for a sticky note, an `--overlay`
  annotation. Write that back out through `--target hdoc`/`--in-place` and the first three
  come back as a generic `[text]{.sub}`-shaped role span, which re-parses as a `:span`
  rather than the tag the rule built; a sticky note comes back as a footnote, which preserves
  every block of its body but not its `Attr`, since a footnote has none.
  `haselnuss.emit.hdoc/lossy-role-span-diagnostics` can report every occurrence a document
  carries, but nothing on the command line calls it today, so this is silent unless you know
  to look for it. `:highlight` is the exception: `==…==` is its own spelling, so it is an
  exact round trip — except one nested inside another, which that spelling cannot express and
  which comes back as a `[text]{.highlight}` role span. `:strike` is the same kind of
  exception since `~~…~~` became its spelling: an exact round trip, except one strike nested
  inside another, which comes back as a `[text]{.strike}` role span.

`examples/publish.clj` and `examples/draft.hdoc` are a worked example: three rules
stripping the drafting scaffolding a real page might actually carry before it goes out — an
internal-only editorial directive deleted outright, a paragraph of reviewer `TODO:` items
spliced into several separate, numbered admonitions, and a heading's leftover `[DRAFT]`
marker tidied by regex — exercising all three return shapes in one file. `examples/
publish.clj`'s own header comment has the exact commands, building into `examples/out/` as
ever.

## Example

`examples/hazelnuts.hdoc` is a short real document — sections, a figure,
a table, display and inline math, citations, and two custom directives —
that converts to both targets:

```sh
lein uberjar
cd examples && mkdir -p out && cp catkins.png out/
java -jar ../target/uberjar/haselnuss.jar hazelnuts.hdoc --output out/hazelnuts.html
java -jar ../target/uberjar/haselnuss.jar hazelnuts.hdoc --target latex --computed-numbers \
  --output out/hazelnuts.tex
pdflatex -output-directory=out out/hazelnuts.tex   # twice, so cross-references settle
pdflatex -output-directory=out out/hazelnuts.tex
```

Everything the build produces lands in `examples/out/`, which is ignored, rather than
beside the sources: a conversion writes an `.html`, a `.tex` and (in native mode) a
`.bib`, and pdflatex adds five or six more files of its own. Two commits in this
repository's history swept some of those in and had to be amended (TASK-50.5). The
`cp catkins.png out/` is what keeps the HTML's `<img src="catkins.png">` resolving for a
browser, which has no equivalent of pdflatex's search path; run pdflatex itself from
`examples/`, as above, so *it* finds the figure.

A native-mode build -- no `--computed-numbers`, letting LaTeX and BibTeX do the numbering
-- needs BibTeX run from inside `out/`, or it looks for the generated `.bib` in the
current directory and quietly leaves every citation unresolved. Three pdflatex passes,
not two: the third is what turns `[?]` into `[1]`, matching the sequence under
[Usage](#usage) above.

```sh
java -jar ../target/uberjar/haselnuss.jar hazelnuts.hdoc --target latex \
  --output out/hazelnuts.tex
pdflatex -output-directory=out out/hazelnuts.tex
(cd out && bibtex hazelnuts)
pdflatex -output-directory=out out/hazelnuts.tex
pdflatex -output-directory=out out/hazelnuts.tex
```

The two outputs agree on every number and citation. They differ where
the graceful-degradation contract says they should: the `collapsable`
directive is an interactive `<details>` widget in HTML and flattens to
its content in print, and the `admonition` directive is a styled
`<div class="admonition" role="note">` in HTML and an indented italic block
in print. Both carry the same head — `Note 1` — and both are
cross-referenceable, which is what lets the document's conclusion point
back at the admonition by id. Lowered directives (theorem-like, admonition, figure
panel grids) are `<div>`s, never `<blockquote>`s; only a node carrying the
in-memory `:container` marker that the CLI sets becomes one, so an authored quote
with a `.proof` class stays a quote. Props are written as HTML attributes
(`lang=en` is a real `lang`), except the document-model ones `caption`, `key` and
`ref`, which are never emitted.

`--computed-numbers` is what makes the two outputs agree number for number, which is what
this example is for. A native-mode build works too: it delegates the reference list to
BibTeX, and Haselnuss generates the `.bib` BibTeX needs from the same CSL-JSON the resolver
reads — see [Bibliographies](#bibliographies).

### The Heidelberg catechism example

`examples/catecismo-heidelberg.hdoc` has about 1,400 Bible proof texts, none of them
copied into the file: each is a `!quote bible:<book>#<passage>` line, resolved from the
library in `examples/libraries/` (the Bíblia Livre, CC BY 3.0 Brasil; see that folder's
`bible/README.md` for edition, licence evidence and attribution). The library needs
`scripts: true`, which `examples/libraries/libraries.yaml` sets. Build into `out/` as above:

```sh
cd examples
java -jar ../target/uberjar/haselnuss.jar catecismo-heidelberg.hdoc \
  --libraries libraries/libraries.yaml --output out/catecismo-heidelberg.html
java -jar ../target/uberjar/haselnuss.jar catecismo-heidelberg.hdoc \
  --libraries libraries/libraries.yaml --target latex --computed-numbers \
  --output out/catecismo-heidelberg.tex
pdflatex -output-directory=out out/catecismo-heidelberg.tex   # twice
pdflatex -output-directory=out out/catecismo-heidelberg.tex
```

The PDF is about 240 pages. The library folder is whitelisted in `examples/.gitignore`
(`!libraries/**`), since that file otherwise ignores every subdirectory.

## Requirements

- [Leiningen](https://leiningen.org/) 2.x
- JDK 11+

## Build

```sh
lein compile
```

## Run

```sh
lein run -- paper.hdoc --target latex
```

See [Usage](#usage) above for the packaged jar, which is how the tool is
meant to be distributed and run.

## Native binary (optional)

The jar pays a JVM and Clojure cold start on every invocation -- around 1.5
seconds before it has read a byte of your document. That is the dominant cost
of a single `haselnuss paper.hdoc`, and it matters when the tool is called
repeatedly from a shell loop, an editor integration, or an LLM pipeline.

`bin/native` compiles a GraalVM native image, which removes it:

```sh
sdk install java 21.0.2-graalce   # or set GRAALVM_HOME to an existing GraalVM
./bin/native
./dist/haselnuss paper.hdoc --target latex
```

| | jar (JDK 17) | native binary |
|---|---|---|
| `--help` | 1.31s | 0.11s |
| `examples/hazelnuts.hdoc` to HTML | 1.45s | 0.16s |

This is **optional and not part of the build or the test gate.** GraalVM is not
in the requirements above, `lein test` never needs it, and nothing about the
distributed jar changes. The binary is also platform-specific -- unlike the jar,
one build does not run everywhere -- and it is 101MB.

Verify a binary you have built with `lein test haselnuss.native-test`, which
compares its output against the jar's and skips when no binary is present.
`doc-4` records why each flag in `bin/native` is there -- every one of them is
load-bearing -- and `doc-9` is the story of how the binary came about, including
what the investigation got wrong on the way.

## Test

```sh
lein test                                               # everything, ~90s
lein test haselnuss.parser-test                         # one namespace
lein test :only haselnuss.parser-test/inline-math-test  # one test
```

Requires `pdflatex`, `bibtex`, `pdftotext`, `pdfinfo` and `qpdf` on `PATH`: several
namespaces compile real LaTeX and read the resulting PDF back rather than asserting on
emitted strings, and they fail rather than skip without a toolchain. `qpdf` is what
TASK-121's annotation tests read the PDF's own `/Annots` with -- the claim there is that a
reader opening the file sees a real annotation, so the test has to look at the PDF and not
at the `.tex`. Three of them are most of the wall time, for
that same deliberate reason:

| namespace | ~time | why |
| --------- | ----- | --- |
| `haselnuss.emit.latex-test` | 45s | compiles fixtures with a real `pdflatex` |
| `haselnuss.repl-config-test` | 19s | five nested `lein` invocations, each a fresh JVM |
| `haselnuss.uberjar-test` | 14s | rebuilds the uberjar and runs the jar |

Everything else is under ten seconds each, most of that JVM startup — including
`haselnuss.thesis-test` (~7s), which is the end-to-end fixture for book-length output:
`test/fixtures/thesis.hdoc` is one chaptered document carrying chapters, two abstracts, the
front-matter prose family, the three lists, a multi-panel figure, a listing, an algorithm and
both image sizings, and the test compiles it standalone *and* as a `--fragment` `\input` into
a minimal host. Every other whole-document fixture here is paper-shaped, and a paper cannot
show the numbering a book gets wrong.

While iterating, a single namespace -- or the nREPL below, which starts no JVM at all after the first -- is
the fast path; the full run is the gate before a task is finished (see `CLAUDE.md`).

## Permission allowlist (agent tooling)

`.claude/settings.json` — tracked, so every clone gets it; `.claude/settings.local.json`
is matched by most global gitignores and helps one machine only — allowlists the commands
task work actually runs, so a long unattended session is not spent answering prompts. The
list was derived from real session transcripts rather than guessed (TASK-50.4): the
project's own gate and build commands (`./bin/lint`, `lein test`, `clj-kondo --lint`,
`lein cljfmt`), the nREPL entry points, the CLI and the packaged jar, the read-only
`backlog` commands the workflow runs constantly, and `bibtex`/`pdftotext`.

What is deliberately **not** on it, since a Bash rule can only match a command prefix and
therefore cannot constrain the arguments after it:

- `pdflatex`. `-shell-escape` turns it into arbitrary shell execution, and no prefix rule
  can exclude a flag. The test suite invokes it itself, which is the routine path, and it
  is covered by `lein test`; a hand-run `pdflatex` prompts, which is the right price.
- `git add`/`git commit`, and `backlog task edit`/`create`. Repo-local, but mutations
  rather than read-only or build commands — one prompt per task for the actions worth a
  human glance. (Read-only git is auto-allowed by Claude Code and needs no rule.)
- `git push`, `curl`, package installs, and any interpreter or shell wildcard
  (`python3 *`, `bash *`, `npx *`): outside the repository, or arbitrary execution.

`lein test` and the nREPL do run project code — that is what they are for — and the CLI
writes wherever `--output` points. The line drawn here is that nothing whose *purpose* is
to reach outside the repository is allowlisted, and nothing offers a shell escape hatch.

## Clojure LSP (agent tooling)

This repo ships a project-scoped Claude Code plugin at `.claude/skills/clojure-lsp/` that
registers [clojure-lsp](https://clojure-lsp.io/) as the LSP server for `.clj`/`.cljs`/`.cljc`
files, giving the coding agent go-to-definition, find-references, hover and diagnostics
through Claude Code's built-in LSP tool instead of plain-text grep. See
`.backlog/docs/doc-1 - Agentic-Clojure-Tooling-Stack.md` for why clojure-lsp was chosen.

Requirements: [clojure-lsp](https://clojure-lsp.io/docs/installation/) on `PATH`.

**Start/verify:**

- Claude Code loads the plugin automatically once you trust this workspace; if it was
  added or changed after your session started, run `/reload-plugins` to pick it up.
- Check it's registered: `claude plugin list` should show `clojure-lsp@skills-dir` under
  "Skills-directory plugins" with status `loaded`.
- Sanity-check the server itself outside Claude Code: `clojure-lsp diagnostics -p .`
  should analyze the project and report `No diagnostics found!` (or real diagnostics, if
  any exist).
- Inside Claude Code, the LSP tool should then resolve symbols in this repo's namespaces,
  e.g. go-to-definition/find-references/hover/diagnostics on any file under `src/`.

## nREPL (agent tooling)

For fast eval-based feedback while implementing tasks, start a project nREPL server
instead of round-tripping through `lein test` for every small change. `project.clj` pins
`:repl-options {:host "127.0.0.1" :port 7888}`, so the server binds loopback and the same
port on every run.

**Start:**

```sh
bin/repl              # headless server, safe to background
```

This prints the listening port and writes it to `.nrepl-port` in the project root
(gitignored). It does not open a client, so it's safe to run in the background. `lein repl
:headless` works too and lands in the same place; `bin/repl` additionally closes an
override `project.clj` alone cannot (see below).

**Connect:**

```sh
bin/repl :connect                     # 127.0.0.1:7888
lein repl :connect                    # or: reads the port from ./.nrepl-port
```

Any other nREPL client (CIDER, Calva, etc.) can connect the same way. Because the port is
pinned, a client holding a previously-read port either reaches the intended REPL or fails
to connect — it can't land on an unrelated later server that happened to grab the same
random port. The flip side, and the deliberate trade: only one server at a time. A second
`bin/repl` while the first is up dies on `BindException: Address already in use` rather
than quietly moving to another port.

**Security note.** `:host`/`:port` in `:repl-options` are only the third-highest-precedence
source. `leiningen.repl/configured-repl-connection` resolves the connection as CLI argument
> `LEIN_REPL_*` environment variable > `:repl-options` > nREPL's own `nrepl.edn` files, so
pinning alone would leave `LEIN_REPL_HOST` in an ambient shell free to move the server off
loopback. Two things close that:

- `project.clj`'s `:repl` profile carries an `:injections` guard. Leiningen splices
  injections into the project JVM ahead of the form that starts the server, so any
  `LEIN_REPL_HOST`, `LEIN_REPL_PORT` or `LEIN_REPL_SOCKET`, and any `nrepl.edn` `:socket`
  key, throws before anything binds. The profile is named `:repl` because Leiningen
  implicitly activates that profile for the `repl` task and no other, so `lein
  test`/`run`/`uberjar` are unaffected.
- `bin/repl` passes `:host`/`:port` as task arguments — the highest-precedence source —
  which is how it also beats `:repl-options` set in a user-level `~/.lein/profiles.clj`.
  That one is invisible to the in-JVM guard, since Leiningen merges profiles before any
  project code exists.

```
$ LEIN_REPL_HOST=0.0.0.0 lein repl :headless
Execution error (ExceptionInfo) ...
LEIN_REPL_HOST=0.0.0.0 would override the nREPL connection this project pins in
project.clj's :repl-options. Unset it, or pass :host/:port to `lein repl` explicitly
if you really mean to.
Subprocess failed (exit code: 1)
```

(With `:headless` that message is the last line. With `lein repl :start` the guard fires
just the same, but Leiningen then waits out its 60-second ack timeout and ends with `REPL
server launch timed out.` — the real cause is the earlier message, not the timeout.)

What remains uncovered, deliberately: an explicit `lein repl :start :host <x>` typed by
hand. Leiningen resolves task arguments in its own JVM and nothing project-supplied runs
earlier, so there is nothing to hook — and unlike an inherited environment variable, it is
not drift. `haselnuss.repl-config-test` covers the rest by *running* it: `bin/lint` reads
both files since TASK-50.2, but static checks cannot tell whether Leiningen actually
evaluates the guard before it binds a socket, and clj-kondo leaves
`:unresolved-symbol` off inside a `project.clj` besides.

## Paren-balance hook (agent tooling)

`.claude/hooks/check-clj-balance.sh` runs as a `PostToolUse` hook (wired in
`.claude/settings.json`) after every `Write`/`Edit`/`MultiEdit` on a `.clj`/`.cljs`/`.cljc`
file. It
reuses clj-kondo (already the project linter, see doc-1) rather than a naive character
counter: it lints the edited file with JSON output and filters for findings of
`"type": "syntax"` -- the class clj-kondo uses specifically for unmatched/mismatched
delimiters, as opposed to ordinary lint findings like unused bindings. Real-reader
parsing means parens/brackets/braces inside strings, chars, comments and regex literals
don't trigger false positives.

If it finds any such findings, it blocks with an error naming the file and the
approximate `line:column` of each unmatched delimiter, so an accidental missing paren
surfaces immediately after the edit instead of only at the next compile/test run.

Test it directly:

```sh
echo '{"tool_input":{"file_path":"'"$PWD"'/src/haselnuss/cli.clj"}}' | .claude/hooks/check-clj-balance.sh
```

Prints nothing (exit 0) for a balanced file; prints a `{"decision":"block",...}` JSON
object naming the file/location for an unbalanced one.

## Backlog CLI (agent tooling)

Task and project state lives in [Backlog.md](https://github.com/MrLesk/Backlog.md), and
`CLAUDE.md` opens by requiring `backlog instructions overview` before anything else — so
`backlog` has to resolve as a bare command in every agent shell, including the shells
`clojure-implementer`/`clojure-reviewer` subagents get, whose own first step is
`backlog task view TASK-N --plain`. A session that has to prefix each call with an
`export PATH=...` workaround also defeats the `Bash(backlog ...)` permission rules in
`.claude/settings.local.json`, which match on the start of the command line.

The CLI is installed globally under a node managed by [nvm](https://github.com/nvm-sh/nvm)
(`~/.nvm/versions/node/v22.19.0/bin/backlog`), and nvm only puts a node on `PATH` if it
is told which one to use. Both `~/.zshrc` and `~/.bashrc` therefore run

```sh
nvm use --silent 22.19.0
```

right after the nvm loader lines. Claude Code snapshots the shell (rc file included) when
a session starts, so the pinned version is what every command and subagent in that session
sees.

**Version pin:** node **v22.19.0**. If that version is removed or `backlog` is reinstalled
under another one, update the `nvm use` line in both rc files to match — `nvm use` on a
missing version fails silently enough that the only visible symptom is
`command not found: backlog` at the start of the next session.

**Verify** (in a *new* shell/session, with no `PATH` prefix):

```sh
backlog task list --plain
```

One wrinkle if you ever change the rc lines: Claude Code reuses an existing snapshot from
`~/.claude/shell-snapshots/` instead of rebuilding one per session, so an rc edit is not
picked up by simply restarting. Clearing that directory forces the next session to
regenerate the snapshot from the current rc file:

```sh
rm ~/.claude/shell-snapshots/*.sh   # between sessions; they are a regenerated cache
```

## Linting

This project lints with [clj-kondo](https://github.com/clj-kondo/clj-kondo) (chosen in
doc-1: static analysis, no compile step, fast enough to run after every edit) and checks
formatting with [cljfmt](https://github.com/weavejester/cljfmt) via the `lein-cljfmt`
plugin. Both are wired as a hard gate: **`./bin/lint` must exit `0` before a task is
finished** -- this is a required command documented in `CLAUDE.md` ("Finishing a task",
which pairs it with a passing `lein test`), so any Claude Code session (including
implementer/reviewer subagents) is bound by it, not just a suggestion.

Run it locally:

```sh
./bin/lint
```

or the checks separately:

```sh
clj-kondo --lint src test project.clj
lein cljfmt check src test project.clj   # `fix` instead of `check` to auto-format
for f in bin/* .claude/hooks/*; do bash -n "$f"; done   # the scripts themselves
```

**What it covers.** `src`, `test` and `project.clj`. That last one is not decorative:
`project.clj` is Clojure, it holds about forty lines of `:injections` guard code
(TASK-45), and until TASK-50.2 the mandatory gate passed over it vacuously. `lein cljfmt
check` with no arguments reads `:source-paths`/`:test-paths` and therefore *cannot* see
`project.clj`, which is why `bin/lint` passes the same explicit path list to both tools --
so the two cannot drift into covering different files.

One limit worth knowing, measured rather than assumed: clj-kondo turns
`:unresolved-symbol` and `:unresolved-namespace` off for a file *named* `project.clj`, and
no `--config` turns them back on. A misspelled var inside `:injections` is therefore still
invisible to the linter; everything else fires, and `haselnuss.repl-config-test` is what
executes that code for real.

**Shell scripts.** `bin/lint`, `bin/repl` and the paren-balance hook are real logic, so
`bin/lint` checks them too -- discovered by shebang across `bin/` and `.claude/hooks/`,
not by directory listing, so a future `bin/NOTES.md` or `bin/tool.py` does not fail the
gate. `bash -n` (a parse check) is the floor, chosen because it needs no install and so
cannot degrade into a gate that quietly does nothing; `shellcheck --severity=warning` runs
on top of it when installed, pinned so the gate means the same thing on every machine, and
its absence is *printed* rather than passed over in silence. `bash -n` catches syntax
errors only -- an unquoted variable or a misspelled test operator needs shellcheck, or
`haselnuss.repl-config-test`, which runs `bin/repl` for real.

**Rule set:** `.clj-kondo/config.edn` turns on clj-kondo's strict, idiom-focused
linters that are off by default -- e.g. `:missing-docstring`, `:shadowed-var`,
`:used-underscored-binding`, `:single-key-in`, `:unsorted-required-namespaces`,
`:unused-alias`, `:equals-nil`/`:equals-true`/`:equals-false`, and
`:main-without-gen-class` -- on top of clj-kondo's own defaults (unresolved
vars/symbols, arity errors, unused bindings/namespaces, redefined vars, etc.). Every
added rule was individually confirmed to actually fire against this project's clj-kondo
version before being added (see TASK-34 notes). A couple of candidate rules
(`:consistent-alias`, `:redundant-fn-wrapper`, `:not-nil?`) were deliberately left out;
the config file itself documents why in a comment.

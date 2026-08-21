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

Each is an empty block directive written exactly where the list belongs — a thesis puts the
lists between the abstract and the contents, a report puts the contents first and no lists at
all, and neither position is fixed here:

```
:::{toc}
:::

:::{list-of-figures}
:::

:::{list-of-tables}
:::
```

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
the offending include is dropped and the rest of the document still converts. To write the
literal text, indent it four spaces or wrap it in backticks — the same shielding that
protects a `:::` directive fence.

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

Exit codes, so a `make`-style tool can act on them:

| code | meaning |
| ---- | ------- |
| 0 | the document was converted and written |
| 1 | the build failed (no representation for the target, a parse error, an unreadable file); nothing was written |
| 2 | the command line was wrong; usage is printed |

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
`<blockquote class="admonition">` in HTML and an indented italic block
in print. Both carry the same head — `Note 1` — and both are
cross-referenceable, which is what lets the document's conclusion point
back at the admonition by id.

`--computed-numbers` is what makes the two outputs agree number for number, which is what
this example is for. A native-mode build works too: it delegates the reference list to
BibTeX, and Haselnuss generates the `.bib` BibTeX needs from the same CSL-JSON the resolver
reads — see [Bibliographies](#bibliographies).

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

## Test

```sh
lein test                                               # everything, ~90s
lein test haselnuss.parser-test                         # one namespace
lein test :only haselnuss.parser-test/inline-math-test  # one test
```

Requires `pdflatex`, `bibtex` and `pdftotext` on `PATH`: several namespaces compile real
LaTeX and read the resulting PDF back rather than asserting on emitted strings, and they
fail rather than skip without a toolchain. Three of them are most of the wall time, for
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

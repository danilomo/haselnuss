(ns haselnuss.cli
  "The command-line entry point (TASK-25): wires SPEC.md sec3's whole
  pipeline together -- read a `.hdoc` file, `parse` it, `resolve` it,
  `lower` it for the chosen target, and `emit` it through the HTML or
  LaTeX emitter -- and gives an author LaTeX-style build feedback while
  doing it.

  Split deliberately into a pure core and a thin shell. `run` takes an
  already-parsed options map plus the source text and returns
  `{:output :diagnostics}` or throws; `build` adds file I/O; `-main`
  adds only argument parsing, printing and the process exit code.
  Everything an acceptance criterion talks about is therefore assertable
  without shelling out to a subprocess (see `haselnuss.cli-test`), which
  is what lets the exit-code and stderr behavior be tested directly
  rather than approximated.

  Pipeline order is the thing this namespace actually owns, and two
  steps in it are easy to get subtly wrong:

  - `resolve-document` is given a lexicon merged from the registry
    (`registry/numbering-lexicon` over `resolver/default-lexicon`) and
    the registry itself as `:directive-registry`. Both matter: without
    the lexicon a registered kind never numbers and, since TASK-36, its
    own nodes all read as role-mismatched; without the registry every
    built-in directive is reported as unknown. Since TASK-48 it is also
    given `:directive-kinds`, read off the same directive-environment
    table `build-registry` registers, so a `{lemma #thm:x}` -- numbered
    from its prefix here and from `lemma`'s own LaTeX counter there --
    is warned about instead of silently producing two numbers.
  - The `:labels` table both emitters take is the one `resolve-document`
    itself numbered with, returned by that pass rather than recomputed
    here. Re-running `number-document` over the resolved document looks
    equivalent -- it differs by exactly one entry, the generated
    bibliography Section's own -- but it creates
    two tables that must agree by luck: every `CrossRef`'s `:text` was
    already baked from the first one, so an emitter reading the second
    could print a number that disagrees with the reference pointing at
    it. That one differing entry stopped being harmless in TASK-41:
    both emitters now read a Section's own entry to print its heading
    number, so the re-derived table would number the generated
    bibliography heading while every reference to it, and
    `derive-toc`'s own default arity, disagreed.

  `lower`'s own `:transform-rules` option is deliberately NOT exposed
  here (TASK-44 AC #4) -- a different mechanism from the `--transform`
  CLI flag below (TASK-103) despite the similar name: `lower`'s is about
  RENDERING a Fallback node's `:transform` variant (sec8.3), never
  authored from `.hdoc` at all; `--transform` is about REWRITING the
  parsed document before resolve/lower/emit ever run. Not an oversight,
  and not deferred work waiting on a flag: an authored `.hdoc` cannot
  declare a `:fallback` of any kind, because `haselnuss.parser` constructs
  no `:fallback` field at all (see `haselnuss.lower`'s own docstring on
  being parser-inert), so a `:transform` fallback is unreachable from a
  document this namespace can be handed. A CLI option would be plumbing to
  a place no input can get to -- and it would have to mean \"load and
  evaluate Clojure from a path\", since a transform rule is a function,
  which is a large step for a document converter to take for a feature no
  document can use. The option becomes worth adding the day authored
  fallbacks do; until then, an API caller reaches `lower` directly, which
  is how `haselnuss.degradation-test` covers the variant.

  Diagnostics vs errors (AC #4/#5) follow the split
  `haselnuss.resolver` and `haselnuss.lower` already drew between them:
  a resolver diagnostic is a *warning* -- a dangling reference or
  citation, a duplicate id, an unknown directive, an id-prefix/role
  mismatch, a directive whose id prefix names a kind other than its own,
  a section whose id prefix names a division other than the one its
  level is emitted as -- printed to stderr, and the build still produces its output
  file and exits 0, exactly as `pdflatex` finishes a document with
  undefined references. `run`'s own `irrelevant-flag-diagnostics`
  (TASK-108) is the same kind of warning for a different source: a flag
  that cannot affect the chosen target -- `--overlay`/`--extensions` on
  `--target json`/`hdoc`, `--computed-numbers`/`--fragment`/`--untrusted`
  off `latex`, `--no-stylesheet` off `html`, `--trusted-extensions` with no
  `--extensions` at all -- rather than something the resolver found
  inside the document. An `ex-info` from any pass -- `lower`'s
  `::no-representation` for a directive with no representation and no
  fallback, an emitter's `::unsupported-block`/`::unresolved-include`, a
  malformed directive from the parser -- is unrecoverable: nothing is
  written, the message goes to stderr, and the exit code is non-zero."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [haselnuss.anchor :as anchor]
            [haselnuss.book-ref :as book-ref]
            [haselnuss.emit.hdoc :as hdoc]
            [haselnuss.emit.html :as html]
            [haselnuss.emit.latex :as latex]
            [haselnuss.extensions.collapsable :as collapsable]
            [haselnuss.extensions.front-matter :as front-matter]
            [haselnuss.extensions.rules :as rules]
            [haselnuss.extensions.sidecar :as sidecar]
            [haselnuss.extensions.small-collapsable :as small-collapsable]
            [haselnuss.json :as json]
            [haselnuss.library :as library]
            [haselnuss.library.directory :as directory]
            [haselnuss.library.filesystem :as fs]
            [haselnuss.library.generated :as generated]
            [haselnuss.library.passage :as passage]
            [haselnuss.library.references :as references]
            [haselnuss.lower :as lower]
            [haselnuss.parser :as parser]
            [haselnuss.registry :as registry]
            [haselnuss.resolver :as resolver]
            [haselnuss.sandbox :as sandbox]
            [haselnuss.sanitize :as sanitize]
            [haselnuss.transform :as transform])
  (:gen-class))

(def targets
  "The emission targets this CLI supports, each mapping to the file
  extension `output-path` gives its output when none was requested.

  `json` (TASK-80) and `hdoc` (TASK-98) are not rendering targets like
  `html`/`latex`: both write the parsed Document exactly AS PARSED, with
  no resolve or lower pass run over it at all, and no
  `haselnuss.registry` -- see `run`'s own `emit-target` branch. `json`
  writes `haselnuss.json/->json`'s interchange representation (SPEC.md
  sec11), its prose folded more compactly than that encoding's own
  faithful round trip -- see `coalesce-prose`. `hdoc` (`haselnuss.emit.
  hdoc/emit-document`) instead writes canonical `.hdoc` SOURCE -- the
  same format `.hdoc` documents are authored in -- so it is a formatter
  and, once transform rules exist, the write side of a scripted
  transformation, rather than an interchange dump; see that namespace's
  own docstring (\"THE CONTRACT\") for why it stops at exactly the same
  point json does."
  {"html" {:target :html :extension ".html"}
   "latex" {:target :latex :extension ".tex"}
   "json" {:target :json :extension ".json"}
   "hdoc" {:target :hdoc :extension ".hdoc"}})

(defn- environment-title
  "The head word a mapped environment prints of its own accord (its
  `:title`), or nil when it prints none.

  Deliberately NOT falling back to the capitalized directive name: an
  environment with no `:title` prints no head in LaTeX, so inventing one
  for the degraded target made the two outputs structurally disagree --
  TASK-29's dogfood document showed an `<strong>Admonition</strong>`
  heading in HTML that appears nowhere in the PDF.

  Since TASK-40 every entry in the built-in table carries a `:title`
  (`admonition` gained \"Note\" along with the counter that makes it
  referenceable), so the nil branch is reachable only through a
  caller-supplied `:directive-environments` table with a title-less
  entry. Kept rather than deleted: the rule is about what an entry
  declares, not about which entries happen to exist today."
  [_directive-name spec]
  (:title spec))

(defn- environment-lower-rule
  "A `haselnuss.registry` `:lower` rule (sec8.3's second branch) that
  degrades a built-in directive environment to plain Blocks for a target
  with no native renderer for it: a BlockQuote holding a bold head
  paragraph followed by the directive's own content.

  This is what makes the built-ins work on *every* target rather than
  only on LaTeX. `haselnuss.emit.latex/register-environments` gives them
  a native `:latex` renderer, and `lower` consults a `:lower` rule only
  when a target has no native renderer -- so LaTeX keeps its real
  `theorem`/`proof` environments and HTML gets this.

  Without it a `{theorem}` in an authored document is an unrecoverable
  build error on the HTML target, because `haselnuss.parser` constructs
  no `:fallback` field at all (it is parser-inert, see
  `haselnuss.lower`'s own docstring) -- so an author has no way to
  declare one, and sec8.3's third branch can never fire for a parsed
  document. A registry-level rule is the mechanism SPEC provides for
  exactly that case, and the CLI is where it belongs: this namespace is
  the one that chose to register these directives, so it owns making
  them representable everywhere it offers to emit.

  Three details, each a defect found by review of this rule's first
  version:

  - **The head carries the resolver's own number** (`labels`, keyed by
    the directive's `attr.id`) -- \"Theorem 1\", not a bare
    \"Theorem\". Without it the degraded HTML head was the one place
    in the whole toolchain that omitted the number, contradicting the
    `<a href=\"#thm:main\">Theorem 1</a>` in its own document and
    defeating the cross-format parity `--computed-numbers` exists for.
    A registry `:lower` rule has signature `(fn [directive target])` and
    cannot see a label table, which is why `build-registry` takes `labels` and
    closes over it per build rather than being a top-level constant.
  - **The whole `attr` survives**, on a wrapping BlockQuote, not just
    the `:id`. The first version built a carrier only when there was an
    `:id`, silently dropping `:classes`/`:props` otherwise -- so
    `{admonition .warning}` and `{admonition .note}` emitted
    byte-identical HTML with nothing for a stylesheet to key on.
  - **The content is grouped**, in that same BlockQuote, rather than
    spliced in as loose sibling Paras. A flat splice left nothing
    marking where the environment ended, so neither a stylesheet nor a
    reader could tell its body from the text after it.

  BlockQuote is the wrapper because it is the only Block variant that
  both carries an `Attr` and holds a Block vector without inventing a
  heading level the author never wrote (Section would; Para has no
  `Attr` at all, sec4.3)."
  [labels title]
  (fn [directive _target]
    (let [attr (:attr directive)
          head (or (:text (get labels (:id attr))) title)]
      [{:t :block-quote
        ;; TASK-139: a carrier, not a quotation -- the HTML emitter
        ;; writes a <div> (role=note for an admonition).
        :container (if (= "admonition" (:name directive)) :note :div)
        ;; The directive's own name joins its classes, so the degraded
        ;; node is still identifiable as what it was -- an admonition
        ;; with no head (see `environment-title`) would otherwise be
        ;; byte-identical to a plain markdown quote, and cross-format
        ;; parity bought by erasing the only marker is not parity worth
        ;; having (TASK-29 review).
        ;; (`caption`/`key`/`ref` never reach HTML as attributes:
        ;; see `haselnuss.emit.html/prop-attrs`, TASK-139.)
        :attr (update attr :classes (fnil conj []) (:name directive))
        ;; No head at all when the environment prints none of its own
        ;; (see `environment-title`) -- an invented one is a structural
        ;; difference between the targets, not a nicety.
        :blocks (into (if head
                        [{:t :para :inlines [{:t :strong :inlines [{:t :str :text head}]}]}]
                        [])
                      (:blocks directive))}])))

(defn- panel-block?
  "True when Block `block` is a PANEL of the float it sits in (TASK-56):
  a Directive whose own name maps to a `:sub` entry in `environments`.
  Read off that table rather than matched against a directive name here,
  for the reason `haselnuss.emit.latex/sub-float-spec` gives for the
  same lookup on its own side."
  [environments block]
  (and (= :directive (:t block))
       (boolean (:sub (get environments (:name block))))))

(defn- panel-rows
  "Float `directive`'s own `:blocks`, with any PANELS among them (TASK-56
  -- children whose own name maps to a `:sub` entry in `environments`)
  grouped into rows of `haselnuss.emit.latex/panel-columns`: each row
  one BlockQuote classed `subfigure-row`, in place, everything else left
  exactly where the author wrote it.

  A BlockQuote is the row because it is the only Block variant that both
  carries an `Attr` and holds a Block vector -- the same reason
  `environment-lower-rule` wraps a degraded environment in one. The
  class is what `haselnuss.emit.html`'s stylesheet lays the row out
  from.

  Consecutive panels only: a paragraph written between two panels ends
  the row it interrupts, which is the same thing a `\\\\` between them
  does in the LaTeX float. A float with no panels at all -- every
  listing, every algorithm, every single-image figure -- comes back
  untouched, so nothing that worked before this task sees a new shape."
  [environments directive]
  (let [panel? (partial panel-block? environments)
        blocks (:blocks directive)]
    (if-not (some panel? blocks)
      ;; A float with no panels -- every listing, every algorithm --
      ;; comes back untouched, and its `columns` attribute is never even
      ;; read, so an attribute that means nothing there cannot fail a
      ;; build (found by review).
      (vec blocks)
      (let [columns (latex/panel-columns directive)]
        (into []
              (mapcat (fn [group]
                        (if (panel? (first group))
                          (map (fn [row]
                                 {:t :block-quote
                                  :container :div
                                  :attr {:classes ["subfigure-row"]
                                         ;; The row's own track count,
                                         ;; so a TRAILING partial row
                                         ;; leaves its empty tracks
                                         ;; empty and its panel keeps
                                         ;; the width its siblings have
                                         ;; -- which is what the LaTeX
                                         ;; side does, where every
                                         ;; panel is sized 1/N of the
                                         ;; line (found by review).
                                         :props {"style" (str "grid-template-columns:repeat("
                                                              columns ",1fr)")}}
                                  :blocks (vec row)})
                               (partition-all columns group))
                          group)))
              (partition-by panel? blocks))))))

(defn- float-lower-rule
  "A `haselnuss.registry` `:lower` rule for a directive whose LaTeX
  mapping is a real FLOAT (`haselnuss.emit.latex/default-directive-
  environments`' own `:float` entries -- a captioned listing, TASK-57;
  an algorithm, TASK-58): degrades it to a `:figure` Block for every
  target with no native renderer for it.

  A Figure rather than `environment-lower-rule`'s BlockQuote, and this
  is the whole point: `haselnuss.ast`'s Figure is already precisely \"a
  captioned, numbered, referenceable block\", so
  `haselnuss.emit.html/render-figure` and `render-caption` compose
  \"Listing 3: The dining philosophers\" from the very same label table
  that the LaTeX float's own `\\caption` is composed from. A BlockQuote
  would have needed the caption re-rendered by hand into a bold head,
  in a second place, in a second shape.

  It does NOT make the node a figure for numbering purposes: numbering
  is driven by the id prefix (`lst:`/`alg:`), so
  `haselnuss.resolver/derive-list-of-figures` -- which requires an
  entry whose own `:kind` is `:fig` -- still excludes it, and the label
  reads \"Listing 3\" rather than \"Figure 3\".

  The directive's own name joins the classes, so the degraded node is
  still identifiable as what it was (the same reason
  `environment-lower-rule` does it). Figure's `:content` must be
  exactly one Block (sec4.3), so a directive wrapping more than one --
  a fence plus a stray paragraph -- is grouped in a BlockQuote rather
  than being silently truncated to its first block.

  `environments` is the same directive-environment table the LaTeX
  mapping comes from, consulted for one thing only: which of this
  float's own children are PANELS of it (`:sub` entries, TASK-56).
  Those are grouped into rows of `haselnuss.emit.latex/panel-columns`,
  each row a BlockQuote classed `subfigure-row`, so the degraded target
  arranges the panels exactly as the LaTeX one does -- the author's
  arrangement, not the target's (AC #4). The panels themselves are left
  as Directives: `haselnuss.lower` runs this rule's output back through
  itself, so each panel lowers through this very function into a nested
  captioned Figure of its own, and its `(a)` comes from the same label
  table the LaTeX `\\caption` letter does."
  [environments]
  (fn [directive _target]
    (let [caption (get-in directive [:attr :props "caption"])
          blocks (panel-rows environments directive)
          ;; The wrapper a multi-row figure needs is a BlockQuote like
          ;; any other grouping here, and would otherwise wear the quote
          ;; bar and indentation the stylesheet gives every one of them
          ;; -- around the panels of a figure, which is not a quotation
          ;; of anything. The class is what the sheet resets it by.
          panels? (some (partial panel-block? environments) (:blocks directive))]
      [{:t :figure
        :attr (-> (:attr directive)
                  (update :classes (fnil conj []) (:name directive))
                  ;; `caption` becomes the Figure's own `:caption` below,
                  ;; so it must not ALSO survive as a prop: HTML renders
                  ;; every prop as a literal attribute, and the element
                  ;; would carry the caption text twice, once of them as
                  ;; an attribute HTML has no meaning for. `columns`
                  ;; goes the same way, but only for a float that HAS
                  ;; panels: it was spent on the rows below, while on a
                  ;; float that holds none it was never read and is left
                  ;; exactly where the author put it.
                  (update :props #(cond-> (dissoc % "caption")
                                    panels? (dissoc "columns"))))
        :content (if (= 1 (count blocks))
                   (first blocks)
                   {:t :block-quote
                    :container :div
                    :attr {:classes (if panels? ["subfigures"] []) :props {}}
                    :blocks (vec blocks)})
        :caption (if (seq caption) [{:t :str :text caption}] [])}])))

(defn build-registry
  "The extension registry a build uses, given the `labels` table
  (`haselnuss.resolver/number-document`'s own result) its degradation
  rules read numbers from -- `{}` before numbering has run, which is
  fine for the two things a registry is needed for at that point
  (`:directive-registry` membership, and `:kind` lexicon fragments;
  neither depends on labels).

  Holds the built-in `collapsable` and `small-collapsable` extensions
  (each of which brings its own `:lower` rule), TASK-24's
  directive-environment mappings for the `:latex` target, and an
  `environment-lower-rule` for each of those so they degrade rather than
  fail on every other target.

  TASK-89 moved the three derived-list placeholder names
  (`\"toc\"`/`\"list-of-figures\"`/`\"list-of-tables\"`) from a registered
  marker (`haselnuss.extensions.derived-lists`'s own former
  `list-renderer`/`extensions`, never actually called) to a real renderer
  each emitter now installs itself, `register-derived-lists` -- the same
  shape `register-environments` already has below it, and called right
  alongside it for the same reason: a name with a real per-target
  renderer is what makes `haselnuss.lower/lower` keep the directive for
  that target at all, and calling it is now genuinely each emitter's own
  job rather than a special case `render-directive` used to check ahead
  of the registry.

  Registering the LaTeX mappings is what makes a `{theorem}`/`{proof}`/
  `{admonition}` directive survive `lower` for LaTeX at all -- see
  `haselnuss.emit.latex/register-environments` -- and the `:lower` rules
  are merged onto those same entries afterwards, not registered as
  fresh ones, since `registry/register` is last-write-wins and would
  otherwise drop the `:latex` renderer it just added."
  ([] (build-registry {}))
  ([labels]
   (reduce (fn [reg [directive-name spec]]
             (assoc-in reg [directive-name :lower]
                       ;; Which degradation an entry gets is read off the
                       ;; entry itself (TASK-57), so a new float mapping
                       ;; brings its own without a second table here.
                       (if (:float spec)
                         (float-lower-rule latex/default-directive-environments)
                         (environment-lower-rule labels
                                                 (environment-title directive-name spec)))))
           (-> {}
               (registry/register collapsable/extension)
               (registry/register small-collapsable/extension)
               ;; TASK-54: registering these is what keeps `lower` from
               ;; aborting on an authored front-matter block, which
               ;; carries no `:fallback` of its own. Neither emitter
               ;; calls the renderer registered here -- both extract
               ;; front-matter blocks before rendering -- so it is a
               ;; marker; see front-matter/front-matter-renderer.
               (registry/register-all front-matter/extensions)
               ;; TASK-89: and a real renderer for the three list
               ;; placeholders, one per emitter -- each draws a list
               ;; itself, from the resolver's own derivations, or (LaTeX
               ;; native mode) by handing the job to LaTeX's own
               ;; counters -- installed the same way
               ;; `register-environments` installs its own mapping,
               ;; right below. Registering either is what keeps `lower`
               ;; from aborting on a placeholder, which carries no
               ;; fallback of its own.
               (html/register-derived-lists)
               (latex/register-derived-lists)
               (latex/register-environments))
           latex/default-directive-environments)))

(def ^:private version
  "This build's own version, for `--version` (TASK-46). Never a literal
  in source: the point of the flag is telling *which build* a jar on
  disk is, and a duplicated string would drift from `project.clj` the
  first time the version changed.

  Two sources, in order:

  - `META-INF/maven/haselnuss/haselnuss/pom.properties`, which
    Leiningen generates from `project.clj` and packs into the jar. This
    is the one that matters, since the flag exists for the packaged
    artifact (`:uberjar-name` deliberately carries no version, so the
    filename cannot answer the question either). The full
    groupId/artifactId path is spelled out because an uberjar contains
    a `pom.properties` for every dependency.
  - `project.clj` in the working directory, for a source checkout,
    where the jar resource does not exist. Guarded on the file really
    being *this* project's `defproject` form, so running from an
    unrelated project directory reports `unknown` rather than someone
    else's version.

  That second branch reads a file chosen by the *caller's* working
  directory, so it is read defensively (found by review):
  `*read-eval*` is bound off, since it is on by default and a
  `#=(...)` form in a stray `project.clj` would otherwise be evaluated
  before the `defproject` guard ever sees the form; and the whole read,
  including pulling the version out of the form, is inside the `try`,
  so a truncated `(defproject haselnuss)` yields `unknown` rather than
  an IndexOutOfBoundsException surfacing as a build error.
  `clojure.edn/read-string` would be the obvious alternative and is
  not usable: it rejects the `~` unquote forms a `project.clj` may
  legitimately contain.

  Delayed only so a tool that mostly does not print its version does
  not pay the I/O on every startup. It is *not* an AOT workaround: a
  top-level initializer is re-run when the class loads, not frozen at
  compile time."
  (delay
    (or (when-let [resource (io/resource "META-INF/maven/haselnuss/haselnuss/pom.properties")]
          (with-open [in (io/input-stream resource)]
            (.getProperty (doto (java.util.Properties.) (.load in)) "version")))
        (let [file (io/file "project.clj")]
          (when (.isFile file)
            (try
              (let [form (binding [*read-eval* false] (read-string (slurp file)))]
                (when (and (seq? form)
                           (= 'defproject (first form))
                           (= 'haselnuss (second form)))
                  (some-> (nth form 2 nil) str)))
              (catch Exception _ nil))))
        "unknown")))

(def ^:private usage
  "The `--help` text (TASK-26 AC #2 will reuse this verbatim from the
  packaged jar; it lives here so there is one description of the
  interface, not two)."
  (str/join
   "\n"
   ["Usage: haselnuss [options] INPUT.hdoc"
    "       haselnuss [options] BOOK-ID"
    "       haselnuss [--libraries PATH] --list-books LIBRARY"
    ""
    "Converts a Haselnuss document to HTML, LaTeX, its own JSON interchange"
    "representation, or canonical .hdoc source."
    ""
    "Options:"
    "  -t, --target TARGET     html (default), latex, json, or hdoc"
    "  -o, --output PATH       output file (default: INPUT with the target's extension)."
    "                          - writes the converted document to stdout instead, for"
    "                          any target"
    "      --in-place          write the converted document back over INPUT itself."
    "                          Implies --target hdoc when no --target is given; refused,"
    "                          naming the conflict, together with --output or a --target"
    "                          other than hdoc. The only way to lift the input-overwrite"
    "                          refusal below."
    "      --computed-numbers  latex only: bake in the resolver's computed numbers and"
    "                          citation text instead of emitting \\Cref/\\cite for LaTeX"
    "                          and BibTeX to resolve. Use this when the same document is"
    "                          also emitted to HTML and the numbering must match exactly."
    "      --fragment          latex only: emit just the body -- no \\documentclass,"
    "                          no preamble, no \\begin{document} -- for \\input into a"
    "                          host document that owns its own class and page furniture."
    "                          The packages the body needs are written alongside it as"
    "                          <output>-preamble.tex, to be \\input as the FIRST line of"
    "                          the host's preamble (see that file's own header)."
    "      --bibliography PATH CSL-JSON bibliography (overrides the document's own"
    "                          meta.bibliography)"
    "      --libraries PATH    the library directory file (a YAML list of libraries) that"
    "                          book links @{...}, !quote lines and a BOOK-ID input resolve"
    "                          against; overrides $HASELNUSS_LIBRARIES and"
    "                          ~/.config/haselnuss/libraries.yaml (see README \"Libraries"
    "                          and books\"). No effect on --target json or hdoc, which"
    "                          resolve no book reference, unless the input is a BOOK-ID"
    "      --list-books LIBRARY  print the canonical ID and title of every book in"
    "                          LIBRARY, one per line, tab-separated and sorted by ID,"
    "                          and exit; takes no input. An unknown library is an error"
    "      --overlay PATH      a .hdoc overlay file patching the base document via"
    "                          prepend/append/describe/highlight/sticky-note directives"
    "                          (repeatable; see README \"Overlays\" and \"Annotations\")."
    "                          No effect on --target json or hdoc."
    "      --extensions PATH   a sidecar .clj file defining author directives, evaluated"
    "                          in a sandbox with no host interop, file, process or"
    "                          classpath access (repeatable; see README \"Author-defined"
    "                          extensions\"). No effect on --target json or hdoc."
    "      --trusted-extensions  allow every --extensions file loaded this run to emit"
    "                          verbatim, unescaped output, and lift the filtering of"
    "                          the elements, attributes, links and TeX it emits; off"
    "                          by default, and never settable by the sidecar file itself"
    "      --transform PATH    a sidecar .clj file defining rules that select and rewrite"
    "                          nodes of the parsed document before any other pass runs"
    "                          (repeatable; applied in file order, then declaration order"
    "                          within a file; see README \"Scripted transformations\")."
    "                          Runs on EVERY target, including json and hdoc -- unlike"
    "                          --overlay/--extensions, it has an effect there too, since"
    "                          it is the only pass that runs before either at all. Shares"
    "                          --extensions' own sandbox, with no host interop, file,"
    "                          process or classpath access; --trusted-extensions grants"
    "                          a transform nothing, since it never emits verbatim output"
    "      --no-stylesheet     html only: omit the default stylesheet the emitter"
    "                          otherwise inlines, for embedding the output in a page"
    "                          that brings its own CSS"
    "      --untrusted         latex only: hold the document's own TeX -- its math,"
    "                          image paths, citation keys and attribute values -- to"
    "                          the allow-list untrusted extension output is held to,"
    "                          and fail the build at the first string outside it,"
    "                          naming file:line and the token. Without it, document"
    "                          math reaches LaTeX as written, trusted; either way"
    "                          \\href, \\url and \\hyperref are dropped from it, which"
    "                          stops those links but no other route (see README"
    "                          \"Converting a document you do not trust\")"
    "  -h, --help              show this help"
    "      --version           show the version of this build and exit"
    ""
    "The json target writes the parsed document's own JSON representation --"
    "no resolve or lower pass runs over it, so it carries no computed numbers,"
    "cross-reference text or bibliography, and --computed-numbers, --fragment"
    "and --no-stylesheet have no effect on it. Prose is written compactly:"
    "consecutive word/space tokens are folded into plain :str text runs. A"
    "meta.bibliography this document declares need not exist on disk for this"
    "target -- unlike html/latex, it never reads the file's content, only (if"
    "the file happens to be present) which bare @word tokens are citations."
    ""
    "The hdoc target writes the parsed document back out as canonical .hdoc"
    "source, the same pre-resolve, pre-lower AST the json target dumps -- and"
    "the same tolerance of a missing meta.bibliography file, unchanged in the"
    "output either way -- so --computed-numbers, --fragment, --no-stylesheet"
    "and --overlay have no effect on it either. It re-spells canonically"
    "rather than preserving the original bytes (one emphasis delimiter, ATX"
    "headings, ...), so it is a formatter for .hdoc, not an identity"
    "transform; running it with no --output is refused by the same overwrite"
    "guard that protects every other target's input, since here the derived"
    "output path shares the input's own extension."
    ""
    "--in-place lifts that one refusal, deliberately: it writes the converted"
    "document back over the file it was read from, so it is the only flag in"
    "this tool whose whole job is destroying the document you gave it. It"
    "implies --target hdoc when no --target is given -- only hdoc output can"
    "honestly overwrite a document being read as one -- and is refused, naming"
    "the conflict, together with --output (two destinations is a mistake, not"
    "a merge) or with any --target other than hdoc. The write itself goes"
    "through a temp file in the SAME directory as the input, moved into place"
    "atomically only once the whole build has succeeded, so a build that fails"
    "partway -- a parse error, an unrepresentable construct, a full disk --"
    "leaves the input exactly as it was. Without --in-place, --target hdoc"
    "keeps refusing to overwrite its own input, as above."
    ""
    "--output - writes the converted document to stdout, the POSIX convention,"
    "for every target -- and nothing else: the \"haselnuss: wrote <path>\" lines"
    "below print to stderr instead, same as a warning or an error, so stdout"
    "carries the document alone and a build can be piped. The overwrite and"
    "bibliography-collision guards above do not apply, since - is not a path on"
    "disk. A build that would also have to write a companion file -- a native-"
    "mode LaTeX bibliography, a --fragment preamble, a front-matter side file --"
    "is refused instead, naming the file that has nowhere to go, rather than"
    "writing the document and silently dropping its companions. A literal file"
    "named - is unaffected: pass ./- (a different string) or --output=- (an"
    "explicit value, exempt from this convention the same way --flag=value is"
    "already exempt from the next-argument-looks-like-a-flag check above)."
    ""
    "Resolver diagnostics (dangling references and citations, duplicate ids,"
    "unknown directives, id-prefix/role mismatches, directives whose id prefix"
    "names a different kind than the directive does, sections whose id prefix"
    "names a different division than their own level is emitted as) are printed"
    "to stderr as warnings on the html/latex targets; the build still succeeds."
    ""
    "A BOOK-ID input -- library:[shelf/...]slug, e.g. reformed:heidelberg -- builds"
    "that book of the library directory instead of a file. It is taken as a book"
    "only when no file or folder of that name exists, and the output defaults to"
    "<slug> with the target's extension in the current directory -- never over an"
    "existing file there unless --output names it. A book ID that cannot be found,"
    "an unknown or unavailable library, and no library directory at all are"
    "errors; --in-place is refused with one."
    ""
    "Book references are resolved on the html/latex targets. One that cannot be"
    "resolved prints ?? (a quote, a ?? placeholder paragraph) and is reported as a"
    "warning naming the reference as written, the book's canonical ID when known,"
    "and file:line; so are the library directory's own problems (a library that"
    "failed to load), once per build. None of them fails the build. A library"
    "directory file that cannot be read or is not a valid list of libraries does."
    ""
    "A flag given that has no effect on the chosen target -- each option's own"
    "note above -- is reported the same way, on every target: a warning on"
    "stderr, the build unaffected. So is --trusted-extensions given with no"
    "--extensions at all: the privilege it grants has nothing to apply to."
    ""
    "Exit codes:"
    "  0  the document was converted and written"
    "  1  the build failed (no representation for the target, a parse error,"
    "     a missing or unreadable file, a BOOK-ID or --list-books library that"
    "     cannot be found, an invalid library directory file); nothing was written"
    "  2  the command line was wrong; a pointer to --help is printed"]))

(defn- parse-args
  "Parses `args` into `{:input :target :output :computed-numbers
  :bibliography :overlay :extensions :trusted-extensions :transform :help
  :version :no-stylesheet :fragment :in-place :libraries :list-books
  :untrusted}`
  (`:list-books` needs, and takes, no `:input`), or throws
  `ex-info` (`:type ::usage-error`) for
  an unknown flag, a flag missing its value, a target that is not in
  `targets`, or anything other than exactly one positional input.

  Hand-rolled rather than pulling in `tools.cli`: the option set is
  small and fixed, and this project has taken no CLI dependency, so
  adding one for seven flags would be the larger change. Long and short
  forms are both accepted for the two options that have one, plus the
  three conventions a hand-rolled parser most often forgets (all three
  found missing by review): `--flag=value` alongside `--flag value`, a
  bare `--` ending option parsing so an input path may start with a
  dash, and a check that an option's value is not itself a flag -- so
  `-t -o out.html doc.hdoc` reports the missing value rather than
  silently setting the target to `\"-o\"`.

  `-o`/`--output` carries one further exception to that last rule
  (TASK-100): a bare `-`, given as its OWN token (`-o -`/`--output -`),
  is the one value this parser lets through despite looking flag-shaped
  -- the POSIX convention for \"this stream, not a file\", read by
  `output-path` as a request to write to stdout. Given instead as
  `--output=-`, it is an ORDINARY value like any other, naming a literal
  file called `-` in the working directory (as does `-o ./-`, since
  `./-` is a different string than `-`) -- the `=` form is already
  exempt from the flag-shaped check above (its value cannot be mistaken
  for the next flag, having no separate token to be one), so this is the
  one value where that existing exemption changes what gets built,
  rather than only who is exempt from a rejection.

  `:target` carries no default of its own here any more (TASK-101): the
  DEFAULT html/hdoc split -- `--in-place` with no `--target` given
  implies `hdoc`, everything else implies `html` -- is computed once, at
  the bottom of this function, from whichever of `:target`/`:in-place`
  the loop actually collected, precisely so that split can tell an
  EXPLICIT `--target html` (refused alongside `--in-place`, see below)
  apart from no `--target` at all (silently becomes `hdoc`) -- a
  distinction a pre-seeded `\"html\"` default would have erased before
  this function ever saw it."
  [args]
  (loop [[arg & more :as remaining] args
         opts {}
         literal? false]
    (let [[arg inline-value] (if (and (not literal?) (str/starts-with? (str arg) "--")
                                      (str/includes? (str arg) "="))
                               (str/split arg #"=" 2)
                               [arg nil])
          _ (when (and inline-value
                       (contains? #{"--help" "--version" "--computed-numbers"
                                    "--no-stylesheet" "--fragment" "--trusted-extensions"
                                    "--in-place" "--untrusted"}
                                  arg))
              ;; A value on a flag that takes none must be an error, not
              ;; silently discarded: `--computed-numbers=false` turned
              ;; the flag ON (found by review).
              (throw (ex-info (str "option " arg " takes no value") {:type ::usage-error})))
          take-value (fn [flag & {:keys [allow-dash?]}]
                       (cond
                         inline-value [inline-value more]
                         (and (seq more) (or (and allow-dash? (= "-" (first more)))
                                             (not (str/starts-with? (first more) "-"))))
                         [(first more) (rest more)]
                         :else (throw (ex-info (str "option " flag " requires a value")
                                               {:type ::usage-error}))))]
      (cond
        (empty? remaining)
        (cond
          ;; Both are asked *before* the input check: `--help` and
          ;; `--version` are questions about the program, not requests
          ;; to convert something, so neither needs a document.
          (:help opts) opts
          (:version opts) opts
          ;; TASK-157: a listing converts nothing, so an input given
          ;; alongside it is a mistake rather than something to ignore.
          (and (:list-books opts) (:input opts))
          (throw (ex-info (str "--list-books takes no input; got " (pr-str (:input opts)))
                          {:type ::usage-error}))
          (:list-books opts) opts
          (nil? (:input opts)) (throw (ex-info "no input file given" {:type ::usage-error}))
          ;; TASK-101: --in-place's own two refusals are checked BEFORE
          ;; the generic unknown-target check below, both because they
          ;; are the more specific, more actionable message or an
          ;; explicit --target that also happens to be bogus, and
          ;; because an explicit --target other than hdoc need not even
          ;; be a KNOWN target for this to be the right thing to say --
          ;; "requires --target hdoc" already implies it.
          (and (:in-place opts) (:output opts))
          (throw (ex-info "--in-place cannot be combined with --output: pass one destination, not two"
                          {:type ::usage-error}))
          (and (:in-place opts) (:target opts) (not= "hdoc" (:target opts)))
          (throw (ex-info (str "--in-place requires --target hdoc, since only hdoc output can"
                               " honestly overwrite the input it was read from; got --target "
                               (pr-str (:target opts)))
                          {:type ::usage-error}))
          :else
          (let [;; The one place `:target` gets its default (TASK-101):
                ;; an explicit --target wins outright; absent one,
                ;; --in-place implies hdoc (the only target that can
                ;; honestly overwrite the input it was read from) and
                ;; every other build keeps the pre-TASK-101 default.
                target (or (:target opts) (if (:in-place opts) "hdoc" "html"))]
            (when (nil? (targets target))
              (throw (ex-info (str "unknown target " (pr-str target)
                                   " (expected one of: " (str/join ", " (sort (keys targets))) ")")
                              {:type ::usage-error})))
            (assoc opts :target target)))

        (and (not literal?) (= "--" arg)) (recur more opts true)

        (and (not literal?) (contains? #{"-h" "--help"} arg))
        (recur more (assoc opts :help true) literal?)

        (and (not literal?) (= "--version" arg))
        (recur more (assoc opts :version true) literal?)

        (and (not literal?) (contains? #{"-t" "--target"} arg))
        (let [[value rest-args] (take-value arg)]
          (recur rest-args (assoc opts :target value) literal?))

        (and (not literal?) (contains? #{"-o" "--output"} arg))
        (let [[value rest-args] (take-value arg :allow-dash? true)]
          ;; TASK-100: `-` as its OWN token means stdout; `--output=-`
          ;; takes `inline-value`'s branch of `take-value` instead, never
          ;; reaching the flag-shaped check `allow-dash?` widens, so it
          ;; stays an ordinary value naming a literal file called `-`.
          (recur rest-args
                 (assoc opts :output (if (and (not inline-value) (= "-" value)) :stdout value))
                 literal?))

        (and (not literal?) (= "--bibliography" arg))
        (let [[value rest-args] (take-value arg)]
          (recur rest-args (assoc opts :bibliography value) literal?))

        ;; TASK-157 (doc-10, "CLI"): the library directory file, ahead of
        ;; $HASELNUSS_LIBRARIES and the home config.
        (and (not literal?) (= "--libraries" arg))
        (let [[value rest-args] (take-value arg)]
          (recur rest-args (assoc opts :libraries value) literal?))

        ;; TASK-157: a question about a library, like --help one about the
        ;; program, so it needs no input (and refuses one, below).
        (and (not literal?) (= "--list-books" arg))
        (let [[value rest-args] (take-value arg)]
          (recur rest-args (assoc opts :list-books value) literal?))

        ;; The one repeatable flag (TASK-83.1): every other branch here
        ;; uses `assoc`, since every other option is single-valued, so
        ;; `--overlay a.hdoc --overlay b.hdoc` collects into a vector
        ;; instead of the second occurrence silently replacing the first.
        (and (not literal?) (= "--overlay" arg))
        (let [[value rest-args] (take-value arg)]
          (recur rest-args (update opts :overlay (fnil conj []) value) literal?))

        ;; TASK-90: the second repeatable flag, exactly `--overlay`'s own
        ;; shape and for the same reason -- an author may load more than
        ;; one sidecar file in a single build.
        (and (not literal?) (= "--extensions" arg))
        (let [[value rest-args] (take-value arg)]
          (recur rest-args (update opts :extensions (fnil conj []) value) literal?))

        (and (not literal?) (= "--trusted-extensions" arg))
        (recur more (assoc opts :trusted-extensions true) literal?)

        ;; TASK-103: the third repeatable flag, exactly `--extensions`'s
        ;; own shape -- an author may apply more than one transform file
        ;; to a single build, in the order given (AC #1). No trust flag
        ;; of its own (this namespace's own `usage` text, and
        ;; `haselnuss.extensions.rules`'s own docstring, NO TRUST FLAG IS
        ;; INVOLVED): `--trusted-extensions` is read only where `--
        ;; extensions` is loaded, below.
        (and (not literal?) (= "--transform" arg))
        (let [[value rest-args] (take-value arg)]
          (recur rest-args (update opts :transform (fnil conj []) value) literal?))

        ;; TASK-101: a boolean flag, the same shape as --trusted-
        ;; extensions above -- the target-defaulting and the two
        ;; refusals it drives are all in the final `(empty? remaining)`
        ;; branch, once every flag (including a LATER --target) has been
        ;; seen.
        (and (not literal?) (= "--in-place" arg))
        (recur more (assoc opts :in-place true) literal?)

        (and (not literal?) (= "--computed-numbers" arg))
        (recur more (assoc opts :computed-numbers true) literal?)

        (and (not literal?) (= "--no-stylesheet" arg))
        (recur more (assoc opts :no-stylesheet true) literal?)

        (and (not literal?) (= "--fragment" arg))
        (recur more (assoc opts :fragment true) literal?)

        ;; TASK-219: hold the document's own TeX to TASK-215's allow-list
        ;; in the LaTeX build (`run`, `sanitize/vet-untrusted-document`).
        (and (not literal?) (= "--untrusted" arg))
        (recur more (assoc opts :untrusted true) literal?)

        (and (not literal?) (str/starts-with? arg "-"))
        (throw (ex-info (str "unknown option " (pr-str arg)) {:type ::usage-error}))

        (:input opts)
        (throw (ex-info (str "unexpected extra argument " (pr-str arg)
                             " (only one input file is accepted)")
                        {:type ::usage-error}))

        :else (recur more (assoc opts :input arg) literal?)))))

(defn- derived-output-path
  "Where `output-path` writes when `opts` gives no explicit `:output`:
  `input` with its extension replaced by the target's own (`targets`).
  An input with no extension simply gains one.

  Stripping is guarded against a dotfile (`.hidden`), where the regex
  would otherwise consume the whole name and write a hidden `.html` in
  the *current directory* instead of beside the input -- the same
  empty-after-stripping trap `haselnuss.emit.latex/bib-resource-name`
  already guards (its own TASK-23 review finding).

  Also `build`'s own name for what a companion file (a native-mode
  `.bib`, a `--fragment` preamble, a front-matter side file) would have
  been called, when `--output -` (TASK-100) has sent the document itself
  to stdout instead of to a path any of them could sit beside -- see its
  own `nominal-path`. Deriving that name from `input` rather than from
  the literal `\"-\"` `output-path` returns in that case is what keeps a
  refusal readable: \"doc.bib has nowhere to go\", not \"--bib has
  nowhere to go\"."
  [input target]
  (let [file (io/file input)
        parent (.getParent file)
        base-name (.getName file)
        stripped (str/replace base-name #"\.[^.]*$" "")
        new-name (str (if (str/blank? stripped) base-name stripped)
                      (:extension (targets target)))]
    (if parent (str (io/file parent new-name)) new-name)))

(defn- default-output-path
  "Where a build of `opts` writes with no `:output`: `derived-output-path`,
  except for a BOOK-ID input (TASK-157), which `build` gives its own
  `::default-output` -- `<slug>.<ext>` in the current directory (doc-10,
  \"CLI\"), not beside the book's file inside the library."
  [opts]
  (or (::default-output opts) (derived-output-path (:input opts) (:target opts))))

(defn- output-path
  "The path to write to: `opts`' own `:output` when given, else
  `derived-output-path`.

  The chosen path -- derived or explicit -- is checked against the
  input, so a build can never overwrite the document it is reading. That
  guard matters more for `hdoc` (TASK-98) than for any earlier target:
  it is the first whose own default extension (`.hdoc`) is the SAME as a
  typical input's, so `haselnuss doc.hdoc --target hdoc` derives
  `doc.hdoc` -- the input itself -- and is refused by this same check
  with no target-specific code, exactly as `-o doc.hdoc doc.hdoc`
  already is.

  `bib-path` (TASK-81), when given, is checked the same way: the json
  target's own default extension is `.json`, the same extension a
  CSL-JSON bibliography almost always carries, and this repo's own
  `examples/hazelnuts.hdoc` names its bibliography `hazelnuts.json` --
  the exact base name `--target json` with no `--output` would derive on
  its own. Running that combination against the real file, once, is what
  found this: the bibliography was silently replaced by the AST dump.
  html/latex practically never collide here (nobody names a CSL-JSON
  file `.html`/`.tex`), but the check is unconditional rather than
  target-gated, since it costs nothing and a target-specific carve-out
  would be one more thing to keep in sync with `targets`.

  Neither guard applies to `--output -` (TASK-100, AC #3): both are
  about a path on disk, `:stdout` is not one, and `derived-output-path`
  is never even consulted -- there is no `:output`-less derivation to
  guard, since `-` came from `opts` explicitly. `:stdout` itself is
  returned unchanged, for `build` to recognize; a caller wanting the
  display string for a message reads it off `opts` (`\"-\"`) or writes
  its own, rather than this function inventing one no guard ever
  checked.

  `:in-place` (TASK-101) is the ONE flag that lifts the overwrite-input
  guard above, and only that one: `path` becomes `input` itself,
  bypassing `derived-output-path` entirely (an in-place build overwrites
  the exact file it read, whatever its own extension -- it does not
  reach for `.hdoc`, the way an ordinary `--target hdoc` build with no
  `--output` would), and the overwrite-input check below is skipped
  outright rather than checked and refused. The bibliography-collision
  guard is NOT lifted -- it is a different guard, about a different
  file, and `--in-place` says nothing about it. Both of `:in-place`'s own
  refusals (paired with `:output`, paired with a `:target` other than
  hdoc) are re-checked here too, even though `parse-args` already
  refuses both -- `run`/`build` are public, so a caller reaching this
  directly gets the same guarantee a caller going through the CLI does,
  the same reason `run` itself re-validates `target` against `targets`."
  [{:keys [input output target in-place] :as opts} bib-path]
  (when (and in-place output)
    (throw (ex-info "--in-place cannot be combined with --output: pass one destination, not two"
                    {:type ::usage-error})))
  (when (and in-place (not= "hdoc" target))
    (throw (ex-info (str "--in-place requires --target hdoc, since only hdoc output can honestly"
                         " overwrite the input it was read from; got --target " (pr-str target))
                    {:type ::usage-error})))
  (if (= :stdout output)
    :stdout
    (let [file (io/file input)
          path (if in-place input (or output (default-output-path opts)))]
      ;; Checked on the FINAL path, whichever branch chose it. Guarding
      ;; only the derived one left `-o victim.hdoc victim.hdoc` destroying
      ;; its own source -- while the message told the user to pass exactly
      ;; that flag (found by review). Skipped entirely under `:in-place`
      ;; (TASK-101): deliberately overwriting the input is the whole
      ;; point of that flag, not a mistake to catch.
      (when (and (not in-place)
                 (= (.getAbsolutePath (io/file path)) (.getAbsolutePath file)))
        (throw (ex-info (str "refusing to overwrite the input file: " input)
                        {:type ::usage-error})))
      (when (and bib-path (= (.getAbsolutePath (io/file path)) (.getAbsolutePath (io/file bib-path))))
        (throw (ex-info (str "refusing to overwrite the bibliography file: " bib-path
                             " -- pass --output to write the converted document elsewhere")
                        {:type ::usage-error})))
      ;; TASK-157 review: a BOOK-ID build's default output lands in
      ;; whatever directory the user happens to be in, under a name
      ;; (`<slug>.<ext>`) they never typed -- `haselnuss lib:apostles
      ;; --target hdoc` beside their own `apostles.hdoc` replaced it. So an
      ;; existing file there is never overwritten by default, on any
      ;; target; naming it with --output says the overwrite is meant.
      (when (and (nil? output) (::default-output opts) (.exists (io/file path)))
        (throw (ex-info (str "refusing to overwrite " path ", which already exists, with book "
                             (::book-ref opts) " -- pass --output " path
                             " to replace it, or --output elsewhere")
                        {:type ::book-output-exists :path path})))
      path)))

(defn- bibliography-path
  "The CSL-JSON bibliography file to read for a document whose own
  `meta.bibliography` is `declared`, given `opts`' own `:bibliography`
  override and `:base-dir` (the directory of the input file), or nil
  when the document declares none and none was given.

  A relative `meta.bibliography` is resolved against the *document's own
  directory*, not the process working directory: `meta.bibliography` is
  written by an author inside a `.hdoc` file, alongside which the `.json`
  normally sits, so `haselnuss subdir/paper.hdoc` must find
  `subdir/refs.json` the same way it would if run from `subdir`. A
  `--bibliography` given on the command line is resolved against the
  working directory instead, since that is where the user typed it.
  An absolute path in either position is used as-is."
  [{:keys [bibliography base-dir]} declared]
  (cond
    bibliography bibliography
    (str/blank? declared) nil
    (.isAbsolute (io/file declared)) declared
    :else (str (io/file (or base-dir ".") declared))))

(defn- load-bibliography!
  "`resolver/load-bibliography` for `path`, or `nil` for a nil `path`,
  raising a named `ex-info` when the file is missing rather than letting
  a bare `FileNotFoundException` reach the user -- a document that names
  a bibliography and does not get one would otherwise silently produce
  every citation as a dangling `??`, which is a worse failure than
  stopping.

  `required?` (TASK-107) is false only for `run`'s own json/hdoc
  branch: neither target resolves a citation, so neither has any use for
  the file's CONTENT, only (via `vocabulary`'s `:cite-keys`, in `run`)
  for the same `@word`-is-a-citation classification html/latex would
  make for the identical document -- worth having when the file happens
  to be there, but not worth refusing an otherwise-successful build
  over when it is not (see `run`'s own docstring). So a present file is
  still loaded, and still fails loudly if it fails to parse as CSL-JSON
  -- `required?` forgives ABSENCE only, not a bibliography that exists
  and is broken -- while an absent one quietly yields nil, exactly as if
  `path` itself had been nil (no bibliography declared at all)."
  ([path] (load-bibliography! path true))
  ([path required?]
   (when path
     (if (.isFile (io/file path))
       (resolver/load-bibliography path)
       (when required?
         (throw (ex-info (str "bibliography file not found: " path)
                         {:type ::missing-bibliography})))))))

(defn- load-overlays!
  "Reads and parses every `--overlay` path in `paths` (TASK-83.1, doc-5),
  mirroring `load-bibliography!`: a missing file is a hard failure
  (`ex-info ::missing-overlay`), like `::missing-bibliography` above --
  it is a path the user typed on the command line, not a reference
  embedded in document prose that can be diagnosed and skipped.

  Each file is parsed with `vocabulary` -- the same one the base
  document uses, so `@cross-ref`/`[@cite]` tokens inside an overlay file
  parse the same construct they would in the document it patches -- and
  then run through `resolver/expand-includes` on its OWN, with
  `:source-path`/`:base-dir` seeded to THAT FILE's own path/directory.
  That is the exact TASK-38 finding-1 root-seeding fix
  (`resolve-document`'s own `:source-path` above), one level down: an
  overlay file that `!include`s itself is caught as a cycle instead of
  looping, and its own relative `!include`s resolve against its own
  directory, not the base document's.

  Returns `{:sources [[Block] ...] :warnings [...]}`: `:sources`, in
  `paths`' own order, each already include-expanded and ready to hand to
  `resolver/resolve-document`'s `:overlay-sources`; `:warnings`, every
  `expand-includes` warning from every source, concatenated in the same
  order. This I/O runs here, in `haselnuss.cli`, entirely BEFORE
  `resolve-document` is even called -- unlike the base document's own
  `:includes`, whose I/O is deferred into a `:load` callback
  `resolve-document` invokes itself -- so `:warnings` is prepended by
  `run` onto whatever `resolve-document` separately returns in its own
  `:diagnostics`, rather than folded in by the resolver the way
  `apply-overlays`'s own diagnostics are. Asymmetric with `:includes` by
  necessity, not oversight: an overlay file is read by this namespace,
  never by the resolver, which stays free of file I/O throughout."
  [paths vocabulary]
  (reduce
   (fn [acc path]
     (let [file (io/file path)]
       (when-not (.isFile file)
         (throw (ex-info (str "overlay file not found: " path) {:type ::missing-overlay})))
       (let [document (parser/parse (slurp file) (assoc vocabulary :source path))
             {:keys [document warnings]}
             (resolver/expand-includes document
                                       {:base-dir (.getParentFile (.getAbsoluteFile file))
                                        :source-path path
                                        :load (fn [f] (parser/parse (slurp f) (assoc vocabulary :source (str f))))})]
         (-> acc
             (update :sources conj (:blocks document))
             (update :warnings into warnings)))))
   {:sources [] :warnings []}
   paths))

(def ^:private glueable-inline-tags
  "Inline tags the json target (TASK-80 follow-up) folds into plain text
  rather than keeping as their own JSON entries. CommonMark's own word/
  whitespace tokenization is what puts one word per `:str` node with a
  `:space` or `:soft-break` between them -- needed so an inline construct
  like `*emph*` or a bare `@key` has a clean token boundary, and for
  TASK-47's vocabulary check -- but it makes for noisy JSON that no other
  target exposes at all: html/latex both simply concatenate this exact
  shape when rendering, so nothing downstream of the parser actually
  depends on the words staying split.

  `:line-break` is deliberately excluded: it is an AUTHORED line break (a
  trailing backslash or two spaces), not filler between words, so it
  ends a glued run rather than folding into it."
  #{:str :space :soft-break})

(defn- glue-text
  "The literal text one glued run of `glueable-inline-tags` nodes becomes:
  each `:str`'s own `:text`, each `:space`/`:soft-break` a single literal
  space -- concatenated in order, so a space at either edge of the run
  (adjacent to markup outside it, e.g. the two spaces around `*emph*` in
  \"wrong *emph* text\") survives instead of being silently dropped."
  [run]
  (apply str (map (fn [{:keys [t text]}] (if (= :str t) text " ")) run)))

(defn- coalesce-inline-run
  "One `haselnuss.ast` Inline vector (`:inlines`, or any of the other
  fields typed as a vector of Inlines -- `:heading`, `:caption`, `:title`,
  `:prefix`, `:suffix`), with every consecutive run of
  `glueable-inline-tags` nodes folded into a single `:str`.

  Safe to call on ANY vector, Inline or not: a `:blocks`/`:rows`/
  `:classes` vector's own elements never carry a `:t` in
  `glueable-inline-tags` (a Block's own tags are disjoint from an
  Inline's, and a bare string has no `:t` at all), so `partition-by`
  finds nothing to fold there and the vector comes back unchanged."
  [v]
  (into []
        (mapcat (fn [group]
                  (if (and (map? (first group))
                           (contains? glueable-inline-tags (:t (first group))))
                    [{:t :str :text (glue-text group)}]
                    group)))
        (partition-by #(and (map? %) (contains? glueable-inline-tags (:t %))) v)))

(defn- coalesce-prose
  "`document`, with every Inline vector anywhere in it run through
  `coalesce-inline-run` -- a compact json-target dump, at the cost of
  `haselnuss.json`'s own general round-trip contract, which this
  deliberately does NOT touch: `haselnuss.json` itself stays exactly as
  faithful as TASK-3 built it, and this is a display-only transform
  private to the CLI's own json target, applied to the parsed Document
  right before `json/->json` sees it.

  A plain `postwalk`, not a schema-aware walk, because `coalesce-inline-
  run` is already safe to call on every vector in the tree regardless of
  what it holds (see its own docstring) -- so there is no field name
  (`:inlines`/`:heading`/`:caption`/...) this would need to know about,
  and a schema change adding a new Inline-vector field needs no matching
  change here."
  [document]
  (walk/postwalk (fn [x] (cond-> x (vector? x) coalesce-inline-run)) document))

(defn- irrelevant-flag-diagnostic
  "One `{:type :irrelevant-flag :flag :message}` diagnostic (TASK-108),
  the same `:type`/`:message` shape every `haselnuss.resolver` diagnostic
  already uses, so `warn!` prints it exactly like a dangling reference or
  an unknown directive -- no new stderr shape, no change to the exit
  code."
  [flag message]
  {:type :irrelevant-flag :flag flag :message message})

(defn- irrelevant-flag-diagnostics
  "One `irrelevant-flag-diagnostic` (TASK-108) per flag in `opts` that
  `usage` itself already documents as having no effect on `emit-target`,
  or as granting nothing at all -- the seven spellings that acceptance
  criterion this task fixed were found for:

  - `--overlay`/`--extensions` given together with `--target json` or
    `--target hdoc` (AC #1) -- `usage`'s own \"No effect on --target
    json or hdoc\" for each.
  - `--computed-numbers`/`--fragment` given on any target but `latex`,
    or `--no-stylesheet` given on any target but `html` (AC #2) --
    `usage`'s own \"latex only\"/\"html only\".
  - `--trusted-extensions` given with no `--extensions` at all (AC #3):
    the privilege it grants has an empty set of files to apply to,
    independent of `emit-target` -- true even when an `--extensions` WAS
    given but is itself irrelevant on this target (the first bullet
    above already warns about that combination on its own).

  Pure and independent of parsing or file I/O: every one of these is a
  fact about the flag/target combination alone, decided entirely by
  `opts` and `emit-target` -- the same information `parse-args` already
  has by the time it returns (this task's own finding), just never
  revisited once `run` knows the target. A warning, not a refusal (AC
  #4): each flag is real and spelled correctly, so the build still
  produces its output exactly as if the flag had been omitted (AC #5:
  a build passing only applicable flags triggers none of these, so it
  returns `[]`, same as the diagnostics list of a build with no
  irrelevant flag at all)."
  [opts emit-target]
  (let [target (:target opts)]
    (cond-> []
      (and (contains? #{:json :hdoc} emit-target) (seq (:overlay opts)))
      (conj (irrelevant-flag-diagnostic
             "--overlay" (str "--overlay has no effect on --target " target)))

      (and (contains? #{:json :hdoc} emit-target) (seq (:extensions opts)))
      (conj (irrelevant-flag-diagnostic
             "--extensions" (str "--extensions has no effect on --target " target)))

      ;; TASK-157: neither target resolves a book reference -- but a
      ;; BOOK-ID input is found through the directory on every target.
      (and (contains? #{:json :hdoc} emit-target) (:libraries opts) (not (::book opts)))
      (conj (irrelevant-flag-diagnostic
             "--libraries" (str "--libraries has no effect on --target " target)))

      (and (not= :latex emit-target) (:computed-numbers opts))
      (conj (irrelevant-flag-diagnostic
             "--computed-numbers"
             (str "--computed-numbers has no effect on --target " target)))

      (and (not= :latex emit-target) (:fragment opts))
      (conj (irrelevant-flag-diagnostic
             "--fragment" (str "--fragment has no effect on --target " target)))

      (and (not= :html emit-target) (:no-stylesheet opts))
      (conj (irrelevant-flag-diagnostic
             "--no-stylesheet" (str "--no-stylesheet has no effect on --target " target)))

      ;; TASK-219: html math reaches MathJax, which TASK-217 already
      ;; confines; json and hdoc filter nothing at all.
      (and (not= :latex emit-target) (:untrusted opts))
      (conj (irrelevant-flag-diagnostic
             "--untrusted" (str "--untrusted has no effect on --target " target)))

      (and (:trusted-extensions opts) (empty? (:extensions opts)))
      (conj (irrelevant-flag-diagnostic
             "--trusted-extensions"
             "--trusted-extensions grants nothing with no --extensions given")))))

;; --- libraries (TASK-157, doc-10 "CLI") ---------------------------------------
;;
;; A build reaches the library directory in one of three ways:
;;
;; - lazily, for a document's own book references: `run` hands
;;   `resolve-document` a `:books` option over `references/directory-loader`,
;;   which reads the directory file only on the first reference, so a
;;   document with none never touches it (doc-10: "only a document that
;;   actually uses a book reference is affected");
;; - eagerly, for a BOOK-ID input, which `build` has to find before there is
;;   any file to read (`book-input-opts`); the same directory value is then
;;   handed to `run`, so the build loads it once;
;; - eagerly, for `--list-books` (`list-books`).
;;
;; `:library-env` and `:library-home` are not command-line options: they
;; replace the process environment and home folder `directory/
;; find-directory-file` consults, so a test never reads the real
;; `$HASELNUSS_LIBRARIES` or `~/.config/haselnuss/libraries.yaml`.
;;
;; `:library-directory` (TASK-159) is not a command-line option either: a
;; LibraryDirectory value used in place of any directory file -- by a
;; document's book references, a BOOK-ID input and `list-books` alike
;; (`books-option`, `load-directory!`) -- so a program embedding Haselnuss,
;; or a test, can work against in-process (`:memory`) libraries through the
;; same pipeline as the CLI. No directory file is then read -- not a
;; `--libraries` file, `$HASELNUSS_LIBRARIES` or the home config. A BOOK-ID
;; input still needs a filesystem book,
;; since only one has a source file to build.

(defn- parse-kinds
  "The `:kinds` of the parse vocabulary a build of `emit-target` uses --
  every kind name of `lexicon` -- or nil when it parses with no vocabulary
  at all (native-mode LaTeX; see `run`'s own `vocabulary`). The books the
  build reads parse with the same kinds (`fs/provider`), so a `@fig:x` in
  a quoted book means what it would in the host."
  [lexicon emit-target computed-numbers]
  (when (or (not= emit-target :latex) computed-numbers)
    (into #{} (map name) (keys lexicon))))

(defn- directory-opts
  "The `haselnuss.library.directory` options for `opts`: `--libraries` as
  the explicit `:path`, the injected `:library-env`/`:library-home` when
  given, and the default provider registry with the `:filesystem` provider
  rebuilt to parse every book with `kinds` (TASK-153's `fs/provider`)."
  [opts kinds]
  (cond-> {:path (:libraries opts)
           :registry (directory/register-provider directory/default-registry :filesystem
                                                  (fs/provider {:kinds kinds}))}
    (contains? opts :library-env) (assoc :env (:library-env opts))
    (contains? opts :library-home) (assoc :home (:library-home opts))))

(defn- check-libraries-file!
  "Throws when `--libraries` names a file that does not exist -- typed on
  the command line, so a hard error up front like a missing `--overlay`,
  even for a document that turns out to hold no book reference. Reading
  and validating it stays lazy."
  [opts]
  (when (some? (:libraries opts))
    (directory/find-directory-file {:path (:libraries opts)})))

(def ^:private no-directory-message
  (str "no library directory was found (pass --libraries, set $" directory/env-var
       " or create ~/.config/haselnuss/libraries.yaml)"))

(defn- directory-diagnostics
  "The library directory `dir`'s own load problems (doc-10: a library that
  failed to load), each `:level :error`, located at its entry."
  [dir]
  (if (satisfies? library/LibraryDiagnostics dir) (vec (library/library-diagnostics dir)) []))

(defn- load-directory!
  "The library directory for `opts`, loaded now, or a hard error when
  there is none at all. `what` prefixes the message, naming what needed it."
  [opts kinds what]
  (or (:library-directory opts)
      ((references/directory-loader (directory-opts opts kinds)))
      (throw (ex-info (str what ": " no-directory-message) {:type ::no-library-directory}))))

(defn- find-library!
  "The Library `lib-name` of `dir`, or a hard error: an unknown library
  (naming those the directory has), or one that failed to load (the
  `:unavailable-library` message, whose cause names the directory file
  and line). `diagnostics` ride along to be printed first."
  [dir lib-name what diagnostics]
  (let [lib (library/find-library dir lib-name)
        fail (fn [error-type message]
               (throw (ex-info (str what ": " message)
                               {:type error-type :library lib-name ::diagnostics diagnostics})))]
    (cond
      (nil? lib)
      (let [names (library/library-names dir)]
        (fail ::unknown-library
              (str "the library directory has no library " (pr-str lib-name)
                   (if (seq names) (str " (it has: " (str/join ", " names) ")") " (it has none)"))))
      (library/diagnostic? lib) (fail ::unavailable-library (:message lib))
      :else lib)))

(defn- book-id-input
  "What `book-ref/parse-book-ref` makes of `input` when it is a BOOK-ID
  input (doc-10, \"CLI\"): no file or folder of that name exists and it
  parses as a book reference. nil otherwise -- then it is a file path, and
  `build` reports a missing one as it always has."
  [input]
  (when (and (string? input) (not (.exists (io/file input))))
    (let [parsed (book-ref/parse-book-ref input)]
      (when-not (book-ref/malformed? parsed) parsed))))

(defn- book-input-opts
  "`opts` for building the book its `:input` names, `parsed` being that
  input as a book reference: `:input` becomes the book's entry file, so
  every pass -- `!include`, the bibliography, file:line positions -- reads
  the book as it would the file; `::book` carries its BookId (for
  `resolve-document`'s `:self`) and the loaded directory, and
  `::default-output` is `<slug>.<ext>` in the current directory.

  Hard errors (doc-10, \"Error handling\"): a passage in the input, no
  library directory, an unknown or unavailable library, an unknown or
  ambiguous book, a book that is not a `.hdoc` file (only filesystem books
  have a file to build), and `--in-place`, which would rewrite a library's
  book under a name the user never typed. The directory's own load
  problems are `::directory-diagnostics`, printed once, first."
  [opts parsed]
  (let [{:keys [input target in-place]} opts
        what (str "input " (pr-str input) " (no such file, so read as a book ID)")
        {:keys [id passage]} parsed
        {lib-name :library slug :slug} id
        target-info (targets target)]
    (when passage
      (throw (ex-info (str what ": a book ID input names a whole book, not a passage; drop #"
                           (book-ref/format-passage passage))
                      {:type ::book-input-error})))
    (when in-place
      (throw (ex-info (str "--in-place needs a file input, not a book ID (" input ")")
                      {:type ::usage-error})))
    (let [kinds (parse-kinds (registry/numbering-lexicon (build-registry) resolver/default-lexicon)
                             (:target target-info) (:computed-numbers opts))
          dir (load-directory! opts kinds what)
          diagnostics (directory-diagnostics dir)
          lib (find-library! dir lib-name what diagnostics)
          book (library/find-book lib slug)
          fail (fn [message]
                 (throw (ex-info (str what ": " message)
                                 {:type ::book-input-error ::diagnostics diagnostics})))]
      (cond
        (nil? book) (fail (str "library " (pr-str lib-name) " has no book " (pr-str slug)))
        (library/diagnostic? book) (fail (:message book))
        ;; TASK-158: doc-10 builds only a filesystem book from a book ID,
        ;; since only it has a source file; a generated book's document
        ;; is the script's data, so say where it comes from.
        (generated/generated-book? book)
        (fail (str "book " (book-ref/format-book-id (:id (library/book-entry book)))
                   " is generated by " (generated/script-path book)
                   ", so there is no .hdoc source to build; quote it from a document instead"))
        (not (fs/filesystem-book? book))
        (fail (str "book " (book-ref/format-book-id (:id (library/book-entry book)))
                   " is not a .hdoc file, so there is no source to build"))
        :else
        (let [current (:id (library/book-entry book))
              canonical (book-ref/format-book-id current)
              stale (when (and (seq (:shelf id)) (not= (:shelf id) (:shelf current)))
                      {:type :stale-shelf
                       :level :info
                       :ref input
                       :canonical canonical
                       :message (str "book ID " (pr-str input) ": the book is now at " canonical
                                     "; it still resolves, but its shelf path is stale")})]
          (assoc opts
                 :input (str (fs/entry-file book))
                 ::book {:id current :directory dir}
                 ::book-ref input
                 ::directory-diagnostics (cond-> diagnostics stale (conj stale))
                 ::default-output (str slug (:extension target-info))))))))

(defn- books-option
  "`resolve-document`'s `:books` for one build of `opts`
  (`references/books-option`): the BOOK-ID input's already-loaded
  directory, whose load problems `build` has already reported, or the
  injected `:library-directory` value, or a lazy loader; a passage context
  sharing `resolve-opts`, so a book numbers and names its labels with this
  build's lexicon; and `:self` for a book."
  [opts kinds resolve-opts]
  (let [{book-id :id dir :directory} (::book opts)]
    (references/books-option
     (cond-> {:directory (or dir
                             (:library-directory opts)
                             (references/directory-loader (directory-opts opts kinds)))
              :directory-reported? (some? dir)
              :context (passage/context {:resolve-opts resolve-opts})}
       book-id (assoc :self book-id)))))

(defn- with-books
  "`resolve-opts` -- the build's own `resolve-document` options -- with
  `:books`, so quote expansion and link resolution run inside
  `resolve-document`, and therefore before `sanitize/sanitize-document`
  sees the URLs they fill in (TASK-156 review). Checks `--libraries` first."
  [resolve-opts opts kinds]
  (check-libraries-file! opts)
  (assoc resolve-opts :books (books-option opts kinds resolve-opts)))

(def ^:private build-only-flags
  "Every option `parse-args` collects that only means something to a
  build, keyed by the spelling `--help` gives it -- what `--list-books`
  (which builds nothing) has no use for."
  [[:target "--target"] [:output "--output"] [:in-place "--in-place"]
   [:computed-numbers "--computed-numbers"] [:fragment "--fragment"]
   [:bibliography "--bibliography"] [:overlay "--overlay"] [:extensions "--extensions"]
   [:trusted-extensions "--trusted-extensions"] [:transform "--transform"]
   [:no-stylesheet "--no-stylesheet"] [:untrusted "--untrusted"]])

(defn- list-books-flag-diagnostics
  "One irrelevant-flag warning (TASK-108's policy) per build option given
  together with `--list-books`: the listing is unaffected, but a flag the
  user typed is never silently dropped."
  [opts]
  (vec (for [[k flag] build-only-flags
             :when (some? (get opts k))]
         (irrelevant-flag-diagnostic flag (str flag " has no effect with --list-books")))))

(defn list-books
  "`--list-books` (doc-10, \"CLI\"): `{:books [[canonical-id title] ...]
  :diagnostics [...]}` for the library `lib-name` of `opts`' library
  directory, sorted by canonical ID, each title as plain text. Reads only
  front matter. `:diagnostics` are a warning per build-only flag given
  with it (`list-books-flag-diagnostics`), the directory's own load problems and
  the library's scan problems (`:duplicate-slug`, `:invalid-book-name`,
  ...), worth seeing exactly when looking for what to cite. Throws, as a
  BOOK-ID input does, when there is no directory or no such usable
  library."
  [opts lib-name]
  (let [what (str "--list-books " lib-name)
        flag-diagnostics (list-books-flag-diagnostics opts)
        dir (try (load-directory! opts nil what)
                 (catch clojure.lang.ExceptionInfo e
                   (throw (ex-info (ex-message e)
                                   (assoc (ex-data e) ::diagnostics flag-diagnostics) e))))
        diagnostics (into flag-diagnostics (directory-diagnostics dir))
        lib (find-library! dir lib-name what diagnostics)]
    {:books (->> (library/list-books lib)
                 (map (fn [{:keys [id title]}]
                        [(book-ref/format-book-id id) (str/join (map anchor/inline-text title))]))
                 (sort-by first)
                 vec)
     :diagnostics (into diagnostics (when (satisfies? library/LibraryDiagnostics lib)
                                      (library/library-diagnostics lib)))}))

(defn run
  "The pure core (AC #1/#2/#3): parses `source`, resolves it, lowers it
  for `opts`' own target, and emits it. Returns `{:output :diagnostics
  :bibliography :ordered-keys :preamble}` -- the emitted document text,
  every resolver warning in pass order (AC #4), the loaded bibliography
  plus the keys the document cites, which `build` needs to generate the
  BibTeX database native mode asks for (TASK-42), and (only for a
  `--fragment` LaTeX build, TASK-52) the companion preamble text the
  emitted body's host has to load. Reads nothing but the
  bibliography the document itself names (see `bibliography-path`) --
  required to be PRESENT for every target except json/hdoc (TASK-107;
  see this function's own `:json`/`:hdoc` paragraph below) --
  every `opts`' own `:overlay` path (TASK-83.1, `load-overlays!`, non-
  json/hdoc target only -- see its own docstring), every `opts`' own
  `:transform` path (TASK-103, `haselnuss.extensions.rules/
  load-transforms` -- applied via `haselnuss.transform/transform-
  document` immediately after parsing, on EVERY target, json/hdoc
  included: see this function's own `:json`/`:hdoc` paragraph below,
  doc-7 decision 1), and every `opts`' own `:extensions` path (TASK-90,
  `haselnuss.extensions.sidecar/load-extensions`, non-json/hdoc target
  only, for the same reason `:overlay` is) -- and writes nothing: a
  caller decides what to do with the result. `:extensions` and
  `:transform` share one forked `haselnuss.sandbox` context per call
  (`sandbox-ctx`, below) rather than each getting its own (doc-7
  decision 5). `:trusted-extensions` (also non-json/hdoc-only) is
  forwarded to `load-extensions` as `:trusted?`, gating whether every
  SIDECAR loaded THIS call may emit unescaped output
  (`haselnuss.registry/register`'s own `:trusted?`); it defaults to
  false and is never something a sidecar file can set for itself, and it
  grants a `--transform` rule nothing at all -- `haselnuss.transform`'s
  own return-value contract accepts plain AST data only, never a
  `[:raw ...]` node, so there is nothing for this flag to gate there
  (`haselnuss.extensions.rules`'s own docstring, NO TRUST FLAG IS
  INVOLVED).

  `opts` additions beyond the CLI's own flags: `:bib-resource`, the
  `\\bibliography{}` argument native mode should name. `build` passes
  the database it is about to write; a caller invoking `run` DIRECTLY
  gets the emitter's own default instead (`meta.bibliography` with its
  extension stripped), which names a `.bib` nothing here produces --
  such a caller is writing the files itself and owns that file too.

  Throws whatever `parse`/`lower`/`emit-document` throw. That is the
  unrecoverable half of the split this namespace's docstring describes
  (AC #5); `build`/`-main` turn it into a message and an exit code.

  The `:json` and `:hdoc` targets (TASK-80, TASK-98) are a deliberate
  exception to all of the above, and to each other only in which emitter
  they call: both return right after `parser/parse` and the `--transform`
  pass immediately following it (TASK-103), with `:diagnostics` exactly
  that pass's own `:transform-rule-unmatched` diagnostics (`[]` with no
  `--transform` at all) -- no `resolve-document`, no `build-registry`, no
  `lower`, no other emitter runs at all. `:json`'s `:output` is the
  parsed (and possibly transformed) Document's own JSON interchange
  representation (`haselnuss.json/->json`, through `coalesce-prose` --
  see its own docstring); `:hdoc`'s is the same Document written back out
  as canonical `.hdoc` source (`haselnuss.emit.hdoc/emit-document`, given
  that Document directly -- no `coalesce-prose` step, since that
  transform exists only to compact the JSON dump). Both still need
  `vocabulary` (so a bare `@key` parses as the same construct it would on
  any other target), and therefore the same `bib-path`/`bibliography`
  computation the rest of this function does, but nothing past
  `parser/parse`/`--transform` -- resolution is what would turn a
  `CrossRef`'s bare `:label` into resolved `:text`, and the point of both
  targets is the document exactly as parsed (or transformed), not that
  computed view of it. Unlike every other target, though, neither one
  requires the file to exist at all (TASK-107): the bibliography's own
  CONTENT never reaches either target's output, only (through
  `vocabulary`'s `:cite-keys`) which bare `@word` tokens parse as a
  citation at all, so a file that IS there is still loaded and still
  used for exactly that classification -- matching html/latex's own
  reading of the same document -- while one that is not is treated as if
  `meta.bibliography` had never been declared: `:cite-keys` comes back
  empty, so no bare `@word` parses as a Cite for this build, the same
  outcome a document with no bibliography at all already gets. `build`'s
  own multi-file convention (a chapter whose CSL-JSON lives at the book
  root) is exactly the case this exists for: formatting one chapter file
  in isolation, or running it through `--in-place`, no longer requires
  the bibliography to be sitting right beside it. A file that IS there
  but fails to parse as CSL-JSON still fails the build loudly on every
  target, json/hdoc included -- only ABSENCE is forgiven here, not
  malformed content (`load-bibliography!`'s own docstring). A
  consequence for `:hdoc` in particular: every flag that only means
  something to a resolved/lowered build -- `:computed-numbers`, `:fragment`,
  `:no-stylesheet`, `:overlay` (and, for the same reason,
  `:extensions`/`:trusted-extensions`, since sidecar loading also sits
  past this branch) -- is a no-op here, the same way a latex-only flag is
  already a no-op on `--target html`. Not a SILENT one any more
  (TASK-108): `flag-diagnostics`/`irrelevant-flag-diagnostics`, bound
  once in the outer `let` before this branch is even chosen, reports each
  one given as a warning, the exact contract every other diagnostic in
  this namespace already follows -- the build still produces this exact
  output, unaffected. `:transform` is the one flag in that list this
  branch does NOT no-op: it is the whole reason this branch reads
  `document` from the outer `let` rather than re-deriving it, rather than
  the `parser/parse` result directly."
  [source {:keys [target computed-numbers] :as opts}]
  (when-not (targets target)
    ;; `run` and `build` are public, so a caller can reach them without
    ;; going through `parse-args`' own validation; without this an
    ;; unknown target died as `IllegalArgumentException: No matching
    ;; clause` from a `case` several steps later.
    (throw (ex-info (str "unknown target " (pr-str target)
                         " (expected one of: " (str/join ", " (sort (keys targets))) ")")
                    {:type ::usage-error})))
  (let [{emit-target :target} (targets target)
        ;; TASK-108: a pure, command-line-only fact, so it is computed
        ;; before anything is even read off disk and leads every other
        ;; diagnostic below -- the same "ran first, chronologically"
        ;; ordering `transform-diagnostics` gets for the same reason,
        ;; one step earlier still since this needs no parse at all.
        ;; TASK-157: a BOOK-ID input's library directory was loaded by
        ;; `build` before this ran, so its load problems lead even these.
        flag-diagnostics (into (vec (::directory-diagnostics opts))
                               (irrelevant-flag-diagnostics opts emit-target))
        ;; Two registries, same extensions: the pre-numbering one only
        ;; supplies directive names and `:kind` fragments, while the
        ;; post-numbering one closes its degradation rules over the real
        ;; label table so a degraded directive's head carries the same
        ;; number a cross-reference to it prints (see
        ;; `environment-lower-rule`).
        base-registry (build-registry)
        lexicon (registry/numbering-lexicon base-registry resolver/default-lexicon)
        ;; Bound once: the numbering pass, the TOC derivation and the
        ;; structural diagnostics all have to agree about which
        ;; directives are front matter, and a set spelled out three
        ;; times is three places for them to stop agreeing.
        front-matter-names (set (keys front-matter/blocks))
        ;; The vocabularies that tell a real `@fig:tree`/`@knuth1984` from
        ;; a chat handle or a social mention (TASK-47) are assembled
        ;; BEFORE parsing. The lexicon was always available this early;
        ;; the bibliography needs the document's own `meta.bibliography`,
        ;; which is why the front matter is read on its own first rather
        ;; than the whole document being parsed twice. One consequence,
        ;; small but real: a document with BOTH a parse error and a
        ;; missing bibliography now reports the missing bibliography,
        ;; where it used to report the parse error -- true on every
        ;; target EXCEPT json/hdoc (TASK-107, just below), which never
        ;; make that report at all.
        bib-path (bibliography-path opts (:bibliography (parser/front-matter source (when (:input opts) {:source (:input opts)}))))
        ;; TASK-107: required everywhere except the json/hdoc branch
        ;; below, which reads this only for `vocabulary`'s `:cite-keys`
        ;; (never the bibliography's own content) and, per that branch's
        ;; own docstring, must not refuse a build over a file it was
        ;; never going to open. A present file is still loaded either
        ;; way -- and still fails loudly if it is there but malformed --
        ;; so only ABSENCE is forgiven, and only for those two targets.
        bibliography (load-bibliography! bib-path (not (contains? #{:json :hdoc} emit-target)))
        ;; ...but only where this codebase is the authority on what a
        ;; reference resolves to. Native-mode LaTeX is not that case: it
        ;; emits \\Cref/\\citet and lets LaTeX and BibTeX resolve them,
        ;; against a vocabulary nothing here can see -- a `.bib` whose
        ;; keys need not match the CSL-JSON's (the documented seam
        ;; `latex/bib-resource-name` describes and TASK-42 tracks), and
        ;; LaTeX's own \\label namespace, which accepts prefixes the
        ;; lexicon has no kind for. Filtering there would turn a working
        ;; \\citet{key} into literal text (found by review). So the
        ;; filter applies to HTML, and to LaTeX only under
        ;; --computed-numbers, where the resolver's own answers are what
        ;; gets baked in and an unresolved reference was already a `??`.
        vocabulary (when-let [kinds (parse-kinds lexicon emit-target computed-numbers)]
                     ;; `:cite-keys` is the empty set, not nil, when there
                     ;; is no bibliography at all: such a document has no
                     ;; bare citation that could resolve, and it is
                     ;; exactly where "cc @someone" gets written. nil
                     ;; would hand those documents back to the heuristic.
                     ;; A bracketed `[@key]` is unaffected either way --
                     ;; explicit syntax, and it still warns when it
                     ;; dangles.
                     {:kinds kinds
                      :cite-keys (set (keys bibliography))})
        document (parser/parse source (cond-> vocabulary (:input opts) (assoc :source (:input opts))))
        ;; TASK-103, doc-7 decision 1: the transform pass runs HERE,
        ;; immediately after parsing and before the json/hdoc-vs-resolve
        ;; branch below, so it composes with EVERY target -- including
        ;; json, the only other target that stops this early, and
        ;; including a target that resolves and lowers, where a transform
        ;; still runs on the document exactly as parsed, before any of
        ;; that computed content exists to be seen. `sandbox-ctx`
        ;; (`haselnuss.sandbox/fork`, `delay`-wrapped) is shared with
        ;; `--extensions`' own `sidecar/load-extensions` call below (non-
        ;; json/hdoc branch only) rather than each forking its own --
        ;; `haselnuss.sandbox`'s own docstring, ISOLATION -- so a build
        ;; using neither flag pays no sci init cost at all, and one using
        ;; both shares a single fork. `rules/load-transforms` with no
        ;; `--transform` paths at all returns `[]`, and `transform/
        ;; transform-document` over `[]` rules is a no-op returning
        ;; `document` unchanged with empty diagnostics -- no special case
        ;; needed for the common "no --transform" build.
        sandbox-ctx (delay (sandbox/fork))
        transform-rules (rules/load-transforms (:transform opts) {:ctx sandbox-ctx})
        {document :document transform-diagnostics :diagnostics}
        (transform/transform-document document transform-rules)]
    (if (contains? #{:json :hdoc} emit-target)
      ;; TASK-80/TASK-98: the json and hdoc targets are both the raw
      ;; parsed (and, since TASK-103, transformed) AST, not a resolved/
      ;; lowered view -- SPEC.md sec11 treats the JSON representation as
      ;; the canonical interchange form derived straight from parsing, and
      ;; `haselnuss.emit.hdoc`'s own docstring ("THE CONTRACT") gives the
      ;; identical reason for stopping at the same point, so no resolver
      ;; diagnostic, directive registry, or lowering pass applies to
      ;; either. `coalesce-prose` is a json-only step, folding
      ;; CommonMark's own per-word :str/:space tokenization into
      ;; plain-text :str runs purely for a more compact dump -- see its
      ;; own docstring; `hdoc/emit-document` takes the parsed (and
      ;; possibly transformed) Document as-is, with no such compaction.
      {:diagnostics (into (vec flag-diagnostics) transform-diagnostics)
       :bibliography bibliography
       :ordered-keys nil
       :preamble nil
       :front-matter nil
       :output (case emit-target
                 :json (json/->json (coalesce-prose document))
                 ;; `vocabulary` again (TASK-135): the emitter backslash-
                 ;; escapes an `@word` only if it would re-parse as a
                 ;; reference, which is the same question the parse above
                 ;; asked of it.
                 :hdoc (hdoc/emit-document document vocabulary))}
      (let [;; TASK-83.1. Read here, before `resolve-document` runs, and
            ;; only on this (non-json/hdoc) branch -- an `--overlay`
            ;; combined with `--target json` or `--target hdoc` has no
            ;; effect, matching `--bibliography`'s own existing behavior
            ;; there (neither target calls `resolve-document` at all). A
            ;; missing path is a hard failure (`load-overlays!`'s own
            ;; `ex-info`); every overlay file's own `!include` warnings
            ;; are threaded through below, alongside `resolve-document`'s.
            {overlay-sources :sources overlay-load-warnings :warnings}
            (load-overlays! (:overlay opts) vocabulary)
        ;; TASK-90. Loaded here too, alongside overlays, for the same
        ;; --target json/hdoc exclusion (this whole branch is never
        ;; reached on either target -- see `usage`'s own "No effect on
        ;; --target json or hdoc" for `--extensions`) and because a
        ;; sidecar-defined directive has to be part of the registry
        ;; BEFORE `resolve-document` runs, or it is reported as an
        ;; unknown directive rather than dispatched.
        ;; A missing `--extensions` path, a malformed extension entry, or
        ;; a sidecar file that fails to evaluate (host interop, a file
        ;; read, a non-terminating top-level form, ...) are all hard
        ;; failures naming the offending file
        ;; (`haselnuss.extensions.sidecar`'s own `ex-info`), the same
        ;; "typed on the command line, not embedded in document prose"
        ;; treatment `load-overlays!`/`load-bibliography!` already give
        ;; their own paths.
        ;;
        ;; A sidecar extension's own `:kind` fragment, if it gives one, is
        ;; deliberately NOT folded into `lexicon` above -- that is
        ;; computed once, before this branch, from the built-in registry
        ;; only, since `lexicon` also drives `vocabulary` for PARSING,
        ;; which runs on every target and is exactly what "no effect on
        ;; --target json or hdoc" means to keep true. This is the same
        ;; "without the lexicon a registered kind never numbers" caveat
        ;; this namespace's own docstring already names for `:kind` in
        ;; general (`build-registry`'s own docstring), now also true of
        ;; one sourced from a sidecar file specifically: its directive is
        ;; fully dispatched and rendered, but a NEW numbering kind it
        ;; tries to introduce is not numbered -- an author reaches
        ;; existing numbering (a referenceable, numbered result) the same
        ;; way `chart`'s own SPEC.md sec8.4 example already does, by
        ;; reusing an EXISTING kind's id-prefix convention (`#fig:...`),
        ;; not by inventing a new kind keyword.
            sidecar-extensions (sidecar/load-extensions
                                (:extensions opts)
                                {:trusted? (boolean (:trusted-extensions opts))
                                 :ctx sandbox-ctx})
            directive-registry (registry/register-all base-registry sidecar-extensions)
            {:keys [document diagnostics labels ordered-keys bibliography-id]}
            ;; TASK-105 review finding: `resolve-document`'s only
            ;; exception-throwing step is `expand-includes`, and once it
            ;; throws (an included file that exists but fails to parse),
            ;; its own `:warnings` -- every diagnostic THAT pass already
            ;; collected from an earlier, unrelated include before the
            ;; fatal one -- rides along in `(ex-data e)` rather than being
            ;; lost with the rest of `resolve-document`'s never-reached
            ;; return. This catch folds it into `::diagnostics` alongside
            ;; `transform-diagnostics`/`overlay-load-warnings` (the two
            ;; diagnostic sources that ran even earlier, before
            ;; `resolve-document` was even called -- see this list's own
            ;; ordering a few lines below, for the success path), the
            ;; exact `lower`/emit try/catch precedent just below this one
            ;; already sets for "warnings collected before a fatal error
            ;; must still reach `main*`".
            (try
              (resolver/resolve-document document
                                         (cond-> {:lexicon lexicon
                                                  :directive-registry directive-registry
                                            ;; TASK-48. The same table
                                            ;; `build-registry` registers, read
                                            ;; for the kind each directive
                                            ;; numbers as -- so an id prefix
                                            ;; naming a different kind is a
                                            ;; warning rather than two
                                            ;; different numbers in the two
                                            ;; targets.
                                                  :directive-kinds
                                                  (latex/directive-lexicon-kinds)
                                            ;; TASK-54. Which directives
                                            ;; are front matter is the
                                            ;; extension's to say; what
                                            ;; the resolver does with
                                            ;; that -- number nothing
                                            ;; inside them, list none of
                                            ;; their sections -- is
                                            ;; `body-view`'s.
                                                  :front-matter-names front-matter-names
                                            ;; TASK-56. Which directive
                                            ;; is a PANEL of the float
                                            ;; above it is the emitter
                                            ;; table's to say; what the
                                            ;; resolver does with it --
                                            ;; letter it within that
                                            ;; float instead of
                                            ;; numbering it -- is
                                            ;; `number-block`'s. Read
                                            ;; off the same table the
                                            ;; LaTeX side lays the
                                            ;; panels out from, so the
                                            ;; two cannot disagree
                                            ;; about what a panel is.
                                                  :sublabel-names
                                                  (latex/sublabel-directive-names)
                                            ;; TASK-38. The resolver owns the
                                            ;; splicing, the cycle guard and
                                            ;; the diagnostics; reading and
                                            ;; parsing a file is this layer's
                                            ;; job, so the resolver keeps its
                                            ;; passes pure and never depends
                                            ;; on the parser. An included
                                            ;; file is parsed with the same
                                            ;; vocabulary as the including
                                            ;; one, so an `@`-token means the
                                            ;; same thing in a chapter as in
                                            ;; the document that includes it.
                                                  :includes
                                                  {:base-dir (:base-dir opts)
                                             ;; Puts the document itself
                                             ;; on the cycle stack, so a
                                             ;; loop back to the root is
                                             ;; caught like any other.
                                                   :source-path (:input opts)
                                                   :load (fn [file]
                                                           (parser/parse (slurp file)
                                                                         (assoc vocabulary :source (str file))))}
                                            ;; TASK-83.1. Already read
                                            ;; and include-expanded above
                                            ;; (`load-overlays!`); empty
                                            ;; with no `--overlay` at
                                            ;; all, which `apply-overlays`
                                            ;; treats as a no-op.
                                                  :overlay-sources overlay-sources}
                                           bibliography (assoc :bibliography bibliography)
                                            ;; TASK-157. Last, so the
                                            ;; books share every option
                                            ;; above that they keep.
                                           true (with-books opts (:kinds vocabulary))))
              (catch clojure.lang.ExceptionInfo e
                (throw (ex-info (ex-message e)
                                (assoc (ex-data e)
                                       ::diagnostics
                                       (into (vec flag-diagnostics)
                                             (into (vec transform-diagnostics)
                                                   (into (vec overlay-load-warnings)
                                                         (:warnings (ex-data e))))))
                                e))))
            ;; TASK-108's own `flag-diagnostics` lead every other
            ;; diagnostic in the list, ahead even of `transform-
            ;; diagnostics`: it needs no parse at all, so it is known
            ;; before `transform-diagnostics`'s own pass -- the first
            ;; thing this function does with `source` -- has even run.
            ;; TASK-103's own `transform-diagnostics` lead every other
            ;; diagnostic behind it -- that pass ran first,
            ;; chronologically, before parsing's own overlay/`!include`
            ;; expansion or anything the resolver itself found.
            ;; `load-overlays!`'s OWN warnings (reading/expanding the
            ;; overlay FILES themselves) come next, ahead of `resolve-
            ;; document`'s -- that I/O happened here, before `resolve-
            ;; document` was even called, unlike `:includes`, whose
            ;; warnings `resolve-document` already folds in itself (see
            ;; its own docstring).
            diagnostics (into (vec flag-diagnostics)
                              (into (vec transform-diagnostics)
                                    (into (vec overlay-load-warnings) diagnostics)))
            ;; TASK-136. After the resolver, so overlay-copied `describe`
            ;; props and bibliography links are already in the tree, and
            ;; before lowering and every emitter: a javascript:/data: URL
            ;; or an on* attribute never reaches output. Its diagnostics
            ;; are warnings like the resolver's.
            {document :document sanitize-diagnostics :diagnostics}
            (sanitize/sanitize-document document)
            diagnostics (into diagnostics sanitize-diagnostics)
            ;; TASK-219: the LaTeX build splices the document's own TeX
            ;; as written. `--untrusted` first holds every such string to
            ;; TASK-215's allow-list, failing the build at the first one
            ;; outside it; then, flag or not, `\href`/`\url`/`\hyperref` are dropped
            ;; from math, so `$\href{javascript:...}{x}$` makes no PDF
            ;; link. Here, on the resolved document, so included files,
            ;; overlays and a transform's output are covered, and before
            ;; lowering, so an extension's output is not: that is
            ;; `--trusted-extensions`' business (TASK-215).
            {document :document math-link-diagnostics :diagnostics}
            (if (= :latex emit-target)
              (do (when (:untrusted opts)
                    (try
                      (sanitize/vet-untrusted-document document)
                      (catch clojure.lang.ExceptionInfo e
                        (throw (ex-info (ex-message e)
                                        (assoc (ex-data e) ::diagnostics diagnostics)
                                        e)))))
                  (sanitize/neutralise-math-links document))
              {:document document :diagnostics []})
            diagnostics (into diagnostics math-link-diagnostics)
        ;; TASK-90. The same sidecar extensions merged into
        ;; `directive-registry` above, merged again onto the labels-aware
        ;; registry `lower`/the emitters actually dispatch through --
        ;; `build-registry` is called twice for the reason its own
        ;; docstring gives (a pre- and a post-numbering registry), so
        ;; this merge is repeated here rather than threaded through once.
            build-reg (registry/register-all (build-registry labels) sidecar-extensions)
        ;; TASK-59. Derived here, once, from the SAME resolved document
        ;; and the same label table every cross-reference's text was
        ;; baked from -- the discipline `:labels` already follows, and
        ;; for the same reason: an emitter re-deriving them would build
        ;; a second table that has to agree with the first by luck.
        ;; Derived before lowering, because that is where the document
        ;; still has the Figures, Tables and float directives these
        ;; lists are built from; and with the same
        ;; `front-matter-names`, so a heading inside an abstract is no
        ;; more a table-of-contents entry than it is a numbered
        ;; section.
            float-names (latex/float-directive-names)
            derived-lists {:toc (resolver/derive-toc document labels front-matter-names)
                           :list-of-figures (resolver/derive-list-of-figures document labels float-names)
                           :list-of-tables (resolver/derive-list-of-tables document labels float-names)}
            emit-opts {:registry build-reg
                       :labels labels
                       :derived-lists derived-lists
                       :bibliography-id bibliography-id
                   ;; TASK-42: in native mode `build` generates a .bib
                   ;; from the CSL-JSON already loaded and names it here,
                   ;; so the reference list needs no hand-maintained
                   ;; database. `:bib-resource` is the name of THAT file
                   ;; (see `bibtex-resource`), not of the CSL-JSON.
                   ;;
                   ;; The fallback, for a caller reaching `run` directly
                   ;; and writing nothing: only an explicit
                   ;; --bibliography overrides the emitter's own default.
                   ;; It has to, or native mode resolves citations
                   ;; against the overriding file while emitting
                   ;; \\bibliography{} naming the document's own, so
                   ;; BibTeX builds the reference list from the file the
                   ;; user overrode. But it must NOT be passed for an
                   ;; ordinary document, or the resolved, absolute path
                   ;; leaks into the .tex -- turning a portable
                   ;; \\bibliography{refs} into a machine-specific one
                   ;; (found by review).
                       :bib-resource (or (:bib-resource opts)
                                         (some-> (:bibliography opts) latex/bib-resource-name))}
        ;; Lowering happens here rather than inline below because it
        ;; produces a diagnostic of its own (TASK-51): only the LOWERED
        ;; tree shows which references now point at a degraded directive,
        ;; and only this layer knows the target and the mode.
        ;; TASK-215: what lowering and the emitters drop from an untrusted
        ;; extension's output (an on* or javascript: attribute in its
        ;; hiccup, an unsafe link in the AST it renders through its ctx
        ;; callbacks or returns from its :lower rule) is collected here and
        ;; warned about like the document pass's own.
            extension-warnings (atom [])
            lowered (binding [sanitize/*untrusted-diagnostics* extension-warnings]
                      (try
                        (lower/lower document emit-target build-reg)
                        (catch clojure.lang.ExceptionInfo e
                          (throw (ex-info (ex-message e)
                                          (assoc (ex-data e) ::diagnostics
                                                 (into diagnostics @extension-warnings))
                                          e)))))
            diagnostics (cond-> diagnostics
                          (= :latex emit-target)
                          (-> (into (latex/unanchored-reference-diagnostics
                                     lowered {:computed-numbers (boolean computed-numbers)}))
                              ;; TASK-121: a sticky note whose body a PDF
                              ;; annotation string cannot hold is emitted
                              ;; as a footnote instead, and an annotation
                              ;; nested inside a highlight degrades too.
                              ;; Both are the emitter's own knowledge and
                              ;; neither may be silent -- the whole point
                              ;; of a defined degradation is the
                              ;; diagnostic at its boundary (SPEC sec4.5).
                              (into (latex/annotation-diagnostics lowered))))
        ;; TASK-89 review comments #16/#17 (finalizer's own call, after a
        ;; blocking false-negative -- Finding A -- and a blocking
        ;; regression -- Finding B -- were both found in the AST+text
        ;; reconciliation machinery a #11/#12/#13/#14 patch sequence had
        ;; built here): the AST-based image-path-hazard check
        ;; (`latex/unescapable-image-path-diagnostics`, TASK-78) is
        ;; DELIBERATELY NOT called from here any more. It is still
        ;; defined, still public, still independently tested in
        ;; `haselnuss.emit.latex-test` -- just not wired into this
        ;; pipeline. See `unescapable-image-path-text-diagnostics`'s own
        ;; docstring, immediately below where its output is used, for
        ;; what running only the text-based scan means it does not catch,
        ;; and why that is now an accepted, documented limitation rather
        ;; than something this namespace tries to close by reconciling
        ;; two overlapping scans.
        ;; One options map for both LaTeX entry points (TASK-52). The
        ;; companion preamble is only worth anything if it names what
        ;; THIS body needs, so it must be derived from the same document
        ;; and the same options -- not from a second, hand-kept list.
            latex-opts (assoc emit-opts
                              :computed-numbers (boolean computed-numbers)
                              :fragment (boolean (:fragment opts)))
            fragment-latex? (and (= :latex emit-target) (:fragment opts))
        ;; Every emission -- the document, the companion preamble and the
        ;; front-matter side files -- inside ONE try, so whichever of them
        ;; throws carries the diagnostics collected so far (found by
        ;; review: a nested front-matter block failed the fragment build
        ;; with none of them, while the same document's standalone build
        ;; reported them, because two of these used to be evaluated
        ;; outside the guard).
            {:keys [output preamble front-matter]}
            (binding [sanitize/*untrusted-diagnostics* extension-warnings]
              (try
                {:output (case emit-target
                           :html (html/emit-document lowered
                                                     (assoc emit-opts
                                                            :ordered-keys ordered-keys
                                                            :stylesheet (if (:no-stylesheet opts)
                                                                          :none
                                                                          :default)))
                           :latex (latex/emit-document lowered latex-opts))
                 :preamble (when fragment-latex? (latex/emit-preamble lowered latex-opts))
                 :front-matter (when fragment-latex? (latex/emit-front-matter lowered latex-opts))}
                (catch clojure.lang.ExceptionInfo e
                  (throw (ex-info (ex-message e)
                                  (assoc (ex-data e) ::diagnostics
                                         (into diagnostics @extension-warnings))
                                  e)))))
            diagnostics (into diagnostics @extension-warnings)
        ;; TASK-76/78: the last thing checked is what is actually about to
        ;; be written, so authored and generated text are scanned by one
        ;; pass, once `output`/`front-matter` both exist. The front-matter
        ;; side files are the document too -- a fragment build puts
        ;; authored prose in them rather than in `output`. The companion
        ;; preamble is the one written file NOT scanned, and deliberately:
        ;; every character in it comes from this emitter's own package
        ;; list rather than from the author.
        ;;
        ;; The image-path-hazard check (TASK-78) is the text-based
        ;; `unescapable-image-path-text-diagnostics` ONLY (TASK-89 review
        ;; comments #16/#17 -- see the comment above `latex-opts`, and
        ;; that function's own docstring, for why the AST-based
        ;; `unescapable-image-path-diagnostics` used to also run here and
        ;; no longer does): it sees every hazard a core Image's own
        ;; `\includegraphics{...}` can carry AND, since it scans the
        ;; actually-emitted text rather than the AST, a v2 renderer's own
        ;; trusted `:raw` output too, which never becomes an `:image` AST
        ;; node at any stage. Its own documented residual limitation (a
        ;; stray-CLOSING-brace hazard with nothing open before it) is an
        ;; accepted, tested gap now, for core-authored and extension-
        ;; produced content alike, rather than something this namespace
        ;; tries to close by also running a second, AST-based scan and
        ;; reconciling the two.
            text-based-diagnostics
            (if (= :latex emit-target)
              (into [] (mapcat (fn [text]
                                 (concat (latex/untypesettable-character-diagnostics text :document)
                                         (latex/unescapable-image-path-text-diagnostics text))))
                    (cons output (map :content front-matter)))
              [])]
        {:diagnostics
     ;; Plain concatenation, no de-duplication (TASK-89 review comment
     ;; #21): `diagnostics` (resolver warnings, overlay warnings, the
     ;; unanchored-reference check) and `text-based-diagnostics`
     ;; (character/image-path hazards scanned from the final emitted
     ;; text) are fully disjoint in both type and source now that the
     ;; AST-based image-path check is gone -- there is no overlapping
     ;; scan left to reconcile between them, the only thing any
     ;; de-duplication here was ever structurally needed for. A `distinct`
     ;; briefly lived here (comments #19-20) on the belief that it
     ;; restored pre-TASK-89 behavior for a `:dangling-cross-ref`
     ;; diagnostic repeated for the same missing id referenced twice --
     ;; comment #21 checked that directly against the actual pre-TASK-89
     ;; commit (`d751e26`) rather than against this task's own prose about
     ;; it, and found the claim false: pre-TASK-89 `run` never
     ;; de-duplicated anything, and `haselnuss.resolver` produces one
     ;; `:dangling-cross-ref` warning per occurrence, undeduplicated, by
     ;; its own established design (its own docstring says so). Plain
     ;; `into`, matching that exactly, rather than a `distinct` that was
     ;; new, general, whole-list behavior no AC ever asked for and that
     ;; this codebase had mis-described as a restoration since the round
     ;; that introduced it.
         (into diagnostics text-based-diagnostics)
     ;; Returned so `build` can write the generated BibTeX database
     ;; (TASK-42) from the same loaded data the resolver formatted
     ;; against, rather than reading `meta.bibliography` a second time
     ;; and risking the two disagreeing.
         :bibliography bibliography
     ;; Alongside it, the keys the document actually cites, so the
     ;; generated database carries those entries and not every entry in
     ;; a possibly-shared CSL-JSON file.
         :ordered-keys ordered-keys
     ;; The companion preamble a `--fragment` build owes its host
     ;; (TASK-52), or nil when this build is not one -- `build` writes it
     ;; beside the fragment as `<output>-preamble.tex`. Returned rather
     ;; than written here for the same reason `:bibliography` is: this
     ;; function writes nothing at all.
         :preamble preamble
     ;; TASK-54: the front-matter blocks a fragment does NOT put in its
     ;; body, each to become its own `\input`-able side file. Empty for
     ;; a document with none, and nil for any other build -- a
     ;; standalone document places them itself.
         :front-matter front-matter
         :output output}))))

(def ^:private generated-bibtex-marker
  "The first line of a `.bib` this namespace generated (TASK-42). Its
  only job is to tell a rewrite from a clobber: `build` replaces a file
  that starts with it and refuses to touch one that does not, so an
  author's own `paper.bib` beside their `paper.tex` survives a build
  that would otherwise have eaten it."
  "% Generated by haselnuss from the document's CSL-JSON bibliography.")

(def ^:private generated-preamble-marker
  "The first line of a companion preamble this namespace generated
  (TASK-52), serving the same rewrite-vs-clobber purpose
  `generated-bibtex-marker` does for a generated `.bib`.

  Guarded for a weaker reason than the `.bib` is, and deliberately kept
  anyway. `paper.bib` is a widespread hand-maintained convention, so a
  first build eating one is a realistic accident;
  `<output>-preamble.tex` is a name this tool invents. But a host
  template author is exactly the person who might hand-write a preamble
  file, and the whole point of fragment mode is that they own the
  document around the fragment -- refusing to overwrite a file this did
  not write costs one comparison and turns an unrecoverable loss into a
  rename."
  "% Generated by haselnuss: the packages and declarations this document's body needs.")

(def ^:private preamble-file-instructions
  "The comment header the generated preamble carries under its marker
  (TASK-52), telling the host author the one thing about this file that
  is not obvious from its contents: WHERE to `\\input` it.

  Placement is load-bearing, and getting it wrong fails at compile time
  in the host's own document (found by review, reproduced against a real
  `pdflatex`). `\\documentclass{abntex2}` + `\\usepackage[num]{abntex2cite}`
  + `\\input` of this file dies with `Command \\citetext already defined`,
  because this file loads `natbib` and abntex2cite has already defined
  natbib's own commands; putting the `\\input` FIRST compiles, since
  abntex2cite then finds natbib loaded and adapts to it. The same shape
  with a plain `\\usepackage{natbib}` ahead of it is an option clash,
  for the same reason in a milder form -- this file loads natbib with
  the options the document's own `cslStyle` needs.

  First, therefore, is the rule: LaTeX packages generally tolerate being
  loaded again by a later `\\usepackage` and generally do not tolerate
  the reverse, so the file whose options are derived from the document
  goes ahead of the template's own preamble rather than after it."
  (str "% Do not edit: it is rewritten on every --fragment build.\n"
       "% \\input this as the FIRST line of your own document's preamble, ahead of\n"
       "% your template's own \\usepackage lines. A package loaded here can be\n"
       "% loaded again below with no options; the reverse is an option clash, and a\n"
       "% citation package loaded before natbib is a redefinition error.\n"))

(def ^:private generated-front-matter-marker
  "The first line of a generated front-matter side file (TASK-54),
  serving the same rewrite-vs-clobber purpose the other two markers do."
  "% Generated by haselnuss: one front-matter block, with no environment around it.")

(def ^:private front-matter-file-instructions
  "The comment header a generated front-matter side file carries.

  Says the one thing the file's contents cannot: that the bare prose
  inside is deliberate, and that choosing the environment is the host's
  job. Without it the obvious reading of a file holding two paragraphs
  and a bold keywords line is that something went wrong on the way out."
  ;; Worded without naming an environment as literal LaTeX (no
  ;; "\begin{...}"): a test asserting the side file contains no
  ;; environment reads the whole file, comments included, and this
  ;; header would otherwise be the one thing making that assertion fail.
  (str "% Do not edit: it is rewritten on every --fragment build.\n"
       "% Deliberately bare: no environment is wrapped around this content, because\n"
       "% which one it belongs in is your template's decision, not haselnuss's.\n"
       "% \\input it inside whatever environment your own template uses for it.\n"))

(defn- path-beside
  "The path of `new-name` in the same directory as `path`, spelled the
  same way `path` itself was: relative when `path` is relative, absolute
  when `path` is absolute, and bare -- not resolved against the process's
  working directory -- when `path` has no directory component at all
  (TASK-109).

  That last case is `.getParent` returning nil, which the naive fix
  (`.getAbsoluteFile` first, so a bare name always has a non-nil parent)
  gets wrong on purpose: it makes EVERY bare output absolute, including
  ones nothing asked to be. `output`, and every OTHER file `build` writes
  beside it (a fragment's own preamble, a front-matter side file, both
  through `sibling-path` below), already answer in whatever terms the
  path they were given used; a companion silently switching to an
  absolute one is the bug TASK-109 exists to close, not a case to
  reintroduce here.

  Shared by `sibling-path`, which derives `new-name` from `path`'s own
  base name plus a suffix, and by `build`'s own generated-`.bib` path,
  which derives `new-name` from the document's base name instead -- both
  need the identical answer to \"what directory is this beside `path`\",
  and only that."
  [path new-name]
  (if-let [parent (.getParent (io/file path))]
    (str (io/file parent new-name))
    new-name))

(defn- sibling-path
  "The path of a file `build` writes beside its own output `path`, with
  `suffix` inserted before the output's extension -- so `thesis-body.tex`
  and a suffix of `-preamble` give `thesis-body-preamble.tex` (TASK-52),
  and `-abstract-pt-BR` gives `thesis-body-abstract-pt-BR.tex` (TASK-54).

  Beside the output, and named after it, for the same reason
  `bibtex-resource` names the generated `.bib` after the output rather
  than after `meta.bibliography`: every one of these is that one build's
  own product, and a host `\\input`-ing `thesis-body` wants its
  companions obvious from the name. An extensionless output simply gains
  the suffix, and a dotfile output (`.hidden`) keeps its whole name -- a
  leading dot is the file's name, not an extension, the same distinction
  `output-path` already guards for the same shape.

  The directory `new-name` lands in, and whether that directory is
  spelled relatively, absolutely, or not at all, is `path-beside`'s own
  job, shared with the generated `.bib`'s path in `build` (TASK-109)."
  [path suffix]
  (let [base-name (.getName (io/file path))
        dot (.lastIndexOf base-name ".")
        [stem extension] (if (pos? dot)
                           [(subs base-name 0 dot) (subs base-name dot)]
                           [base-name ""])]
    (path-beside path (str stem suffix extension))))

(defn- front-matter-suffix
  "The `sibling-path` suffix for the front-matter block at position
  `index` in `entries` -- `-abstract-pt-BR`, from the block's own name
  and language.

  Both parts are in the name because both are what a host author is
  choosing between when they write the `\\input` line: which block, and
  which language's version of it. A document carrying two blocks that
  produce the same name gains a 1-based ordinal rather than silently
  overwriting the earlier one.

  The duplicate check runs on the SANITIZED suffix, not on the authored
  `[name lang]` pair (found by review). A language tag reaches a
  filename through a substitution -- anything outside `[A-Za-z0-9._-]`
  becomes `_`, since it is going in a path -- so `pt/BR` and `pt_BR` are
  two different tags that name one file. Deduplicating on the authored
  pair called them distinct, wrote one file twice, and reported the same
  path for both while the first block's content was gone."
  [entries index]
  (let [suffix-of (fn [i]
                    (let [{block-name :name block-lang :lang} (nth entries i)]
                      (str "-" block-name "-"
                           (str/replace (str block-lang) #"[^A-Za-z0-9._-]" "_"))))
        base (suffix-of index)
        same (filterv (fn [i] (= base (suffix-of i))) (range (count entries)))]
    (if (= 1 (count same))
      base
      (str base "-" (inc (.indexOf ^java.util.List same index))))))

(defn- preamble-path
  "Where `build` writes the companion preamble for a `--fragment` LaTeX
  build: the output path with `-preamble` before its extension, so
  `thesis-body.tex` gets `thesis-body-preamble.tex` beside it (TASK-52).

  Beside the fragment, and named after it, for the same reason
  `bibtex-resource` names the generated `.bib` after the output rather
  than after `meta.bibliography`: both files are that one build's own
  output, and a host `\\input`-ing `thesis-body` wants the matching
  preamble to be obvious from the name -- see `sibling-path`, which this
  and the front-matter side files (TASK-54) share."
  [path]
  (sibling-path path "-preamble"))

(defn- bibtex-resource
  "The `\\bibliography{}` argument for the database `build` generates in
  native LaTeX mode: the output file's own base name, so `doc.tex` gets
  `doc.bib` beside it.

  Named after the OUTPUT, not after `meta.bibliography` (TASK-42). A
  document declaring `bibliography: refs.json` would otherwise generate
  `refs.bib` -- and an author who already keeps a hand-maintained
  `refs.bib` beside their JSON, which is exactly the workaround this
  task exists to remove, would have had it silently overwritten by a
  build. `doc.bib` beside a generated `doc.tex` is unmistakably part of
  the build's own output.

  Sanitized, and nil when nothing usable is left. `\\bibliography{}`
  takes a filename BibTeX resolves itself, and BibTeX drops whitespace
  from it -- so `my paper.tex` asked for `mypaper.bib` while the file
  written was `my paper.bib`, and the build exited 0 with an empty
  reference list (found by review). Every character outside
  `[A-Za-z0-9._-]` becomes `_`, and the `.bib` is written under that
  same sanitized name, so the two cannot disagree. nil (no database at
  all, falling back to the resolver's own reference list) when the base
  name is empty, mirroring `latex/bib-resource-name`'s own guard for
  the same shape."
  [written-path]
  (let [base (-> (.getName (io/file written-path))
                 (str/replace #"\.[^.]*$" "")
                 (str/replace #"[^A-Za-z0-9._-]" "_"))]
    (when (seq (str/replace base #"[._-]" "")) base)))

(defn- check-companion!
  "Raises `clobber-type` if `path` already holds a file this namespace did
  not generate -- one whose first line is not `marker`.

  Separate from `write-companion!`, and called for every companion file
  BEFORE any of them is written (found by review): a build that writes
  the `.bib` and then refuses the preamble left the `.bib` on disk while
  reporting a failed build, contradicting `build`'s own \"nothing was
  written\" contract and `--help`'s own exit-code text. Checking first
  makes that contract hold however many companions a build has.

  Replacing a file this DID generate is not a clobber: it carries the
  marker and is rewritten on every build. Refusing anything else is
  recoverable -- the author renames one file -- where an overwrite is
  not. `diagnostics` rides along in the `ex-data` so `-main` can still
  print the warnings collected before the failure."
  [path marker clobber-type diagnostics]
  (when (and (.isFile (io/file path))
             (not (str/starts-with? (slurp (io/file path)) marker)))
    (throw (ex-info (str path " already exists and was not generated by haselnuss"
                         "; move it aside or pass --output to write elsewhere")
                    {:type clobber-type :path path ::diagnostics diagnostics}))))

(defn- refuse-stdout-companion!
  "Raises when `--output -` (TASK-100) would also have to write `path`
  BESIDE the document -- a native-mode `.bib` or a `--fragment` preamble
  -- `description` naming which. A no-op when `path` is nil, so `build`
  can call this unconditionally for every companion it might have
  produced rather than guarding each call site itself.

  `build` calls this for the `.bib` and the preamble only, not for a
  front-matter side file: a front-matter block only exists inside a
  `--fragment` build (`run` gates both by the same flag), and that build
  always has a preamble too (`latex/emit-preamble` is unconditional on
  document content), so the preamble call above always refuses first --
  a third call naming the front-matter file would never be the one that
  throws. See the comment at `build`'s own call site.

  There is no honest way to put more than one file on a single stream,
  so the whole build is refused, naming the file that has nowhere to
  go, rather than writing the document to stdout and silently dropping
  its companions -- the same shape `check-companion!` already gives a
  same-named file it did not generate. `diagnostics` rides along in the
  `ex-data` for the same reason it does there: `-main` still prints the
  warnings collected before this failure."
  [path description diagnostics]
  (when path
    (throw (ex-info (str path " has nowhere to go: --output - writes the document to a single stream, and"
                         " this build also generates " description
                         "; pass --output to a real path instead")
                    {:type ::stdout-companion-refused :path path ::diagnostics diagnostics}))))

(defn- write-companion!
  "Writes the file `build` generates BESIDE its own output -- the
  native-mode `.bib` (TASK-42) or a fragment's companion preamble
  (TASK-52) -- to `path`.

  `content` is a THUNK, not a string, and deliberately so (found by
  review): building the `.bib` text means running
  `haselnuss.emit.latex/csl-json->bibtex` over the loaded bibliography,
  which can throw, and that has to land in the same `unwritable-type`
  `ex-info` carrying `::diagnostics` as a failed write does. Passing an
  already-built string moved that work outside the `try` and dropped
  every collected warning on the way to `main*`'s generic handler.

  `description` names the file kind in the error message. The clobber
  check is `check-companion!`, run earlier for all companions at once."
  [path {:keys [content description unwritable-type diagnostics]}]
  (try
    (spit path (content))
    (catch Exception e
      (throw (ex-info (str "cannot write the generated " description " " path ": " (ex-message e))
                      {:type unwritable-type :path path ::diagnostics diagnostics}
                      e)))))

(defn- write-in-place!
  "Writes `content` to `path` -- an INPUT file `build` is about to
  overwrite under `--in-place` (TASK-101) -- without ever exposing a
  half-written version of it: a fresh temp file, in the SAME directory
  as `path` (so the move below can never cross a filesystem boundary,
  which would silently turn `ATOMIC_MOVE` into a copy-then-delete, not
  atomic at all), receives the WHOLE text first; only once that write has
  fully succeeded is it moved onto `path` with
  `java.nio.file.StandardCopyOption/ATOMIC_MOVE`, which a POSIX
  filesystem performs via a single `rename(2)` call -- so `path` is,
  at every instant another process could observe it, either every byte
  of the OLD document or every byte of the NEW one, never a mix of the
  two and never truncated.

  `content` IS a plain string here, unlike `write-companion!`'s own
  (deliberately a thunk there): building it can throw -- it runs
  `haselnuss.emit.latex/csl-json->bibtex` over loaded CSL-JSON -- so that
  function defers the call inside its own `try`. Nothing analogous is
  left to defer here: `build` calls this only after `run` has already
  produced `output` in full, so the only failures left to guard are I/O
  ones (a full disk writing the temp file, a permissions problem moving
  it) -- and either one deletes the temp file and rethrows, leaving
  `path` itself untouched throughout: this function never opens `path`
  for writing directly, only ever as the target of one atomic move.

  Permissions matter too, not just bytes (found by review): the JDK
  deliberately creates a fresh `createTempFile` with restrictive,
  owner-only permissions, and `ATOMIC_MOVE` carries the TEMP file's own
  mode onto `path`, not `path`'s original mode -- so without this, a
  644 input would silently become 600 on every successful `--in-place`
  build. To avoid that, the temp file is created WITH `path`'s current
  POSIX permissions as a `FileAttribute` up front (`createTempFile`
  itself, not a follow-up `setPosixFilePermissions`, so there is never a
  moment where the temp file exists with the JDK's own restrictive
  default) whenever `path` is on a POSIX filesystem --
  `getPosixFilePermissions` throwing `UnsupportedOperationException` is
  the documented signal that it is not (irrelevant to this project's own
  Linux/macOS toolchain, but cheap to fall back on rather than let this
  helper hard-fail on a platform the rest of the codebase never claimed
  to support), in which case the temp file falls back to the JDK's own
  default attributes exactly as before."
  [path content]
  (let [target (.toPath (.getAbsoluteFile (io/file path)))
        dir (.getParent target)
        attrs (try
                (into-array
                 java.nio.file.attribute.FileAttribute
                 [(java.nio.file.attribute.PosixFilePermissions/asFileAttribute
                   (java.nio.file.Files/getPosixFilePermissions
                    target (make-array java.nio.file.LinkOption 0)))])
                (catch UnsupportedOperationException _
                  (make-array java.nio.file.attribute.FileAttribute 0)))
        temp (java.nio.file.Files/createTempFile
              dir "haselnuss-in-place-" ".tmp" attrs)]
    (try
      (spit (.toFile temp) content)
      (java.nio.file.Files/move
       temp target
       (into-array java.nio.file.CopyOption [java.nio.file.StandardCopyOption/ATOMIC_MOVE]))
      (catch Exception e
        (java.nio.file.Files/deleteIfExists temp)
        (throw e)))))

(defn- build-file
  "`run` over the file named by `opts`' own `:input`, writing the result
  to `output-path`. Returns `{:output-path :diagnostics :bibtex-path
  :preamble-path :output}`, `:output-path` being the keyword `:stdout`
  rather than a string when `opts`' own `:output` asked for one
  (TASK-100), and `:bibtex-path`/`:preamble-path` being nil unless a
  BibTeX database or a fragment's companion preamble was generated
  alongside. The output is written only on success -- an exception from
  any pass propagates with nothing written, so a failed build never
  leaves a half-converted document behind for a `make`-style tool to
  mistake for a good one (AC #5).

  Native-mode LaTeX writes a SECOND file (TASK-42): `<output>.bib`,
  generated by `haselnuss.emit.latex/csl-json->bibtex` from the CSL-JSON
  the resolver already loaded, which is what makes the reference list
  work with no hand-maintained database. That is a deliberate change to
  this function's contract -- one input used to mean one output -- and
  it is the smallest honest way to close the seam: the alternative,
  emitting `\\bibliography{refs}` at a `refs.bib` nothing produces, exits
  0 while every citation in the PDF prints `?`.

  Two modes write nothing extra, because neither needs it: HTML, and
  `--computed-numbers` LaTeX, which bakes the resolver's own reference
  list into the `.tex`.

  A `--fragment` LaTeX build writes its own extra file for a related
  reason (TASK-52): `<output>-preamble.tex` (`preamble-path`), holding
  the `\\usepackage` lines and declarations the emitted body needs. A
  fragment cannot load a package for itself, so without this the host
  author is left to infer the dependency list from the body -- and the
  first missing one is a compile error in someone else's document. Both
  extra files are guarded against clobbering a same-named file this did
  not write; see `generated-bibtex-marker`/`generated-preamble-marker`.

  Every extra file is written BEFORE the `.tex`, so a failure to write
  one (a read-only directory, say) aborts the build naming that file
  rather than leaving a `.tex` pointing confidently at a database or a
  preamble that is not there -- which is the silently-empty reference
  list this task set out to eliminate, in another spelling.

  `--output -` (TASK-100) writes the document to stdout instead, and has
  no path a companion could sit beside; a build that would have written
  one is refused entirely, naming it, by `refuse-stdout-companion!`,
  called BEFORE any of the checks above -- there is nothing to check a
  clobber against when nothing is going to be written in the first
  place. A front-matter block never triggers a refusal of its own: it
  only exists inside a `--fragment` build, which always has a companion
  preamble too, so that refusal -- naming the preamble, not the
  front-matter file -- always fires first; see the comment at this
  function's own `refuse-stdout-companion!` call site.

  `--in-place` (TASK-101) writes the very file `input` names, in place
  of an ordinary derived/explicit `path` -- `output-path` itself is what
  makes `path` equal `input` under this flag, and lifts the overwrite-
  input guard that would otherwise refuse it (see that function's own
  docstring) -- through `write-in-place!` rather than `spit`, so the
  document this function just read `source` from is never truncated by
  its own replacement: see that function's own docstring for the
  temp-file-plus-atomic-move this buys. Since `output-path` also refuses
  `--in-place` together with `--output` or any `--target` other than
  `hdoc`, no native-mode `.bib`, `--fragment` preamble or front-matter
  side file can ever coexist with it -- those are all LaTeX-only, and
  `--in-place` forces `hdoc`."
  [{:keys [input in-place] :as opts}]
  (let [input-file (io/file input)]
    (cond
      ;; TASK-113: checked ahead of the plain `.isFile` test below so a
      ;; directory gets its own true message instead of falling into
      ;; "not found" -- the path IS there, it is just not a file, the
      ;; same distinction `haselnuss.resolver/expand-includes` already
      ;; makes for `!include` one layer down (its own "cannot include
      ;; ... (Is a directory)", surfaced via a real `IOException`
      ;; `slurp` throws for a directory). This check exists BEFORE
      ;; anything reads the file, so it has no such exception to read
      ;; the wording off; the message is spelled out directly instead.
      (.isDirectory input-file)
      (throw (ex-info (str "input path is a directory: " input) {:type ::input-error}))
      ;; Neither a directory nor a plain file: doesn't exist (the
      ;; ordinary case), or is some other kind of filesystem entry this
      ;; tool has no more specific word for. A file that DOES exist but
      ;; cannot be READ (e.g. mode 000) is a plain file as far as
      ;; `.isFile` is concerned, so it is not caught here at all -- it
      ;; falls through to `slurp` below, which throws its own
      ;; `IOException` ("... (Permission denied)"), left uncaught and
      ;; reported by `main*`'s generic handler instead (AC #3).
      (not (.isFile input-file))
      ;; TASK-138: an input problem, not a command-line one -- exit 1
      ;; like the unreadable-file case, and no usage text.
      (throw (ex-info (str "input file not found: " input) {:type ::input-error}))))
  (let [opts (assoc opts :base-dir (.getParent (.getAbsoluteFile (io/file input))))
        source (slurp input)
        ;; The same declared-or-overridden bibliography `run` itself
        ;; loads (TASK-81), read here too so `output-path` can refuse a
        ;; collision -- see its own docstring. Reading front matter only
        ;; (not the whole document) is what keeps this cheap enough to
        ;; do before the "chosen before converting anything" check below.
        bib-path (bibliography-path opts (:bibliography (parser/front-matter source (when (:input opts) {:source (:input opts)}))))
        ;; Chosen before converting anything, so a bad destination is
        ;; reported without first doing the whole build's work.
        path (output-path opts bib-path)
        ;; TASK-100: `path` is the keyword `:stdout`, not a path on disk,
        ;; when `--output -` was given. A companion is still named after
        ;; what the OUTPUT would have been called, for a readable refusal
        ;; ("doc.bib has nowhere to go") rather than one derived from a
        ;; sentinel ("--bib has nowhere to go") -- `derived-output-path`
        ;; gives that name the same way `output-path` itself would have,
        ;; had `--output` not been given at all.
        nominal-path (if (= :stdout path) (default-output-path opts) path)
        native-latex? (and (= :latex (:target (targets (:target opts))))
                           (not (:computed-numbers opts)))
        resource (when native-latex? (bibtex-resource nominal-path))
        {:keys [output diagnostics bibliography ordered-keys preamble front-matter]}
        (run source (cond-> opts resource (assoc :bib-resource resource)))
        ;; The condition is that the emitted .tex actually asks for this
        ;; database, read off the output itself rather than re-derived
        ;; from the same inputs. A document that declares a bibliography
        ;; but cites nothing emits no \bibliography{} at all, and would
        ;; otherwise have got an unused file written beside it.
        ;;
        ;; TASK-109: `path-beside`, not a hand-rolled `getParentFile` off
        ;; `getAbsoluteFile`, so the announced path stays whatever kind
        ;; `nominal-path` itself is -- relative beside a relative output,
        ;; bare beside a bare one -- the same way `preamble-file`/
        ;; `front-matter-files` below already answer through
        ;; `sibling-path`, rather than the odd one out among this build's
        ;; own companions.
        bibtex-path (when (and resource
                               (str/includes? output (str "\\bibliography{" resource "}")))
                      (path-beside nominal-path (str resource ".bib")))
        ;; Conditioned on `run` having produced one at all, which is
        ;; exactly "this was a --fragment LaTeX build" -- so the two
        ;; cannot disagree about whether the fragment has a companion.
        preamble-file (when preamble (preamble-path nominal-path))
        ;; TASK-54: one side file per front-matter block, paired with the
        ;; content that goes in it so the two cannot get out of step.
        front-matter-files (mapv (fn [index]
                                   [(sibling-path nominal-path (front-matter-suffix front-matter index))
                                    (:content (nth front-matter index))])
                                 (range (count front-matter)))
        ;; TASK-42: computed once rather than inside the writer's own
        ;; thunk, because the same text is also what TASK-76's
        ;; diagnostic scans -- generating it twice would be two chances
        ;; for the scanned bytes and the written ones to differ.
        bibtex-content (when bibtex-path
                         (str generated-bibtex-marker "\n"
                              "% Do not edit: it is rewritten on every native-mode build.\n\n"
                              (latex/csl-json->bibtex bibliography ordered-keys)
                              "\n"))
        ;; TASK-76: the generated database is the origin worth naming
        ;; most, since it is written from CSL-JSON the author never
        ;; opens -- a warning pointing at the document would send them
        ;; looking in the wrong file.
        diagnostics (cond-> diagnostics
                      bibtex-content
                      (into (latex/untypesettable-character-diagnostics
                             bibtex-content :bibliography))
                      ;; TASK-219 review: a javascript: URL the .bib
                      ;; leaves out, which no other pass sees in native
                      ;; mode, is warned about like a dropped link.
                      bibtex-content
                      (into (latex/bibtex-address-diagnostics bibliography ordered-keys)))
        ;; A fragment carrying its own reference list is a warning, not a
        ;; silent success (found by review): LaTeX allows exactly one
        ;; \bibliography per document, so a host template that keeps its
        ;; own gets "Illegal, another \bibdata command" from BibTeX, its
        ;; database dropped and every citation in the thesis unresolved.
        ;; The fragment still emits it -- dropping the document's own
        ;; reference list to avoid a collision would lose authored
        ;; content silently, which is worse -- so the build says which
        ;; line in the host has to go instead.
        diagnostics (cond-> diagnostics
                      (and preamble-file bibtex-path)
                      (conj {:type :fragment-bibliography
                             :message (str "this fragment emits its own \\bibliography{" resource
                                           "}; a LaTeX document may have only one, so remove the"
                                           " host document's own \\bibliography command or BibTeX"
                                           " will drop one of the two databases")}))]
    ;; TASK-100: checked before anything else -- `check-companion!`
    ;; below reads each companion off DISK, which `--output -` gives it
    ;; nothing to do, and refusing here first is what keeps this build
    ;; from writing the document to stdout and only THEN discovering a
    ;; companion has nowhere to go.
    ;;
    ;; No third call for `front-matter-files` (found by review, TASK-100):
    ;; it would be unreachable. `run` gates `:front-matter` and `:preamble`
    ;; by the exact same `fragment-latex?` (see the map built there), and
    ;; `latex/emit-preamble` is a FIXED preamble -- every package it loads
    ;; is unconditional on document content (its own docstring; verified
    ;; at the REPL) -- so it never returns nil/blank. That makes
    ;; `preamble-file` truthy every time `front-matter-files` could be
    ;; non-empty, which means the `preamble-file` refusal just above
    ;; always throws first for that combination; a build with a
    ;; front-matter block therefore still writes nothing and is still
    ;; refused, naming the preamble it also needed. If `run` ever stops
    ;; deriving the two from the same flag, or `emit-preamble` stops being
    ;; unconditional, this comment is the trip wire for bringing the
    ;; front-matter-specific call back.
    (when (= :stdout path)
      (refuse-stdout-companion! bibtex-path "a BibTeX database" diagnostics)
      (refuse-stdout-companion! preamble-file "a fragment's companion preamble" diagnostics))
    ;; Every companion is checked before ANY of them is written, so a
    ;; refused second file cannot leave a written first one behind
    ;; (found by review). `<output>.bib` is also a hand-maintained
    ;; convention (pandoc's own `paper.md` + `paper.bib`), so a first
    ;; build must not silently eat one.
    (when bibtex-path
      (check-companion! bibtex-path generated-bibtex-marker ::bibtex-would-clobber diagnostics))
    (when preamble-file
      (check-companion! preamble-file generated-preamble-marker
                        ::preamble-would-clobber diagnostics))
    (doseq [[file _] front-matter-files]
      (check-companion! file generated-front-matter-marker
                        ::front-matter-would-clobber diagnostics))
    (when bibtex-path
      (write-companion! bibtex-path
                        {:description "BibTeX database"
                         :unwritable-type ::unwritable-bibtex
                         :diagnostics diagnostics
                         :content (constantly bibtex-content)}))
    (when preamble-file
      (write-companion! preamble-file
                        {:description "preamble"
                         :unwritable-type ::unwritable-preamble
                         :diagnostics diagnostics
                         :content #(str generated-preamble-marker "\n"
                                        preamble-file-instructions "\n"
                                        preamble)}))
    (doseq [[file content] front-matter-files]
      (write-companion! file
                        {:description "front-matter block"
                         :unwritable-type ::unwritable-front-matter
                         :diagnostics diagnostics
                         :content #(str generated-front-matter-marker "\n"
                                        front-matter-file-instructions "\n"
                                        content)}))
    ;; TASK-100: `path` is never `:stdout` here with any companion left
    ;; to drop -- the refusal above already threw for that combination --
    ;; so writing the document itself to `*out*` instead of `spit`-ing it
    ;; is the only difference that branch makes. TASK-101: `path` IS
    ;; `input` under `--in-place` (`output-path`'s own doing), so that
    ;; write goes through `write-in-place!`'s temp-file-plus-atomic-move
    ;; instead of an ordinary `spit`, which would truncate the very file
    ;; this function just finished reading `source` from before writing
    ;; a single byte of the replacement.
    (cond
      (= :stdout path) (print output)
      in-place (write-in-place! path output)
      :else (spit path output))
    {:output-path path :diagnostics diagnostics :bibtex-path bibtex-path
     :preamble-path preamble-file
     :front-matter-paths (mapv first front-matter-files)
     :output output}))

(defn build
  "Builds `opts`' own `:input` and writes the result: `build-file` (whose
  docstring has the whole contract), after turning a BOOK-ID input
  (TASK-157, doc-10 \"CLI\": the argument parses as a book reference and
  no file or folder of that name exists) into its book's own `.hdoc`
  file, written by default to `<slug>.<ext>` in the current directory --
  see `book-input-opts`, which also throws the hard errors a book that
  cannot be found is."
  [opts]
  (let [opts (if-let [parsed (book-id-input (:input opts))]
               (book-input-opts opts parsed)
               opts)]
    (try
      (build-file opts)
      (catch clojure.lang.ExceptionInfo e
        ;; A BOOK-ID input's directory problems were found before
        ;; anything here ran, so they are still worth printing when the
        ;; build then stops on an error that carries no diagnostics of
        ;; its own (a missing bibliography, a refused output path).
        ;; One that does carry them already leads with these: `run`
        ;; puts them at the head of every list it builds.
        (if (and (seq (::directory-diagnostics opts)) (nil? (::diagnostics (ex-data e))))
          (throw (ex-info (ex-message e)
                          (assoc (ex-data e) ::diagnostics (::directory-diagnostics opts))
                          e))
          (throw e))))))

(defn- warn!
  "Prints one resolver diagnostic to stderr as a warning (AC #4)."
  [diagnostic]
  (binding [*out* *err*]
    (println (str "haselnuss: warning: " (:message diagnostic)))))

(def ^:private error-detail-keys
  "The `ex-data` keys worth showing alongside an unrecoverable error's
  own message, in the order they are printed. Every pass in this
  pipeline already puts the offending thing in its `ex-data` but names
  it only generically in the message -- `haselnuss.parser`'s
  \"unsupported inline construct\" carries the source `:text`,
  `haselnuss.lower`'s `::no-representation` carries the directive
  `:name`/`:id`, `haselnuss.emit.html`/`latex`'s `::unresolved-include`
  carries the `:src` -- so without this an author is told *that* the
  build failed but never *where*. Deliberately a fixed allow-list rather
  than dumping all of `ex-data`: `:block`/`:inline`/`:node` hold whole
  AST subtrees, which would bury the message they are meant to
  clarify."
  [:text :name :id :src :target :rule :token])

(defn- error-detail
  "The ` (key value, ...)` suffix `fail!` appends for an `ex-info`'s own
  `ex-data`, or `\"\"` when it carries none of `error-detail-keys`."
  [data]
  (let [pairs (keep (fn [k] (when-some [v (get data k)] (str (name k) " " (pr-str v))))
                    error-detail-keys)]
    (if (seq pairs) (str " (" (str/join ", " pairs) ")") "")))

(defn- fail!
  "Prints `message` to stderr as an unrecoverable error (AC #5), followed
  by whatever `error-detail` can pull out of `data` to say where."
  ([message] (fail! message nil))
  ([message data]
   (binding [*out* *err*]
     (println (str "haselnuss: error: " message (error-detail data))))))

(defn main*
  "The whole command-line behavior except the process exit itself:
  parses `args`, runs `build`, prints every diagnostic to stderr, and
  *returns* the status code -- 0 on success, `--help` or `--version`, 2
  for a usage error, 1 for anything else (AC #5).

  Separate from `-main` so a test can assert the real thing: `-main`
  calls `System/exit`, which would kill the test JVM and which
  `with-redefs` cannot stub (it is a static method). Without this split
  the stderr wording, the warnings-before-error ordering and the status
  mapping -- all of which AC #4/#5 are literally about -- could only be
  approximated by asserting on data structures instead.

  `overrides` (TASK-157) is merged over the parsed options: never a
  command-line option, it is how a test passes `:library-env` and
  `:library-home` so that it never reads the real `$HASELNUSS_LIBRARIES`
  or `~/.config/haselnuss/libraries.yaml`."
  ([args] (main* args nil))
  ([args overrides]
   (try
     (let [opts (merge (parse-args args) overrides)]
       (cond
         (:help opts) (do (println usage) 0)
         (:version opts) (do (println (str "haselnuss " @version)) 0)
        ;; TASK-157: the listing is the document here, so it goes to
        ;; stdout; the library's problems go to stderr like any warning.
         (:list-books opts)
         (let [{:keys [books diagnostics]} (list-books opts (:list-books opts))]
           (run! warn! diagnostics)
           (run! (fn [[id title]] (println (str id "\t" title))) books)
           0)
         :else
         (let [{:keys [diagnostics bibtex-path front-matter-paths]
                written :output-path preamble-file :preamble-path}
               (build opts)]
           (run! warn! diagnostics)
          ;; TASK-100: every "wrote" line goes to stderr, not stdout --
          ;; unconditionally, not only under --output -, since the two
          ;; streams are fixed for the whole process and a line here
          ;; would land in the middle of a document `build` already put
          ;; on stdout for that case. `written` prints as "-", not
          ;; ":stdout", the same token `--output -` was given on the
          ;; command line, and the one `derived-output-path`'s own
          ;; docstring points at for why a companion refusal does not
          ;; reuse it as a name.
           (binding [*out* *err*]
             (println (str "haselnuss: wrote " (if (= :stdout written) "-" written)))
            ;; Reported on its own line, not folded into the one above:
            ;; native-mode LaTeX writes a second file (TASK-42), a
            ;; --fragment build writes its companion preamble (TASK-52),
            ;; and a build that quietly produced a file the user did not
            ;; ask for is exactly the kind of surprise a converter should
            ;; not have.
             (when bibtex-path (println (str "haselnuss: wrote " bibtex-path)))
             (when preamble-file (println (str "haselnuss: wrote " preamble-file)))
             (run! (fn [file] (println (str "haselnuss: wrote " file))) front-matter-paths))
           0)))
     (catch clojure.lang.ExceptionInfo e
      ;; Warnings first, then the error that stopped the build -- the
      ;; order `pdflatex` itself reports in, and the order that lets a
      ;; warning explain the error (see `run`).
       (run! warn! (::diagnostics (ex-data e)))
       (fail! (ex-message e) (ex-data e))
       (if (= ::usage-error (:type (ex-data e)))
        ;; TASK-138: one pointer line, not the whole usage text,
        ;; which buried the one line the user needed.
         (do (binding [*out* *err*]
               (println "Try 'haselnuss --help' for usage."))
             2)
         1))
     (catch Exception e
       (fail! (or (ex-message e) (str e)))
       1))))

(defn -main
  "The process entry point: `main*`, then exit with the status it
  returned."
  [& args]
  (let [status (main* args)]
    (flush)
    (System/exit status)))

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
  here (TASK-44 AC #4). Not an oversight, and not deferred work waiting
  on a flag: an authored `.hdoc` cannot declare a `:fallback` of any
  kind, because `haselnuss.parser` constructs no `:fallback` field at
  all (see `haselnuss.lower`'s own docstring on being parser-inert), so
  a `:transform` fallback is unreachable from a document this namespace
  can be handed. A CLI option would be plumbing to a place no input can
  get to -- and it would have to mean \"load and evaluate Clojure from
  a path\", since a transform rule is a function, which is a large step
  for a document converter to take for a feature no document can use.
  The option becomes worth adding the day authored fallbacks do; until
  then, an API caller reaches `lower` directly, which is how
  `haselnuss.degradation-test` covers the variant.

  Diagnostics vs errors (AC #4/#5) follow the split
  `haselnuss.resolver` and `haselnuss.lower` already drew between them:
  a resolver diagnostic is a *warning* -- a dangling reference or
  citation, a duplicate id, an unknown directive, an id-prefix/role
  mismatch, a directive whose id prefix names a kind other than its own,
  a section whose id prefix names a division other than the one its
  level is emitted as -- printed to stderr, and the build still produces its output
  file and exits 0, exactly as `pdflatex` finishes a document with
  undefined references. An `ex-info` from any pass -- `lower`'s
  `::no-representation` for a directive with no representation and no
  fallback, an emitter's `::unsupported-block`/`::unresolved-include`, a
  malformed directive from the parser -- is unrecoverable: nothing is
  written, the message goes to stderr, and the exit code is non-zero."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [haselnuss.emit.html :as html]
            [haselnuss.emit.latex :as latex]
            [haselnuss.extensions.collapsable :as collapsable]
            [haselnuss.extensions.derived-lists :as derived-lists]
            [haselnuss.extensions.front-matter :as front-matter]
            [haselnuss.extensions.small-collapsable :as small-collapsable]
            [haselnuss.json :as json]
            [haselnuss.lower :as lower]
            [haselnuss.parser :as parser]
            [haselnuss.registry :as registry]
            [haselnuss.resolver :as resolver])
  (:gen-class))

(def targets
  "The emission targets this CLI supports, each mapping to the file
  extension `output-path` gives its output when none was requested.

  `json` (TASK-80) is not a rendering target like the other two: it
  writes the parsed Document's own JSON interchange representation
  (`haselnuss.json/->json`, SPEC.md sec11), with no resolve or lower
  pass run over it at all -- see `run`'s own `emit-target` branch. Its
  prose is written more compactly than `haselnuss.json`'s own faithful
  encoding would: consecutive word/space Inlines (CommonMark's own
  per-word tokenization) are folded into single `:str` runs before
  encoding -- see `coalesce-prose`."
  {"html" {:target :html :extension ".html"}
   "latex" {:target :latex :extension ".tex"}
   "json" {:target :json :extension ".json"}})

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
        ;; The directive's own name joins its classes, so the degraded
        ;; node is still identifiable as what it was -- an admonition
        ;; with no head (see `environment-title`) would otherwise be
        ;; byte-identical to a plain markdown quote, and cross-format
        ;; parity bought by erasing the only marker is not parity worth
        ;; having (TASK-29 review).
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
               ;; TASK-59: and the same for the three list
               ;; placeholders. Both emitters draw a list themselves --
               ;; from the resolver's own derivations, or by handing
               ;; the job to LaTeX's counters in native mode -- so what
               ;; is registered here is a marker too; registering it is
               ;; what keeps `lower` from aborting on a placeholder,
               ;; which carries no fallback of its own either.
               (registry/register-all derived-lists/extensions)
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
    ""
    "Converts a Haselnuss document to HTML, LaTeX, or its own JSON interchange"
    "representation."
    ""
    "Options:"
    "  -t, --target TARGET     html (default), latex, or json"
    "  -o, --output PATH       output file (default: INPUT with the target's extension)"
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
    "      --no-stylesheet     html only: omit the default stylesheet the emitter"
    "                          otherwise inlines, for embedding the output in a page"
    "                          that brings its own CSS"
    "  -h, --help              show this help"
    "      --version           show the version of this build and exit"
    ""
    "The json target writes the parsed document's own JSON representation --"
    "no resolve or lower pass runs over it, so it carries no computed numbers,"
    "cross-reference text or bibliography, and --computed-numbers, --fragment"
    "and --no-stylesheet have no effect on it. Prose is written compactly:"
    "consecutive word/space tokens are folded into plain :str text runs."
    ""
    "Resolver diagnostics (dangling references and citations, duplicate ids,"
    "unknown directives, id-prefix/role mismatches, directives whose id prefix"
    "names a different kind than the directive does, sections whose id prefix"
    "names a different division than their own level is emitted as) are printed"
    "to stderr as warnings on the html/latex targets; the build still succeeds."
    ""
    "Exit codes:"
    "  0  the document was converted and written"
    "  1  the build failed (no representation for the target, a parse error,"
    "     an unreadable file); nothing was written"
    "  2  the command line was wrong; this usage text is printed"]))

(defn- parse-args
  "Parses `args` into `{:input :target :output :computed-numbers
  :bibliography :help :version :no-stylesheet :fragment}`, or throws
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
  silently setting the target to `\"-o\"`."
  [args]
  (loop [[arg & more :as remaining] args
         opts {:target "html"}
         literal? false]
    (let [[arg inline-value] (if (and (not literal?) (str/starts-with? (str arg) "--")
                                      (str/includes? (str arg) "="))
                               (str/split arg #"=" 2)
                               [arg nil])
          _ (when (and inline-value
                       (contains? #{"--help" "--version" "--computed-numbers"
                                    "--no-stylesheet" "--fragment"}
                                  arg))
              ;; A value on a flag that takes none must be an error, not
              ;; silently discarded: `--computed-numbers=false` turned
              ;; the flag ON (found by review).
              (throw (ex-info (str "option " arg " takes no value") {:type ::usage-error})))
          take-value (fn [flag]
                       (cond
                         inline-value [inline-value more]
                         (and (seq more) (not (str/starts-with? (first more) "-")))
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
          (nil? (:input opts)) (throw (ex-info "no input file given" {:type ::usage-error}))
          (nil? (targets (:target opts)))
          (throw (ex-info (str "unknown target " (pr-str (:target opts))
                               " (expected one of: " (str/join ", " (sort (keys targets))) ")")
                          {:type ::usage-error}))
          :else opts)

        (and (not literal?) (= "--" arg)) (recur more opts true)

        (and (not literal?) (contains? #{"-h" "--help"} arg))
        (recur more (assoc opts :help true) literal?)

        (and (not literal?) (= "--version" arg))
        (recur more (assoc opts :version true) literal?)

        (and (not literal?) (contains? #{"-t" "--target"} arg))
        (let [[value rest-args] (take-value arg)]
          (recur rest-args (assoc opts :target value) literal?))

        (and (not literal?) (contains? #{"-o" "--output"} arg))
        (let [[value rest-args] (take-value arg)]
          (recur rest-args (assoc opts :output value) literal?))

        (and (not literal?) (= "--bibliography" arg))
        (let [[value rest-args] (take-value arg)]
          (recur rest-args (assoc opts :bibliography value) literal?))

        (and (not literal?) (= "--computed-numbers" arg))
        (recur more (assoc opts :computed-numbers true) literal?)

        (and (not literal?) (= "--no-stylesheet" arg))
        (recur more (assoc opts :no-stylesheet true) literal?)

        (and (not literal?) (= "--fragment" arg))
        (recur more (assoc opts :fragment true) literal?)

        (and (not literal?) (str/starts-with? arg "-"))
        (throw (ex-info (str "unknown option " (pr-str arg)) {:type ::usage-error}))

        (:input opts)
        (throw (ex-info (str "unexpected extra argument " (pr-str arg)
                             " (only one input file is accepted)")
                        {:type ::usage-error}))

        :else (recur more (assoc opts :input arg) literal?)))))

(defn- output-path
  "The path to write to: `opts`' own `:output` when given, else `:input`
  with its extension replaced by the target's own (`targets`). An input
  with no extension simply gains one.

  Stripping is guarded against a dotfile (`.hidden`), where the regex
  would otherwise consume the whole name and write a hidden `.html` in
  the *current directory* instead of beside the input -- the same
  empty-after-stripping trap `haselnuss.emit.latex/bib-resource-name`
  already guards (its own TASK-23 review finding). The chosen path --
  derived or explicit -- is then checked against the input, so a build
  can never overwrite the document it is reading.

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
  would be one more thing to keep in sync with `targets`."
  [{:keys [input output target]} bib-path]
  (let [file (io/file input)
        path (or output
                 (let [parent (.getParent file)
                       base-name (.getName file)
                       ;; Stripped from the BASE NAME, never the whole
                       ;; path: doing it on the path turns ".hidden" into
                       ;; the directory itself, and the result lands as a
                       ;; stray ".html" in that directory rather than
                       ;; beside the input.
                       stripped (str/replace base-name #"\.[^.]*$" "")
                       new-name (str (if (str/blank? stripped) base-name stripped)
                                     (:extension (targets target)))]
                   (if parent (str (io/file parent new-name)) new-name)))]
    ;; Checked on the FINAL path, whichever branch chose it. Guarding
    ;; only the derived one left `-o victim.hdoc victim.hdoc` destroying
    ;; its own source -- while the message told the user to pass exactly
    ;; that flag (found by review).
    (when (= (.getAbsolutePath (io/file path)) (.getAbsolutePath file))
      (throw (ex-info (str "refusing to overwrite the input file: " input)
                      {:type ::usage-error})))
    (when (and bib-path (= (.getAbsolutePath (io/file path)) (.getAbsolutePath (io/file bib-path))))
      (throw (ex-info (str "refusing to overwrite the bibliography file: " bib-path
                           " -- pass --output to write the converted document elsewhere")
                      {:type ::usage-error})))
    path))

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
  stopping."
  [path]
  (when path
    (when-not (.isFile (io/file path))
      (throw (ex-info (str "bibliography file not found: " path) {:type ::missing-bibliography})))
    (resolver/load-bibliography path)))

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

(defn run
  "The pure core (AC #1/#2/#3): parses `source`, resolves it, lowers it
  for `opts`' own target, and emits it. Returns `{:output :diagnostics
  :bibliography :ordered-keys :preamble}` -- the emitted document text,
  every resolver warning in pass order (AC #4), the loaded bibliography
  plus the keys the document cites, which `build` needs to generate the
  BibTeX database native mode asks for (TASK-42), and (only for a
  `--fragment` LaTeX build, TASK-52) the companion preamble text the
  emitted body's host has to load. Reads nothing but the
  bibliography the document itself names (see `bibliography-path`), and
  writes nothing: a caller decides what to do with the result.

  `opts` additions beyond the CLI's own flags: `:bib-resource`, the
  `\\bibliography{}` argument native mode should name. `build` passes
  the database it is about to write; a caller invoking `run` DIRECTLY
  gets the emitter's own default instead (`meta.bibliography` with its
  extension stripped), which names a `.bib` nothing here produces --
  such a caller is writing the files itself and owns that file too.

  Throws whatever `parse`/`lower`/`emit-document` throw. That is the
  unrecoverable half of the split this namespace's docstring describes
  (AC #5); `build`/`-main` turn it into a message and an exit code.

  The `:json` target (TASK-80) is a deliberate exception to all of the
  above: it returns right after `parser/parse`, with `:output` the
  parsed Document's own JSON interchange representation
  (`haselnuss.json/->json`, through `coalesce-prose` -- see its own
  docstring) and empty `:diagnostics` -- no `resolve-document`, no
  `build-registry`, no `lower`, no emitter runs at all. It still needs
  `vocabulary` (so a bare `@key` parses as the same construct it would on
  any other target), and therefore the same bibliography load `bib-path`/
  `bibliography` do, but nothing past `parser/parse` -- resolution is
  what would turn a `CrossRef`'s bare `:label` into resolved `:text`, and
  the JSON target's whole point is the document exactly as parsed, not
  that computed view of it."
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
        ;; where it used to report the parse error.
        bib-path (bibliography-path opts (:bibliography (parser/front-matter source)))
        bibliography (load-bibliography! bib-path)
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
        vocabulary (when (or (not= emit-target :latex) computed-numbers)
                     ;; `:cite-keys` is the empty set, not nil, when there
                     ;; is no bibliography at all: such a document has no
                     ;; bare citation that could resolve, and it is
                     ;; exactly where "cc @someone" gets written. nil
                     ;; would hand those documents back to the heuristic.
                     ;; A bracketed `[@key]` is unaffected either way --
                     ;; explicit syntax, and it still warns when it
                     ;; dangles.
                     {:kinds (into #{} (map name) (keys lexicon))
                      :cite-keys (set (keys bibliography))})
        document (parser/parse source vocabulary)]
    (if (= emit-target :json)
      ;; TASK-80: the JSON target is the raw parsed AST, not a
      ;; resolved/lowered view -- SPEC.md sec11 treats the JSON
      ;; representation as the canonical interchange form derived
      ;; straight from parsing, so no resolver diagnostic, directive
      ;; registry, or lowering pass applies here at all. `coalesce-prose`
      ;; is the one exception to "exactly as parsed": it folds CommonMark's
      ;; own per-word :str/:space tokenization into plain-text :str runs,
      ;; purely for a more compact dump -- see its own docstring.
      {:diagnostics []
       :bibliography bibliography
       :ordered-keys nil
       :preamble nil
       :front-matter nil
       :output (json/->json (coalesce-prose document))}
      (let [{:keys [document diagnostics labels ordered-keys bibliography-id]}
            (resolver/resolve-document document
                                       (cond-> {:lexicon lexicon
                                                :directive-registry base-registry
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
                                                         (parser/parse (slurp file) vocabulary))}}
                                         bibliography (assoc :bibliography bibliography)))
            build-reg (build-registry labels)
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
            lowered (try
                      (lower/lower document emit-target build-reg)
                      (catch clojure.lang.ExceptionInfo e
                        (throw (ex-info (ex-message e)
                                        (assoc (ex-data e) ::diagnostics diagnostics)
                                        e))))
            diagnostics (cond-> diagnostics
                          (= :latex emit-target)
                          (into (latex/unanchored-reference-diagnostics
                                 lowered {:computed-numbers (boolean computed-numbers)})))
        ;; TASK-78: an Image :src is a filename this emitter cannot
        ;; safely rewrite (see latex/unescapable-image-path-diagnostics'
        ;; own docstring) the way a Link :target or a bibliography
        ;; url/doi field now is, so a backslash or unbalanced brace in
        ;; one is warned about here instead of fixed.
            diagnostics (cond-> diagnostics
                          (= :latex emit-target)
                          (into (latex/unescapable-image-path-diagnostics lowered)))
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
                                (assoc (ex-data e) ::diagnostics diagnostics)
                                e))))]
        {:diagnostics
     ;; TASK-76: the last thing checked is what is actually about to be
     ;; written, so authored and generated text are scanned by one pass.
     ;; The front-matter side files are the document too -- a fragment
     ;; build puts authored prose in them rather than in `output`. The
     ;; companion preamble is the one written file NOT scanned, and
     ;; deliberately: every character in it comes from this emitter's
     ;; own package list rather than from the author.
         (cond-> diagnostics
           (= :latex emit-target)
           (into (mapcat #(latex/untypesettable-character-diagnostics % :document)
                         (cons output (map :content front-matter)))))
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
  `output-path` already guards for the same shape."
  [path suffix]
  (let [file (io/file path)
        base-name (.getName file)
        dot (.lastIndexOf base-name ".")
        [stem extension] (if (pos? dot)
                           [(subs base-name 0 dot) (subs base-name dot)]
                           [base-name ""])
        new-name (str stem suffix extension)]
    (if-let [parent (.getParent file)]
      (str (io/file parent new-name))
      new-name)))

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

(defn build
  "`run` over the file named by `opts`' own `:input`, writing the result
  to `output-path`. Returns `{:output-path :diagnostics :bibtex-path
  :preamble-path}`, the last two being nil unless a BibTeX database or a
  fragment's companion preamble was generated alongside. The output
  file is written only on success -- an exception from any pass
  propagates with nothing written, so a failed build never leaves a
  half-converted document behind for a `make`-style tool to mistake for
  a good one (AC #5).

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
  list this task set out to eliminate, in another spelling."
  [{:keys [input] :as opts}]
  (when-not (.isFile (io/file input))
    (throw (ex-info (str "input file not found: " input) {:type ::usage-error})))
  (let [opts (assoc opts :base-dir (.getParent (.getAbsoluteFile (io/file input))))
        source (slurp input)
        ;; The same declared-or-overridden bibliography `run` itself
        ;; loads (TASK-81), read here too so `output-path` can refuse a
        ;; collision -- see its own docstring. Reading front matter only
        ;; (not the whole document) is what keeps this cheap enough to
        ;; do before the "chosen before converting anything" check below.
        bib-path (bibliography-path opts (:bibliography (parser/front-matter source)))
        ;; Chosen before converting anything, so a bad destination is
        ;; reported without first doing the whole build's work.
        path (output-path opts bib-path)
        native-latex? (and (= :latex (:target (targets (:target opts))))
                           (not (:computed-numbers opts)))
        resource (when native-latex? (bibtex-resource path))
        {:keys [output diagnostics bibliography ordered-keys preamble front-matter]}
        (run source (cond-> opts resource (assoc :bib-resource resource)))
        ;; The condition is that the emitted .tex actually asks for this
        ;; database, read off the output itself rather than re-derived
        ;; from the same inputs. A document that declares a bibliography
        ;; but cites nothing emits no \bibliography{} at all, and would
        ;; otherwise have got an unused file written beside it.
        bibtex-path (when (and resource
                               (str/includes? output (str "\\bibliography{" resource "}")))
                      (str (io/file (.getParentFile (.getAbsoluteFile (io/file path)))
                                    (str resource ".bib"))))
        ;; Conditioned on `run` having produced one at all, which is
        ;; exactly "this was a --fragment LaTeX build" -- so the two
        ;; cannot disagree about whether the fragment has a companion.
        preamble-file (when preamble (preamble-path path))
        ;; TASK-54: one side file per front-matter block, paired with the
        ;; content that goes in it so the two cannot get out of step.
        front-matter-files (mapv (fn [index]
                                   [(sibling-path path (front-matter-suffix front-matter index))
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
                             bibtex-content :bibliography)))
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
    (spit path output)
    {:output-path path :diagnostics diagnostics :bibtex-path bibtex-path
     :preamble-path preamble-file
     :front-matter-paths (mapv first front-matter-files)}))

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
  approximated by asserting on data structures instead."
  [args]
  (try
    (let [opts (parse-args args)]
      (cond
        (:help opts) (do (println usage) 0)
        (:version opts) (do (println (str "haselnuss " @version)) 0)
        :else
        (let [{:keys [diagnostics bibtex-path front-matter-paths]
               written :output-path preamble-file :preamble-path}
              (build opts)]
          (run! warn! diagnostics)
          (println (str "haselnuss: wrote " written))
          ;; Reported on its own line, not folded into the one above:
          ;; native-mode LaTeX writes a second file (TASK-42), a
          ;; --fragment build writes its companion preamble (TASK-52),
          ;; and a build that quietly produced a file the user did not
          ;; ask for is exactly the kind of surprise a converter should
          ;; not have.
          (when bibtex-path (println (str "haselnuss: wrote " bibtex-path)))
          (when preamble-file (println (str "haselnuss: wrote " preamble-file)))
          (run! (fn [file] (println (str "haselnuss: wrote " file))) front-matter-paths)
          0)))
    (catch clojure.lang.ExceptionInfo e
      ;; Warnings first, then the error that stopped the build -- the
      ;; order `pdflatex` itself reports in, and the order that lets a
      ;; warning explain the error (see `run`).
      (run! warn! (::diagnostics (ex-data e)))
      (fail! (ex-message e) (ex-data e))
      (if (= ::usage-error (:type (ex-data e)))
        (do (binding [*out* *err*] (println usage)) 2)
        1))
    (catch Exception e
      (fail! (or (ex-message e) (str e)))
      1)))

(defn -main
  "The process entry point: `main*`, then exit with the status it
  returned."
  [& args]
  (let [status (main* args)]
    (flush)
    (System/exit status)))

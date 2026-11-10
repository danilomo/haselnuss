(ns haselnuss.resolver
  "The resolver pass (`SPEC.md` sec9): a pure,
  target-independent function over a parsed `haselnuss.ast/Document` that
  runs once before any emitter. This namespace currently implements a
  step 0 -- splicing every multi-file `:include` in place, via
  `expand-includes` (TASK-38), which must run before everything below so
  an included chapter's own nodes number and resolve as if they had been
  typed into the including file -- sec9
  step 0.5 -- splicing `prepend`/`append` overlay-directive content,
  collected from one or more SEPARATE files, into a Section of the base
  document by id (TASK-83.1), and merging `describe` overlay-directive
  props into the `attr.props` of any Section/Figure/Table/MathBlock/
  referenceable Directive the base document carries by id (TASK-83.2),
  via `apply-overlays`, which runs after step 0 (an overlay can target a
  node that exists only post-include-expansion) and before step 1 (so
  spliced content numbers and cross-references as if authored inline,
  the same reasoning step 0 uses for itself) -- sec9
  step 1 -- numbering every labeled node from a per-kind counter, via
  `number-document` (TASK-11) -- sec9 step 2 -- resolving `CrossRef`
  inlines to their target's computed label and a link, via
  `resolve-cross-refs` (TASK-12) -- sec9 step 3 -- formatting every
  `Cite` in place and generating a referenceable bibliography Section, via
  `resolve-citations` (TASK-13) -- and sec9 step 4 -- deriving the table
  of contents, list-of-figures, list-of-tables, and every Section's
  next/previous navigation links, via `derive-toc`/`derive-list-of-
  figures`/`derive-list-of-tables`/`derive-navigation` (TASK-14) -- and
  sec9 step 5 -- collecting structural diagnostics (duplicate ids,
  unknown directive names) not already produced by cross-reference/
  citation resolution, via `structural-diagnostics`, and combining them
  with every earlier pass's own warnings into one report, via
  `resolve-document` (TASK-15).

  A target's *kind* is derived purely from its `attr.id`'s prefix before the
  first `:` -- sec4.1's own convention (`sec:`/`fig:`/`tbl:`/`eq:`/`thm:`/a
  custom kind), not from the AST node's `:t`/`:name` -- so any node type can
  register under any kind, and a custom directive participates in numbering
  the same way a built-in Figure or Table does (TASK-11 AC #4). A node with
  no `:id`, or whose id has no recognized kind prefix, is not a numbering
  target at all: it consumes no counter and gets no entry in the returned
  label table, mirroring TASK-8 AC #3's precedent that an un-id'd Figure/
  Table parses but is not treated as a numbering target downstream.

  `sec` (Section) is modeled as an ordinary `:section-scoped` lexicon kind,
  exactly like `fig`/`tbl`/`eq`: a Section's own counter scope is its
  *parent* Section's already-computed number path, which is precisely the
  general section-scoped mechanic and is what produces hierarchical `2`,
  `2.1`, `2.3.1` numbers (TASK-11 AC #1) without any Section-specific code.
  An unlabeled Section consumes no counter and is transparent for nesting
  purposes: its children inherit its own unchanged enclosing path."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [haselnuss.anchor :as anchor]
            [haselnuss.ast :as ast]
            [haselnuss.book-ref :as book-ref]
            [haselnuss.walk :as walk]))

(def default-lexicon
  "The built-in label lexicon (SPEC.md sec6.1's `LabelLexicon`): every
  built-in kind maps to `{:counter :section-scoped|:global :words {lang
  {:singular ... :template? ...}} :node-types #{...}}`. `:counter` picks
  the numbering behavior (`:section-scoped` composes with the enclosing
  Section's number, e.g. \"Figure 2.3\"; `:global` is one running count,
  e.g. \"Theorem 4\").
  `:words` maps a BCP-47 language tag (`meta.lang`) to the prefix word
  printed ahead of the number; `:template`, when present, overrides the
  default `\"{word} {n}\"` formatting (see `eq`'s `\"Eq. ({n})\"`, sec6.1's
  own worked example) with `{n}` substituted for the computed number.

  `:node-types` (TASK-36) is the set of AST node `:t` values this kind
  conventionally labels -- the only thing that makes an id-prefix/node-
  role disagreement detectable at all, since numbering itself is
  deliberately driven purely by the id prefix and never by `:t` (see
  this namespace's own docstring). `kind-role-diagnostics` warns when a
  labeled node's `:t` is absent from its kind's set. `:directive`
  appears in `fig`/`tbl`/`eq` because SPEC genuinely allows it there:
  sec8.4's own worked example has a `chart` directive participating in
  Figure numbering \"through its own `#fig:` id\". For the theorem-like
  family and `adm` it is the ONLY entry, because a directive is the only
  way to author one at all.

  Keeping this in the lexicon rather than in a table beside the
  diagnostics pass preserves TASK-11 AC #4's extensibility: a custom
  kind declares its own conventional role the same way it declares its
  counter and words, and a kind that omits `:node-types` is simply
  never role-checked -- the right default for a kind whose author has
  not said what it labels.

  Custom directives extend this by passing their own lexicon (merged over
  this map, e.g. `(merge default-lexicon {:frm {...}})`) as
  `number-document`'s second argument -- see TASK-11 AC #4."
  {;; TASK-53. A chapter is a Section like any other -- `:section-scoped`
   ;; and `:node-types #{:section}`, exactly as `sec` is -- so it nests by
   ;; AST structure and a `sec:`-labeled heading inside it composes onto
   ;; its number with no special case anywhere in `number-block`. What
   ;; makes it a *chapter* is `meta.topLevelDivision`, which decides what
   ;; a level-1 heading is called in the output and what a section-scoped
   ;; figure number composes against; see `number-document`.
   :ch {:counter :section-scoped
        :node-types #{:section}
        :words {"en" {:singular "Chapter"}
                "pt-BR" {:singular "Capítulo"}}}
   ;; TASK-82. A part sits one division above a chapter
   ;; (`topLevelDivision: part`) and is structural exactly the way `:ch`
   ;; is -- `:section-scoped` plus `:node-types #{:section}` so it nests
   ;; by AST structure, and its own counter steps for every level-1
   ;; Section whether labeled or not (`number-block`'s `part-counter-key`).
   ;; What sets it apart from every other kind is `:number-format
   ;; :roman`: confirmed against a real pdflatex, a `\part` prints as a
   ;; roman numeral ("Part I") via its own counter, which never resets or
   ;; composes with the chapter/figure/table/equation numbering -- a
   ;; chapter after Part II still numbers 4, not reset to 1, and a figure
   ;; under it still composes only with its own chapter (see
   ;; `number-block`'s own docstring for the full confirmation).
   :part {:counter :section-scoped
          :node-types #{:section}
          :number-format :roman
          :words {"en" {:singular "Part"}
                  "pt-BR" {:singular "Parte"}}}
   :sec {:counter :section-scoped
         :node-types #{:section}
         :words {"en" {:singular "Section"}
                 "pt-BR" {:singular "Seção"}}}
   :fig {:counter :section-scoped
         :node-types #{:figure :directive}
         :words {"en" {:singular "Figure"}
                 "pt-BR" {:singular "Figura"}}}
   :tbl {:counter :section-scoped
         :node-types #{:table :directive}
         :words {"en" {:singular "Table"}
                 "pt-BR" {:singular "Tabela"}}}
   :eq {:counter :section-scoped
        :node-types #{:math-block :directive}
        :words {"en" {:singular "Eq." :template "Eq. ({n})"}
                "pt-BR" {:singular "Eq." :template "Eq. ({n})"}}}
   ;; The theorem-like family and the admonition (TASK-40). One kind per
   ;; directive `haselnuss.emit.latex/default-directive-environments`
   ;; maps, so every one of them is a numbering target and therefore
   ;; cross-referenceable; before this, `thm` was the only one, and an
   ;; `#adm:x` or `#lem:x` resolved to no kind at all, which made every
   ;; reference to it dangle. Each mapped environment names its kind
   ;; here in its own `:kind` key, and
   ;; `haselnuss.emit.latex-test/kind-and-environment-agreement-test`
   ;; fails if the two tables ever drift apart -- a kind the emitter
   ;; cannot anchor is worse than no kind at all (AC #4).
   ;;
   ;; `:global`, like `thm`: these are document-wide sequences, and
   ;; native-mode LaTeX numbers them with `\\newtheorem` counters, which
   ;; are global unless told otherwise.
   :thm {:counter :global
         :node-types #{:directive}
         :words {"en" {:singular "Theorem"}
                 "pt-BR" {:singular "Teorema"}}}
   :lem {:counter :global
         :node-types #{:directive}
         :words {"en" {:singular "Lemma"}
                 "pt-BR" {:singular "Lema"}}}
   :cor {:counter :global
         :node-types #{:directive}
         :words {"en" {:singular "Corollary"}
                 "pt-BR" {:singular "Corolário"}}}
   :def {:counter :global
         :node-types #{:directive}
         :words {"en" {:singular "Definition"}
                 "pt-BR" {:singular "Definição"}}}
   :prf {:counter :global
         :node-types #{:directive}
         :words {"en" {:singular "Proof"}
                 "pt-BR" {:singular "Prova"}}}
   :adm {:counter :global
         :node-types #{:directive}
         :words {"en" {:singular "Note"}
                 "pt-BR" {:singular "Nota"}}}
   ;; A captioned, numbered code listing (TASK-57). `:global`, and that
   ;; is not the arbitrary half of the choice: native-mode LaTeX numbers
   ;; it with a `float`-package `\\newfloat` counter, which carries no
   ;; `\\@addtoreset` and so runs document-wide -- confirmed with a real
   ;; pdflatex, including in a chaptered document, where a
   ;; `:section-scoped` kind here would have printed 1.1 beside LaTeX's
   ;; own 1. `:node-types #{:directive}` because the diagnostics passes
   ;; run before `haselnuss.lower`, so what they see is still the
   ;; authored directive rather than whatever it lowers to.
   :lst {:counter :global
         :node-types #{:directive}
         :words {"en" {:singular "Listing"}
                 "pt-BR" {:singular "Listagem"}}}
   ;; Pseudocode (TASK-58). Same shape and same reasoning as `lst`: it is
   ;; the same `float`-package float underneath, so the same `:global`
   ;; counter behaviour, and the same `:directive` role because the
   ;; diagnostics passes run before lowering.
   :alg {:counter :global
         :node-types #{:directive}
         :words {"en" {:singular "Algorithm"}
                 "pt-BR" {:singular "Algoritmo"}}}})

(defn- id->kind
  "The numbering kind for `id` (a node's `attr.id`, e.g. \"fig:tree\"): the
  keyword before its first `:` (`:fig`), or nil if `id` is nil or has no
  `:`-separated prefix at all (sec4.1's own id-prefix convention -- an id
  with no prefix has no derivable kind, so is never a numbering target)."
  [id]
  (when id
    (let [[kind label] (str/split id #":" 2)]
      (when (and label (seq kind))
        (keyword kind)))))

(defn- table-cells
  "Every Cell in Table block `table`, across its head row and body rows."
  [table]
  (mapcat :cells (cons (:head table) (:rows table))))

(defn- cite-item-inlines
  "Every Inline held by `item`, a CiteItem -- its `:prefix` and `:suffix`,
  read off `haselnuss.walk/table` rather than named, so this stays right
  if the schema gives a CiteItem a third such field."
  [item]
  (mapcat #(get item %) (walk/fields-holding ::ast/cite-item item ::ast/inline)))

(defn- rebuild-inline-children
  "`inline` with every one of its nested-content fields rewritten:
  `blocks-fn` over each `[:many ::ast/block]` field (a footnote `:note`'s
  or a `:sticky-note`'s own `:blocks`), `inlines-fn` over each `[:many
  ::ast/inline]` one (`:emph`, `:span`, `:link`, `:highlight`, ...), and
  `inlines-fn` again over each CiteItem's own `:prefix`/`:suffix` behind
  a `[:many ::ast/cite-item]` field.

  The shared body of this file's two rebuild-in-place inline walks
  (`expand-inlines`, `splice-overlay-inlines`), which were separate
  hand-written `case`s over the same eight variant names -- kept
  deliberately \"in step with each other\" by their own docstrings, and
  both silently incomplete the day TASK-116 added a node type neither
  named. Asking `haselnuss.walk/fields-holding` instead means there is
  nothing left to keep in step.

  An absent optional field (a CiteItem with no `:prefix`) is left absent
  rather than created empty, matching what these walks did before."
  [inline {:keys [blocks-fn inlines-fn]}]
  (as-> inline node
    (reduce (fn [n field] (cond-> n (get n field) (update field blocks-fn)))
            node (walk/fields-holding ::ast/inline node ::ast/block))
    (reduce (fn [n field] (cond-> n (get n field) (update field inlines-fn)))
            node (walk/fields-holding ::ast/inline node ::ast/inline))
    (reduce (fn [n field]
              (cond-> n
                (get n field)
                (update field
                        (partial mapv
                                 (fn [item]
                                   (reduce (fn [i f] (cond-> i (get i f) (update f inlines-fn)))
                                           item
                                           (walk/fields-holding ::ast/cite-item item
                                                                ::ast/inline)))))))
            node (walk/fields-holding ::ast/inline node ::ast/cite-item))))

(defn- inline-blocks
  "Every Block reachable from `inlines` through an Inline that carries
  them -- a footnote `:note`, and since TASK-116 a `:sticky-note` --
  recursing first into every Inline that carries nested Inlines
  (`:emph`, `:strong`, `:span`, `:link`, `:highlight`, ...) and into a
  Cite's own per-item `:prefix`/`:suffix`, so a labeled Figure/Table/etc.
  tucked inside a footnote still numbers (sec9 step 1: \"every labeled
  node\").

  WHICH Inlines those are is asked of `haselnuss.walk/fields-holding`,
  not listed here. It used to be a hand-written `case` naming eight
  variants, and the day TASK-116 added `:highlight` and `:sticky-note`
  that list was silently wrong: a Figure inside a footnote inside a
  highlight got no number at all, and no diagnostic, while the identical
  document written with `*emph*` numbered fine."
  [inlines]
  (mapcat
   (fn [inline]
     (concat
      (mapcat #(get inline %) (walk/fields-holding ::ast/inline inline ::ast/block))
      (mapcat #(inline-blocks (get inline %))
              (walk/fields-holding ::ast/inline inline ::ast/inline))
      (mapcat (fn [field] (mapcat #(inline-blocks (cite-item-inlines %)) (get inline field)))
              (walk/fields-holding ::ast/inline inline ::ast/cite-item))))
   inlines))

(defn- block-children
  "The Block's nested Blocks, one case per Block variant that can contain
  any (sec4.3): a Section/BlockQuote/Directive's own `:blocks`, a List's
  `:items` (a vector of Blocks per item), a Figure's single `:content`
  Block, a Table's cells' `:blocks`, and a Para's Inlines (via
  `inline-blocks`, for footnote-nested Blocks). Every other variant
  (CodeBlock/MathBlock/Include/ThematicBreak) has no nested Blocks."
  [block]
  (case (:t block)
    :section (:blocks block)
    :list (mapcat identity (:items block))
    :block-quote (:blocks block)
    :directive (:blocks block)
    :figure [(:content block)]
    :table (mapcat :blocks (table-cells block))
    :para (inline-blocks (:inlines block))
    ;; TASK-154: a quote's spliced passage is deliberately NOT a child
    ;; here. Everything built on this traversal -- numbering, the TOC,
    ;; the lists of figures and tables, the structural diagnostics --
    ;; is about the host's own body, and quoted content belongs to the
    ;; book it came from (doc-10, step 0b: never numbered, never listed).
    :book-quote []
    []))

;; --- Include expansion (TASK-38, sec4.3) -----------------------------------
;;
;; sec9 step 0, in effect: an `:include` Block is replaced by the Blocks of
;; the document its `:src` names, before anything else runs. It has to be
;; first -- an included chapter's figures and sections must number, be
;; cross-referenced and be diagnosed exactly as if they had been typed into
;; the including file, which is only true if every later pass sees one
;; already-spliced document.
;;
;; Reading and parsing the referenced file is the caller's job, supplied as
;; `:load`. That keeps this namespace free of both file I/O in its passes and
;; a dependency on `haselnuss.parser` (the pipeline runs parse -> resolve; a
;; resolver that called the parser would invert that for one feature), and it
;; mirrors how `:bibliography` is handed in pre-loaded rather than read here.
;; `haselnuss.cli` supplies the obvious `slurp` + `parse`.
;;
;; Only the included document's `:blocks` are spliced. Its own front matter is
;; dropped: a Meta is a property of the document being built, and merging two
;; titles, two author lists or two bibliographies has no defensible answer.
;;
;; Two failure modes are diagnostics, not exceptions, and both drop the
;; offending `:include` so the rest of the build proceeds (AC #3/#4): a file
;; that cannot be READ, and a cycle. A cycle is detected by canonical path
;; against the stack of files currently being expanded, so a diamond (two
;; chapters including one shared file) is fine while a true loop is not.
;;
;; A THIRD failure -- `:load` throwing for a file that WAS read but failed to
;; PARSE -- is deliberately NOT one of those two (TASK-105). Before TASK-105
;; this pass caught every exception `:load` raised alike, so a malformed
;; included file -- an unclosed YAML flow sequence in its own front matter,
;; an unsupported inline construct -- was warned about and its Blocks were
;; spliced in as `[]`: a whole chapter vanished from the output while the
;; build still exited 0, unlike the identical content converted on its own,
;; which is a hard failure. Content the author wrote and intended to ship is
;; not the same kind of "nothing to lose" as a file that never existed or a
;; cycle that closes on itself (both of THOSE genuinely have no content to
;; drop), so it does not get the same warn-and-drop treatment: `expand-one`
;; below re-throws anything `:load` raises that is not a `java.io.IOException`
;; (a missing file, a directory, a permission error -- exactly what `slurp`
;; itself throws for each, confirmed against a real `slurp`), naming the
;; offending file, and lets it propagate uncaught the same way an emit-stage
;; error inside an included file already does (this namespace's own prior
;; asymmetry finding). Catching by JVM exception type rather than adding a
;; parser dependency here keeps the "free of `haselnuss.parser`" property
;; this namespace's own docstring already commits to, while still matching
;; exactly what `haselnuss.cli`'s real `:load` (`slurp` then `parser/parse`)
;; actually raises for each case.

(defn- locate-warning
  "Diagnostic `w` placed at `node`'s source position (TASK-143): the position
  the parser recorded (`haselnuss.ast/source`) as `:source-line` and, when
  the document was parsed with a `:source` path, `:source-file`, with
  `path:line: ` prepended to the unchanged message -- the same form a
  located parse error prints. A node with no recorded position (built
  by a rule, read from JSON, or parsed without a path for the message)
  leaves `w` as it was. Defined here, ahead of include expansion, because
  that is its first user (TASK-163: an include diagnostic names the
  `!include` line)."
  [node w]
  (if-let [{:keys [file line]} (ast/source node)]
    (cond-> (assoc w :source-line line)
      file (-> (assoc :source-file file)
               (update :message #(str file ":" line ": " %))))
    w))

(declare expand-blocks)

(defn- expand-inlines
  "`inlines` with every footnote's own Blocks expanded, mirroring
  `inline-blocks`' traversal exactly -- the same variants, for the same
  reason: an `:include` tucked inside a footnote is still an include.
  The parser cannot put one there (a footnote definition is parsed by
  flexmark, which knows nothing of `!include` lines), but
  `haselnuss.json/json->ast` can, and an emitter reached that way would
  raise `::unresolved-include` from inside a note."
  [inlines expand-one]
  (mapv (fn [inline]
          (rebuild-inline-children
           inline
           {:blocks-fn #(expand-blocks % expand-one)
            :inlines-fn #(expand-inlines % expand-one)}))
        inlines))

(defn- expand-blocks
  "`blocks` with every `:include` replaced by `(expand-one include)`'s own
  Blocks, recursing into every Block variant that can hold one.

  That is `block-children`'s own set with a single exception, Figure's
  `:content`: it is one Block, not a vector, so it has nowhere to put a
  splice of many -- and the parser never puts an `:include` there, since
  a Figure's content comes from flexmark. Keeping the rest in step with
  `block-children` matters, because a Block variant added to one and not
  the other is an `:include` that silently survives to an emitter;
  `include-expansion-reaches-every-container-test` pins the set.

  `expand-one` returns a vector, so an include may expand to many
  Blocks or, when it fails, to none."
  [blocks expand-one]
  (into []
        (mapcat
         (fn [block]
           (case (:t block)
             :include (expand-one block)
             (:section :block-quote :directive)
             [(update block :blocks expand-blocks expand-one)]
             :para [(update block :inlines expand-inlines expand-one)]
             :list [(update block :items (partial mapv #(expand-blocks % expand-one)))]
             :table [(-> block
                         (update :head update :cells
                                 (partial mapv #(update % :blocks expand-blocks expand-one)))
                         (update :rows
                                 (partial mapv
                                          (fn [row]
                                            (update row :cells
                                                    (partial mapv #(update % :blocks expand-blocks
                                                                           expand-one)))))))]
             [block])))
        blocks))

(defn- resolve-include-path
  "The `java.io.File` `src` names, relative to `dir` (the directory of the
  file the `:include` was written in, so a chapter's own includes resolve
  against the chapter, not against whichever document happens to be
  including it). An absolute `src` is used as-is."
  ^java.io.File [dir src]
  (let [file (java.io.File. ^String src)]
    (if (.isAbsolute file) file (java.io.File. ^String (str dir) ^String src))))

(defn- canonical-path
  "`file`'s canonical path, or its absolute path if the filesystem
  refuses to canonicalize it (a broken symlink, a permission error).
  Used as the cycle-detection identity, so that two spellings of one
  file -- `a/../a/ch.hdoc` and `a/ch.hdoc` -- are one node in the graph
  rather than two."
  [^java.io.File file]
  (try (.getCanonicalPath file) (catch java.io.IOException _ (.getAbsolutePath file))))

(defn expand-includes
  "Replaces every `:include` Block in `document` with the Blocks of the
  document its `:src` names, recursively, returning `{:document
  document' :warnings warnings}` (AC #1/#2 of TASK-38).

  `opts`:

  - `:load`, `(fn [^java.io.File file] -> Document)`. Required for any
    expansion to happen at all: with no loader this pass is a no-op and
    every `:include` is left standing, which is the right behavior for a
    caller that has no filesystem to read (a test, or a document arriving
    through `haselnuss.json`'s interchange format).
  - `:base-dir`, the directory the top-level document's own relative
    `:src` values resolve against -- its own directory. Defaults to the
    process working directory.
  - `:source-path`, the file `document` itself was read from. Only the
    cycle guard uses it, and it is what puts the ROOT document on the
    stack: without it a loop that comes back around to the root splices
    the root's own body in before the guard fires, so `doc.hdoc`
    including itself emitted its body twice (found by review). Omit it
    only for a document that came from no file.

  Two failures produce a warning and drop the offending `:include`
  rather than throwing, so one bad reference does not cost an author the
  rest of the build -- neither has any content to lose:

  - `{:type :unreadable-include :src :path :message}` when `:load`
    throws a `java.io.IOException` -- a missing file, a directory, or a
    permission error (AC #4). Exactly what a real `slurp` itself throws
    for each (confirmed against a real `slurp`), which is what
    `haselnuss.cli`'s own `:load` is built from.
  - `{:type :include-cycle :src :path :cycle :message}` when a file is
    already being expanded further up the stack (AC #3). Detected by
    canonical path, so a diamond -- two chapters including one shared
    file -- expands twice as it should, while a true loop stops at the
    edge that closes it.

  Both, and the `::unparseable-include` error below, are placed at the
  `!include` line that caused them (TASK-163, `locate-warning`):
  `:source-line`, plus `:source-file` and a `path:line: ` message prefix
  when the including document was parsed with a `:source`. An include
  with no recorded position (read from JSON, built by a rule) reports
  exactly as before.

  A file that WAS read but failed to parse is neither of those (TASK-105
  AC #1): `:load` throwing anything other than a `java.io.IOException` --
  a malformed YAML front matter, an unsupported inline construct, any
  other `haselnuss.parser/parse` failure -- propagates OUT of this
  function uncaught, naming the offending file
  (`::unparseable-include`'s own message), rather than being warned about
  and spliced in as `[]`. Content the author wrote and intended to ship
  is not the same kind of \"nothing to lose\" the two diagnostics above
  are for, so it gets the same fate an emit-stage error inside an
  included file already had before this task: the build stops rather
  than silently dropping a chapter and exiting 0. This is true at any
  include depth (TASK-105 AC #3) for free -- nothing here catches it on
  the way back up through a chain of recursive `expand` calls.

  That thrown `ex-info`'s own `ex-data` also carries `:warnings` -- every
  `:unreadable-include`/`:include-cycle` THIS call already collected
  from an earlier sibling include anywhere in the walk, before the fatal
  one was reached (review finding on TASK-105, fixed same-day): without
  it, an earlier warning was silently lost the moment a later include in
  the same document turned out to be unparseable, since the normal
  return -- the only place `warnings` was otherwise read back out of its
  `volatile!` -- never happens once anything throws. A caller that
  catches this exception (`haselnuss.cli/run` does, mirroring its own
  `lower`/emit try/catch precedent) is expected to fold `:warnings` back
  into whatever diagnostics it reports alongside the failure, the same
  way `pdflatex` still shows every earlier warning on the way to a fatal
  one instead of only the last line before it stopped.

  What the cycle guard bounds is recursion *depth*, not total work: a
  cycle-free DAG where each of N files includes the same two children
  still expands exponentially, and gets no diagnostic for it. Accepted
  -- it takes deliberate effort to author and is not a shape any real
  document has -- and recorded rather than guessed at with an arbitrary
  expansion cap."
  ([document] (expand-includes document {}))
  ([document {load-document :load :keys [base-dir source-path]}]
   (if-not load-document
     {:document document :warnings []}
     (let [warnings (volatile! [])
           warn! (fn [w] (vswap! warnings conj w) [])
           expand
           (fn expand [blocks dir stack]
             (expand-blocks
              blocks
              (fn [include]
                (let [src (:src include)
                      ^java.io.File file (resolve-include-path dir src)
                      path (canonical-path file)]
                  (if (contains? (set stack) path)
                    (warn! (locate-warning
                            include
                            {:type :include-cycle
                             :src src
                             :path path
                             :cycle (conj (vec stack) path)
                             :message (str "include cycle: " (pr-str src) " is already being"
                                           " included, via " (str/join " -> " (conj (vec stack) path)))}))
                    (let [included (try (load-document file)
                                        ;; A missing file, a directory, a
                                        ;; permission error -- confirmed
                                        ;; against a real `slurp` to be
                                        ;; exactly what it throws for
                                        ;; each. Nothing here reads as
                                        ;; content lost, so this stays a
                                        ;; warning (TASK-105 AC #2).
                                        (catch java.io.IOException e
                                          (warn! (locate-warning
                                                  include
                                                  {:type :unreadable-include
                                                   :src src
                                                   :path path
                                                   :message (str "cannot include " (pr-str src)
                                                                 " (" path "): " (ex-message e))}))
                                          ::failed)
                                        ;; Anything else `:load` threw is a
                                        ;; file that WAS read but failed to
                                        ;; PARSE -- content the author
                                        ;; wrote, so this does NOT warn and
                                        ;; drop; it names the file and
                                        ;; propagates, failing the build
                                        ;; the same way the identical
                                        ;; content fails on its own
                                        ;; (TASK-105 AC #1/#3). `:warnings`
                                        ;; is `@warnings` AT THIS INSTANT,
                                        ;; not `[]` -- every
                                        ;; `:unreadable-include`/
                                        ;; `:include-cycle` this SAME
                                        ;; `expand-includes` call already
                                        ;; collected from an earlier
                                        ;; sibling include, anywhere in the
                                        ;; walk, before this one failed.
                                        ;; Without this a warning for an
                                        ;; earlier, unrelated bad include
                                        ;; in the same document vanished
                                        ;; the moment a LATER one turned
                                        ;; out to be unparseable, since
                                        ;; `expand-includes` never reaches
                                        ;; its own normal return -- and
                                        ;; hence never returns `@warnings`
                                        ;; at all -- once anything throws
                                        ;; (review finding on this task).
                                        ;; `haselnuss.cli/run` reads this
                                        ;; back out of `(ex-data e)` to
                                        ;; fold it into `::diagnostics`
                                        ;; alongside every other pass's,
                                        ;; the same precedent its own
                                        ;; `lower`/emit try/catches already
                                        ;; set.
                                        (catch Exception e
                                          (let [data (locate-warning
                                                      include
                                                      {:type ::unparseable-include :src src :path path
                                                       :warnings @warnings
                                                       :message (str "cannot include " (pr-str src)
                                                                     " (" path "): " (ex-message e))})]
                                            (throw (ex-info (:message data) (dissoc data :message) e)))))]
                      (if (= ::failed included)
                        []
                        ;; Recur with the included file's OWN directory
                        ;; and the stack it now sits on, so its includes
                        ;; resolve and cycle-check from where it lives.
                        (expand (:blocks included)
                                (.getParentFile (.getAbsoluteFile file))
                                (conj (vec stack) path)))))))))
           ;; The root document is on the stack from the start, when the
           ;; caller says where it came from -- a loop back to the root
           ;; is a cycle like any other.
           expanded (expand (:blocks document) (or base-dir ".")
                            (if source-path
                              [(canonical-path (java.io.File. ^String (str source-path)))]
                              []))]
       {:document (assoc document :blocks expanded)
        :warnings @warnings}))))

;; --- Quote expansion (TASK-154, doc-10 "Resolver" step 0b) -----------------
;;
;; Every `:book-quote` (a `!quote <ref>` line) gets the blocks of the
;; passage its reference names spliced inside it, as `:blocks`, along with
;; the passage's canonical reference (`:canonical`), its title and its URL.
;;
;; Finding the book and its passage is not this namespace's job: the
;; library providers require this namespace, so it cannot require them.
;; The caller injects a `:resolve` fn instead -- exactly as `!include`
;; gets the parser through `:includes`' `:load` -- and
;; `haselnuss.library.references/books-option` is the one a build uses. It
;; is called only for a quote that exists, so a document with no `!quote`
;; never loads the library directory.
;;
;; Quoted content belongs to the book it came from, not to the host, and
;; is made so here:
;;
;; - every `attr.id` in it is stripped, so nothing quoted is a cross-
;;   reference target, an overlay target, or a duplicate of a host id;
;; - every `:cross-ref` in it becomes a `:book-ref` to the source book,
;;   still pointing where its author meant: one the book resolved keeps
;;   the book's text and gets `:canonical`; one that dangled in the book
;;   gets `??`, no `:canonical`, and an `:unknown-label` warning at the
;;   `!quote` (see `quoted-cross-ref`);
;; - it is never numbered, never in the TOC or the lists of figures and
;;   tables: `block-children` does not descend into a `:book-quote`, and
;;   with no ids nothing in it has a number for an emitter to print;
;; - its citations were formatted against the BOOK's bibliography when the
;;   book was resolved, so `resolve-document` hides quoted blocks from the
;;   host's own citation pass (`detach-quoted`).
;;
;; A quote inside quoted content expands too. The books being expanded
;; form a chain from the host down; a quote whose book is already on it
;; is a `:quote-cycle` (one book quoting itself included, as `!include`
;; treats a file), and a quote more than `max-quote-depth` levels down is
;; `:quote-too-deep`. Both are measured from the host, never cached: the
;; passage context caches each book's resolved document WITHOUT its quotes
;; expanded, so what a book resolves to never depends on who quoted it
;; first, and loading one never re-enters another's loading.
;;
;; A failed quote keeps its node, with a placeholder paragraph naming the
;; reference as its `:blocks` and no `:canonical`, and a warning placed at
;; the `!quote` line; the build continues.

(def max-quote-depth
  "How deeply quotes may nest (doc-10): a quote in the host is at depth 1,
  a quote inside what it quotes at depth 2, and one beyond this is
  `:quote-too-deep`."
  8)

(defn- quote-placeholder
  "The visible paragraph a quote that could not be resolved leaves behind,
  naming its reference as written -- the `??` a dangling cross-reference
  prints, for a whole block."
  [written]
  {:t :para :inlines [{:t :str :text (str "?? " written)}]})

(defn- quoted-cross-ref
  "`cross-ref`, a `:cross-ref` inline inside a passage of the book `id`, as
  a `:book-ref` to that label of that book. `:canonical` means a RESOLVED
  target, so it depends on what the book's own resolution made of the
  reference:

  - resolved (a `:target`): `:canonical` set, and the book's text
    (\"Section 3\") kept as the link text, so the quote still reads as its
    author wrote it. Already resolved: link resolution leaves it alone.
  - dangling in the book (`:target nil`): NO `:canonical`, and `:text`
    the same `??` placeholder the book printed. `sanitize-quoted` reports
    it (`:unknown-label`, at the host's `!quote`), so it is resolved --
    to a failure -- here, and link resolution leaves it alone too.
  - never resolved by the book (no `:target` key: a passage a book's own
    `locate` built): neither `:canonical` nor `:text` -- an ordinary
    unresolved `:book-ref`, for link resolution (TASK-155) to resolve
    and, if it dangles, report."
  [id {:keys [label text] :as cross-ref}]
  (let [canonical (book-ref/format-book-ref {:id id :passage {:form :label :label label}})
        node {:t :book-ref :ref canonical :passage {:form :label :label label}}]
    (cond
      (not (contains? cross-ref :target)) node
      (nil? (:target cross-ref)) (assoc node :text [{:t :str :text "??"}])
      :else (cond-> (assoc node :canonical canonical)
              (string? text) (assoc :text [{:t :str :text text}])))))

(defn- sanitize-quoted
  "`blocks`, a passage of the book `id`, made fit to sit inside the host:
  every `attr.id` stripped (at every position, a Directive's `:fallback`
  included, since lowering can bring it in later) and every `:cross-ref`
  rewritten into a `:book-ref` to the source book (`quoted-cross-ref`),
  calling `dangling!` with the label of each one that dangled in the
  book. Source positions survive, so a quote nested in the passage is
  still reported at the line of the book it was written on. A rewritten
  reference with no position of its own (a locator's blocks) takes
  `quote-node`'s, so link resolution (TASK-155) can report it at the
  `!quote` that brought it in."
  [id blocks quote-node dangling!]
  (let [{quote-file :file quote-line :line} (ast/source quote-node)
        walker {:table walk/table
                :scope walk/every-ref-kind
                :rewrite-fn
                (fn [ref-kind node]
                  (cond
                    (not (map? node)) [node]
                    (and (= ::ast/inline ref-kind) (= :cross-ref (keyword (:t node))))
                    (do (when (and (contains? node :target) (nil? (:target node)))
                          (dangling! (:label node)))
                        [(cond-> (with-meta (quoted-cross-ref id node) (meta node))
                           (nil? (ast/source node)) (ast/with-source quote-file quote-line))])
                    (and (map? (:attr node)) (contains? (:attr node) :id))
                    [(update node :attr dissoc :id)]
                    :else [node]))}]
    (into [] (mapcat #(walk/rewrite walker ::ast/block %)) blocks)))

(def ^:private quote-scope
  "Where a `:book-quote` can sit: every block and inline position of the
  document, `meta.title` included (a footnote in a title can hold
  blocks), but not a Directive's target-specific `:fallback`."
  #{::ast/block ::ast/inline ::ast/cell ::ast/row ::ast/cite-item ::ast/meta})

(defn- chain-text
  [chain]
  (str/join " -> " (map book-ref/format-book-id chain)))

(declare expand-quotes-in)

(defn- expand-quote
  "`node`, a `:book-quote` at `depth` with the books `chain` above it, with
  its passage spliced in, or with a placeholder when that failed. Warnings
  go to `(:warn! env)`."
  [{resolve-ref :resolve warn! :warn! :as env} chain depth node]
  (let [written (:ref node)
        parsed (book-ref/parse-book-ref written)
        report! (fn [diagnostic]
                  (warn! (locate-warning node (merge {:level :warning} diagnostic {:ref written}))))
        fail (fn [diagnostic]
               (report! diagnostic)
               (-> node
                   (dissoc :canonical :title :url)
                   (assoc :blocks [(quote-placeholder written)])))]
    (cond
      (book-ref/malformed? parsed) (fail parsed)

      (some #(book-ref/same-book? % (:id parsed)) chain)
      (fail {:type :quote-cycle
             :cycle (conj (mapv book-ref/format-book-id chain) (book-ref/format-book-id (:id parsed)))
             :message (str "quote cycle: !quote " written
                           " names a book that is already being quoted, via "
                           (chain-text (conj chain (:id parsed))))})

      (> depth max-quote-depth)
      (fail {:type :quote-too-deep
             :depth depth
             :message (str "!quote " written " is nested " depth " quotes deep, more than the "
                           max-quote-depth " allowed, via " (chain-text chain))})

      :else
      (let [{:keys [passage id failure warnings diagnostics]} (resolve-ref written parsed)]
        (run! warn! diagnostics)
        (if failure
          (fail failure)
          (do (run! report! warnings)
              (cond-> (-> node
                          (dissoc :url)
                          (assoc :canonical (:ref passage)
                                 :title (:title passage)
                                 :blocks (expand-quotes-in
                                          env (conj chain id) (inc depth)
                                          (sanitize-quoted
                                           id (:blocks passage) node
                                           (fn [label]
                                             (report!
                                              {:type :unknown-label
                                               :label label
                                               :book (book-ref/format-book-id id)
                                               :canonical (:ref passage)
                                               :message (str "!quote " written ": the quoted passage"
                                                             " refers to @" label ", which book "
                                                             (book-ref/format-book-id id)
                                                             " does not have; it reads ??")}))))))
                (:url passage) (assoc :url (:url passage)))))))))

(defn- quote-walker
  "The `haselnuss.walk/rewrite` walker that expands every quote it meets at
  `depth` below `chain`. An expanded node is marked (in its metadata) so
  that the walk, which goes on into what it just spliced, does not expand
  the quotes in there a second time."
  [env chain depth]
  {:table walk/table
   :scope quote-scope
   :rewrite-fn (fn [ref-kind node]
                 (if (and (= ::ast/block ref-kind)
                          (= :book-quote (keyword (:t node)))
                          (not (::expanded (meta node))))
                   [(vary-meta (expand-quote env chain depth node) assoc ::expanded true)]
                   [node]))})

(defn- expand-quotes-in
  [env chain depth blocks]
  (let [walker (quote-walker env chain depth)]
    (into [] (mapcat #(walk/rewrite walker ::ast/block %)) blocks)))

(defn expand-quotes
  "Splices into every `:book-quote` in `document` the passage its `:ref`
  names (doc-10 step 0b; see this section's comment), returning
  `{:document document' :warnings warnings}`.

  `opts` is `resolve-document`'s `:books`:

  - `:resolve`, `(fn [written parsed] result)`: `written` the reference
    as written, `parsed` what `haselnuss.book-ref/parse-book-ref` made of it
    (never malformed -- that is reported here). `result` is `{:passage
    Passage :id BookId :warnings [...] :diagnostics [...]}` -- `:id` the
    book's current, canonical id -- or `{:failure diagnostic :diagnostics
    [...]}`. `:warnings` (and `:failure`) concern this reference and are
    placed at its `!quote` line; `:diagnostics` concern the build (a
    library directory's load problems, a book's own load warnings) and
    are reported as they are. Without `:resolve` this pass is a no-op and
    every `:book-quote` is left as parsed, as `expand-includes` leaves an
    `:include` without a `:load`.
  - `:self`, the BookId of the document itself when it is a book of a
    library, so that a quote back into it is a cycle.

  Every warning carries `:ref`, the reference as written, and a `:level`
  (`:warning`, or `:info` for a `:stale-shelf`)."
  ([document] (expand-quotes document {}))
  ([document {resolve-ref :resolve self :self}]
   (if-not resolve-ref
     {:document document :warnings []}
     (let [warnings (volatile! [])
           env {:resolve resolve-ref :warn! #(vswap! warnings conj %)}
           walker (quote-walker env (if self [self] []) 1)]
       {:document (first (walk/rewrite walker ::ast/document document))
        :warnings @warnings}))))

(defn- detach-quoted
  "`[document' quoted]`: `document` with every outermost spliced quote's
  `:blocks` taken out and set aside in `quoted`, for a pass that must not
  see them (`reattach-quoted` puts them back)."
  [document]
  (let [quoted (volatile! [])
        walker {:table walk/table
                :scope quote-scope
                :rewrite-fn (fn [ref-kind node]
                              (if (and (= ::ast/block ref-kind)
                                       (= :book-quote (keyword (:t node)))
                                       (contains? node :blocks))
                                (let [n (count @quoted)]
                                  (vswap! quoted conj (:blocks node))
                                  [(assoc node :blocks [] ::quoted n)])
                                [node]))}]
    [(first (walk/rewrite walker ::ast/document document)) @quoted]))

(defn- reattach-quoted
  [document quoted]
  (if (empty? quoted)
    document
    (let [walker {:table walk/table
                  :scope quote-scope
                  :rewrite-fn (fn [ref-kind node]
                                (if (and (= ::ast/block ref-kind) (contains? node ::quoted))
                                  [(-> node
                                       (assoc :blocks (nth quoted (::quoted node)))
                                       (dissoc ::quoted))]
                                  [node]))}]
      (first (walk/rewrite walker ::ast/document document)))))

;; --- Overlay application (TASK-83.1/TASK-83.2, doc-5) ----------------------
;;
;; sec9 step 0.5, in effect: `apply-overlays` applies `prepend`/`append`/
;; `describe` overlay directives -- collected from one or more SEPARATE
;; files, each an ordinary `.hdoc` whose top-level Blocks are expected to be
;; entirely such directives -- to the base document, each by the target
;; node's `attr.id`: `prepend`/`append` splice Blocks into the start/end of
;; a Section (part/chapter/section, any level); `describe` merges flat
;; metadata into `attr.props` on any of the five referenceable node kinds
;; `describable-block-types` names. Runs after `expand-includes` (an overlay
;; can target a node that exists only post-include-expansion, e.g. a
;; chapter pulled in from another file) and before `number-document` (so
;; spliced content numbers and cross-references exactly as if authored
;; inline) -- the same ordering argument `expand-includes` already makes for
;; itself, one step later.
;;
;; Two-phase, unlike `expand-includes`'s pure in-place replace: splicing
;; happens at a different tree location than collection, so nothing here
;; can be a single recursive replace.
;;
;; `apply-overlays` needs no `:load`/I/O callback (unlike `expand-includes`):
;; reading each overlay file, and running `expand-includes` over its OWN
;; `!include`s, is `haselnuss.cli/load-overlays!`'s job, so this namespace
;; stays free of file I/O and of any dependency on `haselnuss.parser` --
;; the plain Block vectors it reads here already sat through that pass.

(def ^:private annotation-directive-names
  "The two overlay directive names that place an annotation (TASK-119,
  doc-8 sec4), as opposed to injecting content or metadata."
  #{"highlight" "sticky-note"})

(def ^:private overlay-directive-names
  "Every top-level directive name an overlay file may use."
  (into #{"prepend" "append" "describe"} annotation-directive-names))

(def ^:private recognized-overlay-names
  "`overlay-directive-names` as one readable list, for the diagnostic
  TASK-112 built to tell an author who misspelled one which names DO
  work. Derived rather than written out, since the list it used to
  hard-code stopped being true the moment TASK-119 added two names -- in
  the one message whose whole job is to be accurate about it."
  (str/join "/" (sort overlay-directive-names)))

(def annotation-colors
  "The `color=` values an annotation recognizes (doc-8 sec2). A CLOSED
  set, and one outside it is diagnosed HERE, where the author wrote it,
  rather than in an emitter -- which is why both emitters may safely fall
  back to yellow for a value that reaches them some other way.

  Public because it is one vocabulary that three namespaces have to
  agree on -- this one diagnoses it, and both emitters style it -- and
  `haselnuss.cross-format-test` pins that agreement rather than leaving
  three copies to drift apart."
  #{"yellow" "green" "blue" "pink"})

(def ^:private annotation-edges
  "The `at=` values a sticky note recognizes: which edge of the matched
  run the balloon sits at."
  #{"start" "end"})

(def ^:private annotation-body-numbered-types
  "The node types that TAKE A NUMBER, and which a sticky note's body may
  therefore not contain anywhere in its subtree (doc-8 sec2's numbering
  invariant).

  An annotation must not renumber the document it annotates. Anything
  here inside a sticky note's body would take a number out of the base
  document's own sequence and push everything after it along -- exactly
  the bug TASK-54 fixed for front matter.

  `:note` is in the set because a footnote number is a number like any
  other. The four Block types are not the whole story on their own,
  though, which is why `numbered-in-body?` also looks for an `attr.id`:
  `number-block` numbers by ID PREFIX on ANY Block, never by `:t` -- a
  `:::{theorem #thm:inner}` or a `:::{listing #lst:x}` in a body numbers
  and shifts the document just as a Figure does, and neither is a type
  this set could name without naming every registered directive."
  #{:figure :table :math-block :section :note})

(defn- numbered-in-body?
  "Whether `block` would take a number, or contains anything that would,
  anywhere in its own subtree.

  The SUBTREE is what matters, and checking only the top level is how
  this was wrong: a Figure inside a block quote, inside a list item,
  inside a nested directive body, or a footnote anywhere in the body each
  numbered and shifted the base document with no diagnostic at all
  (found by review). `tree-seq` rather than a walker, because this is a
  read-only scan for ANY node of a shape, so it needs none of the
  walker's per-position knowledge and cannot fall behind the schema."
  [block]
  (boolean (some (fn [node]
                   (and (map? node)
                        (or (contains? annotation-body-numbered-types (:t node))
                            (some? (get-in node [:attr :id])))))
                 (tree-seq coll? seq block))))

(defn- overlay-directive-block?
  "True when `block` is a top-level overlay directive this pass recognizes
  -- `prepend`/`append` (sec9 step 0.5's content-injection convention,
  TASK-83.1), `describe` (TASK-83.2's flat-metadata convention), or
  `highlight`/`sticky-note` (TASK-119's annotation convention, doc-8
  sec4). Anything else -- a paragraph, a section, an unrelated or
  misspelled directive name -- is unrecognized top-level overlay content
  (see `overlay-plan`)."
  [block]
  (and (= :directive (:t block)) (contains? overlay-directive-names (:name block))))

(defn- annotation-nth
  "The `nth=` prop parsed to a 1-based integer, `:missing` when absent,
  or nil when present but not a number -- three outcomes rather than two,
  so `annotation-entry` can tell \"the author did not disambiguate\" from
  \"the author tried to and wrote something that is not an index\"."
  [props]
  (let [raw (get props "nth")]
    (cond
      (str/blank? raw) :missing
      (re-matches #"-?\d+" raw) (parse-long raw)
      :else nil)))

(def ^:private annotation-anchor-props
  "The props that say WHERE an annotation goes, as opposed to what it is
  (`annotation-attr`)."
  #{"target" "text" "before" "after" "nth"})

(defn- recognised-annotation-props
  "Every prop name a `highlight`/`sticky-note` directive of kind `kind`
  recognises. `at=` and `style=` are the sticky note's alone -- one says
  which edge a balloon sits at and the other which shape it takes, and a
  highlight is not a balloon."
  [kind]
  (cond-> (into annotation-anchor-props ["color" "author"])
    (= :sticky-note kind) (into ["at" "style"])))

(defn- unrecognised-annotation-props
  "The prop names on this directive that no annotation recognises, sorted.

  Diagnosed rather than ignored, for the same reason a misspelled
  DIRECTIVE name is (TASK-112): `colour=green` did exactly nothing and
  said nothing, so an author saw an unstyled annotation and no reason for
  it. This is the one place that mistake is visible."
  [kind props]
  (sort (remove (recognised-annotation-props kind) (keys props))))

(defn- annotation-attr
  "The `Attr` the annotation NODE carries into the document: the
  directive's own recognised annotation props (`color=`, `author=`,
  and `style=` for a sticky note, which `haselnuss.emit.latex` reads),
  and nothing else.

  Deliberately not the directive's own `attr` wholesale: `target=`,
  `text=`, `before=`, `after=`, `nth=` and `at=` are instructions to THIS
  pass about where the annotation goes, not properties of the annotation,
  and an emitter that wrote them out as HTML attributes would be
  publishing the overlay's own bookkeeping into the document. The
  directive's own id is discarded and diagnosed by `overlay-plan`, like
  every other overlay directive's."
  [props]
  {:classes []
   :props (select-keys props ["color" "author" "style"])})

(defn- annotation-body
  "`[kept dropped]` for a sticky note's own `blocks`: the Blocks a PDF
  or HTML balloon may carry, and the ones dropped to keep doc-8's
  numbering invariant (`numbered-in-body?`) -- which is a property of a
  block's whole SUBTREE, not of its own type."
  [blocks]
  [(into [] (remove numbered-in-body?) blocks)
   (into [] (filter numbered-in-body?) blocks)])

(defn- annotation-entry
  "The plan entry for one `highlight`/`sticky-note` overlay directive, or
  nil when `warn!` was called instead and the annotation dropped.

  Every refusal here is doc-8's own rule that an annotation applied
  wrongly is a wrong document: a quote that names nothing, a colour or an
  edge outside the recognised set, an empty balloon, an `nth=` that is
  not an index. Each is diagnosed BY NAME and drops only this one
  annotation, exactly as `prepend`'s own diagnostics drop only their own
  directive."
  [kind block target warn!]
  (let [props (get-in block [:attr :props])
        text (get props "text")
        color (get props "color")
        at (get props "at")
        parsed-nth (annotation-nth props)
        [kept dropped] (annotation-body (:blocks block))
        drop! (fn [diagnostic-type message]
                (warn! {:type diagnostic-type
                        :directive (name kind)
                        :target target
                        :quote text
                        :message message})
                nil)]
    (cond
      (str/blank? text)
      (drop! :annotation-missing-text
             (str "a \"" (name kind) "\" directive for \"" target "\" has no text= (or it is"
                  " blank); there is nothing to anchor it to, so it was dropped"))

      (and (some? color) (not (contains? annotation-colors color)))
      (drop! :annotation-unknown-color
             (str "color=" color " is not one of "
                  (str/join ", " (sort annotation-colors))
                  "; the annotation for \"" text "\" was dropped"))

      (and (some? at) (not (contains? annotation-edges at)))
      (drop! :annotation-unknown-edge
             (str "at=" at " is not one of " (str/join ", " (sort annotation-edges))
                  "; the annotation for \"" text "\" was dropped"))

      (and (some? at) (= :highlight kind))
      (drop! :annotation-prop-not-applicable
             (str "at= says which edge of the matched run a BALLOON sits at, and a"
                  " \"highlight\" marks a run rather than sitting at an edge of it -- a"
                  " highlight with a body always places its note immediately after the"
                  " marked run; the annotation for \"" text "\" was dropped"))

      (and (some? (get props "style")) (= :highlight kind))
      (drop! :annotation-prop-not-applicable
             (str "style= chooses between a PDF annotation and a footnote for a BALLOON, and"
                  " a \"highlight\" is neither; the annotation for \"" text "\" was dropped"))

      (and (some? (get props "style"))
           (not (contains? #{"annotation" "footnote"} (get props "style"))))
      (drop! :annotation-unknown-style
             (str "style=" (get props "style") " is not one of annotation, footnote; the"
                  " annotation for \"" text "\" was dropped"))

      (seq (unrecognised-annotation-props kind props))
      (drop! :annotation-unknown-prop
             (str "this \"" (name kind) "\" directive carries "
                  (str/join ", " (map #(str % "=") (unrecognised-annotation-props kind props)))
                  ", which " (if (= 1 (count (unrecognised-annotation-props kind props)))
                               "is not a prop" "are not props")
                  " an annotation recognises (a misspelled color= or author= would otherwise"
                  " be silently ignored); the annotation for \"" text "\" was dropped"))

      (nil? parsed-nth)
      (drop! :annotation-invalid-nth
             (str "nth=" (get props "nth") " is not a whole number; the annotation for \""
                  text "\" was dropped"))

      (and (= :sticky-note kind) (empty? (:blocks block)))
      (drop! :annotation-empty-sticky-note
             (str "a \"sticky-note\" directive for \"" text "\" has no body; an empty balloon"
                  " says nothing, so it was dropped"))

      ;; Both kinds, not just `:sticky-note`. A `highlight` WITH a body
      ;; is a highlight plus a note, and when the numbering rule empties
      ;; that body the note has nothing to say -- so the highlight was
      ;; applied alone and the author's comment vanished, while the
      ;; warning said "the rest of the body was kept" (found by review).
      ;; Dropping the whole directive is the right answer for both: an
      ;; author who wrote a comment and got a bare mark has lost the
      ;; thing they were actually trying to say.
      (and (seq (:blocks block)) (empty? kept))
      ;; One message rather than two: the per-block warnings below would
      ;; each say a block was dropped "and the rest of the body kept",
      ;; which is not what happens when there IS no rest. So this names
      ;; what emptied it itself.
      (drop! :annotation-empty-sticky-note
             (str "a \"" (name kind) "\" directive for \"" text "\" has nothing left in its"
                  " body once its "
                  (str/join ", " (distinct (map (comp name :t) dropped)))
                  " -- which would have renumbered the document -- was dropped; the whole"
                  " annotation was dropped rather than leaving a"
                  (if (= :highlight kind) " mark with no comment on it" "n empty balloon")))

      :else
      (do
        (doseq [block' dropped]
          (warn! {:type :annotation-body-unsupported-block
                  :node-type (:t block')
                  :target target
                  :quote text
                  :message (str "this annotation's body holds a " (name (:t block'))
                                " that would take a number out of the document's own sequence"
                                " and push everything after it along -- an annotation must not"
                                " renumber the document it annotates -- so that block was"
                                " dropped; the rest of the body was kept")}))
        {:kind kind
         :anchor (cond-> {:text text :target target}
                   (get props "before") (assoc :before (get props "before"))
                   (get props "after") (assoc :after (get props "after"))
                   (int? parsed-nth) (assoc :nth parsed-nth))
         :attr (annotation-attr props)
         :blocks kept
         :at (if (= "start" at) :start :end)}))))

(defn- overlay-plan
  "Walks every overlay source's own top-level Blocks ONLY (never
  recursing into a directive's nested Blocks, which are the content to
  inject, not more directives) building `{target-id {:prepend [Block]
  :append [Block] :props {k v}}}` plus a `{:warnings [...]}` collection,
  in `sources`' own order then each source's in-file order -- which is
  exactly the file-order-then-in-file-order accumulation AC #3 asks for,
  since a later `(fnil into [])`/`(fnil merge {})` for the same key
  always appends/overwrites.

  `sources` is a seq of Block vectors, one per overlay file, already
  include-expanded by the caller (`haselnuss.cli/load-overlays!`).

  `prepend`/`append` accumulate their own nested `:blocks` under `:prepend`/
  `:append`; `describe` merges every prop other than `target` from its own
  `attr.props` into `:props`, via a plain `merge` -- a later `describe`
  aimed at the same id and the same prop key wins over an earlier one AT
  THIS STAGE (this pass has no notion yet of what the base document
  already carries), same last-wins accumulation order as `prepend`/
  `append` (TASK-110: winner unchanged, but no longer SILENT -- see
  `:describe-prop-overwritten` below). The other prop collision doc-5/
  AC #3 asks to be diagnosed -- a `describe` prop colliding with a value
  already set on the TARGET NODE itself, e.g. a value the base document's
  own author wrote -- can only be checked once splicing has found that
  node, so `:overlay-prop-overwritten` is produced by `splice-overlay-block`
  (see `merge-overlay-props`), not here. A `describe` with `target=` but no
  other props contributes nothing to the plan at all -- there is nothing to
  merge, so no `:props` entry is created for that id, and (unless some
  OTHER overlay directive also targets it) `apply-overlays` reports it as a
  `:dangling-overlay-target` like any other id nothing ends up matching.

  Four non-fatal diagnostics, each dropping only the offending block (or,
  for `:describe-prop-overwritten`, only the offending KEY -- the rest of
  that directive's props still merge) rather than the rest of the
  overlay (this codebase's own diagnose-don't-silently-drop convention,
  `expand-includes`'s own precedent), shared across all three recognized
  directive names except where noted:
  - `:overlay-directive-id-ignored` -- a `prepend`/`append`/`describe`
    directive that carries its OWN `attr.id` (e.g. `:::{prepend #foo
    target=ch:x}`): that id is discarded once the directive node itself
    is consumed by splicing, so flagging it here is the only chance to
    tell an author it can never be referenced. Non-fatal: the directive
    is still processed for its `target=`.
  - `:overlay-missing-target` -- `target=` absent or blank (a typo like
    `taget=`, or a bare `:::{prepend}`), distinct from a target that IS
    named but does not resolve to anything (`:dangling-overlay-target`,
    diagnosed by `apply-overlays` once splicing is done, since only it
    knows what the base document actually contains).
  - `:overlay-unexpected-content` -- any top-level block that is not a
    recognized directive (`overlay-directive-block?`) -- including a
    directive whose name is not `prepend`/`append`/`describe` at all.
    This is what keeps \"the base document never sees a stray paragraph
    from an overlay file\" airtight: dropped here, never merged in.
    TASK-112: when the dropped block IS a `:directive` (a misspelled or
    unrelated name, e.g. `:::{sprinkle target=ch:intro}`), the message
    names it and, when it carries one, its `target=` -- the only string
    an author has to search their overlay files for. A stray paragraph
    has no name to add, so that case is unchanged.
  - `:describe-prop-overwritten` -- `describe`-only: a prop key already
    written into the plan for this `target` by an EARLIER `describe`
    directive (own file or an earlier `--overlay`) is about to be
    overwritten by a later one. TASK-110: before this, the pre-splice
    `merge` above made this silent -- indistinguishable, at build-output
    time, from an author never having written the earlier value at all,
    unlike the exact same kind of collision against the base document's
    own value, which `:overlay-prop-overwritten` already caught. The
    later value still wins (accumulation order, unchanged) -- this is a
    disclosure, not a merge policy change."
  [sources]
  (let [warnings (volatile! [])
        warn! (fn [w] (vswap! warnings conj w))
        plan (volatile! {})]
    (doseq [blocks sources
            block blocks]
      (if (overlay-directive-block? block)
        (let [kind (keyword (:name block))
              target (get-in block [:attr :props "target"])
              own-id (get-in block [:attr :id])]
          (when own-id
            (warn! {:type :overlay-directive-id-ignored
                    :id own-id
                    :directive (:name block)
                    :message (str "the id \"" own-id "\" on this \"" (:name block)
                                  "\" directive is discarded once its content is spliced into"
                                  " its target -- it can never be referenced")}))
          (if (str/blank? target)
            (warn! {:type :overlay-missing-target
                    :directive (:name block)
                    :message (str "a \"" (:name block) "\" directive has no target= (or it is"
                                  " blank); its content was dropped")})
            (case kind
              (:prepend :append)
              (vswap! plan update-in [target kind] (fnil into []) (:blocks block))
              (:highlight :sticky-note)
              (when-let [entry (annotation-entry kind block target warn!)]
                (vswap! plan update-in [target :annotations] (fnil conj []) entry))
              :describe
              (let [extra-props (dissoc (get-in block [:attr :props]) "target")]
                (when (seq extra-props)
                  (let [already-set (get-in @plan [target :props] {})]
                    (doseq [k (keys extra-props)]
                      (when (contains? already-set k)
                        (warn! {:type :describe-prop-overwritten
                                :id target
                                :key k
                                :message (str "describe overlay prop \"" k "\" for \"" target
                                              "\" was set by an earlier describe overlay"
                                              " directive too -- the earlier value was"
                                              " overwritten")}))))
                  (vswap! plan update-in [target :props] (fnil merge {}) extra-props))))))
        (if (= :directive (:t block))
          (let [target (get-in block [:attr :props "target"])]
            (warn! {:type :overlay-unexpected-content
                    :node-type (:t block)
                    :directive (:name block)
                    :target target
                    :message (str "unexpected top-level directive \"" (:name block) "\""
                                  (when-not (str/blank? target) (str " (target=" target ")"))
                                  " in an overlay file (only " recognized-overlay-names
                                  " directives are recognized there); it was dropped")}))
          (warn! {:type :overlay-unexpected-content
                  :node-type (:t block)
                  :message (str "unexpected top-level " (name (:t block)) " in an overlay file"
                                " (only " recognized-overlay-names " directives are recognized"
                                " there); it was dropped")}))))
    {:plan @plan :warnings @warnings}))

(def ^:private describable-block-types
  "The Block `:t` variants a `describe` directive's `target=` may match
  (TASK-83.2, doc-5 section 2.2's own enumeration: \"any node carrying
  attr.id -- Section, Figure, Table, MathBlock, referenceable Directive\").
  Deliberately narrower than every Block variant `ast.clj` lets carry an
  `:attr` with an `:id` -- List/CodeBlock/BlockQuote do too (confirmed
  against `ast.clj`'s `::block` schema, and independently against
  `haselnuss.emit.latex/anchor-bearing-block-types`, which includes them
  as LaTeX label anchors) -- but doc-5's own text bounds `describe`'s
  target set to exactly these five, the same set `prepend`/`append`'s
  `:section`-only match is a strict subset of. Inline `:attr`-carriers
  (`:span`/`:link`/`:image`) are excluded for the same reason `emit/latex`
  gives them: they are not this kind of referenceable/anchorable node
  either."
  #{:section :figure :table :math-block :directive})

(defn- merge-overlay-props
  "`block`'s own `attr.props` with `props` (a `describe` plan entry's
  merged prop map, `overlay-plan`'s `:props`) merged in, one key at a
  time. A key already present in `block`'s OWN `attr.props` -- i.e. one
  the base document itself set, or an earlier overlay pass already wrote
  via a DIFFERENT call to this fn -- is a non-fatal
  `:overlay-prop-overwritten` diagnostic (doc-5 section 2.2, AC #3)
  rather than a silent overwrite: `block`'s own value is kept and the new
  one is dropped, the same drop-the-offending-write-and-diagnose shape
  every other overlay diagnostic here already has
  (`:overlay-missing-target`/`:overlay-unexpected-content` both drop
  rather than apply anyway)."
  [block id props ctx]
  (reduce (fn [block [k v]]
            (if (contains? (get-in block [:attr :props]) k)
              (do ((:warn! ctx) {:type :overlay-prop-overwritten
                                 :id id
                                 :key k
                                 :message (str "describe overlay prop \"" k "\" for \"" id
                                               "\" was not applied -- \"" id "\" already has"
                                               " its own value for that prop")})
                  block)
              (assoc-in block [:attr :props k] v)))
          block
          props))

(declare splice-overlay-block splice-overlay-blocks)

(defn- splice-overlay-inlines
  "`splice-overlay-block` applied wherever an Inline can hold a Block --
  the same traversal `expand-inlines` runs, and now literally the same
  code (`rebuild-inline-children`), for the same reason: a Section tucked
  inside a footnote is still a Section an overlay might target."
  [inlines ctx]
  (mapv (fn [inline]
          (rebuild-inline-children
           inline
           {:blocks-fn #(splice-overlay-blocks % ctx)
            :inlines-fn #(splice-overlay-inlines % ctx)}))
        inlines))

(defn- annotation-nodes
  "The `{:highlight :sticky-note :at}` map `haselnuss.anchor/apply-
  annotation` takes, built from one plan entry.

  A `highlight` WITH a body is a highlight PLUS a sticky note -- doc-8's
  Acrobat \"highlight with a comment\" shape, built from the two node
  types rather than from a third -- so both keys are filled and the note
  lands immediately after the wrapping node. A `highlight` with no body
  places only the highlight; a `sticky-note` places only the note."
  [{:keys [kind attr blocks at]}]
  (cond-> {:at at}
    (= :highlight kind) (assoc :highlight {:t :highlight :inlines [] :attr attr})
    (or (= :sticky-note kind) (and (= :highlight kind) (seq blocks)))
    (assoc :sticky-note {:t :sticky-note :blocks blocks :attr attr})))

(defn- apply-annotations
  "Every annotation planned for `block`'s own id applied to it in turn,
  in `--overlay` order then in-file order (`overlay-plan`'s own
  accumulation), each anchored by its quote inside `block` itself --
  which is what `target=` is for, and what lets a quote stay short.

  Applied AFTER this block's own `prepend`/`append`/`describe`, so an
  annotation sees the section as the overlays leave it rather than a
  half-built one. Every `haselnuss.anchor` diagnostic, at resolve time or
  at apply time, is passed straight through to `ctx`'s own `warn!` -- so
  a stale quote is a non-fatal build warning naming the quote and the
  target, and the build still exits 0 (doc-8 sec1: an annotation layer
  that goes stale and says so is a layer you can fix; one that goes stale
  silently is worse than none)."
  [block annotations ctx]
  (reduce (fn [block' annotation]
            (let [{matched :range diagnostics :diagnostics}
                  (anchor/resolve-anchor block' (:anchor annotation))]
              (run! (:warn! ctx) diagnostics)
              (if matched
                (let [applied (anchor/apply-annotation block' matched
                                                       (annotation-nodes annotation))]
                  (run! (:warn! ctx) (:diagnostics applied))
                  (:document applied))
                block')))
          block
          annotations))

(defn- splice-overlay-block
  "One Block of the BASE document, recursed into first (so a nested
  target is patched too, and a target nested several levels down splices
  exactly as one at the top would), then patched at itself against
  `entry`, the plan entry (if any) for its own `attr.id` (`ctx` is
  `{:plan plan :matched! (fn [id]) :warn! (fn [diagnostic])}`, `matched!`
  recording that the id was found so `apply-overlays` can diagnose the
  ones that never were; `warn!` collecting `merge-overlay-props`' own
  `:overlay-prop-overwritten` diagnostics).

  Two independent things can happen at a matching node, and either, both,
  or neither may apply depending on what kind of Block it is and what
  `entry` actually holds:
  - `prepend`/`append` splice into `:blocks` ONLY when the node is a
    `:section` (doc-5 section 1's own restriction) and `entry` carries
    `:prepend`/`:append` content.
  - `describe`'s props merge into `:attr.props` (`merge-overlay-props`)
    ONLY when the node's `:t` is in `describable-block-types` and `entry`
    carries non-empty `:props`.

  `matched!` fires per aspect actually applied, not merely because
  `entry` exists for the id -- a `prepend`/`append` entry that names a
  non-`:section` id (an author error `describe`'s own wider match must
  not paper over) still reads as dangling even if that same id also
  happens to match a `describe` entry's props elsewhere.

  Covers `block-children`'s FULL variant set, including a Figure's
  `:content` -- deliberately UNLIKE `expand-blocks`, which skips it for
  its own reason (one Block slot cannot hold a splice of many). That
  reason does not apply to a rebuild walk like this one (each base Block
  maps to exactly one, possibly modified, Block back), and a target could
  in principle sit inside a Figure's `:content` (schema-unrestricted,
  `ast.clj:151-155`) -- so this still has to reach in and recurse, or a
  target nested there would wrongly read as dangling. Skipping this
  container is exactly the traversal-drift `block-children`/
  `expand-blocks` already showed real bugs from (TASK-38 review finding
  6); `overlay-splice-reaches-every-container-test` pins the full set."
  [block ctx]
  (let [block (case (:t block)
                (:section :block-quote :directive)
                (update block :blocks splice-overlay-blocks ctx)
                :list (update block :items (partial mapv #(splice-overlay-blocks % ctx)))
                :figure (update block :content splice-overlay-block ctx)
                :table (-> block
                           (update :head update :cells
                                   (partial mapv #(update % :blocks splice-overlay-blocks ctx)))
                           (update :rows
                                   (partial mapv
                                            (fn [row]
                                              (update row :cells
                                                      (partial mapv #(update % :blocks
                                                                             splice-overlay-blocks
                                                                             ctx)))))))
                :para (update block :inlines splice-overlay-inlines ctx)
                block)
        id (get-in block [:attr :id])
        entry (when id (get (:plan ctx) id))
        prepend-append? (and entry (= :section (:t block))
                             (or (seq (:prepend entry)) (seq (:append entry))))
        describe? (and entry (contains? describable-block-types (:t block)) (seq (:props entry)))
        block (if prepend-append?
                (do ((:matched! ctx) id :prepend-append)
                    (update block :blocks
                            (fn [own] (into (vec (:prepend entry)) (into own (:append entry))))))
                block)
        block (if describe?
                (do ((:matched! ctx) id :describe)
                    (merge-overlay-props block id (:props entry) ctx))
                block)
        ;; Unlike prepend/append (Section-only) and describe
        ;; (`describable-block-types`), an annotation is accepted on ANY
        ;; node carrying the id: `target=` scopes the quote search rather
        ;; than naming what is patched, so anything with a subtree to
        ;; search in is a legitimate target. `matched!` fires whether or
        ;; not the quote resolved -- the TARGET did, and reporting it as
        ;; a dangling target as well as a dangling anchor would name the
        ;; same mistake twice in two different vocabularies.
        block (if (and entry (seq (:annotations entry)))
                (do ((:matched! ctx) id :annotations)
                    (apply-annotations block (:annotations entry) ctx))
                block)]
    block))

(defn- splice-overlay-blocks
  "`splice-overlay-block` mapped over `blocks` -- a REBUILD walk (`mapv`,
  one Block in for one Block out), unlike `expand-blocks`'s `mapcat` (one
  `:include` in for zero-to-many Blocks out): splicing happens at a
  different tree location than collection, so nothing here replaces a
  node in place the way an include does."
  [blocks ctx]
  (mapv #(splice-overlay-block % ctx) blocks))

(defn apply-overlays
  "Splices every `prepend`/`append`/`describe` directive collected from
  `sources` (a seq of Block vectors, one per overlay file -- see
  `overlay-plan`) into `document`, returning `{:document document'
  :warnings warnings}` (TASK-83.1, TASK-83.2).

  With no sources (nil or `[]`, the default an absent `--overlay` flag
  leaves `resolve-document` with) this is a no-op returning `document`
  unchanged and no warnings -- the headline invariant doc-5 requires: a
  base document builds byte-for-byte identically whether or not an
  overlay file exists on disk, as long as it is never named.

  `warnings` is `overlay-plan`'s own (`:overlay-directive-id-ignored`/
  `:overlay-missing-target`/`:overlay-unexpected-content`/
  `:describe-prop-overwritten`), in the order it produced them, followed
  by every `:overlay-prop-overwritten` diagnostic `merge-overlay-props`
  produced during splicing, in traversal order, followed by one
  `:dangling-overlay-target` diagnostic per plan
  key that matched no describable node in `document` at all -- checked
  only after splicing finishes, since only then is it certain nothing in
  `document` (including a node nested inside another overlay's own
  injected content) ever carried that id.

  A target id shared by two or more nodes -- a pre-existing id collision
  in the base document -- gets the overlay applied to ALL of them:
  deterministic, no hidden first-match-wins state, though also no
  diagnostic of its own here, since `structural-diagnostics`'s own
  `duplicate-id-diagnostics` -- which `resolve-document` runs later,
  over this pass's own output -- already reports the collision."
  [document sources]
  (if (empty? sources)
    {:document document :warnings []}
    (let [{:keys [plan warnings]} (overlay-plan sources)
          collected (volatile! (vec warnings))
          matched (volatile! #{})
          ctx {:plan plan
               :matched! (fn [id aspect] (vswap! matched conj [id aspect]))
               :warn! (fn [w] (vswap! collected conj w))}
          spliced (splice-overlay-blocks (:blocks document) ctx)
          ;; PER ASPECT, not per id. `matched!` has always fired per
          ;; aspect actually applied -- `splice-overlay-block`'s own
          ;; docstring says so -- but the check here was per id, so one
          ;; aspect that matched silenced another on the same id that did
          ;; not. An annotation, which matches on ANY node carrying the
          ;; id, made that visible: adding a highlight to `fig:a` silenced
          ;; a genuinely dangling `prepend` there (found by review). One
          ;; diagnostic per id still, since an id whose every aspect
          ;; failed is one mistake however many directives named it.
          aspects-of (fn [entry]
                       (cond-> #{}
                         (or (seq (:prepend entry)) (seq (:append entry)))
                         (conj :prepend-append)
                         (seq (:props entry)) (conj :describe)
                         (seq (:annotations entry)) (conj :annotations)))
          dangling (into []
                         (keep (fn [[id entry]]
                                 ;; EVERY aspect, not any: an id is only
                                 ;; fully resolved when each directive
                                 ;; that named it found what it needed.
                                 (when-not (every? #(contains? @matched [id %])
                                                   (aspects-of entry))
                                   {:type :dangling-overlay-target
                                    :id id
                                    ;; The kinds named are prepend/append/
                                    ;; describe's own, which is what this
                                    ;; message has always been about. An
                                    ;; annotation accepts ANY node
                                    ;; carrying the id, so naming that
                                    ;; list at one would have sent its
                                    ;; author looking for the wrong
                                    ;; mistake (found by review).
                                    :message (str "overlay target \"" id "\" does not resolve to"
                                                  " a node in the base document that the"
                                                  " directives naming it can patch"
                                                  " (prepend/append need a Section; describe"
                                                  " needs a Section, Figure, Table, MathBlock or"
                                                  " referenceable Directive; an annotation"
                                                  " needs only a node carrying that id)")})))
                         plan)]
      {:document (assoc document :blocks spliced)
       :warnings (into @collected dangling)})))

(defn- front-matter-block?
  "True when Block `block` is a front-matter directive per
  `front-matter-names` -- a set of directive-name strings, injected
  rather than known here (TASK-54).

  Injected because this namespace deliberately knows no directive names
  at all: which ones are front matter is
  `haselnuss.extensions.front-matter`'s to say, exactly as which LaTeX
  environment a directive maps to is an emitter's (see
  `structural-diagnostics`' own `:directive-kinds` for the same
  argument). A caller passing none gets today's behaviour, where a
  directive is an ordinary block like any other."
  [front-matter-names block]
  (and (= :directive (:t block))
       (contains? front-matter-names (:name block))))

(defn- body-view
  "`document` with its top-level front-matter blocks removed -- the view
  every pass that defines the NUMBERED BODY runs over (TASK-54).

  This is where \"an abstract is never numbered, never appears in the
  TOC and is not a cross-reference target\" is actually enforced, and it
  has to be here rather than in an emitter. Front-matter blocks used to
  be lifted out at emit time, which is long after numbering and the TOC
  derivation have run: an abstract carrying `#thm:a` was numbered
  \"Theorem 1\" and answered a `@thm:a`, and a Section written inside one
  took a number from the body's own sequence and pushed everything after
  it along (both found by review). Removing them before those passes run
  makes the exclusion structural -- there is nothing to skip, because
  there is nothing there.

  Cross-reference and citation resolution deliberately do NOT use this
  view: an abstract may cite, and may refer to a section of the body,
  and both must work. Only numbering and the derivations built on it are
  body-only."
  [document front-matter-names]
  (if (seq front-matter-names)
    (update document :blocks (partial filterv (complement
                                               (partial front-matter-block? front-matter-names))))
    document))

(defn- scope-key
  "The `:counters` map key that `kind`'s counter is bumped under, given
  `lexicon-entry` and the current enclosing `section-path`: a `:global`
  kind counts once for the whole document; a `:section-scoped` kind counts
  separately per distinct enclosing `section-path` (so it resets whenever
  the enclosing Section changes, per sec6.1)."
  [lexicon-entry kind section-path]
  (if (= :global (:counter lexicon-entry))
    [:global kind]
    [:section section-path kind]))

(defn- assign-number
  "Bumps the counter for `kind` in `counters` (see `scope-key`) and returns
  `[own-path counters']`: `own-path` is this node's own full number path --
  `[n]` for a `:global` kind, or `section-path` with the new local count
  appended for a `:section-scoped` kind (so a Section's own path becomes
  the prefix its children compose onto, per this namespace's docstring)."
  [counters lexicon-entry kind section-path]
  (let [counter-key (scope-key lexicon-entry kind section-path)
        n (inc (get counters counter-key 0))
        own-path (if (= :global (:counter lexicon-entry))
                   [n]
                   (conj section-path n))]
    [own-path (assoc counters counter-key n)]))

(defn- sublabel-letter
  "The `a`/`b`/`c` suffix a SUBLABELED node (TASK-56) takes as its `n`th
  place within its parent -- `a`..`z`, then `aa`, `ab`, ... -- matching
  LaTeX's own `\\alph` for as long as `\\alph` works at all.

  Past 26 the two targets cannot agree, and that is deliberate rather
  than overlooked: `\\alph` fails a native build outright (\"LaTeX
  Error: Counter too large\") at the 27th panel, so there is no letter
  this could return that LaTeX would also print. Continuing the
  sequence keeps HTML numbering something a reader can follow, while
  the LaTeX build stops loudly rather than printing a different letter
  -- visibly broken beats silently disagreeing, this codebase's
  standing preference."
  [n]
  (loop [n n acc ""]
    (let [n (dec n)
          acc (str (char (+ (int \a) (mod n 26))) acc)
          n (quot n 26)]
      (if (pos? n) (recur n acc) acc))))

(defn- assign-sublabel
  "The sublabel counterpart of `assign-number`, for a numbered node whose
  nearest numbered ancestor carries the SAME kind (TASK-56 -- a
  `subfigure` panel inside a `figure`, the only shape that occurs
  today): returns `[own-path counters' letter]`, where `own-path` is
  `parent`'s own path with this node's letter appended and the letter is
  its place among its same-kind siblings under that parent.

  Counted per parent id rather than per scope, so two multi-panel
  figures in one section both start from `a`, and structural rather
  than keyed on any directive name: what makes a node a sub-thing is
  that it numbers as the same kind as the thing it sits inside, which
  is exactly what `subcaption` prints in LaTeX (`Figure 1.1a`) and
  needs no new vocabulary here to recognize."
  [counters parent kind]
  (let [counter-key [:sublabel (:id parent) kind]
        n (inc (get counters counter-key 0))
        letter (sublabel-letter n)]
    [(conj (vec (:path parent)) letter) (assoc counters counter-key n) letter]))

(defn- words-for
  "The `{:singular :template?}` word map `lexicon-entry` prints for `lang`:
  an exact `lang` match, else an `\"en\"` fallback, else whatever entry
  happens to be first, else an empty map (so a lexicon entry with no
  `:words` at all still numbers, just without a prefix word)."
  [lexicon-entry lang]
  (let [words (:words lexicon-entry)]
    (or (get words lang)
        (get words "en")
        (first (vals words))
        {})))

(defn- format-text
  "The fully formatted label text for a computed `number` string, using
  `word-info`'s `:template` (with `{n}` substituted for `number`) if
  present, else the default `\"{singular} {number}\"` (sec6.1)."
  [word-info number]
  (let [{:keys [singular template]} word-info]
    (if template
      (str/replace template "{n}" number)
      (str/trim (str singular " " number)))))

(def ^:private roman-numerals
  "`[value numeral]` pairs, largest first, for the standard subtractive
  roman-numeral algorithm `roman-numeral` greedily consumes."
  [[1000 "M"] [900 "CM"] [500 "D"] [400 "CD"]
   [100 "C"] [90 "XC"] [50 "L"] [40 "XL"]
   [10 "X"] [9 "IX"] [5 "V"] [4 "IV"] [1 "I"]])

(defn- roman-numeral
  "`n` (a positive int) as an uppercase roman numeral, e.g. `4` -> `\"IV\"`
  (TASK-82): a real `\\part` prints this way via cleveref against a
  standard class's own `\\Roman{part}`, confirmed with a real pdflatex."
  [n]
  (loop [n n acc "" pairs roman-numerals]
    (if-let [[[value numeral] & more] (seq pairs)]
      (if (>= n value)
        (recur (- n value) (str acc numeral) pairs)
        (recur n acc more))
      acc)))

(def ^:private chapter-counter-key
  "The `:counters` key the STRUCTURAL chapter counter lives under
  (TASK-53). Structural, not per-kind: it counts every level-1 `:section`
  Block in a chaptered document, labeled or not, because
  `haselnuss.emit.latex/section-command` turns every one of them into a
  `\\chapter` and LaTeX counts every `\\chapter` it is given.

  That is the whole reason it is not simply the `:ch` kind's own
  per-kind counter, which -- like every other counter here -- steps only
  for a node carrying an id. An author labels the chapters they
  cross-reference and leaves the rest bare; with a labeled-only count,
  the third chapter of such a document would compose its figures against
  \"1\" while the compiled PDF numbered them from `\\thechapter` = 3
  (found by review)."
  [:chapter])

(def ^:private part-counter-key
  "The `:counters` key the STRUCTURAL part counter lives under (TASK-82),
  the exact `chapter-counter-key` idea one division up: it counts every
  level-1 `:section` Block in a PARTED document, labeled or not, because
  a real `\\part`-bearing class counts every `\\part` it is given the
  same unconditional way it counts `\\chapter`. Unlike the chapter
  counter, nothing downstream composes onto this one -- a part never
  resets or scopes the chapter/figure/table/equation counters (confirmed
  with a real pdflatex, see `number-block`) -- so its only job is giving
  an unlabeled part the same number a labeled one would have gotten."
  [:part])

(defn- number-block
  "Recursively numbers `block` and its descendants, threading `state`
  (`{:section-path :chapter-path :counters :entries}`) through document
  order. Only a
  `:section`-typed node whose own kind resolved to a `:section-scoped`
  lexicon entry extends `:section-path` for its own children -- every other
  node (a `:fig`/`:tbl`/`:eq`/`:thm`/custom-kind target, or an unlabeled/
  unrecognized-kind Section) is transparent for path purposes, so
  fig/tbl/eq numbering is independent of section numbering (AC #2) while
  Section numbering still nests by AST structure, not by `:t`, alone (AC
  #1).

  A chaptered document (`ctx`'s own `:chapters`, TASK-53) adds a second
  path beside that one, and this is the part that took two attempts to
  get right. `:chapter-path` is `[]` until the first level-1 Section and
  `[n]` inside chapter n, tracked STRUCTURALLY -- every level-1 Section
  steps it, whether or not it carries an id (see
  `chapter-counter-key`). Three things then follow from it, each
  matching what `report`/`book` do with the same document:

  - A non-Section node -- a figure, a table, an equation, a directive
    numbering as one of those -- composes its `:section-scoped` number
    onto `:chapter-path` rather than onto the whole `:section-path`. A
    chaptered class resets those counters per chapter and prints
    `\\thechapter.\\arabic{figure}`, so the third figure of chapter 5 is
    Figure 5.3 wherever in the chapter it sits, never Figure 5.2.3.
  - Composing onto a path tracked separately, rather than onto
    `(take 1 section-path)`, is what keeps a labeled section OUTSIDE any
    chapter from colliding with chapter 1 (found by review): a
    `#sec:preface` before the first `\\chapter` has its own path `[1]`,
    which truncation could not tell apart from chapter 1's, so the two
    shared one figure counter and every figure in chapter 1 was shifted.
    Outside a chapter `:chapter-path` is `[]`, and a figure there
    numbers as a bare \"1\" -- exactly what report prints, its own
    `\\thefigure` being guarded on `\\ifnum \\c@chapter>\\z@`.
  - A chapter's own number is that structural count, not its kind's
    per-kind one, so a document whose chapters are only partly labeled
    still agrees with the PDF about which chapter it is in. Its
    children's `:section-path` starts from the same number, so a section
    inside an unlabeled chapter numbers 2.1 rather than skipping the
    chapter entirely.

  None of this is reachable for a document that did not opt in:
  `:chapters` is false, `:chapter-path` is never consulted, and every
  number is composed exactly as before (AC #4).

  A PARTED document (`ctx`'s own `:parts`, TASK-82) sits one division
  above the chapter and, once its own structural number is assigned, is
  otherwise a no-op for everything above: `:chapter-path`/`:section-path`
  are neither reset nor seeded by entering or leaving a Part, confirmed
  against a real pdflatex on the equivalent `report` document -- chapters
  keep counting continuously across parts (a chapter after Part II still
  numbers 4, never restarting at 1), and a float sitting directly under a
  `\\part` before its first `\\chapter` still carries whatever
  `\\c@chapter` the PREVIOUS part left set, exactly what inheriting
  `:chapter-path` unchanged already produces here. `:parts` shifts which
  level IS the chapter -- level 2, not level 1 -- via `chapter-level`;
  level 1 becomes `part?`, stepped by its own STRUCTURAL `part-counter-key`
  (`chapter-counter-key`'s own reasoning, one division up: an unlabeled
  `\\part` still numbers in the PDF). A part's `:number-format :roman`
  lexicon entry (`default-lexicon`'s `:part`) is what turns its digit
  path into \"I\"/\"II\"/... instead of the arithmetic every other kind
  prints."
  [{:keys [lexicon lang chapters parts sublabel-names] :as ctx} state block]
  (let [id (some-> block :attr :id)
        kind (id->kind id)
        entry (get lexicon kind)
        section-path (:section-path state)
        chapter-path (:chapter-path state)
        section? (= :section (:t block))
        section-scoped? (= :section-scoped (:counter entry))
        chapter-level (if parts 2 1)
        chapter? (and chapters section? (= chapter-level (:level block)))
        part? (and parts section? (= 1 (:level block)))
        chapter-n (when chapter? (inc (get (:counters state) chapter-counter-key 0)))
        part-n (when part? (inc (get (:counters state) part-counter-key 0)))
        counters (cond-> (:counters state)
                   chapter? (assoc chapter-counter-key chapter-n)
                   part? (assoc part-counter-key part-n))
        ;; What a `:section-scoped` number here composes onto: a chapter
        ;; or a part each start a path rather than continuing one (their
        ;; own number is never composed against anything), another
        ;; Section continues the section path, and anything else
        ;; continues the chapter path in a document with chapters and the
        ;; section path otherwise.
        own-scope (cond
                    chapter? []
                    part? []
                    section? section-path
                    chapters chapter-path
                    :else section-path)
        ;; A numbered node whose nearest numbered ancestor carries the
        ;; same kind is a SUBLABEL of it (TASK-56), not a new number of
        ;; its own: a `subfigure` panel inside a `figure` is Figure
        ;; 1.1a, and takes no figure number away from the figure after
        ;; it. Recognized structurally -- same kind, nested -- rather
        ;; than by directive name, so it needs no new lexicon
        ;; vocabulary and composes with chapter scoping for free (the
        ;; parent's number is whatever the parent already got).
        parent (:parent-label state)
        ;; What makes a node a sublabel, and every part is load-bearing
        ;; (the name check was found missing by review):
        ;;
        ;; - its own directive name is one the caller DECLARED a
        ;;   sub-thing (`sublabel-names`; `haselnuss.cli` passes the
        ;;   `:sub` entries of the emitter's own environment table).
        ;;   Same-kind nesting alone is not enough: a theorem inside a
        ;;   theorem is ordinary, legal and numbered 1, 2 by every
        ;;   LaTeX class, and lettering the inner one 1a made this pass
        ;;   disagree with a native build about every theorem after it.
        ;; - its nearest numbered ancestor carries the SAME kind, so a
        ;;   panel wearing a `#tbl:` id is numbered as the table it
        ;;   says it is rather than lettered as a figure.
        ;; - that ancestor is directly above it (see `:parent-label`).
        ;;
        ;; Never a Section: sections nest through `:section-path`
        ;; already, and a `#sec:` subsection inside a `#sec:` section
        ;; would otherwise become "Section 1a" instead of "Section
        ;; 1.1". The rule is for the nodes that have no nesting
        ;; mechanism of their own.
        sub? (and id entry (not section?)
                  (contains? sublabel-names (:name block))
                  (= kind (:kind parent)))
        [own-path counters' sublabel]
        (cond
          (and chapter? id entry section-scoped?) [[chapter-n] counters nil]
          (and part? id entry section-scoped?) [[part-n] counters nil]
          sub? (assign-sublabel counters parent kind)
          (and id entry) (let [[path counters'] (assign-number counters entry kind own-scope)]
                           [path counters' nil])
          :else [nil counters nil])
        number (when own-path
                 (cond
                   sublabel
                   ;; The parent's own number with the letter appended
                   ;; and no separator -- "Figure 1.1a", exactly what
                   ;; `subcaption` prints and what a `\Cref` to a panel
                   ;; resolves to (confirmed with a real pdflatex).
                   (str (str/join "." (butlast own-path)) sublabel)
                   ;; A part's own path is always its single structural
                   ;; count (`[part-n]`); a `:number-format :roman` kind
                   ;; is always a top-level structural one like `:part`,
                   ;; never a composed multi-component path.
                   (= :roman (:number-format entry)) (roman-numeral (first own-path))
                   :else (str/join "." own-path)))
        entries' (if own-path
                   ;; A duplicated id (TASK-133) keeps its FIRST entry: a
                   ;; reference to it resolves, deterministically, to the
                   ;; first definition -- the one a browser's `#id` jump
                   ;; lands on -- while `duplicate-id-diagnostics`
                   ;; separately warns about the collision. The later node
                   ;; still consumes a number (counters advance above), as
                   ;; in a native build, but does not overwrite the label.
                   (if (contains? (:entries state) id)
                     (:entries state)
                     (let [word-info (words-for entry lang)]
                       (assoc (:entries state) id
                              (cond-> {:kind kind
                                       :path own-path
                                       :number number
                                       :word (:singular word-info)
                                       :text (format-text word-info number)}
                                sublabel (assoc :sublabel sublabel)))))
                   (:entries state))
        child-section-path (cond
                             ;; Unconditional for a chapter, unlike the
                             ;; labeled-only rule below: an unlabeled
                             ;; `\chapter` still numbers in the PDF, so
                             ;; its sections must still hang off it.
                             chapter? [chapter-n]
                             ;; A part's own `[part-n]` path is NEVER what
                             ;; its children compose onto -- unlike a
                             ;; chapter, a part does not seed
                             ;; `:section-path` at all (confirmed with a
                             ;; real pdflatex: entering a `\part` leaves
                             ;; `\thesection` alone). Checked ahead of the
                             ;; generic branch below, which would
                             ;; otherwise fire for a labeled part too
                             ;; (`:part` is `:section-scoped`) and wrongly
                             ;; seed it with the part's own roman-numbered
                             ;; path.
                             part? section-path
                             (and own-path section? section-scoped?) own-path
                             :else section-path)
        state' (assoc state :counters counters' :entries entries'
                      :section-path child-section-path
                      :chapter-path (if chapter? [chapter-n] chapter-path)
                      ;; The node a child of this one can be a SUBLABEL
                      ;; of: this one, when it took a number, and
                      ;; nothing otherwise.
                      ;;
                      ;; Directly above, never seen through a wrapper
                      ;; (found by review): that is the only rule an
                      ;; emitter can hold up its own end of, since it
                      ;; lays out the panels it can see, and the two
                      ;; halves agreeing on what a panel is matters
                      ;; more than reaching one buried inside a block
                      ;; quote.
                      ;;
                      ;; A Section is never one either, labeled or not:
                      ;; everything inside a section composes onto that
                      ;; section's number already, through
                      ;; `:section-path`, and a second mechanism on top
                      ;; of the first is how a Figure inside a section
                      ;; that wears a `fig:` id by mistake -- the exact
                      ;; shape `kind-role-diagnostics` warns about --
                      ;; would have become "1a" instead of "1.1" (found
                      ;; by the diagnostics test that pins that
                      ;; behaviour).
                      :parent-label (when (and own-path (not section?))
                                      {:id id :kind kind
                                       :path own-path :number number}))
        state'' (reduce (partial number-block ctx) state' (block-children block))]
    ;; `:section-path` (and `:parent-label`) revert to what THIS node saw
    ;; on entry -- tree-scoped, exactly like `report`'s own `\thesection`,
    ;; which really does reset once its enclosing scope ends. `:chapter-
    ;; path` does NOT: unlike `\thesection`, `\c@chapter` is a genuinely
    ;; global, monotonically-advancing LaTeX counter that nothing ever
    ;; rewinds, so `state''`'s own chapter-path (whatever the most
    ;; recently seen chapter left it at) has to flow onward exactly like
    ;; `:counters` already does below this node, not revert to what stood
    ;; before it (TASK-82, found by a real pdflatex: reverting here was
    ;; unreachable and therefore harmless before a chapter could be
    ;; nested two levels deep under a Part with content following it at
    ;; the PART's own level -- `\part{Applications}` then a figure before
    ;; its first `\chapter` still carried the PREVIOUS part's last
    ;; `\c@chapter`, e.g. \"Figure 1.2\", not a bare \"Figure 2\" as
    ;; reverting here produced)."
    (assoc state'' :section-path section-path :parent-label parent)))

(defn number-document
  "The numbering pass (SPEC.md sec9 step 1): assigns every labeled node in
  `document` (a `haselnuss.ast/Document`) a number from its kind's per-kind
  counter, respecting section nesting. `lexicon` defaults to
  `default-lexicon`; pass a merged-in lexicon to add or override kinds
  (TASK-11 AC #4). The prefix word is chosen from `(:lang (:meta
  document))`, defaulting to \"en\" (TASK-11 AC #3).

  Returns a label table: a map from each numbered node's `attr.id` string
  to `{:kind :path :number :word :text}`, e.g. `{\"fig:tree\" {:kind :fig
  :path [2 3] :number \"2.3\" :word \"Figure\" :text \"Figure 2.3\"}}` --
  plus `:sublabel` for a node numbered INSIDE a same-kind one (TASK-56):
  a subfigure panel is `{:kind :fig :path [2 3 \"a\"] :number \"2.3a\"
  :sublabel \"a\" :text \"Figure 2.3a\"}`, and an emitter prints its
  `:sublabel` in parentheses where it prints another node's full
  `:text` (see `haselnuss.emit.html/render-caption`). This
  does not mutate `document` or its nodes -- `haselnuss.ast` is a 1:1 port
  of SPEC.md sec4 with no numbering fields of its own -- so later resolver
  steps (TASK-12's CrossRef resolution, TASK-14's TOC) are expected to
  consult this table directly rather than fields stamped onto the AST.

  `meta.topLevelDivision` (TASK-53, extended by TASK-82) changes one
  thing here, and only for a document that opts in: with `:chapter`,
  every level-1 Section is a chapter -- counted structurally, labeled or
  not -- and a `:section-scoped` kind on a node that is not itself a
  Section composes with, and counts per, that enclosing chapter rather
  than the whole section path. This matches what a `\\chapter`-bearing
  LaTeX class does with the same document, which is what lets a
  native-mode `\\Cref` and an HTML reference print the same number; see
  `number-block` for the three consequences and the two shapes that made
  a simpler design wrong. With `:part`, every level-1 Section is instead
  a roman-numbered part and every level-2 Section a chapter, shifted down
  the same way -- but a part composes with, resets, or is composed onto
  by NOTHING else (see `number-block`'s own docstring): chapters keep
  counting continuously across parts, exactly what a real `\\part`-bearing
  class does. Every other document is numbered exactly as before.

  `sublabel-names` (TASK-56, default `#{}`) names the directives whose
  nodes take a LETTER within the numbered node directly above them
  instead of a number of their own -- `#{\"subfigure\"}` in a real
  build, passed by `haselnuss.cli` from the `:sub` entries of the
  emitter's own environment table. Injected rather than known here for
  the same reason `front-matter-names` and `directive-kinds` are: which
  directive is which is the emitter's and the extension's business, and
  this pass numbers by id prefix and structure. With the default, no
  node is ever a sublabel and numbering behaves exactly as it did
  before that concept existed.

  `front-matter-names` (TASK-54, default `#{}`) names the directives
  whose blocks are not part of the numbered body at all; they and
  everything inside them are removed before numbering starts (see
  `body-view`), so nothing in an abstract can take a number from the
  body's own sequence."
  ([document] (number-document document default-lexicon))
  ([document lexicon] (number-document document lexicon #{}))
  ([document lexicon front-matter-names] (number-document document lexicon front-matter-names #{}))
  ([document lexicon front-matter-names sublabel-names]
   (let [document (body-view document front-matter-names)
         lang (get-in document [:meta :lang] "en")
         division (get-in document [:meta :top-level-division])
         ctx {:lexicon lexicon
              :lang lang
              :sublabel-names sublabel-names
              :chapters (contains? #{:chapter :part} division)
              :parts (= :part division)}
         ;; A chaptered document starts inside chapter ZERO, not outside
         ;; any chapter, and the asymmetry is `report`'s own: a section
         ;; before the first `\chapter` sets as \"0.1\" (its
         ;; `\thesection` is `\thechapter.\arabic{section}`,
         ;; unconditionally), while a FLOAT there sets as a bare \"1\"
         ;; (its `\thefigure` guards the chapter prefix on
         ;; `\ifnum \c@chapter>\z@`). Confirmed with a real pdflatex.
         ;; Two paths are what let both hold at once: the section path
         ;; carries the 0, the chapter path stays empty until a real
         ;; chapter opens.
         init-state {:section-path (if (:chapters ctx) [0] [])
                     :chapter-path []
                     :parent-label nil
                     :counters {}
                     :entries {}}]
     (:entries (reduce (partial number-block ctx) init-state (:blocks document))))))

;; Cross-reference resolution (TASK-12, sec9 step 2)
;;
;; A `:cross-ref` inline's `:label` is already the *target id string*
;; (TASK-9's own convention -- the whole `kind:label` token is one opaque
;; string, matching `attr.id` verbatim), so resolving it is a direct lookup
;; against `number-document`'s label table: no id-parsing is needed here.
;;
;; Unlike numbering (whose targets each have a unique `attr.id`, making a
;; flat id -> entry table the natural shape), a document can hold many
;; distinct `CrossRef` *occurrences* pointing at the same target, and each
;; occurrence has no id of its own -- so resolution annotates each
;; `:cross-ref` node in place (adding `:target`/`:text`) rather than
;; returning a second side table, and returns the rewritten Document
;; alongside any dangling-reference warnings collected along the way.
;;
;; Scope limit (deliberate, mirroring this project's established practice of
;; writing interpretive calls down rather than leaving them silent): a
;; Directive's optional `:fallback` (a Fallback variant, sec4.5) is not
;; walked for `CrossRef`s. Fallback content is target-specific degradation
;; material consumed by TASK-17's later `lower` pass, orthogonal to this
;; resolution step over the document's primary content.
;;
;; Every position above is reached through `haselnuss.walk/rewrite`
;; (TASK-86), driven off the schema `haselnuss.ast/registry` itself
;; declares, rather than a hand-written recursive function per node kind --
;; see `cross-ref-scope`/`resolve-cross-refs-node`, below `resolve-cross-ref`
;; itself (unchanged by this port: `rewrite`'s own `:rewrite-fn` still calls
;; it exactly the same way, one `:cross-ref` node at a time).

(defn- resolve-cross-ref
  "Resolves a single `:cross-ref` inline node against `labels` (a
  `number-document` label table), returning `[node' warnings]` (`warnings`
  a vector, possibly empty).

  A match (TASK-12 AC #1/#2) annotates `node'` with `:target` (the resolved
  target id, for a later emitter to build a link/anchor from) and `:text`
  -- the target's full `:text` (e.g. \"Figure 2.3\") normally, or, when the
  node's own `:suppress-prefix` is set, just the bare `:number` (e.g.
  \"2.3\") with no prefix word.

  An id defined more than once (TASK-133) is not dangling: `labels`
  holds the FIRST definition's entry (see `number-block`), so the
  reference resolves to it, and the collision is reported separately as
  `:duplicate-id`.

  No match is a dangling reference (TASK-12 AC #3): `node'` gets `:target
  nil` and `:text \"??\"` (a visible placeholder, mirroring LaTeX's own
  behavior for an unresolved `\\ref`, instead of a crash), and `warnings`
  carries one `{:type :dangling-cross-ref :label :message}` map (plus
  `:node-kind` when the id is on an inline node).

  TASK-141: when `inline-ids` (an id -> node-kind map of ids written on
  INLINE nodes, see `inline-node-ids`) holds the label, the id is not
  absent -- it sits on an inline image/link/span, which is not a
  numbering target (only a lone-image paragraph becomes a figure). The
  result is the same `??` placeholder, but the message names the node
  kind instead of claiming there is no target."
  [labels inline-ids {:keys [label suppress-prefix] :as cross-ref}]
  (if-let [entry (get labels label)]
    [(assoc cross-ref
            :target label
            :text (if suppress-prefix (:number entry) (:text entry)))
     []]
    [(assoc cross-ref :target nil :text "??")
     [(if-let [kind (get inline-ids label)]
        {:type :dangling-cross-ref
         :label label
         :node-kind kind
         :message (str "dangling cross-reference: id \"" label "\" is on an inline "
                       (name kind) ", which is not referenceable; only a block "
                       "(for an image, one alone in its paragraph) can be a target")}
        {:type :dangling-cross-ref
         :label label
         :message (str "dangling cross-reference: no target with id \"" label "\"")})]]))

(def ^:private cross-ref-scope
  "`haselnuss.walk/rewrite`'s own `:scope` for `resolve-cross-refs`
  (TASK-86): every reference kind a `:cross-ref` Inline can be reached
  through -- `::block`/`::inline` for the tree itself, `::row`/`::cell` so
  a Table's own `:head`/`:rows` reach down to each cell's `:blocks`,
  `::cite-item` so a Cite's own `:items` reach their `:prefix`/`:suffix`
  (which can themselves hide a footnote, or another CrossRef -- see
  `cross-ref-nested-in-structural-positions-test`), and `::meta` so one
  `rewrite` call rooted at the Document also reaches `meta.title`. Exactly
  `haselnuss.lower`'s own `scope` (TASK-85), for exactly the same reasons:
  resolving a CrossRef has no ordering dependency on its own descendants
  (a CrossRef carries no nested Inlines of its own to resolve first) any
  more than lowering a Directive does, so nothing here needs the
  narrower, deliberately-excludes-`::cite-item` scope `resolve-citations`
  needs below (see `citation-scope`).

  `::attr`/`::col` are absent because neither schema carries a reference of
  its own to descend through either way (mirrors `haselnuss.lower`).
  `::fallback` is absent DELIBERATELY, unchanged from before this port (see
  this section's own top-of-namespace comment): a Directive's own
  `:fallback` is target-specific degradation material for `haselnuss.
  lower`'s later pass, not part of the document a CrossRef resolves
  against."
  #{::ast/block ::ast/inline ::ast/cell ::ast/row ::ast/cite-item ::ast/meta})

(defn- inline-node-ids
  "id -> node kind (`:image`, `:link`, ...) for every INLINE node under
  `document` carrying an `attr.id` (TASK-141). First occurrence wins."
  [document]
  (walk/fold {:table walk/table :scope cross-ref-scope
              :visit (fn [acc _path ref-kind node]
                       (let [id (get-in node [:attr :id])]
                         (if (and (= ref-kind ::ast/inline) id (not (contains? acc id)))
                           (assoc acc id (:t node))
                           acc)))}
             {} ::ast/document document))

(defn- resolve-cross-refs-node
  "The CrossRef-resolution pass's own `:rewrite-fn` (TASK-86): a
  `:cross-ref` Inline is `resolve-cross-ref`'s alone to resolve; every
  other node this pass reaches -- via `haselnuss.walk/rewrite`'s own
  schema-derived recursion (`cross-ref-scope`), not a hand-written
  traversal function per node kind -- has no opinion of its own and is
  returned unchanged, `[node]`, so only its own child-bearing fields (if
  it has any) are ever touched.

  `warn!` is the mutable side channel `haselnuss.walk`'s own namespace
  docstring calls for: `rewrite`'s `:rewrite-fn` returns replacement nodes
  only, with no channel of its own for `resolve-cross-ref`'s own `[node'
  warnings]` diagnostics, so this closes over a caller-supplied `(fn [w])`
  and calls it once per warning `resolve-cross-ref` produced for this node
  (zero or one) -- the same single-diagnostic-at-a-time `warn!` shape this
  namespace's own `expand-includes`/`apply-overlays` already use, called in
  the exact order `rewrite` visits nodes (document order, per
  `cross-ref-scope`)."
  [labels inline-ids warn! ref-kind node]
  (if (and (= ref-kind ::ast/inline) (= :cross-ref (:t node)))
    (let [[node' warnings] (resolve-cross-ref labels inline-ids node)]
      (run! (comp warn! (partial locate-warning node)) warnings)
      [node'])
    [node]))

(defn resolve-cross-refs
  "The `CrossRef` resolution pass (SPEC.md sec9 step 2): resolves every
  `:cross-ref` inline reachable from `document` (its top-level `:blocks`
  and, if present, `(:title (:meta document))`) against `labels` -- a
  `number-document` label table, defaulting to `(number-document
  document)`, or pass one built with a custom lexicon to match TASK-11 AC
  #4's pattern.

  Returns `{:document document' :warnings warnings}`: `document'` is
  `document` with every `:cross-ref` node annotated per `resolve-cross-ref`
  (`:target`/`:text` on a match, TASK-12 AC #1/#2; `:target nil` `:text
  \"??\"` on a dangling reference, TASK-12 AC #3), and `warnings` is a
  vector of `{:type :dangling-cross-ref :label :message}` maps (with an
  optional `:node-kind`), one per
  dangling reference encountered, in document order -- `document'` never
  contains a raw crash, only a visible placeholder plus a diagnostic,
  mirroring `resolve`'s own `{ast, meta, diagnostics}` shape at this
  narrower, single-step scale (sec9).

  Walked via `haselnuss.walk/rewrite` (TASK-86, `cross-ref-scope`/
  `resolve-cross-refs-node`), rooted at the Document itself so one call
  reaches both `:blocks` and `meta.title` -- `::meta` is in
  `cross-ref-scope` for exactly that reason, mirroring how
  `haselnuss.lower/lower` reaches `meta.title` the same way (TASK-85)."
  ([document] (resolve-cross-refs document (number-document document)))
  ([document labels]
   (let [warnings (volatile! [])
         warn! (fn [w] (vswap! warnings conj w))
         walker {:table walk/table :scope cross-ref-scope
                 :rewrite-fn (partial resolve-cross-refs-node labels (inline-node-ids document) warn!)}
         document' (first (walk/rewrite walker ::ast/document document))]
     {:document document' :warnings @warnings})))

;; --- Book links (TASK-155, doc-10 "Resolver" step 2) ------------------------
;;
;; Every `:book-ref` (an inline `@{<ref>}`) resolves alongside the cross-
;; references: through the same injected `:books` option the quote step
;; uses, it gets the passage it names (`:passage`), the target's canonical
;; reference (`:canonical`), the passage's title as its link text
;; (`:text`, e.g. "Heidelberg Catechism, Section 3") and, when the book's
;; library entry has a URL template, a `:url`. One that cannot be resolved
;; reads `??`, as a dangling `@fig:x` does, and is a warning placed at its
;; own line.
;;
;; A `:book-ref` that already has `:text` was settled before this step --
;; a quoted cross-reference the quote step rewrote (`quoted-cross-ref`):
;; it keeps the book's own wording ("Section 1.1", faithful to the quote,
;; and its `:suppress-prefix` choice, which the rewrite drops), and a
;; dangling one was already reported at its `!quote`. Such a ref is never
;; resolved or reported again; one with `:canonical` only gains the URL
;; its template fills in, so it can link back to the book it quotes. Every
;; other `:book-ref` -- the host's own, a book's own `@{...}` inside
;; quoted content, a quoted cross-reference the book never resolved -- is
;; resolved here.

(defn- resolve-book-ref
  "`node`, an unresolved `:book-ref`, resolved through `env`'s `:resolve`,
  or with `??` text when that failed. Warnings go to `(:warn! env)`."
  [{resolve-ref :resolve warn! :warn!} node]
  (let [written (:ref node)
        parsed (book-ref/parse-book-ref written)
        fail (fn [diagnostic]
               (warn! (locate-warning node (merge {:level :warning} diagnostic {:ref written})))
               (cond-> (-> node
                           (dissoc :canonical :url :passage)
                           (assoc :text [{:t :str :text "??"}]))
                 (:passage parsed) (assoc :passage (:passage parsed))))]
    (if (book-ref/malformed? parsed)
      (fail parsed)
      (let [{:keys [passage failure warnings diagnostics]} (resolve-ref written parsed)]
        (run! warn! diagnostics)
        (if failure
          (fail failure)
          (do (run! #(warn! (locate-warning node (merge {:level :warning} % {:ref written})))
                    warnings)
              (cond-> (-> node
                          (dissoc :passage :url)
                          (assoc :canonical (:ref passage) :text (:title passage)))
                (:passage parsed) (assoc :passage (:passage parsed))
                (:url passage) (assoc :url (:url passage)))))))))

(defn- link-settled-book-ref
  "`node`, a `:book-ref` settled at quote time, with the URL its
  `:canonical` reference's template fills in, when there is one."
  [{url-of :url warn! :warn!} node]
  (let [parsed (when (and url-of (string? (:canonical node)) (not (contains? node :url)))
                 (book-ref/parse-book-ref (:canonical node)))]
    (if (or (nil? parsed) (book-ref/malformed? parsed))
      node
      (let [{:keys [url diagnostics]} (url-of parsed)]
        (run! warn! diagnostics)
        (cond-> node url (assoc :url url))))))

(defn resolve-book-refs
  "Resolves every `:book-ref` in `document` (doc-10 step 2; see this
  section's comment), returning `{:document document' :warnings
  warnings}`.

  `opts` is `resolve-document`'s `:books` (`expand-quotes`' own): its
  `:resolve` resolves a reference as written, exactly as for a quote, and
  its optional `:url`, `(fn [parsed-canonical-ref] {:url? :diagnostics})`,
  fills in the URL of a reference settled at quote time
  (`haselnuss.library.references/books-option` builds both). Without
  `:resolve` this pass is a no-op and every `:book-ref` is left as parsed.

  Every warning about a reference carries `:ref`, the reference as
  written, a `:level` (`:warning`, or `:info` for a `:stale-shelf`) and,
  when the node has a recorded source position, `:source-file`/
  `:source-line` with a `file:line: ` message prefix; `:diagnostics` the
  option hands back about the build are reported as they are."
  ([document] (resolve-book-refs document {}))
  ([document {resolve-ref :resolve url-of :url}]
   (if-not resolve-ref
     {:document document :warnings []}
     (let [warnings (volatile! [])
           env {:resolve resolve-ref :url url-of :warn! #(vswap! warnings conj %)}
           walker {:table walk/table
                   :scope cross-ref-scope
                   :rewrite-fn (fn [ref-kind node]
                                 (if (and (= ::ast/inline ref-kind) (map? node)
                                          (= :book-ref (keyword (:t node))))
                                   [(if (contains? node :text)
                                      (link-settled-book-ref env node)
                                      (resolve-book-ref env node))]
                                   [node]))}]
       {:document (first (walk/rewrite walker ::ast/document document))
        :warnings @warnings}))))

;; Citation resolution and bibliography generation (TASK-13, sec7/sec9 step 3)
;;
;; This is a from-scratch Clojure port of a spec (`SPEC.md`
;; sec7/sec12) originally written against JS-only engines (`citeproc-js` +
;; `citation-js`), which do not apply here. Re-implementing an engine that
;; loads and interprets arbitrary CSL style XML files is out of scope for
;; one resolver task -- mirrors sec12's own "we do not re-implement CSL"
;; framing, and this project's established practice of small, explicitly
;; documented scope calls (TASK-9 decision D3, TASK-11 AC #4). What ships
;; here instead:
;;
;; - Bibliography data: CSL-JSON only (a JSON array of reference objects,
;;   each with at least an `"id"`), read via `load-bibliography` using
;;   `clojure.data.json` + `:key-fn keyword` -- the same convention
;;   `haselnuss.json` already uses, so no new dependency is needed.
;;   `meta.bibliography` points at CSL-JSON, and that is now the single
;;   source both output worlds read from: TASK-42 added the missing
;;   direction, `haselnuss.emit.latex/csl-json->bibtex`, so a
;;   native-mode LaTeX build gets a generated `.bib` rather than needing
;;   a hand-maintained one beside the JSON. READING a `.bib` is still
;;   not implemented and is no longer on the critical path -- it would
;;   be a convenience for an author whose bibliography already lives in
;;   BibTeX, not a gap in the pipeline.
;; - Style selection: `meta.cslStyle` picks a style descriptor from
;;   `default-citation-styles` (a small, extensible registry mirroring
;;   `default-lexicon`'s "built-in entries + optional caller-supplied
;;   merge" shape) -- not a real CSL style-file interpreter. Two built-ins
;;   ship: `"numeric"` (the default when `cslStyle` is absent or
;;   unrecognized, since it needs no author/year data to produce sane
;;   output) and `"author-date"` (aliased as `"apa"`, sec7's own worked
;;   example, though exact APA punctuation/italicization rules are not
;;   reproduced -- a close, documented approximation).
;;
;; Like CrossRef resolution, a missing citation key does not crash: it
;; becomes a visible `"??"` placeholder plus one build warning per
;; *occurrence* (not deduplicated per key), mirroring `resolve-cross-ref`'s
;; own dangling-reference convention. Unlike `CrossRef`'s `:text` (a plain
;; string), a resolved `:cite` node's `:text` is a *vector of Inline
;; nodes*: each `CiteItem`'s own authored `:prefix`/`:suffix` Inlines are
;; spliced in verbatim rather than flattened to a string, since there is no
;; emitter yet (milestone m-4/m-5) to flatten rich authored content into --
;; flattening now would be genuinely lossy, echoing SPEC.md sec4.4's own
;; complaint about the old lossy `FontType`.
;;
;; Scope note on ordering (documented, not fixed here): sec9 lists
;; numbering (step 1), CrossRef resolution (step 2), then citation
;; resolution (step 3) in that order, so the bibliography Section this step
;; appends is not itself numbered or reachable by a CrossRef resolved
;; *before* it existed. `resolve-citations` does not re-run
;; `number-document`/`resolve-cross-refs` itself -- it only guarantees the
;; appended Section is an ordinary id-bearing Section (AC #3, "can be
;; cross-referenced"), which becomes numberable/resolvable like any other
;; target the moment a caller re-runs those two passes over the resulting
;; document. Assembling the full, correctly-ordered multi-pass pipeline is
;; TASK-25's job, not an individual resolver step's.

(defn load-bibliography
  "Reads and parses a CSL-JSON bibliography file at `path` (SPEC.md sec7's
  `meta.bibliography`) into a map from citation key (each entry's `\"id\"`)
  to its CSL-JSON reference entry (`:author`/`:issued`/`:title`/... with
  keyword keys, via `clojure.data.json`'s `:key-fn keyword`, mirroring
  `haselnuss.json`'s own convention).

  This is the one impure function in citation resolution: `resolve-citations`
  itself stays pure over already-loaded bibliography data, mirroring
  `number-document`/`resolve-cross-refs`'s pattern of accepting caller-
  supplied data over doing their own I/O. Only CSL-JSON is read.
  Producing a `.bib` from what this loads is
  `haselnuss.emit.latex/csl-json->bibtex`'s job (TASK-42), which is what
  makes CSL-JSON the single source a native-mode LaTeX build needs too;
  reading a `.bib` is still not implemented (see this section's
  top-of-namespace comment)."
  [path]
  (let [entries (json/read-str (slurp path) :key-fn keyword)]
    (into {} (map (fn [entry] [(:id entry) entry])) entries)))

(defn- str-inline
  "A plain `:str` Inline node wrapping `s` (sec4.4)."
  [s]
  {:t :str :text s})

(defn- author-family-name
  "The display family name for one CSL-JSON `author` entry: `:family`, or
  `:literal` for a corporate/organizational author with no given/family
  split, or \"Unknown\" if neither is present."
  [{:keys [family literal]}]
  (or family literal "Unknown"))

(defn- author-initial
  "\"G.\" for a CSL-JSON author's `given` name \"George\", or nil if `given`
  is nil/blank."
  [given]
  (when (seq given)
    (str (str/upper-case (subs given 0 1)) ".")))

(defn- author-full-name
  "\"Family, G.\" for one CSL-JSON `author` entry (bibliography-entry form,
  full given-name initial included), or just the family/literal name when
  there is no `given` name to abbreviate."
  [{:keys [given] :as author}]
  (let [family (author-family-name author)]
    (if-let [initial (author-initial given)]
      (str family ", " initial)
      family)))

(defn- entry-year
  "The publication year string for CSL-JSON `entry`'s `:issued` date, per
  CSL-JSON's `{:date-parts [[year month day]]}` shape, or \"n.d.\" (\"no
  date\", the standard CSL placeholder) if `:issued` is absent/malformed."
  [entry]
  (if-let [year (get-in entry [:issued :date-parts 0 0])]
    (str year)
    "n.d."))

(defn- authors-in-text
  "A short author designator for in-text citations, following the common
  author-date convention: the sole author's family name; \"A & B\" for
  exactly two; \"A et al.\" for three or more; \"n.a.\" (\"no author\") if
  `entry` has no `:author` at all."
  [entry]
  (let [names (map author-family-name (:author entry))]
    (case (count names)
      0 "n.a."
      1 (first names)
      2 (str (first names) " & " (second names))
      (str (first names) " et al."))))

(defn- authors-full-list
  "Every author in CSL-JSON `entry`, in full bibliography-entry form
  (`author-full-name`), joined \"A, B, & C\" (an Oxford-comma-less \"&\"
  before the last name, common to both APA- and numeric-style reference
  lists); \"n.a.\" if `entry` has no `:author` at all."
  [entry]
  (let [names (map author-full-name (:author entry))]
    (case (count names)
      0 "n.a."
      1 (first names)
      (str (str/join ", " (butlast names)) ", & " (last names)))))

(defn- present
  "`v` as a trimmed string when non-blank, else nil (CSL-JSON numbers such
  as `volume 4` arrive as numbers or strings alike)."
  [v]
  (let [s (some-> v str str/trim)]
    (when (seq s) s)))

(defn- end-sentence
  "`s` with a full stop appended unless it already ends in terminal
  punctuation (`.`, `?` or `!`), so \"Nut Press Inc.\" and a title ending
  \"?\" do not print doubled."
  [s]
  (if (re-find #"[.?!]$" s) s (str s ".")))

(defn- container-text
  "\"Container, vol(issue), pages\" for whichever of CSL-JSON `entry`'s
  `container-title`, `volume`, `issue` and `page` are present, or nil."
  [entry]
  (let [volume (present (:volume entry))
        issue (present (:issue entry))
        vol-issue (cond (and volume issue) (str volume "(" issue ")")
                        volume volume
                        issue (str "(" issue ")"))]
    (when-let [parts (seq (remove nil? [(present (:container-title entry))
                                        vol-issue
                                        (present (:page entry))]))]
      (str/join ", " parts))))

(defn- link-inline
  "A `:link` Inline to `target` whose text is `shown`."
  [target shown]
  {:t :link :target target :inlines [(str-inline shown)]
   :attr {:id nil :classes [] :props {}}})

(defn- address-inlines
  "The DOI and URL of CSL-JSON `entry` as linked Inlines separated by a
  space (a DOI links to doi.org; a URL naming that same address prints
  once), or an empty vector when it has neither."
  [entry]
  (let [doi (present (:DOI entry))
        url (present (:URL entry))
        doi-url (when doi
                  (if (re-find #"(?i)^https?://" doi) doi (str "https://doi.org/" doi)))
        links (cond-> []
                doi-url (conj (link-inline doi-url doi-url))
                (and url (not= url doi-url)) (conj (link-inline url url)))]
    (vec (interpose {:t :space} links))))

(defn- full-reference-text
  "The full formatted bibliography-entry text for CSL-JSON `entry`:
  \"Authors (Year). Title. Container, vol(issue), pages. Publisher. DOI URL\"
  (each part only when the entry has it; DOI and URL are linked), shared
  by every built-in style (only in-text citation formatting, not the
  reference-list entry itself, differs between `default-citation-styles`'
  built-ins). Carries the container title, volume, issue, pages,
  publisher, DOI and URL (TASK-132), so HTML and computed-numbers LaTeX
  say what a citation refers to. It is not full field parity with
  `haselnuss.emit.latex/csl-json->bibtex` (edition, note, place and
  editors are not shown here)."
  [entry]
  (let [lead (str (authors-full-list entry) " (" (entry-year entry) "). "
                  (end-sentence (or (:title entry) "")))
        middle (keep (fn [s] (when s (end-sentence s)))
                     [(container-text entry) (present (:publisher entry))])
        links (address-inlines entry)]
    (cond-> [(str-inline (str/join " " (cons lead middle)))]
      (seq links) (into (cons {:t :space} links)))))

(defn- numeric-item-core
  "The core in-text citation text for one CiteItem, given `citation-info`
  (`{:entry :number}` from the citation table, or nil if the key had no
  bibliography match) and `bare?` (true exactly when every item in the
  enclosing Cite is `:mode :author` -- the same predicate `resolve-cite-
  node` uses to decide whether `wrap-cite-text` skips the outer `[...]`,
  passed in rather than recomputed here).

  When `bare?` is false, this is unbracketed (the style's own outer
  `:open`/`:close` supplies the `[...]`): the entry's assigned
  bibliography number, or \"??\" (mirroring `resolve-cross-ref`'s
  dangling-reference placeholder) when missing. Numeric style otherwise
  deliberately does not distinguish CiteItem `:mode` (documented
  simplification -- an author/year split has no numeric-style analogue).

  When `bare?` is true, no outer wrap will be added at all (see
  `wrap-cite-text`), so this self-embeds its own `[...]` instead --
  otherwise a bare `@key` author-in-text citation (the primary bare-
  citation form, SPEC.md sec5.11) would render as a naked, unmarked
  number/placeholder, indistinguishable from surrounding body text
  (review finding on TASK-13): \"Smith [1]\" (the entry's author
  designator alongside its bracketed number, the conventional way a
  numeric style still marks an author-in-text mention) or \"[??]\" for a
  dangling key."
  [citation-info _mode bare?]
  (if citation-info
    (let [number (str (:number citation-info))]
      (if bare?
        (str (authors-in-text (:entry citation-info)) " [" number "]")
        number))
    (if bare? "[??]" "??")))

(defn- author-date-item-core
  "The core in-text citation text for one CiteItem, given `citation-info`,
  the item's own `mode`, and `bare?` (true exactly when every item in the
  enclosing Cite is `:mode :author` -- see `numeric-item-core`'s
  docstring for the shared contract).

  \"??\" (mirroring `resolve-cross-ref`'s placeholder) when `citation-info`
  is nil, itself self-wrapped in `(??)` when `bare?` so a dangling bare
  citation still shows a visible marker rather than a naked placeholder.
  Bare \"Year\" for `:year` mode (pandoc's `-@key` suppress-author
  convention, TASK-9) -- unaffected by `bare?`, since a Cite is only ever
  `bare?` when *every* item is `:author` mode, never `:year`. Otherwise:
  `:author` mode is \"Authors (Year)\" (reads as a sentence subject, e.g.
  \"Smith (2020) showed...\") only when `bare?` -- i.e. only when no outer
  `(...)` wrap will be added by `wrap-cite-text`, so this is the *only*
  parenthesization the text gets; when `bare?` is false (a Cite mixing
  `:author` with other-mode items -- review finding on TASK-13), this
  instead renders as plain \"Authors, Year\" like `:normal` mode, since
  the outer wrap already supplies one shared pair of parens around the
  whole joined citation and adding a second, item-own pair here would
  double/nest them (e.g. the buggy \"(Smith (2020); Jones, 2019)\"). Any
  other mode (`:normal`, or `:author` when not `bare?`) is \"Authors,
  Year\"."
  [citation-info mode bare?]
  (if-not citation-info
    (if bare? "(??)" "??")
    (let [entry (:entry citation-info)
          year (entry-year entry)]
      (case mode
        :author (if bare?
                  (str (authors-in-text entry) " (" year ")")
                  (str (authors-in-text entry) ", " year))
        :year year
        (str (authors-in-text entry) ", " year)))))

(def default-citation-styles
  "The built-in citation-style registry (SPEC.md sec7): `meta.cslStyle`
  selects an entry here, defaulting to `\"numeric\"` when absent or
  unrecognized. Each style is `{:open :close :item-core :bib-entry
  :sort-key? :ordered?}`:
  - `:open`/`:close` -- the strings `wrap-cite-text` wraps a Cite's joined
    item texts in (e.g. \"[\"/\"]\"), unless every item in the Cite is
    `:author` mode (`bare?`), in which case `:item-core` is expected to
    supply any punctuation an author-in-text mention still needs on its
    own (see `numeric-item-core`/`author-date-item-core`'s docstrings --
    this is the TASK-13 review fix for a `:mode :author` Cite otherwise
    rendering with no citation marker at all under \"numeric\").
  - `:item-core` -- `(fn [citation-info mode bare?] core-text-string)`, one
    CiteItem's own in-text text, unwrapped unless `bare?`.
  - `:bib-entry` -- `(fn [entry] Inline-vector)`, one bibliography entry's
    full reference-list text.
  - `:sort-key?` -- when present, `(fn [key entry] sort-key)` used to order
    the generated bibliography (author-date sorts alphabetically by
    author); when absent (numeric), entries stay in first-appearance
    order, matching the numbers assigned to them.
  - `:ordered?` -- whether the generated bibliography List renders as an
    ordered (numbered) list.

  Custom styles can be added the same way custom lexicon kinds are (TASK-11
  AC #4): `(merge default-citation-styles {\"my-style\" {...}})`, passed as
  `resolve-citations`'s optional third argument."
  (let [author-date {:open "(" :close ")"
                     :item-core author-date-item-core
                     :bib-entry full-reference-text
                     :sort-key? (fn [_key entry]
                                  [(str/lower-case (authors-in-text entry)) (entry-year entry)])
                     :ordered? false}]
    {"numeric" {:open "[" :close "]"
                :item-core numeric-item-core
                :bib-entry full-reference-text
                :ordered? true}
     "author-date" author-date
     "apa" author-date}))

(def ^:private bibliography-heading-words
  "The generated bibliography Section's heading word, per `meta.lang`
  (mirrors `default-lexicon`'s own per-language `:words`)."
  {"en" "Bibliography"
   "pt-BR" "Referências"})

(defn- item-inline-text
  "One CiteItem's full formatted text: its own authored `:prefix` Inlines
  (if any, followed by a space), then `core` (the style's own unwrapped
  in-text text, as a `:str` Inline), then its own authored `:suffix`
  Inlines (if any, preceded by \", \") -- e.g. \"see Smith, 2020, p. 42\"
  for a CiteItem with prefix \"see\" and suffix \"p. 42\". Prefix/suffix
  Inlines are spliced in verbatim, never flattened (see this section's
  top-of-namespace comment)."
  [core item]
  (vec (concat
        (when (seq (:prefix item)) (conj (vec (:prefix item)) {:t :space}))
        [(str-inline core)]
        (when (seq (:suffix item)) (into [(str-inline ", ")] (:suffix item))))))

(defn- join-inline-groups
  "Every Inline-vector in `groups` concatenated together with a plain
  `:str` Inline of `sep` inserted between consecutive groups (e.g. `\"; \"`
  between citation items)."
  [sep groups]
  (vec (apply concat (interpose [(str-inline sep)] groups))))

(defn- wrap-cite-text
  "Combines `item-texts` (one Inline-vector per CiteItem, in order, already
  `item-inline-text`-formatted) into a Cite node's full `:text`: joined
  with \"; \" between items, then wrapped in `open`/`close` (the style's
  own outer punctuation) -- unless `bare?` (true exactly when every item's
  `:mode` is `:author`, computed once by `resolve-cite-node` and passed in
  here rather than recomputed), in which case no outer wrapping is added
  at all, since an author-in-text citation (e.g. \"Smith (2020)
  showed...\") must read as part of the surrounding sentence, not inside
  its own brackets/parens -- `:item-core` is then responsible for any
  punctuation the bare text still needs on its own (see
  `numeric-item-core`/`author-date-item-core`)."
  [item-texts bare? open close]
  (let [joined (join-inline-groups "; " item-texts)]
    (if bare?
      joined
      (vec (concat [(str-inline open)] joined [(str-inline close)])))))

(defn- lookup-citation
  "`citation-table`'s entry for `citation-key` (`{:entry :number}`), or nil
  if `citation-key` had no bibliography match."
  [citation-table citation-key]
  (get citation-table citation-key))

(defn- resolve-cite-node
  "Formats Cite node `cite`'s own `:text` (its `:items` are assumed
  already resolved for any nested Cites -- see `resolve-citations-node`,
  TASK-86, which guarantees this before ever calling here), using `style`
  and `citation-table` (a `build-citation-table` result).
  `bare?` (true exactly when every item's `:mode` is `:author`) is
  computed once here and threaded into both `:item-core` (so it can
  self-punctuate a bare author-in-text mention, review fix for TASK-13)
  and `wrap-cite-text` (so the outer wrap decision and the item-core
  rendering can never disagree, which is what produced the doubled-parens
  bug for a Cite mixing `:author` with other-mode items).

  Returns `[cite' warnings]`: `warnings` has one `{:type :dangling-citation
  :key :message}` map per CiteItem whose `:key` had no bibliography match
  (TASK-13 AC #4), in item order, not deduplicated across items sharing
  the same missing key (mirrors `resolve-cross-ref`'s own convention)."
  [{:keys [style citation-table]} cite]
  (let [items (:items cite)
        bare? (every? #(= :author (:mode %)) items)
        cores (map (fn [item]
                     ((:item-core style) (lookup-citation citation-table (:key item))
                                         (:mode item)
                                         bare?))
                   items)
        item-texts (map item-inline-text cores items)
        text (wrap-cite-text item-texts bare? (:open style) (:close style))
        warnings (into []
                       (keep (fn [item]
                               (when-not (lookup-citation citation-table (:key item))
                                 {:type :dangling-citation
                                  :key (:key item)
                                  :message (str "dangling citation: no bibliography entry for key \""
                                                (:key item) "\"")})))
                       items)]
    [(assoc cite :text text) warnings]))

;; Two concerns are kept separate since bibliography ordering/numbering
;; needs the *whole* document's distinct key set before any single Cite can
;; be formatted:
;;
;; `cite-keys-in-*` below gathers every CiteItem `:key` in document order --
;; a plain read-only collecting walk with no transform, exactly `fold`'s own
;; contract (TASK-87) -- via `cite-keys-walker`/`cite-keys-visit` below.
;;
;; Formatting every reachable Cite node's own `:text` is, since TASK-86,
;; `resolve-citations-node`, below `build-citation-table`: `haselnuss.walk/
;; rewrite`'s schema-derived recursion (`citation-scope`) reaches every
;; position this walk does too, with ONE deliberate exception -- see
;; `citation-scope`'s own docstring for why a Cite's `:items` (`::cite-
;; item`) has to stay out of `:scope` and be walked manually instead,
;; unlike `resolve-cross-refs`. `cite-keys-scope` makes the identical
;; exclusion, for the identical reason -- see there.

(def ^:private cite-keys-scope
  "`haselnuss.walk/fold`'s own `:scope` for `cite-keys-in-*` (TASK-87):
  `cross-ref-scope` minus `::meta` (this pass is called separately over
  `meta.title` and `:blocks`, mirroring `resolve-citations`'s own two call
  sites below, so nothing here ever roots at the Document itself) and
  minus `::cite-item`, excluded for exactly `citation-scope`'s own reason
  applied one pass earlier: a Cite's own `:items` need to contribute their
  `:key`s as ONE BATCH, ahead of any key nested inside an item's own
  `:prefix`/`:suffix` (see `cite-keys-visit`'s own docstring) -- an order
  `fold`'s automatic per-item recursion cannot produce on its own, since it
  would interleave each item's own key with that SAME item's nested
  content before ever reaching the next item. Citation numbers are
  assigned by first-appearance order over this exact sequence
  (`build-citation-table`), so getting this order wrong is an observable,
  rendered-output bug (a document whose first Cite hides a nested Cite in
  an early item's own prefix would number citations differently), not a
  cosmetic one."
  #{::ast/block ::ast/inline ::ast/cell ::ast/row})

(declare cite-keys-walker)

(defn- cite-keys-visit
  "`cite-keys-walker`'s own `:visit` (TASK-87): every other node this walk
  reaches contributes nothing directly (a citation key only ever lives on
  a CiteItem, never a Block or Inline itself) and is left to `fold`'s own
  recursion into its child-bearing fields. A `:cite` Inline is the one
  exception, handled ENTIRELY here rather than through `:items`
  (`::cite-item`, deliberately out of `cite-keys-scope`): first every
  item's own `:key`, in item order (mirrors the pre-TASK-87 `cite-keys-in-
  inline`'s own `(map :key (:items inline))`), THEN, for each item in that
  SAME order, any key nested inside its own `:prefix` then `:suffix`
  (each walked recursively with this same walker) -- reproducing the pre-
  TASK-87 `:cite` case's own two-pass order exactly, which a naive
  per-item interleaved dispatch would not (see `cite-keys-scope`)."
  [acc _path ref-kind node]
  (if (and (= ref-kind ::ast/inline) (= :cite (:t node)))
    (let [nested-keys-in (fn [acc inlines]
                           (reduce (fn [acc inline] (walk/fold cite-keys-walker acc ::ast/inline inline))
                                   acc inlines))]
      (reduce (fn [acc item] (-> acc (nested-keys-in (:prefix item)) (nested-keys-in (:suffix item))))
              (into acc (map :key (:items node)))
              (:items node)))
    acc))

(def ^:private cite-keys-walker
  {:table walk/table :scope cite-keys-scope :visit cite-keys-visit})

(defn- cite-keys-in-blocks
  "Every CiteItem `:key` reachable from `blocks`, in document order
  (TASK-87: `cite-keys-walker`, walked via `haselnuss.walk/fold`)."
  [blocks]
  (reduce (fn [acc block] (walk/fold cite-keys-walker acc ::ast/block block)) [] blocks))

(defn- cite-keys-in-inlines
  "Every CiteItem `:key` reachable from `inlines`, in document order
  (TASK-87: `cite-keys-walker`, walked via `haselnuss.walk/fold`)."
  [inlines]
  (reduce (fn [acc inline] (walk/fold cite-keys-walker acc ::ast/inline inline)) [] inlines))

(defn- build-citation-table
  "A map from each *resolvable* key in `keys-in-order` (one already found
  in `bibliography`) to `{:entry :number}`, `:number` its 1-based
  first-appearance index among resolvable keys only -- a key with no
  bibliography match consumes no number and has no entry here at all
  (`lookup-citation` returning nil for it is exactly what marks it
  dangling, TASK-13 AC #4)."
  [bibliography keys-in-order]
  (let [found (filter #(contains? bibliography %) keys-in-order)]
    (into {}
          (map-indexed (fn [i k] [k {:entry (get bibliography k) :number (inc i)}]))
          found)))

(def ^:private citation-scope
  "`haselnuss.walk/rewrite`'s own `:scope` for `resolve-citations` (TASK-86)
  -- `cross-ref-scope` with ONE deliberate difference: `::cite-item` is OUT
  of scope here, unlike there.

  Formatting a Cite node's own `:text` (`resolve-cite-node`) needs its
  CiteItems' `:prefix`/`:suffix` ALREADY resolved: a nested Cite there must
  already carry its own `:text` before the outer Cite's `:text` splices it
  in verbatim (`item-inline-text`), exactly what resolving `:items` before
  ever calling `resolve-cite-node` guaranteed before this port. `rewrite`
  cannot express that ordering with a plain per-node dispatch at `::inline`:
  `(:rewrite-fn walker)` always runs BEFORE `rewrite` recurses into
  whatever it returns, never after (see `haselnuss.walk/rewrite`'s own
  docstring), so a naive dispatch would compute a Cite's `:text` from its
  STILL-unresolved items -- observably wrong output, not just a different
  code path, since the outer Cite's own `:text` would then embed a nested
  Cite with no `:text` of its own.

  Excluding `::cite-item` from `:scope` is what makes the fix safe rather
  than merely convenient: the ONLY field anywhere in `haselnuss.ast/
  registry` whose type ever refers to `::cite-item` is a Cite's own
  `:items` (confirmed against `ast.clj` directly), so nothing else in the
  schema ever needs it in scope, and excluding it stops `rewrite`'s own
  automatic recursion from EVER reaching a CiteItem on its own. That is
  what lets `resolve-citations-node`'s own `:cite` branch walk `:items`
  itself FIRST (recursively, with this same scope, so a nested Cite
  resolves correctly at any depth), THEN call `resolve-cite-node`, with no
  risk of `rewrite`'s later automatic descent into the very same,
  already-resolved `:items` running `resolve-cite-node` on it a SECOND
  time -- which would not just waste work but double-count any nested
  dangling-citation warning."
  #{::ast/block ::ast/inline ::ast/cell ::ast/row ::ast/meta})

(declare resolve-citations-node)

(defn- citation-walker
  "The Cite-resolution pass's own `haselnuss.walk/rewrite` walker
  (TASK-86): `:scope` is `citation-scope` (see there for why `::cite-item`
  is deliberately excluded) and `:rewrite-fn` is `resolve-citations-node`
  itself, partially applied over `ctx`. Built both by the top-level call in
  `resolve-citations` and by `resolve-citations-node`'s own recursive call
  into a Cite's CiteItems' `:prefix`/`:suffix` content -- see there for why
  that one recursion has to be manual rather than left to `rewrite`'s own
  automatic per-field descent."
  [ctx]
  {:table walk/table :scope citation-scope :rewrite-fn (partial resolve-citations-node ctx)})

(defn- resolve-citations-node
  "The Cite-resolution pass's own `:rewrite-fn` (TASK-86, `ctx` is `{:style
  :citation-table :warn!}`): a `:cite` Inline is resolved HERE, not left to
  `rewrite`'s own automatic recursion into its `:items` (out of
  `citation-scope` -- see there), because `resolve-cite-node` needs those
  items' own nested Cites (if any) already resolved first.

  So this walks `:items` itself: `resolve-nested` recurses into each
  CiteItem's own `:prefix`/`:suffix` via `walk/rewrite` (`citation-walker`,
  the SAME scope/ctx, so a Cite nested arbitrarily deep inside another
  Cite's own CiteItem resolves correctly, one level of recursion per level
  of nesting), producing `items'` -- CiteItems whose own `:prefix`/`:suffix`
  are already, correctly resolved, mirroring TASK-9's own prefix/suffix-
  presence contract (`update` only touches a field the item actually has).
  `resolve-cite-node` is then called on `(assoc node :items items')`, and
  ITS OWN warnings (dangling-citation, one per unresolved key in `:items`,
  TASK-13 AC #4) are reported through `warn!` -- `ctx`'s own mutable side
  channel (`haselnuss.walk`'s own namespace docstring: `rewrite`'s
  `:rewrite-fn` returns replacement nodes only, so a caller wanting a side
  log closes over one itself), in the exact order this recursion visits
  nodes: any NESTED Cite's own warnings first (via `resolve-nested`, since
  `items'` is computed before `resolve-cite-node` runs on the outer node),
  then this node's own, mirroring the pre-port `cite-resolve-inline`'s own
  `(into iw cw)` ordering exactly.

  Every other node this pass reaches -- via `haselnuss.walk/rewrite`'s own
  schema-derived recursion, not a hand-written traversal function per node
  kind -- has no opinion of its own and is returned unchanged, `[node]`."
  [ctx ref-kind node]
  (if (and (= ref-kind ::ast/inline) (= :cite (:t node)))
    (let [walker (citation-walker ctx)
          resolve-nested (fn [inlines] (into [] (mapcat #(walk/rewrite walker ::ast/inline %)) inlines))
          items' (mapv (fn [item]
                         (cond-> item
                           (:prefix item) (update :prefix resolve-nested)
                           (:suffix item) (update :suffix resolve-nested)))
                       (:items node))
          [node' warnings] (resolve-cite-node ctx (assoc node :items items'))]
      (run! (comp (:warn! ctx) (partial locate-warning node)) warnings)
      [node'])
    [node]))

;; Bibliography-section id disambiguation (TASK-13 review fix, AC #3)
;;
;; `bibliography-section` previously hardcoded attr.id "sec:bibliography"
;; unconditionally. If the document already had any Block with that same
;; id (e.g. an author-written `# Bibliography {#sec:bibliography}`
;; heading), the result silently had two blocks sharing one id, and
;; `number-document`'s label table (keyed by id via plain `assoc`) would
;; silently keep only the later (generated) entry -- any pre-existing
;; CrossRef to the author's own "sec:bibliography" node would silently
;; retarget to the generated bibliography, with no warning at all.
;;
;; `ids-in-blocks` below (TASK-87: `ids-walker`/`ids-visit`, via
;; `haselnuss.walk/fold`) collects every id already in play -- deliberately
;; scoped to Block `:attr.id`s only, exactly the id namespace
;; `number-document`'s label table is built from (see `id->kind`'s own
;; docstring: a numbering target is always a Block, never a Link/Image/Span
;; Inline's own id), since that is precisely the collision this fix closes.
;; `unique-bibliography-id` then picks the base id its own division calls
;; for ("sec:bibliography", or "ch:bibliography" in a chaptered document --
;; TASK-63) when that is free, else the first numbered-suffix variant not
;; already taken, and -- mirroring this namespace's own "never change
;; behavior silently" convention (the dangling-cross-ref/dangling-citation
;; warnings above) -- reports the collision as a build warning rather than
;; just quietly picking a different id with no trace.
;;
;; `ids-scope` reaches every position `block-children`'s own (pre-TASK-87)
;; Block-nested-Block traversal did -- including a footnote nested inside a
;; Cite's own `:prefix`/`:suffix`, which `block-children`'s `:para` case
;; reached via `inline-blocks` -- PLUS one position it did not: a footnote
;; nested inside a Section's own `:heading` or a Figure/Table's own
;; `:caption`, not just a Para's `:inlines`. `haselnuss.walk/fold`'s
;; `:scope` filters by reference KIND (`::inline`), not by which node's
;; field it is, so it cannot single out "reachable through a Para" the way
;; the old, hand-written traversal did.
;;
;; HONEST NOTE ON TASK-87's OWN AC #2 ("collected ... ids ... identical to
;; before, including order and duplicate handling"): this widening means
;; that wording does NOT hold literally for `ids-in-blocks`. It is a
;; disclosed, deliberate, verified-safe change, not an oversight glossed
;; over as "identical" -- confirmed empirically (clojure-reviewer, TASK-87
;; review, and independently reproduced from a git worktree pinned at the
;; pre-TASK-87 commit): given a Section whose `:heading` and a Figure whose
;; `:caption` each carry a footnote wrapping the SAME id-bearing MathBlock,
;; the pre-TASK-87 `ids-in-block` returns `(nil nil nil)` for the pair (the
;; footnotes are invisible to it), while this walk returns `[nil "the-id"
;; nil nil "the-id"]` -- a strict superset, not a mere reorder. It is safe
;; specifically because of what `ids-in-blocks` feeds and nothing more
;; general than that: its one caller, `existing-block-ids`, converts
;; straight to a SET for collision-avoidance when choosing the generated
;; bibliography section's own id (order and duplicate COUNT are already
;; irrelevant to it), and `duplicate-id-diagnostics` independently re-
;; checks the FINAL document regardless of what this pass finds -- so a
;; wider reach here can only let `unique-bibliography-id` notice a real,
;; pre-existing collision it used to miss (and proactively pick a
;; different id, with its own warning), never manufacture a false one.
;; This is the class of fix the schema-derived walker exists for (doc-6):
;; the old, hand-written `block-children`-based walk had a narrower reach
;; than `inline-blocks`' own `::inline`-vs-`::block` distinction could
;; ever express in the first place, and this port's `:scope` mechanism
;; cannot silently keep repeating that narrowing -- it has no notion of
;; "this `::inline` field, but not that one". Reverting to the narrower,
;; pre-TASK-87 reach would mean deliberately reintroducing that gap, which
;; is not what "identical to before" is worth doing here.

(def ^:private ids-scope
  "`haselnuss.walk/fold`'s own `:scope` for `ids-in-blocks` (TASK-87): every
  reference kind `inline-blocks` (this pass's own pre-TASK-87 footnote
  reachability, reused by `block-children`'s `:para` case) could reach a
  nested Block through -- `::inline` for a Para's own `:inlines`, `::cite-
  item` for a Cite's own `:prefix`/`:suffix` -- plus `::row`/`::cell` for a
  Table's own cells, exactly `block-children`'s own coverage there. See
  this section's own top-of-namespace comment (\"HONEST NOTE ON TASK-87's
  OWN AC #2\") for the one documented, verified-safe way this genuinely
  reaches MORE than the pre-TASK-87 walk did -- not merely a reorder."
  #{::ast/block ::ast/inline ::ast/cell ::ast/row ::ast/cite-item})

(defn- ids-visit
  "`ids-walker`'s own `:visit` (TASK-87): `(get-in node [:attr :id])` (nil
  included, mirroring the pre-TASK-87 `ids-in-block`'s own unconditional
  `cons`) for every Block this walk reaches, nothing for anything else --
  a numbering target, and therefore a candidate for `existing-block-ids`,
  is always a Block (see this section's own top-of-namespace comment)."
  [acc _path ref-kind node]
  (if (= ref-kind ::ast/block)
    (conj acc (get-in node [:attr :id]))
    acc))

(def ^:private ids-walker
  {:table walk/table :scope ids-scope :visit ids-visit})

(defn- ids-in-blocks
  "Every Block `attr.id` (nil included) reachable from `blocks`, in
  document order (TASK-87: `ids-walker`, walked via `haselnuss.walk/fold`)."
  [blocks]
  (reduce (fn [acc block] (walk/fold ids-walker acc ::ast/block block)) [] blocks))

(defn- existing-block-ids
  "Every non-nil Block `attr.id` already present anywhere in `document`."
  [document]
  (into #{} (remove nil?) (ids-in-blocks (:blocks document))))

(defn- chaptered?
  "True when `document` has a CHAPTER level (`meta.topLevelDivision` is
  `:chapter`, or `:part` -- TASK-82 shifts chapters to level 2 rather
  than removing them).

  One function rather than the third literal copy of the same
  `get-in` (found by review): the generated bibliography's id prefix and
  `division-kind-diagnostics` must agree about this, or the warning the
  prefix exists to avoid comes straight back."
  [document]
  (contains? #{:chapter :part} (get-in document [:meta :top-level-division])))

(defn- parted?
  "True when `document` opted into parts (`meta.topLevelDivision`
  `:part`, TASK-82) -- unlike `chaptered?`, `:chapter` alone does not
  count: this is specifically the division ABOVE chapter."
  [document]
  (= :part (get-in document [:meta :top-level-division])))

(defn- unique-bibliography-id
  "The id `bibliography-section` should use, given `existing-ids` (an
  `existing-block-ids` result): the bare `\"sec:bibliography\"` when that
  id is not already taken, else the first `\"sec:bibliography-2\"`,
  `\"sec:bibliography-3\"`, ... suffix not already taken. Returns `[id
  warnings]` -- `warnings` carries one `{:type :bibliography-id-collision
  :id :resolved-id :message}` map exactly when disambiguation was needed,
  so the author whose own node already used `\"sec:bibliography\"` is
  told their id was *not* silently reused (TASK-13 review fix, AC #3).

  `chapters?` (`meta.topLevelDivision` `:chapter` OR `:part`, TASK-82)
  changes the PREFIX to `ch:`, and that is not cosmetic (TASK-63). The
  generated section is a heading at the chapter level (level 1 for
  `:chapter`, level 2 for `:part` -- see `bibliography-section`'s own
  `level`), which such a document emits as `\\chapter`, so a `sec:`
  prefix there is exactly the id-prefix/division disagreement
  `division-kind-diagnostics` warns about -- a reference to it printing
  \"Section 5\" in HTML and \"Chapter 5\" in a native PDF. Every
  chaptered document with a bibliography therefore warned about a
  section nobody wrote, and no edit to the document could silence it,
  since the id is generated here. The prefix follows the division the
  section is actually emitted into instead. It stays `ch:` rather than
  gaining a third `part:` option in a parted document: the bibliography
  is chapter-level furniture there too, never its own `\\part` (matching
  `haselnuss.emit.latex/front-matter-standalone`'s own `:heading` shape,
  which reasons the same way about an acknowledgements page)."
  [existing-ids chapters?]
  (let [base (if chapters? "ch:bibliography" "sec:bibliography")]
    (if-not (contains? existing-ids base)
      [base []]
      (let [resolved-id (->> (iterate inc 2)
                             (map #(str base "-" %))
                             (drop-while existing-ids)
                             first)]
        [resolved-id
         [{:type :bibliography-id-collision
           :id base
           :resolved-id resolved-id
           :message (str "bibliography id \"" base "\" is already used by an existing node; "
                         "using \"" resolved-id "\" for the generated bibliography section instead")}]]))))

(defn- bibliography-section
  "The generated, referenceable bibliography Section (TASK-13 AC #2/#3):
  attr.id `id` (see `unique-bibliography-id` -- not always the bare
  `\"sec:bibliography\"`, since that could already be taken), one Para
  per distinct resolvable key in `ordered-keys` (already sorted/ordered
  per `style`), each formatted via `style`'s own `:bib-entry`. `lang`
  picks the heading word from `bibliography-heading-words`, defaulting to
  English. `level` is the heading level to emit it at -- `1` normally, or
  `2` in a PARTED document (TASK-82), so the bibliography lands at the
  chapter level (`\\chapter`) rather than becoming a `\\part` of its own
  (see `unique-bibliography-id`'s own docstring for why)."
  [style bibliography ordered-keys lang id level]
  (let [items (mapv (fn [k] [{:t :para :inlines ((:bib-entry style) (get bibliography k))}])
                    ordered-keys)
        heading-word (or (get bibliography-heading-words lang) (get bibliography-heading-words "en"))]
    {:t :section
     :level level
     :heading [(str-inline heading-word)]
     :blocks [{:t :list :ordered (boolean (:ordered? style)) :tight true :items items
               :attr {:classes [] :props {}}}]
     :attr {:id id :classes ["bibliography"] :props {}}}))

(defn resolve-citations
  "The citation-resolution and bibliography-generation pass (SPEC.md sec7,
  sec9 step 3): formats every `:cite` inline reachable from `document`
  (its `:blocks` and, if present, `meta.title`) using `bibliography` (a map
  from citation key to its CSL-JSON reference entry, e.g. from
  `load-bibliography`) and the style selected by `(:csl-style (:meta
  document))` -- looked up in `styles` (default `default-citation-styles`),
  falling back to the built-in `\"numeric\"` style when `cslStyle` is
  absent or names no registered style -- then appends a generated,
  referenceable bibliography Section (TASK-13 AC #2/#3) with one formatted
  entry per distinct citation key actually used in the document, ordered
  per the selected style (first-appearance for `\"numeric\"`, alphabetical
  by author for `\"author-date\"`/`\"apa\"`). No Section is appended when
  no citation key resolves to a bibliography entry (nothing to list). The
  appended Section's own id is `\"sec:bibliography\"` -- or
  `\"ch:bibliography\"` in a chaptered document, where it is emitted as a
  chapter (TASK-63) -- unless that id is
  already used by some other node in `document`, in which case a
  disambiguated id is used instead and a `:bibliography-id-collision`
  warning is added (see `unique-bibliography-id` -- TASK-13 review fix,
  AC #3: this is what keeps a pre-existing CrossRef to the author's own
  `\"sec:bibliography\"` node resolving to that node, not silently
  retargeting to the generated bibliography).

  A citation key with no matching bibliography entry (TASK-13 AC #4) does
  not crash: its CiteItem's in-text text becomes \"??\" (mirroring
  `resolve-cross-ref`'s dangling-reference placeholder, itself wrapped in
  its style's own bracket/parens when the Cite is bare -- see
  `numeric-item-core`/`author-date-item-core`) and one `{:type
  :dangling-citation :key :message}` warning is added per occurrence (not
  deduplicated per key, mirroring `resolve-cross-ref`'s own convention); a
  missing key never gets a bibliography entry, since there is nothing to
  format.

  A `cslStyle` that names no registered style also does not crash: it
  falls back to `\"numeric\"` (as above) but, unlike before this fix, adds
  one `{:type :unrecognized-citation-style :style :message}` warning
  (TASK-13 review fix -- consistent with every other \"couldn't resolve
  this\" case in this namespace, this no longer fails silently). A
  `cslStyle` that is simply absent is not itself a warning-worthy event --
  `\"numeric\"` is then the deliberate default, not a fallback from
  something invalid.

  ([document]) reads `meta.bibliography` via `load-bibliography` (or uses
  `{}` when `meta.bibliography` is absent -- every citation then simply
  dangles, still without crashing); ([document bibliography]) and
  ([document bibliography styles]) accept already-loaded/caller-supplied
  data directly, for pure, filesystem-independent use and custom styles
  (mirroring TASK-11 AC #4's lexicon-merge pattern).

  Returns `{:document document' :warnings warnings :ordered-keys
  ordered-keys :bibliography-id bib-id}` (extending -- additively,
  non-breakingly -- `resolve-cross-refs`'s own `{:document :warnings}`
  return shape): `warnings` holds, in order, any
  `:unrecognized-citation-style` warning, then every `:dangling-citation`
  warning in document order, then any `:bibliography-id-collision`
  warning. `ordered-keys` is every distinct resolvable citation key, in
  the exact order the generated bibliography List renders them (`[]` when
  none resolve); `bibliography-id` is the appended Section's own `attr.id`
  (see `unique-bibliography-id` above), or `nil` when no Section was
  appended. Both are TASK-20 additions (the HTML emitter's own follow-up
  task): a citation key's identity, and the position it ends up at in the
  rendered bibliography list, are only ever known here, at generation
  time -- `bibliography-section`'s own output Paras carry no key/id of
  their own for a later pass to recover them from (Para has no `Attr`
  field at all, sec4.3), so an emitter wanting to link an in-text citation
  to its own bibliography-list entry needs this pair returned directly,
  the same way `number-document`'s label table is handed to
  `resolve-cross-refs`/`derive-toc` rather than re-derived from the AST.
  Purely additive: every existing caller destructuring `:document`/
  `:warnings` (this namespace's own `resolve-document`, every
  `resolve-citations` test) is unaffected.

  Walked via `haselnuss.walk/rewrite` (TASK-86, `citation-scope`/
  `citation-walker`/`resolve-citations-node`), rooted at the Document
  itself so one call reaches both `:blocks` and `meta.title` -- mirroring
  `resolve-cross-refs`'s own TASK-86 port -- with `citation-scope`'s own
  deliberate `::cite-item` exclusion accounted for (see there)."
  ([document]
   (resolve-citations document (if-let [path (get-in document [:meta :bibliography])]
                                 (load-bibliography path)
                                 {})))
  ([document bibliography] (resolve-citations document bibliography default-citation-styles))
  ([document bibliography styles]
   (let [style-key (get-in document [:meta :csl-style])
         resolved-style (get styles style-key)
         style (or resolved-style (get styles "numeric"))
         style-warnings (if (and style-key (not resolved-style))
                          [{:type :unrecognized-citation-style
                            :style style-key
                            :message (str "unrecognized cslStyle \"" style-key
                                          "\": falling back to the built-in \"numeric\" style")}]
                          [])
         title (get-in document [:meta :title])
         keys-in-order (distinct (concat (cite-keys-in-inlines title)
                                         (cite-keys-in-blocks (:blocks document))))
         citation-table (build-citation-table bibliography keys-in-order)
         walk-warnings (volatile! [])
         ctx {:style style :citation-table citation-table
              :warn! (fn [w] (vswap! walk-warnings conj w))}
         document' (first (walk/rewrite (citation-walker ctx) ::ast/document document))
         resolvable-keys (filter #(contains? citation-table %) keys-in-order)
         ordered-keys (if-let [sort-key (:sort-key? style)]
                        (sort-by #(sort-key % (get bibliography %)) resolvable-keys)
                        resolvable-keys)
         lang (get-in document [:meta :lang] "en")
         bib-level (if (parted? document) 2 1)
         [bib-id bib-id-warnings] (if (seq ordered-keys)
                                    (unique-bibliography-id (existing-block-ids document)
                                                            (chaptered? document))
                                    [nil []])
         document'' (cond-> document'
                      (seq ordered-keys)
                      (update :blocks conj
                              (bibliography-section style bibliography ordered-keys lang bib-id
                                                    bib-level)))]
     {:document document''
      :warnings (-> style-warnings (into @walk-warnings) (into bib-id-warnings))
      :ordered-keys (vec ordered-keys)
      :bibliography-id bib-id
      ;; The word that Section prints as its own heading (TASK-64).
      ;; Returned because a reference to the generated section is a
      ;; reference to something UNNUMBERED -- see
      ;; `bibliography-label-entry` -- so the only text it can print is
      ;; that word, and this is the pass that chose it.
      :bibliography-heading (when bib-id
                              (or (get bibliography-heading-words lang)
                                  (get bibliography-heading-words "en")))})))

;; Document-wide derived structures (TASK-14, sec9 step 4): TOC,
;; list-of-figures/list-of-tables, and section navigation links
;;
;; SPEC.md sec9 step 4 says these are *derived*, never authored -- fixing
;; the old model's `SectionType.next`/`previous`, hand-stored on each
;; section in the TypeScript reader's own `Types.tsx` (outside this
;; repository) and consumed
;; directly by `Book.tsx`/`Article.tsx`. Every function below is a pure
;; function of `document` (plus an optional caller-supplied `labels` table,
;; mirroring `number-document`/`resolve-cross-refs`/`resolve-citations`'s
;; own `([document])`/`([document labels])` arity pattern) and does not
;; mutate the AST, exactly like every other pass in this namespace.
;;
;; `derive-toc` and `derive-navigation` are both built from one shared
;; notion of "the resolved Section tree": the tree formed by every
;; `:section` Block actually present in `document`, nested exactly as they
;; are nested in the AST -- a Section's own children are the Sections
;; found within its `:blocks`, skipping through any intervening
;; non-Section wrapper structure (a List item, BlockQuote, Directive,
;; Figure/Table content, or footnote) via the same `block-children`
;; traversal `number-block` itself uses. `sections-within`/`child-sections`
;; below build this tree; `derive-toc` renders it nested, `derive-
;; navigation` flattens it into one pre-order sequence.
;;
;; This is a deliberately *different*, and wider, notion of nesting than
;; `number-block`'s own `:section-path` extension: `number-block` only
;; extends its numbering path through a Section whose *own* kind resolves
;; to a `:section-scoped` lexicon entry (ordinarily `:sec`), so a Section
;; mislabeled with some other kind's id prefix (e.g. `"thm:oops"`, TASK-11
;; review finding #1) silently flattens its numbering nesting for its
;; descendants. TOC/navigation nesting has no such gap: it reflects a
;; Section's actual AST position regardless of what its `attr.id` happens
;; to resolve to for numbering purposes -- a mislabeled Section still nests
;; correctly in the TOC/navigation tree, though its own displayed
;; `:number`/`:text` (looked up from the very same `labels` table
;; `number-document` produced) can still show the "wrong" kind's number.
;; That underlying mislabeling is no longer silent -- TASK-36's
;; `kind-role-diagnostics`/`unnumbered-section-diagnostics` warn about
;; both halves of it -- but it is still a warning, not a refusal to
;; build, so the flattening it describes remains reachable for a document
;; its author chose to build anyway. TASK-14 does not attempt to change
;; that; it only avoids introducing an *additional*, navigation-specific
;; instance of it.

(defn bibliography-label-entry
  "The label-table entry for the generated bibliography Section
  (TASK-64): `{:kind :sec :word heading :text heading}` -- a target with
  a WORD and no number.

  Everything else in a label table is numbered, and this one cannot be.
  The section is appended by `resolve-citations`, after the numbering
  pass has run; and in native-mode LaTeX it is not a section at all by
  the time it reaches the PDF -- it is replaced by
  `\\bibliographystyle`/`\\bibliography`, and BibTeX sets the reference
  list under an unnumbered heading. A number here would be one HTML
  printed and the PDF did not.

  So a reference to it prints its name -- \"see the Bibliography\" --
  which is what a reader would write anyway, and which all three outputs
  can say identically. Before this, `@sec:bibliography` resolved to
  nothing at all: the table an emitter is handed is the one numbering
  ran on, and the section did not exist yet (TASK-13 AC #3 claimed it
  was referenceable; it never was)."
  [id heading]
  (when (and id heading)
    {id {:kind (id->kind id) :word heading :text heading}}))

(defn- sections-within
  "Every `:section` Block reachable from `block`: `[block]` if `block`
  itself is a Section (recursion stops there -- that Section's own nested
  Sections are its own children, found separately via `child-sections`,
  not flattened into this result), else every Section found by recursing
  into `block`'s own `block-children`."
  [block]
  (if (= :section (:t block))
    [block]
    (mapcat sections-within (block-children block))))

(defn- top-level-sections
  "Every top-level `:section` Block in `blocks` (a Document's own
  `:blocks`, or any other Block vector), via `sections-within`."
  [blocks]
  (mapcat sections-within blocks))

(defn- child-sections
  "`section`'s own immediate child Sections: every Section found within
  `section`'s own `block-children` (i.e. `section`'s own `:blocks`,
  skipping through any intervening non-Section wrapper structure) -- NOT
  `(sections-within section)` itself, which would just return `[section]`
  unchanged since `section` is itself already a Section."
  [section]
  (mapcat sections-within (block-children section)))

(defn- section-summary
  "The flat `{:id :number :text :level :heading}` summary for Section
  `section`, looking up `:number`/`:text` in `labels` by `section`'s own
  `attr.id` -- nil for both when `section` is unlabeled, or labeled with
  an id whose prefix has no recognized kind (mirrors `number-document`'s
  own \"not a numbering target\" precedent): the Section still occupies
  its own place in the TOC/navigation tree, simply with no number to
  show."
  [labels section]
  (let [entry (get labels (get-in section [:attr :id]))]
    {:id (get-in section [:attr :id])
     :number (:number entry)
     :text (:text entry)
     :level (:level section)
     :heading (:heading section)}))

(defn- section-node
  "One nested TOC entry for Section `section`: `section-summary` plus
  `:children`, one nested entry per `child-sections`, built recursively."
  [labels section]
  (assoc (section-summary labels section)
         :children (mapv (partial section-node labels) (child-sections section))))

(defn derive-toc
  "The table-of-contents derivation (SPEC.md sec9 step 4, TASK-14 AC #1): a
  vector of nested TOC entries, one per top-level `:section` Block in
  `document`, each `{:id :number :text :level :heading :children}` (see
  `section-node`) -- `:children` is that Section's own nested TOC entries,
  so the returned tree's nesting is exactly `document`'s own resolved
  Section nesting, and every entry's `:number`/`:text` comes directly from
  `labels` (default `(number-document document)`) -- the very numbering
  `number-document` already computed, never recomputed or re-derived here.
  Pass a `labels` built with a custom lexicon to match TASK-11 AC #4's
  pattern.

  `front-matter-names` (TASK-54, default `#{}`) is `body-view`'s own: a
  Section written inside an abstract is outside sectioning by
  definition, so it is not a TOC entry either -- and since it takes no
  number, an entry for it would print no number beside every numbered
  sibling."
  ([document] (derive-toc document (number-document document)))
  ([document labels] (derive-toc document labels #{}))
  ([document labels front-matter-names]
   (mapv (partial section-node labels)
         (top-level-sections (:blocks (body-view document front-matter-names))))))

(defn- pre-order-sections
  "Every `:section` Block in `document`, flattened into one pre-order
  sequence (a Section, then its own `child-sections`' pre-order sequences,
  recursively, in document order) -- the same tree `derive-toc` renders
  nested, flattened instead for `derive-navigation`."
  [document]
  (letfn [(walk [section] (cons section (mapcat walk (child-sections section))))]
    (mapcat walk (top-level-sections (:blocks document)))))

(defn derive-navigation
  "The section navigation-links derivation (SPEC.md sec9 step 4, TASK-14
  AC #3): a vector of `{:id :number :text :level :heading :previous
  :next}` entries, one per `:section` Block in `document`, in the exact
  order `pre-order-sections` visits them -- a pre-order walk of the
  resolved Section tree (see this section's top-of-namespace comment).
  `:previous`/`:next` are each either nil (the first/last entry has no
  previous/next respectively) or the *neighboring* entry's own
  `section-summary` (with no `:previous`/`:next` of its own, so this never
  recurses infinitely) -- computed purely from `document`'s AST structure
  and this pre-order position, never read from any authored field (TASK-14
  AC #3's \"never read from authored input\": the AST's `::block`
  `:section` schema, sec4.3, carries no `next`/`previous` field of its own
  at all, so there is nothing of the kind to read even if this function
  wanted to).

  `labels` defaults to `(number-document document)`, exactly as
  `derive-toc`'s does; pass one built with a custom lexicon to match
  TASK-11 AC #4's pattern. `front-matter-names` (default `#{}`) excludes
  front-matter blocks for the reason `derive-toc`'s does."
  ([document] (derive-navigation document (number-document document)))
  ([document labels] (derive-navigation document labels #{}))
  ([document labels front-matter-names]
   (let [entries (mapv (partial section-summary labels)
                       (pre-order-sections (body-view document front-matter-names)))]
     (into []
           (map-indexed (fn [i entry]
                          (assoc entry
                                 :previous (get entries (dec i))
                                 :next (get entries (inc i)))))
           entries))))

(defn- pre-order-blocks
  "Every Block reachable from `block`: itself, then its own nested Blocks
  (`block-children`) recursively, in pre-order -- the same document-order
  walk `number-block` uses to visit every labeled node, reused here to
  find every Figure/Table anywhere in `document` (including one nested
  inside a footnote, list item, block quote, directive, or table cell,
  mirroring TASK-11's own \"every labeled node\" framing)."
  [block]
  (cons block (mapcat pre-order-blocks (block-children block))))

(defn- numbered-blocks-of-type
  "`[block entry]` pairs, in document order, for every `node-type`-typed
  (`:t`) Block anywhere in `blocks` that is itself a numbering target for
  `kind` specifically -- i.e. has an entry in `labels` for its own
  `attr.id` (TASK-11's own \"an un-id'd, or unrecognized-kind-prefixed,
  node is not a numbering target\" precedent: such a Figure/Table simply
  has no `labels` entry and is excluded here too, per TASK-14 AC #2's
  \"every *numbered* Figure/Table\" wording) AND that entry's own `:kind`
  matches `kind`. Without the `:kind` check, a `:table` Block mislabeled
  with a `fig:`-prefixed id would show up in the list of tables wearing
  Figure numbering/wording (TASK-14 review) -- id-prefix-derived kind
  (TASK-11) and AST node type are two independent things, which
  `kind-role-diagnostics` now also warns about in general (TASK-36),
  though this filter stays: a diagnostic is a warning, not a refusal to
  emit, so the list must still be right for a document its author
  chose to build anyway. This list
  must agree with both, not just the node's `:t`."
  [labels node-type kind float-names blocks]
  (into []
        (keep (fn [block]
                (when (or (= node-type (:t block))
                          ;; A float authored as a DIRECTIVE counts too
                          ;; (TASK-59): `{figure}` is a real spelling
                          ;; since TASK-56 and the only one a
                          ;; multi-panel figure has, and native LaTeX's
                          ;; own `.lof` lists it like any other float --
                          ;; so a list built from `:t` alone would be
                          ;; missing entries the compiled PDF has. What
                          ;; makes it belong here is its own labeled
                          ;; kind and a name the CALLER gave as a float
                          ;; directive -- never "any directive" (found
                          ;; by review): numbering is id-prefix-driven
                          ;; by design, so `{admonition #fig:careful}`
                          ;; numbers as a figure, and listing it would
                          ;; print a caption-less "Figure 1.1" in a list
                          ;; the compiled PDF has no entry for. What
                          ;; belongs in a list of figures is what the
                          ;; emitter typesets as a figure float, a fact
                          ;; its own table holds and `haselnuss.cli`
                          ;; injects here the way it injects
                          ;; `directive-kinds` and `sublabel-names`.
                          ;; Panels are not among those names, which is
                          ;; also how a panel stays out of the list
                          ;; whether or not the figure holding it
                          ;; carries an id.
                          (and (= :directive (:t block))
                               (contains? float-names (:name block))))
                  (when-let [entry (get labels (get-in block [:attr :id]))]
                    (when (and (= kind (:kind entry))
                               ;; A PANEL is not an entry of its own:
                               ;; `\listoffigures` lists the figure,
                               ;; never its subfigures. A panel is
                               ;; already outside `float-names` -- it is
                               ;; laid out by its parent rather than as
                               ;; a float of its own -- so this holds
                               ;; for the one case that set cannot: a
                               ;; caller whose own table names a panel a
                               ;; float. The number a sublabelled node
                               ;; carries belongs to the figure above
                               ;; it, so an entry for it would print
                               ;; that figure's number twice (TASK-56).
                               (not (:sublabel entry)))
                      [block entry])))))
        (mapcat pre-order-blocks blocks)))

(defn- numbered-block-entry
  "One list-of-figures/list-of-tables row: `entry` (a `number-document`
  label-table row -- `:kind`/`:path`/`:number`/`:word`/`:text`) plus
  `block`'s own `attr.id` and `:caption` (an Inline vector, spliced in
  verbatim rather than flattened to a string, mirroring this namespace's
  citation-formatting convention of not flattening rich authored content
  when there is no emitter yet to flatten it into)."
  [[block entry]]
  (assoc entry
         :id (get-in block [:attr :id])
         ;; A Figure/Table carries its caption as Inlines; a float
         ;; authored as a directive carries it as a plain-text
         ;; attribute (TASK-57's own decision, since an attribute value
         ;; is a string by schema), so it is wrapped as the one Inline
         ;; it is. Either way an entry's `:caption` is an Inline vector
         ;; and an emitter renders it the one way.
         :caption (or (:caption block)
                      (when-let [caption (get-in block [:attr :props "caption"])]
                        [{:t :str :text caption}])
                      [])))

(defn derive-list-of-figures
  "The list-of-figures derivation (SPEC.md sec9 step 4, TASK-14 AC #2):
  every numbered `:figure` Block anywhere in `document`, in document
  order, as `{:kind :path :number :word :text :id :caption}` (see
  `numbered-block-entry`). `labels` defaults to `(number-document
  document)`; pass one built with a custom lexicon to match TASK-11 AC #4's
  pattern. An unlabeled Figure, or one whose id has no recognized kind
  prefix, has no `labels` entry and is excluded (TASK-11 AC #3/TASK-8 AC
  #3's precedent) -- this list is exactly the *numbered* subset, never
  every Figure Block in the document. A Figure mislabeled with a
  different kind's id prefix (e.g. `thm:proof` on a `:figure` node) is
  also excluded, since its `labels` entry's own `:kind` is `:thm`, not
  `:fig` (TASK-14 review).

  A float authored as a DIRECTIVE -- `{figure}` since TASK-56, and the
  only spelling a multi-panel figure has -- is listed too, since native
  LaTeX's own `.lof` lists it; its panels are not, since
  `\\listoffigures` does not list subfigures either (TASK-59).
  `float-names` (default `#{}`, so a caller passing none gets exactly
  the Figure-Block-only list this returned before) names the directives
  that count: `haselnuss.cli` passes
  `haselnuss.emit.latex/float-directive-names`, since what belongs in a
  list of figures is what an emitter typesets as one."
  ([document] (derive-list-of-figures document (number-document document)))
  ([document labels] (derive-list-of-figures document labels #{}))
  ([document labels float-names]
   (mapv numbered-block-entry
         (numbered-blocks-of-type labels :figure :fig float-names (:blocks document)))))

(defn derive-list-of-tables
  "The list-of-tables derivation (SPEC.md sec9 step 4, TASK-14 AC #2):
  every numbered `:table` Block anywhere in `document`, in document order
  -- see `derive-list-of-figures`'s docstring, which this mirrors exactly
  but for `:table` Blocks instead of `:figure` Blocks."
  ([document] (derive-list-of-tables document (number-document document)))
  ([document labels] (derive-list-of-tables document labels #{}))
  ([document labels float-names]
   (mapv numbered-block-entry
         (numbered-blocks-of-type labels :table :tbl float-names (:blocks document)))))

;; Structural diagnostics pass (TASK-15, sec9 step 5)
;;
;; sec9 step 5 lists three diagnostic kinds: dangling refs/cites, duplicate
;; ids, unknown directive names. The first is already produced by
;; `resolve-cross-refs`/`resolve-citations` above (TASK-12/13); this section
;; adds the other two -- `structural-diagnostics` -- and `resolve-document`,
;; which runs the whole pipeline (number/CrossRef/Cite/structural) and
;; combines every pass's own diagnostics into the one report sec9 describes.
;;
;; Locations, not source positions: this AST (`haselnuss.ast`) carries no
;; source line/column info at all -- neither the parser nor the AST schema
;; tracks it past flexmark's own transient parse tree -- so "naming both
;; locations" for a duplicate id (AC #1) cannot mean a source position.
;; Instead, a location is a structural descriptor: the node's own `:t` plus
;; a `:path`. For a purely Block-nested location the path is a vector of
;; bare indices from the document root down to, and including, the node
;; itself (unchanged from this pass's original shape, TASK-15). A location
;; reached through an Inline-bearing field instead has its path extended
;; with that field's own keyword (`:title`/`:heading`/`:inlines`/`:caption`/
;; `:items`/`:prefix`/`:suffix`/`:blocks` for a footnote `:note`'s own
;; nested Blocks) followed by that field's own index -- reproducible and
;; sufficient to tell any two same-id nodes apart, Block or Inline.
;;
;; Coverage (review fix on this task -- see TASK-15's task comments for the
;; original finding): every `attr.id` anywhere in the document is checked,
;; not just Block-level ones. An Attr-carrying Inline's own `attr.id`
;; (`:link`/`:image`/`:span`, plus TASK-116's `:highlight`/`:sticky-note`,
;; per `ast.clj`'s `::inline` schema, sec4.4) is in the SAME id namespace
;; as a Block's, per
;; `SPEC.md` sec4.1's own Attr definition (`id?: string; //
;; unique document-wide anchor`) -- there is no Block-only carve-out in the
;; spec, and any future HTML emitter would render two same-id nodes, Block
;; or Inline, as an invalid duplicate `id="..."` attribute either way.
;; `id-locations-in-block`/`id-locations-in-inline` below (TASK-87, via
;; `haselnuss.walk/fold` -- two walkers, see below) walk every nested-
;; Inline/nested-Block field a Block/Inline variant can carry (sec4.3/4.4),
;; mirroring `resolve-block`/`resolve-inline`'s own exhaustive coverage
;; (TASK-12), NOT `id->kind`'s narrower, numbering-specific "a numbering
;; target is always a Block" convention (TASK-11) -- that rule decides
;; which nodes are *numbering-eligible*, a different and narrower question
;; than generic id-uniqueness-as-anchor, and does not transfer here. (This
;; pass's first revision made exactly that mistake -- scoping duplicate-id
;; checking to Block-level ids only -- caught by clojure-reviewer on this
;; task.)
;;
;; Two walkers, not one (TASK-87): a single `fold` call cannot reproduce
;; the path convention above unmodified, since `fold`'s own path always
;; carries the schema field name at every hop (e.g. `:blocks 0`), while
;; the Block-nested convention above -- unchanged since TASK-15, and
;; pinned by `resolver-test.clj`'s own `duplicate-id` fixtures, which this
;; file cannot touch -- drops the field name for a purely Block-to-Block
;; hop (a bare index only, e.g. `[0 0]`) and keeps it everywhere else. So:
;; `id-locations-in-block` walks with `:scope` #{`::block` `::row`
;; `::cell`} ONLY (no `::inline`), which makes every field-name keyword
;; `fold` contributes within THAT walker unambiguously a Block-to-Block
;; hop -- `compact-block-suffix` strips it back out, keeping the index(es)
;; that followed (or synthesizing a single `0` for a `:one` position with
;; none of its own, e.g. Figure's own `:content` -- `block-children`'s own
;; pre-TASK-87 single-element-vector convention for exactly that
;; position). `id-locations-in-inline` walks with `:scope` #{`::inline`
;; `::cite-item`} instead, and uses `fold`'s own path UNCHANGED -- the
;; pre-TASK-87 walk already kept field names uniformly on the Inline side
;; (`:inlines`/`:items`/`:prefix`/`:suffix`), which is exactly `fold`'s own
;; convention too (confirmed field-by-field, including CiteItem's own
;; declared `:prefix`-before-`:suffix` order).
;;
;; A footnote `:note`'s own `:blocks` is the one position that crosses from
;; the Inline walker to the Block one, and is why `::block` is excluded
;; from the Inline walker's `:scope` rather than folded in alongside
;; `::inline`: `fold`'s own automatic recursion has no way to know that a
;; Note's `:blocks` keeps its field name (the Inline side's own
;; convention) while a Section's does not (the Block side's own), since
;; both are literally the keyword `:blocks`, both `::block`-typed --
;; indistinguishable from the field name and ref-kind alone. Excluding
;; `::block` here (mirroring `citation-scope`'s own precedent for
;; excluding `::cite-item`, one pass earlier) forces `:note` to be a
;; DELIBERATE, manual exception in `id-locations-inline-visit` -- the one
;; place this walk still keeps the `:blocks` keyword for a Block-typed
;; position -- rather than something the two walkers could silently
;; disagree about.
;;
;; This design accepts two documented behavior differences from the pre-
;; TASK-87 walk. Neither is exercised by any `resolver-test.clj` fixture
;; (checked directly against every `:path` assertion it pins, not
;; assumed), and neither ever reaches rendered document output (id-
;; locations feeds only a `:duplicate-id` diagnostic, never anything
;; emitted) -- but they are NOT equally inert, and are recorded here
;; exactly as each was independently verified (clojure-reviewer, TASK-87
;; review; reproduced from a git worktree pinned at the pre-TASK-87
;; commit):
;;
;; - A Table cell's or a List item's own nested Block now carries a path
;;   that preserves which row/cell or item it came from (`compact-block-
;;   suffix` keeps every index `fold` contributes), rather than the old
;;   `block-children`/`table-cells`' own lossy `mapcat`-everything-
;;   together flattening, which collapsed two different cells' own nested
;;   Blocks to indistinguishable bare indices. This one is fully inert: a
;;   `:duplicate-id` diagnostic's own message text is built from
;;   `:node-type` alone (`duplicate-id-diagnostics`, below), never
;;   `:path`, so no observable text ever depends on which of the two path
;;   shapes a Table/List position gets.
;;
;; - A block's own directly-authored `:heading`/`:caption` content is now
;;   reported immediately after the block itself, ahead of its nested
;;   Block descendants, rather than after them (`id-locations-block-
;;   visit` computes both together, before `fold`'s own recursion into
;;   the remaining, Block-typed fields that follow) -- which also makes
;;   this pass's own Section ordering consistent with `cite-keys-in-
;;   block`'s (TASK-87 again), which already put heading before blocks.
;;   HONEST NOTE ON TASK-87's OWN AC #2 ("id locations are identical to
;;   before, including order"): unlike the Table/List path change above,
;;   this one is NOT inert. `duplicate-id-diagnostics`' own message
;;   string joins `:node-type` names in `:locations` order, so for a
;;   document where a duplicate id spans a block's own `:heading`/
;;   `:caption` AND that SAME block's own nested-Block descendant, the
;;   exact text of a real `:duplicate-id` build warning changes -- e.g. a
;;   Section whose `:heading` and whose one nested Figure both collide
;;   with `meta.title` and a sibling Image reports `:node-type` order
;;   `(:span :figure :span :image)` before this port and `(:span :span
;;   :figure :image)` after (confirmed both ways). This is a disclosed,
;;   deliberate change, not an oversight glossed over as "identical": the
;;   NEW order is `:heading` before `:blocks`, which is the schema's own
;;   TRUE declaration order (`haselnuss.walk/table`'s `:section` entry
;;   lists `:heading` before `:blocks`, confirmed against the derived
;;   table directly) -- the old, hand-written `id-locations-in-block`
;;   instead visited a Section's nested `:blocks` before its own
;;   `:heading`, an inconsistency nobody had reason to notice by hand
;;   (this SAME pass's own `cite-keys-in-block`, written independently,
;;   already used heading-before-blocks). That is exactly the class of
;;   bug this milestone exists to close (doc-6): a human had to remember
;;   the right traversal order for every node type, by hand, in every
;;   pass, and this one case quietly disagreed with its own sibling pass
;;   for no documented reason. Reverting to the old order would mean
;;   deliberately reintroducing that inconsistency instead of fixing it,
;;   which is not what "identical to before" is worth doing here -- but
;;   it is a real, user-visible change to a warning's exact wording for a
;;   narrow class of documents, and is recorded as exactly that rather
;;   than waved through as equivalent to the Table/List change above.
;;
;; Scope limit on the extension registry (AC #2): TASK-16 ("Implement the
;; extension registry") is a not-yet-implemented sibling task, not a
;; dependency of this one, so this pass cannot depend on its real
;; name -> {kind, emit, lower} registry shape. `directive-registry` here is
;; instead anything usable with `contains?` keyed by directive name -- a
;; plain set of registered names today (defaulting to `#{}`, since this
;; codebase registers no directive names anywhere yet), or, once TASK-16
;; lands, its registry map directly (`contains?` behaves identically for a
;; set and a map, so no adapter is needed then). A Directive's own
;; `:fallback` is not walked for this check either, mirroring
;; `resolve-block`'s TASK-12-documented scope limit for the same underlying
;; reason: `:fallback` is target-specific degradation content for TASK-17's
;; later `lower` pass, orthogonal to this validation step.
;;
;; The third diagnostic kind (TASK-36), deferred by TASK-15 and built
;; here: a node's id-derived numbering `:kind` (`id->kind`'s prefix
;; convention, sec4.1) can disagree with its actual AST role. Numbering is
;; deliberately driven by the id prefix alone and never by `:t` (see this
;; namespace's own docstring, TASK-11 AC #4) -- which is exactly what lets
;; any node type register under any kind, and exactly what makes a
;; disagreement invisible without a check like this one. Two distinct
;; conditions, each with its own diagnostic type because the remedies
;; differ (see `kind-role-diagnostics`/`unnumbered-section-diagnostics`):
;; a labeled node whose `:t` is not among its kind's own `:node-types`
;; (TASK-11 review finding #1's `"thm:oops"`-on-a-Section, and TASK-14's
;; own list-of-figures symptom, generalized), and a Section whose id
;; resolves to no numbering kind at all -- which silently drops that
;; Section out of its own children's number path.

(defn- kind-role-diagnostics
  "One `{:type :kind-role-mismatch :id :kind :node-type :expected
  :message}` diagnostic (AC #1/#2) per labeled *Block* anywhere in
  `document` whose own `:t` is absent from its id-derived kind's
  `:node-types` set in `lexicon` (see `default-lexicon`), in document
  order.

  Only kinds that actually declare `:node-types` are checked -- a custom
  kind whose author never said what it labels is not second-guessed
  here. An id with no recognized kind at all is likewise not this
  diagnostic's concern: it makes the node a non-target rather than a
  mislabeled one, which TASK-8 AC #3's own precedent already treats as
  fine (an un-id'd Figure/Table parses and is simply not a numbering
  target).

  Three scope decisions, recorded rather than left to be discovered:

  - **Blocks only.** `duplicate-id-diagnostics` walks Inline ids too
    (they share one id namespace, TASK-15), but a *numbering* kind is a
    Block-level concept -- `number-block`'s own walk never visits an
    Inline -- so an inline `[x]{#fig:y}` has no role to disagree with,
    and checking one would fire on every parsed standalone-image Figure,
    whose inner `:image` carries the Figure's own id by construction.
  - **Every attr-bearing Block, not just the ones a kind names.** A
    `:code-block` or `:list` wearing `#fig:` is reported, which is a
    deliberate widening past this task's own \"Figure/Table/MathBlock/
    Directive\" wording: sec6 makes any id-bearing node a numbering
    target keyed by its prefix, so the same mislabeling has the same
    effect there, and excluding those two would be arbitrary.
  - **A Directive's `:fallback` is not walked**, mirroring
    `resolve-block`'s own TASK-12-documented limit for the same reason:
    fallback content is target-specific degradation material for
    `haselnuss.lower`, not part of the document being validated.

  A `:section` also losing its level from its children's numbers is
  reported separately, by `unnumbered-section-diagnostics` -- see there
  for why that condition is broader than a role mismatch and must be
  checked on its own rather than folded in here."
  [document lexicon]
  (into []
        (comp (mapcat pre-order-blocks)
              (keep (fn [block]
                      (let [id (get-in block [:attr :id])
                            kind (id->kind id)
                            expected (:node-types (get lexicon kind))]
                        (when (and expected (not (contains? expected (:t block))))
                          {:type :kind-role-mismatch
                           :id id
                           :kind kind
                           :node-type (:t block)
                           :expected expected
                           :message (str "id \"" id "\" has kind " kind
                                         ", which labels "
                                         (str/join "/" (sort (map name expected)))
                                         " nodes, but this node is a "
                                         (name (:t block)))})))))
        (:blocks document)))

(defn- directive-kind-diagnostics
  "One `{:type :directive-kind-mismatch :name :id :kind :expected-kind
  :message}` diagnostic (TASK-48 AC #1) per `:directive` Block anywhere
  in `document` whose id-derived kind disagrees with the kind its own
  `:name` maps to in `directive-kinds` -- a plain `{directive-name
  kind-keyword}` map (`haselnuss.emit.latex/directive-lexicon-kinds`
  builds one from a directive-environment table; default `{}`, which
  reports nothing).

  The condition this catches is one node numbered twice, differently:
  `{lemma #thm:x}` numbers from the `thm` prefix everywhere this
  codebase does the counting, and from the `lemma` environment's own
  `\\newtheorem` counter in native-mode LaTeX, so the HTML reads
  \"Theorem 1\" and the PDF \"Lemma 1\" for the same node, with nothing
  said about it. Before TASK-40 the built-in lexicon had one
  theorem-like kind, so every authored lemma/corollary/definition/proof
  had to wear `thm:` and is now silently in this state.

  Native mode specifically -- `--computed-numbers` bakes the resolver's
  own number into an unnumbered environment, so both targets there read
  \"Theorem 1\" and agree. The diagnostic still fires, and should: the
  document is authored once and converted to either, and the mode that
  hands the counting to LaTeX is the default one.

  This is a sibling of `kind-role-diagnostics`, not a case of it, and
  cannot be folded in: that check compares an id's kind against the AST
  *role* it labels, and a Directive is a perfectly conventional role for
  `thm` -- the disagreement here is with the directive's own *name*,
  which is not an AST property at all and which the resolver has no
  table for on its own. Hence the injected map.

  Silent in three shapes, each deliberate (AC #3):

  - a directive whose `:name` has no entry in `directive-kinds` -- an
    unmapped or custom directive declares no kind of its own, so there
    is nothing to disagree with;
  - a directive with no id, or an id with no `kind:` prefix -- not a
    numbering target at all (TASK-8 AC #3's own precedent);
  - a prefix that is not a kind in `lexicon` -- likewise a non-target
    here, and the shape an author uses for a LaTeX `\\label` namespace
    this lexicon has no kind for, which `haselnuss.cli` deliberately
    leaves alone in native mode.

  Note the last one is checked against `lexicon` rather than against
  `directive-kinds`' own values: a prefix naming a kind the lexicon
  knows but no directive maps to (`#fig:x` on a `{lemma}`) IS a genuine
  double-numbering, and dropping it would be the more common mislabeling
  of the two.

  Deliberately not disjoint from `kind-role-diagnostics`, on the same
  terms `unnumbered-section-diagnostics` records for its own overlap: a
  `{lemma #sec:x}` reports twice, because it has two independent
  problems -- it wears a kind that labels Sections rather than
  Directives, *and* it is numbered as a section here and as a lemma in
  native LaTeX. Neither is visible from the other, and their remedies
  differ."
  [document directive-kinds lexicon]
  (into []
        (comp (mapcat pre-order-blocks)
              (filter #(= :directive (:t %)))
              (keep (fn [directive]
                      (let [id (get-in directive [:attr :id])
                            kind (id->kind id)
                            expected (get directive-kinds (:name directive))]
                        (when (and expected kind (contains? lexicon kind) (not= kind expected))
                          {:type :directive-kind-mismatch
                           :name (:name directive)
                           :id id
                           :kind kind
                           :expected-kind expected
                           :message (str "directive \"" (:name directive) "\" is tagged with id \""
                                         id "\", whose kind " kind " is not the " expected
                                         " this directive maps to: it is numbered as " kind
                                         " here, but native-mode LaTeX numbers it with the "
                                         (:name directive) " environment's own counter, so one"
                                         " node gets two different numbers")})))))
        (:blocks document)))

(defn- unnumbered-section-diagnostics
  "One `{:type :unnumbered-section-id :id :kind :message}` diagnostic
  (the other half of AC #1) per `:section` Block anywhere in `document`
  whose `attr.id` does not resolve to a `:section-scoped` kind in
  `lexicon`, in document order.

  The predicate is exactly the one `number-block` itself branches on --
  it extends `:section-path` only for a `:section` whose own kind
  resolved to a `:section-scoped` entry -- which is what makes this the
  literal reading of AC #1's \"when that mismatch would silently affect
  its own children's numbering path\". Such a Section is silently
  *transparent*: it consumes no counter, and every descendant numbers as
  though it were not there, skipping a level.

  Three shapes reach it, and an earlier version of this pass reported
  only the first two (TASK-36 review finding #1):

  - no `kind:` prefix at all (`\"#intro\"`);
  - an unrecognized prefix (`\"#part:one\"`);
  - a *recognized* kind that is `:global` rather than `:section-scoped`
    (`\"#thm:oops\"`), or one whose lexicon entry declares no
    `:node-types` at all. Every kind registered through
    `haselnuss.registry`'s own `:kind` fragment lands in that last
    shape, so restricting this to unrecognized prefixes left the most
    likely real-world case entirely undiagnosed.

  Deliberately NOT disjoint from `kind-role-diagnostics`: a `:section`
  wearing `#thm:oops` genuinely has two independent problems -- it
  labels the wrong role, *and* it drops a level from its children's
  numbers -- and one of them is invisible from the other. A `:section`
  wearing `#fig:one` has only the first, since `fig` is section-scoped
  and the path is still extended. Reporting each condition on its own
  terms is what keeps that distinction visible.

  Section-specific, because the consequence is: on a Figure or Table the
  same id just makes it a non-target, which TASK-8 AC #3's own precedent
  already treats as fine and which misleads nobody.

  An id-less Section is NOT reported: this namespace's own docstring
  documents an unlabeled Section as intentionally transparent for
  nesting, which is a choice an author makes by omitting the id. This is
  for an author who *did* give the Section an id and would reasonably
  expect it to number."
  [document lexicon]
  (into []
        (comp (mapcat pre-order-blocks)
              (keep (fn [block]
                      (let [id (get-in block [:attr :id])
                            kind (id->kind id)]
                        (when (and (= :section (:t block))
                                   id
                                   (not= :section-scoped (:counter (get lexicon kind))))
                          {:type :unnumbered-section-id
                           :id id
                           :kind kind
                           :message (str "section id \"" id "\" does not resolve to a"
                                         " section-scoped numbering kind, so this section is"
                                         " not numbered and its children's numbers skip a"
                                         " level")})))))
        (:blocks document)))

(defn- compact-block-suffix
  "The portion of a `haselnuss.walk/fold`-computed path AFTER a known
  prefix, collapsed from `fold`'s own field-name-plus-index convention
  down to `block-children`'s pre-TASK-87 bare-index one (TASK-87 -- see
  this section's own top-of-namespace comment): every field-name keyword
  is dropped, keeping the index(es) that immediately followed it, or
  contributing a synthetic `0` when none followed at all (a `:one`
  position, e.g. Figure's own `:content`) -- `block-children`'s own
  single-element-vector convention for exactly that position. Safe to
  apply unconditionally here because `id-locations-block-walker`'s own
  `:scope` excludes `::inline` entirely, so every field-name keyword
  `fold` can possibly contribute within it is, by construction, a
  Block-to-Block hop -- never a footnote `:note`'s own `:blocks`, which is
  reachable only through `::inline` and is handled by the OTHER walker
  instead (`id-locations-inline-visit`)."
  [suffix]
  (loop [segs (seq suffix) acc []]
    (if-not segs
      acc
      (let [seg (first segs) more (rest segs)]
        (if (keyword? seg)
          (let [[indices remaining] (split-with integer? more)]
            (recur (seq remaining) (into acc (if (seq indices) indices [0]))))
          (recur (seq more) (conj acc seg)))))))

(def ^:private id-locations-block-scope
  "`haselnuss.walk/fold`'s own `:scope` for `id-locations-in-block`
  (TASK-87): `::block` for a nested Block, plus `::row`/`::cell` so a
  Table's own `:head`/`:rows` reach down to each cell's own `:blocks` --
  deliberately EXCLUDING `::inline` (see this section's own top-of-
  namespace comment for why: it is what makes `compact-block-suffix` safe
  to apply unconditionally within this walker)."
  #{::ast/block ::ast/row ::ast/cell})

(declare id-locations-in-block)

(defn- id-location
  "One `:locations` entry for `node` at `path`: `{:node-type :path}`, plus
  `:source-line` (and `:source-file` when known) when the parser recorded
  where the node was written (TASK-143)."
  [node path]
  (let [{:keys [file line]} (ast/source node)]
    (cond-> {:node-type (:t node) :path path}
      line (assoc :source-line line)
      file (assoc :source-file file))))

(defn- id-locations-inline-visit
  "`id-locations-inline-walker`'s own `:visit` (TASK-87): every Inline
  this walk reaches contributes its own `attr.id` (`:link`/`:image`/
  `:span`, plus TASK-116's `:highlight`/`:sticky-note` -- the Inline
  variants that carry an `Attr` at all, sec4.4), paired with `{:node-type
  :path}` at `fold`'s own path,
  UNCHANGED (see this section's own top-of-namespace comment for why the
  Inline side needs no compaction, unlike the Block side). `fold`'s own
  recursion already covers every other position this walk needs -- every
  `[:many ::ast/inline]` field, and `:items` then each item's own
  `:prefix`/`:suffix` for `:cite`, via `id-locations-inline-walker`'s own
  `::cite-item` scope -- EXCEPT an Inline's own Block-typed field, which
  is out of this walker's `:scope`: handled here instead, recursing into
  `id-locations-in-block` (the OTHER walker) for each Block, at `path`
  extended by that field's own keyword.

  WHICH fields those are is asked of `haselnuss.walk/fields-holding`
  rather than hardcoded to `:note`'s `:blocks`. It was hardcoded, and the
  day TASK-116 added `:sticky-note` a duplicate `attr.id` inside one
  stopped being reported at all -- the same document with the id inside a
  `:note` reported `:duplicate-id` correctly."
  [acc path ref-kind node]
  (let [own (when-let [id (get-in node [:attr :id])]
              [[id (id-location node path)]])
        acc (into acc own)]
    (if (= ref-kind ::ast/inline)
      (reduce (fn [acc field]
                (into acc (mapcat (fn [i block] (id-locations-in-block (conj path field i) block))
                                  (range) (get node field))))
              acc
              (walk/fields-holding ::ast/inline node ::ast/block))
      acc)))

(def ^:private id-locations-inline-walker
  {:table walk/table :scope #{::ast/inline ::ast/cite-item} :visit id-locations-inline-visit})

(defn- id-locations-in-inlines
  "Every `[id location]` pair reachable from `inlines`, each at `path`
  extended by its own index (TASK-87: `id-locations-inline-walker`,
  walked via `haselnuss.walk/fold`)."
  [path inlines]
  (into [] (mapcat (fn [i inline] (walk/fold id-locations-inline-walker [] ::ast/inline inline (conj path i)))
                   (range) inlines)))

(defn- id-locations-block-visit
  "`id-locations-block-walker`'s own `:visit` (TASK-87; `prefix-len` is
  the length of the caller-supplied starting path -- see `id-locations-
  in-block`): every Block this walk reaches contributes its own
  `attr.id` (if any), paired with `{:node-type :path}` at the legacy path
  (`compact-block-suffix` applied to whatever `fold` contributed beyond
  `prefix-len`, appended onto the unchanged prefix itself), followed
  immediately by every id in its own direct Inline-bearing field, if
  any -- a Section's `:heading`, a Para's `:inlines`, a Figure or Table's
  `:caption` -- via `id-locations-in-inlines`, at that SAME legacy path
  extended by the field's own keyword. `fold`'s own recursion into this
  node's remaining Block-typed fields (its nested Blocks) runs
  afterwards, once this function returns -- see this section's own
  top-of-namespace comment (\"HONEST NOTE ON TASK-87's OWN AC #2\") for
  the one documented, verified, user-visible ordering difference this
  produces relative to the pre-TASK-87 walk: this now matches the
  schema's own TRUE declaration order (`:heading` before `:blocks`), not
  the pre-TASK-87 walk's own, inconsistent-with-its-sibling-pass order."
  [prefix-len acc path _ref-kind node]
  (let [legacy-path (into (subvec path 0 prefix-len) (compact-block-suffix (subvec path prefix-len)))
        own (when-let [id (get-in node [:attr :id])]
              [[id (id-location node legacy-path)]])
        own-inline (case (:t node)
                     :section (id-locations-in-inlines (conj legacy-path :heading) (:heading node))
                     :para (id-locations-in-inlines (conj legacy-path :inlines) (:inlines node))
                     (:figure :table) (id-locations-in-inlines (conj legacy-path :caption) (:caption node))
                     [])]
    (-> acc (into own) (into own-inline))))

(defn- id-locations-block-walker
  "The `id-locations-in-block`-family walker rooted at a starting path of
  length `prefix-len` (TASK-87) -- a fresh one per call, since
  `id-locations-block-visit` closes over `prefix-len` to know where its
  own legacy-path compaction should start (see there)."
  [prefix-len]
  {:table walk/table :scope id-locations-block-scope :visit (partial id-locations-block-visit prefix-len)})

(defn- id-locations-in-block
  "Every `[id location]` pair (see this section's own top-of-namespace
  comment) reachable from `block` at structural `path` (TASK-87:
  `id-locations-block-walker`, walked via `haselnuss.walk/fold`)."
  [path block]
  (walk/fold (id-locations-block-walker (count path)) [] ::ast/block block path))

(defn- id-locations-in-document
  "Every `[id location]` pair (see `id-locations-in-block`/`id-locations-
  in-inline`) for every id anywhere in `document`: `(:title (:meta
  document))` first (an Inline vector that can itself carry a Link/Image/
  Span's own id -- mirrors `resolve-cross-refs`/`resolve-citations`'s own
  inclusion of `meta.title` in their walks), then every top-level Block in
  `document`'s own `:blocks`, in document order."
  [document]
  (into (id-locations-in-inlines [:title] (get-in document [:meta :title]))
        (mapcat (fn [i block] (id-locations-in-block [i] block))
                (range) (:blocks document))))

(defn- location-label
  "How `duplicate-id-diagnostics` names one node: its type, then `at
  path:line` when it is known where it was written (TASK-143)."
  [{:keys [node-type source-file source-line]}]
  (str (name node-type) (when (and source-file source-line)
                          (str " at " source-file ":" source-line))))

(defn- duplicate-id-diagnostics
  "One `{:type :duplicate-id :id :locations :message}` diagnostic (AC #1)
  per id used by 2+ nodes anywhere in `document` -- Block-level or
  Inline-level (a Link/Image/Span's own `attr.id`) alike, per this
  section's top-of-namespace comment -- in first-occurrence document
  order; `:locations` names every node using that id (see
  `id-locations-in-block`/`id-locations-in-inline`), not just the first
  two, so a three-or-more-way collision is reported in full rather than
  only partially."
  [document]
  (let [pairs (id-locations-in-document document)
        by-id (group-by first pairs)
        first-seen (distinct (map first pairs))]
    (into []
          (keep (fn [id]
                  (let [group (get by-id id)]
                    (when (> (count group) 1)
                      {:type :duplicate-id
                       :id id
                       :locations (mapv second group)
                       :message (str "duplicate id \"" id "\": used by " (count group)
                                     " nodes (" (str/join ", " (map (comp location-label second) group))
                                     ")")}))))
          first-seen)))

(defn- unknown-directive-diagnostics
  "One `{:type :unknown-directive :name :id :message}` diagnostic (AC #2)
  per `:directive` Block anywhere in `document` whose `:name` has no entry
  in `directive-registry` (see this section's top-of-namespace comment for
  what `directive-registry` accepts), one per occurrence -- not
  deduplicated by name, mirroring `resolve-cross-ref`/`resolve-cite-node`'s
  own per-occurrence warning convention."
  [document directive-registry]
  (into []
        (comp (mapcat pre-order-blocks)
              (filter #(= :directive (:t %)))
              (remove #(contains? directive-registry (:name %)))
              (map (fn [directive]
                     {:type :unknown-directive
                      :name (:name directive)
                      :id (get-in directive [:attr :id])
                      :message (str "unknown directive \"" (:name directive)
                                    "\": not registered in the extension registry")})))
        (:blocks document)))

(def ^:private division-kinds
  "The built-in kinds that name a sectioning DIVISION (TASK-53, extended
  by TASK-82's `:part`), mapped to the word for the LaTeX sectioning
  command a Section of that division is emitted as. Only these three are
  checked by `division-kind-diagnostics`: a custom section-scoped kind on
  a heading is its author's own arrangement, and warning about it would
  be this namespace guessing at a convention it does not define."
  {:part "part"
   :ch "chapter"
   :sec "section"})

(defn- division-kind-diagnostics
  "One `{:type :division-kind-mismatch :id :kind :expected-kind :level
  :message}` diagnostic per `:section` Block whose id prefix names a
  DIVISION other than the one it is actually emitted as (TASK-53).

  The drift this catches is invisible from either end on its own, and
  silent. In a chaptered document a level-1 Section is emitted as
  `\\chapter`, so native-mode LaTeX -- where cleveref names a target by
  the sectioning command it found, not by anything this codebase wrote --
  prints `Chapter 1` for a `\\Cref` to it. If that same Section is
  labeled `#sec:intro`, the resolver numbers it as a `sec` and HTML and
  computed-numbers mode both print `Section 1` for the same reference.
  Confirmed against a real pdflatex: one node, two different words, in
  two outputs of one document, with nothing said. The mirror case is a
  `#ch:` id in a document that never opted into chapters, emitted as
  `\\section` and read back as `Section`.

  Exactly the shape `directive-kind-diagnostics` already warns about for
  a `{lemma #thm:x}`, and warned about for the same reason: the id
  prefix and the emitted construct are two independent statements of
  what a node is, and a build should say so when they disagree rather
  than let two targets quietly differ.

  A Section with no id, or one whose prefix is neither of
  `division-kinds`, is not reported here -- an unlabeled Section is
  intentionally transparent (see `unnumbered-section-diagnostics`), and
  a non-division kind on a Section is `kind-role-diagnostics`' own case.

  Three-way since TASK-82: in a PARTED document level 1 expects `:part`
  and level 2 expects `:ch` (both shifted down the one extra division),
  in a chapter-only document level 1 still expects `:ch`, and every other
  level in either shape expects `:sec`."
  [document]
  (let [chapters? (chaptered? document)
        parts? (parted? document)]
    (into []
          (comp (mapcat pre-order-blocks)
                (keep (fn [block]
                        (let [kind (id->kind (get-in block [:attr :id]))
                              level (:level block)
                              expected (cond
                                         (and parts? (= 1 level)) :part
                                         (and parts? (= 2 level)) :ch
                                         (and chapters? (= 1 level)) :ch
                                         :else :sec)]
                          (when (and (= :section (:t block))
                                     (contains? division-kinds kind)
                                     (not= kind expected))
                            {:type :division-kind-mismatch
                             :id (get-in block [:attr :id])
                             :kind kind
                             :expected-kind expected
                             :level (:level block)
                             :message (str "section id \"" (get-in block [:attr :id])
                                           "\" names the " kind " division, but a level-"
                                           (:level block) " heading in this document is"
                                           " emitted as \\" (division-kinds expected)
                                           ": a native-mode LaTeX reference to it is named"
                                           " after the sectioning command, so it would print"
                                           " one word where HTML prints the other")})))))
          (:blocks document))))

(defn- front-matter-diagnostics
  "Two `{:type :front-matter-not-numbered|:front-matter-section ...}`
  diagnostics for front-matter blocks (TASK-54, `front-matter-names`),
  in document order.

  `body-view` makes the exclusion structural, which is right and which
  is also silent: the author who wrote `:::{abstract #thm:a}` gets an
  abstract that is simply not a target, and every `@thm:a` pointing at
  it reports a dangling reference naming a label they can see in their
  own source. These say the actual reason instead.

  - An id whose prefix names a numbering kind, on the block itself. The
    prefix is how every other node opts into numbering, so writing one
    here is a reasonable mistake to make, and the resulting dangling
    reference does not explain it.
  - A `:section` anywhere inside one. It is outside sectioning by
    definition -- unnumbered, absent from the TOC -- and the emitted
    output shows a heading that looks like every other heading, which is
    exactly when a build should say what is different about it."
  [document front-matter-names lexicon]
  (into []
        (comp
         (filter (partial front-matter-block? front-matter-names))
         (mapcat
          (fn [block]
            (let [id (get-in block [:attr :id])
                  kind (id->kind id)]
              (concat
               (when (contains? lexicon kind)
                 [{:type :front-matter-not-numbered
                   :name (:name block)
                   :id id
                   :kind kind
                   :message (str "front-matter block \"" (:name block) "\" carries id \"" id
                                 "\", whose " kind " prefix would number any other node: a"
                                 " front-matter block is outside numbering entirely, so it takes"
                                 " no number and every reference to it dangles")}])
               (keep (fn [inner]
                       (when (= :section (:t inner))
                         {:type :front-matter-section
                          :name (:name block)
                          :id (get-in inner [:attr :id])
                          :message (str "a section is written inside front-matter block \""
                                        (:name block) "\": front matter is outside sectioning,"
                                        " so this heading is neither numbered nor listed in the"
                                        " table of contents, unlike every other heading in the"
                                        " document")}))
                     (mapcat pre-order-blocks (:blocks block))))))))
        (:blocks document)))

(defn structural-diagnostics
  "The structural-diagnostics pass (SPEC.md sec9 step 5's remaining half --
  dangling refs/cites are already collected by `resolve-cross-refs`/
  `resolve-citations`, TASK-12/13): duplicate ids, Block-level or
  Inline-level alike (TASK-15 AC #1, via `duplicate-id-diagnostics`),
  `:directive` names absent from `directive-registry` (TASK-15 AC #2, via
  `unknown-directive-diagnostics`; default `#{}` -- see this section's
  top-of-namespace comment), and (TASK-36) id-prefix kinds that disagree
  with the node they label, via `kind-role-diagnostics` and
  `unnumbered-section-diagnostics` against `lexicon` (default
  `default-lexicon`, the same default `number-document` uses -- a caller
  numbering with a merged lexicon must pass the same one here, or a
  custom kind's own nodes all read as mismatched).

  `directive-kinds` (TASK-48, default `{}`) is `directive-kind-
  diagnostics`' own `{directive-name kind-keyword}` map: a directive
  whose id prefix names a different kind than its own name does gets
  numbered twice, differently, across targets. It is injected rather
  than derived because the mapping lives in an emitter's environment
  table, which this namespace deliberately knows nothing about -- see
  `haselnuss.emit.latex/directive-lexicon-kinds`.

  Returns a flat vector in pass order: duplicate-id diagnostics first
  (first-occurrence-of-the-duplicated-id document order), then
  unknown-directive, then kind-role-mismatch, then
  directive-kind-mismatch, then unnumbered-section-id, then (TASK-53)
  division-kind-mismatch (each in document order).

  `division-kind-diagnostics` needs no option of its own: what a Section
  is emitted as follows from its `:level` and the document's own
  `meta.topLevelDivision`, both of which are in `document`."
  ([document] (structural-diagnostics document #{}))
  ([document directive-registry] (structural-diagnostics document directive-registry default-lexicon))
  ([document directive-registry lexicon] (structural-diagnostics document directive-registry lexicon {}))
  ([document directive-registry lexicon directive-kinds]
   (structural-diagnostics document directive-registry lexicon directive-kinds #{}))
  ([document directive-registry lexicon directive-kinds front-matter-names]
   (-> (duplicate-id-diagnostics document)
       (into (unknown-directive-diagnostics document directive-registry))
       (into (kind-role-diagnostics document lexicon))
       (into (directive-kind-diagnostics document directive-kinds lexicon))
       (into (unnumbered-section-diagnostics document lexicon))
       (into (division-kind-diagnostics document))
       (into (front-matter-diagnostics document front-matter-names lexicon)))))

(defn- disambiguate-duplicate-ids
  "Gives every LATER node that reuses an id its own unique id (TASK-133),
  `<id>-dup<n>` (n = 2, 3, ... in document order), leaving the first
  definition's id alone. Numbering, and every emitter's lookup of a node's
  own number by its `attr.id`, then give each duplicate its own number
  rather than the first node's, while an `@id` reference -- which names the
  original id -- still resolves to the first definition. The collision
  itself is reported from the document as authored (see `resolve-document`),
  so the warning still names the id the author wrote.

  A generated name an author already used (a document with `fig:x` twice
  AND a node written `fig:x-dup2`) is skipped: the next free n is taken
  instead (TASK-146), so the rename never creates a collision that nothing
  reports."
  [document]
  (let [seen (volatile! {})
        taken (into #{} (map first) (id-locations-in-document document))
        walker {:table walk/table :scope cross-ref-scope
                :rewrite-fn (fn [_ref-kind node]
                              (if-let [id (get-in node [:attr :id])]
                                (if (contains? @seen id)
                                  (let [n (loop [n (get @seen id)]
                                            (if (contains? taken (str id "-dup" n))
                                              (recur (inc n))
                                              n))]
                                    (vswap! seen assoc id (inc n))
                                    [(assoc-in node [:attr :id] (str id "-dup" n))])
                                  (do (vswap! seen assoc id 2)
                                      [node]))
                                [node]))}]
    (first (walk/rewrite walker ::ast/document document))))

(defn resolve-document
  "The resolver's full pipeline (SPEC.md sec9 steps 1-3 and 5; step 4's
  derived TOC/list-of-figures/list-of-tables/navigation are read separately
  via `derive-toc`/`derive-list-of-figures`/`derive-list-of-tables`/
  `derive-navigation`, since they are extra structures derived alongside
  the resolved document, not part of it or of its diagnostics): numbers
  every labeled node (`number-document`), resolves every `CrossRef`
  (`resolve-cross-refs`) and formats every `Cite` plus appends the
  generated bibliography section (`resolve-citations`), then runs
  `structural-diagnostics` over that fully resolved result -- so duplicate-
  id checking also sees the generated bibliography section's own,
  already-disambiguated id (TASK-13's own `unique-bibliography-id`
  guarantees it never collides on its own).

  Returns `{:document document' :diagnostics diagnostics :labels labels
  :ordered-keys ordered-keys :bibliography-id bib-id}`: `document'` is
  the fully resolved Document; `labels` is the `number-document` table
  this pipeline itself numbered with -- returned rather than left for a
  caller to recompute, because it is the table every `CrossRef`'s
  `:text` was baked from, so an emitter handed any *other* table can
  print a number that disagrees with the reference pointing at it. (A
  re-run over the resolved document differs by exactly one entry, the
  generated bibliography Section's own, which no emitter reads.) `diagnostics` is every warning from every step, in
  step order (AC #3) -- `resolve-cross-refs`'s dangling-cross-ref
  warnings, then `resolve-citations`'s own warnings (already ordered per
  its own docstring: unrecognized-style, dangling-citation,
  bibliography-id-collision), then `structural-diagnostics`'s own
  duplicate-id/unknown-directive/kind-role-mismatch/directive-kind-
  mismatch/unnumbered-section-id
  diagnostics (TASK-36 AC #3 -- the last three run against the same
  `:lexicon` the numbering pass used, so a custom kind is judged by the
  role it declares for itself rather than by the built-in conventions).
  `ordered-keys`/`bibliography-id` are
  `resolve-citations`'s own same-named additions (TASK-20), re-threaded
  here unchanged so a caller of the *full* pipeline gets them too without
  having to call `resolve-citations` a second time itself.

  Include expansion (`expand-includes`, TASK-38) runs first, before
  numbering, and its own warnings lead the diagnostics for the same
  reason every other pass's do: they explain what the passes after them
  did or did not see.

  Overlay application (`apply-overlays`, TASK-83.1/TASK-83.2) runs next,
  as sec9 step 0.5 -- after include expansion (an overlay can target a
  node that exists only post-include-expansion, e.g. a chapter pulled in
  from another file), before numbering (so spliced content numbers and
  cross-references exactly as if authored inline, the same reasoning
  include expansion uses for itself) -- and its own warnings follow
  `include-warnings` in the returned `:diagnostics`, for the same reason.

  Quote expansion (`expand-quotes`, TASK-154, doc-10 step 0b) runs after
  both and before numbering, driven by `:books` (`expand-quotes`' own
  opts, default none -- a no-op that leaves every `:book-quote` as
  parsed); its warnings follow `overlay-warnings`. Quoted blocks are kept
  out of the host's numbering, derived lists and citation pass (see
  `expand-quotes`' section comment). The same `:books` resolves every
  book link right after the cross-references (`resolve-book-refs`,
  TASK-155, doc-10 step 2), whose warnings follow theirs.

  `([document])` uses every pass's own defaults. `([document opts])` takes
  an options map: `:includes` (`expand-includes`' own opts, default
  none -- so no expansion happens and every `:include` is left standing),
  `:overlay-sources` (`apply-overlays`' own second arg, default nil/`[]`
  -- so no overlay is applied and, as long as no caller passes any, a
  document resolves byte-for-byte identically to a build with this
  feature absent entirely), `:lexicon` (`number-document`'s second arg, default
  `default-lexicon`), `:bibliography` (a pre-loaded map, default: load
  `meta.bibliography` via `load-bibliography` if present, else `{}`,
  mirroring `resolve-citations`'s own 1-arg-arity default exactly),
  `:citation-styles` (`resolve-citations`'s third arg, default
  `default-citation-styles`), `:directive-registry`
  (`structural-diagnostics`'s second arg, default `#{}`),
  `:directive-kinds` (its fourth, default `{}` -- TASK-48), and
  `:front-matter-names` (default `#{}` -- TASK-54: the directive names
  whose blocks are not part of the numbered body, so numbering and the
  derivations built on it never see them; see `body-view`), and
  `:sublabel-names` (default `#{}` -- TASK-56: the directive names whose
  nodes take a letter within the node above them rather than a number of
  their own; see `number-document`) -- for callers
  needing a custom lexicon/bibliography/styles/registry, mirroring TASK-11
  AC #4's extensibility pattern, generalized to every knob this pipeline
  now has."
  ([document] (resolve-document document {}))
  ([document opts]
   (let [{:keys [lexicon bibliography citation-styles directive-registry directive-kinds includes
                 overlay-sources front-matter-names sublabel-names books]
          :or {directive-registry #{} directive-kinds {} front-matter-names #{}
               sublabel-names #{}}} opts
         lexicon (or lexicon default-lexicon)
         ;; Step 0 (TASK-38), and it must be first: an included chapter's
         ;; figures and sections have to number, cross-reference and be
         ;; diagnosed exactly as if they had been typed into the
         ;; including file, which is only true if every pass below sees
         ;; one already-spliced document. `includes` is
         ;; `expand-includes`' own opts map; with none, or with none
         ;; carrying a `:load`, this is a no-op.
         {document :document include-warnings :warnings} (expand-includes document includes)
         ;; Step 0.5 (TASK-83.1/TASK-83.2): after include expansion (an
         ;; overlay can target a node that only exists post-expansion),
         ;; before numbering (so spliced content numbers and cross-
         ;; references exactly as if authored inline). `overlay-sources`
         ;; is nil/`[]` by default, which is a no-op -- see
         ;; `apply-overlays`' own docstring for the byte-identical-build
         ;; invariant this keeps.
         {document :document overlay-warnings :warnings} (apply-overlays document overlay-sources)
         ;; Step 0b (TASK-154): quotes, before numbering -- quoted content
         ;; is kept out of it -- and after overlays as well as includes,
         ;; so a `!quote` an overlay splices in is expanded like any
         ;; other. No `:books` (or none with a `:resolve`) is a no-op.
         {document :document quote-warnings :warnings} (expand-quotes document books)
         ;; Numbering runs over the BODY only (TASK-54): a front-matter
         ;; block is not part of the numbered document, and everything
         ;; downstream of this table -- every CrossRef's own text, the
         ;; TOC, the lists of figures and tables, both emitters' printed
         ;; numbers -- inherits that from here rather than each
         ;; re-deciding it.
         authored document
         document (disambiguate-duplicate-ids document)
         labels (number-document document lexicon front-matter-names sublabel-names)
         ;; Cross-references and citations, by contrast, run over the
         ;; WHOLE document: an abstract may cite, and may refer to a
         ;; section of the body, and both have to work.
         bib (or bibliography
                 (when-let [path (get-in document [:meta :bibliography])]
                   (load-bibliography path))
                 {})
         styles (or citation-styles default-citation-styles)
         ;; Citations run BEFORE cross-references (TASK-64), and the
         ;; order is the whole fix: this pass APPENDS the generated
         ;; bibliography Section, and a `@sec:bibliography` in the body
         ;; can only resolve against a table that already knows about
         ;; it. Run the other way round -- as it was -- every reference
         ;; to the reference list dangled, in both targets, and TASK-13
         ;; AC #3's "can be cross-referenced" was never true. Neither
         ;; pass reads the other's output, so the order is free.
         ;; Quoted blocks are set aside for it (TASK-154): their
         ;; citations are already formatted against their own book's
         ;; bibliography, and must neither be re-resolved against the
         ;; host's nor take a number in the host's reference list.
         [detached quoted] (detach-quoted document)
         {cite-doc :document cite-warnings :warnings
          ordered-keys :ordered-keys bib-id :bibliography-id
          bib-heading :bibliography-heading} (resolve-citations detached bib styles)
         cite-doc (reattach-quoted cite-doc quoted)
         ;; The generated section's own entry, merged in rather than
         ;; numbered: see `bibliography-label-entry` for why it carries
         ;; a word and no number.
         labels (merge labels (bibliography-label-entry bib-id bib-heading))
         {cr-doc :document cr-warnings :warnings} (resolve-cross-refs cite-doc labels)
         ;; Step 2's other half (TASK-155): book links, through the same
         ;; `:books` option as quotes; no-op without one.
         {cr-doc :document link-warnings :warnings} (resolve-book-refs cr-doc books)
         ;; Duplicates are reported against the document as authored
         ;; (`disambiguate-duplicate-ids` has renamed them in `cr-doc`);
         ;; every other structural check runs on the resolved document.
         diagnostics (into (duplicate-id-diagnostics authored)
                           (remove #(= :duplicate-id (:type %)))
                           (structural-diagnostics cr-doc directive-registry lexicon directive-kinds
                                                   front-matter-names))]
     {:document cr-doc
      ;; Warning order follows the passes, which now run citations
      ;; first (TASK-64) -- a reader tracing a build reads them in the
      ;; order the passes produced them.
      :diagnostics (-> [] (into include-warnings) (into overlay-warnings) (into quote-warnings)
                       (into cite-warnings)
                       (into cr-warnings) (into link-warnings) (into diagnostics))
      :labels labels
      :ordered-keys ordered-keys
      :bibliography-id bib-id})))

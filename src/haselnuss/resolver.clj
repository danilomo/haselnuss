(ns haselnuss.resolver
  "The resolver pass (`SPEC.md` sec9): a pure,
  target-independent function over a parsed `haselnuss.ast/Document` that
  runs once before any emitter. This namespace currently implements a
  step 0 -- splicing every multi-file `:include` in place, via
  `expand-includes` (TASK-38), which must run before everything below so
  an included chapter's own nodes number and resolve as if they had been
  typed into the including file -- sec9
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
            [clojure.string :as str]))

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

(defn- inline-blocks
  "Every Block reachable from `inlines` via a footnote (`:note`), recursing
  into every Inline variant that itself carries nested Inlines
  (`:emph`/`:strong`/`:strike`/`:small-caps`/`:sub`/`:sup`/`:span`/`:link`)
  or a Cite's per-item `:prefix`/`:suffix` Inlines, so a labeled Figure/
  Table/etc. tucked inside a footnote still numbers (sec9 step 1: \"every
  labeled node\")."
  [inlines]
  (mapcat
   (fn [inline]
     (case (:t inline)
       :note (:blocks inline)
       (:emph :strong :strike :small-caps :sub :sup :span :link)
       (inline-blocks (:inlines inline))
       :cite (mapcat (fn [item]
                       (concat (inline-blocks (:prefix item))
                               (inline-blocks (:suffix item))))
                     (:items inline))
       []))
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
;; Both failure modes are diagnostics, not exceptions, and both drop the
;; offending `:include` so the rest of the build proceeds (AC #3/#4): a file
;; that cannot be read, and a cycle. A cycle is detected by canonical path
;; against the stack of files currently being expanded, so a diamond (two
;; chapters including one shared file) is fine while a true loop is not.

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
  (mapv
   (fn [inline]
     (case (:t inline)
       :note (update inline :blocks expand-blocks expand-one)
       (:emph :strong :strike :small-caps :sub :sup :span :link)
       (update inline :inlines expand-inlines expand-one)
       :cite (update inline :items
                     (partial mapv (fn [item]
                                     (cond-> item
                                       (:prefix item) (update :prefix expand-inlines expand-one)
                                       (:suffix item) (update :suffix expand-inlines expand-one)))))
       inline))
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
  rest of the build:

  - `{:type :unreadable-include :src :path :message}` when `:load`
    throws -- a missing file, a directory, a permission error, or a
    parse error in the included document (AC #4).
  - `{:type :include-cycle :src :path :cycle :message}` when a file is
    already being expanded further up the stack (AC #3). Detected by
    canonical path, so a diamond -- two chapters including one shared
    file -- expands twice as it should, while a true loop stops at the
    edge that closes it.

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
                    (warn! {:type :include-cycle
                            :src src
                            :path path
                            :cycle (conj (vec stack) path)
                            :message (str "include cycle: " (pr-str src) " is already being"
                                          " included, via " (str/join " -> " (conj (vec stack) path)))})
                    (let [included (try (load-document file)
                                        (catch Exception e
                                          (warn! {:type :unreadable-include
                                                  :src src
                                                  :path path
                                                  :message (str "cannot include " (pr-str src)
                                                                " (" path "): " (ex-message e))})
                                          ::failed))]
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
  number is composed exactly as before (AC #4)."
  [{:keys [lexicon lang chapters sublabel-names] :as ctx} state block]
  (let [id (some-> block :attr :id)
        kind (id->kind id)
        entry (get lexicon kind)
        section-path (:section-path state)
        chapter-path (:chapter-path state)
        section? (= :section (:t block))
        section-scoped? (= :section-scoped (:counter entry))
        chapter? (and chapters section? (= 1 (:level block)))
        chapter-n (when chapter? (inc (get (:counters state) chapter-counter-key 0)))
        counters (cond-> (:counters state)
                   chapter? (assoc chapter-counter-key chapter-n))
        ;; What a `:section-scoped` number here composes onto: a chapter
        ;; starts a path rather than continuing one, another Section
        ;; continues the section path, and anything else continues the
        ;; chapter path in a chaptered document and the section path
        ;; otherwise.
        own-scope (cond
                    chapter? []
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
          sub? (assign-sublabel counters parent kind)
          (and id entry) (let [[path counters'] (assign-number counters entry kind own-scope)]
                           [path counters' nil])
          :else [nil counters nil])
        number (when own-path
                 (if sublabel
                   ;; The parent's own number with the letter appended
                   ;; and no separator -- "Figure 1.1a", exactly what
                   ;; `subcaption` prints and what a `\Cref` to a panel
                   ;; resolves to (confirmed with a real pdflatex).
                   (str (str/join "." (butlast own-path)) sublabel)
                   (str/join "." own-path)))
        entries' (if own-path
                   (let [word-info (words-for entry lang)]
                     (assoc (:entries state) id
                            (cond-> {:kind kind
                                     :path own-path
                                     :number number
                                     :word (:singular word-info)
                                     :text (format-text word-info number)}
                              sublabel (assoc :sublabel sublabel))))
                   (:entries state))
        child-section-path (cond
                             ;; Unconditional for a chapter, unlike the
                             ;; labeled-only rule below: an unlabeled
                             ;; `\chapter` still numbers in the PDF, so
                             ;; its sections must still hang off it.
                             chapter? [chapter-n]
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
    (assoc state'' :section-path section-path :chapter-path chapter-path :parent-label parent)))

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

  `meta.topLevelDivision` (TASK-53) changes one thing here, and only for
  a document that opts in: with `:chapter`, every level-1 Section is a
  chapter -- counted structurally, labeled or not -- and a
  `:section-scoped` kind on a node that is not itself a Section composes
  with, and counts per, that enclosing chapter rather than the whole
  section path. This matches what a `\\chapter`-bearing LaTeX class does
  with the same document, which is what lets a native-mode `\\Cref` and
  an HTML reference print the same number; see `number-block` for the
  three consequences and the two shapes that made a simpler design
  wrong. Every other document is numbered exactly as before.

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
         ctx {:lexicon lexicon
              :lang lang
              :sublabel-names sublabel-names
              :chapters (= :chapter (get-in document [:meta :top-level-division]))}
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

(defn- resolve-cross-ref
  "Resolves a single `:cross-ref` inline node against `labels` (a
  `number-document` label table), returning `[node' warnings]` (`warnings`
  a vector, possibly empty).

  A match (TASK-12 AC #1/#2) annotates `node'` with `:target` (the resolved
  target id, for a later emitter to build a link/anchor from) and `:text`
  -- the target's full `:text` (e.g. \"Figure 2.3\") normally, or, when the
  node's own `:suppress-prefix` is set, just the bare `:number` (e.g.
  \"2.3\") with no prefix word.

  No match is a dangling reference (TASK-12 AC #3): `node'` gets `:target
  nil` and `:text \"??\"` (a visible placeholder, mirroring LaTeX's own
  behavior for an unresolved `\\ref`, instead of a crash), and `warnings`
  carries one `{:type :dangling-cross-ref :label :message}` map."
  [labels {:keys [label suppress-prefix] :as cross-ref}]
  (if-let [entry (get labels label)]
    [(assoc cross-ref
            :target label
            :text (if suppress-prefix (:number entry) (:text entry)))
     []]
    [(assoc cross-ref :target nil :text "??")
     [{:type :dangling-cross-ref
       :label label
       :message (str "dangling cross-reference: no target with id \"" label "\"")}]]))

(defn- resolve-seq
  "Maps `resolve-one` (a `node -> [node' warnings]` function) over `xs`,
  returning `[xs' warnings']` with every element's own (already-a-vector)
  `warnings` concatenated in `xs`'s order."
  [resolve-one xs]
  (reduce (fn [[acc warnings] x]
            (let [[x' w] (resolve-one x)]
              [(conj acc x') (into warnings w)]))
          [[] []]
          xs))

(declare resolve-block)

(defn- resolve-blocks
  "`resolve-block` mapped over every Block in `blocks`, in order."
  [labels blocks]
  (resolve-seq (partial resolve-block labels) blocks))

(declare resolve-inline)

(defn- resolve-inlines
  "`resolve-inline` mapped over every Inline in `inlines`, in order."
  [labels inlines]
  (resolve-seq (partial resolve-inline labels) inlines))

(defn- resolve-cite-item
  "Resolves any `CrossRef`s nested in CiteItem `item`'s optional `:prefix`/
  `:suffix` Inlines (sec4.4), leaving an absent field absent rather than
  becoming `[]` (mirroring TASK-9's own prefix/suffix-presence contract)."
  [labels {:keys [prefix suffix] :as item}]
  (let [[prefix' pw] (if prefix (resolve-inlines labels prefix) [nil []])
        [suffix' sw] (if suffix (resolve-inlines labels suffix) [nil []])]
    [(cond-> item
       prefix (assoc :prefix prefix')
       suffix (assoc :suffix suffix'))
     (into pw sw)]))

(defn- resolve-inline
  "Resolves any `:cross-ref` node reachable from `inline`: itself, if
  `inline` is one, else recursing into every nested-Inline/nested-Block
  field an Inline variant can carry (sec4.4) -- `:inlines` for
  `:emph`/`:strong`/`:strike`/`:small-caps`/`:sub`/`:sup`/`:span`/`:link`,
  `:items` for `:cite` (via `resolve-cite-item`), `:blocks` for a footnote
  `:note`. Every other variant (`:str`/`:space`/`:soft-break`/`:line-break`/
  `:code`/`:math-inline`/`:image`) carries neither and is returned
  unchanged."
  [labels inline]
  (case (:t inline)
    :cross-ref (resolve-cross-ref labels inline)
    (:emph :strong :strike :small-caps :sub :sup :span :link)
    (let [[inlines' warnings] (resolve-inlines labels (:inlines inline))]
      [(assoc inline :inlines inlines') warnings])
    :cite
    (let [[items' warnings] (resolve-seq (partial resolve-cite-item labels) (:items inline))]
      [(assoc inline :items items') warnings])
    :note
    (let [[blocks' warnings] (resolve-blocks labels (:blocks inline))]
      [(assoc inline :blocks blocks') warnings])
    [inline []]))

(defn- resolve-cell
  "Resolves any `CrossRef`s in Cell `cell`'s own `:blocks`."
  [labels cell]
  (let [[blocks' warnings] (resolve-blocks labels (:blocks cell))]
    [(assoc cell :blocks blocks') warnings]))

(defn- resolve-row
  "`resolve-cell` mapped over Row `row`'s `:cells`, in order."
  [labels row]
  (let [[cells' warnings] (resolve-seq (partial resolve-cell labels) (:cells row))]
    [(assoc row :cells cells') warnings]))

(defn- resolve-block
  "Resolves any `:cross-ref` node reachable from `block`, recursing into
  every nested-Inline/nested-Block field a Block variant can carry (sec4.3)
  -- a Section's `:heading` and `:blocks`; a Para's `:inlines`; a List's
  `:items` (a vector of Block vectors); a Figure's `:content` and
  `:caption`; a Table's `:head`/`:rows` (via `resolve-row`) and `:caption`;
  a BlockQuote or Directive's `:blocks`. Every other variant (`:code-block`/
  `:math-block`/`:include`/`:thematic-break`) carries neither and is
  returned unchanged."
  [labels block]
  (case (:t block)
    :section
    (let [[heading' hw] (resolve-inlines labels (:heading block))
          [blocks' bw] (resolve-blocks labels (:blocks block))]
      [(assoc block :heading heading' :blocks blocks') (into hw bw)])
    :para
    (let [[inlines' warnings] (resolve-inlines labels (:inlines block))]
      [(assoc block :inlines inlines') warnings])
    :list
    (let [[items' warnings] (resolve-seq (partial resolve-blocks labels) (:items block))]
      [(assoc block :items items') warnings])
    :figure
    (let [[content' cw] (resolve-block labels (:content block))
          [caption' capw] (resolve-inlines labels (:caption block))]
      [(assoc block :content content' :caption caption') (into cw capw)])
    :table
    (let [[head' hw] (resolve-row labels (:head block))
          [rows' rw] (resolve-seq (partial resolve-row labels) (:rows block))
          [caption' capw] (resolve-inlines labels (:caption block))]
      [(assoc block :head head' :rows rows' :caption caption') (into (into hw rw) capw)])
    (:block-quote :directive)
    (let [[blocks' warnings] (resolve-blocks labels (:blocks block))]
      [(assoc block :blocks blocks') warnings])
    [block []]))

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
  vector of `{:type :dangling-cross-ref :label :message}` maps, one per
  dangling reference encountered, in document order -- `document'` never
  contains a raw crash, only a visible placeholder plus a diagnostic,
  mirroring `resolve`'s own `{ast, meta, diagnostics}` shape at this
  narrower, single-step scale (sec9)."
  ([document] (resolve-cross-refs document (number-document document)))
  ([document labels]
   (let [title (get-in document [:meta :title])
         [title' tw] (if title (resolve-inlines labels title) [nil []])
         [blocks' bw] (resolve-blocks labels (:blocks document))
         document' (cond-> document
                     title (assoc-in [:meta :title] title')
                     true (assoc :blocks blocks'))]
     {:document document' :warnings (into tw bw)})))

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

(defn- full-reference-text
  "The full formatted bibliography-entry text for CSL-JSON `entry`:
  \"Authors (Year). Title.\", shared by every built-in style (only in-text
  citation formatting, not the reference-list entry itself, differs
  between `default-citation-styles`' built-ins)."
  [entry]
  [(str-inline (str (authors-full-list entry) " (" (entry-year entry) "). "
                    (or (:title entry) "") "."))])

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
  already resolved for any nested Cites -- see `cite-resolve-inline`),
  using `style` and `citation-table` (a `build-citation-table` result).
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

;; Structural walks below mirror TASK-12's resolve-block/resolve-inline
;; coverage (every Block/Inline variant that can carry nested Inlines/
;; Blocks per sec4.3/4.4), but are deliberately new and self-contained
;; rather than a refactor of that already-reviewed code -- they reuse only
;; the generic `resolve-seq` helper defined above. Two concerns are kept
;; separate since bibliography ordering/numbering needs the *whole*
;; document's distinct key set before any single Cite can be formatted:
;; `cite-keys-in-*` (a plain collecting walk, no transform) gathers every
;; CiteItem :key in document order; `cite-resolve-*` (a transforming walk,
;; threading warnings like TASK-12's resolve-*) formats every reachable
;; Cite node's :text.

(declare cite-keys-in-block)

(defn- cite-keys-in-blocks
  [blocks]
  (mapcat cite-keys-in-block blocks))

(declare cite-keys-in-inline)

(defn- cite-keys-in-inlines
  [inlines]
  (mapcat cite-keys-in-inline inlines))

(defn- cite-keys-in-inline
  [inline]
  (case (:t inline)
    :cite (concat (map :key (:items inline))
                  (mapcat (fn [item]
                            (concat (cite-keys-in-inlines (:prefix item))
                                    (cite-keys-in-inlines (:suffix item))))
                          (:items inline)))
    (:emph :strong :strike :small-caps :sub :sup :span :link)
    (cite-keys-in-inlines (:inlines inline))
    :note (cite-keys-in-blocks (:blocks inline))
    []))

(defn- cite-keys-in-cell
  [cell]
  (cite-keys-in-blocks (:blocks cell)))

(defn- cite-keys-in-block
  [block]
  (case (:t block)
    :section (concat (cite-keys-in-inlines (:heading block)) (cite-keys-in-blocks (:blocks block)))
    :para (cite-keys-in-inlines (:inlines block))
    :list (mapcat cite-keys-in-blocks (:items block))
    :figure (concat (cite-keys-in-block (:content block)) (cite-keys-in-inlines (:caption block)))
    :table (concat (mapcat cite-keys-in-cell (table-cells block)) (cite-keys-in-inlines (:caption block)))
    (:block-quote :directive) (cite-keys-in-blocks (:blocks block))
    []))

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

(declare cite-resolve-block)

(defn- cite-resolve-blocks
  [ctx blocks]
  (resolve-seq (partial cite-resolve-block ctx) blocks))

(declare cite-resolve-inline)

(defn- cite-resolve-inlines
  [ctx inlines]
  (resolve-seq (partial cite-resolve-inline ctx) inlines))

(defn- cite-resolve-cite-item
  "Resolves any nested Cites reachable through CiteItem `item`'s own
  `:prefix`/`:suffix` Inlines, leaving an absent field absent (mirrors
  TASK-12's `resolve-cite-item`)."
  [ctx {:keys [prefix suffix] :as item}]
  (let [[prefix' pw] (if prefix (cite-resolve-inlines ctx prefix) [nil []])
        [suffix' sw] (if suffix (cite-resolve-inlines ctx suffix) [nil []])]
    [(cond-> item
       prefix (assoc :prefix prefix')
       suffix (assoc :suffix suffix'))
     (into pw sw)]))

(defn- cite-resolve-inline
  "Resolves any `:cite` node reachable from `inline`: itself (after first
  resolving any nested Cites in its own items' prefix/suffix, then
  formatting its own `:text` via `resolve-cite-node`), else recursing into
  every nested-Inline/nested-Block field an Inline variant can carry
  (sec4.4), exactly mirroring TASK-12's `resolve-inline` coverage."
  [ctx inline]
  (case (:t inline)
    :cite
    (let [[items' iw] (resolve-seq (partial cite-resolve-cite-item ctx) (:items inline))
          [cite' cw] (resolve-cite-node ctx (assoc inline :items items'))]
      [cite' (into iw cw)])
    (:emph :strong :strike :small-caps :sub :sup :span :link)
    (let [[inlines' w] (cite-resolve-inlines ctx (:inlines inline))]
      [(assoc inline :inlines inlines') w])
    :note
    (let [[blocks' w] (cite-resolve-blocks ctx (:blocks inline))]
      [(assoc inline :blocks blocks') w])
    [inline []]))

(defn- cite-resolve-cell
  [ctx cell]
  (let [[blocks' w] (cite-resolve-blocks ctx (:blocks cell))]
    [(assoc cell :blocks blocks') w]))

(defn- cite-resolve-row
  [ctx row]
  (let [[cells' w] (resolve-seq (partial cite-resolve-cell ctx) (:cells row))]
    [(assoc row :cells cells') w]))

(defn- cite-resolve-block
  "Resolves any `:cite` node reachable from `block`, exactly mirroring
  TASK-12's `resolve-block` coverage of Block variants (sec4.3)."
  [ctx block]
  (case (:t block)
    :section
    (let [[heading' hw] (cite-resolve-inlines ctx (:heading block))
          [blocks' bw] (cite-resolve-blocks ctx (:blocks block))]
      [(assoc block :heading heading' :blocks blocks') (into hw bw)])
    :para
    (let [[inlines' w] (cite-resolve-inlines ctx (:inlines block))]
      [(assoc block :inlines inlines') w])
    :list
    (let [[items' w] (resolve-seq (partial cite-resolve-blocks ctx) (:items block))]
      [(assoc block :items items') w])
    :figure
    (let [[content' cw] (cite-resolve-block ctx (:content block))
          [caption' capw] (cite-resolve-inlines ctx (:caption block))]
      [(assoc block :content content' :caption caption') (into cw capw)])
    :table
    (let [[head' hw] (cite-resolve-row ctx (:head block))
          [rows' rw] (resolve-seq (partial cite-resolve-row ctx) (:rows block))
          [caption' capw] (cite-resolve-inlines ctx (:caption block))]
      [(assoc block :head head' :rows rows' :caption caption') (into (into hw rw) capw)])
    (:block-quote :directive)
    (let [[blocks' w] (cite-resolve-blocks ctx (:blocks block))]
      [(assoc block :blocks blocks') w])
    [block []]))

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
;; `ids-in-block`/`ids-in-blocks` below collect every id already in play,
;; mirroring `block-children`'s own Block-nested-Block traversal (reused
;; directly, since it is a plain structural helper with no numbering-
;; specific behavior of its own) -- deliberately scoped to Block `:attr
;; :id`s only, exactly the id namespace `number-document`'s label table
;; is built from (see `id->kind`'s own docstring: a numbering target is
;; always a Block, never a Link/Image/Span Inline's own id), since that is
;; precisely the collision this fix closes. `unique-bibliography-id` then
;; picks the base id its own division calls for ("sec:bibliography", or
;; "ch:bibliography" in a chaptered document -- TASK-63) when that is
;; free, else the first numbered-suffix variant not already taken, and -- mirroring this namespace's own
;; "never change behavior silently" convention (the dangling-cross-ref/
;; dangling-citation warnings above) -- reports the collision as a build
;; warning rather than just quietly picking a different id with no trace.

(defn- ids-in-block
  "`block`'s own `attr.id` (nil if absent), followed by every id reachable
  from its descendant Blocks (via `block-children`, mirroring
  `number-block`'s own traversal)."
  [block]
  (cons (get-in block [:attr :id]) (mapcat ids-in-block (block-children block))))

(defn- ids-in-blocks
  [blocks]
  (mapcat ids-in-block blocks))

(defn- existing-block-ids
  "Every non-nil Block `attr.id` already present anywhere in `document`."
  [document]
  (into #{} (remove nil?) (ids-in-blocks (:blocks document))))

(defn- chaptered?
  "True when `document` opted into chapters (`meta.topLevelDivision`).

  One function rather than the third literal copy of the same
  `get-in` (found by review): the generated bibliography's id prefix and
  `division-kind-diagnostics` must agree about this, or the warning the
  prefix exists to avoid comes straight back."
  [document]
  (= :chapter (get-in document [:meta :top-level-division])))

(defn- unique-bibliography-id
  "The id `bibliography-section` should use, given `existing-ids` (an
  `existing-block-ids` result): the bare `\"sec:bibliography\"` when that
  id is not already taken, else the first `\"sec:bibliography-2\"`,
  `\"sec:bibliography-3\"`, ... suffix not already taken. Returns `[id
  warnings]` -- `warnings` carries one `{:type :bibliography-id-collision
  :id :resolved-id :message}` map exactly when disambiguation was needed,
  so the author whose own node already used `\"sec:bibliography\"` is
  told their id was *not* silently reused (TASK-13 review fix, AC #3).

  `chapters?` (`meta.topLevelDivision`) changes the PREFIX to `ch:`,
  and that is not cosmetic (TASK-63). The generated section is a
  level-1 heading, which a chaptered document emits as `\\chapter`, so a
  `sec:` prefix there is exactly the id-prefix/division disagreement
  `division-kind-diagnostics` warns about -- a reference to it printing
  \"Section 5\" in HTML and \"Chapter 5\" in a native PDF. Every
  chaptered document with a bibliography therefore warned about a
  section nobody wrote, and no edit to the document could silence it,
  since the id is generated here. The prefix follows the division the
  section is actually emitted into instead."
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
  English."
  [style bibliography ordered-keys lang id]
  (let [items (mapv (fn [k] [{:t :para :inlines ((:bib-entry style) (get bibliography k))}])
                    ordered-keys)
        heading-word (or (get bibliography-heading-words lang) (get bibliography-heading-words "en"))]
    {:t :section
     :level 1
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
  `resolve-citations` test) is unaffected."
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
         ctx {:style style :citation-table citation-table}
         [title' tw] (if title (cite-resolve-inlines ctx title) [nil []])
         [blocks' bw] (cite-resolve-blocks ctx (:blocks document))
         resolvable-keys (filter #(contains? citation-table %) keys-in-order)
         ordered-keys (if-let [sort-key (:sort-key? style)]
                        (sort-by #(sort-key % (get bibliography %)) resolvable-keys)
                        resolvable-keys)
         lang (get-in document [:meta :lang] "en")
         [bib-id bib-id-warnings] (if (seq ordered-keys)
                                    (unique-bibliography-id (existing-block-ids document)
                                                            (chaptered? document))
                                    [nil []])
         document' (cond-> document
                     title (assoc-in [:meta :title] title')
                     true (assoc :blocks (vec blocks'))
                     (seq ordered-keys)
                     (update :blocks conj
                             (bibliography-section style bibliography ordered-keys lang bib-id)))]
     {:document document'
      :warnings (-> style-warnings (into tw) (into bw) (into bib-id-warnings))
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
;; `block-children` indices from the document root down to, and including,
;; the node itself (unchanged from this pass's original shape). A location
;; reached through an Inline-bearing field instead has its path extended
;; with that field's own keyword (`:title`/`:heading`/`:inlines`/`:caption`/
;; `:items`/`:prefix`/`:suffix`/`:blocks` for a footnote `:note`'s own
;; nested Blocks) followed by that field's own index -- reproducible and
;; sufficient to tell any two same-id nodes apart, Block or Inline.
;;
;; Coverage (review fix on this task -- see TASK-15's task comments for the
;; original finding): every `attr.id` anywhere in the document is checked,
;; not just Block-level ones. A Link/Image/Span Inline's own `attr.id` (the
;; only Inline variants that carry an `Attr` at all, per `ast.clj`'s
;; `::inline` schema, sec4.4) is in the SAME id namespace as a Block's, per
;; `SPEC.md` sec4.1's own Attr definition (`id?: string; //
;; unique document-wide anchor`) -- there is no Block-only carve-out in the
;; spec, and any future HTML emitter would render two same-id nodes, Block
;; or Inline, as an invalid duplicate `id="..."` attribute either way.
;; `id-locations-in-block`/`id-locations-in-inline` below walk every
;; nested-Inline/nested-Block field a Block/Inline variant can carry
;; (sec4.3/4.4), mirroring `resolve-block`/`resolve-inline`'s own
;; exhaustive coverage (TASK-12), NOT `id->kind`'s narrower, numbering-
;; specific "a numbering target is always a Block" convention (TASK-11) --
;; that rule decides which nodes are *numbering-eligible*, a different and
;; narrower question than generic id-uniqueness-as-anchor, and does not
;; transfer here. (This pass's first revision made exactly that mistake --
;; scoping duplicate-id checking to Block-level ids only -- caught by
;; clojure-reviewer on this task.)
;;
;; No double-counting: a `:para` Block's own `block-children` (footnote-
;; embedded Blocks, reached via `inline-blocks`) is deliberately NOT walked
;; as a Block-descendant path in `id-locations-in-block` -- its `:para`
;; case instead relies solely on `id-locations-in-inline`'s own `:note`
;; handling to reach those SAME footnote-embedded Blocks (through
;; `:inlines` rather than `block-children`), so nothing is ever reported
;; twice. Every other Block variant's `block-children`-derived nesting
;; (Section/List/BlockQuote/Directive/Figure/Table) has no such overlap
;; with its own Inline-bearing fields (`:heading`/`:caption`, neither of
;; which `block-children` ever inspects), so those keep using
;; `block-children` directly, unchanged.
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

(declare id-locations-in-inline)

(defn- id-locations-in-inlines
  "`id-locations-in-inline` mapped over `inlines`, each at `path` extended
  by its own index."
  [path inlines]
  (into [] (mapcat (fn [i inline] (id-locations-in-inline (conj path i) inline))
                   (range) inlines)))

(defn- id-locations-in-cite-item
  "Every `[id location]` pair reachable from CiteItem `item`'s own
  `:prefix`/`:suffix` Inlines at structural `path` (mirrors `resolve-cite-
  item`/`cite-resolve-cite-item`'s own `:prefix`/`:suffix`-only coverage --
  a CiteItem carries no `Attr`/`:id` of its own, sec4.4)."
  [path item]
  (into (id-locations-in-inlines (conj path :prefix) (:prefix item))
        (id-locations-in-inlines (conj path :suffix) (:suffix item))))

(declare id-locations-in-block)

(defn- id-locations-in-inline
  "Every `[id location]` pair for an id anywhere reachable from Inline
  `inline` at structural `path`: `inline`'s own `attr.id` (`:link`/`:image`/
  `:span` only -- the only Inline variants that carry an `Attr` at all, per
  `ast.clj`'s `::inline` schema, sec4.4) paired with `{:node-type (:t
  inline) :path path}`, followed by every id found recursively -- mirrors
  `resolve-inline`/`cite-resolve-inline`'s own exhaustive Inline-variant
  coverage: `:inlines` for `:emph`/`:strong`/`:strike`/`:small-caps`/`:sub`/
  `:sup`/`:span`/`:link`, `:items` for `:cite` (via `id-locations-in-cite-
  item`), `:blocks` for a footnote `:note` (recursing back into
  `id-locations-in-block`, since a footnote can carry labeled Blocks too --
  this is the ONLY path by which a `:para`'s footnote-embedded Blocks are
  reached by this pass; see this section's top-of-namespace comment on
  avoiding double-counting)."
  [path inline]
  (let [own (when-let [id (get-in inline [:attr :id])]
              [[id {:node-type (:t inline) :path path}]])]
    (into (vec own)
          (case (:t inline)
            (:emph :strong :strike :small-caps :sub :sup :span :link)
            (id-locations-in-inlines (conj path :inlines) (:inlines inline))
            :cite
            (into [] (mapcat (fn [i item] (id-locations-in-cite-item (conj path :items i) item))
                             (range) (:items inline)))
            :note
            (into [] (mapcat (fn [i block] (id-locations-in-block (conj path :blocks i) block))
                             (range) (:blocks inline)))
            []))))

(defn- id-locations-in-block
  "Every `[id location]` pair for an id -- Block-level `attr.id`, or an
  Inline-level Link/Image/Span's own `attr.id` reached through this
  block's own Inline-bearing fields -- reachable from `block` at
  structural `path`: `block`'s own id (if any) paired with `{:node-type
  (:t block) :path path}`, followed by:
  - every id found among `block`'s nested Blocks, via `block-children`
    (reused, already excludes a Directive's own `:fallback`), each at
    `path` extended by its own index among those children -- EXCEPT for a
    `:para` Block, whose `block-children` (footnote-embedded Blocks, via
    `inline-blocks`) is deliberately skipped here so it is not walked
    twice (see below);
  - every id found in `block`'s own direct Inline-bearing field, if any,
    via `id-locations-in-inlines` at `path` extended by that field's own
    keyword: a Section's `:heading`, a Para's `:inlines` (a `:para`'s ONLY
    source of nested ids in this walk -- including its own footnote-
    embedded Blocks, reached via `id-locations-in-inline`'s own `:note`
    handling instead of `block-children`, so nothing is counted twice), a
    Figure or Table's `:caption`.
  A List/BlockQuote/Directive/Table's other nested-Block fields (`:items`/
  `:blocks`/table cells) have no Inline-bearing field of their own at this
  level; their own nested Blocks (found via `block-children`, e.g. a List
  item's Para, a Table cell's Para) are still reached by the ordinary
  recursive call above, and each of THOSE blocks' own Inline-bearing
  fields is covered the same way once recursion reaches them."
  [path block]
  (let [own (when-let [id (get-in block [:attr :id])]
              [[id {:node-type (:t block) :path path}]])
        block-descendants
        (if (= :para (:t block))
          []
          (into [] (mapcat (fn [i child] (id-locations-in-block (conj path i) child))
                           (range) (block-children block))))
        inline-descendants
        (case (:t block)
          :section (id-locations-in-inlines (conj path :heading) (:heading block))
          :para (id-locations-in-inlines (conj path :inlines) (:inlines block))
          (:figure :table) (id-locations-in-inlines (conj path :caption) (:caption block))
          [])]
    (into (vec own) (into block-descendants inline-descendants))))

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
                                     " nodes (" (str/join ", " (map (comp name :node-type second) group))
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
  "The two built-in kinds that name a sectioning DIVISION (TASK-53),
  mapped to the word for the LaTeX sectioning command a Section of that
  division is emitted as. Only these two are checked by
  `division-kind-diagnostics`: a custom section-scoped kind on a heading
  is its author's own arrangement, and warning about it would be this
  namespace guessing at a convention it does not define."
  {:ch "chapter"
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
  a non-division kind on a Section is `kind-role-diagnostics`' own case."
  [document]
  (let [chapters? (chaptered? document)]
    (into []
          (comp (mapcat pre-order-blocks)
                (keep (fn [block]
                        (let [kind (id->kind (get-in block [:attr :id]))
                              expected (if (and chapters? (= 1 (:level block))) :ch :sec)]
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

  `([document])` uses every pass's own defaults. `([document opts])` takes
  an options map: `:includes` (`expand-includes`' own opts, default
  none -- so no expansion happens and every `:include` is left standing),
  `:lexicon` (`number-document`'s second arg, default
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
                 front-matter-names sublabel-names]
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
         ;; Numbering runs over the BODY only (TASK-54): a front-matter
         ;; block is not part of the numbered document, and everything
         ;; downstream of this table -- every CrossRef's own text, the
         ;; TOC, the lists of figures and tables, both emitters' printed
         ;; numbers -- inherits that from here rather than each
         ;; re-deciding it.
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
         {cite-doc :document cite-warnings :warnings
          ordered-keys :ordered-keys bib-id :bibliography-id
          bib-heading :bibliography-heading} (resolve-citations document bib styles)
         ;; The generated section's own entry, merged in rather than
         ;; numbered: see `bibliography-label-entry` for why it carries
         ;; a word and no number.
         labels (merge labels (bibliography-label-entry bib-id bib-heading))
         {cr-doc :document cr-warnings :warnings} (resolve-cross-refs cite-doc labels)
         diagnostics (structural-diagnostics cr-doc directive-registry lexicon directive-kinds
                                             front-matter-names)]
     {:document cr-doc
      ;; Warning order follows the passes, which now run citations
      ;; first (TASK-64) -- a reader tracing a build reads them in the
      ;; order the passes produced them.
      :diagnostics (-> [] (into include-warnings) (into cite-warnings)
                       (into cr-warnings) (into diagnostics))
      :labels labels
      :ordered-keys ordered-keys
      :bibliography-id bib-id})))

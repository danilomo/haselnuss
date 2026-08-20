(ns haselnuss.extensions.front-matter
  "Front-matter prose blocks (TASK-54): authored content that belongs to
  the document but not to its numbered body -- an abstract, and (TASK-55)
  the acknowledgements/epigraph/dedication family that extends this same
  mechanism.

  What makes them a category rather than four unrelated directives is
  where they are NOT. A front-matter block is outside sectioning, outside
  the table of contents and outside cross-referencing, and all three fall
  out of what it already is: a `:directive`, which `haselnuss.resolver/
  derive-toc` never sees (that pass walks `:section` Blocks only) and
  which numbering ignores unless its own `attr.id` prefix names a
  numbering kind -- and none of these registers one. So the ACs asking
  for those three exclusions need no exclusion logic; they need
  assertions, which `haselnuss.front-matter-test` provides.

  Surface syntax is the ordinary block directive, because the content is
  multi-paragraph marked-up prose:

      :::{abstract lang=pt-BR keywords=\"Aveleira; Colheita\"}
      Duas frases de resumo, com *ênfase*.

      Segundo parágrafo.
      :::

  A YAML front-matter scalar would have been the other candidate and is
  the wrong carrier: `haselnuss.parser/parse-front-matter` produces plain
  strings for every field but `title`, and an abstract has emphasis,
  quotes and paragraph breaks in it. The directive fence already parses
  attributes (including TASK-39's quoted values, which is what makes the
  keywords list writable at all) and already holds a Block vector.

  This namespace owns the *data* -- which names are front-matter blocks,
  what word each prints in each language, how a keywords string becomes a
  list -- plus the registry entries that keep them alive through
  `haselnuss.lower`. It owns no rendering: both emitters render a
  front-matter block through their own `render-blocks`, which the
  registry's `(fn [directive target])` renderer signature gives an
  extension no way to reach (the same limitation
  `haselnuss.extensions.collapsable` records for its own nested content).
  What is registered is therefore a marker, `front-matter-renderer`,
  recognized by the emitters and never called -- exactly the shape
  `haselnuss.emit.latex/environment-renderer` already established for the
  directive-environment table.

  PLACEMENT is the decision this namespace exists to make, and it is
  read differently by the two output shapes:

  - A standalone document places each front-matter block itself, once,
    immediately after the title block and before the body -- the
    conventional position, and the only one available when this emitter
    owns the whole document.
  - A `--fragment` build (TASK-52) emits each block as its own
    `\\input`-able side file and leaves it out of the body entirely. The
    alternative was to render it inline where it was authored; that is
    wrong by construction for the case this exists for. A thesis
    template's own front matter -- capa, folha de rosto, ficha
    catalográfica -- comes before the `\\input` of the body, so an
    abstract inside the body lands after all of it, pages from where a
    Brazilian reader expects the Resumo. A side file lets the template
    place it inside whatever environment it uses (`resumo`, `abstract`,
    something else), which is also why the side file contains no
    environment at all: `\\begin{resumo}` is abntex2's, and template
    furniture is out of scope for this milestone.

  `extract` is the one function both emitters call to make that split."
  (:require [clojure.string :as str]))

(def keyword-separator
  "The separator between terms in a front-matter block's own `keywords`
  attribute (`keywords=\"Aveleira; Colheita\"`).

  A semicolon rather than a comma, deliberately: a single keyword
  contains a comma far more often than it contains a semicolon (\"Knuth,
  Donald\", \"machine learning, applied\"), so a comma-separated list
  cannot be split back apart reliably. The whole point of carrying
  keywords as a list is that a consumer never has to re-split prose."
  #";")

(def keyword-join
  "The separator both emitters print BETWEEN keyword terms. Paired with
  `keyword-separator` here rather than spelled out at each call site, so
  the two cannot drift into a document whose keywords are written one
  way and printed another."
  "; ")

(def blocks
  "The front-matter block names, each mapped to `{:shape :words}` -- how
  that name is set, and the heading word it prints in each language.

  A table, not a branch: TASK-55's acknowledgements, epigraph and
  dedication are three rows here rather than three code paths in two
  emitters. Every entry carries both languages
  `haselnuss.resolver/default-lexicon` carries, for the same reason it
  does -- a document is authored in one language and this word is
  printed, not authored.

  `:shape` is the one thing these four do not share, because LaTeX's
  `abstract` environment is right for exactly one of them. Each value is
  read as data by both emitters (see
  `haselnuss.emit.latex/front-matter-standalone` and
  `haselnuss.emit.html/render-front-matter-block`); all four were
  confirmed compiling together in a `report` document with a
  `\\tableofcontents` present, none of them appearing in it:

  - `:abstract` -- LaTeX's own `abstract` environment. Its heading is
    the environment's, renamed per block.
  - `:heading` -- an unnumbered heading over ordinary prose
    (`\\section*`). Starred, so LaTeX neither numbers it nor lists it,
    which agrees with the exclusion `haselnuss.resolver/body-view`
    already enforces upstream.
  - `:epigraph` -- a right-set italic quotation. Core LaTeX
    (`flushright`), not the `epigraph` package: no AC asks for that
    package's features and this emitter's \"compiles under a standard
    toolchain\" property is worth more.
  - `:dedication` -- centred italic prose.

  An epigraph's attribution is authored as its own last paragraph
  rather than as an `attribution` attribute. It is prose the author
  wrote, in the author's own words and punctuation; a dedicated field
  would be a second way to write the same thing, and both emitters
  would then have to decide how to punctuate it.

  A name absent from this table is not a front-matter block at all --
  `haselnuss.lower` then treats it as any other unregistered directive
  and the build stops naming it, which is TASK-55 AC #3's requirement
  and is inherited rather than re-implemented."
  {"abstract" {:shape :abstract
               :words {"en" "Abstract"
                       "pt-BR" "Resumo"}}
   "acknowledgements" {:shape :heading
                       :words {"en" "Acknowledgements"
                               "pt-BR" "Agradecimentos"}}
   "epigraph" {:shape :epigraph
               :words {"en" "Epigraph"
                       "pt-BR" "Epígrafe"}}
   "dedication" {:shape :dedication
                 :words {"en" "Dedication"
                         "pt-BR" "Dedicatória"}}})

(def headed-shapes
  "The `blocks` shapes that print a heading word at all.

  An epigraph and a dedication print none, in either target: both are
  set apart by position and typography, which is how a book sets them,
  and a title over a dedication reads as a mistake. Shared between the
  emitters rather than decided twice, because a heading in one target
  and none in the other is a structural disagreement about the same
  document -- the thing this codebase refuses elsewhere."
  #{:abstract :heading})

(def keyword-labels
  "The word introducing the keywords line, per language -- printed, like
  a block's own heading word, rather than authored."
  {"en" "Keywords"
   "pt-BR" "Palavras-chave"})

(defn front-matter?
  "True when Block `block` is a front-matter directive: a `:directive`
  whose `:name` is a key of `blocks`."
  [block]
  (and (= :directive (:t block))
       (contains? blocks (:name block))))

(defn lang
  "The language tag a front-matter `directive` is written in: its own
  `lang` attribute prop, falling back to `doc-lang` (the document's own
  `meta.lang`) and then to `\"en\"`.

  Per block rather than per document, and that is the whole point of
  the field. The case this was built for has a Portuguese Resumo and an
  English Abstract inside one document whose `meta.lang` is a single
  value; without a tag of its own, one of the two would be mislabeled
  in both outputs."
  [directive doc-lang]
  ;; `not-empty`, not `or` on the raw value: `lang=` with nothing after
  ;; it parses to an empty string, which is truthy in Clojure and so beat
  ;; `doc-lang` and named a side file `<output>-abstract-.tex` (found by
  ;; review). An empty tag is an absent tag.
  (or (not-empty (get-in directive [:attr :props "lang"]))
      (not-empty doc-lang)
      "en"))

(defn shape
  "Front-matter `directive`'s own `:shape` (`blocks`) -- how it is set,
  which is the one thing the four kinds do not share."
  [directive]
  (get-in blocks [(:name directive) :shape]))

(defn headed?
  "True when front-matter `directive` prints a heading word at all --
  see `headed-shapes` for why an epigraph and a dedication do not."
  [directive]
  (contains? headed-shapes (shape directive)))

(defn heading-word
  "The word front-matter `directive` prints as its own heading, in its
  own `lang` -- an exact language match, else the `\"en\"` entry (the
  same fallback `haselnuss.resolver/words-for` applies for the same
  reason: a document in an unlisted language still gets a readable
  heading rather than none)."
  [directive doc-lang]
  (let [words (get-in blocks [(:name directive) :words])]
    (or (get words (lang directive doc-lang)) (get words "en"))))

(defn keyword-label
  "The word introducing `directive`'s own keywords line, in its own
  `lang` (`keyword-labels`, with the same `\"en\"` fallback
  `heading-word` uses)."
  [directive doc-lang]
  (or (get keyword-labels (lang directive doc-lang)) (get keyword-labels "en")))

(defn keywords
  "Front-matter `directive`'s own keywords as a vector of distinct terms,
  split on `keyword-separator` and trimmed, or `[]` when it carries no
  `keywords` attribute at all.

  A vector, not the authored string, because a keywords line is a list of
  terms and both emitters render them as such -- a consumer that got one
  opaque string back would have to re-split prose to do anything with it,
  which is precisely the mistake this avoids. Empty segments are dropped,
  so a trailing separator (`keywords=\"a; b;\"`) yields two terms rather
  than a third empty one."
  [directive]
  (if-let [raw (get-in directive [:attr :props "keywords"])]
    (into [] (comp (map str/trim) (remove str/blank?)) (str/split raw keyword-separator))
    []))

(defn front-matter-renderer
  "The marker registered as every front-matter block's own per-target
  renderer. Registering *something* is what makes `haselnuss.lower/lower`
  keep the directive rather than demand a fallback it has no way to
  declare; the emitters recognize these directives by name in `extract`
  and render them through their own `render-blocks`, so this is never
  called.

  A function that throws rather than an inert value, for the reason
  `haselnuss.emit.latex/environment-renderer` records in full: the
  registry is one table shared across targets, and a consumer that calls
  whatever non-nil `:emit` value it finds would otherwise splice
  something meaningless into its output. This makes that a named error
  instead."
  [directive target]
  (throw (ex-info
          (str "the front-matter marker was called as a renderer for directive "
               (pr-str (:name directive)) " on target " (pr-str target)
               ": a front-matter block is extracted by the emitter before the body is"
               " rendered (haselnuss.extensions.front-matter/extract), never dispatched"
               " through the registry")
          {:type ::front-matter-marker-called :block directive :target target})))

(def extensions
  "One `haselnuss.registry/register`-shaped entry per front-matter block
  name, each carrying the `front-matter-renderer` marker for both
  targets. No `:kind` (a front-matter block takes part in no numbering
  scheme, which is what keeps it out of cross-referencing) and no
  `:lower` (it has a representation on every target this project emits,
  so it never needs to degrade)."
  (mapv (fn [block-name]
          {:name block-name
           :emit {:html front-matter-renderer :latex front-matter-renderer}})
        (sort (keys blocks))))

(defn extract
  "Splits a Document's own top-level `blocks` into
  `[front-matter body-blocks]` -- the front-matter directives in document
  order, and everything else unchanged.

  Top-level ONLY, and a front-matter block found deeper raises `ex-info`
  (`:type ::nested-front-matter`) naming it. A directive is otherwise
  free to appear anywhere, so this is a real restriction and it is worth
  stating why: the whole contract of a front-matter block is that it is
  not part of the numbered body, and one written inside a section is
  inside the body by construction. Emitting it there anyway would put an
  abstract under a section heading, in the table of contents' shadow, and
  -- in a fragment build -- silently in a different place from the side
  file the host was told to `\\input`. Failing names the mistake instead."
  [document]
  (let [top (:blocks document)]
    (letfn [(check! [block]
              (when (front-matter? block)
                (throw (ex-info
                        (str "front-matter block " (pr-str (:name block))
                             " is nested inside the document body; it must be a top-level"
                             " block, since it is not part of the numbered body at all")
                        {:type ::nested-front-matter :name (:name block)
                         :id (get-in block [:attr :id])})))
              (run! check! (nested block)))
            ;; Every position a Block can be nested in, kept in step with
            ;; `haselnuss.resolver/block-children` -- including a Para's
            ;; own Inlines, which reach Blocks through a footnote. That
            ;; last one is not hypothetical tidiness: without it an
            ;; abstract written inside a footnote passed this check and
            ;; failed later, in an emitter, as a marker-called error that
            ;; named neither the mistake nor where it was.
            (nested [block]
              (case (:t block)
                (:section :block-quote :directive) (:blocks block)
                :list (mapcat identity (:items block))
                :figure [(:content block)]
                :table (mapcat :blocks (mapcat :cells (cons (:head block) (:rows block))))
                :para (note-blocks (:inlines block))
                []))
            (note-blocks [inlines]
              (mapcat (fn [inline]
                        (case (:t inline)
                          :note (:blocks inline)
                          (:emph :strong :strike :small-caps :sub :sup :span :link)
                          (note-blocks (:inlines inline))
                          :cite (mapcat (fn [item]
                                          (concat (note-blocks (:prefix item))
                                                  (note-blocks (:suffix item))))
                                        (:items inline))
                          []))
                      inlines))]
      (run! (fn [block] (run! check! (nested block))) top))
    [(filterv front-matter? top)
     (filterv (complement front-matter?) top)]))

(ns haselnuss.anchor
  "Text-quote anchoring (doc-8 section 1 and section 6, TASK-117): given a
  quoted run of words and a subtree to look in, find WHERE in that subtree
  the words are.

  An annotation -- a highlight, a sticky note -- is not addressed to a
  node. It is addressed to a run of words inside one, and this codebase's
  AST has nothing stable to address it by: `:para` carries no `:attr` at
  all, how a run of prose divides into `:str` leaves is flexmark's
  business rather than the document's, and inserting a sentence renumbers
  every node after it. So an anchor quotes the words instead, the way the
  W3C Web Annotation Data Model's own `TextQuoteSelector` and Hypothesis
  both do:

      {:text \"ripen in early autumn\" :before ... :after ... :nth ...}

  The whole argument for quoting over counting is that a quote FAILS
  LOUDLY. Edit the base document so the words are gone and nothing
  matches, and `resolve-anchor` says so by name rather than silently
  recalculating onto the wrong words. Resolution is therefore total, and
  every outcome other than \"exactly one\" is a named diagnostic that
  applies nothing (doc-8 section 1's own table):

      exactly 1        -> the range
      0                -> :dangling-annotation-anchor
      >1 with no nth   -> :ambiguous-annotation-anchor
      nth out of range -> :annotation-anchor-out-of-range

  Dropping rather than guessing follows `:overlay-missing-target`'s own
  precedent: an annotation applied to the wrong one of three identical
  phrases is a wrong document.

  This namespace owns FINDING the range. Splicing an annotation node into
  one is TASK-118's half, and lives here too once it exists; nothing in
  either half performs I/O, knows about overlays or the CLI, or requires
  a `haselnuss` namespace that does (AC #10) -- so a `--transform` rule
  (m-13) can build annotations programmatically from exactly this.

  ## The inline stratum, and why there is no per-variant `case` here

  One idea governs both halves of the flattening, and it is a set of
  reference kinds:

      inline-stratum = #{::ast/inline ::ast/cite-item}

  A child-bearing field whose `ref-kind` is IN it is flattened into its
  parent's text -- that is what makes `:emph`, `:strong`, `:span`,
  `:link`, `:highlight` and a `:cite`'s own per-item `:prefix`/`:suffix`
  all disappear into the prose around them, which is exactly what a quote
  spanning them has to see. A field whose `ref-kind` is NOT in it -- in
  practice a `[:many ::ast/block]` one, a footnote `:note`'s or a
  `:sticky-note`'s own `:blocks` -- starts a fresh SEARCH UNIT instead,
  because its text is a different run of prose that a quote cannot
  straddle.

  Both halves read those fields off `haselnuss.walk/table`, which derives
  them from the malli schema. So a wrapping inline added to
  `haselnuss.ast/registry` later is flattened for free, and a
  Block-bearing inline added later becomes its own annotatable unit for
  free. That is deliberate: doc-5's own pitfall 1 is traversal drift, and
  `haselnuss.resolver` already carries three hand-written per-variant
  `case`s that list the container inlines by name and were each silently
  incomplete the day TASK-116 added a node type. This namespace has none.

  ## The four steps

  1. SEARCH UNITS (`search-units`). Every position in the subtree holding
     a vector of Inlines whose owner is outside the inline stratum, in
     document order: a Section's `:heading`, a Para's `:inlines`, a
     Figure's or Table's `:caption`, `Meta`'s `:title`. A table cell, a
     list item and a footnote body are all reached through their own
     Blocks, so they are searched without being named here (AC #9).

  2. FLATTEN WITH AN OFFSET MAP (`flatten-unit`). Each unit becomes raw
     text plus, per character, the `[inline-index offset-within-node]` it
     came from -- `inline-index` into the unit's own top-level vector,
     `offset-within-node` into that inline's own flattened text, so a
     boundary falling inside an `:emph` is expressible as an offset
     TASK-118 can recurse with. Leaf text follows
     `haselnuss.select/flatten-text`'s own rules, pinned by a test, so
     the two cannot disagree about what a node's text is.

  3. NORMALISE (`normalise`). Every run of whitespace collapsed to one
     space and both ends trimmed, on the quote and on the document text
     alike. Reflowing a paragraph inserts `:soft-break` nodes that
     flatten to a space, and reflowing is the second most common edit
     after inserting text; an anchor that broke on rewrapping would be
     nearly as brittle as the counter scheme doc-8 rejects. A
     normalised-offset -> raw-offset vector maps a match back exactly.

  4. FIND AND DISAMBIGUATE (`resolve-anchor`). All occurrences in
     document order, filtered by `:before`/`:after` (each an immediate-
     context string) and then by 1-based `:nth`.

  A quote spanning a paragraph boundary belongs to no single unit and
  comes back as `:dangling-annotation-anchor`. Saying so specifically --
  by noticing the quote IS present in the concatenation -- is a
  worthwhile refinement and is deliberately not here (doc-8 section 6)."
  (:require [clojure.string :as str]
            [haselnuss.ast :as ast]
            [haselnuss.walk :as walk]))

;; --- the inline stratum ----------------------------------------------------

(def inline-stratum
  "The reference kinds whose own Inline-bearing fields are flattened INTO
  their parent's text rather than starting a search unit of their own --
  this namespace's one governing idea, see its docstring.

  `::ast/inline` is every wrapping inline (`:emph`, `:strong`, `:span`,
  `:link`, `:highlight`, ...): a quote spanning one has to see through
  it. `::ast/cite-item` is a `:cite`'s own per-item `:prefix`/`:suffix`,
  which `haselnuss.select/flatten-text` likewise folds into the prose
  around the citation.

  Everything else -- in practice a `[:many ::ast/block]` field, a
  footnote `:note`'s or a `:sticky-note`'s own `:blocks` -- is a
  different run of prose that a quote cannot straddle, and starts a fresh
  unit."
  #{::ast/inline ::ast/cite-item})

(def unit-scope
  "The reference kinds `search-units` walks: every kind the schema uses,
  LESS `::ast/fallback`.

  A Fallback (sec4.5) is a static SUBSTITUTE for a Directive on a target
  that cannot render it natively -- an alternative representation of the
  same content, at most one of which is ever emitted, and one `lower`
  chooses between only AFTER annotations have been applied (doc-8 section
  6 puts this pass in `apply-overlays`, well before `lower`). Its
  `:poster` variant carries a `:caption` vector of Inlines, so walking it
  would make that caption a search unit: a phrase written in both a
  directive's body and its poster caption -- which is exactly what a
  fallback caption is FOR, restating the content -- would then resolve as
  `:ambiguous-annotation-anchor` and be dropped, and an annotation that
  did resolve there would be spliced into a representation the chosen
  target may never emit.

  This also matches every other pass in the codebase: `haselnuss.lower`'s
  own docstring records that a Directive's `:fallback` is a position
  nothing here walks."
  (disj walk/every-ref-kind ::ast/fallback))

(def ^:private leaf-text-field
  "The one hand-written table in this namespace: the node types whose own
  text is a plain field rather than something to recurse into, mapped to
  that field. Deliberately identical to the corresponding branches of
  `haselnuss.select/node-text` -- `:str`/`:code` carry `:text`,
  `:math-inline` carries `:tex`, an `:image` contributes its `:alt`, and
  a `:cross-ref` contributes whatever `:text` the numbering pass wrote
  onto it, which before that pass has run is nothing at all.
  `leaf-text-agrees-with-select-test` pins the agreement rather than
  leaving it to inspection (AC #2: the two cannot disagree about what a
  node's text is).

  A node type absent from here and carrying no inline-stratum field of
  its own contributes nothing, which is the right answer for
  `:thematic-break` and for anything a future schema adds."
  {:str :text
   :code :text
   :math-inline :tex
   :image :alt
   :cross-ref :text})

(def ^:private whitespace-types
  "The Inline variants that ARE a space rather than carry one --
  `haselnuss.select/node-text`'s own `(:space :soft-break :line-break)`
  branch. Each contributes exactly one character, so a quote written
  across a source line break still matches after normalisation."
  #{:space :soft-break :line-break})

(defn- inline-stratum-fields
  "The child-bearing fields of a node of type `type-key` that this
  namespace flattens through: those whose `ref-kind` is in
  `inline-stratum`, read off `haselnuss.walk/table` rather than listed
  per variant. `[field arity ref-kind]` triples, in schema declaration
  order -- `ref-kind` carried through so a `:cite`'s own `:items`
  (`[:many ::ast/cite-item]`) recurses as a CiteItem rather than being
  mistaken for another Inline."
  [type-key]
  (into []
        (keep (fn [[field [arity ref-kind]]]
                (when (contains? inline-stratum ref-kind) [field arity ref-kind])))
        (get-in walk/table [:children type-key] [])))

(defn- stratum-text
  "`node`'s own flattened text, `ref-kind` its reference kind: its
  `leaf-text-field` value if it has one, a single space if it is one of
  the `whitespace-types`, else the concatenation of every inline-stratum
  field's own children flattened in turn (`inline-stratum-fields`), which
  is what makes a container inline transparent to a quote spanning it.

  Handles the `:one` and `:many` arities the table reports; a
  `:many-of-many` one cannot currently occur at an inline-stratum
  position (List's `:items` is the schema's only field of that shape, and
  it is Block-typed), and would contribute nothing rather than throw."
  [ref-kind node]
  (let [type-key (walk/node-type (:discriminators walk/table) ref-kind node)]
    (cond
      (contains? whitespace-types type-key) " "

      (contains? leaf-text-field type-key)
      (str (get node (get leaf-text-field type-key)))

      :else
      (str/join
       (for [[field arity child-kind] (inline-stratum-fields type-key)
             :let [value (get node field)]
             :when (some? value)
             child (case arity :one [value] :many value [])]
         (stratum-text child-kind child))))))

(defn inline-text
  "One Inline's own flattened plain text, as this namespace's anchoring
  sees it: every `:str`/`:code`/`:math-inline` leaf and Image `:alt`
  reachable from `inline` WITHOUT leaving the inline stratum, with
  structural markup stripped, following exactly the leaf rules
  `haselnuss.select/flatten-text` applies.

  Two deliberate divergences from that function, both structural rather
  than about leaf text, and both the reason this is not simply a call to
  it:

  - A footnote `:note`'s (or a `:sticky-note`'s) own `:blocks`
    contribute NOTHING here, because those Blocks are separate search
    units of their own. Including them would make every footnote's text
    appear twice -- once inside its containing paragraph's unit and once
    in its own -- so every quote inside a footnote would resolve as
    `:ambiguous-annotation-anchor` and be dropped.
  - A `:cite` carrying SEVERAL items concatenates their
    `:prefix`/`:suffix` text, where `flatten-text` joins them with a
    space it invents (`join-parts`). Inventing a character here would put
    a position in `flatten-unit`'s offset map that no Inline in the
    document accounts for, and TASK-118 has to be able to split at every
    position the map reports."
  [inline]
  (stratum-text ::ast/inline inline))

;; --- 1. search units (AC #9) ----------------------------------------------

(defn- unit-fields
  "The `[:many ::ast/inline]` fields of a node of type `type-key` -- the
  positions that are search units when their owner sits outside the
  inline stratum. Read off `haselnuss.walk/table`, so a node type added
  to the schema later is annotatable with no edit here."
  [type-key]
  (into []
        (keep (fn [[field [arity ref-kind]]]
                (when (and (= :many arity) (= ::ast/inline ref-kind)) field)))
        (get-in walk/table [:children type-key] [])))

(defn- unit-visit
  "`search-units`' own `haselnuss.walk/fold` visit: contributes one unit
  per `unit-fields` position of `node`, unless `ref-kind` is itself in
  `inline-stratum` -- an `:emph`'s own `:inlines` is not a unit, it is
  already inside the unit its enclosing Para opened.

  A unit is `{:path :inlines}`: `path` is `fold`'s own path extended by
  the field keyword, i.e. exactly what `assoc-in`/`update-in` need to
  write a rewritten vector back (which is what TASK-118 does with it).
  An empty vector contributes no unit -- there is no text in it to
  match, and a unit with no candidates would only widen the search."
  [acc path ref-kind node]
  (if (contains? inline-stratum ref-kind)
    acc
    (let [type-key (walk/node-type (:discriminators walk/table) ref-kind node)]
      (into acc
            (keep (fn [field]
                    (when-let [inlines (seq (get node field))]
                      {:path (conj path field) :inlines (vec inlines)})))
            (unit-fields type-key)))))

(defn search-units
  "Every search unit reachable from `node` (of reference kind `ref-kind`,
  `::ast/block` by default -- an overlay `target=` names a Block), in
  document order (AC #9).

  A unit is one vector of Inlines whose owner is outside the inline
  stratum: a Section's `:heading`, a Para's `:inlines`, a Figure's or
  Table's `:caption`, `Meta`'s `:title`. A table cell, a list item and a
  footnote body hold Blocks rather than Inlines, so they are reached
  through those Blocks and searched without being named anywhere here --
  which is the point: `haselnuss.walk/table` supplies the field list, so
  this stays correct for a node type the schema gains later."
  ([node] (search-units ::ast/block node))
  ([ref-kind node]
   (walk/fold {:table walk/table :scope unit-scope :visit unit-visit}
              [] ref-kind node)))

;; --- 2. flatten with an offset map ----------------------------------------

(defn flatten-unit
  "One unit's `inlines` flattened to `{:text :positions}`: `text` is
  every inline's own `inline-text` concatenated in order, with NO extra
  separator (a run of words already carries its inter-word whitespace as
  explicit `:space`/`:soft-break` Inlines of its own), and `positions` is
  a vector holding, for each character of `text`, the `[inline-index
  offset-within-node]` it came from.

  `offset-within-node` is an offset into that one inline's OWN flattened
  text, not into the unit -- so a boundary landing inside an `:emph` is
  already expressed as the offset TASK-118 recurses into that `:emph`
  with. An inline contributing no text at all (an unnumbered
  `:cross-ref`, a footnote) simply occupies no position."
  [inlines]
  (let [texts (mapv inline-text inlines)]
    {:text (str/join texts)
     :positions (into []
                      (mapcat (fn [i text] (map (fn [offset] [i offset]) (range (count text))))
                              (range) texts))}))

;; --- 3. normalisation (AC #3) ---------------------------------------------

(defn normalise
  "`raw` with every run of whitespace collapsed to a single space and
  both ends trimmed, as `{:text :offsets}`: `offsets` holds, for each
  character of the normalised text, the index in `raw` it came from -- a
  collapsed run maps to the index of its FIRST whitespace character, so a
  match that begins at a collapsed space still maps back inside `raw`.

  This is what lets an anchor survive the source being reflowed (doc-8
  section 1): `:soft-break` flattens to a space, so a quote written on
  one line still matches the same words rewrapped across two.

  \"Whitespace\" is `Character/isWhitespace`, which deliberately does NOT
  include U+00A0. A non-breaking space is a typographic choice an author
  made on purpose -- it is there precisely so a line does NOT break
  there -- so collapsing it would let a quote written with an ordinary
  space match text that says something different about how it should
  set. It is matched literally, in the quote and in the document alike."
  [raw]
  (let [n (count raw)]
    (loop [i 0, kept (transient []), offsets (transient []), pending nil]
      (if (= i n)
        {:text (apply str (persistent! kept)) :offsets (persistent! offsets)}
        (let [c (.charAt ^String raw i)]
          (cond
            ;; A whitespace run: remembered, not emitted, so a run at
            ;; either end of `raw` is dropped rather than trimmed later.
            (Character/isWhitespace c)
            (recur (inc i) kept offsets (when (pos? (count kept)) (or pending i)))

            pending
            (recur (inc i) (conj! (conj! kept \space) c) (conj! (conj! offsets pending) i) nil)

            :else
            (recur (inc i) (conj! kept c) (conj! offsets i) nil)))))))

(defn- normalise-text
  "`normalise`'s normalised string alone -- for a quote or a
  `:before`/`:after` context string, which need no offset map of their
  own.

  `str` rather than a type check: nil becomes the empty string (an absent
  `:before` constrains nothing), and a non-string value -- which a
  `--transform` rule building an anchor programmatically can perfectly
  well produce by mistake -- becomes its printed form and then fails to
  match, rather than throwing. Resolution is total for a malformed anchor
  the same way it is for a stale one."
  [s]
  (:text (normalise (str s))))

;; --- 4. find and disambiguate ---------------------------------------------

(defn- occurrences
  "Every index at which `needle` occurs in `haystack`, in order,
  advancing by ONE rather than by the match length: two overlapping
  occurrences of a repeated quote are two candidates, not one. That is
  the conservative direction -- an extra candidate produces
  `:ambiguous-annotation-anchor` and drops the annotation, where missing
  one would silently apply it to whichever the scan happened to keep."
  [haystack needle]
  (loop [from 0, found []]
    (if-let [i (str/index-of haystack needle from)]
      (recur (inc i) (conj found i))
      found)))

(defn- context-matches?
  "Whether one candidate at normalised `[start end)` in `text` satisfies
  the anchor's `:before`/`:after` (each already normalised, either
  possibly blank, in which case it constrains nothing).

  The surrounding text is trimmed on the facing side before comparison,
  so `before=\"cold\"` matches `\"...cold frames\"` whether or not the
  author wrote the separating space into the prop. \"Immediately
  precedes\" is about words, not about whitespace the normaliser has
  already made uniform."
  [text start end before after]
  (and (or (str/blank? before) (str/ends-with? (str/trimr (subs text 0 start)) before))
       (or (str/blank? after) (str/starts-with? (str/triml (subs text end)) after))))

(defn- candidate
  "One candidate range: the unit it is in plus the match's own bounds,
  mapped out of normalised offsets and back through `positions` to the
  unit's own inline vector.

  `:start`/`:end` are raw (un-normalised) character offsets into the
  unit's flattened text, `end` exclusive. `:start-pos`/`:end-pos` are the
  `[inline-index offset-within-node]` pairs for those same two
  boundaries, `:end-pos` one past the last covered character -- so a
  match ending exactly at the end of inline `i` is `[i (count text-of-i)]`
  rather than `[(inc i) 0]`, which is the form a split wants."
  [anchor unit flattened normalised start end]
  (let [{:keys [positions]} flattened
        offsets (:offsets normalised)
        raw-start (nth offsets start)
        raw-last (nth offsets (dec end))
        [last-index last-offset] (nth positions raw-last)]
    {:path (:path unit)
     :inlines (:inlines unit)
     :start raw-start
     :end (inc raw-last)
     :start-pos (nth positions raw-start)
     :end-pos [last-index (inc last-offset)]
     ;; Carried purely so an APPLY-time diagnostic can name the same
     ;; annotation a resolve-time one would (TASK-118 review): a
     ;; `:annotation-anchor-snapped` that cannot say which quote snapped
     ;; is a warning an author has nowhere to take.
     :quote (:text anchor)
     :target (:target anchor)}))

(defn- unit-candidates
  "Every candidate range for `quoted` (the already-normalised quote)
  inside one `unit`, in offset order, filtered by the already-normalised
  `before` and `after` context strings."
  [anchor unit quoted before after]
  (let [flattened (flatten-unit (:inlines unit))
        normalised (normalise (:text flattened))
        text (:text normalised)]
    (into []
          (keep (fn [start]
                  (let [end (+ start (count quoted))]
                    (when (context-matches? text start end before after)
                      (candidate anchor unit flattened normalised start end)))))
          (occurrences text quoted))))

(defn- describe-target
  "The `target=` id an anchor names, quoted for a message, or a plain
  phrase when the caller supplied none -- `haselnuss.anchor` is usable
  without one (a `--transform` rule anchors against whatever node it is
  holding), so a diagnostic has to read sensibly either way."
  [target]
  (if (str/blank? target) "the annotated node" (str "\"" target "\"")))

(defn- dangling
  "The `:dangling-annotation-anchor` diagnostic. `context-excluded?` says
  the quote WAS found and `before=`/`after=` then rejected every
  occurrence -- a different mistake with a different fix, and telling an
  author to update a quote that matched is worse than saying nothing
  (found by review)."
  [anchor quoted context-excluded?]
  {:type :dangling-annotation-anchor
   :quote (:text anchor)
   :target (:target anchor)
   :message
   (if context-excluded?
     (str "\"" quoted "\" was found in " (describe-target (:target anchor))
          ", but no occurrence of it has "
          (str/join " and "
                    (remove nil? [(when-not (str/blank? (:before anchor))
                                    (str "before=\"" (:before anchor) "\" immediately before it"))
                                  (when-not (str/blank? (:after anchor))
                                    (str "after=\"" (:after anchor) "\" immediately after it"))]))
          " -- the annotation was dropped. Check the context props against the"
          " document's current wording.")
     (str "no text matching \"" quoted "\" was found in "
          (describe-target (:target anchor))
          " -- the annotation was dropped. If the base document was edited,"
          " update the quote to match its current wording."))})

(defn- ambiguous
  [anchor quoted n]
  {:type :ambiguous-annotation-anchor
   :quote (:text anchor)
   :target (:target anchor)
   :count n
   :message (str "\"" quoted "\" occurs " n " times in " (describe-target (:target anchor))
                 " -- the annotation was dropped rather than applied to a guess."
                 " Add before=, after= or nth= to say which one is meant.")})

(defn- out-of-range
  [anchor quoted n]
  {:type :annotation-anchor-out-of-range
   :quote (:text anchor)
   :target (:target anchor)
   :count n
   :nth (:nth anchor)
   :message (str "nth=" (:nth anchor) " is out of range: \"" quoted "\" has " n
                 (if (= 1 n) " match in " " matches in ")
                 (describe-target (:target anchor))
                 " -- the annotation was dropped.")})

(defn- nth-candidate
  "The `nth`-th candidate (1-based) of `candidates`, or nil when `nth` is
  outside `[1, count]` -- including a zero or negative `nth`, which is
  out of range rather than an error: an anchor comes from a document, and
  a bad value in one is diagnosed like every other."
  [candidates nth-value]
  (when (and (pos-int? nth-value) (<= nth-value (count candidates)))
    (nth candidates (dec nth-value))))

(defn resolve-anchor
  "Resolves text-quote `anchor` against `node` (of reference kind
  `ref-kind`, `::ast/block` by default), returning `{:range :diagnostics}`
  -- `:range` nil whenever nothing was applied, `:diagnostics` a vector
  of `{:type :message ...}` maps in this codebase's own warning shape.

  `anchor` is `{:text :before :after :nth :target}`. `:text` is the
  quoted run and is the only required key; `:before`/`:after` are
  immediate-context strings and `:nth` a 1-based index, each narrowing
  the candidates in that order; `:target` is the id the caller resolved
  `node` from, used only to name it in a diagnostic.

  Every outcome other than exactly one surviving candidate is a named
  diagnostic and produces no range (doc-8 section 1):

      0                -> :dangling-annotation-anchor
      >1 with no nth   -> :ambiguous-annotation-anchor, naming the count
      nth out of range -> :annotation-anchor-out-of-range, naming it

  A blank `:text` is `:dangling-annotation-anchor` too rather than a
  match at every position: an empty quote addresses nothing.

  The returned range is `{:path :inlines :start :end :start-pos :end-pos}`
  -- see `candidate`. A quote spanning two paragraphs belongs to no single
  unit and so comes back dangling, never as a partial or cross-block
  range."
  ([node anchor] (resolve-anchor ::ast/block node anchor))
  ([ref-kind node anchor]
   (let [quoted (normalise-text (:text anchor))
         before (normalise-text (:before anchor))
         after (normalise-text (:after anchor))
         units (if (str/blank? quoted) [] (search-units ref-kind node))
         candidates (into [] (mapcat #(unit-candidates anchor % quoted before after)) units)
         ;; Only computed when it changes the message: the quote is
         ;; present, and the context props are what rejected it.
         context-excluded? (and (zero? (count candidates))
                                (or (not (str/blank? before)) (not (str/blank? after)))
                                (seq (into [] (mapcat #(unit-candidates anchor % quoted "" ""))
                                           units)))
         n (count candidates)
         nth-value (:nth anchor)]
     (cond
       (zero? n) {:range nil :diagnostics [(dangling anchor quoted (boolean context-excluded?))]}
       (some? nth-value) (if-let [chosen (nth-candidate candidates nth-value)]
                           {:range chosen :diagnostics []}
                           {:range nil :diagnostics [(out-of-range anchor quoted n)]})
       (= 1 n) {:range (first candidates) :diagnostics []}
       :else {:range nil :diagnostics [(ambiguous anchor quoted n)]}))))

;; --- 5. apply: splice the annotation into the range (TASK-118) -------------
;;
;; Built on exactly the same inline-stratum idea as the search half, so
;; there is still no per-variant `case` here. Reading `walk/table` sorts
;; every Inline into one of three kinds, and each has one rule:
;;
;;   CONTAINER   a `[:many ::ast/inline]` field -- :emph, :strong, :span,
;;               :link, :highlight, ... A container the range only PARTLY
;;               covers is recursed into, so the highlight descends inside
;;               it and the tree stays well-formed. One anchor can
;;               therefore produce several :highlight nodes at different
;;               depths: visually seamless, and correct. Flattening or
;;               unwrapping the container to avoid that would change the
;;               document.
;;   SPLITTABLE  `:str` -- a boundary falling inside one splits it into up
;;               to three.
;;   ATOMIC      everything else: :code, :math-inline, :image, :cross-ref,
;;               :cite, and :book-ref despite its Inline `:text` field
;;               (`atomic-inline-types`). There is nothing to split, so a boundary falling
;;               INSIDE one snaps outward to include it whole, and says so
;;               (`:annotation-anchor-snapped`). The quote plainly meant to
;;               cover it, and a slightly wider highlight is a better
;;               failure than a missing one -- but it is never silent.
;;
;; PARTIAL APPLICATION IS FORBIDDEN (doc-8 pitfall 3). If anything about
;; placement fails, nothing is spliced and the annotation is dropped whole:
;; a highlight with no end is worse than no highlight.

(def ^:private atomic-inline-types
  "Inline types that are ATOMIC here even though `haselnuss.walk/table`
  gives them an Inline field, so `container-inline-field` would otherwise
  make them CONTAINERS.

  `:book-ref` (TASK-152): its `:text` is link text the resolver computes
  from ANOTHER book (doc-10), not prose the author wrote, so it is to an
  annotation what a `:cross-ref`'s computed text already is -- something
  a quote can read across (its text still counts towards the flattened
  run, `stratum-text`) but never cut into. A highlight descending into it
  would also be spliced into content that resolution rewrites."
  #{:book-ref})

(defn- container-inline-field
  "The single `[:many ::ast/inline]` field of Inline `node`, or nil when
  it has none or is one of the `atomic-inline-types` -- read off
  `haselnuss.walk/table`, so this is the whole of what makes a node a
  CONTAINER here. Every current such Inline has exactly one (`:inlines`,
  or `:book-ref`'s `:text`, which the atomic set excludes); the first is
  taken if a later schema adds a second, since a highlight has to descend
  into one definite position."
  [node]
  (let [type-key (walk/node-type (:discriminators walk/table) ::ast/inline node)]
    (when-not (contains? atomic-inline-types type-key)
      (first (keep (fn [[field [arity ref-kind]]]
                     (when (and (= :many arity) (= ::ast/inline ref-kind)) field))
                   (get-in walk/table [:children type-key] []))))))

(defn- article-for
  "\"a\" or \"an\" for `word` -- so a diagnostic reads \"an image\" and
  \"an emphasis\" rather than \"a image\". Small, but a warning that
  reads like a template reads like nobody meant it."
  [word]
  (if (contains? #{\a \e \i \o \u} (first word)) "an" "a"))

(defn- names-annotation
  "How an apply-time diagnostic refers to the annotation it is about --
  `the quoted run \"ripen\" in \"sec:intro\"`, or just `the quoted run`
  when the range knows neither.

  Placed INLINE by each message rather than appended as a tail: appended,
  it landed after the verb (`...widened to cover it whole for \"See
  foo\" in \"sec:intro\"`), which reads as though the widening were for
  the quote's benefit. A diagnostic is prose an author reads once, under
  pressure.

  `resolve-anchor` copies the anchor's own `:text`/`:target` onto every
  range it returns for exactly this: a diagnostic an author cannot trace
  back to the annotation that caused it is one they cannot act on, and
  all three resolve-time diagnostics already name both."
  [anchor-range subject]
  (let [quoted (:quote anchor-range)
        target (:target anchor-range)]
    (str subject
         (when quoted (str " \"" quoted "\""))
         (when target (str " in \"" target "\"")))))

(defn- snapped-range-boundary
  "The `:annotation-anchor-snapped` diagnostic for `node`, an atomic leaf
  a RANGE boundary fell inside (see this section's own comment). `edge`
  says which boundary, so the message states what actually happened
  rather than always claiming the run \"ends\" there."
  [anchor-range node edge]
  {:type :annotation-anchor-snapped
   :node-type (:t node)
   :quote (:quote anchor-range)
   :target (:target anchor-range)
   :message (str (names-annotation anchor-range "the quoted run")
                 " " (if (= :start edge) "begins" "ends") " inside "
                 (article-for (name (:t node))) " " (name (:t node))
                 ", which has nothing to split -- the annotation was widened to cover it"
                 " whole")})

(defn- snapped-note-edge
  "The `:annotation-anchor-snapped` diagnostic for a `:sticky-note` whose
  `at=` edge fell inside `node`.

  Same TYPE as `snapped-range-boundary`, deliberately -- both are \"the
  anchor named a position this tree has no seam at, and the annotation
  moved rather than being dropped\" -- but its own message, because
  nothing here is widened: a point cannot be. It MOVED, to the nearest
  seam, and a message claiming otherwise would be describing a different
  edit."
  [anchor-range node at]
  {:type :annotation-anchor-snapped
   :node-type (:t node)
   :quote (:quote anchor-range)
   :target (:target anchor-range)
   :message (str (names-annotation anchor-range "the sticky note anchored at")
                 " has its " (name at) " edge inside "
                 (article-for (name (:t node))) " " (name (:t node))
                 ", which is not a position between two inlines -- it was moved to the "
                 (if (= :start at) "start" "end") " of that node")})

(defn- str-piece
  "`node` (a `:str`) narrowed to `[from to)` of its own text, or nil when
  that is empty -- an empty `:str` is a node that renders nothing and
  says nothing, so a split simply produces one fewer piece."
  [node from to]
  (when (< from to)
    {:t :str :text (subs (:text node) from to)}))

(defn- wrap-inlines
  "`inlines` rewritten so that `[start end)` of its own flattened text is
  covered by `highlight` (a `:highlight` node whose `:inlines` this
  fills in). Returns `[inlines' diagnostics]`.

  One walk, partitioning into three: everything wholly before the range,
  everything the range covers, and everything wholly after it. A `:str`
  straddling either boundary contributes a piece to two of the three; a
  container straddling one is recursed into and stays OUTSIDE the wrap,
  carrying its own nested highlight; an atomic leaf a boundary falls
  inside joins the covered run whole, with a diagnostic.

  When the range turns out to lie entirely inside one container, the
  covered run is empty and no top-level highlight is emitted at all --
  the only `:highlight` for that anchor is the nested one the recursion
  produced."
  [anchor-range inlines start end highlight nested?]
  (let [lengths (mapv (comp count inline-text) inlines)
        total (count inlines)
        ;; Only the OUTERMOST :highlight keeps the annotation's own id.
        ;; One anchor partly covering a container produces several
        ;; :highlight nodes at different depths, and copying the id into
        ;; each made a single annotation report itself as a duplicate id
        ;; (found in review). The id names the annotation, and the
        ;; annotation is one thing however many nodes carry it.
        highlight (cond-> highlight nested? (update :attr dissoc :id))]
    (loop [i 0, pos 0, before [], covered [], after [], diagnostics [],
           snapped-start start, snapped-end end]
      (if (= i total)
        [(-> (vec before)
             (cond-> (seq covered) (conj (assoc highlight :inlines (vec covered))))
             (into after))
         diagnostics
         snapped-start
         snapped-end]
        (let [node (nth inlines i)
              node-end (+ pos (nth lengths i))
              straddles? (and (< pos end) (> node-end start)
                              (or (< pos start) (> node-end end)))]
          (cond
            (<= node-end start)
            (recur (inc i) node-end (conj before node) covered after diagnostics
                   snapped-start snapped-end)

            (>= pos end)
            (recur (inc i) node-end before covered (conj after node) diagnostics
                   snapped-start snapped-end)

            (not straddles?)
            (recur (inc i) node-end before (conj covered node) after diagnostics
                   snapped-start snapped-end)

            (= :str (:t node))
            (let [from (max 0 (- start pos))
                  to (min (count (:text node)) (- end pos))]
              (recur (inc i) node-end
                     (cond-> before (str-piece node 0 from) (conj (str-piece node 0 from)))
                     (cond-> covered (str-piece node from to) (conj (str-piece node from to)))
                     (cond-> after (str-piece node to (count (:text node)))
                             (conj (str-piece node to (count (:text node)))))
                     diagnostics snapped-start snapped-end))

            :else
            (if-let [field (container-inline-field node)]
              (let [[inner inner-diagnostics]
                    (wrap-inlines anchor-range (get node field) (- start pos) (- end pos)
                                  highlight true)
                    node' (assoc node field inner)]
                ;; A partly-covered container is never itself wrapped: it
                ;; sits on whichever side of the range its own start falls,
                ;; carrying the nested highlight the recursion just built.
                (if (< pos start)
                  (recur (inc i) node-end (conj before node') covered after
                         (into diagnostics inner-diagnostics) snapped-start snapped-end)
                  (recur (inc i) node-end before covered (conj after node')
                         (into diagnostics inner-diagnostics) snapped-start snapped-end)))
              ;; An atomic leaf a boundary fell inside: the range snaps
              ;; outward over it whole, and the EFFECTIVE bounds move
              ;; with it -- which is what a sticky note's own edge is
              ;; then measured against, so it does not later report
              ;; itself as falling inside the highlight this very call
              ;; created.
              (recur (inc i) node-end before (conj covered node) after
                     (conj diagnostics
                           (snapped-range-boundary anchor-range node
                                                   (if (< pos start) :start :end)))
                     (min snapped-start pos) (max snapped-end node-end)))))))))

(defn- split-inlines
  "`inlines` split into `[left right diagnostics]` at `offset` of its own
  flattened text -- the point a `:sticky-note` is spliced between.

  A `:str` the offset falls inside is divided. Anything else it falls
  inside has no divisible position, so the note goes to the shallowest
  place where the edge DOES fall between two inlines: before that node
  for `:start`, after it for `:end`. Deliberately not recursed into --
  a sticky note is a marker in the text flow, and burying one inside an
  `:emph` would change what the emphasis covers without making the
  balloon land anywhere different on the page."
  [anchor-range inlines offset at]
  (let [lengths (mapv (comp count inline-text) inlines)
        total (count inlines)]
    (loop [i 0, pos 0, left []]
      (if (= i total)
        [left [] []]
        (let [node (nth inlines i)
              node-end (+ pos (nth lengths i))]
          (cond
            ;; A node the offset has reached, but which contributes NO
            ;; text, is stepped over rather than stopped at. That is what
            ;; makes two annotations at the same anchor accumulate in the
            ;; order they were written: a `:sticky-note` already spliced
            ;; here occupies no characters, so the next one resolved to
            ;; this same offset would otherwise land BEFORE it and the
            ;; pair would come out reversed (found by review). The cost
            ;; is that a note can land after a neighbouring zero-text
            ;; inline (an unnumbered cross-reference), which moves it by
            ;; no visible distance at all.
            (and (<= offset pos) (pos? (nth lengths i)))
            [left (vec (subvec (vec inlines) i)) []]

            (or (<= offset pos) (>= offset node-end))
            (recur (inc i) node-end (conj left node))

            (= :str (:t node))
            (let [k (- offset pos)
                  text (:text node)]
              [(cond-> left (str-piece node 0 k) (conj (str-piece node 0 k)))
               (into (if-let [tail (str-piece node k (count text))] [tail] [])
                     (subvec (vec inlines) (inc i)))
               []])

            :else
            (if (= :start at)
              [left (vec (subvec (vec inlines) i)) [(snapped-note-edge anchor-range node at)]]
              [(conj left node) (vec (subvec (vec inlines) (inc i)))
               [(snapped-note-edge anchor-range node at)]])))))))

(def ^:private schema-for-ref-kind
  "The `haselnuss.ast` schema `apply-annotation` validates its result
  against, per reference kind -- AC #8's `ast/valid?` guard, and the
  cheapest check there is that a split lost or duplicated no text."
  {::ast/block ast/Block
   ::ast/document ast/Document
   ::ast/inline ast/Inline})

(defn- placement-failed
  [reason]
  {:type :annotation-placement-failed
   :reason reason
   :message (str "the annotation could not be placed (" reason
                 ") -- it was dropped whole rather than half-applied")})

(defn- annotation-node?
  "Whether `node` is a placeable annotation node of type `t` -- a map
  carrying that `:t`. Checked rather than assumed: `apply-annotation` is
  advertised for a `--transform` rule to call directly (doc-8 sec6), and
  a rule handing it a string used to throw `ClassCastException` out of
  `assoc` instead of being diagnosed like every other malformed input."
  [node t]
  (and (map? node) (= t (:t node))))

(defn- refuse-placement
  "The `:annotation-placement-failed` diagnostic refusing to apply
  `annotation` at `anchor-range` over `current` (the inline vector
  actually at the range's own path), or nil when everything checks out.

  Every one of these is AC #9: partial application is forbidden, so each
  is caught BEFORE anything is rewritten rather than found afterwards.
  The bounds checks matter for the same reason `normalise-text` coerces
  its input -- a range this namespace produced is always well-formed, but
  a `--transform` rule can build one by hand, and an inverted range used
  to emit both outer pieces of a split `:str` and DUPLICATE the text
  between them, silently, in a document `ast/valid?` still accepted."
  [anchor-range current annotation]
  (let [{:keys [highlight sticky-note]} annotation
        {:keys [start end]} anchor-range
        length (when (vector? current) (count (str/join (map inline-text current))))]
    (cond
      (not= current (:inlines anchor-range))
      (placement-failed "the text at the anchor has changed")

      (and (nil? highlight) (nil? sticky-note))
      (placement-failed "no annotation node to place")

      (not (and (int? start) (int? end)))
      (placement-failed "the range does not name integer offsets")

      (not (<= 0 start end length))
      (placement-failed (str "the range " [start end] " is not inside the "
                             length "-character run it names"))

      (and highlight (not (annotation-node? highlight :highlight)))
      (placement-failed "the :highlight to place is not a :highlight node")

      (and sticky-note (not (annotation-node? sticky-note :sticky-note)))
      (placement-failed "the :sticky-note to place is not a :sticky-note node"))))

(defn apply-annotation
  "Applies `annotation` at `range` (a `resolve-anchor` result) inside
  `node` (of reference kind `ref-kind`, `::ast/block` by default),
  returning `{:document :diagnostics}`.

  `annotation` is `{:highlight :sticky-note :at}`:

  - `:highlight` -- a `:highlight` node whose `:inlines` this fills in
    with the matched run. nil to place none.
  - `:sticky-note` -- a `:sticky-note` node, spliced in whole at the
    `:at` edge. nil to place none.
  - `:at` -- `:start` or `:end`, which edge of the matched run the sticky
    note sits at. \"Between two words\" is the quote of the word before,
    with the default. ONLY `:start` is tested for: absent, nil, `:end`
    and anything else all mean the end. Deliberate -- validating the
    value belongs where an author writes it (TASK-119 diagnoses an `at=`
    outside `start|end` by name and drops the annotation), and a second
    check here would be a second place to keep the vocabulary.

  Both together is doc-8's Acrobat \"highlight with a comment\" shape: the
  run is wrapped, and the note lands immediately after (or before) the
  wrapping node, built from the two node types rather than a third. The
  edge is computed against the ALREADY-WRAPPED vector, which is sound
  precisely because wrapping preserves the flattened text exactly, so no
  offset moves.

  PARTIAL APPLICATION IS IMPOSSIBLE HERE (AC #9). Every check runs
  BEFORE anything is rewritten (`refuse-placement`), and any one of them
  failing returns `node` UNCHANGED with an
  `:annotation-placement-failed` diagnostic naming why: the inline vector
  at the range's own `:path` must still be the one the range was resolved
  against (so a range stale against a document something else has since
  rewritten is refused rather than applied at moved offsets); the range
  must name integer offsets inside that run; and each node to place must
  actually be a node of its own type. The rewritten subtree must then
  satisfy `ast/valid?` as a final backstop -- though not as the only
  guard, since an inverted range produced a document that duplicated text
  and still validated (found in review)."
  ([node anchor-range annotation] (apply-annotation ::ast/block node anchor-range annotation))
  ([ref-kind node anchor-range annotation]
   (let [{:keys [highlight sticky-note at]} annotation
         path (:path anchor-range)
         current (get-in node path)
         schema (schema-for-ref-kind ref-kind ast/Block)]
     (if-let [refusal (refuse-placement anchor-range current annotation)]
       {:document node :diagnostics [refusal]}
       (let [[wrapped wrap-diagnostics snapped-start snapped-end]
             (if highlight
               (wrap-inlines anchor-range current (:start anchor-range) (:end anchor-range)
                             highlight false)
               [current [] (:start anchor-range) (:end anchor-range)])
             ;; Measured against the SNAPPED bounds, not the anchor's own:
             ;; if the range widened over an atomic leaf, the note belongs
             ;; at the edge of what was actually marked. Using the original
             ;; end made the note report itself as falling inside the
             ;; :highlight this same call had just created (found in review).
             edge (if (= :start at) snapped-start snapped-end)
             [left right split-diagnostics]
             (if sticky-note (split-inlines anchor-range wrapped edge at) [wrapped [] []])
             spliced (if sticky-note (into (conj (vec left) sticky-note) right) wrapped)
             document (assoc-in node path spliced)
             diagnostics (into (vec wrap-diagnostics) split-diagnostics)]
         (if (ast/valid? schema document)
           {:document document :diagnostics diagnostics}
           {:document node
            :diagnostics (conj diagnostics
                               (placement-failed
                                (str "the result would not be a valid "
                                     (name (keyword (name ref-kind))))))}))))))

(ns haselnuss.lower
  "The `lower(target, registry)` pass (`SPEC.md` sec8.3): runs after
  `resolve` and before `emit` (sec3's pipeline
  diagram), reducing every custom/dynamic `:directive` node to exactly one
  representation the target can handle, so emitters never meet a node they
  don't know how to render. Per sec8.3's own pseudocode:

  ```
  if   registry[name].emit[target] exists   -> keep it (emitter renders natively)
  elif registry[name].lower(node, target)   -> replace with the special-rule output
  elif node.fallback                        -> apply it (blocks | poster | transform | drop)
  else                                       -> BUILD ERROR: no representation for this target
  ```

  This is the graceful-degradation contract (sec2/sec8.3): a `.hdoc` always
  produces a valid PDF even when interactive richness is lost, because a
  custom element with no target-native renderer *must* declare a fallback
  (or a registry `:lower` rule) -- the final branch above raises instead of
  silently dropping the node, mirroring this codebase's established
  no-silent-drop convention (`haselnuss.parser`'s `::malformed-directive`,
  `haselnuss.registry`'s `::missing-extension-name`).

  Scope: only `:directive` Block nodes are lowered. A `Span` (sec8.1's
  other custom-element shape) carries no `:fallback` field at all in this
  AST (sec4.5/`haselnuss.ast`'s `::inline` `:span` schema), so degrading a
  custom Span is out of scope here -- a deliberate, AST-schema-driven
  limit, mirroring TASK-15/16's own precedent of naming such limits
  explicitly rather than leaving them to be discovered by accident.

  Unlike `haselnuss.resolver`'s passes -- which collect *diagnostics*
  (warnings) for recoverable problems like a dangling cross-reference --
  this pass's failure mode (AC #4) is a hard `ex-info` abort, not a
  collected warning: there is no valid document to hand an emitter once a
  node has no representation at all for the chosen target. This mirrors
  TASK-25's own split between recoverable resolver warnings (printed to
  stderr, build continues) and an unrecoverable build error (non-zero
  exit).

  This namespace requires only `haselnuss.registry`, not
  `haselnuss.resolver` -- a caller wires `parse -> resolve -> lower ->
  emit` together explicitly (sec3), exactly mirroring `haselnuss.registry`
  itself staying independent of `haselnuss.resolver`."
  (:require [haselnuss.ast :as ast]
            [haselnuss.registry :as registry]))

(declare lower-blocks)

;; Fallback application (sec4.5, AC #3)
;;
;; A Fallback's `:blocks` variant flattens to its own static content,
;; unmodified. A deliberate, documented scope limit: the original
;; directive's own `:attr.id` is not re-attached to that flattened content
;; (unlike the `:poster` variant below, sec4.5 gives `:blocks` no field to
;; carry an id on, and SPEC.md names no requirement that one be invented
;; here) -- a fallback author wanting the directive's id to remain
;; anchorable in flattened output is responsible for placing that id on one
;; of `fallback.blocks`' own nodes directly.

(defn- poster-block
  "Synthesizes a `:figure` Block (sec4.3) from `directive`'s `:poster`
  Fallback (sec4.5: `{:kind :poster :src :caption?}`), using the exact
  `{:content {:t :para :inlines [image]} :caption ... :attr ...}` shape
  `haselnuss.parser`'s own standalone-figure-image conversion already
  establishes for a bare image becoming Figure content (sec4.3 requires
  Figure's `:content` to be a Block, and an Image is only ever an Inline,
  so a wrapping `:para` is how a bare image becomes valid Figure content).

  Reuses `directive`'s own `:attr` (not a fresh one) as the synthesized
  Figure's `:attr` -- required, not optional, since sec8.4 explicitly
  documents \"chart's poster participates in figure numbering through its
  own #fig: id\": numbering already ran, in `resolve`, before `lower`,
  keyed by that same id, so the id must survive onto *something* in the
  lowered tree for a later emitter to anchor against."
  [directive fallback]
  (let [image {:t :image :src (:src fallback) :alt "" :attr {:classes [] :props {}}}]
    {:t :figure
     :content {:t :para :inlines [image]}
     :caption (or (:caption fallback) [])
     :attr (:attr directive)}))

(defn- declined?
  "True when a rule's result -- a registry `:lower` rule's or a
  `:transform` rule's -- means \"I have nothing for this target\": nil,
  or an empty collection (sec8.3's own \"lower(node, target) -> Block[]
  | nil\").

  Deliberately not `(not (seq result))`, which is what this replaced:
  `seq` throws `IllegalArgumentException: Don't know how to create ISeq
  from: java.lang.Boolean` on anything not seqable, so a rule returning
  `false` -- the exact shape a `(when ...)`/`(and ...)` rule produces,
  and the shape TASK-49 AC #4 is about -- died with a message naming
  neither the rule nor the directive, before any validation could
  report it (found by review). Anything non-nil that is not an empty
  collection is treated as an *attempt*, so it reaches
  `validate-lowered!` and is reported there."
  [result]
  (or (nil? result)
      (and (coll? result) (empty? result))))

(defn- transform-blocks
  "Runs `directive`'s `:transform` Fallback (sec4.5: `{:kind :transform
  :rule}`) -- a named rule looked up by string name in `transform-rules`
  (`{rule-name (fn [directive target] -> Block[] | nil)}`), mirroring the
  registry `:lower` rule's own `(node, target)` signature (sec8.2/8.3).
  SPEC.md names no further mechanism for resolving a `:transform` rule
  name anywhere else (sec4.5's own comment is just \"named rule run at
  lower-time\"), so `transform-rules` is the minimal, orthogonal,
  caller-supplied extensibility point this pass needs to make that variant
  concretely runnable -- parallel to how `haselnuss.resolver/
  resolve-document`'s own `:directive-registry` option was introduced as a
  decoupled caller-supplied parameter ahead of the real registry
  (TASK-15/16).

  An unknown rule name, or a rule that returns nil/empty, is itself a
  build error: there is no further fallback once already inside applying
  the node's own Fallback. Tagged `::unavailable-transform-rule`, distinct
  from `lower-directive`'s own `::no-representation` (AC #4), so a caller
  can tell \"declared a fallback that doesn't actually resolve\" apart
  from \"declared no fallback at all\".

  What a rule *returns* is not checked here: since TASK-49 every
  lowering branch's result is validated in one place, on
  `lower-directive`'s own result -- see `validate-lowered!`. This
  function keeps only the \"returned nothing\" check, which is
  transform-specific because it is the one branch where declining is
  not an option."
  [directive target transform-rules {:keys [rule] :as _fallback}]
  (let [rule-fn (get transform-rules rule)
        blocks (when rule-fn (rule-fn directive target))]
    (when (declined? blocks)
      (throw (ex-info (str "transform rule \"" rule "\" for directive \""
                           (:name directive) "\" (id "
                           (pr-str (get-in directive [:attr :id]))
                           ") produced no representation for target " target)
                      {:type ::unavailable-transform-rule
                       :name (:name directive)
                       :id (get-in directive [:attr :id])
                       :rule rule
                       :target target})))
    blocks))

(defn- fallback-blocks
  "Applies `directive`'s own `:fallback` (sec4.5), returning the Block
  vector it lowers to: `:blocks` splices its own blocks in place (AC #3);
  `:poster` synthesizes a Figure (`poster-block`); `:transform` runs a
  named rule (`transform-blocks`); `:drop` omits the node entirely (`[]`).

  Nothing upstream guarantees `fallback` is a schema-valid `Fallback` by
  the time it reaches here -- `haselnuss.parser` never constructs a
  `:fallback` field at all (it is parser-inert), so any `:fallback` this
  pass sees is caller/programmatically constructed, and neither this
  namespace nor `lower` itself performs an `ast/valid?` gate on its input.
  A `:kind` that is missing or not one of the four recognized keywords is
  therefore itself treated as \"no valid representation\" (AC #4's own
  concern, reached one level deeper): raises `ex-info`
  (`:type ::unrecognized-fallback-kind`) naming the offending directive
  and the bad `:kind`, instead of letting Clojure's bare `case` throw an
  undescriptive `IllegalArgumentException` naming neither."
  [directive target transform-rules fallback]
  (case (:kind fallback)
    :blocks (:blocks fallback)
    :poster [(poster-block directive fallback)]
    :transform (transform-blocks directive target transform-rules fallback)
    :drop []
    (throw (ex-info
            (str "unrecognized fallback for directive \"" (:name directive)
                 "\" (id " (pr-str (get-in directive [:attr :id]))
                 ") on target " target
                 ": fallback kind " (pr-str (:kind fallback))
                 " is not one of :blocks/:poster/:transform/:drop")
            {:type ::unrecognized-fallback-kind
             :name (:name directive)
             :id (get-in directive [:attr :id])
             :target target
             :fallback fallback}))))

(def ^:private branch-descriptions
  "How each lowering branch is named in an `::invalid-lowered-blocks`
  message (TASK-49 AC #3). The fix differs per branch -- a registry rule
  is code, a `:blocks` fallback is authored data, a `:poster`'s `:src`
  is one field -- so the message has to say which one produced the bad
  block, not just that one did."
  {:lower-rule "the registry :lower rule for directive"
   :blocks "the :blocks fallback of directive"
   :poster "the :poster fallback of directive"
   :transform "the transform rule of directive"
   ;; `:drop` produces `[]`, which is trivially valid, so this string is
   ;; unreachable in practice. Present anyway: without it this table
   ;; would double as the decide-whether-to-validate predicate, and a
   ;; future fallback kind added to `fallback-blocks` but not here would
   ;; be silently exempted -- the same class of hole this task closes,
   ;; one level up (found by review).
   :drop "the :drop fallback of directive"})

(defn- validate-lowered!
  "Validates `blocks` -- what lowering branch `branch` (`:lower-rule`/
  `:blocks`/`:poster`/`:transform`/`:drop`) produced for `directive` on
  `target` -- against `haselnuss.ast/Block`, returning it unchanged when
  it is sound and raising `::invalid-lowered-blocks` when it is not
  (TASK-49).

  TASK-44 checked only a `:transform` rule's result, reasoning that it
  was the one place a malformed tree could enter the pipeline. Review
  disproved that by building one through a registry `:lower` rule --
  same signature, and the branch `haselnuss.cli` actually wires up. So
  the check sits one level up from where TASK-44 put it, covering every
  branch under one error type carrying `:branch`.

  Two shapes, distinguished because they point at different mistakes:

  - not sequential at all -- a rule returning one Block map instead of a
    vector of them, which scanned as a seq of MapEntries and got
    reported as a bad *block* (TASK-44 review).
  - a member that is not a valid Block. Found with `remove` over the
    whole collection, never `when-let` on the first hit: a `nil` or
    `false` block -- what a `(map ...)`/`(when ...)` rule produces -- IS
    the invalid one, and reading it as \"nothing invalid\" is the exact
    defect TASK-44's first version had (AC #4).

  Called on a branch's raw output, before `lower-blocks` recurses into
  it, so the block reported is the one the caller's rule returned rather
  than something derived from it. A Directive nested in that output is
  itself a valid Block, so it passes here and is validated on its own
  terms when the recursion lowers it.

  That ordering tightens one shape that used to work by accident, and
  it is a real tightening rather than a bug fixed (found by review):
  `ast/Block` spells every list-valued field `[:vector ...]`, while the
  recursion normalizes seqs into vectors on the way through, so a rule
  returning `{:t :block-quote :blocks (map f xs)}` produced correct
  output before and is an error now. The schema is the published
  contract for what a rule returns (see `haselnuss.registry/register`),
  and honouring it costs a rule author a `vec`; relying on this pass's
  incidental normalization was relying on an accident. The top level
  stays tolerant -- `sequential?`, so a lazy seq OF blocks is fine."
  [directive target branch blocks]
  (let [fail! (fn [message data]
                (throw (ex-info (str (branch-descriptions branch (str "the " branch " branch of"
                                                                      " directive"))
                                     " \"" (:name directive)
                                     "\" (id " (pr-str (get-in directive [:attr :id]))
                                     ") on target " target " " message)
                                (cond-> (merge {:type ::invalid-lowered-blocks
                                                :branch branch
                                                :name (:name directive)
                                                :id (get-in directive [:attr :id])
                                                :target target}
                                               data)
                                  (= :transform branch)
                                  (assoc :rule (get-in directive [:fallback :rule]))))))]
    (when-not (sequential? blocks)
      (fail! "returned a single value where a vector of Blocks was expected"
             {:block blocks}))
    (let [invalid (remove (partial ast/valid? ast/Block) blocks)]
      (when (seq invalid)
        (fail! "produced a block that is not a valid haselnuss.ast/Block"
               {:block (first invalid)
                :explanation (ast/explain ast/Block (first invalid))})))
    blocks))

(defn- lower-directive
  "The sec8.3 4-way branch for one `:directive` Block `directive`, against
  `target` (a keyword, e.g. `:html`/`:latex`) and `registry` (a
  `haselnuss.registry` map). Returns the vector of 0..N Blocks `directive`
  lowers to, each already run back through `lower-blocks` so any Directive
  nested in the chosen representation is itself lowered (sec8.3: \"emitters
  never meet an unknown node\").

  - AC #1: a native renderer registered for `target` -> `directive` is left
    as-is (still one element, itself unchanged) other than recursively
    lowering its own `:blocks` (still part of the tree an emitter will walk
    when it renders this directive's body).
  - AC #2: no native renderer, but the registry entry's own `:lower` rule
    (`(fn [directive target] -> Block[] | nil)`, sec8.2) returns a non-empty
    result -> replaced with that result.
  - AC #3: neither of the above, but `directive` carries its own
    `:fallback` -> replaced per `fallback-blocks`.
  - AC #4: none of the above -> `ex-info` (`:type ::no-representation`)
    naming the offending directive (`:name`/`:id`) and `target`, instead of
    silently dropping it.

  Whichever of the replacing branches fires, its result is validated
  before it is spliced in (`validate-lowered!`, TASK-49) -- the
  `:fallback` ones by the `:kind` they took, so the error names the one
  that needs fixing. The AC #1 branch is not validated: it keeps the
  Directive the document already contained, which no rule produced."
  [{:keys [target registry transform-rules] :as ctx} directive]
  (let [entry (registry/lookup registry (:name directive))]
    (if (some? (registry/renderer entry target))
      [(update directive :blocks (partial lower-blocks ctx))]
      (let [lower-fn (:lower entry)
            lowered (when lower-fn (lower-fn directive target))
            fallback (:fallback directive)
            [branch blocks]
            (cond
              ;; A rule DECLINING -- nil or empty -- is not this branch
              ;; firing at all (AC #2), so it falls through to the
              ;; fallback below. Anything else is this branch firing,
              ;; well or badly, and lands in the validator that can say
              ;; which (see `declined?` for why that is not `seq`).
              (not (declined? lowered))
              [:lower-rule lowered]

              fallback
              [(:kind fallback)
               (fallback-blocks directive target transform-rules fallback)]

              :else
              (throw (ex-info
                      (str "no representation for directive \"" (:name directive)
                           "\" (id " (pr-str (get-in directive [:attr :id]))
                           ") on target " target
                           ": no native renderer, no registry lower rule, and no fallback")
                      {:type ::no-representation
                       :name (:name directive)
                       :id (get-in directive [:attr :id])
                       :target target})))]
        (lower-blocks ctx (validate-lowered! directive target branch blocks))))))

;; Generic tree traversal -- mirrors `haselnuss.resolver/resolve-block`/
;; `resolve-inline`'s own exhaustive per-variant recursion (every position
;; those functions reach: a Section's `:heading`, a Figure/Table's
;; `:caption`, a Cite item's `:prefix`/`:suffix`, a footnote `:note`'s own
;; nested Blocks, and `meta.title`), not `block-children`'s narrower
;; numbering-only field set -- since `lower`, like `resolve-cross-refs`/
;; `resolve-citations`, is a rewrite pass over the whole tree, not a
;; read-only structural scan like `structural-diagnostics`.
;;
;; Unlike those single-node-in/single-node-out passes, a `:directive` can
;; lower to zero, one, or many Blocks (a `:blocks`/`:transform` fallback or
;; a registry `:lower` rule can each return any number, and `:drop` always
;; returns zero) -- so every Block-list-valued position (`:blocks`, a
;; List's `:items`, a Table cell's `:blocks`) is rewritten with `mapcat`,
;; not a 1:1 `map`. A Figure's own `:content` must stay exactly one Block
;; (sec4.3 requires it); the parser never places a bare Directive there
;; (`haselnuss.parser`'s own standalone-figure-image conversion always
;; wraps an Image in a `:para`), so lowering it is expected to normally
;; yield exactly one Block -- if it ever doesn't, that is itself a build
;; error (`::invalid-figure-content`) naming the offending Figure rather
;; than silently reshaping the AST into something schema-invalid.

(defn- lower-content
  "Lowers Figure `content` (a single Block, sec4.3) and asserts the result
  is still exactly one Block, since Figure's `:content` cannot hold a
  Block[]. Raises `::invalid-figure-content` rather than silently
  reshaping the AST if `content` (in practice never a bare `:directive`,
  per `haselnuss.parser`'s own standalone-figure-image convention) ever
  lowers to a count other than one."
  [ctx content]
  (let [lowered (lower-blocks ctx [content])]
    (if (= 1 (count lowered))
      (first lowered)
      (throw (ex-info
              (str "figure content lowered to " (count lowered)
                   " blocks instead of exactly 1")
              {:type ::invalid-figure-content :content content :lowered lowered})))))

(defn- lower-cell
  "Lowers Cell `cell`'s own `:blocks` (mirrors `haselnuss.resolver/
  resolve-cell`)."
  [ctx cell]
  (update cell :blocks (partial lower-blocks ctx)))

(defn- lower-row
  "`lower-cell` mapped over Row `row`'s `:cells` (mirrors
  `haselnuss.resolver/resolve-row`)."
  [ctx row]
  (update row :cells (partial mapv (partial lower-cell ctx))))

(declare lower-inlines)

(defn- lower-cite-item
  "Lowers any Directive nested (via a footnote) in CiteItem `item`'s
  optional `:prefix`/`:suffix` Inlines (mirrors `haselnuss.resolver/
  resolve-cite-item`), leaving an absent field absent."
  [ctx {:keys [prefix suffix] :as item}]
  (cond-> item
    prefix (assoc :prefix (lower-inlines ctx prefix))
    suffix (assoc :suffix (lower-inlines ctx suffix))))

(defn- lower-inline
  "Lowers any Directive reachable from `inline` (mirrors
  `haselnuss.resolver/resolve-inline`'s own exhaustive per-variant
  recursion): recurses into `:inlines` for `:emph`/`:strong`/`:strike`/
  `:small-caps`/`:sub`/`:sup`/`:span`/`:link`, `:items` for `:cite` (via
  `lower-cite-item`), and `:blocks` for a footnote `:note` (via
  `lower-blocks` -- the one place a Block-carrying Inline can hide a
  Directive). Every other variant carries neither and is returned
  unchanged. Always returns exactly one Inline -- Inlines are never
  spliced/replaced by this pass, only their nested Blocks are rewritten."
  [ctx inline]
  (case (:t inline)
    (:emph :strong :strike :small-caps :sub :sup :span :link)
    (update inline :inlines (partial lower-inlines ctx))
    :cite (update inline :items (partial mapv (partial lower-cite-item ctx)))
    :note (update inline :blocks (partial lower-blocks ctx))
    inline))

(defn- lower-inlines
  "`lower-inline` mapped over every Inline in `inlines`, in order."
  [ctx inlines]
  (mapv (partial lower-inline ctx) inlines))

(defn- lower-block
  "Lowers `block`, returning the vector of 0..N Blocks it lowers to
  (mirrors `haselnuss.resolver/resolve-block`'s own exhaustive per-variant
  recursion, generalized from a 1:1 `assoc` to a `mapcat`-friendly vector
  result since only a `:directive` can ever produce a count other than
  one): a Section's `:heading` (via `lower-inlines`) and `:blocks`; a
  Para's `:inlines`; a List's `:items` (a vector of Block vectors); a
  Figure's `:content` (via `lower-content`, kept singular) and `:caption`;
  a Table's `:head`/`:rows` (via `lower-row`) and `:caption`; a BlockQuote's
  `:blocks`; a `:directive` (via `lower-directive`, sec8.3's 4-way branch).
  Every other variant (`:code-block`/`:math-block`/`:include`/
  `:thematic-break`) carries neither and is returned unchanged."
  [ctx block]
  (case (:t block)
    :section [(-> block
                  (update :heading (partial lower-inlines ctx))
                  (update :blocks (partial lower-blocks ctx)))]
    :para [(update block :inlines (partial lower-inlines ctx))]
    :list [(update block :items (partial mapv (partial lower-blocks ctx)))]
    :figure [(-> block
                 (update :content (partial lower-content ctx))
                 (update :caption (partial lower-inlines ctx)))]
    :table [(-> block
                (update :head (partial lower-row ctx))
                (update :rows (partial mapv (partial lower-row ctx)))
                (update :caption (partial lower-inlines ctx)))]
    :block-quote [(update block :blocks (partial lower-blocks ctx))]
    :directive (lower-directive ctx block)
    [block]))

(defn- lower-blocks
  "`lower-block` mapped-and-concatenated over every Block in `blocks`, in
  order (a `mapcat`, not a 1:1 `map`, since a `:directive` can lower to any
  number of Blocks)."
  [ctx blocks]
  (into [] (mapcat (partial lower-block ctx)) blocks))

(defn lower
  "The `lower(target, registry)` pass (SPEC.md sec8.3): reduces every
  custom/dynamic `:directive` node reachable from `document` (a resolved
  `haselnuss.ast/Document`) to exactly one representation `target` (a
  keyword, e.g. `:html`/`:latex`) can handle, per this namespace's own
  docstring. `registry` is a `haselnuss.registry` map.

  Returns the fully lowered Document directly -- there is nothing to
  report on success (unlike `haselnuss.resolver`'s passes, this one
  produces no diagnostics/warnings of its own); a node with no
  representation at all raises `ex-info` instead (AC #4), so a caller sees
  either a complete, emitter-ready Document or an exception naming the
  offending node, never a partially-lowered tree.

  `([document target registry])` uses no `:transform-rules` (an
  authored `:transform` fallback with no rule available for lookup then
  itself raises, since it has no representation to offer -- see
  `transform-blocks`). `([document target registry opts])` takes
  `opts` `{:transform-rules {}}`: a map from a `:transform` Fallback's
  `:rule` name (string) to `(fn [directive target] -> Block[] | nil)`
  (sec4.5), the minimal caller-supplied mechanism this pass needs to make
  that Fallback variant concretely runnable (SPEC.md names no other
  mechanism for resolving a `:transform` rule name).

  Note: `transform-rules` is a single flat string-keyed map shared across
  every extension in the document -- mirroring `haselnuss.registry`'s own
  flat `:name` namespace convention (registering the same key twice is
  last-write-wins there too), so this is a known, low-risk pattern in this
  codebase rather than a new one. It does mean two different extensions
  that each pick the same `:rule` name would silently collide (whichever
  is registered last in the map wins) -- worth keeping in mind for any
  future caller assembling this map from multiple independent extension
  sources, e.g. by namespacing `:rule` names per extension, though nothing
  here enforces that.

  Whatever a directive lowers to is validated against
  `haselnuss.ast/Block` before it is spliced in, whichever branch
  produced it -- a registry `:lower` rule, a `:blocks` or `:poster`
  fallback, or a `:transform` rule (TASK-49, generalizing TASK-44's
  transform-only check). A malformed result raises
  `::invalid-lowered-blocks` carrying `:branch`, the offending block and
  malli's own explanation of it; a `:transform` rule that returns
  nothing at all still raises the separate `::unavailable-transform-
  rule`, since \"returned nonsense\" and \"returned nothing\" want
  different fixes. See `validate-lowered!`."
  ([document target registry] (lower document target registry {}))
  ([document target registry opts]
   (let [ctx {:target target :registry registry
              :transform-rules (:transform-rules opts {})}
         title (get-in document [:meta :title])]
     (cond-> document
       title (assoc-in [:meta :title] (lower-inlines ctx title))
       true (update :blocks (partial lower-blocks ctx))))))

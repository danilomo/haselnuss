(ns haselnuss.ext
  "The `haselnuss.ext` namespace `haselnuss.sandbox` binds inside SCI (TASK-90,
  TASK-103) -- the one extra namespace both `--extensions` and `--transform`
  sidecar files see beyond sci's own default core. Real, ordinary Clojure:
  `haselnuss.sandbox` binds each var here into its shared sci context by
  reference (`{'raw raw 'text text ...}`), so this namespace is tested and
  reasoned about like any other, and there is no second copy of any of this
  logic living only inside a sandboxed string.

  `raw` is TASK-90's own verbatim-output constructor (unchanged by this
  task, moved here from where it used to live inline inside
  `haselnuss.extensions.sidecar`): building the `[:raw s]` node both
  emitters' `RenderData` vocabularies use for the trust-gated escape hatch.
  Calling it never grants trust by itself -- that is still decided host-side,
  by `haselnuss.registry/register`'s own `:trusted?` -- and a `--transform`
  rule has no path to it at all: `haselnuss.transform`'s own return-value
  contract (a node, `nil`, or a sequence of nodes) accepts plain AST data
  only, never a `[:raw ...]` node, and `haselnuss.extensions.rules` does not
  wrap a rule's `:transform` the way `haselnuss.extensions.sidecar` wraps an
  `:emit` renderer with trust either way -- `raw` simply has nothing to be
  spliced into for a transform to reach.

  `text`, `map-text` and `text->inlines` are TASK-103's own addition: the
  helpers a `--transform` rule actually needs to touch prose, since
  \"uppercase this heading\" or \"strip that prefix\" is what an author
  reaches for first, and neither is one line against a nested `Inline` tree
  by hand (`haselnuss.ast` sec4.4) -- a heading is a *vector* of `:str`/
  `:emph`/`:link`/... nodes, not a bare string, and rewriting only the
  leaves while keeping the surrounding markup means a recursive walk either
  way. `text` and `map-text` both accept a `haselnuss.ast` node OR a vector
  of nodes -- the shape a `:heading`/`:caption`/`:title`/... FIELD already
  is (`haselnuss.ast` sec4.2-4.4) -- so a rule reads `(ext/text
  (:heading node))` directly rather than mapping over it by hand first."
  (:require [clojure.string :as str]
            [haselnuss.ast :as ast]
            [haselnuss.select :as select]
            [haselnuss.walk :as walk]))

(defn raw
  "`[:raw s]` (`s` coerced to a string) -- both emitters' `RenderData`
  vocabularies' verbatim escape hatch, honored only when the surrounding
  build was given `:trusted?` true (`haselnuss.registry/register`'s own
  `:trusted?`, forced host-side, never by code running inside the sandbox --
  see this namespace's own docstring). `(str s)`, not `s` verbatim, matches
  `haselnuss.extensions.sidecar`'s own original recorded reasoning (TASK-90):
  a no-op for the common case (already a string), and harmless for an
  uncommon one, since a non-string second element was always going to be
  coerced by the surrounding `str`/`str/join` an emitter's own
  `render-blocks` concatenates it through regardless."
  [s]
  [:raw (str s)])

(defn text
  "`node`'s own flattened plain text (`haselnuss.select/flatten-text`,
  TASK-96), or, when `node-or-nodes` is a vector of nodes rather than a
  single node -- the shape a `:heading`/`:caption`/`:title`/`:prefix`/
  `:suffix` FIELD already is (`haselnuss.ast` sec4.2-4.4), not a node in its
  own right -- every element's own flattened text concatenated in order with
  NO separator inserted between them, mirroring `haselnuss.select`'s own
  private `inlines->text`: an Inline vector already carries its own
  inter-word whitespace as explicit `:space`/`:soft-break`/`:line-break`
  nodes, so concatenating bare reproduces the original spacing rather than
  doubling it."
  [node-or-nodes]
  (if (map? node-or-nodes)
    (select/flatten-text node-or-nodes)
    (apply str (map select/flatten-text node-or-nodes))))

(defn- fallback-node?
  "True when `node` is a `haselnuss.ast` Fallback (sec4.5) rather than a
  Block or Inline -- `:kind` is the field ONLY a Fallback variant carries as
  its own discriminator (`haselnuss.walk`'s own docstring: `:t` for
  Block/Inline, `:kind` for Fallback, per `haselnuss.ast/dispatch-by`'s own
  convention); a Block or Inline node has no `:kind` field of its own."
  [node]
  (contains? node :kind))

(defn- map-text-one
  "One `haselnuss.ast` node (a Block, Inline or Fallback), with every
  descendant `:str` node's own `:text` passed through `f` -- the recursive
  engine behind `map-text`, one node at a time. `haselnuss.walk/rewrite-post`
  (TASK-94) does the actual walking, scoped to `walk/every-ref-kind` so a
  `:str` reachable through ANY child-bearing field (a Table cell, a CiteItem
  prefix, a footnote's own Blocks, not merely `:blocks`/`:inlines`) is
  reached, not only the fields `haselnuss.transform`'s own narrower
  `default-scope` walks. `::ast/fallback`/`::ast/inline` (`fallback-node?`)
  is only ever needed to decode `node`'s OWN discriminator field correctly
  at this top level -- `:t` and `:kind` are otherwise interchangeable for
  `haselnuss.walk/table`'s purposes once the walk actually descends, since
  every node type's own child-bearing fields are keyed by its bare `:t`/
  `:kind` tag regardless of which reference kind was passed in to name it.
  The `:rewrite-fn` here never deletes or splices -- always exactly the one
  node back, `[node']` -- so unwrapping `rewrite-post`'s own top-level
  result with `first` is safe."
  [node f]
  (let [walker {:table walk/table
                :scope walk/every-ref-kind
                :rewrite-fn (fn [_path _ref-kind node]
                              [(if (= :str (:t node)) (update node :text f) node)])}]
    (first (walk/rewrite-post walker (if (fallback-node? node) ::ast/fallback ::ast/inline) node))))

(defn map-text
  "`node-or-nodes` -- a single `haselnuss.ast` node, or a vector of them
  (see `text`'s own docstring for why a `:heading`/`:caption`/... FIELD is
  exactly this shape) -- with every descendant `:str` node's own `:text`
  passed through `f` (`(fn [s] -> s')`), the node(s) otherwise unchanged.
  `f` sees only the raw string a `:str` leaf carries, never surrounding
  markup: `(map-text heading str/upper-case)` upper-cases every word of a
  heading while leaving its emphasis, links and spacing exactly as they
  were."
  [node-or-nodes f]
  (if (map? node-or-nodes)
    (map-text-one node-or-nodes f)
    (mapv #(map-text-one % f) node-or-nodes)))

(defn text->inlines
  "Splits plain text `s` into a vector of `:str`/`:space` Inlines, mirroring
  `haselnuss.parser`'s own private `text->inlines` (CommonMark's Str/Space
  atomicity, sec4.4: each run of non-whitespace is its own `:str`, each run
  of whitespace collapses to one `:space`) -- so hand-built prose a
  `--transform` rule constructs still round-trips through `--target hdoc`
  the same way parsed prose does, rather than one giant `:str` the emitter
  has no word boundary to wrap at. Deliberately re-implemented here rather
  than calling `haselnuss.parser`'s own (private) version: this namespace is
  bound directly inside the sandbox's shared sci context
  (`haselnuss.sandbox`), and stays a small leaf requiring nothing but
  `clojure.string`, rather than pulling the whole parser (flexmark, the
  directive grammar, ...) in for two lines of regex neither side's own
  behavior depends on the other for."
  [s]
  (->> (re-seq #"\S+|\s+" s)
       (mapv (fn [tok] (if (str/blank? tok) {:t :space} {:t :str :text tok})))))

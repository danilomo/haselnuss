(ns haselnuss.walk
  "The schema-derived AST traversal (doc-6 'Traversal is derived from the
  schema, not hand-written'; TASK-84): reads which fields of which node
  type hold children, and how many, directly off `haselnuss.ast/registry`'s
  own malli EDN (`derive-table`), then offers two generic primitives --
  `rewrite` and `fold` -- that walk any Block, Inline, Fallback, Cell, Row,
  CiteItem, Meta or Document node through that table instead of a
  hand-written `case` per node type.

  This closes the hole doc-6 measures: `haselnuss.resolver`'s five
  `resolve-*`/`cite-resolve-*`/`cite-keys-in-*`/`ids-in-*`/`id-locations-
  in-*` families and `haselnuss.lower`'s own eight traversal functions all
  hand-encode the same parent/child relationships, and a Block/Inline
  variant missing from any one of them is not rejected -- it is silently
  skipped by whichever pass forgot it (`resolver.clj:1338`/`:1861`,
  `lower.clj:432`). Deriving the table from the schema means a node type
  new to `haselnuss.ast/registry` is automatically covered by every pass
  built on `rewrite`/`fold`; `haselnuss.walk-test`'s own canary test fails
  loudly if the derivation itself ever misses one (AC #7).

  This namespace only introduces the table and the two primitives -- it
  changes no existing pass's behavior. TASK-85/86/87 port `haselnuss.lower`
  and `haselnuss.resolver` onto them; this task's own tests are the only
  ones exercising this namespace directly (AC #8).

  ## The table (`table`, `derive-table`)

  `(:children table)` maps a bare node-type keyword -- a Block/Inline/
  Fallback's own `:t`/`:kind` tag (`:section`, `:cite`, `:poster`, ...), or,
  for the registry entries with no tag of their own (`Attr`, `Col`, `Cell`,
  `Row`, `CiteItem`, `Meta`, `Document`), the un-namespaced registry key
  itself (`:attr`, `:cell`, ...) -- to a vector of `[field [arity
  ref-kind]]` pairs, in schema DECLARATION order (AC #2): the raw schema is
  a literal vector, so reading its fields in order is reading them in
  declaration order (`map-children`). `arity` is `:one` (a single nested
  node, e.g. Section's `:attr`), `:many` (a vector of them, e.g. Section's
  `:blocks`) or `:many-of-many` (a vector of vectors -- List's `:items` is
  currently the schema's only field shaped this way, sec4.3). `ref-kind` is
  the registry key the position refers to (e.g. `::block`): what `rewrite`/
  `fold` dispatch the next step on, and what a caller's `:scope` set is
  drawn from. A field with no nested AST node at all (CodeBlock's `:text`,
  List's `:ordered`) has no entry: this table is child-bearing fields only,
  `block-children`/`inline-blocks`' own contract generalized to every node
  kind (AC #1).

  `(:discriminators table)` maps a `:multi`-schema registry key (`::block`,
  `::inline`, `::fallback`) to the name of the field that carries its own
  variant tag (`:t` for Block/Inline, `:kind` for Fallback) -- found from
  the schema data itself (the field whose type is a bare `[:= tag]`)
  rather than by inspecting the registry's actual dispatch function, which
  is an opaque compiled closure (`haselnuss.ast/dispatch-by`) this
  namespace has no way to read `:t`/`:kind` back out of. A registry key
  with no `:multi` -- `Attr`/`Col`/`Cell`/`Row`/`CiteItem`/`Meta`/
  `Document` -- has no entry here: there is no tag to read, so `node-type`
  falls back to that registry key's own bare name for those instead.

  `table` derives all of this from `haselnuss.ast/registry` itself, once,
  at load time; `derive-table` is the pure function it is built from,
  exported so tests can run it against a small synthetic registry to pin
  the parsing rules precisely, independent of the full AST schema's size.

  ## Reference kinds and scope (AC #6)

  A 'reference kind' is a registry key: what a `[:ref ...]`-typed field
  points at. `rewrite` and `fold` both take a `:scope` -- a set of
  reference kinds -- in their `walker` map, and only descend through a
  field whose own `:ref-kind` is IN it: `#{::block}` alone walks only
  Block-typed positions (mirroring `block-children`'s Block-only
  traversal), `#{::block ::inline}` also reaches a Para's `:inlines` or a
  footnote `:note`'s own `:blocks` (mirroring `resolve-block`/`resolve-
  inline`'s combined coverage), and `#{::block ::inline ::fallback}`
  additionally reaches a Directive's own `:fallback` -- a position no
  existing pass in this codebase walks today (`haselnuss.lower`'s own
  top-of-namespace comment: 'Scope: only :directive Block nodes are
  lowered'), reachable now for whichever future pass needs it.
  `every-ref-kind` is every reference kind the schema uses anywhere, for a
  caller (or a test) that wants no filtering at all.

  ## The primitives

  `fold` is read-only: it visits every reachable node in document order --
  a node itself before its own descendants, exactly `id-locations-in-
  block`'s own 'own id, then descendants' order -- calling `(visit acc
  path ref-kind node)` at each one (AC #5). `path`, threaded alongside,
  extends by the field keyword the walk is currently inside plus, at a
  `:many`/`:many-of-many` position, that child's own index/indices -- so a
  Table's third row's second cell's first Block, reached from the Table
  itself, is `[:rows 2 :cells 1 :blocks 0]`.

  `rewrite` replaces a node with zero, one or many nodes of the same
  reference kind: it calls `((:rewrite-fn walker) ref-kind node)`, then
  recurses into every child-bearing field of EACH node that returns,
  mirroring `lower-directive`/`lower-blocks`'s own 'substitute, then lower
  what it substituted in' order, so a nested Directive inside a
  freshly-substituted Fallback is itself reachable. Called at a `:many`/
  `:many-of-many` position, each element can independently expand to 0, 1
  or several nodes (AC #3, `lower-blocks`' own `mapcat`); called at a
  `:one` position, the result must come back exactly 1-for-1 -- a count
  other than one, whether at Figure's `:content`, Document's `:meta` or
  any other `:one` position in the schema, raises `::arity-error` naming
  the field and the node it belongs to (AC #4, generalizing `lower-
  content`'s own Figure-only `::invalid-figure-content` check to every
  `:one` position).

  Neither primitive threads anything beyond what its own signature says --
  `(:rewrite-fn walker)` collects no warnings of its own, unlike
  `haselnuss.resolver`'s current `resolve-*` family, which returns `[node'
  warnings]` tuples. A caller wanting a side log (a future port of
  `resolve-cross-refs`/`resolve-citations`) is expected to close over a
  mutable accumulator (an `atom`/`volatile!`) inside its own `:rewrite-fn`/
  `:visit`, read back out once the top-level call returns -- ordinary
  Clojure for a pure tree transform with a side channel, and simpler than
  making either primitive generically monadic for a need only one kind of
  future caller has."
  (:require [haselnuss.ast :as ast]))

;; --- Deriving the table from the raw schema -------------------------------

(def ^:private ast-namespace
  "The namespace every `haselnuss.ast/registry` key of `haselnuss.ast`'s
  own is qualified with -- derived from an actual keyword in that registry
  rather than a hardcoded string literal, so a rename of the namespace is
  a compile error here instead of a silent mismatch."
  (namespace ::ast/attr))

(defn- own-registry-entries
  "Just the entries of `registry` (a merged malli registry, e.g.
  `haselnuss.ast/registry`) that are namespaced `haselnuss.ast` --
  `haselnuss.ast`'s own ten (`::attr`/`::block`/`::cell`/`::cite-item`/
  `::col`/`::document`/`::fallback`/`::inline`/`::meta`/`::row`), found by
  filtering `(keys registry)` rather than a hand-copied list of them, so a
  future `haselnuss.ast/registry` key needs no matching edit here. Malli's
  own `default-schemas`, merged into the same map purely so `[:ref ...]`
  resolves, are unnamespaced or namespaced under a bare symbol -- never
  `haselnuss.ast` -- and so are excluded."
  [registry]
  (into {}
        (filter (fn [[k _]] (and (keyword? k) (= ast-namespace (namespace k)))))
        registry))

(defn- opts+type
  "`[opts type]` for one raw malli field spec `[field-key & rest]`, `rest`
  either `(type)` (`[:t [:= :section]]`) or `(opts type)` (`[:lang
  {:optional true} :string]`) -- `opts` nil for the former. This table
  never distinguishes the two shapes any further than that: whether a
  position is `:optional` doesn't change whether it holds a reference or
  what arity it is (`rewrite`/`fold` already handle an actually-absent
  field the same way regardless, per this namespace's own docstring), so
  `:optional true` carries no information this derivation needs."
  [field-spec]
  (if (= 3 (count field-spec))
    [(nth field-spec 1) (nth field-spec 2)]
    [nil (nth field-spec 1)]))

(defn- ref-spec
  "`[arity ref-kind]` for field type `field-type`: `[:ref k]` is `[:one
  k]`, `[:vector [:ref k]]` is `[:many k]`, `[:vector [:vector [:ref
  k]]]` is `[:many-of-many k]` (List's `:items` is currently the schema's
  only field shaped this way, sec4.3). nil when `field-type` carries no
  reference at all -- a plain `:string`/`:int`/`:boolean`/`[:enum ...]`/
  `[:map-of ...]`, or a `[:vector ...]` of one of those (Attr's
  `:classes`, Meta's `:authors`) -- which is every field this table has
  nothing to say about."
  [field-type]
  (cond
    (and (vector? field-type) (= :ref (first field-type)))
    [:one (second field-type)]

    (and (vector? field-type) (= :vector (first field-type))
         (vector? (second field-type)) (= :ref (first (second field-type))))
    [:many (second (second field-type))]

    (and (vector? field-type) (= :vector (first field-type))
         (vector? (second field-type)) (= :vector (first (second field-type)))
         (vector? (second (second field-type))) (= :ref (first (second (second field-type)))))
    [:many-of-many (second (second (second field-type)))]

    :else nil))

(defn- discriminator-field
  "The name of the field whose type is a bare `[:= tag]` among
  `field-specs` (one `:multi` variant's own `[:map & field-specs]`) --
  `:t` for a Block/Inline variant, `:kind` for a Fallback one, per
  `haselnuss.ast/dispatch-by`'s own convention -- found from the schema
  DATA itself rather than hardcoded: the registry's actual dispatch
  function is an opaque compiled closure this namespace has no way to
  read a field name back out of (see `derive-table`). nil if no field has
  that shape."
  [field-specs]
  (some (fn [field-spec]
          (let [[_ field-type] (opts+type field-spec)]
            (when (and (vector? field-type) (= := (first field-type)))
              (first field-spec))))
        field-specs))

(defn- map-children
  "The `table` entry for one `[:map & field-specs]` schema (a `:multi`
  variant's own map, or a plain non-multi registry entry): every field
  with a `ref-spec`, as `[field-name [arity ref-kind]]` pairs, in
  `field-specs`' own declaration order (AC #2) -- a scalar field, or the
  `[:= tag]` discriminator field itself (`:=` is never a `ref-spec`
  shape), is simply absent from the result rather than needing to be
  filtered out separately."
  [field-specs]
  (into []
        (keep (fn [field-spec]
                (let [[_ field-type] (opts+type field-spec)]
                  (when-let [spec (ref-spec field-type)]
                    [(first field-spec) spec]))))
        field-specs))

(defn- multi-variants
  "`[tag field-specs]` for every variant of a `[:multi {:dispatch fn} &
  variants]` schema, `variants` each `[tag [:map & field-specs]]` --
  drops the leading `:multi` keyword and its opts map, the two elements
  that are never a variant (confirmed against the live registry, not
  assumed)."
  [multi-schema]
  (for [[tag [_map & field-specs]] (drop 2 multi-schema)]
    [tag field-specs]))

(defn derive-table
  "Derives `{:children {...} :discriminators {...}}` (this namespace's own
  docstring) from `registry`, a merged malli registry shaped like
  `haselnuss.ast/registry`: `haselnuss.ast`'s own entries (`own-registry-
  entries`) walked as plain vector/keyword/map data, with no `malli.core`
  call anywhere in this namespace -- per doc-6, 'the schemas are plain
  malli data ... so the traversal table can be read straight off the raw
  EDN'.

  A `:multi` entry (`::block`/`::inline`/`::fallback`, sec4.3-4.5's three
  tagged unions) contributes one `:children` entry per variant, keyed by
  the variant's own tag, plus one `:discriminators` entry (registry key ->
  discriminator field name). A plain `[:map ...]` entry (`Attr`, `Col`,
  `Cell`, `Row`, `CiteItem`, `Meta`, `Document`) contributes a single
  `:children` entry keyed by its own un-namespaced registry key, and no
  `:discriminators` entry -- there is no tag to dispatch on."
  [registry]
  (reduce
   (fn [derived [registry-key schema]]
     (if (and (vector? schema) (= :multi (first schema)))
       (let [variants (multi-variants schema)
             discriminator (some discriminator-field (map second variants))]
         (cond-> (update derived :children into
                         (map (fn [[tag field-specs]] [tag (map-children field-specs)]))
                         variants)
           discriminator (assoc-in [:discriminators registry-key] discriminator)))
       (assoc-in derived [:children (keyword (name registry-key))]
                 (map-children (rest schema)))))
   {:children {} :discriminators {}}
   (own-registry-entries registry)))

(def table
  "`(derive-table haselnuss.ast/registry)` -- the parent/child table this
  codebase's passes are meant to walk against (AC #1), computed once at
  load time. See this namespace's own docstring for its shape."
  (derive-table ast/registry))

(defn node-type
  "The `(:children table)` key for `node`, a value of reference kind
  `ref-kind` (e.g. `::block`): the value at `node`'s own discriminator
  field (`(get discriminators ref-kind)`, e.g. `:t` for a Block) when
  `ref-kind` is a `:multi` kind, coerced to a keyword the same way
  `haselnuss.ast/dispatch-by` does -- a value fresh off a JSON round-trip
  (`haselnuss.json`) can still carry its tag as a string until malli's own
  json-transformer runs -- else (`Attr`/`Col`/`Cell`/`Row`/`CiteItem`/
  `Meta`/`Document`, which carry no tag of their own) `(keyword (name
  ref-kind))`, since there is only one shape and a caller reaching one
  already knows which it has."
  [discriminators ref-kind node]
  (if-let [field (get discriminators ref-kind)]
    (keyword (get node field))
    (keyword (name ref-kind))))

(def every-ref-kind
  "Every reference kind `table` uses anywhere -- the union of every
  `ref-kind` named in `(:children table)` -- for a caller (or a test) that
  wants `rewrite`/`fold`'s `:scope` to filter nothing at all (AC #6)."
  (into #{}
        (mapcat (fn [field-specs] (map (fn [[_ [_ ref-kind]]] ref-kind) field-specs)))
        (vals (:children table))))

;; --- fold: a read-only, path-reporting visit (AC #5) -----------------------

(declare fold-position)

(defn fold
  "Read-only fold over `node` (of reference kind `ref-kind`, e.g.
  `::block`) and everything reachable from it through a field whose own
  `:ref-kind` is in `(:scope walker)` (AC #6): calls `((:visit walker) acc
  path ref-kind node)` for `node` itself first, then walks its own
  child-bearing fields in schema declaration order (AC #2), each field's
  own children in their own vector order -- pre-order, so a node is
  visited before its descendants, exactly document order (mirrors
  `id-locations-in-block`'s own 'own id, then descendants').

  `path` (the 4-arity default is `[]`, a true root) is the path to `node`
  itself; each descent extends it by the field keyword the walk is
  currently inside plus, at a `:many`/`:many-of-many` position, that
  child's own index/indices (AC #5) -- e.g. a Table's third row's second
  cell's first Block, reached from the Table itself, is `[:rows 2 :cells
  1 :blocks 0]`.

  `walker` is `{:table {:children :discriminators} :scope #{ref-kind...}
  :visit (fn [acc path ref-kind node] -> acc')}`. Returns the final `acc`,
  threaded through every visit in traversal order -- a `visit` collecting
  into a vector (`conj`) reproduces that order exactly, the guarantee
  `resolve-seq`'s own callers rely on today."
  ([walker acc ref-kind node] (fold walker acc ref-kind node []))
  ([walker acc ref-kind node path]
   (let [{:keys [children discriminators]} (:table walker)
         visit (:visit walker)
         type-key (node-type discriminators ref-kind node)
         acc' (visit acc path ref-kind node)]
     (reduce (fn [acc [field spec]] (fold-position walker acc path node field spec))
             acc'
             (get children type-key [])))))

(defn- fold-position
  "One child-bearing field (`field`, `[arity ref-kind]` spec) of `node`,
  already visited by `fold`: folds every node reachable through it,
  extending `path` per this namespace's own `fold` docstring. Skipped
  entirely -- contributing nothing to `acc` -- when `ref-kind` is not in
  `(:scope walker)`, or when the field is altogether absent from `node`
  (an optional position -- CiteItem's `:prefix`/`:suffix`, Meta's
  `:title`, Directive's `:fallback` -- that isn't there has nothing to
  fold over)."
  [walker acc path node field [arity ref-kind]]
  (if-not (contains? (:scope walker) ref-kind)
    acc
    (case arity
      :one
      (if-let [child (get node field)]
        (fold walker acc ref-kind child (conj path field))
        acc)

      :many
      (if-let [children (get node field)]
        (reduce (fn [acc [i child]] (fold walker acc ref-kind child (conj path field i)))
                acc (map-indexed vector children))
        acc)

      :many-of-many
      (if-let [groups (get node field)]
        (reduce (fn [acc [i group]]
                  (reduce (fn [acc [j child]]
                            (fold walker acc ref-kind child (conj path field i j)))
                          acc (map-indexed vector group)))
                acc (map-indexed vector groups))
        acc))))

;; --- rewrite: 0/1/many at list positions, exactly 1 at a single one -------
;; (AC #3, #4) ---------------------------------------------------------------

(defn- expect-count-one!
  "`replacements` when it holds exactly one node, else raises
  `::arity-error` (AC #4) naming `field` -- the `:one` position of
  `parent-node` (of reference kind `parent-ref-kind`) that produced it --
  and `parent-node`'s own type, so a caller can still identify e.g. 'that
  Figure' from the exception (a future port of `lower-content`'s
  Figure-only `::invalid-figure-content` check onto this, generalized to
  every `:one` position in the schema). A `:one` position, unlike a
  `:many`/`:many-of-many` one, has nowhere in the map shape to put zero or
  several replacement nodes, so any count but 1 is unrepresentable rather
  than merely surprising."
  [walker parent-ref-kind parent-node field replacements]
  (if (= 1 (count replacements))
    (first replacements)
    (let [discriminators (:discriminators (:table walker))]
      (throw (ex-info
              (str "position " field " of a "
                   (node-type discriminators parent-ref-kind parent-node)
                   " node rewrote to " (count replacements)
                   " node(s), expected exactly 1")
              {:type ::arity-error
               :field field
               :node parent-node
               :count (count replacements)
               :replacements replacements})))))

(declare rewrite)

(defn- rewrite-position
  "`parent-node` with one child-bearing field (`field`, `[arity ref-kind]`
  spec) rewritten: `:many`/`:many-of-many` `mapcat` `rewrite` over each
  element, so an element can vanish, stay one node or fan out to several
  (AC #3, `lower-blocks`' own `mapcat`); `:one` requires `rewrite` to come
  back with exactly one node (`expect-count-one!`, AC #4). Left alone --
  same as `fold-position` -- when `ref-kind` is out of `(:scope walker)`,
  or the field is altogether absent from `parent-node`."
  [walker parent-node parent-ref-kind field [arity ref-kind]]
  (if-not (contains? (:scope walker) ref-kind)
    parent-node
    (case arity
      :one
      (if-let [child (get parent-node field)]
        (assoc parent-node field
               (expect-count-one! walker parent-ref-kind parent-node field
                                  (rewrite walker ref-kind child)))
        parent-node)

      :many
      (if-let [kids (get parent-node field)]
        (assoc parent-node field
               (into [] (mapcat (partial rewrite walker ref-kind)) kids))
        parent-node)

      :many-of-many
      (if-let [groups (get parent-node field)]
        (assoc parent-node field
               (mapv (fn [group] (into [] (mapcat (partial rewrite walker ref-kind)) group))
                     groups))
        parent-node))))

(defn rewrite
  "Rewrites `node` (of reference kind `ref-kind`) by calling `((:rewrite-fn
  walker) ref-kind node)`, then recursing into every child-bearing field
  of EACH node it returns -- descending only through a field whose own
  `:ref-kind` is in `(:scope walker)` (AC #6) -- and returns the resulting
  vector of 0..N nodes (AC #3).

  `(:rewrite-fn walker)` runs before recursion, on the node as given, and
  never recurses itself: mirroring `lower-directive`, it decides only
  whether THIS node becomes something else, and whatever it returns is
  walked by THIS function afterwards, not by itself -- a registry rule's
  or a Fallback's own nested Directive is reached this way, exactly
  `lower-directive`'s own `(lower-blocks ctx (validate-lowered! ...))`
  tail call. A node the caller has no opinion about should return `[node]`
  unchanged, indistinguishable at this level from 'kept, but its children
  may still change'.

  `walker` is `{:table {:children :discriminators} :scope #{ref-kind...}
  :rewrite-fn (fn [ref-kind node] -> [node...])}`. Called at a `:many`/
  `:many-of-many` position (`rewrite-position`) via `mapcat`; called at a
  `:one` position, the caller requires exactly 1 node back, raising
  `::arity-error` otherwise (AC #4) -- this function itself doesn't know
  or care which kind of position invoked it."
  [walker ref-kind node]
  (let [{:keys [children discriminators]} (:table walker)
        rewrite-fn (:rewrite-fn walker)]
    (mapv (fn [replacement]
            (let [type-key (node-type discriminators ref-kind replacement)]
              (reduce (fn [node' [field spec]] (rewrite-position walker node' ref-kind field spec))
                      replacement
                      (get children type-key []))))
          (rewrite-fn ref-kind node))))

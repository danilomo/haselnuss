(ns haselnuss.registry
  "The extension registry (`SPEC.md` sec8.2): a
  pluggable, per-target renderer table -- the mechanism through which
  custom and dynamic elements (`theorem`/`admonition`/`collapsable`/
  `chart`/`form`/future kinds) are expressed without inventing new AST
  node types or new syntax (sec8.1: any custom element is already just a
  `Directive{name, attr, blocks, fallback?}` or a `Span{attr}`).

  A registry is a plain map from a directive/span's own `name` string
  (`Directive`'s `:name` field, sec4.3; a `Span` carries no `:name` field
  of its own, sec4.4 -- a caller deriving a role name from one of a
  Span's `attr.classes`, per sec4.1's \"classes select a directive/role's
  behavior\", is that caller's own concern, not this namespace's) to an
  entry `{:name :kind :emit :lower}` (see `register`). This is exactly
  the shape TASK-15's `haselnuss.resolver/structural-diagnostics`/
  `resolve-document` already documented their `:directive-registry`
  option as expecting -- \"anything usable with `contains?` keyed by
  directive name... once TASK-16 lands, its registry map works
  identically through `contains?`, no adapter needed\" -- so a registry
  built here plugs into that resolver option directly.

  This namespace is deliberately a leaf: it does not require
  `haselnuss.resolver` and never calls a registered `:emit`/`:lower`
  function itself (calling `:emit` is the not-yet-built emitter core's
  job, TASK-19/21; calling `:lower` is TASK-17's `lower` pass) -- it only
  stores and retrieves opaque data. `numbering-lexicon` is the one
  function that bridges to `haselnuss.resolver/number-document`'s own
  lexicon-merge convention (TASK-11 AC #4), and it does so by producing a
  plain map a caller passes in explicitly, rather than importing
  `haselnuss.resolver` to do it automatically -- keeping the dependency
  direction the caller's choice, not this namespace's.")

(defn register
  "Adds `extension` to `registry` (a plain map, `{}` for a fresh one),
  returning the updated registry. `extension` is `{:name :kind? :emit?
  :lower?}` (AC #1):

  - `:name` -- the directive/span name this extension answers to (a
    non-blank string, matching `Directive`'s own `:name` field, sec4.3);
    required -- an extension with no name has nothing to key the registry
    by, so this throws `ex-info` (`:type ::missing-extension-name`) rather
    than silently dropping it or registering it under a bogus key, per
    this codebase's established no-silent-drop convention (e.g.
    `parser.clj`'s `::malformed-directive`).
  - `:kind` -- optional; when present, a `LabelLexicon` merge-fragment in
    exactly `haselnuss.resolver/default-lexicon`'s own per-kind shape --
    a map from one numbering-kind keyword to a `{:counter :words
    :node-types?}` entry, e.g. `{:frm {:counter :global :node-types
    #{:directive} :words {\"en\" {:singular \"Form\"}}}}` -- the
    same shape that namespace's own docstring already documents callers
    merging in by hand (`(merge default-lexicon {:frm {...}})`, TASK-11
    AC #4); registering it here is what makes
    `registry-lexicon`/`numbering-lexicon` able to assemble it
    automatically instead (AC #4). `:node-types` is optional but worth
    supplying (TASK-36): it names the AST node roles the kind labels,
    and `haselnuss.resolver/structural-diagnostics` role-checks a
    labeled node against it -- a kind that omits it is simply never
    role-checked. Despite AC #4's singular \"a kind\"
    wording, nothing here actually limits this map to one entry -- a
    single extension may contribute several kind keywords at once, since
    `registry-lexicon` just merges whatever map is here; this is a
    deliberate widening; a single-kind extension is still the common
    case. Nil (the default) registers no kind at
    all -- most extensions need none: SPEC.md sec8.4's own `chart` example
    participates in *existing* Figure numbering purely through its own
    `#fig:...` id, with no registry involvement, since numbering is
    id-prefix-driven regardless of directive name (TASK-11).
  - `:emit` -- optional per-target native-renderer map, e.g. `{:html
    render-html :latex render-tex}` (room for `:epub`/`:dita` later, per
    sec8.2's own TS interface); defaults to `{}` (never nil), so
    `renderer`/`renderer-for` can always `get-in` into it uniformly.
    Every value here is opaque to this namespace -- never called, only
    stored -- since a renderer's call signature is an emitter-core
    concern this codebase has not yet decided (TASK-19/21 are still To
    Do).
  - `:lower` -- optional `(fn [node target] ...)` special-rule function
    (sec8.3); also opaque here, never called -- TASK-17's `lower` pass is
    the one that will call it. What it must return, since TASK-49
    validates it: a sequential collection of nodes each valid against
    `haselnuss.ast/Block`, or nil/`[]` to decline the target and let the
    node's own `:fallback` apply. \"Valid\" is literal -- every
    list-valued field in that schema is a `[:vector ...]`, so a rule
    building one with `map`/`filter`/`concat` owes it a `vec`. A result
    that is neither declines nor valid Blocks is a build error naming
    this rule's directive (`haselnuss.lower/validate-lowered!`), rather
    than an invalid Document reaching an emitter.

  Registering the same `:name` again replaces its previous entry
  entirely (last write wins), mirroring plain `assoc` semantics -- there
  is no partial-merge of an old and a new registration for the same
  name."
  [registry {ext-name :name :keys [kind emit lower] :as extension}]
  (when-not (and (string? ext-name) (seq ext-name))
    (throw (ex-info "extension name is required and must be a non-blank string"
                    {:type ::missing-extension-name :extension extension})))
  (assoc registry ext-name {:name ext-name :kind kind :emit (or emit {}) :lower lower}))

(defn register-all
  "`register` folded over `extensions` (a collection of extension maps, in
  order), starting from `registry`. A convenience for registering several
  extensions at once; later extensions in `extensions` win over earlier
  ones sharing the same `:name`, exactly as repeated `register` calls
  would."
  [registry extensions]
  (reduce register registry extensions))

(defn lookup
  "The entry registered under `name` in `registry`, or nil if `name` has
  no entry at all -- the \"no registry entry\" half of AC #3's
  distinguishability requirement. Callers combine this with `renderer`
  rather than a single all-in-one lookup so the two failure modes stay
  distinguishable by construction: `(lookup registry name)` itself is nil
  exactly when `name` is unregistered, regardless of what `target` a
  caller might have asked for."
  [registry directive-name]
  (get registry directive-name))

(defn renderer
  "The native renderer registered for `target` (a keyword, `:html`/
  `:latex`/...) in `entry` (a `register`-shaped entry, typically
  `(lookup registry name)`'s result), or nil if `entry` has no `:emit`
  entry for that target -- the \"entry has no renderer for the requested
  target\" half of AC #3's distinguishability requirement (AC #2's direct
  positive case: a registered name with a renderer for `target` returns
  it here). Note this is a different nil than `lookup` returning nil for
  an unregistered name: this function is only ever handed an `entry` that
  already exists, so its own nil return means \"registered, but not for
  this target\", never \"not registered at all\" -- that distinction
  lives one level up, at the `lookup` call that produced `entry`."
  [entry target]
  (get-in entry [:emit target]))

(defn renderer-for
  "One-call convenience combining `lookup` and `renderer`: looks up `name`
  in `registry`, then that entry's renderer for `target`. Returns:
  - `::unregistered` if `name` has no entry in `registry` at all
  - `::no-renderer` if `name` is registered but has no `:emit` entry for
    `target`
  - the renderer function itself, otherwise (AC #2)

  `::unregistered` and `::no-renderer` are two distinct, directly
  `=`-comparable keywords (not both a bare `nil`), so AC #3's
  distinguishability holds from this single call alone, without a caller
  needing to compose `lookup`/`renderer` by hand (though that composition
  remains available, and is what this function is built from)."
  [registry directive-name target]
  (if-let [entry (lookup registry directive-name)]
    (or (renderer entry target) ::no-renderer)
    ::unregistered))

(defn registered-kinds
  "The set of numbering-kind keywords contributed by any entry in
  `registry` (every entry's own `:kind`, when present, is itself a map
  from kind keyword to `LabelLexicon` entry -- see `register`'s
  docstring -- so this is the union of those maps' own key sets)."
  [registry]
  (into #{} (mapcat keys) (keep :kind (vals registry))))

(defn registry-lexicon
  "A `LabelLexicon`-shaped map (`haselnuss.resolver/default-lexicon`'s own
  per-kind `{:counter :words :node-types?}` shape) assembled from every entry's own
  `:kind` fragment in `registry` (AC #4's mechanism): entries are visited
  in `:name`-sorted order (not `registry`'s own hash-map iteration order,
  which Clojure makes no ordering guarantee about) before merging, so
  that the rare case of two different extensions registering the same
  kind keyword resolves deterministically -- the alphabetically-later
  extension name's own definition for that kind wins -- rather than
  depending on map-iteration order, which could otherwise vary run to
  run. An entry with no `:kind` (the common case, per `register`'s
  docstring) contributes nothing.

  Caution: a kind keyword here is merged purely by that keyword, with no
  connection to the extension's own `:name` -- if a misconfigured
  extension registers a `:kind` fragment under a *built-in* keyword
  (any key of `haselnuss.resolver/default-lexicon` -- `:sec`/`:ch`/
  `:fig`/`:tbl`/`:eq`, the theorem-like family, `:lst`/`:alg`; named by
  reference rather than listed here, since the list has grown twice and
  a copy of it goes stale), the merge silently overrides that
  built-in's `default-lexicon` entry document-wide when this result feeds
  `numbering-lexicon`/`number-document` (mirrors `merge`'s own
  last-write-wins semantics, per TASK-11's own \"pass a merged-in
  lexicon\" precedent -- not a new failure mode this function
  introduces, just one worth knowing about here specifically)."
  [registry]
  (->> (sort (keys registry))
       (map registry)
       (keep :kind)
       (apply merge {})))

(defn numbering-lexicon
  "The `LabelLexicon` a caller should pass as `haselnuss.resolver/
  number-document`'s second argument to make every kind registered in
  `registry` available to the numbering pass (AC #4): `(merge base
  (registry-lexicon registry))`, so a registered kind is added alongside
  `base`'s own entries, and -- on a genuine kind-keyword collision --
  overrides `base`'s own entry for it (mirroring `haselnuss.resolver/
  default-lexicon`'s own documented merge convention, `(merge
  default-lexicon {:frm {...}})`, verbatim). `base` defaults to `{}`,
  deliberately -- this namespace does not require `haselnuss.resolver`,
  so it cannot default `base` to that namespace's `default-lexicon`
  itself; a caller wanting the built-in kinds preserved calls this as
  `(numbering-lexicon registry resolver/default-lexicon)`, then feeds the
  result into `number-document` themselves, e.g.:

  ```
  (resolver/number-document doc (numbering-lexicon registry resolver/default-lexicon))
  ```"
  ([registry] (numbering-lexicon registry {}))
  ([registry base] (merge base (registry-lexicon registry))))

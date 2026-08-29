(ns haselnuss.extensions.collapsable
  "The `collapsable` extension (`SPEC.md` sec8.4): the
  reference built-in extension (TASK-18), re-expressing the original
  `Collapsable` React component -- from the TypeScript reader this format
  was extracted from, outside this repository: a `<button>` that toggles a
  hidden `<span>`, driven by two plain strings `text`/`hiddenText` -- as a
  `Directive{name:\"collapsable\"}` (sec8.1) -- SPEC.md's own words: \"the
  simplest instance of the whole mechanism.\" Exercises the extension
  registry (`haselnuss.registry`, TASK-16), the `lower` pass
  (`haselnuss.lower`, TASK-17) and, since TASK-89, the emitter core's own
  v2 renderer contract end-to-end on this one real element, proving
  sec8.4's own worked-example row verbatim: `emit.html` an interactive
  widget, static targets via `fallback: {kind:\"blocks\"}` flattening both
  parts inline.

  AST shape (a design decision this namespace makes, since SPEC.md says no
  more than \"Directive{name, attr, blocks, fallback?}\"):

  - `attr.props [\"summary\"]` -- the always-visible label. A plain string,
    matching `haselnuss.ast`'s own `Attr` schema (`:props` is
    `[:map-of :string :string]`, string-valued only -- a rich/Block-valued
    label is not representable there regardless) and the original
    component's own `text` field, itself a plain string.
  - `:blocks` -- the collapsed/hidden content, revealed on toggle: an
    ordinary Block vector -- richer than the original component's own
    `hiddenText` plain string, since the Directive AST's `:blocks` field is
    already Block-list-typed. A deliberate, natural widening; not a SPEC
    requirement.
  - `:fallback {:kind :blocks}` -- sec8.4's own exact prescription for
    `collapsable` -- splices a synthesized always-visible summary Para (the
    label, emphasized) ahead of the directive's own (no-longer-hidden)
    `:blocks`, unmodified: \"flatten both parts inline.\"

  `render-html` (this extension's native `:html` renderer, registered on
  `extension`) speaks TASK-89's v2 contract: `(fn [directive ctx] ->
  data)`, `renderer-v2`-marked. It produces hiccup-shaped data -- a real
  `<details>`/`<summary>` element, HTML5's native disclosure widget,
  strictly more accessible than the original component's `useState`+
  `onClick` button, and requiring no client-side scripting story this
  codebase has not built -- which `haselnuss.emit.html`'s own core
  serializes and escapes; this namespace itself no longer escapes
  anything.

  Before TASK-89, this namespace hand-rolled its own minimal Block/
  Inline-to-HTML sub-renderer for the hidden content (`:para` plus a
  handful of purely-textual Inline variants only, with a hard-coded
  one-entry map recognizing a nested `collapsable` by name so it could
  dispatch back into its own `render-html`), because the OLD `(fn
  [directive target] -> string)` registry contract gave a renderer no way
  to reach the real emitter's own `render-blocks`/`render-inlines` at
  all -- documented at length here as a scope limit, and named as the
  reason a real registry-driven nested-directive dispatch was \"left open
  pending TASK-19/21's own renderer call-signature decision\". TASK-89 is
  that decision: `render-details` now renders `:blocks` through `ctx`'s
  own `:render-blocks` callback, the exact same visitor the rest of the
  document uses, so this extension supports every Block/Inline variant
  the core emitter does (not a hand-picked subset), and a nested
  `:directive` -- `collapsable` inside itself, or any OTHER
  natively-rendered directive -- resolves correctly through the ordinary
  registry, with no name this namespace has to know in advance. Both
  limitations this docstring used to name at length are gone with the
  code that caused them, rather than merely worked around.

  This extension needs no registry `:kind` (collapsable participates in no
  numbering scheme, exactly as the original component never did). It DOES
  need a registry `:lower` rule (`lower-rule`), correcting an earlier
  claim here that its own per-instance `:fallback` alone handled every
  non-`:html` target: `haselnuss.parser` constructs no `:fallback` field
  at all, so an authored collapsable has none, and without the rule
  `lower` aborts the build for every target but `:html` -- confirmed by
  running the real CLI over an authored one for `:latex`."
  (:require [clojure.string :as str]
            [haselnuss.registry :as registry]))

(defn summary
  "The always-visible label of collapsable `directive` (its own
  `attr.props[\"summary\"]`), or nil if unset."
  [directive]
  (get-in directive [:attr :props "summary"]))

(defn- details-id
  "Directive `directive`'s own `attr.id`, or nil -- the raw value
  `render-details`'s own hiccup `:id` attrs-map entry needs; the core
  emitter this data reaches escapes it, so this returns the value
  itself, not an HTML fragment (contrast the pre-TASK-89 `id-attr`, which
  built one by hand and escaped it here). This is what makes a rendered
  `<details>` element anchorable via a URL fragment (a real bug fix, kept
  from that earlier version: this attribute used to be silently dropped
  regardless of `attr.id`, per `make`'s own docstring, which see for what
  this id does -- and does not -- make a collapsable referenceable for)."
  [directive]
  (get-in directive [:attr :id]))

(defn- details-classes
  "The space-joined class list `render-details`'s own hiccup `:class`
  attrs-map entry needs: `base-class` (the owning extension's own styling
  hook -- `collapsable`/`small-collapsable`) first, then any
  caller-supplied `attr.classes` from `directive`, appended in order --
  raw and unescaped, since the core emitter escapes an attribute value
  generically now (a real bug fix, kept from the pre-TASK-89 version:
  `attr.classes` used to be silently dropped entirely, only the
  hardcoded base class ever reaching the output)."
  [base-class directive]
  (str/join " " (cons base-class (get-in directive [:attr :classes] []))))

(defn render-details
  "Renders `directive` as an HTML5 `<details>`/`<summary>` disclosure --
  the shared body of every disclosure extension built on this namespace
  -- as TASK-89's v2 hiccup DATA (a vector `haselnuss.emit.html` itself
  serializes and escapes), not a string. `opts` keys:

  - `:base-class` -- the class always present on the `<details>` element,
    alongside any authored `attr.classes` (`details-classes`).
  - `:body-class` -- when set, the revealed `:blocks` are wrapped in a
    `[:div {:class ...}]` carrying it; when nil they are emitted directly
    as the `<details>` element's own children, which is what `collapsable`
    has always done and what keeps its output byte-identical. The wrapper
    exists because a *block* box is what gives an inline-rendered
    disclosure a line break before and after its revealed content, and CSS
    cannot introduce a box that the markup does not have (see
    `haselnuss.extensions.small-collapsable`).

  `directive`'s own `:blocks` (the revealed content) are rendered through
  `ctx`'s own `:render-blocks` callback (TASK-89 AC #2) -- this
  namespace's former hand-rolled `blocks->html`/`block->html`/
  `inline->html` sub-renderer, and the nested-directive dispatch map it
  needed to recognize a nested `collapsable` by name, are both gone: the
  core emitter that callback reaches already supports every Block/Inline
  variant it defines, and already dispatches ANY nested `:directive` with
  a native renderer through the ordinary registry -- see this namespace's
  own docstring.

  `directive`'s own `attr.id`, when set, becomes the element's own `:id`
  attrs-map entry, which is what makes a rendered disclosure anchorable
  by URL fragment."
  [{:keys [base-class body-class]} directive ctx]
  (let [id (details-id directive)
        body ((:render-blocks ctx) (:blocks directive))]
    [:details (cond-> {:class (details-classes base-class directive)} id (assoc :id id))
     [:summary (or (summary directive) "")]
     (if body-class [:div {:class body-class} body] body)]))

(defn render-html
  "This extension's native `:html` renderer (registered on `extension`,
  TASK-89's v2 contract: `(fn [directive ctx] -> data)`, `renderer-v2`-
  marked): renders collapsable `directive` as an interactive HTML5
  `<details>`/`<summary>` disclosure -- `summary` is always visible;
  `directive`'s own `:blocks` (the collapsed content) are revealed
  natively by the browser on toggle, no JavaScript required, and rendered
  through `ctx`'s own `:render-blocks` callback (`render-details`), so
  any Block/Inline this codebase's own core emitter supports is supported
  here too. `directive`'s own `attr.id`, when set, is emitted as the
  `<details>` element's own `:id` (`details-id`) -- see `make`'s
  docstring for exactly what this id does, and does not, make a
  collapsable referenceable for. `directive`'s own `attr.classes`, when
  set, are appended to the element's own `:class` alongside the
  always-present `collapsable` class (`details-classes`)."
  [directive ctx]
  (render-details {:base-class "collapsable"} directive ctx))

(defn fallback-for
  "The `:fallback {:kind :blocks}` (sec8.4) for a collapsable directive
  with label `summary-text` and collapsed content `hidden-blocks`: a
  synthesized always-visible summary Para (the label, emphasized) followed
  by `hidden-blocks` unmodified -- \"flatten both parts inline.\" Public so
  a sibling disclosure extension degrades identically on static targets:
  the distinction the two draw is one of HTML layout only, and nothing
  about it survives into a target that cannot collapse anything."
  [summary-text hidden-blocks]
  {:kind :blocks
   ;; No summary at all -- an authored `:::{collapsable}` with no
   ;; `summary=` prop, which `summary` returns nil for -- means there is
   ;; no label to flatten, so the head Para is omitted rather than
   ;; emitted holding a nil `:text`. That nil made the whole lowered
   ;; Document schema-invalid while both emitters rendered it as an
   ;; empty `<summary>`/`\textbf{}`, i.e. degraded silently, which is
   ;; the opposite of this codebase's no-silent-drop convention (found
   ;; by review of `lower-rule`).
   :blocks (into (if summary-text
                   [{:t :para :inlines [{:t :strong :inlines [{:t :str :text summary-text}]}]}]
                   [])
                 hidden-blocks)})

(defn make
  "Builds a schema-valid collapsable `:directive` node (`haselnuss.ast/
  Block`'s `:directive` variant) from `summary-text` (the always-visible
  label, a plain string) and `hidden-blocks` (the collapsed content,
  revealed on toggle -- an ordinary Block vector, `[]` for none), deriving
  this extension's own `:fallback {:kind :blocks}` for static targets
  automatically (sec8.4: \"flatten both parts inline\") so a caller never
  has to hand-construct that half. `attr` (default `{:classes [] :props
  {}}`) has `summary-text` merged into its own `:props[\"summary\"]`.

  Referenceability, corrected twice now (this docstring has overclaimed
  here before -- real bugs caught by review): supplying an `:id` in `attr`
  does two independent things, neither dependent on the other. First,
  `render-html` emits it verbatim as the rendered `<details>` element's own
  `id=\"...\"` HTML attribute, so a plain URL fragment (`#collapse:1`) can
  link straight to it on the `:html` target. Second -- and this is
  *unrelated to* `extension` registering no `:kind` -- `haselnuss.resolver/
  number-document`'s numbering is purely `attr.id`-prefix-driven,
  independent of node type or registry `:kind` (`haselnuss.resolver`'s own
  docstring, TASK-11 AC #4): giving a collapsable's `:id` a
  lexicon-recognized prefix (e.g. `\"fig:tree\"`) numbers and resolves it as
  a first-class cross-reference target directly, exactly like `chart`'s own
  sec8.4 mechanism (`haselnuss.registry`'s own docstring: chart numbers
  \"purely through its own `#fig:...` id, with no registry involvement\") --
  no enclosing wrapper node needed. Only an id with *no* recognized-kind
  prefix (e.g. a bare `\"collapse:1\"`, used purely for the HTML anchor
  above) is invisible to numbering and leaves a `:cross-ref` pointing at it
  dangling; that is a property of the id string chosen, not of this
  extension specifically."
  ([summary-text hidden-blocks] (make summary-text hidden-blocks {:classes [] :props {}}))
  ([summary-text hidden-blocks attr]
   {:t :directive
    :name "collapsable"
    :blocks hidden-blocks
    :attr (assoc-in attr [:props "summary"] summary-text)
    :fallback (fallback-for summary-text hidden-blocks)}))

(defn lower-rule
  "This extension's registry `:lower` rule (sec8.3's second branch):
  degrades a collapsable to the same flattened Blocks its own
  `:fallback` carries -- literally the same `fallback-for` output, so
  the two routes can never drift apart.

  Registering it is not redundant with that fallback, though this
  namespace's own docstring claimed so until now. `haselnuss.parser`
  never constructs a `:fallback` field at all (it is parser-inert -- see
  `haselnuss.lower`'s own docstring), so a collapsable an *author* wrote
  in a `.hdoc` file has none: only one built programmatically by `make`
  does. Without this rule, `lower` reaches its fourth branch for every
  authored collapsable on any target but `:html` and aborts the build
  with `::no-representation` -- confirmed by running the real CLI over
  `:::{collapsable summary=Details}` for the `:latex` target.

  A `make`-built directive now takes this rule rather than its own
  `:fallback`, since `lower-directive` tries `:lower` first. That is a
  no-op in output terms, both being `fallback-for`, and the `:fallback`
  stays on the node for any caller that consults it directly.

  One thing the flat splice cannot do is carry the directive's own
  `attr.id`, and sec4.3 gives Para no `Attr` at all, so there is nowhere
  to put it (TASK-51). An id-BEARING collapsable therefore keeps a
  BlockQuote carrying the whole `attr` around its flattened content --
  the same carrier `haselnuss.cli/environment-lower-rule` uses, and for
  the same reason: without it a `@collapse:1` pointing at this node
  resolves to a number the output has no anchor for, silently. An
  id-less one -- the common case, and the only one sec8.4's \"flatten
  both parts inline\" actually describes -- is spliced flat, unchanged.

  Conditional on the id specifically rather than on `attr` being
  non-empty, which is narrower than `environment-lower-rule`'s own rule
  and deliberate (raised by review of TASK-51, which noted that rule had
  been widened to keep `:classes`/`:props` too): those two do change the
  output on the `:html` target, but this rule never runs there --
  `extension` registers a native `:html` renderer that emits both -- and
  on every target it does run for, LaTeX today, neither has any
  representation at all (see `haselnuss.emit.latex`'s own docstring on
  Span). The id is the one field whose loss changes what the output can
  do."
  [directive _target]
  (let [blocks (:blocks (fallback-for (summary directive) (:blocks directive)))]
    (if (get-in directive [:attr :id])
      [{:t :block-quote :attr (:attr directive) :blocks (vec blocks)}]
      blocks)))

(def extension
  "The registry entry for `collapsable`
  (`haselnuss.registry/register`'s own `{:name :kind? :emit? :lower?}`
  shape): a caller registers it via `(registry/register some-registry
  haselnuss.extensions.collapsable/extension)` (or via `register-all`
  alongside other built-ins). Registers an `:html` renderer (the native
  `<details>` widget, `renderer-v2`-marked -- TASK-89's v2 contract) and a
  `:lower` rule (`lower-rule`, the flattened degradation every other
  target gets). No `:trusted?`: every Block/Inline this renderer emits
  goes through ordinary hiccup elements and `ctx`'s own `:render-blocks`,
  never a `[:raw ...]` node, so this extension proves an untrusted one can
  still produce rich structured output -- just never raw, unescaped
  text."
  {:name "collapsable" :emit {:html (registry/renderer-v2 render-html)} :lower lower-rule})

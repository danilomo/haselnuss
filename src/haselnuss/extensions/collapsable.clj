(ns haselnuss.extensions.collapsable
  "The `collapsable` extension (`SPEC.md` sec8.4): the
  reference built-in extension (TASK-18), re-expressing the original
  `Collapsable` React component -- from the TypeScript reader this format
  was extracted from, outside this repository: a `<button>` that toggles a
  hidden `<span>`, driven by two plain strings `text`/`hiddenText` -- as a
  `Directive{name:\"collapsable\"}` (sec8.1) -- SPEC.md's own words: \"the
  simplest instance of the whole mechanism.\" Exercises the extension
  registry (`haselnuss.registry`, TASK-16) and the `lower` pass
  (`haselnuss.lower`, TASK-17) end-to-end on this one real element, proving
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
  `extension`) uses a `(fn [directive target] -> string)` call signature.
  `haselnuss.registry`'s own docstring explicitly leaves a renderer's call
  signature an open, not-yet-decided emitter-core concern (TASK-19/21 are
  still To Do); this is the first concrete instance, chosen to match the
  2-arg shape `haselnuss.lower-test`'s own AC #1 fixtures already exercise
  (`(fn [_node _target] ...)`) rather than `haselnuss.registry-test`'s own
  single-arg example -- neither fixture is authoritative (both are
  placeholder stand-ins, never actually called by `haselnuss.registry`/
  `haselnuss.lower` themselves), so this namespace's own choice is offered
  as a reference for TASK-19/21 to adopt, adjust, or formalize once the
  real emitter core exists, not a settled contract. It produces a real,
  JS-free interactive disclosure widget using HTML5's native `<details>`/
  `<summary>` elements -- strictly more accessible than the original
  component's `useState`+`onClick` button, and requires no client-side
  scripting story this codebase has not built.

  Scope limit (flagged explicitly, mirroring this codebase's established
  precedent of naming such limits rather than leaving them to be discovered
  by accident): `render-html`'s own Block/Inline-to-HTML conversion
  (turning the hidden `:blocks`' content into markup) is intentionally
  minimal -- covers only Para blocks and the handful of purely-textual
  Inline variants (`:str`/`:space`/`:soft-break`/`:line-break`/`:emph`/
  `:strong`) a collapsable's own hidden content is realistically expected
  to carry, NOT a general emitter; it raises a descriptive `ex-info`
  (`::unsupported-block`/`::unsupported-inline`) for anything outside that
  set rather than silently mangling or dropping it. TASK-19/20 own building
  the real, comprehensive HTML emitter core; this helper is not meant to be
  reused as one.

  Nested directives, corrected (this namespace previously claimed the
  opposite -- a real bug caught by review): by the time `render-html` runs,
  `haselnuss.lower/lower` has already recursively lowered this directive's
  own `:blocks` -- but `lower-directive`'s own AC #1 branch (`haselnuss.
  lower`) deliberately *keeps* a directive with a native renderer for the
  target as a `:t :directive` node (only recursing into its own nested
  `:blocks`), precisely so a later emitter can dispatch it to that native
  renderer. Since `collapsable` is (so far) this codebase's only built-in
  extension, the one concrete instance of this is a `collapsable` nested
  inside another `collapsable`'s own hidden content -- SPEC.md sec8 names
  no rule forbidding it. `block->html` therefore special-cases a nested
  `:directive` block whose own `:name` is `\"collapsable\"`, dispatching it
  straight back into `render-html` (ordinary same-signature recursion, no
  registry needed for this one case); a nested `:directive` under any
  *other* name is still outside this helper's documented scope and raises
  `::unsupported-block`, same as any other unsupported block type here.
  Generalizing this into a real registry-driven dispatch (so an arbitrary
  native-renderable nested directive resolves correctly, not just
  `collapsable` recognizing itself) is left open pending TASK-19/21's own
  renderer call-signature decision (see above) -- there is only one
  extension in this codebase to test that against today.

  This extension needs no registry `:kind` (collapsable participates in no
  numbering scheme, exactly as the original component never did). It DOES
  need a registry `:lower` rule (`lower-rule`), correcting an earlier
  claim here that its own per-instance `:fallback` alone handled every
  non-`:html` target: `haselnuss.parser` constructs no `:fallback` field
  at all, so an authored collapsable has none, and without the rule
  `lower` aborts the build for every target but `:html` -- confirmed by
  running the real CLI over an authored one for `:latex`."
  (:require [clojure.string :as str]))

(defn escape-html
  "Escapes `&`, `<`, `>`, and `\"` in `s` so plain text is always safe to
  splice directly into HTML markup. Public so a sibling disclosure
  extension built on `render-details` (`haselnuss.extensions.
  small-collapsable`) escapes exactly the same set rather than growing a
  second, subtly different copy."
  [s]
  (-> s
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(declare inlines->html)

(defn- inline->html
  "Renders one Inline (`haselnuss.ast/Inline`) to an HTML fragment. Scope
  limit (see namespace docstring): only the purely-textual variants a
  collapsable's own hidden content is expected to carry are supported;
  anything else raises `ex-info` (`:type ::unsupported-inline`) instead of
  silently dropping or mangling it."
  [inline]
  (case (:t inline)
    :str (escape-html (:text inline))
    :space " "
    :soft-break "\n"
    :line-break "<br/>"
    :emph (str "<em>" (inlines->html (:inlines inline)) "</em>")
    :strong (str "<strong>" (inlines->html (:inlines inline)) "</strong>")
    (throw (ex-info
            (str "collapsable's html renderer does not support inline type "
                 (pr-str (:t inline)) " in hidden content")
            {:type ::unsupported-inline :inline inline}))))

(defn- inlines->html
  "`inline->html` mapped and concatenated over every Inline in `inlines`."
  [inlines]
  (apply str (map inline->html inlines)))

(declare render-html)

(def default-nested-renderers
  "The nested-directive dispatch `blocks->html` uses when a caller names
  none: this extension recognizing itself, exactly the one case the
  namespace docstring's \"Nested directives, corrected\" section describes.
  A map of directive `:name` -> a `(fn [directive target] -> string)`
  renderer, which is all the dispatch a registry-less helper can do; a
  sibling extension passes its own map (see `render-details`) so the two
  disclosure directives can nest inside each other without either
  namespace requiring the other."
  {"collapsable" (fn [directive target] (render-html directive target))})

(defn- block->html
  "Renders one Block (`haselnuss.ast/Block`) to an HTML fragment. Scope
  limit (see namespace docstring): `:para` is always supported; a nested
  `:directive` is supported only when `nested` (a `:name` -> renderer map,
  `default-nested-renderers` by default) has an entry for its own `:name`
  -- since that is the only native dispatch this namespace has without a
  registry, see the namespace docstring's \"Nested directives, corrected\"
  section; anything else raises `ex-info` (`:type ::unsupported-block`)."
  [nested block]
  (case (:t block)
    :para (str "<p>" (inlines->html (:inlines block)) "</p>")
    :directive
    (if-let [render (get nested (:name block))]
      (render block :html)
      (throw (ex-info
              (str "collapsable's html renderer does not support nested directive \""
                   (:name block) "\" in hidden content")
              {:type ::unsupported-block :block block})))
    (throw (ex-info
            (str "collapsable's html renderer does not support block type "
                 (pr-str (:t block)) " in hidden content")
            {:type ::unsupported-block :block block}))))

(defn blocks->html
  "`block->html` mapped and concatenated over every Block in `blocks`.
  `nested` is the nested-directive dispatch map documented on
  `block->html`, defaulting to `default-nested-renderers`. Public for the
  same reason `escape-html` is: a sibling disclosure extension renders its
  own hidden content through this one helper, so the supported-content
  scope limit stays a single definition (and a single pair of
  `::unsupported-block`/`::unsupported-inline` error types) rather than
  drifting between copies."
  ([blocks] (blocks->html blocks default-nested-renderers))
  ([blocks nested] (apply str (map #(block->html nested %) blocks))))

(defn summary
  "The always-visible label of collapsable `directive` (its own
  `attr.props[\"summary\"]`), or nil if unset."
  [directive]
  (get-in directive [:attr :props "summary"]))

(defn- id-attr
  "A ` id=\"...\"` HTML attribute fragment (escaped) for `directive`'s own
  `attr.id`, or `\"\"` if unset -- this is what makes a rendered `<details>`
  element anchorable via a URL fragment (a real bug fix: this attribute
  used to be silently dropped regardless of `attr.id`, per `make`'s own
  docstring, which see for what this id does -- and does not -- make a
  collapsable referenceable for)."
  [directive]
  (if-let [id (get-in directive [:attr :id])]
    (str " id=\"" (escape-html id) "\"")
    ""))

(defn- class-attr
  "The rendered `<details>` element's `class=\"...\"` HTML attribute
  (escaped): always includes `base-class` (the owning extension's own
  styling hook -- `collapsable` here), plus any caller-supplied
  `attr.classes` from `directive` appended in order -- a real bug fix:
  `attr.classes` used to be silently dropped entirely, only the hardcoded
  `collapsable` literal ever reached the output, same class of bug as
  `attr.id` before it."
  [base-class directive]
  (->> (cons base-class (get-in directive [:attr :classes]))
       (map escape-html)
       (str/join " ")))

(defn render-details
  "Renders `directive` as an HTML5 `<details>`/`<summary>` disclosure --
  the shared body of every disclosure extension built on this namespace.
  `opts` keys:

  - `:base-class` -- the class always present on the `<details>` element,
    alongside any authored `attr.classes` (`class-attr`).
  - `:body-class` -- when set, the revealed `:blocks` are wrapped in a
    `<div class=\"...\">` carrying it; when nil they are emitted directly
    as the `<details>` element's own children, which is what `collapsable`
    has always done and what keeps its output byte-identical. The wrapper
    exists because a *block* box is what gives an inline-rendered
    disclosure a line break before and after its revealed content, and CSS
    cannot introduce a box that the markup does not have (see
    `haselnuss.extensions.small-collapsable`).
  - `:nested` -- the nested-directive dispatch map `blocks->html`
    documents, defaulting to `default-nested-renderers`.

  `directive`'s own `attr.id`, when set, becomes the element's `id=\"...\"`
  (`id-attr`), which is what makes a rendered disclosure anchorable by URL
  fragment."
  [{:keys [base-class body-class nested] :or {nested default-nested-renderers}} directive]
  (let [body (blocks->html (:blocks directive) nested)]
    (str "<details" (id-attr directive) " class=\"" (class-attr base-class directive) "\">"
         "<summary>" (escape-html (or (summary directive) "")) "</summary>"
         (if body-class
           (str "<div class=\"" (escape-html body-class) "\">" body "</div>")
           body)
         "</details>")))

(defn render-html
  "This extension's native `:html` renderer (registered on `extension`):
  renders collapsable `directive` as an interactive HTML5 `<details>`/
  `<summary>` disclosure -- `summary` is always visible; `directive`'s own
  `:blocks` (the collapsed content) are revealed natively by the browser on
  toggle, no JavaScript required. `directive`'s own `attr.id`, when set, is
  emitted as the `<details>` element's own `id=\"...\"` HTML attribute
  (`id-attr`) -- see `make`'s docstring for exactly what this id does, and
  does not, make a collapsable referenceable for. `directive`'s own
  `attr.classes`, when set, are appended to the element's `class=\"...\"`
  attribute alongside the always-present `collapsable` class (`class-attr`).
  `target` is accepted, per this namespace's own documented
  `(fn [directive target] ...)` call-signature choice, but unused: a
  `:html`-registered renderer is only ever invoked for the `:html` target."
  [directive _target]
  (render-details {:base-class "collapsable"} directive))

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
  `<details>` widget) and a `:lower` rule (`lower-rule`, the flattened
  degradation every other target gets)."
  {:name "collapsable" :emit {:html render-html} :lower lower-rule})

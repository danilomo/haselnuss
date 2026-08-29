(ns haselnuss.extensions.small-collapsable
  "The `small-collapsable` extension: a disclosure that reads as a word in
  running text rather than as a block of its own.

  Semantically it is `haselnuss.extensions.collapsable` -- same
  `attr.props[\"summary\"]` label, same `:blocks` hidden content, same
  `:fallback {:kind :blocks}` degradation -- and it deliberately shares
  that namespace's rendering helpers (`collapsable/render-details`,
  `collapsable/fallback-for`) rather than copying them, so the two stay
  one definition.

  What differs is layout, and it is a two-state contract:

  - *Collapsed*, it does not break the line. Several in a row -- the case
    this was built for, a run of Bible references at the end of a
    catechism answer -- flow and wrap like words in a sentence.
  - *Expanded*, the revealed content is a block: the line breaks before it
    and again after it, and following inline content resumes on a new
    line.

  Both states come from ordinary CSS on ordinary markup, no scripting:
  `details.small-collapsable{display:inline}` gives the collapsed state,
  and the revealed content's own `<div class=\"small-collapsable-body\">`
  (a *block* box inside that inline box) gives the expanded one, since
  CSS's block-in-inline rule splits the inline box around it -- which is
  exactly a line break before and after. `haselnuss.emit.html`'s
  `default-stylesheet` carries both rules.

  That wrapper `<div>` is the whole reason `render-details` takes a
  `:body-class` at all: `collapsable` passes none and keeps emitting its
  revealed blocks as direct children of `<details>`, unchanged. A
  stylesheet alone could not have done this -- CSS can restyle a box but
  cannot introduce one the markup never had, and without the wrapper the
  revealed content would inherit the inline formatting context and run on
  in the same line.

  Nesting (TASK-89): a directive nested inside this extension's own
  hidden content -- `small-collapsable` recursively, a plain `collapsable`,
  or any OTHER natively-rendered directive -- is rendered through `ctx`'s
  own `:render-blocks` callback, the ordinary core emitter dispatch, so it
  resolves through the registry exactly the way a top-level one does; this
  namespace (like `collapsable`) no longer hand-maintains a dispatch map
  of directive names it happens to know about. Before TASK-89, the OLD
  `(fn [directive target] -> string)` registry contract gave a renderer
  no way to reach the real emitter's own `render-blocks` at all, so this
  namespace's own dispatch map recognized `small-collapsable`/
  `collapsable` by name and nothing else -- in particular, the reverse
  case, a `small-collapsable` nested inside a plain `collapsable`, was
  NOT supported (closing it would have meant `collapsable` requiring this
  namespace, a require cycle). `ctx`'s own `:render-blocks` closes that
  gap for free: neither namespace requires the other, and either can now
  nest inside the other, or inside any third native renderer, uniformly.

  Like `collapsable`, this extension registers no `:kind` (it takes part
  in no numbering scheme) and does register a `:lower` rule, without which
  `haselnuss.lower` aborts the build on every non-`:html` target for an
  *authored* directive -- one parsed from a `.hdoc` file never carries the
  `:fallback` that only `make` attaches."
  (:require [clojure.string :as str]
            [haselnuss.extensions.collapsable :as collapsable]
            [haselnuss.registry :as registry]))

(defn label
  "`directive`'s own optional `attr.props[\"label\"]` -- a plain-text
  marker rendered immediately ahead of the disclosure, in the same inline
  flow, or nil if unset. It exists because the thing that most often
  precedes a run of these is a marker naming the run (a footnote number
  like `(1)` ahead of the proof texts it cites), and a marker written as
  an ordinary paragraph instead would be a *block* box -- which breaks the
  line and so undoes exactly the single-flowing-paragraph layout this
  element exists to produce. Unlike `summary` it is inert: not part of the
  clickable disclosure, so a marker never reads as one of the labels it
  introduces."
  [directive]
  (get-in directive [:attr :props "label"]))

(defn render-html
  "This extension's native `:html` renderer (registered on `extension`,
  TASK-89's v2 contract: `(fn [directive ctx] -> data)`, `renderer-v2`-
  marked): renders `directive` as an HTML5 `<details>`/`<summary>`
  disclosure whose revealed `:blocks` are wrapped in a `[:div {:class
  \"small-collapsable-body\"}]`. That wrapper, plus the `small-collapsable`
  class on the `<details>` element itself, are the two hooks the default
  stylesheet's inline-when-collapsed / block-when-expanded rules attach to
  (see the namespace docstring). `directive`'s own `attr.id` and
  `attr.classes` are carried through exactly as
  `collapsable/render-details` carries them.

  An optional `label` (which see) is emitted as a `[:span {:class
  \"small-collapsable-label\"}]` immediately BEFORE the `<details>`
  element rather than inside it: content before `<summary>` would not be
  valid inside `<details>`, and a span is inline-level, so the marker
  joins the same flow the disclosures themselves lay out in. The two are
  returned as a fragment (a vector whose first child is not itself a tag
  keyword -- see `haselnuss.emit.html/render-data`'s own docstring on
  that vocabulary) rather than one wrapping element, since there is no
  single element both belong inside."
  [directive ctx]
  (let [details (collapsable/render-details {:base-class "small-collapsable"
                                             :body-class "small-collapsable-body"}
                                            directive ctx)]
    (if-let [marker (label directive)]
      [[:span {:class "small-collapsable-label"} marker] details]
      details)))

(defn- flattened-head
  "The head a static target prints ahead of the no-longer-hidden content:
  `label` and `summary` space-joined, either absent one dropped, and nil
  when both are (which `collapsable/fallback-for` reads as \"emit no head
  Para at all\"). Carrying the marker here is what stops it being silently
  lost off the `:html` target: the HTML renderer gives it its own inline
  span, and nothing else in the flattened form would hold it."
  [directive]
  (not-empty (str/join " " (remove nil? [(label directive)
                                         (collapsable/summary directive)]))))

(defn make
  "Builds a schema-valid `small-collapsable` `:directive` node from
  `summary-text` (the always-visible label) and `hidden-blocks` (the
  revealed content, `[]` for none), deriving the same
  `:fallback {:kind :blocks}` `collapsable/make` derives -- the two
  degrade identically, since the distinction between them is one of HTML
  layout only and a static target collapses nothing either way.

  `attr` (default `{:classes [] :props {}}`) has `summary-text` merged
  into its own `:props[\"summary\"]`. An `:id` supplied in `attr` becomes
  the rendered `<details>` element's own `id=\"...\"`; see
  `collapsable/make`'s docstring for what that id does, and does not, make
  a disclosure referenceable for -- it applies here unchanged."
  ([summary-text hidden-blocks] (make summary-text hidden-blocks {:classes [] :props {}}))
  ([summary-text hidden-blocks attr]
   (let [attr' (assoc-in attr [:props "summary"] summary-text)]
     {:t :directive
      :name "small-collapsable"
      :blocks hidden-blocks
      :attr attr'
      :fallback (collapsable/fallback-for (flattened-head {:attr attr'}) hidden-blocks)})))

(defn lower-rule
  "This extension's registry `:lower` rule: degrades a `small-collapsable`
  to the same flattened Blocks `collapsable` degrades to -- the label as
  an emphasized Para ahead of the no-longer-hidden content. Registering it
  is what keeps a non-`:html` build from aborting with
  `::lower/no-representation` on an authored directive, which carries no
  `:fallback` of its own (see the namespace docstring).

  An id-bearing one keeps a BlockQuote carrier around that content, for
  the reason `collapsable/lower-rule` records in full (TASK-51): the flat
  splice has nowhere to put the directive's own `attr.id`, and a
  reference to it would then point at nothing in the emitted LaTeX."
  [directive _target]
  (let [blocks (:blocks (collapsable/fallback-for (flattened-head directive)
                                                  (:blocks directive)))]
    (if (get-in directive [:attr :id])
      [{:t :block-quote :attr (:attr directive) :blocks (vec blocks)}]
      blocks)))

(def extension
  "The registry entry for `small-collapsable`
  (`haselnuss.registry/register`'s own `{:name :kind? :emit? :lower?}`
  shape): an `:html` renderer (the inline-flowing `<details>` widget,
  `renderer-v2`-marked -- TASK-89's v2 contract) and a `:lower` rule (the
  flattened degradation every other target gets). Registered alongside
  `collapsable/extension` in `haselnuss.cli`. No `:trusted?`, for the same
  reason `collapsable/extension` has none: every Block/Inline this
  renderer emits goes through ordinary hiccup elements and `ctx`'s own
  `:render-blocks`, never a `[:raw ...]` node."
  {:name "small-collapsable" :emit {:html (registry/renderer-v2 render-html)} :lower lower-rule})

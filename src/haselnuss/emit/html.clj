(ns haselnuss.emit.html
  "The HTML emitter core (TASK-19, extended by TASK-20): a visitor over a
  resolved-and-lowered `haselnuss.ast/Document`, producing one complete,
  valid HTML document.

  Scope: Section, Para, List, CodeBlock, BlockQuote, the purely-textual/
  recursive Inline nodes (`:emph`/`:strong`/`:strike`/`:small-caps`/`:sub`/
  `:sup`/`:code`), Link, Image, Span and footnotes (`:note`) (TASK-19);
  Figure, Table, MathInline/MathBlock, resolved CrossRef, Cite and the
  generated bibliography section (TASK-20, this namespace's own
  follow-up, see below for each one's own rendering/opts contract).
  `:thematic-break` renders as `<hr/>` (TASK-37 AC #1). An `:include`
  that reaches this emitter has no target content to render, and raises
  a dedicated `::unresolved-include` (TASK-37 AC #3's own \"or raises a
  clear, documented diagnostic\" branch) naming the `:src` and the real
  cause, rather than the generic `::unsupported-block` it used to share
  with genuinely out-of-scope node types. Since TASK-38 that is a
  should-not-happen for a document converted through `haselnuss.cli`,
  which always runs `haselnuss.resolver/expand-includes` -- expansion
  splices the file in, or reports a diagnostic and drops the Include.
  The two routes that still reach it are the ones that skip that pass:
  `haselnuss.json/json->ast`, and a caller invoking `resolve-document`
  with no `:includes` loader. Every other unhandled node type still raises a documented
  `ex-info` (`::unsupported-block`/`::unsupported-inline`) rather than
  being silently dropped or mangled, mirroring this codebase's
  established no-silent-drop convention (`haselnuss.parser`'s
  `::malformed-directive`, `haselnuss.lower`'s `::no-representation`,
  `haselnuss.extensions.collapsable`'s own `::unsupported-block`/
  `::unsupported-inline`).

  `emit-document`'s `opts` (TASK-20 additions, alongside TASK-19's own
  `:registry`): `:labels` -- a `haselnuss.resolver/number-document` label
  table, used to print a Figure/Table's own resolver-computed number
  ahead of its caption (AC #1). Not stamped onto the AST anywhere (per
  `number-document`'s own docstring, later passes \"consult this table
  directly rather than fields stamped onto the AST\"), so a caller re-runs
  `number-document` over the fully resolved document (the same pattern
  `derive-toc`'s own default arity uses) and passes the result straight
  through. `:bibliography-id`/`:ordered-keys` -- `haselnuss.resolver/
  resolve-citations`'s (and `resolve-document`'s) own same-named TASK-20
  additions: the generated bibliography Section's own `attr.id`, and
  every distinct resolvable citation key in the exact order its List
  renders them. Together these let a resolved Cite link to its own
  bibliography-list entry (AC #4) -- data that exists nowhere else by the
  time a document reaches this namespace, since `bibliography-section`'s
  generated Paras carry no key/id of their own to recover it from later
  (Para has no `Attr` field at all, sec4.3).

  Math (AC #2): `:math-inline`/`:math-block` render their raw TeX between
  MathJax's own default (zero-config) delimiters -- `\\(...\\)` for
  inline, `\\[...\\]` for display -- inside a `<span class=\"math
  inline\">`/`<div class=\"math display\">` wrapper (the same convention
  Pandoc's own `--mathjax` output uses), with the raw TeX HTML-escaped so
  a `<`/`>`/`&` in it survives as ordinary text through the browser's own
  entity decoding rather than breaking the surrounding markup.
  A numbered `:math-block` -- one whose `attr.id` has an entry in the
  `:labels` opt -- also prints its own number in a trailing `<span
  class=\"math-number\">`, so the equation shows the number every
  reference to it prints, as it does in LaTeX (TASK-27's cross-format
  invariant; see `render-math-block`).

  Styling (TASK-43): `emit-document` inlines a default stylesheet as a
  `<style>` in `<head>`, so its output is legible with no second file
  to keep alongside it. The `:stylesheet` opt takes `:default` (the
  default), `:none` to omit it, or a CSS string to replace it -- see
  `default-stylesheet` for what it targets and why, and
  `stylesheet-tag` for the opt itself. A handful of per-node inline
  styles survive it on purpose (a cell's `text-align`, a `col`'s
  `width`, the equation number's `float`, `:small-caps`' own
  `font-variant`): the first two are authored values no sheet can
  know, and the last two are cases where losing the style would cost
  meaning or produce broken output rather than an unstyled one.

  `emit-document` always includes one async MathJax v3 CDN `<script>` tag
  in `<head>` (unconditionally, not only when `document` actually
  contains math -- detecting that would need either duplicating this
  namespace's own render-time tree walk in a second, parallel checker, or
  threading yet another accumulator through every render function
  alongside `footnotes`/`ctx`, for a savings of one small, harmless
  `<script>` tag on math-free documents) so formulas render correctly in
  a real browser without any further reader setup.

  CrossRef (AC #3): a `:cross-ref` inline reaching this namespace is
  expected to already be resolved (`haselnuss.resolver/resolve-cross-
  refs` has run) -- its own `:target`/`:text` are rendered directly,
  never re-resolved here. A non-nil `:target` becomes `<a
  href=\"#target\">text</a>`; a dangling reference (`:target nil`, the
  resolver's own `\"??\"` placeholder in `:text`) renders as that
  placeholder text alone, with no link to give it (nothing to link to).

  Cite (AC #4): a `:cite` inline's already-style-formatted `:text`
  (`resolve-citations`) renders as-is. When `:bibliography-id`/
  `:citation-positions` (the latter built from `opts`' `:ordered-keys`)
  resolve at least one of the Cite's own `:items`' keys, the WHOLE
  rendered text is wrapped in one link to that *first* resolvable item's
  own bibliography-list entry -- a deliberate, documented simplification
  for a Cite naming more than one source: `resolve-citations`'s own
  `:text` has already flattened every item's core text, brackets, and
  \"; \" separators together into one opaque Inline vector by the time it
  reaches here (see this namespace's own `resolve-citations` docstring
  reference above), so there is no structural seam left to link each
  item independently to a *different* target. The generated bibliography
  Section itself (its own `attr.id` matching `:bibliography-id`) tags
  each of its direct List's `<li>` elements `id=\"{bibliography-id}-{i}\"`
  (1-based, matching `:ordered-keys`'s own render order by construction)
  so those links resolve to something real.

  Directive dispatch: a `:directive` Block surviving `haselnuss.lower/
  lower` for the `:html` target always carries a native `:html` renderer
  in the registry (that is exactly what `lower`'s own AC #1 branch
  guarantees -- see its docstring) -- calling it is *this* task's job, not
  a later one: `haselnuss.registry`'s own docstring says so explicitly
  (\"calling `:emit` is the not-yet-built emitter core's job, TASK-19/21\").
  `render-directive` therefore looks the directive's own `:name` up in an
  optional `:registry` passed via `emit-document`'s `opts` and calls its
  `:html` renderer with the `(fn [directive target] -> string)` signature
  `haselnuss.extensions.collapsable` already established as a reference
  (TASK-18) -- treating the returned string as an already-complete,
  opaque HTML fragment (a native renderer's own nested content, if any, is
  its own concern; it is not walked back into this namespace's footnote
  accumulator, since nothing in the registry contract gives an extension
  access to that state). No registry, or no renderer registered for the
  directive's name, is the same \"no representation\" failure as any other
  unsupported block: `::unsupported-block`, naming the directive.

  Footnote numbering (AC #3): a `:note` Inline (`haselnuss.ast`'s own
  footnote representation -- the resolved definition's Blocks live
  directly on the marker node, sec4.4/`haselnuss.parser`'s own Footnote
  handling) carries no label at the AST level, and a source label can
  expand into several independent `:note` nodes (one full copy per
  reference -- `haselnuss.parser`'s own `footnote-test`/cyclic-footnote
  tests document this precedent). Numbers are therefore assigned purely
  by marker-encounter order during this namespace's own document-order
  walk, not by any label: every render function that can reach a `:note`
  threads a `footnotes` accumulator (a vector of `{:number :html}`, in
  the order numbers were assigned) alongside a `ctx` map (`:registry`,
  plus TASK-20's own `:labels`/`:bibliography-id`/`:citation-positions`,
  see above) through render calls, so `emit-document` can render
  the accumulated footnote content into a trailing footnote list once the
  whole body has been walked. A `:note`'s own number is reserved (an
  `{:number n}` placeholder is `conj`ed onto the accumulator) *before*
  its own `:blocks` are rendered, so a footnote nested inside another
  footnote's body -- SPEC names no rule forbidding it -- still gets a
  higher number than its enclosing one, matching encounter order, instead
  of corrupting the outer entry's own position."
  (:require [clojure.string :as str]
            [haselnuss.extensions.derived-lists :as derived-lists]
            [haselnuss.extensions.front-matter :as front-matter]
            [haselnuss.registry :as registry]))

(defn- escape-html
  "Escapes `&`, `<`, `>`, and `\"` in `s` so plain text is always safe to
  splice directly into HTML markup or into a double-quoted HTML attribute
  value (mirrors `haselnuss.extensions.collapsable`'s own `escape-html`)."
  [s]
  (-> s
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- prop-attrs
  "One ` escaped-key=\"escaped value\"` HTML attribute fragment per entry
  in `props` (an Attr's own `:props`, sec4.1's open-ended string-keyed
  bag), concatenated together. Each entry passes through directly as a
  literal HTML attribute name/value pair -- e.g. a `.note lang=en` Attr's
  `{\"lang\" \"en\"}` becomes a real `lang=\"en\"` HTML attribute -- the
  simplest reading of `props` consistent with how `parser_test.clj`'s own
  fixtures use it. Both the key *and* the value are escaped: today's
  `haselnuss.parser` attribute-key syntax happens to reject a `\"` in a
  key, but `haselnuss.ast`'s own schema (`:props [:map-of :string
  :string]`) places no such restriction, and SPEC.md sec11 names the JSON
  AST as an interchange format other tools may produce directly,
  bypassing the parser's key validation entirely -- so this emitter, a
  general visitor over any resolved-and-lowered Document, cannot rely on
  an assumption that belongs to a different namespace. Leaving the key
  unescaped would let a key like `\"x\\\" onmouseover=\\\"alert(1)\"`
  break out of the attribute list."
  [props]
  (apply str (map (fn [[k v]] (str " " (escape-html k) "=\"" (escape-html v) "\"")) props)))

(defn- attr-attrs
  "Every HTML attribute fragment for Attr `attr` (sec4.1: `:id`/
  `:classes`/`:props`): an `id=\"...\"` fragment when `:id` is present, a
  `class=\"...\"` fragment when `:classes` is non-empty, then one fragment
  per `:props` entry (`prop-attrs`) -- concatenated in that order, or
  `\"\"` when `attr` carries none of the three."
  [{:keys [id classes props]}]
  (str (when id (str " id=\"" (escape-html id) "\""))
       (when (seq classes) (str " class=\"" (escape-html (str/join " " classes)) "\""))
       (prop-attrs props)))

(def ^:private image-style-props
  "The Image sizing props HTML expresses as CSS rather than as literal
  attributes (TASK-60), each mapped to `{:css :percent}` -- the CSS
  property it becomes, and how a percentage written for it is
  converted.

  `height` is a length, and HTML's own `height` attribute takes a bare
  pixel count -- so `height=4cm` as an attribute is not merely
  unhonoured, it is invalid. As a CSS `height` it means exactly what it
  means in LaTeX. A PERCENTAGE height becomes `vh`, not `%`: LaTeX reads
  it against `\\textheight`, and a CSS percentage height resolves
  against the containing block, which for an image in normal flow has no
  definite height and so silently computes to `auto`. Half the viewport
  is the screen's own answer to half the text height, and unlike `%` it
  actually does something.

  DECISION, `scale`. It is a multiple of the image's NATURAL size, and
  CSS has no intrinsic-relative length: a percentage width is relative
  to the CONTAINER, which is a different quantity that happens to
  coincide for one image size, and `transform: scale()` resizes the
  painted result without reflowing anything around it, so the text would
  keep the space the unscaled image occupied. Both would make the two
  targets disagree about how large a figure is, which is precisely what
  this task forbids.

  `zoom` is the one CSS property that is the same operation: a
  multiplier on the used size that DOES reflow. It is now specified
  (CSS Viewport) and supported by every current engine, and its
  degradation in an engine without it is the honest one -- the image
  renders at its natural size, which is what `\\includegraphics` with no
  options does too. A percentage `scale` is normalized to the same bare
  multiplier LaTeX gets, so the two outputs read alike.

  The alternative was to read the image file for its intrinsic
  dimensions and emit a computed width. Rejected structurally, not on
  taste: both emitters are pure functions over the AST that never open
  the file a `src` names (an Image's `:src` is passed through verbatim
  in both), and making HTML emission depend on the filesystem to size
  one prop is a far larger change than the prop is worth.

  `width` is deliberately NOT here. It has been a literal HTML `width`
  attribute since TASK-22 and this task's own AC #3 puts its behaviour
  out of scope; moving it would change output the committed example
  document and existing fixtures depend on. Worth knowing rather than
  discovering: HTML's `width` attribute takes a bare pixel count, so a
  browser does not honour the `50%` a document writes there, while the
  LaTeX side renders it exactly. That divergence predates this task and
  is the one place these two targets still describe different sizes."
  {"height" {:css "height" :percent (fn [n] (str n "vh"))}
   "scale" {:css "zoom" :percent (fn [n] (str (/ (Double/parseDouble n) 100.0)))}})

(defn- image-style
  "The CSS declaration Image sizing prop `prop` contributes for authored
  `value`, or nil when `value` is blank.

  Blank is ABSENT, matching the LaTeX side, where a bare `height=` is an
  illegal option that fails the compile -- a document must not build on
  one target and break on the other over the same empty attribute.

  A percentage is rewritten by that prop's own `:percent` conversion
  (see `image-style-props`) rather than passed through, and the two
  conversions genuinely differ: `50%` of the text height is `50vh`,
  because a `vh` is already one percent of the viewport, while a `scale`
  of `50%` is the bare multiplier `0.5`. Passing the percentage through
  for either would have described a different size from the one the
  LaTeX output describes."
  [prop value]
  (when-let [value (not-empty (str/trim (str value)))]
    (let [{:keys [css percent]} (get image-style-props prop)]
      (str css ": "
           ;; The same permissive percentage shape the LaTeX side accepts
           ;; (an optional sign, a leading `.` with no integer part), so
           ;; one authored value cannot mean 0.5 in print and something
           ;; else on screen.
           (if-let [[_ n] (re-matches #"([+-]?(?:\d+(?:\.\d+)?|\.\d+))%" value)]
             ;; The raw matched digits, not a parsed Double: `50%`
             ;; becomes `50vh`, and printing a parsed 50.0 there would
             ;; have written `50.0vh` for no reason. The one conversion
             ;; that needs arithmetic does its own parsing.
             (percent n)
             value)))))

(defn- image-attr
  "Image Attr `attr` with its CSS-expressed sizing props
  (`image-style-props`) moved out of `:props` and into an inline
  `style`, merged ahead of any authored `style` prop so an author who
  writes one still wins.

  Moved rather than copied, and moved whether or not they contribute a
  declaration: left in `:props` they would ALSO reach `prop-attrs`,
  which renders every prop as a literal HTML attribute -- and
  `scale=\"0.55\"` is not an HTML attribute at all, while
  `height=\"4cm\"` is an invalid value for one. That holds for a blank
  value too, which contributes no declaration but is no more an HTML
  attribute for being empty."
  [attr]
  (let [props (:props attr)
        styled (keep (fn [prop] (image-style prop (get props prop)))
                     (sort (keys image-style-props)))]
    (if (some #(contains? props %) (keys image-style-props))
      (-> attr
          (update :props #(apply dissoc % (keys image-style-props)))
          (cond-> (seq styled)
            (update :props update "style"
                    (fn [authored]
                      (str/join "; " (cond-> (vec styled) authored (conj authored)))))))
      attr)))

(declare render-inlines)

(defn- render-wrapped
  "Renders `inline`'s own `:inlines` through `render-inlines`, then wraps
  the result between `open` and `close` tag fragments -- the shared shape
  behind every purely-recursive Inline variant (`:emph`/`:strong`/
  `:strike`/`:small-caps`/`:sub`/`:sup`/`:span`)."
  [ctx inline footnotes open close]
  (let [[html footnotes'] (render-inlines ctx (:inlines inline) footnotes)]
    [(str open html close) footnotes']))

(declare render-blocks)

(defn- render-note
  "Renders footnote marker `note` (an Inline `:note`, see this namespace's
  own docstring): reserves the next sequential footnote number `n`
  *before* rendering `note`'s own `:blocks` (so a nested footnote inside
  them still gets a strictly-later number), then fills that reservation
  in with the rendered content once available. Returns `[marker-html
  footnotes']`, where `marker-html` is the inline `<sup>`/`<a>` reference
  linking to the footnote list entry `emit-document` will render at
  `#fn{n}`."
  [ctx note footnotes]
  (let [n (inc (count footnotes))
        marker (str "<sup id=\"fnref" n "\"><a href=\"#fn" n "\">" n "</a></sup>")
        reserved (conj footnotes {:number n :html nil})
        [content-html footnotes'] (render-blocks ctx (:blocks note) reserved)
        filled (mapv (fn [entry] (if (= n (:number entry)) (assoc entry :html content-html) entry))
                     footnotes')]
    [marker filled]))

(defn- render-math-inline
  "Renders MathInline `inline` (sec4.4: `:tex`) as `<span class=\"math
  inline\">\\(escaped tex\\)</span>` -- see this namespace's own docstring
  for why `\\(...\\)` (MathJax's own zero-config inline delimiter) and why
  `tex` is HTML-escaped."
  [{:keys [tex]}]
  (str "<span class=\"math inline\">\\(" (escape-html tex) "\\)</span>"))

(defn- render-cross-ref
  "Renders resolved CrossRef `cross-ref` (sec4.4: `:label`/`:target?`/
  `:text?`, annotated in place by `haselnuss.resolver/resolve-cross-refs`
  -- see this namespace's own docstring). `:target` present ->
  `<a href=\"#target\">text</a>` (AC #3); `:target` absent/nil (a dangling
  reference, or a raw unresolved node -- either way there is nothing valid
  to link to) -> the bare escaped `text` alone. `(or (:text cross-ref)
  \"??\")` mirrors `resolve-cross-ref`'s own dangling-reference
  placeholder as a defensive fallback for the latter, unresolved case."
  [{:keys [target text]}]
  (let [text (escape-html (or text "??"))]
    (if target
      (str "<a href=\"#" (escape-html target) "\">" text "</a>")
      text)))

(defn- render-cite
  "Renders resolved Cite `cite` (sec4.4: `:items`/`:text`, formatted in
  place by `haselnuss.resolver/resolve-citations` -- see this namespace's
  own docstring) to `[html footnotes']`: `cite`'s own already-formatted
  `:text` rendered via `render-inlines`, wrapped in one link to the
  bibliography-list entry of the *first* of `cite`'s own `:items` whose
  `:key` resolves in `ctx`'s `:citation-positions` (built from
  `emit-document`'s `:ordered-keys` opt) -- `(some citation-positions
  keys)` returns that first resolvable position, or nil if none resolve
  (an entirely-dangling Cite, or no `:ordered-keys`/`:bibliography-id`
  supplied at all) -- in which case `text-html` is returned unwrapped, per
  this namespace's own docstring on this deliberate simplification for a
  multi-key Cite."
  [{:keys [bibliography-id citation-positions] :as ctx} cite footnotes]
  (let [[text-html footnotes'] (render-inlines ctx (:text cite) footnotes)
        position (some citation-positions (map :key (:items cite)))]
    [(if (and bibliography-id position)
       (str "<a href=\"#" (escape-html bibliography-id) "-" position "\">" text-html "</a>")
       text-html)
     footnotes']))

(defn- render-inline
  "Renders one Inline (`haselnuss.ast/Inline`) to `[html footnotes']`
  (`footnotes` threaded per this namespace's own docstring). Covers this
  task's documented Inline scope; every other variant raises `ex-info`
  (`:type ::unsupported-inline`)."
  [ctx inline footnotes]
  (case (:t inline)
    :str [(escape-html (:text inline)) footnotes]
    :space [" " footnotes]
    :soft-break ["\n" footnotes]
    :line-break ["<br/>" footnotes]
    :emph (render-wrapped ctx inline footnotes "<em>" "</em>")
    :strong (render-wrapped ctx inline footnotes "<strong>" "</strong>")
    ;; A stylistic strikethrough, not a "this was deleted" edit annotation
    ;; -- `<s>` carries presentation semantics, unlike `<del>`.
    :strike (render-wrapped ctx inline footnotes "<s>" "</s>")
    ;; No native HTML small-caps element; an inline `font-variant` style
    ;; renders correctly with no dependency on an external stylesheet.
    :small-caps (render-wrapped ctx inline footnotes
                                "<span style=\"font-variant: small-caps;\">" "</span>")
    :sub (render-wrapped ctx inline footnotes "<sub>" "</sub>")
    :sup (render-wrapped ctx inline footnotes "<sup>" "</sup>")
    :code [(str "<code>" (escape-html (:text inline)) "</code>") footnotes]
    :link
    (render-wrapped ctx inline footnotes
                    (str "<a href=\"" (escape-html (:target inline)) "\""
                         (attr-attrs (:attr inline)) ">")
                    "</a>")
    :image
    [(str "<img src=\"" (escape-html (:src inline)) "\" alt=\"" (escape-html (:alt inline)) "\""
          (attr-attrs (image-attr (:attr inline))) "/>")
     footnotes]
    :span (render-wrapped ctx inline footnotes (str "<span" (attr-attrs (:attr inline)) ">") "</span>")
    :note (render-note ctx inline footnotes)
    :math-inline [(render-math-inline inline) footnotes]
    :cross-ref [(render-cross-ref inline) footnotes]
    :cite (render-cite ctx inline footnotes)
    (throw (ex-info
            (str "html emitter does not support inline type " (pr-str (:t inline)))
            {:type ::unsupported-inline :inline inline}))))

(defn- render-inlines
  "`render-inline` folded over every Inline in `inlines`, in order,
  concatenating rendered HTML and threading `footnotes` throughout."
  [ctx inlines footnotes]
  (reduce (fn [[html footnotes] inline]
            (let [[inline-html footnotes'] (render-inline ctx inline footnotes)]
              [(str html inline-html) footnotes']))
          ["" footnotes]
          inlines))

(defn- heading-tag
  "The `hN` tag name for Section `level`, clamped to HTML5's own `h1`-`h6`
  range (sec4.3 places no upper bound on a Section's `:level`, but HTML
  has no heading element beyond `h6`) -- a level below 1 or above 6 still
  renders, at the nearest valid boundary, rather than producing an
  invalid tag name."
  [level]
  (str "h" (min 6 (max 1 level))))

(defn- section-number-html
  "The number a numbered Section prints in its own heading, as a
  `<span class=\"section-number\">`, or `\"\"` for a Section the
  resolver numbered nothing for (TASK-41).

  DECISION, recorded here because both emitters had to make the same
  one. A numbered Section prints its number, the way a Figure, Table
  and equation already print theirs -- before TASK-41 neither target
  did, so a document could say \"See Section 1\" above a heading
  reading only \"Why hazel\", a reference to a number that appeared
  nowhere.

  What it prints is the bare NUMBER (`1.2`), not the full label text
  (`Section 1.2`). That is what every book and what LaTeX's own
  `\\section` do, and it is why the word is worth keeping in the
  reference and not in the heading: \"Section 1.2\" reads correctly
  mid-sentence and absurdly as a heading.

  An UNNUMBERED Section prints nothing at all, and needs nothing to
  fill a gap: the number is a prefix, not a column, so its absence just
  starts the heading further left. The same shape as LaTeX's
  `\\section*` beside `\\section`, which is exactly what the other
  emitter does with it.

  \"Unnumbered\" means precisely \"absent from `:labels`\" -- what this
  function tests -- not \"has no id\": a Section whose id prefix is not
  a recognized kind at all is absent too, and so is one the caller
  simply passed no labels for. A Section whose prefix IS a recognized
  kind but the wrong one (`{#thm:oops}`) is a fourth case, and it does
  print a number here, its `thm` one, beside references reading
  \"Theorem 1\". That is deliberate rather than special-cased: the
  resolver already emits both a kind-role-mismatch and an
  unnumbered-section-id diagnostic for exactly that shape, so it is a
  warned-about authoring error, not a silent one -- and suppressing the
  number in the heading would make the emitter disagree with the
  reference instead of with the author.

  The generated bibliography Section is the case that occurs without
  anyone authoring it: it carries a generated id -- `sec:bibliography`,
  or `ch:bibliography` in a chaptered document (TASK-63) -- but no entry
  in `:labels`, because `resolve-document` returns the table numbering
  ran on *before* appending it. So it prints no number, in both targets,
  which is also how a bibliography is conventionally set -- and, for the
  same reason, a cross-reference TO it dangles in both targets, which is
  its own gap rather than this function's business."
  [ctx attr]
  (if-let [number (:number (get (:labels ctx) (:id attr)))]
    (str "<span class=\"section-number\">" (escape-html number) "</span> ")
    ""))

(defn- render-list-item
  "Renders one List item's own Block vector `item-blocks` to `[html
  footnotes']`. In a `tight` list (CommonMark's own tight/loose
  distinction, `haselnuss.parser`'s `:tight` verbatim from flexmark's
  `isTight`), CommonMark's actual rule is that *every* `:para` Block
  directly in an item renders its `:inlines` bare, without the `<p>`
  wrapper a loose list's Para would otherwise get -- not just when the
  item consists of exactly one Para. The most common shape this matters
  for is a Para immediately followed by a nested sub-list with no blank
  line, which is still tight (confirmed against flexmark's own reference
  HtmlRenderer -- the library `haselnuss.parser` is built on -- for
  `\"- a\\n  - nested\\n- b\\n\"`: `<li>a<ul><li>nested</li></ul></li>`,
  never `<li><p>a</p>...`). Every direct Block in `item-blocks` is
  therefore rendered individually: a `:para` bare via `render-inlines`
  when `tight`, every other Block (or any Block at all in a loose list)
  through the ordinary `render-blocks` path for that one Block."
  [ctx tight item-blocks footnotes]
  (reduce (fn [[html footnotes] block]
            (let [[block-html footnotes']
                  (if (and tight (= :para (:t block)))
                    (render-inlines ctx (:inlines block) footnotes)
                    (render-blocks ctx [block] footnotes))]
              [(str html block-html) footnotes']))
          ["" footnotes]
          item-blocks))

(defn- render-list
  "Renders List `block` (sec4.3: `:ordered`/`:tight`/`:items`/`:attr`) as
  an `<ol>`/`<ul>` (per `:ordered`), `attr-attrs`'s own fragment for
  `:attr` on that element, one `<li>` per entry in `:items` via
  `render-list-item`.

  TASK-20 addition: when `ctx`'s own `:li-id-prefix` is set (only ever
  true while rendering the generated bibliography Section's own direct
  List, see `render-block`'s own `:section` case), each `<li>` also gets
  `id=\"{prefix}-{i}\"` (1-based `i`, matching `render-cite`'s own
  `:citation-positions` numbering by construction -- see this namespace's
  own docstring). `:li-id-prefix` is stripped from `ctx` before recursing
  into each item's own nested content (`render-list-item`), so a List
  nested inside one bibliography entry never inherits it too."
  [ctx {:keys [ordered tight items attr]} footnotes]
  (let [tag (if ordered "ol" "ul")
        prefix (:li-id-prefix ctx)
        item-ctx (dissoc ctx :li-id-prefix)
        [items-html footnotes']
        (reduce (fn [[html footnotes] [i item-blocks]]
                  (let [[item-html footnotes'] (render-list-item item-ctx tight item-blocks footnotes)
                        li-open (str "<li" (when prefix (str " id=\"" (escape-html prefix) "-" (inc i) "\"")) ">")]
                    [(str html li-open item-html "</li>") footnotes']))
                ["" footnotes]
                (map-indexed vector items))]
    [(str "<" tag (attr-attrs attr) ">" items-html "</" tag ">") footnotes']))

(defn- render-code-block
  "Renders CodeBlock `block` (sec4.3: `:lang?`/`:text`/`:attr`) as
  `<pre attr><code class=\"language-x\">escaped text</code></pre>` -- the
  common Pandoc/highlight.js convention for naming a fenced code block's
  language via a `language-x` class on `<code>`; `attr` (id/classes/
  props) is rendered on the outer `<pre>`. No `:lang` at all omits the
  `class` attribute on `<code>` entirely rather than emitting an empty
  one. Pure: a CodeBlock's own `:text` is never further parsed for
  Inlines, so this needs no `footnotes` accumulator."
  [{:keys [lang text attr]}]
  (str "<pre" (attr-attrs attr) "><code"
       (when lang (str " class=\"language-" (escape-html lang) "\""))
       ">" (escape-html text) "</code></pre>"))

(defn- render-math-block
  "Renders MathBlock `block` (sec4.3: `:tex`/`:attr`) as `<div class=\"math
  display\" ...>\\[escaped tex\\]</div>` -- see this namespace's own
  docstring for why `\\[...\\]` and why `tex` is HTML-escaped. `attr`'s
  `:id`/`:props` render on the outer `<div>` via `attr-attrs`'s own
  fragments; `:classes` (if any) are appended *after* the fixed `\"math
  display\"` pair rather than through `attr-attrs` (which would replace
  it outright), so an authored class never silently drops the marker
  class MathJax-adjacent tooling/stylesheets key off of.

  A numbered equation -- one whose `attr.id` has an entry in `ctx`'s own
  `:labels` -- also prints that number, in a trailing `<span
  class=\"math-number\">`. LaTeX prints it on the equation itself (via
  `\\tag` in computed-numbers mode, or `equation`'s own counter
  natively), so omitting it here left the two targets visibly disagreeing
  about the same document: a reader of the PDF saw \"(1.1)\" beside the
  equation while a reader of the HTML saw nothing, even though a
  cross-reference in both said \"Eq. (1.1)\". That is precisely the
  numbering drift TASK-27's cross-format invariant exists to rule out.
  The bare `:number` in parentheses, not the full `:text`: the label's
  own word (\"Eq.\") belongs in a reference to the equation, not
  stamped on the equation itself, matching LaTeX's own `(1.1)`."
  [ctx {:keys [tex attr]}]
  (let [number (:number (get (:labels ctx) (:id attr)))]
    (str "<div"
         (when-let [id (:id attr)] (str " id=\"" (escape-html id) "\""))
         " class=\"" (escape-html (str/join " " (into ["math" "display"] (:classes attr)))) "\""
         (prop-attrs (:props attr))
         ">"
         ;; The number comes FIRST in source order and floats right, so
         ;; it sits on the equation's own line as LaTeX prints it.
         ;; Placed after the math it landed on the next line at the left
         ;; margin, because MathJax replaces the `\\[...\\]` run with a
         ;; block-level container (seen in a real browser while
         ;; reviewing TASK-29's dogfood output). An inline style rather
         ;; than a stylesheet rule, mirroring this namespace's own
         ;; `:small-caps` precedent. It stayed inline when TASK-43 gave
         ;; the emitter a stylesheet (AC #3), deliberately: the sheet is
         ;; opt-out, and a number that lands mid-equation instead of
         ;; beside it is broken output rather than an unstyled one. The
         ;; sheet's own `.math.display{overflow-x:auto}` contains this
         ;; float rather than replacing it.
         (when number
           (str "<span class=\"math-number\" style=\"float: right;\">("
                (escape-html number) ")</span>"))
         "\\[" (escape-html tex) "\\]"
         "</div>")))

(defn- render-caption
  "Renders a Figure/Table's own resolver-computed number (AC #1) ahead of
  its authored `caption` (an Inline vector, sec4.3), to `[caption-text
  footnotes']`: `\"{label-text}: {caption html}\"` when both are present,
  just `label-text` (escaped) when there is no caption, just the rendered
  caption when there is no `label-text`, or nil (nothing to show at all)
  when neither is present -- the caller (`render-figure`/`render-table`)
  omits its own wrapping `<figcaption>`/`<caption>` element entirely in
  that last case, rather than emitting an empty one. `label-text` is
  `ctx`'s own `:labels` (a `number-document` label table, see this
  namespace's own docstring) entry for `id`'s own `:text` (e.g. \"Figure
  2.3\"), or nil when `id` is nil or unlabeled/unnumbered.

  A SUBLABELED node -- a subfigure panel, whose entry carries a
  `:sublabel` (TASK-56) -- prints `\"(a) {caption}\"` instead: its
  parenthesized letter, and a space rather than a colon. That is
  verbatim what `subcaption` prints inside a LaTeX float for the same
  panel, so the two targets read identically; the panel's full \"Figure
  2.3a\" stays where the resolver put it, in the references that point
  at it, and `haselnuss.emit.latex/caption-command` makes the same
  choice for the same reason."
  [ctx id caption footnotes]
  (let [entry (get (:labels ctx) id)
        sublabel (:sublabel entry)
        label-text (if sublabel (str "(" sublabel ")") (:text entry))
        separator (if sublabel " " ": ")
        [caption-html footnotes'] (render-inlines ctx caption footnotes)]
    [(cond
       (and label-text (seq caption)) (str (escape-html label-text) separator caption-html)
       label-text (escape-html label-text)
       (seq caption) caption-html
       :else nil)
     footnotes']))

(defn- render-figure
  "Renders Figure `block` (sec4.3: `:content`/`:caption`/`:attr`) as
  `<figure attr>{rendered content}<figcaption>{render-caption}</figcaption>
  </figure>` (AC #1) -- `<figcaption>` omitted entirely when
  `render-caption` returns nil (neither a resolved number nor an authored
  caption)."
  [ctx {:keys [content caption attr]} footnotes]
  (let [[content-html footnotes'] (render-blocks ctx [content] footnotes)
        [caption-text footnotes''] (render-caption ctx (:id attr) caption footnotes')]
    [(str "<figure" (attr-attrs attr) ">" content-html
          (when caption-text (str "<figcaption>" caption-text "</figcaption>"))
          "</figure>")
     footnotes'']))

(defn- render-col
  "Renders one Table Col `col` (sec4.3: `:align?`/`:width?`) as `<col/>`,
  with a `width` inline style when `:width` is present -- `:align` is
  deliberately not rendered here (an HTML `<col>`'s own alignment styling
  is not reliably inherited by its column's cells across browsers);
  `render-cell` applies alignment directly on each `<th>`/`<td>` instead,
  the reliable mechanism."
  [{:keys [width]}]
  (str "<col" (when width (str " style=\"width: " (escape-html width) ";\"")) "/>"))

(defn- render-colgroup
  "Renders every Col in Table `colspec` as one `<colgroup>` (`render-col`
  mapped over `colspec`), or `\"\"` when `colspec` is empty -- no empty
  `<colgroup>` clutters a document with no column spec at all."
  [colspec]
  (if (seq colspec)
    (str "<colgroup>" (apply str (map render-col colspec)) "</colgroup>")
    ""))

(defn- cell-align
  "The effective text alignment for a Table Cell at `col-index` (sec4.3):
  `cell`'s own `:align` if present, else the `col-index`-th entry in
  `colspec`'s own `:align` (nil if neither is set, or `col-index` is
  beyond `colspec`'s own length -- a hand-authored/JSON Cell need not
  agree with its own column's `colspec` entry, so this checks the Cell
  first, mirroring `haselnuss.parser`'s own convention of copying the
  column's alignment onto each cell rather than the other way around)."
  [colspec col-index cell]
  (or (:align cell) (:align (nth colspec col-index nil))))

(defn- render-cell
  "Renders Table Cell `cell` (sec4.3: `:blocks`/`:align?`/`:span?`) as one
  `<th>`/`<td>` (`tag`), its own `:blocks` rendered via `render-blocks`; a
  resolved `cell-align` (see above) becomes a `text-align` inline style
  (no native HTML5 alignment attribute exists any more, and an inline
  style needs no external stylesheet dependency, mirroring `:small-caps`'
  own precedent elsewhere in this namespace); `:span` (if present) becomes
  a `colspan` attribute verbatim."
  [ctx tag colspec col-index cell footnotes]
  (let [[html footnotes'] (render-blocks ctx (:blocks cell) footnotes)
        align (cell-align colspec col-index cell)
        span (:span cell)]
    [(str "<" tag
          (when align (str " style=\"text-align: " (name align) ";\""))
          (when span (str " colspan=\"" span "\""))
          ">" html "</" tag ">")
     footnotes']))

(defn- render-row
  "Renders Table Row `row` (sec4.3: `:cells`) as one `<tr>` of `tag`
  cells (`render-cell`), threading a running `col-index` across `:cells`
  -- incremented by each cell's own `:span` (default 1) -- so a later
  cell's effective column-based alignment (`cell-align`) still lines up
  with `colspec` correctly even after an earlier spanning cell."
  [ctx tag colspec row footnotes]
  (let [[html footnotes' _col-index]
        (reduce (fn [[html footnotes col-index] cell]
                  (let [[cell-html footnotes'] (render-cell ctx tag colspec col-index cell footnotes)]
                    [(str html cell-html) footnotes' (+ col-index (or (:span cell) 1))]))
                ["" footnotes 0]
                (:cells row))]
    [(str "<tr>" html "</tr>") footnotes']))

(defn- render-table
  "Renders Table `block` (sec4.3: `:head`/`:rows`/`:caption`/`:colspec`/
  `:attr`) as `<table attr>`, an optional leading `<caption>` (AC #1,
  `render-caption` -- HTML requires `<caption>` to be `<table>`'s first
  child, before `<colgroup>`/`<thead>`/`<tbody>`, omitted entirely when
  `render-caption` returns nil), an optional `<colgroup>` (`render-
  colgroup`), `<thead>` from `:head` (as `<th>` cells), then `<tbody>`
  from `:rows` (as `<td>` cells)."
  [ctx {:keys [head rows caption colspec attr]} footnotes]
  (let [[caption-text footnotes'] (render-caption ctx (:id attr) caption footnotes)
        [head-html footnotes''] (render-row ctx "th" colspec head footnotes')
        [rows-html footnotes''']
        (reduce (fn [[html footnotes] row]
                  (let [[row-html footnotes'] (render-row ctx "td" colspec row footnotes)]
                    [(str html row-html) footnotes']))
                ["" footnotes'']
                rows)]
    [(str "<table" (attr-attrs attr) ">"
          (when caption-text (str "<caption>" caption-text "</caption>"))
          (render-colgroup colspec)
          "<thead>" head-html "</thead>"
          "<tbody>" rows-html "</tbody>"
          "</table>")
     footnotes''']))

(defn- render-toc-entries
  "One `<ol>` of `entries` (`haselnuss.resolver/derive-toc`'s own nested
  `{:id :number :text :level :heading :children}` rows), each entry its
  own number and heading, and each entry's own `:children` a nested
  `<ol>` inside its `<li>` -- so the list's shape is the document's
  section nesting rather than a flat run of indented lines.

  The number printed is the entry's own `:number` and not its `:text`
  (`1.1 Method`, not `Section 1.1 Method`): that is what LaTeX's own
  `\\tableofcontents` prints, and printing the kind word here would say
  it twice on the same line. An entry the resolver numbered nothing --
  an unlabeled section is still a table-of-contents entry -- prints its
  heading alone.

  The whole entry is the link when it has an id to link to, so the
  number and the heading are one target rather than two."
  [ctx entries footnotes]
  (let [[html footnotes']
        (reduce (fn [[html footnotes] {:keys [id number heading children]}]
                  (let [[heading-html fs'] (render-inlines ctx (derived-lists/entry-inlines heading)
                                                           footnotes)
                        [children-html fs''] (if (seq children)
                                               (render-toc-entries ctx children fs')
                                               ["" fs'])
                        body (str (when number
                                    (str "<span class=\"toc-number\">"
                                         (escape-html number) "</span> "))
                                  heading-html)]
                    [(str html "<li>"
                          (if id
                            (str "<a href=\"#" (escape-html id) "\">" body "</a>")
                            body)
                          children-html
                          "</li>")
                     fs'']))
                ["" footnotes]
                entries)]
    [(str "<ol>" html "</ol>") footnotes']))

(defn- render-float-list-entries
  "One `<ol>` of `entries` (`haselnuss.resolver/derive-list-of-figures`'s
  own `{:kind :path :number :word :text :id :caption}` rows), each
  reading `\"Figure 1.1: A tree\"` -- the entry's own `:text`, then its
  caption, which is the same shape `render-caption` prints on the figure
  itself, so the list and the body say the same thing about the same
  node.

  Flat, unlike the table of contents: a list of figures has no nesting
  to show."
  [ctx entries footnotes]
  (let [[html footnotes']
        (reduce (fn [[html footnotes] {:keys [id text caption]}]
                  (let [[caption-html fs'] (render-inlines ctx (derived-lists/entry-inlines caption)
                                                           footnotes)
                        body (str (escape-html text)
                                  (when (seq caption) (str ": " caption-html)))]
                    [(str html "<li>"
                          (if id
                            (str "<a href=\"#" (escape-html id) "\">" body "</a>")
                            body)
                          "</li>")
                     fs']))
                ["" footnotes]
                entries)]
    [(str "<ol>" html "</ol>") footnotes']))

(defn- render-derived-list
  "Renders one list placeholder -- `{toc}`, `{list-of-figures}`,
  `{list-of-tables}` (TASK-59) -- as a `<nav>` carrying the directive's
  own Attr, its heading in the document's own language, and the derived
  list itself.

  The entries come from `ctx`'s `:derived-lists`, which `haselnuss.cli`
  built from the same resolved document and the same label table every
  cross-reference's text was baked from: this emitter never re-derives
  them, for the reason its `:labels` opt already documents -- a second
  table has to agree with the first by luck.

  A `<nav>`, and a heading of the same rank a front-matter block gets:
  a table of contents is navigation, and it is a peer of the sections
  it lists rather than a part of one. A placeholder for a list that is
  empty -- a document with no figures asking for a list of figures --
  still prints its heading and an empty list, which is exactly what
  `\\listoffigures` does with the same document."
  [ctx spec directive footnotes]
  (let [entries (get (:derived-lists ctx) (:derivation spec) [])
        [entries-html footnotes'] (if (= :toc (:derivation spec))
                                    (render-toc-entries ctx entries footnotes)
                                    (render-float-list-entries ctx entries footnotes))]
    [(str "<nav" (attr-attrs (update (:attr directive) :classes
                                     (fnil conj []) (:name directive)))
          ">"
          "<h1>" (escape-html (derived-lists/heading-word spec (:lang ctx))) "</h1>"
          entries-html "</nav>")
     footnotes']))

(defn- render-directive
  "Dispatches Directive `directive` to its own native `:html` renderer,
  looked up in `registry` (an optional `haselnuss.registry` map, from
  `ctx`) by `directive`'s own `:name` -- see this namespace's own
  docstring for why calling it is this task's job. No registry at all, or
  no renderer registered for `directive`'s name, raises `ex-info` (`:type
  ::unsupported-block`) naming the directive, exactly like any other
  unsupported block type; the renderer's own return value is spliced in
  verbatim, `footnotes` unchanged (see docstring: an extension's own
  nested content is opaque to this namespace's footnote accumulator)."
  [{:keys [registry] :as ctx} {directive-name :name :as directive} footnotes]
  (if-let [spec (derived-lists/spec directive)]
    ;; A list placeholder is drawn here, from the resolver's own
    ;; derivations, rather than by the marker the registry holds for it
    ;; (TASK-59) -- a list entry carries Inline content, which the
    ;; registry's `(fn [directive target])` signature gives no way to
    ;; render. Checked before the registry so the marker is never
    ;; called, the same order `haselnuss.emit.latex/render-directive`
    ;; uses for its own environment table.
    (render-derived-list ctx spec directive footnotes)
    (if-let [renderer (some-> registry (registry/lookup directive-name) (registry/renderer :html))]
      [(renderer directive :html) footnotes]
      (throw (ex-info
              (str "html emitter has no native renderer for directive " (pr-str directive-name))
              {:type ::unsupported-block :block directive})))))

(defn- unresolved-include-message
  "The `::unresolved-include` message for Include Block `block` (TASK-37
  AC #3). Names the `:src` and the real cause: an emitter has no target
  content to render, because expansion is
  `haselnuss.resolver/expand-includes`' job and it did not happen here.

  Since TASK-38 that pass exists and `haselnuss.cli` always runs it, so
  a document converted through the CLI never reaches this error --
  expansion either splices the file in or, for a missing file or a
  cycle, reports a diagnostic and drops the Include. What is left are
  the two routes that skip the pass: an AST built through
  `haselnuss.json/json->ast`, and a caller invoking `resolve-document`
  with no `:includes` loader.

  `haselnuss.emit.latex` raises the identically-worded error from its
  own namespace (asserted equal by test, not merely by convention), so
  the two targets fail the same way on the same document."
  [block]
  (str "cannot emit an :include block for " (pr-str (:src block))
       ": it was never expanded, so there is no target content to render"
       " (haselnuss.resolver/expand-includes does that, and needs an :includes"
       " :load option -- haselnuss.cli always supplies one, so this AST did not"
       " come through it)"))

(defn- render-block
  "Renders one Block (`haselnuss.ast/Block`) to `[html footnotes']`.
  Covers every Block variant `haselnuss.ast` defines except `:include`
  (Section/Para/List/CodeBlock/BlockQuote/Directive/Figure/Table/
  MathBlock, plus TASK-37's `:thematic-break`); `:include` raises
  `::unresolved-include` and anything outside the schema entirely raises
  `::unsupported-block` -- see this namespace's own docstring for why."
  [ctx block footnotes]
  (case (:t block)
    :section
    (let [[heading-html footnotes'] (render-inlines ctx (:heading block) footnotes)
          tag (heading-tag (:level block))
          ;; TASK-20: the generated bibliography Section's own direct List
          ;; gets each <li> tagged for render-cite's own anchors to target
          ;; -- see render-list's own docstring.
          section-ctx (if (and (:bibliography-id ctx)
                               (= (:bibliography-id ctx) (get-in block [:attr :id])))
                        (assoc ctx :li-id-prefix (:bibliography-id ctx))
                        ctx)
          [body-html footnotes''] (render-blocks section-ctx (:blocks block) footnotes')]
      [(str "<section" (attr-attrs (:attr block)) ">"
            "<" tag ">" (section-number-html ctx (:attr block)) heading-html "</" tag ">"
            body-html "</section>")
       footnotes''])

    :para
    (let [[html footnotes'] (render-inlines ctx (:inlines block) footnotes)]
      [(str "<p>" html "</p>") footnotes'])

    :list (render-list ctx block footnotes)

    :code-block [(render-code-block block) footnotes]

    :math-block [(render-math-block ctx block) footnotes]

    :figure (render-figure ctx block footnotes)

    :table (render-table ctx block footnotes)

    ;; `attr-attrs` here, not a bare `<blockquote>`: BlockQuote carries
    ;; an `Attr` like every other attr-bearing Block (sec4.3), and
    ;; dropping it silently cost any BlockQuote its id -- so a resolved
    ;; cross-reference to one emitted a link to an anchor that existed
    ;; nowhere in the document. Reachable in practice: a directive
    ;; degraded to a BlockQuote by a registry `:lower` rule keeps the
    ;; directive's own id (see `haselnuss.cli`).
    :block-quote
    (let [[html footnotes'] (render-blocks ctx (:blocks block) footnotes)]
      [(str "<blockquote" (attr-attrs (:attr block)) ">" html "</blockquote>") footnotes'])

    :directive (render-directive ctx block footnotes)

    ;; TASK-37 AC #1. HTML's own semantic element for a thematic break
    ;; between sections of content -- self-closed, since this
    ;; namespace's own test suite parses its output as XML.
    :thematic-break ["<hr/>" footnotes]

    :include (throw (ex-info (unresolved-include-message block)
                             {:type ::unresolved-include :block block}))

    (throw (ex-info
            (str "html emitter does not support block type " (pr-str (:t block)))
            {:type ::unsupported-block :block block}))))

(defn- render-blocks
  "`render-block` folded over every Block in `blocks`, in order,
  concatenating rendered HTML and threading `footnotes` throughout."
  [ctx blocks footnotes]
  (reduce (fn [[html footnotes] block]
            (let [[block-html footnotes'] (render-block ctx block footnotes)]
              [(str html block-html) footnotes']))
          ["" footnotes]
          blocks))

(defn- render-front-matter-block
  "Renders one front-matter directive `block` (TASK-54; see
  `haselnuss.extensions.front-matter` for what the category is and why
  its placement is decided rather than inherited) to `[html footnotes']`.

  A `<section>` carrying the directive's own Attr, its heading word in
  its own language, its prose rendered through the ordinary
  `render-blocks` visitor, and -- when it carries any -- a keywords line
  whose terms are each their own `<span class=\"keyword\">`.

  Three details are the ACs rather than decoration. The language tag
  reaches the output as a real `lang` attribute, per block rather than
  per document, so two abstracts in different languages inside one
  document are each labeled correctly (and `attr-attrs` needs no help
  here: `prop-attrs` already renders a `lang` prop as a literal HTML
  attribute). The block's own name joins its classes, so a stylesheet
  and a reader can both tell an abstract from an acknowledgement. And
  the keywords are separate elements, not one string with separators
  left in it, so a consumer never re-splits prose.

  Not every kind prints a heading: an epigraph and a dedication print
  none in either target (`front-matter/headed?`), because both are set
  apart by position and typography the way a book sets them, and a
  title over a dedication reads as a mistake. The decision is shared
  between the emitters rather than made twice -- a heading in one target
  and none in the other is a structural disagreement about the same
  document.

  Where there IS one, the heading is an `<h1>`, the same level a top-level Section gets
  (`heading-tag`), because that is what a front-matter block is: a peer
  of the body's top-level divisions, not something inside one. An
  earlier version used `<h2>` on the theory that `<h1>` belonged to the
  document title, which left the output opening with an `<h2>` followed
  by `<h1>`s (found by review). Since TASK-68 there IS a title heading
  in `<body>` -- `title-block`'s own `<h1 class=\"title\">` -- and the
  answer is still `<h1>`: the document title, the abstract and the first
  chapter are siblings in the outline, not a title with everything
  nested under it, which is the same shape pandoc emits. The stylesheet,
  not the tag, is what sets each of them apart in size.

  It is NOT a `:section` Block and never becomes one; that, plus
  `haselnuss.resolver/body-view` removing it before numbering runs, is
  what keeps it out of `derive-toc` and out of every number in the
  document."
  [ctx block footnotes]
  (let [doc-lang (:lang ctx)
        [body footnotes'] (render-blocks ctx (:blocks block) footnotes)
        terms (front-matter/keywords block)
        attr (-> (:attr block)
                 (update :classes (fnil conj []) (:name block))
                 ;; `keywords` is rendered as content below, so it must
                 ;; not ALSO pass through `prop-attrs` as a literal
                 ;; attribute: HTML has no `keywords` attribute, and the
                 ;; element would carry the undifferentiated string this
                 ;; task exists to stop handing consumers.
                 (update :props dissoc "keywords")
                 ;; `lang` is written back RESOLVED rather than passed
                 ;; through: a block with no tag of its own inherits the
                 ;; document's, and it has to say so in the output. Left
                 ;; to `prop-attrs` alone, the inheriting block emitted
                 ;; no `lang` at all, so a document with a Portuguese
                 ;; and an English abstract tagged only one of them.
                 (assoc-in [:props "lang"] (front-matter/lang block doc-lang))
                 ;; A `<section>` with no heading has no accessible name
                 ;; at all, so an epigraph and a dedication announced
                 ;; themselves as unlabelled regions (found by review).
                 ;; Their heading WORD is in the table either way; this
                 ;; is where it earns its place for the two kinds that
                 ;; print none.
                 (cond->
                  (not (front-matter/headed? block))
                   (assoc-in [:props "aria-label"]
                             (front-matter/heading-word block doc-lang))))]
    [(str "<section" (attr-attrs attr) ">"
          (when (front-matter/headed? block)
            (str "<h1>" (escape-html (front-matter/heading-word block doc-lang)) "</h1>"))
          body
          (when (seq terms)
            ;; The label's colon and the separators between terms are
            ;; real TEXT, not stylesheet `::before`/`::after` content
            ;; (found by review): `--no-stylesheet` output is a supported
            ;; mode, and there it read "KeywordsAlphaBeta" while LaTeX
            ;; printed "Keywords: Alpha; Beta". The spans stay, so a
            ;; consumer still gets the terms individually; what changed
            ;; is that the punctuation no longer lives only in the CSS.
            (str "<p class=\"keywords\"><span class=\"keywords-label\">"
                 (escape-html (front-matter/keyword-label block doc-lang))
                 "</span>: "
                 (str/join (escape-html front-matter/keyword-join)
                           (map (fn [term]
                                  (str "<span class=\"keyword\">" (escape-html term) "</span>"))
                                terms))
                 "</p>"))
          "</section>")
     footnotes']))

(defn- render-front-matter
  "`render-front-matter-block` over every front-matter directive in
  `blocks`, in document order, to `[html footnotes']`."
  [ctx blocks footnotes]
  (reduce (fn [[html footnotes] block]
            (let [[block-html footnotes'] (render-front-matter-block ctx block footnotes)]
              [(str html block-html) footnotes']))
          ["" footnotes]
          blocks))

(declare plain-text)

(defn- plain-text-inline
  "The plain-text contribution of one Inline `inline`, stripping all
  markup -- used only for `<title>`'s own fallback text (`emit-document`),
  not for body rendering, so this is deliberately permissive rather than
  raising on a type it does not specifically handle: `meta.title` is an
  ordinary open-ended `[:vector [:ref ::inline]]` (sec4.2), so it can in
  principle carry a node this namespace's own body-rendering scope does
  not cover, and a `<title>` with slightly degraded fallback text for one
  of those is preferable to `emit-document` itself refusing to produce a
  document over it. Recurses into `:inlines`-carrying variants, Link, and
  (TASK-20) `:cite`'s own already-formatted `:text`; `:code`/`:image`
  contribute their own text/`:alt`; `:math-inline`'s raw `:tex` and a
  resolved CrossRef's already-computed `:text` (both TASK-20) also
  contribute their own text directly (unescaped, like every other case
  here -- `emit-document` HTML-escapes the whole assembled title string
  once, at the point it splices it into `<title>`); every other variant
  (`:note`, a footnote's Blocks are not Inlines) contributes nothing."
  [inline]
  (case (:t inline)
    :str (:text inline)
    :space " "
    :soft-break " "
    :line-break " "
    (:emph :strong :strike :small-caps :sub :sup :span :link) (plain-text (:inlines inline))
    :code (:text inline)
    :image (:alt inline)
    :math-inline (:tex inline)
    :cross-ref (or (:text inline) "")
    :cite (plain-text (:text inline))
    ""))

(defn- plain-text
  "`plain-text-inline` mapped and concatenated over every Inline in
  `inlines`."
  [inlines]
  (apply str (map plain-text-inline inlines)))

(defn- title-block
  "The document's own title, authors and date (sec4.2 `meta`) as a
  `<header class=\"title-block\">` at the top of `<body>`, or `[\"\"
  footnotes]` when `meta` carries no `:title` -- returns `[html
  footnotes']`, since a footnote written into a title is still a
  footnote (TASK-68).

  This is HTML's `\\maketitle`, and it is gated the same way the LaTeX
  one is: `haselnuss.emit.latex/meta-preamble` omits `\\maketitle`
  entirely rather than emitting a title-less one, so a document with no
  `:title` gets no title block here either, `:authors` and `:date`
  included. Gating on the title rather than on \"any of the three\" is
  the deliberate half of that: the defect this fixes is one document
  printing its own metadata in one target and not the other, and a rule
  that fired here but not there would have re-made it in the other
  direction. What a title-less document with authors gets in both
  targets is nothing -- see the README.

  `:title` is a vector of Inlines and renders through the ordinary
  `render-inlines` pipeline, so authored markup inside a title reaches
  the page as markup -- unlike `<title>`, which is text-only by
  definition and takes `plain-text`'s flattening of the same vector.
  `:authors` and `:date` are plain strings by schema and are escaped,
  not rendered; each author is its own `<p class=\"author\">` rather
  than one joined string, so a consumer never re-splits what the front
  matter already had separate (the same reasoning as
  `render-front-matter-block`'s keyword spans).

  The classes are the whole of AC #4's override story, and the markup
  under them is plain heading-and-paragraph, so `--no-stylesheet`
  output still reads as a title over its authors over its date."
  [ctx {:keys [title authors date]} footnotes]
  (if (seq title)
    (let [[title-html footnotes'] (render-inlines ctx title footnotes)]
      [(str "<header class=\"title-block\">"
            "<h1 class=\"title\">" title-html "</h1>"
            (apply str
                   (map (fn [author]
                          (str "<p class=\"author\">" (escape-html author) "</p>"))
                        authors))
            ;; `seq`, not just `some?`: a blank `date:` in the front
            ;; matter emitted an empty centred paragraph with margins of
            ;; its own, where the LaTeX side prints nothing (found by
            ;; review).
            (when (seq date) (str "<p class=\"date\">" (escape-html date) "</p>"))
            "</header>")
       footnotes'])
    ["" footnotes]))

(defn- footnotes-section
  "The trailing footnote-list `<section>` (AC #3) built from `footnotes`
  (this namespace's own accumulator, in ascending `:number` order by
  construction): one `<li id=\"fn{n}\">` per entry, its rendered content
  followed by a `#fnref{n}` backlink to the marker that referenced it, so
  the list and its markers are linked both ways. `\"\"` when `footnotes`
  is empty -- no empty footnote section clutters a document with none."
  [footnotes]
  (if (empty? footnotes)
    ""
    (str "<section class=\"footnotes\"><hr/><ol>"
         (apply str
                (map (fn [{:keys [number html]}]
                       (str "<li id=\"fn" number "\">" html
                            "<a href=\"#fnref" number "\">&#8617;</a></li>"))
                     footnotes))
         "</ol></section>")))

(def ^:private mathjax-script
  "The MathJax v3 CDN `<script>` tag `emit-document` always includes in
  `<head>` (AC #2) -- see this namespace's own docstring for why
  unconditionally, and why no separate config script is needed.
  `async=\"async\"` (the explicit-value boolean-attribute form), not a
  bare `async`, since this namespace's own test suite (and XML in
  general) requires every attribute to have a value -- a bare `async`
  parses fine in an actual HTML5 browser but is not well-formed XML;
  `async=\"async\"` is well-formed XML *and* HTML5's own documented
  boolean-attribute convention, so a browser still treats it as present/
  truthy exactly the same way."
  (str "<script id=\"MathJax-script\" async=\"async\""
       " src=\"https://cdn.jsdelivr.net/npm/mathjax@3/es5/tex-mml-chtml.js\"></script>"))

(def default-stylesheet
  "The default stylesheet `emit-document` inlines into `<head>`
  (TASK-43), so its output is legible with no external file.

  DECISIONS this encodes, each of which had an alternative:

  - INLINED into `<style>`, not linked and not written beside the
    output. The emitter's whole contract is one self-contained file
    (AC #5) -- an author converts a `.hdoc` and gets something they can
    mail, commit or open from a filesystem with no server -- and a
    linked stylesheet would have made that two files that must travel
    together. The cost is a fixed ~2 KB in every document, which is
    smaller than the MathJax `<script>` tag's own consequences.
  - It styles the vocabulary the emitter ALREADY emits (AC #2):
    `math display`, `math-number`, `footnotes`, `bibliography`,
    `section-number`, `collapsable`, `small-collapsable` (plus the
    `small-collapsable-body` wrapper its own renderer emits, which is
    what carries that element's expanded state -- see
    `haselnuss.extensions.small-collapsable`), and a degraded directive's own
    name (`admonition`, `theorem`, `lemma`, `corollary`, `definition`,
    `proof`), plus plain elements. No new class was added for it -- the
    gap TASK-29's review found was presentational, not structural, and
    inventing markup to hang CSS on would have changed what the emitter
    means. Two classes it deliberately does NOT key on: `math inline`,
    which should sit in the text line exactly as the text does, and
    `language-*`, whose point is to tell a highlighter which language a
    block is, not to look different per language -- `pre code` covers
    what a default sheet can honestly say about either.
  - Every colour is either inherited or a semi-transparent grey, and
    neither `background` nor `color` is set on `body`, so one sheet
    reads on a light and a dark canvas without a `prefers-color-scheme`
    block guessing at either. `:root{color-scheme:light dark}` is what
    makes that reachable rather than theoretical: a page that never
    declares `color-scheme` keeps the light canvas even when the
    reader's browser is in dark mode, so the low-alpha greys were
    costing contrast and buying nothing (measured by review -- the
    background pixel stayed 255,255,255 under a dark preference, and
    flips to 18,18,18 with this line).
  - No `>` child combinators, deliberately: this namespace's own tests
    parse the output as XML, and an unescaped `>` inside `<style>` is
    valid HTML but not valid XML. A caller substituting their own sheet
    through `:stylesheet` owns that constraint themselves.

  Written as a default a reader can override, not a theme: no fonts are
  downloaded, the measure is capped rather than fixed, and every rule is
  one a caller's own `:stylesheet` string can restate."
  (str/join
   "\n"
   [":root{color-scheme:light dark}"
    "body{max-width:42rem;margin:2rem auto;padding:0 1rem;"
    "line-height:1.6;font-family:Georgia,'Times New Roman',serif}"
    "h1,h2,h3,h4,h5,h6{line-height:1.25;margin:2rem 0 .5rem}"
    ".section-number{opacity:.65;margin-right:.4em}"
    ;; Tables: the gap TASK-29's side-by-side reading made concrete --
    ;; the PDF had rules and aligned columns, the HTML neither.
    "table{border-collapse:collapse;width:100%;margin:1.5rem 0}"
    "caption{caption-side:top;text-align:left;font-style:italic;"
    "padding-bottom:.5rem;opacity:.85}"
    "th,td{border:1px solid rgba(128,128,128,.4);padding:.4rem .6rem;vertical-align:top}"
    "thead th{border-bottom-width:2px;text-align:left}"
    ;; A degraded directive keeps its own name as a class, which is the
    ;; only thing distinguishing an admonition from a plain quote.
    "blockquote{margin:1.5rem 0;padding:.1rem 1rem;"
    "border-left:3px solid rgba(128,128,128,.4)}"
    "blockquote.admonition{background:rgba(128,128,128,.08);font-style:italic}"
    "blockquote.theorem,blockquote.lemma,blockquote.corollary,"
    "blockquote.definition,blockquote.proof{background:rgba(128,128,128,.08)}"
    "blockquote.theorem p:first-child,blockquote.lemma p:first-child,"
    "blockquote.corollary p:first-child,blockquote.definition p:first-child,"
    "blockquote.proof p:first-child,blockquote.admonition p:first-child{margin-top:.6rem}"
    "blockquote.theorem p:last-child,blockquote.lemma p:last-child,"
    "blockquote.corollary p:last-child,blockquote.definition p:last-child,"
    "blockquote.proof p:last-child,blockquote.admonition p:last-child{margin-bottom:.6rem}"
    ;; Code: monospace at a readable size, and scrollable rather than
    ;; overflowing, since a code block's lines are not reflowable.
    "pre{overflow-x:auto;padding:.8rem 1rem;background:rgba(128,128,128,.1);"
    "border-radius:3px;line-height:1.45}"
    "pre code,code{font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;font-size:.9em}"
    "code{background:rgba(128,128,128,.12);padding:.1em .3em;border-radius:2px}"
    "pre code{background:none;padding:0}"
    "figure{margin:1.5rem 0;text-align:center}"
    "figure img{max-width:100%;height:auto}"
    "figcaption{font-style:italic;opacity:.85;margin-top:.5rem;text-align:left}"
    ;; TASK-57/TASK-58: a listing and an algorithm are both <figure>, and
    ;; `figure{text-align:center}` above is inherited by the <pre> inside
    ;; them -- so code came out centred, one ragged line per line (found
    ;; by review, twice: the first fix named only `.listing`, and
    ;; float-lower-rule puts the DIRECTIVE NAME on the class, so
    ;; pseudocode -- the content least able to survive being centred --
    ;; still was). Code is left-aligned everywhere else and has to be
    ;; here too.
    "figure.listing pre,figure.algorithm pre{text-align:left}"
    ;; TASK-56: a multi-panel figure's rows. The row carries the
    ;; author's own `columns` arrangement -- one row element per N
    ;; panels, decided when the directive was lowered -- so the panels
    ;; only have to share the row's width, never decide how many of them
    ;; fit. `blockquote` because that is the Block a lowered directive
    ;; has to group children in (see `haselnuss.cli`), which means
    ;; undoing the quote styling the rule above gives every one of them.
    "blockquote.subfigures{margin:0;padding:0;border-left:none}"
    ;; A grid, not a flex row: the row carries its own track count as an
    ;; inline style (see `haselnuss.cli/panel-rows`), so a trailing
    ;; partial row leaves its empty tracks empty and its panel keeps the
    ;; width its siblings have -- which is the width the LaTeX side
    ;; gives every panel. Flex-grow stretched a lone last panel across
    ;; the full line while the PDF kept it at half (found by review).
    "blockquote.subfigure-row{display:grid;gap:1rem;align-items:end;"
    "margin:0;padding:0;border-left:none}"
    ;; A descendant selector, not a `>` child one: this namespace's own
    ;; documented stylesheet constraint is that its tests parse the
    ;; output as XML, where a bare `>` inside <style> is invalid. The
    ;; only figures inside a row are its own panels anyway.
    "blockquote.subfigure-row figure{margin:.5rem 0}"
    "figure.subfigure figcaption{text-align:center}"
    ;; TASK-59: the three derived lists. The entries print their own
    ;; numbers, so the browser's list markers would set a second,
    ;; different one beside each ("1. 1 Background"); and a list's own
    ;; number span is the same thing a heading's is, so it is styled the
    ;; same way `.section-number` is (found by review, which found the
    ;; two looking nothing alike).
    "nav.toc,nav.list-of-figures,nav.list-of-tables{margin:1.5rem 0}"
    "nav.toc ol,nav.list-of-figures ol,nav.list-of-tables ol{list-style:none;"
    "padding-left:1.25rem;margin:.25rem 0}"
    "nav.toc h1,nav.list-of-figures h1,nav.list-of-tables h1{font-size:1.3rem}"
    ".toc-number{opacity:.65;margin-right:.4em}"
    ;; The equation number already floats via an inline style; this is
    ;; what stops it colliding with the equation body.
    ".math.display{margin:1.5rem 0;overflow-x:auto}"
    ".math-number{opacity:.75}"
    "details.collapsable{margin:1.5rem 0;padding:.4rem .8rem;"
    "border:1px solid rgba(128,128,128,.4);border-radius:3px}"
    "details.collapsable summary{cursor:pointer;font-weight:bold}"
    ;; small-collapsable's two-state layout contract (see
    ;; `haselnuss.extensions.small-collapsable`): inline while collapsed,
    ;; so a run of them flows and wraps like words; and a *block* body,
    ;; whose block-in-inline box is what breaks the line before and after
    ;; the revealed content. The trailing margin on the summary is what
    ;; separates consecutive labels -- the emitter concatenates sibling
    ;; blocks with no whitespace between them, so without it two adjacent
    ;; labels would render as one run-on word.
    "details.small-collapsable{display:inline}"
    ;; Chrome wraps a <details>'s content in a ::details-content
    ;; pseudo-element that is itself display:block. Left alone, that
    ;; block box splits the inline box around it and puts every
    ;; COLLAPSED label on a line of its own -- measuring the rendered
    ;; page is what identified it, since nothing in the emitted markup
    ;; shows it. Making it inline is what lets the labels flow; the
    ;; body wrapper below still supplies the block box that breaks the
    ;; line when [open]. Browsers with no such pseudo-element ignore
    ;; this rule and already lay the element out correctly.
    "details.small-collapsable::details-content{display:inline}"
    "details.small-collapsable summary{display:inline;cursor:pointer;"
    "list-style:none;border-bottom:1px dotted;margin-right:.4em}"
    "details.small-collapsable summary::-webkit-details-marker{display:none}"
    ;; The inert marker an authored `label=` puts ahead of a disclosure.
    ;; A span is already inline, so spacing is all it needs -- and it does
    ;; need that: the emitter writes no whitespace between the span and
    ;; the <details> that follows it.
    ".small-collapsable-label{margin-right:.35em}"
    ;; The body is display:none while collapsed and only becomes a block
    ;; under [open]. Both halves are load-bearing, and measuring the
    ;; rendered page is what proved it: giving the wrapper a permanent
    ;; display:block overrides the UA rule that hides a closed
    ;; <details>'s content, so the block box existed even when
    ;; collapsed -- and a block box inside an inline one splits it,
    ;; which put every collapsed label on a line of its own, exactly
    ;; the behaviour this element exists to avoid.
    "details.small-collapsable .small-collapsable-body{display:none}"
    "details.small-collapsable[open] .small-collapsable-body{display:block;"
    "margin:.6rem 0;padding:.4rem .8rem;"
    "border-left:2px solid rgba(128,128,128,.4)}"
    ;; No border-top on .footnotes: the emitter already writes an
    ;; <hr/> as that section's first child, and one styled here too
    ;; drew two parallel grey lines (found by review, in the render).
    ;; TASK-68: the title block is the page's own title page, so it is
    ;; centred and set apart from what follows the way `\\maketitle`
    ;; sets one. Its markup is a heading and two paragraph kinds, so the
    ;; classes here only place and size what is already legible without
    ;; them (`--no-stylesheet` is a supported mode).
    "header.title-block{margin:0 0 3rem;text-align:center}"
    "header.title-block h1.title{margin:0 0 1rem;font-size:1.8em}"
    "header.title-block p.author{margin:.2rem 0;font-size:1.05em}"
    "header.title-block p.date{margin:1rem 0 0;opacity:.75}"
    ;; TASK-54: a front-matter block is set apart from the body the way
    ;; a printed abstract is -- narrower measure, smaller type -- and its
    ;; keywords read as a labelled list of terms rather than a sentence.
    ;; Only classes this emitter already writes are keyed on, per this
    ;; stylesheet's own rule.
    "section.abstract{margin:2rem 0 2.5rem;padding-bottom:1rem;font-size:.95em;"
    "border-bottom:1px solid rgba(128,128,128,.4)}"
    "section.abstract h1,section.acknowledgements h1{font-size:1.15em}"
    "p.keywords{margin-top:1rem;font-size:.95em}"
    ".keywords-label{font-weight:bold}"
    "section.acknowledgements{margin:2rem 0 2.5rem}"
    ;; TASK-55: an epigraph and a dedication print no heading in either
    ;; target, so position and typography are all that set them apart --
    ;; which makes these rules load-bearing rather than decorative.
    "section.epigraph{margin:2rem 0;text-align:right;font-style:italic}"
    "section.dedication{margin:4rem 0;text-align:center;font-style:italic}"
    ;; Emphasis inside an italic block reverts to upright -- which is
    ;; what LaTeX's own `\\emph` does inside `\\itshape`, and what a
    ;; browser does NOT do on its own. Without this, the same authored
    ;; `*word*` came out upright in the PDF and italic in HTML: one
    ;; document, two different renderings of one piece of markup (found
    ;; by review). LaTeX's behaviour is the typographically correct one,
    ;; so HTML is the side made to agree.
    "section.epigraph em,section.dedication em{font-style:normal}"
    "section.footnotes{margin-top:2.5rem;font-size:.92em}"
    "section.bibliography{margin-top:2.5rem;padding-top:1rem;"
    "border-top:1px solid rgba(128,128,128,.4);font-size:.92em}"
    "hr{border:none;border-top:1px solid rgba(128,128,128,.4);margin:2rem 0}"
    "img{max-width:100%}"]))

(defn- stylesheet-tag
  "The `<style>` element `emit-document` puts in `<head>` for the
  `:stylesheet` opt, or `\"\"` for `:none` (AC #4).

  `:default` (or nil, i.e. absent) inlines `default-stylesheet`;
  `:none` (or `false`) inlines nothing, for a caller embedding this
  output in a page that brings its own CSS; a non-blank string is used
  verbatim as the sheet's own content, which is how a caller replaces
  the default rather than merely suppressing it.

  Anything else throws `ex-info` (`:type ::invalid-stylesheet`) rather
  than being used as CSS. Without that, a typo -- `:defualt`, or a
  keyword a caller expected to be recognized -- emitted
  `<style>:defualt</style>`: an unstyled document with junk in its
  `<head>` and nothing said about it (found by review). A blank string
  is refused for the same reason, since `:none` is how one asks for no
  sheet."
  [stylesheet]
  (let [css (cond
              (contains? #{:none false} stylesheet) nil
              (contains? #{:default nil} stylesheet) default-stylesheet
              (and (string? stylesheet) (not (str/blank? stylesheet))) stylesheet
              :else (throw (ex-info (str "invalid :stylesheet " (pr-str stylesheet)
                                         " (expected :default, :none, or a non-blank CSS string)")
                                    {:type ::invalid-stylesheet :stylesheet stylesheet})))]
    (if css (str "<style>" css "</style>") "")))

(defn- citation-positions
  "A map from each key in `ordered-keys` (an `:ordered-keys` opt, see this
  namespace's own docstring) to its 1-based position -- the same position
  `render-list`'s own `:li-id-prefix` numbering assigns to the matching
  `<li>` by construction, since both derive from the identical sequence.
  `{}` for a nil/empty `ordered-keys` (no citation ever resolves to a
  link target then, exactly as if `:ordered-keys` had not been supplied
  at all)."
  [ordered-keys]
  (into {} (map-indexed (fn [i k] [k (inc i)])) ordered-keys))

(defn emit-document
  "Emits `document` (a resolved-and-lowered `haselnuss.ast/Document`) as
  one complete, valid HTML document string: a `<!DOCTYPE html>` document
  with `<html lang=\"...\">` from `meta.lang` (omitted when absent), a
  `<head>` carrying a UTF-8 charset declaration, a `<title>` (from
  `meta.title`'s own `plain-text` rendering, or the literal string
  \"Untitled document\" when `meta.title` is absent/empty -- a valid HTML5
  document always has a `<title>`) and the MathJax CDN `<script>` tag (AC
  #2, `mathjax-script`), and a `<body>` holding the rendered Blocks (AC
  #1/#2/#3/#4) followed by the accumulated footnote list, if any (AC #3
  of TASK-19, `footnotes-section`).

  `<body>` opens with the `title-block` -- the document's own title,
  authors and date, which `<title>` alone cannot carry (TASK-68) -- then
  the front matter, then the body.

  `opts` (default `{}`) accepts `:registry` -- a `haselnuss.registry` map
  used to dispatch any surviving `:directive` Block to its own native
  `:html` renderer (see this namespace's own docstring); omitted, a
  `:directive` in `document` always raises `::unsupported-block` -- plus
  three TASK-20 additions, all also described in this namespace's own
  docstring: `:labels` (a `number-document` label table, default `{}`,
  for Figure/Table numbering), `:bibliography-id` (the generated
  bibliography Section's own id, default nil), and `:ordered-keys` (that
  Section's own List rendering order, default `[]`, from which this
  namespace's own `citation-positions` builds the `ctx` map `render-cite`
  consults), and `:stylesheet` (TASK-43: `:default`, `:none`, or a CSS
  string -- see `stylesheet-tag` and `default-stylesheet`), and
  `:derived-lists` (TASK-59: the resolver's own derived table of
  contents / list of figures / list of tables, keyed by derivation --
  `:toc`/`:list-of-figures`/`:list-of-tables` -- which a matching
  placeholder directive in `document` renders; default `{}`, with which
  such a placeholder prints its heading over an empty list).

  Front-matter blocks (TASK-54) are lifted out of `document`'s own
  top-level Blocks by `haselnuss.extensions.front-matter/extract` and
  rendered ahead of the body, each as its own `<section>` carrying its
  own `lang`. That is what makes them not-a-body-section rather than
  merely styled like one, and it is the same split the LaTeX emitter
  makes -- see that namespace for what `--fragment` does with them
  instead."
  ([document] (emit-document document {}))
  ([document opts]
   (let [lang (get-in document [:meta :lang])
         ctx {:registry (:registry opts)
              :labels (:labels opts {})
              :bibliography-id (:bibliography-id opts)
              :citation-positions (citation-positions (:ordered-keys opts))
              ;; TASK-54: the document's own language, so a front-matter
              ;; block with no `lang` of its own still prints its heading
              ;; word in the language the document is written in.
              :lang lang
              ;; TASK-59: the three derived lists, keyed by derivation
              ;; (`:toc`/`:list-of-figures`/`:list-of-tables`), for
              ;; whichever placeholders the document wrote. Handed in
              ;; rather than derived here for exactly the reason
              ;; `:labels` is: they are built from the same resolved
              ;; document the numbering ran over, and a second
              ;; derivation would be a second table that has to agree
              ;; with the first by luck. Absent -- a caller that
              ;; passes none -- every placeholder prints its heading
              ;; over an empty list.
              :derived-lists (:derived-lists opts {})}
         title-inlines (get-in document [:meta :title])
         title-text (if (seq title-inlines) (plain-text title-inlines) "Untitled document")
         ;; TASK-54: front matter is lifted out of the body and placed
         ;; ahead of it, which is what makes it not-a-body-section rather
         ;; than merely styled like one.
         [front-matter body-blocks] (front-matter/extract document)
         ;; TASK-68: the title block comes first and is threaded through
         ;; the same footnote accumulator as everything after it, so a
         ;; note in a title takes number 1 rather than colliding with
         ;; the body's own.
         [title-html footnotes] (title-block ctx (:meta document) [])
         [front-html footnotes] (render-front-matter ctx front-matter footnotes)
         [body footnotes] (render-blocks ctx body-blocks footnotes)]
     (str "<!DOCTYPE html>"
          "<html" (when lang (str " lang=\"" (escape-html lang) "\"")) ">"
          "<head><meta charset=\"utf-8\"/>"
          ;; TASK-43: without this a mobile browser lays the page out at
          ;; its ~980px fallback width and zooms out, so the capped
          ;; measure, `img{max-width:100%}` and `pre{overflow-x:auto}`
          ;; the stylesheet relies on never take effect (found by
          ;; review). Unconditional, not tied to `:stylesheet`: it is
          ;; correct for any HTML document, styled or not.
          "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"/>"
          "<title>" (escape-html title-text) "</title>"
          (stylesheet-tag (:stylesheet opts)) mathjax-script "</head>"
          "<body>" title-html front-html body (footnotes-section footnotes) "</body>"
          "</html>"))))

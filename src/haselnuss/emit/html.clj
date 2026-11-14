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

  `emit-document` includes one async MathJax v3 CDN `<script>` tag in
  `<head>` only when the rendered output actually contains math
  (`has-math?`, a check on the rendered HTML for the `math inline` /
  `math display` class markers, which user text cannot forge because it
  is escaped). Checking the output rather than the tree means a math
  node a lowering pass dropped does not count, and avoids a second
  parallel tree walker. A math-free page therefore makes no third-party
  request (TASK-130). An inline `mathjax-config` script precedes it, so
  a document's own math cannot make links or attributes (TASK-217).
  Opt-out for offline use: pass `:mathjax false` in
  `opts` to omit the script even for a document with math (the default,
  `:auto`, is the behaviour above). It is a library opt only; the CLI
  has no flag for it.

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
            [haselnuss.ast :as ast]
            [haselnuss.emit.quote :as book-quote]
            [haselnuss.extensions.derived-lists :as derived-lists]
            [haselnuss.extensions.front-matter :as front-matter]
            [haselnuss.registry :as registry]
            [haselnuss.sanitize :as sanitize]
            [haselnuss.select :as select]
            [haselnuss.walk :as walk]))

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

(def ^:private directive-internal-props
  "Prop names that belong to the document model, not to HTML (TASK-139):
  `caption` (a float's caption text, or a theorem's, which LaTeX never
  prints), `key` and `ref` (a reference's target). None is a valid
  attribute on the elements they land on (`<blockquote caption=..>`,
  `<span ref=..>`), so `prop-attrs` never writes them; genuine HTML
  attributes such as `lang` still pass through."
  #{"caption" "key" "ref"})

(defn- prop-attrs
  "One ` escaped-key=\"escaped value\"` HTML attribute fragment per entry
  in `props` (an Attr's own `:props`, sec4.1's open-ended string-keyed
  bag, minus `directive-internal-props`), concatenated together. Each entry passes through directly as a
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
  (apply str (map (fn [[k v]] (str " " (escape-html k) "=\"" (escape-html v) "\""))
                  (remove (comp directive-internal-props key) props))))

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

(defn- title-attr
  "The ` title=\"...\"` fragment for a Link/Image `inline` carrying a
  `:title` (CommonMark's `[t](url \"Title\")`; browsers show it as a
  tooltip), or `\"\"` for one without. An authored `{title=...}` prop on
  the same node wins: emitting both would be a duplicate attribute
  (TASK-140)."
  [inline]
  (if (and (:title inline) (not (contains? (:props (:attr inline)) "title")))
    (str " title=\"" (escape-html (:title inline)) "\"")
    ""))

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

;; --- Annotations: :highlight and :sticky-note (TASK-120, doc-8 sec5) -------

(def annotation-colors
  "The `color=` values an annotation recognizes (doc-8 sec2). A CLOSED
  set, not free hex: the value has to map to both a CSS colour and a PDF
  annotation colour, and each one here has its own rule in
  `default-stylesheet`."
  #{"yellow" "green" "blue" "pink"})

(def ^:private default-annotation-color
  "The colour an annotation with no `color=` prop is rendered in, and the
  fallback for one carrying a value outside `annotation-colors` (AC #4).

  Falling back rather than raising, deliberately: an authored value
  outside the set is diagnosed where an author writes it -- on the
  overlay directive (TASK-119) -- and by the time a Document reaches an
  emitter it may equally have come through `--target json` or a
  transform rule. Rendering it in the default colour keeps the annotation
  visible, which is the whole point of one, rather than losing it to a
  class no stylesheet defines."
  "yellow")

(defn- annotation-color
  "`attr`'s own `color=` prop when it names one of `annotation-colors`,
  else `default-annotation-color`."
  [attr]
  (let [authored (get-in attr [:props "color"])]
    (if (contains? annotation-colors authored) authored default-annotation-color)))

(defn- annotation-attr
  "`attr` rewritten for an annotation element of kind `kind` (`\"highlight\"`
  or `\"sticky-note\"`): `color=` becomes a CLASS rather than a literal
  attribute, and `author=` becomes `data-author`.

  Both moves are the point rather than housekeeping. A class is what lets
  a caller substituting their own `:stylesheet` restate the colour, where
  an inline style could not be overridden; and `color=\"yellow\"` is not
  an HTML attribute at all, so left in `:props` it would reach
  `prop-attrs` and be written as one (the same reasoning
  `image-attr` already applies to `scale`/`height`). `author=` becomes a
  data attribute because it belongs in the markup -- it is what makes an
  overlay usable as a review pass -- but must never become visible page
  text (AC #5).

  The kind class comes first and the colour class second (`class=\"highlight
  highlight-yellow\"`), with any authored classes after both, so a
  stylesheet can key on either the kind alone or the exact colour."
  [attr kind]
  (let [author (get-in attr [:props "author"])]
    (-> attr
        (assoc :classes (into [kind (str kind "-" (annotation-color attr))]
                              (:classes attr)))
        ;; `style` goes too, and it is the one that would have hurt most:
        ;; doc-8 sec2 defines it, `haselnuss.resolver` deliberately keeps
        ;; it on the node for the LaTeX target to read, and `style` IS a
        ;; real HTML attribute -- so left in `:props` it reached
        ;; `prop-attrs` and emitted `style="footnote"`, colliding with an
        ;; authored CSS `style` prop into a duplicate attribute.
        (update :props dissoc "color" "author" "style")
        (cond-> author (assoc-in [:props "data-author"] author)))))

(declare render-inlines)

(defn- concat-rendered
  "`[html footnotes']`: `(render x footnotes)` -- itself `[piece
  footnotes']` -- over every `x` in `xs`, in order, threading `footnotes`
  and concatenating the pieces (a nil piece as \"\", as `str` has it).

  One StringBuilder for the whole sequence (TASK-261, as TASK-258 did for
  a paragraph's inlines): `(str html piece)` per element copied everything
  written so far each time, so a document of 80k paragraphs -- or a list
  of 80k items, a table of 80k rows -- took tens of seconds to emit."
  [render footnotes xs]
  (let [sb (StringBuilder.)
        footnotes' (reduce (fn [footnotes x]
                             (let [[piece footnotes'] (render x footnotes)]
                               (.append sb (str piece))
                               footnotes'))
                           footnotes
                           xs)]
    [(str sb) footnotes']))

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
        ;; TASK-261: the reservation is entry n-1 of the vector this namespace
        ;; builds, so fill it in place; walking every footnote for each one
        ;; made a document of many footnotes quadratic. The walk stays for an
        ;; accumulator of any other shape.
        filled (if (and (vector? footnotes') (= n (:number (get footnotes' (dec n)))))
                 (assoc-in footnotes' [(dec n) :html] content-html)
                 (mapv (fn [entry] (if (= n (:number entry)) (assoc entry :html content-html) entry))
                       footnotes'))]
    [marker filled]))

(defn- balloon-blocks
  "A `:sticky-note` body's own Blocks rendered as PHRASING content: each
  Block becomes a `<span>` (given `display:block` by the stylesheet),
  with its own inline content rendered normally inside.

  This is not decoration, it is what makes the balloon legal. A sticky
  note is an INLINE node, so everything it emits sits inside a `<p>`, and
  a `<p>` may contain only phrasing content -- a `<p>`, `<ul>` or `<pre>`
  in there is not nested by an HTML parser, it CLOSES the enclosing
  paragraph and becomes its sibling. Measured, not assumed: two
  independent HTML5 tree builders re-parse a `<p>` containing a `<p>` into
  three siblings, which puts the balloon's marker on a line of its own at
  the left margin and orphans the rest of the sentence -- the exact
  opposite of what a sticky note is for.

  A Block this has no phrasing rendering for -- a Figure or Table, which
  the overlay directive already drops for the numbering invariant, but
  which `--target json` or a transform rule could still put here -- falls
  back to its flattened text rather than to markup that would break the
  paragraph. Lossy, and deliberately: the words survive and the document
  around them stays intact, which is the better of the two failures."
  [ctx blocks footnotes]
  (concat-rendered
   (fn [block footnotes']
     (let [[inner footnotes'']
           (case (:t block)
             :para (render-inlines ctx (:inlines block) footnotes')
             :block-quote (balloon-blocks ctx (:blocks block) footnotes')
             :list (balloon-blocks ctx (apply concat (:items block)) footnotes')
             :code-block [(str "<code>" (escape-html (:text block)) "</code>") footnotes']
             [(escape-html (select/flatten-text block)) footnotes'])]
       [(str "<span>" inner "</span>") footnotes'']))
   footnotes
   blocks))

(defn- render-sticky-note
  "Renders a `:sticky-note` (sec4.4) as an inline disclosure whose body is
  an absolutely-positioned box: click the marker to open it, click again
  to dismiss, with no JavaScript at all (doc-8 sec5).

  NOT a `<details>`, which is what doc-8 sec5 and this task's own AC #2
  specify -- and the deviation is the whole point of this docstring.
  `<details>` is flow content, not phrasing, so an HTML parser closes the
  enclosing `<p>` when it meets one; a sticky note is an inline node, so
  its `<details>` became a SIBLING of the paragraph it was supposed to sit
  inside. Measured with two independent HTML5 tree builders and confirmed
  in a real browser's layout boxes: the marker landed on a new line at
  the left margin and the rest of the sentence was orphaned after it.
  `display:inline` cannot repair that -- by the time CSS runs, the
  document tree is already wrong.

  The `small-collapsable` precedent does not carry over, and that is why
  it looked safe: that extension is a BLOCK directive, so its `<details>`
  is a sibling of `<p>` to begin with and only its LAYOUT is inline. A
  `:sticky-note` is the first inline node in this emitter that has to
  carry flow-shaped content.

  So the disclosure is a hidden checkbox plus its `<label>` -- `<input>`,
  `<label>` and `<span>` are all phrasing content, so the whole thing is
  legal inside a paragraph -- and the stylesheet opens the body on
  `:checked`. Still no script, still click-to-open and click-to-dismiss.

  The `<label>` marker is deliberately a word rather than a symbol: this
  emitter's contract is one self-contained file legible with no
  stylesheet at all (`--no-stylesheet` is a supported mode), and an empty
  label is a click target a reader cannot see. `author=` is NOT in it --
  that is a data attribute and never visible page text (AC #5)."
  [ctx sticky-note footnotes]
  (let [[body-html footnotes'] (balloon-blocks ctx (:blocks sticky-note) footnotes)
        attr (annotation-attr (:attr sticky-note) "sticky-note")
        ;; The checkbox needs an id for its own <label> to point at, and
        ;; it must not be the annotation's own id -- that belongs to the
        ;; annotation, so a cross-reference to it lands on the note
        ;; rather than on its toggle. `emit-document` supplies a counter
        ;; per document; a caller who assembled a ctx without one (this
        ;; namespace's own tests do) still gets a usable, if unnumbered,
        ;; id rather than an exception.
        toggle-id (str "sticky-note-toggle-"
                       (if-let [counter (:sticky-note-counter ctx)] (swap! counter inc) 1))]
    [(str "<span" (attr-attrs attr) ">"
          "<input class=\"sticky-note-toggle\" type=\"checkbox\" id=\"" toggle-id "\"/>"
          "<label class=\"sticky-note-marker\" for=\"" toggle-id "\">note</label>"
          "<span class=\"sticky-note-body\">" body-html "</span>"
          "</span>")
     footnotes']))

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

(defn- render-book-ref
  "Renders BookRef `inline` (doc-10 \"Emitters\", TASK-156) to `[html
  footnotes']`: `<a class=\"book-ref\" href=\"url\">text</a>` when
  resolution gave it a `:url` (its library has a URL template), else
  `<span class=\"book-ref\">text</span>` -- the class either way, so a
  stylesheet can tell a book link from an ordinary one. `text` is the
  resolved `:text` (`??` for a reference that failed to resolve, the
  placeholder a dangling `@fig:x` prints too); a node no resolution ever
  ran over (no `:text`, e.g. the CLI before it is given a library
  directory) shows its `:ref` as written, so it neither throws nor
  vanishes. `:url` reaches here only through `haselnuss.sanitize`, which
  drops an unsafe-scheme one, and is attribute-escaped like a Link's."
  [ctx {:keys [text url] :as inline} footnotes]
  (let [[html footnotes'] (if text
                            (render-inlines ctx text footnotes)
                            [(escape-html (:ref inline)) footnotes])]
    [(if url
       (str "<a class=\"book-ref\" href=\"" (escape-html url) "\">" html "</a>")
       (str "<span class=\"book-ref\">" html "</span>"))
     footnotes']))

(def built-in-inline-handlers
  "The built-in `:t -> (fn [ctx inline footnotes] -> [rendered
  footnotes'])` table `render-inline` dispatches through (TASK-88): every
  Inline variant this namespace's own docstring documents, contributed as
  data rather than as `case` branches, so `emit-document`'s own
  `:inline-handlers` opt can merge in a node type neither this file nor
  `haselnuss.emit.latex` has ever heard of, for one document, with no
  edit here (AC #2/#4).

  Most entries reference an existing render function directly, since its
  own signature already matches this table's `(ctx inline footnotes)`
  contract (`render-note`, `render-cite`); the rest are small adapter
  `fn`s wrapping a narrower-signatured helper that needs no `ctx`/
  `footnotes` of its own (`render-math-inline`, `render-cross-ref`), or
  inlining what used to be a one-line `case` body directly."
  {:str (fn [_ctx inline footnotes] [(escape-html (:text inline)) footnotes])
   :space (fn [_ctx _inline footnotes] [" " footnotes])
   :soft-break (fn [_ctx _inline footnotes] ["\n" footnotes])
   :line-break (fn [_ctx _inline footnotes] ["<br/>" footnotes])
   :emph (fn [ctx inline footnotes] (render-wrapped ctx inline footnotes "<em>" "</em>"))
   :strong (fn [ctx inline footnotes] (render-wrapped ctx inline footnotes "<strong>" "</strong>"))
   ;; A stylistic strikethrough, not a "this was deleted" edit annotation
   ;; -- `<s>` carries presentation semantics, unlike `<del>`.
   :strike (fn [ctx inline footnotes] (render-wrapped ctx inline footnotes "<s>" "</s>"))
   ;; No native HTML small-caps element; an inline `font-variant` style
   ;; renders correctly with no dependency on an external stylesheet.
   :small-caps (fn [ctx inline footnotes]
                 (render-wrapped ctx inline footnotes
                                 "<span style=\"font-variant: small-caps;\">" "</span>"))
   :sub (fn [ctx inline footnotes] (render-wrapped ctx inline footnotes "<sub>" "</sub>"))
   :sup (fn [ctx inline footnotes] (render-wrapped ctx inline footnotes "<sup>" "</sup>"))
   :code (fn [_ctx inline footnotes]
           [(str "<code>" (escape-html (:text inline)) "</code>") footnotes])
   :link (fn [ctx inline footnotes]
           (render-wrapped ctx inline footnotes
                           (str "<a href=\"" (escape-html (:target inline)) "\""
                                (title-attr inline)
                                (attr-attrs (:attr inline)) ">")
                           "</a>"))
   :image (fn [_ctx inline footnotes]
            [(str "<img src=\"" (escape-html (:src inline)) "\" alt=\""
                  (escape-html (:alt inline)) "\""
                  (title-attr inline)
                  (attr-attrs (image-attr (:attr inline))) "/>")
             footnotes])
   :span (fn [ctx inline footnotes]
           (render-wrapped ctx inline footnotes
                           (str "<span" (attr-attrs (:attr inline)) ">") "</span>"))
   :note render-note
   ;; TASK-120 (doc-8 sec5). `<mark>` is the element for exactly this,
   ;; and it is legible with no stylesheet at all -- which is this
   ;; emitter's own self-contained-single-file contract, and the reason
   ;; a highlight is NOT a `<span class="highlight">`.
   :highlight (fn [ctx inline footnotes]
                (render-wrapped ctx inline footnotes
                                (str "<mark"
                                     (attr-attrs (annotation-attr (:attr inline) "highlight"))
                                     ">")
                                "</mark>"))
   :sticky-note render-sticky-note
   :math-inline (fn [_ctx inline footnotes] [(render-math-inline inline) footnotes])
   :cross-ref (fn [_ctx inline footnotes] [(render-cross-ref inline) footnotes])
   :book-ref render-book-ref
   :cite render-cite})

(defn- unsupported-inline!
  "The declared default `render-inline` dispatches an unrecognized Inline
  `:t` to (TASK-88 AC #3): raises the same `ex-info` (`:type
  ::unsupported-inline`) this namespace has always raised for a variant
  outside its documented scope, naming it -- unchanged from the `case`
  fallthrough this default replaces, so the failure mode a caller already
  handles for keeps working."
  [_ctx inline _footnotes]
  (throw (ex-info
          (str "html emitter does not support inline type " (pr-str (:t inline)))
          {:type ::unsupported-inline :inline inline})))

(defn- render-inline
  "Renders one Inline (`haselnuss.ast/Inline`) to `[html footnotes']`
  (`footnotes` threaded per this namespace's own docstring): looks
  `inline`'s own `:t` up in `ctx`'s `:inline-handlers` table (TASK-88 AC
  #1 -- `built-in-inline-handlers` merged with any document-specific
  additions `emit-document`'s own `:inline-handlers` opt supplied) and
  calls the handler found there, or `unsupported-inline!` when there is
  none."
  [ctx inline footnotes]
  ((get (:inline-handlers ctx) (:t inline) unsupported-inline!) ctx inline footnotes))

(defn- render-inlines
  "`render-inline` folded over every Inline in `inlines`, in order,
  concatenating rendered HTML and threading `footnotes` throughout.

  One StringBuilder for the whole sequence (TASK-258): `(str html
  inline-html)` per Inline copied everything written so far each time, so
  a paragraph on one ~136k-character line took seconds to emit."
  [ctx inlines footnotes]
  (concat-rendered #(render-inline ctx %1 %2) footnotes inlines))

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
  (concat-rendered (fn [block footnotes]
                     (if (and tight (= :para (:t block)))
                       (render-inlines ctx (:inlines block) footnotes)
                       (render-blocks ctx [block] footnotes)))
                   footnotes
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
  [ctx {:keys [ordered start tight items attr]} footnotes]
  (let [tag (if ordered "ol" "ul")
        prefix (:li-id-prefix ctx)
        item-ctx (dissoc ctx :li-id-prefix)
        [items-html footnotes']
        (concat-rendered (fn [[i item-blocks] footnotes]
                           (let [[item-html footnotes'] (render-list-item item-ctx tight item-blocks footnotes)
                                 li-open (str "<li" (when prefix (str " id=\"" (escape-html prefix) "-" (inc i) "\"")) ">")]
                             [(str li-open item-html "</li>") footnotes']))
                         footnotes
                         (map-indexed vector items))]
    [(str "<" tag (when (and ordered start (not= 1 start)) (str " start=\"" start "\""))
          (attr-attrs attr) ">" items-html "</" tag ">") footnotes']))

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

(defn- set-image-alt
  "Returns `node` (a Block/Inline tree) with the `:alt` of every `:image`
  in it replaced by `alt`, NOT descending into a nested `:figure` -- a
  subfigure panel owns its own alt (its own `describe`)."
  [node alt]
  (cond
    (and (map? node) (= :figure (:t node))) node
    (and (map? node) (= :image (:t node))) (assoc node :alt alt)
    (map? node) (update-vals node #(set-image-alt % alt))
    (sequential? node) (into (empty node) (map #(set-image-alt % alt)) node)
    :else node))

(defn- render-figure
  "Renders Figure `block` (sec4.3: `:content`/`:caption`/`:attr`) as
  `<figure attr>{rendered content}<figcaption>{render-caption}</figcaption>
  </figure>` (AC #1) -- `<figcaption>` omitted entirely when
  `render-caption` returns nil (neither a resolved number nor an authored
  caption)."
  [ctx {:keys [content caption attr]} footnotes]
  (let [alt (get-in attr [:props "alt"])
        ;; `alt` is not a valid <figure> attribute and assistive
        ;; technology reads it from <img>: a `describe`d alt moves onto the
        ;; figure's own images (TASK-131).
        attr (update attr :props dissoc "alt")
        content (cond-> content alt (set-image-alt alt))
        [content-html footnotes'] (render-blocks ctx [content] footnotes)
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
  (let [cells (:cells row)
        col-indexes (reductions + 0 (map #(or (:span %) 1) cells))
        [html footnotes']
        (concat-rendered (fn [[col-index cell] footnotes]
                           (render-cell ctx tag colspec col-index cell footnotes))
                         footnotes
                         (map vector col-indexes cells))]
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
        (concat-rendered (fn [row footnotes] (render-row ctx "td" colspec row footnotes))
                         footnotes''
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
        (concat-rendered
         (fn [{:keys [id number heading children]} footnotes]
           (let [[heading-html fs'] (render-inlines ctx (derived-lists/entry-inlines heading)
                                                    footnotes)
                 [children-html fs''] (if (seq children)
                                        (render-toc-entries ctx children fs')
                                        ["" fs'])
                 body (str (when number
                             (str "<span class=\"toc-number\">"
                                  (escape-html number) "</span> "))
                           heading-html)]
             [(str "<li>"
                   (if id
                     (str "<a href=\"#" (escape-html id) "\">" body "</a>")
                     body)
                   children-html
                   "</li>")
              fs'']))
         footnotes
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
        (concat-rendered
         (fn [{:keys [id text caption]} footnotes]
           (let [[caption-html fs'] (render-inlines ctx (derived-lists/entry-inlines caption)
                                                    footnotes)
                 body (str (escape-html text)
                           (when (seq caption) (str ": " caption-html)))]
             [(str "<li>"
                   (if id
                     (str "<a href=\"#" (escape-html id) "\">" body "</a>")
                     body)
                   "</li>")
              fs']))
         footnotes
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

(defrecord Rendered
  ;; TASK-89: the wrapper `ctx`'s `:render-blocks`/`:render-inlines`
  ;; callbacks return -- "this HTML string was already produced by this
  ;; namespace's own render pipeline (which already escaped whatever
  ;; needed it), splice it verbatim" -- see `RenderData`'s own docstring
  ;; for why this is a distinct type from the trust-gated `[:raw ...]`
  ;; rather than the same mechanism: a v2 renderer cannot itself produce
  ;; one just by returning the right-looking data (a plain vector/keyword
  ;; pair is trivial to construct; `->Rendered` is an ordinary function in
  ;; THIS namespace, not one a v2 renderer is handed), and once TASK-90's
  ;; sandbox excludes this namespace from its own allowed-namespace list
  ;; it becomes unreachable outright rather than merely inconvenient --
  ;; adequate for today's threat model (doc-6: "prevent accident and
  ;; casual mischief, not a determined attacker").
  ;;
  ;; TASK-213: being unconstructible is not enough -- `assoc` on a record
  ;; keeps its type, so a renderer handed one could rewrite its `:html`.
  ;; An untrusted renderer's `Rendered` is therefore spliced only when it
  ;; is the very instance a ctx callback issued during that call
  ;; (`haselnuss.registry/issued?`); see `render-data`'s own clause.
           [html])

(def ^:private ^:dynamic *issued*
  "The `haselnuss.registry/issued-fragments` set of the `render-directive`
  call in progress on this thread, for this namespace's OWN v2 renderers
  (`derived-list-renderer`), which build a `Rendered` themselves rather
  than through a ctx callback. A var, not a ctx key, so a renderer handed
  that ctx -- a sandboxed one included -- has no way to reach it."
  nil)

(defprotocol ^:private RenderData
  (render-data [node ctx]
    "Serializes a v2 renderer's return value `node` (TASK-89) to an HTML
    string, applying exactly the escaping/error behavior this namespace's
    own core rendering already gets, over this small vocabulary:

    - `nil` -- `\"\"`.
    - a `String` or `Keyword` -- HTML-escaped text (`escape-html`; a
      Keyword renders as its own bare `name`, escaped the same way).
    - a `Number` -- `str`-ed then escaped, for a renderer that hands back
      a bare count/id/etc. rather than pre-stringifying it.
    - a vector `[:raw s]` -- `s` spliced verbatim, no escaping at all --
      the one way to emit output that has to be genuinely raw (a
      browser-only interactive widget, doc-5's own motivating case) --
      but ONLY when `ctx`'s own `:trusted?` is true (set by
      `render-directive` from the DISPATCHED entry's own `:trusted`,
      which a renderer's own code never controls); otherwise a loud
      `ex-info` naming the directive, never a silent downgrade to escaped
      text and never a silent splice.
    - a vector `[tag attrs? & children]`, `tag` a keyword other than
      `:raw`, `attrs?` an optional leading map that is not a record (a
      leading record, e.g. a `Rendered`, is the first child, TASK-216) -- a hiccup-shaped HTML
      element: `<tag attrs>children</tag>`, `attrs`' own keys/values
      escaped as an ordinary attribute (`element-attrs`), each child
      itself `render-data`-ed and concatenated. Unless `ctx`'s
      `:trusted?` is true (TASK-215), `tag` must be one of
      `untrusted-element-tags` -- anything else is a loud `ex-info` -- and
      an event-handler attribute, a malformed attribute name or a URL
      attribute failing `haselnuss.sanitize`'s scheme test is dropped
      with a warning.
    - any other vector, or any `ISeq` (a `map`/`for`/`list` result) --
      a FRAGMENT: every child `render-data`-ed and concatenated, no
      wrapping tag at all. (A vector fragment whose own first child could
      itself be mistaken for a tag keyword should use `list`/`seq`
      instead, which is never read as an element in any hiccup
      convention -- see this method's own `IPersistentVector` clause.)
    - a `Rendered` (this namespace's own -- see its own docstring) --
      spliced verbatim: it is this namespace's own already-rendered,
      already-escaped output, returned by `ctx`'s
      `:render-blocks`/`:render-inlines` callbacks, never something a v2
      renderer's own code constructs. Unless `ctx`'s `:trusted?` is true,
      only the very instance a callback issued during this call is
      (TASK-213); one rewritten with `assoc`/`merge`/`into`, built some
      other way or kept from another call is a loud `ex-info`
      (`forged-fragment!`).
    - anything else -- a loud `ex-info` naming the value and its class,
      rather than silently stringifying or dropping something a renderer
      almost certainly did not mean to return this way."))

(defn- element-attr-name
  "The literal attribute name a hiccup attrs-map key `k` becomes: a
  Keyword's own bare `name` (`:data-x` -> `\"data-x\"`), else `str`."
  [k]
  (if (keyword? k) (name k) (str k)))

(defn- element-attrs
  "Every escaped ` name=\"value\"` HTML attribute fragment for hiccup
  attrs map `attrs`, concatenated in a fixed, deterministic order: `:id`
  first, then `:class`, then every other key sorted by its own attribute
  name -- the same `id`, then `class`, then the rest order `attr-attrs`
  already uses for a core Block's own Attr (TASK-89's hiccup vocabulary
  deliberately mirrors it rather than depending on hash-map iteration
  order, which Clojure makes no guarantee about). A key mapped to `nil`
  contributes nothing at all -- omitted, not an empty attribute -- the
  same `(when id ...)` convention `attr-attrs` already uses for a Block
  with no id of its own."
  [attrs]
  (let [known [:id :class]
        ordered (concat (filter #(contains? attrs %) known)
                        (sort-by element-attr-name (remove (set known) (keys attrs))))]
    (apply str
           (keep (fn [k]
                   (when-let [v (get attrs k)]
                     (str " " (escape-html (element-attr-name k)) "=\""
                          (escape-html (str v)) "\"")))
                 ordered))))

(def ^:private element-tag-name-re
  "The only shape a hiccup element's own tag name (`render-element`) may
  take: an ASCII letter, then any run of ASCII letters/digits/hyphens --
  every real HTML element name, including a hyphenated custom element
  (`<my-widget>`), and nothing able to break out of the tag position in
  the surrounding markup: no whitespace, `\"`/`'`, `<`/`>`, `/`, `=`, or
  anything else an attribute or a second tag could be smuggled in with.

  This exists because the tag position is structure, not escapable text
  -- `element-attrs` already escapes every attribute name/value, and
  every child goes back through `render-data`, but nothing validated
  `tag` itself before this (TASK-89 review finding #1, reproduced live:
  an UNTRUSTED registration returning `[(keyword \"div onmouseover=..\")
  \"x\"]` spliced that whole string into both the opening and the
  closing tag, no `:raw`/`:trusted?` involved at all -- exactly the
  injection AC #1/#8 and doc-6 decision #3 exist to make structurally
  impossible, not merely filtered). Validating the tag closes it the
  same way `element-attrs` already closes the attribute position: by
  construction, before a single character of it reaches the output,
  rather than by trying to escape a value that is supposed to be HTML
  syntax (a tag name), not text."
  #"[a-zA-Z][a-zA-Z0-9-]*")

(defn- element-tag-name!
  "`tag`'s own `name`, validated against `element-tag-name-re` -- anything
  else raises a loud `ex-info` (`:type ::invalid-element-tag`) naming the
  offending value, rather than splicing it into the output. See
  `element-tag-name-re`'s own docstring for why this check exists at
  all: an untrusted v2 renderer has no way to reach the output through a
  malformed tag, trusted or not, since this throws before `render-element`
  ever builds a string with it."
  [tag]
  (let [n (name tag)]
    (if (re-matches element-tag-name-re n)
      n
      (throw (ex-info
              (str "html emitter cannot use " (pr-str tag) " as a hiccup element tag: a tag "
                   "name must match " (pr-str (str element-tag-name-re)) ", so it cannot inject "
                   "markup outside the tag position it appears in")
              {:type ::invalid-element-tag :tag tag})))))

(def ^:private untrusted-element-tags
  "The elements an untrusted v2 renderer (TASK-215) may build, lower-cased:
  structure, text-level semantics, lists, tables, images and media, and
  `details`/`summary`/`button`/`label`, which do nothing without script.
  Nothing that runs or loads script or styles (`script`, `style`, `link`,
  `noscript`, `template`), changes how the page is read (`base`, `meta`,
  `html`/`head`/`body`/`title`), embeds another document (`iframe`,
  `frame`, `object`, `embed`), submits anything (`form` and its controls),
  switches parsing namespace (`svg`, `math`), or names a custom element,
  whose behaviour is whatever script defines it."
  #{"a" "abbr" "address" "article" "aside" "audio" "b" "bdi" "bdo" "blockquote" "br"
    "button" "caption" "cite" "code" "col" "colgroup" "data" "dd" "del" "details" "dfn"
    "div" "dl" "dt" "em" "figcaption" "figure" "footer" "h1" "h2" "h3" "h4" "h5" "h6"
    "header" "hgroup" "hr" "i" "img" "ins" "kbd" "label" "li" "main" "mark" "meter" "nav"
    "ol" "p" "picture" "pre" "progress" "q" "rp" "rt" "ruby" "s" "samp" "section" "small"
    "source" "span" "strong" "sub" "summary" "sup" "table" "tbody" "td" "tfoot" "th"
    "thead" "time" "tr" "track" "u" "ul" "var" "video" "wbr"})

(def ^:private attribute-name-re
  "What an untrusted renderer's attribute name must look like (TASK-215):
  `element-attrs` escapes a name's `<`/`&`/`\"` but not a space or `=`, so
  a key like `\"x onclick\"` would otherwise become two attributes."
  #"[a-zA-Z_:][-a-zA-Z0-9_:.]*")

(defn- untrusted-element-refusal!
  "Raises the `ex-info` (`:type ::untrusted-element`) an untrusted
  renderer's hiccup gets for an element not in `untrusted-element-tags`."
  [ctx tag-name]
  (let [where (::source-label ctx)]
    (throw (ex-info
            (str "directive " (pr-str (:directive-name ctx)) (when where (str " (" where ")"))
                 "'s renderer returned a <" tag-name "> element, which an untrusted extension "
                 "may not emit -- only --trusted-extensions lifts this")
            (cond-> {:type ::untrusted-element :directive-name (:directive-name ctx)
                     :name tag-name}
              where (assoc :source where))))))

(defn- untrusted-attrs
  "`attrs` with every attribute an untrusted renderer may not set removed
  (TASK-215) -- a malformed name, `is`, an event handler, a URL attribute whose
  URL fails `haselnuss.sanitize`'s scheme test -- each reported through
  `ctx`'s `::report`, the way the document pass reports its own."
  [ctx attrs]
  (let [dropped (volatile! [])
        kept (into {}
                   (remove (fn [[k v]]
                             (when (some? v)
                               (let [n (element-attr-name k)
                                     d (cond
                                         (not (re-matches attribute-name-re n))
                                         {:type :malformed-attribute-name
                                          :name n
                                          :message (str "Dropped the attribute named " (pr-str n)
                                                        ": not a well-formed attribute name.")}
                                         ;; `is` makes a built-in a customized
                                         ;; custom element: whatever script
                                         ;; defines it, like a custom tag.
                                         (= "is" (str/lower-case n))
                                         {:type :custom-element-attribute
                                          :name n
                                          :message (str "Dropped the attribute is=\"" v
                                                        "\": it makes the element a custom element.")}
                                         :else (sanitize/attribute-diagnostic n (str v)))]
                                 (when d (vswap! dropped conj d))))))
                   attrs)]
    (when (seq @dropped)
      ((::report ctx) (map #(sanitize/extension-diagnostic (:directive-name ctx)
                                                           (::source-label ctx) %)
                           @dropped)))
    kept))

(defn- render-element
  "One hiccup element `<tag attrs>children</tag>` -- `tag` a keyword,
  validated by `element-tag-name!` before either tag is built, `rest-args`
  everything after it in the vector `render-data`'s own
  `IPersistentVector` clause matched, an optional leading attrs map then
  every child, each `render-data`-ed against `ctx` and concatenated.

  For an untrusted renderer (TASK-215) the tag must also be one of
  `untrusted-element-tags` (otherwise refused), and the attributes go
  through `untrusted-attrs`.

  Only a plain map is the attrs map (TASK-216): a record is a map too,
  and a `Rendered` from a ctx callback is one, so `[:div rendered]` would
  otherwise render it as `<div html=\"...\">`. A record in that position
  is a child like any other, and goes through `render-data` -- the
  TASK-213 forging check included."
  [tag rest-args ctx]
  (let [[attrs children] (if (and (map? (first rest-args)) (not (record? (first rest-args))))
                           [(first rest-args) (rest rest-args)]
                           [nil rest-args])
        tag-name (element-tag-name! tag)
        untrusted? (not (:trusted? ctx))
        _ (when (and untrusted? (not (contains? untrusted-element-tags (str/lower-case tag-name))))
            (untrusted-element-refusal! ctx tag-name))
        attrs (if (and untrusted? attrs) (untrusted-attrs ctx attrs) attrs)]
    (str "<" tag-name (element-attrs attrs) ">"
         (apply str (map #(render-data % ctx) children))
         "</" tag-name ">")))

(defn- untrusted-raw-output!
  "Raises the `ex-info` (`:type ::untrusted-raw-output`) a `[:raw ...]`
  node in `ctx`'s current v2 renderer's return value gets when that
  renderer's own registry entry is not `:trusted` (`register`'s own
  `:trusted?`, TASK-89 AC #8) -- naming the directive so the build says
  which extension tried to emit unescaped output without the opt-in,
  rather than silently downgrading it to escaped text (which would leave
  a widget's own script mangled and unrunnable) or silently splicing it
  (which is exactly the injection this contract exists to close off)."
  [ctx]
  (throw (ex-info
          (str "directive " (pr-str (:directive-name ctx)) "'s renderer returned a [:raw ...] "
               "node, but its own registry entry is not :trusted -- only a registration with "
               ":trusted? true (haselnuss.registry/register) may emit unescaped output")
          {:type ::untrusted-raw-output :directive-name (:directive-name ctx)})))

(defn- forged-fragment!
  "Raises the `ex-info` (`:type ::forged-rendered`) an untrusted v2
  renderer's return value gets for holding a `Rendered` none of its ctx
  callbacks issued during this call (TASK-213) -- one it rewrote with
  `assoc`/`merge`/`into`, or kept from an earlier call -- naming the
  directive and, when the parser recorded one, where it is in the source."
  [ctx]
  (let [where (::source-label ctx)]
    (throw (ex-info
            (str "directive " (pr-str (:directive-name ctx)) (when where (str " (" where ")"))
                 "'s renderer returned a Rendered fragment its ctx's :render-blocks/:render-inlines "
                 "did not produce in this call (changed with assoc/merge/into, or kept from another "
                 "call), but its own registry entry is not :trusted -- an untrusted renderer may "
                 "return such a fragment only exactly as the callback handed it back")
            (cond-> {:type ::forged-rendered :directive-name (:directive-name ctx)}
              where (assoc :source where))))))

(extend-protocol RenderData
  nil
  (render-data [_ _ctx] "")

  String
  (render-data [s _ctx] (escape-html s))

  clojure.lang.Keyword
  (render-data [k _ctx] (escape-html (name k)))

  Number
  (render-data [n _ctx] (escape-html (str n)))

  Rendered
  (render-data [r ctx]
    (if (or (:trusted? ctx) (registry/issued? (::issued ctx) r))
      (:html r)
      (forged-fragment! ctx)))

  clojure.lang.IPersistentVector
  (render-data [v ctx]
    (cond
      (= :raw (first v)) (if (:trusted? ctx) (second v) (untrusted-raw-output! ctx))
      (keyword? (first v)) (render-element (first v) (rest v) ctx)
      :else (apply str (map #(render-data % ctx) v))))

  clojure.lang.ISeq
  (render-data [s ctx] (apply str (map #(render-data % ctx) s)))

  Object
  (render-data [x _ctx]
    (throw (ex-info
            (str "html emitter cannot serialize a v2 renderer's return value of type "
                 (pr-str (class x)) ": " (pr-str x))
            {:type ::unrenderable-data :value x}))))

(defn- render-directive
  "Dispatches Directive `directive` to its own native `:html` renderer,
  looked up in `registry` (an optional `haselnuss.registry` map, from
  `ctx`) by `directive`'s own `:name` -- see this namespace's own
  docstring for why calling it is this task's job. No registry at all, or
  no renderer registered for `directive`'s name, raises `ex-info` (`:type
  ::unsupported-block`) naming the directive, exactly like any other
  unsupported block type.

  Two renderer contracts share this one dispatch point (TASK-89 --
  `haselnuss.registry/register`'s own `:emit` docstring and
  `renderer-v2`'s docstring have the full split):

  - a v2 renderer (`registry/v2?`) is called `(renderer directive ctx')`,
    `ctx'` this call's own `ctx` plus `:directive-name` (for a clear
    diagnostic), `:trusted?` (this directive's own registry entry, so an
    untrusted extension's `[:raw ...]` attempt is a loud error rather
    than a silent bypass or downgrade -- AC #8) and
    `:render-blocks`/`:render-inlines` -- callbacks a renderer uses to
    render its own nested content through this very emitter (AC #2),
    each closing over a footnotes accumulator of its own so a `:note`
    reached through one still gets a real number in the surrounding
    document's own footnote list, entirely invisibly to the renderer
    itself (it only ever sees the returned `Rendered` HTML, never
    `footnotes` at all). The renderer's return value is DATA this
    namespace serializes and escapes via `render-data`/`RenderData`
    (AC #1) rather than a string spliced verbatim, which is what makes
    injection through it structurally impossible rather than merely
    filtered.
  - a legacy renderer (anything else callable) is called `(renderer
    directive :html)`, its returned string spliced in verbatim,
    `footnotes` unchanged -- exactly this namespace's own pre-TASK-89
    behavior, preserved so a renderer registered before TASK-89 is not
    broken by it (AC #7)."
  [{:keys [registry] :as ctx} {directive-name :name :as directive} footnotes]
  (let [entry (some-> registry (registry/lookup directive-name))
        renderer (registry/renderer entry :html)]
    (cond
      (and (ifn? renderer) (registry/v2? renderer))
      (let [footnotes-atom (atom footnotes)
            issued (registry/issued-fragments)
            trusted? (boolean (:trusted entry))
            where (ast/source-label directive)
            ;; TASK-215: captured here, on the calling thread, so a callback
            ;; a sandboxed renderer runs on its worker still reports.
            report! (sanitize/untrusted-reporter)
            vet (sanitize/fragment-vetter directive trusted?)
            ctx' (assoc ctx
                        :directive-name directive-name
                        :trusted? trusted?
                        :render-blocks (fn [blocks]
                                         (let [[html fs'] (render-blocks ctx (vet :blocks blocks)
                                                                         @footnotes-atom)]
                                           (reset! footnotes-atom fs')
                                           (registry/issue! issued (->Rendered html))))
                        :render-inlines (fn [inlines]
                                          (let [[html fs'] (render-inlines ctx (vet :inlines inlines)
                                                                           @footnotes-atom)]
                                            (reset! footnotes-atom fs')
                                            (registry/issue! issued (->Rendered html)))))
            result (binding [*issued* issued] (renderer directive ctx'))]
        ;; `issued` and `report!` reach `render-data` only through this
        ;; ctx, which the renderer never sees (TASK-213, TASK-215).
        [(render-data result (assoc ctx'
                                    ::issued issued
                                    ::report report!
                                    ::source-label where))
         @footnotes-atom])

      (ifn? renderer)
      [(renderer directive :html) footnotes]

      :else
      (throw (ex-info
              (str "html emitter has no native renderer for directive " (pr-str directive-name))
              {:type ::unsupported-block :block directive})))))

(defn- derived-list-renderer
  "`render-derived-list`, adapted to TASK-89's v2 contract (`registry/
  renderer-v2` wraps this before `register-derived-lists` installs it):
  `[directive ctx] -> Rendered`, rather than `[ctx spec directive
  footnotes] -> [html footnotes']`.

  Deliberately the thinnest possible adapter, not a rewrite into hiccup:
  `render-derived-list` and everything it calls
  (`render-toc-entries`/`render-float-list-entries`/`attr-attrs`/
  `escape-html`) already are this namespace's own core code, already
  escaping through the exact same functions core Block rendering does --
  wrapping its unmodified output in `Rendered` is honest about that
  (\"this namespace already rendered and escaped this\"), rather than
  re-expressing already-correct, already-tested string-building as
  hiccup for no safety benefit. `footnotes` is always `[]` in and ignored
  out: a list entry's own Inlines are always `derived-lists/entry-inlines`
  first, which strips every `:note` out by construction (see its own
  docstring), so a list can never itself introduce a footnote."
  [directive ctx]
  (let [spec (derived-lists/spec directive)]
    (registry/issue! *issued* (->Rendered (first (render-derived-list ctx spec directive []))))))

(defn register-derived-lists
  "Registers a real `:html` renderer (`derived-list-renderer`, TASK-89)
  for every list-placeholder name `haselnuss.extensions.derived-lists`
  defines (`\"toc\"`/`\"list-of-figures\"`/`\"list-of-tables\"`), merging
  into an existing entry of the same name exactly the way
  `haselnuss.emit.latex/register-environments` already does for its own
  per-target mapping -- so registering this does not silently discard a
  same-named entry's own `:kind`/`:lower`/other-target `:emit`. A name
  with no existing entry is registered fresh, with no `:kind` (a list is
  not a numbering target, `haselnuss.extensions.derived-lists`'s own
  docstring) and no `:lower` (it has a representation on every target
  this project emits).

  This is what makes `render-directive` need no special case for a list
  placeholder ahead of the registry any more (TASK-89 AC #3): dispatch
  finds a real, `renderer-v2`-marked renderer through the ordinary
  registry lookup, the same way it finds any other extension's own."
  [registry]
  (reduce (fn [reg list-name]
            (if-let [entry (registry/lookup reg list-name)]
              (assoc reg list-name (assoc-in entry [:emit :html]
                                             (registry/renderer-v2 derived-list-renderer)))
              (registry/register reg {:name list-name
                                      :emit {:html (registry/renderer-v2 derived-list-renderer)}})))
          registry
          (sort (keys derived-lists/lists))))

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

(defn- render-section
  "Renders Section `block` (sec4.3: `:level`/`:heading`/`:blocks`/`:attr`)
  as `<section attr><hN>number heading</hN>body</section>` (`tag` per
  `heading-tag`, the number per `section-number-html`, TASK-41).

  The level is `:level` moved by `ctx`'s `:level-shift` (TASK-156: set
  inside a quoted passage, see `render-book-quote`), and the body is
  rendered with `:section-level` set to it, so a quote inside this
  Section knows how deep it sits."
  [ctx block footnotes]
  (let [[heading-html footnotes'] (render-inlines ctx (:heading block) footnotes)
        level (max 1 (+ (:level block) (:level-shift ctx 0)))
        tag (heading-tag level)
        ;; TASK-20: the generated bibliography Section's own direct List
        ;; gets each <li> tagged for render-cite's own anchors to target
        ;; -- see render-list's own docstring.
        section-ctx (cond-> (assoc ctx :section-level level)
                      (and (:bibliography-id ctx)
                           (= (:bibliography-id ctx) (get-in block [:attr :id])))
                      (assoc :li-id-prefix (:bibliography-id ctx)))
        [body-html footnotes''] (render-blocks section-ctx (:blocks block) footnotes')]
    [(str "<section" (attr-attrs (:attr block)) ">"
          "<" tag ">" (section-number-html ctx (:attr block)) heading-html "</" tag ">"
          body-html "</section>")
     footnotes'']))

(defn- render-para
  "Renders Para `block` (sec4.3: `:inlines`) as `<p>...</p>`."
  [ctx block footnotes]
  (let [[html footnotes'] (render-inlines ctx (:inlines block) footnotes)]
    [(str "<p>" html "</p>") footnotes']))

(defn- render-book-quote
  "Renders BookQuote `block` (doc-10 \"Emitters\", TASK-156) to `[html
  footnotes']`: the quoted passage in `<blockquote class=\"book-quote\"
  data-source=\"canonical\" cite=\"url\">`, each attribute only when
  resolution supplied it, followed by the source line `— title` when it
  supplied a `:title` -- a link when there is a `:url`.

  The source line is attribution, and the HTML standard puts attribution
  OUTSIDE the `<blockquote>`, grouping the two in a `<figure>` with the
  attribution as its `<figcaption>`; `<cite>` marks the title of the
  work. So a resolved quote is `<figure class=\"book-quote-figure\">`
  around both, and the stylesheet keeps that figure's content flush left
  (`figure` is centred for images) and sets its caption on the right.

  A quote that failed to resolve has no `:canonical` and its `:blocks`
  are the resolver's placeholder paragraph `?? ref`, which renders inside
  a bare blockquote like any passage, so it is visible. A node no quote
  expansion ever ran over (no `:blocks`) shows its `:ref` as written the
  same way.

  Inside the passage (`ctx`):

  - quoted headings move by `haselnuss.emit.quote/level-shift`, so the
    shallowest sits one level below the host Section around the quote;
  - a citation is not linked into the host's bibliography
    (`:citation-positions` emptied): it was formatted against the
    book's own bibliography, so its number names an entry there, and a
    link to the host's entry for the same key would show one number and
    jump to another."
  [ctx {:keys [canonical title url blocks] :as block} footnotes]
  (let [inner-ctx (-> ctx
                      (assoc :level-shift (book-quote/level-shift (:section-level ctx 0) blocks))
                      (assoc :citation-positions {}))
        [body footnotes'] (if blocks
                            (render-blocks inner-ctx blocks footnotes)
                            [(str "<p>" (escape-html (:ref block)) "</p>") footnotes])
        quote-html (str "<blockquote class=\"book-quote\""
                        (when canonical (str " data-source=\"" (escape-html canonical) "\""))
                        (when url (str " cite=\"" (escape-html url) "\""))
                        ">" body "</blockquote>")]
    (if (seq title)
      (let [[title-html footnotes''] (render-inlines ctx title footnotes')
            linked (if url
                     (str "<a href=\"" (escape-html url) "\">" title-html "</a>")
                     title-html)]
        [(str "<figure class=\"book-quote-figure\">" quote-html
              "<figcaption>— <cite>" linked "</cite></figcaption></figure>")
         footnotes''])
      [quote-html footnotes'])))

(defn- render-block-quote
  "Renders BlockQuote `block` (sec4.3: `:blocks`, `:attr` optional) as
  `<blockquote attr>...</blockquote>`, or as `<div attr>` (TASK-139)
  when the node carries the in-memory `:container` marker --
  `:container :note` additionally writes `role=\"note\"`.

  `haselnuss.cli` sets the marker on every BlockQuote it makes as the
  vehicle for a lowered directive (theorem-like, admonition, a float's
  grouping, a panel row): the AST has no generic container Block, and a
  `<blockquote>` makes assistive technology and reader modes announce a
  note or a figure grid as a quotation. The marker is a node key, not a
  class or prop, so an author cannot set it from `.hdoc` source -- an
  authored `.proof` class on a real quote stays a `<blockquote>`. It is
  not part of the JSON interchange form; lowering happens in memory
  just before emission. `attr-attrs` here, not a bare
  `<blockquote>`: BlockQuote carries an `Attr` like every other
  attr-bearing Block, and dropping it silently cost any BlockQuote its id
  -- so a resolved cross-reference to one emitted a link to an anchor
  that existed nowhere in the document. Reachable in practice: a
  directive degraded to a BlockQuote by a registry `:lower` rule keeps
  the directive's own id (see `haselnuss.cli`)."
  [ctx block footnotes]
  (let [[html footnotes'] (render-blocks ctx (:blocks block) footnotes)
        tag (if (:container block) "div" "blockquote")
        role (when (= :note (:container block)) " role=\"note\"")]
    [(str "<" tag (attr-attrs (:attr block)) role ">" html "</" tag ">") footnotes']))

(def built-in-block-handlers
  "The built-in `:t -> (fn [ctx block footnotes] -> [rendered
  footnotes'])` table `render-block` dispatches through (TASK-88): every
  Block variant `haselnuss.ast` defines except `:include` (Section/Para/
  List/CodeBlock/BlockQuote/Directive/Figure/Table/MathBlock, plus
  TASK-37's `:thematic-break`), plus `:include` itself -- see this
  namespace's own docstring for why it raises `::unresolved-include`
  rather than rendering. Contributed as data rather than as `case`
  branches (AC #2), so `emit-document`'s own `:block-handlers` opt can
  merge in a node type this file has never heard of, for one document,
  with no edit here (AC #4).

  Most entries reference an existing render function directly, since its
  own signature already matches this table's `(ctx block footnotes)`
  contract (`render-section`, `render-para`, `render-list`,
  `render-figure`, `render-table`, `render-block-quote`,
  `render-directive`); the rest are small adapter `fn`s wrapping a
  narrower-signatured helper (`render-code-block`/`render-math-block`
  need no `footnotes` of their own) or a fixed string/throw."
  {:section render-section
   :para render-para
   :list render-list
   :code-block (fn [_ctx block footnotes] [(render-code-block block) footnotes])
   :math-block (fn [ctx block footnotes] [(render-math-block ctx block) footnotes])
   :figure render-figure
   :table render-table
   :block-quote render-block-quote
   :directive render-directive
   ;; TASK-37 AC #1. HTML's own semantic element for a thematic break
   ;; between sections of content -- self-closed, since this
   ;; namespace's own test suite parses its output as XML.
   :thematic-break (fn [_ctx _block footnotes] ["<hr/>" footnotes])
   :book-quote render-book-quote
   :include (fn [_ctx block _footnotes]
              (throw (ex-info (unresolved-include-message block)
                              {:type ::unresolved-include :block block})))})

(defn- unsupported-block!
  "The declared default `render-block` dispatches an unrecognized Block
  `:t` to (TASK-88 AC #3): raises the same `ex-info` (`:type
  ::unsupported-block`) this namespace has always raised for a variant
  outside its documented scope, naming it -- unchanged from the `case`
  fallthrough this default replaces, so the failure mode a caller already
  handles for keeps working."
  [_ctx block _footnotes]
  (throw (ex-info
          (str "html emitter does not support block type " (pr-str (:t block)))
          {:type ::unsupported-block :block block})))

(defn- render-block
  "Renders one Block (`haselnuss.ast/Block`) to `[html footnotes']`:
  looks `block`'s own `:t` up in `ctx`'s `:block-handlers` table (TASK-88
  AC #1 -- `built-in-block-handlers` merged with any document-specific
  additions `emit-document`'s own `:block-handlers` opt supplied) and
  calls the handler found there, or `unsupported-block!` when there is
  none."
  [ctx block footnotes]
  ((get (:block-handlers ctx) (:t block) unsupported-block!) ctx block footnotes))

(defn- render-blocks
  "`render-block` folded over every Block in `blocks`, in order,
  concatenating rendered HTML and threading `footnotes` throughout."
  [ctx blocks footnotes]
  (concat-rendered #(render-block ctx %1 %2) footnotes blocks))

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
  (concat-rendered #(render-front-matter-block ctx %1 %2) footnotes blocks))

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
    :code (:text inline)
    :image (:alt inline)
    :math-inline (:tex inline)
    :cross-ref (or (:text inline) "")
    :cite (plain-text (:text inline))
    ;; Every remaining Inline that carries nested Inlines -- `:emph`,
    ;; `:strong`, `:span`, `:link` and, since TASK-116, `:highlight` --
    ;; contributes its children's text. Asked of `haselnuss.walk/fields-
    ;; holding` rather than listed by name: this was a hardcoded list of
    ;; eight variants, and a `:highlight` in a document's own title
    ;; silently contributed nothing to `<title>` the moment one could be
    ;; written. A variant with no such field (`:space` and the two break
    ;; types are handled above; `:note`'s Blocks are not Inlines) has no
    ;; fields to fold over and still contributes "".
    (apply str (map (fn [field] (plain-text (get inline field)))
                    (walk/fields-holding ::ast/inline inline ::ast/inline)))))

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

(def ^:private mathjax-config
  "The inline `<script>` setting `window.MathJax` that `emit-document`
  writes immediately before `mathjax-script` (TASK-217). MathJax reads
  the global when its own script runs, so this one must come first; it
  is a classic synchronous script and the CDN one is `async`, so it
  always has.

  Without it a document's own math could make script: MathJax v3's TeX
  input autoloads its `html` extension, so `$\\href{javascript:...}{x}$`
  became a live `javascript:` link and `\\class`/`\\cssId`/`\\style`
  wrote attributes onto the page, getting around the promise
  `haselnuss.sanitize` makes for the document (README \"Converting a
  document you do not trust\"). Each setting closes a different door,
  read off the MathJax 3.2.2 source -- the exact version
  `mathjax-script` pins, so these claims stay true -- and checked by
  rendering in headless Chrome against that CDN build:

  - `tex.autoload.html = []` -- `\\href`, `\\class`, `\\cssId` and
    `\\style` stay undefined, so they render as an undefined-macro
    error rather than loading the extension.
  - `tex.autoload.unicode = []` -- `\\unicode[font]{N}` (the extension's
    only macro) copies its font argument into the `fontfamily`
    attribute, which reaches the page's CSS unfiltered (see below).
  - `tex.packages` minus `require` -- `\\require{html}` would otherwise
    load the same extension by name (or any other).
  - `ui/safe` with `safeOptions.allow.URLs = 'none'` -- the base
    package's own `\\mmlToken{mi}[href=\"...\"]{x}` sets an `href`
    without any extension, and the safe extension is what filters the
    MathML attributes TeX produces. With its defaults it also drops an
    `id` or `class` not starting `mjx-`, and passes a `style` attribute
    through the browser's own CSS parser keeping only an allow-list of
    properties (colour, border, margin, padding, font and a few more)
    with bounded lengths and no `javascript:`/`data:` -- which is what
    removes a `\\bbox` style's `background:url(...)`.
    URLs are dropped outright, not left at safe's own `'safe'`: that
    mode reads the scheme with `/^\\s*([a-z]+):/i`, so `java<TAB>script:`
    or a leading control character reads as schemeless and is kept,
    and the browser then strips those characters and runs it. Ordinary
    math makes no links: a document's references are its own
    CrossRefs, outside the math, and a MathJax `\\eqref` inside it would
    lose only its link.
  - `startup.ready` adds one filter safe lacks. MathJax's HTML output
    joins a token's `fontfamily`, `fontweight` and `fontstyle` into its
    `style` attribute as raw text, and safe checks none of the three,
    so `\\mmlToken{mi}[fontfamily=\"x;position:fixed;...\"]{x}` could
    cover the page and fetch a third-party image. The hook runs
    MathJax's own `defaultReady` (which builds the document and its
    `safe` object) and then maps those three attributes on that object
    to a filter that keeps a value only if it is letters, digits,
    spaces, commas, hyphens and underscores -- a plain font-family
    list, nothing that can end a declaration or open a `url(`. Quotes
    are refused too: once `\\unicode` is gone no ordinary TeX sets these
    attributes at all, and an unbalanced quote could swallow the
    declarations after it. Typesetting starts only after `ready`
    returns, and if `safe` failed to load the hook throws and MathJax
    typesets nothing, so it fails closed.

  Every key here is an exact MathJax v3 option name; a misspelt one is
  only a console warning, so `haselnuss.emit.html-test` pins the string.
  It must stay well-formed XML (no `<` or `&`). A Content-Security-
  Policy that forbids inline script would block this config, but the
  same policy also blocks a `javascript:` link from running."
  (str "<script>window.MathJax={"
       "loader:{load:['ui/safe']},"
       "tex:{packages:{'[-]':['require']},autoload:{html:[],unicode:[]}},"
       "options:{safeOptions:{allow:{URLs:'none'}}},"
       "startup:{ready:function(){"
       "MathJax.startup.defaultReady();"
       "var s=MathJax.startup.document.safe;"
       "s.filterMethods.filterFontToken=function(_,v){return /^[-\\w ,]*$/.test(v)?v:null;};"
       "['fontfamily','fontweight','fontstyle'].forEach(function(a){s.filterAttributes.set(a,'filterFontToken');});"
       "}}"
       "};</script>"))

(def ^:private mathjax-script
  "The MathJax v3 CDN `<script>` tag `emit-document` includes in
  `<head>` when the output has math (TASK-130) -- see this namespace's
  own docstring, and `mathjax-config` for the inline configuration that
  always precedes it (TASK-217). Pinned to an exact release, 3.2.2,
  not the floating `mathjax@3`: what `mathjax-config` guarantees was
  read off that version's source, and a later 3.x could add a macro or
  change a filter underneath it.
  `async=\"async\"` (the explicit-value boolean-attribute form), not a
  bare `async`, since this namespace's own test suite (and XML in
  general) requires every attribute to have a value -- a bare `async`
  parses fine in an actual HTML5 browser but is not well-formed XML;
  `async=\"async\"` is well-formed XML *and* HTML5's own documented
  boolean-attribute convention, so a browser still treats it as present/
  truthy exactly the same way."
  (str "<script id=\"MathJax-script\" async=\"async\""
       " src=\"https://cdn.jsdelivr.net/npm/mathjax@3.2.2/es5/tex-mml-chtml.js\"></script>"))

(defn- has-math?
  "True when rendered HTML `html` contains a math span or div."
  [html]
  (boolean (re-find #"class=\"math (?:inline|display)" html)))

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
    ;; TASK-139: those directives are <div>s (see `render-block-quote`),
    ;; not <blockquote>s, so the quote look is given to both.
    "blockquote,div.admonition,div.theorem,div.lemma,div.corollary,"
    "div.definition,div.proof{margin:1.5rem 0;padding:.1rem 1rem;"
    "border-left:3px solid rgba(128,128,128,.4)}"
    "div.admonition{background:rgba(128,128,128,.08);font-style:italic}"
    "div.theorem,div.lemma,div.corollary,"
    "div.definition,div.proof{background:rgba(128,128,128,.08)}"
    "div.theorem p:first-child,div.lemma p:first-child,"
    "div.corollary p:first-child,div.definition p:first-child,"
    "div.proof p:first-child,div.admonition p:first-child{margin-top:.6rem}"
    "div.theorem p:last-child,div.lemma p:last-child,"
    "div.corollary p:last-child,div.definition p:last-child,"
    "div.proof p:last-child,div.admonition p:last-child{margin-bottom:.6rem}"
    ;; TASK-156: a quoted passage and its source line share a <figure>
    ;; (see `render-book-quote`), which must not centre the passage the
    ;; way `figure` below centres an image; the source line sits right.
    "figure.book-quote-figure{text-align:left}"
    "figure.book-quote-figure figcaption{text-align:right;font-style:normal}"
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
    ;; fit. They are plain <div>s (TASK-139), which carry no quote
    ;; styling, so there is nothing to undo.
    "div.subfigures{margin:0}"
    ;; A grid, not a flex row: the row carries its own track count as an
    ;; inline style (see `haselnuss.cli/panel-rows`), so a trailing
    ;; partial row leaves its empty tracks empty and its panel keeps the
    ;; width its siblings have -- which is the width the LaTeX side
    ;; gives every panel. Flex-grow stretched a lone last panel across
    ;; the full line while the PDF kept it at half (found by review).
    "div.subfigure-row{display:grid;gap:1rem;align-items:end;margin:0}"
    ;; A descendant selector, not a `>` child one: this namespace's own
    ;; documented stylesheet constraint is that its tests parse the
    ;; output as XML, where a bare `>` inside <style> is invalid. The
    ;; only figures inside a row are its own panels anyway.
    "div.subfigure-row figure{margin:.5rem 0}"
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
    "img{max-width:100%}"
    ;; TASK-120 (doc-8 sec5), annotations. `<mark>` already has a UA
    ;; background, so these rules only replace it per colour; a highlight
    ;; is legible with this whole sheet removed, which is why it is a
    ;; `<mark>` in the first place. The colours are translucent so they
    ;; read over either canvas without a prefers-color-scheme block, the
    ;; same rule every other colour here lives under.
    ;; `<mark>` already has a UA background, so these rules only replace
    ;; it per colour; a highlight is legible with this whole sheet
    ;; removed, which is why it is a `<mark>` in the first place. The
    ;; alphas are the lowest that keep every colour above WCAG AA against
    ;; BOTH canvases -- measured on each, since the sheet has no
    ;; prefers-color-scheme block by design and one set of values has to
    ;; serve both.
    "mark.highlight{padding:0 .1em;border-radius:2px;color:inherit}"
    "mark.highlight-yellow{background:rgba(255,225,90,.55)}"
    "mark.highlight-green{background:rgba(130,220,130,.55)}"
    "mark.highlight-blue{background:rgba(130,190,255,.55)}"
    "mark.highlight-pink{background:rgba(255,150,195,.55)}"
    ;; The balloon. A hidden checkbox plus its own <label> rather than a
    ;; <details>, because everything a sticky note emits sits inside a
    ;; <p> and <details> is flow content, not phrasing -- see
    ;; `render-sticky-note`, where that is measured rather than argued.
    ;; `position:relative` on the wrapper is what the body is positioned
    ;; against.
    "span.sticky-note{position:relative}"
    ;; Hidden without display:none, which would take it out of the
    ;; keyboard's reach: the label is still clickable, and the checkbox
    ;; itself is still focusable and toggles with the space bar.
    "input.sticky-note-toggle{position:absolute;width:1px;height:1px;opacity:0}"
    "label.sticky-note-marker{cursor:pointer;font-size:.75em;vertical-align:super;"
    "padding:0 .3em;border:1px solid rgba(128,128,128,.6);border-radius:3px}"
    "span.sticky-note-yellow label{background:rgba(255,225,90,.55)}"
    "span.sticky-note-green label{background:rgba(130,220,130,.55)}"
    "span.sticky-note-blue label{background:rgba(130,190,255,.55)}"
    "span.sticky-note-pink label{background:rgba(255,150,195,.55)}"
    "span.sticky-note-body{display:none}"
    ;; `background:Canvas` rather than a fixed colour: the sheet declares
    ;; `color-scheme:light dark`, so the system canvas colour is already
    ;; the right ground under either preference, and an opaque balloon is
    ;; required -- it sits ON TOP of the text it is about.
    "input.sticky-note-toggle:checked ~ span.sticky-note-body{display:block;position:absolute;"
    "left:0;top:1.6em;z-index:10;width:22rem;max-width:80vw;"
    "padding:.5rem .75rem;font-size:.92em;background:Canvas;color:CanvasText;"
    "border:1px solid rgba(128,128,128,.5);border-radius:4px;"
    "box-shadow:0 2px 8px rgba(0,0,0,.25)}"
    ;; Each Block of the body is a bare <span> (phrasing content, so the
    ;; enclosing paragraph survives); this is what makes them read as the
    ;; blocks they are.
    "span.sticky-note-body span{display:block;margin:.4em 0}"]))

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
  document always has a `<title>`) and, when the document has math, the MathJax CDN `<script>` tag
  (`mathjax-script`) preceded by its inline configuration
  (`mathjax-config`, TASK-217), and a `<body>` holding the rendered Blocks (AC
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
  such a placeholder prints its heading over an empty list), and
  (TASK-88) `:block-handlers`/`:inline-handlers` -- extra `:t -> (fn [ctx
  node footnotes] -> [rendered footnotes'])` entries merged ON TOP of
  `built-in-block-handlers`/`built-in-inline-handlers` for this call
  alone, so a caller can render a Block/Inline `:t` this namespace has
  never heard of without editing it (or override a built-in entry): see
  `render-block`/`render-inline`. Freshly merged every call, into a plain
  local map handed only to this one document's `ctx` -- nothing here is
  a process-global table, so one document's addition never reaches a
  later, unrelated `emit-document` call in the same process.

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
              ;; TASK-89: which target a v2 renderer is being asked to
              ;; render for -- available on `ctx` for a renderer that
              ;; wants it (e.g. a diagnostic, or a renderer shared
              ;; between both `:emit` targets branching on it), though
              ;; none of this project's own built-in extensions need to,
              ;; since `:emit {:html f :latex g}` already separates by
              ;; target before either is ever called.
              :target :html
              :labels (:labels opts {})
              :bibliography-id (:bibliography-id opts)
              :citation-positions (citation-positions (:ordered-keys opts))
              ;; TASK-88: merged fresh for this one call, never mutating
              ;; built-in-block-handlers/built-in-inline-handlers
              ;; themselves -- see emit-document's own docstring.
              :block-handlers (merge built-in-block-handlers (:block-handlers opts))
              :inline-handlers (merge built-in-inline-handlers (:inline-handlers opts))
              ;; TASK-120: one counter per DOCUMENT, so every sticky
              ;; note's own checkbox gets an id no other one in the same
              ;; page shares. Derived from the footnote count before
              ;; this, which sticky notes do not contribute to -- so
              ;; every note in a document got the SAME id, every <label>
              ;; pointed at the first checkbox, and clicking any balloon
              ;; opened that one instead (found by review, in this
              ;; project's own shipped example). Built here rather than
              ;; held in a var: this is per-call state, and nothing in
              ;; this namespace is process-global.
              :sticky-note-counter (atom 0)
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
          (stylesheet-tag (:stylesheet opts))
          (when (and (not (false? (:mathjax opts)))
                     (has-math? (str title-html front-html body
                                     (footnotes-section footnotes))))
            ;; TASK-217: the config first -- MathJax reads it on load.
            (str mathjax-config mathjax-script))
          "</head>"
          "<body>" title-html front-html body (footnotes-section footnotes) "</body>"
          "</html>"))))

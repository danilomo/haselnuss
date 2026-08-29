;; reveal.clj -- a worked example of a TRUSTED sidecar extension (TASK-92).
;;
;; TASK-90 shipped author-defined directives loaded from a sidecar .clj file
;; via `--extensions`, but its own worked example (haselnuss.cli-test's
;; `callout` directive) deliberately stays UNTRUSTED, to prove an ordinary
;; sidecar extension can already produce rich, structured output with no
;; special privilege at all. This file is the other half: `reveal`, a small,
;; self-contained, browser-only "click to see the answer" widget that
;; genuinely needs the `:trusted`/`haselnuss.ext/raw` escape hatch doc-6
;; describes, and a print target that does not pretend a PDF has a click to
;; defer to.
;;
;; Try it (from the repository root, matching this project's own README
;; "Example" section -- build into a scratch directory, never beside the
;; sources):
;;
;;   mkdir -p examples/out
;;   lein run -- examples/reveal-demo.hdoc --extensions examples/reveal.clj \
;;     --trusted-extensions --output examples/out/reveal-demo.html
;;   lein run -- examples/reveal-demo.hdoc --target latex \
;;     --extensions examples/reveal.clj --trusted-extensions \
;;     --output examples/out/reveal-demo.tex
;;
;; and, to see the trust gate refuse the identical document when the sidecar
;; file is loaded WITHOUT that flag (the same ::untrusted-raw-output error
;; TASK-89 already gives a host-defined untrusted renderer, not a second,
;; different mechanism):
;;
;;   lein run -- examples/reveal-demo.hdoc --extensions examples/reveal.clj \
;;     --output examples/out/reveal-demo.html
;;
;; THE DIRECTIVE: `:::{reveal question="..." answer="..."}` -- both a plain
;; string in `attr.props`, mirroring `haselnuss.extensions.collapsable`'s own
;; `summary=` convention. No nested `:blocks` needed; the directive's whole
;; body is these two strings.
;;
;; WHY THE HTML SIDE ACTUALLY NEEDS :raw, not just uses it for flavor: the
;; visible question/button/answer markup below is ordinary hiccup -- ANY
;; sidecar extension, trusted or not, can build a `<button>`/`<span>` shell
;; this way, exactly like `callout`'s own `<div>`. What an untrusted renderer
;; cannot do is make that button DO anything without a real inline `<script>`
;; element, and a `<script>` element's body is not safely representable as an
;; ordinary hiccup child string at all: a browser never HTML-entity-decodes a
;; `<script>` body (it is HTML "raw text," the same treatment `<style>`
;; gets), so ANY escaping `haselnuss.emit.html`'s own `escape-html` applied --
;; not merely the specific `"` -> `&quot;` this script's own `querySelector`
;; calls would trigger -- would reach the page as literal characters rather
;; than the JavaScript they are meant to be. `[:raw ...]` is
;; the one way to splice it verbatim, and only a `:trusted` registration
;; (`--trusted-extensions`, decided by whoever loads this file -- never by
;; this file itself; see `haselnuss.extensions.sidecar`'s own docstring) may
;; return one at all.
;;
;; WHY THE LATEX SIDE IS NOT A DOWN-RENDERED COPY: a PDF reader has no click
;; to defer the answer to, so hiding it and revealing it later on the same
;; page would only be a worse version of just printing both. The `:lower`
;; rule below -- not a native `:latex` renderer -- degrades a `reveal`
;; directly to two ordinary ("Q: ...", "A: ...") Paragraphs, the question
;; immediately followed by its answer, the same way
;; `haselnuss.extensions.collapsable`'s own `:lower` rule flattens its hidden
;; content for a static target rather than trying to reproduce the widget.
;; Because those come back as core `:para`/`:strong`/`:str` nodes, not this
;; file's own TeX text, `haselnuss.emit.latex`'s OWN rendering (`\textbf`,
;; `escape-tex`) does the formatting -- no raw TeX, and no :trusted needed on
;; this half at all.

(defn- prop
  "`directive`'s own `attr.props[k]`, or `\"\"` if unset -- a `reveal`
  authored with a missing `question=`/`answer=` degrades to an empty prompt
  rather than throwing, mirroring `haselnuss.extensions.collapsable/summary`'s
  own tolerance of a missing `summary=`."
  [directive k]
  (get-in directive [:attr :props k] ""))

(defn question
  "`directive`'s own `question=` text, or `\"\"` if unset."
  [directive]
  (prop directive "question"))

(defn answer
  "`directive`'s own `answer=` text, or `\"\"` if unset."
  [directive]
  (prop directive "answer"))

(def ^:private reveal-script
  "The one inline `<script>`, identical for every `reveal` instance on the
  page (it takes no per-instance text at all -- the question/answer strings
  live only in the ordinary, auto-escaped hiccup around it, never inside
  this string), so nothing here is built from directive-authored text and
  there is nothing here for an author's own `question=`/`answer=` to inject
  into. `document.currentScript.parentElement` is this script's own
  enclosing `<div class=\"reveal\">` -- the last element built before it in
  `render-html` -- so each widget wires up its OWN button/answer pair with
  no id and no page-global state, and several `reveal`s on one page cannot
  collide."
  (str "<script>(function () {\n"
       "  var widget = document.currentScript.parentElement;\n"
       "  var button = widget.querySelector(\".reveal-button\");\n"
       "  var answer = widget.querySelector(\".reveal-answer\");\n"
       "  button.addEventListener(\"click\", function () {\n"
       "    answer.hidden = false;\n"
       "    button.hidden = true;\n"
       "  });\n"
       "}());</script>"))

(defn render-html
  "This extension's native `:html` renderer (TASK-89's v2 contract, `(fn
  [directive ctx] -> data)`): an ordinary hiccup shell -- the question, a
  button, and the initially-`hidden` answer, each plain auto-escaped text,
  no different from what an UNTRUSTED renderer could already build -- plus
  `reveal-script` spliced in via `haselnuss.ext/raw` as this `<div>`'s last
  child, which is the one part that needs `:trusted` (see this file's own
  header comment for exactly why)."
  [directive _ctx]
  [:div {:class "reveal"}
   [:p {:class "reveal-question"} (question directive)]
   [:button {:type "button" :class "reveal-button"} "Show answer"]
   [:p {:class "reveal-answer" :hidden "hidden"} (answer directive)]
   (haselnuss.ext/raw reveal-script)])

(defn lower-rule
  "This extension's registry `:lower` rule: degrades a `reveal` to two
  ordinary Paragraphs -- \"Q: <question>\" then \"A: <answer>\", both always
  visible -- for any target with no native renderer (`:latex` today; see
  this file's own header comment for why this is a distinct fallback rather
  than a down-rendered widget). `haselnuss.lower/lower-directive` runs this
  automatically once no `:emit` entry matches the current target; a
  `reveal` with neither would otherwise abort the build on that target with
  `::no-representation`."
  [directive _target]
  [{:t :para :inlines [{:t :strong :inlines [{:t :str :text "Q: "}]}
                        {:t :str :text (question directive)}]}
   {:t :para :inlines [{:t :strong :inlines [{:t :str :text "A: "}]}
                        {:t :str :text (answer directive)}]}])

{:name "reveal"
 :emit {:html render-html}
 :lower lower-rule}
